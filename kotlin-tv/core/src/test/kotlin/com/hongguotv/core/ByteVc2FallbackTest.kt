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
 * 服务端对某些集只发 bytevc2（Android 的 MP4 解不出视频轨）。
 *
 * 这时该走官方播放器那条兼容请求（`codec_type=0&force_codec=h264`），
 * 并且解密 `main_url` 用的 `key_seed` 必须换成兼容请求那一份——两份响应的 seed 不通用，
 * 拿旧 seed 解新 url 会得到一串解不开的东西，整条清晰度列表又变空。
 */
class ByteVc2FallbackTest {
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val legacyPath = "/novel/player/multi_video_model/v1/"
    private val fallbackUrl = "https://vas-lf-x.snssdk.com/video/fplay/1/abc/v03test"

    private fun model(codec: String) = JSONObject()
        .put("status", 10).put("message", "success")
        .put("fallback_api", JSONObject().put("fallback_api", fallbackUrl))
        .put("video_list", JSONArray().put(JSONObject()
            .put("main_url", "https://example.com/only-bytevc2.mp4")
            .put("video_meta", JSONObject().put("definition", "720p").put("codec_type", codec)
                .put("vwidth", 1280).put("vheight", 720))
            .put("encrypt_info", JSONObject().put("encrypt", false))))

    private fun tabBody(id: String) = JSONObject()
        .put("code", 0)
        .put("data", JSONObject().put("video_model_data",
            JSONObject().put(id, JSONObject().put("video_model", model("bytevc2").toString()))))
        .toString()

    private fun legacyBody(id: String) = JSONObject()
        .put("code", 0)
        .put("data", JSONObject().put(id, JSONObject().put("video_model", model("bytevc2").toString())))
        .toString()

    /** 第一次读 fallback：全档 bytevc2，main_url 还是解不开的密文。 */
    private fun byteVc2Page() = JSONObject()
        .put("video_info", JSONObject().put("data", JSONObject()
            .put("key_seed", "AAAAAAAAAAAAAAAAAAAAAA==")
            .put("video_list", JSONObject().put("video_1", JSONObject()
                .put("main_url", "这一串不是能解开的密文")
                .put("codec_type", "bytevc2").put("definition", "720p").put("vheight", 720)))))
        .toString()

    /** 兼容请求的响应：给出可播的档位，并且带着自己的（这里为空的）key_seed。 */
    private fun compatiblePage() = JSONObject()
        .put("video_info", JSONObject().put("data", JSONObject()
            .put("video_list", JSONObject().put("video_1", JSONObject()
                .put("main_url", "https://example.com/playable.mp4")
                .put("codec_type", "h265").put("definition", "720p").put("vheight", 720)))))
        .toString()

    @Test fun allByteVc2EpisodeFallsBackToTheCodecCompatibleRequest() {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when {
                request.url.encodedPath == tabPath -> tabBody("123")
                request.url.encodedPath == legacyPath -> legacyBody("123")
                request.url.queryParameter("force_codec") != null -> compatiblePage()
                else -> byteVc2Page()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()
        val repository = ContentRepository(http, System::currentTimeMillis,
            PlaybackDeviceSession { DeviceRegister.Registered("333", "444") })

        val ladder = repository.streamLadder("123")
        assertEquals("https://example.com/playable.mp4", ladder.renditions.single().url)

        val compatible = requests.single { it.url.queryParameter("force_codec") != null }
        assertEquals("0", compatible.url.queryParameter("codec_type"))
        assertEquals("h264", compatible.url.queryParameter("force_codec"))
        // 只在这一集确实全档 bytevc2 时才多打这一次。
        assertEquals(1, requests.count { it.url.queryParameter("force_codec") != null })
    }
}
