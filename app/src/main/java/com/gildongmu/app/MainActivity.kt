package com.gildongmu.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
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

    internal val ui = Handler(Looper.getMainLooper())

    internal lateinit var t: Theme

    internal lateinit var monitor: DrivingMonitor

    internal var tts: TextToSpeech? = null

    internal var ttsReady = false

    internal var voice: VoiceInput? = null

    internal var info = DriveInfo(DriveState.UNKNOWN, false, 0, null)

    internal var forcedInfo: DriveInfo? = null   // debug builds only (see force_state)

    // how long we have been crawling or standing: a human measure the navigation does not have
    internal var jamSince: Long? = null

    internal var fastSince: Long? = null

    internal var navi: NaviClient? = null

    internal var destName: String? = null

    internal var trip: Trip? = null

    internal var board = BoardView(emptyList(), Standing(0, "길 위 새싹", 30, 0))

    internal val skipped = mutableSetOf<Int>()        // reports whose "맞아요 / 고마워요" prompt ran out or was answered

    internal val promptSeen = mutableSetOf<Int>()     // prompts that already started their countdown

    internal val announced = mutableSetOf<Int>()      // urgent reports we already read out loud

    internal var questions: List<QuestionItem> = emptyList()

    internal val announcedQ = mutableSetOf<Int>()     // questions that were already read out loud

    internal var roadMessages: List<RoadMessage> = emptyList()

    internal val announcedMsg = mutableSetOf<Int>()   // messages that were already read out loud

    internal var hazard: HazardProposal? = null       // "the car sees something: report it?"

    internal var hazardToken = 0

    internal val gps by lazy { PlatformLocation(this) }

    internal var pleosVehicle: PleosVehicleClient? = null   // Pleos Vehicle SDK: the gear and the brake pedal

    internal var pleosFused: PleosFusedClient? = null       // Pleos FusedLocation SDK: the car's own position (after consent)

    internal var hazardByDriver = false

    internal var hazardKey = ""                       // our own id for the report on screen: pressing again after a slow answer is not a second report

    internal var hazardSeenAt = 0L                    // when the driver saw it (the server also notes when it arrived)

    internal var hazardFailed = false                 // the last try did not get through: the card is back, with a hint

    internal var hazardPhoto: ByteArray? = null          // one frame taken when the hazard was detected; sent only if the driver reports

    internal val hazardCamera by lazy { HazardCamera(this) }

    internal var answerTarget: Int? = null            // the question a spoken answer is for (null = a message to a car)

    internal var pickedCar: Neighbor? = null          // a car the driver tapped on the road: ask it something, or report it

    internal var pickedToken = 0

    internal var pendingReport: PendingReport? = null // "신고" was tapped: the driver is picking the reason (nothing is filed yet)

    internal var askCar: Neighbor? = null             // the car the next spoken question goes to (null = everyone around)

    internal var askCarAt = 0L

    internal var lastPosLog = 0L

    // the driver's own profile: a name built from two lists, a colour and a Hyundai model; nothing is typed
    internal var myAdj = ADJECTIVES.random()

    internal var myAnimal = ANIMALS.random()

    internal var myColor = COLORS[0]

    internal var myModel = ""

    internal var myShowModel = true

    internal var profileOpen = false

    internal lateinit var profileAvatar: TextView

    internal lateinit var profileName: TextView

    internal lateinit var profileSub: TextView

    internal var turn: Turn? = null                   // the navigation's next manoeuvre (null: no route, or none reported)

    internal lateinit var turnBar: LinearLayout       // above the road, never over it: the turn and the arrival time

    internal lateinit var turnArrow: TextView

    internal lateinit var turnMain: TextView

    internal lateinit var turnSub: TextView

    internal lateinit var etaLine: TextView

    internal val jam = JamDetector()                 // decides when the car may offer "왜 막힐까요?"

    internal var nudge: JamReason? = null             // the offer on screen right now

    internal var nudgeToken = 0

    internal var bubbleToken = 0

    internal var answersToken = 0

    internal val answeredCars = mutableMapOf<String, String>()   // neighbour id -> cause it named (lights the car up)

    internal lateinit var whyButton: LinearLayout

    internal lateinit var whyTitle: TextView

    internal lateinit var whySub: TextView

    internal lateinit var pointsChip: TextView

    internal var themePref = "auto"

    internal lateinit var ctaRow: LinearLayout

    internal lateinit var attentionCard: LinearLayout

    internal var neighbors: List<Neighbor> = emptyList()

    internal var neighborsKnown = false

    internal var online = true

    // backend (server/app_server.py); falls back to local demo data if unreachable
    internal lateinit var api: ApiClient

    internal val io = java.util.concurrent.Executors.newFixedThreadPool(3)   // a slow call must not make a report or a tap wait behind it

    internal val pollIo = java.util.concurrent.Executors.newSingleThreadExecutor()   // polling never waits behind a slow send

    @Volatile internal var pollBusy = false

    internal val pollTick = Runnable { pollLoop() }   // one named runnable, so a pause/resume can never leave two loops running

    internal var lastInboxId = 0

    internal var polling = false

    internal var inboxPrimed = false

    internal var tick = 0

    // the answers to the last question
    internal var answers = mutableListOf<Inbound>()

    internal var answersTitle = "도로 소식"

    internal var answerLimit = MAX_ANSWERS

    internal var note: String? = null

    internal var expandedId: Int? = null

    internal val thankedIds = mutableSetOf<Int>()

    internal val thankedPoints = mutableMapOf<Int, Int>()   // message id -> points the neighbour got from our thanks

    internal var asking = false              // a question is out and nobody has answered yet

    internal var askToken = 0

    // what the recogniser heard, held back until the driver says it is right (never sent unconfirmed)
    internal var heardText: String? = null

    internal var order: Pick? = null                // a (sample) pickup waiting for the driver to pay or cancel

    internal var alts: List<Pick> = emptyList()      // the shops of the asked kind in range, nearest first; "다른 곳" steps through them

    internal var altIdx = 0

    internal var confirmByVoice = false   // true while we wait for "보내"/"다시"/"취소" said out loud

    // views
    internal lateinit var road: RoadView

    internal lateinit var stateDot: View

    internal lateinit var stateText: TextView

    internal lateinit var stateSub: TextView

    internal lateinit var liveChip: TextView

    internal lateinit var crowdText: TextView

    internal lateinit var bannerCard: LinearLayout

    internal lateinit var stoppedArea: LinearLayout

    internal lateinit var tilesRow: LinearLayout

    internal lateinit var answersCard: LinearLayout

    internal lateinit var answersHeader: TextView

    internal lateinit var answersCount: TextView

    internal lateinit var answersList: LinearLayout

    internal lateinit var voiceBar: LinearLayout

    internal lateinit var voiceWave: WaveView

    internal lateinit var voiceHero: LinearLayout

    internal val tileViews = mutableListOf<Triple<LinearLayout, ImageView, TextView>>()

    internal val fRegular by lazy { ResourcesCompat.getFont(this, R.font.pretendard_regular) ?: Typeface.DEFAULT }

    internal val fSemi by lazy { ResourcesCompat.getFont(this, R.font.pretendard_semibold) ?: Typeface.DEFAULT_BOLD }

    internal val fBold by lazy { ResourcesCompat.getFont(this, R.font.pretendard_bold) ?: Typeface.DEFAULT_BOLD }

    internal val informTiles by lazy {
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

    // while another app is in front there is nobody to show the road to: stop asking the server
    override fun onPause() { polling = false; super.onPause() }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) debugCommand(intent)
    }

    override fun onResume() {
        super.onResume()
        if (consented && !polling && !isDestroyed) { polling = true; ui.removeCallbacks(pollTick); pollLoop() }
    }

    // ---------------------------------------------------------------- privacy: what is used, and the driver's yes

    internal var consented = false

    internal var consentView: View? = null

    internal var introView: View? = null

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

    internal val shopCache = ShopCache()             // shops held now: in at 5 km, deleted beyond 6 km, memory only

    internal var shopView: ShopsView? = null          // what the server last said is near the car

    internal var shopLoading = false

    internal var shopFailed = false

    internal var orderKey = ""

    internal var payOpen = false                   // the payment-methods and order-history card is open (stopped only)

    internal var payTab = "live"                    // "live" or "done"

    internal var payHistory: List<OrderRow> = emptyList()

    internal var payExpanded: String? = null        // the receipt whose payment detail is open

    internal var resumeOrder: Pick? = null          // the order card to come back to after a payment method was registered

    internal var debugReceiver: android.content.BroadcastReceiver? = null   // debug builds only: unregistered in onDestroy

    internal var listening = false                   // the microphone is open right now

    internal var orderListenTries = 0                 // automatic re-listens on the order card

    internal var kindChooser = false                  // the "what do you need on the way?" window is open

    internal var routeEta: Int? = null                // minutes along the real route to the shop, once it is a waypoint

    internal var activeShop: Shop? = null             // the shop of the order being followed (its position goes to the navigation)

    internal var waypointAdded = false

    internal var activeOrder: OrderReceipt? = null   // the order being made (paid), followed until it is picked up or cancelled

    internal var lastOrderStatus = ""

    internal var showAllShops = false                  // favourites are set, but the driver asked to see every shop for now

    /** One look: where, which item, how much, when it will be ready. The payment is a mock: nothing is charged. */
    internal var payAsk: Pick? = null   // the "pay now?" window is open for this very pick (a new pick closes it by itself)

    internal var standingPopup: PopupWindow? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) voice?.listen()
        if (requestCode == 4 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) gps.start()
        if (requestCode == 5 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && consented) startFused()
    }

    // ---------------------------------------------------------------- my profile

    /** Where we are: the navigation's position while it is current, else the platform GPS (null: no position at all). */
    internal var fakePlace: Place? = null   // debug builds only: --ef fake_lat / fake_lon, to try the pickup range without a drive

    internal val CAR_QUESTIONS = listOf("왜 막혀요?", "사고 났나요?", "얼마나 더 막혀요?", "다른 길이 나을까요?")

    // lateinit 확인(::x.isInitialized)은 선언한 클래스 안에서만 되므로, 다른 파일의 확장 함수가 쓸 수 있게 여기서 열어 둡니다.
    internal val answersListReady get() = ::answersList.isInitialized
    internal val bannerCardReady get() = ::bannerCard.isInitialized
    internal val profileAvatarReady get() = ::profileAvatar.isInitialized
    internal val roadReady get() = ::road.isInitialized
    internal val stoppedAreaReady get() = ::stoppedArea.isInitialized
    internal val turnBarReady get() = ::turnBar.isInitialized
    internal val whyButtonReady get() = ::whyButton.isInitialized
    internal val whyTitleReady get() = ::whyTitle.isInitialized

    companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
    }
}
