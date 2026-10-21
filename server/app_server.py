"""HTTP front for core.py / live.py so the car app can run against a real backend.
    python server/app_server.py [port]          (default 8080; the emulator reaches it at 10.0.2.2)

Car app (JSON):
    GET  /api/nearby?vid=me
    POST /api/send    {"preset" | "spoken","to","arg","gear","voice","restricted"}   one-touch or spoken question;
                      "restricted" = the vehicle forbids touch (all UX restrictions set): only "voice" gets through;
                      "to" = one car, left out = everyone around (up to 3 answers, all visible to the neighbours)
    POST /api/report  {"msg_id","reason"}                      report a neighbour: blocked, answers no longer shown
    GET  /api/inbox?vid=me&after=<id>
    POST /api/thanks  {"msg_id"}                               "고마워요" to whoever answered
    GET  /api/ahead?vid=me                                     next rest area + liked restaurant
    GET  /api/situation                                        what neighbours reported about the road ahead
    POST /api/inform  {"kind"}                                 "알리기": tell the road what you see (no question needed)
    GET/POST /api/mood {"mood"}                                "한마디": a fixed feeling shown as a bubble over our car
    POST /api/n/mood {"vid","mood"}                            the same, from a neighbour's phone
    POST /api/trip    {"dest"}
    GET  /api/demo/event?kind=accident|rest                    a neighbour speaks up unprompted (demo)
Neighbour phones (open http://<this PC>:8080/neighbor):
    POST /api/n/skip {"vid","qid"}   POST /api/n/pause {"vid","paused"}      decline one question / stop receiving
    GET  /api/n/options                                       the lists a profile is built from
    POST /api/n/join {"adjective","animal","dest","color","show_model","show_dest","show_title"} -> {"vid"}
    POST /api/n/profile (same fields + "vid")                 change it later; nothing typed ever reaches other drivers   GET /api/n/poll?vid=   POST /api/n/answer {"vid","qid","option"|"spoken"}
    Over https (port+363, self-signed) the page can use the phone's microphone.

Real people on phones answer when they are around. If nobody answers in time (or nobody is
connected), simulated neighbours and public-data fallbacks answer, so the app always works.
"""
import json
import pathlib
import shutil
import socket
import ssl
import subprocess
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import core
import live

ME = "me"
APP_PRESETS = {"HELLO", "ASK_FOOD", "ASK_REST", "ASK_ACCIDENT"}  # what a driver may send
M_PER_DEG_LAT = 111_000.0
LAT0, LON0 = 36.7500, 127.1500
HUMAN_WAIT = 15.0          # seconds a real neighbour gets before the simulated one answers
LIVE_OFFSETS_M = [30, -30, 70, -70, 110, 150]   # where phone neighbours sit around the car
LIVE_MODELS = ["투싼", "아이오닉 5", "K5", "쏘나타", "EV6", "그랜저"]
STATIC = pathlib.Path(__file__).with_name("static")

# (id, nickname, model, dest, metres ahead (+) / behind (-))
NEIGHBOURS = [
    ("car03", "귀성길곰", "아이오닉 5", "대구", 40),
    ("car07", "느긋한토끼", "그랜저", "대구", 90),
    ("car01", "졸린사자", "쏘나타", "부산", -60),
    ("car05", "달리는펭귄", "EV6", "광주", 120),
]

lock = threading.RLock()  # one shared connection, used from several handler threads
db = None
room = live.LiveRoom()
reports = live.Reports()
car_mood = {"key": None, "at": 0.0}   # the bubble over our own car
SIM_MOODS = ["home", "sleepy", "cheer", "hungry", "escape"]


def _open():
    global db
    db = sqlite3.connect(":memory:", check_same_thread=False)
    db.row_factory = sqlite3.Row
    db.executescript(core.SCHEMA.read_text(encoding="utf-8"))


