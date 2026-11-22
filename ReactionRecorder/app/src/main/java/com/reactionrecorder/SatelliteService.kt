package com.reactionrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Size
import android.view.Display
import android.view.OrientationEventListener
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/** إعدادات هذا الجهاز عندما يعمل كجهاز مساعد */
class SatCfg {
    var host = ""
    var pin = ""
    var name = Build.MODEL ?: "Phone"
    /** mic = صوت فقط، both = صوت + فيديو */
    var role = "mic"
    var front = true
    /** اتجاه الفيديو: auto = حسب وضع الجوال لحظة بدء التسجيل (ثم يثبت)، portrait، landscape */
    var orient = "auto"
    /** آخر اتجاه فعلي سُجّل به (يُحسب عند البدء) */
    var portrait = true
    /** هوية الجوال الرئيسي المقترن (من الباركود) — للرجوع التلقائي حتى لو تغيّر عنوانه */
    var hubId = ""
    var hubName = ""
    var shortSide = 720
    /** تثبيت الصورة (إذا الكاميرا بتدعمه) */
    var stab = true
    var camLong = 1280
    var mirror = false
    /** 0 = MIC عادي، 1 = UNPROCESSED (خام) */
    var aSrc = 0
    var gain = 1f
    var devId = ""
    /** تدوير يدوي للفيديو (0/90/180/270) + ضبط الكاميرا */
    var rot = 0
    var zoom = 1f
    var exp = 0
    var torch = false

    /** عناوين الجهاز الرئيسي (قد تكون عدة عناوين مفصولة بفاصلة) */
    fun hosts(): List<String> = host.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        private const val P = "reaction_sat_dev"
        fun load(ctx: Context): SatCfg {
            val s = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            val c = SatCfg()
            c.host = s.getString("host", "") ?: ""
            c.pin = s.getString("pin", "") ?: ""
            c.name = s.getString("name", c.name) ?: c.name
            c.role = s.getString("role", "mic") ?: "mic"
            c.front = s.getBoolean("front", true)
            c.portrait = s.getBoolean("portrait", true)
            c.orient = s.getString("orient", "auto") ?: "auto"
            c.hubId = s.getString("hubId", "") ?: ""
            c.hubName = s.getString("hubName", "") ?: ""
            c.shortSide = s.getInt("short", 720)
            c.stab = s.getBoolean("stab", true)
            c.camLong = s.getInt("camLong", 1280)
            c.mirror = s.getBoolean("mirror", false)
            c.aSrc = s.getInt("aSrc", 0)
            c.gain = s.getFloat("gain", 1f)
            c.rot = s.getInt("rot", 0)
            c.zoom = s.getFloat("zoom", 1f)
            c.exp = s.getInt("exp", 0)
            c.torch = s.getBoolean("torch", false)
            var id = s.getString("devId", null)
            if (id == null) {
                id = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                s.edit().putString("devId", id).apply()
            }
            c.devId = id
            return c
        }

        fun save(ctx: Context, c: SatCfg) {
            ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
                .putString("host", c.host).putString("pin", c.pin).putString("name", c.name).putString("role", c.role)
                .putBoolean("front", c.front).putBoolean("portrait", c.portrait).putInt("short", c.shortSide).putBoolean("stab", c.stab)
                .putString("orient", c.orient).putString("hubId", c.hubId).putString("hubName", c.hubName)
                .putInt("camLong", c.camLong).putBoolean("mirror", c.mirror).putInt("aSrc", c.aSrc).putFloat("gain", c.gain)
                .putInt("rot", c.rot).putFloat("zoom", c.zoom).putInt("exp", c.exp).putBoolean("torch", c.torch)
                .apply()
        }
    }
}

/** حالة الجهاز المساعد للواجهة (نفس العملية) */
object SatState {
    @Volatile var serviceOn = false
    @Volatile var connected = false
    @Volatile var recording = false
    @Volatile var status = tx("غير متصل", "Not connected")
    /** الاتجاه المثبّت أثناء التسجيل (للشاشة): 0 = غير مثبّت، 1 = عمودي، 2 = أفقي */
    @Volatile var lockedOrient = 0
    @Volatile var level = 0
    /** متى بدأ التسجيل على هذا الجوال (elapsedRealtime ms) — لعدّاد الشاشة والإشعار */
    @Volatile var recSince = 0L
    @Volatile var sessionId = ""
    @Volatile var transfer = ""
    @Volatile var hostName = ""
    /** شاشة على هذا الجوال بدها تشوف إطار الكاميرا كامل (قبل التسجيل) */
    @Volatile var wantLocal = false
    @Volatile var localFrame: android.graphics.Bitmap? = null
    @Volatile var localFrameAt = 0L

    class Local(val id: String, val sizeBytes: Long, val uploaded: Boolean, val hasVideo: Boolean)

    fun localSessions(ctx: Context): List<Local> {
        val root = File(ctx.filesDir, "sat_sessions")
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: return emptyList()
        return dirs.map { d ->
            Local(d.name, d.walkTopDown().filter { it.isFile }.sumOf { it.length() }, File(d, "uploaded.flag").exists(), File(d, "video.mp4").exists())
        }
    }

    fun deleteLocal(ctx: Context, id: String) {
        try { File(File(ctx.filesDir, "sat_sessions"), id).deleteRecursively() } catch (_: Throwable) {}
    }

