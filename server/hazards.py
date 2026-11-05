"""Road hazards the car sees (roadkill, falling rocks, objects on the road, potholes) and the report that follows.

The flow:
  1. A detection arrives ([Hazards.detect]): from the car's own camera pipeline, or any other source that can say
     "I think there is X ahead". It becomes a PROPOSAL for the driver: nothing is reported yet.
  2. The driver taps once ([Hazards.report]) and gets a ticket number back. The report is stored, and the same
     hazard is posted for the neighbours.
  3. The driver can ignore the proposal; it goes away by itself.

What this module does NOT do, on purpose: recognise anything in a picture, or send anything to a road authority.
Both need things this prototype does not have (access to the vehicle's cameras, and an agreement and API with
the authority). [Hazards.detect] is the place where a camera pipeline plugs in; [Hazards.tickets] is what an
authority hand-over would read. Until then a ticket's status is honest: 접수됨 (received by this service).
No Pleos or database dependency; app_server.py wires it up.
"""
import base64
import binascii
import sqlite3
import threading
import time
from pathlib import Path

# kind -> (what the driver reads, who normally handles it: shown as information, never as "sent to")
KINDS = {
    "roadkill": ("로드킬", "도로 관리 기관"),
    "rockfall": ("낙석", "도로 관리 기관"),
    "debris": ("낙하물", "도로 관리 기관"),
    "pothole": ("포트홀", "도로 관리 기관"),
}

PROPOSAL_SECONDS = 20        # a proposal that is not answered goes away by itself
SAME_KIND_SECONDS = 5 * 60   # the same hazard from the same car is one report, not five
MAX_PER_HOUR = 6             # one car cannot flood the intake
MAX_PHOTO_BYTES = 400_000    # one small JPEG; the app shrinks it before sending
MIN_CONFIDENCE = 0.6         # below this the camera is not sure enough to bother the driver


def clean_photo(b64):
    """A base64 JPEG of a sensible size, or None. Only the JPEG signature is checked: this is a size and type gate,
    not an image-safety scan."""
    if not b64 or not isinstance(b64, str):
        return None
    try:
        raw = base64.b64decode(b64, validate=True)
    except (binascii.Error, ValueError):
        return None
    if len(raw) > MAX_PHOTO_BYTES or raw[:3] != b"\xff\xd8\xff":
        return None
    return raw


def clean_place(place):
    """Keep only a real position (latitude -90..90, longitude -180..180) and a short address; otherwise None."""
    try:
        lat, lon = float(place["lat"]), float(place["lon"])
    except (TypeError, KeyError, ValueError):
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180) or (lat == 0 and lon == 0):
        return None
    out = {"lat": round(lat, 5), "lon": round(lon, 5)}
    try:
        out["heading"] = round(float(place.get("heading", 0)), 0)
    except (TypeError, ValueError):
        pass
    addr = " ".join(str(place.get("address") or "").split())[:80]
    if addr:
        out["address"] = addr
    return out


class HazardError(Exception):
    pass


SCHEMA = """
CREATE TABLE IF NOT EXISTS tickets (
    ticket       TEXT PRIMARY KEY,
    seq          INTEGER NOT NULL,
    kind         TEXT NOT NULL,
    who          TEXT NOT NULL,
    source       TEXT NOT NULL,
    received_at  REAL NOT NULL,       -- when this service got it (the server's clock)
    event_at     REAL NOT NULL,       -- when the driver saw it (the car's clock, if sane)
    status       TEXT NOT NULL,
    lat REAL, lon REAL, heading REAL, address TEXT,
    photo_file   TEXT,                -- the JPEG lives in a file next to the database, not inside it
    idem_key     TEXT UNIQUE          -- the car's own id for this report: sending it twice is one report
)"""


