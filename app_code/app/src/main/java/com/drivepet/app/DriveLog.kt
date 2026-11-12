package com.drivepet.app

import java.time.LocalDate
import java.util.Locale
import java.util.TreeMap
import kotlin.math.roundToInt

/**
 * The driving record ("운전 기록"): one line per day the car was driven, kept on the device so the calendar can show it.
 *
 * The day's score is the **깜빡이 사용 점수** (turn-signal score): the share of the day's turns made with the turn signal on,
 * 0..100. It is the one habit the car really measures here:
 *  - The Pleos Vehicle SDK gives the turn signal, the steering wheel angle, the brake pedal (pressed or not), the gear and
 *    the odometer, and no speed or acceleration, so a hard stop cannot be measured, only guessed (three quick presses).
 *  - The points and experience the pet earns mix distance, calm time and signals, so they are not a measure of habits.
 *  - No safe-driving score could be used: Hyundai Developers' driving data APIs need a partnership and a server, the Pleos
 *    cloud vehicle data API (Europe only for now) returns raw samples without any score, and TMAP has no public score API.
 * So the score stays a turn-signal score, and the guesses (jerky braking, a sharp wheel) are shown beside it as notes,
 * never mixed into it. A day with no turn has no score (it is still a driven day on the calendar).
 *
 * Pure logic with no Android classes, so it can be tested on the JVM.
 */
data class DayRecord(
    /** the day as a number, like 20261112 */
    val day: Int,
    val km: Float = 0f,
    val trips: Int = 0,
    /** turns made with the turn signal on */
    val good: Int = 0,
    /** turns made without it */
    val missed: Int = 0,
    /** brake pumped three times in a short while: only a guess at a hard stop (note, not in the score) */
    val jerky: Int = 0,
    /** the wheel turned sharply (note, not in the score) */
    val harsh: Int = 0,
    /** the points the drives of the day earned (what the trip cards showed) */
    val points: Int = 0,
) {
    val turns: Int get() = good + missed

    /** the day's 깜빡이 사용 점수, or null when there was no turn to judge */
    val score: Int? get() = DriveLog.score(good, missed)

    fun plus(t: TripResult) = copy(
        km = km + t.km, trips = trips + 1, good = good + t.good, missed = missed + t.missed,
        jerky = jerky + t.jerky, harsh = harsh + t.harsh, points = points + t.points,
    )
}

/** the three colours of the calendar */
enum class ScoreBand(val label: String) {
    GOOD("90~100점"), FAIR("70~89점"), LOW("0~69점");

    companion object {
        fun of(score: Int): ScoreBand = when {
            score >= 90 -> GOOD
            score >= 70 -> FAIR
            else -> LOW
        }
    }
}

/** the numbers above the calendar. Averages count every turn of the days that have a score (see [DriveLog.average]). */
data class DriveStats(
    /** the last 7 days, today included */
    val avg7: Int?,
    val avg30: Int?,
    /** this week's average (the last 7 days) minus the week before's; null when either has no score */
    val weekDelta: Int?,
    /** the share of all of this week's turns made with the signal on, in %, and its change from the week before in %p */
    val weekRate: Int?,
    val weekRateDelta: Int?,
    /** the month on the calendar: days driven, distance, and the average score */
    val monthDays: Int,
    val monthKm: Float,
    val monthAvg: Int?,
)

