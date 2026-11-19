// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.PriorityQueue
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** Page-owned artwork requests. Public methods are called on the UI thread; decoding stays on workers. */
class ArtworkLoader(context: Context) {
    companion object {
        const val PRIORITY_FOCUSED = 0
        const val PRIORITY_VISIBLE = 1
        const val PRIORITY_PREFETCH = 2
        private const val MAX_PENDING = 24
        private const val MAX_BYTES = 4 * 1024 * 1024
    }

    private data class Key(val url: String, val width: Int, val height: Int)
    private class Binding(view: ImageView, val owner: Long, val url: String, var priority: Int,
                          val width: Int, val height: Int, val onApplied: (()->Unit)?) {
        val view = WeakReference(view)
        var key: Key? = null
        var applied: Key? = null
        var failed: Key? = null
        lateinit var layout: View.OnLayoutChangeListener
        lateinit var attach: View.OnAttachStateChangeListener
    }
    private class Job(val key: Key, val owner: Long, var priority: Int, val order: Long) {
        val listeners = linkedSetOf<Binding>()
        /** 不属于某一页的取图（跨页面的过渡层）：换页不取消它，结果直接交回调用方。 */
        val callbacks = mutableListOf<(Bitmap?) -> Unit>()
        @Volatile var standalone = false
        @Volatile var cancelled = false
        @Volatile var call: Call? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val lowRam = context.applicationContext.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    private val cacheBytes = (if (lowRam) 4 else 8) * 1024 * 1024
    private val cache = object : LruCache<Key, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    private val disk = ArtworkCache(File(context.applicationContext.cacheDir, "artwork-v1"))
    // This client owns its dispatcher and pool; cancelling artwork never cancels video or catalog calls.
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val workers = Executors.newFixedThreadPool(if (lowRam) 1 else 2) { runnable ->
        Thread(runnable, "hongguotv-artwork").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val signal = Object()
    private val pending = PriorityQueue<Job>(11,compareBy<Job> { it.priority }.thenBy { it.order })
    private val jobs = mutableMapOf<Key, Job>()
    private val bindings = WeakHashMap<ImageView, Binding>()
    private var owner = 0L
    private var order = 0L
    @Volatile private var closed = false

    init { repeat(if (lowRam) 1 else 2) { workers.execute(::work) } }

    /** Size defaults to the laid-out content bounds, including the detail page's portrait artwork. */
    fun load(view: ImageView, url: String, priority: Int = PRIORITY_VISIBLE, widthPx: Int = 0, heightPx: Int = 0,
             onApplied: (()->Unit)? = null) {
        if (closed) return
        val previous = bindings[view]
        if (previous != null && previous.owner == owner && previous.url == url && previous.width == widthPx &&
            previous.height == heightPx && previous.onApplied === onApplied) {
            previous.priority = priority.coerceIn(PRIORITY_FOCUSED, PRIORITY_PREFETCH)
            previous.failed = null
            request(previous)
            return
        }
        if (previous != null) unbind(previous)
        view.setImageDrawable(null)
        if (url.isBlank()) return
        val binding = Binding(view, owner, url, priority.coerceIn(PRIORITY_FOCUSED, PRIORITY_PREFETCH), widthPx, heightPx, onApplied)
        binding.layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> request(binding) }
        binding.attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { request(binding) }
            override fun onViewDetachedFromWindow(v: View) { unbind(binding) }
        }
        bindings[view] = binding
        view.addOnLayoutChangeListener(binding.layout)
        view.addOnAttachStateChangeListener(binding.attach)
        request(binding)
    }

