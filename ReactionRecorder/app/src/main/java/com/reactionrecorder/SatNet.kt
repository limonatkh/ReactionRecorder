package com.reactionrecorder

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket

/** قناة JSON سطر-بسطر فوق TCP. الإرسال من أي خيط آمن؛ القراءة من خيط واحد */
class SatSocket(val socket: Socket) {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val out = socket.getOutputStream()

    init {
        try { socket.tcpNoDelay = true } catch (_: Throwable) {}
        try { socket.keepAlive = true } catch (_: Throwable) {}
    }

    @Volatile private var closed = false
    private val lock = Any()
    /** كاتب خلفي للرسائل المرسلة من الخيط الرئيسي (أندرويد يمنع الشبكة على الخيط الرئيسي) */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * يرسل رسالة. من خيط خلفي: كتابة مباشرة ويعيد النتيجة الفعلية.
     * من الخيط الرئيسي: تُرسل في الخلفية ويعيد true ما دام الاتصال مفتوحًا.
     */
    fun send(o: JSONObject): Boolean {
        if (closed) return false
        val bytes = (o.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return try {
                writer.execute { write(bytes) }
                true
            } catch (_: Throwable) {
                false
            }
        }
        return write(bytes)
    }

    /** يرسل رسالة يُحسب وقتها لحظة الكتابة الفعلية (لقياس الساعة بدقة). من خيط خلفي فقط */
    fun sendStamped(build: (Long) -> JSONObject): Boolean {
        if (closed) return false
        return synchronized(lock) {
            try {
                val o = build(android.os.SystemClock.elapsedRealtimeNanos())
                out.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun write(bytes: ByteArray): Boolean = synchronized(lock) {
        try {
            out.write(bytes)
            out.flush()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** يقرأ رسالة؛ null عند إغلاق الاتصال أو انتهاء المهلة أو رسالة غير صالحة */
    fun read(): JSONObject? = try {
        val line = reader.readLine()
        if (line == null) null else JSONObject(line)
    } catch (_: Throwable) {
        null
    }

    fun rawIn() = socket.getInputStream()
    fun rawOut() = socket.getOutputStream()

    fun close() {
        closed = true
        try { socket.close() } catch (_: Throwable) {}
        try { writer.shutdown() } catch (_: Throwable) {}
    }
}

object SatNet {
    const val PORT = 47600
    const val VERSION = 1

    /** يحوّل الأرقام العربية/الفارسية إلى 0-9 (لوحات المفاتيح العربية) */
    fun normDigits(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                else -> c
            },
        )
    }

    fun msg(type: String): JSONObject = JSONObject().put("t", type)

    /** نفس localIps بدون اسم الواجهة (لباركود الاقتران) */
    fun localIpsPlain(): List<String> = localIps().map { it.substringBefore(' ') }

    /** باركود الاقتران v2: العناوين + المنفذ + الرمز + هوية الجوال الرئيسي (للرجوع التلقائي حتى لو تغيّر عنوانه) */
    fun qrPayload(pin: String, port: Int, hubId: String = "", hubName: String = ""): String =
        "RRSAT2|" + localIpsPlain().joinToString(",") { "$it:$port" } + "|" + port + "|" + pin + "|" + hubId + "|" + hubName.replace("|", " ")

    class Qr(val hosts: String, val pin: String, val hubId: String, val hubName: String)

    /** يقرأ باركود v1 أو v2؛ null إن لم يكن باركودنا */
    fun parseQr(raw0: String): Qr? {
        var raw = raw0.trim()
        // باركود المشاركة: رابط تنزيل التطبيق يحمل بيانات الاقتران في p=
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val q = raw.substringAfter('?', "").split('&').firstOrNull { it.startsWith("p=") }?.substring(2) ?: return null
            raw = try { java.net.URLDecoder.decode(q, "UTF-8") } catch (_: Throwable) { return null }
        }
        val p = raw.trim().split('|')
        if (p.size < 4 || (p[0] != "RRSAT1" && p[0] != "RRSAT2")) return null
        val pin = normDigits(p[3]).trim()
        if (!pin.matches(Regex("[0-9]{4}"))) return null
        val port = p[2].trim().toIntOrNull() ?: PORT
        // عناوين بدون منفذ نضيف لها المنفذ المعلن
        val hosts = p[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(",") { if (it.contains(':')) it else "$it:$port" }
        if (hosts.isEmpty()) return null
        return Qr(hosts, pin, p.getOrNull(4)?.trim() ?: "", p.getOrNull(5)?.trim() ?: "")
    }

    /**
     * زاوية الحساس (0..359، 0 = الجوال واقف طبيعي) → دوران CameraX (Surface.ROTATION_*: 0,1,2,3).
     * بهامش ثبات: ما بنغيّر إلا لما الزاوية تقرب 35° من الاتجاه الجديد (ما يرجف عند 45°).
     */
    fun rotFromAngle(angle: Int, current: Int): Int {
        val a = ((angle % 360) + 360) % 360
        // الحساس: 90 = الجوال مايل لليمين (أعلاه يمين) → ROTATION_270
        fun center(r: Int) = when (r) { 0 -> 0; 3 -> 90; 2 -> 180; else -> 270 }
        fun dist(x: Int, y: Int): Int { val d = Math.abs(x - y) % 360; return if (d > 180) 360 - d else d }
        if (current in 0..3 && dist(a, center(current)) <= 55) return current
        return when (a) { in 45 until 135 -> 3; in 135 until 225 -> 2; in 225 until 315 -> 1; else -> 0 }
    }

    /** رابط المشاركة: يفتح صفحة تنزيل التطبيق في المتصفح، وتطبيقنا يقرأ منه بيانات الاقتران */
    fun shareUrl(ip: String, httpPort: Int, pairPayload: String): String =
        "http://$ip:$httpPort/j?p=" + java.net.URLEncoder.encode(pairPayload, "UTF-8")

    /** نداء الجوال الرئيسي على الشبكة المحلية (كل ثانيتين) كي تجده الأجهزة حتى لو تغيّر عنوانه أو منفذه */
    const val BEACON_PORT = 47611

    fun beacon(hubId: String, port: Int, name: String): String = "RRHUB2|$hubId|$port|" + name.replace("|", " ")

    class Beacon(val hubId: String, val port: Int, val name: String)

    fun parseBeacon(s: String): Beacon? {
        val p = s.trim().split('|')
        if (p.size < 3 || p[0] != "RRHUB2" || p[1].isBlank()) return null
        val port = p[2].toIntOrNull() ?: return null
        return Beacon(p[1], port, p.getOrNull(3) ?: "")
    }

    /** يضع العنوان الجديد أولًا في قائمة العناوين (بدون تكرار) */
    fun preferHost(hosts: String, h: String): String =
        (listOf(h) + hosts.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() && it != h }).take(6).joinToString(",")

    /** عناوين البث لكل شبكة محلية (واي فاي / هوتسبوت) */
    fun broadcastAddrs(): List<java.net.InetAddress> {
        val res = ArrayList<java.net.InetAddress>()
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en != null && en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (ia.address is Inet4Address) res.add(b)
                }
            }
        } catch (_: Throwable) {}
        try { res.add(java.net.InetAddress.getByName("255.255.255.255")) } catch (_: Throwable) {}
        return res
    }

    /** عناوين IPv4 المحلية لهذا الجوال (واي فاي / هوتسبوت) */
    fun localIps(): List<String> {
        val res = ArrayList<String>()
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en != null && en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && a.isSiteLocalAddress) res.add(a.hostAddress + "  (" + ni.name + ")")
                }
            }
        } catch (_: Throwable) {
        }
        return res
    }
}
