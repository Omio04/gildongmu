package com.drivepet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the items stand on a region's island picture, read from assets/regions/<id>/spots.json (made with
 * design/regions/tools/make_islands.py): the spots as fractions of the picture (furthest first), the bubble's radius
 * (a fraction of the width), how far below a bubble its tile lies (a fraction of the height), and for Seoul the spot of the
 * first item, which is painted into the picture.
 */
class RegionArt(val spots: List<Pair<Float, Float>>, val bubbleR: Float, val tileDy: Float, val first: Pair<Float, Float>?) {
    companion object {
        fun load(context: Context, id: String): RegionArt? = try {
            val o = org.json.JSONObject(context.assets.open("regions/$id/spots.json").bufferedReader().use { it.readText() })
            val a = o.getJSONArray("spots")
            val f = o.optJSONArray("first")
            RegionArt((0 until a.length()).map { val p = a.getJSONArray(it); p.getDouble(0).toFloat() to p.getDouble(1).toFloat() },
                o.getDouble("bubbleR").toFloat(), o.getDouble("tileDy").toFloat(), f?.let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() })
        } catch (e: Exception) { null }
    }
}

/** what the land of each region looks like: a city has buildings, the others have hills, palms or a sea horizon */
enum class Land { CITY, MOUNTAIN, COAST, ISLAND, PLAIN }
fun landOf(id: String): Land = when (id) {
    "seoul", "busan", "incheon", "daegu", "daejeon", "gwangju", "ulsan", "sejong" -> Land.CITY
    "gangwon", "gyeongbuk", "chungbuk", "jeonbuk" -> Land.MOUNTAIN
    "gyeongnam", "chungnam", "jeonnam" -> Land.COAST
    "jeju" -> Land.ISLAND
    else -> Land.PLAIN
}

/** the strip at the bottom of the region screen: not always a city (hills, or the sea with boats) */
class HorizonDrawable(private val d: Float, private val night: Boolean, private val land: Land) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
        val sea = land == Land.ISLAND || land == Land.COAST
        fun ridge(base: Float, amp: Float, step: Float, col: Int, ph: Float) {
            p.color = col; val path = Path(); path.moveTo(0f, h)
            var x = 0f
            while (x <= w + step) { path.lineTo(x, h - base - amp * (0.5f + 0.5f * sin(x / step * 1.7f + ph))); x += step / 4f }
            path.lineTo(w, h); path.close(); c.drawPath(path, p)
        }
        if (sea) {
            p.color = if (night) 0x552A4AB0 else 0x6694C8F4; c.drawRect(0f, h - 70f * d, w, h, p)
            p.color = if (night) 0x33FFFFFF else 0x55FFFFFF
            var x = 20f * d; while (x < w) { c.drawRect(x, h - 44f * d, x + 40f * d, h - 42f * d, p); x += 130f * d }
            p.color = if (night) 0xFFF6D98C.toInt() else 0xFFFFFFFF.toInt()   // a few little boats
            var bx = w * 0.12f; while (bx < w) { val by = h - 56f * d; c.drawRect(bx, by, bx + 26f * d, by + 6f * d, p); c.drawRect(bx + 11f * d, by - 14f * d, bx + 13f * d, by, p); bx += w * 0.27f }
            ridge(60f * d, 22f * d, 90f * d, if (night) 0x552B3C96 else 0x5594B4DC, 1f)
        } else {
            ridge(10f * d, 70f * d, 140f * d, if (night) 0x552B3C96 else 0x669DB8E0.toInt(), 0.4f)
            ridge(0f, 44f * d, 90f * d, if (night) 0x66364A9E else 0x77B7CCEA.toInt(), 2f)
        }
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: android.graphics.ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** the colour of each region and the area it belongs to (the same colours as the map) */
object RegionLook {
    val hue: Map<String, Int> = mapOf(
        "seoul" to "#9B6CFF", "incheon" to "#9B6CFF", "gyeonggi" to "#9B6CFF", "gangwon" to "#F5A04A",
        "chungbuk" to "#E8589E", "sejong" to "#E8589E", "daejeon" to "#E8589E", "chungnam" to "#5B8FF9",
        "jeonbuk" to "#E2C84A", "gwangju" to "#35C48A", "jeonnam" to "#35C48A", "gyeongbuk" to "#4E8BEA",
        "daegu" to "#4E8BEA", "gyeongnam" to "#E266C0", "busan" to "#E266C0", "ulsan" to "#E266C0", "jeju" to "#86DA5A",
    ).mapValues { Color.parseColor(it.value) }
    val area: Map<String, String> = mapOf(
        "seoul" to "수도권", "incheon" to "수도권", "gyeonggi" to "수도권", "gangwon" to "강원권",
        "chungbuk" to "충청권", "sejong" to "충청권", "daejeon" to "충청권", "chungnam" to "충청권",
        "jeonbuk" to "호남권", "gwangju" to "호남권", "jeonnam" to "호남권",
        "gyeongbuk" to "영남권", "daegu" to "영남권", "gyeongnam" to "영남권", "busan" to "영남권", "ulsan" to "영남권", "jeju" to "제주권",
    )

