// Adapted 2026-10-21 from charlie901208/HGTV feat/phone-login (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 手机号 + 验证码登录（大陆与境外号码都收）。
 *
 * * [send]：先匿名注册一台**新设备**（[DeviceRegister]），再用这台设备的身份发码。发码这一步实测可用。
 * * [verify]：用**同一台设备**验码，平台在 `Set-Cookie` 里下发 `sessionid`。
 *   换到的凭据带上这台设备的 `device_id` / `iid`（[AccountCredentials.device]），
 *   之后账号接口的签名都用它，会话与设备保持一致。
 *
 * 实测边界（2026-10-26 第二轮）：`aid=8662` 这条 passport 手机号流程能发出短信；只发**一次**
 * `validate_code` 请求时，平台的第一手答复是 `error_code 1`（`会话过期，请重新登录`）——不是 `1105`
 * 滑块，也没有进到比对验证码那一步。此前观察到的 `1203`（错误次数过多）是四个端点连发四次的次生现象。
 * 现在待判定的是两件事之一：这条通道本身要求调用方已持有登录会话，或者我们没把会话材料（验证票据 /
 * 设备 Cookie）带过去。[verify] 失败时给出的会话材料清单就是用来判定这一点的。
 * 官网通道（[WebLogin]）的手机号发码则被滑块风控（`1105`）挡住。
 *
 * 一个实例对应一次登录；重新发码会换一台新设备，之前的验证码随之作废。
 */
class PhoneLogin(private val http: OkHttpClient) {
    var deviceId=""
    var installId=""
    var mobile=""
    var ticket=""
    var type=LOGIN_CODE_TYPE
    private var expired=false
    val cookies=linkedMapOf<String,String>()
    /** Compatibility field; authentication responses are never retained here. */
    val trace=mutableListOf<String>()

    /**
     * 诊断包用的**字段名**清单：只记录平台回了哪些字段名和哪些 Cookie 名，
     * 不记录任何值——手机号、验证码、票据、Cookie 值一个都不留。
     */
    internal var sentFields=listOf<String>()
        private set
    internal var sentDataFields=listOf<String>()
        private set
    internal var verifyFields=listOf<String>()
        private set
    internal var verifyDataFields=listOf<String>()
        private set

    /** 平台拒绝时的可读原因，界面直接显示 [message]；[diagnostics] 是诊断包额外展示的会话材料清单。 */
    class Failure(message: String,val errorCode: Int?=null,val canRetryCode: Boolean=false,val diagnostics: String?=null): Exception(message)
    /** [maskedMobile] 是平台回显的打码号码（如 `+886******23`）。 */
    data class Sent(val maskedMobile: String,val retrySeconds: Int)

    fun send(mobile: String): Sent {
        val registered=CloudAccountClient(http).registerDevice() ?: throw Failure("设备注册失败，请检查网络后重试")
        deviceId=registered.deviceId; installId=registered.installId
        cookies.clear(); cookies.putAll(registered.cookies)
        expired=false
        this.mobile=mobile
        val reply=call("/passport/mobile/send_code/v1/",mapOf("mobile" to mobile,"type" to type),form=false)
        sentFields=names(reply)
        val data=reply.optJSONObject("data") ?: JSONObject()
        sentDataFields=names(data)
        // 风控要求滑块验证：电视上没法做，也不去绕它。
        if(data.has("verify_center_decision_conf")) throw Failure("红果要求完成滑块验证，请改用扫码登录",1105)
        if(reply.optString("message")!="success") throw Failure(describe(data) ?: "验证码没发出去")
        ticket=data.optString("mobile_ticket")
        return Sent(data.optString("mobile").ifBlank { mobile },data.optInt("retry_time",60))
    }

