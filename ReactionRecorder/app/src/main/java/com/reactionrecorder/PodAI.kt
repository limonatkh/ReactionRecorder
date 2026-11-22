package com.reactionrecorder

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** إعدادات الخدمة الخارجية (اختيارية): أي خدمة متوافقة مع OpenAI. المفتاح يُحفظ على الجهاز فقط */
class AiCfg(var base: String, var key: String, var stt: String, var chat: String, var lang: String, var prov: String = "openai", var gmodel: String = PodAI.GEMINI_DEFAULT) {
    val gemini get() = prov == "gemini"
    val ok get() = key.isNotBlank() && (gemini || base.startsWith("http"))
    /** للعرض */
    val where get() = if (gemini) "Google Gemini ($gmodel)" else "$base / $chat"
}

/**
 * التفريغ الزمني والتحليل الدلالي والترجمة عبر خدمة خارجية يختارها المستخدم (OpenAI أو Groq أو غيرها).
 * لا يُرفع شيء إلا بطلب صريح من المستخدم. المونتاج الأساسي يعمل بدونها.
 */
object PodAI {
    private const val P = "reaction_ai"
    const val GEMINI_DEFAULT = "gemini-3.6-flash"

    fun cfg(ctx: Context): AiCfg {
        val s = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        return AiCfg(
            s.getString("base", "https://api.openai.com/v1") ?: "", s.getString("key", "") ?: "",
            s.getString("stt", "whisper-1") ?: "whisper-1", s.getString("chat", "gpt-4o-mini") ?: "gpt-4o-mini",
            s.getString("lang", "ar") ?: "ar",
            s.getString("prov", null) ?: if ((s.getString("key", "") ?: "").isBlank()) "gemini" else "openai",
            s.getString("gmodel", GEMINI_DEFAULT) ?: GEMINI_DEFAULT,
        )
    }

