package com.hongguotv.nativeapp

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
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
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26],qualifiers="w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackQueueTest {
    private fun get(target: Any,name: String)=target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
    private fun set(target: Any,name: String,value: Any?) { target.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(target,value) }
    private fun invoke(target: Any,name: String,vararg args: Any): Any? {
        val method=target.javaClass.declaredMethods.single { it.name==name };method.isAccessible=true
        return method.invoke(target,*args)
    }
    private class QueuePlayer {
        val items=mutableListOf(MediaItem.Builder().setMediaId("101").build())
        val calls=mutableListOf<String>();var index=0
        val player=Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,arrayOf(ExoPlayer::class.java)) { _,method,args ->
            calls+=method.name
            when(method.name) {
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getMediaItemCount" -> items.size
                "getCurrentMediaItemIndex" -> index
                "getCurrentPosition" -> 0L
                "getDuration" -> 90000L
                "getPlaybackState" -> Player.STATE_READY
                "getPlaybackParameters" -> PlaybackParameters(1f)
                "getPlayWhenReady","isPlaying" -> true
                "hasNextMediaItem" -> index+1<items.size
                "addMediaSource" -> { items+=(args!![0] as MediaSource).mediaItem;null }
                "removeMediaItems" -> {
                    val start=args!![0] as Int;val end=args[1] as Int
                    items.subList(start,end).clear();if(start<index) index-=(end-start);null
                }
                "seekToNextMediaItem" -> { index++;null }
                else -> when(method.returnType) { java.lang.Boolean.TYPE -> false;java.lang.Integer.TYPE -> 0;java.lang.Long.TYPE -> 0L;else -> null }
            }
        } as ExoPlayer
    }
    private fun fixture(test: (MainActivity,QueuePlayer,Library,RemoteVideo)->Unit) {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create();val activity=controller.get()
        try {
            val library=get(activity,"library") as Library
            (get(library,"loader") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS)
            (get(get(activity,"cloudAccount")!!,"worker") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            library.autoNext=true
            val data=Detail(Series("1","接续测试",badge="共3集"),listOf("101","102","103"))
            val fake=QueuePlayer()
            set(activity,"detail",data);set(activity,"screen","player");set(activity,"foreground",true)
            set(activity,"player",fake.player);set(activity,"episodeIndex",0);set(activity,"playbackReady",true)
            set(activity,"playbackTitle",TextView(activity));set(activity,"controls",LinearLayout(activity))
            set(activity,"episodeDuration",90000L)
            val remote=RemoteVideo(OkHttpClient(),StreamInfo("https://example.com/next",null,"720P"))
            @Suppress("UNCHECKED_CAST") val slot=get(activity,"preparedNext") as PreparedSlot<RemoteVideo>
            val key="102:${library.maxQuality}";slot.complete(slot.begin(key),key,remote)
            test(activity,fake,library,remote)
        } finally {
            // No formal playback tick/network is started by these tests.
            set(activity,"foreground",false);controller.destroy()
        }
    }
    @Test fun automaticTransitionReusesPlayerAndUpdatesEpisodeProgress()=fixture { activity,fake,library,remote ->
        invoke(activity,"queuePreparedEpisode","102:${library.maxQuality}")
        assertEquals(listOf("101","102"),fake.items.map { it.mediaId })
        fake.index=1
        assertEquals(true,invoke(activity,"advanceQueuedEpisode",90000L,true))
        assertEquals(1,get(activity,"episodeIndex"))
        assertSame(remote,get(activity,"video"));assertNull(get(activity,"queuedNext"))
        assertEquals(listOf("102"),fake.items.map { it.mediaId })
        assertEquals("102",library.progress("1")?.episodeId)
        assertEquals(0L,library.progress("1")?.position)
        assertFalse(fake.calls.any { it in listOf("stop","clearMediaItems","setMediaSource","prepare","release") })
    }
    @Test fun manualNextUsesQueuedSourceWithoutConsumingEpisodeSleepCount()=fixture { activity,fake,library,_ ->
        val timer=get(activity,"sleepTimer") as SleepTimer;timer.afterEpisodes(3)
        invoke(activity,"queuePreparedEpisode","102:${library.maxQuality}")
        invoke(activity,"playEpisode",1,0L,true,false,false)
        assertTrue(fake.calls.contains("seekToNextMediaItem"))
        assertEquals(true,invoke(activity,"advanceQueuedEpisode",12000L,false))
        assertEquals(3,timer.episodesLeft)
        assertEquals("102",library.progress("1")?.episodeId)
        assertFalse(fake.calls.any { it in listOf("stop","clearMediaItems","setMediaSource","prepare") })
    }
    @Test fun cancellationDropsOnlyQueuedSourceAndClosesItsResource()=fixture { activity,fake,library,remote ->
        invoke(activity,"queuePreparedEpisode","102:${library.maxQuality}")
        invoke(activity,"cancelPrefetch")
        assertEquals(listOf("101"),fake.items.map { it.mediaId })
        assertNull(get(activity,"queuedNext"))
        assertThrows(java.io.IOException::class.java) { remote.read(0,1) }
        assertFalse(fake.calls.contains("stop"))
    }
    @Test fun timerAndQualityMismatchPreventQueueing()=fixture { activity,fake,library,_ ->
        invoke(activity,"queuePreparedEpisode","102:wrong-quality")
        assertEquals(1,fake.items.size)
        (get(activity,"sleepTimer") as SleepTimer).afterEpisodes(1)
        invoke(activity,"queuePreparedEpisode","102:${library.maxQuality}")
        assertEquals(1,fake.items.size);assertNull(get(activity,"queuedNext"))
    }
}
