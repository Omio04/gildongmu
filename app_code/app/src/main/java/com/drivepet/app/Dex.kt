package com.drivepet.app

import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The map collection ("지도 도감"): every 시·도 has five things to find (foods, local products, places). While the pet follows
 * the car on a navigation route, it now and then fetches one of the region the car is in. A region is complete when all five
 * are found; the whole map is complete with all 17 regions. Pure logic with no Android classes, so it can be tested.
 *
 * Probabilities and rewards are in [Dex]'s constants. The item names are common knowledge about each region, not a verified
 * official list: they are meant to be reviewed and redrawn by the designer (the emoji are only stand-ins for the art).
 */
enum class Rarity(val label: String, val weight: Int, val points: Int) {
    COMMON("흔함", 6, 5), UNCOMMON("귀함", 3, 10), RARE("아주 귀함", 1, 20)
}

enum class Kind2(val label: String) { FOOD("음식"), PRODUCT("특산물"), SIGHT("명소") }

data class DexItem(val id: String, val name: String, val regionId: String, val kind: Kind2, val rarity: Rarity, val emoji: String)

class Region(val id: String, val name: String, val items: List<DexItem>, val anchors: List<Pair<Double, Double>>)

/** where each region is, how it is drawn on the tile map (column, row) and what it holds */
object Korea {
    private fun d(r: String, id: String, name: String, kind: Kind2, rar: Rarity, emoji: String) = DexItem("$r.$id", name, r, kind, rar, emoji)
    private fun a(vararg v: Double): List<Pair<Double, Double>> = v.toList().chunked(2).map { it[0] to it[1] }
    private val C = Rarity.COMMON; private val U = Rarity.UNCOMMON; private val R = Rarity.RARE
    private val F = Kind2.FOOD; private val P = Kind2.PRODUCT; private val S = Kind2.SIGHT

