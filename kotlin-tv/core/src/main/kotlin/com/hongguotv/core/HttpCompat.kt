// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.RequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// Android 4.4 分支只能用 OkHttp 3.12（3.x 的最后一条线，官方支持 Android 2.3+）：
// OkHttp 4 要求 Android 5.0 以上。这一层把 4.x 的 Kotlin 扩展写法换成 3.12 的等价构造，
// 调用点保持同一形状，避免两条分支在业务代码上分叉。
internal fun urlOf(value: String): HttpUrl =
    HttpUrl.parse(value) ?: throw IOException("请求地址无效：$value")

internal fun mediaTypeOf(value: String): MediaType = MediaType.parse(value) ?: throw IOException("内容类型无效：$value")

internal fun bodyOf(content: String, contentType: String): RequestBody =
    RequestBody.create(mediaTypeOf(contentType), content)

internal fun bodyOf(content: ByteArray, contentType: String): RequestBody =
    RequestBody.create(mediaTypeOf(contentType), content)

/** Runs one call with a wall-clock deadline the caller owns, cancelling it when the deadline passes. */
internal fun <T> withDeadline(call: Call, timeout: Long, unit: TimeUnit, consume: (okhttp3.Response) -> T): T {
    val deadline = System.nanoTime() + unit.toNanos(timeout)
    val watchdog = Thread {
        try {
            val wait = deadline - System.nanoTime()
            if (wait > 0) Thread.sleep(unit.convert(wait, TimeUnit.MILLISECONDS))
            if (System.nanoTime() >= deadline) call.cancel()
        } catch (_: InterruptedException) {
        }
    }
    watchdog.isDaemon = true
    watchdog.start()
    try {
        return RequestScope.execute(call, consume)
    } finally {
        watchdog.interrupt()
    }
}

// OkHttp 3.12 是 Java 库：4.x 里写成属性的东西，在 3.x 只能调 getter。
// 这里用扩展属性把形状补回来，业务代码在两条分支上保持同一写法，不逐处改成方法调用。
val okhttp3.Response.code: Int get() = code()
val okhttp3.Response.body: okhttp3.ResponseBody? get() = body()
val okhttp3.Response.headers: okhttp3.Headers get() = headers()
val okhttp3.Response.request: okhttp3.Request get() = request()
val okhttp3.Response.protocol: okhttp3.Protocol get() = protocol()
val okhttp3.Response.priorResponse: okhttp3.Response? get() = priorResponse()
val okhttp3.Request.url: okhttp3.HttpUrl get() = url()
val okhttp3.Request.method: String get() = method()
val okhttp3.Request.headers: okhttp3.Headers get() = headers()
val okhttp3.Request.body: okhttp3.RequestBody? get() = body()
val okhttp3.Cookie.name: String get() = name()
val okhttp3.Cookie.value: String get() = value()
val okhttp3.Cookie.domain: String get() = domain()
val okhttp3.Cookie.path: String get() = path()
val okhttp3.Cookie.expiresAt: Long get() = expiresAt()
val okhttp3.Cookie.persistent: Boolean get() = persistent()
val okhttp3.Cookie.hostOnly: Boolean get() = hostOnly()
val okhttp3.Cookie.secure: Boolean get() = secure()
val okhttp3.Cookie.httpOnly: Boolean get() = httpOnly()
val okhttp3.OkHttpClient.dispatcher: okhttp3.Dispatcher get() = dispatcher()
val okhttp3.OkHttpClient.connectionPool: okhttp3.ConnectionPool get() = connectionPool()
val okhttp3.Dispatcher.executorService: java.util.concurrent.ExecutorService? get() = executorService()
val okhttp3.HttpUrl.scheme: String get() = scheme()
val okhttp3.HttpUrl.host: String get() = host()
val okhttp3.HttpUrl.query: String? get() = query()
val okhttp3.HttpUrl.port: Int get() = port()

// 测试里沿用 4.x 的写法（"…".toResponseBody()、url.encodedPath）。同样用扩展补形状，
// 测试代码不需要为 OkHttp 3 改写。
fun String.toMediaType(): MediaType = mediaTypeOf(this)
fun String.toHttpUrl(): HttpUrl = urlOf(this)
fun String.toResponseBody(contentType: MediaType? = null): okhttp3.ResponseBody =
    okhttp3.ResponseBody.create(contentType, this)
