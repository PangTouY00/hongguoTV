package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** 「缓存未看」这一排按钮：它排的是哪些集、都看过时它说什么。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26],qualifiers="w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class EpisodePanelUnwatchedTest {
    private fun texts(view: View): List<TextView> = if(view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else if(view is TextView) listOf(view) else emptyList()
    private fun dialogOf(panel: EpisodePanel): Dialog =
        EpisodePanel::class.java.getDeclaredField("dialog").apply { isAccessible=true }.get(panel) as Dialog

    private fun open(activity: Activity,count: Int,current: Int,lastWatched: Int?,requested: MutableList<List<Int>>): EpisodePanel {
        val panel=EpisodePanel(activity,count,current,{ _,_,_ -> },{ },{ },
            lastWatched=lastWatched,lastCompleted=false,
            cache=EpisodeCacheUi(cacheUnwatched={ requested.add(it) }))
        panel.show()
        return panel
    }

    @Test fun thePanelOffersToCacheOnlyWhatHasNotBeenWatchedYet() {
        val controller=Robolectric.buildActivity(Activity::class.java).create()
        val requested=mutableListOf<List<Int>>()
        try {
            val panel=open(controller.get(),163,143,143,requested)
            val labels=texts(dialogOf(panel).findViewById(android.R.id.content)).map { it.text.toString() }
            assertTrue("没有「缓存未看」按钮：$labels",labels.any { it=="缓存未看 19 集" })
            assertTrue("原有的两个入口都还在",labels.containsAll(listOf("缓存本集","缓存全部")))
            texts(dialogOf(panel).findViewById(android.R.id.content)).first { it.text.toString()=="缓存未看 19 集" }.performClick()
            assertEquals("排的是第 145 到 163 集的下标",listOf((144..162).toList()),requested)
        } finally { controller.destroy() }
    }

    @Test fun whenTheLastEpisodeIsAlreadyWatchedTheButtonSaysSoAndCachesNothing() {
        val controller=Robolectric.buildActivity(Activity::class.java).create()
        val requested=mutableListOf<List<Int>>()
        try {
            val panel=open(controller.get(),163,162,162,requested)
            val root=dialogOf(panel).findViewById<View>(android.R.id.content)
            val labels=texts(root).map { it.text.toString() }
            assertTrue("没有说明没有未看的集：$labels",labels.any { it=="没有未看的集" })
            texts(root).first { it.text.toString()=="没有未看的集" }.performClick()
            assertTrue("没有未看的集时不该排任何东西",requested.isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun withoutAnyWatchedEpisodeTheWholeSeriesCountsAsUnwatched() {
        val controller=Robolectric.buildActivity(Activity::class.java).create()
        val requested=mutableListOf<List<Int>>()
        try {
            val panel=open(controller.get(),12,0,null,requested)
            val root=dialogOf(panel).findViewById<View>(android.R.id.content)
            assertTrue(texts(root).any { it.text.toString()=="缓存未看 12 集" })
            texts(root).first { it.text.toString()=="缓存未看 12 集" }.performClick()
            assertEquals(listOf((0..11).toList()),requested)
        } finally { controller.destroy() }
    }
}