    val regions: List<Region> = listOf(
        Region("seoul", "서울", listOf(
            d("seoul", "tteok", "떡볶이", F, C, "🌶️"), d("seoul", "chicken", "치킨", F, C, "🍗"),
            d("seoul", "hanbok", "한복", P, U, "👗"), d("seoul", "gyeongbok", "경복궁 기와", S, U, "🏯"), d("seoul", "namsan", "남산타워", S, R, "🗼")),
            a(37.564, 126.998, 37.497, 127.028, 37.551, 126.850, 37.654, 127.056, 37.478, 126.951, 37.514, 127.105, 37.603, 126.929,
              37.557, 126.924, 37.589, 127.017, 37.530, 127.124, 37.495, 126.887, 37.669, 127.047, 37.526, 126.896, 37.484, 127.032)),
        Region("busan", "부산", listOf(
            d("busan", "gukbap", "돼지국밥", F, C, "🍲"), d("busan", "eomuk", "어묵", F, C, "🍥"), d("busan", "hotteok", "씨앗호떡", F, C, "🥞"),
            d("busan", "seagull", "갈매기", S, U, "🕊️"), d("busan", "gwangan", "광안대교", S, R, "🌉")),
            a(35.180, 129.075, 35.163, 129.164, 35.245, 129.222, 35.097, 129.024, 35.160, 128.930, 35.105, 128.975, 35.243, 129.092,
              35.090, 129.070, 35.200, 129.000, 35.145, 129.113, 35.030, 128.820)),
        Region("daegu", "대구", listOf(
            d("daegu", "makchang", "막창", F, C, "🥩"), d("daegu", "napjak", "납작만두", F, C, "🥟"), d("daegu", "apple", "대구 사과", P, C, "🍎"),
            d("daegu", "herb", "약령시 한약재", P, U, "🌿"), d("daegu", "gatbawi", "팔공산 갓바위", S, R, "⛰️")),
            a(35.871, 128.601, 35.858, 128.630, 35.830, 128.533, 35.886, 128.583, 35.886, 128.636, 35.775, 128.430, 36.240, 128.570)),
        Region("incheon", "인천", listOf(
            d("incheon", "jjajang", "짜장면", F, C, "🍜"), d("incheon", "jjolmyeon", "쫄면", F, C, "🥢"), d("incheon", "ginseng", "강화 인삼", P, C, "🌱"),
            d("incheon", "plane", "인천공항 비행기", S, U, "✈️"), d("incheon", "lantern", "차이나타운 홍등", S, R, "🏮")),
            a(37.456, 126.705, 37.389, 126.650, 37.747, 126.488, 37.966, 124.714, 37.490, 126.520, 37.537, 126.737, 37.593, 126.665, 37.410, 126.678)),
        Region("gwangju", "광주", listOf(
            d("gwangju", "galbi", "떡갈비", F, C, "🍖"), d("gwangju", "duck", "오리탕", F, C, "🦆"), d("gwangju", "melon", "무등산 수박", P, C, "🍉"),
            d("gwangju", "kimchi", "광주 김치", P, U, "🥬"), d("gwangju", "mudeung", "무등산 주상절리대", S, R, "🪨")),
            a(35.160, 126.851, 35.140, 126.790, 35.174, 126.912, 35.133, 126.902, 35.146, 126.923)),
        Region("daejeon", "대전", listOf(
            d("daejeon", "soboro", "튀김소보로", F, C, "🥐"), d("daejeon", "kalguksu", "칼국수", F, C, "🍜"), d("daejeon", "duruchigi", "두부두루치기", F, C, "🍳"),
            d("daejeon", "hanbit", "엑스포 한빛탑", S, U, "🛰️"), d("daejeon", "yuseong", "유성 온천", S, R, "♨️")),
            a(36.351, 127.385, 36.362, 127.356, 36.350, 127.416, 36.312, 127.455, 36.320, 127.340)),
        Region("ulsan", "울산", listOf(
            d("ulsan", "bulgogi", "언양 불고기", F, C, "🥩"), d("ulsan", "pear", "울산 배", P, C, "🍐"), d("ulsan", "whale", "장생포 고래", S, U, "🐋"),
            d("ulsan", "daewangam", "대왕암 공원", S, U, "🪨"), d("ulsan", "ganjeolgot", "간절곶 일출", S, R, "🌅")),
            a(35.540, 129.311, 35.567, 129.120, 35.520, 129.100, 35.582, 129.361, 35.505, 129.417, 35.430, 129.330)),
        Region("sejong", "세종", listOf(
            d("sejong", "peach", "조치원 복숭아", P, C, "🍑"), d("sejong", "cherry", "조치원 벚꽃길", S, C, "🌸"), d("sejong", "brush", "한글 붓", P, U, "🖌️"),
            d("sejong", "arboretum", "세종 수목원 온실", S, U, "🌲"), d("sejong", "lake", "세종호수공원", S, R, "🏞️")),
            a(36.504, 127.265, 36.601, 127.297, 36.550, 127.280, 36.470, 127.270)),
        Region("gyeonggi", "경기", listOf(
            d("gyeonggi", "galbi", "수원 왕갈비", F, C, "🍖"), d("gyeonggi", "rice", "이천 쌀", P, C, "🍚"), d("gyeonggi", "bean", "파주 장단콩", P, C, "🫘"),
            d("gyeonggi", "pinenut", "가평 잣", P, U, "🌰"), d("gyeonggi", "hwaseong", "수원화성", S, R, "🏰")),
            a(37.263, 127.029, 37.420, 127.127, 37.241, 127.178, 37.658, 126.832, 37.760, 126.780, 37.738, 127.034, 37.785, 127.046, 37.895, 127.200,
              38.096, 127.075, 37.831, 127.510, 37.490, 127.490, 37.636, 127.216, 37.594, 127.129, 37.540, 127.215, 37.430, 127.255, 37.272, 127.435,
              37.298, 127.637, 37.008, 127.280, 36.992, 127.113, 37.199, 126.831, 37.322, 126.831, 37.380, 126.803, 37.504, 126.766, 37.615, 126.716,
              37.478, 126.865, 37.394, 126.957, 37.429, 126.988, 37.360, 126.935, 37.345, 126.968, 37.150, 127.077, 37.904, 127.060, 37.200, 127.100)),
        Region("gangwon", "강원", listOf(
            d("gangwon", "sundubu", "초당순두부", F, C, "🍲"), d("gangwon", "squid", "속초 오징어", F, C, "🦑"), d("gangwon", "potato", "평창 감자", P, C, "🥔"),
            d("gangwon", "hanwoo", "횡성 한우", P, U, "🐂"), d("gangwon", "seorak", "설악산 단풍", S, R, "🍁")),
            a(37.881, 127.730, 37.342, 127.920, 37.752, 128.876, 38.207, 128.591, 37.524, 129.114, 37.450, 129.165, 37.164, 128.986, 37.370, 128.390,
              37.380, 128.660, 37.490, 127.985, 37.697, 127.888, 38.075, 128.619, 38.146, 127.313, 38.106, 127.708, 38.070, 128.170, 38.380, 128.468,
              37.180, 128.460, 38.110, 127.990, 37.680, 128.720)),
        Region("chungbuk", "충북", listOf(
            d("chungbuk", "grape", "영동 포도", P, C, "🍇"), d("chungbuk", "apple", "충주 사과", P, C, "🍎"), d("chungbuk", "olgaengi", "괴산 올갱이국", F, C, "🥣"),
            d("chungbuk", "jikji", "직지 금속활자", S, U, "🔤"), d("chungbuk", "dodamsambong", "단양 도담삼봉", S, R, "⛰️")),
            a(36.642, 127.489, 36.991, 127.926, 37.133, 128.190, 36.985, 128.366, 36.815, 127.787, 36.940, 127.690, 36.855, 127.435, 36.175, 127.783,
              36.306, 127.571, 36.489, 127.729, 36.785, 127.581)),
        Region("chungnam", "충남", listOf(
            d("chungnam", "strawberry", "논산 딸기", P, C, "🍓"), d("chungnam", "walnutcake", "천안 호두과자", F, C, "🌰"), d("chungnam", "crab", "태안 꽃게", F, C, "🦀"),
            d("chungnam", "ginseng", "금산 인삼", P, U, "🌱"), d("chungnam", "mud", "보령 머드", S, R, "🫧")),
            a(36.815, 127.114, 36.790, 127.000, 36.446, 127.119, 36.187, 127.099, 36.785, 126.450, 36.890, 126.630, 36.745, 126.298, 36.333, 126.613,
              36.601, 126.660, 36.680, 126.850, 36.276, 126.910, 36.080, 126.690, 36.108, 127.488, 36.274, 127.249, 36.459, 126.800)),
        Region("jeonbuk", "전북", listOf(
            d("jeonbuk", "bibimbap", "전주 비빔밥", F, C, "🥗"), d("jeonbuk", "cheese", "임실 치즈", P, C, "🧀"), d("jeonbuk", "bokbunja", "고창 복분자", P, C, "🫐"),
            d("jeonbuk", "gochujang", "순창 고추장", P, U, "🌶️"), d("jeonbuk", "hanok", "전주 한옥마을", S, R, "🏘️")),
            a(35.824, 127.148, 35.968, 126.737, 35.948, 126.958, 35.570, 126.856, 35.416, 127.390, 35.803, 126.880, 35.904, 127.162, 35.790, 127.425,
              36.007, 127.660, 35.647, 127.520, 35.617, 127.289, 35.374, 127.137, 35.435, 126.702, 35.732, 126.733)),
        Region("jeonnam", "전남", listOf(
            d("jeonnam", "kkomak", "벌교 꼬막", F, C, "🐚"), d("jeonnam", "abalone", "완도 전복", F, C, "🦪"), d("jeonnam", "tea", "보성 녹차", P, C, "🍵"),
            d("jeonnam", "bamboo", "담양 대나무", S, U, "🎍"), d("jeonnam", "yeosu", "여수 밤바다", S, R, "🌃")),
            a(34.812, 126.392, 34.760, 127.662, 34.950, 127.487, 35.016, 126.711, 34.940, 127.696, 35.320, 126.988, 35.282, 127.292, 35.202, 127.463,
              34.611, 127.285, 34.771, 127.080, 35.065, 126.986, 34.681, 126.907, 34.642, 126.767, 34.573, 126.599, 34.800, 126.697, 34.990, 126.481,
              35.066, 126.517, 35.277, 126.512, 35.302, 126.785, 34.311, 126.755, 34.487, 126.263, 34.850, 126.310, 34.680, 125.435, 34.030, 127.310)),
        Region("gyeongbuk", "경북", listOf(
            d("gyeongbuk", "snowcrab", "영덕 대게", F, C, "🦀"), d("gyeongbuk", "jjimdak", "안동 찜닭", F, C, "🍗"), d("gyeongbuk", "garlic", "의성 마늘", P, C, "🧄"),
            d("gyeongbuk", "persimmon", "상주 곶감", P, U, "🟠"), d("gyeongbuk", "cheomseongdae", "경주 첨성대", S, R, "🔭")),
            a(36.568, 128.729, 35.856, 129.225, 36.019, 129.343, 36.119, 128.344, 36.140, 128.114, 36.806, 128.624, 36.411, 128.159, 36.586, 128.187,
              36.657, 128.452, 36.353, 128.697, 36.436, 129.057, 36.415, 129.365, 36.993, 129.400, 36.893, 128.732, 36.667, 129.112, 35.647, 128.740,
              35.825, 128.741, 35.973, 128.939, 35.919, 128.283, 35.726, 128.263, 35.995, 128.402, 37.484, 130.906, 37.242, 131.865, 36.680, 129.450)),
        Region("gyeongnam", "경남", listOf(
            d("gyeongnam", "oyster", "통영 굴", F, C, "🦪"), d("gyeongnam", "yuja", "남해 유자", P, C, "🍋"), d("gyeongnam", "agujjim", "마산 아구찜", F, C, "🐟"),
            d("gyeongnam", "tripitaka", "합천 팔만대장경", S, U, "📜"), d("gyeongnam", "darangi", "남해 다랭이마을", S, R, "🌾")),
            a(35.228, 128.681, 35.180, 128.108, 35.228, 128.889, 35.335, 129.037, 35.504, 128.746, 34.880, 128.621, 34.854, 128.433, 35.003, 128.064,
              34.837, 127.892, 35.067, 127.751, 35.415, 127.873, 35.520, 127.725, 35.687, 127.909, 35.567, 128.166, 35.322, 128.262, 35.272, 128.406,
              35.545, 128.492, 34.973, 128.322)),
        Region("jeju", "제주", listOf(
            d("jeju", "blackpork", "흑돼지", F, C, "🐖"), d("jeju", "hallabong", "한라봉", P, C, "🍊"), d("jeju", "galchi", "갈치조림", F, C, "🐟"),
            d("jeju", "hareubang", "돌하르방", S, U, "🗿"), d("jeju", "baengnokdam", "한라산 백록담", S, R, "🏔️")),
            a(33.500, 126.530, 33.253, 126.560, 33.460, 126.930, 33.410, 126.270, 33.110, 126.270, 33.500, 126.950, 33.950, 126.300)),
    )

