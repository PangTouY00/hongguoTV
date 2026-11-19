package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackComfortTest {
    @Test fun `time limit uses elapsed clock and fires once including paused time`() {
        var now=100L; val timer=SleepTimer { now }
        timer.afterMinutes(15); now+=899999
        assertFalse(timer.poll()); assertEquals("1 分钟后",timer.label())
        now++; assertTrue(timer.poll()); assertFalse(timer.active); assertFalse(timer.poll())
    }
    @Test fun `episode limit only consumes ends and replacing or cancelling disarms it`() {
        var now=0L; val timer=SleepTimer { now }
        timer.afterEpisodes(3); now=1000000
        assertFalse(timer.poll()); assertFalse(timer.episodeEnded()); assertFalse(timer.episodeEnded())
        assertTrue(timer.episodeEnded()); assertFalse(timer.episodeEnded())
        timer.afterEpisodes(5); timer.afterMinutes(1); assertEquals(0,timer.episodesLeft)
        timer.cancel(); now+=60000; assertFalse(timer.poll())
        timer.afterMinutes(1); timer.afterEpisodes(1); now+=60000
        assertFalse(timer.poll()); assertTrue(timer.episodeEnded())
    }
    @Test fun `elapsed timer also wins at episode transition`() {
        var now=0L; val timer=SleepTimer { now }; timer.afterMinutes(1); now=60000
        assertTrue(timer.episodeEnded()); assertFalse(timer.active)
        assertTrue(runCatching { timer.afterMinutes(0) }.isFailure)
        assertTrue(runCatching { timer.afterEpisodes(11) }.isFailure)
        assertEquals(VideoFrameMode.FIT,VideoFrameMode.fromStored("unknown"))
    }
    @Test fun `zoom only stops on tenth steps and stays put at both ends`() {
        assertEquals(10,VideoZoom.MIN_TENTHS); assertEquals(20,VideoZoom.MAX_TENTHS); assertEquals(1,VideoZoom.STEP_TENTHS)
        assertEquals(1.4,VideoZoom.DEFAULT.toDouble(),0.001); assertEquals("1.4",VideoZoom.text(VideoZoom.DEFAULT))
        // 0.1 在二进制里不精确：档位必须由整数相加得出，按键一次就要正好落到下一档。
        assertEquals(1.5,VideoZoom.step(1.4f,1).toDouble(),0.001)
        assertEquals(1.4,VideoZoom.step(1.5f,-1).toDouble(),0.001)
        assertEquals(2.0,VideoZoom.step(2.0f,1).toDouble(),0.001)
        assertEquals(1.0,VideoZoom.step(1.0f,-1).toDouble(),0.001)
        assertEquals(15,VideoZoom.stepTenths(14,1)); assertEquals(10,VideoZoom.stepTenths(10,-5)); assertEquals(20,VideoZoom.stepTenths(20,5))
        // 不在档位上的旧值与滑杆拖动落下的中间值都收到最近一档。
        assertEquals(1.4,VideoZoom.normalize(1.36f).toDouble(),0.001)
        assertEquals(1.4,VideoZoom.normalize(1.43f).toDouble(),0.001)
        assertEquals(1.5,VideoZoom.normalize(1.46f).toDouble(),0.001)
        assertEquals(1.0,VideoZoom.normalize(0.2f).toDouble(),0.001)
        assertEquals(2.0,VideoZoom.normalize(9f).toDouble(),0.001)
        // NaN 没有方向，回落默认；正负无穷有方向，各自贴到端点。
        assertEquals(1.4,VideoZoom.normalize(Float.NaN).toDouble(),0.001)
        assertEquals(VideoZoom.MAX_TENTHS,VideoZoom.tenths(Float.POSITIVE_INFINITY))
        assertEquals(VideoZoom.MIN_TENTHS,VideoZoom.tenths(Float.NEGATIVE_INFINITY))
        // 十次按键从 1.0 走到 2.0，一格不多一格不少：滑杆的 max 与这个档位表是同一件事。
        var value=VideoZoom.MIN; var steps=0
        while(value<VideoZoom.MAX && steps<40) { value=VideoZoom.step(value,1); steps++ }
        assertEquals(10,steps); assertEquals(2.0,value.toDouble(),0.001)
    }
    @Test fun `frame modes keep their stored names and only the magnified one carries a number`() {
        assertEquals(VideoFrameMode.FIT,VideoFrameMode.fromStored("unknown"))
        assertEquals(VideoFrameMode.ZOOM,VideoFrameMode.fromStored("ZOOM"))
        assertEquals(VideoFrameMode.CROP,VideoFrameMode.fromStored("CROP"))
        assertEquals(listOf("完整画面","自定义放大","裁剪铺满","拉伸铺满"),VideoFrameMode.entries.map { it.label })
        // 倍数只在放大模式上有意义：其它模式的名字不会因为倍数的值而变。
        assertEquals("完整画面",VideoFrameMode.FIT.labelWithZoom(1.7f))
        assertEquals("裁剪铺满",VideoFrameMode.CROP.labelWithZoom(2f))
        assertEquals("放大 1.7 倍",VideoFrameMode.ZOOM.labelWithZoom(1.7f))
    }
}
