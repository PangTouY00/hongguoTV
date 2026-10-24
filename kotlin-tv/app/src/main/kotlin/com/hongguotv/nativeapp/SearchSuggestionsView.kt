// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import com.hongguotv.core.RequestScope
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Suggestions have their own cancellable worker, independent of result pagination/navigation. */
internal class SearchSuggestionsView(
    context: Context,
    private val input: EditText,
    private val peers: List<View>,
    private val destination: ()->View?,
    private val fetch: (String)->List<String>,
    private val search: (String)->Unit
): HorizontalScrollView(context) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val line=LinearLayout(context)
    private val chips=mutableListOf<TextView>()
    private var pending: Runnable?=null
    private var job: Future<*>?=null
    private var scope: RequestScope?=null
    private var ticket=0
    private var closed=false
    private val watcher=object: TextWatcher {
        override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int)=Unit
        override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int)=Unit
        override fun afterTextChanged(s: Editable?) { schedule(s?.toString().orEmpty()) }
    }
    init {
        isHorizontalScrollBarEnabled=false;isFocusable=false
        addView(line);visibility=GONE
        input.addTextChangedListener(watcher)
    }
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun schedule(value: String) {
        cancel()
        val keyword=value.trim()
        if(closed || keyword.isEmpty()) return
        val expected=ticket
        val task=Runnable {
            pending=null
            val request=RequestScope();scope=request
            job=worker.submit {
                val result=runCatching { request.run { fetch(keyword) } }
                main.post {
                    if(closed || ticket!=expected || input.text.toString().trim()!=keyword) return@post
                    render(result.getOrDefault(emptyList()),result.isFailure)
                }
            }
        }
        pending=task;main.postDelayed(task,350)
    }
    private fun render(values: List<String>,failed: Boolean=false) {
        // Replacing suggestions must never leave focus on a detached chip.
        if(chips.any { it.hasFocus() }) input.requestFocus()
        line.removeAllViews();chips.clear()
        values.distinct().take(8).forEach { value ->
            val chip=TextView(context).apply {
                id=View.generateViewId();text=value;textSize=13f;maxLines=1
                ellipsize=android.text.TextUtils.TruncateAt.END;maxWidth=dp(300)
                isFocusable=true;isClickable=true;setTextColor(TvStyle.text)
                setPadding(dp(12),dp(8),dp(12),dp(8));minHeight=dp(36)
                background=TvStyle.card(context,false)
                setOnFocusChangeListener { _,focus -> background=TvStyle.card(context,focus) }
                setOnClickListener { search(value) }
            }
            chips+=chip;line.addView(chip,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(8) })
        }
        if(failed) line.addView(TextView(context).apply {
            text="联想暂不可用，可直接按搜索";textSize=12f;setTextColor(TvStyle.muted)
            setPadding(0,dp(7),0,dp(7))
        })
        visibility=if(chips.isNotEmpty() || failed) VISIBLE else GONE
        scrollTo(0,0);updateNavigation()
    }
    fun updateNavigation() {
        val target=destination()?.id ?: input.id
        (peers+input).forEach { it.nextFocusDownId=chips.firstOrNull()?.id ?: target }
        chips.forEachIndexed { i,chip ->
            chip.nextFocusUpId=input.id;chip.nextFocusDownId=target
            chip.nextFocusLeftId=chips.getOrNull(i-1)?.id ?: chip.id
            chip.nextFocusRightId=chips.getOrNull(i+1)?.id ?: chip.id
        }
    }
    fun cancel() {
        ticket++;pending?.let(main::removeCallbacks);pending=null
        scope?.cancel();scope=null;job?.cancel(true);job=null
        render(emptyList())
    }
    fun close() {
        if(closed) return
        closed=true;input.removeTextChangedListener(watcher);cancel();worker.shutdownNow()
    }
}