/** day numbers like 20261112 and the calendar arithmetic on them (months are numbers like 202611) */
object Days {
    fun of(d: LocalDate): Int = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    fun date(day: Int): LocalDate = LocalDate.of(day / 10000, day / 100 % 100, day % 100)
    fun plus(day: Int, n: Int): Int = of(date(day).plusDays(n.toLong()))
    fun month(day: Int): Int = day / 100
    fun dayOfMonth(day: Int): Int = day % 100
    fun first(month: Int): Int = month * 100 + 1
    fun monthPlus(month: Int, n: Int): Int = date(first(month)).plusMonths(n.toLong()).let { it.year * 100 + it.monthValue }
    fun length(month: Int): Int = date(first(month)).lengthOfMonth()
    /** 0 = Sunday … 6 = Saturday */
    fun weekday(day: Int): Int = date(day).dayOfWeek.value % 7
    fun label(month: Int): String = "${month / 100}년 ${month % 100}월"
    private val WEEK = arrayOf("일", "월", "화", "수", "목", "금", "토")
    fun weekdayName(day: Int): String = WEEK[weekday(day)]
    /** "11월 12일 (목)" */
    fun dayLabel(day: Int): String = "${day / 100 % 100}월 ${day % 100}일 (${weekdayName(day)})"
}

class DriveLog(records: Collection<DayRecord> = emptyList()) {
    private val byDay = TreeMap<Int, DayRecord>()

    init { records.forEach { byDay[it.day] = it } }

    /** every recorded day, oldest first */
    val days: Collection<DayRecord> get() = byDay.values
    val isEmpty: Boolean get() = byDay.isEmpty()
    val size: Int get() = byDay.size

    fun day(day: Int): DayRecord? = byDay[day]

    fun inMonth(month: Int): List<DayRecord> = byDay.subMap(Days.first(month), true, month * 100 + 31, true).values.toList()

    /**
     * Adds a finished drive to the day it ended on. A drive shorter than [MIN_KM] (moving the car in a car park) is not
     * kept: false, and nothing changes.
     */
    fun addTrip(day: Int, t: TripResult): Boolean {
        if (t.km < MIN_KM) return false
        byDay[day] = (byDay[day] ?: DayRecord(day)).plus(t)
        while (byDay.size > MAX_DAYS) byDay.pollFirstEntry()
        return true
    }

    /** demo / tests: put a whole day in place */
    fun put(r: DayRecord) { byDay[r.day] = r; while (byDay.size > MAX_DAYS) byDay.pollFirstEntry() }

    fun clear() = byDay.clear()

    private fun between(from: Int, to: Int): Collection<DayRecord> = byDay.subMap(from, true, to, true).values

    fun stats(today: Int, month: Int = Days.month(today)): DriveStats {
        val last7 = between(Days.plus(today, -6), today)
        val prev7 = between(Days.plus(today, -13), Days.plus(today, -7))
        val a7 = average(last7)
        val p7 = average(prev7)
        val r7 = rate(last7)
        val pr7 = rate(prev7)
        val m = inMonth(month)
        return DriveStats(
            avg7 = a7, avg30 = average(between(Days.plus(today, -29), today)),
            // the change is of the two shown numbers, so "86점, 지난주 78점" always reads as +8
            weekDelta = if (a7 != null && p7 != null) a7 - p7 else null,
            weekRate = r7, weekRateDelta = if (r7 != null && pr7 != null) r7 - pr7 else null,
            monthDays = m.size, monthKm = m.sumOf { it.km.toDouble() }.toFloat(), monthAvg = average(m),
        )
    }

    /**
     * Driven days in a row with a score of [GOOD_SCORE] or more, counting back from the last driven day. Days without
     * driving, and driven days without a turn (no score), are skipped: they neither break nor extend the run.
     */
    fun goodStreak(): Int {
        var n = 0
        for (r in byDay.descendingMap().values) {
            val s = r.score ?: continue
            if (s < GOOD_SCORE) break
            n++
        }
        return n
    }

    fun goodDaysIn(month: Int): Int = inMonth(month).count { (it.score ?: -1) >= GOOD_SCORE }

    fun totalGoodSignals(): Int = byDay.values.sumOf { it.good }

    /** the distance driven on days that scored [GOOD_SCORE] or more */
    fun goodKm(): Float = byDay.values.filter { (it.score ?: -1) >= GOOD_SCORE }.sumOf { it.km.toDouble() }.toFloat()

