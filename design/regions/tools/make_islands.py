"""
Turns the region collection pictures into the app's island art.

    python design/regions/tools/make_islands.py              (every picture in design/regions/src)
    python design/regions/tools/make_islands.py jeju busan   (only these)
    python design/regions/tools/make_islands.py --day        (the daytime pictures in design/regions/src_day)

Input: design/regions/src/<regionId>.png, a full screen picture like the Gyeonggi one (about 1479x1063: the island on the left,
the collection panel on the right, the title bar on top) with the five "?" bubbles painted in.
Output, for each picture: app_code/app/src/main/assets/regions/<regionId>/island.png (the island part, with the bubbles and their
tiles painted out) and spots.json (where the bubbles stood). The app draws the bubbles itself at those spots, and a found item's
sticker takes the bubble's place.

Day pictures (--day) are the same kind of screen picture painted in daylight. They give island_day.png and spots_day.json next to
the night ones; the app uses them when it is not in the dark look. Seoul is included there (its day picture has all five bubbles).

Needs: pip install opencv-python-headless numpy
"""
import json, os, sys
import cv2
import numpy as np

DAY = "--day" in sys.argv
SRC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src_day" if DAY else "src"))
SUFFIX = "_day" if DAY else ""
OUT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", "app_code", "app", "src", "main", "assets", "regions"))

IDS = {"seoul", "busan", "daegu", "incheon", "gwangju", "daejeon", "ulsan", "sejong", "gyeonggi", "gangwon",
       "chungbuk", "chungnam", "jeonbuk", "jeonnam", "gyeongbuk", "gyeongnam", "jeju"}

# labels painted into a picture that are not part of the scenery (x0, y0, x1, y1 in the full picture)
EXTRA = {"gyeonggi": [(330, 238, 505, 312), (406, 296, 430, 432)],
         "gangwon": [(6, 32, 104, 156)],
         "incheon": [(0, 0, 122, 126)],
         "sejong": [(0, 0, 160, 128)],
         "daegu": [(240, 78, 368, 130), (296, 116, 314, 210)],        # the "83타워" tag and its dotted line
         # these have no small map at the top left: nothing to paint out there
         "gyeongnam": [], "gyeongbuk": [], "jeonnam": [], "chungbuk": [], "ulsan": [], "jeju": []}   # the small map of Korea at the top left (the app draws its own)
# pictures that are not the usual full screen: where the island part is (x0, y0, x1, y1), and how much to enlarge a small one
LAYOUT = {"gangwon": dict(crop=(0, 60, 575, 562), scale=2),
          "incheon": dict(crop=(0, 10, 566, 532), scale=2)}


# the daytime pictures all have the same layout (1448 x 1086): the title bar and the "back" button on top, the collection panel
# from x = 1003, and the small map of Korea with its tag at the top left, which the app draws itself
if DAY:
    LAYOUT = {rid: dict(crop=(0, 96, 1000, 1031)) for rid in IDS}
    EXTRA = {rid: [(0, 0, 268, 272)] for rid in IDS}


def enlarge(img, k):
    """a gentle enlargement for a smaller picture: cubic, edge-preserving smoothing, a light sharpen"""
    a = cv2.resize(img, None, fx=k, fy=k, interpolation=cv2.INTER_CUBIC)
    a = cv2.bilateralFilter(a, 7, 25, 7)
    blur = cv2.GaussianBlur(a, (0, 0), 2)
    return cv2.addWeighted(a, 1.45, blur, -0.45, 0)


