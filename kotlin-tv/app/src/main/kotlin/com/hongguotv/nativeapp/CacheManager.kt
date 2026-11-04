// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.Looper
import com.hongguotv.core.CachedEpisode
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.Detail
import com.hongguotv.core.EpisodeCache
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.Rendition
import com.hongguotv.core.Series
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.Executors

/** 空间不够：换一档清晰度不会让磁盘变大，直接停并把原因报给用户。 */
internal class NotEnoughSpace(message: String) : IOException(message)
/** 这一集本来就没有能用的清晰度：换档也没有可换的。 */
internal class NoRenditionException(message: String) : IOException(message)
/** 缓存目录建不出来或写不进去：换清晰度、换一集都不会改变结果，直接停并把位置报出来。 */
internal class CacheStorageException(message: String) : IOException(message)

/**
 * 缓存任务的编排：一次只下一集，按点下的顺序排队。
 *
 * 这里没有用 WorkManager。下载是用户在选集面板里当场点下的，进度要立刻回到同一块屏幕，
 * 取消也要当场生效；系统调度、跨进程重启与 Doze 下的延迟在这个场景里没有收益，反而会让
 * 「点了缓存但界面没反应」变成常态。串行一次一集也和成熟下载库的做法一致——并发下载
 * 打在同一组 CDN 上并不会更快，只会让正在播放的那一集更慢。
 */