    /** the island picture shipped for a region (assets/regions/<id>/island.png), or null when the island is drawn in code */
    fun art(context: Context, id: String): android.graphics.Bitmap? =
        try { context.assets.open("regions/$id/island.png").use { android.graphics.BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
    fun hasArt(context: Context, id: String): Boolean = try { context.assets.list("regions/$id")?.let { "island.png" in it && "spots.json" in it } == true } catch (e: Exception) { false }

    /** a line about each region (the map panel and the region screen) */
    val blurb: Map<String, String> = mapOf(
        "seoul" to "대한민국의 수도이자 중심 도시, 서울! 한강과 높은 빌딩이 어우러진 이곳은 수많은 이야기가 시작되는 곳이에요.",
        "incheon" to "서해로 열린 관문 도시, 인천! 공항과 항구, 차이나타운이 한곳에 모여 있어요.",
        "gyeonggi" to "서울을 둘러싼 가장 큰 생활권, 경기! 산과 강, 과수원과 신도시가 함께 있어요.",
        "gangwon" to "높은 산과 푸른 동해가 만나는 강원! 설악산부터 바닷가까지 달리는 길마다 풍경이 달라요.",
        "chungbuk" to "바다 없이 산과 호수로 둘러싸인 충북! 한반도의 한가운데에 자리한 고요한 고장이에요.",
        "sejong" to "행정의 중심으로 새로 만들어진 도시, 세종! 넓은 공원과 호수가 반듯하게 이어져요.",
        "daejeon" to "과학과 교통의 중심, 대전! 기차와 도로가 모이는 한가운데 도시예요.",
        "chungnam" to "서해안과 들판이 이어지는 충남! 갯벌과 온천, 오래된 성곽이 있어요.",
        "jeonbuk" to "맛과 멋의 고장, 전북! 한옥마을과 넓은 평야가 함께하는 곳이에요.",
        "gwangju" to "빛고을 광주! 예술과 맛이 가득한 남도의 중심 도시예요.",
        "jeonnam" to "남쪽 바다와 섬을 품은 전남! 푸른 차밭과 갯벌, 다도해가 펼쳐져요.",
        "gyeongbuk" to "천 년의 역사가 숨 쉬는 경북! 옛 왕국의 도읍과 동해안 바다가 있어요.",
        "daegu" to "분지에 자리 잡은 도시, 대구! 뜨거운 여름과 맛있는 음식으로 유명해요.",
        "gyeongnam" to "남해와 낙동강을 낀 경남! 섬과 포구, 공업 도시가 어우러져요.",
        "busan" to "바다와 항구의 도시, 부산! 큰 다리와 해변, 시장이 가득해요.",
        "ulsan" to "산업과 바다가 만나는 울산! 큰 배와 고래, 해돋이로 알려진 곳이에요.",
        "jeju" to "바다 위의 화산섬, 제주! 한라산과 돌담, 푸른 바다가 어우러져요.",
    )

    /** one line about an item (a hint while it is still unknown) */
    fun line(r: Region, item: DexItem, have: Boolean): String = if (have) when (item.kind) {
        Kind2.FOOD -> "${r.name}에서 만난 ${item.name}! 길 위에서 처음 맛본 이 고장의 맛이에요."
        Kind2.PRODUCT -> "${r.name}이 자랑하는 ${item.name}. 이 고장의 정성이 담긴 특산물이에요."
        Kind2.SIGHT -> "${r.name}의 명소 ${item.name}. 달리다 마주친 풍경을 도감에 담았어요."
    } else "아직 만나지 못했어요. 내비 길안내로 ${r.name}을(를) 달리면 펫이 주워 와요." + if (item.rarity == Rarity.RARE) " (아주 귀한 ${item.kind.label}이에요)" else ""
}

/**
 * One region as a small 2.5D island (an isometric slab with trees and buildings on it). What the driver has found stands on it
 * on its own pedestal, with a name tag; what is still missing is a locked pedestal. The pet stands there too.
 */
class RegionSceneView(context: Context, private val region: Region, private val night: Boolean, private val onPick: (String) -> Unit) : View(context) {
    var selectedId: String? = null
        set(v) { field = v; invalidate() }
    var zoom = 1f
        set(v) { field = v.coerceIn(0.7f, 1.7f); invalidate() }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Look.display(context) }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val painter = PetPainter()
    private val hue = RegionLook.hue[region.id] ?: Color.GRAY
    private val land = landOf(region.id)
    /** an island picture, if the app ships one: app/src/main/assets/regions/<id>/island.png (the drawn island is the fallback) */
    private val spots: RegionArt? = RegionArt.load(context, region.id)
    /** the picture is used only together with its spots (without them the drawn island is shown) */
    private val art: android.graphics.Bitmap? = if (spots == null) null else RegionLook.art(context, region.id)?.let { feather(it) }

    /** fades the four edges of the picture to nothing, so its own sky melts into the screen instead of showing a box */
    private fun feather(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val b = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val c = Canvas(b); val w = b.width.toFloat(); val h = b.height.toFloat()
        val p = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
        val clear = 0x00000000; val solid = Color.BLACK
        val fx = floatArrayOf(0f, 0.12f, 0.88f, 1f); val fy = floatArrayOf(0f, 0.14f, 0.86f, 1f)
        p.shader = android.graphics.LinearGradient(0f, 0f, w, 0f, intArrayOf(clear, solid, solid, clear), fx, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, p)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, intArrayOf(clear, solid, solid, clear), fy, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, p)
        return b
    }

    // ---- the island: a rounded square in ground coordinates with a few bumps
    private val outline: List<Pair<Float, Float>> = run {
        val seed = region.id.hashCode()
        (0 until 32).map { i ->
            val a = i / 32f * 2f * Math.PI.toFloat()
            val c = cos(a); val s = sin(a)
            val r = 0.93f + 0.06f * sin(3f * a + (seed % 7)) + 0.03f * sin(5f * a + (seed % 5))
            (sign(c) * sqrt(abs(c)) * r) to (sign(s) * sqrt(abs(s)) * r)
        }
    }

    private class Piece(val u: Float, val v: Float, val kind: Int, val size: Float, val height: Float, val color: Int, val item: DexItem?)
    private val pieces: List<Piece>
    private val petAt = -0.58f to 0.52f

