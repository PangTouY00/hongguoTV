// 最后一个没测过的组合：把「注册身份 + 请求参数 + User-Agent」三者完全对齐到这台手机
// 的真实型号与官方红果当前版本号，再取一次址。
//
// 已排除的变量（2026-11-23 实测，见 PLAYBACK_DIAGNOSIS.md）：签名头族、域名、
// 新注册/固定身份、device_token 的各种带法。没排除的是「三处版本号与设备型号互不一致」：
// 注册 payload 报 25053RT47C/71332，取址 query 报 PGT-AN10/73932，UA 报 SM-N9860/71532。
// 唯一确定能通的 feed 请求，这三处是一致的。
//
// 跑法：
//   REPO=/storage/emulated/0/Work/.wt-main/kotlin-tv \
//     CLASS=com.hongguotv.core.LocalIdentityProbe \
//     sh "$DSH_WORKSPACE/.toolchain/scripts/run-any-probe.sh" LocalIdentityProbe
package com.hongguotv.core

import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object LocalIdentityProbe {
    private const val VID = "7691249364097829913"
    private const val MODEL = "24117RK2CC"          // getprop ro.product.model
    private const val CODE = "74532"                // 官方红果当前版本 7.4.5.32
    private const val UA = "com.phoenix.read/74532 (Linux; U; Android 16; zh_CN; 24117RK2CC; " +
        "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23)"

    @JvmStatic fun main(args: Array<String>) {
        val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val local = VendorConstants.device + mapOf(
            "device_type" to MODEL, "device_brand" to "Redmi",
            "os_version" to "16", "os_api" to "36",
            "version_code" to CODE, "version_name" to "7.4.5.32",
            "manifest_version_code" to CODE, "update_version_code" to CODE, "pv_player" to CODE,
        )
        val body = DeviceRegister.payload(local)
        val register = Request.Builder().url(DeviceRegister.url())
            .apply { DeviceRegister.headers(body, local).forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody("application/octet-stream".toMediaType())).build()
        val registered = http.newCall(register).execute().use { r ->
            DeviceRegister.parse(r.body?.string().orEmpty(), r.headers("Set-Cookie"))
        } ?: run { println("注册失败"); return }
        println("注册 device_id=" + registered.deviceId + " new_user=" + registered.newUser)
        val cookie = registered.cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        val profile = local + mapOf(
            "device_id" to registered.deviceId, "iid" to registered.installId,
            "cdid" to UUID.randomUUID().toString(),
        )
        val params = linkedMapOf(
            "video_id" to VID, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
            "video_platform" to "1024", "source" to "0", "from_video_id" to "",
        )
        val hosts = listOf(
            "api5-normal-sinfonlinea.fqnovel.com",   // feed 用的那条，已知能通
            "api5-normal-sinfonlineb.fqnovel.com",
        )
        for (host in hosts) {
            val base = "https://$host/reading/bookapi/video_tab/multi_video_model/v1/"
            val signed = Signer(mobileV5 = true).signPlayback(base, profile + params, null, System.currentTimeMillis())
            val request = Request.Builder().url(signed.url)
                .header("User-Agent", UA)
                .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
                .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                .build()
            try {
                http.newCall(request).execute().use { r ->
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    println("对齐身份 host=$host http=" + r.code + " bytes=" + bytes.size)
                    if (bytes.isNotEmpty()) println("  正文前 200: " + String(bytes, 0, minOf(200, bytes.size)))
                }
            } catch (error: Throwable) {
                println("对齐身份 host=$host ERROR " + error.javaClass.simpleName + ": " + error.message)
            }
        }
    }
}
