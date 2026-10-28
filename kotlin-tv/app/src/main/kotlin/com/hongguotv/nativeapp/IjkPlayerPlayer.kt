// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import com.hongguotv.core.RemoteVideo
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.misc.IMediaDataSource

/**
 * 随包软解后端（ijkplayer 0.8.8 / FFmpeg 4.0）。
 *
 * 用它的原因很具体：这台 Android 4.4 电视没有任何 H.265 解码器，而服务端给这一集的所有档位
 * 都是 bytevc1。系统硬解那条路（[ExoPlayerPlayer]）在这种设备上必然失败，能播的唯一办法是
 * 自带解码器。ijkplayer 的 FFmpeg 内置 hevc 解码器，且它是 4.4 时代资料最多、minSdk 最低
 * 的一套（官方支持到 Android 4.1）。
 *
 * 取流不经过本机 http 端口：ijkplayer 有自己的 [IMediaDataSource] 随机读接口，直接接在
 * [RemoteVideo] 的「按字节范围给解密后的字节」上。它和 media3 那条路一样支持随机 seek，
 * 也不落整集临时文件。
 */
internal class IjkPlayerPlayer(
    context: Context,
    private val holder: SurfaceHolder,
    private val listener: PlaybackListener,
) : VideoPlayerBackend {
    private val player = IjkMediaPlayer()
    private var state = PlaybackState.IDLE
    private var frameWidth = 0
    private var frameHeight = 0
    private var mediaId = ""
    private var wantsPlay = false
    private var pendingSeek = 0L
    private var requestedSpeed = 1f
    private var speedApplied = false
    private var released = false

    init {
        // 装载失败时这里直接抛：调用方（PlaybackBackends）决定退回系统硬解，不静默降级。
        SoftwareDecoder.load().getOrThrow()
        // 让 FFmpeg 自己的错误进 logcat：这台机器上没有 logcat 可看，但开发机上必须能看到，
        // 否则「prepared 了却没有画面」这种中间状态没有任何地方能解释。
        IjkMediaPlayer.native_setLogLevel(
            if (BuildConfig.FORCE_SOFT_DECODE) IjkMediaPlayer.IJK_LOG_INFO else IjkMediaPlayer.IJK_LOG_WARN)
        player.setDisplay(holder)
        player.setScreenOnWhilePlaying(true)
        player.setOnPreparedListener(object : IMediaPlayer.OnPreparedListener {
            override fun onPrepared(mp: IMediaPlayer) {
                // prepared 时视频流已经打开。主动读一次尺寸：界面层在 READY 那一步用
                // hasVideoTrack 判断这一集到底有没有画面，不能等它自己报。
                reportVideoSize(mp.videoWidth, mp.videoHeight)
                val seek = pendingSeek; pendingSeek = 0L
                if (seek > 0) runCatching { mp.seekTo(seek) }
                applySpeed()
                Log.i("HongguoTV","Soft-decode prepared ok episode=${mediaId} duration=${mp.duration} frame=${mp.videoWidth}x${mp.videoHeight}")
                setState(PlaybackState.READY)
            }
        })
        player.setOnCompletionListener(object : IMediaPlayer.OnCompletionListener {
            override fun onCompletion(mp: IMediaPlayer) {
                Log.i("HongguoTV","Soft-decode completed episode=${mediaId}")
                setState(PlaybackState.ENDED)
            }
        })
        player.setOnVideoSizeChangedListener(object : IMediaPlayer.OnVideoSizeChangedListener {
            override fun onVideoSizeChanged(mp: IMediaPlayer, width: Int, height: Int, sarNum: Int, sarDen: Int) =
                reportVideoSize(width, height)
        })
        player.setOnInfoListener(object : IMediaPlayer.OnInfoListener {
            override fun onInfo(mp: IMediaPlayer, what: Int, extra: Int): Boolean {
                // MEDIA_INFO_VIDEO_RENDERING_START = 3：第一帧真的画出来了。
                if (what == 3 && !released) listener.onFirstFrame()
                return false
            }
            // 这一版 ijkplayer 的 OnInfoListener 比官方多了两个回调（SEI 与音频 PCM），
            // 接口多出来的方法也必须实现，否则编译不过。这里都不关心，原样返回。
            override fun onInfoSEI(mp: IMediaPlayer, what: Int, extra: Int, data: String?): Boolean = false
            override fun onInfoAudioPcmData(mp: IMediaPlayer, data: ByteArray?, count: Int) {}
        })
        player.setOnSeekCompleteListener(object : IMediaPlayer.OnSeekCompleteListener {
            override fun onSeekComplete(mp: IMediaPlayer) {
                if (!released) listener.onPositionMoved(mp.currentPosition.coerceAtLeast(0L), false)
            }
        })
        player.setOnErrorListener(object : IMediaPlayer.OnErrorListener {
            override fun onError(mp: IMediaPlayer, what: Int, extra: Int): Boolean {
                if (released) return true
                val (message, decoding, learn) = describeError(what, extra)
                Log.w("HongguoTV","Soft-decode error episode=${mediaId} what=$what extra=$extra message=$message")
                listener.onError("$message（软解错误 $what/$extra）", decoding, learn)
                return true
            }
        })
    }

    override val playbackState: Int get() = state
    override val playWhenReady: Boolean get() = wantsPlay
    override val isPlaying: Boolean get() = runCatching { player.isPlaying }.getOrDefault(false)
    override val duration: Long get() = runCatching { player.duration }.getOrDefault(0L).coerceAtLeast(0L)
    override val currentPosition: Long get() = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
    override val hasVideoTrack: Boolean get() = frameWidth > 0 && frameHeight > 0
    override val videoFrameWidth: Int get() = frameWidth
    override val videoFrameHeight: Int get() = frameHeight
    override val currentMediaId: String get() = mediaId
    override val backendName: String get() = "随包软解（FFmpeg）"

    override fun setPlayWhenReady(value: Boolean) {
        wantsPlay = value
        if (value) { if (state == PlaybackState.READY) runCatching { player.start() } }
        else runCatching { player.pause() }
        listener.onPlayingChanged(value && state == PlaybackState.READY)
    }

    override fun prepare(source: PlaybackSource, position: Long, autoplay: Boolean) {
        frameWidth = 0; frameHeight = 0; mediaId = source.mediaId
        wantsPlay = autoplay; pendingSeek = position.coerceAtLeast(0L)
        // 这条路的意义就是自带解码器：不去借用设备的 MediaCodec，免得又回到「这台电视没有这个解码器」。
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 0)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", if (autoplay) 1 else 0)
        // 老电视的 CPU 软解 H.265 可能跟不上：允许丢帧保住音画同步，比画面越拖越远要好。
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1)
        // 容器头由 RemoteVideo 已经读过的部分提供，探测范围给足即可，不必让 FFmpeg 再大范围试探。
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1048576)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2000000)
        player.setDataSource(RangeDataSource(source.video))
        setState(PlaybackState.BUFFERING)
        player.prepareAsync()
        Log.i("HongguoTV","Soft-decode playback prepared episode=${source.mediaId} size=${source.video.total}")
    }

    override fun play() = setPlayWhenReady(true)
    override fun pause() = setPlayWhenReady(false)

    override fun seekTo(position: Long) {
        val target = position.coerceAtLeast(0L)
        if (state != PlaybackState.READY) { pendingSeek = target; return }
        runCatching { player.seekTo(target) }.onFailure { Log.w("HongguoTV","Soft-decode seek failed: $it") }
    }

    /** 变速需要 ijkplayer 的 soundtouch 库，随包的这三个 .so 里没有它。
     *  所以这里能设成功就设，设不了就保持原速——界面层已经知道这一条路不一定有倍速。 */
    override fun setPlaybackSpeed(speed: Float) {
        requestedSpeed = speed
        applySpeed()
    }

    private fun applySpeed() {
        if (state != PlaybackState.READY) return
        speedApplied = runCatching { player.setSpeed(requestedSpeed); true }.getOrDefault(false)
        if (!speedApplied && requestedSpeed != 1f) Log.i("HongguoTV","Soft-decode backend keeps original speed (no soundtouch)")
    }

    override fun stop() {
        pendingSeek = 0L; frameWidth = 0; frameHeight = 0
        runCatching { player.reset() }
        setState(PlaybackState.IDLE)
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { player.reset() }
        runCatching { player.release() }
        state = PlaybackState.IDLE
    }

    private fun setState(value: Int) {
        if (state == value) return
        state = value
        listener.onStateChanged(value)
    }

    private fun reportVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (frameWidth == width && frameHeight == height) return
        frameWidth = width; frameHeight = height
        listener.onVideoSize(width, height)
    }

    /** 错误码分两套：负的一千多是 Android MediaPlayer 那套（MEDIA_ERROR_IO=-1004、
     *  MALFORMED=-1007、UNSUPPORTED=-1010、TIMED_OUT=-110），FFmpeg 自己的错误走
     *  FFP_MSG_ERROR=-10012，extra 是 AVERROR。取流失败不记进设备能力，
     *  只有明确「这种格式不支持」才记——记错一条会误伤这一档以后所有剧集。 */
    private fun describeError(what: Int, extra: Int): Triple<String, Boolean, Boolean> = when (what) {
        -1004, -110 -> Triple("读取视频时网络中断", false, false)
        -1007, -1010 -> Triple("这台设备无法解码该视频", true, true)
        -10012 -> Triple("随包的解码器读不出这个视频", true, false)
        else -> Triple("播放失败", false, false)
    }

    /**
     * 把 [RemoteVideo] 的按范围随机读接成 ijkplayer 的数据源。
     * FFmpeg 的 avio 会按自己的节奏分段读、也会 seek 回容器头，这两件事这里都支持。
     *
     * 这里带一层 512 KiB 的预读窗口，不是可选的优化：FFmpeg 的读缓冲只有 32 KiB，
     * 直接照搬的话每 32 KiB 就是一次 HTTP 分段请求，2 Mbps 的码率要每秒发八次请求，
     * 老电视上这条链路撑不住。窗口命中就纯内存拷贝，只有跨过窗口边界才再发一次请求。
     */
    private class RangeDataSource(private val video: RemoteVideo) : IMediaDataSource {
        private val window = 512 * 1024
        private var from = -1L
        private var bytes = ByteArray(0)
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= video.total || size <= 0) return -1
            if (from < 0 || position < from || position >= from + bytes.size) {
                val length = minOf(window.toLong(), video.total - position).toInt()
                bytes = video.read(position, length); from = position
                if (bytes.isEmpty()) return -1
            }
            val skip = (position - from).toInt()
            val n = minOf(size.toLong(), (bytes.size - skip).toLong(), (buffer.size - offset).toLong()).toInt()
            if (n <= 0) return -1
            bytes.copyInto(buffer, offset, skip, skip + n)
            return n
        }
        override fun getSize(): Long = video.total
        override fun close() { bytes = ByteArray(0); from = -1L }
    }
}
