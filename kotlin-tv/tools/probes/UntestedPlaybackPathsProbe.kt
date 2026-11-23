// 今天还没测过的两条取址路径：
//   1) POST /novel/player/video_model/v1/          —— 仓库里带账号的那条后备，本轮没跑过
//   2) POST /novel/player/multi_video_model/<dr_scene>/v1/ —— 官方 App 包里有这条带场景段的路径，本仓库没有
// 全部用匿名身份（不带 Cookie），先确认接口本身放不放行。
//
// 跑法：
//   REPO=/storage/emulated/0/Work/.wt-main/kotlin-tv \
//     CLASS=com.hongguotv.core.UntestedPlaybackPathsProbe \
//     sh "$DSH_WORKSPACE/.toolchain/scripts/run-any-probe.sh" UntestedPlaybackPathsProbe
package com.hongguotv.core

import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object UntestedPlaybackPathsProbe {
    private const val VID = "7691249364097829913"
    private const val HOST = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val BIZ = """{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,""" +
        """"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,""" +
        """"use_server_dns":false,"video_platform":1024}"""

    private fun attempt(label: String, path: String, body: ByteArray, http: OkHttpClient, profile: Map<String, String>) {
        val signed = try {
            Signer(mobileV5 = true).signPlayback("$HOST$path", profile, body, System.currentTimeMillis())
        } catch (error: Throwable) {
            println("$label 签名失败 " + error.message); return
        }
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", VendorConstants.VIDEO_UA)
            .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
            .post(signed.body!!.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                println("$label http=" + response.code + " bytes=" + bytes.size +
                    (if (bytes.isEmpty()) "" else " 正文前 200: " + String(bytes, 0, minOf(200, bytes.size))))
            }
        } catch (error: Throwable) {
            println("$label ERROR " + error.javaClass.simpleName + ": " + error.message)
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val profile = VendorConstants.device + mapOf("cdid" to UUID.randomUUID().toString())

        attempt("video_model/v1", "/novel/player/video_model/v1/",
            ("""{"video_id":"$VID","biz_param":$BIZ}""").toByteArray(), http, profile)
        attempt("multi_video_model/v1", "/novel/player/multi_video_model/v1/",
            ("""{"biz_param":$BIZ,"mixed_video_id_map":{"1004":["$VID"]}}""").toByteArray(), http, profile)
        for (scene in listOf("video_player", "video_playback", "default", "short_play")) {
            attempt("带场景段 dr_scene=$scene", "/novel/player/multi_video_model/$scene/v1/",
                ("""{"biz_param":$BIZ,"mixed_video_id_map":{"1004":["$VID"]}}""").toByteArray(), http, profile)
        }
    }
}
