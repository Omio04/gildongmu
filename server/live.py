"""Live neighbours: real people join from a phone and answer the car's one-touch questions.

No Pleos or database dependency; app_server.py wires this to the vehicle table and the inbox.
A neighbour never types anything: it only picks one of a few answers, so nothing can be abused.
"""
import random
import threading
import time

NICKS = ["귀성길곰", "느긋한토끼", "졸린사자", "달리는펭귄", "고향행여우", "휴게소요정", "나들이고래", "든든한다람쥐"]

# A profile is built only from these lists: nothing a driver types ever reaches another driver.
ADJECTIVES = ["귀성길", "느긋한", "졸린", "달리는", "든든한", "신나는", "조용한", "반가운", "씩씩한", "여유로운", "부지런한", "포근한"]
ANIMALS = ["곰", "토끼", "사자", "펭귄", "여우", "고래", "다람쥐", "수달", "고양이", "강아지", "오리", "거북이"]
COLORS = ["#00AAD2", "#12B76A", "#F5A524", "#E5446D", "#7A5AF8", "#2E90FA"]
DESTINATIONS = ["서울", "인천", "수원", "대전", "청주", "전주", "광주", "대구", "부산", "울산", "강릉", "춘천", "목포", "여수", "포항", "제주"]
ACTIVE_SECONDS = 10     # a phone that polled within this window counts as "in the next car"
QUESTION_SECONDS = 45   # how long a question stays on the phones
MAX_ANSWERS = 3         # a question to everyone closes after this many answers
DIRECT_COOLDOWN = 60    # seconds before the same car can be asked directly again (no harassment)

QUESTION_TEXT = {
    "ASK_REST": "다음 휴게소 혼잡한가요?",
    "ASK_FOOD": "근처 맛집 있나요?",
    "ASK_ACCIDENT": "앞에 무슨 일 있나요?",
    "HELLO": "인사를 건넸어요",
}

# answers a neighbour can pick (id, label shown on the phone)
OPTIONS = {
    "ASK_REST": [("free", "한산해요"), ("normal", "보통이에요"), ("busy", "혼잡해요"), ("unknown", "잘 모르겠어요")],
    "ASK_FOOD": [("walnut", "호두과자 맛집이 좋아요"), ("udon", "휴게소 우동이 괜찮아요"), ("unknown", "잘 모르겠어요")],
    "ASK_ACCIDENT": [("accident", "사고 처리 중이에요"), ("work", "공사 중이에요"),
                     ("jam", "그냥 정체예요, 곧 풀려요"), ("unknown", "잘 모르겠어요")],
    "HELLO": [("hi", "반가워요!"), ("same", "저도 같은 방향이에요")],
}

# (helped answers needed, title)
TITLES = [(0, "길동무 새싹"), (3, "길 안내 단골"), (10, "도로 위 박사")]


def title_for(helped):
    out = TITLES[0][1]
    for need, name in TITLES:
        if helped >= need:
            out = name
    return out


class LiveError(Exception):
    pass


class Player:
    def __init__(self, vid, nick, dest, now):
        self.vid, self.nick, self.dest = vid, nick, dest
        self.joined = self.last_seen = now
        self.color = COLORS[0]
        self.show_model = True   # privacy: what other drivers may see
        self.show_dest = True
        self.show_title = True
        self.helped = 0          # answers given
        self.thanks = 0          # thank-yous received
        self.thanks_unseen = 0   # shown once on the phone as a toast
        self.blocked = False     # reported by a driver: no more questions, and the answers no longer count
        self.paused = False      # "잠시 쉬기": the neighbour chose not to receive questions for now


