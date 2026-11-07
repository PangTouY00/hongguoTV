// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/** Shared native colors and shapes. The screen sits on a fixed two-glow wash; panels are
 * semi-transparent with a hairline light edge, which reads as frosted glass without runtime
 * blur because the target boxes predate RenderEffect. Focus is expressed by panel lift and a
 * brighter edge, not by scaling, so card geometry and layout stay untouched. */
object TvStyle {
    val background=Color.rgb(7,11,18)
    val glowWarm=Color.rgb(42,27,58)
    val glowCool=Color.rgb(18,32,58)
    val surface=Color.argb(214,20,27,37)
    val raised=Color.argb(236,32,42,56)
    val panel=Color.argb(243,12,17,25)
    val accent=Color.rgb(255,137,100)
    val text=Color.rgb(243,244,246)
    val muted=Color.rgb(147,160,178)
    val outline=Color.argb(48,120,140,165)
    val edge=Color.argb(28,255,255,255)
    val edgeFocus=Color.argb(155,255,170,130)
    val scrim=Color.argb(242,7,11,18)
    val watched=Color.argb(214,64,41,34)
    val selected=Color.argb(222,66,42,34)
    const val POSTER_ASPECT=.7f

    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=10,borderDp: Float=2f)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*context.resources.displayMetrics.density
        setStroke((borderDp*context.resources.displayMetrics.density).toInt(),border)
    }
    fun card(context: Context,focused: Boolean)=shape(context,if(focused) raised else surface,if(focused) edgeFocus else edge,borderDp=if(focused) 1.5f else 1f)
    fun panel(context: Context)=shape(context,panel,edge,radius=12,borderDp=1f)

    /** Two large radial washes over the base tone, built once per screen and never animated. */
    fun backdrop(context: Context): Drawable {
        val metrics=context.resources.displayMetrics
        val w=metrics.widthPixels.toFloat(); val h=metrics.heightPixels.toFloat()
        return LayerDrawable(arrayOf(
            GradientDrawable(GradientDrawable.Orientation.BL_TR,intArrayOf(Color.rgb(12,17,27),background)),
            wash(w,h,.22f,.14f,1.15f,glowWarm,118),
            wash(w,h,.88f,.90f,1.25f,glowCool,96)))
    }
    private fun wash(w: Float,h: Float,cx: Float,cy: Float,scale: Float,color: Int,alpha: Int)=GradientDrawable().apply {
        setShape(GradientDrawable.RADIAL_GRADIENT)
        setGradientCenter(w*cx,h*cy)
        setGradientRadius(Math.max(w,h)*scale)
        val r=Color.red(color); val g=Color.green(color); val b=Color.blue(color)
        setColors(intArrayOf(Color.argb(alpha,r,g,b),Color.argb((alpha*.55).toInt(),r,g,b),Color.argb((alpha*.22).toInt(),r,g,b),Color.TRANSPARENT))
    }
}
