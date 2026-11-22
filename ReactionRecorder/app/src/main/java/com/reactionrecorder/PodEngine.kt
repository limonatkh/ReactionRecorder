package com.reactionrecorder

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ═══════════ نص الحوار (تفريغ زمني) ═══════════

class TWord(val a: Double, val b: Double, val w: String)

/** جملة مفرّغة: i رقمها، speaker من يتكلم (من توقيت المايكات) */
class TSeg(val i: Int, var a: Double, var b: Double, var text: String, var speaker: Int, var words: List<TWord>) {
    fun toJson(): JSONObject = JSONObject().put("i", i).put("a", a).put("b", b).put("t", text).put("sp", speaker)
        .put("w", JSONArray().also { j -> for (x in words) j.put(JSONArray().put(x.a).put(x.b).put(x.w)) })

    companion object {
        fun fromJson(o: JSONObject): TSeg {
            val w = ArrayList<TWord>()
            o.optJSONArray("w")?.let { a -> for (k in 0 until a.length()) { val x = a.getJSONArray(k); w.add(TWord(x.getDouble(0), x.getDouble(1), x.getString(2))) } }
            return TSeg(o.optInt("i"), o.optDouble("a"), o.optDouble("b"), o.optString("t"), o.optInt("sp", -1), w)
        }
    }
}

class Transcript(val segs: MutableList<TSeg>, var lang: String, var source: String) {
    fun toJson(): JSONObject = JSONObject().put("lang", lang).put("src", source).put("segs", JSONArray().also { j -> for (s in segs) j.put(s.toJson()) })

    fun textIn(a: Double, b: Double): String = segs.filter { it.b > a && it.a < b }.joinToString(" ") { it.text.trim() }

    companion object {
        fun fromJson(o: JSONObject): Transcript {
            val a = o.optJSONArray("segs") ?: JSONArray()
            return Transcript(MutableList(a.length()) { TSeg.fromJson(a.getJSONObject(it)) }, o.optString("lang", "ar"), o.optString("src"))
        }
    }
}

/** نتيجة التحليل الدلالي (من خدمة خارجية اختيارية) */
class Semantic(val regions: List<PodEngine.Region>, val keys: List<DoubleArray>, val ideas: List<PodEngine.Suggest>, val emphWords: Set<String>) {
    fun toJson(): JSONObject = JSONObject()
        .put("regions", JSONArray().also { j -> for (r in regions) j.put(r.toJson()) })
        .put("keys", JSONArray().also { j -> for (k in keys) j.put(JSONArray().put(k[0]).put(k[1])) })
        .put("ideas", JSONArray().also { j -> for (s in ideas) j.put(s.toJson()) })
        .put("emph", JSONArray(emphWords.toList()))

    companion object {
        fun fromJson(o: JSONObject): Semantic {
            val r = ArrayList<PodEngine.Region>()
            o.optJSONArray("regions")?.let { a -> for (i in 0 until a.length()) r.add(PodEngine.Region.fromJson(a.getJSONObject(i))) }
            val k = ArrayList<DoubleArray>()
            o.optJSONArray("keys")?.let { a -> for (i in 0 until a.length()) { val x = a.getJSONArray(i); k.add(doubleArrayOf(x.getDouble(0), x.getDouble(1))) } }
            val s = ArrayList<PodEngine.Suggest>()
            o.optJSONArray("ideas")?.let { a -> for (i in 0 until a.length()) s.add(PodEngine.Suggest.fromJson(a.getJSONObject(i))) }
            val e = HashSet<String>()
            o.optJSONArray("emph")?.let { a -> for (i in 0 until a.length()) e.add(a.getString(i)) }
            return Semantic(r, k, s, e)
        }
    }
}

/**
 * منطق المونتاج كاملًا بدون أي اعتماد على أندرويد (قابل للاختبار على الكمبيوتر):
 * أدوار الكلام والتعقيبات القصيرة، طبيعة الحوار، اللقطات التلقائية، حماية المثبّت، قص الصمت، ربط الزمن بعد القص،
 * الزوم، اقتراح الريلز، تقسيم الكتابة، وملفات SRT.
 */
object PodEngine {

    // ═══════════ أدوار الكلام ═══════════

    /** فترة كلام متصلة لشخص. backchannel = تعقيب قصير ("آه"، "صح") لا يأخذ الدور */
    class Turn(val p: Int, var a: Double, var b: Double, var backchannel: Boolean = false) {
        val dur get() = b - a
    }

    /** يحوّل نشاط المايكات إلى أدوار: يتجاهل الأصوات الأقصر من minSpeech ويصل الفراغات الأقصر من joinGap */
    fun turns(an: PodAnalysis, n: Int, minSpeech: Double = 0.2, joinGap: Double = 0.7): List<Turn> {
        val fs = an.frameSec
        val res = ArrayList<Turn>()
        for (p in 0 until min(n, an.active.size)) {
            val row = an.active[p]
            var cur: Turn? = null
            var i = 0
            while (i < row.size) {
                if (!row[i]) { i++; continue }
                var j = i
                while (j < row.size && row[j]) j++
                val a = i * fs
                val b = j * fs
                if (b - a >= minSpeech) {
                    val c = cur
                    if (c != null && a - c.b < joinGap) c.b = b
                    else { val t = Turn(p, a, b); res.add(t); cur = t }
                }
                i = j
            }
        }
        res.sortBy { it.a }
        return res
    }

    /** يحدّد التعقيبات القصيرة: قصيرة، وشخص آخر يتكلم قبلها وبعدها (يملك الدور) */
    fun markBackchannels(turns: List<Turn>, bcMax: Double = 1.2, around: Double = 1.5): List<Turn> {
        for (t in turns) {
            if (t.dur > bcMax) continue
            val before = turns.any { o -> o.p != t.p && o.dur > bcMax && o.a <= t.a && o.b >= t.a - around }
            val after = turns.any { o -> o.p != t.p && o.dur > bcMax && o.b >= t.b && o.a <= t.b + around }
            if (before && after) t.backchannel = true
        }
        return turns
    }

    /** من يملك الدور في كل جزء من الزمن (بدون التعقيبات). people فارغة = صمت */
    class Floor(val a: Double, val b: Double, val people: IntArray)

    fun floor(turns: List<Turn>, a: Double, b: Double): List<Floor> {
        val ev = ArrayList<Double>()
        ev.add(a); ev.add(b)
        val real = turns.filter { !it.backchannel && it.b > a && it.a < b }
        for (t in real) { ev.add(t.a.coerceIn(a, b)); ev.add(t.b.coerceIn(a, b)) }
        val pts = ev.distinct().sorted()
        val res = ArrayList<Floor>()
        for (k in 0 until pts.size - 1) {
            val x0 = pts[k]; val x1 = pts[k + 1]
            if (x1 - x0 < 1e-6) continue
            val mid = (x0 + x1) / 2
            val who = real.filter { it.a <= mid && it.b > mid }.map { it.p }.distinct().sorted().toIntArray()
            val last = res.lastOrNull()
            if (last != null && last.people.contentEquals(who)) res[res.size - 1] = Floor(last.a, x1, who)
            else res.add(Floor(x0, x1, who))
        }
        return res
    }

    // ═══════════ طبيعة الحوار ═══════════

    /** منطقة بطبيعة حوار: type = monologue/qa/fast/overlap/normal/silence (+ story/explain/debate/emotional من التحليل الدلالي) */
    class Region(val a: Double, val b: Double, val type: String, val conf: Double, val source: String) {
        fun toJson(): JSONObject = JSONObject().put("a", a).put("b", b).put("t", type).put("c", conf).put("s", source)

        fun label(): String = when (type) {
            "monologue" -> tx("سرد أو شرح متصل", "Continuous narration or explanation")
            "story" -> tx("قصة / سرد", "Story")
            "explain" -> tx("شرح / معلومة", "Explanation")
            "qa" -> tx("سؤال وجواب", "Q&A")
            "fast", "debate" -> tx("نقاش سريع", "Fast debate")
            "overlap" -> tx("تداخل كلام", "Overlapping speech")
            "emotional" -> tx("لحظة انفعالية", "Emotional moment")
            "silence" -> tx("صمت", "Silence")
            else -> tx("حوار عادي", "Normal conversation")
        }

