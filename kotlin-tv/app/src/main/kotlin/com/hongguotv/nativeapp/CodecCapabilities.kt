// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks

/** Device decoder capability probe. TV boxes commonly cap AVC decoders near 1920x1088 with
 *  width/height checked independently, so portrait sizes like 1080x1920 are rejected while
 *  landscape 1920x1080 plays fine — and some boxes claim support they don't have. Learned
 *  runtime failures (persisted across restarts) override the static probe. */
object CodecCapabilities {
    private val lessons=DecoderLessons()
    private var prefs: SharedPreferences?=null
    private val verdicts=HashMap<String,Boolean>()
    @Volatile private var logged=false
    fun init(context: Context) {
        if(prefs!=null) return
        val store=context.getSharedPreferences("native-decoder-lessons-v1",Context.MODE_PRIVATE)
        prefs=store
        lessons.restore(store.getStringSet("failures",emptySet()) ?: emptySet())
    }
    // MediaFormat.MIMETYPE_VIDEO_* 是 API 21 的常量，值本身从 API 18 起就是这两个字符串。
    private const val VIDEO_AVC = "video/avc"
    private const val VIDEO_HEVC = "video/hevc"

    fun mime(codec: String): String? = when(codec.lowercase()) {
        "h264","avc","avc1","avc3" -> VIDEO_AVC
        "bytevc1","hvc1","hev1","h265","hevc" -> VIDEO_HEVC
        else -> null
    }
    /** MediaCodecList 的构造函数在 API 19 上不是 public，REGULAR_CODECS 也还不存在，按名字取。 */
    private fun codecInfos(): List<MediaCodecInfo>? = try {
        val constructor=MediaCodecList::class.java.getConstructor(Int::class.javaPrimitiveType).apply { setAccessible(true) }
        val flags=try { MediaCodecList::class.java.getField("REGULAR_CODECS").getInt(null) } catch (_: Exception) { -1 }
        val list=constructor.newInstance(flags) as MediaCodecList
        @Suppress("UNCHECKED_CAST")
        val infos=MediaCodecList::class.java.getMethod("getCodecInfos").invoke(list) as Array<MediaCodecInfo>
        infos.toList()
    } catch (_: Exception) { null }
    fun supports(codec: String,width: Int?,height: Int?): Boolean {
        if(lessons.fails(codec,width,height)) return false
        val type=mime(codec) ?: return true
        if(width==null || height==null || width<=0 || height<=0) return true
        val key="$type $width $height"
        synchronized(verdicts) { verdicts[key]?.let { return it } }
        val verdict=runCatching {
            codecInfos()?.any { info ->
                // API 19 的 CodecCapabilities 只有 colorFormats／profileLevels，没有任何尺寸查询
                // （isSizeSupported 与 videoCapabilities 都是 API 21）。这里只回答「这台机器上有没有
                // 解码器声明支持这种格式」；尺寸能不能解由 MediaPlayer 自己回答，真失败会被 learnFailure
                // 记下来，同一档尺寸不会再试第二次。
                !info.isEncoder && runCatching { info.getCapabilitiesForType(type) }.isSuccess
            } ?: true
        }.getOrDefault(true)   // A broken ROM probe must not block playback.
        synchronized(verdicts) { verdicts[key]=verdict }
        return verdict
    }
    fun supports(rendition: Rendition)=supports(rendition.codec,rendition.width,rendition.height)
    fun supports(track: VideoTracks.Track)=supports(track.fourcc,track.width,track.height)
    /** Records a runtime decode failure; true when the static probe had claimed support (over-reporting). */
    fun learnFailure(codec: String,width: Int?,height: Int?): Boolean {
        val overReported=supports(codec,width,height)
        val key=lessons.remember(codec,width,height) ?: return false
        prefs?.edit()?.putStringSet("failures",lessons.keys())?.apply()
        android.util.Log.w("HongguoTV","Learned decoder failure $key (static probe claimed support: $overReported)")
        return overReported
    }
    fun resetLearned() {
        lessons.reset()
        prefs?.edit()?.putStringSet("failures",lessons.keys())?.apply()
    }
    fun learnedCount()=lessons.size()
    /** 4.4 上能问出来的只有「这个解码器声明支持哪些类型」。日志与界面诊断共用这一份。 */
    fun deviceSummary(): String {
        val infos=codecInfos() ?: return "读不到本机解码器列表"
        val decoders=infos.filter { !it.isEncoder }
        if(decoders.isEmpty()) return "没有枚举到任何解码器"
        return decoders.joinToString("; ") { info -> "${info.name} supports ${info.supportedTypes.joinToString(",")}" }
    }
    fun logDeviceCapabilities() {
        if(logged) return; logged=true
        android.util.Log.i("HongguoTV","Decoder capabilities: ${deviceSummary()}")
    }
}
