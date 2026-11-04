// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
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

/**
 * 观看历史这类目录页的海报网格。封面是一张张到达的，任何一处只按"自己那张封面"重测卡片，
 * 都会让同一行里出现两种海报高度，切到顶部标签再回来时尤其明显。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26],qualifiers="w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CatalogCardSizeTest {
    @Test fun historyRowKeepsEveryPosterTheSameHeightAcrossATabSwitch() {
        CoverRatio.reset()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        try {
            val library=field(activity,"library") as Library
            val history=(1..8).map { index ->
                BackupProgress(Series("$index","历史剧$index"),"e$index",index,1000,90000,false,1000L+index)
            }
            setField(library,"index",LibraryIndex(BackupData(emptyList(),history,emptySet(),emptyList(),BackupSettings())))
            openHistory(activity)
            val first=posterHeights(activity)
            assertTrue("历史页应当渲染出整行海报卡片，实际只有 ${first.values.flatten().size} 张", first.values.flatten().size>=6)
            assertTrue("同一行的海报必须等高，实测 $first", first.values.all { it.distinct().size==1 })

            // 别的页面学到了新的封面比例，再切回历史页：整行一起按新比例重测，不能只有一张卡缩下去。
            repeat(4) { index -> CoverRatio.observe("cover$index",Bitmap.createBitmap(180,300,Bitmap.Config.ARGB_8888)) }
            switchTab(activity,0)
            openHistory(activity)
            val second=posterHeights(activity)
            assertTrue(second.values.all { it.distinct().size==1 })
        } finally {
            CoverRatio.reset()
            controller.destroy()
        }
    }
    private fun openHistory(activity: MainActivity) {
        switchTab(activity,4)
    }
    private fun switchTab(activity: MainActivity,tab: Int) {
        MainActivity::class.java.getDeclaredMethod("switchTab",Int::class.javaPrimitiveType)
            .apply { isAccessible=true }.invoke(activity,tab)
        layout(activity)
    }
    /** 每张卡片的第一子节点就是海报框，它的 layoutParams.height 正是用户看到的封面高度。 */
    private fun posterHeights(activity: MainActivity): Map<View,List<Int>> {
        @Suppress("UNCHECKED_CAST")
        val cards=(field(activity,"catalogCards") as Map<String,View>).values.toList()
        return cards.groupBy { it.parent as View }.mapValues { entry ->
            entry.value.map { card -> (card as ViewGroup).getChildAt(0).layoutParams.height }
        }
    }
    private fun field(target: Any,name: String)=target::class.java.getDeclaredField(name).apply { isAccessible=true }.get(target)
    private fun setField(target: Any,name: String,value: Any?)=target::class.java.getDeclaredField(name).apply { isAccessible=true }.set(target,value)
    private fun layout(activity: Activity) {
        val root=activity.findViewById<View>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY))
        root.layout(0,0,960,540)
        ShadowLooper.idleMainLooper()
    }
}