        companion object {
            fun fromJson(o: JSONObject) = Region(o.optDouble("a"), o.optDouble("b"), o.optString("t", "normal"), o.optDouble("c", 0.5), o.optString("s", "local"))
        }
    }

    /**
     * تصنيف محلي من التوقيت فقط (لا يدّعي فهم المعنى): نافذة 30 ثانية كل 10 ثوانٍ.
     * السرد والشرح لا يمكن التمييز بينهما بدون نص، فيظهران معًا كـ tx("سرد أو شرح متصل", "Continuous narration or explanation").
     */
    fun regions(turns: List<Turn>, duration: Double): List<Region> {
        if (duration <= 0) return emptyList()
        val real = turns.filter { !it.backchannel }
        val slot = 10.0
        val nSlots = max(1, Math.ceil(duration / slot).toInt())
        val types = arrayOfNulls<String>(nSlots)
        val confs = DoubleArray(nSlots)
        for (k in 0 until nSlots) {
            val c = (k + 0.5) * slot
            val w0 = max(0.0, c - 15)
            val w1 = min(duration, c + 15)
            val win = w1 - w0
            val inWin = real.filter { it.b > w0 && it.a < w1 }
            var speech = 0.0
            var overlap = 0.0
            val step = 0.25
            var t = w0
            while (t < w1) {
                val cnt = inWin.count { it.a <= t && it.b > t }
                if (cnt >= 1) speech += step
                if (cnt >= 2) overlap += step
                t += step
            }
            // أطول دور متصل لنفس الشخص (التعقيبات لا تقطعه)
            var maxRun = 0.0
            for (x in inWin) maxRun = max(maxRun, min(x.b, w1) - max(x.a, w0))
            val ordered = inWin.sortedBy { it.a }
            var changes = 0
            for (i in 1 until ordered.size) if (ordered[i].p != ordered[i - 1].p) changes++
            val meanTurn = if (ordered.isEmpty()) 0.0 else ordered.sumOf { min(it.b, w1) - max(it.a, w0) } / ordered.size
            var qa = false
            for (i in 0 until ordered.size - 1) {
                val q = ordered[i]; val r = ordered[i + 1]
                if (q.p != r.p && q.dur in 1.5..10.0 && r.dur >= 10.0 && r.a - q.b < 2.0) { qa = true; break }
            }
            val (ty, cf) = when {
                speech / win < 0.3 -> "silence" to 0.8
                speech > 0 && overlap / speech >= 0.15 -> "overlap" to 0.55
                maxRun >= 20.0 && changes <= 2 -> "monologue" to 0.7
                qa -> "qa" to 0.5
                changes / (win / 60.0) >= 8 && meanTurn <= 5.0 -> "fast" to 0.65
                else -> "normal" to 0.6
            }
            types[k] = ty; confs[k] = cf
        }
        val res = ArrayList<Region>()
        var k = 0
        while (k < nSlots) {
            var j = k
            while (j + 1 < nSlots && types[j + 1] == types[k]) j++
            res.add(Region(k * slot, min(duration, (j + 1) * slot), types[k]!!, confs[k], "local"))
            k = j + 1
        }
        return res
    }

    fun regionAt(regs: List<Region>, t: Double): Region? = regs.firstOrNull { t >= it.a && t < it.b }

    // ═══════════ قواعد المونتاج حسب الشدة ═══════════

    class Rules(
        val minShot: Double,
        val pipMax: Double,
        val lead: Double,
        val reactions: Boolean,
        val pingWin: Double,
        val pingCount: Int,
        val silenceToWide: Double,
        val takeFloor: Double,
    )

    fun rules(intensity: String): Rules = when (intensity) {
        "calm" -> Rules(3.0, 0.0, 0.25, false, 8.0, 4, 4.0, 1.6)
        "lively" -> Rules(1.2, 3.0, 0.12, true, 4.5, 3, 2.5, 0.9)
        else -> Rules(2.0, 2.5, 0.18, false, 6.0, 3, 3.0, 1.2)
    }

    private class V(val type: Int, val main: Int, val others: IntArray) {
        fun same(o: V) = type == o.type && main == o.main && others.contentEquals(o.others)
        fun shot(a: Double, b: Double) = PodShot(a, b, type, main, others.copyOf())
    }

    /**
     * اللقطات التلقائية: الانتقال للمتحدث فقط عندما يأخذ الدور فعلًا (لا مع التعقيبات القصيرة)،
     * الثبات أثناء الكلام المتصل (بدون مؤقت)، لقطة مشتركة عند التداخل أو التبادل السريع، والعامة عند الصمت الطويل.
     */
    fun autoShots(
        an: PodAnalysis, people: Int, hasWide: Boolean, usePip: Boolean, st: ProjSettings, a: Double, b: Double,
        regionsOverride: List<Region>? = null,
        hasCam: ((Int) -> Boolean)? = null,
    ): MutableList<PodShot> {
        val R = rules(st.intensity)
        if (st.layout == "grid" && people > 0 && b - a > 0.01) {
            // الكل ظاهر طول الوقت
            val who = (0 until people).filter { hasCam?.invoke(it) != false }.ifEmpty { (0 until people).toList() }
            return mutableListOf(PodShot(a, b, if (who.size > 1) PodShot.MULTI else PodShot.FULL, who[0], if (who.size > 1) who.toIntArray() else IntArray(0)))
        }
        if (st.cutMode == "speaker" && people > 0 && b - a > 0.01) {
            // كاميرا اللي بيحكي بس: قاعدة وحدة، وولا إشي ثاني بيتدخل
            var r = speakerShots(markBackchannels(turns(an, people)), a, b, st.takeSec.toDouble(), 1.5, hasCam)
            if (st.firstHold > 0f) r = firstHold(r, st.firstHold.toDouble())
            return tidy(r)
        }
        val wideOk = hasWide && st.wideAfter >= 0f
        val wideAfter = if (st.wideAfter > 0f) st.wideAfter.toDouble() else R.silenceToWide
        val ts = markBackchannels(turns(an, people))
        val regs = regionsOverride ?: regions(ts, an.duration)
        fun typeAt(t: Double): String = if (st.style != "auto") st.style else (regionAt(regs, t)?.type ?: "normal")
        val fl = floor(ts, a, b)
        val out = ArrayList<PodShot>()
        if (b - a <= 0.01 || people == 0) return out
        val firstSpeaker = fl.firstOrNull { it.people.size == 1 }?.people?.get(0)
        var cur = when {
            firstSpeaker != null -> V(PodShot.FULL, firstSpeaker, IntArray(0))
            wideOk -> V(PodShot.WIDE, 0, IntArray(0))
            else -> V(PodShot.FULL, 0, IntArray(0))
        }
        var curStart = a
        val share = shareOf(st)
        var pk = 0
        fun pipOk(): Boolean = sharePick(pk++, share)

        fun switchTo(v: V, at: Double) {
            if (v.same(cur)) return
            val t = max(at, curStart + 0.05)
            if (t >= b) return
            out.add(cur.shot(curStart, t))
            cur = v
            curStart = t
        }

        for ((idx, f) in fl.withIndex()) {
            val d = f.b - f.a
            val ty = typeAt(f.a)
            when {
                f.people.size == 1 -> {
                    val p = f.people[0]
                    if (cur.type == PodShot.FULL && cur.main == p) continue
                    if (cur.type == PodShot.PIP && cur.others.contentEquals(intArrayOf(p))) {
                        // كان في المربع العائم ويكمل الكلام → يصير بملء الشاشة
                        switchTo(V(PodShot.FULL, p, IntArray(0)), f.a)
                        continue
                    }
                    if (d < R.takeFloor && idx + 1 < fl.size) {
                        // كلام قصير لا يأخذ الدور فعليًا؛ إذا عاد الكلام لنفس الشخص السابق نتجاهله، وإلا نُبقي ونقرر عند التالي
                        val back = fl.drop(idx + 1).firstOrNull { it.people.isNotEmpty() }
                        if (back != null && cur.type == PodShot.FULL && back.people.contentEquals(intArrayOf(cur.main))) {
                            if (usePip && R.pipMax > 0 && d >= 0.8 && ty != "monologue" && ty != "story" && ty != "explain" && f.a > a + 0.5 && pipOk())
                                switchTo(V(PodShot.PIP, cur.main, intArrayOf(p)), f.a)
                            continue
                        }
                    }
                    val lead = if (ty == "qa") R.lead + 0.1 else R.lead
                    if (usePip && R.pipMax > 0 && cur.type == PodShot.FULL && d <= R.pipMax && ty != "monologue" && f.a > a + 0.5 && pipOk()) {
                        switchTo(V(PodShot.PIP, cur.main, intArrayOf(p)), f.a - lead)
                    } else switchTo(V(PodShot.FULL, p, IntArray(0)), f.a - lead)
                }
                f.people.size >= 2 -> {
                    if (usePip && d >= 0.5) {
                        // مقاطعة: اللي كان بيحكي بيضل بملء الشاشة، واللي قاطعه بمربع عائم
                        val main = if (cur.type != PodShot.WIDE && cur.main in f.people) cur.main else f.people[0]
                        switchTo(V(PodShot.PIP, main, f.people.filter { it != main }.toIntArray()), f.a)
                    } else if (d >= 1.0) switchTo(V(PodShot.MULTI, f.people[0], f.people.copyOf()), f.a)
                }
                else -> {
                    if (wideOk && d >= wideAfter) switchTo(V(PodShot.WIDE, 0, IntArray(0)), f.a + 0.6)
                }
            }
        }
        out.add(cur.shot(curStart, b))

        var res = mergePingPong(out, R, people)
        res = enforceMinShot(res, R.minShot)
        if (st.shortMode == "show" || st.shortMode == "min") res = showShort(res, ts, st, usePip, hasCam)
        else if (R.reactions || st.intensity == "lively") res = addReactions(res, ts, regs, st)
        if (st.brk > 0) res = cutaways(res, ts, st.brk.toDouble(), st.brkTo, st.brkLen.toDouble(), people, wideOk, hasCam, st.brkVar)
        res = settle(res, max(R.minShot, 1.5), usePip)
        // الفيديو ما بيبلّش بمربع عائم: أول لقطة بملء الشاشة على اللي بيحكي
        res.firstOrNull()?.let { f0 -> if (f0.type == PodShot.PIP && !f0.locked) { f0.type = PodShot.FULL; f0.others = IntArray(0) } }
        if (st.overlap != "split") res = unsplit(res, ts, st.overlap, st.ovSwitch.toDouble(), st.ovMax.toDouble(), max(R.minShot, 1.5), hasCam)
        if (st.firstHold > 0f) res = firstHold(res, st.firstHold.toDouble())
        return tidy(res)
    }

