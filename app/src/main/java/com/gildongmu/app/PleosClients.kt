package com.gildongmu.app

import android.content.Context
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener

/**
 * Pleos Vehicle SDK, read-only: the gear (P / R / N / D) and the brake pedal.
 * Together with the speed from the platform they tell "parked" from "stopped in traffic" (D, brake held, 0 km/h) from "moving".
 * Permission: pleos.car.permission.CAR_DRIVE (read only; nothing is ever set).
 */
class PleosVehicleClient(
    context: Context,
    private val onGear: (String) -> Unit,
    private val onBrake: (Boolean) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val vehicle = Vehicle(context.applicationContext)
    @Volatile var gear: String? = null
        private set
    @Volatile var brakePressed: Boolean? = null
        private set

    private fun gearLetter(g: VehicleGear?): String? = when (g) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_UNKNOWN -> null
        else -> "D"   // GEAR_DRIVE and the numbered gears
    }

    private fun setGear(g: VehicleGear?) {
        val letter = gearLetter(g) ?: return
        gear = letter
        onGear(letter)
    }

    fun start() {
        try {
            vehicle.initialize()
            vehicle.getGear().getCurrentGearSelectionState({ setGear(it) }, { onState("gear read failed: ${it?.message}") })
            vehicle.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) { setGear(gear) }
                override fun onFailed(e: Exception) { onState("gear listener failed: ${e.message}") }
            })
            vehicle.getBrake().getCurrentBrakePedalStatus({ p -> if (p != null) { brakePressed = p; onBrake(p) } }, { onState("brake read failed: ${it?.message}") })
            vehicle.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
                    val p = pressed ?: return
                    brakePressed = p
                    onBrake(p)
                }
                override fun onFailed(e: Exception) { onState("brake listener failed: ${e.message}") }
            })
            onState("vehicle sdk started")
        } catch (e: Exception) {
            onState("vehicle sdk failed: ${e.message}")
        }
    }

    fun stop() { try { vehicle.release() } catch (_: Exception) {} }
}

/**
 * Pleos FusedLocation SDK: the car's own fused position. Used first when the platform supports it; if it does not
 * (or gives nothing lately) the navigation's position and then the platform GPS take over.
 * Permission: pleos.car.permission.FUSED_LOCATION. Started only after the driver agreed to the privacy notice.
 */
class PleosFusedClient(context: Context, private val onState: (String) -> Unit) {
    private val fused = FusedLocation(context.applicationContext)
    @Volatile private var last: Place? = null
    @Volatile private var at = 0L
    @Volatile var supported = false
        private set

    private val listener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            if (data == null) return
            last = Place(data.latitude, data.longitude, data.heading.toDouble(), "")
            at = System.currentTimeMillis()
        }
        override fun onError(exception: Exception) { onState("fused location error: ${exception.message}") }
    }

    fun fresh(maxAgeMs: Long = 20_000L): Place? = last?.takeIf { System.currentTimeMillis() - at <= maxAgeMs }

    fun start() {
        try {
            fused.initialize()
            supported = fused.isSupported()
            onState(if (supported) "fused location supported" else "fused location not supported here")
            if (supported) {
                fused.getLastKnownLocation(listener)
                fused.registerFusedLocationCallback(listener)
            }
        } catch (e: Exception) {
            onState("fused location failed: ${e.message}")
        }
    }

    fun stop() { try { fused.unregisterFusedLocationCallback(listener); fused.release() } catch (_: Exception) {} }
}