    val items: List<DexItem> = regions.flatMap { it.items }
    private val byId: Map<String, Region> = regions.associateBy { it.id }
    private val itemById: Map<String, DexItem> = items.associateBy { it.id }

    fun region(id: String): Region? = byId[id]
    fun item(id: String): DexItem? = itemById[id]

    /** the position of each region's tile on the tile map: (column 0..4, row 0..5), a rough picture of the peninsula */
    val tile: Map<String, Pair<Int, Int>> = mapOf(
        "seoul" to (1 to 0),
        "incheon" to (0 to 1), "gyeonggi" to (1 to 1), "gangwon" to (2 to 1),
        "chungnam" to (0 to 2), "sejong" to (1 to 2), "chungbuk" to (2 to 2), "gyeongbuk" to (3 to 2),
        "jeonbuk" to (0 to 3), "daejeon" to (1 to 3), "daegu" to (3 to 3), "ulsan" to (4 to 3),
        "gwangju" to (0 to 4), "jeonnam" to (1 to 4), "gyeongnam" to (2 to 4), "busan" to (3 to 4),
        "jeju" to (0 to 5),
    )

    // ---- which region a GPS position is in: the nearest anchor point (the main towns), within MAX_KM of one
    private val anchorLat: DoubleArray
    private val anchorLon: DoubleArray
    private val anchorRegion: IntArray
    init {
        val la = ArrayList<Double>(); val lo = ArrayList<Double>(); val re = ArrayList<Int>()
        regions.forEachIndexed { i, r -> r.anchors.forEach { (y, x) -> la += y; lo += x; re += i } }
        anchorLat = la.toDoubleArray(); anchorLon = lo.toDoubleArray(); anchorRegion = re.toIntArray()
    }