    init {
        val rnd = java.util.Random(region.id.hashCode().toLong())
        val slots = arrayOf(0.05f to -0.55f, -0.5f to -0.08f, 0.52f to -0.1f, -0.08f to 0.38f, 0.55f to 0.5f)
        val list = ArrayList<Piece>()
        val items = region.items.sortedByDescending { it.rarity.ordinal }   // the rarest stands at the back, like a landmark
        for ((i, it) in items.withIndex()) list += Piece(slots[i].first, slots[i].second, PROP, 0.11f, 0f, 0, it)
        fun free(u: Float, v: Float, gap: Float): Boolean {
            if (u * u * u * u + v * v * v * v > 0.5f) return false
            for (s in slots) if (hypot(u - s.first, v - s.second) < gap) return false
            if (hypot(u - petAt.first, v - petAt.second) < gap) return false
            for (p in list) if (p.kind != PROP && hypot(u - p.u, v - p.v) < 0.16f) return false
            return true
        }
        var tries = 0; var boxes = 0
        val maxBoxes = if (land == Land.CITY) 9 else 0
        while (boxes < maxBoxes && tries++ < 400) {
            val u = rnd.nextFloat() * 1.7f - 0.85f; val v = rnd.nextFloat() * 1.7f - 0.85f
            if (!free(u, v, 0.34f)) continue
            val pale = intArrayOf(0xFFD8D6F6.toInt(), 0xFFF4DCEB.toInt(), 0xFFE2E8FA.toInt(), 0xFFEFE4D4.toInt())
            list += Piece(u, v, BOX, 0.045f + rnd.nextFloat() * 0.03f, 0.1f + rnd.nextFloat() * 0.22f, pale[rnd.nextInt(pale.size)], null); boxes++
        }
        tries = 0; var hills = 0
        val maxHills = when (land) { Land.MOUNTAIN -> 6; Land.ISLAND -> 1; Land.COAST -> 2; Land.PLAIN -> 3; else -> 0 }
        while (hills < maxHills && tries++ < 400) {
            val u = rnd.nextFloat() * 1.7f - 0.85f; val v = rnd.nextFloat() * 1.7f - 0.85f
            if (!free(u, v, 0.4f)) continue
            list += Piece(u, v, HILL, if (land == Land.ISLAND) 0.2f else 0.1f + rnd.nextFloat() * 0.07f, 0f, 0, null); hills++
        }
        tries = 0; var trees = 0
        while (trees < (if (land == Land.CITY) 12 else 18) && tries++ < 600) {
            val u = rnd.nextFloat() * 1.8f - 0.9f; val v = rnd.nextFloat() * 1.8f - 0.9f
            if (!free(u, v, 0.26f)) continue
            list += Piece(u, v, TREE, 0.04f + rnd.nextFloat() * 0.02f, 0f, 0, null); trees++
        }
        list += Piece(petAt.first, petAt.second, PET, 0f, 0f, 0, null)
        list.sortBy { it.u + it.v }
        pieces = list
    }

    private fun hypot(a: Float, b: Float) = sqrt(a * a + b * b)

    // ---- where each prop was drawn, to find the one that is touched
    private val hitX = FloatArray(8); private val hitY = FloatArray(8); private val hitR = FloatArray(8); private val hitId = arrayOfNulls<String>(8)
    private var hits = 0

    private var sx = 1f; private var sy = 1f; private var cx0 = 0f; private var cy0 = 0f; private var th = 0f
    private fun ix(u: Float, v: Float) = cx0 + (u - v) * sx
    private fun iy(u: Float, v: Float) = cy0 + (u + v) * sy

    private fun tone(c: Int) = if (night) Look.mix(c, 0xFF1B2A6B.toInt(), 0.4f) else c
    private fun dark(c: Int, k: Float) = Look.mix(c, Color.BLACK, k)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        sx = min(w * 0.27f, h * 0.43f) * zoom; sy = sx * 0.52f; th = sx * 0.22f
        cx0 = w * 0.5f; cy0 = h * 0.5f - th * 0.4f
        hits = 0
        val now = System.currentTimeMillis()

        // a glow on the ground under the island
        fill.shader = RadialGradient(cx0, cy0 + th, sx * 1.5f, if (night) 0x553F66FF else 0x40FFFFFF, 0x00000000, Shader.TileMode.CLAMP)
        fill.color = Color.BLACK; cv.drawCircle(cx0, cy0 + th, sx * 1.5f, fill); fill.shader = null

        if (art != null) {   // a picture of the island: put it in the middle, the props and tags stand on top of it
            val k = min(w * 0.92f / art.width, h * 0.92f / art.height) * zoom
            val aw = art.width * k; val ah = art.height * k
            cv.drawBitmap(art, null, android.graphics.RectF(w / 2f - aw / 2f, h / 2f - ah / 2f, w / 2f + aw / 2f, h / 2f + ah / 2f), fill)
            // the picture shows the pet and the stall; each other item stands on its spot: a "?" bubble, or its sticker once found
            val ra = spots
            if (ra != null) {
                val firstAt = ra.first
                val first = if (firstAt != null) region.items.firstOrNull() else null   // Seoul: the first item is painted into the picture
                val rest = region.items.filter { it !== first }.sortedByDescending { it.rarity.ordinal }   // the rarest on the furthest spot
                for ((i, item) in rest.withIndex()) {
                    val sp = ra.spots.getOrNull(i) ?: break
                    val x = w / 2f - aw / 2f + sp.first * aw; val y = h / 2f - ah / 2f + sp.second * ah
                    drawBadge(cv, item, x, y, aw * 0.1f, y + ah * ra.tileDy, bubbleR = aw * ra.bubbleR)   // the tile lies just under the bubble
                }
                // the first item is painted into the picture (the stall); its dish is covered by the same sticker as the others
                if (first != null && firstAt != null) { val sp = firstAt; run {
                    val x = w / 2f - aw / 2f + sp.first * aw; val y = h / 2f - ah / 2f + sp.second * ah
                    drawBadge(cv, first, x, y, aw * 0.088f, y + ah * 0.06f, tag = false)   // the stall already has its sign
                } }
                if (isAttachedToWindow) postInvalidateDelayed(80)   // the twinkles
            }
            return
        }
        // the slab: the side walls, then the top
        val side = tone(if (land == Land.ISLAND) 0xFF3B3F4A.toInt() else Look.mix(hue, 0xFF3A2C7A.toInt(), 0.5f))
        for (i in outline.indices) {
            val a = outline[i]; val b = outline[(i + 1) % outline.size]
            path.reset(); path.moveTo(ix(a.first, a.second), iy(a.first, a.second)); path.lineTo(ix(b.first, b.second), iy(b.first, b.second))
            path.lineTo(ix(b.first, b.second), iy(b.first, b.second) + th); path.lineTo(ix(a.first, a.second), iy(a.first, a.second) + th); path.close()
            fill.color = if (a.first + a.second > b.first + b.second) dark(side, 0.12f) else side; cv.drawPath(path, fill)
        }
        path.reset()
        for ((i, p) in outline.withIndex()) { if (i == 0) path.moveTo(ix(p.first, p.second), iy(p.first, p.second)) else path.lineTo(ix(p.first, p.second), iy(p.first, p.second)) }
        path.close()
        val topA = tone(Look.mix(0xFFCDEBB5.toInt(), hue, 0.18f)); val topB = tone(Look.mix(0xFF86C27F.toInt(), hue, 0.18f))
        fill.shader = android.graphics.LinearGradient(0f, cy0 - sy, 0f, cy0 + sy, topA, topB, Shader.TileMode.CLAMP); fill.color = Color.BLACK; cv.drawPath(path, fill); fill.shader = null
        line.color = if (night) 0xCCBFD2FF.toInt() else 0xCCFFFFFF.toInt(); line.strokeWidth = 2.4f * d; cv.drawPath(path, line)
        // a paler plaza in the middle, so the island does not look flat
        cv.save(); cv.translate(0f, 0f)
        path.reset()
        for ((i, p) in outline.withIndex()) { val u = p.first * 0.72f; val v = p.second * 0.72f; if (i == 0) path.moveTo(ix(u, v), iy(u, v)) else path.lineTo(ix(u, v), iy(u, v)) }
        path.close(); fill.color = if (night) 0x22FFFFFF else 0x30FFFFFF; cv.drawPath(path, fill); cv.restore()

