// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.SurfaceHolder
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.hongguotv.core.RemoteVideo

/**
 * 播放状态用这套常量表示，界面层不需要直接引用 media3 的 Player.STATE_*。
 * 取值与 media3 1.4.1 的 STATE_* 不同，所以映射只写在 ExoPlayerPlayer 里。
 */
internal object PlaybackState {
    const val IDLE = 0
    const val BUFFERING = 1
    const val READY = 2
    const val ENDED = 3
}

internal interface PlaybackListener {
    fun onStateChanged(state: Int)
    fun onFirstFrame()
    fun onPlayingChanged(playing: Boolean)
    /** 第三个参数 learn 表示「这条错误值得记进设备能力记录」。只有确认是解码器本身的问题才为真：
     *  记错一条，这一档在以后所有剧集里都会被跳过。 */
    fun onError(message: String, decoding: Boolean, learn: Boolean)
    /** 位置跳到另一处（手动 seek 或这一集自然接上下一集）。 */
    fun onPositionMoved(positionMs: Long, autoTransition: Boolean) {}
    /** 解码器报出的画面尺寸（像素）。4.4 分支靠它把 Surface 排成正确的比例，
     *  否则系统解码器会把帧直接拉伸填满 Surface，画面就变形。 */
    fun onVideoSize(width: Int, height: Int) {}
}

/** 一次播放的输入：解密取流对象，加上用来核对「这是哪一集」的标识。 */
internal class PlaybackSource(val video: RemoteVideo, val mediaId: String)

/**
 * Android 4.4 兼容分支的播放后端。它和 main 分支用的是同一类播放器（ExoPlayer），
 * 区别只有依赖版本：这里钉在 androidx.media3 1.4.1，那是官方最后一个 minSdk 19 的版本。
 *
 * 取流走 [VideoDataSource]：解密样本直接交给播放器，不再伪装成本地 http 地址。
 * 对外接口与旧的 MediaPlayerPlayer 一致，界面层不需要分支。
 */
internal class ExoPlayerPlayer(
    context: Context,
    private val holder: SurfaceHolder,
    private val listener: PlaybackListener,
) {
    private val player = ExoPlayer.Builder(context).build()
    private var state = PlaybackState.IDLE
    private var frameWidth = 0
    private var frameHeight = 0
    private var released = false

    private val stateListener = object : Player.Listener {
        override fun onPlaybackStateChanged(value: Int) = setState(mapState(value))
        override fun onIsPlayingChanged(playing: Boolean) {
            // 只有真的在播、且播放器处于 READY 才算「正在播放」，与旧实现保持一致。
            listener.onPlayingChanged(playing && state == PlaybackState.READY)
        }
        override fun onPlayerError(error: PlaybackException) {
            if (released) return
            val (message, decoding, learn) = describeError(error)
            listener.onError("$message（播放器错误 ${error.errorCodeName}）", decoding, learn)
        }
        override fun onVideoSizeChanged(size: VideoSize) = reportVideoSize(size.width, size.height)
    }

    private val frameListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, dummy: Any, timeUs: Long) {
            if (!released) listener.onFirstFrame()
        }
        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, size: VideoSize) =
            reportVideoSize(size.width, size.height)
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, value: Int) = setState(mapState(value))
    }

    init {
        player.setVideoSurface(holder.surface)
        player.addListener(stateListener)
        player.addAnalyticsListener(frameListener)
    }

    val playbackState: Int get() = state
    val playWhenReady: Boolean get() = player.playWhenReady
    val isPlaying: Boolean get() = player.isPlaying
    val duration: Long get() = player.duration.coerceAtLeast(0L)
    val currentPosition: Long get() = player.currentPosition.coerceAtLeast(0L)
    /** 解码器实际给出的画面尺寸；0 表示还没有视频轨道。 */
    val hasVideoTrack: Boolean get() = frameWidth > 0 && frameHeight > 0
    val videoFrameWidth: Int get() = frameWidth
    val videoFrameHeight: Int get() = frameHeight
    val currentMediaId: String get() = player.currentMediaItem?.mediaId ?: ""

    fun setPlayWhenReady(value: Boolean) {
        player.playWhenReady = value
        listener.onPlayingChanged(value && state == PlaybackState.READY)
    }

    fun prepare(source: PlaybackSource, position: Long, autoplay: Boolean) {
        val mediaSource = ProgressiveMediaSource.Factory { VideoDataSource(source.video) }
            .createMediaSource(MediaItem.Builder().setMediaId(source.mediaId)
                .setUri("hongguotv://episode/${source.mediaId}").setMimeType(MimeTypes.VIDEO_MP4).build())
        frameWidth = 0; frameHeight = 0
        player.setMediaSource(mediaSource)
        if (position > 0) player.seekTo(position.coerceAtLeast(0))
        player.playWhenReady = autoplay
        player.prepare()
    }

    fun play() = setPlayWhenReady(true)
    fun pause() = setPlayWhenReady(false)

    fun seekTo(position: Long) { player.seekTo(position.coerceAtLeast(0)) }

    fun setPlaybackSpeed(speed: Float) { player.setPlaybackSpeed(speed) }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        frameWidth = 0; frameHeight = 0
        setState(PlaybackState.IDLE)
    }

    fun release() {
        if (released) return
        released = true
        try { player.removeListener(stateListener) } catch (_: RuntimeException) {}
        try { player.removeAnalyticsListener(frameListener) } catch (_: RuntimeException) {}
        try { player.setVideoSurface(null) } catch (_: RuntimeException) {}
        player.release()
        state = PlaybackState.IDLE
    }

    private fun mapState(value: Int): Int = when (value) {
        Player.STATE_BUFFERING -> PlaybackState.BUFFERING
        Player.STATE_READY -> PlaybackState.READY
        Player.STATE_ENDED -> PlaybackState.ENDED
        else -> PlaybackState.IDLE
    }

    private fun setState(value: Int) {
        if (state == value) return
        state = value
        listener.onStateChanged(value)
    }

    /** 只在尺寸真的变化时上报一次。界面层据此把 Surface 排成正确比例，
     *  所以它必须发生在第一帧渲染之前——两个回调都在解码器开始输出之前到达。 */
    private fun reportVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (frameWidth == width && frameHeight == height) return
        frameWidth = width; frameHeight = height
        listener.onVideoSize(width, height)
    }

    /** 错误码分段沿用 media3 自己的划分（与 main 分支同一套判断）：
     *  3000..4999 是解码器与容器解析，属于设备能力问题；2000..2999 是取流，属于网络问题。 */
    private fun describeError(error: PlaybackException): Triple<String, Boolean, Boolean> = when {
        error.errorCode in 3000..3999 -> Triple("这台设备无法解码该视频", true, true)
        error.errorCode in 4000..4999 -> Triple("该视频的格式不受支持", true, true)
        error.errorCode in 2000..2999 -> Triple("读取视频时网络中断", false, false)
        error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> Triple("读取视频超时", false, false)
        else -> Triple("播放失败", false, false)
    }
}

/** 播放失败。decoding 表示这是解码能力问题（应降清晰度）；learn 表示这条值得记进设备能力记录，
 *  只有确定是解码器本身的问题才为真，否则会误伤这一档的以后所有剧集。 */
internal class PlaybackFailure(message: String, val decoding: Boolean, val retryable: Boolean, val learn: Boolean = true) : java.io.IOException(message)
