package com.gildongmu.app

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * One distraction-optimized screen for the car display (landscape).
 * Left: road view with the neighbours. Right: driving state, one-touch presets, replies.
 * No text input, no video, no settings -> compatible with restriction flags 0x50.
 */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var monitor: DrivingMonitor
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var info = DriveInfo(DriveState.UNKNOWN, false, 0, null)

    private lateinit var road: RoadView
    private lateinit var stateDot: View
    private lateinit var stateText: TextView
    private lateinit var speedText: TextView
    private lateinit var stateNote: TextView
    private lateinit var lockBanner: LinearLayout
    private lateinit var replyAvatar: TextView
    private lateinit var replyTitle: TextView
    private lateinit var replyBody: TextView
    private val presetViews = mutableListOf<Triple<LinearLayout, ImageView, TextView>>()
    private lateinit var restStrip: TextView
    private lateinit var tripText: TextView
    private var navi: NaviClient? = null
    private var destName = "대구"

    // backend (server/app_server.py); falls back to local demo data if unreachable
    private val api = ApiClient()
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastInboxId = 0
    private var offline = false
    private var polling = false
    private var inboxPrimed = false
    private lateinit var couponRow: LinearLayout
    private lateinit var couponText: TextView

    // palette: light, matches the Pleos launcher tone
    private val bg = Color.parseColor("#EEF1F5")
    private val surface = Color.WHITE
    private val ink = Color.parseColor("#101828")
    private val muted = Color.parseColor("#667085")
    private val navy = Color.parseColor("#002C5F")
    private val blue = Color.parseColor("#1E6BFF")
    private val green = Color.parseColor("#12B76A")
    private val amber = Color.parseColor("#F79009")
    private val locked = Color.parseColor("#E4E7EC")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.KOREAN)
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        if (ContextCompat.checkSelfPermission(this, SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(SPEED_PERMISSION), 1)
        }
        monitor = DrivingMonitor(this) { newInfo -> ui.post { onDriveInfo(newInfo) } }
        monitor.start()
        render()
        polling = true
        pollLoop()

        navi = NaviClient(this, { trip -> ui.post { onTrip(trip) } }, { st -> android.util.Log.i("Gildongmu", "navi $st") }).also { it.start() }
        if (intent.getBooleanExtra("demo_route", false)) ui.postDelayed({ navi?.startDemoRoute() }, 2500)
        ui.postDelayed(object : Runnable {
            override fun run() { navi?.refresh(); ui.postDelayed(this, 10_000) }
        }, 10_000)
    }

    private fun onTrip(t: Trip) {
        if (t.destination.isBlank()) return
        destName = t.destination
        val h = t.remainMin / 60
        val m = t.remainMin % 60
        tripText.text = "${t.destination}까지 " + (if (h > 0) "${h}시간 " else "") + "${m}분 · %.0fkm".format(t.remainKm)
        tripText.setTextColor(navy)
        io.execute { try { api.setDestination(t.destination) } catch (_: Exception) {} }
    }

    /** Neighbours + incoming messages every 1.5 s, rest-area line every ~20 s. */
    private fun pollLoop(tick: Int = 0) {
        if (!polling) return
        io.execute {
            try {
                val near = api.nearby()
                val inbound = api.inbox(lastInboxId)
                val rest = if (tick % 13 == 0) api.nextRest() else null
                ui.post {
                    offline = false
                    if (near.isNotEmpty()) road.neighbors = near
                    if (!inboxPrimed) {  // skip history from before the app was opened
                        inbound.maxByOrNull { it.id }?.let { lastInboxId = it.id }
                        inboxPrimed = true
                    } else inbound.forEach { showInbound(it) }
                    rest?.let { restStrip.text = "다음 휴게소  ${it.name} · ${it.km}km · ${it.congestion}" }
                }
            } catch (e: Exception) {
                ui.post { offline = true }
            }
        }
        ui.postDelayed({ pollLoop(tick + 1) }, 1500)
    }

    private fun showInbound(m: Inbound) {
        lastInboxId = maxOf(lastInboxId, m.id)
        replyTitle.text = if (m.auto) "자동 안내 · ${m.from}" else "${m.from}님의 메시지"
        replyBody.text = m.text
        replyAvatar.text = m.from.take(1)
        replyAvatar.background = oval(if (m.auto) amber else blue)
        couponRow.visibility = if (m.coupon.isBlank()) View.GONE else View.VISIBLE
        couponText.text = m.coupon
        speak(m.text)
    }

    override fun onDestroy() {
        polling = false
        navi?.stop()
        monitor.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI build

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 24) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun text(s: String, sp: Float, color: Int = ink, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        }

    private fun card(pad: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(surface)
        elevation = dp(2).toFloat()
        setPadding(dp(pad + 4), dp(pad), dp(pad + 4), dp(pad))
    }

    private fun icon(res: Int, sizeDp: Int, tint: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        root.addView(buildRoadPanel(), LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(View(this), LinearLayout.LayoutParams(dp(14), 1))
        root.addView(buildControlPanel(), LinearLayout.LayoutParams(0, -1, 1.3f))
        return root
    }

    private fun buildRoadPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("길동무", 22f, navy, true))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tripText = text("", 15f, muted, true)
        head.addView(tripText)
        col.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        road = RoadView(this, DemoData.neighbors)
        col.addView(road, LinearLayout.LayoutParams(-1, 0, 1f))
        restStrip = text("다음 휴게소  불러오는 중…", 15f, ink, true).apply {
            background = rounded(surface, 16)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        col.addView(restStrip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(text("★ 같은 목적지 · 위치는 공개되지 않아요", 12f, muted).apply { setPadding(dp(4), dp(6), 0, 0) })
        return col
    }

    private fun buildControlPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // status card: state pill + speed
        val status = card(12).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stateDot = View(this)
        val pill = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        pill.addView(stateDot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(10) })
        stateText = text("", 22f, ink, true)
        pill.addView(stateText)
        status.addView(pill)
        stateNote = text("", 13f, muted).apply { setPadding(dp(14), 0, 0, 0) }
        status.addView(stateNote, LinearLayout.LayoutParams(0, -2, 1f))
        speedText = text("", 26f, navy, true)
        status.addView(speedText)
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // one-touch presets
        val icons = mapOf(
            "HELLO" to R.drawable.ic_chat, "ASK_REST" to R.drawable.ic_parking,
            "ASK_FOOD" to R.drawable.ic_food, "ASK_ACCIDENT" to R.drawable.ic_warning,
        )
        DemoData.presets.chunked(2).forEach { pair ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { p ->
                val ic = icon(icons[p.code] ?: R.drawable.ic_chat, 24, Color.WHITE)
                val badge = FrameBox(this).apply {
                    background = oval(blue)
                    addView(ic, android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                }
                val label = text(p.label, 17f, ink, true)
                val btn = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(surface)
                    elevation = dp(2).toFloat()
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    minimumHeight = dp(92)
                    setOnClickListener { send(p) }
                }
                btn.addView(badge, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
                btn.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                presetViews += Triple(btn, ic, label)
                line.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            col.addView(line, LinearLayout.LayoutParams(-1, -2))
        }

        // lock banner (only while moving)
        lockBanner = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#FEF0C7"), 16)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        lockBanner.addView(icon(R.drawable.ic_lock, 18, Color.parseColor("#B54708")))
        lockBanner.addView(text("주행 중에는 버튼이 잠겨요 · 답변은 음성으로 전달돼요", 14f, Color.parseColor("#B54708"), true)
            .apply { setPadding(dp(8), 0, 0, 0) })
        col.addView(lockBanner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(6), dp(4), dp(2)) })

        // reply card
        val reply = card(14).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        replyAvatar = text("길", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = oval(navy)
        }
        reply.addView(replyAvatar, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(16) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        replyTitle = text("받은 답변", 13f, muted, true)
        replyBody = text("버튼을 누르면 주변 차량이 답해요", 20f, ink, true)
        body.addView(replyTitle)
        body.addView(replyBody)
        couponRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        couponRow.addView(text("쿠폰", 13f, Color.WHITE, true).apply {
            background = rounded(blue, 10)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        })
        couponText = text("", 15f, blue, true).apply { setPadding(dp(10), 0, 0, 0) }
        couponRow.addView(couponText)
        body.addView(couponRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        reply.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        reply.addView(icon(R.drawable.ic_volume, 24, muted))
        col.addView(reply, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        return col
    }

    /** FrameLayout with an oval badge background. */
    private class FrameBox(ctx: android.content.Context) : android.widget.FrameLayout(ctx)

    // ---------------------------------------------------------------- behaviour

    private fun onDriveInfo(newInfo: DriveInfo) {
        val becameMoving = newInfo.state == DriveState.MOVING && info.state != DriveState.MOVING
        info = newInfo
        render()
        if (becameMoving) speak("주행 중이에요. 버튼은 잠기고 안내는 음성으로만 전달돼요")
    }

    private fun render() {
        val color = when (info.state) {
            DriveState.PARKED -> green
            DriveState.IDLING -> amber
            DriveState.MOVING -> Color.parseColor("#F04438")
            DriveState.UNKNOWN -> muted
        }
        stateDot.background = oval(color)
        stateText.text = info.state.label
        stateNote.text = when (info.state) {
            DriveState.PARKED -> "메시지를 보낼 수 있어요"
            DriveState.IDLING -> "정체 중 · 한 번 터치로 보내요"
            DriveState.MOVING -> "안전을 위해 입력 잠금"
            DriveState.UNKNOWN -> "차량 미연결(시험 모드)"
        }
        speedText.text = info.speedKmh?.let { "%.0f km/h".format(it) } ?: ""
        road.moving = info.state == DriveState.MOVING

        val enabled = info.canSend
        presetViews.forEach { (btn, ic, label) ->
            btn.isEnabled = enabled
            btn.background = rounded(if (enabled) surface else locked)
            btn.elevation = if (enabled) dp(2).toFloat() else 0f
            label.setTextColor(if (enabled) ink else Color.parseColor("#98A2B3"))
            ic.alpha = if (enabled) 1f else 0.55f
        }
        lockBanner.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun send(p: Preset) {
        if (!info.canSend) return
        replyTitle.text = "보냄 · ${p.label}"
        replyBody.text = "답변 기다리는 중…"
        val speed = info.speedKmh ?: 0f
        val gear = if (info.state == DriveState.PARKED) "P" else "D"
        io.execute {
            val err = try { api.send(p.code, if (p.code == "HELLO") destName else null, speed, gear) } catch (e: Exception) { "offline" }
            if (err == "offline") {
                // no backend: keep the demo usable with canned answers
                val reply = DemoData.replyFor(p.code)
                ui.postDelayed({ showInbound(Inbound(lastInboxId, reply.from, reply.text, reply.auto)) }, 1200)
            } else if (err != null) {
                ui.post { replyBody.text = "보낼 수 없어요 ($err)" }
            }
        }
    }

    private fun speak(s: String) {
        if (ttsReady) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "msg")
    }

    companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
    }
}
