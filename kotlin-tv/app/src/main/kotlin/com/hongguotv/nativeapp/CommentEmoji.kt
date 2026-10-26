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

/**
 * Fluent 表情插画，用来替代表情标签，不是平台原图；没有对应插画时标签原样保留。
 *
 * 名字表覆盖两类标签：评论里常见的通用写法，以及红果弹幕表情面板里的名字
 * （爱慕、爽、奸笑、震惊、什么、尬笑、舔屏、吃瓜、探究、送花、笑）。
 */
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
        "吃瓜" to "1f349",
        // 红果弹幕表情面板里的标签名，按语义映射到同一套插画。
        "爱慕" to "1f60d",
        "爽" to "1f60c",
        "奸笑" to "1f60f",
        "震惊" to "1f632",
        "什么" to "1f928",
        // Fluent 表情集里没有 U+1F605，尬笑用 U+1F603（笑中带汗）的插画代替。
        "尬笑" to "1f603",
        "舔屏" to "1f924",
        "探究" to "1f9d0",
        "送花" to "1f490",
        "笑" to "1f642"
    )
    private val pattern=Regex("""(?:\[|［|【)([^\]］】]{1,20})(?:\]|］|】)""")
    private val cache=mutableMapOf<String,Bitmap>()
    /** Decoded once per tag and pixel size; a missing asset returns null so the tag stays as text. */
    private fun bitmapFor(tag: String, size: Int): Bitmap? {
        val file=names[tag] ?: return null
        if(size<=0) return null
        val key="$file:$size"
        cache[key]?.let { return it }
        val decoded=openAsset(file)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val scaled=if(decoded.width==size && decoded.height==size) decoded
            else runCatching { Bitmap.createScaledBitmap(decoded,size,size,true) }.getOrDefault(decoded)
        if(scaled!==decoded) decoded.recycle()
        cache[key]=scaled
        return scaled
    }
    /** 素材有两种容器：早期内置的是 WebP，后补的是 Fluent 3D 的 PNG，两种 BitmapFactory 都能直接解。 */
    private fun openAsset(file: String): java.io.InputStream? =
        listOf("webp","png").firstNotNullOfOrNull { extension -> runCatching { context.assets.open("comment_emoji/$file.$extension") }.getOrNull() }
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
