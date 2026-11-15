#!/usr/bin/env python3
"""
Screen tour: opens every screen of 운전펫 on the emulator, saves a screenshot of each, and checks the layout from the
accessibility tree (what a driver would see go wrong):
  * text that sticks out of the screen
  * two texts drawn on top of each other
  * text too small to read in a car (height under MIN_TEXT_PX)
Look at the saved screenshots too: some problems (a bubble over a button, a clipped picture) only show in the image.

    python3 screen_tour.py [output_dir]      # default: ./screens
Needs the emulator with the debug build and the app in front (gear P). Uses the debug demo commands to fill empty screens.
"""
import os
import re
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_blackbox as bb                      # noqa: E402  (shared adb helpers)

OUT = sys.argv[1] if len(sys.argv) > 1 else "screens"
W, H = 2560, 1440
MIN_TEXT_PX = 16                               # a text box shorter than this is too small to read at a glance in a car
                                               # (the car's own small labels are about 17 px high on this screen)
os.makedirs(OUT, exist_ok=True)
issues = []                                    # (screen, kind, detail)


def dump_nodes():
    bb.shell("uiautomator dump --windows /sdcard/tour_ui.xml")
    root = ET.fromstring(bb.adb("shell", "cat", "/sdcard/tour_ui.xml"))
    nodes = []
    for n in root.iter("node"):
        if n.get("package") != bb.PKG:
            continue
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds") or "")
        t = (n.get("text") or "").strip()
        if m and t:
            nodes.append((t, *map(int, m.groups())))
    return nodes


def inter(a, b):
    x = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    y = max(0, min(a[4], b[4]) - max(a[2], b[2]))
    return x * y


HOME_LAYER = ("01_", "11_", "12_", "13_", "14_", "15_")     # screens that are the room itself
home_texts = set()                             # the room's own texts: they stay in the accessibility tree behind full-screen pages


def check(screen):
    nodes = dump_nodes()
    if screen.startswith("01_"):
        home_texts.update(n[0] for n in nodes)
    behind_page = not screen.startswith(HOME_LAYER)
    for t, x1, y1, x2, y2 in nodes:
        if behind_page and t in home_texts:
            continue                           # covered by the page in front: not a real problem
        if x1 < 0 or y1 < 0 or x2 > W or y2 > H:
            issues.append((screen, "화면 밖으로 나감", f"'{t[:30]}' [{x1},{y1}][{x2},{y2}]"))
        h = y2 - y1
        if 0 < h < MIN_TEXT_PX and len(t) > 1:
            issues.append((screen, "글자가 너무 작음", f"'{t[:30]}' 높이 {h}px"))
    for i in range(len(nodes)):
        for j in range(i + 1, len(nodes)):
            a, b = nodes[i], nodes[j]
            if a[0] == b[0]:
                continue
            if behind_page and (a[0] in home_texts or b[0] in home_texts):
                continue
            area = min((a[3] - a[1]) * (a[4] - a[2]), (b[3] - b[1]) * (b[4] - b[2]))
            if area > 0 and inter(a, b) > 0.25 * area:
                issues.append((screen, "글자가 서로 겹침", f"'{a[0][:20]}' ↔ '{b[0][:20]}'"))
    return nodes


def shot(name):
    path = os.path.join(OUT, name + ".png")
    with open(path, "wb") as f:
        f.write(bb.subprocess.run([bb.ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)
    n = check(name)
    print(f"  {name}: 글자 {len(n)}개, 최소 높이 {min([b-a for _, _, a, _, b in n] or [0])}px")


def back(times=1):
    for _ in range(times):
        bb.adb("shell", "input", "keyevent", "4")
        time.sleep(0.7)


def front():
    bb.adb("shell", "am", "start", "--user", bb.USER, "-n", f"{bb.PKG}/.MainActivity")
    time.sleep(1.5)


def open_menu_page(title):
    front()
    if not bb.tap_text("메뉴"):
        issues.append((title, "버튼을 못 찾음", "'메뉴'")); return False
    time.sleep(1.2)
    if not bb.tap_text(title):
        issues.append((title, "버튼을 못 찾음", f"'{title}' 줄")); return False
    time.sleep(1.5)
    return True


def main():
    bb.gear("Park")
    bb.soc(80)
    front()
    # fill the empty screens so there is something to look at
    bb.demo("points"); bb.demo("log_demo", "45"); bb.demo("mission_part"); time.sleep(2)
    print("1) 홈")
    shot("01_home")
    for i, title in enumerate(["캐릭터 정보", "미션", "상점", "인벤토리", "설정"], start=2):
        print(f"{i}) 메뉴 - {title}")
        if open_menu_page(title):
            shot(f"{i:02d}_menu_{title}")
        back(2)
    for i, title in [(7, "미니게임"), (8, "지도 도감"), (9, "운전 기록")]:
        print(f"{i}) {title}")
        front()
        if bb.tap_text(title):
            time.sleep(2.0)
            shot(f"{i:02d}_{title}")
        else:
            issues.append((title, "버튼을 못 찾음", title))
        back(1)
    print("10) 배지 모아보기")
    front()
    if bb.tap_text("나의 달성 배지"):
        time.sleep(1.5); shot("10_badges")
    back(1)
    print("11) 밤 모드")
    front()
    nodes = bb.ui_nodes()
    moon = [n for n in nodes if n[0].startswith("&#127769;") or "\U0001F319" in n[0]]
    if moon:
        bb.adb("shell", "input", "tap", str(moon[0][1]), str(moon[0][2])); time.sleep(1.5); shot("11_night")
        bb.adb("shell", "input", "tap", str(moon[0][1]), str(moon[0][2])); time.sleep(1.0)
    print("12) 성장 단계별 모습")
    for km, name in [(1000, "infant"), (3000, "rebel"), (5000, "adult")]:
        bb.demo("km", str(km)); time.sleep(2); front(); shot(f"12_stage_{name}")
    print("13) 펫 상태별 모습")
    for mood, name in [(50, "angry"), (20, "hurt")]:
        bb.demo("mood", str(mood)); time.sleep(2); shot(f"13_mood_{name}")
    bb.demo("mood", "90"); bb.demo("battery", "10"); time.sleep(2); shot("13_hungry"); bb.demo("battery", "80")
    print("14) 주행 중 (D): 내비 위 작은 펫과 알림")
    bb.gear("Drive"); time.sleep(3)
    bb.controls(speed=40, accel=10); time.sleep(2); shot("14_drive")
    bb.demo("belt_on"); time.sleep(1)
    bb.demo("belt_off"); time.sleep(2.5); shot("14_belt_off"); bb.demo("belt_on")
    bb.demo("wiper_on"); time.sleep(2.5); shot("14_wiper_on"); bb.demo("wiper_off")
    bb.demo("battery", "10"); bb.demo("range", "40"); time.sleep(2.5); shot("14_hungry_alert"); bb.demo("battery", "80")
    bb.controls(); bb.gear("Park"); time.sleep(3)
    print("15) 주행 결과 카드")
    shot("15_after_drive")

    print(f"\n자동 검사 결과: 문제 {len(issues)}건")
    for s, k, d in issues:
        print(f"  [{k}] {s}: {d}")
    sys.exit(1 if issues else 0)


if __name__ == "__main__":
    main()
