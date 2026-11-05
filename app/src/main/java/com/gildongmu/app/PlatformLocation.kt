package com.gildongmu.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/**
 * The car's position straight from the platform (GPS), for when the navigation does not answer. Standard Android
 * location API: on Android Automotive the vehicle's GNSS is exposed this way. Needs the location permission; without
 * it, or without a fix, [place] stays null and the car simply does not appear on anyone's road.
 */
class PlatformLocation(context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile var place: Place? = null
        private set
    @Volatile private var at = 0L

    private val listener = LocationListener { loc -> use(loc) }

    private fun use(loc: Location) {
        place = Place(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else 0.0, "")
        at = System.currentTimeMillis()
    }

    /** The last position, or null when it is older than [maxAgeMs]. */
    fun fresh(maxAgeMs: Long = 20_000L): Place? = if (System.currentTimeMillis() - at <= maxAgeMs) place else null

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            if (LocationManager.GPS_PROVIDER !in manager.allProviders) return
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { use(it) }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    fun stop() { try { manager.removeUpdates(listener) } catch (_: Exception) {} }
}
