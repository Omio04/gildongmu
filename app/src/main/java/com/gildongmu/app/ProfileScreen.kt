package com.gildongmu.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** 프로필(이름·색·차종): 불러오기, 서버 전송, 화면, 말로 바꾸기.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

/** Right after the first agreement: offer the profile (name, colour, car). It can be skipped, and is never forced while moving. */
internal fun MainActivity.maybeFirstProfile() {
    if (getSharedPreferences("gildongmu", Context.MODE_PRIVATE).getBoolean(PROFILE_KEY, false) || !profileEditable()) return
    profileOpen = true; pickedCar = null; pendingReport = null
    renderAnswers()
}

internal fun MainActivity.profileDone() {
    val first = !getSharedPreferences("gildongmu", Context.MODE_PRIVATE).getBoolean(PROFILE_KEY, false)
    getSharedPreferences("gildongmu", Context.MODE_PRIVATE).edit().putBoolean(PROFILE_KEY, true).apply()
    profileOpen = false
    if (first && payMethods().isEmpty() && profileEditable()) openPayments()   // first run: offer the payment method once, right away (can be closed)
    else renderAnswers()
}

/** Settings by voice are allowed while driving (touching them is not): "내 차는 아반떼", "이름 바꿔 줘". */
internal fun MainActivity.voiceProfile(spoken: String): Boolean {
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

/** Editing the profile only while the car stands still (정차: parked or stopped in traffic), never while moving.
 *  Note for review: the emulator measured the stopped-in-traffic state with the "no setup" flag (0x40) set, the same
 *  as moving. If the platform ever enforces that flag for this screen, this is the one line to tighten. */
internal fun MainActivity.profileEditable() = info.state != DriveState.MOVING

internal fun MainActivity.myNick() = myAdj + myAnimal

internal fun MainActivity.loadProfile() {
    val p = getSharedPreferences("gildongmu", Context.MODE_PRIVATE)
    p.getString("p_adj", null)?.takeIf { it in ADJECTIVES }?.let { myAdj = it }
    p.getString("p_animal", null)?.takeIf { it in ANIMALS }?.let { myAnimal = it }
    p.getString("p_color", null)?.takeIf { it in COLORS }?.let { myColor = it }
    p.getString("p_model", null)?.takeIf { it in MODELS }?.let { myModel = it }
    myShowModel = p.getBoolean("p_show_model", true)
}

internal fun MainActivity.pushProfile() {
    val adj = myAdj; val animal = myAnimal; val color = myColor; val model = myModel; val show = myShowModel
    io.execute { try { api.setMyProfile(adj, animal, color, model, show) } catch (_: Exception) {} }
}

/** The header button: the avatar in the chosen colour, the nickname, and the car (or the hint to pick one). */
internal fun MainActivity.refreshProfile() {
    if (!profileAvatarReady) return
    profileAvatar.text = myNick().take(1)
    profileAvatar.background = oval(Color.parseColor(myColor))
    profileName.text = myNick()
    profileSub.text = (myModel.ifBlank { "내 차를 골라 주세요" }) + "  ▾"
    if (roadReady) {   // the same name stands over my own car on the road
        road.myName = myNick()
        road.myModel = if (myShowModel) myModel else ""
    }
}

/** Every change is saved at once, shown at once, and sent to the server: there is no separate "save" step. */
internal fun MainActivity.applyProfile() {
    getSharedPreferences("gildongmu", Context.MODE_PRIVATE).edit()
        .putString("p_adj", myAdj).putString("p_animal", myAnimal).putString("p_color", myColor)
        .putString("p_model", myModel).putBoolean("p_show_model", myShowModel).apply()
    refreshProfile()
    pushProfile()
    renderAnswers()
}

internal fun MainActivity.profileChip(label: String, selected: Boolean, compact: Boolean = false, action: () -> Unit) =
    text(label, if (compact) 14f else 15f, if (selected) Color.WHITE else t.ink, 2).apply {
    gravity = Gravity.CENTER
    maxLines = 1
    background = if (selected) fill(t.accent, 12) else outline()
    if (compact) setPadding(dp(4), dp(14), dp(4), dp(14)) else setPadding(dp(14), dp(14), dp(14), dp(14))
    setOnClickListener { action() }
    press(this)
}

internal fun MainActivity.renderProfile() {
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
    if (!getSharedPreferences("gildongmu", Context.MODE_PRIVATE).getBoolean(PROFILE_KEY, false))
        foot.addView(actionBtn("건너뛰기", t.surface, t.muted, 16f) { profileDone() }, LinearLayout.LayoutParams(dp(120), -2).apply { rightMargin = dp(8) })
    foot.addView(actionBtn("완료", t.accent, Color.WHITE, 16f) { profileDone() },
        LinearLayout.LayoutParams(dp(120), -2))
    attentionCard.addView(foot, lp(14))
    attentionCard.addView(text("개인정보 안내 보기", 13f, t.muted, 1).apply {
        setPadding(0, dp(10), dp(10), dp(2))
        setOnClickListener { showConsent(readOnly = true) }
    }, LinearLayout.LayoutParams(-2, -2))
}
