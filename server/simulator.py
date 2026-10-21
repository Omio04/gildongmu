"""Virtual vehicles on a southbound expressway stretch, so the app can be
demoed without real Pleos vehicles."""
import random
import sys

from core import connect, upsert_vehicle

MODELS = ["아이오닉 5", "그랜저", "쏘나타", "EV6", "K5", "투싼"]
NICKS = ["귀성길곰", "느긋한토끼", "졸린사자", "달리는펭귄", "고향행여우", "휴게소요정"]


def seed(db, n=12, jam=True, seed_=1, now=None):
    rnd = random.Random(seed_)
    # heading ~180 (south); in a jam cars sit roughly 15-40 m apart
    lat, lon = 36.80, 127.15
    for i in range(n):
        lat -= rnd.uniform(0.00015, 0.00035)
        upsert_vehicle(db, f"car{i:02d}", rnd.choice(NICKS) + str(i), rnd.choice(MODELS),
                       lat, lon + rnd.uniform(-0.00005, 0.00005),
                       heading=rnd.uniform(170, 190),
                       speed_kmh=rnd.uniform(0, 4) if jam else rnd.uniform(80, 100),
                       gear="D", dest=rnd.choice(["부산", "대구", "대구", "광주"]), now=now)
    db.executemany("INSERT INTO places(kind,name,lat,lon,congestion,likes) VALUES (?,?,?,?,?,?)", [
        ("restarea", "천안삼거리휴게소", lat - 0.05, lon, 1, 0),
        ("restarea", "안성휴게소", lat - 0.20, lon, 2, 0),
        ("restaurant", "호두과자 맛집", lat - 0.06, lon, None, 3),
    ])
    db.commit()


if __name__ == "__main__":
    db = connect(sys.argv[1] if len(sys.argv) > 1 else "demo.db")
    seed(db)
    print("seeded demo db")
