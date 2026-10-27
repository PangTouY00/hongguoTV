package com.hongguotv.nativeapp

import com.hongguotv.core.PlaybackDeviceRegistrationException
import com.hongguotv.core.PlaybackDeviceSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26], qualifiers = "w960dp-h540dp-land-mdpi")
class PlaybackDeviceIntegrationTest {
    private fun field(target: Any,name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)

    @Test
    fun homepageAndFullscreenShareOneAnonymousIdentity() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val home=PublicFeedBackend(720)
        try {
            val fullscreen=field(controller.get(),"repository")!!
            val feed=field(home,"repository")!!
            // 两个入口各自注册会被平台看成两台设备，播放身份必须同源。
            assertSame(PlaybackDeviceSession.shared,field(fullscreen,"playbackSession"))
            assertSame(field(fullscreen,"playbackSession"),field(feed,"playbackSession"))
        } finally {
            home.close()
            controller.destroy()
        }
    }

    @Test
    fun registrationFailureIsItsOwnSafeCategory() {
        val error=PlaybackDeviceRegistrationException(java.io.IOException("test-only private response"))
        assertEquals("播放设备注册失败，请稍后重试",error.message)
        assertFalse(error.message.orEmpty().contains("private response"))
    }
}