def seed():
    _open()
    core.upsert_vehicle(db, ME, "나", "아이오닉 6", LAT0, LON0, heading=180, speed_kmh=0, gear="D", dest="대구")
    for vid, nick, model, dest, ahead_m in NEIGHBOURS:
        lat = LAT0 - ahead_m / M_PER_DEG_LAT  # heading south: ahead means smaller latitude
        core.upsert_vehicle(db, vid, nick, model, lat, LON0 + 0.00004, heading=180, speed_kmh=2, gear="D", dest=dest)
    db.executemany("INSERT INTO places(kind,name,lat,lon,congestion,likes) VALUES (?,?,?,?,?,?)", [
        ("restarea", "천안삼거리휴게소", LAT0 - 5000 / M_PER_DEG_LAT, LON0, 1, 0),
        ("restarea", "안성휴게소", LAT0 - 20000 / M_PER_DEG_LAT, LON0, 2, 0),
        ("restaurant", "호두과자 맛집", LAT0 - 6000 / M_PER_DEG_LAT, LON0, None, 3),
    ])
    db.commit()


def _sync():
    """Keep every vehicle fresh, and show a phone neighbour on the road only while its page is open."""
    now = time.time()
    active = {p.vid for p in room.active()}
    for i, p in enumerate(room.players.values()):
        off = LIVE_OFFSETS_M[i % len(LIVE_OFFSETS_M)]
        core.upsert_vehicle(db, p.vid, p.nick, LIVE_MODELS[i % len(LIVE_MODELS)] if p.show_model else "차종 비공개",
                            LAT0 - off / M_PER_DEG_LAT, LON0 - 0.00004, heading=180, speed_kmh=2, gear="D",
                            dest=p.dest if p.show_dest else None,
                            discoverable=p.vid in active and not p.blocked, now=now)
    db.execute("UPDATE vehicles SET updated_at=? WHERE id NOT LIKE 'live-%'", (now,))
    db.commit()


def _name(vid):
    if vid == "PUBLIC_DATA":
        return "공공데이터"
    row = db.execute("SELECT nickname FROM vehicles WHERE id=?", (vid,)).fetchone()
    return row["nickname"] if row else vid


def _post(from_id, to_id, text, auto=0):
    """auto: 0 simulated neighbour, 1 public data, 2 a real person on a phone."""
    cur = db.execute("INSERT INTO messages(from_id,to_id,preset,arg,auto,created_at) VALUES (?,?,?,?,?,?)",
                     (from_id, to_id, "REPLY_TEXT", text, auto, time.time()))
    db.commit()
    return cur.lastrowid


def ahead_info():
    rest = core.places_ahead(db, ME, "restarea", limit=3)
    food = core.places_ahead(db, ME, "restaurant", limit=1)
    return {
        "rest": [{"name": r["name"], "km": round(r["distance_m"] / 1000, 1),
                  "congestion": core.CONGESTION_LABEL[r["congestion"] or 0]} for r in rest],
        "food": [{"name": f["name"], "km": round(f["distance_m"] / 1000, 1), "likes": f["likes"]} for f in food],
    }


REST_WORD = {"free": "한산해요", "normal": "보통이에요", "busy": "혼잡해요"}
ANSWER_WORD = {
    "ASK_FOOD": {"walnut": "호두과자 맛집이 좋아요", "udon": "휴게소 우동이 괜찮아요"},
    "ASK_ACCIDENT": {"accident": "앞쪽에 사고 처리 중이에요", "work": "앞쪽에서 공사 중이에요", "jam": "그냥 정체예요, 곧 풀려요"},
    "HELLO": {"hi": "반가워요!", "same": "저도 같은 방향이에요"},
}


def compose_answer(preset, option):
    """The sentence the car hears for the one-touch answer a neighbour picked."""
    if preset == "ASK_REST":
        info = ahead_info()["rest"]
        name = info[0]["name"] if info else "다음 휴게소"
        if option in REST_WORD:
            return f"{name}는 {REST_WORD[option]}"
    elif option in ANSWER_WORD.get(preset, {}):
        return ANSWER_WORD[preset][option]
    return "잘 모르겠어요, 미안해요"


