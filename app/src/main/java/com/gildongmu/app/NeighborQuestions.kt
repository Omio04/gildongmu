package com.gildongmu.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** 이웃 질문·답: "왜 막혀요?" 제안, 질문 보내기, 받은 답 카드, 말풍선, 감사·신고.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

/** Feeds the detector with the current speed. Called on every reading and every few seconds. */
internal fun MainActivity.evaluateJam() {
    val speed = info.speedKmh ?: return
}

/**
 * The car offers the question itself, rarely: "차가 갑자기 느려졌어요. 앞에 왜 막히는지 물어볼까요?"
 * It goes away by itself after [NUDGE_MS]; ignoring it costs nothing.
 */
internal fun MainActivity.showNudge(reason: JamReason) {
    if (nudge != null || heardText != null || !online || asking) return   // already asking: do not offer to ask
    jam.shown(System.currentTimeMillis())
    nudge = reason
    val token = ++nudgeToken
    val line = "${reason.sentence}. 앞에 왜 막히는지 물어볼까요?"
    if (info.canSend) speak(line) else speakThen("$line 네, 라고 말하면 물어볼게요", "nudge")
    renderAnswers()
    ui.postDelayed({ if (token == nudgeToken) dismissNudge() }, NUDGE_MS)
}

internal fun MainActivity.dismissNudge() {
    if (nudge == null) return
    nudge = null; nudgeToken++
    renderAnswers()
}

internal fun MainActivity.answerNudge(said: String) {
    val w = said.replace(" ", "")
    if (listOf("네", "응", "물어", "좋아", "그래", "해줘").any { it in w }) { dismissNudge(); askWhy() } else dismissNudge()
}

/** The core question, one tap: why is the road stuck? A bubble goes over our car until somebody answers. */
internal fun MainActivity.askWhy() {
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
internal fun MainActivity.renderNudge(reason: JamReason) {
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
internal fun MainActivity.onQuestions(items: List<QuestionItem>) {
    if (items == questions) return
    questions = items
    items.firstOrNull { !it.answered && announcedQ.add(it.id) }?.let {
        speak("${it.from}님이 물어봐요. ${it.text}. 답하려면 말해 주세요")
    }
    updateTabs()
    renderAnswers()
}

/** What others said out loud: shown in the list, and the newest one is read out once (eyes stay on the road). */
internal fun MainActivity.onRoadMessages(items: List<RoadMessage>) {
    if (items == roadMessages) return
    roadMessages = items
    items.firstOrNull { announcedMsg.add(it.id) }?.let { speak("${it.from}님이 말해요. ${it.text}") }
    renderAnswers()
}

internal fun MainActivity.reportQuestion(q: QuestionItem, reason: String) {
    questions = questions.filter { it.id != q.id }
    flash("신고했어요 · 이유: ${reasonLabel(reason)} · ${q.from}님의 질문은 더 이상 보이지 않아요", "신고")
    io.execute { try { api.reportQuestion(q.id, reason) } catch (_: Exception) {} }
}

internal fun MainActivity.reportMessage(m: RoadMessage, reason: String) {
    roadMessages = roadMessages.filter { it.id != m.id }
    flash("신고했어요 · 이유: ${reasonLabel(reason)} · 이 이웃의 말은 더 이상 보이지 않아요", "신고")
    io.execute { try { api.reportMessage(m.id, reason) } catch (_: Exception) {} }
}

internal fun MainActivity.answerQuick(q: QuestionItem, reply: String) {
    if (!info.canSend) return
    io.execute {
        try { api.answerQuestion(q.id, reply) } catch (_: Exception) {}
        val fresh = try { api.questions() } catch (_: Exception) { null }
        val shared = try { api.board() } catch (_: Exception) { null }
        ui.post { fresh?.let { onQuestions(it) }; shared?.let { renderBoard(it, force = true) }; flash("답을 보냈어요 · 고마워요") }
    }
}

/** The question card: who asked, the sentence, and the quickest ways to answer. */
internal fun MainActivity.renderQuestions() {
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

/** A bubble beside our car for a while: what we just asked, said or reported. */
internal fun MainActivity.sayBubble(text: String) {
    road.myMood = text
    val token = ++bubbleToken
    ui.postDelayed({ if (token == bubbleToken) road.myMood = null }, BUBBLE_MS)
}

internal fun MainActivity.newQuestion(title: String) {
    answers.clear()
    note = null
    expandedId = null
    answersTitle = title
    answerLimit = MAX_ANSWERS
}

internal fun MainActivity.deliver(res: SendResult?, presetCode: String?) {
    when {
        res == null && presetCode == null -> ui.post { showNote("연결을 확인하면 보낼 수 있어요") }
        res == null -> ui.post { showNote("연결을 확인하면 보낼 수 있어요") }
        res.code == 422 -> ui.post { showNote(res.error ?: "잘 못 알아들었어요. 다시 말해 주세요") }
        res.error != null -> ui.post { showNote(res.error) }
    }
}

internal fun MainActivity.showInbound(m: Inbound) {
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
internal fun MainActivity.showNote(msg: String) {
    stopAsking()
    answers.clear()
    note = msg
    renderAnswers()
}

// ---------------------------------------------------------------- the answers card

internal fun MainActivity.renderAnswers() {
    if (!answersListReady) return
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

internal fun MainActivity.thank(id: Int) {
    if (!info.canSend || id in thankedIds) return
    thankedIds += id
    renderAnswers()
    io.execute {
        val pts = try { api.thanks(id) } catch (_: Exception) { 0 }
        val shared = try { api.board() } catch (_: Exception) { null }
        ui.post { thankedPoints[id] = pts; shared?.let { board = it }; renderAnswers() }
    }
}

internal fun MainActivity.report(id: Int, reason: String) {
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
internal fun MainActivity.startAsking() {
    asking = true
    road.scanning = true
    setAskLabel()
    renderAnswers()   // the confirm card / old answers give way to the board while we wait
    val token = ++askToken
    ui.postDelayed({ if (token == askToken && asking) { stopAsking(); setAskLabel("아직 답이 없어요 · 잠시 뒤 다시 물어볼 수 있어요"); ui.postDelayed({ setAskLabel() }, 6_000) } }, ASK_MS)
}

internal fun MainActivity.stopAsking() {
    if (!asking) return
    asking = false
    askToken++
    road.scanning = false
    setAskLabel()
}

internal fun MainActivity.setAskLabel(note: String? = null) {
    if (!whyTitleReady) return
    whyTitle.text = if (asking) "이웃에게 물어보는 중…" else "왜 막혀요?"
    whySub.text = note ?: if (asking) "답이 오면 바로 보여 드릴게요" else "주변 이웃에게 지금 물어보기"
    whyButton.alpha = if (asking || !info.canSend) 0.6f else 1f
}
