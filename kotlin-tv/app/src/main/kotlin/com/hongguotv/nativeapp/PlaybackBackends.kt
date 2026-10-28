// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.SurfaceHolder
import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks

/**
 * 「这一档这台设备能不能播」和「用哪个后端播」这两件事的唯一出处。
 *
 * 判断顺序就是优先级：设备自己有硬解就用设备的（省 CPU、省电），没有才走随包的 FFmpeg 软解。
 * 这台 4.4 电视属于后者——服务端给它的每一档都是 bytevc1，系统里一个 H.265 解码器都没有。
 */
internal object PlaybackBackends {
    fun canPlay(codec: String, width: Int?, height: Int?): Boolean =
        CodecCapabilities.supports(codec, width, height) || SoftwareDecoder.supports(codec)
    fun canPlay(rendition: Rendition): Boolean = canPlay(rendition.codec, rendition.width, rendition.height)
    /** 容器头里读到的实际轨道。没有轨道信息时放行，让播放器自己回答。 */
    fun canPlay(track: VideoTracks.Track?): Boolean = track == null || canPlay(track.fourcc, track.width, track.height)

    /** 这一档实际会走哪条路，用来在界面上写清楚「现在是软解在跑」。 */
    fun usesSoftwareDecode(codec: String): Boolean =
        !CodecCapabilities.supports(codec, null, null) && SoftwareDecoder.available()

    fun create(context: Context, holder: SurfaceHolder, codec: String, listener: PlaybackListener): VideoPlayerBackend {
        // 调试开关：BuildConfig.FORCE_SOFT_DECODE 让这台明明有硬解的开发机也走随包软解，
        // 这样软解那条路在装机上能被完整验证，而不是只在没有硬解的电视上第一次跑通。
        if (BuildConfig.FORCE_SOFT_DECODE && SoftwareDecoder.available())
            return IjkPlayerPlayer(context, holder, listener)
        if (codec.isNotBlank() && !CodecCapabilities.supports(codec, null, null) && SoftwareDecoder.available())
            return IjkPlayerPlayer(context, holder, listener)
        return ExoPlayerPlayer(context, holder, listener)
    }
}
