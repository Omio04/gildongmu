import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "server"))
import core
import simulator

NOW = 1000.0


def fresh():
    db = core.connect()
    simulator.seed(db, now=NOW)
    return db


class Tests(unittest.TestCase):
    def test_nearby_hides_coordinates_and_filters_radius(self):
        db = fresh()
        res = core.nearby(db, "car00", 300, now=NOW)
        self.assertTrue(res)
        self.assertNotIn("lat", res[0])
        self.assertTrue(all(r["distance_m"] <= 310 for r in res))
        self.assertEqual(core.nearby(db, "car00", 1, now=NOW), [])

    def test_stale_and_hidden_vehicles_excluded(self):
        db = fresh()
        n = len(core.nearby(db, "car00", 300, now=NOW))
        self.assertEqual(len(core.nearby(db, "car00", 300, now=NOW + 999)), 0)
        db.execute("UPDATE vehicles SET discoverable=0 WHERE id='car01'")
        self.assertEqual(len(core.nearby(db, "car00", 300, now=NOW)), n - 1)

    def test_cannot_send_while_driving(self):
        db = fresh()
        db.execute("UPDATE vehicles SET speed_kmh=90 WHERE id='car00'")
        with self.assertRaises(core.ChatError):
            core.send(db, "car00", "car01", "ASK_FOOD", now=NOW)
        db.execute("UPDATE vehicles SET gear='P' WHERE id='car00'")
        self.assertIn("text", core.send(db, "car00", "car01", "ASK_FOOD", now=NOW))

    def test_send_and_inbox(self):
        db = fresh()
        core.send(db, "car00", "car01", "HELLO", arg="대구", now=NOW)
        self.assertEqual(core.inbox(db, "car01")[0]["text"], "안녕하세요! 대구 가는 중이에요")

    def test_block_and_report(self):
        db = fresh()
        core.report(db, "car01", "car00", "spam", now=NOW)
        with self.assertRaises(core.ChatError):
            core.send(db, "car00", "car01", "ASK_FOOD", now=NOW)
        self.assertNotIn("car00", [v["id"] for v in core.nearby(db, "car01", 300, now=NOW)])

    def test_free_text_and_missing_arg_rejected(self):
        db = fresh()
        with self.assertRaises(core.ChatError):
            core.send(db, "car00", "car01", "아무말", now=NOW)
        with self.assertRaises(core.ChatError):
            core.send(db, "car00", "car01", "HELLO", now=NOW)

    def test_public_data_fallback_only_ahead(self):
        db = fresh()
        text = core.auto_answer(db, "car00", "ASK_REST", now=NOW)
        self.assertIn("천안삼거리휴게소", text)  # nearest ahead, southbound
        db.execute("UPDATE vehicles SET heading=0 WHERE id='car00'")  # now northbound
        self.assertIsNone(core.auto_answer(db, "car00", "ASK_REST", now=NOW))

    def test_like_once_per_vehicle(self):
        db = fresh()
        pid = db.execute("SELECT id FROM places WHERE kind='restaurant'").fetchone()["id"]
        core.like_place(db, "car00", pid)
        core.like_place(db, "car00", pid)
        core.like_place(db, "car01", pid)
        self.assertEqual(db.execute("SELECT likes FROM places WHERE id=?", (pid,)).fetchone()[0], 5)
        with self.assertRaises(core.ChatError):
            core.like_place(db, "stranger", pid)


class RelativeTest(unittest.TestCase):
    def test_a_car_north_of_me_is_ahead_when_i_drive_north(self):
        ahead, lateral = core.relative(36.75, 127.15, 0, 36.75 + 100 / 111_000, 127.15)
        self.assertAlmostEqual(ahead, 100, delta=1)
        self.assertAlmostEqual(lateral, 0, delta=1)

    def test_the_same_car_is_behind_me_when_i_drive_south(self):
        ahead, _ = core.relative(36.75, 127.15, 180, 36.75 + 100 / 111_000, 127.15)
        self.assertAlmostEqual(ahead, -100, delta=1)

    def test_right_and_left_follow_my_heading(self):
        east = 127.15 + 10 / (111_000 * 0.8014)   # about 10 m east at this latitude
        _, lateral_n = core.relative(36.75, 127.15, 0, 36.75, east)      # driving north: east is my right
        _, lateral_s = core.relative(36.75, 127.15, 180, 36.75, east)    # driving south: east is my left
        self.assertGreater(lateral_n, 8)
        self.assertLess(lateral_s, -8)

    def test_nearby_reports_where_each_car_is_without_coordinates(self):
        db = core.connect()
        core.upsert_vehicle(db, "me", "나", "", 36.75, 127.15, heading=180, now=NOW)
        core.upsert_vehicle(db, "a", "앞차", "", 36.75 - 60 / 111_000, 127.15, heading=180, now=NOW)
        res = core.nearby(db, "me", 300, now=NOW)[0]
        self.assertEqual(res["ahead_m"], 60)
        self.assertNotIn("lat", res)

    def test_a_report_reason_is_one_of_the_fixed_ones(self):
        for code in core.REPORT_REASONS:
            self.assertEqual(core.clean_reason(code), code)
        self.assertEqual(core.clean_reason(None), "other")    # an old client that sends no reason
        self.assertEqual(core.clean_reason(""), "other")
        self.assertIsNone(core.clean_reason("아무 말이나"))      # nothing typed ever becomes a reason
        self.assertIsNone(core.clean_reason("DROP TABLE reports"))

    def test_grid_search_finds_exactly_the_cars_a_full_scan_finds(self):
        """The cell index is only a speed-up: for many random cars, and radii from a street to a few cells, the
        answer must equal checking every single car (cars land on, and across, cell borders)."""
        import math
        import random
        rnd = random.Random(7)
        for lat0 in (33.5, 36.75, 38.2):   # the south coast, the middle, the north of Korea
            db = core.connect()
            m_lon = 111_000 * math.cos(math.radians(lat0))
            for i in range(400):   # 2 km x 2 km: dense enough that every radius finds a crowd and many sit near borders
                core.upsert_vehicle(db, f"c{i}", f"n{i}", "", lat0 + rnd.uniform(0, 2000) / 111_000,
                                    127.15 + rnd.uniform(0, 2000) / m_lon, now=NOW)
            for me in ("c0", "c1", "c2", "c3", "c4"):
                row = db.execute("SELECT lat, lon FROM vehicles WHERE id=?", (me,)).fetchone()
                for radius in (100, 300, 500, 700, 1500):
                    want = {r["id"] for r in db.execute("SELECT id, lat, lon FROM vehicles WHERE id!=?", (me,))
                            if core.haversine_m(row["lat"], row["lon"], r["lat"], r["lon"]) <= radius}
                    got = {c["id"] for c in core.nearby(db, me, radius, now=NOW)}
                    self.assertEqual(got, want, f"lat {lat0} {me} radius {radius}")


if __name__ == "__main__":
    unittest.main()
