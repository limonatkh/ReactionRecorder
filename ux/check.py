#!/usr/bin/env python3
"""Checks an exported video against expected.json (see make_session.py).
usage: check.py video.mp4 expected.json project.json  → prints a report, writes report.txt, exits 1 on failure"""
import json, subprocess, sys, statistics

import argparse
ap = argparse.ArgumentParser()
ap.add_argument("vid"); ap.add_argument("expf"); ap.add_argument("projf")
ap.add_argument("--pre", type=float, default=0.0, help="seconds of clips before the episode")
ap.add_argument("--seg", nargs="*", default=[], help="a-b=LABEL checks on video time (A, B, AB, W, G grey, Q rotated+flipped intro)")
ap.add_argument("--report", default="report.txt")
args = ap.parse_args()
vid, expf, projf = args.vid, args.expf, args.projf
exp = json.load(open(expf))
try:
    proj = json.load(open(projf))
except Exception:
    proj = {}
A0 = float(proj.get("a", 0.0) or 0.0) - args.pre
FPS = 4
out = []
def log(s=""):
    print(s); out.append(s)

def frames(w, h, gray=False):
    fmt = "gray" if gray else "rgb24"
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", vid, "-vf", f"fps={FPS},scale={w}:{h}:flags=area", "-f", "rawvideo", "-pix_fmt", fmt, "-"],
                         capture_output=True, check=True).stdout
    n = w * h * (1 if gray else 3)
    return [raw[i:i + n] for i in range(0, len(raw) - n + 1, n)]

W, H = 64, 36
def colour(px):
    r, g, b = px
    if r > 150 and g < 90 and b < 90: return "A"
    if b > 150 and r < 90 and g < 90: return "B"
    if g > 90 and r < 70 and b < 70: return "W"
    if abs(r - g) < 20 and abs(g - b) < 20 and 90 < r < 170: return "G"
    return None

def region(fr, x0, x1, y0, y1):
    c = {}
    for y in range(y0, y1):
        for x in range(x0, x1):
            i = (y * W + x) * 3
            k = colour(fr[i:i + 3])
            if k: c[k] = c.get(k, 0) + 1
    return max(c, key=c.get) if c else None

