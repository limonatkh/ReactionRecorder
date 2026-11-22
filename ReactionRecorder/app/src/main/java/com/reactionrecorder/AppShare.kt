package com.reactionrecorder

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * مشاركة التطبيق على الشبكة المحلية (هوتسبوت الجوال) بدون إنترنت:
 * خادم صغير يعرض صفحة "نزّل التطبيق" وملف الـ APK نفسه المثبّت على هذا الجوال.
 * نفس الباركود يعمل للاقتران: من عنده التطبيق يقرأ بيانات الاقتران من الرابط، ومن ليس عنده يفتح الصفحة وينزّل.
 */
object AppShare {
    @Volatile var port = 0
        private set
    @Volatile var running = false
        private set
    @Volatile var downloads = 0
    private var server: ServerSocket? = null
    private val exec = Executors.newCachedThreadPool()
    private lateinit var app: Context

    @Synchronized
    fun start(ctx: Context) {
        if (running) return
        app = ctx.applicationContext
        for (p in 8080..8089) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p))
                server = ss; port = p; running = true
                break
            } catch (_: Throwable) {}
        }
        if (!running) return
        exec.execute {
            val ss = server ?: return@execute
            while (running) {
                val s = try { ss.accept() } catch (_: Throwable) { break }
                exec.execute { handle(s) }
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
    }

    /** أول عنوان محلي (للباركود) */
    fun ip(): String? = SatNet.localIpsPlain().firstOrNull()

    /** رابط صفحة التنزيل فقط */
    fun pageUrl(): String? { val i = ip() ?: return null; return if (running) "http://$i:$port/" else null }

    /** باركود واحد للوظيفتين: تنزيل التطبيق + الاقتران بهذا الجوال */
    fun pairQr(): String {
        val pair = SatNet.qrPayload(SatHub.pin, SatHub.port, SatHub.hubId, SatHub.hubName)
        val i = ip()
        return if (running && i != null) SatNet.shareUrl(i, port, pair) else pair
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 15_000
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val line = r.readLine() ?: return
            while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break }
            val path = line.split(' ').getOrNull(1) ?: "/"
            val out = s.getOutputStream()
            if (path.startsWith("/app.apk")) {
                val f = File(app.applicationInfo.sourceDir)
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: ${f.length()}\r\n" +
                    "Content-Disposition: attachment; filename=\"ReactionRecorder.apk\"\r\nConnection: close\r\n\r\n").toByteArray())
                f.inputStream().use { it.copyTo(out, 256 * 1024) }
                downloads++
            } else {
                val body = page().toByteArray(Charsets.UTF_8)
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body)
            }
            out.flush()
        } catch (_: Throwable) {
        } finally {
            try { s.close() } catch (_: Throwable) {}
        }
    }

    private fun page(): String = """<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Reaction Recorder</title>
<style>body{font-family:sans-serif;background:#101114;color:#eceef2;margin:0;padding:28px 20px;text-align:center}
a.b{display:block;background:#ff3b30;color:#fff;text-decoration:none;font-size:20px;font-weight:bold;padding:18px;border-radius:24px;margin:24px 0}
p{color:#9aa1af;line-height:1.7;font-size:15px}.e{direction:ltr}</style></head><body>
<h1>Reaction Recorder</h1>
<a class="b" href="/app.apk">⬇ نزّل التطبيق / Download</a>
<p>بعد التنزيل افتح الملف واسمح بالتثبيت من هذا المصدر مرة واحدة.<br>
ثم افتح التطبيق ← انضمام لتسجيل ← امسح الباركود نفسه مرة ثانية.</p>
<p class="e">After downloading, open the file and allow installing from this source once.<br>
Then open the app ← Join a recording ← scan the same QR code again.</p>
</body></html>"""
}
