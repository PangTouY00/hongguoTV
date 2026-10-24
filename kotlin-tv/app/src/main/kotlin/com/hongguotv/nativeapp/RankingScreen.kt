// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.*

/** D-pad-first list: one focus target per work, stable source ranks after filtering. */
internal class RankingScreen(
    private val activity: Activity, private val state: RankBrowserState,
    private val nav: List<View>, private val tools: TvTools,
    private val cover: (ImageView,String)->Unit,
    private val open: (Series)->Unit, private val more: (Series,View)->Unit,
    private val load: (RankBoard,((RankSnapshot?,String?)->Unit))->Unit
): LinearLayout(activity) {
    private val controls=mutableListOf<TextView>()
    private val heading=label("热播排行榜",25f)
    private val subtitle=label("",12f,TvStyle.muted)
    private val body=LinearLayout(activity).apply { orientation=VERTICAL }
    private var filterButton: TextView
    private var refreshButton: TextView
    private var loading=false
    private var browser: ContinuousCatalogView?=null
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=16f,color: Int=TvStyle.text)=TextView(activity).apply {
        text=value; textSize=size; setTextColor(color); includeFontPadding=false
    }
    private fun params(w: Int=-1,h: Int=-2)=LayoutParams(w,h)
    private fun row()=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
    private fun button(value: String,action: ()->Unit)=label(value,15f).apply {
        id=View.generateViewId(); isFocusable=true; isClickable=true; gravity=Gravity.CENTER
        setPadding(dp(15),dp(10),dp(15),dp(10)); minHeight=dp(42)
        fun paint() { setTextColor(if(isSelected) TvStyle.accent else TvStyle.text); background=TvStyle.shape(activity,if(isSelected) android.graphics.Color.rgb(48,32,29) else TvStyle.surface,if(hasFocus()) TvStyle.accent else android.graphics.Color.TRANSPARENT) }
        setOnFocusChangeListener { _,_-> paint() }; setOnClickListener { action() }; paint()
    }
    init {
        orientation=VERTICAL
        val title=row().apply { setPadding(0,dp(8),0,dp(8)) }
        heading.typeface=Typeface.DEFAULT_BOLD
        title.addView(heading,params(0).apply { weight=1f })
        title.addView(label("上下选剧  ·  确定看详情  ·  长按更多",12f,TvStyle.muted))
        addView(title)
        val bar=row()
        RankBoard.entries.forEach { board ->
            val control=button(board.label) {
                if(loading || state.board==board) return@button
                state.select(board); markControls(); render(); if(state.snapshots[board]==null) refresh()
            }.apply { isSelected=state.board==board; nextFocusUpId=nav[2].id }
            bar.addView(control,params().apply { width=dp(92); rightMargin=dp(8) }); controls+=control
        }
        bar.addView(View(activity),params(0,1).apply { weight=1f })
        filterButton=button("筛选题材") { chooseTopic() }.apply { nextFocusUpId=nav[2].id }
        refreshButton=button("刷新") { if(!loading) refresh() }.apply { nextFocusUpId=nav[2].id }
        bar.addView(filterButton,params(-2));bar.addView(refreshButton,params(-2).apply { leftMargin=dp(8) })
        controls+=filterButton;controls+=refreshButton
        controls.forEachIndexed { i,v -> v.nextFocusLeftId=controls.getOrNull(i-1)?.id ?: v.id; v.nextFocusRightId=controls.getOrNull(i+1)?.id ?: v.id }
        nav.forEach { it.nextFocusDownId=controls[state.board.ordinal].id }
        addView(bar)
        subtitle.setPadding(0,dp(9),0,dp(9)); subtitle.maxLines=2
        addView(subtitle)
        addView(body,params(-1,0).apply { weight=1f })
        markControls(); render()
    }
    private fun markControls() {
        RankBoard.entries.forEachIndexed { i,b ->
            controls[i].isSelected=state.board==b
            controls[i].setTextColor(if(state.board==b) TvStyle.accent else TvStyle.text)
            controls[i].background=TvStyle.shape(activity,if(state.board==b) android.graphics.Color.rgb(48,32,29) else TvStyle.surface,if(controls[i].hasFocus()) TvStyle.accent else android.graphics.Color.TRANSPARENT)
        }
        heading.text="${state.board.label}热播榜"
    }
    fun start(force: Boolean,focusNav: Boolean) {
        if(focusNav) nav[2].requestFocus()
        else controls[state.board.ordinal].requestFocus()
        if(force || state.snapshots[state.board]==null) refresh()
        else if(!focusNav && state.selectedId.isNotEmpty()) browser?.enter()
    }
    private fun refresh() {
        loading=true; refreshButton.text="读取中…"
        subtitle.text="正在读取完整榜单，题材筛选将覆盖全部已返回作品…"
        val board=state.board
        load(board) { snapshot,error ->
            loading=false; refreshButton.text="刷新"
            if(snapshot!=null) {
                state.snapshots[board]=snapshot
                if(state.topic !in snapshot.topics) state.topic=""
                render()
            } else {
                render()
                if(state.snapshots[board]==null) {
                    body.removeAllViews()
                    body.addView(label("暂时无法读取榜单，请按右上方刷新重试",17f,TvStyle.muted).apply { setPadding(dp(16),dp(30),0,0) })
                }
                subtitle.text=if(state.snapshots[board]!=null) "刷新失败，保留上次榜单 · ${error.orEmpty()}" else "读取失败 · ${error.orEmpty()} · 按右上方刷新重试"
            }
        }
    }
    private fun chooseTopic() {
        if(loading) return
        val snapshot=state.snapshots[state.board] ?: return
        val values=listOf("")+snapshot.topics
        val labels=values.map { if(it.isEmpty()) "全部题材 · ${snapshot.items.size} 部" else "$it · ${snapshot.filtered(it).size} 部" }
        tools.chooseGrid("${state.board.label}热播榜 · 选择题材",labels,values.indexOf(state.topic),filterButton) { i -> state.filter(values[i]); render() }
    }
    private fun render() {
        val enterRows=browser?.grid?.hasFocus()==true
        browser?.close();body.removeAllViews()
        val snapshot=state.snapshots[state.board]
        filterButton.text=if(state.topic.isBlank()) "筛选题材" else "题材：${state.topic}"
        filterButton.isEnabled=snapshot!=null && !loading
        val all=snapshot?.filtered(state.topic).orEmpty()
        subtitle.text=snapshot?.let {
            "${it.ranking.updatedText.ifBlank { "来源：红果官网热播榜" }}  ·  ${it.items.size} 部${if(state.topic.isNotEmpty()) "  ·  ${state.topic} ${all.size} 部（保留原榜名次）" else "  ·  筛选覆盖本榜全部作品"}"
        } ?: "选择榜单，发现下一部好剧"
        if(all.isEmpty()) {
            browser=null
            body.addView(label(if(snapshot==null) "正在准备榜单，请稍候…" else "本榜暂无符合条件的作品，可切换题材",17f,TvStyle.muted))
            controls.forEach { it.nextFocusDownId=it.id }
            return
        }
        val saved=state.selectedId
        val list=ContinuousCatalogView(activity,1,controls[state.board.ordinal],{ item,recycled ->
            val card=recycled as? RankCard ?: RankCard(activity)
            card.show(item,snapshot?.ranking?.positions?.get(item.id));cover(card.image,item.cover);card
        },{ state.selectedId=it.id },{ state.selectedId=it.id;open(it) },{ item,anchor ->state.selectedId=item.id;more(item,anchor) },{})
        browser=list;body.addView(list,params(-1,0).apply { weight=1f })
        list.show(all,false,saved.takeIf { it.isNotBlank() }?.let { ContinuousCatalogView.Position(it,0) })
        controls.forEach { it.nextFocusDownId=list.grid.id }
        if(enterRows) list.enter()
    }
}

