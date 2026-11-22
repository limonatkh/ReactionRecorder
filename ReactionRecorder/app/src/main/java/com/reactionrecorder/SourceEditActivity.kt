package com.reactionrecorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * تعديل فيديوهات المصادر نفسها (لكل كاميرا): تدوير، قص (حر أو بمقاس)، وشو يعبّي الفراغ إذا القص غيّر المقاس.
 * التعديل بينطبق على كل اللقطات من هالمصدر، بالمعاينة والتصدير.
 */
class SourceEditActivity : BaseActivity() {
    private lateinit var sid: String
    private lateinit var setup: PodSetup
    private val keys = ArrayList<Pair<String, String>>()   // (p0, devId)
    private var cur = 0
    private var base: Bitmap? = null          // الإطار الأصلي (معدول)
    private var rot = 0
    private var crop: FloatArray? = null
    private var fill = "auto"
    private var aspect = 0f                   // 0 = حر
    private var timePct = 0.4f
    private var outVertical = false
    /** true = بنعدّل «قص الشكل الخاص» (الفترات الدورية من القالب) بدل المصدر نفسه */
    private var lookMode = false
    private lateinit var modeRow: LinearLayout
    private lateinit var cropView: CropView
    private lateinit var result: ResultView
    private lateinit var srcRow: LinearLayout
    private lateinit var opts: LinearLayout
    private lateinit var info: TextView

