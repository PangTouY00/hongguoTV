// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.media.AudioManager
import android.media.MediaPlayer
import android.view.SurfaceHolder
import java.io.IOException

/** 播放状态用这套常量表示，不引用 media3 的 Player.STATE_*（media3 要求 Android 5.0 以上）。 */
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
}

/** 一次播放的输入：本地代理给出的地址，加上用来核对「这是哪一集」的标识。 */
internal class PlaybackSource(val url: String, val mediaId: String, private val proxy: LocalVideoServer?) : java.io.Closeable {
    override fun close() { proxy?.close() }
}

/**
 * Android 4.4 上唯一的播放器就是系统的 MediaPlayer。它没有播放队列、没有变速、没有轨道选择，
 * 也没有「准备好再播」的状态机，所以这些语义在这里自己补齐：
 * wantPlay 记录用户是否要播，prepared 后才真正 start；队列退化成单集，下一集由界面层重新 prepare。
 */
internal class MediaPlayerPlayer(
    private val holder: SurfaceHolder,
    private val listener: PlaybackListener,
) {
    private var player: MediaPlayer? = null
    private var state = PlaybackState.IDLE
    private var wantPlay = false
    private var mediaId = ""
    private var pendingPosition = -1L
    private var videoWidth = 0
    private var videoHeight = 0
    private var released = false

    val playbackState: Int get() = state
    val playWhenReady: Boolean get() = wantPlay
    val isPlaying: Boolean get() = try { player?.isPlaying == true } catch (_: RuntimeException) { false }
    val duration: Long get() = try { player?.duration?.toLong() ?: 0L } catch (_: RuntimeException) { 0L }
    val currentPosition: Long get() = try { player?.currentPosition?.toLong() ?: 0L } catch (_: RuntimeException) { 0L }
    /** 解码器实际给出的画面尺寸；0 表示还没有视频轨道。 */
    val hasVideoTrack: Boolean get() = videoWidth > 0 && videoHeight > 0
    val currentMediaId: String get() = mediaId

    fun setPlayWhenReady(value: Boolean) {
        wantPlay = value
        val current = player ?: return
        try { if (value && state == PlaybackState.READY) current.start() else if (!value) current.pause() } catch (_: RuntimeException) {}
        reportPlaying()
    }

    fun prepare(source: PlaybackSource, position: Long, autoplay: Boolean) {
        stopInternal()
        wantPlay = autoplay
        mediaId = source.mediaId
        pendingPosition = position.coerceAtLeast(0)
        videoWidth = 0; videoHeight = 0
        val created = MediaPlayer()
        player = created
        setState(PlaybackState.BUFFERING)
        try {
            created.setAudioStreamType(AudioManager.STREAM_MUSIC)
            created.setScreenOnWhilePlaying(true)
            created.setDisplay(holder)
            created.setDataSource(source.url)
            created.setOnPreparedListener(object: MediaPlayer.OnPreparedListener {
                override fun onPrepared(prepared: MediaPlayer) {
                    if (released) return
                    if (pendingPosition > 0) try { prepared.seekTo(pendingPosition.toInt()) } catch (_: RuntimeException) {}
                    setState(PlaybackState.READY)
                    if (wantPlay) { try { prepared.start() } catch (_: RuntimeException) {} }
                    reportPlaying()
                }
            })
            created.setOnCompletionListener(object: MediaPlayer.OnCompletionListener {
                override fun onCompletion(completed: MediaPlayer) {
                    if (released) return
                    wantPlay = false
                    setState(PlaybackState.ENDED)
                    reportPlaying()
                }
            })
            created.setOnErrorListener(object: MediaPlayer.OnErrorListener {
                override fun onError(source: MediaPlayer, what: Int, extra: Int): Boolean {
                    if (released) return true
                    // 19xx 是 MediaPlayer 的解码/格式错误段，0x01xxxx 是 DRM 相关，这里只给出能行动的描述。
                    val (message,decoding,learn)=playbackErrorMessage(what, extra)
                    listener.onError(message,decoding,learn)
                    return true
                }
            })
            created.setOnVideoSizeChangedListener(object: MediaPlayer.OnVideoSizeChangedListener {
                override fun onVideoSizeChanged(source: MediaPlayer, width: Int, height: Int) {
                    videoWidth = width; videoHeight = height
                }
            })
            created.setOnInfoListener(object: MediaPlayer.OnInfoListener {
                override fun onInfo(source: MediaPlayer, what: Int, extra: Int): Boolean {
                    when (what) {
                        MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> listener.onFirstFrame()
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> setState(PlaybackState.BUFFERING)
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> if (state != PlaybackState.ENDED) setState(PlaybackState.READY)
                    }
                    return true
                }
            })
            created.prepareAsync()
        } catch (problem: IOException) {
            listener.onError(problem.message ?: "无法开始播放",false,false)
        } catch (problem: RuntimeException) {
            listener.onError(problem.message ?: "无法开始播放",false,false)
        }
    }

    fun play() { setPlayWhenReady(true) }
    fun pause() { setPlayWhenReady(false) }

    fun seekTo(position: Long) {
        val current = player ?: return
        if (state != PlaybackState.READY) return
        try { current.seekTo(position.coerceAtLeast(0).toInt()) } catch (_: RuntimeException) {}
    }

    /** 4.4 的 MediaPlayer 没有变速能力；保留入口让界面层不需要分支。 */
    fun setPlaybackSpeed(speed: Float) {}

    fun stop() { stopInternal() }

    fun release() {
        released = true
        stopInternal()
    }

    private fun stopInternal() {
        val current = player ?: return
        player = null
        try { current.setDisplay(null) } catch (_: RuntimeException) {}
        try { current.reset() } catch (_: RuntimeException) {}
        try { current.release() } catch (_: RuntimeException) {}
        state = PlaybackState.IDLE
        videoWidth = 0; videoHeight = 0
    }

    private fun setState(value: Int) {
        if (state == value) return
        state = value
        listener.onStateChanged(value)
    }

    private fun reportPlaying() {
        listener.onPlayingChanged(state == PlaybackState.READY && wantPlay)
    }

    /** 第二个值表示「这是解码/格式问题」，界面层据此降清晰度而不是重试；
     *  第三个值表示「这一条可以记进设备能力记录」。原始 what/extra 一律附在消息里：
     *  4.4 上这是唯一能把「网络取不到」和「解码器不接」分开的证据。 */
    private fun playbackErrorMessage(what: Int, extra: Int): Triple<String, Boolean, Boolean> {
        val (message, decoding, learn) = when {
            // 标准 MediaPlayer 错误码都是正数；负值说明底层把 status_t（-errno）直接当 what 透传了，
            // -38 就是 -ENOSYS。含义不明确，所以只沿清晰度阶梯降档，不写进设备能力记录。
            what < 0 -> Triple("这台设备无法解码该视频", true, false)
            what == MediaPlayer.MEDIA_ERROR_UNKNOWN && extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED ->
                Triple("这台电视不支持该视频的编码格式", true, true)
            what == MediaPlayer.MEDIA_ERROR_UNKNOWN && extra == MediaPlayer.MEDIA_ERROR_MALFORMED ->
                Triple("视频数据不完整，无法解码", true, true)
            what == MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK ->
                Triple("该视频不支持边播边读", true, false)
            what == MediaPlayer.MEDIA_ERROR_UNKNOWN -> Triple("播放失败，可能是网络或编码不支持", false, false)
            what == MediaPlayer.MEDIA_ERROR_SERVER_DIED -> Triple("系统播放器中断，请重试", false, false)
            else -> Triple("播放失败（播放器报错）", false, false)
        }
        return Triple("$message（播放器错误 $what/$extra）", decoding, learn)
    }
}

/** 播放失败。decoding 表示这是解码能力问题（应降清晰度）；learn 表示这条值得记进设备能力记录，
 *  只有确定是解码器本身的问题才为真，否则会误伤这一档的以后所有剧集。 */
internal class PlaybackFailure(message: String, val decoding: Boolean, val retryable: Boolean, val learn: Boolean=true) : java.io.IOException(message)
