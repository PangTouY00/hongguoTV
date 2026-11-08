// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Log
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.CachedVideo
import com.hongguotv.core.EpisodeCache
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.VideoSource
import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks
import okhttp3.OkHttpClient

/** `http` is set only when the stream was carried over from an earlier warm-up, so the caller
 *  hands that client to playback instead of opening a second connection for the same episode. */
internal data class OpenedStream(val video: VideoSource,val rendition: Rendition?,val tiers: List<Rendition>,val track: VideoTracks.Track?,val http: OkHttpClient?=null,val cached: Boolean=false)
/** 在线那条路交出来的总是 RemoteVideo，缓存流由 openCached 单独给出。预热与首页试播需要
 *  warm() 这类只有在线才有的能力，所以它们从这个入口取。 */
internal fun OpenedStream.remote(): RemoteVideo = video as RemoteVideo
/**
 * 源里确实有这一集，可它给的每一档这台设备都解不了。
 *
 * 和「取不到播放地址」「播放接口暂时不可用」是三类不同的事：后两种过一会儿再试就成了，
 * 这一种再试一百次也一样，所以界面要写清源里给的是哪一档，并且不该挂着自动重试。
 */
internal class NoDecodableRenditionException(detail: String): java.io.IOException(detail)
/** 把「解不了」说成具体是哪一档：只有一档时讲清只有这一档，多档时把尺寸列出来。 */
internal fun unplayableRenditionMessage(renditions: List<Rendition>): String {
    val sizes=renditions.mapNotNull { r -> r.width?.let { w -> r.height?.let { h -> "${w}×${h}" } } }.distinct()
    return when {
        sizes.size==1 -> "这一集只有 ${sizes.first()} 的一档，这台设备解不了，可以先看别的集。"
        sizes.size>1 -> "这一集的 ${sizes.joinToString("、")} 这几档，这台设备都解不了，可以先看别的集。"
        else -> "这一集没有这台设备能播放的清晰度，可以先看别的集。"
    }
}
/** Opens the first rendition the device can actually decode: select by API metadata, then
 *  verify the coded size read from the MP4 header and step down the ladder on mismatch. */
internal fun openStream(http: OkHttpClient,repository: ContentRepository,id: String,maxQuality: Int,
    supportsRendition: (Rendition)->Boolean,initialBytes: Int=256*1024,supportsTrack: (VideoTracks.Track?)->Boolean): OpenedStream? {
    val ladder=repository.streamLadder(id)
    for(rendition in ladder.playbackOrder(maxQuality,supportsRendition)) {
        val video=RemoteVideo(http,rendition.toStreamInfo())
        try {
            video.prepare(initialBytes)
            val track=video.trackInfo()
            if(supportsTrack(track)) return OpenedStream(video,rendition,ladder.renditions,track)
            Log.i("HongguoTV","Skipped ${rendition.quality}P ${rendition.codec}: coded ${track?.width}x${track?.height} beyond decoder capability")
            video.close()
        } catch(error: Exception) { video.close(); throw error }
    }
    // 源里一档都没有是另一回事（地址查询本身没拿到资源），留给调用方按原来的说法报。
    if(ladder.renditions.isNotEmpty()) throw NoDecodableRenditionException(unplayableRenditionMessage(ladder.renditions))
    return null
}
/**
 * 已缓存的一集直接本地播放。
 *
 * 不查清晰度阶梯、不发任何请求：签名过期、限流、设备注册这些在线才会出的问题都不存在。
 * 缓存档能不能解码仍然照同一套 trackInfo 校验判；判不过就返回 null，让调用方回退在线，
 * 在线那条路会自己挑这台设备解得了的那一档。
 */
internal fun openCached(cache: EpisodeCache,id: String,maxQuality: Int,supportsTrack: (VideoTracks.Track?)->Boolean): OpenedStream? {
    val video=cache.open(id,maxQuality) ?: return null
    try {
        video.prepare(256*1024)
        val track=video.trackInfo()
        if(!supportsTrack(track)) { Log.i("HongguoTV","Cached ${video.record.quality}P is beyond decoder capability"); video.close(); return null }
        return OpenedStream(video,null,emptyList(),track,null,true)
    } catch(error: Exception) { Log.w("HongguoTV","Cached episode could not be read: ${error.javaClass.simpleName}"); video.close(); return null }
}
/** Next manual retry tier: only offer a lower option than what was already attempted. */
internal fun nextLowerQuality(attempted: Int): Int? = PlaybackQuality.options.filter { it<attempted }.maxOrNull()
/** Auto-downgrade is worth another attempt while budget remains and a strictly lower tier exists. */
internal fun shouldAutoDowngrade(attempts: Int,maxAttempts: Int,tiers: List<Rendition>,currentQuality: Int): Boolean =
    attempts<maxAttempts && currentQuality>0 && tiers.any { it.quality in 1 until currentQuality }
