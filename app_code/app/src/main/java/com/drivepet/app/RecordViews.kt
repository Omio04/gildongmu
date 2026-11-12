package com.drivepet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The pictures of the driving record: the score colours, the badge medals, the month calendar, the score ring and the
 * pet's speech bubble. Drawn in the app's look (cocoa outlines, light from the top left, pastel fills).
 */
object Bands {
    /**
     * a score colour: the chip's top and bottom, its edge, the ink on it, a solid tone for rings and bars, and a bright
     * one for numbers on the dark (night) cards
     */
    class Tone(val top: Int, val bottom: Int, val edge: Int, val ink: Int, val solid: Int, private val bright: Int) {
        /** the colour of a number in this band on a card: deep on cream by day, bright on deep blue at night */
        fun text(): Int = if (Theme.night) bright else ink
    }

    val GOOD = Tone(0xFFD4F0CC.toInt(), 0xFFB3E1A8.toInt(), 0xFF7CC074.toInt(), 0xFF2B6E35.toInt(), 0xFF5DAE65.toInt(), 0xFF9BE39A.toInt())
    val FAIR = Tone(0xFFFFEEB8.toInt(), 0xFFFDDB84.toInt(), 0xFFE3B44A.toInt(), 0xFF875809.toInt(), 0xFFF2B33D.toInt(), 0xFFFFD877.toInt())
    val LOW = Tone(0xFFFFDDCA.toInt(), 0xFFFDBF9E.toInt(), 0xFFEE9466.toInt(), 0xFFA5441D.toInt(), 0xFFEE7F4E.toInt(), 0xFFFFA783.toInt())
    /** a driven day without a turn: no score */
    val NONE = Tone(0xFFF7F0E6.toInt(), 0xFFEEE4D6.toInt(), 0xFFE0D1BD.toInt(), Look.SUB, 0xFFC9B8A6.toInt(), 0xFFB8C2E6.toInt())

    fun of(b: ScoreBand): Tone = when (b) { ScoreBand.GOOD -> GOOD; ScoreBand.FAIR -> FAIR; ScoreBand.LOW -> LOW }
    fun of(score: Int?): Tone = if (score == null) NONE else of(ScoreBand.of(score))

    /** a word for how the day went */
    fun word(score: Int?): String = when {
        score == null -> "점수 없음"
        score >= DriveLog.GOOD_SCORE -> "아주 좋아요"
        score >= 70 -> "좋아요"
        else -> "아쉬워요"
    }
}

/** the round medals of the badges: a coloured disc with a picture and two ribbons; a badge not earned yet is dark and locked */
object Medals {
    private class Disc(val light: Int, val base: Int, val shade: Int, val ribbon: Int, val ribbonDeep: Int)

    private val discs = mapOf(
        Medal.FIRST to Disc(0xFFFFF1BC.toInt(), 0xFFF9CD5A.toInt(), 0xFFDDA02F.toInt(), 0xFF8FAAF0.toInt(), 0xFF5F80DC.toInt()),
        Medal.STREAK3 to Disc(0xFFDDEBFF.toInt(), 0xFFA6C8F8.toInt(), 0xFF739FE6.toInt(), 0xFFF7A58A.toInt(), 0xFFE07A57.toInt()),
        Medal.STREAK7 to Disc(0xFFE8DFFF.toInt(), 0xFFB9A7F2.toInt(), 0xFF8B75D9.toInt(), 0xFFF9D27E.toInt(), 0xFFE0A42F.toInt()),
        Medal.MONTH to Disc(0xFFDFF4D8.toInt(), 0xFFA9DCA0.toInt(), 0xFF72B96D.toInt(), 0xFFF7A58A.toInt(), 0xFFE07A57.toInt()),
        Medal.SIGNAL to Disc(0xFFD8F4ED.toInt(), 0xFF95D8CA.toInt(), 0xFF5DB3A4.toInt(), 0xFF8FAAF0.toInt(), 0xFF5F80DC.toInt()),
        Medal.ROAD to Disc(0xFFFFE2D4.toInt(), 0xFFF8AF90.toInt(), 0xFFE07C5B.toInt(), 0xFF9ED49A.toInt(), 0xFF5DAE65.toInt()),
    )
    private val LOCKED = Disc(0xFF7A8193.toInt(), 0xFF585E6E.toInt(), 0xFF3E4351.toInt(), 0xFF555B69.toInt(), 0xFF3B404C.toInt())
    private const val SIL = 0xFF8C93A6.toInt()        // the picture on a locked medal: one flat grey
    private const val SIL_DEEP = 0xFF6E7588.toInt()
    private const val LOCK_LINE = 0xFF2A2E37.toInt()

