package com.reactionrecorder

import kotlin.math.max
import kotlin.math.min

/**
 * هندسة المونتاج المشتركة بين الإخراج الحقيقي (PodcastRenderer) ونموذج القالب (LayoutDemoView):
 * مكان المربع العائم، شريط الكتابة، العناصر الثابتة، والمناطق الممنوعة بالريل.
 * عشان اللي بتشوفه بنموذج القالب هو نفس الترتيب اللي بيطلع بالفيديو.
 */
object PodLayout {
    /** مستطيل بسيط (مش android.graphics.RectF عشان الحساب ينفحص باختبارات عادية) */
    class Box(var left: Float, var top: Float, var right: Float, var bottom: Float) {
        fun width() = right - left
        fun height() = bottom - top
        fun centerX() = (left + right) / 2f
        fun centerY() = (top + bottom) / 2f
        fun offset(dx: Float, dy: Float) { left += dx; right += dx; top += dy; bottom += dy }
        fun inset(d: Float) = Box(left - d, top - d, right + d, bottom + d)
        fun toRectF() = android.graphics.RectF(left, top, right, bottom)
    }

    /** المناطق اللي بتغطيها واجهة المنصات بالريل: تحت (اسم الحساب والوصف)، يمين تحت (الأزرار)، فوق شوي */
    fun blocked(vertical: Boolean, W: Float, H: Float): List<Box> =
        if (vertical) listOf(Box(0f, H * 0.82f, W, H), Box(W * 0.86f, H * 0.45f, W, H), Box(0f, 0f, W, H * 0.07f))
        else listOf(Box(0f, H * 0.93f, W, H))

    /** مركز الكتابة عموديًا */
    fun capCenterY(tp: PodTemplate, vertical: Boolean, H: Float): Float = when (tp.capPos) {
        "top" -> if (vertical) H * 0.18f else H * 0.14f
        "middle" -> H * 0.5f
        else -> if (vertical) H * (if (tp.capStyle == "line") 0.70f else 0.68f) else H * 0.86f
    }

    /** حجم خط الكتابة بالبكسل */
    fun capTextSize(tp: PodTemplate, vertical: Boolean, H: Float, capSize: Float): Float {
        val base = if (tp.capStyle == "line") (if (vertical) 0.036f else 0.052f) else (if (vertical) 0.050f else 0.066f)
        return H * base * capSize
    }

    /** المنطقة التقريبية للكتابة (عشان المربع العائم والعناصر ما يغطوها) */
    fun capBand(tp: PodTemplate, vertical: Boolean, W: Float, H: Float, captionsOn: Boolean): Box? {
        if (!captionsOn) return null
        val cy = capCenterY(tp, vertical, H)
        val h = capTextSize(tp, vertical, H, tp.capSize) * (if (tp.capStyle == "line") 2.6f else 1.8f)
        return Box(W * 0.06f, cy - h / 2f, W * 0.94f, cy + h / 2f)
    }

    /** [عرض، ارتفاع] المربع العائم */
    fun pipSize(tp: PodTemplate, vertical: Boolean, W: Float, circle: Boolean): FloatArray {
        val pw = W * (if (vertical) min(0.95f, tp.pipSize * 1.45f) else min(0.9f, tp.pipSize))
        // الشكل: بالريل مربع افتراضيًا (مش طولي)، وبالحلقة بالعرض
        val asp = if (tp.pipAspect == "auto") (if (vertical) "square" else "169") else tp.pipAspect
        val ph = when {
            circle -> pw
            tp.pipFace -> if (tp.faceAspect == "portrait") pw * 1.25f else pw
            asp == "square" -> pw
            asp == "wide" -> pw * 0.75f
            asp == "tall" -> pw * 4f / 3f
            else -> pw * 9f / 16f
        }
        return floatArrayOf(pw, ph)
    }

    /** المربع على الوجه: الوجه بياخد قديش من ارتفاع المربع (خفيف/متوسط/قريب) — الزوم بيتغير مع قرب الشخص */
    fun faceTarget(faceZoom: Float): Float = when { faceZoom < 1.45f -> 0.35f; faceZoom < 1.9f -> 0.45f; else -> 0.6f }

    /** زوم بيخلي الوجه (حجمه fs من ارتفاع المصدر) ياخد target من ارتفاع المربع؛ kh = نسبة ارتفاع المصدر الظاهرة بدون زوم */
    fun adaptiveZoom(target: Float, fs: Float, kh: Float): Float = if (fs <= 0.01f) 1.6f else (target * kh / fs).coerceIn(1f, 4f)

