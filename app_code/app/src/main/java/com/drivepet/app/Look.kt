package com.drivepet.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The look of the app ("햇살 드라이브"), taken from the app icon: a honey bear with a cocoa outline, a periwinkle car,
 * a soft daytime sky and cream surfaces. Colours, the display font and the parts the screens share live here, so the
 * screens only say what they show, not how it looks. Nothing in here changes what the app does.
 */
object Look {
    // ink and surfaces
    const val COCOA = 0xFF4A3426.toInt()       // the outline of the character and the props (the icon's brown line)
    const val INK = 0xFF3D2F27.toInt()         // main text
    const val SUB = 0xFF7A6A5F.toInt()         // secondary text
    const val MUTED = 0xFFA89889.toInt()       // disabled text
    const val CREAM = 0xFFFFF9F1.toInt()       // cards
    const val CREAM2 = 0xFFFBF0E2.toInt()      // quiet fills
    const val LINE = 0xFFEFDFCB.toInt()        // card borders
    const val ROW_LINE = 0xFFF1E3D2.toInt()    // row borders
    const val WHITE = 0xFFFFFFFF.toInt()
    // the bear
    const val HONEY = 0xFFF7C873.toInt()
    const val HONEY_DEEP = 0xFFA8670F.toInt()  // points, on cream
    const val BLUSH = 0xFFF59A7A.toInt()
    const val EAR_IN = 0xFFF2A27C.toInt()
    // the car: actions
    const val PERI_TOP = 0xFF7C9BEC.toInt()
    const val PERI = 0xFF6686E2.toInt()
    const val PERI_BOT = 0xFF5272D6.toInt()
    const val PERI_LIP = 0xFF3F5DBD.toInt()
    const val PERI_L = 0xFFE3EBFD.toInt()
    const val PERI_DEEP = 0xFF3A57AA.toInt()
    const val NAVY_LINE = 0xFF2F4A8C.toInt()   // the car's outline
    // states and accents
    const val SAGE_D = 0xFF3C8A4D.toInt()
    const val SAGE_L = 0xFFE6F3E2.toInt()
    const val CORAL = 0xFFF07F6E.toInt()
    const val CORAL_D = 0xFFC9502F.toInt()
    const val CORAL_L = 0xFFFDE8E0.toInt()
    const val MUSTARD_D = 0xFF9A6A08.toInt()
    const val MUSTARD_L = 0xFFFCF0D2.toInt()
    const val SLATE_D = 0xFF5E6C86.toInt()
    const val SLATE_L = 0xFFECEFF5.toInt()
    const val SHADOW = 0xFF7A5434.toInt()      // shadows are warm brown, never grey

    /** the body colours of each state: the base, the light from the top left and the deeper tone at the bottom */
    class Body(val base: Int, val light: Int, val shade: Int)
    private val bodies = mapOf(
        PetState.NORMAL to Body(0xFFF7C873.toInt(), 0xFFFFE4A8.toInt(), 0xFFE8A955.toInt()),
        PetState.ANGRY to Body(0xFFF59E6E.toInt(), 0xFFFFCBA6.toInt(), 0xFFDF7C4C.toInt()),
        PetState.HUNGRY to Body(0xFFF2DDA2.toInt(), 0xFFFFF3D2.toInt(), 0xFFD9BC7A.toInt()),
        PetState.HURT to Body(0xFFC2CCDC.toInt(), 0xFFE6EBF3.toInt(), 0xFF9EADC6.toInt()),
    )
    fun body(st: PetState): Body = bodies.getValue(st)

    @Volatile private var jua: Typeface? = null
    /** the rounded display face (Jua, SIL Open Font License) for titles, buttons, numbers and the pet's words */
    fun display(c: Context): Typeface = jua ?: (try { c.resources.getFont(R.font.jua) } catch (_: Exception) { Typeface.DEFAULT_BOLD }).also { jua = it }

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }
    fun shade(c: Int, k: Float): Int = Color.rgb((Color.red(c) * k).toInt().coerceIn(0, 255), (Color.green(c) * k).toInt().coerceIn(0, 255), (Color.blue(c) * k).toInt().coerceIn(0, 255))
    fun alpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24)
}

