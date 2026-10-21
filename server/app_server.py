"""HTTP front for core.py so the car app can run against a real backend.
    python server/app_server.py [port]          (default 8080; emulator reaches it at 10.0.2.2)

Endpoints (JSON):
    GET  /api/nearby?vid=me
    POST /api/send    {"from","to","preset","arg","speed_kmh","gear"}
    GET  /api/inbox?vid=me&after=<id>
    GET  /api/ahead?vid=me                      next rest area + liked restaurant
    GET  /api/demo/event?kind=accident|rest     a neighbour speaks up unprompted (demo/video)
Neighbours here are simulated vehicles; replies are composed from the place data.
"""
import json
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import core

ME = "me"
APP_PRESETS = {"HELLO", "ASK_FOOD", "ASK_REST", "ASK_ACCIDENT"}  # what a driver may send
M_PER_DEG_LAT = 111_000.0
LAT0, LON0 = 36.7500, 127.1500

# (id, nickname, model, dest, metres ahead (+) / behind (-))
NEIGHBOURS = [
    ("car03", "귀성길곰", "아이오닉 5", "대구", 40),
    ("car07", "느긋한토끼", "그랜저", "대구", 90),
    ("car01", "졸린사자", "쏘나타", "부산", -60),
    ("car05", "달리는펭귄", "EV6", "광주", 120),
]

lock = threading.RLock()  # one shared connection, used from several handler threads
db = None


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


def _touch():
    db.execute("UPDATE vehicles SET updated_at=?", (time.time(),))
    db.commit()


def _name(vid):
    if vid == "PUBLIC_DATA":
        return "공공데이터"
    row = db.execute("SELECT nickname FROM vehicles WHERE id=?", (vid,)).fetchone()
    return row["nickname"] if row else vid


def _post(from_id, to_id, text, auto=0):
    db.execute("INSERT INTO messages(from_id,to_id,preset,arg,auto,created_at) VALUES (?,?,?,?,?,?)",
               (from_id, to_id, "REPLY_TEXT", text, auto, time.time()))
    db.commit()


def ahead_info():
    rest = core.places_ahead(db, ME, "restarea", limit=3)
    food = core.places_ahead(db, ME, "restaurant", limit=1)
    return {
        "rest": [{"name": r["name"], "km": round(r["distance_m"] / 1000, 1),
                  "congestion": core.CONGESTION_LABEL[r["congestion"] or 0]} for r in rest],
        "food": [{"name": f["name"], "km": round(f["distance_m"] / 1000, 1), "likes": f["likes"]} for f in food],
    }


def _respond(to_vid, preset, arg, delay=1.2):
    """Simulate the neighbour / fallback answering after a short delay."""
    def work():
        with lock:
            near = core.nearby(db, ME)
            if preset == "HELLO":
                mates = [n for n in near if n["same_dest"]]
                if mates:
                    _post(mates[0]["id"], ME, f"반가워요! 저도 {arg} 가요")
                    return
            if preset == "ASK_REST":
                info = ahead_info()["rest"]
                if near and info:
                    word = {"여유": "여유로워요", "보통": "보통이에요", "혼잡": "혼잡해요"}
                    text = ", ".join(f"{r['name']}는 {word[r['congestion']]}" for r in info[:2])
                    _post(near[0]["id"], ME, text)
                    return
            if preset == "ASK_ACCIDENT" and near:
                _post(near[0]["id"], ME, "앞쪽 사고 처리 중이에요, 곧 풀려요")
                return
            if preset == "ASK_FOOD":  # nobody answers -> public-data fallback
                food = ahead_info()["food"]
                if food:
                    _post("PUBLIC_DATA", ME, f"{food[0]['name']} 추천해요||방문 인증 시 10% 할인", auto=1)
                    return
            _post("PUBLIC_DATA", ME, "지금은 알려 드릴 정보가 없어요", auto=1)
    threading.Timer(delay, work).start()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        with lock:
            _touch()
            if u.path == "/api/nearby":
                return self._json(200, core.nearby(db, q.get("vid", ME)))
            if u.path == "/api/inbox":
                after = int(q.get("after", 0))
                rows = db.execute("SELECT * FROM messages WHERE to_id=? AND id>? ORDER BY id",
                                  (q.get("vid", ME), after)).fetchall()
                out = []
                for r in rows:
                    text, _, coupon = core.render(r["preset"], r["arg"]).partition("||")
                    out.append({"id": r["id"], "from": _name(r["from_id"]), "text": text,
                                "coupon": coupon, "auto": bool(r["auto"])})
                return self._json(200, out)
            if u.path == "/api/ahead":
                return self._json(200, ahead_info())
            if u.path == "/api/demo/event":
                kind = q.get("kind", "accident")
                near = core.nearby(db, ME)
                if not near:
                    return self._json(200, {"ok": False})
                text = {"accident": "앞쪽 사고 처리 중이에요, 곧 풀려요",
                        "rest": "천안삼거리휴게소는 지금 한산해요"}.get(kind, "안전운전하세요")
                _post(near[0]["id"], ME, text)
                return self._json(200, {"ok": True})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)
        n = int(self.headers.get("Content-Length", 0))
        data = json.loads(self.rfile.read(n) or b"{}")
        if u.path == "/api/trip":
            with lock:
                db.execute("UPDATE vehicles SET dest=? WHERE id=?", (data.get("dest"), ME))
                db.commit()
            return self._json(200, {"ok": True})
        if u.path != "/api/send":
            return self._json(404, {"error": "not found"})
        with lock:
            _touch()
            preset = data.get("preset")
            if preset not in APP_PRESETS:
                return self._json(400, {"error": "unknown preset"})
            db.execute("UPDATE vehicles SET speed_kmh=?, gear=? WHERE id=?",
                       (float(data.get("speed_kmh", 0)), data.get("gear", "D"), ME))
            db.commit()
            near = core.nearby(db, ME)
            if not near:
                return self._json(409, {"error": "no neighbours"})
            try:
                out = core.send(db, ME, data.get("to") or near[0]["id"], preset, data.get("arg"))
            except core.ChatError as e:
                return self._json(403, {"error": str(e)})
            _respond(ME, preset, data.get("arg"))
            return self._json(200, out)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    seed()
    print(f"gildongmu server on :{port}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
