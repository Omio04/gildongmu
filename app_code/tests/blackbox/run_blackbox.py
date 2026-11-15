#!/usr/bin/env python3
"""
Black-box tests for 운전펫 (DrivePet) on the Pleos Connect emulator.

"Black box" means the test knows nothing about the code. It only does what a driver or the car would do and looks at what
the app does in return:
  * input  : the emulator's Virtual Console (gear, wheel, brake, turn signal, odometer, EV battery), the navigation app
  * output : what is on the screen (uiautomator text), which activity is in front, the app's debug log, the numbers it saves

Needs: a running Pleos Connect v2.1.0 emulator with the DEBUG build installed, and the Virtual Console page
(http://127.0.0.1:8088/) OPEN in a browser: the console only accepts input while a browser page is connected.

    python3 run_blackbox.py                      # run everything
    python3 run_blackbox.py --apk app-debug.apk  # install the APK first
    python3 run_blackbox.py --fresh              # clear the app's data first (first-run flow is done by the script)
    python3 run_blackbox.py --only BB-08 BB-09   # a few cases
    python3 run_blackbox.py --report result.md   # also write a markdown report

Exit code: 0 all passed (skips allowed), 1 at least one failed, 2 the environment is not ready.
Uses only the Python standard library.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

PKG = "com.drivepet.app"
USER = "10"                                  # the Pleos Connect emulator runs apps as user 10
NAVI = "ai.umos.maps.android.navigation.app"
CONSOLE = "http://127.0.0.1:8088"
SEOUNGNAM = ("127.1386", "37.4467")          # lon lat, the emulator's default place in 경기도 성남
SAVE_WAIT = 12                               # the app saves its numbers about every 10 s

ADB = (os.environ.get("ADB") or shutil.which("adb")
       or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
results = []                                 # (id, title, status, detail)


# ---------------------------------------------------------------------------------------------- plumbing
def adb(*args, timeout=60, check=False):
    p = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {p.stderr.strip() or p.stdout.strip()}")
    return p.stdout


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def vc(path):
    """PUT to the Virtual Console. Raises when the console is not connected to a browser."""
    # The console only works while a browser page is connected. A page in a background tab can miss its heartbeat for a
    # few seconds, so wait a little before giving up instead of failing a whole case on one missed beat.
    for attempt in range(8):
        req = urllib.request.Request(CONSOLE + path, method="PUT")
        try:
            body = urllib.request.urlopen(req, timeout=8).read().decode()
        except urllib.error.HTTPError as e:
            body = e.read().decode()
        if "emulator_inactive" not in body:
            return body
        time.sleep(2.5)
    raise RuntimeError("Virtual Console is not active: open http://127.0.0.1:8088/ in a browser and keep it open")


def console_state():
    return json.loads(urllib.request.urlopen(CONSOLE + "/vehicle/state", timeout=8).read().decode())


def gear(g): vc(f"/vehicle/gear?gear={g}")
def controls(speed=0, accel=0, brake=0, steering=0): vc(f"/vehicle/controls?speed={speed}&accelerator={accel}&brake={brake}&steering={steering}")
def signal(mode): vc(f"/vehicle/turn-signal?mode={mode}")
def odometer(km): vc(f"/vehicle/odometer?traveled_distance_km={km}")
def soc(pct): vc(f"/vehicle/ev-battery?field=soc&value={pct}")


def demo(cmd, ids=None):
    a = ["shell", "am", "broadcast", "--user", USER, "-a", "com.drivepet.DEMO", "-p", PKG, "--es", "cmd", cmd]
    if ids is not None:
        a += ["--es", "ids", str(ids)]
    adb(*a)


def prefs():
    """The numbers the app saved (debug build: readable with run-as)."""
    xml = adb("shell", "run-as", PKG, "--user", USER, "cat", "shared_prefs/pet.xml")
    out = {}
    for el in ET.fromstring(xml):
        name = el.get("name")
        out[name] = el.get("value") if el.get("value") is not None else (el.text or "")
    return out


def num(p, key):
    return float(p.get(key, 0))


def drivelog(p):
    rows = [r for r in p.get("drivelog", "").split(";") if r.strip()]
    return [r.split(",") for r in rows]


def settle(sec=SAVE_WAIT):
    time.sleep(sec)


def ui_nodes():
    shell("uiautomator dump --windows /sdcard/bb_ui.xml")
    xml = adb("shell", "cat", "/sdcard/bb_ui.xml")
    nodes = []
    for n in ET.fromstring(xml).iter("node"):
        t = (n.get("text") or "").strip()
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if t and m:
            x1, y1, x2, y2 = map(int, m.groups())
            nodes.append((t, (x1 + x2) // 2, (y1 + y2) // 2, n.get("class") or ""))
    return nodes


def ui_texts():
    return [t for t, *_ in ui_nodes()]


def tap_text(text, exact=True):
    for t, x, y, _ in ui_nodes():
        if (t == text) if exact else (text in t):
            adb("shell", "input", "tap", str(x), str(y))
            return True
    return False


def drivepet_in_front():
    out = shell("dumpsys activity activities")
    return any(PKG + "/.MainActivity" in l for l in out.splitlines() if "topResumedActivity" in l)


def logcat_clear(): adb("logcat", "-c")
def log_drivepet(): return adb("logcat", "-d", "-s", "DrivePet")


def wait_log(needle, seconds):
    """Wait until the app's debug log contains [needle]."""
    end = time.time() + seconds
    while time.time() < end:
        if needle in log_drivepet():
            return True
        time.sleep(1)
    return False


