// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 与 core 的 HttpCompat 同一件事：OkHttp 3.12 是 Java 库，4.x 里是属性的东西这里只能调 getter。
// core 的那组扩展是模块内可见的，界面层用这一组，业务代码在两条分支上保持同一写法。
internal val okhttp3.OkHttpClient.dispatcher: okhttp3.Dispatcher get() = dispatcher()
internal val okhttp3.OkHttpClient.connectionPool: okhttp3.ConnectionPool get() = connectionPool()
internal val okhttp3.Dispatcher.executorService: java.util.concurrent.ExecutorService? get() = executorService()
internal val okhttp3.Response.code: Int get() = code()
internal val okhttp3.Response.body: okhttp3.ResponseBody? get() = body()
internal val okhttp3.Response.headers: okhttp3.Headers get() = headers()
internal val okhttp3.Response.request: okhttp3.Request get() = request()
internal val okhttp3.Request.url: okhttp3.HttpUrl get() = url()
internal val okhttp3.HttpUrl.host: String get() = host()
internal val okhttp3.HttpUrl.encodedPath: String get() = encodedPath()
