// SPDX-License-Identifier: GPL-3.0-only
// 2026-11-24：Debian 上 dev34 模拟器里官方红果 7.4.5.32 的抓包（第 13 轮，Frida hook okhttp3.Builder）
// 抓到了沉浸式播放页真实发出的取址请求：
//   POST https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1
//     query 34 项公共参数（与首页/详情用的是同一套），body gzip 236 字节，Content-Length: 236
//     头：X-SS-STUB / Accept-Encoding: gzip / Accept / Content-Encoding: gzip / X-Xs-From-Web: 0 /
//          X-SS-REQ-TICKET / x-reading-request / x-vc-bdturing-sdk-version: 4.0.3.cn / lc: 101 /
//          sdk-version: 2 / passport-sdk-version: 5051452 / x-tt-store-region: cn-sd /
//          x-tt-store-region-src: did / UA(74532, tt-ok/3.12.13.20) /
//          X-Ladon / X-Khronos / X-Argus / X-Gorgon(8404…) / X-Helios / X-Medusa / Content-Type
//     没有任何 Cookie。
// host 与 path 与我们 MobilePlayback.PRIMARY_PATH/PRIMARY_HOST 完全一致，所以我们这条一律回
// HTTP 200 + 0 字节的原因不在 host 与 path。本探针一次把剩下的四个嫌疑分开测：
//   A 我们现在的窄 query（13 项）                    —— 基线，应当复出现象
//   B 只把 query 换成官方那 34 项                    —— query 参数集是不是变量
//   C 在 B 上补齐官方那些非签名头                    —— 请求头是不是变量
//   D 在 C 上再带上设备注册下发的 Cookie             —— 设备 Cookie 是不是变量
//   E 在 C 上把版本号退回 74332                      —— 版本号是不是变量
// 各路之间只允许被检的那一项不同：同一份新注册身份、同一集、同一固定时间戳与随机量、同一正文。
//
//   REPO=/storage/emulated/0/Work/.wt-main/kotlin-tv \
//     sh /storage/emulated/0/Work/.toolchain/scripts/run-any-probe.sh AppShapeProbe [video_id]
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object AppShapeProbe {
    private val FIXED_RANDOM = { 0.5 }
    private val FIXED_NONCE = "appshape-probe-nonce"
    private var FIXED_NOW = System.currentTimeMillis()

    private const val HOST = "https://api5-normal-sinfonlinea.fqnovel.com"
    private const val PATH = "/novel/player/multi_video_model/player/v1"
    private const val OFFICIAL_UA =
        "com.phoenix.read/74532 (Linux; U; Android 14; en_US; sdk_gphone64_x86_64; Build/UE1A.230829.050;tt-ok/3.12.13.20)"

    private fun signer() = Signer(
        clock = { FIXED_NOW }, random = FIXED_RANDOM, processNonce = FIXED_NONCE,
        mobileV5 = true,
    )

    /** A 路用的窄 query，逐字段照 MobilePlayback.model() 现在的写法。 */
    private fun narrowQuery(id: String, deviceId: String, installId: String, cdid: String) = linkedMapOf(
        "aid" to "8662", "app_name" to "novelread",
        "version_code" to "74332", "version_name" to "7.4.3.32",
        "channel" to "vivo_8662_64", "device_platform" to "android",
        "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
        "iid" to installId, "cdid" to cdid, "device_id" to deviceId,
    )

    /**
     * B–E 路用的完整公共参数，逐项照 2026-11-24 抓到的那条真实请求（含 is_64_* 与 host_abi 之间那个空参数）。
     * 只把身份换成本次注册得到的 device_id/iid/cdid；`_rticket` 交给签名器写。
     */
    private fun fullQuery(deviceId: String, installId: String, cdid: String, version: String) = linkedMapOf(
        "device_platform" to "android", "os" to "android", "ssmix" to "a",
        "cdid" to cdid, "channel" to "xiaomi_8662_64", "aid" to "8662", "app_name" to "novelread",
        "version_code" to version, "version_name" to versionNameOf(version),
        "manifest_version_code" to version, "update_version_code" to version,
        "resolution" to "1080*2209", "dpi" to "420",
        "device_type" to "sdk_gphone64_x86_64", "device_brand" to "google", "language" to "en",
        "os_api" to "34", "os_version" to "14", "ac" to "wifi",
        "is_64_runtime" to "true", "is_64_device" to "true", "" to "",
        "host_abi" to "arm64-v8a", "dragon_device_type" to "phone", "pv_player" to version,
        "is_x86_device" to "true", "compliance_status" to "0",
        "need_personal_recommend" to "1", "player_so_load" to "1", "is_android_pad_screen" to "0",
        "rom_version" to "sdk_gphone64_x86_64-userdebug 14 UE1A.230829.050 12077443 dev-keys",
        "iid" to installId, "device_id" to deviceId,
    )

    private fun versionNameOf(code: String) = when (code) {
        "74532" -> "7.4.5.32"
        "74332" -> "7.4.3.32"
        else -> code
    }

    /** 官方那些非签名头（照抓包逐条列出，签名三头由签名器给）。 */
    private fun officialExtraHeaders(ticket: Long) = linkedMapOf(
        "Accept-Encoding" to "gzip",
        "Accept" to "application/json; charset=utf-8,application/x-protobuf",
        "Content-Encoding" to "gzip",
        "X-Xs-From-Web" to "0",
        "x-reading-request" to "$ticket-${(ticket * 2654435761L) % 2000000000}",
        "x-vc-bdturing-sdk-version" to "4.0.3.cn",
        "lc" to "101",
        "sdk-version" to "2",
        "passport-sdk-version" to "5051452",
        "x-tt-store-region" to "cn-sd",
        "x-tt-store-region-src" to "did",
        "User-Agent" to OFFICIAL_UA,
    )

    /** 响应可能被 gzip 压缩（我们手动带 Accept-Encoding: gzip 时 okhttp 不会自动解）。 */
    private fun decode(raw: ByteArray): ByteArray =
        if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(raw)).readAllBytes()
        else raw

    private fun summarize(text: String): String {
        if (text.isBlank()) return "空正文"
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return "非 JSON(${text.take(60)})"
        val code = root.optInt("Code", root.optInt("code", -1))
        val data = root.optJSONObject("data") ?: return "code=$code 无 data"
        val entries = data.optJSONObject("1004") ?: data
        val model = entries.keySet().asSequence().mapNotNull { key ->
            val raw = entries.optJSONObject(key)?.opt("video_model")
            when (raw) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            }
        }.firstOrNull() ?: data.optJSONObject("video_model")?.let { it } ?: return "code=$code 无模型"
        val list = model.optJSONArray("video_list")
        val codecs = (0 until (list?.length() ?: 0)).mapNotNull { i ->
            val row = list?.optJSONObject(i) ?: return@mapNotNull null
            val meta = row.optJSONObject("video_meta") ?: row
            "${meta.optString("codec_type", "?")} ${meta.optInt("vwidth")}x${meta.optInt("vheight")}"
        }
        val fallback = if (model.has("fallback_api")) " 带 fallback_api" else ""
        return "code=$code 档位=${list?.length() ?: 0} [${codecs.joinToString(", ")}]$fallback"
    }

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    private fun attempt(
        label: String,
        http: OkHttpClient,
        query: Map<String, String>,
        body: ByteArray,
        extraHeaders: Map<String, String>,
        cookie: String,
        stub: String,
        // 默认用线上形态的 v5 签名器；换签名族时由调用方给签名器与「保留哪些签名头」的过滤。
        signerInstance: Signer = signer(),
        signedFilter: (Map<String, String>) -> Map<String, String> = { it },
    ) {
        val signed = signerInstance.signPlayback(HOST + PATH, query, body, FIXED_NOW)
        val builder = Request.Builder().url(signed.url)
        // 先放被测的形态头，再放签名头：签名头始终由签名器给，形态各路只在非签名部分不同。
        (extraHeaders + mapOf("x-ss-stub" to stub, "Content-Type" to "application/json; charset=utf-8")).forEach { (k, v) -> builder.header(k, v) }
        signedFilter(signed.headers).forEach { (k, v) -> builder.header(k, v) }
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        try {
            http.newCall(builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build())
                .execute().use { response ->
                    val raw = response.body?.bytes() ?: ByteArray(0)
                    val unzipped = decode(raw)
                    println("  $label → HTTP ${response.code} body=${raw.size} 字节${if (unzipped.size != raw.size) "（gzip，解压后 ${unzipped.size} 字节）" else ""}  ${summarize(String(unzipped, Charsets.UTF_8))}")
                }
        } catch (error: Exception) {
            println("  $label 请求失败 ${error.javaClass.simpleName} ${error.message?.take(120)}")
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val id = args.getOrNull(0) ?: "7709114791150767128"
        val http = OkHttpClient()

        val registerBody = DeviceRegister.payload(VendorConstants.device)
        val register = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(registerBody, VendorConstants.device).forEach { (k, v) -> register.header(k, v) }
        register.post(registerBody.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
        val registered = http.newCall(register.build()).execute().use { response ->
            DeviceRegister.parse(String(response.body?.bytes() ?: ByteArray(0), Charsets.UTF_8), response.headers("Set-Cookie"))
        } ?: run { println("注册失败"); return }
        val cdid = UUID.randomUUID().toString()
        println("注册 device_id=${registered.deviceId} iid=${registered.installId}")
        val cookie = registered.cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        println("注册下发的 Cookie 键：${registered.cookies.keys.joinToString(",")}")

        val payload = JSONObject(
            """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,""" +
                """"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,""" +
                """"use_os_player":false,"use_server_dns":false,"video_platform":1024},""" +
                """"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}"""
        ).toString().toByteArray(Charsets.UTF_8)
        val gz = gzip(payload)
        val stub = java.security.MessageDigest.getInstance("MD5").digest(gz)
            .joinToString("") { "%02X".format(it) }
        println("正文明文 ${payload.size} 字节 / gzip 后 ${gz.size} 字节（抓包里官方是 236 字节）")
        println("同一份身份、同一集、同一时间戳 $FIXED_NOW、同一随机量与进程 nonce；各路之间只改被检的那一项")

        val narrow = narrowQuery(id, registered.deviceId, registered.installId, cdid)
        val full = fullQuery(registered.deviceId, registered.installId, cdid, "74532")
        val extra = officialExtraHeaders(FIXED_NOW)
        // 只带最基本的两条头（和 A 一样），用来做「只加 Content-Encoding」这类单变量实验。
        val bare = linkedMapOf("User-Agent" to OFFICIAL_UA, "Accept" to "application/json")
        val withContentEncoding = bare + mapOf("Content-Encoding" to "gzip")
        val withAcceptEncoding = bare + mapOf("Accept-Encoding" to "gzip")
        // 已知能成的形态当作锚点，其余每路只删一组参数，定位这条接口真正要的是什么。
        // 注意 os_version / device_type / channel / version_name / device_id 不能删：签名器的 medusa 明文
        // 直接按这几个 key 取值（Signer.kt 的 medusaV5），缺一个就 NoSuchElementException——上一轮 g 路就是这么崩的。
        val DEVICE_DESC = setOf(
            "resolution", "dpi", "device_brand", "language", "os_api",
            "ac", "is_64_runtime", "is_64_device", "host_abi", "is_x86_device", "rom_version",
        )
        val BUSINESS_FLAGS = setOf(
            "compliance_status", "need_personal_recommend", "player_so_load", "is_android_pad_screen",
            "dragon_device_type", "pv_player", "ssmix", "os",
        )
        val CORE = setOf(
            "aid", "app_name", "version_code", "version_name", "channel", "device_platform",
            "device_type", "os_version", "update_version_code", "iid", "cdid", "device_id",
            "manifest_version_code",
        )

        val flags = mapOf(
            "os" to "android", "ssmix" to "a", "pv_player" to "74332",
            "dragon_device_type" to "phone", "compliance_status" to "0",
            "need_personal_recommend" to "1", "player_so_load" to "1",
            "is_android_pad_screen" to "0",
        )

        // 基准是上一轮定下的「能通的最小形态」：核心 13 项 + manifest_version_code。
        // 这一组只换签名族，query、正文、时间戳、随机量、进程 nonce 全部同一份。
        val minimal = narrow + mapOf("manifest_version_code" to "74332")
        val legacySigner = Signer(
            clock = { FIXED_NOW }, random = FIXED_RANDOM, processNonce = FIXED_NONCE, mobileV5 = false,
        )
        val legacyHeaders = legacySigner.signPlayback(HOST + PATH, minimal, gz, FIXED_NOW).headers
        val oldFamily = legacyHeaders.filterKeys { it.lowercase() in setOf("x-ladon", "x-argus", "x-gorgon") }
        println("旧族样本 X-Ladon=${oldFamily["x-ladon"]} X-Argus=${oldFamily["x-argus"]} X-Gorgon=${oldFamily["x-gorgon"]?.take(24)}…")

        println("=== 签名族实验：在带 manifest_version_code 的最小形态上只换签名 ===")
        attempt("S1 只 v5 三头（x-khronos/x-helios/x-medusa，锚点）", http, minimal, gz, withContentEncoding, "", stub)
        attempt("S2 v5 三头 + 旧族（x-ladon/x-argus/x-gorgon 一起带，官方就是两套同时带）",
            http, minimal, gz, withContentEncoding + oldFamily, "", stub)
        attempt("S3 完全不带任何签名头", http, minimal, gz, withContentEncoding, "", stub,
            signedFilter = { emptyMap() })
        attempt("S4 只旧族 + x-khronos（拿掉 x-helios/x-medusa）",
            http, minimal, gz, withContentEncoding, "", stub, legacySigner,
            signedFilter = { h -> h.filterKeys { it.lowercase() !in setOf("x-helios", "x-medusa") } })
        attempt("S5 完整旧签名形态（legacy 全套，含 legacy 版 helios/medusa）",
            http, minimal, gz, withContentEncoding, "", stub, legacySigner)
    }

    private fun stubOf(body: ByteArray) =
        java.security.MessageDigest.getInstance("MD5").digest(body).joinToString("") { "%02X".format(it) }
}
