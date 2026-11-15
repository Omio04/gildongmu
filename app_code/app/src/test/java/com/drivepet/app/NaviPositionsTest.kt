package com.drivepet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviPositionsTest {
    @Test fun aRealPositionIsUsable() {
        assertTrue(NaviPositions.usable(37.4467, 127.1386))     // 성남, the emulator's default position
        assertTrue(NaviPositions.usable(35.8714, 128.6014))     // 대구
    }

    @Test fun theZeroPositionBeforeTheNavigationKnowsIsNotUsable() {
        assertFalse(NaviPositions.usable(0.0, 0.0))
    }

    @Test fun brokenNumbersAreNotUsable() {
        assertFalse(NaviPositions.usable(Double.NaN, 127.0))
        assertFalse(NaviPositions.usable(37.0, Double.POSITIVE_INFINITY))
        assertFalse(NaviPositions.usable(91.0, 127.0))
        assertFalse(NaviPositions.usable(37.0, 181.0))
    }

    @Test fun aUsablePositionFindsItsRegion() {
        // the navigation's position goes through the same lookup the GPS used: 성남 is in 경기
        assertEquals("gyeonggi", Korea.locate(37.4467, 127.1386)?.id)
    }
}
