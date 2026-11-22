package com.reactionrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.max
import java.io.File

/** حالة أعمال البودكاست الطويلة للواجهة */
object PodState {
    @Volatile var running = false
    @Volatile var sid = ""
    @Volatile var job = ""
    @Volatile var project = ""
    @Volatile var stage = ""
    @Volatile var progress = 0f
    @Volatile var error: String? = null
    @Volatile var resultUri: String? = null
    /** كل الملفات الناتجة من آخر تصدير (قد يكون عرضي + طولي) */
    @Volatile var results: List<String> = emptyList()
    /** النتيجة لسا مش بالمعرض: بتستنى توافق بعد ما تشوفها */
    @Volatile var held = false
    /** اللي حفظته بالمعرض بعد ما شفته */
    @Volatile var saved: List<String> = emptyList()
    /** نسخة خفيفة (360p) للمشاهدة */
    @Volatile var light = false
    @Volatile var previewFile: String? = null
    @Volatile var info: String? = null
    @Volatile var warnings: List<String> = emptyList()
    @Volatile var cancel = false
    /** يزيد عند انتهاء كل عمل (لتعرف الواجهة أن هناك نتيجة جديدة) */
    @Volatile var doneSeq = 0
}

/**
 * خدمة في الخلفية للأعمال الطويلة: التصدير، المعاينة، التفريغ، فهم الحوار، تتبع الوجه، وملفات SRT.
 * لا تحذف أي مشروع أو أصل عند الفشل.
 */
class PodcastExportService : Service() {
    companion object {
        const val ACTION_EXPORT = "pod.EXPORT"
        const val ACTION_PREVIEW = "pod.PREVIEW"
        const val ACTION_TRANSCRIBE = "pod.TRANSCRIBE"
        const val ACTION_SEMANTIC = "pod.SEMANTIC"
        const val ACTION_FACES = "pod.FACES"
        const val ACTION_SRT = "pod.SRT"
        const val ACTION_CANCEL = "pod.CANCEL"
        private const val CHANNEL = "podcast"
        private const val NOTIF_ID = 1004
    }