class LiveRoom:
    def __init__(self, clock=time.time):
        self.clock = clock
        self.lock = threading.RLock()
        self.players = {}
        self.questions = {}
        self.last_direct = {}   # vid -> when it was last asked directly
        self._seq = 0

    def _next(self):
        self._seq += 1
        return self._seq

    # ---- phones
    def join(self, adjective=None, animal=None, dest=None, color=None, show_model=True, show_dest=True, show_title=True):
        """Anything outside the allowed lists is replaced by a safe default."""
        with self.lock:
            if adjective in ADJECTIVES and animal in ANIMALS:
                nick = adjective + animal
            else:
                nick = random.choice(NICKS)
            vid = "live-%d" % self._next()
            p = Player(vid, nick, dest if dest in DESTINATIONS else None, self.clock())
            self._apply(p, color, show_model, show_dest, show_title)
            self.players[vid] = p
            return vid

    @staticmethod
    def _apply(p, color, show_model, show_dest, show_title):
        p.color = color if color in COLORS else COLORS[0]
        p.show_model, p.show_dest, p.show_title = bool(show_model), bool(show_dest), bool(show_title)

    def set_profile(self, vid, adjective=None, animal=None, dest=None, color=None, show_model=True, show_dest=True, show_title=True):
        with self.lock:
            p = self.touch(vid)
            if adjective in ADJECTIVES and animal in ANIMALS:
                p.nick = adjective + animal
            if dest in DESTINATIONS or dest is None:
                p.dest = dest
            self._apply(p, color, show_model, show_dest, show_title)

    def touch(self, vid):
        p = self.players.get(vid)
        if not p:
            raise LiveError("unknown player")
        p.last_seen = self.clock()
        return p

    def active(self):
        """Players whose phone is open right now, oldest first."""
        now = self.clock()
        with self.lock:
            return [p for p in self.players.values() if now - p.last_seen <= ACTIVE_SECONDS]

    # ---- questions
    def ask(self, preset, to=None):
        """The car asked something: to everyone around (to=None) or to one car.
        Returns a question id, or None when nobody is available to answer."""
        if preset not in OPTIONS:
            raise LiveError("unknown preset")
        with self.lock:
            now = self.clock()
            available = [p.vid for p in self.active() if not p.blocked and not p.paused]
            if to is not None:
                if to not in available:
                    return None
                last = self.last_direct.get(to)
                if last is not None and now - last < DIRECT_COOLDOWN:
                    raise LiveError("cooldown")
                self.last_direct[to] = now
                targets, scope = {to}, "direct"
            else:
                if not available:
                    return None
                targets, scope = set(available), "all"
            qid = self._next()
            self.questions[qid] = {"preset": preset, "created": now, "scope": scope, "targets": targets,
                                   "answers": []}   # (vid, option)
            return qid

    def _open(self, q):
        limit = 1 if q["scope"] == "direct" else MAX_ANSWERS
        return len(q["answers"]) < limit

    def is_answered(self, qid):
        q = self.questions.get(qid)
        return bool(q and q["answers"])

    def poll(self, vid):
        with self.lock:
            p = self.touch(vid)
            now = self.clock()
            me = {"nick": p.nick, "helped": p.helped, "thanks": p.thanks, "title": title_for(p.helped), "color": p.color}
            if p.blocked:
                return {"pending": [], "thanks_new": 0, "blocked": True, "me": me}
            pending = []
            for qid, q in self.questions.items():
                mine = any(v == vid for v, _ in q["answers"])
                if p.paused or mine or not self._open(q) or vid not in q["targets"] or now - q["created"] > QUESTION_SECONDS:
                    continue
                labels = dict(OPTIONS[q["preset"]])
                pending.append({
                    "qid": qid, "preset": q["preset"], "text": QUESTION_TEXT[q["preset"]], "scope": q["scope"],
                    "options": [{"id": i, "label": l} for i, l in OPTIONS[q["preset"]]],
                    # what the other neighbours already said: everyone sees the whole conversation
                    "answers": [{"nick": self.players[v].nick, "label": labels[o]} for v, o in q["answers"]],
                })
            thanks_new, p.thanks_unseen = p.thanks_unseen, 0
            return {"pending": pending, "thanks_new": thanks_new, "paused": p.paused, "me": me}

    def answer(self, vid, qid, option):
        """Returns (preset, option id). Everyone-questions take up to MAX_ANSWERS, one each."""
        with self.lock:
            p = self.touch(vid)
            q = self.questions.get(qid)
            if not q or vid not in q["targets"]:
                raise LiveError("unknown question")
            if any(v == vid for v, _ in q["answers"]):
                raise LiveError("already answered")
            if not self._open(q):
                raise LiveError("already answered")
            if option not in dict(OPTIONS[q["preset"]]):
                raise LiveError("unknown option")
            q["answers"].append((vid, option))
            p.helped += 1
            return q["preset"], option

    def skip(self, vid, qid):
        """The neighbour declines this question (it stays open for the others)."""
        with self.lock:
            self.touch(vid)
            q = self.questions.get(qid)
            if q:
                q["targets"].discard(vid)
            return bool(q)

    def set_paused(self, vid, paused):
        """"잠시 쉬기": no questions until the neighbour turns it back on."""
        with self.lock:
            self.touch(vid).paused = bool(paused)

    def block(self, vid):
        """A driver reported this neighbour."""
        with self.lock:
            p = self.players.get(vid)
            if not p:
                return False
            p.blocked = True
            return True

    def thank(self, vid):
        with self.lock:
            p = self.players.get(vid)
            if not p:
                return False
            p.thanks += 1
            p.thanks_unseen += 1
            return True


# ---- shared picture of what is ahead, built from the answers neighbours give

