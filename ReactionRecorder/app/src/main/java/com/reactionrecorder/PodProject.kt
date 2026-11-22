package com.reactionrecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** مقطع محذوف من النسخة المعدّلة (بزمن الجلسة). التسجيل الأصلي لا يُمس */
class PCut(var a: Double, var b: Double, var kind: String, var locked: Boolean = false, var ref: Int = -1) {
    fun toJson(): JSONObject = JSONObject().put("a", a).put("b", b).put("k", kind).put("l", locked).put("r", ref)
    fun copy() = PCut(a, b, kind, locked, ref)

    companion object {
        const val SILENCE = "silence"   // قص سكتات تلقائي (ريلز)
        const val GAP = "gap"           // فراغ طويل وافق عليه المستخدم (حلقة)
        const val TEXT = "text"         // جملة حُذفت من النص
        const val MANUAL = "manual"
        const val PAUSE = "pause"       // إيقاف مؤقت وقت التصوير (فلوق)

        fun fromJson(o: JSONObject) = PCut(o.optDouble("a"), o.optDouble("b"), o.optString("k", MANUAL), o.optBoolean("l"), o.optInt("r", -1))
    }
}

/** حركة زوم على الكادر (بزمن الجلسة). scale=1 مع locked = تثبيت الكادر بلا زوم */
class PZoom(var a: Double, var b: Double, var scale: Float, var smooth: Boolean, var locked: Boolean = false, var auto: Boolean = true, var reason: String = "") {
    fun toJson(): JSONObject = JSONObject().put("a", a).put("b", b).put("s", scale.toDouble()).put("sm", smooth).put("l", locked).put("au", auto).put("r", reason)
    fun copy() = PZoom(a, b, scale, smooth, locked, auto, reason)

    companion object {
        fun fromJson(o: JSONObject) = PZoom(
            o.optDouble("a"), o.optDouble("b"), o.optDouble("s", 1.0).toFloat(), o.optBoolean("sm", true),
            o.optBoolean("l"), o.optBoolean("au", true), o.optString("r"),
        )
    }
}

