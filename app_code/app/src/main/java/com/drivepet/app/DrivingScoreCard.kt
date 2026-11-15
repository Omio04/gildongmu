package com.drivepet.app

import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView

/** Parked-only summary. No score or reward is announced while the driver is moving. */
class DrivingScoreCard(context: Context) : LinearLayout(context) {
    private val kit = Kit(context)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int = Look.INK, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Theme.text(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }
    init {
        orientation = VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18))
        background = kit.card(24f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
        kit.lift(this, 4f)
    }
    fun render(summary: ScoreSummary, demo: Boolean, official: OfficialDrivingScore?) {
        removeAllViews()
        addView(label(if (demo) "시연 데이터 · 길동무 추정 점수" else "길동무 추정 점수 · 최근 90일", 23f, Look.PERI_DEEP, true))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(ScoreRing(context).apply { score = summary.score; contentDescription = summary.score?.let { "길동무 추정 $it 점" } ?: "측정 중, 점수 없음" }, LayoutParams(dp(112), dp(112)))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(18), dp(12), 0, 0)
                val line = when {
                    summary.score == null -> "충분히 측정한 뒤 알려드려요"
                    summary.score!! >= 90 -> "부드럽게 달린 시간이 쌓였어요"
                    else -> "다음 주행도 여유 있게 함께해요"
                }
                addView(label(line, 20f, Look.INK, true))
                addView(label(summary.explanation, 16f, Look.SUB))
                addView(label("GPS 속도 기반 · 블루링크 공식 점수가 아니에요", 15f, Look.SUB))
            }, LayoutParams(0, -2, 1f))
        }, LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val metrics = listOf("급가속 추정" to "${summary.accelerations}회", "급감속 추정" to "${summary.brakes}회",
            "급출발 추정" to "${summary.starts}회", "심야주행" to "${(summary.nightSeconds / 60).toInt()}분")
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            metrics.forEach { (name, value) ->
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8))
                    addView(label(name, 15f, Look.SUB))
                    addView(label(if (summary.seconds == 0.0) "–" else value, 20f, Look.INK, true))
                }, LayoutParams(0, -2, 1f))
            }
        })
        addView(label(if (official == null) "블루링크 공식 점수 · 미연동 (향후 연계 예정)"
            else "블루링크 공식 점수 ${official.value}점 · 기준 ${Days.dayLabel(official.asOfDay)}", 15f, Look.SUB))
        if (demo) addView(label("예시 화면입니다. 실제 기록·펫 보상에 반영되지 않아요.", 15f, Look.CORAL_D, true))
    }
}
