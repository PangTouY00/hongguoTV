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
    fun detail(series: Series): Detail
    fun prepare(detail: Detail): TrialClip
}
internal class PublicFeedBackend(private val quality: Int): FeedBackend {
    private val http=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(35,TimeUnit.SECONDS).build()
    private val repository=ContentRepository(http)
    override fun catalog(category: DramaCategory,page: Int)=repository.discover(category,page=page)
    override fun detail(series: Series)=repository.detail(series.id)
    override fun prepare(detail: Detail): TrialClip {
        val id=detail.episodes.firstOrNull() ?: throw java.io.IOException("该作品暂时没有可试播的剧集")
        val video=RemoteVideo(http,repository.stream(id,quality))
        try { return TrialClip(detail,video.prepare()) } catch(error: Exception) { video.close();throw error }
    }
    override fun close() { http.dispatcher.cancelAll();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown() }
}
internal enum class TrialStatus { BUFFERING, READY, ENDED, ERROR }
internal interface TrialPlayback {
    val view: View
    var status: ((TrialStatus)->Unit)?
    fun load(clip: TrialClip,position: Long,autoplay: Boolean)
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
            override fun onPlayerError(error: PlaybackException) { if(active()) status?.invoke(TrialStatus.ERROR) }
        })
    }
    override fun load(clip: TrialClip,position: Long,autoplay: Boolean) {
        activeId=clip.detail.series.id+":"+clip.episodeId
        val source=ProgressiveMediaSource.Factory { VideoDataSource(clip.video) }.createMediaSource(
            MediaItem.Builder().setMediaId(activeId!!).setUri("hongguotv://trial/${clip.episodeId}").setMimeType(MimeTypes.VIDEO_MP4).build())
        player.setMediaSource(source);player.seekTo(position.coerceAtLeast(0));player.prepare();player.playWhenReady=autoplay
    }
    override fun pause()=player.pause()
    override fun resume()=player.play()
    override fun restart() { player.seekTo(0);player.play() }
    override fun position()=player.currentPosition.coerceAtLeast(0)
    override fun clear() { activeId=null;player.stop();player.clearMediaItems() }
    override fun release() { status=null;surface.player=null;player.release() }
}