        for (p in pieces) when (p.kind) {
            TREE -> drawTree(cv, p)
            HILL -> drawHill(cv, p)
            BOX -> drawBox(cv, p)
            PROP -> drawProp(cv, p.item!!, p)
            PET -> drawPet(cv, p, now)
        }
    }

    private fun drawTree(cv: Canvas, p: Piece) {
        val x = ix(p.u, p.v); val y = iy(p.u, p.v); val r = p.size * sx
        fill.color = 0x26000000; cv.drawOval(x - r * 1.1f, y - r * 0.35f, x + r * 1.1f, y + r * 0.35f, fill)
        if ((land == Land.ISLAND || land == Land.COAST) && (p.u * 7).toInt() % 2 == 0) {   // a palm
            fill.color = tone(0xFF8A6A48.toInt()); cv.drawRect(x - r * 0.1f, y - r * 1.9f, x + r * 0.1f, y, fill)
            fill.color = tone(0xFF3FAE6A.toInt())
            for (k in -2..2) { cv.save(); cv.rotate(k * 32f, x, y - r * 1.9f); cv.drawOval(x - r * 0.12f, y - r * 2.7f, x + r * 0.12f, y - r * 1.85f, fill); cv.restore() }
            return
        }
        fill.color = tone(0xFF7A5A3C.toInt()); cv.drawRect(x - r * 0.12f, y - r * 0.9f, x + r * 0.12f, y, fill)
        fill.color = tone(0xFF3F9A4F.toInt()); cv.drawCircle(x, y - r * 1.25f, r * 0.85f, fill)
        fill.color = tone(0xFF63BB68.toInt()); cv.drawCircle(x - r * 0.25f, y - r * 1.5f, r * 0.6f, fill)
    }

    /**
     * A found item on a "?" spot of the picture, drawn as a sticker: its own shape cut out with a white edge (gold when chosen),
     * a warm light on the ground under it and two twinkles, so it reads as something collected and not as part of the picture.
     * Nothing is drawn while it is still unknown (the picture's "?" bubble stays).
     */
    private fun drawBadge(cv: Canvas, item: DexItem, x: Float, y: Float, size: Float, groundY: Float, bubbleR: Float = 0f, tag: Boolean = true) {
        val have = PetHub.started && PetHub.dex.has(item.id)
        if (hits < hitX.size) { hitX[hits] = x; hitY[hits] = y; hitR[hits] = size * 0.7f; hitId[hits] = item.id; hits++ }
        val chosen = selectedId == item.id
        if (!have) { if (bubbleR > 0f) drawMystery(cv, x, y, groundY, bubbleR, chosen); return }
        ItemArt.groundGlow(cv, x, groundY, size * (if (chosen) 0.85f else 0.7f))
        ItemArt.sticker(cv, item, x, y, size, if (chosen) 0xFFFFD66B.toInt() else 0xFFFFFFFF.toInt(), d)
        val tw = (System.currentTimeMillis() % 1600) / 1600f
        ItemArt.twinkle(cv, x + size * 0.5f, y - size * 0.42f, size * (0.07f + 0.04f * sin(tw * 6.283f)), 0xFFFFE7A0.toInt())
        ItemArt.twinkle(cv, x - size * 0.52f, y - size * 0.12f, size * (0.05f + 0.03f * cos(tw * 6.283f)), 0xFFFFFFFF.toInt())
        if (!tag) return
        // the name tag, in the same style as the tags painted in the picture
        txt.textSize = 15f * d; txt.color = 0xFFF0F3FF.toInt()
        val label = item.name + if (item.rarity == Rarity.RARE) "  ★" else ""
        val w2 = txt.measureText(label) / 2f + 12f * d; val ty = y - size * 0.62f
        fill.color = 0xF01A2466.toInt(); cv.drawRoundRect(x - w2, ty - 18f * d, x + w2, ty + 8f * d, 13f * d, 13f * d, fill)
        line.color = if (chosen) 0xFFFFD66B.toInt() else 0x998FA0F0.toInt(); line.strokeWidth = 1.4f * d
        cv.drawRoundRect(x - w2, ty - 18f * d, x + w2, ty + 8f * d, 13f * d, 13f * d, line)
        cv.drawText(label, x, ty, txt)
    }

    /** a spot still to be found: a small dark tile on the ground and a glass bubble with "?" over it (gold when chosen) */
    private fun drawMystery(cv: Canvas, x: Float, y: Float, groundY: Float, r: Float, chosen: Boolean) {
        val hw = r * 0.8f; val hh = hw * 0.5f; val t = r * 0.14f
        fun diamond(cy: Float) { path.reset(); path.moveTo(x, cy - hh); path.lineTo(x + hw, cy); path.lineTo(x, cy + hh); path.lineTo(x - hw, cy); path.close() }
        diamond(groundY + t); fill.color = 0xFF16206B.toInt(); cv.drawPath(path, fill)                     // its thickness
        diamond(groundY); fill.color = if (chosen) 0xFF5A4AB8.toInt() else 0xFF2A3A9A.toInt(); cv.drawPath(path, fill)
        line.color = if (chosen) 0xFFFFD66B.toInt() else 0xFF8FA6FF.toInt(); line.strokeWidth = (if (chosen) 2.4f else 1.4f) * d; cv.drawPath(path, line)
        // the glass bubble
        fill.shader = RadialGradient(x - r * 0.3f, y - r * 0.35f, r * 1.3f, intArrayOf(0x66FFFFFF, 0x33E6ECFF, 0x55C8D4FF), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        fill.color = Color.BLACK; cv.drawCircle(x, y, r, fill); fill.shader = null
        fill.color = 0x88FFFFFF.toInt(); cv.drawOval(x - r * 0.62f, y - r * 0.78f, x - r * 0.12f, y - r * 0.5f, fill)   // the shine
        line.color = if (chosen) 0xFFFFD66B.toInt() else 0xAAFFFFFF.toInt(); line.strokeWidth = (if (chosen) 3f else 1.6f) * d; cv.drawCircle(x, y, r, line)
        txt.textSize = r * 1.25f; txt.color = Color.WHITE; cv.drawText("?", x, y + r * 0.43f, txt)
    }

    private fun drawHill(cv: Canvas, p: Piece) {
        val x = ix(p.u, p.v); val y = iy(p.u, p.v); val r = p.size * sx * 2.6f
        val h = r * (if (land == Land.ISLAND) 0.95f else 1.1f)
        fill.color = 0x22000000; cv.drawOval(x - r * 1.05f, y - r * 0.3f, x + r * 1.05f, y + r * 0.3f, fill)
        path.reset(); path.moveTo(x - r, y); path.lineTo(x - r * 0.12f, y - h); path.lineTo(x + r * 0.12f, y - h); path.lineTo(x + r, y); path.close()
        fill.color = tone(if (land == Land.ISLAND) 0xFF6E7F8C.toInt() else 0xFF8FA6B8.toInt()); cv.drawPath(path, fill)
        path.reset(); path.moveTo(x, y - h); path.lineTo(x + r * 0.12f, y - h); path.lineTo(x + r, y); path.lineTo(x, y); path.close()
        fill.color = dark(tone(0xFF8FA6B8.toInt()), 0.12f); cv.drawPath(path, fill)
        if (land == Land.MOUNTAIN) { path.reset(); path.moveTo(x - r * 0.3f, y - h * 0.72f); path.lineTo(x - r * 0.12f, y - h); path.lineTo(x + r * 0.12f, y - h); path.lineTo(x + r * 0.3f, y - h * 0.72f); path.close(); fill.color = Color.WHITE; cv.drawPath(path, fill) }
    }

    private fun drawBox(cv: Canvas, p: Piece) {
        val s = p.size; val u = p.u; val v = p.v; val hh = p.height * sx
        val c = tone(p.color)
        fun quad(a: Pair<Float, Float>, b: Pair<Float, Float>, up: Float, col: Int) {
            path.reset(); path.moveTo(ix(a.first, a.second), iy(a.first, a.second) - up)
            path.lineTo(ix(b.first, b.second), iy(b.first, b.second) - up); path.lineTo(ix(b.first, b.second), iy(b.first, b.second) - up - hh)
            path.lineTo(ix(a.first, a.second), iy(a.first, a.second) - up - hh); path.close(); fill.color = col; cv.drawPath(path, fill)
        }
        val a = (u - s) to (v - s); val b = (u + s) to (v - s); val cc = (u + s) to (v + s); val dd = (u - s) to (v + s)
        quad(dd, cc, 0f, dark(c, 0.18f)); quad(cc, b, 0f, dark(c, 0.08f))
        path.reset(); path.moveTo(ix(a.first, a.second), iy(a.first, a.second) - hh); path.lineTo(ix(b.first, b.second), iy(b.first, b.second) - hh)
        path.lineTo(ix(cc.first, cc.second), iy(cc.first, cc.second) - hh); path.lineTo(ix(dd.first, dd.second), iy(dd.first, dd.second) - hh); path.close()
        fill.color = Look.mix(c, Color.WHITE, 0.25f); cv.drawPath(path, fill)
        if (night) {   // a few lit windows on the left face
            fill.color = 0xCCFFD98A.toInt()
            val x0 = ix(dd.first, dd.second); val y0 = iy(dd.first, dd.second); val x1 = ix(cc.first, cc.second); val y1 = iy(cc.first, cc.second)
            var row = 0.2f
            while (row < 0.85f) { for (k in intArrayOf(1, 3)) { val t = k / 4f; val wx = x0 + (x1 - x0) * t; val wy = y0 + (y1 - y0) * t - hh * row; cv.drawRect(wx - 2f * d, wy - 3f * d, wx + 2f * d, wy + 1f * d, fill) }; row += 0.25f }
        }
    }

    private fun drawProp(cv: Canvas, item: DexItem, p: Piece) {
        val have = PetHub.started && PetHub.dex.has(item.id)
        val x = ix(p.u, p.v); val y = iy(p.u, p.v)
        val rare = item.rarity == Rarity.RARE
        val tile = p.size * sx
        // the pedestal: a flat rounded diamond
        path.reset(); path.moveTo(x, y - tile * 0.5f); path.lineTo(x + tile, y); path.lineTo(x, y + tile * 0.5f); path.lineTo(x - tile, y); path.close()
        fill.color = 0x30000000; cv.save(); cv.translate(0f, tile * 0.18f); cv.drawPath(path, fill); cv.restore()
        fill.color = if (have) (if (night) 0xFF5C6CC8.toInt() else 0xFFFFF4DA.toInt()) else (if (night) 0xFF2F3C8C.toInt() else 0xFFD9D4E8.toInt()); cv.drawPath(path, fill)
        line.color = if (have) rarityColor(item.rarity) else 0x66FFFFFF; line.strokeWidth = 2.4f * d; cv.drawPath(path, line)
        val selected = selectedId == item.id
        if (selected) { line.color = 0xFFFFD66B.toInt(); line.strokeWidth = 4f * d; cv.drawPath(path, line) }
        val es = sx * (if (rare) 0.5f else 0.42f)
        val cy = y - es * 0.55f
        if (have) {
            if (rare) { fill.shader = RadialGradient(x, cy, es * 0.9f, 0x66FFE9A0, 0x00FFE9A0, Shader.TileMode.CLAMP); fill.color = Color.BLACK; cv.drawCircle(x, cy, es * 0.9f, fill); fill.shader = null }
            fill.color = 0x30000000; cv.drawOval(x - es * 0.42f, y - es * 0.1f, x + es * 0.42f, y + es * 0.12f, fill)
            emoji.textSize = es; cv.drawText(item.emoji, x, cy + es * 0.34f, emoji)
        } else {
            fill.color = if (night) 0x55FFFFFF else 0x66FFFFFF; cv.drawCircle(x, cy, es * 0.42f, fill)
            txt.color = if (night) 0xFFE6ECFF.toInt() else 0xFF7A6A8F.toInt(); txt.textSize = es * 0.6f; cv.drawText("?", x, cy + es * 0.2f, txt)
        }
        // the name tag
        val label = (if (have) item.name else "???") + (if (rare) "  ★" else "")
        txt.textSize = 15f * d; txt.color = if (night) 0xFFF0F3FF.toInt() else Look.INK
        val tw = txt.measureText(label); val ty = cy - es * 0.62f
        fill.color = if (night) 0xE61A2466.toInt() else 0xF2FFFFFF.toInt()
        cv.drawRoundRect(x - tw / 2f - 12f * d, ty - 18f * d, x + tw / 2f + 12f * d, ty + 8f * d, 14f * d, 14f * d, fill)
        line.color = if (night) 0x998FA0F0.toInt() else 0x66B7A98F; line.strokeWidth = 1.4f * d; cv.drawRoundRect(x - tw / 2f - 12f * d, ty - 18f * d, x + tw / 2f + 12f * d, ty + 8f * d, 14f * d, 14f * d, line)
        cv.drawText(label, x, ty, txt)
        if (hits < hitX.size) { hitX[hits] = x; hitY[hits] = cy; hitR[hits] = max(es * 0.7f, tile); hitId[hits] = item.id; hits++ }
    }

    private fun rarityColor(r: Rarity) = when (r) { Rarity.COMMON -> 0xFFB8C2E6.toInt(); Rarity.UNCOMMON -> 0xFF8FB0FF.toInt(); Rarity.RARE -> 0xFFF2C25A.toInt() }

    private fun drawPet(cv: Canvas, p: Piece, now: Long) {
        val x = ix(p.u, p.v); val y = iy(p.u, p.v)
        val bw = sx * 0.2f
        fill.color = 0x33000000; cv.drawOval(x - bw * 1.1f, y - bw * 0.2f, x + bw * 1.1f, y + bw * 0.2f, fill)
        val stage = if (PetHub.started) PetHub.engine.snapshot().stage else GrowthStage.ADULT
        cv.save(); cv.translate(x, y); cv.scale(bw, bw)
        painter.draw(cv, PetState.NORMAL, stage, if (PetHub.started) PetHub.outfit else emptyMap(), false, false, 0f, max(1.6f * d / bw, 0.058f), now, false)
        cv.restore()
        // "와! 서울이다!"
        val label = "와! ${region.name}이다!"
        txt.textSize = 17f * d; txt.color = Look.INK
        val tw = txt.measureText(label); val bx = x + bw * 1.1f + tw / 2f + 22f * d; val by = y - bw * 1.6f
        fill.color = 0xFFFFFDF8.toInt(); cv.drawRoundRect(bx - tw / 2f - 16f * d, by - 22f * d, bx + tw / 2f + 16f * d, by + 12f * d, 18f * d, 18f * d, fill)
        line.color = Look.COCOA; line.strokeWidth = 2f * d; cv.drawRoundRect(bx - tw / 2f - 16f * d, by - 22f * d, bx + tw / 2f + 16f * d, by + 12f * d, 18f * d, 18f * d, line)
        cv.drawText(label, bx, by, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action != MotionEvent.ACTION_UP) return true
        var best = -1; var bd = Float.MAX_VALUE
        for (i in 0 until hits) {
            val dd = hypot(e.x - hitX[i], e.y - hitY[i])
            if (dd <= hitR[i] && dd < bd) { bd = dd; best = i }
        }
        if (best >= 0) hitId[best]?.let { onPick(it) }
        return true
    }

    private companion object {
        /** where the "?" bubbles are in each picture (fractions of the picture), rarest first, and the spot of the first item */
        const val HILL = 4; const val TREE = 0; const val BOX = 1; const val PROP = 2; const val PET = 3 }
}