    /** the whole medal, the disc centred on (cx, cy) with radius r; the ribbons hang below it down to about cy + 1.25r */
    fun draw(cv: Canvas, ink: Ink, medal: Medal, earned: Boolean, cx: Float, cy: Float, r: Float, face: Typeface) {
        val c = if (earned) discs.getValue(medal) else LOCKED
        val ow = max(1.2f, r * 0.07f)
        val line = if (earned) Look.COCOA else LOCK_LINE

        // the two ribbons behind the disc
        for (sg in intArrayOf(-1, 1)) {
            val p = ink.path; p.reset()
            p.moveTo(cx + sg * r * 0.56f, cy + r * 0.3f)
            p.lineTo(cx + sg * r * 0.1f, cy + r * 0.52f)
            p.lineTo(cx + sg * r * 0.22f, cy + r * 1.26f)
            p.lineTo(cx + sg * r * 0.42f, cy + r * 1.08f)
            p.lineTo(cx + sg * r * 0.64f, cy + r * 1.2f)
            p.close()
            ink.fillPath(cv, p, LinearGradient(cx, cy + r * 0.3f, cx, cy + r * 1.26f, c.ribbon, c.ribbonDeep, Shader.TileMode.CLAMP))
            ink.stroke(cv, p, line, ow * 0.85f)
        }

        // the disc: a soft shadow, the light from the top left, the outline, a dotted inner ring
        ink.circle(cv, cx, cy + r * 0.07f, r, 0x2E7A5434)
        ink.fill.color = OPAQUE
        ink.fill.shader = RadialGradient(cx - r * 0.32f, cy - r * 0.36f, r * 1.45f, intArrayOf(c.light, c.base, c.shade), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, ink.fill); ink.fill.shader = null
        ink.circleOutline(cv, cx, cy, r, line, ow)
        ink.circleOutline(cv, cx, cy, r * 0.8f, if (earned) 0x8CFFFFFF.toInt() else 0x33FFFFFF, r * 0.045f)
        val dot = if (earned) 0xB3FFFFFF.toInt() else 0x40FFFFFF
        for (i in 0 until 20) {
            val a = (i * 2 * PI / 20).toFloat()
            ink.circle(cv, cx + cos(a) * r * 0.9f, cy + sin(a) * r * 0.9f, r * 0.026f, dot)
        }

        when (medal) {
            Medal.FIRST -> car(cv, ink, cx, cy, r, earned, ow)
            Medal.STREAK3 -> flame(cv, ink, cx, cy, r, earned, ow, "3", face)
            Medal.STREAK7 -> flame(cv, ink, cx, cy, r, earned, ow, "7", face)
            Medal.MONTH -> calendar(cv, ink, cx, cy, r, earned, ow)
            Medal.SIGNAL -> blinker(cv, ink, cx, cy, r, earned, ow)
            Medal.ROAD -> road(cv, ink, cx, cy, r, earned, ow, face)
        }

        if (earned) {
            // a little shine on the rim, top left
            ink.line.color = 0x99FFFFFF.toInt(); ink.line.strokeWidth = r * 0.07f
            ink.rect.set(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f)
            cv.drawArc(ink.rect, 200f, 46f, false, ink.line)
        } else {
            lock(cv, ink, cx + r * 0.66f, cy + r * 0.62f, r * 0.3f)
        }
    }

    private fun lock(cv: Canvas, ink: Ink, x: Float, y: Float, s: Float) {
        ink.circle(cv, x, y, s, 0xFF343946.toInt()); ink.circleOutline(cv, x, y, s, 0xFF1F222A.toInt(), max(1f, s * 0.12f))
        ink.line.color = 0xFFDCE0E9.toInt(); ink.line.strokeWidth = s * 0.16f
        ink.rect.set(x - s * 0.32f, y - s * 0.6f, x + s * 0.32f, y + s * 0.05f)
        cv.drawArc(ink.rect, 180f, 180f, false, ink.line)
        ink.rect.set(x - s * 0.48f, y - s * 0.22f, x + s * 0.48f, y + s * 0.5f)
        ink.fill.shader = null; ink.fill.color = 0xFFDCE0E9.toInt(); cv.drawRoundRect(ink.rect, s * 0.14f, s * 0.14f, ink.fill)
        ink.circle(cv, x, y + s * 0.12f, s * 0.1f, 0xFF343946.toInt())
    }

