package com.reactionrecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * مصدر صوت (ملف WAV من جهاز) مربوط بخط زمن الجلسة: t (ثوانٍ من بداية الجلسة) ← رقم العيّنة في الملف.
 * يستعمل نفس قياس الساعة الذي يستعمله الدمج.
 */
class PodAudioSrc private constructor(val dev: DevInfo, val rd: WavReader, private val map: Finalizer.AudioMap, private val fit: Finalizer.ClockFit, private val start: Long) {
    fun nAt(tSec: Double): Double {
        val m = start + (tSec * 1e9).toLong()
        val sat = m.toDouble() + fit.theta(m) - dev.nudgeMs * 1e6
        return map.nOf(sat)
    }

    fun close() = rd.close()

    companion object {
        fun open(ctx: Context, s: SessionInfo, devId: String): PodAudioSrc? {
            val dv = s.devices[devId] ?: return null
            val dd = SessionStore.devDir(ctx, s.id, devId)
            val wf = File(dd, "audio.wav")
            val mf = File(dd, "meta.json")
            if (!wf.exists() || !mf.exists()) return null
            return try {
                val a = JSONObject(mf.readText()).optJSONObject("audio") ?: return null
                val rd = WavReader(wf)
                PodAudioSrc(dv, rd, Finalizer.AudioMap(a, rd.rate), Finalizer.ClockFit(dv.samples), s.startElapsedNs)
            } catch (_: Throwable) {
                null
            }
        }
    }
}

object PodcastAnalyzer {
    const val FRAME = 0.05

    fun setupOf(s: SessionInfo): PodSetup = try { PodSetup.fromJson(JSONObject(s.extra)) } catch (_: Throwable) { PodSetup() }

    /** المايك الفعلي لشخص: مايكه إن وصل ملفه، وإلا صوت كاميرته (جوال الكاميرا يسجّل صوتًا أيضًا) */
    fun micOf(ctx: Context, s: SessionInfo, p: PodPerson): String {
        fun has(dev: String): Boolean {
            if (dev.isEmpty() || s.devices[dev]?.started != true) return false
            val d = SessionStore.devDir(ctx, s.id, dev)
            return File(d, "audio.wav").exists() && File(d, "meta.json").exists()
        }
        return if (has(p.micDev)) p.micDev else if (has(p.camDev)) p.camDev else p.micDev
    }

    /** هل وصل فيديو كاميرا هذا الشخص؟ */
    fun hasVideo(ctx: Context, s: SessionInfo, dev: String): Boolean {
        if (dev.isEmpty()) return false
        val d = SessionStore.devDir(ctx, s.id, dev)
        return File(d, "video.mp4").exists() && File(d, "meta.json").exists()
    }

    fun duration(s: SessionInfo): Double = ((s.endElapsedNs - s.startElapsedNs).coerceAtLeast(0L)) / 1e9

    // ───── حفظ/قراءة ─────

    fun saveAnalysis(ctx: Context, sid: String, a: PodAnalysis) {
        try { File(SessionStore.dir(ctx, sid), "pod_analysis.json").writeText(a.toJson().toString()) } catch (_: Throwable) {}
    }

    fun loadAnalysis(ctx: Context, sid: String): PodAnalysis? = try {
        val f = File(SessionStore.dir(ctx, sid), "pod_analysis.json")
        if (f.exists()) JSONObject(f.readText()).takeIf { it.optInt("v", 1) >= PodAnalysis.VERSION }?.let { PodAnalysis.fromJson(it) } else null
    } catch (_: Throwable) { null }

    fun saveShots(ctx: Context, sid: String, shots: List<PodShot>) {
        val a = JSONArray()
        for (x in shots) a.put(x.toJson())
        try { File(SessionStore.dir(ctx, sid), "pod_edit.json").writeText(a.toString()) } catch (_: Throwable) {}
    }

    fun loadShots(ctx: Context, sid: String): MutableList<PodShot>? = try {
        val f = File(SessionStore.dir(ctx, sid), "pod_edit.json")
        if (!f.exists()) null else {
            val a = JSONArray(f.readText())
            MutableList(a.length()) { PodShot.fromJson(a.getJSONObject(it)) }
        }
    } catch (_: Throwable) { null }

    // ───── تحليل الصوت: مين بيحكي ومتى ─────

