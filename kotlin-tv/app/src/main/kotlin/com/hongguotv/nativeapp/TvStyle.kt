// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/** Shared native colors and shapes. The values follow the accepted 01 暗厅 · 取色洗底 direction
 * (ui-round-01/a-hall.html): a dark base with two large radial glows behind everything,
 * cards drawn as low-opacity white glass with a hairline light edge, coral as the only accent.
 * The glass is what makes the wash readable through the cards; a solid panel would hide it.
 * Focus is still expressed by a brighter fill and edge, not by scaling or runtime blur — the
 * target boxes predate RenderEffect and card geometry must not jump under the d-pad.
 *
 * 洗底不在这里画：它是 res/drawable-nodpi/tv_backdrop.png，由 tools/gen-backdrop.py 按设计稿的同一套
 * 参数生成，主题把它设为 windowBackground，界面代码通过下面的 backdrop() 取同一份资源。设计稿的光斑是
 * 1100x620 的椭圆径向渐变，墙上还有一层 3px 颗粒；Android 6 的 GradientDrawable 只能画正圆、也不做
 * dithering，硬画既走形又起同心环带。改配色改脚本重新生成，别手改 PNG。
 *
 * The same palette is declared once more in res/values/colors.xml for the theme and the dialog
 * panel drawable — AlertDialogs are themed through AppTheme.Dialog rather than drawn here, so
 * changing a colour means changing both places. */
object TvStyle {
    val background=Color.rgb(7,11,18)
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

    /** 界面代码要的是一面现成的墙，不在这里画渐变：直接取预渲染位图。主题里的 windowBackground
     * 指向同一份资源，两处共用同一个资源常量，位图只解码一次。资源缺失时退回竖向三段打底，界面不会变成透明。 */
    fun backdrop(context: Context): Drawable=context.getDrawable(R.drawable.tv_backdrop)
        ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.rgb(10,15,26),background,Color.rgb(5,8,14)))

    /** 弹窗、选集、弹幕设置这类独立窗口用的同一面墙，只是多盖了一层深色玻璃（res/drawable/tv_backdrop_panel.xml）。
     * 这些窗口自己带压暗，正文需要一个够实的面板；纯洗底在光斑亮的地方会把对比拉下来。
     * 玻璃浓度取 colors.xml 的 panel_wash（.40）：实机截图证明 .80 会把光斑压平，面板看不出是墙。
     * 主题里的 alertDialogTheme 已经指到同一份资源，自绘 Dialog 走这里，两处不会是两套颜色。 */
    fun backdropPanel(context: Context): Drawable=context.getDrawable(R.drawable.tv_backdrop_panel)
        ?: GradientDrawable().apply { setColor(Color.argb(102,7,11,18)); cornerRadius=12*context.resources.displayMetrics.density; setStroke(dp(context,1),Color.argb(74,143,166,196)) }

    private fun dp(context: Context,value: Int)=(value*context.resources.displayMetrics.density).toInt()
}
