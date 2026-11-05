"""HTTP front for core.py / live.py so the car app can run against a real backend.
    python server/app_server.py [port]          (default 8080; the emulator reaches it at 10.0.2.2)

Car app (JSON):
    GET  /api/nearby?vid=me
    POST /api/pos     {"lat","lon","heading"}                              where this car is (from the navigation)
    GET/POST /api/me/profile {"adjective","animal","color","model","show_model"}   this car's own profile, list-only
    POST /api/send    {"preset" | "spoken","arg","gear","voice","restricted"}   the one question ("왜 막혀?"), by tap or
                      voice; "restricted" = the vehicle forbids touch (all UX restrictions set): only "voice" gets through.
                      Any other sentence said by voice (and confirmed by the driver) goes out as a road message instead:
                      to everyone around, never to one car. Up to 3 answers, all visible to the neighbours.
    POST /api/ask     {"text","to"}                            a spoken question the driver confirmed: to everyone around, or
                      to the one nearby car "to" (that car only; one answer closes it; the same car once a minute)
    POST /api/report  {"msg_id","reason"}                      report a neighbour: blocked, answers no longer shown.
                      "reason" is one of core.REPORT_REASONS (reckless / rude / weird / spam / fake / other), picked by touch
    POST /api/questions/report {"qid","reason"}                report a strange question: whoever asked it is blocked
    POST /api/report_car {"vid","reason"}                      the same, for a car picked on the road
    GET  /api/inbox?vid=me&after=<id>
    POST /api/thanks  {"msg_id"}                               "고마워요" to whoever answered
    GET  /api/ahead?vid=me                                     next rest area + liked restaurant
    GET  /api/situation                                        what neighbours reported about the road ahead
    POST /api/inform  {"kind"}                                 "알리기": tell the road what you see (no question needed)
    GET  /api/hazard/proposal                                  the car thinks it sees roadkill / falling rocks / a pothole
    POST /api/hazard/detected {"kind","confidence","source"}  the entrance for a camera pipeline (nothing is reported yet)
    POST /api/hazard/report {"kind","proposal_id"}             the driver's one tap: a ticket number comes back (접수됨)
    GET  /api/chat?vid=me                                      what others said lately (short spoken messages to everyone)
    POST /api/chat/report {"id"}                               report a message: its sender is off the road
    POST /api/n/say {"vid","text"}                             a phone neighbour says something short to everyone
    POST /api/trip    {"dest"}
    GET  /api/demo/event?kind=rockfall|roadkill|pothole        test trigger for the camera pipeline's entrance
Neighbour phones (open http://<this PC>:8080/neighbor):
    POST /api/n/skip {"vid","qid"}   POST /api/n/pause {"vid","paused"}      decline one question / stop receiving
    GET  /api/n/options                                       the lists a profile is built from
    POST /api/n/join {"adjective","animal","dest","color","show_model","show_dest","show_title"} -> {"vid"}
    POST /api/n/profile (same fields + "vid")                 change it later; nothing typed ever reaches other drivers   GET /api/n/poll?vid=   POST /api/n/answer {"vid","qid","option"|"spoken"}
    Over https (port+363, self-signed) the page can use the phone's microphone.

Everything on the road is real. Each app reports where it is (POST /api/pos from the car, POST /api/n/pos
{"vid","lat","lon","heading"} from a phone); the server measures the distance and who is ahead, and only cars with a
fresh position within RADIUS_M appear. With nobody around, "왜 막혀요?" says so instead of inventing an answer.
"""
import json
import os
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

import board as boardmod
import chat as chatmod
import core
import hazards as hzmod
import live
import questions as qmod

ME = "me"
APP_PRESETS = {"ASK_ACCIDENT"}  # the one question the car asks: why is the road stuck
M_PER_DEG_LAT = 111_000.0
LAT0, LON0 = 36.7500, 127.1500
QUESTION_POINTS = 3         # for answering a neighbour's question
HAZARD_POINTS = 5           # for a confirmed hazard report: the road is safer for everyone behind
POS_FRESH = 30.0           # seconds: a position older than this is not shown (the car left or the phone went to sleep)
RADIUS_M = 300             # who counts as "around"
STATIC = pathlib.Path(__file__).with_name("static")

