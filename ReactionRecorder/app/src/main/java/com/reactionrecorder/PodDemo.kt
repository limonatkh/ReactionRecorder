package com.reactionrecorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min


/**
 * نموذج مصغّر كامل لشكل الفيديو بعد المونتاج — بنفس هندسة المونتاج الحقيقي (PodLayout) وبالقالب المختار.
 *  • بالقالب: قصة كاملة بتمر على كل السيناريوهات: العنوان، كشف الوجه والقص عليه، تبديل المتكلم، المقاطعة،
 *    كلام طويل وكسر الملل، التقريب السريع، رد الفعل، النقاش السريع، الصمت (الكاميرا العامة)، وآخر الفيديو.
 *  • بدون تصوير: أشخاص مرسومين بلون واحد (عشان تشوف بالضبط وين الوجه وكيف بيتقص). جوّا مشروع: لقطات من الفيديو نفسه.
 *  • editable: بمحرر القوالب، اسحب المربع العائم لأي مكان وكبّره/صغّره بإصبعين (حر بالكامل).
 */
class LayoutDemoView(ctx: Context) : View(ctx) {
    var landscape = true
    var names: List<String> = listOf("1", "2")
    var hasCam: List<Boolean> = listOf(true, true)
    var pip = true
    var boxes = false
    /** فلوق: switch / grid / mainboxes، أو null للحوار */
    var vlogLayout: String? = null
    var glow = true
    var live = false
    var frames: List<Bitmap?> = emptyList()
    var liveSpeakers: IntArray = IntArray(0)
    /** القالب (إن وُجد بيتحكم بالشكل) */
    var tpl: PodTemplate? = null
    /** -1 = تشغيل؛ رقم = مشهد ثابت؛ HOLD_PIP = مشهد المربع العائم (للسحب) */
    var hold = -1
    var editable = false
    var onTplChanged: (() -> Unit)? = null
    /** وصف المشهد الحالي (للنص تحت النموذج) */
    var onCaption: ((String) -> Unit)? = null
    /** لقطات حقيقية لكل شخص (كم وحدة) + مكان الوجه فيها */
    var photoSets: List<List<Bitmap?>> = emptyList()
    var faceSets: List<List<FloatArray?>> = emptyList()
    /** نصوصك الثابتة */
    var brand: Map<String, String> = emptyMap()
    /** إظهار مناطق أزرار المنصة (تنبيه أحمر بس) */
    var showGuides = false
    /** شو بيعبّي الفراغ لكل مصدر (من «المصادر»): auto/zoom/blur/color/image */
    var srcFills: List<String> = emptyList()
    /** كاميرات فيها أكثر من شخص (بالشاشة المقسومة بتطلع كاملة متل الإخراج) */
    var crowdSet: Set<Int> = emptySet()
    /** الأشخاص المرسومين: مصدرهم بالطول؟ (لتجربة "صوّرت بالطول") */
    var drawnPortrait = false
    /** جوّا مشروع: اللقطات الحقيقية — النموذج بيعرض بالضبط اللي رح يطلع (بدل القصة التجريبية) */
    var realShots: List<PodShot>? = null
    var realA = 0.0
    var realTotal = 0.0
    /** العنوان الحقيقي ("" = ما في عنوان بالفيديو) */
    var realTitle = ""
    /** الكتابة الحقيقية؛ فاضية = ما رح يطلع كتابة بالفيديو */
    var realCaps: List<PodEngine.CapChunk> = emptyList()
    /** أطول مدة لمشهد بالنموذج (ثواني) — اللقطات الأطول بتنعرض مختصرة */
    var realMaxScene = 5.0

    companion object { const val HOLD_PIP = -2 }