    /**
     * 取一张不属于任何页面的图：拿到就交回调用方自己持有，换页（[cancelPage]）不会取消它。
     * 「预览 → 全屏」的过渡层需要这种图——它要在换页之后继续留在屏幕上，
     * 而按 View 绑定的取图会在换页时被清掉（画面只剩一层黑，就是黑屏）。
     */
    fun requestBitmap(url: String, widthPx: Int, heightPx: Int, done: (Bitmap?) -> Unit) {
        if (closed) return
        val key = sizedKey(url, widthPx, heightPx)
        if (key == null) { main.post { done(null) }; return }
        cache.get(key)?.let { cached -> main.post { done(cached) }; return }
        var cancelled: Job? = null
        synchronized(signal) {
            // 同一张图已经在排着或正在读：并进去，不再花第二次网络往返。
            jobs[key]?.let { job ->
                job.standalone = true
                job.callbacks += done
                if (PRIORITY_FOCUSED < job.priority) {
                    val queued = pending.remove(job)
                    job.priority = PRIORITY_FOCUSED
                    if (queued) pending.add(job)
                }
                return
            }
            if (pending.size >= MAX_PENDING) {
                val worst = pending.maxWithOrNull(compareBy<Job> { it.priority }.thenBy { -it.order })!!
                if (worst.priority < PRIORITY_FOCUSED) return
                cancelLocked(worst)
                cancelled = worst
            }
            val job = Job(key, owner, PRIORITY_FOCUSED, ++order).apply { standalone = true; callbacks += done }
            jobs[key] = job
            pending += job
            signal.notifyAll()
        }
        cancelled?.call?.cancel()
    }

    /** 想要多大 → 一个安全的缓存键：最长边不超过 1024，不让老电视去解一张全屏大图。 */
    private fun sizedKey(url: String, widthPx: Int, heightPx: Int): Key? {
        if (url.isBlank() || widthPx <= 0 || heightPx <= 0) return null
        val scale = min(1.0, 1024.0 / max(widthPx, heightPx))
        return Key(url, max(1, (widthPx * scale).toInt()), max(1, (heightPx * scale).toInt()))
    }

    private fun request(binding: Binding) {
        val view = binding.view.get() ?: return
        if (closed || binding.owner != owner || bindings[view] !== binding) return
        val width = if (binding.width > 0) binding.width else view.width - view.paddingLeft - view.paddingRight
        val height = if (binding.height > 0) binding.height else view.height - view.paddingTop - view.paddingBottom
        if (width <= 0 || height <= 0) return
        // A malformed layout cannot ask an old TV to allocate a full-screen, multi-megapixel thumbnail.
        val key = sizedKey(binding.url, width, height) ?: return
        if (binding.key != key) { detachJob(binding); binding.key = key; binding.failed = null }
        if (binding.applied == key || binding.failed == key) return
        cache.get(key)?.let { bitmap -> apply(binding, view, bitmap); return }
        var cancelled: Job? = null
        synchronized(signal) {
            jobs[key]?.let { job ->
                job.listeners += binding
                if (binding.priority < job.priority) {
                    val queued = pending.remove(job)
                    job.priority = binding.priority
                    if (queued) pending.add(job)
                }
                return
            }
            if (pending.size >= MAX_PENDING) {
                val worst = pending.maxWithOrNull(compareBy<Job> { it.priority }.thenBy { -it.order })!!
                if (worst.priority < binding.priority) return
                cancelLocked(worst)
                cancelled = worst
            }
            val job = Job(key, owner, binding.priority, ++order)
            job.listeners += binding
            jobs[key] = job
            pending += job
            signal.notifyAll()
        }
        cancelled?.call?.cancel()
    }

    private fun apply(binding: Binding, view: ImageView, bitmap: Bitmap) {
        view.setImageBitmap(bitmap)
        binding.applied = binding.key
        binding.onApplied?.invoke()
    }

    private fun cancelLocked(job: Job) {
        job.cancelled = true
        pending.remove(job)
        if (jobs[job.key] === job) jobs.remove(job.key)
        job.listeners.clear()
    }

    private fun detachJob(binding: Binding) {
        val key = binding.key ?: return
        val cancelled = synchronized(signal) {
            jobs[key]?.takeIf { it.owner == binding.owner }?.let { job ->
                job.listeners.remove(binding)
                if (job.listeners.isEmpty()) { cancelLocked(job); job } else null
            }
        }
        cancelled?.call?.cancel()
    }

    private fun unbind(binding: Binding) {
        binding.view.get()?.let { view ->
            view.removeOnLayoutChangeListener(binding.layout)
            view.removeOnAttachStateChangeListener(binding.attach)
            if (bindings[view] === binding) bindings.remove(view)
        }
        detachJob(binding)
        binding.view.clear()
    }

    /** Release every old-page listener and bitmap binding, including queued and in-flight requests. */
    fun cancelPage(clearMemory: Boolean = false) {
        owner++
        val old = bindings.values.toList()
        old.forEach { binding -> binding.view.get()?.setImageDrawable(null); unbind(binding) }
        val cancelled = synchronized(signal) {
            // 跨页面的那张图不属于任何一页：换页不能取消它，换页之后它还要留在屏幕上。
            val pageJobs = jobs.values.filter { !it.standalone }
            pageJobs.forEach(::cancelLocked)
            signal.notifyAll()
            pageJobs
        }
        cancelled.forEach { it.call?.cancel() }
        if (clearMemory) cache.evictAll()
    }