/** إعدادات مسار المونتاج (مستقلة لكل مشروع: الحلقة وكل ريل) */
class ProjSettings {
    /** calm / balanced / lively */
    var intensity = "balanced"
    /** auto (حسب الحوار) / qa / story / explain / debate / emotional */
    var style = "auto"
    var fade = false
    /** off / light / balanced / intense (للحلقة: off أو gaps) */
    var silence = "off"
    /** أقل فراغ طويل يُقترح حذفه في الحلقة (ثوانٍ) */
    var gapMin = 1.5f
    /** off / auto / manual */
    var zoomMode = "off"
    var zoomMax = 1.12f
    var zoomSmooth = true
    var captions = false
    var capSize = 1f
    var highlight = true
    var titleOn = false
    var titleSecs = 3f
    /** h / v / both */
    var orient = "h"
    var height = 720
    /** المدة المستهدفة للريل (مرنة) */
    var targetLen = 45f
    var faces = false
    /** smart / fill / fit — كيف تملأ الكاميرا الإطار (smart = بدون قص مؤذٍ) */
    var frame = "smart"
    /** other / freeze / card — ماذا نعرض إذا لم يوجد فيديو لشخص في لحظة ما */
    var fill = "other"
    /** الردود القصيرة في مربع عائم (معطّل افتراضيًا: المتكلم دائمًا بملء الشاشة) */
    var pip = false
    /** أرقام الأشخاص الظاهرين طول الوقت */
    var pinned: List<Int> = emptyList()
    /** إطار مضيء حول مربع من يتكلم */
    var glow = false
    /** لحظة قلب الكاميرا: freeze آخر صورة، other كاميرا أخرى، blur تمويه */
    var flipFill = "freeze"
    /** فلوق: اتبع اختياراتي وقت التصوير (وإلا تلقائي على المتكلم) */
    var liveSwitch = true
    /** قالب المونتاج (شكل المربع العائم، المقسوم، الأسماء…) — فاضي = الافتراضي */
    var tpl = ""
    /** إعدادات عدّلتها بإيدك بهالمشروع (ما بتتغير لما يتعدّل القالب) */
    var ov: MutableSet<String> = HashSet()
    /** auto = الكاميرا بتلحق اللي بيحكي، grid = الكل ظاهر طول الوقت */
    var layout = "auto"
    /** كسر الملل: كل كم ثانية كلام متصل بنعمل لقطة قصيرة (0 = مطفي) */
    var brk = 0
    /** auto / listener / wide / zoom */
    var brkTo = "auto"
    var brkLen = 2.5f
    var brkVar = true
    /** الردود القصيرة (آه، امم): show = أي كلام بيطلع (افتراضي)، min = بس إذا حكى أكثر من minTalk */
    var shortMode = "show"
    /** mix (منوّع، أغلبه انتقال كامل) / pip / switch */
    var shortStyle = "mix"
    /** إذا المربع/اللقطة رح تختفي وترجع خلال هالمدة (ثواني) بتضل ظاهرة */
    var bridge = 3f
    /** أول ظهور لكل شخص ببداية الفيديو: الكاميرا بتضل عليه هالقد ثواني حتى لو غيره حكى (عشان اسمه ينكتب وينقرا). 0 = بدون */
    var firstHold = 5f
    /** لما اثنين يحكوا مع بعض: split = الشاشة بتنقسم / stay = بتضل على اللي كان / talker = بتلحق اللي حكيه أكثر / alternate = بتبدّل بينهم */
    /** smart = المونتاج الذكي بكل خياراته / speaker = كاميرا اللي بيحكي بس (ولا مربع ولا تقسيم ولا زوم) */
    var cutMode = "smart"
    /** بوضع "كاميرا اللي بيحكي": الكاميرا بتنتقل لما حدا يحكي متواصل أكثر من هيك (ثواني) */
    var takeSec = 1.5f
    var overlap = "split"
    /** بتقلب بس إذا الثاني حكى لحاله هالقد ثواني (من آخر لحظات) */
    var ovSwitch = 2f
    var ovAfter = 0f
    /** أطول لقطة على شخص وقت النقاش (0 = بدون حد) */
    var ovMax = 0f
    /** نسبة ظهور اللي بيرد بمربع عائم (والباقي انتقال كامل): 0..1 */
    var pipShare = 0.3f
    /** ريل وكاميرات بالعرض: كاميرا فوق وكاميرا تحت طول الوقت */
    var stackReel = true
    /** بعد ما يسكت: الكاميرا بتضل عليه كم ثانية */
    var holdAfter = 2.5f
    var minTalk = 1.0f
    /** الكاميرا العامة بعد صمت: 0 = حسب الإيقاع، -1 = أبدًا، غير هيك ثواني */
    var wideAfter = 0f
    /** نسخة نظام المزامنة مع القالب (0 = مشروع قديم) */
    var sv = 0
    /** (مؤقت، ما بينحفظ) القالب انطبق للتو — مش تعديل يدوي */
    var applied = false

    fun toJson(): JSONObject = JSONObject().put("in", intensity).put("st", style).put("fd", fade).put("si", silence).put("gm", gapMin.toDouble())
        .put("zm", zoomMode).put("zx", zoomMax.toDouble()).put("zs", zoomSmooth).put("cp", captions).put("cs", capSize.toDouble())
        .put("hl", highlight).put("to", titleOn).put("ts", titleSecs.toDouble()).put("or", orient).put("h", height)
        .put("tl", targetLen.toDouble()).put("fc", faces)
        .put("fr", frame).put("fl", fill).put("pp", pip).put("pn", pinned.joinToString(","))
        .put("gl", glow).put("ff", flipFill).put("ls", liveSwitch).put("tp", tpl).put("ov", ov.joinToString(",")).put("lay", layout).put("brk", brk).put("brkTo", brkTo).put("brkLen", brkLen.toDouble()).put("brkVar", brkVar).put("shM", shortMode).put("shS", shortStyle).put("hold", holdAfter.toDouble()).put("brg", bridge.toDouble()).put("fH", firstHold.toDouble()).put("cut", cutMode).put("take", takeSec.toDouble()).put("ovl", overlap).put("ovS", ovSwitch.toDouble()).put("ovA", ovAfter.toDouble()).put("ovM", ovMax.toDouble()).put("pSh", pipShare.toDouble()).put("stk", stackReel).put("minT", minTalk.toDouble()).put("wa", wideAfter.toDouble()).put("sv", sv)

    fun copy(): ProjSettings = fromJson(toJson())

