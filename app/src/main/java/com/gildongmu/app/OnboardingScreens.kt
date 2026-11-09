package com.gildongmu.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/** 첫 실행 소개와 개인정보 동의 화면.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

/** The first thing a new driver sees: what the app is for, in four short cards. Shown once. */
internal fun MainActivity.showIntro(onDone: () -> Unit) {
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
        getSharedPreferences("gildongmu", Context.MODE_PRIVATE).edit().putBoolean(INTRO_KEY, true).apply()
        introView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; introView = null
        onDone()
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    // 1. showIntro 함수 하단
    val overlay = FrameLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
        isClickable = true
        // this@OnboardingScreens 를 this@MainActivity 로 수정
        addView(android.widget.ScrollView(this@showIntro).apply { addView(panel) }, FrameLayout.LayoutParams(-1, -1))
    }
    introView = overlay
    addContentView(overlay, android.view.ViewGroup.LayoutParams(-1, -1))
}

/** The first-run notice (and, read-only, from the profile): plain words, one short paragraph per thing that is used. */
internal fun MainActivity.showConsent(readOnly: Boolean) {
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
            getSharedPreferences("gildongmu", Context.MODE_PRIVATE).edit().putBoolean(CONSENT_KEY, true).apply()
            consented = true
            close()
            startSharing()
            maybeFirstProfile()
        }, LinearLayout.LayoutParams(0, -2, 1.6f))
        buttons.addView(actionBtn("동의하지 않음", t.surface, t.muted) { finish() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
    }
    panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    // 1. showIntro 함수 하단
    val overlay = FrameLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
        isClickable = true
        // this@OnboardingScreens 를 this@MainActivity 로 수정
        addView(android.widget.ScrollView(this@showConsent).apply { addView(panel) }, FrameLayout.LayoutParams(-1, -1))
    }

    consentView = overlay
    addContentView(overlay, android.view.ViewGroup.LayoutParams(-1, -1))
}
