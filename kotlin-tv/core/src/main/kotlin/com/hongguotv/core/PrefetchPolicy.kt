// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * When to open the next episode while the current one still plays. Cellular is not a reason to
 * skip the warm-up: a phone on mobile data is the common viewing case, and switching without a
 * prepared next stream costs a full address lookup plus index request in front of the viewer.
 * Cellular keeps the same behaviour with a smaller footprint and a longer lead instead.
 */
object PrefetchPolicy {
    const val WARM_BYTES=2*1024*1024
    const val WARM_BYTES_METERED=1024*1024
    /** Wall-clock lead before the end of the episode; cellular needs more time per byte. */
    private const val LEAD_MS=30_000L
    private const val LEAD_MS_METERED=60_000L
    /** How far the current episode must be buffered ahead so the warm-up cannot starve it. */
    private const val BUFFER_AHEAD_MS=5_000L
    private const val BUFFER_AHEAD_MS_METERED=2_500L
    private const val MAX_ATTEMPTS=3
    private const val MAX_ATTEMPTS_METERED=4
    private const val RETRY_COOLDOWN_MS=15_000L
    private const val RETRY_COOLDOWN_MS_METERED=8_000L

    /** Both budgets stay inside the bounded warm-up range RemoteVideo accepts. */
    fun warmBytes(metered: Boolean): Int = if(metered) WARM_BYTES_METERED else WARM_BYTES
    fun maxAttempts(metered: Boolean): Int = if(metered) MAX_ATTEMPTS_METERED else MAX_ATTEMPTS
    fun retryCooldownMs(metered: Boolean): Long = if(metered) RETRY_COOLDOWN_MS_METERED else RETRY_COOLDOWN_MS
    private fun leadMs(metered: Boolean): Long = if(metered) LEAD_MS_METERED else LEAD_MS
    private fun bufferAheadMs(metered: Boolean): Long = if(metered) BUFFER_AHEAD_MS_METERED else BUFFER_AHEAD_MS

    /** Wall-clock time to the end matters: long episodes must not outlive the slot TTL. */
    fun nearEnd(position: Long, duration: Long, speed: Float, metered: Boolean=false): Boolean {
        if (duration <= 0 || position < 0 || position >= duration || !speed.isFinite() || speed <= 0) return false
        return (duration - position) / speed <= leadMs(metered).toFloat()
    }
    /** Start sooner on short dramas, but do not compete with a starving current episode. Cellular
     *  buffers more unevenly, so waiting for a 5 second lead would miss the window entirely. */
    fun canStart(position: Long,duration: Long,speed: Float,stableFor: Long,bufferedAhead: Long,metered: Boolean=false): Boolean =
        nearEnd(position,duration,speed,metered) && stableFor>=1500 &&
            bufferedAhead>=minOf(bufferAheadMs(metered),duration-position)
}
