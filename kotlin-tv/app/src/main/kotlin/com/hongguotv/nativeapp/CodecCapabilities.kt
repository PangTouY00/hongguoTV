// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecList
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
    fun mime(codec: String): String? = when(codec.lowercase()) {
        "h264","avc","avc1","avc3" -> MediaFormat.MIMETYPE_VIDEO_AVC
        "bytevc1","hvc1","hev1","h265","hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
        else -> null
    }
    fun supports(codec: String,width: Int?,height: Int?): Boolean {
        if(lessons.fails(codec,width,height)) return false
        val type=mime(codec) ?: return true
        if(width==null || height==null || width<=0 || height<=0) return true
        val key="$type $width $height"
        synchronized(verdicts) { verdicts[key]?.let { return it } }
        val verdict=runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && runCatching {
                    info.getCapabilitiesForType(type).videoCapabilities.isSizeSupported(width,height)
                }.getOrDefault(false)
            }
        }.getOrDefault(true)   // A broken ROM probe must not block playback.
        synchronized(verdicts) { verdicts[key]=verdict }
        return verdict
    }
    fun supports(rendition: Rendition)=supports(rendition.codec,rendition.width,rendition.height)
    fun supports(track: VideoTracks.Track)=supports(track.fourcc,track.width,track.height)
    /**
     * 播放侧的判定：静态探测说「超出上限」但只差一点时，先真解一次，让解码器自己回答。
     *
     * 厂商报出来的上限常常偏保守，isSizeSupported 还按「宽度必须是 16 的倍数」这类对齐条件拒绝。
     * 实测这部剧有二十来集源里只给 1924x1080 一档——比常见的 1920 上限宽 4 个像素，标准里完全合法，
     * 按静态探测却被直接排除，于是这一集连试都没试就显示失败。ExoPlayer 这边本来就开了
     * setEnableDecoderFallback(true)，软解能接住这种尺寸，真解一次的代价只是多等几秒。
     * 解不成由 learnFailure 记住，之后不再白试；超出上限太多的（4K 对 1080 上限）照旧直接跳过。
     */
    fun worthTrying(codec: String,width: Int?,height: Int?): Boolean {
        if(lessons.fails(codec,width,height)) return false
        if(supports(codec,width,height)) return true
        val type=mime(codec) ?: return true
        if(width==null || height==null || width<=0 || height<=0) return true
        val ceiling=reportedCeiling(type) ?: return true   // 探测不出上限时不拦播放。
        return width<=ceiling.first+slack(ceiling.first) && height<=ceiling.second+slack(ceiling.second)
    }
    fun worthTrying(rendition: Rendition)=worthTrying(rendition.codec,rendition.width,rendition.height)
    fun worthTrying(track: VideoTracks.Track)=worthTrying(track.fourcc,track.width,track.height)
    /** 容差取「64 像素或上限的十六分之一，取大」：1924 对 1920 落在里面，3840 对 1920 不落在里面。 */
    private fun slack(upper: Int)=maxOf(64,upper/16)
    private val ceilings=HashMap<String,Pair<Int,Int>?>()
    /** 本机对该编码报告的最大宽高，取所有解码器里最宽的那一个；探测不到返回 null。 */
    private fun reportedCeiling(type: String): Pair<Int,Int>? {
        synchronized(ceilings) { if(ceilings.containsKey(type)) return ceilings[type] }
        val best=runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
                .mapNotNull { info -> runCatching {
                    val video=info.getCapabilitiesForType(type).videoCapabilities
                    Pair(video.supportedWidths.upper,video.supportedHeights.upper)
                }.getOrNull() }
                .reduceOrNull { a,b -> Pair(maxOf(a.first,b.first),maxOf(a.second,b.second)) }
        }.getOrNull()
        synchronized(ceilings) { ceilings[type]=best }
        return best
    }
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
