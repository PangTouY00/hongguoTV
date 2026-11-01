// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class UnwatchedEpisodesTest {
    @Test fun noProgressMeansNothingHasBeenWatched() {
        assertEquals(listOf(0,1,2), UnwatchedEpisodes.indexes(3, null))
        assertEquals(emptyList<Int>(), UnwatchedEpisodes.indexes(0, null))
    }

    @Test fun everythingAfterTheWatchedEpisodeIsStillUnwatched() {
        // 看到第 144 集（下标 143）：未看的是它之后的 19 集，这一集本身算看过。
        assertEquals(listOf(144,145,146,147,148,149,150,151,152,153,154,155,156,157,158,159,160,161,162),
            UnwatchedEpisodes.indexes(163, 143))
    }

    @Test fun watchingTheLastEpisodeLeavesNothingToCache() {
        assertEquals(emptyList<Int>(), UnwatchedEpisodes.indexes(163, 162))
    }

    @Test fun aProgressPointingOffTheListTreatsTheWholeSeriesAsUnwatched() {
        // 分集列表变短或进度里的分集已不在列表里：按没看过处理，宁可多下一遍也别漏。
        assertEquals(listOf(0,1), UnwatchedEpisodes.indexes(2, -1))
    }

    @Test fun aNegativePositionOtherThanUnknownIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { UnwatchedEpisodes.indexes(5, -2) }
        assertThrows(IllegalArgumentException::class.java) { UnwatchedEpisodes.indexes(-1, null) }
    }
}