def restart_app():
    adb("shell", "am", "force-stop", "--user", USER, PKG)
    time.sleep(1)
    adb("shell", "am", "start", "--user", USER, "-n", f"{PKG}/.MainActivity")


def onboard_if_needed():
    """First-run flow: Next, Next, Next, type a name, finish."""
    for _ in range(12):
        time.sleep(1.5)
        nodes = ui_nodes()
        texts = [t for t, *_ in nodes]
        edit = [n for n in nodes if "EditText" in n[3]]
        if "시작하기" in texts:
            tap_text("시작하기")
        elif "이 이름으로 정할래" in texts:
            tap_text("이 이름으로 정할래")
        elif edit and "이름을 입력해 주세요" in texts or (edit and not any(t.startswith("mong") for t in texts)):
            adb("shell", "input", "tap", str(edit[0][1]), str(edit[0][2]))
            time.sleep(0.5)
            adb("shell", "input", "text", "mong")
            adb("shell", "input", "keyevent", "66")
        elif "다음" in texts:
            tap_text("다음")
        else:
            return


# ---------------------------------------------------------------------------------------------- case runner
def case(cid, title):
    def wrap(fn):
        fn.cid, fn.title = cid, title
        CASES.append(fn)
        return fn
    return wrap


CASES = []


class Skip(Exception):
    pass


def expect(cond, detail_ok, detail_bad):
    if not cond:
        raise AssertionError(detail_bad)
    return detail_ok


# ---------------------------------------------------------------------------------------------- the cases
@case("BB-01", "앱을 켜면 Vehicle SDK가 시작된다 (로그)")
def bb01():
    logcat_clear()
    restart_app()
    time.sleep(12)
    log = log_drivepet()
    return expect("vehicle sdk started" in log, "로그에 'vehicle sdk started'", "'vehicle sdk started' 로그가 없음:\n" + log[-400:])


@case("BB-02", "앱을 켜면 NaviHelper가 연결된다 (로그)")
def bb02():
    log = log_drivepet()
    return expect("navi connected" in log, "로그에 'navi connected'", "'navi connected' 로그가 없음:\n" + log[-400:])


@case("BB-03", "NaviHelper 현재 위치로 지역이 정해진다 (경기도, 로그)")
def bb03():
    log = log_drivepet()
    return expect("navi position: region gyeonggi" in log, "로그에 'navi position: region gyeonggi'",
                  "지역 로그가 없음 (내비 앱이 켜져 있고 에뮬레이터 위치가 성남인지 확인):\n" + log[-400:])


@case("BB-04", "차량·내비 권한이 모두 허용되어 설치된다")
def bb04():
    out = shell(f"dumpsys package {PKG}")
    need = ["CAR_DRIVE", "CAR_LIGHTS", "CAR_ENERGY", "CAR_SEATS", "CAR_WINDOWS", "NAVI_ROUTE"]
    bad = [n for n in need if not re.search(r"pleos\.car\.permission\.%s: granted=true" % n, out)]
    return expect(not bad, "6개 권한 모두 granted=true", "허용되지 않은 권한: " + ", ".join(bad))


@case("BB-05", "주차(P)일 때 펫 화면이 맨 앞에 있다")
def bb05():
    gear("Park")
    time.sleep(3)
    adb("shell", "am", "start", "--user", USER, "-n", f"{PKG}/.MainActivity")
    time.sleep(2)
    return expect(drivepet_in_front(), "펫 화면이 맨 앞", "P인데 펫 화면이 맨 앞이 아님")


