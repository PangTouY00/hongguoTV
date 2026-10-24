package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26],qualifiers="w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RankingScreenTest {
    private fun views(view: View): List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));view.layout(0,0,960,540)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }
    @Test fun continuousRowsKeepSourceRanksAndRestoreDetailSelection() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get();val tools=TvTools(activity)
        try {
            val state=RankBrowserState()
            val items=(1..25).map { Series(it.toString(),"剧$it",tags=if(it==25) "系统" else "穿越") }
            state.snapshots[RankBoard.ALL]=RankSnapshot(RankBoard.ALL,items,ComicRanking(items.associate { it.id to RankPosition(it.id.toInt()+10,"100万热度") },2,"今日已更新"))
            val nav=(0..7).map { TextView(activity).apply { id=View.generateViewId();isFocusable=true } }
            var opened="";var longPressed=""
            val screen=RankingScreen(activity,state,nav,tools,{ _,_-> },{ opened=it.id },{ item,_->longPressed=item.id },{ _,_->fail("cached board fetched") })
            activity.setContentView(FrameLayout(activity).apply { addView(screen) })
            screen.start(false,false);layout(screen)
            val browser=views(screen).filterIsInstance<ContinuousCatalogView>().single()
            browser.enter();layout(screen)
            val first=browser.grid.getChildAt(0)
            browser.grid.performItemClick(first,0,browser.grid.adapter.getItemId(0));assertEquals("1",opened);assertEquals("1",state.selectedId)
            browser.grid.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_MENU));assertEquals("1",longPressed)
            val allViews=views(screen)
            assertTrue(allViews.filterIsInstance<TextView>().any { it.text.toString()=="11" })
            assertNotEquals(View.NO_ID,browser.grid.nextFocusUpId)
            assertEquals(25,browser.grid.adapter.count)
            browser.grid.setSelection(20);layout(screen)
            assertEquals("21",browser.capture()?.id);assertTrue(browser.grid.hasFocus())
            assertTrue(browser.grid.childCount<25)
            val returned=RankingScreen(activity,state,nav,tools,{ _,_-> },{}, { _,_-> }, { _,_->fail("return fetched cached board") })
            activity.setContentView(returned);returned.start(false,false);layout(returned)
            val restored=views(returned).filterIsInstance<ContinuousCatalogView>().single()
            assertEquals("21",restored.capture()?.id)
            views(returned).filterIsInstance<TextView>().single { it.text.toString()=="筛选题材" }.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            views(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString()=="系统 · 1 部" }.performClick()
            assertEquals("系统",state.topic);assertEquals(1,state.page)
            assertFalse(dialog.isShowing)
            val filtered=RankingScreen(activity,state,nav,tools,{ _,_-> },{}, { _,_-> }, { _,_->fail("unexpected fetch") })
            activity.setContentView(filtered);filtered.start(false,false);layout(filtered)
            assertNotNull(filtered.findViewWithTag<View>("25"));assertNull(filtered.findViewWithTag<View>("1"))
            assertTrue(views(filtered).filterIsInstance<TextView>().any { it.text.toString()=="35" })
        } finally { tools.destroy();controller.pause().stop().destroy() }
    }
    @Test fun failedRefreshRetainsLastSuccessfulBoard() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get();val tools=TvTools(activity)
        try {
            val state=RankBrowserState();state.snapshots[RankBoard.ALL]=RankSnapshot(RankBoard.ALL,listOf(Series("1","已有作品")),ComicRanking(emptyMap(),1,""))
            val nav=(0..7).map { TextView(activity).apply { id=View.generateViewId();isFocusable=true } }
            val screen=RankingScreen(activity,state,nav,tools,{ _,_-> },{}, { _,_-> }, { _,done->done(null,"离线") })
            activity.setContentView(screen);screen.start(true,false);layout(screen)
            assertNotNull(screen.findViewWithTag<View>("1"))
            assertTrue(views(screen).filterIsInstance<TextView>().any { it.text.contains("保留上次榜单") })
        } finally { tools.destroy();controller.pause().stop().destroy() }
    }
}
