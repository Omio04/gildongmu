import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "server"))
import questions as qs


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def make():
    c = Clock()
    return qs.Questions(clock=c), c


class QuestionsTest(unittest.TestCase):
    def test_a_question_reaches_everyone_but_the_asker(self):
        q, _ = make()
        q.ask("a", "앞에 무슨 일 있는지 아세요?")
        self.assertEqual(len(q.feed("b")), 1)
        self.assertEqual(len(q.feed("c")), 1)
        self.assertEqual(q.feed("a"), [])   # you never see your own question in your inbox

    def test_every_answer_is_shown_to_everyone(self):
        q, _ = make()
        qid = q.ask("a", "앞에 무슨 일 있는지 아세요?")
        q.answer(qid, "b", "yes")
        q.answer(qid, "c", "앞에서 사고 처리 중이에요")
        f = q.feed("d")[0]
        self.assertEqual([x["text"] for x in f["answers"]], ["네", "앞에서 사고 처리 중이에요"])   # d has not answered: sees all
        self.assertNotIn("scope", f)   # there is no private question

    def test_newest_first_and_an_answered_question_leaves_my_list(self):
        q, c = make()
        old = q.ask("a", "먼저 온 질문")
        c.t += 1
        new = q.ask("b", "나중에 온 질문")
        self.assertEqual([x["id"] for x in q.feed("me")], [new, old])
        q.answer(new, "me", "no")
        self.assertEqual([x["id"] for x in q.feed("me")], [old])    # answered: done
        self.assertEqual([x["id"] for x in q.feed("other")], [new, old])   # still open for everyone else

    def test_one_answer_each_and_never_to_your_own_question(self):
        q, _ = make()
        qid = q.ask("a", "공개 질문")
        with self.assertRaises(qs.QuestionError):
            q.answer(qid, "a", "yes")
        q.answer(qid, "b", "yes")
        with self.assertRaises(qs.QuestionError):
            q.answer(qid, "b", "no")

    def test_a_question_closes_after_five_answers(self):
        q, _ = make()
        qid = q.ask("a", "공개 질문")
        for i in range(qs.MAX_PUBLIC_ANSWERS):
            q.answer(qid, f"v{i}", "yes")
        with self.assertRaises(qs.QuestionError):
            q.answer(qid, "late", "yes")
        self.assertFalse(q.feed("z")[0]["open"])

    def test_a_report_of_a_question_can_find_who_asked_it(self):
        q, _ = make()
        qid = q.ask("bad", "이상한 질문이에요")
        self.assertEqual(q.sender_of(qid), "bad")
        self.assertIsNone(q.sender_of(999))
        q.remove_by("bad")
        self.assertIsNone(q.sender_of(qid))       # once reported, the question is gone

    def test_links_numbers_and_empty_text_are_refused(self):
        q, _ = make()
        for bad in ("", "  ", "www.a.com", "01012345678", "가" * 61):
            with self.assertRaises(qs.QuestionError):
                q.ask("a", bad)
        qid = q.ask("a", "공개 질문")
        with self.assertRaises(qs.QuestionError):
            q.answer(qid, "b", "me@mail.net")

    def test_no_flooding_and_old_questions_disappear(self):
        q, c = make()
        for i in range(qs.MAX_OPEN_PER_ASKER):
            q.ask("a", f"질문 {i}")
        with self.assertRaises(qs.QuestionError):
            q.ask("a", "하나 더")
        c.t += qs.QUESTION_SECONDS + 1
        self.assertEqual(q.feed("z"), [])
        q.ask("a", "다시 가능")

    def test_a_blocked_neighbours_questions_and_answers_are_removed(self):
        q, _ = make()
        qid = q.ask("a", "공개 질문")
        q.answer(qid, "bad", "yes")
        q.ask("bad", "나쁜 질문")
        q.remove_by("bad")
        f = q.feed("z")
        self.assertEqual(len(f), 1)
        self.assertEqual(f[0]["answers"], [])

    def test_a_question_to_one_car_is_seen_and_answered_by_that_car_only(self):
        q, _ = make()
        qid = q.ask("a", "저기 우회로 있어요?", to="b")
        self.assertEqual(len(q.feed("b")), 1)
        self.assertTrue(q.feed("b")[0]["direct"])
        self.assertEqual(q.feed("c"), [])           # another car does not even see it
        with self.assertRaises(qs.QuestionError):
            q.answer(qid, "c", "yes")               # nor can it answer
        q.answer(qid, "b", "yes")
        with self.assertRaises(qs.QuestionError):
            q.answer(qid, "b", "no")                # one answer closes it

    def test_the_same_car_cannot_be_asked_again_within_a_minute(self):
        q, c = make()
        q.ask("a", "첫 번째 질문이에요", to="b")
        with self.assertRaises(qs.QuestionError):
            q.ask("a", "또 물어봐요", to="b")
        q.ask("a", "다른 차에게 물어봐요", to="c")   # another car is fine
        c.t += qs.DIRECT_GAP_SECONDS + 1
        q.ask("a", "이제 다시 물어봐요", to="b")

    def test_you_cannot_ask_yourself_and_reporting_a_car_removes_questions_to_it(self):
        q, _ = make()
        with self.assertRaises(qs.QuestionError):
            q.ask("a", "나에게 묻기", to="a")
        q.ask("a", "저기 우회로 있어요?", to="bad")
        q.remove_by("bad")
        self.assertEqual(q.feed("bad"), [])


if __name__ == "__main__":
    unittest.main()
