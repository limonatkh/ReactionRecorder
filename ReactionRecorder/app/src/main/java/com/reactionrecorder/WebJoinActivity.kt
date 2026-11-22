package com.reactionrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/** السيرفر المحلي للمتصفحات: واحد للتطبيق كله، بيضل شغّال بالخلفية (خدمة أمامية) */
object WebHubHolder {
    @Volatile var hub: WebHub? = null
    @Volatile var listener: (() -> Unit)? = null

    fun hub(ctx: Context): WebHub {
        hub?.let { return it }
        val app = ctx.applicationContext
        val h = WebHub(object : WebHub.Env {
            override val dataDir: File = app.filesDir
            override fun asset(path: String): ByteArray? = try {
                app.assets.open(if (path.startsWith("fonts/")) path else "web/$path").use { it.readBytes() }
            } catch (_: Throwable) { null }
            override fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
            override val label: String = (SatCfg.load(app).name.takeIf { it.isNotBlank() } ?: Build.MODEL)
            override fun changed() { listener?.let { l -> Handler(Looper.getMainLooper()).post { l() } } }
        })
        hub = h
        return h
    }
}

class WebHubService : Service() {
    companion object {
        const val CH = "webhub"
        fun start(ctx: Context) = androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, WebHubService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, WebHubService::class.java))
    }
    private var wake: PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, tx("الانضمام من المتصفح", "Browser join"), NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 7, Intent(this, WebJoinActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(tx("الانضمام من المتصفح شغّال", "Browser join is on"))
            .setContentText(tx("الأجهزة بتقدر تنضم وتبعت تسجيلاتها", "Devices can join and send their recordings")).setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(42, n)
        try {
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rr:webhub").also { it.acquire(12 * 60 * 60 * 1000L) }
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager).createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "rr:webhub").also { it.acquire() }
        } catch (_: Throwable) {}
        val h = WebHubHolder.hub(this)
        if (!h.running) Thread {
            try { h.lastError = null; h.start(SatNet.localIpsPlain()) } catch (e: Throwable) { h.lastError = e.message ?: e.javaClass.simpleName; h.stop() }
        }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        WebHubHolder.hub?.stop()
        try { wake?.release() } catch (_: Throwable) {}
        try { wifi?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }
}

/**
 * شاشة المدير: شغّل/وقّف، باركود الانضمام، الموافقة على الأجهزة، وتقارير الفحص من كل جهاز.
 */
class WebJoinActivity : BaseActivity() {
    private lateinit var body: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = UiKit.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = UiKit.page(this, tx("الانضمام من المتصفح", "Join from a browser"))
        WebHubHolder.listener = { build() }
        build()
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
    override fun onDestroy() { super.onDestroy(); if (WebHubHolder.listener != null) WebHubHolder.listener = null }
    private val tick = object : Runnable { override fun run() { build(); ui.postDelayed(this, 3000) } }

    private fun txt(s: String, size: Float = 14f, c: String = UiKit.TEXT, bold: Boolean = false) = UiKit.text(this, s, size, c, bold).apply { setPadding(0, dp(4), 0, dp(4)) }

