// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/** 播放设备注册失败。和「接口返回空响应」「HTTP 错误」区分开，界面单独提示。 */
class PlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

/**
 * 匿名播放身份：**进程内注册一次，首页试播与正式播放共用**。
 *
 * 播放接口只认「服务端注册过的 `device_id` + `iid`」这一对身份（见 [DeviceRegister] 的记录）：
 * 没注册过的身份它回 HTTP 200 + 0 字节，看起来和「网络没问题但就是播不了」一模一样。
 *
 * 除了这对 id，注册响应里的 `Set-Cookie`（`install_id` / `ttreq` / `store-region` /
 * `store-region-src`）也要跟着播放请求发出去：播放域名是 `api5-normal-sinfonlineb.fqnovel.com`，
 * 注册域名是 `log5-applog-lf.fqnovel.com`，这些 Cookie 没有跨子域可见的 `Domain` 属性，
 * 交给 CookieJar 按域匹配发不出去，只能显式取白名单自己拼 `Cookie` 头。
 *
 * 注册是匿名的：不带账号 Cookie、不登录、不发短信；身份只留在内存里，进程结束即消失，
 * 下一次启动重新注册。注册失败**不**退回随机身份（随机身份必然空响应），而是抛
 * [PlaybackDeviceRegistrationException]，由界面提示后重试。
 */
class PlaybackDeviceSession(
    private val register: (OkHttpClient) -> DeviceRegister.Registered? = { http ->
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (key, value) ->
            request.header(key, value)
        }
        request.post(bodyOf(body, "application/octet-stream"))
        // 注册请求本身不带任何 Cookie；账号 Cookie 更不能转发。取消跟随调用方的请求作用域。
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        RequestScope.execute(transport.newCall(request.build())) { response ->
            DeviceRegister.parse(cloudResponse(response), response.headers("Set-Cookie"))
        }
    }
) {
    companion object {
        /** 整个应用进程共用的播放身份。 */
        val shared = PlaybackDeviceSession()
        /** 注册响应里允许回写到播放请求的 Cookie；账号会话类 Cookie 一律不转发。 */
        private val DEVICE_COOKIES = setOf("install_id", "ttreq", "store-region", "store-region-src")
        private val VALID_ID = Regex("[1-9][0-9]{0,29}")
    }

    internal data class Profile(val values: Map<String, String>, val cookie: String)

    /** 播放请求里的 `cdid`：同一进程内稳定，和注册身份一起发。 */
    private val cdid = UUID.randomUUID().toString()
    @Volatile private var ready: Profile? = null

    /**
     * 已经拿到的身份；没拿到就是 null。界面诊断只读它，**不会顺手发一次注册请求**。
     */
    internal fun readyProfile(): Profile? = ready

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let { return it }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val device = try {
            register(http) ?: throw PlaybackDeviceRegistrationException()
        } catch (error: InterruptedIOException) {
            throw error
        } catch (error: IOException) {
            throw PlaybackDeviceRegistrationException(error)
        }
        // 服务端拒掉注册时会回 device_id=0 / install_id=0；这类身份拿去播必然是空响应。
        if (!VALID_ID.matches(device.deviceId) || !VALID_ID.matches(device.installId)) {
            throw PlaybackDeviceRegistrationException()
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return Profile(
            VendorConstants.device + mapOf(
                "device_id" to device.deviceId,
                "iid" to device.installId,
                "cdid" to cdid,
            ),
            device.cookies
                .filterKeys { it in DEVICE_COOKIES }
                .entries
                .joinToString("; ") { (name, value) -> "$name=$value" },
        ).also { ready = it }
    }
}