/**
 * A paint that draws with a gradient still applies its colour's alpha, so it is made opaque first (a translucent colour
 * left over from an earlier draw would otherwise fade the gradient).
 */
const val OPAQUE = 0xFF000000.toInt()

/** paints and scratch objects for drawing; one per view, so nothing is allocated while drawing */
class Ink {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    val path = Path()
    val path2 = Path()
    val rect = RectF()

    fun fillPath(cv: Canvas, p: Path, color: Int) { fill.shader = null; fill.color = color; cv.drawPath(p, fill) }
    fun fillPath(cv: Canvas, p: Path, shader: Shader) { fill.color = OPAQUE; fill.shader = shader; cv.drawPath(p, fill); fill.shader = null }
    fun stroke(cv: Canvas, p: Path, color: Int, w: Float) { line.color = color; line.strokeWidth = w; cv.drawPath(p, line) }
    fun circle(cv: Canvas, x: Float, y: Float, r: Float, color: Int) { fill.shader = null; fill.color = color; cv.drawCircle(x, y, r, fill) }
    fun circleOutline(cv: Canvas, x: Float, y: Float, r: Float, color: Int, w: Float) { line.color = color; line.strokeWidth = w; cv.drawCircle(x, y, r, line) }
    fun oval(cv: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int) { fill.shader = null; fill.color = color; rect.set(cx - rx, cy - ry, cx + rx, cy + ry); cv.drawOval(rect, fill) }
    fun ovalOutline(cv: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, w: Float) { line.color = color; line.strokeWidth = w; rect.set(cx - rx, cy - ry, cx + rx, cy + ry); cv.drawOval(rect, line) }
}

/**
 * Light or dark for everything built from views (the cards, rows, texts and keys), so the whole app has the look of the map
 * collection: by day the cream cards on the room, by night deep-blue glass with pale text. It is set once when the screen is
 * built ([night]); the screen is built again when the driver switches.
 */
object Theme {
    @JvmField var night = false

    private fun lum(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    /** a surface colour: cream things become blue glass at night, white rows a lighter blue, colourful fills stay */
    fun fill(c: Int): Int {
        if (!night) return c
        if (c == Look.WHITE) return 0xFF26348F.toInt()
        val l = lum(c)
        return when { l > 0.82f -> 0xE61A2466.toInt(); l > 0.62f -> 0xFF1F2B80.toInt(); else -> c }
    }

    /** a line colour: the pale borders become a soft periwinkle at night */
    fun stroke(c: Int): Int = if (night && lum(c) > 0.7f) Color.argb(150, 140, 160, 240) else c

    /** a text colour: the dark browns become pale, other dark colours are lightened */
    fun text(c: Int): Int {
        if (!night) return c
        if (c == Look.INK || c == Look.COCOA) return 0xFFF0F3FF.toInt()
        if (c == Look.SUB) return 0xFFB8C2E6.toInt()
        if (c == Look.MUTED) return 0xFF8FA0E0.toInt()
        return if (lum(c) < 0.5f) Look.mix(c, 0xFFFFFFFF.toInt(), 0.72f) else c
    }
}

/** the parts of the screens built from views: cards, rows and the three kinds of buttons */
class Kit(c: Context) {
    private val d = c.resources.displayMetrics.density
    val face: Typeface = Look.display(c)
    fun dp(v: Float) = v * d
    private fun px(v: Float) = max(1, (v * d + 0.5f).toInt())

    /** a cream card: the surface of everything that sits on the room */
    fun card(r: Float, fill: Int = Look.CREAM, stroke: Int = Look.LINE, strokeW: Float = 1.2f) = GradientDrawable().apply {
        setColor(Theme.fill(fill)); cornerRadius = dp(r); if (strokeW > 0f) setStroke(px(strokeW), Theme.stroke(stroke))
    }