    /** the first drive: the icon's periwinkle car with a sparkle */
    private fun car(cv: Canvas, ink: Ink, cx: Float, cy: Float, r: Float, e: Boolean, ow: Float) {
        val line = if (e) Look.NAVY_LINE else LOCK_LINE
        ink.rect.set(cx - r * 0.3f, cy - r * 0.32f, cx + r * 0.2f, cy + r * 0.02f)
        ink.fill.shader = null; ink.fill.color = if (e) 0xFFCADAFA.toInt() else SIL; cv.drawRoundRect(ink.rect, r * 0.13f, r * 0.13f, ink.fill)
        ink.line.color = line; ink.line.strokeWidth = ow * 0.9f; cv.drawRoundRect(ink.rect, r * 0.13f, r * 0.13f, ink.line)
        ink.rect.set(cx - r * 0.52f, cy - r * 0.08f, cx + r * 0.52f, cy + r * 0.26f)
        if (e) { ink.fill.color = OPAQUE; ink.fill.shader = LinearGradient(0f, cy - r * 0.08f, 0f, cy + r * 0.26f, Look.PERI_TOP, Look.PERI_BOT, Shader.TileMode.CLAMP) } else ink.fill.color = SIL
        cv.drawRoundRect(ink.rect, r * 0.14f, r * 0.14f, ink.fill); ink.fill.shader = null
        cv.drawRoundRect(ink.rect, r * 0.14f, r * 0.14f, ink.line)
        for (sg in intArrayOf(-1, 1)) {
            ink.circle(cv, cx + sg * r * 0.28f, cy + r * 0.27f, r * 0.13f, if (e) 0xFF4A4245.toInt() else SIL_DEEP)
            ink.circleOutline(cv, cx + sg * r * 0.28f, cy + r * 0.27f, r * 0.13f, line, ow * 0.8f)
            ink.circle(cv, cx + sg * r * 0.28f, cy + r * 0.27f, r * 0.045f, if (e) 0xFFD9D9DE.toInt() else SIL)
        }
        if (e) {
            ink.circle(cv, cx + r * 0.44f, cy + r * 0.05f, r * 0.05f, 0xFFFFF6D2.toInt())
            Pics.sparkle(cv, ink, cx + r * 0.4f, cy - r * 0.4f, r * 0.15f, 0xFFFFFFFF.toInt())
            Pics.sparkle(cv, ink, cx - r * 0.46f, cy - r * 0.34f, r * 0.08f, 0xFFFFF7D6.toInt())
        }
    }

