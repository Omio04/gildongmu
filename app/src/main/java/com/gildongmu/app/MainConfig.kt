package com.gildongmu.app

/** MainActivity가 쓰는 상수(예전 companion object)와 작은 클래스.
 * 여기 값을 바꾸면 모든 화면 파일에 적용됩니다. */

internal class Tile(val code: String, val label: String, val icon: Int, val color: Int)
internal class PendingReport(val name: String, val carId: String?, val file: (String) -> Unit)

internal const val TAG = "Gildongmu"
internal const val MAX_ANSWERS = 3
internal const val JAM_KMH = 20f
internal val GRADE_STEPS = listOf(0 to "길 위 새싹", 30 to "길잡이", 100 to "도로 박사", 300 to "도로 영웅")   // same as server/board.py
internal const val CORNER = 16
internal const val ROAD_CARS = 6   // cars the road view has room for; the rest are counted below
internal val HAZARD_TILES = setOf("debris", "roadkill")   // these take a photo and file a ticket
internal const val BANNER_DP = 104   // two reports, or one report with its 3-tap prompt
internal val REPORT_PHRASE = mapOf("accident" to "앞에 사고 났어요", "debris" to "낙하물이 있어요",
    "roadkill" to "로드킬이 있어요", "work" to "공사 중이에요")
// what a profile is built from: the same lists as server/live.py (the server accepts nothing else)
internal val ADJECTIVES = listOf("귀성길", "느긋한", "졸린", "달리는", "든든한", "신나는", "조용한", "반가운", "씩씩한", "여유로운", "부지런한", "포근한")
internal val ANIMALS = listOf("곰", "토끼", "사자", "펭귄", "여우", "고래", "다람쥐", "수달", "고양이", "강아지", "오리", "거북이")
internal val COLORS = listOf("#00AAD2", "#12B76A", "#F5A524", "#E5446D", "#7A5AF8", "#2E90FA")
internal val MODELS = listOf("캐스퍼", "아반떼", "쏘나타", "그랜저", "코나", "투싼", "싼타페", "팰리세이드", "스타리아",
    "아이오닉 5", "아이오닉 6", "아이오닉 9")
internal const val FUSED_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
internal const val INTRO_KEY = "intro_v1"       // the first-run explanation was shown
internal const val PROFILE_KEY = "profile_done"  // the profile was finished or skipped once
internal const val CONSENT_KEY = "consent_v2"   // bump the number when what is collected changes: everyone is asked again
internal const val PHOTO_DAYS = 30               // same as PHOTO_DAYS in server/app_server.py
internal const val PICK_MS = 15_000L     // the card of a tapped car (or of the report reasons) closes by itself
internal val REPORT_REASONS = listOf("reckless" to "난폭 운전", "rude" to "욕설·비하", "weird" to "이상한 질문",
    "spam" to "광고·스팸", "fake" to "허위 제보", "other" to "기타")   // same codes as core.REPORT_REASONS on the server
internal const val ASK_CAR_MS = 60_000L  // "이 차에 물어보기" is forgotten if nobody speaks within this long
internal val QUESTION_HINTS = listOf("나요", "까요", "가요", "인가", "인지", "어때", "어떻게", "어디", "언제", "누구", "뭐", "몇",
    "얼마", "알아요", "아세요", "알려", "궁금", "있나", "없나", "되나", "있어요?", "해요?")
internal const val PROMPT_MS = 10_000L   // an unanswered prompt goes away by itself
internal const val NUDGE_MS = 12_000L    // so does the car's own offer to ask
internal const val HAZARD_MS = 15_000L   // and the offer to report a hazard
internal const val ASK_MS = 30_000L      // how long we wait for a first answer before going back to normal
internal const val BUBBLE_MS = 25_000L   // the question bubble over our car
internal const val ANSWERS_MS = 30_000L  // how long the answers stay before the board comes back by itself
internal val CAUSE_SHORT = mapOf("accident" to "사고", "work" to "공사", "debris" to "낙하물",
    "breakdown" to "고장 차", "merge" to "합류", "crowd" to "차 많음")