    /** a white row inside a card */
    fun row(r: Float = 18f, fill: Int = Look.WHITE, stroke: Int = Look.ROW_LINE, strokeW: Float = 1f) = card(r, fill, stroke, strokeW)

    /** the main action: periwinkle, standing on a darker lip it sinks into when pressed, like a toy key */
    fun primary(r: Float = 100f): Drawable = keyDrawable(r, intArrayOf(Look.PERI_TOP, Look.PERI_BOT), intArrayOf(Look.PERI, Look.PERI_BOT), Look.PERI_LIP, 0, 0)

    /** the second action: white with a periwinkle edge */
    fun secondary(r: Float = 100f): Drawable = keyDrawable(r, intArrayOf(Look.WHITE, Look.WHITE), intArrayOf(0xFFF3F6FE.toInt(), 0xFFF3F6FE.toInt()), 0xFFD4DDF6.toInt(), 0xFFAFC1F2.toInt(), 1)

    /** something that cannot be pressed now (or is done) */
    fun quiet(r: Float = 100f) = card(r, Look.CREAM2, 0xFFEFE2D2.toInt(), 1.2f)

    /** a name chip and other light choices */
    fun chip(r: Float = 100f) = card(r, 0xFFFFFDF8.toInt(), Look.LINE, 1.2f)

    /** the lip a key stands on, in px: give the key this much extra padding at the bottom so its label sits on the face */
    val lip: Int get() = px(3.5f)

    private fun keyDrawable(r: Float, face: IntArray, pressedFace: IntArray, lipColor: Int, edge: Int, edgeW: Int): Drawable {
        val lipPx = px(3.5f)
        fun layer(pressed: Boolean): LayerDrawable {
            val base = GradientDrawable().apply { setColor(lipColor); cornerRadius = dp(r) }
            val top = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (pressed) pressedFace else face).apply {
                cornerRadius = dp(r); if (edgeW > 0) setStroke(px(edgeW * 1.4f), edge)
            }
            return LayerDrawable(arrayOf(base, top)).apply {
                if (pressed) setLayerInset(1, 0, (lipPx * 0.7f).toInt(), 0, (lipPx * 0.3f).toInt()) else setLayerInset(1, 0, 0, 0, lipPx)
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), layer(true))
            addState(intArrayOf(), layer(false))
        }
    }

    /** a soft warm shadow under a view (the shape comes from its background) */
    fun lift(v: View, elevationDp: Float) {
        v.elevation = dp(elevationDp)
        v.outlineAmbientShadowColor = Look.SHADOW
        v.outlineSpotShadowColor = Look.SHADOW
    }

    /** a label in the display face */
    fun title(tv: TextView, sp: Float, color: Int = Look.INK): TextView = tv.apply { typeface = face; textSize = sp; setTextColor(Theme.text(color)); includeFontPadding = false }

    /**
     * a key: the label in the display face on a primary / secondary / quiet background; the padding is set here (not added
     * to), with the lip's extra height at the bottom so the label sits in the middle of the face. Safe to call again.
     */
    fun key(tv: TextView, kind: Int, sp: Float, padH: Float = 16f, padV: Float = 8f, r: Float = 100f): TextView = tv.apply {
        typeface = face; textSize = sp; includeFontPadding = false; gravity = Gravity.CENTER
        when (kind) {
            KEY_PRIMARY -> { background = primary(r); setTextColor(Look.WHITE); setShadowLayer(0.01f, 0f, dp(1f), 0x553A4FA0) }
            KEY_DANGER -> {   // something that cannot be undone (deleting): coral, on a deeper lip
                background = keyDrawable(r, intArrayOf(0xFFF7A58A.toInt(), 0xFFEC7E5E.toInt()), intArrayOf(0xFFEF8E70.toInt(), 0xFFE2704F.toInt()), Look.CORAL_D, 0, 0)
                setTextColor(Look.WHITE); setShadowLayer(0.01f, 0f, dp(1f), 0x55A0402A)
            }
            KEY_SECONDARY -> {
                if (Theme.night) { background = keyDrawable(r, intArrayOf(0xFF2A3796.toInt(), 0xFF2A3796.toInt()), intArrayOf(0xFF34439E.toInt(), 0xFF34439E.toInt()), 0xFF141C5E.toInt(), 0xFF8FA0E0.toInt(), 1); setTextColor(0xFFE6ECFF.toInt()) }
                else { background = secondary(r); setTextColor(Look.PERI_DEEP) }
                setShadowLayer(0f, 0f, 0f, 0)
            }
            else -> { background = quiet(r); setTextColor(Theme.text(if (kind == KEY_DONE) Look.SAGE_D else Look.MUTED)); setShadowLayer(0f, 0f, 0f, 0) }
        }
        val extra = if (kind == KEY_PRIMARY || kind == KEY_SECONDARY || kind == KEY_DANGER) lip else 0
        setPadding(px(padH), px(padV), px(padH), px(padV) + extra)
    }

    /** a white row that turns cream while pressed */
    fun rowPressable(r: Float = 20f): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), row(r, Look.CREAM2))
        addState(intArrayOf(), row(r))
    }

    companion object { const val KEY_PRIMARY = 0; const val KEY_SECONDARY = 1; const val KEY_QUIET = 2; const val KEY_DONE = 3; const val KEY_DANGER = 4 }
}

