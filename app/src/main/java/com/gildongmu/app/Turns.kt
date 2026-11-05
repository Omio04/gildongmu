package com.gildongmu.app

/**
 * The next manoeuvre on the navigation's route, in the words and with the arrow the driver reads at a glance.
 * [distanceM] is how far away it is; [where] is the navigation's own description (the road or direction), if it gave one.
 */
data class Turn(val word: String, val arrow: String, val distanceM: Int, val where: String)

/** Turns the navigation's turn types (the names of the Pleos TurnType values) into [Turn]s. Pure text: no SDK needed. */
object Turns {
    fun from(typeName: String, meters: Double, description: String?): Turn {
        val side = when {
            typeName.endsWith("_L") -> "L"
            typeName.endsWith("_R") -> "R"
            else -> "C"
        }
        val (word, arrow) = when {
            typeName == "TURN_LEFT" -> "좌회전" to "←"
            typeName == "TURN_RIGHT" -> "우회전" to "→"
            typeName == "TURN_STRAIGHT" -> "직진" to "↑"
            typeName == "TURN_SHARP_L" -> "급좌회전" to "↰"
            typeName == "TURN_SHARP_R" -> "급우회전" to "↱"
            typeName == "TURN_KEEP_L" -> "왼쪽 방향" to "↖"
            typeName == "TURN_KEEP_R" -> "오른쪽 방향" to "↗"
            typeName == "TURN_UTURN" -> "U턴" to "↶"
            typeName.startsWith("TURN_ENTER_IC") -> "진입" to sideArrow(side)
            typeName.startsWith("TURN_EXIT_IC") -> "출구" to sideArrow(side)
            typeName.startsWith("TURN_ENTER_ROUNDABOUT") || typeName == "TURN_EXIT_ROUNDABOUT" -> "회전교차로" to "⟳"
            typeName == "TURN_ARRIVE" -> "목적지 도착" to "⚑"
            typeName == "TURN_TUNNEL" -> "터널" to "↑"
            typeName == "TURN_TOLL" -> "톨게이트" to "↑"
            typeName == "TURN_ENTER_SERVICEAREA" -> "휴게소" to sideArrow(side)
            typeName == "TURN_RESTAREA" -> "졸음쉼터" to sideArrow(side)
            typeName == "TURN_DEVIDE_MERGE" -> "분기·합류" to "↑"
            typeName.startsWith("TURN_ENTER_UNDER") || typeName.startsWith("TURN_AVOID_UNDER") -> "지하차도" to sideArrow(side)
            typeName.startsWith("TURN_ENTER_OVER") || typeName.startsWith("TURN_AVOID_OVER") -> "고가도로" to sideArrow(side)
            else -> "안내" to "↑"
        }
        return Turn(word, arrow, meters.toInt().coerceAtLeast(0), description?.trim().orEmpty())
    }

    private fun sideArrow(side: String) = when (side) { "L" -> "↖"; "R" -> "↗"; else -> "↑" }

    /** "80m" under a kilometre (rounded to 10 m so the number does not flicker), "2.4km" above. */
    fun distance(meters: Int): String =
        if (meters >= 1000) "%.1fkm".format(meters / 1000.0) else "${(meters / 10) * 10}m"
}
