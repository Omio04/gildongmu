package com.gildongmu.app

/** Why the car thinks the road ahead has just got worse. Shown to the driver as one short sentence. */
enum class JamReason(val sentence: String) {
    SUDDEN("갑자기 속도가 줄었어요"),
    DELAY("도착이 평소보다 늦어지고 있어요"),
    SLOWDOWN("평소보다 속도가 크게 줄었어요"),
    CRAWL("차가 거의 움직이지 않아요"),
}

/**
 * Decides when the app may offer "앞에 왜 막히는지 물어볼까요?". It has to be rare, or it becomes noise:
 * it fires on a real change (not on a slow road that was always slow), once per slow-down, and then rests.
 *
 *  - SUDDEN:   speed fell by 35 km/h or more within 10 s and is now 40 or less
 *  - DELAY:    the predicted arrival moved 3 minutes or more later than the best prediction of the last 15 min
 *  - SLOWDOWN: after cruising at 50+ km/h on average, now at half of that (and 40 or less) for 20 s
 *  - CRAWL:    20 km/h or less for 60 s
 *
 * Never while parked, never in the first minute (not enough to compare), and not again for 10 minutes.
 * One slow-down is one episode: it ends only after 30 s at 40 km/h or more.
 */
class JamDetector {
    private class Sample(val t: Long, val kmh: Float)
    private class Eta(val t: Long, val arrival: Long)

    private val samples = ArrayDeque<Sample>()
    private val etas = ArrayDeque<Eta>()
    private var firstSeen: Long? = null
    private var slowSince: Long? = null
    private var crawlSince: Long? = null
    private var fastSince: Long? = null
    private var inEpisode = false
    private var lastNudge: Long? = null

    /** Call for every speed reading (and at least every few seconds). Returns a reason when it is time to ask. */
    fun onSpeed(now: Long, kmh: Float, parked: Boolean): JamReason? {
        if (firstSeen == null) firstSeen = now
        if (parked) { reset(); return null }
        samples.addLast(Sample(now, kmh))
        while (samples.isNotEmpty() && now - samples.first().t > KEEP_MS) samples.removeFirst()

        // an episode ends once the road is moving again
        if (kmh >= RESUME_KMH) {
            if (fastSince == null) fastSince = now
            if (now - fastSince!! >= RESUME_MS) { inEpisode = false; slowSince = null; crawlSince = null }
        } else fastSince = null

        val cruise = cruiseKmh(now)
        if (cruise != null && kmh <= cruise * 0.5f && kmh <= SLOW_KMH) { if (slowSince == null) slowSince = now } else slowSince = null
        if (kmh <= CRAWL_KMH) { if (crawlSince == null) crawlSince = now } else crawlSince = null

        val recentMax = samples.filter { now - it.t <= SUDDEN_MS }.maxOfOrNull { it.kmh } ?: kmh
        val reason = when {
            recentMax - kmh >= SUDDEN_DROP && kmh <= SLOW_KMH -> JamReason.SUDDEN
            slowSince != null && now - slowSince!! >= SLOW_MS -> JamReason.SLOWDOWN
            crawlSince != null && now - crawlSince!! >= CRAWL_MS -> JamReason.CRAWL
            else -> null
        }
        return reason?.takeIf { allowed(now) }
    }

    /** Call with the navigation's remaining minutes each time it changes. */
    fun onRemaining(now: Long, remainMin: Int): JamReason? {
        if (firstSeen == null) firstSeen = now
        etas.addLast(Eta(now, now + remainMin * 60_000L))
        while (etas.isNotEmpty() && now - etas.first().t > ETA_KEEP_MS) etas.removeFirst()
        val best = etas.minOf { it.arrival }
        val delayMs = now + remainMin * 60_000L - best
        return if (delayMs >= DELAY_MS && allowed(now)) JamReason.DELAY else null
    }

    /** The app showed the question: start the rest and the episode. */
    fun shown(now: Long) {
        lastNudge = now
        inEpisode = true
    }

    private fun allowed(now: Long): Boolean {
        if (inEpisode) return false
        if (now - (firstSeen ?: now) < WARMUP_MS) return false
        val last = lastNudge
        return last == null || now - last >= COOLDOWN_MS
    }

    /** Average speed while cruising (40+ km/h) between 3 minutes ago and 30 s ago; null if there was not enough of it. */
    private fun cruiseKmh(now: Long): Float? {
        val older = samples.filter { now - it.t in 30_000..180_000 && it.kmh >= 40f }
        if (older.size < 3) return null
        val span = older.last().t - older.first().t
        if (span < 40_000) return null
        val avg = older.map { it.kmh }.average().toFloat()
        return avg.takeIf { it >= CRUISE_MIN_KMH }
    }

    private fun reset() {
        samples.clear(); slowSince = null; crawlSince = null; fastSince = null
    }

    private companion object {
        const val KEEP_MS = 240_000L
        const val ETA_KEEP_MS = 15 * 60_000L
        const val DELAY_MS = 3 * 60_000L
        const val SUDDEN_MS = 10_000L
        const val SUDDEN_DROP = 35f
        const val SLOW_KMH = 40f
        const val SLOW_MS = 20_000L
        const val CRAWL_KMH = 20f
        const val CRAWL_MS = 60_000L
        const val CRUISE_MIN_KMH = 50f
        const val RESUME_KMH = 40f
        const val RESUME_MS = 30_000L
        const val WARMUP_MS = 60_000L
        const val COOLDOWN_MS = 10 * 60_000L
    }
}