// ---------------------------------------------------------------- small pictures used inside text and on canvases

object Pics {
    private val sparklePath = Path()

    /** the safe-driving coin: honey with a cocoa rim and a P */
    fun coin(cv: Canvas, ink: Ink, cx: Float, cy: Float, r: Float, face: Typeface) {
        val ow = max(1.1f, r * 0.13f)
        ink.fill.color = OPAQUE; ink.fill.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.4f, intArrayOf(0xFFFFE58F.toInt(), 0xFFF7C54A.toInt(), 0xFFE9A930.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, ink.fill); ink.fill.shader = null
        ink.circleOutline(cv, cx, cy, r, Look.COCOA, ow)
        ink.circleOutline(cv, cx, cy, r * 0.66f, 0x8CC98A1B.toInt(), ow * 0.7f)
        ink.text.typeface = face; ink.text.textSize = r * 1.05f; ink.text.color = 0xFF9A5F0C.toInt(); ink.text.textAlign = Paint.Align.CENTER
        cv.drawText("P", cx, cy + r * 0.38f, ink.text)
        ink.circle(cv, cx - r * 0.42f, cy - r * 0.42f, r * 0.12f, 0xCCFFFFFF.toInt())
    }

    /** a four-point sparkle */
    fun sparkle(cv: Canvas, ink: Ink, x: Float, y: Float, r: Float, color: Int) {
        val p = sparklePath; p.reset(); p.moveTo(x, y - r)
        p.quadTo(x + r * 0.16f, y - r * 0.16f, x + r, y); p.quadTo(x + r * 0.16f, y + r * 0.16f, x, y + r)
        p.quadTo(x - r * 0.16f, y + r * 0.16f, x - r, y); p.quadTo(x - r * 0.16f, y - r * 0.16f, x, y - r); p.close()
        ink.fillPath(cv, p, color)
    }