class Hazards:
    """Proposals live in memory (they last 20 seconds). Tickets are kept for good when [store] is a folder:
    one SQLite file (WAL: a crash cannot leave half a ticket) plus a photos/ folder. Without [store] everything is
    in memory, which is what the tests use."""

    def __init__(self, clock=time.time, store=None):
        self.clock = clock
        self.lock = threading.RLock()
        self.proposals = []   # dicts: id, kind, confidence, at, state (open / reported / declined)
        self.tickets = []     # dicts: ticket, kind, who, source, at (received), event_at, status, place, photo, key
        self._pid = 0
        self._tid = 0
        self.declined = {}    # kind -> when the driver last said no (do not ask again at once)
        self.store = Path(store) if store else None
        self.db = None
        if self.store:
            (self.store / "photos").mkdir(parents=True, exist_ok=True)
            self.db = sqlite3.connect(str(self.store / "tickets.db"), check_same_thread=False)
            self.db.execute("PRAGMA journal_mode=WAL")
            self.db.execute("PRAGMA synchronous=NORMAL")
            self.db.execute(SCHEMA)
            self._load()

    def close(self):
        """Close the store (the server does this on shutdown; a file cannot be removed while it is open)."""
        with self.lock:
            if self.db:
                self.db.close()
                self.db = None

    # ---- the store
    def _load(self):
        for r in self.db.execute("SELECT ticket, seq, kind, who, source, received_at, event_at, status, lat, lon, heading,"
                                 " address, photo_file, idem_key FROM tickets ORDER BY seq"):
            place = None
            if r[8] is not None and r[9] is not None:
                place = {"lat": r[8], "lon": r[9]}
                if r[10] is not None:
                    place["heading"] = r[10]
                if r[11]:
                    place["address"] = r[11]
            self.tickets.append({"ticket": r[0], "kind": r[2], "who": r[3], "source": r[4], "at": r[5], "event_at": r[6],
                                 "status": r[7], "place": place, "photo": r[12], "key": r[13]})
            self._tid = max(self._tid, r[1])

    def _save(self, t):
        """Write one ticket through to disk. The photo moves out of memory into its own file."""
        if not self.db:
            return
        if isinstance(t["photo"], bytes):
            path = self.store / "photos" / f"{t['ticket']}.jpg"
            path.write_bytes(t["photo"])
            t["photo"] = str(path)
        p = t["place"] or {}
        with self.db:
            self.db.execute("INSERT INTO tickets VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            (t["ticket"], int(t["ticket"].split("-")[1]), t["kind"], t["who"], t["source"], t["at"],
                             t["event_at"], t["status"], p.get("lat"), p.get("lon"), p.get("heading"), p.get("address"),
                             t["photo"], t["key"]))

    def purge_old(self, days=30):
        """Photos are the most personal thing kept: delete those older than [days]. The ticket itself stays."""
        if not self.db:
            return 0
        cutoff = self._now() - days * 86400
        n = 0
        with self.lock:
            for t in self.tickets:
                if t["at"] < cutoff and isinstance(t["photo"], str):
                    try:
                        Path(t["photo"]).unlink()
                    except OSError:
                        pass
                    t["photo"] = None
                    with self.db:
                        self.db.execute("UPDATE tickets SET photo_file=NULL WHERE ticket=?", (t["ticket"],))
                    n += 1
        return n

    def _event_time(self, event_at, now):
        """When the car says it saw the hazard, if that is believable (within the last hour, not in the future)."""
        try:
            e = float(event_at)
        except (TypeError, ValueError):
            return now
        return e if now - 3600 <= e <= now + 60 else now

    def _now(self):
        return self.clock()

    def detect(self, kind, confidence=1.0, source="camera"):
        """A detection arrives. Returns the proposal id, or None when it is not worth the driver's attention."""
        if kind not in KINDS:
            raise HazardError("unknown hazard")
        if confidence < MIN_CONFIDENCE:
            return None
        with self.lock:
            now = self._now()
            if now - self.declined.get(kind, -1e9) < SAME_KIND_SECONDS:
                return None   # the driver just said no to this
            for p in self.proposals:
                if p["kind"] == kind and p["state"] == "open" and now - p["at"] <= PROPOSAL_SECONDS:
                    return p["id"]   # already asking about it
            if any(t["kind"] == kind and now - t["at"] <= SAME_KIND_SECONDS for t in self.tickets):
                return None   # already reported a moment ago
            self._pid += 1
            self.proposals.append({"id": self._pid, "kind": kind, "confidence": round(float(confidence), 2),
                                   "at": now, "state": "open", "source": source})
            return self._pid

    def proposal(self):
        """The one open proposal, or None."""
        with self.lock:
            now = self._now()
            for p in reversed(self.proposals):
                if p["state"] == "open" and now - p["at"] <= PROPOSAL_SECONDS:
                    return {"id": p["id"], "kind": p["kind"], "title": KINDS[p["kind"]][0],
                            "confidence": p["confidence"], "left": max(0, int(PROPOSAL_SECONDS - (now - p["at"])))}
            return None

    def decline(self, proposal_id):
        with self.lock:
            for p in self.proposals:
                if p["id"] == proposal_id and p["state"] == "open":
                    p["state"] = "declined"
                    self.declined[p["kind"]] = self._now()
                    return True
            return False

    def report(self, kind, who, proposal_id=None, source="driver", place=None, photo=None, key=None, event_at=None):
        """The driver reports (usually by accepting a proposal). Returns the ticket.
        [place] = {"lat","lon","heading","address"} from the car's navigation: where it was seen. A report without a
        place is still stored, but flagged: an authority cannot act on "somewhere on the road".
        [key] is the car's own id for this report: a retry after a slow answer comes back with the same ticket.
        [event_at] is when the car saw it (epoch seconds); the ticket also records when this service received it."""
        if kind not in KINDS:
            raise HazardError("unknown hazard")
        with self.lock:
            now = self._now()
            if key:
                for t in self.tickets:
                    if t["key"] == key:
                        return dict(t)   # the same report sent again (a retry): the same ticket, whatever the time
            for t in self.tickets:
                if t["who"] == who and t["kind"] == kind and now - t["at"] <= SAME_KIND_SECONDS:
                    return dict(t)   # the same report again: the same ticket
            if sum(1 for t in self.tickets if t["who"] == who and now - t["at"] <= 3600) >= MAX_PER_HOUR:
                raise HazardError("too many")
            if proposal_id is not None:
                for p in self.proposals:
                    if p["id"] == proposal_id and p["state"] == "open":
                        p["state"] = "reported"
                        source = p.get("source", "camera")
            self._tid += 1
            ticket = {"ticket": f"R-{self._tid:04d}", "kind": kind, "who": who, "source": source,
                      "at": now, "event_at": self._event_time(event_at, now), "status": "접수됨",
                      "place": clean_place(place), "photo": clean_photo(photo), "key": key or None}
            self._save(ticket)   # on disk before the driver hears "접수됨"
            self.tickets.append(ticket)
            return dict(ticket)

    def mine(self, who):
        with self.lock:
            return [{"ticket": t["ticket"], "title": KINDS[t["kind"]][0], "status": t["status"],
                     "where": (t["place"] or {}).get("address") or ("위치 확인됨" if t["place"] else "위치 없음"),
                     "photo": t["photo"] is not None}
                    for t in self.tickets if t["who"] == who]
