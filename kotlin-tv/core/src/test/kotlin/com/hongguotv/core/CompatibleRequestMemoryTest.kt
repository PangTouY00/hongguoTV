// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全档私有编码时那条兼容请求（`codec_type=0&force_codec=h264`）走的是旧接口。
 *
 * 2026-11-18 的实机日志里它对每一集都回 HTTP 200 + 0 字节，一次 0.9～1.8 秒，连着五次全失败；
 * 而最终播通的恰恰是 v5 给的那份 1080P bytevc1。也就是说这一笔钱每次都付、每次都没换回任何东西。
 * 首页试播四次取址里它占了 32%～47%，正式播放那一次占了取址总耗时的 68%。
 *
 * 这里要求同一件事只问一次：问失败过就先别再问，或者这一集用的编码是真播通过过的就别再问。
 * 判断依据全是实测到的事实，不是设备能力表——本仓库选档一贯不预判设备能不能解。
 */
class CompatibleRequestMemoryTest {
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val primaryPath = "/novel/player/multi_video_model/player/v1"

    private fun row(url: String, codec: String, definition: String) = JSONObject()
        .put("main_url", url)
        .put("video_meta", JSONObject().put("definition", definition).put("codec_type", codec)
            .put("vwidth", 1280).put("vheight", 720))
        .put("encrypt_info", JSONObject().put("encrypt", false))

    private fun tabBody(id: String, vararg rows: JSONObject) = JSONObject()
        .put("code", 0)
        .put("data", JSONObject().put("video_model_data", JSONObject().put(id, JSONObject()
            .put("video_model", JSONObject().put("video_list", JSONArray(rows.toList())).toString()))))
        .toString()

    /** 每一档都是 bytevc1 的一集：正是会触发兼容请求的那种。 */
    private fun vendorOnly(id: String) =
        tabBody(id, row("https://example.com/$id.mp4", "bytevc1", "1080p"))

    /** 兼容请求被拒：线上是 HTTP 200 空正文，这里用业务码拒绝，都属于「这条接口不给这一问答复」。 */
    private fun refused() = JSONObject().put("code", 110001).toString()

    private class Harness(val repository: ContentRepository, val requests: MutableList<Request>,
        val attempts: MutableList<String>) {
        fun compatibleAsks() = requests.count { it.url.queryParameter("force_codec") != null }
    }

    private fun harness(responder: (Request) -> String, clock: () -> Long = System::currentTimeMillis,
        account: String = ""): Harness {
        val requests = mutableListOf<Request>()
        val attempts = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responder(request).toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()
        return Harness(ContentRepository(http, clock,
            PlaybackDeviceSession { DeviceRegister.Registered("333", "444") },
            accountCookie = { account },
            onPlaybackAttempt = { attempts += it }), requests, attempts)
    }

    @Test fun aFailedCompatibleRequestIsNotAskedAgainForTheNextEpisode() {
        val h = harness({ request ->
            when {
                request.url.encodedPath != tabPath -> "{}"
                request.url.queryParameter("force_codec") != null -> refused()
                else -> vendorOnly(request.url.queryParameter("video_id")!!)
            }
        })
        val first = h.repository.legacyStreamLadder("123")
        val second = h.repository.legacyStreamLadder("456")
        assertEquals("https://example.com/123.mp4", first.renditions.single().url)
        assertEquals("https://example.com/456.mp4", second.renditions.single().url)
        assertEquals("第二集不该再问一遍：${h.requests}", 1, h.compatibleAsks())
        assertTrue("跳过这件事要写进日志，否则没人知道为什么少了一次：${h.attempts}",
            h.attempts.any { it.contains("兼容请求刚失败过") })
    }