    /** a puffy cloud with a flat bottom; [under] tints its lower part */
    fun cloud(cv: Canvas, ink: Ink, x: Float, y: Float, s: Float, fill: Int, under: Int = 0) {
        val p = ink.path2; p.reset()
        p.moveTo(x - s * 1.1f, y)
        p.arcTo(x - s * 0.62f - s * 0.42f, y - s * 0.28f - s * 0.42f, x - s * 0.62f + s * 0.42f, y - s * 0.28f + s * 0.42f, 171f, 144f, false)
        p.arcTo(x - s * 0.02f - s * 0.62f, y - s * 0.55f - s * 0.62f, x - s * 0.02f + s * 0.62f, y - s * 0.55f + s * 0.62f, 216f, 130f, false)
        p.arcTo(x + s * 0.62f - s * 0.46f, y - s * 0.26f - s * 0.46f, x + s * 0.62f + s * 0.46f, y - s * 0.26f + s * 0.46f, 243f, 139f, false)
        p.quadTo(x + s * 1.1f, y, x + s * 0.9f, y); p.close()
        ink.fillPath(cv, p, fill)
        if (under != 0) { cv.save(); cv.clipPath(p); ink.oval(cv, x, y + s * 0.05f, s * 1.3f, s * 0.28f, under); cv.restore() }
    }

    /** the little periwinkle car of the icon, seen from the side */
    fun car(cv: Canvas, ink: Ink, cx: Float, cy: Float, s: Float) {
        val ow = max(1f, s * 0.09f)
        ink.rect.set(cx - s * 0.5f, cy - s * 0.42f, cx + s * 0.4f, cy); ink.fill.shader = null; ink.fill.color = 0xFFBFD1F7.toInt(); cv.drawRoundRect(ink.rect, s * 0.2f, s * 0.2f, ink.fill)
        ink.line.color = Look.NAVY_LINE; ink.line.strokeWidth = ow; cv.drawRoundRect(ink.rect, s * 0.2f, s * 0.2f, ink.line)
        ink.rect.set(cx - s * 0.95f, cy - s * 0.12f, cx + s * 0.95f, cy + s * 0.4f)
        ink.fill.color = OPAQUE; ink.fill.shader = LinearGradient(0f, cy - s * 0.12f, 0f, cy + s * 0.4f, 0xFF8EABF0.toInt(), 0xFF5F80DC.toInt(), Shader.TileMode.CLAMP)
        cv.drawRoundRect(ink.rect, s * 0.22f, s * 0.22f, ink.fill); ink.fill.shader = null
        cv.drawRoundRect(ink.rect, s * 0.22f, s * 0.22f, ink.line)
        for (sg in intArrayOf(-1, 1)) { ink.circle(cv, cx + sg * s * 0.52f, cy + s * 0.4f, s * 0.2f, 0xFF4A4245.toInt()); ink.circleOutline(cv, cx + sg * s * 0.52f, cy + s * 0.4f, s * 0.2f, 0xFF2B2326.toInt(), ow * 0.7f) }
        ink.circle(cv, cx + s * 0.8f, cy + s * 0.08f, s * 0.1f, 0xFFFFF6D2.toInt())
    }

