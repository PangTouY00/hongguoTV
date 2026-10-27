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
import org.junit.Assert.*
import org.junit.Test

/** 播放身份必须来自匿名注册：注册一次、进程内复用、注册失败不拿随机身份硬播。 */
class PlaybackDeviceSessionTest {
    private val model =
        """{"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}"""

    @Test
    fun registersBeforePlaybackAndReusesIdentityAcrossRepositoriesAndRetries() {
        var registrations = 0
        var models = 0
        val requests = mutableListOf<okhttp3.Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val registration = request.url.encodedPath == "/service/2/device_register/"
            val body = if (registration) {
                registrations++
                // 注册请求是匿名的：不带 Cookie，也不带业务签名头。
                assertNull(request.header("Cookie"))
                assertNull(request.header("x-gorgon"))
                """{"device_id":"333","install_id":"444"}"""
            } else {
                requests += request
                assertEquals("333", request.url.queryParameter("device_id"))
                assertEquals("444", request.url.queryParameter("iid"))
                // 注册响应里的设备 Cookie 必须跟着播放请求走，账号 Cookie 不在白名单里。
                assertEquals("ttreq=test-only", request.header("Cookie"))
                if (++models == 1) "" else model
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .apply { if (registration) addHeader("Set-Cookie", "ttreq=test-only; Path=/") }
                .body(body.toResponseBody()).build()
        }.build()
        val session = PlaybackDeviceSession()
        ContentRepository(http, playbackSession = session).streamLadder("123")
        ContentRepository(http, playbackSession = session).streamLadder("123")
        assertEquals("注册一次就够，跨 repository 复用", 1, registrations)
        assertEquals(3, models)
        assertEquals(1, requests.map { it.url.queryParameter("cdid") }.distinct().size)
    }

    @Test
    fun concurrentCallersRegisterOnlyOnceAndKeepOneProfile() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = PlaybackDeviceSession {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            DeviceRegister.Registered("333", "444")
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit { session.profile(OkHttpClient()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit { session.profile(OkHttpClient()) }
            release.countDown()
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun rejectedRegistrationNeverRequestsPlaybackWithRandomIds() {
        var models = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            models++
            error("Playback must not be requested")
        }.build()
        for (registered in listOf(
            null,
            DeviceRegister.Registered("0", "444"),
            DeviceRegister.Registered("333", "bad"),
        )) {
            val repo = ContentRepository(http, playbackSession = PlaybackDeviceSession { registered })
            assertThrows(PlaybackDeviceRegistrationException::class.java) { repo.streamLadder("123") }
        }
        assertEquals(0, models)
    }

    @Test
    fun registrationFailureCanBeRetriedButSuccessfulIdentityNeverRotates() {
        var calls = 0
        val session = PlaybackDeviceSession {
            if (++calls == 1) throw IOException("test-only")
            DeviceRegister.Registered("333", "444", cookies = mapOf("ttreq" to "test-only", "sessionid" to "excluded"))
        }
        val http = OkHttpClient()
        assertThrows(PlaybackDeviceRegistrationException::class.java) { session.profile(http) }
        val profile = session.profile(http)
        assertEquals("ttreq=test-only", profile.cookie)
        assertSame("注册成功后身份不再轮换", profile, session.profile(http))
        assertEquals(2, calls)
    }

    @Test
    fun cancelledScopeDoesNotSendOrCacheRegistration() {
        var registrations = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            registrations++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"device_id":"333","install_id":"444"}""".toResponseBody()).build()
        }.build()
        val session = PlaybackDeviceSession()
        val scope = RequestScope()
        scope.cancel()
        assertThrows(IOException::class.java) { scope.run { session.profile(http) } }
        assertEquals(0, registrations)
        session.profile(http)
        assertEquals(1, registrations)
    }

    @Test
    fun registrationFailureKeepsItsOwnSafeCategory() {
        val session = PlaybackDeviceSession { throw IOException("test-only private response") }
        val error = assertThrows(PlaybackDeviceRegistrationException::class.java) { session.profile(OkHttpClient()) }
        assertFalse(error.message.orEmpty().contains("private response"))
    }

    @Test
    fun emptyPlaybackResponseIsRetriedWithAFreshTicket() {
        val reads = AtomicInteger()
        val tickets = mutableListOf<String?>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            tickets += request.url.queryParameter("_rticket")
            val body = if (reads.incrementAndGet() < 3) "" else model
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        var now = 0L
        val repo = ContentRepository(http, { now += 1_000L; now }, PlaybackDeviceSession { DeviceRegister.Registered("333", "444") })
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