    /**
     * بديل يدوي عن الشبكة: ينسخ ملفات التسجيل إلى Download/ReactionRecorder/<الجلسة>_<الجهاز>/
     * لتنقلها بأي طريقة (Quick Share، كابل…) ثم تستوردها في الجوال الرئيسي. الأصل يبقى هنا.
     */
    fun exportLocal(ctx: Context, id: String, progress: (String) -> Unit = {}): String? {
        val dir = File(File(ctx.filesDir, "sat_sessions"), id)
        if (!dir.exists()) return null
        val dev = SatCfg.load(ctx).devId
        val folder = android.os.Environment.DIRECTORY_DOWNLOADS + "/ReactionRecorder/" + id + "_" + dev
        var n = 0
        for (name in listOf("meta.json", "audio.wav", "video.mp4")) {
            val f = File(dir, name)
            if (!f.exists()) continue
            progress(name)
            try {
                val v = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, when { name.endsWith(".wav") -> "audio/wav"; name.endsWith(".mp4") -> "video/mp4"; else -> "application/json" })
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, folder)
                }
                val u = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: continue
                ctx.contentResolver.openOutputStream(u)?.use { out -> f.inputStream().use { it.copyTo(out, 256 * 1024) } }
                n++
            } catch (_: Throwable) {}
        }
        return if (n > 0) folder.replace(android.os.Environment.DIRECTORY_DOWNLOADS, "Download") else null
    }
}

/**
 * خدمة الجهاز المساعد (Foreground): تتصل بالجهاز الرئيسي، تقيس فرق الساعة، وتسجّل محليًا عند الأمر.
 * التسجيل مستقل عن الشبكة: لو انقطع الاتصال يستمر، وعند الرجوع يرفع الملفات ويكمل من حيث توقف.
 */
class SatelliteService : LifecycleService() {
    companion object {
        const val ACTION_CONNECT = "sat.CONNECT"
        const val ACTION_DISCONNECT = "sat.DISCONNECT"
        const val ACTION_STOPREC = "sat.STOPREC"
        const val ACTION_UPLOAD = "sat.UPLOAD"
        const val ACTION_FLIP = "sat.FLIP"
        /** تحديث حالة المعاينة المحلية (SatState.wantLocal) */
        const val ACTION_LOCALPV = "sat.LOCALPV"
        private const val CHANNEL = "satellite"
        private const val NOTIF_ID = 1002
    }

    private val main = Handler(Looper.getMainLooper())
    private val camExec = Executors.newSingleThreadExecutor()
    private val io = Executors.newCachedThreadPool()
    @Volatile private var enabled = false
    private var cfg = SatCfg()
    @Volatile private var conn: SatSocket? = null
    private var audio: SatAudioRec? = null
    private var video: SatVideoRec? = null
    private var curSession = ""
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var camProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private val uploading = ConcurrentHashMap.newKeySet<String>()
    /** جلسات انتهى تسجيلها ولم يُكتب ملف meta.json لها بعد */
    private val finalizing = ConcurrentHashMap.newKeySet<String>()