    /**
     * الردود القصيرة: أي كلام (حتى آه، امم) بيطلع — بمربع عائم أو الكاميرا بتنتقل عليه — وبتضل عليه
     * holdAfter ثانية بعد ما يسكت. إذا رجع حكى والمربع كان رح يختفي أقل من bridge ثانية، بيضل ظاهر (ما بيختفي ويرجع).
     * الأسلوب: pip / switch / mix (منوّع: أغلبه انتقال كامل). بوضع "min" بس اللي حكى أطول من minTalk.
     */
    fun showShort(list: List<PodShot>, ts: List<Turn>, st: ProjSettings, usePip: Boolean, hasCam: ((Int) -> Boolean)? = null): MutableList<PodShot> {
        val minDur = if (st.shortMode == "min") st.minTalk.toDouble() else 0.0
        val hold = st.holdAfter.toDouble().coerceIn(0.0, 10.0)
        val bridge = st.bridge.toDouble().coerceIn(0.0, 15.0)
        class G(val p: Int, val a: Double, var b: Double, val base: PodShot, var talk: Double)
        val groups = ArrayList<G>()
        val sorted = ts.sortedBy { it.a }
        for (t in sorted) {
            if (t.b - t.a < minDur) continue
            if (hasCam?.invoke(t.p) == false) continue
            val mid = (t.a + t.b) / 2
            val sh = list.firstOrNull { mid >= it.start && mid < it.end } ?: continue
            if (sh.locked) continue
            val visible = when (sh.type) {
                PodShot.FULL -> sh.main == t.p
                PodShot.PIP, PodShot.MULTI -> sh.main == t.p || t.p in sh.others
                else -> false
            }
            if (visible) continue
            val last = groups.lastOrNull()
            // نفس الشخص رجع حكى قبل ما المربع/اللقطة تختفي بأكثر من bridge → بيضل ظاهر
            if (last != null && last.p == t.p && last.base.main == sh.main && t.a - (last.b + hold) <= bridge) {
                last.b = max(last.b, t.b); last.talk += t.b - t.a; continue
            }
            groups.add(G(t.p, t.a, t.b, sh, t.b - t.a))
        }
        val inserts = ArrayList<PodShot>()
        val share = shareOf(st)
        var k = 0
        val listStart = list.firstOrNull()?.start ?: 0.0
        for (g in groups) {
            val a = g.a - 0.15
            var b = g.b + hold
            // ما نغطي بداية كلام شخص ثالث
            sorted.firstOrNull { it.p != g.p && it.p != g.base.main && it.a > g.b && it.a < b }?.let { b = it.a - 0.1 }
            // الفيديو ما بيبلّش بمربع عائم
            val canPip = usePip && g.base.type == PodShot.FULL && a > listStart + 0.5
            // النسبة اللي حددتها: مثلًا 10% → وحدة من كل ١٠ ردود بمربع، والباقي انتقال كامل من أول لحظة.
            // القوانين الأعلى بتغلب: الكلام الأطول من ٤ث دايمًا انتقال كامل
            // المربع ما بيضل أكثر من ٨ ثواني متواصلة (لو ضل يرد كل شوي) → انتقال كامل
            val style = if (canPip && g.talk < 4.0 && b - a <= MAX_PIP && sharePick(k++, share)) "pip" else "switch"
            // ردود قصيرة ورا بعض لفترة طويلة (مش كلام متصل) = حوار → الاثنين مع بعض
            val chat = b - a > MAX_PIP && g.talk < (b - a) * 0.4 && g.base.type == PodShot.FULL
            val x = when {
                chat -> PodShot(a, b, PodShot.MULTI, min(g.base.main, g.p), intArrayOf(g.base.main, g.p).sortedArray())
                style == "pip" -> PodShot(a, b, PodShot.PIP, g.base.main, intArrayOf(g.p))
                else -> PodShot(a, b, PodShot.FULL, g.p, IntArray(0))
            }
            x.tag = "short"
            val last = inserts.lastOrNull()
            if (last != null && a < last.end) last.end = a
            if (last != null && last.dur < 0.3) inserts.removeAt(inserts.size - 1)
            if (b - a > 0.3) inserts.add(x)
        }
        if (inserts.isEmpty()) return list.toMutableList()
        return overlay(list, inserts)
    }

    /**
     * ببداية الفيديو بس: أول مرة كل شخص بيطلع بملء الشاشة، الكاميرا بتضل عليه [hold] ثانية على الأقل
     * حتى لو غيره حكى بالنص (عشان الاسم ينكتب وينقرا). اللقطات اللي بعده بتنقص أو بتنشال (إلا المثبتة بإيدك).
     * بعد ما الكل ظهر مرة، الإيقاع العادي.
     */
    fun firstHold(list: List<PodShot>, hold: Double, window: Double = 90.0): MutableList<PodShot> {
        val res = list.sortedBy { it.start }.map { it.copy() }.toMutableList()
        if (res.isEmpty()) return res
        val t0 = res[0].start
        val seen = HashSet<Int>()
        var i = 0
        while (i < res.size) {
            val s = res[i]
            if (s.start > t0 + window) break // بس ببداية الفيديو
            if (s.type != PodShot.FULL || s.main in seen) { i++; continue }
            val p = s.main
            seen.add(p)
            val want = s.start + hold
            if (s.end >= want || s.locked) { i++; continue }
            var end = s.end
            val j = i + 1
            while (end < want && j < res.size) {
                val n = res[j]
                if (n.locked) break
                // لقطة بتخلص قبل الموعد (أو تقريبًا) أو لنفس الشخص: بتنشال وبنكمّل عليه
                if (n.end <= want + 0.8 || (n.type == PodShot.FULL && n.main == p)) { end = max(end, n.end); res.removeAt(j); continue }
                end = want; n.start = want; break
            }
            s.end = end
            i++
        }
        return res.filter { it.end - it.start > 0.05 }.toMutableList()
    }