    /** a position outside every outline (a port, a bridge, a coast road, an outline that is a little rough) is given to the nearest main town within this distance */
    const val MAX_KM = 25.0

    /** the region of a position: inside its outline, or else next to it; null outside Korea or far out at sea */
    fun locate(lat: Double, lon: Double): Region? {
        if (lat.isNaN() || lon.isNaN() || lat < 32.0 || lat > 39.0 || lon < 124.0 || lon > 132.0) return null
        KoreaMap.regionAt(lat, lon)?.let { id -> byId[id]?.let { return it } }
        var best = -1; var bestD = Double.MAX_VALUE
        for (i in anchorLat.indices) {
            val d = distKm(lat, lon, anchorLat[i], anchorLon[i])
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0 && bestD <= MAX_KM) regions[anchorRegion[best]] else null
    }

    fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
        val dp = p2 - p1; val dl = (lon2 - lon1) * PI / 180
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}

/** one thing the pet brought back */
data class Find(val item: DexItem, val isNew: Boolean, val points: Int, val regionDone: Boolean, val allDone: Boolean)

/** what is saved between runs */
data class DexState(
    val owned: Set<String> = emptySet(),
    val visited: Set<String> = emptySet(),
    val guaranteed: Set<String> = emptySet(),
    val sinceFind: Float = 0f,
    val dupStreak: Int = 0,
    val carryKm: Float = 0f,
    val findsToday: Int = 0,
    val day: Int = 0,
)

