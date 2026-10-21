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
 * Left: the road with the cars around us, our state and trip on top, how many people are connected below.
 * Right: what neighbours reported about the road ahead, how to ask, the answers, and the voice bar.
 * Parked or waiting in traffic: four quick questions (to everyone, or to one car tapped on the road).
 * Moving: the buttons give way to a big voice screen, so nothing useless sits locked.
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
    private var tick = 0

    // the answers to the last question
    private var answers = mutableListOf<Inbound>()
    private var answersTitle = "이웃의 답"
    private var answerLimit = MAX_ANSWERS
    private var note: String? = null
    private var expandedId: Int? = null
    private val thankedIds = mutableSetOf<Int>()
    private var waitingText: String? = null
    private var pixel = 0

    // views
    private lateinit var road: RoadView
    private lateinit var stateDot: View
    private lateinit var stateText: TextView
    private lateinit var tripMain: TextView
    private lateinit var tripSub: TextView
    private lateinit var liveChip: TextView
    private lateinit var crowdText: TextView
    private lateinit var bannerCard: LinearLayout
    private lateinit var stoppedArea: LinearLayout
    private lateinit var chipAll: LinearLayout
    private lateinit var chipAllText: TextView
    private lateinit var chipOne: LinearLayout
    private lateinit var chipOneText: TextView
    private lateinit var answersCard: LinearLayout
    private lateinit var answersHeader: TextView
    private lateinit var answersCount: TextView
    private lateinit var answersList: LinearLayout
    private lateinit var voiceBar: LinearLayout
    private lateinit var voiceWave: WaveView
    private lateinit var voiceHero: LinearLayout
    private val tileViews = mutableListOf<Triple<LinearLayout, ImageView, TextView>>()

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
            onHeard = { spoken -> ui.post { voiceWave.active = false; askSpoken(spoken) } },
            onProblem = { msg -> ui.post { voiceWave.active = false; showNote(msg) } },
            onListening = { ui.post { voiceWave.active = true; startWaiting("듣고 있어요") } })

        if (ContextCompat.checkSelfPermission(this, SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(SPEED_PERMISSION), 1)
        }
        monitor = DrivingMonitor(this) { newInfo -> ui.post { onDriveInfo(newInfo) } }
        monitor.start()
        render()
        renderAnswers()
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

    /** A glass card: a soft fill and a thin edge. */
    private fun glass(color: Int = t.surface, radius: Int = 22) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), t.border)
    }

    private fun fill(color: Int, radius: Int = 24) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** weight: 0 regular, 1 semibold, 2 bold. Sizes used: 12 meta, 15 body, 18 label, 22 title. */
    private fun text(s: String, sp: Float, color: Int = t.ink, weight: Int = 0) =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = when (weight) { 2 -> fBold; 1 -> fSemi; else -> fRegular }
            includeFontPadding = false
        }

    private fun icon(res: Int, sizeDp: Int, tint: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun badge(res: Int, color: Int, glyph: Int = 22): FrameLayout {
        val ic = ImageView(this).apply { setImageResource(res); setColorFilter(Color.WHITE) }
        return FrameLayout(this).apply {
            background = oval(color)
            addView(ic, FrameLayout.LayoutParams(dp(glyph), dp(glyph), Gravity.CENTER))
        }
    }

    private fun pill(label: String, bgColor: Int, fg: Int = Color.WHITE, sp: Float = 12f) =
        text(label, sp, fg, 2).apply {
            background = fill(bgColor, 10)
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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(buildRoadPanel(), LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        root.addView(buildRightPanel(), LinearLayout.LayoutParams(0, -1, 1.28f))
        return root
    }

    private fun buildRoadPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // the road, with our state and trip floating on top
        val frame = FrameLayout(this)
        road = RoadView(this, DemoData.neighbors, t, fRegular, fSemi).apply {
            onPick = { n -> directTarget = n; selectedId = n.id; updateScope() }
        }
        frame.addView(road, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(10), dp(10), 0) }
        val state = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = fill(if (t.night) Color.parseColor("#CC0B1424") else Color.parseColor("#E6FFFFFF"), 16)
            setPadding(dp(12), dp(8), dp(14), dp(8))
        }
        stateDot = View(this)
        state.addView(stateDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        stateText = text("", 15f, t.ink, 2)
        state.addView(stateText)
        top.addView(state, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        val trip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fill(if (t.night) Color.parseColor("#CC0B1424") else Color.parseColor("#E6FFFFFF"), 16)
            setPadding(dp(12), dp(6), dp(14), dp(6))
        }
        tripMain = text("목적지 미설정", 14f, t.primary, 2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        tripSub = text("내비에서 설정해 주세요", 11f, t.muted)
        trip.addView(tripMain)
        trip.addView(tripSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        top.addView(trip, LinearLayout.LayoutParams(0, -2, 1f))
        frame.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        col.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        // how many people and cars are around right now
        val crowd = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = glass(radius = 18)
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

        // header: who we are, and whether real people are on the road
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = FrameLayout(this).apply {
            background = fill(t.navyFill, 11)
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_launcher_foreground) },
                FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        }
        head.addView(logo, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })
        head.addView(text("길동무", 22f, t.primary, 2))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        liveChip = text("● 시뮬레이션", 13f, t.muted, 2).apply {
            background = glass(radius = 16)
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        head.addView(liveChip)
        col.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // what neighbours reported about the road ahead (always there, so the layout does not jump)
        bannerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(radius = 20)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        col.addView(bannerCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        renderSituation(emptyList(), force = true)

        // parked / waiting in traffic: who to ask, then the four quick questions
        stoppedArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scope = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipAllText = text("전체 이웃", 15f, Color.WHITE, 2)
        chipAll = chip(chipAllText) { directMode = false; directTarget = null; road.selectedId = null; updateScope() }
        chipOneText = text("한 대에게", 15f, t.ink, 2)
        chipOne = chip(chipOneText) { directMode = true; updateScope() }
        scope.addView(chipAll, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(8) })
        scope.addView(chipOne, LinearLayout.LayoutParams(0, dp(38), 1f))
        stoppedArea.addView(scope, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val spec = listOf(
            Triple("ASK_REST", "휴게소 혼잡", R.drawable.ic_parking),
            Triple("ASK_FOOD", "맛집", R.drawable.ic_food),
            Triple("ASK_ACCIDENT", "앞 상황", R.drawable.ic_warning),
            Triple("HELLO", "인사", R.drawable.ic_chat),
        )
        spec.forEachIndexed { i, (code, short, res) ->
            val preset = DemoData.presets.first { it.code == code }
            val bd = badge(res, t.accent, 22)
            val label = text(short, 15f, t.ink, 2).apply { gravity = Gravity.CENTER }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = glass(radius = 22)
                setPadding(dp(6), dp(12), dp(6), dp(12))
                setOnClickListener { send(preset) }
            }
            press(tile)
            tile.addView(bd, LinearLayout.LayoutParams(dp(40), dp(40)))
            tile.addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            tileViews += Triple(tile, bd.getChildAt(0) as ImageView, label)
            tiles.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        stoppedArea.addView(tiles, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        col.addView(stoppedArea, LinearLayout.LayoutParams(-1, -2))

        // the answers
        answersCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(radius = 22)
            setPadding(dp(16), dp(12), dp(16), dp(10))
        }
        val ah = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        answersHeader = text("이웃의 답", 14f, t.muted, 2)
        answersCount = text("", 13f, t.accent, 2)
        ah.addView(answersHeader)
        ah.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        ah.addView(answersCount)
        answersCard.addView(ah)
        answersList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        answersCard.addView(answersList, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        col.addView(answersCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(8) })

        // the voice bar (compact) and, while moving, the big voice screen in its place
        voiceBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = glass(radius = 24)
            setPadding(dp(12), dp(8), dp(18), dp(8))
            setOnClickListener { startVoice() }
        }
        press(voiceBar)
        val ring = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), t.accent) }
            addView(icon(R.drawable.ic_mic, 22, t.accent), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        voiceBar.addView(ring, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(14) })
        val vtext = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vtext.addView(text("말로 물어보세요", 17f, t.ink, 2))
        vtext.addView(text("“다음 휴게소 막혀?”", 13f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        voiceBar.addView(vtext, LinearLayout.LayoutParams(0, -2, 1f))
        voiceWave = WaveView(this, t.accent)
        voiceBar.addView(voiceWave, LinearLayout.LayoutParams(dp(54), dp(26)))
        col.addView(voiceBar, LinearLayout.LayoutParams(-1, dp(62)))

        voiceHero = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = fill(if (t.night) t.surfaceAlt else t.navyFill, 28)
            setPadding(dp(20), dp(14), dp(22), dp(14))
            visibility = View.GONE
            setOnClickListener { startVoice() }
        }
        press(voiceHero)
        voiceHero.addView(badge(R.drawable.ic_mic, t.accent, 36), LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(18) })
        val vcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vcol.addView(text("말로 물어보세요", 22f, Color.WHITE, 2))
        vcol.addView(text("“다음 휴게소 혼잡해?”  “앞에 무슨 일이야?”", 14f, Color.parseColor("#C9D6EA")),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
        vcol.addView(text("달리는 중에는 근처 모든 이웃에게 물어요 · 답은 음성으로 들려드려요", 12f, Color.parseColor("#9FB2D0")),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        voiceHero.addView(vcol)
        col.addView(voiceHero, LinearLayout.LayoutParams(-1, -2))
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
            chip.background = if (on) fill(t.strong, 19) else glass(radius = 19)
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
        destName = tr.destination
        val h = tr.remainMin / 60
        val m = tr.remainMin % 60
        tripMain.text = "${tr.destination} " + (if (h > 0) "${h}시간 " else "") + "${m}분"
        val eta = SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(System.currentTimeMillis() + tr.remainMin * 60_000L))
        tripSub.text = "%.0fkm · %s 도착".format(tr.remainKm, eta)
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
        liveChip.text = if (people > 0) "● LIVE · 실제 이웃 ${people}명" else "● 시뮬레이션"
        liveChip.setTextColor(if (people > 0) t.parked else t.muted)
        crowdText.text = "지금 연결된 이웃  사람 ${people}명 · 주변 차량 ${near.size}대"
        if (directTarget != null && near.none { it.id == directTarget!!.id }) {  // the car we picked drove off
            directTarget = null
            road.selectedId = null
        }
        updateScope()
    }

    /** Every answer a neighbour gives is shared: whoever asked first, everyone on the road reads it. */
    private fun renderSituation(items: List<SituationItem>, force: Boolean = false) {
        if (!force && items == situation) return
        situation = items
        bannerCard.removeAllViews()
        bannerCard.addView(text("앞 상황 · 이웃 제보", 12f, t.muted, 1))
        if (items.isEmpty()) {
            bannerCard.addView(text("아직 제보가 없어요. 물어보면 이웃이 알려 줘요", 15f, t.ink, 1),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            return
        }
        items.take(2).forEach { item ->
            val (accent, glyph) = when (item.kind) {
                "warning" -> t.moving to R.drawable.ic_warning
                "cafe" -> t.waiting to R.drawable.ic_cafe
                "food" -> t.parked to R.drawable.ic_food
                else -> t.accent to R.drawable.ic_info
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(badge(glyph, accent, 16), LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(item.title, 16f, t.ink, 2), LinearLayout.LayoutParams(-2, -2))
            col.addView(text(item.detail, 12f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
            row.addView(col)
            bannerCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
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

        // moving: the big voice screen instead of the buttons
        stoppedArea.visibility = if (moving) View.GONE else View.VISIBLE
        voiceBar.visibility = if (moving) View.GONE else View.VISIBLE
        voiceHero.visibility = if (moving) View.VISIBLE else View.GONE
        if (moving) { directMode = false; directTarget = null; road.selectedId = null }
        updateScope()

        val enabled = info.canSend
        tileViews.forEach { (tile, ic, label) ->
            tile.isEnabled = enabled
            ic.alpha = if (enabled) 1f else 0.55f
            label.setTextColor(if (enabled) t.ink else t.faint)
        }
        renderAnswers()
    }

    private fun gearCode() = if (info.state == DriveState.PARKED) "P" else "D"

    private fun send(p: Preset) {
        if (!info.canSend) return
        if (directMode && directTarget == null) { showNote("먼저 도로에서 물어볼 차를 눌러 주세요"); return }
        val to = if (directMode) directTarget?.id else null
        newQuestion(if (to != null) "${directTarget!!.nickname}에게 물어봤어요" else "전체 이웃에게 물어봤어요", to != null)
        startWaiting(if (to != null) "${directTarget!!.nickname}에게 물어보는 중" else "근처 이웃에게 물어보는 중")
        val speed = info.speedKmh ?: 0f
        io.execute {
            val res = try { api.send(p.code, if (p.code == "HELLO") destName else null, speed, gearCode(), to) } catch (e: Exception) { null }
            deliver(res, p.code)
        }
    }

    /** Hands-free: stays available while driving, which is how a driver asks once the buttons are gone. */
    private fun askSpoken(spoken: String) {
        newQuestion("“$spoken”", false)
        startWaiting("근처 이웃에게 물어보는 중")
        io.execute {
            val res = try { api.sendSpoken(spoken, destName, gearCode()) } catch (e: Exception) { null }
            deliver(res, null)
        }
    }

    private fun newQuestion(title: String, direct: Boolean) {
        answers.clear()
        note = null
        expandedId = null
        answersTitle = title
        answerLimit = if (direct) 1 else MAX_ANSWERS
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
        note = null
        if (answers.size >= answerLimit) answers.removeAt(0)
        answers += m
        renderAnswers()
        speak(m.text)
    }

    /** A short message in the answers card (hearing problems, errors, confirmations). */
    private fun showNote(msg: String) {
        stopWaiting()
        answers.clear()
        note = msg
        renderAnswers()
    }

    // ---------------------------------------------------------------- the answers card

    private fun renderAnswers() {
        if (!::answersList.isInitialized) return
        answersHeader.text = answersTitle
        answersCount.text = if (answers.isEmpty()) "" else "답변 ${answers.size}/$answerLimit"
        answersList.removeAllViews()
        val enabled = info.canSend

        waitingText?.let {
            answersList.addView(text("$it  ${(0 until 3).joinToString("") { i -> if (i == pixel) "■" else "□" }}", 20f, t.ink, 2),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        if (answers.isEmpty() && waitingText == null) {
            answersList.addView(text(note ?: "물어보면 근처 이웃이 답해요", 20f, t.ink, 2),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        answers.takeLast(3).forEach { m ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val av = text(m.from.take(1), 15f, Color.WHITE, 2).apply {
                gravity = Gravity.CENTER
                background = oval(if (m.auto) t.waiting else t.accent)
            }
            row.addView(av, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val nameRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            nameRow.addView(text(if (m.auto) "자동 안내 · ${m.from}" else m.from, 12f, t.muted, 1))
            if (m.human) nameRow.addView(pill("LIVE", t.parked, Color.WHITE, 10f), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            col.addView(nameRow)
            col.addView(text(m.text, 18f, t.ink, 2).apply { maxLines = 2 }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            if (m.coupon.isNotBlank()) {
                val cr = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                cr.addView(pill("쿠폰", t.accent, Color.WHITE, 11f))
                cr.addView(text(m.coupon, 13f, t.accent, 1).apply { setPadding(dp(8), 0, 0, 0) })
                col.addView(cr, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            val play = badge(R.drawable.ic_play, if (t.night) t.surfaceAlt else t.navyFill, 16).apply {
                setOnClickListener { speak(m.text) }
            }
            row.addView(play, LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(8) })
            box.addView(row)
            if (!m.auto && m.id >= 0) {
                if (expandedId == m.id) {
                    val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(48), dp(8), 0, 0) }
                    val done = m.id in thankedIds
                    val thanks = LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        background = fill(t.heartSoft, 18)
                        setPadding(dp(14), dp(7), dp(16), dp(7))
                        alpha = if (enabled || done) 1f else 0.45f
                        setOnClickListener { thank(m.id) }
                    }
                    thanks.addView(icon(R.drawable.ic_heart, 16, t.heart))
                    thanks.addView(text(if (done) "전했어요 ✓" else "고마워요", 14f, t.heart, 2).apply { setPadding(dp(6), 0, 0, 0) })
                    actions.addView(thanks)
                    actions.addView(text("신고", 13f, t.muted, 1).apply {
                        setPadding(dp(16), dp(8), dp(6), dp(8))
                        alpha = if (enabled) 1f else 0.45f
                        setOnClickListener { report(m.id) }
                    })
                    box.addView(actions)
                }
                row.setOnClickListener { expandedId = if (expandedId == m.id) null else m.id; renderAnswers() }
            }
            answersList.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    }

    private fun thank(id: Int) {
        if (!info.canSend || id in thankedIds) return
        thankedIds += id
        renderAnswers()
        io.execute { try { api.thanks(id) } catch (_: Exception) {} }
    }

    private fun report(id: Int) {
        if (!info.canSend) return
        io.execute { try { api.report(id) } catch (_: Exception) {} }
        answers.removeAll { it.id == id }
        note = "신고했어요 · 이 이웃의 답은 더 이상 보이지 않아요"
        expandedId = null
        renderAnswers()
    }

    // waiting indicator: three pixel squares that light up one after another
    private val pixelRunnable = object : Runnable {
        override fun run() {
            if (waitingText == null) return
            pixel = (pixel + 1) % 3
            renderAnswers()
            ui.postDelayed(this, 380)
        }
    }

    private fun startWaiting(text: String) {
        waitingText = text
        note = null
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
        private const val MAX_ANSWERS = 3
    }
}
