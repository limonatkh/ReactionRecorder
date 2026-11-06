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
        adb("shell", "rm", "-f", "/sdcard/ui.xml")
        r = subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
        x = adb("exec-out", "cat", "/sdcard/ui.xml", out=True)
        try:
            return ET.fromstring(x)
        except Exception:
            log("!! dump failed: " + (r.stdout + r.stderr).decode(errors="ignore").strip()[:200])
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
        adb("shell", "input", "swipe", str(W // 2), str(int(H * 0.68)), str(W // 2), str(int(H * 0.97)), "250")
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

def tool(name):
    """the editor's bottom toolbar: items carry their name as content-desc"""
    for nd in dump().iter("node"):
        if (nd.get("content-desc") or "").strip() == name:
            x, y = bounds(nd); adb("shell", "input", "tap", str(x), str(y)); time.sleep(2.2)
            log(f"-- tool «{name}»"); return True
    log(f"!! no tool «{name}»"); return False

def export_btn():
    return tap("تصدير", scroll=False, exact=True)

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
tap("جلسة تصوير جديدة", scroll=False); time.sleep(1); shot("new_session_sheet"); back()
tap("الأجهزة", scroll=False); page("devices_tab"); tap("مشاريعي", scroll=False)
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
    shot("editor_open")
    for st, nm in [("القالب", "tpl"), ("اللقطة", "shot"), ("الصوت", "audio"), ("الكتابة", "text"), ("الإنترو", "intro"), ("المزيد", "more")]:
        if tool(st): page("tool_" + nm, 18)
    # tap the timeline: the shot tool opens on that moment
    nodes = [nd for nd in dump().iter("node") if "HorizontalScrollView" in (nd.get("class") or "")]
    if nodes:
        x, y = bounds(nodes[-1]); adb("shell", "input", "tap", str(x), str(y)); time.sleep(3); shot("timeline_tap")
    if export_btn(): page("tool_export", 18)
    def exports():
        ls = adb("shell", "ls", "/sdcard/Movies/ReactionRecorder/", out=True).decode(errors="ignore")
        return [x for x in ls.split() if x.endswith(".mp4") and not x.startswith(".")]
    def export_and_wait(n_before, name):
        export_btn()
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
    tool("الإنترو")
    if tap("الصورة:"):
        time.sleep(3); shot("fx_before")
        tap("دوّر ٩٠°", scroll=False); time.sleep(2)
        tap("اقلب", scroll=False); time.sleep(2); shot("fx_after")
        tap("تم", exact=True, scroll=False); time.sleep(2)
    page("intro_with_clips", 6)
    export_and_wait(len(exports()), "1")
    # 2) intro from the episode, edited by hand: سامي from 0, أحمد from 3 s, everyone from 6 s
    tool("الإنترو")
    if tap("مقطع من الحلقة"):
        time.sleep(3); shot("intro_self")
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
    tool("المزيد")
    if tap("قص «الشكل الخاص»"):
        time.sleep(5); page("look_crop", 5); back()
    # double-tap a rule row -> goes to where it's edited
    tool("القالب")
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
    # template edited from inside the project → the open project changes at once
    tool("القالب")
    for _ in range(6):
        if find("أقل لقطة") is not None: break
        swipe_up()
    calm = find("هادئ — أقل لقطة") is not None
    want, want_txt = ("سريع", "سريع — أقل لقطة") if calm else ("هادئ", "هادئ — أقل لقطة")
    log(f"-- pace before: {'calm' if calm else 'not calm'} → setting {want}")
    if tap("عدّل «"):
        time.sleep(4)
        # the tab row is the topmost «المونتاج» (a section below has the same title)
        swipe_down_all()
        nodes = [nd for nd in dump().iter("node") if (nd.get("text") or "").strip() == "المونتاج"]
        if nodes:
            nd = min(nodes, key=lambda x: bounds(x)[1]); x, y = bounds(nd)
            adb("shell", "input", "tap", str(x), str(y)); time.sleep(2.5); log("-- tap tab «المونتاج»")
        page("tpl_from_project", 4)
        swipe_down_all()
        for _ in range(4):
            if find(want, exact=True) is not None: break
            swipe_up()
        if find(want, exact=True) is None:
            # the editing section starts collapsed: its header is the LOWER «المونتاج» (the upper one is the tab)
            swipe_down_all()
            nodes = [nd for nd in dump().iter("node") if (nd.get("text") or "").strip() == "المونتاج"]
            if len(nodes) >= 2:
                nd = max(nodes, key=lambda x: bounds(x)[1]); x, y = bounds(nd)
                adb("shell", "input", "tap", str(x), str(y)); time.sleep(2.5); log("-- opened the editing section")
        tap(want, exact=True)
        shot("tpl_pace_changed")
        tap("احفظ", scroll=False, exact=True); time.sleep(2)
        if not tap("احفظ على القالب", scroll=False, tries=2):
            # a built-in template can't be overwritten: save as a new one (the project follows it too)
            if tap("احفظ كقالب جديد", scroll=False, tries=2):
                time.sleep(2); shot("tpl_new_name"); tap("احفظ", exact=True, scroll=False)
        time.sleep(8)
        tool("القالب")
        for _ in range(6):
            if find(want_txt) is not None: break
            swipe_up()
        ok = find(want_txt) is not None
        log(f"-- template change applied to the open project: {'YES' if ok else 'NO'}")
        shot("tpl_applied")
    tool("الصوت")
    if tap("راجع الأصوات"): shot("review_sounds"); back()
    tool("القالب")
    # landscape
    adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb("shell", "settings", "put", "system", "user_rotation", "1"); time.sleep(4); size()
    page("edit_landscape", 8)
    adb("shell", "settings", "put", "system", "user_rotation", "0"); time.sleep(4); size()
    back()
# recording screen (no phones connected)
tap("مشاريعي", scroll=False); tap("بودكاست")
for label in ["ابدأ", "التسجيل", "شاشة التسجيل"]:
    if tap(label, tries=2): break
shot("record_screen")
adb("shell", "settings", "put", "system", "user_rotation", "1"); time.sleep(4); size()
shot("record_landscape")
adb("shell", "settings", "put", "system", "user_rotation", "0"); time.sleep(3)
# reaction: screen recording WITHOUT camera
back(); back(); start()
if tap("رياكشن"):
    time.sleep(5); shot("reaction_open")
    for lab in ["Allow", "السماح", "While using the app", "أثناء استخدام التطبيق"]:
        tap(lab, scroll=False, tries=1)
    tap("الشاشة", scroll=False, tries=2)
    if tap("بدون كاميرا", tries=3):
        time.sleep(2); shot("reaction_nocam")
        before = adb("shell", "ls", "/sdcard/Movies/ReactionRecorder/", out=True).decode(errors="ignore").split()
        if tap("سجّل", exact=True, tries=3):
            time.sleep(3)
            for lab in ["Start now", "ابدأ الآن", "START NOW", "Entire screen", "الشاشة بأكملها"]:
                tap(lab, scroll=False, tries=1)
            time.sleep(4); tap("Start now", scroll=False, tries=1); tap("ابدأ الآن", scroll=False, tries=1)
            time.sleep(14); shot("reaction_nocam_recording")
            start()  # back to the app (recording continues in the service)
            time.sleep(3)
            if not tap("إيقاف", tries=2):
                adb("shell", "am", "start", "-n", "com.reactionrecorder/.MainActivity"); time.sleep(3); tap("إيقاف", tries=2)
            time.sleep(10)
            after = adb("shell", "ls", "/sdcard/Movies/ReactionRecorder/", out=True).decode(errors="ignore").split()
            new = [x for x in after if x not in before and x.endswith(".mp4") and not x.startswith(".")]
            log(f"-- reaction without camera: new files {new}")
            if new:
                adb("pull", "/sdcard/Movies/ReactionRecorder/" + new[-1], os.path.join(OUT, "reaction_nocam.mp4"))
            shot("reaction_nocam_done")

# browser join: start the local server in the app, then real Chrome on the runner joins through adb forward
back(); back(); start()
tap("الأجهزة", scroll=False)
if tap("الانضمام من المتصفح"):
    time.sleep(3); tap("شغّل الانضمام من المتصفح"); time.sleep(12); page("webjoin_on", 4)
    adb("forward", "tcp:47643", "tcp:47643"); adb("forward", "tcp:47680", "tcp:47680")
    import shlex
    env = os.environ.copy()
    for k in list(env):
        if "proxy" in k.lower(): del env[k]
    ca = subprocess.run(["curl", "-s", "--noproxy", "*", "-o", "/tmp/rrca.der", "-w", "%{http_code}", "http://127.0.0.1:47680/ca.crt"], capture_output=True, text=True).stdout
    log(f"-- CA download from the phone: {ca}")
    home = "/tmp/rrhome"; os.makedirs(home + "/.pki/nssdb", exist_ok=True)
    subprocess.run(["certutil", "-N", "-d", "sql:" + home + "/.pki/nssdb", "--empty-password"])
    subprocess.run(["certutil", "-A", "-d", "sql:" + home + "/.pki/nssdb", "-n", "rr", "-t", "C,,", "-i", "/tmp/rrca.der"])
    nb = subprocess.Popen(["node", "browser.js", home, "20", OUT], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
    approved = False; t0 = time.time()
    while time.time() - t0 < 150 and not approved:
        time.sleep(5)
        if tap("وافق", tries=3): approved = True; shot("webjoin_approved")
    out, _ = nb.communicate(timeout=400)
    log("-- browser:\n" + out)
    time.sleep(3); page("webjoin_after", 6)
log("\nDONE")
