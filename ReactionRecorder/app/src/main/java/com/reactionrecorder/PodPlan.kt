package com.reactionrecorder

import kotlin.math.max
import kotlin.math.min

/**
 * منهج المونتاج — محرّك واحد، قوانين ثابتة، وخيارات قليلة (كلها بالقالب ← الإيقاع):
 *
 * ١. كل لحظة (كل عُشر ثانية) بنحسب لكل شخص: قديش حكى بآخر W ثواني (W ≈ ١٫٦ × سرعة التقليب).
 *    • واحد حكيه أكثر من النص والباقي قليل → «الدور معه»: الكاميرا عليه.
 *    • اثنين على كاميرات مختلفة بيحكوا بنفس اللحظة ١٫٥ث من آخر ٣ث → «بيحكوا مع بعض» (نقاش، مقاطعة متبادلة).
 *      أو «تبادل سريع»: الدور تنقّل بينهم ٣ مرات بآخر ٤ ثواني.
 *      (ضحكة مع بعض، أو تسليم دور فيه تداخل نص ثانية: مش «مع بعض».)
 *    • ولا حدا ماسك الدور بوضوح (وقفة بنص الكلام، «آه» من الثاني) → الكاميرا بتضل مكانها.
 *    • ولا حدا (أقل من ١٢٪) → «سكوت»: الكاميرا بتضل مكانها (أو العامة إذا السكوت طويل وفي كاميرا عامة).
 * ٢. التعليقات القصيرة (آه، صح، ضحكة) ما بتوصل للنسبة، فما بتقلب الكاميرا أبدًا.
 *    (اختياري: بتطلع بمربع عائم صغير فوق اللي ماسك الدور.)
 * ٣. القطع بيصير على بداية كلام الشخص الجديد (مش بعد ما نتأكد)، وأقل لقطة حسب السرعة.
 * ٤. الكاميرا هي الوحدة: شخصين على نفس الكاميرا → تبادل الدور بينهم ما بيقلب.
 * ٥. لما يحكوا مع بعض: يا بتضل على اللي كان، يا الاثنين مع بعض (تقسيم — بس للكاميرتين اللي بيحكوا)،
 *    يا الكاميرا العامة إذا موجودة.
 * ٦. كسر الملل (اختياري): بالكلام الطويل لقطة ٢٫٥ ث على المستمع كل N ثانية.
 * ٧. أول ظهور لكل كاميرا ببداية الفيديو بيطوّل (عشان الاسم ينقرا).
 */
object PodPlan {
    class Cfg(
        /** calm / balanced / lively */
        val pace: String = "balanced",
        /** stay = بتضل على اللي كان / switch = بتنتقل على اللي دخل / both = الاثنين مع بعض / wide = العامة */
        val together: String = "stay",
        /** بعد قديش من حكيهم مع بعض بيصير هاد (ثواني) */
        val togetherAfter: Double = 0.0,
        /** التعليقات القصيرة بمربع عائم */
        val commentBox: Boolean = false,
        /** كسر الملل كل كم ثانية (0 = مطفي) */
        val breakEvery: Int = 0,
        val breakLen: Double = 2.5,
        val firstHold: Double = 0.0,
    ) {
        val take: Double get() = when (pace) { "calm" -> 1.8; "lively" -> 0.8; else -> 1.2 }
        val minShot: Double get() = when (pace) { "calm" -> 2.5; "lively" -> 1.2; else -> 1.8 }
        val window: Double get() = (take * 1.6).coerceIn(1.2, 4.0)

        companion object {
            fun of(st: ProjSettings) = Cfg(
                pace = st.intensity,
                together = when (st.overlap) { "both", "split" -> "both"; "wide" -> "wide"; "switch" -> "switch"; else -> "stay" },
                togetherAfter = st.ovAfter.toDouble().coerceIn(0.0, 10.0),
                commentBox = st.pip,
                breakEvery = st.brk,
                breakLen = st.brkLen.toDouble(),
                firstHold = st.firstHold.toDouble(),
            )
        }
    }

    /** وصف شو بيعمل كل خيار (للشاشات): قانون واحد لكل خيار */
    private const val DT = 0.1