    companion object {
        fun fromJson(o: JSONObject?): ProjSettings {
            val s = ProjSettings()
            if (o == null) return s
            s.intensity = o.optString("in", s.intensity)
            s.style = o.optString("st", s.style)
            s.fade = o.optBoolean("fd", s.fade)
            s.silence = o.optString("si", s.silence)
            s.gapMin = o.optDouble("gm", s.gapMin.toDouble()).toFloat()
            s.zoomMode = o.optString("zm", s.zoomMode)
            s.zoomMax = o.optDouble("zx", s.zoomMax.toDouble()).toFloat()
            s.zoomSmooth = o.optBoolean("zs", s.zoomSmooth)
            s.captions = o.optBoolean("cp", s.captions)
            s.capSize = o.optDouble("cs", 1.0).toFloat()
            s.highlight = o.optBoolean("hl", true)
            s.titleOn = o.optBoolean("to", false)
            s.titleSecs = o.optDouble("ts", 3.0).toFloat()
            s.orient = o.optString("or", s.orient)
            s.height = o.optInt("h", s.height)
            s.targetLen = o.optDouble("tl", 45.0).toFloat()
            s.faces = o.optBoolean("fc", false)
            s.frame = o.optString("fr", "smart")
            s.fill = o.optString("fl", "other")
            // ما في مربعات عائمة بالبودكاست والفلوق (مشاريع قديمة كمان)
            s.pip = false
            s.pinned = emptyList()
            s.glow = o.optBoolean("gl", false)
            s.flipFill = o.optString("ff", "freeze")
            s.liveSwitch = o.optBoolean("ls", true)
            s.tpl = o.optString("tp", "")
            s.ov = o.optString("ov", "").split(',').filter { it.isNotBlank() }.toHashSet()
            s.layout = o.optString("lay", "auto")
            s.brk = o.optInt("brk", 0)
            s.brkTo = o.optString("brkTo", "auto")
            s.brkLen = o.optDouble("brkLen", 2.5).toFloat()
            s.brkVar = o.optBoolean("brkVar", true)
            s.shortMode = o.optString("shM", "show"); s.shortStyle = o.optString("shS", "mix").let { if (it == "auto") "mix" else it }; s.bridge = o.optDouble("brg", 3.0).toFloat(); s.firstHold = o.optDouble("fH", 5.0).toFloat(); s.cutMode = o.optString("cut", "smart"); s.takeSec = o.optDouble("take", 1.5).toFloat(); s.overlap = o.optString("ovl", "split"); s.ovSwitch = o.optDouble("ovS", 2.0).toFloat(); s.ovAfter = o.optDouble("ovA", 0.0).toFloat(); s.ovMax = o.optDouble("ovM", 0.0).toFloat(); s.pipShare = o.optDouble("pSh", 0.3).toFloat(); s.stackReel = o.optBoolean("stk", true)
            s.holdAfter = o.optDouble("hold", 2.5).toFloat(); s.minTalk = o.optDouble("minT", 1.0).toFloat()
            s.wideAfter = o.optDouble("wa", 0.0).toFloat()
            s.sv = o.optInt("sv", 0)
            return s
        }

        fun episodeDefaults() = ProjSettings().apply { intensity = "balanced"; silence = "off"; zoomMode = "off"; captions = false; orient = "h"; fade = false }
        fun reelDefaults() = ProjSettings().apply { intensity = "lively"; silence = "balanced"; zoomMode = "auto"; captions = true; orient = "v"; fade = false; titleOn = true }
    }
}

