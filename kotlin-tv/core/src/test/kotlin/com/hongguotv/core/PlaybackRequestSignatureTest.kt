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
 * 取播放信息的两条请求都必须带官方那套签名头。
 *
 * 2026-11-05 实测：同一台设备、同一个接口，不带签名的请求一律 `HTTP 200 + 0 字节`，
 * 带签名的请求回 `code 0` + 完整播放模型；换域名、换 UA、换出口 IP、补参数都不改变这一点。
 * 所以这里锁住签名头与请求参数，避免再退回只拼 query 的写法。
 */
class PlaybackRequestSignatureTest {
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val legacyPath = "/novel/player/multi_video_model/v1/"
    private val signatureHeaders = listOf("x-gorgon", "x-argus", "x-ladon", "x-khronos", "x-helios")

    private fun row(url: String, quality: Int, height: Int) = JSONObject()
        .put("main_url", url)
        .put("video_meta", JSONObject().put("definition", "${quality}p").put("codec_type", "h264")
            .put("vwidth", quality).put("vheight", height))
        .put("encrypt_info", JSONObject().put("encrypt", false))

    private fun tabPayload(id: String) = JSONObject()
        .put("code", 0).put("message", "SUCCESS")
        .put("data", JSONObject().put("video_model_data", JSONObject().put(id,
            JSONObject().put("video_model", JSONObject().put("video_list", JSONArray().put(row("https://example.com/signed", 720, 1280)))))))
        .toString()

    private fun legacyPayload(id: String) = JSONObject()
        .put("code", 0)
        .put("data", JSONObject().put(id, JSONObject().put("video_model",
            JSONObject().put("video_list", JSONArray().put(row("https://example.com/legacy", 480, 854))))))
        .toString()

    private fun repository(respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(respond(request).toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build(),
        System::currentTimeMillis,
        // 播放身份固定成注册结果，测试不真的去打设备注册接口。
        PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }
    )

    @Test fun tabRequestCarriesSignatureHeadersAndTheRegisteredIdentity() {
        val requests = mutableListOf<Request>()
        val repository = repository { request ->
            requests += request
            if (request.url.encodedPath == tabPath) tabPayload("123") else "{}"
        }
        assertEquals("https://example.com/signed", repository.streamLadder("123").renditions.single().url)

        val tab = requests.single { it.url.encodedPath == tabPath }
        for (name in signatureHeaders) assertNotNull("播放取址缺少签名头 $name", tab.header(name))
        assertEquals("333", tab.url.queryParameter("device_id"))
        assertEquals("444", tab.url.queryParameter("iid"))
        assertEquals("123", tab.url.queryParameter("video_id"))
        assertEquals("1024", tab.url.queryParameter("video_platform"))
        assertEquals("1", tab.url.queryParameter("device_level"))
        assertNotNull(tab.url.queryParameter("ts"))
        assertNotNull(tab.url.queryParameter("_rticket"))
    }

    @Test fun tabRequestDoesNotSendEmptyParameters() {
        val requests = mutableListOf<Request>()
        val repository = repository { request ->
            requests += request
            if (request.url.encodedPath == tabPath) tabPayload("123") else "{}"
        }
        repository.streamLadder("123")
        val tab = requests.single { it.url.encodedPath == tabPath }
        // 空值参数（from_video_id=）不是官方请求的一部分，服务端会把它当成异常客户端。
        assertNull(tab.url.queryParameter("from_video_id"))
        assertTrue(tab.url.queryParameterNames.none { tab.url.queryParameter(it).isNullOrEmpty() })
    }

    @Test fun legacyPlaybackModelRequestIsSignedWithItsBody() {
        val requests = mutableListOf<Request>()
        val repository = repository { request ->
            requests += request
            if (request.url.encodedPath == tabPath) """{"code":100103,"message":"PARAM_INVALID"}""" else legacyPayload("123")
        }
        assertEquals("https://example.com/legacy", repository.streamLadder("123").renditions.single().url)

        val legacy = requests.single { it.url.encodedPath == legacyPath }
        for (name in signatureHeaders) assertNotNull("旧播放接口缺少签名头 $name", legacy.header(name))
        // POST 的 body 参与签名：x-ss-stub 是 body 的 MD5。
        assertNotNull(legacy.header("x-ss-stub"))
        assertNotNull(legacy.body)
    }
}