def _report(preset, option, who):
    """Share what a neighbour told us with every car on the road."""
    topic = {"ASK_ACCIDENT": "ahead", "ASK_REST": "rest", "ASK_FOOD": "food"}.get(preset)
    if topic and option != "unknown":
        reports.add(topic, option, who)


def _respond(preset, arg, to=None):
    """Simulated neighbour / public-data answer. If the question was for one simulated car, that car answers."""
    with lock:
        near = core.nearby(db, ME)
        sims = [n for n in near if not n["id"].startswith("live-")]
        if to:
            sims = [n for n in sims if n["id"] == to] or sims
        if preset == "HELLO":
            mates = [n for n in sims if n["same_dest"]]
            if mates:
                _post(mates[0]["id"], ME, f"반가워요! 저도 {arg} 가요")
                return
        if preset == "ASK_REST":
            info = ahead_info()["rest"]
            if sims and info:
                word = {"여유": "여유로워요", "보통": "보통이에요", "혼잡": "혼잡해요"}
                _post(sims[0]["id"], ME, ", ".join(f"{r['name']}는 {word[r['congestion']]}" for r in info[:2]))
                _report("ASK_REST", {"여유": "free", "보통": "normal", "혼잡": "busy"}[info[0]["congestion"]], sims[0]["id"])
                return
        if preset == "ASK_ACCIDENT" and sims:
            _post(sims[0]["id"], ME, "앞쪽 사고 처리 중이에요, 곧 풀려요")
            _report("ASK_ACCIDENT", "accident", sims[0]["id"])
            return
        if preset == "ASK_FOOD":  # nobody answers -> public-data fallback
            food = ahead_info()["food"]
            if food:
                _post("PUBLIC_DATA", ME, f"{food[0]['name']} 추천해요||방문 인증 시 10% 할인", auto=1)
                return
        _post("PUBLIC_DATA", ME, "지금은 알려 드릴 정보가 없어요", auto=1)


