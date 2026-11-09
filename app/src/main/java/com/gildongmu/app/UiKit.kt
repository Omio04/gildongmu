package com.gildongmu.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 화면 공통 도구: 크기(dp), 배경 모양, 글자·아이콘·버튼 만들기. 다른 화면 파일이 모두 가져다 씁니다.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

// ---------------------------------------------------------------- UI helpers

internal fun MainActivity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** A card: a calm fill and a thin edge. One corner radius for the whole app, so nothing looks pasted together. */
internal fun MainActivity.glass(color: Int = t.surface, radius: Int = CORNER) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius.coerceAtMost(CORNER)).toFloat()
    setStroke(dp(1), t.border)
}

internal fun MainActivity.fill(color: Int, radius: Int = CORNER) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius.coerceAtMost(CORNER)).toFloat()
}

/** Cards lift off the page by day (a soft shadow); at night they are told apart by lightness, not shadow. */
internal fun MainActivity.lift(v: View, dp: Int = 2): View = v.apply { if (!t.night) elevation = dp(dp).toFloat() }

/** The main action: a navy that deepens to the right (a flat block looks like a form, not a car). */
/** The one filled main button of the screen: a single flat colour (no gradient: it carries no information). */
internal fun MainActivity.hero() = GradientDrawable().apply {
    setColor(if (t.night) Color.parseColor("#1B66D1") else Color.parseColor("#002C5F"))
    cornerRadius = dp(CORNER).toFloat()
}

/** A secondary button: the fill is reserved for the one action that matters on a card. */
internal fun MainActivity.outline() = GradientDrawable().apply {
    setColor(t.surface)
    cornerRadius = dp(CORNER - 2).toFloat()
    setStroke(dp(1), t.border)
}

internal fun MainActivity.oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

/**
 * Small text is enlarged for a driver's glance: 12sp becomes 15, 14 becomes 17, 18 becomes 20; titles from 24sp up
 * are unchanged. The curve is monotonic, so the order of sizes in the design stays the same.
 */
internal fun MainActivity.legible(sp: Float) = sp * (1f + 0.25f * maxOf(0f, 24f - sp) / 12f)

/** weight: 0 regular, 1 semibold, 2 bold. Sizes used: 12 meta, 15 body, 18 label, 22 title (before [legible]). */
internal fun MainActivity.text(s: String, sp: Float, color: Int = t.ink, weight: Int = 0) =
    TextView(this).apply {
        text = s
        textSize = legible(sp)
        setTextColor(color)
        typeface = when (weight) { 2 -> fBold; 1 -> fSemi; else -> fRegular }
        includeFontPadding = false
        setLineSpacing(0f, 1.25f)   // a little air between lines: easier to read at a glance (the guides ask 140-160%; cards are tight)
        if (android.os.Build.VERSION.SDK_INT >= 33) {   // keep a Korean phrase together when the line wraps
            setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
        }
    }

internal fun MainActivity.icon(res: Int, sizeDp: Int, tint: Int) = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

internal fun MainActivity.badge(res: Int, color: Int, glyph: Int = 22): FrameLayout {
    val ic = ImageView(this).apply { setImageResource(res); setColorFilter(Color.WHITE) }
    return FrameLayout(this).apply {
        background = oval(color)
        addView(ic, FrameLayout.LayoutParams(dp(glyph), dp(glyph), Gravity.CENTER))
    }
}

internal fun MainActivity.pill(label: String, bgColor: Int, fg: Int = Color.WHITE, sp: Float = 13f) =
    text(label, sp, fg, 2).apply {
        background = fill(bgColor, 6)
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }

internal fun MainActivity.press(v: View) {
    v.setOnTouchListener { view, e ->  // a small press effect, so a tap is clearly felt
        when (e.action) {
            MotionEvent.ACTION_DOWN -> if (view.isEnabled) view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        false
    }
}

internal fun MainActivity.touchOk() = info.state != DriveState.MOVING

/** One big button in the card at the top: filled in its colour, or outlined when [bg] is the card's own surface. */
internal fun MainActivity.actionBtn(label: String, bg: Int, fg: Int, sp: Float = 17f, action: () -> Unit) = text(label, sp, if (bg == t.surface) t.ink else fg, 2).apply {
    gravity = Gravity.CENTER
    maxLines = 1
    background = if (bg == t.surface) outline() else fill(bg, 12)
    setPadding(dp(8), dp(14), dp(8), dp(14))
    setOnClickListener { action() }
    press(this)
}

/** [color] with the given opacity: a soft wash of it, for chips that must stand out without shouting. */
internal fun MainActivity.tint(color: Int, opacity: Float) = Color.argb((opacity * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
