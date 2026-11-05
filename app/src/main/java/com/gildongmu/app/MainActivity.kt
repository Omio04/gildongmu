package com.gildongmu.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
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
 * 길동무: the people around you in a traffic jam. Not a navigation app: no route, no turn-by-turn; the navigation
 * only lends the basics (where to, how long, arrival) as one quiet line.
 *
 * Left: the road with the cars around us (some with a speech bubble), how long we have been stuck,
 * and how many real people are on the road with us.
 * Right: what neighbours reported ahead, three ways to take part (ask / tell / say something), the answers,
 * and always a voice bar. If the vehicle forbids touching altogether, the buttons give way to a big voice screen.
 * One distraction-optimized screen: big one-tap choices and voice, nothing to type, nothing that moves on its own.
 */
class MainActivity : AppCompatActivity() {

    private class Tile(val code: String, val label: String, val icon: Int, val color: Int)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var t: Theme
    private lateinit var monitor: DrivingMonitor
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voice: VoiceInput? = null
    private var info = DriveInfo(DriveState.UNKNOWN, false, 0, null)
    private var forcedInfo: DriveInfo? = null   // debug builds only (see force_state)

    // how long we have been crawling or standing: a human measure the navigation does not have
    private var jamSince: Long? = null
    private var fastSince: Long? = null

    private var navi: NaviClient? = null
    private var destName: String? = null
    private var trip: Trip? = null
    private var board = BoardView(emptyList(), Standing(0, "길 위 새싹", 30, 0))
    private val skipped = mutableSetOf<Int>()        // reports whose "맞아요 / 고마워요" prompt ran out or was answered
    private val promptSeen = mutableSetOf<Int>()     // prompts that already started their countdown
    private val announced = mutableSetOf<Int>()      // urgent reports we already read out loud
    private var questions: List<QuestionItem> = emptyList()
    private val announcedQ = mutableSetOf<Int>()     // questions that were already read out loud
    private var roadMessages: List<RoadMessage> = emptyList()
    private val announcedMsg = mutableSetOf<Int>()   // messages that were already read out loud
    private var hazard: HazardProposal? = null       // "the car sees something: report it?"
    private var hazardToken = 0
    private val gps by lazy { PlatformLocation(this) }
    private var hazardByDriver = false
    private var hazardKey = ""                       // our own id for the report on screen: pressing again after a slow answer is not a second report
    private var hazardSeenAt = 0L                    // when the driver saw it (the server also notes when it arrived)
    private var hazardFailed = false                 // the last try did not get through: the card is back, with a hint
    private var hazardPhoto: ByteArray? = null          // one frame taken when the hazard was detected; sent only if the driver reports
    private val hazardCamera by lazy { HazardCamera(this) }
    private var answerTarget: Int? = null            // the question a spoken answer is for (null = a message to a car)
    private var pickedCar: Neighbor? = null          // a car the driver tapped on the road: ask it something, or report it
    private var pickedToken = 0
    private class PendingReport(val name: String, val carId: String?, val file: (String) -> Unit)
    private var pendingReport: PendingReport? = null // "신고" was tapped: the driver is picking the reason (nothing is filed yet)
    private var askCar: Neighbor? = null             // the car the next spoken question goes to (null = everyone around)
    private var askCarAt = 0L
    private var lastPosLog = 0L
    // the driver's own profile: a name built from two lists, a colour and a Hyundai model; nothing is typed
    private var myAdj = ADJECTIVES.random()
    private var myAnimal = ANIMALS.random()
    private var myColor = COLORS[0]
    private var myModel = ""
    private var myShowModel = true
    private var profileOpen = false
    private lateinit var profileAvatar: TextView
    private lateinit var profileName: TextView
    private lateinit var profileSub: TextView
    private var turn: Turn? = null                   // the navigation's next manoeuvre (null: no route, or none reported)
    private lateinit var turnBar: LinearLayout       // above the road, never over it: the turn and the arrival time
    private lateinit var turnArrow: TextView
    private lateinit var turnMain: TextView
    private lateinit var turnSub: TextView
    private lateinit var etaLine: TextView
    private val jam = JamDetector()                 // decides when the car may offer "왜 막힐까요?"
    private var nudge: JamReason? = null             // the offer on screen right now
    private var nudgeToken = 0
    private var bubbleToken = 0
    private var answersToken = 0
    private val answeredCars = mutableMapOf<String, String>()   // neighbour id -> cause it named (lights the car up)
    private lateinit var whyButton: LinearLayout
    private lateinit var whyTitle: TextView
    private lateinit var whySub: TextView
    private lateinit var pointsChip: TextView
    private var themePref = "auto"
    private lateinit var ctaRow: LinearLayout
    private lateinit var attentionCard: LinearLayout
    private var neighbors: List<Neighbor> = emptyList()
    private var neighborsKnown = false
    private var online = true

    // backend (server/app_server.py); falls back to local demo data if unreachable
    private lateinit var api: ApiClient
    private val io = java.util.concurrent.Executors.newFixedThreadPool(3)   // a slow call must not make a report or a tap wait behind it
    private val pollIo = java.util.concurrent.Executors.newSingleThreadExecutor()   // polling never waits behind a slow send
    @Volatile private var pollBusy = false
    private val pollTick = Runnable { pollLoop() }   // one named runnable, so a pause/resume can never leave two loops running
    private var lastInboxId = 0
    private var polling = false
    private var inboxPrimed = false
    private var tick = 0

    // the answers to the last question
    private var answers = mutableListOf<Inbound>()
    private var answersTitle = "도로 소식"
    private var answerLimit = MAX_ANSWERS
    private var note: String? = null
    private var expandedId: Int? = null
    private val thankedIds = mutableSetOf<Int>()
    private val thankedPoints = mutableMapOf<Int, Int>()   // message id -> points the neighbour got from our thanks
    private var asking = false              // a question is out and nobody has answered yet
    private var askToken = 0

    // what the recogniser heard, held back until the driver says it is right (never sent unconfirmed)
    private var heardText: String? = null
    private var order: Shop? = null                // a (sample) pickup waiting for the driver to pay or cancel
    private var pickPlaces: List<Shop> = emptyList()   // the places listed for the kind the driver asked for
    private var pickType = ""
    private var confirmByVoice = false   // true while we wait for "보내"/"다시"/"취소" said out loud

    // views
    private lateinit var road: RoadView
    private lateinit var stateDot: View
    private lateinit var stateText: TextView
    private lateinit var stateSub: TextView
    private lateinit var liveChip: TextView
    private lateinit var crowdText: TextView
    private lateinit var bannerCard: LinearLayout
    private lateinit var stoppedArea: LinearLayout
    private lateinit var tilesRow: LinearLayout
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

