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
 * 2026-11-23 四路对照（同一份新注册身份、同一个域名、同一份参数，只换签名头族）：
 * `x-khronos` + `x-helios` + `x-medusa` 回 22070 字节完整模型；
 * `x-argus` + `x-ladon` + `x-gorgon` + `x-khronos` 回 0 字节；不签名回 0 字节；
 * v5 三头再额外补上 argus/ladon 仍然给数据。
 * 决定因素是**有没有 medusa/helios**，不是「多带一个头就被判伪造」——此前按主仓库说法
 * 停发 medusa/helios 的那一版（PR #51）正是取址全空的原因。
 * 所以这里锁的是：v5 三头必须都在，旧族 argus/ladon/gorgon 一律不发。
 */
class PlaybackRequestSignatureTest {
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val legacyPath = "/novel/player/multi_video_model/v1/"
    private val signatureHeaders = listOf("x-khronos", "x-helios", "x-medusa")
    // 2026-11-23 起这三个头一律不发：实测带它们的那一路回 0 字节。
    private val stoppedHeaders = listOf("x-argus", "x-ladon", "x-gorgon")

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
        // x-khronos 是秒级时间戳，与 URL 的 _rticket 同源（签名器挑票据时最多跨一秒）。
        val khronos = tab.header("x-khronos")!!.toLong()
        assertTrue(khronos > 0)
        assertTrue(Math.abs(tab.url.queryParameter("_rticket")!!.toLong() / 1000 - khronos) <= 1)
        // medusa 是真签名（base64，内含 device_id 与进程 nonce），不是占位值。
        val medusa = tab.header("x-medusa")!!
        assertTrue("x-medusa 不像真签名", medusa.length > 40)
        assertTrue("x-medusa 解不开", runCatching { java.util.Base64.getDecoder().decode(medusa) }.isSuccess)
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
        // POST 的 body 参与签名：x-ss-stub 是 body 的 MD5。
        assertNotNull(legacy.header("x-ss-stub"))
        // 大写十六进制（设备注册那条用的也是大写）：小写的那一份服务端按没给处理。
        assertTrue(legacy.header("x-ss-stub")!!.all { it.isDigit() || it in 'A'..'F' })
        assertNotNull(legacy.body)
    }

    /** 每一轮取址都要重新签名：同一份 medusa 重放第二次还是会被拒。 */
    @Test fun eachAddressRoundReSignsTheRequest() {
        val tabMedusa = mutableListOf<String?>()
        val repository = repository { request ->
            if (request.url.encodedPath == tabPath) { tabMedusa += request.header("x-medusa"); tabPayload("123") } else "{}"
        }
        repository.legacyStreamLadder("123", fresh = true)
        repository.legacyStreamLadder("123", fresh = true)
        assertEquals(2, tabMedusa.size)
        assertNotEquals(tabMedusa[0], tabMedusa[1])
    }

    private fun repositoryWithRegistrationCookies(respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(respond(request).toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build(),
        System::currentTimeMillis,
        // 这一份注册结果带着平台下发的 store-region 那对 Cookie，播放请求要照着它写头。
        PlaybackDeviceSession { DeviceRegister.Registered("333", "444", cookies = mapOf(
            "install_id" to "555", "ttreq" to "1abc",
            "store-region" to "cn-sd", "store-region-src" to "did")) }
    )

    /**
     * 2026-11-23 用 Frida 直接读到了官方取址请求的真实头（见 tmp/hongguo-net/红果播放链路抓包分析.md §9）：
     * 它带 `x-reading-request`，值的前半就是本次请求的 `_rticket`；还带 `x-tt-store-region(-src)`，
     * 值就是设备注册下发的那对 Cookie（实测省级 `cn-sd`，不是写死的 `cn`）。
     * 这里锁住这两条，免得以后又被当作可省的杂头删掉。
     */
    @Test fun playbackRequestCarriesTheReadingAndRegionHeaders() {
        val requests = mutableListOf<Request>()
        val repository = repositoryWithRegistrationCookies { request ->
            requests += request
            if (request.url.encodedPath == tabPath) tabPayload("123") else "{}"
        }
        repository.legacyStreamLadder("123")
        val tab = requests.single { it.url.encodedPath == tabPath }
        val ticket = tab.url.queryParameter("_rticket")
        assertNotNull(ticket)
        val reading = tab.header("x-reading-request")
        assertNotNull(reading)
        assertEquals(ticket, reading!!.substringBefore('-'))
        assertEquals("cn-sd", tab.header("x-tt-store-region"))
        assertEquals("did", tab.header("x-tt-store-region-src"))
    }
}
