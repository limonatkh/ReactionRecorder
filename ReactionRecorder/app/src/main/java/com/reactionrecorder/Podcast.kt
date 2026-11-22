package com.reactionrecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** شخص في البودكاست: كاميرته ومايكه (معرّفات الأجهزة)، واسمه */
class PodPerson(var name: String, var camDev: String, var micDev: String)

/** إيقاع المونتاج (بالثواني) */
class PodPacing(
    /** يجب أن يستمر الكلام هذه المدة قبل أن تتغيّر اللقطة */
    var switchDelay: Float,
    /** أقل مدة للقطة */
    var minShot: Float,
    /** مدة المربع العائم قبل أن يصير المتكلم الجديد بملء الشاشة */
    var pipToFull: Float,
    /** أقصر كلام يُحسب (أقصر منه = "آه"، "اممم"…) */
    var minSpeech: Float,
    /** أطول لقطة لنفس الشخص قبل لقطة عامة للتنويع (0 = بدون) */
    var maxShot: Float,
    /** سكوت بهذه المدة → الكاميرا العامة */
    var silenceToWide: Float,
) {
    fun copy() = PodPacing(switchDelay, minShot, pipToFull, minSpeech, maxShot, silenceToWide)

    fun toJson(): JSONObject = JSONObject().put("sd", switchDelay.toDouble()).put("ms", minShot.toDouble()).put("pf", pipToFull.toDouble())
        .put("msp", minSpeech.toDouble()).put("mx", maxShot.toDouble()).put("sw", silenceToWide.toDouble())

    companion object {
        val FAST = PodPacing(0.4f, 1.2f, 1.2f, 0.25f, 8f, 2.5f)
        val MEDIUM = PodPacing(0.8f, 2.0f, 2.0f, 0.4f, 15f, 3.5f)
        val CALM = PodPacing(1.5f, 3.5f, 3.5f, 0.6f, 25f, 5f)

        fun preset(name: String): PodPacing = when (name) { "fast" -> FAST.copy(); "calm" -> CALM.copy(); else -> MEDIUM.copy() }

        fun fromJson(o: JSONObject?, def: PodPacing): PodPacing {
            if (o == null) return def.copy()
            return PodPacing(
                o.optDouble("sd", def.switchDelay.toDouble()).toFloat(), o.optDouble("ms", def.minShot.toDouble()).toFloat(),
                o.optDouble("pf", def.pipToFull.toDouble()).toFloat(), o.optDouble("msp", def.minSpeech.toDouble()).toFloat(),
                o.optDouble("mx", def.maxShot.toDouble()).toFloat(), o.optDouble("sw", def.silenceToWide.toDouble()).toFloat(),
            )
        }
    }
}

/** إعداد جلسة البودكاست */
/** هندسة تعديل المصدر (تدوير + قص) — مشتركة بين الإخراج والمعاينات */
object SrcGeo {
    /** نقطة من الصورة الأصلية (0..1 من أعلى اليسار) لمكانها بعد التدوير (مع عقارب الساعة) */
    fun rotPoint(x: Float, y: Float, deg: Int): FloatArray = when (((deg % 360) + 360) % 360) {
        90 -> floatArrayOf(1f - y, x)
        180 -> floatArrayOf(1f - x, 1f - y)
        270 -> floatArrayOf(y, 1f - x)
        else -> floatArrayOf(x, y)
    }

    /** وجه [x,y,حجم] من الأصل لإحداثيات المصدر بعد التعديل (أو null إذا برا القص) */
    fun mapFace(f: FloatArray?, deg: Int, crop: FloatArray?, srcW: Int, srcH: Int): FloatArray? {
        if (f == null || f[0].isNaN()) return null
        val p = rotPoint(f[0], f[1], deg)
        val quarter = (((deg % 360) + 360) % 360) % 180 != 0
        // حجم الوجه نسبة من ارتفاع الصورة: بعد ربع لفة الارتفاع صار العرض القديم
        var size = if (quarter) f[2] * srcH / srcW.toFloat() else f[2]
        var x = p[0]; var y = p[1]
        if (crop != null) {
            x = (x - crop[0]) / crop[2]; y = (y - crop[1]) / crop[3]; size /= crop[3]
            if (x < -0.1f || x > 1.1f || y < -0.1f || y > 1.1f) return null
        }
        return floatArrayOf(x, y, size)
    }

    /** أبعاد المصدر بعد التعديل */
    fun dims(w: Int, h: Int, deg: Int, crop: FloatArray?): IntArray {
        val q = (((deg % 360) + 360) % 360) % 180 != 0
        var ew = if (q) h else w; var eh = if (q) w else h
        if (crop != null) { ew = (ew * crop[2]).toInt().coerceAtLeast(2); eh = (eh * crop[3]).toInt().coerceAtLeast(2) }
        return intArrayOf(ew, eh)
    }

