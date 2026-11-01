package com.hongguotv.nativeapp

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.BackupData
import com.hongguotv.core.BackupSettings
import com.hongguotv.core.EpisodeCache
import com.hongguotv.core.LibraryIndex
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
class FavoritesDownloadsTest {
    private fun get(target: Any,name: String)=target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
    private fun texts(view: View): List<TextView> = if(view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else if(view is TextView) listOf(view) else emptyList()
    private fun method(activity: MainActivity,name: String,value: Int) = MainActivity::class.java.getDeclaredMethod(name,Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,value)
    /** `by lazy` 委托在字节码里叫 <属性名>$delegate。 */
    private fun cacheOf(activity: MainActivity): EpisodeCache = (get(activity,"episodeCache\$delegate") as Lazy<*>).value as EpisodeCache
    /** 本机列表走的是持久化索引；测试里直接给一个空的，不去碰真实存储。 */
    private fun emptyLibrary(activity: MainActivity) {
        val library=get(activity,"library") as Library
        Library::class.java.getDeclaredField("index").apply { isAccessible=true }.set(library,LibraryIndex(BackupData(emptyList(),emptyList(),emptySet(),emptyList(),BackupSettings())))
    }

    @Test fun theLibraryTabOffersThreeSeparateSourcesUnderOneName() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        try {
            emptyLibrary(activity)
            method(activity,"switchTab",3)
            val root=activity.findViewById<View>(android.R.id.content)
            assertTrue("导航与页面标题都应叫「收藏下载」",texts(root).filter { it.text.toString()=="收藏下载" }.size>=2)
            listOf("本机收藏","账户收藏","本机下载").forEach { label ->
                assertTrue("缺少来源切换项 $label",texts(root).any { it.text.toString()==label })
            }
        } finally { controller.destroy() }
    }

    @Test fun theDownloadsSourceShowsCachedEpisodesGroupedBySeriesAndKeepsThemOutOfTheFavoritesList() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        try {
            emptyLibrary(activity)
            val cache=cacheOf(activity)
            val bytes=ByteArray(2048) { (it % 251).toByte() }
            cache.store("910",720,"avc1",2048L,read={ _,length -> bytes.copyOf(length) },seriesId="s-91",seriesTitle="已下载测试剧")
            cache.store("911",720,"avc1",1024L,read={ _,length -> bytes.copyOf(length) },seriesId="s-91",seriesTitle="已下载测试剧")

            method(activity,"switchTab",3)
            val root=activity.findViewById<View>(android.R.id.content)
            assertFalse("本机收藏里不该出现只下载没收藏的剧",texts(root).any { it.text.toString()=="已下载测试剧" })

            method(activity,"switchLibrarySource",2)
            assertTrue(texts(root).any { it.text.toString()=="已下载测试剧" })
            assertTrue("卡片上要写清缓存了几集",texts(root).any { it.text.toString().contains("已缓存 2 集") })

            method(activity,"switchLibrarySource",0)
            assertFalse(texts(root).any { it.text.toString()=="已下载测试剧" })
        } finally { controller.destroy() }
    }
}
