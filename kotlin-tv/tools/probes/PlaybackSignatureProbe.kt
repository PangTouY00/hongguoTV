// SPDX-License-Identifier: GPL-3.0-only
// 取址回 HTTP 200 + 0 字节时的第一件要做的事：把「签名头族」单独当变量测一遍。
//
// 这不是单元测试，是排查工具。它不进 Gradle 的源码目录，跑法（本机，不用 Gradle）：
//
//   cp kotlin-tv/tools/probes/PlaybackSignatureProbe.kt "$DSH_WORKSPACE/.toolchain/probe/"
//   REPO=/storage/emulated/0/Work/.wt-main/kotlin-tv \
//     CLASS=com.hongguotv.core.PlaybackSignatureProbe \
//     sh "$DSH_WORKSPACE/.toolchain/scripts/run-any-probe.sh" PlaybackSignatureProbe <video_id> [域名]
//
// 四路用的是同一份新注册身份、同一个域名、同一份参数，只有签名头不同。判读：
//   - 只有 B 给数据 → 变量就是签名头族，照 B 的形态改请求（PLAYBACK_DIAGNOSIS.md 的 A 节）。
//   - 四路全空 → 不是签名问题，按 PLAYBACK_DIAGNOSIS.md 走别的分支（注册、身份资格、这一集本身没资源）。
//   - D 也给数据 → 多带旧头不会导致被拒，别把「少发一个头」当成修复方向。
//
// 2026-11-23 这一轮的结果写在 PLAYBACK_V5.md 的「同日更正」一节：A 与 C 回 0 字节，
// B 回 22070 字节完整模型，D 回 22078 字节。
package com.hongguotv.core

import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object PlaybackSignatureProbe {
    private val PARAMS = linkedMapOf(
        "video_id" to "", "video_id_type" to "0", "device_level" to "1",
        "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
        "video_platform" to "1024", "source" to "0", "from_video_id" to "",
    )

    @JvmStatic fun main(args: Array<String>) {
        val videoId = args.getOrNull(0) ?: run { println("用法: PlaybackSignatureProbe <video_id> [域名]"); return }
        val host = args.getOrNull(1) ?: "api5-normal-sinfonlineb.fqnovel.com"
        val http = OkHttpClient()
        PARAMS["video_id"] = videoId

        val registerBody = DeviceRegister.payload(VendorConstants.device)
        val register = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(registerBody, VendorConstants.device).forEach { (key, value) -> register.header(key, value) }
        register.post(registerBody.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
        val registered = http.newCall(register.build()).execute().use { response ->
            DeviceRegister.parse(String(response.body?.bytes() ?: ByteArray(0), Charsets.UTF_8), response.headers("Set-Cookie"))
        } ?: run { println("注册失败，先查注册域名（log.snssdk.com 可能直接回 444）"); return }
        println("device_id=${registered.deviceId} iid=${registered.installId}")
        val cookie = registered.cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }

        val profile = VendorConstants.device + mapOf(
            "device_id" to registered.deviceId, "iid" to registered.installId,
            "cdid" to UUID.randomUUID().toString(),
            "version_code" to "73932", "manifest_version_code" to "73932",
            "update_version_code" to "73932", "pv_player" to "73932", "version_name" to "7.3.9.32",
        )
        val base = "https://$host/reading/bookapi/video_tab/multi_video_model/v1/"

        // A 当前线上形态：Signer 签名后只保留 PlayerSigner 真算的那一组。
        run("A 旧族 argus+ladon+gorgon+khronos", http, base, profile, cookie) { values ->
            val signed = Signer().signPlayback(base, values, null, System.currentTimeMillis())
            val query = signed.url.substringAfter('?', "")
            val ticket = query.split('&').firstOrNull { it.startsWith("_rticket=") }
                ?.substringAfter('=')?.toLongOrNull() ?: System.currentTimeMillis()
            val kept = signed.headers.filterKeys { it.lowercase() !in setOf(
                "x-argus", "x-ladon", "x-khronos", "x-gorgon", "x-helios", "x-medusa",
                "x-ss-stub", "x-ss-req-ticket", "x-medias", "x-tt-dt") }
            signed.copy(headers = kept + PlayerSigner.headers(query, null, ts = ticket / 1000, ticket = ticket))
        }

        // B v5 那一族：只留 x-khronos / x-helios / x-medusa，正文摘要按空内容算。
        run("B v5 族 khronos+helios+medusa", http, base, profile, cookie) { values ->
            Signer(mobileV5 = true).signPlayback(base, values, null, System.currentTimeMillis())
        }

        // C 完全不签名。
        run("C 无签名", http, base, profile, cookie) { values ->
            val query = Signer.encode(values + mapOf(
                "_rticket" to System.currentTimeMillis().toString()))
            Signer.Request("$base?$query", emptyMap(), null)
        }

        // D v5 三头再额外补上旧族：用来判断「多带一个头会不会被拒」。
        run("D v5 族 + argus/ladon", http, base, profile, cookie) { values ->
            val signed = Signer(mobileV5 = true).signPlayback(base, values, null, System.currentTimeMillis())
            val query = signed.url.substringAfter('?', "")
            val ticket = query.split('&').firstOrNull { it.startsWith("_rticket=") }
                ?.substringAfter('=')?.toLongOrNull() ?: System.currentTimeMillis()
            signed.copy(headers = signed.headers + PlayerSigner.headers(query, null, ts = ticket / 1000, ticket = ticket))
        }

        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    private fun run(label: String, http: OkHttpClient, base: String, profile: Map<String, String>,
                    cookie: String, build: (Map<String, String>) -> Signer.Request) {
        val signed = try { build(profile + PARAMS) } catch (error: Exception) {
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
                val heads = signed.headers.keys.sorted().joinToString(",")
                println("$label http=${response.code} bytes=${body.size} headers=$heads " +
                    String(body, Charsets.UTF_8).take(120).replace("\n", " "))
            }
        } catch (error: Exception) {
            println("$label 请求失败 ${error.javaClass.simpleName} ${error.message?.take(70)}")
        }
    }
}
