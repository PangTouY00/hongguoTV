// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.RemoteVideo

/**
 * 播放状态用这套常量表示，界面层不需要直接引用任何播放器的 STATE_*。
 * 取值与 media3 1.4.1 的 STATE_* 不同，映射只写在各个后端里。
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
 * 播放后端的共同形状。界面层只认这一套成员，不关心底下是 media3 的 ExoPlayer
 * 还是随包携带的 ijkplayer（FFmpeg 软解）。
 *
 * 两个后端必须遵守同一套时序约定：
 *  - prepare() 之后 onStateChanged 至少给一次 BUFFERING，成功给 READY，失败给 onError；
 *  - READY 之前必须已经报过 onVideoSize（界面层用 hasVideoTrack 判断这一集到底有没有画面）；
 *  - release() 之后不再回调。
 */
internal interface VideoPlayerBackend {
    val playbackState: Int
    val playWhenReady: Boolean
    val isPlaying: Boolean
    val duration: Long
    val currentPosition: Long
    /** 解码器实际给出的画面尺寸；0 表示还没有视频轨道。 */
    val hasVideoTrack: Boolean
    val videoFrameWidth: Int
    val videoFrameHeight: Int
    val currentMediaId: String
    /** 这一集实际由哪个后端在播，界面与日志都要能说出来：同一个清晰度在硬解和软解上
     *  的表现完全不同，出问题时必须能分清是哪条路。 */
    val backendName: String

    fun setPlayWhenReady(value: Boolean)
    fun prepare(source: PlaybackSource, position: Long, autoplay: Boolean)
    fun play()
    fun pause()
    fun seekTo(position: Long)
    fun setPlaybackSpeed(speed: Float)
    fun stop()
    fun release()
}

/** 播放失败。decoding 表示这是解码能力问题（应降清晰度）；learn 表示这条值得记进设备能力记录，
 *  只有确定是解码器本身的问题才为真，否则会误伤这一档的以后所有剧集。 */
internal class PlaybackFailure(message: String, val decoding: Boolean, val retryable: Boolean, val learn: Boolean = true) : java.io.IOException(message)
