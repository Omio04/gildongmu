package com.gildongmu.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnsTest {
    @Test
    fun `the common turns read as the driver says them`() {
        assertEquals("좌회전" to "←", Turns.from("TURN_LEFT", 80.0, null).let { it.word to it.arrow })
        assertEquals("우회전" to "→", Turns.from("TURN_RIGHT", 80.0, null).let { it.word to it.arrow })
        assertEquals("U턴" to "↶", Turns.from("TURN_UTURN", 300.0, null).let { it.word to it.arrow })
    }

    @Test
    fun `ramps keep their side`() {
        assertEquals("↗", Turns.from("TURN_EXIT_IC_R", 900.0, null).arrow)
        assertEquals("↖", Turns.from("TURN_EXIT_IC_L", 900.0, null).arrow)
        assertEquals("출구", Turns.from("TURN_EXIT_IC_C", 900.0, null).word)
        assertEquals("회전교차로", Turns.from("TURN_ENTER_ROUNDABOUT_09", 60.0, null).word)
    }

    @Test
    fun `an unknown turn still shows something and the description is kept`() {
        val t = Turns.from("TURN_SOMETHING_NEW", 120.4, "  기업지원허브 방면 ")
        assertEquals("안내", t.word)
        assertEquals(120, t.distanceM)
        assertEquals("기업지원허브 방면", t.where)
    }

    @Test
    fun `distance is rounded so the number does not flicker`() {
        assertEquals("80m", Turns.distance(84))
        assertEquals("0m", Turns.distance(3))
        assertEquals("1.2km", Turns.distance(1234))
        assertEquals("0m", Turns.distance(Turns.from("TURN_LEFT", -5.0, null).distanceM))   // never negative
    }
}
