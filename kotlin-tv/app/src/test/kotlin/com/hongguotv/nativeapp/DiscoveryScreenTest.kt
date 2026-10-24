// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26],qualifiers="w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class DiscoveryScreenTest {
    private fun views(view: View): List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private val groups=listOf(DiscoveryGroup("genre","类型",listOf(DiscoveryFilter("short_play","真人剧"))),
        DiscoveryGroup("category_dim_role","设定",listOf(DiscoveryFilter("cate_36","重生"))),
        DiscoveryGroup("category_dim_epoch","背景",listOf(DiscoveryFilter("cate_1","都市"))))
    private fun page(id: String)=FilteredDiscoveryPage(listOf(Series(id,"作品$id")),groups,null)
    @Test fun newerFilterRequestWinsAndClosedPageIgnoresCallbacks() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val callbacks=mutableListOf<(FilteredDiscoveryPage?,Throwable?)->Unit>()
        val state=DiscoveryBrowserState();val upper=TextView(activity).apply { id=View.generateViewId();isFocusable=true }
        val screen=DiscoveryScreen(activity,state,upper,{ _,_-> },{}, { _,_-> },{ _,_,done -> callbacks+=done })
        try {
            activity.setContentView(screen);screen.start(false);callbacks[0](page("1"),null)
            views(screen).filterIsInstance<TextView>().single { it.text.toString()=="重生" }.performClick()
            views(screen).filterIsInstance<TextView>().single { it.text.toString()=="都市" }.performClick()
            assertEquals(3,callbacks.size)
            callbacks[1](page("2"),null);assertTrue(state.items.isEmpty())
            callbacks[2](page("3"),null);assertEquals(listOf("3"),state.items.map { it.id })
            assertEquals(mapOf("category_dim_role" to "cate_36","category_dim_epoch" to "cate_1"),state.selection.values)
            views(screen).filterIsInstance<TextView>().single { it.text.toString()=="刷新" }.performClick()
            screen.close();callbacks[3](page("4"),null)
            assertEquals(listOf("3"),state.items.map { it.id })
        } finally { screen.close();controller.pause().stop().destroy() }
    }
    @Test fun gridEntryCollapsesPanelAndEmptyResultsCanBeReset() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val state=DiscoveryBrowserState().apply { loaded=true;items=page("1").items;groups=this@DiscoveryScreenTest.groups }
        val upper=TextView(activity).apply { id=View.generateViewId();isFocusable=true }
        val screen=DiscoveryScreen(activity,state,upper,{ _,_-> },{}, { _,_-> },{ _,_,done -> done(FilteredDiscoveryPage(emptyList(),groups,null),null) })
        try {
            activity.setContentView(screen)
            screen.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(420,View.MeasureSpec.EXACTLY));screen.layout(0,0,960,420);ShadowLooper.idleMainLooper()
            val browser=views(screen).filterIsInstance<ContinuousCatalogView>().single()
            browser.enter();assertFalse(state.expanded)
            views(screen).filterIsInstance<TextView>().single { it.text.toString()=="展开筛选" }.performClick();assertTrue(state.expanded)
            views(screen).filterIsInstance<TextView>().single { it.text.toString()=="重置" }.performClick()
            assertEquals(0,browser.grid.adapter.count);assertTrue(state.loaded)
            assertTrue(state.selection.values.isEmpty())
        } finally { screen.close();controller.pause().stop().destroy() }
    }
}