    /** الوجه [x,y,حجم] لازق بطرف الصورة (فوق أو تحت أو الجوانب) */
    fun faceAtEdge(f: FloatArray): Boolean = f[1] - f[2] * 0.6f < 0.03f || f[1] + f[2] * 0.6f > 0.97f || f[0] < 0.06f || f[0] > 0.94f

    fun margin(tp: PodTemplate, W: Float, H: Float): Float =
        if (tp.avoidFace) max(min(W, H) * tp.pipMargin, min(W, H) * 0.05f) else min(W, H) * tp.pipMargin

    /**
     * أماكن المربعات العائمة (واحد لكل شخص قاطع). mirrorX = الجهة الثانية، flipY = فوق↔تحت.
     * حر بالكامل: مناطق أزرار المنصة بالريل تنبيه أحمر بالمعاينة بس، والكتابة هي اللي بتبعد عن المربع.
     */
    fun pipRects(tp: PodTemplate, W: Float, H: Float, vertical: Boolean, n: Int, pw: Float, ph: Float,
                 mirrorX: Boolean, flipY: Boolean, captionsOn: Boolean): List<Box> {
        val m = margin(tp, W, H)
        val pos = tp.pipPos
        if (pos == "custom") {
            var cx = tp.pipX; var cy = tp.pipY
            if (mirrorX) cx = 1f - cx
            if (flipY) cy = 1f - cy
            return List(n) { i ->
                val dy = if (cy > 0.5f) -i * (ph + H * 0.03f) else i * (ph + H * 0.03f)
                clamp(Box(cx * W - pw / 2f, cy * H - ph / 2f + dy, cx * W + pw / 2f, cy * H + ph / 2f + dy), W, H)
            }
        }
        var top = pos.startsWith("t")
        var hx = when { pos.endsWith("c") -> 0; pos.endsWith("l") -> -1; else -> 1 }
        if (mirrorX) hx = -hx
        if (flipY) top = !top
        val topY = if (vertical) H * 0.09f else m
        val bottomY = if (vertical) H * 0.79f - ph else H - ph - m
        val res = ArrayList<Box>()
        for (i in 0 until n) {
            val x: Float; val y: Float
            if (hx == 0) {
                // بالنص: المربعات جنب بعض
                val totalW = n * pw + (n - 1) * W * 0.03f
                x = (W - totalW) / 2f + i * (pw + W * 0.03f)
                y = if (top) topY else bottomY
            } else {
                x = if (hx < 0) m else W - pw - m
                y = if (top) topY + i * (ph + H * 0.03f) else bottomY - i * (ph + H * 0.03f)
            }
            res.add(clamp(Box(x, y, x + pw, y + ph), W, H))
        }
        return res
    }

    /** قرار المربع العائم بلقطة معيّنة: الجهة، قديش بيصغر، أو تقسيم الشاشة بداله */
    class PipPlan(val mirrorX: Boolean, val flipY: Boolean, val scale: Float, val split: Boolean)

