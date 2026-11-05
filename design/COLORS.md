# 색 목록 (낮 / 밤)

앱 코드(`app/src/main/java/com/gildongmu/app/Theme.kt`)에 있는 색을 표로 옮긴 것입니다. 디자이너가 값을 바꿔 주면 개발자가 이 파일 한 곳에 반영합니다.

- 색은 hex 6자리(`#RRGGBB`)입니다. `glow`만 앞 두 자리가 투명도인 8자리(`#AARRGGBB`)입니다.
- 낮 테마는 밝은 바탕에 남색·하늘색, 밤 테마는 거의 검은 바탕에 밝은 하늘색입니다.
- 초록·노랑·빨강은 두 테마에서 같은 뜻입니다(초록 = 주차·정차, 노랑 = 주의, 빨강 = 경고).

| 이름 | 낮 | 밤 | 쓰는 곳 |
|---|---|---|---|
| bgTop | #F7F8FA | #06090F | 화면 배경(위) |
| bgBottom | #F2F4F7 | #0A0F18 | 화면 배경(아래) |
| surface | #FFFFFF | #111722 | 카드 바탕 |
| surfaceAlt | #F1F4F8 | #192130 | 카드 안의 보조 바탕, 눌림 |
| border | #ECEFF2 | #222C3C | 테두리 |
| ink | #0F172A | #E6ECF5 | 기본 글자 |
| muted | #4B5563 | #B7C5D9 | 보조 글자 |
| faint | #6B7280 | #8696AD | 더 옅은 글자 |
| primary | #002C5F | #A8D2FF | 제목·칩 글자(밤에는 밝은 하늘색) |
| accent | #007DA6 | #35C4F3 | 주요 버튼, 강조 글자(액티브 블루) |
| accentSoft | #E3F3F9 | #0F2A3D | 강조 바탕(옅게) |
| navyFill | #002C5F | #14243F | 진한 남색 바탕(메인 큰 버튼) |
| strong | #002C5F | #1D6FD9 | 강한 파랑 |
| parked | #0E9F5C | #2BD17E | 주차·정차 표시(초록) |
| waiting | #E09200 | #F5B03A | 주의(노랑) |
| moving | #D92D20 | #FF5A4D | 경고·위험(빨강) |
| heartSoft | #FFEFF3 | #3A1624 | 단골 하트 바탕 |
| heart | #E5446D | #FF7A9C | 단골 하트 |
| roadTop | #FAFCFE | #04070C | 도로 위쪽 |
| roadBottom | #F1F5FA | #0D141F | 도로 아래쪽 |
| roadEdge | #D5DEE9 | #5A6E8D | 도로 가장자리 선 |
| roadDash | #C3D0DE | #33445F | 차선 점선 |
| carMe | #FFFFFF | #DCE3EE | 내 차 |
| carSame | #00A8E0 | #2DB4F5 | 같은 차종 이웃 |
| carOther | #262D38 | #1A212C | 일반 차 |
| carLive | #1F6FD0 | #2796E0 | 앱을 쓰는 실제 이웃 차(파랑) |
| glow | #3300A8E0 | #6635C4F3 | 답한 차의 빛 번짐(투명도 포함 8자리) |

## 지금 쓰는 방향 (바꿔도 되는 것)
- 브랜드 기준색은 현대 남색 `#002C5F`, 포인트는 `#007DA6`(낮) / `#35C4F3`(밤).
- 그라데이션과 장식 효과는 쓰지 않습니다.
- 글자 대비는 4.5:1 이상을 지키는 쪽으로 정했습니다(햇빛 반사 때문).