@case("BB-06", "주행(D)으로 바꾸면 펫 화면이 닫히고 서비스는 계속 산다")
def bb06():
    gear("Drive")
    time.sleep(3)
    closed = not drivepet_in_front()
    alive = PKG in shell("dumpsys activity services " + PKG + " --user " + USER) and "isForeground=true" in shell("dumpsys activity services " + PKG + " --user " + USER)
    expect(closed, "", "D인데 펫 화면이 그대로 앞에 있음")
    return expect(alive, "펫 화면 닫힘, 포그라운드 서비스 유지", "D로 바꾼 뒤 포그라운드 서비스가 없음")


@case("BB-07", "주행거리: +1km 반영, 큰 점프(+8km)는 무시")
def bb07():
    gear("Drive")
    time.sleep(1)
    cur = int(console_state()["odometer"]["traveled_distance_km"])
    settle()
    before = num(prefs(), "km")
    base = cur + 1000
    logcat_clear()
    # The car/SDK may merge two quick updates into one (seen on the emulator: +1 and +1 arrived as +2, and +10, +11 as +9,
    # which the app rightly drops as a jump). So send the next value only after the app has logged the previous one.
    for v in (base, base + 1, base + 2, base + 10, base + 11):
        odometer(v)
        if not wait_log(f"odometer {float(v)}:", 20):
            raise AssertionError(f"앱이 주행거리 {v}를 20초 안에 받지 못함 (Vehicle SDK 전달 지연)")
    settle()
    after = num(prefs(), "km")
    d = after - before
    return expect(abs(d - 3.0) < 0.06, f"총 거리 +{d:.2f}km (기준점 이동 → +1 +1, +8 무시, +1)", f"총 거리 변화 {d:.2f}km, 기대 +3.0km")


def _turn(with_signal, slow=True):
    if with_signal:
        signal("BlinkRight")
        time.sleep(0.8)
    for a in (30, 60, 90):
        controls(steering=a); time.sleep(0.5)
    time.sleep(0.4)
    for a in (60, 30, 0):
        controls(steering=a); time.sleep(0.5)
    signal("Off")
    time.sleep(4.5)                              # past the 4 s turn cooldown


@case("BB-08", "깜빡이를 켜고 회전하면 '켠 회전'이 늘어난다")
def bb08():
    settle(); p0 = prefs()
    _turn(True)
    settle(); p1 = prefs()
    g = num(p1, "good") - num(p0, "good"); m = num(p1, "missed") - num(p0, "missed")
    return expect(g == 1 and m == 0, "켠 회전 +1, 안 켠 회전 +0", f"켠 회전 +{g:.0f}, 안 켠 회전 +{m:.0f} (기대 +1, +0)")


@case("BB-09", "깜빡이 없이 회전하면 '안 켠 회전'이 늘고 기분이 내려간다")
def bb09():
    settle(); p0 = prefs()
    _turn(False)
    settle(); p1 = prefs()
    m = num(p1, "missed") - num(p0, "missed"); dm = num(p0, "mood") - num(p1, "mood")
    expect(m == 1, "", f"안 켠 회전 +{m:.0f} (기대 +1)")
    return expect(dm >= 8 or num(p0, "mood") <= 10, f"안 켠 회전 +1, 기분 {num(p0,'mood'):.0f}→{num(p1,'mood'):.0f}", f"기분이 {dm:.0f}만 내려감 (기대 약 10)")


@case("BB-10", "핸들을 빠르게 돌리면 '급한 핸들'이 늘어난다 (천천히 돌리면 안 늘어남)")
def bb10():
    settle(); p0 = prefs()
    controls(steering=20); time.sleep(0.25)
    for a in (80, 140, 200, 260, 320):
        controls(steering=a)
    time.sleep(1.5)
    for a in (200, 120, 60, 0):
        controls(steering=a); time.sleep(0.6)
    time.sleep(4)
    settle(); p1 = prefs()
    h = num(p1, "harsh") - num(p0, "harsh")
    return expect(h == 1, "급한 핸들 +1 (천천히 90°까지는 BB-08/09에서 0)", f"급한 핸들 +{h:.0f} (기대 +1)")


@case("BB-11", "브레이크를 10초 안에 3번 밟으면 '덜컹 브레이크'가 늘어난다")
def bb11():
    settle(); p0 = prefs()
    for _ in range(3):
        controls(brake=80); time.sleep(0.7); controls(brake=0); time.sleep(1.0)
    settle(); p1 = prefs()
    j = num(p1, "jerky") - num(p0, "jerky")
    return expect(j == 1, "덜컹 브레이크 +1", f"덜컹 브레이크 +{j:.0f} (기대 +1)")


