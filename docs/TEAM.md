# 팀 역할과 협업 (개발 3명 + 디자인 1명)

> 초안입니다. 아래 역할 배정은 제안이고, 누가 무엇을 맡을지는 팀이 정합니다.

## 개발 3명의 나눔 제안
| 담당 | 맡는 것 | 주로 보는 파일 |
|---|---|---|
| **A. 총괄·플랫폼** | 화면 흐름과 구조, 주행·정차 판단, Pleos SDK(NaviHelper·Vehicle·FusedLocation, 나중에 STT·TTS), 빌드·릴리스·GitHub, 기획서·영상 정리 | `MainActivity.kt`(구조), `DrivingMonitor.kt`, `PleosClients.kt`, `NaviClient.kt`, `build.gradle.kts` |
| **B. 픽업·결제** | 가게 등록·조회, 주문 진행 상태, 결제 흐름(지금은 모의), 가게 화면, 실제 데이터(공공데이터·지도 API) 연동, 결제 인증 방식 조사 | `Errands.kt`, `server/shops.py`, `server/static/shop.html`, `tests/test_shops.py`, 앱의 픽업 카드 부분 |
| **C. 촬영·차량 간 소통** | 위험 신고(촬영·사진·접수번호), 이웃 질문·답·말풍선, 반경(300m→단계 확장), 이웃 두 대 이상 시험, 음성 질문, 신고·차단 | `HazardCamera.kt`, `server/hazards.py`, `server/core.py`, `server/questions.py`, `server/chat.py`, `server/live.py`, `VoiceInput.kt`, `RoadView.kt` |

디자이너(세린)는 UX·UI(화면 구조, 색, 글꼴, 컴포넌트)를 맡고, 색·글꼴 값은 `Theme.kt` 한 곳에 모여 있어 그 파일이 디자인과 개발의 접점입니다.

## 서로 맞닿는 곳 (먼저 약속해 둘 것)
- **서버 API 규칙:** 앱↔서버는 JSON over HTTP입니다. 새 API는 `server/app_server.py` 맨 위 설명에 한 줄씩 적고, 서버 쪽 테스트를 같이 올립니다. 바꾸면 담당자끼리 먼저 알립니다.
- **주행 중 규칙:** 주행 중에는 터치 입력을 막고 음성만 허용합니다. 모든 담당자가 새 화면을 만들 때 이 규칙을 지킵니다(`touchOk()`).
- **한 파일 문제:** `MainActivity.kt`가 한 파일이라 셋이 동시에 고치면 충돌합니다. 합류 전에 기능별 파일(`PickupUi.kt`, `HazardUi.kt`, `RoadChatUi.kt`)로 먼저 나누는 것을 권합니다. 이 분리는 한 사람이 한 번에 하고, 그 사이 다른 사람은 서버 쪽을 만집니다.
- **정직 표시:** 가게·결제·샘플 데이터는 화면과 문서에 "샘플·모의"라고 계속 적습니다.

## 협업 방식
1. **브랜치:** `main`은 항상 빌드·테스트가 통과하는 상태로 두고, 기능마다 브랜치(`pickup-...`, `hazard-...`)를 따서 합칩니다.
2. **합치기 전 확인:** `python -m unittest discover -s tests`(서버)와 `./gradlew :app:testDebugUnitTest`(앱)가 통과해야 합니다.
3. **하루 10분 공유:** 어제 한 것, 오늘 할 것, 막힌 것.
4. **디자인 전달:** 디자인 수정은 피그마 코멘트로 남기고, 개발이 어려운 부분은 대안을 함께 제시합니다.
5. **마감 역산:** 11/15 수정 제출 → 12/3 PT. 새 기능보다 "한 장면을 완벽하게"(`docs/DEMO.md`)가 우선입니다.

## 남은 10일의 순서 (제안)
1. 오늘~: 서명 동의서 받기(팀원 4명), 팀명·역할 확정, 팀 참가로 바꿀 수 있는지 문의
2. 담당 확정 후 `MainActivity` 분리 → 각자 담당 기능 보강
3. 새 화면으로 시연 영상 → 기획서 수정본 → 11/15 전에 제출
