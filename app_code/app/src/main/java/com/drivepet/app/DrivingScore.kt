package com.drivepet.app

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Public Bluelink categories, but entirely OUR experimental thresholds/weights, never Hyundai's formula. */
object ScorePolicy {
    const val VERSION = 1
    const val ACCEL = 3.0 // m/s², conservative lower bound after speed uncertainty
    const val BRAKE = 3.5
    const val MIN_KM = 10.0
    const val MIN_SECONDS = 600.0
    const val MIN_COVERAGE = 0.8
    val KOREA: ZoneId = ZoneId.of("Asia/Seoul")
    fun day(wallMs: Long): Int = Days.of(Instant.ofEpochMilli(wallMs).atZone(KOREA).toLocalDate())
    fun night(wallMs: Long): Boolean = Instant.ofEpochMilli(wallMs).atZone(KOREA).hour.let { it >= 23 || it < 5 }
}

/** Only aggregates are persisted; GPS positions and individual speed samples are not. */
data class ScoredDrive(
    val endedAt: Long,
    val km: Double,
    val observedSeconds: Double,
    val elapsedSeconds: Double,
    val accelerations: Int,
    val brakes: Int,
    val starts: Int,
    val nightSeconds: Double,
    val version: Int = ScorePolicy.VERSION,
) {
    val day get() = ScorePolicy.day(endedAt)
    val coverage get() = if (elapsedSeconds > 0) (observedSeconds / elapsedSeconds).coerceIn(0.0, 1.0) else 0.0
    fun valid(): Boolean = endedAt > 0 && version == ScorePolicy.VERSION &&
        km.isFinite() && km in 0.0..10000.0 && observedSeconds.isFinite() && elapsedSeconds.isFinite() &&
        observedSeconds >= 0 && elapsedSeconds >= observedSeconds && elapsedSeconds <= 7 * 86400 &&
        nightSeconds.isFinite() && nightSeconds in 0.0..observedSeconds &&
        accelerations in 0..100000 && brakes in 0..100000 && starts in 0..100000
}

data class ScoreSummary(
    val km: Double, val seconds: Double, val elapsed: Double,
    val accelerations: Int, val brakes: Int, val starts: Int, val nightSeconds: Double,
    val trips: Int,
) {
    val coverage get() = if (elapsed > 0) (seconds / elapsed).coerceIn(0.0, 1.0) else 0.0
    val ready get() = km >= ScorePolicy.MIN_KM && seconds >= ScorePolicy.MIN_SECONDS && coverage >= ScorePolicy.MIN_COVERAGE
    // Rates per 100 km; each category is capped. Night driving is context, not proof of unsafe behaviour.
    val score: Int? get() = if (!ready) null else (100.0 -
        min(35.0, accelerations * 200.0 / km) - min(35.0, brakes * 200.0 / km) -
        min(20.0, starts * 200.0 / km) - min(10.0, nightSeconds / seconds * 10.0)).roundToInt().coerceIn(0, 100)
    val explanation: String get() = when {
        trips == 0 -> "속도 기록을 기다리고 있어요"
        coverage < ScorePolicy.MIN_COVERAGE -> "속도 수신이 부족해요 · 수신율 ${(coverage * 100).toInt()}% / 필요 80%"
        !ready -> "측정 %.1f/10km · %.0f/10분 · 더 쌓이면 점수가 나와요".format(km, seconds / 60)
        else -> "측정 %.1fkm · 수신율 %d%% · %d회 주행".format(km, (coverage * 100).toInt(), trips)
    }
}

class DrivingHistory(drives: List<ScoredDrive> = emptyList()) {
    private val entries = drives.filter { it.valid() }.toMutableList()
    val size get() = entries.size
    fun add(drive: ScoredDrive) { if (drive.valid()) { entries.add(drive); prune(drive.day) } }
    fun clear() = entries.clear()
    private fun prune(today: Int) { val oldest = Days.plus(today, -89); entries.removeAll { it.day < oldest } }
    fun summary(today: Int, days: Int = 90): ScoreSummary {
        require(days in 1..90)
        val oldest = Days.plus(today, 1 - days)
        val rs = entries.filter { it.day in oldest..today }
        return ScoreSummary(rs.sumOf { it.km }, rs.sumOf { it.observedSeconds }, rs.sumOf { it.elapsedSeconds },
            rs.sumOf { it.accelerations }, rs.sumOf { it.brakes }, rs.sumOf { it.starts }, rs.sumOf { it.nightSeconds }, rs.size)
    }
    fun encode(): String = entries.joinToString(";") { d ->
        listOf(d.endedAt, d.km, d.observedSeconds, d.elapsedSeconds, d.accelerations, d.brakes, d.starts, d.nightSeconds, d.version).joinToString(",")
    }
    companion object {
        fun decode(raw: String?): DrivingHistory = DrivingHistory(raw.orEmpty().split(';').mapNotNull { row ->
            try {
                val f = row.split(','); if (f.size != 9) null else
                    ScoredDrive(f[0].toLong(), f[1].toDouble(), f[2].toDouble(), f[3].toDouble(),
                        f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7].toDouble(), f[8].toInt()).takeIf { it.valid() }
            } catch (_: RuntimeException) { null }
        })
    }
}

