import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "server"))
import shops as sh

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
        self.assertEqual(got[0]["menu"], [{"name": "아메리카노", "price": 4000, "kind": "음료"}])
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
        a = s.order(x["id"], x["menu"][0]["name"], "me", *HERE, key="k1")
        b = s.order(x["id"], x["menu"][0]["name"], "me", *HERE, key="k1")
        self.assertEqual(a, b)
        self.assertEqual(a["price"], x["menu"][0]["price"])
        self.assertTrue(a["receipt"].startswith("D-"))
        self.assertEqual(len(s.orders_for(x["id"])), 1)   # what the shop would see: one order

    def test_an_order_for_an_unknown_item_or_a_far_shop_is_refused(self):
        s = fresh()
        x = s.near(*HERE)["shops"][0]
        with self.assertRaises(sh.ShopError):
            s.order(x["id"], "없는 메뉴", "me", *HERE)
        with self.assertRaises(sh.ShopError):
            s.order("nope", "x", "me", *HERE)
        with self.assertRaises(sh.ShopError):
            s.order(x["id"], x["menu"][0]["name"], "me", HERE[0] + 0.5, HERE[1])   # the car is 55 km away

    def test_orders_and_shops_survive_a_restart(self):
        with tempfile.TemporaryDirectory() as d:
            s = sh.Shops(store=d)
            x = s.near(*HERE)["shops"][0]
            s.order(x["id"], x["menu"][0]["name"], "me", *HERE, key="k")
            s.close()
            s2 = sh.Shops(store=d)
            self.assertEqual(len(s2.near(*HERE)["shops"]), 5)   # not seeded twice
            self.assertEqual(len(s2.orders_for(x["id"])), 1)
            s2.close()


if __name__ == "__main__":
    unittest.main()