    private fun build() {
        body.removeAllViews()
        val h = WebHubHolder.hub(this)
        body.addView(txt(tx("أي جهاز فيه متصفح (آيفون، آيباد، أندرويد، لابتوب ويندوز أو ماك) بينضم على نفس الشبكة — نقطة الاتصال من جوالك أو نفس الواي فاي. ما في إنترنت ولا سحابة.",
            "Any device with a browser (iPhone, iPad, Android, Windows or Mac laptop) joins on the same network — your hotspot or the same Wi-Fi. No internet, no cloud."), 13.5f, UiKit.SUB))
        val on = h.running
        val b = UiKit.primary(this, if (on) tx("⏹ وقّف", "⏹ Stop") else tx("▶ شغّل الانضمام من المتصفح", "▶ Turn on browser join")) {
            if (on) WebHubService.stop(this) else WebHubService.start(this)
            ui.postDelayed({ build() }, 1500)
        }
        body.addView(b, UiKit.full(this, b, 8, 54))
        h.lastError?.let { body.addView(txt("⚠ $it", 13f, UiKit.ORANGE)) }
        if (!on) return
        val urls = h.setupUrls()
        if (urls.isEmpty()) {
            body.addView(txt(tx("⚠ الجوال مش على شبكة محلية. شغّل نقطة الاتصال أو اتصل بواي فاي.", "⚠ This phone isn't on a local network. Turn on the hotspot or join a Wi-Fi."), 14f, UiKit.ORANGE))
            return
        }
        // باركود لأول عنوان (الباقي مكتوب)
        PodcastActivity.qrBitmap(urls[0])?.let { bm ->
            val iv = ImageView(this).apply { setImageBitmap(bm); setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(dp(10), dp(10), dp(10), dp(10)) }
            body.addView(iv, LinearLayout.LayoutParams(dp(230), dp(230)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        }
        for (u in urls) body.addView(txt(u, 16f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) })
        body.addView(txt(tx("بصمة الشهادة: ${h.caFingerprint()} — لازم تطابق اللي بتطلع على الجهاز.", "Certificate fingerprint: ${h.caFingerprint()} — must match what the device shows."), 12.5f, UiKit.SUB).apply { gravity = Gravity.CENTER })
        body.addView(txt(tx("أول مرة بس: الجهاز بيثبّت شهادة الجوال (الصفحة بتشرحله الخطوات حسب نوعه). بعدها كل مرة بيفتح على طول.", "First time only: the device installs this phone's certificate (the page explains the steps for its type). After that it opens straight away."), 12.5f, UiKit.SUB))

        // الأجهزة
        body.addView(txt(tx("الأجهزة", "Devices"), 17f, UiKit.TEXT, true).apply { setPadding(0, dp(18), 0, dp(4)) })
        val devs = h.devices.values.sortedByDescending { it.lastSeen }
        if (devs.isEmpty()) body.addView(txt(tx("ولا جهاز لسا. امسح الباركود من الجهاز التاني.", "No device yet. Scan the code from the other device."), 13.5f, UiKit.SUB))
        val now = System.currentTimeMillis()
        for (d in devs) {
            val card = UiKit.card(this, null, body)
            val ago = if (d.lastSeen == 0L) "" else ((now - d.lastSeen) / 1000).let { s -> if (s < 10) tx(" · متصل", " · online") else tx(" · آخر ظهور قبل ${s} ث", " · seen ${s}s ago") }
            card.addView(txt("${d.name.ifBlank { "?" }} · ${d.platform}$ago", 15.5f, UiKit.TEXT, true))
            card.addView(txt(when (d.state) { "approved" -> tx("✓ موافَق عليه", "✓ Approved"); "denied" -> tx("✗ مرفوض", "✗ Denied"); else -> tx("⏳ بستنى موافقتك", "⏳ Waiting for you") } + " · ${d.ip}", 13.5f,
                when (d.state) { "approved" -> "#7CFC9A"; "denied" -> "#FF6B6B"; else -> "#FFD21F" }))
            val row = LinearLayout(this)
            fun btn(label: String, run: () -> Unit) = row.addView(UiKit.chip(this, label) { run(); build() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(4), dp(3), 0) })
            if (d.state != "approved") btn(tx("وافق", "Approve")) { h.approve(d.id) }
            if (d.state != "denied") btn(tx("ارفض", "Deny")) { h.deny(d.id) }
            btn(tx("انسَ", "Forget")) { h.forget(d.id) }
            card.addView(row)
            d.report?.let { r -> reportView(card, r, d) }
            for (rec in d.recs.values) {
                val done = rec.optBoolean("done")
                card.addView(txt((if (done) "🎞 " else "⇣ ") + tx("تسجيل ${rec.optString("rec")}: ${rec.optInt("parts")} جزء · ${"%.1f".format(rec.optLong("bytes") / 1048576.0)} MB", "Recording ${rec.optString("rec")}: ${rec.optInt("parts")} parts · ${"%.1f".format(rec.optLong("bytes") / 1048576.0)} MB") +
                    (if (done) tx(" · وصل كامل وانجمع ✓", " · complete and assembled ✓") else tx(" · بيوصل…", " · arriving…")), 13f))
            }
        }
    }

    /** ملخص تقرير الفحص (من الجهاز نفسه) + نسخه */
    private fun reportView(card: LinearLayout, r: JSONObject, d: WebHub.Dev) {
        card.addView(txt(tx("تقرير الفحص", "Check report"), 14.5f, UiKit.TEXT, true).apply { setPadding(0, dp(10), 0, 0) })
        val tests = r.optJSONObject("tests") ?: JSONObject()
        val names = linkedMapOf("basics" to tx("صفحة آمنة + كاميرا + مسجّل", "Secure page + camera + recorder"), "cam" to tx("الكاميرا والمايك", "Camera & mic"), "devices" to tx("الأجهزة", "Devices"),
            "formats" to tx("صيغ التسجيل", "Formats"), "storage" to tx("المساحة", "Storage"), "local" to tx("الكتابة المحلية", "Local writing"), "clock" to tx("الساعة", "Clock"),
            "wake" to tx("منع قفل الشاشة", "Keep screen on"), "dual" to tx("مايكين مع بعض", "Two mics"), "record" to tx("تسجيل تجريبي", "Test recording"))
        for ((k, label) in names) {
            val t = tests.optJSONObject(k) ?: continue
            val st = t.optString("status")
            val mark = when (st) { "ok" -> "✓"; "bad" -> "✗"; "warn" -> "⚠"; else -> "…" }
            card.addView(txt("$mark $label", 13f, when (st) { "ok" -> "#7CFC9A"; "bad" -> "#FF6B6B"; "warn" -> "#FFB86B"; else -> UiKit.SUB }))
        }
        val c = UiKit.chip(this, tx("📋 انسخ التقرير كامل", "📋 Copy the full report")) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("report", JSONObject().put("device", d.json()).put("report", r).toString(1)))
            Toast.makeText(this, tx("انسخ ✓ — ابعته لـClaude", "Copied ✓ — send it to Claude"), Toast.LENGTH_SHORT).show()
        }
        card.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
    }
}
