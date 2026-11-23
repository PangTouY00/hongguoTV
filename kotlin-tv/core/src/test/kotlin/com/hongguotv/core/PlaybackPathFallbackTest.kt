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
 * 取址顺序（2026-11-23 起照主仓库 tsukiyo-zero/hongguoTV）：先 video_tab GET，
 * 三条入口按健康度轮换、回空的那条冷却 30 秒；三条都不给结果才试后面的接口。
 *
 * 线上对照（2026-11-23，同一台 Android 16 手机、同一份新注册匿名身份、同一集号）：
 * 三条入口全部 `HTTP 200 + 0 字节`，v5 那两条写法同样全空；只有带账号身份才下发清晰度。
 * 所以这里锁的是「三条入口各试一次、不重复撞同一条」，以及匿名全被拒时报「需要登录」。
 */
class PlaybackPathFallbackTest {
    private val primaryPath = "/novel/player/multi_video_model/player/v1"
    private val fallbackPath = "/novel/player/multi_video_model/v1/"
    private val legacyPath = "/novel/player/multi_video_model/v1/"
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val tabHosts = PlayRetryPlan.hosts

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
                .put("encrypt_info", JSONObject().put("encrypt", false))))))))
        .toString()

    private fun respond(request: okhttp3.Request, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()

    /** 按 path 给响应；tabPath 的响应再按域名区分，用来观察轮换顺序。 */
    private fun repositoryOf(bodies: Map<String, String> = emptyMap(), byHost: Map<String, String> = emptyMap(),
                            seen: MutableList<String>, attempts: MutableList<String> = mutableListOf()): ContentRepository =
        ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                seen += if (path == tabPath) "$tabPath@${request.url.host}" else path
                val body = if (path == tabPath && request.url.host in byHost) byHost.getValue(request.url.host)
                    else bodies[path] ?: "{}"
                respond(request, body)
            }.build(),
            System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("1001", "2001") },
            onPlaybackAttempt = { attempts += it },
        )

    /** 第一条入口回空就换下一条，三条按 [PlayRetryPlan.hosts] 的顺序各试一次，不重复撞同一条。 */
    @Test fun tabHostsRotateAfterAnEmptyResponse() {
        val seen = mutableListOf<String>()
        val attempts = mutableListOf<String>()
        val repository = repositoryOf(
            byHost = mapOf(tabHosts.first() to "", tabHosts[1] to "", tabHosts.last() to tabPayload("123")),
            seen = seen, attempts = attempts,
        )
        val ladder = repository.streamLadder("123")
        assertEquals("https://example.com/tab", ladder.renditions.single().url)
        assertEquals("三条入口该按顺序各试一次：$seen", tabHosts.map { "$tabPath@$it" }, seen)
        assertTrue("换域名这件事没有写进日志：$attempts",
            attempts.any { it.contains("video_tab ${PlayRetryPlan.tag(tabHosts.first())} 失败") })
    }

    /** 三条入口都不给结果，才轮到 v5 那两条写法。 */
    @Test fun allTabHostsEmptyOnlyThenFallsBackToV5() {
        val seen = mutableListOf<String>()
        val repository = repositoryOf(linkedMapOf(
            tabPath to "",
            primaryPath to model("123"),
            fallbackPath to model("123"),
        ), seen = seen)
        val ladder = repository.streamLadder("123")
        assertEquals("https://example.com/720p", ladder.renditions.maxByOrNull { it.quality }!!.url)
        assertEquals("先三条入口、再 v5 当前写法：$seen",
            tabHosts.map { "$tabPath@$it" } + primaryPath, seen)
    }

    /** 三条入口都成功时不该多花请求：第一条成功就不再试后面两条。 */
    @Test fun firstHealthyTabHostIsUsedAgainOnTheNextRound() {
        val seen = mutableListOf<String>()
        val repository = repositoryOf(byHost = tabHosts.associateWith { tabPayload("123") }, seen = seen)
        repeat(2) {
            assertEquals("https://example.com/tab", repository.streamLadder("123", fresh = true).renditions.single().url)
        }
        assertEquals("成功过的入口仍然优先，且每次只发一条：$seen",
            listOf("$tabPath@${tabHosts.first()}", "$tabPath@${tabHosts.first()}"), seen)
    }

    /** 未登录时三条接口一律回空：该报「需要登录」，不是「暂时取不到」。 */
    @Test fun anonymousRejectionReportsLoginRequired() {
        val seen = mutableListOf<String>()
        val attempts = mutableListOf<String>()
        val repository = repositoryOf(linkedMapOf(
            tabPath to "", primaryPath to "", fallbackPath to "", legacyPath to "",
        ), seen = seen, attempts = attempts)
        val problem = runCatching { repository.streamLadder("123") }.exceptionOrNull()
        assertTrue("匿名全被拒时应该报需要登录：$problem / $attempts",
            generateSequence(problem) { it.cause }.any { it is PlaybackLoginRequiredException })
    }
}
