package com.drivepet.app

import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/** The next manoeuvre the navigation announces: "좌회전", an arrow, and how many metres away. */
data class Turn(val word: String, val arrow: String, val meters: Int, val description: String)

/** What the navigation reports about the trip in progress. */
data class NavTrip(val destination: String, val remainMin: Int, val remainKm: Double)

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
            else -> "안내" to "↑"
        }
        return Turn(word, arrow, meters.toInt().coerceAtLeast(0), description?.trim().orEmpty())
    }

    private fun sideArrow(side: String) = when (side) { "L" -> "↖"; "R" -> "↗"; else -> "↑" }

    /** "80m" under a kilometre (rounded to 10 m so the number does not flicker), "2.4km" above. */
    fun distance(meters: Int): String =
        if (meters >= 1000) "%.1fkm".format(meters / 1000.0) else "${(meters / 10) * 10}m"
}

/**
 * Reads the route in progress from the car's navigation through Pleos NaviHelper (read only):
 * the destination with remaining time and distance, and the next turn.
 * Permission: pleos.car.permission.NAVI_ROUTE. Without a running route nothing arrives and the pet screen shows nothing.
 * Callbacks are posted to the main thread.
 */
class NaviSource(
    context: Context,
    private val onTrip: (NavTrip?) -> Unit,
    private val onTurn: (Turn?) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val helper = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            main.post { onTrip(NavTrip(info.name ?: "", info.remainTime / 60, info.remainDistance / 1000.0)) }
        }

        // the next manoeuvre (turn, ramp, roundabout...) with how far away it is
        override fun onTBTInfo(list: List<ai.pleos.playground.navi.data.TBTInfo>) {
            val next = list.firstOrNull { it.type.name != "TURN_UNKNOWN" } ?: list.firstOrNull()
            main.post { onTurn(next?.let { Turns.from(it.type.name, it.distance, it.description) }) }
        }

        override fun onRouteEnded() { main.post { onTurn(null); onTrip(null) } }
        override fun onRouteCancelled() { main.post { onTurn(null); onTrip(null) } }
        override fun onDestinationArrived(info: ai.pleos.playground.navi.data.DestinationArrivedInfo) { main.post { onTurn(null); onTrip(null) } }
        override fun onDestinationChanged() { helper.getDestinationInfo() }
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) { helper.getDestinationInfo(); helper.getTBTInfo() }
    }

    fun start() {
        try {
            helper.initialize(
                onConnected = {
                    ready = true
                    main.post { onLog("navi connected") }
                    helper.addListener(listener)
                    helper.getDestinationInfo()
                    helper.getTBTInfo()
                },
                onFailed = { main.post { onLog("navi failed: ${it.message}") } },
            )
        } catch (e: Exception) {
            onLog("navi failed: ${e.message}")
        }
    }

    /** The remaining distance and the next turn change as the car moves: ask again every few seconds. */
    fun refresh() {
        if (!ready) return
        try { helper.getDestinationInfo(); helper.getTBTInfo() } catch (_: Exception) {}
    }

    /** Demo only: starts a guidance so the emulator has a route to read. Needs pleos.car.permission.NAVI_ROUTE_SEARCH (debug build). */
    fun startDemoRoute(): Boolean {
        if (!ready) return false
        try {
            helper.requestRoute(ai.pleos.playground.navi.data.RouteInfo(
                35.8714, 128.6014, null, null, "대구", "", "대구광역시",
                "", ai.pleos.playground.navi.constants.RouteOption.RECOMMENDED, "", false))
        } catch (e: Exception) { onLog("route failed: ${e.message}") }
        return true
    }

    fun stop() {
        try { helper.removeListener(listener); helper.release() } catch (_: Exception) {}
    }
}
