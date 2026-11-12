package com.drivepet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The small round portrait of the pet at the top of the menu: its face follows the current state. */
class AvatarView(context: Context) : View(context) {
    var state: PetState = PetState.NORMAL
        set(v) { field = v; invalidate() }
    /** false: only the face, no white ring or background (used inside the status card) */
    var framed: Boolean = true

    private val ink = Ink()
    private val d = resources.displayMetrics.density

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) / 2f
        val cx = w / 2f; val cy = h / 2f
        if (framed) {
            ink.circle(cv, cx, cy + 2f * d, r - 1f * d, 0x2E3C5AA0)                 // a soft shadow toward the sky band
            ink.circle(cv, cx, cy, r - 1f * d, Look.WHITE)
            val inner = r * 0.86f
            ink.fill.color = OPAQUE; ink.fill.shader = LinearGradient(0f, cy - inner, 0f, cy + inner, 0xFFFFF3D8.toInt(), 0xFFFFE2B0.toInt(), Shader.TileMode.CLAMP)
            cv.drawCircle(cx, cy, inner, ink.fill); ink.fill.shader = null
            cv.save(); ink.path.reset(); ink.path.addCircle(cx, cy, inner, Path.Direction.CW); cv.clipPath(ink.path)
            Pics.bearHead(cv, ink, cx, cy + r * 0.2f, r * 0.64f, state)
            cv.restore()
        } else {
            Pics.bearHead(cv, ink, cx, cy + r * 0.16f, r * 0.72f, state)
        }
    }
}

enum class IconKind { INFO, MISSION, SHOP, INVENTORY, GAME, DEX, RECORD, SETTINGS, DEV }

/** The small icon in front of a menu row: a pastel tile with a tiny outlined drawing, in the character's style. */
class MenuIcon(context: Context, private val kind: IconKind) : View(context) {
    private val ink = Ink()
    private val rect = RectF()

    override fun onDraw(cv: Canvas) {
        val s = min(width, height).toFloat()
        val (a, b) = when (kind) {
            IconKind.INFO -> 0xFFFFF0D2.toInt() to 0xFFFBE1B0.toInt()
            IconKind.MISSION -> 0xFFFFE6DD.toInt() to 0xFFFBD0C2.toInt()
            IconKind.SHOP -> 0xFFFFEBDD.toInt() to 0xFFFBD8C0.toInt()
            IconKind.INVENTORY -> 0xFFE5F4E4.toInt() to 0xFFCDE9CC.toInt()
            IconKind.GAME -> 0xFFE5ECFD.toInt() to 0xFFCCDAF9.toInt()
            IconKind.DEX -> 0xFFDFF4EE.toInt() to 0xFFC2E8DC.toInt()
            IconKind.RECORD -> 0xFFF1EBFE.toInt() to 0xFFDFD5F8.toInt()
            IconKind.SETTINGS -> 0xFFEEEDF5.toInt() to 0xFFDCDBEA.toInt()
            IconKind.DEV -> 0xFFEFEFEF.toInt() to 0xFFE0E0E0.toInt()
        }
        rect.set(0f, 0f, s, s)
        ink.fill.color = OPAQUE; ink.fill.shader = LinearGradient(0f, 0f, 0f, s, a, b, Shader.TileMode.CLAMP); cv.drawRoundRect(rect, s * 0.32f, s * 0.32f, ink.fill); ink.fill.shader = null
        rect.set(0.6f, 0.6f, s - 0.6f, s - 0.6f); ink.line.color = 0xCCFFFFFF.toInt(); ink.line.strokeWidth = 1.2f; cv.drawRoundRect(rect, s * 0.32f, s * 0.32f, ink.line)
        draw(cv, ink, kind, s / 2f, s / 2f, s)
    }