    companion object {
        /** drives shorter than this are not recorded */
        const val MIN_KM = 0.5f
        /** the oldest days are dropped beyond this many (about three years of daily driving) */
        const val MAX_DAYS = 1100
        /** a "good" day: green on the calendar, and what the streak badges count */
        const val GOOD_SCORE = 90

        /** fewer turns than this and the day is not scored: one turn would make the day 0 or 100 on luck */
        const val MIN_TURNS = 3

        fun score(good: Int, missed: Int): Int? = if (good + missed < MIN_TURNS) null else (good * 100f / (good + missed)).roundToInt()

        /**
         * The share of turns made with the signal on, over all the turns of the days that have a score, in %. Not the mean of
         * the days' scores: a day with 30 turns weighs more than a day with 3, and a short day cannot swing the average.
         */
        private fun average(rs: Collection<DayRecord>): Int? {
            val scored = rs.filter { it.score != null }
            val turns = scored.sumOf { it.turns }
            return if (turns == 0) null else (scored.sumOf { it.good } * 100f / turns).roundToInt()
        }

        /** the same number as [average]: one way of counting, so the tiles never show two different "rates" */
        private fun rate(rs: Collection<DayRecord>): Int? = average(rs)

        /** "20261112,12.40,3,11,1,0,1,86;…" (one day per entry, oldest first) */
        fun encode(log: DriveLog): String = log.days.joinToString(";") {
            String.format(Locale.ROOT, "%d,%.2f,%d,%d,%d,%d,%d,%d", it.day, it.km, it.trips, it.good, it.missed, it.jerky, it.harsh, it.points)
        }

        /** the opposite of [encode]; a broken entry is skipped instead of losing the rest */
        fun decode(text: String?): DriveLog {
            if (text.isNullOrBlank()) return DriveLog()
            val list = text.split(';').mapNotNull { e ->
                val f = e.split(',')
                if (f.size < 8) return@mapNotNull null
                try {
                    val day = f[0].trim().toInt()
                    Days.date(day)   // throws on a day that does not exist
                    DayRecord(day, f[1].trim().toFloat(), f[2].trim().toInt(), f[3].trim().toInt(), f[4].trim().toInt(), f[5].trim().toInt(), f[6].trim().toInt(), f[7].trim().toInt())
                } catch (_: Exception) { null }
            }
            return DriveLog(list)
        }
    }
}

// ---------------------------------------------------------------- badges

/** which picture a badge's medal carries */
enum class Medal { FIRST, STREAK3, STREAK7, MONTH, SIGNAL, ROAD }

/**
 * One badge: what to do ([condition]), how far along it is ([progress], read from the record on a given day) and its
 * reward. A [monthly] badge can be earned again each month (its reward is paid once per month); every other badge once.
 * New badges are added to [Badges.all]; nothing else needs to change.
 */
class Badge(
    val id: String,
    val name: String,
    val condition: String,
    val goal: Int,
    val unit: String,
    val reward: Int,
    val medal: Medal,
    val monthly: Boolean = false,
    val progress: (log: DriveLog, today: Int) -> Int,
) {
    /** "2/3일" */
    fun progressLabel(p: Int): String = "${p.coerceAtMost(goal)}/$goal$unit"
}

