package com.gildongmu.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * Top-down road with our car and the neighbours around it.
 * Only relative lane/distance is drawn - no coordinates are ever shown.
 * The lane marks and the LIVE mark are small squares, a nod to Hyundai's pixel lamp motif.
 * Only the lane marks scroll, at a pace that follows the real speed (still when parked, a slow crawl in a jam),
 * so the road feels alive without anything else moving. The animation stops when the view is hidden or the system turns animations off.
 * A tap on a car picks it (when [pickEnabled]); a car can wear a speech bubble with one of the fixed feelings.
 */
class RoadView(
    context: Context,
    neighbors: List<Neighbor>,
    private val theme: Theme,
    regular: Typeface,
    bold: Typeface,
) : View(context) {

    var neighbors: List<Neighbor> = neighbors
        set(v) { field = v; dirty() }

    /** The bubble over our own car. */
    var myMood: String? = null
        set(v) { if (field != v) { field = v; dirty() } }

    /** Tapping a car picks it (only while [pickEnabled]). */
    var onPick: ((Neighbor) -> Unit)? = null
    var pickEnabled = false
        set(v) { field = v; invalidate() }
    var selectedId: String? = null
        set(v) { field = v; dirty() }

    private val d = resources.displayMetrics.density
    private val road = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.roadEdge; strokeWidth = 2.5f * d; alpha = 190 }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.roadDash }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#55000000") }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#44000000") }
    private val lampFront = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF3C4") }
    private val lampRear = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF5A4F") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * d; color = theme.accent }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.surface }
    private val chipSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accent }
    private val chipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = theme.border }
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.surface }
    private val bubbleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = theme.accent }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * d; typeface = bold; color = theme.ink }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * d; typeface = bold; color = theme.ink }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; typeface = regular; color = theme.muted }
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5A524") }
    private val liveP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; typeface = bold; color = theme.parked }

    // lane (0..2) and vertical position (0 = far ahead, 1 = behind us), in the order of the neighbour list
    private val slots = listOf(0 to 0.54f, 2 to 0.36f, 2 to 0.68f, 1 to 0.30f, 0 to 0.80f, 1 to 0.55f)   // clear of the chips on top
    private val hits = mutableListOf<Pair<RectF, Neighbor>>()

    /** Kept so the screen can tell the road about the driving state. */
    var moving: Boolean = false

    /** Vehicle speed; the lane marks scroll in proportion (0 = standing still). */
    var speedKmh: Float = 0f
        set(v) { field = v; if (v > 0f) { lastFrame = 0L; invalidate() } }

    private var scroll = 0f
    private var lastFrame = 0L
    private val animationsOn: Boolean
        get() = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    // The road is drawn in two cached pieces so a frame costs almost nothing on an old head unit:
    // the background (gradient, clip) is made once per size, and the cars/tags/bubbles are painted into a
    // bitmap that is only redone when something about them changes. Per frame, only the lane marks move.
    private var clip: Path? = null
    private var fg: Bitmap? = null
    private var fgDirty = true

    private fun dirty() { fgDirty = true; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        clip = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 28 * d, 28 * d, Path.Direction.CW) }
        road.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), theme.roadTop, theme.roadBottom, Shader.TileMode.CLAMP)
        fg?.recycle(); fg = null; fgDirty = true
    }

    override fun onDetachedFromWindow() { fg?.recycle(); fg = null; fgDirty = true; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        c.save(); c.clipPath(clip ?: return)
        c.drawRect(0f, 0f, w, h, road)

        val roadW = w * 0.86f
        val left = (w - roadW) / 2f
        val laneW = roadW / 3f
        c.drawLine(left, 0f, left, h, edge)
        c.drawLine(left + roadW, 0f, left + roadW, h, edge)
        // lane marks: columns of small squares
        val period = 64 * d
        val sq = 5 * d
        val now = android.os.SystemClock.uptimeMillis()
        val running = speedKmh > 0f && animationsOn && isShown
        if (running) {
            if (lastFrame != 0L) scroll = (scroll + (now - lastFrame) / 1000f * (14f + speedKmh.coerceAtMost(80f) * 1.4f) * d) % period
            lastFrame = now
        } else lastFrame = 0L
        for (i in 1..2) {
            val x = left + laneW * i
            var y = -period + scroll
            while (y < h) {
                for (k in 0 until 4) {
                    val top = y + k * (sq + 3.5f * d)
                    c.drawRect(x - sq / 2, top, x + sq / 2, top + sq, dash)
                }
                y += period
            }
        }

        c.drawBitmap(foreground(w.toInt(), h.toInt(), left, laneW), 0f, 0f, null)
        c.restore()
        if (running) postInvalidateDelayed(33)   // about 30 frames a second is plenty for slow lane marks
    }

    /** Cars, name tags and bubbles on a transparent layer, redone only when [fgDirty]. */
    private fun foreground(wi: Int, hi: Int, left: Float, laneW: Float): Bitmap {
        fg?.let { if (!fgDirty) return it }
        val bmp = fg ?: Bitmap.createBitmap(wi, hi, Bitmap.Config.ARGB_8888).also { fg = it }
        bmp.eraseColor(Color.TRANSPARENT)
        drawCars(Canvas(bmp), hi.toFloat(), left, laneW)
        fgDirty = false
        return bmp
    }

    private fun drawCars(c: Canvas, h: Float, left: Float, laneW: Float) {
        fun laneX(l: Int) = left + laneW * (l + 0.5f)
        val cw = laneW * 0.52f; val ch = cw * 1.9f

        hits.clear()
        val bubbles = mutableListOf<Triple<Float, Pair<Float, Float>, String>>()   // x, (bottom, below), text
        neighbors.take(slots.size).forEachIndexed { i, n ->
            val (lane, yf) = slots[i]
            val cx = laneX(lane); val cy = h * yf
            car(c, cx, cy, cw, ch, if (n.sameDest) theme.carSame else theme.carOther)
            val selected = n.id == selectedId
            if (selected) {
                c.drawRoundRect(RectF(cx - cw / 2 - 5 * d, cy - ch / 2 - 5 * d, cx + cw / 2 + 5 * d, cy + ch / 2 + 5 * d), cw * 0.38f, cw * 0.38f, ring)
            }
            val tagTop = tag(c, cx, cy - ch / 2f - 6 * d, n, selected)
            val mood = n.mood
            if (mood != null && bubbles.size < 2) {   // two at a time, so the road stays readable
                bubbles += Triple(cx, (tagTop - 4 * d) to (cy + ch / 2f + 6 * d), mood)
            }
            hits += RectF(cx - cw / 2 - 10 * d, cy - ch / 2 - 44 * d, cx + cw / 2 + 10 * d, cy + ch / 2 + 10 * d) to n
        }
        // our car: a soft glow underneath
        val mx = laneX(1); val my = h * 0.80f
        glowP.shader = RadialGradient(mx, my, ch * 0.95f, theme.glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, ch * 0.95f, glowP)
        car(c, mx, my, cw, ch, theme.carMe)
        // speech bubbles last, so no name tag covers them
        bubbles.forEach { (x, ys, text) -> bubble(c, x, ys.first, ys.second, text) }
        myMood?.let { bubble(c, mx, my - ch / 2f - 8 * d, my + ch / 2f + 6 * d, it) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!pickEnabled) return false
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP) {
            hits.firstOrNull { it.first.contains(e.x, e.y) }?.let { onPick?.invoke(it.second); performClick() }
            return true
        }
        return false
    }

    override fun performClick(): Boolean = super.performClick()

    /** A speech bubble above [bottom] (or below the car, at [below], when there is no room above). */
    private fun bubble(c: Canvas, cx: Float, bottom: Float, below: Float, text: String) {
        val padX = 10 * d
        val hB = 26 * d
        val tail = 6 * d
        val wB = bubbleText.measureText(text) + padX * 2
        val roomAbove = bottom - tail - hB > 52 * d           // keep clear of the chips on top
        val top = if (roomAbove) bottom - tail - hB else below + tail
        val r = RectF(cx - wB / 2, top, cx + wB / 2, top + hB)
        val shift = when { r.left < 6 * d -> 6 * d - r.left; r.right > width - 6 * d -> width - 6 * d - r.right; else -> 0f }
        r.offset(shift, 0f)
        val tailX = cx.coerceIn(r.left + 10 * d, r.right - 10 * d)
        val path = Path().apply {
            addRoundRect(r, 13 * d, 13 * d, Path.Direction.CW)
            if (roomAbove) { moveTo(tailX - 5 * d, r.bottom - 1); lineTo(tailX, r.bottom + tail); lineTo(tailX + 5 * d, r.bottom - 1) }
            else { moveTo(tailX - 5 * d, r.top + 1); lineTo(tailX, r.top - tail); lineTo(tailX + 5 * d, r.top + 1) }
        }
        c.drawPath(path, bubbleFill)
        c.drawPath(path, bubbleEdge)
        c.drawText(text, r.left + padX, r.top + hB / 2 + 4.3f * d, bubbleText)
    }

    /** A car with a little depth: a shadow, a lighter roof, windscreens and lamps. */
    private fun car(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, color: Int) {
        val r = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawRoundRect(RectF(r.left + 2 * d, r.top + 5 * d, r.right + 2 * d, r.bottom + 5 * d), w * 0.3f, w * 0.3f, shade)
        body.shader = LinearGradient(r.left, r.top, r.right, r.bottom, lighten(color, 0.18f), color, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, w * 0.3f, w * 0.3f, body)
        body.shader = null
        c.drawRoundRect(RectF(r.left + w * 0.12f, r.top + h * 0.18f, r.right - w * 0.12f, r.top + h * 0.38f), 6 * d, 6 * d, glass)
        c.drawRoundRect(RectF(r.left + w * 0.12f, r.bottom - h * 0.26f, r.right - w * 0.12f, r.bottom - h * 0.12f), 6 * d, 6 * d, glass)
        val lamp = 2.2f * d
        c.drawRoundRect(RectF(r.left + w * 0.10f, r.top + 1.5f * d, r.left + w * 0.32f, r.top + 1.5f * d + lamp), lamp, lamp, lampFront)
        c.drawRoundRect(RectF(r.right - w * 0.32f, r.top + 1.5f * d, r.right - w * 0.10f, r.top + 1.5f * d + lamp), lamp, lamp, lampFront)
        c.drawRoundRect(RectF(r.left + w * 0.10f, r.bottom - 1.5f * d - lamp, r.left + w * 0.32f, r.bottom - 1.5f * d), lamp, lamp, lampRear)
        c.drawRoundRect(RectF(r.right - w * 0.32f, r.bottom - 1.5f * d - lamp, r.right - w * 0.10f, r.bottom - 1.5f * d), lamp, lamp, lampRear)
    }

    private fun lighten(color: Int, f: Float): Int {
        fun up(v: Int) = (v + (255 - v) * f).toInt().coerceIn(0, 255)
        return Color.rgb(up(Color.red(color)), up(Color.green(color)), up(Color.blue(color)))
    }

    private fun drawStar(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Path()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.45f
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = cx + (rad * Math.cos(a)).toFloat()
            val y = cy + (rad * Math.sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        c.drawPath(p, star)
    }

    /** Draws the name tag above a car and returns where its top edge is. */
    private fun tag(c: Canvas, cx: Float, bottom: Float, n: Neighbor, selected: Boolean): Float {
        val t1 = n.nickname
        val t2 = "${n.distanceM}m"
        val pad = 8 * d
        val star = if (n.sameDest) 16 * d else 0f
        val liveW = if (n.live) 46 * d else 0f
        val wTxt = maxOf(label.measureText(t1) + star, sub.measureText(t2) + liveW) + pad * 2
        val hTag = 36 * d
        val r = RectF(cx - wTxt / 2, bottom - hTag, cx + wTxt / 2, bottom)
        // keep the tag inside the road card
        val shift = when { r.left < 6 * d -> 6 * d - r.left; r.right > width - 6 * d -> width - 6 * d - r.right; else -> 0f }
        r.offset(shift, 0f)
        c.drawRoundRect(r, 11 * d, 11 * d, if (selected) chipSelected else chip)
        if (!selected) c.drawRoundRect(r, 11 * d, 11 * d, chipBorder)
        val fg = if (selected) Color.WHITE else null
        label.color = fg ?: theme.ink
        sub.color = fg ?: theme.muted
        var x = r.left + pad
        if (n.sameDest) { drawStar(c, x + 5.5f * d, r.top + 11.5f * d, 6.5f * d); x += star }
        c.drawText(t1, x, r.top + 16 * d, label)
        c.drawText(t2, r.left + pad, r.top + 31 * d, sub)
        if (n.live) {  // a real person on a phone, not a simulated car: a small green square + LIVE
            val lx = r.left + pad + sub.measureText(t2) + 10 * d
            liveP.color = if (selected) Color.WHITE else theme.parked
            c.drawRect(lx - 3 * d, r.top + 24.5f * d, lx + 3 * d, r.top + 30.5f * d, liveP)
            c.drawText("LIVE", lx + 7 * d, r.top + 31 * d, liveP)
        }
        return r.top
    }
}
