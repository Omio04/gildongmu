package com.gildongmu.app

/** 서버 폴링: 이웃·질문·소식을 주기적으로 받아 화면 쪽 함수로 넘깁니다.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

// ---------------------------------------------------------------- data in

internal fun MainActivity.onTrip(tr: Trip) {
    if (tr.destination.isBlank()) return
    trip = tr
    destName = tr.destination
    io.execute { try { api.setDestination(tr.destination) } catch (_: Exception) {} }
    renderState()
}

/** Neighbours + incoming messages every 1.5 s, what is ahead every ~4.5 s. */
internal fun MainActivity.pollLoop() {
    if (!polling) return
    val n = tick++
    if (pollBusy) { ui.postDelayed(pollTick, 500); return }   // the last round is still running: don't pile up
    pollBusy = true
    pollIo.execute {
        try {
            currentPlace()?.let {   // tell the server where we are (the navigation while it is current, else the platform GPS)
                try { api.postPosition(it) } catch (_: Exception) {}
                if (System.currentTimeMillis() - lastPosLog > 30_000L) {
                    lastPosLog = System.currentTimeMillis()
                    android.util.Log.i(TAG, "position sent: ${"%.5f".format(it.lat)}, ${"%.5f".format(it.lon)} from ${if (navi?.fresh() != null) "navigation" else "gps"}")
                }
            }
            val near = api.nearby()
            val inbound = api.inbox(lastInboxId)
            val shared = if (n % 2 == 0 || inbound.isNotEmpty()) api.board() else null
            val asked = if (n % 2 == 1 || inbound.isNotEmpty()) api.questions() else null
            val said = if (n % 2 == 0 || inbound.isNotEmpty()) api.roadMessages() else null
            val seen = if (n % 2 == 1) api.hazardProposal() else null
            ui.post {
                setOnline(true)
                onNeighbors(near)   // an empty list is real too: nobody around
                if (!inboxPrimed) {  // skip history from before the app was opened
                    inbound.maxByOrNull { it.id }?.let { lastInboxId = it.id }
                    inboxPrimed = true
                } else inbound.forEach { showInbound(it) }
                shared?.let { renderBoard(it) }
                asked?.let { onQuestions(it) }
                said?.let { onRoadMessages(it) }
                seen?.let { showHazard(it) }
            }
        } catch (e: Exception) {
            ui.post { setOnline(false) }   // the screen keeps working with what it has, and says so
        } finally { pollBusy = false }
    }
    ui.postDelayed(pollTick, if (online) 2000 else 4000)   // slower while offline, back to normal as soon as it answers
}

internal fun MainActivity.setOnline(ok: Boolean) {
    if (online == ok) return
    online = ok
    renderHeader()
}

internal fun MainActivity.onNeighbors(near: List<Neighbor>) {
    if (neighborsKnown && near == neighbors) return   // nothing changed: no redraw, no layout
    neighborsKnown = true
    neighbors = near
    road.neighbors = near
    crowdText.text = if (near.isEmpty()) "주변에 앱 쓰는 이웃이 없어요"
        else "반경 300m · 앱 사용 ${near.size}대" + if (near.size > ROAD_CARS) "  · 화면 밖 +${near.size - ROAD_CARS}" else ""
    renderHeader()
    updateTabs()
    val gone = { id: String -> near.none { it.id == id } }
    if ((pickedCar != null && gone(pickedCar!!.id)) || (pendingReport?.carId?.let(gone) == true)) {   // that car drove out of range
        pickedCar = null; pendingReport = null
        renderAnswers()
    }
}
