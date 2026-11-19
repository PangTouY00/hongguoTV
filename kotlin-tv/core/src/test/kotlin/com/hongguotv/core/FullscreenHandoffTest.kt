package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class FullscreenHandoffTest {

    @Test fun `the default windows are the ones the interface is built around`() {
        val handoff = FullscreenHandoff()
        assertEquals(900L, handoff.coverGraceMs)
        assertEquals(700L, handoff.hintAfterMs)
        assertEquals(3_000L, handoff.hintWithCoverAfterMs)
        assertEquals(20_000L, handoff.giveUpAfterMs)
    }

    @Test fun `the shade goes up as soon as the cover is there, not after a fixed wait`() {
        val handoff = FullscreenHandoff()
        handoff.begin(1_000)
        // 封面早到：过渡层就用封面那一刻开始，不该再多空等那 900 毫秒。
        handoff.coverArrived(1_000 + 300)
        assertEquals(1_300L, handoff.shadeAt())
        assertTrue(handoff.coverReady)
    }

    @Test fun `a cover that never comes still hands over a dark shade with a hint`() {
        val handoff = FullscreenHandoff()
        handoff.begin(1_000)
        // 封面没到之前不能把用户留在「正在加载剧集…」那种中间页上。
        assertTrue(handoff.waiting)
        assertEquals(1_000 + 900, handoff.shadeAt())
        assertFalse(handoff.coverReady)
        // 晚到的封面仍然填上去：它比一块黑底更能说明要看的是这一部。
        handoff.coverArrived(1_000 + 2_000)
        assertTrue(handoff.coverReady)
    }

    @Test fun `the shade is only handed over once the first frame is really drawn`() {
        val handoff = FullscreenHandoff()
        handoff.begin(1_000)
        handoff.coverArrived(1_100)
        // 过去这里是「固定 2.5 秒后自己交出去」。取址比它慢，结果就是先撤掉封面、再对着黑播放器等。
        assertFalse(handoff.handedOff(1_000 + 2_500))
        assertFalse(handoff.shouldGiveUp(1_000 + 2_500))
        assertTrue(handoff.waiting)
        handoff.firstFrame(1_000 + 3_200)
        assertTrue(handoff.handedOff(1_000 + 3_200))
        assertFalse(handoff.waiting)
        // 画面已经交出去了，这一层不该再出现。
        assertEquals(0L, handoff.shadeAt())
        assertFalse(handoff.coverReady)
    }

    @Test fun `a failure clears the shade at once so the error page can be seen`() {
        val handoff = FullscreenHandoff()
        handoff.begin(1_000)
        handoff.coverArrived(1_100)
        assertFalse(handoff.shouldGiveUp(1_000 + 1_000))
        handoff.failed(1_000 + 1_500)
        assertTrue(handoff.shouldGiveUp(1_000 + 1_500))
        assertFalse(handoff.waiting)
        assertFalse(handoff.coverReady)
        assertFalse(handoff.showHint(1_000 + 1_500))
        assertEquals(0L, handoff.shadeAt())
    }

    @Test fun `the shade gives up at the ceiling rather than waiting forever`() {
        val handoff = FullscreenHandoff()
        handoff.begin(1_000)
        assertEquals(21_000L, handoff.giveUpAt())
        assertFalse(handoff.shouldGiveUp(1_000 + 19_999))
        assertTrue(handoff.shouldGiveUp(1_000 + 20_000))
    }

    @Test fun `the hint comes earlier when there is no picture on screen at all`() {
        val handoff = FullscreenHandoff()
        handoff.begin(1_000)
        assertEquals(1_700L, handoff.hintAt())
        assertFalse(handoff.showHint(1_000 + 699))
        assertTrue(handoff.showHint(1_000 + 700))
        // 出了画面就不需要这句提示了。
        handoff.firstFrame(1_000 + 900)
        assertEquals(0L, handoff.hintAt())
        assertFalse(handoff.showHint(1_000 + 1_200))
    }

    @Test fun `a cover on screen earns a longer quiet window before the hint`() {
        val handoff = FullscreenHandoff()
        handoff.begin(1_000)
        handoff.coverArrived(1_100)
        // 这时候等着的是播放地址，不是缺一张图：别急着在海报上叠一行字。
        assertEquals(4_000L, handoff.hintAt())
        assertFalse(handoff.showHint(1_000 + 2_999))
        assertTrue(handoff.showHint(1_000 + 3_000))
    }

    @Test fun `begin starts a clean round`() {
        val handoff = FullscreenHandoff()
        handoff.begin(1_000)
        handoff.coverArrived(1_100)
        handoff.failed(1_200)
        assertFalse(handoff.waiting)
        handoff.begin(5_000)
        assertTrue(handoff.waiting)
        assertFalse(handoff.coverReady)
        assertFalse(handoff.handedOff(5_000))
        assertFalse(handoff.shouldGiveUp(5_000))
        assertEquals(5_000 + 900, handoff.shadeAt())
    }
}
