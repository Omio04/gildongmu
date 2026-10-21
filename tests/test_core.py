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


if __name__ == "__main__":
    unittest.main()
