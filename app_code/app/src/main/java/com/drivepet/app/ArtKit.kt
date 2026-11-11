package com.drivepet.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The little drawing kit the item pictures are made with: flat colour shapes with the brown outline of the app's characters,
 * in a unit square (-1..1, y down). Colours are written as 0xAARRGGBB longs to keep the drawings short.
 */
internal open class ArtKit {
    protected val f = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val l = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    protected val p = Path()
    protected val r = RectF()
    protected val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    companion object { const val INK = 0xFF4A3426; const val W = 0.05f }

    protected fun col(c: Long) = c.toInt()

    /** a closed polygon through the points */
    protected fun poly(vararg v: Float): Path { p.reset(); p.moveTo(v[0], v[1]); var i = 2; while (i < v.size) { p.lineTo(v[i], v[i + 1]); i += 2 }; p.close(); return p }

    /** fills [path] and draws the outline */
    protected fun sh(cv: Canvas, path: Path, c: Long, w: Float = W) {
        f.shader = null; f.color = col(c); cv.drawPath(path, f)
        if (w > 0f) { l.color = col(INK); l.strokeWidth = w; cv.drawPath(path, l) }
    }
    protected fun ov(cv: Canvas, a: Float, b: Float, c: Float, d: Float, k: Long, w: Float = W) {
        r.set(a, b, c, d); f.shader = null; f.color = col(k); cv.drawOval(r, f)
        if (w > 0f) { l.color = col(INK); l.strokeWidth = w; cv.drawOval(r, l) }
    }
    protected fun ci(cv: Canvas, x: Float, y: Float, rad: Float, k: Long, w: Float = W) = ov(cv, x - rad, y - rad, x + rad, y + rad, k, w)
    protected fun rr(cv: Canvas, a: Float, b: Float, c: Float, d: Float, rad: Float, k: Long, w: Float = W) {
        r.set(a, b, c, d); f.shader = null; f.color = col(k); cv.drawRoundRect(r, rad, rad, f)
        if (w > 0f) { l.color = col(INK); l.strokeWidth = w; cv.drawRoundRect(r, rad, rad, l) }
    }
    protected fun ln(cv: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, k: Long, w: Float = W) { l.color = col(k); l.strokeWidth = w; cv.drawLine(x1, y1, x2, y2, l) }
    protected fun dot(cv: Canvas, x: Float, y: Float, rad: Float, k: Long) { f.shader = null; f.color = col(k); cv.drawCircle(x, y, rad, f) }
    protected fun curve(cv: Canvas, k: Long, w: Float, vararg v: Float) {   // an open quadratic chain: start, then (control, end) pairs
        p.reset(); p.moveTo(v[0], v[1]); var i = 2; while (i + 3 < v.size + 0) { p.quadTo(v[i], v[i + 1], v[i + 2], v[i + 3]); i += 4 }
        l.color = col(k); l.strokeWidth = w; cv.drawPath(p, l)
    }
    protected fun text(cv: Canvas, s: String, x: Float, y: Float, size: Float, k: Long) { t.textSize = size; t.color = col(k); cv.drawText(s, x, y, t) }
    protected fun rot(cv: Canvas, deg: Float, x: Float, y: Float, block: () -> Unit) { cv.save(); cv.rotate(deg, x, y); block(); cv.restore() }

    protected fun steam(cv: Canvas, vararg xs: Float) {
        for (x in xs) { p.reset(); p.moveTo(x, -0.45f); p.quadTo(x - 0.1f, -0.6f, x, -0.74f); p.quadTo(x + 0.1f, -0.86f, x, -0.98f); l.color = 0xCCFFFFFF.toInt(); l.strokeWidth = 0.055f; cv.drawPath(p, l) }
    }

    /** a deep round bowl seen from the side and a little above; the food goes inside the oval -0.85..0.85 x -0.32..0.08 */
    protected fun bowl(cv: Canvas, body: Long, soup: Long, band: Long? = null) {
        p.reset(); p.moveTo(-0.9f, -0.12f); p.cubicTo(-0.86f, 0.72f, 0.86f, 0.72f, 0.9f, -0.12f); p.close(); sh(cv, p, body)
        if (band != null) { p.reset(); p.moveTo(-0.82f, 0.18f); p.cubicTo(-0.6f, 0.5f, 0.6f, 0.5f, 0.82f, 0.18f); l.color = col(band); l.strokeWidth = 0.07f; cv.drawPath(p, l) }
        rr(cv, -0.3f, 0.52f, 0.3f, 0.66f, 0.05f, body)   // the foot
        ov(cv, -0.9f, -0.34f, 0.9f, 0.1f, soup)
    }

