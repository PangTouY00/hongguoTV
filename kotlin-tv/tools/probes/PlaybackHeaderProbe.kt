// SPDX-License-Identifier: GPL-3.0-only
// 对照「取址请求要不要带 x-reading-request / x-tt-store-region」这两条头。
// 同一份注册身份、同一域名、同一集，只有请求头不同：B 不带、E 两条都带、F 只带 region。
//
// 本机不用 Gradle 的跑法：
//   cp tools/probes/PlaybackHeaderProbe.kt "$DSH_WORKSPACE/.toolchain/probe/"
//   REPO=/storage/emulated/0/Work/.wt-main/kotlin-tv \
//     CLASS=com.hongguotv.core.PlaybackHeaderProbe \
//     sh "$DSH_WORKSPACE/.toolchain/scripts/run-any-probe.sh" PlaybackHeaderProbe <video_id> [复用的device_id]
//
// 第二个参数可选：复用一个已知的 device_id（注册照做，Cookie 用新注册的，只换身份编号），
// 用来把「身份」这个变量按住不动，只测请求头。
//
// 2026-11-23 这一轮：B/E/F 三路都是 HTTP 200 + 0 字节，加与不加完全一致 —— 这两条头不是变量，
// 带上它们不会让请求变得更坏。同一轮里注册 Set-Cookie 实测下发 store-region=cn-sd（省级），
// 而请求头名是 x-tt-store-region：两个名字不同，取值要照 Cookie。结论写在 PLAYBACK_DIAGNOSIS.md 的 A 节第 6 步。
package com.hongguotv.core

import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object PlaybackHeaderProbe {
    private val PARAMS = linkedMapOf(
        "video_id" to "", "video_id_type" to "0", "device_level" to "1",
        "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
        "video_platform" to "1024", "source" to "0", "from_video_id" to "",
    )

    @JvmStatic fun main(args: Array<String>) {
        val videoId = args.getOrNull(0) ?: run { println("用法: PlaybackHeaderProbe <video_id>"); return }
        val reuse = args.getOrNull(1)
        val host = args.getOrNull(2) ?: "api5-normal-sinfonlineb.fqnovel.com"
        val http = OkHttpClient()
        PARAMS["video_id"] = videoId

        val registerBody = DeviceRegister.payload(VendorConstants.device)
        val register = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(registerBody, VendorConstants.device).forEach { (key, value) -> register.header(key, value) }
        register.post(registerBody.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
        // 第二个参数复用已知能出数据的 device_id：注册照做（Cookie 要真实），只换身份编号。
        val fresh = http.newCall(register.build()).execute().use { response ->
            DeviceRegister.parse(String(response.body?.bytes() ?: ByteArray(0), Charsets.UTF_8), response.headers("Set-Cookie"))
        } ?: run { println("注册失败"); return }
        val registered = if (reuse == null) fresh else fresh.copy(deviceId = reuse, installId = reuse)
        println("device_id=${registered.deviceId} cookies=${registered.cookies.entries.joinToString(", ") { (k, v) -> "$k=$v" }}")
        val cookie = registered.cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
        val region = registered.cookies["store-region"] ?: "cn"

        val profile = VendorConstants.device + mapOf(
            "device_id" to registered.deviceId, "iid" to registered.installId,
            "cdid" to UUID.randomUUID().toString(),
            "version_code" to "73932", "manifest_version_code" to "73932",
            "update_version_code" to "73932", "pv_player" to "73932", "version_name" to "7.3.9.32",
        )
        val base = "https://$host/reading/bookapi/video_tab/multi_video_model/v1/"

        // B 当前 main 的形态：v5 三头，没有这两条。
        run("B 基线（无这两条头）", http, base, profile, cookie) { signed -> signed }

        // E 本轮改动：照红果补上这两条。
        run("E 加 x-reading-request + x-tt-store-region=$region", http, base, profile, cookie) { signed ->
            val query = signed.url.substringAfter('?', "")
            val ticket = query.split('&').firstOrNull { it.startsWith("_rticket=") }
                ?.substringAfter('=')?.toLongOrNull() ?: System.currentTimeMillis()
            signed.copy(headers = signed.headers + mapOf(
                "x-reading-request" to "$ticket-${(100000000..999999999).random()}",
                "x-tt-store-region" to region,
                "x-tt-store-region-src" to "did",
            ))
        }

        // F 只加 region 两条，用来定位是哪一条引起变化。
        run("F 只加 x-tt-store-region", http, base, profile, cookie) { signed ->
            signed.copy(headers = signed.headers + mapOf(
                "x-tt-store-region" to region, "x-tt-store-region-src" to "did",
            ))
        }

        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    private fun run(label: String, http: OkHttpClient, base: String, profile: Map<String, String>,
                    cookie: String, transform: (Signer.Request) -> Signer.Request) {
        val signed = try {
            transform(Signer(mobileV5 = true).signPlayback(base, profile + PARAMS, null, System.currentTimeMillis()))
        } catch (error: Exception) {
            println("$label 签名失败 ${error.javaClass.simpleName} ${error.message?.take(60)}"); return
        }
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", VendorConstants.APP_UA)
            .header("Accept", "application/json; charset=utf-8")
            .header("Cookie", cookie)
        signed.headers.forEach { (key, value) -> request.header(key, value) }
        try {
            http.newCall(request.build()).execute().use { response ->
                val body = response.body?.bytes() ?: ByteArray(0)
                val text = String(body, Charsets.UTF_8)
                val playUrl = Regex("\"play_url\":\"(http[^\\\\\"]{0,80})").find(text)?.groupValues?.get(1) ?: "-"
                println("$label http=${response.code} bytes=${body.size} play_url=$playUrl " +
                    "code=${Regex("\"code\":(-?\\d+)").find(text)?.groupValues?.get(1) ?: "-"} " +
                    "headers=${signed.headers.keys.sorted().joinToString(",")}")
            }
        } catch (error: Exception) {
            println("$label 请求失败 ${error.javaClass.simpleName} ${error.message?.take(70)}")
        }
    }
}
