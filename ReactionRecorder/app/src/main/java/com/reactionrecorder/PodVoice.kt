package com.reactionrecorder

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** صوت قصير بمايك شخص: شو هو وشو قررنا فيه */
class VEvent(val p: Int, val a: Double, val b: Double, var label: String, var verdict: String, var conf: Float) {
    /** مفتاح ثابت (للتعديلات اليدوية) */
    val key: String get() = "$p:${(a * 1000).toLong()}"
    fun toJson(): JSONObject = JSONObject().put("p", p).put("a", a).put("b", b).put("l", label).put("v", verdict).put("c", conf.toDouble())

    companion object {
        const val SPEECH = "speech"
        const val NOISE = "noise"
        const val UNSURE = "unsure"
        fun fromJson(o: JSONObject) = VEvent(o.optInt("p"), o.optDouble("a"), o.optDouble("b"), o.optString("l"), o.optString("v", UNSURE), o.optDouble("c", 0.0).toFloat())

        fun labelName(l: String): String = when (l) {
            "speech" -> tx("كلام قصير (آه، امم)", "Short speech (uh-huh, hmm)")
            "laugh" -> tx("ضحك", "Laughter")
            "cough" -> tx("سعال", "Cough")
            "sneeze" -> tx("عطس", "Sneeze")
            "throat" -> tx("تنحنح", "Throat clearing")
            "breath" -> tx("نَفَس/شهقة", "Breath")
            "knock" -> tx("دق/خبطة", "Knock/bang")
            "rustle" -> tx("خشخشة/حركة أغراض", "Rustling/handling")
            "steps" -> tx("خطوات/حركة", "Footsteps/movement")
            "music" -> tx("موسيقى", "Music")
            else -> tx("صوت قصير", "Short sound")
        }
    }
}

/**
 * تمييز الأصوات القصيرة بمايك كل شخص: كلام (آه، امم) ولا سعال/عطس/دق/خشخشة؟
 * ثلاث طبقات: (١) نوع الصوت (فيه نغمة حنجرة؟ بدايته مفاجئة؟)، (٢) مُصنّف أصوات YAMNet على الجهاز،
 * (٣) التفريغ إذا موجود (فيه كلمة؟ أو Gemini علّمه سعال/ضجة). اللي مش متأكدين منه ما بيقلب الكاميرا وبيطلع للمراجعة.
 */
object PodVoice {
    /** أقصر من هيك = "صوت قصير" لازم يتفحص (الأطول كلام أكيد) */
    const val SHORT = 2.0
    private const val SR = 16000

    private val SPEECH_SET = setOf("Speech", "Child speech, kid speaking", "Conversation", "Narration, monologue", "Babbling", "Whispering", "Humming", "Grunt", "Sigh", "Groan", "Shout", "Yell", "Chant", "Speech synthesizer", "Male speech, man speaking", "Female speech, woman speaking")
    private val LAUGH_SET = setOf("Laughter", "Giggle", "Chuckle, chortle", "Belly laugh", "Snicker", "Baby laughter")
    private val NOISE_MAP = mapOf(
        "Cough" to "cough", "Sneeze" to "sneeze", "Throat clearing" to "throat", "Sniff" to "breath", "Breathing" to "breath", "Gasp" to "breath",
        "Wheeze" to "breath", "Snoring" to "breath", "Burping, eructation" to "breath", "Hiccup" to "breath",
        "Knock" to "knock", "Tap" to "knock", "Clatter" to "knock", "Thump, thud" to "knock", "Bang" to "knock", "Door" to "knock", "Slam" to "knock",
        "Wood" to "knock", "Glass" to "knock", "Chink, clink" to "knock", "Dishes, pots, and pans" to "knock", "Cutlery, silverware" to "knock", "Squeak" to "knock",
        "Rustle" to "rustle", "Crumpling, crinkling" to "rustle", "Writing" to "rustle", "Typing" to "rustle", "Computer keyboard" to "rustle", "Zipper (clothing)" to "rustle",
        "Paper rustling" to "rustle", "Scratch" to "rustle", "Rub" to "rustle", "Walk, footsteps" to "steps", "Shuffle" to "steps",
        "Music" to "music", "Musical instrument" to "music",
    )

