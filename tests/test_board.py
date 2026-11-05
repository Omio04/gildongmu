import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "server"))
import board


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def make():
    c = Clock()
    return board.Board(clock=c), c


class BoardTest(unittest.TestCase):
    def test_a_report_is_posted_and_listed(self):
        b, _ = make()
        pid = b.post("accident", "me")
        f = b.feed("other")
        self.assertEqual([x["id"] for x in f], [pid])
        self.assertTrue(f[0]["urgent"])
        self.assertFalse(f[0]["mine"])

    def test_road_surface_dangers_are_urgent_and_shown_before_good_news(self):
        b, c = make()
        b.post("clearing", "a")
        c.t += 1
        b.post("ice", "b")
        c.t += 1
        b.post("flood", "c")
        f = b.feed("me")
        self.assertEqual([x["kind"] for x in f], ["flood", "ice", "clearing"])
        self.assertTrue(f[0]["urgent"] and f[1]["urgent"] and not f[2]["urgent"])

    def test_unknown_report_is_refused(self):
        b, _ = make()
        with self.assertRaises(board.BoardError):
            b.post("free text", "me")

    def test_each_tap_pays_the_author_by_what_it_says(self):
        b, _ = make()
        pid = b.post("accident", "me")
        b.react(pid, "a", "confirm")
        b.react(pid, "b", "thanks")
        b.react(pid, "c", "passed")
        self.assertEqual(b.me("me")["points"], board.POINTS["confirm"] + board.POINTS["thanks"] + board.POINTS["passed"])
        self.assertEqual(b.me("a")["points"], board.POINTS_HELPER)   # checking the road earns a little too
        self.assertEqual(b.feed("me")[0]["votes"], {"confirm": 1, "thanks": 1, "passed": 1})

    def test_one_tap_per_car_and_never_on_your_own_report(self):
        b, _ = make()
        pid = b.post("work", "me")
        with self.assertRaises(board.BoardError):
            b.react(pid, "me", "confirm")
        b.react(pid, "a", "confirm")
        with self.assertRaises(board.BoardError):
            b.react(pid, "a", "passed")   # already answered: no farming
        self.assertEqual(b.feed("a")[0]["my_vote"], "confirm")
        self.assertEqual(b.me("me")["points"], board.POINTS["confirm"])

    def test_unknown_reaction_is_refused(self):
        b, _ = make()
        pid = b.post("work", "me")
        with self.assertRaises(board.BoardError):
            b.react(pid, "a", "like")

    def test_repeating_the_same_report_does_not_make_a_second_post(self):
        b, c = make()
        first = b.post("accident", "me")
        c.t += 30
        self.assertEqual(b.post("accident", "me"), first)
        c.t += board.SAME_KIND_SECONDS + 1
        self.assertNotEqual(b.post("accident", "me"), first)

    def test_old_reports_disappear(self):
        b, c = make()
        b.post("accident", "me")
        c.t += board.POST_SECONDS + 1
        self.assertEqual(b.feed("x"), [])

    def test_urgent_reports_come_first_and_only_three_show(self):
        b, c = make()
        b.post("clearing", "a")
        c.t += 1
        b.post("work", "b")
        c.t += 1
        b.post("debris", "c")
        c.t += 1
        b.post("accident", "d")
        kinds = [x["kind"] for x in b.feed("v")]
        self.assertEqual(len(kinds), 3)
        self.assertEqual(kinds[:2], ["accident", "debris"])

    def test_a_blocked_neighbours_reports_are_removed(self):
        b, _ = make()
        b.post("accident", "bad")
        b.remove_by("bad")
        self.assertEqual(b.feed("v"), [])

    def test_grade_rises_with_points(self):
        self.assertEqual(board.grade_for(0), ("길 위 새싹", 30))
        self.assertEqual(board.grade_for(30), ("길잡이", 100))
        self.assertEqual(board.grade_for(1000), ("도로 영웅", None))

class RewardTest(unittest.TestCase):
    def test_first_answer_gets_a_bonus_and_plain_answers_are_capped(self):
        b, _ = make()
        self.assertEqual(b.reward_answer("a", first=True), board.REWARD["answer"] + board.REWARD["first"])
        self.assertEqual(b.reward_answer("b"), board.REWARD["answer"])
        for _ in range(100):
            b.reward_answer("farm")
        self.assertEqual(b.me("farm")["points"], board.BASE_ANSWER_CAP)

    def test_a_car_ahead_of_the_asker_earns_more(self):
        b, _ = make()
        self.assertGreater(b.reward_answer("ahead", ahead=True, first=True), b.reward_answer("behind", first=True))

    def test_agreement_pays_each_car_once(self):
        b, _ = make()
        b.reward_agree(["a", "b", "a"])
        self.assertEqual(b.me("a")["points"], board.REWARD["agree"])
        self.assertEqual(b.me("b")["points"], board.REWARD["agree"])

    def test_the_askers_thanks_is_the_big_reward_and_works_once_per_answer(self):
        b, _ = make()
        self.assertEqual(b.reward_helped("me", "a", key=1), board.REWARD["helped"])
        self.assertEqual(b.reward_helped("me", "a", key=1), 0)   # the same answer, thanked twice
        self.assertEqual(b.me("a")["points"], board.REWARD["helped"])

    def test_the_same_pair_rewarding_each_other_is_worth_less_and_then_nothing(self):
        b, _ = make()
        got = [b.reward_helped("me", "friend", key=k) for k in range(4)]
        self.assertEqual(got, [10, 5, 0, 0])

    def test_nobody_can_thank_themselves(self):
        b, _ = make()
        self.assertEqual(b.reward_helped("me", "me", key=1), 0)


if __name__ == "__main__":
    unittest.main()
