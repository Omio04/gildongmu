package com.gildongmu.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** 위험 신고: 사고·낙하물·로드킬·공사 카드, 사진 촬영 신고, 알리기.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

internal fun MainActivity.onTile(tile: Tile) {
    if (!info.canSend) return
    if (tile.code in HAZARD_TILES) reportNow(tile) else inform(tile)
}

/** 낙하물 / 로드킬: the tap only opens the question "촬영하고 신고할까요?"; nothing is sent until the driver confirms. */
internal fun MainActivity.reportNow(tile: Tile) = showHazard(HazardProposal(0, tile.code, tile.label, 0), byDriver = true)

/**
 * The car thinks it sees something on the road (roadkill, falling rocks, a pothole). It never reports by itself:
 * it asks, and one tap files the report and returns a ticket number. Unanswered, it goes away after [HAZARD_MS].
 */
internal fun MainActivity.showHazard(p: HazardProposal, byDriver: Boolean = false) {
    if (hazard?.id == p.id || heardText != null) return
    hazard = p
    hazardByDriver = byDriver
    hazardPhoto = null
    hazardKey = java.util.UUID.randomUUID().toString()
    hazardSeenAt = System.currentTimeMillis()
    hazardFailed = false
    val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    if (camOk && hazardCamera.available()) {
        hazardCamera.capture { bytes -> ui.post { if (hazard?.id == p.id) hazardPhoto = bytes } }   // taken now, while it is in view
    } else if (!camOk) {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 3)   // asked once; this report goes without a photo
    }
    val token = ++hazardToken
    val line = if (byDriver) "${p.title}, 촬영하고 신고할까요?" else "앞에 ${p.title}이 감지됐어요. 신고할까요?"
    if (info.canSend) { if (!byDriver) speak(line) } else speakThen("$line 네, 라고 말하면 신고할게요", "nudge")
    renderAnswers()
    ui.postDelayed({ if (token == hazardToken && hazard?.id == p.id) { hazard = null; renderAnswers() } }, HAZARD_MS)
}

internal fun MainActivity.answerHazard(said: String) {
    val p = hazard ?: return
    val w = said.replace(" ", "")
    if (listOf("네", "응", "신고", "해줘", "그래").any { it in w }) fileHazard(p) else declineHazard(p)
}

internal fun MainActivity.declineHazard(p: HazardProposal) {
    hazard = null; hazardToken++; hazardPhoto = null   // not reported: the frame is thrown away
    io.execute { try { api.hazardDecline(p.id) } catch (_: Exception) {} }
    renderAnswers()
}

internal fun MainActivity.fileHazard(p: HazardProposal) {
    hazard = null; hazardToken++
    val photo = hazardPhoto; hazardPhoto = null
    renderAnswers()
    val key = hazardKey
    val seenAt = hazardSeenAt
    io.execute {
        val ticket = try { api.hazardReport(p.kind, p.id, currentPlace(), photo, key, seenAt) } catch (_: Exception) { null }
        val shared = try { api.board() } catch (_: Exception) { null }
        ui.post {
            shared?.let { renderBoard(it, force = true) }
            if (ticket != null) {
                sayBubble("앞에 ${p.title}")
                speak("신고가 접수됐어요")
                flash("신고가 접수됐어요 · 접수번호 ${ticket.ticket}" + (if (ticket.located) " · 위치" else " · 위치 없음") + (if (ticket.photo) " · 사진" else ""), "신고")
            } else {   // it may or may not have arrived: the card comes back, and pressing again sends the same id (never a second report)
                hazard = p; hazardPhoto = photo; hazardFailed = true
                val token = ++hazardToken
                ui.postDelayed({ if (token == hazardToken && hazard?.id == p.id) { hazard = null; hazardFailed = false; renderAnswers() } }, HAZARD_MS)
                renderAnswers()
            }
        }
    }
}

/** The offer card: what the car sees, and two big choices. One tap reports. */
internal fun MainActivity.renderHazard(p: HazardProposal) {
    answersCount.text = ""
    attentionCard.removeAllViews()
    attentionCard.visibility = View.VISIBLE
    val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    head.addView(badge(R.drawable.ic_warning, t.moving, 20), LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) })
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(text(if (hazardByDriver) "${p.title}, 촬영하고 신고할까요?" else "앞에 ${p.title}이 감지됐어요", 22f, t.ink, 2))
    col.addView(text(if (hazardPhoto != null) "위치와 사진이 함께 신고돼요 · 근처 이웃에게도 알려요" else "위치와 함께 신고돼요 · 근처 이웃에게도 알려요", 14f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
    head.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    attentionCard.addView(head)
    if (hazardFailed) {
        attentionCard.addView(text("전송하지 못했어요 · 다시 누르면 같은 신고로 보내요", 14f, t.moving, 2),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
    }
    if (!info.canSend) {
        attentionCard.addView(text("“네”라고 말하면 신고해요", 14f, t.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        return
    }
    val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    fun btn(label: String, primary: Boolean, w: Float, action: () -> Unit) {
        row.addView(text(label, 19f, if (primary) Color.WHITE else t.ink, 2).apply {
            gravity = Gravity.CENTER
            background = if (primary) fill(t.moving, 14) else outline()
            setPadding(dp(8), dp(16), dp(8), dp(16))
            setOnClickListener { action() }
            press(this)
        }, LinearLayout.LayoutParams(0, -2, w).apply { if (row.childCount > 0) leftMargin = dp(10) })
    }
    btn(if (hazardByDriver) "촬영하고 신고" else "신고하기", true, 1.6f) { fileHazard(p) }
    btn(if (hazardByDriver) "닫기" else "괜찮아요", false, 1f) { declineHazard(p) }
    attentionCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
}

/** "알리기": tell the road what you see, without being asked. */
internal fun MainActivity.inform(tile: Tile) {
    io.execute {
        val ok = try { api.inform(tile.code) } catch (e: Exception) { false }
        val shared = if (ok) try { api.board() } catch (e: Exception) { null } else null
        ui.post {
            newQuestion("내 제보")
            if (ok) sayBubble(REPORT_PHRASE[tile.code] ?: tile.label)   // the road shows it too, beside our car
            flash(if (ok) "“${tile.label}”을 알렸어요 · 앞 상황에 바로 반영돼요" else "지금은 보낼 수 없어요. 연결을 확인해 주세요", "내 제보")
            shared?.let { renderBoard(it) }
        }
    }
}
