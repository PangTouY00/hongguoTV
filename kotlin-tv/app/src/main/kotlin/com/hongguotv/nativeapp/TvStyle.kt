// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/** Shared native colors and shapes. The values follow the accepted 01 暗厅 · 取色洗底 direction
 * (ui-round-01/style-explorer.html): a dark base with two large radial glows behind everything,
 * cards drawn as low-opacity white glass with a hairline light edge, coral as the only accent.
 * The glass is what makes the wash readable through the cards; a solid panel would hide it.
 * Focus is still expressed by a brighter fill and edge, not by scaling or runtime blur — the
 * target boxes predate RenderEffect and card geometry must not jump under the d-pad.
 *
 * The same palette is declared once more in res/values/colors.xml for the theme and the dialog
 * panel drawable — AlertDialogs are themed through AppTheme.Dialog rather than drawn here, so
 * changing a colour means changing both places. */
object TvStyle {
    val background=Color.rgb(7,11,18)
    val glowWarm=Color.rgb(42,27,58)
    val glowCool=Color.rgb(18,32,58)
    /** 卡片与选中态是一层白色低透明度玻璃，不是实色面板：身后的洗底透过来，卡片明暗随它在墙上的位置变。 */
    val surface=Color.argb(14,255,255,255)
    val raised=Color.argb(23,255,255,255)
    val selected=raised
    val panel=Color.argb(243,12,17,25)
    val accent=Color.rgb(255,137,100)
    val text=Color.rgb(241,243,247)
    val muted=Color.rgb(142,155,176)
    val ok=Color.rgb(95,200,166)
    val outline=Color.argb(48,120,140,165)
    val edge=Color.argb(41,255,255,255)
    val edgeFocus=Color.argb(155,255,170,130)
    val scrim=Color.argb(242,7,11,18)
    /** 已看过的集走绿色语义，和「已看完」一致；半透明绿压在暗底上仍够白字对比。 */
    val watched=Color.argb(96,95,200,166)
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
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.rgb(10,15,26),background,Color.rgb(5,8,14))),
            wash(w,h,.22f,.08f,.62f,glowWarm,235),
            wash(w,h,.78f,.26f,.58f,glowCool,190)))
    }
    /** 十二段 smoothstep 衰减。低版本的 GradientDrawable 不做 dithering，四段衰减会在墙上留下可见的同心环带。 */
    // GradientDrawable 自 API 16 起接受 RADIAL_GRADIENT，但 setShape 的常量注解没有把它列进去，lint 会误报。
    @android.annotation.SuppressLint("WrongConstant")
    private fun wash(w: Float,h: Float,cx: Float,cy: Float,radiusScale: Float,color: Int,peak: Int): GradientDrawable {
        val r=Color.red(color); val g=Color.green(color); val b=Color.blue(color)
        val steps=12
        val colors=IntArray(steps+1){ i ->
            val t=i.toFloat()/steps
            Color.argb((peak*(1f-t*t*(3f-2f*t))).toInt().coerceIn(0,255),r,g,b)
        }
        // 多色用 API 16 就有的构造函数：GradientDrawable.setColors 是 API 24，Android 6 上没有。
        return GradientDrawable(GradientDrawable.Orientation.BL_TR,colors).apply {
            setShape(GradientDrawable.RADIAL_GRADIENT)
            setGradientCenter(w*cx,h*cy)
            setGradientRadius(Math.max(w,h)*radiusScale)
        }
    }
}