/** GPS speed is accepted only with fresh timestamps and explicit accuracy; gaps never imply a calm drive. */
class DrivingScoreTracker {
    private data class Sample(val ms: Long, val wall: Long, val speed: Double, val error: Double)
    private var startAt: Long? = null
    private var previous: Sample? = null
    private var observed = 0.0
    private var km = 0.0
    private var night = 0.0
    private var accel = 0
    private var brake = 0
    private var launch = 0
    private var accelEpisode = false
    private var brakeEpisode = false
    private var lastAccel: Long? = null
    private var lastBrake: Long? = null

    fun begin(nowMs: Long) {
        startAt = nowMs; previous = null; observed = 0.0; km = 0.0; night = 0.0
        accel = 0; brake = 0; launch = 0; accelEpisode = false; brakeEpisode = false
        lastAccel = null; lastBrake = null
    }
    fun breakContinuity() { previous = null; accelEpisode = false; brakeEpisode = false }
    fun sample(ms: Long, wallMs: Long, speedMps: Double, accuracyMps: Double, nowMs: Long) {
        val started = startAt ?: return
        if (ms < started || nowMs - ms !in 0..2000 || !speedMps.isFinite() || speedMps !in 0.0..70.0 ||
            !accuracyMps.isFinite() || accuracyMps !in 0.0..0.5) { breakContinuity(); return }
        val current = Sample(ms, wallMs, speedMps, accuracyMps)
        val prev = previous
        if (prev != null && ms <= prev.ms) return // duplicates/out-of-order fixes do not replace the latest sample
        previous = current
        if (prev == null) return
        val dt = (ms - prev.ms) / 1000.0
        if (dt !in 0.5..2.0 || abs((wallMs - prev.wall) - (ms - prev.ms)) > 1000) {
            accelEpisode = false; brakeEpisode = false; return
        }
        val rate = (speedMps - prev.speed) / dt
        if (abs(rate) > 12.0) { breakContinuity(); return } // reject implausible GPS jumps rather than penalise them
        observed += dt
        km += (speedMps + prev.speed) * 0.5 * dt / 1000
        if (speedMps + prev.speed > 1.0 && ScorePolicy.night(prev.wall + (wallMs - prev.wall) / 2)) night += dt
        val uncertainty = (accuracyMps + prev.error) / dt
        if (rate - uncertainty >= ScorePolicy.ACCEL) {
            if (!accelEpisode && (lastAccel == null || ms - lastAccel!! >= 3000)) {
                if (prev.speed <= 10.0 / 3.6) launch++ else accel++ // no double count with rapid acceleration
                lastAccel = ms
            }
            accelEpisode = true
        } else if (rate < 1.0) accelEpisode = false
        if (-rate - uncertainty >= ScorePolicy.BRAKE) {
            if (!brakeEpisode && (lastBrake == null || ms - lastBrake!! >= 3000)) { brake++; lastBrake = ms }
            brakeEpisode = true
        } else if (rate > -1.0) brakeEpisode = false
    }
    fun finish(nowMs: Long, wallMs: Long): ScoredDrive? {
        val started = startAt ?: return null
        startAt = null; previous = null
        val elapsed = (nowMs - started).coerceAtLeast(0) / 1000.0
        if (elapsed < 10) return null
        return ScoredDrive(wallMs, km, observed.coerceAtMost(elapsed), elapsed, accel, brake, launch, night.coerceAtMost(observed)).takeIf { it.valid() }
    }
}

/** Integration boundary, not a Hyundai API schema. A future authorised backend supplies a dated official value. */
data class OfficialDrivingScore(val value: Int, val asOfDay: Int) {
    init { require(value in 0..100); Days.date(asOfDay) }
}
fun interface OfficialScoreProvider { fun latest(): OfficialDrivingScore? }
object BluelinkNotConnected : OfficialScoreProvider { override fun latest(): OfficialDrivingScore? = null }