    private val t0 = SystemClock.uptimeMillis()
    private var lastCaption = ""
    private var liveMain = 0
    private val colors = intArrayOf(0xFF2F6FED.toInt(), 0xFFE0533D.toInt(), 0xFF2EA86B.toInt(), 0xFFB45CE0.toInt(), 0xFFE0A33D.toInt())
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Fonts.bold }
    private val pName = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Fonts.bold }
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x88FFFFFF.toInt(); strokeWidth = 2f }
    private val pHint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF3D8BFF.toInt(); strokeWidth = 3f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f) }
    private val pDash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 7f), 0f) }
    private val pFig = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pRed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFFFF453A.toInt() }

    /** مشهد: نوع اللقطة + شو بيوضّح */
    private class Scene(
        val type: Int, val main: Int, val others: IntArray, val speak: IntArray, val cap: String, val ord: Int = 0,
        /** "" عادي، title، detect، break، punch، zoombreak، reaction، end */
        val kind: String = "",
        /** الوقت التقريبي بالفيديو (للعناصر الثابتة والشرح) */
        val vt: Double = 0.0,
        val ms: Long = 2000,
    )

    private fun n() = names.size
    private fun nm(i: Int) = names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: tx("شخص ${i + 1}", "Person ${i + 1}")
    private fun usePip() = tpl?.pip ?: pip
    private fun useBoxes() = tpl?.let { it.multi == "boxes" || it.multi == "people" } ?: boxes
    private fun useGlow() = tpl?.glow ?: glow
    private fun clock(s: Double) = String.format(java.util.Locale.US, "%d:%02d", (s / 60).toInt(), (s % 60).toInt())

    private var scriptCache: List<Scene>? = null
    private var scriptSig = ""

    /** القصة حسب القالب — كل سيناريو بيمر */
    private fun script(): List<Scene> {
        val t = tpl
        val rs = realShots
        val sig = (t?.toJson()?.toString() ?: "") + names.joinToString() + vlogLayout + usePip() + useBoxes() + (rs?.joinToString { "${it.start}${it.type}${it.main}" } ?: "")
        if (rs != null && t != null && rs.isNotEmpty()) {
            if (sig == scriptSig) scriptCache?.let { return it }
            scriptSig = sig
            val list = ArrayList<Scene>()
            var ord = 0
            // أول ٩٠ ثانية من الفيديو (أو كله إذا أقصر)
            for (sh in rs) {
                val vt = sh.start - realA
                if (vt > 90.0) break
                val d = sh.dur
                val kind = when (sh.tag) { "zoom" -> "zoombreak"; "break" -> "break"; else -> "" }
                val who = when (sh.type) {
                    PodShot.FULL -> tx("${nm(sh.main)} بملء الشاشة", "${nm(sh.main)} full screen")
                    PodShot.PIP -> tx("${sh.others.joinToString("، ") { nm(it) }} بمربع عائم فوق ${nm(sh.main)}", "${sh.others.joinToString(", ") { nm(it) }} in a box over ${nm(sh.main)}")
                    PodShot.WIDE -> tx("الكاميرا العامة", "Wide camera")
                    else -> tx("شاشة مقسومة: ", "Split: ") + (if (sh.others.isNotEmpty()) sh.others else intArrayOf(sh.main)).joinToString(" | ") { nm(it) }
                }
                val why = when (sh.tag) { "short" -> tx(" (رد قصير)", " (short reply)"); "settle" -> tx(" (تبادلوا الحكي بسرعة)", " (fast exchange)"); "zoom", "break" -> tx(" (كسر الملل)", " (cutaway)"); "reaction" -> tx(" (رد فعل)", " (reaction)"); else -> "" }
                val len = String.format(java.util.Locale.US, "%.1f", d) + tx("ث", "s")
                val cap = "${clock(vt)} · $who$why · $len" + if (d > realMaxScene) tx(" (مختصرة هون)", " (shortened here)") else ""
                val others = if (sh.type == PodShot.MULTI && sh.others.isEmpty()) intArrayOf(sh.main) else sh.others
                list += Scene(sh.type, sh.main, others, if (sh.type == PodShot.PIP) others else intArrayOf(sh.main), cap, if (sh.type == PodShot.PIP) ord++ else ord,
                    kind = kind, vt = vt, ms = (min(d, realMaxScene) * 1000).toLong().coerceAtLeast(300))
            }
            scriptCache = list
            return list
        }
        if (sig == scriptSig) scriptCache?.let { return it }
        scriptSig = sig
        val list = ArrayList<Scene>()
        val n = n()
        // الشاشة المقسومة: بس اللي إلهم كاميرا
        val all = (0 until max(1, n)).filter { hasCam.getOrNull(it) != false }.ifEmpty { listOf(0) }.toIntArray()
        if (t == null) {
            // بدون قالب (شاشة التصوير القديمة)
            if (n <= 1) list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("كاميرا وحدة — الفيديو كله منها", "One camera — the whole video uses it"))
            else {
                list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("${nm(0)} بيحكي ← بملء الشاشة", "${nm(0)} speaks ← full screen"))
                list += Scene(PodShot.FULL, 1, IntArray(0), intArrayOf(1), tx("${nm(1)} بيرد ← الكاميرا بتنتقل عليه", "${nm(1)} replies ← the camera switches"))
                list += if (usePip()) Scene(PodShot.PIP, 1, intArrayOf(0), intArrayOf(0, 1), tx("${nm(0)} قاطعه ← مربع عائم", "${nm(0)} interrupts ← floating box"))
                else Scene(PodShot.MULTI, 0, intArrayOf(0, 1), intArrayOf(0, 1), tx("${nm(0)} قاطعه ← الشاشة بتنقسم", "${nm(0)} interrupts ← split screen"))
                list += Scene(PodShot.MULTI, 0, all, all, tx("نقاش سريع ← الكل مع بعض", "Fast exchange ← everyone together"))
            }
            scriptCache = list; return list
        }
        val wideOk = t.wideAfter >= 0f
        // ١. العنوان
        if (t.titleOn) list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("أول الفيديو: العنوان (${t.hookSecs.toInt()} ث)", "Start: the title (${t.hookSecs.toInt()} s)"), kind = "title", vt = 0.0, ms = 2600)
        // ٢. كشف الوجه والقص عليه
        list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("كشف الوجه: الأخضر وجه ${nm(0)}، والأبيض اللي بيطلع بالفيديو", "Face found: green is ${nm(0)}'s face, white is what shows in the video") +
            (if (t.pipFace) tx("، والأصفر قص المربع العائم على الوجه", ", yellow is the floating box's face crop") else ""), kind = "detect", vt = 2.0, ms = 3400)
        if (t.layout == "grid" || n <= 1) {
            if (n > 1) {
                list += Scene(PodShot.MULTI, 0, all, intArrayOf(0), tx("الكل ظاهر طول الوقت — ${nm(0)} بيحكي", "Everyone on screen — ${nm(0)} speaks"), vt = 6.0)
                list += Scene(PodShot.MULTI, 0, all, intArrayOf(1), tx("${nm(1)} بيحكي — الكتابة على الفاصل", "${nm(1)} speaks — captions on the divider"), vt = 20.0)
            } else {
                list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("${nm(0)} بيحكي", "${nm(0)} speaks"), vt = 6.0)
            }
            if (t.punch != "off") list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("تقريب سريع على كلمة مهمة", "Quick punch-in on a key word"), kind = "punch", vt = 40.0, ms = 1800)
            if (t.breakEvery > 0 && n <= 1) list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("⏱ ${clock(t.breakEvery.toDouble())} كلام متصل ← كسر الملل: تقريب", "⏱ ${clock(t.breakEvery.toDouble())} of talk ← cutaway: punch-in"), kind = "zoombreak", vt = t.breakEvery.toDouble(), ms = 1900)
            list += Scene(if (n > 1) PodShot.MULTI else PodShot.FULL, 0, if (n > 1) all else IntArray(0), intArrayOf(0), tx("آخر الفيديو", "End of the video"), kind = "end", vt = 179.0)
            scriptCache = list; return list
        }
        // ٣. تبديل المتكلم
        // مدة كافية تشوف فيها الاسم بينكتب وبينمسح
        val nameMs = if (t.names != "never") (TypeAnim.total(max(nm(0).length, nm(1).length), t.nameSecs, t.nameAnim) * 1000).toLong() + 300 else 2000L
        list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("${nm(0)} بيحكي ← بملء الشاشة", "${nm(0)} speaks ← full screen") + if (t.faces) tx("، والكادر لاحق وجهه", ", framing follows the face") else "", vt = 6.0, ms = nameMs)
        list += Scene(PodShot.FULL, 1, IntArray(0), intArrayOf(1), tx("${nm(1)} أخذ الدور ← الكاميرا بتنتقل عليه", "${nm(1)} takes the turn ← the camera switches"), vt = 14.0, ms = nameMs)
        // ٤. مقاطعة
        list += if (usePip()) Scene(PodShot.PIP, 1, intArrayOf(0), intArrayOf(0, 1), tx("${nm(0)} قاطعه ← مربع عائم", "${nm(0)} interrupts ← floating box") + if (t.avoidFace) tx(" (ما بيغطي الوجه)", " (never covers the face)") else "", 0, vt = 22.0)
        else Scene(PodShot.MULTI, 0, intArrayOf(0, 1), intArrayOf(0, 1), tx("${nm(0)} قاطعه ← الشاشة بتنقسم", "${nm(0)} interrupts ← the screen splits"), vt = 22.0)
        // ٥. كلام طويل + كسر الملل
        val be = t.breakEvery
        list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("⏱ ${nm(0)} بيحكي كلام طويل…", "⏱ ${nm(0)} talks for a long time…") + if (be <= 0) tx(" (الكاميرا بتضل عليه)", " (the camera stays on them)") else "", vt = 30.0)
        if (be > 0) {
            val to = when (t.breakTo) { "wide" -> if (wideOk) "w" else "z"; "zoom" -> "z"; else -> "l" }
            val lbl = tx("⏱ مرّ ${clock(be.toDouble())} ← كسر الملل: ", "⏱ ${clock(be.toDouble())} passed ← cutaway: ")
            list += when (to) {
                "w" -> Scene(PodShot.WIDE, 0, all, intArrayOf(0), lbl + tx("الكاميرا العامة ${"%.1f".format(t.breakLen)} ث", "wide camera ${"%.1f".format(t.breakLen)} s"), kind = "break", vt = 30.0 + be, ms = 1800)
                "z" -> Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), lbl + tx("تقريب عليه", "punch-in"), kind = "zoombreak", vt = 30.0 + be, ms = 1800)
                else -> Scene(PodShot.FULL, 1, IntArray(0), intArrayOf(0), lbl + tx("لقطة على ${nm(1)} وهو بيسمع", "a shot of ${nm(1)} listening"), kind = "break", vt = 30.0 + be, ms = 1800)
            }
            list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("ورجعنا على ${nm(0)}", "and back to ${nm(0)}"), vt = 33.0 + be, ms = 1400)
        }
        // ٦. تقريب سريع على كلمة مهمة
        if (t.punch != "off") list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("تقريب سريع على كلمة مهمة", "Quick punch-in on a key word"), kind = "punch", vt = 70.0, ms = 1800)
        // ٧. رد فعل
        if (t.intensity == "lively") list += Scene(PodShot.FULL, 1, IntArray(0), intArrayOf(1), tx("${nm(1)} علّق (آه، صح) ← لقطة رد فعل قصيرة", "${nm(1)} reacts ← a short reaction shot"), kind = "reaction", vt = 80.0, ms = 1400)
        // ٨. مقاطعة ثانية (التناوب)
        if (usePip()) list += Scene(PodShot.PIP, 0, intArrayOf(1), intArrayOf(0, 1), tx("${nm(1)} قاطع ← مربع عائم", "${nm(1)} interrupts ← floating box") + if (t.pipPlace != "fixed") tx(" (لاحظ التناوب)", " (note the alternation)") else "", 1, vt = 95.0)
        // ٩. نقاش سريع
        list += Scene(PodShot.MULTI, 0, all, all, tx("نقاش سريع ← الكل مع بعض", "Fast exchange ← everyone together") + if (useBoxes()) tx(" (مربعات فوق خلفية)", " (boxes on a background)") else "", vt = 110.0)
        // ١٠. صمت
        if (wideOk) list += Scene(PodShot.WIDE, 0, all, IntArray(0), tx("صمت ${"%.1f".format(if (t.wideAfter > 0) t.wideAfter else PodEngine.rules(t.intensity).silenceToWide.toFloat())} ث ← الكاميرا العامة (إذا في وحدة)", "Silence ← the wide camera (if there is one)"), vt = 130.0)
        // ١١. آخر الفيديو
        list += Scene(PodShot.FULL, 0, IntArray(0), intArrayOf(0), tx("آخر الفيديو", "End of the video"), kind = "end", vt = 179.0)
        scriptCache = list
        return list
    }

    private fun liveScene(): Scene {
        val sp = liveSpeakers
        val n = n()
        if (n <= 1) return Scene(PodShot.FULL, 0, IntArray(0), sp, if (sp.isEmpty()) tx("ما حدا بيحكي", "Nobody is speaking") else tx("بيحكي", "Speaking"))
        return when {
            sp.isEmpty() -> Scene(PodShot.FULL, liveMain, IntArray(0), sp, tx("ما حدا بيحكي ← بتضل آخر لقطة", "Nobody speaks ← the last shot stays"))
            sp.size == 1 -> { liveMain = sp[0]; Scene(PodShot.FULL, sp[0], IntArray(0), sp, "${nm(sp[0])} " + tx("بيحكي ← بملء الشاشة", "speaks ← full screen")) }
            usePip() -> { val m = if (liveMain in sp) liveMain else sp[0]; liveMain = m; Scene(PodShot.PIP, m, sp.filter { it != m }.toIntArray(), sp, tx("حدا قاطع ← مربع عائم", "Someone interrupts ← floating box")) }
            else -> Scene(PodShot.MULTI, sp[0], sp, sp, tx("بيحكوا مع بعض ← الشاشة مقسومة", "Talking together ← split screen"))
        }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = if (landscape) w * 9 / 16 else min(w * 16 / 9, (resources.displayMetrics.density * 380).toInt())
        setMeasuredDimension(w, h)
    }

    private fun shotFor(cam: Int): Int {
        if (hasCam.getOrNull(cam) == true) return cam
        return hasCam.indexOfFirst { it }.takeIf { it >= 0 } ?: cam
    }

    // ───────── المصدر: صورة حقيقية أو شخص مرسوم ─────────

    private var frameIdx = 0

    private fun photo(p: Int): Bitmap? {
        if (live) return frames.getOrNull(p)
        val l = photoSets.getOrNull(p) ?: return null
        val ok = l.filterNotNull()
        if (ok.isEmpty()) return null
        return l.getOrNull(frameIdx % l.size) ?: ok[0]
    }

    private fun srcAspect(p: Int): Float = photo(p)?.let { it.width.toFloat() / it.height } ?: if (drawnPortrait) 9f / 16f else 16f / 9f

    /** وجه المصدر [x,y,حجم] */
    private fun srcFace(p: Int): FloatArray? {
        if (photo(p) != null) {
            val l = faceSets.getOrNull(p) ?: return null
            return l.getOrNull(frameIdx % max(1, l.size)) ?: l.firstOrNull { it != null }
        }
        // الشخص المرسوم: قاعد مايل شوي عن النص
        return floatArrayOf(if (p % 2 == 0) 0.56f else 0.44f, if (drawnPortrait) 0.40f else 0.36f, if (drawnPortrait) 0.16f else 0.28f)
    }

    /** نفس حساب القص بالإخراج: [x0,y0,x1,y1] من المصدر (0..1) */
    private fun cropFor(p: Int, da: Float, zoom: Float, faceZoom: Float, cell: Boolean): FloatArray {
        val sa = srcAspect(p)
        var kw = 1f; var kh = 1f
        if (sa > da) kw = da / sa else kh = sa / da
        val f = srcFace(p)
        // على الوجه: الزوم حسب حجم الوجه (نفس الإخراج)
        var z = if (faceZoom > 0f && f != null) max(zoom, PodLayout.adaptiveZoom(PodLayout.faceTarget(faceZoom), f[2], kh)) else zoom
        if (faceZoom > 0f && f != null && PodLayout.faceAtEdge(f)) z = min(z, max(1f, zoom))
        kw /= z; kh /= z
        var cx = 0.5f; var cy = 0.5f
        val follow = faceZoom > 0f || cell || pipBoxNow || tpl?.faces == true
        if (f != null && follow) { cx = f[0]; cy = f[1] + f[2] * (if (faceZoom > 0f) 0.05f else 0.15f) }
        cx = cx.coerceIn(kw / 2, 1 - kw / 2); cy = cy.coerceIn(kh / 2, 1 - kh / 2)
        return floatArrayOf(cx - kw / 2, cy - kh / 2, cx + kw / 2, cy + kh / 2)
    }

    /** عم نرسم مربع عائم (دايمًا معبّى وعلى الوجه) */
    private var pipBoxNow = false

    private fun isFit(p: Int, da: Float, faceZoom: Float, cell: Boolean): Boolean {
        if (faceZoom > 0f || pipBoxNow) return false
        val sa = srcAspect(p)
        val ratio = if (sa > da) sa / da else da / sa
        when (srcFills.getOrNull(p) ?: "auto") { "zoom" -> return false; "blur", "color", "image" -> return ratio > 1.02f }
        if (cell && p in crowdSet) return ratio > 1.15f
        if (cell && srcFace(p) != null) return false
        val fr = tpl?.frame ?: "smart"
        return fr == "fit" || (fr == "smart" && ratio > 1.3f)
    }

    /** يرسم الجزء crop من المصدر داخل dst (صورة حقيقية، أو الشخص المرسوم بلون واحد) */
    private fun drawSource(c: Canvas, p: Int, dst: RectF, crop: FloatArray, alpha: Float) {
        val b = photo(p)
        if (b != null) {
            val src = android.graphics.Rect((crop[0] * b.width).toInt(), (crop[1] * b.height).toInt(), (crop[2] * b.width).toInt(), (crop[3] * b.height).toInt())
            pBmp.alpha = (255 * alpha).toInt()
            c.drawBitmap(b, src, dst, pBmp)
            return
        }
        // الشخص المرسوم: المصدر بوحدات (عرض = aspect، ارتفاع = 1)
        val a = srcAspect(p)
        c.save()
        c.clipRect(dst)
        c.translate(dst.left, dst.top)
        c.scale(dst.width() / ((crop[2] - crop[0]) * a), dst.height() / (crop[3] - crop[1]))
        c.translate(-crop[0] * a, -crop[1])
        drawFigure(c, p, a, alpha)
        c.restore()
    }

    /** شخص قاعد بسيط بلون واحد (رأس، كتاف، كرسي، طاولة) على خلفية لون الشخص */
    private fun drawFigure(c: Canvas, p: Int, a: Float, alpha: Float) {
        val base = colors[p % colors.size]
        val al = (255 * alpha).toInt()
        pFig.color = base; pFig.alpha = al
        c.drawRect(0f, 0f, a, 1f, pFig)
        // ظل خفيف للجدار وأرضية
        pFig.color = 0x22000000; c.drawRect(0f, 0.78f, a, 1f, pFig)
        val f = srcFace(p) ?: return
        val fx = f[0] * a; val fy = f[1]; val r = f[2] * 0.5f
        val ink = Color.argb(al, 245, 246, 250)
        pFig.color = ink
        // كرسي وراه
        pFig.color = Color.argb((al * 0.35f).toInt(), 255, 255, 255)
        c.drawRoundRect(RectF(fx - r * 2.6f, fy + r * 0.6f, fx + r * 2.6f, fy + r * 6.5f), r * 0.6f, r * 0.6f, pFig)
        pFig.color = ink
        // جسم (كتاف وصدر)
        val body = Path().apply {
            moveTo(fx - r * 0.55f, fy + r * 0.95f)
            cubicTo(fx - r * 2.3f, fy + r * 1.3f, fx - r * 2.5f, fy + r * 2.4f, fx - r * 2.5f, fy + r * 5.5f)
            lineTo(fx + r * 2.5f, fy + r * 5.5f)
            cubicTo(fx + r * 2.5f, fy + r * 2.4f, fx + r * 2.3f, fy + r * 1.3f, fx + r * 0.55f, fy + r * 0.95f)
            close()
        }
        c.drawPath(body, pFig)
        // رقبة ورأس
        c.drawRect(fx - r * 0.4f, fy + r * 0.5f, fx + r * 0.4f, fy + r * 1.1f, pFig)
        c.drawOval(RectF(fx - r * 0.85f, fy - r, fx + r * 0.85f, fy + r), pFig)
        // عيون صغيرة عشان يبين وين الوجه متّجه
        pFig.color = base; pFig.alpha = al
        c.drawCircle(fx - r * 0.3f, fy - r * 0.1f, r * 0.09f, pFig)
        c.drawCircle(fx + r * 0.3f, fy - r * 0.1f, r * 0.09f, pFig)
        // طاولة
        pFig.color = Color.argb((al * 0.55f).toInt(), 20, 22, 28)
        c.drawRect(0f, min(1f, fy + r * 4.6f), a, 1f, pFig)
    }

    /** مكان الوجه داخل مربع على الشاشة (بعد القص) */
    private fun faceIn(p: Int, dst: RectF, crop: FloatArray): RectF? {
        val f = srcFace(p) ?: return null
        val sa = srcAspect(p)
        val fx = dst.left + (f[0] - crop[0]) / (crop[2] - crop[0]) * dst.width()
        val fy = dst.top + (f[1] - crop[1]) / (crop[3] - crop[1]) * dst.height()
        val hw = f[2] / sa * 1.35f / (crop[2] - crop[0]) * dst.width() / 2f
        val hh = f[2] * 1.5f / (crop[3] - crop[1]) * dst.height() / 2f
        return RectF(fx - hw, fy - hh, fx + hw, fy + hh)
    }

    // ───────── رسم ─────────

    private val frameR = RectF()
    private val pipR = RectF()
    private var sceneStart = 0L
    private var sceneIdx = -1
    /** اللي ما لازم الكتابة تغطيه بهالإطار */
    private val avoid = ArrayList<PodLayout.Box>()
    /** مكان كل طبقة بآخر إطار (نسبة للإطار): pip / name / title / cap / item:N */
    val layerRects = LinkedHashMap<String, RectF>()
    /** محرر الطبقات: كل العناصر ظاهرة، والمختار بس بيتحرك */
    var layersMode = false
    var selLayer: String? = null
    var onLayerSelected: ((String) -> Unit)? = null
    /** المربعات العائمة بهالإطار (العناصر الثابتة بتبعد عنها) */
    private val pipBoxes = ArrayList<PodLayout.Box>()
    private var divider: Float? = null

    private fun currentScene(now: Long): Scene {
        if (live) return liveScene()
        val s = script()
        val i = when {
            hold == HOLD_PIP -> s.indexOfFirst { it.type == PodShot.PIP }.takeIf { it >= 0 } ?: s.indexOfFirst { it.kind == "" }.coerceAtLeast(0)
            hold in s.indices -> hold
            else -> {
                val total = s.sumOf { it.ms }
                var k = (now - t0) % max(1L, total)
                var idx = 0
                while (idx < s.size - 1 && k >= s[idx].ms) { k -= s[idx].ms; idx++ }
                idx
            }
        }
        if (i != sceneIdx) { sceneIdx = i; sceneStart = now; frameIdx = i }
        return s[i]
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val sc = currentScene(now)
        if (sc.cap != lastCaption) { lastCaption = sc.cap; onCaption?.invoke(sc.cap) }
        val a = if (landscape) 16f / 9f else 9f / 16f
        val s = min(width / a, height.toFloat())
        val W = s * a; val H = s
        val ox = (width - W) / 2f; val oy = (height - H) / 2f
        frameR.set(ox, oy, ox + W, oy + H)
        pFill.color = Color.BLACK
        c.drawRect(frameR, pFill)
        val t = tpl
        pGlow.color = t?.glowColor ?: 0xFFFFD60A.toInt()
        pGlow.strokeWidth = max(3f, H * (t?.glowWidth ?: 0.018f))
        val vertical = !landscape
        val glowOn = useGlow() && n() > 1
        val since = now - sceneStart
        pipR.setEmpty(); avoid.clear(); pipBoxes.clear(); layerRects.clear(); divider = null
        c.save(); c.clipRect(frameR)
        // حركة الصورة الرئيسية: انتقال بتكبير، تقريب سريع، تقريب كسر الملل
        var zm = 1f
        if (t != null && t.trans == "zoom" && since < 280 && hold < 0) zm *= 1f + 0.12f * (1f - since / 280f).let { it * it }
        if (sc.kind == "punch") { val e = (since / 120f).coerceIn(0f, 1f); zm *= 1f + ((t?.punchAmt ?: 1.18f) - 1f) * e }
        if (sc.kind == "zoombreak") zm *= 1.3f
        if (sc.kind == "detect" && !live) drawDetect(c, sc, ox, oy, W, H, since)
        else when (sc.type) {
            PodShot.FULL -> tile(c, shotFor(sc.main), ox, oy, W, H, 0f, sc.main in sc.speak && glowOn && sc.kind != "break", 1f, zm, 0f, false, true)
            PodShot.WIDE -> drawWide(c, ox, oy, W, H)
            PodShot.PIP -> {
                // القرار قبل الرسم: إذا المربع رح يغطي الوجه حتى لو صغّرناه → شاشة مقسومة (متل الإخراج)
                val pl = pipPlanFor(sc, W, H, vertical)
                if (pl.split) tile(c, shotFor(sc.others.firstOrNull() ?: sc.main), ox, oy, W, H, 0f, false, 1f, zm, 0f, false, true)
                else {
                    tile(c, shotFor(sc.main), ox, oy, W, H, 0f, false, 1f, zm, 0f, false, true)
                    drawPip(c, sc, ox, oy, W, H, vertical, now, pl)
                }
            }
            else -> drawMulti(c, sc.others, sc, ox, oy, W, H, vertical, glowOn)
        }
        // اسم الشخص
        if (t != null && t.names != "never" && !live && (sc.type == PodShot.FULL || sc.type == PodShot.PIP) && sc.kind != "detect") {
            val first = script().indexOfFirst { (it.type == PodShot.FULL || it.type == PodShot.PIP) && it.kind != "detect" && it.main == sc.main } == sceneIdx
            if (t.names == "switch" || first || layersMode) drawNameTag(c, nm(sc.main), t, ox, oy, W, H, vertical, if (hold >= 0) 0.9 else since / 1000.0)
        }
        if (sc.kind != "detect") drawOverlays(c, sc, ox, oy, W, H, vertical, now)
        if (!pipR.isEmpty) layerRects["pip"] = RectF(pipR.left - ox, pipR.top - oy, pipR.right - ox, pipR.bottom - oy)
        // الطبقات: العنصر المختار مضوّي، والباقي باهت تحته
        if (layersMode) {
            val sel = selLayer?.let { layerRects[it] }
            if (sel != null) {
                val r = RectF(sel).apply { offset(ox, oy); inset(-8f, -8f) }
                val path = Path().apply { fillType = Path.FillType.EVEN_ODD; addRect(frameR, Path.Direction.CW); addRoundRect(r, 10f, 10f, Path.Direction.CW) }
                pFill.color = 0xA6000000.toInt(); c.drawPath(path, pFill)
                c.drawRoundRect(r, 10f, 10f, pHint)
            }
        }
        c.restore()
        c.drawRect(frameR, pEdge)
        if (editable && !pipR.isEmpty) c.drawRect(pipR.left - 6, pipR.top - 6, pipR.right + 6, pipR.bottom + 6, pHint)
        if (editable) for ((k, r) in itemRects) if (k == selItem) c.drawRect(frameR.left + r.left - 6, frameR.top + r.top - 6, frameR.left + r.right + 6, frameR.top + r.bottom + 6, pHint)
        postInvalidateDelayed(50)
    }

    private fun drawMulti(c: Canvas, who: IntArray, sc: Scene, ox: Float, oy: Float, W: Float, H: Float, vertical: Boolean, glowOn: Boolean) {
        val t = tpl
        val bx = useBoxes()
        val pad = if (bx) H * 0.08f else 0f
        if (bx) { pFill.color = t?.bgColor ?: 0xFF1B1D24.toInt(); c.drawRect(frameR, pFill) }
        else if (t != null) { pFill.color = t.gapColor; c.drawRect(frameR, pFill) }
        val gap = if (bx) H * 0.04f else if (t != null) (if (t.gap <= 0f) 0f else max(1f, H * t.gap)) else max(2f, H / 180f)
        val stack = when (t?.splitDir) { "side" -> false; "stack" -> true; else -> vertical }
        val cells = grid(who.size, stack, ox + pad, oy + pad, W - 2 * pad, H - 2 * pad, gap)
        for ((i, r) in cells.withIndex()) tile(c, shotFor(who[i]), r[0], r[1], r[2], r[3], if (bx) H * (t?.boxRadius ?: 0.035f) else 0f, glowOn && who.size > 1 && who[i] in sc.speak, 1f, 1f, 0f, true, true)
        if (cells.size == 2 && cells[1][1] > cells[0][1] + 1f) divider = (cells[0][1] + cells[0][3] + cells[1][1]) / 2f - oy
    }

    /** نفس قرار الإخراج للمربع العائم: الجهة، قديش بيصغر، أو شاشة مقسومة */
    private fun pipPlanFor(sc: Scene, W: Float, H: Float, vertical: Boolean): PodLayout.PipPlan {
        val t = tpl ?: PodTemplate()
        val circle = t.pipShape == "circle" || (t.pipShape == "alternate" && sc.ord % 2 == 0)
        val sz = PodLayout.pipSize(t, vertical, W, circle)
        val mx0 = (t.pipPlace == "person" && (sc.others.firstOrNull() ?: 0) % 2 == 1) || (t.pipPlace == "alternate" && sc.ord % 2 == 1)
        if (editable || live) return PodLayout.PipPlan(mx0, false, 1f, false)
        val mainP = shotFor(sc.main)
        val mf = faceIn(mainP, RectF(0f, 0f, W, H), cropFor(mainP, W / H, 1f, 0f, false))
        return PodLayout.pipPlan(t, W, H, vertical, sc.others.size, sz[0], sz[1], mx0, t.captions, mf?.let { PodLayout.Box(it.left, it.top, it.right, it.bottom) })
    }

    /** مشهد "كشف الوجه": المصدر كامل، الوجه (أخضر)، اللي بيطلع بالفيديو (أبيض)، وقص المربع العائم (أصفر) */
    private fun drawDetect(c: Canvas, sc: Scene, ox: Float, oy: Float, W: Float, H: Float, since: Long) {
        val p = shotFor(sc.main)
        val sa = srcAspect(p)
        pFill.color = 0xFF0E0F12.toInt(); c.drawRect(frameR, pFill)
        // المصدر كامل جوّا الإطار
        val fw: Float; val fh: Float
        if (sa > W / H) { fw = W * 0.92f; fh = fw / sa } else { fh = H * 0.92f; fw = fh * sa }
        val src = RectF(ox + (W - fw) / 2f, oy + (H - fh) / 2f, ox + (W + fw) / 2f, oy + (H + fh) / 2f)
        drawSource(c, p, src, floatArrayOf(0f, 0f, 1f, 1f), 1f)
        val outA = W / H
        val k = (since / 1400f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
        fun box(cr: FloatArray) = RectF(src.left + cr[0] * src.width(), src.top + cr[1] * src.height(), src.left + cr[2] * src.width(), src.top + cr[3] * src.height())
        // الوجه
        faceIn(p, src, floatArrayOf(0f, 0f, 1f, 1f))?.let { fr ->
            pDash.color = 0xFF32D74B.toInt(); c.drawRect(fr, pDash)
            label(c, tx("وجه", "Face"), fr.centerX(), fr.top - 6f, 0xFF32D74B.toInt())
        }
        // اللي بيطلع بالفيديو (بيتحرك من كامل الصورة للقص)
        val fit = isFit(p, outA, 0f, false)
        val target = if (fit) floatArrayOf(0f, 0f, 1f, 1f) else cropFor(p, outA, 1f, 0f, false)
        val cr = floatArrayOf(target[0] * k, target[1] * k, 1f - (1f - target[2]) * k, 1f - (1f - target[3]) * k)
        val wb = box(cr)
        pDash.color = Color.WHITE; pDash.pathEffect = null; pDash.strokeWidth = 4f
        c.drawRect(wb, pDash)
        pDash.pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 7f), 0f); pDash.strokeWidth = 3f
        label(c, if (fit) tx("الصورة كاملة (مع خلفية)", "Whole picture (with background)") else tx("اللي بيطلع بالفيديو", "What shows in the video"), wb.centerX(), wb.bottom + 22f, Color.WHITE)
        // قص المربع العائم على الوجه: خفيف/متوسط/قريب
        val t = tpl
        if (t != null && t.pipFace && since > 900) {
            val circle = t.pipShape == "circle"
            val pa = if (circle) 1f else if (t.faceAspect == "portrait") 0.8f else 1f
            for ((lv, name) in listOf(1.3f to tx("خفيف", "Light"), 1.6f to tx("متوسط", "Medium"), 2.2f to tx("قريب", "Close"))) {
                val sel = kotlin.math.abs(lv - t.faceZoom) < 0.05f
                val crp = cropFor(p, pa, 1f, lv, false)
                val r = box(crp)
                pDash.color = if (sel) 0xFFFFD60A.toInt() else 0x66FFD60A
                pDash.strokeWidth = if (sel) 4f else 2f
                c.drawRect(r, pDash)
                if (sel) label(c, tx("المربع العائم: $name", "Floating box: $name"), r.centerX(), r.top - 6f, 0xFFFFD60A.toInt())
            }
            pDash.strokeWidth = 3f
        }
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, color: Int) {
        pName.textSize = max(11f, frameR.height() * (if (landscape) 0.045f else 0.024f))
        val w = pName.measureText(s) + 12f
        pFill.color = 0xB3000000.toInt()
        val yy = y.coerceIn(frameR.top + pName.textSize + 4f, frameR.bottom - 4f)
        val xx = x.coerceIn(frameR.left + w / 2, frameR.right - w / 2)
        c.drawRoundRect(RectF(xx - w / 2, yy - pName.textSize - 2f, xx + w / 2, yy + 6f), 6f, 6f, pFill)
        pName.color = color
        c.drawText(s, xx - w / 2 + 6f, yy, pName)
        pName.color = Color.WHITE
    }

    /** الكاميرا العامة: الكل بإطار واحد */
    private fun drawWide(c: Canvas, ox: Float, oy: Float, W: Float, H: Float) {
        pFill.color = 0xFF3A3F4B.toInt(); c.drawRect(frameR, pFill)
        val n = max(1, n())
        val cw = W / n
        for (i in 0 until n) {
            val dst = RectF(ox + i * cw, oy + H * 0.25f, ox + (i + 1) * cw, oy + H)
            drawSource(c, shotFor(i), dst, cropFor(shotFor(i), dst.width() / dst.height(), 1f, 0f, true), 1f)
        }
        label(c, tx("الكاميرا العامة", "Wide camera"), ox + W / 2, oy + H * 0.15f, Color.WHITE)
    }

    private fun drawPip(c: Canvas, sc: Scene, ox: Float, oy: Float, W: Float, H: Float, vertical: Boolean, now: Long, plan: PodLayout.PipPlan) {
        val t = tpl ?: PodTemplate()
        val circle = t.pipShape == "circle" || (t.pipShape == "alternate" && sc.ord % 2 == 0)
        val sz = PodLayout.pipSize(t, vertical, W, circle)
        val capsOn = t.captions && !live
        val pw = sz[0] * plan.scale; val ph = sz[1] * plan.scale
        val r = when { circle -> pw / 2f; t.pipShape == "rect" -> 0f; else -> min(pw, ph) * t.pipRadius }
        val b = if (t.pipBorder > 0f) max(1.5f, H * t.pipBorder) else 0f
        val rects = PodLayout.pipRects(t, W, H, vertical, sc.others.size, pw, ph, plan.mirrorX, plan.flipY, capsOn)
        avoid.addAll(rects); pipBoxes.addAll(rects)
        val ap = if (t.pipAnim == "cut" || t.pipAnimMs <= 0 || hold >= 0 || hold == HOLD_PIP) 1f else ((now - sceneStart) / t.pipAnimMs.toFloat()).coerceIn(0f, 1f)
        val e = 1f - (1f - ap) * (1f - ap)
        for ((i, o) in sc.others.withIndex()) {
            val rc = rects.getOrNull(i) ?: continue
            val px = rc.left; val py = rc.top
            var sw = pw; var sh = ph; var x = px; var y = py; var alpha = 1f
            when (t.pipAnim) {
                "fade" -> alpha = e
                "slide" -> x = if (px + pw / 2f < W / 2f) px - (1f - e) * (px + pw) else px + (1f - e) * (W - px)
                "zoom" -> { val k = 0.6f + 0.4f * e; sw = pw * k; sh = ph * k; x = px + (pw - sw) / 2f; y = py + (ph - sh) / 2f; alpha = e }
            }
            val rr = if (circle) sw / 2f else r * (sw / pw)
            val ax = ox + x; val ay = oy + y
            if (i == 0) pipR.set(ax, ay, ax + sw, ay + sh)
            if (t.pipShadow) { pFill.color = Color.argb((100 * alpha).toInt(), 0, 0, 0); c.drawRoundRect(RectF(ax + b + H * 0.006f, ay + b + H * 0.008f, ax + b + H * 0.006f + sw, ay + b + H * 0.008f + sh), rr + b, rr + b, pFill) }
            if (b > 0f) { pFill.color = t.pipBorderColor; pFill.alpha = (255 * alpha).toInt(); c.drawRoundRect(RectF(ax - b, ay - b, ax + sw + b, ay + sh + b), rr + b, rr + b, pFill); pFill.alpha = 255 }
            pipBoxNow = true
            tile(c, shotFor(o), ax, ay, sw, sh, rr, useGlow() && o in sc.speak, alpha, 1f, if (t.pipFace) t.faceZoom else 0f, false, false)
            pipBoxNow = false
            if (showGuides && vertical && PodLayout.inBlocked(rc, true, W, H)) c.drawRoundRect(RectF(ax - 4, ay - 4, ax + sw + 4, ay + sh + 4), rr + 4, rr + 4, pRed)
        }
    }

    /** مربع شخص: نفس قص الإخراج (ملء على الوجه، أو الصورة كاملة مع خلفية) */
    private fun tile(c: Canvas, cam: Int, x: Float, y: Float, w: Float, h: Float, r: Float, glowOn: Boolean, alpha: Float, zoom: Float, faceZoom: Float, cell: Boolean, noteFace: Boolean) {
        val rect = RectF(x, y, x + w, y + h)
        c.save()
        c.clipPath(Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) })
        val da = w / h
        if (isFit(cam, da, faceZoom, cell)) {
            // خلفية: نفسها مغبّشة (تقريبًا: مكبّرة وغامقة)، أو لون
            val bgMode = tpl?.fitBg ?: "blur"
            if (bgMode == "blur") { drawSource(c, cam, rect, cropFor(cam, da, 1f, 0f, false), alpha * 0.35f) }
            else { pFill.color = tpl?.bgColor ?: 0xFF1B1D24.toInt(); pFill.alpha = (255 * alpha).toInt(); c.drawRect(rect, pFill); pFill.alpha = 255 }
            val sa = srcAspect(cam)
            val fw: Float; val fh: Float
            if (sa > da) { fw = w; fh = w / sa } else { fh = h; fw = h * sa }
            val dst = RectF(x + (w - fw) / 2f, y + (h - fh) / 2f, x + (w + fw) / 2f, y + (h + fh) / 2f)
            drawSource(c, cam, dst, floatArrayOf(0f, 0f, 1f, 1f), alpha)
            if (noteFace) faceIn(cam, dst, floatArrayOf(0f, 0f, 1f, 1f))?.let { avoid.add(PodLayout.Box(it.left - frameR.left, it.top - frameR.top, it.right - frameR.left, it.bottom - frameR.top)) }
        } else {
            val crop = cropFor(cam, da, zoom, faceZoom, cell)
            drawSource(c, cam, rect, crop, alpha)
            if (noteFace) faceIn(cam, rect, crop)?.let { avoid.add(PodLayout.Box(it.left - frameR.left, it.top - frameR.top, it.right - frameR.left, it.bottom - frameR.top)) }
        }
        c.restore()
        if (glowOn) { pGlow.alpha = (255 * alpha).toInt(); c.drawRoundRect(rect, r, r, pGlow); pGlow.alpha = 255 }
    }

    private fun drawNameTag(c: Canvas, name: String, t: PodTemplate, ox: Float, oy: Float, W: Float, H: Float, vertical: Boolean, since: Double) {
        // نفس حركة الإخراج: بينكتب حرف حرف، بيثبت، وبينمسح (أو بيظهر ويختفي)
        val st = (if (hold >= 0 || layersMode) name to 1f else TypeAnim.at(name, since, t.nameSecs, t.nameAnim)) ?: return
        val shown = st.first
        if (shown.isEmpty()) return
        pName.textSize = max(10f, (if (vertical) H * 0.030f else H * 0.045f) * t.nameSize)
        pName.typeface = VidFonts.typeface(context, t.nameFont.ifBlank { t.font })
        pName.color = t.nameFg
        val pad = pName.textSize * 0.55f
        val fullW = pName.measureText(name) + pad * 2
        val w = pName.measureText(shown) + pad * 2
        val h = pName.textSize * 1.7f
        val m = min(W, H) * 0.045f
        val x0 = ox + if (t.nameX >= 0f) t.nameX * W - fullW / 2f else when (t.namePos) { "bc" -> (W - fullW) / 2f; "br", "tr" -> W - fullW - m; else -> m }
        val rtl = name.any { it in '\u0600'..'\u06FF' }
        val x = if (rtl) x0 + fullW - w else x0
        val y = oy + if (t.nameY >= 0f) t.nameY * H - h / 2f else if (t.namePos.startsWith("t")) m + (if (vertical) H * 0.06f else 0f) else (if (vertical) H * 0.62f else H - h - m * 2.2f)
        layerRects["name"] = RectF(x0 - ox, y - oy, x0 - ox + fullW, y - oy + h)
        val al = (255 * st.second).toInt()
        if (t.nameBg) { pFill.color = t.nameColor; pFill.alpha = al; c.drawRoundRect(RectF(x, y, x + w, y + h), h / 2f, h / 2f, pFill); pFill.alpha = 255 }
        else { pCapS.textSize = pName.textSize; pCapS.strokeWidth = pName.textSize * 0.14f; pCapS.typeface = pName.typeface; pCapS.textAlign = Paint.Align.LEFT; c.drawText(shown, x + pad, y + pName.textSize * 1.2f, pCapS) }
        pName.alpha = al
        c.drawText(shown, x + pad, y + pName.textSize * 1.2f, pName)
        pName.alpha = 255
        pName.color = Color.WHITE
        pName.typeface = Fonts.bold
        avoid.add(PodLayout.Box(x0 - ox, y - oy, x0 - ox + fullW, y - oy + h))
    }

    private fun grid(n: Int, stack: Boolean, x: Float, y: Float, w: Float, h: Float, gap: Float): List<FloatArray> {
        val g = max(gap, min(w, h) * 0.015f)
        return if (tpl?.multiMain == true && n >= 3) SplitLayout.mainPlus(n - 1, x, y, w, h, g)
        else SplitLayout.equal(n, x, y, w, h, g, tpl?.splitDir ?: "auto")
    }

    // ───────── طبقات القالب فوق الصورة (كتابة، عنوان، عناصر ثابتة، شريط، ومضة) ─────────

    private val sampleWords: List<String> by lazy { tx("هاي معاينة لشكل الكتابة على الفيديو بهالقالب وكيف بتتحرك الكلمات مع الكلام", "This is a preview of how captions look on the video with this template").split(' ') }
    private val pCap = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold }
    private val pCapS = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = 0xE6000000.toInt() }
    private val imgCache = HashMap<String, Bitmap?>()

    /** يرسم كلمات بصف واحد بالنص (يمين لشمال بالعربي)، الكلمة hi ملوّنة */
    private fun drawWords(c: Canvas, words: List<String>, hi: Set<Int>, cx: Float, cy: Float, size: Float, color: Int, hiColor: Int, box: Boolean, maxW: Float) {
        pCap.textSize = size; pCapS.textSize = size; pCapS.strokeWidth = size * 0.14f
        var sp = pCap.measureText(" ")
        var ws = words.map { pCap.measureText(it) }
        var total = ws.sum() + sp * (words.size - 1)
        if (total > maxW) { val k = maxW / total; pCap.textSize = size * k; pCapS.textSize = size * k; sp *= k; ws = ws.map { it * k }; total = maxW }
        val rtl = words.firstOrNull()?.any { it in '؀'..'ۿ' } ?: L.ar
        val base = cy + pCap.textSize * 0.35f
        if (box) { pFill.color = 0x99000000.toInt(); val pd = pCap.textSize * 0.35f; c.drawRoundRect(RectF(cx - total / 2 - pd, cy - pCap.textSize * 0.75f, cx + total / 2 + pd, cy + pCap.textSize * 0.75f), pd, pd, pFill) }
        var x = if (rtl) cx + total / 2 else cx - total / 2
        for ((i, w) in words.withIndex()) {
            val wx = if (rtl) x - ws[i] else x
            if (!box) c.drawText(w, wx, base, pCapS)
            pCap.color = if (i in hi) hiColor else color
            c.drawText(w, wx, base, pCap)
            x = if (rtl) wx - sp else wx + ws[i] + sp
        }
    }

    /** عنوان "أسطر": كل سطر بخلفية على قد عرضه */
    private fun drawLinesTitle(c: Canvas, title: String, t: PodTemplate, W: Float, H: Float, vertical: Boolean, e: Float, wipe: Boolean) {
        val size = (if (vertical) H * 0.048f else H * 0.075f) * t.hookSize
        pCap.textSize = size
        val maxW = W * 0.72f
        val words = title.split(' ')
        val lines = ArrayList<String>(); var cur = ""
        for (w in words) { val tryL = if (cur.isEmpty()) w else "$cur $w"; if (pCap.measureText(tryL) > maxW && cur.isNotEmpty()) { lines += cur; cur = w } else cur = tryL }
        if (cur.isNotEmpty()) lines += cur
        val lh = size * 1.5f
        val cy = if (t.hookY >= 0f) t.hookY * H else when (t.hookPos) { "top" -> H * 0.16f; "bottom" -> H * (if (vertical) 0.68f else 0.80f); else -> H * (if (vertical) 0.45f else 0.5f) }
        var y = cy - lines.size * lh / 2f
        val rtl = title.any { it in '؀'..'ۿ' }
        for (l in lines) {
            val w = pCap.measureText(l) + size * 0.7f
            val x = when { t.hookX >= 0f -> t.hookX * W - w / 2f; t.hookAlign != "start" -> (W - w) / 2f; rtl -> W - w - W * 0.08f; else -> W * 0.08f }
            c.save()
            if (wipe) { val vis = w * e; if (rtl) c.clipRect(x + w - vis, y, x + w, y + lh) else c.clipRect(x, y, x + vis, y + lh) }
            pFill.color = t.hookBg; pFill.alpha = if (wipe) 255 else (255 * e).toInt()
            c.drawRect(x, y, x + w, y + lh, pFill); pFill.alpha = 255
            pCap.color = t.hookColor; pCap.alpha = if (wipe) 255 else (255 * e).toInt()
            c.drawText(l, x + size * 0.35f, y + lh * 0.72f, pCap); pCap.alpha = 255
            c.restore()
            y += lh
        }
    }

    private fun drawOverlays(c: Canvas, sc: Scene, ox: Float, oy: Float, W: Float, H: Float, vertical: Boolean, now: Long) {
        val t = tpl ?: return
        if (live) return
        val since = now - sceneStart
        c.save(); c.translate(ox, oy)
        val tf = VidFonts.typeface(context, t.font)
        pCap.typeface = tf; pCapS.typeface = tf
        // ومضة مع بداية كل مشهد
        if (t.trans == "flash" && since < 160 && hold < 0) { pFill.color = Color.argb((165 * (1f - since / 160f)).toInt().coerceIn(0, 255), 255, 255, 255); c.drawRect(0f, 0f, W, H, pFill) }
        val real = realShots != null && realShots!!.isNotEmpty()
        val hookOn = if (layersMode) t.titleOn else if (real) realTitle.isNotBlank() && sc.vt + since / 1000.0 < t.hookSecs else sc.kind == "title" && t.titleOn
        if (hookOn && t.hookStyle == "big") { pFill.color = 0x73000000; c.drawRect(0f, 0f, W, H, pFill) }
        // العناصر الثابتة (قبل الكتابة عشان الكتابة تبعد عنها)
        val total = if (real && realTotal > 0) realTotal else 180.0
        itemRects.clear()
        if (t.items.isNotEmpty()) {
            val perPos = HashMap<String, Int>()
            val tt = if (real) sc.vt + min(since / 1000.0, sc.ms / 1000.0) else sc.vt + since / 1000.0
            val forceAll = hold >= 0 || hold == HOLD_PIP || layersMode
            for ((ii, it) in t.items.withIndex()) {
                val i = perPos.getOrDefault(it.pos, 0); perPos[it.pos] = i + 1
                // نفس الإخراج: فترة الظهور وطريقة الظهور
                val win = if (forceAll) doubleArrayOf(tt - 5.0, tt + 5.0) else it.window(tt, total)
                if (it.kind == "image") {
                    val bmp = imgCache.getOrPut(it.image) { try { android.graphics.BitmapFactory.decodeFile(it.image) } catch (_: Throwable) { null } }
                    val ih = (if (vertical) H * 0.05f else H * 0.08f) * it.size
                    val iw = if (bmp != null) ih * bmp.width / bmp.height else ih
                    val rr = PodLayout.itemRect(it.pos, iw, ih, W, H, vertical, i, cx = it.x, cy = it.y)
                    avoid.add(rr); itemRects.add(ii to rr); layerRects["item:$ii"] = rr.toRectF()
                    if (win == null) continue
                    val al = if (forceAll || it.anim == "none") 1f else (min(tt - win[0], win[1] - tt) / 0.3).toFloat().coerceIn(0f, 1f)
                    if (al > 0.01f) {
                        if (bmp != null) { pBmp.alpha = (255 * al).toInt(); c.drawBitmap(bmp, null, rr.toRectF(), pBmp) }
                        else { pDash.color = Color.WHITE; c.drawRect(rr.toRectF(), pDash); drawWords(c, listOf(tx("شعار", "Logo")), emptySet(), rr.centerX(), rr.centerY(), ih * 0.4f, Color.WHITE, Color.WHITE, false, rr.width()) }
                    }
                    continue
                }
                var txt = Brand.fill(it.text, brand)
                if (txt.isBlank()) txt = it.text.replace("{link}", tx("رابطك", "your link")).replace("{handle}", "@" + tx("حسابك", "you")).replace("{channel}", tx("اسم البودكاست", "Podcast name")).replace("{series}", tx("اسم السلسلة", "Series")).replace("{guest}", tx("اسم الضيف", "Guest")).replace("{role}", tx("وصف الضيف", "Guest's title"))
                val itf = VidFonts.typeface(context, it.font.ifBlank { t.font })
                pCap.typeface = itf; pCapS.typeface = itf
                val size = (if (vertical) H * 0.026f else H * 0.04f) * it.size
                pCap.textSize = size
                val w = pCap.measureText(txt) + size * 0.7f; val h = size * 1.7f
                val rr = PodLayout.itemRect(it.pos, min(w, W * 0.9f), h, W, H, vertical, i, cx = it.x, cy = it.y)
                avoid.add(rr); itemRects.add(ii to rr); layerRects["item:$ii"] = rr.toRectF()
                if (win != null) {
                    var shown = txt; var al = 1f; var dx = 0f
                    val ramp = (min(tt - win[0], win[1] - tt) / 0.3).toFloat().coerceIn(0f, 1f)
                    if (!forceAll) when (it.anim) {
                        "type" -> shown = TypeAnim.inWindow(txt, tt - win[0], win[1] - win[0])?.first ?: ""
                        "none" -> {}
                        "slide" -> { al = ramp; dx = (1f - ramp) * W * 0.06f * (if (L.ar) 1f else -1f) }
                        else -> al = ramp
                    }
                    if (shown.isNotEmpty() && al > 0.01f) {
                        val sw = pCap.measureText(shown) + size * 0.7f
                        val rtl = txt.any { ch -> ch in '\u0600'..'\u06FF' }
                        val bx = if (rtl) RectF(rr.right - sw, rr.top, rr.right, rr.bottom) else RectF(rr.left, rr.top, rr.left + sw, rr.bottom)
                        bx.offset(dx, 0f)
                        if (it.bg) { pFill.color = 0x8C000000.toInt(); pFill.alpha = (0x8C * al).toInt(); c.drawRoundRect(bx, h * 0.3f, h * 0.3f, pFill); pFill.alpha = 255 }
                        pCap.alpha = (255 * al).toInt(); pCapS.alpha = (230 * al).toInt()
                        drawWords(c, listOf(shown), emptySet(), bx.centerX(), bx.centerY(), size, it.color, it.color, it.bg, bx.width() + size)
                        pCap.alpha = 255; pCapS.alpha = 230
                    }
                }
                pCap.typeface = tf; pCapS.typeface = tf
            }
        }
        // الكتابة: بعيد عن الوجوه والمربع والنصوص، وعلى الفاصل لما يكونوا فوق وتحت
        if (real) {
            // الكتابة الحقيقية بس (إذا ما في تفريغ ما بيطلع إشي — متل الفيديو)
            val tr = realA + sc.vt + min(since / 1000.0, sc.ms / 1000.0)
            val ch = realCaps.firstOrNull { tr >= it.a && tr < it.b + 0.15 }
            if (ch != null && !(hookOn && (t.hookStyle == "big" || t.hookStyle == "lines"))) {
                val size = PodLayout.capTextSize(t, vertical, H, t.capSize)
                val capH = size * (if (t.capStyle == "line") 1.6f else 1.4f)
                val cy = if (t.capY >= 0f) t.capY * H else PodLayout.capPlace(t, vertical, W, H, capH, avoid, divider)
                val cx = if (t.capX >= 0f) t.capX * W else W / 2
                val wi = if (t.capStyle == "line") -1 else ch.wordAt(tr)
                drawWords(c, ch.words, if (wi >= 0) setOf(wi) else ch.emph, cx, cy, size, t.capColor, t.capHi, t.capStyle == "boxed", W * (if (t.capStyle == "line") 0.86f else 0.82f))
                layerRects["cap"] = RectF(cx - W * 0.4f, cy - capH / 2, cx + W * 0.4f, cy + capH / 2)
            }
        } else if (t.captions && (layersMode || (!(hookOn && (t.hookStyle == "big" || t.hookStyle == "lines")) && sc.kind != "end"))) {
            val per = 380L
            val idx = (((now - t0) / per) % sampleWords.size).toInt()
            val size = PodLayout.capTextSize(t, vertical, H, t.capSize)
            val capH = size * (if (t.capStyle == "line") 1.6f else 1.4f)
            val cy = if (t.capY >= 0f) t.capY * H else PodLayout.capPlace(t, vertical, W, H, capH, avoid, divider)
            val cxx = if (t.capX >= 0f) t.capX * W else W / 2
            layerRects["cap"] = RectF(cxx - W * 0.4f, cy - capH / 2, cxx + W * 0.4f, cy + capH / 2)
            if (t.capStyle == "line") {
                val start = (idx / 6) * 6
                val ws = sampleWords.subList(start, min(sampleWords.size, start + 6))
                drawWords(c, ws, setOf(2), cxx, cy, size, t.capColor, t.capHi, false, W * 0.86f)
            } else {
                val start = (idx / 3) * 3
                val ws = sampleWords.subList(start, min(sampleWords.size, start + 3))
                val pop = 0.86f + 0.14f * (((now - t0) % (per * 3)) / 120f).coerceIn(0f, 1f)
                drawWords(c, ws, setOf(idx - start), cxx, cy, size * pop, t.capColor, t.capHi, t.capStyle == "boxed", W * 0.82f)
            }
        }
        // العنوان الافتتاحي (بدخوله وطلوعه)
        if (hookOn) {
            val title = if (real) realTitle else tx("هل مخاطرة المشاريع أعلى من الوظيفة؟", "Is a business riskier than a job?")
            val dur = if (real) ((t.hookSecs - sc.vt) * 1000).toFloat().coerceAtMost(sc.ms.toFloat()) else sc.ms.toFloat()
            val pin = (since / 350f).coerceIn(0f, 1f); val pout = ((dur - since) / 350f).coerceIn(0f, 1f)
            val inPhase = pin < 1f
            val mode = if (inPhase) t.hookIn else t.hookOut
            val e0 = if (inPhase) 1f - (1f - pin) * (1f - pin) else 1f - (1f - pout) * (1f - pout)
            val e = if (mode == "none" || hold >= 0 || layersMode) 1f else e0
            // نفس مكان وحجم الإخراج
            val tcy0 = if (t.hookY >= 0f) t.hookY * H else when (t.hookPos) { "top" -> H * 0.16f; "bottom" -> if (vertical) H * 0.68f else H * 0.80f; else -> if (vertical) H * 0.45f else H * 0.5f }
            // متل الإخراج: العنوان ما بينحط على وجه (إلا إذا انت حطيته بمكان)
            val thh = (if (vertical) H * 0.05f else H * 0.08f) * t.hookSize * 1.4f
            val tcy = if (t.hookY >= 0f) tcy0 else listOf(tcy0, H * 0.16f, if (vertical) H * 0.74f else H * 0.82f).minByOrNull { c ->
                avoid.sumOf { f -> PodLayout.overlap(PodLayout.Box(W * 0.1f, c - thh / 2f, W * 0.9f, c + thh / 2f), f).toDouble() } + kotlin.math.abs(c - tcy0) * 0.001 } ?: tcy0
            val tcx = if (t.hookX >= 0f) t.hookX * W else W / 2
            when (t.hookStyle) {
                "lines" -> {
                    c.save(); if (mode == "slide") c.translate((1f - e) * W * 0.18f * (if (L.ar) 1f else -1f), 0f)
                    drawLinesTitle(c, title, t, W, H, vertical, if (mode == "wipe" || mode == "fade" || mode == "slide") e else 1f, mode == "wipe")
                    c.restore()
                }
                "big" -> { pCap.alpha = (255 * e).toInt(); drawWords(c, title.split(' '), emptySet(), tcx, tcy, (if (vertical) H * 0.052f else H * 0.08f) * t.hookSize, t.hookColor, t.capHi, false, W * 0.86f); pCap.alpha = 255 }
                else -> drawWords(c, title.split(' '), emptySet(), tcx, tcy, (if (vertical) H * 0.034f else H * 0.06f) * t.hookSize, t.hookColor, t.capHi, true, W * 0.80f)
            }
            val th = (if (vertical) H * 0.05f else H * 0.08f) * t.hookSize * 1.6f
            layerRects["title"] = RectF(tcx - W * 0.4f, tcy - th / 2, tcx + W * 0.4f, tcy + th / 2)
        }
        // شريط التقدّم
        if (t.progress) {
            val bh = max(3f, H * 0.008f)
            pFill.color = 0x40FFFFFF; c.drawRect(0f, 0f, W, bh, pFill)
            pFill.color = t.capHi; c.drawRect(0f, 0f, W * (sc.vt / total).toFloat().coerceIn(0f, 1f), bh, pFill)
        }
        // مناطق أزرار المنصة (تنبيه بس)
        if (vertical && showGuides) {
            pFill.color = 0x30FF3B30
            for (z in PodLayout.blocked(true, W, H)) c.drawRect(z.toRectF(), pFill)
        }
        c.restore()
        pCap.typeface = Fonts.bold; pCapS.typeface = Fonts.bold
    }

    // ───────── محرر الطبقات ─────────
    private var grabDx = 0f
    private var grabDy = 0f
    private var layerDrag = false
    private val layerScaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val t = tpl ?: return false
            val k = d.scaleFactor
            when (val key = selLayer ?: return false) {
                "pip" -> t.pipSize = (t.pipSize * k).coerceIn(0.1f, 0.9f)
                "name" -> t.nameSize = (t.nameSize * k).coerceIn(0.4f, 5f)
                "title" -> t.hookSize = (t.hookSize * k).coerceIn(0.4f, 4f)
                "cap" -> t.capSize = (t.capSize * k).coerceIn(0.4f, 3f)
                else -> t.items.getOrNull(key.removePrefix("item:").toIntOrNull() ?: -1)?.let { it.size = (it.size * k).coerceIn(0.3f, 8f) }
            }
            onTplChanged?.invoke(); return true
        }
    })

    /** يحط مركز الطبقة بمكان (نسبة 0..1 من الصورة) — بدون أي حدود */
    private fun moveLayer(key: String, fx0: Float, fy0: Float) {
        val t = tpl ?: return
        val fx = fx0.coerceIn(0f, 1f); val fy = fy0.coerceIn(0f, 1f)
        when (key) {
            "pip" -> {
                val s = script().getOrNull(sceneIdx)
                val mirrored = s != null && ((t.pipPlace == "person" && (s.others.firstOrNull() ?: 0) % 2 == 1) || (t.pipPlace == "alternate" && s.ord % 2 == 1))
                t.pipPos = "custom"; t.pipX = if (mirrored) 1f - fx else fx; t.pipY = fy
            }
            "name" -> { t.nameX = fx; t.nameY = fy }
            "title" -> { t.hookX = fx; t.hookY = fy }
            "cap" -> { t.capX = fx; t.capY = fy }
            else -> t.items.getOrNull(key.removePrefix("item:").toIntOrNull() ?: -1)?.let { it.x = fx; it.y = fy }
        }
        onTplChanged?.invoke()
    }

    private fun layerTouch(e: MotionEvent): Boolean {
        layerScaler.onTouchEvent(e)
        parent?.requestDisallowInterceptTouchEvent(true)
        if (e.pointerCount > 1) { layerDrag = false; return true }
        val lx = e.x - frameR.left; val ly = e.y - frameR.top
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // ضغطة على عنصر بتختاره (الأصغر أول، عشان الشعار فوق المربع بينمسك)
                val hit = layerRects.entries.filter { (_, r) -> lx >= r.left - 16 && lx <= r.right + 16 && ly >= r.top - 16 && ly <= r.bottom + 16 }.minByOrNull { it.value.width() * it.value.height() }?.key
                if (hit != null && hit != selLayer) { selLayer = hit; onLayerSelected?.invoke(hit) }
                val r = selLayer?.let { layerRects[it] }
                layerDrag = r != null
                if (r != null) { grabDx = r.centerX() - lx; grabDy = r.centerY() - ly }
            }
            MotionEvent.ACTION_MOVE -> if (layerDrag) selLayer?.let { moveLayer(it, (lx + grabDx) / frameR.width(), (ly + grabDy) / frameR.height()) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { layerDrag = false; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    // ───────── محرر القوالب: سحب المربع (حر بالكامل) وتكبيره ─────────

    private var dragging = false
    /** مكان كل عنصر ثابت بآخر إطار (للسحب): رقمه + مستطيله */
    private val itemRects = ArrayList<Pair<Int, PodLayout.Box>>()
    /** العنصر اللي عم ينسحب (-1 = المربع العائم) */
    private var dragItem = -1
    /** محرر القوالب: العنصر المختار (بيتكبّر بإصبعين) */
    var selItem = -1
    var onItemSelected: ((Int) -> Unit)? = null
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val t = tpl ?: return false
            val it = t.items.getOrNull(selItem)
            if (it != null) { it.size = (it.size * d.scaleFactor).coerceIn(0.3f, 6f); onTplChanged?.invoke(); return true }
            t.pipSize = (t.pipSize * d.scaleFactor).coerceIn(0.12f, 0.8f)
            onTplChanged?.invoke(); return true
        }
    })

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (layersMode && tpl != null) return layerTouch(e)
        if (!editable || tpl == null) return super.onTouchEvent(e)
        scaler.onTouchEvent(e)
        if (e.pointerCount > 1) { dragging = false; parent?.requestDisallowInterceptTouchEvent(true); return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // عنصر ثابت (شعار، نص) تحت الإصبع؟
                val lx = e.x - frameR.left; val ly = e.y - frameR.top
                val hit = itemRects.lastOrNull { (_, r) -> lx >= r.left - 20 && lx <= r.right + 20 && ly >= r.top - 20 && ly <= r.bottom + 20 }
                if (hit != null) {
                    dragItem = hit.first; dragging = true; selItem = hit.first; onItemSelected?.invoke(hit.first)
                    parent?.requestDisallowInterceptTouchEvent(true); return true
                }
                dragItem = -1
                dragging = !pipR.isEmpty && RectF(pipR).apply { inset(-30f, -30f) }.contains(e.x, e.y)
                if (dragging) { selItem = -1; onItemSelected?.invoke(-1); parent?.requestDisallowInterceptTouchEvent(true) }
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging && dragItem >= 0) {
                val it = tpl!!.items.getOrNull(dragItem)
                if (it != null) {
                    it.x = ((e.x - frameR.left) / frameR.width()).coerceIn(0.02f, 0.98f)
                    it.y = ((e.y - frameR.top) / frameR.height()).coerceIn(0.02f, 0.98f)
                    onTplChanged?.invoke()
                }
            } else if (dragging) {
                val t = tpl!!
                val s = script().getOrNull(sceneIdx)
                var fx = ((e.x - frameR.left) / frameR.width()).coerceIn(0f, 1f)
                val fy = ((e.y - frameR.top) / frameR.height()).coerceIn(0f, 1f)
                // إذا المشهد معكوس (حسب الشخص/بالتناوب) نخزن المكان الأصلي
                val mirrored = s != null && ((t.pipPlace == "person" && (s.others.firstOrNull() ?: 0) % 2 == 1) || (t.pipPlace == "alternate" && s.ord % 2 == 1))
                if (mirrored) fx = 1f - fx
                t.pipPos = "custom"; t.pipX = fx; t.pipY = fy
                onTplChanged?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
}