    fun save(ctx: Context, c: AiCfg) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString("base", c.base.trim().trimEnd('/')).putString("key", c.key.trim())
            .putString("stt", c.stt.trim()).putString("chat", c.chat.trim()).putString("lang", c.lang.trim())
            .putString("prov", c.prov).putString("gmodel", c.gmodel.trim().ifBlank { GEMINI_DEFAULT }).apply()
    }

    // ───── ملفات الجلسة ─────

    fun transcriptFile(ctx: Context, sid: String) = File(SessionStore.dir(ctx, sid), "transcript.json")
    fun semanticFile(ctx: Context, sid: String) = File(SessionStore.dir(ctx, sid), "semantic.json")

    fun loadTranscript(ctx: Context, sid: String): Transcript? = try {
        val f = transcriptFile(ctx, sid)
        if (f.exists()) Transcript.fromJson(JSONObject(f.readText())) else null
    } catch (_: Throwable) { null }

    fun saveTranscript(ctx: Context, sid: String, t: Transcript) {
        try { transcriptFile(ctx, sid).writeText(t.toJson().toString()) } catch (_: Throwable) {}
    }

    fun loadSemantic(ctx: Context, sid: String): Semantic? = try {
        val f = semanticFile(ctx, sid)
        if (f.exists()) Semantic.fromJson(JSONObject(f.readText())) else null
    } catch (_: Throwable) { null }

    // ───── التفريغ ─────

    /**
     * يخلط مايكات الأشخاص إلى صوت أحادي 16kHz، يقسّمه لأجزاء (~8 دقائق) عند أقرب صمت، ويرسله للتفريغ.
     * المتحدث لكل جملة يُحدَّد من توقيت المايكات المحلي (لا يحتاج الخدمة).
     */
    fun transcribe(ctx: Context, s: SessionInfo, setup: PodSetup, an: PodAnalysis, progress: (String, Float) -> Unit, cancelled: () -> Boolean): Transcript {
        val c = cfg(ctx)
        require(c.ok) { tx("أدخل عنوان الخدمة والمفتاح أولًا", "Enter the service address and key first") }
        val dur = an.duration
        val srcs = setup.people.map { it.micDev }.filter { it.isNotEmpty() }.distinct().mapNotNull { PodAudioSrc.open(ctx, s, it) }
        require(srcs.isNotEmpty()) { tx("لا يوجد صوت مايكات في هذه الجلسة", "No microphone audio in this session") }
        val segs = ArrayList<TSeg>()
        val sounds = JSONArray()
        try {
            // حدود الأجزاء عند أقرب صمت (Gemini: أجزاء أقصر لأن الصوت بينبعت داخل الطلب)
            val cuts = ArrayList<Double>()
            cuts.add(0.0)
            val target = if (c.gemini) 150.0 else 480.0
            var t = target
            val sil = PodEngine.silences(an, 0.0, dur, 0.4)
            while (t < dur - 30) {
                val best = sil.filter { abs((it[0] + it[1]) / 2 - t) < 25 }.minByOrNull { abs((it[0] + it[1]) / 2 - t) }
                val c0 = best?.let { (it[0] + it[1]) / 2 } ?: t
                cuts.add(c0)
                t = c0 + target
            }
            cuts.add(dur)
            for (k in 0 until cuts.size - 1) {
                if (cancelled()) throw InterruptedException("Cancelled")
                val a = cuts[k]; val b = cuts[k + 1]
                progress(tx("تجهيز الصوت ${k + 1}/${cuts.size - 1}", "Preparing audio ${k + 1}/${cuts.size - 1}"), k.toFloat() / (cuts.size - 1))
                val wav = File(ctx.cacheDir, "stt_${s.id}_$k.wav")
                writeMix16k(srcs, a, b, wav)
                progress(tx("التفريغ ${k + 1}/${cuts.size - 1} (رفع وانتظار)", "Transcribing ${k + 1}/${cuts.size - 1} (upload and wait)"), (k + 0.3f) / (cuts.size - 1))
                if (c.gemini) {
                    try { segs.addAll(geminiTranscribe(c, wav, a, b, an, segs.size, sounds)) } finally { wav.delete() }
                    continue
                }
                val o = try {
                    try { postTranscription(c, wav, 2) } catch (e: IllegalStateException) {
                        // بعض الخدمات/النماذج لا تدعم توقيت الكلمات أو verbose_json: نتراجع تدريجيًا
                        if (e.message?.contains("400") != true) throw e
                        try { postTranscription(c, wav, 1) } catch (e2: IllegalStateException) {
                            if (e2.message?.contains("400") != true) throw e2
                            postTranscription(c, wav, 0)
                        }
                    }
                } finally { wav.delete() }
                val arr = o.optJSONArray("segments") ?: JSONArray()
                val words = o.optJSONArray("words")
                val allWords = ArrayList<TWord>()
                if (words != null) for (i in 0 until words.length()) {
                    val w = words.getJSONObject(i)
                    allWords.add(TWord(a + w.optDouble("start"), a + w.optDouble("end"), w.optString("word")))
                }
                if (arr.length() == 0 && o.optString("text").isNotBlank()) {
                    segs.add(TSeg(segs.size, a, b, o.optString("text").trim(), -1, allWords))
                }
                for (i in 0 until arr.length()) {
                    val g = arr.getJSONObject(i)
                    val sa = a + g.optDouble("start"); val sb = a + g.optDouble("end")
                    val text = g.optString("text").trim()
                    if (text.isEmpty()) continue
                    val ws = allWords.filter { it.a >= sa - 0.05 && it.b <= sb + 0.05 }
                    segs.add(TSeg(segs.size, sa, min(sb, b), text, -1, ws))
                }
            }
        } finally {
            for (x in srcs) x.close()
        }
        // من المتحدث في كل جملة (من نشاط المايكات)
        for (g in segs) g.speaker = speakerOf(an, g.a, g.b)
        val tr = Transcript(segs, c.lang, if (c.gemini) "gemini / ${c.gmodel}" else "${c.base} / ${c.stt}")
        saveTranscript(ctx, s.id, tr)
        if (c.gemini) try { PodVoice.soundEventsFile(ctx, s.id).writeText(sounds.toString()) } catch (_: Throwable) {}
        progress(tx("تم التفريغ", "Transcription done"), 1f)
        return tr
    }

    fun speakerOf(an: PodAnalysis, a: Double, b: Double): Int {
        val f0 = max(0, (a / an.frameSec).toInt()); val f1 = min(an.frames, (b / an.frameSec).toInt())
        var best = -1; var bestN = 0
        for ((p, row) in an.active.withIndex()) {
            var n = 0
            for (f in f0 until f1) if (row[f]) n++
            if (n > bestN) { bestN = n; best = p }
        }
        return best
    }

    private fun writeMix16k(srcs: List<PodAudioSrc>, a: Double, b: Double, out: File) {
        val rate = 16000
        val total = ((b - a) * rate).toLong()
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(44))
            val block = 8000
            val buf = FloatArray(block * 3 + 64)
            val mix = FloatArray(block)
            val bytes = ByteArray(block * 2)
            var k = 0L
            while (k < total) {
                val n = min(block.toLong(), total - k).toInt()
                java.util.Arrays.fill(mix, 0, n, 0f)
                val t0 = a + k.toDouble() / rate
                val t1 = a + (k + n).toDouble() / rate
                for (src in srcs) {
                    val n0 = src.nAt(t0); val n1 = src.nAt(t1)
                    val st = floor(n0).toLong() - 1
                    val cnt = (Math.ceil(n1).toLong() + 2 - st).toInt()
                    if (cnt <= 0 || cnt > buf.size) continue
                    src.rd.readChannel(st, cnt, 0, buf)
                    for (i in 0 until n) {
                        val np = n0 + (n1 - n0) * i / n
                        val fl = floor(np)
                        val ix = (fl.toLong() - st).toInt()
                        if (ix < 0 || ix + 1 >= cnt) continue
                        val w = (np - fl).toFloat()
                        mix[i] += buf[ix] * (1 - w) + buf[ix + 1] * w
                    }
                }
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bb.putShort((mix[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                raf.write(bytes, 0, n * 2)
                k += n
            }
            val data = total * 2
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + data).toInt()); h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
            h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(data.toInt())
            raf.seek(0); raf.write(h.array())
        }
    }

    /** level: 2 = مقاطع + كلمات، 1 = مقاطع فقط، 0 = نص فقط (json) */
    private fun postTranscription(c: AiCfg, wav: File, level: Int): JSONObject {
        val boundary = "----rr" + System.currentTimeMillis()
        val conn = URL(c.base.trimEnd('/') + "/audio/transcriptions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 600_000
        conn.setRequestProperty("Authorization", "Bearer " + c.key)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setChunkedStreamingMode(64 * 1024)
        DataOutputStream(conn.outputStream).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                out.write(value.toByteArray(Charsets.UTF_8)); out.writeBytes("\r\n")
            }
            field("model", c.stt)
            if (c.lang.isNotBlank()) field("language", c.lang)
            field("response_format", if (level >= 1) "verbose_json" else "json")
            if (level >= 2) {
                field("timestamp_granularities[]", "segment")
                field("timestamp_granularities[]", "word")
            }
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
            wav.inputStream().use { it.copyTo(out, 64 * 1024) }
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        return readJson(conn)
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { s ->
            val bo = ByteArrayOutputStream(); s.copyTo(bo); bo.toString("UTF-8")
        } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            val msg = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Throwable) { null }
            throw IllegalStateException(tx("الخدمة رفضت الطلب ($code): ${msg ?: body.take(200)}", "The service rejected the request ($code): ${msg ?: body.take(200)}"))
        }
        return JSONObject(body)
    }

    private fun chat(c: AiCfg, system: String, user: String): JSONObject {
        if (c.gemini) return geminiJson(c, system, JSONArray().put(JSONObject().put("text", user)))
        val conn = URL(c.base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000
        conn.setRequestProperty("Authorization", "Bearer " + c.key)
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val req = JSONObject().put("model", c.chat).put("temperature", 0.2)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
        conn.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
        val o = readJson(conn)
        val content = o.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        val i = content.indexOf('{'); val j = content.lastIndexOf('}')
        if (i < 0 || j <= i) throw IllegalStateException(tx("رد غير مفهوم من الخدمة", "Unclear reply from the service"))
        return parseReply(content.substring(i, j + 1))
    }

    // ───── Gemini (واجهة Google الأصلية) ─────

    /** طلب لـ Gemini بيرجع JSON (parts: نص و/أو صوت) */
    private fun parseReply(js: String): JSONObject = try { JSONObject(js) } catch (_: org.json.JSONException) {
        try { JSONObject(fixTimes(js)) } catch (_: org.json.JSONException) {
            throw IllegalStateException(tx("الرد طلع ناقص أو مش مرتب — جرّب كمان مرة", "The reply was incomplete or malformed — try again"))
        }
    }

    /** أحيانًا النموذج بيكتب الوقت 1:00.1 بدل 60.1 — بنحوّله لثواني قبل القراءة */
    fun fixTimes(js: String): String = Regex("(:\\s*)(\\d+):(\\d{1,2}(?:\\.\\d+)?)(?=\\s*[,}\\]])").replace(js) { m ->
        val sec = m.groupValues[2].toInt() * 60 + m.groupValues[3].toDouble()
        m.groupValues[1] + String.format(java.util.Locale.US, "%.2f", sec)
    }

    /** شكل رد التفريغ الإجباري (أرقام ثواني بس) */
    private val TRANSCRIBE_SCHEMA: JSONObject get() {
        fun obj(vararg p: Pair<String, JSONObject>, req: List<String>) = JSONObject().put("type", "OBJECT").put("properties", JSONObject().also { o -> for ((k, v) in p) o.put(k, v) }).put("required", JSONArray(req))
        fun t(type: String) = JSONObject().put("type", type)
        val seg = obj("start" to t("NUMBER"), "end" to t("NUMBER"), "text" to t("STRING"), req = listOf("start", "end", "text"))
        val snd = obj("start" to t("NUMBER"), "end" to t("NUMBER"), "type" to t("STRING").put("enum", JSONArray(listOf("cough", "sneeze", "laugh", "knock", "noise", "speech"))), req = listOf("start", "end", "type"))
        return obj("segments" to t("ARRAY").put("items", seg), "sounds" to t("ARRAY").put("items", snd), req = listOf("segments"))
    }

    private fun geminiJson(c: AiCfg, system: String, parts: JSONArray, temperature: Double = 0.2, schema: JSONObject? = null): JSONObject {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${c.gmodel.trim()}:generateContent"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 600_000
        conn.setRequestProperty("x-goog-api-key", c.key)
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val req = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", temperature).also { g -> if (schema != null) g.put("responseSchema", schema) })
        conn.setChunkedStreamingMode(64 * 1024)
        conn.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
        val o = try { readJson(conn) } catch (e: IllegalStateException) {
            if (e.message?.contains("(404)") == true) throw IllegalStateException(tx("النموذج «${c.gmodel}» مش موجود — غيّر اسم النموذج بإعدادات الخدمة", "Model “${c.gmodel}” not found — change the model name in the service settings"))
            throw e
        }
        val cand = o.optJSONArray("candidates")?.optJSONObject(0)
        val txt = StringBuilder()
        cand?.optJSONObject("content")?.optJSONArray("parts")?.let { a -> for (i in 0 until a.length()) txt.append(a.optJSONObject(i)?.optString("text") ?: "") }
        val content = txt.toString()
        if (content.isBlank()) throw IllegalStateException(tx("Gemini ما رجّع رد", "Gemini returned nothing") + (cand?.optString("finishReason")?.let { " ($it)" } ?: ""))
        val i = content.indexOf('{'); val j = content.lastIndexOf('}')
        if (i < 0 || j <= i) throw IllegalStateException(tx("رد غير مفهوم من الخدمة", "Unclear reply from the service"))
        return parseReply(content.substring(i, j + 1))
    }

    /**
     * تفريغ جزء صوت بـ Gemini: جمل قصيرة بتوقيتها. التوقيت من Gemini تقريبي، فبنثبّت البداية والنهاية
     * على بداية ونهاية الكلام الحقيقي من تحليل المايكات عنا.
     */
    private fun geminiTranscribe(c: AiCfg, wav: File, a: Double, b: Double, an: PodAnalysis, firstIndex: Int, sounds: JSONArray? = null): List<TSeg> {
        val b64 = android.util.Base64.encodeToString(wav.readBytes(), android.util.Base64.NO_WRAP)
        val system = """
            You transcribe Arabic speech (often Palestinian or other Levantine dialect, sometimes mixed with English) verbatim, exactly as spoken — do not translate, correct or formalise the dialect.
            Return ONLY JSON: {"segments":[{"start":seconds,"end":seconds,"text":"..."}],"sounds":[{"start":seconds,"end":seconds,"type":"cough|sneeze|laugh|knock|noise|speech"}]}
            "sounds" lists every short non-speech sound (cough, sneeze, throat clearing = cough, laughter, knocks/taps/objects = knock, other background noise = noise),
            and also very short vocal reactions like "mm", "aha", "yes" with type "speech".
            Times are plain numbers of seconds from the start of this audio clip (e.g. 63.4, never 1:03.4). Make segments short: one sentence or phrase, at most about 7 seconds each, in order, covering all speech.
            Skip silence and music. Use Arabic script for Arabic words.
        """.trimIndent()
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", b64)))
            .put(JSONObject().put("text", "Transcribe this clip (${String.format(java.util.Locale.US, "%.1f", b - a)} seconds long)."))
        val o = geminiJson(c, system, parts, 0.0, TRANSCRIBE_SCHEMA)
        val arr = o.optJSONArray("segments") ?: JSONArray()
        o.optJSONArray("sounds")?.let { sa ->
            for (i in 0 until sa.length()) {
                val g = sa.optJSONObject(i) ?: continue
                val t0 = a + g.optDouble("start", -1.0); val t1 = a + g.optDouble("end", g.optDouble("start", -1.0) + 0.5)
                if (t0 < a || t1 <= t0) continue
                val l = when (val ty = g.optString("type").lowercase()) { "cough", "sneeze", "laugh", "knock", "speech" -> ty; else -> "noise" }
                sounds?.put(JSONObject().put("a", t0 - 0.15).put("b", min(b, t1) + 0.15).put("l", l))
            }
        }
        val out = ArrayList<TSeg>()
        // كلام حقيقي (أي مايك) لتثبيت التوقيت
        fun speechAt(t: Double): Boolean {
            val f = (t / an.frameSec).toInt()
            return an.active.any { it.getOrNull(f) == true }
        }
        fun snapStart(t: Double): Double {
            var best = t
            var x = t - 0.6
            while (x <= t + 0.6) { if (speechAt(x) && !speechAt(x - an.frameSec)) { if (abs(x - t) < abs(best - t) || best == t) best = x }; x += an.frameSec }
            return best
        }
        fun snapEnd(t: Double): Double {
            var best = t
            var x = t - 0.6
            while (x <= t + 0.6) { if (speechAt(x) && !speechAt(x + an.frameSec)) { if (abs(x - t) < abs(best - t) || best == t) best = x + an.frameSec }; x += an.frameSec }
            return best
        }
        var last = a
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val text = g.optString("text").trim()
            if (text.isEmpty()) continue
            var sa = (a + g.optDouble("start", 0.0)).coerceIn(a, b)
            var sb = (a + g.optDouble("end", g.optDouble("start", 0.0) + 2.0)).coerceIn(a, b)
            sa = max(last, snapStart(sa)); sb = max(sa + 0.3, snapEnd(sb)).coerceAtMost(b)
            out.add(TSeg(firstIndex + out.size, sa, sb, text, -1, emptyList()))
            last = sb
        }
        return out
    }

    // ───── قالب من وصف ─────

    /** وصف إعدادات القالب للنموذج (القيم المسموحة) */
    private val TPL_SCHEMA = """
        name: short Arabic name
        intensity: calm|balanced|lively   (how fast the camera switches)
        layout: auto|grid   (grid = everyone on screen all the time)
        pip: true|false   (short replies/interruptions in a floating box, else split screen)
        pipPos: tl|tc|tr|bl|bc|br   (floating box position; for vertical reels prefer tc)
        pipShape: rect|rounded|circle|alternate ; pipSize: 0.15..0.45 ; pipAnim: cut|fade|slide|zoom
        pipFace: true|false (box zoomed on the face) ; avoidFace: true|false (box never covers the speaker's face)
        pipPlace: fixed|person|alternate
        multi: split|boxes ; splitDir: auto|side|stack
        glow: true|false ; glowColor: "#RRGGBB"
        names: never|first|switch ; namePos: tl|tr|bl|bc|br ; nameColor: "#RRGGBB"
        breakEvery: 0..300 seconds (0 = off; cut briefly away during long monologues) ; breakTo: auto|listener|wide|zoom ; breakLen: 1.5..5
        wideAfter: 0 (by pace) | -1 (never) | seconds of silence before showing the wide camera
        style: auto|qa|story|explain|debate|emotional
        zoomMode: off|auto ; zoomMax: 1.0..1.25 ; faces: true|false (crop follows the face)
        silence: off|light|balanced|intense (reels only: cut pauses)
        captions: true|false ; capStyle: line|words|boxed ; capPos: top|middle|bottom ; capSize: 0.8..1.4 ; capColor/capHi: "#RRGGBB"
        punch: off|emph (quick punch-in zoom on key words) ; punchAmt: 1.05..1.35
        trans: cut|fade|flash|zoom (transition between shots) ; progress: true|false (progress bar on top)
        titleOn: true|false ; hookStyle: box|big (opening title)
        items: [{"text": "...", "pos": tl|tc|tr|bl|bc|br, "when": always|start|end|every, "secs": 3..15, "everyMin": 1..10}]  — fixed texts; use {link} {handle} {channel} {guest} placeholders for the user's saved link, @handle, podcast name, guest name.
    """.trimIndent()

    /** قالب جاهز من وصفك بالكلام (المحرك بيرتب الأماكن صح، فالقالب بيطلع جاهز) */
    fun templateFromText(ctx: Context, desc: String, kind: String): PodTemplate {
        val c = cfg(ctx)
        require(c.ok) { tx("أدخل مفتاح الخدمة أولًا", "Enter the service key first") }
        val system = """
            You design video edit templates for a podcast editing app. The user describes the template they want in Arabic (often Palestinian dialect).
            Output ONLY JSON with any of these keys (omit keys you don't need; unspecified ones keep sensible defaults):
            $TPL_SCHEMA
            The template is for ${if (kind == "reel") "vertical 9:16 reels/shorts: make it engaging (hook title, word captions, punch-ins) unless the user asks for calm" else "a full horizontal 16:9 episode: keep it clean and watchable for a long time"}.
            Follow the user's description precisely; pick tasteful values for anything not mentioned.
        """.trimIndent()
        val o = chat(c, system, desc)
        return templateFromJson(o, kind)
    }

    /** يحوّل رد النموذج لقالب، بقيم مقيّدة (ما بنثق بأي قيمة) */
    fun templateFromJson(o: JSONObject, kind: String): PodTemplate {
        val t = PodTemplate()
        t.kind = kind
        if (kind == "reel") t.reelBase()
        fun str(k: String, allowed: List<String>, set: (String) -> Unit) { o.optString(k).takeIf { it in allowed }?.let(set) }
        fun bool(k: String, set: (Boolean) -> Unit) { if (o.has(k)) set(o.optBoolean(k)) }
        fun num(k: String, lo: Double, hi: Double, set: (Double) -> Unit) { if (o.has(k)) o.optDouble(k).takeIf { !it.isNaN() }?.let { set(it.coerceIn(lo, hi)) } }
        fun color(k: String, set: (Int) -> Unit) { o.optString(k).takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }?.let { set(android.graphics.Color.parseColor(it)) } }
        t.name = o.optString("name").take(40).ifBlank { tx("قالب مقترح", "Suggested template") }
        str("intensity", listOf("calm", "balanced", "lively")) { t.intensity = it }
        str("layout", listOf("auto", "grid")) { t.layout = it }
        bool("pip") { t.pip = false }
        str("pipPos", listOf("tl", "tc", "tr", "bl", "bc", "br")) { t.pipPos = it }
        str("pipShape", listOf("rect", "rounded", "circle", "alternate")) { t.pipShape = it }
        num("pipSize", 0.15, 0.45) { t.pipSize = it.toFloat() }
        str("pipAnim", listOf("cut", "fade", "slide", "zoom")) { t.pipAnim = it }
        str("pipPlace", listOf("fixed", "person", "alternate")) { t.pipPlace = it }
        bool("pipFace") { t.pipFace = it }
        bool("avoidFace") { t.avoidFace = it }
        str("multi", listOf("split", "boxes")) { t.multi = it }
        str("splitDir", listOf("auto", "side", "stack")) { t.splitDir = it }
        bool("glow") { t.glow = it }
        color("glowColor") { t.glowColor = it }
        str("names", listOf("never", "first", "switch")) { t.names = it }
        str("namePos", listOf("tl", "tr", "bl", "bc", "br")) { t.namePos = it }
        color("nameColor") { t.nameColor = it }
        num("breakEvery", 0.0, 300.0) { t.breakEvery = it.toInt() }
        str("breakTo", listOf("auto", "listener", "wide", "zoom")) { t.breakTo = it }
        num("breakLen", 1.5, 5.0) { t.breakLen = it.toFloat() }
        num("wideAfter", -1.0, 10.0) { t.wideAfter = it.toFloat() }
        str("style", listOf("auto", "qa", "story", "explain", "debate", "emotional")) { t.style = it }
        str("zoomMode", listOf("off", "auto")) { t.zoomMode = it }
        num("zoomMax", 1.0, 1.25) { t.zoomMax = it.toFloat() }
        bool("faces") { t.faces = it }
        if (kind == "reel") str("silence", listOf("off", "light", "balanced", "intense")) { t.silence = it }
        bool("captions") { t.captions = it }
        str("capStyle", listOf("line", "words", "boxed")) { t.capStyle = it }
        str("capPos", listOf("top", "middle", "bottom")) { t.capPos = it }
        num("capSize", 0.8, 1.4) { t.capSize = it.toFloat() }
        color("capColor") { t.capColor = it }
        color("capHi") { t.capHi = it }
        str("punch", listOf("off", "emph")) { t.punch = it }
        num("punchAmt", 1.05, 1.35) { t.punchAmt = it.toFloat() }
        str("trans", listOf("cut", "fade", "flash", "zoom")) { t.trans = it }
        bool("progress") { t.progress = it }
        bool("titleOn") { t.titleOn = it }
        str("hookStyle", listOf("box", "big")) { t.hookStyle = it }
        o.optJSONArray("items")?.let { a ->
            for (i in 0 until min(a.length(), 4)) {
                val x = a.optJSONObject(i) ?: continue
                val txt = x.optString("text").take(60)
                if (txt.isBlank()) continue
                t.items.add(TplItem().apply {
                    text = txt
                    pos = x.optString("pos").takeIf { it in listOf("tl", "tc", "tr", "bl", "bc", "br") } ?: "br"
                    whenShow = x.optString("when").takeIf { it in listOf("always", "start", "end", "every") } ?: "always"
                    secs = x.optDouble("secs", 5.0).coerceIn(3.0, 15.0).toFloat()
                    everyMin = x.optDouble("everyMin", 3.0).coerceIn(1.0, 10.0).toFloat()
                })
            }
        }
        if (t.trans == "fade") t.fade = true
        return t
    }

    // ───── التحليل الدلالي ─────

    /**
     * يرسل النص بتوقيته (بدون صوت) لنموذج لغوي يصنّف أجزاء الحوار ويحدد الجمل المحورية ويقترح أفكار ريلز مكتملة.
     * النتائج تُتحقّق: الأوقات داخل الحلقة، والاقتراحات تُثبَّت على حدود الجمل.
     */
    fun semantic(ctx: Context, sid: String, tr: Transcript, names: List<String>, duration: Double, target: Double, progress: (String, Float) -> Unit, cancelled: () -> Boolean): Semantic {
        val c = cfg(ctx)
        require(c.ok) { tx("أدخل عنوان الخدمة والمفتاح أولًا", "Enter the service address and key first") }
        val lines = tr.segs.map { g ->
            val who = names.getOrNull(g.speaker) ?: "?"
            "[${g.i}] (${String.format(java.util.Locale.US, "%.1f-%.1f", g.a, g.b)}) $who: ${g.text}"
        }
        val chunks = ArrayList<List<String>>()
        var cur = ArrayList<String>(); var len = 0
        for (l in lines) {
            if (len + l.length > 14000 && cur.isNotEmpty()) { chunks.add(cur); cur = ArrayList(); len = 0 }
            cur.add(l); len += l.length
        }
        if (cur.isNotEmpty()) chunks.add(cur)
        val system = """
            You analyse an Arabic (possibly Palestinian dialect) podcast transcript to help a video editor. Lines are "[segment_index] (start-end seconds) speaker: text".
            Return ONLY JSON with keys:
            "regions": [{"start":s,"end":e,"type":"qa|story|explain|debate|emotional|normal","confidence":0..1}] covering the conversation (types: question & answer, storytelling, explanation/information, fast debate, emotional/funny moment, normal).
            "key_sentences": [{"start":s,"end":e}] — pivotal sentences, conclusions, confessions or strong points (few, only real ones).
            "reel_ideas": [{"start":s,"end":e,"title":"short Arabic title","reason":"Arabic reason","type":"qa|story|explain|opinion"}] — self-contained ideas that make sense alone, start with an engaging understandable sentence and end after the idea is complete; target length about $target seconds but never cut an idea; do not reorder speech.
            "emphasis": [{"seg":i,"word":k}] — at most one important word per few sentences (k = 0-based word index in that segment text).
            Use only times that exist in the lines. If unsure, use lower confidence; do not invent laughter or emotions not evident in text.
        """.trimIndent()
        val regs = ArrayList<PodEngine.Region>()
        val keys = ArrayList<DoubleArray>()
        val ideas = ArrayList<PodEngine.Suggest>()
        val emph = HashSet<String>()
        for ((k, ch) in chunks.withIndex()) {
            if (cancelled()) throw InterruptedException("Cancelled")
            progress(tx("فهم الحوار ${k + 1}/${chunks.size}", "Understanding ${k + 1}/${chunks.size}"), k.toFloat() / chunks.size)
            val o = chat(c, system, ch.joinToString("\n"))
            o.optJSONArray("regions")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.getJSONObject(i)
                    val s0 = r.optDouble("start").coerceIn(0.0, duration); val s1 = r.optDouble("end").coerceIn(0.0, duration)
                    if (s1 - s0 > 2) regs.add(PodEngine.Region(s0, s1, r.optString("type", "normal"), r.optDouble("confidence", 0.6).coerceIn(0.0, 1.0), "ai"))
                }
            }
            o.optJSONArray("key_sentences")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.getJSONObject(i)
                    val s0 = r.optDouble("start"); val s1 = r.optDouble("end")
                    if (s1 > s0 && s0 >= 0 && s1 <= duration + 1 && s1 - s0 < 30) keys.add(doubleArrayOf(s0, min(duration, s1)))
                }
            }
            o.optJSONArray("reel_ideas")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.getJSONObject(i)
                    var s0 = r.optDouble("start"); var s1 = r.optDouble("end")
                    // تثبيت على حدود الجمل حتى لا يبدأ أو ينتهي في منتصف كلمة
                    tr.segs.filter { it.a <= s0 + 0.5 && it.b > s0 }.minByOrNull { abs(it.a - s0) }?.let { s0 = it.a }
                    tr.segs.filter { it.b >= s1 - 0.5 && it.a < s1 }.minByOrNull { abs(it.b - s1) }?.let { s1 = it.b }
                    s0 = max(0.0, s0 - 0.15); s1 = min(duration, s1 + 0.3)
                    if (s1 - s0 in 8.0..240.0) ideas.add(PodEngine.Suggest(s0, s1, 1.0 - i * 0.05, r.optString("title"), r.optString("reason"), r.optString("type", "idea")))
                }
            }
            o.optJSONArray("emphasis")?.let { a ->
                for (i in 0 until a.length()) { val r = a.getJSONObject(i); emph.add("${r.optInt("seg")}:${r.optInt("word")}") }
            }
        }
        val sem = Semantic(regs.sortedBy { it.a }, keys.sortedBy { it[0] }, ideas, emph)
        try { semanticFile(ctx, sid).writeText(sem.toJson().toString()) } catch (_: Throwable) {}
        progress(tx("تم", "Done"), 1f)
        return sem
    }

    // ───── الترجمة ─────

    fun translate(ctx: Context, texts: List<String>, toLang: String, progress: (Float) -> Unit, cancelled: () -> Boolean): List<String> {
        val c = cfg(ctx)
        require(c.ok) { tx("أدخل عنوان الخدمة والمفتاح أولًا", "Enter the service address and key first") }
        val out = ArrayList<String>()
        var i = 0
        while (i < texts.size) {
            if (cancelled()) throw InterruptedException("Cancelled")
            val part = texts.subList(i, min(texts.size, i + 40))
            val o = chat(
                c,
                "Translate each subtitle line to $toLang. Keep meaning and tone, short and natural. Return JSON {\"lines\":[...]} with exactly the same number of lines in the same order.",
                JSONObject().put("lines", JSONArray(part)).toString(),
            )
            val a = o.optJSONArray("lines")
            for (k in part.indices) out.add(a?.optString(k) ?: part[k])
            i += part.size
            progress(i.toFloat() / texts.size)
        }
        return out
    }

    // ───── حفظ ملفات نصية في التنزيلات ─────

    fun saveToDownloads(ctx: Context, name: String, text: String): String? = try {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ReactionRecorder")
        }
        val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
        if (u == null) null else {
            ctx.contentResolver.openOutputStream(u)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            "Download/ReactionRecorder/$name"
        }
    } catch (_: Throwable) { null }
}