    /**
     * "ما تقسم الشاشة": كل لقطة "مشتركة" (اثنين أو أكثر بيحكوا مع بعض) بتصير لقطات كاملة على شخص واحد:
     *  stay = بتضل على اللي كان بالشاشة، وبتقلب بس إذا الثاني حكى لحاله [switchAfter] ث وهو ساكت تقريبًا
     *  talker = بتلحق اللي حكيه أكثر بآخر [switchAfter] ث (أسرع)
     *  alternate = متل talker، وكمان كل [maxShot] ث بتبدّل للثاني إذا كان عم يحكي
     * maxShot > 0 بأي وضع: ما بتضل أطول من هيك على شخص إذا الثاني عم يحكي. minHold = أقل مدة لقطة.
     */
    fun unsplit(list: List<PodShot>, ts: List<Turn>, mode: String, switchAfter: Double, maxShot: Double, minHold: Double, hasCam: ((Int) -> Boolean)? = null): MutableList<PodShot> {
        val out = ArrayList<PodShot>()
        val win = switchAfter.coerceIn(0.5, 10.0)
        for (s in list) {
            if (s.type != PodShot.MULTI || s.locked) { out.add(s.copy()); continue }
            val who = (if (s.others.isNotEmpty()) s.others.toList() else listOf(s.main)).distinct().filter { hasCam?.invoke(it) != false }.ifEmpty { listOf(s.main) }
            if (who.size < 2) { out.add(PodShot(s.start, s.end, PodShot.FULL, who[0], IntArray(0)).also { it.tag = s.tag }); continue }
            fun talk(p: Int, a: Double, b: Double): Double = ts.filter { it.p == p && it.b > a && it.a < b }.sumOf { min(it.b, b) - max(it.a, a) }
            // مين بنبلّش عليه: اللي كان بالشاشة قبل (إذا هو من الناس)، وإلا اللي حكى أكثر بأول اللقطة
            val prev = out.lastOrNull()?.takeIf { it.type == PodShot.FULL && it.main in who }?.main
            var cur = prev ?: who.maxByOrNull { talk(it, s.start, min(s.end, s.start + win)) }!!
            var segStart = s.start
            var t = s.start + 0.1
            while (t < s.end - 0.05) {
                if (t - segStart >= minHold && s.end - t >= minHold * 0.6) {
                    val a = t - win
                    val mine = talk(cur, a, t)
                    val other = who.filter { it != cur }.maxByOrNull { talk(it, a, t) }!!
                    val theirs = talk(other, a, t)
                    val sw = when (mode) {
                        "stay" -> theirs >= win * 0.85 && mine < win * 0.3
                        else -> theirs >= win * 0.6 && theirs > mine * 1.3
                    } || (maxShot > 0 && t - segStart >= maxShot && theirs > 0.3 && (mode == "alternate" || theirs >= mine))
                    if (sw) {
                        out.add(PodShot(segStart, t, PodShot.FULL, cur, IntArray(0)))
                        cur = other; segStart = t
                    }
                }
                t += 0.1
            }
            out.add(PodShot(segStart, s.end, PodShot.FULL, cur, IntArray(0)))
        }
        return out
    }

    /**
     * "كاميرا اللي بيحكي": لقطة كاملة على شخص واحد دايمًا.
     *  • الكاميرا بتنتقل لشخص لما يحكي متواصل [take] ثانية أو أكثر — من أول ما بلّش حكي.
     *  • الحكي الأقصر (آه، صح، تعليق صغير) ما بينقل الكاميرا أبدًا.
     *  • إذا حدا قاطع واللي بالشاشة لسا بيحكي: الكاميرا بتضل على اللي بيحكي لحد ما يسكت،
     *    وبعدها بتنتقل للمقاطع بس إذا كمّل [take] ثانية لحاله.
     *  • أقل لقطة [minShot] عشان ما يصير تقليب بشع.
     */
    fun speakerShots(ts: List<Turn>, a: Double, b: Double, take: Double, minShot: Double, hasCam: ((Int) -> Boolean)? = null): MutableList<PodShot> {
        val tl = ts.filter { (hasCam?.invoke(it.p) != false) && it.b > a && it.a < b }.sortedBy { it.a }
        val out = ArrayList<PodShot>()
        if (b - a <= 0.01) return out
        var cur = tl.firstOrNull { it.dur >= take }?.p ?: tl.firstOrNull()?.p ?: 0
        var segStart = a
        for (t in tl) {
            if (t.p == cur || t.dur < take) continue
            // اللي بالشاشة لسا بيحكي لما الثاني بلّش؟ بنستنى لحد ما يسكت
            val curEnd = tl.filter { it.p == cur && it.a <= t.a + 0.05 && it.b > t.a }.maxOfOrNull { it.b }
            val cut = if (curEnd == null) t.a - 0.15 else { if (t.b - curEnd < take) continue; curEnd }
            val at = max(cut, segStart + minShot)
            if (at >= t.b - 0.5 || at >= b - 0.3) continue
            out.add(PodShot(segStart, at, PodShot.FULL, cur, IntArray(0)))
            cur = t.p; segStart = at
        }
        out.add(PodShot(segStart, b, PodShot.FULL, cur, IntArray(0)))
        return out
    }

    /** أطول مدة لمربع عائم متواصل (ثواني) */
    const val MAX_PIP = 8.0

    /** نسبة المربع العائم من الإعدادات (مربع بس = 100%، انتقال بس = 0%) */
    fun shareOf(st: ProjSettings): Double = when (st.shortStyle) { "pip" -> 1.0; "switch" -> 0.0; else -> st.pipShare.toDouble().coerceIn(0.0, 1.0) }

    /** الرد رقم k بمربع؟ موزّع بالتساوي: 30% → ٣ من كل ١٠ بالضبط */
    fun sharePick(k: Int, share: Double): Boolean = kotlin.math.floor((k + 1) * share + 1e-9) > kotlin.math.floor(k * share + 1e-9)

    /** رقم ثابت بين 0 و1 لكل وقت (للتنويع بدون عشوائية بتتغير كل مرة) */
    fun mixPick(t: Double): Double {
        var h = (t * 1000).toLong() * -7046029254386353131L
        h = h xor (h ushr 29)
        return ((h ushr 11) and 0xFFFFF).toDouble() / 0xFFFFF
    }

    /**
     * بعد أي انتقال الكاميرا بتضل فترة دنيا (minHold) عشان ما يصير تقليب بشع:
     * لقطات قصيرة ورا بعض بين شخصين أو أكثر → الكل ظاهر مع بعض (شاشة مقسومة)؛
     * لقطة قصيرة لحالها → بتنمد للحد الأدنى أو بتندمج باللي قبلها.
     */
    fun settle(list: List<PodShot>, minHold: Double, usePip: Boolean): MutableList<PodShot> {
        val src = tidy(list)
        val res = ArrayList<PodShot>()
        fun ppl(x: PodShot?): List<Int> = when {
            x == null -> emptyList()
            x.type == PodShot.FULL -> listOf(x.main)
            x.type == PodShot.PIP -> listOf(x.main) + x.others.toList()
            x.type == PodShot.MULTI -> if (x.others.isNotEmpty()) x.others.toList() else listOf(x.main)
            else -> emptyList()
        }
        // المربع العائم فوق نفس اللقطة مش انتقال كامل: حدّه أقل
        fun short(x: PodShot) = x.dur < (if (x.type == PodShot.PIP) minHold * 0.6 else minHold)
        fun soft(x: PodShot) = !x.locked && x.type != PodShot.WIDE && x.tag != "reaction" && x.tag != "zoom" && x.tag != "break"
        var i = 0
        while (i < src.size) {
            val x = src[i]
            if (!soft(x) || !short(x)) { res.add(x.copy()); i++; continue }
            var j = i
            while (j < src.size && soft(src[j]) && short(src[j])) j++
            val run = src.subList(i, j)
            val start = run.first().start; val end = run.last().end
            val prev = res.lastOrNull(); val next = src.getOrNull(j)
            val inRun = LinkedHashSet<Int>(); for (r in run) inRun.addAll(ppl(r))
            val around = LinkedHashSet<Int>(inRun).apply { addAll(ppl(prev).take(1)); addAll(ppl(next).take(1)) }
            when {
                // تبديل سريع بين أكثر من شخص → الكل مع بعض
                (run.size >= 2 || end - start >= minHold * 0.6) && around.size in 2..4 -> {
                    val who = around.sorted().toIntArray()
                    res.add(PodShot(start, end, PodShot.MULTI, who[0], who).also { it.tag = "settle" })
                }
                // لقطة وحدة قصيرة: بنمدها للحد الأدنى (على حساب اللي بعدها إذا مش مثبّتة)
                next != null && soft(next) && next.dur - (minHold - (end - start)) >= minHold -> {
                    val ext = minHold - (end - start)
                    for (r in run) res.add(r.copy())
                    res.last().end = end + ext
                    src[j].start = end + ext
                }
                prev != null && soft(prev) -> prev.end = end
                else -> for (r in run) res.add(r.copy())
            }
            i = j
        }
        // الشاشة المقسومة القصيرة اللي بين لقطتين لنفس الشخص: بتنمد عشان ما تنطّ
        return tidy(res)
    }

