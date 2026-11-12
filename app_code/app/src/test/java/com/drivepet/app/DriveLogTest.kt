package com.drivepet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveLogTest {
    private val today = 20261112   // a Thursday

    private fun trip(km: Float, good: Int = 0, missed: Int = 0, jerky: Int = 0, harsh: Int = 0, points: Int = 10) =
        TripResult(points, km, missed + jerky + harsh == 0, good, missed, jerky, harsh)

    /** a day with [score] out of 10 turns (score 0..100 in steps of 10) */
    private fun day(day: Int, score: Int, km: Float = 10f) = DayRecord(day, km, 1, score / 10, 10 - score / 10, 0, 0, 20)

    // ---------------------------------------------------------------- recording drives

    @Test fun drivesShorterThanHalfAKilometreAreNotRecorded() {
        val log = DriveLog()
        assertFalse(log.addTrip(today, trip(0.49f, good = 2)))
        assertTrue(log.isEmpty)
        assertTrue(log.addTrip(today, trip(0.5f, good = 2)))
        assertEquals(1, log.size)
    }

    @Test fun drivesOfOneDayAddUp() {
        val log = DriveLog()
        log.addTrip(today, trip(12.4f, good = 5, missed = 1, jerky = 1, points = 60))
        log.addTrip(today, trip(3.1f, good = 4, harsh = 2, points = 30))
        val d = log.day(today)!!
        assertEquals(15.5f, d.km, 0.001f)
        assertEquals(2, d.trips)
        assertEquals(9, d.good)
        assertEquals(1, d.missed)
        assertEquals(1, d.jerky)
        assertEquals(2, d.harsh)
        assertEquals(90, d.points)
        assertEquals(90, d.score)            // 9 of 10 turns with the signal
    }

    @Test fun aDayWithoutTurnsHasNoScoreButIsStillADrivenDay() {
        val log = DriveLog()
        log.addTrip(today, trip(8f))
        assertNull(log.day(today)!!.score)
        assertEquals(1, log.stats(today).monthDays)
        assertNull(log.stats(today).avg7)    // nothing to average
    }

    @Test fun scoreIsTheShareOfSignalledTurns() {
        assertEquals(67, DriveLog.score(2, 1))
        assertEquals(100, DriveLog.score(3, 0))
        assertEquals(0, DriveLog.score(0, 4))
        assertNull(DriveLog.score(0, 0))
        assertEquals(ScoreBand.GOOD, ScoreBand.of(90))
        assertEquals(ScoreBand.FAIR, ScoreBand.of(89))
        assertEquals(ScoreBand.FAIR, ScoreBand.of(70))
        assertEquals(ScoreBand.LOW, ScoreBand.of(69))
    }

    @Test fun recordSurvivesSavingAndLoading() {
        val log = DriveLog()
        log.addTrip(today, trip(12.4f, good = 5, missed = 1, jerky = 1, points = 60))
        log.addTrip(20261031, trip(3.25f, good = 1, harsh = 1, points = 18))
        val back = DriveLog.decode(DriveLog.encode(log))
        assertEquals(log.days.toList(), back.days.toList())
        assertEquals(12.4f, back.day(today)!!.km, 0.001f)
        assertEquals(listOf(20261031, today), back.days.map { it.day })   // oldest first
    }

    @Test fun brokenEntriesAreSkipped() {
        val log = DriveLog.decode("20261112,3.00,1,2,0,0,0,25;garbage;20261131,1,1,1,1,1,1,1;20261110,4.00,1,1,1,0,0,12")
        assertEquals(listOf(20261110, 20261112), log.days.map { it.day })   // 31 November does not exist
        assertTrue(DriveLog.decode(null).isEmpty)
        assertTrue(DriveLog.decode("").isEmpty)
    }

    // ---------------------------------------------------------------- the numbers above the calendar

    @Test fun sevenAndThirtyDayAverages() {
        val log = DriveLog(listOf(
            day(today, 100), day(Days.plus(today, -6), 80),        // this week: 90
            day(Days.plus(today, -7), 60), day(Days.plus(today, -13), 70),   // the week before: 65
            day(Days.plus(today, -29), 40),                       // still in the 30 days
            day(Days.plus(today, -30), 0),                        // just outside
        ))
        val s = log.stats(today)
        assertEquals(90, s.avg7)
        assertEquals(70, s.avg30)          // (100 + 80 + 60 + 70 + 40) / 5
        assertEquals(25, s.weekDelta)      // 90 - 65
    }

    @Test fun weekComparisonOfTheSignalRate() {
        val log = DriveLog(listOf(
            DayRecord(today, 5f, 1, good = 9, missed = 1),                    // this week: 9 of 10 turns
            DayRecord(Days.plus(today, -8), 5f, 1, good = 3, missed = 3),     // last week: 3 of 6, then 7 of 10
            DayRecord(Days.plus(today, -9), 5f, 1, good = 7, missed = 3),
        ))
        val s = log.stats(today)
        assertEquals(90, s.weekRate)
        assertEquals(27, s.weekRateDelta)   // 90% against 10 of 16 = 63%
        assertNull(DriveLog(listOf(DayRecord(today, 5f, 1, 1, 0))).stats(today).weekDelta)   // no week before: nothing to compare
    }

    @Test fun theMonthOnTheCalendar() {
        val log = DriveLog(listOf(
            DayRecord(20261101, 10f, 1, 9, 1), DayRecord(20261112, 2.5f, 1, 1, 1),
            DayRecord(20261031, 40f, 2, 5, 0), DayRecord(20261201, 3f, 1, 1, 0),
        ))
        val nov = log.stats(today)
        assertEquals(2, nov.monthDays)
        assertEquals(12.5f, nov.monthKm, 0.001f)
        assertEquals(70, nov.monthAvg)            // (90 + 50) / 2
        val oct = log.stats(today, 202610)
        assertEquals(1, oct.monthDays)
        assertEquals(40f, oct.monthKm, 0.001f)
    }

    @Test fun calendarArithmetic() {
        assertEquals(20261201, Days.plus(20261130, 1))
        assertEquals(20251231, Days.plus(20260101, -1))
        assertEquals(202701, Days.monthPlus(202612, 1))
        assertEquals(202512, Days.monthPlus(202601, -1))
        assertEquals(28, Days.length(202602))
        assertEquals(29, Days.length(202802))
        assertEquals(4, Days.weekday(today))          // Thursday
        assertEquals(0, Days.weekday(20261101))       // 1 November 2026 is a Sunday
        assertEquals("11월 12일 (목)", Days.dayLabel(today))
    }

    // ---------------------------------------------------------------- badges and rewards

    private fun badge(id: String) = Badges.get(id)!!

    @Test fun theFirstRecordGivesItsBadgeOnce() {
        val log = DriveLog()
        val book = BadgeBook()
        var paid = 0
        assertTrue(book.evaluate(log, today) { paid += it }.isEmpty())
        log.addTrip(today, trip(1f, good = 1))
        val fresh = book.evaluate(log, today) { paid += it }
        assertEquals(listOf("first"), fresh.map { it.id })
        assertEquals(30, paid)
        log.addTrip(today, trip(2f, good = 1))
        assertTrue(book.evaluate(log, today) { paid += it }.isEmpty())   // never twice
        assertEquals(30, paid)
        assertEquals(today, book.firstDay(badge("first")))
    }

    @Test fun streaksCountDrivenDaysAndSkipRestDays() {
        val log = DriveLog(listOf(
            day(20261101, 60),                       // an old low day, before the run
            day(20261103, 90), day(20261106, 100),   // days off in between do not break it
            DayRecord(20261108, 4f, 1),              // driven without a turn: skipped
            day(20261110, 95),
        ))
        assertEquals(3, log.goodStreak())
        val book = BadgeBook()
        var paid = 0
        val fresh = book.evaluate(log, 20261110) { paid += it }.map { it.id }
        assertTrue("streak3" in fresh)
        assertFalse("streak7" in fresh)
        assertEquals(30 + 20, paid)                 // the first record and the 3-day run
        log.put(day(20261111, 80))                  // a day under 90 ends the run
        assertEquals(0, log.goodStreak())
        assertEquals(20, badge("streak3").reward)
        assertEquals(50, badge("streak7").reward)
    }

    @Test fun theMonthlyGoalPaysOncePerMonth() {
        val log = DriveLog((1..10).map { day(20261100 + it, 100, km = 1f) })
        val book = BadgeBook()
        var paid = 0
        val nov = book.evaluate(log, 20261110) { paid += it }.map { it.id }
        assertTrue("month10" in nov)
        assertTrue(book.evaluate(log, 20261120) { paid += it }.none { it.id == "month10" })   // the same month: not again
        (1..10).forEach { log.put(day(20261200 + it, 100, km = 1f)) }
        val dec = book.evaluate(log, 20261215) { paid += it }.map { it.id }
        assertTrue("month10" in dec)                // a new month: earned again
        assertEquals(2, book.times(badge("month10")))
        assertTrue(book.earnedThisMonth(badge("month10"), 20261231))
        assertFalse(book.earnedThisMonth(badge("month10"), 20270101))
    }

    @Test fun signalAndDistanceBadges() {
        val log = DriveLog(listOf(DayRecord(20261001, 60f, 3, 60, 4), DayRecord(20261002, 50f, 2, 40, 10)))
        assertEquals(100, log.totalGoodSignals())
        assertEquals(60f, log.goodKm(), 0.001f)     // only the 94-point day counts; the 80-point one does not
        val fresh = BadgeBook().evaluate(log, 20261002) { }.map { it.id }
        assertTrue("signal100" in fresh)
        assertFalse("km100" in fresh)
    }

    @Test fun deletingTheRecordKeepsTheBadges() {
        val log = DriveLog()
        val book = BadgeBook()
        var paid = 0
        log.addTrip(today, trip(1f, good = 1))
        book.evaluate(log, today) { paid += it }
        log.clear()
        assertTrue(book.earned(badge("first")))
        log.addTrip(Days.plus(today, 1), trip(1f, good = 1))
        assertTrue(book.evaluate(log, Days.plus(today, 1)) { paid += it }.isEmpty())   // the first badge is not given again
        assertEquals(30, paid)
    }

    @Test fun badgesSurviveSavingAndLoading() {
        val log = DriveLog((1..10).map { day(20261100 + it, 100, km = 12f) })
        val book = BadgeBook()
        book.evaluate(log, 20261110) { }
        val back = BadgeBook.decode(BadgeBook.encode(book))
        assertEquals(book.awards.toList(), back.awards.toList())
        assertEquals(book.earnedCount(), back.earnedCount())
        assertTrue(BadgeBook.decode("bad;;x,y").awards.isEmpty())
    }

    @Test fun everyBadgeHasAUniqueIdAndAReward() {
        assertEquals(Badges.all.size, Badges.all.map { it.id }.toSet().size)
        assertTrue(Badges.all.all { it.reward > 0 && it.goal > 0 && it.condition.isNotBlank() })
        assertTrue(Badges.all.none { '@' in it.id || ',' in it.id || ';' in it.id })   // the id is part of the saved key
    }
}
