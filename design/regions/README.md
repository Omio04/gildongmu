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

## 낮 그림 (밝은 모드)

- 낮 그림 원본(같은 형식의 큰 화면 한 장, 1448×1086)은 `design/regions/src_day/<지역ID>.webp` 에 둡니다. 17개 지역 모두 있습니다.
- 실행: `python design/regions/tools/make_islands.py --day` (한 지역만: `... --day jeju`)
- 결과는 같은 폴더에 `island_day.png`, `spots_day.json` 으로 생기고, 밤 그림(`island.png`, `spots.json`)은 그대로 둡니다. 서울은 낮 그림에 떡볶이 가게가 없어서 말풍선 5개 모두 `spots_day.json`에 들어갑니다.
- 앱은 밝은 모드면 낮 그림을, 어두운 모드면 밤 그림을 씁니다(낮 그림이 없는 지역은 어느 모드든 밤 그림).
- 낮 그림은 밝아서 말풍선을 지운 자리가 잘 보입니다. 그래서 낮 전용 채우기(주변 평균색 + 채도 보정), 더 작은 지우기 범위, 번짐 폭 축소를 쓰고, 앱은 찾은 아이템 뒤에 은은한 빛 원을 깔아 남은 자국을 가립니다. (근처 풍경을 복사해 붙이는 방법은 탑·캐릭터가 복사돼 쓰지 않았습니다. 가장 깔끔한 방법은 말풍선이 없는 낮 그림을 받는 것입니다.)
- 말풍선은 "?" 모양을 찾은 뒤 견본(충북 낮 그림의 말풍선)과 가장 닮은 5개만 고릅니다(진짜는 0.29 이상, 가짜는 0.11 이하).
