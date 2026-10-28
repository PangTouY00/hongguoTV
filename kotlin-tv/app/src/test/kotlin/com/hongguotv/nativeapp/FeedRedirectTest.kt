package com.hongguotv.nativeapp

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 这个测试原本没有声明 API，于是跑在 Robolectric 的默认配置上（= targetSdk 36）。
 * OkHttp 3.12 在 SDK_INT >= 28 时会改用 Android 的 NetworkPolicyManager 判断能不能走明文，
 * 而这里要验的只是「301 要跟着走、不要把 301 抛到界面上」，与 API 级别无关。
 * 按本分支的统一约定钉在 API 23/26 上：那是这条分支的兼容性回归覆盖，也是明文请求真实存在的环境。
 */
@Config(sdk=[23,26])
class FeedRedirectTest {
    /**
     * 清单里写的是 android:usesCleartextTraffic="true"，但 Robolectric 造出来的 ApplicationInfo 不会带上
     * framework 判断明文流量用的那个私有标志，于是 OkHttp 在 Android 平台上会先撞在
     * `CLEARTEXT communication not enabled for client` 上——这个用例要验的只是重定向，跟明文许可无关。
     * 这里把清单里已经声明的同一个许可补到测试的应用信息上：低于 API 28 时框架默认允许明文，
     * 那个私有标志则直接对应清单里的声明。两步都容错，取不到就保持原样。
     */
    private fun allowCleartextTraffic() {
        val info=org.robolectric.RuntimeEnvironment.getApplication().applicationInfo
        info.targetSdkVersion=android.os.Build.VERSION_CODES.O
        runCatching {
            val flag=android.content.pm.ApplicationInfo::class.java
                .getField("PRIVATE_FLAG_ALLOW_CLEARTEXT_TRAFFIC").getInt(null)
            info.privateFlags=info.privateFlags or flag
        }
    }

    @Test fun contentTransportFollowsRedirectInsteadOfSurfacing301() {
        allowCleartextTraffic()
        val server=ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))
        val errors=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val serving=Thread {
            try {
                repeat(2) {
                    server.accept().use { socket ->
                        socket.soTimeout=5000
                        val reader=socket.getInputStream().bufferedReader()
                        val first=reader.readLine()
                        while(!reader.readLine().isNullOrEmpty()) { }
                        val response=if(first.contains("/start"))
                            "HTTP/1.1 301 Moved Permanently\r\nLocation: /video\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        else "HTTP/1.1 200 OK\r\nContent-Length: 14\r\nConnection: close\r\n\r\nvideo-response"
                        socket.getOutputStream().apply { write(response.toByteArray());flush() }
                    }
                }
            } catch(error: Throwable) { errors.set(error) }
        }.apply { start() }
        val backend=PublicFeedBackend(720)
        try {
            val http=PublicFeedBackend::class.java.getDeclaredField("http").apply { isAccessible=true }.get(backend) as OkHttpClient
            http.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/start").build()).execute().use {
                assertEquals(200,it.code);assertEquals("video-response",it.body!!.string())
                assertEquals(301,it.priorResponse()?.code())
            }
        } finally { backend.close();server.close();serving.join(5000) }
        assertNull(errors.get())
    }
}
