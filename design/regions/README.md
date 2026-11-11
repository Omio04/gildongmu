# 지역 섬 그림 넣는 법

1. 지역 컬렉션 그림 원본(경기 그림처럼 큰 화면 한 장, 약 1479×1063, "?" 구슬 5개 포함)을
   `design/regions/src/<지역ID>.png` 로 저장합니다.
   지역ID: seoul busan daegu incheon gwangju daejeon ulsan sejong gyeonggi gangwon chungbuk chungnam jeonbuk jeonnam gyeongbuk gyeongnam jeju
2. 실행합니다 (opencv-python-headless, numpy 필요):

       python design/regions/tools/make_islands.py            # 전부
       python design/regions/tools/make_islands.py jeju       # 한 지역만

3. `app_code/app/src/main/assets/regions/<지역ID>/` 에 `island.png`(구슬을 지운 섬)와 `spots.json`(구슬 자리)이 생깁니다.
   앱을 다시 빌드하면 그 지역이 그림으로 나오고, 구슬은 앱이 같은 자리에 그립니다(찾은 아이템은 스티커로 바뀜).

- 구슬을 5개 못 찾으면 경고가 나옵니다. 그림을 확인하세요.
- 그림에 지역 이름표처럼 지워야 할 글자가 그려져 있으면 `tools/make_islands.py`의 `EXTRA`에 그 사각형을 적습니다(경기 '수원화성' 예시).
- 서울은 첫 아이템(떡볶이 가게)이 그림에 들어 있어 손으로 만든 `spots.json`(`first` 항목 포함)을 씁니다. 도구는 서울을 건너뜁니다.
- 작은 그림(모음 이미지에서 잘라낸 것)은 확대하면 깨져서 쓰지 않습니다. `src/_unused/`에 보관만 합니다.
