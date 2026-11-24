// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 6672b55; GPL-3.0-only.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 签名同时返回最终地址，确保请求票据选择的摘要分支与实际请求一致。 */
internal data class MobileSignedRequest(val url: String, val headers: Map<String, String>)

internal fun interface MobilePlaybackSigner {
    fun sign(url: String, body: ByteArray, device: Map<String, String>): MobileSignedRequest
}

internal object MobilePlayback {
    /** 官方 App 当前在用的取址写法，2026-11-16 起实测能下发清晰度。 */
    internal const val PRIMARY_PATH = "/novel/player/multi_video_model/player/v1"
    /**
     * 同一份签名、同一个正文下的另一条取址写法：不带 `player` 那一段、结尾带斜杠。
     *
     * 2026-11-17 用只依赖标准库的脚本对照过（[PRIMARY_PATH] 一律回 HTTP 200 + 0 字节，
     * 这条在 reading.snssdk.com 与两个 fqnovel 域名上都回 code=0 与五档模型）。
     * 上游 N3urda/hongguoTV 的 `PlayerSource.PHOENIX_URL` 保留的也是这条写法。
     */
    internal const val FALLBACK_PATH = "/novel/player/multi_video_model/v1/"
    private const val HOST = "https://api5-normal-sinfonlinea.fqnovel.com"

    private val v5Signer = Signer(mobileV5 = true)
    val signer = MobilePlaybackSigner { url, body, _ ->
        val parsed = url.toHttpUrl()
        val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
        val now = values.getValue("_rticket").toLong()
        val request = v5Signer.signPlayback(parsed.newBuilder().query(null).build().toString(), values, body, now)
        MobileSignedRequest(request.url, request.headers)
    }

    /** `path` 决定用哪一条取址写法，正文与签名两种写法完全相同。 */
    @JvmOverloads fun model(id: String, device: Map<String, String>, http: OkHttpClient,
               signer: MobilePlaybackSigner, now: Long,
               path: String = PRIMARY_PATH): JSONObject {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        // `manifest_version_code` 是这条接口的必需参数：2026-11-24 在 Debian 上用官方 7.4.5.32 的抓包
        // 对照探针（`.toolchain/probe/AppShapeProbe.kt`，结论记在 `tmp/hongguo-net/红果播放链路抓包分析.md` 第 12 节）逐项二分——
        // 同一份注册身份、同一集、同一签名与正文，只在 query 里加或不去这一个参数：
        //   带 manifest_version_code → code=0、五档清晰度；不带 → HTTP 200 + 0 字节。
        // 其余 20 项公共参数（resolution/dpi/device_brand/rom_version/pv_player/业务开关…）全都可有可无，
        // 整组删掉照样给完整模型；`manifest_version_code` 与 `version_code` 不同值也能通过。
        // 之前把「一律回空」归到签名族、域名或 path 写法，都是这个缺失参数造成的假象。
        val query = linkedMapOf("aid" to "8662", "app_name" to "novelread",
            "version_code" to "74332", "version_name" to "7.4.3.32", "manifest_version_code" to "74332",
            "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
            "iid" to device.getValue("iid"), "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id"), "_rticket" to now.toString())
        val url = (HOST + path).toHttpUrl().newBuilder().apply { query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        // 电视使用系统解码器，以等级 1 协商兼容资源；仍需按返回的实际编码过滤。
        val payload = JSONObject("""{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}""")
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val request = Request.Builder().url(url)
            .header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)")
            .header("Accept", "application/json").header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) })
        val signed = signer.sign(url.toString(), bytes, device)
        request.url(signed.url)
        signed.headers.forEach { (k,v) -> request.header(k,v) }
        RequestScope.ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            // 状态码要留在异常里：上层判断「这条写法挂了、换一条」靠的就是它，5xx 与 404 不是一回事。
            if (!response.isSuccessful) throw ContentHttpException(response.code)
            val body = response.body ?: throw IOException("播放信息格式异常")
            val limit = 2 * 1024 * 1024L
            if (body.contentLength() > limit) throw IOException("播放信息过大")
            val source = body.source()
            source.request(limit + 1)
            if (source.buffer.size > limit) throw IOException("播放信息过大")
            val text = source.readUtf8()
            if (text.isBlank()) throw EmptyPlaybackResponseException()
            val root = try { JSONObject(text) } catch (_: Exception) {
                throw IOException("播放信息格式异常")
            }
            val code = root.optInt("Code", root.optInt("code", 0))
            if (code != 0) throw IOException("播放接口暂不可用（错误码 $code）")
            val data = root.optJSONObject("data") ?: throw IOException("播放信息为空")
            val entries = data.optJSONObject("1004") ?: data
            val raw = entries.optJSONObject(id)?.opt("video_model")
            val model = when (raw) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            } ?: throw IOException("播放信息未包含指定剧集")
            if ((model.optJSONArray("video_list")?.length() ?: 0) == 0 && !model.has("fallback_api"))
                throw IOException("播放信息没有可用资源")
            JSONObject().put("video_model", model)
        }
    }
}
