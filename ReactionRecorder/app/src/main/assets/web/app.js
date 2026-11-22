// ReactionRecorder — الانضمام من المتصفح والفحص (بدون إنترنت، بدون مكتبات خارجية)
"use strict";
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var show = function (id, on) { $(id).classList.toggle("hide", !on); };
  var store = { get: function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } }, set: function (k, v) { try { localStorage.setItem(k, v); } catch (e) {} } };
  function rid() { var a = new Uint8Array(12); crypto.getRandomValues(a); return Array.prototype.map.call(a, function (b) { return ("0" + b.toString(16)).slice(-2); }).join(""); }
  var id = store.get("rr_id") || ("d-" + rid()); store.set("rr_id", id);
  var token = "";
  var R = { v: 1, id: id, ua: navigator.userAgent, platform: platform(), started: new Date().toISOString(), tests: {}, events: [] };
  function platform() {
    var ua = navigator.userAgent;
    if (/iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return "ios";
    if (/Android/.test(ua)) return "android"; if (/Windows/.test(ua)) return "windows"; if (/Macintosh/.test(ua)) return "mac"; return "other";
  }
  function ev(what, extra) { var e = { t: Math.round(performance.now()), what: what }; if (extra) e.x = extra; R.events.push(e); if (R.events.length > 500) R.events.shift(); }
  document.addEventListener("visibilitychange", function () { ev("visibility", document.visibilityState); });
  window.addEventListener("pagehide", function () { ev("pagehide"); });
  window.addEventListener("online", function () { ev("online"); }); window.addEventListener("offline", function () { ev("offline"); });

  function api(path, opt) {
    opt = opt || {};
    var sep = path.indexOf("?") < 0 ? "?" : "&";
    return fetch(path + (token ? sep + "token=" + token : ""), opt).then(function (r) { return r.json().then(function (j) { j._status = r.status; return j; }); });
  }

  // ───── نتائج على الشاشة ─────
  function row(key, label, status, detail) {
    var tr = $("r_" + key);
    if (!tr) { tr = document.createElement("tr"); tr.id = "r_" + key; $("res").appendChild(tr); }
    var cls = status === "ok" ? "ok" : status === "bad" ? "bad" : status === "warn" ? "warn" : "sub";
    var mark = status === "ok" ? "✓" : status === "bad" ? "✗" : status === "warn" ? "⚠" : "…";
    tr.innerHTML = '<td class="k">' + label + '</td><td><span class="' + cls + '">' + mark + "</span> " + (detail || "") + "</td>";
  }
  function put(key, status, data) { R.tests[key] = { status: status, data: data }; }

  // ───── الانضمام ─────
  $("name").value = store.get("rr_name") || "";
  $("joinBtn").onclick = join;
  if (store.get("rr_name")) join();
  function join() {
    var nm = $("name").value.trim() || ("جهاز " + R.platform);
    store.set("rr_name", nm); $("wname").textContent = nm;
    $("joinMsg").textContent = "…";
    api("/api/join", { method: "POST", body: JSON.stringify({ id: id, name: nm, platform: R.platform }) }).then(onState).catch(function () { $("joinMsg").textContent = "ما قدرنا نوصل لجوال المدير."; });
  }
  function onState(s) {
    if (s.label) $("label").textContent = "· " + s.label;
    show("join", false);
    if (s.state === "approved") { token = s.token; show("wait", false); show("check", true); return; }
    if (s.state === "denied") { show("wait", false); show("denied", true); return; }
    show("wait", true);
    setTimeout(function () { fetch("/api/state?id=" + id).then(function (r) { return r.json(); }).then(onState).catch(function () { setTimeout(function () { onState(s); }, 2000); }); }, 2000);
  }

  // ───── التخزين المحلي: OPFS (عامل + SyncAccessHandle) ← OPFS createWritable ← IndexedDB ─────
  var workerSrc = [
    "var h = {};",
    "onmessage = async function (e) { var m = e.data; try {",
    " var root = await navigator.storage.getDirectory();",
    " if (m.op === 'write') { if (!h[m.name]) { var fh = await root.getFileHandle(m.name, { create: true }); h[m.name] = await fh.createSyncAccessHandle(); }",
    "   var n = h[m.name].write(new Uint8Array(m.buf), { at: m.at }); h[m.name].flush(); postMessage({ id: m.id, ok: true, n: n }); }",
    " else if (m.op === 'read') { var hh = h[m.name]; var b = new Uint8Array(m.size); var r = hh.read(b, { at: m.at }); postMessage({ id: m.id, ok: true, buf: b.buffer, n: r }, [b.buffer]); }",
    " else if (m.op === 'size') { postMessage({ id: m.id, ok: true, n: h[m.name] ? h[m.name].getSize() : 0 }); }",
    " else if (m.op === 'close') { if (h[m.name]) { h[m.name].close(); delete h[m.name]; } postMessage({ id: m.id, ok: true }); }",
    " else if (m.op === 'probe') { var f = await root.getFileHandle('probe.bin', { create: true }); var s = await f.createSyncAccessHandle(); s.write(new Uint8Array([1,2,3]), { at: 0 }); s.flush(); var sz = s.getSize(); s.close(); await root.removeEntry('probe.bin'); postMessage({ id: m.id, ok: sz === 3 }); }",
    "} catch (err) { postMessage({ id: m.id, ok: false, err: String(err) }); } };"
  ].join("\n");
  var W = null, wq = {}, wn = 0;
  function wcall(m, transfer) { return new Promise(function (res, rej) { m.id = ++wn; wq[m.id] = { res: res, rej: rej }; W.postMessage(m, transfer || []); }); }
  function startWorker() {
    try { W = new Worker(URL.createObjectURL(new Blob([workerSrc], { type: "text/javascript" }))); } catch (e) { W = null; return Promise.resolve(false); }
    W.onmessage = function (e) { var q = wq[e.data.id]; if (!q) return; delete wq[e.data.id]; if (e.data.ok) q.res(e.data); else q.rej(new Error(e.data.err || "fail")); };
    return Promise.race([wcall({ op: "probe" }).then(function (r) { return !!r.ok; }).catch(function () { return false; }), new Promise(function (r) { setTimeout(function () { r(false); }, 4000); })]);
  }
  var idb = null;
  function openIdb() { return new Promise(function (res, rej) { var r = indexedDB.open("rr", 1); r.onupgradeneeded = function () { r.result.createObjectStore("chunks"); }; r.onsuccess = function () { idb = r.result; res(true); }; r.onerror = function () { rej(r.error); }; }); }
  function idbPut(k, v) { return new Promise(function (res, rej) { var t = idb.transaction("chunks", "readwrite"); t.objectStore("chunks").put(v, k); t.oncomplete = function () { res(); }; t.onerror = function () { rej(t.error); }; }); }
  function idbGet(k) { return new Promise(function (res, rej) { var t = idb.transaction("chunks").objectStore("chunks").get(k); t.onsuccess = function () { res(t.result); }; t.onerror = function () { rej(t.error); }; }); }

  // كاتب واحد بيختار أحسن طريقة متوفرة. الجزء بينكتب محليًا قبل أي نقل.
  var Local = { mode: "", name: "", at: 0, writable: null,
    init: function (name) {
      Local.name = name; Local.at = 0;
      return startWorker().then(function (ok) {
        if (ok) { Local.mode = "opfs-sync"; return; }
        if (navigator.storage && navigator.storage.getDirectory) {
          return navigator.storage.getDirectory().then(function (root) { return root.getFileHandle(name, { create: true }); }).then(function (fh) {
            if (!fh.createWritable) throw new Error("no createWritable");
            Local.fh = fh; Local.mode = "opfs-writable";
          }).catch(function () { return openIdb().then(function () { Local.mode = "indexeddb"; }); });
        }
        return openIdb().then(function () { Local.mode = "indexeddb"; });
      });
    },
    write: function (seq, buf) {
      var at = Local.at; Local.at += buf.byteLength;
      if (Local.mode === "opfs-sync") return wcall({ op: "write", name: Local.name, at: at, buf: buf.slice(0) }).then(function () { return at; });
      if (Local.mode === "opfs-writable") return Local.fh.createWritable({ keepExistingData: true }).then(function (w) { return w.seek(at).then(function () { return w.write(buf); }).then(function () { return w.close(); }); }).then(function () { return at; });
      return idbPut(Local.name + ":" + seq, buf).then(function () { return at; });
    },
    read: function (seq, at, size) {
      if (Local.mode === "opfs-sync") return wcall({ op: "read", name: Local.name, at: at, size: size }).then(function (r) { return r.buf; });
      if (Local.mode === "opfs-writable") return Local.fh.getFile().then(function (f) { return f.slice(at, at + size).arrayBuffer(); });
      return idbGet(Local.name + ":" + seq);
    },
    size: function () {
      if (Local.mode === "opfs-sync") return wcall({ op: "size", name: Local.name }).then(function (r) { return r.n; });
      if (Local.mode === "opfs-writable") return Local.fh.getFile().then(function (f) { return f.size; });
      return Promise.resolve(Local.at);
    },
    file: function (mime) {
      if (Local.mode === "opfs-sync") return wcall({ op: "close", name: Local.name }).then(function () { return navigator.storage.getDirectory(); }).then(function (r) { return r.getFileHandle(Local.name); }).then(function (fh) { return fh.getFile(); });
      if (Local.mode === "opfs-writable") return Local.fh.getFile();
      return Promise.resolve(null);
    }
  };

  function sha(buf) { return crypto.subtle.digest("SHA-256", buf).then(function (h) { return Array.prototype.map.call(new Uint8Array(h), function (b) { return ("0" + b.toString(16)).slice(-2); }).join(""); }); }
  function mb(n) { return (n / 1048576).toFixed(1) + " MB"; }

  // ───── الفحص ─────
  var stream = null;
  $("runBtn").onclick = function () { $("runBtn").disabled = true; run().catch(function (e) { row("fatal", "خطأ", "bad", String(e)); }).then(function () { $("runBtn").disabled = false; sendReport(); }); };

  function run() {
    // ١) الأساسيات
    var basics = {
      secure: window.isSecureContext, mediaDevices: !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia),
      mediaRecorder: typeof MediaRecorder !== "undefined", opfs: !!(navigator.storage && navigator.storage.getDirectory),
      idb: typeof indexedDB !== "undefined", wakeLock: "wakeLock" in navigator, subtle: !!(crypto && crypto.subtle),
      persistApi: !!(navigator.storage && navigator.storage.persist)
    };
    put("basics", basics.secure && basics.mediaDevices && basics.mediaRecorder ? "ok" : "bad", basics);
    row("basics", "صفحة آمنة + الكاميرا + المسجّل", basics.secure && basics.mediaDevices && basics.mediaRecorder ? "ok" : "bad",
      (basics.secure ? "آمنة" : "مش آمنة!") + " · كاميرا/مايك " + (basics.mediaDevices ? "✓" : "✗") + " · MediaRecorder " + (basics.mediaRecorder ? "✓" : "✗"));
    var p = Promise.resolve();
    // ٢) التخزين
    p = p.then(function () {
      var est = navigator.storage && navigator.storage.estimate ? navigator.storage.estimate() : Promise.resolve({});
      var per = navigator.storage && navigator.storage.persist ? navigator.storage.persist() : Promise.resolve(null);
      return Promise.all([est, per]).then(function (a) {
        var e = a[0] || {}; var d = { quota: e.quota || 0, usage: e.usage || 0, persisted: a[1] };
        put("storage", d.quota > 0 ? "ok" : "warn", d);
        row("storage", "المساحة المتاحة للمتصفح", d.quota > 0 ? "ok" : "warn", (d.quota ? "حوالي " + mb(d.quota - d.usage) + " (تقديري من المتصفح)" : "ما بيعطي رقم") + " · تخزين دائم: " + (a[1] === true ? "✓" : a[1] === false ? "رفض" : "مش مدعوم"));
      });
    });
    // ٣) الكاميرا والمايك
    p = p.then(function () {
      row("cam", "الكاميرا والمايك", "", "اسمح للمتصفح…");
      return navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 } }, audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } }).then(function (s) {
        stream = s; show("live", true); $("vid").srcObject = s;
        var v = s.getVideoTracks()[0], a = s.getAudioTracks()[0];
        var vs = v ? v.getSettings() : {}, as = a ? a.getSettings() : {};
        var caps = v && v.getCapabilities ? v.getCapabilities() : null;
        put("cam", "ok", { video: vs, audio: as, vlabel: v && v.label, alabel: a && a.label, caps: caps });
        row("cam", "الكاميرا والمايك", "ok", (vs.width || "?") + "×" + (vs.height || "?") + " @" + (vs.frameRate ? Math.round(vs.frameRate) : "?") + "fps (المطلوب 1280×720@30) · صوت " + (as.sampleRate || "?") + "Hz " + (as.channelCount || "?") + "ch");
        meter(a, $("meters"), (a && a.label) || "المايك");
      }).catch(function (e) { put("cam", "bad", String(e)); row("cam", "الكاميرا والمايك", "bad", String(e.name || e)); });
    });
    // ٤) الأجهزة
    p = p.then(function () {
      return navigator.mediaDevices.enumerateDevices().then(function (ds) {
        var mics = ds.filter(function (d) { return d.kind === "audioinput"; }), cams = ds.filter(function (d) { return d.kind === "videoinput"; });
        put("devices", "ok", ds.map(function (d) { return { kind: d.kind, label: d.label, id: d.deviceId.slice(0, 12), group: d.groupId.slice(0, 12) }; }));
        row("devices", "الكاميرات والمايكات", "ok", cams.length + " كاميرا · " + mics.length + " مايك" + (mics.length ? ": " + mics.map(function (m) { return m.label || "؟"; }).join("، ") : ""));
        var real = mics.filter(function (m) { return m.deviceId !== "default" && m.deviceId !== "communications"; });
        if (real.length >= 2) { show("dual", true); $("dualBtn").onclick = function () { dualTest(real.slice(0, 2)); }; }
      });
    });
    // ٥) صيغ التسجيل
    p = p.then(function () {
      var types = ["video/mp4;codecs=avc1.42E01E,mp4a.40.2", "video/mp4", "video/webm;codecs=vp9,opus", "video/webm;codecs=vp8,opus", "video/webm", "audio/mp4", "audio/webm;codecs=opus", "audio/ogg;codecs=opus"];
      var ok = types.filter(function (t) { try { return MediaRecorder.isTypeSupported(t); } catch (e) { return false; } });
      put("formats", ok.length ? "ok" : "bad", ok);
      row("formats", "صيغ التسجيل", ok.length ? "ok" : "bad", ok.length ? '<span class="mono">' + ok.join(" · ") + "</span>" : "ولا صيغة");
    });
    // ٦) الساعة
    p = p.then(clockTest);
    // ٧) التخزين المحلي
    p = p.then(function () {
      return Local.init("probe-" + Date.now() + ".bin").then(function () {
        put("local", Local.mode === "indexeddb" ? "warn" : "ok", Local.mode);
        row("local", "الكتابة المحلية أثناء التسجيل", Local.mode === "indexeddb" ? "warn" : "ok", { "opfs-sync": "OPFS (الأسرع، بعامل منفصل)", "opfs-writable": "OPFS (كتابة عادية)", "indexeddb": "IndexedDB (أبطأ)" }[Local.mode] || Local.mode);
      }).catch(function (e) { put("local", "bad", String(e)); row("local", "الكتابة المحلية", "bad", String(e)); });
    });
    // ٨) منع قفل الشاشة
    p = p.then(function () {
      if (!("wakeLock" in navigator)) { put("wake", "warn", "unsupported"); row("wake", "منع قفل الشاشة", "warn", "مش مدعوم — لازم تطفي القفل التلقائي يدويًا"); return; }
      return navigator.wakeLock.request("screen").then(function (l) { put("wake", "ok", true); row("wake", "منع قفل الشاشة", "ok", "شغّال"); l.release(); })
        .catch(function (e) { put("wake", "warn", String(e)); row("wake", "منع قفل الشاشة", "warn", String(e.name || e)); });
    });
    p = p.then(function () { if (stream) show("recCard", true); $("recBtn").onclick = recTest; });
    return p;
  }

  function meter(track, box, label) {
    if (!track) return;
    var Ctx = window.AudioContext || window.webkitAudioContext; if (!Ctx) return;
    var ctx = new Ctx(); var src = ctx.createMediaStreamSource(new MediaStream([track])); var an = ctx.createAnalyser(); an.fftSize = 1024; src.connect(an);
    var d = document.createElement("div"); d.innerHTML = '<div class="sub">' + label + '</div><div class="meter"><div></div></div>'; box.appendChild(d);
    var bar = d.querySelector(".meter > div"), buf = new Float32Array(an.fftSize);
    (function tick() { an.getFloatTimeDomainData(buf); var s = 0; for (var i = 0; i < buf.length; i++) s += buf[i] * buf[i]; var db = 10 * Math.log10(s / buf.length + 1e-12); bar.style.width = Math.max(0, Math.min(100, (db + 60) * 1.8)) + "%"; requestAnimationFrame(tick); })();
    return { ctx: ctx, an: an };
  }

  // فرق الساعة: ٢٠ قياس، بناخد الأقل تأخيرًا (زي NTP)
  function clockTest() {
    var samples = [], i = 0;
    function one() {
      var t0 = performance.now();
      return fetch("/api/time", { cache: "no-store" }).then(function (r) { return r.json(); }).then(function (j) {
        var t1 = performance.now(); samples.push({ rtt: t1 - t0, off: j.t / 1e6 - (t0 + t1) / 2 });
      });
    }
    var p = Promise.resolve();
    for (i = 0; i < 20; i++) p = p.then(one).then(function () { return new Promise(function (r) { setTimeout(r, 60); }); });
    return p.then(function () {
      samples.sort(function (a, b) { return a.rtt - b.rtt; });
      var best = samples.slice(0, 5), off = best.reduce(function (s, x) { return s + x.off; }, 0) / best.length;
      var spread = Math.max.apply(null, best.map(function (x) { return x.off; })) - Math.min.apply(null, best.map(function (x) { return x.off; }));
      var d = { offsetMs: off, minRtt: samples[0].rtt, medianRtt: samples[Math.floor(samples.length / 2)].rtt, spreadMs: spread, n: samples.length };
      R.clock0 = { at: performance.now(), off: off };
      put("clock", spread < 15 ? "ok" : "warn", d);
      row("clock", "فرق الساعة مع المدير", spread < 15 ? "ok" : "warn", "أقل تأخير " + samples[0].rtt.toFixed(1) + "ms · عدم اليقين ±" + (spread / 2 + samples[0].rtt / 2).toFixed(1) + "ms");
    }).catch(function (e) { put("clock", "bad", String(e)); row("clock", "فرق الساعة", "bad", String(e)); });
  }

  // ───── مايكين مع بعض ─────
  function dualTest(mics) {
    $("dualBtn").disabled = true; $("dualRes").textContent = "بنفتح المايكين…";
    var opens = mics.map(function (m) { return navigator.mediaDevices.getUserMedia({ audio: { deviceId: { exact: m.deviceId }, echoCancellation: false, noiseSuppression: false, autoGainControl: false } }); });
    Promise.all(opens).then(function (ss) {
      var tr = ss.map(function (s) { return s.getAudioTracks()[0]; });
      var st = tr.map(function (t) { return t.getSettings(); });
      var Ctx = window.AudioContext || window.webkitAudioContext; var ctx = new Ctx();
      var ans = tr.map(function (t) { var a = ctx.createAnalyser(); a.fftSize = 2048; ctx.createMediaStreamSource(new MediaStream([t])).connect(a); return a; });
      var env = [[], []], buf = new Float32Array(2048);
      $("dualMeters").innerHTML = "";
      var bars = mics.map(function (m) { var d = document.createElement("div"); d.innerHTML = '<div class="sub">' + (m.label || "مايك") + '</div><div class="meter"><div></div></div>'; $("dualMeters").appendChild(d); return d.querySelector(".meter > div"); });
      var t0 = performance.now();
      (function tick() {
        for (var k = 0; k < 2; k++) { ans[k].getFloatTimeDomainData(buf); var s = 0; for (var i = 0; i < buf.length; i++) s += buf[i] * buf[i]; var db = 10 * Math.log10(s / buf.length + 1e-12); env[k].push(db); bars[k].style.width = Math.max(0, Math.min(100, (db + 60) * 1.8)) + "%"; }
        if (performance.now() - t0 < 8000) return setTimeout(tick, 50);
        // اختلاف حقيقي = منحنيات الصوت مش متطابقة، والفرق بينهم بيتغيّر لما تحكي عند واحد
        var n = Math.min(env[0].length, env[1].length), ma = 0, mb2 = 0; for (var j = 0; j < n; j++) { ma += env[0][j]; mb2 += env[1][j]; } ma /= n; mb2 /= n;
        var sab = 0, saa = 0, sbb = 0, dmax = -99, dmin = 99;
        for (j = 0; j < n; j++) { var x = env[0][j] - ma, y = env[1][j] - mb2; sab += x * y; saa += x * x; sbb += y * y; var df = env[0][j] - env[1][j]; if (df > dmax) dmax = df; if (df < dmin) dmin = df; }
        var corr = sab / Math.sqrt(saa * sbb + 1e-9);
        var same = st[0].deviceId && st[0].deviceId === st[1].deviceId;
        var distinct = !same && (corr < 0.97 || dmax - dmin > 6);
        var d = { settings: st, labels: tr.map(function (t) { return t.label; }), corr: corr, diffRangeDb: dmax - dmin, distinct: distinct, bothLive: tr.every(function (t) { return t.readyState === "live"; }) };
        put("dual", distinct ? "ok" : "warn", d);
        row("dual", "مايكين مع بعض", distinct ? "ok" : "warn", (d.bothLive ? "انفتحوا مع بعض" : "واحد وقف") + " · " + (distinct ? "مختلفين فعلًا" : "شكلهم نفس المصدر (أو ما حكيت عند واحد)") + " · قنوات " + st.map(function (s) { return s.channelCount || "?"; }).join("/"));
        $("dualRes").textContent = distinct ? "✓ مايكين مستقلين" : "⚠ ما قدرنا نتأكد إنهم مختلفين";
        ss.forEach(function (s) { s.getTracks().forEach(function (t) { t.stop(); }); }); ctx.close(); $("dualBtn").disabled = false; sendReport();
      })();
    }).catch(function (e) { put("dual", "bad", String(e)); row("dual", "مايكين مع بعض", "bad", "ما انفتحوا مع بعض: " + (e.name || e)); $("dualRes").textContent = String(e); $("dualBtn").disabled = false; sendReport(); });
  }

  // ───── التسجيل التجريبي: محلي أولًا، النقل وراه، بدون ما نجمع الفيديو بالذاكرة ─────
  var rec = null;
  function recTest() {
    if (!stream) return;
    var types = R.tests.formats && R.tests.formats.data || [];
    var mime = types.filter(function (t) { return t.indexOf("video/") === 0; })[0] || "";
    var recId = "r" + Date.now().toString(36);
    var st = { rec: recId, mime: mime, iv: [], chunks: [], startedAt: 0, stoppedAt: 0, bytes: 0, maxChunk: 0, uploaded: 0, upFail: 0, localFail: 0, gaps: [], mode: "" };
    var queue = [], upBusy = false, lastT = 0, wake = null;
    $("recBtn").disabled = true; $("stopBtn").disabled = false;
    var len = parseInt($("recLen").value, 10);
    Local.init("rec-" + recId + ".bin").then(function () {
      st.mode = Local.mode;
      var mr; try { mr = mime ? new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: 2500000 }) : new MediaRecorder(stream); } catch (e) { mr = new MediaRecorder(stream); }
      st.mime = mr.mimeType || mime;
      rec = { mr: mr, st: st };
      if ("wakeLock" in navigator) navigator.wakeLock.request("screen").then(function (l) { wake = l; }).catch(function () {});
      var chain = Promise.resolve(), seq = 0;
      mr.ondataavailable = function (e) {
        // المتصفح بيقرّر كل قديش بيعطي جزء (كروم مثلًا كل ٥ ث). «تأخير» = جزء وصل متأخر ٣ أضعاف المعتاد (مثلًا الشاشة انقفلت)
        var now = performance.now(), dt = now - lastT; lastT = now;
        st.iv.push(dt); var sorted = st.iv.slice().sort(function (x, y) { return x - y; }), med = sorted[Math.floor(sorted.length / 2)];
        if (st.iv.length >= 3 && dt > Math.max(2500, med * 3)) st.gaps.push({ at: Math.round(now - st.startedAt), ms: Math.round(dt) });
        if (!e.data || !e.data.size) return;
        var my = seq++, size = e.data.size; st.bytes += size; if (size > st.maxChunk) st.maxChunk = size;
        // الكتابة المحلية بالترتيب، وبعدها بس بيدخل الجزء على قائمة النقل
        chain = chain.then(function () { return e.data.arrayBuffer(); }).then(function (buf) {
          return Promise.all([Local.write(my, buf), sha(buf)]).then(function (a) { st.chunks[my] = { seq: my, at: a[0], size: size, sha: a[1], t: Math.round(now - st.startedAt), up: false }; queue.push(my); pump(); });
        }).catch(function (err) { st.localFail++; ev("local-write-fail", String(err)); });
        stats();
      };
      mr.onerror = function (e) { ev("recorder-error", String(e.error || e)); };
      mr.onstop = function () {
        st.stoppedAt = performance.now();
        if (wake) try { wake.release(); } catch (e) {}
        // بنستنى آخر جزء ينكتب محليًا، وبعدين كل النقل، وبعدين بنأكد مع المدير
        chain.then(function () { return waitUploads(); }).then(function () {
          return api("/api/finish", { method: "POST", body: JSON.stringify({ rec: recId, parts: st.chunks.length, mime: st.mime, shas: st.chunks.map(function (c) { return c.sha; }), startedPerf: st.startedAt, clock: R.clock0 }) });
        }).then(function (f) {
          st.finish = f;
          return Local.size().then(function (sz) { st.localSize = sz; });
        }).then(function () { return Local.file(st.mime); }).then(function (file) {
          // نتأكد إن النسخة المحلية بتشتغل كملف واحد
          if (!file) return;
          return new Promise(function (res) {
            var v = document.createElement("video"); v.muted = true; v.preload = "metadata";
            var done = false, fin = function (ok) { if (done) return; done = true; st.localPlayable = ok; st.localDuration = v.duration; res(); };
            v.onloadedmetadata = function () { if (v.duration === Infinity) { v.currentTime = 1e9; v.ontimeupdate = function () { v.ontimeupdate = null; fin(true); }; } else fin(true); };
            v.onerror = function () { fin(false); }; setTimeout(function () { fin(false); }, 8000);
            v.src = URL.createObjectURL(file);
          });
        }).then(function () {
          var okAll = st.finish && st.finish.ok && st.localFail === 0;
          put("record", okAll ? (st.gaps.length ? "warn" : "ok") : "bad", summary());
          row("record", "التسجيل التجريبي", okAll ? (st.gaps.length ? "warn" : "ok") : "bad",
            ((st.stoppedAt - st.startedAt) / 1000).toFixed(0) + " ث · " + st.chunks.length + " جزء · " + mb(st.bytes) + " · " + (st.finish && st.finish.ok ? "وصل كامل للمدير ✓" : "ما اكتمل عند المدير ✗") +
            " · جزء كل ~" + (median(st.iv) / 1000).toFixed(1) + " ث" + (st.gaps.length ? " · تأخيرات: " + st.gaps.length : "") + (st.localPlayable === false ? " · النسخة المحلية ما اشتغلت" : st.localPlayable ? " · النسخة المحلية بتشتغل (" + (isFinite(st.localDuration) ? st.localDuration.toFixed(0) + " ث" : "؟") + ")" : ""));
          $("recBtn").disabled = false; $("stopBtn").disabled = true; stats(); sendReport();
        }).catch(function (e) { row("record", "التسجيل التجريبي", "bad", String(e)); $("recBtn").disabled = false; sendReport(); });
      };
      st.startedAt = performance.now(); lastT = st.startedAt;
      mr.start(1000);
      ev("rec-start", { mime: st.mime, mode: st.mode });
      $("stopBtn").onclick = function () { if (mr.state !== "inactive") mr.stop(); };
      setTimeout(function () { if (mr.state !== "inactive") mr.stop(); }, len * 1000);
      var iv = setInterval(function () { if (mr.state === "inactive") clearInterval(iv); stats(); }, 1000);
    });
    function pump() {
      if (upBusy || !queue.length) return;
      upBusy = true;
      var s = queue[0], c = st.chunks[s];
      // النقل بيقرا من التخزين المحلي (مش من الذاكرة) — هيك نفس الطريق بعد انقطاع أو إعادة فتح
      Local.read(s, c.at, c.size).then(function (buf) {
        return fetch("/api/chunk?token=" + token + "&rec=" + st.rec + "&seq=" + s + "&sha=" + c.sha, { method: "PUT", body: buf });
      }).then(function (r) { return r.json().then(function (j) { if (!r.ok || !j.ok) throw new Error(j.error || r.status); }); })
        .then(function () { c.up = true; st.uploaded++; queue.shift(); upBusy = false; pump(); })
        .catch(function (e) { st.upFail++; ev("upload-fail", String(e)); upBusy = false; setTimeout(pump, Math.min(10000, 500 * st.upFail)); });
    }
    function waitUploads() { return new Promise(function (res) { (function w() { if (!queue.length && !upBusy) return res(); pump(); setTimeout(w, 300); })(); }); }
    function summary() {
      return { rec: st.rec, mime: st.mime, mode: st.mode, seconds: (st.stoppedAt - st.startedAt) / 1000, parts: st.chunks.length, bytes: st.bytes, maxChunk: st.maxChunk,
        chunkEveryMs: median(st.iv), uploaded: st.uploaded, uploadRetries: st.upFail, localFail: st.localFail, gaps: st.gaps, localSize: st.localSize, localPlayable: st.localPlayable, localDuration: st.localDuration,
        finish: st.finish, chunkTimes: st.chunks.map(function (c) { return [c.t, c.size]; }).slice(0, 4000) };
    }
    function stats() {
      var el = ((rec && rec.mr.state !== "inactive" ? performance.now() : st.stoppedAt || performance.now()) - st.startedAt) / 1000;
      $("recStats").innerHTML = "<tr><td class='k'>المدة</td><td>" + el.toFixed(0) + " ث</td></tr>" +
        "<tr><td class='k'>محفوظ محليًا</td><td>" + st.chunks.filter(Boolean).length + " جزء · " + mb(st.bytes) + " · أكبر جزء " + mb(st.maxChunk) + " · " + st.mode + "</td></tr>" +
        "<tr><td class='k'>وصل للمدير</td><td>" + st.uploaded + " جزء" + (st.upFail ? " · إعادات: " + st.upFail : "") + "</td></tr>" +
        (st.gaps.length ? "<tr><td class='k warn'>تأخيرات (الصفحة وقفت؟)</td><td>" + st.gaps.map(function (g) { return (g.at / 1000).toFixed(0) + "ث (" + (g.ms / 1000).toFixed(1) + "ث)"; }).join("، ") + "</td></tr>" : "");
    }
  }

  function median(a) { if (!a.length) return 0; var s = a.slice().sort(function (x, y) { return x - y; }); return s[Math.floor(s.length / 2)]; }

  function sendReport() {
    R.sent = new Date().toISOString();
    R.screen = { w: screen.width, h: screen.height, dpr: window.devicePixelRatio };
    return api("/api/report", { method: "POST", body: JSON.stringify(R) }).then(function (j) { row("sent", "التقرير", j.ok ? "ok" : "bad", j.ok ? "وصل لجوال المدير" : "ما وصل"); }).catch(function () { row("sent", "التقرير", "bad", "ما وصل"); });
  }
})();
