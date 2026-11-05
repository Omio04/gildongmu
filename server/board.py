"""The road notice board: what drivers report about the road ahead, and how the road rewards them.

A report is posted once. Another car can answer it with one of three taps, or ignore it (the prompt goes away
by itself, no penalty): "맞아요" (it is true), "고마워요", "덕분에 잘 지나갔어요" (it really helped me).
Each tap earns the author points, so the useful drivers rise and a false alarm has nobody to confirm it.
No Pleos or database dependency; app_server.py wires the ids of the car and the phone neighbours in.
"""
import threading
import time

KINDS = {
    "accident": "앞쪽 사고 처리 중",
    "work": "앞쪽 공사 중",
    "debris": "도로에 낙하물이 있어요",
    "breakdown": "고장 난 차가 서 있어요",
    "roadkill": "도로에 로드킬이 있어요",
    "rockfall": "낙석이 있어요",
    "pothole": "도로에 포트홀이 있어요",
    "ice": "노면이 얼어 있어요",
    "flood": "도로가 물에 잠겼어요",
    "clearing": "앞쪽 정체가 풀리는 중",
}
# what people must see first (anything that can hurt a car); the others are good news or plain information
URGENT = {"accident", "debris", "breakdown", "roadkill", "rockfall", "pothole", "ice", "flood"}

POST_SECONDS = 15 * 60       # a report is stale after this long
SAME_KIND_SECONDS = 2 * 60   # the same driver repeating a report only refreshes it
REACTIONS = {"confirm": "맞아요", "thanks": "고마워요", "passed": "덕분에 잘 지나갔어요"}
POINTS = {"confirm": 5, "thanks": 8, "passed": 12}   # for the author, by what the other car tapped
POINTS_HELPER = 1            # whoever answers: checking the road is useful too
TOP_N = 3

# (points needed, grade): shown next to the points, and the step toward a (future) Hyundai point exchange
GRADES = [(0, "길 위 새싹"), (30, "길잡이"), (100, "도로 박사"), (300, "도로 영웅")]


def grade_for(points):
    name, nxt = GRADES[0][1], None
    for i, (need, g) in enumerate(GRADES):
        if points >= need:
            name = g
            nxt = GRADES[i + 1][0] if i + 1 < len(GRADES) else None
    return name, nxt


# ---- rewards for answering "왜 막혀?"
# Principles: pay for help, not for volume; the person who benefited decides; make collusion worthless.
REWARD = {
    "answer": 1,    # any answer: a small thank-you for taking part
    "first": 3,     # the first answer to a question: speed matters when the road is stuck
    "agree": 4,     # two independent cars gave the same cause: agreement is what makes an answer trustworthy
    "helped": 10,   # the asker said "도움이 됐어요": the one reward the answerer cannot give themselves
}
AHEAD_BONUS = 1.5            # a car AHEAD of the asker can see the cause; behind, it can only guess
PAIR_FACTOR = [1.0, 0.5, 0.0]   # the same two cars rewarding each other: full, half, then nothing
BASE_ANSWER_CAP = 20         # points from plain answers per car; real help is not capped


class BoardError(Exception):
    pass


