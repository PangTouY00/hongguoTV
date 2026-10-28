// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.View
import com.hongguotv.core.*
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.concurrent.TimeUnit

internal data class TrialClip(val detail: Detail,val video: RemoteVideo,val episodeCount: Int=detail.episodes.size): Closeable {
    val episodeId get()=detail.episodes.first()
    override fun close()=video.close()
}
internal interface FeedBackend: Closeable {
    fun catalog(category: DramaCategory,page: Int): CatalogPage
    fun categoryRecommendations(category: DramaCategory,page: Int,cursor: RecommendationCursor): CatalogPage=catalog(category,page)
    fun recommendations(page: Int,cursor: RecommendationCursor): CatalogPage=catalog(DramaCategory.COMIC,page)
    fun detail(series: Series): Detail
    fun prepare(detail: Detail): TrialClip
    fun prefetch(detail: Detail): TrialClip=prepare(detail)
    fun trial(series: Series): TrialClip=prepare(detail(series))
    fun preload(series: Series): TrialClip=prefetch(detail(series))
}
internal class PublicFeedBackend(
    private val quality: Int,
    private val http: OkHttpClient=newHttpClientBuilder().connectionPool(feedPool).connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build(),
    account: AccountCredentials?=null,
    private val startupCache: FeedStartupCache?=null,
    // 播放身份进程内共享：首页试播不再单独注册，和正式播放用同一台设备。
    playbackSession: PlaybackDeviceSession=PlaybackDeviceSession.shared
): FeedBackend {
    companion object {
        // Reuse idle public TLS connections across homepage visits. Dispatchers remain per backend,
        // so cancelling an old page cannot cancel its replacement page's requests.
        private val feedPool=okhttp3.ConnectionPool(3,2,TimeUnit.MINUTES)
    }
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    private val repository=ContentRepository(http, playbackSession=playbackSession)
    private val recommendationAccount=account
    private val cacheIdentity=account?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it.cookie.toByteArray(Charsets.UTF_8)).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
    } ?: "guest"
    private val trialHints=linkedMapOf<String,TrialHint>()
    private val startupPages=mutableMapOf<String,CatalogPage>()
    private fun rememberHints(page: CatalogPage): CatalogPage {
        synchronized(trialHints) {
            trialHints.putAll(page.trialHints)
            while(trialHints.size>72) trialHints.remove(trialHints.keys.first())
        }
        return page
    }
    private fun <T> timed(stage: String, action: ()->T): T {
        val start=android.os.SystemClock.elapsedRealtime()
        try { return action() } finally { android.util.Log.i("HongguoTV","Home $stage elapsedMs=${android.os.SystemClock.elapsedRealtime()-start}") }
    }
    private fun page(source: String,page: Int,cursor: RecommendationCursor,fetch: ()->CatalogPage): CatalogPage {
        if(page==1) startupCache?.load(cacheIdentity,source)?.let { cached ->
            if(cursor.restoreStarter(cached)) {
                synchronized(trialHints) { startupPages[source]=cached }
                android.util.Log.i("HongguoTV","Home starter-cache hit")
                return rememberHints(cached)
            }
        }
        val result=rememberHints(fetch())
        if(page==1) {
            synchronized(trialHints) { startupPages[source]=result }
            startupCache?.save(cacheIdentity,source,result)
        }
        return result
    }
    override fun recommendations(page: Int,cursor: RecommendationCursor)=timed("recommendations") { page("recommend",page,cursor) { RecommendationFeed(http,recommendationAccount,cursor).page(page) } }
    override fun categoryRecommendations(category: DramaCategory,page: Int,cursor: RecommendationCursor)=timed("category") { page(category.route,page,cursor) { RecommendationFeed(http,recommendationAccount,cursor,category).page(page) } }
    override fun catalog(category: DramaCategory,page: Int)=repository.discover(category,page=page)
    override fun detail(series: Series)=timed("detail") { repository.detail(series.id) }
    private fun open(id: String): RemoteVideo {
        val opened=openStream(http,repository,id,quality,CodecCapabilities::supports,initialBytes=32*1024) { it==null || CodecCapabilities.supports(it) }
        return opened.video
    }
    override fun prepare(detail: Detail): TrialClip {
        val id=detail.episodes.firstOrNull() ?: throw java.io.IOException("该作品暂时没有可试播的剧集")
        return TrialClip(detail,open(id))
    }
    override fun trial(series: Series): TrialClip {
        val hint=synchronized(trialHints) { trialHints[series.id] } ?: run {
            val detail=detail(series)
            val first=detail.episodes.firstOrNull() ?: throw java.io.IOException("该作品暂时没有可试播的剧集")
            val verified=TrialHint(first,detail.episodes.size)
            val updates=synchronized(trialHints) {
                trialHints[series.id]=verified
                startupPages.filterValues { page -> page.items.any { it.id==series.id } }.map { (key,page) ->
                    val updated=page.copy(trialHints=page.trialHints+(series.id to verified))
                    startupPages[key]=updated;key to updated
                }
            }
            updates.forEach { (source,page) -> startupCache?.save(cacheIdentity,source,page) }
            verified
        }
        return TrialClip(Detail(series,listOf(hint.episodeId)),timed("play-address") { open(hint.episodeId) },hint.episodeCount)
    }
    // Publish the prepared header immediately. Media3 preloads samples according to duration;
    // a foreground switch must not wait for an additional fixed 1 MiB speculative download.
    override fun preload(series: Series): TrialClip=trial(series)
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        // Idle TLS socket closure may write close_notify. Never do it on Android's UI thread.
        Thread({
            http.dispatcher.cancelAll()
            if(http.connectionPool!==feedPool) http.connectionPool.evictAll()
            http.dispatcher.executorService?.shutdown()
        },"hongguotv-feed-cleanup").start()
    }
}
internal enum class TrialStatus { BUFFERING, READY, ENDED, ERROR }
internal interface TrialPlayback {
    val view: View
    var status: ((TrialStatus)->Unit)?
    fun load(clip: TrialClip,position: Long,autoplay: Boolean)
    fun setCurrentIndex(index: Int) {}
    fun queue(clip: TrialClip,index: Int) {}
    fun remove(seriesId: String) {}
    fun select(clip: TrialClip,position: Long,autoplay: Boolean): Boolean=false
    fun canPrefetch(): Boolean=true
    fun pause()
    fun resume()
    fun restart()
    fun position(): Long
    fun clear()
    fun release()
}
/**
 * 4.4 分支的首页试播：一次只播一条。下一条的地址与索引仍由上层预取好，
 * 这里只负责「切到这一条并播起来」，所以 canPrefetch() 直接返回 false，
 * 让上层不要按预加载节奏排队。
 */
