package com.drivepet.app

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager

/**
 * The small pet that floats over other screens (the navigation, music...) while the pet's own screen is not showing.
 * No text is ever drawn on it.
 *  - in gear (D or R): seen from behind. Walking while the car moves; when the car stops it simply stops where it is
 *    (it does not move to the edge, it does not turn round).
 *  - with the navigation on and the car in gear, it stays next to the car on the map ("follow the car");
 *    otherwise it waits at the right edge, between the corner buttons most map screens keep there.
 *  - not in gear: standing, front view.
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
        dp(120), dp(120),
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
            val busy = applyFetch()
            if (busy && shown) anim.postDelayed(this, 40)
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

    // ---- the fetch: a found thing appears on the road ahead, the pet runs to it, picks it up and runs back
    /** the found thing lying on the road ahead: a sticker on a little warm light, no circle round it */
    private class Marker(ctx: Context) : android.view.View(ctx) {
        var item: DexItem? = null
            set(v) { if (field != v) { field = v; invalidate() } }
        override fun onDraw(cv: android.graphics.Canvas) {
            val it = item ?: return
            val s = width.toFloat(); val dd = resources.displayMetrics.density
            ItemArt.groundGlow(cv, s / 2f, s * 0.8f, s * 0.45f)
            ItemArt.sticker(cv, it, s / 2f, s * 0.45f, s * 0.62f, 0xFFFFFFFF.toInt(), dd)
            ItemArt.twinkle(cv, s * 0.84f, s * 0.16f, s * 0.08f, 0xFFFFD66B.toInt())
        }
    }
    private val marker = Marker(ctx)
    private val markerLp = WindowManager.LayoutParams(dp(84), dp(84), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT)
    private var markerShown = false

    /** the pet has just found something: play the fetch from the start */
    fun playFetch() {
        if (!shown) return
        anim.removeCallbacks(animTick)
        anim.post(animTick)
    }

    private fun ease(x: Float) = (x * x * (3 - 2 * x)).coerceIn(0f, 1f)

    /** sets the pet (and the thing on the road) for this moment of the fetch; returns false when it is over */
    private fun applyFetch(): Boolean {
        val f = PetHub.fetchNow()
        if (f == null) { endFetch(); return false }
        fetching = true
        val t = f.second
        // ahead of the car on the map = up the screen, a little to the right of the road
        val outX = dp(OUT_X); val outY = -dp(OUT_Y)
        val away = when {
            t < GO_MS -> ease(t / GO_MS.toFloat())
            t < PICK_MS -> 1f
            t < BACK_MS -> 1f - ease((t - PICK_MS) / (BACK_MS - PICK_MS).toFloat())
            else -> 0f
        }
        // bouncy: small hops on the way out and back, one big hop when it grabs the thing, two happy hops once home
        val hop = when {
            t < GO_MS || t in PICK_MS until BACK_MS -> Math.abs(Math.sin(t * Math.PI / HOP_MS)) * dp(16)
            t in GO_MS + 150 until GO_MS + 600 -> Math.sin((t - GO_MS - 150) * Math.PI / 450) * dp(30)
            t in BACK_MS until BACK_MS + 2 * HOP_MS -> Math.abs(Math.sin((t - BACK_MS) * Math.PI / HOP_MS)) * dp(22)
            else -> 0.0
        }
        fetchDx = (outX * away).toInt(); fetchDy = (outY * away - hop).toInt()
        place()
        pet.pose = when { t < GO_MS -> Pose.WALK_BACK; t < BACK_MS -> Pose.STAND; else -> pet.pose }
        // the thing waits on the road until the pet gets there, bobbing a little
        val picked = t >= GO_MS + 300
        if (!picked) {
            val size = markerLp.width
            val baseX = lp.x - (if (following) fetchDx else -fetchDx); val baseY = lp.y - fetchDy
            markerLp.gravity = lp.gravity
            markerLp.x = baseX + (if (following) outX else -outX) + (lp.width - size) / 2
            markerLp.y = baseY + outY + (lp.height - size) / 2 + (Math.sin(t / 180.0) * dp(4)).toInt()
            marker.item = f.first.item
            marker.alpha = (t / 300f).coerceIn(0f, 1f)
            try { if (!markerShown) { wm.addView(marker, markerLp); markerShown = true } else wm.updateViewLayout(marker, markerLp) } catch (_: Exception) {}
        } else hideMarker()
        pet.carry = if (picked) f.first.item else null
        return true
    }

    private fun hideMarker() { if (markerShown) { try { wm.removeView(marker) } catch (_: Exception) {}; markerShown = false } }

    /** back to the base spot */
    private fun endFetch() {
        hideMarker()
        val was = fetching
        fetching = false; fetchDx = 0; fetchDy = 0
        pet.translationX = 0f; pet.alpha = 1f; pet.carry = null
        if (was) { place(); refresh() }
    }

    init {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        pet.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true
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
        hideMarker(); fetching = false; fetchDx = 0; fetchDy = 0
        pet.translationX = 0f; pet.alpha = 1f; pet.carry = null
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
        pet.outfit = PetHub.outfit; pet.furniture = PetHub.furniture; pet.stage = s.stage
        if (!fetching) pet.pose = when {   // during a fetch the fetch sets the pose
            PetHub.moving && s.stage != GrowthStage.EGG -> Pose.WALK_BACK   // the egg stays still; the baby hops along (PetView)
            PetHub.inDrive -> Pose.STAND_BACK
            else -> Pose.STAND
        }
        val size = dp(if (PetHub.inDrive) 132 else 120)   // big enough to read at a glance next to the car marker
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
        const val DEFAULT_FOLLOW_X = 0.615f
        const val DEFAULT_FOLLOW_Y = 0.745f
        // the fetch: how far ahead the found thing lies (dp), and when the pet gets there, picks it up and is back (ms)
        const val OUT_X = 70
        const val OUT_Y = 190
        const val GO_MS = 1_400L
        const val PICK_MS = 2_400L
        const val BACK_MS = 3_800L
        const val HOP_MS = 280L   // one hop
    }
}
