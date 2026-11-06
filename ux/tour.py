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
for tab, nm in [("الشكل", "look"), ("الكتابة", "text"), ("المونتاج", "pace"), ("متقدم", "adv")]:
    if tap(tab, exact=True): page("tpl_" + nm)
# change something and save -> the before/after sheet
tap("المونتاج", exact=True)
if tap("سريع", exact=False):
    tap("متوازن")
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
    def exports():
        ls = adb("shell", "ls", "/sdcard/Movies/ReactionRecorder/", out=True).decode(errors="ignore")
        return [x for x in ls.split() if x.endswith(".mp4") and not x.startswith(".")]
    def export_and_wait(n_before, name):
        tap("٣ التصدير", scroll=False)
        tap("360p للفحص")
        if not tap("صدّر الفيديو"): return
        time.sleep(20); shot("exporting_" + name)
        t0 = time.time(); ready = False
        # the video is made but held back: watch it, then save the same file
        def seek_save():
            # the save button is under the export card: look down the page
            for _ in range(6):
                if find("✓ احفظه بالمعرض") is not None: return True
                swipe_up()
            swipe_down_all(); return False
        while time.time() - t0 < 1500:
            if seek_save(): ready = True; break
            time.sleep(15)
        log(f"-- export {name} {'ready' if ready else 'TIMED OUT'} after {time.time() - t0:.0f}s")
        shot("export_ready_" + name)
        log(f"-- files before saving: {len(exports())} (was {n_before})")
        if tap("▶ شوفه", scroll=False):
            time.sleep(8); shot("watch_final_" + name)
            if find("✓ احفظه بالمعرض") is None: back(); time.sleep(3)
        seek_save()
        tap("✓ احفظه بالمعرض")
        t1 = time.time(); done = False
        while time.time() - t1 < 60:
            if len(exports()) > n_before: done = True; break
            time.sleep(3)
        log(f"-- saved {name}: {'yes' if done else 'NO'}")
        time.sleep(3); shot("export_done_" + name)
    # 1) intro file (4 colours) turned 90° + flipped, then a grey bridge clip, then the episode
    tap("٣ التصدير", scroll=False)
    if tap("الصورة:"):
        time.sleep(3); shot("fx_before")
        tap("دوّر ٩٠°", scroll=False); time.sleep(2)
        tap("اقلب", scroll=False); time.sleep(2); shot("fx_after")
        tap("تم", exact=True, scroll=False); time.sleep(2)
    page("export_with_clips", 6)
    export_and_wait(len(exports()), "1")
    # 2) intro from the episode, edited by hand: سامي from 0, أحمد from 3 s, everyone from 6 s
    tap("٣ التصدير", scroll=False)
    if tap("مقطع من الحلقة"):
        if tap("اختار المقطع ومين يطلع"):
            time.sleep(5); shot("intro_picker")
            tap("سامي من هون")
            for _ in range(3): tap("+1", exact=True)
            tap("أحمد من هون")
            for _ in range(3): tap("+1", exact=True)
            tap("الكل مع بعض من هون")
            page("intro_marks", 4)
            tap("خلّي هاد الإنترو"); time.sleep(3)
            page("export_after_intro", 6)
            export_and_wait(len(exports()), "2")
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
        adb("shell", f"input tap {x} {y}; input tap {x} {y}"); time.sleep(4)
        shot("rule_doubletap")
        if find("تعديل القالب") is not None: back(); time.sleep(2)
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