/** One recycled row keeps ranks and heat from the unfiltered source snapshot. */
private class RankCard(private val activity: Activity): LinearLayout(activity) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(size: Float,color: Int=TvStyle.text)=TextView(activity).apply { textSize=size;setTextColor(color);includeFontPadding=false }
    private val number=label(26f,TvStyle.accent).apply { gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD }
    val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val title=label(19f).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END;typeface=Typeface.DEFAULT_BOLD }
    private val tags=label(12f,TvStyle.muted).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END;setPadding(0,dp(6),0,dp(5)) }
    private val description=label(13f,TvStyle.muted).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END }
    private val heat=label(17f,TvStyle.accent)
    private val metrics=label(11f,TvStyle.muted).apply { gravity=Gravity.END;maxLines=3 }
    init {
        gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(10),dp(16),dp(10))
        layoutParams=AbsListView.LayoutParams(-1,dp(112))
        addView(number,LayoutParams(dp(42),-1));addView(image,LayoutParams(dp(64),dp(90)).apply { rightMargin=dp(16) })
        val info=LinearLayout(activity).apply { orientation=VERTICAL;addView(title);addView(tags);addView(description) }
        addView(info,LayoutParams(0,-2,1f).apply { rightMargin=dp(16) })
        val values=LinearLayout(activity).apply { orientation=VERTICAL;gravity=Gravity.END;addView(heat);addView(metrics) }
        addView(values,LayoutParams(dp(135),-2))
    }
    fun show(item: Series,position: RankPosition?) {
        number.text=position?.rank?.toString() ?: "—"
        number.setTextColor(if((position?.rank ?: 100)>3) TvStyle.muted else TvStyle.accent)
        title.text=item.title;tags.text=listOf(item.badge,item.tags).filter(String::isNotBlank).joinToString("  ·  ")
        description.text=item.description.ifBlank { "暂无剧情简介，按确定查看剧集" }
        heat.text=position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"
        metrics.text=position?.metrics.orEmpty().replace(" · ","\n")
        contentDescription="第${position?.rank ?: "未知"}名，${item.title}，${item.tags}，${position?.heat.orEmpty()}"
    }
}
