package com.drivepet.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The pet's room. Only used while the car is parked (gear P):
 *  - the room with the pet walking around in it, and a few things that matter: level, distance together, points, the route
 *  - a menu card that slides in from the right: 캐릭터 정보 / 상점 / 인벤토리 / 설정
 * The moment the gear leaves P this screen closes by itself; only the small floating pet stays over the other screens
 * (see [PetOverlay]). The pet itself lives in [PetHub] / [PetService], so it keeps running meanwhile.
 *
 * The look (cream cards, periwinkle keys, the rounded display face) comes from [Look] / [Kit].
 */
class MainActivity : AppCompatActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private val engine get() = PetHub.engine
    private val kit by lazy { Kit(this) }

    // main screen
    private lateinit var pet: PetView
    private lateinit var hudLevel: TextView
    private lateinit var hudXp: PillBar
    private lateinit var hudKm: TextView
    private lateinit var darkBtn: TextView
    private lateinit var pointsChip: TextView
    private lateinit var hud: View
    private lateinit var topRight: View
    // the first-run introduction
    private lateinit var onboard: FrameLayout
    private lateinit var obBubble: TextView
    private lateinit var obNext: TextView
    private lateinit var obNameBox: LinearLayout
    private lateinit var obTop: LinearLayout
    private val nightRows = arrayOfNulls<TextView>(3)
    private lateinit var obNameInput: EditText
    private val obNamesList = listOf("몽이", "보리", "하루", "별이", "콩이", "두부", "모카", "초코")   // the first one is only a fallback
    private var obStep = 0
    private var obName: String? = null
    private lateinit var tripCard: LinearLayout
    private lateinit var tripHead: TextView
    private lateinit var tripBody: TextView
    private lateinit var scrim: View
    private var drawerOpen = false

    // the menu card
    private lateinit var drawer: LinearLayout
    private lateinit var avatar: AvatarView
    private lateinit var drawerName: TextView
    private lateinit var drawerLevel: TextView
    private lateinit var drawerXp: PillBar
    private lateinit var backRow: LinearLayout
    private lateinit var backTitle: TextView
    private lateinit var menuPage: LinearLayout
    private lateinit var infoPage: LinearLayout
    private lateinit var missionPage: LinearLayout
    private lateinit var shopPage: LinearLayout
    private lateinit var inventoryPage: LinearLayout
    private lateinit var settingsPage: LinearLayout
    private lateinit var devPage: LinearLayout
    private lateinit var devRow: View
    private lateinit var followRow: TextView
    private lateinit var overlayBox: LinearLayout
    private var gameView: FlapGameView? = null
    private var dexLayer: DexLayer? = null
    private var askedLocation = false
    private lateinit var quickBar: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var overlayState: TextView

    private companion object {
        const val REQ_LOCATION = 41
    }

    private enum class Page(val label: String) { MENU("메뉴"), INFO("캐릭터 정보"), MISSION("미션"), SHOP("상점"), INVENTORY("인벤토리"), SETTINGS("설정"), DEV("개발자") }
    private var page = Page.MENU
    private var pageKey = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val drawerW get() = dp(520)
    private val drawerMargin get() = dp(12)

    // cream cards on top of the room; periwinkle for actions (the icon's car), cocoa ink for text
    private val ink = Look.INK
    private val sub = Look.SUB
    private val sky = Look.PERI
    private val navy = Look.PERI_DEEP

    /** how grown the pet is now: what it says, and how, depends on it (see [Voice]) */
    private val stageNow get() = if (PetHub.started) engine.snapshot().stage else GrowthStage.EGG

    private val reactionL: (Reaction) -> Unit = { r ->
        Voice.react(stageNow, r)?.let { say(it) }
        pet.burst(r.kind == Kind.GOOD_SIGNAL || r.kind == Kind.CALM || r.kind == Kind.LEVEL_UP || r.kind == Kind.TRIP_END)
        if (r.kind == Kind.TRIP_END) showPendingTrip()
        refresh()
    }
    private val tickL: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the service owns the pet; starting it twice is harmless
        PetHub.start(this)
        startForegroundService(Intent(this, PetService::class.java))
        Theme.night = Night.isDark(this)
        setContentView(buildUi())
        showPage(Page.MENU)
        if (!PetHub.onboarded) startOnboarding() else Voice.event(stageNow, Ev.GREET, fallback = "왔어? 보고 싶었어!")?.let { say(it) }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        PetHub.reactionListeners += reactionL
        PetHub.tickListeners += tickL
        PetHub.setAppVisible(true)
        refresh()
        showPendingTrip()
        if (PetHub.onboarded) askLocationOnce()
        closeIfNotAllowed()
    }

    override fun onStop() {
        closeGame(); closeDex()
        PetHub.reactionListeners -= reactionL
        PetHub.tickListeners -= tickL
        PetHub.setAppVisible(false)   // the service now shows the small floating pet
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            gameView != null -> closeGame()
            dexLayer != null -> { if (dexLayer?.back() != true) closeDex() }
            drawerOpen && page != Page.MENU -> showPage(Page.MENU)
            drawerOpen -> setDrawer(false)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /**
     * this screen is only used while parked: in any other gear it closes by itself and leaves only the small pet over the
     * other screens. (moveTaskToBack was not enough: the car's launcher kept showing the app, so the screen is really
     * closed. The pet itself lives in the service, so nothing is lost.)
     */
    private fun closeIfNotAllowed() {
        if (PetHub.started && !PetHub.screenAllowed() && !isFinishing) {
            // the very first start happens while the car is not parked: tell the driver instead of opening anything
            if (!PetHub.onboarded) notifyNeedPark()
            finishAndRemoveTask()
        }
    }

    private fun notifyNeedPark() {
        val nm = getSystemService(NotificationManager::class.java)
        // no heads-up pop-up (IMPORTANCE_LOW): Pleos does not allow one that is unrelated to driving
        nm.createNotificationChannel(NotificationChannel("start", "운전펫 시작 안내", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "start")
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setColor(Look.HONEY)
            .setContentTitle("운전펫을 시작하려면 주차해 주세요")
            .setContentText("P단에 넣으면 운전펫이 자동으로 열려요.")
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }

    // ---------------------------------------------------------------- the first-run introduction

    private fun obLine(): String = when (obStep) {
        0 -> "안녕! 나는 앞으로 너의 길동무가 되어줄 친구야."
        1 -> "안전하게 운전할수록 운전 점수가 쌓이고, 그 점수가 포인트가 돼."
        2 -> "그 포인트로 상점에서 옷이랑 방 소품을 사서 나를 꾸며 줄 수 있어."
        3 -> if (obName == null) "그런데 나는 아직 이름이 없어. 내 이름을 지어줄래?" else "‘${obName}’ 어때? 마음에 들면 아래 버튼을 눌러 줘."
        else -> "${obName}! 마음에 쏙 들어. 이제부터 같이 달려 보자!"
    }

    private fun startOnboarding() {
        obStep = 0; obName = null
        obNameInput.setText("")
        onboard.visibility = View.VISIBLE
        pet.stayStill = true
        showObStep()
        placeOnboard()
        refresh()   // hides the top bars and the quick buttons while the introduction is shown
    }

    /** the introduction's column stands right above the egg's head, so the egg is the one who seems to speak */
    private fun placeOnboard() {
        val h = root.height
        if (h <= 0) return
        val lp = obTop.layoutParams as FrameLayout.LayoutParams
        val want = (h - pet.petTopY() + dp(6)).toInt().coerceAtLeast(dp(20))
        if (lp.bottomMargin != want) { lp.bottomMargin = want; obTop.layoutParams = lp }
    }

    private fun showObStep() {
        obBubble.text = obLine()
        obNameBox.visibility = if (obStep == 3) View.VISIBLE else View.GONE
        obNext.text = when (obStep) { 3 -> "이 이름으로 정할래"; 4 -> "시작하기"; else -> "다음" }
        val enabled = obStep != 3 || obName != null
        obNext.alpha = if (enabled) 1f else 0.4f
        obNext.isEnabled = enabled
    }

    /** the name typed so far (blank = none yet); the bubble reads it back as you type */
    private fun chooseName(n: String?) { obName = n; showObStep() }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(obNameInput.windowToken, 0)
        obNameInput.clearFocus()
    }

    private fun onObNext() {
        when (obStep) {
            3 -> { hideKeyboard(); obStep = 4; pet.burst(true) }
            4 -> { finishOnboarding(); return }
            else -> obStep++
        }
        showObStep()
    }

    private fun finishOnboarding() {
        PetHub.completeOnboarding(obName ?: obNamesList.first())
        askLocationOnce()
        onboard.visibility = View.GONE
        pet.stayStill = false
        Voice.event(stageNow, Ev.NAMED, PetHub.petName, "${PetHub.petName}, 잘 부탁해!")?.let { say(it) }
        refresh()
    }

    /** the pet speaks: the words appear in a bubble beside it and move with it (this screen only) */
    private fun say(s: String) { pet.say(s) }

    // ---------------------------------------------------------------- the result of the last drive

    private val hideTripCard = Runnable { tripCard.animate().alpha(0f).setDuration(400).withEndAction { tripCard.visibility = View.GONE }.start() }

    private fun showPendingTrip() {
        val (t, left) = PetHub.tripCardNow() ?: return
        tripHead.text = "+${t.points}점"   // "이번 주행" is the ribbon above it
        val finds = PetHub.pendingFinds
        val dexLine = if (finds.isEmpty()) "" else "\n도감에 새로 담긴 것  " + finds.joinToString(", ") { it.item.name + (if (it.isNew) "" else "(중복)") }
        tripBody.text = "%.1fkm 주행 · 깜빡이 %d번%s\n안전운전 포인트와 경험치에 더해졌어요%s".format(t.km, t.good, if (t.clean) " · 부드러운 주행 보너스" else "", dexLine)
        tripCard.animate().cancel()
        tripCard.alpha = 0f; tripCard.visibility = View.VISIBLE
        tripCard.animate().alpha(1f).setDuration(300).start()
        ui.removeCallbacks(hideTripCard)
        ui.postDelayed(hideTripCard, left)
    }

    // ---------------------------------------------------------------- refresh

    private fun stateLabel(st: PetState) = when (st) { PetState.NORMAL -> "평상시"; PetState.ANGRY -> "화난"; PetState.HUNGRY -> "배고픈"; PetState.HURT -> "다친" }

    private fun stateHint(st: PetState) = when (st) {
        PetState.NORMAL -> "잘 지내고 있어요. 안전하게 달려 주세요."
        PetState.ANGRY -> "깜빡이 없이 돌거나 핸들을 급하게 돌려서 화가 났어요. 부드럽게 달리면 풀려요."
        PetState.HUNGRY -> "차의 연료나 배터리가 부족해요. 주유하거나 충전하면 괜찮아져요."
        PetState.HURT -> "거친 운전이 이어져서 다쳤어요. 부드럽게 달리면 천천히 나아요."
    }

    private fun refresh() {
        if (!PetHub.started) return
        if (!PetHub.screenAllowed()) { closeIfNotAllowed(); return }
        val s = engine.snapshot()
        val st = PetHub.petState
        pet.state = st
        pet.level = s.level
        pet.outfit = PetHub.outfit; pet.furniture = PetHub.furniture; pet.stage = s.stage

        val name = PetHub.petName
        hudLevel.text = levelLine(name, s.level)
        hudXp.progress = s.xpIntoLevel
        hudKm.text = "오늘 함께한 거리  %.1fkm".format(s.todayKm)
        pointsChip.text = "${s.points}P"
        darkBtn.text = if (Night.isDark(this)) "☀" else "🌙"
        val intro = onboard.visibility == View.VISIBLE
        hud.visibility = if (intro) View.GONE else View.VISIBLE
        topRight.visibility = if (intro) View.GONE else View.VISIBLE
        quickBar.visibility = if (intro) View.GONE else View.VISIBLE   // 미니게임·지도 도감 are not shown while the pet introduces itself and is named
        // the menu card's header
        avatar.state = st
        drawerName.text = name
        drawerName.visibility = if (name.isEmpty()) View.GONE else View.VISIBLE
        drawerLevel.text = "Lv.${s.level}"
        drawerXp.progress = s.xpIntoLevel

        updateNightRows()
        updateOption(followRow, "차 옆에서 따라가기", "내비를 켠 채 달리면 작은 펫이 지도의 차 옆에 있어요", PetHub.followCar)
        val canOverlay = Settings.canDrawOverlays(this)
        overlayBox.visibility = if (canOverlay) View.GONE else View.VISIBLE
        overlayState.text = "달리는 동안이나 다른 화면에서 작은 펫을 보려면 '다른 앱 위에 표시'를 허용해야 해요."

        // the lists are rebuilt only when something in them changed
        val key = "${s.points}|${s.earnedPoints}|${s.signalsToday}|${s.tripsToday}|${s.cleanTripsToday}|${s.claimedMask}|${s.totalKm}|${s.todayKm}|${PetHub.petName}|${s.tripCount}|${s.goodSignals}|${s.missedSignals}|${s.jerkyBrakes}|${s.harshTurns}|${s.level}|$st|" +
            "${PetHub.wardrobe.ownedCsv()}|${PetHub.wardrobe.equippedCsv()}|$page"
        if (key != pageKey) { pageKey = key; buildInfo(s, st); buildMission(s); buildShop(s.points); buildInventory() }
    }

    /** "몽이  Lv.3": the name in the display face, the level as a small periwinkle badge */
    private fun levelLine(name: String, level: Int): CharSequence {
        val sb = SpannableStringBuilder()
        if (name.isNotEmpty()) sb.append(name).append("  ")
        val st = sb.length
        sb.append("Lv.$level")
        sb.setSpan(BadgeSpan(Look.PERI_L, navy, kit.face, kit.dp(19f), kit.dp(10f)), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            say("이 화면에서는 설정을 열 수 없어요")
        }
    }

    // ---------------------------------------------------------------- the menu card

    private fun setDrawer(open: Boolean) {
        drawerOpen = open
        drawer.animate().translationX(if (open) 0f else (drawerW + drawerMargin).toFloat()).setDuration(220).start()
        if (open) scrim.visibility = View.VISIBLE
        scrim.animate().alpha(if (open) 1f else 0f).setDuration(220).withEndAction { if (!drawerOpen) scrim.visibility = View.GONE }.start()
    }

    private fun openMenu() { showPage(Page.MENU); setDrawer(true) }

    private fun showPage(p: Page) {
        page = p
        menuPage.visibility = if (p == Page.MENU) View.VISIBLE else View.GONE
        infoPage.visibility = if (p == Page.INFO) View.VISIBLE else View.GONE
        missionPage.visibility = if (p == Page.MISSION) View.VISIBLE else View.GONE
        shopPage.visibility = if (p == Page.SHOP) View.VISIBLE else View.GONE
        inventoryPage.visibility = if (p == Page.INVENTORY) View.VISIBLE else View.GONE
        settingsPage.visibility = if (p == Page.SETTINGS) View.VISIBLE else View.GONE
        devPage.visibility = if (p == Page.DEV) View.VISIBLE else View.GONE
        backRow.visibility = if (p == Page.MENU) View.GONE else View.VISIBLE
        backTitle.text = p.label   // the round back button in front of it is the arrow
        pageKey = ""
        if (PetHub.started) refresh()
    }

    // ---------------------------------------------------------------- the pages

    private fun kv(k: String, v: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(18), dp(9), dp(18), dp(9))
        addView(text(k, 18f, sub), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(v, 18.5f, ink, true))
    }

    /** a white card that holds rows, with a fine line between them */
    private fun group() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.row(18f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun LinearLayout.addRow(v: View) {
        if (childCount > 0) addView(View(context).apply { setBackgroundColor(0xFFF4EADD.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(14), 0, dp(14), 0) })
        addView(v)
    }

    private fun buildInfo(s: PetSnapshot, st: PetState) {
        infoPage.removeAllViews()
        val (bg, fg) = when (st) {
            PetState.NORMAL -> Look.SAGE_L to Look.SAGE_D
            PetState.ANGRY -> Look.CORAL_L to Look.CORAL_D
            PetState.HUNGRY -> Look.MUSTARD_L to Look.MUSTARD_D
            PetState.HURT -> Look.SLATE_L to Look.SLATE_D
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
            background = kit.card(20f, bg, Look.mix(bg, fg, 0.2f), 1.2f)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("지금 상태", 16f, sub, true))
                addView(title(stateLabel(st), 34f, fg), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                addView(text(stateHint(st), 17f, ink).apply { setLineSpacing(0f, 1.25f) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(AvatarView(context).apply { framed = false; state = st }, LinearLayout.LayoutParams(dp(80), dp(80)).apply { leftMargin = dp(8) })
        }
        infoPage.addView(card, LinearLayout.LayoutParams(-1, -2))
        infoPage.addView(heading("성장"))
        val grow = group()
        if (PetHub.petName.isNotEmpty()) grow.addRow(kv("이름", PetHub.petName))
        grow.addRow(kv("성장 단계", s.stage.label))
        GrowthStage.kmToNext(s.totalKm)?.let { grow.addRow(kv("다음 단계까지", "%.0fkm".format(it))) }
        grow.addRow(kv("레벨", "Lv.${s.level}"))
        grow.addRow(kv("다음 레벨까지", "${PetEngine.XP_PER_LEVEL - s.xpIntoLevel}"))
        grow.addRow(kv("오늘 함께한 거리", "%.1fkm".format(s.todayKm)))
        grow.addRow(kv("총 함께한 거리", "%.1fkm".format(s.totalKm)))
        grow.addRow(kv("주행 횟수", "${s.tripCount}번"))
        grow.addRow(kv("지금까지 모은 포인트", "${s.earnedPoints}P"))
        grow.addRow(kv("지금 가진 포인트", "${s.points}P"))
        infoPage.addView(grow, LinearLayout.LayoutParams(-1, -2))
        infoPage.addView(heading("운전 기록"))
        val drive = group()
        drive.addRow(kv("깜빡이를 켠 회전", "${s.goodSignals}번"))
        drive.addRow(kv("깜빡이 없이 회전", "${s.missedSignals}번"))
        drive.addRow(kv("덜컹 브레이크", "${s.jerkyBrakes}번"))
        drive.addRow(kv("급한 핸들", "${s.harshTurns}번"))
        infoPage.addView(drive, LinearLayout.LayoutParams(-1, -2))
    }

    private fun buildMission(s: PetSnapshot) {
        missionPage.removeAllViews()
        missionPage.addView(title("오늘의 미션", 28f, ink))
        missionPage.addView(text("하루가 지나면 새로 시작해요. 끝낸 미션은 받기를 눌러 포인트를 받아요.", 15f, sub), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); bottomMargin = dp(4) })
        for (m in Missions.all) missionPage.addView(missionRow(m, s))
    }

    private fun missionRow(m: Mission, s: PetSnapshot): View {
        val done = Missions.done(m, s)
        val took = Missions.claimed(m, s)
        val frac = (Missions.progress(m, s) / m.goal).coerceIn(0f, 1f)
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(m.title, 20f, ink, true))
            addView((if (done) bar(100, 0xFF9ED49A.toInt(), 0xFF5DAE65.toInt()) else bar(100, 0xFFA9C3F7.toInt(), 0xFF6F8FE6.toInt())).apply { progress = (frac * 100).toInt() },
                LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(11) })
            addView(text(Missions.label(m, s), 15f, sub, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
        val enabled = done && !took
        val btn = TextView(this).apply {
            text = when { took -> "받음"; done -> "받기 +${m.reward}P"; else -> "+${m.reward}P" }
            minWidth = dp(150); minHeight = dp(62)
            kit.key(this, when { enabled -> Kit.KEY_PRIMARY; took -> Kit.KEY_DONE; else -> Kit.KEY_QUIET }, 20f, 14f, 10f)
            if (took) { setCompoundDrawablesRelative(Glyphs.check(this@MainActivity, 14f, Look.SAGE_D), null, null, null); compoundDrawablePadding = dp(4) }
            if (enabled) setOnClickListener {
                if (PetHub.claimMission(m)) { Voice.event(stageNow, Ev.REWARD, "${m.reward}P", "${m.reward}P 받았어! 고마워")?.let { say(it) }; pet.burst(true) }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(14), dp(16))
            background = kit.row(20f)
            addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(14) })
            addView(btn)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) }
        }
    }

    /**
     * one line of the shop or the inventory: a picture of the item, its name and a line under it, and a key.
     * [secondary]: the quieter white key (take off / put away); [price]: the line under the name is a price, with a coin.
     */
    private fun itemRow(name: String, subText: String, btnText: String, enabled: Boolean, thumb: String? = null, secondary: Boolean = false,
                        subColor: Int = sub, price: Boolean = false, onClick: () -> Unit): View {
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(name, 21f, ink, true))
            addView(text(subText, 16f, if (price) Look.HONEY_DEEP else subColor, price).apply {
                if (price) { setCompoundDrawablesRelative(Glyphs.coin(this@MainActivity, 18f), null, null, null); compoundDrawablePadding = dp(6) }
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        }
        val btn = TextView(this).apply {
            text = btnText
            minWidth = dp(140); minHeight = dp(62)
            kit.key(this, when { !enabled -> Kit.KEY_QUIET; secondary -> Kit.KEY_SECONDARY; else -> Kit.KEY_PRIMARY }, 20f, 14f, 10f)
            if (enabled) setOnClickListener { onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(14), dp(12))
            background = kit.row(20f)
            if (thumb != null) addView(ItemThumb(context, thumb), LinearLayout.LayoutParams(dp(66), dp(66)).apply { rightMargin = dp(14) })
            addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
        }
    }

    private fun heading(t: String) = text(t, 16f, sub, true).apply { setPadding(dp(6), dp(20), 0, dp(6)) }

    private fun buildShop(points: Int) {
        val level = if (PetHub.started) PetHub.engine.level else 1
        shopPage.removeAllViews()
        shopPage.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = kit.card(20f, 0xFFFFF2D4.toInt(), 0xFFF6DFA9.toInt(), 1.2f)
            addView(ImageView(context).apply { setImageDrawable(Glyphs.coin(this@MainActivity, 46f)) }, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("안전운전 포인트", 16f, 0xFF8A5E1A.toInt(), true))
                addView(title("${points}P", 31f, Look.HONEY_DEEP))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        }, LinearLayout.LayoutParams(-1, -2))
        shopPage.addView(text("깜빡이를 잘 켜고 부드럽게 달릴수록 포인트와 레벨이 올라요. 레벨이 오르면 새 소품이 열려요", 16f, sub).apply { setPadding(dp(4), dp(8), 0, 0) })
        for (slot in Slot.values()) {
            shopPage.addView(heading(slot.label))
            for (item in Catalog.bySlot(slot)) {
                val owned = PetHub.wardrobe.has(item.id)
                val can = points >= item.price
                val locked = level < item.minLevel   // the level is what opens new things in the shop
                shopPage.addView(itemRow(
                    item.name, if (locked) "Lv.${item.minLevel}에 열려요" else "${item.price}P",
                    when { owned -> "보유 중"; locked -> "🔒 Lv.${item.minLevel}"; can -> "구매"; else -> "포인트 부족" },
                    enabled = !owned && !locked && can, thumb = item.id, price = true,
                ) {
                    if (PetHub.buy(item.id)) {
                        Voice.event(stageNow, Ev.BOUGHT, item.name, "${item.name} 샀어! 인벤토리에서 입혀 줘")?.let { say(it) }
                        pet.burst(true)
                    }
                })
            }
        }
    }

    private fun buildInventory() {
        inventoryPage.removeAllViews()
        val owned = Catalog.items.filter { PetHub.wardrobe.has(it.id) }
        if (owned.isEmpty()) {
            inventoryPage.addView(text("아직 산 소품이 없어요.\n상점에서 안전운전 포인트로 살 수 있어요.", 15f, sub).apply { setPadding(dp(4), dp(8), 0, 0); setLineSpacing(0f, 1.25f) })
            return
        }
        for (slot in Slot.values()) {
            val inSlot = owned.filter { it.slot == slot }
            if (inSlot.isEmpty()) continue
            inventoryPage.addView(heading(slot.label))
            for (item in inSlot) {
                val on = PetHub.wardrobe.isEquipped(item.id)
                val verbOn = if (slot == Slot.FURNITURE) "놓기" else "입기"
                val verbOff = if (slot == Slot.FURNITURE) "치우기" else "벗기"
                val eggNoClothes = slot == Slot.PANTS && (stageNow == GrowthStage.EGG || stageNow == GrowthStage.INFANT)   // no trousers for an egg or a baby
                inventoryPage.addView(itemRow(item.name, if (eggNoClothes) "아기일 때는 바지를 입을 수 없어요" else if (on) (if (slot == Slot.FURNITURE) "방에 놓여 있어요" else "입고 있어요") else "보관 중", if (on) verbOff else verbOn, enabled = !eggNoClothes,
                    thumb = item.id, secondary = on, subColor = if (on) Look.SAGE_D else sub) {
                    if (on) { PetHub.unequipItem(item.id); Voice.event(stageNow, Ev.TAKE_OFF, item.name, "${item.name} 치웠어")?.let { say(it) } } else { PetHub.equip(item.id); Voice.event(stageNow, Ev.TRY_ON, item.name, "${item.name}, 어때? 잘 어울려?")?.let { say(it) }; pet.burst(true) }
                })
            }
        }
    }

    /** a choice in the settings: a marker, a title and a line of explanation; highlighted when selected */
    private fun option(onClick: () -> Unit) = text("", 20f, ink).apply {
        minHeight = dp(88)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setLineSpacing(0f, 1.2f)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
    }

    /** 화면 밝기: 자동 (the car's day/night) / 라이트 / 다크 */
    private fun updateNightRows() {
        val cur = Night.current(this)
        nightRows[Night.LIGHT]?.let { updateOption(it, "라이트", "낮의 방 (화면 위 해/달 버튼으로도 바꿀 수 있어요)", cur == Night.LIGHT) }
        nightRows[Night.DARK]?.let { updateOption(it, "다크", "밤 창밖과 켜진 스탠드, 어두운 지도", cur == Night.DARK) }
    }

    private fun updateOption(v: TextView, title: String, subText: String, on: Boolean) {
        val sp = android.text.SpannableString("$title\n$subText")
        sp.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, title.length, 0)
        sp.setSpan(android.text.style.ForegroundColorSpan(Theme.text(sub)), title.length + 1, sp.length, 0)
        sp.setSpan(android.text.style.RelativeSizeSpan(0.8f), title.length + 1, sp.length, 0)
        v.text = sp
        v.setCompoundDrawablesRelative(Glyphs.radio(this, 20f, on), null, null, null)
        v.compoundDrawablePadding = dp(12)
        v.background = kit.card(20f, if (on) (if (Theme.night) 0xFF33449F.toInt() else 0xFFEEF3FE.toInt()) else Look.WHITE, if (on) sky else Look.ROW_LINE, if (on) 2f else 1f)
    }

    // ---------------------------------------------------------------- views


    private fun text(t: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Theme.text(color)); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** a label in the rounded display face */
    private fun title(t: String, size: Float, color: Int = ink) = kit.title(TextView(this).apply { text = t }, size, color)

    private fun chip(t: String, onClick: () -> Unit) = text(t, 14f, ink, true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = kit.chip(16f)
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { child ->
            addView(child, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
    }

    /** a rounded bar with a soft gradient (the same max / progress as before) */
    private fun bar(max: Int, from: Int, to: Int, track: Int = 0xFFF1E4D2.toInt()) = PillBar(this, from, to, Theme.fill(track)).apply { this.max = max }

    /** the position is only used to know which 시·도 the car is in (the map collection) */
    private fun askLocationOnce() {
        if (askedLocation) return
        askedLocation = true
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            startForegroundService(Intent(this, PetService::class.java))   // the service takes the location type and starts the positions
        }
    }

    private fun openDex() {
        if (dexLayer != null || gameView != null) return
        setDrawer(false)
        val l = DexLayer(this) { closeDex() }
        l.elevation = dp(40).toFloat()
        dexLayer = l
        root.addView(l, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeDex() {
        val l = dexLayer ?: return
        root.removeView(l); dexLayer = null
        refresh()
    }

    private fun openGame() {
        if (gameView != null) return
        setDrawer(false)
        val gv = FlapGameView(this, { closeGame() }) { coins -> PetHub.grantGamePoints(coins) }
        gv.elevation = dp(40).toFloat()
        gameView = gv
        root.addView(gv, FrameLayout.LayoutParams(-1, -1))
        gv.start()
    }

    private fun closeGame() {
        val gv = gameView ?: return
        gv.stop(); root.removeView(gv); gameView = null
        refresh()
    }

    /** one line of the menu: a small picture tile, a title with a short hint, and an arrow */
    private fun menuRow(kind: IconKind, title: String, target: Page, hint: String = "", action: (() -> Unit)? = null): View {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(title, 25f, ink))
            if (hint.isNotEmpty()) addView(text(hint, 15f, sub), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(10), dp(14), dp(10))
            minimumHeight = dp(88)
            background = kit.rowPressable(20f)
            addView(MenuIcon(context, kind), LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
            addView(text("›", 26f, 0xFFC9B8A6.toInt()))
            setOnClickListener { if (action != null) action() else showPage(target) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) }
        }
    }

    private fun buildUi(): View {
        pet = PetView(this)
        pet.onPoke = { Voice.event(stageNow, Ev.POKE)?.let { say(it) } }

        // ---- top-left: the level and how far you have gone together
        hudLevel = title("", 27f, ink)
        hudXp = bar(PetEngine.XP_PER_LEVEL, 0xFFFFD978.toInt(), 0xFFF2B33D.toInt())
        hudKm = text("", 17f, sub, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelative(Glyphs.car(this@MainActivity, 25f), null, null, null); compoundDrawablePadding = dp(8)
        }
        hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(24), dp(16))
            background = kit.card(26f, 0xF5FFF9F1.toInt())
            kit.lift(this, 4f)
            addView(hudLevel)
            addView(hudXp, LinearLayout.LayoutParams(dp(250), dp(13)).apply { topMargin = dp(10) })
            addView(hudKm, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(9) })
        }

        // ---- top-right: points, and the menu
        pointsChip = title("0P", 25f, Look.HONEY_DEEP).apply {
            gravity = Gravity.CENTER
            minHeight = dp(66)
            setPadding(dp(14), dp(8), dp(22), dp(8))
            setCompoundDrawablesRelative(Glyphs.coin(this@MainActivity, 36f), null, null, null); compoundDrawablePadding = dp(8)
            background = kit.card(100f, 0xF5FFF9F1.toInt())
            kit.lift(this, 4f)
        }
        val menuBtn = TextView(this).apply {
            text = "메뉴"
            minHeight = dp(70); minWidth = dp(92)
            kit.key(this, Kit.KEY_PRIMARY, 24f, 22f, 10f)
            setCompoundDrawablesRelative(Glyphs.menu(this@MainActivity, 21f), null, null, null); compoundDrawablePadding = dp(8)
            kit.lift(this, 4f)
            setOnClickListener { openMenu() }
        }
        // day / night: one round button, the driver switches the room between light and dark
        darkBtn = TextView(this).apply {
            textSize = 27f; gravity = Gravity.CENTER
            minWidth = dp(70); minHeight = dp(70)
            background = kit.card(100f, 0xF5FFF9F1.toInt())
            kit.lift(this, 4f)
            contentDescription = "다크 모드"
            setOnClickListener { Night.set(this@MainActivity, if (Night.isDark(this@MainActivity)) Night.LIGHT else Night.DARK); recreate() }
        }
        topRight = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(darkBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            addView(pointsChip)
            addView(menuBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        }

        // ---- the card that appears when you open the app after a drive
        val tripRibbon = title("이번 주행", 14.5f, 0xFF6B4410.toInt()).apply {
            setPadding(dp(16), dp(5), dp(16), dp(5))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFFFD978.toInt(), 0xFFF2B33D.toInt())).apply {
                cornerRadius = dp(100).toFloat(); setStroke(dp(1).coerceAtLeast(1) + 1, Look.COCOA)
            }
        }
        tripHead = title("", 34f, navy).apply { gravity = Gravity.CENTER }
        tripBody = text("", 14f, ink).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f) }
        tripCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(14), dp(30), dp(16))
            background = kit.card(26f, 0xFFFFFCF6.toInt())
            visibility = View.GONE
            kit.lift(this, 8f)
            addView(tripRibbon)
            addView(tripHead, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            addView(tripBody, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }

        // ---- the menu card: header (portrait, level, experience, close) on a bit of sky, and the pages
        avatar = AvatarView(this)
        drawerName = text("", 17f, 0xFF4D6593.toInt(), true).apply { visibility = View.GONE }
        drawerLevel = title("Lv.1", 33f, ink).apply {
            // a long press here shows the developer entry
            setOnLongClickListener { devRow.visibility = View.VISIBLE; showPage(Page.DEV); true }
        }
        drawerXp = bar(PetEngine.XP_PER_LEVEL, 0xFFFFD978.toInt(), 0xFFF2B33D.toInt(), 0xBFFFFFFF.toInt())
        val close = ImageView(this).apply {
            setImageDrawable(Glyphs.cross(this@MainActivity, 16f, Look.COCOA))
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Look.WHITE); setStroke(dp(1).coerceAtLeast(1), Look.LINE) }
            kit.lift(this, 3f)
            contentDescription = "닫기"
            setOnClickListener { setDrawer(false) }
        }
        val headText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(drawerName)
            addView(drawerLevel, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            addView(drawerXp, LinearLayout.LayoutParams(-1, dp(11)).apply { topMargin = dp(8) })
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(avatar, LinearLayout.LayoutParams(dp(84), dp(84)))
            addView(headText, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14); rightMargin = dp(12) })
            addView(close, LinearLayout.LayoutParams(dp(58), dp(58)))
        }

        backTitle = title("", 21f, ink)
        backRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            visibility = View.GONE
            addView(BackButton(context), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(backTitle, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            setOnClickListener { showPage(Page.MENU) }
        }

        devRow = menuRow(IconKind.DEV, "개발자", Page.DEV).apply { visibility = View.GONE }
        menuPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(menuRow(IconKind.INFO, "캐릭터 정보", Page.INFO, "성장 · 운전 기록"))
            addView(menuRow(IconKind.MISSION, "미션", Page.MISSION, "오늘의 미션"))
            addView(menuRow(IconKind.SHOP, "상점", Page.SHOP, "옷과 방 소품"))
            addView(menuRow(IconKind.INVENTORY, "인벤토리", Page.INVENTORY, "입히고 놓기"))
            addView(menuRow(IconKind.SETTINGS, "설정", Page.SETTINGS, "내비 위의 작은 펫"))
            addView(devRow)
        }
        infoPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        missionPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        shopPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inventoryPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        followRow = option { PetHub.setFollowCar(!PetHub.followCar) }
        overlayState = text("", 14f, Look.CORAL_D)
        overlayBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(heading("권한이 필요해요"))
            addView(overlayState)
            addView(TextView(context).apply {
                text = "다른 앱 위에 표시 권한 열기"; minHeight = dp(48)
                kit.key(this, Kit.KEY_SECONDARY, 16f, 12f, 8f)
                setOnClickListener { openOverlaySettings() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        settingsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading("앱 화면"))
            addView(text("달리는 동안은 꺼지고, 주차(P)하면 다시 열려요.", 18f, ink).apply { setPadding(dp(6), 0, 0, 0) })
            addView(heading("화면 밝기"))
            for (m in intArrayOf(Night.LIGHT, Night.DARK)) {
                val r = option { Night.set(this@MainActivity, m); recreate() }
                nightRows[m] = r
                addView(r)
            }
            addView(heading("내비게이션을 켰을 때"))
            addView(followRow)
            addView(overlayBox)
        }
        devPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("개발자 · 실차 신호가 없을 때 같은 경로로 신호를 흘려 보냅니다.\n주행을 시작하면 앱 화면은 자동으로 꺼집니다(알림의 데모 버튼으로 조작).", 12f, sub))
            addView(row(chip("주행 시작") { PetHub.demoDrive() }, chip("주차(P)") { PetHub.demoStop() }))
            addView(row(chip("깜빡이 켜고 회전") { PetHub.demoTurn(true, false) }, chip("깜빡이 없이 회전") { PetHub.demoTurn(false, false) }))
            addView(row(chip("핸들 확 돌리기") { PetHub.demoTurn(true, true) }, chip("덜컹 브레이크 ×3") { PetHub.demoBrake(true) }))
            addView(row(chip("상태: 평상시") { PetHub.demoHungry(null); PetHub.demoMood(70) }, chip("상태: 화난") { PetHub.demoHungry(null); PetHub.demoMood(45) }))
            addView(row(chip("상태: 배고픈") { PetHub.demoMood(70); PetHub.demoHungry(true) }, chip("상태: 다친") { PetHub.demoHungry(null); PetHub.demoMood(15) }))
            addView(row(chip("포인트 +200") { PetHub.demoGivePoints(200) }, chip("내비 경로 시작") { if (!PetHub.startDemoRoute()) say("내비게이션이 아직 연결되지 않았어요") }))
            addView(row(chip("미션: 진행 중") { PetHub.demoMission(false) }, chip("미션: 모두 완료") { PetHub.demoMission(true) }))
            addView(row(chip("처음 안내 다시 보기") { PetHub.demoResetOnboarding(); setDrawer(false); startOnboarding() }))
        }

        val pages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(14))
            addView(menuPage); addView(infoPage); addView(missionPage); addView(shopPage); addView(inventoryPage); addView(settingsPage); addView(devPage)
        }
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SkyBandDrawable(context, 104f, 26f)
            clipToOutline = true
            setPadding(dp(20), dp(20), dp(20), 0)
            translationX = (drawerW + drawerMargin).toFloat()
            kit.lift(this, 30f)   // above the bubble and the trip card, which have a shadow of their own
            addView(header)
            addView(View(context), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16) })   // the sky band's wave separates the header
            addView(backRow)
            addView(ScrollView(context).apply { addView(pages); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        scrim = View(this).apply {
            setBackgroundColor(0x4D462E1A)   // a warm dim, not grey
            elevation = dp(20).toFloat()
            alpha = 0f; visibility = View.GONE
            setOnClickListener { setDrawer(false) }
        }

        // ---- the first-run introduction: the pet explains itself in a speech bubble, then you name it
        obBubble = title("", 23f, ink).apply {
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(20), dp(30), dp(20))
            maxWidth = dp(640)
            setLineSpacing(0f, 1.25f)
            setTextColor(Look.INK)
            background = GradientDrawable().apply { setColor(0xFFFFFDF8.toInt()); cornerRadius = dp(30).toFloat(); setStroke((2.4f * resources.displayMetrics.density).toInt().coerceAtLeast(2), Look.COCOA) }
            kit.lift(this, 6f)
        }
        // the tail sits over the bubble's lower edge (a little higher in z than the bubble, and without a shadow of its own)
        val notch = BubbleTail(this).apply { elevation = obBubble.elevation + kit.dp(1f) }
        // the name is typed in: a cream field with the bubble's cocoa line; the keyboard's done key confirms it too
        obNameInput = EditText(this).apply {
            hint = "이름을 입력해 주세요"
            setHintTextColor(Look.MUTED)
            setTextColor(ink); textSize = 22f; typeface = kit.face
            gravity = Gravity.CENTER
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(8))
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI   // no full-screen keyboard in landscape
            background = GradientDrawable().apply { setColor(0xFFFFFDF8.toInt()); cornerRadius = dp(100).toFloat(); setStroke(dp(2), Look.COCOA) }
            setPadding(dp(24), dp(10), dp(24), dp(10))
            minHeight = dp(56)
            kit.lift(this, 4f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { chooseName(s?.toString()?.trim()?.ifEmpty { null }) }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { if (obName != null && obStep == 3) onObNext() else hideKeyboard(); true } else false
            }
        }
        obNameBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(obNameInput, LinearLayout.LayoutParams(dp(320), -2))
            addView(text("8글자까지 쓸 수 있어요", 12.5f, sub, true).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(4), dp(12), dp(4)); background = kit.card(100f, 0xF2FFF9F1.toInt(), Look.LINE, 1f) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        obNext = TextView(this).apply {
            text = "다음"
            minWidth = dp(260); minHeight = dp(60)
            kit.key(this, Kit.KEY_PRIMARY, 21f, 28f, 12f)
            kit.lift(this, 4f)
            setOnClickListener { onObNext() }
        }
        // the column sits right above the sleeping egg (see placeOnboard): the name field on top, then the bubble, whose tail points at the egg
        obTop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(obNameBox, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(14) })
            addView(obBubble, LinearLayout.LayoutParams(dp(560), -2))   // a fixed width: wrap_content collapsed to the notch's width
            addView(notch, LinearLayout.LayoutParams(dp(30), dp(20)).apply { topMargin = -dp(3) })
        }
        onboard = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true   // while it is shown, touches do not reach the room
            addView(obTop, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(320) })
            addView(obNext, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) })
        }
        // quick buttons along the bottom of the room: things you do for fun, not settings (so they are not in the menu)
        fun quick(kind: IconKind, label: String, onTap: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(26), dp(10))
            minimumHeight = dp(78)
            background = kit.card(100f, 0xF5FFF9F1.toInt())
            kit.lift(this, 4f)
            addView(MenuIcon(context, kind), LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(title(label, 24f, ink), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
            setOnClickListener { onTap() }
        }
        // under the menu button, at the right edge: the two things to do for fun
        quickBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(quick(IconKind.GAME, "미니게임") { openGame() }, LinearLayout.LayoutParams(dp(214), -2))
            addView(quick(IconKind.DEX, "지도 도감") { openDex() }, LinearLayout.LayoutParams(dp(214), -2).apply { topMargin = dp(14) })
        }

        root = FrameLayout(this).apply {
            setBackgroundColor(Look.CREAM)
            addView(pet, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(14), 0, 0) })
            addView(topRight, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(14), dp(16), 0) })
            // at the top, so the result does not cover the pet
            addView(tripCard, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(14) })
            addView(quickBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(102), dp(18), 0) })
            addView(onboard, FrameLayout.LayoutParams(-1, -1))
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(drawer, FrameLayout.LayoutParams(drawerW, -1, Gravity.END).apply { setMargins(0, drawerMargin, drawerMargin, drawerMargin) })
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeOnboard() }
        }
        return root
    }
}
