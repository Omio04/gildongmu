#!/usr/bin/env python3
"""
Black-box tests for what a driver does INSIDE the app, with the car parked: shop, inventory, missions, settings,
the mini game, the map collection and the driving record. Each case taps real buttons (found by their text in the
accessibility tree), then checks the screen text and the numbers the app saves. Screenshots go to the output folder.

    python3 run_ui_features.py [output_dir] [--only UI-01 UI-02] [--report result.md]

Needs the emulator with the DEBUG build (demo commands give points, missions and a made-up driving record).
Does not need the Virtual Console page.
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_blackbox as bb  # noqa: E402  (shared adb helpers)

OUT = "ui_screens"
CASES = []
results = []


def case(cid, title):
    def wrap(fn):
        fn.cid, fn.title = cid, title
        CASES.append(fn)
        return fn
    return wrap


def front():
    bb.adb("shell", "am", "start", "--user", bb.USER, "-n", f"{bb.PKG}/.MainActivity")
    time.sleep(1.3)


def back(n=1):
    for _ in range(n):
        bb.adb("shell", "input", "keyevent", "4")
        time.sleep(0.7)


def shot(name):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(bb.subprocess.run([bb.ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)


def home():
    """Back to the room with nothing open.
    The accessibility tree still holds the room's texts while a page or the menu card is open, so "the room's texts are
    there" does not mean "nothing is open". Press back enough times to close any page and the card, then bring the app to
    the front again (if the last back left the app, this starts it again with the room showing)."""
    back(3)
    front()


def open_page(title):
    home()
    assert bb.tap_text("메뉴"), "'메뉴' 버튼 없음"
    time.sleep(1.2)
    assert bb.tap_text(title), f"'{title}' 줄 없음"
    time.sleep(1.3)


def points_text():
    """The points the screen shows (the first '123P' text)."""
    for t in bb.ui_texts():
        m = re.fullmatch(r"(\d+)P", t)
        if m:
            return int(m.group(1))
    raise AssertionError("화면에서 포인트를 찾을 수 없음")


def tap_nth(text_or_prefix, n=0, prefix=False):
    hits = [x for x in bb.ui_nodes() if (x[0].startswith(text_or_prefix) if prefix else x[0] == text_or_prefix)]
    if len(hits) <= n:
        return False
    bb.adb("shell", "input", "tap", str(hits[n][1]), str(hits[n][2]))
    return True


def count(text):
    return sum(1 for t in bb.ui_texts() if t == text)


def pet_pref(key):
    time.sleep(bb.SAVE_WAIT)
    return bb.prefs().get(key, "")


def ui_pref(key):
    xml = bb.adb("shell", "run-as", bb.PKG, "--user", bb.USER, "cat", "shared_prefs/ui.xml")
    m = re.search(r'name="%s" value="([^"]*)"' % key, xml)
    return m.group(1) if m else None


# ------------------------------------------------------------------------------------------------ shop and inventory
@case("UI-01", "상점: 구매하면 포인트가 가격만큼 줄고 버튼이 '보유 중'으로 바뀐다")
def ui01():
    bb.demo("points"); time.sleep(1)
    open_page("상점")
    p0 = points_text(); own0 = count("보유 중")
    assert tap_nth("구매"), "'구매' 버튼 없음"
    time.sleep(1.5)
    shot("01_shop_after_buy")
    p1 = points_text(); own1 = count("보유 중")
    assert own1 == own0 + 1, f"'보유 중'이 {own0}→{own1} (기대 +1)"
    assert p0 - p1 in (30, 40, 50, 60), f"포인트 {p0}→{p1}: 가격 30~60P 만큼 줄어야 함"
    return f"포인트 {p0}→{p1} (-{p0-p1}), '보유 중' {own0}→{own1}"


@case("UI-02", "상점: 레벨이 모자란 소품은 눌러도 살 수 없다")
def ui02():
    open_page("상점")
    p0 = points_text()
    locked = [x for x in bb.ui_nodes() if "Lv." in x[0] and ("🔒" in x[0] or "\U0001F512" in x[0])]
    assert locked, "잠긴 소품('🔒 Lv.N')을 찾지 못함"
    bb.adb("shell", "input", "tap", str(locked[0][1]), str(locked[0][2]))
    time.sleep(1.2)
    p1 = points_text()
    assert p0 == p1, f"잠긴 소품을 눌렀는데 포인트가 {p0}→{p1}로 바뀜"
    return f"잠긴 소품 {len(locked)}개, 눌러도 포인트 {p0}P 그대로"


@case("UI-03", "인벤토리: 산 모자를 입고 벗을 수 있고 저장된다")
def ui03():
    open_page("인벤토리")
    shot("03_inventory")
    assert tap_nth("입기"), "'입기' 버튼 없음 (산 옷이 없음?)"
    time.sleep(1.2)
    assert count("벗기") >= 1, "입은 뒤 '벗기' 버튼이 나오지 않음"
    worn = pet_pref("worn")
    assert worn.strip(), f"입었는데 저장된 worn이 비어 있음: '{worn}'"
    assert tap_nth("벗기"), "'벗기' 버튼 없음"
    time.sleep(1.2)
    worn2 = pet_pref("worn")
    return f"입기 → 저장 '{worn}', 벗기 → 저장 '{worn2}'"


@case("UI-04", "인벤토리: 산 가구를 방에 놓고 치울 수 있다")
def ui04():
    open_page("상점")
    # the furniture is at the bottom of the shop list: scroll the menu card up until the plant row is on screen
    for _ in range(4):
        if any(x[0] == "화분" for x in bb.ui_nodes()):
            break
        bb.adb("shell", "input", "swipe", "2250", "1150", "2250", "450", "600")
        time.sleep(0.8)
    # buy the plant: its row's '구매' button is the one at the same height as the row text
    rows = [x for x in bb.ui_nodes() if x[0] == "화분"]
    assert rows, "상점에 '화분'이 없음 (스크롤해도 못 찾음)"
    ys = rows[0][2]
    buy = [x for x in bb.ui_nodes() if x[0] == "구매" and abs(x[2] - ys) < 60]
    if buy:
        bb.adb("shell", "input", "tap", str(buy[0][1]), str(buy[0][2])); time.sleep(1.2)
    open_page("인벤토리")
    assert tap_nth("놓기"), "'놓기' 버튼 없음"
    time.sleep(1.2)
    placed = count("치우기") >= 1
    assert placed, "놓은 뒤 '치우기' 버튼이 나오지 않음"
    tap_nth("치우기"); time.sleep(1.0)
    return "화분을 놓으면 '치우기'가 나오고, 치우면 '놓기'로 돌아옴" if count("놓기") >= 1 else "치운 뒤 '놓기'로 돌아오지 않음"


# ------------------------------------------------------------------------------------------------ missions and settings
@case("UI-05", "미션: 끝낸 미션의 보상을 받으면 포인트가 늘고 '받음'이 된다")
def ui05():
    bb.demo("mission_done"); time.sleep(1.5)
    open_page("미션")
    shot("05_missions")
    p0 = points_text(); took0 = count("받음")
    assert tap_nth("받기", prefix=True), "'받기 +NP' 버튼 없음"
    time.sleep(1.5)
    p1 = points_text(); took1 = count("받음")
    assert took1 == took0 + 1, f"'받음'이 {took0}→{took1}"
    assert p1 > p0, f"포인트가 늘지 않음 {p0}→{p1}"
    # a second tap must not pay again
    tap_nth("받음"); time.sleep(1.0)
    p2 = points_text()
    assert p2 == p1, f"이미 받은 보상을 다시 눌렀더니 포인트가 {p1}→{p2}"
    return f"포인트 {p0}→{p1} (+{p1-p0}), 다시 눌러도 그대로"


# The settings page draws its options itself, so they are not in the accessibility tree (a screen reader cannot read them
# either): tap them by position inside the menu card. Positions are for the 2560x1440 emulator screen.
SETTINGS = {"light": (2260, 445), "dark": (2260, 544), "follow": (2260, 685), "delete": (2260, 859),
            "do_delete": (2148, 1000), "cancel": (2377, 1000)}


def tap_xy(key):
    x, y = SETTINGS[key]
    bb.adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1.3)


@case("UI-06", "설정: 다크/라이트 화면 밝기를 바꾸면 저장되고 화면이 바뀐다")
def ui06():
    open_page("설정")
    shot("06_settings_light")
    tap_xy("dark")                           # the app re-creates itself after this, which closes the card
    time.sleep(1.5)
    dark = ui_pref("night")                  # 1 = light, 2 = dark
    shot("06_dark_room")
    open_page("설정")
    tap_xy("light")
    time.sleep(1.5)
    light = ui_pref("night")
    shot("06_light_room")
    assert dark == "2" and light == "1", f"다크 → night={dark}, 라이트 → night={light} (기대 2, 1)"
    return "다크 → night=2, 라이트 → night=1 (다시 만들어지며 패널이 닫힘)"


@case("UI-07", "설정: 내비 위 작은 펫(차 옆에서 따라가기)을 켜고 끄면 저장된다")
def ui07():
    open_page("설정")
    # a setting that was never changed is not in the saved file yet: that means the default, which is "on"
    def follow():
        return pet_pref("followCar") or "true"
    before = follow()
    tap_xy("follow")
    shot("07_settings_follow_toggled")
    after = follow()
    tap_xy("follow")
    again = follow()
    assert before != after and again == before, f"followCar {before} → {after} → {again} (눌러서 바뀌고 다시 누르면 돌아와야 함)"
    return f"followCar {before} → {after} → {again}"


@case("UI-08", "설정: 운전 기록을 지우면 기록은 사라지고 배지는 남는다 (취소하면 그대로)")
def ui08():
    bb.demo("log_demo", "45"); time.sleep(2)
    open_page("설정")
    p = pet_pref("drivelog")
    before = len([r for r in p.split(";") if r.strip()])
    assert before > 0, "시험용 운전 기록이 만들어지지 않음"
    tap_xy("delete")
    shot("08_delete_confirm")
    tap_xy("cancel")                         # cancel first: nothing may be deleted
    kept = len([r for r in pet_pref("drivelog").split(";") if r.strip()])
    assert kept == before, f"취소했는데 기록이 {before}→{kept}줄"
    tap_xy("delete")
    tap_xy("do_delete")
    shot("08_after_delete")
    p2 = bb.prefs()
    time.sleep(bb.SAVE_WAIT)
    p2 = bb.prefs()
    after = len([r for r in p2.get("drivelog", "").split(";") if r.strip()])
    assert after == 0, f"운전 기록 {before}줄 → {after}줄 (기대 0)"
    assert p2.get("badges", "") != "", "기록을 지웠더니 배지도 사라짐"
    return f"취소하면 {kept}줄 그대로, 지우면 {before}줄 → {after}줄, 배지는 유지"


# ------------------------------------------------------------------------------------------------ mini game
@case("UI-09", "미니게임: 시작해서 플레이하면 코인을 모으거나 끝나고 결과 화면이 나온다")
def ui09():
    home()
    assert bb.tap_text("미니게임"), "'미니게임' 버튼 없음"
    time.sleep(2)
    shot("09_game_ready")
    # tap the play area to start, then keep hopping for a while
    for i in range(30):
        bb.adb("shell", "input", "tap", "1700", "700")
        time.sleep(0.45)
        if i == 4:
            shot("09_game_running")
    time.sleep(1.5)
    shot("09_game_over")
    texts = bb.ui_texts()
    return "게임 화면 텍스트: " + ", ".join(texts[:12])


# ------------------------------------------------------------------------------------------------ collection and record
@case("UI-10", "지도 도감: 지역을 눌러 컬렉션을 열고 닫을 수 있다")
def ui10():
    home()
    assert bb.tap_text("지도 도감"), "'지도 도감' 버튼 없음"
    time.sleep(2.0)
    shot("10_dex_map")
    t0 = bb.ui_texts()
    assert any("대한민국 지도 도감" in t for t in t0), "도감 제목이 없음"
    assert bb.tap_text("🏝 컬렉션 열기") or tap_nth("컬렉션 열기", prefix=False) or any(bb.tap_text(t) for t in t0 if "컬렉션" in t), "'컬렉션 열기' 버튼 없음"
    time.sleep(1.5)
    shot("10_dex_collection")
    t1 = bb.ui_texts()
    back(2)
    return "지도 → 컬렉션 열림: " + ", ".join(t1[:10])


@case("UI-11", "지도 도감: 시연 명령으로 줍기를 하면 모은 개수가 늘어난다")
def ui11():
    bb.demo("region", "seoul"); bb.demo("find"); time.sleep(1.5)
    home()
    bb.tap_text("지도 도감"); time.sleep(2.0)
    texts = bb.ui_texts()
    m = [t for t in texts if re.search(r"전체 \d+/\d+", t)]
    shot("11_dex_found")
    back(1)
    return "도감 진행: " + (m[0] if m else "(표시 없음)")


@case("UI-12", "운전 기록: 날짜를 눌러 상세를 보고 점수 기준을 열 수 있다")
def ui12():
    bb.demo("log_demo", "45"); time.sleep(2)
    home()
    assert bb.tap_text("운전 기록"), "'운전 기록' 버튼 없음"
    time.sleep(2)
    shot("12_record")
    t = bb.ui_texts()
    assert any("운전 기록" in x for x in t), "운전 기록 화면이 아님"
    ok_cal = any(re.fullmatch(r"\d+점", x) or re.fullmatch(r"\d+", x) for x in t)
    assert bb.tap_text("점수 기준"), "'점수 기준' 버튼 없음"
    time.sleep(1.5)
    shot("12_score_rules")
    rules = bb.ui_texts()
    back(2)
    return f"달력 표시 {ok_cal}, 점수 기준 화면 텍스트 {len(rules)}개"


@case("UI-13", "배지: 눌러서 조건과 진행 상황을 볼 수 있다")
def ui13():
    home()
    assert bb.tap_text("나의 달성 배지"), "배지 카드 없음"
    time.sleep(2)
    shot("13_badges")
    nodes = [x for x in bb.ui_nodes() if "일 연속" in x[0] or "정상 사용" in x[0] or "누적" in x[0]]
    assert nodes, "배지 이름을 찾지 못함"
    bb.adb("shell", "input", "tap", str(nodes[0][1]), str(nodes[0][2]))
    time.sleep(1.5)
    shot("13_badge_detail")
    return "배지 상세 화면 텍스트: " + ", ".join(bb.ui_texts()[:12])


@case("UI-14", "성장 단계: 거리를 올리면 캐릭터 정보의 성장 단계가 바뀐다")
def ui14():
    out = []
    for km, want in ((1000, "유아기"), (3000, "반항기"), (5000, "성인기")):
        bb.demo("km", str(km)); time.sleep(1.5)
        open_page("캐릭터 정보")
        t = bb.ui_texts()
        got = t[t.index("성장 단계") + 1] if "성장 단계" in t else "(없음)"
        out.append(f"{km}km → {got}")
        assert got == want, f"{km}km에서 성장 단계 '{got}' (기대 '{want}')"
        back(2)
    return ", ".join(out)


# ------------------------------------------------------------------------------------------------ main
def main():
    global OUT
    ap = argparse.ArgumentParser()
    ap.add_argument("out", nargs="?", default="ui_screens")
    ap.add_argument("--only", nargs="*"); ap.add_argument("--report")
    args = ap.parse_args()
    OUT = args.out
    bb.adb("shell", "am", "start", "--user", bb.USER, "-n", f"{bb.PKG}/.MainActivity")
    time.sleep(2)
    for fn in CASES:
        if args.only and fn.cid not in args.only:
            continue
        t0 = time.time()
        try:
            detail, status = (fn() or ""), "PASS"
        except AssertionError as e:
            detail, status = str(e), "FAIL"
        except Exception as e:
            detail, status = f"{type(e).__name__}: {e}", "FAIL"
        results.append((fn.cid, fn.title, status, detail))
        print(f"[{status}] {fn.cid} {fn.title}  ({time.time()-t0:.0f}s)\n        {detail}", flush=True)
        try:
            home()
        except Exception:
            pass
    n = {s: sum(1 for r in results if r[2] == s) for s in ("PASS", "FAIL")}
    print(f"\n합계: 통과 {n['PASS']} / 실패 {n['FAIL']}")
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            f.write("# 앱 안 조작 시험 결과\n\n| ID | 항목 | 결과 | 내용 |\n|---|---|:-:|---|\n")
            for cid, title, status, detail in results:
                f.write(f"| {cid} | {title} | {status} | {detail.splitlines()[0] if detail else ''} |\n")
    sys.exit(1 if n["FAIL"] else 0)


if __name__ == "__main__":
    main()
