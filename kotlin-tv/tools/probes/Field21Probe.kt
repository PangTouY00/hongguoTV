// SPDX-License-Identifier: GPL-3.0-only
// 2026-11-23：上游 N3urda/hongguoTV 的 PR #107 把 Medusa 第 21 字段从 377 改成 312，
// 并用「同一 URL、同一正文、同一身份、同一时间戳，只替换这一个字段」的 A/B 证明它足以恢复取流。
// 这里分两步验证我们这边是否同样成立：
//   第一步（离线，不联网）：用上游随 PR 给出的固定向量 core/src/test/resources/signing-v5-playback-312.json
//     做逐字节对拍。312 必须完全一致，377 必须不一致——这证明移植只动了这一个字段。
//   第二步（联网 A/B）：同一份新注册身份、同一集、同一域名、同一时间戳、同一固定随机量与进程 nonce，
//     只有第 21 字段不同。第 5 路是上游 0.16.14 的完整形态（reading.snssdk.com + preload/v1 + 74532 + level 2），
//     用来判断「只改这一个字段」在我们这条接口上够不够。
// 判读：
//   - 第 2 路给数据、第 1 路空 → 这个字段在我们这条接口上同样是决定项，直接落地。
//   - 只有第 5 路给数据 → 单改字段不够，还要一起换请求形态，按第 5 路的形态改。
//   - 全空 → 与上一轮一样先怀疑身份/时段，换身份隔十分钟复测，别急着改结论。
//
//   cp tools/probes/Field21Probe.kt "$DSH_WORKSPACE/.toolchain/probe/"
//   REPO=/storage/emulated/0/Work/.wt-main/kotlin-tv \
//     CLASS=com.hongguotv.core.Field21Probe \
//     sh "$DSH_WORKSPACE/.toolchain/scripts/run-any-probe.sh" Field21Probe [video_id] [域名]
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object Field21Probe {
    private val VECTOR = "core/src/test/resources/signing-v5-playback-312.json"
    private val TAB_PARAMS = linkedMapOf(
        "video_id" to "", "video_id_type" to "0", "device_level" to "1",
        "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
        "video_platform" to "1024", "source" to "0", "from_video_id" to "",
    )

    /** 两路之间只允许第 21 字段不同，所以随机量、进程 nonce、进程启动时间全部固定。 */
    private val FIXED_RANDOM = { 0.5 }
    private val FIXED_NONCE = "field21-probe-nonce"
    private var FIXED_NOW = System.currentTimeMillis()

    private fun signer(field21: Int) = Signer(
        clock = { FIXED_NOW }, random = FIXED_RANDOM, processNonce = FIXED_NONCE,
        mobileV5 = true, field21 = field21,
    )

    /** 向量里的 medusa 明文带着「进程启动时间」与「进程 nonce」，这两项要照向量固定下来才可比。 */
    private fun vectorSigner(field21: Int) = Signer(
        clock = { 1800000000000L }, random = FIXED_RANDOM, processNonce = "synthetic",
        mobileV5 = true, field21 = field21,
    )

    private fun summarize(text: String): String {
        if (text.isBlank()) return "空正文"
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return "非 JSON"
        val code = root.optInt("Code", root.optInt("code", -1))
        val data = root.optJSONObject("data") ?: return "code=$code 无 data"
        // video_tab 的结构是 data.video_model_data.<id>.video_model；App 那条是 data.1004.<id>.video_model。
        val entries = data.optJSONObject("video_model_data") ?: data.optJSONObject("1004") ?: data
        val model = entries.keySet().asSequence().mapNotNull { key ->
            val raw = entries.optJSONObject(key)?.opt("video_model")
            when (raw) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            }
        }.firstOrNull() ?: return "code=$code 无模型"
        val list = model.optJSONArray("video_list")
        val codecs = (0 until (list?.length() ?: 0)).mapNotNull { i ->
            val row = list?.optJSONObject(i) ?: return@mapNotNull null
            val meta = row.optJSONObject("video_meta") ?: row
            "${meta.optString("codec_type", "?")} ${meta.optInt("vwidth")}x${meta.optInt("vheight")}" +
                (if (row.optString("main_url").isNotBlank()) " 有地址" else " 无地址")
        }
        val fallback = if (model.has("fallback_api")) " 带 fallback_api" else ""
        return "code=$code 档位=${list?.length() ?: 0} [${codecs.joinToString(", ")}]$fallback"
    }

    private fun offlineVectorCheck(): Boolean {
        val text = runCatching { java.io.File(VECTOR).readText() }.getOrNull()
        if (text == null) {
            println("读不到向量文件 $VECTOR（要在仓库根目录下运行）")
            return false
        }
        val fixture = JSONObject(text)
        val values = fixture.getJSONObject("identity")
        val order = fixture.getJSONArray("queryOrder")
        val identity = (0 until order.length()).associate { order.getString(it).let { k -> k to values.getString(k) } }
        val body = java.util.Base64.getDecoder().decode(fixture.getString("body"))
        val expected = fixture.getJSONObject("headers")
        var matched = false
        for (field21 in listOf(312, 377)) {
            val actual = vectorSigner(field21).signPlayback(
                "https://example.test/model", identity, body, 1800000000000L,
            )
            val problems = mutableListOf<String>()
            if (actual.url != fixture.getString("url")) problems += "url 不一致"
            if (actual.headers.keys != expected.keys().asSequence().toSet()) problems += "头集合不一致 ${actual.headers.keys}"
            expected.keys().forEach { if (actual.headers[it] != expected.getString(it)) problems += "$it 不一致" }
            val verdict = if (problems.isEmpty()) "逐字节一致" else problems.joinToString("; ")
            println("  向量对拍 field21=$field21 → $verdict")
            if (field21 == 312) matched = problems.isEmpty()
        }
        return matched
    }

    private fun tabGet(label: String, http: OkHttpClient, videoId: String, host: String,
                       profile: Map<String, String>, field21: Int, cookie: String = "") {
        TAB_PARAMS["video_id"] = videoId
        val signed = signer(field21).signPlayback(
            "https://$host/reading/bookapi/video_tab/multi_video_model/v1/",
            TAB_PARAMS + profile, null, FIXED_NOW,
        )
        val builder = Request.Builder().url(signed.url).header("User-Agent", VendorConstants.APP_UA)
        signed.headers.forEach { (k, v) -> builder.header(k, v) }
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        try {
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.bytes() ?: ByteArray(0)
                println("  $label → HTTP ${response.code} body=${body.size} 字节  ${summarize(String(body, Charsets.UTF_8))}")
            }
        } catch (error: Exception) {
            println("  $label 请求失败 ${error.javaClass.simpleName} ${error.message?.take(100)}")
        }
    }

    private fun appPost(label: String, http: OkHttpClient, host: String, path: String,
                       versionCode: String, versionName: String, deviceLevel: Int,
                       id: String, profile: Map<String, String>, field21: Int) {
        val query = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to versionCode, "version_name" to versionName,
            "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to versionCode,
            "iid" to profile.getValue("iid"), "cdid" to profile.getValue("cdid"),
            "device_id" to profile.getValue("device_id"),
        )
        val payload = JSONObject(
            """{"biz_param":{"detail_page_version":0,"device_level":$deviceLevel,"disable_digg_stat":false,""" +
                """"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,""" +
                """"use_os_player":false,"use_server_dns":false,"video_platform":1024},""" +
                """"dr_scene":"preload","mixed_video_id_map":{"1004":["$id"]}}"""
        )
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val base = "https://$host$path"
        val signed = signer(field21).signPlayback(base, query, bytes, FIXED_NOW)
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", VendorConstants.APP_UA)
            .header("Accept", "application/json").header("Content-Encoding", "gzip")
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        try {
            http.newCall(request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType())).build())
                .execute().use { response ->
                    val body = response.body?.bytes() ?: ByteArray(0)
                    println("  $label → HTTP ${response.code} body=${body.size} 字节  ${summarize(String(body, Charsets.UTF_8))}")
                }
        } catch (error: Exception) {
            println("  $label 请求失败 ${error.javaClass.simpleName} ${error.message?.take(100)}")
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        // 第一个参数是逗号分隔的样本 id；默认用上游 issue #102 的三个验收样本。
        val ids = (args.getOrNull(0)
            ?: "7709114791150767128,7704857946630409240,7709197486451084312").split(",")
        val tabHost = args.getOrNull(1) ?: "api5-normal-sinfonlineb.fqnovel.com"
        val http = OkHttpClient()

        println("=== 第一步：离线向量对拍（不联网） ===")
        if (!offlineVectorCheck()) {
            println("向量对拍没通过：先修移植，不要开始试网络。")
            return
        }

        println("=== 第二步：同一身份下只换第 21 字段 ===")
        val registerBody = DeviceRegister.payload(VendorConstants.device)
        val register = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(registerBody, VendorConstants.device).forEach { (k, v) -> register.header(k, v) }
        register.post(registerBody.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
        val registered = http.newCall(register.build()).execute().use { response ->
            DeviceRegister.parse(String(response.body?.bytes() ?: ByteArray(0), Charsets.UTF_8), response.headers("Set-Cookie"))
        } ?: run { println("注册失败"); return }
        println("注册成功 device_id=${registered.deviceId} iid=${registered.installId}")

        val cdid = UUID.randomUUID().toString()
        val profile = VendorConstants.device + mapOf(
            "device_id" to registered.deviceId, "iid" to registered.installId, "cdid" to cdid,
            "version_code" to "73932", "manifest_version_code" to "73932",
            "update_version_code" to "73932", "pv_player" to "73932", "version_name" to "7.3.9.32",
        )
        val shared = profile + mapOf("device_id" to registered.deviceId, "iid" to registered.installId, "cdid" to cdid)
        val cookie = registered.cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        println("固定时间戳 _rticket=$FIXED_NOW 固定随机量与进程 nonce；同一样本各路之间只有第 21 字段/Cookie 不同")

        for (id in ids) {
            println("样本 $id")
            tabGet("1 video_tab GET  第21字段=377（当前线上写法）", http, id, tabHost, profile, 377)
            tabGet("2 video_tab GET  第21字段=312", http, id, tabHost, profile, 312)
            tabGet("3 video_tab GET  第21字段=312 + 设备 Cookie", http, id, tabHost, profile, 312, cookie)
        }

        val first = ids.first()
        appPost("4 App POST player/v1  第21字段=377", http, "api5-normal-sinfonlinea.fqnovel.com",
            "/novel/player/multi_video_model/player/v1", "74332", "7.4.3.32", 1, first, shared, 377)
        appPost("5 App POST player/v1  第21字段=312", http, "api5-normal-sinfonlinea.fqnovel.com",
            "/novel/player/multi_video_model/player/v1", "74332", "7.4.3.32", 1, first, shared, 312)
        appPost("6 App POST preload/v1 @reading.snssdk.com 74532 level2 第21字段=312（上游 0.16.14 形态）",
            http, "reading.snssdk.com", "/novel/player/multi_video_model/preload/v1",
            "74532", "7.4.5.32", 2, first, shared, 312)
    }
}