    /** the bear's head, front view, for the menu portrait and the small icons */
    fun bearHead(cv: Canvas, ink: Ink, cx: Float, cy: Float, r: Float, st: PetState) {
        val col = Look.body(st); val ow = max(1.2f, r * 0.07f)
        for (sg in intArrayOf(-1, 1)) {
            ink.circle(cv, cx + sg * r * 0.6f, cy - r * 0.62f, r * 0.27f, col.base); ink.circleOutline(cv, cx + sg * r * 0.6f, cy - r * 0.62f, r * 0.27f, Look.COCOA, ow)
            ink.circle(cv, cx + sg * r * 0.6f, cy - r * 0.65f, r * 0.13f, Look.EAR_IN)
        }
        ink.rect.set(cx - r * 0.98f, cy + r * 0.05f - r * 0.78f, cx + r * 0.98f, cy + r * 0.05f + r * 0.78f)
        ink.fill.color = OPAQUE; ink.fill.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.35f, intArrayOf(col.light, col.base, col.shade), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawOval(ink.rect, ink.fill); ink.fill.shader = null
        ink.line.color = Look.COCOA; ink.line.strokeWidth = ow; cv.drawOval(ink.rect, ink.line)
        val ey = cy + r * 0.02f; val ex = r * 0.36f; val er = r * 0.1f
        val blush = if (st == PetState.ANGRY) 0xFFF26A5E.toInt() else Look.BLUSH
        for (sg in intArrayOf(-1, 1)) {
            val x = cx + sg * r * 0.62f; val y = ey + r * 0.26f
            ink.fill.color = OPAQUE; ink.fill.shader = RadialGradient(x, y, r * 0.2f, intArrayOf(Look.alpha(blush, 0.85f), Look.alpha(blush, 0f)), null, Shader.TileMode.CLAMP)
            ink.rect.set(x - r * 0.2f, y - r * 0.15f, x + r * 0.2f, y + r * 0.15f); cv.drawOval(ink.rect, ink.fill); ink.fill.shader = null
        }
        ink.line.color = Look.COCOA; ink.line.strokeWidth = r * 0.085f
        ink.fill.color = Look.COCOA
        when (st) {
            PetState.NORMAL -> {
                for (sg in intArrayOf(-1, 1)) { ink.rect.set(cx + sg * ex - er * 1.3f, ey + er * 0.8f - er * 1.3f, cx + sg * ex + er * 1.3f, ey + er * 0.8f + er * 1.3f); cv.drawArc(ink.rect, 212f, 116f, false, ink.line) }
                ink.line.strokeWidth = r * 0.07f; ink.rect.set(cx - r * 0.13f, ey + r * 0.14f - r * 0.13f, cx + r * 0.13f, ey + r * 0.14f + r * 0.13f); cv.drawArc(ink.rect, 32f, 116f, false, ink.line)
            }
            PetState.ANGRY -> {
                for (sg in intArrayOf(-1, 1)) { cv.drawCircle(cx + sg * ex, ey, er, ink.fill); cv.drawLine(cx + sg * (ex + er * 1.6f), ey - er * 2.3f, cx + sg * (ex - er * 1.4f), ey - er * 1.3f, ink.line) }
                ink.line.strokeWidth = r * 0.07f; ink.rect.set(cx - r * 0.12f, ey + r * 0.46f - r * 0.12f, cx + r * 0.12f, ey + r * 0.46f + r * 0.12f); cv.drawArc(ink.rect, 216f, 108f, false, ink.line)
            }
            PetState.HUNGRY -> {
                for (sg in intArrayOf(-1, 1)) { cv.drawCircle(cx + sg * ex, ey, er, ink.fill); cv.drawLine(cx + sg * (ex + er * 1.3f), ey - er * 1.4f, cx + sg * (ex - er * 1.4f), ey - er * 2.3f, ink.line) }
                ink.oval(cv, cx, ey + r * 0.36f, r * 0.08f, r * 0.1f, 0xFFB5525A.toInt()); ink.ovalOutline(cv, cx, ey + r * 0.36f, r * 0.08f, r * 0.1f, Look.COCOA, ow * 0.6f)
            }
            PetState.HURT -> {
                for (sg in intArrayOf(-1, 1)) { val x = cx + sg * ex; ink.path.reset(); ink.path.moveTo(x - sg * er, ey - er); ink.path.lineTo(x + sg * er, ey); ink.path.lineTo(x - sg * er, ey + er); cv.drawPath(ink.path, ink.line) }
                ink.line.strokeWidth = r * 0.07f; ink.rect.set(cx - r * 0.12f, ey + r * 0.44f - r * 0.12f, cx + r * 0.12f, ey + r * 0.44f + r * 0.12f); cv.drawArc(ink.rect, 216f, 108f, false, ink.line)
                ink.rect.set(cx - r * 0.62f, cy - r * 0.78f, cx - r * 0.12f, cy - r * 0.56f)
                cv.save(); cv.rotate(-20f, ink.rect.centerX(), ink.rect.centerY())
                ink.fill.color = 0xFFF7E7CB.toInt(); cv.drawRoundRect(ink.rect, r * 0.06f, r * 0.06f, ink.fill)
                ink.line.color = Look.COCOA; ink.line.strokeWidth = ow * 0.6f; cv.drawRoundRect(ink.rect, r * 0.06f, r * 0.06f, ink.line)
                cv.restore()
            }
        }
    }

