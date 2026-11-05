"""A second car for testing: joins the server like a phone neighbour (/neighbor) and plays one neighbour on the road.

It is a separate client talking to the server over HTTP, so the emulator sees a real neighbour appear on its road,
and what the emulator asks reaches it. It is a TEST tool: the real use is the phone page http://<PC>:8080/neighbor.

    python tools/neighbor_demo.py --north-m 80 --answer      # 80 m ahead; answers the first question it receives
    python tools/neighbor_demo.py --say "앞에 사고 났어요"      # says one short sentence to everyone around, then keeps running

The position defaults to the emulator's start position (37.41272, 127.09468) moved north by --north-m metres.
"""
import argparse
import json
import time
import urllib.request

LAT, LON = 37.41272, 127.09468


def call(base, method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method, headers={"Content-Type": "application/json; charset=utf-8"})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read().decode("utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://127.0.0.1:8080")
    ap.add_argument("--north-m", type=float, default=80.0, help="metres north of the emulator's start position")
    ap.add_argument("--east-m", type=float, default=0.0)
    ap.add_argument("--answer", action="store_true", help="answer the first question received with its first quick answer")
    ap.add_argument("--say", default="", help="say one short sentence to everyone around once")
    ap.add_argument("--seconds", type=int, default=120, help="how long to stay on the road")
    a = ap.parse_args()

    opts = call(a.server, "GET", "/api/n/options")
    vid = call(a.server, "POST", "/api/n/join", {"adjective": opts["adjectives"][0], "animal": opts["animals"][1], "dest": None,
                                                 "color": opts["colors"][2] if isinstance(opts["colors"][2], str) else None})["vid"]
    lat, lon = LAT + a.north_m / 111000.0, LON + a.east_m / 88000.0
    print("joined as", vid, flush=True)
    said, answered = False, False
    end = time.time() + a.seconds
    while time.time() < end:
        call(a.server, "POST", "/api/n/pos", {"vid": vid, "lat": lat, "lon": lon, "heading": 0.0})
        if a.say and not said:
            call(a.server, "POST", "/api/n/say", {"vid": vid, "text": a.say})
            said = True
            print("said:", a.say, flush=True)
        polled = call(a.server, "GET", f"/api/n/poll?vid={vid}")
        qs = polled.get("pending") or []
        if a.answer and not answered and qs:
            q = qs[0]
            opts_q = q.get("options") or []
            first = opts_q[0] if opts_q else None
            option = first.get("id") if isinstance(first, dict) else first
            print("question:", json.dumps(q, ensure_ascii=False)[:200], flush=True)
            if option:
                print("answer:", call(a.server, "POST", "/api/n/answer", {"vid": vid, "qid": q["qid"], "option": option}), flush=True)
                answered = True
        time.sleep(3)


if __name__ == "__main__":
    main()
