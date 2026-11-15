package com.drivepet.app

/**
 * How grown the pet is, by the distance driven together (the total, never reset).
 * 부화기 (egg) -> 유아기 (infant, in half a shell) -> 반항기 (rebel) -> 성인기 (adult, fully grown at 5000 km).
 * The thresholds are first guesses; only the 1000 km (infant) and 5000 km (adult) ones were given.
 */
enum class GrowthStage(val label: String, val fromKm: Float) {
    EGG("부화기", 0f),
    INFANT("유아기", 1000f),
    REBEL("반항기", 3000f),
    ADULT("성인기", 5000f);

    companion object {
        /**
         * The egg is a plain, sleeping egg only for the first drives: after this many km it cracks, a face and two ears
         * come out and it starts to bounce. About one ordinary day of driving (a commute there and back), so something
         * already happens the first or second time the app is opened again.
         */
        const val EGG_WAKES_KM = 30f
        fun eggAwake(totalKm: Float) = totalKm >= EGG_WAKES_KM

        fun of(totalKm: Float): GrowthStage = values().last { totalKm >= it.fromKm }

        /** km still to drive to reach the next stage, or null when the pet is fully grown */
        fun kmToNext(totalKm: Float): Float? = values().firstOrNull { it.fromKm > totalKm }?.let { it.fromKm - totalKm }
    }
}
