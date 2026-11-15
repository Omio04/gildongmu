package com.drivepet.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Pleos Vehicle SDK, read only: gear, turn signal, steering wheel angle, brake pedal, odometer, fuel and battery,
 * the distance the car can still go, the driver's seat belt and the windscreen wiper.
 * Permissions: pleos.car.permission.CAR_DRIVE (gear, steering, brake), CAR_LIGHTS (turn signal), CAR_ENERGY (fuel, battery),
 * CAR_SEATS (seat belt) and CAR_WINDOWS (wiper). Nothing in the car is ever set or moved by this app.
 * Every callback is posted to the main thread, so the pet engine only ever runs on one thread.
 */
class VehicleSource(
    context: Context,
    private val onGear: (String) -> Unit,
    private val onSignal: (Signal) -> Unit,
    private val onBrake: (Boolean) -> Unit,
    private val onAngle: (Float) -> Unit,
    private val onDistance: (Float) -> Unit,   // the odometer reading; the unit is assumed to be km (NOT verified on a car)
    private val onFuel: (Int) -> Unit,         // fuel percent
    private val onBattery: (Int) -> Unit,      // EV battery state of charge, percent
    private val onRange: (Float) -> Unit,      // how far the car can still go, as the car reports it (see PetHub.onRange for the unit)
    private val onSeatBelt: (Boolean) -> Unit, // the driver's seat belt is buckled
    private val onWiper: (Boolean) -> Unit,    // the windscreen wiper is running
    private val onLog: (String) -> Unit,
) {
    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    /** true once any real value arrived from the car (so the UI can tell "connected" from "demo only"). */
    @Volatile var gotData = false
        private set

    private fun ui(block: () -> Unit) = main.post { gotData = true; block() }
    private fun fail(what: String, e: Exception?) = main.post { onLog("$what: ${e?.message}") }

    private fun gearLetter(g: VehicleGear?): String? = when (g) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_UNKNOWN, null -> null
        else -> "D"   // GEAR_DRIVE and the numbered gears
    }

    private fun signal(v: TurnSignalValue?) = when (v) {
        TurnSignalValue.LEFT -> Signal.LEFT
        TurnSignalValue.RIGHT -> Signal.RIGHT
        else -> Signal.OFF
    }

    fun start() {
        try {
            vehicle.initialize()
            started = true
        } catch (e: Exception) {
            onLog("vehicle sdk failed: ${e.message}")
            return
        }
        // each signal is wrapped on its own: one unsupported signal must not take the others down
        guard("gear") {
            vehicle.getGear().getCurrentGearSelectionState({ g -> gearLetter(g)?.let { ui { onGear(it) } } }, { fail("gear read", it) })
            vehicle.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) { gearLetter(gear)?.let { ui { onGear(it) } } }
                override fun onFailed(e: Exception) { fail("gear listener", e) }
            })
        }
        guard("turn signal") {
            vehicle.getTurnSignal().getCurrentTurnSignalState({ v -> ui { onSignal(signal(v)) } }, { fail("turn signal read", it) })
            vehicle.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(value: TurnSignalValue?) { ui { onSignal(signal(value)) } }
                override fun onFailed(e: Exception) { fail("turn signal listener", e) }
            })
        }
        guard("steering") {
            vehicle.getSteeringWheel().getCurrentSteeringWheelAngle({ a -> if (a != null) ui { onAngle(a) } }, { fail("steering read", it) })
            vehicle.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) { if (angle != null) ui { onAngle(angle) } }
                override fun onFailed(e: Exception) { fail("steering listener", e) }
            })
        }
        guard("odometer") {
            vehicle.getOdometer().getCurrentDistanceDriven({ d -> if (d != null) ui { onDistance(d) } }, { fail("odometer read", it) })
            vehicle.getOdometer().registerDistanceDriven(object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(distance: Float?) { if (distance != null) ui { onDistance(distance) } }
                override fun onFailed(e: Exception) { fail("odometer listener", e) }
            })
        }
        // how full the tank or the battery is: the pet is hungry when it is low
        guard("fuel") {
            vehicle.getFuel().getFuelPercent({ p -> if (p != null) ui { onFuel(p) } }, { fail("fuel read", it) })
            vehicle.getFuel().registerFuelPercent(object : FuelPercentListener {
                override fun onFuelPercentUpdated(percent: Int?) { if (percent != null) ui { onFuel(percent) } }
                override fun onFailed(e: Exception) { fail("fuel listener", e) }
            })
        }
        guard("battery") {
            vehicle.getEvBattery().getEvBatteryStateOfCharge({ p -> if (p != null) ui { onBattery(p) } }, { fail("battery read", it) })
            vehicle.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(percent: Int?) { if (percent != null) ui { onBattery(percent) } }
                override fun onFailed(e: Exception) { fail("battery listener", e) }
            })
        }
        guard("range") {
            vehicle.getOdometer().getRangeRemainingDistance({ d -> if (d != null) ui { onLog("range remaining = $d"); onRange(d) } }, { fail("range read", it) })
            vehicle.getOdometer().registerRangeRemainingListener(object : RangeRemainingListener {
                override fun onRangeRemainingChanged(distance: Float?) { if (distance != null) ui { onRange(distance) } }
                override fun onFailed(e: Exception) { fail("range listener", e) }
            })
        }
        // the driver's seat (row 1 left: left-hand-drive cars, as sold in Korea)
        guard("seat belt") {
            val driver = SeatArea(SeatValue.ROW_1_LEFT)
            vehicle.getSeat().getCurrentSeatBeltBuckledState(driver, { b -> if (b != null) ui { onLog("seat belt buckled = $b"); onSeatBelt(b) } }, { fail("seat belt read", it) })
            vehicle.getSeat().registerSeatBeltBuckledState(driver, object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) { if (buckled != null) ui { onSeatBelt(buckled) } }
                override fun onFailed(e: Exception) { fail("seat belt listener", e) }
            })
        }
        guard("wiper") {
            val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
            vehicle.getWiper().getCurrentWiperState(front, { s -> ui { onLog("wiper = $s"); onWiper(s == WiperState.ON) } }, { fail("wiper read", it) })
            vehicle.getWiper().registerWiperState(front, object : WiperStateListener {
                override fun onWiperStateUpdated(state: WiperState?) { ui { onWiper(state == WiperState.ON) } }
                override fun onFailed(e: Exception) { fail("wiper listener", e) }
            })
        }
        guard("brake") {
            vehicle.getBrake().getCurrentBrakePedalStatus({ p -> if (p != null) ui { onBrake(p) } }, { fail("brake read", it) })
            vehicle.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(pressed: Boolean?) { if (pressed != null) ui { onBrake(pressed) } }
                override fun onFailed(e: Exception) { fail("brake listener", e) }
            })
        }
        onLog("vehicle sdk started")
    }

    private fun guard(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { onLog("$name failed: ${e.message}") }
    }

    fun stop() {
        if (!started) return
        try { vehicle.release() } catch (_: Exception) {}
    }
}
