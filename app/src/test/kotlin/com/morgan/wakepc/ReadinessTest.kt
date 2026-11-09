package com.morgan.wakepc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A relay with `ready = <port>` adds `"ready": bool` to /status. These pin how
 * the app reads it, and that a relay without it reads exactly as before.
 */
class ReadinessTest {
    @Test
    fun `a status reply with ready is read`() {
        val up = parseStats("""{"awake": true, "sent": 1, "loss_pct": 0.0, "avg_ms": 2.5, "ready": false}""")
        assertEquals(false, up.ready)
        assertEquals(Readiness.UP, up.readiness)

        val ready = parseStats("""{"awake": true, "sent": 1, "loss_pct": 0.0, "avg_ms": 2.5, "ready": true}""")
        assertEquals(Readiness.READY, ready.readiness)
    }

    @Test
    fun `a reply from a relay with no ready port is plain awake`() {
        val stats = parseStats("""{"awake": true, "sent": 1, "loss_pct": 0.0, "avg_ms": 2.5}""")
        assertNull(stats.ready)
        assertEquals(Readiness.AWAKE, stats.readiness)
        assertEquals(2.5, stats.avgMs)
    }

    @Test
    fun `asleep is asleep whatever ready says`() {
        assertEquals(Readiness.ASLEEP, readinessOf(awake = false, ready = null))
        assertEquals(Readiness.ASLEEP, readinessOf(awake = false, ready = false))
        assertNull("no reading yet", readinessOf(awake = null, ready = null))
    }

    @Test
    fun `only READY and plain AWAKE end a wake`() {
        assertTrue(Readiness.READY.usable)
        assertTrue(Readiness.AWAKE.usable)
        assertFalse(Readiness.UP.usable)
        assertFalse(Readiness.ASLEEP.usable)
    }

    @Test
    fun `the widget and tile say UP and READY`() {
        val up = PingStats(awake = true, lossPct = 0.0, minMs = 1.0, avgMs = 2.0, maxMs = 3.0, ready = false)
        assertEquals("UP 2.0MS", widgetLabel(up))
        assertEquals("READY 2.0MS", widgetLabel(up.copy(ready = true)))
        assertEquals("AWAKE 2.0MS", widgetLabel(up.copy(ready = null)))
        assertEquals("ASLEEP", widgetLabel(up.copy(awake = false)))
        assertEquals("?", widgetLabel(null))

        assertEquals("desk pc starting up", tileSubtitle("desk pc", Readiness.UP))
        assertEquals("desk pc ready", tileSubtitle("desk pc", Readiness.READY))
    }
}
