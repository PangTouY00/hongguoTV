// SPDX-License-Identifier: GPL-3.0-only
// 2026-11-23 晚：真机带账号取址全空，本机匿名同样全空。剩下的候选解释有两个——
//   (1) 我们的 v5 签名这一刻不被接受（算法或字段变了）；
//   (2) 签名被接受了，是身份/资格/服务端状态的问题。
// 这两件事在 body 上看不出区别（都是 0 字节），所以这里把**响应头**打出来，并故意制造三种签名错误：
//   1 原样发出   2 把 x-medusa 改坏一个字符   3 去掉 x-medusa
// 判读：
//   - 三路反应完全一样 → 服务端对签名正确性没有可观察反应，问题不在签名，别在这条路上继续试。
//   - 改坏/去掉那两路给出可辨的错误（不同状态码、不同头、非空 body）而第 1 路仍静默空
//     → 第 1 路的签名是被接受的，问题在身份或这一侧的服务端状态。
//
//   cp tools/probes/SignatureAcceptProbe.kt "$DSH_WORKSPACE/.toolchain/probe/"
//   REPO=/storage/emulated/0/Work/.wt-main/kotlin-tv \
//     CLASS=com.hongguotv.core.SignatureAcceptProbe \
//     sh "$DSH_WORKSPACE/.toolchain/scripts/run-any-probe.sh" SignatureAcceptProbe [video_id] [域名]
package com.hongguotv.core

import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object SignatureAcceptProbe {
    private val PARAMS = linkedMapOf(
        "video_id" to "", "video_id_type" to "0", "device_level" to "1",
        "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
        "video_platform" to "1024", "source" to "0", "from_video_id" to "",
    )

    private fun send(label: String, http: OkHttpClient, request: Signer.Request) {
        val builder = Request.Builder().url(request.url).header("User-Agent", VendorConstants.APP_UA)
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        try {
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.bytes() ?: ByteArray(0)
                println("### $label → HTTP ${response.code}  body=${body.size} 字节")
                response.headers.forEach { (key, value) -> println("      < $key: ${value.take(160)}") }
                if (body.isNotEmpty()) println("      body[:240]: ${String(body, Charsets.UTF_8).take(240)}")
            }
        } catch (error: Exception) {
            println("### $label 请求失败 ${error.javaClass.simpleName} ${error.message?.take(100)}")
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val videoId = args.getOrNull(0) ?: "7709114791150767128"
        val host = args.getOrNull(1) ?: "api5-normal-sinfonlineb.fqnovel.com"
        PARAMS["video_id"] = videoId
        val http = OkHttpClient()

        val registerBody = DeviceRegister.payload(VendorConstants.device)
        val register = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(registerBody, VendorConstants.device).forEach { (key, value) -> register.header(key, value) }
        register.post(registerBody.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
        val registered = http.newCall(register.build()).execute().use { response ->
            DeviceRegister.parse(String(response.body?.bytes() ?: ByteArray(0), Charsets.UTF_8), response.headers("Set-Cookie"))
        } ?: run { println("注册失败"); return }
        println("注册成功 device_id=${registered.deviceId} iid=${registered.installId}")

        val cookie = registered.cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
        val profile = VendorConstants.device + mapOf(
            "device_id" to registered.deviceId, "iid" to registered.installId,
            "cdid" to UUID.randomUUID().toString(),
            "version_code" to "73932", "manifest_version_code" to "73932",
            "update_version_code" to "73932", "pv_player" to "73932", "version_name" to "7.3.9.32",
        )
        val base = "https://$host/reading/bookapi/video_tab/multi_video_model/v1/"
        val signed = Signer(mobileV5 = true).signPlayback(base, PARAMS + profile, null, System.currentTimeMillis())
        println("签名头: " + signed.headers.entries
            .filter { it.key.lowercase() in setOf("x-khronos", "x-helios", "x-medusa", "x-argus", "x-ladon", "x-gorgon") }
            .joinToString("; ") { "${it.key}=${it.value.take(24)}…" })

        val headers = signed.headers
        val medusaKey = headers.keys.firstOrNull { it.lowercase() == "x-medusa" }
        val medusa = medusaKey?.let { headers[it] }.orEmpty()
        println("x-medusa 长度=${medusa.length}")

        send("1 签名原样", http, signed)

        if (medusa.isNotBlank()) {
            val last = medusa.last()
            val flipped = if (last == 'A') 'B' else 'A'
            val broken = HashMap(headers)
            broken[medusaKey!!] = medusa.dropLast(1) + flipped
            send("2 x-medusa 改坏最后一位", http, Signer.Request(signed.url, broken, signed.body))
        }

        val withoutMedusa = headers.filter { it.key.lowercase() != "x-medusa" }
        send("3 去掉 x-medusa", http, Signer.Request(signed.url, withoutMedusa, signed.body))
    }
}
