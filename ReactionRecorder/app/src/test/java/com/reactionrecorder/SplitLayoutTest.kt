package com.reactionrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** منهج قلب القالب: خانات آمنة (3:4–4:3)، جوا الشاشة، ما بتتداخل، وبالطول = نفس الحجم مقلوب */
class SplitLayoutTest {
    private fun check(cells: List<FloatArray>, W: Float, H: Float, safe: Boolean = true) {
        for (c in cells) {
            assertTrue("inside", c[0] >= -0.5f && c[1] >= -0.5f && c[0] + c[2] <= W + 0.5f && c[1] + c[3] <= H + 0.5f)
            if (safe) { val a = c[2] / c[3]; assertTrue("aspect $a", a >= SplitLayout.AMIN - 0.01f && a <= SplitLayout.AMAX + 0.01f) }
        }
        for (i in cells.indices) for (j in i + 1 until cells.size) {
            val a = cells[i]; val b = cells[j]
            val ov = a[0] < b[0] + b[2] - 0.5f && b[0] < a[0] + a[2] - 0.5f && a[1] < b[1] + b[3] - 0.5f && b[1] < a[1] + a[3] - 0.5f
            assertTrue("overlap $i $j", !ov)
        }
    }

    @Test fun equalLandscapeAndPortrait() {
        for (n in 2..9) {
            val l = SplitLayout.equal(n, 0f, 0f, 1920f, 1080f, 16f)
            val p = SplitLayout.equal(n, 0f, 0f, 1080f, 1920f, 16f)
            assertEquals(n, l.size); assertEquals(n, p.size)
            check(l, 1920f, 1080f); check(p, 1080f, 1920f)
            // نفس المساحة بالاتجاهين (نفس التقسيم مقلوب)
            assertEquals(l[0][2] * l[0][3], p[0][2] * p[0][3], l[0][2] * l[0][3] * 0.02f)
        }
    }

    @Test fun twoPeopleSideBySideThenStacked() {
        val l = SplitLayout.equal(2, 0f, 0f, 1920f, 1080f, 16f)
        assertEquals(l[0][1], l[1][1], 0.5f)          // نفس السطر
        assertTrue(l[0][0] > l[1][0])                  // الأول يمين
        val p = SplitLayout.equal(2, 0f, 0f, 1080f, 1920f, 16f)
        assertEquals(p[0][0], p[1][0], 0.5f)          // نفس العمود
        assertTrue(p[0][1] < p[1][1])                  // الأول فوق
    }

    @Test fun mainIsAtLeastFourTimesBigger() {
        for (k in 1..8) for ((W, H) in listOf(1920f to 1080f, 1080f to 1920f)) {
            val c = SplitLayout.mainPlus(k, 0f, 0f, W, H, 16f)
            assertEquals(k + 1, c.size)
            check(c, W, H)
            val m = c[0][2] * c[0][3]
            for (s in c.drop(1)) assertTrue("ratio", m >= s[2] * s[3] * 3.99f)
        }
    }
}
