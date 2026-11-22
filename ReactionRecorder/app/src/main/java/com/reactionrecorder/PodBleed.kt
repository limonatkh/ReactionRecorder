package com.reactionrecorder

/**
 * مين بيحكي فعلًا، لما كل مايك سامع الثاني (قاعدين قريب من بعض).
 *
 * الفكرة: لما A بيحكي لحاله، مايك B بيلقط صوته أوطى بمقدار ثابت تقريبًا (مثلًا ٨ dB) — هاد «التسريب» من A لـ B.
 * بنتعلّم هالمقدار لكل زوج مايكات من اللحظات اللي واضح فيها مين بيحكي، وبعدين بكل لحظة:
 * B بيحكي بس إذا صوته بمايكه أعلى (بهامش) من اللي بيفسّره تسريب A. هيك:
 *  • A لحاله → B مش ناشط (صوته بمايكه = تسريب).
 *  • الاثنين مع بعض → كل واحد أعلى من تسريب الثاني → الاثنين ناشطين.
 * إذا ما في بيانات كفاية نتعلّم منها، بنرجع للقاعدة القديمة (ضمن domDb من الأعلى).
 */
object PodBleed {
    /** هامش فوق التسريب المتوقع عشان نعتبره حكي حقيقي (dB) */
    const val MARGIN = 3f

    /** فرق قوة كل مايك عن المعدل (النسبة ٨٠ من لحظات الكلام)، محدود ±١٢ dB */
    fun offsets(db: Array<FloatArray>, thr: FloatArray): FloatArray {
        val n = db.size
        val loud = FloatArray(n) { p ->
            val v = db[p].filter { it > thr[p] }.sorted()
            if (v.size < 20) Float.NaN else v[(v.size * 0.8).toInt().coerceIn(0, v.size - 1)]
        }
        val known = loud.filter { !it.isNaN() }
        val ref = if (known.isEmpty()) 0f else known.average().toFloat()
        return FloatArray(n) { p -> if (loud[p].isNaN() || known.size < 2) 0f else (loud[p] - ref).coerceIn(-12f, 12f) }
    }

    class Result(val act: Array<BooleanArray>, val leak: Array<FloatArray>, val learned: Array<BooleanArray>)

    /**
     * @param db مستوى كل مايك بكل إطار (dB)، @param thr عتبة الضوضاء لكل مايك، @param off فرق قوة المايك (للمقارنة القديمة)
     * @param same same[p][q] = نفس المايك (شخصين على مايك واحد) → ما في مقارنة بينهم
     */
    fun activity(db: Array<FloatArray>, thr: FloatArray, off: FloatArray, domDb: Float, same: (Int, Int) -> Boolean = { _, _ -> false }): Result {
        val n = db.size
        val frames = db.firstOrNull()?.size ?: 0
        // ١) القاعدة القديمة: أولي — مين الأعلى (بعد توحيد قوة المايكات)
        val first = Array(n) { BooleanArray(frames) }
        for (f in 0 until frames) {
            var mx = -200f
            for (p in 0 until n) if (db[p][f] > thr[p] && db[p][f] - off[p] > mx) mx = db[p][f] - off[p]
            if (mx < -150f) continue
            for (p in 0 until n) first[p][f] = db[p][f] > thr[p] && db[p][f] - off[p] >= mx - domDb
        }
        // ٢) نتعلّم التسريب p→q: بلحظات p فيها أعلى بوضوح (أعلى من الكل بـ ٣ dB بعد التوحيد) وصوته عالي
        val leak = Array(n) { FloatArray(n) { Float.NaN } }
        val learned = Array(n) { BooleanArray(n) }
        for (p in 0 until n) {
            val loudP = db[p].filter { it > thr[p] }.sorted()
            if (loudP.size < 40) continue
            val strong = loudP[(loudP.size * 0.5).toInt()]
            for (q in 0 until n) {
                if (q == p || same(p, q)) continue
                val d = ArrayList<Float>()
                for (f in 0 until frames) {
                    if (db[p][f] < strong) continue
                    var clear = true
                    for (r in 0 until n) if (r != p && !same(p, r) && db[r][f] - off[r] > db[p][f] - off[p] - 3f) { clear = false; break }
                    if (clear) d.add(db[q][f] - db[p][f])
                }
                if (d.size >= 40) {
                    d.sort()
                    // الربع الأعلى (مش الوسيط): أحيانًا q كمان بيحكي شوي، وما بدنا نستهين بالتسريب
                    leak[p][q] = d[(d.size * 0.6).toInt().coerceIn(0, d.size - 1)]
                    learned[p][q] = true
                }
            }
        }
        // ٣) بكل لحظة: q ناشط إذا فوق عتبته، ومش مفسَّر كتسريب من أي مايك ثاني أعلى منه
        val act = Array(n) { BooleanArray(frames) }
        for (f in 0 until frames) for (q in 0 until n) {
            if (db[q][f] <= thr[q]) continue
            var ok = true
            var usedLeak = false
            for (p in 0 until n) {
                if (p == q || same(p, q) || db[p][f] <= thr[p]) continue
                val k = leak[p][q]
                if (k.isNaN()) continue
                usedLeak = true
                if (db[q][f] < db[p][f] + k + MARGIN) { ok = false; break }
            }
            act[q][f] = if (usedLeak) ok else first[q][f]
        }
        return Result(act, leak, learned)
    }

    /** وصف قصير للتسريب (للبيانات اللي بتنسخها) */
    fun describe(r: Result): String {
        val n = r.leak.size
        val parts = ArrayList<String>()
        for (p in 0 until n) for (q in 0 until n) if (r.learned[p][q]) parts += "${p + 1}→${q + 1} ${String.format(java.util.Locale.US, "%.0f", r.leak[p][q])}dB"
        return if (parts.isEmpty()) "-" else parts.joinToString(" ")
    }
}
