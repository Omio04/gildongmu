# 운전펫 Pleos SDK 사용 정리

브랜치: `feature/with-navihelper` (기준: `feature/without-navihelper` + 지도 도감 위치를 NaviHelper로 변경)
작성일: 2026-11-15

> 짝이 되는 브랜치 `feature/without-navihelper`는 같은 코드에서 지도 도감 위치만 안드로이드 GPS로 받는 버전입니다. 두 브랜치의 차이는 지도 도감의 위치를 어디서 받느냐 하나입니다.

### `feature/without-navihelper`에서 바뀐 것
| 파일 | 변경 |
|---|---|
| `NaviSource.kt` | NaviHelper `getCurrentLocationInfo()`를 연결 직후와 3초마다 요청하고, `onCurrentLocationInfo`로 받은 위도·경도를 넘김. 0,0이나 잘못된 값은 버림 (`NaviPositions.usable`) |
| `PetHub.kt` | 지역 판별을 `NaviSource`의 위치로 함 (`onNaviPosition`). 안드로이드 GPS의 위치는 지역 판별에 쓰지 않고, 운전 점수용 속도에만 씀. 지역이 바뀌면 `navi position: region …` 로그 |
| `DexLayer.kt` | 도감 안내 문구: "위치 권한이 없어서" → "내비게이션에 연결되지 않아서" (지역 판별에 안드로이드 위치 권한이 필요 없어짐) |
| `LocationSource.kt` | 설명 주석만 변경 |
| `NaviPositionsTest.kt` | 새 단위 테스트 4개 |

## 1. 쓰는 SDK

| SDK | 버전 | 용도 |
|---|---|---|
| Pleos Vehicle SDK | 2.3.2 | 기어, 방향지시등, 핸들 각도, 브레이크, 주행거리, 주행 가능 거리, 연료·배터리, 안전벨트, 와이퍼 (모두 읽기만) |
| Pleos NaviHelper SDK | 3.0.5 | 목적지·남은 시간·남은 거리, 다음 회전, 길 안내 시작·종료, **현재 위치** (읽기만, 시연용 경로 시작은 디버그 빌드만) |

쓰지 않는 Pleos SDK: FusedLocation, TextToSpeech, SpeechToText, Gleo Interface.

## 2. 기능별 SDK 사용

| 앱 기능 | SDK 신호 (호출) | 코드 | 권한 |
|---|---|---|---|
| 주행·주차 판단 (주행 시작하면 펫 화면 닫고 작은 펫 띄우기, P에서 펫 화면 열기, 주행 결과 카드) | Vehicle Gear `getCurrentGearSelectionState` / `registerGearSelection` | `VehicleSource.kt`, `PetHub.setGear` | `CAR_DRIVE` |
| 깜빡이 사용 점수 (운전 기록 달력, 배지, 펫 기분·포인트) | Vehicle TurnSignal `registerTurnSignalState` + SteeringWheel `registerSteeringWheelAngle` (회전 감지) | `VehicleSource.kt`, `PetEngine.onTurnSignal` / `onSteeringAngle` | `CAR_LIGHTS`, `CAR_DRIVE` |
| 급한 핸들 (참고 기록) | Vehicle SteeringWheel 각도 변화 속도 | `PetEngine.onSteeringAngle` | `CAR_DRIVE` |
| 덜컹 브레이크 (참고 기록), 신호 대기 판단 | Vehicle Brake `registerBrakePedalStatus` | `PetEngine.onBrake`, `PetHub.onBrake` | `CAR_DRIVE` |
| 펫 성장 단계, 주행 거리, 미션, 주행 포인트 | Vehicle Odometer `registerDistanceDriven` | `PetHub.onOdometer` | `CAR_DRIVE` |
| 배고픔 상태와 알림 ("배가 고파요") | Vehicle Fuel `registerFuelPercent`, EvBattery `registerEvBatteryStateOfCharge` | `PetHub.onEnergy` | `CAR_ENERGY` |
| 배고픔 알림의 "앞으로 약 N km" | Vehicle Odometer `registerRangeRemainingListener` | `PetHub.onRange` | `CAR_DRIVE` |
| 안전벨트 알림 ("안전벨트를 매 주세요") | Vehicle Seat `registerSeatBeltBuckledState` | `PetHub.onSeatBelt` | `CAR_SEATS` |
| 비 알림 ("비가 와요") | Vehicle Wiper `registerWiperState` | `PetHub.onWiper` | `CAR_WINDOWS` |
| 내비 위 작은 펫의 길 안내 표시 (다음 회전, 남은 시간·거리) | NaviHelper `getDestinationInfo` / `onDestinationInfo`, `getTBTInfo` / `onTBTInfo` | `NaviSource.kt` | `NAVI_ROUTE` |
| **지도 도감: 지금 어느 시·도에 있는지** | NaviHelper `getCurrentLocationInfo` / `onCurrentLocationInfo` (3초마다) | `NaviSource.kt`, `PetHub.onNaviPosition` | `NAVI_ROUTE` |
| 지도 도감: "길 안내 중"일 때만 펫이 물건을 주워 옴 | NaviHelper `onRouteStarted` / `onRouteEnded` / `onRouteCancelled` / `onDestinationArrived` | `NaviSource.kt`, `PetHub.dexDrive` | `NAVI_ROUTE` |
| 시연용 길 안내 시작 (디버그 빌드만) | NaviHelper `requestRoute` | `NaviSource.startDemoRoute` | `NAVI_ROUTE_SEARCH` (debug 매니페스트) |