    /** a run of good days: a flame with the number of days on its light core */
    private fun flame(cv: Canvas, ink: Ink, cx: Float, cy: Float, r: Float, e: Boolean, ow: Float, n: String, face: Typeface) {
        val p = ink.path; p.reset()
        val y0 = cy + r * 0.04f
        p.moveTo(cx, y0 - r * 0.62f)
        p.cubicTo(cx + r * 0.2f, y0 - r * 0.36f, cx + r * 0.5f, y0 - r * 0.2f, cx + r * 0.45f, y0 + r * 0.16f)
        p.cubicTo(cx + r * 0.42f, y0 + r * 0.46f, cx + r * 0.2f, y0 + r * 0.58f, cx, y0 + r * 0.58f)
        p.cubicTo(cx - r * 0.2f, y0 + r * 0.58f, cx - r * 0.42f, y0 + r * 0.46f, cx - r * 0.45f, y0 + r * 0.16f)
        p.cubicTo(cx - r * 0.48f, y0 - r * 0.1f, cx - r * 0.3f, y0 - r * 0.2f, cx - r * 0.22f, y0 - r * 0.42f)
        p.cubicTo(cx - r * 0.1f, y0 - r * 0.3f, cx - r * 0.04f, y0 - r * 0.42f, cx, y0 - r * 0.62f)
        p.close()
        if (e) ink.fillPath(cv, p, LinearGradient(0f, y0 - r * 0.62f, 0f, y0 + r * 0.58f, intArrayOf(0xFFFFD86E.toInt(), 0xFFFFA24D.toInt(), 0xFFF0714E.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP))
        else ink.fillPath(cv, p, SIL)
        ink.stroke(cv, p, if (e) Look.COCOA else LOCK_LINE, ow)
        ink.oval(cv, cx, y0 + r * 0.24f, r * 0.25f, r * 0.27f, if (e) 0xFFFFF0B5.toInt() else SIL_DEEP)
        ink.text.typeface = face; ink.text.textAlign = Paint.Align.CENTER; ink.text.textSize = r * 0.44f
        ink.text.color = if (e) Look.COCOA else LOCK_LINE
        cv.drawText(n, cx, y0 + r * 0.4f, ink.text)
    }

    /** a month: a calendar page with a star */
    private fun calendar(cv: Canvas, ink: Ink, cx: Float, cy: Float, r: Float, e: Boolean, ow: Float) {
        val line = if (e) Look.COCOA else LOCK_LINE
        val l = cx - r * 0.4f; val t = cy - r * 0.34f; val rr = cx + r * 0.4f; val b = cy + r * 0.44f
        ink.rect.set(l, t, rr, b)
        ink.fill.shader = null; ink.fill.color = if (e) 0xFFFFFDF8.toInt() else SIL; cv.drawRoundRect(ink.rect, r * 0.1f, r * 0.1f, ink.fill)
        cv.save(); ink.path2.reset(); ink.path2.addRoundRect(ink.rect, r * 0.1f, r * 0.1f, Path.Direction.CW); cv.clipPath(ink.path2)
        ink.fill.color = if (e) 0xFFF4876F.toInt() else SIL_DEEP; cv.drawRect(l, t, rr, t + r * 0.2f, ink.fill)
        cv.restore()
        ink.line.color = line; ink.line.strokeWidth = ow * 0.8f; cv.drawLine(l, t + r * 0.2f, rr, t + r * 0.2f, ink.line)
        ink.line.strokeWidth = ow; ink.rect.set(l, t, rr, b); cv.drawRoundRect(ink.rect, r * 0.1f, r * 0.1f, ink.line)
        for (sg in intArrayOf(-1, 1)) {
            ink.rect.set(cx + sg * r * 0.18f - r * 0.05f, t - r * 0.1f, cx + sg * r * 0.18f + r * 0.05f, t + r * 0.1f)
            ink.fill.color = if (e) Look.WHITE else SIL; cv.drawRoundRect(ink.rect, r * 0.05f, r * 0.05f, ink.fill)
            ink.line.strokeWidth = ow * 0.75f; cv.drawRoundRect(ink.rect, r * 0.05f, r * 0.05f, ink.line)
        }
        star(ink.path, cx, cy + r * 0.13f, r * 0.24f, r * 0.105f)
        if (e) ink.fillPath(cv, ink.path, LinearGradient(0f, cy - r * 0.1f, 0f, cy + r * 0.36f, 0xFFFFE07A.toInt(), 0xFFF2B33D.toInt(), Shader.TileMode.CLAMP))
        else ink.fillPath(cv, ink.path, SIL_DEEP)
        ink.stroke(cv, ink.path, line, ow * 0.85f)
    }

    /** the turn signal: an amber arrow, blinking */
    private fun blinker(cv: Canvas, ink: Ink, cx: Float, cy: Float, r: Float, e: Boolean, ow: Float) {
        val p = ink.path; p.reset()
        p.moveTo(cx - r * 0.36f, cy)
        p.lineTo(cx + r * 0.06f, cy - r * 0.4f)
        p.lineTo(cx + r * 0.06f, cy - r * 0.16f)
        p.lineTo(cx + r * 0.56f, cy - r * 0.16f)
        p.lineTo(cx + r * 0.56f, cy + r * 0.16f)
        p.lineTo(cx + r * 0.06f, cy + r * 0.16f)
        p.lineTo(cx + r * 0.06f, cy + r * 0.4f)
        p.close()
        if (e) ink.fillPath(cv, p, LinearGradient(0f, cy - r * 0.4f, 0f, cy + r * 0.4f, 0xFFFFE38E.toInt(), 0xFFFFAE3A.toInt(), Shader.TileMode.CLAMP))
        else ink.fillPath(cv, p, SIL)
        ink.stroke(cv, p, if (e) Look.COCOA else LOCK_LINE, ow)
        ink.line.color = if (e) 0xFFE5891E.toInt() else SIL_DEEP; ink.line.strokeWidth = r * 0.075f
        cv.drawLine(cx - r * 0.46f, cy - r * 0.2f, cx - r * 0.6f, cy - r * 0.33f, ink.line)
        cv.drawLine(cx - r * 0.5f, cy, cx - r * 0.68f, cy, ink.line)
        cv.drawLine(cx - r * 0.46f, cy + r * 0.2f, cx - r * 0.6f, cy + r * 0.33f, ink.line)
    }

    /** a stretch of road going into the distance, with a "100" sign */
    private fun road(cv: Canvas, ink: Ink, cx: Float, cy: Float, r: Float, e: Boolean, ow: Float, face: Typeface) {
        val line = if (e) Look.COCOA else LOCK_LINE
        val p = ink.path; p.reset()
        p.moveTo(cx - r * 0.5f, cy + r * 0.52f); p.lineTo(cx + r * 0.5f, cy + r * 0.52f)
        p.lineTo(cx + r * 0.13f, cy - r * 0.22f); p.lineTo(cx - r * 0.13f, cy - r * 0.22f); p.close()
        if (e) ink.fillPath(cv, p, LinearGradient(0f, cy - r * 0.22f, 0f, cy + r * 0.52f, 0xFF9AA3BE.toInt(), 0xFF5E6782.toInt(), Shader.TileMode.CLAMP))
        else ink.fillPath(cv, p, SIL_DEEP)
        ink.stroke(cv, p, line, ow)
        val dash = if (e) 0xFFFFE38E.toInt() else SIL
        for ((y0, y1, w) in listOf(Triple(0.3f, 0.46f, 0.06f), Triple(0.08f, 0.2f, 0.045f), Triple(-0.1f, -0.02f, 0.032f))) {
            ink.rect.set(cx - r * w, cy + r * y0, cx + r * w, cy + r * y1); ink.fill.shader = null; ink.fill.color = dash
            cv.drawRoundRect(ink.rect, r * w, r * w, ink.fill)
        }
        ink.rect.set(cx - r * 0.3f, cy - r * 0.62f, cx + r * 0.3f, cy - r * 0.34f)
        ink.fill.color = if (e) Look.WHITE else SIL; cv.drawRoundRect(ink.rect, r * 0.14f, r * 0.14f, ink.fill)
        ink.line.color = line; ink.line.strokeWidth = ow * 0.8f; cv.drawRoundRect(ink.rect, r * 0.14f, r * 0.14f, ink.line)
        ink.text.typeface = face; ink.text.textAlign = Paint.Align.CENTER; ink.text.textSize = r * 0.22f
        ink.text.color = if (e) Look.COCOA else LOCK_LINE
        cv.drawText("100", cx, cy - r * 0.405f, ink.text)
    }

    private fun star(p: Path, cx: Float, cy: Float, ro: Float, ri: Float) {
        p.reset()
        for (i in 0 until 10) {
            val a = (-PI / 2 + i * PI / 5).toFloat(); val rr = if (i % 2 == 0) ro else ri
            if (i == 0) p.moveTo(cx + cos(a) * rr, cy + sin(a) * rr) else p.lineTo(cx + cos(a) * rr, cy + sin(a) * rr)
        }
        p.close()
    }
}

/** one badge's medal as a view (square; the medal is drawn to fit) */
class MedalView(context: Context, medal: Medal, earned: Boolean) : View(context) {
    var medal: Medal = medal
        set(v) { field = v; invalidate() }
    var earned: Boolean = earned
        set(v) { field = v; invalidate() }
    private val ink = Ink()
    private val face = Look.display(context)

