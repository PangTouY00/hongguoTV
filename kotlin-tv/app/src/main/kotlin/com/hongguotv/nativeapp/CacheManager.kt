// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.Looper
import com.hongguotv.core.CachedEpisode
import com.hongguotv.core.ContentRepository
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

    /** 任务带着它属于哪部剧：落盘时把归属写进元数据，「已下载」列表才认得出剧名。 */
    class Task(val episodeId: String, val quality: Int, val series: Series) {
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

    /** 把一批分集排进队列。已经缓存过的、已经在队列里的直接跳过，不重复下载。 */
    fun enqueue(series: Series, episodeIds: List<String>, quality: Int) {
        var added = false
        synchronized(this) {
            episodeIds.forEach { id ->
                if (cache.cached(id) != null) return@forEach
                val existing = known[id]
                if (existing != null) {
                    // 失败过的重新排一次。界面上写着「失败」却怎么点都不动，最容易被当成整个功能坏了。
                    if (existing.state == State.FAILED) {
                        existing.state = State.QUEUED; existing.percent = 0; existing.message = ""; existing.canceled = false
                        pending.addLast(existing); added = true
                    }
                    return@forEach
                }
                val task = Task(id, quality, series)
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
                catch (error: Exception) {
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
                seriesId = task.series.id, seriesTitle = task.series.title, cover = task.series.cover)
            synchronized(this) { known.remove(task.episodeId) }
            android.util.Log.i("HongguoTV", "Episode cached episode=${task.episodeId} quality=${rendition.quality} bytes=${video.total}")
        } finally { video.close(); synchronized(this) { if (activeVideo === video) activeVideo = null } }
    }

    private fun notifyUi() { main.post(listener) }

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
