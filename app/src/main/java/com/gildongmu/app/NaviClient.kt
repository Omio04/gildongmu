package com.gildongmu.app

import android.content.Context
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** What the navigation app reports about the trip in progress. */
data class Trip(val destination: String, val remainMin: Int, val remainKm: Double)

/**
 * Reads the destination and remaining time/distance from the car's navigation
 * through Pleos NaviHelper (read-only: permission pleos.car.permission.NAVI_ROUTE).
 * Without a running route the callback never fires and the app keeps its default.
 */
class NaviClient(context: Context, private val onTrip: (Trip) -> Unit, private val onState: (String) -> Unit) {

    private val helper = NaviHelper(context.applicationContext)
    private var ready = false

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            onTrip(Trip(info.name ?: "", info.remainTime / 60, info.remainDistance / 1000.0))
        }

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

    /** Ask again (remaining time changes as the car moves). */
    fun refresh() { if (ready) try { helper.getDestinationInfo() } catch (_: Exception) {} }

    fun stop() {
        try { helper.removeListener(listener); helper.release() } catch (_: Exception) {}
    }
}
