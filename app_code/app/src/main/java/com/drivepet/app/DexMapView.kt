package com.drivepet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import java.util.Random

/**
 * The map of the map collection, drawn like a paper sticker map of Korea: every 시·도 in its own pastel colour (provinces that
 * share a colour group, like Seoul, Incheon and Gyeonggi, are told apart by the white outlines), cut into small cells, on a
 * daytime sea. A region that has nothing found yet is faded like old paper; the more is found, the more its colour comes back,
 * and a finished one is fully bright with a gold outline. The cells are only a drawing: they are the nearest main town of the region.
 *
 * The heavy part (which pixel belongs to which region and cell) is computed once on a background thread whenever the size
 * changes; changing the colours afterwards only repaints one bitmap.
 */
class DexMapView(context: Context, private val onPick: (String) -> Unit) : View(context) {
    var selected: String? = null
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val kx = cos(Math.toRadians(36.0))

    private var s = 1f; private var ox = 0f; private var oy = 0f
    private val paths = HashMap<String, Path>()
    private var region: IntArray? = null      // per pixel: -1 sea, otherwise the index into Korea.regions
    private var cell: IntArray? = null        // per pixel: which cell (the index of the nearest anchor)
    private var bmp: Bitmap? = null
    private var building = false
    private var seaBg: Paint? = null
    private val waves = ArrayList<FloatArray>()
    private val chips = ArrayList<Pair<RectF, String>>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val disp: Typeface = Look.display(context)
    private val ink = Ink()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    /** dark mode: a night sea, dim land colours and light labels */
    private var night = false

    private fun c(hex: String) = Color.parseColor(hex)