/** the whole of Korea, small, with the chosen region lit up (top left of the region screen) */
class RegionMiniMap(context: Context, private val regionId: String, private val night: Boolean) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Look.display(context) }
    private val paths = ArrayList<Pair<String, Path>>()
    private var cx = 0f; private var cy = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        paths.clear()
        val kx = cos(Math.toRadians(36.0)); val lonMin = 124.6; val lonMax = 131.0; val latMin = 33.0; val latMax = 38.65
        val s = min((w - 6 * d) / ((lonMax - lonMin) * kx), (h - 6 * d) / (latMax - latMin))
        val ox = (w - (lonMax - lonMin) * kx * s) / 2.0 - lonMin * kx * s; val oy = (h - (latMax - latMin) * s) / 2.0
        for (sh in KoreaMap.shapes) {
            val p = Path()
            for (ring in sh.rings) {
                for (i in 0 until ring.size / 2) {
                    val x = (ring[2 * i] * kx * s + ox).toFloat(); val y = ((latMax - ring[2 * i + 1]) * s + oy).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            paths += sh.id to p
        }
        // labelPoint is (lon, lat), like the rings
        KoreaMap.labelPoint(regionId)?.let { cx = (it.first * kx * s + ox).toFloat(); cy = ((latMax - it.second) * s + oy).toFloat() }
    }

    override fun onDraw(cv: Canvas) {
        val hue = RegionLook.hue[regionId] ?: Color.GRAY
        for ((id, p) in paths) {
            fill.color = if (id == regionId) hue else if (night) 0x553A4C96 else 0x66FFFFFF; cv.drawPath(p, fill)
            line.color = if (night) 0x66BFD2FF else 0x99FFFFFF.toInt(); line.strokeWidth = 0.8f * d; cv.drawPath(p, line)
        }
        for ((id, p) in paths) if (id == regionId) { line.color = Color.WHITE; line.strokeWidth = 2f * d; cv.drawPath(p, line) }
        val name = Korea.region(regionId)?.name ?: return
        txt.textSize = 13f * d; val tw = txt.measureText(name)
        fill.color = 0xFFFFFDF8.toInt(); cv.drawRoundRect(cx - tw / 2f - 9f * d, cy - 34f * d, cx + tw / 2f + 9f * d, cy - 10f * d, 12f * d, 12f * d, fill)
        txt.color = Look.INK; cv.drawText(name, cx, cy - 16f * d, txt)
    }
}