def find_bubbles(img, right_edge):
    """the "?" of each bubble: a white mark of the right shape. Returns (x, y, r) of each bubble, at most five"""
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    white = ((hsv[:, :, 2] > 235) & (hsv[:, :, 1] < 40)).astype(np.uint8)
    n, _, st, _ = cv2.connectedComponentsWithStats(white, 8)
    H = img.shape[0]
    found = []
    for i in range(1, n):
        x, y, w, h, a = st[i]
        if not (0.022 * H <= h <= 0.06 * H and 0.55 <= w / h <= 1.05 and a > 0.25 * w * h): continue
        if y < 0.08 * H or x + w > right_edge: continue      # the title bar and the panel
        cx, cy, r = x + w / 2, y + h * 0.73, h * 1.3          # the bubble around the "?"
        # a real bubble is pale and bluish all round the mark
        ring = img[int(max(0, cy - r * 0.9)):int(cy + r * 0.9), int(max(0, cx - r * 0.9)):int(cx + r * 0.9)]
        if ring.size == 0 or ring.mean() < 110: continue
        found.append((cx, cy, r, a))
    found.sort(key=lambda f: -f[3])
    return [(x, y, r) for x, y, r, _ in found[:8]]


_TPL = None
def bubble_template():
    """a bubble cut from a picture whose "?" marks are found cleanly (design/regions/src/gyeongnam.*): (picture, radius)"""
    global _TPL
    if _TPL is None and DAY:
        # a day bubble, cut from the Chungbuk picture (the upper left one; its "?" is found cleanly)
        ref_path = next((os.path.join(SRC, f) for f in os.listdir(SRC) if f.startswith("chungbuk.")), None)
        if ref_path is None: return None
        ref = cv2.imread(ref_path)
        x, y, r = min(find_bubbles(ref, 1000), key=lambda b: abs(b[0] - 331) + abs(b[1] - 341))
        _TPL = (ref[int(y - r * 1.05):int(y + r * 1.05), int(x - r * 1.05):int(x + r * 1.05)], r)
    elif _TPL is None:
        ref_path = next((os.path.join(SRC, f) for f in os.listdir(SRC) if f.startswith("gyeongnam.")), None)
        if ref_path is None: return None
        ref = cv2.imread(ref_path)
        x, y, r = find_bubbles(ref, ref.shape[1])[0]
        _TPL = (ref[int(y - r * 1.05):int(y + r * 1.05), int(x - r * 1.05):int(x + r * 1.05)], r)
    return _TPL


def looks_like_bubble(img, x, y, r):
    """how much the place around a found "?" looks like a bubble (0..1)"""
    t = bubble_template()
    if t is None: return 1.0
    tpl, r0 = t
    patch = img[int(y - r * 1.05):int(y + r * 1.05), int(x - r * 1.05):int(x + r * 1.05)]
    if patch.shape[0] < 8 or patch.shape[1] < 8: return 0.0
    patch = cv2.resize(patch, (tpl.shape[1], tpl.shape[0]))
    return float(cv2.matchTemplate(patch, tpl, cv2.TM_CCOEFF_NORMED)[0][0])


