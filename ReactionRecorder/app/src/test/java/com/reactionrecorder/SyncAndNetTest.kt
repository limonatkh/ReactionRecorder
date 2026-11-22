package com.reactionrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** اختبارات المزامنة بالصوت، توحيد مدى الجلسة، وباركود/نداء الاقتران */
class SyncAndNetTest {

    /** غلاف كلام صناعي: مقاطع عشوائية من "كلام" و"صمت" (dB) */
    private fun speechDb(n: Int, seed: Int): FloatArray {
        val r = Random(seed)
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            val talk = r.nextInt(8, 60)
            for (k in 0 until talk) if (i + k < n) out[i + k] = -25f + r.nextFloat() * 8f
            i += talk
            val gap = r.nextInt(4, 30)
            for (k in 0 until gap) if (i + k < n) out[i + k] = -70f + r.nextFloat() * 4f
            i += gap
        }
        return out
    }

    @Test
    fun soundSyncFindsKnownOffset() {
        // المرجع (بزمن الجلسة) وملف جهاز بدأ قبل الجلسة بـ 137 عيّنة (6.85 ث): x[i+137] = ref[i]
        val full = speechDb(6000, 7)
        val lag = 137
        val ref = full.copyOfRange(lag, 5000)
        val x = full.copyOfRange(0, 5600)
        val res = PodSync.bestLag(PodSync.onsets(ref), PodSync.onsets(x), 2000)
        assertNotNull(res)
        assertEquals(lag, res!!.lag)
        assertTrue("score ${res.score}", res.score > 0.5)
        assertTrue(res.score > res.second * 1.25)
    }

    @Test
    fun soundSyncNegativeOffset() {
        // الجهاز بدأ بعد الجلسة بـ 90 عيّنة: x[i-90] = ref[i]
        val full = speechDb(5000, 11)
        val ref = full.copyOfRange(0, 4000)
        val x = full.copyOfRange(90, 4500)
        val res = PodSync.bestLag(PodSync.onsets(ref), PodSync.onsets(x), 1000)!!
        assertEquals(-90, res.lag)
    }

    @Test
    fun soundSyncSurvivesNoiseAndLevelDifference() {
        val full = speechDb(5000, 3)
        val r = Random(5)
        val ref = full.copyOfRange(200, 4200)
        // جهاز بعيد: أخفض بـ 12 dB مع ضوضاء
        val x = FloatArray(4600) { full[it] - 12f + (r.nextFloat() - 0.5f) * 6f }
        val res = PodSync.bestLag(PodSync.onsets(ref), PodSync.onsets(x), 1500)!!
        assertEquals(200, res.lag)
    }

    @Test
    fun thetaFromLagMatchesAudioMap() {
        // بطوابع زمنية: f0 عند t0 على ساعة الجهاز. الجلسة تبدأ عند start على ساعة الرئيسي.
        val rate = 44100
        val start = 5_000_000_000_000L
        val t0 = 9_000_000_000_000L
        val f0 = 4410L
        val lagSec = 6.85
        val theta = PodSync.thetaFromLag(lagSec, start, true, f0, t0, 0L, rate)
        // عند t ثانية من الجلسة: sat = start + t + θ ⇒ n = f0 + (sat − t0)·rate ⇒ يجب أن يساوي (t + lag)·rate
        for (t in listOf(0.0, 10.0, 300.0)) {
            val m = start + (t * 1e9).toLong()
            val sat = m + theta
            val n = f0 + (sat - t0).toDouble() * rate / 1e9
            assertEquals((t + lagSec) * rate, n, 2.0)
        }
        // بدون طوابع (fallbackStart)
        val fb = 7_000_000_000_000L
        val th2 = PodSync.thetaFromLag(lagSec, start, false, 0L, 0L, fb, rate)
        val n2 = ((start + th2) - fb).toDouble() * rate / 1e9
        assertEquals(lagSec * rate, n2, 2.0)
    }

    @Test
    fun spanUnionAndCommon() {
        val cov = listOf(doubleArrayOf(-3.0, 100.0), doubleArrayOf(0.5, 140.0))
        val u = PodSync.span(cov, "union", 100.0)
        assertEquals(-3.0, u[0], 1e-9); assertEquals(140.0, u[1], 1e-9)
        val c = PodSync.span(cov, "common", 100.0)
        assertEquals(0.5, c[0], 1e-9); assertEquals(100.0, c[1], 1e-9)
        val m = PodSync.span(cov, "main", 100.0)
        assertEquals(0.0, m[0], 1e-9); assertEquals(100.0, m[1], 1e-9)
        assertTrue(PodSync.mismatch(cov, 100.0))
        assertFalse(PodSync.mismatch(listOf(doubleArrayOf(-0.4, 100.3), doubleArrayOf(0.2, 99.8)), 100.0))
    }

    @Test
    fun shiftKeepsEditsInPlace() {
        val p = PodProject("episode", PodProject.EPISODE)
        p.shots.add(PodShot(0.0, 10.0, PodShot.FULL, 0, IntArray(0)))
        p.shots.add(PodShot(10.0, 20.0, PodShot.FULL, 1, IntArray(0)).also { it.locked = true })
        p.cuts.add(PCut(12.0, 13.0, PCut.GAP))
        p.zooms.add(PZoom(14.0, 16.0, 1.1f, true))
        // الجلسة صارت تبدأ قبل 3 ثوانٍ: كل الأزمنة +3
        PodSync.shiftProject(p, 3.0, 123.0)
        assertEquals(3.0, p.shots[0].start, 1e-9)
        assertEquals(23.0, p.shots[1].end, 1e-9)
        assertTrue(p.shots[1].locked)
        assertEquals(15.0, p.cuts[0].a, 1e-9)
        assertEquals(17.0, p.zooms[0].a, 1e-9)
        // بداية أبكر من 0 تُقص
        PodSync.shiftProject(p, -5.0, 118.0)
        assertEquals(0.0, p.shots[0].start, 1e-9)
    }

    @Test
    fun shiftTranscriptKeepsWords() {
        val tr = Transcript(mutableListOf(TSeg(0, 1.0, 2.0, "مرحبا", 0, listOf(TWord(1.0, 1.5, "مرحبا")))), "ar", "t")
        PodSync.shiftTranscript(tr, 2.5)
        assertEquals(3.5, tr.segs[0].a, 1e-9)
        assertEquals(3.5, tr.segs[0].words[0].a, 1e-9)
    }

    @Test
    fun qrV2ParsesWithoutManualInput() {
        val q = SatNet.parseQr("RRSAT2|192.168.43.1:47601,10.0.0.5:47601|47601|٤٢٣١|hub123|Galaxy M13")
        assertNotNull(q)
        assertEquals("4231", q!!.pin)
        assertEquals("hub123", q.hubId)
        assertEquals("Galaxy M13", q.hubName)
        assertTrue(q.hosts.startsWith("192.168.43.1:47601"))
        // الصيغة القديمة ما زالت مقبولة، والعناوين بلا منفذ يضاف لها المنفذ المعلن
        val old = SatNet.parseQr("RRSAT1|192.168.1.4|47603|1234")!!
        assertEquals("192.168.1.4:47603", old.hosts)
        assertEquals("", old.hubId)
        assertNull(SatNet.parseQr("hello"))
        assertNull(SatNet.parseQr("RRSAT2|1.2.3.4|47600|12"))
    }

    @Test
    fun beaconUpdatesHostFirst() {
        val b = SatNet.parseBeacon(SatNet.beacon("hubX", 47602, "M13"))!!
        assertEquals("hubX", b.hubId); assertEquals(47602, b.port)
        assertNull(SatNet.parseBeacon("RRHUB2||47600|x"))
        val h = SatNet.preferHost("192.168.43.1:47600,10.0.0.2:47600", "10.0.0.2:47602")
        assertEquals("10.0.0.2:47602", h.split(',')[0])
        assertEquals(3, h.split(',').size)
        assertEquals("a:1,b:2", SatNet.preferHost("b:2,a:1", "a:1"))
    }

    @Test
    fun onsetsIgnoreConstantLevel() {
        val flat = FloatArray(500) { -40f }
        val o = PodSync.onsets(flat)
        assertTrue(o.all { abs(it) < 1e-6 })
    }
}