    /**
     * @param camOf لكل شخص: رقم كاميرته (أشخاص على نفس الجهاز = نفس الرقم)، أو -1 إذا ما إله كاميرا
     * @param hasWide في كاميرا عامة؟
     */
    fun plan(an: PodAnalysis, camOf: IntArray, hasWide: Boolean, cfg: Cfg, a: Double, b: Double): MutableList<PodShot> {
        val n0 = min(camOf.size, an.active.size)
        val cams = (0 until n0).map { camOf[it] }.filter { it >= 0 }.distinct().sorted()
        if (cams.size == (0 until n0).count { camOf[it] >= 0 }) return core(an, camOf, hasWide, cfg, a, b)
        // ── أكثر من شخص على نفس الكاميرا: الكاميرا هي «المتكلم». حكي أي واحد فيهم = حكي كاميرته
        //    (هيك اثنين بيتبادلوا على نفس الكاميرا بيحسبوا مع بعض، وما بتضيع عليهم الكاميرا)
        val len = an.active.maxOf { it.size }
        val rows = Array(cams.size) { BooleanArray(len) }
        for (p in 0 until n0) { val c = cams.indexOf(camOf[p]); if (c < 0) continue; val r = an.active[p]; for (i in r.indices) if (r[i]) rows[c][i] = true }
        val camAn = PodAnalysis(an.frameSec, rows, FloatArray(cams.size) { -20f }, an.duration)
        val res = core(camAn, IntArray(cams.size) { it }, hasWide, cfg, a, b)
        // نرجّع لكل لقطة شخص من الكاميرا (اللي حكى أكثر فيها) — الصورة نفسها، بس عشان الاسم والقص
        fun rep(c: Int, s0: Double, s1: Double): Int {
            val ps = (0 until n0).filter { camOf[it] == cams[c] }
            val i0 = (s0 / an.frameSec).toInt(); val i1 = (s1 / an.frameSec).toInt()
            return ps.maxByOrNull { p -> val r = an.active[p]; var k = 0; for (i in max(0, i0) until min(r.size, i1)) if (r[i]) k++; k } ?: ps.first()
        }
        for (sh in res) {
            if (sh.type == PodShot.WIDE) continue
            val m = rep(sh.main, sh.start, sh.end)
            sh.others = IntArray(sh.others.size) { rep(sh.others[it], sh.start, sh.end) }
            sh.main = m
        }
        return res
    }