val okhttp3.HttpUrl.encodedPath: String get() = encodedPath()
val okhttp3.HttpUrl.encodedQuery: String? get() = encodedQuery()
val okhttp3.Headers.size: Int get() = size()
fun ByteArray.toResponseBody(contentType: okhttp3.MediaType? = null): okhttp3.ResponseBody =
    okhttp3.ResponseBody.create(contentType, this)

// Android 4.1~4.4 实现了 TLS 1.2，但默认不启用；服务端只接受 1.2 时握手会直接失败。
// core 是纯 JVM 模块，不能直接引用 android.os.Build，按名字取；取不到（JVM 测试环境）
// 就当高版本处理，这条分支只在真机 Android 4.x 上生效。
private val androidApiLevel: Int by lazy {
    try {
        Class.forName("android.os.Build\$VERSION").getField("SDK_INT").getInt(null)
    } catch (_: Exception) {
        Int.MAX_VALUE
    }
}

/** 所有 https 客户端都从这里创建：4.x 设备上会统一启用 TLS 1.2。
 *  app 模块也走这个入口，避免新加的客户端漏掉 compatTls（播放、试播、账号、海报
 *  各自的客户端一旦漏掉，在 4.4 上就只剩「数据能加载、视频加载不出来」这种半残状态）。 */
fun newHttpClientBuilder(): okhttp3.OkHttpClient.Builder = okhttp3.OkHttpClient.Builder().compatTls()

/** 给 OkHttp 一个明确启用 TLS 1.2 的 socket factory；设备不支持时保持系统默认。 */
internal fun okhttp3.OkHttpClient.Builder.compatTls(): okhttp3.OkHttpClient.Builder {
    if (androidApiLevel > 20) return this
    return try {
        val trustFactory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        trustFactory.init(null as java.security.KeyStore?)
        val trust = trustFactory.trustManagers.firstOrNull { it is javax.net.ssl.X509TrustManager }
            as? javax.net.ssl.X509TrustManager ?: return this
        val context = tlsContext("TLSv1.2")
        context.init(null, trustFactory.trustManagers, null)
        // 只给 socket factory 不够：OkHttp 先按 connectionSpecs 挑协议，默认的 MODERN_TLS 含
        // TLS 1.3，4.4 上 isSupported() 判为不支持就整条跳过，退回 COMPATIBLE_TLS（只有 1.0/1.1），
        // 于是服务端回 "tlsv1 alert protocol version"，表现为每个界面都「暂时无法加载」。
        // 这里显式只保留 TLS 1.2，OkHttp 才会真的 setEnabledProtocols("TLSv1.2")。
        val tls12Only = okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
            .tlsVersions(okhttp3.TlsVersion.TLS_1_2)
            // 不写死套件名：MODERN_TLS 自带 CHACHA20 等 4.4 没有的套件，一旦逐个比对失败，
            // 这条 spec 同样会被跳过。交给设备自己那套 enabled suites 去协商。
            .allEnabledCipherSuites()
            .build()
        sslSocketFactory(context.socketFactory, trust).connectionSpecs(listOf(tls12Only))
    } catch (_: Exception) {
        this
    }
}

/**
 * 部分 Android 4.x 机型（实测 TCL 电视）的系统 JSSE Provider 只有 SSLv3/TLSv1，
 * `SSLContext.getInstance("TLSv1.2")` 能建出来但 socket 的 supported protocols 里没有 1.2。
 * 这种设备只能用随包携带的实现，[preferJsseProvider] 由 app 在启动时装好 Conscrypt 后调用。
 */
private var jsseProvider: String? = null

/** 指定 JSSE Provider 名；之后创建的 SSLContext 都走它。 */
fun preferJsseProvider(name: String) {
    jsseProvider = name
}

private fun tlsContext(algorithm: String): javax.net.ssl.SSLContext {
    val provider = jsseProvider
    if (provider != null) {
        try {
            return javax.net.ssl.SSLContext.getInstance(algorithm, provider)
        } catch (_: Exception) {
            // Provider 没装上（例如 native 库加载失败），退回系统实现，至少不阻断启动。
        }
    }
    return javax.net.ssl.SSLContext.getInstance(algorithm)
}
