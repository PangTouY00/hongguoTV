// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * The ratio the site actually serves covers in. Poster frames used to assume one fixed ratio, and any
 * difference showed up as a dark band on both sides of the artwork. Learning the ratio from the images
 * that really arrive lets every grid size its frames to the artwork instead.
 */
internal object CoverRatio {
    private val samples=ArrayList<Float>()
    var current= TvStyle.POSTER_ASPECT
        private set
    /** Records one delivered cover; the median keeps a single odd crop from moving every card. */
    fun observe(bitmap: Bitmap) {
        if(bitmap.width<=0 || bitmap.height<=0) return
        val ratio=bitmap.width.toFloat()/bitmap.height
        if(ratio<=.25f || ratio>3f) return
        synchronized(samples) {
            if(samples.size>=24) samples.removeAt(0)
            samples.add(ratio)
            if(samples.size>=4) current=samples.sorted()[samples.size/2]
        }
    }
    /** True when the learned ratio has moved far enough that frames should be re-measured. */
    fun differsFrom(value: Float)=abs(current-value)>=.04f
    /** Drops what was learned; used by tests so one case cannot leave another one measuring a different layout. */
    fun reset() {
        synchronized(samples) { samples.clear(); current= TvStyle.POSTER_ASPECT }
    }
}
