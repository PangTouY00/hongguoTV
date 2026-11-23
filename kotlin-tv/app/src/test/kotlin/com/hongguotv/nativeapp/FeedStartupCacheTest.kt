// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26])
class FeedStartupCacheTest {
    private val page=CatalogPage(listOf(Series("101","缓存作品")),true)
    @Test fun sourcesAreSeparateAndAccountChangeOrExpiryCannotReuseOldStarter() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();var now=1000L
        val cache=FeedStartupCache(controller.get()) { now }
        try {
            cache.save("test-account-a","recommend",page)
            assertNotNull(cache.load("test-account-a","recommend"))
            assertNull(cache.load("test-account-a",DramaCategory.COMIC.route))
            now+=600001;assertNull(cache.load("test-account-a","recommend"))
            cache.save("test-account-a","recommend",page)
            assertNull(cache.load("test-account-b","recommend"))
            assertNull(cache.load("test-account-a","recommend"))
            cache.save("test-account-a","recommend",page)
            controller.get().getSharedPreferences("native-feed-startup-v1",android.content.Context.MODE_PRIVATE).edit().putInt("recommend",42).apply()
            assertNull(cache.load("test-account-a","recommend"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun verifiedFirstEpisodeSurvivesBackendRecreationWithoutFeedOrDetailRequest() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val cache=FeedStartupCache(controller.get()) { 1000L }
        cache.save("guest","recommend",page)
        val paths=java.util.concurrent.CopyOnWriteArrayList<String>()
        val ranges=java.util.concurrent.CopyOnWriteArrayList<String>()
        val file=ByteArray(1024*1024)
        java.nio.ByteBuffer.wrap(file).putInt(8).put("ftyp".toByteArray()).putInt(8).put("moov".toByteArray())
        fun transport()=OkHttpClient.Builder().addInterceptor { chain ->
            val req=chain.request();paths+=req.url.encodedPath
            val builder=Response.Builder().request(req).protocol(Protocol.HTTP_1_1).message("test")
            if(req.header("Range")!=null) {
                val header=req.header("Range")!!;ranges+=header
                val limits=header.removePrefix("bytes=").split('-').map(String::toInt)
                builder.code(206).header("Content-Range","bytes ${limits[0]}-${limits[1]}/${file.size}")
                    .body(file.copyOfRange(limits[0],limits[1]+1).toResponseBody()).build()
            } else {
                val json=when(req.url.encodedPath) {
                    "/detail" -> """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"101","series_title":"缓存作品","vid_list":["10101","10102"]}}}};</script>"""
                    // 2026-11-23 起 video_tab 是首选：这里按「三条入口都不给结果」的形态回 100103。
                    "/reading/bookapi/video_tab/multi_video_model/v1/" -> """{"code":100103,"message":"PARAM_INVALID"}"""
                    // 退到 v5 时当前写法按空响应处理，播放地址由备用写法给出。
                    "/novel/player/multi_video_model/player/v1" -> ""
                    "/novel/player/multi_video_model/v1/" -> """{"data":{"10101":{"video_model":{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}"""
                    else -> error("Unexpected request")
                }
                builder.code(200).body(json.toResponseBody()).build()
            }
        }.build()
        try {
            repeat(2) {
                val backend=PublicFeedBackend(720,http=transport(),startupCache=cache,playbackSession=testPlaybackDeviceSession())
                try {
                    val result=backend.recommendations(1,DramaFeedState().recommendationCursor)
                    backend.trial(result.items.single()).use { clip ->
                        assertEquals("10101",clip.episodeId);assertEquals(2,clip.episodeCount)
                        assertEquals(32*1024,clip.video.cachedBytes(0))
                    }
                } finally { backend.close() }
            }
            assertEquals(1,paths.count { it=="/detail" })
            // 首选是 video_tab：每轮三条入口按健康度各试一次，都不给结果才往下退。
            val tab=paths.count { it=="/reading/bookapi/video_tab/multi_video_model/v1/" }
            assertTrue("首选该是 video_tab，且每轮三条入口各试一次：$paths", tab > 0 && tab % 3 == 0)
            // 退到 v5 时两条写法各试一次，成功的是备用那条。
            assertEquals("两条 v5 写法该各试同样次数：$paths",
                paths.count { it=="/novel/player/multi_video_model/player/v1" },
                paths.count { it=="/novel/player/multi_video_model/v1/" })
            assertEquals(listOf("bytes=0-262143","bytes=0-262143"),ranges)
            // 两轮取址 = 三条入口 + 两条 v5 写法，另加一次详情与两次视频分段，不该再多花请求。
            assertTrue("取址请求数量：$paths", paths.size <= 2 * (3 + 2) + 1 + 2)
        } finally { controller.pause().stop().destroy() }
    }
}
