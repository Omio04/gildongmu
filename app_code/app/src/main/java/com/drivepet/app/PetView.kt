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
import android.view.MotionEvent
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
 * How the small floating pet moves. In gear: WALK_BACK while the car moves (it walks on the spot), STAND_BACK when it has
 * stopped; STAND when the car is not in gear. It is always drawn from the front now (the names are from when it was seen
 * from behind while driving).
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
        set(v) {
            // the egg has just hatched in front of us: the baby bounces for joy for a moment before it settles down
            if (stageKnown && field == GrowthStage.EGG && v == GrowthStage.INFANT && showScene && isAttachedToWindow) { hatchUntil = System.currentTimeMillis() + HATCH_MS; burst(true) }
            stageKnown = true
            field = v; invalidate()
        }
    /** false while the egg is still whole (the first km): it only sleeps. True once its face is out: it bounces now and then */
    var eggAwake: Boolean = true
        set(v) {
            // the face has just come out in front of us: a burst of sparkles and a few happy hops in a row
            if (stageKnown && !field && v && stage == GrowthStage.EGG && showScene && isAttachedToWindow) { hatchUntil = System.currentTimeMillis() + HATCH_MS; burst(true) }
            field = v; invalidate()
        }
    private var stageKnown = false
    private var hatchUntil = 0L
    /** the emoji of the thing the pet has just fetched (small pet only), drawn as a badge */
    var carry: DexItem? = null
        set(v) { field = v; invalidate() }
    /** the car is low on energy: 1 = a fuel pump, 2 = a battery, pulsing beside the pet's head (0 = nothing) */
    var energyBadge: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }
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

    /**
     * How much of the room's right side is hidden right now, 0..1 of the width (the menu card slides in over it).
     * The pet keeps to the part that can still be seen, so clothes can be tried on with the pet in view.
     */
    var coveredRight: Float = 0f
        set(v) { field = v.coerceIn(0f, 0.8f); invalidate() }
    private var coverK = 0f          // 0..1, eased towards "covered" so the pet slides aside instead of jumping
    private var coverF = 1f          // how far the floor's width is squeezed to the left when fully covered
    private var lastFrame = 0L

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
    private var pokeUntil = 0L
    /** called when the pet is touched (the screen picks what it says) */
    var onPoke: (() -> Unit)? = null
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

    /** touching the pet: it wiggles for a moment, sparkles and says something (not while it is being introduced) */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!showScene) return false
        if (e.action != MotionEvent.ACTION_UP) return e.action == MotionEvent.ACTION_DOWN
        if (stayStill) return true
        val now = System.currentTimeMillis()
        val pl = roomPlace(now)
        val bw = min(vw, vh) * 0.19f * pl.factor * growOf(stage)
        if (abs(e.x - pl.x) <= bw * PetPainter.halfWidth(stage) * 1.1f && e.y <= pl.ground + bw * 0.2f && e.y >= pl.ground - bw * PetPainter.height(stage) * 1.05f) {
            pokeUntil = now + 800L
            burst(true)
            onPoke?.invoke()
            invalidate()
        }
        return true
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
        val night = Night.isDark(context)
        val key = "${w.toInt()}x${h.toInt()}|$hurt|$night|${furniture.sorted().joinToString(",")}"
        if (key == roomKey && roomBmp != null) return
        freeRoom()
        val g = geo ?: return
        roomPainter.hurt = hurt; roomPainter.night = night
        val bmp = Bitmap.createBitmap(max(1, w.toInt()), max(1, h.toInt()), Bitmap.Config.ARGB_8888)
        roomPainter.paintRoom(Canvas(bmp), g, furniture)
        roomBmp = bmp
        if ("plant" in furniture) { val p = floorPoint(-0.82f, 0.3f); plantSprite = roomPainter.plantSprite(p.first, p.second, h * 0.1f * p.third) }
        // the floor lamp is part of the room (not a shop item): off by day, lit in dark mode
        run { val p = floorPoint(RoomPainter.LAMP_U, RoomPainter.LAMP_V); lampSprite = roomPainter.lampSprite(p.first, p.second, h * 0.1f * p.third, night) }
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
    /** the floor's left-right coordinate, squeezed into the part of the room the menu card leaves free */
    private fun seenU(u: Float) = lerp(u, -0.8f + (u + 0.8f) * coverF, coverK)

    private fun roomPlace(now: Long): Place {
        // the egg does not move: it sits in its nest in the middle of the room (the intro keeps any stage still, too).
        // the baby cannot walk yet either: it sits where it hatched. Only the rebel and the grown bear go round the room.
        if (stayStill || stage == GrowthStage.EGG || stage == GrowthStage.INFANT) { val p = floorPoint(seenU(0f), 0.5f); return Place(p.first, p.second, p.third, 0.5f, false, false, 0f) }
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
        val p = floorPoint(seenU(u), v)
        // going deeper into the room: seen from behind. Otherwise facing us, leaning a little the way it goes.
        val away = walking && dv < 0f && abs(dv) * DEPTH_COST > abs(du) * 0.7f
        val lean = if (walking) (du / dist).coerceIn(-1f, 1f) * 4f else 0f
        return Place(p.first, p.second, p.third, v, walking, away, lean)
    }

    // the small pet over the navigation always faces us (its face and mood can be read at a glance); it walks while the car moves
    private fun overlayPlace(w: Float, h: Float) = Place(w / 2f, h * 0.9f, 1f, 1f, pose == Pose.WALK_BACK, false, 0f)

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
            // ease the pet out from under the menu card (and back) in about a third of a second
            val target = if (coveredRight > 0f) 1f else 0f
            if (coveredRight > 0f) coverF = ((2f * (1f - coveredRight) - 1f - 0.22f + 0.8f) / 1.6f).coerceIn(0.3f, 1f)
            if (coverK != target) { val step = ((now - lastFrame).coerceIn(0L, 100L)) / 320f; coverK = if (target > coverK) min(target, coverK + step) else max(target, coverK - step) }
            lastFrame = now
            val place = roomPlace(now)
            updateIdle(now, place.walking)
            val base = s * 0.19f * place.factor
            // furniture stands at fixed spots; whatever is further back is drawn first so the pet can walk in front of it
            val items = listOfNotNull(
                plantSprite?.let { 0.3f to it },
                lampSprite?.let { RoomPainter.LAMP_V to it },
            )
            items.filter { it.first <= place.v }.forEach { cv.drawBitmap(it.second.bmp, it.second.left, it.second.top, blit) }
            val inNest = stage == GrowthStage.EGG
            if (inNest) drawNest(cv, place, base * EGG_GROW, false)
            drawPet(cv, place, base, now)
            if (inNest) drawNest(cv, place, base * EGG_GROW, true)
            items.filter { it.first > place.v }.forEach { cv.drawBitmap(it.second.bmp, it.second.left, it.second.top, blit) }
            // the speech bubble last: in front of every piece of furniture, even when the pet stands far back in the room
            val g = growOf(stage)
            if (energyBadge != 0) drawEnergy(cv, place.x - base * g * PetPainter.halfWidth(stage) * 1.05f, place.ground - base * g * (PetPainter.height(stage) - 0.35f), base * g * 0.34f, now)
            drawBubble(cv, place.x, place.ground - base * g * PetPainter.faceY(stage), base * g * PetPainter.halfWidth(stage), now)
        } else {
            drawPet(cv, overlayPlace(w, h), s * 0.29f, now)
            drawCarry(cv, w, h)
            if (energyBadge != 0) drawEnergy(cv, w * 0.19f, h * 0.2f, s * 0.115f, now)
        }
    }

    // ---- idle moments: now and then the pet yawns, hiccups (infant), rocks in its sleep (egg) or mutters something.
    // What it does is decided once per time slot, only while it rests, calm and not already talking.
    private var idle: Idle? = null
    private var idleStart = 0L
    private var idleSlot = -1L

    private fun idleProgress(now: Long): Float {
        val a = idle ?: return 0f
        return if (a.durationMs <= 0L) 0f else ((now - idleStart).toFloat() / a.durationMs).coerceIn(0f, 1f)
    }
    /** 0 -> 1 -> 0 over the moment: how far the yawn is open */
    private fun idleK(now: Long) = sin(idleProgress(now) * PI.toFloat())

    private fun updateIdle(now: Long, walking: Boolean) {
        val a = idle
        if (a != null && now - idleStart >= a.durationMs) idle = null
        if (idle != null || walking || stayStill || !showScene || now < hatchUntil) return
        val slot = now / IDLE_SLOT_MS
        if (slot == idleSlot) return
        idleSlot = slot
        if (bubbleText != null && bubbleUntil > now) return              // already talking
        if (state != PetState.NORMAL && stage != GrowthStage.EGG) return // hungry, angry or hurt: no idling
        val r = (((slot * 2654435761L) ushr 5) and 0x7FFFFFL) % 100
        val act = when (stage) {
            GrowthStage.EGG -> if (r < 25) Idle.WOBBLE else null
            GrowthStage.INFANT -> when { r < 22 -> Idle.YAWN; r < 40 -> Idle.HICCUP; r < 58 -> Idle.MUTTER; else -> null }
            else -> when { r < 22 -> Idle.YAWN; r < 48 -> Idle.MUTTER; else -> null }
        } ?: return
        idle = act; idleStart = now
        Voice.idle(stage, act)?.let { say(it, if (act == Idle.MUTTER) 4_500L else max(act.durationMs, 1_800L)) }
    }

    // the designer's sheets already draw the baby and the rebel smaller than the grown bear; this only evens them out
    private fun growOf(st: GrowthStage) = when (st) { GrowthStage.EGG -> EGG_GROW; GrowthStage.INFANT -> 0.88f; GrowthStage.REBEL -> 0.87f; GrowthStage.ADULT -> 0.9f }

    /**
     * Where the top of the pet's head is on the screen (the first-run bubble sits right above it).
     * The pet stands still in the middle of the room while it is an egg or while the intro is shown.
     */
    fun petTopY(): Float {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return h * 0.55f
        if (w != vw || h != vh) layoutRoom(w, h)   // the room's geometry may not have been laid out yet
        val p = floorPoint(0f, 0.5f)
        val bw = min(vw, vh) * 0.19f * p.third * growOf(stage)
        return p.second - (PetPainter.height(stage) + (if (Slot.HAT in outfit) 0.4f else 0f)) * bw   // a hat stands a little higher than the ears
    }

    // ---- the egg's nest of straw
    private val strawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val nestFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun straw(c: Int) = if (Night.isDark(context)) Color.rgb((Color.red(c) * 0.5f).toInt(), (Color.green(c) * 0.46f).toInt(), (Color.blue(c) * 0.5f).toInt()) else c

    /** a round nest of straw. The back half is drawn before the egg, the front rim after it, so the egg sits in it */
    private fun drawNest(cv: Canvas, p: Place, bw: Float, front: Boolean) {
        val cx = p.x; val cy = p.ground - bw * 0.36f
        val rx = bw * 1.34f; val ry = bw * 0.36f
        val ow = max(1.4f * d, bw * 0.03f)
        val light = straw(0xFFF3CE74.toInt()); val mid = straw(0xFFE5B24F.toInt()); val dark = straw(0xFFC98F34.toInt())
        if (!front) {
            // a soft shadow under the nest, then the bowl and its dark hollow
            cv.save(); cv.translate(cx, p.ground); cv.scale(rx * 1.08f, rx * 1.08f * 0.16f); cv.drawCircle(0f, 0f, 1f, shadowPaint); cv.restore()
            rect.set(cx - rx, cy - ry, cx + rx, cy + ry * 0.92f)
            nestFill.color = mid; cv.drawOval(rect, nestFill)
            ink.line.color = Look.alpha(Look.COCOA, 0.9f); ink.line.strokeWidth = ow; cv.drawOval(rect, ink.line)
            rect.set(cx - rx * 0.86f, cy - ry * 0.78f, cx + rx * 0.86f, cy + ry * 0.38f)
            nestFill.color = straw(0xFFB07A2C.toInt()); cv.drawOval(rect, nestFill)
            // straws standing up around the back of the rim
            strawPaint.strokeWidth = bw * 0.04f
            for (i in 0 until 30) {
                val a = PI.toFloat() + i / 29f * PI.toFloat()
                val px = cx + cos(a) * rx * 0.93f; val py = cy - ry * 0.05f + sin(a) * ry * 0.88f
                val len = bw * (0.12f + 0.1f * ((i * 7) % 5) / 4f); val j = (((i * 37) % 9) - 4) / 4f * 0.5f
                strawPaint.color = when (i % 3) { 0 -> light; 1 -> dark; else -> mid }
                cv.drawLine(px, py, px + (cos(a) * 0.7f + j) * len, py + sin(a) * len * 1.1f, strawPaint)
            }
        } else {
            // the front rim: a thick band over the egg's feet, made of short slanting straws
            val ryF = ry * 0.75f; val cyF = cy + ry * 0.1f
            rect.set(cx - rx, cyF - ryF, cx + rx, cyF + ryF)
            strawPaint.color = mid; strawPaint.strokeWidth = bw * 0.17f
            cv.drawArc(rect, 8f, 164f, false, strawPaint)
            for (i in 0 until 36) {
                val a = (8f + i / 35f * 164f) * PI.toFloat() / 180f
                val px = cx + cos(a) * rx; val py = cyF + sin(a) * ryF
                val j = (((i * 53) % 7) - 3) / 3f
                strawPaint.strokeWidth = bw * 0.04f
                strawPaint.color = when (i % 3) { 0 -> light; 1 -> dark; else -> mid }
                val dx = bw * 0.1f * (if (i % 2 == 0) 1f else -1f)
                cv.drawLine(px - dx, py - bw * 0.07f + j * bw * 0.02f, px + dx, py + bw * 0.07f - j * bw * 0.02f, strawPaint)
            }
            ink.line.color = Look.alpha(Look.COCOA, 0.9f); ink.line.strokeWidth = ow
            rect.set(cx - rx, cyF - ryF - bw * 0.085f, cx + rx, cyF + ryF + bw * 0.085f); cv.drawArc(rect, 8f, 164f, false, ink.line)
        }
    }

    /** the low fuel / battery mark, breathing a little so it is noticed */
    private fun drawEnergy(cv: Canvas, cx: Float, cy: Float, r: Float, now: Long) {
        val k = 1f + 0.07f * sin(now / 260f)
        cv.save(); cv.scale(k, k, cx, cy)
        Pics.energy(cv, ink, cx, cy, r, energyBadge == 2)
        cv.restore()
    }

    /** the thing the small pet has just fetched, held up as a sticker */
    private fun drawCarry(cv: Canvas, w: Float, h: Float) {
        val item = carry ?: return
        val r = min(w, h) * 0.2f
        ItemArt.sticker(cv, item, w * 0.78f, h * 0.22f, r * 2f, 0xFFFFFFFF.toInt(), d)
    }

    private fun drawPet(cv: Canvas, p: Place, unit: Float, now: Long) {
        val t = phase * 2 * PI.toFloat()
        // the size follows the growth stage
        val grow = growOf(stage)
        val bw = unit * grow; val bh = bw * PetPainter.height(stage) * 0.45f   // bh: about half the pet's height, the measure of its motions
        val back = p.back

        // motion: a real step (alternate feet, a bob and a waddle) while walking; otherwise the mood's idle motion
        var dx = 0f; var dy = 0f; var sx = 1f; var sy = 1f; var tilt = p.lean
        var stepT = 0f
        when {
            now < pokeUntil && showScene -> { val k = (pokeUntil - now) / 800f; tilt += sin(k * 16f) * 6f * k; sx = 1f + 0.05f * k; sy = 1f - 0.05f * k }   // a wiggle when touched
            (stage == GrowthStage.INFANT && (p.walking || (showScene && now < hatchUntil))) ||
                (stage == GrowthStage.EGG && eggAwake && showScene && !stayStill && (now < hatchUntil || now % EGG_HOP_EVERY_MS < 2 * HOP_MS)) -> {
                // the egg with its face out cannot sit still: every few seconds it hops twice in its nest.
                // a baby does not walk yet: just out of the egg (and riding along in the car) it bounces on the spot,
                // squashing a little each time it lands
                val k = (now % HOP_MS) / HOP_MS.toFloat()
                val up = sin(k * PI.toFloat())
                dy = -up * bh * 0.32f
                val land = if (k < 0.12f) 1f - k / 0.12f else if (k > 0.9f) (k - 0.9f) / 0.1f else 0f
                sy = 1f + up * 0.06f - land * 0.12f; sx = 1f - up * 0.04f + land * 0.1f
            }
            p.walking -> {
                stepT = (now % STEP_MS) / STEP_MS.toFloat() * 2f * PI.toFloat()
                dx = sin(stepT) * bw * 0.02f; tilt += sin(stepT) * 1.6f   // a plodding walk: the feet take turns, the body does not bob or hop
            }
            idle == Idle.WOBBLE && showScene && !(stage == GrowthStage.EGG && eggAwake) -> {   // a dream: the egg rocks in its nest for a moment
                val k = idleProgress(now); val b = sin(t)
                tilt += sin(k * 6f * PI.toFloat()) * 4.5f * (1f - k); sy = 1f + b * 0.018f; sx = 1f - b * 0.012f
            }
            stage == GrowthStage.EGG && showScene -> { val b = sin(t); sy = 1f + b * 0.018f; sx = 1f - b * 0.012f }   // asleep: it only breathes
            idle == Idle.YAWN && showScene -> { val k = idleK(now); sy = 1f + 0.07f * k; sx = 1f - 0.03f * k; tilt -= 3f * k }   // stretches up with the yawn
            idle == Idle.HICCUP && showScene -> { val k = idleProgress(now); sy = 1f + 0.05f * abs(sin(k * 2.5f * PI.toFloat())); sx = 1f - 0.03f * abs(sin(k * 2.5f * PI.toFloat())) }   // two little jolts in place, no jumping
            else -> when (state) {
                PetState.NORMAL -> { val b = sin(t); sy = 1f + b * 0.012f; sx = 1f - b * 0.008f }   // resting: it only breathes, no bouncing
                PetState.ANGRY -> { dx = sin(t * 8) * bw * 0.035f; sy = 1f + sin(t * 4) * 0.02f }                  // quick shaking
                PetState.HUNGRY -> { sy = 1f + sin(t) * 0.015f; dy = bh * 0.07f }                                  // slumped
                PetState.HURT -> { tilt += sin(t) * 2.5f; dy = bh * 0.09f }                                        // slow sway
            }
        }

        val cx = p.x + dx
        // a soft shadow on the floor
        val air = if (dy < 0f) 1f + dy / (bh * 0.32f) * 0.35f else 1f   // the shadow shrinks while the baby is up in the air
        val shadow = bw * (if (stage == GrowthStage.EGG) 1.05f else if (stage == GrowthStage.INFANT) 0.95f else 0.78f)   // the grown bear stands on small feet
        if (!(stage == GrowthStage.EGG && showScene)) { cv.save(); cv.translate(cx, p.ground); cv.scale(shadow * air, shadow * 0.17f * air); cv.drawCircle(0f, 0f, 1f, shadowPaint); cv.restore() }

        cv.save()
        cv.translate(cx, p.ground + dy)
        cv.scale(sx, sy)
        if (tilt != 0f) cv.rotate(tilt)
        cv.scale(bw, bw)   // from here on: body units (see PetPainter)
        val ow = max(1.6f * d / bw, 0.047f)   // the designer's line: 5 of the 512 art pixels
        painter.eggAwake = eggAwake
        painter.yawn = if (idle == Idle.YAWN && showScene) idleK(now) else 0f
        painter.draw(cv, state, stage, outfit, back, p.walking && stage != GrowthStage.INFANT, stepT, ow, now, sticker = !showScene)
        cv.restore()
        drawBurst(cv, cx, p.ground - bw * PetPainter.height(stage) * 0.55f, bw * 3.4f)
    }

    /**
     * The bubble sits beside the pet's head (on the right when there is room, otherwise on the left), with a small tail
     * pointing at it, and moves with the pet. It stays below the top bar and inside the screen.
     */
    private fun drawBubble(cv: Canvas, px: Float, headY: Float, bw: Float, now: Long) {
        val text = bubbleText ?: return
        val left = bubbleUntil - now
        if (left <= 0) { bubbleText = null; return }
        val alpha = if (left < 400) (255 * left / 400f).toInt() else 255

        var size = vh * 0.043f
        textPaint.textSize = size
        val tail = size * 0.8f
        // The bubble must fit beside the pet, never on it. It used to be pushed against the screen edge when it was too wide
        // for both sides, and then it covered the pet's face. So: use the side with more room; if the text does not fit there
        // in one line, break it into two; if it still does not fit, make the letters smaller.
        // the menu card slides over the right part of the room: the bubble must stay left of it (it was cut off by the shop card)
        val rightLimit = min(vw * 0.97f, vw * (1f - coveredRight) - vw * 0.02f)
        val rightRoom = rightLimit - (px + bw + tail)
        val leftRoom = (px - bw - tail) - vw * 0.02f
        val room = max(rightRoom, leftRoom)
        val padFactor = 1.7f                                           // the two paddings together, in letter sizes
        val cap = min(room, vw * 0.5f + size * padFactor)
        var lines = listOf(text)
        var tw = textPaint.measureText(text)
        if (tw + size * padFactor > cap) {
            lines = splitInTwo(text)
            tw = lines.maxOf { textPaint.measureText(it) }
        }
        if (tw + size * padFactor > cap) {
            val s = (cap / (tw + size * padFactor)).coerceAtLeast(0.3f)
            size *= s; textPaint.textSize = size; tw *= s
        }
        val padX = size * 0.85f; val padY = size * 0.62f
        val lineH = size * 1.2f
        val wBubble = tw + padX * 2; val hBubble = size + (lines.size - 1) * lineH + padY * 2
        val midY = headY.coerceIn(vh * 0.2f + hBubble / 2f, vh * 0.8f)

        val onRight = px + bw + tail + wBubble <= rightLimit
        val x0 = if (onRight) px + bw + tail else (px - bw - tail - wBubble).coerceAtLeast(vw * 0.02f)
        val top = midY - hBubble / 2f
        val tipX = if (onRight) x0 - tail else x0 + wBubble + tail
        bubbleOutline(x0, top, wBubble, hBubble, onRight, tipX, midY + tail * 0.28f)

        // a soft shadow, then the bubble with its cocoa line (one outline with the tail, like a comic)
        cv.save(); cv.translate(0f, 4f * d); ink.fill.shader = null; ink.fill.color = Color.argb((50 * alpha / 255f).toInt(), 110, 70, 30); cv.drawPath(bubblePath, ink.fill); cv.restore()
        ink.fill.color = Color.argb(alpha, 255, 253, 248); cv.drawPath(bubblePath, ink.fill)
        ink.line.color = Look.alpha(Look.COCOA, alpha / 255f); ink.line.strokeWidth = 2f * d; cv.drawPath(bubblePath, ink.line)
        textPaint.alpha = alpha
        lines.forEachIndexed { i, line -> cv.drawText(line, x0 + padX, top + padY + size * 0.82f + i * lineH, textPaint) }
        postInvalidateOnAnimation()
    }

    /** the text in two lines of about the same length, broken at a " · " or a space near the middle (or in the middle) */
    private fun splitInTwo(s: String): List<String> {
        val mid = s.length / 2
        val breaks = s.indices.filter { s[it] == ' ' }
        val at = breaks.minByOrNull { kotlin.math.abs(it - mid) } ?: return listOf(s.substring(0, mid), s.substring(mid))
        val first = s.substring(0, at).trim().trimEnd('·').trim()
        val second = s.substring(at + 1).trim().trimStart('·').trim()
        return if (first.isEmpty() || second.isEmpty()) listOf(s.substring(0, mid), s.substring(mid)) else listOf(first, second)
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
        const val WALK_SLOT_MS = 12_000L
        const val HOP_MS = 520L          // one hop of the baby
        const val WALK_SPEED = 0.22f     // floor units per second (a slow walk)
        const val DEPTH_COST = 1.4f      // walking into the depth of the room counts for more than sideways
        const val STEP_MS = 1_150L       // one full step cycle (both feet)
        const val EGG_GROW = 0.68f       // the egg's size factor
        const val EGG_HOP_EVERY_MS = 4_160L   // the awake egg hops twice, then rests (8 hop lengths in all)
        const val HATCH_MS = 5_200L      // how long the baby bounces right after hatching (ten hops)
        const val IDLE_SLOT_MS = 9_000L  // how often the pet may start an idle moment
    }
}