    private val informTiles by lazy {
        listOf(
            Tile("accident", "사고 났어요", R.drawable.ic_warning, t.moving),
            Tile("debris", "낙하물", R.drawable.ic_warning, t.moving),
            Tile("roadkill", "로드킬", R.drawable.ic_warning, t.moving),
            Tile("work", "공사 중", R.drawable.ic_info, t.waiting),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // first run: follow the vehicle's own day/night; after that, the driver's choice (라이트 / 다크) is kept
        themePref = getSharedPreferences("gildongmu", MODE_PRIVATE).getString("theme", null).let { saved ->
            if (saved == "day" || saved == "night") saved
            else if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) "night" else "day"
        }
        t = Theme.of(resources.configuration, themePref)
        api = ApiClient(serverUrl())
        loadProfile()
        setContentView(buildUi())
        refreshProfile()   // now that the road exists, it gets my name too

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.KOREAN)
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) { if (utteranceId == "confirm" || utteranceId == "nudge") ui.post { listenForConfirm() } }
                    override fun onDone(utteranceId: String?) { if (utteranceId == "confirm" || utteranceId == "nudge") ui.post { listenForConfirm() } }
                })
                android.util.Log.i(TAG, "tts ready=$ttsReady (language result $r)")
            } else {
                android.util.Log.i(TAG, "tts init failed: $status")
            }
        }
        voice = VoiceInput(this,
            onHeard = { spoken -> ui.post { voiceWave.active = false; if (confirmByVoice) answerConfirm(spoken) else if (hazard != null) answerHazard(spoken) else if (order != null) answerOrder(spoken) else if (pickPlaces.isNotEmpty()) answerPick(spoken) else if (nudge != null) answerNudge(spoken) else routeSpoken(spoken) } },
            onProblem = { msg -> ui.post { voiceWave.active = false; showNote(msg) } },
            onListening = { ui.post { voiceWave.active = true } })

        if (ContextCompat.checkSelfPermission(this, SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(SPEED_PERMISSION), 1)
        }
        monitor = DrivingMonitor(this) { newInfo -> ui.post { onDriveInfo(newInfo) } }
        monitor.start()
        render()
        renderAnswers()
        // nothing leaves the car (position, profile, questions) until the driver has read what is used and agreed
        consented = getSharedPreferences("gildongmu", MODE_PRIVATE).getBoolean(CONSENT_KEY, false)
        if (consented) startSharing() else showConsent(readOnly = false)
        ui.postDelayed(object : Runnable {   // the "stuck for N minutes" label moves on its own clock
            override fun run() { renderState(); ui.postDelayed(this, 20_000) }
        }, 20_000)

        ui.postDelayed(object : Runnable {
            override fun run() { evaluateJam(); ui.postDelayed(this, 5_000) }
        }, 5_000)

        // debug builds only: pretend the car is in a given state, to look at that screen without the console
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            when (intent.getStringExtra("force_state")) {
                "moving" -> ui.postDelayed({ forcedInfo = DriveInfo(DriveState.MOVING, true, 0x50, 32f); onDriveInfo(forcedInfo!!) }, 4000)
                "idling" -> ui.postDelayed({ forcedInfo = DriveInfo(DriveState.IDLING, true, 0x50, 0f); onDriveInfo(forcedInfo!!) }, 4000)
                "locked" -> ui.postDelayed({ forcedInfo = DriveInfo(DriveState.MOVING, true, 0x1FF, 32f); onDriveInfo(forcedInfo!!) }, 4000)
            }
            intent.getStringExtra("demo_hazard")?.let { k -> ui.postDelayed({ io.execute { try { java.net.URL("${serverUrl()}/api/demo/event?kind=$k").readText() } catch (_: Exception) {} } }, 4000) }
            if (intent.getBooleanExtra("demo_nudge", false)) ui.postDelayed({ showNudge(JamReason.SUDDEN) }, 5000)
            if (intent.getBooleanExtra("demo_pick", false)) ui.postDelayed({ neighbors.firstOrNull()?.let { onCarTapped(it.id) } }, 4500)   // the card of a tapped car
            if (intent.getBooleanExtra("demo_ask_car", false)) ui.postDelayed({ neighbors.firstOrNull()?.let { askCar = it; askCarAt = System.currentTimeMillis() } }, 4500)   // as if "이 차에 물어보기" was tapped
            intent.getStringExtra("demo_heard")?.let { said -> ui.postDelayed({ propose(said) }, 5000) }   // the "이렇게 들었어요" card
            intent.getIntExtra("jam_minutes", -1).takeIf { it >= 0 }?.let { m ->
                ui.postDelayed({ jamSince = System.currentTimeMillis() - m * 60_000L; renderState() }, 4600)
            }
        }

        // the navigation only tells us where we are going (same-destination cars get a star)
        navi = NaviClient(this, { tr -> ui.post { onTrip(tr) } }, { st -> android.util.Log.i(TAG, "navi $st") },
            onZone = { z -> io.execute { try { api.hazardDetected(z.kind, "navi-zone") } catch (_: Exception) {} } },
            onTurn = { tn -> ui.post { turn = tn; renderTurn() } }).also { it.start() }
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
        ui.postDelayed(object : Runnable {   // the distance to the next turn counts down: ask again every few seconds
            override fun run() { navi?.refreshTurn(); ui.postDelayed(this, 3_000) }
        }, 3_000)
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

    // while another app is in front there is nobody to show the road to: stop asking the server
    override fun onPause() { polling = false; super.onPause() }

    override fun onResume() {
        super.onResume()
        if (consented && !polling && !isDestroyed) { polling = true; ui.removeCallbacks(pollTick); pollLoop() }
    }

    // ---------------------------------------------------------------- privacy: what is used, and the driver's yes

    private var consented = false
    private var consentView: View? = null

    /** Starts everything that talks to the server or reads the position. Only ever after the driver agreed. */
    private fun startSharing() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 4)
        } else gps.start()
        pushProfile()   // the server learns the name and car other cars will see
        polling = true
        pollLoop()
    }

    /** The first-run notice (and, read-only, from the profile): plain words, one short paragraph per thing that is used. */
    private fun showConsent(readOnly: Boolean) {
        if (consentView != null) return
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(30), dp(22), dp(30), dp(26)) }
        panel.addView(text("개인정보와 권한 안내", 26f, t.ink, 2))
        panel.addView(text("길동무가 무엇을 쓰고, 무엇을 쓰지 않는지 먼저 알려 드려요", 15f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        listOf(
            "위치" to "가까운 차와의 거리를 재고, 신고한 곳을 남기는 데만 써요. 다른 차에는 정확한 위치 대신 대략의 거리만 보여요.",
            "사진" to "위험을 신고할 때 ‘촬영하고 신고’를 누른 경우에만 한 장을 찍어요. 계속 녹화하지 않고, 서버에 ${PHOTO_DAYS}일 보관한 뒤 지워요.",
            "음성" to "말한 소리는 글자로만 바꿔 쓰고 저장하지 않아요. 글자는 내가 확인하고 ‘보내기’를 누르기 전에는 아무에게도 가지 않아요.",
            "이름과 차종" to "목록에서 고른 이름과 차종만 이웃에게 보여요. 차종은 숨길 수 있어요.",
            "신고" to "신고하면 이유가 기록되고, 신고된 이웃은 차단돼요. 신고 내용은 도로 관리 기관에 자동으로 전달되지 않아요.",
        ).forEach { (title, body) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(); setPadding(dp(16), dp(10), dp(16), dp(12)) }
            row.addView(text(title, 17f, t.primary, 2))
            row.addView(text(body, 14f, t.ink), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun close() { consentView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; consentView = null }
        if (readOnly) {
            buttons.addView(actionBtn("닫기", t.accent, Color.WHITE) { close() }, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            buttons.addView(actionBtn("동의하고 시작", t.accent, Color.WHITE) {
                getSharedPreferences("gildongmu", MODE_PRIVATE).edit().putBoolean(CONSENT_KEY, true).apply()
                consented = true
                close()
                startSharing()
            }, LinearLayout.LayoutParams(0, -2, 1.6f))
            buttons.addView(actionBtn("동의하지 않음", t.surface, t.muted) { finish() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        }
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val overlay = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
            isClickable = true   // nothing underneath can be touched until the driver has answered
            addView(android.widget.ScrollView(this@MainActivity).apply { addView(panel) }, FrameLayout.LayoutParams(-1, -1))
        }
        consentView = overlay
        addContentView(overlay, android.view.ViewGroup.LayoutParams(-1, -1))
    }

    override fun onDestroy() {
        polling = false
        ui.removeCallbacksAndMessages(null)
        pollIo.shutdown(); io.shutdown()
        navi?.stop()
        gps.stop()
        monitor.stop()
        voice?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** A card: a calm fill and a thin edge. One corner radius for the whole app, so nothing looks pasted together. */
    private fun glass(color: Int = t.surface, radius: Int = CORNER) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.coerceAtMost(CORNER)).toFloat()
        setStroke(dp(1), t.border)
    }

    private fun fill(color: Int, radius: Int = CORNER) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.coerceAtMost(CORNER)).toFloat()
    }

    /** Cards lift off the page by day (a soft shadow); at night they are told apart by lightness, not shadow. */
    private fun lift(v: View, dp: Int = 2): View = v.apply { if (!t.night) elevation = dp(dp).toFloat() }

    /** The main action: a navy that deepens to the right (a flat block looks like a form, not a car). */
    private fun hero() = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        if (t.night) intArrayOf(Color.parseColor("#1B66D1"), Color.parseColor("#2E8CF0"))
        else intArrayOf(Color.parseColor("#002C5F"), Color.parseColor("#0B4E96")),
    ).apply { cornerRadius = dp(CORNER).toFloat() }

    /** A secondary button: the fill is reserved for the one action that matters on a card. */
    private fun outline() = GradientDrawable().apply {
        setColor(t.surface)
        cornerRadius = dp(CORNER - 2).toFloat()
        setStroke(dp(1), t.border)
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
            if (android.os.Build.VERSION.SDK_INT >= 33) {   // keep a Korean phrase together when the line wraps
                setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
            }
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

    private fun pill(label: String, bgColor: Int, fg: Int = Color.WHITE, sp: Float = 13f) =
        text(label, sp, fg, 2).apply {
            background = fill(bgColor, 6)
            setPadding(dp(8), dp(3), dp(8), dp(3))
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

        // the navigation's essentials get a bar of their own on top: the road below makes room, nothing floats over it
        val sub = Color.parseColor("#B4BFD4")
        turnBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fill(Color.parseColor("#0B1424"), 16)
            setPadding(dp(12), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        val topRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        turnArrow = text("", 30f, Color.WHITE, 2).apply { gravity = Gravity.CENTER; background = fill(t.accent, 12) }
        topRow.addView(turnArrow, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(12) })
        val turnText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        turnMain = text("", 22f, Color.WHITE, 2).apply { maxLines = 1 }
        turnSub = text("", 13f, sub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        turnText.addView(turnMain)
        turnText.addView(turnSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
        topRow.addView(turnText, LinearLayout.LayoutParams(0, -2, 1f))
        turnBar.addView(topRow, LinearLayout.LayoutParams(-1, -2))
        // the arrival gets a line of its own: this panel is narrow, and the time must never be cut off
        etaLine = text("", 15f, Color.WHITE, 2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        turnBar.addView(etaLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(turnBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // the road, with how long we have been stuck floating on top
        val frame = FrameLayout(this)
        road = RoadView(this, emptyList(), t, fRegular, fSemi)
        road.onCarTap = { id -> onCarTapped(id) }
        frame.addView(road, FrameLayout.LayoutParams(-1, -1))
        val state = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fill(if (t.night) Color.parseColor("#D90B1424") else Color.parseColor("#F2FFFFFF"), 16)
            setPadding(dp(14), dp(8), dp(16), dp(8))
        }
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        stateDot = View(this)
        line.addView(stateDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        stateText = text("", 16f, t.ink, 2)
        line.addView(stateText)
        state.addView(line)
        stateSub = text("", 13f, t.muted).apply { visibility = View.GONE }
        state.addView(stateSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        frame.addView(state, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })
        col.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        // how many people are around right now, on the road itself
        val crowd = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = fill(if (t.night) Color.parseColor("#D90B1424") else Color.parseColor("#F2FFFFFF"))
            setPadding(dp(14), dp(8), dp(16), dp(8))
        }
        crowd.addView(View(this).apply { background = oval(t.parked) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        crowdText = text("주변을 찾는 중…", 15f, t.ink, 2)
        crowd.addView(crowdText)
        frame.addView(crowd, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(10)) })
        return col
    }

    private fun buildRightPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // header: who we are, and whether real people are on the road
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // who we are: a round avatar, the nickname and the car; a tap opens the profile
        val profileBtn = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(12), dp(2))
            setOnClickListener {
                if (profileOpen) { profileOpen = false; renderAnswers() }
                else if (!profileEditable()) flash("프로필은 정차 중에만 바꿀 수 있어요", "프로필")   // not while moving
                else { profileOpen = true; pickedCar = null; pendingReport = null; renderAnswers() }
            }
            press(this)
        }
        profileAvatar = text("", 18f, Color.WHITE, 2).apply { gravity = Gravity.CENTER }
        profileBtn.addView(profileAvatar, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) })
        val profileText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        profileName = text("", 20f, t.primary, 2).apply { maxLines = 1 }
        profileSub = text("", 12f, t.muted).apply { maxLines = 1 }
        profileText.addView(profileName)
        profileText.addView(profileSub)
        profileBtn.addView(profileText)
        head.addView(profileBtn)
        refreshProfile()
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // 라이트 / 다크: the driver's choice. (On the very first run it starts from the vehicle's own day/night.)
        val themeSwitch = LinearLayout(this).apply {
            background = glass(radius = 10)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        listOf("day" to "라이트", "night" to "다크").forEach { (value, label) ->
            val on = themePref == value
            themeSwitch.addView(text(label, 13f, if (on) Color.WHITE else t.muted, 2).apply {
                gravity = Gravity.CENTER
                background = if (on) fill(t.accent, 8) else null
                setPadding(dp(11), dp(6), dp(11), dp(6))
                setOnClickListener {
                    if (!on) {
                        getSharedPreferences("gildongmu", MODE_PRIVATE).edit().putString("theme", value).apply()
                        recreate()
                    }
                }
            })
        }
        head.addView(themeSwitch, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        pointsChip = text("", 13f, t.primary, 2).apply {
            background = glass(radius = 10)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { showStanding() }
        }
        head.addView(pointsChip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        // only ever shown when something is wrong: the connection is gone
        liveChip = text("", 13f, t.waiting, 2).apply {
            background = glass(radius = 16)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            visibility = View.GONE
        }
        head.addView(liveChip)
        col.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // the attention slot: the car's offer to ask, or "did I hear you right". It only exists while it is needed.
        attentionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass()
            setPadding(dp(18), dp(14), dp(18), dp(14))
            visibility = View.GONE
        }
        lift(attentionCard, 4)
        col.addView(attentionCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // The notification slot: what neighbours reported about the road ahead. A fixed height, so when a report
        // arrives or its prompt expires nothing the driver is about to press moves; the controls sit below it.
        bannerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        lift(bannerCard)
        col.addView(bannerCard, LinearLayout.LayoutParams(-1, dp(BANNER_DP)).apply { bottomMargin = dp(12) })
        renderBoard(board, force = true)

        // one tap to tell the road what you see
        stoppedArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        whyButton = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = hero()
            setPadding(dp(20), dp(22), dp(24), dp(22))
            setOnClickListener { if (info.canSend && !asking) askWhy() }
        }
        lift(whyButton, 4)
        press(whyButton)
        val micBox = FrameLayout(this).apply {
            background = fill(Color.parseColor("#26FFFFFF"), 10)
            addView(icon(R.drawable.ic_mic, 30, Color.WHITE), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }
        whyButton.addView(micBox, LinearLayout.LayoutParams(dp(60), dp(60)).apply { rightMargin = dp(20) })
        val whyText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        whyTitle = text("왜 막혀요?", 30f, Color.WHITE, 2)
        whySub = text("주변 이웃에게 지금 물어보기", 16f, Color.parseColor("#BFD0EA"))
        whyText.addView(whyTitle)
        whyText.addView(whySub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        whyButton.addView(whyText, LinearLayout.LayoutParams(0, -2, 1f))
        whyButton.addView(text("›", 40f, Color.parseColor("#BFD0EA"), 1))
        ctaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctaRow.addView(whyButton, LinearLayout.LayoutParams(0, -2, 1f))
        stoppedArea.addView(ctaRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        stoppedArea.addView(text("본 대로 알려 주세요", 13f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6); leftMargin = dp(4) })
        tilesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stoppedArea.addView(tilesRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        col.addView(stoppedArea, LinearLayout.LayoutParams(-1, -2))

        // the answers
        answersCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass()
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        lift(answersCard)
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

        // the mic sits beside the ask button: say an answer, a report, or a message to the car you picked.
        // (When the vehicle forbids touching, the big voice screen below takes its place.)
        voiceBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = outline()
            setOnClickListener { startVoice() }
        }
        lift(voiceBar)
        press(voiceBar)
        voiceBar.addView(icon(R.drawable.ic_mic, 24, t.accent), LinearLayout.LayoutParams(dp(24), dp(24)))
        voiceWave = WaveView(this, t.accent)
        voiceBar.addView(voiceWave, LinearLayout.LayoutParams(dp(30), dp(8)).apply { topMargin = dp(5) })
        ctaRow.addView(voiceBar, LinearLayout.LayoutParams(dp(92), -1).apply { leftMargin = dp(10) })

        voiceHero = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = hero()
            setPadding(dp(20), dp(14), dp(22), dp(14))
            visibility = View.GONE
            setOnClickListener { startVoice() }
        }
        press(voiceHero)
        voiceHero.addView(badge(R.drawable.ic_mic, t.accent, 36), LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(18) })
        val vcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vcol.addView(text("말로 한마디 하세요", 22f, Color.WHITE, 2))
        vcol.addView(text("“앞에 사슴이 있어요”  “끼워 줘서 감사합니다”", 14f, Color.parseColor("#C9D6EA")),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
        vcol.addView(text("지금은 차량이 터치를 막고 있어요 · 질문은 읽어 드려요, 말로 답하세요", 13f, Color.parseColor("#9FB2D0")),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        voiceHero.addView(vcol)
        col.addView(voiceHero, LinearLayout.LayoutParams(-1, -2))

        updateTabs()
        renderTiles()
        renderAnswers()
        return col
    }

    // ---------------------------------------------------------------- tabs and tiles

    /** Picking a car on the road is for a 1:1 message, and not while moving (aiming at a small target). */
    private fun updateTabs() {
        if (::whyButton.isInitialized) whyButton.alpha = if (info.canSend) 1f else 0.45f
    }

    private fun renderTiles() {
        tilesRow.removeAllViews()
        tileViews.clear()
        informTiles.forEachIndexed { i, tile ->
            val ic = icon(tile.icon, 22, tile.color)
            val label = text(tile.label, 13f, t.ink, 2).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = outline()
                setPadding(dp(4), dp(12), dp(4), dp(12))
                setOnClickListener { onTile(tile) }
            }
            lift(view)
            press(view)
            view.addView(ic, LinearLayout.LayoutParams(dp(22), dp(22)))
            view.addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            tileViews += Triple(view, ic, label)
            tilesRow.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        applyEnabled()
    }

    private fun applyEnabled() {
        val enabled = info.canSend
        tileViews.forEach { (tile, ic, label) ->
            tile.isEnabled = enabled
            ic.alpha = if (enabled) 1f else 0.55f
            label.setTextColor(if (enabled) t.ink else t.faint)
        }
    }

    private fun onTile(tile: Tile) {
        if (!info.canSend) return
        if (tile.code in HAZARD_TILES) reportNow(tile) else inform(tile)
    }

    /** 낙하물 / 로드킬: the tap only opens the question "촬영하고 신고할까요?"; nothing is sent until the driver confirms. */
    private fun reportNow(tile: Tile) = showHazard(HazardProposal(0, tile.code, tile.label, 0), byDriver = true)

    // ---------------------------------------------------------------- data in

    private fun onTrip(tr: Trip) {
        if (tr.destination.isBlank()) return
        trip = tr
        destName = tr.destination
        io.execute { try { api.setDestination(tr.destination) } catch (_: Exception) {} }
        jam.onRemaining(System.currentTimeMillis(), tr.remainMin)?.let { showNudge(it) }
        renderState()
    }

    /** Neighbours + incoming messages every 1.5 s, what is ahead every ~4.5 s. */
    private fun pollLoop() {
        if (!polling) return
        val n = tick++
        if (pollBusy) { ui.postDelayed(pollTick, 500); return }   // the last round is still running: don't pile up
        pollBusy = true
        pollIo.execute {
            try {
                currentPlace()?.let {   // tell the server where we are (the navigation while it is current, else the platform GPS)
                    try { api.postPosition(it) } catch (_: Exception) {}
                    if (System.currentTimeMillis() - lastPosLog > 30_000L) {
                        lastPosLog = System.currentTimeMillis()
                        android.util.Log.i(TAG, "position sent: ${"%.5f".format(it.lat)}, ${"%.5f".format(it.lon)} from ${if (navi?.fresh() != null) "navigation" else "gps"}")
                    }
                }
                val near = api.nearby()
                val inbound = api.inbox(lastInboxId)
                val shared = if (n % 2 == 0 || inbound.isNotEmpty()) api.board() else null
                val asked = if (n % 2 == 1 || inbound.isNotEmpty()) api.questions() else null
                val said = if (n % 2 == 0 || inbound.isNotEmpty()) api.roadMessages() else null
                val seen = if (n % 2 == 1) api.hazardProposal() else null
                ui.post {
                    setOnline(true)
                    onNeighbors(near)   // an empty list is real too: nobody around
                    if (!inboxPrimed) {  // skip history from before the app was opened
                        inbound.maxByOrNull { it.id }?.let { lastInboxId = it.id }
                        inboxPrimed = true
                    } else inbound.forEach { showInbound(it) }
                    shared?.let { renderBoard(it) }
                    asked?.let { onQuestions(it) }
                    said?.let { onRoadMessages(it) }
                    seen?.let { showHazard(it) }
                }
            } catch (e: Exception) {
                ui.post { setOnline(false) }   // the screen keeps working with what it has, and says so
            } finally { pollBusy = false }
        }
        ui.postDelayed(pollTick, if (online) 2000 else 4000)   // slower while offline, back to normal as soon as it answers
    }

    private fun setOnline(ok: Boolean) {
        if (online == ok) return
        online = ok
        renderHeader()
    }

    private fun onNeighbors(near: List<Neighbor>) {
        if (neighborsKnown && near == neighbors) return   // nothing changed: no redraw, no layout
        neighborsKnown = true
        neighbors = near
        road.neighbors = near
        crowdText.text = if (near.isEmpty()) "주변에 앱 쓰는 이웃이 없어요"
            else "반경 300m · 앱 사용 ${near.size}대" + if (near.size > ROAD_CARS) "  · 화면 밖 +${near.size - ROAD_CARS}" else ""
        renderHeader()
        updateTabs()
        val gone = { id: String -> near.none { it.id == id } }
        if ((pickedCar != null && gone(pickedCar!!.id)) || (pendingReport?.carId?.let(gone) == true)) {   // that car drove out of range
            pickedCar = null; pendingReport = null
            renderAnswers()
        }
    }

    private fun renderHeader() {
        if (online) { liveChip.visibility = View.GONE; return }
        liveChip.text = "● 연결 끊김"
        liveChip.visibility = View.VISIBLE

    }

    /**
     * The road notice board: what neighbours reported ahead. An urgent report is red and read out once.
     * Another car's report gets a three-tap prompt (맞아요 / 고마워요 / 덕분에 잘 지나갔어요) that goes away
     * by itself after [PROMPT_MS]; ignoring it costs nobody anything.
     */
    private fun renderBoard(view: BoardView, force: Boolean = false) {
        if (!force && view == board) return
        board = view
        pointsChip.text = "${view.me.grade}  ${view.me.points}점"
        bannerCard.removeAllViews()
        if (view.posts.isEmpty()) {   // nothing reported: a calm status, not an empty box
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(icon(R.drawable.ic_check, 22, t.parked), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text("앞쪽 특이사항 없음", 17f, t.ink, 2))
            col.addView(text("사고나 공사가 제보되면 여기에 알려 드려요", 13f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            row.addView(col)
            bannerCard.addView(row)
            applyFocus(); return
        }
        var promptShown = false
        val promptPost = view.posts.take(2).firstOrNull { !it.mine && it.myVote == null && it.id !in skipped }
        (if (promptPost != null) listOf(promptPost) else view.posts.take(2)).forEach { post ->   // two lines at most: the road must stay the main thing
            if (post.urgent && !post.mine && announced.add(post.id)) speak("${post.title}. 조심하세요")
            val tone = if (post.urgent) t.moving else t.waiting
            val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(View(this).apply { background = fill(tone, 2) }, LinearLayout.LayoutParams(dp(4), -1).apply { rightMargin = dp(12) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(icon(if (post.urgent) R.drawable.ic_warning else R.drawable.ic_info, 18, tone))
            head.addView(text(post.title, if (post.urgent) 17f else 15f, t.ink, 2).apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            val seen = post.votes.values.sum()
            head.addView(text("${if (post.mine) "내 제보" else post.by} · ${post.age}" + if (seen > 0) " · ${seen}명 확인" else "", 12f, t.muted, 1))
            // the prompt only runs for a few seconds; a report from a neighbour keeps a "확인하기" afterwards, one tap brings it back
            if (!post.mine && post.myVote == null && post.id in skipped) {
                head.addView(text("확인하기", 12f, Color.WHITE, 2).apply {
                    background = fill(t.accent, 8)
                    setPadding(dp(9), dp(4), dp(9), dp(4))
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                box.setOnClickListener { if (info.canSend) { skipped -= post.id; promptSeen.remove(post.id); renderBoard(board, force = true) } }
                press(box)
            }
            col.addView(head)
            val wantsPrompt = !post.mine && post.myVote == null && post.id !in skipped && !promptShown
            if (wantsPrompt) {
                promptShown = true
                col.addView(reactionRow(post), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                if (promptSeen.add(post.id)) {   // first time on screen: start the countdown
                    ui.postDelayed({ skipped += post.id; renderBoard(board, force = true) }, PROMPT_MS)
                }
            }
            box.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            // with a prompt on screen, only that report fits; otherwise two lines
            if (!promptShown || wantsPrompt) bannerCard.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (bannerCard.childCount > 0) dp(8) else 0 })
        }
        applyFocus()
    }

    /** The three taps. They only count while the vehicle allows touching; the prompt moves on by itself either way. */
    private fun reactionRow(post: BoardPost): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val choices = listOf("confirm" to "맞아요", "thanks" to "고마워요", "passed" to "덕분에 잘 지나갔어요")
        choices.forEachIndexed { i, (code, label) ->
            val b = text(label, 13f, if (i == 2) Color.WHITE else t.ink, 2).apply {
                gravity = Gravity.CENTER
                background = if (i == 2) fill(t.accent, 12) else outline()
                setPadding(dp(6), dp(11), dp(6), dp(11))
                alpha = if (info.canSend) 1f else 0.45f
                setOnClickListener { react(post, code) }
                press(this)
            }
            row.addView(b, LinearLayout.LayoutParams(0, -2, if (i == 2) 1.7f else 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        return row
    }

    private fun react(post: BoardPost, what: String) {
        if (!info.canSend) return
        skipped += post.id
        io.execute {
            try { api.react(post.id, what) } catch (_: Exception) {}
            val fresh = try { api.board() } catch (_: Exception) { null }
            ui.post { fresh?.let { renderBoard(it, force = true) } ?: renderBoard(board, force = true) }
        }
    }

    /**
     * What is on screen. While a question is live, the answers matter most: the board, the ask row and the tiles
     * step aside. When the vehicle forbids touching, the ask row and tiles are replaced by the big voice screen.
     */
    private fun applyFocus() {
        if (!::stoppedArea.isInitialized || !::bannerCard.isInitialized) return
        // Answers never take over the screen: they appear in the card at the bottom, and the controls stay where they are.
        // The tall cards (my profile, the report reasons) take the board's place so that nothing is pushed off the screen.
        // the pickup list and the order card need the room: the report banner steps aside while they are open
        bannerCard.visibility = if (profileOpen || pendingReport != null || order != null || pickPlaces.isNotEmpty()) View.GONE else View.VISIBLE
        stoppedArea.visibility = if (info.canSend && !profileOpen) View.VISIBLE else View.GONE   // editing the profile needs the room
    }

    /**
     * The car thinks it sees something on the road (roadkill, falling rocks, a pothole). It never reports by itself:
     * it asks, and one tap files the report and returns a ticket number. Unanswered, it goes away after [HAZARD_MS].
     */
    private fun showHazard(p: HazardProposal, byDriver: Boolean = false) {
        if (hazard?.id == p.id || heardText != null) return
        hazard = p
        hazardByDriver = byDriver
        hazardPhoto = null
        hazardKey = java.util.UUID.randomUUID().toString()
        hazardSeenAt = System.currentTimeMillis()
        hazardFailed = false
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (camOk && hazardCamera.available()) {
            hazardCamera.capture { bytes -> ui.post { if (hazard?.id == p.id) hazardPhoto = bytes } }   // taken now, while it is in view
        } else if (!camOk) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 3)   // asked once; this report goes without a photo
        }
        val token = ++hazardToken
        val line = if (byDriver) "${p.title}, 촬영하고 신고할까요?" else "앞에 ${p.title}이 감지됐어요. 신고할까요?"
        if (info.canSend) { if (!byDriver) speak(line) } else speakThen("$line 네, 라고 말하면 신고할게요", "nudge")
        renderAnswers()
        ui.postDelayed({ if (token == hazardToken && hazard?.id == p.id) { hazard = null; renderAnswers() } }, HAZARD_MS)
    }

    private fun answerHazard(said: String) {
        val p = hazard ?: return
        val w = said.replace(" ", "")
        if (listOf("네", "응", "신고", "해줘", "그래").any { it in w }) fileHazard(p) else declineHazard(p)
    }

    private fun declineHazard(p: HazardProposal) {
        hazard = null; hazardToken++; hazardPhoto = null   // not reported: the frame is thrown away
        io.execute { try { api.hazardDecline(p.id) } catch (_: Exception) {} }
        renderAnswers()
    }

    private fun fileHazard(p: HazardProposal) {
        hazard = null; hazardToken++
        val photo = hazardPhoto; hazardPhoto = null
        renderAnswers()
        val key = hazardKey
        val seenAt = hazardSeenAt
        io.execute {
            val ticket = try { api.hazardReport(p.kind, p.id, currentPlace(), photo, key, seenAt) } catch (_: Exception) { null }
            val shared = try { api.board() } catch (_: Exception) { null }
            ui.post {
                shared?.let { renderBoard(it, force = true) }
                if (ticket != null) {
                    sayBubble("앞에 ${p.title}")
                    speak("신고가 접수됐어요")
                    flash("신고가 접수됐어요 · 접수번호 ${ticket.ticket}" + (if (ticket.located) " · 위치" else " · 위치 없음") + (if (ticket.photo) " · 사진" else ""), "신고")
                } else {   // it may or may not have arrived: the card comes back, and pressing again sends the same id (never a second report)
                    hazard = p; hazardPhoto = photo; hazardFailed = true
                    val token = ++hazardToken
                    ui.postDelayed({ if (token == hazardToken && hazard?.id == p.id) { hazard = null; hazardFailed = false; renderAnswers() } }, HAZARD_MS)
                    renderAnswers()
                }
            }
        }
    }

    /** The offer card: what the car sees, and two big choices. One tap reports. */
    private fun renderHazard(p: HazardProposal) {
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(badge(R.drawable.ic_warning, t.moving, 20), LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(if (hazardByDriver) "${p.title}, 촬영하고 신고할까요?" else "앞에 ${p.title}이 감지됐어요", 22f, t.ink, 2))
        col.addView(text("위치${if (hazardPhoto != null) "·사진" else ""}과 함께 신고돼요 · 근처 이웃에게도 알려요", 14f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        head.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        attentionCard.addView(head)
        if (hazardFailed) {
            attentionCard.addView(text("전송하지 못했어요 · 다시 누르면 같은 신고로 보내요", 14f, t.moving, 2),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        if (!info.canSend) {
            attentionCard.addView(text("“네”라고 말하면 신고해요", 14f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            return
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun btn(label: String, primary: Boolean, w: Float, action: () -> Unit) {
            row.addView(text(label, 19f, if (primary) Color.WHITE else t.ink, 2).apply {
                gravity = Gravity.CENTER
                background = if (primary) fill(t.moving, 14) else outline()
                setPadding(dp(8), dp(16), dp(8), dp(16))
                setOnClickListener { action() }
                press(this)
            }, LinearLayout.LayoutParams(0, -2, w).apply { if (row.childCount > 0) leftMargin = dp(10) })
        }
        btn(if (hazardByDriver) "촬영하고 신고" else "신고하기", true, 1.6f) { fileHazard(p) }
        btn(if (hazardByDriver) "취소" else "괜찮아요", false, 1f) { declineHazard(p) }
        attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    /** Feeds the detector with the current speed. Called on every reading and every few seconds. */
    private fun evaluateJam() {
        val speed = info.speedKmh ?: return
        jam.onSpeed(System.currentTimeMillis(), speed, info.state == DriveState.PARKED)?.let { showNudge(it) }
    }

    /**
     * The car offers the question itself, rarely: "차가 갑자기 느려졌어요. 앞에 왜 막히는지 물어볼까요?"
     * It goes away by itself after [NUDGE_MS]; ignoring it costs nothing.
     */
    private fun showNudge(reason: JamReason) {
        if (nudge != null || heardText != null || !online || asking) return   // already asking: do not offer to ask
        jam.shown(System.currentTimeMillis())
        nudge = reason
        val token = ++nudgeToken
        val line = "${reason.sentence}. 앞에 왜 막히는지 물어볼까요?"
        if (info.canSend) speak(line) else speakThen("$line 네, 라고 말하면 물어볼게요", "nudge")
        renderAnswers()
        ui.postDelayed({ if (token == nudgeToken) dismissNudge() }, NUDGE_MS)
    }

    private fun dismissNudge() {
        if (nudge == null) return
        nudge = null; nudgeToken++
        renderAnswers()
    }

    private fun answerNudge(said: String) {
        val w = said.replace(" ", "")
        if (listOf("네", "응", "물어", "좋아", "그래", "해줘").any { it in w }) { dismissNudge(); askWhy() } else dismissNudge()
    }

    /** The core question, one tap: why is the road stuck? A bubble goes over our car until somebody answers. */
    private fun askWhy() {
        newQuestion("왜 막히는지 물어봤어요")
        answeredCars.clear()
        road.answers = emptyMap()
        sayBubble("왜 막혀요?")
        startAsking()
        val speed = info.speedKmh ?: 0f
        io.execute {
            val res = try { api.send("ASK_ACCIDENT", null, speed, gearCode(), info.fullyRestricted) } catch (e: Exception) { null }
            deliver(res, "ASK_ACCIDENT")
        }
    }

    /** The offer card: why the car is asking, and two big choices. */
    private fun renderNudge(reason: JamReason) {
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        attentionCard.addView(text(reason.sentence, 13f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        attentionCard.addView(text("앞에 왜 막히는지 물어볼까요?", 22f, t.ink, 2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        if (!info.canSend) {
            attentionCard.addView(text("“네”라고 말하면 이웃에게 물어봐요", 13f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            return
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun btn(label: String, bg: Int, fg: Int, w: Float, action: () -> Unit) {
            row.addView(text(label, 18f, if (bg == t.accent) fg else t.ink, 2).apply {
                gravity = Gravity.CENTER
                background = if (bg == t.accent) fill(bg, 12) else outline()
                setPadding(dp(8), dp(15), dp(8), dp(15))
                setOnClickListener { action() }
                press(this)
            }, LinearLayout.LayoutParams(0, -2, w).apply { if (row.childCount > 0) leftMargin = dp(8) })
        }
        btn("물어보기", t.accent, Color.WHITE, 1.6f) { dismissNudge(); askWhy() }
        btn("괜찮아요", t.surface, t.muted, 1f) { dismissNudge() }
        attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    /** Questions people asked us or everyone: shown on the card; one addressed to us is read out once. */
    private fun onQuestions(items: List<QuestionItem>) {
        if (items == questions) return
        questions = items
        items.firstOrNull { !it.answered && announcedQ.add(it.id) }?.let {
            speak("${it.from}님이 물어봐요. ${it.text}. 답하려면 말해 주세요")
        }
        updateTabs()
        renderAnswers()
    }

    /** What others said out loud: shown in the list, and the newest one is read out once (eyes stay on the road). */
    private fun onRoadMessages(items: List<RoadMessage>) {
        if (items == roadMessages) return
        roadMessages = items
        items.firstOrNull { announcedMsg.add(it.id) }?.let { speak("${it.from}님이 말해요. ${it.text}") }
        renderAnswers()
    }

    private fun reportQuestion(q: QuestionItem, reason: String) {
        questions = questions.filter { it.id != q.id }
        flash("신고했어요 · 이유: ${reasonLabel(reason)} · ${q.from}님의 질문은 더 이상 보이지 않아요", "신고")
        io.execute { try { api.reportQuestion(q.id, reason) } catch (_: Exception) {} }
    }

    private fun reportMessage(m: RoadMessage, reason: String) {
        roadMessages = roadMessages.filter { it.id != m.id }
        flash("신고했어요 · 이유: ${reasonLabel(reason)} · 이 이웃의 말은 더 이상 보이지 않아요", "신고")
        io.execute { try { api.reportMessage(m.id, reason) } catch (_: Exception) {} }
    }

    private fun answerQuick(q: QuestionItem, reply: String) {
        if (!info.canSend) return
        io.execute {
            try { api.answerQuestion(q.id, reply) } catch (_: Exception) {}
            val fresh = try { api.questions() } catch (_: Exception) { null }
            val shared = try { api.board() } catch (_: Exception) { null }
            ui.post { fresh?.let { onQuestions(it) }; shared?.let { renderBoard(it, force = true) }; flash("답을 보냈어요 · 고마워요") }
        }
    }

    // ---------------------------------------------------------------- pickup on the way (sample data)

    /** A spoken sentence: a pickup request if it names a kind of place, otherwise the normal "this is what I heard" flow. */
    private fun routeSpoken(spoken: String) {
        val type = Errands.matchType(spoken)
        if (type != null) openList(type) else propose(spoken)
    }

    private fun touchOk() = info.state != DriveState.MOVING

    /** The idle slot: one chip per kind of place. Touch only when stopped; while moving, the driver asks aloud. */
    private fun renderErrands() {
        if (order != null || pickPlaces.isNotEmpty()) return
        answersList.addView(text(if (touchOk()) "가는 길에 받을 수 있는 곳을 골라 보세요" else "말로 물어보세요: “앞에 드라이브스루 뭐 있어?”", 13f, t.muted, 1),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        val row = LinearLayout(this)
        Errands.TYPES.forEach { type ->
            row.addView(text(type, 13f, t.ink, 2).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                background = outline()
                setPadding(dp(2), dp(14), dp(2), dp(14))
                alpha = if (touchOk()) 1f else 0.5f
                if (touchOk()) { setOnClickListener { openList(type) }; press(this) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (row.childCount > 0) leftMargin = dp(6) })
        }
        answersList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun openList(type: String) {
        val list = Errands.near(type)
        pickedCar = null; profileOpen = false; pendingReport = null; order = null
        if (list.isEmpty()) { pickPlaces = emptyList(); flash("가는 길에 ${type}이 없어요", "가는 길 픽업"); return }
        pickType = type; pickPlaces = list
        if (!info.canSend || !touchOk())
            speakThen("${type} ${list.size}곳 있어요. " + list.joinToString(". ") { "${it.name} ${"%.1f".format(it.km)}킬로" } + ". 어디로 할까요?", "confirm")
        renderAnswers()
    }

    /** Candidates, big: name on the left, distance / arrival / waiting on the right. */
    private fun renderPickList() {
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        attentionCard.addView(text("$pickType · 가는 길 ${pickPlaces.size}곳 (샘플)", 13f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        val canTap = info.canSend && touchOk()
        pickPlaces.forEach { p ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = outline()
                setPadding(dp(12), dp(11), dp(12), dp(11))
                if (canTap) { setOnClickListener { openOrder(p) }; press(this) }
            }
            row.addView(text(p.name, 16f, t.ink, 2).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text("${"%.1f".format(p.km)}km · 도착 ${p.etaMin()}분 · 대기 ${p.waitMin}분", 12f, t.muted))
            attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        if (canTap) attentionCard.addView(actionBtn("닫기", t.surface, t.muted, 15f) { closePick() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        else attentionCard.addView(text("가게 이름이나 “첫 번째”라고 말해 주세요", 13f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
    }

    private fun closePick() { pickPlaces = emptyList(); renderAnswers() }

    private fun answerPick(said: String) {
        val w = said.replace(" ", "")
        if (listOf("취소", "그만", "됐어", "닫").any { it in w }) { closePick(); return }
        val p = Errands.pick(pickPlaces, said)
        if (p != null) openOrder(p) else speakThen("가게 이름이나 첫 번째, 두 번째라고 말해 주세요", "confirm")
    }

    private fun openOrder(p: Shop) {
        order = p; pickPlaces = emptyList()
        if (!info.canSend || !touchOk())
            speakThen("${p.name} 인기 메뉴는 ${p.item}, ${p.priceText()}. ${p.etaMin()}분 뒤 도착이라 맞춰 준비해요. 결제할까요?", "confirm")
        renderAnswers()
    }

    /** One look: what, where, how much, when it will be ready. The payment is a mock: nothing is charged. */
    private fun renderOrder(p: Shop) {
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        attentionCard.addView(text("샘플 매장 · 시험용이라 실제 결제는 되지 않아요", 13f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        attentionCard.addView(text("${p.name} · ${p.item}", 20f, t.ink, 2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        val cook = p.cookInMin()
        attentionCard.addView(text("${p.priceText()} · 도착 ${p.etaMin()}분 뒤 · " + (if (cook > 0) "${cook}분 뒤 조리 시작" else "바로 조리 시작"), 15f, t.muted, 1),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        if (!info.canSend || !touchOk()) {
            attentionCard.addView(text("“결제”라고 말하면 주문해요", 13f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            return
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(actionBtn("결제하기", t.accent, Color.WHITE, 18f) { payOrder() }, LinearLayout.LayoutParams(0, -2, 1.6f))
        row.addView(actionBtn("취소", t.surface, t.muted, 18f) { cancelOrder() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun payOrder() {
        val p = order ?: return
        order = null
        val no = Errands.receiptNo()
        val cook = p.cookInMin()
        speak("주문했어요. 접수번호 $no")
        flash("${p.name} · ${p.item} · ${p.priceText()} · 접수번호 $no · " + (if (cook > 0) "${cook}분 뒤 조리 시작 예정" else "바로 조리 시작") + " · 시험용, 실제 결제 없음", "주문 접수", 8_000)
    }

    private fun cancelOrder() {
        order = null
        flash("주문을 취소했어요", "가는 길 픽업")
    }

    private fun answerOrder(said: String) {
        val w = said.replace(" ", "")
        when {
            listOf("취소", "그만", "아니", "됐어").any { it in w } -> cancelOrder()
            listOf("결제", "주문", "네", "응", "좋아", "그래").any { it in w } -> payOrder()
            else -> speakThen("결제 또는 취소라고 말해 주세요", "confirm")
        }
    }

    /** The question card: who asked, the sentence, and the quickest ways to answer. */
    private fun renderQuestions() {
        roadMessages.take(2).forEach { m ->   // what neighbours said out loud: one line each, with a way to report
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(icon(R.drawable.ic_chat, 20, t.accent), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text("${m.from} · ${m.age}", 13f, t.muted, 1))
            col.addView(text(m.text, 17f, t.ink, 2).apply { maxLines = 2 }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text("신고", 13f, t.muted, 1).apply {
                setPadding(dp(14), dp(10), dp(4), dp(10))
                setOnClickListener { startReport(m.from) { reason -> reportMessage(m, reason) } }
            })
            answersList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val shown = questions.take(1)   // one question, with its buttons in full; the rest is a count, not a clipped second card
        if (shown.isEmpty()) {
            if (roadMessages.isEmpty()) renderErrands()
            return
        }
        shown.forEach { q ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(text("${q.from} · ${q.age}" + if (q.direct) " · 나에게만" else "", 13f, t.muted, 1), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(text("신고", 13f, t.muted, 1).apply {   // a strange question: report whoever asked it
                setPadding(dp(14), dp(6), dp(2), dp(6))
                alpha = if (info.canSend) 1f else 0.45f
                setOnClickListener { startReport(q.from) { reason -> reportQuestion(q, reason) } }
            })
            box.addView(top)
            box.addView(text(q.text, 18f, t.ink, 2).apply { maxLines = 2 }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            if (q.answers.isNotEmpty()) {   // a public question shows every answer to everyone
                box.addView(text(q.answers.joinToString("  ·  ") { (if (it.mine) "나: " else "${it.by}: ") + it.text }, 13f, t.accent, 1).apply { maxLines = 2 },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            }
            if (!q.answered && q.open) {
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                fun chip(label: String, bg: Int, fg: Int, w: Float, action: () -> Unit) {
                    row.addView(text(label, 14f, if (bg == t.accent) fg else t.ink, 2).apply {
                        gravity = Gravity.CENTER
                        background = if (bg == t.accent) fill(bg, 12) else outline()
                        setPadding(dp(4), dp(10), dp(4), dp(10))
                        alpha = if (info.canSend) 1f else 0.45f
                        setOnClickListener { action() }
                        press(this)
                    }, LinearLayout.LayoutParams(0, -2, w).apply { if (row.childCount > 0) leftMargin = dp(6) })
                }
                chip("네", t.accentSoft, t.accent, 0.8f) { answerQuick(q, "yes") }
                chip("아니요", t.accentSoft, t.accent, 1f) { answerQuick(q, "no") }
                chip("모르겠어요", t.accentSoft, t.accent, 1.2f) { answerQuick(q, "unknown") }
                chip("말로 답하기", t.accent, Color.WHITE, 1.5f) { answerTarget = q.id; startVoice() }
                box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            } else if (q.answered) {
                box.addView(text("답했어요 ✓", 13f, t.parked, 2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            }
            answersList.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        }
        if (questions.size > shown.size) {
            answersList.addView(text("다른 질문 ${questions.size - shown.size}건", 13f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
    }

    // ---------------------------------------------------------------- driving state

    private fun onDriveInfo(reported: DriveInfo) {
        val newInfo = forcedInfo ?: reported
        val becameMoving = newInfo.state == DriveState.MOVING && info.state != DriveState.MOVING
        info = newInfo
        if (profileOpen && !profileEditable()) profileOpen = false   // the car set off while the profile was open: close it
        trackJam()
        evaluateJam()
        render()
        if (becameMoving && !online.not()) {
            speak(if (info.canSend) "달리는 중이에요. 안전하게 운전하세요" else "달리는 중이에요. 질문은 말로 해 주세요. 답은 음성으로 들려드려요")
        }
    }

    /** "Stuck" = not parked and crawling (20 km/h or less). Resets after 15 s of real driving. */
    private fun trackJam() {
        val now = System.currentTimeMillis()
        val speed = info.speedKmh ?: 0f
        when {
            info.state == DriveState.PARKED -> { jamSince = null; fastSince = null }
            speed <= JAM_KMH -> { if (jamSince == null) jamSince = now; fastSince = null }
            else -> {
                if (fastSince == null) fastSince = now
                if (now - fastSince!! > 15_000) { jamSince = null }
            }
        }
    }

    private fun render() {
        renderState()
        val moving = info.state == DriveState.MOVING
        road.moving = moving
        road.speedKmh = if (info.state == DriveState.PARKED) 0f else (info.speedKmh ?: 0f).coerceAtLeast(if (jamSince != null) 4f else 0f)

        // only if the vehicle forbids touching altogether do the buttons give way to the big voice screen
        val locked = !info.canSend
        applyFocus()
        voiceHero.visibility = if (locked) View.VISIBLE else View.GONE
        updateTabs()
        applyEnabled()
        renderAnswers()
    }

    private fun renderState() {
        val minutes = jamSince?.let { ((System.currentTimeMillis() - it) / 60_000L).toInt() } ?: 0
        val color = when (info.state) {
            DriveState.PARKED -> t.parked
            DriveState.IDLING -> t.waiting
            DriveState.MOVING -> if (jamSince != null) t.waiting else t.moving
            DriveState.UNKNOWN -> t.muted
        }
        stateDot.background = oval(color)
        stateText.text = when {
            info.state == DriveState.PARKED -> "주차 중"
            jamSince != null && minutes >= 1 -> "정체 ${minutes}분째"
            jamSince != null -> "정체 중"
            info.state == DriveState.UNKNOWN -> "차량 연결 전"
            else -> "주행 중"
        }
        stateSub.visibility = View.GONE   // the route has its own bar above the road now
        renderTurn()
    }

    /** The next turn and the arrival, on a bar of their own above the road: essential, so never covered by anything. */
    private fun renderTurn() {
        if (!::turnBar.isInitialized) return
        val tr = trip
        val tn = turn
        turnBar.visibility = if (tr != null || tn != null) View.VISIBLE else View.GONE
        if (tn != null) {
            turnArrow.visibility = View.VISIBLE
            turnArrow.text = tn.arrow
            turnMain.text = "${tn.word} · ${Turns.distance(tn.distanceM)}"
            turnSub.text = tn.where.ifBlank { tr?.destination ?: "" }
        } else {
            turnArrow.visibility = View.GONE
            turnMain.text = tr?.destination ?: ""
            turnSub.text = "경로 안내 중"
        }
        etaLine.visibility = if (tr != null) View.VISIBLE else View.GONE
        if (tr != null) {
            val eta = SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(System.currentTimeMillis() + tr.remainMin * 60_000L))
            val h = tr.remainMin / 60
            val m = tr.remainMin % 60
            etaLine.text = "$eta 도착  ·  " + (if (h > 0) "${h}시간 " else "") + "${m}분  ·  ${"%.1f".format(tr.remainKm)}km"
        }
    }

    // ---------------------------------------------------------------- taking part

    private fun gearCode() = if (info.state == DriveState.PARKED) "P" else "D"

    /**
     * The recogniser's words are shown (and read back) first. Nothing is sent until the driver confirms,
     * because a misheard "안녕하세요" must never reach a neighbour as something else.
     */
    private fun propose(spoken: String) {
        heardText = spoken
        stopAsking()
        answers.clear()
        note = null
        answersTitle = "이렇게 들었어요"
        if (!info.canSend) {   // the vehicle forbids touching: read it back and ask for a spoken yes/no
            confirmByVoice = true
            speakThen("이렇게 들었어요. $spoken. 보낼까요? 보내, 다시, 취소 중에 말해 주세요", "confirm")
        }
        renderAnswers()
    }

    private fun listenForConfirm() {
        if ((heardText != null && confirmByVoice) || nudge != null || hazard != null || order != null || pickPlaces.isNotEmpty()) voice?.listen()
    }

    private fun answerConfirm(said: String) {
        val w = said.replace(" ", "")
        when {
            listOf("취소", "그만", "됐어").any { it in w } -> cancelHeard()
            listOf("다시", "아니", "틀렸", "아냐").any { it in w } -> retryHeard()
            listOf("보내", "네", "응", "맞", "좋아", "그래", "확인").any { it in w } -> sendHeard()
            else -> speakThen("보내, 다시, 취소 중에 말해 주세요", "confirm")
        }
    }

    private fun cancelHeard() {
        heardText = null; confirmByVoice = false; answerTarget = null; askCar = null
        answersTitle = "도로 소식"
        showNote("보내지 않았어요")
    }

    private fun retryHeard() {
        heardText = null; confirmByVoice = false   // answerTarget stays: the new sentence answers the same question
        startVoice()
    }

    /** Hands-free and always available: the confirmed sentence goes out, to the picked car or to the road. */
    private fun sendHeard() {
        val spoken = heardText ?: return
        heardText = null; confirmByVoice = false
        answerTarget?.let { qid ->   // a spoken answer to somebody's question
            answerTarget = null
            answersTitle = "도로 소식"
            renderAnswers()
            io.execute {
                try { api.answerQuestion(qid, spoken) } catch (_: Exception) {}
                val fresh = try { api.questions() } catch (_: Exception) { null }
                val shared = try { api.board() } catch (_: Exception) { null }
                ui.post { fresh?.let { onQuestions(it) } ?: renderAnswers(); shared?.let { renderBoard(it, force = true) } }
            }
            return
        }
        val car = activeAskCar()
        askCar = null
        if (car != null || isOpenQuestion(spoken)) {   // any other question: to the picked car, or to everyone around
            sayBubble(spoken)
            flash(if (car != null) "${car.nickname}에게 물어봤어요" else "근처 모든 차에게 물어봤어요", "내 질문")
            io.execute {
                val res = try { api.ask(spoken, car?.id) } catch (e: Exception) { null }
                deliver(res, "ASK")
            }
            return
        }
        sayBubble(spoken)   // what the recogniser heard, as a bubble beside our car
        if (isWhyQuestion(spoken)) {
            newQuestion("왜 막히는지 물어봤어요")
            answeredCars.clear()
            road.answers = emptyMap()
            startAsking()
        } else {
            renderAnswers()
            flash("도로에 전했어요 · 근처 모든 차에 보여요")
        }
        io.execute {
            val res = try { api.sendSpoken(spoken, destName, gearCode()) } catch (e: Exception) { null }
            deliver(res, if (isWhyQuestion(spoken)) "ASK_ACCIDENT" else null)
        }
    }

    /** Same words the server listens for. Everything else a driver says is a message to the road. */
    private fun isWhyQuestion(spoken: String): Boolean {
        val w = spoken.replace(" ", "")
        return listOf("왜막", "왜이렇게", "왜이리", "무슨일", "막히는이유", "이유가", "원인").any { it in w }
    }

    /** A sentence that asks something, as opposed to a statement for the road: a question mark, or a question ending or word. */
    private fun looksLikeQuestion(spoken: String): Boolean {
        val w = spoken.replace(" ", "")
        return w.endsWith("?") || QUESTION_HINTS.any { it in w }
    }

    /** Any question other than "왜 막혀?" (that one has its own flow): it goes out as a question, not as a bubble. */
    private fun isOpenQuestion(spoken: String) = !isWhyQuestion(spoken) && looksLikeQuestion(spoken)

    /** A bubble beside our car for a while: what we just asked, said or reported. */
    private fun sayBubble(text: String) {
        road.myMood = text
        val token = ++bubbleToken
        ui.postDelayed({ if (token == bubbleToken) road.myMood = null }, BUBBLE_MS)
    }

    /** A short line in the card that clears itself: for confirmations that need no action. */
    private fun flash(msg: String, title: String = "도로 소식", ms: Long = 3_500) {
        answersTitle = title
        note = msg
        renderAnswers()
        ui.postDelayed({ if (note == msg) { note = null; renderAnswers() } }, ms)
    }

    /** Tap the grade chip in the header: grade, points and the next step, shown briefly in the card. */
    private fun showStanding() {
        val me = board.me
        val steps = GRADE_STEPS
        val idx = steps.indexOfLast { me.points >= it.first }.coerceAtLeast(0)
        val next = steps.getOrNull(idx + 1)
        val line = "${me.grade} · ${me.points}점 · " +
            (if (next != null) "‘${next.second}’까지 ${next.first - me.points}점" else "가장 높은 등급") +
            " · 올린 제보 ${me.posts}건"
        flash(line, "내 기록", 6_000)
    }

    /** "알리기": tell the road what you see, without being asked. */
    private fun inform(tile: Tile) {
        io.execute {
            val ok = try { api.inform(tile.code) } catch (e: Exception) { false }
            val shared = if (ok) try { api.board() } catch (e: Exception) { null } else null
            ui.post {
                newQuestion("내 제보")
                if (ok) sayBubble(REPORT_PHRASE[tile.code] ?: tile.label)   // the road shows it too, beside our car
                flash(if (ok) "“${tile.label}”을 알렸어요 · 앞 상황에 바로 반영돼요" else "지금은 보낼 수 없어요. 연결을 확인해 주세요", "내 제보")
                shared?.let { renderBoard(it) }
            }
        }
    }

    private fun newQuestion(title: String) {
        answers.clear()
        note = null
        expandedId = null
        answersTitle = title
        answerLimit = MAX_ANSWERS
    }

    private fun deliver(res: SendResult?, presetCode: String?) {
        when {
            res == null && presetCode == null -> ui.post { showNote("연결을 확인해 주세요. 지금은 보낼 수 없어요") }
            res == null -> ui.post { showNote("연결을 확인해 주세요. 지금은 보낼 수 없어요") }
            res.code == 422 -> ui.post { showNote(res.error ?: "잘 못 알아들었어요. 다시 말해 주세요") }
            res.error != null -> ui.post { showNote(res.error) }
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

    private fun speakThen(s: String, id: String) {
        if (ttsReady) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, id) else ui.post { listenForConfirm() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) voice?.listen()
        if (requestCode == 4 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) gps.start()
    }

    private fun showInbound(m: Inbound) {
        lastInboxId = maxOf(lastInboxId, m.id)
        stopAsking()
        note = null
        if (answers.size >= answerLimit) answers.removeAt(0)
        answers += m
        val clearToken = ++answersToken
        ui.postDelayed({   // the answers stay for 30 s, then the screen goes back to the board
            if (clearToken == answersToken && heardText == null && nudge == null) {
                answers.clear(); answeredCars.clear(); road.answers = emptyMap()
                answersTitle = "도로 소식"; expandedId = null
                renderAnswers()
            }
        }, ANSWERS_MS)
        if (m.tag.isNotBlank()) {   // the answering car turns blue on the road and says what it saw
            neighbors.firstOrNull { it.nickname == m.from }?.let { answeredCars[it.id] = CAUSE_SHORT[m.tag] ?: "답변" }
            road.answers = answeredCars.toMap()
            road.myMood = null      // the question bubble has done its job
        }
        renderAnswers()
        speak(m.text)
    }

    /** A short message in the answers card (hearing problems, errors, confirmations). */
    private fun showNote(msg: String) {
        stopAsking()
        answers.clear()
        note = msg
        renderAnswers()
    }

    // ---------------------------------------------------------------- the answers card

    private fun renderAnswers() {
        if (!::answersList.isInitialized) return
        applyFocus()
        val quiet = answers.isEmpty() && note == null && roadMessages.isEmpty() && questions.isEmpty()
        answersHeader.text = if (quiet) "가는 길 픽업" else if (answers.isEmpty() && note == null) "도로 소식" else answersTitle
        val causes = answers.map { it.tag }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        val top = causes.maxByOrNull { it.value }
        answersCount.text = when {
            answers.isEmpty() -> ""
            top != null && top.value >= 2 -> "${top.value}대가 ${CAUSE_SHORT[top.key] ?: "같은 답"}이라고 해요"
            else -> "답변 ${answers.size}/$answerLimit"
        }
        answersList.removeAllViews()
        val enabled = info.canSend
        // the top slot is the board, unless the car is offering to ask or the driver is confirming a sentence
        val spoken = heardText
        val offer = nudge
        val picked = pickedCar
        val reporting = pendingReport
        when {
            spoken != null -> renderHeard(spoken)
            order != null -> renderOrder(order!!)
            pickPlaces.isNotEmpty() -> renderPickList()
            reporting != null -> renderReportReasons(reporting)
            profileOpen -> renderProfile()
            picked != null -> renderPicked(picked)
            hazard != null -> renderHazard(hazard!!)
            offer != null -> renderNudge(offer)
            else -> { attentionCard.visibility = View.GONE; renderBoard(board, force = true) }
        }

        if (answers.isEmpty()) {
            if (note != null) {
                answersList.addView(text(note!!, 20f, t.ink, 2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            } else renderQuestions()
        }
        answers.takeLast(2).forEach { m ->   // one answer per row: who, what they said, and the one tap that pays them
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(m.from.take(1), 14f, Color.WHITE, 2).apply {
                gravity = Gravity.CENTER
                background = fill(if (m.auto) t.waiting else t.navyFill, 8)
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val nameRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            nameRow.addView(text(if (m.auto) "자동 안내 · ${m.from}" else m.from, 13f, t.muted, 1))
            col.addView(nameRow)
            col.addView(text(m.text, 16f, t.ink, 2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            if (!m.auto && m.id >= 0) {
                val done = m.id in thankedIds
                val pts = thankedPoints[m.id] ?: 0
                row.addView(text(if (done) "✓ 전했어요" + (if (pts > 0) " +${pts}점" else "") else "도움이 됐어요", 14f, if (done) Color.WHITE else t.accent, 2).apply {
                    gravity = Gravity.CENTER
                    background = if (done) fill(t.accent, 12) else GradientDrawable().apply {
                        setColor(t.surface); cornerRadius = dp(12).toFloat(); setStroke(dp(2), t.accent)
                    }
                    setPadding(dp(14), dp(10), dp(14), dp(10))   // a real target: this tap is the reward that counts
                    alpha = if (enabled || done) 1f else 0.45f
                    setOnClickListener { thank(m.id) }
                    press(this)
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                row.addView(text("신고", 13f, t.muted, 1).apply {
                    setPadding(dp(10), dp(10), dp(2), dp(10))
                    alpha = if (enabled) 1f else 0.45f
                    setOnClickListener { startReport(m.from) { reason -> report(m.id, reason) } }
                })
            }
            answersList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    /** One big button in the card at the top: filled in its colour, or outlined when [bg] is the card's own surface. */
    private fun actionBtn(label: String, bg: Int, fg: Int, sp: Float = 17f, action: () -> Unit) = text(label, sp, if (bg == t.surface) t.ink else fg, 2).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        background = if (bg == t.surface) outline() else fill(bg, 12)
        setPadding(dp(8), dp(14), dp(8), dp(14))
        setOnClickListener { action() }
        press(this)
    }

    /** "이렇게 들었어요": the recognised sentence in big type, with send / say again / cancel. */
    private fun renderHeard(spoken: String) {
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        val car = activeAskCar()
        attentionCard.addView(text("“$spoken”", 22f, t.ink, 2).apply { maxLines = 3 },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        attentionCard.addView(text(
            if (confirmByVoice) "맞으면 “보내”, 다시 말하려면 “다시”, 그만두려면 “취소”라고 말해 주세요"
            else if (answerTarget != null) "이 답을 보낼까요?"
            else if (car != null) "${car.nickname}에게만 보낼까요?"
            else if (isOpenQuestion(spoken)) "근처 모든 차에게 물어볼까요?"
            else "근처 모든 차에게 보낼까요?", 13f, t.muted),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        if (!confirmByVoice) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(actionBtn("보내기", t.accent, Color.WHITE) { sendHeard() }, LinearLayout.LayoutParams(0, -2, 1.4f))
            row.addView(actionBtn("다시 말하기", if (t.night) t.surfaceAlt else t.navyFill, Color.WHITE) { retryHeard() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            row.addView(actionBtn("취소", t.surface, t.muted) { cancelHeard() }, LinearLayout.LayoutParams(0, -2, 0.7f).apply { leftMargin = dp(8) })
            attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    // ---------------------------------------------------------------- my profile

    /** Where we are: the navigation's position while it is current, else the platform GPS (null: no position at all). */
    private fun currentPlace(): Place? = navi?.fresh() ?: gps.fresh()

    /** Editing the profile only while the car stands still (정차: parked or stopped in traffic), never while moving.
     *  Note for review: the emulator measured the stopped-in-traffic state with the "no setup" flag (0x40) set, the same
     *  as moving. If the platform ever enforces that flag for this screen, this is the one line to tighten. */
    private fun profileEditable() = info.state != DriveState.MOVING

    private fun myNick() = myAdj + myAnimal

    private fun loadProfile() {
        val p = getSharedPreferences("gildongmu", MODE_PRIVATE)
        p.getString("p_adj", null)?.takeIf { it in ADJECTIVES }?.let { myAdj = it }
        p.getString("p_animal", null)?.takeIf { it in ANIMALS }?.let { myAnimal = it }
        p.getString("p_color", null)?.takeIf { it in COLORS }?.let { myColor = it }
        p.getString("p_model", null)?.takeIf { it in MODELS }?.let { myModel = it }
        myShowModel = p.getBoolean("p_show_model", true)
    }

    private fun pushProfile() {
        val adj = myAdj; val animal = myAnimal; val color = myColor; val model = myModel; val show = myShowModel
        io.execute { try { api.setMyProfile(adj, animal, color, model, show) } catch (_: Exception) {} }
    }

    /** The header button: the avatar in the chosen colour, the nickname, and the car (or the hint to pick one). */
    private fun refreshProfile() {
        if (!::profileAvatar.isInitialized) return
        profileAvatar.text = myNick().take(1)
        profileAvatar.background = oval(Color.parseColor(myColor))
        profileName.text = myNick()
        profileSub.text = (myModel.ifBlank { "내 차를 골라 주세요" }) + "  ▾"
        if (::road.isInitialized) {   // the same name stands over my own car on the road
            road.myName = myNick()
            road.myModel = if (myShowModel) myModel else ""
        }
    }

    /** Every change is saved at once, shown at once, and sent to the server: there is no separate "save" step. */
    private fun applyProfile() {
        getSharedPreferences("gildongmu", MODE_PRIVATE).edit()
            .putString("p_adj", myAdj).putString("p_animal", myAnimal).putString("p_color", myColor)
            .putString("p_model", myModel).putBoolean("p_show_model", myShowModel).apply()
        refreshProfile()
        pushProfile()
        renderAnswers()
    }

    private fun profileChip(label: String, selected: Boolean, compact: Boolean = false, action: () -> Unit) =
        text(label, if (compact) 14f else 15f, if (selected) Color.WHITE else t.ink, 2).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        background = if (selected) fill(t.accent, 12) else outline()
        if (compact) setPadding(dp(4), dp(11), dp(4), dp(11)) else setPadding(dp(14), dp(11), dp(14), dp(11))
        setOnClickListener { action() }
        press(this)
    }

    private fun renderProfile() {
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

        // who: a big avatar, the name, the car; and a way to draw another name (names come from lists, never typed)
        val who = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        who.addView(text(myNick().take(1), 24f, Color.WHITE, 2).apply {
            gravity = Gravity.CENTER
            background = oval(Color.parseColor(myColor))
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(14) })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text(myNick(), 22f, t.ink, 2))
        names.addView(text(if (myModel.isBlank()) "아직 차를 고르지 않았어요" else if (myShowModel) "$myModel · 이웃에게 보여요" else "$myModel · 나만 알아요", 13f, t.muted),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
        who.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        who.addView(actionBtn("다른 이름", t.surface, t.ink, 15f) {
            val before = myNick()
            do { myAdj = ADJECTIVES.random(); myAnimal = ANIMALS.random() } while (myNick() == before)   // always a new one
            applyProfile()
        }, LinearLayout.LayoutParams(-2, -2))
        attentionCard.addView(who, lp(6))

        // colour of the avatar
        attentionCard.addView(text("색", 13f, t.muted, 1), lp(12))
        val colors = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        COLORS.forEachIndexed { i, hex ->
            val on = hex == myColor
            colors.addView(View(this).apply {
                background = if (on) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(hex)); setStroke(dp(3), t.ink) } else oval(Color.parseColor(hex))
                setOnClickListener { myColor = hex; applyProfile() }
                press(this)
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { if (i > 0) leftMargin = dp(12) })
        }
        attentionCard.addView(colors, lp(6))

        // the Hyundai model: one tap on a chip. All twelve are on screen at once (4 x 3): no sideways scrolling to fight with
        attentionCard.addView(text("내 차 (현대)", 13f, t.muted, 1), lp(12))
        MODELS.chunked(4).forEachIndexed { r, chunk ->
            val row = LinearLayout(this)
            chunk.forEachIndexed { i, name ->
                row.addView(profileChip(name, name == myModel, compact = true) { myModel = if (myModel == name) "" else name; applyProfile() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
            attentionCard.addView(row, lp(if (r == 0) 6 else 8))
        }

        // privacy and done
        val foot = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        foot.addView(profileChip(if (myShowModel) "차종 공개 중" else "차종 비공개", myShowModel) { myShowModel = !myShowModel; applyProfile() })
        foot.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        foot.addView(actionBtn("완료", t.accent, Color.WHITE, 16f) { profileOpen = false; renderAnswers() },
            LinearLayout.LayoutParams(dp(120), -2))
        attentionCard.addView(foot, lp(14))
        attentionCard.addView(text("개인정보 안내 보기", 13f, t.muted, 1).apply {
            setPadding(0, dp(10), dp(10), dp(2))
            setOnClickListener { showConsent(readOnly = true) }
        }, LinearLayout.LayoutParams(-2, -2))
    }

    // ---------------------------------------------------------------- a car picked on the road

    /** The car the next question goes to, while the pick is fresh; otherwise null (everyone around). */
    private fun activeAskCar(): Neighbor? {
        if (askCar != null && System.currentTimeMillis() - askCarAt > ASK_CAR_MS) askCar = null
        return askCar
    }

    /** A tap on a car: ask it something, or report it. Not while moving: a small target is for a stopped car. */
    private fun onCarTapped(id: String) {
        if (info.state == DriveState.MOVING || !info.canSend) {
            flash("달리는 중에는 차를 고를 수 없어요 · 말로 물어보세요")
            return
        }
        val car = neighbors.firstOrNull { it.id == id } ?: return
        pickedCar = car
        pendingReport = null
        val token = ++pickedToken
        ui.postDelayed({ if (token == pickedToken && pickedCar != null) { pickedCar = null; renderAnswers() } }, PICK_MS)
        renderAnswers()
    }

    /** "신고" on a car, an answer or a message: first the reason (a fixed list, touch only), and only then is it filed. */
    private fun startReport(name: String, carId: String? = null, file: (String) -> Unit) {
        if (!info.canSend) return
        if (info.state == DriveState.MOVING) {   // picking a reason is two taps: while moving only one-tap and voice things are allowed
            flash("달리는 중에는 신고 이유를 고를 수 없어요 · 멈춘 뒤에 해 주세요", "신고")
            return
        }
        pickedCar = null
        pendingReport = PendingReport(name, carId, file)
        val token = ++pickedToken
        ui.postDelayed({ if (token == pickedToken && pendingReport != null) { pendingReport = null; renderAnswers() } }, PICK_MS)
        renderAnswers()
    }

    private fun reasonLabel(code: String) = REPORT_REASONS.firstOrNull { it.first == code }?.second ?: code

    /** [color] with the given opacity: a soft wash of it, for chips that must stand out without shouting. */
    private fun tint(color: Int, opacity: Float) = Color.argb((opacity * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** A report reason: a big, soft-red chip, easy to hit and easy to read at a glance. */
    private fun reasonChip(label: String, action: () -> Unit) = text(label, 16f, t.ink, 2).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        background = GradientDrawable().apply {
            setColor(tint(t.moving, if (t.night) 0.20f else 0.09f)); cornerRadius = dp(12).toFloat()
            setStroke(dp(1), tint(t.moving, if (t.night) 0.55f else 0.35f))
        }
        setPadding(dp(6), dp(16), dp(6), dp(16))
        setOnClickListener { action() }
        press(this)
    }

    private fun renderReportReasons(p: PendingReport) {
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(icon(R.drawable.ic_warning, 24, t.moving), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) })
        head.addView(text("${p.name}님을 왜 신고할까요?", 20f, t.ink, 2))
        attentionCard.addView(head, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        attentionCard.addView(text("신고하면 차단되고, 이 이웃이 올린 글과 질문이 모두 사라져요", 13f, t.muted),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); leftMargin = dp(34) })
        REPORT_REASONS.chunked(3).forEach { chunk ->   // two rows of three: each reason is a large target
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            chunk.forEachIndexed { i, (code, label) ->
                row.addView(reasonChip(label) { pendingReport = null; p.file(code) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
            attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        attentionCard.addView(actionBtn("취소", t.surface, t.muted, 15f) { pendingReport = null; renderAnswers() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun renderPicked(car: Neighbor) {
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        val where = "${car.distanceM}m " + if (car.aheadM >= 0) "앞" else "뒤"
        val dest = car.dest.takeIf { it.isNotBlank() && it != "null" }?.let { " · $it 가는 중" } ?: ""
        val who = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }   // the same initial badge as an answer row
        who.addView(text(car.nickname.take(1), 18f, Color.WHITE, 2).apply {
            gravity = Gravity.CENTER
            background = fill(t.navyFill, 12)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        val whoText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        whoText.addView(text(car.nickname, 22f, t.ink, 2))
        whoText.addView(text(where + dest, 14f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
        who.addView(whoText)
        attentionCard.addView(who, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(actionBtn("이 차에 물어보기", t.accent, Color.WHITE) { askPicked(car) }, LinearLayout.LayoutParams(0, -2, 1.7f))
        row.addView(actionBtn("신고", t.surface, t.muted) { startReport(car.nickname, car.id) { reason -> reportPicked(car, reason) } },
            LinearLayout.LayoutParams(0, -2, 0.7f).apply { leftMargin = dp(8) })
        row.addView(actionBtn("닫기", t.surface, t.muted) { pickedCar = null; renderAnswers() },
            LinearLayout.LayoutParams(0, -2, 0.7f).apply { leftMargin = dp(8) })
        attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    /** "이 차에 물어보기": the driver now says the question out loud; it is read back before it goes to this car only. */
    private fun askPicked(car: Neighbor) {
        askCar = car
        askCarAt = System.currentTimeMillis()
        pickedCar = null
        answersTitle = "${car.nickname}에게 물어보기"
        renderAnswers()
        startVoice()
    }

    private fun reportPicked(car: Neighbor, reason: String) {
        pickedCar = null
        neighbors = neighbors.filter { it.id != car.id }   // gone from the road at once; the server blocks it for good
        road.neighbors = neighbors
        flash("신고했어요 · 이유: ${reasonLabel(reason)} · ${car.nickname}님은 차단되고 더 이상 보이지 않아요", "신고")
        io.execute { try { api.reportCar(car.id, reason) } catch (_: Exception) {} }
    }

    private fun thank(id: Int) {
        if (!info.canSend || id in thankedIds) return
        thankedIds += id
        renderAnswers()
        io.execute {
            val pts = try { api.thanks(id) } catch (_: Exception) { 0 }
            val shared = try { api.board() } catch (_: Exception) { null }
            ui.post { thankedPoints[id] = pts; shared?.let { board = it }; renderAnswers() }
        }
    }

    private fun report(id: Int, reason: String) {
        if (!info.canSend) return
        io.execute { try { api.report(id, reason) } catch (_: Exception) {} }
        answers.removeAll { it.id == id }
        note = "신고했어요 · 이유: ${reasonLabel(reason)} · 이 이웃의 답은 더 이상 보이지 않아요"
        expandedId = null
        renderAnswers()
    }

    /**
     * A question is out. The screen stays as it was (nobody should stare at a waiting card): the ask button
     * says so, a ripple spreads from our car, and the answers appear the moment one arrives.
     * After 30 s without an answer it quietly goes back to normal.
     */
    private fun startAsking() {
        asking = true
        road.scanning = true
        setAskLabel()
        renderAnswers()   // the confirm card / old answers give way to the board while we wait
        val token = ++askToken
        ui.postDelayed({ if (token == askToken && asking) { stopAsking(); setAskLabel("아직 답이 없어요 · 잠시 뒤 다시 물어볼 수 있어요"); ui.postDelayed({ setAskLabel() }, 6_000) } }, ASK_MS)
    }

    private fun stopAsking() {
        if (!asking) return
        asking = false
        askToken++
        road.scanning = false
        setAskLabel()
    }

    private fun setAskLabel(note: String? = null) {
        if (!::whyTitle.isInitialized) return
        whyTitle.text = if (asking) "이웃에게 물어보는 중…" else "왜 막혀요?"
        whySub.text = note ?: if (asking) "답이 오면 바로 보여 드릴게요" else "주변 이웃에게 지금 물어보기"
        whyButton.alpha = if (asking || !info.canSend) 0.6f else 1f
    }

    private fun speak(s: String) {
        if (ttsReady) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "msg")
    }

    companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        private const val TAG = "Gildongmu"
        private const val MAX_ANSWERS = 3
        private const val JAM_KMH = 20f
        private val GRADE_STEPS = listOf(0 to "길 위 새싹", 30 to "길잡이", 100 to "도로 박사", 300 to "도로 영웅")   // same as server/board.py
        private const val CORNER = 16
        private const val ROAD_CARS = 6   // cars the road view has room for; the rest are counted below
        private val HAZARD_TILES = setOf("debris", "roadkill")   // these take a photo and file a ticket
        private const val BANNER_DP = 104   // two reports, or one report with its 3-tap prompt
        private val REPORT_PHRASE = mapOf("accident" to "앞에 사고 났어요", "debris" to "낙하물이 있어요",
            "roadkill" to "로드킬이 있어요", "work" to "공사 중이에요")
        // what a profile is built from: the same lists as server/live.py (the server accepts nothing else)
        private val ADJECTIVES = listOf("귀성길", "느긋한", "졸린", "달리는", "든든한", "신나는", "조용한", "반가운", "씩씩한", "여유로운", "부지런한", "포근한")
        private val ANIMALS = listOf("곰", "토끼", "사자", "펭귄", "여우", "고래", "다람쥐", "수달", "고양이", "강아지", "오리", "거북이")
        private val COLORS = listOf("#00AAD2", "#12B76A", "#F5A524", "#E5446D", "#7A5AF8", "#2E90FA")
        private val MODELS = listOf("캐스퍼", "아반떼", "쏘나타", "그랜저", "코나", "투싼", "싼타페", "팰리세이드", "스타리아",
            "아이오닉 5", "아이오닉 6", "아이오닉 9")
        private const val CONSENT_KEY = "consent_v1"   // bump the number when what is collected changes: everyone is asked again
        private const val PHOTO_DAYS = 30               // same as PHOTO_DAYS in server/app_server.py
        private const val PICK_MS = 15_000L     // the card of a tapped car (or of the report reasons) closes by itself
        private val REPORT_REASONS = listOf("reckless" to "난폭 운전", "rude" to "욕설·비하", "weird" to "이상한 질문",
            "spam" to "광고·스팸", "fake" to "허위 제보", "other" to "기타")   // same codes as core.REPORT_REASONS on the server
        private const val ASK_CAR_MS = 60_000L  // "이 차에 물어보기" is forgotten if nobody speaks within this long
        private val QUESTION_HINTS = listOf("나요", "까요", "가요", "인가", "인지", "어때", "어떻게", "어디", "언제", "누구", "뭐", "몇",
            "얼마", "알아요", "아세요", "알려", "궁금", "있나", "없나", "되나", "있어요?", "해요?")
        private const val PROMPT_MS = 10_000L   // an unanswered prompt goes away by itself
        private const val NUDGE_MS = 12_000L    // so does the car's own offer to ask
        private const val HAZARD_MS = 15_000L   // and the offer to report a hazard
        private const val ASK_MS = 30_000L      // how long we wait for a first answer before going back to normal
        private const val BUBBLE_MS = 25_000L   // the question bubble over our car
        private const val ANSWERS_MS = 30_000L  // how long the answers stay before the board comes back by itself
        private val CAUSE_SHORT = mapOf("accident" to "사고", "work" to "공사", "debris" to "낙하물",
            "breakdown" to "고장 차", "merge" to "합류", "crowd" to "차 많음")
    }
}
