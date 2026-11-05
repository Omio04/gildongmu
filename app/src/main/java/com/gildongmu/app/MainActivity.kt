package com.gildongmu.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
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
    private var pleosVehicle: PleosVehicleClient? = null   // Pleos Vehicle SDK: the gear and the brake pedal
    private var pleosFused: PleosFusedClient? = null       // Pleos FusedLocation SDK: the car's own position (after consent)
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
    private var order: Pick? = null                // a (sample) pickup waiting for the driver to pay or cancel
    private var alts: List<Pick> = emptyList()      // the shops of the asked kind in range, nearest first; "다른 곳" steps through them
    private var altIdx = 0
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
            onHeard = { spoken -> ui.post { voiceWave.active = false; listening = false; orderListenTries = 0; handleHeard(spoken) } },
            onProblem = { msg -> ui.post {
                voiceWave.active = false; listening = false
                // an order card is open and nothing was caught: listen again by itself (twice at most), so the driver need not tap
                if (order != null && msg == "잘 듣지 못했어요" && orderListenTries < 2) {
                    orderListenTries++
                    renderAnswers()
                    ui.postDelayed({ if (order != null && !listening) voice?.listen() }, 500)
                } else showNote(msg)
            } },
            onListening = { ui.post { voiceWave.active = true; listening = true; if (order != null) renderAnswers() } })

        if (ContextCompat.checkSelfPermission(this, SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(SPEED_PERMISSION), 1)
        }
        pleosVehicle = PleosVehicleClient(this, { g -> android.util.Log.i(TAG, "pleos gear $g") }, { b -> android.util.Log.i(TAG, "pleos brake $b") }, { android.util.Log.i(TAG, it) }).also { it.start() }
        monitor = DrivingMonitor(this) { newInfo -> ui.post { onDriveInfo(newInfo) } }
        monitor.start()
        render()
        renderAnswers()
        // nothing leaves the car (position, profile, questions) until the driver has read what is used and agreed
        consented = getSharedPreferences("gildongmu", MODE_PRIVATE).getBoolean(CONSENT_KEY, false)
        // first run: what the app does, then what it uses (and the driver's yes), then the profile (or skip it)
        val begin = { if (consented) startSharing() else showConsent(readOnly = false) }
        if (!getSharedPreferences("gildongmu", MODE_PRIVATE).getBoolean(INTRO_KEY, false)) showIntro(begin) else begin()
        ui.postDelayed(object : Runnable {   // the "stuck for N minutes" label moves on its own clock
            override fun run() { renderState(); ui.postDelayed(this, 20_000) }
        }, 20_000)

        ui.postDelayed(object : Runnable {
            override fun run() { evaluateJam(); ui.postDelayed(this, 5_000) }
        }, 5_000)

        // debug builds only: pretend the car is in a given state, to look at that screen without the console
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            val filter = android.content.IntentFilter("com.gildongmu.app.DEBUG")
            val rx = object : android.content.BroadcastReceiver() { override fun onReceive(c: android.content.Context?, i: android.content.Intent) { ui.post { debugCommand(i) } } }
            debugReceiver = rx
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(rx, filter, android.content.Context.RECEIVER_EXPORTED) else registerReceiver(rx, filter)
            when (intent.getStringExtra("force_state")) {
                "moving" -> ui.postDelayed({ forcedInfo = DriveInfo(DriveState.MOVING, true, 0x50, 32f); onDriveInfo(forcedInfo!!) }, 4000)
                "idling" -> ui.postDelayed({ forcedInfo = DriveInfo(DriveState.IDLING, true, 0x50, 0f); onDriveInfo(forcedInfo!!) }, 4000)
                "locked" -> ui.postDelayed({ forcedInfo = DriveInfo(DriveState.MOVING, true, 0x1FF, 32f); onDriveInfo(forcedInfo!!) }, 4000)
            }
            // --es say "커피 마시고 싶어,결제": what the recogniser would have heard, one phrase every 4 s (the microphone itself is not exercised)
            intent.getStringExtra("say")?.split(",")?.forEachIndexed { i, phrase ->
                ui.postDelayed({ handleHeard(phrase.trim()) }, intent.getIntExtra("say_delay", 12) * 1_000L + i * 4_000L)
            }
            if (intent.hasExtra("fake_lat") && intent.hasExtra("fake_lon"))
                fakePlace = Place(intent.getFloatExtra("fake_lat", 0f).toDouble(), intent.getFloatExtra("fake_lon", 0f).toDouble(), 0.0, "시험 위치")
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
            onTurn = { tn -> ui.post { turn = tn; renderTurn() } },
            onWaypoint = { m -> ui.post { routeEta = m } }).also { it.start() }
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
        ui.postDelayed(object : Runnable {   // the car moves: which pickup shops are in range changes, so redraw the idle slot
            override fun run() {
                if (order == null && note == null && answers.isEmpty() && questions.isEmpty() && roadMessages.isEmpty()) refreshShops()   // the answer redraws the slot
                trackActiveOrder()
                ui.postDelayed(this, 10_000)
            }
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

    /**
     * Debug builds only: the same test options as at start, sent to the running app without restarting it (used to record
     * the demo video):  adb shell am broadcast -a com.gildongmu.app.DEBUG --es say "a,b" --es force_state moving|idling|parked
     * --ef fake_lat 37.9 --ef fake_lon 127.5
     */
    private fun debugCommand(intent: android.content.Intent) {
        android.util.Log.i(TAG, "debug command: force_state=${intent.getStringExtra("force_state")} say=${intent.getStringExtra("say")}")
        intent.getStringExtra("force_state")?.let { st ->
            val info2 = when (st) {
                "moving" -> DriveInfo(DriveState.MOVING, true, 0x50, 32f)
                "idling" -> DriveInfo(DriveState.IDLING, true, 0x50, 0f)
                "parked" -> DriveInfo(DriveState.PARKED, false, 0, 0f)
                else -> null
            }
            if (info2 != null) { forcedInfo = info2; onDriveInfo(info2) }
        }
        if (intent.hasExtra("probe_x")) {   // log which views lie under a screen point (to find what swallows a tap)
            val px = intent.getIntExtra("probe_x", 0); val py = intent.getIntExtra("probe_y", 0)
            fun walk(v: View, depth: Int) {
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                if (px >= loc[0] && px < loc[0] + v.width && py >= loc[1] && py < loc[1] + v.height && v.visibility == View.VISIBLE) {
                    android.util.Log.i(TAG, "probe ${"  ".repeat(depth)}${v.javaClass.simpleName} clickable=${v.isClickable} [${loc[0]},${loc[1]} ${v.width}x${v.height}]")
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
                }
            }
            walk(window.decorView, 0)
        }
        if (intent.hasExtra("fake_lat") && intent.hasExtra("fake_lon"))
            fakePlace = Place(intent.getFloatExtra("fake_lat", 0f).toDouble(), intent.getFloatExtra("fake_lon", 0f).toDouble(), 0.0, "시험 위치")
        intent.getStringExtra("say")?.split(",")?.forEachIndexed { i, phrase ->
            ui.postDelayed({ handleHeard(phrase.trim()) }, intent.getIntExtra("say_delay", 0) * 1_000L + i * 4_000L)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) debugCommand(intent)
    }

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
        startFused()   // the car's fused position, first choice (its own permission, asked after the agreement)
        pushProfile()   // the server learns the name and car other cars will see
        polling = true
        pollLoop()
    }

    private var introView: View? = null

    private fun startFused() {
        if (pleosFused != null) return
        if (ContextCompat.checkSelfPermission(this, FUSED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(FUSED_PERMISSION), 5)
            return
        }
        pleosFused = PleosFusedClient(this) { android.util.Log.i(TAG, it) }.also { it.start() }
    }

    /** The first thing a new driver sees: what the app is for, in four short cards. Shown once. */
    private fun showIntro(onDone: () -> Unit) {
        if (introView != null) return
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(30), dp(22), dp(30), dp(26)) }
        panel.addView(text("길동무는 이렇게 써요", 26f, t.ink, 2))
        panel.addView(text("막힌 길 위에서 근처 차에게 묻고, 알리고, 도움받아요", 15f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        listOf(
            "왜 막혀요?" to "정체 중에 버튼 한 번, 또는 말로 근처 이웃에게 지금 사정을 물어봐요. 이웃이 답하면 바로 보여 드려요.",
            "말로 하기" to "운전 중에는 마이크로 말해요. 말한 글자를 읽어 드리고, 내가 확인한 뒤에만 보내요.",
            "알리기와 점수" to "사고·낙하물·로드킬·공사를 알리면, 이웃이 ‘도움이 됐어요’를 눌러 점수와 등급이 올라가요.",
            "가는 길 픽업" to "5km 안 제휴 가게의 커피·식사·간식을 도착 시간에 맞춰 받아요. (지금은 시험용 샘플이고 결제는 모의예요)",
        ).forEach { (title, body) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(); setPadding(dp(16), dp(10), dp(16), dp(12)) }
            row.addView(text(title, 17f, t.primary, 2))
            row.addView(text(body, 14f, t.ink), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        panel.addView(actionBtn("다음", t.accent, Color.WHITE) {
            getSharedPreferences("gildongmu", MODE_PRIVATE).edit().putBoolean(INTRO_KEY, true).apply()
            introView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; introView = null
            onDone()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val overlay = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
            isClickable = true
            addView(android.widget.ScrollView(this@MainActivity).apply { addView(panel) }, FrameLayout.LayoutParams(-1, -1))
        }
        introView = overlay
        addContentView(overlay, android.view.ViewGroup.LayoutParams(-1, -1))
    }

    /** Right after the first agreement: offer the profile (name, colour, car). It can be skipped, and is never forced while moving. */
    private fun maybeFirstProfile() {
        if (getSharedPreferences("gildongmu", MODE_PRIVATE).getBoolean(PROFILE_KEY, false) || !profileEditable()) return
        profileOpen = true; pickedCar = null; pendingReport = null
        renderAnswers()
    }

    private fun profileDone() {
        val first = !getSharedPreferences("gildongmu", MODE_PRIVATE).getBoolean(PROFILE_KEY, false)
        getSharedPreferences("gildongmu", MODE_PRIVATE).edit().putBoolean(PROFILE_KEY, true).apply()
        profileOpen = false
        if (first && payMethods().isEmpty() && profileEditable()) openPayments()   // first run: offer the payment method once, right away (can be closed)
        else renderAnswers()
    }

    /** The first-run notice (and, read-only, from the profile): plain words, one short paragraph per thing that is used. */
    private fun showConsent(readOnly: Boolean) {
        if (consentView != null) return
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(30), dp(22), dp(30), dp(26)) }
        panel.addView(text("개인정보와 권한 안내", 26f, t.ink, 2))
        panel.addView(text("길동무가 무엇을 쓰고, 무엇을 쓰지 않는지 먼저 알려 드려요", 15f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        listOf(
            "위치" to "가까운 차와의 거리를 재고, 신고한 곳을 남기고, 가까운 가게를 찾는 데만 써요. 다른 차에는 정확한 위치 대신 대략의 거리만 보여요.",
            "사진" to "위험을 신고할 때 ‘촬영하고 신고’를 누른 경우에만 한 장을 찍어요. 계속 녹화하지 않고, 서버에 ${PHOTO_DAYS}일 보관한 뒤 지워요.",
            "음성" to "마이크는 내가 누르거나 앱이 무언가 물어본 직후에만 들어요. 말한 소리는 글자로만 바꿔 쓰고 저장하지 않아요. 글자는 내가 확인하고 ‘보내기’를 누르기 전에는 아무에게도 가지 않아요.",
            "이름·차종·주문" to "목록에서 고른 이름과 차종만 이웃에게 보여요. 가는 길 픽업은 시험용이라 실제 결제는 되지 않고, 주문(가게·메뉴·접수번호)만 서버에 기록돼요.",
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
                maybeFirstProfile()
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
        debugReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        shopCache.clear()
        pleosFused?.stop(); pleosVehicle?.stop()
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
    /** The one filled main button of the screen: a single flat colour (no gradient: it carries no information). */
    private fun hero() = GradientDrawable().apply {
        setColor(if (t.night) Color.parseColor("#1B66D1") else Color.parseColor("#002C5F"))
        cornerRadius = dp(CORNER).toFloat()
    }

    /** A secondary button: the fill is reserved for the one action that matters on a card. */
    private fun outline() = GradientDrawable().apply {
        setColor(t.surface)
        cornerRadius = dp(CORNER - 2).toFloat()
        setStroke(dp(1), t.border)
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /**
     * Small text is enlarged for a driver's glance: 12sp becomes 15, 14 becomes 17, 18 becomes 20; titles from 24sp up
     * are unchanged. The curve is monotonic, so the order of sizes in the design stays the same.
     */
    private fun legible(sp: Float) = sp * (1f + 0.25f * maxOf(0f, 24f - sp) / 12f)

    /** weight: 0 regular, 1 semibold, 2 bold. Sizes used: 12 meta, 15 body, 18 label, 22 title (before [legible]). */
    private fun text(s: String, sp: Float, color: Int = t.ink, weight: Int = 0) =
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
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
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
        voiceBar.addView(text("말로 묻기", 13f, t.accent, 2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })   // says what it is: free speech, not the fixed question
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
        liveChip.text = "● 끊김"   // short: the header is already full
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
            // the neighbour who just answered my question already has a "도움이 됐어요" in the answers card: the same thing is not asked twice
            val answeredMe = answers.any { it.from == post.by }
            val wantsPrompt = !post.mine && post.myVote == null && post.id !in skipped && !promptShown && !answeredMe
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
        // any big card in the top slot (a report, a sentence to confirm, an offer, an order, a picked car, the profile) takes the room of the banner:
        // otherwise the buttons at the bottom are pushed off the screen
        bannerCard.visibility = if (profileOpen || payOpen || kindChooser || pendingReport != null || order != null || hazard != null || heardText != null || nudge != null || pickedCar != null) View.GONE else View.VISIBLE
        answersCard.visibility = if (order != null || kindChooser || profileOpen || payOpen || hazard != null || heardText != null || nudge != null || pickedCar != null || pendingReport != null) View.GONE else View.VISIBLE   // the order or profile card replaces it: no empty squeezed card under it
        stoppedArea.visibility = if (info.canSend && !profileOpen && !payOpen && order == null && !kindChooser && pickedCar == null && pendingReport == null) View.VISIBLE else View.GONE   // editing the profile needs the room; a picked car is the only thing on screen
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
        col.addView(text(if (hazardPhoto != null) "위치와 사진이 함께 신고돼요 · 근처 이웃에게도 알려요" else "위치와 함께 신고돼요 · 근처 이웃에게도 알려요", 14f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
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
        btn(if (hazardByDriver) "닫기" else "괜찮아요", false, 1f) { declineHazard(p) }
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
    /** Whatever was heard goes to whoever is waiting for an answer: a confirmation, a hazard, an order, an offer, or a new request. */
    private fun handleHeard(spoken: String) {
        android.util.Log.i(TAG, "heard ${spoken.length} chars -> " + (if (confirmByVoice) "confirm" else if (hazard != null) "hazard" else if (order != null) "order" else if (nudge != null) "nudge" else "route"))   // length only, never the words
        if (confirmByVoice) answerConfirm(spoken)
        else if (hazard != null) answerHazard(spoken)
        else if (order != null) answerOrder(spoken)
        else if (nudge != null) answerNudge(spoken)
        else routeSpoken(spoken)
    }

    /** Settings by voice are allowed while driving (touching them is not): "내 차는 아반떼", "이름 바꿔 줘". */
    private fun voiceProfile(spoken: String): Boolean {
        val w = spoken.replace(" ", "")
        val model = MODELS.firstOrNull { w.contains(it.replace(" ", "")) }
        if (model != null && (w.contains("내차") || w.contains("차종") || w.contains("차는") || w.contains("차로"))) {
            myModel = model; applyProfile()
            speak("내 차를 $model(으)로 바꿨어요")
            flash("내 차 · $model", "프로필")
            return true
        }
        if (w.contains("이름") && (w.contains("바꿔") || w.contains("다른") || w.contains("새로"))) {
            val before = myNick()
            do { myAdj = ADJECTIVES.random(); myAnimal = ANIMALS.random() } while (myNick() == before)
            applyProfile()
            speak("이름을 ${myNick()}(으)로 바꿨어요")
            flash("이름 · ${myNick()}", "프로필")
            return true
        }
        return false
    }

    private fun routeSpoken(spoken: String) {
        if (voiceProfile(spoken)) return
        if (spoken.replace(" ", "").let { "전체" in it && "보" in it } && favourites().isNotEmpty()) { showAllShops = true; renderAnswers(); return }
        activeOrder?.let { o ->   // "주문 취소", "주문 어떻게 됐어?": about the order on its way
            val w = spoken.replace(" ", "")
            if ("경유지" in w || ("내비" in w && "넣" in w)) { addShopWaypoint(); return }
            if ("주문" in w && listOf("취소", "그만").any { it in w }) { speak("결제한 주문은 앱에서 취소할 수 없어요. 가게로 전화해 주세요"); return }
            if ("주문" in w && listOf("어떻게", "상태", "언제", "됐", "어디").any { it in w }) { speak(Errands.statusSpoken(o)); return }
        }
        if (kindChooser) {   // the window is open: a kind, a shop's name or a thing to buy picks; or close it
            val w0 = spoken.replace(" ", "")
            if (listOf("닫", "그만", "취소", "됐어").any { it in w0 }) { kindChooser = false; renderAnswers(); return }
            Errands.kindFromWord(spoken)?.let { openList(it); return }
            shopView?.let { v -> Errands.shopFor(v, spoken)?.let { openShopListing(it); return } }
            Errands.matchType(spoken)?.let { if (it != "ALL") { openList(it); return } }
            speakThen("커피, 식사, 간식 중에 말하거나 가게 이름을 말해 주세요", "confirm"); return
        }
        shopView?.let { v -> Errands.shopFor(v, spoken)?.let { openShopListing(it); return } }
        val type = Errands.matchType(spoken)
        if (type == "ALL") openKindChooser() else if (type != null) openList(type) else propose(spoken)
    }

    private fun touchOk() = info.state != DriveState.MOVING

    private val shopCache = ShopCache()             // shops held now: in at 5 km, deleted beyond 6 km, memory only
    private var shopView: ShopsView? = null          // what the server last said is near the car
    private var shopLoading = false
    private var shopFailed = false
    private var orderKey = ""
    private var payOpen = false                   // the payment-methods and order-history card is open (stopped only)
    private var payTab = "live"                    // "live" or "done"
    private var payHistory: List<OrderRow> = emptyList()
    private var payExpanded: String? = null        // the receipt whose payment detail is open

    private fun prefs() = getSharedPreferences("gildongmu", MODE_PRIVATE)

    private fun payMethods(): List<PayMethod> = try {
        val arr = org.json.JSONArray(prefs().getString("pay_methods", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> PayMethod(o.getString("id"), o.getString("type"), o.optString("last4", ""), o.getString("token")) } }
    } catch (_: Exception) { emptyList() }

    private fun savePayMethods(list: List<PayMethod>, default: String?) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("type", it.type).put("last4", it.last4).put("token", it.token)) }
        prefs().edit().putString("pay_methods", arr.toString()).putString("pay_default", default ?: list.firstOrNull()?.id).apply()
    }

    private fun defaultMethod(): PayMethod? = payMethods().let { l -> l.firstOrNull { it.id == prefs().getString("pay_default", null) } ?: l.firstOrNull() }
    private fun payToken(): String? = defaultMethod()?.token
    private fun payLabel(): String = defaultMethod()?.label() ?: "없음"

    /**
     * MOCK registration, done while stopped. A real one is done on the phone or the web with the card company's own screen,
     * and the car only keeps the token it gets back. Here a made-up token is stored: no card number exists anywhere.
     */
    private fun addPayMethod(type: String) {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val tok = "tok_" + (1..12).map { chars.random() }.joinToString("")
        val m = PayMethod("m" + (1..6).map { chars.random() }.joinToString(""), type, if (type == "domestic") "1234" else if (type == "overseas") "5678" else "", tok)
        val list = payMethods() + m
        savePayMethods(list, prefs().getString("pay_default", null) ?: m.id)
        renderAnswers()
    }

    private fun removePayMethod(id: String) {
        val list = payMethods().filter { it.id != id }
        val keep = prefs().getString("pay_default", null)?.takeIf { d -> list.any { it.id == d } }
        savePayMethods(list, keep)
        renderAnswers()
    }

    private var resumeOrder: Pick? = null          // the order card to come back to after a payment method was registered

    private fun openPayments() {
        resumeOrder = order; order = null           // the order card would otherwise cover this screen
        payOpen = true; profileOpen = false; pickedCar = null; pendingReport = null
        payExpanded = null
        io.execute {
            val rows = try { api.myOrders() } catch (_: Exception) { null }
            ui.post { if (rows != null) payHistory = rows; if (payOpen) renderAnswers() }
        }
        renderAnswers()
    }

    private fun closePayments() {
        payOpen = false
        resumeOrder?.let { order = it; resumeOrder = null }   // back to paying, now with a method on file
        renderAnswers()
    }

    /** Payment methods (list, default, delete, add) and the order history with a receipt: the kind of screen ride and order apps have. */
    private fun renderPayments() {
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("결제·주문", 20f, t.ink, 2), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(profileChip("닫기", false, compact = true) { closePayments() }, LinearLayout.LayoutParams(dp(90), -2))
        attentionCard.addView(top)
        attentionCard.addView(text("결제 수단", 13f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        val methods = payMethods()
        val def = defaultMethod()
        if (methods.isEmpty()) attentionCard.addView(text("결제 수단을 등록하면 바로 결제할 수 있어요", 15f, t.ink, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        methods.take(3).forEach { m ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(m.label(), 15f, t.ink, 2).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            if (m.id == def?.id) row.addView(text("기본", 13f, Color.WHITE, 2).apply { background = fill(t.accent, 8); setPadding(dp(10), dp(4), dp(10), dp(4)) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            else row.addView(profileChip("기본으로", false, compact = true) { savePayMethods(methods, m.id); renderAnswers() }, LinearLayout.LayoutParams(dp(90), -2).apply { rightMargin = dp(6) })
            row.addView(profileChip("삭제", false, compact = true) { removePayMethod(m.id) }, LinearLayout.LayoutParams(dp(70), -2))
            attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        val add = LinearLayout(this)
        listOf("easy" to "+ 간편결제", "domestic" to "+ 국내 카드", "overseas" to "+ 해외 카드").forEach { (type, label) ->
            add.addView(profileChip(label, false, compact = true) { addPayMethod(type) }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (add.childCount > 0) leftMargin = dp(6) })
        }
        attentionCard.addView(add, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        attentionCard.addView(text("카드 번호는 폰·웹의 카드사 화면에서 입력하고 차에는 토큰만 저장돼요 (지금은 모의 등록)", 12f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })

        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tabs.addView(text("주문 내역", 13f, t.muted, 1), LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(profileChip("진행 중", payTab == "live", compact = true) { payTab = "live"; payExpanded = null; renderAnswers() }, LinearLayout.LayoutParams(dp(100), -2).apply { rightMargin = dp(6) })
        tabs.addView(profileChip("완료", payTab == "done", compact = true) { payTab = "done"; payExpanded = null; renderAnswers() }, LinearLayout.LayoutParams(dp(90), -2))
        attentionCard.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val rows = payHistory.filter { (payTab == "live") == it.live }.take(2)
        if (rows.isEmpty()) attentionCard.addView(text(if (payTab == "live") "진행 중인 주문이 없어요" else "완료된 주문이 없어요", 15f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        val fmt = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA)
        rows.forEach { o ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = outline(); setPadding(dp(12), dp(8), dp(12), dp(8)) }
            box.addView(text("${o.shop} · ${o.item}", 15f, t.ink, 2).apply { maxLines = 1 })
            box.addView(text("${o.statusLabel} · ${"%,d원".format(o.price)} · ${fmt.format(Date((o.createdAt * 1000).toLong()))}", 13f, t.muted, 1).apply { maxLines = 1 })
            if (payExpanded == o.receipt) {
                box.addView(text("결제 상세 · 메뉴 ${"%,d원".format(o.price)} · 할인 0원 · 합계 ${"%,d원".format(o.price)}" + (if (o.refunded) " · 환불됨(모의)" else ""), 13f, t.ink, 1),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                box.addView(text("${o.payment} · ${o.receipt}", 12f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
            }
            box.setOnClickListener { payExpanded = if (payExpanded == o.receipt) null else o.receipt; renderAnswers() }
            press(box)
            attentionCard.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    private var debugReceiver: android.content.BroadcastReceiver? = null   // debug builds only: unregistered in onDestroy
    private var listening = false                   // the microphone is open right now
    private var orderListenTries = 0                 // automatic re-listens on the order card
    private var kindChooser = false                  // the "what do you need on the way?" window is open
    private var routeEta: Int? = null                // minutes along the real route to the shop, once it is a waypoint
    private var activeShop: Shop? = null             // the shop of the order being followed (its position goes to the navigation)
    private var waypointAdded = false
    private var activeOrder: OrderReceipt? = null   // the order being made (paid), followed until it is picked up or cancelled
    private var lastOrderStatus = ""
    private var showAllShops = false                  // favourites are set, but the driver asked to see every shop for now

    private fun favourites(): Set<String> = getSharedPreferences("gildongmu", MODE_PRIVATE).getStringSet("fav_brands", emptySet())!!.toSet()
    private fun activeFavs(): Set<String> = if (showAllShops) emptySet() else favourites()

    /** Mark a brand as a favourite (or not): once there are favourites, only those brands are offered. */
    private fun toggleFavourite(brand: String) {
        val now = favourites().toMutableSet()
        val added = now.add(brand)
        if (!added) now.remove(brand)
        getSharedPreferences("gildongmu", MODE_PRIVATE).edit().putStringSet("fav_brands", now).apply()
        showAllShops = false
        flash(if (added) "♥ $brand · 단골 가게만 보여요" else "$brand · 단골 해제", "가는 길 픽업")
    }                         // one per order screen: a retried "결제" is not a second order

    /** Ask the server which partner shops are near the car's position (off the main thread). */
    private fun refreshShops(thenOpen: String? = null) {
        val here = currentPlace() ?: return
        if (shopLoading) return
        shopLoading = true
        io.execute {
            val raw = try { api.shopsNear(here.lat, here.lon, if (info.state == DriveState.MOVING) here.heading else null, Errands.KEEP_KM) } catch (_: Exception) { null }
            ui.post {
                shopLoading = false
                val v = raw?.let { r ->
                    val ch = shopCache.update(r)   // who came into range, who was deleted
                    if (ch.entered.isNotEmpty() || ch.left.isNotEmpty())
                        android.util.Log.i(TAG, "shops in: ${ch.entered.joinToString { it.name }} · deleted: ${ch.left.joinToString { it.name }}")
                    shopCache.asView(r)
                }
                shopFailed = v == null
                if (v != null) shopView = v
                if (v == null) { if (thenOpen != null) flash("가까운 가게를 불러올 수 없어요 · 연결을 확인해 주세요", "가는 길 픽업"); return@post }
                if (thenOpen != null) openList(thenOpen) else if (order == null && note == null) renderAnswers()
            }
        }
    }

    /** The idle slot: what the driver can ask for, from the shops in range. While driving: only what to say. */
    private fun renderErrands() {
        if (order != null) return
        activeOrder?.let { renderTracking(it); return }   // an order is on its way: follow it instead of offering new ones
        if (!touchOk()) {   // driving: nothing that cannot be used is shown; only what to say
            answersList.addView(text("운전 중이에요 · 말로 해 보세요", 18f, t.ink, 2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            answersList.addView(text("“커피 마시고 싶어” · “앞에 드라이브스루 뭐 있어?”", 16f, t.accent, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            // nothing is taken away while driving: what is on offer stays on screen, to be asked for by voice
            val v = shopView
            if (v == null) { if (!shopLoading) refreshShops() }
            else {
                val kinds = Errands.kindsIn(v, activeFavs())
                answersList.addView(text(if (kinds.isEmpty()) "${v.rangeKm.toInt()}km 안에는 받을 수 있는 곳이 없어요"
                    else "지금 ${v.rangeKm.toInt()}km 안: " + kinds.joinToString(" · ") { Errands.label(it) }, 15f, t.ink, 2),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            }
            return
        }
        val here = currentPlace()
        if (here == null) {   // no position yet: nothing to measure from
            answersList.addView(text("위치를 확인하고 있어요", 15f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            return
        }
        val v = shopView
        if (v == null) {
            if (!shopLoading) refreshShops()
            answersList.addView(text(if (shopFailed) "가까운 가게를 불러올 수 없어요" else "가까운 가게를 찾고 있어요", 15f, t.muted, 1),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            return
        }
        val favs = activeFavs()
        val kinds = Errands.kindsIn(v, favs)
        val km = v.rangeKm.toInt()
        if (kinds.isEmpty()) {   // nothing inside the range: say so, show no buttons
            answersList.addView(text(if (favs.isEmpty()) "${km}km 안에 받을 수 있는 곳이 없어요" else "${km}km 안에 단골 가게가 없어요", 18f, t.ink, 2),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            if (favs.isNotEmpty()) answersList.addView(actionBtn("전체 가게 보기", t.surface, t.muted, 15f) { showAllShops = true; renderAnswers() },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            else v.nearestName?.let {
                answersList.addView(text("가장 가까운 곳은 $it · ${"%.1f".format(v.nearestKm)}km", 14f, t.muted, 1),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }
            return
        }
        // one button, not one per shop kind: touching it starts the same talk the home mic starts
        answersList.addView(text("${km}km 안 제휴 가게(샘플)", 13f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        answersList.addView(actionBtn("음성으로 주문·결제", t.accent, Color.WHITE, 18f) { openKindChooser() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        if (favourites().isNotEmpty()) {   // the driver has favourites: say which view this is, and let them switch
            answersList.addView(text(if (showAllShops) "전체 가게를 보는 중 · ★ 단골만 보기" else "★ 단골 가게만 보는 중 · 전체 보기", 14f, t.accent, 1).apply {
                setPadding(0, dp(8), dp(8), dp(4))
                setOnClickListener { showAllShops = !showAllShops; renderAnswers() }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }
    }

    /**
     * One tap, no choosing: the nearest shop that has what was asked for, with one item.
     * "다른 곳" shows the next shop; the shop's phone number is part of its data, shown on the card.
     */
    /** "가는 길에 뭐 필요해?": a small window with what can be had within range, read out loud, picked with one word. */
    private fun openKindChooser() {
        val v = shopView
        if (v == null) { refreshShops(); flash("가까운 가게를 찾고 있어요 · 잠시 뒤 다시 말해 주세요", "가는 길 픽업"); return }
        val kinds = Errands.kindsIn(v, activeFavs())
        if (kinds.isEmpty()) {
            val msg = "${v.rangeKm.toInt()}km 안에는 받을 수 있는 곳이 아직 없어요"
            flash(msg, "가는 길 픽업"); speak(msg); return
        }
        pickedCar = null; profileOpen = false; payOpen = false; pendingReport = null; order = null
        kindChooser = true
        speakThen("무엇을 주문하시겠어요? " + kinds.joinToString(", ") { Errands.label(it) } + " 중에 말하거나 가게 이름을 말해 주세요", "confirm")
        renderAnswers()
    }

    private fun renderKindChooser() {
        val v = shopView
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        attentionCard.addView(text("무엇을 주문하시겠어요?", 20f, t.ink, 2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        attentionCard.addView(text(if (listening) "듣고 있어요 · 가게 이름이나 “커피”처럼 말해 보세요" else "말해 보세요 · 예: “근처 스타벅스” “커피”", 14f, if (listening) t.accent else t.muted, if (listening) 2 else 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val kinds = if (v == null) emptyList() else Errands.kindsIn(v, activeFavs())
        val row = LinearLayout(this)
        kinds.forEach { kind ->
            row.addView(actionBtn(Errands.label(kind), t.surface, t.ink, 17f) { openList(kind) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (row.childCount > 0) leftMargin = dp(8) })
        }
        attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        attentionCard.addView(actionBtn("닫기", t.surface, t.muted, 16f) { kindChooser = false; renderAnswers() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun openList(key: String) {
        kindChooser = false
        android.util.Log.i(TAG, "openList $key shopView=${shopView != null} state=${info.state}")
        val v = shopView
        if (v == null) {
            if (currentPlace() == null) {   // no position yet: say so, instead of staying silent
                val msg = "아직 위치를 몰라요 · 잠시 뒤에 다시 말해 주세요"
                flash(msg, "가는 길 픽업"); if (!info.canSend || !touchOk()) speak(msg)
                return
            }
            refreshShops(key); return
        }
        val list = Errands.picks(v, key, activeFavs())
        pickedCar = null; profileOpen = false; pendingReport = null; order = null
        if (list.isEmpty()) {
            alts = emptyList()
            val msg = if (activeFavs().isEmpty()) "${v.rangeKm.toInt()}km 안에 ${Errands.label(key)}을 받을 수 있는 곳이 없어요"
                else "단골 가게 중에는 ${Errands.label(key)}을 받을 곳이 5km 안에 없어요 · 전체 보기를 눌러 보세요"
            flash(msg, "가는 길 픽업")
            if (!info.canSend || !touchOk()) speak(msg)
            return
        }
        alts = list; altIdx = 0
        openOrder(list[0])
    }

    /** "근처 해뜰커피 알려줘" / "근처 주유소에 파는 거 알려줘": the shop opens, and what it sells is read out. Price and payment follow by voice. */
    private fun openShopListing(shop: Shop) {
        pickedCar = null; profileOpen = false; payOpen = false; pendingReport = null; kindChooser = false
        val first = shop.menu.firstOrNull { it.simple } ?: shop.menu.firstOrNull() ?: return
        alts = listOf(Pick(shop, first)); altIdx = 0
        order = Pick(shop, first); orderKey = java.util.UUID.randomUUID().toString()
        speakThen(Errands.sellingSpoken(shop), "confirm")
        renderAnswers()
    }

    private fun nextShop() {
        if (alts.size < 2) return
        altIdx = (altIdx + 1) % alts.size
        openOrder(alts[altIdx])
    }

    private fun openOrder(p: Pick) {
        order = p
        orderKey = java.util.UUID.randomUUID().toString()
        orderListenTries = 0
        speakThen(Errands.spoken(p), "confirm")   // voice and touch both: the card is read out and then listened to, moving or stopped
        renderAnswers()
    }

    /** One look: where, which item, how much, when it will be ready. The payment is a mock: nothing is charged. */
    private var payAsk: Pick? = null   // the "pay now?" window is open for this very pick (a new pick closes it by itself)
    private fun confirming() = order != null && payAsk === order

    /** "결제": not paid yet: the window shows what is bought and how, the voice asks once more, a spoken or touched yes pays. */
    private fun askPay() {
        val p = order ?: return
        if (payToken() == null) { payOrder(); return }   // no card on file: says what to do
        payAsk = p; orderListenTries = 0
        speakThen("${p.shop.name}, ${p.item.name} ${p.item.priceText()}, ${payLabel()}로 결제할까요? 결제, 아니요 중에 말해 주세요", "confirm")
        renderAnswers()
    }

    private fun renderPayConfirm(p: Pick) {
        val s = p.shop
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        attentionCard.addView(text("결제할까요?", 20f, t.ink, 2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        attentionCard.addView(text("${s.name} · ${p.item.name}", 17f, t.ink, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        attentionCard.addView(text(p.item.priceText(), 28f, t.accent, 2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        attentionCard.addView(text("${payLabel()} · 도착 ${s.etaMin}분 뒤 받기", 14f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        attentionCard.addView(text("시험용이라 실제로 돈이 나가지 않아요", 13f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        if (info.canSend && touchOk()) {
            val row = LinearLayout(this)
            row.addView(actionBtn("결제", t.accent, Color.WHITE, 18f) { payOrder() }, LinearLayout.LayoutParams(0, -2, 1.4f))
            row.addView(actionBtn("돌아가기", t.surface, t.muted, 16f) { payAsk = null; renderAnswers() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        attentionCard.addView(text(if (listening) "듣고 있어요 · “결제” “아니요”" else "말로도 돼요 · “결제” “아니요”", 13f, if (listening) t.accent else t.muted, if (listening) 2 else 1),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
    }

    private fun renderOrder(p: Pick) {
        if (confirming()) { renderPayConfirm(p); return }
        val s = p.shop
        answersCount.text = ""
        attentionCard.removeAllViews()
        attentionCard.visibility = View.VISIBLE
        attentionCard.addView(text("${s.place} · 샘플 제휴 가게 · 시험용이라 실제 결제는 되지 않아요", 13f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        // the shop's name with the usual heart on the right: tap it to keep this brand as a favourite
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(text("${s.name} · ${"%.1f".format(s.km)}km", 20f, t.ink, 2), LinearLayout.LayoutParams(0, -2, 1f))
        val fav = s.brand in favourites()
        titleRow.addView(text(if (fav) "♥" else "♡", 28f, if (fav) t.moving else t.muted, 0).apply {
            gravity = Gravity.CENTER
            if (touchOk() && info.canSend) { setOnClickListener { toggleFavourite(s.brand) }; press(this) }
            contentDescription = if (fav) "단골 해제" else "단골로 등록"
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        attentionCard.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        attentionCard.addView(text("${p.item.name} · ${p.item.priceText()}", 17f, t.ink, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        attentionCard.addView(text("도착 ${s.etaMin}분 뒤 · " + (if (s.cookInMin > 0) "${s.cookInMin}분 뒤 조리 시작" else "바로 조리 시작") + " · ☎ ${s.phone}", 14f, t.muted, 1),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        attentionCard.addView(text(if (payToken() != null) "결제: ${payLabel()} · ${p.item.priceText()}" else "결제 수단을 등록하면 결제할 수 있어요 (정차 중에 등록해요)", 13f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        if (s.menu.size > 1 && info.canSend && touchOk()) {
            attentionCard.addView(text("메뉴", 13f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            s.menu.take(4).forEach { m ->
                val mr = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = if (m == p.item) fill(t.navyFill, 10) else outline()
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener { order = Pick(s, m); orderKey = java.util.UUID.randomUUID().toString(); renderAnswers() }; press(this)
                }
                val fg = if (m == p.item) Color.WHITE else t.ink
                mr.addView(text(m.name, 16f, fg, 2).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
                mr.addView(text(m.priceText(), 15f, fg, 1))
                attentionCard.addView(mr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }
        if (!info.canSend || !touchOk()) {
            attentionCard.addView(text("“결제”라고 말하면 바로 주문해요 · 다른 메뉴는 “메뉴”", 13f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            return
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (payToken() == null) row.addView(actionBtn("결제 수단 등록", t.accent, Color.WHITE, 17f) { openPayments() }, LinearLayout.LayoutParams(0, -2, 1.6f))
        else row.addView(actionBtn("결제하기", t.accent, Color.WHITE, 18f) { payOrder() }, LinearLayout.LayoutParams(0, -2, 1.6f))
        if (alts.size > 1) row.addView(actionBtn("다른 곳", t.surface, t.muted, 16f) { nextShop() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        row.addView(actionBtn("닫기", t.surface, t.muted, 16f) { cancelOrder() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val voiceLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        voiceLine.addView(text(if (listening) "듣고 있어요 · “결제” “크로와상” “닫기”" else "말로도 돼요 · “결제” “크로와상” “닫기”", 13f, if (listening) t.accent else t.muted, if (listening) 2 else 1),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (!listening) voiceLine.addView(profileChip("다시 말하기", false, compact = true) { orderListenTries = 0; startVoice() }, LinearLayout.LayoutParams(dp(120), -2))   // only a backup
        attentionCard.addView(voiceLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    /** The order made, as the slot shows it: where it is, when the car arrives, and a way to cancel while that is still possible. */
    private fun renderTracking(o: OrderReceipt) {
        answersList.addView(text("${o.shop} · ${o.item}", 16f, t.ink, 2).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        // status and the one action on the same line: the card is short
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        line.addView(text(o.statusLabel + (if (!o.finished) " · 도착 ${o.etaMin}분 뒤" + (if (o.delayMin > 0) " · 막혀서 +${o.delayMin}분" else "") else ""),
            15f, t.accent, 2).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        answersList.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val hint = when {
            waypointAdded -> "내비 경유지에 넣었어요" + (routeEta?.let { " · 길 따라 ${it}분" } ?: "")
            trip != null && !o.finished && activeShop != null -> "“경유지”라고 말하면 내비 길에 넣어요"
            o.status == "cooking" -> "결제한 주문은 취소가 안 돼요 · 문의 ☎ ${o.phone}"
            o.status == "ready" -> "도착하면 바로 받으세요"
            else -> "접수번호 ${o.receipt}"
        }
        answersList.addView(text(hint, 13f, t.muted, 1).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
    }

    /** Follow the order made: ask the server how it is going. A change is spoken; a held-up car moves the arrival time later. */
    private fun trackActiveOrder() {
        val o = activeOrder ?: run { restoreActiveOrder(); return }
        val here = currentPlace() ?: return
        io.execute {
            if (waypointAdded) navi?.refreshWaypoint()
            val r = try { api.trackOrder(o.receipt, here.lat, here.lon, if (waypointAdded) routeEta else null) } catch (_: Exception) { null }
            ui.post {
                if (r == null || activeOrder?.receipt != o.receipt) return@post
                val changed = r.status != lastOrderStatus
                val later = r.delayMin >= o.delayMin + 2
                activeOrder = r
                if (changed) {
                    lastOrderStatus = r.status
                    speak(when (r.status) {
                        "accepted" -> "${r.shop}이 주문을 받았어요"
                        "cooking" -> "조리를 시작했어요"
                        "ready" -> "준비가 끝났어요. 도착하면 바로 받으세요"
                        "picked_up" -> "수령이 끝났어요. 맛있게 드세요"
                        "cancelled" -> "주문이 취소됐어요"
                        else -> r.statusLabel
                    })
                } else if (later && !r.finished) speak("길이 막혀 도착이 ${r.delayMin}분 늦어졌어요. 가게에 알렸어요")
                if (r.finished) { forgetActiveOrder(); ui.postDelayed({ if (activeOrder?.receipt == r.receipt) { activeOrder = null; renderAnswers() } }, 8_000) }
                if (order == null && note == null && answers.isEmpty() && questions.isEmpty() && roadMessages.isEmpty()) renderAnswers()
            }
        }
    }

    /** After a restart: pick the order up again from the server, if it is still going. */
    private fun restoreActiveOrder() {
        val prefs = getSharedPreferences("gildongmu", MODE_PRIVATE)
        val receipt = prefs.getString("active_receipt", null) ?: return
        io.execute {
            val r = try { api.trackOrder(receipt, null, null) } catch (_: Exception) { null }
            ui.post {
                if (r == null) return@post
                if (r.finished) { prefs.edit().remove("active_receipt").apply(); return@post }
                if (activeOrder == null) { activeOrder = r; lastOrderStatus = r.status; renderAnswers() }
            }
        }
    }

    private fun forgetActiveOrder() = getSharedPreferences("gildongmu", MODE_PRIVATE).edit().remove("active_receipt").apply()

    /** The shop becomes a stop on the car's own navigation route: the route, not a straight line, then decides when the shop cooks. */
    private fun addShopWaypoint() {
        val o = activeOrder ?: return
        val shop = activeShop ?: Shop("", o.shop, o.shop, "", 0.0, o.etaMin, 0, o.cookInMin, o.phone, emptyList(), o.shopLat, o.shopLon)   // an order restored after a restart has no Shop: use the order's own data
        if (shop.lat == 0.0 && shop.lon == 0.0) { speak("가게 위치를 몰라요"); return }
        if (waypointAdded) { speak("이미 내비 경유지에 있어요"); return }
        if (trip == null) { speak("내비 안내 중일 때 경유지로 넣을 수 있어요"); return }
        val ok = navi?.addWaypoint(shop.lat, shop.lon, shop.name) == true
        if (ok) { waypointAdded = true; speak("${shop.name}을 내비 경유지에 넣었어요"); ui.postDelayed({ navi?.refreshWaypoint() }, 2_500) }
        else speak("지금은 경유지로 넣을 수 없어요")
        renderAnswers()
    }

    /** Pay: the order goes to the server, which records it for the shop and answers with a receipt number. */
    private fun payOrder() {
        val p = order ?: return
        val here = currentPlace()
        if (here == null) { flash("아직 위치를 몰라요", "가는 길 픽업"); return }
        val token = payToken()
        if (token == null) {   // no card yet: it is registered once, while stopped (never while driving)
            val msg = "먼저 결제 수단을 등록해 주세요. 차를 세운 뒤 화면에서 등록할 수 있어요"
            flash(msg, "결제 수단"); if (!info.canSend || !touchOk()) speak(msg)
            return
        }
        val key = orderKey
        io.execute {
            val r = try { api.orderShop(p.shop.id, p.item.name, here.lat, here.lon, key, token) } catch (_: Exception) { null }
            ui.post {
                if (order !== p) return@post   // cancelled meanwhile
                if (r == null) { flash("주문을 보내지 못했어요 · 연결을 확인해 주세요", "가는 길 픽업"); return@post }
                order = null; alts = emptyList()
                activeOrder = r; lastOrderStatus = r.status; activeShop = p.shop; waypointAdded = false; routeEta = null
                getSharedPreferences("gildongmu", MODE_PRIVATE).edit().putString("active_receipt", r.receipt).apply()   // survives the app being restarted
                speak("주문했어요. 접수번호 ${r.receipt}. 가게에 알렸어요")
                flash("${r.shop} · ${r.item} · ${"%,d원".format(r.price)} · 접수번호 ${r.receipt} · " +
                    (if (r.cookInMin > 0) "${r.cookInMin}분 뒤 조리 시작 예정" else "바로 조리 시작") + " · 시험용, 실제 결제 없음", "주문 접수", 8_000)
            }
        }
    }

    private fun cancelOrder() {
        order = null; alts = emptyList(); payAsk = null
        flash("주문을 취소했어요", "가는 길 픽업")
    }

    private fun answerOrder(said: String) {
        val w = said.replace(" ", "")
        val p = order ?: return
        if (confirming()) {   // the pay window is open: only a yes pays, a no goes back
            when {
                listOf("아니", "취소", "그만", "닫", "돌아", "괜찮", "됐어").any { it in w } -> { payAsk = null; speakThen("결제하지 않았어요. 다른 메뉴나 다른 곳을 말하거나 닫기라고 해 주세요", "confirm"); renderAnswers() }
                Errands.isPayWord(said) -> payOrder()
                else -> speakThen("결제, 아니요 중에 말해 주세요", "confirm")
            }
            return
        }
        when {
            listOf("취소", "그만", "아니", "됐어", "괜찮", "닫").any { it in w } -> cancelOrder()
            Errands.itemNamed(p.shop, said) != null && Errands.itemNamed(p.shop, said) != p.item -> {   // "아이스 라떼로": another item of this shop
                val m = Errands.itemNamed(p.shop, said)!!
                order = Pick(p.shop, m); orderKey = java.util.UUID.randomUUID().toString()
                speakThen(if (listOf("얼마", "가격", "몇원").any { it in w }) "${m.name} ${m.priceText()}이에요. 결제할까요?" else "${m.name} ${m.priceText()}으로 바꿨어요. 결제할까요?", "confirm")
                renderAnswers()
            }
            listOf("얼마", "가격", "몇원").any { it in w } -> speakThen("${p.item.name} ${p.item.priceText()}이에요. 결제할까요?", "confirm")
            listOf("메뉴", "뭐있", "뭐팔", "파는").any { it in w } -> speakThen(Errands.menuSpoken(p.shop), "confirm")
            listOf("단골", "즐겨찾기").any { it in w } -> toggleFavourite(p.shop.brand)
            listOf("다른", "다음", "딴").any { it in w } -> nextShop()
            Errands.isPayWord(said) -> askPay()
            else -> speakThen("결제, 메뉴, 다른 곳, 단골, 취소 중에 말해 주세요", "confirm")
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
                setPadding(dp(14), dp(14), dp(4), dp(14))
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
                setPadding(dp(14), dp(14), dp(2), dp(14))
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
        if (payOpen && !profileEditable()) payOpen = false           // and the payment card
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
        road.flowing = info.state != DriveState.PARKED
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

    private fun gearCode() = pleosVehicle?.gear ?: if (info.state == DriveState.PARKED) "P" else "D"   // the real gear from the Pleos Vehicle SDK when it answers

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
        if ((heardText != null && confirmByVoice) || nudge != null || hazard != null || order != null || kindChooser) voice?.listen()
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

    private var standingPopup: PopupWindow? = null

    /**
     * Tap the grade chip in the header: a small popover right under it, like the tier pop-ups of other apps.
     * Grade and points, a progress bar to the next grade, the four steps, and how points are earned.
     * It takes no room on the screen and closes with a tap anywhere else.
     */
    private fun showStanding() {
        standingPopup?.let { if (it.isShowing) { it.dismiss(); return } }
        val me = board.me
        val steps = GRADE_STEPS
        val idx = steps.indexOfLast { me.points >= it.first }.coerceAtLeast(0)
        val floor = steps[idx].first
        val next = steps.getOrNull(idx + 1)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(t.surface); cornerRadius = dp(16).toFloat(); setStroke(dp(1), t.border) }
            setPadding(dp(18), dp(16), dp(18), dp(16))
            elevation = dp(10).toFloat()
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(me.grade, 20f, t.ink, 2), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text("${me.points}점", 20f, t.accent, 2))
        box.addView(top)
        val fraction = if (next == null) 1f else ((me.points - floor).toFloat() / (next.first - floor)).coerceIn(0.04f, 1f)
        val bar = LinearLayout(this).apply { background = fill(t.surfaceAlt, 5) }
        bar.addView(View(this).apply { background = fill(t.accent, 5) }, LinearLayout.LayoutParams(0, -1, fraction))
        bar.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f - fraction + 0.0001f))
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(12) })
        box.addView(text(if (next != null) "다음 등급 ‘${next.second}’까지 ${next.first - me.points}점" else "가장 높은 등급이에요", 15f, t.ink, 1),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        val ladder = LinearLayout(this)
        steps.forEachIndexed { i, s ->
            ladder.addView(text(s.second.removePrefix("길 위 "), 13f, if (i <= idx) t.accent else t.muted, if (i == idx) 2 else 0).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        box.addView(ladder, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        box.addView(text("올린 제보 ${me.posts}건 · 이웃이 “도움이 됐어요”를 누르면 올라요", 13f, t.muted),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        val w = dp(360)
        standingPopup = PopupWindow(box, w, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            showAsDropDown(pointsChip, pointsChip.width - w, dp(8))
        }
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
            res == null && presetCode == null -> ui.post { showNote("연결을 확인하면 보낼 수 있어요") }
            res == null -> ui.post { showNote("연결을 확인하면 보낼 수 있어요") }
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
        if (requestCode == 5 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && consented) startFused()
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
            reporting != null -> renderReportReasons(reporting)
            kindChooser -> renderKindChooser()
            payOpen -> renderPayments()
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
            row.addView(actionBtn("닫기", t.surface, t.muted) { cancelHeard() }, LinearLayout.LayoutParams(0, -2, 0.7f).apply { leftMargin = dp(8) })
            attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    // ---------------------------------------------------------------- my profile

    /** Where we are: the navigation's position while it is current, else the platform GPS (null: no position at all). */
    private var fakePlace: Place? = null   // debug builds only: --ef fake_lat / fake_lon, to try the pickup range without a drive

    private fun currentPlace(): Place? = fakePlace ?: pleosFused?.fresh() ?: navi?.fresh() ?: gps.fresh()

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
        if (compact) setPadding(dp(4), dp(14), dp(4), dp(14)) else setPadding(dp(14), dp(14), dp(14), dp(14))
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
        names.addView(text(if (myModel.isBlank()) "아직 차를 고르지 않았어요" else "$myModel · 이웃에게 보여요", 13f, t.muted),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
        who.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        who.addView(actionBtn("이름 변경", t.surface, t.ink, 15f) {
            val before = myNick()
            do { myAdj = ADJECTIVES.random(); myAnimal = ANIMALS.random() } while (myNick() == before)   // always a new one
            applyProfile()
        }, LinearLayout.LayoutParams(-2, -2))
        attentionCard.addView(who, lp(6))

        // the Hyundai model: one tap on a chip. All twelve are on screen at once (4 x 3): no sideways scrolling to fight with
        attentionCard.addView(text("나의 차량", 13f, t.muted, 1), lp(12))
        MODELS.chunked(4).forEachIndexed { r, chunk ->
            val row = LinearLayout(this)
            chunk.forEachIndexed { i, name ->
                row.addView(profileChip(name, name == myModel, compact = true) { myModel = if (myModel == name) "" else name; applyProfile() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
            attentionCard.addView(row, lp(if (r == 0) 6 else 8))
        }

        // the payment method, set once while stopped: the car keeps only a token (mock here)
        val payRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        payRow.addView(text("결제 수단 · " + (defaultMethod()?.label() ?: "아직 없어요"), 14f, t.muted, 1).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        payRow.addView(profileChip("결제·주문 내역", payToken() == null, compact = true) { openPayments() }, LinearLayout.LayoutParams(dp(150), -2))
        attentionCard.addView(payRow, lp(10))

        // privacy and done
        val foot = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        foot.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (!getSharedPreferences("gildongmu", MODE_PRIVATE).getBoolean(PROFILE_KEY, false))
            foot.addView(actionBtn("건너뛰기", t.surface, t.muted, 16f) { profileDone() }, LinearLayout.LayoutParams(dp(120), -2).apply { rightMargin = dp(8) })
        foot.addView(actionBtn("완료", t.accent, Color.WHITE, 16f) { profileDone() },
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
        attentionCard.addView(actionBtn("닫기", t.surface, t.muted, 15f) { pendingReport = null; renderAnswers() },
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
        // one thing to do (ask this car: the mic opens at once), and two small ways out
        attentionCard.addView(actionBtn("이 차에 물어보기", t.accent, Color.WHITE, 20f) { askPicked(car) }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(14) })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(actionBtn("신고", t.surface, t.muted, 15f) { startReport(car.nickname, car.id) { reason -> reportPicked(car, reason) } }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(actionBtn("닫기", t.surface, t.muted, 15f) { pickedCar = null; renderAnswers() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
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
        private const val FUSED_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val INTRO_KEY = "intro_v1"       // the first-run explanation was shown
        private const val PROFILE_KEY = "profile_done"  // the profile was finished or skipped once
        private const val CONSENT_KEY = "consent_v2"   // bump the number when what is collected changes: everyone is asked again
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