lock = threading.RLock()  # one shared connection, used from several handler threads
db = None
room = live.LiveRoom()
reports = live.Reports()
DATA_DIR = pathlib.Path(os.environ.get("GILDONGMU_DATA") or pathlib.Path(__file__).with_name("data"))   # tickets + photos survive a restart
PHOTO_DAYS = 30             # photos are the most personal thing kept: deleted after this many days
hazards = hzmod.Hazards(store=DATA_DIR)   # what the car sees on the road, and the reports the driver confirms
hazards.purge_old(PHOTO_DAYS)
road_chat = chatmod.Chat()   # short spoken messages to everyone around, shown beside the sender's car
inbox_q = qmod.Questions()   # what people ask each other: private to one car, or public to all
board = boardmod.Board()   # the road notice board: reports, taps, points
positions = {}   # vid -> {"lat","lon","heading","at"}: what each car's app last told us. Nothing is made up.
me_profile = {"nick": None, "color": live.COLORS[0], "model": "", "show_model": True}   # this car's own profile (from the list-only picker)


def _open():
    global db
    db = sqlite3.connect(":memory:", check_same_thread=False)
    db.row_factory = sqlite3.Row
    db.executescript(core.SCHEMA.read_text(encoding="utf-8"))


def seed():
    _open()
    # our own row exists so messages have somebody to belong to; it has no real position until the app reports one
    core.upsert_vehicle(db, ME, "나", "", LAT0, LON0, heading=0, speed_kmh=0, gear="D", dest=None)
    db.commit()


def report_position(vid, data):
    """A car's app (or a phone) says where it is. Only a real position is kept."""
    place = hzmod.clean_place(data)
    if place is None:
        return False
    positions[vid] = {"lat": place["lat"], "lon": place["lon"], "heading": place.get("heading", 0.0), "at": time.time()}
    return True


def _fresh(vid, now):
    pos = positions.get(vid)
    return pos if pos and now - pos["at"] <= POS_FRESH else None


def _sync():
    """Put every car where its app last said it is. A car with no recent position is not on the road."""
    now = time.time()
    active = {p.vid for p in room.active()}
    mine = _fresh(ME, now)
    if mine:
        core.upsert_vehicle(db, ME, me_profile["nick"] or "나", me_profile["model"] if me_profile["show_model"] else "",
                            mine["lat"], mine["lon"], heading=mine["heading"], speed_kmh=0, gear="D",
                            dest=None, discoverable=True, now=now)
    for p in room.players.values():
        pos = _fresh(p.vid, now)
        if pos:
            core.upsert_vehicle(db, p.vid, p.nick, "차종 비공개", pos["lat"], pos["lon"], heading=pos["heading"],
                                speed_kmh=0, gear="D", dest=p.dest if p.show_dest else None,
                                discoverable=p.vid in active and not p.blocked, now=now)
        else:
            db.execute("UPDATE vehicles SET discoverable=0 WHERE id=?", (p.vid,))
    db.commit()


def _name(vid):
    if vid == "PUBLIC_DATA":
        return "공공데이터"
    row = db.execute("SELECT nickname FROM vehicles WHERE id=?", (vid,)).fetchone()
    return row["nickname"] if row else vid


def _post(from_id, to_id, text, auto=0, tag=""):
    """auto: 2 a real person on a phone (0 and 1 are kept for old rows)."""
    cur = db.execute("INSERT INTO messages(from_id,to_id,preset,arg,auto,created_at) VALUES (?,?,?,?,?,?)",
                     (from_id, to_id, "REPLY_TEXT", f"{text}||||{tag}" if tag else text, auto, time.time()))
    db.commit()
    return cur.lastrowid   # arg = "text||coupon||tag": the tag is the cause the answering car named, shown on the road


