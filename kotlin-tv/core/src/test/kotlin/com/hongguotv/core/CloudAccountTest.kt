package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudAccountTest {
    private val credentials=AccountCredentials("sessionid=test-only", "3012345678901234567", "3012345678901234568")
    private fun http(respond: (Request)->String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respond(chain.request()).toResponseBody()).build()
    }.build()
    /** 登录流程要看 `Set-Cookie`，所以这一组桩额外带响应头。 */
    private fun httpCookies(respond: (Request)->Pair<String,List<String>>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (body,cookies)=respond(chain.request())
        val builder=Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody())
        cookies.forEach { builder.addHeader("Set-Cookie",it) }
        builder.build()
    }.build()

    @Test fun accountSessionRoundTripsWithoutExposingCredentialsInToString() {
        assertEquals(credentials,AccountCredentials.fromJson(credentials.toJson()))
        assertFalse(credentials.toString().contains("test-only"))
    }
    @Test fun historyKeepsExactPositionAndNormalizesTimestamp() {
        val rows=JSONArray("""[{"series_id":"123","vid":"456","vid_index":3,"current_play_time":43210,"current_total_time":90000,"last_watch_time":1792500000}]""")
        val result=CloudAccountClient.parseProgress(rows).single()
        assertEquals(2,result.episodeIndex); assertEquals(43210L,result.position)
        assertEquals(90000L,result.duration); assertEquals(1792500000000L,result.updatedAt)
    }
    @Test fun accountProfileRejectsAnonymousFallbackInsteadOfPretendingLoginSucceeded() {
        val client=CloudAccountClient(http { """{"code":0,"data":{"user_name":"用户12345678","has_set_username":false}}""" })
        assertThrows(IOException::class.java) { client.profile(credentials) }
    }
    @Test fun emptyResponseIsAnAccountError() {
        val client=CloudAccountClient(http { "" })
        val failure=assertThrows(IOException::class.java) { client.profile(credentials) }
        assertTrue(failure.message.orEmpty().contains("空响应"))
    }
    @Test fun historyLoadsMetadataWithoutLosingPlaybackPosition() {
        val client=CloudAccountClient(http { request ->
            assertEquals("sessionid=test-only",request.header("Cookie"))
            assertEquals(credentials.deviceId,request.url.queryParameter("device_id"))
            if(request.url.encodedPath.contains("read_progress"))
                """{"code":0,"video_data":[{"series_id":"123","vid":"456","vid_index":2,"current_play_time":12000}]}"""
            else """{"code":0,"data":[{"book_id":"123","book_name":"云端测试剧"}]}"""
        })
        val row=client.history(credentials).single()
        assertEquals("云端测试剧",row.series.title); assertEquals(12000L,row.position)
    }
    @Test fun uploadingProgressUsesCurrentSessionAndOneBasedEpisodeIndex() {
        var calls=0
        val client=CloudAccountClient(http { request ->
            calls++
            assertEquals("api5-normal-sinfonlinea.fqnovel.com",request.url.host)
            assertEquals("sessionid=test-only",request.header("Cookie"))
            assertEquals(credentials.deviceId,request.url.queryParameter("device_id"))
            assertEquals("/reading/bookapi/read_progress/upload/v1/",request.url.encodedPath)
            val buffer=okio.Buffer(); request.body!!.writeTo(buffer)
            val body=JSONObject(buffer.readUtf8()); val book=body.getJSONArray("books").getJSONObject(0)
            assertEquals(1,body.getInt("write_mode")); assertEquals(3,book.getInt("vid_index"))
            assertEquals(43210L,book.getLong("current_play_time")); assertEquals("456",book.getString("item_id"))
            """{"code":0}"""
        })
        client.upload(credentials,CloudProgress(Series("123","测试"),"456",2,43210,90000,1792500000000),8)
        assertEquals(1,calls)
    }
    @Test fun rejectedUploadNeverReportsSuccess() {
        val client=CloudAccountClient(http { """{"code":100103}""" })
        assertThrows(IOException::class.java) { client.upload(credentials,CloudProgress(Series("123","测试"),"456",0,0,0,1),1) }
    }
    @Test fun phoneLoginSubmitsTheCodeToSmsLoginAndStopsOnAnIncorrectCode() {
        var calls=0; var path=""
        val login=PhoneLogin(http { request -> calls++; path=request.url.encodedPath; """{"message":"error","data":{"error_code":1102,"description":"验证码错误"}}""" })
        login.deviceId=credentials.deviceId; login.installId=credentials.installId; login.mobile="13800138000"
        val failure=assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        // 实测：只有 sms_login 能把真实验证码换成 sessionid，validate_code 永远回「会话过期」。
        assertEquals(PhoneLogin.SMS_LOGIN,path); assertEquals(1,calls); assertTrue(login.trace.isEmpty())
        assertTrue("验证码错了允许重新输入",failure.canRetryCode)
    }
    @Test fun rejectedLoginSessionKeepsThePlatformsFirstReplyAndInvalidatesTheSession() {
        var calls=0
        val login=PhoneLogin(http { calls++; """{"message":"error","data":{"error_code":1,"description":"会话过期，请重新登录"}}""" })
        login.deviceId=credentials.deviceId; login.installId=credentials.installId
        login.cookies["temporary"]="test-session"; login.ticket="test-ticket"
        val first=assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertEquals(1,first.errorCode); assertFalse(first.canRetryCode); assertTrue("会话作废后要重新发码",first.needNewCode)
        // 只发一次请求：同一个 ticket 被反复校验会触发 1203（错误次数过多），把平台的第一手答复盖住。
        assertEquals(1,calls)
        assertTrue(login.cookies.isEmpty()); assertEquals("",login.ticket)
        assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertEquals(1,calls)
    }
    /** 诊断包：失败时要说清平台到底给了哪些会话材料——只列名字，任何值都不出现。 */
    @Test fun rejectedLoginSessionListsTheSessionMaterialsWithoutExposingTheirValues() {
        val login=PhoneLogin(OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val body=when {
                request.url.encodedPath.contains("device_register") -> """{"device_id":"3012345678901234567","install_id":"3012345678901234568"}"""
                request.url.encodedPath.contains("send_code") -> """{"message":"success","data":{"mobile":"+86******0000","mobile_ticket":"test-ticket","retry_time":60}}"""
                else -> """{"message":"error","data":{"error_code":1,"description":"会话过期，请重新登录"}}"""
            }
            val reply=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody())
            if(request.url.encodedPath.contains("send_code")) reply.header("Set-Cookie","verify_device_cookie=test-cookie; Path=/")
            reply.build()
        }.build())
        login.send("13800138000")
        assertEquals("test-ticket",login.ticket)
        val failure=assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        val note=failure.diagnostics.orEmpty()
        assertTrue(note.contains("验证票据：已取到"))
        assertTrue(note.contains("mobile_ticket")); assertTrue(note.contains("verify_device_cookie"))
        assertTrue(note.contains("error_code")); assertTrue(note.contains("description"))
        // 只有字段名与 Cookie 名：值、手机号、验证码一律不出现在诊断文本里。
        assertFalse(note.contains("test-ticket")); assertFalse(note.contains("test-cookie"))
        assertFalse(note.contains("13800138000")); assertFalse(note.contains("123456"))
    }
    /** 票据字段缺失时不再静默：请求里确实少了它，这一点必须被说出来。 */
    @Test fun missingVerificationTicketIsReportedInsteadOfBeingSilentlyDroppedFromTheRequest() {
        var sentTicket:String?=null
        val login=PhoneLogin(http { request -> when {
            request.url.encodedPath.contains("device_register") -> """{"device_id":"3012345678901234567","install_id":"3012345678901234568"}"""
            request.url.encodedPath.contains("send_code") -> """{"message":"success","data":{"mobile":"+86******0000","retry_time":60}}"""
            else -> { sentTicket=formOf(request).formValue("mobile_ticket"); """{"message":"error","data":{"error_code":1,"description":"会话过期，请重新登录"}}""" }
        } })
        login.send("13800138000")
        assertEquals("",login.ticket)
        val failure=assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertNull("空票据被 filterValues 静默丢掉，请求体里没有它",sentTicket)
        assertTrue(failure.diagnostics.orEmpty().contains("验证票据：没取到"))
    }
    /** 实测：真实验证码正确时平台常回 2046，并在 verify_ways 里写清「发什么内容、到哪个号码」。 */
    @Test fun riskControlTurnsIntoTheUpstreamSmsInstructionTheScreenHasToShow() {
        val login=PhoneLogin(http { """{"message":"error","data":{"error_code":2046,"description":"为保证账号安全，暂不支持此操作","mobile":"185******71","encrypt_uid":"opaque","biz_params":{"sms_code_key":"k"},"event_params":{"verify_scene":"sms_login","verify_reason":"ato"},"verify_ways":[{"channel_mobile":"9515211003","mobile":"185******71","sms_content":"YZ","verify_way":"mobile_up_sms_verify"},{"platform_screen_name":"**海","verify_way":"oauth_douyin_verify"}]}}""" })
        login.deviceId=credentials.deviceId; login.installId=credentials.installId; login.mobile="13800138000"; login.ticket="test-ticket"
        val failure=assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        val up=failure.upSms
        assertNotNull("平台给了 verify_ways，界面必须拿到它",up)
        assertEquals("9515211003",up!!.channelMobile); assertEquals("YZ",up.smsContent); assertEquals("185******71",up.mobile)
        assertTrue("已经给出上行短信方式，不再列其它方式",up.alternatives.isEmpty())
        assertEquals(up,login.pendingVerification())
    }
    /** 平台只给了电视做不到的方式（抖音授权）时不硬编一条上行短信出来。 */
    @Test fun riskControlWithoutAnUpstreamSmsOptionDoesNotInventOne() {
        val login=PhoneLogin(http { """{"message":"error","data":{"error_code":2046,"description":"为保证账号安全，暂不支持此操作","verify_ways":[{"verify_way":"oauth_douyin_verify"}]}}""" })
        login.deviceId=credentials.deviceId; login.installId=credentials.installId; login.mobile="13800138000"; login.ticket="test-ticket"
        val failure=assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertNull(failure.upSms); assertNull(login.pendingVerification())
    }
    /** 实测链路：没收到短信时回 1045，收到后回 ticket；带票重新发码再校验才签发 sessionid。 */
    @Test fun upstreamSmsVerificationPollsThenCompletesTheLoginWithTheTicket() {
        val paths=mutableListOf<String>(); var upstream=0
        val login=PhoneLogin(httpCookies { request ->
            val path=request.url.encodedPath; paths+=path
            when {
                path==PhoneLogin.UPSMS_VERIFY && ++upstream==1 -> response("""{"message":"error","data":{"error_code":1045,"description":"认证失败"}}""")
                path==PhoneLogin.UPSMS_VERIFY -> response("""{"message":"success","data":{"ticket":"verify-ticket","registered":false}}""")
                path==PhoneLogin.SEND_CODE -> response("""{"message":"success","data":{"mobile":"185******71","mobile_ticket":"second-ticket","retry_time":60}}""")
                path==PhoneLogin.SMS_LOGIN && !formOf(request).formValue("verify_ticket").isNullOrEmpty() ->
                    response("""{"message":"success","data":{"user_id":"42","screen_name":"测试"}}""","sessionid=test-session; Path=/")
                path==PhoneLogin.SMS_LOGIN -> response("""{"message":"error","data":{"error_code":2046,"description":"为保证账号安全，暂不支持此操作","mobile":"185******71","encrypt_uid":"opaque","biz_params":{"sms_code_key":"k"},"event_params":{"verify_scene":"sms_login","verify_reason":"ato"},"verify_ways":[{"channel_mobile":"9515211003","mobile":"185******71","sms_content":"YZ","verify_way":"mobile_up_sms_verify"}]}}""")
                else -> response("""{"message":"error","data":{"error_code":1,"description":"不该再请求这个端点"}}""")
            }
        })
        login.deviceId=credentials.deviceId; login.installId=credentials.installId; login.mobile="13800138000"; login.ticket="first-ticket"
        val ask=assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertNotNull(ask.upSms)
        login.confirmUpSms()                       // 第一次 1045，第二次拿到 ticket
        val sent=login.resend()
        assertEquals("185******71",sent.maskedMobile)
        // 二次验证之后必须沿用同一个会话，平台才认那张票：不重新注册设备。
        assertFalse(paths.any { it.contains("device_register") })
        val account=login.verify("654321")
        assertTrue(account.cookie.contains("sessionid"))
        assertEquals(credentials.deviceId,account.deviceId)
    }
    /** 同一张验证码被 2046 消耗后再提交回 1203：界面要重新发一张，而不是让用户再输一遍。 */
    @Test fun expiredCodeAsksForANewOneInsteadOfAnotherAttempt() {
        val login=PhoneLogin(http { """{"message":"error","data":{"error_code":1203,"description":"错误次数过多或验证码过期，请稍后重试","mobile_ticket":"next-ticket"}}""" })
        login.deviceId=credentials.deviceId; login.installId=credentials.installId; login.mobile="13800138000"; login.ticket="used-ticket"
        val failure=assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertEquals(1203,failure.errorCode); assertTrue(failure.needNewCode); assertFalse(failure.canRetryCode)
    }
    private fun response(body: String,vararg cookies: String)=Pair(body,cookies.toList())
    private fun formOf(request: Request): String {
        val body=request.body ?: return ""
        val buffer=okio.Buffer(); body.writeTo(buffer); return buffer.readUtf8()
    }
    private fun String.formValue(name: String): String? = split("&").firstOrNull { it.substringBefore("=")==name }?.substringAfter("=")
}