    /**
     * مصفوفة إحداثيات النسيج (عمودية-أولًا لـ GL): من إحداثيات الصورة المعدّلة (u,v؛ v لفوق)
     * لإحداثيات الصورة الأصلية، قبل مصفوفة الـ SurfaceTexture.
     */
    fun texMatrix(deg: Int, crop: FloatArray?): FloatArray {
        // القص: (u,v) → (cx + u*cw, (1-cy-ch) + v*ch)
        val c = ident()
        if (crop != null) { c[0] = crop[2]; c[5] = crop[3]; c[12] = crop[0]; c[13] = 1f - crop[1] - crop[3] }
        // التدوير (مع عقارب الساعة) بإحداثيات v لفوق
        val r = ident()
        when (((deg % 360) + 360) % 360) {
            90 -> { r[0] = 0f; r[4] = -1f; r[12] = 1f; r[1] = 1f; r[5] = 0f; r[13] = 0f }
            180 -> { r[0] = -1f; r[12] = 1f; r[5] = -1f; r[13] = 1f }
            270 -> { r[0] = 0f; r[4] = 1f; r[12] = 0f; r[1] = -1f; r[5] = 0f; r[13] = 1f }
        }
        return mul(r, c)
    }

    fun ident() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** a × b (عمودية-أولًا) */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (i in 0 until 4) for (j in 0 until 4) { var s = 0f; for (k in 0 until 4) s += a[k * 4 + i] * b[j * 4 + k]; o[j * 4 + i] = s }
        return o
    }

    /** تطبيق مصفوفة على نقطة (u,v) */
    fun apply(m: FloatArray, u: Float, v: Float): FloatArray = floatArrayOf(m[0] * u + m[4] * v + m[12], m[1] * u + m[5] * v + m[13])
}

class PodSetup {
    val people = ArrayList<PodPerson>()
    val wides = ArrayList<String>()
    var presetName = "medium"
    var pacing: PodPacing = PodPacing.MEDIUM.copy()
    /** شكل المشهد لأكثر من شخص: split = تقسيم الشاشة، boxes = مربعات فوق خلفية */
    var multiStyle = "split"
    /** المقاطِع (اللي بيدخل على كلام غيره) يظهر بمربع عائم فوق المتكلم */
    var usePip = false
    var fade = true
    /** لون خلفية المربعات (ARGB) وصورة اختيارية */
    var bgColor = 0xFF1B1D24.toInt()
    var bgImage = ""
    /** horizontal / vertical / both (الطولي في الدفعة القادمة) */
    var output = "horizontal"
    var height = 720
    /** حساسية الكلام (dB فوق ضوضاء الخلفية) والفرق المطلوب عن بقية المايكات */
    var thrDb = 12f
    var domDb = 6f
    /** تخفيض المايكات الساكتة (0..1) وتوحيد مستوى الأصوات */
    var duck = 0.35f
    var level = true
    /** موضع القص الأفقي/العمودي لكل كاميرا (-1..1) عند ملء إطار بنسبة مختلفة — المفتاح p0..p3 أو w0.. */
    val panX = HashMap<String, Float>()
    val panY = HashMap<String, Float>()
    /** تعديل المصدر نفسه (لكل كاميرا p0/w0): تدوير بالدرجات، قص [x,y,w,h] نسبة من الصورة بعد التدوير، وشو يعبّي الفراغ */
    val srcRot = HashMap<String, Int>()
    val srcCrop = HashMap<String, FloatArray>()
    /** قص «الشكل الخاص» لكل كاميرا: [x, y, w, h] من الصورة بعد التدوير (بنفس مقاس الفيديو النهائي) */
    val lookCrop = HashMap<String, FloatArray>()
    /** auto / zoom / blur / color / image */
    val srcFill = HashMap<String, String>()
    /** كم شخص بكل كاميرا: "many" (أكثر من واحد) / "one" / بدون = تلقائي من كشف الوجوه */
    val srcCrowd = HashMap<String, String>()
    /** قص السكتات في الحلقة الكاملة، وأقل سكتة تُقص (ثوانٍ) */
    var cutSilence = false
    var silenceGap = 0.35f
    /** فلوق: شكل الفيديو — switch تبديل، grid الكل ثابت، mainboxes الرئيسي بملء الشاشة والباقي مربعات */
    var vlog = false
    var vlogLayout = "switch"
    /** في التبديل: true = اختياراتي وقت التصوير، false = تلقائي على من يتكلم */
    var vlogManual = true
    /** إطار مضيء حول مربع من يتكلم */
    var glow = true

