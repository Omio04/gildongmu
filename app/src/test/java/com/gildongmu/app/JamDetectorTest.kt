package com.gildongmu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JamDetectorTest {
    private val s = 1000L

    /** Drives at [kmh] from [from] to [to] seconds, one reading a second; returns the first reason that fired. */
    private fun drive(d: JamDetector, from: Int, to: Int, kmh: Float): JamReason? {
        var hit: JamReason? = null
        for (t in from..to) d.onSpeed(t * s, kmh, parked = false)?.let { if (hit == null) hit = it }
        return hit
    }

    @Test
    fun `cruising at highway speed asks nothing`() {
        assertNull(drive(JamDetector(), 0, 300, 100f))
    }

    @Test
    fun `a sudden drop in speed fires at once`() {
        val d = JamDetector()
        assertNull(drive(d, 0, 100, 100f))
        assertEquals(JamReason.SUDDEN, d.onSpeed(101 * s, 30f, false))
    }

    @Test
    fun `falling to half of the cruise speed for 20 seconds fires`() {
        val d = JamDetector()
        drive(d, 0, 150, 100f)
        // a gentle slide that never drops 35 km/h inside 10 s
        var hit: JamReason? = null
        var v = 100f
        for (t in 151..260) { v = (v - 3f).coerceAtLeast(30f); d.onSpeed(t * s, v, false)?.let { if (hit == null) hit = it } }
        assertEquals(JamReason.SLOWDOWN, hit)
    }

    @Test
    fun `a road that was always slow does not count as a change`() {
        val d = JamDetector()
        // 35 km/h all the time: no cruise to compare with, and not slow enough to crawl
        assertNull(drive(d, 0, 400, 35f))
    }

    @Test
    fun `crawling for a minute fires`() {
        val d = JamDetector()
        assertEquals(JamReason.CRAWL, drive(d, 0, 130, 10f))
    }

    @Test
    fun `parked never fires and clears what it learned`() {
        val d = JamDetector()
        for (t in 0..400) assertNull(d.onSpeed(t * s, 0f, parked = true))
    }

    @Test
    fun `nothing fires in the first minute`() {
        assertNull(drive(JamDetector(), 0, 50, 5f))
    }

    @Test
    fun `an arrival three minutes later than the best prediction fires`() {
        val d = JamDetector()
        d.onSpeed(0, 80f, false)
        assertNull(d.onRemaining(70 * s, 30))                  // arrive in 30 min
        assertNull(d.onRemaining(130 * s, 30))                 // a minute later still "30": already 1 min behind
        assertNull(d.onRemaining(190 * s, 30))                 // 2 minutes behind: still under 3
        assertEquals(JamReason.DELAY, d.onRemaining(400 * s, 33))   // now well over 3 min behind the first prediction
    }

    @Test
    fun `it asks once per slow down and then rests`() {
        val d = JamDetector()
        drive(d, 0, 100, 100f)
        assertEquals(JamReason.SUDDEN, d.onSpeed(101 * s, 20f, false))
        d.shown(101 * s)
        assertNull(drive(d, 102, 300, 10f))                    // same episode: crawling does not ask again
        // the road clears, then a new slow-down: still inside the 10 minute rest
        drive(d, 301, 340, 100f)
        assertNull(d.onSpeed(341 * s, 20f, false))
        // after the rest it may ask again
        drive(d, 342, 800, 100f)
        assertEquals(JamReason.SUDDEN, d.onSpeed(801 * s, 20f, false))
    }
}
