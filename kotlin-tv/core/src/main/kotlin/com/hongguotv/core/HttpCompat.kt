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
internal val okhttp3.Response.code: Int get() = code()
internal val okhttp3.Response.body: okhttp3.ResponseBody? get() = body()
internal val okhttp3.Response.headers: okhttp3.Headers get() = headers()
internal val okhttp3.Response.request: okhttp3.Request get() = request()
internal val okhttp3.Response.protocol: okhttp3.Protocol get() = protocol()
internal val okhttp3.Request.url: okhttp3.HttpUrl get() = url()
internal val okhttp3.Request.method: String get() = method()
internal val okhttp3.Request.headers: okhttp3.Headers get() = headers()
internal val okhttp3.Request.body: okhttp3.RequestBody? get() = body()
internal val okhttp3.Cookie.name: String get() = name()
internal val okhttp3.Cookie.value: String get() = value()
internal val okhttp3.Cookie.domain: String get() = domain()
internal val okhttp3.Cookie.path: String get() = path()
internal val okhttp3.Cookie.expiresAt: Long get() = expiresAt()
internal val okhttp3.Cookie.persistent: Boolean get() = persistent()
internal val okhttp3.Cookie.hostOnly: Boolean get() = hostOnly()
internal val okhttp3.Cookie.secure: Boolean get() = secure()
internal val okhttp3.Cookie.httpOnly: Boolean get() = httpOnly()
internal val okhttp3.OkHttpClient.dispatcher: okhttp3.Dispatcher get() = dispatcher()
internal val okhttp3.OkHttpClient.connectionPool: okhttp3.ConnectionPool get() = connectionPool()
internal val okhttp3.Dispatcher.executorService: java.util.concurrent.ExecutorService? get() = executorService()
internal val okhttp3.HttpUrl.scheme: String get() = scheme()
internal val okhttp3.HttpUrl.host: String get() = host()
internal val okhttp3.HttpUrl.query: String? get() = query()
internal val okhttp3.HttpUrl.port: Int get() = port()

// 测试里沿用 4.x 的写法（"…".toResponseBody()、url.encodedPath）。同样用扩展补形状，
// 测试代码不需要为 OkHttp 3 改写。
internal fun String.toMediaType(): MediaType = mediaTypeOf(this)
internal fun String.toHttpUrl(): HttpUrl = urlOf(this)
internal fun String.toResponseBody(contentType: MediaType? = null): okhttp3.ResponseBody =
    okhttp3.ResponseBody.create(contentType, this)
internal val okhttp3.HttpUrl.encodedPath: String get() = encodedPath()
internal val okhttp3.HttpUrl.encodedQuery: String? get() = encodedQuery()
internal val okhttp3.Headers.size: Int get() = size()
internal fun ByteArray.toResponseBody(contentType: okhttp3.MediaType? = null): okhttp3.ResponseBody =
    okhttp3.ResponseBody.create(contentType, this)