    /**
     * يحسب مستوى كل مايك كل 50ms، ثم يقرّر من يتكلم:
     * المايك فوق ضوضائه بـ thrDb، وقريب (ضمن domDb) من أعلى مايك في نفس اللحظة — حتى لا يُحسب تسرّب صوت الآخر كلامًا.
     */
    fun analyze(ctx: Context, s: SessionInfo, setup: PodSetup, progress: (Float) -> Unit, cancelled: () -> Boolean): PodAnalysis {
        val dur = duration(s)
        val frames = max(1, (dur / FRAME).toInt())
        val n = setup.people.size
        val db = Array(n) { FloatArray(frames) { -120f } }
        val mics = setup.people.map { micOf(ctx, s, it) }
        for (p in 0 until n) {
            val src = PodAudioSrc.open(ctx, s, mics[p]) ?: continue
            try {
                levels(src, frames, db[p], { f -> progress((p + f) / n * 0.9f) }, cancelled)
            } finally {
                src.close()
            }
            if (cancelled()) break
        }
        // الضوضاء الأساسية لكل مايك = النسبة المئوية العاشرة
        val thr = FloatArray(n)
        for (p in 0 until n) {
            val vals = db[p].filter { it > -100f }.sorted()
            val floorDb = if (vals.isEmpty()) -90f else vals[(vals.size * 0.10).toInt().coerceIn(0, vals.size - 1)]
            thr[p] = max(floorDb + setup.thrDb, -55f)
        }
        // جوال صوته أعلى من التاني (مايك أقوى أو أقرب): بنوحّد مستوى الكلام بين المايكات قبل ما نقارن مين أعلى،
        // عشان لما الكل قاعدين جنب بعض وكل مايك سامع الكل، يفوز اللي أقرب لمايكه مش صاحب المايك الأقوى
        val off = PodBleed.offsets(db, thr)
        // مين بيحكي: كل مايك سامع الثاني، فبنتعلّم قديش كل مايك بيلقط من الثاني ونطرحه (PodBleed)
        val bl = PodBleed.activity(db, thr, off, setup.domDb) { p, q -> mics[p].isNotEmpty() && mics[p] == mics[q] }
        val act = bl.act
        // شخصان على نفس المايك: نحسب الكلام للأول فقط (لا يمكن التمييز بينهما من الصوت)
        for (p in 0 until n) for (q in 0 until p) {
            if (mics[p].isNotEmpty() && mics[p] == mics[q]) java.util.Arrays.fill(act[p], false)
        }
        // سدّ الفراغات القصيرة بين الكلمات (أقل من 0.35 ثانية)
        val gap = (0.35 / FRAME).toInt()
        for (p in 0 until n) fillGaps(act[p], gap)
        // الأصوات القصيرة: كلام (آه، امم) ولا سعال/عطس/دق؟ — اللي مش كلام ما بيقلب الكاميرا
        val events = try { PodVoice.filter(ctx, s, setup, act, FRAME, { f -> progress(0.9f + f * 0.09f) }, cancelled) } catch (_: Throwable) { emptyList() }
        val speech = FloatArray(n) { p ->
            val v = ArrayList<Float>()
            for (f in 0 until frames) if (act[p][f]) v.add(db[p][f])
            if (v.isEmpty()) -30f else { v.sort(); v[v.size / 2] }
        }
        progress(1f)
        return PodAnalysis(FRAME, act, speech, dur).also { it.events = events; it.bleed = PodBleed.describe(bl) }
    }

    private fun levels(src: PodAudioSrc, frames: Int, out: FloatArray, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val per = 20 // 20 إطارًا = ثانية
        val buf = FloatArray(50_000)
        var f0 = 0
        while (f0 < frames) {
            if (cancelled()) return
            val cnt = min(per, frames - f0)
            val n0 = src.nAt(f0 * FRAME)
            val n1 = src.nAt((f0 + cnt) * FRAME)
            val a = floor(n0).toLong()
            val len = (ceil(n1).toLong() - a).toInt().coerceIn(0, buf.size)
            if (len > 0 && n1 > 0 && n0 < src.rd.frames) {
                src.rd.readChannel(a, len, 0, buf)
                for (j in 0 until cnt) {
                    val s0 = ((n0 + (n1 - n0) * j / cnt) - a).toInt().coerceIn(0, len)
                    val s1 = ((n0 + (n1 - n0) * (j + 1) / cnt) - a).toInt().coerceIn(0, len)
                    var sum = 0.0
                    for (k in s0 until s1) sum += buf[k] * buf[k]
                    val rms = if (s1 > s0) sqrt(sum / (s1 - s0)) else 0.0
                    out[f0 + j] = (20 * log10(rms + 1e-9)).toFloat()
                }
            }
            f0 += cnt
            if (f0 % 200 == 0) progress(f0.toFloat() / frames)
        }
    }

    private fun fillGaps(a: BooleanArray, maxGap: Int) {
        var last = -1
        for (i in a.indices) {
            if (a[i]) {
                if (last >= 0 && i - last - 1 in 1..maxGap) for (k in last + 1 until i) a[k] = true
                last = i
            }
        }
    }

    private fun dropShort(a: BooleanArray, minLen: Int) {
        var i = 0
        while (i < a.size) {
            if (!a[i]) { i++; continue }
            var j = i
            while (j < a.size && a[j]) j++
            if (j - i < minLen) for (k in i until j) a[k] = false
            i = j
        }
    }