모든 차량 신호는 읽기만 합니다. 차를 제어하는 호출은 없습니다.

## 3. SDK가 아니라 안드로이드 기본 기능을 쓰는 곳

이 브랜치에서는 지도 도감 위치가 NaviHelper로 옮겨 가서, 안드로이드 위치(GPS)는 운전 점수의 속도에만 남았습니다.

| 기능 | 이 브랜치 | 이유 / 대안 |
|---|---|---|
| 운전 점수 (급가속·급감속·급출발 추정) | 안드로이드 GPS 속도 (1초) | Vehicle·NaviHelper·FusedLocation SDK 어디에도 차량 속도가 없음 (SDK 파일에서 확인). 그래서 SDK로 바꿀 수 없음 |
| 펫 음성 | 안드로이드 `TextToSpeech` | Pleos TextToSpeech SDK는 작성 당시 Playground에서 "Coming Soon" (`Speech.kt` 주석) |
| 내비 위 작은 펫 | `SYSTEM_ALERT_WINDOW` (다른 앱 위에 표시) | Pleos 문서에 외부 앱용 떠 있는 창 API가 없음. 에뮬레이터에서는 adb로 권한을 켜야 뜸. 실차·앱마켓 허용 여부 미확인 |
| 백그라운드 유지, 알림 | 포그라운드 서비스, 알림 | 안드로이드 표준 |

## 4. 아직 쓰지 않는 NaviHelper 기능 (참고)

| 기능 | 쓸 수 있는 곳 |
|---|---|
| `onCameraAlert` (단속 카메라, 제한속도, 과속 여부) | 과속 기록 → `feature/speeding-note`에 구현해 둠 (이 브랜치에는 없음) |
| `onCurrentRoadSpeedLimit` (현재 도로 제한속도) | 미사용 |
| `onCautionPointInfo` (낙석·동물 출현 등 주의 구간) | 미사용 |
| `onTripInfo` (도착 예상 배터리 등) | 미사용 |

## 5. 확인한 것 / 확인하지 못한 것

- 빌드 성공, 단위 테스트 126개 통과 (`NaviPositionsTest` 4개 포함).
- 에뮬레이터(Pleos Connect v2.1.0, 화면 없이 실행)에서 확인한 것:
  - 앱이 NaviHelper에 연결됨 (`navi connected`)
  - 3~4초마다 `getCurrentLocationInfo` 요청이 내비 앱까지 전달됨 (내비 쪽 로그 `onCurrentLocationInfoRequest`)
- **확인하지 못한 것: 위치 응답이 실제로 와서 지역이 정해지는 것.** 화면 없이 띄운 에뮬레이터에서는 내비 앱이 계속 `navi not ready`(지도 준비 안 됨)로 답했습니다. 내비 지도가 뜨는 창 모드 에뮬레이터에서 `adb logcat -s DrivePet`의 `navi position: region …` 로그로 확인해야 합니다.
- 에뮬레이터는 내비 앱이 자동으로 켜지지 않을 수 있습니다. 내비가 꺼져 있으면 위치 요청에 답할 곳이 없습니다 (실차는 내비가 항상 실행 중일 것으로 예상, 미확인).
- 에뮬레이터(Pleos Connect v2.1.0)에서 Vehicle SDK 신호(기어, 경고등, 속도 등)는 Virtual Console로 바꿀 수 있습니다.
- 실차에서는 아무것도 확인하지 못했습니다. 특히 주행 가능 거리 단위, 오버레이 권한, 운전 점수의 GPS 수신은 실차 확인이 필요합니다.
