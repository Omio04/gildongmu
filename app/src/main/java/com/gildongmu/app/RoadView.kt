package com.gildongmu.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The road ahead, seen from straight above (the look of the demo video): a flat dark road, white edge lines, grey
 * lane dashes, and flat cars, each a rounded body with a dark windscreen and rear-glass band. Our car is white; a
 * neighbour using the app is blue, any other car light grey. Only relative lane and distance are drawn: no
 * coordinates are ever shown.
 *
 * What moves, and why:
 *  - The lane marks stand still when parked; otherwise (stopped in D or moving) they ease up to one steady pace (not
 *    tied to the real speed) and ease back to a halt when the car is parked.
 *  - The cars around us drift a little back and forth, each on its own slow rhythm, the way traffic in a jam
 *    stretches and closes up.
 *  - While a question is out, rings spread from our car; a car that answers glows once and keeps a blue halo.
 * Everything stops when the view is hidden or the system turns animations off.
 */
class RoadView(
    context: Context,
    neighbors: List<Neighbor>,
    private val theme: Theme,
    regular: Typeface,
    bold: Typeface,
) : View(context) {

    var neighbors: List<Neighbor> = neighbors
        set(v) { field = v; invalidate() }

    /** Neighbour id -> the short cause it answered with. A car that answered gets a halo and a tag. */
    var answers: Map<String, String> = emptyMap()
        set(v) {
            if (field != v) {
                val now = android.os.SystemClock.uptimeMillis()
                v.keys.filter { it !in field }.forEach { litAt[it] = now }   // a new answer: that car glows once
                field = v; invalidate()
            }
        }

    /** True while a question is out: rings spread from our car. */
    var scanning = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Our own name (and car, if shared) on a tag above our car, like every neighbour's. */
    var myName: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var myModel: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The bubble beside our own car. */
    var myMood: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Kept so the screen can tell the road about the driving state. */
    var moving: Boolean = false

    /** The lane marks flow: in every state except parked (stopped in D flows like driving, at the same pace). */
    var flowing: Boolean = false

    /** Vehicle speed; the lane marks scroll in proportion (0 = standing still, the road still inches along). */
    var speedKmh: Float = 0f
        set(v) { field = v; invalidate() }

    /** A tap on a neighbour's car, with that car's id. Null = cars are not tappable (e.g. while driving). */
    var onCarTap: ((String) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val taken = mutableListOf<RectF>()   // cars and name tags already drawn this frame: bubbles keep off them
    private val carBox = HashMap<String, RectF>()   // where each neighbour's car was drawn: what a tap lands on

    // road
    private val shoulder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ROAD_COLOR }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6EAF0"); strokeWidth = 3f * d }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A94A6") }

    // cars
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.parseColor("#C3D0DE") }
    private val glassP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33000000") }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = theme.accent }
    private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = theme.accent }

    // tags and bubbles
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.surface }
    private val chipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = theme.border }
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.surface }
    private val bubbleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = theme.accent }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; typeface = bold; color = theme.ink }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16 * d; typeface = bold; color = theme.ink }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14 * d; typeface = regular; color = theme.muted }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val onP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val onRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }

    private class Spot(val lane: Int, val yf: Float)

    /**
     * Where each car stands: how far ahead of (or behind) us it really is sets the height, how far to the side it is
     * sets the lane. Both come from the positions the apps reported, measured by the server. Two cars never overlap:
     * the later one moves to a free lane, or a little further up the road.
     */
    private fun spots(list: List<Neighbor>): Map<String, Spot> {
        val placed = mutableListOf<Spot>()
        val out = HashMap<String, Spot>()
        for (n in list.sortedBy { Math.abs(it.aheadM) }) {
            // the road is in perspective, so near cars take more room than far ones: height grows with the square root of distance
            val far = Math.sqrt((Math.abs(n.aheadM) / RADIUS).toDouble().coerceAtMost(1.0)).toFloat()
            val yf0 = if (n.aheadM >= 0) MY_Y - far * 0.62f else (MY_Y + far * 0.10f + 0.02f).coerceAtMost(0.86f)
            val pref = (Math.round(n.lateralM / 3.5f) + 1).coerceIn(0, 2)
            val lanes = listOf(pref, 1, 0, 2).distinct().sortedBy { Math.abs(it - pref) }
            var spot: Spot? = null
            var yf = yf0
            var tries = 0
            while (spot == null && tries < 4) {
                spot = lanes.firstOrNull { l ->
                    placed.none { it.lane == l && Math.abs(it.yf - yf) < GAP } && !(l == 1 && yf > MY_Y - GAP && yf < MY_Y + GAP)
                }?.let { Spot(it, yf) }
                yf = (yf - GAP).coerceAtLeast(0.2f); tries++
            }
            spot?.let { placed += it; out[n.id] = it }
        }
        return out
    }

    private var scroll = 0f
    private var pace = 0f
    private var lastFrame = 0L
    private val litAt = HashMap<String, Long>()
    private val seenAt = HashMap<String, Long>()   // when a car first showed up: it fades in with a green ring
    private var firstLook = true
    private val carPos = HashMap<String, PointF>()
    private var clip: Path? = null
    private val animationsOn: Boolean
        get() = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        clip = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 22 * d, 22 * d, Path.Direction.CW) }
        // night: the dark road of the video design. Day: a light road with grey-blue dashed lanes, white cars outlined, real neighbours in navy
        shoulder.color = if (theme.night) darken(theme.roadTop, 0.42f) else Color.parseColor("#E7ECF3")
        edge.color = if (theme.night) Color.parseColor("#E6EAF0") else theme.roadEdge
        dash.color = if (theme.night) Color.parseColor("#8A94A6") else theme.roadDash
        road.shader = if (theme.night) LinearGradient(0f, 0f, 0f, h.toFloat(), ROAD_TOP, ROAD_BOTTOM, Shader.TileMode.CLAMP)   // slightly lighter far ahead
            else LinearGradient(0f, 0f, 0f, h.toFloat(), theme.roadTop, theme.roadBottom, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        c.save(); c.clipPath(clip ?: return)
        c.drawRect(0f, 0f, w, h, shoulder)

        // perspective: the road is narrower far ahead (top) than near us (bottom)
        val roadWB = w * 0.90f
        fun s(y: Float) = TOP_SCALE + (1f - TOP_SCALE) * (y / h).coerceIn(0f, 1f)
        fun xAt(lane: Float, y: Float) = w / 2f + (lane - 1.5f) * (roadWB / 3f) * s(y)   // lane edge i (0..3) or centre (i + 0.5)
        val now = android.os.SystemClock.uptimeMillis()
        val running = animationsOn && isShown

        val asphalt = Path().apply {
            moveTo(xAt(0f, 0f), 0f); lineTo(xAt(3f, 0f), 0f); lineTo(xAt(3f, h), h); lineTo(xAt(0f, h), h); close()
        }
        c.drawPath(asphalt, road)
        c.drawLine(xAt(0f, 0f), 0f, xAt(0f, h), h, edge); c.drawLine(xAt(3f, 0f), 0f, xAt(3f, h), h, edge)

        // lane dashes scroll
        val period = 56 * d
        // parked: the road stands still. In D (stopped or moving) it eases up to one steady pace and stays there, whatever the real
        // speed; going back to P eases it to a halt.
        val target = if (flowing) CRUISE_KMH else 0f
        if (running) {
            if (lastFrame != 0L) {
                val dt = ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
                val rate = if (target > pace) 0.9 else 2.5   // slow to get going, quicker to settle when stopping
                pace += (target - pace) * (1f - Math.exp(-rate * dt).toFloat())
                if (target == 0f && pace < 0.5f) pace = 0f
                scroll = (scroll + dt * (pace / CRUISE_KMH) * CRUISE_PX_PER_SEC * d) % period
            }
            lastFrame = now
        } else lastFrame = 0L
        for (i in 1..2) {
            var y = -period + scroll
            while (y < h) {
                val y1 = y + period * 0.5f
                dashQuad(c, xAt(i.toFloat(), y), y, 1.5f * d, xAt(i.toFloat(), y1), y1, 1.5f * d)
                y += period
            }
        }

        // ---- while a question is out, rings spread from our car
        val myY = h * MY_Y
        val mx = xAt(1.5f, myY)
        val laneAtMe = roadWB / 3f * s(myY)
        val cwMe = laneAtMe * 0.52f
        val chMe = cwMe * CAR_LENGTH
        if (scanning && running) {
            for (k in 0..2) {
                val ph = ((now % 2400L) / 2400f + k / 3f) % 1f
                ripple.alpha = ((1f - ph) * (1f - ph) * 120f).toInt()
                c.drawCircle(mx, myY, chMe * 0.6f + ph * chMe * 3.0f, ripple)
            }
        }

        // ---- the cars. They drift a little, each on its own rhythm: traffic stretching and closing up.
        taken.clear(); carPos.clear(); carBox.clear()
        val t = now / 1000.0
        val tags = mutableListOf<Triple<Neighbor, RectF, String?>>()   // car, its box, what it answered
        // a full road still makes room for whoever is speaking or has just answered: they come first
        val shownCars = neighbors.sortedBy { if (it.say != null || it.id in answers) 0 else 1 }.take(MAX_CARS)
        shownCars.forEach { n -> if (n.id !in seenAt) seenAt[n.id] = if (firstLook) 0L else now }
        firstLook = false
        seenAt.keys.retainAll(shownCars.map { it.id }.toSet())
        val spots = spots(shownCars)
        shownCars.forEach { n ->
            val spot = spots[n.id] ?: return@forEach
            val lane = spot.lane; val yf = spot.yf
            val i = Math.abs(n.id.hashCode() % 11)   // each car keeps its own rhythm, whoever else comes or goes
            val drift = if (running) sin(t * 0.55 + i * 1.7) * 5.0 + sin(t * 0.23 + i) * 3.0 else 0.0
            val sway = if (running) sin(t * 0.4 + i * 2.3) * 1.6 else 0.0
            val cy = h * yf + (drift * d).toFloat()
            val sc = s(cy)
            val cw = roadWB / 3f * sc * 0.52f
            val ch = cw * CAR_LENGTH
            val cx = xAt(lane + 0.5f, cy) + (sway * d).toFloat()
            carPos[n.id] = PointF(cx, cy)
            val said = answers[n.id]
            val age = if (seenAt[n.id] == 0L) APPEAR_MS else (now - (seenAt[n.id] ?: now)).coerceAtLeast(0L)
            val fade = (age / APPEAR_MS.toFloat()).coerceIn(0f, 1f)
            if (fade < 1f) c.saveLayerAlpha(cx - ch, cy - ch, cx + ch, cy + ch, (fade * 255).toInt())
            // blue = a real person on the app, light grey = a simulated car; a car that answered also gets a blue halo
            car(c, cx, cy, cw, ch,
                if (theme.night) (if (n.live) CAR_BLUE else CAR_GREY) else (if (n.live) theme.carLive else theme.carOther), halo = said != null)
            if (fade < 1f) c.restore()
            if (fade < 1f) {   // a car that just joined: a green ring spreads from it once
                onRing.color = theme.accent; onRing.strokeWidth = 2.5f * d; onRing.alpha = ((1f - fade) * 200).toInt()
                c.drawCircle(cx, cy, cw * (0.6f + fade * 1.6f), onRing)
            }
            val box = RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
            taken += box
            carBox[n.id] = RectF(box)
            tags += Triple(n, box, said)   // everyone on the road shows who they are and how far, whether or not they spoke
        }
        car(c, mx, myY, cwMe, chMe, Color.WHITE)
        taken += RectF(mx - cwMe / 2, myY - chMe / 2, mx + cwMe / 2, myY + chMe / 2)

        // a car that just answered glows once
        if (litAt.isNotEmpty()) {
            litAt.entries.removeAll { now - it.value > PULSE_MS }
            litAt.forEach { (id, at) ->
                carPos[id]?.let { pos ->
                    val ph = (now - at) / PULSE_MS.toFloat()
                    pulse.alpha = ((1f - ph) * 170f).toInt()
                    c.drawCircle(pos.x, pos.y, cwMe * (0.7f + ph * 1.1f), pulse)
                }
            }
        }

        // ---- tags, then speech bubbles last so nothing covers them. Ours first, then at most two neighbours.
        val bubbles = mutableListOf<Triple<Float, Pair<Float, Float>, String>>()   // x, (bottom, below), text
        tags.forEach { (n, box, said) ->
            val tagTop = tag(c, box.centerX(), box.top - 6 * d, n, said)
            n.say?.let { bubbles += Triple(box.centerX(), (tagTop - 4 * d) to (box.bottom + 6 * d), it) }
        }
        val placed = mutableListOf<RectF>()
        var myTop = myY - chMe / 2f - 8 * d   // where our own bubble may stand: above the car, or above its name tag
        if (myName.isNotBlank()) {
            myTop = tag(c, mx, myY - chMe / 2f - 6 * d, Neighbor("me", myName, "", "", 0, false), null, myModel.ifBlank { "나" }) - 4 * d
        }
        myMood?.let { bubble(c, mx, myTop, myY + chMe / 2f + 6 * d, it, placed) }
        var shown = 0
        for ((x, ys, text) in bubbles) {
            if (shown >= MAX_NEIGHBOUR_BUBBLES) break
            if (bubble(c, x, ys.first, ys.second, text, placed)) shown++
        }
        c.restore()
        if (running || litAt.isNotEmpty()) postInvalidateOnAnimation()   // one frame per screen refresh: smooth, and idle when hidden
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (onCarTap == null) return super.onTouchEvent(e)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> return carAt(e.x, e.y) != null
            MotionEvent.ACTION_UP -> carAt(e.x, e.y)?.let { performClick(); onCarTap?.invoke(it); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /** The neighbour whose car is under the finger. The target is a little bigger than the car: a finger is not a pin. */
    private fun carAt(x: Float, y: Float): String? {
        val slop = 14 * d
        return carBox.entries
            .filter { RectF(it.value).apply { inset(-slop, -slop) }.contains(x, y) }
            .minByOrNull { hypot(x - it.value.centerX(), y - it.value.centerY()) }?.key
    }

    /** A car whose driver uses the app stands in a ring of blue light on the road; one that has answered shines brighter. */
    private fun appRing(c: Canvas, cx: Float, cy: Float, cw: Float, ch: Float, now: Long, running: Boolean, answered: Boolean) {
        val rx = cw * 0.95f; val ry = ch * 0.62f
        val ar = Color.red(theme.accent); val ag = Color.green(theme.accent); val ab = Color.blue(theme.accent)
        ringFill.shader = RadialGradient(cx, cy, rx * 1.1f, Color.argb(if (answered) 120 else 70, ar, ag, ab), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.save(); c.scale(1f, ry / rx, cx, cy); c.drawCircle(cx, cy, rx * 1.1f, ringFill); c.restore()
        val breathe = if (running) 0.75f + 0.25f * sin(now / 700.0 + cx).toFloat() else 1f
        onRing.color = theme.accent; onRing.alpha = ((if (answered) 255 else 190) * breathe).toInt()
        onRing.strokeWidth = (if (answered) 3.5f else 2.2f) * d
        c.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), onRing)
    }

    /** One lane dash as a trapezoid, so it narrows with the road. */
    private fun dashQuad(c: Canvas, x0: Float, y0: Float, hw0: Float, x1: Float, y1: Float, hw1: Float) {
        val p = Path().apply { moveTo(x0 - hw0, y0); lineTo(x0 + hw0, y0); lineTo(x1 + hw1, y1); lineTo(x1 - hw1, y1); close() }
        c.drawPath(p, dash)
    }

    /**
     * A short speech bubble above [bottom] (or below the car, at [below], when there is no room above).
     * Returns false, drawing nothing, when neither spot is free of cars, name tags and earlier bubbles.
     */
    private fun bubble(c: Canvas, cx: Float, bottom: Float, below: Float, full: String, placed: MutableList<RectF>): Boolean {
        val padX = 10 * d
        val hB = 26 * d
        val tail = 6 * d
        val maxW = 150 * d
        var text = full
        if (bubbleText.measureText(text) + padX * 2 > maxW) {   // long sentences are cut short, never wrapped
            val n = bubbleText.breakText(text, true, maxW - padX * 2 - bubbleText.measureText("…"), null)
            text = text.take(n).trimEnd() + "…"
        }
        val wB = bubbleText.measureText(text) + padX * 2

        fun rectAt(above: Boolean): RectF {
            val top = if (above) bottom - tail - hB else below + tail
            val r = RectF(cx - wB / 2, top, cx + wB / 2, top + hB)
            val shift = when { r.left < 6 * d -> 6 * d - r.left; r.right > width - 6 * d -> width - 6 * d - r.right; else -> 0f }
            r.offset(shift, 0f)
            return r
        }
        fun free(r: RectF, above: Boolean): Boolean {
            val withTail = RectF(r.left, if (above) r.top else r.top - tail, r.right, if (above) r.bottom + tail else r.bottom)
            return withTail.top >= 52 * d && withTail.bottom <= height - 4 * d &&   // keep clear of the chips on top
                taken.none { RectF.intersects(it, withTail) } && placed.none { RectF.intersects(it, withTail) }
        }
        val above = rectAt(true).takeIf { free(it, true) }
        val r = above ?: rectAt(false).takeIf { free(it, false) } ?: return false
        val isAbove = above != null
        placed += RectF(r.left, if (isAbove) r.top else r.top - tail, r.right, if (isAbove) r.bottom + tail else r.bottom)

        val tailX = cx.coerceIn(r.left + 10 * d, r.right - 10 * d)
        val path = Path().apply {
            addRoundRect(r, 13 * d, 13 * d, Path.Direction.CW)
            if (isAbove) { moveTo(tailX - 5 * d, r.bottom - 1); lineTo(tailX, r.bottom + tail); lineTo(tailX + 5 * d, r.bottom - 1) }
            else { moveTo(tailX - 5 * d, r.top + 1); lineTo(tailX, r.top - tail); lineTo(tailX + 5 * d, r.top + 1) }
        }
        c.drawPath(path, bubbleFill)
        c.drawPath(path, bubbleEdge)
        c.drawText(text, r.left + padX, r.top + hB / 2 + 4.3f * d, bubbleText)
        return true
    }

    /**
     * A car seen from straight above, as in the demo video: a rounded body with a dark windscreen band near the nose
     * and a darker rear-glass band near the tail. [halo] puts a soft blue light under the car (it just answered).
     */
    private fun car(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, color: Int, halo: Boolean = false) {
        if (halo) {
            val r = h / 2f * 1.05f
            glowP.shader = RadialGradient(cx, cy, r, Color.argb(if (theme.night) 120 else 100, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, glowP)
        }
        val r = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        // the same colour, shaded a little: lighter at the nose and upper left, deeper at the tail and lower right
        body.shader = LinearGradient(r.left, r.top, r.right, r.bottom, lighten(color, 0.22f), darken(color, 0.14f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, w * 0.3f, w * 0.3f, body)
        body.shader = null
        if (!theme.night) c.drawRoundRect(r, w * 0.3f, w * 0.3f, carEdge)   // a light road needs an outline: a white car must not disappear
        c.drawRoundRect(RectF(r.left + w * 0.12f, r.top + h * 0.18f, r.right - w * 0.12f, r.top + h * 0.38f), 6 * d, 6 * d, glassP)
        c.drawRoundRect(RectF(r.left + w * 0.12f, r.bottom - h * 0.26f, r.right - w * 0.12f, r.bottom - h * 0.12f), 6 * d, 6 * d, glassP)
    }

    private fun lighten(color: Int, f: Float): Int {
        fun up(v: Int) = (v + (255 - v) * f).toInt().coerceIn(0, 255)
        return Color.rgb(up(Color.red(color)), up(Color.green(color)), up(Color.blue(color)))
    }

    private fun darken(color: Int, f: Float): Int {
        fun down(v: Int) = (v * (1f - f)).toInt().coerceIn(0, 255)
        return Color.rgb(down(Color.red(color)), down(Color.green(color)), down(Color.blue(color)))
    }

    /** Draws the name tag above a car and returns where its top edge is. */
    private fun tag(c: Canvas, cx: Float, bottom: Float, n: Neighbor, answer: String?, second: String? = null): Float {
        val t1 = n.nickname
        val t2 = second ?: ("${n.distanceM}m" + (answer?.let { " · $it" } ?: ""))
        val pad = 8 * d
        val dotW = 0f   // no status dot on name tags: every car on this road is a real app user, so a dot would say nothing
        val wTxt = maxOf(label.measureText(t1), sub.measureText(t2)) + pad * 2 + dotW
        val hTag = 36 * d
        val r = RectF(cx - wTxt / 2, bottom - hTag, cx + wTxt / 2, bottom)
        // keep the tag inside the road card
        val shift = when { r.left < 6 * d -> 6 * d - r.left; r.right > width - 6 * d -> width - 6 * d - r.right; else -> 0f }
        r.offset(shift, 0f)
        taken += RectF(r)
        c.drawRoundRect(r, 11 * d, 11 * d, chip)
        c.drawRoundRect(r, 11 * d, 11 * d, chipBorder)
        label.color = theme.ink
        sub.color = theme.muted
        c.drawText(t1, r.left + pad + dotW, r.top + 16 * d, label)
        c.drawText(t2, r.left + pad + dotW, r.top + 31 * d, sub)
        return r.top
    }

    private companion object {
        const val MAX_NEIGHBOUR_BUBBLES = 2   // plus our own: the road stays readable
        const val CRUISE_KMH = 50f            // the one steady pace the road flows at while moving
        const val CRUISE_PX_PER_SEC = 55f     // calm: less than half the old look at 50 km/h
        const val IDLE_KMH = 8f
        const val MAX_CARS = 6               // the road has room for this many; the rest are counted on the chip
        const val RADIUS = 300f              // metres from our car to the top of the road view
        const val MY_Y = 0.78f               // our car's height on the road, 0 = far ahead, 1 = the bottom
        const val GAP = 0.22f                // the least height two cars in one lane keep between them
        const val PIN_R = 16f                 // dp: radius of a car's profile pin
        const val PIN_H = 40f                 // dp: how much room the pin takes above the car
        const val APPEAR_MS = 700L            // a car that joins the radius fades in over this long
        const val PULSE_MS = 1100L            // how long an answering car glows
        const val TOP_SCALE = 1f              // 1 = a flat road seen from straight above (no perspective)
        const val CAR_LENGTH = 1.9f           // car length as a multiple of its width
        val ROAD_COLOR = Color.parseColor("#2A3342")
        val ROAD_TOP = Color.parseColor("#344055")      // the far end of the road
        val ROAD_BOTTOM = Color.parseColor("#232B39")   // near us
        val CAR_BLUE = Color.parseColor("#1E6BFF")
        val CAR_GREY = Color.parseColor("#C9D1DC")
    }
}
