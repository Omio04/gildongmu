package com.gildongmu.app

/** A nearby vehicle as shown in the list. No raw coordinates are ever exposed. */
data class Neighbor(
    val id: String,
    val nickname: String,
    val model: String,
    val dest: String,
    val distanceM: Int,
    val sameDest: Boolean,
    val live: Boolean = false,   // a real person on a phone, not a simulated car
    val say: String? = null,     // what this car said out loud lately: a bubble beside it
    val aheadM: Int = 0,         // metres in front of us (negative: behind), measured by the server from both apps' positions
    val lateralM: Int = 0,       // metres to our right (negative: left)
)

/** One-touch message: the user never types anything while driving. */
data class Preset(val code: String, val label: String)

/** Platform driving state as the app sees it. */
enum class DriveState(val label: String) {
    UNKNOWN("시험 모드(차량 미연결)"),
    PARKED("주차"),
    IDLING("정차 중"),
    MOVING("주행 중"),
}

/** Everything the UI needs about the driving situation. */
data class DriveInfo(
    val state: DriveState,
    val requiresDo: Boolean,
    val restrictions: Int,
    val speedKmh: Float?,
) {
    /**
     * The vehicle's own verdict: every restriction flag set (0x1FF) means the platform allows no interaction at all.
     * Anything less (e.g. 0x50 = no setup flows, no video) leaves one-tap presets available, at any speed:
     * there is no typing here, only big one-tap choices, which is what the Pleos guide asks for.
     */
    val fullyRestricted: Boolean get() = restrictions and FULLY_RESTRICTED == FULLY_RESTRICTED

    /** Touching the quick questions is allowed unless the vehicle restricts everything. */
    val canSend: Boolean get() = !fullyRestricted

    companion object {
        const val FULLY_RESTRICTED = 0x1FF   // CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED
    }
}
