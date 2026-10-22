// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.*
import java.io.Closeable
import java.util.concurrent.Executors

/** A separate trial player. Never calls Library.save or AccountController.upload. */
internal class HomeFeedScreen(
    private val activity: Activity,private val state: DramaFeedState,private val nav: List<View>,
    private val watch: (Series)->Unit,private val favorite: (Series,View)->Unit,
    private val favoriteLabel: (String)->String,
    private val backend: FeedBackend,private val playback: TrialPlayback
): LinearLayout(activity),Closeable {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newFixedThreadPool(2)
    private var generation=0
    private var scope: RequestScope?=null
    private var job: java.util.concurrent.Future<*>?=null
    private var prefetchScope: RequestScope?=null
    private var prefetchJob: java.util.concurrent.Future<*>?=null
    private var nextDetail: Detail?=null
    private var clip: TrialClip?=null
    private var closed=false
    private var active=false
    private var loading=false
    private var failed=false
    private val stage=FrameLayout(activity)
    private val title=label("发现下一部好剧",25f)
    private val meta=label("第一集试播",13f,TvStyle.accent)
    private val intro=label("喜欢这部作品，就选择观看全集。",15f,TvStyle.muted)
    private val status=label("正在准备热门作品…",15f)
    private val tag=label("热门发现",12f,TvStyle.accent)
    private val actions=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
    private val buttons=mutableListOf<TextView>()
    private val pauseButton: TextView
    private val watchButton: TextView
    private lateinit var favoriteButton: TextView
    private val kindButton: TextView
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float,color: Int=TvStyle.text)=TextView(activity).apply {
        text=value;textSize=size;setTextColor(color);includeFontPadding=false
    }
    private fun lp(w: Int=-1,h: Int=-2)=LayoutParams(w,h)
    private fun button(value: String,action: ()->Unit)=label(value,14f).apply {
        id=View.generateViewId();isFocusable=true;isFocusableInTouchMode=true;isClickable=true
        gravity=Gravity.CENTER;minHeight=dp(44);setPadding(dp(10),dp(10),dp(10),dp(10))
        background=TvStyle.card(activity,false)
        setOnFocusChangeListener { _,focused -> background=TvStyle.card(activity,focused) }
        setOnClickListener { action() }
    }
    init {
        orientation=VERTICAL
        val content=LinearLayout(activity)
        stage.id=View.generateViewId();stage.isFocusable=true;stage.isFocusableInTouchMode=true;stage.isClickable=true
        stage.setPadding(dp(3),dp(3),dp(3),dp(3));stage.background=TvStyle.card(activity,false)
        stage.addView(playback.view,FrameLayout.LayoutParams(-1,-1))
        tag.setPadding(dp(12),dp(8),dp(12),dp(8));tag.background=TvStyle.shape(activity,Color.argb(210,11,15,21))
        stage.addView(tag,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply { setMargins(dp(10),dp(10),dp(10),0) })
        status.gravity=Gravity.CENTER;status.setPadding(dp(14),dp(12),dp(14),dp(12));status.background=TvStyle.shape(activity,Color.argb(215,11,15,21))
        stage.addView(status,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { setMargins(dp(10),0,dp(10),dp(10)) })
        content.addView(stage,lp(0,-1).apply { weight=2.2f;rightMargin=dp(22) })
        val info=LinearLayout(activity).apply { orientation=VERTICAL;setPadding(0,dp(14),0,dp(10)) }
        title.typeface=Typeface.DEFAULT_BOLD;title.maxLines=3;title.ellipsize=TextUtils.TruncateAt.END
        info.addView(title)
        meta.maxLines=2;meta.setPadding(0,dp(12),0,dp(12));info.addView(meta)
        intro.maxLines=6;intro.ellipsize=TextUtils.TruncateAt.END
        info.addView(intro)
        info.addView(Space(activity),lp(1,0).apply { weight=1f })
        info.addView(label("↑ ↓  换一部\n确定  打开操作\n返回  顶部导航",13f,TvStyle.muted).apply { setLineSpacing(dp(6).toFloat(),1f) })
        content.addView(info,lp(0,-1).apply { weight=1f })
        addView(content,lp(-1,0).apply { weight=1f })
        pauseButton=button("暂停试播") { toggle() }
        watchButton=button("观看全集") { state.current?.let { remember();pause();watch(it) } }
        favoriteButton=button("账户收藏") {
            state.current?.let { state.userPaused=true;playback.pause();remember();paintActions();favorite(it,favoriteButton) }
        }
        kindButton=button("类型：漫剧") { changeType() }
        buttons+=listOf(pauseButton,watchButton,favoriteButton,kindButton)
        buttons.forEachIndexed { index,b ->
            actions.addView(b,lp(0,dp(44)).apply { weight=1f;if(index<buttons.lastIndex) rightMargin=dp(10) })
            b.nextFocusLeftId=buttons.getOrNull(index-1)?.id ?: b.id
            b.nextFocusRightId=buttons.getOrNull(index+1)?.id ?: b.id
            b.nextFocusUpId=stage.id;b.nextFocusDownId=b.id
        }
        actions.setPadding(0,dp(10),0,0);actions.visibility=View.GONE;addView(actions)
        stage.nextFocusLeftId=stage.id;stage.nextFocusRightId=stage.id
        stage.nextFocusUpId=stage.id;stage.nextFocusDownId=stage.id
        nav.forEach { it.nextFocusDownId=stage.id }
        stage.setOnFocusChangeListener { _,focused -> stage.background=TvStyle.card(activity,focused) }
        stage.setOnClickListener { showActions() }
        stage.setOnKeyListener { _,key,event ->
            when(key) {
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) move(if(key==KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> { if(event.action==KeyEvent.ACTION_UP) showActions();true }
                else -> false
            }
        }
        playback.status={ value ->
            if(!closed && clip!=null) when(value) {
                TrialStatus.BUFFERING -> status.text="正在加载第一集…"
                TrialStatus.READY -> { failed=false;status.text=if(state.userPaused) "试播已暂停" else "第一集试播 · 喜欢就看全集";paintActions() }
                TrialStatus.ENDED -> {
                    remember();state.ended=true;state.userPaused=true
                    status.text="本集试播结束 · 看全集或按 ↓ 换一部";paintActions()
                    if(active && stage.hasFocus()) showActions()
                }
                TrialStatus.ERROR -> { playback.pause();failed=true;status.text="试播暂不可用 · 可重试、换剧或查看全集";paintActions() }
            }
        }
        describe();paintActions()
    }
    fun start(foreground: Boolean,focusNav: Boolean) {
        if(focusNav) nav[0].requestFocus() else stage.requestFocus()
        if(foreground) resume()
    }
    fun resume() {
        if(closed || active) return
        active=true
        if(clip!=null) { if(!state.userPaused && !state.ended) playback.resume();paintActions() }
        else if(state.current!=null) loadCurrent() else loadPage(false)
    }
    fun pause() {
        if(closed) return
        remember();active=false;playback.pause();cancelRequest();cancelPrefetch();paintActions()
    }
    fun back(): Boolean {
        if(actions.visibility!=View.VISIBLE) return false
        actions.visibility=View.GONE;stage.requestFocus();return true
    }
    fun refreshAccountLabel() { if(!closed) paintActions() }
    private fun showActions() {
        actions.visibility=View.VISIBLE;paintActions()
        (if(watchButton.isEnabled) watchButton else pauseButton).requestFocus()
    }
    private fun paintActions() {
        pauseButton.text=when { failed || state.current==null -> "重试试播";state.ended -> "重新试播";state.userPaused -> "继续试播";else -> "暂停试播" }
        watchButton.isEnabled=state.current!=null;favoriteButton.isEnabled=state.current!=null
        favoriteButton.text=state.current?.let { favoriteLabel(it.id) } ?: "账户收藏"
        kindButton.text="类型：${state.category.label}"
        stage.keepScreenOn=active && clip!=null && !state.userPaused && !state.ended && !failed
    }
    private fun describe() {
        val series=state.current
        title.text=series?.title ?: "发现下一部好剧"
        meta.text=if(series==null) "第一集试播" else listOf("第一集试播",series.badge,series.tags).filter(String::isNotBlank).joinToString(" · ")
        intro.text=series?.description?.ifBlank { "先试试看，喜欢这部作品就选择观看全集。" } ?: "选择漫剧或真人剧，上下换一部，找到想追的故事。"
        tag.text="热门发现 · ${state.category.label}"
        paintActions()
    }
    private fun remember() { if(clip!=null) state.position=playback.position() }
    private fun cancelRequest() { generation++;scope?.cancel();scope=null;job?.cancel(true);job=null;loading=false }
    private fun cancelPrefetch() { prefetchScope?.cancel();prefetchScope=null;prefetchJob?.cancel(true);prefetchJob=null }
    private fun clearCurrent() { remember();cancelRequest();cancelPrefetch();playback.clear();clip?.close();clip=null }
    private fun <T> request(action: ()->T,discard: (T)->Unit={},done: (T)->Unit) {
        cancelRequest();loading=true;val token=generation;val requestScope=RequestScope();scope=requestScope
        job=worker.submit {
            try {
                val result=requestScope.run(action)
                main.post { if(closed || token!=generation || !active) discard(result) else { loading=false;done(result) } }
            } catch(error: Exception) {
                main.post {
                    if(closed || token!=generation || !active) return@post
                    loading=false;failed=true
                    val message=error.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(90)
                    status.text="暂时无法试播 · ${message.ifBlank { "请重试或换一部" }}";paintActions()
                }
            }
        }
    }
    private fun loadPage(advance: Boolean) {
        if(!active || closed || loading) return
        if(state.page>=100 || (!state.hasMore && state.current!=null)) { status.text="本批作品已看完，可切换类型继续发现";return }
        status.text="正在读取热门作品…"
        val page=state.page+1;val category=state.category
        request({ backend.catalog(category,page) }) { result ->
            state.append(result,page)
            if(advance) state.move(1)
            if(state.current==null) { status.text="暂时没有可试播的作品，可切换类型或重试";failed=true;paintActions() }
            else loadCurrent()
        }
    }
    private fun loadCurrent() {
        val series=state.current ?: return
        if(!active || closed) return
        clearCurrent();failed=false;describe();status.text="正在准备第一集试播…"
        val cached=nextDetail?.takeIf { it.series.id==series.id };nextDetail=null
        request({ backend.prepare(cached ?: backend.detail(series)) },{ it.close() }) { next ->
            clip=next
            val count=next.detail.episodes.size
            meta.text="第一集试播 · 共 $count 集"+(series.tags.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            if(next.detail.series.description.isNotBlank()) intro.text=next.detail.series.description
            playback.load(next,state.position,active && !state.userPaused && !state.ended)
            paintActions();prefetchMetadata()
        }
    }
    private fun prefetchMetadata() {
        cancelPrefetch()
        val next=state.next ?: return
        val token=generation;val requestScope=RequestScope();prefetchScope=requestScope
        prefetchJob=worker.submit {
            val detail=runCatching { requestScope.run { backend.detail(next) } }.getOrNull() ?: return@submit
            main.post { if(!closed && active && token==generation) nextDetail=detail }
        }
    }
    private fun move(delta: Int) {
        if(closed || !active) return
        if(delta<0 && state.index==0) { status.text="已到本次浏览的最早作品";return }
        if(delta>0 && state.next==null) {
            if(loading) return
            if(!state.hasMore) { status.text="本批作品已看完，可切换类型继续发现";return }
            clearCurrent();loadPage(true);return
        }
        clearCurrent()
        if(state.move(delta)) loadCurrent()
    }
    private fun toggle() {
        if(loading) return
        if(failed || state.current==null || clip==null) {
            if(state.current==null) state.select(state.category)
            state.userPaused=false;state.ended=false
            if(state.current==null) loadPage(false) else loadCurrent()
            return
        } else if(state.ended) { state.position=0;state.ended=false;state.userPaused=false;playback.restart() }
        else { state.userPaused=!state.userPaused;if(state.userPaused) { remember();playback.pause() } else if(active) playback.resume() }
        status.text=if(state.userPaused) "试播已暂停" else "第一集试播 · 喜欢就看全集";paintActions()
    }
    private fun changeType() {
        clearCurrent();state.select(if(state.category==DramaCategory.COMIC) DramaCategory.REAL else DramaCategory.COMIC)
        nextDetail=null;actions.visibility=View.GONE;stage.requestFocus();describe();loadPage(false)
    }
    override fun close() {
        if(closed) return
        clearCurrent();closed=true;active=false;stage.keepScreenOn=false
        playback.release();backend.close();worker.shutdownNow()
    }
}
