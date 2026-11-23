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
 * 取播放信息的两条请求都必须带**真**签名头。
 *
 * 2026-11-05 实测：同一台设备、同一个接口，不带签名的请求一律 `HTTP 200 + 0 字节`，
 * 带签名的请求回 `code 0` + 完整播放模型；换域名、换 UA、换出口 IP、补参数都不改变这一点。
 *
 * 2026-11-11 实测：`x-argus=base64(时间戳)` 那对占位 argus/ladon 不够，换成 `PlayerSigner`
 * 真算的那一份才通。当时又得出「medusa/helios 必须留着」（依据是同类项目 charlie901208/HGTV 的
 * docs/APP_SIGNING.md §11.2，2026-10-09）；2026-11-23 在本机复测不成立——同一集、同一份新注册身份，
 * 带 medusa/helios 的 video_tab GET 与旧 POST 在三条域名上全部 `HTTP 200 + 0 字节`。主仓库
 * tsukiyo-zero/hongguoTV 现行实现也把这些头停发，只带 `PlayerSigner` 真算的那一组。
 * 所以这里锁的是：真 argus/ladon 必须能解开，且 medusa/helios/gorgon 一律不发。
 */
class PlaybackRequestSignatureTest {
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val legacyPath = "/novel/player/multi_video_model/v1/"
    private val signatureHeaders = listOf("x-argus", "x-ladon", "x-khronos", "x-ss-req-ticket")
    // 2026-11-23 起这三个头一律不发：它们与 PlayerSigner 真头同时出现时，整条请求被当作带伪造痕迹拒掉。
    private val stoppedHeaders = listOf("x-medusa", "x-helios", "x-gorgon")

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
        assertEquals("https://example.com/signed", repository.legacyStreamLadder("123").renditions.single().url)

        val tab = requests.single { it.url.encodedPath == tabPath }
        for (name in signatureHeaders) assertNotNull("播放取址缺少签名头 $name", tab.header(name))
        for (name in stoppedHeaders) assertNull("播放取址带了应当停发的签名头 $name", tab.header(name))
        // x-khronos 与 URL 的 _rticket 同源，签名才对得上 query。
        assertEquals(tab.url.queryParameter("_rticket")!!.toLong() / 1000, tab.header("x-khronos")!!.toLong())
        // x-argus 必须是能解开的真 bean（占位值解不开），且带本次调用的随机 nonce。
        assertNotNull("x-argus 不是可解开的真签名", Argus.decrypt551(tab.header("x-argus")!!))
        assertNotNull("x-argus 缺少本次调用的 nonce 字段", Argus.recover551Field3(tab.header("x-argus")!!))
        assertEquals("333", tab.url.queryParameter("device_id"))
        assertEquals("444", tab.url.queryParameter("iid"))
        assertEquals("123", tab.url.queryParameter("video_id"))
        assertEquals("1024", tab.url.queryParameter("video_platform"))
        assertEquals("1", tab.url.queryParameter("device_level"))
        // 官方取播放信息的请求不带 ts，只带 _rticket（主仓库的播放签名同此）。
        assertNull(tab.url.queryParameter("ts"))
        assertNotNull(tab.url.queryParameter("_rticket"))
    }

    @Test fun tabRequestSendsOnlyTheOfficialParameterSet() {
        val requests = mutableListOf<Request>()
        val repository = repository { request ->
            requests += request
            if (request.url.encodedPath == tabPath) tabPayload("123") else "{}"
        }
        repository.legacyStreamLadder("123")
        val tab = requests.single { it.url.encodedPath == tabPath }
        // from_video_id 是官方 GetVideoModelRequest 里的字段，只取一集时留空也要带上；其余参数一律非空。
        assertTrue(tab.url.queryParameterNames.contains("from_video_id"))
        assertTrue(tab.url.queryParameterNames.filter { it != "from_video_id" }.all { tab.url.queryParameter(it)?.isNotEmpty() == true })
    }

    @Test fun legacyPlaybackModelRequestIsSignedWithItsBody() {
        val requests = mutableListOf<Request>()
        val repository = repository { request ->
            requests += request
            if (request.url.encodedPath == tabPath) """{"code":100103,"message":"PARAM_INVALID"}""" else legacyPayload("123")
        }
        assertEquals("https://example.com/legacy", repository.legacyStreamLadder("123").renditions.single().url)

        val legacy = requests.single { it.url.encodedPath == legacyPath }
        for (name in signatureHeaders) assertNotNull("旧播放接口缺少签名头 $name", legacy.header(name))
        for (name in stoppedHeaders) assertNull("旧播放接口带了应当停发的签名头 $name", legacy.header(name))
        // POST 的 body 参与签名：x-ss-stub 是 body 的 MD5，并作为 argus 的 body hash 输入。
        assertNotNull(legacy.header("x-ss-stub"))
        // 大写十六进制（设备注册那条用的也是大写）：小写的那一份服务端按没给处理。
        assertTrue(legacy.header("x-ss-stub")!!.all { it.isDigit() || it in 'A'..'F' })
        assertNotNull(legacy.body)
        assertNotNull("x-argus 不是可解开的真签名", Argus.decrypt551(legacy.header("x-argus")!!))
    }

    /** 每一轮取址都要重新签名：同一个 argus 重放第二次还是会被拒。 */
    @Test fun eachAddressRoundReSignsTheRequest() {
        val tabArgus = mutableListOf<String?>()
        val repository = repository { request ->
            if (request.url.encodedPath == tabPath) { tabArgus += request.header("x-argus"); tabPayload("123") } else "{}"
        }
        repository.legacyStreamLadder("123", fresh = true)
        repository.legacyStreamLadder("123", fresh = true)
        assertEquals(2, tabArgus.size)
        assertNotEquals(tabArgus[0], tabArgus[1])
        assertNotEquals(tabArgus[0]?.let { Argus.recover551Field3(it) }, tabArgus[1]?.let { Argus.recover551Field3(it) })
    }
}
