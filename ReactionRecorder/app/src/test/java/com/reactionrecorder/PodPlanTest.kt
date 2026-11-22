package com.reactionrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** سيناريوهات الحوار ومنهج المونتاج المتوقع لكل واحد (PodPlan) */
class PodPlanTest {
    private val fs = 0.05

    /** نشاط كل مايك: قائمة فترات كلام لكل شخص */
    private fun an(dur: Double, vararg talk: List<Pair<Double, Double>>): PodAnalysis {
        val n = (dur / fs).toInt()
        val act = Array(talk.size) { p -> BooleanArray(n).also { r -> for ((a, b) in talk[p]) for (i in (a / fs).toInt() until minOf(n, (b / fs).toInt())) r[i] = true } }
        return PodAnalysis(fs, act, FloatArray(talk.size) { -20f }, dur)
    }

    /** كلام متقطع (زي شخصين على نفس المايك بيعلّقوا): on ثانية حكي كل period */
    private fun sparse(a: Double, b: Double, on: Double, period: Double): List<Pair<Double, Double>> {
        val l = ArrayList<Pair<Double, Double>>(); var x = a
        while (x < b) { l.add(x to minOf(b, x + on)); x += period }
        return l
    }

    private fun at(r: List<PodShot>, t: Double) = r.first { t >= it.start && t < it.end }
    private fun cont(r: List<PodShot>) { for (k in 1 until r.size) assertEquals(r[k - 1].end, r[k].start, 1e-6) }
    private val two = intArrayOf(0, 1)