    companion object {
        /** the drawing itself, centred on (cx, cy), for a tile of size s */
        fun draw(cv: Canvas, ink: Ink, kind: IconKind, cx: Float, cy: Float, s: Float) {
            val ow = max(1.3f, s * 0.05f)
            val p = ink.path
            when (kind) {
                IconKind.INFO -> Pics.bearHead(cv, ink, cx, cy + s * 0.06f, s * 0.3f, PetState.NORMAL)
                IconKind.MISSION -> {   // a coral flag on a pole
                    ink.line.color = Look.COCOA; ink.line.strokeWidth = ow * 1.6f; cv.drawLine(cx - s * 0.17f, cy - s * 0.27f, cx - s * 0.17f, cy + s * 0.28f, ink.line)
                    p.reset(); p.moveTo(cx - s * 0.16f, cy - s * 0.26f); p.quadTo(cx + s * 0.05f, cy - s * 0.32f, cx + s * 0.25f, cy - s * 0.2f); p.lineTo(cx + s * 0.25f, cy + s * 0.02f)
                    p.quadTo(cx + s * 0.05f, cy - s * 0.08f, cx - s * 0.16f, cy - s * 0.02f); p.close()
                    ink.fillPath(cv, p, LinearGradient(cx, 0f, cx + s * 0.3f, 0f, 0xFFF7897A.toInt(), 0xFFEC6A5B.toInt(), Shader.TileMode.CLAMP)); ink.stroke(cv, p, Look.COCOA, ow)
                    Pics.sparkle(cv, ink, cx + s * 0.05f, cy - s * 0.13f, s * 0.06f, 0xFFFFF2B8.toInt())
                }
                IconKind.SHOP -> {   // a gift box with a periwinkle ribbon
                    box(cv, ink, cx - s * 0.25f, cy - s * 0.05f, cx + s * 0.25f, cy + s * 0.28f, s * 0.05f, LinearGradient(0f, cy, 0f, cy + s * 0.3f, 0xFFF9AD7F.toInt(), 0xFFEE8B57.toInt(), Shader.TileMode.CLAMP), ow)
                    box(cv, ink, cx - s * 0.29f, cy - s * 0.15f, cx + s * 0.29f, cy - s * 0.02f, s * 0.04f, null, ow, 0xFFFBC29F.toInt())
                    ink.rect.set(cx - s * 0.05f, cy - s * 0.15f, cx + s * 0.05f, cy + s * 0.28f); ink.fill.color = 0xFF7F9FEA.toInt(); cv.drawRect(ink.rect, ink.fill)
                    ink.line.color = Look.COCOA; ink.line.strokeWidth = ow * 0.6f; cv.drawRect(ink.rect, ink.line)
                    for (sg in intArrayOf(-1, 1)) {
                        cv.save(); cv.rotate(sg * 28.6f, cx + sg * s * 0.1f, cy - s * 0.22f)
                        ink.oval(cv, cx + sg * s * 0.1f, cy - s * 0.22f, s * 0.1f, s * 0.07f, 0xFF8FAAF0.toInt()); ink.ovalOutline(cv, cx + sg * s * 0.1f, cy - s * 0.22f, s * 0.1f, s * 0.07f, Look.COCOA, ow)
                        cv.restore()
                    }
                }
                IconKind.INVENTORY -> {   // a sage bag
                    box(cv, ink, cx - s * 0.24f, cy - s * 0.12f, cx + s * 0.24f, cy + s * 0.28f, s * 0.1f, LinearGradient(0f, cy - s * 0.1f, 0f, cy + s * 0.28f, 0xFF9DD49B.toInt(), 0xFF6FB673.toInt(), Shader.TileMode.CLAMP), ow)
                    ink.rect.set(cx - s * 0.12f, cy - s * 0.24f, cx + s * 0.12f, cy); ink.line.color = Look.COCOA; ink.line.strokeWidth = ow * 1.2f; cv.drawArc(ink.rect, 180f, 180f, false, ink.line)
                    box(cv, ink, cx - s * 0.14f, cy + s * 0.02f, cx + s * 0.14f, cy + s * 0.14f, s * 0.04f, null, ow * 0.8f, 0xFFE6F5E2.toInt())
                }
                IconKind.GAME -> Pics.car(cv, ink, cx, cy - s * 0.04f, s * 0.3f)
                IconKind.DEX -> {   // a folded map with a coral pin
                    p.reset()
                    p.moveTo(cx - s * 0.3f, cy - s * 0.18f); p.lineTo(cx - s * 0.1f, cy - s * 0.25f); p.lineTo(cx + s * 0.1f, cy - s * 0.18f); p.lineTo(cx + s * 0.3f, cy - s * 0.25f)
                    p.lineTo(cx + s * 0.3f, cy + s * 0.22f); p.lineTo(cx + s * 0.1f, cy + s * 0.29f); p.lineTo(cx - s * 0.1f, cy + s * 0.22f); p.lineTo(cx - s * 0.3f, cy + s * 0.29f); p.close()
                    ink.fillPath(cv, p, 0xFFBFE7DA.toInt())
                    ink.path2.reset(); ink.path2.moveTo(cx - s * 0.1f, cy - s * 0.25f); ink.path2.lineTo(cx + s * 0.1f, cy - s * 0.18f); ink.path2.lineTo(cx + s * 0.1f, cy + s * 0.29f); ink.path2.lineTo(cx - s * 0.1f, cy + s * 0.22f); ink.path2.close()
                    ink.fillPath(cv, ink.path2, 0x59509F8C)
                    ink.stroke(cv, p, Look.COCOA, ow)
                    Pics.pin(cv, ink, cx, cy + s * 0.1f, s * 0.3f)
                }
                IconKind.RECORD -> {   // a calendar page whose days are the three score colours
                    val l = cx - s * 0.28f; val t = cy - s * 0.22f; val r = cx + s * 0.28f; val b = cy + s * 0.28f
                    box(cv, ink, l, t, r, b, s * 0.07f, null, ow, 0xFFFFFDF8.toInt())
                    cv.save(); ink.rect.set(l, t, r, b); ink.path2.reset(); ink.path2.addRoundRect(ink.rect, s * 0.07f, s * 0.07f, android.graphics.Path.Direction.CW); cv.clipPath(ink.path2)
                    ink.fill.color = OPAQUE; ink.fill.shader = LinearGradient(0f, t, 0f, t + s * 0.13f, 0xFF8FAAF0.toInt(), 0xFF6F8FE6.toInt(), Shader.TileMode.CLAMP)
                    cv.drawRect(l, t, r, t + s * 0.13f, ink.fill); ink.fill.shader = null
                    cv.restore()
                    ink.line.color = Look.COCOA; ink.line.strokeWidth = ow * 0.8f; cv.drawLine(l, t + s * 0.13f, r, t + s * 0.13f, ink.line)
                    ink.rect.set(l, t, r, b); cv.drawRoundRect(ink.rect, s * 0.07f, s * 0.07f, ink.line.apply { strokeWidth = ow })
                    for (sg in intArrayOf(-1, 1)) {   // the two rings on top
                        ink.rect.set(cx + sg * s * 0.13f - s * 0.035f, t - s * 0.06f, cx + sg * s * 0.13f + s * 0.035f, t + s * 0.07f)
                        ink.fill.color = 0xFFFFFDF8.toInt(); cv.drawRoundRect(ink.rect, s * 0.035f, s * 0.035f, ink.fill)
                        ink.line.strokeWidth = ow * 0.8f; cv.drawRoundRect(ink.rect, s * 0.035f, s * 0.035f, ink.line)
                    }
                    val cols = intArrayOf(0xFF8FD18C.toInt(), 0xFFFBD97A.toInt(), 0xFFFDB894.toInt(), 0xFFFBD97A.toInt(), 0xFF8FD18C.toInt(), 0xFF8FD18C.toInt())
                    val cell = s * 0.12f; val gap = s * 0.035f
                    val gx = cx - (cell * 3 + gap * 2) / 2f; val gy = t + s * 0.18f
                    for (i in 0 until 6) {
                        val x = gx + (i % 3) * (cell + gap); val y = gy + (i / 3) * (cell + gap)
                        ink.rect.set(x, y, x + cell, y + cell); ink.fill.color = cols[i]; cv.drawRoundRect(ink.rect, cell * 0.3f, cell * 0.3f, ink.fill)
                    }
                }
                IconKind.SETTINGS -> {   // a lavender gear
                    p.reset()
                    for (i in 0 until 16) {
                        val an = (i * PI / 8).toFloat(); val r = if (i % 2 == 0) s * 0.29f else s * 0.22f
                        if (i == 0) p.moveTo(cx + cos(an) * r, cy + sin(an) * r) else p.lineTo(cx + cos(an) * r, cy + sin(an) * r)
                    }
                    p.close()
                    ink.fillPath(cv, p, LinearGradient(0f, cy - s * 0.3f, 0f, cy + s * 0.3f, 0xFFB9B6D6.toInt(), 0xFF9693BD.toInt(), Shader.TileMode.CLAMP)); ink.stroke(cv, p, Look.COCOA, ow)
                    ink.circle(cv, cx, cy, s * 0.09f, 0xFFF4F2FA.toInt()); ink.circleOutline(cv, cx, cy, s * 0.09f, Look.COCOA, ow)
                }
                IconKind.DEV -> {   // < >
                    ink.line.color = 0xFF777777.toInt(); ink.line.strokeWidth = ow * 1.4f
                    p.reset(); p.moveTo(cx - s * 0.08f, cy - s * 0.16f); p.lineTo(cx - s * 0.22f, cy); p.lineTo(cx - s * 0.08f, cy + s * 0.16f); cv.drawPath(p, ink.line)
                    p.reset(); p.moveTo(cx + s * 0.08f, cy - s * 0.16f); p.lineTo(cx + s * 0.22f, cy); p.lineTo(cx + s * 0.08f, cy + s * 0.16f); cv.drawPath(p, ink.line)
                }
            }
        }

        private fun box(cv: Canvas, ink: Ink, l: Float, t: Float, r: Float, b: Float, rad: Float, shader: Shader?, ow: Float, color: Int = 0) {
            ink.rect.set(l, t, r, b)
            if (shader != null) { ink.fill.color = OPAQUE; ink.fill.shader = shader; cv.drawRoundRect(ink.rect, rad, rad, ink.fill); ink.fill.shader = null }
            else { ink.fill.color = color; cv.drawRoundRect(ink.rect, rad, rad, ink.fill) }
            ink.line.color = Look.COCOA; ink.line.strokeWidth = ow; cv.drawRoundRect(ink.rect, rad, rad, ink.line)
        }
    }
}