    /** a black stone pot (ttukbaegi) */
    protected fun pot(cv: Canvas, soup: Long) {
        p.reset(); p.moveTo(-0.86f, -0.12f); p.lineTo(-0.78f, 0.58f); p.quadTo(0f, 0.74f, 0.78f, 0.58f); p.lineTo(0.86f, -0.12f); p.close(); sh(cv, p, 0xFF4A3A36)
        ln(cv, -0.8f, 0.2f, 0.8f, 0.2f, 0x55FFFFFF, 0.04f)
        ov(cv, -0.86f, -0.34f, 0.86f, 0.1f, soup)
    }

    /** a flat round plate; the food goes on the oval -0.8..0.8 x -0.2..0.45 */
    protected fun plate(cv: Canvas, k: Long = 0xFFFFFCF6, rim: Long = 0xFFDCE6F7) {
        ov(cv, -0.98f, -0.25f, 0.98f, 0.62f, rim); ov(cv, -0.8f, -0.15f, 0.8f, 0.5f, k, 0.03f)
    }

    /** a round iron grill or pan */
    protected fun grill(cv: Canvas, handles: Boolean = false) {
        if (handles) { rr(cv, -1f, 0.05f, -0.78f, 0.2f, 0.06f, 0xFF3A3A44); rr(cv, 0.78f, 0.05f, 1f, 0.2f, 0.06f, 0xFF3A3A44) }
        ov(cv, -0.92f, -0.3f, 0.92f, 0.62f, 0xFF3A3A44); ov(cv, -0.8f, -0.22f, 0.8f, 0.52f, 0xFF55555F, 0.03f)
    }

    protected fun leaf(cv: Canvas, x: Float, y: Float, len: Float, wid: Float, deg: Float, k: Long = 0xFF5DB85C) {
        rot(cv, deg, x, y) {
            p.reset(); p.moveTo(x, y); p.quadTo(x + len * 0.5f, y - wid, x + len, y); p.quadTo(x + len * 0.5f, y + wid, x, y); p.close(); sh(cv, p, k, 0.04f)
            ln(cv, x + len * 0.1f, y, x + len * 0.85f, y, 0x66000000, 0.025f)
        }
    }

    /** a round fruit: body, a shine, a stem and a leaf */
    protected fun fruit(cv: Canvas, k: Long, rad: Float = 0.72f, cy: Float = 0.15f, leafK: Long = 0xFF5DB85C, stem: Boolean = true) {
        ci(cv, 0f, cy, rad, k)
        ov(cv, -rad * 0.6f, cy - rad * 0.65f, -rad * 0.2f, cy - rad * 0.3f, 0x77FFFFFF, 0f)
        if (stem) { ln(cv, 0f, cy - rad + 0.04f, 0.08f, cy - rad - 0.22f, 0xFF7A5A3C, 0.08f); leaf(cv, 0.08f, cy - rad - 0.1f, 0.42f, 0.16f, -25f, leafK) }
    }

    /** the sea along the bottom, with a few waves */
    protected fun sea(cv: Canvas, top: Float, k: Long = 0xFF5B8FF9) {
        rr(cv, -0.98f, top, 0.98f, 0.9f, 0.2f, k)
        for (i in 0..2) { val y = top + 0.15f + i * 0.2f; curve(cv, 0x88FFFFFF, 0.04f, -0.6f + i * 0.2f, y, -0.45f + i * 0.2f, y - 0.06f, -0.3f + i * 0.2f, y) }
    }

    /** green ground under a building or a place */
    protected fun lawn(cv: Canvas, k: Long = 0xFF86C27F) = ov(cv, -0.98f, 0.5f, 0.98f, 0.95f, k)

    /** a Korean tiled roof with upturned eaves, between x [a]..[b], its ridge at [top] and eaves at [eave] */
    protected fun roof(cv: Canvas, a: Float, b: Float, top: Float, eave: Float, k: Long = 0xFF3E4A7A) {
        val m = (b - a) * 0.18f
        p.reset(); p.moveTo(a - 0.08f, eave - 0.06f); p.quadTo(a + m * 0.6f, eave - 0.04f, a + m, top)
        p.lineTo(b - m, top); p.quadTo(b - m * 0.6f, eave - 0.04f, b + 0.08f, eave - 0.06f); p.quadTo((a + b) / 2f, eave + 0.1f, a - 0.08f, eave - 0.06f); p.close()
        sh(cv, p, k)
        var x = a + m; while (x < b - m) { ln(cv, x, top + 0.04f, x - (x - (a + b) / 2f) * 0.25f, eave - 0.02f, 0x44FFFFFF, 0.025f); x += 0.12f }
        rr(cv, a + m - 0.04f, top - 0.07f, b - m + 0.04f, top + 0.03f, 0.03f, 0xFF2A3360)
    }
}
