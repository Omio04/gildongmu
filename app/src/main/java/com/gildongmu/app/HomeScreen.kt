package com.gildongmu.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 홈 화면: 왼쪽 도로, 오른쪽 패널, 탭·타일, 소식판, 주행 상태·회전 안내 표시.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

// ---------------------------------------------------------------- UI build

internal fun MainActivity.buildUi(): View {
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

internal fun MainActivity.buildRoadPanel(): View {
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

internal fun MainActivity.buildRightPanel(): View {
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
                    getSharedPreferences("gildongmu", Context.MODE_PRIVATE).edit().putString("theme", value).apply()
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
internal fun MainActivity.updateTabs() {
    if (whyButtonReady) whyButton.alpha = if (info.canSend) 1f else 0.45f
}

internal fun MainActivity.renderTiles() {
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

internal fun MainActivity.applyEnabled() {
    val enabled = info.canSend
    tileViews.forEach { (tile, ic, label) ->
        tile.isEnabled = enabled
        ic.alpha = if (enabled) 1f else 0.55f
        label.setTextColor(if (enabled) t.ink else t.faint)
    }
}

internal fun MainActivity.renderHeader() {
    if (online) { liveChip.visibility = View.GONE; return }
    liveChip.text = "● 끊김"   // short: the header is already full
    liveChip.visibility = View.VISIBLE

}

/**
 * The road notice board: what neighbours reported ahead. An urgent report is red and read out once.
 * Another car's report gets a three-tap prompt (맞아요 / 고마워요 / 덕분에 잘 지나갔어요) that goes away
 * by itself after [PROMPT_MS]; ignoring it costs nobody anything.
 */
internal fun MainActivity.renderBoard(view: BoardView, force: Boolean = false) {
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
internal fun MainActivity.reactionRow(post: BoardPost): View {
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

internal fun MainActivity.react(post: BoardPost, what: String) {
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
internal fun MainActivity.applyFocus() {
    if (!stoppedAreaReady || !bannerCardReady) return
    // Answers never take over the screen: they appear in the card at the bottom, and the controls stay where they are.
    // The tall cards (my profile, the report reasons) take the board's place so that nothing is pushed off the screen.
    // the pickup list and the order card need the room: the report banner steps aside while they are open
    // any big card in the top slot (a report, a sentence to confirm, an offer, an order, a picked car, the profile) takes the room of the banner:
    // otherwise the buttons at the bottom are pushed off the screen
    bannerCard.visibility = if (profileOpen || payOpen || kindChooser || pendingReport != null || order != null || hazard != null || heardText != null || nudge != null || pickedCar != null) View.GONE else View.VISIBLE
    answersCard.visibility = if (order != null || kindChooser || profileOpen || payOpen || hazard != null || heardText != null || nudge != null || pickedCar != null || pendingReport != null) View.GONE else View.VISIBLE   // the order or profile card replaces it: no empty squeezed card under it
    stoppedArea.visibility = if (info.canSend && !profileOpen && !payOpen && order == null && !kindChooser && pickedCar == null && pendingReport == null) View.VISIBLE else View.GONE   // editing the profile needs the room; a picked car is the only thing on screen
}

// ---------------------------------------------------------------- driving state

internal fun MainActivity.onDriveInfo(reported: DriveInfo) {
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
internal fun MainActivity.trackJam() {
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

internal fun MainActivity.render() {
    renderState()
    val moving = info.state == DriveState.MOVING
    road.moving = moving
    road.flowing = true   // the road always flows at the same pace, parked or not
    road.speedKmh = if (info.state == DriveState.PARKED) 0f else (info.speedKmh ?: 0f).coerceAtLeast(if (jamSince != null) 4f else 0f)

    // only if the vehicle forbids touching altogether do the buttons give way to the big voice screen
    val locked = !info.canSend
    applyFocus()
    voiceHero.visibility = if (locked) View.VISIBLE else View.GONE
    updateTabs()
    applyEnabled()
    renderAnswers()
}

internal fun MainActivity.renderState() {
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
internal fun MainActivity.renderTurn() {
    if (!turnBarReady) return
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

internal fun MainActivity.gearCode() = pleosVehicle?.gear ?: if (info.state == DriveState.PARKED) "P" else "D"   // the real gear from the Pleos Vehicle SDK when it answers

/** A short line in the card that clears itself: for confirmations that need no action. */
internal fun MainActivity.flash(msg: String, title: String = "도로 소식", ms: Long = 3_500) {
    answersTitle = title
    note = msg
    renderAnswers()
    ui.postDelayed({ if (note == msg) { note = null; renderAnswers() } }, ms)
}

/**
 * Tap the grade chip in the header: a small popover right under it, like the tier pop-ups of other apps.
 * Grade and points, a progress bar to the next grade, the four steps, and how points are earned.
 * It takes no room on the screen and closes with a tap anywhere else.
 */
internal fun MainActivity.showStanding() {
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