class LiveSpeakers {
    private val until = HashMap<Int, Long>()

    fun update(levels: IntArray): IntArray {
        val now = SystemClock.elapsedRealtime()
        val top = levels.maxOrNull() ?: 0
        val thr = max(1800, (top * 0.45f).toInt())
        for ((i, l) in levels.withIndex()) if (l >= thr) until[i] = now + 500
        return levels.indices.filter { (until[it] ?: 0L) > now }.toIntArray()
    }
}

/** صور حقيقية من التصوير (لنموذج القالب) — كم لقطة لكل شخص من أوقات مختلفة، مع مكان الوجه */
object DemoPhotos {
    class Pack(val sid: String, val frames: List<List<Bitmap?>>, val faces: List<List<FloatArray?>>, val names: List<String>, val sig: String = "", val fills: List<String> = emptyList())
    @Volatile private var cache: Pack? = null

    /** بيشتغل بخيط خلفي. sid = جلسة معيّنة، وإلا آخر جلسة فيها فيديو كاميرات */
    fun load(ctx: Context, sid: String? = null): Pack? {
        // تعديلات المصادر (تدوير/قص) جزء من المفتاح: لو عدّلت مصدر بتنعاد الصور
        fun sigOf(st: PodSetup) = st.people.joinToString { it.name } + st.srcRot.toSortedMap().toString() + st.srcCrop.toSortedMap().mapValues { it.value.joinToString() } + st.srcFill.toSortedMap()
        cache?.let { c -> if (sid == null || c.sid == sid) { val ss = SessionStore.load(ctx, c.sid); if (ss != null && sigOf(PodcastAnalyzer.setupOf(ss)) == c.sig) return c } }
        val sessions = if (sid != null) listOfNotNull(SessionStore.load(ctx, sid)) else try { SessionStore.list(ctx).sortedByDescending { it.wallMs }.take(20) } catch (_: Throwable) { emptyList() }
        for (s in sessions) {
            val setup = PodcastAnalyzer.setupOf(s)
            val files = setup.people.map { p -> if (p.camDev.isEmpty()) null else java.io.File(SessionStore.devDir(ctx, s.id, p.camDev), "video.mp4").takeIf { it.exists() && it.length() > 100_000 } }
            if (files.count { it != null } == 0) continue
            val frames = ArrayList<List<Bitmap?>>(); val faces = ArrayList<List<FloatArray?>>()
            for ((i, f) in files.withIndex()) {
                if (f == null) { frames.add(emptyList()); faces.add(emptyList()); continue }
                val fl = ArrayList<Bitmap?>(); val fc = ArrayList<FloatArray?>()
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(f.absolutePath)
                    val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val track = try { PodFaces.load(ctx, s.id, setup.people[i].camDev) } catch (_: Throwable) { null }
                    for (k in listOf(0.3, 0.55, 0.8)) {
                        val ms = dur * k
                        val raw = try { PodFaces.upright(r, r.getScaledFrameAtTime((ms * 1000).toLong(), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)) } catch (_: Throwable) { null }
                        // نفس تعديل المصدر اللي بالإخراج: تدوير ثم قص
                        val key = "p$i"
                        val deg = setup.srcRot[key] ?: 0
                        val crop = setup.srcCrop[key]
                        var bmp = raw
                        if (bmp != null && deg % 360 != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
                        if (bmp != null && crop != null) bmp = try { Bitmap.createBitmap(bmp, (crop[0] * bmp.width).toInt().coerceIn(0, bmp.width - 2), (crop[1] * bmp.height).toInt().coerceIn(0, bmp.height - 2),
                            (crop[2] * bmp.width).toInt().coerceIn(2, bmp.width), (crop[3] * bmp.height).toInt().coerceIn(2, bmp.height)) } catch (_: Throwable) { bmp }
                        fl.add(bmp)
                        var face = if (raw != null) SrcGeo.mapFace(track?.at(ms), deg, crop, raw.width, raw.height) else null
                        if (face == null && bmp != null) face = detect(bmp)
                        fc.add(face)
                    }
                } catch (_: Throwable) {} finally { try { r.release() } catch (_: Throwable) {} }
                frames.add(fl); faces.add(fc)
            }
            val set = Pack(s.id, frames, faces, setup.people.map { it.name }, sigOf(setup), setup.people.indices.map { setup.srcFill["p$it"] ?: "auto" })
            cache = set
            return set
        }
        return null
    }

    private fun detect(b: Bitmap): FloatArray? = try {
        val det = com.google.mlkit.vision.face.FaceDetection.getClient(com.google.mlkit.vision.face.FaceDetectorOptions.Builder().setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
        try {
            val fs = com.google.android.gms.tasks.Tasks.await(det.process(com.google.mlkit.vision.common.InputImage.fromBitmap(b, 0)))
            fs.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.let { f -> floatArrayOf(f.boundingBox.exactCenterX() / b.width, f.boundingBox.exactCenterY() / b.height, f.boundingBox.height().toFloat() / b.height) }
        } finally { det.close() }
    } catch (_: Throwable) { null }

    /** يحط الصور بنموذج */
    fun apply(v: LayoutDemoView, s: Pack?) {
        if (s == null) return
        v.photoSets = s.frames; v.faceSets = s.faces; v.srcFills = s.fills
        if (s.names.size >= 2) v.names = s.names
        v.hasCam = s.frames.map { it.any { b -> b != null } }
        v.invalidate()
    }
}
