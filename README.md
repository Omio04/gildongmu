# 운전펫 (DrivePet)

차량 신호에 반응하는 펫과 운전 습관 기록을 연결하는 Pleos 차량용 앱입니다. 향후 현대자동차 블루링크 공식 운전점수와 연계하는 방향으로 개발합니다.

## 현재 프로젝트
- `app_code/`: 현재 안드로이드 프로젝트. Android Studio에서는 이 폴더를 여세요.
- `apk/drivepet-debug.apk`: 현재 소스로 빌드한 최신 개발용 설치 파일.
- `screenshots/`: 현재 캐릭터 적용 이후 화면. 기존 캡처는 각 촬영 시점의 기능을 보여주며 최신 점수 화면은 별도로 확인합니다.
- `design/character/`, `design/regions/`: 현재 캐릭터와 낮/밤 지역 그림 원본 및 생성 도구.
- `DRIVING_RECORD.md`: 기존 깜빡이 기록·배지와 새 추정 점수의 구분.
- `docs/DRIVING_SCORE.md`: 점수 산식, 데이터 품질 기준, 현대차 연계 계획.

## 구현된 기능
- 차량 기어·깜빡이·핸들·브레이크·주행거리·배터리/연료·벨트·와이퍼에 반응하는 펫.
- 거리별 성장, 옷·가구, 미션, 미니게임, 17개 지역 지도 도감.
- 주차(P) 상태에서 앱 화면을 열고, 다른 기어에서는 앱 화면을 닫는 처리.
- 날짜별 깜빡이 습관 기록과 배지. 기존 기록·보상은 유지합니다.
- **운전펫 추정 점수 v1**: GPS 속도로 급가속·급감속·급출발과 심야주행을 집계하고 최근 90일 점수를 표시합니다. 측정 10km·10분·수신율 80%를 충족하지 못하면 점수를 표시하지 않습니다.
- 실제 점수와 분리된 개발용 시연 화면. 시연 기록은 저장하거나 보상에 반영하지 않습니다.

## 점수의 출처
블루링크의 공개 평가 항목을 참고했지만 계산식·임계값·가중치는 운전펫 자체 실험 기준입니다. **블루링크 공식 점수와 같지 않으며 실차 검증 전입니다.** 깜빡이 점수, 게임 포인트와도 별개입니다.

현대차 공식 운전습관 API는 안내돼 있으나 이 앱의 사용 권한·실제 점수 조회 규격은 아직 확보하지 않았습니다. 공식 점수 연결 인터페이스를 분리했고 현재 제공자는 항상 미연동을 반환합니다. 네트워크 연동이나 현대차 계정 로그인은 구현하지 않았습니다.

## 빌드·테스트
PowerShell에서:
```powershell
cd app_code
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```
`local.properties`에 Android SDK 경로가 필요합니다. 처음 의존성을 받는 환경에서는 `--offline`을 빼세요.

빌드 결과: `app_code/app/build/outputs/apk/debug/app-debug.apk`. 개발용 배포 복사본: `apk/drivepet-debug.apk`.

## 확인 범위와 한계
- 최신 소스 빌드와 JVM 테스트를 확인합니다. 실차 센서 정확도·점수 타당성·안전운전 개선 효과는 검증 전입니다.
- GPS 속도 정확도가 제공되지 않거나 위치 권한이 없으면 추정 점수가 나오지 않습니다. 가상 위치·데모 주행은 실제 점수에 넣지 않습니다.
- 내비 위 펫은 `SYSTEM_ALERT_WINDOW`에 의존합니다. 실제 Pleos App Market 배포 허용 여부는 미확인입니다.
- 주행 중 애니메이션·알림의 시선 분산 여부, 실차별 신호 지원 범위도 검증이 필요합니다.
- 현재 APK는 디버그 빌드이며 제출·배포용 서명 검증은 별도입니다.

## 공식 참고 자료
- [공모전 심사 기준](https://appdevchallenge.auto-hmg.io/guide/judging-awards#criteria): 차량특화성·창의성·기술 활용성·사업성. 세부 배점은 비공개.
- [현대차 데이터 API 소개](https://developers.hyundai.com/web/v1/hyundai/data_api): 최근 90일 운전습관 점수 제공 안내.
- [현대차 연동 절차](https://developers.hyundai.com/web/v1/hyundai/faqs): 개발 프로젝트·테스트·상용화 심사.