/** the screen of one region: the island on the left, what has been found on the right */
class RegionLayer(context: Context, private val regionId: String, private val onBack: () -> Unit) : FrameLayout(context) {
    /** the island pictures are painted at night, so a region that has one is always shown in the night look */
    private val night = Night.isDark(context) || RegionLook.hasArt(context, regionId)
    private val kit = Kit(context)
    private val region = Korea.region(regionId)!!
    private var picked: String? = null
    private val ink get() = if (night) 0xFFF0F3FF.toInt() else Look.INK
    private val sub get() = if (night) 0xFFB8C2E6.toInt() else Look.SUB
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private lateinit var scene: RegionSceneView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int, stroke: Int? = null, w: Int = 2) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != null) setStroke(dp(w), stroke) }
    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply { text = s; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD }
    private fun title(s: String, sp: Float, color: Int) = kit.title(TextView(context).apply { text = s }, sp, color)
    private fun rarityColor(r: Rarity) = when (r) { Rarity.COMMON -> if (night) 0xFFB8C2E6.toInt() else 0xFF9A8F86.toInt(); Rarity.UNCOMMON -> if (night) 0xFF8FB0FF.toInt() else 0xFF3F6FD0.toInt(); Rarity.RARE -> if (night) 0xFFF2C25A.toInt() else 0xFFB87510.toInt() }

    init {
        val grad = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (night) intArrayOf(Color.parseColor("#0A1448"), Color.parseColor("#122A74"), Color.parseColor("#1C3F96")) else intArrayOf(Color.parseColor("#D5E8FC"), Color.parseColor("#B4D5F8"), Color.parseColor("#9CC9F2")))
        background = LayerDrawable(arrayOf(grad, if (landOf(regionId) == Land.CITY) SkylineDrawable(resources.displayMetrics.density, night) else HorizonDrawable(resources.displayMetrics.density, night, landOf(regionId))))
        isClickable = true

        val back = title("‹ 뒤로", 19f, ink).apply {
            gravity = Gravity.CENTER; minHeight = dp(44); minWidth = dp(92); setPadding(dp(12), dp(6), dp(16), dp(6))
            background = if (night) kit.card(100f, 0xE61B2566.toInt(), Color.argb(150, 140, 160, 240), 1.2f) else kit.card(100f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
            kit.lift(this, 3f); setOnClickListener { onBack() }
        }
        val headInk = if (night) Color.parseColor("#E6ECFF") else Color.parseColor("#2D3F66")
        val name = title(region.name, 26f, headInk).apply {
            gravity = Gravity.CENTER; setPadding(dp(20), dp(8), dp(24), dp(8))
            setCompoundDrawablesRelative(Glyphs.pin(context, 22f), null, null, null); compoundDrawablePadding = dp(8)
            background = if (night) round(Color.argb(200, 16, 28, 90), 26, Color.argb(160, 140, 160, 240), 1) else round(Color.argb(210, 255, 255, 255), 26, Color.argb(230, 255, 255, 255), 1)
        }
        val area = text(RegionLook.area[regionId] ?: "", 14f, sub, true).apply { setPadding(dp(14), 0, 0, 0) }
        val all = title("도감 전체 보기 ›", 16f, headInk).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), dp(8), dp(18), dp(8))
            background = if (night) round(Color.argb(150, 20, 30, 60), 18, Color.argb(160, 150, 165, 210), 1) else round(Color.argb(191, 255, 255, 255), 18, Color.argb(230, 255, 255, 255), 1)
            setOnClickListener { onBack() }
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(16), dp(6))
            addView(back)
            addView(LinearLayout(context).apply { gravity = Gravity.CENTER; addView(name); addView(area) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(all)
        }

        scene = RegionSceneView(context, region, night) { id -> picked = id; scene.selectedId = id; renderPanel() }
        val zoomBtn = { label: String, onTap: () -> Unit ->
            title(label, 24f, headInk).apply {
                gravity = Gravity.CENTER; minWidth = dp(56); minHeight = dp(56)
                background = if (night) round(0xE61B2566.toInt(), 28, Color.argb(150, 140, 160, 240), 1) else round(0xFFFFFCF6.toInt(), 28, 0xFFEFDFCB.toInt(), 1)
                setOnClickListener { onTap() }
            }
        }
        val zoomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(zoomBtn("−") { scene.zoom = scene.zoom - 0.15f }); addView(zoomBtn("+") { scene.zoom = scene.zoom + 0.15f }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            addView(zoomBtn("◎") { scene.zoom = 1f }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        }
        val sceneFrame = FrameLayout(context).apply {
            addView(scene, LayoutParams(-1, -1))
            addView(RegionMiniMap(context, regionId, night), LayoutParams(dp(190), dp(260), Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(8), 0, 0) })
            addView(zoomRow, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(18), 0, 0, dp(18)) })
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sceneFrame, LinearLayout.LayoutParams(0, -1, 1f))
            addView(ScrollView(context).apply {
                background = if (night) LayerDrawable(arrayOf(round(0xE6141E5A.toInt(), 24, Color.argb(160, 140, 160, 240), 1))) else kit.card(24f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
                clipToOutline = true; kit.lift(this, 6f); addView(panel); isVerticalScrollBarEnabled = false
            }, LinearLayout.LayoutParams(dp(340), -1).apply { setMargins(dp(8), dp(4), dp(14), dp(14)) })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-1, dp(60)))
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }, LayoutParams(-1, -1))
        // the rarest thing found so far is shown first
        picked = region.items.sortedByDescending { it.rarity.ordinal }.firstOrNull { PetHub.started && PetHub.dex.has(it.id) }?.id
        scene.selectedId = picked
        renderPanel()
    }

    private fun renderPanel() {
        panel.removeAllViews()
        panel.setPadding(dp(16), dp(14), dp(16), dp(14))
        val got = region.items.count { PetHub.started && PetHub.dex.has(it.id) }
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(title("${region.name} 컬렉션", 24f, ink), LinearLayout.LayoutParams(0, -2, 1f))
            addView(title("$got / ${region.items.size}", 22f, ink))
        })
        panel.addView(PillBar(context, 0xFFFFD978.toInt(), 0xFFF2B33D.toInt(), if (night) 0x33FFFFFF else 0xFFF1E4D2.toInt()).apply { max = region.items.size; progress = got }, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(10) })
        // one row per item: a round picture (or "?"), the name (or "???") and "kind · region"
        val items = region.items
        for ((i, item) in items.withIndex()) {
            val have = PetHub.started && PetHub.dex.has(item.id)
            val on = picked == item.id
            panel.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(12), dp(8))
                background = when {
                    on -> round(if (night) 0xFF3A4AA8.toInt() else 0xFFFFF4DA.toInt(), 18, if (night) 0xFFE6ECFF.toInt() else 0xFFFFD66B.toInt(), 2)
                    have -> round(if (night) 0xFF2A3790.toInt() else Look.WHITE, 18, rarityColor(item.rarity), 2)
                    else -> round(if (night) 0xFF1D2A78.toInt() else 0xFFF8F1E7.toInt(), 18, Color.argb(90, 140, 160, 230), 1)
                }
                addView((if (have) TextView(context).apply { foreground = ItemIconDrawable(item) } else title("?", 20f, if (night) 0xFF8FA0E0.toInt() else Look.MUTED)).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (night) (if (have) 0xFF3A4AA8.toInt() else 0xFF2B3A92.toInt()) else if (have) Color.parseColor("#FFF4E2") else Color.parseColor("#EFE5D8")) }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(if (have) item.name else "???", 15.5f, if (have) ink else sub, true).apply { maxLines = 1 })
                    addView(text("${item.kind.label} · ${region.name}", 12f, rarityColor(item.rarity), true))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                if (have && item.rarity == Rarity.RARE) addView(text("★", 18f, 0xFFF2C25A.toInt(), true))
                setOnClickListener { picked = item.id; scene.selectedId = item.id; renderPanel() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (i == 0) dp(14) else dp(8) })
        }
        // the card of the chosen item
        items.firstOrNull { it.id == picked }?.let { item ->
            val have = PetHub.started && PetHub.dex.has(item.id)
            panel.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = round(if (night) 0xFF2A3790.toInt() else Look.WHITE, 22, rarityColor(item.rarity), 2)
                addView((if (have) TextView(context).apply { foreground = ItemIconDrawable(item, 0.7f) } else text("?", 54f, ink)).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (night) 0xFF3A4AA8.toInt() else Color.parseColor("#FFF4E2")); setStroke(dp(2), rarityColor(item.rarity)) }
                }, LinearLayout.LayoutParams(dp(110), dp(110)))
                addView(title(if (have) item.name else "???", 24f, ink), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
                addView(text("${region.name} · ${item.kind.label} · ${item.rarity.label}", 14f, rarityColor(item.rarity), true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                addView(text(RegionLook.line(region, item, have), 14.5f, ink).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        RegionLook.blurb[regionId]?.let { panel.addView(text(it, 13.5f, ink).apply { setPadding(0, dp(14), 0, 0); setLineSpacing(0f, 1.25f) }) }
        val left = region.items.size - got
        panel.addView(text(if (left == 0) "이 지역의 컬렉션을 모두 모았어요! 🎉" else "${left}개를 더 모으면 컬렉션 완성이에요.", 14f, if (left == 0) (if (night) 0xFFFFD27A.toInt() else 0xFF7A5200.toInt()) else sub, true).apply { setPadding(0, dp(14), 0, 0) })
    }
}
