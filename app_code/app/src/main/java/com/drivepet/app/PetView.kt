package com.drivepet.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How the pet is drawn. In gear the small floating pet is seen from behind: WALK_BACK while the car moves, STAND_BACK when
 * it has stopped (it just stops where it is). STAND is the front view when the car is not in gear.
 */
enum class Pose { STAND, WALK_BACK, STAND_BACK }

/**
 * The pet and its room.
 * The room is a box seen from the front: a back wall, a left and a right wall, a ceiling and a wooden floor, all in
 * perspective. The pet walks on the floor from spot to spot at a steady pace; going deeper into the room it gets
 * smaller and is seen from behind, coming forward it faces you. All drawn with the canvas: no image files.
 *
 * The look follows the app icon (see [Look], [PetPainter], [RoomPainter]). The room itself is painted once into a bitmap
 * and reused; every frame only the pet, the clock's hands and the two standing items are drawn.
 */
class PetView(context: Context) : View(context) {
    var state: PetState = PetState.NORMAL
        set(v) { field = v; invalidate() }
    var level: Int = 1
        set(v) { field = v; invalidate() }
    /** how grown the pet is, by the distance driven together: egg, infant, rebel, adult */
    var stage: GrowthStage = GrowthStage.EGG
        set(v) { field = v; invalidate() }
    /** the emoji of the thing the pet has just fetched (small pet only), drawn as a badge */
    var carry: String? = null
        set(v) { field = v; invalidate() }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    /** the furniture standing in the room, by item id (several at once) */
    var furniture: Set<String> = emptySet()
        set(v) { field = v; invalidate() }
    var pose: Pose = Pose.STAND
        set(v) { field = v; invalidate() }
    /** what the pet wears and which furniture stands in its room, by item id */
    var outfit: Map<Slot, String> = emptyMap()
        set(v) { field = v; invalidate() }
    /** false for the small floating pet: no room, only the character */
    var showScene: Boolean = true
    /** true: the pet stands still in the middle of the room instead of wandering (used by the first-run introduction) */
    var stayStill: Boolean = false
        set(v) { field = v; invalidate() }