object Badges {
    val all: List<Badge> = listOf(
        Badge("first", "첫 운전 기록", "0.5km 넘게 달려서 첫 운전 기록을 남기면 받아요.", 1, "일", 30, Medal.FIRST) { log, _ -> log.size },
        Badge("streak3", "깜빡이 90점 3일 연속", "깜빡이 사용 점수 90점 이상인 날이 3일 이어지면 받아요. 운전하지 않은 날은 건너뛰고 세요.", 3, "일", 20, Medal.STREAK3) { log, _ -> log.goodStreak() },
        Badge("streak7", "깜빡이 90점 7일 연속", "깜빡이 사용 점수 90점 이상인 날이 7일 이어지면 받아요. 운전하지 않은 날은 건너뛰고 세요.", 7, "일", 50, Medal.STREAK7) { log, _ -> log.goodStreak() },
        Badge("month10", "한 달 90점 10일", "한 달 안에 깜빡이 사용 점수 90점 이상인 날이 10일이면 받아요. 매달 다시 받을 수 있어요.", 10, "일", 100, Medal.MONTH, monthly = true) { log, today -> log.goodDaysIn(Days.month(today)) },
        Badge("signal100", "깜빡이 정상 사용 100회", "깜빡이를 켜고 회전한 횟수가 모두 100번이 되면 받아요.", 100, "번", 50, Medal.SIGNAL) { log, _ -> log.totalGoodSignals() },
        Badge("km100", "90점 주행 누적 100km", "깜빡이 사용 점수 90점 이상인 날에 달린 거리가 모두 100km가 되면 받아요.", 100, "km", 50, Medal.ROAD) { log, _ -> log.goodKm().toInt() },
    )

    fun get(id: String): Badge? = all.firstOrNull { it.id == id }
}

/** a badge given (for a monthly badge, one month of it): the day it was given and the points that came with it */
data class Award(val key: String, val day: Int, val points: Int) {
    val badgeId: String get() = key.substringBefore('@')
}

/**
 * The badges earned so far. They stay when the driving record is deleted, and the key of each award (the badge's id, plus
 * the month for a monthly badge) is what makes sure no reward is ever paid twice.
 */
class BadgeBook(awards: Collection<Award> = emptyList()) {
    private val got = LinkedHashMap<String, Award>()

    init { awards.forEach { got[it.key] = it } }

    val awards: Collection<Award> get() = got.values

    private fun keyOf(b: Badge, today: Int) = if (b.monthly) "${b.id}@${Days.month(today)}" else b.id

    fun earned(b: Badge): Boolean = got.values.any { it.badgeId == b.id }

    /** how many times it was given (a monthly badge can be given once a month) */
    fun times(b: Badge): Int = got.values.count { it.badgeId == b.id }

    /** the first day it was given, or null */
    fun firstDay(b: Badge): Int? = got.values.filter { it.badgeId == b.id }.minOfOrNull { it.day }

    /** for a monthly badge: already given this month? */
    fun earnedThisMonth(b: Badge, today: Int): Boolean = got.containsKey(keyOf(b, today))

    fun earnedCount(): Int = Badges.all.count { earned(it) }

    /** the earned badges, the most recently given first (awards are only ever added in the order they were given) */
    fun recent(): List<Badge> = got.values.toList().asReversed().mapNotNull { Badges.get(it.badgeId) }.distinct()

    fun awardsOn(day: Int): List<Award> = got.values.filter { it.day == day }

    /**
     * Checks every badge against the record on [today]; each one reached for the first time (this month, for a monthly one)
     * is given and its points paid through [pay]. Returns the badges given now (empty when nothing new).
     */
    fun evaluate(log: DriveLog, today: Int, pay: (Int) -> Unit): List<Badge> {
        val fresh = mutableListOf<Badge>()
        for (b in Badges.all) {
            val key = keyOf(b, today)
            if (got.containsKey(key)) continue
            if (b.progress(log, today) < b.goal) continue
            got[key] = Award(key, today, b.reward)
            pay(b.reward)
            fresh += b
        }
        return fresh
    }

    /** demo / tests only: forget every badge */
    fun clear() = got.clear()

    companion object {
        fun encode(b: BadgeBook): String = b.awards.joinToString(";") { "${it.key},${it.day},${it.points}" }

        fun decode(text: String?): BadgeBook {
            if (text.isNullOrBlank()) return BadgeBook()
            return BadgeBook(text.split(';').mapNotNull { e ->
                val f = e.split(',')
                if (f.size < 3 || f[0].isBlank()) null else try { Award(f[0].trim(), f[1].trim().toInt(), f[2].trim().toInt()) } catch (_: Exception) { null }
            })
        }
    }
}