def label(fr):
    t, b_ = region(fr, 0, W, 2, H // 2 - 1), region(fr, 0, W, H // 2 + 1, H - 2)
    l, r = region(fr, 2, W // 2 - 1, 0, H), region(fr, W // 2 + 1, W - 2, 0, H)
    s = {x for x in (t, b_, l, r) if x}
    if s == {"A", "B"} and (t != b_ or l != r): return "AB"
    if len(s) == 1: return s.pop()
    return "?" if not s else "+".join(sorted(s))

RAW = frames(W, H)
labs = [label(f) for f in RAW]

def hue(px):
    r, g, b = px
    if r > 180 and g > 180 and b < 90: return "Y"
    if r < 90 and g > 180 and b > 180: return "C"
    if r > 180 and g < 90 and b > 180: return "M"
    if r > 180 and 70 < g < 170 and b < 70: return "O"
    return None

def quad(fr):
    """the centred picture's 4 quadrants (portrait 9:16 inside 16:9 after a 90° turn)"""
    fw = H * 9 / 16
    xs = [int(W / 2 - fw / 4), int(W / 2 + fw / 4)]; ys = [int(H * 0.25), int(H * 0.75)]
    out = ""
    for y in ys:
        for x in xs:
            i = (y * W + x) * 3; out += hue(fr[i:i + 3]) or "."
    return out
# intro 4 colours TL Y, TR C, BL M, BR O → turned 90° clockwise then flipped left-right → TL Y, TR M, BL C, BR O
QWANT = "YMCO"
# grid spacing (white lines every 160 source px) on a 320-wide grey copy → zoom/movement check
GW, GH = 320, 180
def spacing(fr):
    row = GH // 3
    cols = [sum(fr[y * GW + x] for y in range(row - 10, row + 10)) / 20 for x in range(GW)]
    m = statistics.mean(cols)
    peaks = [x for x in range(1, GW - 1) if cols[x] > m + 40 and cols[x] >= cols[x - 1] and cols[x] >= cols[x + 1]]
    pk = []
    for x in peaks:
        if not pk or x - pk[-1] > 4: pk.append(x)
    if len(pk) < 3: return None, None
    d = [pk[i + 1] - pk[i] for i in range(len(pk) - 1)]
    return statistics.median(d), pk[0] % statistics.median(d)
sp = [spacing(f) for f in frames(GW, GH, gray=True)]

log(f"video: {len(labs) / FPS:.1f}s, project start a={A0:.2f}s")
# timeline (one char per 0.5 s)
line = ""
for i in range(0, len(labs), 2):
    x = labs[i]; line += {"A": "a", "B": "b", "AB": "S", "W": "w", "G": "g"}.get(x, ".")
log("timeline (0.5s/char, a=أحمد b=سامي S=split w=wide g=grey .=other):")
for i in range(0, len(line), 40): log(f"  {i / 2:5.0f}s  {line[i:i + 40]}")

ok = True
if args.seg:
    log("\nbefore the episode:")
    for spec in args.seg:
        rng, want = spec.split("=")
        a, b = map(float, rng.split("-"))
        idx = [i for i in range(len(labs)) if a <= i / FPS < b]
        if want == "Q":
            got = [quad(RAW[i]) for i in idx]
            hit = sum(1 for g in got if g == QWANT) / max(1, len(idx))
            detail = {g: got.count(g) for g in set(got)}
        else:
            hit = sum(1 for i in idx if labs[i] == want) / max(1, len(idx))
            detail = {}
            for i in idx: detail[labs[i]] = detail.get(labs[i], 0) + 1
        passed = hit >= 0.85 and idx
        ok &= bool(passed)
        log(f"  {'OK ' if passed else 'BAD'} {a:5.1f}-{b:5.1f} want {want:<4} got {hit * 100:3.0f}%  {detail}")
log("\nscenes:")
for sc in exp["scenes"]:
    idx = [i for i in range(len(labs)) if sc["a"] <= A0 + i / FPS < sc["b"]]
    if not idx: log(f"  ?? {sc['name']}: not in the video"); ok = False; continue
    hit = sum(1 for i in idx if labs[i] == sc["expect"]) / len(idx)
    got = {}
    for i in idx: got[labs[i]] = got.get(labs[i], 0) + 1
    passed = hit >= 0.85
    ok &= passed
    log(f"  {'OK ' if passed else 'BAD'} {sc['a']:5.1f}-{sc['b']:5.1f} {sc['name']:<26} want {sc['expect']:<2}  got {hit * 100:3.0f}%  {got}")

# cuts: count camera changes and the shortest shot
chg = [i for i in range(1, len(labs)) if labs[i] != labs[i - 1] and labs[i] in ("A", "B", "AB", "W") and labs[i - 1] in ("A", "B", "AB", "W")]
runs = []
s0 = 0
for i in chg: runs.append((i - s0) / FPS); s0 = i
log(f"\ncuts: {len(chg)}  shortest shot: {min(runs) if runs else 0:.2f}s")

# still framing: inside one-camera frames the grid must not move or scale
still = [(sp[i][0], sp[i][1]) for i in range(min(len(sp), len(labs))) if labs[i] in ("A", "B", "W") and sp[i][0]]
if still:
    s = [x[0] for x in still]
    med = statistics.median(s)
    moved = sum(1 for x in s if abs(x - med) > med * 0.06)
    log(f"framing: grid spacing median {med:.1f}px, frames off by >6%: {moved}/{len(s)}")
    if moved > len(s) * 0.03: ok = False; log("BAD framing moves (zoom / follow) although the template says still")
else:
    log("framing: could not measure")

shots = proj.get("shots") or []
log(f"\nproject shots ({len(shots)}):")
names = {0: "A", 1: "B"}
for s in shots:
    t = {0: "full", 1: "pip", 2: "multi", 3: "wide"}.get(s.get("t"), str(s.get("t")))
    log(f"  {s['s']:6.2f}-{s['e']:6.2f} {t:<5} {names.get(s.get('m'), s.get('m'))} {s.get('o')} {'LOCK' if s.get('l') else ''} {s.get('g', '')}")
log("\nRESULT: " + ("PASS" if ok else "FAIL"))
open(args.report, "w").write("\n".join(out))
sys.exit(0 if ok else 1)