def _is_ahead(vid):
    """True when that car is in front of mine (measured from the positions the apps reported)."""
    now = time.time()
    me, other = _fresh(ME, now), _fresh(vid, now)
    if not me or not other:
        return False
    return core.relative(me["lat"], me["lon"], me["heading"], other["lat"], other["lon"])[0] > 0


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
    "ASK_ACCIDENT": {"accident": "앞쪽에 사고 처리 중이에요", "work": "앞쪽에서 공사 중이에요",
                     "debris": "도로에 낙하물이 있어요", "breakdown": "고장 난 차가 서 있어요",
                     "merge": "합류 구간이라 막혀요", "crowd": "그냥 차가 많아요, 곧 풀려요"},
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


def _publish(kind, who, card=True):
    """One report of the road ahead goes to both places that show it: the "앞 상황" card and the notice board
    (a camera hazard report skips the card, as before). Returns the board post id (None when not a board kind)."""
    if card and kind in live.AHEAD_TEXT:
        reports.add("ahead", kind, who)
    try:
        return board.post(kind, who)
    except boardmod.BoardError:
        return None


def _report(preset, option, who):
    """Share what a neighbour told us with every car on the road."""
    if preset == "ASK_ACCIDENT" and option != "unknown":
        _publish(option, who)
        return
    topic = {"ASK_REST": "rest", "ASK_FOOD": "food"}.get(preset)
    if topic and option != "unknown":
        reports.add(topic, option, who)


def _ban(who, reason="inappropriate"):
    """A reported car is blocked and everything it said or posted leaves the road."""
    core.report(db, ME, who, reason)   # also blocks
    reports.remove_by(who)
    board.remove_by(who)
    inbox_q.remove_by(who)
    road_chat.remove_by(who)
    if who in room.players:
        room.block(who)


def _who_name(who):
    if who == ME:
        return "나"
    p = room.players.get(who)
    return p.nick if p else _name(who)