    private fun core(an: PodAnalysis, camOf: IntArray, hasWide: Boolean, cfg: Cfg, a: Double, b: Double): MutableList<PodShot> {
        val n = min(camOf.size, an.active.size)
        val out = ArrayList<PodShot>()
        if (b - a <= 0.05 || n == 0) return out
        val fs = an.frameSec
        // مجموع تراكمي لنشاط كل شخص (عشان «قديش حكى بآخر W ثانية» يكون سريع)
        val pre = Array(n) { p ->
            val row = an.active[p]
            IntArray(row.size + 1).also { c -> for (i in row.indices) c[i + 1] = c[i] + if (row[i]) 1 else 0 }
        }
        fun talk(p: Int, t0: Double, t1: Double): Double {
            val c = pre[p]
            val i0 = (t0 / fs).toInt().coerceIn(0, c.size - 1); val i1 = (t1 / fs).toInt().coerceIn(0, c.size - 1)
            return if (i1 <= i0) 0.0 else (c[i1] - c[i0]) * fs
        }
        fun activeAt(p: Int, t: Double): Boolean { val r = an.active[p]; val i = (t / fs).toInt(); return i in r.indices && r[i] }
        /** بداية كلام p المتصل لحد t (فراغات أقل من ٠٫٤ث ما بتقطع) */
        fun turnStart(p: Int, t: Double): Double {
            var x = t; var gap = 0.0
            while (x > a) {
                if (activeAt(p, x)) gap = 0.0 else { gap += fs; if (gap > 0.4) return x + gap }
                x -= fs
            }
            return a
        }
        /** قديش الاثنين حكوا بنفس اللحظة بين t0 وt1 (ثواني) */
        fun both(p: Int, q: Int, t0: Double, t1: Double): Double {
            val r1 = an.active[p]; val r2 = an.active[q]
            var c = 0
            for (i in max(0, (t0 / fs).toInt()) until min(min(r1.size, r2.size), (t1 / fs).toInt())) if (r1[i] && r2[i]) c++
            return c * fs
        }
        /** كم مرة الدور انتقل بين p وq (لما واحد بس بيحكي) بين t0 وt1 */
        fun pingPong(p: Int, q: Int, t0: Double, t1: Double): Int {
            if (camOf[p] == camOf[q]) return 0
            val r1 = an.active[p]; val r2 = an.active[q]
            var last = -1; var k = 0
            for (i in max(0, (t0 / fs).toInt()) until min(min(r1.size, r2.size), (t1 / fs).toInt())) {
                val w = if (r1[i] && !r2[i]) 0 else if (r2[i] && !r1[i]) 1 else continue
                if (last >= 0 && w != last) k++
                last = w
            }
            return k
        }
        /** آخر لحظة حكى فيها حدا مش على هالكاميرا (لحد t) */
        fun lastOther(cam: Int, t: Double): Double {
            var x = t
            while (x > a) { for (p in 0 until n) if (camOf[p] >= 0 && camOf[p] != cam && activeAt(p, x)) return x; x -= fs }
            return a
        }
        val withCam = (0 until n).filter { camOf[it] >= 0 }
        if (withCam.isEmpty()) return out

        // ── شو المفروض يطلع بكل لحظة
        class View(val type: Int, val main: Int, val others: IntArray, var why: String = "floor") {
            fun same(o: View) = type == o.type && (if (type == PodShot.FULL) camOf[main] == camOf[o.main] else if (type == PodShot.MULTI) others.map { camOf[it] }.toSet() == o.others.map { camOf[it] }.toSet() else true)
        }
        val W = cfg.window
        var cur: View? = null
        var curStart = a
        var cand: View? = null
        var candSince = a
        var silentSince = -1.0
        var togSince = -1.0
        var lastTogWant = false
        var togFrom = -1
        var t = a
        while (t < b) {
            val shares = withCam.associateWith { talk(it, max(a, t - W), t) / max(DT, min(W, t - a + DT)) }
            val ranked = withCam.sortedByDescending { shares[it] ?: 0.0 }
            val d = ranked[0]; val sd = shares[d] ?: 0.0
            // التاني الأقوى على كاميرا ثانية
            val q = ranked.drop(1).firstOrNull { camOf[it] != camOf[d] }
            val sq = q?.let { shares[it] } ?: 0.0
            var want: View? = null
            if (sd < 0.12) {
                if (silentSince < 0) silentSince = t
                if (hasWide && t - silentSince > 4.0) want = View(PodShot.WIDE, 0, IntArray(0), "silence-wide")
            } else {
                silentSince = -1.0
                // «مع بعض» = الاثنين بيحكوا بنفس اللحظة ثانية ونص على الأقل من آخر ٣ ثواني (ضحكة أو تسليم دور ما بيكفّوا)
                //  أو «تبادل سريع»: بآخر ٤ ثواني الدور تنقّل بينهم ٣ مرات أو أكثر (جمل قصيرة ورا بعض)
                val together = q != null && ((sq >= 0.35 && sd >= 0.35 && both(d, q, t - 3.0, t) >= 1.5) || (pingPong(d, q, t - 4.0, t) >= 3 && talk(q, t - 4.0, t) >= 1.2 && talk(d, t - 4.0, t) >= 1.2))
                if (together) { if (togSince < 0) { togSince = t - 1.5; togFrom = cur?.takeIf { it.type == PodShot.FULL }?.let { camOf[it.main] } ?: -1 } } else togSince = -1.0
                lastTogWant = together
                // لسا ما مرّ الوقت اللي اخترته → بتضل على اللي كان
                val togMode = if (together && t - togSince >= cfg.togetherAfter) cfg.together else "stay"
                want = if (together && q != null) {
                    when {
                        // بتنتقل على اللي دخل (مش اللي كانت عليه)
                        //  (مرة وحدة لكل نقاش: اللي كانت عليه لما بلّشوا ← الثاني، وبتضل عليه)
                        togMode == "switch" -> when (togFrom) {
                            camOf[d] -> View(PodShot.FULL, q, IntArray(0), "tog-switch")
                            camOf[q] -> View(PodShot.FULL, d, IntArray(0), "tog-switch")
                            else -> cur ?: View(PodShot.FULL, d, IntArray(0))
                        }
                        togMode == "stay" -> cur?.takeIf { it.type == PodShot.FULL && (camOf[it.main] == camOf[d] || camOf[it.main] == camOf[q]) } ?: View(PodShot.FULL, d, IntArray(0), "tog-stay")
                        togMode == "wide" && hasWide -> View(PodShot.WIDE, 0, IntArray(0), "tog-wide")
                        togMode == "both" || togMode == "wide" -> View(PodShot.MULTI, min(d, q), intArrayOf(d, q).sortedBy { camOf[it] }.toIntArray(), "tog-both")
                        // بتضل على اللي كان (إذا اللي كان واحد منهم)، وإلا على الأقوى
                        else -> cur?.takeIf { it.type == PodShot.FULL && (camOf[it.main] == camOf[d] || camOf[it.main] == camOf[q]) } ?: View(PodShot.FULL, d, IntArray(0))
                    }
                } else if (sd >= 0.5 || cur == null) View(PodShot.FULL, d, IntArray(0))
                // ما حدا ماسك الدور بوضوح (وقفة، «آه»، كلام متقطع) → بتضل مكانها
                else null
            }
            if (cur == null) {
                cur = want ?: View(PodShot.FULL, d, IntArray(0)); cur.why = "start"; curStart = a
            } else if (want != null && !want.same(cur)) {
                if (cand == null || !cand.same(want)) { cand = want; candSince = t }
                // تأكدنا (ربع W) ومرّت أقل لقطة → قطع
                if (t - candSince >= W * 0.25 && t - curStart >= cfg.minShot) {
                    // القطع على بداية كلامه — بس مش أبعد من نافذة وحدة قبل ما بيّن (لو كان بيحكي من زمان مع غيره)
                    // وإذا كانوا مقسومين: القطع لما الثاني سكت
                    var cut = if (lastTogWant && cfg.togetherAfter > 0) max(candSince - W * 0.25, togSince + cfg.togetherAfter)
                        else if (want.type == PodShot.FULL) maxOf(turnStart(want.main, t) - 0.15, (if (cur.type == PodShot.MULTI) lastOther(camOf[want.main], t) + 0.1 else a), candSince - W) else candSince - W * 0.3
                    cut = cut.coerceIn(curStart + cfg.minShot, t)
                    val c0 = cur
                    out.add(PodShot(curStart, cut, c0.type, c0.main, c0.others.copyOf()).also { it.why = c0.why })
                    cur = want; curStart = cut; cand = null
                }
            } else cand = null
            t += DT
        }
        val cl = cur!!
        out.add(PodShot(curStart, b, cl.type, cl.main, cl.others.copyOf()).also { it.why = cl.why })
        var res: MutableList<PodShot> = merge(out)

        // ── التعليقات القصيرة بمربع عائم (اختياري)
        if (cfg.commentBox && withCam.map { camOf[it] }.distinct().size >= 2) {
            val tops = ArrayList<PodShot>()
            val ts = PodEngine.turns(an, n)
            for (tn in ts) {
                if (camOf.getOrElse(tn.p) { -1 } < 0 || tn.dur < 0.35 || tn.dur >= cfg.take) continue
                if (tn.a < a + 3.0 || tn.b > b) continue
                val host = res.firstOrNull { (tn.a + tn.b) / 2 >= it.start && (tn.a + tn.b) / 2 < it.end } ?: continue
                if (host.type != PodShot.FULL || camOf[host.main] == camOf[tn.p]) continue
                val s0 = max(host.start, tn.a - 0.1); val s1 = min(host.end, tn.b + 1.0)
                if (s1 - s0 < 0.8) continue
                if (tops.lastOrNull()?.let { s0 < it.end + 1.0 } == true) continue
                tops.add(PodShot(s0, s1, PodShot.PIP, host.main, intArrayOf(tn.p)).also { it.tag = "short" })
            }
            if (tops.isNotEmpty()) res = PodEngine.overlay(res, tops)
        }
        // ── كسر الملل (اختياري): لقطة على المستمع بالكلام الطويل
        if (cfg.breakEvery > 0) {
            val tops = ArrayList<PodShot>()
            for (sh in res) {
                if (sh.type != PodShot.FULL || sh.dur < cfg.breakEvery * 1.3) continue
                val listener = withCam.firstOrNull { camOf[it] != camOf[sh.main] } ?: continue
                var x = sh.start + cfg.breakEvery
                while (x + cfg.breakLen < sh.end - cfg.minShot) {
                    tops.add(PodShot(x, x + cfg.breakLen, PodShot.FULL, listener, IntArray(0)).also { it.tag = "break" })
                    x += cfg.breakEvery + cfg.breakLen
                }
            }
            if (tops.isNotEmpty()) res = PodEngine.overlay(res, tops)
        }
        // ── أول ظهور ببداية الفيديو
        if (cfg.firstHold > 0) res = PodEngine.firstHold(res, cfg.firstHold)
        return PodEngine.tidy(merge(res))
    }

    /** لقطات ورا بعض لنفس الكاميرا (حتى لو شخص ثاني على نفس الكاميرا) = لقطة وحدة */
    private fun merge(list: List<PodShot>): MutableList<PodShot> {
        val res = ArrayList<PodShot>()
        for (x in list.sortedBy { it.start }) {
            if (x.end - x.start <= 1e-4) continue
            val l = res.lastOrNull()
            if (l != null && l.same(x) && l.tag == x.tag && kotlin.math.abs(l.end - x.start) < 1e-3) { l.end = x.end; continue }
            res.add(x.copy())
        }
        return res
    }

    /** رقم الكاميرا لكل شخص من الإعداد (نفس الجهاز = نفس الرقم) */
    fun camOf(setup: PodSetup, hasCam: (Int) -> Boolean): IntArray {
        val devs = ArrayList<String>()
        return IntArray(setup.people.size) { i ->
            val dev = setup.people[i].camDev
            if (dev.isEmpty() || !hasCam(i)) -1 else { var k = devs.indexOf(dev); if (k < 0) { devs.add(dev); k = devs.size - 1 }; k }
        }
    }
}
