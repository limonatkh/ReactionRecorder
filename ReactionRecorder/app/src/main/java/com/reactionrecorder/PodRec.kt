package com.reactionrecorder

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * تسجيل الحوار (بودكاست) على كل الأجهزة المتصلة — مشترك بين شاشة الإعداد وشاشة الستوديو (المراقبة).
 * كل جهاز يسجّل محليًا بجودة كاملة؛ هنا فقط أوامر البدء/الإيقاف وخط الزمن والعلامات.
 */
object PodRec {
    private val bg = Executors.newSingleThreadExecutor()
    private const val P = "reaction_podcast"

    /** رقم الجلسة الجارية أو null */
    val active: String? get() {
        val a = (SatHub.activeSession ?: starting)?.takeIf { it.startsWith("p") } ?: return null
        return if (a == stopping) null else a
    }
    @Volatile private var starting: String? = null
    @Volatile private var stopping: String? = null

    fun startedAt(ctx: Context): Long = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getLong("recStart", SystemClock.elapsedRealtime())

    /** الوقت المسجّل فعليًا (بدون فترات الإيقاف المؤقت) */
    fun elapsedSec(ctx: Context): Long {
        if (active == null) return 0
        val sp = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val pAt = sp.getLong("pauseAt", 0L)
        val paused = sp.getLong("pausedMs", 0L) + (if (pAt > 0) now - pAt else 0L)
        return ((now - startedAt(ctx) - paused) / 1000).coerceAtLeast(0)
    }

    fun isPaused(ctx: Context): Boolean = active != null && ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getLong("pauseAt", 0L) > 0

    /**
     * ⏸ إيقاف مؤقت: كل الجوالات بتضل تسجّل (ما في خطر يضيع إشي أو يختلف التزامن)،
     * بس الفترة بتنحفظ وبتنحذف من الفيديو تلقائيًا بالمونتاج — وبتقدر ترجعها من هناك.
     */
    fun pause(ctx: Context) {
        val sid = active ?: return
        val sp = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        if (sp.getLong("pauseAt", 0L) > 0) return
        sp.edit().putLong("pauseAt", SystemClock.elapsedRealtime()).apply()
        val now = SystemClock.elapsedRealtimeNanos()
        val app = ctx.applicationContext
        bg.execute { editPauses(app, sid) { it.put(org.json.JSONObject().put("a", now)) } }
    }

    fun resume(ctx: Context) {
        val sid = active ?: return
        val sp = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val at = sp.getLong("pauseAt", 0L)
        if (at <= 0) return
        sp.edit().putLong("pauseAt", 0L).putLong("pausedMs", sp.getLong("pausedMs", 0L) + (SystemClock.elapsedRealtime() - at)).apply()
        val now = SystemClock.elapsedRealtimeNanos()
        val app = ctx.applicationContext
        bg.execute { editPauses(app, sid) { a -> if (a.length() > 0) a.getJSONObject(a.length() - 1).let { o -> if (!o.has("b")) o.put("b", now) } } }
    }

    private fun editPauses(app: Context, sid: String, f: (org.json.JSONArray) -> Unit) {
        try {
            val file = java.io.File(SessionStore.dir(app, sid), "pauses.json")
            val a = if (file.exists()) org.json.JSONArray(file.readText()) else org.json.JSONArray()
            f(a); file.writeText(a.toString())
        } catch (_: Throwable) {}
    }

    /** فترات الإيقاف المؤقت بزمن الجلسة (ثوانٍ) */
    fun pauseList(ctx: Context, s: SessionInfo): List<Pair<Double, Double>> = try {
        val f = java.io.File(SessionStore.dir(ctx, s.id), "pauses.json")
        if (!f.exists()) emptyList() else {
            val a = org.json.JSONArray(f.readText())
            val end = if (s.endElapsedNs > 0) s.endElapsedNs else Long.MAX_VALUE
            List(a.length()) { i -> val o = a.getJSONObject(i); Pair((o.optLong("a") - s.startElapsedNs) / 1e9, (minOf(o.optLong("b", end), end) - s.startElapsedNs) / 1e9) }
                .filter { it.second > it.first && it.first >= 0 }.sortedBy { it.first }
        }
    } catch (_: Throwable) { emptyList() }

