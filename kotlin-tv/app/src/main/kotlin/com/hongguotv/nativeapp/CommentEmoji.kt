// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan

/** A run of a danmaku line: either plain text or one emoji illustration. */
internal class EmojiSegment(val text: String, val bitmap: Bitmap?)

/** Generic Fluent emoji illustrations, not the platform's original stickers. Unknown tags remain intact. */
internal class CommentEmoji(private val context: Context) {
    private val names=mapOf(
        "笑哭" to "1f602",
        "微笑" to "1f642",
        "呲牙" to "1f601",
        "大笑" to "1f606",
        "流泪" to "1f622",
        "大哭" to "1f62d",
        "赞" to "1f44d",
        "点赞" to "1f44d",
        "鼓掌" to "1f44f",
        "爱心" to "2764-fe0f",
        "比心" to "2764-fe0f",
        "玫瑰" to "1f339",
        "捂脸" to "1f926",
        "偷笑" to "1f92d",
        "思考" to "1f914",
        "害羞" to "1f60a",
        "发怒" to "1f620",
        "惊讶" to "1f62e",
        "破防" to "1f62d",
        "哭哭" to "1f62d",
        "狗头" to "1f412",
        "打call" to "1f499",
        "666" to "1f525",
        "酸" to "1f964",
        "吃瓜" to "1f349"
    )
    private val pattern=Regex("""(?:\[|［|【)([^\]］】]{1,20})(?:\]|］|】)""")
    private val cache=mutableMapOf<String,Bitmap>()
    /** Decoded once per tag and pixel size; a missing asset returns null so the tag stays as text. */
    private fun bitmapFor(tag: String, size: Int): Bitmap? {
        val file=names[tag] ?: return null
        if(size<=0) return null
        val key="$file:$size"
        cache[key]?.let { return it }
        val decoded=runCatching { context.assets.open("comment_emoji/$file.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
        val scaled=if(decoded.width==size && decoded.height==size) decoded
            else runCatching { Bitmap.createScaledBitmap(decoded,size,size,true) }.getOrDefault(decoded)
        if(scaled!==decoded) decoded.recycle()
        cache[key]=scaled
        return scaled
    }
    /** Splits one line into drawable runs so a canvas-drawn danmaku can show the same stickers as the comment list. */
    fun segments(text: String, size: Int): List<EmojiSegment> {
        val out=ArrayList<EmojiSegment>()
        var index=0
        for(match in pattern.findAll(text)) {
            val bitmap=bitmapFor(match.groupValues[1],size) ?: continue
            if(match.range.first>index) out.add(EmojiSegment(text.substring(index,match.range.first),null))
            out.add(EmojiSegment("",bitmap))
            index=match.range.last+1
        }
        if(out.isEmpty()) return listOf(EmojiSegment(text,null))
        if(index<text.length) out.add(EmojiSegment(text.substring(index),null))
        return out
    }
    fun render(text: String, size: Float): CharSequence {
        val result=SpannableStringBuilder(text)
        for(match in pattern.findAll(text)) {
            val bitmap=bitmapFor(match.groupValues[1],size.toInt()) ?: continue
            val drawable=BitmapDrawable(context.resources,bitmap).apply { setBounds(0,0,size.toInt(),size.toInt()) }
            result.setSpan(ImageSpan(drawable,ImageSpan.ALIGN_BOTTOM),match.range.first,match.range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return result
    }
}
