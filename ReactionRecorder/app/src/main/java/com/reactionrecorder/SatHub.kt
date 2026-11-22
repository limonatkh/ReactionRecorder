package com.reactionrecorder

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * الجهاز الرئيسي: خادم TCP يستقبل الأجهزة المساعدة (مايك/كاميرا). يرسل لها أوامر البدء والإيقاف،
 * ويقيس فرق الساعة معها باستمرار، ويستلم ملفاتها المسجَّلة محليًا عند الاتصال (حتى بعد انقطاع طويل).
 * يعيش داخل العملية (singleton) فيبقى شغالًا أثناء التسجيل.
 */
object SatHub {
    class Link(val id: String) {
        @Volatile var name = "device"
        @Volatile var role = "mic"
        @Volatile var sock: SatSocket? = null
        @Volatile var connected = false
        @Volatile var level = 0
        @Volatile var recording = false
        @Volatile var battery = -1
        @Volatile var freeMb = -1L
        @Volatile var rttMs = 0L
        @Volatile var lastSeen = 0L
        @Volatile var use = true
        @Volatile var burst = 0
        @Volatile var transfer = ""
        // معاينة وتحكم عن بُعد
        @Volatile var pvVideo = false
        @Volatile var pvAudio = false
        @Volatile var pvLevel = false
        /** فترة صور المعاينة (ms) وحجمها (px) — المراقبة الخفيفة تطلب صورة كل ثانية تقريبًا */
        @Volatile var pvIv = 125
        @Volatile var pvPx = 320
        /** آخر صورة تأكيد عند بدء التسجيل (حتى لو المعاينة مطفأة) */
        @Volatile var snapAt = 0L
        /** رقم ترخيص الجهاز البعيد، والمتبقي من ترخيصه (ms؛ -1 غير معروف)، وهل هو صاحب التطبيق */
        @Volatile var licDev = ""
        @Volatile var licLeft = -1L
        @Volatile var licOwner = false
        @Volatile var licMsg = ""
        /** عرض معاينة هذا الجهاز على الشاشة الرئيسية للتطبيق */
        @Volatile var stripOn = false
        @Volatile var frame: android.graphics.Bitmap? = null
        @Volatile var frameAt = 0L
        @Volatile var cfgs: JSONObject? = null
        @Volatile var caps: JSONObject? = null
        var track: android.media.AudioTrack? = null
        val recent = ArrayList<DevSample>()
        val pending = ConcurrentHashMap<Int, Long>()
    }

    private lateinit var app: Context
    @Volatile var running = false
        private set
    @Volatile var lastError = ""
    /** المنفذ الفعلي (قد يتغيّر إذا كان الأساسي مشغولًا) */
    @Volatile var port = SatNet.PORT
        private set
    @Volatile var activeSession: String? = null
        private set
    @Volatile var pin = "0000"
        private set
    /** هوية ثابتة لهذا الجوال الرئيسي: تجد بها الأجهزة المساعدة طريقها إليه حتى لو تغيّر عنوانه */
    @Volatile var hubId = ""
        private set
    val hubName: String get() = Build.MODEL ?: "Phone"
    val links = ConcurrentHashMap<String, Link>()

    private var server: ServerSocket? = null
    private val exec = Executors.newCachedThreadPool()
    private val sched = Executors.newSingleThreadScheduledExecutor()
    private var pingJob: ScheduledFuture<*>? = null
    private val seq = AtomicInteger(1)

    private const val PREFS = "reaction_sat"

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hubWanted(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("hub", false)
    fun setHubWanted(ctx: Context, on: Boolean) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("hub", on).apply() }