    /** مشاكل قبل البدء (أجهزة غير متصلة…) — قائمة فارغة = جاهز */
    fun check(setup: PodSetup): List<String> {
        val missing = ArrayList<String>()
        if (!SatHub.running) missing += tx("الاستقبال لا يعمل", "Receiving is off")
        for (p in setup.people) {
            if (p.camDev.isEmpty() && p.micDev.isEmpty()) missing += tx("${p.name}: بدون كاميرا ولا مايك", "${p.name}: no camera and no mic")
            for (d in listOf(p.camDev, p.micDev)) if (d.isNotEmpty() && SatHub.links[d]?.connected != true)
                missing += "${p.name}: ${SatHub.links[d]?.name ?: tx("جهاز", "device")} " + tx("غير متصل", "not connected")
        }
        for (w in setup.wides) if (SatHub.links[w]?.connected != true) missing += tx("الكاميرا العامة غير متصلة", "The wide camera is not connected")
        return missing.distinct()
    }

    fun start(ctx: Context, setup: PodSetup): String {
        val sid = "p" + System.currentTimeMillis()
        val app = ctx.applicationContext
        app.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putLong("recStart", SystemClock.elapsedRealtime()).putLong("pauseAt", 0L).putLong("pausedMs", 0L).apply()
        val extra = setup.toJson().toString()
        starting = sid; stopping = null
        app.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("recVlog", setup.vlog).apply()
        // نعلن الجلسة فورًا (حتى تظهر الواجهة حالة التسجيل)، ثم نرسل الأوامر في الخلفية
        bg.execute {
            SessionStore.create(app, sid)
            SessionStore.update(app, sid) { x -> x.kind = "podcast"; x.extra = extra }
            SatHub.startSession(app, sid)
            starting = null
            // الأجهزة تحتاج لحظة لتبدأ فعلًا: نبدأ خط الزمن بعد ثانية ونصف
            val start = SystemClock.elapsedRealtimeNanos() + 1_500_000_000L
            SessionStore.update(app, sid) { x -> x.startElapsedNs = start }
        }
        return sid
    }

    fun stop(ctx: Context) {
        val sid = active ?: return
        val app = ctx.applicationContext
        if (isPaused(ctx)) resume(ctx)
        stopping = sid
        bg.execute {
            val end = SystemClock.elapsedRealtimeNanos()
            SessionStore.update(app, sid) { x -> x.endElapsedNs = end; x.ended = true; x.mainUri = "" }
            SatHub.stopSession(app, sid)
        }
    }

    fun isVlog(ctx: Context): Boolean = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean("recVlog", false)

    /** فلوق: الكاميرا الظاهرة الآن (رقم الشخص) — يُحفظ كقرار مونتاج ويمكن تعديله لاحقًا */
    fun live(ctx: Context, person: Int) {
        val sid = active ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val app = ctx.applicationContext
        bg.execute {
            try {
                val f = java.io.File(SessionStore.dir(app, sid), "vlog_live.json")
                val a = if (f.exists()) org.json.JSONArray(f.readText()) else org.json.JSONArray()
                a.put(org.json.JSONObject().put("e", now).put("k", person))
                f.writeText(a.toString())
            } catch (_: Throwable) {}
        }
    }

    /** اختيارات الكاميرا وقت التصوير بزمن الجلسة (ثوانٍ) */
    fun liveList(ctx: Context, s: SessionInfo): List<Pair<Double, Int>> = try {
        val f = java.io.File(SessionStore.dir(ctx, s.id), "vlog_live.json")
        if (!f.exists()) emptyList() else {
            val a = org.json.JSONArray(f.readText())
            List(a.length()) { i -> val o = a.getJSONObject(i); Pair((o.optLong("e") - s.startElapsedNs) / 1e9, o.optInt("k")) }.sortedBy { it.first }
        }
    } catch (_: Throwable) { emptyList() }

    fun mark(ctx: Context, kind: String) {
        val sid = active ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val app = ctx.applicationContext
        bg.execute { ProjectStore.addMarker(app, sid, now, kind) }
    }
}