    // ───── المصنّف (YAMNet عبر MediaPipe) — اختياري: إذا مش موجود بنكمل بطبقة نوع الصوت ─────

    private class Yam(ctx: Context) {
        val clf: com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
        init {
            val opts = com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(com.google.mediapipe.tasks.core.BaseOptions.builder().setModelAssetPath("yamnet.tflite").build())
                .setRunningMode(com.google.mediapipe.tasks.audio.core.RunningMode.AUDIO_CLIPS)
                .setMaxResults(10)
                .build()
            clf = com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier.createFromOptions(ctx, opts)
        }

        /** أعلى نتيجة لكل تصنيف عبر النوافذ */
        fun classify(x: FloatArray): Map<String, Float> {
            val fmt = com.google.mediapipe.tasks.components.containers.AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(SR.toFloat()).build()
            val data = com.google.mediapipe.tasks.components.containers.AudioData.create(fmt, x.size)
            data.load(x)
            val res = clf.classify(data)
            val out = HashMap<String, Float>()
            for (cr in res.classificationResults()) for (cl in cr.classifications()) for (c in cl.categories()) {
                val n = c.categoryName()
                out[n] = max(out[n] ?: 0f, c.score())
            }
            return out
        }

        fun close() { try { clf.close() } catch (_: Throwable) {} }
    }

    private fun yamOrNull(ctx: Context): Yam? = try {
        ctx.assets.open("yamnet.tflite").close()
        Yam(ctx)
    } catch (_: Throwable) { null }

    // ───── طبقة نوع الصوت ─────

    /** [نسبة الإطارات المصوّتة، مفاجئ؟ (1/0)، مدة الجزء المسموع بالثواني] */
    fun voicing(x: FloatArray): FloatArray {
        val frame = 640; val hop = 320
        if (x.size < frame) return floatArrayOf(0f, 1f, 0f)
        val nF = (x.size - frame) / hop + 1
        val en = FloatArray(nF)
        for (f in 0 until nF) { var s = 0.0; val o = f * hop; for (i in 0 until frame) s += x[o + i] * x[o + i]; en[f] = s.toFloat() }
        val maxE = en.maxOrNull() ?: 0f
        if (maxE <= 1e-7f) return floatArrayOf(0f, 0f, 0f)
        var energetic = 0; var voiced = 0; var first = -1; var last = -1
        for (f in 0 until nF) {
            if (en[f] < maxE * 0.05f) continue
            energetic++
            if (first < 0) first = f
            last = f
            // ارتباط ذاتي مطبّع: نغمة الحنجرة بين ٧٥ و٤٠٠ هرتز
            val o = f * hop
            var best = 0.0
            var tau = SR / 400
            while (tau <= SR / 75) {
                var s = 0.0; var a = 0.0; var b = 0.0
                var i = 0
                while (i + tau < frame) { val u = x[o + i]; val v = x[o + i + tau]; s += u * v; a += u * u; b += v * v; i += 2 }
                val r = s / (sqrt(a * b) + 1e-12)
                if (r > best) best = r
                tau += 2
            }
            if (best > 0.55) voiced++
        }
        val dur = if (first < 0) 0f else ((last - first + 1) * hop / SR.toFloat())
        // مفاجئ: بيوصل قمته بأول إطارين وقصير، أو قصير جدًا
        val peak = en.indices.maxByOrNull { en[it] } ?: 0
        val impulsive = (dur < 0.15f) || (peak - max(0, first) <= 1 && dur < 0.35f)
        return floatArrayOf(if (energetic == 0) 0f else voiced.toFloat() / energetic, if (impulsive) 1f else 0f, dur)
    }

