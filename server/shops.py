"""Partner shops and pickup orders: the "가는 길 픽업" data.

A shop registers itself once, as a structure: name, kind of place, position, phone, preparation time and a
menu where every item is one of three kinds of thing the driver can ask for: 음료 (coffee and drinks), 식사 (a meal)
or 간식 (a snack). The same three kinds fit a drive-through, a rest-stop restaurant and a convenience store, so
the driver asks for what they want, not for a kind of place.

The car asks for the shops near its position; the server answers with distance, arrival time and the menu.
An order is recorded for the shop (what its own screen or printer would show). Nothing is charged: the payment
is a mock, and no message is sent to the shop by phone or network. Sample shops are fictional.
"""
import hashlib
import math
import re
import sqlite3
import time
from pathlib import Path

ITEM_KINDS = ("음료", "식사", "간식")
PLACES = ("드라이브스루", "휴게소", "편의점·주유")
RANGE_KM = 5.0
MAX_SHOPS = 50
_PHONE = re.compile(r"^[0-9+\-\s]{7,20}$")

SCHEMA = """
CREATE TABLE IF NOT EXISTS shops (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, brand TEXT NOT NULL DEFAULT '', place TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,
  phone TEXT NOT NULL, wait_min INTEGER NOT NULL, registered_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS menu (
  shop_id TEXT NOT NULL, pos INTEGER NOT NULL, name TEXT NOT NULL, price INTEGER NOT NULL, kind TEXT NOT NULL,
  PRIMARY KEY (shop_id, pos));
CREATE TABLE IF NOT EXISTS orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT, receipt TEXT NOT NULL, key TEXT UNIQUE, shop_id TEXT NOT NULL,
  item TEXT NOT NULL, price INTEGER NOT NULL, vehicle TEXT NOT NULL, eta_min INTEGER NOT NULL,
  cook_in_min INTEGER NOT NULL, created_at REAL NOT NULL, status TEXT NOT NULL);
"""

# sample shops around the emulator's start position (37.41272, 127.09468): (name, place, north km, east km, wait, phone, menu)
_ANCHOR = (37.41272, 127.09468)
_SEED = [
    ("해뜰커피 DT", "드라이브스루", 2.1, 0.3, 3, "031-555-0101", [("아메리카노 1잔", 4500, "음료"), ("크로와상", 3800, "간식")], "해뜰커피"),
    ("해뜰커피 DT 북점", "드라이브스루", 4.0, -0.2, 3, "031-555-0109", [("아메리카노 1잔", 4500, "음료"), ("크로와상", 3800, "간식")], "해뜰커피"),
    ("든든버거 DT", "드라이브스루", 3.0, -0.4, 4, "031-555-0102", [("치즈버거 세트", 7900, "식사"), ("콜라", 2000, "음료")]),
    ("고소베이커리 DT", "드라이브스루", 4.5, 0.2, 2, "031-555-0103", [("소금빵 2개", 5200, "간식"), ("아이스 라떼", 4800, "음료")]),
    ("초록주유소 편의점", "편의점·주유", 3.8, 0.1, 1, "031-555-0104",
     [("생수 500ml", 1000, "음료"), ("샌드위치", 3800, "식사"), ("초코바", 1500, "간식")]),
    ("들꽃쉼터 매점", "휴게소", 7.0, 0.0, 2, "031-555-0105", [("컵라면", 2500, "식사"), ("음료수", 1500, "음료")]),
    ("별빛충전소 편의점", "편의점·주유", 9.0, -0.2, 1, "031-555-0106", [("에너지 음료", 2200, "음료"), ("삼각김밥", 1400, "식사")]),
    ("산들휴게소 푸드코트", "휴게소", 12.0, 0.0, 6, "031-555-0107", [("우동 세트", 7000, "식사"), ("식혜", 2500, "음료")]),
    ("산들휴게소 호두과자", "휴게소", 12.2, 0.0, 3, "031-555-0108", [("호두과자 한 봉지", 4000, "간식")]),
]


class ShopError(Exception):
    pass


def distance_km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(a))


def eta_min(km):
    """About 50 km/h on average, at least one minute."""
    return max(1, int(km * 1.2 + 0.5))


