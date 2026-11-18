// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecList
import android.media.MediaFormat
import com.hongguotv.core.CodecMemory
import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks

/**
 * 这台设备真解败过哪些档。
 *
 * 选档不再预判设备能力（理由见 StreamLadder 的注释）：厂商报出来的上限和 isSizeSupported 会把
 * 实际能解的尺寸判死，这部剧的 1924x1080 就是被它误杀的那一档。这里只留实测到的事实——
 * 播放真失败过的那一档被记下来，跨重启保留，用来把它排在后面，不用来排除它。
 * 设备能力仍然打一条日志，供排查，不参与任何判断。
 *
 * 另一半是 [memory]：真播通过过的**编码**。它不改选档顺序，只回答「要不要为私有编码再要一次
 * 兼容模型」——那一问在这台设备上实测每次都回空正文，一次一秒上下，问过一次就够了。
 */
object CodecCapabilities {
    private val lessons=DecoderLessons()
    private val playable=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var prefs: SharedPreferences?=null
    @Volatile private var logged=false
    fun init(context: Context) {
        if(prefs!=null) return
        val store=context.getSharedPreferences("native-decoder-lessons-v1",Context.MODE_PRIVATE)
        prefs=store
        lessons.restore(store.getStringSet("failures",emptySet()) ?: emptySet())
        // 合并而不是覆盖：init 之前记下的那几条也要留下。
        playable.addAll(store.getStringSet("playable",emptySet()) ?: emptySet())
    }
    /**
     * 给取址用的那份「这台设备真播通过过哪些编码」。
     *
     * 不要求调用顺序：`init` 之前拿到它也能记，只是那部分暂时只活在进程里；`init` 之后写的都落盘。
     */
    fun memory(): CodecMemory = object : CodecMemory {
        override fun isPlayable(codec: String) = codec.isNotBlank() && codec.lowercase() in playable
        override fun notePlayable(codec: String) {
            if(codec.isBlank()) return
            playable+=codec.lowercase()
            persistPlayable()
        }
    }
    private fun persistPlayable() {
        prefs?.edit()?.putStringSet("playable",playable.toSet())?.apply()
    }
    /** 首帧真的画出来了，这一集用的就是这种编码。 */
    fun notePlayableCodec(codec: String?) { if(!codec.isNullOrBlank()) memory().notePlayable(codec) }
    fun playableCodecs(): Set<String> = playable.toSet()
    /** 这台设备真解败过这一档。只用于排序，不用于排除。 */
    fun hasFailed(codec: String,width: Int?,height: Int?)=lessons.fails(codec,width,height)
    fun hasFailed(rendition: Rendition)=hasFailed(rendition.codec,rendition.width,rendition.height)
    fun hasFailed(track: VideoTracks.Track)=hasFailed(track.fourcc,track.width,track.height)
    /** Records a runtime decode failure; true when this is a new lesson. */
    fun learnFailure(codec: String,width: Int?,height: Int?): Boolean {
        val key=lessons.remember(codec,width,height) ?: return false
        prefs?.edit()?.putStringSet("failures",lessons.keys())?.apply()
        DiagLog.warn("Learned decoder failure $key")
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
        DiagLog.info("Decoder capabilities: ${lines.joinToString("; ")}")
    }
}
