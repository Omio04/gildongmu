package com.drivepet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
    private val ink get() = if (night) 0xFFF0F3FF.toInt() else Look.INK
    private val sub get() = if (night) 0xFFB8C2E6.toInt() else Look.SUB
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

    private var selectedItem: String? = null
    private val night = Night.isDark(context)
    /** the heading and the counter sit on the sea: dark blue by day, pale on the night sea */
    private val headInk = if (night) Color.parseColor("#E6ECFF") else Color.parseColor("#2D3F66")

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (night) intArrayOf(Color.parseColor("#0A1448"), Color.parseColor("#122A74"), Color.parseColor("#1C3F96"))
            else intArrayOf(Color.parseColor("#D5E8FC"), Color.parseColor("#B4D5F8"), Color.parseColor("#9CC9F2")))
        isClickable = true   // touches do not reach the room underneath
        selected = defaultSelection()

        val close = title("‹ 뒤로", 19f, ink).apply {
            gravity = Gravity.CENTER; minHeight = dp(44); minWidth = dp(92)
            setPadding(dp(12), dp(6), dp(16), dp(6))
                        background = if (night) kit.card(100f, 0xE61B2566.toInt(), Color.argb(150, 140, 160, 240), 1.2f) else kit.card(100f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
            kit.lift(this, 3f)
            setOnClickListener { onClose() }
        }
        total.apply {
            textSize = 13f; setTextColor(headInk); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = if (night) round(Color.argb(150, 20, 30, 60), 18, Color.argb(160, 150, 165, 210), 1)
            else round(Color.argb(191, 255, 255, 255), 18, Color.argb(230, 255, 255, 255), 1)
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(16), dp(6))
            addView(close)
            addView(FrameLayout(context).apply {
                addView(title("대한민국 지도 도감", 23f, headInk).apply {
                    gravity = Gravity.CENTER; setPadding(dp(20), dp(8), dp(24), dp(8))
                    setCompoundDrawablesRelative(Glyphs.pin(context, 22f), null, null, null); compoundDrawablePadding = dp(8)
                    background = if (night) round(Color.argb(200, 16, 28, 90), 26, Color.argb(160, 140, 160, 240), 1) else round(Color.argb(210, 255, 255, 255), 26, Color.argb(230, 255, 255, 255), 1)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(total)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(map, LinearLayout.LayoutParams(0, -1, 1f))
            addView(ScrollView(context).apply {
                background = if (night) LayerDrawable(arrayOf(round(0xE6141E5A.toInt(), 24, Color.argb(160, 140, 160, 240), 1), SkylineDrawable(resources.displayMetrics.density, true)))
                else LayerDrawable(arrayOf(kit.card(24f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f), SkylineDrawable(resources.displayMetrics.density, false)))
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
        // day / night is chosen on the home screen only (the ☀ button there)
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

    private fun rarityColor(r: Rarity) = when (r) { Rarity.COMMON -> sub; Rarity.UNCOMMON -> Color.parseColor(if (night) "#8FB0FF" else "#3F6FD0"); Rarity.RARE -> Color.parseColor(if (night) "#F2C25A" else "#B87510") }

    private fun openRegion(id: String) {
        var layer: RegionLayer? = null
        layer = RegionLayer(context, id) { layer?.let { removeView(it) } }
        addView(layer, LayoutParams(-1, -1))
    }

    private fun renderPanel() {
        panel.removeAllViews()
        if (!PetHub.started) return
        val dex = PetHub.dex
        total.text = "전체 ${dex.owned.size}/${dex.total} · 완성 지역 ${dex.regionsDone()}/${Korea.regions.size}"
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
        if (PetHub.regionId == r.id) panel.addView(text("지금 이 지역을 달리고 있어요", 12f, if (night) 0xFFFF9C8A.toInt() else Look.CORAL_D, true).apply {
            setPadding(0, dp(6), 0, 0); gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelative(Glyphs.pin(context, 13f), null, null, null); compoundDrawablePadding = dp(4)
        })

        for (item in r.items) {
            val have = dex.has(item.id)
            panel.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { selectedItem = item.id; renderPanel() }
                background = if (night) (if (have) round(0xFF2A3790.toInt(), 18, rarityColor(item.rarity), 2) else round(0xFF1D2A78.toInt(), 18, Color.argb(90, 140, 160, 230), 1))
                else if (have) round(Look.WHITE, 18, rarityColor(item.rarity), 2) else round(Color.parseColor("#F8F1E7"), 18, Look.ROW_LINE, 1)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView((if (have) TextView(context).apply { foreground = ItemIconDrawable(item) } else title("?", 22f, if (night) 0xFF8FA0E0.toInt() else Look.MUTED)).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (night) (if (have) 0xFF3A4AA8.toInt() else 0xFF2B3A92.toInt()) else if (have) Color.parseColor("#FFF4E2") else Color.parseColor("#EFE5D8")); if (have) setStroke(dp(1), if (night) Color.argb(120, 160, 180, 255) else Color.parseColor("#F0DCC0")) }
                }, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(if (have) item.name else "???", 15.5f, if (have) ink else sub, true))
                    addView(text("${item.kind.label} · ${item.rarity.label}", 12f, rarityColor(item.rarity), true))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                if (item.rarity == Rarity.RARE) addView(View(context).apply { background = Glyphs.sparkleDrawable(context, 14f) }, LinearLayout.LayoutParams(dp(14), dp(14)))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        // the card of the touched item: a big picture, what it is, and a line about it; a hint while it is still "???"
        r.items.firstOrNull { it.id == selectedItem }?.let { item ->
            val have = dex.has(item.id)
            panel.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = if (night) round(0xFF2A3790.toInt(), 22, rarityColor(item.rarity), 2) else round(Look.WHITE, 22, rarityColor(item.rarity), 2)
                addView((if (have) TextView(context).apply { foreground = ItemIconDrawable(item, 0.7f) } else title("?", 44f, if (night) 0xFF8FA0E0.toInt() else Look.MUTED)).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (night) 0xFF3A4AA8.toInt() else Color.parseColor("#FFF4E2")); setStroke(dp(2), rarityColor(item.rarity)) }
                }, LinearLayout.LayoutParams(dp(110), dp(110)))
                addView(title(if (have) item.name else "???", 24f, ink), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
                addView(text("${r.name} · ${item.kind.label} · ${item.rarity.label}", 14f, rarityColor(item.rarity), true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                val line = if (have) when (item.kind) {
                    Kind2.FOOD -> "${r.name}에서 만난 ${item.name}! 길 위에서 처음 맛본 이 고장의 맛이에요."
                    Kind2.PRODUCT -> "${r.name}이 자랑하는 ${item.name}. 이 고장의 정성이 담긴 특산물이에요."
                    Kind2.SIGHT -> "${r.name}의 명소 ${item.name}. 달리다 마주친 풍경을 도감에 담았어요."
                } else "아직 만나지 못했어요. 내비 길안내로 ${r.name}을(를) 달리면 펫이 주워 와요." + if (item.rarity == Rarity.RARE) " (아주 귀한 ${item.kind.label}이에요)" else ""
                addView(text(line, 14.5f, ink).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        panel.addView(title("🏝 컬렉션 열기", 18f, if (night) Color.WHITE else Color.parseColor("#2D3F66")).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10))
            background = if (night) round(0xFF3A4AA8.toInt(), 22, Color.argb(160, 140, 160, 240), 1) else round(0xFFE6EEFF.toInt(), 22, 0xFFB9CBF2.toInt(), 1)
            setOnClickListener { openRegion(r.id) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        RegionLook.blurb[r.id]?.let { panel.addView(text(it, 13.5f, ink).apply { setPadding(0, dp(12), 0, 0); setLineSpacing(0f, 1.25f) }) }
        val note = if (done) "이 지역을 모두 모았어요! 🎉" else "다 모으면 완성 보너스 +${Dex.REGION_BONUS}P"
        panel.addView(text(note, 13f, if (done) Color.parseColor(if (night) "#FFD27A" else "#7A5200") else sub, true).apply { setPadding(0, dp(12), 0, 0) })
        panel.addView(text("내비 길안내 중에 이 지역을 달리면 펫이 주워 와요. 처음 들어간 지역에서는 첫 1km에 꼭 하나를 주워 와요.", 11.5f, sub).apply { setPadding(0, dp(4), 0, 0); setLineSpacing(0f, 1.2f) })
        if (!PetHub.locationAllowed) panel.addView(text("위치 권한이 없어서 지금은 주워 올 수 없어요", 12f, Color.parseColor("#C0392B"), true).apply { setPadding(0, dp(8), 0, 0) })
    }
}

/** a night city along the bottom of the map panel: dark blue buildings with a few lit windows, a hill and the Seoul tower */
internal class SkylineDrawable(private val d: Float, private val night: Boolean) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(c: Canvas) {
        val l = bounds.left.toFloat(); val r = bounds.right.toFloat(); val base = bounds.bottom.toFloat(); val top = base - 190f * d
        p.shader = if (night) LinearGradient(0f, top, 0f, base, 0x00406BFF, 0x55406BFF, Shader.TileMode.CLAMP) else LinearGradient(0f, top, 0f, base, 0x00BFE0FF, 0x66BFE0FF, Shader.TileMode.CLAMP)
        c.drawRect(l, top, r, base, p); p.shader = null
        val w = r - l
        // the hill and the tower on it
        p.color = if (night) 0xFF1A2478.toInt() else 0xFFA9D8A0.toInt()
        path.reset(); path.moveTo(l + w * 0.35f, base); path.quadTo(l + w * 0.72f, base - 70f * d, r, base - 28f * d); path.lineTo(r, base); path.close()
        c.drawPath(path, p)
        val tx = l + w * 0.74f
        p.color = if (night) 0xFF222E8A.toInt() else 0xFF8FA5D8.toInt()
        c.drawRect(tx - 1.6f * d, base - 118f * d, tx + 1.6f * d, base - 52f * d, p)
        c.drawRoundRect(tx - 6f * d, base - 92f * d, tx + 6f * d, base - 80f * d, 3f * d, 3f * d, p)
        c.drawRect(tx - 0.8f * d, base - 138f * d, tx + 0.8f * d, base - 118f * d, p)
        // the buildings: the same ones every time it is drawn
        val rnd = java.util.Random(11)
        var x = l + 4f * d
        while (x < r) {
            val bw = (14 + rnd.nextInt(18)) * d; val bh = (26 + rnd.nextInt(64)) * d
            p.color = if (night) 0xFF16206B.toInt() else 0xFFB8C8E8.toInt(); c.drawRect(x, base - bh, x + bw, base, p)
            p.color = if (night) 0xCCFFD98A.toInt() else 0xCCFFFFFF.toInt()
            var wy = base - bh + 7f * d
            while (wy < base - 8f * d) {
                var wx = x + 3f * d
                while (wx < x + bw - 4f * d) { if (rnd.nextInt(4) == 0) c.drawRect(wx, wy, wx + 2.6f * d, wy + 3.6f * d, p); wx += 6f * d }
                wy += 9f * d
            }
            x += bw + 2f * d
        }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