    override fun onDraw(cv: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        Medals.draw(cv, ink, medal, earned, width / 2f, height / 2f - s * 0.07f, s * 0.36f, face)
    }
}

/**
 * The month on the record screen: a cell per day. A driven day is a chip in its score's colour with the score on it (a
 * driven day without a turn is a plain chip with a small car), a day without driving stays blank. Today has a periwinkle
 * ring, the chosen day a cocoa outline. Tapping a day picks it.
 */
class MonthCalendarView(context: Context, private val onPick: (Int) -> Unit) : View(context) {
    var month: Int = Days.month(currentDayKey())
        set(v) { field = v; requestLayout(); invalidate() }
    var today: Int = currentDayKey()
        set(v) { field = v; invalidate() }
    var selected: Int? = null
        set(v) { field = v; invalidate() }
    var log: DriveLog? = null
        set(v) { field = v; invalidate() }

    private val ink = Ink()
    private val d = resources.displayMetrics.density
    private val face = Look.display(context)
    private val night = Theme.night
    private val headH = 40f * d
    private val gap = 7f * d
    private var pressed: Int? = null

    private fun rows(): Int = (Days.weekday(Days.first(month)) + Days.length(month) + 6) / 7
    private fun cellW(w: Float) = (w - gap * 6) / 7f
    private fun cellH(w: Float) = (cellW(w) * 0.6f).coerceIn(56f * d, 94f * d)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val n = rows()
        setMeasuredDimension(w, (headH + n * cellH(w.toFloat()) + (n - 1) * gap).toInt() + 1)
    }

