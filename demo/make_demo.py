"""Records the demo on the Pleos emulator and lays the presenter's own voice over it.

The presenter's recording (one file, five spoken blocks A-E with pauses between them) is cut into
clips; the in-car voices (neighbour answers, partner-restaurant announcement) are synthesised
because that is what a car's system voice sounds like. Scene actions are timed from clip lengths.

Needs: emulator running, app installed, Virtual Console tab open (DDS ONLINE) and visible,
       server/app_server.py running, ffmpeg, `pip install edge-tts`.
Usage: python make_demo.py <path to the voice recording>
Output: ../video/gildongmu_demo.mp4
"""
import asyncio
import json
import pathlib
import subprocess
import sys
import threading
import time
import urllib.request

import edge_tts

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT.parent / "video"
WORK = ROOT / "work"
for d in (OUT, WORK):
    d.mkdir(exist_ok=True)

ADB = pathlib.Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
FF = next((pathlib.Path.home() / "AppData/Local/Microsoft/WinGet/Packages").rglob("ffmpeg.exe"))
FP = FF.with_name("ffprobe.exe")
DEV = "emulator-5554"
CONSOLE = "http://127.0.0.1:8088"
SERVER = "http://127.0.0.1:8080"
APP = "com.gildongmu.app/.MainActivity"
REC_LAG = 1.65  # measured: the first frame comes ~0.85 s later than the process start + 0.8

# Presenter's blocks: (start, end) in the raw recording, and what was actually said, with times
# in the raw recording (checked against a transcript of the recording).
CLIPS = {
    "A": (0.0, 19.0),
    "B": (22.3, 27.9),
    "C": (30.7, 38.8),
    "D": (40.8, 46.7),
    "E": (50.2, 57.0),
}
SAID = {
    "A": [(0.0, 2.9, "안녕하세요, 길동무 앱 시연 영상 보여드리겠습니다."),
          (3.72, 8.48, "먼저 명절에 고속도로가 꽉 막혀서 차가 서 있다고 가정해 보겠습니다."),
          (9.18, 12.1, "앞에 무슨 일이 있는지, 휴게소는 막히는지 모르잖아요."),
          (12.56, 15.64, "그때 근처 차한테 바로 물어볼 수 있습니다."),
          (16.26, 18.56, "휴게소가 혼잡한지 눌러 볼게요.")],
    "B": [(22.5, 24.06, "이렇게 바로 답이 옵니다."),
          (25.06, 27.6, "이번에는 맛집을 물어볼게요.")],
    "C": [(30.94, 34.96, "근처에 답해 줄 차가 없으면, 이렇게 공공데이터가 알려 주고"),
          (34.96, 36.48, "쿠폰도 나옵니다."),
          (37.1, 38.24, "이제 출발을 해볼게요.")],
    "D": [(41.06, 43.68, "달리기 시작하면 버튼이 잠기고"),
          (43.68, 46.3, "대신 앞차 소식은 소리로 들려줍니다.")],
    "E": [(50.44, 55.46, "멈추면 다시 풀리고, 위에 내비랑 연동해서 남은 시간도 나옵니다."),
          (55.88, 56.62, "이상입니다.")],
}
# In-car voices (synthesised)
APPVOICE = {
    "v1": ("ko-KR-InJoonNeural", "천안삼거리휴게소는 보통이고, 안성휴게소는 좀 혼잡해요.", "귀성길곰: "),
    "v2": ("ko-KR-SunHiNeural", "호두과자 맛집 추천드려요. 방문 인증하면 십 퍼센트 할인이에요.", "공공데이터: "),
    "v3": ("ko-KR-InJoonNeural", "앞쪽에 사고 처리 중이에요. 곧 풀릴 거예요.", "귀성길곰: "),
}


def adb(*args):
    return subprocess.run([str(ADB), "-s", DEV, *args], capture_output=True, text=True)


def put(path):
    """The console only answers while its browser tab is awake, so retry for a while."""
    for _ in range(40):
        try:
            urllib.request.urlopen(urllib.request.Request(CONSOLE + path, method="PUT"), timeout=5).read()
            return
        except Exception:
            time.sleep(0.5)
    raise RuntimeError("virtual console not reachable - keep its browser window visible")