    /**
     * كسر الملل: أثناء كلام متصل لشخص واحد أطول من every ثانية، بنحط لقطة قصيرة (len)
     * على المستمع أو الكاميرا العامة أو تقريب على نفس الشخص، وبنرجع عليه. بتتكرر كل every ثانية.
     */
    fun cutaways(
        list: List<PodShot>, ts: List<Turn>, every: Double, to: String, len: Double, people: Int, hasWide: Boolean,
        hasCam: ((Int) -> Boolean)? = null,
        vary: Boolean = false,
    ): MutableList<PodShot> {
        if (every <= 0) return list.toMutableList()
        val inserts = ArrayList<PodShot>()
        var k = 0
        // ثابت لنفس المشروع (نفس النتيجة كل مرة) بس مش منتظم
        val rnd = java.util.Random(4242L + (every * 10).toLong())
        fun gap(): Double = if (vary) every * (0.75 + rnd.nextDouble() * 0.5) else every
        val reactions = list.filter { it.tag == "reaction" || it.tag == "short" }.map { it.start }
        for (sh in list) {
            if (sh.type != PodShot.FULL || sh.locked || sh.tag == "reaction" || sh.tag == "short") continue
            var t = sh.start + gap()
            while (t + len <= sh.end - 2.0) {
                // المستمع: آخر واحد حكى غير المتكلم (وإلا اللي بعده بالترتيب)
                val listener = ts.lastOrNull { it.p != sh.main && it.a < t }?.p
                    ?: (1 until people).map { (sh.main + it) % people }.firstOrNull { hasCam?.invoke(it) != false }
                val canListen = listener != null && listener != sh.main && hasCam?.invoke(listener) != false
                val opts = when (to) {
                    "listener" -> listOf("l", "z")
                    "wide" -> listOf("w", "z")
                    "zoom" -> listOf("z")
                    else -> listOf(listOf("l", "w", "z"), listOf("w", "z", "l"), listOf("z", "l", "w"))[k % 3]
                }
                // في تبديل طبيعي قريب (رد فعل) → ما في داعي
                if (reactions.any { abs(it - t) < 8.0 }) { t += gap(); continue }
                val pick = opts.firstOrNull { (it == "l" && canListen) || (it == "w" && hasWide) || it == "z" } ?: "z"
                val x = when (pick) {
                    "l" -> PodShot(t, t + len, PodShot.FULL, listener!!, IntArray(0))
                    "w" -> PodShot(t, t + len, PodShot.WIDE, 0, IntArray(0))
                    else -> PodShot(t, t + len, PodShot.FULL, sh.main, IntArray(0))
                }
                x.tag = if (pick == "z") "zoom" else "break"
                inserts.add(x)
                k++
                t += gap() + len
            }
        }
        if (inserts.isEmpty()) return list.toMutableList()
        return overlay(list, inserts)
    }

    /** تبديلات متكررة بين نفس الأشخاص خلال وقت قصير → لقطة مشتركة بدل التنطيط */
    private fun mergePingPong(list: List<PodShot>, R: Rules, people: Int): MutableList<PodShot> {
        val res = ArrayList<PodShot>()
        var i = 0
        while (i < list.size) {
            var j = i
            val who = HashSet<Int>()
            while (j < list.size && list[j].type == PodShot.FULL && list[j].start - list[i].start <= R.pingWin) { who.add(list[j].main); j++ }
            val n = j - i
            if (n >= R.pingCount && who.size >= 2) {
                val m = PodShot(list[i].start, list[j - 1].end, PodShot.MULTI, who.min(), who.sorted().toIntArray())
                res.add(m)
                i = j
            } else {
                res.add(list[i].copy())
                i++
            }
        }
        return res
    }

    /** اللقطات الأقصر من الحد الأدنى تُدمج في السابقة (لا تختفي المثبّتة) */
    fun enforceMinShot(list: List<PodShot>, minShot: Double): MutableList<PodShot> {
        val res = ArrayList<PodShot>()
        for (x in list) {
            val last = res.lastOrNull()
            val minX = if (x.type == PodShot.PIP) min(minShot, 0.6) else minShot
            if (last != null && !x.locked && x.dur < minX && x.tag != "reaction" && x.tag != "short") { last.end = x.end; continue }
            if (last != null && !last.locked && last.dur < minShot && res.size == 1 && last.tag != "reaction") {
                x.start = last.start; res[0] = x.copy(); continue
            }
            res.add(x.copy())
        }
        return res
    }

    /** لقطة رد فعل تلقائية فقط عند دليل صوتي (تعقيب قصير من المستمع أثناء كلام متصل)، وبتباعد ≥ 20 ثانية */
    private fun addReactions(list: MutableList<PodShot>, ts: List<Turn>, regs: List<Region>, st: ProjSettings): MutableList<PodShot> {
        var last = -1e9
        val inserts = ArrayList<PodShot>()
        for (sh in list) {
            if (sh.type != PodShot.FULL || sh.dur < 10.0) continue
            for (t in ts) {
                if (!t.backchannel || t.p == sh.main) continue
                if (t.a < sh.start + 1.5 || t.a > sh.end - 2.5) continue
                if (t.a - last < 20.0) continue
                val r = PodShot(t.a - 0.1, min(sh.end - 1.0, t.a + 1.3), PodShot.FULL, t.p, IntArray(0)).also { it.tag = "reaction" }
                if (r.dur < 0.8) continue
                inserts.add(r)
                last = t.a
            }
        }
        if (inserts.isEmpty()) return list
        return overlay(list, inserts)
    }

    /** يضع لقطات فوق قائمة أساسية (يقص ما يتداخل معها) */
    fun overlay(base: List<PodShot>, tops: List<PodShot>): MutableList<PodShot> {
        var res: MutableList<PodShot> = base.map { it.copy() }.toMutableList()
        for (t in tops.sortedBy { it.start }) {
            val next = ArrayList<PodShot>()
            for (x in res) {
                if (x.end <= t.start || x.start >= t.end) { next.add(x); continue }
                if (x.start < t.start) next.add(x.copy().also { it.end = t.start })
                if (x.end > t.end) next.add(x.copy().also { it.start = t.end })
            }
            next.add(t.copy())
            next.sortBy { it.start }
            res = next
        }
        return res
    }

    /** يدمج المتشابه المتتالي ويحذف الفارغ؛ لا يدمج المثبّتة مع غيرها */
    /**
     * فلوق: لقطات من اختياراتك وقت التصوير (الكاميرا الظاهرة). قبل أول اختيار يظهر الرئيسي.
     * live = (زمن الجلسة، رقم الشخص) مرتبة؛ اختيار شخص بلا كاميرا يُتجاهل.
     */
    fun shotsFromLive(live: List<Pair<Double, Int>>, a: Double, b: Double, hasCam: (Int) -> Boolean, main: Int = 0): MutableList<PodShot> {
        val out = ArrayList<PodShot>()
        var cur = main
        var t0 = a
        for ((t, k) in live.sortedBy { it.first }) {
            if (!hasCam(k)) continue
            if (t <= a) { cur = k; continue }
            if (t >= b) break
            if (k == cur) continue
            if (t - t0 > 0.04) out.add(PodShot(t0, t, PodShot.FULL, cur, IntArray(0)).also { it.tag = "live" })
            cur = k; t0 = t
        }
        if (b - t0 > 0.04) out.add(PodShot(t0, b, PodShot.FULL, cur, IntArray(0)).also { it.tag = "live" })
        return out
    }