def match_bubbles(img, right_edge, want=5):
    """when the "?" marks cannot be told apart: look for the bubble's shape at a few sizes"""
    t = bubble_template()
    if t is None: return []
    tpl0, r = t
    best = None
    for k in (0.8, 0.9, 1.0, 1.1, 1.2, 1.35, 1.5, 2.0):
        tpl = cv2.resize(tpl0, None, fx=k, fy=k)
        res = cv2.matchTemplate(img[:, :right_edge], tpl, cv2.TM_CCOEFF_NORMED)
        found = []; rr = res.copy(); th, tw = tpl.shape[:2]
        while len(found) < want:
            _, mx, _, (lx, ly) = cv2.minMaxLoc(rr)
            if mx < 0.5: break
            found.append((lx + tw / 2, ly + th / 2, r * k, mx))
            cv2.rectangle(rr, (lx - tw // 2, ly - th // 2), (lx + tw // 2, ly + th // 2), -1, -1)
        score = sum(f[3] for f in found)
        if len(found) == want and (best is None or score > best[0]): best = (score, found)
    return [(x, y, r) for x, y, r, _ in best[1]] if best else []


def right_edge_of_island(img):
    """the left side of the collection panel: a long bright vertical line in the right third"""
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    H, W = g.shape
    cols = (g[int(H * 0.2):int(H * 0.8), :] > 120).mean(axis=0)
    for x in range(int(W * 0.62), int(W * 0.85)):
        if cols[x] > 0.6: return x - 8
    return int(W * 0.71)


def harmonic_fill(small, mask, boost=True):
    """a smooth fill (each hidden pixel the average of its neighbours, solved from coarse to fine). The night pictures are dark and
    forgive the streaks of the usual inpainting; a bright day picture shows them, so the day ones use this softer fill"""
    m = mask > 0
    out = small.astype(np.float32)
    ring = cv2.dilate(mask, np.ones((9, 9), np.uint8)) > 0
    out[m] = out[ring & ~m].mean(axis=0)
    for level, iters in ((4, 400), (2, 300), (1, 200)):
        if level > 1:
            h, w = out.shape[:2]
            lo = cv2.resize(out, (w // level, h // level), interpolation=cv2.INTER_AREA)
            ml = cv2.resize(mask, (w // level, h // level), interpolation=cv2.INTER_NEAREST) > 0
            known = lo.copy()
            for _ in range(iters):
                lo = cv2.blur(lo, (3, 3)); lo[~ml] = known[~ml]
            up = cv2.resize(lo, (w, h), interpolation=cv2.INTER_CUBIC)
            out[m] = up[m]
        else:
            known = out.copy()
            for _ in range(iters):
                out = cv2.blur(out, (3, 3)); out[~m] = known[~m]
    # averaging pulls the colours towards grey: give back the colourfulness of the scenery round the hole
    if not boost: return np.clip(out, 0, 255).astype(np.uint8)    # plain sky (the small map's place): no boost, or it shows as a box
    hsv = cv2.cvtColor(np.clip(out, 0, 255).astype(np.uint8), cv2.COLOR_BGR2HSV).astype(np.float32)
    hsv[..., 1] = np.clip(hsv[..., 1] * 1.3, 0, 255); hsv[..., 2] = np.clip(hsv[..., 2] * 1.03, 0, 255)
    out = cv2.cvtColor(hsv.astype(np.uint8), cv2.COLOR_HSV2BGR).astype(np.float32)
    return np.clip(out, 0, 255).astype(np.uint8)


def paint_out(img, bubbles, extra):
    """fills the bubbles and their tiles with the colours around them. The mask hugs the bubble's rim (and the tile), the
    fill is made on a half-size copy (a soft blend, no streaks) and blended back through a narrow feather, so only the
    bubble's own place is softened, not the scenery around it"""
    m = np.zeros(img.shape[:2], np.uint8)
    me = np.zeros(img.shape[:2], np.uint8)      # the labels and the small map: filled on their own
    for (x, y, r) in bubbles:
        # one smooth shape round the bubble and its tile: the hull of the bubble's rim and the tile's four corners
        cy = y + r * 1.4
        # by day the hole is cut closer to the bubble's real size (the bubble's rim, the tile's four corners): the day fill
        # shows more than the night one, so the less of it the better
        ro, tw, tu, td = (1.0, 0.98, 0.52, 0.52) if DAY else (1.08, 1.48, 0.7, 0.95)
        pts = [(x + r * ro * np.cos(a), y + r * ro * np.sin(a)) for a in np.linspace(0, 2 * np.pi, 36, endpoint=False)]
        pts += [(x - r * tw, cy), (x + r * tw, cy), (x, cy - r * tu), (x, cy + r * td)]
        cv2.fillPoly(m, [cv2.convexHull(np.array(pts, np.int32))], 255)
    for (a, b, c, d) in extra: cv2.rectangle(me, (a, b), (c, d), 255, -1)
    m = cv2.bitwise_or(m, me)
    H, W = img.shape[:2]; k = 4
    small = cv2.resize(img, (W // k, H // k), interpolation=cv2.INTER_AREA)
    ms = cv2.resize(m, (W // k, H // k), interpolation=cv2.INTER_NEAREST)
    if not DAY: ms = cv2.dilate(ms, np.ones((3, 3), np.uint8))      # by day the hole is not widened: less of the fill shows
    if DAY:
        mes = cv2.dilate(cv2.resize(me, (W // k, H // k), interpolation=cv2.INTER_NEAREST), np.ones((3, 3), np.uint8))
        mbs = cv2.bitwise_and(ms, cv2.bitwise_not(mes))
        filled = np.where(mes[..., None] > 0, harmonic_fill(small, mes, False), harmonic_fill(small, mbs, True))
    else:
        filled = cv2.GaussianBlur(cv2.inpaint(small, ms, 5, cv2.INPAINT_NS), (0, 0), 1.6)
    up = cv2.resize(filled, (W, H), interpolation=cv2.INTER_CUBIC)
    feather = cv2.GaussianBlur(m.astype(np.float32) / 255, (0, 0), 2.0 if DAY else 3.5)[..., None]
    out = img.astype(np.float32) * (1 - feather) + up.astype(np.float32) * feather
    out += np.random.default_rng(1).normal(0, 3.0, img.shape).astype(np.float32) * feather
    return np.clip(out, 0, 255).astype(np.uint8)


def make(rid, path):
    img = cv2.imread(path)
    if img is None: print(f"{rid}: cannot read {path}"); return
    H, W = img.shape[:2]
    lay = LAYOUT.get(rid)
    extra = list(EXTRA.get(rid, []))
    if lay is None and W < 900:
        # a phone-sized screenshot of the island alone: a thin bar at the very top, the small map of Korea at the top left
        lay = dict(crop=(0, 10, W, H - 12), scale=2)
        if rid not in EXTRA: extra.append((0, 0, int(W * 0.24), int(H * 0.235)))
    right = lay["crop"][2] if lay else right_edge_of_island(img)
    # a white flower or a lit window can look like a "?": keep only marks that sit in a bubble
    if DAY:
        # white details (the lit window of a building, the book in the pet's hands) can look like a "?": keep the five that
        # look most like a bubble
        scored = sorted(((looks_like_bubble(img, *b), b) for b in find_bubbles(img, right)), key=lambda t: -t[0])
        print(f"  {rid} bubble scores:", [round(sc, 2) for sc, _ in scored[:7]])
        bubbles = [b for sc, b in scored[:5] if sc > 0.2]   # real bubbles score 0.29 and up, the false ones 0.11 and below
    else:
        bubbles = [b for b in find_bubbles(img, right) if looks_like_bubble(img, *b) > 0.18][:5]
    if len(bubbles) < 5 and not DAY: bubbles = match_bubbles(img, right) or bubbles
    if len(bubbles) < 5: print(f"{rid}: found only {len(bubbles)} bubbles; the app needs five spots - check the picture")
    clean = paint_out(img, bubbles, extra)
    if lay: left, top, right, bottom = lay["crop"]
    else: left, top, bottom = 0, int(H * 0.075), int(H * 0.95)
    crop = clean[top:bottom, left:right]
    ch, cw = crop.shape[:2]
    if lay and lay.get("scale", 1) > 1: crop = enlarge(crop, lay["scale"])
    os.makedirs(os.path.join(OUT, rid), exist_ok=True)
    cv2.imwrite(os.path.join(OUT, rid, "island%s.png" % SUFFIX), crop)
    spots = sorted((((x - left) / cw, (y - top) / ch) for x, y, _ in bubbles), key=lambda s: s[1])   # furthest (highest) first
    r = float(np.mean([b[2] for b in bubbles])) if bubbles else 50.0
    data = {"spots": [[round(x, 4), round(y, 4)] for x, y in spots], "bubbleR": round(r / cw, 4), "tileDy": round(r * 1.4 / ch, 4)}
    with open(os.path.join(OUT, rid, "spots%s.json" % SUFFIX), "w", encoding="utf-8") as f: json.dump(data, f)
    print(f"{rid}: {len(bubbles)} bubbles at {[(round(x), round(y)) for x, y, _ in bubbles]}, island {crop.shape[1]}x{crop.shape[0]}")


if __name__ == "__main__":
    want = set(a for a in sys.argv[1:] if not a.startswith("--"))
    for name in sorted(os.listdir(SRC)):
        rid, ext = os.path.splitext(name)
        if ext.lower() not in (".png", ".jpg", ".jpeg", ".webp") or rid not in IDS or (rid == "seoul" and not DAY): continue
        if want and rid not in want: continue
        make(rid, os.path.join(SRC, name))
