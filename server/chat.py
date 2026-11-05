"""Road messages: a short sentence anyone can say out loud ("앞에 사슴이 죽어 있어요", "끼워 줘서 감사합니다"),
shown to every car around as a speech bubble beside the car that sent it. Never to one person: it is a
notice on the road, not a conversation.

The driver confirms the recognised words before they are sent (the app does that); here the text is only
checked to be short and free of links and phone numbers, and a sender is rate-limited so one car cannot fill
the road with bubbles. A message a neighbour reports takes its sender off the road.
No Pleos or database dependency; app_server.py wires the ids in.
"""
import threading
import time

import live

BUBBLE_SECONDS = 25     # how long the bubble stays beside the car
RECENT_SECONDS = 90     # how long a message stays in the list
MIN_GAP_SECONDS = 8     # one car, one message at a time
MAX_PER_MINUTE = 5
SAME_TEXT_SECONDS = 30  # saying the same thing again only keeps the first
LIST_N = 3


class ChatError(Exception):
    pass


class Chat:
    def __init__(self, clock=time.time):
        self.clock = clock
        self.lock = threading.RLock()
        self.items = []   # dicts: id, who, text, at
        self._id = 0

    def _fresh(self):
        now = self.clock()
        self.items = [m for m in self.items if now - m["at"] <= RECENT_SECONDS]
        return now

    def post(self, who, raw):
        """Returns the message id. Raises ChatError("bad text") or ChatError("too fast")."""
        text = live.clean_text(raw)
        if text is None:
            raise ChatError("bad text")
        with self.lock:
            now = self._fresh()
            mine = [m for m in self.items if m["who"] == who]
            for m in mine:
                if m["text"] == text and now - m["at"] <= SAME_TEXT_SECONDS:
                    return m["id"]
            if mine and now - max(m["at"] for m in mine) < MIN_GAP_SECONDS:
                raise ChatError("too fast")
            if sum(1 for m in mine if now - m["at"] <= 60) >= MAX_PER_MINUTE:
                raise ChatError("too fast")
            self._id += 1
            self.items.append({"id": self._id, "who": who, "text": text, "at": now})
            return self._id

    def say_of(self, who):
        """The sentence to show in a bubble beside this car right now, or None."""
        with self.lock:
            now = self._fresh()
            mine = [m for m in self.items if m["who"] == who and now - m["at"] <= BUBBLE_SECONDS]
            return mine[-1]["text"] if mine else None

    def sender_of(self, msg_id):
        with self.lock:
            return next((m["who"] for m in self.items if m["id"] == msg_id), None)

    def remove_by(self, who):
        with self.lock:
            self.items = [m for m in self.items if m["who"] != who]

    def _age(self, now, at):
        s = int(now - at)
        return "방금" if s < 60 else f"{s // 60}분 전"

    def recent(self, viewer, name_of=lambda who: who):
        """What others said lately, newest first (never the viewer's own: they already see their bubble)."""
        with self.lock:
            now = self._fresh()
            rows = [m for m in self.items if m["who"] != viewer]
            rows.sort(key=lambda m: -m["at"])
            return [{"id": m["id"], "from": name_of(m["who"]), "text": m["text"], "age": self._age(now, m["at"])}
                    for m in rows[:LIST_N]]
