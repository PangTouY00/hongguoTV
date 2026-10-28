// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.SurfaceHolder
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * 系统硬解后端（media3 / ExoPlayer）。它只用设备自己的 MediaCodec：设备没有的那种编码
 * 它播不了，那种情况走 [IjkPlayerPlayer] 的随包软解。共同形状见 [VideoPlayerBackend]，
 * 界面层不需要分支。
 *
 * 它和 main 分支用的是同一类播放器，区别只有依赖版本：这里钉在 androidx.media3 1.4.1，
 * 那是官方最后一个 minSdk 19 的版本。取流走 [VideoDataSource]：解密样本直接交给播放器，
 * 不再伪装成本地 http 地址。
 *
 * AnalyticsListener、ProgressiveMediaSource.Factory 与 setMediaSource 在 1.4.1 里都还标着
 * UnstableApi，不显式认领就是 lint 的 UnsafeOptInUsageError 错误（11 个）。这里整类认领：
 * 我们用的就是这一版钉死的接口，升级 media3 时应当重新核对这一行。
 */
@OptIn(markerClass = [UnstableApi::class])
internal class ExoPlayerPlayer(
    context: Context,
    private val holder: SurfaceHolder,
    private val listener: PlaybackListener,
) : VideoPlayerBackend {
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

    override val playbackState: Int get() = state
    override val playWhenReady: Boolean get() = player.playWhenReady
    override val isPlaying: Boolean get() = player.isPlaying
    override val duration: Long get() = player.duration.coerceAtLeast(0L)
    override val currentPosition: Long get() = player.currentPosition.coerceAtLeast(0L)
    /** 解码器实际给出的画面尺寸；0 表示还没有视频轨道。 */
    override val hasVideoTrack: Boolean get() = frameWidth > 0 && frameHeight > 0
    override val videoFrameWidth: Int get() = frameWidth
    override val videoFrameHeight: Int get() = frameHeight
    override val currentMediaId: String get() = player.currentMediaItem?.mediaId ?: ""
    override val backendName: String get() = "系统硬解"

    override fun setPlayWhenReady(value: Boolean) {
        player.playWhenReady = value
        listener.onPlayingChanged(value && state == PlaybackState.READY)
    }

    override fun prepare(source: PlaybackSource, position: Long, autoplay: Boolean) {
        val mediaSource = ProgressiveMediaSource.Factory { VideoDataSource(source.video) }
            .createMediaSource(MediaItem.Builder().setMediaId(source.mediaId)
                .setUri("hongguotv://episode/${source.mediaId}").setMimeType(MimeTypes.VIDEO_MP4).build())
        frameWidth = 0; frameHeight = 0
        player.setMediaSource(mediaSource)
        if (position > 0) player.seekTo(position.coerceAtLeast(0))
        player.playWhenReady = autoplay
        player.prepare()
    }

    override fun play() = setPlayWhenReady(true)
    override fun pause() = setPlayWhenReady(false)

    override fun seekTo(position: Long) { player.seekTo(position.coerceAtLeast(0)) }

    override fun setPlaybackSpeed(speed: Float) { player.setPlaybackSpeed(speed) }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
        frameWidth = 0; frameHeight = 0
        setState(PlaybackState.IDLE)
    }

    override fun release() {
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
