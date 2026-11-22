package com.reactionrecorder

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * سيرفر محلي على جوال المدير للانضمام من المتصفح (iPhone/iPad، أندرويد، ويندوز…) — بدون إنترنت.
 *
 *  • http://IP:47680/  صفحة التجهيز: تنزيل الشهادة وخطوات الثقة حسب الجهاز (بتشتغل قبل الثقة).
 *  • https://IP:47643/ صفحة الانضمام والفحص (الكاميرا والمايك بيشتغلوا هون بس — سياق آمن).
 *
 * كل جهاز لازم المدير يوافق عليه؛ بعدها بياخد «مفتاح» بيرافق كل طلب. الصلاحيات بتنفحص بالسيرفر،
 * مش بإخفاء أزرار.
 *
 * ما فيه ولا إشي من أندرويد (عشان ينفحص على الكمبيوتر مع متصفح حقيقي): كل اللي بيحتاجه من الجوال بيجي من [Env].
 */
class WebHub(private val env: Env) {
    interface Env {
        /** مجلد البيانات (الشهادة، التقارير، تسجيلات الفحص) */
        val dataDir: File
        /** ملف من assets/web (أو null) */
        fun asset(path: String): ByteArray?
        /** ساعة الجلسة (نانو ثانية، رتيبة) */
        fun nowNs(): Long
        /** اسم الجوال (بيطلع بالبروفايل والصفحة) */
        val label: String
        /** صار تغيير (جهاز جديد، تقرير…) → الواجهة بتتحدّث */
        fun changed()
    }

    class Dev(val id: String) {
        var name = ""
        var platform = ""
        var ua = ""
        /** pending / approved / denied */
        var state = "pending"
        var token = ""
        var lastSeen = 0L
        var ip = ""
        var report: JSONObject? = null
        var reportAt = 0L
        /** تسجيلات الفحص اللي وصلت: rec → وصف */
        val recs = ConcurrentHashMap<String, JSONObject>()
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("platform", platform).put("state", state).put("ip", ip).put("seen", lastSeen)
    }

    val devices = ConcurrentHashMap<String, Dev>()
    @Volatile var running = false; private set
    @Volatile var httpsPort = HTTPS_PORT; private set
    @Volatile var httpPort = HTTP_PORT; private set
    @Volatile var names: List<String> = emptyList(); private set
    @Volatile var lastError: String? = null
    private var ca: WebCa.Ca? = null
    private var httpsSock: ServerSocket? = null
    private var httpSock: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val rnd = SecureRandom()

    private val webDir get() = File(env.dataDir, "web").also { it.mkdirs() }

    /** السلطة (بتنعمل أول مرة بس) */
    fun ca(): WebCa.Ca {
        ca?.let { return it }
        val dir = File(webDir, "ca")
        val c = WebCa.load(dir) ?: WebCa.newCa(env.label).also { WebCa.save(it, dir) }
        ca = c
        return c
    }

    fun caFingerprint(): String = WebCa.fingerprint(ca().der)

