// 前面所有取址实验用的都是硬编码的旧 video_id。这条探针改成：先问 feed 接口（它此刻确定能通）
// 要一批当前真实存在的 video_id，再拿这些 id 去取址，并在同一轮里对照那个旧 id。
//
// 判读：
//   - 新 id 有数据、旧 id 空 → 之前那批「全空」是 id 过期造成的，取址本身没问题。
//   - 新 id 也全空 → 取址接口对匿名请求确实不放行，与 id 无关。
//
// 跑法：
//   REPO=/storage/emulated/0/Work/.wt-main/kotlin-tv \
//     CLASS=com.hongguotv.core.FeedToPlaybackProbe \
//     sh "$DSH_WORKSPACE/.toolchain/scripts/run-any-probe.sh" FeedToPlaybackProbe
package com.hongguotv.core

import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object FeedToPlaybackProbe {
    private const val OLD_ID = "7691249364097829913"   // 之前所有实验用的那个 id

    @JvmStatic fun main(args: Array<String>) {
        val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        // 原本这里直接用 VendorConstants.device 里那份硬编码 device_id、也不带任何 Cookie。
        // 那样跑出来的 feed `code=110 ILLEGAL_ACCESS` 只说明那份固定身份没有访问权限，
        // 不能当成「这台设备被整体拒绝」——真机日志里 App 自己的推荐请求同一时刻是正常的。
        // 这里改成先注册一份新身份，feed 与取址共用它和它拿到的 Cookie，对照才成立。
        val registerBody = DeviceRegister.payload(VendorConstants.device)
        val registerCall = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(registerBody, VendorConstants.device).forEach { (k, v) -> registerCall.header(k, v) }
        registerCall.post(registerBody.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
        val registered = http.newCall(registerCall.build()).execute().use { response ->
            DeviceRegister.parse(String(response.body?.bytes() ?: ByteArray(0), Charsets.UTF_8), response.headers("Set-Cookie"))
        } ?: run { println("注册失败，先查注册域名（log.snssdk.com 可能直接回 444）"); return }
        println("新注册身份 device_id=${registered.deviceId} iid=${registered.installId}")
        val cookie = registered.cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        val identity = VendorConstants.device + mapOf(
            "device_id" to registered.deviceId, "iid" to registered.installId,
            "cdid" to UUID.randomUUID().toString(),
        )

        // 1) feed：与 ContentRepository 里那条能正常工作的请求同一形态
        val params = LinkedHashMap(identity).apply {
            put("tab_type", "16"); put("bottom_tab_type", "7"); put("landing_bottom_tab_type", "7")
            put("offset", "0"); put("client_req_type", "1"); put("_rticket", System.currentTimeMillis().toString())
        }
        val feedSigned = Signer(device = identity).sign(
            "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/bookmall/tab/v1/", params, null)
        val feedRequest = Request.Builder().url(feedSigned.url)
            .header("User-Agent", "com.dragon.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C)")
            .header("Accept", "application/json").header("X-XS-From-Web", "0").header("Sdk-Version", "2")
            .header("Cookie", cookie)
            .apply { feedSigned.headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val feedText = http.newCall(feedRequest).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            println("feed http=" + response.code + " bytes=" + bytes.size)
            println("feed 正文: " + String(bytes, 0, minOf(500, bytes.size)))
            String(bytes)
        }
        val ids = Regex("\"video_id\"\\s*:\\s*\"?(\\d{10,})").findAll(feedText)
            .map { it.groupValues[1] }.distinct().toList()
        println("feed 抓到 video_id " + ids.size + " 个，前 3 个=" + ids.take(3))

        // 2) 用这些 id 取址，同一轮里对照旧 id
        val profile = identity + mapOf("cdid" to UUID.randomUUID().toString())
        val targets = ids.take(3) + listOf(OLD_ID)
        for (vid in targets) {
            val query = linkedMapOf(
                "video_id" to vid, "video_id_type" to "0", "device_level" to "1",
                "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
                "video_platform" to "1024", "source" to "0", "from_video_id" to "",
            )
            val base = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/"
            val signed = Signer(mobileV5 = true).signPlayback(base, profile + query, null, System.currentTimeMillis())
            val request = Request.Builder().url(signed.url)
                .header("User-Agent", VendorConstants.APP_UA)
                .header("Cookie", cookie)
                .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    val tag = if (vid == OLD_ID) "旧 id" else "新 id"
                    println("取址 " + tag + " vid=" + vid + " http=" + response.code + " bytes=" + bytes.size)
                    if (bytes.isNotEmpty()) println("    正文前 160: " + String(bytes, 0, minOf(160, bytes.size)))
                }
            } catch (error: Throwable) {
                println("取址 vid=" + vid + " ERROR " + error.javaClass.simpleName + ": " + error.message)
            }
        }
    }
}
