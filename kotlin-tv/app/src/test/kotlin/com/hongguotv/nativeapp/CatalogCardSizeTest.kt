// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.util.DisplayMetrics
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
 * 观看历史这类目录页的海报网格。
 *
 * 槽位尺寸只由屏幕推导，不读任何一张封面的比例：同一行必须等高，而且切走再回来、封面一张张到达之后
 * 都不应该改变。之前海报高度跟着「网站实际送来的封面比例」走，同一页里既有横 banner 又有竖海报时，
 * 整页卡片会被挤成一条矮横带。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26],qualifiers="w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CatalogCardSizeTest {
    @Test fun historyRowKeepsEveryPosterTheSameHeightAcrossATabSwitch() {
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

            // 切到顶部标签再回来：同一行仍然等高，高度也不会因为这一页看到了什么封面而变。
            switchTab(activity,0)
            openHistory(activity)
            val second=posterHeights(activity)
            assertTrue(second.values.all { it.distinct().size==1 })
            assertEquals(first.values.flatten().first(), second.values.flatten().first())
        } finally {
            controller.destroy()
        }
    }

    @Test fun posterHeightComesFromTheColumnWidthAndNotFromAnyCover() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        try {
            val library=field(activity,"library") as Library
            val history=(1..6).map { index ->
                BackupProgress(Series("$index","历史剧$index"),"e$index",index,1000,90000,false,1000L+index)
            }
            setField(library,"index",LibraryIndex(BackupData(emptyList(),history,emptySet(),emptyList(),BackupSettings())))
            openHistory(activity)
            val metrics=activity.resources.displayMetrics
            val gap=GridMetrics.gap(metrics)
            @Suppress("UNCHECKED_CAST")
            val cards=(field(activity,"catalogCards") as Map<String,View>).values.toList()
            val expected=GridMetrics.posterHeight(cards.first().layoutParams.width,gap)
            posterHeights(activity).values.flatten().forEach { actual ->
                assertEquals("海报高度应当等于按列宽推导的固定值", expected, actual)
            }
            assertTrue("海报槽至少和列宽一样高，竖版封面才有完整的显示空间",
                expected>=GridMetrics.cardWidth(metrics,GridMetrics.columns(metrics),gap))
        } finally {
            controller.destroy()
        }
    }

    /** 列数与卡片宽度只由屏幕推导：小屏少列，大屏更大的卡片而不是更多的卡片。 */
    @Test fun columnsAndCardWidthFollowTheScreen() {
        val phone=metrics(1080,2400,440)      // 手机竖屏
        val phoneLandscape=metrics(2400,1080,440)
        val tv1080=metrics(1920,1080,320)
        val tv4k=metrics(3840,2160,320)

        assertEquals(3,GridMetrics.columns(phone))
        assertEquals(6,GridMetrics.columns(phoneLandscape))
        assertEquals(6,GridMetrics.columns(tv1080))
        assertEquals(6,GridMetrics.columns(tv4k))

        val narrow=GridMetrics.cardWidth(phone,GridMetrics.columns(phone),GridMetrics.gap(phone))
        val wide=GridMetrics.cardWidth(tv4k,GridMetrics.columns(tv4k),GridMetrics.gap(tv4k))
        assertTrue("4K 电视上的卡片应当比手机上的更大，实际 $narrow → $wide", wide>narrow)

        // 槽位比例固定：同一列宽在任何屏幕上都给出同样的「宽:高」，不随封面变化。
        val gap=GridMetrics.gap(tv1080)
        val card=GridMetrics.cardWidth(tv1080,GridMetrics.columns(tv1080),gap)
        assertEquals((card/TvStyle.POSTER_ASPECT).toInt().toLong(),GridMetrics.posterHeight(card,gap).toLong())
    }

    private fun metrics(width: Int,height: Int,densityDpi: Int): DisplayMetrics {
        val metrics=DisplayMetrics()
        metrics.widthPixels=width; metrics.heightPixels=height
        metrics.densityDpi=densityDpi; metrics.density=densityDpi/160f; metrics.scaledDensity=metrics.density
        return metrics
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
