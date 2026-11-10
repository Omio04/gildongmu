package com.drivepet.app

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** today as a number: 20261110 */
fun currentDayKey(): Int {
    val c = java.util.Calendar.getInstance()
    return c.get(java.util.Calendar.YEAR) * 10000 + (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
}

/** Turn signal state as the engine sees it (independent of the Pleos enum so the engine is unit-testable on the JVM). */
enum class Signal { OFF, LEFT, RIGHT }

/**
 * How the pet looks. NORMAL is the everyday look; the other three are the designed states.
 *  - HURT: the driving has been very rough for a while (the mood value is low)
 *  - ANGRY: rough driving (a missed signal, a sharp wheel, jerky braking) has upset it
 *  - HUNGRY: the car is low on energy (battery for an electric car, otherwise fuel); fills up again after charging / refuelling
 * When more than one applies the order is HURT, ANGRY, HUNGRY, NORMAL.
 */
enum class PetState {
    NORMAL, ANGRY, HUNGRY, HURT;

    companion object {
        const val ANGRY_BELOW = 60          // mood value
        const val HURT_BELOW = 30
        const val LOW_ENERGY_PERCENT = 15

        fun from(mood: Int, hungry: Boolean) = when {
            mood < HURT_BELOW -> HURT
            mood < ANGRY_BELOW -> ANGRY
            hungry -> HUNGRY
            else -> NORMAL
        }

        /** the battery is what counts when the car reports one; otherwise the fuel. Unknown means "not low". */
        fun energyLow(fuelPercent: Int?, batteryPercent: Int?): Boolean =
            if (batteryPercent != null) batteryPercent <= LOW_ENERGY_PERCENT else (fuelPercent != null && fuelPercent <= LOW_ENERGY_PERCENT)
    }
}

enum class Kind { GOOD_SIGNAL, MISSED_SIGNAL, JERKY_BRAKE, HARSH_TURN, CALM, LEVEL_UP, TRIP_END }

/** One thing the pet reacts to. [say] is the short spoken / shown line. */
data class Reaction(val kind: Kind, val say: String, val moodDelta: Int, val xpDelta: Int)

/** What one drive (from moving off to stopping) earned. Nothing is ever taken away. */
data class TripResult(
    val points: Int, val km: Float, val clean: Boolean,
    val good: Int, val missed: Int, val jerky: Int, val harsh: Int,
)

data class PetSnapshot(
    val mood: Int,
    val xp: Int,
    val level: Int,
    val goodSignals: Int,
    val missedSignals: Int,
    val jerkyBrakes: Int,
    val harshTurns: Int,
    val totalKm: Float = 0f,
    val tripCount: Int = 0,
    val lastPoints: Int = 0,
    val lastKm: Float = 0f,
    val points: Int = 0,
    val todayKm: Float = 0f,
    val todayKey: Int = 0,
    /** every point ever earned (spending in the shop does not lower it) */
    val earnedPoints: Int = 0,
    // today's counters for the daily missions (they start again each day)
    val signalsToday: Int = 0,
    val tripsToday: Int = 0,
    val cleanTripsToday: Int = 0,
    val claimedMask: Int = 0,
    /** points earned today in the mini game (capped each day) */
    val gameToday: Int = 0,
) {
    /** how grown the pet is: by the total distance driven together */
    val stage: GrowthStage get() = GrowthStage.of(totalKm)

    /** the look, given whether the car is low on energy (that comes from the vehicle, not from the engine) */
    fun state(hungry: Boolean = false) = PetState.from(mood, hungry)
    val xpIntoLevel: Int get() = xp % PetEngine.XP_PER_LEVEL
}

/**
 * Turns raw vehicle signals (turn signal, steering wheel angle, brake pedal, distance) into the pet's mood and growth.
 *
 * Scoring only ever goes up. Good driving earns points, which are both "evolution points" (xp, they level the pet up
 * and never go down) and "safe-driving points" (the currency for the shop, which are spent when buying).
 * Rough driving earns nothing and makes the pet queasy (mood), but it never takes points away.
 *
 * What it can and cannot see (the Pleos Vehicle SDK gives no speed or acceleration here):
 *  - a turn is "steering wheel beyond [TURN_START_DEG]"; a signal counts if it is on, or was switched off just before.
 *  - "harsh" is the wheel angle changing faster than [HARSH_RATE_DEG_S].
 *  - "jerky braking" is [JERKY_PRESSES] brake presses within [JERKY_WINDOW_MS] — the pedal state only says pressed or not,
 *    so this is a stand-in for a hard stop, not a measured deceleration.
 * Thresholds and amounts are first guesses to be tuned on real driving data.
 *
 * Not thread-safe by design: call from one thread (the UI thread).
 */
class PetEngine(
    private val clock: () -> Long,
    initial: PetSnapshot = PetSnapshot(START_MOOD, 0, 1, 0, 0, 0, 0),
    private val onReaction: (Reaction) -> Unit = {},
) {
    var mood = initial.mood; private set
    var xp = initial.xp; private set
    var points = initial.points; private set
    /** every point ever earned: [points] goes down when something is bought, this never does */
    var earnedPoints = initial.earnedPoints; private set
    var goodSignals = initial.goodSignals; private set
    var missedSignals = initial.missedSignals; private set
    var jerkyBrakes = initial.jerkyBrakes; private set
    var harshTurns = initial.harshTurns; private set
    var totalKm = initial.totalKm; private set
    var tripCount = initial.tripCount; private set
    var lastPoints = initial.lastPoints; private set
    var lastKm = initial.lastKm; private set

    /** what day it is (as a number like 20261110); replaceable in tests */
    var dayKey: () -> Int = { currentDayKey() }
    private var todayKey = initial.todayKey
    /** the distance driven today: it starts again from 0 on a new day, while [totalKm] keeps growing */
    var todayKm = initial.todayKm; private set
    var signalsToday = initial.signalsToday; private set
    var tripsToday = initial.tripsToday; private set
    var cleanTripsToday = initial.cleanTripsToday; private set
    /** which of today's missions have been collected (bit = mission id) */
    var claimedMask = initial.claimedMask; private set
    var gameToday = initial.gameToday; private set

    private fun rollDay() {
        val k = dayKey()
        if (k != todayKey) { todayKey = k; todayKm = 0f; signalsToday = 0; tripsToday = 0; cleanTripsToday = 0; claimedMask = 0; gameToday = 0 }
    }

    var driving = false; private set

    // the drive in progress
    var tripKm = 0f; private set
    /** points earned so far on this drive (live) */
    var tripEarned = 0; private set
    private var tripGood = 0
    private var tripMissed = 0
    private var tripJerky = 0
    private var tripHarsh = 0
    var lastTrip: TripResult? = null; private set

    private var signal = Signal.OFF
    private var signalOffAt = Long.MIN_VALUE / 2
    private var lastAngle: Float? = null
    private var lastAngleAt = 0L
    private var lastTurnAt = Long.MIN_VALUE / 2
    private var lastHarshAt = Long.MIN_VALUE / 2
    private var lastBadAt = Long.MIN_VALUE / 2
    private var lastJerkyAt = Long.MIN_VALUE / 2
    private val brakePresses = ArrayDeque<Long>()
    private var braking = false
    private var calmTicks = 0

    val level: Int get() = 1 + xp / XP_PER_LEVEL

    fun snapshot(): PetSnapshot {
        rollDay()
        return PetSnapshot(mood, xp, level, goodSignals, missedSignals, jerkyBrakes, harshTurns, totalKm, tripCount, lastPoints, lastKm, points, todayKm, todayKey, earnedPoints,
            signalsToday, tripsToday, cleanTripsToday, claimedMask, gameToday)
    }

    /** Pay [cost] safe-driving points. False (and nothing changes) when there are not enough. The pet's level is not affected. */
    fun spend(cost: Int): Boolean {
        if (cost < 0 || cost > points) return false
        points -= cost
        return true
    }

    /** gives the reward of a finished mission, once a day. False when it is not finished yet or was already collected. */
    fun claimMission(m: Mission): Boolean {
        val s = snapshot()
        if (!Missions.done(m, s) || Missions.claimed(m, s)) return false
        claimedMask = claimedMask or (1 shl m.id)
        addPoints(m.reward)
        return true
    }

    /**
     * Points for the coins collected in the mini game. A day's total is capped at [GAME_DAILY_CAP] so the game cannot
     * be farmed instead of driving. Returns how many points were really given.
     */
    fun grantGamePoints(coins: Int): Int {
        rollDay()
        val room = (GAME_DAILY_CAP - gameToday).coerceAtLeast(0)
        val given = minOf(coins.coerceAtLeast(0), room)
        gameToday += given
        addPoints(given)
        return given
    }

    /** demo only: pretend the pet has driven this far together in total (to look at the growth stages) */
    fun demoSetTotalKm(km: Float) { totalKm = km.coerceAtLeast(0f) }

    /** demo only: fill today's mission counters (finished = all done, otherwise partly done) */
    fun demoMissionProgress(finished: Boolean) {
        rollDay()
        if (finished) { signalsToday = 3; todayKm = 5.2f; cleanTripsToday = 1; tripsToday = 2 }
        else { signalsToday = 1; todayKm = 2.3f; cleanTripsToday = 0; tripsToday = 1 }
        claimedMask = 0
        if (totalKm < todayKm) totalKm = todayKm   // the total can never be less than today's distance
    }

    /** demo only: set the mood value directly (to show the angry / hurt looks) */
    fun demoSetMood(value: Int) { mood = value.coerceIn(0, 100) }

    /** demo / test only: points that did not come from driving. The pet's level is not affected. */
    fun addPoints(n: Int) { if (n > 0) { points += n; earnedPoints += n } }

    fun setDriving(d: Boolean) {
        if (d == driving) return
        driving = d
        if (d) {
            lastBadAt = clock()   // grace period: no calm reward the instant the car starts moving
            tripKm = 0f; tripEarned = 0; tripGood = 0; tripMissed = 0; tripJerky = 0; tripHarsh = 0
        } else {
            endTrip()
        }
    }

    /** Distance driven since the last call, in km. Only counts while the car is in drive. */
    fun addDistance(km: Float) {
        if (!driving || km <= 0f) return
        rollDay()
        tripKm += km
        totalKm += km
        todayKm += km
    }

    private fun gain(n: Int) {
        xp += n
        points += n
        earnedPoints += n
        tripEarned += n
    }

    private fun endTrip() {
        if (tripKm < MIN_TRIP_KM) return
        val distancePts = min(MAX_DISTANCE_POINTS, (tripKm * 2f).roundToInt())
        val clean = tripMissed + tripJerky + tripHarsh == 0
        val bonus = if (clean) CLEAN_BONUS else 0
        val before = level
        // what the events already paid during the drive is in xp/points; add the distance and the clean-drive bonus now
        xp += distancePts + bonus
        points += distancePts + bonus
        earnedPoints += distancePts + bonus
        val total = tripEarned + distancePts + bonus
        val moodDelta = if (clean) 4 else 0
        mood = (mood + moodDelta).coerceIn(0, 100)
        lastTrip = TripResult(total, tripKm, clean, tripGood, tripMissed, tripJerky, tripHarsh)
        lastPoints = total; lastKm = tripKm; tripCount++
        rollDay(); tripsToday++; if (clean) cleanTripsToday++
        onReaction(Reaction(Kind.TRIP_END, if (clean) "이번 주행 +${total}점! 아주 부드러웠어" else "이번 주행 +${total}점 모았어", moodDelta, total))
        if (level > before) onReaction(Reaction(Kind.LEVEL_UP, "레벨 업! 이제 ${level}단계야", 0, 0))
    }

    fun onTurnSignal(s: Signal) {
        if (signal != Signal.OFF && s == Signal.OFF) signalOffAt = clock()
        signal = s
    }

    fun onBrake(pressed: Boolean) {
        if (!driving) { braking = pressed; return }
        val now = clock()
        if (pressed && !braking) {
            brakePresses.addLast(now)
            while (brakePresses.isNotEmpty() && now - brakePresses.first() > JERKY_WINDOW_MS) brakePresses.removeFirst()
            if (brakePresses.size >= JERKY_PRESSES && now - lastJerkyAt > JERKY_COOLDOWN_MS) {
                lastJerkyAt = now
                brakePresses.clear()
                jerkyBrakes++; tripJerky++
                bad(Kind.JERKY_BRAKE, "브레이크가 덜컹거려서 깜짝 놀랐어", -12)
            }
        }
        braking = pressed
    }

    fun onSteeringAngle(angle: Float) {
        val now = clock()
        val prev = lastAngle
        val prevAt = lastAngleAt
        lastAngle = angle
        lastAngleAt = now
        if (!driving || prev == null) return

        // a turn begins when the wheel passes the start angle
        if (abs(prev) < TURN_START_DEG && abs(angle) >= TURN_START_DEG && now - lastTurnAt > TURN_COOLDOWN_MS) {
            lastTurnAt = now
            val signalled = signal != Signal.OFF || now - signalOffAt <= SIGNAL_GRACE_MS
            if (signalled) {
                goodSignals++; tripGood++; rollDay(); signalsToday++
                good(Kind.GOOD_SIGNAL, "깜빡이 켜 줘서 고마워! 기분 최고야", +6, SIGNAL_POINTS)
            } else {
                missedSignals++; tripMissed++
                bad(Kind.MISSED_SIGNAL, "깜빡이 없이 돌아서 깜짝 놀랐어", -10)
            }
        }

        // harsh = the wheel moves faster than a smooth turn would
        val dt = now - prevAt
        if (dt in 1L..HARSH_WINDOW_MS) {
            val rate = abs(angle - prev) * 1000f / dt
            if (rate >= HARSH_RATE_DEG_S && abs(angle) >= HARSH_MIN_ANGLE_DEG && now - lastHarshAt > HARSH_COOLDOWN_MS) {
                lastHarshAt = now
                harshTurns++; tripHarsh++
                bad(Kind.HARSH_TURN, "핸들을 확 돌려서 깜짝 놀랐어", -14)
            }
        }
    }

    /** Call about once a second while the car is moving: calm driving slowly cheers the pet up and earns a few points. */
    fun tick() {
        if (!driving) return
        val now = clock()
        if (now - lastBadAt < CALM_AFTER_BAD_MS) { calmTicks = 0; return }
        calmTicks++
        if (calmTicks % CALM_TICKS_PER_STEP == 0) {
            val before = level
            mood = (mood + 2).coerceAtMost(100)
            gain(CALM_POINTS)
            if (level > before) onReaction(Reaction(Kind.LEVEL_UP, "레벨 업! 이제 ${level}단계야", 0, 0))
            else if (calmTicks % (CALM_TICKS_PER_STEP * 4) == 0) onReaction(Reaction(Kind.CALM, "부드러운 운전이라 편안해", +2, CALM_POINTS))
        }
    }

    private fun good(kind: Kind, say: String, moodDelta: Int, pts: Int) {
        val before = level
        mood = (mood + moodDelta).coerceIn(0, 100)
        gain(pts)
        onReaction(Reaction(kind, say, moodDelta, pts))
        if (level > before) onReaction(Reaction(Kind.LEVEL_UP, "레벨 업! 이제 ${level}단계야", 0, 0))
    }

    /** rough driving: only the pet's mood suffers; no points are taken away */
    private fun bad(kind: Kind, say: String, moodDelta: Int) {
        lastBadAt = clock()
        calmTicks = 0
        mood = (mood + moodDelta).coerceIn(0, 100)
        onReaction(Reaction(kind, say, moodDelta, 0))
    }

    companion object {
        const val START_MOOD = 70
        const val XP_PER_LEVEL = 100
        const val TURN_START_DEG = 45f
        const val TURN_COOLDOWN_MS = 4_000L
        const val SIGNAL_GRACE_MS = 1_500L
        const val HARSH_RATE_DEG_S = 360f
        const val HARSH_MIN_ANGLE_DEG = 60f
        const val HARSH_WINDOW_MS = 1_000L
        const val HARSH_COOLDOWN_MS = 3_000L
        const val JERKY_PRESSES = 3
        const val JERKY_WINDOW_MS = 10_000L
        const val JERKY_COOLDOWN_MS = 6_000L
        const val CALM_AFTER_BAD_MS = 20_000L
        const val CALM_TICKS_PER_STEP = 5

        // what earns points
        const val SIGNAL_POINTS = 10
        const val CALM_POINTS = 3
        const val CLEAN_BONUS = 10          // a whole drive without a single rough moment
        const val MAX_DISTANCE_POINTS = 40  // 2 points per km, up to this much per drive
        const val GAME_DAILY_CAP = 50        // most points the mini game can give in a day
        const val MIN_TRIP_KM = 0.1f        // shorter than this is not counted (moving the car in the car park)
    }
}
