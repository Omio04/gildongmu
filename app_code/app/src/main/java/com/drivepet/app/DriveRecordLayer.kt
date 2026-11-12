package com.drivepet.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The driving record screen ("운전 기록"), opened from the home screen. Like the rest of the app screen it only exists
 * while parked (P); the activity closes it in any other gear.
 *  - top: the averages of the last 7 and 30 days, the change from last week, the month's driven days and distance
 *  - left: the month calendar (each driven day a chip in its score's colour), and under it every badge
 *  - right: the chosen day in detail, with a word from the pet
 * The score is the 깜빡이 사용 점수 (see [DriveLog]); jerky braking and sharp turns are shown as notes, never scored.
 */
class DriveRecordLayer(context: Context, private val onClose: () -> Unit) : FrameLayout(context) {
    private val kit = Kit(context)
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private val night = Theme.night
    private val today = currentDayKey()
    private var month = Days.month(today)
    private var selected = today
    /** the record version on screen: it is drawn again only when it changed */
    private var shown = -1
    private var columns = 6

    private val statsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val monthTitle = title("", 27f)
    private val prev = ChevronButton(context, true)
    private val next = ChevronButton(context, false)
    private val toToday = TextView(context)
    private val calendar = MonthCalendarView(context) { pick(it) }
    private val badgeCount = title("", 17f, Look.HONEY_DEEP)
    private val badgeGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val badgeCard = LinearLayout(context)
    private val detail = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val leftScroll = ScrollView(context)
    private val rightScroll = ScrollView(context)
    private var popup: View? = null

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (night) intArrayOf(0xFF0E1650.toInt(), 0xFF16246A.toInt(), 0xFF1C2E7E.toInt())
            else intArrayOf(0xFFE6F0FD.toInt(), 0xFFFFF6EA.toInt(), 0xFFFDEFDC.toInt()))
        isClickable = true   // touches do not reach the room underneath

        // ---- the bar along the top: back, the title, and what the score means
        val close = title("‹ 뒤로", 21f).apply {
            gravity = Gravity.CENTER; minHeight = dp(54f); minWidth = dp(112f)
            setPadding(dp(14f), dp(6f), dp(20f), dp(6f))
            background = kit.card(100f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
            kit.lift(this, 3f)
            contentDescription = "운전 기록 닫기"
            setOnClickListener { onClose() }
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(26f), dp(6f))
            background = kit.card(100f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
            kit.lift(this, 3f)
            addView(MenuIcon(context, IconKind.RECORD), lp(dp(46f), dp(46f)))
            addView(title("운전 기록", 27f), lp(-2, -2).apply { leftMargin = dp(10f) })
        }
        val info = TextView(context).apply {
            text = "깜빡이 사용 점수란?"
            minHeight = dp(54f)
            kit.key(this, Kit.KEY_SECONDARY, 18f, 20f, 8f)
            setOnClickListener { showInfo() }
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(6f))
            addView(close)
            addView(FrameLayout(context).apply { addView(head, LayoutParams(-2, -2, Gravity.CENTER)) }, lp(0, -2, 1f))
            addView(info)
        }

        // ---- the month: arrows, the month's name, back to today; the calendar; what the colours mean
        prev.contentDescription = "이전 달"; prev.setOnClickListener { goMonth(-1) }
        next.contentDescription = "다음 달"; next.setOnClickListener { goMonth(1) }
        toToday.apply {
            text = "오늘로"
            minHeight = dp(48f)
            kit.key(this, Kit.KEY_SECONDARY, 17f, 18f, 6f)
            setOnClickListener { month = Days.month(today); pick(today); renderMonth(); renderStats() }
        }
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prev, lp(dp(50f), dp(50f)))
            addView(monthTitle, lp(-2, -2).apply { leftMargin = dp(16f); rightMargin = dp(16f) })
            addView(next, lp(dp(50f), dp(50f)))
            addView(View(context), lp(0, 1, 1f))
            addView(toToday)
        }
        val legend = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(legendChip(Bands.GOOD, "90~100점"))
            addView(legendChip(Bands.FAIR, "70~89점"), lp(-2, -2).apply { leftMargin = dp(8f) })
            addView(legendChip(Bands.LOW, "0~69점"), lp(-2, -2).apply { leftMargin = dp(8f) })
            addView(legendChip(Bands.NONE, "회전 없음 · 점수 없음"), lp(-2, -2).apply { leftMargin = dp(8f) })
            addView(text("빈칸은 운전하지 않은 날", 14.5f, Look.SUB), lp(-2, -2).apply { leftMargin = dp(14f) })
        }
        val calendarCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(18f))
            background = kit.card(24f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
            kit.lift(this, 4f)
            addView(nav)
            addView(calendar, lp(-1, -2).apply { topMargin = dp(8f) })
            addView(legend, lp(-1, -2).apply { topMargin = dp(14f) })
        }

        // ---- every badge
        badgeCount.apply {
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            background = kit.card(100f, 0xFFFFF2D4.toInt(), 0xFFF6DFA9.toInt(), 1.2f)
        }
        badgeCard.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(20f))
            background = kit.card(24f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
            kit.lift(this, 4f)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title("배지", 26f))
                addView(badgeCount, lp(-2, -2).apply { leftMargin = dp(12f) })
                addView(View(context), lp(0, 1, 1f))
                addView(text("배지를 누르면 조건과 진행 상황을 볼 수 있어요", 14.5f, Look.SUB))
            })
            addView(badgeGrid, lp(-1, -2).apply { topMargin = dp(14f) })
        }

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(statsRow, lp(-1, -2))
            addView(calendarCard, lp(-1, -2).apply { topMargin = dp(14f) })
            addView(badgeCard, lp(-1, -2).apply { topMargin = dp(14f) })
        }
        leftScroll.apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(4f), dp(6f), dp(4f), dp(14f))   // room for the cards' shadows
            addView(left)
            addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
                if (r - l != oldR - oldL) {
                    val want = if (r - l - dp(48f) >= dp(6 * 150f)) 6 else 3   // six across when there is room, else two rows of three
                    if (want != columns) { columns = want; post { renderBadges() } }
                }
            }
        }
        detail.setPadding(dp(22f), dp(20f), dp(22f), dp(24f))
        rightScroll.apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true   // so the empty state can sit in the middle
            background = kit.card(26f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
            clipToOutline = true
            kit.lift(this, 6f)
            addView(detail)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14f), 0, dp(18f), dp(4f))
            addView(leftScroll, lp(0, -1, 1f))
            addView(rightScroll, lp(0, -1, 0.42f).apply { leftMargin = dp(14f); topMargin = dp(6f); bottomMargin = dp(18f) })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, lp(-1, -2))
            addView(body, lp(-1, 0, 1f))
        }, LayoutParams(-1, -1))
        render()
    }

    /** called every second by the screen: draws again only when a drive, a deletion or a badge changed the record */
    fun refresh() { if (PetHub.started && PetHub.recordVersion != shown) render() }

    /** the back key: closes the open card first; false when there is nothing to close here */
    fun back(): Boolean {
        if (popup != null) { closePopup(); return true }
        return false
    }

    /** opened from the home screen's badge box: scroll down to the badges */
    fun showBadges() {
        leftScroll.postDelayed({ leftScroll.smoothScrollTo(0, (badgeCard.top - dp(12f)).coerceAtLeast(0)) }, 150)
    }

    private fun render() {
        if (!PetHub.started) return
        shown = PetHub.recordVersion
        renderStats(); renderMonth(); renderBadges(); renderDetail()
    }

    // ---------------------------------------------------------------- the numbers on top

    private fun withUnit(num: String, unit: String): CharSequence {
        val sb = SpannableStringBuilder(num).append(unit)
        sb.setSpan(RelativeSizeSpan(0.5f), num.length, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun signed(n: Int) = if (n > 0) "+$n" else "$n"

    private fun statCard(label: String, value: CharSequence, valueColor: Int, sub: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18f), dp(14f), dp(16f), dp(14f))
        background = kit.card(22f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
        kit.lift(this, 3f)
        addView(text(label, 16f, Look.SUB, true))
        addView(title(value, 36f, valueColor), lp(-2, -2).apply { topMargin = dp(6f) })
        addView(text(sub, 14.5f, Look.SUB).apply { maxLines = 2 }, lp(-2, -2).apply { topMargin = dp(4f) })
    }

    private fun renderStats() {
        val s = PetHub.driveLog.stats(today, month)
        val waiting = "기록이 쌓이면 보여요"
        fun score(v: Int?): CharSequence = if (v == null) "–" else withUnit("$v", "점")
        fun color(v: Int?) = if (v == null) Look.MUTED else Bands.of(v).text()
        statsRow.removeAllViews()
        statsRow.addView(statCard("최근 7일 평균", score(s.avg7), color(s.avg7), if (s.avg7 == null) waiting else "깜빡이 사용 점수"), lp(0, -1, 1f))
        statsRow.addView(statCard("최근 30일 평균", score(s.avg30), color(s.avg30), if (s.avg30 == null) waiting else "깜빡이 사용 점수"), lp(0, -1, 1f).apply { leftMargin = dp(12f) })
        val wd = s.weekDelta
        val weekSub = when {
            s.weekRate != null && s.weekRateDelta != null -> "깜빡이 사용률 ${s.weekRate}% (${signed(s.weekRateDelta)}%p)"
            s.weekRate != null -> "이번 주 깜빡이 사용률 ${s.weekRate}%"
            else -> "두 주 기록이 있으면 보여요"
        }
        val weekColor = when { wd == null -> Look.MUTED; wd > 0 -> Bands.GOOD.text(); wd < 0 -> Bands.LOW.text(); else -> Look.SUB }
        statsRow.addView(statCard("지난주보다", if (wd == null) "–" else withUnit(signed(wd), "점"), weekColor, weekSub), lp(0, -1, 1f).apply { leftMargin = dp(12f) })
        val label = if (month == Days.month(today)) "이번 달 기록" else "${month % 100}월 기록"
        val monthSub = if (s.monthDays == 0) "운전한 날이 없어요" else "%.1fkm 주행".format(s.monthKm) + (s.monthAvg?.let { " · 평균 ${it}점" } ?: "")
        statsRow.addView(statCard(label, withUnit("${s.monthDays}", "일 운전"), Look.INK, monthSub), lp(0, -1, 1f).apply { leftMargin = dp(12f) })
    }

    // ---------------------------------------------------------------- the calendar

    private fun goMonth(n: Int) {
        val cur = Days.month(today)
        val m = Days.monthPlus(month, n)
        if (m > cur) return
        month = m
        renderMonth(); renderStats()
    }

    private fun pick(day: Int) {
        selected = day
        calendar.selected = day
        renderDetail()
        updateToToday()
        rightScroll.scrollTo(0, 0)
    }

    private fun updateToToday() {
        toToday.visibility = if (month != Days.month(today) || selected != today) View.VISIBLE else View.INVISIBLE
    }

    private fun renderMonth() {
        monthTitle.text = Days.label(month)
        val canNext = month < Days.month(today)
        next.isEnabled = canNext; next.alpha = if (canNext) 1f else 0.35f
        calendar.today = today
        calendar.log = PetHub.driveLog
        calendar.selected = selected
        calendar.month = month
        updateToToday()
    }

    private fun legendChip(tone: Bands.Tone, label: String) = TextView(context).apply {
        text = label; textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(tone.ink)
        setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tone.top, tone.bottom)).apply {
            cornerRadius = dp(100f).toFloat(); setStroke(dp(1.2f).coerceAtLeast(1), tone.edge)
        }
    }

    // ---------------------------------------------------------------- the badges

    private fun renderBadges() {
        val book = PetHub.badges
        badgeCount.text = "${book.earnedCount()}/${Badges.all.size} 달성"
        badgeGrid.removeAllViews()
        var row: LinearLayout? = null
        Badges.all.forEachIndexed { i, b ->
            if (i % columns == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                badgeGrid.addView(row, lp(-1, -2).apply { if (i > 0) topMargin = dp(12f) })
            }
            row?.addView(badgeCell(b), lp(0, -1, 1f).apply { if (i % columns > 0) leftMargin = dp(12f) })
        }
        repeat((columns - Badges.all.size % columns) % columns) { row?.addView(View(context), lp(0, 1, 1f).apply { leftMargin = dp(12f) }) }
    }

    private fun badgeCell(b: Badge): View {
        val book = PetHub.badges
        val earned = book.earned(b)
        val p = b.progress(PetHub.driveLog, today)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8f), dp(12f), dp(8f), dp(14f))
            background = kit.rowPressable(20f)
            addView(MedalView(context, b.medal, earned), lp(dp(92f), dp(92f)))
            addView(title(b.name, 16.5f, if (earned) Look.INK else Look.SUB).apply { gravity = Gravity.CENTER; maxLines = 2; setLineSpacing(0f, 1.1f) },
                lp(-1, -2).apply { topMargin = dp(4f) })
            val status: View = if (earned) {
                val word = when {
                    b.monthly && book.earnedThisMonth(b, today) -> "이번 달 달성"
                    b.monthly -> "${book.times(b)}번 달성"
                    else -> book.firstDay(b)?.let { "${it / 100 % 100}월 ${it % 100}일 달성" } ?: "달성"
                }
                text(word, 13.5f, Look.SAGE_D, true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setCompoundDrawablesRelative(Glyphs.check(context, 13f, Theme.text(Look.SAGE_D)), null, null, null); compoundDrawablePadding = dp(3f)
                }
            } else LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(PillBar(context, 0xFFA9C3F7.toInt(), 0xFF6F8FE6.toInt(), Theme.fill(0xFFF1E4D2.toInt())).apply { max = b.goal; progress = p.coerceAtMost(b.goal) },
                    lp(dp(96f), dp(9f)))
                addView(text(b.progressLabel(p), 13.5f, Look.SUB, true), lp(-2, -2).apply { topMargin = dp(4f) })
            }
            addView(status, lp(-2, -2).apply { topMargin = dp(6f) })
            contentDescription = "${b.name} 배지, " + if (earned) "받음" else "진행 ${b.progressLabel(p)}"
            setOnClickListener { showBadge(b) }
        }
    }

    // ---------------------------------------------------------------- the chosen day

    private fun renderDetail() {
        detail.removeAllViews()
        detail.gravity = Gravity.START
        val log = PetHub.driveLog
        val stage = PetHub.engine.snapshot().stage
        if (log.isEmpty) { emptyState(stage); return }
        detail.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title(Days.dayLabel(selected), 27f))
            if (selected == today) addView(pill("오늘", Look.PERI, Look.WHITE), lp(-2, -2).apply { leftMargin = dp(10f) })
        })
        val rec = log.day(selected)
        if (rec == null) {
            val future = selected > today
            detail.addView(petSays(stage, if (future) DayTalk.FUTURE else DayTalk.REST, PetState.NORMAL, selected), lp(-1, -2).apply { topMargin = dp(18f) })
            detail.addView(text(if (future) "아직 오지 않은 날이에요." else "이 날은 운전 기록이 없어요.\n색이 칠해진 날을 누르면 그날의 기록을 볼 수 있어요.", 16f, Look.SUB).apply {
                setLineSpacing(0f, 1.3f); setPadding(dp(6f), 0, dp(6f), 0)
            }, lp(-1, -2).apply { topMargin = dp(16f) })
            return
        }
        dayDetail(rec, stage)
    }

    private fun dayDetail(rec: DayRecord, stage: GrowthStage) {
        val sc = rec.score
        val tone = Bands.of(sc)
        detail.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ScoreRing(context).apply { score = sc; contentDescription = if (sc == null) "점수 없음" else "깜빡이 사용 점수 ${sc}점" }, lp(dp(138f), dp(138f)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = Bands.word(sc); textSize = 16f; typeface = kit.face; setTextColor(tone.ink)
                    setPadding(dp(14f), dp(5f), dp(14f), dp(5f))
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tone.top, tone.bottom)).apply {
                        cornerRadius = dp(100f).toFloat(); setStroke(dp(1.2f).coerceAtLeast(1), tone.edge)
                    }
                }, lp(-2, -2))
                addView(text("깜빡이 사용 점수", 15.5f, Look.SUB, true), lp(-2, -2).apply { topMargin = dp(8f) })
                addView(text(if (sc == null) "회전이 없어서 점수를 매기지 않았어요" else "회전 ${rec.turns}번 중 ${rec.good}번 깜빡이를 켰어요", 17f, Look.INK, true).apply {
                    setLineSpacing(0f, 1.2f)
                }, lp(-2, -2).apply { topMargin = dp(4f) })
            }, lp(0, -2, 1f).apply { leftMargin = dp(18f) })
        }, lp(-1, -2).apply { topMargin = dp(14f) })

        val talk = DayTalk.of(sc)
        detail.addView(petSays(stage, talk, if (talk == DayTalk.LOW) PetState.ANGRY else PetState.NORMAL, rec.day), lp(-1, -2).apply { topMargin = dp(16f) })

        detail.addView(heading("이 날의 기록"))
        val g = group()
        g.addRow(kv("주행 거리", "%.1fkm".format(rec.km)))
        g.addRow(kv("주행 횟수", "${rec.trips}번"))
        g.addRow(kv("깜빡이 사용률", if (sc == null) "–" else "$sc% (${rec.good}/${rec.turns}번)", if (sc == null) Look.INK else tone.text()))
        g.addRow(kv("깜빡이 없이 회전", "${rec.missed}번"))
        val bonus = PetHub.badges.awardsOn(rec.day).sumOf { it.points }
        g.addRow(kv("획득 포인트", "+${rec.points}P" + if (bonus > 0) "  · 배지 +${bonus}P" else "", Look.HONEY_DEEP))
        detail.addView(g, lp(-1, -2))

        detail.addView(heading("참고 기록 · 점수에는 넣지 않아요"))
        val n = group()
        n.addRow(kv("덜컹 브레이크 (급제동 추정)", "${rec.jerky}번"))
        n.addRow(kv("급한 핸들 (급조향)", "${rec.harsh}번"))
        detail.addView(n, lp(-1, -2))
        detail.addView(text("차가 속도·가속도를 알려 주지 않아서, 브레이크를 짧게 여러 번 밟은 것과 핸들을 빠르게 돌린 것으로 짐작한 값이에요.", 13.5f, Look.SUB).apply {
            setLineSpacing(0f, 1.3f); setPadding(dp(6f), dp(8f), dp(6f), 0)
        })

        val got = PetHub.badges.awardsOn(rec.day).mapNotNull { Badges.get(it.badgeId) }.distinct()
        if (got.isNotEmpty()) {
            detail.addView(heading("이 날 받은 배지"))
            detail.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (b in got) addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(MedalView(context, b.medal, true), lp(dp(66f), dp(66f)))
                    addView(text(b.name, 13f, Look.INK, true).apply { gravity = Gravity.CENTER; maxLines = 2 }, lp(dp(100f), -2))
                    setOnClickListener { showBadge(b) }
                }, lp(-2, -2).apply { rightMargin = dp(6f) })
            })
        }
    }

    /** nothing recorded yet: the pet says it will start from today */
    private fun emptyState(stage: GrowthStage) {
        detail.gravity = Gravity.CENTER
        detail.addView(AvatarView(context).apply { state = PetState.NORMAL }, lp(dp(150f), dp(150f)))
        detail.addView(title("오늘부터 운전 기록이 쌓여요!", 28f).apply { gravity = Gravity.CENTER }, lp(-2, -2).apply { topMargin = dp(18f) })
        detail.addView(bubble(Voice.day(stage, DayTalk.EMPTY), tail = false), lp(-2, -2).apply { topMargin = dp(14f) })
        val first = Badges.get("first")
        val line = if (first == null || PetHub.badges.earned(first)) "0.5km 넘게 달린 주행이 날짜별로 저장돼요. 기록을 지워도 받은 배지와 포인트는 그대로예요."
        else "0.5km 넘게 달린 주행이 날짜별로 저장돼요. 첫 기록이 남으면 '첫 운전 기록' 배지와 ${first.reward}P를 받아요."
        detail.addView(text(line, 16f, Look.SUB).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f)
        }, lp(-1, -2).apply { topMargin = dp(16f) })
    }

    private fun petSays(stage: GrowthStage, talk: DayTalk, state: PetState, seed: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(AvatarView(context).apply { this.state = state }, lp(dp(86f), dp(86f)))
        addView(bubble(Voice.day(stage, talk, seed), tail = true), lp(0, -2, 1f).apply { leftMargin = dp(4f) })
    }

    private fun bubble(line: String, tail: Boolean): TextView {
        val fill = if (night) 0xFF26348F.toInt() else 0xFFFFFDF8.toInt()
        val edge = if (night) 0xFF8FA0E0.toInt() else Look.COCOA
        return title(line, 19f).apply {
            setLineSpacing(0f, 1.2f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(58f)
            if (tail) {
                val b = SpeechBubbleDrawable(d, fill, edge)
                background = b
                setPadding(dp(16f) + b.tailPx, dp(12f), dp(18f), dp(12f))
            } else {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(24f).toFloat(); setStroke(dp(2f), edge) }
                setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
            }
        }
    }

    // ---------------------------------------------------------------- the cards that open over the screen

    private fun openPopup(card: View) {
        closePopup()
        val w = if (width > 0) minOf(dp(580f), (width * 0.9f).toInt()) else dp(580f)
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(0x66462E1A)
            isClickable = true
            setOnClickListener { closePopup() }
            card.isClickable = true   // a touch on the card itself does not close it
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(0, dp(16f), 0, dp(16f)); addView(card) },
                LayoutParams(w, -2, Gravity.CENTER))
            elevation = dp(30f).toFloat()
            alpha = 0f
        }
        popup = scrim
        addView(scrim, LayoutParams(-1, -1))
        scrim.animate().alpha(1f).setDuration(160).start()
    }

    private fun closePopup() {
        popup?.let { removeView(it) }
        popup = null
    }

    private fun popupCard() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(30f), dp(24f), dp(30f), dp(24f))
        background = kit.card(30f, 0xFFFFFCF6.toInt(), Look.LINE, 1.2f)
        kit.lift(this, 12f)
    }

    private fun closeKey() = TextView(context).apply {
        text = "닫기"; minWidth = dp(200f); minHeight = dp(58f)
        kit.key(this, Kit.KEY_PRIMARY, 21f, 24f, 10f)
        setOnClickListener { closePopup() }
    }

    /** a badge: its medal, what to do, how far along, the reward, and whether (when) it was given */
    private fun showBadge(b: Badge) {
        val book = PetHub.badges
        val earned = book.earned(b)
        val p = b.progress(PetHub.driveLog, today)
        val card = popupCard().apply {
            addView(MedalView(context, b.medal, earned), lp(dp(156f), dp(156f)))
            addView(title(b.name, 29f).apply { gravity = Gravity.CENTER }, lp(-2, -2).apply { topMargin = dp(4f) })
            val status = when {
                b.monthly && book.earnedThisMonth(b, today) -> "이번 달에 받았어요 · 지금까지 ${book.times(b)}번"
                b.monthly && earned -> "지금까지 ${book.times(b)}번 받았어요 · 이번 달은 아직이에요"
                earned -> "${Days.dayLabel(book.firstDay(b) ?: today)}에 받았어요"
                else -> "아직 받지 않았어요"
            }
            addView(pill(status, if (earned) Look.SAGE_L else Look.CREAM2, if (earned) Look.SAGE_D else Look.SUB), lp(-2, -2).apply { topMargin = dp(10f) })
            addView(text(b.condition, 18f, Look.INK).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f) }, lp(-1, -2).apply { topMargin = dp(16f) })
            val shownP = if (earned && !b.monthly) b.goal else p
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(PillBar(context, 0xFFA9C3F7.toInt(), 0xFF6F8FE6.toInt(), Theme.fill(0xFFF1E4D2.toInt())).apply { max = b.goal; progress = shownP.coerceAtMost(b.goal) },
                    lp(0, dp(16f), 1f))
                addView(title(b.progressLabel(shownP), 21f, Look.PERI_DEEP), lp(-2, -2).apply { leftMargin = dp(14f) })
            }, lp(-1, -2).apply { topMargin = dp(18f) })
            val note = when (b.id) {
                "streak3", "streak7" -> when {
                    earned -> ""
                    p == 0 -> "90점 이상인 날부터 다시 세요"
                    else -> "지금 ${p}일째 이어지고 있어요"
                }
                "month10" -> "${monthOf(today)}월 진행 상황이에요"
                else -> ""
            }
            if (note.isNotEmpty()) addView(text(note, 14.5f, Look.SUB, true), lp(-2, -2).apply { topMargin = dp(6f) })
            addView(title(if (b.monthly) "보상 +${b.reward}P · 매달 한 번" else "보상 +${b.reward}P", 22f, Look.HONEY_DEEP).apply {
                gravity = Gravity.CENTER_VERTICAL
                setCompoundDrawablesRelative(Glyphs.coin(context, 26f), null, null, null); compoundDrawablePadding = dp(8f)
            }, lp(-2, -2).apply { topMargin = dp(16f) })
            addView(closeKey(), lp(-2, -2).apply { topMargin = dp(20f) })
        }
        openPopup(card)
    }

    private fun monthOf(day: Int) = day / 100 % 100

    /** what the score is and why it is not called a safe-driving score */
    private fun showInfo() {
        val card = popupCard().apply {
            gravity = Gravity.START
            addView(title("깜빡이 사용 점수란?", 28f))
            addView(text("회전할 때 깜빡이를 켠 비율로 그날의 점수를 매겨요. 핸들을 45° 넘게 돌리면 회전 한 번으로 세요.", 18f, Look.INK).apply { setLineSpacing(0f, 1.3f) },
                lp(-1, -2).apply { topMargin = dp(12f) })
            addView(title("점수 = 깜빡이를 켠 회전 ÷ 전체 회전 × 100", 19f, Look.PERI_DEEP).apply {
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                background = kit.card(16f, Look.PERI_L, 0xFFC9D6F6.toInt(), 1.2f)
            }, lp(-2, -2).apply { topMargin = dp(14f) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(legendChip(Bands.GOOD, "90~100점"))
                addView(legendChip(Bands.FAIR, "70~89점"), lp(-2, -2).apply { leftMargin = dp(8f) })
                addView(legendChip(Bands.LOW, "0~69점"), lp(-2, -2).apply { leftMargin = dp(8f) })
                addView(legendChip(Bands.NONE, "점수 없음"), lp(-2, -2).apply { leftMargin = dp(8f) })
            }, lp(-2, -2).apply { topMargin = dp(14f) })
            addView(text(
                "· 0.5km보다 짧은 주행은 기록하지 않아요.\n" +
                    "· 운전하지 않은 날은 빈칸, 운전했지만 회전이 없던 날은 점수 없이 회색이에요.\n" +
                    "· 덜컹 브레이크와 급한 핸들은 차가 속도·가속도를 알려 주지 않아 짐작한 값이라, 점수에 넣지 않고 참고 기록으로만 보여줘요.\n" +
                    "· 현대자동차·TMAP의 안전운전 점수는 이 앱에서 쓸 수 있는 공개 연동이 없어서, 정확히 잴 수 있는 깜빡이로만 점수를 매겨요. 그래서 '안전운전 점수'가 아니라 '깜빡이 사용 점수'라고 불러요.",
                16f, Look.SUB).apply { setLineSpacing(0f, 1.4f) }, lp(-1, -2).apply { topMargin = dp(16f) })
            addView(closeKey(), lp(-2, -2).apply { topMargin = dp(20f); gravity = Gravity.CENTER_HORIZONTAL })
        }
        openPopup(card)
    }

    // ---------------------------------------------------------------- small builders

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun text(s: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s; textSize = sp; setTextColor(Theme.text(color)); if (bold) typeface = Typeface.DEFAULT_BOLD
        byWords(this)
    }

    private fun title(s: CharSequence, sp: Float, color: Int = Look.INK) = kit.title(TextView(context).apply { text = s; byWords(this) }, sp, color)

    /** a Korean line breaks between words, not in the middle of one (Android 13 and later; older ones break as before) */
    private fun byWords(tv: TextView) {
        if (android.os.Build.VERSION.SDK_INT >= 33) tv.lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
    }

    private fun pill(s: String, bg: Int, fg: Int) = kit.title(TextView(context).apply { text = s }, 15f, fg).apply {
        setTextColor(fg)   // the pill keeps its own colours in both themes
        setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(100f).toFloat() }
    }

    private fun heading(t: String) = text(t, 16f, Look.SUB, true).apply { setPadding(dp(6f), dp(20f), 0, dp(6f)) }

    private fun group() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.row(18f)
        setPadding(0, dp(4f), 0, dp(4f))
    }

    private fun LinearLayout.addRow(v: View) {
        if (childCount > 0) addView(View(context).apply { setBackgroundColor(if (night) 0x26FFFFFF else 0xFFF4EADD.toInt()) },
            lp(-1, dp(1f).coerceAtLeast(1)).apply { setMargins(dp(14f), 0, dp(14f), 0) })
        addView(v)
    }

    private fun kv(k: String, v: String, valueColor: Int = Look.INK) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(50f)
        setPadding(dp(18f), dp(9f), dp(18f), dp(9f))
        addView(text(k, 17f, Look.SUB), lp(0, -2, 1f))
        addView(text(v, 17.5f, valueColor, true))
    }
}