    /** the day under a point, or null */
    private fun dayAt(x: Float, y: Float): Int? {
        val w = width.toFloat(); val cw = cellW(w); val ch = cellH(w)
        if (y < headH) return null
        val col = (x / (cw + gap)).toInt().coerceIn(0, 6)
        val row = ((y - headH) / (ch + gap)).toInt()
        val dom = row * 7 + col - Days.weekday(Days.first(month)) + 1
        return if (dom in 1..Days.length(month)) month * 100 + dom else null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = dayAt(e.x, e.y); invalidate(); return true }
            MotionEvent.ACTION_UP -> {
                val day = dayAt(e.x, e.y)
                if (day != null && day == pressed) { playSoundEffect(android.view.SoundEffectConstants.CLICK); onPick(day) }
                pressed = null; invalidate(); return true
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
            MotionEvent.ACTION_MOVE -> if (pressed != null && dayAt(e.x, e.y) != pressed) { pressed = null; invalidate() }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val cw = cellW(w); val ch = cellH(w)
        // the weekday names: Sunday coral, Saturday periwinkle, as on Korean calendars
        ink.text.typeface = Typeface.DEFAULT_BOLD; ink.text.textAlign = Paint.Align.CENTER; ink.text.textSize = 15f * d
        val names = arrayOf("일", "월", "화", "수", "목", "금", "토")
        for (i in 0 until 7) {
            ink.text.color = Theme.text(when (i) { 0 -> Look.CORAL_D; 6 -> Look.PERI_DEEP; else -> Look.SUB })
            cv.drawText(names[i], i * (cw + gap) + cw / 2f, headH * 0.62f, ink.text)
        }
        val first = Days.weekday(Days.first(month))
        for (dom in 1..Days.length(month)) {
            val idx = first + dom - 1
            val x = (idx % 7) * (cw + gap); val y = headH + (idx / 7) * (ch + gap)
            cell(cv, month * 100 + dom, idx % 7, x, y, cw, ch)
        }
    }