/** A picture of a shop item on a cream tile, drawn with the same code that puts it on the pet or in the room. */
class ItemThumb(context: Context, private val id: String) : View(context) {
    private val ink = Ink()
    private val pet = PetPainter()
    private val room = RoomPainter(context)

    override fun onDraw(cv: Canvas) {
        val s = min(width, height).toFloat()
        ink.rect.set(0f, 0f, s, s)
        ink.fill.color = OPAQUE; ink.fill.shader = LinearGradient(0f, 0f, 0f, s, 0xFFFFF7EA.toInt(), 0xFFF8EAD6.toInt(), Shader.TileMode.CLAMP); cv.drawRoundRect(ink.rect, s * 0.3f, s * 0.3f, ink.fill); ink.fill.shader = null
        cv.save(); ink.path.reset(); ink.path.addRoundRect(ink.rect, s * 0.3f, s * 0.3f, Path.Direction.CW); cv.clipPath(ink.path)
        val cx = s / 2f; val cy = s / 2f
        val k = PetPainter.K
        fun unit(scale: Float, centerY: Float, draw: (Float) -> Unit) {
            val b = s * 0.8f * scale
            cv.save(); cv.translate(cx, cy - centerY * b); cv.scale(b, b); draw(max(1.2f / b, 0.058f)); cv.restore()
        }
        when (id) {
            "straw", "beret", "crown" -> unit(if (id == "crown") 0.8f else if (id == "beret") 0.68f else 0.62f, -2.2f * k) { ow -> pet.hat(cv, id, ow) }
            "sun", "round" -> unit(0.73f, -1.29f * k) { ow -> pet.glasses(cv, id, false, ow) }
            "jeans", "shorts" -> unit(0.62f, if (id == "jeans") -0.35f * k else -0.44f * k) { ow -> pet.pants(cv, id, ow, false) }
            "plant" -> room.plant(cv, cx, s * 0.9f, s * 0.19f)
            "clock" -> { room.clockFace(cv, cx, cy, s * 0.3f); room.clockHands(cv, cx, cy, s * 0.3f, 10f + 10f / 60f, 10f) }
            "picture" -> room.picture(cv, cx - s * 0.24f, cy - s * 0.2f, s * 0.48f, s * 0.4f)
            "rug" -> {
                cv.save(); cv.translate(cx, cy); cv.scale(1f, 0.55f)
                ink.circle(cv, 0f, 0f, s * 0.36f, 0xFFFFF4E4.toInt()); ink.circleOutline(cv, 0f, 0f, s * 0.36f, 0xCCC9A27A.toInt(), 1.5f)
                ink.circle(cv, 0f, 0f, s * 0.31f, 0xFFAFC6F6.toInt()); ink.circle(cv, 0f, 0f, s * 0.18f, 0xFF93B0F0.toInt()); ink.circle(cv, 0f, 0f, s * 0.08f, 0xFFFFF4E4.toInt())
                cv.restore()
            }
        }
        cv.restore()
    }
}