    fun tidy(list: List<PodShot>): MutableList<PodShot> {
        val res = ArrayList<PodShot>()
        for (x in list.sortedBy { it.start }) {
            if (x.dur <= 1e-4) continue
            val last = res.lastOrNull()
            if (last != null && last.same(x) && last.locked == x.locked && last.tag == x.tag && abs(last.end - x.start) < 1e-3) { last.end = x.end; continue }
            res.add(x.copy())
        }
        return res
    }

    /**
     * إعادة توليد المونتاج مع حماية المثبّت: اللقطات المثبّتة تبقى كما هي، وإن حُدّد مدى [ra,rb]
     * تبقى كل اللقطات خارجه كما هي ويُعاد توليد ما بداخله فقط.
     */
    fun regenerate(old: List<PodShot>, fresh: List<PodShot>, a: Double, b: Double, ra: Double? = null, rb: Double? = null): MutableList<PodShot> {
        val r0 = ra ?: a
        val r1 = rb ?: b
        val base = ArrayList<PodShot>()
        if (ra != null || rb != null) {
            for (x in old) {
                if (x.end <= r0 || x.start >= r1) base.add(x.copy())
                else {
                    if (x.start < r0) base.add(x.copy().also { it.end = r0 })
                    if (x.end > r1) base.add(x.copy().also { it.start = r1 })
                }
            }
        }
        for (x in fresh) {
            val s0 = max(x.start, r0); val s1 = min(x.end, r1)
            if (s1 - s0 > 1e-4) base.add(x.copy().also { it.start = s0; it.end = s1 })
        }
        base.sortBy { it.start }
        val locked = old.filter { it.locked }
        return tidy(fillHoles(overlay(base, locked), a, b))
    }

    /** يضمن تغطية [a,b] بلا فراغات (يمدّ اللقطة السابقة) */
    fun fillHoles(list: List<PodShot>, a: Double, b: Double): MutableList<PodShot> {
        val s = list.sortedBy { it.start }.map { it.copy() }.toMutableList()
        if (s.isEmpty()) return s
        s[0].start = min(s[0].start, a)
        for (i in 1 until s.size) if (s[i].start > s[i - 1].end) s[i - 1].end = s[i].start
        s[s.size - 1].end = max(s[s.size - 1].end, b)
        return s
    }

    // ═══════════ قص الصمت وربط الزمن ═══════════

    class SilenceLevel(val minGap: Double, val keepBefore: Double, val keepAfter: Double)

    fun silenceLevel(name: String): SilenceLevel? = when (name) {
        "light" -> SilenceLevel(0.8, 0.15, 0.20)
        "balanced" -> SilenceLevel(0.5, 0.12, 0.15)
        "intense" -> SilenceLevel(0.3, 0.08, 0.10)
        else -> null
    }

    /** فترات لا يتكلم فيها أحد (أي صوت على أي مايك يُعتبر كلامًا: الضحك والتنفّس المسموع لا يُقص) */
    fun silences(an: PodAnalysis, a: Double, b: Double, minLen: Double): List<DoubleArray> {
        val fs = an.frameSec
        val res = ArrayList<DoubleArray>()
        val f0 = max(0, (a / fs).toInt())
        val f1 = min(an.frames, Math.ceil(b / fs).toInt())
        var f = f0
        while (f < f1) {
            if (an.active.any { it[f] }) { f++; continue }
            var g = f
            while (g < f1 && an.active.none { it[g] }) g++
            val s0 = max(a, f * fs); val s1 = min(b, g * fs)
            if (s1 - s0 >= minLen) res.add(doubleArrayOf(s0, s1))
            f = g
        }
        return res
    }

    /**
     * قص السكتات بين الكلام مع هامش قبل وبعد (النَّفَس وبدايات/نهايات الكلمات محمية).
     * الوقفة التعبيرية (نفس المتحدث قبلها وبعدها، 0.9–2 ثانية) تُقصَّر ولا تُحذف، إلا في "مكثّف".
     */
    fun silenceCuts(an: PodAnalysis, turns: List<Turn>, level: String, a: Double, b: Double): List<PCut> {
        val L = silenceLevel(level) ?: return emptyList()
        val res = ArrayList<PCut>()
        for (g in silences(an, a, b, L.minGap)) {
            var c0 = g[0] + L.keepAfter
            var c1 = g[1] - L.keepBefore
            if (g[0] <= a + 1e-6) c0 = g[0]
            if (g[1] >= b - 1e-6) c1 = g[1]
            val len = g[1] - g[0]
            if (level != "intense" && len in 0.9..2.0) {
                val before = turns.lastOrNull { !it.backchannel && it.b <= g[0] + 0.05 }
                val after = turns.firstOrNull { !it.backchannel && it.a >= g[1] - 0.05 }
                if (before != null && after != null && before.p == after.p) { c0 = g[0] + 0.3; c1 = g[1] - 0.3 }
            }
            if (c1 - c0 >= 0.05) res.add(PCut(c0, c1, PCut.SILENCE))
        }
        // صوت «مش متأكدين» (ممكن كلام) ما بينقص أبدًا لحاله: بنشيله من القص (مع هامش)
        val keep = an.events.filter { it.verdict == VEvent.UNSURE }.map { doubleArrayOf(it.a - 0.15, it.b + 0.15) }
        if (keep.isEmpty()) return res
        val out = ArrayList<PCut>()
        for (c in res) {
            var parts = listOf(doubleArrayOf(c.a, c.b))
            for (k in keep) parts = parts.flatMap { x -> if (k[1] <= x[0] || k[0] >= x[1]) listOf(x) else listOf(doubleArrayOf(x[0], k[0]), doubleArrayOf(k[1], x[1])).filter { it[1] - it[0] >= 0.05 } }
            for (x in parts) out.add(PCut(x[0], x[1], PCut.SILENCE))
        }
        return out
    }

    /** الفراغات الطويلة في الحلقة (للمراجعة قبل الاعتماد): يُقترح ترك 0.6 ثانية */
    fun longGaps(an: PodAnalysis, minGap: Double, a: Double, b: Double): List<PCut> =
        silences(an, a, b, minGap).mapNotNull { g ->
            val c0 = g[0] + 0.3; val c1 = g[1] - 0.3
            if (c1 - c0 > 0.1) PCut(c0, c1, PCut.GAP) else null
        }

    /** الأجزاء الباقية من [a,b] بعد حذف المقاطع */
    fun keepSegments(a: Double, b: Double, cuts: List<PCut>): List<DoubleArray> {
        val cs = cuts.map { doubleArrayOf(max(a, it.a), min(b, it.b)) }.filter { it[1] - it[0] > 1e-4 }.sortedBy { it[0] }
        val merged = ArrayList<DoubleArray>()
        for (c in cs) {
            val l = merged.lastOrNull()
            if (l != null && c[0] <= l[1] + 1e-6) l[1] = max(l[1], c[1]) else merged.add(c.copyOf())
        }
        val res = ArrayList<DoubleArray>()
        var t = a
        for (c in merged) {
            if (c[0] - t > 0.04) res.add(doubleArrayOf(t, c[0]))
            t = max(t, c[1])
        }
        if (b - t > 0.04) res.add(doubleArrayOf(t, b))
        return res
    }

    /** ربط الزمن بين الجلسة والنسخة المعدّلة بعد القص (الصوت والصورة والكتابة والزوم كلها تستعمله) */
    class TimeMap(val segs: List<DoubleArray>) {
        val outStart = DoubleArray(segs.size)
        val outDur: Double

        init {
            var c = 0.0
            for (i in segs.indices) { outStart[i] = c; c += segs[i][1] - segs[i][0] }
            outDur = c
        }

        fun segAtOut(tOut: Double): Int {
            if (segs.isEmpty()) return -1
            var lo = 0
            var hi = segs.size - 1
            while (lo < hi) { val m = (lo + hi + 1) / 2; if (outStart[m] <= tOut) lo = m else hi = m - 1 }
            return lo
        }