    private fun makeSea(w: Int, h: Int) = Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, w * 0.25f, h.toFloat(),
            if (night) intArrayOf(c("#0A1448"), c("#122A74"), c("#1C3F96")) else intArrayOf(c("#D5E8FC"), c("#B4D5F8"), c("#9CC9F2")),
            floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }
    private val rimColor get() = if (night) 0xFFEAF1FF.toInt() else Color.WHITE
    private val lineColor get() = if (night) Color.argb(200, 150, 165, 210) else Color.argb(242, 255, 255, 255)
    private val textColor get() = if (night) 0xFFF0F3FF.toInt() else Look.INK
    private val haloColor get() = if (night) Color.argb(235, 14, 22, 48) else Color.argb(242, 255, 255, 255)
    private val doneColor get() = if (night) c("#FFD27A") else c("#B07A00")
    private val subColor get() = if (night) 0xFFB8C2E6.toInt() else Look.SUB

    /** the colour group of each region (like the colour blocks of a sticker map) */
    private val hue: Map<String, Int> = mapOf(
        "seoul" to "#9B6CFF", "incheon" to "#9B6CFF", "gyeonggi" to "#9B6CFF",
        "gangwon" to "#F5A04A",
        "chungbuk" to "#E8589E", "sejong" to "#E8589E", "daejeon" to "#E8589E",
        "chungnam" to "#5B8FF9",
        "jeonbuk" to "#E2C84A",
        "gwangju" to "#35C48A", "jeonnam" to "#35C48A",
        "gyeongbuk" to "#4E8BEA", "daegu" to "#4E8BEA",
        "gyeongnam" to "#E266C0", "busan" to "#E266C0", "ulsan" to "#E266C0",
        "jeju" to "#86DA5A",
    ).mapValues { c(it.value) }

    /** small cities are named on a chip out at sea, with a line to the place: (chip lon, chip lat) */
    private val callout: Map<String, Pair<Double, Double>> = mapOf(
        "seoul" to (126.35 to 38.25), "incheon" to (125.5 to 37.3), "sejong" to (125.5 to 36.65), "daejeon" to (125.5 to 36.05),
        "gwangju" to (125.5 to 35.3), "daegu" to (130.55 to 36.3), "ulsan" to (130.55 to 35.65), "busan" to (130.45 to 35.05),
    )
    /** names that sit on the region, where the middle of the biggest outline is a bad place */
    private val labelAt: Map<String, Pair<Double, Double>> = mapOf(
        "gyeonggi" to (127.3 to 37.2), "chungnam" to (126.75 to 36.5), "jeonnam" to (126.95 to 34.9), "jeju" to (126.55 to 33.36),
    )

    init {
        val r = Random(5)
        repeat(40) { waves += floatArrayOf(r.nextFloat(), r.nextFloat(), 5f + r.nextFloat() * 5f) }
    }

    fun refresh() { recolor(); invalidate() }

    private fun px(lon: Double) = (lon * kx * s + ox).toFloat()
    private fun py(lat: Double) = ((LAT_MAX - lat) * s + oy).toFloat()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0 || h <= 0) return
        val pad = 14 * d
        val worldW = (LON_MAX - LON_MIN) * kx; val worldH = LAT_MAX - LAT_MIN
        s = min((w - 2 * pad) / worldW, (h - 2 * pad) / worldH).toFloat()
        ox = ((w - worldW * s) / 2.0 - LON_MIN * kx * s).toFloat()
        oy = ((h - worldH * s) / 2.0).toFloat()
        paths.clear()
        for (sh in KoreaMap.shapes) {
            val p = Path().apply { fillType = Path.FillType.EVEN_ODD }
            for (ring in sh.rings) {
                for (i in 0 until ring.size / 2) {
                    val x = px(ring[2 * i]); val y = py(ring[2 * i + 1])
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            paths[sh.id] = p
        }
        night = Night.isDark(context)
        seaBg = makeSea(w, h)
        buildBuffers(w, h)
    }

    private fun buildBuffers(w: Int, h: Int) {
        if (building) return
        building = true
        val regions = Korea.regions
        val pathCopy = HashMap(paths)
        // the anchors of every region, in pixels
        val ax = Array(regions.size) { i -> FloatArray(regions[i].anchors.size) { k -> px(regions[i].anchors[k].second) } }
        val ay = Array(regions.size) { i -> FloatArray(regions[i].anchors.size) { k -> py(regions[i].anchors[k].first) } }
        val base = IntArray(regions.size)
        var acc = 0
        for (i in regions.indices) { base[i] = acc; acc += regions[i].anchors.size }
        Thread {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            val p = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
            regions.forEachIndexed { i, r -> p.color = Color.rgb(i + 1, 0, 0); pathCopy[r.id]?.let { cv.drawPath(it, p) } }
            val raw = IntArray(w * h); b.getPixels(raw, 0, w, 0, 0, w, h)
            val reg = IntArray(w * h) { if (Color.alpha(raw[it]) == 0) -1 else Color.red(raw[it]) - 1 }
            val cel = IntArray(w * h) { -1 }
            for (y in 0 until h) for (x in 0 until w) {
                val r = reg[y * w + x]
                if (r < 0 || r >= regions.size) continue
                var best = 0; var bd = Float.MAX_VALUE
                val xs = ax[r]; val ys = ay[r]
                for (k in xs.indices) { val dx = xs[k] - x; val dy = ys[k] - y; val dd = dx * dx + dy * dy; if (dd < bd) { bd = dd; best = k } }
                cel[y * w + x] = base[r] + best
            }
            post {
                bmp?.recycle()
                bmp = b
                region = reg; cell = cel
                building = false
                recolor(); invalidate()
            }
        }.start()
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb((Color.red(a) * (1 - k) + Color.red(b) * k).toInt(), (Color.green(a) * (1 - k) + Color.green(b) * k).toInt(), (Color.blue(a) * (1 - k) + Color.blue(b) * k).toInt())
    }

    /** how a region is coloured: dull when nothing is found, brighter with every find, fully bright when complete */
    private fun fillOf(id: String): Int {
        val r = Korea.region(id) ?: return Color.GRAY
        val hueC = (hue[id] ?: Color.GRAY).let { if (night) mix(it, 0xFF121A44.toInt(), 0.06f) else it }
        val paper = if (night) PAPER_NIGHT else PAPER
        val k = 0.3f   // how far an undiscovered region fades toward the paper (the colours stay vivid)
        if (!PetHub.started) return mix(hueC, paper, k)
        val n = PetHub.dex.count(id)
        val frac = n / r.items.size.toFloat()
        return mix(hueC, paper, k * (1f - frac) + (if (n == 0) 0.04f else 0f))
    }

    private fun recolor() {
        val b = bmp ?: return
        val reg = region ?: return; val cel = cell ?: return
        val w = b.width; val h = b.height
        val colors = IntArray(Korea.regions.size) { fillOf(Korea.regions[it].id) }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val r = reg[i]
            if (r < 0) continue
            var col = colors[r]
            val rightDiff = x + 1 < w && reg[i + 1] == r && cel[i + 1] != cel[i]
            val downDiff = y + 1 < h && reg[i + w] == r && cel[i + w] != cel[i]
            if (rightDiff || downDiff) col = if (night) mix(col, 0xFF8FA0D0.toInt(), 0.2f) else mix(col, Color.WHITE, 0.45f)
            val grain = (((x * 73856093) xor (y * 19349663)) and 15) / 3   // a fine paper grain
            out[i] = Color.rgb((Color.red(col) - grain).coerceAtLeast(0), (Color.green(col) - grain).coerceAtLeast(0), (Color.blue(col) - grain).coerceAtLeast(0))
        }
        b.setPixels(out, 0, w, 0, 0, w, h)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val nowNight = Night.isDark(context)
        if (nowNight != night) { night = nowNight; seaBg = makeSea(width, height); recolor() }   // the mode was switched while the map is open
        seaBg?.let { cv.drawRect(0f, 0f, w, h, it) }
        if (night) drawSky(cv, w, h)
        // little wave marks on the sea
        stroke.color = if (night) Color.argb(60, 190, 210, 255) else Color.argb(140, 255, 255, 255); stroke.strokeWidth = 1.6f * d
        for (wv in waves) {
            val x = wv[0] * w; val y = wv[1] * h; val s = wv[2] * d
            ink.path.reset(); ink.path.moveTo(x - s, y); ink.path.quadTo(x - s / 2f, y - s * 0.6f, x, y); ink.path.quadTo(x + s / 2f, y + s * 0.6f, x + s, y)
            cv.drawPath(ink.path, stroke)
        }
        if (KoreaMap.shapes.isEmpty()) {
            txt.color = textColor; txt.textSize = 16 * d; cv.drawText("지도를 불러오지 못했어요", w / 2, h / 2, txt); return
        }
        // sea names
        txt.typeface = disp; txt.color = if (night) Color.argb(230, 120, 160, 255) else Color.argb(230, 255, 255, 255); txt.textSize = 15 * d
        cv.drawText("서해", px(125.0), py(34.25), txt); cv.drawText("동해", px(130.65), py(37.2), txt); cv.drawText("남해", px(128.2), py(33.6), txt)
        txt.typeface = Typeface.DEFAULT_BOLD

        // the land as a paper sticker: a soft shadow, a white rim, the colours, the outlines
        fill.color = if (night) Color.argb(80, 10, 18, 50) else Color.argb(72, 70, 90, 130)
        cv.save(); cv.translate(2.5f * d, 4f * d)
        for (p in paths.values) cv.drawPath(p, fill)
        cv.restore()
        // a soft glow around the land (bluer and stronger at night)
        stroke.color = if (night) Color.argb(34, 150, 185, 255) else Color.argb(40, 255, 255, 255); stroke.strokeWidth = 20f * d
        for (p in paths.values) cv.drawPath(p, stroke)
        stroke.color = if (night) Color.argb(70, 175, 205, 255) else Color.argb(80, 255, 255, 255); stroke.strokeWidth = 11f * d
        for (p in paths.values) cv.drawPath(p, stroke)
        stroke.color = rimColor; stroke.strokeWidth = (if (night) 3.4f else 5f) * d
        for (p in paths.values) cv.drawPath(p, stroke)
        bmp?.let { cv.drawBitmap(it, 0f, 0f, bmpPaint) }

        // outlines: thin white around every region, gold around the finished ones, a cocoa line around the chosen one
        stroke.color = lineColor; stroke.strokeWidth = 1.8f * d
        for (p in paths.values) cv.drawPath(p, stroke)
        if (PetHub.started) {
            stroke.color = c("#F2B33D"); stroke.strokeWidth = 2.8f * d
            for (r in Korea.regions) if (PetHub.dex.regionDone(r.id)) paths[r.id]?.let { cv.drawPath(it, stroke) }
        }
        selected?.let { id ->
            paths[id]?.let {
                stroke.color = if (night) Color.argb(205, 200, 215, 255) else Color.argb(205, 255, 255, 255); stroke.strokeWidth = 8f * d; cv.drawPath(it, stroke)
                stroke.color = if (night) c("#FFE08A") else Look.COCOA; stroke.strokeWidth = 2.6f * d; cv.drawPath(it, stroke)
            }
        }
        drawLabels(cv)
    }

    private fun drawLabels(cv: Canvas) {
        chips.clear()
        val here = if (PetHub.started) PetHub.regionId else null
        for (r in Korea.regions) {
            val n = if (PetHub.started) PetHub.dex.count(r.id) else 0
            val done = n == r.items.size
            val lp = labelAt[r.id] ?: KoreaMap.labelPoint(r.id) ?: continue
            val call = callout[r.id]
            val tag = if (done) "★" else "$n/${r.items.size}"
            if (call == null) {
                // the name on the region, with a white halo so it reads on any colour
                val x = px(lp.first); val y = py(lp.second)
                halo(cv, r.name, x, y, disp, 15f * d, textColor)
                halo(cv, tag, x, y + 14f * d, Typeface.DEFAULT_BOLD, 11f * d, if (done) doneColor else subColor)
                rect.set(x - 24 * d, y - 16 * d, x + 24 * d, y + 18 * d); chips += RectF(rect) to r.id
            } else {
                // small cities: a cream chip out at sea, joined to the place by a line
                val ax = px(r.anchors.first().second); val ay = py(r.anchors.first().first)
                val cx = px(call.first); val cy = py(call.second)
                stroke.color = lineColor; stroke.strokeWidth = 1.6f * d
                cv.drawLine(ax, ay, cx, cy, stroke)
                fill.color = rimColor; cv.drawCircle(ax, ay, 3f * d, fill)
                stroke.color = if (night) c("#0E1731") else Look.COCOA; stroke.strokeWidth = 1.2f * d; cv.drawCircle(ax, ay, 3f * d, stroke)
                rect.set(cx - 40 * d, cy - 18 * d, cx + 40 * d, cy + 18 * d)
                cv.save(); cv.translate(0f, 2f * d); fill.color = Color.argb(60, 60, 90, 140); cv.drawRoundRect(rect, 18 * d, 18 * d, fill); cv.restore()
                fill.color = if (night) c("#0F1A55") else c("#FFFCF6"); cv.drawRoundRect(rect, 18 * d, 18 * d, fill)
                stroke.color = hue[r.id] ?: Color.GRAY; stroke.strokeWidth = 2.4f * d; cv.drawRoundRect(rect, 18 * d, 18 * d, stroke)
                drawLandmark(cv, r.id, cx - 24 * d, cy, 17f * d, if (night) 0xFFE6ECFF.toInt() else (hue[r.id] ?: Color.GRAY))
                txt.typeface = disp; txt.color = textColor; txt.textSize = 13.5f * d; cv.drawText(r.name, cx + 11 * d, cy - 1f * d, txt)
                txt.typeface = Typeface.DEFAULT_BOLD; txt.textSize = 10f * d; txt.color = if (done) doneColor else subColor; cv.drawText(tag, cx + 11 * d, cy + 12f * d, txt)
                chips += RectF(rect) to r.id
            }
            if (r.id == here) {
                // where the car is now: a coral pin over the region
                val pinX = if (call == null) px(lp.first) else px(r.anchors.first().second)
                val pinY = (if (call == null) py(lp.second) else py(r.anchors.first().first)) - 14 * d
                Pics.pin(cv, ink, pinX, pinY, 13f * d)
            }
        }
    }

    /** a tiny landmark for each city chip: a tower, an anchor, a building, a ship or a bridge */
    private fun drawLandmark(cv: Canvas, id: String, x: Float, y: Float, s: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = 1.8f * d; fill.color = color
        val h = s / 2f
        when (id) {
            "seoul", "daejeon", "gwangju", "daegu" -> {   // a tower with a round deck
                cv.drawLine(x, y - h, x, y + h, stroke)
                rect.set(x - h * 0.45f, y - h * 0.45f, x + h * 0.45f, y - h * 0.05f); cv.drawOval(rect, fill)
                cv.drawLine(x - h * 0.45f, y + h, x + h * 0.45f, y + h, stroke)
            }
            "incheon" -> {   // an anchor
                cv.drawCircle(x, y - h * 0.65f, h * 0.22f, stroke)
                cv.drawLine(x, y - h * 0.42f, x, y + h * 0.75f, stroke)
                cv.drawLine(x - h * 0.4f, y - h * 0.1f, x + h * 0.4f, y - h * 0.1f, stroke)
                rect.set(x - h * 0.7f, y - h * 0.3f, x + h * 0.7f, y + h * 0.95f); cv.drawArc(rect, 20f, 140f, false, stroke)
            }
            "sejong" -> {   // a building with columns
                cv.drawLine(x - h * 0.75f, y - h * 0.2f, x, y - h * 0.8f, stroke); cv.drawLine(x, y - h * 0.8f, x + h * 0.75f, y - h * 0.2f, stroke)
                for (i in -1..1) cv.drawLine(x + i * h * 0.4f, y - h * 0.05f, x + i * h * 0.4f, y + h * 0.7f, stroke)
                cv.drawLine(x - h * 0.8f, y + h * 0.85f, x + h * 0.8f, y + h * 0.85f, stroke)
            }
            "ulsan" -> {   // a ship
                ink.path.reset(); ink.path.moveTo(x - h * 0.9f, y + h * 0.2f); ink.path.lineTo(x + h * 0.9f, y + h * 0.2f); ink.path.lineTo(x + h * 0.55f, y + h * 0.75f); ink.path.lineTo(x - h * 0.55f, y + h * 0.75f); ink.path.close()
                cv.drawPath(ink.path, fill)
                ink.path.reset(); ink.path.moveTo(x, y - h * 0.85f); ink.path.lineTo(x + h * 0.6f, y + h * 0.05f); ink.path.lineTo(x, y + h * 0.05f); ink.path.close()
                cv.drawPath(ink.path, fill)
            }
            "busan" -> {   // a bridge
                cv.drawLine(x - h * 0.5f, y - h * 0.8f, x - h * 0.5f, y + h * 0.6f, stroke); cv.drawLine(x + h * 0.5f, y - h * 0.8f, x + h * 0.5f, y + h * 0.6f, stroke)
                cv.drawLine(x - h, y + h * 0.4f, x + h, y + h * 0.4f, stroke)
                rect.set(x - h * 0.95f, y - h * 0.9f, x + h * 0.95f, y + h * 0.5f); cv.drawArc(rect, 10f, 160f, false, stroke)
            }
        }
    }

    /** the night sky: a few soft clouds low in the corners and little stars */
    private fun drawSky(cv: Canvas, w: Float, h: Float) {
        fill.color = OPAQUE_BLACK
        for ((cx, cy, r) in arrayOf(Triple(0.04f, 0.9f, 0.16f), Triple(0.14f, 0.97f, 0.15f), Triple(0.24f, 1.02f, 0.14f), Triple(0.88f, 0.92f, 0.16f), Triple(0.97f, 0.98f, 0.18f), Triple(0.78f, 1.03f, 0.14f))) {
            fill.shader = android.graphics.RadialGradient(cx * w, cy * h, r * h, intArrayOf(Color.argb(78, 120, 150, 255), Color.argb(34, 110, 140, 255), Color.argb(0, 100, 130, 255)), floatArrayOf(0f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
            cv.drawCircle(cx * w, cy * h, r * h, fill)
        }
        fill.shader = null
        for ((i, wv) in waves.withIndex()) {
            fill.color = Color.argb(90 + (i % 4) * 40, 255, 246, 216)
            cv.drawCircle(wv[0] * w, wv[1] * h * 0.85f, (0.5f + (wv[2] - 5f) * 0.14f) * d, fill)
        }
    }

    /** text with a white halo (stroke under fill) */
    private fun halo(cv: Canvas, s: String, x: Float, y: Float, face: Typeface, size: Float, color: Int) {
        txt.typeface = face; txt.textSize = size
        txt.style = Paint.Style.STROKE; txt.strokeWidth = 4f * d; txt.strokeJoin = Paint.Join.ROUND; txt.color = haloColor
        cv.drawText(s, x, y, txt)
        txt.style = Paint.Style.FILL; txt.color = color
        cv.drawText(s, x, y, txt)
        txt.typeface = Typeface.DEFAULT_BOLD
    }

    /** the region under (or next to) a touch point, using the pixel map so that tiny cities can be hit */
    private fun regionNear(x: Float, y: Float): String? {
        val reg = region ?: return null
        val w = width; val h = height
        val cx = x.toInt(); val cy = y.toInt()
        var best: String? = null; var bd = Int.MAX_VALUE
        val rad = (16 * d).toInt()
        for (yy in (cy - rad).coerceAtLeast(0)..(cy + rad).coerceAtMost(h - 1)) for (xx in (cx - rad).coerceAtLeast(0)..(cx + rad).coerceAtMost(w - 1)) {
            val r = reg[yy * w + xx]
            if (r < 0) continue
            val dd = (xx - cx) * (xx - cx) + (yy - cy) * (yy - cy)
            if (dd < bd) { bd = dd; best = Korea.regions[r].id }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action != MotionEvent.ACTION_UP) return true
        val hit = chips.firstOrNull { it.first.contains(e.x, e.y) }?.second ?: regionNear(e.x, e.y)
        if (hit != null) { selected = hit; onPick(hit) }
        return true
    }

    private companion object {
        const val LON_MIN = 124.6
        const val LON_MAX = 131.0
        const val LAT_MIN = 32.85   // a little sea under Jeju, so its name and count are not cut off
        const val LAT_MAX = 38.65
        private const val OPAQUE_BLACK = 0xFF000000.toInt()
        const val PAPER = 0xFFEFE8DC.toInt()   // the faded paper a region starts as
        const val PAPER_NIGHT = 0xFF33458F.toInt()   // the same, at night: an indigo, not black
    }
}