def _respond_later(preset, arg, qid, to=None):
    """Real neighbours get HUMAN_WAIT seconds; otherwise the simulated answer comes after a moment."""
    if qid is None:
        threading.Timer(1.2, _respond, (preset, arg, to)).start()
        return

    def fallback():
        if not room.is_answered(qid):
            _respond(preset, arg, to)
    threading.Timer(HUMAN_WAIT, fallback).start()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    _head = False   # HEAD requests get the headers only

    def _body(self, data):
        if not self._head:
            self.wfile.write(data)

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self._body(body)

    def _file(self, name, ctype, download_as=None):
        try:
            body = (STATIC / name).read_bytes()
        except OSError:
            return self._json(404, {"error": "not found"})
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if download_as:   # a real file name, so the phone saves it as an app
            self.send_header("Content-Disposition", f'attachment; filename="{download_as}"')
        self.end_headers()
        self._body(body)

    def _guard(self, fn):
        """A bad request must never take the connection down: answer 400/500 and keep serving."""
        try:
            fn()
        except (ValueError, KeyError) as e:
            self._json(400, {"error": f"bad request ({e.__class__.__name__})"})
        except Exception as e:  # noqa: BLE001 - last resort, logged
            print("server error:", repr(e), flush=True)
            self._json(500, {"error": "server error"})

    def do_GET(self):
        self._guard(self._get)

    def do_HEAD(self):
        self._head = True
        self._guard(self._get)

    def do_POST(self):
        self._guard(self._post_request)

    def _get(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path in ("/neighbor", "/neighbor/"):
            return self._file("neighbor.html", "text/html; charset=utf-8")
        if u.path in ("/ask", "/ask/"):   # voice test page: no app needed
            return self._file("ask.html", "text/html; charset=utf-8")
        if u.path == "/manifest.webmanifest":
            return self._file("manifest.webmanifest", "application/manifest+json; charset=utf-8")
        if u.path == "/connect":   # open on the phone: install the app, then tap the button to point it at this server
            host = self.headers.get("Host", "").split(":")[0] or lan_ip()
            base = f"http://{host}:{self.server.server_address[1] if self.server.server_address[1] < 8443 else 8080}"
            page = (STATIC / "connect.html").read_text(encoding="utf-8").replace("{{BASE}}", base)
            body = page.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self._body(body)
            return
        if u.path == "/gildongmu.apk":   # install on a phone: open this address in the phone's browser
            print("apk download requested", flush=True)
            return self._file("gildongmu-debug.apk", "application/vnd.android.package-archive", "gildongmu.apk")
        if u.path == "/icon.svg":
            return self._file("icon.svg", "image/svg+xml")
        with lock:
            _sync()
            if u.path == "/api/nearby":
                out = core.nearby(db, q.get("vid", ME))
                for n in out:
                    n["live"] = n["id"].startswith("live-")
                    if n["live"] and n["id"] in room.players:
                        pl = room.players[n["id"]]
                        n["color"] = pl.color
                        n["title"] = live.title_for(pl.helped) if pl.show_title else ""
                        n["mood"] = room.mood_of(n["id"])
                    else:
                        k = (int(time.time() // 25) + sum(map(ord, n["id"]))) % (len(SIM_MOODS) + 3)
                        n["mood"] = live.MOODS[SIM_MOODS[k]] if k < len(SIM_MOODS) else None
                out.sort(key=lambda n: not n["live"])  # real neighbours first (stable: keeps distance order)
                return self._json(200, out)
            if u.path == "/api/inbox":
                after = int(q.get("after", 0))
                rows = db.execute("SELECT * FROM messages WHERE to_id=? AND id>? ORDER BY id",
                                  (q.get("vid", ME), after)).fetchall()
                out = []
                for r in rows:
                    text, _, coupon = core.render(r["preset"], r["arg"]).partition("||")
                    out.append({"id": r["id"], "from": _name(r["from_id"]), "text": text, "coupon": coupon,
                                "auto": r["auto"] == 1, "human": r["auto"] == 2})
                return self._json(200, out)
            if u.path == "/api/mood":
                key = car_mood["key"] if time.time() - car_mood["at"] <= live.MOOD_SECONDS else None
                return self._json(200, {"mood": live.MOODS.get(key)})
            if u.path == "/api/ahead":
                return self._json(200, ahead_info())
            if u.path == "/api/situation":
                info = ahead_info()["rest"]
                return self._json(200, reports.summary(info[0]["name"] if info else None))
            if u.path == "/api/n/options":
                return self._json(200, {"adjectives": live.ADJECTIVES, "animals": live.ANIMALS,
                                        "colors": live.COLORS, "destinations": live.DESTINATIONS,
                                        "moods": [{"id": k, "text": v} for k, v in live.MOODS.items()]})
            if u.path == "/api/n/poll":
                try:
                    return self._json(200, room.poll(q.get("vid", "")))
                except live.LiveError as e:
                    return self._json(404, {"error": str(e)})
            if u.path == "/api/demo/event":
                near = core.nearby(db, ME)
                if not near:
                    return self._json(200, {"ok": False})
                text = {"accident": "앞쪽 사고 처리 중이에요, 곧 풀려요",
                        "rest": "천안삼거리휴게소는 지금 한산해요"}.get(q.get("kind", "accident"), "안전운전하세요")
                _post(near[0]["id"], ME, text)
                return self._json(200, {"ok": True})
        self._json(404, {"error": "not found"})

    def _post_request(self):
        u = urlparse(self.path)
        n = int(self.headers.get("Content-Length", 0))
        try:
            data = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return self._json(400, {"error": "bad json"})
        with lock:
            _sync()
            if u.path == "/api/trip":
                db.execute("UPDATE vehicles SET dest=? WHERE id=?", (data.get("dest"), ME))
                db.commit()
                return self._json(200, {"ok": True})

            if u.path == "/api/n/join":
                return self._json(200, {"vid": room.join(
                    data.get("adjective"), data.get("animal"), data.get("dest"), data.get("color"),
                    data.get("show_model", True), data.get("show_dest", True), data.get("show_title", True))})

            if u.path == "/api/n/profile":
                try:
                    room.set_profile(data.get("vid", ""), data.get("adjective"), data.get("animal"), data.get("dest"),
                                     data.get("color"), data.get("show_model", True), data.get("show_dest", True),
                                     data.get("show_title", True))
                except live.LiveError as e:
                    return self._json(404, {"error": str(e)})
                return self._json(200, {"ok": True})

            if u.path == "/api/n/answer":
                option = data.get("option", "")
                if not option and data.get("spoken"):  # said out loud: map the words to one of the fixed answers
                    q = room.questions.get(int(data.get("qid", 0)))
                    option = (live.match_answer(q["preset"], data["spoken"]) if q else None) or ""
                    print("voice answer from a phone:", "understood" if option else "not understood", flush=True)  # never the words
                    if not option:
                        return self._json(422, {"error": "no match"})
                try:
                    preset, option = room.answer(data.get("vid", ""), int(data.get("qid", 0)), option)
                except (live.LiveError, ValueError) as e:
                    return self._json(409, {"error": str(e)})
                _post(data["vid"], ME, compose_answer(preset, option), auto=2)
                _report(preset, option, data["vid"])
                return self._json(200, {"ok": True})

            if u.path == "/api/inform":   # the driver tells the road what they see, without being asked
                kind = data.get("kind")
                if kind not in live.AHEAD_TEXT:
                    return self._json(400, {"error": "unknown report"})
                reports.add("ahead", kind, ME)
                return self._json(200, {"ok": True})

            if u.path == "/api/mood":
                key = data.get("mood")
                if key is not None and key not in live.MOODS:
                    return self._json(400, {"error": "unknown mood"})
                car_mood["key"], car_mood["at"] = key, time.time()
                return self._json(200, {"ok": True})

            if u.path == "/api/n/mood":
                try:
                    room.set_mood(data.get("vid", ""), data.get("mood"))
                except live.LiveError as e:
                    return self._json(409, {"error": str(e)})
                return self._json(200, {"ok": True})

            if u.path == "/api/n/skip":
                try:
                    room.skip(data.get("vid", ""), int(data.get("qid", 0)))
                except (live.LiveError, ValueError) as e:
                    return self._json(409, {"error": str(e)})
                return self._json(200, {"ok": True})

            if u.path == "/api/n/pause":
                try:
                    room.set_paused(data.get("vid", ""), bool(data.get("paused")))
                except live.LiveError as e:
                    return self._json(404, {"error": str(e)})
                return self._json(200, {"ok": True})

            if u.path == "/api/report":
                row = db.execute("SELECT from_id FROM messages WHERE id=?", (int(data.get("msg_id", 0)),)).fetchone()
                if not row or row["from_id"] == "PUBLIC_DATA":
                    return self._json(404, {"error": "nothing to report"})
                target = row["from_id"]
                core.report(db, ME, target, str(data.get("reason", "inappropriate"))[:40])  # also blocks
                reports.remove_by(target)
                room.block(target)
                return self._json(200, {"ok": True})

            if u.path == "/api/thanks":
                row = db.execute("SELECT from_id FROM messages WHERE id=?", (int(data.get("msg_id", 0)),)).fetchone()
                return self._json(200, {"ok": True, "live": bool(row and room.thank(row["from_id"]))})

            if u.path != "/api/send":
                return self._json(404, {"error": "not found"})
            preset = data.get("preset") or live.match_question(data.get("spoken"))
            if data.get("spoken"):
                print("voice question from the car:", "understood" if preset in APP_PRESETS else "not understood", flush=True)
            if preset not in APP_PRESETS:
                return self._json(422 if data.get("spoken") else 400, {"error": "unknown preset"})
            # The vehicle decides. When it restricts everything ("restricted"), only a spoken question gets through;
            # otherwise a one-tap preset is fine at any speed (there is no typing, only big one-tap choices).
            if data.get("restricted") and not data.get("voice"):
                return self._json(403, {"error": "차량이 지금은 터치를 제한하고 있어요. 말로 물어보세요"})
            db.execute("UPDATE vehicles SET speed_kmh=?, gear=? WHERE id=?", (0.0, data.get("gear", "D"), ME))
            db.commit()
            near = core.nearby(db, ME)
            if not near:
                return self._json(409, {"error": "no neighbours"})
            to = data.get("to") or None           # None = everyone around, otherwise one car
            try:
                out = core.send(db, ME, to or near[0]["id"], preset, data.get("arg"))
            except core.ChatError as e:
                return self._json(403, {"error": str(e)})
            try:
                qid = room.ask(preset, to=to if to and to.startswith("live-") else None)
            except live.LiveError:
                return self._json(429, {"error": "같은 이웃에게는 잠시 후에 다시 물어볼 수 있어요"})
            if to and to.startswith("live-") and qid is None:
                return self._json(409, {"error": "이 이웃은 지금 질문을 받을 수 없어요"})
            _respond_later(preset, data.get("arg"), qid, to if to and not to.startswith("live-") else None)
            out["waiting_human"] = qid is not None
            out["preset"] = preset
            return self._json(200, out)


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "127.0.0.1"


def _make_cert(ip):
    """Self-signed certificate for the https twin (phones only allow the microphone on https)."""
    certs = pathlib.Path(__file__).with_name("certs")
    cert, key = certs / "cert.pem", certs / "key.pem"
    if cert.exists() and key.exists() and (certs / "ip.txt").read_text() == ip:
        return cert, key
    exe = shutil.which("openssl") or r"C:\Program Files\Git\usr\bin\openssl.exe"
    certs.mkdir(exist_ok=True)
    subprocess.run([exe, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key), "-out", str(cert),
                    "-days", "60", "-subj", "/CN=gildongmu", "-addext", f"subjectAltName=IP:{ip},DNS:localhost"],
                   check=True, capture_output=True)
    (certs / "ip.txt").write_text(ip)
    return cert, key


class TlsServer(ThreadingHTTPServer):
    """https twin. The TLS handshake runs in the worker thread, so a browser that opens a speculative
    connection and never speaks cannot hold up everyone else (it can when the listening socket is wrapped)."""

    def __init__(self, addr, handler, context):
        super().__init__(addr, handler)
        self.context = context

    def finish_request(self, request, client_address):
        request.settimeout(10)
        try:
            tls = self.context.wrap_socket(request, server_side=True)
        except (ssl.SSLError, OSError) as e:
            reason = getattr(e, "reason", None) or e.__class__.__name__
            print("https: handshake not completed:", reason, flush=True)   # e.g. the browser refused the certificate
            return
        tls.settimeout(None)
        self.RequestHandlerClass(tls, client_address, self)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    seed()
    ip = lan_ip()
    print(f"gildongmu server on :{port}", flush=True)
    print(f"neighbour page for phones on the same Wi-Fi:  http://{ip}:{port}/neighbor", flush=True)
    try:  # https twin: same app, so the browser allows the microphone
        cert, key = _make_cert(ip)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, key)
        secure = TlsServer(("0.0.0.0", port + 363), Handler, ctx)
        threading.Thread(target=secure.serve_forever, daemon=True).start()
        print(f"with microphone (accept the certificate warning once):  https://{ip}:{port + 363}/neighbor", flush=True)
    except (OSError, subprocess.SubprocessError) as e:
        print("https twin not started:", e, flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
