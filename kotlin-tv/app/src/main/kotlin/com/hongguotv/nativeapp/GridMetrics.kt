// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.DisplayMetrics
import kotlin.math.roundToInt

/**
 * 网格的尺寸只由屏幕决定，不由封面图片决定。
 *
 * 之前海报框的高度跟着「网站实际送来的封面比例」走：哪类封面先到达、哪类封面占多数，整页的卡片就会
 * 跟着变矮或变高。同一页里既有 16:9 的横 banner 又有 2:3 的竖海报时，这个值会落在两者之间，把竖海报
 * 挤成一小块。Android TV 的卡片规范用的是相反的做法——槽位尺寸固定，图片适配槽位——这里照这个来。
 *
 * 列数按屏幕的 dp 宽度推导。用 densityDpi 而不是 density 换算：电视的 density 常常是 1.0，直接按 dp
 * 定卡片宽度会让电视上的卡片比手机还小；换算成 dp 宽度后，手机横屏（约 872dp）与 1080p 电视（约
 * 960dp）得到同样的列数，而电视上每列的实际像素宽度更大，物理尺寸也就更大，这正是观看距离需要的。
 */
internal object GridMetrics {
    /** 一列的目标宽度。手机横屏 872dp → 6 列，手机竖屏 392dp → 3 列，1080p 电视 → 6 列。 */
    private const val TARGET_COLUMN_DP = 145f
    private const val MIN_COLUMNS = 3
    /** 上限 6 列：更大的屏幕得到更大的卡片，而不是更多的卡片。 */
    private const val MAX_COLUMNS = 6

    /** 屏幕宽度换算成 dp。 */
    fun widthDp(metrics: DisplayMetrics) = metrics.widthPixels * 160f / metrics.densityDpi

    fun columns(metrics: DisplayMetrics): Int =
        (widthDp(metrics) / TARGET_COLUMN_DP).roundToInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    fun gap(metrics: DisplayMetrics) = (14 * metrics.density).toInt()

    /** 卡片宽度：可用宽度平分给每一列，不留一列窄一列宽。 */
    fun cardWidth(metrics: DisplayMetrics, columns: Int, gap: Int): Int {
        val usable = metrics.widthPixels * 0.9f - gap * (columns - 1)
        return (usable / columns).toInt().coerceAtLeast((100 * metrics.density).toInt())
    }

    /**
     * 海报槽的高度。固定用竖版海报比例，不读任何一张封面的比例：槽位稳定，封面在槽内适配，
     * 同一行永远等高。
     */
    fun posterHeight(cardWidth: Int, gap: Int) =
        ((cardWidth - gap / 2) / TvStyle.POSTER_ASPECT).toInt()
}