def _board_view(viewer):
    return {"posts": board.feed(viewer, _who_name), "me": board.me(viewer)}


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
                viewer = q.get("vid", ME)
                if not _fresh(viewer, time.time()):
                    return self._json(200, [])   # we do not know where this car is, so nobody is "around" it
                out = core.nearby(db, viewer, radius_m=RADIUS_M)
                for n in out:
                    n["live"] = True   # every neighbour here is a real person using the app
                    if n["live"] and n["id"] in room.players:
                        pl = room.players[n["id"]]
                        n["color"] = pl.color
                        n["title"] = live.title_for(pl.helped) if pl.show_title else ""
                    n["say"] = road_chat.say_of(n["id"])   # what this car said out loud lately (a bubble beside it)
                out.sort(key=lambda n: not n["live"])  # real neighbours first (stable: keeps distance order)
                return self._json(200, out)
            if u.path == "/api/inbox":
                after = int(q.get("after", 0))
                rows = db.execute("SELECT * FROM messages WHERE to_id=? AND id>? ORDER BY id",
                                  (q.get("vid", ME), after)).fetchall()
                out = []
                for r in rows:
                    text, _, rest = core.render(r["preset"], r["arg"]).partition("||")
                    coupon, _, tag = rest.partition("||")
                    out.append({"id": r["id"], "from": _name(r["from_id"]), "text": text, "coupon": coupon, "tag": tag,
                                "auto": r["auto"] == 1, "human": r["auto"] == 2})
                return self._json(200, out)
            if u.path == "/api/hazard/proposal":   # does the car think it sees something? (the driver decides)
                return self._json(200, {"proposal": hazards.proposal()})
            if u.path == "/api/hazard/mine":
                return self._json(200, {"items": hazards.mine(ME)})
            if u.path == "/api/chat":
                return self._json(200, {"items": road_chat.recent(q.get("vid", ME), _who_name)})
            if u.path == "/api/ahead":
                return self._json(200, ahead_info())
            if u.path == "/api/questions":
                return self._json(200, {"items": inbox_q.feed(q.get("vid", ME), _who_name)})
            if u.path == "/api/board":
                return self._json(200, _board_view(q.get("vid", ME)))
            if u.path == "/api/situation":
                info = ahead_info()["rest"]
                return self._json(200, reports.summary(info[0]["name"] if info else None))
            if u.path == "/api/me/profile":
                return self._json(200, {**me_profile, "models": live.MODELS})
            if u.path == "/api/n/options":
                return self._json(200, {"adjectives": live.ADJECTIVES, "animals": live.ANIMALS,
                                        "colors": live.COLORS, "destinations": live.DESTINATIONS})
            if u.path == "/api/n/poll":
                try:
                    polled = room.poll(q.get("vid", ""))
                    polled["reward"] = board.me(q.get("vid", ""))   # the phone shows its own points and grade
                    return self._json(200, polled)
                except live.LiveError as e:
                    return self._json(404, {"error": str(e)})
            if u.path == "/api/demo/event":   # test trigger for the camera pipeline's entrance: "the camera sees X"
                kind = q.get("kind", "")
                if kind in hzmod.KINDS and kind != "debris":
                    hazards.detect(kind, 0.92, "camera-test")
                    return self._json(200, {"ok": True})
                return self._json(400, {"ok": False})
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

            if u.path == "/api/me/profile":   # the driver's own profile, picked from lists: what other cars see
                new = live.clean_profile(data)
                if new["nick"] is None:
                    new["nick"] = me_profile["nick"]   # no valid name sent: keep the one we have
                me_profile.update(new)
                return self._json(200, {**me_profile, "models": live.MODELS})

            if u.path == "/api/pos":   # the car's app: where we are (from the navigation)
                return self._json(200, {"ok": report_position(ME, data)})

            if u.path == "/api/n/pos":   # a phone neighbour: where it is (from the phone's GPS)
                vid = data.get("vid", "")
                if vid not in room.players:
                    return self._json(404, {"error": "unknown neighbour"})
                room.touch(vid)
                return self._json(200, {"ok": report_position(vid, data)})

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
                if not option and data.get("text"):   # a spoken reply the neighbour confirmed on the phone
                    option = "t:" + str(data["text"])
                if not option and data.get("spoken"):  # said out loud: map the words to one of the fixed answers
                    q = room.questions.get(int(data.get("qid", 0)))
                    option = (live.match_answer(q["preset"], data["spoken"]) if q else None) or ""
                    print("voice answer from a phone:", "understood" if option else "not understood", flush=True)  # never the words
                    if not option:
                        return self._json(422, {"error": "no match"})
                asked = room.questions.get(int(data.get("qid", 0)))
                before = list(asked["answers"]) if asked else []
                try:
                    preset, option = room.answer(data.get("vid", ""), int(data.get("qid", 0)), option)
                except (live.LiveError, ValueError) as e:
                    return self._json(409, {"error": str(e)})
                _post(data["vid"], ME, compose_answer(preset, option), auto=2,
                      tag=option if preset == "ASK_ACCIDENT" and option != "unknown" else "")
                _report(preset, option, data["vid"])
                # rewards: a small one for answering, a bonus for being first, one for agreeing with another car
                gained = board.reward_answer(data["vid"], first=not before, ahead=_is_ahead(data["vid"]))
                same = [v for v, o in before if o == option and option != "unknown"]
                if same:
                    board.reward_agree(same + [data["vid"]])
                    gained += boardmod.REWARD["agree"]
                return self._json(200, {"ok": True, "gained": gained, "reward": board.me(data["vid"])})

            if u.path == "/api/inform":   # the driver tells the road what they see, without being asked
                kind = data.get("kind")
                if kind not in live.AHEAD_TEXT and kind not in boardmod.KINDS:
                    return self._json(400, {"error": "unknown report"})
                return self._json(200, {"ok": True, "post": _publish(kind, ME)})

            if u.path == "/api/questions/answer":   # the car answers: a quick reply or its confirmed spoken sentence
                try:
                    text = inbox_q.answer(int(data.get("qid", 0)), ME, str(data.get("reply", "")))
                except (qmod.QuestionError, ValueError) as e:
                    return self._json(409, {"error": str(e)})
                board.award(ME, QUESTION_POINTS)
                return self._json(200, {"ok": True, "text": text, "items": inbox_q.feed(ME, _who_name),
                                        "me": board.me(ME)})

            if u.path == "/api/n/ask":   # a phone neighbour asks everyone around
                vid = data.get("vid", "")
                if vid not in room.players or room.players[vid].blocked:
                    return self._json(404, {"error": "unknown neighbour"})
                try:
                    qid = inbox_q.ask(vid, str(data.get("text", "")))
                except qmod.QuestionError as e:
                    return self._json(422, {"error": str(e)})
                return self._json(200, {"ok": True, "qid": qid})

            if u.path in ("/api/board/react", "/api/n/react"):   # the car or a phone neighbour taps one of three
                voter = data.get("vid", ME) if u.path == "/api/n/react" else ME
                try:
                    board.react(int(data.get("post", 0)), voter, data.get("what"))
                except (boardmod.BoardError, ValueError) as e:
                    return self._json(409, {"error": str(e)})
                return self._json(200, {"ok": True, **_board_view(voter)})

            if u.path == "/api/n/say":   # a phone neighbour says something short to everyone around
                vid = data.get("vid", "")
                if vid not in room.players or room.players[vid].blocked:
                    return self._json(404, {"error": "unknown neighbour"})
                try:
                    road_chat.post(vid, str(data.get("text", "")))
                except chatmod.ChatError as e:
                    return self._json(422 if str(e) == "bad text" else 429, {"error": str(e)})
                return self._json(200, {"ok": True})

            if u.path == "/api/hazard/detected":   # the entrance for a camera pipeline: "I think there is X ahead"
                try:
                    pid = hazards.detect(str(data.get("kind", "")), float(data.get("confidence", 1.0)), str(data.get("source", "camera")))
                except (hzmod.HazardError, ValueError) as e:
                    return self._json(422, {"error": str(e)})
                return self._json(200, {"ok": True, "proposal": pid})

            if u.path == "/api/hazard/decline":
                return self._json(200, {"ok": hazards.decline(int(data.get("proposal_id", 0)))})

            if u.path == "/api/hazard/report":   # one tap: stored with a ticket number, and shown to the neighbours
                kind = str(data.get("kind", ""))
                before = len(hazards.mine(ME))
                try:
                    key = str(data["key"])[:64] if data.get("key") else None   # the car's id for this report: a retry is not a second report
                    ticket = hazards.report(kind, ME, proposal_id=data.get("proposal_id"), place=data.get("place"), photo=data.get("photo"),
                                            key=key, event_at=data.get("event_at"))
                except hzmod.HazardError as e:
                    return self._json(422 if str(e) == "unknown hazard" else 429, {"error": str(e)})
                if len(hazards.mine(ME)) > before:   # a new ticket, not the same report again
                    board.award(ME, HAZARD_POINTS)
                    _publish(kind, ME, card=False)
                return self._json(200, {"ok": True, "ticket": ticket["ticket"], "title": hzmod.KINDS[kind][0],
                                        "status": ticket["status"], "located": ticket["place"] is not None, "photo": ticket["photo"] is not None, "me": board.me(ME)})

            if u.path == "/api/chat/report":   # the car reports a message: its sender is off the road
                reason = core.clean_reason(data.get("reason"))
                if reason is None:
                    return self._json(422, {"error": "unknown reason"})
                who = road_chat.sender_of(int(data.get("id", 0)))
                if not who:
                    return self._json(404, {"error": "gone"})
                _ban(who, reason)
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
                reason = core.clean_reason(data.get("reason"))
                if reason is None:
                    return self._json(422, {"error": "unknown reason"})
                _ban(row["from_id"], reason)
                return self._json(200, {"ok": True})

            if u.path == "/api/questions/report":   # a strange question: its asker is reported, so blocked
                reason = core.clean_reason(data.get("reason"))
                if reason is None:
                    return self._json(422, {"error": "unknown reason"})
                who = inbox_q.sender_of(int(data.get("qid", 0)))
                if not who or who == ME:
                    return self._json(404, {"error": "gone"})
                _ban(who, reason)
                return self._json(200, {"ok": True})

            if u.path == "/api/report_car":   # a car picked on the road: reported with a reason, so blocked
                reason = core.clean_reason(data.get("reason"))
                if reason is None:
                    return self._json(422, {"error": "unknown reason"})
                target = str(data.get("vid", ""))
                near = core.nearby(db, ME, radius_m=RADIUS_M) if _fresh(ME, time.time()) else []
                if target not in {n["id"] for n in near}:
                    return self._json(404, {"error": "그 차는 이제 근처에 없어요"})
                _ban(target, reason)
                return self._json(200, {"ok": True})

            if u.path == "/api/ask":   # a spoken question the driver confirmed: to everyone, or to one picked car
                to = data.get("to") or None
                near = core.nearby(db, ME, radius_m=RADIUS_M) if _fresh(ME, time.time()) else []
                if not near:
                    return self._json(409, {"error": "주변에 앱을 쓰는 이웃이 없어요"})
                if to is not None and to not in {n["id"] for n in near}:
                    return self._json(404, {"error": "그 차는 이제 근처에 없어요"})
                try:
                    qid = inbox_q.ask(ME, str(data.get("text", "")), to=to)
                except qmod.QuestionError as e:
                    msg = {"bad text": "그 문장은 보낼 수 없어요. 더 짧게 다시 말해 주세요",
                           "too soon": "같은 차에는 1분 뒤에 다시 물을 수 있어요",
                           "too many": "잠시 뒤에 다시 물어봐 주세요"}.get(str(e), "보낼 수 없어요")
                    return self._json(422 if str(e) == "bad text" else 429, {"error": msg})
                return self._json(200, {"ok": True, "qid": qid})

            if u.path == "/api/thanks":
                msg_id = int(data.get("msg_id", 0))
                row = db.execute("SELECT from_id FROM messages WHERE id=?", (msg_id,)).fetchone()
                helped = 0
                if row and row["from_id"].startswith("live-"):   # only a real person earns points
                    helped = board.reward_helped(ME, row["from_id"], key=msg_id, ahead=_is_ahead(row["from_id"]))
                return self._json(200, {"ok": True, "live": bool(row and room.thank(row["from_id"])), "points": helped})

            if u.path != "/api/send":
                return self._json(404, {"error": "not found"})
            preset = data.get("preset") or live.match_question(data.get("spoken"))
            if data.get("spoken"):
                print("voice question from the car:", "understood" if preset in APP_PRESETS else "not understood", flush=True)
            if preset not in APP_PRESETS and data.get("spoken") and data.get("voice"):
                # not the question: a short sentence the driver confirmed. It goes to everyone around, as a bubble.
                try:
                    road_chat.post(ME, data["spoken"])
                except chatmod.ChatError as e:
                    if str(e) == "bad text":
                        return self._json(422, {"error": "그 문장은 보낼 수 없어요. 더 짧게 다시 말해 주세요"})
                    return self._json(429, {"error": "잠시 뒤에 다시 말해 주세요"})
                return self._json(200, {"ok": True, "say": True, "preset": ""})
            if preset not in APP_PRESETS:
                return self._json(422 if data.get("spoken") else 400, {"error": "unknown preset"})
            # The vehicle decides. When it restricts everything ("restricted"), only a spoken question gets through;
            # otherwise a one-tap preset is fine at any speed (there is no typing, only big one-tap choices).
            if data.get("restricted") and not data.get("voice"):
                return self._json(403, {"error": "차량이 지금은 터치를 제한하고 있어요. 말로 물어보세요"})
            db.execute("UPDATE vehicles SET speed_kmh=?, gear=? WHERE id=?", (0.0, data.get("gear", "D"), ME))
            db.commit()
            near = core.nearby(db, ME, radius_m=RADIUS_M) if _fresh(ME, time.time()) else []
            if not near:
                return self._json(409, {"error": "주변에 앱을 쓰는 이웃이 없어요"})
            try:
                out = core.send(db, ME, near[0]["id"], preset, data.get("arg"))
            except core.ChatError as e:
                return self._json(403, {"error": str(e)})
            qid = room.ask(preset, only={n["id"] for n in near})   # everyone within range who can answer right now
            if qid is None:
                return self._json(409, {"error": "지금 답할 수 있는 이웃이 없어요"})
            out["waiting_human"] = True
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
