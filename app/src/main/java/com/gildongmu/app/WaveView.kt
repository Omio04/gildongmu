package com.gildongmu.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/** A few bars that move like a voice level while we are listening, and rest as a quiet line otherwise. */
class WaveView(context: Context, color: Int) : View(context) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    var active = false
        set(v) {
            field = v
            if (v) { if (!anim.isStarted) anim.start() } else { anim.cancel(); phase = 0f; invalidate() }
        }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val n = 7
        val gap = 5 * d
        val barW = (width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val level = if (active) 0.25f + 0.75f * abs(sin(phase + i * 0.9f)) else 0.18f + 0.12f * (if (i % 2 == 0) 1f else 0.5f)
            val h = height * level
            val x = i * (barW + gap)
            c.drawRoundRect(RectF(x, (height - h) / 2, x + barW, (height + h) / 2), barW / 2, barW / 2, p)
        }
    }
}
