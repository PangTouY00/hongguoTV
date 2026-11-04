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
    // One sample per cover. A poster gets decoded again whenever its frame is re-measured, and counting
    // those repeats would let the card that keeps re-deciding pull the median toward its own artwork.
    private val samples=LinkedHashMap<String,Float>()
    var current= TvStyle.POSTER_ASPECT
        private set
    /** Records one delivered cover; the median keeps a single odd crop from moving every card. */
    fun observe(url: String, bitmap: Bitmap) {
        if(bitmap.width<=0 || bitmap.height<=0) return
        val ratio=bitmap.width.toFloat()/bitmap.height
        if(ratio<=.25f || ratio>3f) return
        synchronized(samples) {
            if(samples.containsKey(url)) return
            if(samples.size>=24) samples.remove(samples.keys.first())
            samples[url]=ratio
            if(samples.size>=4) current=samples.values.sorted()[samples.size/2]
        }
    }
    /** True when the learned ratio has moved far enough that frames should be re-measured. */
    fun differsFrom(value: Float)=abs(current-value)>=.04f
    /** Drops what was learned; used by tests so one case cannot leave another one measuring a different layout. */
    fun reset() {
        synchronized(samples) { samples.clear(); current= TvStyle.POSTER_ASPECT }
    }
}