    // معاينة حية للجهاز الرئيسي
    @Volatile private var pvVideo = false
    @Volatile private var pvAudio = false
    @Volatile private var pvLevel = false
    @Volatile private var monLevel = 0
    private val pvQueue = LinkedBlockingQueue<JSONObject>(30)
    private val anExec = Executors.newSingleThreadExecutor()
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var monitor: SatMonitorMic? = null
    @Volatile private var lastPv = 0L
    @Volatile private var lastLocal = 0L
    @Volatile private var pvIv = 125
    @Volatile private var pvPx = 320
    /** صورة تأكيد واحدة تُرسل بعد بدء التسجيل (ليطمئن الجوال الرئيسي أن الكاميرا تصوّر) */
    @Volatile private var snapPending = false
    private val auAcc = ShortArray(4096)
    private var auN = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            if (physRot < 0) applyRot(curRot())
        }
    }

    /**
     * اتجاه الجوال الفعلي (من حساس الميلان) — مش اتجاه الشاشة. الشاشة ممكن تكون مقفولة عمودي
     * (أو قفل التدوير شغّال) والجوال واقف بالعرض؛ الفيديو لازم يتبع الجوال نفسه.
     */
    @Volatile private var physRot = -1
    private var orientListener: OrientationEventListener? = null

    private fun displayRot(): Int = try { (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).rotation } catch (_: Throwable) { 0 }

    /** الدوران المستعمل للكاميرا: الحساس إن عرفناه، وإلا الشاشة (الجوال نايم على الطاولة مثلًا) */
    private fun curRot(): Int = if (physRot >= 0) physRot else displayRot()

    private fun applyRot(r: Int) {
        // أثناء التسجيل الاتجاه مثبّت: تدوير الجوال لا يغيّر إطار الفيديو
        if (SatState.recording && lockedRot >= 0) return
        preview?.targetRotation = r
        analysis?.targetRotation = r
        video?.compositor?.displayQuarter = r
    }

    private fun startOrientation() {
        if (orientListener != null) return
        val l = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = SatNet.rotFromAngle(o, physRot)
                if (r != physRot) { physRot = r; main.post { applyRot(r) } }
            }
        }
        if (l.canDetectOrientation()) { l.enable(); orientListener = l }
    }

    private fun stopOrientation() { try { orientListener?.disable() } catch (_: Throwable) {}; orientListener = null; physRot = -1 }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        L.init(this)
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            ACTION_STOPREC -> main.post { stopRecording() }
            ACTION_FLIP -> main.post { flipCamera() }
            ACTION_LOCALPV -> main.post { refreshPreviewState() }
            ACTION_UPLOAD -> {
                val id = intent.getStringExtra("session") ?: ""
                if (id.isNotEmpty()) io.execute { uploadSession(id) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        enabled = false
        stopOrientation()
        releaseLocks()
        SatState.serviceOn = false
        SatState.connected = false
    }

    // ───────── الاتصال ─────────

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun connect() {
        if (enabled) return
        cfg = SatCfg.load(this)
        createChannel()
        if (!granted(android.Manifest.permission.RECORD_AUDIO)) {
            SatState.status = tx("يلزم إذن المايك", "Microphone permission is required")
            stopSelf(); return
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (cfg.role == "both" && granted(android.Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(NOTIF_ID, notif(tx("جارٍ الاتصال…", "Connecting…")), types)
            } else {
                startForeground(NOTIF_ID, notif(tx("جارٍ الاتصال…", "Connecting…")))
            }
        } catch (t: Throwable) {
            SatState.status = "Cannot start: ${t.message}"
            stopSelf(); return
        }
        enabled = true
        SatState.serviceOn = true
        acquireLocks()
        try { (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, main) } catch (_: Throwable) {}
        startOrientation()
        io.execute { loop() }
        io.execute { beaconLoop() }
    }

    /**
     * يستمع لنداء الجوال الرئيسي المقترن على الشبكة: إذا تغيّر عنوانه أو منفذه (هوتسبوت جديد، راوتر…)
     * نحدّث العنوان تلقائيًا فيرجع الاتصال بدون باركود ولا إدخال يدوي.
     */
    private fun beaconLoop() {
        var lock: WifiManager.MulticastLock? = null
        var sock: java.net.DatagramSocket? = null
        try {
            lock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createMulticastLock("rr:beacon").also { it.setReferenceCounted(false); it.acquire() }
        } catch (_: Throwable) {}
        try {
            sock = java.net.DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 3000; bind(InetSocketAddress(SatNet.BEACON_PORT)) }
            val buf = ByteArray(512)
            while (enabled) {
                val pk = java.net.DatagramPacket(buf, buf.size)
                try { sock.receive(pk) } catch (_: java.net.SocketTimeoutException) { continue }
                val b = SatNet.parseBeacon(String(pk.data, 0, pk.length, Charsets.UTF_8)) ?: continue
                if (cfg.hubId.isEmpty() || b.hubId != cfg.hubId) continue
                val ip = pk.address?.hostAddress ?: continue
                val h = "$ip:${b.port}"
                if (conn == null && !cfg.hosts().firstOrNull().equals(h)) {
                    val fresh = SatCfg.load(this)
                    fresh.host = SatNet.preferHost(fresh.host, h)
                    SatCfg.save(this, fresh)
                    cfg.host = fresh.host
                    goodHost = h
                }
                lastBeaconHost = h
            }
        } catch (_: Throwable) {
        } finally {
            try { sock?.close() } catch (_: Throwable) {}
            try { lock?.release() } catch (_: Throwable) {}
        }
    }

    @Volatile private var lastBeaconHost = ""

    private fun disconnect() {
        enabled = false
        main.post {
            stopRecording()
            try { (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
            stopOrientation()
        }
        try { conn?.close() } catch (_: Throwable) {}
        SatState.connected = false
        SatState.status = tx("غير متصل", "Not connected")
        releaseLocks()
        main.postDelayed({
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 1500)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rr:satellite").also { it.acquire() }
        } catch (_: Throwable) {}
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "rr:satellite").also { it.acquire() }
        } catch (_: Throwable) {}
    }

    private fun releaseLocks() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Throwable) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Throwable) {}
        wake = null; wifi = null
    }

    private fun loop() {
        while (enabled) {
            var ss: SatSocket? = null
            try {
                SatState.status = tx("جارٍ الاتصال بـ ", "Connecting to ") + (cfg.hubName.ifBlank { cfg.host }) + "…"
                val sock = openSocket(3500)
                if (sock == null) {
                    SatState.status = tx("الجوال الرئيسي غير ظاهر على الشبكة — نحاول مجددًا تلقائيًا", "Main phone not reachable — retrying automatically")
                    throw java.io.IOException("no route")
                }
                sock.soTimeout = 10_000
                ss = SatSocket(sock)
                ss.send(
                    SatNet.msg("hello").put("pin", cfg.pin).put("id", cfg.devId).put("name", cfg.name)
                        .put("role", cfg.role).put("ver", SatNet.VERSION)
                        .put("lid", License.deviceId(this)).put("lleft", License.remaining(this)).put("own", License.isOwner(this)),
                )
                val w = ss.read()
                if (w == null) {
                    SatState.status = tx("لا رد من الجوال الرئيسي", "No answer from the main phone")
                } else if (w.optString("t") != "welcome") {
                    SatState.status = tx("رُفض الاتصال: الرمز تغيّر — امسح الباركود من جديد", "Refused: the PIN changed — scan the QR code again")
                    ss.close()
                    sleepMs(4000)
                    continue
                } else {
                    conn = ss
                    SatState.connected = true
                    SatState.hostName = w.optString("name")
                    SatState.status = tx("متصل بـ ", "Connected to ") + SatState.hostName
                    if (cfg.hubName.isBlank() && SatState.hostName.isNotBlank()) { cfg.hubName = SatState.hostName; SatCfg.save(this, cfg) }
                    updateNotif()
                    val active = w.optString("active")
                    if (SatState.recording && curSession != active) main.post { stopRecording() }
                    val me = ss
                    pvQueue.clear()
                    io.execute { reporter(me) }
                    io.execute { pvSender(me) }
                    sendCfgs(me)
                    main.post { sendCaps() }
                    sendHave(me)
                    while (enabled) {
                        val m = ss.read() ?: break
                        handle(ss, m)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                if (conn === ss) {
                    conn = null
                    pvVideo = false; pvAudio = false; pvLevel = false
                    main.post { refreshPreviewState() }
                }
                SatState.connected = false
                try { ss?.close() } catch (_: Throwable) {}
            }
            if (enabled) {
                SatState.status = if (SatState.recording) tx("انقطع الاتصال — التسجيل مستمر على هذا الجوال، وسيرجع الاتصال تلقائيًا", "Disconnected — still recording here; reconnecting automatically")
                else tx("انقطع الاتصال — نعيد المحاولة تلقائيًا…", "Disconnected — retrying automatically…")
                updateNotif()
                sleepMs(1500)
            }
        }
    }

    private fun sleepMs(ms: Long) { try { Thread.sleep(ms) } catch (_: Throwable) {} }

    /** نخبر الجوال الرئيسي بكل تسجيل محفوظ هنا لم يُرسل بعد، فيطلبه مباشرة */
    private fun sendHave(ss: SatSocket) {
        val list = SatState.localSessions(this).filter { !it.uploaded && !(SatState.recording && it.id == curSession) }
        if (list.isEmpty()) return
        val a = org.json.JSONArray()
        for (x in list) a.put(x.id)
        ss.send(SatNet.msg("have").put("s", a))
    }

    private fun reporter(ss: SatSocket) {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        while (enabled && conn === ss) {
            val a = audio
            val lvl = a?.level ?: monLevel
            SatState.level = lvl
            val free = try { StatFs(filesDir.path).availableBytes / 1048576L } catch (_: Throwable) { -1L }
            val bat = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Throwable) { -1 }
            if (!ss.send(SatNet.msg("lvl").put("l", lvl).put("rec", SatState.recording).put("bat", bat).put("free", free).put("ll", if (License.isOwner(this)) -2L else License.remaining(this)))) break
            if (SatState.recording && free in 0..120) main.post { stopRecording() } // التخزين يكاد يمتلئ: نحفظ ما سُجِّل
            sleepMs(500)
        }
    }

    private fun handle(ss: SatSocket, m: JSONObject) {
        when (m.optString("t")) {
            "ping" -> {
                val t2 = SystemClock.elapsedRealtimeNanos()
                val id = m.optInt("id")
                ss.sendStamped { t3 -> SatNet.msg("pong").put("id", id).put("t2", t2).put("t3", t3) }
            }
            "start" -> {
                val id = m.optString("session")
                main.post { startRecording(id) }
            }
            "stop" -> main.post { stopRecording() }
            "flip" -> main.post { flipCamera() }
            "pv" -> {
                pvVideo = m.optBoolean("video")
                pvAudio = m.optBoolean("audio")
                pvLevel = m.optBoolean("level")
                pvIv = m.optInt("iv", 125).coerceIn(80, 5000)
                pvPx = m.optInt("px", 320).coerceIn(120, 480)
                main.post { refreshPreviewState() }
            }
            "cfg" -> main.post { applyRemoteCfg(m) }
            "send" -> {
                val id = m.optString("session")
                io.execute { uploadSession(id) }
            }
            "lic" -> {
                val err = License.apply(this, m.optString("l"))
                if (err == null) {
                    ss.send(SatNet.msg("licok").put("ll", License.remaining(this)))
                    SatState.status = tx("✓ فُعّل التطبيق لمدة ", "✓ App activated for ") + License.fmtLeft(License.remaining(this))
                } else ss.send(SatNet.msg("licerr").put("why", err))
            }
            "unlic" -> {
                if (!License.isOwner(this)) {
                    License.revoke(this)
                    SatState.status = tx("قفل صاحب التطبيق هذه النسخة", "The owner locked this copy")
                }
            }
        }
    }

    // ───────── التسجيل المحلي ─────────

    private fun sessionDir(id: String): File = File(File(filesDir, "sat_sessions"), id).also { it.mkdirs() }

    private fun startRecording(id: String) {
        if (id.isBlank()) return
        if (SatState.recording && curSession == id) return
        if (SatState.recording) stopRecording()
        monitor?.stop(); monitor = null
        flips.clear(); flipPending = null
        val dir = sessionDir(id)
        curSession = id
        cfg = SatCfg.load(this)
        var ok = false
        // 0 طبيعي، 1 المحيط (خام بدون معالجة)، 2 صوتي بس (عزل: وضع المكالمات + تنظيف الضجة)
        val srcType = when (cfg.aSrc) { 1 -> MediaRecorder.AudioSource.UNPROCESSED; 2 -> MediaRecorder.AudioSource.VOICE_COMMUNICATION; else -> MediaRecorder.AudioSource.MIC }
        val a = SatAudioRec(this, File(dir, "audio.wav"), srcType, cfg.gain)
        if (a.start()) {
            audio = a; ok = true
            a.tap = { b, n -> pushAudio(b, n, 4) }
        } else SatState.status = a.error
        if (cfg.role == "both") {
            if (startVideo(dir)) ok = true
        }
        SatState.recording = ok
        SatState.sessionId = id
        if (ok) {
            SatState.recSince = SystemClock.elapsedRealtime()
            SatState.status = tx("يسجّل على هذا الجوال", "Recording on this phone"); updateNotif(); buzz(true)
        }
        if (ok && cfg.role == "both") snapPending = true
        sendCfgs(conn)
        refreshPreviewState()
    }

    /** الاتجاه الفعلي: تلقائي = حسب وضع الجوال الآن (ثم يثبت طوال التسجيل) */
    private fun resolvePortrait(): Boolean = when (cfg.orient) {
        "portrait" -> true
        "landscape" -> false
        else -> {
            val r = curRot()
            r == android.view.Surface.ROTATION_0 || r == android.view.Surface.ROTATION_180
        }
    }

    /**
     * الدوران اللي بنثبّته للتسجيل: نفس اتجاه الجوال الحقيقي. إذا المستخدم فارض اتجاه (عمودي/عرضي)
     * والجوال مش هيك، بناخد أقرب دوران بنفس الاتجاه المفروض (الصورة بتطلع مستقيمة على هذا الأساس).
     */
    private fun lockRotFor(portrait: Boolean): Int {
        val r = curRot()
        val isP = r == android.view.Surface.ROTATION_0 || r == android.view.Surface.ROTATION_180
        return if (isP == portrait) r else if (portrait) android.view.Surface.ROTATION_0 else android.view.Surface.ROTATION_90
    }

    /** دوران الشاشة المثبّت أثناء التسجيل (-1 = غير مثبّت) */
    @Volatile private var lockedRot = -1

    private fun startVideo(dir: File): Boolean {
        cfg.portrait = resolvePortrait()
        SatCfg.save(this, cfg)
        lockedRot = lockRotFor(cfg.portrait)
        SatState.lockedOrient = if (cfg.portrait) 1 else 2
        val shortS = cfg.shortSide
        val longS = when (shortS) { 480 -> 848; 1080 -> 1920; else -> 1280 }
        val w = if (cfg.portrait) shortS else longS
        val h = if (cfg.portrait) longS else shortS
        val br = when (shortS) { 480 -> 2_500_000; 1080 -> 12_000_000; else -> 5_000_000 }
        val v = SatVideoRec(File(dir, "video.mp4"), w, h, 30, br)
        return try {
            v.prepare()
            video = v
            bindCamera(v)
            v.begin()
            true
        } catch (t: Throwable) {
            SatState.status = tx("فشل تسجيل الكاميرا: ", "Camera recording failed: ") + (t.message ?: t.javaClass.simpleName)
            try { v.stop() } catch (_: Throwable) {}
            video = null
            false
        }
    }

    /**
     * صورة أنظف وقت الحركة: ٣٠ إطار ثابتة (الكاميرا ما بتنزل لـ ١٥ بالضو الخفيف فتصير الصورة ممغوطة ومتقطعة)
     * + تثبيت الصورة الإلكتروني إذا الكاميرا بتدعمه.
     */
    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun tuneCamera(p: ProcessCameraProvider, pb: Preview.Builder) {
        try {
            val info = selector().filter(p.availableCameraInfos).firstOrNull() ?: return
            val ch = androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
            val ext = androidx.camera.camera2.interop.Camera2Interop.Extender(pb)
            val ranges = ch.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val best = ranges?.filter { it.upper == 30 && it.lower >= 24 }?.maxByOrNull { it.lower }
            if (best != null) ext.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best)
            val modes = ch.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            val on = android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            if (cfg.stab && modes?.contains(on) == true) ext.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, on)
        } catch (_: Throwable) {}
    }

    private fun bindCamera(v: SatVideoRec) {
        val comp = v.compositor
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                camProvider = p
                p.unbindAll()
                val long = cfg.camLong
                val rs = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(long, long * 9 / 16), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val rot = if (lockedRot >= 0) lockedRot else curRot()
                val pb = Preview.Builder().setResolutionSelector(rs).setTargetRotation(rot)
                tuneCamera(p, pb)
                val pv = pb.build()
                pv.setSurfaceProvider(camExec) { request ->
                    val res = request.resolution
                    comp.setCameraBufferSize(0, res.width, res.height)
                    request.provideSurface(comp.cameraSurfaceFor(0), camExec) { }
                    request.setTransformationInfoListener(camExec) { info -> comp.setCameraInfoRotation(0, info.rotationDegrees) }
                }
                preview = pv
                comp.displayQuarter = rot
                comp.cam0IsFront = false
                comp.cameraVisible = true
                comp.setCameraMirror(0, cfg.front && cfg.mirror)
                comp.cameraAdjust = cfg.rot
                val an = buildAnalysis(rot)
                analysis = an
                val cam = p.bindToLifecycle(this, selector(), pv, an)
                camera = cam
                applyCamControls(cam)
                sendCaps()
                flipPending?.let { f -> f[1] = SystemClock.elapsedRealtimeNanos() + 400_000_000L; flipPending = null }
            } catch (t: Throwable) {
                SatState.status = "Camera error: ${t.message ?: t.javaClass.simpleName}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** فترات قلب الكاميرا أثناء التسجيل [بداية، نهاية] بساعة هذا الجهاز (ns) — المونتاج يملؤها حسب اختيارك */
    private val flips = ArrayList<LongArray>()

    /**
     * قلب الكاميرا (أمامية/خلفية) — حتى أثناء التسجيل. الفيديو لا يتوقف: خلال لحظة التبديل يبقى آخر إطار،
     * ونسجّل الفترة ليملأها المونتاج (آخر صورة، كاميرا أخرى، أو تمويه).
     */
    private fun flipCamera() {
        if (cfg.role != "both") return
        cfg.front = !cfg.front
        SatCfg.save(this, cfg)
        val v = video
        if (SatState.recording && v != null) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val f = LongArray(2) { t0 }
            flips.add(f)
            flipPending = f
            bindCamera(v)
        } else if (analysis != null) {
            try { camProvider?.unbindAll() } catch (_: Throwable) {}
            analysis = null; camera = null
            refreshPreviewState()
        }
        sendCfgs(conn)
        SatState.status = if (cfg.front) tx("الكاميرا الأمامية", "Front camera") else tx("الكاميرا الخلفية", "Back camera")
    }

    @Volatile private var flipPending: LongArray? = null

    private fun stopRecording() {
        val a = audio
        val v = video
        if (!SatState.recording && a == null && v == null) return
        val id = curSession
        audio = null
        video = null
        SatState.recording = false
        SatState.recSince = 0L
        buzz(false)
        lockedRot = -1
        SatState.lockedOrient = 0
        snapPending = false
        try { camProvider?.unbindAll() } catch (_: Throwable) {}
        preview = null
        analysis = null
        camera = null
        a?.tap = null
        SatState.status = tx("جارٍ الحفظ…", "Saving…")
        finalizing.add(id)
        sendCfgs(conn)
        refreshPreviewState()
        io.execute {
            try {
                a?.stop()
                val vOk = try { v?.stop() ?: false } catch (_: Throwable) { false }
                writeMeta(id, a, v, vOk)
            } finally {
                finalizing.remove(id)
            }
            SatState.status = if (SatState.connected) tx("متصل — حُفظ التسجيل، جارٍ إرساله", "Connected — saved, sending") else tx("حُفظ على هذا الجوال — سيُرسل عند رجوع الاتصال", "Saved here — will be sent when reconnected")
            updateNotif()
            // إن كنا متصلين نعرض ما عندنا فورًا (لا ننتظر طلب الجوال الرئيسي)
            conn?.let { sendHave(it) }
        }
    }

    private fun writeMeta(id: String, a: SatAudioRec?, v: SatVideoRec?, vOk: Boolean) {
        try {
            val o = JSONObject().put("v", 1).put("session", id).put("dev", cfg.devId).put("role", cfg.role).put("name", cfg.name)
            if (a != null && a.frames > 0L) {
                o.put(
                    "audio",
                    JSONObject().put("file", "audio.wav").put("rate", a.rate).put("frames", a.frames)
                        .put("f0", a.f0).put("t0", a.t0).put("f1", a.f1).put("t1", a.t1)
                        .put("tsOk", a.tsOk).put("fallbackStart", a.fallbackStart),
                )
            }
            if (v != null && vOk) {
                o.put(
                    "video",
                    JSONObject().put("file", "video.mp4").put("startBoot", v.startElapsedNs).put("first", try { v.compositor.firstCamNs } catch (_: Throwable) { 0L })
                        .put("w", v.width).put("h", v.height).put("mirror", cfg.front && cfg.mirror).put("front", cfg.front)
                        .put("flips", org.json.JSONArray().also { a -> for (f in flips) a.put(org.json.JSONArray().put(f[0]).put(f[1])) }),
                )
            }
            File(sessionDir(id), "meta.json").writeText(o.toString())
        } catch (_: Throwable) {
        }
    }

    // ───────── معاينة حية وتحكم عن بُعد ─────────

    private fun selector() = if (cfg.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    /** يجرّب العناوين بالترتيب (الذي نجح آخر مرة أولًا) */
    @Volatile private var goodHost = ""

    private fun openSocket(timeoutMs: Int): Socket? {
        val hs = cfg.hosts().toMutableList()
        if (goodHost.isNotEmpty() && hs.remove(goodHost)) hs.add(0, goodHost)
        for (h in hs) {
            try {
                val sock = Socket()
                val ix = h.lastIndexOf(':')
                val addr = if (ix > 0 && h.indexOf(':') == ix) InetSocketAddress(h.substring(0, ix), h.substring(ix + 1).toIntOrNull() ?: SatNet.PORT)
                else InetSocketAddress(h, SatNet.PORT)
                sock.connect(addr, timeoutMs)
                goodHost = h
                return sock
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun enqueue(j: JSONObject) {
        if (!pvQueue.offer(j)) { pvQueue.poll(); pvQueue.offer(j) }
    }

    private fun pvSender(ss: SatSocket) {
        while (enabled && conn === ss) {
            val j = try { pvQueue.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) { null } ?: continue
            if (!ss.send(j)) break
        }
    }

    private fun buildAnalysis(rot: Int): ImageAnalysis {
        // 16:9 مثل التسجيل نفسه — المعاينة بتفرجي نفس الإطار اللي بيتسجّل بالزبط
        val rs = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val an = ImageAnalysis.Builder()
            .setResolutionSelector(rs)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rot)
            .build()
        an.setAnalyzer(anExec) { img -> analyze(img) }
        return an
    }

    private fun analyze(img: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            // معاينة محلية (شاشة "الإطار كامل" على نفس الجوال)
            if (SatState.wantLocal && now - lastLocal >= 120) {
                lastLocal = now
                val b0 = img.toBitmap()
                val r0 = (img.imageInfo.rotationDegrees + cfg.rot) % 360
                val m0 = Matrix()
                if (r0 != 0) m0.postRotate(r0.toFloat())
                if (cfg.front && cfg.mirror) m0.postScale(-1f, 1f)
                val s0 = 640f / maxOf(b0.width, b0.height)
                if (s0 < 1f) m0.postScale(s0, s0)
                SatState.localFrame = Bitmap.createBitmap(b0, 0, 0, b0.width, b0.height, m0, true)
                SatState.localFrameAt = now
            }
            val snap = snapPending
            if ((!pvVideo && !snap) || conn == null || (!snap && now - lastPv < pvIv)) return
            lastPv = now
            snapPending = false
            val bmp = img.toBitmap()
            val rot = (img.imageInfo.rotationDegrees + cfg.rot) % 360
            val m = Matrix()
            if (rot != 0) m.postRotate(rot.toFloat())
            if (cfg.front && cfg.mirror) m.postScale(-1f, 1f)
            val sc = (if (snap) 240f else pvPx.toFloat()) / maxOf(bmp.width, bmp.height)
            m.postScale(sc, sc)
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val bos = java.io.ByteArrayOutputStream(16 * 1024)
            out.compress(Bitmap.CompressFormat.JPEG, 55, bos)
            val w = out.width
            val h = out.height
            if (out !== bmp) out.recycle()
            bmp.recycle()
            enqueue(SatNet.msg("pvf").put("w", w).put("h", h).put("snap", snap).put("j", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)))
        } catch (_: Throwable) {
        } finally {
            img.close()
        }
    }

    /** يضيف صوتًا للمعاينة. step=1 للميكروفون الخفيف (11kHz)، 4 لتسجيل 44.1kHz (نأخذ متوسط كل 4) */
    @Synchronized
    private fun pushAudio(src: ShortArray, n: Int, step: Int) {
        if (!pvAudio) return
        var i = 0
        while (i + step <= n) {
            var sum = 0
            for (k in 0 until step) sum += src[i + k]
            auAcc[auN++] = (sum / step).toShort()
            i += step
            if (auN >= 441) {
                val b = ByteArray(auN * 2)
                for (j in 0 until auN) { b[j * 2] = (auAcc[j].toInt() and 0xFF).toByte(); b[j * 2 + 1] = (auAcc[j].toInt() shr 8).toByte() }
                auN = 0
                enqueue(SatNet.msg("au").put("d", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)))
            }
        }
    }

    /** يشغّل/يوقف الميكروفون الخفيف وكاميرا المعاينة بحسب ما يطلبه الجهاز الرئيسي (خارج التسجيل) */
    private fun refreshPreviewState() {
        val rec = SatState.recording
        val ok = enabled && SatState.connected
        val wantMon = (pvAudio || pvLevel) && !rec && ok
        if (wantMon && monitor == null) {
            val mm = SatMonitorMic({ b, n -> pushAudio(b, n, 1) }, { monLevel = it })
            if (mm.start()) monitor = mm
        } else if (!wantMon && monitor != null) {
            monitor?.stop(); monitor = null
        }
        val wantCam = ((pvVideo && ok) || (SatState.wantLocal && enabled)) && cfg.role == "both" && !rec && granted(android.Manifest.permission.CAMERA)
        if (wantCam && analysis == null) bindIdle()
        else if (!wantCam && !rec && analysis != null) {
            try { camProvider?.unbindAll() } catch (_: Throwable) {}
            analysis = null; camera = null
        }
    }

    private fun bindIdle() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (SatState.recording) return@addListener
                val p = future.get()
                camProvider = p
                p.unbindAll()
                val rot = curRot()
                val an = buildAnalysis(rot)
                analysis = an
                val cam = p.bindToLifecycle(this, selector(), an)
                camera = cam
                applyCamControls(cam)
                sendCaps()
            } catch (t: Throwable) {
                analysis = null
                SatState.status = "Camera error: ${t.message ?: t.javaClass.simpleName}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyCamControls(cam: Camera) {
        try {
            val zs = cam.cameraInfo.zoomState.value
            if (zs != null) cam.cameraControl.setZoomRatio(cfg.zoom.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
            val er = cam.cameraInfo.exposureState.exposureCompensationRange
            cam.cameraControl.setExposureCompensationIndex(cfg.exp.coerceIn(er.lower, er.upper))
            cam.cameraControl.enableTorch(cfg.torch && cam.cameraInfo.hasFlashUnit())
        } catch (_: Throwable) {
        }
    }

    /** تركيز بلمسة: (x,y) من 0 لـ 1 على الصورة المسجّلة — بنحوّلها لاتجاه حسّاس الكاميرا */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        try {
            var sx = x; var sy = y
            if (cfg.portrait) { sx = y; sy = 1f - x }
            if (cfg.front) sy = 1f - sy
            val pt = androidx.camera.core.SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(sx.coerceIn(0f, 1f), sy.coerceIn(0f, 1f))
            cam.cameraControl.startFocusAndMetering(
                androidx.camera.core.FocusMeteringAction.Builder(pt).setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS).build(),
            )
        } catch (_: Throwable) {}
    }

    /** اهتزاز عند بدء/إيقاف التسجيل — عشان تعرف إنه بلّش أو وقف حتى والشاشة مطفية */
    private fun buzz(start: Boolean) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(android.os.VibratorManager::class.java)).defaultVibrator
                else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
            val pat = if (start) longArrayOf(0, 120) else longArrayOf(0, 90, 120, 90)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(pat, -1)) else @Suppress("DEPRECATION") v.vibrate(pat, -1)
        } catch (_: Throwable) {}
    }

    private fun applyRemoteCfg(m: JSONObject) {
        val rec = SatState.recording
        var rebind = false
        if (!rec) {
            if (m.has("front")) { cfg.front = m.optBoolean("front"); rebind = true }
            if (m.has("q")) {
                val q = m.optInt("q", 720)
                cfg.shortSide = q
                cfg.camLong = when (q) { 480 -> 854; 1080 -> 1920; else -> 1280 }
                rebind = true
            }
            if (m.has("portrait")) cfg.orient = if (m.optBoolean("portrait")) "portrait" else "landscape"
            if (m.has("orient")) cfg.orient = m.optString("orient", "auto")
            if (m.has("aSrc")) cfg.aSrc = m.optInt("aSrc").coerceIn(0, 2)
            if (m.has("stab")) cfg.stab = m.optBoolean("stab", true)
            if (m.has("role")) {
                val r = if (m.optString("role") == "both") "both" else "mic"
                if (r != cfg.role) {
                    if (r == "both" && !granted(android.Manifest.permission.CAMERA)) {
                        SatState.status = tx("الجوال الرئيسي بدّه هالجوال كاميرا — بس إذن الكاميرا مش مسموح هون", "The main phone wants this phone as a camera — but camera permission isn't granted here")
                    } else {
                        cfg.role = r
                        if (Build.VERSION.SDK_INT >= 30) try {
                            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            if (r == "both") types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                            startForeground(NOTIF_ID, notif(SatState.status), types)
                        } catch (_: Throwable) {}
                        if (r == "mic") { try { camProvider?.unbindAll() } catch (_: Throwable) {}; analysis = null; camera = null; preview = null }
                        rebind = r == "both"
                        SatState.status = if (r == "both") tx("الجوال الرئيسي خلّى هالجوال: كاميرا ومايك", "The main phone set this phone to: camera and mic") else tx("الجوال الرئيسي خلّى هالجوال: مايك لاسلكي", "The main phone set this phone to: wireless mic")
                    }
                }
            }
        }
        if (m.has("afx")) focusAt(m.optDouble("afx", 0.5).toFloat(), m.optDouble("afy", 0.5).toFloat())
        if (m.has("rot")) cfg.rot = ((m.optInt("rot") % 360) + 360) % 360
        if (m.has("mirror")) cfg.mirror = m.optBoolean("mirror")
        if (m.has("zoom")) cfg.zoom = m.optDouble("zoom", 1.0).toFloat()
        if (m.has("exp")) cfg.exp = m.optInt("exp")
        if (m.has("torch")) cfg.torch = m.optBoolean("torch")
        if (m.has("gain")) { cfg.gain = m.optDouble("gain", 1.0).toFloat().coerceIn(0.1f, 4f); audio?.gain = cfg.gain }
        SatCfg.save(this, cfg)
        video?.compositor?.let { it.cameraAdjust = cfg.rot; it.setCameraMirror(0, cfg.front && cfg.mirror) }
        camera?.let { applyCamControls(it) }
        if (rebind && !rec && analysis != null) {
            try { camProvider?.unbindAll() } catch (_: Throwable) {}
            analysis = null; camera = null
            refreshPreviewState()
        }
        sendCfgs(conn)
    }

    private fun sendCfgs(ss: SatSocket?) {
        ss ?: return
        ss.send(
            SatNet.msg("cfgs").put("front", cfg.front).put("q", cfg.shortSide).put("portrait", cfg.portrait).put("orient", cfg.orient)
                .put("rot", cfg.rot).put("mirror", cfg.mirror).put("zoom", cfg.zoom.toDouble()).put("exp", cfg.exp)
                .put("torch", cfg.torch).put("gain", cfg.gain.toDouble()).put("aSrc", cfg.aSrc).put("role", cfg.role)
                .put("rec", SatState.recording),
        )
    }

    private fun sendCaps() {
        val c = conn ?: return
        val ci = camera?.cameraInfo
        val zs = ci?.zoomState?.value
        val er = ci?.exposureState?.exposureCompensationRange
        c.send(
            SatNet.msg("caps").put("zmin", (zs?.minZoomRatio ?: 1f).toDouble()).put("zmax", (zs?.maxZoomRatio ?: 1f).toDouble())
                .put("emin", er?.lower ?: 0).put("emax", er?.upper ?: 0).put("flash", ci?.hasFlashUnit() ?: false),
        )
    }

    // ───────── رفع الملفات ─────────

    private fun uploadSession(id: String) {
        if (id.isBlank()) return
        if (SatState.recording && curSession == id) {
            // طُلب الإرسال وهذه الجلسة ما زالت تسجّل هنا (انتهت عند الرئيسي أثناء الانقطاع): نوقفها أولًا ثم نرسل
            main.post { if (SatState.recording && curSession == id) stopRecording() }
            var w = 0
            while (SatState.recording && curSession == id && w < 10_000) { sleepMs(100); w += 100 }
            sleepMs(300)
        }
        val dir = File(File(filesDir, "sat_sessions"), id)
        // ننتظر انتهاء حفظ التسجيل (قد يصل طلب الإرسال قبل اكتمال الحفظ)
        var waited = 0
        while (finalizing.contains(id) && waited < 30_000) { sleepMs(250); waited += 250 }
        if (!dir.exists()) {
            conn?.send(SatNet.msg("nosession").put("session", id))
            return
        }
        if (!File(dir, "meta.json").exists()) {
            // تسجيل انقطع بشكل غير طبيعي (إغلاق مفاجئ): نصلح ملف الصوت ونكتب وصفًا احتياطيًا
            val wav = File(dir, "audio.wav")
            if (wav.exists()) {
                WavWriter.fixHeader(wav, 44100, 1)
                // تقدير البداية من وقت آخر تعديل للملف (أقل دقة؛ يمكن ضبطه يدويًا عند الدمج)
                val frames = ((wav.length() - 44L).coerceAtLeast(0L)) / 2L
                val endNs = SystemClock.elapsedRealtimeNanos() - (System.currentTimeMillis() - wav.lastModified()) * 1_000_000L
                val startNs = endNs - frames * 1_000_000_000L / 44100L
                try {
                    File(dir, "meta.json").writeText(
                        JSONObject().put("v", 1).put("session", id).put("dev", cfg.devId).put("role", cfg.role).put("name", cfg.name)
                            .put("audio", JSONObject().put("file", "audio.wav").put("rate", 44100).put("frames", frames).put("tsOk", false).put("fallbackStart", startNs))
                            .toString(),
                    )
                } catch (_: Throwable) {}
            }
            if (!File(dir, "meta.json").exists()) {
                conn?.send(SatNet.msg("nosession").put("session", id))
                return
            }
        }
        if (!uploading.add(id)) return
        try {
            val names = listOf("audio.wav", "video.mp4", "meta.json").filter { File(dir, it).exists() }
            var allOk = true
            for (n in names) {
                var ok = false
                var tries = 0
                while (!ok && tries < 3 && enabled) {
                    tries++
                    ok = uploadFile(dir, n, id)
                }
                if (!ok) { allOk = false; break }
            }
            if (allOk) {
                try { File(dir, "uploaded.flag").writeText("1") } catch (_: Throwable) {}
                // هذا الجوال نفسه هو المستقبِل (وضع البودكاست): لا داعي لنسخة ثانية
                if (goodHost.startsWith("127.0.0.1") || goodHost.startsWith("localhost")) SatState.deleteLocal(this, id)
                conn?.send(SatNet.msg("sent").put("session", id))
                SatState.transfer = ""
            } else {
                SatState.transfer = tx("انقطع الإرسال — يكمل تلقائيًا من حيث توقف عند رجوع الاتصال", "Upload interrupted — it resumes automatically when reconnected")
            }
        } finally {
            uploading.remove(id)
        }
    }

    private fun uploadFile(dir: File, name: String, session: String): Boolean {
        val f = File(dir, name)
        var ss: SatSocket? = null
        return try {
            val sock = openSocket(4000) ?: return false
            sock.soTimeout = 20_000
            val s = SatSocket(sock)
            ss = s
            s.send(
                SatNet.msg("upload").put("pin", cfg.pin).put("dev", cfg.devId).put("session", session)
                    .put("name", name).put("size", f.length()),
            )
            val r = s.read() ?: return false
            val offset = r.optLong("offset", 0L)
            if (offset < f.length()) {
                val out = s.rawOut()
                val buf = ByteArray(64 * 1024)
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(offset)
                    var sent = offset
                    while (true) {
                        val n = raf.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        SatState.transfer = tx("إرسال ", "Sending ") + "$name ${if (f.length() > 0) sent * 100 / f.length() else 100}%"
                    }
                }
                out.flush()
            }
            val d = s.read()
            d != null && d.optString("t") == "done"
        } catch (_: Throwable) {
            false
        } finally {
            try { ss?.close() } catch (_: Throwable) {}
        }
    }

    // ───────── إشعار ─────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tx("جهاز مساعد", "Remote device"), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notif(text: String): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, SatelliteActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val rec = SatState.recording && SatState.recSince > 0
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (rec) tx("🔴 بيسجّل — عادي تطفي الشاشة", "🔴 Recording — fine to turn the screen off") else tx("Reaction Recorder — جهاز مساعد", "Reaction Recorder — remote device"))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
        if (rec) b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - SatState.recSince)).setShowWhen(true)
        return b.build()
    }

    private fun updateNotif() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notif(SatState.status))
        } catch (_: Throwable) {}
    }
}
