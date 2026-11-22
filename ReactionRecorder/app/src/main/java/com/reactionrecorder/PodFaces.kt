package com.reactionrecorder

import android.content.Context
import android.media.MediaMetadataRetriever
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * مسار الوجه في فيديو كاميرا: مركز الوجه (0..1 من أعلى اليسار) وحجمه، بزمن الملف (ms).
 * يُنعَّم ويُثبَّت (منطقة ميتة) حتى لا يهتز الكادر.
 */
class FaceTrack(val t: DoubleArray, val x: FloatArray, val y: FloatArray, val s: FloatArray) {
    /** نسبة اللقطات اللي فيها أكثر من وجه واضح (كاميرا جايبة شخصين) — -1 = مش معروف */
    var multi = -1f
    /**
     * الناس بالكادر لكل عيّنة (نفس أوقات t)، قنوات G_*: حدود كل الوجوه الواضحة، عددها، الوجه اليسار واليمين، وفتحة الفم لكل واحد.
     * null = تحليل قديم بدونها.
     */
    var g: Array<FloatArray>? = null

    /** قيم الناس بلحظة ms (مستكملة بين العيّنات) أو null */
    fun groupAt(ms: Double): FloatArray? {
        val gg = g ?: return null
        if (t.isEmpty() || gg.isEmpty() || gg[0].size != t.size) return null
        if (ms <= t[0]) return FloatArray(G_N) { gg[it][0] }
        if (ms >= t[t.size - 1]) return FloatArray(G_N) { gg[it][t.size - 1] }
        var lo = 0; var hi = t.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (t[m] <= ms) lo = m else hi = m }
        val k = ((ms - t[lo]) / (t[hi] - t[lo])).toFloat().coerceIn(0f, 1f)
        return FloatArray(G_N) { c -> val a = gg[c][lo]; val b = gg[c][hi]; if (c == G_CNT) (if (k < 0.5f) a else b) else a + (b - a) * k }
    }

    /** مين فمه بيتحرك أكثر حوالين ms (اليسار 0 / اليمين 1 / مش واضح -1) — للكاميرا اللي فيها شخصين */
    fun talkerSide(ms: Double, spanMs: Double = 5000.0): Int {
        val gg = g ?: return -1
        var n = 0; var sl = 0.0; var sr = 0.0; var ml = 0.0; var mr = 0.0
        val idx = t.indices.filter { kotlin.math.abs(t[it] - ms) <= spanMs && gg[G_CNT][it] >= 2f && !gg[G_LO][it].isNaN() && !gg[G_RO][it].isNaN() }
        if (idx.size < 3) return -1
        for (i in idx) { ml += gg[G_LO][i]; mr += gg[G_RO][i]; n++ }
        ml /= n; mr /= n
        for (i in idx) { sl += (gg[G_LO][i] - ml).let { it * it }; sr += (gg[G_RO][i] - mr).let { it * it } }
        return when { sl > sr * 1.6 && sl > 1e-5 -> 0; sr > sl * 1.6 && sr > 1e-5 -> 1; else -> -1 }
    }

    fun at(ms: Double): FloatArray? {
        if (t.isEmpty()) return null
        if (ms <= t[0]) return floatArrayOf(x[0], y[0], s[0])
        if (ms >= t[t.size - 1]) return floatArrayOf(x[t.size - 1], y[t.size - 1], s[t.size - 1])
        var lo = 0
        var hi = t.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (t[m] <= ms) lo = m else hi = m }
        val k = ((ms - t[lo]) / (t[hi] - t[lo])).toFloat().coerceIn(0f, 1f)
        return floatArrayOf(x[lo] + (x[hi] - x[lo]) * k, y[lo] + (y[hi] - y[lo]) * k, s[lo] + (s[hi] - s[lo]) * k)
    }

    fun toJson(): JSONObject = JSONObject().put("m", multi.toDouble()).also { o -> g?.let { gg -> o.put("g", JSONArray().also { a -> for (ch in gg) a.put(JSONArray(ch.map { v -> if (v.isNaN()) -9.0 else v.toDouble() })) }) } }.put("t", JSONArray(t.toList())).put("x", JSONArray(x.map { it.toDouble() }))
        .put("y", JSONArray(y.map { it.toDouble() })).put("s", JSONArray(s.map { it.toDouble() }))

    companion object {
        fun fromJson(o: JSONObject): FaceTrack {
            val t = o.getJSONArray("t"); val x = o.getJSONArray("x"); val y = o.getJSONArray("y"); val s = o.getJSONArray("s")
            return FaceTrack(DoubleArray(t.length()) { t.getDouble(it) }, FloatArray(x.length()) { x.getDouble(it).toFloat() },
                FloatArray(y.length()) { y.getDouble(it).toFloat() }, FloatArray(s.length()) { s.getDouble(it).toFloat() }).also { tr ->
                tr.multi = o.optDouble("m", -1.0).toFloat()
                o.optJSONArray("g")?.let { a -> if (a.length() == G_N) tr.g = Array(G_N) { c -> val ch = a.getJSONArray(c); FloatArray(ch.length()) { i -> ch.getDouble(i).toFloat().let { v -> if (v < -8f) Float.NaN else v } } } }
            }
        }

        const val G_X0 = 0; const val G_Y0 = 1; const val G_X1 = 2; const val G_Y1 = 3; const val G_CNT = 4
        const val G_LX = 5; const val G_LY = 6; const val G_LS = 7; const val G_RX = 8; const val G_RY = 9; const val G_RS = 10
        const val G_LO = 11; const val G_RO = 12; const val G_N = 13

        /** قنوات الناس: الفراغات بتتعبى من أقرب عيّنة، والحدود بتتنعّم (بدون نطّ) */
        fun buildGroup(raw: List<FloatArray>): Array<FloatArray>? {
            val n = raw.size
            if (n == 0 || raw.none { !it[G_X0].isNaN() }) return null
            val out = Array(G_N) { FloatArray(n) }
            for (c in 0 until G_N) {
                val idx = (0 until n).filter { !raw[it][c].isNaN() }
                for (i in 0 until n) out[c][i] = if (idx.isEmpty()) Float.NaN else if (!raw[i][c].isNaN()) raw[i][c] else if (c == G_LO || c == G_RO) Float.NaN else raw[idx.minByOrNull { kotlin.math.abs(it - i) }!!][c]
                if (c in listOf(G_X0, G_Y0, G_X1, G_Y1, G_LX, G_LY, G_LS, G_RX, G_RY, G_RS) && idx.isNotEmpty()) {
                    val a = out[c]; val k = 0.4f
                    for (i in 1 until n) a[i] = a[i - 1] + (a[i] - a[i - 1]) * k
                    for (i in n - 2 downTo 0) a[i] = a[i + 1] + (a[i] - a[i + 1]) * k
                }
            }
            // العدد: الأكثر حوالين كل عيّنة (عشان وجه بيختفي لحظة ما يغيّر الكادر)
            val cnt = out[G_CNT].copyOf()
            for (i in 0 until n) {
                val w = (maxOf(0, i - 2)..minOf(n - 1, i + 2)).map { raw[it][G_CNT] }.filter { !it.isNaN() }
                out[G_CNT][i] = if (w.isEmpty()) cnt[i] else w.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
            }
            return out
        }

        /** من عيّنات خام (قد تحوي NaN حيث لم يُكتشف وجه) إلى مسار ناعم ثابت */
        fun build(raw: List<FloatArray>, times: List<Double>): FaceTrack? {
            val idx = raw.indices.filter { !raw[it][0].isNaN() }
            if (idx.isEmpty()) return null
            val n = raw.size
            val x = FloatArray(n); val y = FloatArray(n); val s = FloatArray(n)
            // ملء الفراغات بأقرب قيمة معروفة
            var last = idx.first()
            for (i in 0 until n) {
                if (!raw[i][0].isNaN()) last = i
                val src = if (raw[i][0].isNaN()) (idx.minByOrNull { abs(it - i) } ?: last) else i
                x[i] = raw[src][0]; y[i] = raw[src][1]; s[i] = raw[src][2]
            }
            // تنعيم أمامي وخلفي (بلا تأخير)
            fun smooth(a: FloatArray) {
                val k = 0.35f
                for (i in 1 until n) a[i] = a[i - 1] + (a[i] - a[i - 1]) * k
                for (i in n - 2 downTo 0) a[i] = a[i + 1] + (a[i] - a[i + 1]) * k
            }
            smooth(x); smooth(y); smooth(s)
            // منطقة ميتة: لا نتحرك إلا لتغيّر واضح (> 4%)
            fun dead(a: FloatArray) {
                var held = a[0]
                for (i in a.indices) { if (abs(a[i] - held) > 0.04f) held += (a[i] - held) * 0.5f; a[i] = held }
            }
            dead(x); dead(y)
            return FaceTrack(times.toDoubleArray(), x, y, s)
        }
    }
}

