package com.drivepet.app

import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Draws the pet in the style of the app icon: a honey "mochi" body with a cocoa outline, light from the top left,
 * a soft vinyl gloss and blurry cheeks; the clothes and the growth-stage marks in the same style.
 *
 * Everything is drawn in body units: half the body width is 1, so the body is 2 wide and 2 * [K] tall and stands on
 * y = 0. The caller moves and scales the canvas. That way every path and gradient is made once and reused every frame
 * (the room view redraws at the display's frame rate), and the outline keeps its proportion at any size.
 */
class PetPainter {
    companion object { const val K = 0.833f }   // body height / body width, as before

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val stitch = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val tmp = Path()

    // ------------------------------------------------------------------ shapes, in body units
    private val mochi = Path().apply {
        moveTo(0f, -2 * K)
        cubicTo(0.64f, -2 * K, 1.0f, -1.7f * K, 1.02f, -1.04f * K)
        cubicTo(1.04f, -0.42f * K, 0.86f, -0.01f * K, 0.46f, 0f)
        lineTo(-0.46f, 0f)
        cubicTo(-0.86f, -0.01f * K, -1.04f, -0.42f * K, -1.02f, -1.04f * K)
        cubicTo(-1.0f, -1.7f * K, -0.64f, -2 * K, 0f, -2 * K)
        close()
    }
    private val egg = Path().apply {
        moveTo(0f, -2.45f * K)
        cubicTo(0.56f, -2.45f * K, 0.92f, -1.62f * K, 0.92f, -0.95f * K)
        cubicTo(0.92f, -0.34f * K, 0.52f, 0f, 0f, 0f)
        cubicTo(-0.52f, 0f, -0.92f, -0.34f * K, -0.92f, -0.95f * K)
        cubicTo(-0.92f, -1.62f * K, -0.56f, -2.45f * K, 0f, -2.45f * K)
        close()
    }
    private val crack = Path().apply { moveTo(-0.86f, -1.72f * K); for (i in 1..6) lineTo(-0.86f + i * (1.72f / 6), -K * (if (i % 2 == 0) 1.72f else 1.95f)) }
    private val shellPath = Path().apply {
        moveTo(-1.06f, -0.7f * K)
        for (i in 1..6) lineTo(-1.06f + i * (2.12f / 6), -K * (if (i % 2 == 0) 0.7f else 0.97f))
        lineTo(1.06f, -0.3f * K); quadTo(0.95f, 0.2f * K, 0f, 0.2f * K); quadTo(-0.95f, 0.2f * K, -1.06f, -0.3f * K); close()
    }
    private val tufts = listOf(-0.26f to -2.55f, 0f to -2.78f, 0.26f to -2.55f).map { (x, tip) ->
        val lean = if (x < 0f) -1f else if (x > 0f) 1f else 0f
        Path().apply {
            moveTo(x - 0.13f, -1.97f * K)
            quadTo(x + lean * 0.12f, -2.3f * K, x + lean * 0.06f, tip * K)
            quadTo(x + 0.1f, -2.25f * K, x + 0.13f, -1.97f * K); close()
        }
    }
    private val star = Path().apply {
        val cy = -0.62f * K; val r = 0.17f
        for (i in 0 until 10) {
            val a = (-PI / 2 + i * PI / 5).toFloat(); val rr = if (i % 2 == 0) r else r * 0.48f
            if (i == 0) moveTo(cos(a) * rr, cy + sin(a) * rr) else lineTo(cos(a) * rr, cy + sin(a) * rr)
        }
        close()
    }
    private val crown = Path().apply {
        moveTo(-0.42f, -1.93f * K); lineTo(-0.48f, -2.44f * K); lineTo(-0.22f, -2.2f * K); lineTo(0f, -2.54f * K); lineTo(0.22f, -2.2f * K)
        lineTo(0.48f, -2.44f * K); lineTo(0.42f, -1.93f * K); quadTo(0f, -1.86f * K, -0.42f, -1.93f * K); close()
    }
    private val strawDome = Path().apply { moveTo(-0.42f, -2.02f * K); cubicTo(-0.42f, -2.6f * K, 0.42f, -2.6f * K, 0.42f, -2.02f * K); close() }
    private val legs = HashMap<String, Pair<Path, Path>>()
    private fun legPaths(id: String): Pair<Path, Path> = legs.getOrPut(id) {
        val bottom = if (id == "jeans") 0.06f * K else -0.14f * K
        val r = 0.14f; val radii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        Path().apply { addRoundRect(RectF(-0.64f, -0.66f * K, -0.04f, bottom), radii, Path.Direction.CW) } to
            Path().apply { addRoundRect(RectF(0.04f, -0.66f * K, 0.64f, bottom), radii, Path.Direction.CW) }
    }
    private val lens = listOf(-1, 1).map { sg ->
        val r1 = 0.08f; val r2 = 0.16f
        Path().apply { addRoundRect(RectF(sg * 0.36f - 0.25f, -1.3f * K - 0.14f * K, sg * 0.36f + 0.25f, -1.3f * K + 0.18f * K), floatArrayOf(r1, r1, r1, r1, r2, r2, r2, r2), Path.Direction.CW) }
    }

    // ------------------------------------------------------------------ colours, made once per state
    private class Skin(val body: Shader, val egg: Shader, val ear: Array<Shader>, val earIn: Array<Shader>, val cheek: Array<Shader>, val tail: Shader, val tuft: Shader, val base: Int)
    private val skins = HashMap<Int, Skin>()
    private fun skin(st: PetState, back: Boolean): Skin = skins.getOrPut(st.ordinal * 2 + if (back) 1 else 0) {
        val c = Look.body(st)
        val base = if (back) Look.shade(c.base, 0.92f) else c.base
        val shade = if (back) Look.shade(c.shade, 0.94f) else c.shade
        val radial = RadialGradient(-0.3f, -1.45f * K, 1.7f, intArrayOf(c.light, base, shade), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        val bottom = LinearGradient(0f, -0.7f * K, 0f, 0f, Look.alpha(shade, 0f), Look.alpha(Look.shade(shade, 0.9f), 0.55f), Shader.TileMode.CLAMP)
        val blush = when (st) { PetState.ANGRY -> 0xFFF26A5E.toInt(); else -> Look.BLUSH }
        val blushA = when (st) { PetState.NORMAL -> 0.85f; PetState.ANGRY -> 0.8f; PetState.HUNGRY -> 0.5f; PetState.HURT -> 0.4f }
        Skin(
            body = ComposeShader(radial, bottom, PorterDuff.Mode.SRC_OVER),
            egg = RadialGradient(-0.3f, -1.6f * K, 1.6f, intArrayOf(0xFFFFFBF0.toInt(), Look.mix(0xFFFFF3DC.toInt(), c.base, 0.25f), Look.mix(0xFFF3DFC0.toInt(), c.shade, 0.3f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP),
            ear = Array(2) { i -> val sg = if (i == 0) -1f else 1f; RadialGradient(sg * 0.45f, -1.95f * K, 0.3f, c.light, base, Shader.TileMode.CLAMP) },
            earIn = Array(2) { i -> val sg = if (i == 0) -1f else 1f; RadialGradient(sg * 0.52f, -1.9f * K, 0.12f, 0xFFF5AE8A.toInt(), Look.EAR_IN, Shader.TileMode.CLAMP) },
            cheek = Array(2) { i -> val sg = if (i == 0) -1f else 1f; RadialGradient(sg * 0.62f, -1.3f * K + 0.3f * K, 0.19f, intArrayOf(Look.alpha(blush, blushA), Look.alpha(blush, blushA * 0.75f), Look.alpha(blush, 0f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) },
            tail = RadialGradient(0f, -0.5f * K, 0.16f, 0xFFFFF6DE.toInt(), c.light, Shader.TileMode.CLAMP),
            tuft = LinearGradient(0f, -2.7f * K, 0f, -1.97f * K, Look.shade(c.shade, 0.85f), c.shade, Shader.TileMode.CLAMP),
            base = base,
        )
    }
    private val shellShader = RadialGradient(-0.3f, -0.7f * K, 1.4f, 0xFFFFFCF4.toInt(), 0xFFF1E2C7.toInt(), Shader.TileMode.CLAMP)
    private val starShader = RadialGradient(-0.05f, -0.67f * K, 0.2f, 0xFFFFF0A8.toInt(), 0xFFF5BE2E.toInt(), Shader.TileMode.CLAMP)
    private val crownShader = LinearGradient(0f, -2.54f * K, 0f, -1.9f * K, intArrayOf(0xFFFFE88E.toInt(), 0xFFF9CB45.toInt(), 0xFFE9A92C.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    private val brimShader = LinearGradient(0f, -2.1f * K, 0f, -1.84f * K, 0xFFF6DC8E.toInt(), 0xFFDDB560.toInt(), Shader.TileMode.CLAMP)
    private val domeShader = LinearGradient(0f, -2.5f * K, 0f, -2.0f * K, 0xFFFBE6A6.toInt(), 0xFFEBC874.toInt(), Shader.TileMode.CLAMP)
    private val beretShader = RadialGradient(-0.2f, -2.15f * K, 0.7f, 0xFFF59184.toInt(), 0xFFD9574A.toInt(), Shader.TileMode.CLAMP)
    private val lensShader = LinearGradient(0f, -1.3f * K - 0.14f * K, 0f, -1.3f * K + 0.18f * K, 0xFF4A4458.toInt(), 0xFF221E2A.toInt(), Shader.TileMode.CLAMP)
    private val jeansShader = LinearGradient(0f, -0.66f * K, 0f, 0.06f * K, 0xFF7E9EE6.toInt(), 0xFF5D7ED6.toInt(), Shader.TileMode.CLAMP)
    private val shortsShader = LinearGradient(0f, -0.66f * K, 0f, -0.14f * K, 0xFFA3D7A8.toInt(), 0xFF7FC08A.toInt(), Shader.TileMode.CLAMP)

    private fun fillPath(cv: Canvas, p: Path, color: Int) { fill.shader = null; fill.color = color; cv.drawPath(p, fill) }
    private fun fillPath(cv: Canvas, p: Path, s: Shader) { fill.color = OPAQUE; fill.shader = s; fill.color = Look.COCOA; cv.drawPath(p, fill); fill.shader = null }
    private fun outline(cv: Canvas, p: Path, w: Float, color: Int = Look.COCOA) { line.color = color; line.strokeWidth = w; cv.drawPath(p, line) }
    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = rect.apply { set(cx - rx, cy - ry, cx + rx, cy + ry) }

    /**
     * The pet, in body units, standing on y = 0 of the current canvas.
     * [ow] is the outline width in body units; [sticker] adds a white rim around the whole silhouette (the small pet over
     * other apps, so it reads on any map).
     */
    fun draw(cv: Canvas, st: PetState, stage: GrowthStage, outfit: Map<Slot, String>, back: Boolean, walking: Boolean, stepT: Float, ow: Float, now: Long, sticker: Boolean) {
        val sk = skin(st, back)
        val isEgg = stage == GrowthStage.EGG
        val liftA = if (walking) max(0f, sin(stepT)) * K * 0.3f else 0f
        val liftB = if (walking) max(0f, -sin(stepT)) * K * 0.3f else 0f
        val body = if (isEgg) egg else mochi

        if (sticker) {
            val rim = ow * 3.2f
            for (pass in 0..1) {
                line.color = if (pass == 0) 0x40281E14 else Look.WHITE
                line.strokeWidth = if (pass == 0) rim + ow * 0.9f else rim
                fill.shader = null; fill.color = if (pass == 0) 0x40281E14 else Look.WHITE
                if (!isEgg) for (sg in SIDES) {
                    cv.drawCircle(sg * 0.52f, -1.86f * K, 0.22f, line)
                    cv.drawOval(oval(sg * 0.34f, -0.01f * K - (if (sg < 0) liftA else liftB), 0.22f, 0.17f * K), line)
                }
                cv.drawPath(body, line)
                hatSilhouette(cv, outfit)
            }
            if (!isEgg) for (sg in SIDES) {
                fill.color = Look.WHITE; cv.drawCircle(sg * 0.52f, -1.86f * K, 0.22f, fill)
                cv.drawOval(oval(sg * 0.34f, -0.01f * K - (if (sg < 0) liftA else liftB), 0.22f, 0.17f * K), fill)
            }
            fillPath(cv, body, Look.WHITE)
        }

        if (!isEgg) {
            for ((i, sg) in SIDES.withIndex()) {
                fill.color = OPAQUE; fill.shader = sk.ear[i]; cv.drawCircle(sg * 0.52f, -1.86f * K, 0.22f, fill); fill.shader = null
                line.color = Look.COCOA; line.strokeWidth = ow; cv.drawCircle(sg * 0.52f, -1.86f * K, 0.22f, line)
                if (!back) { fill.color = OPAQUE; fill.shader = sk.earIn[i]; cv.drawCircle(sg * 0.52f, -1.9f * K, 0.11f, fill); fill.shader = null }
            }
            for (sg in SIDES) {
                val r = oval(sg * 0.34f, -0.01f * K - (if (sg < 0) liftA else liftB), 0.22f, 0.17f * K)
                fill.shader = null; fill.color = Look.shade(sk.base, 0.97f); cv.drawOval(r, fill)
                line.color = Look.COCOA; line.strokeWidth = ow; cv.drawOval(r, line)
            }
        }
        // the body, its gloss and its outline
        fillPath(cv, body, if (isEgg) sk.egg else sk.body)
        cv.save(); cv.translate(-0.45f, -(if (isEgg) 1.85f else 1.6f) * K); cv.rotate(-28.6f)
        fill.color = 0x8CFFFFFF.toInt(); cv.drawOval(oval(0f, 0f, 0.22f, 0.1f), fill); cv.restore()
        outline(cv, body, ow)

        when {
            isEgg -> eggMarks(cv, ow)
            back -> { fill.color = OPAQUE; fill.shader = sk.tail; cv.drawCircle(0f, -0.5f * K, 0.15f, fill); fill.shader = null; line.color = Look.COCOA; line.strokeWidth = ow; cv.drawCircle(0f, -0.5f * K, 0.15f, line) }
            else -> { fill.color = 0x8CFFF1CF.toInt(); cv.drawOval(oval(0f, -0.55f * K, 0.56f, 0.4f * K), fill) }
        }
        if (stage == GrowthStage.INFANT) {
            fillPath(cv, shellPath, shellShader); outline(cv, shellPath, ow)
            fill.color = 0x73E8A955; cv.drawCircle(-0.5f, -0.25f * K, 0.08f, fill); cv.drawCircle(0.48f, -0.38f * K, 0.06f, fill)
        }
        pants(cv, outfit[Slot.PANTS], ow, back)
        if (!isEgg && !back) paws(cv, sk.base, ow, if (walking) sin(stepT) else 0f)
        if (stage == GrowthStage.REBEL) for (t in tufts) { fillPath(cv, t, sk.tuft); outline(cv, t, ow) }
        if (!back) face(cv, st, sk, ow, now)
        if (stage == GrowthStage.ADULT && !back) { fillPath(cv, star, starShader); outline(cv, star, ow * 0.7f) }
        glasses(cv, outfit[Slot.GLASSES], back, ow)
        hat(cv, outfit[Slot.HAT], ow)
    }

    private fun eggMarks(cv: Canvas, ow: Float) {
        fill.shader = null; fill.color = 0x8CE8A955.toInt()
        for (m in EGG_SPOTS) cv.drawCircle(m[0], m[1] * K, m[2], fill)
        outline(cv, crack, ow * 0.85f)
    }

    private fun paws(cv: Canvas, base: Int, ow: Float, swing: Float) {
        for (sg in SIDES) {
            cv.save(); cv.translate(sg * 0.74f, -0.88f * K + sg * swing * K * 0.05f); cv.rotate(sg * 31.5f)
            val r = oval(0f, 0f, 0.16f, 0.14f * K)
            fill.shader = null; fill.color = Look.shade(base, 1.02f); cv.drawOval(r, fill)
            line.color = Look.COCOA; line.strokeWidth = ow * 0.85f; cv.drawOval(r, line)
            line.strokeWidth = ow * 0.55f
            for (t in floatArrayOf(-0.35f, 0.35f)) cv.drawLine(t * 0.12f, 0.15f * K, t * 0.1f, 0.05f * K, line)
            cv.restore()
        }
    }

    /** trousers: two legs and a waistband; also used for the shop picture */
    fun pants(cv: Canvas, id: String?, ow: Float, back: Boolean) {
        id ?: return
        val jeans = id == "jeans"
        val (l, r) = legPaths(id)
        val shader = if (jeans) jeansShader else shortsShader
        for (p in arrayOf(l, r)) { fillPath(cv, p, shader); outline(cv, p, ow * 0.85f) }
        stitch.color = if (jeans) 0xFFC9D7F7.toInt() else 0xFFE6F4E6.toInt(); stitch.strokeWidth = ow * 0.4f
        stitch.pathEffect = DashPathEffect(floatArrayOf(ow * 1.2f, ow), 0f)
        val bottom = if (jeans) 0.06f * K else -0.14f * K
        for (sg in SIDES) { val x = sg * 0.34f + sg * 0.12f; cv.drawLine(x, -0.48f * K, x, bottom - 0.06f * K, stitch) }
        rect.set(-0.68f, -0.74f * K, 0.68f, -0.54f * K)
        fill.shader = null; fill.color = if (jeans) 0xFF4B69BE.toInt() else 0xFF5FA56E.toInt(); cv.drawRoundRect(rect, 0.08f, 0.08f, fill)
        line.color = Look.COCOA; line.strokeWidth = ow * 0.85f; cv.drawRoundRect(rect, 0.08f, 0.08f, line)
        if (!back) { fill.color = if (jeans) 0xFFF6C760.toInt() else Look.WHITE; cv.drawCircle(0f, -0.64f * K, 0.045f, fill) }
    }

    /** glasses; also used for the shop picture */
    fun glasses(cv: Canvas, id: String?, back: Boolean, ow: Float) {
        id ?: return
        val eyeY = -1.3f * K; val eyeX = 0.36f
        if (back) {
            line.color = 0xFF2B2733.toInt(); line.strokeWidth = 0.05f
            for (sg in SIDES) cv.drawLine(sg * 0.99f, eyeY, sg * 0.8f, eyeY, line)
            return
        }
        if (id == "sun") {
            for ((i, sg) in SIDES.withIndex()) {
                fillPath(cv, lens[i], lensShader); outline(cv, lens[i], ow * 0.7f)
                cv.save(); cv.clipPath(lens[i])
                tmp.reset(); tmp.moveTo(sg * eyeX - 0.2f, eyeY + 0.2f * K); tmp.lineTo(sg * eyeX - 0.06f, eyeY + 0.2f * K); tmp.lineTo(sg * eyeX + 0.12f, eyeY - 0.2f * K); tmp.lineTo(sg * eyeX - 0.02f, eyeY - 0.2f * K); tmp.close()
                fillPath(cv, tmp, 0x59FFFFFF); cv.restore()
            }
            line.color = 0xFF2B2733.toInt(); line.strokeWidth = 0.055f
            tmp.reset(); tmp.moveTo(-eyeX + 0.25f, eyeY - 0.05f * K); tmp.quadTo(0f, eyeY - 0.12f * K, eyeX - 0.25f, eyeY - 0.05f * K); cv.drawPath(tmp, line)
        } else {
            for (sg in SIDES) {
                fill.shader = null; fill.color = 0x59C8E1FF; cv.drawCircle(sg * eyeX, eyeY, 0.2f, fill)
                line.color = Look.COCOA; line.strokeWidth = 0.05f; cv.drawCircle(sg * eyeX, eyeY, 0.2f, line)
            }
            tmp.reset(); tmp.moveTo(-eyeX + 0.2f, eyeY); tmp.quadTo(0f, eyeY - 0.08f * K, eyeX - 0.2f, eyeY); cv.drawPath(tmp, line)
        }
    }

    /** hats; also used for the shop picture */
    fun hat(cv: Canvas, id: String?, ow: Float) {
        when (id) {
            "straw" -> {
                val r = oval(0f, -1.98f * K, 0.82f, 0.15f * K)
                fill.color = OPAQUE; fill.shader = brimShader; cv.drawOval(r, fill); fill.shader = null; line.color = Look.COCOA; line.strokeWidth = ow * 0.85f; cv.drawOval(r, line)
                fillPath(cv, strawDome, domeShader); outline(cv, strawDome, ow * 0.85f)
                rect.set(-0.42f, -2.16f * K, 0.42f, -2.04f * K); fill.color = 0xFFF07F6E.toInt(); cv.drawRoundRect(rect, 0.03f, 0.03f, fill)
                line.strokeWidth = ow * 0.6f; cv.drawRoundRect(rect, 0.03f, 0.03f, line)
            }
            "beret" -> {
                cv.save(); cv.rotate(-4.6f)
                val r = oval(-0.04f, -2.08f * K, 0.66f, 0.25f * K)
                fill.color = OPAQUE; fill.shader = beretShader; cv.drawOval(r, fill); fill.shader = null; line.color = Look.COCOA; line.strokeWidth = ow * 0.85f; cv.drawOval(r, line)
                rect.set(-0.07f, -2.42f * K, 0.01f, -2.28f * K); fill.color = 0xFFB8483D.toInt(); cv.drawRoundRect(rect, 0.03f, 0.03f, fill)
                line.strokeWidth = ow * 0.6f; cv.drawRoundRect(rect, 0.03f, 0.03f, line)
                cv.restore()
            }
            "crown" -> {
                fillPath(cv, crown, crownShader); outline(cv, crown, ow * 0.85f)
                for (b in CROWN_TIPS) { fill.color = 0xFFFFF2B8.toInt(); cv.drawCircle(b[0], b[1] * K, 0.05f, fill); line.strokeWidth = ow * 0.5f; cv.drawCircle(b[0], b[1] * K, 0.05f, line) }
                fill.color = 0xFFF2706B.toInt(); cv.drawCircle(0f, -2.1f * K, 0.065f, fill); cv.drawCircle(0f, -2.1f * K, 0.065f, line)
                for (sg in SIDES) { fill.color = 0xFF6F9BEA.toInt(); cv.drawCircle(sg * 0.25f, -2.06f * K, 0.045f, fill); line.strokeWidth = ow * 0.45f; cv.drawCircle(sg * 0.25f, -2.06f * K, 0.045f, line) }
                fill.color = 0xBFFFFFFF.toInt(); cv.drawCircle(-0.02f, -2.13f * K, 0.02f, fill)
            }
        }
    }

    /** only the outline of the hat, for the white rim of the small pet */
    private fun hatSilhouette(cv: Canvas, outfit: Map<Slot, String>) {
        when (outfit[Slot.HAT]) {
            "straw" -> { cv.drawOval(oval(0f, -1.98f * K, 0.82f, 0.15f * K), line); cv.drawPath(strawDome, line) }
            "beret" -> { cv.save(); cv.rotate(-4.6f); cv.drawOval(oval(-0.04f, -2.08f * K, 0.66f, 0.25f * K), line); cv.restore() }
            "crown" -> cv.drawPath(crown, line)
        }
    }

    private fun face(cv: Canvas, st: PetState, sk: Skin, ow: Float, now: Long) {
        val eyeY = -1.3f * K; val eyeX = 0.36f; val er = 0.09f
        for ((i, sg) in SIDES.withIndex()) { fill.color = OPAQUE; fill.shader = sk.cheek[i]; cv.drawOval(oval(sg * 0.62f, eyeY + 0.3f * K, 0.19f, 0.15f), fill) }
        fill.shader = null
        line.color = Look.COCOA; fill.color = Look.COCOA
        when (st) {
            PetState.NORMAL -> {   // ^ ^ and a small smile, like the icon
                line.strokeWidth = 0.065f
                for (sg in SIDES) cv.drawArc(oval(sg * eyeX, eyeY + er * 0.75f, er * 1.25f, er * 1.25f), 212.4f, 115.2f, false, line)
                line.strokeWidth = 0.055f
                cv.drawArc(oval(0f, eyeY + 0.14f * K, 0.12f, 0.12f), 32.4f, 115.2f, false, line)
            }
            PetState.ANGRY -> {   // slanted brows, a frown, puffs of steam and an anger mark
                eyes(cv, eyeX, eyeY, er, 0.3f)
                line.color = Look.COCOA; line.strokeWidth = 0.07f
                cv.drawLine(-eyeX - er * 1.8f, eyeY - er * 2.5f, -eyeX + er * 1.5f, eyeY - er * 1.4f, line)
                cv.drawLine(eyeX + er * 1.8f, eyeY - er * 2.5f, eyeX - er * 1.5f, eyeY - er * 1.4f, line)
                line.strokeWidth = 0.055f
                cv.drawArc(oval(0f, eyeY + 0.5f * K, 0.12f, 0.12f), 216f, 108f, false, line)
                val puff = abs(sin(now / 220f))
                for (sg in SIDES) {
                    val x = sg * 0.86f; val y = -2.02f * K - puff * K * 0.12f; val s = 0.17f
                    tmp.reset()
                    tmp.arcTo(x - s * 0.45f - s * 0.5f, y - s * 0.5f, x - s * 0.45f + s * 0.5f, y + s * 0.5f, 90f, 180f, false)
                    tmp.arcTo(x - s * 0.55f, y - s * 0.35f - s * 0.55f, x + s * 0.55f, y - s * 0.35f + s * 0.55f, 180f, 180f, false)
                    tmp.arcTo(x + s * 0.45f - s * 0.5f, y - s * 0.5f, x + s * 0.45f + s * 0.5f, y + s * 0.5f, 270f, 180f, false)
                    tmp.close()
                    fillPath(cv, tmp, 0xFFFFF4F0.toInt()); outline(cv, tmp, ow * 0.7f, Look.CORAL)
                }
                line.color = Look.CORAL_D; line.strokeWidth = ow * 0.8f
                val ax = 0.55f; val ay = -1.72f * K; val a = 0.07f
                for ((dx, dy, rot) in ANGER) cv.drawArc(oval(ax + dx * a * 1.15f, ay + dy * a * 1.15f, a, a), 90f * rot + 135f, 90f, false, line)
            }
            PetState.HUNGRY -> {   // droopy brows, a small open mouth and tummy rumbles
                eyes(cv, eyeX, eyeY, er, 0.32f)
                line.color = Look.COCOA; line.strokeWidth = 0.05f
                cv.drawLine(-eyeX - er * 1.4f, eyeY - er * 1.5f, -eyeX + er * 1.5f, eyeY - er * 2.4f, line)
                cv.drawLine(eyeX + er * 1.4f, eyeY - er * 1.5f, eyeX - er * 1.5f, eyeY - er * 2.4f, line)
                val m = oval(0f, eyeY + 0.38f * K, 0.08f, 0.1f * K)
                fill.color = 0xFFB5525A.toInt(); cv.drawOval(m, fill); line.strokeWidth = ow * 0.6f; cv.drawOval(m, line)
                line.color = 0xFFC79A3A.toInt(); line.strokeWidth = 0.04f
                for (sg in SIDES) for (k in 0 until 2) {
                    val x = sg * (0.5f + k * 0.12f); val y = -0.5f * K - k * 0.12f * K
                    cv.drawArc(oval(x, y, 0.05f, 0.05f), if (sg < 0) 99f else 261f, 162f, false, line)
                }
            }
            PetState.HURT -> {   // > < eyes, a wobbly mouth, a tear and a plaster
                line.strokeWidth = 0.06f
                for (sg in SIDES) {
                    val ex = sg * eyeX
                    tmp.reset(); tmp.moveTo(ex - sg * er * 1.2f, eyeY - er * 1.2f); tmp.lineTo(ex + sg * er * 1.1f, eyeY); tmp.lineTo(ex - sg * er * 1.2f, eyeY + er * 1.2f)
                    cv.drawPath(tmp, line)
                }
                line.strokeWidth = 0.05f
                val my = eyeY + 0.42f * K; val mw = 0.2f
                tmp.reset(); tmp.moveTo(-mw, my)
                for (i in 0 until 4) { val x0 = -mw + i * mw / 2f; tmp.quadTo(x0 + mw / 4f, my + (if (i % 2 == 0) -1 else 1) * 0.07f * K, x0 + mw / 2f, my) }
                cv.drawPath(tmp, line)
                val ty = eyeY + 0.3f * K + abs(sin(now / 400f)) * 0.15f * K; val tx = eyeX * 1.22f
                tmp.reset(); tmp.moveTo(tx, ty - 0.1f); tmp.quadTo(tx + 0.07f, ty, tx, ty + 0.06f); tmp.quadTo(tx - 0.07f, ty, tx, ty - 0.1f); tmp.close()
                fillPath(cv, tmp, 0xFF7CC7FF.toInt()); outline(cv, tmp, ow * 0.4f, 0xFF3E86C9.toInt())
                cv.save(); cv.translate(-0.35f, -1.88f * K); cv.rotate(-21.8f)
                rect.set(-0.26f, -0.12f * K, 0.26f, 0.12f * K); fill.color = 0xFFF7E7CB.toInt(); cv.drawRoundRect(rect, 0.08f, 0.08f, fill)
                line.color = Look.COCOA; line.strokeWidth = ow * 0.6f; cv.drawRoundRect(rect, 0.08f, 0.08f, line)
                rect.set(-0.09f, -0.12f * K, 0.09f, 0.12f * K); fill.color = 0xFFEBD3AD.toInt(); cv.drawRect(rect, fill)
                fill.color = 0xFFC9A877.toInt(); cv.drawCircle(-0.17f, 0f, 0.02f, fill); cv.drawCircle(0.17f, 0f, 0.02f, fill)
                cv.restore()
            }
        }
    }

    private fun eyes(cv: Canvas, eyeX: Float, eyeY: Float, er: Float, glint: Float) {
        fill.shader = null; fill.color = Look.COCOA
        cv.drawCircle(-eyeX, eyeY, er, fill); cv.drawCircle(eyeX, eyeY, er, fill)
        fill.color = Look.WHITE
        cv.drawCircle(-eyeX + er * 0.3f, eyeY - er * 0.3f, er * glint, fill); cv.drawCircle(eyeX + er * 0.3f, eyeY - er * 0.3f, er * glint, fill)
    }

    private val SIDES = floatArrayOf(-1f, 1f)
    private val EGG_SPOTS = arrayOf(floatArrayOf(-0.5f, -0.55f, 0.11f), floatArrayOf(0.55f, -0.8f, 0.08f), floatArrayOf(0.28f, -0.35f, 0.065f), floatArrayOf(-0.6f, -2.0f, 0.07f), floatArrayOf(0.45f, -2.15f, 0.06f))
    private val CROWN_TIPS = arrayOf(floatArrayOf(-0.48f, -2.44f), floatArrayOf(0f, -2.54f), floatArrayOf(0.48f, -2.44f))
    private val ANGER = arrayOf(Triple(-1, -1, 0), Triple(1, -1, 1), Triple(1, 1, 2), Triple(-1, 1, 3))
}
