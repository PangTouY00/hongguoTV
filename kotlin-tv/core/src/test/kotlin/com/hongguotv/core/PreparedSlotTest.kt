// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class PreparedSlotTest {
    private class Value: Closeable {
        var closes=0
        override fun close() { closes++ }
    }
    @Test fun awaitTakesACompletionThatLandsFromAnotherThread() {
        val slot=PreparedSlot<Value>({ System.currentTimeMillis() })
        val ticket=slot.begin("102")
        val value=Value()
        assertTrue(slot.isOutstanding("102"))
        val worker=Thread { Thread.sleep(120); slot.complete(ticket,"102",value) }
        worker.start()
        assertSame(value,slot.await("102",3_000))
        worker.join()
        assertEquals(0,value.closes)
        assertFalse(slot.isFresh("102"))
    }
    @Test fun awaitGivesUpOnTheDeadlineAndLeavesTheWarmUpInFlight() {
        val slot=PreparedSlot<Value>({ System.currentTimeMillis() })
        slot.begin("102")
        assertNull(slot.await("102",150))
        assertTrue(slot.isOutstanding("102"))
    }
    @Test fun awaitNeverWaitsForAKeyThatIsNotInFlight() {
        val slot=PreparedSlot<Value>({ System.currentTimeMillis() })
        val started=System.currentTimeMillis()
        assertNull(slot.await("102",5_000))
        assertTrue(System.currentTimeMillis()-started<1_000)
        // A warm-up that was replaced by another episode is not waited for either.
        slot.begin("103")
        assertNull(slot.await("102",5_000))
        assertTrue(slot.isOutstanding("103"))
    }
    @Test fun aStaleCompletionClosesItselfAndAwaitNeverReturnsIt() {
        val slot=PreparedSlot<Value>({ System.currentTimeMillis() })
        val ticket=slot.begin("102")
        val value=Value()
        slot.clear()
        assertFalse(slot.complete(ticket,"102",value))
        assertEquals(1,value.closes)
        assertNull(slot.await("102",150))
    }
}