    /**
     * المربع العائم ما بيغطي وجه اللي بالشاشة: أول إشي بنجرب الجهة الثانية (إذا "ما يغطي الوجه" مفعّل)،
     * بعدين بيصغر شوي (لحد 85%)، وإذا لسا بيغطي → الشاشة كلها بتنتقل على الشخص اللي كان رح يطلع بالمربع
     * (split = true يعني "انتقال كامل"). إذا ما في معلومات وجه، بنفترض الوجه بنص الصورة.
     */
    fun pipPlan(tp: PodTemplate, W: Float, H: Float, vertical: Boolean, n: Int, pw: Float, ph: Float,
                mirrorX: Boolean, captionsOn: Boolean, face0: Box?): PipPlan {
        val face = face0 ?: assumedFace(W, H)
        val fa = max(1f, face.width() * face.height())
        val pad = margin(tp, W, H) * 0.3f
        val sides = if (tp.avoidFace) listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1) else listOf(0 to 0)
        for (k in floatArrayOf(1f, 0.92f, 0.85f)) {
            for ((a, b) in sides) {
                val rs = pipRects(tp, W, H, vertical, n, pw * k, ph * k, mirrorX xor (a == 1), b == 1, captionsOn)
                val ov = rs.sumOf { overlap(it.inset(pad), face).toDouble() }
                if (ov <= fa * 0.01) return PipPlan(mirrorX xor (a == 1), b == 1, k, false)
            }
        }
        return PipPlan(mirrorX, false, 1f, true)
    }

    /** الوجه المفترض لما ما في كشف وجه: بنص الصورة وفوق شوي */
    fun assumedFace(W: Float, H: Float) = Box(W * 0.36f, H * 0.12f, W * 0.64f, H * 0.6f)

    /** يدخّل المستطيل جوّا الصورة بس */
    fun clamp(r: Box, W: Float, H: Float): Box {
        val w = min(r.width(), W); val h = min(r.height(), H)
        val x = r.left.coerceIn(0f, max(0f, W - w)); val y = r.top.coerceIn(0f, max(0f, H - h))
        return Box(x, y, x + w, y + h)
    }

    fun overlap(a: Box, b: Box): Float {
        val ix = min(a.right, b.right) - max(a.left, b.left)
        val iy = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (ix > 0 && iy > 0) ix * iy else 0f
    }

    /** هل المستطيل داخل منطقة بتغطيها واجهة المنصة؟ (للتنبيه الأحمر) */
    fun inBlocked(r: Box, vertical: Boolean, W: Float, H: Float): Boolean = blocked(vertical, W, H).any { overlap(it, r) > r.width() * r.height() * 0.15f }

    /** مكان عنصر ثابت حجمه w×h؛ index = ترتيبه بين العناصر بنفس المكان. بعيد عن الحافة متل البودكاستات المعروفة */
    fun itemRect(pos: String, w: Float, h: Float, W: Float, H: Float, vertical: Boolean, index: Int, band: Box? = null, cx: Float = -1f, cy: Float = -1f): Box {
        if (cx >= 0f && cy >= 0f) return clamp(Box(cx * W - w / 2f, cy * H - h / 2f, cx * W + w / 2f, cy * H + h / 2f), W, H)
        val mx = W * 0.06f
        val top = pos.startsWith("t")
        val x = when { pos.endsWith("c") -> (W - w) / 2f; pos.endsWith("l") -> mx; else -> W - w - mx }
        val step = h + H * 0.012f
        val y = if (top) (if (vertical) H * 0.085f else H * 0.06f) + index * step
        else (if (vertical) H * 0.80f - h else H * 0.94f - h) - index * step
        return Box(x, y, x + w, y + h)
    }

    /** عنصر ثابت بيبعد عن المربع العائم: الجهة الثانية أفقيًا، أو تحت/فوق المربع */
    fun itemAvoid(r: Box, avoid: List<Box>, W: Float, H: Float): Box {
        if (avoid.none { overlap(it, r) > 0f }) return r
        val gap = H * 0.015f
        val cands = ArrayList<Box>()
        cands.add(Box(W - r.right, r.top, W - r.left, r.bottom))
        for (a in avoid) if (overlap(a, r) > 0f) {
            cands.add(Box(r.left, a.bottom + gap, r.right, a.bottom + gap + r.height()))
            cands.add(Box(r.left, a.top - gap - r.height(), r.right, a.top - gap))
        }
        return cands.map { clamp(it, W, H) }.firstOrNull { c -> avoid.none { overlap(it, c) > 0f } } ?: r
    }

    /**
     * مكان الكتابة (مركزها عموديًا): ما بتغطي وجه ولا مربع عائم ولا نص ثابت، وما بتلزق بالحافة.
     * divider = خط الفاصل لما يكونوا شخص فوق وشخص تحت (الكتابة بتروح عليه).
     */
    fun capPlace(tp: PodTemplate, vertical: Boolean, W: Float, H: Float, capH: Float, avoid: List<Box>, divider: Float?): Float {
        val lo = H * 0.07f + capH / 2f; val hi = H * 0.93f - capH / 2f
        if (divider != null && tp.capPos != "top") return divider.coerceIn(lo, hi)
        val def = capCenterY(tp, vertical, H)
        val cands = linkedSetOf(def, H * 0.72f, H * 0.80f, H * 0.64f, H * 0.86f, H * 0.28f, H * 0.18f, H * 0.5f)
        fun cost(c: Float): Float {
            val b = Box(W * 0.06f, c - capH / 2f, W * 0.94f, c + capH / 2f)
            return avoid.sumOf { overlap(it, b).toDouble() }.toFloat()
        }
        var best = def.coerceIn(lo, hi); var bestCost = Float.MAX_VALUE
        for (c0 in cands) {
            val c = c0.coerceIn(lo, hi)
            val k = cost(c) + kotlin.math.abs(c - def) * 0.01f
            if (k < bestCost) { best = c; bestCost = k }
            if (cost(c) == 0f && c0 == def) return c
        }
        return best
    }
}

/**
 * توزيع الناس على الشاشة (منهج «قلب القالب»): كل شخص بخانة ضمن النطاق الآمن 3:4 لـ 4:3 — ما بتنمغط لدرجة تقص الشخص —
 * وبأكبر حجم ممكن. اللي بيضل فراغ = خلفية. نفس الحساب بالعرض والطول (جنب بعض ↔ فوق بعض لحاله).
 * الترتيب مثل القراءة بالعربي: الأول يمين فوق.
 */