/**
 * The collection and the rules of finding. Every whole kilometre driven in a region (only counted while the caller says
 * the pet is out following the route) is one roll:
 *  - [P_PER_KM] chance to find something, about one find per 12 km;
 *  - the first time a region is entered, the first kilometre always finds something (a welcome gift, and the map starts to fill);
 *  - after [DROUGHT_KM] km without a find, the next kilometre finds one;
 *  - what is found is drawn by rarity weight; after [PITY_DUPS] repeats in a row the next one is always a new item of that region;
 *  - repeats give [DUP_POINTS] points, new items the points of their rarity, completing a region [REGION_BONUS], the whole map [MAP_BONUS];
 *  - at most [DAILY_CAP] finds a day.
 */
class Dex(state: DexState = DexState(), private val rnd: Random = Random()) {
    private val _owned = state.owned.filter { Korea.item(it) != null }.toMutableSet()
    private val _visited = state.visited.filter { Korea.region(it) != null }.toMutableSet()
    private val _guaranteed = state.guaranteed.filter { Korea.region(it) != null }.toMutableSet()
    private var sinceFind = state.sinceFind.coerceAtLeast(0f)
    private var dupStreak = state.dupStreak.coerceAtLeast(0)
    private var carry = state.carryKm.coerceIn(0f, 0.999f)
    private var findsToday = state.findsToday.coerceAtLeast(0)
    private var day = state.day

    val owned: Set<String> get() = _owned
    val visited: Set<String> get() = _visited
    val total get() = Korea.items.size

    fun has(id: String) = id in _owned
    fun count(regionId: String) = Korea.region(regionId)?.items?.count { it.id in _owned } ?: 0
    fun regionDone(regionId: String) = Korea.region(regionId)?.let { r -> r.items.all { it.id in _owned } } ?: false
    fun regionsDone() = Korea.regions.count { regionDone(it.id) }
    val allDone get() = Korea.regions.all { regionDone(it.id) }

    /** call whenever the region of the car is known: the first time a region is seen, its first kilometre will find something */
    fun enter(regionId: String?) {
        if (regionId == null || Korea.region(regionId) == null || regionId in _visited) return
        _visited += regionId
        _guaranteed += regionId
    }

