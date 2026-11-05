import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "server"))
import live


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def room():
    c = Clock()
    return live.LiveRoom(clock=c), c


class LiveRoomTest(unittest.TestCase):
    def test_nobody_around_means_no_question(self):
        r, _ = room()
        self.assertIsNone(r.ask("ASK_REST"))

    def test_question_to_everyone_collects_answers_everyone_can_see(self):
        r, _ = room()
        a, b = r.join("귀성길", "곰"), r.join("느긋한", "토끼")
        qid = r.ask("ASK_REST")
        self.assertEqual(len(r.poll(a)["pending"]), 1)
        self.assertEqual(r.answer(a, qid, "busy"), ("ASK_REST", "busy"))
        seen = r.poll(b)["pending"][0]
        self.assertEqual(seen["answers"], [{"nick": "귀성길곰", "label": "혼잡해요"}])   # b sees what a said
        self.assertEqual(r.poll(a)["pending"], [])                                   # a already answered
        r.answer(b, qid, "normal")
        self.assertTrue(r.is_answered(qid))

    def test_a_question_to_everyone_closes_after_three_answers(self):
        r, _ = room()
        vids = [r.join(a, "곰") for a in ("귀성길", "느긋한", "졸린", "든든한")]
        qid = r.ask("ASK_FOOD")
        for v in vids[:3]:
            r.answer(v, qid, "walnut")
        with self.assertRaises(live.LiveError):
            r.answer(vids[3], qid, "udon")

    def test_unknown_option_is_rejected(self):
        r, _ = room()
        a = r.join("귀성길", "곰")
        qid = r.ask("ASK_ACCIDENT")
        with self.assertRaises(live.LiveError):
            r.answer(a, qid, "anything typed by a driver")

    def test_phone_that_went_away_is_not_asked(self):
        r, c = room()
        r.join("귀성길", "곰")
        c.t += live.ACTIVE_SECONDS + 1
        self.assertIsNone(r.ask("ASK_FOOD"))

    def test_old_question_disappears(self):
        r, c = room()
        a = r.join("귀성길", "곰")
        r.ask("ASK_REST")
        c.t += live.QUESTION_SECONDS + 1
        self.assertEqual(r.poll(a)["pending"], [])

    def test_helping_and_thanks_build_the_title(self):
        r, _ = room()
        a = r.join("귀성길", "곰")
        for _ in range(3):
            qid = r.ask("HELLO")
            r.answer(a, qid, "hi")
        r.thank(a)
        me = r.poll(a)
        self.assertEqual(me["me"]["helped"], 3)
        self.assertEqual(me["me"]["title"], "길 안내 단골")
        self.assertEqual(me["thanks_new"], 1)
        self.assertEqual(r.poll(a)["thanks_new"], 0)  # the toast shows only once

    def test_nickname_only_comes_from_the_lists(self):
        r, _ = room()
        v = r.join("아무말", "아무거나")
        self.assertIn(r.players[v].nick, live.NICKS)


class ReportsTest(unittest.TestCase):
    def test_reports_are_shared_and_counted(self):
        c = Clock()
        r = live.Reports(clock=c)
        r.add("ahead", "accident", "a")
        r.add("ahead", "accident", "b")
        r.add("ahead", "jam", "c")
        c.t += 130
        out = r.summary("천안삼거리휴게소")
        self.assertEqual(out[0]["title"], "앞쪽 사고 처리 중")
        self.assertIn("이웃 2명", out[0]["detail"])
        self.assertIn("2분 전", out[0]["detail"])

    def test_rest_and_food_lines(self):
        r = live.Reports(clock=Clock())
        r.add("rest", "busy", "a")
        r.add("food", "walnut", "a")
        titles = [i["title"] for i in r.summary("안성휴게소")]
        self.assertIn("안성휴게소 혼잡해요", titles)
        self.assertIn("호두과자 맛집이 좋대요", titles)

    def test_old_reports_expire(self):
        c = Clock()
        r = live.Reports(clock=c)
        r.add("ahead", "work", "a")
        c.t += live.REPORT_SECONDS + 1
        self.assertEqual(r.summary(), [])

    def test_unknown_answers_are_ignored_by_the_summary(self):
        r = live.Reports(clock=Clock())
        r.add("ahead", "unknown", "a")
        self.assertEqual(r.summary(), [])


