package com.gildongmu.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 결제 수단(모의) 등록·삭제와 결제 수단·주문 내역 화면.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

internal fun MainActivity.prefs() = getSharedPreferences("gildongmu", Context.MODE_PRIVATE)

internal fun MainActivity.payMethods(): List<PayMethod> = try {
    val arr = org.json.JSONArray(prefs().getString("pay_methods", "[]"))
    (0 until arr.length()).map { arr.getJSONObject(it).let { o -> PayMethod(o.getString("id"), o.getString("type"), o.optString("last4", ""), o.getString("token")) } }
} catch (_: Exception) { emptyList() }

internal fun MainActivity.savePayMethods(list: List<PayMethod>, default: String?) {
    val arr = org.json.JSONArray()
    list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("type", it.type).put("last4", it.last4).put("token", it.token)) }
    prefs().edit().putString("pay_methods", arr.toString()).putString("pay_default", default ?: list.firstOrNull()?.id).apply()
}

internal fun MainActivity.defaultMethod(): PayMethod? = payMethods().let { l -> l.firstOrNull { it.id == prefs().getString("pay_default", null) } ?: l.firstOrNull() }

internal fun MainActivity.payToken(): String? = defaultMethod()?.token

internal fun MainActivity.payLabel(): String = defaultMethod()?.label() ?: "없음"

/**
 * MOCK registration, done while stopped. A real one is done on the phone or the web with the card company's own screen,
 * and the car only keeps the token it gets back. Here a made-up token is stored: no card number exists anywhere.
 */
internal fun MainActivity.addPayMethod(type: String) {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    val tok = "tok_" + (1..12).map { chars.random() }.joinToString("")
    val m = PayMethod("m" + (1..6).map { chars.random() }.joinToString(""), type, if (type == "domestic") "1234" else if (type == "overseas") "5678" else "", tok)
    val list = payMethods() + m
    savePayMethods(list, prefs().getString("pay_default", null) ?: m.id)
    renderAnswers()
}

internal fun MainActivity.removePayMethod(id: String) {
    val list = payMethods().filter { it.id != id }
    val keep = prefs().getString("pay_default", null)?.takeIf { d -> list.any { it.id == d } }
    savePayMethods(list, keep)
    renderAnswers()
}

internal fun MainActivity.openPayments() {
    resumeOrder = order; order = null           // the order card would otherwise cover this screen
    payOpen = true; profileOpen = false; pickedCar = null; pendingReport = null
    payExpanded = null
    io.execute {
        val rows = try { api.myOrders() } catch (_: Exception) { null }
        ui.post { if (rows != null) payHistory = rows; if (payOpen) renderAnswers() }
    }
    renderAnswers()
}

internal fun MainActivity.closePayments() {
    payOpen = false
    resumeOrder?.let { order = it; resumeOrder = null }   // back to paying, now with a method on file
    renderAnswers()
}

/** Payment methods (list, default, delete, add) and the order history with a receipt: the kind of screen ride and order apps have. */
internal fun MainActivity.renderPayments() {
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
