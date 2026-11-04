#!/usr/bin/env python3
"""Walks through the app on the emulator like a user: taps by visible text, scrolls whole pages,
and saves a screenshot + the on-screen text of every step into ./shots/."""
import hashlib, os, re, subprocess, sys, time, xml.etree.ElementTree as ET

PKG = "com.reactionrecorder"
OUT = os.path.abspath("shots"); os.makedirs(OUT, exist_ok=True)
LOG = open(os.path.join(OUT, "log.txt"), "w", encoding="utf-8")
n = 0

def adb(*a, out=False):
    r = subprocess.run(["adb", *a], capture_output=True)
    return r.stdout if out else r.returncode

def log(s):
    print(s); LOG.write(s + "\n"); LOG.flush()

def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        x = adb("exec-out", "cat", "/sdcard/ui.xml", out=True)
        try:
            return ET.fromstring(x)
        except Exception:
            time.sleep(1)
    return ET.fromstring("<hierarchy/>")

def texts(root):
    return [nd.get("text") or nd.get("content-desc") for nd in root.iter("node") if (nd.get("text") or nd.get("content-desc"))]

def shot(name, force=True):
    """screenshot + texts; returns a hash of what's on screen (texts only — the clock changes)"""
    global n
    t = texts(dump())
    h = hashlib.md5(" | ".join(t).encode()).hexdigest()
    n += 1
    png = adb("exec-out", "screencap", "-p", out=True)
    fn = f"{n:03d}_{name}.png"
    open(os.path.join(OUT, fn), "wb").write(png)
    log(f"\n=== {fn}\n" + " | ".join(t))
    return h

def bounds(nd):
    m = re.findall(r"\d+", nd.get("bounds", "[0,0][0,0]"))
    x0, y0, x1, y1 = map(int, m)
    return (x0 + x1) // 2, (y0 + y1) // 2

def find(text, exact=False):
    root = dump()
    for nd in root.iter("node"):
        t = (nd.get("text") or "") + " " + (nd.get("content-desc") or "")
        if (t.strip() == text) if exact else (text in t):
            return nd
    return None

def tap(text, scroll=True, exact=False, tries=8):
    if scroll and find(text, exact) is None: swipe_down_all()
    for i in range(tries):
        nd = find(text, exact)
        if nd is not None:
            x, y = bounds(nd)
            adb("shell", "input", "tap", str(x), str(y)); time.sleep(2.2)
            log(f"-- tap «{text}»")
            return True
        if not scroll: break
        swipe_up()
    log(f"!! not found «{text}»")
    return False

