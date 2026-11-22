package com.reactionrecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** عيّنة مزامنة ساعة بين الجهاز الرئيسي والمساعد: sat = main + theta عند الزمن m (كله بالنانو ثانية، ساعة elapsedRealtime) */
class DevSample(val m: Long, val theta: Long, val rtt: Long)

class DevInfo(
    var id: String,
    var name: String,
    var role: String, // mic | cam | both
) {
    val samples = ArrayList<DevSample>()
    var started = false
    var uploaded = false
    var stopped = false
    var gain = 1f
    var nudgeMs = 0
    var useAudio = true
    var useVideo = true
    var videoLatencyMs = 80
    /** تدوير فيديو هذا الجهاز عند الدمج (0/90/180/270) */
    var rot = 0
    /** مكان الصورة في الفيديو النهائي: -1 تلقائي، 0..5 = أعلى يسار/أعلى يمين/وسط يسار/وسط يمين/أسفل يسار/أسفل يمين */
    var pos = -1
    var size = 1f
}

/** جلسة تسجيل واحدة على الجهاز الرئيسي */
class SessionInfo(var id: String) {
    var wallMs = 0L
    var startElapsedNs = 0L
    var endElapsedNs = 0L
    /** فترات الإيقاف المؤقت: [بداية elapsed، مدة] بالنانو ثانية */
    val pauses = ArrayList<LongArray>()
    var mainUri = ""
    var mainName = ""
    var micGain = 1f
    var srcGain = 0.8f
    var hasWav = false
    var ended = false
    var merged = false
    var mergedUri = ""
    var overlayUri = ""
    /** "" = تسجيل عادي، "podcast" = جلسة بودكاست */
    var kind = ""
    /** إعدادات إضافية بصيغة JSON (مثل توزيع أشخاص البودكاست) */
    var extra = ""
    val devices = LinkedHashMap<String, DevInfo>()

    fun hasVideoDevice(): Boolean = devices.values.any { it.started && (it.role == "cam" || it.role == "both") }
}

object SessionStore {
    fun root(ctx: Context): File {
        val base = ctx.getExternalFilesDir("sessions") ?: File(ctx.filesDir, "sessions")
        base.mkdirs()
        return base
    }

    fun dir(ctx: Context, id: String): File = File(root(ctx), id).also { it.mkdirs() }

    fun devDir(ctx: Context, id: String, dev: String): File = File(dir(ctx, id), safe(dev)).also { it.mkdirs() }

    fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

    @Synchronized
    fun create(ctx: Context, id: String): SessionInfo {
        val s = SessionInfo(id)
        s.wallMs = System.currentTimeMillis()
        save(ctx, s)
        return s
    }

