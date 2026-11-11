package com.drivepet.app

import kotlin.random.Random

/** things the pet does now and then when nothing else is going on */
enum class Idle(val durationMs: Long) {
    YAWN(2600), HICCUP(900), MUTTER(0), WOBBLE(1500)
}

/** moments in the app where the pet says something that is not a driving reaction */
enum class Ev { GREET, NAMED, REWARD, BOUGHT, TAKE_OFF, TRY_ON, POKE }

/**
 * How the pet talks, by growth stage. The words are chosen at random from a few per situation, so it does not say the same
 * thing every time.
 *  - egg: cannot talk; it only makes sleeping sounds (and not every time)
 *  - infant: baby talk, mispronounces, short
 *  - rebel: blunt and bored, pretends not to care
 *  - adult: calm and reliable, a little warmer
 * A null result means "say nothing".
 */
object Voice {
    private val rnd = Random.Default
    private fun List<String>.pick() = this[rnd.nextInt(size)]

    // ------------------------------------------------------------------ the egg
    private val EGG_SOUNDS = listOf("쿨쿨…", "음냐…", "…zZz", "후우…", "쿨…")
    private fun egg(chance: Float = 0.55f): String? = if (rnd.nextFloat() < chance) EGG_SOUNDS.pick() else null

    // ------------------------------------------------------------------ driving reactions
    private val INFANT = mapOf(
        Kind.GOOD_SIGNAL to listOf("깜빡! 짝짜꿍~", "빠알~ 깜빠기 조아!", "우와~ 잘해쪄!", "깜빡깜빡 꺄르르!"),
        Kind.MISSED_SIGNAL to listOf("앙… 깜빠기 어옹…", "으엥? 까먹어쪄?", "깜빡… 깜빡이…?"),
        Kind.JERKY_BRAKE to listOf("으앙! 덜컹해쪄!", "아야야… 무서웡", "흐엥… 쿵 해쪄"),
        Kind.HARSH_TURN to listOf("빙글빙글! 어지러웡", "우왕! 핑그르~", "으에… 핑핑 돌아"),
        Kind.CALM to listOf("냠냠… 편안해~", "우웅… 졸료…", "뿌우~ 조아", "아빠빠~ 둥실둥실"),
        Kind.LEVEL_UP to listOf("빵빠레! 레벨 업!", "나 컸다! 우와!", "마마! 나 컸쪄!"),
        Kind.TRIP_END to listOf("다 와쪄! +%d점 짝짝", "+%d점! 또 가자아~", "와~ %d점 모아쪄!"),
    )
    private val REBEL = mapOf(
        Kind.GOOD_SIGNAL to listOf("…흠. 뭐, 나쁘진 않네.", "깜빡이? 당연한 거 아냐.", "그 정도는 기본이지."),
        Kind.MISSED_SIGNAL to listOf("…깜빡이.", "하. 또 까먹었네.", "방금 그거 뭐야."),
        Kind.JERKY_BRAKE to listOf("…덜컹했어. 일부러냐?", "아, 목 아파.", "살살 좀 해."),
        Kind.HARSH_TURN to listOf("아 진짜… 핸들 좀.", "어지럽거든?", "…장난해?"),
        Kind.CALM to listOf("…뭐, 이 정도면 괜찮네.", "조용해서 좋아. 계속 그래.", "…졸리진 않거든."),
        Kind.LEVEL_UP to listOf("레벨? 별거 아니거든.", "…올랐네. 그래서?", "흥. 당연한 거지."),
        Kind.TRIP_END to listOf("다 왔어? …수고. +%d점.", "점수? +%d점. 그냥 받아줄게.", "+%d점. 별로 안 기쁘거든."),
    )
    private val ADULT = mapOf(
        Kind.GOOD_SIGNAL to listOf("깜빡이, 완벽해. 늘 고마워.", "좋아, 그 호흡이야.", "배려 있는 운전이네."),
        Kind.MISSED_SIGNAL to listOf("깜빡이 하나만 더 챙겨볼까?", "다음엔 깜빡이를 먼저 켜 보자."),
        Kind.JERKY_BRAKE to listOf("조금만 부드럽게 가 보자.", "급하지 않아도 괜찮아."),
        Kind.HARSH_TURN to listOf("핸들은 천천히, 안전이 먼저야.", "숨 한 번 고르고 가자."),
        Kind.CALM to listOf("지금 아주 편안해. 이대로 가자.", "좋은 흐름이야.", "창밖 풍경이 좋네."),
        Kind.LEVEL_UP to listOf("한 단계 더 컸네! 함께라 든든해.", "레벨 업, 우리 꽤 잘 맞아."),
        Kind.TRIP_END to listOf("오늘도 수고했어. +%d점, 푹 쉬어.", "안전하게 도착! +%d점, 고마워."),
    )

