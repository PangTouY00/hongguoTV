package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PlaybackDeviceSessionTest {
    private val model =
        """{"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}"""

    @Test
    fun registersOnceThenReusesTheIdentityAcrossRepositories() {
        var registrations = 0
        val requests = mutableListOf<okhttp3.Request>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val registration = request.url.encodedPath == "/service/2/device_register/"
                    val body =
                        if (registration) {
                            registrations++
                            // 注册请求是匿名身份的来源，不能带账号 Cookie 或签名头。
                            assertNull(request.header("Cookie"))
                            assertNull(request.header("x-gorgon"))
                            """{"device_id":"333","install_id":"444"}"""
                        } else {
                            requests += request
                            assertEquals("333", request.url.queryParameter("device_id"))
                            assertEquals("444", request.url.queryParameter("iid"))
                            assertEquals("ttreq=test-only", request.header("Cookie"))
                            model
                        }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .apply {
                            if (registration) addHeader("Set-Cookie", "ttreq=test-only; Path=/")
                        }
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val session = PlaybackDeviceSession()
        ContentRepository(http, playbackSession = session).streamLadder("123")
        ContentRepository(http, playbackSession = session).streamLadder("123")
        assertEquals(1, registrations)
        assertEquals(2, requests.size)
        // 两个仓库共用同一个 cdid：重新注册会让平台看成另一台设备，播放进度和限流都对不上。
        assertEquals(1, requests.map { it.url.queryParameter("cdid") }.distinct().size)
    }

    @Test
    fun concurrentCallersShareOneRegistration() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session =
            PlaybackDeviceSession {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                DeviceRegister.Registered("333", "444")
            }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map { pool.submit<PlaybackDeviceSession.Profile> { session.profile(OkHttpClient()) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            val profiles = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            assertEquals(1, profiles.map { it.values["cdid"] }.distinct().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun registrationFailureKeepsItsOwnSafeCategory() {
        val session = PlaybackDeviceSession { throw IOException("test-only private response") }
        val error =
            assertThrows(PlaybackDeviceRegistrationException::class.java) {
                session.profile(OkHttpClient())
            }
        assertFalse(error.message.orEmpty().contains("private response"))
    }

    @Test
    fun rejectedIdentifiersCannotBeStoredAsAnIdentity() {
        for (registered in listOf(DeviceRegister.Registered("0", "444"), DeviceRegister.Registered("333", ""))) {
            val session = PlaybackDeviceSession { registered }
            assertThrows(PlaybackDeviceRegistrationException::class.java) {
                session.profile(OkHttpClient())
            }
        }
    }

    @Test
    fun emptyPlaybackResponseIsRetriedWithAFreshTicket() {
        val reads = AtomicInteger()
        val tickets = mutableListOf<String?>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    tickets += request.url.queryParameter("_rticket")
                    val body = if (reads.incrementAndGet() < 3) "" else model
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        var now = 0L
        val repo =
            ContentRepository(
                http,
                { now += 1_000L; now },
                PlaybackDeviceSession { DeviceRegister.Registered("333", "444") },
            )
        assertEquals(1, repo.streamLadder("123").renditions.size)
        assertEquals(3, reads.get())
        // 同一签名重读通常还是空响应，所以每次重试都要换新的 _rticket。
        assertEquals(3, tickets.distinct().size)
    }

    @Test
    fun interruptedCallerDoesNotStartARegistration() {
        val session = PlaybackDeviceSession { throw AssertionError("registration must not start") }
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { session.profile(OkHttpClient()) }
        } finally {
            Thread.interrupted()
        }
    }
}