    /** قرار من طبقة نوع الصوت والمصنّف */
    fun decide(v: FloatArray, yam: Map<String, Float>?): Triple<String, String, Float> {
        val voicedRatio = v[0]; val impulsive = v[1] > 0.5f
        if (yam != null && yam.isNotEmpty()) {
            val sp = SPEECH_SET.maxOf { yam[it] ?: 0f }
            val lg = LAUGH_SET.maxOf { yam[it] ?: 0f }
            var nz = 0f; var nzLabel = "noise"
            for ((k, l) in NOISE_MAP) { val s = yam[k] ?: 0f; if (s > nz) { nz = s; nzLabel = l } }
            if (lg > 0.2f && lg >= sp && lg >= nz) return Triple("laugh", VEvent.SPEECH, lg)
            if (sp >= 0.25f && sp >= nz) return Triple("speech", VEvent.SPEECH, sp)
            if (nz >= 0.25f && nz > sp) return Triple(nzLabel, VEvent.NOISE, nz)
            // المصنّف مش متأكد: نرجع لنوع الصوت
            if (voicedRatio >= 0.5f && !impulsive) return Triple("speech", VEvent.SPEECH, 0.5f + voicedRatio / 4)
            if (impulsive && voicedRatio < 0.2f) return Triple(if (nz > 0.1f) nzLabel else "knock", VEvent.NOISE, 0.5f)
            return Triple(if (nz > sp) nzLabel else "noise", VEvent.UNSURE, max(sp, nz))
        }
        if (voicedRatio >= 0.5f && !impulsive) return Triple("speech", VEvent.SPEECH, 0.5f + voicedRatio / 4)
        if (impulsive && voicedRatio < 0.2f) return Triple("knock", VEvent.NOISE, 0.6f)
        if (voicedRatio < 0.15f) return Triple("noise", VEvent.NOISE, 0.5f)
        return Triple("noise", VEvent.UNSURE, 0.3f)
    }

    /**
     * يمر على الأصوات القصيرة بمايك كل شخص، ويطفي (من نشاط الكلام) اللي مش كلام أو مش متأكدين منه.
     * بيرجع كل الأحداث مع قرارها (للمراجعة والتعديل اليدوي).
     */
    fun filter(ctx: Context, s: SessionInfo, setup: PodSetup, act: Array<BooleanArray>, frameSec: Double, progress: (Float) -> Unit, cancelled: () -> Boolean): List<VEvent> {
        val out = ArrayList<VEvent>()
        val runs = ArrayList<IntArray>() // p, f0, f1
        for (p in act.indices) {
            val a = act[p]
            var i = 0
            while (i < a.size) {
                if (!a[i]) { i++; continue }
                var j = i
                while (j < a.size && a[j]) j++
                if ((j - i) * frameSec < SHORT) runs.add(intArrayOf(p, i, j))
                i = j
            }
        }
        if (runs.isEmpty()) return out
        val yam = yamOrNull(ctx)
        val mics = setup.people.map { PodcastAnalyzer.micOf(ctx, s, it) }
        val srcs = HashMap<String, PodAudioSrc?>()
        try {
            for ((k, r) in runs.withIndex()) {
                if (cancelled()) break
                if (k % 10 == 0) progress(k.toFloat() / runs.size)
                val p = r[0]
                val a = r[1] * frameSec; val b = r[2] * frameSec
                val mic = mics.getOrNull(p) ?: continue
                val src = srcs.getOrPut(mic) { PodAudioSrc.open(ctx, s, mic) } ?: continue
                // الصوت نفسه للتحليل، ونافذة ثانية حواليه للمصنّف
                val x = read16k(src, a - 0.05, b + 0.05)
                val mid = (a + b) / 2
                val win = if (b - a >= 0.975) x else read16k(src, mid - 0.5, mid + 0.5)
                val v = voicing(x)
                val ym = try { yam?.classify(win) } catch (_: Throwable) { null }
                val (label, verdict, conf) = decide(v, ym)
                val e = VEvent(p, a, b, label, verdict, conf)
                out.add(e)
                if (verdict != VEvent.SPEECH) for (f in r[1] until r[2]) act[p][f] = false
            }
        } finally {
            for (x in srcs.values) try { x?.close() } catch (_: Throwable) {}
            yam?.close()
        }
        progress(1f)
        return out
    }