class Shops:
    def __init__(self, store=None, clock=time.time, seed=True):
        self.clock = clock
        path = ":memory:"
        if store is not None:
            Path(store).mkdir(parents=True, exist_ok=True)
            path = str(Path(store) / "shops.db")
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        if store is not None:
            self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)
        if seed and not self.db.execute("SELECT 1 FROM shops LIMIT 1").fetchone():
            self._seed()

    def close(self):
        self.db.close()

    def _seed(self):
        for name, place, north, east, wait, phone, menu, *rest in _SEED:   # rest: the brand, when it differs from the name
            lat = _ANCHOR[0] + north / 111.0
            lon = _ANCHOR[1] + east / (111.0 * math.cos(math.radians(_ANCHOR[0])))
            self.register({"name": name, "brand": rest[0] if rest else name, "place": place, "lat": lat, "lon": lon, "phone": phone, "wait_min": wait,
                           "menu": [{"name": n, "price": p, "kind": k} for n, p, k in menu]})

    # ------------------------------------------------------------ a shop registers itself

    def register(self, data):
        """Add or update a partner shop from its own structure. The id comes from its name and position."""
        try:
            name = str(data["name"]).strip()
            brand = str(data.get("brand") or name).strip()
            place = str(data["place"])
            lat, lon = float(data["lat"]), float(data["lon"])
            phone = str(data["phone"]).strip()
            wait = int(data.get("wait_min", 3))
            menu = data["menu"]
        except (KeyError, TypeError, ValueError):
            raise ShopError("missing or wrong field")
        if not 1 <= len(name) <= 30:
            raise ShopError("name")
        if not 1 <= len(brand) <= 30:
            raise ShopError("brand")
        if place not in PLACES:
            raise ShopError("place")
        if not (math.isfinite(lat) and math.isfinite(lon) and -90 <= lat <= 90 and -180 <= lon <= 180):
            raise ShopError("position")
        if not _PHONE.match(phone):
            raise ShopError("phone")
        if not 0 <= wait <= 60:
            raise ShopError("wait_min")
        if not isinstance(menu, list) or not 1 <= len(menu) <= 12:
            raise ShopError("menu")
        items = []
        for m in menu:
            try:
                iname, price, kind = str(m["name"]).strip(), int(m["price"]), str(m["kind"])
            except (KeyError, TypeError, ValueError):
                raise ShopError("menu item")
            if not 1 <= len(iname) <= 30 or not 0 < price <= 100000 or kind not in ITEM_KINDS:
                raise ShopError("menu item")
            items.append((iname, price, kind))
        sid = hashlib.sha1(f"{name}|{lat:.5f}|{lon:.5f}".encode("utf-8")).hexdigest()[:12]
        with self.db:
            self.db.execute("DELETE FROM menu WHERE shop_id=?", (sid,))
            self.db.execute("INSERT OR REPLACE INTO shops (id, name, brand, place, lat, lon, phone, wait_min, registered_at) VALUES (?,?,?,?,?,?,?,?,?)",
                            (sid, name, brand, place, lat, lon, phone, wait, self.clock()))
            self.db.executemany("INSERT INTO menu VALUES (?,?,?,?,?)", [(sid, i, n, p, k) for i, (n, p, k) in enumerate(items)])
        return sid

    # ------------------------------------------------------------ the car asks

    def _menu(self, sid):
        return [{"name": r["name"], "price": r["price"], "kind": r["kind"]}
                for r in self.db.execute("SELECT * FROM menu WHERE shop_id=? ORDER BY pos", (sid,))]

    def _view(self, r, km):
        eta = eta_min(km)
        return {"id": r["id"], "name": r["name"], "brand": r["brand"], "place": r["place"], "km": round(km, 2), "eta_min": eta,
                "wait_min": r["wait_min"], "cook_in_min": max(0, eta - r["wait_min"]), "phone": r["phone"],
                "menu": self._menu(r["id"])}

    def near(self, lat, lon, radius_km=RANGE_KM):
        """Shops inside the range, nearest first, and the nearest shop of all (to say how far "nothing" is)."""
        rows = self.db.execute("SELECT * FROM shops").fetchall()
        measured = sorted(((distance_km(lat, lon, r["lat"], r["lon"]), r) for r in rows), key=lambda x: x[0])
        inside = [self._view(r, km) for km, r in measured if km <= radius_km][:MAX_SHOPS]
        nearest = None
        if measured:
            km, r = measured[0]
            nearest = {"name": r["name"], "place": r["place"], "km": round(km, 2)}
        return {"range_km": radius_km, "shops": inside, "nearest": nearest}

    # ------------------------------------------------------------ an order (the payment is a mock)

    def order(self, shop_id, item, vehicle, lat, lon, key=None):
        if key:
            old = self.db.execute("SELECT * FROM orders WHERE key=?", (key,)).fetchone()
            if old:   # the same tap again (a slow answer, a retry): the same order
                return self._order_view(old)
        r = self.db.execute("SELECT * FROM shops WHERE id=?", (shop_id,)).fetchone()
        if r is None:
            raise ShopError("unknown shop")
        m = self.db.execute("SELECT * FROM menu WHERE shop_id=? AND name=?", (shop_id, item)).fetchone()
        if m is None:
            raise ShopError("unknown item")
        km = distance_km(lat, lon, r["lat"], r["lon"])
        if km > RANGE_KM:
            raise ShopError("too far")
        eta = eta_min(km)
        now = self.clock()
        with self.db:
            cur = self.db.execute(
                "INSERT INTO orders (receipt, key, shop_id, item, price, vehicle, eta_min, cook_in_min, created_at, status) "
                "VALUES ('', ?,?,?,?,?,?,?,?,?)",
                (key, shop_id, m["name"], m["price"], vehicle, eta, max(0, eta - r["wait_min"]), now, "접수(모의 결제)"))
            self.db.execute("UPDATE orders SET receipt=? WHERE id=?", (f"D-{1000 + cur.lastrowid}", cur.lastrowid))
        return self._order_view(self.db.execute("SELECT * FROM orders WHERE id=?", (cur.lastrowid,)).fetchone())

    def _order_view(self, o):
        r = self.db.execute("SELECT name, phone FROM shops WHERE id=?", (o["shop_id"],)).fetchone()
        return {"ok": True, "receipt": o["receipt"], "shop": r["name"], "item": o["item"], "price": o["price"],
                "eta_min": o["eta_min"], "cook_in_min": o["cook_in_min"], "status": o["status"]}

    def orders_for(self, shop_id, limit=20):
        """What the shop would see on its own screen: the latest orders, with when to start cooking."""
        rows = self.db.execute("SELECT * FROM orders WHERE shop_id=? ORDER BY id DESC LIMIT ?", (shop_id, limit)).fetchall()
        return [{"receipt": o["receipt"], "item": o["item"], "price": o["price"], "eta_min": o["eta_min"],
                 "cook_in_min": o["cook_in_min"], "status": o["status"]} for o in rows]
