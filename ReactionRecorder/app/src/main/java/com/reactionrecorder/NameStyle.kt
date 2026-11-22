package com.reactionrecorder

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import java.util.Locale

/**
 * شكل اسم الشخص على الفيديو: إمتى بيظهر، الحجم، الخط، اللون، الخلفية، الحركة، المدة، والمكان.
 * نفس الشاشة من محرر القالب ومن صفحة المونتاج، وفيها معاينة حية فوق.
 */
object NameStyle {
    private val COLORS = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD60A.toInt(), 0xFF3D8BFF.toInt(), 0xFFFF453A.toInt(), 0xFF32D74B.toInt(), 0xFFB45CE0.toInt(), 0xFF1B1D24.toInt())

    fun summary(t: PodTemplate): String = when (t.names) {
        "never" -> tx("ما بيطلع", "Hidden")
        else -> (if (t.names == "first") tx("أول مرة بيظهر", "First appearance") else tx("كل ما يظهر", "Every time")) +
            " · " + VidFonts.label(t.nameFont.ifBlank { t.font }).substringBefore(" (") +
            " · " + (if (t.nameBg) tx("بخلفية", "with background") else tx("بدون خلفية", "no background")) +
            " · ${(t.nameSize * 100).toInt()}%" + " · " + when (t.nameAnim) { "type" -> tx("كيبورد", "typing"); "fade" -> tx("تلاشي", "fade"); else -> tx("بدون حركة", "no motion") }
    }

    /** المعاينة الحية: الاسم بنفس الحركة والشكل فوق خلفية رمادية */
    class Preview(a: Activity, private val t: PodTemplate, private val name: String) : View(a) {
        private val t0 = SystemClock.uptimeMillis()
        private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = 0xE6000000.toInt() }
        private val pBg = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onMeasure(w: Int, h: Int) { val ww = MeasureSpec.getSize(w); setMeasuredDimension(ww, ww * 9 / 16) }
        override fun onDraw(c: Canvas) {
            val W = width.toFloat(); val H = height.toFloat()
            pBg.color = 0xFF4A505C.toInt(); c.drawRect(0f, 0f, W, H, pBg)
            pBg.color = 0xFF6B7280.toInt(); c.drawOval(RectF(W * 0.36f, H * 0.18f, W * 0.64f, H * 0.62f), pBg); c.drawRoundRect(RectF(W * 0.22f, H * 0.6f, W * 0.78f, H * 1.2f), H * 0.2f, H * 0.2f, pBg)
            val total = TypeAnim.total(name.length, t.nameSecs, t.nameAnim) + 0.8
            val since = ((SystemClock.uptimeMillis() - t0) / 1000.0) % total
            val st = TypeAnim.at(name, since, t.nameSecs, t.nameAnim)
            if (st != null && st.first.isNotEmpty()) {
                val size = H * 0.045f * t.nameSize
                pTxt.textSize = size; pTxt.typeface = VidFonts.typeface(context, t.nameFont.ifBlank { t.font }); pTxt.color = t.nameFg; pTxt.alpha = (255 * st.second).toInt()
                pStroke.textSize = size; pStroke.typeface = pTxt.typeface; pStroke.strokeWidth = size * 0.14f
                val pad = size * 0.55f
                val fullW = pTxt.measureText(name) + pad * 2; val w = pTxt.measureText(st.first) + pad * 2; val h = size * 1.7f
                val m = minOf(W, H) * 0.045f
                val x0 = if (t.nameX >= 0f) t.nameX * W - fullW / 2f else when (t.namePos) { "bc" -> (W - fullW) / 2f; "br", "tr" -> W - fullW - m; else -> m }
                val rtl = name.any { it in '؀'..'ۿ' }
                val x = if (rtl) x0 + fullW - w else x0
                val y = if (t.nameY >= 0f) t.nameY * H - h / 2f else if (t.namePos.startsWith("t")) m else H - h - m * 2.2f
                if (t.nameBg) { pBg.color = t.nameColor; pBg.alpha = pTxt.alpha; c.drawRoundRect(RectF(x, y, x + w, y + h), h / 2f, h / 2f, pBg); pBg.alpha = 255 }
                else c.drawText(st.first, x + pad, y + size * 1.2f, pStroke)
                c.drawText(st.first, x + pad, y + size * 1.2f, pTxt)
            }
            postInvalidateDelayed(40)
        }
    }

    /** الشاشة نفسها. onChange بعد كل تغيير، onDone لما تسكّر */
    fun show(a: Activity, t: PodTemplate, sampleName: String, landscape: Boolean, onChange: () -> Unit, onDone: () -> Unit) {
        fun dp(v: Int) = UiKit.dp(a, v)
        val d = UiKit.sheet(a, tx("اسم الشخص على الفيديو", "Name on the video")) { col, _ ->
            val pv = Preview(a, t, sampleName.ifBlank { tx("عماد الخطيب", "Sam Lee") })
            col.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            fun changed() { pv.invalidate(); onChange() }
            fun title(s: String) = col.addView(UiKit.text(a, s, 14.5f, UiKit.TEXT, true).apply { setPadding(0, dp(12), 0, dp(2)) })
            /** أزرار اختيار بتتلوّن فورًا */
            fun group(items: List<Pair<String, String>>, cur: () -> String, set: (String) -> Unit) {
                val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                fun draw() {
                    box.removeAllViews()
                    for (chunk in items.chunked(3)) {
                        val r = LinearLayout(a)
                        for ((k, n) in chunk) r.addView(UiKit.chip(a, n, k == cur()) { set(k); changed(); draw() }.apply { textSize = 13.5f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
                        repeat(3 - chunk.size) { r.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)) }
                        box.addView(r)
                    }
                }
                draw(); col.addView(box)
            }
            fun colors(cur: () -> Int, set: (Int) -> Unit) {
                val r = LinearLayout(a)
                fun draw() {
                    r.removeAllViews()
                    for (c in COLORS) r.addView(View(a).apply {
                        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(17).toFloat(); setColor(c); setStroke(dp(if (c == cur()) 3 else 1), Color.parseColor(if (c == cur()) UiKit.BLUE else UiKit.LINE)) }
                        setOnClickListener { set(c); changed(); draw() }
                    }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(0, dp(6), dp(8), 0) })
                }
                draw(); col.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(r) })
            }
            fun slider(label: String, min: Float, max: Float, cur: Float, fmt: (Float) -> String, set: (Float) -> Unit) {
                val tv = UiKit.text(a, "$label: ${fmt(cur)}", 14f, UiKit.TEXT)
                col.addView(tv.apply { setPadding(0, dp(10), 0, 0) })
                col.addView(SeekBar(a).apply {
                    this.max = 100; progress = (((cur - min) / (max - min)) * 100).toInt().coerceIn(0, 100); minimumHeight = dp(40)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (!u) return; val v = min + (max - min) * p / 100f; set(v); tv.text = "$label: ${fmt(v)}"; changed() }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                })
            }

            title(tx("إمتى بيظهر", "When"))
            group(listOf("first" to tx("أول مرة بيظهر", "First appearance"), "switch" to tx("كل ما يظهر", "Every time"), "never" to tx("ما بيطلع", "Never")), { t.names }) { t.names = it }
            slider(tx("الحجم", "Size"), 0.5f, 4f, t.nameSize, { "${(it * 100).toInt()}%" }) { t.nameSize = (it * 20).toInt() / 20f }
            title(tx("الخط", "Font"))
            group(listOf("" to tx("خط القالب", "Template font")) + VidFonts.list.map { it.id to it.label.substringBefore(" (") } +
                (if (t.font.startsWith("file:")) listOf(t.font to VidFonts.label(t.font)) else emptyList()), { t.nameFont }) { t.nameFont = it }
            title(tx("لون الكتابة", "Text colour"))
            colors({ t.nameFg }) { t.nameFg = it }
            title(tx("الخلفية", "Background"))
            group(listOf("0" to tx("بدون خلفية", "No background"), "1" to tx("بخلفية", "With background")), { if (t.nameBg) "1" else "0" }) { t.nameBg = it == "1" }
            col.addView(UiKit.text(a, tx("بدون خلفية: الكتابة بإطار غامق خفيف عشان تنقرا فوق أي صورة. لون الخلفية:", "No background: text gets a soft dark outline so it reads on any picture. Background colour:"), 12f, UiKit.SUB))
            colors({ t.nameColor }) { t.nameColor = it }
            title(tx("طريقة الظهور", "How it appears"))
            group(listOf("type" to tx("كيبورد", "Typing"), "fade" to tx("تلاشي", "Fade"), "none" to tx("بدون حركة", "No motion")), { t.nameAnim }) { t.nameAnim = it }
            slider(tx("بيضل ثابت", "Stays"), 1f, 8f, t.nameSecs, { String.format(Locale.US, "%.1f ث", it) }) { t.nameSecs = (it * 2).toInt() / 2f }
            title(tx("المكان: اضغط وين بدك إياه", "Position: tap where you want it"))
            col.addView(PosPad(a, landscape, t.nameX, t.nameY) { fx, fy -> t.nameX = fx; t.nameY = fy; changed() })
            val reset = UiKit.chip(a, tx("↺ المكان الافتراضي (تحت يمين)", "↺ Default spot (bottom right)")) { t.nameX = -1f; t.nameY = -1f; t.namePos = "br"; changed() }
            col.addView(reset, UiKit.full(a, reset, 6))
        }
        d.setOnDismissListener { onDone() }
    }
}
