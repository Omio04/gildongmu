# 길동무

팀명: **동무들** (팀 참가)

차량 신호에 반응하는 펫을 키우며 운전 습관(깜빡이 사용·급가속/급감속)을 기록하는 Pleos 차량용 앱입니다.

## 구성
| 위치 | 내용 |
|---|---|
| `app_code/` | 안드로이드 프로젝트 소스 (Kotlin, 패키지 `com.drivepet.app`). Android Studio에서는 이 폴더를 여세요. |
| `apk/동무들_길동무_release.apk` | 제출용 release APK (versionName 0.1, 위 소스에서 빌드) |
| `design/regions/tools/make_islands.py` | 지역 섬 그림 생성 스크립트(참고용, 앱 빌드와 무관) |

## 구현된 기능
- 차량 기어·깜빡이·핸들·브레이크·주행거리·배터리/연료·안전벨트·와이퍼에 반응하는 펫.
- 거리별 성장, 옷·가구, 미션, 미니게임, 17개 지역 지도 도감(길 안내 중 현재 지역의 물건 줍기).
- 주차(P) 상태에서 앱 화면을 열고, 다른 기어에서는 화면을 닫은 뒤 내비 위에 작은 펫을 띄움.
- 날짜별 깜빡이 사용 기록, 배지·보상, 길동무 추정 점수(GPS 속도 기반 급가속·급감속·급출발·심야주행, 최근 90일).
- 사용 Pleos SDK: Vehicle SDK(차량 신호), NaviHelper SDK(길 안내·위치).