/** The tail under the first-run speech bubble: cream with the bubble's cocoa line on its two sides. */
class BubbleTail(context: Context) : View(context) {
    private val ink = Ink()
    private val d = resources.displayMetrics.density
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val sw = 2.4f * d
        val p = ink.path; p.reset()
        p.moveTo(w * 0.1f, 0f); p.quadTo(w * 0.42f, h * 0.35f, w * 0.38f, h - sw); p.quadTo(w * 0.62f, h * 0.4f, w * 0.9f, 0f); p.close()
        ink.fillPath(cv, p, 0xFFFFFDF8.toInt())
        ink.path2.reset(); ink.path2.moveTo(w * 0.1f, sw * 0.2f); ink.path2.quadTo(w * 0.42f, h * 0.35f, w * 0.38f, h - sw); ink.path2.quadTo(w * 0.62f, h * 0.4f, w * 0.9f, sw * 0.2f)
        ink.stroke(cv, ink.path2, Look.COCOA, sw)
    }
}

/** The round back button in front of a page title. */
class BackButton(context: Context) : View(context) {
    private val ink = Ink()
    private val d = resources.displayMetrics.density
    override fun onDraw(cv: Canvas) {
        val s = min(width, height).toFloat(); val r = s / 2f - 2f * d
        ink.circle(cv, s / 2f, s / 2f + 1.5f * d, r, 0x1F7A5434)
        ink.circle(cv, s / 2f, s / 2f, r, Look.WHITE); ink.circleOutline(cv, s / 2f, s / 2f, r, Look.LINE, 1.4f * d)
        ink.line.color = Look.COCOA; ink.line.strokeWidth = 2.4f * d
        ink.path.reset(); ink.path.moveTo(s * 0.55f, s * 0.33f); ink.path.lineTo(s * 0.42f, s * 0.5f); ink.path.lineTo(s * 0.55f, s * 0.67f); cv.drawPath(ink.path, ink.line)
    }
}

