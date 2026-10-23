// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoStartupCacheTest {
    @Test fun firstPlayerReadUsesPreparedPrefixWithoutAnotherHttpRoundTrip() {
        val file=ByteArray(1024*1024)
        java.nio.ByteBuffer.wrap(file).putInt(8).put("ftyp".toByteArray()).putInt(8).put("moov".toByteArray())
        var requests=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            requests++;val limits=chain.request().header("Range")!!.removePrefix("bytes=").split('-').map { it.toInt() }
            val end=minOf(file.lastIndex,limits[1])
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("partial")
                .header("Content-Range","bytes ${limits[0]}-$end/${file.size}").body(file.copyOfRange(limits[0],end+1).toResponseBody()).build()
        }.build()
        val video=RemoteVideo(http,StreamInfo("https://example.test/video",null,"测试")).prepare()
        val source=VideoDataSource(video)
        try {
            source.open(DataSpec.Builder().setUri("https://example.test/video").build())
            val result=ByteArray(16);assertEquals(16,source.read(result,0,16))
            assertArrayEquals(file.copyOf(16),result);assertEquals(1,requests)
        } finally { source.close();video.close() }
    }
}
