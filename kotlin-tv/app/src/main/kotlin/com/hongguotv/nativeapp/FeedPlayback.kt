// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.View
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
    private val http: OkHttpClient=OkHttpClient.Builder().connectionPool(feedPool).connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(35,TimeUnit.SECONDS).build(),
    account: AccountCredentials?=null
): FeedBackend {
    companion object {
        // Reuse idle public TLS connections across homepage visits. Dispatchers remain per backend,
        // so cancelling an old page cannot cancel its replacement page's requests.
        private val feedPool=okhttp3.ConnectionPool(3,2,TimeUnit.MINUTES)
    }
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    private val repository=ContentRepository(http)
    private val recommendationAccount=account
    private val trialHints=linkedMapOf<String,TrialHint>()
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
    override fun recommendations(page: Int,cursor: RecommendationCursor)=timed("recommendations") { rememberHints(RecommendationFeed(http,recommendationAccount,cursor).page(page)) }
    override fun categoryRecommendations(category: DramaCategory,page: Int,cursor: RecommendationCursor)=rememberHints(RecommendationFeed(http,recommendationAccount,cursor,category).page(page))
    override fun catalog(category: DramaCategory,page: Int)=repository.discover(category,page=page)
    override fun detail(series: Series)=repository.detail(series.id)
    private fun open(id: String): RemoteVideo {
        val opened=openStream(http,repository,id,quality,CodecCapabilities::supports) { it==null || CodecCapabilities.supports(it) }
            ?: throw java.io.IOException("该作品暂时没有可试播的剧集")
        return opened.video
    }
    override fun prepare(detail: Detail): TrialClip {
        val id=detail.episodes.firstOrNull() ?: throw java.io.IOException("该作品暂时没有可试播的剧集")
        return TrialClip(detail,open(id))
    }
    override fun trial(series: Series): TrialClip {
        val hint=synchronized(trialHints) { trialHints[series.id] } ?: return prepare(detail(series))
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
            http.dispatcher.executorService.shutdown()
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class Media3TrialPlayback(context: Context): TrialPlayback {
    private var currentIndex=0
    private var preloading=false
    private val preloadBuilder=DefaultPreloadManager.Builder(context,
        TargetPreloadStatusControl<Int,DefaultPreloadManager.PreloadStatus> { index ->
            // Keep parsed tracks and existing samples during hand-off; NOT_PRELOADED clears them.
            if(!preloading || index==currentIndex) DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            else when(index-currentIndex) {
                1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(1500L)
                -1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(1000L)
                2,3 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        })
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2500,10000,500,1000)
            .setTargetBufferBytes(6*1024*1024).setPlayerTargetBufferBytes("preload",4*1024*1024)
            .setPrioritizeTimeOverSizeThresholds(false).build())
    private val preloadManager=preloadBuilder.build()
    private val player=preloadBuilder.buildExoPlayer()
    private val items=linkedMapOf<String,MediaItem>()
    private val surface=PlayerView(context).apply {
        useController=false;isFocusable=false;resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);this.player=this@Media3TrialPlayback.player
    }
    override fun canPrefetch(): Boolean {
        val allowed=player.isPlaying && player.totalBufferedDuration>=1500
        if(allowed!=preloading) { preloading=allowed;preloadManager.invalidate() }
        return allowed
    }
    override val view: View get()=surface
    override var status: ((TrialStatus)->Unit)?=null
    private var activeId: String?=null
    private var startedAt=0L
    init {
        player.pauseAtEndOfMediaItems=true
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object: Player.Listener {
            private fun active()=activeId!=null && player.currentMediaItem?.mediaId==activeId
            override fun onPlaybackStateChanged(value: Int) {
                if(!active()) return
                when(value) {
                    Player.STATE_BUFFERING->status?.invoke(TrialStatus.BUFFERING)
                    Player.STATE_READY->status?.invoke(if(player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) TrialStatus.READY else TrialStatus.ERROR)
                    Player.STATE_ENDED->status?.invoke(TrialStatus.ENDED)
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean,reason: Int) {
                if(active() && !playWhenReady && reason==Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                    status?.invoke(TrialStatus.ENDED)
            }
            override fun onPlayerError(error: PlaybackException) { if(active()) status?.invoke(TrialStatus.ERROR) }
            override fun onRenderedFirstFrame() {
                if(active()) android.util.Log.i("HongguoTV","Home first-frame elapsedMs=${android.os.SystemClock.elapsedRealtime()-startedAt}")
            }
        })
    }
    private fun key(clip: TrialClip)=clip.detail.series.id+":"+clip.episodeId
    private fun source(clip: TrialClip)=ProgressiveMediaSource.Factory { VideoDataSource(clip.video) }.createMediaSource(
        MediaItem.Builder().setMediaId(key(clip)).setUri("hongguotv://trial/${clip.episodeId}").setMimeType(MimeTypes.VIDEO_MP4).build())
    override fun setCurrentIndex(index: Int) {
        currentIndex=index;preloading=false
        preloadManager.setCurrentPlayingIndex(index);preloadManager.invalidate()
    }
    private fun register(clip: TrialClip,index: Int): MediaItem = items.getOrPut(key(clip)) {
        val source=source(clip)
        preloadManager.add(source,index)
        source.mediaItem
    }
    private fun play(clip: TrialClip,position: Long,autoplay: Boolean) {
        val item=register(clip,currentIndex)
        activeId=key(clip);startedAt=android.os.SystemClock.elapsedRealtime()
        player.setMediaSource(checkNotNull(preloadManager.getMediaSource(item)),position.coerceAtLeast(0))
        player.prepare();player.playWhenReady=autoplay
    }
    override fun load(clip: TrialClip,position: Long,autoplay: Boolean)=play(clip,position,autoplay)
    override fun queue(clip: TrialClip,index: Int) {
        register(clip,index);preloadManager.invalidate()
    }
    override fun remove(seriesId: String) {
        val id=items.keys.firstOrNull { it.startsWith("$seriesId:") } ?: return
        if(id!=activeId) items.remove(id)?.let { preloadManager.remove(it) }
    }
    override fun select(clip: TrialClip,position: Long,autoplay: Boolean): Boolean {
        if(key(clip) !in items) return false
        play(clip,position,autoplay)
        return true
    }
    override fun pause() { player.pause();preloading=false;preloadManager.invalidate() }
    override fun resume()=player.play()
    override fun restart() { player.seekTo(0);player.play() }
    override fun position()=player.currentPosition.coerceAtLeast(0)
    override fun clear() { activeId=null;preloading=false;player.stop();player.clearMediaItems();preloadManager.reset();items.clear() }
    override fun release() { status=null;surface.player=null;player.release();preloadManager.release();items.clear() }
}
