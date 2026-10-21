package com.gildongmu.app

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches the platform driving state through the standard AAOS APIs:
 *  - CarUxRestrictionsManager: DO required? + active restriction flags
 *  - CarPropertyManager (PERF_VEHICLE_SPEED): to tell IDLING (stopped in D) from MOVING
 * Emulator measurements: PARKED -> DO off, IDLING/MOVING -> DO on with flags 0x50.
 */
class DrivingMonitor(
    private val context: Context,
    private val onChange: (DriveInfo) -> Unit,
) {
    private val tag = "DrivingMonitor"
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var requiresDo = false
    private var restrictions = 0
    private var speedKmh: Float? = null
    private var connected = false

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        apply(r)
        publish()
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val ms = (value.value as? Float) ?: return
            speedKmh = ms * 3.6f
            publish()
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(tag, "speed error prop=$propId zone=$zone")
        }
    }

    fun start() {
        try {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (ready) attach(c) else {
                    connected = false
                    publish()
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Car API not available: $e")
            publish()
        }
    }

    fun stop() {
        try {
            car?.disconnect()
        } catch (_: Exception) {
        }
        car = null
    }

    private fun attach(c: Car) {
        try {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            ux?.registerListener(uxListener)
            ux?.currentCarUxRestrictions?.let { apply(it) }
            connected = ux != null
        } catch (e: Exception) {
            Log.w(tag, "UX restrictions unavailable: $e")
        }
        try {
            val props = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            props?.registerCallback(
                speedCallback,
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_ONCHANGE,
            )
        } catch (e: Exception) {
            Log.w(tag, "speed unavailable (permission?): $e")
        }
        publish()
    }

    private fun apply(r: CarUxRestrictions) {
        requiresDo = r.isRequiresDistractionOptimization
        restrictions = r.activeRestrictions
    }

    private fun publish() {
        val state = when {
            !connected -> DriveState.UNKNOWN
            !requiresDo -> DriveState.PARKED
            (speedKmh ?: 0f) > MOVING_KMH -> DriveState.MOVING
            else -> DriveState.IDLING
        }
        onChange(DriveInfo(state, requiresDo, restrictions, speedKmh))
    }

    companion object {
        /** Below this the car counts as stopped (traffic jam / red light). */
        const val MOVING_KMH = 3f
    }
}