    // ───── المونتاج التلقائي ─────

    private class View(val type: Int, val main: Int, val others: IntArray) {
        fun key() = "$type:$main:" + others.joinToString(",")
    }

    fun autoShots(an: PodAnalysis, setup: PodSetup): MutableList<PodShot> {
        val n = min(setup.people.size, an.active.size)
        val frames = an.frames
        val fs = an.frameSec
        val pc = setup.pacing
        val lanes = Array(n) { an.active[it].copyOf() }
        val minLen = max(1, (pc.minSpeech / fs).toInt())
        for (l in lanes) dropShort(l, minLen)
        val hasWide = setup.wides.isNotEmpty()
        var wideIdx = 0
        fun wideView(): View { val v = View(PodShot.WIDE, wideIdx % max(1, setup.wides.size), IntArray(0)); wideIdx++; return v }

        val out = ArrayList<PodShot>()
        if (frames == 0 || n == 0) return out

        // اللقطة الأولى: أول من يتكلم، أو العامة
        var first = -1
        loop@ for (f in 0 until frames) for (p in 0 until n) if (lanes[p][f]) { first = p; break@loop }
        var cur = when {
            first >= 0 -> View(PodShot.FULL, first, IntArray(0))
            hasWide -> wideView()
            else -> View(PodShot.FULL, 0, IntArray(0))
        }
        var curStart = 0.0
        var candKey = ""
        var candSince = 0.0
        var silence = 0.0
        var cutawayUntil = -1.0

        fun emit(v: View, t: Double) {
            if (t - curStart > 1e-6) out.add(PodShot(curStart, t, cur.type, cur.main, cur.others))
            cur = v
            curStart = t
            candKey = ""
        }

        for (f in 0 until frames) {
            val t = f * fs
            val speakers = (0 until n).filter { lanes[it][f] }
            silence = if (speakers.isEmpty()) silence + fs else 0.0
            val inShot = t - curStart

            // ما يجب أن يظهر الآن
            var desire: View = when {
                speakers.size == 1 -> View(PodShot.FULL, speakers[0], IntArray(0))
                speakers.size >= 2 -> View(PodShot.MULTI, speakers[0], speakers.toIntArray())
                hasWide && silence >= pc.silenceToWide -> View(PodShot.WIDE, cur.main, IntArray(0))
                else -> cur
            }

            // لقطة عامة للتنويع إذا طالت لقطة نفس الشخص
            if (hasWide && pc.maxShot > 0 && cur.type == PodShot.FULL && desire.key() == cur.key() && inShot >= pc.maxShot) {
                cutawayUntil = t + max(pc.minShot.toDouble(), 2.5)
                emit(wideView(), t)
                continue
            }
            if (cur.type == PodShot.WIDE && t < cutawayUntil && desire.type != PodShot.MULTI) {
                continue
            }

            // ترجمة الرغبة إلى لقطة مع المربع العائم
            if (desire.type == PodShot.FULL && setup.usePip) {
                val p = desire.main
                if (cur.type == PodShot.FULL && cur.main != p) {
                    desire = View(PodShot.PIP, cur.main, intArrayOf(p))
                } else if (cur.type == PodShot.PIP && cur.others.contentEquals(intArrayOf(p))) {
                    desire = if (inShot >= pc.pipToFull) View(PodShot.FULL, p, IntArray(0)) else cur
                    if (desire === cur) { candKey = ""; continue }
                    emit(desire, t)
                    continue
                }
            }
            if (desire.type == PodShot.WIDE && cur.type == PodShot.WIDE) { candKey = ""; continue }
            val k = desire.key()
            if (k == cur.key()) { candKey = ""; continue }
            if (k != candKey) { candKey = k; candSince = t }
            val held = t - candSince
            if (held >= pc.switchDelay && inShot >= pc.minShot) {
                val v = if (desire.type == PodShot.WIDE) wideView() else desire
                emit(v, max(candSince, curStart + pc.minShot))
            }
        }
        out.add(PodShot(curStart, frames * fs, cur.type, cur.main, cur.others))
        return tidy(out, pc.minShot.toDouble())
    }

    // ───── قص السكتات واقتراح اللحظات ─────

    /** هل يتكلم أحد في الإطار f (بعد تجاهل الأصوات القصيرة) */
    private fun anyoneLane(an: PodAnalysis, minSpeech: Float): BooleanArray {
        val minLen = max(1, (minSpeech / an.frameSec).toInt())
        val out = BooleanArray(an.frames)
        for (row in an.active) {
            val r = row.copyOf()
            dropShort(r, minLen)
            for (i in r.indices) if (r[i]) out[i] = true
        }
        return out
    }

