package com.drivepet.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The picture of a collection item. A few items are drawn by hand because no emoji shows them right
 * (한복 would be the kimono 👘, 떡볶이 the fish-cake skewer 🍢); the rest use their emoji until the designer's art arrives.
 *
 * [sticker] draws the item as a die-cut sticker (a white outline round its own shape and a soft shadow), so on the island
 * picture it reads as "a thing I collected" and not as part of the scenery.
 */
object ItemArt {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()

    /** draws the item in a square of side [size] centred on ([cx], [cy]) */
    fun draw(cv: Canvas, item: DexItem, cx: Float, cy: Float, size: Float) {
        cv.save(); cv.translate(cx, cy)
        val u = size / 2f
        when (item.id) {
            "seoul.hanbok" -> { cv.scale(u, u); hanbok(cv) }
            "seoul.tteok" -> { cv.scale(u, u); tteokbokki(cv) }
            else -> {
                cv.save(); cv.scale(u, u)
                val drawn = ArtFood.draw(cv, item.id) || ArtGoods.draw(cv, item.id) || ArtPlaces.draw(cv, item.id)
                cv.restore()
                if (!drawn) { emoji.textSize = size * 0.82f; cv.drawText(item.emoji, 0f, size * 0.29f, emoji) }
            }
        }
        cv.restore()
    }

