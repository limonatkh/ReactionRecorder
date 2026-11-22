package com.reactionrecorder

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * أدوات توحيد الوقت بين الأجهزة بعد التسجيل:
 *  • مزامنة بالصوت: لجهاز بلا قياس ساعة (انضم متأخرًا، استيراد يدوي، انقطاع من البداية) نجد إزاحته
 *    بمقارنة غلاف صوته مع جهاز مضبوط (الكل يلتقط نفس الحوار).
 *  • تغطية كل جهاز على خط الجلسة: متى بدأ ومتى توقف فعلًا.
 *  • مدى الجلسة: كل الأجهزة (من أول من بدأ لآخر من توقف) أو الجزء المشترك فقط.
 */
object PodSync {
    /** دقة الغلاف: 20 قيمة في الثانية */
    const val HOP = 0.05

    // ───────── منطق خالص (مختبر) ─────────

    /** يحوّل مستويات الصوت (dB) إلى إشارة "بدايات كلام": نطرح متوسطًا متحركًا ونقص السالب ثم نطبّع */
    fun onsets(db: FloatArray, win: Int = 20): FloatArray {
        val n = db.size
        val out = FloatArray(n)
        if (n == 0) return out
        val cs = DoubleArray(n + 1)
        for (i in 0 until n) cs[i + 1] = cs[i] + max(db[i], -90f)
        for (i in 0 until n) {
            val a = max(0, i - win); val b = min(n, i + win + 1)
            val avg = (cs[b] - cs[a]) / (b - a)
            out[i] = max(0.0, max(db[i], -90f) - avg).toFloat()
        }
        var m = 0.0; for (v in out) m += v; m /= n
        var sd = 0.0; for (v in out) sd += (v - m) * (v - m); sd = sqrt(sd / n)
        if (sd < 1e-6) return FloatArray(n)
        for (i in 0 until n) out[i] = ((out[i] - m) / sd).toFloat()
        return out
    }

    class Lag(val lag: Int, val score: Double, val second: Double)

    /**
     * أفضل إزاحة L بحيث x[i+L] ≈ ref[i]، ضمن |L| ≤ maxLag، بالارتباط عبر FFT.
     * score = الارتباط المطبّع عند القمة، second = أعلى قمة بعيدة (للتأكد أن النتيجة غير ملتبسة).
     */
    fun bestLag(ref: FloatArray, x: FloatArray, maxLag: Int): Lag? {
        if (ref.isEmpty() || x.isEmpty()) return null
        var n = 1
        while (n < ref.size + x.size) n = n shl 1
        val ar = DoubleArray(n); val ai = DoubleArray(n)
        val br = DoubleArray(n); val bi = DoubleArray(n)
        for (i in ref.indices) ar[i] = ref[i].toDouble()
        for (i in x.indices) br[i] = x[i].toDouble()
        fft(ar, ai, false); fft(br, bi, false)
        // corr[L] = Σ x[i+L]·ref[i]  ⇐  IFFT( X · conj(R) )
        for (k in 0 until n) {
            val re = br[k] * ar[k] + bi[k] * ai[k]
            val im = bi[k] * ar[k] - br[k] * ai[k]
            br[k] = re; bi[k] = im
        }
        fft(br, bi, true)
        var best = -1; var bestV = Double.NEGATIVE_INFINITY
        val lags = IntArray(2 * maxLag + 1) { it - maxLag }
        val vals = DoubleArray(lags.size)
        for ((j, L) in lags.withIndex()) {
            // عدد العينات المتداخلة عند هذه الإزاحة (نطبّع كي لا تفوز الإزاحات ذات التداخل الأكبر فقط)
            val lo = max(0, -L); val hi = min(ref.size, x.size - L)
            val overlap = hi - lo
            if (overlap < min(ref.size, x.size) / 4 || overlap < 40) { vals[j] = Double.NEGATIVE_INFINITY; continue }
            val idx = ((L % n) + n) % n
            val v = br[idx] / overlap
            vals[j] = v
            if (v > bestV) { bestV = v; best = L }
        }
        if (bestV == Double.NEGATIVE_INFINITY) return null
        var second = Double.NEGATIVE_INFINITY
        val guard = 20 // ثانية واحدة
        for ((j, L) in lags.withIndex()) if (kotlin.math.abs(L - best) > guard && vals[j] > second) second = vals[j]
        return Lag(best, bestV, if (second == Double.NEGATIVE_INFINITY) 0.0 else second)
    }

