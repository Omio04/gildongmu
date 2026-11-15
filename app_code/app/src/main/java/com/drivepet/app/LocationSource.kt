package com.drivepet.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

/**
 * Fresh GPS speed with reported accuracy feeds the experimental score (no Pleos SDK reports the car's speed).
 * In this build the region comes from the navigation (NaviSource, Pleos NaviHelper), so PetHub passes a no-op [onFix].
 * Coordinates and individual speed samples are never stored or sent. Network positions never supply speed.
 */
class LocationSource(
    private val ctx: Context,
    private val onFix: (Double, Double) -> Unit,
    private val onSpeed: (Long, Long, Double, Double, Long) -> Unit,
    private val onSpeedMissing: () -> Unit,
) : LocationListener {
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
                    m.requestLocationUpdates(p, if (p == LocationManager.GPS_PROVIDER) 1000L else 30_000L,
                        if (p == LocationManager.GPS_PROVIDER) 0f else 30f, this, ctx.mainLooper)
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

    override fun onLocationChanged(l: Location) {
        onFix(l.latitude, l.longitude)
        if (l.provider != LocationManager.GPS_PROVIDER) return
        @Suppress("DEPRECATION") val mock = l.isFromMockProvider
        if (mock || !l.hasSpeed() || !l.hasSpeedAccuracy()) { onSpeedMissing(); return }
        onSpeed(l.elapsedRealtimeNanos / 1_000_000L, l.time, l.speed.toDouble(),
            l.speedAccuracyMetersPerSecond.toDouble(), android.os.SystemClock.elapsedRealtime())
    }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { if (provider == LocationManager.GPS_PROVIDER) onSpeedMissing() }
}