object SplitLayout {
    const val AMIN = 0.75f
    const val AMAX = 4f / 3f

    private class Fit(val area: Float, val rows: Int, val cols: Int, val cw: Float, val ch: Float)

    private fun fit(n: Int, w: Float, h: Float, g: Float, only: Int = 0): Fit? {
        var best: Fit? = null
        for (c in 1..n) {
            if (only == 1 && c != n) continue      // صف واحد (جنب بعض)
            if (only == 2 && c != 1) continue      // عمود واحد (فوق بعض)
            val r = (n + c - 1) / c
            if ((r - 1) * c >= n) continue
            val aw = (w - (c + 1) * g) / c
            val ah = (h - (r + 1) * g) / r
            if (aw <= 1f || ah <= 1f) continue
            var cw = min(aw, ah * AMAX)
            var ch = min(ah, cw / AMIN)
            if (cw / ch > AMAX) cw = ch * AMAX
            if (cw / ch < AMIN) ch = cw / AMIN
            val a = cw * ch
            if (best == null || a > best.area * 1.01f) best = Fit(a, r, c, cw, ch)
        }
        return best
    }

    private fun place(n: Int, f: Fit, x: Float, y: Float, w: Float, h: Float, g: Float, scale: Float = 1f): List<FloatArray> {
        val cw = f.cw * scale; val ch = f.ch * scale
        val gh = f.rows * ch + (f.rows - 1) * g
        val out = ArrayList<FloatArray>()
        for (i in 0 until n) {
            val row = i / f.cols
            val inRow = if (row == f.rows - 1) n - f.cols * (f.rows - 1) else f.cols
            val k = i - row * f.cols
            val rw = inRow * cw + (inRow - 1) * g
            out.add(floatArrayOf(x + (w - rw) / 2f + rw - (k + 1) * cw - k * g, y + (h - gh) / 2f + row * (ch + g), cw, ch))
        }
        return out
    }

    /**
     * n شخص بنفس الحجم داخل (x,y,w,h). dir: auto | side (صف واحد إذا ≤٣) | stack (عمود واحد إذا ≤٣).
     * شخص واحد = المنطقة كلها.
     */
    fun equal(n: Int, x: Float, y: Float, w: Float, h: Float, g: Float, dir: String = "auto"): List<FloatArray> {
        if (n <= 0) return emptyList()
        if (n == 1) return listOf(floatArrayOf(x, y, w, h))
        val only = if (n <= 3) when (dir) { "side" -> 1; "stack" -> 2; else -> 0 } else 0
        val f = fit(n, w, h, g, only) ?: fit(n, w, h, g) ?: return listOf(floatArrayOf(x, y, w, h))
        return place(n, f, x, y, w, h, g)
    }

    /**
     * شخص أساسي + k ثانويين: الأساسي بطول الضلع القصير (يمين بالعرض، فوق بالطول)، والثانويين بالباقي.
     * مساحة الأساسي ٤ أضعاف الثانوي أو أكثر. أول خانة بالنتيجة = الأساسي.
     */
    fun mainPlus(k: Int, x: Float, y: Float, w: Float, h: Float, g: Float): List<FloatArray> {
        if (k <= 0) return listOf(floatArrayOf(x, y, w, h))
        val land = w >= h
        var best: Pair<Float, List<FloatArray>>? = null
        for (step in 0..20) {
            val a = AMIN + (AMAX - AMIN) * step / 20f
            val mw: Float; val mh: Float; val mx: Float; val my: Float
            val sx: Float; val sy: Float; val sw: Float; val sh: Float
            if (land) { mh = h - 2 * g; mw = mh * a; mx = x + w - g - mw; my = y + g; sx = x; sy = y; sw = w - mw - g; sh = h }
            else { mw = w - 2 * g; mh = mw / a; mx = x + g; my = y + g; sx = x; sy = y + mh + g; sw = w; sh = h - mh - g }
            if (sw <= 2 * g || sh <= 2 * g) continue
            val f = fit(k, sw, sh, g) ?: continue
            val m = mw * mh
            val scale = if (f.area > m / 4f) kotlin.math.sqrt(m / 4f / f.area) else 1f
            val sa = f.area * scale * scale
            val score = min(m / 4f, sa) + m * 1e-3f
            if (best == null || score > best.first) best = score to (listOf(floatArrayOf(mx, my, mw, mh)) + place(k, f, sx, sy, sw, sh, g, scale))
        }
        return best?.second ?: equal(k + 1, x, y, w, h, g)
    }
}
