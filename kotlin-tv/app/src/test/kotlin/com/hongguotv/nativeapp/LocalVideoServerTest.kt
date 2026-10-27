// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.StreamInfo
import com.hongguotv.core.toResponseBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 4.4 的播放器读不了自定义数据源：LocalVideoServer 在 127.0.0.1 上把 RemoteVideo 的
 * 分段读取翻译成 HTTP Range。这里验证 Range 响应与「只服务 /video.mp4」。
 */
class LocalVideoServerTest {
    private val file = ByteArray(1024 * 1024) { (it % 251).toByte() }.also {
        java.nio.ByteBuffer.wrap(it).putInt(8).put("ftyp".toByteArray()).putInt(8).put("moov".toByteArray())
    }

    private fun video(): RemoteVideo {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val spec = request.header("Range")!!.removePrefix("bytes=")
            val start = spec.substringBefore('-').toLong()
            val end = minOf(spec.substringAfter('-').toLong(), file.lastIndex.toLong())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("partial")
                .header("Content-Range", "bytes $start-$end/${file.size}")
                .body(file.copyOfRange(start.toInt(), end.toInt() + 1).toResponseBody()).build()
        }.build()
        return RemoteVideo(http, StreamInfo("https://example.test/video", null, "测试")).prepare()
    }

    @Test
    fun rangeReadsAreServedFromTheLocalProxyAndOtherPathsAreRejected() {
        val video = video()
        val server = LocalVideoServer(video)
        val client = OkHttpClient()
        try {
            client.newCall(Request.Builder().url(server.url).header("Range", "bytes=262144-327679").build())
                .execute().use { response ->
                    assertEquals(206, response.code())
                    assertEquals("bytes 262144-327679/${file.size}", response.header("Content-Range"))
                    assertArrayEquals(file.copyOfRange(262144, 327680), response.body()!!.bytes())
                }
            client.newCall(Request.Builder().url(server.url.replace("/video.mp4", "/other")).build())
                .execute().use { assertEquals(404, it.code()) }
        } finally {
            server.close()
            video.close()
            client.dispatcher().executorService().shutdown()
        }
    }
}
