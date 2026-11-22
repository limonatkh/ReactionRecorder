package com.reactionrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/** حالة عمل الدمج/إعادة العرض للواجهة (نفس العملية) */
object ReplayState {
    @Volatile var running = false
    @Volatile var sid = ""
    @Volatile var stage = ""
    @Volatile var progress = 0f
    @Volatile var error: String? = null
    @Volatile var done = false
    @Volatile var resultUri: String? = null
    @Volatile var warnings: List<String> = emptyList()
    @Volatile var cancel = false
}

/**
 * خدمة الدمج:
 *  - MERGE: دمج الصوت فقط (أسرع من الزمن الحقيقي) → فيديو جديد "_mix".
 *  - OVERLAY: دمج الصوت ثم إعادة تشغيل الفيديو الرئيسي مع فيديوهات الأجهزة المساعدة داخل المشهد وتسجيلها بالزمن الحقيقي.
 */
class ReplayService : Service() {
    companion object {
        const val ACTION_MERGE = "rp.MERGE"
        const val ACTION_OVERLAY = "rp.OVERLAY"
        const val ACTION_CANCEL = "rp.CANCEL"
        private const val CHANNEL = "replay"
        private const val NOTIF_ID = 1003
        /** أقصى عدد فيديوهات أجهزة مساعدة داخل المشهد (حد فك الترميز على الأجهزة المتوسطة) */
        const val MAX_OVERLAY = 3
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var wake: PowerManager.WakeLock? = null

    private var rec: Recorder? = null
    private var mixFile: File? = null
    private var players = ArrayList<SourcePlayer>()
    private var sats = ArrayList<SatPlay>()
    private var mainPlayer: SourcePlayer? = null
    private var sidNow = ""
    private var finishing = false
    private var syncOn = false
    @Volatile private var lastPct = -1

    private class SatPlay(val player: SourcePlayer, val time: Finalizer.SatVideoTime, val idx: Int) {
        var lastSeek = 0L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        L.init(this)
        when (intent?.action) {
            ACTION_CANCEL -> {
                ReplayState.cancel = true
                if (rec != null) main.post { finishOverlay(true) }
                else if (!ReplayState.running) stopSelf()
            }
            ACTION_MERGE, ACTION_OVERLAY -> {
                if (ReplayState.running) return START_NOT_STICKY
                val sid = intent?.getStringExtra("session") ?: return START_NOT_STICKY
                begin(sid, intent?.action == ACTION_OVERLAY)
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun begin(sid: String, overlay: Boolean) {
        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notif(tx("تجهيز…", "Preparing…")), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIF_ID, notif(tx("تجهيز…", "Preparing…")))
        } catch (t: Throwable) {
            ReplayState.error = "Cannot start: ${t.message}"
            stopSelf(); return
        }
        ReplayState.running = true
        ReplayState.done = false
        ReplayState.error = null
        ReplayState.cancel = false
        ReplayState.resultUri = null
        ReplayState.warnings = emptyList()
        ReplayState.sid = sid
        ReplayState.progress = 0f
        ReplayState.stage = tx("دمج الصوت…", "Mixing audio…")
        sidNow = sid
        finishing = false
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rr:replay").also { it.acquire(3 * 60 * 60 * 1000L) }
        } catch (_: Throwable) {}

        worker.execute {
            val toFile = if (overlay) File(cacheDir, "replay_$sid.mp4").also { mixFile = it } else null
            val r = Finalizer.merge(this, sid, toFile, { p ->
                ReplayState.progress = p * (if (overlay) 0.2f else 1f)
                val pc = (p * 100).toInt()
                if (pc != lastPct) { lastPct = pc; notifyText(tx("دمج الصوت ", "Mixing audio ") + "$pc%") }
            }, { ReplayState.cancel })
            ReplayState.warnings = r.warnings
            if (r.error != null) {
                end(r.error, null); return@execute
            }
            if (!overlay) {
                SessionStore.update(this, sid) { s -> s.merged = true; s.mergedUri = r.uri.toString() }
                end(null, r.uri.toString()); return@execute
            }
            main.post { startOverlay(r.file!!) }
        }
    }

    private fun end(err: String?, uri: String?) {
        main.post {
            ReplayState.error = err
            ReplayState.resultUri = uri
            ReplayState.done = err == null
            ReplayState.running = false
            ReplayState.stage = if (err != null) tx("فشل", "Failed") else tx("تم", "Done")
            ReplayState.progress = if (err == null) 1f else ReplayState.progress
            try { if (wake?.isHeld == true) wake?.release() } catch (_: Throwable) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ───────── إعادة العرض مع الأجهزة المساعدة ─────────

    private fun startOverlay(mix: File) {
        val s = SessionStore.load(this, sidNow)
        if (s == null) { end("Session not found", null); return }
        val vids = ArrayList<Triple<DevInfo, File, Long>>()
        for (dv in s.devices.values) {
            if (!dv.started || !dv.useVideo || (dv.role != "both" && dv.role != "cam")) continue
            val dd = SessionStore.devDir(this, sidNow, dv.id)
            val vf = File(dd, "video.mp4")
            val mf = File(dd, "meta.json")
            if (!vf.exists() || !mf.exists()) { ReplayState.warnings = ReplayState.warnings + "${dv.name}: video not received yet — skipped"; continue }
            try {
                val v = JSONObject(mf.readText()).optJSONObject("video")
                if (v == null) continue
                vids.add(Triple(dv, vf, v.optLong("startBoot")))
            } catch (_: Throwable) {}
            if (vids.size >= MAX_OVERLAY) break
        }
        if (vids.isEmpty()) {
            end(tx("لا يوجد فيديو من الأجهزة لهذه الجلسة", "No device video available for this session"), null); return
        }
        val info = VideoProbe.probe(this, Uri.fromFile(mix).toString(), false)
        if (info == null) { end("Cannot read the mixed video", null); return }

        ReplayState.stage = tx("تسجيل الفيديو النهائي (بالزمن الحقيقي)…", "Recording the final video (real time)…")
        val cfg = ConfigStore.load(this)
        cfg.portrait = info.height >= info.width
        val low = minOf(info.width, info.height)
        cfg.shortSide = listOf(480, 720, 1080).minByOrNull { abs(it - low) } ?: 720
        cfg.micOn = false
        cfg.srcAudioOn = true
        cfg.srcVol = 1f
        cfg.keyMode = 0
        cfg.mode = SourceMode.VIDEO
        cfg.layout = if (cfg.layout.camera2.w <= 0f) Presets.build(1, cfg.portrait, 0.7f) else cfg.layout

        val feed = PlayerAudioFeed()
        worker.execute {
            try {
                val r = Recorder(this, cfg, null, info.width, info.height, feed)
                r.prepare()
                rec = r
                main.post { connectOverlay(r, feed, mix, vids, s) }
            } catch (t: Throwable) {
                main.post { end("Setup failed: ${t.message ?: t.javaClass.simpleName}", null) }
            }
        }
    }

    private fun connectOverlay(r: Recorder, feed: PlayerAudioFeed, mix: File, vids: List<Triple<DevInfo, File, Long>>, s: SessionInfo) {
        val comp = r.compositor
        comp.cam0IsFront = false
        comp.camMain = -1
        // مواضع الصور الصغيرة: الأولى والثانية من التخطيط الحالي، والثالثة أعلى اليسار
        val lay = ConfigStore.load(this).layout
        val autoOrder = intArrayOf(1, 0, 3)
        val rects = ArrayList<NRect>()
        for ((i, v) in vids.withIndex()) {
            val d = v.first
            val w = (lay.camera.w * d.size).coerceIn(0.1f, 0.9f)
            val h = (lay.camera.h * d.size).coerceIn(0.05f, 0.9f)
            val slot = if (d.pos in 0..5) d.pos else autoOrder[i % autoOrder.size]
            val x = if (slot % 2 == 0) 0.04f else 1f - w - 0.04f
            val y = when (slot / 2) { 0 -> 0.03f; 1 -> (1f - h) / 2f; else -> 1f - h - 0.05f }
            rects.add(NRect(x, y, w, h))
        }
        comp.pipRects = rects
        for ((i, v) in vids.withIndex()) comp.setCameraRotation(i, v.first.rot)
        comp.cameraVisible = true
        var ready = 0
        val need = 1 + vids.size
        var started = false
        val onAllReady = {
            if (!started && ready >= need) {
                started = true
                worker.execute {
                    try {
                        r.begin()
                        main.post { startPlayback() }
                    } catch (t: Throwable) {
                        main.post { failOverlay("Start failed: ${t.message ?: t.javaClass.simpleName}") }
                    }
                }
            }
        }
        try {
            val sp = SourcePlayer(this, feed)
            mainPlayer = sp
            players.add(sp)
            sp.onVideoSize = { w, h -> comp.setSourceSize(w, h) }
            sp.onError = { m -> main.post { failOverlay("Playback error: $m") } }
            sp.onEnded = { main.postDelayed({ finishOverlay(false) }, 700) }
            sp.onReady = { ready++; onAllReady() }
            sp.init(comp.sourceSurface, true)
            sp.load(Uri.fromFile(mix), false, 0L)

            for ((i, v) in vids.withIndex()) {
                val (dv, vf, startBoot) = v
                val t = Finalizer.SatVideoTime(s, dv, startBoot)
                val p = SourcePlayer(this, PlayerAudioFeed())
                players.add(p)
                sats.add(SatPlay(p, t, i))
                p.onVideoSize = { w, h ->
                    Thread { comp.setCameraBufferSize(i, w, h); comp.setCameraInfoRotation(i, 0) }.start()
                }
                p.onError = { m -> main.post { failOverlay("Device video error: $m") } }
                p.onReady = { ready++; onAllReady() }
                p.init(comp.cameraSurfaceFor(i), true)
                p.load(Uri.fromFile(vf), false, maxOf(0L, t.fileMs(0L).toLong()))
            }
        } catch (t: Throwable) {
            failOverlay("Cannot open the videos: ${t.message ?: t.javaClass.simpleName}"); return
        }
        main.postDelayed({ if (!started && !finishing) failOverlay("The videos took too long to load") }, 40_000)
    }

    private fun startPlayback() {
        if (finishing) return
        mainPlayer?.start()
        syncOn = true
        syncTick(true)
    }

    private val syncRunnable = Runnable { syncTick(false) }

    /** يحافظ على محاذاة فيديوهات الأجهزة المساعدة مع الفيديو الرئيسي (ويعالج الإيقاف المؤقت والانجراف) */
    private fun syncTick(first: Boolean) {
        if (!syncOn || finishing) return
        val mp = mainPlayer ?: return
        val posMs = mp.positionMs()
        val dur = mp.durationMs()
        if (dur > 0) {
            ReplayState.progress = 0.2f + 0.8f * (posMs.toFloat() / dur).coerceIn(0f, 1f)
            val pc = (ReplayState.progress * 100).toInt()
            if (pc != lastPct) { lastPct = pc; notifyText("Recording final video $pc%") }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        for (sp in sats) {
            val want = sp.time.fileMs(posMs * 1_000_000L)
            if (want < 0.0) {
                if (sp.player.wantsPlay()) sp.player.pause()
                continue
            }
            if (!sp.player.wantsPlay()) sp.player.start()
            val cur = sp.player.positionMs()
            if ((first || abs(cur - want) > 150.0) && now - sp.lastSeek > 1500L) {
                sp.player.seekTo(want.toLong())
                sp.lastSeek = now
            }
        }
        if (ReplayState.cancel) { finishOverlay(true); return }
        main.postDelayed(syncRunnable, 400)
    }

    private fun releasePlayers() {
        syncOn = false
        main.removeCallbacks(syncRunnable)
        for (p in players) try { p.release() } catch (_: Throwable) {}
        players.clear()
        sats.clear()
        mainPlayer = null
    }

    private fun failOverlay(msg: String) {
        if (finishing) return
        finishing = true
        releasePlayers()
        worker.execute {
            try { rec?.abort() } catch (_: Throwable) {}
            rec = null
            try { mixFile?.delete() } catch (_: Throwable) {}
            end(msg, null)
        }
    }

    private fun finishOverlay(cancelled: Boolean) {
        if (finishing) return
        finishing = true
        releasePlayers()
        notifyText("Saving…")
        worker.execute {
            val r = rec
            rec = null
            var uri: Uri? = null
            try {
                uri = if (cancelled) { r?.abort(); null } else r?.stop()
            } catch (_: Throwable) {}
            try { mixFile?.delete() } catch (_: Throwable) {}
            if (cancelled) { end("Cancelled", null); return@execute }
            if (uri == null) { end("The final video was not saved", null); return@execute }
            SessionStore.update(this, sidNow) { s -> s.overlayUri = uri.toString() }
            end(null, uri.toString())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayers()
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Throwable) {}
        worker.shutdown()
    }

    // ───────── إشعار ─────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Merging", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, DevicesActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Reaction Recorder — merging")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notifyText(t: String) {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notif(t)) } catch (_: Throwable) {}
    }
}