REPORT_SECONDS = 15 * 60   # a report is stale after this long
AHEAD_TEXT = {"accident": "앞쪽 사고 처리 중", "work": "앞쪽 공사 중", "jam": "단순 정체, 곧 풀릴 가능성"}
REST_TEXT = {"free": "한산해요", "normal": "보통이에요", "busy": "혼잡해요"}
FOOD_TEXT = {"walnut": "호두과자 맛집이 좋대요", "udon": "휴게소 우동이 괜찮대요"}


class Reports:
    """Every answer a neighbour gives is also a report the whole road can read."""

    def __init__(self, clock=time.time):
        self.clock = clock
        self.lock = threading.RLock()
        self.items = []   # (timestamp, topic, value, who)

    def add(self, topic, value, who):
        with self.lock:
            self.items.append((self.clock(), topic, value, who))

    def remove_by(self, who):
        """Drop everything a (reported) neighbour said."""
        with self.lock:
            self.items = [r for r in self.items if r[3] != who]

    def _fresh(self):
        now = self.clock()
        self.items = [r for r in self.items if now - r[0] <= REPORT_SECONDS]
        return self.items, now

    @staticmethod
    def _age(sec):
        m = int(sec // 60)
        return "방금" if m < 1 else f"{m}분 전"

    def summary(self, rest_name=None):
        """Up to three short lines: what is ahead, how the rest area is, what to eat."""
        with self.lock:
            items, now = self._fresh()
            out = []

            def top(topic, allowed):
                rows = [r for r in items if r[1] == topic and r[2] in allowed]
                if not rows:
                    return None
                counts = {}
                for r in rows:
                    counts[r[2]] = counts.get(r[2], 0) + 1
                best = max(counts, key=lambda k: (counts[k], max(r[0] for r in rows if r[2] == k)))
                newest = max(r[0] for r in rows if r[2] == best)
                return best, counts[best], len(rows), now - newest

            a = top("ahead", AHEAD_TEXT)
            if a:
                v, n, total, age = a
                out.append({"kind": "warning" if v != "jam" else "info", "title": AHEAD_TEXT[v],
                            "detail": f"이웃 {n}명{'' if n == total else f' (총 {total}명 제보)'} · {self._age(age)}"})
            r = top("rest", REST_TEXT)
            if r:
                v, n, total, age = r
                out.append({"kind": "cafe", "title": f"{rest_name or '다음 휴게소'} {REST_TEXT[v]}",
                            "detail": f"이웃 {n}명 · {self._age(age)}"})
            f = top("food", FOOD_TEXT)
            if f:
                v, n, total, age = f
                out.append({"kind": "food", "title": FOOD_TEXT[v], "detail": f"이웃 {n}명 · {self._age(age)}"})
            return out


# ---- spoken words -> one of the fixed answers / questions (the voice never becomes free text)

ANSWER_WORDS = {
    "ASK_REST": {"free": ["한산", "널널", "여유", "괜찮"], "normal": ["보통", "그냥", "적당"],
                 "busy": ["혼잡", "붐", "복잡", "사람 많", "꽉", "만차"], "unknown": ["모르"]},
    "ASK_FOOD": {"walnut": ["호두", "과자"], "udon": ["우동", "국수", "라면"], "unknown": ["모르"]},
    "ASK_ACCIDENT": {"accident": ["사고", "접촉", "추돌"], "work": ["공사", "작업"],
                     "jam": ["정체", "막혀", "차 많", "그냥", "곧 풀"], "unknown": ["모르"]},
    "HELLO": {"hi": ["안녕", "반가"], "same": ["같은", "같이", "저도"]},
}

QUESTION_WORDS = {   # order matters: a question that names the rest area is about the rest area
    "ASK_REST": ["휴게", "휴계", "휴개", "쉼터"],   # the recogniser sometimes writes 휴계소
    "ASK_FOOD": ["맛집", "식당", "맛있", "먹", "음식", "밥", "배고"],
    "ASK_ACCIDENT": ["사고", "무슨 일", "왜 막", "앞에", "정체", "막혀", "막히", "마키"],
    "HELLO": ["안녕", "인사"],
}


def _first_hit(text, table):
    squeezed = (text or "").replace(" ", "")
    for key, words in table.items():
        if any(w.replace(" ", "") in squeezed for w in words):
            return key
    return None


def match_answer(preset, spoken):
    """What a neighbour said out loud -> the answer option it means, or None."""
    return _first_hit(spoken, ANSWER_WORDS.get(preset, {}))


def match_question(spoken):
    """What a driver said out loud -> the question it means, or None."""
    return _first_hit(spoken, QUESTION_WORDS)
