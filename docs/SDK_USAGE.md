# 운전펫 Pleos SDK 사용 정리

브랜치: `feature/without-navihelper` (기준 커밋 `8091d91`, `feature/driving-score-region-day`와 코드 동일)
작성일: 2026-11-15

> 짝이 되는 브랜치 `feature/with-navihelper`는 지도 도감의 위치를 안드로이드 GPS 대신 **NaviHelper**로 받도록 바꾼 버전입니다. 두 브랜치의 차이는 아래 "안드로이드 기본 기능" 표의 첫 줄 하나입니다.

## 1. 쓰는 SDK

| SDK | 버전 | 용도 |
|---|---|---|
| Pleos Vehicle SDK | 2.3.2 | 기어, 방향지시등, 핸들 각도, 브레이크, 주행거리, 주행 가능 거리, 연료·배터리, 안전벨트, 와이퍼 (모두 읽기만) |
| Pleos NaviHelper SDK | 3.0.5 | 목적지·남은 시간·남은 거리, 다음 회전, 길 안내 시작·종료 (읽기만, 시연용 경로 시작은 디버그 빌드만) |

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
| 지도 도감: "길 안내 중"일 때만 펫이 물건을 주워 옴 | NaviHelper `onRouteStarted` / `onRouteEnded` / `onRouteCancelled` / `onDestinationArrived` | `NaviSource.kt`, `PetHub.dexDrive` | `NAVI_ROUTE` |
| 시연용 길 안내 시작 (디버그 빌드만) | NaviHelper `requestRoute` | `NaviSource.startDemoRoute` | `NAVI_ROUTE_SEARCH` (debug 매니페스트) |

모든 차량 신호는 읽기만 합니다. 차를 제어하는 호출은 없습니다.

## 3. SDK가 아니라 안드로이드 기본 기능을 쓰는 곳

| 기능 | 이 브랜치 | 이유 / 대안 |
|---|---|---|
| 지도 도감: 지금 어느 시·도에 있는지 | 안드로이드 `LocationManager` (GPS·네트워크 위치) | **NaviHelper `getCurrentLocationInfo`로 바꿀 수 있음** → `feature/with-navihelper` |
| 운전 점수 (급가속·급감속·급출발 추정) | 안드로이드 GPS 속도 (1초) | Vehicle·NaviHelper·FusedLocation SDK 어디에도 차량 속도가 없음 (SDK 파일에서 확인). 그래서 SDK로 바꿀 수 없음 |
| 펫 음성 | 안드로이드 `TextToSpeech` | Pleos TextToSpeech SDK는 작성 당시 Playground에서 "Coming Soon" (`Speech.kt` 주석) |
| 내비 위 작은 펫 | `SYSTEM_ALERT_WINDOW` (다른 앱 위에 표시) | Pleos 문서에 외부 앱용 떠 있는 창 API가 없음. 에뮬레이터에서는 adb로 권한을 켜야 뜸. 실차·앱마켓 허용 여부 미확인 |
| 백그라운드 유지, 알림 | 포그라운드 서비스, 알림 | 안드로이드 표준 |

## 4. 아직 쓰지 않는 NaviHelper 기능 (참고)

| 기능 | 쓸 수 있는 곳 |
|---|---|
| `getCurrentLocationInfo` / `onCurrentLocationInfo` (위도·경도·방향·주소) | 지도 도감 위치 → `feature/with-navihelper`에서 사용 |
| `onCameraAlert` (단속 카메라, 제한속도, 과속 여부) | 과속 기록 → `feature/speeding-note`에 구현해 둠 (이 브랜치에는 없음) |
| `onCurrentRoadSpeedLimit` (현재 도로 제한속도) | 미사용 |
| `onCautionPointInfo` (낙석·동물 출현 등 주의 구간) | 미사용 |
| `onTripInfo` (도착 예상 배터리 등) | 미사용 |

## 5. 확인한 것 / 확인하지 못한 것

- 단위 테스트와 빌드 결과는 커밋 메시지에 적습니다.
- 에뮬레이터(Pleos Connect v2.1.0)에서 Vehicle SDK 신호(기어, 경고등, 속도 등)는 Virtual Console로 바꿀 수 있습니다.
- 실차에서는 아무것도 확인하지 못했습니다. 특히 주행 가능 거리 단위, 오버레이 권한, 운전 점수의 GPS 수신은 실차 확인이 필요합니다.
