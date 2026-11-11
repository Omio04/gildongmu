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

    /** the pet has just fetched something: it steps off to the roadside, is gone for a moment, and comes back with it */
    fun playFetch() {
        if (!shown) return
        anim.removeCallbacks(animTick)
        anim.post(animTick)
    }

    /** sets the pet for this moment of the show; returns false when the show is over */
    private fun applyFetch(): Boolean {
        val f = PetHub.fetchNow()
        if (f == null) { pet.translationX = 0f; pet.alpha = 1f; pet.carry = null; return false }
        val t = f.second
        val away = when { t < 1200 -> t / 1200f; t < 2600 -> 1f; t < 3800 -> 1f - (t - 2600) / 1200f; else -> 0f }
        pet.translationX = away * dp(34)
        pet.alpha = 1f - away
        pet.carry = if (t >= 2600) f.first.item.emoji else null
        return true
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
                    if (moved && shown) { lp.x = startX + (if (following) dx else -dx).toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(pet, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap() else if (following) rememberFollowSpot()
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
    private fun applyAnchor(size: Int) {
        val follow = PetHub.followCar && PetHub.navTrip != null && PetHub.inDrive
        if (follow) {
            val (sw, sh) = screenSize()
            val spot = Store.loadFollowSpot(ctx) ?: (DEFAULT_FOLLOW_X to DEFAULT_FOLLOW_Y)
            if (!following) {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = (sw * spot.first - size / 2f).toInt()
                lp.y = (sh * spot.second - size / 2f).toInt()
            }
        } else if (following) {
            lp.gravity = Gravity.TOP or Gravity.END
            lp.x = dp(10); lp.y = dp(250)
        }
        val changed = follow != following
        following = follow
        if (changed && shown) wm.updateViewLayout(pet, lp)
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
        pet.pose = when {
            PetHub.moving -> Pose.WALK_BACK
            PetHub.inDrive -> Pose.STAND_BACK
            else -> Pose.STAND
        }
        val size = dp(if (PetHub.inDrive) 96 else 120)
        if (lp.width != size) {
            lp.width = size; lp.height = size
            if (shown) wm.updateViewLayout(pet, lp)
        }
        applyAnchor(size)
    }

    private fun onTap() {
        if (PetHub.started && !PetHub.screenAllowed()) return   // not while in gear; no message either
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private companion object {
        // where this navigation draws the car marker is about (0.66, 0.55) of the screen; the pet stands just beside it,
        // above the trip card, so it does not cover the route
        const val DEFAULT_FOLLOW_X = 0.78f
        const val DEFAULT_FOLLOW_Y = 0.49f
    }
}
