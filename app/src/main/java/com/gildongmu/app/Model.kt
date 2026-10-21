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
    val mood: String? = null,    // the fixed feeling shown as a bubble over the car right now
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

/**
 * Local demo data. Later this is replaced by the server (nearby / send / inbox).
 * The shapes match server/core.py so the swap is mechanical.
 */
object DemoData {
    val presets = listOf(
        Preset("HELLO", "안녕하세요! 대구 가는 중이에요"),
        Preset("ASK_REST", "다음 휴게소 혼잡한가요?"),
        Preset("ASK_FOOD", "근처 맛집 있나요?"),
        Preset("ASK_ACCIDENT", "앞에 무슨 일 있나요?"),
    )

    val neighbors = listOf(
        Neighbor("car03", "귀성길곰", "아이오닉 5", "대구", 40, true),
        Neighbor("car07", "느긋한토끼", "그랜저", "대구", 90, true),
        Neighbor("car01", "졸린사자", "쏘나타", "부산", 60, false),
        Neighbor("car05", "달리는펭귄", "EV6", "광주", 120, false),
    )

    /** Reply a neighbour (or public data) would give for each question. */
    fun replyFor(code: String): Reply = when (code) {
        "HELLO" -> Reply("귀성길곰", "반가워요! 저도 대구 가요", false)
        "ASK_REST" -> Reply("느긋한토끼", "천안삼거리휴게소 보통, 안성휴게소는 혼잡해요", false)
        "ASK_FOOD" -> Reply("공공데이터", "호두과자 맛집 추천해요 · 방문 시 쿠폰", true)
        "ASK_ACCIDENT" -> Reply("귀성길곰", "앞쪽 사고 처리 중, 곧 풀려요", false)
        else -> Reply("길동무", "응답이 없어요", true)
    }

    data class Reply(val from: String, val text: String, val auto: Boolean)
}
