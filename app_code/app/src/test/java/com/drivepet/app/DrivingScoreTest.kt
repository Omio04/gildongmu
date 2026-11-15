package com.drivepet.app

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class DrivingScoreTest {
    private val wall = ZonedDateTime.of(2026, 11, 13, 12, 0, 0, 0, ScorePolicy.KOREA).toInstant().toEpochMilli()
    private fun drive(at: Long = wall, km: Double = 100.0, observed: Double = 7200.0, elapsed: Double = 7500.0,
                      accel: Int = 0, brakes: Int = 0, starts: Int = 0, night: Double = 0.0) =
        ScoredDrive(at, km, observed, elapsed, accel, brakes, starts, night)
    private fun tracker() = DrivingScoreTracker().apply { begin(0) }
    private fun DrivingScoreTracker.fix(second: Int, speed: Double, error: Double = 0.0, base: Long = wall) =
        sample(second * 1000L, base + second * 1000L, speed, error, second * 1000L)

    @Test fun noDataIsNotAPerfectScore() {
        assertNull(DrivingHistory().summary(20261113).score)
        assertNull(DrivingHistory(listOf(drive(km = 9.9))).summary(20261113).score)
        assertNull(DrivingHistory(listOf(drive(observed = 599.0, elapsed = 600.0))).summary(20261113).score)
        assertNull(DrivingHistory(listOf(drive(observed = 600.0, elapsed = 1000.0))).summary(20261113).score)
    }
    @Test fun categoryRatesAndNightExposureHaveExplicitWeights() {
        val summary = DrivingHistory(listOf(drive(accel = 2, brakes = 3, starts = 1, night = 720.0))).summary(20261113)
        assertEquals(87, summary.score)
        assertEquals(90, DrivingHistory(listOf(drive(night = 7200.0))).summary(20261113).score)
        assertEquals(0, DrivingHistory(listOf(drive(accel = 100, brakes = 100, starts = 100, night = 7200.0))).summary(20261113).score)
    }
    @Test fun partialDrivesReduceCoverageInsteadOfBeingSilentlyDropped() {
        val history = DrivingHistory(listOf(drive(), drive(km = 0.0, observed = 0.0, elapsed = 7500.0)))
        assertNull(history.summary(20261113).score)
    }
    @Test fun rollingWindowIncludesTodayAndExcludesFutureAndDayNinetyOne() {
        val today = 20261113
        fun at(day: Int) = Days.date(day).atStartOfDay(ScorePolicy.KOREA).toInstant().toEpochMilli()
        val h = DrivingHistory(listOf(drive(), drive(at(Days.plus(today, -89))), drive(at(Days.plus(today, -90))), drive(at(Days.plus(today, 1)))))
        assertEquals(2, h.summary(today).trips)
        assertEquals(200.0, h.summary(today).km, 0.001)
    }
    @Test fun periodScoreUsesCombinedRatesInsteadOfAverageOfTripScores() {
        val summary = DrivingHistory(listOf(drive(km = 10.0, accel = 2), drive(km = 90.0))).summary(20261113)
        assertEquals(96, summary.score)
    }
    @Test fun storageRoundTripAndCorruptEntries() {
        val history = DrivingHistory(listOf(drive(accel = 2)))
        assertEquals(history.encode(), DrivingHistory.decode(history.encode()).encode())
        assertEquals(1, DrivingHistory.decode("broken;${history.encode()};${wall},NaN,1,1,0,0,0,0,1").size)
        assertEquals(0, DrivingHistory(listOf(drive(km = -1.0), drive(observed = 8000.0), drive(starts = -1))).size)
        history.clear(); assertNull(history.summary(20261113).score)
    }
    @Test fun constantSpeedHasCoverageAndDistanceButNoEvents() {
        val t = tracker()
        for (s in 0..600) t.fix(s, 20.0)
        val d = t.finish(600000, wall + 600000)!!
        assertEquals(12.0, d.km, 0.0001)
        assertEquals(600.0, d.observedSeconds, 0.001)
        assertEquals(100, DrivingHistory(listOf(d)).summary(20261113).score)
    }
    @Test fun rapidStartIsNotAlsoAccelerationAndSustainedEventCountsOnce() {
        val t = tracker()
        for (s in 0..4) t.fix(s, s * 4.0)
        val d = t.finish(10000, wall + 10000)!!
        assertEquals(1, d.starts); assertEquals(0, d.accelerations)
    }
    @Test fun accelerationAndBrakingAreSeparateEpisodes() {
        val t = tracker()
        listOf(10.0, 14.0, 18.0, 18.0, 14.0, 10.0).forEachIndexed { i, v -> t.fix(i, v) }
        val d = t.finish(10000, wall + 10000)!!
        assertEquals(1, d.accelerations); assertEquals(1, d.brakes)
    }
    @Test fun uncertaintyDoesNotTurnBorderlineAccelerationIntoPenalty() {
        val t = tracker(); t.fix(0, 10.0, 0.5); t.fix(1, 13.5, 0.5)
        assertEquals(0, t.finish(10000, wall + 10000)!!.accelerations)
    }
    @Test fun missingOrPoorSpeedCannotBridgeIntoFalseAcceleration() {
        val t = tracker(); t.fix(0, 0.0); t.fix(1, 4.0, 2.0); t.fix(2, 8.0)
        val d = t.finish(10000, wall + 10000)!!
        assertEquals(0, d.starts); assertEquals(0.0, d.observedSeconds, 0.001)
    }
    @Test fun gapsAndImplausibleJumpsAreRejected() {
        val t = tracker(); t.fix(0, 0.0); t.fix(10, 30.0); t.fix(11, 60.0)
        val d = t.finish(12000, wall + 12000)!!
        assertEquals(0.0, d.observedSeconds, 0.001)
        assertEquals(0, d.accelerations)
    }
    @Test fun staleFutureAndNonFiniteFixesAreRejected() {
        val t = tracker()
        t.sample(1000, wall, 10.0, 0.0, 5000)
        t.sample(6000, wall, 10.0, 0.0, 5000)
        t.fix(7, Double.NaN); t.fix(8, 10.0, Double.NaN)
        assertEquals(0.0, t.finish(10000, wall + 10000)!!.observedSeconds, 0.001)
    }
    @Test fun clockJumpDoesNotCreateExposureOrEvents() {
        val t = tracker(); t.fix(0, 10.0); t.fix(1, 20.0, base = wall + 3600000)
        assertEquals(0.0, t.finish(10000, wall + 10000)!!.observedSeconds, 0.001)
    }
    @Test fun nightUsesKoreanTimeAndParkedTimeIsNotNightExposure() {
        fun at(h: Int) = ZonedDateTime.of(2026, 11, 13, h, 0, 0, 0, ScorePolicy.KOREA).toInstant().toEpochMilli()
        assertTrue(ScorePolicy.night(at(23))); assertTrue(ScorePolicy.night(at(4)))
        assertFalse(ScorePolicy.night(at(5))); assertFalse(ScorePolicy.night(at(22)))
        val t = tracker(); t.fix(0, 10.0, base = at(23)); t.fix(1, 10.0, base = at(23))
        assertEquals(1.0, t.finish(10000, at(23) + 10000)!!.nightSeconds, 0.001)
        val parked = tracker(); parked.fix(0, 0.0, base = at(23)); parked.fix(1, 0.0, base = at(23))
        assertEquals(0.0, parked.finish(10000, at(23) + 10000)!!.nightSeconds, 0.001)
    }
    @Test fun finishingTwiceDoesNotDuplicateAndNewDriveResetsEvents() {
        val t = tracker(); t.fix(0, 0.0); t.fix(1, 4.0)
        assertNotNull(t.finish(10000, wall + 10000)); assertNull(t.finish(10000, wall + 10000))
        t.begin(10000)
        assertEquals(0, t.finish(20000, wall + 20000)!!.starts)
        assertNull(BluelinkNotConnected.latest())
    }
}
