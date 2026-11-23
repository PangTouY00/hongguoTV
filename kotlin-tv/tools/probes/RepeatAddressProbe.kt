// SPDX-License-Identifier: GPL-3.0-only
// 真机日志里出现了「同一集 22 秒前播通、22 秒后四轮全空」，这条探针就是为它写的：
// 同一份新注册身份、同一个 video_id、同一条请求，连续问 N 次，看服务端是**概率性放行**还是**时段性放行**。
//
// 判读：
//   - 同一身份内成功与空交替出现、且成功聚在某几分钟内 → 时段/节流，重试策略要改成退避而不是快速撞完。
//   - 同一身份全空、换一份新身份就有数据 → 变量是这份身份本身被标记，注册新身份才是有效重试。
//   - 两组都有成功、比例接近 → 与身份无关，是请求侧的随机拒绝，多轮重试有意义，但要拉开间隔。
//
// 跑法（本机，不用 Gradle）：
//   cp kotlin-tv/tools/probes/RepeatAddressProbe.kt "$DSH_WORKSPACE/.toolchain/probe/"
//   REPO=/storage/emulated/0/Work/.wt-main/kotlin-tv \
//     CLASS=com.hongguotv.core.RepeatAddressProbe \
//     sh "$DSH_WORKSPACE/.toolchain/scripts/run-any-probe.sh" RepeatAddressProbe <video_id> [host] [次数] [间隔ms]
//
// 请求形态照线上：GET /reading/bookapi/video_tab/multi_video_model/v1/，v5 三头，App 的 UA，
// 只带注册下发的设备 Cookie（不带账号 Cookie）——真机日志里播通的那一轮正是这一种。
package com.hongguotv.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object RepeatAddressProbe {
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @JvmStatic fun main(args: Array<String>) {
        val videoId = args.getOrNull(0)
            ?: run { println("用法: RepeatAddressProbe <video_id> [host] [次数] [间隔ms]"); return }
        val host = args.getOrNull(1) ?: "api5-normal-sinfonlineb.fqnovel.com"
        val rounds = args.getOrNull(2)?.toIntOrNull() ?: 10
        val gap = args.getOrNull(3)?.toLongOrNull() ?: 1200L
        // 第 5 个参数选择跑哪几组：默认三组都跑；长时间采样只跑 C（每次新身份）。
        val groups = (args.getOrNull(4) ?: "CAB").uppercase()
        val http = OkHttpClient()
        val base = "https://$host/reading/bookapi/video_tab/multi_video_model/v1/"

        // C 组：每次请求前先换一份新注册身份。这与线上 fetchLadder 每一轮 playbackSession.invalidate()
        // 是同一做法——仓库里记着的实测是「同一身份连试 8 次全空，换新身份第 1~2 次就成」。
        // 这一组回答的是：此刻这份配额池对**新身份**开不开。
        var hitsC = 0
        for (attempt in 1..rounds) {
            val fresh = register(http)
            if (fresh == null) { println("${clock.format(Date())} C #$attempt 注册失败"); continue }
            println("${clock.format(Date())} C #$attempt 身份 device_id=${fresh.first["device_id"]}")
            hitsC += ask(http, base, fresh, videoId, "C 每次新身份", 1, gap)
            // ask 只在多轮之间睡，这里每组换身份、每组一轮，间隔要自己补上。
            if (attempt < rounds) Thread.sleep(gap)
        }

        // D 组：与 C 组唯一的差别是 Cookie——只带线上那四个，不带注册下发的其余部分。
        var hitsD = 0
        if (groups.contains("D")) {
            for (attempt in 1..rounds) {
                val fresh = register(http, onlyAppCookies = true)
                if (fresh == null) { println("${clock.format(Date())} D #$attempt 注册失败"); continue }
                println("${clock.format(Date())} D #$attempt 身份 device_id=${fresh.first["device_id"]} cookie=${fresh.second}")
                hitsD += ask(http, base, fresh, videoId, "D 新身份+线上Cookie", 1, gap)
                if (attempt < rounds) Thread.sleep(gap)
            }
        }

        var hitsA = 0; var hitsB = 0
        if (groups.contains("A")) {
            val first = register(http)
            if (first != null) {
                println("A 身份 device_id=${first.first["device_id"]}")
                hitsA = ask(http, base, first, videoId, "A 同一身份", rounds, gap)
            }
        }

        if (groups.contains("B")) {
            val second = register(http)
            if (second != null) {
                println("B 身份 device_id=${second.first["device_id"]}")
                hitsB = ask(http, base, second, videoId, "B 新身份", rounds, gap)
            }
        }

        println("SUMMARY groups=$groups D(新身份+线上Cookie)=$hitsD/$rounds C(新身份+全部Cookie)=$hitsC/$rounds " +
            "A(同一身份)=$hitsA/$rounds B(换一次身份后重复)=$hitsB/$rounds gapMs=$gap")
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    /**
     * 线上 PlaybackDeviceSession 只把注册下发的这四个 Cookie 带进取址请求
     * （账号 Cookie 不允许混进匿名播放身份）。探针原本带的是注册下发的全部 Cookie，
     * 这一组就是用来对照这个差别本身。
     */
    private val APP_PLAYBACK_COOKIES = setOf("install_id", "ttreq", "store-region", "store-region-src")

    private fun register(http: OkHttpClient, onlyAppCookies: Boolean = false): Pair<Map<String, String>, String>? {
        val body = DeviceRegister.payload(VendorConstants.device)
        val builder = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (key, value) -> builder.header(key, value) }
        builder.post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
        val parsed = http.newCall(builder.build()).execute().use { response ->
            DeviceRegister.parse(String(response.body?.bytes() ?: ByteArray(0), Charsets.UTF_8), response.headers("Set-Cookie"))
        } ?: return null
        val profile = VendorConstants.device + mapOf(
            "device_id" to parsed.deviceId, "iid" to parsed.installId, "cdid" to UUID.randomUUID().toString(),
            "version_code" to "73932", "manifest_version_code" to "73932", "update_version_code" to "73932",
            "pv_player" to "73932", "version_name" to "7.3.9.32",
        )
        val cookie = parsed.cookies.entries
            .filter { !onlyAppCookies || it.key in APP_PLAYBACK_COOKIES }
            .joinToString("; ") { (key, value) -> "$key=$value" }
        return profile to cookie
    }

    private fun ask(http: OkHttpClient, base: String, identity: Pair<Map<String, String>, String>,
                    videoId: String, label: String, rounds: Int, gap: Long): Int {
        var hits = 0
        for (index in 1..rounds) {
            val params = linkedMapOf(
                "video_id" to videoId, "video_id_type" to "0", "device_level" to "1",
                "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
                "video_platform" to "1024", "source" to "0", "from_video_id" to "",
            )
            val signed = try {
                Signer(mobileV5 = true).signPlayback(base, identity.first + params, null, System.currentTimeMillis())
            } catch (error: Exception) {
                println("${clock.format(Date())} $label #$index 签名失败 ${error.javaClass.simpleName}"); continue
            }
            val request = Request.Builder().url(signed.url)
                .header("User-Agent", VendorConstants.APP_UA)
                .header("Accept", "application/json; charset=utf-8")
                .header("Cookie", identity.second)
            signed.headers.forEach { (key, value) -> request.header(key, value) }
            try {
                http.newCall(request.build()).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    val text = String(bytes, Charsets.UTF_8)
                    val ok = text.contains("video_model")
                    if (ok) hits++
                    val preview = if (bytes.size in 1..400) " " + text.take(160).replace('\n', ' ') else ""
                    println("${clock.format(Date())} $label #$index http=${response.code} bytes=${bytes.size} " +
                        "model=$ok logid=${response.header("x-tt-logid").orEmpty()}$preview")
                }
            } catch (error: Exception) {
                println("${clock.format(Date())} $label #$index 请求失败 ${error.javaClass.simpleName} ${error.message?.take(60)}")
            }
            if (index < rounds) Thread.sleep(gap)
        }
        return hits
    }
}
