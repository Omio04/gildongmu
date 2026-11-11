package com.drivepet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The room's geometry: the same one-point perspective as before (the pet walks on exactly the same floor).
 * The back wall is the view shrunk by [DEPTH] toward the vanishing point (vpx, vpy).
 */
class RoomGeo(val w: Float, val h: Float) {
    val vpx = w * 0.5f
    val vpy = h * 0.36f
    val bx0 = vpx * (1f - DEPTH)
    val bx1 = vpx + DEPTH * (w - vpx)
    val by0 = vpy * (1f - DEPTH)
    val by1 = vpy + DEPTH * (h - vpy)
    val wallW get() = bx1 - bx0
    val wallH get() = by1 - by0
    fun floorFront() = h * 0.93f

    /** floor coordinates u -1..1 (left..right), v 0 (back wall)..1 (front): x, y and the size factor into [out] */
    fun floorPoint(u: Float, v: Float, out: FloatArray) {
        val y = by1 + (floorFront() - by1) * v
        val t = (y - by1) / (h - by1)
        val left = bx0 * (1f - t); val right = bx1 + (w - bx1) * t
        out[0] = left + (right - left) * (u + 1f) / 2f; out[1] = y; out[2] = (y - vpy) / (h - vpy)
    }

    /** a point on a side wall: [side] -1 left / 1 right, [z] 0 front .. 1 back corner, [f] 0 ceiling .. 1 floor */
    fun sidePoint(side: Int, z: Float, f: Float, out: FloatArray) {
        val k = 1f / (1f + z * (1f / DEPTH - 1f))
        val wallX = if (side < 0) 0f else w
        val top = vpy + (0f - vpy) * k; val bot = vpy + (h - vpy) * k
        out[0] = vpx + (wallX - vpx) * k; out[1] = top + (bot - top) * f
    }

    companion object { const val DEPTH = 0.55f }
}

/**
 * Paints the room in the icon's mood: warm cream walls with a faint stripe above a wainscot, a honey wood floor with the
 * window's light falling on it, and a window that looks out on the icon's city (soft sky, clouds, lavender buildings,
 * round trees). The furniture is drawn in the character's style: cocoa outlines, gradients, a little gloss.
 *
 * The room does not change from frame to frame, so [PetView] paints it once into a bitmap ([paintRoom]) and only the
 * pet, the clock's hands and the two standing items (drawn as small bitmaps) are drawn every frame.
 */
class RoomPainter(context: Context) {
    private val d = context.resources.displayMetrics.density
    /** while the pet is hurt the room loses its colour, as before */
    var hurt = false
    /** dark mode: the room is lit only by the lamp and the moon, and the window looks out on the city at night */
    var night = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val rect = RectF()
    private val a = FloatArray(3); private val b = FloatArray(3); private val c = FloatArray(3); private val e = FloatArray(3)
    private val ink = Ink()

