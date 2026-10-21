package com.gildongmu.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Top-down road with our car and the neighbours around it.
 * Only relative lane/distance is drawn - no coordinates are ever shown.
 * The lane marks and the LIVE mark are small squares, a nod to Hyundai's pixel lamp motif.
 * In "pick" mode a tap on a car chooses it as the one car to ask.
 */
class RoadView(context: Context, neighbors: List<Neighbor>, private val theme: Theme, regular: Typeface, bold: Typeface) : View(context) {

    var neighbors: List<Neighbor> = neighbors
        set(v) { field = v; invalidate() }

    /** Tapping a car picks it (only while [pickEnabled]). */
    var onPick: ((Neighbor) -> Unit)? = null
    var pickEnabled = false
        set(v) { field = v; invalidate() }
    var selectedId: String? = null
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.roadBg }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.roadEdge; strokeWidth = 3 * d }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.roadDash }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33000000") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * d; color = theme.accent }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.surface }
    private val chipSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accent }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * d; typeface = bold; color = theme.ink
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; typeface = regular; color = theme.muted }
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5A524") }
    private val liveP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * d; typeface = bold; color = theme.parked
    }

    // lane (0..2) and vertical position (0 = far ahead, 1 = behind us), in the order of the neighbour list
    private val slots = listOf(0 to 0.50f, 2 to 0.22f, 2 to 0.64f, 1 to 0.10f, 0 to 0.80f, 1 to 0.40f)
    private var scroll = 0f
    private val hits = mutableListOf<Pair<RectF, Neighbor>>()

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { scroll = it.animatedValue as Float; invalidate() }
    }

    var moving: Boolean = false
        set(v) {
            field = v
            if (v) { if (!anim.isStarted) anim.start() } else { anim.cancel(); scroll = 0f; invalidate() }
        }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), 28 * d, 28 * d, Path.Direction.CW) }
        c.save(); c.clipPath(clip)
        c.drawRect(0f, 0f, w, h, road)

        val roadW = w * 0.86f
        val left = (w - roadW) / 2f
        val laneW = roadW / 3f
        c.drawLine(left, 0f, left, h, edge)
        c.drawLine(left + roadW, 0f, left + roadW, h, edge)
        // lane marks: columns of small squares that scroll while the car moves
        val period = 64 * d
        val sq = 5 * d
        for (i in 1..2) {
            val x = left + laneW * i
            var y = -period + scroll * period
            while (y < h) {
                for (k in 0 until 4) {
                    val top = y + k * (sq + 3.5f * d)
                    c.drawRect(x - sq / 2, top, x + sq / 2, top + sq, dash)
                }
                y += period
            }
        }

        fun laneX(l: Int) = left + laneW * (l + 0.5f)
        val cw = laneW * 0.52f; val ch = cw * 1.9f

        hits.clear()
        neighbors.take(slots.size).forEachIndexed { i, n ->
            val (lane, yf) = slots[i]
            val cx = laneX(lane); val cy = h * yf
            body.color = if (n.sameDest) theme.carSame else theme.carOther
            car(c, cx, cy, cw, ch)
            val selected = n.id == selectedId
            if (selected) {
                c.drawRoundRect(RectF(cx - cw / 2 - 5 * d, cy - ch / 2 - 5 * d, cx + cw / 2 + 5 * d, cy + ch / 2 + 5 * d), cw * 0.38f, cw * 0.38f, ring)
            }
            tag(c, cx, cy - ch / 2f - 6 * d, n, selected)
            hits += RectF(cx - cw / 2 - 10 * d, cy - ch / 2 - 44 * d, cx + cw / 2 + 10 * d, cy + ch / 2 + 10 * d) to n
        }
        body.color = theme.carMe
        car(c, laneX(1), h * 0.80f, cw, ch)
        c.restore()
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

    private fun car(c: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val r = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawRoundRect(r, w * 0.3f, w * 0.3f, body)
        c.drawRoundRect(RectF(r.left + w * 0.12f, r.top + h * 0.18f, r.right - w * 0.12f, r.top + h * 0.38f), 6 * d, 6 * d, glass)
        c.drawRoundRect(RectF(r.left + w * 0.12f, r.bottom - h * 0.26f, r.right - w * 0.12f, r.bottom - h * 0.12f), 6 * d, 6 * d, glass)
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

    private fun tag(c: Canvas, cx: Float, bottom: Float, n: Neighbor, selected: Boolean) {
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
        c.drawRoundRect(r, 10 * d, 10 * d, if (selected) chipSelected else chip)
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
    }
}
