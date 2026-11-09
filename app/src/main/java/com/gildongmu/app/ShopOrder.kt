package com.gildongmu.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** 가는 길 가게 찾기·주문·결제·주문 추적·경유지.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

internal fun MainActivity.favourites(): Set<String> = getSharedPreferences("gildongmu", Context.MODE_PRIVATE).getStringSet("fav_brands", emptySet())!!.toSet()

internal fun MainActivity.activeFavs(): Set<String> = if (showAllShops) emptySet() else favourites()

/** Mark a brand as a favourite (or not): once there are favourites, only those brands are offered. */
internal fun MainActivity.toggleFavourite(brand: String) {
    val now = favourites().toMutableSet()
    val added = now.add(brand)
    if (!added) now.remove(brand)
    getSharedPreferences("gildongmu", Context.MODE_PRIVATE).edit().putStringSet("fav_brands", now).apply()
    showAllShops = false
    flash(if (added) "♥ $brand · 단골 가게만 보여요" else "$brand · 단골 해제", "가는 길 픽업")
}                         // one per order screen: a retried "결제" is not a second order

/** Ask the server which partner shops are near the car's position (off the main thread). */
internal fun MainActivity.refreshShops(thenOpen: String? = null) {
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
internal fun MainActivity.renderErrands() {
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
internal fun MainActivity.openKindChooser() {
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

internal fun MainActivity.renderKindChooser() {
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

internal fun MainActivity.openList(key: String) {
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
internal fun MainActivity.openShopListing(shop: Shop) {
    pickedCar = null; profileOpen = false; payOpen = false; pendingReport = null; kindChooser = false
    val first = shop.menu.firstOrNull { it.simple } ?: shop.menu.firstOrNull() ?: return
    alts = listOf(Pick(shop, first)); altIdx = 0
    order = Pick(shop, first); orderKey = java.util.UUID.randomUUID().toString()
    speakThen(Errands.sellingSpoken(shop), "confirm")
    renderAnswers()
}

internal fun MainActivity.nextShop() {
    if (alts.size < 2) return
    altIdx = (altIdx + 1) % alts.size
    openOrder(alts[altIdx])
}

internal fun MainActivity.openOrder(p: Pick) {
    order = p
    orderKey = java.util.UUID.randomUUID().toString()
    orderListenTries = 0
    speakThen(Errands.spoken(p), "confirm")   // voice and touch both: the card is read out and then listened to, moving or stopped
    renderAnswers()
}

internal fun MainActivity.confirming() = order != null && payAsk === order

/** "결제": not paid yet: the window shows what is bought and how, the voice asks once more, a spoken or touched yes pays. */
internal fun MainActivity.askPay() {
    val p = order ?: return
    if (payToken() == null) { payOrder(); return }   // no card on file: says what to do
    payAsk = p; orderListenTries = 0
    speakThen("${p.shop.name}, ${p.item.name} ${p.item.priceText()}, ${payLabel()}로 결제할까요? 결제, 아니요 중에 말해 주세요", "confirm")
    renderAnswers()
}

internal fun MainActivity.renderPayConfirm(p: Pick) {
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

internal fun MainActivity.renderOrder(p: Pick) {
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
internal fun MainActivity.renderTracking(o: OrderReceipt) {
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
internal fun MainActivity.trackActiveOrder() {
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
internal fun MainActivity.restoreActiveOrder() {
    val prefs = getSharedPreferences("gildongmu", Context.MODE_PRIVATE)
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

internal fun MainActivity.forgetActiveOrder() = getSharedPreferences("gildongmu", Context.MODE_PRIVATE).edit().remove("active_receipt").apply()

/** The shop becomes a stop on the car's own navigation route: the route, not a straight line, then decides when the shop cooks. */
internal fun MainActivity.addShopWaypoint() {
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
internal fun MainActivity.payOrder() {
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
            getSharedPreferences("gildongmu", Context.MODE_PRIVATE).edit().putString("active_receipt", r.receipt).apply()   // survives the app being restarted
            speak("주문했어요. 접수번호 ${r.receipt}. 가게에 알렸어요")
            flash("${r.shop} · ${r.item} · ${"%,d원".format(r.price)} · 접수번호 ${r.receipt} · " +
                (if (r.cookInMin > 0) "${r.cookInMin}분 뒤 조리 시작 예정" else "바로 조리 시작") + " · 시험용, 실제 결제 없음", "주문 접수", 8_000)
        }
    }
}

internal fun MainActivity.cancelOrder() {
    order = null; alts = emptyList(); payAsk = null
    flash("주문을 취소했어요", "가는 길 픽업")
}

internal fun MainActivity.answerOrder(said: String) {
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