    private fun t(col: Int): Int {
        var c = col
        if (hurt) {
            val g = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3f
            c = Color.argb(Color.alpha(c), (Color.red(c) * 0.4f + g * 0.6f).toInt(), (Color.green(c) * 0.4f + g * 0.6f).toInt(), (Color.blue(c) * 0.4f + g * 0.6f).toInt())
        }
        if (night) {
            // the same room with the daylight taken out: a deep violet-blue, the cream turning to dusk
            c = Color.argb(Color.alpha(c),
                (Color.red(c) * 0.36f + 10f).toInt().coerceIn(0, 255),
                (Color.green(c) * 0.34f + 12f).toInt().coerceIn(0, 255),
                (Color.blue(c) * 0.46f + 26f).toInt().coerceIn(0, 255))
        }
        return c
    }
    private fun ta(col: Int, alpha: Float) = Look.alpha(t(col), alpha)
    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int) = LinearGradient(x0, y0, x1, y1, t(c0), t(c1), Shader.TileMode.CLAMP)
    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, cs: IntArray, pos: FloatArray) = LinearGradient(x0, y0, x1, y1, IntArray(cs.size) { t(cs[it]) }, pos, Shader.TileMode.CLAMP)

    private fun poly(cv: Canvas, vararg p: Float, color: Int = 0, shader: Shader? = null) {
        path.reset(); path.moveTo(p[0], p[1]); var i = 2
        while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        path.close()
        fill.color = OPAQUE; fill.shader = shader; fill.color = if (shader != null) Color.BLACK else color; cv.drawPath(path, fill); fill.shader = null
    }
    private inline fun shadowed(radius: Float, dy: Float, color: Int, draw: () -> Unit) {
        fill.setShadowLayer(radius, 0f, dy, color); draw(); fill.clearShadowLayer()
    }

    // ================================================================== the whole room, once
    fun paintRoom(cv: Canvas, g: RoomGeo, furniture: Set<String>) {
        val w = g.w; val h = g.h; val bx0 = g.bx0; val bx1 = g.bx1; val by0 = g.by0; val by1 = g.by1
        val wallW = g.wallW; val wallH = g.wallH
        // ceiling
        poly(cv, 0f, 0f, w, 0f, bx1, by0, bx0, by0, shader = lin(0f, 0f, 0f, by0, 0xFFFFF8EE.toInt(), 0xFFF7E9D6.toInt()))
        // side walls: wallpaper with a faint stripe, a wainscot below the rail, darker toward the corner
        for (side in intArrayOf(-1, 1)) {
            val xf = if (side < 0) 0f else w; val xb = if (side < 0) bx0 else bx1
            if (side < 0) poly(cv, 0f, 0f, bx0, by0, bx0, by1, 0f, h, shader = lin(xf, 0f, xb, 0f, 0xFFFAEBD8.toInt(), 0xFFEBD5BB.toInt()))
            else poly(cv, w, 0f, bx1, by0, bx1, by1, w, h, shader = lin(xf, 0f, xb, 0f, 0xFFFAEBD8.toInt(), 0xFFEBD5BB.toInt()))
            val n = 16
            for (i in 0 until n step 2) {
                g.sidePoint(side, i / n.toFloat(), 0f, a); g.sidePoint(side, (i + 1) / n.toFloat(), 0f, b)
                g.sidePoint(side, (i + 1) / n.toFloat(), RAIL, c); g.sidePoint(side, i / n.toFloat(), RAIL, e)
                poly(cv, a[0], a[1], b[0], b[1], c[0], c[1], e[0], e[1], color = ta(0xFFD9B993.toInt(), 0.13f))
            }
            g.sidePoint(side, 0f, RAIL, a); g.sidePoint(side, 1f, RAIL, b)
            poly(cv, a[0], a[1], b[0], b[1], xb, by1, xf, h, shader = lin(xf, 0f, xb, 0f, 0xFFF1DCC2.toInt(), 0xFFE2C7A6.toInt()))
            g.sidePoint(side, 0f, RAIL - 0.018f, c); g.sidePoint(side, 1f, RAIL - 0.018f, e)
            poly(cv, c[0], c[1], e[0], e[1], b[0], b[1], a[0], a[1], color = t(0xFFFFF8EE.toInt()))
            line.color = ta(0xFFB98D5E.toInt(), 0.28f); line.strokeWidth = d; cv.drawLine(a[0], a[1], b[0], b[1], line)
        }
        // back wall
        fill.color = OPAQUE; fill.shader = lin(0f, by0, 0f, by1, 0xFFFDF6EC.toInt(), 0xFFF8ECDD.toInt()); cv.drawRect(bx0, by0, bx1, by1, fill); fill.shader = null
        val railY = by0 + wallH * RAIL
        val ns = 26
        fill.color = ta(0xFFD9B993.toInt(), 0.13f)
        for (i in 0 until ns step 2) cv.drawRect(bx0 + wallW * i / ns, by0, bx0 + wallW * (i + 1) / ns, railY, fill)
        fill.color = OPAQUE; fill.shader = lin(0f, railY, 0f, by1, 0xFFF1DCC2.toInt(), 0xFFEBD2B4.toInt()); cv.drawRect(bx0, railY, bx1, by1, fill); fill.shader = null
        line.color = ta(0xFFC29A6C.toInt(), 0.22f); line.strokeWidth = 1.2f * d
        val np = 5; val pad = wallW * 0.012f
        for (i in 0 until np) {
            rect.set(bx0 + wallW * i / np + pad * 1.6f, railY + (by1 - railY) * 0.24f, bx0 + wallW * (i + 1) / np - pad * 1.6f, railY + (by1 - railY) * 0.74f)
            cv.drawRoundRect(rect, 3f * d, 3f * d, line)
        }
        fill.color = t(0xFFFFF8EE.toInt()); cv.drawRect(bx0, railY - wallH * 0.018f, bx1, railY, fill)
        line.color = ta(0xFFB98D5E.toInt(), 0.3f); line.strokeWidth = d; cv.drawLine(bx0, railY, bx1, railY, line)
        line.color = t(0xFFFFFBF4.toInt()); line.strokeWidth = 3f * d; cv.drawLine(bx0, by0 + 1.5f * d, bx1, by0 + 1.5f * d, line)

        drawWindow(cv, g)
        if ("clock" in furniture) { val (cx, cy, r) = clockSpot(g); clockFace(cv, cx, cy, r) }
        if ("picture" in furniture) { val pw = wallW * 0.15f; val ph = wallH * 0.25f; picture(cv, bx0 + wallW * 0.85f - pw / 2f, by0 + wallH * 0.2f, pw, ph) }

        // the floor: honey boards running to the vanishing point, the window's light falling on them
        poly(cv, 0f, h, w, h, bx1, by1, bx0, by1, color = t(0xFFE8C597.toInt()))
        cv.save()
        path.reset(); path.moveTo(0f, h); path.lineTo(w, h); path.lineTo(bx1, by1); path.lineTo(bx0, by1); path.close(); cv.clipPath(path)
        val n = 18
        val xb = FloatArray(n + 1) { bx0 + (bx1 - bx0) * it / n }
        val xf = FloatArray(n + 1) { g.vpx + (xb[it] - g.vpx) * (h - g.vpy) / (by1 - g.vpy) }
        for (i in 0 until n) if (i % 2 == 1) poly(cv, xb[i], by1, xb[i + 1], by1, xf[i + 1], h, xf[i], h, color = t(0xFFE2BC8B.toInt()))
        for (i in 0 until n step 3) poly(cv, xb[i], by1, xb[i + 1], by1, xf[i + 1], h, xf[i], h, color = ta(0xFFFFF3DF.toInt(), 0.16f))
        line.color = ta(0xFFB98550.toInt(), 0.32f); line.strokeWidth = h * 0.0022f
        for (i in 0..n) cv.drawLine(xb[i], by1, xf[i], h, line)
        line.strokeWidth = h * 0.0017f
        for (i in 0 until n) for (k in 0 until 2) {
            val tt = (((i * 37 + k * 53 + (i % 4) * 19) % 100) / 100f)
            val y = by1 + (h - by1) * tt * tt; val f = (y - by1) / (h - by1)
            cv.drawLine(xb[i] + (xf[i] - xb[i]) * f, y, xb[i + 1] + (xf[i + 1] - xb[i + 1]) * f, y, line)
        }
        val win = windowRect(g)
        val u0 = (win.left - bx0) / wallW * 2f - 1f; val u1 = (win.right - bx0) / wallW * 2f - 1f; val um = (u0 + u1) / 2f
        for ((ua, ub) in arrayOf(u0 to um - 0.012f, um + 0.012f to u1)) for ((va, vb) in arrayOf(0f to 0.2f, 0.23f to 0.46f)) {
            val sh = 0.24f   // the light leans a little, like late morning sun
            g.floorPoint(ua + va * sh, va, a); g.floorPoint(ub + va * sh, va, b); g.floorPoint(ub + vb * sh, vb, c); g.floorPoint(ua + vb * sh, vb, e)
            // daylight falls on the floor through the window; at night it is a faint cold moonlight instead
            poly(cv, a[0], a[1], b[0], b[1], c[0], c[1], e[0], e[1], shader = LinearGradient(0f, by1, 0f, c[1], if (night) 0x2E9DB4F0 else 0x57FFFAEB, if (night) 0x0A9DB4F0 else 0x14FFFAEB, Shader.TileMode.CLAMP))
        }
        fill.color = OPAQUE; fill.shader = LinearGradient(0f, by1, 0f, h, intArrayOf(if (night) 0x149DB4F0 else 0x33FFF8EB, 0x00FFF8EB, 0x1A784C24), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, by1, w, h, fill); fill.shader = null
        cv.restore()
        fill.color = OPAQUE; fill.shader = LinearGradient(0f, by1, 0f, by1 + h * 0.035f, 0x2E6E461E, 0x006E461E, Shader.TileMode.CLAMP)
        cv.drawRect(bx0, by1, bx1, by1 + h * 0.035f, fill); fill.shader = null
        // skirting boards
        val sk = h * 0.024f
        poly(cv, bx0, by1 - sk, bx1, by1 - sk, bx1, by1, bx0, by1, color = t(0xFFFFF9F1.toInt()))
        for (side in intArrayOf(-1, 1)) {
            g.sidePoint(side, 0f, 1f - 0.024f, a); g.sidePoint(side, 1f, 1f - 0.024f, b)
            val xf0 = if (side < 0) 0f else w; val xb0 = if (side < 0) bx0 else bx1
            poly(cv, xf0, a[1], b[0], b[1], xb0, by1, xf0, h, color = t(if (side < 0) 0xFFF7EEE3.toInt() else 0xFFF1E6D8.toInt()))
        }
        // the room's edges and a faint warm vignette
        line.color = ta(0xFF9C7650.toInt(), 0.22f); line.strokeWidth = h * 0.0035f
        cv.drawLine(bx0, by0, bx0, by1, line); cv.drawLine(bx1, by0, bx1, by1, line); cv.drawLine(bx0, by1, bx1, by1, line)
        cv.drawLine(0f, 0f, bx0, by0, line); cv.drawLine(w, 0f, bx1, by0, line); cv.drawLine(0f, h, bx0, by1, line); cv.drawLine(w, h, bx1, by1, line)
        val vig = if (night) 0x101030 else 0x784C24
        fill.color = OPAQUE; fill.shader = RadialGradient(w / 2f, h * 0.55f, hypot(w, h) * 0.62f, intArrayOf(vig, vig, (if (night) 0x66 else 0x29) shl 24 or vig), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, fill); fill.shader = null

        if ("rug" in furniture) rug(cv, g)
        if (night) lampLight(cv, g)
    }

    /** the lamp is always in the room and is lit in dark mode: a warm pool of light over the wall and the floor around it */
    fun lampSpot(g: RoomGeo): FloatArray { val p = FloatArray(3); g.floorPoint(LAMP_U, LAMP_V, p); return p }

    private fun lampLight(cv: Canvas, g: RoomGeo) {
        val p = lampSpot(g)
        val s = g.h * 0.1f * p[2]
        val cx = p[0]; val cy = p[1] - s * 2.9f
        fill.color = OPAQUE
        fill.shader = RadialGradient(cx, cy, g.h * 0.66f, intArrayOf(0x70FFD98A, 0x38FFC878, 0x12FFB864, 0x00FFB864), floatArrayOf(0f, 0.28f, 0.62f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, g.w, g.h, fill); fill.shader = null
    }

    fun windowRect(g: RoomGeo): RectF { val ww = g.wallW * 0.32f; val wh = g.wallH * 0.5f; val x = (g.bx0 + g.bx1) / 2f - ww / 2f; val y = g.by0 + g.wallH * 0.11f; return RectF(x, y, x + ww, y + wh) }

    private fun drawWindow(cv: Canvas, g: RoomGeo) {
        val r = windowRect(g); val x = r.left; val y = r.top; val w = r.width(); val h = r.height()
        val fr = w * 0.055f; val top = w * 0.3f
        val rodY = y - fr - h * 0.06f
        // the frame, with a soft shadow on the wall
        path.reset(); rect.set(x - fr, y - fr, x + w + fr, y + h + fr)
        path.addRoundRect(rect, floatArrayOf(top + fr, top + fr, top + fr, top + fr, 6f * d, 6f * d, 6f * d, 6f * d), Path.Direction.CW)
        fill.color = t(0xFFFFFDF8.toInt()); shadowed(10f * d, 4f * d, 0x2E784F28) { cv.drawPath(path, fill) }
        line.color = ta(0xFFC9A27A.toInt(), 0.55f); line.strokeWidth = 1.2f * d; cv.drawPath(path, line)
        val glass = Path().apply { addRoundRect(RectF(x, y, x + w, y + h), floatArrayOf(top, top, top, top, 3f * d, 3f * d, 3f * d, 3f * d), Path.Direction.CW) }
        cv.save(); cv.clipPath(glass)
        windowScene(cv, x, y, w, h)
        poly(cv, x + w * 0.1f, y + h, x + w * 0.3f, y + h, x + w * 0.75f, y, x + w * 0.55f, y, color = if (night) 0x12FFFFFF else 0x38FFFFFF)
        poly(cv, x + w * 0.36f, y + h, x + w * 0.42f, y + h, x + w * 0.87f, y, x + w * 0.81f, y, color = if (night) 0x0AFFFFFF else 0x1FFFFFFF)
        cv.restore()
        line.color = ta(0xFF9C7650.toInt(), 0.25f); line.strokeWidth = 1.2f * d; cv.drawPath(glass, line)
        // the bars
        val bw = w * 0.04f
        fill.color = t(0xFFFFFDF8.toInt())
        cv.drawRect(x + w / 2f - bw / 2f, y, x + w / 2f + bw / 2f, y + h, fill)
        cv.drawRect(x, y + h * 0.56f - bw / 2f, x + w, y + h * 0.56f + bw / 2f, fill)
        line.color = ta(0xFFC9A27A.toInt(), 0.35f); line.strokeWidth = 0.8f * d
        cv.drawRect(x + w / 2f - bw / 2f, y, x + w / 2f + bw / 2f, y + h, line)
        cv.drawRect(x, y + h * 0.56f - bw / 2f, x + w, y + h * 0.56f + bw / 2f, line)
        // the sill
        rect.set(x - fr * 2f, y + h + fr * 0.4f, x + w + fr * 2f, y + h + fr * 0.4f + h * 0.075f)
        fill.color = t(0xFFFFFBF4.toInt()); shadowed(6f * d, 3f * d, 0x38784F28) { cv.drawRoundRect(rect, 4f * d, 4f * d, fill) }
        line.color = ta(0xFFC9A27A.toInt(), 0.5f); line.strokeWidth = d; cv.drawRoundRect(rect, 4f * d, 4f * d, line)
        // curtains tied back with a periwinkle band, and the rod
        for (side in intArrayOf(-1, 1)) {
            val edge = if (side < 0) x - fr else x + w + fr
            val outer = edge + side * w * 0.2f; val inner = edge - side * w * 0.08f
            val tieY = y + h * 0.58f; val bottom = y + h + fr * 0.2f
            val cp = Path().apply {
                moveTo(outer, rodY); lineTo(inner, rodY)
                quadTo(inner + side * w * 0.03f, tieY - h * 0.2f, edge + side * w * 0.06f, tieY)
                quadTo(edge + side * w * 0.02f, tieY + h * 0.2f, edge + side * w * 0.01f, bottom)
                lineTo(outer + side * w * 0.03f, bottom)
                quadTo(outer + side * w * 0.05f, tieY + h * 0.15f, outer - side * w * 0.005f, tieY)
                quadTo(outer + side * w * 0.01f, tieY - h * 0.2f, outer, rodY); close()
            }
            fill.color = OPAQUE; fill.shader = lin(outer, 0f, inner, 0f, intArrayOf(0xFFF6EADB.toInt(), 0xFFFFFDF8.toInt(), 0xFFF3E5D3.toInt(), 0xFFFFFCF6.toInt()), floatArrayOf(0f, 0.35f, 0.6f, 1f))
            shadowed(6f * d, 3f * d, 0x29784F28) { cv.drawPath(cp, fill) }; fill.shader = null
            line.color = ta(0xFFC9A27A.toInt(), 0.6f); line.strokeWidth = d; cv.drawPath(cp, line)
            cv.save(); cv.clipPath(cp)
            rect.set(min(edge, outer) - w * 0.3f, tieY - h * 0.03f, min(edge, outer) + w * 0.6f, tieY + h * 0.03f)
            fill.color = t(0xFF8FAAF0.toInt()); cv.drawRect(rect, fill)
            line.color = ta(0xFF3F5DBD.toInt(), 0.45f); cv.drawRect(rect, line)
            cv.restore()
        }
        line.color = t(0xFFB98A5E.toInt()); line.strokeWidth = 3f * d
        cv.drawLine(x - fr - w * 0.24f, rodY, x + w + fr + w * 0.24f, rodY, line)
        fill.color = t(0xFFB98A5E.toInt()); cv.drawCircle(x - fr - w * 0.25f, rodY, 4f * d, fill); cv.drawCircle(x + w + fr + w * 0.25f, rodY, 4f * d, fill)
    }

    /** the view out of the window: the icon's city */
    private fun windowScene(cv: Canvas, x: Float, y: Float, w: Float, h: Float) {
        if (night) { nightScene(cv, x, y, w, h); return }
        fill.color = OPAQUE; fill.shader = lin(0f, y, 0f, y + h, intArrayOf(0xFF8DBAF6.toInt(), 0xFFC3DBFB.toInt(), 0xFFDCEAFD.toInt()), floatArrayOf(0f, 0.7f, 1f))
        cv.drawRect(x, y, x + w, y + h, fill); fill.shader = null
        Pics.cloud(cv, ink, x + w * 0.27f, y + h * 0.3f, w * 0.13f, t(Look.WHITE), ta(0xFFC9D8F2.toInt(), 0.6f))
        Pics.cloud(cv, ink, x + w * 0.8f, y + h * 0.2f, w * 0.09f, t(Look.WHITE), ta(0xFFC9D8F2.toInt(), 0.6f))
        val ground = y + h * 0.86f
        for ((bi, bld) in BUILDINGS.withIndex()) {
            val bx = x + w * bld[0]; val bw = w * bld[1]; val bh = h * bld[2]
            path.reset(); rect.set(bx, ground - bh, bx + bw, ground + 2f)
            path.addRoundRect(rect, floatArrayOf(w * 0.015f, w * 0.015f, w * 0.015f, w * 0.015f, 0f, 0f, 0f, 0f), Path.Direction.CW)
            fill.color = t(BUILDING_COLORS[bi]); cv.drawPath(path, fill)
            fill.color = ta(0xFFE6EEFB.toInt(), 0.85f)
            val rows = max(2, (bld[2] * 9).toInt())
            for (rw in 0 until rows) for (cl in 0 until 2) {
                val wx = bx + bw * (0.18f + cl * 0.42f); val wy = ground - bh + h * 0.05f + rw * h * 0.07f
                cv.drawRect(wx, wy, wx + bw * 0.24f, wy + h * 0.035f, fill)
            }
        }
        for (tr in TREES) {
            val cx = x + w * tr[0]; val rr = w * tr[1] * 0.55f
            fill.color = t(0xFF7FB57A.toInt()); cv.drawCircle(cx, ground - rr * 0.7f, rr, fill)
            fill.color = t(0xFFA2C97F.toInt()); cv.drawCircle(cx - rr * 0.25f, ground - rr * 0.95f, rr * 0.7f, fill)
        }
        fill.color = t(0xFFCFC7C3.toInt()); cv.drawRect(x, ground, x + w, y + h, fill)
        line.color = 0xE6FFFFFF.toInt(); line.strokeWidth = h * 0.012f
        val ry = ground + (y + h - ground) * 0.55f
        for (i in 0 until 5) { val dx = x + w * (0.06f + i * 0.22f); cv.drawLine(dx, ry, dx + w * 0.1f, ry, line) }
    }

    /**
     * the same city after dark: a deep blue sky with stars and a moon, dark blue buildings with a few lit windows,
     * dark round trees and a quiet road. (Painted with its own colours, not tinted from the day view.)
     */
    private fun nightScene(cv: Canvas, x: Float, y: Float, w: Float, h: Float) {
        fill.color = OPAQUE
        fill.shader = LinearGradient(0f, y, 0f, y + h, intArrayOf(0xFF0B1433.toInt(), 0xFF1B2A5C.toInt(), 0xFF34457F.toInt()), floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(x, y, x + w, y + h, fill); fill.shader = null
        // stars: always the same ones, so the picture does not flicker when it is repainted
        for (i in STARS.indices step 3) {
            val sx = x + w * STARS[i]; val sy = y + h * STARS[i + 1] * 0.62f; val sr = w * STARS[i + 2]
            fill.color = Look.alpha(0xFFFFF6D8.toInt(), 0.55f + 0.45f * ((i / 3) % 3) / 2f)
            cv.drawCircle(sx, sy, sr, fill)
        }
        // the moon, a crescent in a soft halo
        val mx = x + w * 0.74f; val my = y + h * 0.22f; val mr = w * 0.075f
        fill.color = OPAQUE
        fill.shader = RadialGradient(mx, my, mr * 3.4f, 0x40FFF1C2, 0x00FFF1C2, Shader.TileMode.CLAMP)
        cv.drawCircle(mx, my, mr * 3.4f, fill); fill.shader = null
        fill.color = 0xFFFFF1C2.toInt(); cv.drawCircle(mx, my, mr, fill)
        fill.color = 0xFF1B2A5C.toInt(); cv.drawCircle(mx + mr * 0.5f, my - mr * 0.18f, mr * 0.88f, fill)
        Pics.cloud(cv, ink, x + w * 0.25f, y + h * 0.32f, w * 0.12f, 0xFF3A4A86.toInt(), 0xFF2B3A70.toInt())
        val ground = y + h * 0.86f
        for ((bi, bld) in BUILDINGS.withIndex()) {
            val bx = x + w * bld[0]; val bw = w * bld[1]; val bh = h * bld[2]
            path.reset(); rect.set(bx, ground - bh, bx + bw, ground + 2f)
            path.addRoundRect(rect, floatArrayOf(w * 0.015f, w * 0.015f, w * 0.015f, w * 0.015f, 0f, 0f, 0f, 0f), Path.Direction.CW)
            fill.color = NIGHT_BUILDING_COLORS[bi]; cv.drawPath(path, fill)
            val rows = max(2, (bld[2] * 9).toInt())
            for (rw in 0 until rows) for (cl in 0 until 2) {
                val wx = bx + bw * (0.18f + cl * 0.42f); val wy = ground - bh + h * 0.05f + rw * h * 0.07f
                val lit = ((bi * 7 + rw * 5 + cl * 3) % 5) < 2   // some windows are lit, always the same ones
                fill.color = if (lit) 0xFFFFD98A.toInt() else 0xFF3A4678.toInt()
                cv.drawRect(wx, wy, wx + bw * 0.24f, wy + h * 0.035f, fill)
            }
        }
        for ((ti, tr) in TREES.withIndex()) {
            val cx = x + w * tr[0]; val rr = w * tr[1] * 0.55f
            fill.color = 0xFF24433F.toInt(); cv.drawCircle(cx, ground - rr * 0.7f, rr, fill)
            fill.color = if (ti % 2 == 0) 0xFF30584F.toInt() else 0xFF2B4E49.toInt(); cv.drawCircle(cx - rr * 0.25f, ground - rr * 0.95f, rr * 0.7f, fill)
        }
        fill.color = 0xFF3A3F58.toInt(); cv.drawRect(x, ground, x + w, y + h, fill)
        line.color = 0x99C9D0E8.toInt(); line.strokeWidth = h * 0.012f
        val ry = ground + (y + h - ground) * 0.55f
        for (i in 0 until 5) { val dx = x + w * (0.06f + i * 0.22f); cv.drawLine(dx, ry, dx + w * 0.1f, ry, line) }
    }

    // ================================================================== furniture
    fun clockSpot(g: RoomGeo) = Triple(g.bx0 + g.wallW * 0.15f, g.by0 + g.wallH * 0.3f, g.wallH * 0.1f)

    /** the clock without its hands (they move, so they are drawn every frame: [clockHands]) */
    fun clockFace(cv: Canvas, cx: Float, cy: Float, r: Float) {
        val ow = max(1.4f * d, r * 0.08f)
        fill.color = t(0xFFA06E48.toInt()); shadowed(r * 0.35f, r * 0.12f, 0x40784F28) { cv.drawCircle(cx, cy, r * 1.14f, fill) }
        fill.color = OPAQUE; fill.shader = RadialGradient(cx - r * 0.2f, cy - r * 0.3f, r * 1.3f, intArrayOf(t(0xFFB98256.toInt()), t(0xFFB98256.toInt()), t(0xFF8E5F3D.toInt())), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r * 1.14f, fill); fill.shader = null
        line.color = t(Look.COCOA); line.strokeWidth = ow; cv.drawCircle(cx, cy, r * 1.14f, line)
        fill.color = OPAQUE; fill.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.4f, r * 1.2f, t(0xFFFFFDF7.toInt()), t(0xFFF6EBDB.toInt()), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r * 0.93f, fill); fill.shader = null
        line.strokeWidth = ow * 0.6f; cv.drawCircle(cx, cy, r * 0.93f, line)
        fill.color = t(Look.COCOA)
        for (i in 0 until 12) { val an = (i * PI / 6).toFloat(); cv.drawCircle(cx + sin(an) * r * 0.74f, cy - cos(an) * r * 0.74f, r * (if (i % 3 == 0) 0.075f else 0.045f), fill) }
    }

    /** the clock's hands at the real time, a honey pin and a glint on the glass */
    fun clockHands(cv: Canvas, cx: Float, cy: Float, r: Float, hour: Float, minute: Float) {
        val ah = (hour * PI / 6).toFloat(); val am = (minute * PI / 30).toFloat()
        line.color = t(Look.COCOA)
        line.strokeWidth = r * 0.12f; cv.drawLine(cx, cy, cx + sin(ah) * r * 0.45f, cy - cos(ah) * r * 0.45f, line)
        line.strokeWidth = r * 0.08f; cv.drawLine(cx, cy, cx + sin(am) * r * 0.66f, cy - cos(am) * r * 0.66f, line)
        fill.color = t(Look.HONEY); cv.drawCircle(cx, cy, r * 0.09f, fill)
        line.strokeWidth = max(1f, r * 0.04f); cv.drawCircle(cx, cy, r * 0.09f, line)
        line.color = 0xB3FFFFFF.toInt(); line.strokeWidth = r * 0.07f
        rect.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f); cv.drawArc(rect, 198f, 63f, false, line)
    }

    /** the picture frame: a little periwinkle car with the bear in it, like the icon */
    fun picture(cv: Canvas, x0: Float, y0: Float, w: Float, h: Float) {
        val ow = max(1.2f * d, w * 0.022f); val f = w * 0.09f
        rect.set(x0 - f, y0 - f, x0 + w + f, y0 + h + f)
        fill.color = t(0xFFD39C5F.toInt()); shadowed(8f * d, 3f * d, 0x40784F28) { cv.drawRoundRect(rect, f * 0.6f, f * 0.6f, fill) }
        fill.color = OPAQUE; fill.shader = lin(0f, y0 - f, 0f, y0 + h + f, 0xFFE2AE70.toInt(), 0xFFC68A4C.toInt()); cv.drawRoundRect(rect, f * 0.6f, f * 0.6f, fill); fill.shader = null
        line.color = t(Look.COCOA); line.strokeWidth = ow; cv.drawRoundRect(rect, f * 0.6f, f * 0.6f, line)
        cv.save(); rect.set(x0, y0, x0 + w, y0 + h); cv.clipRect(rect)
        fill.color = OPAQUE; fill.shader = lin(0f, y0, 0f, y0 + h, 0xFFA8CCF9.toInt(), 0xFFE1EEFD.toInt()); cv.drawRect(rect, fill); fill.shader = null
        fill.color = t(0xFFFFE9A0.toInt()); cv.drawCircle(x0 + w * 0.78f, y0 + h * 0.22f, h * 0.09f, fill)
        path.reset(); path.moveTo(x0, y0 + h); path.lineTo(x0, y0 + h * 0.6f); path.quadTo(x0 + w * 0.3f, y0 + h * 0.42f, x0 + w * 0.62f, y0 + h * 0.64f)
        path.quadTo(x0 + w * 0.85f, y0 + h * 0.5f, x0 + w, y0 + h * 0.58f); path.lineTo(x0 + w, y0 + h); path.close()
        fill.color = t(0xFF9CCB86.toInt()); cv.drawPath(path, fill)
        fill.color = t(0xFFCFC7C3.toInt()); cv.drawRect(x0, y0 + h * 0.82f, x0 + w, y0 + h, fill)
        val cx = x0 + w * 0.42f; val cy = y0 + h * 0.8f; val s = w * 0.2f
        rect.set(cx - s, cy - s * 0.42f, cx + s, cy + s * 0.08f)
        fill.color = t(0xFF86A6EE.toInt()); cv.drawRoundRect(rect, s * 0.25f, s * 0.25f, fill)
        line.color = t(Look.NAVY_LINE); line.strokeWidth = ow * 0.6f; cv.drawRoundRect(rect, s * 0.25f, s * 0.25f, line)
        fill.color = t(Look.HONEY); cv.drawCircle(cx - s * 0.1f, cy - s * 0.55f, s * 0.32f, fill)
        line.color = t(Look.COCOA); line.strokeWidth = ow * 0.5f; cv.drawCircle(cx - s * 0.1f, cy - s * 0.55f, s * 0.32f, line)
        fill.color = t(0xFF4A4245.toInt()); cv.drawCircle(cx - s * 0.55f, cy + s * 0.08f, s * 0.2f, fill); cv.drawCircle(cx + s * 0.55f, cy + s * 0.08f, s * 0.2f, fill)
        cv.restore()
        line.color = t(Look.COCOA); line.strokeWidth = ow * 0.6f; rect.set(x0, y0, x0 + w, y0 + h); cv.drawRect(rect, line)
    }

    /** the round periwinkle rug, laid on the floor in perspective */
    private fun rug(cv: Canvas, g: RoomGeo) {
        fun ring(ru: Float, rv: Float): Path { val p = Path(); for (i in 0..64) { val an = (i / 64f * 2 * PI).toFloat(); g.floorPoint(cos(an) * ru, 0.55f + sin(an) * rv, a); if (i == 0) p.moveTo(a[0], a[1]) else p.lineTo(a[0], a[1]) }; p.close(); return p }
        val outer = ring(0.6f, 0.3f)
        cv.save(); cv.translate(0f, 3f * d); fill.color = 0x1F6E461E; cv.drawPath(outer, fill); cv.restore()
        fill.color = t(0xFFFFF4E4.toInt()); cv.drawPath(outer, fill)
        line.color = ta(0xFFC9A27A.toInt(), 0.7f); line.strokeWidth = 1.2f * d; cv.drawPath(outer, line)
        fill.color = t(0xFFAFC6F6.toInt()); cv.drawPath(ring(0.54f, 0.26f), fill)
        line.color = t(0xFFFFF4E4.toInt()); line.strokeWidth = 3f * d; line.pathEffect = DashPathEffect(floatArrayOf(2f * d, 6f * d), 0f)
        cv.drawPath(ring(0.43f, 0.2f), line); line.pathEffect = null
        fill.color = t(0xFF93B0F0.toInt()); cv.drawPath(ring(0.33f, 0.15f), fill)
        fill.color = ta(0xFFFFF4E4.toInt(), 0.9f); cv.drawPath(ring(0.15f, 0.07f), fill)
    }

    /** the potted plant, standing on the floor at (x, ground); [s] is its size unit */
    fun plant(cv: Canvas, x: Float, ground: Float, s: Float) {
        val ow = max(1.2f * d, s * 0.09f)
        fill.color = 0x386E461E; rect.set(x - s * 0.9f, ground - s * 0.2f, x + s * 0.9f, ground + s * 0.2f); cv.drawOval(rect, fill)
        for ((li, lf) in LEAVES.withIndex()) {
            val len = lf[1]; val col = LEAF_COLORS[li]
            cv.save(); cv.translate(x, ground - s * 1.15f); cv.rotate(lf[0])
            path.reset(); path.moveTo(0f, 0f); path.quadTo(s * 0.48f, -s * len * 0.5f, 0f, -s * len); path.quadTo(-s * 0.48f, -s * len * 0.5f, 0f, 0f); path.close()
            fill.color = OPAQUE; fill.shader = lin(0f, 0f, 0f, -s * len, Look.shade(col, 0.86f), col); cv.drawPath(path, fill); fill.shader = null
            line.color = t(Look.COCOA); line.strokeWidth = ow; cv.drawPath(path, line)
            line.color = ta(0xFF3F7F45.toInt(), 0.6f); line.strokeWidth = ow * 0.6f; cv.drawLine(0f, -s * 0.2f, 0f, -s * len * 0.8f, line)
            cv.restore()
        }
        path.reset(); path.moveTo(x - s * 0.72f, ground - s * 1.35f); path.lineTo(x + s * 0.72f, ground - s * 1.35f); path.lineTo(x + s * 0.52f, ground)
        path.quadTo(x, ground + s * 0.08f, x - s * 0.52f, ground); path.close()
        fill.color = OPAQUE; fill.shader = lin(x - s * 0.7f, 0f, x + s * 0.7f, 0f, intArrayOf(0xFFEFA77A.toInt(), 0xFFE4925F.toInt(), 0xFFC9744A.toInt()), floatArrayOf(0f, 0.6f, 1f)); cv.drawPath(path, fill); fill.shader = null
        line.color = t(Look.COCOA); line.strokeWidth = ow; cv.drawPath(path, line)
        rect.set(x - s * 0.8f, ground - s * 1.5f, x + s * 0.8f, ground - s * 1.2f)
        fill.color = t(0xFFF3B88E.toInt()); cv.drawRoundRect(rect, s * 0.1f, s * 0.1f, fill); cv.drawRoundRect(rect, s * 0.1f, s * 0.1f, line)
    }

    /** the floor lamp: always in the room (it is not something you buy). Switched off by day, lit with a warm glow in dark mode. */
    fun lamp(cv: Canvas, x: Float, ground: Float, s: Float, on: Boolean = false) {
        val ow = max(1.2f * d, s * 0.08f)
        if (on) { fill.color = OPAQUE; fill.shader = RadialGradient(x, ground - s * 3f, s * 2.2f, 0xB3FFE28C.toInt(), 0x00FFE28C, Shader.TileMode.CLAMP); cv.drawCircle(x, ground - s * 3f, s * 2.2f, fill); fill.shader = null }
        fill.color = 0x386E461E; rect.set(x - s * 0.8f, ground - s * 0.18f, x + s * 0.8f, ground + s * 0.18f); cv.drawOval(rect, fill)
        line.color = t(Look.COCOA); line.strokeWidth = s * 0.16f + ow; cv.drawLine(x, ground - s * 2.5f, x, ground - s * 0.1f, line)
        line.color = t(0xFF8C6447.toInt()); line.strokeWidth = s * 0.16f; cv.drawLine(x, ground - s * 2.5f, x, ground - s * 0.1f, line)
        rect.set(x - s * 0.55f, ground - s * 0.22f, x + s * 0.55f, ground + s * 0.06f)
        fill.color = t(0xFF8C6447.toInt()); cv.drawOval(rect, fill); line.color = t(Look.COCOA); line.strokeWidth = ow; cv.drawOval(rect, line)
        path.reset(); path.moveTo(x - s * 0.45f, ground - s * 3.45f); path.lineTo(x + s * 0.45f, ground - s * 3.45f); path.lineTo(x + s * 0.85f, ground - s * 2.3f)
        path.quadTo(x, ground - s * 2.18f, x - s * 0.85f, ground - s * 2.3f); path.close()
        // lit: a bright warm shade (not tinted by the night); off: a pale, dull shade
        val shade = if (on) intArrayOf(0xFFFFEDB0.toInt(), 0xFFFFD978.toInt(), 0xFFF5BE55.toInt()) else intArrayOf(t(0xFFF1E3C2.toInt()), t(0xFFE6D2A6.toInt()), t(0xFFD3B97E.toInt()))
        fill.color = OPAQUE; fill.shader = LinearGradient(x - s, 0f, x + s, 0f, shade, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP); cv.drawPath(path, fill); fill.shader = null
        cv.drawPath(path, line)
        if (on) {
            // the bulb's light under the shade
            fill.color = 0xFFFFF6CC.toInt(); rect.set(x - s * 0.5f, ground - s * 2.36f, x + s * 0.5f, ground - s * 2.12f); cv.drawOval(rect, fill)
        }
        line.color = if (on) 0xCCFFFFFF.toInt() else 0x99FFFFFF.toInt(); line.strokeWidth = ow; cv.drawLine(x - s * 0.32f, ground - s * 3.3f, x - s * 0.55f, ground - s * 2.5f, line)
    }

    /** a standing item as a small bitmap with its position, so it can be drawn in front of or behind the pet cheaply */
    class Sprite(val bmp: Bitmap, val left: Float, val top: Float)

    fun plantSprite(x: Float, ground: Float, s: Float): Sprite = sprite(x - s * 3.2f, ground - s * 4.8f, x + s * 3.2f, ground + s * 0.4f) { plant(it, x, ground, s) }
    fun lampSprite(x: Float, ground: Float, s: Float, on: Boolean = false): Sprite = sprite(x - s * 2.4f, ground - s * 5.3f, x + s * 2.4f, ground + s * 0.4f) { lamp(it, x, ground, s, on) }

    private fun sprite(l: Float, tp: Float, r: Float, bt: Float, draw: (Canvas) -> Unit): Sprite {
        val bmp = Bitmap.createBitmap(max(1, (r - l).toInt() + 2), max(1, (bt - tp).toInt() + 2), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); cv.translate(-l, -tp); draw(cv)
        return Sprite(bmp, l, tp)
    }

    companion object {
        const val LAMP_U = 0.84f   // where the floor lamp stands (floor coordinates)
        const val LAMP_V = 0.26f
        private const val RAIL = 0.70f   // the chair rail: 70% down the wall
        // stars in the night window: x, y, radius (fractions of the window)
        private val STARS = floatArrayOf(0.1f, 0.12f, 0.012f, 0.22f, 0.4f, 0.009f, 0.35f, 0.08f, 0.011f, 0.48f, 0.3f, 0.008f, 0.6f, 0.14f, 0.01f, 0.88f, 0.42f, 0.009f, 0.93f, 0.1f, 0.011f, 0.07f, 0.5f, 0.008f, 0.55f, 0.52f, 0.007f, 0.82f, 0.62f, 0.008f)
        private val NIGHT_BUILDING_COLORS = intArrayOf(0xFF2A3562.toInt(), 0xFF232D58.toInt(), 0xFF313C6C.toInt(), 0xFF283360.toInt(), 0xFF2F3A69.toInt(), 0xFF232D58.toInt())
        // buildings in the window: x, width, height (fractions of the window) and their colours
        val BUILDINGS = arrayOf(floatArrayOf(0.02f, 0.17f, 0.42f), floatArrayOf(0.17f, 0.14f, 0.58f), floatArrayOf(0.3f, 0.12f, 0.36f), floatArrayOf(0.62f, 0.15f, 0.5f), floatArrayOf(0.76f, 0.12f, 0.34f), floatArrayOf(0.86f, 0.16f, 0.46f))
        val BUILDING_COLORS = intArrayOf(0xFFA9BDE6.toInt(), 0xFF9BB2E0.toInt(), 0xFFB8CAEC.toInt(), 0xFFA3B9E4.toInt(), 0xFFB4C6EA.toInt(), 0xFF9BB2E0.toInt())
        val TREES = arrayOf(floatArrayOf(0.12f, 0.08f), floatArrayOf(0.24f, 0.1f), floatArrayOf(0.46f, 0.09f), floatArrayOf(0.56f, 0.07f), floatArrayOf(0.7f, 0.1f), floatArrayOf(0.93f, 0.09f))
        // the plant's leaves: angle, length, and their colours
        val LEAVES = arrayOf(floatArrayOf(-48f, 3.0f), floatArrayOf(-20f, 3.4f), floatArrayOf(8f, 3.2f), floatArrayOf(32f, 3.0f), floatArrayOf(56f, 2.5f))
        val LEAF_COLORS = intArrayOf(0xFF6FB06E.toInt(), 0xFF86C47F.toInt(), 0xFF6FB06E.toInt(), 0xFF86C47F.toInt(), 0xFF7DBB76.toInt())
    }
}
