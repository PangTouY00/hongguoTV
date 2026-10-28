// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 随包携带的软件解码器（ijkplayer 0.8.8 携带的 FFmpeg）。
 *
 * 这台 4.4 电视的系统里没有 HEVC 解码器，而服务端给这一集的每一档都是 bytevc1（H.265）。
 * 系统硬解那条路对它没有任何出路，所以随包带一份 FFmpeg 解码器：不借用设备的 MediaCodec，
 * 解码完全在随包的 .so 里完成。代价是吃 CPU，换来的是「这台电视能播」。
 *
 * 装载只做一次，失败原因留着给界面诊断用——加载失败必须能看出是缺库还是 ABI 不对。
 */
internal object SoftwareDecoder {
    /** 随包 FFmpeg 的内置解码器覆盖这些编码（按接口可能给出的写法列）。 */
    private val builtIn = setOf(
        "h264", "avc", "avc1", "avc3",            // H.264（FFmpeg h264 解码器）
        "bytevc1", "hvc1", "hev1", "hevc", "h265", // H.265（FFmpeg hevc 解码器）
        "mpeg4", "mp4v", "xvid",                   // 老 MP4 编码
        "av1",
    )

    @Volatile private var attempt: Result<Unit>? = null

    /** 装载随包的 libijkplayer/libijksdl/libijkffmpeg。只跑一次，结果缓存。
     *  必须在后台线程调用：这几百万字节的 .so 加载不是瞬间的事。 */
    fun load(): Result<Unit> {
        attempt?.let { return it }
        synchronized(this) {
            attempt?.let { return it }
            val result = runCatching { IjkMediaPlayer.loadLibrariesOnce(null) }
            attempt = result
            result.onSuccess { android.util.Log.i("HongguoTV","Software decoder loaded (ijkplayer/FFmpeg)") }
                .onFailure { android.util.Log.w("HongguoTV","Software decoder failed to load: ${it.javaClass.name} ${it.message}") }
            return result
        }
    }
    fun available(): Boolean = load().isSuccess
    /** 装载失败时的完整异常链，给「网络诊断」那一页直接显示。 */
    fun loadFailure(): String? {
        val error = load().exceptionOrNull() ?: return null
        val chain = ArrayList<String>()
        var cursor: Throwable? = error
        while (cursor != null && chain.size < 6) { chain += "${cursor.javaClass.name}: ${cursor.message}"; cursor = cursor.cause }
        return chain.joinToString(" ← ")
    }
    /** 这种编码能不能交给随包的 FFmpeg 解。这里只回答「解码器在不在」，
     *  跑不跑得动（CPU 够不够）只有真播了才知道。 */
    fun supports(codec: String): Boolean = codec.lowercase() in builtIn && available()
    fun summary(): String = when {
        available() -> "随包 FFmpeg 已装载，可软解 ${builtIn.joinToString("/")}"
        else -> "随包 FFmpeg 装载失败：${loadFailure()}"
    }
}
