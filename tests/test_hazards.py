import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "server"))
import hazards as hz


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def make():
    c = Clock()
    return hz.Hazards(clock=c), c


class HazardsTest(unittest.TestCase):
    def test_a_detection_becomes_a_proposal_not_a_report(self):
        h, _ = make()
        pid = h.detect("roadkill", 0.9)
        p = h.proposal()
        self.assertEqual(p["id"], pid)
        self.assertEqual(p["title"], "로드킬")
        self.assertEqual(h.mine("me"), [])   # nothing reported until the driver says so

    def test_a_weak_detection_does_not_bother_the_driver(self):
        h, _ = make()
        self.assertIsNone(h.detect("rockfall", 0.3))
        self.assertIsNone(h.proposal())

    def test_unknown_hazards_are_refused(self):
        h, _ = make()
        with self.assertRaises(hz.HazardError):
            h.detect("ufo", 1.0)
        with self.assertRaises(hz.HazardError):
            h.report("ufo", "me")

    def test_one_tap_gives_a_ticket_and_closes_the_proposal(self):
        h, _ = make()
        pid = h.detect("roadkill", 0.9)
        t = h.report("roadkill", "me", proposal_id=pid)
        self.assertEqual(t["ticket"], "R-0001")
        self.assertEqual(t["status"], "접수됨")   # honest: received here, not "sent to the authority"
        self.assertIsNone(h.proposal())
        self.assertEqual(h.mine("me")[0]["title"], "로드킬")

    def test_the_same_hazard_twice_is_one_ticket(self):
        h, c = make()
        a = h.report("debris", "me")
        c.t += 30
        self.assertEqual(h.report("debris", "me")["ticket"], a["ticket"])
        c.t += hz.SAME_KIND_SECONDS + 1
        self.assertNotEqual(h.report("debris", "me")["ticket"], a["ticket"])

    def test_an_unanswered_proposal_goes_away_by_itself(self):
        h, c = make()
        h.detect("rockfall", 0.8)
        c.t += hz.PROPOSAL_SECONDS + 1
        self.assertIsNone(h.proposal())

    def test_saying_no_means_it_is_not_asked_again_at_once(self):
        h, c = make()
        pid = h.detect("pothole", 0.9)
        self.assertTrue(h.decline(pid))
        self.assertIsNone(h.proposal())
        self.assertIsNone(h.detect("pothole", 0.95))   # the camera still sees it: no second nag
        c.t += hz.SAME_KIND_SECONDS + 1
        self.assertIsNotNone(h.detect("pothole", 0.95))

    def test_a_hazard_already_reported_is_not_proposed_again(self):
        h, _ = make()
        h.report("roadkill", "me")
        self.assertIsNone(h.detect("roadkill", 0.99))

    def test_the_same_detection_twice_is_one_proposal(self):
        h, _ = make()
        a = h.detect("rockfall", 0.8)
        self.assertEqual(h.detect("rockfall", 0.9), a)

    def test_one_car_cannot_flood_the_intake(self):
        h, c = make()
        kinds = ["roadkill", "rockfall", "debris", "pothole"]
        n = 0
        for i in range(hz.MAX_PER_HOUR):
            c.t += hz.SAME_KIND_SECONDS + 1
            h.report(kinds[i % 4], "me")
            n += 1
        c.t += hz.SAME_KIND_SECONDS + 1
        with self.assertRaises(hz.HazardError):
            h.report("roadkill", "me")


class PlaceTest(unittest.TestCase):
    def test_a_report_keeps_where_it_was_seen(self):
        h, _ = make()
        t = h.report("roadkill", "me", place={"lat": 36.75012, "lon": 127.15, "heading": 181, "address": "경부고속도로 천안 부근"})
        self.assertEqual(t["place"]["lat"], 36.75012)
        self.assertEqual(h.mine("me")[0]["where"], "경부고속도로 천안 부근")

    def test_a_report_without_a_real_position_is_flagged(self):
        h, _ = make()
        h.report("roadkill", "me")
        self.assertEqual(h.mine("me")[0]["where"], "위치 없음")
        for bad in ({"lat": 0, "lon": 0}, {"lat": 120, "lon": 10}, {"lat": "x", "lon": 1}, {}):
            self.assertIsNone(hz.clean_place(bad), bad)