@case("BB-12", "브레이크를 한 번 길게 밟는 것은 '덜컹'이 아니다")
def bb12():
    time.sleep(8)
    settle(); p0 = prefs()
    controls(brake=80); time.sleep(3.2); controls(brake=0)
    settle(); p1 = prefs()
    j = num(p1, "jerky") - num(p0, "jerky")
    return expect(j == 0, "덜컹 브레이크 +0", f"덜컹 브레이크 +{j:.0f} (기대 0)")


@case("BB-13", "P로 돌아오면 주행이 기록되고 펫 화면이 저절로 열린다")
def bb13():
    settle(); p0 = prefs()
    gear("Park")
    time.sleep(4)
    front = drivepet_in_front()
    texts = " ".join(ui_texts())
    settle(); p1 = prefs()
    rows0, rows1 = drivelog(p0), drivelog(p1)
    trips = num(p1, "trips") - num(p0, "trips")
    expect(front, "", "P로 돌아왔는데 펫 화면이 자동으로 열리지 않음")
    expect(trips == 1, "", f"주행 횟수 +{trips:.0f} (기대 +1)")
    expect("이번 주행" in texts, "", "주행 결과 카드('이번 주행')가 화면에 없음")
    last = rows1[-1] if rows1 else []
    return expect(len(last) >= 8 and float(last[1]) > 0, f"화면 자동 열림, 결과 카드, 주행 횟수 +1, 운전 기록 한 줄: {','.join(last)}",
                  "운전 기록에 오늘 주행이 남지 않음")


@case("BB-14", "EV 배터리 10%면 펫이 '배고픈' 상태, 80%면 '평상시' (캐릭터 정보 화면)")
def bb14():
    def state_text():
        adb("shell", "input", "keyevent", "4"); time.sleep(0.5)
        adb("shell", "input", "keyevent", "4"); time.sleep(0.5)
        adb("shell", "am", "start", "--user", USER, "-n", f"{PKG}/.MainActivity"); time.sleep(1.5)
        if not tap_text("메뉴"):
            raise AssertionError("'메뉴' 버튼을 못 찾음")
        time.sleep(1.5)
        if not tap_text("캐릭터 정보"):
            raise AssertionError("'캐릭터 정보' 줄을 못 찾음")
        time.sleep(1.5)
        t = ui_texts()
        return t[t.index("지금 상태") + 1] if "지금 상태" in t else "(상태 줄 없음)"
    soc(10); time.sleep(3)
    low = state_text()
    soc(80); time.sleep(3)
    ok = state_text()
    adb("shell", "input", "keyevent", "4"); adb("shell", "input", "keyevent", "4")
    return expect(low == "배고픈" and ok == "평상시", f"10% → '{low}', 80% → '{ok}'", f"10% → '{low}', 80% → '{ok}' (기대 '배고픈', '평상시')")


@case("BB-15", "연료 % 신호")
def bb15():
    cat = urllib.request.urlopen(CONSOLE + "/vehicle/catalog", timeout=8).read().decode().lower()
    if "fuel" not in cat:
        raise Skip("이 에뮬레이터는 전기차라 가상 콘솔에 연료 신호가 없음(시험 불가)")
    raise AssertionError("콘솔에 연료 신호가 있음: 이 케이스를 구현해야 함")


@case("BB-16", "길 안내를 시작하면 NaviHelper가 경로를 알려 준다 (로그)")
def bb16():
    gear("Park")
    adb("shell", "am", "start", "--user", USER, "-n", f"{NAVI}/.MainActivity")
    time.sleep(3)
    if tap_text("안내 종료"):                     # a route from an earlier run: end it first
        time.sleep(3)
    logcat_clear()
    adb("shell", "am", "start", "--user", USER, "-n", f"{PKG}/.MainActivity")
    time.sleep(2)
    demo("route")
    time.sleep(20)                               # the navigation shows the choices and starts by itself after a few seconds
    log = log_drivepet()
    return expect("navi route: 대구" in log, "로그에 'navi route: 대구'", "경로 로그가 없음:\n" + log[-400:])


@case("BB-17", "앱을 껐다 켜도 저장된 값과 이름이 그대로다")
def bb17():
    settle(); p0 = prefs()
    restart_app()
    time.sleep(10)
    p1 = prefs()
    same = all(p0.get(k) == p1.get(k) for k in ("good", "missed", "harsh", "jerky", "trips", "petName", "km"))
    texts = " ".join(ui_texts())
    expect(same, "", "다시 켠 뒤 저장 값이 다름: " + str({k: (p0.get(k), p1.get(k)) for k in ("good", "missed", "harsh", "jerky", "trips", "km")}))
    return expect(p1.get("petName", "") in texts, f"이름 '{p1.get('petName')}'과 카운터가 그대로", "화면에 펫 이름이 없음")