    /** يقرأ صوت المايك بين a و b (ثواني الجلسة) بـ 16kHz */
    /** صوت شخص من مايكه الأصلي بين a وb (للمراجعة): [عينات 16bit، المعدل] */
    fun clip(ctx: Context, s: SessionInfo, setup: PodSetup, p: Int, a: Double, b: Double): Pair<ShortArray, Int>? {
        val person = setup.people.getOrNull(p) ?: return null
        val src = PodAudioSrc.open(ctx, s, PodcastAnalyzer.micOf(ctx, s, person)) ?: return null
        try {
            val rate = src.rd.rate
            val n0 = floor(src.nAt(max(0.0, a))).toLong().coerceAtLeast(0L)
            val n1 = ceil(src.nAt(max(0.0, b))).toLong()
            val len = (n1 - n0).toInt().coerceIn(0, rate * 12)
            if (len <= 0) return null
            val buf = FloatArray(len)
            src.rd.readChannel(n0, len, 0, buf)
            // تكبير خفيف للأصوات الواطية عشان تنسمع
            val peak = buf.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1e-4f) ?: 1f
            val g = (0.8f / peak).coerceIn(1f, 6f)
            return ShortArray(len) { i -> (buf[i] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort() } to rate
        } catch (_: Throwable) { return null } finally { src.close() }
    }

    private fun read16k(src: PodAudioSrc, a: Double, b: Double): FloatArray {
        val rate = src.rd.rate
        val n0 = src.nAt(max(0.0, a)); val n1 = src.nAt(max(0.0, b))
        val start = floor(n0).toLong()
        val len = (ceil(n1).toLong() - start).toInt().coerceIn(0, rate * 4)
        if (len <= 0) return FloatArray(0)
        val buf = FloatArray(len)
        src.rd.readChannel(start, len, 0, buf)
        val outN = (len.toLong() * SR / rate).toInt().coerceAtLeast(1)
        val out = FloatArray(outN)
        val k = rate.toDouble() / SR
        for (i in 0 until outN) {
            val pos = i * k; val i0 = pos.toInt(); val fr = (pos - i0).toFloat()
            val x0 = buf.getOrElse(i0) { 0f }; val x1 = buf.getOrElse(i0 + 1) { x0 }
            out[i] = x0 + (x1 - x0) * fr
        }
        return out
    }

    // ───── التعديلات اليدوية ─────

    class Overrides {
        /** أحداث قلت "اعرضه" */
        val accept = HashSet<String>()
        /** أحداث قلت "تجاهله" (حتى لو كانت كلام) */
        val reject = HashSet<String>()
        /** فترات "خلّي الكاميرا بعيد عنه" [شخص، من، لـ] بثواني الجلسة */
        val ignores = ArrayList<DoubleArray>()
        /** الضحك بيقلب الكاميرا؟ */
        var laugh = true

        fun toJson(): JSONObject = JSONObject().put("acc", JSONArray(accept.toList())).put("rej", JSONArray(reject.toList())).put("laugh", laugh)
            .put("ign", JSONArray().also { a -> for (x in ignores) a.put(JSONArray(x.toList())) })
    }

    private fun ovFile(ctx: Context, sid: String) = File(SessionStore.dir(ctx, sid), "voice_overrides.json")
    private fun liveFile(ctx: Context, sid: String) = File(SessionStore.dir(ctx, sid), "ignores_live.json")

