// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.hongguotv.core.VideoFrameMode

/**
 * 4.4 分支没有 media3 的 PlayerView / AspectRatioFrameLayout，画面比例只能自己排。
 *
 * 这里必须改 Surface 的像素尺寸，而不是缩放 View：系统解码器把每一帧拉伸填满整个
 * Surface，View 的缩放又不会作用在 Surface 的内容上。所以「Surface 的尺寸 = 按视频
 * 比例算出来的显示尺寸」是唯一不会让画面变形的做法——Surface 自己就是正确比例，
 * 填满它的拉伸就等于没有拉伸。
 */
internal object FrameFit {
    /** 这个画面模式在容器内应占的像素尺寸。视频尺寸未知时先铺满，拿到尺寸后再校正。 */
    fun size(containerW: Int, containerH: Int, videoW: Int, videoH: Int, mode: VideoFrameMode): Pair<Int, Int> {
        if (mode == VideoFrameMode.FILL || videoW <= 0 || videoH <= 0) return Pair(containerW, containerH)
        val fitW = minOf(containerW.toFloat(), containerH.toFloat() * videoW / videoH)
        val fitH = minOf(containerH.toFloat(), containerW.toFloat() * videoH / videoW)
        return Pair((fitW * mode.frameScale).toInt(), (fitH * mode.frameScale).toInt())
    }

    /**
     * 把播放画面按模式排成这个尺寸：居中放在父容器里，放大模式超出容器的边缘由
     * FrameLayout 裁掉。父容器还没布局时不动它——按 0 尺寸排会把画面整个排没。
     */
    fun apply(view: View, videoW: Int, videoH: Int, mode: VideoFrameMode): Boolean {
        val parent = view.parent as? FrameLayout ?: return false
        if (parent.width <= 0 || parent.height <= 0) return false
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return false
        val (width, height) = size(parent.width, parent.height, videoW, videoH, mode)
        if (params.width == width && params.height == height && params.gravity == Gravity.CENTER) return false
        params.width = width; params.height = height; params.gravity = Gravity.CENTER
        view.layoutParams = params
        return true
    }
}