    /**
     * 用**同一台设备**验码换取登录态。
     *
     * 诊断包（第二轮）：只发一次请求，并在失败时附上**会话材料清单**——发码响应回了哪些字段、
     * 验证票据有没有取到、校验响应回了哪些字段、这次请求带了哪些 Cookie（全部只有名字，没有值）。
     *
     * 之所以要问这几件事：`ticket` 取不到时是空串，而 [call] 里的 `filterValues { it.isNotBlank() }`
     * 会把空参数**静默丢掉**——请求照发，但少了那张票据。于是「带不带票据」这个变量从来没有被真正
     * 测过，平台回 `error_code 1`（会话过期）既可能是这条通道本身要求已登录会话，也可能只是我们
     * 没把会话材料带过去。这份清单用来在两者之间定论。
     */
    fun verify(code: String): AccountCredentials {
        if(expired) throw Failure("本次登录会话已失效，不能继续使用此会话提交验证码",1)
        check(deviceId.isNotEmpty()) { "请先发送验证码" }
        val params=mapOf("mobile" to mobile,"code" to code,"type" to type,"mobile_ticket" to ticket)
        val codes=mutableListOf<Int>()
        var described:String?=null
        for((path,form) in VERIFY_ATTEMPTS) {
            val reply=call(path,params,form)
            if(cookies.containsKey("sessionid")) break
            verifyFields=names(reply)
            val error=reply.optJSONObject("data") ?: continue
            verifyDataFields=names(error)
            val errorCode=error.optInt("error_code",0)
            if(errorCode==0) continue
            codes+=errorCode
            described=describe(error) ?: described
            // 验证码错了换端点也没有意义，直接让界面重新输入；要求滑块的只能改用扫码。
            if(errorCode==1102) throw Failure(described ?: "验证码不正确，请重新输入",errorCode,true)
            if(errorCode==1105) throw Failure("红果要求完成滑块验证，请改用扫码登录",errorCode)
        }
        if(!cookies.containsKey("sessionid")) {
            val distinct=codes.distinct()
            val note=sessionMaterials()   // 必须在清空 cookies / ticket 之前取
            if(1 in distinct) { expired=true; cookies.clear(); ticket="" }
            val reason=described ?: "平台没有为这次手机号验证签发登录会话（错误码 ${distinct.joinToString("、").ifBlank { "无" }}）"
            // 诊断包：不加「只在官方 App 内可用」这类结论，让平台的第一手答复原样落到界面上。
            throw Failure("$reason。本次只向 validate_code 发了一次请求，以上是平台的第一手答复。",distinct.firstOrNull(),1102 in distinct,note)
        }
        val header=cookies.entries.joinToString("; ") { (name,value) -> "$name=$value" }
        return AccountCredentialsParser.parseHeader(header)
            .copy(deviceId=deviceId,installId=installId,device=mapOf("device_id" to deviceId,"iid" to installId))
    }

