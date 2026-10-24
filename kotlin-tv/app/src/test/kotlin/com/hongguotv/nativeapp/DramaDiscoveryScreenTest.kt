package com.hongguotv.nativeapp

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26],qualifiers="w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class DramaDiscoveryScreenTest {
    private fun get(target: Any,name: String)=target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
    private fun set(target: Any,name: String,value: Any?) { target.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(target,value) }
    private fun texts(view: View): List<TextView> = if(view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else if(view is TextView) listOf(view) else emptyList()
    private fun pump(activity: MainActivity) { (get(activity,"workJob") as? Future<*>)?.get(5,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();layout(activity) }
    private fun layout(activity: MainActivity) {
        val decor=activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));decor.layout(0,0,960,540)
        ShadowLooper.idleMainLooper()
    }
    private fun response(request: Request): String {
        val searching=request.url.encodedPath=="/api/search/result"
        val page=request.url.queryParameter("page")?.toInt() ?: 1
        val pieces=request.url.pathSegments
        val type=pieces.getOrNull(1) ?: "comic-drama"
        val topic=pieces.getOrNull(2).orEmpty()
        val id=if(searching) "900" else (100+page).toString()
        val rows=JSONArray().put(JSONObject().put("series_id",id).put("series_name",if(searching) "搜索结果剧" else "分类第$page 页"))
        val section=if(searching) JSONObject().put("hasMore",false).put("searchList",rows)
        else JSONObject().put("isSuccess",true).put("recommendList",rows)
            .put("categoryRoute",JSONObject().put("contentType",type).put("enabled",true).put("canonicalPath",request.url.encodedPath))
            .put("selectorList",JSONArray().put(JSONObject().put("items",JSONArray().put(JSONObject().put("selector_item_id",if(type=="real-drama") "romance" else "fantasy").put("show_name",if(type=="real-drama") "爱情" else "玄幻")))))
            .put("pagination",JSONObject().put("pageNum",page).put("totalPages",2).put("pageSize",24).put("total",48))
        if(searching) return section.toString()
        return "<script>_ROUTER_DATA="+JSONObject().put("loaderData",JSONObject().put(if(searching) "search_page" else "category_$",section))+";</script>"
    }
    @Test fun browseSearchAndReturnPreserveServerCategoryPageAndFocus() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create();val activity=controller.get()
        try {
            val library=get(activity,"library") as Library
            (get(library,"loader") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS)
            val account=get(activity,"cloudAccount") as AccountController
            (get(account,"worker") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            val requests=java.util.concurrent.CopyOnWriteArrayList<Request>()
            val failNext=java.util.concurrent.atomic.AtomicBoolean(false)
            set(activity,"repository",ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
                val request=chain.request();requests+=request
                if(failNext.compareAndSet(true,false)) throw java.io.IOException("测试断网")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(response(request).toResponseBody()).build()
            }.build()))
            set(activity,"foreground",true)
            MainActivity::class.java.getDeclaredMethod("switchTab",Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,1)
            pump(activity)
            val root=activity.findViewById<View>(android.R.id.content)
            assertTrue(texts(root).any { it.text.toString()=="找剧" })
            assertTrue(texts(root).any { it.text.toString()=="分类第1 页" })
            val initial=get(activity,"continuousCatalog") as ContinuousCatalogView
            assertTrue("Poster ${initial.grid.getChildAt(0).height} fits the TV list viewport ${initial.grid.height}",initial.grid.getChildAt(0).height<=initial.grid.height)
            texts(root).single { it.text.toString().startsWith("题材：") }.performClick()
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            texts(dialog.window!!.decorView).single { it.text.toString()=="玄幻" }.performClick();pump(activity)
            assertEquals("/category/comic-drama/fantasy",requests.last().url.encodedPath)
            failNext.set(true)
            texts(root).single { it.text.toString()=="加载更多" }.performClick();pump(activity)
            assertEquals(1,get(activity,"page"))
            assertTrue(texts(root).any { it.text.toString()=="分类第1 页" })
            texts(root).single { it.text.toString()=="重试加载" }.performClick();pump(activity)
            assertEquals(2,get(activity,"page"))
            val browser=get(activity,"continuousCatalog") as ContinuousCatalogView
            browser.grid.setSelection(1);browser.enter();layout(activity)
            assertEquals("before search selection","102",browser.capture()?.id)
            val input=get(activity,"searchInput") as EditText
            assertNotEquals(browser.grid.id,input.nextFocusDownId)
            input.setText("大佬")
            texts(root).single { it.text.toString()=="搜索" }.performClick();pump(activity)
            assertTrue(texts(root).any { it.text.toString()=="搜索结果剧" })
            assertFalse(texts(root).any { it.text.toString().startsWith("题材：") })
            assertEquals("saved category selection","102",(get(activity,"discovery") as DramaDiscoveryState).browse?.focus)
            val count=requests.size
            texts(root).single { it.text.toString()=="返回分类" }.performClick()
            assertEquals(count,requests.size)
            assertEquals("",get(activity,"query"));assertEquals(2,get(activity,"page"))
            assertEquals("fantasy",(get(activity,"discovery") as DramaDiscoveryState).topic)
            layout(activity)
            assertTrue(texts(root).any { it.text.toString()=="分类第2 页" })
            val restored=get(activity,"continuousCatalog") as ContinuousCatalogView
            assertTrue(restored.grid.hasFocus());assertEquals("102",restored.capture()?.id)
            assertEquals(2,restored.grid.adapter.count) // Both pages remain in the list after returning.
            texts(root).single { it.text.toString()=="真人剧" }.performClick();pump(activity)
            assertEquals("/category/real-drama",requests.last().url.encodedPath)
            assertEquals("",(get(activity,"discovery") as DramaDiscoveryState).topic)
            assertEquals(listOf("爱情"),(get(activity,"discovery") as DramaDiscoveryState).info!!.topics.map { it.label })
        } finally { controller.destroy() }
    }
}
