#!/usr/bin/env python3
"""Builds a fake 2-person podcast session (speech by espeak-ng, video from face photos)
in the exact on-disk format the app expects, under ./out/<sid>/."""
import json, os, subprocess, sys, wave, array, shutil

SID = "p1762000000000"
START = 1_000_000_000_000          # elapsedRealtime ns of the session start
RATE = 22050
OUT = os.path.abspath("out")
os.makedirs(OUT, exist_ok=True)

# (speaker, text) — a real-ish conversation: long turns, short comments, an interruption, a fast exchange
SCRIPT = [
    (0, "أهلا وسهلا فيكم بحلقة جديدة من البودكاست، اليوم رح نحكي عن موضوع مهم كتير وهو كيف الواحد يبدأ مشروعه الخاص من الصفر"),
    (1, "اه"),
    (0, "وأول إشي لازم نفهمه إنه الفكرة لحالها ما بتكفي، لازم يكون في خطة واضحة وناس بتساعدك"),
    (1, "صح صح"),
    (1, "بس أنا بشوف إنه أهم إشي هو التجربة، يعني الواحد يجرب ويغلط ويتعلم من غلطه، الخطة بتيجي بعدين"),
    (0, "لا لا مش متفق معك"),
    (1, "استنى خليني أكمل، أنا جربت ثلاث مشاريع قبل ما أنجح بواحد"),
    (0, "طيب"),
    (1, "وكل مرة كنت أتعلم إشي جديد عن الناس وعن السوق"),
    (0, "ممتاز"),
    (1, "شو رأيك"),
    (0, "معك حق"),
    (1, "يعني"),
    (0, "بالزبط"),
    (0, "خلينا نحكي عن التمويل هلأ، لأنه هاد السؤال اللي الكل بيسأله، من وين بجيب المصاري لأبدأ"),
    (1, "امم"),
    (0, "في كذا طريقة، أولها التوفير الشخصي، وثانيها الشركاء، وثالثها المستثمرين"),
    (1, "والقروض كمان"),
    (0, "صح والقروض بس لازم الواحد ينتبه منها كتير"),
]

def say(text, voice, path):
    subprocess.run(["espeak-ng", "-v", voice, "-s", "155", "-w", path, text], check=True)
    with wave.open(path) as w:
        assert w.getframerate() == RATE, w.getframerate()
        return array.array("h", w.readframes(w.getnframes()))

tracks = [array.array("h"), array.array("h")]
t = int(RATE * 1.0)
voices = ["ar", "ar+m3"]
for i, (p, text) in enumerate(SCRIPT):
    pcm = say(text, voices[p], f"/tmp/u{i}.wav")
    # short comments overlap the other's talk a bit; long turns follow a short gap
    if len(pcm) < RATE * 1.2 and i > 0:
        start = max(0, t - int(RATE * 0.8))
    elif text.startswith("لا لا") or text.startswith("استنى"):
        start = max(0, t - int(RATE * 1.0))   # interruption
    else:
        start = t + int(RATE * 0.35)
    for tr in tracks:
        need = start + len(pcm) - len(tr)
        if need > 0: tr.extend([0] * need)
    for k, v in enumerate(pcm):
        tracks[p][start + k] = max(-32768, min(32767, tracks[p][start + k] + v))
    t = max(t, start + len(pcm))
total = t + RATE * 2
for tr in tracks: tr.extend([0] * (total - len(tr)))
dur = total / RATE

# each phone's mic hears its own person loud and the other one quietly
def write(path, a, b):
    mix = array.array("h", [max(-32768, min(32767, int(a[k] * 0.9 + b[k] * 0.22))) for k in range(total)])
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(mix.tobytes())

devs = [("camA", "Phone A", "أحمد"), ("camB", "Phone B", "سامي")]
sdir = os.path.join(OUT, SID)
os.makedirs(sdir, exist_ok=True)
for i, (dev, name, person) in enumerate(devs):
    d = os.path.join(sdir, dev); os.makedirs(d, exist_ok=True)
    write("/tmp/raw.wav", tracks[i], tracks[1 - i])
    # 48 kHz, plain 44-byte header (the app's reader assumes it)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", "/tmp/raw.wav", "-ar", "48000", "-ac", "1", "-c:a", "pcm_s16le",
                    "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", os.path.join(d, "audio.wav")], check=True)
    face = f"face{i}.jpg"
    src = ["-loop", "1", "-i", face] if os.path.exists(face) and os.path.getsize(face) > 5000 else ["-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30"]
    vf = ("scale=1280:720:force_original_aspect_ratio=increase,crop=1280:720," if src[0] == "-loop" else "") + \
         f"drawtext=text='{name}':fontcolor=white:fontsize=36:x=30:y=30:box=1:boxcolor=black@0.5"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *src, "-t", f"{dur:.2f}", "-r", "30", "-vf", vf,
                    "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "30", os.path.join(d, "video.mp4")], check=True)
    n48 = int(dur * 48000)
    meta = {"v": 1, "session": SID, "dev": dev, "role": "both", "name": name,
            "audio": {"file": "audio.wav", "rate": 48000, "frames": n48, "f0": 0, "t0": START, "f1": n48, "t1": START + int(dur * 1e9), "tsOk": True, "fallbackStart": START},
            "video": {"file": "video.mp4", "startBoot": START, "w": 1280, "h": 720, "mirror": False, "front": True, "flips": []}}
    json.dump(meta, open(os.path.join(d, "meta.json"), "w"))

setup = {"people": [{"n": devs[0][2], "c": "camA", "m": "camA"}, {"n": devs[1][2], "c": "camB", "m": "camB"}], "wides": []}
sess = {"id": SID, "wall": 1762000000000, "start": START, "end": START + int(dur * 1e9), "uri": "", "name": "", "micGain": 1.0, "srcGain": 0.8,
        "wav": False, "ended": True, "merged": False, "mergedUri": "", "overlayUri": "", "kind": "podcast", "extra": json.dumps(setup, ensure_ascii=False),
        "pauses": [], "devices": [{"id": dev, "name": name, "role": "both", "started": True, "uploaded": True, "stopped": True, "gain": 1.0, "nudge": 0,
                                    "useA": True, "useV": True, "vlat": 0, "rot": 0, "pos": -1, "size": 1.0, "samples": [[START, 0, 1000000]]} for dev, name, _ in devs]}
json.dump(sess, open(os.path.join(sdir, "session.json"), "w"), ensure_ascii=False)
print(f"session {SID}: {dur:.1f}s")