    /** the round map pin */
    fun pin(cv: Canvas, ink: Ink, x: Float, tipY: Float, s: Float) {
        val y = tipY - s; val p = ink.path; p.reset()
        p.moveTo(x, tipY); p.cubicTo(x - s * 0.85f, y + s * 0.1f, x - s * 0.75f, y - s * 0.85f, x, y - s * 0.85f)
        p.cubicTo(x + s * 0.75f, y - s * 0.85f, x + s * 0.85f, y + s * 0.1f, x, tipY); p.close()
        ink.fillPath(cv, p, Look.CORAL); ink.stroke(cv, p, Look.COCOA, max(1f, s * 0.13f))
        ink.circle(cv, x, y - s * 0.22f, s * 0.28f, Look.WHITE)
    }

    @Suppress("unused") private fun angle(i: Int, n: Int) = (i * 2 * PI / n).toFloat().let { cos(it) to sin(it) }
    @Suppress("unused") private fun clampf(v: Float, a: Float, b: Float) = min(max(v, a), b)
}

/** a small picture as a drawable, to sit next to a label (the coin by the points, the car by the distance...) */
class PicDrawable(private val sizePx: Int, private val draw: (Canvas, Ink, Float) -> Unit) : Drawable() {
    private val ink = Ink()
    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save(); canvas.translate(b.left.toFloat(), b.top.toFloat())
        draw(canvas, ink, b.width().toFloat())
        canvas.restore()
    }
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** shared builders of those drawables */
object Glyphs {
    fun coin(c: Context, sizeDp: Float) = sized(c, sizeDp) { cv, ink, s -> Pics.coin(cv, ink, s / 2f, s / 2f, s * 0.46f, Look.display(c)) }
    fun car(c: Context, sizeDp: Float) = sized(c, sizeDp) { cv, ink, s -> Pics.car(cv, ink, s / 2f, s * 0.52f, s * 0.45f) }
    fun menu(c: Context, sizeDp: Float) = sized(c, sizeDp) { cv, ink, s ->
        ink.line.color = Look.WHITE; ink.line.strokeWidth = s * 0.14f
        for (k in intArrayOf(-1, 0, 1)) cv.drawLine(s * 0.12f, s / 2f + k * s * 0.3f, s * 0.88f, s / 2f + k * s * 0.3f, ink.line)
    }
    fun radio(c: Context, sizeDp: Float, on: Boolean) = sized(c, sizeDp) { cv, ink, s ->
        if (on) { ink.circle(cv, s / 2f, s / 2f, s * 0.46f, Look.PERI); ink.circle(cv, s / 2f, s / 2f, s * 0.18f, Look.WHITE) }
        else { ink.circle(cv, s / 2f, s / 2f, s * 0.46f, Look.WHITE); ink.circleOutline(cv, s / 2f, s / 2f, s * 0.42f, 0xFFC9B8A6.toInt(), s * 0.09f) }
    }
    fun check(c: Context, sizeDp: Float, color: Int) = sized(c, sizeDp) { cv, ink, s ->
        ink.line.color = color; ink.line.strokeWidth = s * 0.16f
        ink.path.reset(); ink.path.moveTo(s * 0.18f, s * 0.52f); ink.path.lineTo(s * 0.42f, s * 0.74f); ink.path.lineTo(s * 0.84f, s * 0.28f); cv.drawPath(ink.path, ink.line)
    }
    fun pin(c: Context, sizeDp: Float) = sized(c, sizeDp) { cv, ink, s -> Pics.pin(cv, ink, s / 2f, s * 0.96f, s * 0.6f) }
    fun sparkleDrawable(c: Context, sizeDp: Float) = sized(c, sizeDp) { cv, ink, s -> Pics.sparkle(cv, ink, s / 2f, s / 2f, s * 0.48f, 0xFFF5C33B.toInt()) }
    fun cross(c: Context, sizeDp: Float, color: Int) = sized(c, sizeDp) { cv, ink, s ->
        ink.line.color = color; ink.line.strokeWidth = s * 0.15f
        cv.drawLine(s * 0.22f, s * 0.22f, s * 0.78f, s * 0.78f, ink.line); cv.drawLine(s * 0.78f, s * 0.22f, s * 0.22f, s * 0.78f, ink.line)
    }