    fun overrides(ctx: Context, sid: String): Overrides {
        val o = Overrides()
        try {
            val j = JSONObject(ovFile(ctx, sid).readText())
            j.optJSONArray("acc")?.let { a -> for (i in 0 until a.length()) o.accept.add(a.getString(i)) }
            j.optJSONArray("rej")?.let { a -> for (i in 0 until a.length()) o.reject.add(a.getString(i)) }
            j.optJSONArray("ign")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { x -> if (x.length() == 3) o.ignores.add(doubleArrayOf(x.getDouble(0), x.getDouble(1), x.getDouble(2))) } }
            o.laugh = j.optBoolean("laugh", true)
        } catch (_: Throwable) {}
        return o
    }

    fun saveOverrides(ctx: Context, sid: String, o: Overrides) {
        try { ovFile(ctx, sid).writeText(o.toJson().toString()) } catch (_: Throwable) {}
    }

    /** وقت التسجيل: بداية/نهاية "خلّي الكاميرا بعيد عن فلان" (بزمن الجهاز) */
    @Synchronized
    fun liveIgnore(ctx: Context, sid: String, p: Int, on: Boolean) {
        val now = SystemClock.elapsedRealtimeNanos()
        val f = liveFile(ctx, sid)
        val a = try { JSONArray(f.readText()) } catch (_: Throwable) { JSONArray() }
        if (on) a.put(JSONObject().put("p", p).put("s", now).put("e", 0L))
        else for (i in a.length() - 1 downTo 0) { val o = a.getJSONObject(i); if (o.optInt("p") == p && o.optLong("e") == 0L) { o.put("e", now); break } }
        try { f.writeText(a.toString()) } catch (_: Throwable) {}
    }

    private fun liveRanges(ctx: Context, s: SessionInfo): List<DoubleArray> = try {
        val a = JSONArray(liveFile(ctx, s.id).readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val st = (o.optLong("s") - s.startElapsedNs) / 1e9
            val e = o.optLong("e").let { if (it == 0L) Double.MAX_VALUE else (it - s.startElapsedNs) / 1e9 }
            doubleArrayOf(o.optInt("p").toDouble(), st, e)
        }
    } catch (_: Throwable) { emptyList() }

    /** أحداث Gemini (سعال/عطس/ضجة/ضحك) من التفريغ */
    fun soundEventsFile(ctx: Context, sid: String) = File(SessionStore.dir(ctx, sid), "sound_events.json")

    /**
     * بيطبّق تعديلاتك (اعرضه/تجاهله/فترات التجاهل/الضحك) والتفريغ على نشاط الكلام بالذاكرة،
     * قبل ما المونتاج يقرر اللقطات.
     */
    fun apply(ctx: Context, s: SessionInfo, an: PodAnalysis, tr: Transcript?) {
        val o = overrides(ctx, s.id)
        val fs = an.frameSec
        fun setRange(p: Int, a: Double, b: Double, v: Boolean) {
            val row = an.active.getOrNull(p) ?: return
            val f0 = (a / fs).toInt().coerceIn(0, row.size); val f1 = ceil(b / fs).toInt().coerceIn(0, row.size)
            for (f in f0 until f1) row[f] = v
        }
        // أحداث Gemini: سعال/ضجة بتغلب، وكلام مفرّغ بيأكد
        val gem = try { JSONArray(soundEventsFile(ctx, s.id).readText()).let { a -> List(a.length()) { i -> a.getJSONObject(i) } } } catch (_: Throwable) { emptyList() }
        for (e in an.events) {
            var speech = e.verdict == VEvent.SPEECH
            if (e.label == "laugh") speech = o.laugh
            if (!speech && e.verdict == VEvent.UNSURE && tr != null && tr.segs.any { it.text.isNotBlank() && it.a < e.b - 0.05 && it.b > e.a + 0.05 && (it.speaker == e.p || it.speaker < 0) }) speech = true
            val g = gem.firstOrNull { it.optDouble("a") < e.b && it.optDouble("b") > e.a }
            if (g != null) {
                val gl = g.optString("l")
                if (gl == "laugh") speech = o.laugh else if (gl.isNotBlank() && gl != "speech") speech = false
            }
            if (e.key in o.accept) speech = true
            if (e.key in o.reject) speech = false
            setRange(e.p, e.a, e.b, speech)
        }
        for (r in o.ignores + liveRanges(ctx, s)) setRange(r[0].toInt(), r[1], min(r[2], an.duration), false)
    }
}