    /**
     * [km] driven in [regionId] while the pet is out following the route. Returns what was found (usually nothing).
     * Big jumps are not trusted: at most [MAX_STEP_KM] is counted per call.
     */
    fun onDrive(regionId: String?, km: Float, today: Int): List<Find> {
        if (today != day) { day = today; findsToday = 0 }
        val region = regionId?.let { Korea.region(it) } ?: return emptyList()
        if (km <= 0f || km.isNaN()) return emptyList()
        enter(region.id)
        carry += km.coerceAtMost(MAX_STEP_KM)
        val out = ArrayList<Find>()
        while (carry >= 1f) {
            carry -= 1f
            if (findsToday >= DAILY_CAP) { carry = 0f; break }
            sinceFind += 1f
            val forced = region.id in _guaranteed || sinceFind >= DROUGHT_KM
            if (!forced && rnd.nextFloat() >= P_PER_KM) continue
            out += pickFrom(region, forced = region.id in _guaranteed)
            _guaranteed -= region.id
        }
        return out
    }

    /** the same as a find but right now (the debug button) */
    fun forceFind(regionId: String, today: Int): Find? {
        val region = Korea.region(regionId) ?: return null
        if (today != day) { day = today; findsToday = 0 }
        enter(regionId)
        _guaranteed -= regionId
        return pickFrom(region, forced = false)
    }

    private fun pickFrom(region: Region, forced: Boolean): Find {
        val unowned = region.items.filter { it.id !in _owned }
        val wantNew = forced || dupStreak >= PITY_DUPS
        val pool = if (wantNew && unowned.isNotEmpty()) unowned else region.items
        val item = weighted(pool)
        val isNew = item.id !in _owned
        _owned += item.id
        sinceFind = 0f
        findsToday++
        dupStreak = if (isNew) 0 else dupStreak + 1
        val regionDone = isNew && regionDone(region.id)
        val allDone = isNew && regionDone && this.allDone
        var pts = if (isNew) item.rarity.points else DUP_POINTS
        if (regionDone) pts += REGION_BONUS
        if (allDone) pts += MAP_BONUS
        return Find(item, isNew, pts, regionDone, allDone)
    }

    private fun weighted(pool: List<DexItem>): DexItem {
        var x = rnd.nextInt(pool.sumOf { it.rarity.weight })
        for (i in pool) { x -= i.rarity.weight; if (x < 0) return i }
        return pool.last()
    }

    /** demo only: own this item without finding it */
    fun grant(id: String) { if (Korea.item(id) != null) _owned += id }
    fun grantAll() { Korea.items.forEach { _owned += it.id }; Korea.regions.forEach { _visited += it.id } }
    fun grantRegion(regionId: String) { Korea.region(regionId)?.items?.forEach { _owned += it.id }; if (Korea.region(regionId) != null) _visited += regionId }
    fun clear() { _owned.clear(); _visited.clear(); _guaranteed.clear(); sinceFind = 0f; dupStreak = 0; carry = 0f; findsToday = 0 }

    fun state() = DexState(_owned.toSet(), _visited.toSet(), _guaranteed.toSet(), sinceFind, dupStreak, carry, findsToday, day)

    companion object {
        const val P_PER_KM = 0.08f
        const val DROUGHT_KM = 25f
        const val PITY_DUPS = 4
        const val DAILY_CAP = 15
        const val DUP_POINTS = 2
        const val REGION_BONUS = 30
        const val MAP_BONUS = 200
        const val MAX_STEP_KM = 3f

        fun encode(s: DexState): String =
            listOf(s.owned.joinToString(","), s.visited.joinToString(","), s.guaranteed.joinToString(","),
                s.sinceFind.toString(), s.dupStreak.toString(), s.carryKm.toString(), s.findsToday.toString(), s.day.toString()).joinToString("|")

        fun decode(text: String?): DexState {
            if (text.isNullOrBlank()) return DexState()
            val p = text.split("|")
            fun set(i: Int) = p.getOrNull(i).orEmpty().split(",").filter { it.isNotBlank() }.toSet()
            return DexState(set(0), set(1), set(2), p.getOrNull(3)?.toFloatOrNull() ?: 0f, p.getOrNull(4)?.toIntOrNull() ?: 0,
                p.getOrNull(5)?.toFloatOrNull() ?: 0f, p.getOrNull(6)?.toIntOrNull() ?: 0, p.getOrNull(7)?.toIntOrNull() ?: 0)
        }
    }
}
