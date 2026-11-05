import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "server"))
import shops as sh

TOK = "tok_abcdef12"   # what the payment provider returns after a card is registered
HERE = (37.41272, 127.09468)   # the emulator's start position the sample shops are placed around


def fresh():
    return sh.Shops(seed=True)


def shop(**kw):
    d = {"name": "새 가게", "place": "드라이브스루", "lat": HERE[0] + 0.005, "lon": HERE[1], "phone": "031-555-0199",
         "wait_min": 4, "menu": [{"name": "아메리카노", "price": 4000, "kind": "음료"}]}
    d.update(kw)
    return d


class ShopsTest(unittest.TestCase):
    def test_only_shops_inside_the_range_come_back_nearest_first(self):
        s = fresh()
        out = s.near(*HERE)
        names = [x["name"] for x in out["shops"]]
        self.assertEqual(names, ["해뜰커피 DT", "든든버거 DT", "초록주유소 편의점", "해뜰커피 DT 북점", "고소베이커리 DT"])
        self.assertTrue(all(x["km"] <= sh.RANGE_KM for x in out["shops"]))
        self.assertEqual(out["shops"], sorted(out["shops"], key=lambda x: x["km"]))

    def test_far_away_nothing_is_offered_but_the_nearest_is_named(self):
        s = fresh()
        out = s.near(HERE[0] + 0.5, HERE[1])   # about 55 km away
        self.assertEqual(out["shops"], [])
        self.assertGreater(out["nearest"]["km"], sh.RANGE_KM)

    def test_arrival_and_cook_start_follow_the_distance(self):
        s = fresh()
        x = s.near(*HERE)["shops"][0]
        self.assertEqual(x["eta_min"], sh.eta_min(x["km"]))
        self.assertEqual(x["cook_in_min"], max(0, x["eta_min"] - x["wait_min"]))

    def test_a_shop_registers_itself_and_then_shows_up_when_the_car_is_near(self):
        s = fresh()
        sid = s.register(shop())
        got = [x for x in s.near(*HERE)["shops"] if x["id"] == sid]
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0]["phone"], "031-555-0199")
        self.assertEqual(got[0]["menu"], [{"name": "아메리카노", "price": 4000, "kind": "음료", "simple": True}])
        s.register(shop(menu=[{"name": "라떼", "price": 5000, "kind": "음료"}]))   # same shop again: updated, not doubled
        again = [x for x in s.near(*HERE)["shops"] if x["id"] == sid]
        self.assertEqual([m["name"] for m in again[0]["menu"]], ["라떼"])

    def test_branches_of_one_brand_share_the_brand_so_a_favourite_covers_them_all(self):
        s = fresh()
        brands = [x["brand"] for x in s.near(*HERE)["shops"] if x["name"].startswith("해뜰커피")]
        self.assertEqual(brands, ["해뜰커피", "해뜰커피"])
        sid = s.register(shop(name="무명 가게"))
        self.assertEqual([x["brand"] for x in s.near(*HERE)["shops"] if x["id"] == sid], ["무명 가게"])   # no brand given: the name

    def test_a_bad_registration_is_refused(self):
        s = fresh()
        for bad in (shop(place="미용실"), shop(phone="abc"), shop(lat=99), shop(menu=[]),
                    shop(menu=[{"name": "x", "price": 0, "kind": "음료"}]),
                    shop(menu=[{"name": "x", "price": 1000, "kind": "술"}]), shop(name=""), {"name": "x"}):
            with self.assertRaises(sh.ShopError):
                s.register(bad)

    def test_an_order_takes_the_price_from_the_shop_and_the_same_tap_is_one_order(self):
        s = fresh()
        x = s.near(*HERE)["shops"][0]
        a = s.order(x["id"], x["menu"][0]["name"], "me", *HERE, key="k1", payment_token=TOK)
        b = s.order(x["id"], x["menu"][0]["name"], "me", *HERE, key="k1", payment_token=TOK)
        self.assertEqual(a, b)
        self.assertEqual(a["price"], x["menu"][0]["price"])
        self.assertTrue(a["receipt"].startswith("D-"))
        self.assertEqual(len(s.orders_for(x["id"])), 1)   # what the shop would see: one order

    def test_an_order_without_a_registered_payment_method_is_refused(self):
        s = fresh()
        x = s.near(*HERE)["shops"][0]
        for bad in (None, "", "1234-5678-9012-3456", "tok_x"):   # no token, or something that looks like a card number
            with self.assertRaises(sh.ShopError):
                s.order(x["id"], x["menu"][0]["name"], "me", *HERE, payment_token=bad)
        self.assertEqual(s.orders_for(x["id"]), [])

    def test_the_payment_provider_decides_and_a_decline_makes_no_order(self):
        s = fresh()
        x = s.near(*HERE)["shops"][0]
        with self.assertRaises(sh.ShopError) as cm:
            s.order(x["id"], x["menu"][0]["name"], "me", *HERE, payment_token="tok_declined1")
        self.assertEqual(str(cm.exception), "payment declined")
        self.assertEqual(s.orders_for(x["id"]), [])   # nothing was recorded for the shop

    def test_a_cancelled_order_is_refunded_by_the_provider(self):
        s = fresh()
        _, o = self._placed(s)
        done = s.cancel(o["receipt"])
        self.assertTrue(done["refunded"])
        self.assertEqual(len(s.payments.refunded), 1)
        _, o2 = self._placed(s)
        s.set_status(o2["receipt"], "cancelled")   # the shop declines it
        self.assertEqual(len(s.payments.refunded), 2)

    def test_an_order_for_an_unknown_item_or_a_far_shop_is_refused(self):
        s = fresh()
        x = s.near(*HERE)["shops"][0]
        with self.assertRaises(sh.ShopError):
            s.order(x["id"], "없는 메뉴", "me", *HERE, payment_token=TOK)
        with self.assertRaises(sh.ShopError):
            s.order("nope", "x", "me", *HERE, payment_token=TOK)
        with self.assertRaises(sh.ShopError):
            s.order(x["id"], x["menu"][0]["name"], "me", HERE[0] + 0.5, HERE[1], payment_token=TOK)   # the car is 55 km away

    def _placed(self, s):
        x = s.near(*HERE)["shops"][0]
        return x, s.order(x["id"], x["menu"][0]["name"], "me", *HERE, payment_token=TOK)

    def test_the_shop_moves_an_order_through_its_life_and_the_car_sees_it(self):
        s = fresh()
        x, o = self._placed(s)
        self.assertEqual((o["status"], o["status_label"]), ("placed", "접수됨"))
        self.assertEqual(o["shop_can"], ["accepted", "cancelled"])
        for status in ("accepted", "cooking", "ready", "picked_up"):
            self.assertEqual(s.set_status(o["receipt"], status)["status"], status)
        self.assertEqual(s.track(o["receipt"])["status_label"], "수령 완료")
        self.assertEqual(s.orders_for(x["id"])[0]["status"], "picked_up")

    def test_a_status_cannot_skip_a_step_or_go_back(self):
        s = fresh()
        _, o = self._placed(s)
        with self.assertRaises(sh.ShopError):
            s.set_status(o["receipt"], "ready")   # cooking first
        s.set_status(o["receipt"], "accepted")
        with self.assertRaises(sh.ShopError):
            s.set_status(o["receipt"], "placed")
        with self.assertRaises(sh.ShopError):
            s.set_status("D-0", "accepted")   # unknown order

    def test_the_car_may_cancel_until_the_shop_starts_cooking(self):
        s = fresh()
        _, o = self._placed(s)
        self.assertTrue(o["car_can_cancel"])
        self.assertEqual(s.cancel(o["receipt"])["status"], "cancelled")
        _, o2 = self._placed(s)
        s.set_status(o2["receipt"], "accepted")
        s.set_status(o2["receipt"], "cooking")
        self.assertFalse(s.track(o2["receipt"])["car_can_cancel"])
        with self.assertRaises(sh.ShopError):
            s.cancel(o2["receipt"])

    def test_stuck_in_a_jam_the_arrival_moves_later_and_the_shop_sees_the_delay(self):
        s = fresh()
        x, o = self._placed(s)   # placed from the start position: the shop is about 2 km away
        far = s.track(o["receipt"], HERE[0] - 0.02, HERE[1])   # the car went the other way: about 2 km further
        self.assertGreater(far["eta_min"], o["eta_min"])
        self.assertEqual(far["delay_min"], far["eta_min"] - o["eta_min"])
        self.assertGreaterEqual(far["cook_in_min"], o["cook_in_min"])   # cooking is started later, not earlier
        self.assertEqual(s.orders_for(x["id"])[0]["delay_min"], far["delay_min"])

    def test_a_finished_order_no_longer_moves_with_the_car(self):
        s = fresh()
        _, o = self._placed(s)
        for status in ("accepted", "cooking", "ready"):
            s.set_status(o["receipt"], status)
        s.set_status(o["receipt"], "picked_up")
        done = s.track(o["receipt"], HERE[0] + 0.5, HERE[1])
        self.assertEqual(done["eta_min"], o["eta_min"])

    def test_a_car_sees_its_own_orders_newest_first_with_status_and_refund(self):
        s = fresh()
        x = s.near(*HERE)["shops"][0]
        a = s.order(x["id"], x["menu"][0]["name"], "me", *HERE, payment_token=TOK)
        b = s.order(x["id"], x["menu"][-1]["name"], "me", *HERE, payment_token=TOK)
        s.order(x["id"], x["menu"][0]["name"], "someone-else", *HERE, payment_token=TOK)
        s.cancel(a["receipt"])
        mine = s.history("me")
        self.assertEqual([o["receipt"] for o in mine], [b["receipt"], a["receipt"]])   # not the other car's order
        self.assertEqual((mine[1]["status"], mine[1]["refunded"]), ("cancelled", True))
        self.assertTrue(mine[0]["live"] and not mine[1]["live"])

    def test_orders_and_shops_survive_a_restart(self):
        with tempfile.TemporaryDirectory() as d:
            s = sh.Shops(store=d)
            x = s.near(*HERE)["shops"][0]
            s.order(x["id"], x["menu"][0]["name"], "me", *HERE, key="k", payment_token=TOK)
            s.close()
            s2 = sh.Shops(store=d)
            self.assertEqual(len(s2.near(*HERE)["shops"]), 5)   # not seeded twice
            self.assertEqual(len(s2.orders_for(x["id"])), 1)
            s2.close()


if __name__ == "__main__":
    unittest.main()
