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
    @Volatile private var cachedInfos: List<MediaCodecInfo>? = null
    /** API 19 上 MediaCodecList 只有一个 private 构造器，getConstructor 找不到它；getCodecInfos 又要到
     *  API 21 才存在。两条路都试：先按名字取私有构造器，失败就退回 4.4 上真正可用的静态方法
     *  getCodecCount()/getCodecInfoAt()。之前只走第一条，结果整份列表读不到，所有档位都被当成可解。 */
    private fun probeCodecInfos(): List<MediaCodecInfo>? {
        val reflective=runCatching {
            val constructor=MediaCodecList::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible=true }
            val flags=runCatching { MediaCodecList::class.java.getField("REGULAR_CODECS").getInt(null) }.getOrDefault(-1)
            val list=constructor.newInstance(flags) as MediaCodecList
            @Suppress("UNCHECKED_CAST")
            (MediaCodecList::class.java.getMethod("getCodecInfos").invoke(list) as Array<MediaCodecInfo>).toList()
        }.getOrNull()
        if(!reflective.isNullOrEmpty()) return reflective
        val legacy=runCatching {
            val count=MediaCodecList::class.java.getMethod("getCodecCount").invoke(null) as Int
            val at=MediaCodecList::class.java.getMethod("getCodecInfoAt",Int::class.javaPrimitiveType)
            (0 until count).map { at.invoke(null,it) as MediaCodecInfo }
        }.getOrNull()
        return legacy?.takeIf { it.isNotEmpty() }
    }
    private fun codecInfos(): List<MediaCodecInfo>? {
        cachedInfos?.let { return it }
        val probed=probeCodecInfos() ?: return null
        cachedInfos=probed
        return probed
    }
    /** 探测本身成不成功。界面用它区分「本机确实没有这种解码器」和「本机问不出解码器」。 */
    fun probeAvailable(): Boolean = codecInfos()!=null
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