/** The header of the menu card and the room behind the cards: a bit of the icon's sky with two clouds and a soft wave. */
class SkyBandDrawable(context: Context, private val bandDp: Float, private val radiusDp: Float) : android.graphics.drawable.Drawable() {
    private val d = context.resources.displayMetrics.density
    private val ink = Ink()
    override fun draw(cv: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat(); val band = bandDp * d; val r = radiusDp * d
        cv.save(); cv.translate(b.left.toFloat(), b.top.toFloat())
        ink.path.reset(); ink.path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        ink.fillPath(cv, ink.path, Theme.fill(Look.CREAM))
        cv.save(); cv.clipPath(ink.path)
        val night = Theme.night
        ink.fill.color = OPAQUE; ink.fill.shader = LinearGradient(0f, 0f, 0f, band, if (night) 0xFF14206E.toInt() else 0xFFA8CBFA.toInt(), if (night) 0xFF2C46A8.toInt() else 0xFFD9E9FD.toInt(), Shader.TileMode.CLAMP); cv.drawRect(0f, 0f, w, band + 20f * d, ink.fill); ink.fill.shader = null
        Pics.cloud(cv, ink, w * 0.62f, 36f * d, 22f * d, if (night) 0x4D8FA0F0 else 0xF2FFFFFF.toInt(), if (night) 0x338FA0F0 else 0xB3CDDCF5.toInt())
        Pics.cloud(cv, ink, w * 0.86f, 66f * d, 15f * d, if (night) 0x408FA0F0 else 0xE6FFFFFF.toInt(), if (night) 0x338FA0F0 else 0xB3CDDCF5.toInt())
        ink.path2.reset(); ink.path2.moveTo(0f, band); ink.path2.cubicTo(w * 0.3f, band - 14f * d, w * 0.65f, band + 10f * d, w, band - 6f * d)
        ink.path2.lineTo(w, h); ink.path2.lineTo(0f, h); ink.path2.close(); ink.fillPath(cv, ink.path2, Theme.fill(Look.CREAM))
        cv.restore()
        ink.stroke(cv, ink.path, Theme.stroke(Look.LINE), 1.2f * d)
        cv.restore()
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(bounds, radiusDp * d) }
}
