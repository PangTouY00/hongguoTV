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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线上对照（2026-11-09 实测）：同一台设备、同一集，官方 App 一次就取到清晰度，
 * 我们这种纯匿名请求约十次里只有三四次回 `code=0`，其余是 HTTP 200 空正文。
 * 已登录时取址要带上账号 Cookie，并且每一轮都换一份新注册的身份。
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

    /** 只有带账号 Cookie 的那次才给清晰度，照线上「匿名一直空、带账号就成」的形态。 */
    private fun accountOnlyHttp(seen: MutableList<String?>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = if (path != tabPath && path != legacyPath) "{}" else {
                seen += request.header("Cookie")
                if (seen.last()?.contains("sessionid") == true) payload("123")
                else if (path == legacyPath) JSONObject().put("code", 110001).toString()
                else ""
            }
            respond(request, body)
        }.build()

    @Test fun anonymousRejectionsRecoverOnceTheAccountCookieIsSent() {
        val seen = mutableListOf<String?>()
        val attempts = mutableListOf<String>()
        val repository = ContentRepository(
            accountOnlyHttp(seen), System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1001", "2001") },
            accountCookie = { "sessionid=account-cookie" },
            onPlaybackAttempt = { attempts += it },
        )
        assertEquals("https://example.com/signed", repository.streamLadder("123").renditions.single().url)
        assertTrue("取址重读没有改带账号 Cookie：$seen", seen.any { it?.contains("sessionid") == true })
        assertTrue("第一轮应该先按匿名试：$seen", seen.first()?.contains("sessionid") != true)
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

    @Test fun signedOutRequestsNeverCarryAnAccountCookie() {
        val seen = mutableListOf<String?>()
        val repository = ContentRepository(
            accountOnlyHttp(seen), System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1001", "2001") },
        )
        val failure = runCatching { repository.streamLadder("123") }.exceptionOrNull()
        assertTrue("未登录时应该照旧报取址被拒：$failure", failure is PlaybackRejectedException)
        assertTrue("未登录却带上了账号 Cookie：$seen", seen.none { it?.contains("sessionid") == true })
        assertNull("未登录时账号 Cookie 不该出现在任何一次请求里", seen.firstOrNull { it?.contains("sessionid") == true })
    }
}
