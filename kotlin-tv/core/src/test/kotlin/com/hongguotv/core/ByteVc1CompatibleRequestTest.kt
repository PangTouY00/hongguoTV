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
 * 服务端对某些集五档全给 bytevc1（官方 App 靠自带的 libByteVC1_dec.so 才解得出，系统播放器解不出）。
 *
 * 旧接口那条兼容请求（`codec_type=0&force_codec=h264`）原来只在 fallback 路径上、且只认 bytevc2，
 * 所以这类集会一路走到播放器然后报「无法播放」。这里要求新接口自己也能改走兼容请求，
 * 并且只在**每一档都是私有编码**时才多要一次；兼容请求没给出可解码档时退回原来那份阶梯。
 */
class ByteVc1CompatibleRequestTest {
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val legacyPath = "/novel/player/multi_video_model/v1/"

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

    private fun repository(responder: (Request) -> String): Pair<ContentRepository, MutableList<Request>> {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responder(request).toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()
        return ContentRepository(http, System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }) to requests
    }

    @Test fun allByteVc1EpisodeAsksForTheCompatibleModelOnTheCurrentEndpoint() {
        val (repository, requests) = repository { request ->
            when {
                request.url.encodedPath != tabPath -> "{}"
                request.url.queryParameter("force_codec") != null ->
                    tabBody("123", row("https://example.com/playable.mp4", "h264", "720p"))
                else -> tabBody("123", row("https://example.com/only-bytevc1.mp4", "bytevc1", "1080p"))
            }
        }
        val ladder = repository.streamLadder("123")
        assertEquals("https://example.com/playable.mp4", ladder.renditions.single().url)

        val compatible = requests.single { it.url.queryParameter("force_codec") != null }
        assertEquals(tabPath, compatible.url.encodedPath)
        assertEquals("0", compatible.url.queryParameter("codec_type"))
        assertEquals("h264", compatible.url.queryParameter("force_codec"))
        assertEquals(1, requests.count { it.url.queryParameter("force_codec") != null })
    }

    @Test fun aPlayableTierInSameEpisodeSkipsTheCompatibleRequest() {
        val (repository, requests) = repository { request ->
            if (request.url.encodedPath != tabPath) "{}" else tabBody("123",
                row("https://example.com/only-bytevc1.mp4", "bytevc1", "1080p"),
                row("https://example.com/playable.mp4", "h264", "720p"))
        }
        val ladder = repository.streamLadder("123")
        assertEquals(2, ladder.renditions.size)
        assertTrue(ladder.renditions.any { it.codec == "h264" })
        assertEquals(0, requests.count { it.url.queryParameter("force_codec") != null })
    }

    /** 兼容请求也全是私有编码时不能把原阶梯丢掉：多要一次不会让这一集变得更坏。 */
    @Test fun compatibleRequestWithoutAPlayableTierKeepsTheOriginalLadder() {
        val (repository, requests) = repository { request ->
            when {
                request.url.encodedPath != tabPath -> "{}"
                request.url.queryParameter("force_codec") != null ->
                    tabBody("123", row("https://example.com/still-bytevc1.mp4", "bytevc1", "720p"))
                else -> tabBody("123", row("https://example.com/only-bytevc1.mp4", "bytevc1", "1080p"))
            }
        }
        val ladder = repository.streamLadder("123")
        assertEquals("https://example.com/only-bytevc1.mp4", ladder.renditions.single().url)
        assertEquals(1, requests.count { it.url.queryParameter("force_codec") != null })
    }

    @Test fun compatibleRequestFailureKeepsTheOriginalLadder() {
        val (repository, requests) = repository { request ->
            when {
                request.url.encodedPath != tabPath -> "{}"
                request.url.queryParameter("force_codec") != null -> JSONObject().put("code", 110001).toString()
                else -> tabBody("123", row("https://example.com/only-bytevc1.mp4", "bytevc1", "1080p"))
            }
        }
        val ladder = repository.streamLadder("123")
        assertEquals("https://example.com/only-bytevc1.mp4", ladder.renditions.single().url)
        assertEquals(1, requests.count { it.url.queryParameter("force_codec") != null })
    }
}