@case("BB-18", "시험하는 동안 앱이 죽지 않았다")
def bb18():
    out = adb("logcat", "-d", "-b", "crash") + adb("logcat", "-d", "-s", "AndroidRuntime")
    bad = [l for l in out.splitlines() if PKG in l or "FATAL EXCEPTION" in l]
    return expect(not bad, "크래시 로그 없음", "크래시 흔적:\n" + "\n".join(bad[:5]))


# ---------------------------------------------------------------------------------------------- setup and main
def environment(args):
    if not os.path.exists(ADB) and not shutil.which(ADB):
        print(f"adb를 찾을 수 없음: {ADB}", file=sys.stderr); return False
    dev = adb("devices")
    if "\tdevice" not in dev:
        print("실행 중인 에뮬레이터가 없음:\n" + dev, file=sys.stderr); return False
    adb("forward", "tcp:8088", "tcp:8088")
    try:
        st = console_state()
    except Exception as e:
        print("Virtual Console에 연결할 수 없음:", e, file=sys.stderr); return False
    if not st.get("browser", {}).get("active"):
        print("Virtual Console 페이지가 열려 있지 않음: 브라우저에서 http://127.0.0.1:8088/ 를 열어 둔 채로 다시 실행", file=sys.stderr)
        return False
    if args.apk:
        print("설치:", adb("install", "-r", "--user", USER, args.apk, timeout=180).strip())
    if args.fresh:
        adb("shell", "pm", "clear", "--user", USER, PKG)
        adb("shell", "pm", "grant", "--user", USER, PKG, "android.permission.ACCESS_FINE_LOCATION")
        adb("shell", "pm", "grant", "--user", USER, PKG, "android.permission.POST_NOTIFICATIONS")
        adb("shell", "appops", "set", "--user", USER, PKG, "SYSTEM_ALERT_WINDOW", "allow")
    adb("emu", "geo", "fix", *SEOUNGNAM)
    gear("Park"); controls(); signal("Off"); soc(80)
    adb("shell", "am", "start", "--user", USER, "-n", f"{NAVI}/.MainActivity")
    time.sleep(4)
    if tap_text("안내 종료"):
        time.sleep(2)
    adb("shell", "am", "start", "--user", USER, "-n", f"{PKG}/.MainActivity")
    onboard_if_needed()
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk"); ap.add_argument("--fresh", action="store_true")
    ap.add_argument("--only", nargs="*"); ap.add_argument("--report")
    args = ap.parse_args()
    if not environment(args):
        sys.exit(2)
    started = time.strftime("%Y-%m-%d %H:%M:%S")
    for fn in CASES:
        if args.only and fn.cid not in args.only:
            continue
        t0 = time.time()
        try:
            detail = fn() or ""
            status = "PASS"
        except Skip as e:
            status, detail = "SKIP", str(e)
        except AssertionError as e:
            status, detail = "FAIL", str(e)
        except Exception as e:                    # a broken environment, not a wrong answer
            status, detail = "FAIL", f"{type(e).__name__}: {e}"
        results.append((fn.cid, fn.title, status, detail))
        print(f"[{status}] {fn.cid} {fn.title}  ({time.time()-t0:.0f}s)\n        {detail}", flush=True)
    n = {s: sum(1 for r in results if r[2] == s) for s in ("PASS", "FAIL", "SKIP")}
    print(f"\n합계: 통과 {n['PASS']} / 실패 {n['FAIL']} / 건너뜀 {n['SKIP']}")
    if args.report:                                # write the report first: the clean-up below must never lose it
        with open(args.report, "w", encoding="utf-8") as f:
            f.write(f"# 블랙박스 테스트 결과\n\n실행: {started}\n\n| ID | 항목 | 결과 | 내용 |\n|---|---|:-:|---|\n")
            for cid, title, status, detail in results:
                f.write(f"| {cid} | {title} | {status} | {detail.splitlines()[0] if detail else ''} |\n")
            f.write(f"\n합계: 통과 {n['PASS']} / 실패 {n['FAIL']} / 건너뜀 {n['SKIP']}\n")
    try:                                           # leave the car parked and the wheel straight
        controls(); signal("Off"); gear("Park"); soc(80)
    except Exception as e:
        print("정리 단계 건너뜀:", e)
    sys.exit(1 if n["FAIL"] else 0)


if __name__ == "__main__":
    main()