    /** FFT تكراري (radix-2) في المكان */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] = re[i] / n; im[i] = im[i] / n }
    }

    /**
     * إزاحة ساعة الجهاز (θ بالنانو) من نتيجة المزامنة بالصوت:
     * زمن الملف τ = t + lagSec  ⇒  θ = t0 + (lagSec − f0/rate)·1e9 − start (أو fb + lagSec·1e9 − start بدون طوابع).
     */
    fun thetaFromLag(lagSec: Double, start: Long, tsOk: Boolean, f0: Long, t0: Long, fb: Long, rate: Int): Long =
        if (tsOk) (t0 + (lagSec - f0.toDouble() / rate) * 1e9 - start).toLong()
        else (fb + lagSec * 1e9 - start).toLong()

    /** مدى الجلسة الجديد من تغطيات الأجهزة [بداية، نهاية] (ثوانٍ على الخط الحالي) */
    fun span(cov: List<DoubleArray>, mode: String, dur: Double): DoubleArray {
        if (cov.isEmpty() || mode == "main") return doubleArrayOf(0.0, dur)
        return if (mode == "union") doubleArrayOf(cov.minOf { it[0] }, cov.maxOf { it[1] })
        else {
            val a = cov.maxOf { it[0] }; val b = cov.minOf { it[1] }
            if (b - a < 1.0) doubleArrayOf(0.0, dur) else doubleArrayOf(a, b)
        }
    }

    /** هل تختلف أوقات الأجهزة عن مدى الجلسة بأكثر من tol ثانية؟ */
    fun mismatch(cov: List<DoubleArray>, dur: Double, tol: Double = 1.5): Boolean =
        cov.any { it[0] < -tol || it[1] > dur + tol || it[0] > tol || it[1] < dur - tol }

    /** يزيح كل أزمنة مشروع بمقدار d ثانية (بعد تغيير بداية الجلسة) ويقصّها على [0, dur] */
    fun shiftProject(p: PodProject, d: Double, dur: Double) {
        fun c(x: Double) = (x + d).coerceIn(0.0, dur)
        p.a = c(p.a)
        if (p.b >= 0) p.b = c(p.b)
        for (s in p.shots) { s.start = c(s.start); s.end = c(s.end) }
        p.shots.removeAll { it.end - it.start < 0.04 }
        for (x in p.cuts) { x.a = c(x.a); x.b = c(x.b) }
        p.cuts.removeAll { it.b - it.a < 0.02 }
        for (z in p.zooms) { z.a = c(z.a); z.b = c(z.b) }
        p.zooms.removeAll { it.b - it.a < 0.1 }
    }

    fun shiftTranscript(tr: Transcript, d: Double) {
        for (g in tr.segs) {
            g.a += d; g.b += d
            g.words = g.words.map { TWord(it.a + d, it.b + d, it.w) }
        }
        tr.segs.removeAll { it.b <= 0.0 }
    }

    // ───────── أندرويد: ملفات الجلسة ─────────

    class Cov(val dev: DevInfo, val audio: DoubleArray?, val video: DoubleArray?) {
        val range: DoubleArray? get() = when {
            audio != null && video != null -> doubleArrayOf(min(audio[0], video[0]), max(audio[1], video[1]))
            else -> audio ?: video
        }
    }

    /** تغطية كل جهاز على خط الجلسة الحالي (ثوانٍ) */
    fun coverage(ctx: Context, s: SessionInfo): List<Cov> {
        val res = ArrayList<Cov>()
        for (dv in s.devices.values) {
            if (!dv.started) continue
            val dd = SessionStore.devDir(ctx, s.id, dv.id)
            val mf = File(dd, "meta.json")
            if (!mf.exists()) continue
            val meta = try { JSONObject(mf.readText()) } catch (_: Throwable) { continue }
            var au: DoubleArray? = null
            val src = PodAudioSrc.open(ctx, s, dv.id)
            if (src != null) {
                try {
                    val n0 = src.nAt(0.0); val n1 = src.nAt(100.0)
                    val k = (n1 - n0) / 100.0
                    if (k > 1.0) au = doubleArrayOf(-n0 / k, (src.rd.frames - n0) / k)
                } catch (_: Throwable) {} finally { src.close() }
            }
            var vi: DoubleArray? = null
            val vm = meta.optJSONObject("video")
            val vf = File(dd, "video.mp4")
            if (vm != null && vf.exists()) {
                try {
                    val tm = Finalizer.SatVideoTime(s, dv, vm.optLong("startBoot"))
                    val f0 = tm.fileMs(0L); val f1 = tm.fileMs(100_000_000_000L)
                    val k = (f1 - f0) / 100.0
                    val r = android.media.MediaMetadataRetriever()
                    val durMs = try { r.setDataSource(vf.absolutePath); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0 } finally { r.release() }
                    if (k > 1.0 && durMs > 0) vi = doubleArrayOf(-f0 / k, (durMs - f0) / k)
                } catch (_: Throwable) {}
            }
            res.add(Cov(dv, au, vi))
        }
        return res
    }

    /** مستويات صوت ملف كامل كل HOP ثانية (dB) بزمن الملف نفسه */
    private fun fileLevels(rd: WavReader, cancelled: () -> Boolean): FloatArray {
        val hop = (rd.rate * HOP).toInt()
        val n = (rd.frames / hop).toInt()
        val out = FloatArray(n)
        val buf = FloatArray(hop * 40)
        var i = 0
        while (i < n) {
            if (cancelled()) break
            val cnt = min(40, n - i)
            rd.readChannel(i.toLong() * hop, cnt * hop, 0, buf)
            for (j in 0 until cnt) {
                var sum = 0.0
                for (k in 0 until hop) { val v = buf[j * hop + k]; sum += v * v }
                out[i + j] = (10 * ln(sum / hop + 1e-12) / ln(10.0)).toFloat()
            }
            i += cnt
        }
        return out
    }

    /** مستويات جهاز مضبوط بزمن الجلسة [0, dur] */
    private fun sessionLevels(src: PodAudioSrc, dur: Double, cancelled: () -> Boolean): FloatArray {
        val n = (dur / HOP).toInt()
        val out = FloatArray(n) { -120f }
        val buf = FloatArray(8192)
        for (i in 0 until n) {
            if (i % 200 == 0 && cancelled()) break
            val a = src.nAt(i * HOP); val b = src.nAt((i + 1) * HOP)
            val st = a.toLong(); val len = (b - a).toInt().coerceIn(1, buf.size)
            if (st < 0 || st + len > src.rd.frames) continue
            src.rd.readChannel(st, len, 0, buf)
            var sum = 0.0
            for (k in 0 until len) sum += buf[k] * buf[k]
            out[i] = (10 * ln(sum / len + 1e-12) / ln(10.0)).toFloat()
        }
        return out
    }

    class SyncResult(val dev: String, val name: String, val ok: Boolean, val offsetSec: Double, val note: String)

    /**
     * يضبط بالصوت كل جهاز بلا قياس ساعة (أو كل الأجهزة المختارة force) مقابل جهاز مرجعي مضبوط.
     * يحفظ النتيجة كعيّنة ساعة في الجلسة. لا يمس الملفات.
     */
    fun syncBySound(ctx: Context, sid: String, force: Set<String> = emptySet(), progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): List<SyncResult> {
        val s = SessionStore.load(ctx, sid) ?: return emptyList()
        val dur = PodcastAnalyzer.duration(s)
        val res = ArrayList<SyncResult>()
        val need = s.devices.values.filter { it.started && (it.samples.isEmpty() || it.id in force) && File(SessionStore.devDir(ctx, sid, it.id), "audio.wav").exists() }
        if (need.isEmpty() || dur < 5) return res
        val refDev = s.devices.values.filter { it.started && it.samples.isNotEmpty() && it.id !in force }
            .maxByOrNull { File(SessionStore.devDir(ctx, sid, it.id), "audio.wav").length() }
        if (refDev == null) {
            for (d in need) res.add(SyncResult(d.id, d.name, false, 0.0, tx("لا يوجد جهاز مضبوط للمقارنة", "No synced device to compare with")))
            return res
        }
        val refSrc = PodAudioSrc.open(ctx, s, refDev.id) ?: return res
        val refOn = try { onsets(sessionLevels(refSrc, dur, cancelled)) } finally { refSrc.close() }
        for ((k, d) in need.withIndex()) {
            if (cancelled()) break
            progress((k + 0.5f) / need.size)
            val dd = SessionStore.devDir(ctx, sid, d.id)
            val a = try { JSONObject(File(dd, "meta.json").readText()).optJSONObject("audio") } catch (_: Throwable) { null }
            if (a == null) { res.add(SyncResult(d.id, d.name, false, 0.0, tx("ملف الوصف ناقص", "Missing meta file"))); continue }
            val rd = try { WavReader(File(dd, "audio.wav")) } catch (_: Throwable) { null }
            if (rd == null) { res.add(SyncResult(d.id, d.name, false, 0.0, tx("ملف الصوت تالف", "Bad audio file"))); continue }
            val x = try { onsets(fileLevels(rd, cancelled)) } finally { rd.close() }
            // بحث حتى ±(طول الأطول) — بحد 40 دقيقة
            val maxLag = min(((40 * 60) / HOP).toInt(), max(refOn.size, x.size))
            val lag = bestLag(refOn, x, maxLag)
            if (lag == null || lag.score < 0.15 || lag.score < lag.second * 1.25) {
                res.add(SyncResult(d.id, d.name, false, 0.0, tx("الصوت غير واضح للمزامنة — اضبطها يدويًا", "Audio too unclear to sync — adjust manually")))
                continue
            }
            val lagSec = lag.lag * HOP
            val theta = thetaFromLag(lagSec, s.startElapsedNs, a.optBoolean("tsOk"), a.optLong("f0"), a.optLong("t0"), a.optLong("fallbackStart"), a.optInt("rate", 44100))
            SessionStore.update(ctx, sid) { x2 ->
                x2.devices[d.id]?.let { dv ->
                    dv.samples.clear()
                    dv.samples.add(DevSample(x2.startElapsedNs, theta, 0L))
                    dv.nudgeMs = 0
                }
            }
            res.add(SyncResult(d.id, d.name, true, lagSec, tx("ضُبط بالصوت", "Synced by sound")))
        }
        progress(1f)
        return res
    }

    /**
     * يغيّر مدى الجلسة (يبدأ/ينتهي حسب الأجهزة) ويزيح كل ما بُني عليه: المشاريع والنص.
     * يحذف التحليلات المحسوبة (تُعاد تلقائيًا). يعيد مقدار الإزاحة بالثواني.
     */
    fun applySpan(ctx: Context, sid: String, a: Double, b: Double): Double {
        val s = SessionStore.load(ctx, sid) ?: return 0.0
        val newStart = s.startElapsedNs + (a * 1e9).toLong()
        val newEnd = s.startElapsedNs + (b * 1e9).toLong()
        val d = -a
        SessionStore.update(ctx, sid) { x -> x.startElapsedNs = newStart; x.endElapsedNs = newEnd }
        val dur = (newEnd - newStart) / 1e9
        val dir = ProjectStore.dir(ctx, sid)
        for (f in dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: emptyList()) {
            val p = ProjectStore.load(ctx, sid, f.name.removeSuffix(".json")) ?: continue
            shiftProject(p, d, dur)
            ProjectStore.save(ctx, sid, p)
        }
        try { File(dir, "versions").deleteRecursively() } catch (_: Throwable) {}
        PodAI.loadTranscript(ctx, sid)?.let { tr -> shiftTranscript(tr, d); PodAI.saveTranscript(ctx, sid, tr) }
        val sd = SessionStore.dir(ctx, sid)
        for (n in listOf("pod_analysis.json", "semantic.json", "pod_edit.json")) try { File(sd, n).delete() } catch (_: Throwable) {}
        return d
    }
}
