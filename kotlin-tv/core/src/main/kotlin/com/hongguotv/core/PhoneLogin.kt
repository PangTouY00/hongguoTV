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
 * 实测跑通的完整链路（2026-10-26，真实号码，真实短信）：
 *
 * 1. [send]：先匿名注册一台**新设备**（[DeviceRegister]），再用这台设备的身份向
 *    `/passport/mobile/send_code/v1/` 发码（`type=24`），响应里的 `mobile_ticket` 是后续校验的会话票据。
 * 2. [verify]：`POST /passport/mobile/sms_login/` 带上 `mobile_ticket` 与验证码。**票据必须带**——
 *    不带时平台直接回 `1203`；这条通道不需要 CSRF 头。
 * 3. 验证码正确时平台常回 `error_code 2046`（账号安全风控），并在 `data.verify_ways` 里给出可用的
 *    二次验证方式。其中 `mobile_up_sms_verify` 要求用户**用这台手机号向指定号码发一条内容固定的短信**，
 *    号码与内容都在响应里；[confirmUpSms] 按官方验证页的节奏轮询 `POST /passport/upsms/verify/` 确认，
 *    成功后平台回一张 `ticket`。另一种 `oauth_douyin_verify` 需要抖音授权，电视端不做。
 * 4. 拿到 `verify_ticket` 之后先带票重放**同一张验证码**：`2046` 与 `upsms/verify` 的响应里都另给了一张
 *    `mobile_ticket`，而平台在身份验证期间留着那张码（`biz_params.sms_code_key`），带票重放常常直接签发 `sessionid`，
 *    不必再发一张。
 * 5. 那张码确实作废时 [resend] **沿用同一个会话**重新发码，[verify] 再带上新票据与 `verify_ticket` 完成登录。
 *    发码有冷却：成功发码时平台回 `retry_time`（实测 60 秒），没到点再发回 `1206 验证码发送太频繁`；
 *    [resend] 先等满冷却再发，被 `1206` 拒绝时再等一轮重试一次，要等的秒数通过 [onSendWait] 告诉界面。
 *
 * 实测边界：`type=1` 在 `sms_login` 上被平台拒绝（`3052` 该类型未开通），登录固定用 `24`；
 * 上行短信还没到达时 `upsms/verify` 回 `1045 认证失败`，这是轮询的正常中间态；
 * 同一张验证码被 `2046` 消耗后**不带票**再提交会回 `1203`；同一号码两次发码之间的冷却由 `retry_time` 给出，
 * 而 `1206` 的响应里只有 `description` 与一张新的 `mobile_ticket`，不再回冷却值。
 * 官网通道（[WebLogin]）的手机号发码则被滑块风控（`1105`）挡住。
 *
 * 一个实例对应一次登录；重新发码要么沿用会话（二次验证之后），要么换一台新设备并作废之前的验证码。
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

    /** 二次验证材料：只服务于本次登录，不写日志、不进诊断包。 */
    private var encryptUid=""
    private var bizParams=""
    private var verifyScene=""
    private var verifyReason=""
    private var verifyTicket=""
    private var pendingUpSms: UpSms?=null

    /** 上一次成功发码给的冷却秒数与发码时刻，用来不在冷却里抢着发码。 */
    private var cooldownSeconds=0
    private var lastSendAtMs=0L

    /** 界面可订阅：重新发码前还要等多少秒（发码冷却）。不订阅也照样能走完，只是界面不知道在等。 */
    var onSendWait: ((Int)->Unit)?=null

    /** 时间与等待的替身：真机就是系统时钟和 [Thread.sleep]，测试里换成不真等的记录器。 */
    internal var clock: ()->Long=System::currentTimeMillis
    internal var sleeper: (Long)->Unit={ millis -> Thread.sleep(millis) }

    /**
     * 平台拒绝时的可读原因，界面直接显示 [message]；[diagnostics] 是诊断包额外展示的会话材料清单；
     * [upSms] 非空时平台要求上行短信二次验证，界面按它显示「用哪台手机、发什么内容、到哪个号码」；
     * [needNewCode] 为真时这张验证码已经作废，界面要重新发一张而不是让用户再输一遍；
     * [retryAfterSeconds] 大于零时这是限频类的暂时拒绝，等这么久再发就有效，会话与身份验证票据都保留。
     */
    class Failure(message: String,val errorCode: Int?=null,val canRetryCode: Boolean=false,val diagnostics: String?=null,val upSms: UpSms?=null,val needNewCode: Boolean=false,val retryAfterSeconds: Int=0): Exception(message)
    /** [maskedMobile] 是平台回显的打码号码（如 `+886******23`）。 */
    data class Sent(val maskedMobile: String,val retrySeconds: Int)
    /**
     * 平台要求的上行短信验证：用 [mobile] 向 [channelMobile] 发送内容正好为 [smsContent] 的短信。
     * [alternatives] 是平台同时给出的其它验证方式（如抖音验证），界面只做说明，不代做。
     */
    data class UpSms(val mobile: String,val channelMobile: String,val smsContent: String,val alternatives: List<String>)

    /**
     * 发一张登录验证码。[keepSession] 为真时**沿用当前设备与 Cookie**：上行短信验证通过之后必须用
     * 同一个会话重新发码，平台才认那张 `verify_ticket`（实测）。全新登录才注册新设备。
     */
    fun send(mobile: String,keepSession: Boolean=false): Sent {
        if(!keepSession) {
            val registered=CloudAccountClient(http).registerDevice() ?: throw Failure("设备注册失败，请检查网络后重试")
            deviceId=registered.deviceId; installId=registered.installId
            cookies.clear(); cookies.putAll(registered.cookies)
            expired=false
            encryptUid=""; bizParams=""; verifyScene=""; verifyReason=""; verifyTicket=""; pendingUpSms=null
        }
        this.mobile=mobile
        val reply=call(SEND_CODE,mapOf("mobile" to mobile,"type" to type),form=false)
        sentFields=names(reply)
        val data=reply.optJSONObject("data") ?: JSONObject()
        sentDataFields=names(data)
        // 风控要求滑块验证：电视上没法做，也不去绕它。
        if(data.has("verify_center_decision_conf")) throw Failure("红果要求完成滑块验证，请改用扫码登录",1105)
        if(reply.optString("message")!="success") {
            val rejected=data.optInt("error_code",0)
            // 同一号码两次发码之间有冷却（实测 retry_time 秒）。1206 的响应里不再回冷却值，只能沿用上一张码给的那个。
            if(rejected==SEND_TOO_FREQUENT) throw Failure(describe(data) ?: "红果要求稍等一会儿才能再发验证码",SEND_TOO_FREQUENT,canRetryCode=true,retryAfterSeconds=if(cooldownSeconds>0) cooldownSeconds else DEFAULT_COOLDOWN_SECONDS)
            throw Failure(describe(data) ?: "验证码没发出去")
        }
        ticket=data.optString("mobile_ticket")
        // 冷却按号码算，换设备重登也不会更快，所以换会话时保留它。
        cooldownSeconds=data.optInt("retry_time",if(cooldownSeconds>0) cooldownSeconds else DEFAULT_COOLDOWN_SECONDS)
        lastSendAtMs=clock()
        return Sent(data.optString("mobile").ifBlank { mobile },cooldownSeconds)
    }

    /**
     * 提交验证码。成功时平台在 `Set-Cookie` 里下发 `sessionid`；要求二次验证时抛 [Failure] 并带上
     * [Failure.upSms]，界面据此引导用户发那条短信，之后 [confirmUpSms] → [resend] → 再 [verify]。
     *
     * 只发一次请求，失败时附上**会话材料清单**（字段名与 Cookie 名，没有任何值），
     * 让平台的第一手答复原样落到界面上。
     */
    fun verify(code: String): AccountCredentials {
        if(expired) throw Failure("本次登录会话已失效，不能继续使用此会话提交验证码",1)
        check(deviceId.isNotEmpty()) { "请先发送验证码" }
        val params=linkedMapOf("mobile" to mobile,"code" to code,"type" to type,"mobile_ticket" to ticket)
        if(verifyTicket.isNotBlank()) params["verify_ticket"]=verifyTicket
        val reply=call(SMS_LOGIN,params,form=true)
        if(cookies.containsKey("sessionid")) return credentials()
        verifyFields=names(reply)
        val data=reply.optJSONObject("data") ?: JSONObject()
        verifyDataFields=names(data)
        val errorCode=data.optInt("error_code",0)
        val described=describe(data)
        if(errorCode==MFA_REQUIRED) {
            val up=upSmsFrom(data)
            // 平台在 2046 里另给一张 mobile_ticket 并留着那张码（biz_params.sms_code_key）：后续一律用新票据。
            val renewed=data.optString("mobile_ticket")
            if(renewed.isNotBlank()) ticket=renewed
            if(up!=null) throw Failure("红果要求完成身份验证，请按界面提示发送一条短信",MFA_REQUIRED,upSms=up)
            throw Failure(described ?: "红果要求身份验证，但没有给出可用的验证方式",MFA_REQUIRED)
        }
        // 验证码错了重新输入即可；要求滑块的只能改用扫码；过期或次数用尽的必须重新发一张。
        if(data.has("verify_center_decision_conf")) throw Failure("红果要求完成滑块验证，请改用扫码登录",1105)
        if(errorCode==1102) throw Failure(described ?: "验证码不正确，请重新输入",1102,canRetryCode=true)
        if(errorCode==1105) throw Failure("红果要求完成滑块验证，请改用扫码登录",1105)
        if(errorCode==CODE_EXPIRED) throw Failure(described ?: "这张验证码已经过期或尝试次数过多",CODE_EXPIRED,needNewCode=true)
        if(!cookies.containsKey("sessionid")) {
            val note=sessionMaterials()   // 必须在清空 cookies / ticket 之前取
            if(errorCode==1) { expired=true; cookies.clear(); ticket="" }
            throw Failure(described ?: "平台没有为这次手机号验证签发登录会话（错误码 ${if(errorCode==0) "无" else errorCode}）",errorCode.takeIf { it!=0 },diagnostics=note,needNewCode=true)
        }
        return credentials()
    }

    /**
     * 确认用户已经发出那条上行短信：按官方验证页的节奏轮询 `/passport/upsms/verify/`
     * （最多 [UPSMS_ATTEMPTS] 次、每次间隔 [UPSMS_INTERVAL_MS] 毫秒），成功时记下平台回的 `ticket`。
     *
     * 短信没到达时平台回 `1045 认证失败`，这是轮询的正常中间态；轮询用尽才抛 [Failure]，
     * 且 [Failure.canRetryCode] 为真，界面可以再试一次。
     */
    fun confirmUpSms() {
        check(pendingUpSms!=null) { "当前没有待确认的身份验证，请重新发送验证码" }
        check(encryptUid.isNotBlank()) { "平台没有给出身份验证材料，请重新发送验证码" }
        var described:String?=null
        for(attempt in 1..UPSMS_ATTEMPTS) {
            if(attempt>1) Thread.sleep(UPSMS_INTERVAL_MS)
            val reply=call(UPSMS_VERIFY,upSmsParams(),form=true)
            val data=reply.optJSONObject("data") ?: JSONObject()
            val issued=data.optString("ticket")
            if(reply.optString("message")=="success" && issued.isNotBlank()) {
                verifyTicket=issued
                // 身份验证通过时平台常同时换一张发码票据，接住它，后面无论重放还是重发都用新的。
                val renewed=data.optString("mobile_ticket")
                if(renewed.isNotBlank()) ticket=renewed
                return
            }
            described=describe(data) ?: described
        }
        throw Failure(described ?: "还没收到这条短信。请核对短信内容只有一个词、号码完全一致，再试一次",UP_SMS_PENDING,canRetryCode=true)
    }

    /**
     * 再发一张验证码：二次验证之后**沿用同一会话**（平台才认那张票），否则按全新登录处理。
     * 先等满平台给的发码冷却再发；被 `1206` 拒绝时再等一轮、只重试一次，仍不行才把限频的答复交给界面。
     */
    fun resend(): Sent {
        val keepSession=pendingUpSms!=null
        var retries=0
        while(true) {
            waitOutCooldown()
            try { return send(mobile,keepSession=keepSession) } catch(refused: Failure) {
                if(refused.errorCode!=SEND_TOO_FREQUENT || retries>=SEND_RETRY_ATTEMPTS) throw refused
                retries++
                waitBeforeSend(refused.retryAfterSeconds.takeIf { it>0 } ?: cooldownSeconds)
            }
        }
    }

    /** 上一次发码的冷却还没过就先等够，省掉一次注定被 `1206` 拒绝的请求。 */
    private fun waitOutCooldown() {
        if(lastSendAtMs<=0L || cooldownSeconds<=0) return
        val remaining=cooldownSeconds-(clock()-lastSendAtMs)/1000
        if(remaining<=0) return
        waitBeforeSend(remaining.toInt())
    }

    private fun waitBeforeSend(seconds: Int) {
        val wait=seconds.coerceIn(1,MAX_SEND_WAIT_SECONDS)
        onSendWait?.invoke(wait)
        sleeper(wait*1000L)
    }

    /** 身份验证已通过、票已拿到：界面可以先带票重放同一张验证码，省掉一次发码。 */
    fun verificationPassed(): Boolean = verifyTicket.isNotBlank()

    /** 界面据此决定是「继续同一次登录」还是「重新开始」。 */
    fun pendingVerification(): UpSms? = pendingUpSms

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

    private fun credentials(): AccountCredentials {
        val header=cookies.entries.joinToString("; ") { (name,value) -> "$name=$value" }
        return AccountCredentialsParser.parseHeader(header)
            .copy(deviceId=deviceId,installId=installId,device=mapOf("device_id" to deviceId,"iid" to installId))
    }

    private fun describe(data: JSONObject): String? =
        data.optString("description").takeIf { it.isNotBlank() }?.let { "$it（错误码 ${data.optInt("error_code")}）" }

    /**
     * 从 `2046` 的响应里取出上行短信验证方式，并记下轮询它所需的材料。
     * 平台没给出 `mobile_up_sms_verify` 时返回 null（例如只给了抖音验证），界面改用扫码。
     */
    private fun upSmsFrom(data: JSONObject): UpSms? {
        val ways=data.optJSONArray("verify_ways") ?: return null
        val alternatives=mutableListOf<String>()
        for(index in 0 until ways.length()) {
            val way=ways.optJSONObject(index) ?: continue
            if(way.optString("verify_way")==UP_SMS_WAY) {
                val channel=way.optString("channel_mobile"); val content=way.optString("sms_content")
                if(channel.isBlank() || content.isBlank()) continue
                encryptUid=data.optString("encrypt_uid")
                bizParams=data.optJSONObject("biz_params")?.toString().orEmpty()
                val event=data.optJSONObject("event_params") ?: JSONObject()
                verifyScene=event.optString("verify_scene"); verifyReason=event.optString("verify_reason")
                pendingUpSms=UpSms(data.optString("mobile").ifBlank { mobile },channel,content,alternatives)
                return pendingUpSms
            }
            alternatives+=way.optString("verify_way")
        }
        return null
    }

    /** 照搬官方验证页提交给 `upsms/verify` 的那组材料；空值由 [call] 统一丢掉。 */
    private fun upSmsParams(): Map<String,String> = linkedMapOf(
        "encrypt_uid" to encryptUid,
        "verify_ticket" to "",
        "biz_params" to bizParams,
        "verify_scene" to verifyScene,
        "verify_reason" to verifyReason,
    )

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
        internal const val SEND_CODE="/passport/mobile/send_code/v1/"
        /** 实测唯一能把真实验证码换成 `sessionid` 的端点；`validate_code` 只会回「会话过期」。 */
        internal const val SMS_LOGIN="/passport/mobile/sms_login/"
        internal const val UPSMS_VERIFY="/passport/upsms/verify/"
        /** login-lab 实测：24 = 登录验证码；1 / 3 是绑定 / 换绑手机号，`type=1` 在 `sms_login` 上回 `3052`。 */
        const val LOGIN_CODE_TYPE="24"
        /** 实测错误码：2046 = 要求二次验证；1045 = 上行短信还没收到；1203 = 这张码过期或试得太多。 */
        internal const val MFA_REQUIRED=2046
        internal const val UP_SMS_PENDING=1045
        internal const val CODE_EXPIRED=1203
        /** 实测错误码 1206 = 同一号码发码太频繁；冷却秒数只在成功发码时由 `retry_time` 给出。 */
        internal const val SEND_TOO_FREQUENT=1206
        /** 平台没回 `retry_time` 时按实测的 60 秒算；自动等待的上限；被 1206 拒绝后的自动重试次数。 */
        internal const val DEFAULT_COOLDOWN_SECONDS=60
        internal const val MAX_SEND_WAIT_SECONDS=120
        internal const val SEND_RETRY_ATTEMPTS=1
        internal const val UP_SMS_WAY="mobile_up_sms_verify"
        /** 官方验证页的轮询节奏：最多 4 次、每次间隔 2 秒。 */
        internal const val UPSMS_ATTEMPTS=4
        internal const val UPSMS_INTERVAL_MS=2000L

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