    @Synchronized
    fun start(ctx: Context) {
        if (running) return
        app = ctx.applicationContext
        var p = prefs().getString("pin", null)?.let { SatNet.normDigits(it) }
        if (p == null || !p.matches(Regex("[0-9]{4}"))) {
            p = String.format(java.util.Locale.US, "%04d", java.util.Random().nextInt(10000))
            prefs().edit().putString("pin", p).apply()
        }
        pin = p
        var hid = prefs().getString("hubId", null)
        if (hid.isNullOrBlank()) {
            hid = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10)
            prefs().edit().putString("hubId", hid).apply()
        }
        hubId = hid
        var bound: ServerSocket? = null
        var err = ""
        for (pt in SatNet.PORT..SatNet.PORT + 9) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(pt))
                bound = ss
                port = pt
                break
            } catch (t: Throwable) {
                err = "${t.javaClass.simpleName}: ${t.message}"
            }
        }
        if (bound == null) {
            lastError = "Cannot open a port ($err)"
            return
        }
        server = bound
        lastError = ""
        running = true
        exec.execute { acceptLoop() }
        pingJob = sched.scheduleWithFixedDelay({ try { pingAll() } catch (_: Throwable) {} }, 1, 2, TimeUnit.SECONDS)
        beaconJob = sched.scheduleWithFixedDelay({ try { sendBeacon() } catch (_: Throwable) {} }, 0, 2, TimeUnit.SECONDS)
        // خادم مشاركة التطبيق (نفس الباركود: تنزيل + اقتران)
        try { AppShare.start(app) } catch (_: Throwable) {}
    }

    private var beaconJob: ScheduledFuture<*>? = null
    private var beaconSock: java.net.DatagramSocket? = null

    /** نداء على الشبكة: "أنا الجوال الرئيسي X على المنفذ Y" — الأجهزة المقترنة ترجع لحالها */
    private fun sendBeacon() {
        if (!running) return
        val sk = beaconSock ?: java.net.DatagramSocket().also { it.broadcast = true; beaconSock = it }
        val data = SatNet.beacon(hubId, port, hubName).toByteArray(Charsets.UTF_8)
        for (a in SatNet.broadcastAddrs()) {
            try { sk.send(java.net.DatagramPacket(data, data.size, a, SatNet.BEACON_PORT)) } catch (_: Throwable) {}
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { pingJob?.cancel(false) } catch (_: Throwable) {}
        try { beaconJob?.cancel(false) } catch (_: Throwable) {}
        try { AppShare.stop() } catch (_: Throwable) {}
        try { beaconSock?.close() } catch (_: Throwable) {}
        beaconSock = null
        try { server?.close() } catch (_: Throwable) {}
        server = null
        for (l in links.values) { l.connected = false; l.sock?.close() }
    }

    fun newPin(ctx: Context): String {
        app = ctx.applicationContext
        val p = String.format(java.util.Locale.US, "%04d", java.util.Random().nextInt(10000))
        prefs().edit().putString("pin", p).apply()
        pin = p
        return p
    }

    // ───────── استقبال الاتصالات ─────────

    private fun acceptLoop() {
        val srv = server ?: return
        while (running) {
            val s: Socket = try { srv.accept() } catch (_: Throwable) { break }
            exec.execute { handle(s) }
        }
    }

    private fun handle(socket: Socket) {
        val ss = SatSocket(socket)
        try { socket.soTimeout = 10_000 } catch (_: Throwable) {}
        val first = ss.read()
        if (first == null) { ss.close(); return }
        when (first.optString("t")) {
            "hello" -> control(ss, first)
            "upload" -> upload(ss, first)
            else -> ss.close()
        }
    }

    private fun control(ss: SatSocket, hello: JSONObject) {
        if (SatNet.normDigits(hello.optString("pin")) != pin) {
            ss.send(SatNet.msg("deny").put("why", "wrong PIN"))
            ss.close()
            return
        }
        val id = hello.optString("id")
        if (id.isBlank()) { ss.close(); return }
        val link = links.getOrPut(id) { Link(id) }
        try { link.sock?.close() } catch (_: Throwable) {}
        link.name = hello.optString("name", "device")
        link.role = hello.optString("role", "mic")
        link.licDev = hello.optString("lid")
        link.licLeft = hello.optLong("lleft", -1L)
        link.licOwner = hello.optBoolean("own", false)
        link.sock = ss
        link.connected = true
        link.lastSeen = SystemClock.elapsedRealtime()
        link.use = !(prefs().getStringSet("off", emptySet()) ?: emptySet()).contains(id)
        ss.send(SatNet.msg("welcome").put("name", Build.MODEL).put("active", activeSession ?: ""))
        if (link.pvVideo || link.pvAudio || link.pvLevel) sendPv(link)
        // نسخة أوقفها صاحب التطبيق وهي غير متصلة: تُقفل الآن
        if (link.licDev.isNotEmpty() && !link.licOwner && link.licLeft > 0 && License.isOwner(app) && License.issued(app).any { it.dev == link.licDev && it.revoked }) {
            ss.send(SatNet.msg("unlic")); link.licLeft = 0
        }
        exec.execute { onReconnect(link, ss) }
        while (running && link.sock === ss) {
            val m = ss.read() ?: break
            link.lastSeen = SystemClock.elapsedRealtime()
            handleMsg(link, m)
        }
        if (link.sock === ss) {
            link.connected = false
            link.recording = false
            link.level = 0
        }
        ss.close()
    }

    private fun handleMsg(link: Link, m: JSONObject) {
        when (m.optString("t")) {
            "pong" -> {
                val t1 = link.pending.remove(m.optInt("id")) ?: return
                val t4 = SystemClock.elapsedRealtimeNanos()
                val t2 = m.optLong("t2")
                val t3 = m.optLong("t3")
                val theta = ((t2 - t1) + (t3 - t4)) / 2
                val rtt = (t4 - t1) - (t3 - t2)
                if (rtt < 0) return
                link.rttMs = rtt / 1_000_000L
                recordSample(link, DevSample((t1 + t4) / 2, theta, rtt))
            }
            "lvl" -> {
                link.level = m.optInt("l")
                link.recording = m.optBoolean("rec")
                link.battery = m.optInt("bat", -1)
                link.freeMb = m.optLong("free", -1L)
                if (m.has("ll")) link.licLeft = m.optLong("ll", -1L)
            }
            "pvf" -> {
                try {
                    val b = android.util.Base64.decode(m.optString("j"), android.util.Base64.DEFAULT)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)
                    val snap = m.optBoolean("snap")
                    if (bmp != null && (link.pvVideo || snap)) {
                        link.frame = bmp; link.frameAt = SystemClock.elapsedRealtime()
                        if (snap) link.snapAt = link.frameAt
                    }
                } catch (_: Throwable) {}
            }
            "au" -> if (link.pvAudio) playAudio(link, m.optString("d"))
            "cfgs" -> { link.cfgs = m; m.optString("role").takeIf { it == "both" || it == "mic" }?.let { link.role = it } }
            "caps" -> link.caps = m
            "sent" -> {
                val sid = m.optString("session")
                SessionStore.update(app, sid) { s -> s.devices[link.id]?.uploaded = true }
                link.transfer = ""
            }
            "have" -> onHave(link, m)
            "licok" -> { link.licLeft = m.optLong("ll", link.licLeft); link.licMsg = "" }
            "licerr" -> link.licMsg = m.optString("why")
            "nosession" -> {
                val sid = m.optString("session")
                SessionStore.update(app, sid) { s -> s.devices[link.id]?.let { it.started = false; it.uploaded = true } }
            }
        }
    }

    /**
     * الجهاز المساعد يخبرنا بتسجيلات عنده لم تُرسل بعد: نطلبها كلها، حتى لو انضم بعد بدء الجلسة
     * أو انقطع قبل نهايتها. هكذا لا يضيع ملف بسبب انقطاع أو ترتيب أوامر.
     */
    private fun onHave(link: Link, m: JSONObject) {
        val arr = m.optJSONArray("s") ?: return
        val act = activeSession
        for (i in 0 until arr.length()) {
            val sid = SessionStore.safe(arr.optString(i))
            if (sid.isBlank() || sid == act) continue
            val s = SessionStore.load(app, sid) ?: continue
            var need = false
            SessionStore.update(app, sid) { x ->
                var dv = x.devices[link.id]
                if (dv == null) {
                    dv = DevInfo(link.id, link.name, link.role)
                    dv.started = true
                    synchronized(link.recent) { dv.samples.addAll(link.recent) }
                    x.devices[link.id] = dv
                }
                if (!dv.uploaded) { dv.started = true; need = true }
            }
            if (need && s.ended) link.sock?.send(SatNet.msg("send").put("session", sid))
        }
    }

    private fun recordSample(link: Link, smp: DevSample) {
        synchronized(link.recent) {
            link.recent.add(smp)
            if (link.recent.size > 12) link.recent.removeAt(0)
        }
        val act = activeSession
        if (act != null) SessionStore.addSample(app, act, link.id, smp)
        if (link.burst > 0) {
            link.burst--
            for (s in SessionStore.pendingFor(app, link.id)) if (s.id != act) SessionStore.addSample(app, s.id, link.id, smp)
        }
    }

    private fun pingOne(l: Link) {
        val s = l.sock ?: return
        val id = seq.getAndIncrement()
        if (l.pending.size > 20) l.pending.keys.firstOrNull()?.let { l.pending.remove(it) }
        s.sendStamped { t1 ->
            l.pending[id] = t1
            SatNet.msg("ping").put("id", id)
        }
    }

    private fun pingAll() {
        for (l in links.values) if (l.connected) pingOne(l)
    }

    /** بعد (إعادة) الاتصال: قياس ساعة مكثّف، ثم إيقاف ما بقي يسجّل، ثم طلب الملفات الناقصة */
    private fun onReconnect(link: Link, ss: SatSocket) {
        link.burst = 14
        for (i in 0 until 14) {
            if (link.sock !== ss) return
            pingOne(link)
            try { Thread.sleep(80) } catch (_: Throwable) { return }
        }
        val act = activeSession
        for (s in SessionStore.pendingFor(app, link.id)) {
            if (s.id == act || !s.ended) continue
            val dv = s.devices[link.id] ?: continue
            if (!dv.stopped) {
                ss.send(SatNet.msg("stop").put("session", s.id))
                SessionStore.update(app, s.id) { x -> x.devices[link.id]?.stopped = true }
            }
            if (!dv.uploaded) ss.send(SatNet.msg("send").put("session", s.id))
        }
    }

    // ───────── معاينة وتحكم عن بُعد ─────────

    private fun sendPv(l: Link) {
        l.sock?.send(SatNet.msg("pv").put("video", l.pvVideo).put("audio", l.pvAudio).put("level", l.pvLevel).put("iv", l.pvIv).put("px", l.pvPx))
    }

    /** معاينة خفيفة جدًا (للمراقبة أثناء التسجيل): صورة صغيرة كل intervalMs */
    fun setLightPreview(id: String, on: Boolean, intervalMs: Int = 1000, px: Int = 240) {
        val l = links[id] ?: return
        if (on) setPreview(id, true, false, true, intervalMs, px) else setPreview(id, l.stripOn, false, l.stripOn)
    }

    /** video: صورة صغيرة، audio: صوت حي، level: شريط مستوى فقط (يشغّل المايك الخفيف) */
    fun setPreview(id: String, video: Boolean, audio: Boolean, level: Boolean, iv: Int = 125, px: Int = 320) {
        val l = links[id] ?: return
        l.pvIv = iv; l.pvPx = px
        l.pvVideo = video; l.pvAudio = audio; l.pvLevel = level
        if (!video) l.frame = null
        if (!audio) stopTrack(l)
        if (l.connected) sendPv(l)
    }

    fun stopAllPreviews() {
        for (l in links.values) setPreview(l.id, false, false, false)
    }

    fun sendCfg(id: String, o: JSONObject) {
        val l = links[id] ?: return
        o.put("t", "cfg")
        l.sock?.send(o)
    }

    private fun stopTrack(l: Link) {
        try { l.track?.stop() } catch (_: Throwable) {}
        try { l.track?.release() } catch (_: Throwable) {}
        l.track = null
    }

    private fun playAudio(l: Link, b64: String) {
        try {
            val pcm = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            var t = l.track
            if (t == null) {
                val min = android.media.AudioTrack.getMinBufferSize(11025, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                t = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(11025).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build(),
                    )
                    .setBufferSizeInBytes(maxOf(min, 8192) * 2)
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build()
                t.play()
                l.track = t
            }
            t.write(pcm, 0, pcm.size, android.media.AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: Throwable) {
        }
    }

    // ───────── جلسة التسجيل ─────────

    fun usableLinks(): List<Link> = links.values.filter { it.connected && it.use }
    fun hasUsable(): Boolean = running && usableLinks().isNotEmpty()

    /** يسجّل الأجهزة المتصلة في الجلسة ويرسل أمر البدء (قبل بدء تسجيل الجهاز الرئيسي بثوانٍ قليلة) */
    fun startSession(ctx: Context, sessionId: String) {
        app = ctx.applicationContext
        val use = usableLinks()
        activeSession = sessionId
        SessionStore.update(app, sessionId) { s ->
            for (l in use) {
                val dv = DevInfo(l.id, l.name, l.role)
                dv.started = true
                synchronized(l.recent) { dv.samples.addAll(l.recent) }
                s.devices[l.id] = dv
            }
        }
        for (l in use) l.sock?.send(SatNet.msg("start").put("session", sessionId))
    }

    fun stopSession(ctx: Context, sessionId: String) {
        app = ctx.applicationContext
        activeSession = null
        val toStop = ArrayList<Link>()
        SessionStore.update(app, sessionId) { s ->
            for (dv in s.devices.values) {
                val l = links[dv.id]
                if (l != null && l.connected) {
                    toStop.add(l)
                    dv.stopped = true
                }
            }
        }
        // الإرسال خارج قفل الجلسات كي لا تعلق بقية الخيوط لو كانت الشبكة بطيئة
        for (l in toStop) l.sock?.send(SatNet.msg("stop").put("session", sessionId))
        exec.execute {
            try { Thread.sleep(1500) } catch (_: Throwable) {}
            requestFiles(sessionId)
        }
    }

    /** يطلب من الأجهزة المتصلة رفع ملفات هذه الجلسة */
    fun requestFiles(sessionId: String) {
        val s = SessionStore.load(app, sessionId) ?: return
        for (dv in s.devices.values) {
            if (!dv.started || dv.uploaded) continue
            val l = links[dv.id] ?: continue
            if (l.connected) {
                if (!dv.stopped) {
                    l.sock?.send(SatNet.msg("stop").put("session", sessionId))
                    SessionStore.update(app, sessionId) { x -> x.devices[dv.id]?.stopped = true }
                }
                l.sock?.send(SatNet.msg("send").put("session", sessionId))
            }
        }
    }

    /** يفعّل جهازًا متصلًا (صاحب التطبيق فقط) */
    fun sendLicense(id: String, durMs: Long): Boolean {
        val l = links[id] ?: return false
        if (!l.connected || l.licDev.isEmpty()) return false
        val lic = License.issue(app, l.licDev, l.name, durMs) ?: return false
        return l.sock?.send(SatNet.msg("lic").put("l", lic)) == true
    }

    /** يقفل نسخة جهاز متصل فورًا */
    fun revokeLicense(id: String): Boolean {
        val l = links[id] ?: return false
        if (l.licDev.isNotEmpty()) License.markRevoked(app, l.licDev)
        l.licLeft = 0
        return l.sock?.send(SatNet.msg("unlic")) == true
    }

    fun setUse(id: String, use: Boolean) {
        links[id]?.use = use
        val cur = HashSet(prefs().getStringSet("off", emptySet()) ?: emptySet())
        if (use) cur.remove(id) else cur.add(id)
        prefs().edit().putStringSet("off", cur).apply()
    }

    // ───────── استقبال الملفات ─────────

    private fun upload(ss: SatSocket, h: JSONObject) {
        try {
            if (SatNet.normDigits(h.optString("pin")) != pin) { ss.close(); return }
            val sid = SessionStore.safe(h.optString("session"))
            val dev = h.optString("dev")
            val name = File(h.optString("name")).name
            val size = h.optLong("size")
            val link = links[dev]
            val dir = SessionStore.devDir(app, sid, dev)
            val part = File(dir, "$name.part")
            val fin = File(dir, name)
            try { ss.socket.soTimeout = 20_000 } catch (_: Throwable) {}
            if (fin.exists() && fin.length() == size) {
                ss.send(SatNet.msg("resume").put("offset", size))
                ss.send(SatNet.msg("done"))
                ss.close(); return
            }
            var have = if (part.exists()) part.length() else 0L
            if (have > size) { part.delete(); have = 0L }
            ss.send(SatNet.msg("resume").put("offset", have))
            val input = ss.rawIn()
            val buf = ByteArray(64 * 1024)
            var left = size - have
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(have)
                var got = have
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw java.io.IOException("connection lost")
                    raf.write(buf, 0, n)
                    left -= n
                    got += n
                    link?.transfer = "$name  ${if (size > 0) (got * 100 / size) else 100}%"
                }
            }
            if (fin.exists()) fin.delete()
            part.renameTo(fin)
            ss.send(SatNet.msg("done"))
            link?.transfer = ""
        } catch (_: Throwable) {
            // ينقطع الرفع: الجهاز المساعد سيعيد المحاولة ويكمل من حيث توقف
        } finally {
            ss.close()
        }
    }
}
