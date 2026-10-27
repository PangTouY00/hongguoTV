// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable

/** One cancellable speculative result. Claim transfers ownership; stale completions close themselves. */
class PreparedSlot<T: Closeable>(private val clock: ()->Long,private val ttl: Long=120_000) {
    private var revision=0L
    private var key=""
    private var readyAt=0L
    private var value: T?=null
    @Synchronized fun begin(next: String): Long { clear(); key=next; return revision }
    @Synchronized fun complete(ticket: Long,next: String,result: T): Boolean {
        if(ticket!=revision || key!=next || value!=null) { result.close(); return false }
        value=result; readyAt=clock(); return true
    }
    @Synchronized fun take(next: String): T? {
        val result=value?.takeIf { key==next && clock()-readyAt in 0..ttl }
        if(result!=null) value=null
        clear()
        return result
    }
    @Synchronized fun isFresh(next: String): Boolean = value!=null && key==next && clock()-readyAt in 0..ttl
    /** True while a result for this key has been started but has not landed yet. */
    @Synchronized fun isOutstanding(next: String): Boolean = value==null && key==next
    /** Waits for an outstanding warm-up of this key instead of paying for a second one. Returns
     *  null as soon as nothing for this key is in flight, so a cancelled or failed request never
     *  makes a switch wait. Waiting is a short poll, never a held monitor.
     *  The budget here is wall-clock time the caller is willing to block, so it is measured on the
     *  monotonic clock rather than the injected one: a frozen clock (Robolectric's paused looper
     *  keeps SystemClock.elapsedRealtime fixed) would make this loop never end. The injected clock
     *  stays in charge of freshness, which is about how old a result is, not how long we wait. */
    fun await(next: String,timeoutMs: Long): T? {
        val until=System.nanoTime()+maxOf(0L,timeoutMs)*1_000_000L
        while(isOutstanding(next)) {
            if(System.nanoTime()>=until) return null
            try { Thread.sleep(25) } catch(_: InterruptedException) { return null }
        }
        // Only this key is consumed; a warm-up that moved on to another episode stays untouched.
        return if(isFresh(next)) take(next) else null
    }
    @Synchronized fun clear() { revision++; value?.close(); value=null; key="" }
}
