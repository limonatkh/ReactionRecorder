#!/usr/bin/env python3
"""Builds a fake podcast session in the exact on-disk format the app expects, under ./out/<sid>/.

Every podcast situation is staged at a known time (see SCENES), each camera is a flat colour with a
white grid (A red, B blue, wide green) so the exported video can be checked frame by frame:
which camera is on screen, split or not, and whether the picture moved (zoom / head-following).
expected.json describes what the montage MUST look like in each scene."""
import json, os, subprocess, wave, array

SID = "p1762000000000"
START = 1_000_000_000_000          # elapsedRealtime ns of the session start
RATE = 22050
OUT = os.path.abspath("out")
os.makedirs(OUT, exist_ok=True)

# who talks when (seconds). 0 = أحمد (camA), 1 = سامي (camB)
TALK = {0: [], 1: []}
def t(p, a, b): TALK[p].append((a, b))
SCENES = []
def scene(a, b, name, expect):
    SCENES.append({"a": a, "b": b, "name": name, "expect": expect})

# 1 monologue with backchannels (آه) from the other → stays on A, no cut
t(0, 1.0, 12.0); t(1, 4.0, 4.5); t(1, 8.0, 8.6)
scene(1.0, 12.0, "monologue + backchannels", "A")
# 2 failed interruption: B tries 1 s, A keeps going → still A
t(0, 12.3, 20.0); t(1, 14.0, 15.0)
scene(12.0, 19.0, "failed interruption", "A")
# 3 handover with 0.5 s overlap → clean cut to B, no split
t(1, 19.5, 32.0)
scene(21.0, 31.5, "B takes the floor", "B")
# 4 crosstalk / argument 8 s → split (template default: both together)
t(0, 32.0, 40.0)
scene(33.5, 39.5, "talking at once", "AB")
# 5 A alone again
t(0, 40.0, 50.0)
scene(42.0, 49.5, "A again", "A")
# 6 fast ping-pong, 1.2 s turns → split instead of flicker
x, who = 50.0, 1
while x < 62.0:
    t(who, x, x + 1.2); x += 1.2; who = 1 - who
scene(53.5, 61.5, "fast exchange", "AB")
# 7 long silence 7 s → wide after ~4 s
scene(67.0, 68.8, "long silence", "W")
# 8 B monologue to the end
t(1, 69.0, 82.0)
scene(70.5, 81.5, "B monologue", "B")
DUR = 84.0

def speech(voice, seconds, seed):
    """espeak speech, long enough, then cut to exactly `seconds`"""
    words = ["مرحبا كيف الحال اليوم رح نحكي عن موضوع مهم", "والفكرة إنه الواحد لازم يجرب ويتعلم من غلطه",
             "بس الخطة مهمة كمان والناس اللي حواليك", "خلينا نحكي عن التمويل ومن وين بتيجي المصاري"]
    text = " ".join(words[(seed + i) % 4] for i in range(6))
    subprocess.run(["espeak-ng", "-v", voice, "-s", "165", "-w", "/tmp/sp.wav", text], check=True)
    with wave.open("/tmp/sp.wav") as w:
        assert w.getframerate() == RATE
        pcm = array.array("h", w.readframes(w.getnframes()))
    n = int(seconds * RATE)
    while len(pcm) < n: pcm.extend(pcm)
    return pcm[:n]

total = int(DUR * RATE)
tracks = [array.array("h", [0] * total), array.array("h", [0] * total)]
voices = ["ar", "ar+m3"]
k = 0
for p in (0, 1):
    for a, b in TALK[p]:
        pcm = speech(voices[p], b - a, k); k += 1
        s0 = int(a * RATE)
        for i, v in enumerate(pcm):
            if s0 + i < total: tracks[p][s0 + i] = v

def write(path, a, b, ga=0.9, gb=0.22):
    mix = array.array("h", [max(-32768, min(32767, int(a[i] * ga + b[i] * gb))) for i in range(total)])
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(mix.tobytes())

devs = [("camA", "Phone A", "أحمد", "red"), ("camB", "Phone B", "سامي", "blue"), ("camW", "Phone W", "", "green")]
sdir = os.path.join(OUT, SID)
os.makedirs(sdir, exist_ok=True)
for i, (dev, name, person, colour) in enumerate(devs):
    d = os.path.join(sdir, dev); os.makedirs(d, exist_ok=True)
    if i < 2: write("/tmp/raw.wav", tracks[i], tracks[1 - i])
    else: write("/tmp/raw.wav", tracks[0], tracks[1], 0.5, 0.5)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", "/tmp/raw.wav", "-ar", "48000", "-ac", "1", "-c:a", "pcm_s16le",
                    "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", os.path.join(d, "audio.wav")], check=True)
    # flat colour + white grid (lines every 160 px): the grid spacing in the export tells if the picture zoomed
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", f"color=c={colour}:s=1280x720:r=30:d={DUR}",
                    "-vf", "drawgrid=w=160:h=120:t=6:c=white", "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "30",
                    os.path.join(d, "video.mp4")], check=True)
    n48 = int(DUR * 48000)
    meta = {"v": 1, "session": SID, "dev": dev, "role": "both", "name": name,
            "audio": {"file": "audio.wav", "rate": 48000, "frames": n48, "f0": 0, "t0": START, "f1": n48, "t1": START + int(DUR * 1e9), "tsOk": True, "fallbackStart": START},
            "video": {"file": "video.mp4", "startBoot": START, "w": 1280, "h": 720, "mirror": False, "front": True, "flips": []}}
    json.dump(meta, open(os.path.join(d, "meta.json"), "w"))

setup = {"people": [{"n": "أحمد", "c": "camA", "m": "camA"}, {"n": "سامي", "c": "camB", "m": "camB"}], "wides": ["camW"]}
sess = {"id": SID, "wall": 1762000000000, "start": START, "end": START + int(DUR * 1e9), "uri": "", "name": "", "micGain": 1.0, "srcGain": 0.8,
        "wav": False, "ended": True, "merged": False, "mergedUri": "", "overlayUri": "", "kind": "podcast", "extra": json.dumps(setup, ensure_ascii=False),
        "pauses": [], "devices": [{"id": dev, "name": name, "role": "both", "started": True, "uploaded": True, "stopped": True, "gain": 1.0, "nudge": 0,
                                    "useA": True, "useV": True, "vlat": 0, "rot": 0, "pos": -1, "size": 1.0, "samples": [[START, 0, 1000000]]} for dev, name, _, _ in devs]}
json.dump(sess, open(os.path.join(sdir, "session.json"), "w"), ensure_ascii=False)
json.dump({"scenes": SCENES, "talk": TALK, "dur": DUR}, open(os.path.join(OUT, "expected.json"), "w"), ensure_ascii=False, indent=1)
print(f"session {SID}: {DUR:.1f}s, {len(SCENES)} scenes")
