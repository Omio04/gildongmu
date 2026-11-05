package com.gildongmu.app

/**
 * "가는 길 픽업": the partner shops near the car, as the server sends them. Each shop registered itself with a
 * structure (name, brand, kind of place, position, phone, preparation time, menu), so the app only reads and offers.
 *
 * The driver asks for what they want, not for a kind of place: every menu item is a drink (음료), a meal (식사)
 * or a snack (간식), and the same three fit a drive-through, a rest-stop restaurant and a convenience store.
 * Once the driver has marked brands as favourites, only those are offered (until they ask to see all again).
 * SAMPLE data and a mock payment: the shops are fictional and nothing is charged.
 */
data class MenuItem(val name: String, val price: Int, val kind: String) {
    fun priceText() = "%,d원".format(price)
}

data class Shop(
    val id: String, val name: String, val brand: String, val place: String, val km: Double, val etaMin: Int, val waitMin: Int,
    val cookInMin: Int, val phone: String, val menu: List<MenuItem>,
)

/** What the server answered for the car's position: shops inside the range, and the nearest one of all. */
data class ShopsView(val rangeKm: Double, val shops: List<Shop>, val nearestName: String?, val nearestKm: Double, val nearestPlace: String?)

/** One offer: a shop and the single item that answers the driver's request. */
data class Pick(val shop: Shop, val item: MenuItem)

/** An order as the server knows it: where it is in its life (status), the live arrival time and how much later than first said. */
data class OrderReceipt(
    val receipt: String, val shop: String, val item: String, val price: Int, val etaMin: Int, val cookInMin: Int, val status: String,
    val statusLabel: String = "", val delayMin: Int = 0, val carCanCancel: Boolean = false, val payment: String = "", val phone: String = "",
) {
    val finished get() = status == "picked_up" || status == "cancelled"
}

object Errands {
    val ITEM_KINDS = listOf("음료", "식사", "간식")
    val LABEL = mapOf("음료" to "커피·음료", "식사" to "식사", "간식" to "간식")

    /**
     * The nearest shops (at most 3) that answer [key], each with ONE item: [key] is a kind of item (음료, 식사, 간식),
     * or, when the driver names a kind of place, that place's first menu item. With [favourites] only those brands count.
     */
    fun picks(view: ShopsView, key: String, favourites: Set<String> = emptySet()): List<Pick> =
        view.shops.sortedBy { it.km }
            .filter { favourites.isEmpty() || it.brand in favourites }
            .mapNotNull { s ->
                val item = if (key in ITEM_KINDS) s.menu.firstOrNull { it.kind == key } else if (s.place == key) s.menu.firstOrNull() else null
                item?.let { Pick(s, it) }
            }.take(3)

    /** The kinds of item that at least one shop in range can give: only those are offered as buttons. */
    fun kindsIn(view: ShopsView, favourites: Set<String> = emptySet()): List<String> = ITEM_KINDS.filter { picks(view, it, favourites).isNotEmpty() }

    fun label(key: String) = LABEL[key] ?: key

    private val ASK = listOf("주문", "픽업", "먹", "마실", "마시", "싶어", "고파", "고픈", "뭐 있", "뭐있", "어디", "있어", "사고싶", "사줘", "줘")
    private val NOT_PICKUP = listOf("주차", "막혀", "막힘", "혼잡", "사고", "정체", "낙하", "공사")

    /**
     * What a spoken sentence asks for (an item kind, or a kind of place), or null. A question about parking, jams or
     * accidents is never taken for an order: those go to the neighbours as usual.
     */
    fun matchType(spoken: String): String? {
        val w = spoken.replace(" ", "")
        if (NOT_PICKUP.any { it in w }) return null
        if (ASK.none { it.replace(" ", "") in w }) return null
        return when {
            listOf("커피", "음료", "마실", "차가운", "시원한").any { it in w } -> "음료"
            listOf("식사", "밥", "도시락", "점심", "저녁", "배고", "뭐먹", "먹을").any { it in w } -> "식사"
            listOf("간식", "빵", "과자", "출출").any { it in w } -> "간식"
            "드라이브스루" in w || "디티" in w || "dt" in w.lowercase() -> "드라이브스루"
            listOf("휴게소", "졸음쉼터").any { it in w } -> "휴게소"
            listOf("주유", "충전", "편의점").any { it in w } -> "편의점·주유"
            listOf("뭐있", "픽업").any { it in w } -> "식사"
            else -> null
        }
    }

    /** The sentence read to the driver for one offer (everything needed to say "결제" or "다른 곳"). */
    fun spoken(p: Pick): String =
        "가장 가까운 곳은 ${p.shop.place} ${p.shop.name}, ${"%.1f".format(p.shop.km)}킬로. ${p.item.name} ${p.item.priceText()}. " +
            "${p.shop.etaMin}분 뒤 도착이라 맞춰 준비해요. 결제, 메뉴, 다른 곳, 단골, 취소 중에 말해 주세요"

    /** A menu item the driver named ("아이스 라떼로"): matched by its first letters, or the whole name. */
    fun itemNamed(shop: Shop, spoken: String): MenuItem? {
        val w = spoken.replace(" ", "")
        return shop.menu.firstOrNull { m -> m.name.replace(" ", "").let { n -> w.contains(n.take(3)) || (w.length >= 2 && n.contains(w)) } }
    }

    /** What is said about an order in progress. */
    fun statusSpoken(o: OrderReceipt): String =
        "${o.shop}에 주문한 ${o.item}은 ${o.statusLabel}." + if (!o.finished) " 도착은 ${o.etaMin}분 뒤예요." + (if (o.delayMin > 0) " 막혀서 처음보다 ${o.delayMin}분 늦어졌어요." else "") else ""

    fun menuSpoken(s: Shop): String = "${s.name} 메뉴는 " + s.menu.joinToString(", ") { "${it.name} ${it.priceText()}" } + ". 결제할까요?"
}