class Board:
    def __init__(self, clock=time.time):
        self.clock = clock
        self.lock = threading.RLock()
        self.posts = []     # dicts: id, kind, who, at, confirms{set}, thanks{set}
        self.points = {}    # who -> points
        self.counts = {}    # who -> {"posts": n, "confirmed": n}
        self.base_answer = {}     # who -> points from plain answers (capped)
        self.helped_keys = set()  # (asker, answerer, key): each answer can be thanked once
        self.pair_count = {}      # (asker, answerer) -> how many times already rewarded
        self._id = 0

    def _fresh(self):
        now = self.clock()
        self.posts = [p for p in self.posts if now - p["at"] <= POST_SECONDS]
        return now

    def post(self, kind, who):
        """A driver reports what they see. Repeating the same report soon after only refreshes it."""
        if kind not in KINDS:
            raise BoardError("unknown report")
        with self.lock:
            now = self._fresh()
            for p in self.posts:
                if p["who"] == who and p["kind"] == kind and now - p["at"] <= SAME_KIND_SECONDS:
                    p["at"] = now
                    return p["id"]
            self._id += 1
            self.posts.append({"id": self._id, "kind": kind, "who": who, "at": now, "votes": {}})   # votes: car -> reaction
            self.counts.setdefault(who, {"posts": 0, "confirmed": 0})["posts"] += 1
            return self._id

    def react(self, post_id, voter, what):
        """One tap per car per report: "confirm", "thanks" or "passed". Not for your own report.
        Returns the author's points."""
        if what not in REACTIONS:
            raise BoardError("unknown reaction")
        with self.lock:
            self._fresh()
            p = next((x for x in self.posts if x["id"] == post_id), None)
            if p is None:
                raise BoardError("gone")
            if p["who"] == voter:
                raise BoardError("own report")
            if voter in p["votes"]:
                raise BoardError("already")
            p["votes"][voter] = what
            self.points[p["who"]] = self.points.get(p["who"], 0) + POINTS[what]
            self.counts.setdefault(p["who"], {"posts": 0, "confirmed": 0})["confirmed"] += 1
            self.points[voter] = self.points.get(voter, 0) + POINTS_HELPER
            return self.points[p["who"]]

    def reward_answer(self, who, first=False, ahead=False):
        """Points for giving an answer. Plain answers stop paying after BASE_ANSWER_CAP points."""
        with self.lock:
            got = self.base_answer.get(who, 0)
            gain = REWARD["answer"] + (REWARD["first"] if first else 0)
            gain = max(0, min(gain, BASE_ANSWER_CAP - got))
            if ahead:
                gain = int(round(gain * AHEAD_BONUS))
            self.base_answer[who] = got + gain
            self.award(who, gain)
            return gain

    def reward_agree(self, whos):
        """Cars that independently named the same cause each get the agreement bonus."""
        with self.lock:
            for who in set(whos):
                self.award(who, REWARD["agree"])

    def reward_helped(self, asker, answerer, key, ahead=False):
        """The asker says an answer helped. The same two cars rewarding each other pays less each time,
        and one answer can only be thanked once. Returns the points the answerer got."""
        with self.lock:
            if asker == answerer or (asker, answerer, key) in self.helped_keys:
                return 0
            self.helped_keys.add((asker, answerer, key))
            n = self.pair_count.get((asker, answerer), 0)
            self.pair_count[(asker, answerer)] = n + 1
            factor = PAIR_FACTOR[min(n, len(PAIR_FACTOR) - 1)]
            gain = int(round(REWARD["helped"] * factor * (AHEAD_BONUS if ahead else 1)))
            self.award(answerer, gain)
            if gain:
                self.counts.setdefault(answerer, {"posts": 0, "confirmed": 0})["confirmed"] += 1
            return gain

    def award(self, who, n):
        """Points for something other than a tap on a report (e.g. answering a neighbour's question)."""
        with self.lock:
            self.points[who] = self.points.get(who, 0) + n

    def remove_by(self, who):
        """Everything a (reported and blocked) neighbour posted disappears."""
        with self.lock:
            self.posts = [p for p in self.posts if p["who"] != who]

    def _age(self, now, at):
        m = int((now - at) // 60)
        return "방금" if m < 1 else f"{m}분 전"

    def feed(self, viewer, name_of=lambda who: who):
        """Newest urgent reports first, at most TOP_N. [name_of] turns an id into the nickname shown."""
        with self.lock:
            now = self._fresh()
            rows = sorted(self.posts, key=lambda p: (p["kind"] not in URGENT, -p["at"]))[:TOP_N]
            return [{
                "id": p["id"], "kind": p["kind"], "title": KINDS[p["kind"]], "urgent": p["kind"] in URGENT,
                "by": name_of(p["who"]), "mine": p["who"] == viewer, "age": self._age(now, p["at"]),
                "votes": {k: sum(1 for v in p["votes"].values() if v == k) for k in REACTIONS},
                "my_vote": p["votes"].get(viewer),
            } for p in rows]

    def me(self, who):
        pts = self.points.get(who, 0)
        grade, nxt = grade_for(pts)
        c = self.counts.get(who, {"posts": 0, "confirmed": 0})
        return {"points": pts, "grade": grade, "next": nxt, "posts": c["posts"], "confirmed": c["confirmed"]}