        fun toSession(tOut: Double): Double { val i = segAtOut(tOut); return if (i < 0) tOut else segs[i][0] + (tOut - outStart[i]) }

        /** null إذا كانت اللحظة داخل مقطع محذوف */
        fun toOut(t: Double): Double? {
            for (i in segs.indices) if (t >= segs[i][0] - 1e-9 && t <= segs[i][1] + 1e-9) return outStart[i] + (t - segs[i][0])
            return null
        }

        /** أقرب زمن على النسخة المعدّلة (للحظة محذوفة: بداية الجزء التالي) */
        fun toOutNearest(t: Double): Double {
            toOut(t)?.let { return it }
            for (i in segs.indices) if (segs[i][0] > t) return outStart[i]
            return outDur
        }
    }

    // ═══════════ الزوم ═══════════

    /**
     * زوم تلقائي محافظ مرتبط بالمونتاج:
     * - جمل محورية (من التحليل الدلالي إن وُجد): تقريب خفيف ثم عودة.
     * - تصاعد سرد طويل لنفس المتحدث: تقريب تدريجي بطيء (ليس في "هادئ" إلا بجمل محورية).
     * - "حيوي" فقط وبدون نص: الجملة التي تلي وقفة طويلة داخل كلام متصل (تقدير).
     * لا زوم في اللقطات المشتركة/العامة، وتباعد ≥ 6 ثوانٍ، ولا تناوب.
     */
    fun autoZooms(shots: List<PodShot>, ts: List<Turn>, regs: List<Region>, st: ProjSettings, keys: List<DoubleArray>?, a: Double, b: Double): List<PZoom> {
        if (st.zoomMode != "auto") return emptyList()
        val zmax = st.zoomMax.coerceIn(1.0f, 1.5f)
        val cand = ArrayList<PZoom>()
        val single = shots.filter { it.type == PodShot.FULL || it.type == PodShot.PIP }
        fun inSingle(x0: Double, x1: Double): PodShot? = single.firstOrNull { it.start <= x0 + 0.05 && it.end >= x1 - 0.05 }

        if (keys != null) for (k in keys) {
            val sh = inSingle(k[0], k[1]) ?: continue
            if (k[0] < a || k[1] > b) continue
            val s = if (st.intensity == "calm") 1f + (zmax - 1f) * 0.5f else zmax
            cand.add(PZoom(max(sh.start, k[0] - 0.15), min(sh.end, k[1] + 0.35), s, st.zoomSmooth, false, true, tx("جملة محورية", "Key sentence")))
        }
        if (st.intensity != "calm") {
            for (sh in single) {
                if (sh.dur < 12.0 || sh.end <= a || sh.start >= b) continue
                val r = regionAt(regs, (sh.start + sh.end) / 2)?.type
                if (r != "monologue" && r != "story" && r != "explain") continue
                val s0 = max(a, sh.start + 1.0)
                val s1 = min(b, min(sh.end, s0 + 20.0))
                if (s1 - s0 >= 8.0) cand.add(PZoom(s0, s1, 1f + (zmax - 1f) * 0.6f, true, false, true, tx("تصاعد السرد", "Narrative build-up")))
            }
        }
        if (st.intensity == "lively" && keys == null) {
            val real = ts.filter { !it.backchannel }
            for (i in 1 until real.size) {
                val p = real[i - 1]; val q = real[i]
                if (p.p != q.p || q.a - p.b < 1.0 || q.a < a || q.a > b - 2.0) continue
                val sh = inSingle(q.a, min(q.b, q.a + 2.0)) ?: continue
                val e = min(q.b, q.a + 5.0)
                if (e - q.a < 2.0) continue
                cand.add(PZoom(q.a - 0.1, min(sh.end, e), 1f + (zmax - 1f) * 0.7f, st.zoomSmooth, false, true, tx("بعد وقفة (تقدير)", "After a pause (estimate)")))
            }
        }
        cand.sortBy { it.a }
        val res = ArrayList<PZoom>()
        for (z in cand) {
            val last = res.lastOrNull()
            if (last != null && z.a < last.b + 6.0) continue
            res.add(z)
        }
        return res
    }

    /** يحافظ على حركات الزوم المثبّتة ويحذف التلقائي المتداخل معها */
    fun mergeZooms(old: List<PZoom>, fresh: List<PZoom>): MutableList<PZoom> {
        val keep = old.filter { it.locked || !it.auto }.map { it.copy() }
        val res = ArrayList<PZoom>(keep)
        for (z in fresh) if (keep.none { z.a < it.b && z.b > it.a }) res.add(z)
        res.sortBy { it.a }
        return res
    }

    /** قيمة الزوم في لحظة (بزمن الجلسة): التدريجي يتصاعد على طول الحركة، والعادي يدخل ويخرج بنعومة أو مباشرة */
    fun zoomAt(zs: List<PZoom>, t: Double): Float {
        for (z in zs) {
            if (t < z.a || t >= z.b) continue
            val d = z.b - z.a
            if (!z.smooth) return z.scale
            if (z.reason == tx("تصاعد السرد", "Narrative build-up") || d >= 8.0) {
                val k = ((t - z.a) / d).coerceIn(0.0, 1.0)
                val e = k * k * (3 - 2 * k)
                return (1.0 + (z.scale - 1.0) * e).toFloat()
            }
            val r = min(0.5, d / 3)
            val k = min((t - z.a) / r, (z.b - t) / r).coerceIn(0.0, 1.0)
            val e = k * k * (3 - 2 * k)
            return (1.0 + (z.scale - 1.0) * e).toFloat()
        }
        return 1f
    }

    /**
     * أقصى زوم مسموح لكاميرا حسب دقتها: srcCrop = عرض الجزء المقصوص من المصدر بالبكسل، outW = عرض الإخراج.
     * نسمح بتكبير البكسل حتى 2.2× كحد أعلى حتى لا تتدهور الصورة.
     */
    fun zoomLimit(srcCropPx: Double, outPx: Double, wanted: Float): Float {
        if (srcCropPx <= 0 || outPx <= 0) return 1f
        val lim = (2.2 * srcCropPx / outPx).toFloat()
        return min(wanted, max(1f, lim))
    }

    // ═══════════ اقتراح الريلز ═══════════

    class Suggest(val a: Double, val b: Double, val score: Double, val title: String, val reason: String, val type: String) {
        fun toJson(): JSONObject = JSONObject().put("a", a).put("b", b).put("sc", score).put("ti", title).put("re", reason).put("ty", type)

        companion object {
            fun fromJson(o: JSONObject) = Suggest(o.optDouble("a"), o.optDouble("b"), o.optDouble("sc"), o.optString("ti"), o.optString("re"), o.optString("ty"))
        }
    }

    private fun pauseBefore(ts: List<Turn>, t: Double): Double {
        val prev = ts.filter { !it.backchannel && it.b <= t + 0.05 }.maxOfOrNull { it.b } ?: return 99.0
        return t - prev
    }

    private fun pauseAfter(ts: List<Turn>, t: Double): Double {
        val next = ts.filter { !it.backchannel && it.a >= t - 0.05 }.minOfOrNull { it.a } ?: return 99.0
        return next - t
    }

