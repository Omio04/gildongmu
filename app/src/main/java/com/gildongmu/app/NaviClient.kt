package com.gildongmu.app

import android.content.Context
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** What the navigation app reports about the trip in progress. */
data class Trip(val destination: String, val remainMin: Int, val remainKm: Double)

/** Where the car is, as the navigation knows it. Used to say where a hazard was seen; never shown to other drivers. */
data class Place(val lat: Double, val lon: Double, val heading: Double, val address: String)

/** A navigation-known hazard zone ahead (falling rocks, wildlife crossing, road damage), and how far it is. */
data class ZoneAhead(val kind: String, val metres: Int)

/**
 * Reads the destination and remaining time/distance from the car's navigation
 * through Pleos NaviHelper (read-only: permission pleos.car.permission.NAVI_ROUTE).
 * Without a running route the callback never fires and the app keeps its default.
 */
class NaviClient(
    context: Context,
    private val onTrip: (Trip) -> Unit,
    private val onState: (String) -> Unit,
    private val onZone: (ZoneAhead) -> Unit = {},
    private val onTurn: (Turn?) -> Unit = {},
    private val onWaypoint: (Int?) -> Unit = {},   // minutes along the real route to the first waypoint, or null when there is none
) {
    /** The last position the navigation gave us (null until it has one). It may be old: see [fresh]. */
    @Volatile var place: Place? = null
        private set
    @Volatile private var placeAt = 0L

    /** The navigation's position, or null when it has not given one lately (then the platform GPS is the better source). */
    fun fresh(maxAgeMs: Long = 30_000L): Place? =
        place?.takeIf { System.currentTimeMillis() - placeAt <= maxAgeMs }


    private val helper = NaviHelper(context.applicationContext)
    private var ready = false

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            onTrip(Trip(info.name ?: "", info.remainTime / 60, info.remainDistance / 1000.0))
        }

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            place = Place(info.latitude, info.longitude, info.heading, info.address ?: "")
            placeAt = System.currentTimeMillis()
        }

        // the navigation knows where falling-rock, wildlife and road-damage zones are: a real signal, not a camera
        override fun onCautionPointInfo(points: List<ai.pleos.playground.navi.data.CautionPoint>) {
            for (p in points) {
                val kind = when (p.type) {
                    ai.pleos.playground.navi.constants.CautionPointType.FALLING_ROCK_AREA -> "rockfall"
                    ai.pleos.playground.navi.constants.CautionPointType.WILDLIFE_CROSSING -> "roadkill"
                    ai.pleos.playground.navi.constants.CautionPointType.ROAD_DAMAGE_ALERT -> "pothole"
                    else -> null
                } ?: continue
                if (p.remainDistance in 0..800) onZone(ZoneAhead(kind, p.remainDistance))
            }
        }

        // the next manoeuvre (turn, ramp, roundabout...) with how far away it is
        override fun onTBTInfo(list: List<ai.pleos.playground.navi.data.TBTInfo>) {
            val next = list.firstOrNull { it.type.name != "TURN_UNKNOWN" } ?: list.firstOrNull()
            onTurn(next?.let { Turns.from(it.type.name, it.distance, it.description) })
        }

        // the route is over: nothing to turn at any more
        override fun onRouteEnded() { onTurn(null) }
        override fun onRouteCancelled() { onTurn(null) }
        override fun onDestinationArrived(info: ai.pleos.playground.navi.data.DestinationArrivedInfo) { onTurn(null) }

        // the shop added as a waypoint: the navigation says how long the real road to it is
        override fun onWayPointInfo(list: List<ai.pleos.playground.navi.data.WaypointInfo>) {
            onWaypoint(list.firstOrNull()?.let { (it.remainTime / 60).coerceAtLeast(1) })
        }
        override fun onWaypointChanged(info: ai.pleos.playground.navi.data.WaypointChangedInfo) { try { helper.getWaypointInfo() } catch (_: Exception) {} }
        override fun onDestinationChanged() { helper.getDestinationInfo() }
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) { helper.getDestinationInfo() }
    }

    fun start() {
        try {
            helper.initialize(
                onConnected = {
                    ready = true
                    onState("connected")
                    helper.addListener(listener)
                    helper.getDestinationInfo()
                    helper.getCurrentLocationInfo()
                    helper.getTBTInfo()
                },
                onFailed = { onState("failed: ${it.message}") },
            )
        } catch (e: Exception) {
            onState("failed: ${e.message}")
        }
    }

    /** Demo only: starts a guidance to Daegu so the emulator has a trip to read. */
    fun startDemoRoute(): Boolean {
        if (!ready) return false
        try {
            helper.requestRoute(ai.pleos.playground.navi.data.RouteInfo(
                35.8714, 128.6014, null, null, "대구", "", "대구광역시",
                "", ai.pleos.playground.navi.constants.RouteOption.RECOMMENDED, "", false))
        } catch (e: Exception) { onState("route failed: ${e.message}") }
        return true
    }

    /**
     * Put a shop on the route as the first waypoint (the car's own navigation does the routing). False when there is no
     * connection or the platform refuses (this needs pleos.car.permission.NAVI_ROUTE_SEARCH, declared in debug builds only).
     */
    fun addWaypoint(lat: Double, lon: Double, name: String): Boolean {
        if (!ready) return false
        return try {
            helper.addWaypoint(ai.pleos.playground.navi.data.RequestWaypointInfo(
                lat, lon, name, ai.pleos.playground.navi.constants.WaypointIndex.FIRST, "gildongmu", "", "", "gildongmu"))
            true
        } catch (e: Exception) { onState("waypoint failed: ${e.message}"); false }
    }

    /** Ask the navigation again how long the road to the waypoint is. */
    fun refreshWaypoint() { if (ready) try { helper.getWaypointInfo() } catch (_: Exception) {} }

    /** Ask for the next manoeuvre again (its distance counts down as the car moves): called every few seconds. */
    fun refreshTurn() { if (ready) try { helper.getTBTInfo() } catch (_: Exception) {} }

    /** Ask again (remaining time changes as the car moves). */
    fun refresh() { if (ready) try { helper.getDestinationInfo(); helper.getCurrentLocationInfo() } catch (_: Exception) {} }

    fun stop() {
        try { helper.removeListener(listener); helper.release() } catch (_: Exception) {}
    }
}
