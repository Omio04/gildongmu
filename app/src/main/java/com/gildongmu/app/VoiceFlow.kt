package com.gildongmu.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** 말 인식과 해석: 들은 말 확인(보내기·다시·취소), 말 길잡이(routeSpoken), 읽어 주기.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

// ---------------------------------------------------------------- pickup on the way (sample data)

/** A spoken sentence: a pickup request if it names a kind of place, otherwise the normal "this is what I heard" flow. */
/** Whatever was heard goes to whoever is waiting for an answer: a confirmation, a hazard, an order, an offer, or a new request. */
internal fun MainActivity.handleHeard(spoken: String) {
    android.util.Log.i(TAG, "heard ${spoken.length} chars -> " + (if (confirmByVoice) "confirm" else if (hazard != null) "hazard" else if (order != null) "order" else if (nudge != null) "nudge" else "route"))   // length only, never the words
    if (confirmByVoice) answerConfirm(spoken)
    else if (hazard != null) answerHazard(spoken)
    else if (order != null) answerOrder(spoken)
    else if (nudge != null) answerNudge(spoken)
    else routeSpoken(spoken)
}

internal fun MainActivity.routeSpoken(spoken: String) {
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

/**
 * The recogniser's words are shown (and read back) first. Nothing is sent until the driver confirms,
 * because a misheard "안녕하세요" must never reach a neighbour as something else.
 */
internal fun MainActivity.propose(spoken: String) {
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

internal fun MainActivity.listenForConfirm() {
    if ((heardText != null && confirmByVoice) || nudge != null || hazard != null || order != null || kindChooser) voice?.listen()
}

internal fun MainActivity.answerConfirm(said: String) {
    val w = said.replace(" ", "")
    when {
        listOf("취소", "그만", "됐어").any { it in w } -> cancelHeard()
        listOf("다시", "아니", "틀렸", "아냐").any { it in w } -> retryHeard()
        listOf("보내", "네", "응", "맞", "좋아", "그래", "확인").any { it in w } -> sendHeard()
        else -> speakThen("보내, 다시, 취소 중에 말해 주세요", "confirm")
    }
}

internal fun MainActivity.cancelHeard() {
    heardText = null; confirmByVoice = false; answerTarget = null; askCar = null
    answersTitle = "도로 소식"
    showNote("보내지 않았어요")
}

internal fun MainActivity.retryHeard() {
    heardText = null; confirmByVoice = false   // answerTarget stays: the new sentence answers the same question
    startVoice()
}

/** Hands-free and always available: the confirmed sentence goes out, to the picked car or to the road. */
internal fun MainActivity.sendHeard() {
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
internal fun MainActivity.isWhyQuestion(spoken: String): Boolean {
    val w = spoken.replace(" ", "")
    return listOf("왜막", "왜이렇게", "왜이리", "무슨일", "막히는이유", "이유가", "원인").any { it in w }
}

/** A sentence that asks something, as opposed to a statement for the road: a question mark, or a question ending or word. */
internal fun MainActivity.looksLikeQuestion(spoken: String): Boolean {
    val w = spoken.replace(" ", "")
    return w.endsWith("?") || QUESTION_HINTS.any { it in w }
}

/** Any question other than "왜 막혀?" (that one has its own flow): it goes out as a question, not as a bubble. */
internal fun MainActivity.isOpenQuestion(spoken: String) = !isWhyQuestion(spoken) && looksLikeQuestion(spoken)

internal fun MainActivity.startVoice() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        return
    }
    tts?.stop()
    voice?.listen()
}

internal fun MainActivity.speakThen(s: String, id: String) {
    if (ttsReady) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, id) else ui.post { listenForConfirm() }
}

/** "이렇게 들었어요": the recognised sentence in big type, with send / say again / cancel. */
internal fun MainActivity.renderHeard(spoken: String) {
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

internal fun MainActivity.speak(s: String) {
    if (ttsReady) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "msg")
}