    /**
     * يشغّل السيرفرين. @param addrs عناوين الجوال المحلية (IPv4) — الشهادة بتنعمل إلها + rr.local
     */
    fun start(addrs: List<String>, https: Int = HTTPS_PORT, http: Int = HTTP_PORT) {
        if (running) stop()
        loadDevices()
        val nm = (addrs.filter { isPrivate(it) } + "rr.local").distinct()
        val srv = WebCa.newServer(ca(), nm)
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val pw = CharArray(16) { 'a' + rnd.nextInt(26) }
        ks.setKeyEntry("srv", srv.key, pw, srv.chain)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pw) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, rnd) }
        // منافذ خاصة فينا (٨٠٨٠ مستعمل لمشاركة التطبيق). نفس المنفذ كل مرة = نفس «الموقع» بالمتصفح (التخزين المحلي بيضل)
        var hs: ServerSocket? = null; var hp: ServerSocket? = null
        try {
            for (pt in https until https + 6) {
                try {
                    hs = (ctx.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                        reuseAddress = true; bind(InetSocketAddress(pt), 64)
                        enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                    }; break
                } catch (_: java.net.BindException) { hs = null }
            }
            for (pt in http until http + 6) {
                try { hp = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(pt), 64) }; break } catch (_: java.net.BindException) { hp = null }
            }
            if (hs == null || hp == null) throw IllegalStateException("ports busy")
        } catch (e: Throwable) {
            try { hs?.close() } catch (_: Throwable) {}
            try { hp?.close() } catch (_: Throwable) {}
            throw e
        }
        httpsSock = hs; httpSock = hp
        httpsPort = hs!!.localPort; httpPort = hp!!.localPort; names = nm
        running = true
        Thread({ accept(hs, true) }, "web-https").start()
        Thread({ accept(hp, false) }, "web-http").start()
        env.changed()
    }

    fun stop() {
        running = false
        try { httpsSock?.close() } catch (_: Throwable) {}
        try { httpSock?.close() } catch (_: Throwable) {}
        httpsSock = null; httpSock = null
        env.changed()
    }

    private fun isPrivate(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
    }

    /** روابط الانضمام: صفحة التجهيز (HTTP) لكل عنوان */
    fun setupUrls(): List<String> = names.filter { it != "rr.local" }.map { "http://$it:$httpPort/" }

    // ───── قرارات المدير ─────
    fun approve(id: String) {
        val d = devices[id] ?: return
        d.state = "approved"
        if (d.token.isEmpty()) d.token = newToken()
        saveDevices(); env.changed()
    }

    fun deny(id: String) {
        val d = devices[id] ?: return
        d.state = "denied"; d.token = ""
        saveDevices(); env.changed()
    }

    fun forget(id: String) { devices.remove(id); saveDevices(); env.changed() }

    private fun newToken(): String = ByteArray(18).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    // ───── حفظ الأجهزة (عشان ما توافق كل مرة) ─────
    private fun devFile() = File(webDir, "devices.json")
    private fun saveDevices() {
        val a = JSONArray()
        for (d in devices.values) a.put(d.json().put("token", d.token).put("ua", d.ua))
        try { devFile().writeText(a.toString()) } catch (_: Throwable) {}
    }
    private fun loadDevices() {
        try {
            val a = JSONArray(devFile().readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val d = devices.getOrPut(o.getString("id")) { Dev(o.getString("id")) }
                d.name = o.optString("name"); d.platform = o.optString("platform"); d.state = o.optString("state", "pending")
                d.token = o.optString("token"); d.ua = o.optString("ua")
            }
        } catch (_: Throwable) {}
    }

    // ───── HTTP ─────
    private fun accept(ss: ServerSocket, secure: Boolean) {
        while (running) {
            val s = try { ss.accept() } catch (_: Throwable) { if (!running) return; continue }
            pool.execute { try { serve(s, secure) } catch (_: Throwable) {} finally { try { s.close() } catch (_: Throwable) {} } }
        }
    }

    class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray, val ip: String)

    private fun readReq(inp: InputStream, ip: String): Req? {
        val head = StringBuilder()
        var last4 = 0
        while (true) {
            val b = inp.read()
            if (b < 0) return null
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.length > 32_768) return null
        }
        val lines = head.toString().split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size < 2) return null
        val hs = HashMap<String, String>()
        for (l in lines.drop(1)) { val i = l.indexOf(':'); if (i > 0) hs[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
        val n = hs["content-length"]?.toIntOrNull() ?: 0
        if (n > 64 * 1024 * 1024) return null
        val body = ByteArray(n)
        var off = 0
        while (off < n) { val r = inp.read(body, off, n - off); if (r < 0) return null; off += r }
        val full = parts[1]
        val path = full.substringBefore('?')
        val q = HashMap<String, String>()
        if (full.contains('?')) for (kv in full.substringAfter('?').split('&')) {
            val k = kv.substringBefore('='); if (k.isEmpty()) continue
            q[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
        }
        return Req(parts[0], path, q, hs, body, ip)
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, extra: Map<String, String> = emptyMap()) {
        val msg = when (code) { 200 -> "OK"; 302 -> "Found"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 507 -> "Insufficient Storage"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 $code $msg\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        for ((k, v) in extra) sb.append("$k: $v\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray()); out.write(body); out.flush()
    }
    private fun json(out: OutputStream, o: JSONObject, code: Int = 200) = send(out, code, "application/json; charset=utf-8", o.toString().toByteArray())

    private fun mime(p: String) = when (p.substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"; "js" -> "text/javascript; charset=utf-8"; "css" -> "text/css; charset=utf-8"
        "ttf" -> "font/ttf"; "otf" -> "font/otf"; "woff2" -> "font/woff2"; "svg" -> "image/svg+xml"; "png" -> "image/png"; "json" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun serve(s: Socket, secure: Boolean) {
        s.soTimeout = 60_000
        val ip = (s.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: ""
        val inp = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val r = readReq(inp, ip) ?: return
        if (!secure) return serveSetup(r, out)
        route(r, out)
    }

    /** HTTP (قبل الثقة): صفحة التجهيز والشهادة بس — ولا إشي ثاني */
    private fun serveSetup(r: Req, out: OutputStream) {
        when (r.path) {
            "/", "/setup", "/index.html" -> asset(out, "setup.html")
            "/ca.crt" -> send(out, 200, "application/x-x509-ca-cert", ca().der, mapOf("Content-Disposition" to "attachment; filename=\"ReactionRecorder-local.crt\""))
            "/ca.mobileconfig" -> send(out, 200, "application/x-apple-aspen-config", WebCa.mobileconfig(ca(), env.label).toByteArray(), mapOf("Content-Disposition" to "attachment; filename=\"ReactionRecorder.mobileconfig\""))
            "/info.json" -> json(out, JSONObject().put("fp", caFingerprint()).put("https", httpsPort).put("label", env.label).put("names", JSONArray(names)))
            else -> if (r.path.startsWith("/fonts/") || r.path == "/style.css" || r.path == "/setup.js") asset(out, r.path.removePrefix("/")) else send(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun asset(out: OutputStream, p: String) {
        if (p.contains("..")) return send(out, 404, "text/plain", ByteArray(0))
        val b = env.asset(p) ?: return send(out, 404, "text/plain", "not found".toByteArray())
        send(out, 200, mime(p), b)
    }

    private fun authed(r: Req): Dev? {
        val t = r.query["token"] ?: r.headers["x-token"] ?: return null
        return devices.values.firstOrNull { it.token == t && it.state == "approved" }?.also { it.lastSeen = System.currentTimeMillis(); it.ip = r.ip }
    }

    private fun route(r: Req, out: OutputStream) {
        when {
            r.path == "/" || r.path == "/index.html" -> asset(out, "app.html")
            r.path == "/api/ping" -> send(out, 200, "text/plain", "ok".toByteArray(), mapOf("Access-Control-Allow-Origin" to "*"))
            // ساعة الجلسة: الجهاز بيقيس فرق ساعته وتأخر الشبكة
            r.path == "/api/time" -> json(out, JSONObject().put("t", env.nowNs()))
            r.path == "/api/join" && r.method == "POST" -> {
                val o = try { JSONObject(String(r.body)) } catch (_: Throwable) { return json(out, JSONObject().put("error", "bad"), 400) }
                val id = o.optString("id").filter { it.isLetterOrDigit() || it == '-' }.take(40)
                if (id.length < 8) return json(out, JSONObject().put("error", "bad id"), 400)
                val d = devices.getOrPut(id) { Dev(id) }
                d.name = o.optString("name").take(40).ifBlank { d.name }
                d.platform = o.optString("platform").take(40); d.ua = r.headers["user-agent"]?.take(200) ?: ""
                d.ip = r.ip; d.lastSeen = System.currentTimeMillis()
                saveDevices(); env.changed()
                json(out, stateOf(d))
            }
            r.path == "/api/state" -> {
                val d = devices[r.query["id"] ?: ""] ?: return json(out, JSONObject().put("state", "unknown"))
                d.lastSeen = System.currentTimeMillis(); d.ip = r.ip
                json(out, stateOf(d))
            }
            r.path == "/api/report" && r.method == "POST" -> {
                val d = authed(r) ?: return json(out, JSONObject().put("error", "not approved"), 403)
                val o = try { JSONObject(String(r.body)) } catch (_: Throwable) { return json(out, JSONObject().put("error", "bad"), 400) }
                d.report = o; d.reportAt = System.currentTimeMillis()
                val dir = File(webDir, "devices/${d.id}").also { it.mkdirs() }
                File(dir, "report_${d.reportAt}.json").writeText(o.toString(1))
                env.changed()
                json(out, JSONObject().put("ok", true))
            }
            // جزء من تسجيل: بنتأكد من البصمة قبل ما نحفظه؛ نفس الجزء مرتين = ما بنكرّره
            r.path == "/api/chunk" && (r.method == "PUT" || r.method == "POST") -> {
                val d = authed(r) ?: return json(out, JSONObject().put("error", "not approved"), 403)
                val rec = (r.query["rec"] ?: "").filter { it.isLetterOrDigit() || it == '-' }.take(40)
                val seq = r.query["seq"]?.toIntOrNull() ?: return json(out, JSONObject().put("error", "seq"), 400)
                if (rec.isEmpty()) return json(out, JSONObject().put("error", "rec"), 400)
                val sha = sha256(r.body)
                if (r.query["sha"] != null && r.query["sha"] != sha) return json(out, JSONObject().put("error", "sha mismatch").put("got", sha), 409)
                val dir = File(webDir, "devices/${d.id}/rec_$rec").also { it.mkdirs() }
                val f = File(dir, "%06d.part".format(seq))
                val dup = f.exists() && f.length() == r.body.size.toLong() && sha256(f.readBytes()) == sha
                if (!dup) {
                    if (dir.usableSpace < r.body.size + 50L * 1024 * 1024) return json(out, JSONObject().put("error", "admin storage full"), 507)
                    val tmp = File(dir, "%06d.tmp".format(seq)); tmp.writeBytes(r.body); tmp.renameTo(f)
                }
                val meta = d.recs.getOrPut(rec) { JSONObject().put("rec", rec).put("parts", 0).put("bytes", 0L) }
                meta.put("parts", dir.listFiles { x -> x.name.endsWith(".part") }?.size ?: 0)
                meta.put("bytes", dir.listFiles { x -> x.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L)
                env.changed()
                json(out, JSONObject().put("ok", true).put("seq", seq).put("dup", dup).put("sha", sha))
            }
            // آخر التسجيل: بنتأكد إنه كل الأجزاء وصلت بالترتيب، وبنجمّعهم بملف واحد (بدون إعادة ترميز)
            r.path == "/api/finish" && r.method == "POST" -> {
                val d = authed(r) ?: return json(out, JSONObject().put("error", "not approved"), 403)
                val o = try { JSONObject(String(r.body)) } catch (_: Throwable) { JSONObject() }
                val rec = o.optString("rec").filter { it.isLetterOrDigit() || it == '-' }.take(40)
                val n = o.optInt("parts", -1)
                val dir = File(webDir, "devices/${d.id}/rec_$rec")
                val shas = o.optJSONArray("shas")
                val missing = JSONArray(); val bad = JSONArray()
                for (i in 0 until n) {
                    val f = File(dir, "%06d.part".format(i))
                    if (!f.exists()) missing.put(i) else if (shas != null && shas.optString(i) != sha256(f.readBytes())) bad.put(i)
                }
                if (missing.length() > 0 || bad.length() > 0 || n <= 0) return json(out, JSONObject().put("ok", false).put("missing", missing).put("bad", bad), 409)
                val ext = when { o.optString("mime").contains("mp4") -> "mp4"; o.optString("mime").contains("webm") -> "webm"; o.optString("mime").contains("ogg") -> "ogg"; else -> "bin" }
                val outF = File(dir.parentFile, "rec_$rec.$ext")
                outF.outputStream().use { os -> for (i in 0 until n) File(dir, "%06d.part".format(i)).inputStream().use { it.copyTo(os) } }
                val meta = d.recs.getOrPut(rec) { JSONObject().put("rec", rec) }
                meta.put("file", outF.absolutePath).put("bytes", outF.length()).put("parts", n).put("mime", o.optString("mime")).put("done", true)
                    .put("whole", sha256File(outF))
                File(dir.parentFile, "rec_$rec.json").writeText(o.put("file", outF.name).put("bytes", outF.length()).toString(1))
                env.changed()
                json(out, JSONObject().put("ok", true).put("bytes", outF.length()).put("file", outF.name))
            }
            r.path.startsWith("/fonts/") || r.path.endsWith(".js") || r.path.endsWith(".css") -> asset(out, r.path.removePrefix("/"))
            else -> send(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun stateOf(d: Dev): JSONObject {
        val o = JSONObject().put("state", d.state).put("label", env.label)
        if (d.state == "approved") o.put("token", d.token)
        return o
    }

    companion object {
        const val HTTPS_PORT = 47643
        const val HTTP_PORT = 47680
        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        fun sha256File(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
