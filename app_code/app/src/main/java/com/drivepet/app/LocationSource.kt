package com.drivepet.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

/**
 * Where the car is, only to find out which 시·도 it is in (the map collection). The position itself is never stored or sent:
 * [onFix] hands it to [PetHub], which keeps only the region id. Uses the platform's GPS (and the network position if that is
 * all there is). Without the location permission nothing happens and the map collection simply does not find anything.
 */
class LocationSource(private val ctx: Context, private val onFix: (Double, Double) -> Unit) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var running = false

    fun hasPermission() =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** safe to call again (after the permission was granted); returns whether updates are on */
    fun start(): Boolean {
        val m = lm ?: return false
        if (running) return true
        if (!hasPermission()) return false
        try {
            var any = false
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (m.isProviderEnabled(p)) {
                    m.requestLocationUpdates(p, UPDATE_MS, MIN_METERS, this, ctx.mainLooper)
                    m.getLastKnownLocation(p)?.let { if (System.currentTimeMillis() - it.time < 2 * 60_000L) onFix(it.latitude, it.longitude) }
                    any = true
                }
            }
            running = any
        } catch (_: SecurityException) { running = false } catch (_: Exception) { running = false }
        return running
    }

    fun stop() {
        if (!running) return
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        running = false
    }

    override fun onLocationChanged(l: Location) { onFix(l.latitude, l.longitude) }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private companion object {
        const val UPDATE_MS = 5_000L
        const val MIN_METERS = 30f
    }
}