    private fun cell(cv: Canvas, day: Int, col: Int, x: Float, y: Float, cw: Float, ch: Float) {
        val rec = log?.day(day)
        val rad = min(16f * d, ch * 0.24f)
        val r = ink.rect
        r.set(x, y, x + cw, y + ch)
        val tone = if (rec != null) Bands.of(rec.score) else null
        if (tone != null) {
            ink.fill.color = OPAQUE; ink.fill.shader = LinearGradient(0f, y, 0f, y + ch, tone.top, tone.bottom, Shader.TileMode.CLAMP)
            cv.drawRoundRect(r, rad, rad, ink.fill); ink.fill.shader = null
            ink.line.color = tone.edge; ink.line.strokeWidth = 1.5f * d; r.inset(0.75f * d, 0.75f * d); cv.drawRoundRect(r, rad, rad, ink.line)
            // a little sheen along the top
            ink.fill.color = 0x40FFFFFF; r.set(x + rad * 0.6f, y + 3f * d, x + cw - rad * 0.6f, y + ch * 0.2f); cv.drawRoundRect(r, ch * 0.1f, ch * 0.1f, ink.fill)
        } else {
            ink.fill.shader = null; ink.fill.color = if (night) 0x1AFFFFFF else 0xFFFFFCF7.toInt(); cv.drawRoundRect(r, rad, rad, ink.fill)
            ink.line.color = if (night) 0x26FFFFFF else 0xFFF2E6D6.toInt(); ink.line.strokeWidth = 1f * d; r.inset(0.5f * d, 0.5f * d); cv.drawRoundRect(r, rad, rad, ink.line)
        }
        if (pressed == day) { ink.fill.color = 0x1F4A3426; r.set(x, y, x + cw, y + ch); cv.drawRoundRect(r, rad, rad, ink.fill) }

        // the date, top left
        val future = day > today
        ink.text.typeface = Typeface.DEFAULT_BOLD; ink.text.textAlign = Paint.Align.LEFT
        ink.text.textSize = (if (ch < 70f * d) 13f else 15f) * d
        ink.text.color = when {
            tone != null -> tone.ink
            future -> Theme.text(Look.MUTED)
            col == 0 -> Theme.text(Look.CORAL_D)
            col == 6 -> Theme.text(Look.PERI_DEEP)
            else -> Theme.text(Look.INK)
        }
        cv.drawText(Days.dayOfMonth(day).toString(), x + 9f * d, y + ink.text.textSize + 5f * d, ink.text)

        // the score in the middle (or a small car: driven, but no turn to judge)
        if (rec != null) {
            val s = rec.score
            if (s != null) {
                val big = min(ch * 0.42f, cw * 0.3f)
                ink.text.typeface = face; ink.text.textSize = big; ink.text.color = tone!!.ink
                val unit = if (cw > 96f * d) "점" else ""
                ink.text.textAlign = Paint.Align.LEFT
                val wNum = ink.text.measureText(s.toString())
                ink.text.textSize = big * 0.5f; val wUnit = if (unit.isEmpty()) 0f else ink.text.measureText(unit) + 2f * d
                val sx = x + cw / 2f - (wNum + wUnit) / 2f + (if (cw > 96f * d) 6f * d else 2f * d)
                val base = y + ch * 0.78f
                ink.text.textSize = big; cv.drawText(s.toString(), sx, base, ink.text)
                if (unit.isNotEmpty()) { ink.text.textSize = big * 0.5f; cv.drawText(unit, sx + wNum + 2f * d, base, ink.text) }
            } else {
                Pics.car(cv, ink, x + cw / 2f + 4f * d, y + ch * 0.6f, min(cw, ch) * 0.2f)
            }
        }

        // today: a periwinkle ring just outside the cell, and a small tag
        if (day == today) {
            ink.line.color = Look.PERI; ink.line.strokeWidth = 2.6f * d
            r.set(x - 2.5f * d, y - 2.5f * d, x + cw + 2.5f * d, y + ch + 2.5f * d); cv.drawRoundRect(r, rad + 2.5f * d, rad + 2.5f * d, ink.line)
            if (cw > 70f * d) {
                ink.text.typeface = face; ink.text.textSize = 12.5f * d; ink.text.textAlign = Paint.Align.CENTER
                val tw = ink.text.measureText("오늘") + 12f * d; val th = 19f * d
                r.set(x + cw - tw - 6f * d, y + 6f * d, x + cw - 6f * d, y + 6f * d + th)
                ink.fill.shader = null; ink.fill.color = Look.PERI; cv.drawRoundRect(r, th / 2f, th / 2f, ink.fill)
                ink.text.color = Look.WHITE; cv.drawText("오늘", r.centerX(), r.bottom - 5f * d, ink.text)
            }
        }
        // the chosen day: a cocoa outline
        if (day == selected) {
            ink.line.color = if (night && tone == null) 0xFFF0F3FF.toInt() else Look.COCOA; ink.line.strokeWidth = 3f * d
            r.set(x + 1.5f * d, y + 1.5f * d, x + cw - 1.5f * d, y + ch - 1.5f * d); cv.drawRoundRect(r, rad - 1f * d, rad - 1f * d, ink.line)
        }
    }
}

/** the day's score in a ring: the coloured arc is the score out of 100 */
class ScoreRing(context: Context) : View(context) {
    var score: Int? = null
        set(v) { field = v; invalidate() }
    private val ink = Ink()
    private val d = resources.displayMetrics.density
    private val face = Look.display(context)