    // the speech bubble that stays beside the pet's head and follows it around the room
    private var bubbleText: String? = null
    private var bubbleUntil = 0L
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.INK; typeface = Look.display(context) }

    /** what the pet says: shown in a bubble next to it for [ms] milliseconds, then it fades out */
    fun say(text: String, ms: Long = 5_500L) {
        bubbleText = text
        bubbleUntil = System.currentTimeMillis() + ms
        invalidate()
    }

    private var phase = 0f
    private var burstStart = 0L
    private var burstGood = true

    private val d = resources.displayMetrics.density
    private val ink = Ink()
    private val painter = PetPainter()
    private val roomPainter = RoomPainter(context)
    private val bubblePath = Path()
    private val rect = RectF()
    // the soft shadow under the pet, in unit space (scaled to the pet's size when drawn)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0x57603C1C, 0x29603C1C, 0x00603C1C), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
    }
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    /** a short burst of sparkles (good) or sweat drops (bad) next to the pet */
    fun burst(good: Boolean) { burstGood = good; burstStart = System.currentTimeMillis(); invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); freeRoom(); super.onDetachedFromWindow() }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ================================================================ the room (a box in perspective)

    private var bx0 = 0f; private var bx1 = 0f; private var by0 = 0f; private var by1 = 0f   // the back wall
    private var vw = 0f; private var vh = 0f
    private var vpx = 0f; private var vpy = 0f   // the vanishing point
    private var geo: RoomGeo? = null

    private fun layoutRoom(w: Float, h: Float) {
        vw = w; vh = h
        // One-point perspective. The eye is at (vpx, vpy). The back wall is the screen rectangle shrunk towards that point by
        // ROOM_DEPTH, so the corner lines of the room (screen corner -> back-wall corner) all run through the vanishing
        // point, and so do the floor boards. That is what keeps the corners and the board directions consistent.
        vpx = w * 0.5f; vpy = h * 0.36f
        bx0 = vpx * (1f - ROOM_DEPTH); bx1 = vpx + ROOM_DEPTH * (w - vpx)
        by0 = vpy * (1f - ROOM_DEPTH); by1 = vpy + ROOM_DEPTH * (h - vpy)
        geo = RoomGeo(w, h)
    }

    /** the front edge of the floor where the pet may stand */
    private fun floorFront() = vh * 0.93f

    /** floor coordinates: u -1..1 left to right, v 0 (back wall) .. 1 (front). Returns x, y and the size factor. */
    private fun floorPoint(u: Float, v: Float): Triple<Float, Float, Float> {
        val y = by1 + (floorFront() - by1) * v
        val t = (y - by1) / (vh - by1)
        val left = bx0 * (1f - t); val right = bx1 + (vw - bx1) * t
        return Triple(lerp(left, right, (u + 1f) / 2f), y, (y - vpy) / (vh - vpy))
    }

    // ---- the painted room, kept as bitmaps until something in it changes (size, the hurt tint, the furniture)
    private var roomBmp: Bitmap? = null
    private var roomKey = ""
    private var plantSprite: RoomPainter.Sprite? = null
    private var lampSprite: RoomPainter.Sprite? = null

    private fun ensureRoom(w: Float, h: Float) {
        val hurt = state == PetState.HURT
        val key = "${w.toInt()}x${h.toInt()}|$hurt|${furniture.sorted().joinToString(",")}"
        if (key == roomKey && roomBmp != null) return
        freeRoom()
        val g = geo ?: return
        roomPainter.hurt = hurt
        val bmp = Bitmap.createBitmap(max(1, w.toInt()), max(1, h.toInt()), Bitmap.Config.ARGB_8888)
        roomPainter.paintRoom(Canvas(bmp), g, furniture)
        roomBmp = bmp
        if ("plant" in furniture) { val p = floorPoint(-0.82f, 0.3f); plantSprite = roomPainter.plantSprite(p.first, p.second, h * 0.1f * p.third) }
        if ("lamp" in furniture) { val p = floorPoint(0.84f, 0.26f); lampSprite = roomPainter.lampSprite(p.first, p.second, h * 0.1f * p.third) }
        roomKey = key
    }

    private fun freeRoom() {
        roomBmp?.recycle(); roomBmp = null
        plantSprite?.bmp?.recycle(); plantSprite = null
        lampSprite?.bmp?.recycle(); lampSprite = null
        roomKey = ""
    }

    // ================================================================ where the pet is

    /** where and how the pet is drawn this frame */
    private class Place(val x: Float, val ground: Float, val factor: Float, val v: Float,
                        val walking: Boolean, val back: Boolean, val lean: Float)

    private fun spotU(slot: Long): Float = (((slot * 2654435761L) and 0xFFFFL) % 1601L) / 1000f - 0.8f
    private fun spotV(slot: Long): Float = 0.12f + (((slot * 40503L + 977L) * 2654435761L and 0xFFFFL) % 1001L) / 1000f * 0.84f

    /**
     * Every 8 seconds the pet picks a new spot on the floor and walks there at a steady pace, then rests.
     * The same time always gives the same place, so nothing has to be stored.
     */
    private fun roomPlace(now: Long): Place {
        if (stayStill) { val p = floorPoint(0f, 0.5f); return Place(p.first, p.second, p.third, 0.5f, false, false, 0f) }
        val slot = now / WALK_SLOT_MS
        val t = now % WALK_SLOT_MS
        val u0 = spotU(slot - 1); val v0 = spotV(slot - 1)
        val u1 = spotU(slot); val v1 = spotV(slot)
        val du = u1 - u0; val dv = v1 - v0
        val dist = hypot(du, dv * DEPTH_COST)
        val moveMs = min((dist / WALK_SPEED * 1000f).toLong(), WALK_SLOT_MS - 2000L).coerceAtLeast(1L)
        val walking = t < moveMs && dist > 0.05f
        val k = if (t >= moveMs) 1f else t / moveMs.toFloat()
        val u = lerp(u0, u1, k); val v = lerp(v0, v1, k)
        val p = floorPoint(u, v)
        // going deeper into the room: seen from behind. Otherwise facing us, leaning a little the way it goes.
        val away = walking && dv < 0f && abs(dv) * DEPTH_COST > abs(du) * 0.7f
        val lean = if (walking) (du / dist).coerceIn(-1f, 1f) * 4f else 0f
        return Place(p.first, p.second, p.third, v, walking, away, lean)
    }

    private fun overlayPlace(w: Float, h: Float) = Place(w / 2f, h * 0.9f, 1f, 1f, pose == Pose.WALK_BACK, pose != Pose.STAND, 0f)

    // ================================================================ drawing

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = System.currentTimeMillis()
        val s = min(w, h)
        if (showScene) {
            if (w != vw || h != vh) layoutRoom(w, h)
            ensureRoom(w, h)
            roomBmp?.let { cv.drawBitmap(it, 0f, 0f, blit) }
            if ("clock" in furniture) geo?.let { g ->
                val (cx, cy, r) = roomPainter.clockSpot(g)
                val cal = java.util.Calendar.getInstance()
                val m = cal.get(java.util.Calendar.MINUTE) + cal.get(java.util.Calendar.SECOND) / 60f
                roomPainter.clockHands(cv, cx, cy, r, cal.get(java.util.Calendar.HOUR) + m / 60f, m)
            }
            val place = roomPlace(now)
            val base = s * 0.19f * place.factor
            // furniture stands at fixed spots; whatever is further back is drawn first so the pet can walk in front of it
            val items = listOfNotNull(
                plantSprite?.let { 0.3f to it },
                lampSprite?.let { 0.26f to it },
            )
            items.filter { it.first <= place.v }.forEach { cv.drawBitmap(it.second.bmp, it.second.left, it.second.top, blit) }
            drawPet(cv, place, base, now)
            items.filter { it.first > place.v }.forEach { cv.drawBitmap(it.second.bmp, it.second.left, it.second.top, blit) }
        } else {
            drawPet(cv, overlayPlace(w, h), s * 0.27f, now)
            drawCarry(cv, w, h)
        }
    }

    /** the badge of the thing the small pet has just fetched */
    private fun drawCarry(cv: Canvas, w: Float, h: Float) {
        val e = carry ?: return
        val r = min(w, h) * 0.2f; val cx = w * 0.8f; val cy = h * 0.2f
        ink.circle(cv, cx, cy + r * 0.12f, r * 1.04f, 0x33281E14)
        ink.circle(cv, cx, cy, r, 0xFFFFFCF6.toInt())
        ink.circleOutline(cv, cx, cy, r, Look.COCOA, max(1.5f * d, r * 0.1f))
        emojiPaint.textSize = r * 1.15f
        cv.drawText(e, cx, cy + r * 0.4f, emojiPaint)
    }

    private fun drawPet(cv: Canvas, p: Place, unit: Float, now: Long) {
        val t = phase * 2 * PI.toFloat()
        // the size follows the growth stage
        val grow = when (stage) { GrowthStage.EGG -> 0.9f; GrowthStage.INFANT -> 0.78f; GrowthStage.REBEL -> 0.97f; GrowthStage.ADULT -> 1.12f }
        val bw = unit * grow; val bh = unit * 0.833f * grow
        val back = p.back

        // motion: a real step (alternate feet, a bob and a waddle) while walking; otherwise the mood's idle motion
        var dx = 0f; var dy = 0f; var sx = 1f; var sy = 1f; var tilt = p.lean
        var stepT = 0f
        when {
            p.walking -> {
                stepT = (now % STEP_MS) / STEP_MS.toFloat() * 2f * PI.toFloat()
                dx = sin(stepT) * bw * 0.04f; dy = -abs(sin(stepT)) * bh * 0.1f; tilt += sin(stepT) * 3f
            }
            else -> when (state) {
                PetState.NORMAL -> { val b = abs(sin(t)); dy = -b * bh * 0.27f; sy = 1f + (0.5f - b) * 0.06f; sx = 1f - (0.5f - b) * 0.05f }
                PetState.ANGRY -> { dx = sin(t * 8) * bw * 0.035f; sy = 1f + sin(t * 4) * 0.02f }                  // quick shaking
                PetState.HUNGRY -> { sy = 1f + sin(t) * 0.015f; dy = bh * 0.07f }                                  // slumped
                PetState.HURT -> { tilt += sin(t) * 2.5f; dy = bh * 0.09f }                                        // slow sway
            }
        }

        val cx = p.x + dx
        // a soft shadow on the floor
        cv.save(); cv.translate(cx, p.ground); cv.scale(bw * 1.05f, bw * 1.05f * 0.16f); cv.drawCircle(0f, 0f, 1f, shadowPaint); cv.restore()

        cv.save()
        cv.translate(cx, p.ground + dy)
        cv.scale(sx, sy)
        if (tilt != 0f) cv.rotate(tilt)
        cv.scale(bw, bw)   // from here on: body units (see PetPainter)
        val ow = max(1.6f * d / bw, 0.058f)
        painter.draw(cv, state, stage, outfit, back, p.walking, stepT, ow, now, sticker = !showScene)
        cv.restore()
        drawBurst(cv, cx, p.ground - bh * 1.2f, bw * 3.2f)
        if (showScene) drawBubble(cv, p.x, p.ground, bw, bh, now)
    }

    /**
     * The bubble sits beside the pet's head (on the right when there is room, otherwise on the left), with a small tail
     * pointing at it, and moves with the pet. It stays below the top bar and inside the screen.
     */
    private fun drawBubble(cv: Canvas, px: Float, ground: Float, bw: Float, bh: Float, now: Long) {
        val text = bubbleText ?: return
        val left = bubbleUntil - now
        if (left <= 0) { bubbleText = null; return }
        val alpha = if (left < 400) (255 * left / 400f).toInt() else 255

        var size = vh * 0.043f
        textPaint.textSize = size
        var tw = textPaint.measureText(text)
        val maxW = vw * 0.5f
        if (tw > maxW) { size *= maxW / tw; textPaint.textSize = size; tw = maxW }
        val padX = size * 0.85f; val padY = size * 0.62f
        val wBubble = tw + padX * 2; val hBubble = size + padY * 2
        val tail = size * 0.8f
        val headY = ground - bh * 1.5f
        val midY = headY.coerceIn(vh * 0.2f + hBubble / 2f, vh * 0.8f)

        val onRight = px + bw + tail + wBubble <= vw * 0.97f
        val x0 = if (onRight) px + bw + tail else (px - bw - tail - wBubble).coerceAtLeast(vw * 0.02f)
        val top = midY - hBubble / 2f
        val tipX = if (onRight) x0 - tail else x0 + wBubble + tail
        bubbleOutline(x0, top, wBubble, hBubble, onRight, tipX, midY + tail * 0.28f)

        // a soft shadow, then the bubble with its cocoa line (one outline with the tail, like a comic)
        cv.save(); cv.translate(0f, 4f * d); ink.fill.shader = null; ink.fill.color = Color.argb((50 * alpha / 255f).toInt(), 110, 70, 30); cv.drawPath(bubblePath, ink.fill); cv.restore()
        ink.fill.color = Color.argb(alpha, 255, 253, 248); cv.drawPath(bubblePath, ink.fill)
        ink.line.color = Look.alpha(Look.COCOA, alpha / 255f); ink.line.strokeWidth = 2f * d; cv.drawPath(bubblePath, ink.line)
        textPaint.alpha = alpha
        cv.drawText(text, x0 + padX, top + padY + size * 0.82f, textPaint)
        postInvalidateOnAnimation()
    }

    /** a pill-shaped bubble with its tail as one outline */
    private fun bubbleOutline(x0: Float, top: Float, wB: Float, hB: Float, tailLeft: Boolean, tipX: Float, tipY: Float) {
        val r = hB / 2f; val cy = top + r; val lx = x0 + r; val rx = x0 + wB - r; val dDeg = 24f
        val p = bubblePath; p.reset(); p.moveTo(lx, top); p.lineTo(rx, top)
        if (tailLeft) {
            p.arcTo(rx - r, top, rx + r, top + hB, -90f, 180f, false); p.lineTo(lx, top + hB)
            p.arcTo(lx - r, top, lx + r, top + hB, 90f, 90f - dDeg, false); p.lineTo(tipX, tipY)
            val a = Math.toRadians((180f + dDeg).toDouble()); p.lineTo(lx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
            p.arcTo(lx - r, top, lx + r, top + hB, 180f + dDeg, 90f - dDeg, false)
        } else {
            p.arcTo(rx - r, top, rx + r, top + hB, -90f, 90f - dDeg, false); p.lineTo(tipX, tipY)
            val a = Math.toRadians(dDeg.toDouble()); p.lineTo(rx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
            p.arcTo(rx - r, top, rx + r, top + hB, dDeg, 90f - dDeg, false); p.lineTo(lx, top + hB)
            p.arcTo(lx - r, top, lx + r, top + hB, 90f, 180f, false)
        }
        p.close()
    }

    /** butter-yellow sparkles rising (good) or sky-blue drops falling (bad) */
    private fun drawBurst(cv: Canvas, cx: Float, y: Float, s: Float) {
        val age = System.currentTimeMillis() - burstStart
        if (burstStart == 0L || age > 1100) return
        val k = age / 1100f
        val a = 1f - k * 0.6f
        for (i in 0 until 7) {
            val ang = (i / 7f) * 2 * PI.toFloat() + 0.3f
            val r = s * (0.3f + 0.24f * k)
            val px = cx + cos(ang) * r
            val py = y + (if (burstGood) -k * s * 0.18f else k * s * 0.18f) + sin(ang) * r * 0.6f
            if (burstGood) {
                Pics.sparkle(cv, ink, px, py, s * (if (i % 2 == 1) 0.05f else 0.07f) * (1f - k * 0.3f), Look.alpha(0xFFFFD95A.toInt(), a))
                Pics.sparkle(cv, ink, px, py, s * 0.025f, Look.alpha(Look.WHITE, a))
            } else {
                ink.path.reset(); ink.path.moveTo(px, py - s * 0.05f); ink.path.quadTo(px + s * 0.035f, py, px, py + s * 0.03f); ink.path.quadTo(px - s * 0.035f, py, px, py - s * 0.05f); ink.path.close()
                ink.fillPath(cv, ink.path, Look.alpha(0xFF8FD0FF.toInt(), a))
            }
        }
        postInvalidateOnAnimation()
    }

    private companion object {
        const val ROOM_DEPTH = 0.55f     // how far the back wall is: 1 = at the screen, 0 = infinitely far
        const val WALK_SLOT_MS = 8_000L
        const val WALK_SPEED = 0.4f      // floor units per second
        const val DEPTH_COST = 1.4f      // walking into the depth of the room counts for more than sideways
        const val STEP_MS = 760L         // one full step cycle (both feet)
    }
}
