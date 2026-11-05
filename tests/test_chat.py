import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "server"))
import chat as cm


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def make():
    c = Clock()
    return cm.Chat(clock=c), c


class ChatTest(unittest.TestCase):
    def test_a_message_shows_beside_its_car_for_a_while_and_then_goes(self):
        c, clk = make()
        c.post("a", "앞에 사슴이 죽어 있어요")
        self.assertEqual(c.say_of("a"), "앞에 사슴이 죽어 있어요")
        self.assertIsNone(c.say_of("b"))
        clk.t += cm.BUBBLE_SECONDS + 1
        self.assertIsNone(c.say_of("a"))

    def test_everyone_else_sees_it_in_the_list_but_not_the_sender(self):
        c, _ = make()
        c.post("a", "5중 추돌사고 났네요")
        self.assertEqual([m["text"] for m in c.recent("b")], ["5중 추돌사고 났네요"])
        self.assertEqual(c.recent("a"), [])

    def test_newest_first_and_only_a_few(self):
        c, clk = make()
        for i, who in enumerate("abcde"):
            c.post(who, f"메시지 {i}")
            clk.t += 1
        got = [m["text"] for m in c.recent("z")]
        self.assertEqual(len(got), cm.LIST_N)
        self.assertEqual(got[0], "메시지 4")

    def test_links_numbers_empty_and_long_text_are_refused(self):
        c, _ = make()
        for bad in ("", "  ", "www.a.com 보세요", "01012345678", "가" * 61):
            with self.assertRaises(cm.ChatError):
                c.post("a", bad)

    def test_one_car_cannot_flood_the_road(self):
        c, clk = make()
        c.post("a", "첫 번째")
        with self.assertRaises(cm.ChatError):
            c.post("a", "너무 빨리")
        clk.t += cm.MIN_GAP_SECONDS
        c.post("a", "두 번째")
        for i in range(cm.MAX_PER_MINUTE):
            clk.t += cm.MIN_GAP_SECONDS
            try:
                c.post("a", f"계속 {i}")
            except cm.ChatError:
                break
        else:
            self.fail("the per-minute cap never kicked in")

    def test_saying_the_same_thing_again_keeps_the_first(self):
        c, clk = make()
        first = c.post("a", "감사합니다")
        clk.t += 10
        self.assertEqual(c.post("a", "감사합니다"), first)
        self.assertEqual(len(c.recent("b")), 1)

    def test_a_reported_sender_disappears_from_the_road(self):
        c, _ = make()
        mid = c.post("bad", "이상한 말")
        self.assertEqual(c.sender_of(mid), "bad")
        c.remove_by("bad")
        self.assertEqual(c.recent("z"), [])
        self.assertIsNone(c.say_of("bad"))

    def test_old_messages_leave_the_list(self):
        c, clk = make()
        c.post("a", "오래된 말")
        clk.t += cm.RECENT_SECONDS + 1
        self.assertEqual(c.recent("b"), [])


if __name__ == "__main__":
    unittest.main()
