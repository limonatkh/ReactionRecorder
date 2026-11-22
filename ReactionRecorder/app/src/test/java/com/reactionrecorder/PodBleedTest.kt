package com.reactionrecorder

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** مايكين قريبين من بعض: كل واحد سامع الثاني أوطى بـ leakDb بس */
class PodBleedTest {
    private val fs = 0.05

    private fun sim(leakDb: Float, gainB: Float = 0f): Triple<Array<FloatArray>, FloatArray, IntArray> {
        val rnd = java.util.Random(7)
        val n = (80 / fs).toInt()
        val who = IntArray(n) // 0 سكوت، 1 A، 2 B، 3 الاثنين
        for (i in 0 until n) { val t = i * fs; who[i] = when { t < 30 -> 1; t < 60 -> 2; t < 70 -> 3; else -> 0 } }
        fun lin(d: Double) = 10.0.pow(d / 10)
        val db = Array(2) { FloatArray(n) }
        for (i in 0 until n) {
            // كلام كل واحد بيتذبذب (مقاطع وكلمات)، والضوضاء -60
            val a = if (who[i] == 1 || who[i] == 3) -20.0 + rnd.nextGaussian() * 4 else -200.0
            val b = if (who[i] == 2 || who[i] == 3) -20.0 + rnd.nextGaussian() * 4 else -200.0
            val noise = -60.0
            db[0][i] = (10 * log10(lin(a) + lin(b - leakDb) + lin(noise) * (1 + rnd.nextDouble()))).toFloat()
            db[1][i] = (10 * log10(lin(b + gainB) + lin(a - leakDb + gainB) + lin(noise) * (1 + rnd.nextDouble()))).toFloat()
        }
        return Triple(db, floatArrayOf(-48f, -48f), who)
    }

    private fun frac(act: BooleanArray, who: IntArray, w: Int) = act.indices.count { who[it] == w && act[it] }.toDouble() / who.count { it == w }

    private fun check(leakDb: Float, gainB: Float = 0f) {
        val (db, thr, who) = sim(leakDb, gainB)
        val r = PodBleed.activity(db, thr, PodBleed.offsets(db, thr), 6f)
        val aAloneB = frac(r.act[1], who, 1); val bAloneA = frac(r.act[0], who, 2)
        val bothA = frac(r.act[0], who, 3); val bothB = frac(r.act[1], who, 3)
        val msg = "leak $leakDb gain $gainB: B while A alone $aAloneB, A while B alone $bAloneA, both $bothA/$bothB ${PodBleed.describe(r)}"
        println(msg)
        assertTrue(msg, aAloneB < 0.08 && bAloneA < 0.08)
        assertTrue(msg, frac(r.act[0], who, 1) > 0.9 && frac(r.act[1], who, 2) > 0.9)
        assertTrue(msg, bothA > 0.5 && bothB > 0.5)
    }

    /** تسريب قوي (٥ dB بس فرق) — حالتك: الاثنين كان بيطلعوا «بيحكوا» ٨٠٪ */
    @Test fun strongBleed() = check(5f)
    @Test fun mediumBleed() = check(8f)
    @Test fun weakBleed() = check(14f)
    /** مايك B أقوى من A بـ ٦ dB */
    @Test fun unevenMics() = check(7f, 6f)

    /** القاعدة القديمة كانت تفشل بالتسريب القوي (للتوثيق) */
    @Test fun oldRuleFailsWithStrongBleed() {
        val (db, thr, who) = sim(5f)
        var both = 0; var aAlone = 0
        for (i in who.indices) if (who[i] == 1) { aAlone++; if (db[1][i] > thr[1] && db[1][i] >= max(db[0][i], db[1][i]) - 6f) both++ }
        assertTrue("old rule: B active while A alone ${both.toDouble() / aAlone}", both.toDouble() / aAlone > 0.5)
    }
}
