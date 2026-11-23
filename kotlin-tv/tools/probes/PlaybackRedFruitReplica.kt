// SPDX-License-Identifier: GPL-3.0-only
// 红果能播、我们的取址拿不到数据 —— 这个探针把两者的差异逐项拆开测。
// 基准是 2026-11-23 20:01 从红果进程里直接读到的那条真实请求（tmp/hongguo-net/frida-hdr.txt）：
// POST /novel/player/video_model/v1/，gzip 正文，签名同时带旧族与 v5 族，
// Cookie 里有 odin_tt / passport_csrf_token，参数比我们多一整套设备字段。
// 每一路只改一个变量：A 照抄、B 去掉 passport 那几条 Cookie、C 正文不压缩、D 只带 v5 三头、E 不带 Cookie。
//
// 2026-11-23 这一轮的结果：五路全部 HTTP 200 + 0 字节。照抄红果的 30 个 query 参数、它的完整 Cookie、
// gzip 正文、两族签名、全套杂头，没有一个能让它给数据。同一轮走完整取址链得到的是明确的
// PlaybackLoginRequiredException「播放需要登录红果账号」——差异不在请求形态，在登录态。
// 下面内嵌的这份身份是未登录的：Cookie 里没有 sessionid，只有 odin_tt / passport_csrf_token 这类设备与 CSRF 项。
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

object PlaybackRedFruitReplica {
    private val BASE = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/"
    private const val DEVICE_ID = "4201647359921034"
    private const val IID = "4201647359925130"
    private const val CDID = "bfa7e216-ad54-4101-9829-975865c2f979"
    private const val UA = "com.phoenix.read/74532 (Linux; U; Android 14; en_US; sdk_gphone64_x86_64; Build/UE1A.230829.050;tt-ok/3.12.13.20)"

    // 照抄抓包里的 query（_rticket / iid / device_id / cdid 每次重新生成，不在这里）
    private val RF_PARAMS = linkedMapOf(
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "channel" to "xiaomi_8662_64",
        "aid" to "8662",
        "app_name" to "novelread",
        "version_code" to "74532",
        "version_name" to "7.4.5.32",
        "manifest_version_code" to "74532",
        "update_version_code" to "74532",
        "resolution" to "1080*2209",
        "dpi" to "420",
        "device_type" to "sdk_gphone64_x86_64",
        "device_brand" to "google",
        "language" to "en",
        "os_api" to "34",
        "os_version" to "14",
        "ac" to "wifi",
        "is_64_runtime" to "true",
        "is_64_device" to "true",
        "host_abi" to "arm64-v8a",
        "dragon_device_type" to "phone",
        "pv_player" to "74532",
        "is_x86_device" to "true",
        "compliance_status" to "0",
        "need_personal_recommend" to "1",
        "player_so_load" to "1",
        "is_android_pad_screen" to "0",
        "rom_version" to "sdk_gphone64_x86_64-userdebug+14+UE1A.230829.050+12077443+dev-keys",
        "klink_egdi" to "AALkqqo145SeLjDOzR-up5Il5rh-jVJCrhwa9GTIGGRMNsKs69ZSJbag",
    )
    private const val COOKIE = "store-region=cn-sd; store-region-src=did; install_id=4201647359925130; ttreq=1\$b5763ea4d14d784350d5b9488873376df8290573; odin_tt=048c2634ce63ee3ab84e2803f8c45892055f4baf4e2dce1a6192dc97c436d6582dea921114a7c1341c87baa8e49cf65370d15347b0d7a153a4f01574a528b225bb47cb0e757d86282995d0497bbc4662; passport_csrf_token=7a3c8384f176d35a8eb95521959f8f0f; passport_csrf_token_default=7a3c8384f176d35a8eb95521959f8f0f"

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    @JvmStatic fun main(args: Array<String>) {
        val videoId = args.getOrNull(0) ?: run { println("用法: PlaybackRedFruitReplica <video_id>"); return }
        val json = """{"video_id":"$videoId","biz_param":{"detail_page_version":0,"device_level":3,""" +
            """"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,""" +
            """"use_os_player":false,"use_server_dns":false,"video_platform":1024}}"""
        val gz = gzip(json.toByteArray())
        val params = RF_PARAMS + mapOf(
            "_rticket" to System.currentTimeMillis().toString(),
            "iid" to IID, "device_id" to DEVICE_ID, "cdid" to CDID,
        )
        val passport = setOf("odin_tt", "passport_csrf_token", "passport_csrf_token_default")
        val bareCookie = COOKIE.split("; ").filterNot { it.substringBefore('=').trim() in passport }.joinToString("; ")
        val http = OkHttpClient()

        call(http, "A 照抄红果（gzip 正文 + 两族签名 + 完整 Cookie）", params, gz, COOKIE, both = true, compressed = true)
        call(http, "B 去掉 odin_tt/passport_csrf_token", params, gz, bareCookie, both = true, compressed = true)
        call(http, "C 正文不压缩（我们的写法）", params, json.toByteArray(), COOKIE, both = true, compressed = false)
        call(http, "D 只带 v5 三头（我们的签名收窄）", params, gz, COOKIE, both = false, compressed = true)
        call(http, "E 不带任何 Cookie", params, gz, "", both = true, compressed = true)

        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    private fun call(http: OkHttpClient, label: String, params: Map<String, String>, body: ByteArray,
                     cookie: String, both: Boolean, compressed: Boolean) {
        val signed = try {
            Signer(mobileV5 = true).signPlayback(BASE, params, body, System.currentTimeMillis())
        } catch (error: Exception) {
            println("$label 签名失败 ${error.javaClass.simpleName} ${error.message?.take(60)}"); return
        }
        val query = signed.url.substringAfter('?', "")
        val ticket = query.split('&').firstOrNull { it.startsWith("_rticket=") }
            ?.substringAfter('=')?.toLongOrNull() ?: System.currentTimeMillis()
        val headers = linkedMapOf<String, String>()
        headers.putAll(signed.headers)
        if (both) headers.putAll(PlayerSigner.headers(query, body, ts = ticket / 1000, ticket = ticket))
        headers["x-reading-request"] = "$ticket-${(100000000..999999999).random()}"
        headers["x-tt-store-region"] = "cn-sd"; headers["x-tt-store-region-src"] = "did"
        headers["x-vc-bdturing-sdk-version"] = "4.0.3.cn"; headers["lc"] = "101"
        headers["sdk-version"] = "2"; headers["passport-sdk-version"] = "5051452"
        headers["X-Xs-From-Web"] = "0"
        if (compressed) headers["Content-Encoding"] = "gzip"

        val builder = Request.Builder().url(signed.url)
            .header("User-Agent", UA)
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        headers.forEach { (key, value) -> builder.header(key, value) }

        try {
            http.newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val text = String(bytes, Charsets.UTF_8)
                val code = Regex("\"[Cc]ode\":(-?\\d+)").find(text)?.groupValues?.get(1) ?: "-"
                val playUrl = Regex("\"play_url\":\"(http[^\\\\\"]{0,70})").find(text)?.groupValues?.get(1) ?: "-"
                println("$label http=${response.code} bytes=${bytes.size} code=$code play_url=$playUrl " +
                    "logid=${response.header("x-tt-logid").orEmpty().take(24)}")
            }
        } catch (error: Exception) {
            println("$label 请求失败 ${error.javaClass.simpleName} ${error.message?.take(70)}")
        }
    }
}