    /** the line for a driving reaction. [r] carries the original text; the adult sometimes keeps it. */
    fun react(stage: GrowthStage, r: Reaction): String? {
        val table = when (stage) {
            GrowthStage.EGG -> return egg()
            GrowthStage.INFANT -> INFANT
            GrowthStage.REBEL -> REBEL
            GrowthStage.ADULT -> ADULT
        }
        if (stage == GrowthStage.ADULT && rnd.nextFloat() < 0.25f) return r.say   // now and then the plain, informative line
        val line = table[r.kind]?.pick() ?: return r.say
        return if ("%d" in line) line.format(r.xpDelta) else line
    }

    // ------------------------------------------------------------------ events in the app
    private val EVENTS: Map<GrowthStage, Map<Ev, List<String>>> = mapOf(
        GrowthStage.INFANT to mapOf(
            Ev.GREET to listOf("어! 왔다!", "마… 마…?", "바부~", "꺄! 안뇽!"),
            Ev.NAMED to listOf("%s? 나야? 나?", "%s! 으히히~", "%s… 내 이름?"),
            Ev.REWARD to listOf("우와! 내꺼?", "고마웡~", "%s 이쪄!"),
            Ev.BOUGHT to listOf("새거? 새거!", "%s! 우와아~"),
            Ev.TAKE_OFF to listOf("어… 어디가쪄?", "%s 빠이빠이~"),
            Ev.TRY_ON to listOf("예뻐? 응? 응?", "%s 이쪄! 이뻐?"),
            Ev.POKE to listOf("꺄하하! 간지러워~", "또! 또!", "어푸푸~ 쓰담쓰담!", "마마~ 조아!"),
        ),
        GrowthStage.REBEL to mapOf(
            Ev.GREET to listOf("…왔어?", "늦었네.", "아, 왔어.", "…기다린 거 아니거든."),
            Ev.NAMED to listOf("%s… 흠, 그냥 그래.", "%s. 뭐, 쓸 만하네."),
            Ev.REWARD to listOf("…고마워. 별 뜻 없어.", "쳇, 받아둘게."),
            Ev.BOUGHT to listOf("…나쁘지 않네, 이거.", "내 취향은 아닌데. …쓸게."),
            Ev.TAKE_OFF to listOf("…벗으라고?", "%s 치웠어. 상관없어."),
            Ev.TRY_ON to listOf("…뭐, 괜찮지?", "어때? …안 물어봤어."),
            Ev.POKE to listOf("…건드리지 마.", "뭐, 왜.", "…한 번만이야.", "흥. 간지럽거든?"),
        ),
        GrowthStage.ADULT to mapOf(
            Ev.GREET to listOf("어서 와. 오늘도 수고했어.", "왔구나, 기다렸어.", "오늘은 어디까지 가볼까?"),
            Ev.NAMED to listOf("%s, 이제 정말 우리 이름이네.", "%s. 마음에 쏙 들어."),
            Ev.REWARD to listOf("고마워, 잘 쓸게.", "오늘도 안전운전 덕분이야."),
            Ev.BOUGHT to listOf("멋진데? 소중히 쓸게.", "%s, 마음에 들어."),
            Ev.TAKE_OFF to listOf("치웠어. 필요하면 또 말해줘.", "%s 잘 넣어둘게."),
            Ev.TRY_ON to listOf("어때, 어울려?", "%s, 한번 입어볼게."),
            Ev.POKE to listOf("후후, 간지러워.", "왜 불렀어? 오늘도 수고했어.", "토닥토닥, 고마워.", "같이 있어서 든든해."),
        ),
    )

    /** [arg] is the name or the item the line is about; [fallback] is the plain line used when nothing fits */
    fun event(stage: GrowthStage, ev: Ev, arg: String = "", fallback: String? = null): String? {
        if (stage == GrowthStage.EGG) return egg(0.4f)
        val line = EVENTS[stage]?.get(ev)?.pick() ?: return fallback
        return if ("%s" in line) line.format(arg) else line
    }

    // ------------------------------------------------------------------ idle moments
    private val IDLE: Map<GrowthStage, Map<Idle, List<String>>> = mapOf(
        GrowthStage.EGG to mapOf(Idle.WOBBLE to listOf("음냐…", "…zZ", "쿨…", "으음…")),
        GrowthStage.INFANT to mapOf(
            Idle.YAWN to listOf("하아암~ 졸료…", "아함~ 쿨…"),
            Idle.HICCUP to listOf("딸꾹!", "딸꾹… 딸꾹!"),
            Idle.MUTTER to listOf("바부바…", "응마마~", "뿌잉?", "까꿍!", "냠냠…"),
        ),
        GrowthStage.REBEL to mapOf(
            Idle.YAWN to listOf("…하품 아님.", "하아… 별로 안 졸려."),
            Idle.MUTTER to listOf("…", "…뭘 봐.", "심심하다.", "나중에.", "…됐어."),
        ),
        GrowthStage.ADULT to mapOf(
            Idle.YAWN to listOf("하암… 잠깐 눈 붙일까?", "후… 조금 나른하다."),
            Idle.MUTTER to listOf("오늘 날씨 좋네.", "♪ 흥얼흥얼", "다음 휴게소엔 뭐 먹을까?", "오늘도 안전하게 가자."),
        ),
    )

    fun idle(stage: GrowthStage, act: Idle): String? = IDLE[stage]?.get(act)?.pick()
}