    private fun sized(c: Context, sizeDp: Float, draw: (Canvas, Ink, Float) -> Unit): Drawable {
        val px = (sizeDp * c.resources.displayMetrics.density + 0.5f).toInt()
        return PicDrawable(px, draw).apply { setBounds(0, 0, px, px) }
    }
}

/** "Lv.3" drawn as a small rounded badge inside a line of text (the name stays plain text before it) */
class BadgeSpan(private val bg: Int, private val fg: Int, private val face: Typeface, private val sizePx: Float, private val padPx: Float) : ReplacementSpan() {
    private val rect = RectF()
    private fun paintFor(base: Paint) = Paint(base).apply { typeface = face; textSize = sizePx; isAntiAlias = true }
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (paintFor(paint).measureText(text, start, end) + padPx * 2).toInt()
    override fun draw(cv: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val p = paintFor(paint)
        val w = p.measureText(text, start, end) + padPx * 2
        val fm = p.fontMetrics
        val h = (fm.descent - fm.ascent) * 0.92f + padPx * 0.5f
        val cy = (top + bottom) / 2f
        rect.set(x, cy - h / 2f, x + w, cy + h / 2f)
        p.color = bg; cv.drawRoundRect(rect, h / 2f, h / 2f, p)
        p.color = fg; cv.drawText(text, start, end, x + padPx, cy - (fm.ascent + fm.descent) / 2f, p)
    }
}

/** a progress bar with round ends, a soft gradient and a little sheen (replaces the platform bar, same max / progress) */
class PillBar(c: Context, private val from: Int, private val to: Int, private val track: Int) : View(c) {
    var max: Int = 100
        set(v) { field = v; invalidate() }
    var progress: Int = 0
        set(v) { field = v; invalidate() }
    private val ink = Ink()
    private var shader: Shader? = null
    private var shaderH = -1f

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f || h <= 0f) return
        val r = h / 2f
        ink.fill.shader = null; ink.fill.color = track; ink.rect.set(0f, 0f, w, h); cv.drawRoundRect(ink.rect, r, r, ink.fill)
        ink.fill.color = 0x14784F28; ink.rect.set(0f, 0f, w, h * 0.35f); cv.save(); ink.path.reset(); ink.path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW); cv.clipPath(ink.path); cv.drawRect(ink.rect, ink.fill); cv.restore()
        val frac = if (max > 0) (progress.toFloat() / max).coerceIn(0f, 1f) else 0f
        if (frac <= 0f) return
        val fw = max(h, w * frac)
        if (shader == null || shaderH != h) { shader = LinearGradient(0f, 0f, 0f, h, from, to, Shader.TileMode.CLAMP); shaderH = h }
        ink.fill.color = OPAQUE; ink.fill.shader = shader; ink.rect.set(0f, 0f, fw, h); cv.drawRoundRect(ink.rect, r, r, ink.fill); ink.fill.shader = null
        if (fw > h) { ink.fill.color = 0x66FFFFFF; ink.rect.set(h * 0.4f, h * 0.18f, fw - h * 0.4f, h * 0.44f); cv.drawRoundRect(ink.rect, h * 0.13f, h * 0.13f, ink.fill) }
    }
}