    fun toJson(): JSONObject {
        val o = JSONObject()
        val p = JSONArray()
        for (x in people) p.put(JSONObject().put("n", x.name).put("c", x.camDev).put("m", x.micDev))
        o.put("people", p)
        o.put("wides", JSONArray(wides))
        o.put("preset", presetName).put("pacing", pacing.toJson()).put("multi", multiStyle).put("pip2", usePip).put("fade", fade)
            .put("bg", bgColor).put("bgImg", bgImage).put("out", output).put("h", height)
            .put("thr", thrDb.toDouble()).put("dom", domDb.toDouble()).put("duck", duck.toDouble()).put("lvl", level)
            .put("cutSil", cutSilence).put("gap", silenceGap.toDouble())
            .put("vlog", vlog).put("vlay", vlogLayout).put("vman", vlogManual).put("glow", glow)
        val px = JSONObject()
        for ((k, v) in panX) px.put(k, v.toDouble())
        val py = JSONObject()
        for ((k, v) in panY) py.put(k, v.toDouble())
        o.put("panX", px).put("panY", py)
        val sr = JSONObject(); for ((k, v) in srcRot) sr.put(k, v)
        val sc = JSONObject(); for ((k, v) in srcCrop) sc.put(k, JSONArray(v.map { it.toDouble() }))
        val sf = JSONObject(); for ((k, v) in srcFill) sf.put(k, v)
        val sw = JSONObject(); for ((k, v) in srcCrowd) sw.put(k, v)
        val lc = JSONObject(); for ((k, v) in lookCrop) lc.put(k, JSONArray(v.map { it.toDouble() }))
        o.put("lookCrop", lc)
        o.put("srcRot", sr).put("srcCrop", sc).put("srcFill", sf).put("srcCrowd", sw)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject?): PodSetup {
            val s = PodSetup()
            if (o == null) return s
            val p = o.optJSONArray("people")
            if (p != null) for (i in 0 until p.length()) {
                val j = p.getJSONObject(i)
                s.people.add(PodPerson(j.optString("n", tx("شخص ${i + 1}", "Person ${i + 1}")), j.optString("c"), j.optString("m")))
            }
            val w = o.optJSONArray("wides")
            if (w != null) for (i in 0 until w.length()) s.wides.add(w.getString(i))
            s.presetName = o.optString("preset", "medium")
            s.pacing = PodPacing.fromJson(o.optJSONObject("pacing"), PodPacing.preset(s.presetName))
            s.multiStyle = o.optString("multi", "split")
            s.usePip = false
            s.fade = o.optBoolean("fade", true)
            s.bgColor = o.optInt("bg", 0xFF1B1D24.toInt())
            s.bgImage = o.optString("bgImg")
            s.output = o.optString("out", "horizontal")
            s.height = o.optInt("h", 720)
            s.thrDb = o.optDouble("thr", 12.0).toFloat()
            s.domDb = o.optDouble("dom", 6.0).toFloat()
            s.duck = o.optDouble("duck", 0.35).toFloat()
            s.level = o.optBoolean("lvl", true)
            s.cutSilence = o.optBoolean("cutSil", false)
            s.silenceGap = o.optDouble("gap", 0.35).toFloat()
            s.vlog = o.optBoolean("vlog", false)
            s.vlogLayout = o.optString("vlay", "switch")
            s.vlogManual = o.optBoolean("vman", true)
            s.glow = o.optBoolean("glow", true)
            o.optJSONObject("panX")?.let { j -> for (k in j.keys()) s.panX[k] = j.optDouble(k, 0.0).toFloat() }
            o.optJSONObject("panY")?.let { j -> for (k in j.keys()) s.panY[k] = j.optDouble(k, 0.0).toFloat() }
            o.optJSONObject("srcRot")?.let { j -> for (k in j.keys()) s.srcRot[k] = j.optInt(k, 0) }
            o.optJSONObject("srcCrop")?.let { j -> for (k in j.keys()) j.optJSONArray(k)?.let { a -> if (a.length() == 4) s.srcCrop[k] = FloatArray(4) { i -> a.optDouble(i).toFloat() } } }
            o.optJSONObject("lookCrop")?.let { j -> for (k in j.keys()) j.optJSONArray(k)?.let { a -> if (a.length() == 4) s.lookCrop[k] = FloatArray(4) { i -> a.optDouble(i).toFloat() } } }
            o.optJSONObject("srcFill")?.let { j -> for (k in j.keys()) s.srcFill[k] = j.optString(k, "auto") }
            o.optJSONObject("srcCrowd")?.let { j -> for (k in j.keys()) s.srcCrowd[k] = j.optString(k, "") }
            return s
        }

        private const val P = "reaction_podcast"

        fun loadDefault(ctx: Context, slot: String = "setup"): PodSetup {
            val t = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getString(slot, null)
            val s = try { fromJson(if (t != null) JSONObject(t) else null) } catch (_: Throwable) { PodSetup() }
            if (s.people.isEmpty()) {
                s.people.add(PodPerson(tx("شخص 1", "Person 1"), "", ""))
                s.people.add(PodPerson(tx("شخص 2", "Person 2"), "", ""))
            }
            return s
        }