    fun cancelAll(clearMemory: Boolean = false) = cancelPage(clearMemory)

    @Suppress("DEPRECATION") // Android 8 devices still report the running-memory pressure levels.
    fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> cache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.trimToSize(cacheBytes / 2)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        cancelPage(clearMemory = true)
        synchronized(signal) { signal.notifyAll() }
        workers.shutdownNow()
        // TLS socket pool disposal can block and must not happen on the UI thread.
        Thread({
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }, "hongguotv-artwork-cleanup").start()
    }

    private fun work() {
        while (!closed) {
            val job = try {
                synchronized(signal) {
                    while (pending.isEmpty() && !closed) signal.wait()
                    if (closed) null else pending.poll()
                }
            } catch (_: InterruptedException) { return } ?: return
            val bitmap = try {
                if (job.cancelled) null else cache.get(job.key) ?: fetch(job)
            } catch (_: Exception) { null } catch (_: OutOfMemoryError) { cache.evictAll(); null }
            val handed = synchronized(signal) {
                if (jobs[job.key] === job) jobs.remove(job.key)
                if (!job.cancelled && !closed && bitmap != null) cache.put(job.key, bitmap)
                // 一次把两类要图的人都交清楚：绑到 View 的按页面归属交（换页之后就不再交），
                // 跨页面的那种直接交回调用方，它自己负责把图留在屏幕上。
                val asked = job.callbacks.toList().also { job.callbacks.clear() }
                val bound = job.listeners.toList().also { job.listeners.clear() }
                asked to bound
            }
            if (!job.cancelled && !closed && (handed.first.isNotEmpty() || handed.second.isNotEmpty())) main.post {
                handed.first.forEach { it(bitmap) }
                if (job.owner == owner && !closed) {
                    handed.second.forEach { binding ->
                        val view = binding.view.get()
                        if (view != null && bindings[view] === binding && binding.key == job.key) {
                            if (bitmap != null) apply(binding, view, bitmap)
                            else binding.failed = job.key
                        }
                    }
                    refillVisible()
                }
            }
        }
    }

    // Dropped preloads must not leave a visible card blank when a queue slot becomes free.
    private fun refillVisible() {
        val visible = Rect()
        bindings.values.toList().filter { binding ->
            binding.applied != binding.key && binding.failed != binding.key && binding.view.get()?.getGlobalVisibleRect(visible) == true
        }.sortedBy { it.priority }.take(MAX_PENDING).forEach(::request)
    }

    private fun fetch(job: Job): Bitmap? {
        val bytes = disk.get(job.key.url) ?: run {
            if (job.cancelled) return null
            val call = http.newCall(Request.Builder().url(job.key.url).build())
            job.call = call
            if (job.cancelled) { call.cancel(); return null }
            call.execute().use { response ->
                val body = response.body ?: return null
                if (!response.isSuccessful || body.contentLength() > MAX_BYTES) return null
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                body.byteStream().use { input ->
                    while (!job.cancelled) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > MAX_BYTES) return null
                        out.write(buffer, 0, count)
                    }
                }
                if (job.cancelled) return null
                out.toByteArray().also { disk.put(job.key.url, it) }
            }
        }
        if (job.cancelled) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) return null
        // Fit the entire source into the measured target. Cropping at decode time cannot be
        // undone by ImageView.FIT_CENTER and used to remove most of a portrait poster.
        val fit = min(1.0, min(job.key.width.toDouble() / bounds.outWidth, job.key.height.toDouble() / bounds.outHeight))
        val width = max(1, (bounds.outWidth * fit).toInt())
        val height = max(1, (bounds.outHeight * fit).toInt())
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        if ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > (if (lowRam) 2 else 4) * 1024 * 1024) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (job.cancelled) { decoded.recycle(); return null }
        val result = try { Bitmap.createScaledBitmap(decoded, width, height, true) }
        catch (problem: Throwable) { decoded.recycle(); throw problem }
        if (result !== decoded) decoded.recycle()
        if (job.cancelled) { result.recycle(); return null }
        return result
    }
}
