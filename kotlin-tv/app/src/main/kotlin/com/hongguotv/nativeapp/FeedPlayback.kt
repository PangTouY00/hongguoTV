// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.View
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.concurrent.TimeUnit

internal data class TrialClip(val detail: Detail,val video: RemoteVideo): Closeable {
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
}
internal class PublicFeedBackend(
    private val quality: Int,
    private val http: OkHttpClient=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(35,TimeUnit.SECONDS).build(),
    account: AccountCredentials?=null
): FeedBackend {
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    private val repository=ContentRepository(http)
    private val recommendationAccount=account
    override fun recommendations(page: Int,cursor: RecommendationCursor)=RecommendationFeed(http,recommendationAccount,cursor).page(page)
    override fun categoryRecommendations(category: DramaCategory,page: Int,cursor: RecommendationCursor)=RecommendationFeed(http,recommendationAccount,cursor,category).page(page)
    override fun catalog(category: DramaCategory,page: Int)=repository.discover(category,page=page)
    override fun detail(series: Series)=repository.detail(series.id)
    override fun prepare(detail: Detail): TrialClip {
        val id=detail.episodes.firstOrNull() ?: throw java.io.IOException("该作品暂时没有可试播的剧集")
        val video=RemoteVideo(http,repository.stream(id,quality))
        try { return TrialClip(detail,video.prepare()) } catch(error: Exception) { video.close();throw error }
    }
    override fun prefetch(detail: Detail): TrialClip {
        val clip=prepare(detail)
        try { clip.video.warm();return clip } catch(error: Exception) { clip.close();throw error }
    }
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        // Idle TLS socket closure may write close_notify. Never do it on Android's UI thread.
        Thread({
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        },"hongguotv-feed-cleanup").start()
    }
}
internal enum class TrialStatus { BUFFERING, READY, ENDED, ERROR }
internal interface TrialPlayback {
    val view: View
    var status: ((TrialStatus)->Unit)?
    fun load(clip: TrialClip,position: Long,autoplay: Boolean)
    fun queue(clip: TrialClip) {}
    fun remove(seriesId: String) {}
    fun select(clip: TrialClip,position: Long,autoplay: Boolean): Boolean=false
    fun pause()
    fun resume()
    fun restart()
    fun position(): Long
    fun clear()
    fun release()
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class Media3TrialPlayback(context: Context): TrialPlayback {
    private val player=ExoPlayer.Builder(context,DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2500,10000,500,1000).setTargetBufferBytes(6*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build()).build()
    private val surface=PlayerView(context).apply {
        useController=false;isFocusable=false;resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);this.player=this@Media3TrialPlayback.player
    }
    override val view: View get()=surface
    override var status: ((TrialStatus)->Unit)?=null
    private var activeId: String?=null
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
        })
    }
    private fun key(clip: TrialClip)=clip.detail.series.id+":"+clip.episodeId
    private fun source(clip: TrialClip)=ProgressiveMediaSource.Factory { VideoDataSource(clip.video) }.createMediaSource(
        MediaItem.Builder().setMediaId(key(clip)).setUri("hongguotv://trial/${clip.episodeId}").setMimeType(MimeTypes.VIDEO_MP4).build())
    override fun load(clip: TrialClip,position: Long,autoplay: Boolean) {
        activeId=key(clip)
        player.setMediaSource(source(clip));player.seekTo(position.coerceAtLeast(0));player.prepare();player.playWhenReady=autoplay
    }
    override fun queue(clip: TrialClip) {
        if((0 until player.mediaItemCount).none { player.getMediaItemAt(it).mediaId==key(clip) }) player.addMediaSource(source(clip))
    }
    override fun remove(seriesId: String) {
        val index=(0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId.startsWith("$seriesId:") } ?: return
        if(player.getMediaItemAt(index).mediaId!=activeId) player.removeMediaItem(index)
    }
    override fun select(clip: TrialClip,position: Long,autoplay: Boolean): Boolean {
        val index=(0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId==key(clip) } ?: return false
        activeId=key(clip);player.seekTo(index,position.coerceAtLeast(0));player.playWhenReady=autoplay
        if(player.playbackState==Player.STATE_READY) status?.invoke(TrialStatus.READY)
        return true
    }
    override fun pause()=player.pause()
    override fun resume()=player.play()
    override fun restart() { player.seekTo(0);player.play() }
    override fun position()=player.currentPosition.coerceAtLeast(0)
    override fun clear() { activeId=null;player.stop();player.clearMediaItems() }
    override fun release() { status=null;surface.player=null;player.release() }
}
