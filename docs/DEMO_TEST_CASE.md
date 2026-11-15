# 운전펫 시연 영상 테스트 케이스

제출용 시연 영상에서 **운전펫의 기능을 모두 보여 주기 위한** 촬영 순서입니다. 장면마다 "할 일 → 화면에 보여야 할 것 → 보여 주는 기능"을 적었습니다. 화면 예시는 `screenshots/` 번호로 표시했습니다.

- 대상: 디버그 APK (시연 명령은 디버그 빌드에서만 동작)
- 환경: Pleos Connect v2.1.0 에뮬레이터, **원래 해상도 2560×1440 그대로**, 내비 지도가 보이도록 창 모드(화면 있는 상태)로 실행
- 예상 길이: 6~8분

## 0. 준비

```bash
PKG=com.drivepet.app
# 시연 명령: demo <명령> [값]
demo() { adb shell am broadcast --user 10 -a com.drivepet.DEMO -p $PKG --es cmd "$1" ${2:+--es ids "$2"}; }
# Virtual Console (차량 신호를 실제 SDK 경로로 보냄): vc <경로>
adb forward tcp:8088 tcp:8088
vc() { curl -s -X PUT "http://127.0.0.1:8088$1" > /dev/null; }

adb install -r --user 10 app-debug.apk
adb shell pm clear --user 10 $PKG                                        # 처음 실행 장면을 찍기 위해 초기화
adb shell pm grant --user 10 $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant --user 10 $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set --user 10 $PKG SYSTEM_ALERT_WINDOW allow             # 내비 위 작은 펫 (에뮬레이터는 adb로만 켤 수 있음)
adb emu geo fix 127.1386 37.4467                                          # 에뮬레이터 위치를 성남으로 (처음부터 부팅하면 미국 좌표로 잡힘)
adb shell am start --user 10 -n ai.umos.maps.android.navigation.app/.MainActivity   # 내비 앱 켜기 (꺼져 있을 수 있음)
vc "/vehicle/gear?gear=Park"
```
내비 지도가 화면에 뜨고 현재 위치(성남)가 보일 때까지 기다린 뒤 촬영을 시작합니다.
앱이 차량 권한 창을 띄우면 모두 허용합니다.

## 1. 처음 실행: 소개와 이름 정하기
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| 앱 실행 | 펫 소개 말풍선 (01) | 온보딩 |
| 이름 직접 입력 (최대 8자) → 완료 | 이름 입력 칸, 확인 단계 (02, 03) | 이름 정하기 |

## 2. 홈 화면
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| 홈 둘러보기 | 방(벽지·창밖·가구)과 펫, 오른쪽 빠른 버튼(미니게임, 지도 도감, 운전 기록, 배지) (04) | 홈 |
| `demo km 1000` → `demo km 3000` → `demo km 5000` | 누적 거리에 따라 펫 성장 단계가 바뀜 | 성장 단계 (주행거리, Vehicle SDK Odometer) |

## 3. 메뉴 5개
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| 메뉴 → 캐릭터 정보 | 성장, 포인트, 운전 기록 요약 (05, 06) | 캐릭터 정보 |
| `demo mission_part` → 미션, `demo mission_done` → 보상 받기 | 오늘의 미션 진행과 보상 (07) | 미션 |
| `demo points` → 상점에서 옷·소품 구매 | 포인트 차감, 구매 완료 (08) | 상점 |
| 인벤토리에서 입히고 방에 놓기 | 펫 옷과 방 소품이 바뀜 (09) | 인벤토리 |
| 설정 | 내비 위 작은 펫 설정 (10) | 설정 |

## 4. 미니게임
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| 미니게임 → 시작 → 진행 → 끝 | 준비, 진행, 결과와 획득 포인트 (11~13) | 미니게임 (하루 포인트 상한) |

## 5. 펫 상태
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| `demo mood 50` | 화난 모습 (18) | 기분 60 미만 = 화남 |
| `demo mood 20` | 다친 모습 (20) | 기분 30 미만 = 다침 |
| `demo mood 80` → `demo battery 10` | 배고픈 모습 (19) | 연료·배터리 15% 이하 = 배고픔 (Vehicle SDK Fuel / EvBattery) |
| `demo battery 80` | 원래 모습 | |

## 6. 출발 (Vehicle SDK)
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| `vc "/vehicle/gear?gear=Drive"` | 펫 화면이 닫히고 내비 위에 작은 펫이 뜸 (15, 37, 38) | 기어 감지 (Vehicle SDK Gear), 내비 위 작은 펫 |
| `vc "/vehicle/controls?speed=60&accelerator=20&brake=0&steering=0"` | 왼쪽 주행 정보에 60km/h | 주행 |
| `demo belt_off` | "안전벨트를 매 주세요" 알림 (44) | 안전벨트 (Vehicle SDK Seat) |
| `demo belt_on` | "좋아, 안전하게 가자" | |
| `demo wiper_on` | "비가 와요" 알림 (43) | 비 알림 (Vehicle SDK Wiper) |
| `demo battery 10` + `demo range 40` | "배가 고파요 · 배터리 10% · 앞으로 약 40km" (43) | 배고픔 알림, 주행 가능 거리 (Vehicle SDK Odometer) |

## 7. 길 안내 (NaviHelper SDK)와 지도 도감 줍기
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| `demo route` | 내비가 대구로 길 안내 시작, 작은 펫 옆에 다음 회전과 남은 시간·거리 (16) | 길 안내 정보 (NaviHelper Destination / TBT) |
| 그대로 주행 | 펫이 달려가서 지역 물건을 주워 오고 카드로 보여 줌 (42) | 지도 도감 줍기 (길 안내 중 + 지금 지역) |
| (빨리 보이려면) `demo find` | 바로 주워 옴 | |

