package com.drivepet.app

/**
 * A daily mission: something worth doing with this app, a goal, and a reward in points.
 * The progress comes from what the pet's engine counted today; it starts again from zero every day.
 * Finished missions are collected once with a button, so the reward is never a surprise taken from the driver.
 */
data class Mission(val id: Int, val title: String, val goal: Float, val reward: Int, val unit: String)

object Missions {
    val all = listOf(
        Mission(0, "깜빡이를 켜고 3번 회전하기", 3f, 20, "번"),
        Mission(1, "오늘 5km 함께 달리기", 5f, 20, "km"),
        Mission(2, "거친 순간 없이 한 번 주행하기", 1f, 30, "번"),
        Mission(3, "오늘 2번 주행하기", 2f, 10, "번"),
    )

    /** how far along a mission is, from today's counters */
    fun progress(m: Mission, s: PetSnapshot): Float = when (m.id) {
        0 -> s.signalsToday.toFloat()
        1 -> s.todayKm
        2 -> s.cleanTripsToday.toFloat()
        else -> s.tripsToday.toFloat()
    }

    fun done(m: Mission, s: PetSnapshot) = progress(m, s) >= m.goal
    fun claimed(m: Mission, s: PetSnapshot) = s.claimedMask and (1 shl m.id) != 0

    /** "1 / 3번" or "2.3 / 5km" */
    fun label(m: Mission, s: PetSnapshot): String {
        val p = progress(m, s).coerceAtMost(m.goal)
        return if (m.unit == "km") "%.1f / %.0fkm".format(p, m.goal) else "%d / %d%s".format(p.toInt(), m.goal.toInt(), m.unit)
    }
}