    override fun onDraw(cv: Canvas) {
        val s = min(width, height).toFloat(); if (s <= 0f) return
        val cx = width / 2f; val cy = height / 2f
        val sw = s * 0.095f; val r = s / 2f - sw / 2f - 2f * d
        ink.circle(cv, cx, cy, r + sw / 2f, if (Theme.night) 0x1AFFFFFF else Look.WHITE)
        ink.line.shader = null; ink.line.color = if (Theme.night) 0xFF2A3796.toInt() else 0xFFF1E4D2.toInt(); ink.line.strokeWidth = sw
        cv.drawCircle(cx, cy, r, ink.line)
        val sc = score
        val tone = Bands.of(sc)
        if (sc != null && sc > 0) {
            ink.line.color = tone.solid; ink.rect.set(cx - r, cy - r, cx + r, cy + r)
            cv.drawArc(ink.rect, -90f, 360f * sc / 100f, false, ink.line)
        }
        ink.text.typeface = face; ink.text.textAlign = Paint.Align.CENTER
        if (sc != null) {
            ink.text.textSize = s * 0.3f; ink.text.color = tone.text()
            val num = sc.toString(); val wn = ink.text.measureText(num)
            ink.text.textSize = s * 0.13f; val wu = ink.text.measureText("점")
            val sx = cx - (wn + wu) / 2f
            ink.text.textAlign = Paint.Align.LEFT
            ink.text.textSize = s * 0.3f; cv.drawText(num, sx, cy + s * 0.1f, ink.text)
            ink.text.textSize = s * 0.13f; cv.drawText("점", sx + wn + 1f * d, cy + s * 0.1f, ink.text)
        } else {
            ink.text.textSize = s * 0.26f; ink.text.color = Theme.text(Look.MUTED); cv.drawText("–", cx, cy + s * 0.09f, ink.text)
        }
    }
}

/** a round button with a chevron pointing back or forward (the month arrows) */
class ChevronButton(context: Context, private val back: Boolean) : View(context) {
    private val ink = Ink()
    private val d = resources.displayMetrics.density
    override fun onDraw(cv: Canvas) {
        val s = min(width, height).toFloat(); val r = s / 2f - 2f * d; val cx = width / 2f; val cy = height / 2f
        val night = Theme.night
        ink.circle(cv, cx, cy + 1.5f * d, r, 0x1F7A5434)
        ink.circle(cv, cx, cy, r, if (isPressed) (if (night) 0xFF34439E.toInt() else Look.CREAM2) else if (night) 0xFF26348F.toInt() else Look.WHITE)
        ink.circleOutline(cv, cx, cy, r, if (night) 0x968CA0F0.toInt() else Look.LINE, 1.4f * d)
        ink.line.color = if (night) 0xFFF0F3FF.toInt() else Look.COCOA; ink.line.strokeWidth = 2.6f * d
        val k = if (back) 1f else -1f
        ink.path.reset(); ink.path.moveTo(cx + k * s * 0.06f, cy - s * 0.17f); ink.path.lineTo(cx - k * s * 0.08f, cy); ink.path.lineTo(cx + k * s * 0.06f, cy + s * 0.17f)
        cv.drawPath(ink.path, ink.line)
    }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
}

/** the pet's speech bubble beside its portrait: cream with the cocoa line, a small tail pointing left at the pet */
class SpeechBubbleDrawable(private val d: Float, private val fill: Int, private val line: Int) : Drawable() {
    private val ink = Ink()
    private val tail = 12f * d
    override fun draw(cv: Canvas) {
        val b = bounds; val l = b.left + tail; val t = b.top.toFloat(); val r = b.right.toFloat(); val bt = b.bottom.toFloat()
        val rad = min(22f * d, (bt - t) / 2f)
        val p = ink.path; p.reset()
        p.addRoundRect(l, t, r - 1f * d, bt - 1f * d, rad, rad, Path.Direction.CW)
        val ty = t + min(34f * d, (bt - t) / 2f)
        ink.path2.reset(); ink.path2.moveTo(l + 1f * d, ty - 9f * d); ink.path2.lineTo(b.left + 1f * d, ty + 2f * d); ink.path2.lineTo(l + 1f * d, ty + 8f * d); ink.path2.close()
        p.op(ink.path2, Path.Op.UNION)
        ink.fillPath(cv, p, fill)
        ink.stroke(cv, p, line, 2f * d)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds.left + tail.toInt(), bounds.top, bounds.right, bounds.bottom, 22f * d) }
    /** the tail's width: the text needs this much more padding on the left */
    val tailPx: Int get() = tail.toInt()
}
