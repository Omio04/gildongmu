package com.gildongmu.app

/**
 * "가는 길 픽업": places on the way where something can be picked up. SAMPLE data (fictional shops, made-up
 * distances): nothing here is a real shop, and no payment is made. The unit is the kind of place, not a menu.
 */
data class Shop(
    val name: String,
    val types: List<String>,
    val km: Double,        // sample distance ahead
    val waitMin: Int,      // sample preparation time
    val item: String,      // the popular item
    val price: Int,
) {
    fun priceText() = "%,d원".format(price)
    fun etaMin() = Errands.etaMin(km)
    /** Minutes from now until the shop should start cooking, so it is ready when the car arrives. */
    fun cookInMin() = maxOf(0, etaMin() - waitMin)
}

object Errands {
    val TYPES = listOf("드라이브스루", "휴게소", "간식·음료", "식사", "주유·편의점")

    private val places = listOf(
        Shop("해뜰커피 DT", listOf("드라이브스루", "간식·음료"), 2.1, 3, "아메리카노 1잔", 4500),
        Shop("든든버거 DT", listOf("드라이브스루", "식사"), 3.0, 4, "치즈버거 세트", 7900),
        Shop("고소베이커리 DT", listOf("드라이브스루", "간식·음료"), 4.5, 2, "소금빵 2개", 5200),
        Shop("산들휴게소 푸드코트", listOf("휴게소", "식사"), 12.0, 6, "우동 세트", 7000),
        Shop("산들휴게소 호두과자", listOf("휴게소", "간식·음료"), 12.2, 3, "호두과자 한 봉지", 4000),
        Shop("들꽃쉼터 매점", listOf("휴게소", "간식·음료"), 7.0, 2, "컵라면", 2500),
        Shop("정든도시락", listOf("식사"), 5.5, 8, "불고기 도시락", 6500),
        Shop("초록주유소 편의점", listOf("주유·편의점", "간식·음료"), 3.8, 1, "생수와 샌드위치", 4800),
        Shop("별빛충전소 편의점", listOf("주유·편의점"), 9.0, 1, "에너지 음료", 2200),
    )

    fun near(type: String): List<Shop> = places.filter { type in it.types }.sortedBy { it.km }.take(3)

    /** About 50 km/h on average. */
    fun etaMin(km: Double) = maxOf(1, Math.round(km * 1.2).toInt())

    fun receiptNo(): String = "D-" + (1000 + Math.abs((System.nanoTime() % 9000).toInt()))

    private val ASK = listOf("주문", "픽업", "먹", "마실", "뭐 있", "뭐있", "어디", "있어")
    private val NOT_PICKUP = listOf("주차", "막혀", "막힘", "혼잡", "사고", "정체", "낙하", "공사")

    /**
     * The kind of place a spoken sentence asks for, or null. A question about parking, jams or accidents is
     * never taken for an order: those go to the neighbours as usual.
     */
    fun matchType(spoken: String): String? {
        val w = spoken.replace(" ", "")
        if (NOT_PICKUP.any { it in w }) return null
        if (ASK.none { it.replace(" ", "") in w }) return null
        return when {
            listOf("드라이브스루", "디티", "dt").any { it in w.lowercase() } -> "드라이브스루"
            listOf("휴게소", "졸음쉼터").any { it in w } -> "휴게소"
            listOf("주유", "충전", "편의점").any { it in w } -> "주유·편의점"
            listOf("식사", "밥", "도시락", "점심", "저녁").any { it in w } -> "식사"
            listOf("간식", "음료", "커피", "마실").any { it in w } -> "간식·음료"
            listOf("뭐먹", "먹을", "뭐있", "픽업").any { it in w } -> "드라이브스루"
            else -> null
        }
    }

    /** Which listed place a spoken answer picks ("맥도날드" style: by the first two letters, or "첫 번째"). */
    fun pick(list: List<Shop>, spoken: String): Shop? {
        val w = spoken.replace(" ", "")
        list.firstOrNull { p -> p.name.replace(" ", "").let { n -> w.contains(n.take(2)) || (w.length >= 2 && n.contains(w)) } }?.let { return it }
        return when {
            listOf("첫", "1번", "하나", "일번").any { it in w } -> list.getOrNull(0)
            listOf("두", "2번", "둘", "이번").any { it in w } -> list.getOrNull(1)
            listOf("세", "3번", "셋", "삼번").any { it in w } -> list.getOrNull(2)
            else -> null
        }
    }
}