    private fun dp(v: Int) = UiKit.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sid = intent.getStringExtra("session") ?: run { finish(); return }
        outVertical = intent.getBooleanExtra("vertical", false)
        val s = SessionStore.load(this, sid) ?: run { finish(); return }
        setup = PodcastAnalyzer.setupOf(s)
        for ((i, p) in setup.people.withIndex()) if (p.camDev.isNotEmpty()) keys.add("p$i" to p.camDev)
        for ((i, w) in setup.wides.withIndex()) keys.add("w$i" to w)
        val col = UiKit.page(this, tx("المصادر: تدوير وقص", "Sources: rotate & crop"), tx("التعديل بينطبق على كل اللقطات من هالكاميرا، بالمعاينة والتصدير.", "Changes apply to every shot from this camera, in the preview and the export."))
        if (keys.isEmpty()) { col.addView(UiKit.text(this, tx("ما في كاميرات بهالتصوير", "No cameras in this shoot"), 15f, UiKit.SUB)); return }
        modeRow = LinearLayout(this)
        col.addView(modeRow)
        lookMode = intent.getBooleanExtra("look", false)
        srcRow = LinearLayout(this)
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(srcRow) })
        cropView = CropView(this)
        col.addView(cropView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(10) })
        col.addView(UiKit.text(this, tx("اسحب الإطار الأبيض لتحركه، والزوايا لتكبّره أو تصغّره.", "Drag the white frame to move it, its corners to resize."), 12.5f, UiKit.SUB).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(UiKit.text(this, tx("لقطة من وقت:", "Frame from:"), 13f, UiKit.SUB).apply { setPadding(0, dp(8), 0, 0) })
        col.addView(SeekBar(this).apply {
            max = 100; progress = (timePct * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { timePct = (sb?.progress ?: 40) / 100f; loadFrame() }
            })
        })
        opts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(opts)
        col.addView(UiKit.text(this, tx("كيف رح يطلع", "How it will look"), 16f, UiKit.TEXT, true).apply { setPadding(0, dp(14), 0, dp(4)) })
        result = ResultView(this)
        col.addView(result, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        info = UiKit.text(this, "", 13f, UiKit.SUB)
        col.addView(info)
        val save = UiKit.primary(this, tx("احفظ", "Save")) { save() }
        col.addView(save, UiKit.full(this, save, 14))
        select(0)
    }

    private fun select(i: Int) {
        cur = i
        val k = keys[i].first
        rot = setup.srcRot[k] ?: 0
        fill = setup.srcFill[k] ?: "auto"
        if (lookMode) { crop = setup.lookCrop[k]?.copyOf(); aspect = if (outVertical) 9f / 16f else 16f / 9f }
        else { crop = setup.srcCrop[k]?.copyOf(); aspect = 0f }
        renderMode(); renderSources(); renderOpts(); loadFrame()
    }

    private fun renderMode() {
        modeRow.removeAllViews()
        for ((on, n) in listOf(false to tx("المصدر (دايمًا)", "The source (always)"), true to tx("قص «الشكل الخاص»", "“Special look” crop"))) {
            modeRow.addView(UiKit.chip(this, n, on == lookMode) { if (lookMode != on) { lookMode = on; select(cur) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(6)) })
        }
    }

    private fun nameOf(k: String) = if (k.startsWith("p")) setup.people.getOrNull(k.substring(1).toInt())?.name ?: k else tx("العامة ${k.substring(1).toInt() + 1}", "Wide ${k.substring(1).toInt() + 1}")

    private fun renderSources() {
        srcRow.removeAllViews()
        for ((i, kv) in keys.withIndex()) {
            val edited = (setup.srcRot[kv.first] ?: 0) != 0 || setup.srcCrop[kv.first] != null || (setup.srcFill[kv.first] ?: "auto") != "auto"
            srcRow.addView(UiKit.chip(this, tx("مصدر ${i + 1}: ", "Source ${i + 1}: ") + nameOf(kv.first) + if (edited) " •" else "", i == cur) { select(i) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
    }

    private fun chips(title: String, items: List<Pair<String, String>>, cur: String, set: (String) -> Unit) {
        opts.addView(UiKit.text(this, title, 14f, UiKit.TEXT, true).apply { setPadding(0, dp(12), 0, dp(2)) })
        for (chunk in items.chunked(3)) {
            val r = LinearLayout(this)
            for ((k, n) in chunk) r.addView(UiKit.chip(this, n, k == cur) { set(k); renderOpts(); cropView.invalidate(); result.invalidate(); renderInfo() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
            repeat(3 - chunk.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            opts.addView(r)
        }
    }

    private fun renderOpts() {
        opts.removeAllViews()
        if (lookMode) {
            opts.addView(UiKit.text(this, tx("هاد القص بيطلع بس بالفترات الخاصة اللي بتحددها بالقالب (الإيقاع ← شكل خاص كل فترة). المقاس مقفول على مقاس الفيديو (${if (outVertical) "9:16" else "16:9"}) عشان ما ينمط.", "This crop only shows in the special periods you set in the template (Pacing → Special look). The shape is locked to the video (${if (outVertical) "9:16" else "16:9"}) so nothing stretches."), 12.5f, UiKit.SUB).apply { setPadding(0, dp(8), 0, 0) })
            ensureLookCrop()
            chips(tx("المعاينة على فيديو", "Preview on a video that is"), listOf("h" to tx("بالعرض 16:9", "Landscape 16:9"), "v" to tx("بالطول 9:16", "Portrait 9:16")), if (outVertical) "v" else "h") { outVertical = it == "v"; aspect = if (outVertical) 9f / 16f else 16f / 9f; fitCropToAspect() }
            val same = UiKit.chip(this, tx("نفس القص (نفس المكان والحجم) لكل الكاميرات", "Same crop (place and size) for every camera")) {
                val c = crop ?: return@chip
                for ((k, _) in keys) setup.lookCrop[k] = c.copyOf()
                val ex = setup.toJson().toString(); SessionStore.update(this, sid) { x -> x.extra = ex }
                UiKit.done(this, tx("انحفظ لكل الكاميرات", "Saved for every camera"))
            }
            opts.addView(same, UiKit.full(this, same, 10))
            val reset = UiKit.chip(this, tx("شيل قص الشكل الخاص لهالكاميرا", "Remove this camera's special crop")) { crop = null; setup.lookCrop.remove(keys[cur].first); renderOpts(); cropView.invalidate(); result.invalidate() }
            opts.addView(reset, UiKit.full(this, reset, 6))
            renderInfo()
            return
        }
        val rr = LinearLayout(this)
        rr.addView(UiKit.chipIcon(this, Ic.UNDO, tx("لف لليسار", "Rotate left")) { setRot(rot - 90) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        rr.addView(UiKit.chipIcon(this, Ic.REDO, tx("لف لليمين", "Rotate right")) { setRot(rot + 90) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        opts.addView(rr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        chips(tx("القص", "Crop"), listOf("none" to tx("بدون قص", "No crop"), "0" to tx("حر", "Free"), "1.7778" to "16:9 " + tx("(عرض)", "(wide)"), "0.5625" to "9:16 " + tx("(طول)", "(tall)"), "1" to tx("مربع", "Square"), "1.3333" to "4:3"),
            if (crop == null) "none" else if (aspect == 0f) "0" else listOf("1.7778", "0.5625", "1", "1.3333").firstOrNull { abs(it.toFloat() - aspect) < 0.01f } ?: "0") { k ->
            if (k == "none") { crop = null; aspect = 0f } else { aspect = k.toFloat(); fitCropToAspect() }
        }
        chips(tx("لما المقاس ما بيطابق الفيديو، شو يعبّي الفراغ", "When the shape doesn't match the video, fill the gap with"),
            listOf("auto" to tx("حسب القالب", "By template"), "zoom" to tx("تكبير لحد ما يعبّي", "Zoom to fill"), "blur" to tx("نفسها مموّهة", "Blurred copy"), "color" to tx("لون", "Colour"), "image" to tx("صورة الخلفية", "Background image")), fill) { fill = it }
        chips(tx("المعاينة على فيديو", "Preview on a video that is"), listOf("h" to tx("بالعرض 16:9", "Landscape 16:9"), "v" to tx("بالطول 9:16", "Portrait 9:16")), if (outVertical) "v" else "h") { outVertical = it == "v" }
        val reset = UiKit.chip(this, tx("رجّع المصدر متل ما هو", "Reset this source")) { rot = 0; crop = null; fill = "auto"; aspect = 0f; renderOpts(); loadFrame() }
        opts.addView(reset, UiKit.full(this, reset, 10))
        renderInfo()
    }

    private fun setRot(d: Int) {
        rot = ((d % 360) + 360) % 360
        crop = null
        aspect = 0f
        renderOpts(); applyRotation()
    }

    /** القص بالمقاس المختار، أكبر شي بيتسع بالصورة وبنصها */
    private fun fitCropToAspect() {
        val b = shown ?: return
        if (aspect <= 0f) { if (crop == null) crop = floatArrayOf(0.1f, 0.1f, 0.8f, 0.8f); return }
        val ba = b.width.toFloat() / b.height
        var cw = 1f; var ch = 1f
        if (aspect > ba) ch = ba / aspect else cw = aspect / ba
        val c0 = crop
        var cx = if (c0 != null) c0[0] + c0[2] / 2 else 0.5f; var cy = if (c0 != null) c0[1] + c0[3] / 2 else 0.5f
        cx = cx.coerceIn(cw / 2, 1 - cw / 2); cy = cy.coerceIn(ch / 2, 1 - ch / 2)
        crop = floatArrayOf(cx - cw / 2, cy - ch / 2, cw, ch)
    }

    private var shown: Bitmap? = null

    /** قص الشكل الخاص: لازم يكون في مستطيل بمقاس الفيديو تسحبه (بعد ما تتحمّل الصورة) */
    private fun ensureLookCrop() {
        if (!lookMode || shown == null) return
        if (crop == null) {
            fitCropToAspect()
            crop?.let { c -> val k = 0.6f; crop = floatArrayOf(c[0] + c[2] * (1 - k) / 2, c[1] + c[3] * (1 - k) / 2, c[2] * k, c[3] * k) }
        }
    }

    private fun applyRotation() {
        val b = base ?: return
        shown = if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        ensureLookCrop()
        cropView.invalidate(); result.invalidate(); renderInfo()
    }

    private fun loadFrame() {
        val dev = keys[cur].second
        Thread {
            val bmp = try {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(File(SessionStore.devDir(this, sid, dev), "video.mp4").absolutePath)
                    val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    PodFaces.upright(r, r.getScaledFrameAtTime((durMs * timePct * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 960))
                } finally { r.release() }
            } catch (_: Throwable) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (bmp == null) Toast.makeText(this, tx("ما في فيديو من هالكاميرا (لسا ما وصل؟)", "No video from this camera (not arrived yet?)"), Toast.LENGTH_SHORT).show()
                base = bmp; applyRotation()
            }
        }.start()
    }

    private fun renderInfo() {
        if (!::info.isInitialized) return
        val b = shown ?: base
        val c = crop
        val dims = if (b == null) "" else {
            val w = if (c != null) (b.width * c[2]).toInt() else b.width
            val h = if (c != null) (b.height * c[3]).toInt() else b.height
            tx("المقاس بعد التعديل: ", "Size after editing: ") + if (w > h * 1.05f) tx("بالعرض", "landscape") else if (h > w * 1.05f) tx("بالطول", "portrait") else tx("مربع", "square")
        }
        info.text = dims + tx(" · التدوير: $rot°", " · rotation: $rot°")
    }

    private fun save() {
        val k = keys[cur].first
        if (lookMode) {
            val c = crop
            if (c == null) setup.lookCrop.remove(k) else setup.lookCrop[k] = c.copyOf()
            val ex = setup.toJson().toString()
            SessionStore.update(this, sid) { x -> x.extra = ex }
            UiKit.done(this, tx("انحفظ قص الشكل الخاص لـ${nameOf(k)}", "Saved the special crop for ${nameOf(k)}"))
            return
        }
        // تغيّر التدوير → تحليل الوجوه لهالمصدر بينعاد (على الصورة المعدولة)
        if ((setup.srcRot[k] ?: 0) != rot) try { File(SessionStore.devDir(this, sid, keys[cur].second), "faces.json").delete() } catch (_: Throwable) {}
        if (rot == 0) setup.srcRot.remove(k) else setup.srcRot[k] = rot
        val c = crop
        if (c == null || (c[2] > 0.995f && c[3] > 0.995f)) setup.srcCrop.remove(k) else setup.srcCrop[k] = c.copyOf()
        if (fill == "auto") setup.srcFill.remove(k) else setup.srcFill[k] = fill
        val ex = setup.toJson().toString()
        SessionStore.update(this, sid) { x -> x.extra = ex }
        UiKit.done(this, tx("انحفظ — بينطبق على كل لقطات ${nameOf(k)}", "Saved — applies to all of ${nameOf(k)}'s shots"))
        renderSources()
    }

    /** الإطار مع مربع القص */
    inner class CropView(ctx: Context) : View(ctx) {
        private val pB = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val pShade = Paint().apply { color = 0x99000000.toInt() }
        private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE }
        private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val img = RectF()
        private var mode = 0 // 0 لا شي، 1 تحريك، 2..5 زوايا
        private var lx = 0f; private var ly = 0f

        override fun onDraw(c: Canvas) {
            c.drawColor(0xFF0E0F12.toInt())
            val b = shown ?: return
            val s = min(width / b.width.toFloat(), height / b.height.toFloat())
            val w = b.width * s; val h = b.height * s
            img.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            c.drawBitmap(b, null, img, pB)
            val cr = crop ?: return
            val r = RectF(img.left + cr[0] * w, img.top + cr[1] * h, img.left + (cr[0] + cr[2]) * w, img.top + (cr[1] + cr[3]) * h)
            c.drawRect(img.left, img.top, img.right, r.top, pShade); c.drawRect(img.left, r.bottom, img.right, img.bottom, pShade)
            c.drawRect(img.left, r.top, r.left, r.bottom, pShade); c.drawRect(r.right, r.top, img.right, r.bottom, pShade)
            c.drawRect(r, pLine)
            for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) c.drawCircle(x, y, 14f, pDot)
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val b = shown ?: return true
            if (crop == null) {
                if (lookMode) { ensureLookCrop(); if (crop == null) return true }
                else { crop = floatArrayOf(0.1f, 0.1f, 0.8f, 0.8f); aspect = 0f; renderOpts() }
            }
            val cr = crop!!
            val w = img.width(); val h = img.height()
            if (w <= 0f) return true
            val r = RectF(img.left + cr[0] * w, img.top + cr[1] * h, img.left + (cr[0] + cr[2]) * w, img.top + (cr[1] + cr[3]) * h)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val corners = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)
                    val ci = corners.indexOfFirst { (x, y) -> abs(e.x - x) < 50 && abs(e.y - y) < 50 }
                    mode = if (ci >= 0) 2 + ci else if (r.contains(e.x, e.y)) 1 else 0
                    lx = e.x; ly = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.x - lx) / w; val dy = (e.y - ly) / h
                    lx = e.x; ly = e.y
                    var x0 = cr[0]; var y0 = cr[1]; var x1 = cr[0] + cr[2]; var y1 = cr[1] + cr[3]
                    when (mode) {
                        1 -> { val cw = cr[2]; val ch = cr[3]; x0 = (x0 + dx).coerceIn(0f, 1f - cw); y0 = (y0 + dy).coerceIn(0f, 1f - ch); x1 = x0 + cw; y1 = y0 + ch }
                        2 -> { x0 += dx; y0 += dy }
                        3 -> { x1 += dx; y0 += dy }
                        4 -> { x0 += dx; y1 += dy }
                        5 -> { x1 += dx; y1 += dy }
                    }
                    x0 = x0.coerceIn(0f, 1f); x1 = x1.coerceIn(0f, 1f); y0 = y0.coerceIn(0f, 1f); y1 = y1.coerceIn(0f, 1f)
                    var cw = max(0.08f, x1 - x0); var ch = max(0.08f, y1 - y0)
                    if (aspect > 0f && mode >= 2) {
                        // نحافظ على المقاس (بالبكسل)
                        val ba = b.width.toFloat() / b.height
                        ch = (cw * ba / aspect).coerceAtMost(1f); cw = ch * aspect / ba
                        if (mode == 2 || mode == 4) x0 = (x1 - cw).coerceAtLeast(0f)
                        if (mode == 2 || mode == 3) y0 = (y1 - ch).coerceAtLeast(0f)
                    }
                    if (x0 + cw > 1f) x0 = 1f - cw
                    if (y0 + ch > 1f) y0 = 1f - ch
                    crop = floatArrayOf(x0, y0, cw, ch)
                    invalidate(); result.invalidate(); renderInfo()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
            }
            return true
        }
    }

    /** النتيجة داخل إطار الفيديو (عرض أو طول) مع طريقة تعبئة الفراغ */
    inner class ResultView(ctx: Context) : View(ctx) {
        private val pB = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val pEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x88FFFFFF.toInt() }
        private val pT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 30f; typeface = Fonts.semi }

        override fun onDraw(c: Canvas) {
            c.drawColor(0xFF0E0F12.toInt())
            val b = shown ?: return
            val a = if (outVertical) 9f / 16f else 16f / 9f
            val s = min(width / a, height.toFloat())
            val W = s * a; val H = s
            val fr = RectF((width - W) / 2, (height - H) / 2, (width + W) / 2, (height + H) / 2)
            val cr = crop ?: floatArrayOf(0f, 0f, 1f, 1f)
            val src = android.graphics.Rect((cr[0] * b.width).toInt(), (cr[1] * b.height).toInt(), ((cr[0] + cr[2]) * b.width).toInt(), ((cr[1] + cr[3]) * b.height).toInt())
            val sa = src.width().toFloat() / max(1, src.height())
            val da = W / H
            val ratio = if (sa > da) sa / da else da / sa
            val mode = if (fill == "auto") (if (ratio > 1.3f) "blur" else "zoom") else fill
            c.save(); c.clipRect(fr)
            if (mode == "zoom" || ratio <= 1.02f) {
                var kw = 1f; var kh = 1f
                if (sa > da) kw = da / sa else kh = sa / da
                val sub = android.graphics.Rect((src.left + src.width() * (1 - kw) / 2).toInt(), (src.top + src.height() * (1 - kh) / 2).toInt(), (src.left + src.width() * (1 + kw) / 2).toInt(), (src.top + src.height() * (1 + kh) / 2).toInt())
                c.drawBitmap(b, sub, fr, pB)
            } else {
                when (mode) {
                    "blur" -> { pB.alpha = 90; c.drawBitmap(b, src, fr, pB); pB.alpha = 255 }
                    "color" -> c.drawColor(0xFF1B1D24.toInt())
                    else -> { c.drawColor(0xFF2A2D36.toInt()); c.drawText(tx("صورة الخلفية (من القالب)", "Background image (from the template)"), fr.centerX(), fr.top + 40f, pT) }
                }
                val fw: Float; val fh: Float
                if (sa > da) { fw = W; fh = W / sa } else { fh = H; fw = H * sa }
                c.drawBitmap(b, src, RectF(fr.centerX() - fw / 2, fr.centerY() - fh / 2, fr.centerX() + fw / 2, fr.centerY() + fh / 2), pB)
            }
            c.restore()
            c.drawRect(fr, pEdge)
        }
    }
}