W, H = 1080, 2400
def swipe_up():
    adb("shell", "input", "swipe", str(W // 2), str(int(H * 0.75)), str(W // 2), str(int(H * 0.3)), "450"); time.sleep(1.2)

def swipe_down_all():
    for _ in range(12):
        adb("shell", "input", "swipe", str(W // 2), str(int(H * 0.55)), str(W // 2), str(int(H * 0.95)), "250")
    time.sleep(1)

def page(name, max_pages=14):
    """the whole scrolling page, top to bottom"""
    swipe_down_all()
    seen = set()
    for i in range(max_pages):
        t = texts(dump()); h = hashlib.md5(" | ".join(t).encode()).hexdigest()
        if h in seen: break
        seen.add(h)
        shot(f"{name}_{i + 1}")
        swipe_up()

def back():
    adb("shell", "input", "keyevent", "4"); time.sleep(2)

def wait_text_gone(text, secs):
    t0 = time.time()
    while time.time() - t0 < secs:
        if find(text) is None: return True
        time.sleep(5)
    return False

def wait_text(text, secs):
    t0 = time.time()
    while time.time() - t0 < secs:
        if find(text) is not None: return True
        time.sleep(4)
    return False

def start():
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(6)

def size():
    global W, H
    s = adb("shell", "wm", "size", out=True).decode()
    m = re.findall(r"(\d+)x(\d+)", s)
    if m: W, H = map(int, m[-1])

size()
start()
page("home_projects")
tap("تصوير", scroll=False); page("capture")
tap("بودكاست"); page("podcast_setup")
back()
tap("الإعدادات", scroll=False); page("settings")
tap("قوالب المونتاج"); page("templates")
# edit the first template: open whatever editor button exists
for label in ["عدّل", "تعديل", "✎"]:
    if tap(label, tries=2): break
for tab, nm in [("الشكل", "look"), ("الكتابة", "text"), ("الإيقاع", "pace"), ("متقدم", "adv")]:
    if tap(tab, exact=True): page("tpl_" + nm)
# change something and save -> the before/after sheet
tap("الإيقاع", exact=True)
if tap("كاميرا اللي بيحكي بس"):
    tap("مونتاج ذكي")
tap("احفظ", scroll=False, exact=True); time.sleep(2); page("tpl_save_sheet", 4)
tap("الغي التعديلات")
back()
tap("إعدادات التطبيق"); page("app_settings"); back()
# the project
tap("مشاريعي", scroll=False); swipe_down_all(); shot("projects_again")
if tap("ابدأ المونتاج") or tap("أكمل المونتاج"):
    wait_text_gone("جارٍ التحميل", 600)
    time.sleep(10)
    for st, nm in [("١ التجهيز", "setup"), ("٢ المونتاج", "edit"), ("٣ التصدير", "export")]:
        if tap(st, scroll=False): page("step_" + nm, 18)
    # a moment on the timeline -> the shot sheet
    tap("٢ المونتاج", scroll=False); swipe_down_all()
    nd = find("الخط الزمني")
    if nd is not None:
        x, y = bounds(nd); adb("shell", "input", "tap", str(W // 4), str(y - 170)); time.sleep(3)
        shot("timeline_tapped")
        if tap("خصّص هاللقطة"): page("shot_sheet", 3); back()
    # special-look crop editor
    tap("١ التجهيز", scroll=False)
    if tap("قص «الشكل الخاص»"):
        time.sleep(5); page("look_crop", 5); back()
    # double-tap a rule row -> goes to where it's edited
    tap("٢ المونتاج", scroll=False)
    nd = None
    for _ in range(8):
        nd = find("طريقة المونتاج", exact=True)
        if nd is not None: break
        swipe_up()
    if nd is not None:
        x, y = bounds(nd)
        adb("shell", "input", "tap", str(x), str(y)); time.sleep(0.15); adb("shell", "input", "tap", str(x), str(y)); time.sleep(4)
        shot("rule_doubletap"); back(); time.sleep(2)
    # intro from the episode -> the clip browser with sound
    tap("٣ التصدير", scroll=False)
    if tap("مقطع من الحلقة"):
        if tap("اختار المقطع"):
            time.sleep(4); shot("intro_picker")
            tap("▶", exact=True, scroll=False); time.sleep(4); shot("intro_playing")
            tap("+5", exact=True); tap("البداية هون"); tap("+5", exact=True); tap("النهاية هون"); shot("intro_marked")
            tap("خلّي هاد الإنترو"); time.sleep(3); page("export_after_intro", 4)
    tap("٢ المونتاج", scroll=False)
    if tap("خيارات متقدمة"): page("edit_advanced", 22)
    tap("١ التجهيز", scroll=False)
    if tap("راجع الأصوات"): shot("review_sounds"); back()
    tap("راجع وصدّر", scroll=False); page("review_export"); back()
    # landscape
    adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb("shell", "settings", "put", "system", "user_rotation", "1"); time.sleep(4); size()
    page("edit_landscape", 8)
    adb("shell", "settings", "put", "system", "user_rotation", "0"); time.sleep(4); size()
    back()
# recording screen (no phones connected)
tap("تصوير", scroll=False); tap("بودكاست")
for label in ["ابدأ", "التسجيل", "شاشة التسجيل"]:
    if tap(label, tries=2): break
shot("record_screen")
adb("shell", "settings", "put", "system", "user_rotation", "1"); time.sleep(4); size()
shot("record_landscape")
adb("shell", "settings", "put", "system", "user_rotation", "0"); time.sleep(3)
log("\nDONE")
