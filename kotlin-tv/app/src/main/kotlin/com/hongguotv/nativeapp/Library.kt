// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hongguotv.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The backup and native library use the same immutable progress shape.
typealias WatchProgress = BackupProgress

/** Memory-only UI access; disk reads, JSON serialization and commits run on workers. */
class Library(context: Context) {
    private companion object {
        // A replacement Activity must not load before its predecessor's final checkpoint commits.
        val retiredWrites = mutableListOf<LatestWriteQueue<String, () -> Any?>>()
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-library-load") }
    private val prefs by lazy { app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE) }
    private val homePrefs by lazy { app.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE) }
    private val writes = LatestWriteQueue<String, () -> Any?>("hongguotv-library-write", write = { batch -> commit(prefs, batch) },
        failed = { Log.w("HongguoTV", "Local library commit failed; pending changes retained for retry") })
    private val homeWrites = LatestWriteQueue<String, () -> Any?>("hongguotv-home-write", write = { batch -> commit(homePrefs, batch) },
        failed = { Log.w("HongguoTV", "Home cache commit failed") })
    private var index: LibraryIndex? = null
    // 逐集已看：播完一集记一笔，只存本机。云端上报的仍是「看到第几集」，这份不跟着走。
    private val episodeIds = LinkedHashMap<String, MutableSet<String>>()
    private val homes = mutableMapOf<ContentType, CatalogPage>()
    private val homeSavedAt = mutableMapOf<ContentType, Long>()
    private var loading = false
    private var closed = false
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val errorCallbacks = mutableListOf<(Throwable) -> Unit>()
    val isLoaded get() = index != null

    /** Call before showing the first page. Successful/error callbacks run on the main thread. */
    fun loadAsync(onFailure: (Throwable) -> Unit = { Log.e("HongguoTV", "Local library failed to load", it) }, onReady: () -> Unit) {
        check(!closed)
        if (isLoaded) { onReady(); return }
        readyCallbacks += onReady
        errorCallbacks += onFailure
        if (loading) return
        loading = true
        loader.execute {
            var episodes: Map<String, MutableSet<String>> = emptyMap()
            val result = runCatching {
                val retired = synchronized(retiredWrites) { retiredWrites.toList() }
                retired.forEach { check(it.awaitClosed(30, TimeUnit.SECONDS)) { "上次观看记录仍在保存，请稍后重试" } }
                synchronized(retiredWrites) { retiredWrites.removeAll(retired.toSet()) }
                val values = prefs.all
                val loaded = LibraryDisk.decode(values)
                episodes = decodeEpisodeWatched(values["watched-episodes"] as? String)
                val now = System.currentTimeMillis()
                val cached = mutableMapOf<ContentType, CatalogPage>()
                val stamps = mutableMapOf<ContentType, Long>()
                val migration = homePrefs.edit()
                val oldKeys = mutableListOf<String>()
                ContentType.entries.forEach { type ->
                    val key = "home-${type.storedValue}"
                    val legacy = values[key] as? String
                    val raw = homePrefs.getString(key, null) ?: legacy.orEmpty()
                    CatalogSnapshot.decode(raw, now)?.let { page ->
                        cached[type] = page
                        stamps[type] = org.json.JSONObject(raw).getLong("savedAt")
                        if (!homePrefs.contains(key)) migration.putString(key, raw)
                    }
                    if (legacy != null) oldKeys += key
                }
                // Only remove legacy cache after the separate cache has been committed.
                if (oldKeys.isNotEmpty() && migration.commit()) {
                    prefs.edit().also { edit -> oldKeys.forEach(edit::remove) }.commit()
                }
                Triple(loaded, cached, stamps)
            }
            main.post {
                loading = false
                if (closed) { readyCallbacks.clear(); errorCallbacks.clear(); return@post }
                val ready = readyCallbacks.toList(); readyCallbacks.clear()
                val errors = errorCallbacks.toList(); errorCallbacks.clear()
                result.onSuccess { (loaded, cached, stamps) ->
                    index = loaded; homes.putAll(cached); homeSavedAt.putAll(stamps); episodeIds.putAll(episodes)
                    ready.forEach { it() }
                }.onFailure { failure -> errors.forEach { it(failure) } }
            }
        }
    }
    private fun state(): LibraryIndex = checkNotNull(index) { "Load the local library before accessing it" }
    private fun put(key: String, value: Any?) { writes.submit(mapOf(key to { value })) }
    private fun persist(vararg keys: String) {
        val state = state()
        val batch = linkedMapOf<String, () -> Any?>()
        keys.forEach { key ->
            // Each lambda captures an immutable snapshot; JSON is built by the serial writer.
            batch[key] = when (key) {
                "favorites" -> state.favorites().let { rows -> { LibraryDisk.series(rows) } }
                "later" -> state.later().let { rows -> { LibraryDisk.series(rows) } }
                "progress" -> state.history().let { rows -> { LibraryDisk.history(rows) } }
                "favoriteUpdates" -> state.updates().let { rows -> { LibraryDisk.updates(rows) } }
                "searches" -> state.searches().let { rows -> { SearchHistory.encode(rows) } }
                "watched" -> state.watched().let { ids -> { ids } }
                "hidden" -> state.hidden().let { ids -> { ids } }
                else -> error("Unknown library partition")
            }
        }
        writes.submit(batch)
    }
    fun favorites() = state().favorites()
    fun favorite(id: String) = state().favorite(id)
    fun later() = state().later()
    fun queued(id: String) = state().queued(id)
    fun history(): List<WatchProgress> = state().history()
    fun progress(id: String): WatchProgress? = state().progress(id)
    fun searches() = state().searches()
    fun watched(id: String) = state().watched(id)
    fun hidden() = state().hidden()
    fun favoriteUpdate(id: String) = state().favoriteUpdate(id)
    fun updatedFavorites() = state().updatedFavorites()
    fun favoriteLabel(id: String): String = favoriteUpdate(id)?.let {
        if (it.added > 0) "新增 ${it.added} 集 · 更新至 ${it.total} 集" else "更新至 ${it.total} 集"
    } ?: "尚未检查更新"
    fun toggle(series: Series) = state().toggle(series).also { persist("favorites", "favoriteUpdates") }
    fun toggleLater(series: Series) = state().toggleLater(series).also { persist("later") }
    fun save(progress: WatchProgress) { if (state().save(progress)) persist("progress") }
    fun setWatched(id: String, value: Boolean) { if (state().setWatched(id, value)) persist("watched") }
    /** 这一集在这台设备上播完过。和「整剧已看」是两件事：后者是手动标记，这个来自实际播放。 */
    fun watchedEpisodes(seriesId: String): Set<String> = episodeIds[seriesId].orEmpty()
    fun episodeWatched(seriesId: String, episodeId: String): Boolean = episodeIds[seriesId]?.contains(episodeId) == true
    fun markEpisodeWatched(seriesId: String, episodeId: String) {
        val ids = episodeIds.getOrPut(seriesId) { LinkedHashSet() }
        if (!ids.add(episodeId)) return
        while (ids.size > 2000) ids.remove(ids.iterator().next())
        persistEpisodeWatched()
    }
    fun clearEpisodeWatched(seriesId: String) { if (episodeIds.remove(seriesId) != null) persistEpisodeWatched() }
    private fun persistEpisodeWatched() { writes.submit(mapOf("watched-episodes" to { encodeEpisodeWatched() })) }
    private fun encodeEpisodeWatched(): String = JSONArray(episodeIds.entries.take(200).map { (series, ids) ->
        JSONObject().put("series", series).put("episodes", JSONArray(ids.toList()))
    }).toString()
    private fun decodeEpisodeWatched(raw: String?): Map<String, MutableSet<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val rows = JSONArray(raw)
            val decoded = LinkedHashMap<String, MutableSet<String>>()
            for (row in 0 until rows.length()) {
                val item = rows.optJSONObject(row) ?: continue
                val series = item.optString("series")
                val list = item.optJSONArray("episodes") ?: continue
                if (series.isEmpty() || series.length > 30 || list.length() > 2000) continue
                val ids = LinkedHashSet<String>()
                for (position in 0 until list.length()) {
                    val value = list.optString(position)
                    if (value.isNotEmpty() && value.length <= 30) ids.add(value)
                }
                if (ids.isNotEmpty()) decoded[series] = ids
            }
            decoded
        }.getOrDefault(emptyMap())
    }
    fun hide(id: String) { state().hide(id); persist("hidden") }
    fun unhideAll() { state().unhideAll(); persist("hidden") }
    fun rememberSearch(query: String, type: ContentType) { state().rememberSearch(query, type); persist("searches") }
    fun clearSearches() { state().clearSearches(); persist("searches") }
    fun removeHistory(id: String) { state().removeHistory(id); clearEpisodeWatched(id); persist("progress", "watched") }
    fun clearHistory() { state().clearHistory(); episodeIds.clear(); persist("progress", "watched") }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean = false) {
        if (state().observeFavorite(id, count, now, acknowledge)) persist("favoriteUpdates")
    }
    fun cachedHome(type: ContentType): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (homeSavedAt[type] ?: return null)
        return homes[type].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }
    }
    fun cacheHome(type: ContentType, items: List<Series>, hasMore: Boolean) {
        state()
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val retained = items.take(30).map { it.copy(description = it.description.take(1500)) }
        homes[type] = CatalogPage(retained, hasMore)
        homeSavedAt[type] = now
        homeWrites.submit(mapOf("home-${type.storedValue}" to { CatalogSnapshot.encode(retained, hasMore, now) }))
    }
    var maxQuality: Int
        get() = state().settings.quality
        set(value) { val next = PlaybackQuality.normalize(value); if (next != maxQuality) { state().settings = state().settings.copy(quality = next); put("quality", next) } }
    var autoNext: Boolean
        get() = state().settings.autoNext
        set(value) { if (value != autoNext) { state().settings = state().settings.copy(autoNext = value); put("autoNext", value) } }
    var contentType: ContentType
        get() = state().settings.type
        set(value) { if (value != contentType) { state().settings = state().settings.copy(type = value); put("contentType", value.storedValue) } }
    var playbackSpeed: Float
        get() = state().settings.speed
        set(value) { val next = PlaybackSpeed.normalize(value); if (next != playbackSpeed) { state().settings = state().settings.copy(speed = next); put("playbackSpeed", next) } }
    var frameMode: VideoFrameMode
        get() = state().settings.frame
        set(value) { if (value != frameMode) { state().settings = state().settings.copy(frame = value); put("frameMode", value.name) } }
    fun snapshot() = state().snapshot()
    fun restore(incoming: BackupData, restoreSettings: Boolean) {
        val merged = LibraryBackup.merge(snapshot(), incoming, restoreSettings)
        index = LibraryIndex(merged, state().updates())
        persist("favorites", "later", "progress", "watched", "hidden", "searches", "favoriteUpdates")
        writes.submit(mapOf("quality" to { merged.settings.quality }, "playbackSpeed" to { merged.settings.speed },
            "autoNext" to { merged.settings.autoNext }, "contentType" to { merged.settings.type.storedValue }, "frameMode" to { merged.settings.frame.name }))
        flush()
    }
    /** Requests prompt background commit, without lifecycle-thread disk waits. */
    fun flush() { writes.flush(); homeWrites.flush() }
    /** Drain pending commits after Activity destruction; abrupt process death is still a boundary. */
    fun close() {
        if (closed) return
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdown()
        synchronized(retiredWrites) {
            writes.close(); homeWrites.close()
            retiredWrites += writes; retiredWrites += homeWrites
        }
    }
    private fun commit(target: SharedPreferences, values: Map<String, () -> Any?>): Boolean {
        val edit = target.edit()
        values.forEach { (key, encode) ->
            when (val value = encode()) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported library value")
            }
        }
        return edit.commit()
    }
}