/** مشروع مونتاج غير إتلافي: قرارات فقط، والأصول لا تُعدَّل */
class PodProject(val id: String, val kind: String) {
    var title = ""
    /** حدود المشروع على خط الجلسة (للحلقة: كامل الجلسة؛ b<0 = حتى النهاية) */
    var a = 0.0
    var b = -1.0
    var reason = ""
    /** نسخة محرّك المونتاج اللي عمل اللقطات (٢ = PodPlan) */
    var planVer = 0
    var st: ProjSettings = if (kind == EPISODE) ProjSettings.episodeDefaults() else ProjSettings.reelDefaults()
    var shots: MutableList<PodShot> = ArrayList()
    val cuts = ArrayList<PCut>()
    val zooms = ArrayList<PZoom>()
    /** كلمات مميّزة يدويًا في الكتابة: "رقم_الجملة:رقم_الكلمة" */
    val emph = HashSet<String>()
    var updated = 0L
    /** فيديو قبل الحلقة وبعدها (مسار نسخة جوّا التطبيق، "" = بدون) */
    var intro = ""
    var outro = ""
    /** الإنترو: file = فيديو من الجوال / self = مقطع من الحلقة نفسها */
    var introSrc = "file"
    var ia = -1.0
    var ib = -1.0
    /** -1 = مونتاج مع الكل، وإلا رقم الشخص (كاميرته بس) */
    var icam = -1
    /** أسلوب مونتاج الإنترو لحاله: speaker / smart، وتقسيم الشاشة والمربع العائم */
    var imode = "speaker"
    var isplit = false
    var ipip = false
    /** الانتقال: black / white / cut / flash + المدة */
    var introTr = "black"
    var introTrSec = 0.3
    var outroTr = "black"
    var outroTrSec = 0.3
    fun hasSelfIntro() = introSrc == "self" && ib - ia >= 1.0
    /** الإنترو اليدوي: من لحظة t مين بيطلع (رقم الشخص، أو -1 = الكل مع بعض). فاضي = تلقائي حسب القالب */
    val imarks = ArrayList<Pair<Double, Int>>()
    /** فيديو بين الإنترو والحلقة (لوجو…) */
    var mid = ""
    /** تعديل صورة كل مقطع: تدوير، قلب، قص/تكبير */
    var introFx = ClipFx()
    var midFx = ClipFx()
    var outroFx = ClipFx()

    fun end(duration: Double): Double = if (b < 0 || b > duration) duration else b

    fun toJson(): JSONObject {
        val o = JSONObject().put("id", id).put("kind", kind).put("title", title).put("a", a).put("b", b).put("reason", reason)
            .put("st", st.toJson()).put("upd", updated).put("v", 2).put("pv", planVer).put("intro", intro).put("outro", outro)
            .put("iSrc", introSrc).put("ia", ia).put("ib", ib).put("icam", icam).put("imode", imode).put("isplit", isplit).put("ipip", ipip)
            .put("iTr", introTr).put("iTrS", introTrSec).put("oTr", outroTr).put("oTrS", outroTrSec)
            .put("mid", mid).put("ifx", introFx.toJson()).put("mfx", midFx.toJson()).put("ofx", outroFx.toJson())
            .put("imk", JSONArray().also { j -> for ((t, w) in imarks) j.put(JSONArray().put(t).put(w)) })
        o.put("shots", JSONArray().also { j -> for (x in shots) j.put(x.toJson()) })
        o.put("cuts", JSONArray().also { j -> for (x in cuts) j.put(x.toJson()) })
        o.put("zooms", JSONArray().also { j -> for (x in zooms) j.put(x.toJson()) })
        o.put("emph", JSONArray(emph.toList()))
        return o
    }

    fun copy(): PodProject = fromJson(toJson())

    companion object {
        const val EPISODE = "episode"
        const val REEL = "reel"

        fun fromJson(o: JSONObject): PodProject {
            val p = PodProject(o.optString("id", "episode"), o.optString("kind", EPISODE))
            p.intro = o.optString("intro", ""); p.outro = o.optString("outro", ""); p.planVer = o.optInt("pv", 0)
            p.introSrc = o.optString("iSrc", "file"); p.ia = o.optDouble("ia", -1.0); p.ib = o.optDouble("ib", -1.0); p.icam = o.optInt("icam", -1)
            p.imode = o.optString("imode", "speaker"); p.isplit = o.optBoolean("isplit", false); p.ipip = false
            p.introTr = o.optString("iTr", "black"); p.introTrSec = o.optDouble("iTrS", 0.3); p.outroTr = o.optString("oTr", "black"); p.outroTrSec = o.optDouble("oTrS", 0.3)
            p.mid = o.optString("mid", "")
            p.introFx = ClipFx.fromJson(o.optJSONObject("ifx")); p.midFx = ClipFx.fromJson(o.optJSONObject("mfx")); p.outroFx = ClipFx.fromJson(o.optJSONObject("ofx"))
            o.optJSONArray("imk")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { x -> p.imarks.add(x.optDouble(0) to x.optInt(1, -1)) } }
            p.title = o.optString("title")
            p.a = o.optDouble("a", 0.0)
            p.b = o.optDouble("b", -1.0)
            p.reason = o.optString("reason")
            p.st = ProjSettings.fromJson(o.optJSONObject("st"))
            p.updated = o.optLong("upd")
            o.optJSONArray("shots")?.let { a -> for (i in 0 until a.length()) p.shots.add(PodShot.fromJson(a.getJSONObject(i))) }
            o.optJSONArray("cuts")?.let { a -> for (i in 0 until a.length()) p.cuts.add(PCut.fromJson(a.getJSONObject(i))) }
            o.optJSONArray("zooms")?.let { a -> for (i in 0 until a.length()) p.zooms.add(PZoom.fromJson(a.getJSONObject(i))) }
            o.optJSONArray("emph")?.let { a -> for (i in 0 until a.length()) p.emph.add(a.getString(i)) }
            return p
        }
    }
}

