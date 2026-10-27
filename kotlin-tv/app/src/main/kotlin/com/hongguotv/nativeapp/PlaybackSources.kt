// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Log
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks
import okhttp3.OkHttpClient

/** `http` is set only when the stream was carried over from an earlier warm-up, so the caller
 *  hands that client to playback instead of opening a second connection for the same episode. */
internal data class OpenedStream(val video: RemoteVideo,val rendition: Rendition?,val tiers: List<Rendition>,val track: VideoTracks.Track?,val http: OkHttpClient?=null)
/** 接口给了清晰度，但本机一个都接不上。每一档的实际参数直接写进异常，界面上能看到是哪一步拦住的。 */
internal class NoPlayableRendition(val reasons: List<String>,val maxQuality: Int): java.io.IOException(
    if(reasons.isEmpty()) "播放接口没有给出可用的清晰度（当前上限 ${maxQuality}P）"
    else "没有电视可播放的清晰度：${reasons.joinToString(" / ")}（当前上限 ${maxQuality}P）")
/** Opens the first rendition the device can actually decode: select by API metadata, then
 *  verify the coded size read from the MP4 header and step down the ladder on mismatch. */
internal fun openStream(http: OkHttpClient,repository: ContentRepository,id: String,maxQuality: Int,
    supportsRendition: (Rendition)->Boolean,initialBytes: Int=256*1024,supportsTrack: (VideoTracks.Track?)->Boolean): OpenedStream {
    val ladder=repository.streamLadder(id)
    val order=ladder.playbackOrder(maxQuality,supportsRendition)
    // 一档都没进候选：拦人的是接口元数据（codec／上限），不是播放器。把阶梯原样报出来。
    if(order.isEmpty()) throw NoPlayableRendition(ladder.renditions.map { renditionSummary(it) },maxQuality)
    for(rendition in order) {
        val video=RemoteVideo(http,rendition.toStreamInfo())
        try {
            video.prepare(initialBytes)
            val track=video.trackInfo()
            if(supportsTrack(track)) return OpenedStream(video,rendition,ladder.renditions,track)
            Log.i("HongguoTV","Skipped ${rendition.quality}P ${rendition.codec}: coded ${track?.width}x${track?.height} beyond decoder capability")
            video.close()
        } catch(error: Exception) { video.close(); throw error }
    }
    // 每一档都试过了，MP4 头里的实际尺寸本机接不了。
    throw NoPlayableRendition(order.map { renditionSummary(it) },maxQuality)
}
private fun renditionSummary(rendition: Rendition)=
    "${rendition.quality}P ${rendition.codec.ifBlank { "未知编码" }} ${rendition.width}x${rendition.height}"
/** Next manual retry tier: only offer a lower option than what was already attempted. */
internal fun nextLowerQuality(attempted: Int): Int? = PlaybackQuality.options.filter { it<attempted }.maxOrNull()
/** Auto-downgrade is worth another attempt while budget remains and a strictly lower tier exists. */
internal fun shouldAutoDowngrade(attempts: Int,maxAttempts: Int,tiers: List<Rendition>,currentQuality: Int): Boolean =
    attempts<maxAttempts && currentQuality>0 && tiers.any { it.quality in 1 until currentQuality }
