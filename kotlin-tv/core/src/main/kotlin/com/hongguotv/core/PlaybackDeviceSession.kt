// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlaybackDeviceRegistrationException(cause: Throwable? = null):
    IOException("播放设备注册失败，请稍后重试",cause)

/**
 * 播放用的匿名设备身份。
 *
 * 播放信息接口只认注册下发的 device_id / iid：自己随机编的 ID 会被回成 HTTP 200 空响应，
 * 界面上看起来就是整集播不了。这里注册一次并把身份留在进程里，全屏与首页共用同一份。
 */
class PlaybackDeviceSession(
    private val register: (OkHttpClient) -> DeviceRegister.Registered? = { http ->
        val body=DeviceRegister.payload(VendorConstants.device)
        val request=Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body,VendorConstants.device).forEach { (key,value) -> request.header(key,value) }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        // 注册本身不带账号 Cookie；取消由发起方的 RequestScope 负责。
        val transport=http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        RequestScope.execute(transport.newCall(request.build())) { response ->
            DeviceRegister.parse(cloudResponse(response),response.headers("Set-Cookie"))
        }
    }
) {
    companion object { val shared=PlaybackDeviceSession() }

    internal data class Profile(val values: Map<String,String>,val cookie: String)

    private val cdid=UUID.randomUUID().toString()
    private var ready: Profile?=null

    /** 合并并发注册：第一个成功的身份就是之后所有仓库、所有剧集共用的身份。 */
    @Synchronized internal fun profile(http: OkHttpClient): Profile {
        ready?.let { return it }
        if(Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val device=try {
            register(http) ?: throw PlaybackDeviceRegistrationException()
        } catch (error: InterruptedIOException) { throw error
        } catch (error: IOException) { throw PlaybackDeviceRegistrationException(error) }
        // 平台拒注册时会给 0 或空串；这样的身份拿去请求播放同样只会换回空响应。
        val validId=Regex("[1-9][0-9]{0,29}")
        if(!validId.matches(device.deviceId) || !validId.matches(device.installId)) throw PlaybackDeviceRegistrationException()
        if(Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return Profile(
            VendorConstants.device + mapOf(
                "device_id" to device.deviceId,
                "iid" to device.installId,
                "cdid" to cdid,
            ),
            // 只回写注册下发的那几个 Cookie；账号 Cookie 不能混进匿名播放身份。
            device.cookies
                .filterKeys { it in setOf("install_id","ttreq","store-region","store-region-src") }
                .entries.joinToString("; ") { (name,value) -> "$name=$value" }
        ).also { ready=it }
    }
}
