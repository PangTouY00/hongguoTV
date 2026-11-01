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