def car(gear, speed):
    brake = 100 if speed == 0 else 0
    put(f"/vehicle/gear?gear={gear}")
    put(f"/vehicle/controls?speed={speed}&accelerator=0&brake={brake}&steering=0")


class SpeedHolder:
    """The emulated car coasts back to 0 on its own, so keep re-sending the wanted speed."""

    def __init__(self):
        self.speed = 0
        self.run = True
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        while self.run:
            try:
                brake = 100 if self.speed == 0 else 0
                req = urllib.request.Request(
                    f"{CONSOLE}/vehicle/controls?speed={self.speed}&accelerator=0&brake={brake}&steering=0", method="PUT")
                urllib.request.urlopen(req, timeout=3).read()
            except Exception:
                pass
            time.sleep(0.4)


def car_speed():
    return json.load(urllib.request.urlopen(CONSOLE + "/vehicle/state", timeout=5))["controls"]["speed_kph"]


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def duration(path):
    out = subprocess.run([str(FP), "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
                         capture_output=True, text=True).stdout
    return float(out)


def cut_clips(recording):
    """Presenter blocks -> cleaned wav files (noise, level) and their lengths."""
    d = {}
    for name, (a, b) in CLIPS.items():
        out = WORK / f"{name}.wav"
        af = ("highpass=f=90,afftdn=nf=-28,loudnorm=I=-16:TP=-1.5:LRA=11,"
              f"afade=t=in:d=0.04,afade=t=out:st={b - a - 0.08}:d=0.08")
        subprocess.run([str(FF), "-y", "-v", "error", "-ss", str(a), "-t", str(b - a), "-i", str(recording),
                        "-af", af, "-ac", "1", "-ar", "48000", str(out)], check=True)
        d[name] = duration(out)
    return d


async def make_app_voices():
    d = {}
    for name, (voice, text, _) in APPVOICE.items():
        mp3 = WORK / f"{name}.mp3"
        key = WORK / f"{name}.txt"
        for _ in range(6):
            if mp3.exists() and key.exists() and key.read_text(encoding="utf-8") == f"{voice}|{text}":
                break
            try:
                await edge_tts.Communicate(text, voice, rate="-4%").save(str(mp3))
                key.write_text(f"{voice}|{text}", encoding="utf-8")
                break
            except Exception:
                await asyncio.sleep(2)
        else:
            raise RuntimeError(f"TTS failed for {name}")
        wav = WORK / f"{name}.wav"
        subprocess.run([str(FF), "-y", "-v", "error", "-i", str(mp3), "-af", "loudnorm=I=-19:TP=-2:LRA=7",
                        "-ac", "1", "-ar", "48000", str(wav)], check=True)
        d[name] = duration(wav)
    return d


def plan(d):
    """Start time (s from the start of the recording) of every clip and every scene action."""
    t = {}
    t["A"] = 0.8
    t["tap_rest"] = t["A"] + (18.56 - CLIPS["A"][0]) + 0.35
    t["v1"] = t["tap_rest"] + 4.0                       # the answer shows 2.6-4 s after the tap
    t["B"] = t["v1"] + d["v1"] + 0.5
    t["tap_food"] = t["B"] + d["B"] + 0.1
    t["v2"] = t["tap_food"] + 4.0
    t["C"] = t["v2"] + d["v2"] + 0.5
    t["accel"] = [t["C"] + (38.3 - CLIPS["C"][0]) + 0.1 + k for k in (0, 1.2, 2.4)]
    t["D"] = t["accel"][0] + 1.8
    t["tap_locked"] = t["D"] + 3.0
    t["event"] = t["D"] + 4.8
    t["v3"] = t["event"] + 2.6
    t["E"] = t["v3"] + d["v3"] + 0.5
    t["decel"] = [t["v3"] + 1.2, t["v3"] + 2.6]
    # the emulated speed flickers while it settles, so shift into P: the state is then fixed (parked)
    t["park"] = t["v3"] + 4.3
    t["end"] = t["E"] + d["E"] + 1.5
    return t


def record(t):
    hold = SpeedHolder()
    car("Drive", 0)
    for _ in range(20):
        if car_speed() == 0:
            break
        time.sleep(1)
    adb("shell", "am", "force-stop", APP.split("/")[0])
    adb("shell", "am", "start", "-n", APP)
    time.sleep(9)  # app up, first poll done (history skipped)
    adb("shell", "rm", "-f", "/sdcard/demo.mp4")
    rec = subprocess.Popen([str(ADB), "-s", DEV, "shell", "screenrecord", "--bit-rate", "8000000",
                            "--time-limit", "170", "/sdcard/demo.mp4"])
    t0 = time.time() + REC_LAG

    def at(sec):
        delay = t0 + sec - time.time()
        if delay > 0:
            time.sleep(delay)

    def accident():
        urllib.request.urlopen(SERVER + "/api/demo/event?kind=accident", timeout=5).read()

    events = [(t["tap_rest"], lambda: tap(1240, 210)), (t["tap_food"], lambda: tap(1000, 305)),
              (t["tap_locked"], lambda: tap(1240, 210)), (t["event"], accident)]
    events += [(s, (lambda sp=sp: setattr(hold, "speed", sp))) for s, sp in zip(t["accel"], (10, 20, 30))]
    events += [(s, (lambda sp=sp: setattr(hold, "speed", sp))) for s, sp in zip(t["decel"], (15, 0))]
    events += [(t["park"], lambda: put("/vehicle/gear?gear=Park"))]
    try:
        for sec, fn in sorted(events, key=lambda e: e[0]):
            at(sec)
            fn()
        at(t["end"])
    finally:
        adb("shell", "pkill", "-INT", "screenrecord")
    rec.wait(timeout=20)
    time.sleep(2)
    hold.run = False  # keep the brake on until the recording is closed, or the car creeps
    subprocess.run([str(ADB), "-s", DEV, "pull", "/sdcard/demo.mp4", str(WORK / "raw.mp4")], capture_output=True)
    car("Park", 0)


def srt_time(s):
    ms = int(round(s * 1000))
    return f"{ms // 3600000:02}:{ms // 60000 % 60:02}:{ms // 1000 % 60:02},{ms % 1000:03}"


CAPTION = {"v2": "호두과자 맛집 추천드려요. 방문 인증하면 10% 할인이에요."}


def make_srt(t, d):
    rows = []
    for name, spans in SAID.items():
        for a, b, text in spans:
            rows.append((t[name] + a - CLIPS[name][0], t[name] + b - CLIPS[name][0], text))
    for name, (_, text, prefix) in APPVOICE.items():
        rows.append((t[name], t[name] + d[name], prefix + CAPTION.get(name, text)))
    rows.sort()
    lines = []
    for i, (a, b, text) in enumerate(rows, 1):
        lines += [str(i), f"{srt_time(a)} --> {srt_time(b)}", text, ""]
    (WORK / "sub.srt").write_text("\n".join(lines), encoding="utf-8")


def mux(t):
    names = list(CLIPS) + list(APPVOICE)
    cmd = [str(FF), "-y", "-v", "error", "-i", "raw.mp4"]
    for n in names:
        cmd += ["-i", f"{n}.wav"]
    delays = ";".join(f"[{i + 1}:a]adelay={int(t[n] * 1000)}|{int(t[n] * 1000)}[a{i}]" for i, n in enumerate(names))
    mix = "".join(f"[a{i}]" for i in range(len(names))) + f"amix=inputs={len(names)}:normalize=0[aout]"
    style = ("FontName=Malgun Gothic,FontSize=11,Bold=1,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,"
             "BorderStyle=1,Outline=0,Shadow=0,MarginV=24,Alignment=2")
    cmd += ["-filter_complex",
            f"{delays};{mix};[0:v]pad=iw:ih+150:0:0:color=black,subtitles=sub.srt:force_style='{style}'[vout]",
            "-map", "[vout]", "-map", "[aout]", "-c:v", "libx264", "-crf", "20", "-preset", "medium",
            "-c:a", "aac", "-b:a", "192k", "-pix_fmt", "yuv420p", "-shortest", "../../video/gildongmu_demo.mp4"]
    subprocess.run(cmd, cwd=WORK, check=True)


if __name__ == "__main__":
    rec_path = pathlib.Path(sys.argv[1])
    d = cut_clips(rec_path)
    d.update(asyncio.run(make_app_voices()))
    times = plan(d)
    print(json.dumps(times, ensure_ascii=False, indent=1))
    record(times)
    make_srt(times, d)
    mux(times)
    print("done ->", OUT / "gildongmu_demo.mp4")
