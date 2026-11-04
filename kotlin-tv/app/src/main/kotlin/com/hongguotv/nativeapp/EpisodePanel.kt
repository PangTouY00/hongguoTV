// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.*
import com.hongguotv.core.UnwatchedEpisodes

/**
 * 选集面板看到的缓存状态。
 *
 * label 给某一集一个后缀标记（已缓存、排队中、下载中的百分比、失败），summary 给整批一个
 * 摘要行；三个动作分别对应「缓存这一集」「缓存全部」「缓存还没看的」。全部可省略，省略时面板与之前一样。
 */
class EpisodeCacheUi(
    val label: (Int) -> String? = { null },
    val summary: () -> String? = { null },
    val cacheOne: (Int) -> Unit = {},
    val cacheAll: () -> Unit = {},
    val cacheUnwatched: (List<Int>) -> Unit = {},
)

/** A remote-accessible side panel; selecting a number is the only action that changes episodes. */
class EpisodePanel(
    private val activity: Activity,private val count: Int,private val current: Int,
    private val locate: (Int,View,(Int)->Unit)->Unit,
    private val select: (Int)->Unit,private val closed: ()->Unit,
    private val lastWatched: Int?=null,private val lastCompleted: Boolean=false,
    private val cache: EpisodeCacheUi?=null
) {
    private val dialog=Dialog(activity)
    private var group=current/20
    private var focused=current
    // 当前这一组格子的引用：下载进度落地时只改文字，不重画整屏，焦点不会跳。
    private val cells=mutableListOf<TextView>()
    private val cellIndexes=mutableListOf<Int>()
    private var summaryLine: TextView?=null
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=17f)=TextView(activity).apply { text=value; textSize=size; setTextColor(TvStyle.text); setPadding(dp(8),dp(6),dp(8),dp(6)) }
    private fun button(value: String,active: Boolean=false,action: ()->Unit)=label(value,16f).apply {
        id=View.generateViewId(); gravity=Gravity.CENTER; minHeight=dp(46); isFocusable=true; isFocusableInTouchMode=true
        fun paint(focus: Boolean) { background=GradientDrawable().apply { setColor(if(focus) TvStyle.accent else if(active) Color.rgb(61,39,34) else TvStyle.surface); cornerRadius=dp(6).toFloat() } }
        paint(false); setOnFocusChangeListener { _,hasFocus -> paint(hasFocus) }; setOnClickListener { action() }
    }
    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        render(); dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            attributes=attributes.apply { x=(activity.resources.displayMetrics.widthPixels*.05f).toInt() }
            setLayout(minOf(dp(520),(activity.resources.displayMetrics.widthPixels*.8f).toInt()),(activity.resources.displayMetrics.heightPixels*.9f).toInt())
        }
    }
    fun dismiss()=dialog.dismiss()
    /**
     * 下载进度落地后只改格子上的文字与摘要行：标记要跟着真实状态走，重画整屏会让焦点跳走。
     *
     * 只改真正变了的那几个。一集在下的时候二十个格子里只有一个在动，逐个 setText 会让整片格子
     * 重新测量一遍，每半秒一次——界面看起来就是「下载时整屏发涩」。
     */
    fun refresh() {
        if(!dialog.isShowing) return
        cellIndexes.forEachIndexed { position, index ->
            val cell=cells[position]
            val label=cellLabel(index)
            if(cell.text.toString()!=label) cell.text=label
            val description="第 ${index+1} 集"+(if(index==current) "，当前播放" else "")+cacheMark(index)
            if(cell.contentDescription?.toString()!=description) cell.contentDescription=description
        }
        summaryLine?.let { line ->
            val text=cache?.summary?.invoke()
            if(line.text.toString()!=text.orEmpty()) line.text=text.orEmpty()
            line.visibility=if(text==null) View.GONE else View.VISIBLE
        }
    }
    private fun cacheMark(index: Int): String = cache?.label?.invoke(index)?.let { " $it" }.orEmpty()
    /** 还没看的集：上次看到的那一集之后。没有观看记录就是整部都没看过。 */
    private fun unwatchedIndexes(): List<Int> = UnwatchedEpisodes.indexes(count,lastWatched)
    private fun cellLabel(index: Int): String = "${index+1}${cacheMark(index)}"+when {
        index==current -> " · 当前";index==lastWatched -> if(lastCompleted) " · 已看" else " · 上次";else -> ""
    }
    private fun render() {
        val content=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12)); setBackgroundColor(TvStyle.background) }
        content.addView(label("选集  /  正在看第 ${current+1} 集",20f))
        val actions=LinearLayout(activity)
        val close=button("关闭") { dismiss() }
        lateinit var jump: TextView
        jump=button("跳转集数") { locate(focused,jump) { target -> focused=target; group=target/20; render() } }
        val primary=mutableListOf(close,jump)
        // 缓存的三个动作单独一行：四个按钮挤在一行时「缓存未看 12 集」这种带数量的文字会被截掉。
        val cacheActions=mutableListOf<TextView>()
        if(cache!=null) {
            val unwatched=unwatchedIndexes()
            cacheActions+=button("缓存本集") { cache.cacheOne(current); render() }
            cacheActions+=button("缓存全部") { cache.cacheAll(); render() }
            cacheActions+=button(if(unwatched.isEmpty()) "没有未看的集" else "缓存未看 ${unwatched.size} 集") {
                // 都看过了就什么都不做：把已经看过的重新下一遍只是白占地方。
                if(unwatched.isNotEmpty()) { cache.cacheUnwatched(unwatched); render() }
            }
        }
        // 一行里的按钮按位置左右连通，遥控器从任一个都能走到相邻的那个。
        fun link(row: List<TextView>) { row.forEachIndexed { i,control ->
            control.nextFocusLeftId=row[if(i==0) i else i-1].id
            control.nextFocusRightId=row[if(i==row.lastIndex) i else i+1].id
            control.nextFocusUpId=control.id } }
        primary.forEach { actions.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }) }
        link(primary); content.addView(actions)
        if(cacheActions.isNotEmpty()) {
            val cacheRow=LinearLayout(activity)
            cacheActions.forEach { cacheRow.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }) }
            link(cacheActions)
            cacheActions.forEachIndexed { i,control -> control.nextFocusUpId=primary[if(i<primary.size) i else primary.lastIndex].id }
            close.nextFocusDownId=cacheActions.first().id; jump.nextFocusDownId=cacheActions[minOf(1,cacheActions.lastIndex)].id
            content.addView(cacheRow)
        }
        val summaryView=label("",14f).apply { visibility=View.GONE }
        summaryLine=summaryView
        content.addView(summaryView)
        refresh()
        val paging=LinearLayout(activity)
        val previous=button("‹ 上一组") { group--; focused=group*20; render() }.apply { isEnabled=group>0; isFocusable=isEnabled; alpha=if(isEnabled) 1f else .4f }
        val next=button("下一组 ›") { group++; focused=group*20; render() }.apply { isEnabled=(group+1)*20<count; isFocusable=isEnabled; alpha=if(isEnabled) 1f else .4f }
        listOf(previous,next).forEach { paging.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }) }
        content.addView(paging)
        content.addView(label("第 ${group*20+1}—${minOf((group+1)*20,count)} 集 / 共 $count 集",14f))
        val scroll=ScrollView(activity); val grid=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }; scroll.addView(grid)
        content.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        cells.clear(); cellIndexes.clear()
        (group*20 until minOf((group+1)*20,count)).toList().chunked(5).forEach { indexes ->
            val row=LinearLayout(activity)
            indexes.forEach { index ->
                val mark=cacheMark(index)
                val cell=button(cellLabel(index),index==current) { select(index) }
                cell.contentDescription="第 ${index+1} 集"+(if(index==current) "，当前播放" else "")+mark
                row.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }); cells+=cell; cellIndexes+=index
            }
            repeat(5-indexes.size) { row.addView(Space(activity),LinearLayout.LayoutParams(0,1,1f)) }
            grid.addView(row)
        }
        cells.forEachIndexed { i,cell ->
            cell.nextFocusLeftId=cells[if(i%5==0) i else i-1].id
            cell.nextFocusRightId=cells[if(i%5==4 || i==cells.lastIndex) i else i+1].id
            cell.nextFocusUpId=if(i>=5) cells[i-5].id else if(i<3 && previous.isFocusable) previous.id else if(next.isFocusable) next.id else jump.id
            cell.nextFocusDownId=cells[if(i+5<cells.size) i+5 else if(i/5<cells.lastIndex/5) cells.lastIndex else i].id
        }
        previous.nextFocusUpId=cacheActions.lastOrNull()?.id ?: close.id
        next.nextFocusUpId=cacheActions.lastOrNull()?.id ?: jump.id
        previous.nextFocusDownId=cells.first().id; next.nextFocusDownId=cells[minOf(4,cells.lastIndex)].id
        if(cacheActions.isEmpty()) {
            close.nextFocusDownId=if(previous.isFocusable) previous.id else cells.first().id
            jump.nextFocusDownId=if(next.isFocusable) next.id else cells[minOf(4,cells.lastIndex)].id
        } else cacheActions.forEachIndexed { i,control ->
            control.nextFocusDownId=if(i==0) (if(previous.isFocusable) previous.id else cells.first().id)
                else if(next.isFocusable) next.id else cells[minOf(4,cells.lastIndex)].id
        }
        dialog.setContentView(content)
        cells[(focused-group*20).coerceIn(0,cells.lastIndex)].requestFocus()
    }
}
