package com.hongguotv.nativeapp

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.speech.RecognizerIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26],qualifiers="w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SearchInputTest {
    @Test fun privateV4AddressesExcludeLoopbackIpv6AndPublicAndRemoveDuplicates() {
        val values=listOf("127.0.0.1","192.168.1.20","10.0.0.2","192.168.1.20","8.8.8.8","169.254.1.1","::1","fd00::1")
        assertEquals(listOf("192.168.1.20","10.0.0.2"),LanInputAddresses.privateV4(values.map(InetAddress::getByName)))
    }
    @Test fun missingRecognitionActivityGivesFallbackWithoutLaunching() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var message=""
            VoiceInput(activity.get(),{ fail("unexpected result") },{ message=it }).start()
            assertTrue(message.contains("没有可用的语音识别服务"))
            assertNull(shadowOf(activity.get()).nextStartedActivityForResult)
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun recognizedChineseReturnsTextAndCancellationPreservesExistingDraft() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val handler=ResolveInfo().apply { activityInfo=ActivityInfo().apply { packageName="test.speech"; name="test.speech.Recognize"; exported=true } }
            shadowOf(activity.get().packageManager).addResolveInfoForIntent(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),handler)
            var value="原先输入"; var notice=""
            val voice=VoiceInput(activity.get(),{ value=it },{ notice=it })
            voice.start()
            val started=shadowOf(activity.get()).nextStartedActivityForResult
            assertNotNull(started)
            assertEquals(VoiceInput.REQUEST,started.requestCode)
            assertEquals("zh-CN",started.intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            voice.handle(VoiceInput.REQUEST,Activity.RESULT_CANCELED,null)
            assertEquals("原先输入",value)
            val result=Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,arrayListOf("  测试短剧  "))
            assertTrue(voice.handle(VoiceInput.REQUEST,Activity.RESULT_OK,result))
            assertEquals("测试短剧",value); assertEquals("",notice)
            voice.handle(VoiceInput.REQUEST,Activity.RESULT_OK,Intent())
            assertTrue(notice.contains("没有识别到文字")); assertEquals("测试短剧",value)
            assertFalse(voice.handle(999,Activity.RESULT_OK,result))
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun recognitionResultRejectsControlCharactersAndBoundsLength() {
        assertNull(VoiceInput.result(Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,arrayListOf("\n\u0000"))))
        assertEquals(80,VoiceInput.result(Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,arrayListOf("剧".repeat(90))))!!.length)
    }
}
