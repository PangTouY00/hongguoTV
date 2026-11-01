// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.Looper
import com.hongguotv.core.CachedEpisode
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.EpisodeCache
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.Rendition
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.Executors

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

    class Task(val episodeId: String, val quality: Int) {
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

    /** 把一批分集排进队列。已经缓存过的、已经在队列里的直接跳过，不重复下载。 */
    fun enqueue(episodeIds: List<String>, quality: Int) {
        var added = false
        synchronized(this) {
            episodeIds.forEach { id ->
                if (cache.cached(id) != null || known.containsKey(id)) return@forEach
                val task = Task(id, quality)
                known[id] = task; pending.addLast(task); added = true
            }
        }
        if (added) { notify(); pump() }
    }

    /** 取消一集：还在排队的直接撤掉，正在下载的中断当前分段并保留已写的部分，下次接着写。 */
    fun cancel(episodeId: String) {
        val video = synchronized(this) {
            val task = known[episodeId] ?: return
            if (running === task) task.canceled = true else { pending.remove(task); known.remove(episodeId) }
            activeVideo
        }
        video?.cancel()
        notify()
    }

    fun cancelAll() {
        val video = synchronized(this) {
            pending.toList().forEach { known.remove(it.episodeId) }
            pending.clear()
            running?.canceled = true
            activeVideo
        }
        video?.cancel()
        notify()
    }

    /** 删除已缓存的文件。正在下载的那一集同时取消。 */
    fun remove(episodeId: String): Int {
        cancel(episodeId)
        val removed = cache.remove(episodeId)
        notify(); return removed
    }

    fun clear(): Int {
        cancelAll()
        val removed = cache.clear()
        notify(); return removed
    }

    private fun pump() {
        worker.execute {
            var failures = 0
            while (true) {
                val task = synchronized(this) { pending.removeFirstOrNull() } ?: break
                synchronized(this) { running = task }
                notify()
                try { download(task); failures = 0 }
                catch (error: Exception) {
                    synchronized(this) { task.state = State.FAILED; task.percent = 0
                        task.message = error.message ?: error.javaClass.simpleName }
                    failures++
                    android.util.Log.w("HongguoTV", "Episode cache failed episode=${task.episodeId}: ${error.javaClass.simpleName} ${error.message}")
                }
                synchronized(this) { running = null; if (task.canceled) known.remove(task.episodeId) }
                notify()
                // 连续三次都失败说明不是某一集的问题，停下来，别把整部剧都试一遍。
                if (failures >= 3) { synchronized(this) { pending.toList().forEach { known.remove(it.episodeId) }; pending.clear() }; break }
            }
        }
    }

    private fun download(task: Task) {
        synchronized(this) { task.state = State.RUNNING; task.percent = 0 }
        val ladder = repository.streamLadder(task.episodeId)
        val rendition = ladder.select(task.quality, supportsRendition) ?: throw IOException("这一集没有可缓存的清晰度")
        val video = RemoteVideo(http, rendition.toStreamInfo())
        synchronized(this) { activeVideo = video }
        try {
            video.prepare()
            if (!cache.hasRoom(video.total)) throw IOException("存储空间不足，还剩 ${cache.freeBytes() / 1024 / 1024} MB 可用")
            var reportedAt = 0L
            cache.store(task.episodeId, rendition.quality, rendition.codec, video.total,
                read = { at, length -> video.read(at, length) },
                progress = { done, total ->
                    // 每 2% 或每半秒报一次：一集几十 MB 按 MB 报会让界面每秒重绘十几次。
                    val percent = (done * 100 / total).toInt()
                    val now = System.currentTimeMillis()
                    if (percent >= task.percent + 2 || done == total || now - reportedAt >= 500) {
                        task.percent = percent; reportedAt = now; notify()
                    }
                },
                canceled = { task.canceled })
            synchronized(this) { known.remove(task.episodeId) }
            android.util.Log.i("HongguoTV", "Episode cached episode=${task.episodeId} quality=${rendition.quality} bytes=${video.total}")
        } finally { video.close(); synchronized(this) { if (activeVideo === video) activeVideo = null } }
    }

    private fun notify() { main.post(listener) }

    fun close() { worker.shutdownNow() }
}