/** تراجع/إعادة (نسخ JSON كاملة للمشروع، خفيفة الحجم) */
class UndoStack(private val cap: Int = 60) {
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()

    /** يُستدعى قبل أي تعديل بحالة المشروع الحالية */
    fun push(before: String) {
        undo.addLast(before)
        while (undo.size > cap) undo.removeFirst()
        redo.clear()
    }

    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()

    fun undo(current: String): String? {
        val s = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        return s
    }

    fun redo(current: String): String? {
        val s = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        return s
    }
}

/** علامة أثناء التسجيل: star = لحظة مهمة، oops = غلطة/إعادة (t بزمن الجلسة) */
class PMarker(val t: Double, val kind: String)

/** تخزين المشاريع: SessionDir/proj/episode.json و reel_*.json، والنسخ السابقة في proj/versions */
object ProjectStore {
    fun dir(ctx: Context, sid: String): File = File(SessionStore.dir(ctx, sid), "proj").also { it.mkdirs() }
    private fun file(ctx: Context, sid: String, id: String) = File(dir(ctx, sid), "$id.json")

    fun load(ctx: Context, sid: String, id: String): PodProject? = try {
        val f = file(ctx, sid, id)
        if (f.exists()) PodProject.fromJson(JSONObject(f.readText())) else null
    } catch (_: Throwable) { null }