/** اكتشاف الوجه بـ ML Kit (على الجهاز، بدون إنترنت) — اختياري ويُحفظ لإعادة الاستعمال */
object PodFaces {
    private fun file(ctx: Context, sid: String, dev: String) = File(SessionStore.devDir(ctx, sid, dev), "faces.json")

    fun load(ctx: Context, sid: String, dev: String): FaceTrack? = try {
        val f = file(ctx, sid, dev)
        // نسخة ٢: الإطارات مصحّحة الاتجاه (القديمة ممكن تكون محللة على صور مايلة → بنعيد تحليلها)
        if (f.exists()) JSONObject(f.readText()).takeIf { it.optInt("v", 1) >= 2 }?.let { FaceTrack.fromJson(it) } else null
    } catch (_: Throwable) { null }

    /**
     * يحلل فيديو جهاز بين fromMs و toMs (زمن الملف) بعيّنة كل stepMs، ويدمج مع ما حُلل سابقًا.
     * يعيد عدد العينات التي وُجد فيها وجه.
     */
    /**
     * بعض الأجهزة بترجّع الإطار بدون ما تطبّق تدوير الفيديو (فبيطلع مايل عالجنب).
     * بنقارن اتجاه الصورة مع الاتجاه المتوقع وبندوّرها إذا لزم — عشان الوجوه والمعاينات تطابق الإخراج.
     */
    fun upright(r: MediaMetadataRetriever, bmp: android.graphics.Bitmap?): android.graphics.Bitmap? {
        if (bmp == null) return null
        return try {
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (rot % 360 == 0 || w == 0 || h == 0 || w == h) return bmp
            val q = rot % 180 != 0
            val wantLandscape = if (q) h > w else w > h
            val isLandscape = bmp.width > bmp.height
            if (wantLandscape == isLandscape) bmp
            else android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true).also { if (it !== bmp) bmp.recycle() }
        } catch (_: Throwable) { bmp }
    }

    /**
     * فحص سريع (٨ صور): الكاميرا جايبة أكثر من شخص؟ نسبة الصور اللي فيها وجهين واضحين أو أكثر.
     * النتيجة محفوظة بملف صغير، وإذا في تحليل وجوه كامل بنستعمله.
     */
    fun crowdProbe(ctx: Context, sid: String, dev: String, rotDeg: Int = 0): Float {
        load(ctx, sid, dev)?.multi?.takeIf { it >= 0f }?.let { return it }
        val cache = File(SessionStore.devDir(ctx, sid, dev), "crowd.txt")
        try { if (cache.exists()) return cache.readText().trim().toFloat() } catch (_: Throwable) {}
        val vf = File(SessionStore.devDir(ctx, sid, dev), "video.mp4")
        if (!vf.exists()) return -1f
        val det = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.08f).build())
        val r = MediaMetadataRetriever()
        var any = 0; var many = 0
        try {
            r.setDataSource(vf.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: return -1f
            for (k in 1..8) {
                val raw0 = upright(r, r.getScaledFrameAtTime((dur * k / 9.0 * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)) ?: continue
                val deg = ((rotDeg % 360) + 360) % 360
                val bmp = if (deg == 0) raw0 else android.graphics.Bitmap.createBitmap(raw0, 0, 0, raw0.width, raw0.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
                try {
                    val fs = Tasks.await(det.process(InputImage.fromBitmap(bmp, 0)))
                    val big = fs.maxByOrNull { it.boundingBox.height() }
                    if (big != null) { any++; if (fs.count { it.boundingBox.height() >= big.boundingBox.height() * 0.45f } >= 2) many++ }
                } catch (_: Throwable) {} finally { bmp.recycle(); if (raw0 !== bmp) raw0.recycle() }
            }
        } catch (_: Throwable) { return -1f } finally { try { r.release() } catch (_: Throwable) {}; try { det.close() } catch (_: Throwable) {} }
        val v = if (any == 0) 0f else many.toFloat() / any
        try { cache.writeText(v.toString()) } catch (_: Throwable) {}
        return v
    }

    fun analyze(ctx: Context, sid: String, dev: String, fromMs: Double, toMs: Double, stepMs: Double, progress: (Float) -> Unit, cancelled: () -> Boolean, rotDeg: Int = 0): Int {
        val vf = File(SessionStore.devDir(ctx, sid, dev), "video.mp4")
        if (!vf.exists()) return 0
        val old = load(ctx, sid, dev)
        val times = ArrayList<Double>()
        val raw = ArrayList<FloatArray>()
        val graw = ArrayList<FloatArray>()
        val og = old?.g?.takeIf { it[0].size == old.t.size }
        if (old != null) for (i in old.t.indices) if (old.t[i] < fromMs || old.t[i] > toMs) {
            times.add(old.t[i]); raw.add(floatArrayOf(old.x[i], old.y[i], old.s[i]))
            graw.add(FloatArray(FaceTrack.G_N) { c -> og?.get(c)?.get(i) ?: Float.NaN })
        }
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()

        val det = FaceDetection.getClient(opts)
        val r = MediaMetadataRetriever()
        var found = 0
        var many = 0
        try {
            r.setDataSource(vf.absolutePath)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: toMs
            var t = maxOf(0.0, fromMs)
            val end = minOf(toMs, durMs)
            val total = maxOf(1.0, end - t)
            val start = t
            while (t <= end) {
                if (cancelled()) break
                val raw0 = upright(r, r.getScaledFrameAtTime((t * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360))
                // المصدر معدّل بالتدوير؟ بنحلل الصورة معدولة (الكاشف بيلقط الوجوه المستقيمة بس) وبنرجّع المكان للأصل
                val deg = ((rotDeg % 360) + 360) % 360
                val bmp = if (raw0 == null || deg == 0) raw0 else android.graphics.Bitmap.createBitmap(raw0, 0, 0, raw0.width, raw0.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
                var v = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
                val gv = FloatArray(FaceTrack.G_N) { Float.NaN }
                if (bmp != null) {
                    try {
                        val faces = Tasks.await(det.process(InputImage.fromBitmap(bmp, 0)))
                        val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        // وجه ثاني واضح (مش بعيد بالخلفية) = الكاميرا جايبة أكثر من شخص
                        if (f != null && faces.count { it.boundingBox.height() >= f.boundingBox.height() * 0.45f } >= 2) many++
                        // كل الناس الواضحين بالكادر (مش البعيدين بالخلفية): حدودهم، عددهم، يسار ويمين، وفتحة الفم
                        if (f != null) {
                            val ppl = faces.filter { it.boundingBox.height() >= f.boundingBox.height() * 0.45f }.sortedBy { it.boundingBox.exactCenterX() }
                            fun pt(x: Float, y: Float): FloatArray = if (deg == 0 || raw0 == null) floatArrayOf(x / bmp.width, y / bmp.height) else SrcGeo.rotPoint(x / bmp.width, y / bmp.height, 360 - deg)
                            val sc = if (deg == 0 || raw0 == null) 1f else bmp.height / raw0.height.toFloat()
                            var x0 = 1f; var y0 = 1f; var x1 = 0f; var y1 = 0f
                            for (q in ppl) {
                                val b = q.boundingBox
                                for ((cx, cy) in listOf(b.left.toFloat() to b.top.toFloat(), b.right.toFloat() to b.bottom.toFloat(), b.left.toFloat() to b.bottom.toFloat(), b.right.toFloat() to b.top.toFloat())) {
                                    val pp = pt(cx, cy); x0 = minOf(x0, pp[0]); y0 = minOf(y0, pp[1]); x1 = maxOf(x1, pp[0]); y1 = maxOf(y1, pp[1])
                                }
                            }
                            gv[FaceTrack.G_X0] = x0.coerceIn(0f, 1f); gv[FaceTrack.G_Y0] = y0.coerceIn(0f, 1f); gv[FaceTrack.G_X1] = x1.coerceIn(0f, 1f); gv[FaceTrack.G_Y1] = y1.coerceIn(0f, 1f)
                            gv[FaceTrack.G_CNT] = ppl.size.toFloat()
                            fun mouth(q: com.google.mlkit.vision.face.Face): Float {
                                val l = q.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT)?.position
                                val r = q.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)?.position
                                val btm = q.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM)?.position
                                if (l == null || r == null || btm == null) return Float.NaN
                                return ((btm.y - (l.y + r.y) / 2f) / q.boundingBox.height().coerceAtLeast(1)).coerceIn(-1f, 1f)
                            }
                            if (ppl.size >= 2) {
                                val L = ppl.first(); val R = ppl.last()
                                val lp = pt(L.boundingBox.exactCenterX(), L.boundingBox.exactCenterY()); val rp = pt(R.boundingBox.exactCenterX(), R.boundingBox.exactCenterY())
                                gv[FaceTrack.G_LX] = lp[0]; gv[FaceTrack.G_LY] = lp[1]; gv[FaceTrack.G_LS] = L.boundingBox.height().toFloat() / bmp.height * sc
                                gv[FaceTrack.G_RX] = rp[0]; gv[FaceTrack.G_RY] = rp[1]; gv[FaceTrack.G_RS] = R.boundingBox.height().toFloat() / bmp.height * sc
                                gv[FaceTrack.G_LO] = mouth(L); gv[FaceTrack.G_RO] = mouth(R)
                            }
                        }
                        if (f != null) {
                            val b = f.boundingBox
                            val x = b.exactCenterX() / bmp.width; val y = b.exactCenterY() / bmp.height; val sz = b.height().toFloat() / bmp.height
                            v = if (deg == 0 || raw0 == null) floatArrayOf(x, y, sz) else {
                                val back = SrcGeo.rotPoint(x, y, 360 - deg)
                                floatArrayOf(back[0], back[1], sz * bmp.height / raw0.height.toFloat())
                            }
                            found++
                        }
                    } catch (_: Throwable) {
                    } finally {
                        bmp.recycle()
                        if (raw0 !== bmp) raw0?.recycle()
                    }
                }
                times.add(t); raw.add(v); graw.add(gv)
                t += stepMs
                progress(((t - start) / total).toFloat().coerceIn(0f, 1f))
            }
        } finally {
            try { r.release() } catch (_: Throwable) {}
            try { det.close() } catch (_: Throwable) {}
        }
        val order = times.indices.sortedBy { times[it] }
        val tr = FaceTrack.build(order.map { raw[it] }, order.map { times[it] })
        if (tr != null) tr.multi = if (found > 0) many.toFloat() / found else (old?.multi ?: -1f)
        if (tr != null && graw.size == times.size) tr.g = FaceTrack.buildGroup(order.map { graw[it] })
        if (tr != null) try { file(ctx, sid, dev).writeText(tr.toJson().put("v", 2).toString()) } catch (_: Throwable) {}
        return found
    }
}