## 실행 방법
1. Pleos 공식 가이드(<https://appdevchallenge.auto-hmg.io>)대로 Pleos Connect 에뮬레이터를 설치하고 실행합니다. 에뮬레이터는 **Cold boot**로 켜야 합니다(Quick boot에서는 차량 신호(VHAL)를 쓸 수 없습니다).
2. 에뮬레이터를 켠 뒤 APK를 설치합니다. 이 에뮬레이터는 앱을 **사용자 10번**으로 실행합니다.
   ```bash
   adb install -r --user 10 apk/동무들_길동무_release.apk
   adb shell pm grant --user 10 com.drivepet.app android.permission.ACCESS_FINE_LOCATION
   adb shell pm grant --user 10 com.drivepet.app android.permission.POST_NOTIFICATIONS
   adb shell appops set --user 10 com.drivepet.app SYSTEM_ALERT_WINDOW allow   # 내비 위 작은 펫
   adb shell am start --user 10 -n com.drivepet.app/.MainActivity
   ```
3. 앱 화면은 기어가 **P(주차)**일 때만 열립니다. 에뮬레이터의 Virtual Console(`adb forward tcp:8088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:8088/`)로 차량 신호를 바꿉니다. 아래 명령으로도 바꿀 수 있습니다.
   ```bash
   curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Drive"
   curl -X PUT "http://127.0.0.1:8088/vehicle/controls?speed=30&accelerator=30&brake=0&steering=0"
   ```
4. 지도 도감은 내비(NaviHelper) 위치를 씁니다. 에뮬레이터 위치는 `adb emu geo fix 127.1386 37.4467`(성남)처럼 설정하고 내비 앱을 먼저 켭니다.

## 테스트 환경
- **에뮬레이터: Pleos Connect v2.1.0** (Android 14, API 34, `pleos_car_emulator`)
- **화면 규격: 2560×1440 (가로)** — 에뮬레이터 원래 해상도 그대로 사용.
- 앱 설정: minSdk 28, targetSdk 34, compileSdk 37, Kotlin, Gradle 9.6.0.
- Pleos 라이브러리 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`는 `app_code/settings.gradle.kts`에 들어 있습니다.

## 빌드·테스트
PowerShell에서:
```powershell
cd app_code
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --no-configuration-cache
```
- `app_code/local.properties`에 Android SDK 경로(`sdk.dir=...`)가 필요합니다.
- release 빌드는 lint 도구를 내려받으므로 처음에는 `--offline` 없이 실행하세요.
- 결과: `app_code/app/build/outputs/apk/release/app-release.apk` → `apk/`에 복사해 제출.

## APK 위치
`apk/동무들_길동무_release.apk` (프로토타입 빌드: 디버그 키로 서명되어 에뮬레이터에 그대로 설치됩니다.)

## 이미지·글꼴·오픈소스
- **캐릭터와 앱 화면 그림:** 팀의 디자인 담당이 직접 제작했습니다.
- **지역 지도(섬) 그림:** AI 이미지 생성 도구를 이용해 제작했습니다.
- **글꼴:** Jua (SIL Open Font License 1.1). 라이선스 전문은 `app_code/app/src/main/assets/licenses/Jua-OFL.txt`에 있습니다.
- **라이브러리:** AndroidX(appcompat, core-ktx)와 Material Components는 Apache License 2.0입니다. Pleos SDK(Vehicle, NaviHelper)는 Pleos Playground가 제공하는 것을 사용합니다.

## 설계 선택과 이유
코드를 보는 분이 "왜 이렇게 했지?" 하고 물을 만한 선택을 미리 적어 둡니다.
- **화면을 XML·Compose가 아니라 코드와 Canvas로 직접 그림:** 펫과 방(`PetPainter`, `RoomPainter`)은 해상도에 따라 크기가 변하는 그림이라 직접 그렸습니다. 대상 화면은 가로로 고정된 차량 디스플레이(2560×1440)입니다.
- **저장은 SharedPreferences와 구분자 문자열:** 날짜별 기록과 도감처럼 양이 적고 조회 조건이 단순해서 DB를 쓰지 않았습니다. 저장된 항목 하나가 깨져도 나머지를 잃지 않게 읽는 쪽에서 해당 항목만 건너뜁니다(`DriveLog.decode`).
- **`PetHub` 하나에 상태를 둠:** 앱 화면(Activity)과 백그라운드 서비스(`PetService`)가 같은 펫 상태를 함께 써야 해서 하나의 객체로 공유합니다.
- **1초 간격 반복(Handler)과 시간 주입:** 펫의 성장과 상태는 1초마다 갱신하면 충분해서 코루틴 없이 Handler로 돌립니다. 엔진은 시계를 밖에서 받아서(`PetEngine`) 단위 테스트에서 시간을 마음대로 움직일 수 있습니다.
- **지역 판정은 시·도 경계 다각형의 점 포함 검사(even-odd):** 17개 시·도를 직접 가진 경계 데이터로 판정하며 외부 지도 서비스를 쓰지 않습니다. 이 데이터는 2013년 기준이라 이후 행정구역 변경(예: 2023년 군위군의 대구 편입)은 반영되지 않았습니다(`KoreaMap` 머리말).
- **점수 기준은 자체 실험 값:** 급가속 3.0 m/s², 급감속 3.5 m/s²(GPS 속도 불확실성을 감안한 보수적 하한)이고, 측정이 10km·10분·수신율 80%에 못 미치면 점수를 내지 않습니다. 블루링크 공식 기준과 무관합니다.
- **점수용 속도는 Android 위치(GPS)의 속도:** 개발 중 확인한 Pleos SDK 범위에서 쓸 수 있는 속도 값을 찾지 못했기 때문입니다. 속도 정확도 정보가 없으면 그 표본은 쓰지 않습니다.
- **음성 안내는 Android 기본 TTS:** 에뮬레이터에서 확인 가능한 범위를 우선했습니다.
- **로그:** `Log.d`는 디버그 빌드에서만 출력하고, release 빌드는 출력하지 않습니다.

## 한계와 주의
- 점수는 길동무 자체 실험 기준이며 **현대차 블루링크 공식 점수와 다릅니다.** 현대차 계정·공식 점수 API 연동은 구현하지 않았습니다.
- 실차 센서 정확도, 점수 타당성, 주행 중 화면·알림의 시선 분산 여부는 검증 전입니다.
- 내비 위 작은 펫은 `SYSTEM_ALERT_WINDOW` 권한에 의존하며 실제 Pleos App Market 배포 허용 여부는 확인하지 못했습니다.
- 위치 권한이 없거나 GPS 속도 정확도가 없으면 추정 점수가 나오지 않습니다. 시연용 가상 주행은 실제 점수에 반영되지 않습니다.