    /** 这一集用的编码真渲染出过首帧，就不必再问 h264：那是已经答过的问题。 */
    @Test fun aCodecThatReallyRenderedIsNotAskedAboutAgain() {
        val h = harness({ request ->
            if (request.url.encodedPath != tabPath) "{}" else vendorOnly(request.url.queryParameter("video_id")!!)
        })
        h.repository.notePlayableCodec("bytevc1")
        val ladder = h.repository.legacyStreamLadder("123")
        assertEquals("https://example.com/123.mp4", ladder.renditions.single().url)
        assertEquals("已经实测能播的编码不该再要兼容档：${h.requests}", 0, h.compatibleAsks())
        assertTrue("跳过这件事要写进日志：${h.attempts}",
            h.attempts.any { it.contains("本机已实测这种编码能播") })
    }

    /** 记的是编码本身，大小写不影响：接口写 `byteVC1` 也算同一种。 */
    @Test fun theMemoryMatchesTheCodecWhateverCaseTheApiReports() {
        val h = harness({ request ->
            if (request.url.encodedPath != tabPath) "{}" else tabBody(request.url.queryParameter("video_id")!!,
                row("https://example.com/only.mp4", "byteVC1", "1080p"))
        })
        h.repository.notePlayableCodec("BYTEVC1")
        val ladder = h.repository.legacyStreamLadder("123")
        assertEquals("https://example.com/only.mp4", ladder.renditions.single().url)
        assertEquals("接口写成大写也不该漏掉：${h.requests}", 0, h.compatibleAsks())
    }

    /** 冷却不是永久结论：过了冷却期再问一次，服务端那边可能已经变了。 */
    @Test fun theCooldownExpiresAndTheQuestionIsAskedAgain() {
        var now = 1_000_000L
        val h = harness({ request ->
            when {
                request.url.encodedPath != tabPath -> "{}"
                request.url.queryParameter("force_codec") != null -> refused()
                else -> vendorOnly(request.url.queryParameter("video_id")!!)
            }
        }, clock = { now })
        h.repository.legacyStreamLadder("123")
        h.repository.legacyStreamLadder("456", fresh = true)
        assertEquals("冷却期内不该再问：${h.requests}", 1, h.compatibleAsks())
        now += 11L * 60 * 1000
        h.repository.legacyStreamLadder("789", fresh = true)
        assertEquals("冷却期过后应该再问一次：${h.requests}", 2, h.compatibleAsks())
    }

    /**
     * 成功那一行必须写**实际成功的那条请求**带没带账号 Cookie。
     *
     * v5 那条强制不带任何 Cookie（`CookieJar.NO_COOKIES`）。照轮次写「带」会让人顺着 Cookie 去找原因，
     * 而那次失败与 Cookie 毫无关系——2026-11-18 的日志就是这么误导人的。
     */
    @Test fun aV5SuccessSaysThatRequestCarriedNoAccountCookie() {
        val rows = JSONArray().put(row("https://example.com/v5.mp4", "bytevc1", "1080p"))
        val model = JSONObject()
            .put("code", 0)
            .put("data", JSONObject().put("123", JSONObject()
                .put("video_model", JSONObject().put("video_list", rows))))
            .toString()
        val h = harness({ request ->
            if (request.url.encodedPath == primaryPath) model else "{}"
        }, account = "sessionid=abc")
        val ladder = h.repository.streamLadder("123")
        assertEquals("https://example.com/v5.mp4", ladder.renditions.single().url)
        val success = h.attempts.firstOrNull { it.contains("取址成功") }
        assertTrue("没有写出成功来自哪条请求：${h.attempts}", success != null)
        assertTrue("成功那一行写错了 Cookie：$success",
            success!!.contains("清晰度来自 v5") && success.contains("账号Cookie=不带"))
    }

    @Test fun aLegacySuccessNamesTheEndpointAndTheCookieItActuallySent() {
        val h = harness({ request ->
            if (request.url.encodedPath != tabPath) "{}" else vendorOnly(request.url.queryParameter("video_id")!!)
        }, account = "sessionid=abc")
        h.repository.legacyStreamLadder("123")
        val success = h.attempts.firstOrNull { it.contains("取址成功") }
        assertTrue("没有写出成功来自哪条请求：${h.attempts}", success != null)
        assertTrue("带账号的那条要写「带」：$success",
            success!!.contains("清晰度来自 video_tab") && success.contains("账号Cookie=带"))
    }
}
