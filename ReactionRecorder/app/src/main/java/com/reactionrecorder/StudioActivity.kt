package com.reactionrecorder

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Locale

/**
 * استوديو جهاز مساعد: معاينة حية (صورة + صوت) وضبط كامل عن بُعد قبل بدء التسجيل وأثناءه:
 * الكاميرا (أمامية/خلفية)، الجودة، الاتجاه، التدوير، المرآة، التقريب، الإضاءة، الفلاش، ومستوى/مصدر الميكروفون.
 */
class StudioActivity : BaseActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var devId: String
    private lateinit var img: ImageView
    private lateinit var info: TextView
    private lateinit var level: ProgressBar
    private lateinit var body: LinearLayout
    private var listen = false
    private var builtFor = ""
    private var lastFrameAt = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devId = intent.getStringExtra("dev") ?: ""
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(20)) }
        val sv = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0E0F11")); addView(col) }
        setContentView(sv)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sv) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }
        info = txt("", 15f)
        col.addView(info)
        img = ImageView(this).apply { setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER }
        col.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        col.addView(UiKit.chip(this, "⛶ " + tx("شوف الإطار كامل (كل اللي بيتسجّل)", "See the full frame (everything recorded)")) {
            startActivity(android.content.Intent(this, FramingActivity::class.java).putExtra("dev", devId))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(level, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
    }

    override fun onResume() {
        super.onResume()
        applyPreview()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        val on = SatHub.links[devId]?.stripOn == true
        SatHub.setPreview(devId, on, false, on)
    }

    private fun applyPreview() {
        val l = SatHub.links[devId] ?: return
        SatHub.setPreview(devId, l.role == "both", listen, true)
    }

    private fun txt(t: String, size: Float = 13f, color: String = "#F2F2F3") = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, dp(8), 0, dp(2))
    }

    private fun pill(b: Button, sel: Boolean) {
        b.isAllCaps = false; b.setTextColor(Color.WHITE); b.textSize = 12f
        b.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor(if (sel) "#FF3B30" else "#2A2D36")) }
    }

    private fun send(build: JSONObject.() -> Unit) {
        SatHub.sendCfg(devId, JSONObject().apply(build))
    }

    private fun row(vararg v: android.view.View) = LinearLayout(this).apply {
        for (x in v) addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
    }

    private fun seek(title: String, min: Float, max: Float, cur: Float, steps: Int, fmt: (Float) -> String, onDone: (Float) -> Unit) {
        val tv = txt("$title: ${fmt(cur)}")
        body.addView(tv)
        val sb = SeekBar(this).apply {
            this.max = steps
            progress = (((cur - min) / (max - min)) * steps).toInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { tv.text = "$title: ${fmt(min + (max - min) * p / steps)}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { onDone(min + (max - min) * (s?.progress ?: 0) / steps) }
            })
        }
        body.addView(sb)
    }

    /** يبني أدوات التحكم بحسب إعدادات الجهاز الحالية (تُعاد عند تغيّر الإعدادات المستلمة) */
    private fun buildControls(c: JSONObject, caps: JSONObject?, role: String, rec: Boolean) {
        body.removeAllViews()
        val front = c.optBoolean("front", true)
        val q = c.optInt("q", 720)
        val portrait = c.optBoolean("portrait", true)
        val rot = c.optInt("rot", 0)
        val mirror = c.optBoolean("mirror", false)

        if (rec) body.addView(txt("🔴 " + tx("يسجّل — الكاميرا والجودة والاتجاه ثابتة حتى يتوقف", "Recording — camera side, quality and orientation are locked until it stops"), 12f, "#FFD21F"))

        if (role == "both") {
            body.addView(txt(tx("الكاميرا", "Camera"), 15f))
            val bF = Button(this).apply { text = tx("الأمامية", "Front"); pill(this, front); isEnabled = !rec; setOnClickListener { send { put("front", true) } } }
            val bB = Button(this).apply { text = tx("الخلفية", "Back"); pill(this, !front); isEnabled = !rec; setOnClickListener { send { put("front", false) } } }
            body.addView(row(bF, bB))

            body.addView(txt(tx("الجودة", "Quality")))
            val qs = listOf(480, 720, 1080)
            body.addView(row(*qs.map { v -> Button(this).apply { text = "${v}p"; pill(this, q == v); isEnabled = !rec; setOnClickListener { send { put("q", v) } } } }.toTypedArray()))

            body.addView(txt(tx("اتجاه الفيديو المسجّل", "Orientation of the recorded video")))
            val orient = c.optString("orient", if (portrait) "portrait" else "landscape")
            val bA = Button(this).apply { text = tx("تلقائي", "Auto"); pill(this, orient == "auto"); isEnabled = !rec; setOnClickListener { send { put("orient", "auto") } } }
            val bP = Button(this).apply { text = tx("عمودي", "Portrait"); pill(this, orient == "portrait"); isEnabled = !rec; setOnClickListener { send { put("orient", "portrait") } } }
            val bL = Button(this).apply { text = tx("أفقي", "Landscape"); pill(this, orient == "landscape"); isEnabled = !rec; setOnClickListener { send { put("orient", "landscape") } } }
            body.addView(row(bA, bP, bL))
            body.addView(txt(tx("تلقائي = حسب وضع الجوال لحظة البدء، ثم يثبت.", "Auto = how the phone is held when recording starts, then fixed."), 11f, "#9A9CA3"))

            body.addView(txt(tx("تدوير الصورة (الآن $rot°)", "Rotate the picture (currently $rot°)")))
            val bR = Button(this).apply { text = "↻ 90°"; pill(this, false); setOnClickListener { send { put("rot", (rot + 90) % 360) } } }
            val bR2 = Button(this).apply { text = "180°"; pill(this, false); setOnClickListener { send { put("rot", (rot + 180) % 360) } } }
            val bR0 = Button(this).apply { text = tx("صفر", "Reset"); pill(this, false); setOnClickListener { send { put("rot", 0) } } }
            body.addView(row(bR, bR2, bR0))

            val bM = Button(this).apply { text = if (mirror) tx("المرآة: تعمل", "Mirror: on") else tx("المرآة: مطفأة", "Mirror: off"); pill(this, mirror); setOnClickListener { send { put("mirror", !mirror) } } }
            val torch = c.optBoolean("torch", false)
            val bT = Button(this).apply {
                text = if (torch) tx("الفلاش: يعمل", "Flash: on") else tx("الفلاش: مطفأ", "Flash: off"); pill(this, torch)
                isEnabled = caps?.optBoolean("flash", false) ?: false
                setOnClickListener { send { put("torch", !torch) } }
            }
            body.addView(txt(tx("المرآة / الفلاش", "Mirror / flash")))
            body.addView(row(bM, bT))

            val zmax = (caps?.optDouble("zmax", 1.0) ?: 1.0).toFloat()
            val zmin = (caps?.optDouble("zmin", 1.0) ?: 1.0).toFloat()
            if (zmax > zmin + 0.05f) {
                seek(tx("التقريب", "Zoom"), zmin, zmax, c.optDouble("zoom", 1.0).toFloat().coerceIn(zmin, zmax), 100, { String.format(Locale.US, "%.1fx", it) }) { v -> send { put("zoom", v.toDouble()) } }
            }
            val emin = caps?.optInt("emin", 0) ?: 0
            val emax = caps?.optInt("emax", 0) ?: 0
            if (emax > emin) {
                seek(tx("الإضاءة", "Brightness"), emin.toFloat(), emax.toFloat(), c.optInt("exp", 0).toFloat().coerceIn(emin.toFloat(), emax.toFloat()), emax - emin, { String.format(Locale.US, "%+d", it.toInt()) }) { v -> send { put("exp", v.toInt()) } }
            }
        }

        body.addView(txt(tx("المايك", "Microphone"), 15f))
        seek(tx("التكبير", "Gain"), 0.1f, 4f, c.optDouble("gain", 1.0).toFloat().coerceIn(0.1f, 4f), 39, { String.format(Locale.US, "%.1fx", it) }) { v -> send { put("gain", v.toDouble()) } }
        val aSrc = c.optInt("aSrc", 0)
        val bN = Button(this).apply { text = tx("عادي", "Normal"); pill(this, aSrc == 0); isEnabled = !rec; setOnClickListener { send { put("aSrc", 0) } } }
        val bW = Button(this).apply { text = tx("خام", "Raw"); pill(this, aSrc == 1); isEnabled = !rec; setOnClickListener { send { put("aSrc", 1) } } }
        body.addView(row(bN, bW))
        body.addView(txt(tx("استعمل سماعات أثناء الاستماع، وإلا يلتقط مايك الجوال الرئيسي الصوت (صدى).", "Use headphones when listening, otherwise the main phone's mic will pick the sound up (echo)."), 11f, "#9A9CA3"))
        val bL = Button(this).apply {
            text = if (listen) "🔊 " + tx("تستمع (اضغط للإيقاف)", "Listening (tap to stop)") else "🔈 " + tx("استمع لهذا المايك", "Listen to this microphone")
            pill(this, listen)
            setOnClickListener { listen = !listen; applyPreview(); builtFor = "" }
        }
        body.addView(bL)
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 150)
        }
    }

    private fun refresh() {
        val l = SatHub.links[devId]
        if (l == null) { info.text = tx("الجهاز غير موجود", "Device not found"); return }
        info.text = (if (l.connected) "🟢 " else "⚪ ") + l.name + (if (l.recording) "  • " + tx("يسجّل", "recording") else "") + (if (!l.connected) "  • " + tx("غير متصل", "not connected") else "  • ${l.rttMs} ms") +
            (if (l.battery >= 0) "  • 🔋${l.battery}%" else "")
        level.progress = if (l.connected) (l.level * 100 / 32767).coerceIn(0, 100) else 0
        val f = l.frame
        if (f != null && l.frameAt != lastFrameAt) { img.setImageBitmap(f); lastFrameAt = l.frameAt }
        if (l.role != "both") img.setImageDrawable(null)
        val c = l.cfgs
        if (c != null) {
            val sig = c.toString() + (l.caps?.toString() ?: "") + listen + l.connected
            if (sig != builtFor) {
                builtFor = sig
                buildControls(c, l.caps, l.role, c.optBoolean("rec", false))
            }
        } else if (builtFor != "none") {
            builtFor = "none"
            body.removeAllViews()
            body.addView(txt(tx("بانتظار إعدادات الجهاز…", "Waiting for the device settings…"), 12f, "#9A9CA3"))
        }
    }
}
