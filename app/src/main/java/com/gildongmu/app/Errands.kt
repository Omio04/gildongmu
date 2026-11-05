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
data class MenuItem(val name: String, val price: Int, val kind: String, val simple: Boolean = true) {
    fun priceText() = "%,d원".format(price)
}

data class Shop(
    val id: String, val name: String, val brand: String, val place: String, val km: Double, val etaMin: Int, val waitMin: Int,
    val cookInMin: Int, val phone: String, val menu: List<MenuItem>, val lat: Double = 0.0, val lon: Double = 0.0,
)

/** What the server answered for the car's position: shops inside the range, and the nearest one of all. */
data class ShopsView(val rangeKm: Double, val shops: List<Shop>, val nearestName: String?, val nearestKm: Double, val nearestPlace: String?)

/** One offer: a shop and the single item that answers the driver's request. */
data class Pick(val shop: Shop, val item: MenuItem)

/** An order as the server knows it: where it is in its life (status), the live arrival time and how much later than first said. */
data class OrderReceipt(
    val receipt: String, val shop: String, val item: String, val price: Int, val etaMin: Int, val cookInMin: Int, val status: String,
    val statusLabel: String = "", val delayMin: Int = 0, val carCanCancel: Boolean = false, val payment: String = "", val phone: String = "",
    val shopLat: Double = 0.0, val shopLon: Double = 0.0,
) {
    val finished get() = status == "picked_up" || status == "cancelled"
}

/** A payment method kept in the car: only the token a payment provider returned, never a card number (MOCK registration). */
data class PayMethod(val id: String, val type: String, val last4: String, val token: String) {
    fun label() = when (type) {
        "easy" -> "간편결제 (모의)"
        "domestic" -> "국내 카드 ****$last4 (모의)"
        else -> "해외 카드 ****$last4 (모의)"
    }
}

/** One line of the order history (the receipt screen). */
data class OrderRow(
    val receipt: String, val shop: String, val item: String, val price: Int, val status: String, val statusLabel: String,
    val payment: String, val refunded: Boolean, val createdAt: Double, val live: Boolean,
)

/**
 * The shops this car holds right now, and only those. A shop's data arrives when the car comes within [ENTER_KM] of it and is
 * deleted when the car is farther than [KEEP_KM] (the gap stops a shop at the edge from flickering in and out). Kept in memory
 * only: nothing about shops is written to disk, and the cache is empty again when the app closes.
 */
class ShopCache(private val enterKm: Double = Errands.RANGE_KM, private val keepKm: Double = Errands.KEEP_KM) {
    private val byId = LinkedHashMap<String, Shop>()
    data class Change(val entered: List<Shop>, val left: List<Shop>)

    /** [view] holds what the server sent within [keepKm]. Returns who came into range and who was deleted. */
    fun update(view: ShopsView): Change {
        val seen = view.shops.associateBy { it.id }
        val entered = mutableListOf<Shop>(); val left = mutableListOf<Shop>()
        for ((id, old) in byId.toMap()) {
            val now = seen[id]
            if (now == null || now.km > keepKm) { byId.remove(id); left += old } else byId[id] = now   // refreshed (distance, arrival)
        }
        for (s in view.shops) if (s.id !in byId && s.km <= enterKm) { byId[s.id] = s; entered += s }
        return Change(entered, left)
    }

    fun clear() = byId.clear()
    fun size() = byId.size
    fun asView(from: ShopsView) = from.copy(rangeKm = enterKm, shops = byId.values.sortedBy { it.km })
}

object Errands {
    const val RANGE_KM = 5.0   // a shop's data comes in at this distance
    const val KEEP_KM = 6.0    // and is deleted beyond this one
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
                // the offer is a simple item (nothing to think about while driving); items with options are chosen by voice only
                val item = if (key in ITEM_KINDS) s.menu.firstOrNull { it.kind == key && it.simple } else if (s.place == key) s.menu.firstOrNull { it.simple } else null
                item?.let { Pick(s, it) }
            }.take(3)

    /** The kinds of item that at least one shop in range can give: only those are offered as buttons. */
    fun kindsIn(view: ShopsView, favourites: Set<String> = emptySet()): List<String> = ITEM_KINDS.filter { picks(view, it, favourites).isNotEmpty() }

    fun label(key: String) = LABEL[key] ?: key

    private val ASK = listOf("필요", "살만", "주문", "픽업", "먹", "마실", "마시", "싶어", "고파", "고픈", "뭐 있", "뭐있", "어디", "있어", "사고싶", "사줘", "줘")
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
            listOf("뭐필요", "필요한", "뭐있", "뭐가있", "픽업", "가게", "살만한", "사야").any { it in w } -> "ALL"   // asked in general: open the chooser
            else -> null
        }
    }

    private val SELL_WORDS = listOf("파는", "팔아", "팔고", "판매", "알려", "있어", "뭐있", "메뉴", "얼마")
    private val PLACE_WORDS = listOf("주유소" to "편의점·주유", "편의점" to "편의점·주유", "휴게소" to "휴게소", "졸음쉼터" to "휴게소", "드라이브스루" to "드라이브스루")

    /**
     * "근처 해뜰커피 알려줘", "근처 주유소에 파는 거 알려줘": the nearest shop the sentence names, by its name or brand (two letters
     * are enough) or by its kind of place. Needs a word that asks ("알려", "파는", "있어"...), so chatter that merely contains a
     * shop's name is not taken for a request. Questions about parking or jams are never shop requests.
     */
    fun shopFor(view: ShopsView, spoken: String): Shop? {
        val w = spoken.replace(" ", "")
        if (NOT_PICKUP.any { it in w } || SELL_WORDS.none { it in w }) return null
        val byName = view.shops.filter { s ->
            listOf(s.name, s.brand).any { n -> val k = n.replace(" ", ""); k.length >= 2 && (w.contains(k.take(2)) || w.contains(k)) }
        }.minByOrNull { it.km }
        if (byName != null) return byName
        val place = PLACE_WORDS.firstOrNull { it.first in w }?.second ?: return null
        return view.shops.filter { it.place == place }.minByOrNull { it.km }
    }

    /** What the shop sells, read aloud: "초록주유소 편의점에서는 생수, 샌드위치, 초코바를 팔아요". */
    fun sellingSpoken(s: Shop): String =
        "${s.name}에서는 " + s.menu.joinToString(", ") { it.name } + "를 팔아요. ${"%.1f".format(s.km)}킬로 거리예요. 얼마인지 물어보거나, 결제라고 말해 주세요"

    /** The answer to "무엇이 필요하세요?": one word is enough. */
    fun kindFromWord(spoken: String): String? {
        val w = spoken.replace(" ", "")
        return when {
            listOf("커피", "음료", "마실", "차").any { it in w } -> "음료"
            listOf("식사", "밥", "도시락", "먹을", "배고").any { it in w } -> "식사"
            listOf("간식", "빵", "과자", "출출").any { it in w } -> "간식"
            else -> null
        }
    }

    /** The sentence read to the driver for one offer (everything needed to say "결제" or "다른 곳"). */
    fun spoken(p: Pick): String =
        "가장 가까운 곳은 ${p.shop.place} ${p.shop.name}, ${"%.1f".format(p.shop.km)}킬로. ${p.item.name} ${p.item.priceText()}. " +
            "${p.shop.etaMin}분 뒤 도착이라 맞춰 준비해요. 결제할까요? 다른 메뉴를 원하면 메뉴라고 말해 주세요"

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
