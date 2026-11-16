package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 带账号身份时先试 `POST /novel/player/video_model/v1/`：同类项目 HGTV 记录 2026-10 起
 * 带场景段的 `multi_video_model/:dr_scene/v1/` 一律回空正文，能用的这条要 `video_id` 单值，
 * 而且匿名请求同样回空——所以它只在带账号的那轮被试。
 *
 * 新增一条候选不能把原来能用的那条挤掉：它拿不到时 `GET /reading/bookapi/video_tab/…` 必须照旧走通。
 */
class PostVideoModelAddressTest {
    private val postPath = "/novel/player/video_model/v1/"
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"

    private fun model(url: String) = JSONObject().put("video_list", JSONArray().put(JSONObject()
        .put("main_url", url)
        .put("video_meta", JSONObject().put("definition", "720p").put("codec_type", "h264")
            .put("vwidth", 1280).put("vheight", 720))
        .put("encrypt_info", JSONObject().put("encrypt", false))))

    /** 新路由把模型放在 data.video_model，并且这里给的是字符串化的 JSON。 */
    private fun postBody() = JSONObject().put("code", 0)
        .put("data", JSONObject().put("video_model", model("https://example.com/post-model.mp4").toString()))
        .toString()

    private fun tabBody(id: String) = JSONObject().put("code", 0)
        .put("data", JSONObject().put("video_model_data", JSONObject().put(id,
            JSONObject().put("video_model", model("https://example.com/tab-model.mp4")))))
        .toString()

    private fun repository(
        requests: MutableList<Request>,
        postResponds: Boolean,
        log: MutableList<String>,
    ): ContentRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when {
                request.url.encodedPath == postPath && postResponds -> postBody()
                request.url.encodedPath == postPath -> ""
                else -> tabBody("123")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()
        return ContentRepository(http, System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("333", "444") },
            accountCookie = { "sessionid=abc123" },
            onPlaybackAttempt = { log += it })
    }

    @Test fun accountRoundTriesThePostModelFirst() {
        val requests = mutableListOf<Request>()
        val log = mutableListOf<String>()
        val ladder = repository(requests, postResponds = true, log = log).streamLadder("123")

        assertEquals("https://example.com/post-model.mp4", ladder.renditions.single().url)
        val post = requests.single { it.url.encodedPath == postPath }
        assertEquals("POST", post.method)
        // 请求体要 video_id 单值，不是 mixed_video_id_map。
        val payload = JSONObject(okio.Buffer().also { post.body!!.writeTo(it) }.readUtf8())
        assertEquals("123", payload.getString("video_id"))
        assertFalse(payload.has("mixed_video_id_map"))
        assertTrue(post.header("Cookie")!!.contains("sessionid=abc123"))
        // 这条已经给出清晰度，就不必再打原来那条。
        assertEquals(0, requests.count { it.url.encodedPath == tabPath })
    }

    @Test fun emptyPostModelStillFallsBackToTheExistingEndpoint() {
        val requests = mutableListOf<Request>()
        val log = mutableListOf<String>()
        val ladder = repository(requests, postResponds = false, log = log).streamLadder("123")

        assertEquals("https://example.com/tab-model.mp4", ladder.renditions.single().url)
        assertTrue(requests.any { it.url.encodedPath == postPath })
        assertTrue(requests.any { it.url.encodedPath == tabPath })
        // 失败要留在诊断日志里，不能悄悄被吞掉。
        assertTrue(log.any { it.contains("POST video_model") })
    }
}

private fun readUtf8(buffer: okio.Buffer): String = buffer.readUtf8()
