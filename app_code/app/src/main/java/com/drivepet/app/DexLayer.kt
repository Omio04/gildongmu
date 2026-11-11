package com.drivepet.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The map collection screen ("지도 도감"): a sticker-style map of Korea on the left (tap a region), and on the right what the
 * chosen region holds (its five things, found or still "???"). Opened from the button next to the menu button, and only
 * reachable while parked (the whole app screen is). The emoji are stand-ins for the designer's art.
 */
class DexLayer(context: Context, private val onClose: () -> Unit) : FrameLayout(context) {
    private val ink = Look.INK
    private val sub = Look.SUB
    private val kit = Kit(context)

    private var selected: String? = null
    private val map = DexMapView(context) { id -> selected = id; renderPanel() }
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val total = TextView(context)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int, stroke: Int? = null, strokeW: Int = 2) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != null) setStroke(dp(strokeW), stroke)
    }
    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun title(s: String, sp: Float, color: Int) = kit.title(TextView(context).apply { text = s }, sp, color)

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#D5E8FC"), Color.parseColor("#B4D5F8"), Color.parseColor("#9CC9F2")))
        isClickable = true   // touches do not reach the room underneath
        selected = defaultSelection()

        val close = title("닫기", 17f, ink).apply {
            gravity = Gravity.CENTER; minHeight = dp(44); minWidth = dp(92)
            setPadding(dp(12), dp(6), dp(16), dp(6))
            setCompoundDrawablesRelative(Glyphs.cross(context, 13f, Look.COCOA), null, null, null); compoundDrawablePadding = dp(6)
            background = kit.card(100f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
            kit.lift(this, 3f)
            setOnClickListener { onClose() }
        }
        total.apply {
            textSize = 13f; setTextColor(Color.parseColor("#2D3F66")); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = round(Color.argb(191, 255, 255, 255), 18, Color.argb(230, 255, 255, 255), 1)
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(16), dp(6))
            addView(close)
            addView(title("대한민국 지도 도감", 23f, Color.parseColor("#2D3F66")).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(total)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(map, LinearLayout.LayoutParams(0, -1, 1f))
            addView(ScrollView(context).apply {
                background = kit.card(24f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
                clipToOutline = true
                kit.lift(this, 6f)
                addView(panel)
                isVerticalScrollBarEnabled = false
            }, LinearLayout.LayoutParams(dp(330), -1).apply { setMargins(dp(8), dp(4), dp(14), dp(14)) })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }, LayoutParams(-1, -1))
        map.selected = selected
        renderPanel()
    }

    private fun defaultSelection(): String {
        if (!PetHub.started) return "seoul"
        PetHub.regionId?.let { return it }
        val dex = PetHub.dex
        return Korea.regions.firstOrNull { dex.count(it.id) in 1 until it.items.size }?.id ?: "seoul"
    }

    /** the screen is opened again after finds: show the new state */
    fun refresh() { map.refresh(); renderPanel() }

    /** nothing to go back to inside this screen: the caller closes it */
    fun back(): Boolean = false

    private fun rarityColor(r: Rarity) = when (r) { Rarity.COMMON -> sub; Rarity.UNCOMMON -> Color.parseColor("#3F6FD0"); Rarity.RARE -> Color.parseColor("#B87510") }

    private fun renderPanel() {
        panel.removeAllViews()
        if (!PetHub.started) return
        val dex = PetHub.dex
        total.text = "${dex.owned.size}/${dex.total} · 지역 ${dex.regionsDone()}/${Korea.regions.size}"
        val r = Korea.region(selected ?: "seoul") ?: return
        val n = dex.count(r.id)
        val done = n == r.items.size
        panel.setPadding(dp(16), dp(14), dp(16), dp(12))

        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title(r.name, 27f, ink), LinearLayout.LayoutParams(0, -2, 1f))
            addView(title(if (done) "완성 ★" else "$n/${r.items.size}", 17f, if (done) Color.parseColor("#7A5200") else Color.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = round(if (done) Color.parseColor("#FFD966") else Look.PERI, 16)
            })
        })
        if (PetHub.regionId == r.id) panel.addView(text("지금 이 지역을 달리고 있어요", 12f, Look.CORAL_D, true).apply {
            setPadding(0, dp(6), 0, 0); gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelative(Glyphs.pin(context, 13f), null, null, null); compoundDrawablePadding = dp(4)
        })

        for (item in r.items) {
            val have = dex.has(item.id)
            panel.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (have) round(Look.WHITE, 18, rarityColor(item.rarity), 2) else round(Color.parseColor("#F8F1E7"), 18, Look.ROW_LINE, 1)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView((if (have) text(item.emoji, 26f, ink) else title("?", 22f, Look.MUTED)).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (have) Color.parseColor("#FFF4E2") else Color.parseColor("#EFE5D8")); if (have) setStroke(dp(1), Color.parseColor("#F0DCC0")) }
                }, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(if (have) item.name else "???", 15.5f, if (have) ink else sub, true))
                    addView(text("${item.kind.label} · ${item.rarity.label}", 12f, rarityColor(item.rarity), true))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                if (item.rarity == Rarity.RARE) addView(View(context).apply { background = Glyphs.sparkleDrawable(context, 14f) }, LinearLayout.LayoutParams(dp(14), dp(14)))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        val note = if (done) "이 지역을 모두 모았어요! 🎉" else "다 모으면 완성 보너스 +${Dex.REGION_BONUS}P"
        panel.addView(text(note, 13f, if (done) Color.parseColor("#7A5200") else sub, true).apply { setPadding(0, dp(12), 0, 0) })
        panel.addView(text("내비 길안내 중에 이 지역을 달리면 펫이 주워 와요. 처음 들어간 지역에서는 첫 1km에 꼭 하나를 주워 와요.", 11.5f, sub).apply { setPadding(0, dp(4), 0, 0); setLineSpacing(0f, 1.2f) })
        if (!PetHub.locationAllowed) panel.addView(text("위치 권한이 없어서 지금은 주워 올 수 없어요", 12f, Color.parseColor("#C0392B"), true).apply { setPadding(0, dp(8), 0, 0) })
    }
}
