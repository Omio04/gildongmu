package com.gildongmu.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One distraction-optimized screen for the car display (landscape), day and night.
 *
 * Left: road view with the cars around us and how many people are connected.
 * Right: where we are in the trip, what neighbours reported about the road ahead,
 * and the way to ask. Parked or waiting in traffic: one-touch questions (to everyone, or to one car you
 * tap on the road). Moving: the buttons are replaced by a voice screen, so nothing useless sits locked.
 * No text input, no video, no settings -> compatible with restriction flags 0x50.
 */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var t: Theme
    private lateinit var monitor: DrivingMonitor
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voice: VoiceInput? = null
    private var info = DriveInfo(DriveState.UNKNOWN, false, 0, null)
    private var forcedInfo: DriveInfo? = null   // debug builds only (see force_state)

    // trip data
    private var navi: NaviClient? = null
    private var trip: Trip? = null
    private var destName = "대구"
    private var situation: List<SituationItem> = emptyList()

    // who we ask: everyone around, or the one car tapped on the road
    private var directMode = false
    private var directTarget: Neighbor? = null
    private var neighbors: List<Neighbor> = emptyList()

    // backend (server/app_server.py); falls back to local demo data if unreachable
    private lateinit var api: ApiClient
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastInboxId = 0
    private var polling = false
    private var inboxPrimed = false

    // views
    private lateinit var road: RoadView
    private lateinit var liveChip: TextView
    private lateinit var crowdText: TextView
    private lateinit var stateDot: View
    private lateinit var stateText: TextView
    private lateinit var tripMain: TextView
    private lateinit var tripSub: TextView
    private lateinit var micBtn: LinearLayout
    private lateinit var situationCard: LinearLayout
    private lateinit var stoppedArea: LinearLayout
    private lateinit var chipAll: LinearLayout
    private lateinit var chipAllText: TextView
    private lateinit var chipOne: LinearLayout
    private lateinit var chipOneText: TextView
    private lateinit var voiceHero: LinearLayout
    private lateinit var replyCard: LinearLayout
    private lateinit var replyAvatar: TextView
    private lateinit var replyTitle: TextView
    private lateinit var replyLive: TextView
    private lateinit var replyBody: TextView
    private lateinit var couponRow: LinearLayout
    private lateinit var couponText: TextView
    private lateinit var thanksBtn: LinearLayout
    private lateinit var thanksText: TextView
    private lateinit var reportBtn: TextView
    private val presetViews = mutableListOf<Triple<LinearLayout, ImageView, TextView>>()

    // answer card state
    private var currentMsgId = -1
    private var thanked = false
    private var waitingText: String? = null
    private var tick = 0

    private val fRegular by lazy { ResourcesCompat.getFont(this, R.font.pretendard_regular) ?: Typeface.DEFAULT }
    private val fSemi by lazy { ResourcesCompat.getFont(this, R.font.pretendard_semibold) ?: Typeface.DEFAULT_BOLD }
    private val fBold by lazy { ResourcesCompat.getFont(this, R.font.pretendard_bold) ?: Typeface.DEFAULT_BOLD }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = Theme.of(resources.configuration)
        api = ApiClient(serverUrl())
        setContentView(buildUi())

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.KOREAN)
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                android.util.Log.i(TAG, "tts ready=$ttsReady (language result $r)")
            } else {
                android.util.Log.i(TAG, "tts init failed: $status")
            }
        }
        voice = VoiceInput(this,
            onHeard = { spoken -> ui.post { askSpoken(spoken) } },
            onProblem = { msg -> ui.post { showNote(msg) } },
            onListening = { ui.post { startWaiting("듣고 있어요"); setReplyTitle("말씀해 주세요", false) } })

        if (ContextCompat.checkSelfPermission(this, SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(SPEED_PERMISSION), 1)
        }
        monitor = DrivingMonitor(this) { newInfo -> ui.post { onDriveInfo(newInfo) } }
        monitor.start()
        render()
        polling = true
        pollLoop()

        // debug builds only: pretend the car is in a given state, to look at that screen without the console
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            when (intent.getStringExtra("force_state")) {
                "moving" -> ui.postDelayed({ forcedInfo = DriveInfo(DriveState.MOVING, true, 0x50, 32f); onDriveInfo(forcedInfo!!) }, 4000)
                "idling" -> ui.postDelayed({ forcedInfo = DriveInfo(DriveState.IDLING, true, 0x50, 0f); onDriveInfo(forcedInfo!!) }, 4000)
            }
        }

        navi = NaviClient(this, { tr -> ui.post { onTrip(tr) } }, { st -> android.util.Log.i(TAG, "navi $st") }).also { it.start() }
        if (intent.getBooleanExtra("demo_route", false)) {
            var tries = 0
            ui.postDelayed(object : Runnable {
                override fun run() {
                    if (navi?.startDemoRoute() != true && ++tries < 20) ui.postDelayed(this, 1500)
                }
            }, 2500)
        }
        ui.postDelayed(object : Runnable {
            override fun run() { navi?.refresh(); ui.postDelayed(this, 10_000) }
        }, 10_000)
    }

    /**
     * Where the server is. The emulator reaches the PC at 10.0.2.2; a real phone needs the PC's address:
     *   adb shell am start -n com.gildongmu.app/.MainActivity --es server http://192.168.0.10:8080
     * The address is remembered, so the app can be started normally afterwards.
     */
    private fun serverUrl(): String {
        val prefs = getSharedPreferences("gildongmu", MODE_PRIVATE)
        (intent.getStringExtra("server") ?: intent.data?.getQueryParameter("url"))
            ?.let { prefs.edit().putString("server", it).apply() }
        return prefs.getString("server", null) ?: "http://10.0.2.2:8080"
    }

    override fun onDestroy() {
        polling = false
        navi?.stop()
        monitor.stop()
        voice?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 24) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** weight: 0 regular, 1 semibold, 2 bold. Sizes used: 12 meta, 15 body, 18 label, 21 title, 24 answer. */
    private fun text(s: String, sp: Float, color: Int = t.ink, weight: Int = 0) =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = when (weight) { 2 -> fBold; 1 -> fSemi; else -> fRegular }
            includeFontPadding = false
        }

    private fun card(padV: Int = 14, padH: Int = 18) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(t.surface, 26)
        elevation = if (t.night) 0f else dp(2).toFloat()
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    private fun icon(res: Int, sizeDp: Int, tint: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun badge(res: Int, color: Int, box: Int = 44, glyph: Int = 22): FrameLayout {
        val ic = ImageView(this).apply { setImageResource(res); setColorFilter(Color.WHITE) }
        return FrameLayout(this).apply {
            background = oval(color)
            addView(ic, FrameLayout.LayoutParams(dp(glyph), dp(glyph), Gravity.CENTER))
        }
    }

    private fun pill(label: String, bgColor: Int, fg: Int = Color.WHITE, sp: Float = 12f) =
        text(label, sp, fg, 2).apply {
            background = rounded(bgColor, 10)
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }

    private fun press(v: View) {
        v.setOnTouchListener { view, e ->  // a small press effect, so a tap is clearly felt
            when (e.action) {
                MotionEvent.ACTION_DOWN -> if (view.isEnabled) view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    // ---------------------------------------------------------------- UI build

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(t.bg)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        root.addView(buildRoadPanel(), LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(View(this), LinearLayout.LayoutParams(dp(14), 1))
        root.addView(buildRightPanel(), LinearLayout.LayoutParams(0, -1, 1.35f))
        return root
    }

    private fun buildRoadPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("길동무", 22f, t.primary, 2))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        liveChip = pill("실제 이웃 0명", t.parked).apply { visibility = View.GONE }
        head.addView(liveChip)
        col.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        road = RoadView(this, DemoData.neighbors, t, fRegular, fSemi).apply {
            onPick = { n -> directTarget = n; selectedId = n.id; updateScope() }
        }
        col.addView(road, LinearLayout.LayoutParams(-1, 0, 1f))

        // how many people and cars are around right now
        val crowd = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(t.surface, 18)
            setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        crowd.addView(View(this).apply { setBackgroundColor(t.parked) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) })
        crowdText = text("주변을 찾는 중…", 15f, t.ink, 1)
        crowd.addView(crowdText)
        col.addView(crowd, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return col
    }

    private fun buildRightPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // row 1: driving state, trip, voice
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val state = card(12, 16).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stateDot = View(this)
        state.addView(stateDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(9) })
        stateText = text("", 18f, t.ink, 2)
        state.addView(stateText)
        top.addView(state, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(10) })

        val tripCard = card(10, 18).apply { gravity = Gravity.CENTER_VERTICAL }
        tripMain = text("내비에서 목적지를 설정해 주세요", 18f, t.primary, 2)
        tripSub = text("내비게이션과 연결돼요", 13f, t.muted)
        tripCard.addView(tripMain)
        tripCard.addView(tripSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        top.addView(tripCard, LinearLayout.LayoutParams(0, -1, 1f))

        micBtn = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = rounded(t.primary.let { if (t.night) t.accent else t.navyFill }, 24)
            elevation = dp(2).toFloat()
            setPadding(dp(16), 0, dp(18), 0)
            setOnClickListener { startVoice() }
        }
        micBtn.addView(icon(R.drawable.ic_mic, 22, Color.WHITE))
        micBtn.addView(text("말로 질문", 15f, Color.WHITE, 2).apply { setPadding(dp(8), 0, 0, 0) })
        press(micBtn)
        top.addView(micBtn, LinearLayout.LayoutParams(-2, -1).apply { leftMargin = dp(10) })
        col.addView(top, LinearLayout.LayoutParams(-1, dp(62)).apply { bottomMargin = dp(8) })

        // row 2: what is ahead, as reported by neighbours (hidden until someone has told us something)
        situationCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(t.surface, 22)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        col.addView(situationCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // row 3a (parked / waiting in traffic): who to ask, then the four questions
        stoppedArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scope = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipAllText = text("전체 이웃", 15f, Color.WHITE, 2)
        chipAll = chip(chipAllText) { directMode = false; directTarget = null; road.selectedId = null; updateScope() }
        chipOneText = text("한 대에게", 15f, t.ink, 2)
        chipOne = chip(chipOneText) { directMode = true; updateScope() }
        scope.addView(chipAll, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
        scope.addView(chipOne, LinearLayout.LayoutParams(0, dp(40), 1f))
        stoppedArea.addView(scope, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        val icons = mapOf(
            "HELLO" to R.drawable.ic_chat, "ASK_REST" to R.drawable.ic_parking,
            "ASK_FOOD" to R.drawable.ic_food, "ASK_ACCIDENT" to R.drawable.ic_warning,
        )
        DemoData.presets.chunked(2).forEach { pair ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { p ->
                val bd = badge(icons[p.code] ?: R.drawable.ic_chat, t.accent, 46, 24)
                val ic = bd.getChildAt(0) as ImageView
                val label = text(p.label, 17f, t.ink, 1)
                val btn = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(t.surface, 24)
                    elevation = if (t.night) 0f else dp(2).toFloat()
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    minimumHeight = dp(76)
                    setOnClickListener { send(p) }
                }
                press(btn)
                btn.addView(bd, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) })
                btn.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                presetViews += Triple(btn, ic, label)
                line.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            stoppedArea.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        col.addView(stoppedArea, LinearLayout.LayoutParams(-1, -2))

        // row 3b (moving): voice is the way to ask, so show that instead of locked buttons
        voiceHero = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (t.night) t.surface else t.navyFill, 28)
            setPadding(dp(20), dp(14), dp(22), dp(14))
            visibility = View.GONE
            setOnClickListener { startVoice() }
        }
        press(voiceHero)
        val mic = badge(R.drawable.ic_mic, t.accent, 72, 36)
        voiceHero.addView(mic, LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(18) })
        val vcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vcol.addView(text("말로 물어보세요", 22f, Color.WHITE, 2))
        vcol.addView(text("“다음 휴게소 혼잡해?”  “앞에 무슨 일이야?”", 14f, Color.parseColor("#C9D6EA")),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
        vcol.addView(text("달리는 중에는 근처 모든 이웃에게 물어요 · 답은 음성으로 들려드려요", 12f, Color.parseColor("#9FB2D0")),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        voiceHero.addView(vcol)
        col.addView(voiceHero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        // row 4: the answer
        replyCard = card(14, 18).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        replyAvatar = text("길", 20f, Color.WHITE, 2).apply { gravity = Gravity.CENTER; background = oval(t.navyFill) }
        replyCard.addView(replyAvatar, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(16) })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        replyTitle = text("받은 답변", 13f, t.muted, 1)
        replyLive = pill("LIVE", t.parked, Color.WHITE, 11f).apply { visibility = View.GONE }
        titleRow.addView(replyTitle)
        titleRow.addView(replyLive, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        body.addView(titleRow)
        replyBody = text("물어보면 근처 이웃이 답해요", 22f, t.ink, 2)
        body.addView(replyBody, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })

        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        couponRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        couponRow.addView(pill("쿠폰", t.accent, Color.WHITE, 13f))
        couponText = text("", 15f, t.accent, 1).apply { setPadding(dp(10), 0, 0, 0) }
        couponRow.addView(couponText)
        bottom.addView(couponRow)
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        thanksBtn = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(t.heartSoft, 20)
            setPadding(dp(14), dp(8), dp(16), dp(8))
            visibility = View.GONE
            setOnClickListener { thank() }
        }
        thanksBtn.addView(icon(R.drawable.ic_heart, 18, t.heart))
        thanksText = text("고마워요", 15f, t.heart, 2).apply { setPadding(dp(7), 0, 0, 0) }
        thanksBtn.addView(thanksText)
        bottom.addView(thanksBtn)
        reportBtn = text("신고", 13f, t.muted, 1).apply {
            setPadding(dp(14), dp(8), dp(4), dp(8))
            visibility = View.GONE
            setOnClickListener { reportCurrent() }
        }
        bottom.addView(reportBtn)
        body.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        replyCard.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        replyCard.addView(icon(R.drawable.ic_volume, 24, t.muted))
        col.addView(replyCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        return col
    }

    private fun chip(label: TextView, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        addView(label)
        setOnClickListener { onClick() }
        press(this)
    }

    // ---------------------------------------------------------------- who we ask

    private fun updateScope() {
        fun style(chip: LinearLayout, label: TextView, on: Boolean) {
            chip.background = rounded(if (on) (if (t.night) t.accent else t.navyFill) else t.surface, 20)
            label.setTextColor(if (on) Color.WHITE else t.ink)
        }
        val people = neighbors.count { it.live }
        chipAllText.text = "전체 이웃 · 실제 ${people}명"
        chipOneText.text = when {
            directTarget != null -> "${directTarget!!.nickname}에게"
            directMode -> "도로에서 차를 눌러 고르세요"
            else -> "한 대에게"
        }
        style(chipAll, chipAllText, !directMode)
        style(chipOne, chipOneText, directMode)
        road.pickEnabled = directMode && info.state != DriveState.MOVING
    }

    // ---------------------------------------------------------------- data in

    private fun onTrip(tr: Trip) {
        if (tr.destination.isBlank()) return
        trip = tr
        destName = tr.destination
        val h = tr.remainMin / 60
        val m = tr.remainMin % 60
        tripMain.text = "${tr.destination}까지 " + (if (h > 0) "${h}시간 " else "") + "${m}분"
        val eta = SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(System.currentTimeMillis() + tr.remainMin * 60_000L))
        tripSub.text = "%.0fkm · %s 도착 예정".format(tr.remainKm, eta)
        io.execute { try { api.setDestination(tr.destination) } catch (_: Exception) {} }
    }

    /** Neighbours + incoming messages every 1.5 s, what is ahead every ~4.5 s. */
    private fun pollLoop() {
        if (!polling) return
        val n = tick++
        io.execute {
            try {
                val near = api.nearby()
                val inbound = api.inbox(lastInboxId)
                val shared = if (n % 3 == 0 || inbound.isNotEmpty()) api.situation() else null
                ui.post {
                    if (near.isNotEmpty()) onNeighbors(near)
                    if (!inboxPrimed) {  // skip history from before the app was opened
                        inbound.maxByOrNull { it.id }?.let { lastInboxId = it.id }
                        inboxPrimed = true
                    } else inbound.forEach { showInbound(it) }
                    shared?.let { renderSituation(it) }
                }
            } catch (e: Exception) {
                // server unreachable: the screen keeps working with what it has
            }
        }
        ui.postDelayed({ pollLoop() }, 1500)
    }

    private fun onNeighbors(near: List<Neighbor>) {
        neighbors = near
        road.neighbors = near
        val people = near.count { it.live }
        liveChip.visibility = if (people > 0) View.VISIBLE else View.GONE
        liveChip.text = "실제 이웃 ${people}명"
        crowdText.text = "지금 연결된 이웃  사람 ${people}명 · 주변 차량 ${near.size}대"
        if (directTarget != null && near.none { it.id == directTarget!!.id }) {  // the car we picked drove off
            directTarget = null
            road.selectedId = null
        }
        updateScope()
    }

    /** Every answer a neighbour gives is shared: whoever asked first, everyone on the road reads it. */
    private fun renderSituation(items: List<SituationItem>) {
        if (items == situation) return
        situation = items
        situationCard.removeAllViews()
        if (items.isEmpty()) { situationCard.visibility = View.GONE; return }
        situationCard.visibility = View.VISIBLE
        situationCard.addView(text("앞 상황 · 이웃 제보", 12f, t.muted, 1))
        items.take(2).forEach { item ->
            val (accent, glyph) = when (item.kind) {
                "warning" -> t.moving to R.drawable.ic_warning
                "cafe" -> t.waiting to R.drawable.ic_cafe
                "food" -> t.parked to R.drawable.ic_food
                else -> t.accent to R.drawable.ic_info
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(badge(glyph, accent, 30, 16), LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(item.title, 16f, t.ink, 2), LinearLayout.LayoutParams(-2, -2))
            col.addView(text(item.detail, 12f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
            row.addView(col)
            situationCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }
    }

    // ---------------------------------------------------------------- behaviour

    private fun onDriveInfo(reported: DriveInfo) {
        val newInfo = forcedInfo ?: reported
        val becameMoving = newInfo.state == DriveState.MOVING && info.state != DriveState.MOVING
        info = newInfo
        render()
        if (becameMoving) speak("달리는 중이에요. 질문은 말로 해 주세요. 답은 음성으로 들려드려요")
    }

    private fun render() {
        val color = when (info.state) {
            DriveState.PARKED -> t.parked
            DriveState.IDLING -> t.waiting
            DriveState.MOVING -> t.moving
            DriveState.UNKNOWN -> t.muted
        }
        stateDot.background = oval(color)
        stateText.text = info.state.label
        val moving = info.state == DriveState.MOVING
        road.moving = moving

        // moving: voice screen instead of locked buttons
        stoppedArea.visibility = if (moving) View.GONE else View.VISIBLE
        voiceHero.visibility = if (moving) View.VISIBLE else View.GONE
        micBtn.visibility = if (moving) View.GONE else View.VISIBLE
        if (moving) { directMode = false; directTarget = null; road.selectedId = null }
        updateScope()

        val enabled = info.canSend
        presetViews.forEach { (btn, ic, label) ->
            btn.isEnabled = enabled
            ic.alpha = if (enabled) 1f else 0.55f
            label.setTextColor(if (enabled) t.ink else t.faint)
        }
        thanksBtn.alpha = if (enabled || thanked) 1f else 0.45f
        reportBtn.alpha = if (enabled) 1f else 0.45f
    }

    private fun gearCode() = if (info.state == DriveState.PARKED) "P" else "D"

    private fun send(p: Preset) {
        if (!info.canSend) return
        if (directMode && directTarget == null) { showNote("먼저 도로에서 물어볼 차를 눌러 주세요"); return }
        val to = if (directMode) directTarget?.id else null
        startWaiting(if (to != null) "${directTarget!!.nickname}에게 물어보는 중" else "근처 이웃에게 물어보는 중")
        setReplyTitle("보냄 · ${p.label}", live = false)
        val speed = info.speedKmh ?: 0f
        io.execute {
            val res = try { api.send(p.code, if (p.code == "HELLO") destName else null, speed, gearCode(), to) } catch (e: Exception) { null }
            deliver(res, p.code)
        }
    }

    /** Hands-free: stays available while driving, which is how a driver asks once the buttons are gone. */
    private fun askSpoken(spoken: String) {
        startWaiting("근처 이웃에게 물어보는 중")
        setReplyTitle("“$spoken”", live = false)
        io.execute {
            val res = try { api.sendSpoken(spoken, destName, gearCode()) } catch (e: Exception) { null }
            deliver(res, null)
        }
    }

    private fun deliver(res: SendResult?, presetCode: String?) {
        when {
            res == null -> {  // no backend: keep the demo usable with canned answers
                val reply = DemoData.replyFor(presetCode ?: "ASK_REST")
                ui.postDelayed({ showInbound(Inbound(-1, reply.from, reply.text, reply.auto)) }, 1200)
            }
            res.code == 422 -> ui.post { showNote("잘 못 알아들었어요. “다음 휴게소 혼잡해?”처럼 말해 보세요") }
            res.error != null -> ui.post { showNote(res.error) }
            res.waitingHuman -> ui.post { waitingText = "실제 이웃의 답을 기다리는 중" }
        }
    }

    private fun startVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        tts?.stop()
        voice?.listen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) voice?.listen()
    }

    private fun showInbound(m: Inbound) {
        lastInboxId = maxOf(lastInboxId, m.id)
        stopWaiting()
        swapReply {
            setReplyTitle(if (m.auto) "자동 안내 · ${m.from}" else "${m.from}님의 답변", live = m.human)
            replyBody.text = m.text
            replyAvatar.text = m.from.take(1)
            replyAvatar.background = oval(if (m.auto) t.waiting else t.accent)
            couponRow.visibility = if (m.coupon.isBlank()) View.GONE else View.VISIBLE
            couponText.text = m.coupon
            currentMsgId = m.id
            thanked = false
            thanksText.text = "고마워요"
            thanksBtn.visibility = if (m.auto || m.id < 0) View.GONE else View.VISIBLE
            reportBtn.visibility = thanksBtn.visibility
            render()
        }
        speak(m.text)
    }

    /** A short message in the answer card (hearing problems, errors, confirmations). */
    private fun showNote(msg: String) {
        stopWaiting()
        swapReply {
            setReplyTitle("길동무", live = false)
            replyBody.text = msg
            replyAvatar.text = "길"
            replyAvatar.background = oval(t.navyFill)
            couponRow.visibility = View.GONE
            thanksBtn.visibility = View.GONE
            reportBtn.visibility = View.GONE
        }
    }

    private fun thank() {
        if (!info.canSend || thanked || currentMsgId < 0) return
        thanked = true
        thanksText.text = "전했어요 ✓"
        val id = currentMsgId
        io.execute { try { api.thanks(id) } catch (_: Exception) {} }
    }

    private fun reportCurrent() {
        if (!info.canSend || currentMsgId < 0) return
        val id = currentMsgId
        currentMsgId = -1
        io.execute { try { api.report(id) } catch (_: Exception) {} }
        showNote("신고했어요 · 이 이웃의 답은 더 이상 보이지 않아요")
    }

    private fun setReplyTitle(s: String, live: Boolean) {
        replyTitle.text = s
        replyLive.visibility = if (live) View.VISIBLE else View.GONE
    }

    /** Cross-fade the answer card so a new answer is noticed without being jarring. */
    private fun swapReply(update: () -> Unit) {
        replyCard.animate().cancel()
        replyCard.animate().alpha(0f).translationY(dp(6).toFloat()).setDuration(100).withEndAction {
            update()
            replyCard.translationY = -dp(6).toFloat()
            replyCard.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }.start()
    }

    // waiting indicator: three pixel squares that light up one after another
    private var pixel = 0
    private val pixelRunnable = object : Runnable {
        override fun run() {
            val base = waitingText ?: return
            pixel = (pixel + 1) % 3
            val squares = (0 until 3).joinToString("") { if (it == pixel) "■" else "□" }
            replyBody.text = "$base  $squares"
            ui.postDelayed(this, 380)
        }
    }

    private fun startWaiting(text: String) {
        waitingText = text
        thanksBtn.visibility = View.GONE
        reportBtn.visibility = View.GONE
        couponRow.visibility = View.GONE
        replyAvatar.background = oval(t.muted)
        replyAvatar.text = "…"
        ui.removeCallbacks(pixelRunnable)
        pixelRunnable.run()
    }

    private fun stopWaiting() {
        waitingText = null
        ui.removeCallbacks(pixelRunnable)
    }

    private fun speak(s: String) {
        if (ttsReady) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "msg")
    }

    companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        private const val TAG = "Gildongmu"
    }
}