        fun saveDefault(ctx: Context, s: PodSetup, slot: String = "setup") {
            ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString(slot, s.toJson().toString()).apply()
        }
    }
}

/** لقطة في المونتاج: من/إلى (ثوانٍ على خط الجلسة)، النوع، ومن يظهر */
class PodShot(var start: Double, var end: Double, var type: Int, var main: Int, var others: IntArray) {
    val dur: Double get() = end - start
    /** مثبّتة: لا تستبدلها إعادة المونتاج التلقائي */
    var locked = false
    /** "" تلقائية، "manual" يدوية، "reaction" لقطة رد فعل */
    var tag = ""
    /** ليش المحرّك اختار هاي اللقطة (floor / tog-stay / tog-switch / tog-both / tog-wide / silence-wide / start) */
    var why = ""

    /** مربع عائم ← شاشة مقسومة (نفس الناس، بدون ما حدا يغطي حدا) */
    fun unfloat(): PodShot {
        if (type == PIP) { others = (intArrayOf(main) + others).distinct().toIntArray(); type = if (others.size >= 2) MULTI else FULL }
        return this
    }

    fun copy() = PodShot(start, end, type, main, others.copyOf()).also { it.locked = locked; it.tag = tag; it.why = why }

    fun toJson(): JSONObject = JSONObject().put("s", start).put("e", end).put("t", type).put("m", main).put("o", JSONArray(others.toList()))
        .put("l", locked).put("g", tag).put("w", why)

    fun label(setup: PodSetup): String {
        fun nm(i: Int) = setup.people.getOrNull(i)?.name ?: "?"
        return when (type) {
            FULL -> nm(main)
            PIP -> nm(main) + " + " + others.joinToString(",") { nm(it) }
            MULTI -> others.joinToString(" | ") { nm(it) }
            else -> "عامة"
        }
    }

    fun same(o: PodShot): Boolean = type == o.type && main == o.main && others.contentEquals(o.others)

    companion object {
        const val FULL = 0
        const val PIP = 1
        const val MULTI = 2
        const val WIDE = 3

        fun fromJson(o: JSONObject): PodShot {
            val a = o.optJSONArray("o")
            val others = IntArray(a?.length() ?: 0) { a!!.getInt(it) }
            return PodShot(o.optDouble("s"), o.optDouble("e"), o.optInt("t"), o.optInt("m"), others).also {
                it.locked = o.optBoolean("l", false)
                it.tag = o.optString("g", "")
                it.why = o.optString("w", "")
            }.unfloat()
        }
    }
}

/** ناتج تحليل الصوت: نشاط كل مايك كل 50ms، ومستوى كلامه، ومدة الجلسة */
class PodAnalysis(val frameSec: Double, val active: Array<BooleanArray>, val speechDb: FloatArray, val duration: Double) {
    val frames: Int get() = active.firstOrNull()?.size ?: 0
    /** الأصوات القصيرة اللي اتفحصت (كلام/سعال/ضجة…) */
    var events: List<VEvent> = emptyList()
    /** التسريب اللي اتعلّمناه بين المايكات (للوصف) */
    var bleed = ""

    fun toJson(): JSONObject {
        val a = JSONArray()
        for (row in active) {
            val sb = StringBuilder(row.size)
            for (b in row) sb.append(if (b) '1' else '0')
            a.put(sb.toString())
        }
        return JSONObject().put("fs", frameSec).put("act", a).put("db", JSONArray(speechDb.map { it.toDouble() })).put("dur", duration)
            .put("v", VERSION).put("bl", bleed).put("ev", JSONArray().also { j -> for (e in events) j.put(e.toJson()) })
    }

    companion object {
        fun fromJson(o: JSONObject): PodAnalysis {
            val a = o.getJSONArray("act")
            val act = Array(a.length()) { i -> val s = a.getString(i); BooleanArray(s.length) { s[it] == '1' } }
            val d = o.getJSONArray("db")
            return PodAnalysis(o.optDouble("fs", 0.05), act, FloatArray(d.length()) { d.getDouble(it).toFloat() }, o.optDouble("dur")).also { an ->
                an.bleed = o.optString("bl", "")
                an.events = o.optJSONArray("ev")?.let { j -> List(j.length()) { VEvent.fromJson(j.getJSONObject(it)) } } ?: emptyList()
            }
        }

        /** ٢ = فيها تمييز الأصوات القصيرة (الأقدم بيتحلل من جديد) */
        /** ٤ = بيميّز تسريب الصوت بين المايكات (PodBleed) */
        const val VERSION = 4
    }
}