    /**
     * اقتراحات من بنية الحوار (بدون نص): سؤال قصير وجوابه، كلام متصل يبدأ وينتهي عند وقفة طبيعية، أو تبادل سريع.
     * التقييم: اكتمال الفكرة (حدود عند وقفات)، قرب المدة من الهدف، كثافة الكلام، علامات التسجيل.
     * لا يعتمد على علوّ الصوت.
     */
    fun suggestReels(an: PodAnalysis, names: List<String>, markers: List<PMarker>, target: Double, count: Int): List<Suggest> {
        val ts = markBackchannels(turns(an, names.size))
        val real = ts.filter { !it.backchannel }
        val minLen = max(8.0, target * 0.4)
        val maxLen = target * 2.2
        fun nm(p: Int) = names.getOrNull(p) ?: tx("؟", "?")
        val cand = ArrayList<Suggest>()

        // سؤال وجواب
        for (i in 0 until real.size - 1) {
            val q = real[i]
            if (q.dur !in 1.5..12.0) continue
            val first = real[i + 1]
            if (first.p == q.p || first.a - q.b > 2.0) continue
            var end = first.b
            var k = i + 2
            while (k < real.size) {
                val r = real[k]
                if (r.p == q.p || r.a - end > 1.5) break
                end = r.b; k++
            }
            val a0 = max(0.0, q.a - 0.2); val b0 = min(an.duration, end + 0.3)
            if (b0 - a0 in minLen..maxLen) cand.add(Suggest(a0, b0, 0.0, tx("${nm(first.p)} يجيب", "${nm(first.p)} answers"), tx("سؤال قصير من ${nm(q.p)} يتبعه جواب متصل من ${nm(first.p)} (${(end - first.a).toInt()} ث) — تقدير من توقيت الكلام", "A short question from ${nm(q.p)} followed by a continuous answer from ${nm(first.p)} (${(end - first.a).toInt()} s) — estimated from speech timing"), "qa"))
        }
        // كلام متصل لشخص واحد
        var i = 0
        while (i < real.size) {
            val p = real[i].p
            var j = i
            var end = real[i].b
            while (j + 1 < real.size && real[j + 1].p == p && real[j + 1].a - end < 1.2) { j++; end = real[j].b }
            val a0 = real[i].a
            if (end - a0 >= minLen) {
                // طويل جدًا: نقطع عند أقرب وقفة لهدف المدة
                var b0 = end
                if (end - a0 > maxLen) {
                    val within = real.subList(i, j + 1).filter { it.b - a0 in minLen..maxLen }
                    b0 = within.minByOrNull { abs((it.b - a0) - target) }?.b ?: (a0 + target)
                }
                cand.add(Suggest(max(0.0, a0 - 0.2), min(an.duration, b0 + 0.3), 0.0, tx("من كلام ${nm(p)}", "From ${nm(p)}"), tx("كلام متصل لـ ${nm(p)} يبدأ وينتهي عند وقفة طبيعية — قد يكون قصة أو شرحًا (النص غير متوفر)", "Continuous speech by ${nm(p)} that starts and ends at a natural pause — may be a story or an explanation (no transcript)"), "story"))
            }
            i = j + 1
        }
        // تبادل سريع
        for (r in regions(ts, an.duration)) {
            if (r.type != "fast" && r.type != "overlap") continue
            var a0 = r.a; var b0 = min(r.b, r.a + target)
            a0 = real.filter { it.a >= a0 - 3 && it.a <= a0 + 3 }.minByOrNull { abs(it.a - a0) }?.a ?: a0
            b0 = real.filter { it.b >= b0 - 4 && it.b <= b0 + 4 }.minByOrNull { abs(it.b - b0) }?.b ?: b0
            if (b0 - a0 in minLen..maxLen) {
                val who = real.filter { it.b > a0 && it.a < b0 }.map { it.p }.distinct().joinToString(tx(" و", " and ")) { nm(it) }
                cand.add(Suggest(a0, b0, 0.0, tx("نقاش بين $who", "Discussion between $who"), tx("تبادل كلام سريع بين $who — قد يكون حماسيًا أو عاديًا (لا يُستدل منه على المعنى)", "Fast exchange between $who — could be heated or ordinary (meaning is not inferred)"), "discussion"))
            }
        }

        // التقييم
        val scored = cand.map { c ->
            val len = c.b - c.a
            val complete = (if (pauseBefore(real, c.a + 0.2) >= 0.6) 0.5 else 0.0) + (if (pauseAfter(real, c.b - 0.3) >= 0.6) 0.5 else 0.0)
            val fit = max(0.0, 1.0 - abs(len - target) / target)
            var speech = 0.0
            for (t in real) speech += max(0.0, min(t.b, c.b) - max(t.a, c.a))
            val dens = min(1.0, speech / len)
            val star = markers.count { it.kind == "star" && it.t in c.a..c.b }
            val oops = markers.count { it.kind == "oops" && it.t in c.a..c.b }
            val score = complete * 0.35 + fit * 0.25 + dens * 0.2 + min(1, star) * 0.2 - oops * 0.5
            val extra = (if (star > 0) tx(" • فيه علامة ⭐", " • has a ⭐ marker") else "") + (if (oops > 0) tx(" • تنبيه: فيه علامة غلطة", " • warning: has a mistake marker") else "")
            Suggest(c.a, c.b, score, c.title, c.reason + extra, c.type)
        }.sortedByDescending { it.score }
        val res = ArrayList<Suggest>()
        for (s in scored) {
            if (res.any { s.a < it.b && s.b > it.a }) continue
            res.add(s)
            if (res.size >= count) break
        }
        return res.sortedBy { it.a }
    }

    // ═══════════ الكتابة على الفيديو ═══════════

    class CapChunk(val a: Double, val b: Double, val words: List<String>, val emph: Set<Int>, val wt: DoubleArray = DoubleArray(0)) {
        /** رقم الكلمة اللي بتنحكى بلحظة t */
        fun wordAt(t: Double): Int {
            if (words.isEmpty()) return 0
            if (wt.size == words.size) { var i = 0; while (i < wt.size - 1 && t >= wt[i + 1]) i++; return i }
            return (((t - a) / max(0.01, b - a)) * words.size).toInt().coerceIn(0, words.size - 1)
        }
        val text: String get() = words.joinToString(" ")
    }

    private val punct = Regex("[،,.؟?!؛;:]$")

    /**
     * يقسّم الجمل إلى عبارات قصيرة (سطر أو سطران ≤ maxChars) عند علامات الترقيم أولًا.
     * التوقيت من توقيت الكلمات إن وُجد، وإلا يُوزّع حسب طول النص. emphKeys: "رقم_الجملة:رقم_الكلمة".
     */
    fun captionChunks(segs: List<TSeg>, emphKeys: Set<String>, maxChars: Int = 42, maxWords: Int = 8): List<CapChunk> {
        val res = ArrayList<CapChunk>()
        for (s in segs) {
            val words = if (s.words.isNotEmpty()) s.words.map { it.w.trim() }.filter { it.isNotEmpty() } else s.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            val timed = s.words.isNotEmpty() && s.words.size == words.size
            val totalChars = words.sumOf { it.length + 1 }.toDouble()
            var start = 0
            var acc = 0
            val groups = ArrayList<IntRange>()
            for ((k, w) in words.withIndex()) {
                acc += w.length + 1
                val n = k - start + 1
                val brk = punct.containsMatchIn(w) && n >= 2
                if (acc >= maxChars || n >= maxWords || brk || k == words.size - 1) {
                    groups.add(start..k); start = k + 1; acc = 0
                }
            }
            var charPos = 0
            for (g in groups) {
                val ws = words.subList(g.first, g.last + 1)
                val chars = ws.sumOf { it.length + 1 }
                val (a0, b0) = if (timed) s.words[g.first].a to s.words[g.last].b
                else (s.a + (s.b - s.a) * charPos / totalChars) to (s.a + (s.b - s.a) * (charPos + chars) / totalChars)
                charPos += chars
                val em = HashSet<Int>()
                for (k in g) if (emphKeys.contains("${s.i}:$k")) em.add(k - g.first)
                val wt = if (timed) DoubleArray(ws.size) { s.words[g.first + it].a } else {
                    // بدون توقيت كلمات: حسب طول كل كلمة
                    var acc2 = 0.0
                    DoubleArray(ws.size) { i -> val v = a0 + (b0 - a0) * acc2 / chars; acc2 += ws[i].length + 1; v }
                }
                res.add(CapChunk(a0, b0, ws, em, wt))
            }
        }
        return res
    }

    // ═══════════ ملفات الترجمة SRT ═══════════

    private fun ts(t: Double): String {
        val ms = (max(0.0, t) * 1000).toLong()
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000)
    }

    /** ملف SRT على زمن النسخة المعدّلة (الجمل المحذوفة لا تظهر، والتوقيت بعد القص صحيح) */
    fun srt(entries: List<Triple<Double, Double, String>>, map: TimeMap): String {
        val sb = StringBuilder()
        var n = 1
        for ((a, b, text) in entries) {
            if (text.isBlank()) continue
            val oa = map.toOutNearest(a)
            val ob = map.toOutNearest(b)
            if (ob - oa < 0.2) continue
            sb.append(n++).append('\n').append(ts(oa)).append(" --> ").append(ts(ob)).append('\n').append(text.trim()).append("\n\n")
        }
        return sb.toString()
    }
}