internal class ExoPlayerTrialPlayback(private val context: android.content.Context): TrialPlayback {
    private val surface=android.view.SurfaceView(context).apply { isFocusable=false }
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private var player: VideoPlayerBackend?=null
    private var activeId: String?=""
    private var startedAt=0L
    override var status: ((TrialStatus)->Unit)?=null
    override val view: android.view.View get()=surface
    private fun key(clip: TrialClip)=clip.detail.series.id+":"+clip.episodeId
    /** 首页试播和正片用同一套选择：设备有硬解就用设备的，没有才走随包软解。
     *  换一条 clip 时如果需要的后端变了，必须重建——把软解的实例拿去播硬解的档位，
     *  或者反过来，都只是把同一件事换条路失败一次。 */
    private fun backend(codec: String): VideoPlayerBackend {
        val existing=player
        if(existing!=null && PlaybackBackends.usesSoftwareDecode(codec)==(existing is IjkPlayerPlayer)) return existing
        existing?.release()
        val created=PlaybackBackends.create(context, surface.holder, codec, object: PlaybackListener {
            override fun onStateChanged(state: Int) {
                status?.invoke(when(state) {
                    PlaybackState.BUFFERING->TrialStatus.BUFFERING
                    PlaybackState.READY->TrialStatus.READY
                    PlaybackState.ENDED->TrialStatus.ENDED
                    else->TrialStatus.ERROR
                })
            }
            override fun onVideoSize(width: Int, height: Int) {
                // 首页试播固定「完整画面」，与 main 分支 PlayerView 的 RESIZE_MODE_FIT 一致。
                main.post { FrameFit.apply(surface,width,height,VideoFrameMode.FIT) }
            }
            override fun onFirstFrame() {
                android.util.Log.i("HongguoTV","Home first-frame elapsedMs=${android.os.SystemClock.elapsedRealtime()-startedAt}")
            }
            override fun onPlayingChanged(playing: Boolean) {}
            override fun onError(message: String, decoding: Boolean, learn: Boolean) { status?.invoke(TrialStatus.ERROR) }
        })
        player=created
        return created
    }
    private fun play(clip: TrialClip,position: Long,autoplay: Boolean) {
        activeId=key(clip);startedAt=android.os.SystemClock.elapsedRealtime()
        // 先按已经读到的视频头排好画面，再开始播：中途改尺寸会让 Surface 重新分配、白闪一下。
        clip.video.trackInfo()?.let { frame-> main.post { FrameFit.apply(surface,frame.width,frame.height,VideoFrameMode.FIT) } }
        backend(clip.video.trackInfo()?.fourcc ?: "").prepare(PlaybackSource(clip.video,activeId!!),position,autoplay)
    }
    override fun load(clip: TrialClip,position: Long,autoplay: Boolean)=play(clip,position,autoplay)
    /** 没有预加载队列，界面层不需要为下一条提前腾位置。 */
    override fun canPrefetch(): Boolean=false
    override fun pause() { player?.pause() }
    override fun resume() { player?.play() }
    override fun restart() { player?.seekTo(0); player?.play() }
    override fun position(): Long=player?.currentPosition?.coerceAtLeast(0) ?: 0L
    override fun clear() { activeId="";player?.stop() }
    override fun release() { status=null;player?.release();player=null }
}