class BlockAndVoiceTest(unittest.TestCase):
    def test_reported_neighbour_gets_no_more_questions(self):
        r, _ = room()
        a, b = r.join("귀성길", "곰"), r.join("느긋한", "토끼")
        r.block(a)
        qid = r.ask("ASK_REST")
        self.assertEqual(r.poll(a)["pending"], [])
        self.assertTrue(r.poll(a)["blocked"])
        self.assertEqual(len(r.poll(b)["pending"]), 1)
        self.assertIsNotNone(qid)

    def test_reports_of_a_blocked_neighbour_disappear(self):
        rep = live.Reports(clock=Clock())
        rep.add("ahead", "accident", "live-1")
        rep.add("ahead", "work", "live-2")
        rep.remove_by("live-1")
        self.assertEqual(rep.summary()[0]["title"], "앞쪽 공사 중")

    def test_spoken_answers_map_to_fixed_options(self):
        self.assertEqual(live.match_answer("ASK_REST", "지금 좀 혼잡해요"), "busy")
        self.assertEqual(live.match_answer("ASK_REST", "한산한 편이에요"), "free")
        self.assertEqual(live.match_answer("ASK_ACCIDENT", "앞에 사고 났어요"), "accident")
        self.assertEqual(live.match_answer("ASK_ACCIDENT", "공사 중이래요"), "work")
        self.assertIsNone(live.match_answer("ASK_REST", "배가 고프네요"))

    def test_spoken_questions_map_to_presets(self):
        self.assertEqual(live.match_question("왜 막혀요?"), "ASK_ACCIDENT")
        self.assertEqual(live.match_question("앞에 무슨 일이야"), "ASK_ACCIDENT")
        self.assertEqual(live.match_question("왜 이렇게 막히지"), "ASK_ACCIDENT")
        self.assertIsNone(live.match_question("노래 틀어 줘"))

    def test_news_is_not_mistaken_for_the_question(self):
        # these are road messages, not "why is it stuck": they must go out as messages
        for news in ("앞에 사슴이 죽어 있어요", "5중 추돌사고 났네요", "끼워 줘서 감사합니다", "안녕하세요 대구 가는 중이에요", "지금 막혀요"):
            self.assertIsNone(live.match_question(news), news)

    def test_a_misheard_question_still_gets_through_but_a_cut_off_news_item_does_not(self):
        self.assertEqual(live.match_question("왜 막혀"), "ASK_ACCIDENT")
        self.assertEqual(live.match_question("왜막혀요"), "ASK_ACCIDENT")          # the recogniser drops spaces
        self.assertIsNone(live.match_question("지금"))
        self.assertIsNone(live.match_question("다음 휴게소 어때요"))   # the rest-area question was dropped

class ConsentTest(unittest.TestCase):
    def test_skipping_a_question_hides_it_from_that_neighbour_only(self):
        r, _ = room()
        a, b = r.join("귀성길", "곰"), r.join("느긋한", "토끼")
        qid = r.ask("ASK_REST")
        r.skip(a, qid)
        self.assertEqual(r.poll(a)["pending"], [])
        self.assertEqual(len(r.poll(b)["pending"]), 1)

    def test_paused_neighbour_gets_no_questions(self):
        r, _ = room()
        a = r.join("귀성길", "곰")
        r.set_paused(a, True)
        self.assertIsNone(r.ask("ASK_FOOD"))          # nobody available -> simulated answer takes over
        self.assertTrue(r.poll(a)["paused"])
        r.set_paused(a, False)
        self.assertIsNotNone(r.ask("ASK_FOOD"))

    def test_pausing_after_a_question_arrived_hides_it(self):
        r, _ = room()
        a = r.join("귀성길", "곰")
        r.ask("ASK_FOOD")
        r.set_paused(a, True)
        self.assertEqual(r.poll(a)["pending"], [])


class ProfileTest(unittest.TestCase):
    def test_chosen_name_color_and_privacy_are_kept(self):
        r, _ = room()
        v = r.join("느긋한", "수달", "대구", "#E5446D", show_model=False, show_dest=True, show_title=False)
        p = r.players[v]
        self.assertEqual((p.nick, p.dest, p.color), ("느긋한수달", "대구", "#E5446D"))
        self.assertEqual((p.show_model, p.show_dest, p.show_title), (False, True, False))

    def test_text_that_is_not_in_the_lists_never_becomes_a_name_or_destination(self):
        r, _ = room()
        v = r.join("욕설", "아무말", "아무데나", "red")
        p = r.players[v]
        self.assertIn(p.nick, live.NICKS)
        self.assertIsNone(p.dest)
        self.assertEqual(p.color, live.COLORS[0])

    def test_profile_can_be_changed_later(self):
        r, _ = room()
        v = r.join("귀성길", "곰")
        r.set_profile(v, "신나는", "고래", "부산", "#12B76A", False, False, True)
        self.assertEqual((r.players[v].nick, r.players[v].dest, r.players[v].show_model), ("신나는고래", "부산", False))


class MoodTest(unittest.TestCase):
    def test_clearing_and_nothing_special_are_reports_too(self):
        rep = live.Reports(clock=Clock())
        rep.add("ahead", "clearing", "me")
        self.assertEqual(rep.summary()[0]["title"], "앞쪽 정체가 풀리는 중")
        self.assertEqual(rep.summary()[0]["kind"], "info")

class TextFilterTest(unittest.TestCase):
    def test_links_phone_numbers_and_long_sentences_are_refused(self):
        for bad in ("www.example.com 가보세요", "01012345678 로 연락줘요", "가" * 61, "me@mail.net", "   "):
            self.assertIsNone(live.clean_text(bad), bad)
        self.assertEqual(live.clean_text("  앞에   사슴이 죽어 있어요 "), "앞에 사슴이 죽어 있어요")


class CarProfileTest(unittest.TestCase):
    def test_a_profile_comes_only_from_the_lists(self):
        p = live.clean_profile({"adjective": "느긋한", "animal": "곰", "color": live.COLORS[2], "model": "아이오닉 6", "show_model": False})
        self.assertEqual(p, {"nick": "느긋한곰", "color": live.COLORS[2], "model": "아이오닉 6", "show_model": False})

    def test_anything_typed_is_dropped_or_defaulted(self):
        p = live.clean_profile({"adjective": "욕설", "animal": "곰", "color": "#000000", "model": "내가 만든 차"})
        self.assertIsNone(p["nick"])               # not on the lists: the caller keeps the name it already has
        self.assertEqual(p["color"], live.COLORS[0])
        self.assertEqual(p["model"], "")
        self.assertTrue(p["show_model"])           # sharing the model is the default; hiding it is a choice
        self.assertEqual(live.clean_profile({})["nick"], None)


if __name__ == "__main__":
    unittest.main()