class CacheManager(
    private val cache: EpisodeCache,
    private val repository: ContentRepository,
    private val http: OkHttpClient,
    private val supportsRendition: (Rendition) -> Boolean,
    private val listener: () -> Unit,
) {
    enum class State { QUEUED, RUNNING, FAILED }

    /** 任务带着它属于哪部剧、是这部剧的第几集：落盘时把归属与集号写进元数据，「已下载」列表才认得出
     *  剧名，断网时由缓存拼出来的剧集列表才显示得出「第几集」。 */
    class Task(val episodeId: String, val episodeIndex: Int, val quality: Int, val series: Series) {
        @Volatile var state = State.QUEUED
        @Volatile var percent = 0
        @Volatile var message = ""
        @Volatile var canceled = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-cache") }
    private val pending = ArrayDeque<Task>()
    private val known = LinkedHashMap<String, Task>()
    private var running: Task? = null
    private var activeVideo: RemoteVideo? = null

    /** 这一集在缓存队列里的状态；不在队列里返回 null（是否已缓存由 EpisodeCache 回答）。 */
    fun task(episodeId: String): Task? = synchronized(this) { known[episodeId] }

    /** 队列里还有多少集在等或在跑。 */
    fun pendingCount(): Int = synchronized(this) { pending.size + (if (running == null) 0 else 1) }

    fun cached(): List<CachedEpisode> = cache.records()
    fun cachedCount(): Int = cache.records().size
    fun bytesUsed(): Long = cache.bytesUsed()
    fun freeBytes(): Long = cache.freeBytes()

    /** 队列里失败的任务：界面要写清是哪一集、为什么失败，别只留一个「失败」。 */
    fun failures(): List<Task> = synchronized(this) { known.values.filter { it.state == State.FAILED } }

    /**
     * 还在排队或正在下载的任务，带着它们属于哪部剧。
     *
     * 「本机下载」原来只列已经落盘的集，于是点了「缓存全部」以后那一页还是空的——看不出有没有在干活。
     * 这一份让正在缓存的剧当场出现，并带上还剩几集、当前这一集的百分比。
     */
    fun activeTasks(): List<Task> = synchronized(this) { known.values.filter { it.state == State.QUEUED || it.state == State.RUNNING } }

    /** 把一批分集排进队列。episodes 是（集号, 分集 id）：集号进缓存元数据，离线时用它显示第几集。
     *  已经缓存过的、已经在队列里的直接跳过，不重复下载。 */
    fun enqueue(series: Series, episodes: List<Pair<Int, String>>, quality: Int) {
        // 一次问清哪些已经缓存过。原来每集问一次，每集都要扫一遍缓存目录：排五十集就是五十遍。
        val already = cache.cachedIds()
        var added = false
        synchronized(this) {
            episodes.forEach { (index, id) ->
                if (already.contains(id)) return@forEach
                val existing = known[id]
                if (existing != null) {
                    // 失败过的重新排一次。界面上写着「失败」却怎么点都不动，最容易被当成整个功能坏了。
                    if (existing.state == State.FAILED) {
                        existing.state = State.QUEUED; existing.percent = 0; existing.message = ""; existing.canceled = false
                        pending.addLast(existing); added = true
                    }
                    return@forEach
                }
                val task = Task(id, index, quality, series)
                known[id] = task; pending.addLast(task); added = true
            }
        }
        if (added) { notifyUi(); pump() }
    }

    /** 取消一集：还在排队的直接撤掉，正在下载的中断当前分段并保留已写的部分，下次接着写。 */
    fun cancel(episodeId: String) {
        val video = synchronized(this) {
            val task = known[episodeId] ?: return
            // 只有正在下载的那一集才需要在途请求被断掉；排队中的任务还没开始，没有可断的东西。
            if (running === task) { task.canceled = true; activeVideo } else { pending.remove(task); known.remove(episodeId); null }
        }
        video?.close()
        notifyUi()
    }

    fun cancelAll() {
        val video = synchronized(this) {
            pending.toList().forEach { known.remove(it.episodeId) }
            pending.clear()
            if (running != null) { running?.canceled = true; activeVideo } else null
        }
        video?.close()
        notifyUi()
    }

    /** 删除已缓存的文件。正在下载的那一集同时取消。 */
    fun remove(episodeId: String): Int {
        cancel(episodeId)
        val removed = cache.remove(episodeId)
        notifyUi(); return removed
    }

    fun clear(): Int {
        cancelAll()
        val removed = cache.clear()
        notifyUi(); return removed
    }

    private fun pump() {
        worker.execute {
            var failures = 0
            while (true) {
                val task = synchronized(this) { pending.removeFirstOrNull() } ?: break
                synchronized(this) { running = task }
                notifyUi()
                try { download(task); failures = 0 }
                // 连内存不够这种也接住：这一集下不成，界面写「失败」并说明原因，比整个应用被系统关掉好。
                catch (error: Throwable) {
                    synchronized(this) { task.state = State.FAILED; task.percent = 0
                        task.message = error.message ?: error.javaClass.simpleName }
                    failures++
                    android.util.Log.w("HongguoTV", "Episode cache failed episode=${task.episodeId}: ${error.javaClass.simpleName} ${error.message}")
                }
                synchronized(this) { running = null; if (task.canceled) known.remove(task.episodeId) }
                notifyUi()
                // 连续三次都失败说明不是某一集的问题，停下来，别把整部剧都试一遍。
                if (failures >= 3) { synchronized(this) { pending.toList().forEach { known.remove(it.episodeId) }; pending.clear() }; break }
            }
        }
    }

    /**
     * 缓存一集：照播放同一套阶梯往下试。
     *
     * 播放那条路本来就是「先按设置选，取不到首段就退到更低一档」；缓存原来只试选中的那一档，
     * 于是同一集会出现播得出来、缓存却报失败。换档缓存的是另一份资源，元数据里记的就是
     * 实际落盘的那一档，离线播放照旧按本地文件播。
     */
    private fun download(task: Task) {
        synchronized(this) { task.state = State.RUNNING; task.percent = 0 }
        val ladder = repository.streamLadder(task.episodeId)
        val order = ladder.playbackOrder(task.quality, supportsRendition)
        if (order.isEmpty()) throw NoRenditionException("这一集没有可缓存的清晰度")
        var last: Exception? = null
        for (rendition in order) {
            try { store(task, rendition); return }
            catch (error: Exception) {
                last = error
                android.util.Log.w("HongguoTV", "Cache attempt failed episode=${task.episodeId} quality=${rendition.quality} codec=${rendition.codec}: ${error.javaClass.simpleName} ${error.message}")
                // 取消、空间不足、目录不可用、这一集本来就没有资源：换清晰度不会改变结果，别白试一遍。
                if (task.canceled || error is NotEnoughSpace || error is CacheStorageException || error is NoRenditionException) throw error
            }
        }
        throw last ?: IOException("这一集没有可缓存的清晰度")
    }

    private fun store(task: Task, rendition: Rendition) {
        // 目录不可用时换清晰度、换一集都没有区别，先问它，省掉一次已经知道会失败的请求。
        cache.unusable()?.let { throw CacheStorageException(it) }
        val video = RemoteVideo(http, rendition.toStreamInfo())
        synchronized(this) { activeVideo = video }
        // 落盘按整块要字节、整块解密。上一轮这里改成「一次开一大段、流式消费」，本机实测反而慢 90 倍：
        // 9.5 MB 一集，每 1 MB 一次往返 1.3 秒，流式那段要 120 秒。慢的不是往返——服务端 keep-alive，
        // 一次往返几乎不花时间；慢的是流式读取每几十 KB 就调一次解密，每次都要新建一次 AES 上下文。
        // 播放侧照旧用流式（它按播放进度要字节，量级小，不影响流畅）。
        try {
            video.prepare()
            if (!cache.hasRoom(video.total)) throw NotEnoughSpace("存储空间不足，还剩 ${cache.freeBytes() / 1024 / 1024} MB 可用")
            var reportedAt = 0L
            cache.store(task.episodeId, rendition.quality, rendition.codec, video.total,
                read = { at, length -> video.read(at, length) },
                progress = { done, total ->
                    // 每 2% 或每半秒报一次：一集几十 MB 按 MB 报会让界面每秒重绘十几次。
                    val percent = (done * 100 / total).toInt()
                    val now = System.currentTimeMillis()
                    if (percent >= task.percent + 2 || done == total || now - reportedAt >= 500) {
                        task.percent = percent; reportedAt = now; notifyUi()
                    }
                },
                canceled = { task.canceled },
                seriesId = task.series.id, seriesTitle = task.series.title, cover = task.series.cover,
                episodeIndex = task.episodeIndex)
            synchronized(this) { known.remove(task.episodeId) }
            android.util.Log.i("HongguoTV", "Episode cached episode=${task.episodeId} quality=${rendition.quality} bytes=${video.total}")
        } finally {
            video.close(); synchronized(this) { if (activeVideo === video) activeVideo = null }
        }
    }

    /**
     * 界面要刷新，但同一时刻只排一次。
     *
     * 进度每半秒报一次，逐条 post 会把主线程的消息队列排满：刷新本身要花时间，排在后面的点击
     * 事件等不到处理，五秒不响应系统就报 ANR。已经排着就不再排——下一次刷新看到的是当时的最新状态。
     */
    private val notifyQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun notifyUi() {
        if (!notifyQueued.compareAndSet(false, true)) return
        main.post { notifyQueued.set(false); listener() }
    }

    fun close() { worker.shutdownNow() }
}

/** 摘要行里要写的失败原因：地址不带进界面，也别长到把整行挤掉。 */
internal fun safeCacheError(message: String): String =
    message.replace(Regex("https?://\\S+"), "[地址]").take(120).ifBlank { "未知原因" }

/**
 * 选集面板上方那一行摘要。
 *
 * 队列在跑就报进度；队列停下来是因为失败，就把失败写清楚、把原因带出来。原来这一行只在队列有任务
 * 时显示，失败之后整行空掉——用户只看到格子上一个「失败」，不知道为什么，也不知道还能不能再试。
 */
internal fun cacheSummaryText(pending: Int, cached: Int, bytesUsed: Long, failures: List<Pair<String, String>>): String? {
    val used = bytesUsed / 1024 / 1024
    if (pending > 0) return "缓存队列 · 还有 $pending 集 · 已缓存 $cached 集 · 占用 $used MB"
    val first = failures.firstOrNull() ?: return null
    val more = if (failures.size > 1) " ${failures.size} 集" else ""
    return "缓存失败$more · 原因：${first.second.ifBlank { "未知原因" }} · 再点一次可以重试"
}

/**
 * 断网时的详情：由本地缓存记录拼出这部剧的剧集列表。
 *
 * 集号来自缓存元数据——缓存了第 145 集，列表第一项仍然是第 145 集，不会冒充第 1 集；旧版本写的
 * 缓存没记集号（-1），写进 episodeNumbers 是 0，界面按「已缓存」标注。这部剧一集都没缓存时返回
 * null，让调用方照旧报网络错误，不要凭空造出一个空列表。
 */
internal fun offlineDetailOf(series: Series, cached: List<CachedEpisode>): Detail? {
    val rows = cached.filter { it.seriesId == series.id }
    if (rows.isEmpty()) return null
    // 有集号的按集号排，没集号的排到最后：位置不确定的内容不该挤到已知集号中间。
    val ordered = rows.sortedWith(compareBy({ if (it.episodeIndex >= 0) it.episodeIndex else Int.MAX_VALUE }, { it.episodeId }))
    val title = series.title.ifBlank { ordered.first().seriesTitle.ifBlank { "剧集 ${series.id}" } }
    val cover = series.cover.ifBlank { ordered.first().cover }
    return Detail(
        Series(series.id, title, cover, series.description, series.badge, series.tags),
        ordered.map { it.episodeId },
        ordered.map { it.episodeIndex + 1 },
    )
}
