// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView

/**
 * 海报槽位。槽的尺寸由外部按屏幕推导（见 GridMetrics），图片来适配槽：封面比例和槽不一致时
 * 不在上下留黑，而是把同一张图裁切放大、压暗垫成背景，前景仍然完整显示封面本身。
 *
 * 背景复用前景已经解码好的那张位图，不额外解码、不额外占内存，也不需要第二层视图或加载回调。
 * 这与 Glide 的 Issue #1011 得出的是同一个结论：槽必须先定死，图片去适配槽，不能让槽跟着图片走。
 */
internal class CoverSlot(context: Context): ImageView(context) {
    init {
        scaleType=ScaleType.FIT_CENTER
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var backdrop: BitmapDrawable?=null
    private var backdropFor: Drawable?=null
    override fun onDraw(canvas: Canvas) {
        val source=drawable
        val bitmap=(source as? BitmapDrawable)?.bitmap
        if(bitmap!=null && source!==backdropFor) { backdropFor=source; backdrop=BitmapDrawable(resources,bitmap) }
        else if(source==null) { backdrop=null; backdropFor=null }
        val layer=backdrop
        if(layer!=null && width>0 && height>0) {
            val save=canvas.save()
            val intrinsic=layer.intrinsicWidth
            val intrinsicHeight=layer.intrinsicHeight
            if(intrinsic>0 && intrinsicHeight>0) {
                // 按短边铺满、长边溢出，溢出的部分由视图自己裁掉——就是 CENTER_CROP 的效果。
                val scale=maxOf(width.toFloat()/intrinsic,height.toFloat()/intrinsicHeight)
                val wide=(intrinsic*scale).toInt(); val tall=(intrinsicHeight*scale).toInt()
                layer.setBounds((width-wide)/2,(height-tall)/2,(width+wide)/2,(height+tall)/2)
            } else layer.setBounds(0,0,width,height)
            layer.alpha=BACKDROP_ALPHA
            layer.draw(canvas)
            canvas.restoreToCount(save)
        }
        super.onDraw(canvas)
    }
    private companion object {
        /** 背景压到三成多，够填住槽，又不会和前景的封面抢注意力。 */
        const val BACKDROP_ALPHA=82
    }
}
