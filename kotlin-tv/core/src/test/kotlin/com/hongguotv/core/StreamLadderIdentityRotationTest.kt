// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取址被拒时重读必须同时换一份注册身份：实测同一份身份反复请求容易一直空，
 * 换一份新注册的身份更容易立刻放行。
 *
 * 2026-11-09 之后这条只在**已登录**时成立：未登录时两个取址接口对每一集都回 110001，
 * 换身份没有任何作用，那种情况照 [PlaybackAccountIdentityTest] 里的一条直接报「需要登录」。
 */
class StreamLadderIdentityRotationTest {
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val legacyPath = "/novel/player/multi_video_model/v1/"

    private fun payload(id: String): String {
        val row = JSONObject()
            .put("main_url", "https://example.com/signed")
            .put("video_meta", JSONObject().put("definition", "720p").put("codec_type", "h264")
                .put("vwidth", 720).put("vheight", 1280))
            .put("encrypt_info", JSONObject().put("encrypt", false))
        val model = JSONObject().put("video_list", JSONArray().put(row))
        val entry = JSONObject().put("video_model", model)
        return JSONObject().put("code", 0).put("message", "SUCCESS")
            .put("data", JSONObject().put("video_model_data", JSONObject().put(id, entry)))
            .toString()
    }

    @Test fun emptyResponsesRotateTheRegisteredIdentityUntilTheLadderArrives() {
        val devices = mutableListOf<String>()
        val registrations = AtomicInteger()
        val repository = ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val body: String = if (request.url.encodedPath != tabPath) "{}" else {
                    devices += request.url.queryParameter("device_id").orEmpty()
                    if (devices.size <= 3) "" else payload("123")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
            }.build(),
            System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("${1000 + registrations.incrementAndGet()}", "2000") },
            accountCookie = { "sessionid=account-cookie" },
        )
        assertEquals("https://example.com/signed", repository.streamLadder("123").renditions.single().url)
        val distinct = devices.filter { it.isNotBlank() }.distinct()
        assertTrue("取址重读没有换过注册身份：$devices", distinct.size >= 2)
    }

    @Test fun rejectedCodeAlsoRotatesAndFinallyReportsTheCode() {
        val codes = mutableListOf<String>()
        val registrations = AtomicInteger()
        val repository = ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                // 两个接口一起被拒才是线上真实形态：旧接口自 2026-11-05 起带签名也回 110001。
                val path = request.url.encodedPath
                val body: String = if (path != tabPath && path != legacyPath) "{}" else {
                    if (path == tabPath) codes += request.url.queryParameter("device_id").orEmpty()
                    JSONObject().put("code", 110001).put("message", "reject").toString()
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
            }.build(),
            System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("${1000 + registrations.incrementAndGet()}", "2000") },
            accountCookie = { "sessionid=account-cookie" },
        )
        val failure = runCatching { repository.streamLadder("123") }.exceptionOrNull()
        assertTrue("已登录时取址被拒不该报成「需要登录」：$failure", failure is PlaybackRejectedException)
        assertEquals(110001, (failure as PlaybackRejectedException).code)
        assertTrue("110001 的重读没有换过注册身份：$codes", codes.filter { it.isNotBlank() }.distinct().size >= 2)
    }
}
