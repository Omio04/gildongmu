package com.gildongmu.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Top-down road with our car and the neighbours around it.
 * Only relative lane/distance is drawn - no coordinates are ever shown.
 */
class RoadView(context: Context, neighbors: List<Neighbor>) : View(context) {

    var neighbors: List<Neighbor> = neighbors
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A3342") }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6EAF0"); strokeWidth = 3 * d }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A94A6"); strokeWidth = 3 * d }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33000000") }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * d; typeface = Typeface.DEFAULT_BOLD; color = Color.parseColor("#101828")
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; color = Color.parseColor("#667085") }
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F79009") }

    // lane (0..2) and vertical position (0 = far ahead, 1 = behind us), in the order of DemoData.neighbors
    private val slots = listOf(0 to 0.50f, 2 to 0.22f, 2 to 0.64f, 1 to 0.10f)
    private var scroll = 0f

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
        val period = 56 * d
        for (i in 1..2) {
            val x = left + laneW * i
            var y = -period + scroll * period
            while (y < h) { c.drawLine(x, y, x, y + period * 0.5f, dash); y += period }
        }

        fun laneX(l: Int) = left + laneW * (l + 0.5f)
        val cw = laneW * 0.52f; val ch = cw * 1.9f

        neighbors.forEachIndexed { i, n ->
            val (lane, yf) = slots.getOrElse(i) { 1 to 0.5f }
            body.color = if (n.sameDest) Color.parseColor("#1E6BFF") else Color.parseColor("#C9D1DC")
            car(c, laneX(lane), h * yf, cw, ch)
            tag(c, laneX(lane), h * yf - ch / 2f - 6 * d, n)
        }
        body.color = Color.WHITE
        car(c, laneX(1), h * 0.80f, cw, ch)
        c.restore()
    }

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

    private fun tag(c: Canvas, cx: Float, bottom: Float, n: Neighbor) {
        val t1 = n.nickname
        val t2 = "${n.distanceM}m"
        val pad = 8 * d
        val star = if (n.sameDest) 16 * d else 0f
        val wTxt = maxOf(label.measureText(t1) + star, sub.measureText(t2)) + pad * 2
        val hTag = 36 * d
        val r = RectF(cx - wTxt / 2, bottom - hTag, cx + wTxt / 2, bottom)
        c.drawRoundRect(r, 10 * d, 10 * d, chip)
        var x = r.left + pad
        if (n.sameDest) { drawStar(c, x + 5.5f * d, r.top + 11.5f * d, 6.5f * d); x += star }
        c.drawText(t1, x, r.top + 16 * d, label)
        c.drawText(t2, r.left + pad, r.top + 31 * d, sub)
    }
}
