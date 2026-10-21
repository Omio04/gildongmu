"""Core logic for the in-car neighbour chat app. No Pleos SDK dependency:
the vehicle layer only has to call upsert_vehicle() with signal values."""
import math
import sqlite3
import time
from pathlib import Path

SCHEMA = Path(__file__).with_name("schema.sql")

# One-touch presets only; no free text, so nothing to type while driving.
PRESETS = {
    "HELLO": "안녕하세요! {arg} 가는 중이에요",
    "ASK_FOOD": "근처 맛집 있나요?",
    "ASK_REST": "다음 휴게소 혼잡한가요?",
    "ASK_ACCIDENT": "앞에 사고인가요?",
    "REPLY_OK": "괜찮아요",
    "REPLY_BUSY": "혼잡해요",
    "REPLY_PLACE": "{arg} 추천해요",
    "THANKS": "감사합니다",
    # server/simulator-side only: composed from data, never typed by a driver
    "REPLY_TEXT": "{arg}",
}
REPLIES_WITH_ARG = {"HELLO", "REPLY_PLACE", "REPLY_TEXT"}
SEND_MAX_SPEED_KMH = 5.0  # sending is only allowed when (almost) stopped
STALE_SECONDS = 120
CONGESTION_LABEL = ("여유", "보통", "혼잡")


class ChatError(Exception):
    pass


def connect(path=":memory:"):
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    db.executescript(SCHEMA.read_text(encoding="utf-8"))
    return db


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def angle_diff(a, b):
    return abs((a - b + 180) % 360 - 180)


def upsert_vehicle(db, vid, nickname, model, lat, lon, heading=0, speed_kmh=0,
                   gear="D", dest=None, discoverable=True, now=None):
    db.execute(
        """INSERT INTO vehicles VALUES (?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(id) DO UPDATE SET nickname=excluded.nickname, lat=excluded.lat,
           lon=excluded.lon, heading=excluded.heading, speed_kmh=excluded.speed_kmh,
           gear=excluded.gear, dest=excluded.dest, discoverable=excluded.discoverable,
           updated_at=excluded.updated_at""",
        (vid, nickname, model, lat, lon, heading, speed_kmh, gear, dest,
         int(discoverable), now if now is not None else time.time()))
    db.commit()


def _vehicle(db, vid):
    row = db.execute("SELECT * FROM vehicles WHERE id=?", (vid,)).fetchone()
    if not row:
        raise ChatError(f"unknown vehicle {vid}")
    return row


def is_blocked(db, a, b):
    """True if either side blocked the other."""
    return db.execute(
        "SELECT 1 FROM blocks WHERE (blocker_id=? AND blocked_id=?) OR (blocker_id=? AND blocked_id=?)",
        (a, b, b, a)).fetchone() is not None


def nearby(db, vid, radius_m=300, same_direction=False, now=None):
    """Discoverable, fresh, non-blocked vehicles around vid. Exposes nickname, model,
    destination and rough distance only, never raw coordinates."""
    now = now if now is not None else time.time()
    me = _vehicle(db, vid)
    out = []
    for v in db.execute("SELECT * FROM vehicles WHERE id!=? AND discoverable=1", (vid,)):
        if now - v["updated_at"] > STALE_SECONDS or is_blocked(db, vid, v["id"]):
            continue
        d = haversine_m(me["lat"], me["lon"], v["lat"], v["lon"])
        if d > radius_m:
            continue
        same_dir = angle_diff(me["heading"], v["heading"]) <= 45
        if same_direction and not same_dir:
            continue
        out.append({"id": v["id"], "nickname": v["nickname"], "model": v["model"],
                    "dest": v["dest"], "distance_m": round(d, -1), "same_direction": same_dir,
                    "same_dest": bool(me["dest"] and me["dest"] == v["dest"])})
    # same destination first, then closest
    out.sort(key=lambda x: (not x["same_dest"], x["distance_m"]))
    return out