    @Test fun alternatingMonologuesCutOnTime() {
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 10.0, 20.0 to 30.0), listOf(10.0 to 20.0)), two, false, PodPlan.Cfg(), 0.0, 30.0)
        cont(r)
        assertEquals(listOf(0, 1, 0), r.map { it.main })
        assertTrue("cut1 ${r[1].start}", r[1].start in 9.6..10.3)
        assertTrue("cut2 ${r[2].start}", r[2].start in 19.6..20.3)
        assertTrue(r.all { it.type == PodShot.FULL })
    }

    @Test fun shortCommentsNeverMoveTheCamera() {
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 30.0), listOf(5.0 to 5.4, 12.0 to 12.6, 20.0 to 20.8)), two, false, PodPlan.Cfg(), 0.0, 30.0)
        assertEquals(1, r.size); assertEquals(0, r[0].main)
    }

    @Test fun interruptionThatTakesOverMovesTheCamera() {
        val r = PodPlan.plan(an(20.0, listOf(0.0 to 10.0), listOf(8.0 to 20.0)), two, false, PodPlan.Cfg(), 0.0, 20.0)
        assertEquals(1, r.last().main)
        assertTrue("cut ${r.last().start}", r.last().start in 7.5..10.6)
        assertTrue(r.none { it.type == PodShot.MULTI })
    }

    @Test fun talkingTogetherStayBothOrWide() {
        val a = an(30.0, listOf(0.0 to 20.0), listOf(10.0 to 30.0))
        val stay = PodPlan.plan(a, two, false, PodPlan.Cfg(together = "stay"), 0.0, 30.0)
        assertTrue(stay.none { it.type == PodShot.MULTI })
        val both = PodPlan.plan(a, two, false, PodPlan.Cfg(together = "both"), 0.0, 30.0)
        val m = both.filter { it.type == PodShot.MULTI }
        assertEquals(1, m.size)
        assertTrue("multi ${m[0].start}-${m[0].end}", m[0].start in 9.5..11.5 && m[0].end in 19.5..21.5)
        val wide = PodPlan.plan(a, two, true, PodPlan.Cfg(together = "wide"), 0.0, 30.0)
        assertTrue(wide.any { it.type == PodShot.WIDE })
        assertTrue(wide.none { it.type == PodShot.MULTI })
    }

    @Test fun fastExchangeDoesNotFlicker() {
        val aT = ArrayList<Pair<Double, Double>>(); val bT = ArrayList<Pair<Double, Double>>()
        var x = 0.0; var who = 0
        while (x < 12.0) { (if (who == 0) aT else bT).add(x to x + 1.0); x += 1.0; who = 1 - who }
        val r = PodPlan.plan(an(12.0, aT, bT), two, false, PodPlan.Cfg(), 0.0, 12.0)
        assertTrue("shots ${r.size}", r.size <= 4)
        assertTrue(r.all { it.dur >= 1.79 || it === r.last() })
    }

    /** حالتك: شخصين على نفس الكاميرا والمايك بيعلّقوا طول الوقت → الكاميرا ما بتعلق عليهم */
    @Test fun busyMicDoesNotStealTheFloor() {
        val a = listOf(0.0 to 20.0) + sparse(20.0, 40.0, 0.3, 1.0) + listOf(40.0 to 60.0)
        val r = PodPlan.plan(an(60.0, a, listOf(20.0 to 40.0)), two, false, PodPlan.Cfg(), 0.0, 60.0)
        cont(r)
        val mid = at(r, 30.0)
        assertEquals(1, mid.main)
        assertTrue("to B ${mid.start}", mid.start in 19.5..21.5)
        assertEquals(0, at(r, 50.0).main)
    }

    @Test fun fourPeopleTwoPerCameraOnlyCutsBetweenCameras() {
        val cam = intArrayOf(0, 0, 1, 1)
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 10.0), listOf(10.0 to 20.0), listOf(20.0 to 30.0), listOf()), cam, false, PodPlan.Cfg(), 0.0, 30.0)
        assertEquals(2, r.size)
        assertEquals(0, cam[r[0].main]); assertEquals(1, cam[r[1].main])
        assertTrue("cut ${r[1].start}", r[1].start in 19.5..20.3)
    }

    @Test fun threePeopleTalkingTogetherShowsOnlyTheTwo() {
        val cam = intArrayOf(0, 1, 2)
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 10.0), listOf(10.0 to 30.0), listOf(10.0 to 30.0)), cam, false, PodPlan.Cfg(together = "both"), 0.0, 30.0)
        val m = r.first { it.type == PodShot.MULTI }
        assertEquals(setOf(1, 2), m.others.toSet())
    }

    @Test fun wideCameraOnLongSilence() {
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 10.0), listOf(20.0 to 30.0)), two, true, PodPlan.Cfg(), 0.0, 30.0)
        assertTrue(r.any { it.type == PodShot.WIDE && it.start in 13.0..15.5 })
        assertEquals(1, r.last().main)
        val noWide = PodPlan.plan(an(30.0, listOf(0.0 to 10.0), listOf(20.0 to 30.0)), two, false, PodPlan.Cfg(), 0.0, 30.0)
        assertTrue(noWide.none { it.type == PodShot.WIDE })
    }

    @Test fun commentBoxIsOptional() {
        val a = an(30.0, listOf(0.0 to 30.0), listOf(10.0 to 10.6))
        assertTrue(PodPlan.plan(a, two, false, PodPlan.Cfg(commentBox = false), 0.0, 30.0).none { it.type == PodShot.PIP })
        val r = PodPlan.plan(a, two, false, PodPlan.Cfg(commentBox = true), 0.0, 30.0)
        val p = r.single { it.type == PodShot.PIP }
        assertTrue(p.start in 9.8..10.1); assertEquals(0, p.main); assertEquals(1, p.others[0])
    }

    @Test fun breaksOnlyWhenAsked() {
        val a = an(130.0, listOf(0.0 to 130.0), listOf())
        assertEquals(1, PodPlan.plan(a, two, false, PodPlan.Cfg(), 0.0, 130.0).size)
        val r = PodPlan.plan(a, two, false, PodPlan.Cfg(breakEvery = 60), 0.0, 130.0)
        val br = r.filter { it.tag == "break" }
        // ١٣٠ ث كلام متواصل، كل دقيقة → لقطتين (عند ٦٠ و١٢٢٫٥)
        assertEquals(2, br.size); assertTrue(br.all { it.main == 1 && kotlin.math.abs(it.dur - 2.5) < 1e-6 })
        assertEquals(60.0, br[0].start, 0.01)
    }

    @Test fun firstAppearanceHold() {
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 2.0, 4.0 to 30.0), listOf(2.0 to 4.0)), two, false, PodPlan.Cfg(firstHold = 5.0), 0.0, 30.0)
        assertTrue("first ${r[0].end}", r[0].end >= 5.0)
    }

    @Test fun noCameraNoShot() {
        val r = PodPlan.plan(an(20.0, listOf(0.0 to 10.0), listOf(10.0 to 20.0)), intArrayOf(0, -1), false, PodPlan.Cfg(), 0.0, 20.0)
        assertTrue(r.all { it.main == 0 })
    }

    // ── باقي السيناريوهات

    /** مقاطعة فاشلة: الثاني حاول ١ث والأول كمّل → ما في قطع */
    @Test fun failedInterruptionNoCut() {
        for (pace in listOf("calm", "balanced")) {
            val r = PodPlan.plan(an(30.0, listOf(0.0 to 30.0), listOf(12.0 to 13.0)), two, false, PodPlan.Cfg(pace = pace), 0.0, 30.0)
            assertEquals(pace, 1, r.size)
        }
    }

    /** ضحكة مع بعض (ثانية) → ما في قطع ولا تقسيم */
    @Test fun sharedLaughNoCut() {
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 30.0), listOf(15.0 to 16.0)), two, false, PodPlan.Cfg(together = "both"), 0.0, 30.0)
        assertEquals(1, r.size)
    }

    /** تسليم الدور مع تداخل نص ثانية → قطع نظيف، بلا تقسيم */
    @Test fun handoverWithSmallOverlapIsCleanCut() {
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 15.5), listOf(15.0 to 30.0)), two, false, PodPlan.Cfg(together = "both"), 0.0, 30.0)
        assertEquals(listOf(0, 1), r.map { it.main })
        assertTrue(r.none { it.type == PodShot.MULTI })
        assertTrue("cut ${r[1].start}", r[1].start in 14.6..15.8)
    }

    /** وقفة بنص الكلام ١٫٥ث والثاني قال «آه» → بتضل عليه */
    @Test fun pauseWithBackchannelStays() {
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 10.0, 11.5 to 30.0), listOf(10.3 to 10.8)), two, false, PodPlan.Cfg(), 0.0, 30.0)
        assertEquals(1, r.size)
    }

    /** سكوت طويل مع كاميرا عامة → العامة، وأول ما حدا يحكي بترجع عليه */
    @Test fun wideThenBackToTalker() {
        val r = PodPlan.plan(an(40.0, listOf(0.0 to 10.0), listOf(25.0 to 40.0)), two, true, PodPlan.Cfg(), 0.0, 40.0)
        val w = r.single { it.type == PodShot.WIDE }
        val back = r.last()
        assertEquals(1, back.main)
        assertTrue("back ${back.start}", back.start in 24.5..25.6)
        assertTrue(w.end == back.start)
    }

    /** ٣ أشخاص كل واحد بكاميرا، بالدور → كل واحد عليه بوقته */
    @Test fun threeCamsRoundRobin() {
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 10.0), listOf(10.0 to 20.0), listOf(20.0 to 30.0)), intArrayOf(0, 1, 2), false, PodPlan.Cfg(), 0.0, 30.0)
        assertEquals(listOf(0, 1, 2), r.map { it.main })
    }

    /** ٤ أشخاص (٢ بكل كاميرا) اثنين من كاميرات مختلفة بيتجادلوا → تقسيم للكاميرتين */
    @Test fun fourPeopleCrosstalkSplitsTheTwoCameras() {
        val cam = intArrayOf(0, 0, 1, 1)
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 20.0), listOf(), listOf(), listOf(10.0 to 30.0)), cam, false, PodPlan.Cfg(together = "both"), 0.0, 30.0)
        val m = r.single { it.type == PodShot.MULTI }
        assertEquals(setOf(0, 1), m.others.map { cam[it] }.toSet())
    }

    /** ٤ أشخاص (٢ بكل كاميرا) اللي على نفس الكاميرا بيتجادلوا → ولا قطع ولا تقسيم */
    @Test fun sameCameraCrosstalkNoSplit() {
        val cam = intArrayOf(0, 0, 1, 1)
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 30.0), listOf(5.0 to 25.0), listOf(), listOf()), cam, false, PodPlan.Cfg(together = "both"), 0.0, 30.0)
        assertEquals(1, r.size); assertEquals(0, cam[r[0].main])
    }

    /** نقاش مع كاميرا عامة و«العامة» → عامة، وبعده للي كمّل */
    @Test fun crosstalkToWide() {
        val r = PodPlan.plan(an(30.0, listOf(0.0 to 20.0), listOf(10.0 to 30.0)), intArrayOf(0, 1, 2), true, PodPlan.Cfg(together = "wide"), 0.0, 30.0)
        assertTrue(r.any { it.type == PodShot.WIDE && it.start in 9.5..11.5 })
        assertEquals(1, r.last().main)
    }

    /** السرعة: نفس الحوار، السريع بيقلب أكثر من الهادئ وما في لقطة أقصر من حدها */
    @Test fun paceChangesOnlyTiming() {
        val aT = ArrayList<Pair<Double, Double>>(); val bT = ArrayList<Pair<Double, Double>>()
        var x = 0.0; var who = 0
        for (len in listOf(4.0, 2.0, 3.0, 1.5, 5.0, 2.0, 1.4, 6.0, 2.5, 3.0)) { (if (who == 0) aT else bT).add(x to x + len); x += len; who = 1 - who }
        val res = listOf("calm", "balanced", "lively").map { pace -> pace to PodPlan.plan(an(x, aT, bT), two, false, PodPlan.Cfg(pace = pace), 0.0, x) }
        for ((pace, r) in res) { cont(r); val m = PodPlan.Cfg(pace = pace).minShot; assertTrue(pace, r.dropLast(1).all { it.dur >= m - 1e-6 }) }
        assertTrue("${res.map { it.second.size }}", res[0].second.size <= res[1].second.size && res[1].second.size <= res[2].second.size)
    }

    /** الحكي متقطع (وقفات قصيرة) والثاني بيقول «آه» بالوقفات → ولا تقسيم ولا قطع */
    @Test fun backchannelsInPausesNeverSplit() {
        val aT = ArrayList<Pair<Double, Double>>(); val bT = ArrayList<Pair<Double, Double>>()
        var x = 0.0
        while (x < 40.0) { aT.add(x to x + 1.6); bT.add(x + 1.7 to x + 2.0); x += 2.2 }
        val r = PodPlan.plan(an(40.0, aT, bT), two, false, PodPlan.Cfg(together = "both"), 0.0, 40.0)
        assertEquals(r.toString(), 1, r.size); assertEquals(0, r[0].main)
    }

    /** تبادل سريع وخيار «الاثنين مع بعض» → تقسيم بدل تقليب كل ثانية */
    @Test fun fastExchangeWithBothSplits() {
        val aT = ArrayList<Pair<Double, Double>>(); val bT = ArrayList<Pair<Double, Double>>()
        var x = 0.0; var who = 0
        while (x < 20.0) { (if (who == 0) aT else bT).add(x to x + 1.2); x += 1.2; who = 1 - who }
        val r = PodPlan.plan(an(20.0, aT, bT), two, false, PodPlan.Cfg(together = "both"), 0.0, 20.0)
        assertTrue(r.toString(), r.any { it.type == PodShot.MULTI && it.dur > 8.0 })
    }

    /** حالتك: ٤ أشخاص، ٢ بكل كاميرا. اللي على الكاميرا الثانية بيتبادلوا جمل قصيرة بيناتهم → لازم الكاميرا الثانية تطلع */
    @Test fun twoPerCameraTradingStillGetsTheCamera() {
        val cam = intArrayOf(0, 0, 1, 1)
        val b1 = ArrayList<Pair<Double, Double>>(); val b2 = ArrayList<Pair<Double, Double>>()
        var x = 10.0; var w = 0
        while (x < 30.0) { (if (w == 0) b1 else b2).add(x to x + 1.5); x += 1.5; w = 1 - w }
        val r = PodPlan.plan(an(40.0, listOf(0.0 to 10.0, 30.0 to 40.0), listOf(), b1, b2), cam, false, PodPlan.Cfg(), 0.0, 40.0)
        cont(r)
        val mid = at(r, 20.0)
        assertEquals(r.toString(), 1, cam[mid.main])
        assertTrue("to cam B ${mid.start}", mid.start in 9.5..11.0)
        assertEquals(0, cam[at(r, 36.0).main])
        // والوقت الظاهر للكاميرا الثانية قريب من وقت حكيها (٢٠ من ٤٠ ث)
        val shareB = r.filter { cam[it.main] == 1 }.sumOf { it.dur } / 40.0
        assertTrue("share $shareB", shareB in 0.42..0.58)
    }

    /** «بتنتقل على اللي دخل»: مرة وحدة، وما بترجع وبتقلب */
    @Test fun switchToTheOneWhoCameIn() {
        val a = an(30.0, listOf(0.0 to 20.0), listOf(10.0 to 30.0))
        val r = PodPlan.plan(a, two, false, PodPlan.Cfg(together = "switch"), 0.0, 30.0)
        assertEquals(r.toString(), listOf(0, 1), r.map { it.main })
        assertTrue("cut ${r[1].start}", r[1].start in 9.8..12.0)
        val late = PodPlan.plan(a, two, false, PodPlan.Cfg(together = "switch", togetherAfter = 3.0), 0.0, 30.0)
        assertEquals(late.toString(), listOf(0, 1), late.map { it.main })
        assertTrue("late cut ${late[1].start}", late[1].start in 12.5..14.5)
    }

    /** «الاثنين مع بعض بعد ٢ ث» → التقسيم بيبلش متأخر ٢ ث */
    @Test fun splitAfterDelay() {
        val a = an(30.0, listOf(0.0 to 20.0), listOf(10.0 to 30.0))
        val r = PodPlan.plan(a, two, false, PodPlan.Cfg(together = "both", togetherAfter = 2.0), 0.0, 30.0)
        val m = r.single { it.type == PodShot.MULTI }
        assertTrue("multi ${m.start}", m.start in 11.5..13.5)
    }

    /** كسر الملل بطول اللي بتختاره */
    @Test fun breakLength() {
        val r = PodPlan.plan(an(70.0, listOf(0.0 to 70.0), listOf()), two, false, PodPlan.Cfg(breakEvery = 30, breakLen = 4.0), 0.0, 70.0)
        val br = r.filter { it.tag == "break" }
        assertTrue(br.isNotEmpty()); assertTrue(br.all { kotlin.math.abs(it.dur - 4.0) < 1e-6 })
    }
}