    private var wake: PowerManager.WakeLock? = null
    private var lastPct = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        L.init(this)
        val act = intent?.action
        if (act == ACTION_CANCEL) {
            PodState.cancel = true
            if (!PodState.running) stopSelf(startId)
            return START_NOT_STICKY
        }
        val sid = intent?.getStringExtra("session")
        if (act == null || sid == null || PodState.running) {
            if (!PodState.running) stopSelf(startId)
            return START_NOT_STICKY
        }
        begin(act, sid, intent)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun begin(act: String, sid: String, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Podcast", NotificationManager.IMPORTANCE_LOW))
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notif("…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIF_ID, notif("…"))
        } catch (t: Throwable) {
            PodState.error = "Cannot start: ${t.message}"
            stopSelf(); return
        }
        val projId = intent.getStringExtra("project") ?: "episode"
        PodState.running = true
        PodState.sid = sid
        PodState.job = act
        PodState.project = projId
        PodState.error = null
        PodState.info = null
        PodState.cancel = false
        PodState.progress = 0f
        PodState.warnings = emptyList()
        PodState.stage = tx("تحضير…", "Preparing…")
        if (act == ACTION_EXPORT) { PodState.resultUri = null; PodState.results = emptyList(); PodState.saved = emptyList(); PodState.held = false }
        if (act == ACTION_PREVIEW) PodState.previewFile = null
        lastPct = -1
        span0 = 0f; span1 = 0.1f; overall = 0f; startedAt = android.os.SystemClock.elapsedRealtime()
        jobName = when (act) { ACTION_EXPORT -> tx("التصدير", "Export"); ACTION_PREVIEW -> tx("المعاينة", "Preview"); ACTION_TRANSCRIBE -> tx("التفريغ", "Transcription"); ACTION_FACES -> tx("تحليل الوجوه", "Face analysis"); else -> tx("الشغل", "Work") }
        try {
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rr:podcast").also { it.acquire(4 * 60 * 60 * 1000L) }
        } catch (_: Throwable) {}

        Thread({
            var err: String? = null
            try {
                val d = PodStudio.load(this, sid, { p -> report(tx("تحليل الأصوات", "Analyzing audio"), p) }, { PodState.cancel })
                    ?: throw IllegalStateException(if (PodState.cancel) tx("أُلغي", "Cancelled") else tx("الجلسة غير موجودة", "Session not found"))
                when (act) {
                    ACTION_EXPORT, ACTION_PREVIEW -> {
                        val p = (if (projId == "episode") ProjectStore.episode(this, sid) else ProjectStore.load(this, sid, projId))
                            ?: (if (projId == "episode") PodStudio.newEpisode(d).also { ProjectStore.save(this, sid, it) } else throw IllegalStateException(tx("المشروع غير موجود", "Project not found")))
                        val orient = intent.getStringExtra("orient") ?: p.st.orient
                        // القالب بيحتاج الوجوه (مربع على الوجه / ما يغطي الوجه / تتبع الوجه) → نحللها تلقائيًا إذا ناقصة
                        if (PodStudio.syncTpl(this, p)) PodStudio.regenerate(d, p)
                        // «شوف الحلقة كاملة بخفيف»: نفس كل إشي بس 360p (المشروع ما بيتغيّر — مش محفوظ)
                        val forceH = intent.getIntExtra("forceH", 0)
                        if (forceH > 0) p.st.height = forceH
                        PodState.held = act == ACTION_EXPORT && intent.getBooleanExtra("hold", true)
                        PodState.light = forceH > 0
                        val tp = PodStudio.tplOf(this, p)
                        // الوجوه لازمة: تتبع الوجه، المربع على الوجه، أو قص الشاشة المقسومة على الوجوه والكتابة بعيد عنها
                        // الكادر بيلحق الناس الموجودين بكل كاميرا (شخص → زوم عليه، شخصين → الاثنين) → دايمًا بنحلل الوجوه
                        val needFaces = true
                        val renderFrom = if (needFaces) 0.3f else 0.1f
                        phase(0.1f, renderFrom)
                        if (needFaces) {
                            val fa = if (act == ACTION_PREVIEW) intent.getDoubleExtra("from", p.a) else p.a
                            val fb = if (act == ACTION_PREVIEW) min(p.end(d.duration), fa + 40.0) else p.end(d.duration)
                            try { faces(d, sid, fa, fb, true) } catch (_: Throwable) {}
                            if (PodState.cancel) throw IllegalStateException(tx("أُلغي", "Cancelled"))
                        }
                        if (act == ACTION_PREVIEW) {
                            val from = intent.getDoubleExtra("from", p.a)
                            val job = PodStudio.buildJob(this, d, p, orient == "v", true, from)
                            val r = PodcastRenderer(this, d.s, d.setup, d.an, job, renderReport(renderFrom, 1f), { PodState.cancel })
                            err = r.run()
                            PodState.warnings = r.warnings.toList()
                            if (err == null) PodState.previewFile = job.outFile?.absolutePath
                        } else {
                            val outs = when (orient) { "v" -> listOf(true); "both" -> listOf(false, true); else -> listOf(false) }
                            val done = ArrayList<String>()
                            val warns = ArrayList<String>()
                            for ((idx, vert) in outs.withIndex()) {
                                val tag = if (outs.size > 1) " (${idx + 1}/${outs.size} ${if (vert) "9:16" else "16:9"})" else ""
                                val each = (1f - renderFrom) / outs.size
                                // إنترو من الحلقة نفسها: بنطلّعه أول (بأسلوبه) وبنحطه قبل الحلقة
                                var introF: File? = null
                                PodStudio.selfIntroProject(d, p)?.let { q ->
                                    val f = File(cacheDir, "self_intro_${if (vert) "v" else "h"}.mp4"); f.delete()
                                    val ij = PodStudio.buildJob(this, d, q, vert, false, outTo = f, noSync = true)
                                    val ir = PodcastRenderer(this, d.s, d.setup, d.an, ij, renderReport(renderFrom + idx * each, renderFrom + idx * each + each * 0.15f, tag + " · " + tx("الإنترو", "intro")), { PodState.cancel })
                                    val ie = ir.run()
                                    if (ie == null && f.length() > 1000) introF = f else warns.add(tx("ما قدرنا نطلّع الإنترو من الحلقة: ", "Couldn't make the intro from the episode: ") + (ie ?: ""))
                                }
                                val job = PodStudio.buildJob(this, d, p, vert, false, introFile = introF, hold = PodState.held)
                                val r = PodcastRenderer(this, d.s, d.setup, d.an, job, renderReport(renderFrom + idx * each, renderFrom + (idx + 1) * each, tag), { PodState.cancel })
                                val e = r.run()
                                warns.addAll(r.warnings)
                                if (e != null) { err = e; break }
                                r.resultUri?.toString()?.let { done.add(it) }
                            }
                            PodState.warnings = warns.distinct()
                            PodState.results = done.toList()
                            PodState.resultUri = done.lastOrNull()
                            val u = done.lastOrNull()
                            if (err == null && u != null && projId == "episode" && !PodState.held) SessionStore.update(this, sid) { x -> x.mergedUri = u; x.merged = true }
                        }
                    }
                    ACTION_TRANSCRIBE -> {
                        phase(0.1f, 1f)
                        val tr = PodAI.transcribe(this, d.s, d.setup, d.an, { st, pr -> report(st, pr) }, { PodState.cancel })
                        PodState.info = tx("تم تفريغ ${tr.segs.size} جملة", "Transcribed ${tr.segs.size} sentences")
                    }
                    ACTION_SEMANTIC -> {
                        phase(0.1f, 1f)
                        val tr = d.tr ?: throw IllegalStateException(tx("لا يوجد نص — افرغ الكلام أولًا", "No transcript — transcribe first"))
                        val sem = PodAI.semantic(this, sid, tr, d.names, d.duration, intent.getDoubleExtra("target", 45.0), { st, pr -> report(st, pr) }, { PodState.cancel })
                        PodState.info = tx("تم: ${sem.regions.size} منطقة، ${sem.keys.size} جملة محورية، ${sem.ideas.size} فكرة ريل", "Done: ${sem.regions.size} regions, ${sem.keys.size} key sentences, ${sem.ideas.size} reel ideas")
                    }
                    ACTION_FACES -> {
                        phase(0.1f, 1f)
                        val p = if (projId == "episode") ProjectStore.episode(this, sid) else ProjectStore.load(this, sid, projId)
                        val a = p?.a ?: 0.0
                        val b = p?.end(d.duration) ?: d.duration
                        val found = faces(d, sid, a, b, false)
                        PodState.info = if (found == 0) tx("لم يُكتشف وجه — سيُستعمل الضبط اليدوي/المنتصف", "No face found — manual/centre framing will be used") else tx("تم تتبع الوجه ($found عينة)", "Face tracking done ($found samples)")
                    }
                    ACTION_SRT -> {
                        phase(0.1f, 1f)
                        val p = (if (projId == "episode") ProjectStore.episode(this, sid) else ProjectStore.load(this, sid, projId)) ?: throw IllegalStateException(tx("المشروع غير موجود", "Project not found"))
                        val tr = d.tr ?: throw IllegalStateException(tx("لا يوجد نص — افرغ الكلام أولًا", "No transcript — transcribe first"))
                        val lang = intent.getStringExtra("lang") ?: ""
                        val a = p.a; val b = p.end(d.duration)
                        val segs = tr.segs.filter { it.b > a && it.a < b }
                        val texts = if (lang.isBlank()) null else PodAI.translate(this, segs.map { it.text }, lang, { pr -> report(tx("الترجمة", "Translation"), pr) }, { PodState.cancel })
                        val srt = PodStudio.srtFor(d, p, texts)
                        val name = "Podcast_" + d.s.id + (if (p.kind == PodProject.REEL) "_reel" else "") + (if (lang.isBlank()) "" else "_$lang") + ".srt"
                        val path = PodAI.saveToDownloads(this, name, srt) ?: throw IllegalStateException(tx("تعذر حفظ الملف", "Could not save the file"))
                        PodState.info = tx("تم حفظ $path", "Saved $path")
                    }
                }
            } catch (t: Throwable) {
                err = if (t is InterruptedException) tx("أُلغي", "Cancelled") else (t.message ?: t.javaClass.simpleName)
            }
            PodState.error = err
            PodState.stage = if (err == null) tx("تم ✓", "Done ✓") else tx("فشل", "Failed")
            PodState.running = false
            PodState.doneSeq++
            try { if (wake?.isHeld == true) wake?.release() } catch (_: Throwable) {}
            // الإيقاف على الخيط الرئيسي (حيث تبدأ الأعمال الجديدة) حتى لا نوقف عملًا بدأ للتو
            android.os.Handler(mainLooper).post {
                if (!PodState.running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }, "pod-work").start()
    }

    /** تحليل الوجوه لكل الكاميرات بالمدى [a,b] (بزمن الجلسة). onlyMissing = بس اللي مش متحلل */
    private fun faces(d: PodStudio.Data, sid: String, a: Double, b: Double, onlyMissing: Boolean): Int {
        val devs = (d.setup.people.map { it.camDev } + d.setup.wides).filter { it.isNotEmpty() }.distinct()
        var found = 0
        for ((k, dev) in devs.withIndex()) {
            val dv = d.s.devices[dev] ?: continue
            val mf = File(SessionStore.devDir(this, sid, dev), "meta.json")
            val vm = try { JSONObject(mf.readText()).optJSONObject("video") } catch (_: Throwable) { null } ?: continue
            val tm = Finalizer.SatVideoTime(d.s, dv, vm.optLong("startBoot"))
            val f0 = tm.fileMs((a * 1e9).toLong())
            val f1 = tm.fileMs((b * 1e9).toLong())
            if (onlyMissing) {
                val old = PodFaces.load(this, sid, dev)
                if (old != null && old.g != null && old.t.isNotEmpty() && old.t.first() <= f0 + 4000 && old.t.last() >= f1 - 4000) continue
            }
            val step = if (b - a > 600) 2000.0 else 1000.0
            val key = d.setup.people.indexOfFirst { it.camDev == dev }.let { if (it >= 0) "p$it" else "w${d.setup.wides.indexOf(dev)}" }
            found += PodFaces.analyze(this, sid, dev, f0, f1, step, { pr -> report(tx("تحليل الوجوه ${k + 1}/${devs.size}", "Face analysis ${k + 1}/${devs.size}"), (k + pr) / devs.size) }, { PodState.cancel }, d.setup.srcRot[key] ?: 0)
            if (PodState.cancel) break
        }
        return found
    }

    // ── رقم واحد للشغل كله (1..100): كل خطوة إلها حصة، والرقم ما بيرجع لورا
    @Volatile private var span0 = 0f
    @Volatile private var span1 = 0.1f
    @Volatile private var overall = 0f
    @Volatile private var startedAt = 0L
    @Volatile private var jobName = ""

    private fun phase(a: Float, b: Float) { span0 = a; span1 = b }

    /** الإخراج: الفيديو 85% من حصته، وخلط الصوت 15% */
    private fun renderReport(a: Float, b: Float, tag: String = ""): (String, Float) -> Unit {
        val audio = setOf(tx("خلط الصوت", "Mixing audio"), tx("تجهيز صوت المعاينة", "Preparing preview audio"))
        return { st, pr ->
            val k = if (st in audio) 0.85f + 0.15f * pr else 0.85f * pr
            phase(a, b)
            report(st + tag, k)
        }
    }

    private fun report(stage: String, p: Float) {
        overall = max(overall, (span0 + (span1 - span0) * p.coerceIn(0f, 1f)).coerceIn(0f, 1f))
        PodState.stage = stage
        PodState.progress = overall
        val pct = (overall * 100).toInt().coerceIn(1, 100)
        if (pct != lastPct) {
            lastPct = pct
            val el = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000.0
            val left = if (overall > 0.04f) el * (1 - overall) / overall else -1.0
            val eta = if (left < 0) "" else if (left < 60) tx(" · باقي أقل من دقيقة", " · under a minute left") else tx(" · باقي تقريبًا ${(left / 60).toInt() + 1} د", " · about ${(left / 60).toInt() + 1} min left")
            try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notif("$jobName $pct%$eta", stage, pct)) } catch (_: Throwable) {}
        }
    }

    private fun notif(text: String, sub: String = "", pct: Int = -1): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, PodcastActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Podcast")
            .setContentText(text)
            .also { b -> if (sub.isNotBlank()) b.setSubText(sub); if (pct >= 0) b.setProgress(100, pct, false) }
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Throwable) {}
    }
}