def render(preset, arg=None):
    if preset not in PRESETS:
        raise ChatError(f"unknown preset {preset}")
    if preset in REPLIES_WITH_ARG and not arg:
        raise ChatError(f"{preset} needs an argument")
    return PRESETS[preset].format(arg=arg or "")


def send(db, from_id, to_id, preset, arg=None, radius_m=300, now=None):
    """Safety rule: the sender must be (nearly) stopped or in P."""
    now = now if now is not None else time.time()
    me = _vehicle(db, from_id)
    if me["gear"] != "P" and me["speed_kmh"] > SEND_MAX_SPEED_KMH:
        raise ChatError("driving: sending is disabled")
    if is_blocked(db, from_id, to_id):
        raise ChatError("blocked")
    if to_id not in {v["id"] for v in nearby(db, from_id, radius_m, now=now)}:
        raise ChatError("target out of range")
    text = render(preset, arg)
    cur = db.execute("INSERT INTO messages(from_id,to_id,preset,arg,created_at) VALUES (?,?,?,?,?)",
                     (from_id, to_id, preset, arg, now))
    db.commit()
    return {"id": cur.lastrowid, "text": text}


def inbox(db, vid):
    rows = db.execute("SELECT * FROM messages WHERE to_id=? ORDER BY id", (vid,)).fetchall()
    return [{"id": r["id"], "from": r["from_id"], "text": render(r["preset"], r["arg"]),
             "auto": bool(r["auto"])} for r in rows]


def block(db, blocker, blocked):
    db.execute("INSERT OR IGNORE INTO blocks VALUES (?,?)", (blocker, blocked))
    db.commit()


def report(db, reporter, target, reason, now=None):
    db.execute("INSERT INTO reports(reporter_id,target_id,reason,created_at) VALUES (?,?,?,?)",
               (reporter, target, reason, now if now is not None else time.time()))
    block(db, reporter, target)  # reporting also blocks


def like_place(db, vid, place_id):
    """Only a registered vehicle can like, once per place (anti ad-spam)."""
    _vehicle(db, vid)
    cur = db.execute("INSERT OR IGNORE INTO place_likes VALUES (?,?)", (place_id, vid))
    if cur.rowcount:
        db.execute("UPDATE places SET likes=likes+1 WHERE id=?", (place_id,))
    db.commit()


def places_ahead(db, vid, kind, radius_m=30000, limit=3):
    """Public-data fallback: places in the travel direction, most liked first."""
    me = _vehicle(db, vid)
    out = []
    for p in db.execute("SELECT * FROM places WHERE kind=?", (kind,)):
        d = haversine_m(me["lat"], me["lon"], p["lat"], p["lon"])
        if d > radius_m:
            continue
        bearing = math.degrees(math.atan2(
            math.radians(p["lon"] - me["lon"]) * math.cos(math.radians(me["lat"])),
            math.radians(p["lat"] - me["lat"]))) % 360
        if angle_diff(bearing, me["heading"]) > 60:
            continue
        out.append({"id": p["id"], "name": p["name"], "distance_m": round(d, -2),
                    "congestion": p["congestion"], "likes": p["likes"]})
    out.sort(key=lambda x: (-x["likes"], x["distance_m"]))
    return out[:limit]


def auto_answer(db, asker_id, preset, now=None):
    """No neighbour replied? Answer the question from public data."""
    now = now if now is not None else time.time()
    if preset == "ASK_REST":
        items = places_ahead(db, asker_id, "restarea", limit=1)
        if not items:
            return None
        arg = f"{items[0]['name']}({CONGESTION_LABEL[items[0]['congestion'] or 0]})"
    elif preset == "ASK_FOOD":
        items = places_ahead(db, asker_id, "restaurant", limit=1)
        if not items:
            return None
        arg = items[0]["name"]
    else:
        return None
    db.execute("INSERT INTO messages(from_id,to_id,preset,arg,auto,created_at) VALUES (?,?,?,?,1,?)",
               ("PUBLIC_DATA", asker_id, "REPLY_PLACE", arg, now))
    db.commit()
    return render("REPLY_PLACE", arg)