    @Synchronized
    fun save(ctx: Context, sid: String, p: PodProject) {
        p.updated = System.currentTimeMillis()
        try {
            val f = file(ctx, sid, p.id)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(p.toJson().toString())
            tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    fun delete(ctx: Context, sid: String, id: String) {
        try { file(ctx, sid, id).delete() } catch (_: Throwable) {}
        try { File(dir(ctx, sid), "versions/$id").deleteRecursively() } catch (_: Throwable) {}
    }

    fun reels(ctx: Context, sid: String): List<PodProject> {
        migrateShorts(ctx, sid)
        val fs = dir(ctx, sid).listFiles()?.filter { it.name.startsWith("reel_") && it.name.endsWith(".json") } ?: return emptyList()
        return fs.mapNotNull { f -> try { PodProject.fromJson(JSONObject(f.readText())) } catch (_: Throwable) { null } }.sortedBy { it.a }
    }

    fun newReelId() = "reel_" + System.currentTimeMillis().toString(36)

    /** الحلقة الكاملة. نسخ قديمة: تُنقل لقطات pod_edit.json إليها مرة واحدة */
    fun episode(ctx: Context, sid: String): PodProject? {
        load(ctx, sid, "episode")?.let { return it }
        val old = PodcastAnalyzer.loadShots(ctx, sid) ?: return null
        val p = PodProject("episode", PodProject.EPISODE)
        p.shots = old
        save(ctx, sid, p)
        return p
    }

    /** نسخة الشورتس القديمة (pod_shorts.json) → مشاريع ريلز */
    private fun migrateShorts(ctx: Context, sid: String) {
        val f = File(SessionStore.dir(ctx, sid), "pod_shorts.json")
        if (!f.exists()) return
        try {
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val p = PodProject("reel_old$i", PodProject.REEL)
                p.a = o.optDouble("s"); p.b = o.optDouble("e")
                p.title = "مقطع ${i + 1}"
                save(ctx, sid, p)
            }
            f.renameTo(File(f.parentFile, "pod_shorts.migrated.json"))
        } catch (_: Throwable) {}
    }

    // ───── النسخ السابقة ─────

    class Version(val file: File, val time: Long, val label: String)

    fun snapshot(ctx: Context, sid: String, p: PodProject, label: String) {
        try {
            val d = File(dir(ctx, sid), "versions/${p.id}").also { it.mkdirs() }
            val t = System.currentTimeMillis()
            File(d, "$t.json").writeText(JSONObject().put("label", label).put("p", p.toJson()).toString())
            val all = d.listFiles()?.sortedBy { it.name } ?: return
            if (all.size > 15) for (x in all.take(all.size - 15)) x.delete()
        } catch (_: Throwable) {}
    }

    fun versions(ctx: Context, sid: String, id: String): List<Version> {
        val d = File(dir(ctx, sid), "versions/$id")
        val fs = d.listFiles()?.filter { it.name.endsWith(".json") } ?: return emptyList()
        return fs.mapNotNull { f ->
            try { Version(f, f.name.removeSuffix(".json").toLong(), JSONObject(f.readText()).optString("label")) } catch (_: Throwable) { null }
        }.sortedByDescending { it.time }
    }

    fun loadVersion(v: Version): PodProject? = try { PodProject.fromJson(JSONObject(v.file.readText()).getJSONObject("p")) } catch (_: Throwable) { null }

    // ───── علامات التسجيل ─────

    fun addMarker(ctx: Context, sid: String, elapsedNs: Long, kind: String) {
        try {
            val f = File(SessionStore.dir(ctx, sid), "markers.json")
            val a = if (f.exists()) JSONArray(f.readText()) else JSONArray()
            a.put(JSONObject().put("e", elapsedNs).put("k", kind))
            f.writeText(a.toString())
        } catch (_: Throwable) {}
    }

    fun markers(ctx: Context, s: SessionInfo): List<PMarker> = try {
        val f = File(SessionStore.dir(ctx, s.id), "markers.json")
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            List(a.length()) { i -> val o = a.getJSONObject(i); PMarker((o.optLong("e") - s.startElapsedNs) / 1e9, o.optString("k")) }
                .filter { it.t >= 0 }
        }
    } catch (_: Throwable) { emptyList() }
}

/**
 * تعديل صورة مقطع (إنترو/فاصل/أوترو): تدوير بمضاعفات ٩٠ مع عقارب الساعة، قلب أفقي،
 * ملء الشاشة (قص الزائد) أو كامل، تكبير وموضع (مركز المنطقة الظاهرة: 0..1 من الشمال/من فوق).
 */
data class ClipFx(var rot: Int = 0, var mirror: Boolean = false, var fill: Boolean = false, var zoom: Float = 1f, var cx: Float = 0.5f, var cy: Float = 0.5f) {
    fun isPlain() = rot % 360 == 0 && !mirror && !fill && zoom <= 1.001f
    fun toJson(): JSONObject = JSONObject().put("r", rot).put("m", mirror).put("f", fill).put("z", zoom.toDouble()).put("x", cx.toDouble()).put("y", cy.toDouble())

    /**
     * المنطقة الظاهرة من الصورة (بعد التدوير والقلب) كنِسَب [x0,y0,x1,y1] (y من فوق)، وعرض/طول الناتج.
     * @param sa نسبة عرض/طول الفيديو الأصلي، @param da نسبة الشاشة
     */
    fun region(sa: Float, da: Float): FloatArray {
        val a = if ((rot / 90) % 2 != 0) 1f / sa else sa
        val z = zoom.coerceIn(1f, 4f)
        var rw = 1f / z; var rh = 1f / z
        if (fill) { if (a > da) rw = (da / a) / z else rh = (a / da) / z }
        val x = cx.coerceIn(rw / 2f, 1f - rw / 2f); val y = cy.coerceIn(rh / 2f, 1f - rh / 2f)
        return floatArrayOf(x - rw / 2f, y - rh / 2f, x + rw / 2f, y + rh / 2f, a * rw / rh)
    }

    companion object {
        fun fromJson(o: JSONObject?): ClipFx = if (o == null) ClipFx() else ClipFx(o.optInt("r", 0), o.optBoolean("m"), o.optBoolean("f"), o.optDouble("z", 1.0).toFloat(), o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat())
    }
}
