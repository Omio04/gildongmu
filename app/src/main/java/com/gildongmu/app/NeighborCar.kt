package com.gildongmu.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** 도로의 이웃 차를 눌렀을 때: 그 차에게만 묻기, 차 신고 이유 고르기.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

// ---------------------------------------------------------------- a car picked on the road

/** The car the next question goes to, while the pick is fresh; otherwise null (everyone around). */
internal fun MainActivity.activeAskCar(): Neighbor? {
    if (askCar != null && System.currentTimeMillis() - askCarAt > ASK_CAR_MS) askCar = null
    return askCar
}

/** A tap on a car: ask it something, or report it. Not while moving: a small target is for a stopped car. */
internal fun MainActivity.onCarTapped(id: String) {
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
internal fun MainActivity.startReport(name: String, carId: String? = null, file: (String) -> Unit) {
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

internal fun MainActivity.reasonLabel(code: String) = REPORT_REASONS.firstOrNull { it.first == code }?.second ?: code

/** A report reason: a big, soft-red chip, easy to hit and easy to read at a glance. */
internal fun MainActivity.reasonChip(label: String, action: () -> Unit) = text(label, 16f, t.ink, 2).apply {
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

internal fun MainActivity.renderReportReasons(p: PendingReport) {
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

internal fun MainActivity.renderPicked(car: Neighbor) {
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
    // stopped: the usual questions are one tap each (sent to this car only); the mic is the other way; driving: this card cannot open, voice only
    attentionCard.addView(text("무엇이 궁금하세요?", 14f, t.muted, 1), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
    CAR_QUESTIONS.chunked(2).forEach { pair ->
        val qrow = LinearLayout(this)
        pair.forEachIndexed { i, q ->
            qrow.addView(actionBtn(q, t.surface, t.ink, 17f) { askCarNow(car, q) }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        attentionCard.addView(qrow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    row.addView(actionBtn("말로 묻기", t.accent, Color.WHITE, 17f) { askPicked(car) }, LinearLayout.LayoutParams(0, -2, 1.6f))
    row.addView(actionBtn("신고", t.surface, t.muted, 15f) { startReport(car.nickname, car.id) { reason -> reportPicked(car, reason) } }, LinearLayout.LayoutParams(0, -2, 0.8f).apply { leftMargin = dp(8) })
    row.addView(actionBtn("닫기", t.surface, t.muted, 15f) { pickedCar = null; renderAnswers() }, LinearLayout.LayoutParams(0, -2, 0.8f).apply { leftMargin = dp(8) })
    attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
}

/** A ready-made question, one tap: it goes to this car only, as if said out loud and confirmed. */
internal fun MainActivity.askCarNow(car: Neighbor, q: String) {
    pickedCar = null
    sayBubble(q)
    flash("${car.nickname}에게 물어봤어요", "내 질문")
    io.execute {
        val res = try { api.ask(q, car.id) } catch (e: Exception) { null }
        deliver(res, "ASK")
    }
}

/** "말로 묻기" on a picked car: the driver now says the question out loud; it is read back before it goes to this car only. */
internal fun MainActivity.askPicked(car: Neighbor) {
    askCar = car
    askCarAt = System.currentTimeMillis()
    pickedCar = null
    answersTitle = "${car.nickname}에게 물어보기"
    renderAnswers()
    startVoice()
}

internal fun MainActivity.reportPicked(car: Neighbor, reason: String) {
    pickedCar = null
    neighbors = neighbors.filter { it.id != car.id }   // gone from the road at once; the server blocks it for good
    road.neighbors = neighbors
    flash("신고했어요 · 이유: ${reasonLabel(reason)} · ${car.nickname}님은 차단되고 더 이상 보이지 않아요", "신고")
    io.execute { try { api.reportCar(car.id, reason) } catch (_: Exception) {} }
}
