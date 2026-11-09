// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线上对照（2026-11-09 实测）：同一台手机上未登录时，取址接口对每一集都回 `code=110001`——
 * 3 部剧各前 3 集加上之前那集全部被拒，换注册身份、换 Cookie 形态都不变；登录带上账号身份后
 * 同一集立刻能取到清晰度。所以已登录时第一轮就带账号 Cookie，未登录时试一次就说「需要登录」。
 */
class PlaybackAccountIdentityTest {
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val legacyPath = "/novel/player/multi_video_model/v1/"

    private fun payload(id: String): String {
        val row = JSONObject()
            .put("main_url", "https://example.com/signed")
            .put("video_meta", JSONObject().put("definition", "720p").put("codec_type", "h264")
                .put("vwidth", 720).put("vheight", 1280))
            .put("encrypt_info", JSONObject().put("encrypt", false))
        val model = JSONObject().put("video_list", JSONArray().put(row))
        return JSONObject().put("code", 0).put("message", "SUCCESS")
            .put("data", JSONObject().put("video_model_data",
                JSONObject().put(id, JSONObject().put("video_model", model))))
            .toString()
    }

    private fun respond(request: okhttp3.Request, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()

    /** 只有带账号 Cookie 的那次才给清晰度，照实测形态：匿名一律 `code=110001`。 */
    private fun accountOnlyHttp(seen: MutableList<String?>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = if (path != tabPath && path != legacyPath) "{}" else {
                seen += request.header("Cookie")
                if (seen.last()?.contains("sessionid") == true) payload("123")
                else JSONObject().put("code", 110001).toString()
            }
            respond(request, body)
        }.build()

    @Test fun accountCookieIsCarriedFromTheFirstRound() {
        val seen = mutableListOf<String?>()
        val attempts = mutableListOf<String>()
        val repository = ContentRepository(
            accountOnlyHttp(seen), System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1001", "2001") },
            accountCookie = { "sessionid=account-cookie" },
            onPlaybackAttempt = { attempts += it },
        )
        assertEquals("https://example.com/signed", repository.streamLadder("123").renditions.single().url)
        assertTrue("第一轮就应该带账号 Cookie：$seen", seen.first()?.contains("sessionid") == true)
        assertTrue("带账号一次就成了，不该再退避重读：$seen", seen.size == 1)
        assertTrue("每次尝试的形态没有记录下来：$attempts", attempts.any { it.contains("账号Cookie=带") })
        assertTrue("每一轮都应该换身份：$attempts", attempts.all { it.contains("新注册身份") })
    }

    @Test fun deviceAndAccountCookiesAreMergedIntoOneHeader() {
        var carried: String? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = if (path != tabPath && path != legacyPath) "{}" else {
                val cookie = request.header("Cookie")
                if (cookie?.contains("sessionid") == true) { carried = cookie; payload("123") } else ""
            }
            respond(request, body)
        }.build()
        val repository = ContentRepository(
            http, System::currentTimeMillis,
            PlaybackDeviceSession {
                DeviceRegister.Registered("1001", "2001", cookies = linkedMapOf("ttreq" to "device-cookie"))
            },
            accountCookie = { "sessionid=account-cookie" },
        )
        assertEquals("https://example.com/signed", repository.streamLadder("123").renditions.single().url)
        assertNotNull("带账号那一轮没有发出请求", carried)
        assertTrue("注册下发的设备 Cookie 被丢了：$carried", carried!!.contains("ttreq=device-cookie"))
        assertTrue("账号 Cookie 没带上：$carried", carried!!.contains("sessionid=account-cookie"))
    }

    /** 账号凭据失效时带账号那一轮也会被拒；这时仍要退回匿名试一次，不能整条路都不通。 */
    @Test fun expiredAccountCookieStillFallsBackToAnAnonymousRound() {
        val seen = mutableListOf<String?>()
        val repository = ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val body = if (path != tabPath && path != legacyPath) "{}" else {
                    seen += request.header("Cookie")
                    // 带不带账号都回 110001：模拟账号凭据已失效。
                    if (path == legacyPath) "" else JSONObject().put("code", 110001).toString()
                }
                respond(request, body)
            }.build(),
            System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1001", "2001") },
            accountCookie = { "sessionid=expired-account-cookie" },
        )
        val failure = runCatching { repository.streamLadder("123") }.exceptionOrNull()
        assertTrue("已登录时不该报「需要登录」：$failure", failure !is PlaybackLoginRequiredException)
        assertTrue("带账号那一轮之后应该再匿名试一轮：$seen", seen.any { it?.contains("sessionid") != true })
    }

    @Test fun signedOutRequestsSayLoginAfterOneRejection() {
        val seen = mutableListOf<String?>()
        val repository = ContentRepository(
            accountOnlyHttp(seen), System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1001", "2001") },
        )
        val failure = runCatching { repository.streamLadder("123") }.exceptionOrNull()
        assertTrue("未登录时应该报「需要登录红果账号」：$failure", failure is PlaybackLoginRequiredException)
        assertTrue("未登录却带上了账号 Cookie：$seen", seen.none { it?.contains("sessionid") == true })
        assertTrue("未登录时不该退避重读四轮，新接口一次、旧接口一次就够：$seen", seen.size == 2)
    }
}
