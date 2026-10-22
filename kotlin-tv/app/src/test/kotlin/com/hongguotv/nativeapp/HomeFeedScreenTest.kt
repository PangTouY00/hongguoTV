package com.hongguotv.nativeapp

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26],qualifiers="w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeFeedScreenTest {
    private class Backend: FeedBackend {
        var prepares=0;var closed=false
        override fun catalog(category: DramaCategory,page: Int)=CatalogPage(listOf(Series("1","第一部"),Series("2","第二部")),false)
        override fun detail(series: Series)=Detail(series,listOf("${series.id}01","${series.id}02"))
        override fun prepare(detail: Detail): TrialClip { prepares++;return TrialClip(detail,RemoteVideo(OkHttpClient(),StreamInfo("https://example.com/video",null,"test"))) }
        override fun close() { closed=true }
    }
    private class Playback(activity: Activity): TrialPlayback {
        override val view=View(activity)
        override var status: ((TrialStatus)->Unit)?=null
        var loaded="";var saved=0L;var playing=false;var released=false
        override fun load(clip: TrialClip,position: Long,autoplay: Boolean) { loaded=clip.detail.series.id;saved=position;playing=autoplay }
        override fun position()=saved
        override fun pause() { playing=false }
        override fun resume() { playing=true }
        override fun restart() { saved=0;playing=true }
        override fun clear() { loaded="";playing=false }
        override fun release() { released=true }
    }
    private fun get(target: Any,name: String)=target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
    private fun pump(screen: HomeFeedScreen) { repeat(4) { (get(screen,"job") as? Future<*>)?.get(5,TimeUnit.SECONDS);ShadowLooper.idleMainLooper() } }
    private fun texts(view: View): List<TextView> = if(view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else if(view is TextView) listOf(view) else emptyList()
    @Test fun remoteSwitchesWorksAndCompletionDoesNotAdvance() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val state=DramaFeedState();val backend=Backend();val player=Playback(activity)
        val screen=HomeFeedScreen(activity,state,listOf(TextView(activity)),{}, { _,_-> },{"账户收藏"},backend,player)
        try {
            activity.setContentView(screen);screen.start(true,false);pump(screen)
            assertEquals("1",player.loaded);assertTrue(player.playing)
            // Next metadata may load, but only the current work prepares a video stream.
            assertEquals(1,backend.prepares)
            val stage=get(screen,"stage") as View
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));pump(screen)
            assertEquals("2",player.loaded)
            player.status?.invoke(TrialStatus.ENDED)
            assertEquals("2",state.current?.id);assertTrue(state.ended)
            assertTrue(screen.back());assertFalse(screen.back())
        } finally { screen.close();controller.pause().stop().destroy() }
        assertTrue(player.released);assertTrue(backend.closed)
    }
    @Test fun openingFullSeriesAndReturningRestoresTrialPosition() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val state=DramaFeedState();val backend=Backend();val player=Playback(activity);var watched=""
        val screen=HomeFeedScreen(activity,state,listOf(TextView(activity)),{ watched=it.id },{ _,_-> },{"账户收藏"},backend,player)
        try {
            activity.setContentView(screen);screen.start(true,false);pump(screen);player.saved=12345
            texts(screen).first { it.text=="观看全集" }.performClick()
            assertEquals("1",watched);assertEquals(12345L,state.position);assertFalse(player.playing)
            screen.resume();assertTrue(player.playing)
            screen.close()
            val nextPlayer=Playback(activity)
            val next=HomeFeedScreen(activity,state,listOf(TextView(activity)),{}, { _,_-> },{"账户收藏"},Backend(),nextPlayer)
            try { activity.setContentView(next);next.start(true,false);pump(next);assertEquals(12345L,nextPlayer.saved) }
            finally { next.close() }
        } finally { screen.close();controller.pause().stop().destroy() }
    }
    @Test fun lifecyclePauseAndTypeSwitchStopPreviousTrial() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val state=DramaFeedState();val player=Playback(activity)
        val screen=HomeFeedScreen(activity,state,listOf(TextView(activity)),{}, { _,_-> },{"账户收藏"},Backend(),player)
        try {
            activity.setContentView(screen);screen.start(true,false);pump(screen)
            player.saved=456;screen.pause();assertFalse(player.playing);assertEquals(456L,state.position)
            screen.resume();assertTrue(player.playing)
            texts(screen).first { it.text.toString().startsWith("类型：") }.performClick();pump(screen)
            assertEquals(DramaCategory.REAL,state.category);assertEquals(0L,player.saved)
        } finally { screen.close();controller.pause().stop().destroy() }
    }
}