## 8. 운전 습관 (Vehicle SDK)
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| `demo turn_ok` | 깜빡이 켜고 회전 → 펫 기분↑, 포인트 | 깜빡이 사용 (TurnSignal + SteeringWheel) |
| `demo turn_bad` | 깜빡이 없이 회전 → 펫 놀람 | 깜빡이 없이 회전 |
| `demo harsh` | 핸들을 확 돌림 → 펫 놀람 | 급한 핸들 |
| `demo brake_bad` | 브레이크 짧게 3번 → 펫 놀람 | 덜컹 브레이크 (Brake) |

실제 차량 신호 경로로 보여 주려면 Virtual Console을 씁니다: `vc "/vehicle/turn-signal?mode=BlinkLeft"` 후 `vc "/vehicle/controls?speed=30&accelerator=10&brake=0&steering=120"`, 다시 `steering=0`, `vc "/vehicle/turn-signal?mode=Off"`.

## 9. 도착
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| `vc "/vehicle/controls?speed=0&accelerator=0&brake=30&steering=0"` → `vc "/vehicle/gear?gear=Park"` | 주행 결과 카드(포인트, 거리, 주운 물건, 첫 배지) (17, 23), 펫 화면이 저절로 열림 | 주행 종료, 결과 카드 |

## 10. 지도 도감
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| 지도 도감 열기 | 17개 시·도 섬, 찾은 물건과 남은 칸 (14, 39~41) | 지도 도감 |
| `demo region seoul` → `demo find` | 서울에서 물건 획득 | 지역별 수집 |

## 11. 운전 기록과 점수
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| `demo log_demo 45` → 운전 기록 | 달력(점수 색), 이번 주·지난주 비교 (24, 25) | 깜빡이 사용 점수 달력 |
| 날짜 하나 누르기 | 그날 점수, 회전 수, 참고 기록(덜컹 브레이크·급한 핸들) (26) | 날짜별 상세 |
| 배지 보기 | 잠긴 배지와 받은 배지 (27, 28) | 배지와 보상 |
| 점수 설명 열기 | 점수 계산 방법 (29) | 점수 안내 |
| `demo score_demo` | 운전 점수 카드 87점 예시 (급가속·급감속·급출발·심야) | 실험용 운전 점수 (블루링크 항목 참고) |
| `demo score_live` | 원래 상태로 | |

## 12. 설정
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| 설정 → 내비 위 작은 펫 켜기·끄기 (`demo follow_on` / `demo follow_off`) | 작은 펫이 내비 위에 나타나거나 사라짐 | 작은 펫 설정 |
| 운전 기록 지우기 | 확인 창 후 기록 삭제 (31) | 기록 삭제 |

## 13. NaviHelper 위치 확인 (`feature/with-navihelper` 브랜치만)
| 할 일 | 보여야 할 것 | 기능 |
|---|---|---|
| 앱 실행 후 `adb logcat -s DrivePet` | `navi connected` 다음에 `navi position: region gyeonggi` 로그 | 지역 판별이 NaviHelper 현재 위치로 동작 |
| 지도 도감 | 지금 있는 지역(에뮬레이터 기본 위치: 경기도 성남)이 표시됨 | |

## 촬영 체크리스트
| # | 기능 | 장면 | 통과 |
|---|---|---|---|
| 1 | 온보딩, 이름 입력 | 1 | ☐ |
| 2 | 홈, 성장 단계 | 2 | ☐ |
| 3 | 캐릭터 정보, 미션, 상점, 인벤토리, 설정 | 3 | ☐ |
| 4 | 미니게임 | 4 | ☐ |
| 5 | 화남, 다침, 배고픔 | 5 | ☐ |
| 6 | 주행 감지와 내비 위 작은 펫 | 6 | ☐ |
| 7 | 안전벨트, 비, 배고픔 알림 | 6 | ☐ |
| 8 | 길 안내 정보 (NaviHelper) | 7 | ☐ |
| 9 | 지도 도감 줍기 | 7, 10 | ☐ |
| 10 | 깜빡이, 급한 핸들, 덜컹 브레이크 | 8 | ☐ |
| 11 | 주행 결과 카드 | 9 | ☐ |
| 12 | 운전 기록, 배지, 운전 점수 | 11 | ☐ |
| 13 | 설정 | 12 | ☐ |
| 14 | NaviHelper 위치 (with 브랜치) | 13 | ☐ |

## 알려진 주의점
- 화면 없이(`-no-window`) 띄운 에뮬레이터에서는 내비 지도가 "실행이 지연되고 있습니다"에서 멈추고, NaviHelper 요청에 `navi not ready`로만 답했습니다(2026-11-15 확인). 창 모드로 촬영하세요.
- 에뮬레이터를 처음부터 부팅하면 내비 앱이 꺼져 있고 GPS가 미국 좌표로 잡힙니다. 준비 단계의 `geo fix`와 내비 실행을 빼먹으면 지도 도감과 길 안내 장면이 나오지 않습니다.
- 내비 위 작은 펫은 `SYSTEM_ALERT_WINDOW`를 adb로 켜야 에뮬레이터에 뜹니다.
- 시연 명령(`demo …`)은 디버그 빌드에서만 동작합니다.
- 실험용 운전 점수는 GPS 속도가 필요해서, 에뮬레이터에서는 `score_demo` 예시 화면으로 보여 줍니다.