    /**
     * أجزاء الكلام داخل [a,b] بعد حذف السكتات الأطول من gap (يُترك pad قبل وبعد الكلام كي لا يُقطع النَّفَس).
     * إن لم يوجد كلام يعيد المدى كاملًا.
     */
    fun speechSegments(an: PodAnalysis, a: Double, b: Double, gap: Float, minSpeech: Float = 0.15f): List<DoubleArray> {
        val lane = anyoneLane(an, minSpeech)
        val fs = an.frameSec
        val pad = 0.08
        val raw = ArrayList<DoubleArray>()
        var i = (a / fs).toInt().coerceAtLeast(0)
        val end = (b / fs).toInt().coerceAtMost(lane.size)
        while (i < end) {
            if (!lane[i]) { i++; continue }
            var j = i
            while (j < end && lane[j]) j++
            raw.add(doubleArrayOf(max(a, i * fs - pad), min(b, j * fs + pad)))
            i = j
        }
        if (raw.isEmpty()) return listOf(doubleArrayOf(a, b))
        val merged = ArrayList<DoubleArray>()
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && r[0] - last[1] < gap) last[1] = max(last[1], r[1]) else merged.add(r)
        }
        return merged
    }

    /**
     * يقترح لحظات حيوية للشورتس: نافذة بطول len تُقيَّم بكثافة الكلام، تبادل المتكلمين، والكلام المتداخل (حماس/ضحك).
     * يعيد حتى count نوافذ غير متداخلة، الأفضل أولًا: [بداية، نهاية، تقييم 0..1]
     */
    fun highlights(an: PodAnalysis, len: Double, count: Int): List<DoubleArray> {
        val fs = an.frameSec
        val n = an.frames
        val w = (len / fs).toInt()
        if (n == 0 || w <= 0) return emptyList()
        if (w >= n) return listOf(doubleArrayOf(0.0, an.duration, 1.0))
        val lane = anyoneLane(an, 0.3f)
        val speakerAt = IntArray(n) { -1 }
        val overlap = BooleanArray(n)
        for (f in 0 until n) {
            var c = 0
            for ((p, row) in an.active.withIndex()) if (row[f]) { c++; if (speakerAt[f] < 0) speakerAt[f] = p }
            overlap[f] = c >= 2
        }
        val change = BooleanArray(n)
        var last = -1
        for (f in 0 until n) {
            val sp = speakerAt[f]
            if (sp >= 0) { if (last >= 0 && sp != last) change[f] = true; last = sp }
        }
        // مجاميع تراكمية لتقييم كل نافذة بسرعة
        val cs = IntArray(n + 1); val cc = IntArray(n + 1); val co = IntArray(n + 1)
        for (f in 0 until n) {
            cs[f + 1] = cs[f] + (if (lane[f]) 1 else 0)
            cc[f + 1] = cc[f] + (if (change[f]) 1 else 0)
            co[f + 1] = co[f] + (if (overlap[f]) 1 else 0)
        }
        val step = max(1, (5.0 / fs).toInt())
        val cand = ArrayList<DoubleArray>()
        var f = 0
        while (f + w <= n) {
            val dens = (cs[f + w] - cs[f]).toDouble() / w
            val changes = (cc[f + w] - cc[f]).toDouble() / (len / 60.0)
            val ov = (co[f + w] - co[f]).toDouble() / w
            val score = dens * 0.5 + min(changes / 12.0, 1.0) * 0.35 + min(ov * 5.0, 1.0) * 0.15
            cand.add(doubleArrayOf(f * fs, (f + w) * fs, score))
            f += step
        }
        cand.sortByDescending { it[2] }
        val res = ArrayList<DoubleArray>()
        for (c in cand) {
            if (res.any { c[0] < it[1] && c[1] > it[0] }) continue
            // نبدأ عند بداية جملة قريبة بدل منتصف كلمة
            var st = (c[0] / fs).toInt()
            var k = 0
            while (st > 0 && lane[st] && k < (3.0 / fs).toInt()) { st--; k++ }
            val s0 = st * fs
            res.add(doubleArrayOf(s0, min(an.duration, s0 + len), c[2]))
            if (res.size >= count) break
        }
        val best = res.maxOfOrNull { it[2] } ?: 1.0
        for (r in res) r[2] = if (best > 0) r[2] / best else 0.0
        return res
    }

    /** يدمج المتشابه المتتالي واللقطات الأقصر من الحد الأدنى */
    fun tidy(list: List<PodShot>, minShot: Double): MutableList<PodShot> {
        val res = ArrayList<PodShot>()
        for (x in list) {
            if (x.dur <= 1e-6) continue
            val last = res.lastOrNull()
            if (last != null && (last.same(x) || x.dur < minShot * 0.5)) { last.end = x.end; continue }
            res.add(x.copy())
        }
        return res
    }
}