class PhotoTest(unittest.TestCase):
    JPEG = __import__("base64").b64encode(b"\xff\xd8\xff\xe0" + b"x" * 100).decode()

    def test_a_report_can_carry_a_photo(self):
        h, _ = make()
        t = h.report("rockfall", "me", photo=self.JPEG)
        self.assertIsNotNone(t["photo"])
        self.assertTrue(h.mine("me")[0]["photo"])

    def test_no_photo_is_fine(self):
        h, _ = make()
        h.report("rockfall", "me")
        self.assertFalse(h.mine("me")[0]["photo"])

    def test_things_that_are_not_a_small_jpeg_are_dropped_not_stored(self):
        import base64
        for bad in ("not base64!!", base64.b64encode(b"GIF89a....").decode(), base64.b64encode(b"\xff\xd8\xff" + b"x" * 500_000).decode(), "", None, 12):
            self.assertIsNone(hz.clean_photo(bad), str(bad)[:20])


class DurableTest(unittest.TestCase):
    """Tickets survive a restart when there is a store folder; a retry is one report; the times are honest."""
    JPEG = PhotoTest.JPEG

    def setUp(self):
        import tempfile
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def open(self, clock=None):
        c = clock or Clock()
        h = hz.Hazards(clock=c, store=self.dir.name)
        self.addCleanup(h.close)   # runs before the folder is removed: an open file cannot be deleted on Windows
        return h, c

    def test_a_ticket_is_still_there_after_a_restart(self):
        h, c = self.open()
        t = h.report("rockfall", "me", place={"lat": 36.75, "lon": 127.15, "heading": 90, "address": "경부선"}, photo=self.JPEG)
        h.close()
        h2, _ = self.open(c)   # a new process, the same folder
        [mine] = h2.mine("me")
        self.assertEqual((mine["ticket"], mine["where"], mine["photo"]), (t["ticket"], "경부선", True))
        n = h2.report("pothole", "me")   # numbering carries on, never reusing a number
        self.assertEqual(n["ticket"], "R-0002")

    def test_the_photo_is_a_file_not_a_blob_in_memory(self):
        h, _ = self.open()
        t = h.report("rockfall", "me", photo=self.JPEG)
        from pathlib import Path
        self.assertTrue(Path(t["photo"]).exists())
        self.assertEqual(Path(t["photo"]).read_bytes()[:3], b"\xff\xd8\xff")

    def test_sending_the_same_report_again_is_one_report(self):
        h, c = self.open()
        a = h.report("debris", "me", key="abc-123")
        c.t += 10_000   # long after the 5-minute window: only the key can tell it is a retry
        b = h.report("debris", "me", key="abc-123")
        self.assertEqual(a["ticket"], b["ticket"])
        self.assertEqual(len(h.mine("me")), 1)

    def test_when_the_car_saw_it_and_when_we_got_it_are_two_times(self):
        h, c = self.open()
        t = h.report("debris", "me", event_at=c.t - 40)
        self.assertEqual((t["event_at"], t["at"]), (c.t - 40, c.t))
        for silly in (c.t + 100_000, c.t - 100_000, "yesterday", None):   # not believable: use the time we got it
            c.t += 10_000
            self.assertEqual(h.report("rockfall", "me", event_at=silly)["event_at"], c.t)

    def test_old_photos_are_deleted_but_the_ticket_stays(self):
        h, c = self.open()
        t = h.report("rockfall", "me", photo=self.JPEG)
        from pathlib import Path
        c.t += 31 * 86400
        self.assertEqual(h.purge_old(30), 1)
        self.assertFalse(Path(t["photo"]).exists())
        self.assertEqual(len(h.mine("me")), 1)
        self.assertFalse(h.mine("me")[0]["photo"])


if __name__ == "__main__":
    unittest.main()
