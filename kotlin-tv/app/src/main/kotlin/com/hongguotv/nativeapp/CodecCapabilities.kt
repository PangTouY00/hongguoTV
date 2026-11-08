// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecList
import android.media.MediaFormat
import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks

/**
 * 这台设备真解败过哪些档。
 *
 * 选档不再预判设备能力（理由见 StreamLadder 的注释）：厂商报出来的上限和 isSizeSupported 会把
 * 实际能解的尺寸判死，这部剧的 1924x1080 就是被它误杀的那一档。这里只留实测到的事实——
 * 播放真失败过的那一档被记下来，跨重启保留，用来把它排在后面，不用来排除它。
 * 设备能力仍然打一条日志，供排查，不参与任何判断。
 */
object CodecCapabilities {
    private val lessons=DecoderLessons()
    private var prefs: SharedPreferences?=null
    @Volatile private var logged=false
    fun init(context: Context) {
        if(prefs!=null) return
        val store=context.getSharedPreferences("native-decoder-lessons-v1",Context.MODE_PRIVATE)
        prefs=store
        lessons.restore(store.getStringSet("failures",emptySet()) ?: emptySet())
    }
    /** 这台设备真解败过这一档。只用于排序，不用于排除。 */
    fun hasFailed(codec: String,width: Int?,height: Int?)=lessons.fails(codec,width,height)
    fun hasFailed(rendition: Rendition)=hasFailed(rendition.codec,rendition.width,rendition.height)
    fun hasFailed(track: VideoTracks.Track)=hasFailed(track.fourcc,track.width,track.height)
    /** Records a runtime decode failure; true when this is a new lesson. */
    fun learnFailure(codec: String,width: Int?,height: Int?): Boolean {
        val key=lessons.remember(codec,width,height) ?: return false
        prefs?.edit()?.putStringSet("failures",lessons.keys())?.apply()
        android.util.Log.w("HongguoTV","Learned decoder failure $key")
        return true
    }
    fun resetLearned() {
        lessons.reset()
        prefs?.edit()?.putStringSet("failures",lessons.keys())?.apply()
    }
    fun learnedCount()=lessons.size()
    fun logDeviceCapabilities() {
        if(logged) return; logged=true
        val lines=runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { info ->
                listOf(MediaFormat.MIMETYPE_VIDEO_AVC,MediaFormat.MIMETYPE_VIDEO_HEVC)
                    .filter { type -> runCatching { info.getCapabilitiesForType(type) }.isSuccess }
                    .map { type ->
                        val video=info.getCapabilitiesForType(type).videoCapabilities
                        "${info.name} $type up to ${video.supportedWidths.upper}x${video.supportedHeights.upper}"
                    }
            }
        }.getOrNull() ?: return
        android.util.Log.i("HongguoTV","Decoder capabilities: ${lines.joinToString("; ")}")
    }
}