    /** 带这台设备的身份签名后发一次 passport 请求；`Set-Cookie` 并进 [cookies]，返回 JSON（非 JSON 时包成 `raw`）。 */
    private fun call(path: String,params: Map<String,String>,form: Boolean): JSONObject {
        val device=LinkedHashMap(VendorConstants.device).apply {
            put("device_id",deviceId); put("iid",installId)
            for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) put(key,"73932")
            put("version_name","7.3.9.32")
        }
        val passport=mapOf("aid" to "8662","app_name" to "novelread","device_platform" to "android")+params.filterValues { it.isNotBlank() }
        val payload=if(form) Signer.encode(passport).toByteArray() else null
        val signed=Signer(device=device).sign(PASSPORT+path,if(form) device else device+passport,payload)
        val request=Request.Builder().url(signed.url)
        signed.headers.forEach { (k,v) -> request.header(k,v) }
        if(cookies.isNotEmpty()) request.header("Cookie",cookies.entries.joinToString("; ") { (name,value) -> "$name=$value" })
        if(payload!=null) request.post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        val reply=http.newCall(request.build()).execute().use { response ->
            response.headers("Set-Cookie").forEach { line ->
                val pair=line.substringBefore(';'); val name=pair.substringBefore('=').trim(); val value=pair.substringAfter('=',"").trim()
                if(name.isNotEmpty() && value.isNotEmpty()) cookies[name]=value
            }
            val text=cloudResponse(response)
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("http",response.code).put("raw",text.take(300)) }
        }
        // Never retain authentication responses or verification codes in diagnostics.
        return reply
    }

    private fun describe(data: JSONObject): String? =
        data.optString("description").takeIf { it.isNotBlank() }?.let { "$it（错误码 ${data.optInt("error_code")}）" }

    /** 会话材料清单：只有字段名与 Cookie 名，没有任何值。 */
    private fun sessionMaterials(): String = buildString {
        append("发码响应字段：")
        append(fieldNames(sentFields,sentDataFields))
        append("\n验证票据：")
        append(if(ticket.isNotBlank()) "已取到" else "没取到")
        append("\n校验响应字段：")
        append(fieldNames(verifyFields,verifyDataFields))
        append("\n携带的 Cookie：")
        append(cookies.keys.sorted().joinToString("、").ifBlank { "无" })
    }

    /** 优先列 `data` 层的字段名；平台没回 `data`（非 JSON 或纯 HTTP 错误）时退化成列顶层字段名。 */
    private fun fieldNames(top: List<String>,data: List<String>): String = when {
        data.isNotEmpty() -> data.sorted().joinToString("、")
        top.isNotEmpty() -> "没有 data 层（顶层字段：${top.sorted().joinToString("、")}）"
        else -> "没有 JSON 响应"
    }

    /** 取 JSON 对象的字段名（用 `keys()` 迭代，不依赖 `keySet()`，框架内置的 org.json 也认）。 */
    private fun names(json: JSONObject): List<String> {
        val out=mutableListOf<String>(); val keys=json.keys()
        while(keys.hasNext()) out+=keys.next()
        return out
    }

    companion object {
        private const val PASSPORT="https://is.snssdk.com"
        /** login-lab 实测：24 = 登录验证码；1 / 3 是绑定 / 换绑手机号。 */
        const val LOGIN_CODE_TYPE="24"
        /**
         * 诊断包：只留 `validate_code` 的 GET 一次请求。
         *
         * 四个端点连发会把同一个 `mobile_ticket` 的校验配额烧光，最后一个错误（1203 错误次数过多）
         * 又盖住第一个真正的错误。这里只发一次，让平台的第一手答复原样落到界面上。
         */
        internal val VERIFY_ATTEMPTS=listOf("/passport/mobile/validate_code/v1/" to false)

        /**
         * 区号 + 本地号码 → 平台认的手机号；不合格时抛 [IllegalArgumentException]，[Throwable.message] 可直接显示。
         *
         * * 大陆（86）：1 开头 11 位，原样发（实测可用）；
         * * 境外：`+区号 号码`（区号后一个空格，去掉本地开头的 0），passport 的惯用写法；
         *   长度按 E.164——区号 1–3 位，区号 + 号码合计不超过 15 位，号码至少 4 位。
         *
         * 号码里的空格、横线等非数字字符一律忽略。
         */
        fun format(countryCode: String,number: String): String {
            val cc=countryCode.filter(Char::isDigit)
            val digits=number.filter(Char::isDigit).removePrefix("0")
            if(cc=="86") {
                require(digits.matches(Regex("1\\d{10}"))) { "大陆手机号是 1 开头的 11 位数字" }
                return digits
            }
            require(cc.matches(Regex("[1-9]\\d{0,2}"))) { "区号是 1–3 位数字" }
            require(digits.length>=4 && cc.length+digits.length<=15) { "号码长度不对：区号加号码最多 15 位" }
            return "+$cc $digits"
        }

        /** 一整串的写法：`13800138000`（大陆），或 `+区号-号码` / `+区号 号码`（如 `+886-0912345678`）。 */
        fun parse(raw: String): String {
            val text=raw.trim()
            if(!text.startsWith("+")) return format("86",text)
            val cc=text.drop(1).takeWhile(Char::isDigit)
            val rest=text.drop(1+cc.length)
            require(rest.firstOrNull()=='-' || rest.firstOrNull()==' ') { "境外号码写成 +区号 号码，如 +886 0912345678" }
            return format(cc,rest)
        }
    }
}