    @Synchronized
    fun save(ctx: Context, s: SessionInfo) {
        val o = JSONObject()
        o.put("id", s.id).put("wall", s.wallMs).put("start", s.startElapsedNs).put("end", s.endElapsedNs)
        o.put("uri", s.mainUri).put("name", s.mainName).put("micGain", s.micGain.toDouble()).put("srcGain", s.srcGain.toDouble())
        o.put("wav", s.hasWav).put("ended", s.ended).put("merged", s.merged).put("mergedUri", s.mergedUri).put("overlayUri", s.overlayUri).put("kind", s.kind).put("extra", s.extra)
        val p = JSONArray()
        for (x in s.pauses) p.put(JSONArray().put(x[0]).put(x[1]))
        o.put("pauses", p)
        val d = JSONArray()
        for (dv in s.devices.values) {
            val j = JSONObject()
            j.put("id", dv.id).put("name", dv.name).put("role", dv.role)
            j.put("started", dv.started).put("uploaded", dv.uploaded).put("stopped", dv.stopped)
            j.put("gain", dv.gain.toDouble()).put("nudge", dv.nudgeMs).put("useA", dv.useAudio).put("useV", dv.useVideo).put("vlat", dv.videoLatencyMs).put("rot", dv.rot).put("pos", dv.pos).put("size", dv.size.toDouble())
            val sm = JSONArray()
            for (x in dv.samples) sm.put(JSONArray().put(x.m).put(x.theta).put(x.rtt))
            j.put("samples", sm)
            d.put(j)
        }
        o.put("devices", d)
        try {
            File(dir(ctx, s.id), "session.json").writeText(o.toString())
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun load(ctx: Context, id: String): SessionInfo? {
        return try {
            val f = File(File(root(ctx), id), "session.json")
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            val s = SessionInfo(o.getString("id"))
            s.wallMs = o.optLong("wall")
            s.startElapsedNs = o.optLong("start")
            s.endElapsedNs = o.optLong("end")
            s.mainUri = o.optString("uri")
            s.mainName = o.optString("name")
            s.micGain = o.optDouble("micGain", 1.0).toFloat()
            s.srcGain = o.optDouble("srcGain", 0.8).toFloat()
            s.hasWav = o.optBoolean("wav")
            s.ended = o.optBoolean("ended")
            s.merged = o.optBoolean("merged")
            s.mergedUri = o.optString("mergedUri")
            s.overlayUri = o.optString("overlayUri")
            s.kind = o.optString("kind")
            s.extra = o.optString("extra")
            val p = o.optJSONArray("pauses")
            if (p != null) for (i in 0 until p.length()) {
                val a = p.getJSONArray(i)
                s.pauses.add(longArrayOf(a.getLong(0), a.getLong(1)))
            }
            val d = o.optJSONArray("devices")
            if (d != null) for (i in 0 until d.length()) {
                val j = d.getJSONObject(i)
                val dv = DevInfo(j.getString("id"), j.optString("name", "device"), j.optString("role", "mic"))
                dv.started = j.optBoolean("started")
                dv.uploaded = j.optBoolean("uploaded")
                dv.stopped = j.optBoolean("stopped")
                dv.gain = j.optDouble("gain", 1.0).toFloat()
                dv.nudgeMs = j.optInt("nudge")
                dv.useAudio = j.optBoolean("useA", true)
                dv.useVideo = j.optBoolean("useV", true)
                dv.videoLatencyMs = j.optInt("vlat", 80)
                dv.rot = j.optInt("rot", 0)
                dv.pos = j.optInt("pos", -1)
                dv.size = j.optDouble("size", 1.0).toFloat()
                val sm = j.optJSONArray("samples")
                if (sm != null) for (k in 0 until sm.length()) {
                    val a = sm.getJSONArray(k)
                    dv.samples.add(DevSample(a.getLong(0), a.getLong(1), a.getLong(2)))
                }
                s.devices[dv.id] = dv
            }
            s
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun list(ctx: Context): List<SessionInfo> {
        val dirs = root(ctx).listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { load(ctx, it.name) }.sortedByDescending { it.wallMs }
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        try { File(root(ctx), id).deleteRecursively() } catch (_: Throwable) {}
    }

    /** يضيف عيّنة مزامنة لجلسة/جهاز معيّن */
    @Synchronized
    fun addSample(ctx: Context, sessionId: String, devId: String, smp: DevSample) {
        val s = load(ctx, sessionId) ?: return
        val dv = s.devices[devId] ?: return
        dv.samples.add(smp)
        while (dv.samples.size > 300) dv.samples.removeAt(1) // نحتفظ بأول عيّنة ونحذف من بعدها
        save(ctx, s)
    }

    /** تحديث حقل في جهاز داخل جلسة بدون تعارض مع كتابات أخرى */
    @Synchronized
    fun update(ctx: Context, sessionId: String, block: (SessionInfo) -> Unit) {
        val s = load(ctx, sessionId) ?: return
        block(s)
        save(ctx, s)
    }

    /** الجلسات التي تضم هذا الجهاز ولم تُدمج بعد */
    @Synchronized
    fun pendingFor(ctx: Context, devId: String): List<SessionInfo> =
        list(ctx).filter { !it.merged && it.devices[devId]?.started == true }
}
