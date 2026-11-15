package com.drivepet.app

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The small pet that floats over other screens (the navigation, music...) while the pet's own screen is not showing.
 *  - it always faces the driver. Walking while the car moves; when the car stops it simply stops where it is.
 *  - with the navigation on and the car in gear, it stays next to the car on the map ("follow the car");
 *    otherwise it waits at the right edge, between the corner buttons most map screens keep there.
 *  - when it finds something for the map collection it runs off and is gone for a moment, then comes back carrying the thing,
 *    and a card beside it says what it is (see [applyFetch]). Nothing is shown before it is back: the find is a surprise.
 *  - a short card also appears for the things the car tells us: a new region, the seat belt, rain, low fuel or battery
 *    (see [PetAlert]). A card is one short line and a smaller one, shown for a few seconds, never touchable.
 * Drag to move it (beside the car, the new spot is remembered); tap it to bring the pet's screen back, which is
 * only possible while parked (see [PetHub.screenAllowed]).
 * Needs the "display over other apps" permission (SYSTEM_ALERT_WINDOW); without it [canShow] is false and nothing is drawn.
 * Whether the Pleos app market allows this permission for a published app is NOT confirmed.
 *
 * The car's position on the navigation's map is not available to us (it is another app's screen), so "next to the car"
 * means the spot where this navigation draws the car marker (the car stays fixed on screen while the map moves).
 * That spot is a default for the emulator's navigation and it changes with the navigation's view mode; it is
 * adjustable by dragging.
 */
class PetOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private val pet = PetView(ctx).apply { showScene = false }
    private val lp = WindowManager.LayoutParams(
        dp(SIZE_PARKED), dp(SIZE_PARKED),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // LAYOUT_IN_SCREEN: coordinates start at the top of the screen, not below the system bar
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // resting spot: anchored to the right edge, no screen-width arithmetic
        gravity = Gravity.TOP or Gravity.END
        x = dp(10); y = dp(250)
    }

    private var shown = false
    private var following = false
    private val anim = Handler(Looper.getMainLooper())
    private val animTick = object : Runnable {
        override fun run() {
            val fetchBusy = applyFetch()
            val cardBusy = applyCard()
            if ((fetchBusy || cardBusy) && shown) anim.postDelayed(this, 40)
        }
    }

    // ---- where the pet stands: a base spot (next to the car, or the resting spot) plus the fetch's offset on top of it
    private var restX = dp(10); private var restY = dp(250)
    private var fetchDx = 0; private var fetchDy = 0
    private var fetching = false

    /** puts the window at the base spot of the current mode plus the fetch offset */
    private fun place() {
        val size = lp.width
        if (following) {
            val (sw, sh) = screenSize()
            val spot = Store.loadFollowSpot(ctx) ?: (DEFAULT_FOLLOW_X to DEFAULT_FOLLOW_Y)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (sw * spot.first - size / 2f).toInt() + fetchDx
            lp.y = (sh * spot.second - size / 2f).toInt() + fetchDy
        } else {
            lp.gravity = Gravity.TOP or Gravity.END   // resting spot: anchored to the right edge; x grows to the left
            lp.x = restX - fetchDx
            lp.y = restY + fetchDy
        }
        if (shown) try { wm.updateViewLayout(pet, lp) } catch (_: Exception) {}
    }

    /** the middle of the pet's base spot (without the fetch offset) and its top edge, in screen pixels from the top left */
    private fun baseSpot(): Triple<Int, Int, Int> {
        val (sw, _) = screenSize()
        return if (following) Triple(lp.x - fetchDx + lp.width / 2, lp.y - fetchDy, sw)
        else Triple(sw - (lp.x + fetchDx) - lp.width / 2, lp.y - fetchDy, sw)
    }

    // ---------------------------------------------------------------- "be right back": three dots where the pet stood

    /** shown at the pet's spot while it is away fetching, so the driver sees it is coming back */
    private class Dots(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val r = RectF()
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
            val ph = h * 0.5f
            r.set(w * 0.08f, h / 2f - ph / 2f, w * 0.92f, h / 2f + ph / 2f)
            fill.color = 0x33603C1C; cv.drawRoundRect(r.left, r.top + 3f * d, r.right, r.bottom + 3f * d, ph / 2f, ph / 2f, fill)
            fill.color = 0xFFFFFDF8.toInt(); cv.drawRoundRect(r, ph / 2f, ph / 2f, fill)
            line.color = Look.COCOA; line.strokeWidth = 2.2f * d; cv.drawRoundRect(r, ph / 2f, ph / 2f, line)
            val t = SystemClock.elapsedRealtime()
            fill.color = Look.COCOA
            for (i in 0..2) {
                val up = max(0f, sin((t / 160.0 - i * 0.9).toFloat())) * ph * 0.2f
                cv.drawCircle(w * (0.32f + i * 0.18f), h / 2f - up, ph * 0.11f, fill)
            }
        }
    }
    private val dots = Dots(ctx)
    private val dotsLp = WindowManager.LayoutParams(dp(92), dp(60), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
    private var dotsShown = false

    private fun showDots(alpha: Float) {
        val (cx, top, _) = baseSpot()
        dotsLp.x = cx - dotsLp.width / 2; dotsLp.y = top + lp.height / 2 - dotsLp.height / 2
        dots.alpha = alpha; dots.invalidate()
        try { if (!dotsShown) { wm.addView(dots, dotsLp); dotsShown = true } else wm.updateViewLayout(dots, dotsLp) } catch (_: Exception) {}
    }
    private fun hideDots() { if (dotsShown) { try { wm.removeView(dots) } catch (_: Exception) {}; dotsShown = false } }

    // ---------------------------------------------------------------- the card: what the pet found, or what the car says

    /** one short notice: a picture, a line and a smaller line under it */
    private class Card(ctx: Context) : View(ctx) {
        var item: DexItem? = null
        var icon: AlertIcon? = null
        var title = ""
        var sub = ""
        var accent = Look.PERI_DEEP
        private val ink = Ink()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Look.display(ctx) }
        private val r = RectF()

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
            val pad = 6f * d
            r.set(pad, pad, w - pad, h - pad * 1.8f)
            val rad = r.height() * 0.3f
            fill.color = 0x40603C1C; cv.drawRoundRect(r.left, r.top + 4f * d, r.right, r.bottom + 4f * d, rad, rad, fill)
            fill.color = 0xFFFFFDF8.toInt(); cv.drawRoundRect(r, rad, rad, fill)
            line.color = Look.COCOA; line.strokeWidth = 2.4f * d; cv.drawRoundRect(r, rad, rad, line)
            val s = r.height()
            val ix = r.left + s * 0.55f; val iy = r.centerY()
            val it = item
            if (it != null) {
                ItemArt.sticker(cv, it, ix, iy, s * 0.66f, 0xFFFFFFFF.toInt(), d)
                ItemArt.twinkle(cv, ix + s * 0.34f, iy - s * 0.3f, s * 0.06f, 0xFFFFD66B.toInt())
            } else picture(cv, ix, iy, s * 0.3f, d)
            val tx = r.left + s * 1.08f; val room = r.right - tx - s * 0.2f
            text.color = Look.INK; text.textSize = s * 0.32f
            val tw = text.measureText(title); if (tw > room) text.textSize *= room / tw
            cv.drawText(title, tx, r.top + s * 0.46f, text)
            text.color = accent; text.textSize = s * 0.22f
            val sw = text.measureText(sub); if (sw > room) text.textSize *= room / sw
            cv.drawText(sub, tx, r.top + s * 0.78f, text)
        }

        private fun picture(cv: Canvas, x: Float, y: Float, s: Float, d: Float) {
            when (icon) {
                AlertIcon.PIN -> Pics.pin(cv, ink, x, y + s * 0.95f, s * 0.95f)
                AlertIcon.FUEL -> Pics.energy(cv, ink, x, y, s, false)
                AlertIcon.BATTERY -> Pics.energy(cv, ink, x, y, s, true)
                AlertIcon.BELT -> {   // a belt across a round badge, with its buckle
                    ink.circle(cv, x, y, s, 0xFFFDE8E0.toInt()); ink.circleOutline(cv, x, y, s, Look.COCOA, 2f * d)
                    line.color = Look.COCOA; line.strokeWidth = s * 0.34f; cv.drawLine(x - s * 0.5f, y - s * 0.55f, x + s * 0.45f, y + s * 0.5f, line)
                    line.color = Look.CORAL; line.strokeWidth = s * 0.2f; cv.drawLine(x - s * 0.5f, y - s * 0.55f, x + s * 0.45f, y + s * 0.5f, line)
                    val saved = RectF(x - s * 0.02f, y + s * 0.02f, x + s * 0.5f, y + s * 0.5f)   // not the card's own rectangle: the text is laid out from that
                    fill.color = 0xFFE9EDF5.toInt(); cv.drawRoundRect(saved, s * 0.1f, s * 0.1f, fill)
                    line.color = Look.COCOA; line.strokeWidth = 1.8f * d; cv.drawRoundRect(saved, s * 0.1f, s * 0.1f, line)
                }
                AlertIcon.RAIN -> {   // a cloud and three drops
                    ink.circle(cv, x, y, s, 0xFFE5ECFD.toInt()); ink.circleOutline(cv, x, y, s, Look.COCOA, 2f * d)
                    Pics.cloud(cv, ink, x, y - s * 0.18f, s * 0.42f, 0xFFFFFFFF.toInt(), 0xFFB7C8EE.toInt())
                    line.color = Look.PERI_DEEP; line.strokeWidth = s * 0.11f
                    for (k in -1..1) cv.drawLine(x + k * s * 0.3f + s * 0.06f, y + s * 0.28f, x + k * s * 0.3f - s * 0.06f, y + s * 0.6f, line)
                }
                null -> {}
            }
        }
    }
    private val card = Card(ctx)
    private val cardLp = WindowManager.LayoutParams(dp(CARD_W), dp(CARD_H), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
    private var cardShown = false
    private var cardFrom = 0L
    private var cardUntil = 0L
    private var cardFor: Find? = null

    private fun setCard(item: DexItem?, icon: AlertIcon?, title: String, sub: String, accent: Int, from: Long, ms: Long) {
        card.item = item; card.icon = icon; card.title = title; card.sub = sub; card.accent = accent
        cardFrom = from; cardUntil = from + ms
        card.invalidate()
    }

    /** a notice from the car (a new region, the seat belt, rain, low energy). A find in progress keeps the stage: the notice waits out */
    fun showAlert(a: PetAlert) {
        if (!shown || fetching) return
        val accent = when (a.icon) { AlertIcon.PIN -> Look.PERI_DEEP; AlertIcon.RAIN -> Look.PERI_DEEP; else -> Look.CORAL_D }
        setCard(null, a.icon, a.title, a.sub, accent, SystemClock.elapsedRealtime(), ALERT_MS)
        anim.removeCallbacks(animTick); anim.post(animTick)
    }

    /** keeps the card above the pet, fades it in and out; returns false when there is none (any more) */
    private fun applyCard(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now >= cardUntil || now < cardFrom) { hideCard(); return now < cardFrom }
        val (cx, top, sw) = baseSpot()
        cardLp.x = (cx - cardLp.width / 2).coerceIn(dp(8), max(dp(8), sw - cardLp.width - dp(8)))
        cardLp.y = max(dp(64), top - cardLp.height + dp(4))
        val a = min((now - cardFrom) / 250f, (cardUntil - now) / 400f).coerceIn(0f, 1f)
        card.alpha = a
        card.translationY = (1f - min(1f, (now - cardFrom) / 250f)) * dp(10)
        try { if (!cardShown) { wm.addView(card, cardLp); cardShown = true } else wm.updateViewLayout(card, cardLp) } catch (_: Exception) {}
        return true
    }
    private fun hideCard() { if (cardShown) { try { wm.removeView(card) } catch (_: Exception) {}; cardShown = false } }

    // ---------------------------------------------------------------- the fetch

    /** the pet has just found something: play the fetch from the start */
    fun playFetch() {
        if (!shown) return
        cardUntil = 0L   // a notice that was up makes way
        anim.removeCallbacks(animTick)
        anim.post(animTick)
    }

    private fun ease(x: Float) = (x * x * (3 - 2 * x)).coerceIn(0f, 1f)

    /**
     * Sets the pet for this moment of the fetch; returns false when it is over.
     * 1. it hops away up the road and fades out (the driver sees it leave);
     * 2. it is gone: three dots wait at its spot;
     * 3. it comes back the same way, already carrying the thing;
     * 4. home: two happy hops, and the card says what it found (new or again, the points, how full the region is).
     */
    private fun applyFetch(): Boolean {
        val f = PetHub.fetchNow()
        if (f == null) { endFetch(); return false }
        fetching = true
        val t = f.second
        val leave = PetHub.FETCH_LEAVE_MS; val away = PetHub.FETCH_AWAY_MS; val back = PetHub.FETCH_BACK_MS
        // ahead of the car on the map = up the screen, a little to the right of the road
        val outX = dp(OUT_X); val outY = -dp(OUT_Y)
        val gone = when {
            t < leave -> ease(t / leave.toFloat())
            t < away -> 1f
            t < back -> 1f - ease((t - away) / (back - away).toFloat())
            else -> 0f
        }
        val hop = when {
            t < leave || t in away until back -> abs(sin(t * Math.PI / HOP_MS)) * dp(16)
            t in back until back + 2 * HOP_MS -> abs(sin((t - back) * Math.PI / HOP_MS)) * dp(24)
            else -> 0.0
        }
        fetchDx = (outX * gone).toInt(); fetchDy = (outY * gone - hop).toInt()
        pet.alpha = (1f - gone * gone).coerceIn(0f, 1f)
        val k = 1f - 0.4f * gone; pet.scaleX = k; pet.scaleY = k
        place()
        pet.pose = when { t < back -> Pose.WALK_BACK; else -> Pose.STAND }
        pet.carry = if (t >= away) f.first.item else null
        if (t in leave - 200 until away + 200) showDots(min((t - (leave - 200)) / 200f, (away + 200 - t) / 200f).coerceIn(0f, 1f)) else hideDots()
        if (t >= back && cardFor !== f.first) {
            cardFor = f.first
            val find = f.first; val region = Korea.region(find.item.regionId)
            val progress = region?.let { "${it.name} 도감 ${PetHub.dex.count(it.id)}/${it.items.size}" } ?: ""
            val title = if (find.isNew) "${find.item.name} 발견!" else "${find.item.name} 또 찾았어요"
            val sub = when {
                find.allDone -> "전국 도감 완성!  +${find.points}P"
                find.regionDone -> "${region?.name ?: ""} 도감 완성!  +${find.points}P"
                else -> "$progress  ·  +${find.points}P"
            }
            setCard(find.item, null, title, sub, if (find.isNew) Look.HONEY_DEEP else Look.SUB, SystemClock.elapsedRealtime() - (t - back), PetHub.FETCH_TOTAL_MS - back - 200)
        }
        return true
    }

    /** back to the base spot */
    private fun endFetch() {
        hideDots()
        val was = fetching
        fetching = false; fetchDx = 0; fetchDy = 0
        pet.translationX = 0f; pet.alpha = 1f; pet.scaleX = 1f; pet.scaleY = 1f; pet.carry = null
        if (was) { place(); refresh() }
    }

    init {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        pet.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    // END gravity: moving right means a smaller distance from the right edge; START gravity the opposite
                    if (moved && shown && !fetching) {
                        lp.x = startX + (if (following) dx else -dx).toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(pet, lp)
                        if (!following) { restX = lp.x; restY = lp.y }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap() else if (following && !fetching) rememberFollowSpot()
            }
            true
        }
    }

    fun canShow() = Settings.canDrawOverlays(ctx)

    fun show() {
        if (shown || !canShow()) return
        refresh()
        try { wm.addView(pet, lp); shown = true } catch (_: Exception) {}
    }

    fun hide() {
        if (!shown) return
        anim.removeCallbacks(animTick)
        hideDots(); hideCard(); cardUntil = 0L
        fetching = false; fetchDx = 0; fetchDy = 0
        pet.translationX = 0f; pet.alpha = 1f; pet.scaleX = 1f; pet.scaleY = 1f; pet.carry = null
        try { wm.removeView(pet) } catch (_: Exception) {}
        shown = false
    }

    /**
     * The real size of the display. The window metrics of a service report only the panel the app area covers
     * (about 940 px wide on the emulator), but overlay coordinates are measured from the whole display.
     */
    private fun screenSize(): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        val display = ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    /** next to the car while the navigation is on and the car is in gear (moving or standing); otherwise at the resting spot */
    private fun applyAnchor() {
        val follow = PetHub.followCar && PetHub.navTrip != null && PetHub.inDrive
        val changed = follow != following
        following = follow
        if (changed) place()
    }

    private fun rememberFollowSpot() {
        val (sw, sh) = screenSize()
        Store.saveFollowSpot(ctx, (lp.x + lp.width / 2f) / sw, (lp.y + lp.height / 2f) / sh)
    }

    /** pose, size, look and clothes follow the pet's state */
    fun refresh() {
        if (!PetHub.started) return
        val s = PetHub.engine.snapshot()
        pet.state = PetHub.petState
        pet.level = s.level
        pet.outfit = PetHub.outfit; pet.furniture = PetHub.furniture; pet.stage = s.stage; pet.eggAwake = GrowthStage.eggAwake(s.totalKm)
        pet.energyBadge = if (PetHub.hungry) (if (PetHub.usesBattery) 2 else 1) else 0
        if (!fetching) pet.pose = when {   // during a fetch the fetch sets the pose
            PetHub.moving && s.stage != GrowthStage.EGG -> Pose.WALK_BACK   // the egg stays still; the baby hops along (PetView)
            PetHub.inDrive -> Pose.STAND_BACK
            else -> Pose.STAND
        }
        val size = dp(if (PetHub.inDrive) SIZE_DRIVING else SIZE_PARKED)   // big enough to read at a glance next to the car marker
        val resized = lp.width != size
        if (resized) { lp.width = size; lp.height = size }
        applyAnchor()
        if (resized) place()   // keeps the pet centred on its spot
    }

    private fun onTap() {
        if (PetHub.started && !PetHub.screenAllowed()) return   // not while in gear; no message either
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private companion object {
        // the Pleos Connect v2.1.0 navigation (2560x1440) draws the car marker at about (0.66, 0.76) of the screen, with
        // or without a route (measured on the emulator), and the place name just under it. The pet walks at the car's
        // left side, level with it, so it covers neither the marker nor the place name.
        const val DEFAULT_FOLLOW_X = 0.605f
        const val DEFAULT_FOLLOW_Y = 0.735f
        // the pet's window, in dp: bigger than before (132 / 120), which was hard to make out from the driver's seat
        const val SIZE_DRIVING = 196
        const val SIZE_PARKED = 176
        // the fetch: how far up the road the pet runs before it is gone (dp); the times are in PetHub (FETCH_*_MS)
        const val OUT_X = 70
        const val OUT_Y = 170
        const val HOP_MS = 280L   // one hop
        const val CARD_W = 400
        const val CARD_H = 112
        const val ALERT_MS = 5_000L
    }
}
