"""Questions people ask on the road: to everyone around, or to one car the asker picked.

A question is a short sentence the asker said out loud and confirmed (or one of the fixed ones). Anybody can answer with one of a few quick replies or a spoken sentence they confirmed; a public question shows
all its answers to everyone, so one good answer helps the whole road. Whoever answers earns a few points.
A question to one car is seen and answered by that car only (one answer closes it), and the same car cannot be asked
again for a minute: picking a car is a way to ask, not a way to pester. Reporting a car removes its questions.
No Pleos or database dependency; app_server.py wires the ids and the points in.
"""
import threading
import time

import live

QUICK = [("yes", "네"), ("no", "아니요"), ("unknown", "잘 모르겠어요")]
QUICK_TEXT = dict(QUICK)

QUESTION_SECONDS = 10 * 60   # a question goes away after this long
MAX_PUBLIC_ANSWERS = 5       # a public question closes after this many answers
MAX_OPEN_PER_ASKER = 3       # no flooding the road with questions
DIRECT_GAP_SECONDS = 60      # the same asker may ask the same car again only after this long
SHOWN = 4                  # the list stays short: the road is the main thing


class QuestionError(Exception):
    pass


class Questions:
    def __init__(self, clock=time.time):
        self.clock = clock
        self.lock = threading.RLock()
        self.items = []   # dicts: id, who, text, to (None = everyone), at, answers [(who, text)]
        self._id = 0

    def _fresh(self):
        now = self.clock()
        self.items = [q for q in self.items if now - q["at"] <= QUESTION_SECONDS]
        return now

    def ask(self, who, text, to=None):
        """[to]: the one car that may see and answer it, or None for everyone around. Returns the question id."""
        said = live.clean_text(text)
        if said is None:
            raise QuestionError("bad text")
        if to == who:
            raise QuestionError("bad target")
        with self.lock:
            now = self._fresh()
            if sum(1 for q in self.items if q["who"] == who) >= MAX_OPEN_PER_ASKER:
                raise QuestionError("too many")
            if to is not None and any(q["who"] == who and q["to"] == to and now - q["at"] < DIRECT_GAP_SECONDS
                                      for q in self.items):
                raise QuestionError("too soon")
            self._id += 1
            self.items.append({"id": self._id, "who": who, "text": said, "to": to, "at": now, "answers": []})
            return self._id

    def _open(self, q):
        return len(q["answers"]) < (1 if q["to"] is not None else MAX_PUBLIC_ANSWERS)

    def answer(self, qid, who, reply):
        """[reply]: a quick-reply id (yes / no / unknown) or the confirmed spoken sentence. Returns the answer text."""
        with self.lock:
            self._fresh()
            q = next((x for x in self.items if x["id"] == qid), None)
            if q is None or (q["to"] is not None and q["to"] != who):   # a question to another car does not exist for you
                raise QuestionError("gone")
            if q["who"] == who:
                raise QuestionError("own question")
            if any(w == who for w, _ in q["answers"]) or not self._open(q):
                raise QuestionError("already answered")
            text = QUICK_TEXT.get(reply) or live.clean_text(reply)
            if text is None:
                raise QuestionError("bad text")
            q["answers"].append((who, text))
            return text

    def sender_of(self, qid):
        """Who asked question [qid] (None if it is gone): what a report of that question points at."""
        with self.lock:
            q = next((x for x in self.items if x["id"] == qid), None)
            return q["who"] if q else None

    def remove_by(self, who):
        with self.lock:
            self.items = [q for q in self.items if q["who"] != who and q["to"] != who]
            for q in self.items:
                q["answers"] = [(w, t) for w, t in q["answers"] if w != who]

    def _age(self, now, at):
        m = int((now - at) // 60)
        return "방금" if m < 1 else f"{m}분 전"

    def feed(self, viewer, name_of=lambda who: who):
        """What [viewer] still has to answer, newest first. Once answered, a question is gone from the viewer's list."""
        with self.lock:
            now = self._fresh()
            mine = [q for q in self.items
                    if q["who"] != viewer and q["to"] in (None, viewer)
                    and not any(w == viewer for w, _ in q["answers"])]

            def key(q):
                answered = any(w == viewer for w, _ in q["answers"])
                return (answered, -q["at"])
            return [{
                "id": q["id"], "from": name_of(q["who"]), "text": q["text"],
                "age": self._age(now, q["at"]),
                "answers": [{"by": name_of(w), "text": t, "mine": w == viewer} for w, t in q["answers"]],
                "answered": any(w == viewer for w, _ in q["answers"]), "open": self._open(q),
                "direct": q["to"] == viewer,   # asked to this car alone
            } for q in sorted(mine, key=key)[:SHOWN]]
