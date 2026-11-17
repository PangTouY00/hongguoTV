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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取址的两条写法只差 path：`/novel/player/multi_video_model/player/v1` 与
 * `/novel/player/multi_video_model/v1/`，正文与 v5 签名完全相同。
 *
 * 线上对照（2026-11-17，同一台 Android 16 手机、同一份新注册匿名身份、同一集号）：
 * 前者一律回 HTTP 200 + 0 字节，后者在三个域名上都回 code=0 与五档模型。
 * 所以当前写法没有结果时当场换另一条，而不是先退到 video_tab 那条旧接口。
 */
class PlaybackPathFallbackTest {
    private val primaryPath = "/novel/player/multi_video_model/player/v1"
    private val fallbackPath = "/novel/player/multi_video_model/v1/"
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"

    /** v5 那条接口的响应形状：data 的键直接就是 video_id，模型带内联五档。 */
    private fun model(id: String, codec: String = "h264"): String {
        val rows = JSONArray()
        for (definition in listOf("360p", "480p", "720p")) {
            rows.put(JSONObject()
                .put("main_url", "https://example.com/$definition")
                .put("video_meta", JSONObject().put("definition", definition).put("codec_type", codec)
                    .put("vwidth", 720).put("vheight", 1280))
                .put("encrypt_info", JSONObject().put("encrypt", false)))
        }
        return JSONObject().put("code", 0)
            .put("data", JSONObject().put(id, JSONObject().put("video_model", JSONObject().put("video_list", rows))))
            .toString()
    }

    private fun tabPayload(id: String): String = JSONObject().put("code", 0)
        .put("data", JSONObject().put("video_model_data", JSONObject().put(id, JSONObject().put("video_model",
            JSONObject().put("video_list", JSONArray().put(JSONObject()
                .put("main_url", "https://example.com/tab")
                .put("video_meta", JSONObject().put("definition", "720p").put("codec_type", "h264")
                    .put("vwidth", 720).put("vheight", 1280))
                .put("encrypt_info", JSONObject().put("encrypt", false)))))))
        .toString()

    private fun respond(request: okhttp3.Request, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()

    private fun repository(paths: MutableMap<String, String>, seen: MutableList<String>,
                          attempts: MutableList<String> = mutableListOf()): ContentRepository =
        ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                seen += path
                respond(request, paths[path] ?: "{}")
            }.build(),
            System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1001", "2001") },
            onPlaybackAttempt = { attempts += it },
        )

    @Test fun emptyPrimaryPathSwitchesToTheOtherPathInSameRound() {
        val seen = mutableListOf<String>()
        val attempts = mutableListOf<String>()
        val repository = repository(linkedMapOf(
            primaryPath to "",
            fallbackPath to model("123"),
        ), seen, attempts)
        val ladder = repository.streamLadder("123")
        assertEquals("https://example.com/720p", ladder.renditions.maxByOrNull { it.quality }!!.url)
        assertEquals("当前写法与备用写法各一次：$seen", listOf(primaryPath, fallbackPath), seen)
        assertTrue("切换这件事没有写进日志：$attempts",
            attempts.any { it.contains("当前取址写法没有结果") && it.contains(fallbackPath) })
        assertTrue("备用写法成功这件事没有写进日志：$attempts", attempts.any { it.contains("v5 取址成功（备用写法）") })
    }

    @Test fun bothPathsEmptyOnlyThenFallsBackToTheLegacyEndpoint() {
        val seen = mutableListOf<String>()
        val repository = repository(linkedMapOf(
            primaryPath to "",
            fallbackPath to "",
            tabPath to tabPayload("123"),
        ), seen)
        val ladder = repository.streamLadder("123")
        assertEquals("https://example.com/tab", ladder.renditions.single().url)
        assertEquals("两条写法都该试一次，且都在旧接口之前：$seen", listOf(primaryPath, fallbackPath, tabPath), seen)
    }

    /** 服务端明确拒绝（业务码非 0）不是「接口挂了」：换写法同样会被拒，不该为此多花一次请求。 */
    @Test fun businessCodeFailureDoesNotSwitchPath() {
        val seen = mutableListOf<String>()
        val repository = repository(linkedMapOf(
            primaryPath to JSONObject().put("code", 110001).toString(),
            fallbackPath to model("123"),
            tabPath to tabPayload("123"),
        ), seen)
        runCatching { repository.streamLadder("123") }
        assertTrue("业务码拒绝后不该换写法：$seen", seen.none { it == fallbackPath })
    }

    /** 404 就是这条写法不存在：这正是该换写法的情况。 */
    @Test fun http404OnThePrimaryPathSwitchesToTheOtherPath() {
        val seen = mutableListOf<String>()
        val repository = ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                seen += path
                val body = if (path == fallbackPath) model("123") else ""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (path == primaryPath) 404 else 200).message("test")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
            }.build(),
            System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1001", "2001") },
        )
        assertEquals("https://example.com/720p", repository.streamLadder("123").renditions.maxByOrNull { it.quality }!!.url)
        assertEquals(listOf(primaryPath, fallbackPath), seen)
    }
}