    /** debug: every item of the collection on one sheet, five to a row (one row per region), saved as a PNG */
    fun sheet(file: java.io.File, cell: Int = 160) {
        val regions = Korea.regions
        val bmp = Bitmap.createBitmap(cell * 5, cell * regions.size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFF1A2466.toInt())
        for ((ri, r) in regions.withIndex()) for ((i, it) in r.items.withIndex())
            sticker(c, it, cell * (i + 0.5f), cell * (ri + 0.5f), cell * 0.78f, 0xFFFFFFFF.toInt(), 1f)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- the sticker: rendered once per item and size, then reused
    private val cache = HashMap<String, Bitmap>()

    fun sticker(cv: Canvas, item: DexItem, cx: Float, cy: Float, size: Float, outline: Int, density: Float) {
        val key = "${item.id}|${size.toInt()}|$outline"
        val bmp = cache.getOrPut(key) { makeSticker(item, size, outline, density) }
        cv.drawBitmap(bmp, cx - bmp.width / 2f, cy - bmp.height / 2f, null)
    }

    private fun makeSticker(item: DexItem, size: Float, outline: Int, d: Float): Bitmap {
        val pad = (6f * d + size * 0.06f).toInt()
        val n = size.toInt() + pad * 2
        // the item alone
        val art = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        draw(Canvas(art), item, n / 2f, n / 2f, size)
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // the shadow under the sticker, then the outline: the item's own shape in one colour, stamped in a ring
        p.colorFilter = PorterDuffColorFilter(0x55141E5A, PorterDuff.Mode.SRC_IN)
        val ring = 2.6f * d + size * 0.025f
        for (k in 0 until 16) { val a = k / 16f * 2f * Math.PI; c.drawBitmap(art, (cos(a) * ring).toFloat(), (sin(a) * ring).toFloat() + 3f * d, p) }
        p.colorFilter = PorterDuffColorFilter(outline, PorterDuff.Mode.SRC_IN)
        for (k in 0 until 16) { val a = k / 16f * 2f * Math.PI; c.drawBitmap(art, (cos(a) * ring).toFloat(), (sin(a) * ring).toFloat(), p) }
        p.colorFilter = null
        c.drawBitmap(art, 0f, 0f, p)
        art.recycle()
        return out
    }

    /** a small four-pointed twinkle */
    fun twinkle(cv: Canvas, x: Float, y: Float, r: Float, color: Int) {
        path.reset()
        path.moveTo(x, y - r); path.quadTo(x, y, x + r, y); path.quadTo(x, y, x, y + r); path.quadTo(x, y, x - r, y); path.quadTo(x, y, x, y - r); path.close()
        fill.shader = null; fill.color = color; cv.drawPath(path, fill)
    }

    /** a warm light on the ground where a collected item stands */
    fun groundGlow(cv: Canvas, x: Float, y: Float, rx: Float) {
        cv.save(); cv.scale(1f, 0.42f, x, y)
        fill.shader = RadialGradient(x, y, rx, intArrayOf(0xAAFFE7A0.toInt(), 0x55FFD66B, 0x00FFD66B), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, rx, fill); fill.shader = null
        cv.restore()
    }

    // ---- hand-drawn items (unit square -1..1, y down)

    private const val INK = 0xFF4A3426.toInt()

    private fun outlined(cv: Canvas, color: Int, w: Float = 0.055f) {
        fill.shader = null; fill.color = color; cv.drawPath(path, fill)
        line.color = INK; line.strokeWidth = w; cv.drawPath(path, line)
    }

    /** a woman's hanbok: a short yellow jeogori with a long red ribbon (otgoreum) over a full, high-waisted red chima */
    private fun hanbok(cv: Canvas) {
        // chima: from just under the jacket, flaring out to the floor
        path.reset()
        path.moveTo(-0.27f, -0.42f); path.lineTo(0.27f, -0.42f)
        path.cubicTo(0.42f, 0.05f, 0.68f, 0.55f, 0.8f, 0.9f)
        path.quadTo(0f, 1.04f, -0.8f, 0.9f)
        path.cubicTo(-0.68f, 0.55f, -0.42f, 0.05f, -0.27f, -0.42f)
        outlined(cv, 0xFFE0445E.toInt())
        line.color = 0x55801028; line.strokeWidth = 0.035f   // the pleats
        for (k in -2..2) cv.drawLine(k * 0.06f, -0.3f, k * 0.27f, 0.9f, line)
        fill.color = 0xFFF26A80.toInt(); path.reset()           // a light hem band
        path.moveTo(-0.79f, 0.8f); path.quadTo(0f, 0.93f, 0.79f, 0.8f); path.lineTo(0.8f, 0.9f); path.quadTo(0f, 1.04f, -0.8f, 0.9f); path.close()
        cv.drawPath(path, fill)
        // sleeves: wide, with the soft curve under the arm (baerae) and a white cuff
        for (s in intArrayOf(-1, 1)) {
            path.reset()
            path.moveTo(s * 0.28f, -0.9f); path.lineTo(s * 0.78f, -0.66f)
            path.quadTo(s * 0.96f, -0.38f, s * 0.84f, -0.14f)
            path.lineTo(s * 0.6f, -0.2f)
            path.quadTo(s * 0.48f, -0.42f, s * 0.3f, -0.46f); path.close()
            outlined(cv, 0xFFF7D774.toInt())
            path.reset()   // the cuff (kkeutdong)
            path.moveTo(s * 0.74f, -0.68f); path.quadTo(s * 0.92f, -0.42f, s * 0.84f, -0.14f); path.lineTo(s * 0.71f, -0.17f); path.quadTo(s * 0.79f, -0.42f, s * 0.64f, -0.62f); path.close()
            outlined(cv, 0xFF6A9BE0.toInt(), 0.04f)
        }
        // the jacket body: short, ending above the chest
        path.reset()
        path.moveTo(-0.32f, -0.94f); path.lineTo(0.32f, -0.94f); path.lineTo(0.34f, -0.4f); path.quadTo(0f, -0.33f, -0.34f, -0.4f); path.close()
        outlined(cv, 0xFFF7D774.toInt())
        // the collar (git) crossing left over right, with the white strip (dongjeong)
        line.color = 0xFF7A4BB0.toInt(); line.strokeWidth = 0.13f
        cv.drawLine(-0.16f, -0.93f, 0.12f, -0.55f, line); cv.drawLine(0.16f, -0.93f, 0.05f, -0.76f, line)
        line.color = 0xFFFFFFFF.toInt(); line.strokeWidth = 0.05f
        cv.drawLine(-0.2f, -0.95f, 0.04f, -0.64f, line)
        // the long red ribbon (otgoreum) tied on the right of the chest
        line.color = 0xFFB0173F.toInt(); line.strokeWidth = 0.1f
        cv.drawLine(0.14f, -0.53f, 0.06f, 0.2f, line); cv.drawLine(0.16f, -0.53f, 0.24f, 0.08f, line)
        fill.color = 0xFFB0173F.toInt(); rect.set(0.03f, -0.62f, 0.27f, -0.46f); cv.drawOval(rect, fill)
    }

    /** a shallow bowl of tteokbokki: rice-cake sticks and a fish cake in red sauce, sesame on top */
    private fun tteokbokki(cv: Canvas) {
        // the bowl
        path.reset()
        path.moveTo(-0.92f, -0.08f); path.cubicTo(-0.86f, 0.62f, 0.86f, 0.62f, 0.92f, -0.08f); path.close()
        outlined(cv, 0xFFFFFCF6.toInt())
        line.color = 0xFF3F6FD0.toInt(); line.strokeWidth = 0.06f   // a blue band
        path.reset(); path.moveTo(-0.84f, 0.16f); path.cubicTo(-0.6f, 0.46f, 0.6f, 0.46f, 0.84f, 0.16f); cv.drawPath(path, line)
        // the sauce
        rect.set(-0.92f, -0.3f, 0.92f, 0.14f); fill.color = 0xFFD8402A.toInt(); cv.drawOval(rect, fill)
        line.color = INK; line.strokeWidth = 0.055f; cv.drawOval(rect, line)
        // a fish-cake triangle
        path.reset(); path.moveTo(0.18f, -0.36f); path.lineTo(0.62f, -0.12f); path.lineTo(0.22f, 0.02f); path.close()
        outlined(cv, 0xFFE9B36A.toInt(), 0.045f)
        // the rice-cake sticks, coated in sauce
        val sticks = arrayOf(floatArrayOf(-0.5f, -0.2f, -18f), floatArrayOf(-0.18f, -0.28f, 24f), floatArrayOf(-0.28f, -0.02f, -6f),
            floatArrayOf(0.1f, -0.06f, -30f), floatArrayOf(-0.62f, -0.02f, 40f), floatArrayOf(0.5f, -0.24f, 12f))
        for (s in sticks) {
            cv.save(); cv.translate(s[0], s[1]); cv.rotate(s[2])
            rect.set(-0.26f, -0.075f, 0.26f, 0.075f)
            fill.color = 0xFFF47A5A.toInt(); cv.drawRoundRect(rect, 0.075f, 0.075f, fill)
            line.color = INK; line.strokeWidth = 0.04f; cv.drawRoundRect(rect, 0.075f, 0.075f, line)
            fill.color = 0x88FFFFFF.toInt(); rect.set(-0.2f, -0.055f, 0.16f, -0.015f); cv.drawRoundRect(rect, 0.02f, 0.02f, fill)   // the shine
            cv.restore()
        }
        // spring onion and sesame
        fill.color = 0xFF5DB85C.toInt()
        for (p in arrayOf(floatArrayOf(-0.05f, -0.2f), floatArrayOf(0.34f, 0.02f), floatArrayOf(-0.42f, 0.06f))) { rect.set(p[0] - 0.06f, p[1] - 0.035f, p[0] + 0.06f, p[1] + 0.035f); cv.drawOval(rect, fill) }
        fill.color = 0xFFFFF4D6.toInt()
        for (p in arrayOf(floatArrayOf(-0.32f, -0.22f), floatArrayOf(0.02f, -0.12f), floatArrayOf(0.3f, -0.18f), floatArrayOf(-0.1f, 0.02f))) cv.drawCircle(p[0], p[1], 0.022f, fill)
        // steam
        line.color = 0xCCFFFFFF.toInt(); line.strokeWidth = 0.05f
        for (x in floatArrayOf(-0.3f, 0.05f, 0.38f)) { path.reset(); path.moveTo(x, -0.42f); path.quadTo(x - 0.1f, -0.58f, x, -0.72f); path.quadTo(x + 0.1f, -0.84f, x, -0.96f); cv.drawPath(path, line) }
    }
}

/** an item's picture as a drawable, for the cards and lists (the emoji text views are replaced by this) */
class ItemIconDrawable(private val item: DexItem, private val scale: Float = 0.78f) : Drawable() {
    override fun draw(c: Canvas) {
        val b = bounds
        ItemArt.draw(c, item, b.exactCenterX(), b.exactCenterY(), min(b.width(), b.height()) * scale)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
