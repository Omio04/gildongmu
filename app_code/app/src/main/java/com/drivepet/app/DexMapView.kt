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

    private fun c(hex: String) = Color.parseColor(hex)

    /** the colour group of each region (like the colour blocks of a sticker map) */
    private val hue: Map<String, Int> = mapOf(
        "seoul" to "#B49BE6", "incheon" to "#B49BE6", "gyeonggi" to "#B49BE6",
        "gangwon" to "#F5B36B",
        "chungbuk" to "#F29A84", "sejong" to "#F29A84", "daejeon" to "#F29A84",
        "chungnam" to "#8EB2EE",
        "jeonbuk" to "#F3CB62",
        "gwangju" to "#7CCDB4", "jeonnam" to "#7CCDB4",
        "gyeongbuk" to "#7CC0E4", "daegu" to "#7CC0E4",
        "gyeongnam" to "#EE9CB5", "busan" to "#EE9CB5", "ulsan" to "#EE9CB5",
        "jeju" to "#95CF8B",
    ).mapValues { c(it.value) }

    /** small cities are named on a chip out at sea, with a line to the place: (chip lon, chip lat) */
    private val callout: Map<String, Pair<Double, Double>> = mapOf(
        "seoul" to (126.35 to 38.25), "incheon" to (125.5 to 37.3), "sejong" to (125.5 to 36.65), "daejeon" to (125.5 to 36.05),
        "gwangju" to (125.5 to 35.3), "daegu" to (130.55 to 36.3), "ulsan" to (130.55 to 35.65), "busan" to (130.45 to 35.05),
    )
    /** names that sit on the region, where the middle of the biggest outline is a bad place */
    private val labelAt: Map<String, Pair<Double, Double>> = mapOf(
        "gyeonggi" to (127.3 to 37.2), "chungnam" to (126.75 to 36.5), "jeonnam" to (126.95 to 34.9), "jeju" to (126.55 to 33.0),
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
        seaBg = Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, w * 0.25f, h.toFloat(),
                intArrayOf(c("#D5E8FC"), c("#B4D5F8"), c("#9CC9F2")), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
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
        val hueC = hue[id] ?: Color.GRAY
        if (!PetHub.started) return mix(hueC, PAPER, 0.62f)
        val n = PetHub.dex.count(id)
        val frac = n / r.items.size.toFloat()
        return mix(hueC, PAPER, 0.62f * (1f - frac) + (if (n == 0) 0.04f else 0f))
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
            if (rightDiff || downDiff) col = mix(col, Color.WHITE, 0.45f)
            val grain = (((x * 73856093) xor (y * 19349663)) and 15) / 3   // a fine paper grain
            out[i] = Color.rgb((Color.red(col) - grain).coerceAtLeast(0), (Color.green(col) - grain).coerceAtLeast(0), (Color.blue(col) - grain).coerceAtLeast(0))
        }
        b.setPixels(out, 0, w, 0, 0, w, h)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        seaBg?.let { cv.drawRect(0f, 0f, w, h, it) }
        // little wave marks on the sea
        stroke.color = Color.argb(140, 255, 255, 255); stroke.strokeWidth = 1.6f * d
        for (wv in waves) {
            val x = wv[0] * w; val y = wv[1] * h; val s = wv[2] * d
            ink.path.reset(); ink.path.moveTo(x - s, y); ink.path.quadTo(x - s / 2f, y - s * 0.6f, x, y); ink.path.quadTo(x + s / 2f, y + s * 0.6f, x + s, y)
            cv.drawPath(ink.path, stroke)
        }
        if (KoreaMap.shapes.isEmpty()) {
            txt.color = Look.INK; txt.textSize = 16 * d; cv.drawText("지도를 불러오지 못했어요", w / 2, h / 2, txt); return
        }
        // sea names
        txt.typeface = disp; txt.color = Color.argb(230, 255, 255, 255); txt.textSize = 15 * d
        cv.drawText("서해", px(125.0), py(34.25), txt); cv.drawText("동해", px(130.65), py(37.2), txt); cv.drawText("남해", px(128.2), py(33.6), txt)
        txt.typeface = Typeface.DEFAULT_BOLD

        // the land as a paper sticker: a soft shadow, a white rim, the colours, the outlines
        fill.color = Color.argb(72, 70, 90, 130)
        cv.save(); cv.translate(2.5f * d, 4f * d)
        for (p in paths.values) cv.drawPath(p, fill)
        cv.restore()
        stroke.color = Color.WHITE; stroke.strokeWidth = 5f * d
        for (p in paths.values) cv.drawPath(p, stroke)
        bmp?.let { cv.drawBitmap(it, 0f, 0f, bmpPaint) }

        // outlines: thin white around every region, gold around the finished ones, a cocoa line around the chosen one
        stroke.color = Color.argb(242, 255, 255, 255); stroke.strokeWidth = 1.8f * d
        for (p in paths.values) cv.drawPath(p, stroke)
        if (PetHub.started) {
            stroke.color = c("#F2B33D"); stroke.strokeWidth = 2.8f * d
            for (r in Korea.regions) if (PetHub.dex.regionDone(r.id)) paths[r.id]?.let { cv.drawPath(it, stroke) }
        }
        selected?.let { id ->
            paths[id]?.let {
                stroke.color = Color.argb(205, 255, 255, 255); stroke.strokeWidth = 8f * d; cv.drawPath(it, stroke)
                stroke.color = Look.COCOA; stroke.strokeWidth = 2.6f * d; cv.drawPath(it, stroke)
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
                halo(cv, r.name, x, y, disp, 15f * d, Look.INK)
                halo(cv, tag, x, y + 14f * d, Typeface.DEFAULT_BOLD, 11f * d, if (done) c("#B07A00") else Look.SUB)
                rect.set(x - 24 * d, y - 16 * d, x + 24 * d, y + 18 * d); chips += RectF(rect) to r.id
            } else {
                // small cities: a cream chip out at sea, joined to the place by a line
                val ax = px(r.anchors.first().second); val ay = py(r.anchors.first().first)
                val cx = px(call.first); val cy = py(call.second)
                stroke.color = Color.argb(242, 255, 255, 255); stroke.strokeWidth = 1.6f * d
                cv.drawLine(ax, ay, cx, cy, stroke)
                fill.color = Color.WHITE; cv.drawCircle(ax, ay, 3f * d, fill)
                stroke.color = Look.COCOA; stroke.strokeWidth = 1.2f * d; cv.drawCircle(ax, ay, 3f * d, stroke)
                rect.set(cx - 25 * d, cy - 17 * d, cx + 25 * d, cy + 17 * d)
                cv.save(); cv.translate(0f, 2f * d); fill.color = Color.argb(60, 60, 90, 140); cv.drawRoundRect(rect, 12 * d, 12 * d, fill); cv.restore()
                fill.color = c("#FFFCF6"); cv.drawRoundRect(rect, 12 * d, 12 * d, fill)
                stroke.color = hue[r.id] ?: Color.GRAY; stroke.strokeWidth = 2.4f * d; cv.drawRoundRect(rect, 12 * d, 12 * d, stroke)
                txt.typeface = disp; txt.color = Look.INK; txt.textSize = 13.5f * d; cv.drawText(r.name, cx, cy - 1f * d, txt)
                txt.typeface = Typeface.DEFAULT_BOLD; txt.textSize = 10f * d; txt.color = if (done) c("#B07A00") else Look.SUB; cv.drawText(tag, cx, cy + 12f * d, txt)
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

    /** text with a white halo (stroke under fill) */
    private fun halo(cv: Canvas, s: String, x: Float, y: Float, face: Typeface, size: Float, color: Int) {
        txt.typeface = face; txt.textSize = size
        txt.style = Paint.Style.STROKE; txt.strokeWidth = 4f * d; txt.strokeJoin = Paint.Join.ROUND; txt.color = Color.argb(242, 255, 255, 255)
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
        const val LAT_MIN = 33.0
        const val LAT_MAX = 38.65
        const val PAPER = 0xFFE6DED2.toInt()   // the faded paper a region starts as
    }
}
