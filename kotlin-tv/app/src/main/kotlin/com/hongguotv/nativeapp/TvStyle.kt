// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared native colors and shapes; no blur, bitmap backgrounds or focus animation. */
object TvStyle {
    val background=Color.rgb(11,15,21)
    val surface=Color.rgb(23,30,40)
    val raised=Color.rgb(32,42,54)
    val accent=Color.rgb(255,137,100)
    val text=Color.rgb(243,244,246)
    val muted=Color.rgb(147,160,178)
    val outline=Color.rgb(43,55,70)
    const val POSTER_ASPECT=.7f
    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=10)=GradientDrawable().apply {
        setColor(color); setCornerRadius(radius*context.resources.displayMetrics.density)
        setStroke((2*context.resources.displayMetrics.density).toInt(),border)
    }
    /** progressTintList 是 API 21 的属性；4.4 上直接给出一条同样配色的 progressDrawable。 */
    fun progressTint(bar: android.widget.ProgressBar,foreground: Int,background: Int) {
        val track=android.graphics.drawable.ShapeDrawable().apply { paint.color=background }
        val filled=android.graphics.drawable.ShapeDrawable().apply { paint.color=foreground }
        val layer=android.graphics.drawable.LayerDrawable(arrayOf(track,android.graphics.drawable.ClipDrawable(filled,android.graphics.drawable.ClipDrawable.HORIZONTAL,android.view.Gravity.START)))
        layer.setId(0,android.R.id.background); layer.setId(1,android.R.id.progress)
        bar.progressDrawable=layer
    }
    fun card(context: Context,focused: Boolean)=shape(context,if(focused) raised else surface,if(focused) accent else Color.TRANSPARENT)
}
