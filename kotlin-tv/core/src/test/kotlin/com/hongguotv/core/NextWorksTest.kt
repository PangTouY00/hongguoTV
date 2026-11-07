package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class NextWorksTest {
    private fun work(id: String,title: String,season: String="",upcoming: Boolean=false)=
        RelatedWork(Series(id,title,"","","全 60 集"),season,upcoming)

    @Test fun seasonNumbersAcceptArabicAndChineseSeasonLabels() {
        assertEquals(2,NextWorks.seasonNumber("第2季"))
        assertEquals(11,NextWorks.seasonNumber("第十一季"))
        assertEquals(20,NextWorks.seasonNumber("第 二十 季"))
        assertNull(NextWorks.seasonNumber("番外"))
        assertNull(NextWorks.seasonNumber(""))
    }

    @Test fun seasonSuffixDigitsAndPunctuationStillCountAsTheSameSeries() {
        assertEquals(NextWorks.familyKey("聚宝仙盆之杂灵根才是真BOSS"),NextWorks.familyKey("聚宝仙盆之杂灵根才是真BOSS 第二季"))
        assertEquals(NextWorks.familyKey("聚宝仙盆之杂灵根才是真BOSS"),NextWorks.familyKey("聚宝仙盆之杂灵根才是真boss2"))
        assertNotEquals(NextWorks.familyKey("聚宝仙盆之杂灵根才是真BOSS"),NextWorks.familyKey("仙盆杂灵根"))
    }

    @Test fun laterSeasonsComeFirstThenReservedThenSimilarWorks() {
        val rows=listOf(
            work("9","另一部短剧","第1季"),
            work("3","聚宝仙盆之杂灵根才是真BOSS 第三季","第3季",upcoming=true),
            work("2","聚宝仙盆之杂灵根才是真BOSS2","第2季"),
            work("8","相似的第二部"),
            work("1","聚宝仙盆之杂灵根才是真BOSS 第一季","第一季"),
        )
        val plan=NextWorks.plan("聚宝仙盆之杂灵根才是真BOSS",rows)
        assertEquals(listOf("1","2"),plan.seasons.map { it.series.id })
        assertEquals(listOf("3"),plan.upcoming.map { it.series.id })
        assertEquals(listOf("9","8"),plan.similar.map { it.series.id })
    }

    @Test fun unreadableSeasonOrderKeepsTheSourceOrderInsteadOfGuessing() {
        val plan=NextWorks.plan("长夜难明",listOf(work("b","长夜难明 下",""),work("a","长夜难明 上","")))
        assertEquals(listOf("b","a"),plan.seasons.map { it.series.id })
        assertTrue(plan.similar.isEmpty())
    }

    @Test fun unrelatedTitlesFallIntoSimilarWorksRatherThanDisappearing() {
        val plan=NextWorks.plan("长夜难明",listOf(work("x","沉默的真相")))
        assertTrue(plan.seasons.isEmpty()); assertTrue(plan.upcoming.isEmpty())
        assertEquals(listOf("x"),plan.similar.map { it.series.id })
        assertFalse(plan.isEmpty)
    }

    @Test fun emptyTitleStillOffersPlayableWorksAndSimilarListIsBounded() {
        val many=(1..12).map { work("$it","无关剧$it") }
        val plan=NextWorks.plan("",many)
        assertTrue(plan.seasons.isEmpty())
        assertEquals(6,plan.similar.size)
    }
}
