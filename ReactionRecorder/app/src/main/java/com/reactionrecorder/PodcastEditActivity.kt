package com.reactionrecorder

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** ألوان الأشخاص في الخط الزمني */
object PodColors {
    val people = intArrayOf(0xFF3D7BE0.toInt(), 0xFFC9773A.toInt(), 0xFF8873E8.toInt(), 0xFF2E9E8F.toInt())
    const val WIDE = 0xFF555860.toInt()
    const val MULTI = 0xFFADB5BD.toInt()
    fun region(type: String): Int = when (type) {
        "monologue", "story", "explain" -> 0xFF2F9E44.toInt()
        "qa" -> 0xFF1971C2.toInt()
        "fast", "debate" -> 0xFFE8590C.toInt()
        "overlap", "emotional" -> 0xFFC2255C.toInt()
        "silence" -> 0xFF343A40.toInt()
        else -> 0xFF495057.toInt()
    }
}

/**
 * الخط الزمني لمشروع: طبيعة الحوار، مسار كلام لكل شخص، اللقطات (🔒 = مثبّتة)، المقاطع المحذوفة، حركات الزوم، والعلامات.
 * يعرض حدود المشروع فقط [a,b].
 */
class PodTimelineView(ctx: Context) : View(ctx) {
    var setup = PodSetup()
    var an: PodAnalysis? = null
    var regions: List<PodEngine.Region> = emptyList()
    var project: PodProject? = null
    var markers: List<PMarker> = emptyList()
    var a = 0.0
    var b = 1.0
    var pxPerSec = 12f
        set(v) { field = v.coerceIn(2f, 120f); requestLayout(); invalidate() }
    var selected = -1.0
    var onTap: ((Double) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val lane = 12 * d
    private val shotH = 36 * d
    private val ruler = 16 * d
    private val regH = 10 * d
    private val zoomH = 8 * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11 * d }
    private val clip = Rect()

    private fun x(t: Double) = ((t - a) * pxPerSec).toFloat()

    override fun onMeasure(w: Int, h: Int) {
        val n = setup.people.size
        setMeasuredDimension(((b - a) * pxPerSec).toInt() + (20 * d).toInt(), (ruler + regH + 4 * d + n * (lane + 3 * d) + shotH + zoomH + 14 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.getClipBounds(clip)
        val t0 = a + clip.left / pxPerSec
        val t1 = a + clip.right / pxPerSec
        c.drawColor(Color.parseColor("#121316"))
        p.color = Color.parseColor("#55FFFFFF")
        val step = when { pxPerSec >= 40 -> 1; pxPerSec >= 12 -> 5; pxPerSec >= 5 -> 10; else -> 30 }
        var s = (t0 / step).toInt() * step
        while (s <= t1) {
            if (s >= a) {
                val xx = x(s.toDouble())
                c.drawLine(xx, 0f, xx, ruler * 0.6f, p)
                if (s % (step * 2) == 0) c.drawText("${s / 60}:${String.format(Locale.US, "%02d", s % 60)}", xx + 2 * d, ruler - 3 * d, tp)
            }
            s += step
        }
        var y = ruler
        // طبيعة الحوار
        for (r in regions) {
            if (r.b < t0 || r.a > t1) continue
            p.color = PodColors.region(r.type)
            p.alpha = (110 + 145 * r.conf).toInt().coerceIn(60, 255)
            c.drawRect(x(r.a), y, x(r.b), y + regH, p)
        }
        p.alpha = 255
        y += regH + 4 * d
        val an0 = an
        for (i in setup.people.indices) {
            p.color = Color.parseColor("#22FFFFFF")
            c.drawRect(clip.left.toFloat(), y, clip.right.toFloat(), y + lane, p)
            if (an0 != null && i < an0.active.size) {
                p.color = PodColors.people[i % 4]
                val row = an0.active[i]
                val f0 = (t0 / an0.frameSec).toInt().coerceAtLeast(0)
                val f1 = (t1 / an0.frameSec).toInt().coerceAtMost(row.size - 1)
                var f = f0
                while (f <= f1) {
                    if (row[f]) {
                        var g = f
                        while (g <= f1 && row[g]) g++
                        c.drawRect(x(f * an0.frameSec), y, x(g * an0.frameSec), y + lane, p)
                        f = g
                    } else f++
                }
            }
            y += lane + 3 * d
        }
        val pr = project
        val r = RectF()
        if (pr != null) {
            for (sh in pr.shots) {
                if (sh.end < t0 || sh.start > t1) continue
                r.set(x(sh.start) + 1, y + 2 * d, x(sh.end) - 1, y + shotH)
                p.color = when (sh.type) { PodShot.WIDE -> PodColors.WIDE; PodShot.MULTI -> PodColors.MULTI; else -> PodColors.people[sh.main % 4] }
                c.drawRoundRect(r, 6 * d, 6 * d, p)
                if (sh.type == PodShot.PIP && sh.others.isNotEmpty()) {
                    p.color = PodColors.people[sh.others[0] % 4]
                    c.drawRect(r.right - min(r.width() * 0.3f, 18 * d), r.top + 4 * d, r.right - 3 * d, r.top + 14 * d, p)
                }
                if (sh.locked) {
                    p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d
                    c.drawRoundRect(r, 6 * d, 6 * d, p); p.style = Paint.Style.FILL
                }
                val label = (if (sh.locked) "🔒" else "") + (if (sh.tag == "reaction") "↩" else "") + sh.label(setup)
                if (r.width() > tp.measureText(label) + 8 * d) {
                    tp.color = Color.WHITE
                    c.drawText(label, r.left + 4 * d, r.centerY() + 4 * d, tp)
                    tp.color = Color.WHITE
                }
            }
            // المقاطع المحذوفة (من النسخة المعدّلة فقط)
            p.color = 0xAA000000.toInt()
            for (cu in pr.cuts) {
                if (cu.b < t0 || cu.a > t1) continue
                if (cu.kind == PCut.SILENCE && pr.st.silence == "off") continue
                c.drawRect(x(cu.a), ruler, x(cu.b), y + shotH, p)
            }
            p.color = 0xFFFF3B30.toInt()
            for (cu in pr.cuts) {
                if (cu.b < t0 || cu.a > t1 || cu.kind == PCut.SILENCE) continue
                c.drawRect(x(cu.a), y + shotH - 3 * d, x(cu.b), y + shotH, p)
            }
            y += shotH + 2 * d
            // الزوم
            for (z in pr.zooms) {
                if (z.b < t0 || z.a > t1) continue
                p.color = if (z.scale <= 1.001f) 0xFF868E96.toInt() else if (z.auto) 0xFFBE4BDB.toInt() else 0xFFF06595.toInt()
                c.drawRect(x(z.a), y + 2 * d, x(z.b), y + zoomH, p)
                if (z.locked) { p.color = Color.WHITE; c.drawRect(x(z.a), y + 2 * d, x(z.a) + 3 * d, y + zoomH, p) }
            }
        }
        // العلامات
        for (m in markers) {
            if (m.t < t0 || m.t > t1) continue
            p.color = if (m.kind == "star") 0xFFFFD43B.toInt() else 0xFFFF6B6B.toInt()
            c.drawRect(x(m.t) - 1.5f * d, 0f, x(m.t) + 1.5f * d, height.toFloat(), p)
        }
        if (selected >= 0) {
            p.color = Color.WHITE
            val xx = x(selected)
            c.drawRect(xx - d, 0f, xx + d, height.toFloat(), p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val t = (a + e.x / pxPerSec).coerceIn(a, b)
            selected = t
            invalidate()
            onTap?.invoke(t)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/**
 * محرّر مشروع (حلقة كاملة أو ريل): كل التعديلات غير إتلافية، مع تراجع/إعادة وحفظ تلقائي ونسخ سابقة،
 * وتثبيت اللقطات والزوم، وإعادة توليد غير المثبّت فقط، ومعاينة 20 ثانية بنفس قرارات التصدير.
 */
class PodcastEditActivity : BaseActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sid: String
    private lateinit var projId: String
    private var d: PodStudio.Data? = null
    private var p: PodProject? = null
    private val undo = UndoStack()
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var tl: PodTimelineView
    private lateinit var preview: ImageView
    private lateinit var info: TextView
    private lateinit var body: LinearLayout
    private lateinit var shotBox: LinearLayout
    private lateinit var editTop: LinearLayout
    private lateinit var resultBox: LinearLayout
    private lateinit var undoBtn: Button
    private lateinit var redoBtn: Button
    private var lastDone = PodState.doneSeq
    @Volatile private var busy = true

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private val isReel get() = p?.kind == PodProject.REEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sid = intent.getStringExtra("session") ?: run { finish(); return }
        getSharedPreferences("reaction_ui", MODE_PRIVATE).edit().putString("lastEditSid", sid).apply()
        projId = intent.getStringExtra("project") ?: "episode"
        build()
        loadAll()
        ui.post(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(tick)
    }

    private var resumedOnce = false
    override fun onResume() {
        super.onResume()
        // رجعنا من محرر القوالب: نحدّث المعاينة والخيارات
        if (resumedOnce && d != null && p != null) {
            // رجعنا من محرر المصادر: نقرأ تعديلات المصادر من الجلسة
            try {
                val fresh = PodcastAnalyzer.setupOf(SessionStore.load(this, sid) ?: return)
                val st = d!!.setup
                st.srcRot.clear(); st.srcRot.putAll(fresh.srcRot)
                st.srcCrop.clear(); st.srcCrop.putAll(fresh.srcCrop)
                st.srcFill.clear(); st.srcFill.putAll(fresh.srcFill)
                st.panX.clear(); st.panX.putAll(fresh.panX); st.panY.clear(); st.panY.putAll(fresh.panY)
            } catch (_: Throwable) {}
            refreshAll()
        }
        resumedOnce = true
    }

    // ───────── أدوات الواجهة ─────────

    private fun txt(t: String, size: Float = 14f, color: String = UiKit.TEXT) = TextView(this).apply {
        text = t; textSize = max(12.5f, size + 1.5f); setTextColor(Color.parseColor(if (color == "#9A9CA3") UiKit.SUB else color)); setPadding(0, dp(6), 0, dp(2))
        setLineSpacing(0f, 1.2f)
    }

    private fun btn(label: String, sel: Boolean = false, run: () -> Unit) = UiKit.chip(this, label, sel) { run() }.apply { textSize = 13.5f; setPadding(dp(8), 0, dp(8), 0) }

    private fun sub(t: String) = UiKit.text(this, t, 14f, UiKit.TEXT, true).apply { setPadding(0, dp(14), 0, dp(2)) }

    /** عدّاد: الاسم · − القيمة + (بدل صف أزرار للأرقام). 0 = «لا» */
    private fun stepper(label: String, value: Float, min: Float, max: Float, step: Float, set: (Float) -> Unit): View {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        r.addView(UiKit.text(this, label, 14f, UiKit.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun f(v: Float) = if (v <= 0f) tx("لا", "No") else (if (v % 1f == 0f) "${v.toInt()}" else String.format(Locale.US, "%.1f", v)) + tx(" ث", " s")
        r.addView(btn("－") { set((value - step).coerceIn(min, max)) }, LinearLayout.LayoutParams(dp(52), dp(44)))
        r.addView(UiKit.text(this, f(value), 15f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(btn("＋") { set((value + step).coerceIn(min, max)) }, LinearLayout.LayoutParams(dp(52), dp(44)))
        return r
    }

    /** شرح مطوي: سطر صغير «ⓘ» بيفتح لما تضغطه (بدل فقرات طويلة) */
    private fun hint(full: String): View = UiKit.text(this, "ⓘ " + tx("شرح", "Info"), 12f, UiKit.SUB).apply {
        setPadding(0, dp(4), 0, dp(4)); var open = false
        setOnClickListener { open = !open; text = if (open) "ⓘ $full" else "ⓘ " + tx("شرح", "Info") }
    }

    /** سطر أدوات: عنوان صغير + أزرار بحجمها (بينسحب يمين/شمال إذا كترت) */
    private fun strip(label: String, items: List<View>): View {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        r.addView(UiKit.text(this, label, 12f, UiKit.SUB).apply { setPadding(0, 0, dp(8), 0) })
        for (x in items) r.addView(x, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }
    }

    private fun rowOf(vararg v: View) = LinearLayout(this).apply {
        for (x in v) addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
    }

    /** قسم بالأداة الحالية (كل أداة بتعرض بطاقاتها بس) */
    private fun card(title: String, key: String = title, summary: String = "", open: Boolean = false): LinearLayout {
        if ((TOOL_OF[key] ?: "more") != tool) return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } // مش بهالأداة: ما بينعرض
        return UiKit.section(this, body, "ed2_$key", title, summary, true).also { b -> (b.parent as? View)?.let { cardViews[key] = it } }
    }
    private val cardViews = HashMap<String, View>()

    /** نروح على بطاقة (بنفتح أداتها) */
    private fun focusCard(key: String) {
        tool = TOOL_OF[key] ?: "more"
        uiPrefs.edit().putBoolean("sec_ed2_$key", true).apply()
        buildBody(); renderTools()
        body.post { val v = cardViews[key] ?: return@post; panelSv.smoothScrollTo(0, v.top) }
    }

    /** أدوات الشريط السفلي وبطاقات كل وحدة */
    private val TOOL_OF = mapOf("tpl" to "tpl", "explain" to "tpl", "strategy" to "shot", "frame" to "shot",
        "sounds" to "audio", "audio" to "audio", "silence" to "audio", "text" to "text", "intro" to "intro", "export" to "export",
        "range" to "more", "sources" to "more")
    private val uiPrefs by lazy { getSharedPreferences("reaction_ui", MODE_PRIVATE) }
    private var tool: String
        get() = uiPrefs.getString("ed_tool", "tpl") ?: "tpl"
        set(v) { uiPrefs.edit().putString("ed_tool", v).apply() }
    private val TOOLS = listOf(
        Triple("tpl", "M3 5h18v14H3zM3 10h18", tx("القالب", "Template")),
        Triple("shot", "M6 4v16M18 4v16M6 12h12", tx("اللقطة", "Shot")),
        Triple("audio", "M4 10v4M8 7v10M12 4v16M16 8v8M20 11v2", tx("الصوت", "Audio")),
        Triple("text", Ic.TEXT, tx("الكتابة", "Text")),
        Triple("intro", "M3 6h18v12H3zM10 9.5v5l4-2.5z", tx("الإنترو", "Intro")),
        Triple("more", "M4.5 12a.5.5 0 1 0 1 0a.5.5 0 1 0-1 0zM11.5 12a.5.5 0 1 0 1 0a.5.5 0 1 0-1 0zM18.5 12a.5.5 0 1 0 1 0a.5.5 0 1 0-1 0z", tx("المزيد", "More")))
    private val toolViews = HashMap<String, TextView>()

    private fun pickTool(k: String) {
        if (k == tool) { panelSv.smoothScrollTo(0, 0); return }
        tool = k
        syncResult()
        buildBody(); renderTools()
        panelSv.scrollTo(0, 0)
        body.alpha = 0f; body.translationY = dp(8).toFloat()
        body.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    /** نتيجة التصدير (احفظ/شغّل/شارك) بتضل بأداة «تصدير» بس — ما بتاكل مساحة باقي الأدوات */
    private fun syncResult() {
        if (!::resultBox.isInitialized) return
        resultBox.visibility = if (PodState.job == PodcastExportService.ACTION_EXPORT && tool != "export" && !PodState.held) View.GONE else View.VISIBLE
    }

    private fun renderTools() {
        val dd = d
        val warn = dd != null && (dd.missing.isNotEmpty() || dd.s.devices.values.any { dv -> dv.started && dv.uploaded && dd.setup.people.none { it.camDev == dv.id || it.micDev == dv.id } && dv.id !in dd.setup.wides })
        for ((k, path, n) in TOOLS) {
            val tv = toolViews[k] ?: continue
            val on = k == tool
            val col = if (on) UiKit.ACCENT else UiKit.SUB
            tv.text = n + if (k == "more" && warn) " ⚠" else ""
            tv.setTextColor(Color.parseColor(if (k == "more" && warn && !on) UiKit.ORANGE else col))
            tv.typeface = if (on) Fonts.semi else Fonts.regular
            tv.setCompoundDrawablesWithIntrinsicBounds(null, UiKit.icon(this, path, col, 22), null, null)
        }
        exportBtn.alpha = if (tool == "export") 0.6f else 1f
    }

    /** تنبيهات التجهيز (ملفات ما وصلت) بأداة «المزيد» */
    private fun stepBar(dd: PodStudio.Data) {
        if (tool == "more") {
            for (m in dd.missing) body.addView(txt("⏳ $m", 13f, "#FF9F0A"))
            if (dd.missing.isNotEmpty()) body.addView(txt(tx("لما يوصلوا، المونتاج بيتعمل من جديد لحاله.", "When they arrive, the edit rebuilds itself."), 11.5f, "#9A9CA3"))
        }
    }

    private fun advancedToggle() {}

    private fun fmt(t: Double): String { val s = max(0.0, t).toLong(); return "${s / 60}:${String.format(Locale.US, "%02d", s % 60)}" }

    // ───────── البناء ─────────

    private lateinit var panelSv: ScrollView
    private lateinit var topBar: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var exportBtn: TextView
    private lateinit var badgeQ: TextView
    private lateinit var badgeO: TextView
    private lateinit var titleTv: TextView
    private lateinit var rootBox: LinearLayout

    private fun build() {
        rootBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(UiKit.BG)) }
        setContentView(rootBox)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootBox) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        // الرأس: رجوع · العنوان والشارات · تراجع/إعادة · معاينة · تصدير
        topBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(8), dp(4)) }
        topBar.addView(UiKit.iconButton(this, if (L.ar) Ic.BACK else Ic.CHEV, tx("رجوع", "Back"), bgColor = null) { finish() })
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleTv = UiKit.text(this, tx("المونتاج", "Editing"), 15.5f, UiKit.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        ht.addView(titleTv)
        val bl = LinearLayout(this)
        fun badge() = UiKit.text(this, "", 10.5f, "#C8CAD0").apply { setPadding(dp(6), dp(1), dp(6), dp(1)); background = UiKit.bg(UiKit.CARD2, 5, this@PodcastEditActivity) }
        badgeQ = badge(); badgeO = badge()
        bl.addView(badgeQ); bl.addView(badgeO, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        ht.addView(bl)
        topBar.addView(ht, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoBtn = Button(this); redoBtn = Button(this)
        val undoI = UiKit.iconButton(this, Ic.UNDO, tx("تراجع", "Undo"), bgColor = null) { doUndo() }
        val redoI = UiKit.iconButton(this, Ic.REDO, tx("إعادة", "Redo"), bgColor = null) { doRedo() }
        topBar.addView(undoI, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(redoI, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(UiKit.iconButton(this, Ic.PLAY, tx("معاينة ٢٠ ثانية", "20 s preview"), bgColor = null) { previewHere() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        undoIcon = undoI; redoIcon = redoI
        exportBtn = UiKit.text(this, tx("تصدير", "Export"), 13.5f, UiKit.ON_ACCENT, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0); minHeight = dp(36)
            background = UiKit.ripple(UiKit.bg(UiKit.ACCENT, 10, this@PodcastEditActivity), dark = true)
            isClickable = true; setOnClickListener { pickTool("export") }
        }
        topBar.addView(exportBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(4) })
        // الحالة (تحميل/حساب) — سطر صغير
        status = txt(tx("جارٍ التحميل…", "Loading…"), 11f, UiKit.ACCENT).apply { setPadding(dp(12), 0, dp(12), 0); maxLines = 2 }
        bar = UiKit.level(this).apply { progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(UiKit.ACCENT)) }
        // المعاينة والخط الزمني: ثابتين فوق
        editTop = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        editTop.addView(status)
        editTop.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        preview = ImageView(this).apply { setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER }
        editTop.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewH()).apply { topMargin = dp(4) })
        val ir = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
        info = txt(tx("اضغط على لحظة بالخط الزمني", "Tap a moment on the timeline"), 10.5f, UiKit.SUB).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        ir.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ir.addView(UiKit.iconButton(this, "M5 12h14", tx("صغّر الخط الزمني", "Zoom timeline out"), UiKit.SUB, null) { tl.pxPerSec = tl.pxPerSec / 1.6f }, LinearLayout.LayoutParams(dp(40), dp(40)))
        ir.addView(UiKit.iconButton(this, Ic.PLUS, tx("كبّر الخط الزمني", "Zoom timeline in"), UiKit.SUB, null) { tl.pxPerSec = tl.pxPerSec * 1.6f }, LinearLayout.LayoutParams(dp(40), dp(40)))
        editTop.addView(ir)
        tl = PodTimelineView(this)
        tl.onTap = { t -> if (tool != "shot") pickTool("shot"); onPick(t) }
        editTop.addView(HorizontalScrollView(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; isHorizontalScrollBarEnabled = false; addView(tl) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        shotBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // اللوحة السياقية: بطاقات الأداة الحالية
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(2), dp(12), dp(20)) }
        pc.addView(resultBox); pc.addView(body)
        panelSv = ScrollView(this).apply { isFillViewport = true; addView(pc) }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor(UiKit.CARD)); val r = dp(16).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        panel.addView(panelSv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // شريط الأدوات تحت
        toolbar = LinearLayout(this).apply { setBackgroundColor(Color.parseColor(UiKit.CARD)); setPadding(0, dp(4), 0, dp(4)) }
        for ((k, path, n) in TOOLS) {
            val tv = TextView(this).apply {
                text = n; textSize = 11f; gravity = Gravity.CENTER; compoundDrawablePadding = dp(2)
                setPadding(0, dp(6), 0, dp(4)); minHeight = dp(54); contentDescription = n
                background = UiKit.ripple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                isClickable = true; setOnClickListener { pickTool(k) }
                tag = path
            }
            toolViews[k] = tv
            toolbar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        arrange(resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        renderTools()
    }

    private fun previewH(): Int {
        val m = resources.displayMetrics
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val w = if (land) m.widthPixels * 0.5f else m.widthPixels.toFloat()
        return min((w * 9f / 16f).toInt(), dp(if (land) 200 else 205))
    }

    /** بالطول: كل إشي تحت بعض. بالعرض: المعاينة والخط الزمني على جهة، واللوحة والأدوات على الجهة الثانية */
    private var landNow: Boolean? = null
    private fun arrange(land: Boolean) {
        if (landNow == land) return
        landNow = land
        for (v in listOf(topBar, editTop, panel, toolbar)) (v.parent as? ViewGroup)?.removeView(v)
        rootBox.removeAllViews()
        (preview.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = previewH(); preview.layoutParams = it }
        if (!land) {
            rootBox.addView(topBar)
            rootBox.addView(editTop)
            rootBox.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
            rootBox.addView(View(this).apply { setBackgroundColor(Color.parseColor(UiKit.CARD2)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            rootBox.addView(toolbar)
        } else {
            val row = LinearLayout(this)
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(topBar); left.addView(editTop)
            row.addView(ScrollView(this).apply { addView(left) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            right.addView(toolbar)
            row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(6) })
            rootBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange(newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }

    private var undoIcon: View? = null
    private var redoIcon: View? = null

    /** مراجعة قبل التصدير: ملخص بالكلام العادي ثم تصدير */
    private fun reviewExport() {
        val dd = d ?: return
        val pp = p ?: return
        UiKit.sheet(this, tx("راجع قبل التصدير", "Review before export")) { col, dlg ->
            for ((l, c) in PodExplain.inEdit(this, dd, pp)) col.addView(UiKit.text(this, l, 14.5f, c).apply { setPadding(0, dp(3), 0, dp(3)) })
            col.addView(UiKit.text(this, tx("الإخراج: ", "Output: ") + (if (pp.st.orient == "v") "9:16" else if (pp.st.orient == "h") "16:9" else "16:9 + 9:16") + " · ${pp.st.height}p · " + summary(), 14.5f, UiKit.TEXT, true).apply { setPadding(0, dp(10), 0, 0) })
            col.addView(UiKit.text(this, tx("التصدير بيعمل ملف جديد؛ التسجيل الأصلي والمشروع ما بيتغيروا.", "Export makes a new file; the original recording and the project don't change."), 13.5f, UiKit.SUB))
            val b = UiKit.primary(this, tx("صدّر الفيديو", "Export the video")) { dlg.dismiss(); startJob(PodcastExportService.ACTION_EXPORT) }
            col.addView(b, UiKit.full(this, b, 14))
        }
    }

    private fun loadAll() {
        busy = true
        Thread {
            val dd = PodStudio.load(this, sid, { pr -> ui.post { status.text = tx("تحليل من يتكلم… ${(pr * 100).toInt()}%", "Analyzing who speaks… ${(pr * 100).toInt()}%"); bar.progress = (pr * 100).toInt() } }, { isFinishing })
            if (dd == null) { ui.post { status.text = if (isFinishing) "" else tx("تعذر فتح الجلسة", "Could not open the session") }; return@Thread }
            var pp = if (projId == "episode") ProjectStore.episode(this, sid) else ProjectStore.load(this, sid, projId)
            if (pp == null && projId == "episode") pp = PodStudio.newEpisode(dd).also { ProjectStore.save(this, sid, it) }
            if (pp != null && pp.shots.isEmpty()) { PodStudio.regenerate(dd, pp); ProjectStore.save(this, sid, pp) }
            // بناء فحص الواجهة بس: مقاطع تجريبية للإنترو والفاصل (من المحاكي)
            if (BuildConfig.UX_TEST && pp != null && pp.intro.isBlank()) try {
                val cd = File(filesDir, "ux_clips")
                File(cd, "intro.mp4").takeIf { it.exists() }?.let { pp.intro = it.absolutePath; pp.introSrc = "file" }
                File(cd, "mid.mp4").takeIf { it.exists() }?.let { pp.mid = it.absolutePath }
                ProjectStore.save(this, sid, pp)
            } catch (_: Throwable) {}
            ui.post {
                if (pp == null) { status.text = tx("المشروع غير موجود", "Project not found"); return@post }
                d = dd; p = pp
                busy = false
                if (dd.inputsChanged) toast(tx("وصلت ملفات جديدة — انعمل المونتاج من جديد (المثبّت 🔒 ما تغيّر)", "New files arrived — the edit was rebuilt (locked 🔒 parts kept)"))
                bar.progress = 100
                refreshAll()
            }
        }.start()
    }

    // ───────── تعديل مع تراجع وحفظ تلقائي ─────────

    private fun edit(f: (PodProject) -> Unit) {
        if (computing) { toast(tx("انتظر… جارٍ الحساب", "Wait… calculating")); return }
        val pp = p ?: return
        undo.push(pp.toJson().toString())
        val st0 = pp.st.copy()
        f(pp)
        TemplateStore.trackOverrides(st0, pp.st)
        ProjectStore.save(this, sid, pp)
        refreshAll()
    }

    private var computing = false

    /** تعديل ثقيل (إعادة توليد، قص صمت، زوم تلقائي): يُحسب على نسخة في الخلفية ثم يُعتمد */
    private fun editHeavy(f: (PodProject) -> Unit) {
        val pp = p ?: return
        if (computing) { toast(tx("انتظر… جارٍ الحساب", "Wait… calculating")); return }
        computing = true
        val before = pp.toJson().toString()
        val cp = pp.copy()
        status.text = tx("جارٍ الحساب…", "Calculating…")
        Thread {
            val err = try { val st0 = cp.st.copy(); f(cp); TemplateStore.trackOverrides(st0, cp.st); null } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
            ui.post {
                computing = false
                if (isFinishing || isDestroyed) return@post
                if (err != null) { status.text = "⚠ $err"; return@post }
                undo.push(before)
                p = cp
                ProjectStore.save(this, sid, cp)
                refreshAll()
            }
        }.start()
    }

    private fun doUndo() {
        if (computing) return
        val pp = p ?: return
        val s = undo.undo(pp.toJson().toString()) ?: return
        p = PodProject.fromJson(JSONObject(s)); ProjectStore.save(this, sid, p!!); refreshAll()
    }

    private fun doRedo() {
        if (computing) return
        val pp = p ?: return
        val s = undo.redo(pp.toJson().toString()) ?: return
        p = PodProject.fromJson(JSONObject(s)); ProjectStore.save(this, sid, p!!); refreshAll()
    }

    private fun refreshAll() {
        val dd = d ?: return
        val pp = p ?: return
        tl.setup = dd.setup; tl.an = dd.an; tl.regions = dd.regions; tl.project = pp; tl.markers = dd.markers
        tl.a = pp.a; tl.b = pp.end(dd.duration)
        tl.requestLayout(); tl.invalidate()
        undoBtn.isEnabled = undo.canUndo(); redoBtn.isEnabled = undo.canRedo()
        undoIcon?.alpha = if (undo.canUndo()) 1f else 0.35f; redoIcon?.alpha = if (undo.canRedo()) 1f else 0.35f
        undoIcon?.isEnabled = undo.canUndo(); redoIcon?.isEnabled = undo.canRedo()
        status.text = summary()
        titleTv.text = if (isReel) tx("ريل: ", "Reel: ") + pp.title else tx("الحلقة الكاملة", "Full episode")
        badgeQ.text = "${pp.st.height}P"
        badgeO.text = if (pp.st.orient == "v") "9:16" else if (pp.st.orient == "h") "16:9" else "16:9 + 9:16"
        buildBody(); renderTools()
        if (tl.selected < 0 && pp.shots.isNotEmpty() && preview.drawable == null) pp.shots.firstOrNull { it.end > pp.a + 1.0 }?.let { loadPreview(it, max(it.start, pp.a) + 0.8) }
        if (tl.selected >= 0) onPick(tl.selected) else { shotBox.removeAllViews(); shotBox.addView(UiKit.text(this, tx("اضغط على لحظة بالخط الزمني عشان تشوف لقطتها وتعدّلها", "Tap a moment on the timeline to see and edit its shot"), 13f, UiKit.SUB).apply { setPadding(0, dp(12), 0, dp(4)) }) }
    }

    private fun summary(): String {
        val dd = d ?: return ""
        val pp = p ?: return ""
        val keep = PodStudio.keepSegs(dd, pp)
        val outDur = keep.sumOf { it[1] - it[0] }
        val orig = pp.end(dd.duration) - pp.a
        val head = if (isReel) tx("📱 ريل: ${pp.title}", "📱 Reel: ${pp.title}") else tx("الحلقة الكاملة", "Full episode")
        return tx("$head • ${pp.shots.size} لقطة • المدة ${fmt(outDur)}", "$head • ${pp.shots.size} shots • length ${fmt(outDur)}") + (if (orig - outDur > 0.5) tx(" (حُذف ${fmt(orig - outDur)})", " (removed ${fmt(orig - outDur)})") else "") +
            (if (pp.zooms.isNotEmpty() && pp.st.zoomMode != "off") tx(" • ${pp.zooms.size} زوم", " • ${pp.zooms.size} zooms") else "")
    }

    // ───────── اختيار لحظة ─────────

    private fun shotIndexAt(t: Double): Int {
        val pp = p ?: return -1
        for ((i, s) in pp.shots.withIndex()) if (t >= s.start && t < s.end) return i
        return pp.shots.size - 1
    }

    private fun onPick(t: Double) {
        val pp = p ?: return
        val dd = d ?: return
        if (pp.shots.isEmpty()) return
        tl.selected = t; tl.invalidate()
        val i = shotIndexAt(t)
        val sh = pp.shots[i]
        val reg = PodEngine.regionAt(dd.regions, t)
        val cut = pp.cuts.firstOrNull { t >= it.a && t < it.b && !(it.kind == PCut.SILENCE && pp.st.silence == "off") }
        val text = dd.tr?.segs?.firstOrNull { t >= it.a - 0.2 && t < it.b + 0.2 }?.text
        info.text = tx("عند ${fmt(t)} — لقطة ${i + 1}/${pp.shots.size}: ${typeName(sh)}", "At ${fmt(t)} — shot ${i + 1}/${pp.shots.size}: ${typeName(sh)}") + (if (sh.locked) " 🔒" else "") +
            (reg?.let { "\n" + tx("طبيعة الجزء: ", "Part type: ") + it.label() + " (" + tx("ثقة", "confidence") + " ${(it.conf * 100).toInt()}% • " + (if (it.source == "ai") tx("من فهم النص", "from the text") else tx("تقدير من التوقيت", "estimated from timing")) + ")" } ?: "") +
            (if (cut != null) tx("\n✂ هذه اللحظة محذوفة من النسخة المعدّلة (${cutName(cut.kind)})", "\n✂ This moment is removed from the edit (${cutName(cut.kind)})") else "") +
            (text?.let { "\n«$it»" } ?: "")
        loadPreview(sh, t)
        // أدوات اللقطة ما بتطلع بالصفحة (عشان ما تنضغط بالغلط وانت بتقلّب): زر واحد بيفتح نافذتها
        shotBox.removeAllViews()
        shotBox.addView(UiKit.text(this, tx("اللقطة ${i + 1}/${pp.shots.size} · ${fmt(sh.start)}–${fmt(sh.end)}", "Shot ${i + 1}/${pp.shots.size} · ${fmt(sh.start)}–${fmt(sh.end)}"), 12f, UiKit.SUB).apply { setPadding(0, dp(10), 0, 0) })
        shotBox.addView(UiKit.text(this, typeName(sh) + (if (sh.locked) "  🔒" else ""), 15f, UiKit.TEXT, true))
        val open = UiKit.primary(this, tx("خصّص هاللقطة", "Customise this shot"), UiKit.ACCENT) { shotSheet(i, t) }.apply { textSize = 14.5f }
        shotBox.addView(open, UiKit.full(this, open, 8, 46))
    }

    /** نافذة اللقطة: مين يطلع، ثبّت/قسّم/ادمج، وبداية/نهاية الفيديو */
    /** أسباب اللقطة: القاعدة اللي اختارتها، وإذا يدوية/مثبّتة، ومين حكى قديش بوقتها حسب الصوت */
    private fun whyShot(dd: PodStudio.Data, pp: PodProject, sh: PodShot): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val ppl = dd.setup.people
        fun nm(i: Int) = ppl.getOrNull(i)?.name ?: "?"
        val st = pp.st
        val rule = when {
            sh.tag == "manual" -> tx("✋ لقطة حطيتها بإيدك — بتغلب القالب.", "✋ A shot you placed by hand — it overrides the template.")
            sh.tag == "break" -> tx("⏸ كسر الملل (خيار ٥): لقطة على المستمع بالكلام الطويل.", "⏸ Break monotony (option 5): a cutaway to the listener in long talk.")
            sh.tag == "short" -> tx("💬 تعليق قصير (خيار ٣): مربع صغير فوق اللي بيحكي.", "💬 Short comment (option 3): a small box over the talker.")
            sh.why == "start" -> tx("▶ أول لقطة: على اللي بيحكي ببداية الفيديو (وبتطوّل حسب خيار ٦ «أول ظهور»).", "▶ First shot: on whoever talks at the start (held longer by option 6).")
            sh.why == "silence-wide" -> tx("🤫 سكوت أكثر من ٤ ث → الكاميرا العامة.", "🤫 Silence over 4 s → the wide camera.")
            sh.why == "tog-both" -> tx("🗣🗣 كانوا بيحكوا مع بعض → خيار ٢ = الاثنين مع بعض (تقسيم).", "🗣🗣 They talked at once → option 2 = both together (split).")
            sh.why == "tog-switch" -> tx("🗣🗣 كانوا بيحكوا مع بعض → خيار ٢ = بتنتقل على اللي دخل.", "🗣🗣 They talked at once → option 2 = go to the one who came in.")
            sh.why == "tog-wide" -> tx("🗣🗣 كانوا بيحكوا مع بعض → خيار ٢ = الكاميرا العامة.", "🗣🗣 They talked at once → option 2 = the wide camera.")
            sh.why == "tog-stay" -> tx("🗣🗣 كانوا بيحكوا مع بعض → خيار ٢ = بتضل على اللي كان.", "🗣🗣 They talked at once → option 2 = stay.")
            sh.why == "floor" -> tx("🎙 ${nm(sh.main)} أخد الدور (حكى أكثر من نص آخر لحظات) → الكاميرا عليه.", "🎙 ${nm(sh.main)} took the floor (talked more than half of the last moments) → camera on them.")
            else -> tx("ℹ لقطة من مونتاج قديم (قبل ما نسجّل الأسباب) — «أعد توليد هالجزء» بيعملها من جديد.", "ℹ A shot from an older edit (before reasons were recorded) — “Regenerate this part” rebuilds it.")
        }
        out += rule to UiKit.TEXT
        if (sh.locked) out += tx("🔒 مثبّتة: إعادة المونتاج ما بتغيّرها.", "🔒 Locked: regenerating won't change it.") to UiKit.ORANGE
        // مين حكى بهاللقطة (حسب الصوت)
        val an = dd.an
        val i0 = (sh.start / an.frameSec).toInt(); val i1 = (sh.end / an.frameSec).toInt()
        val share = DoubleArray(ppl.size) { p -> val r = an.active.getOrNull(p) ?: return@DoubleArray 0.0; var k = 0; for (i in max(0, i0) until min(r.size, i1)) if (r[i]) k++; k.toDouble() / max(1, i1 - i0) }
        out += (tx("🔊 مين حكى بهالوقت (حسب الصوت): ", "🔊 Who talked here (from the audio): ") + ppl.indices.joinToString("، ") { "${nm(it)} ${(share[it] * 100).toInt()}%" }) to UiKit.SUB
        val shown = when (sh.type) { PodShot.FULL -> setOf(sh.main); PodShot.MULTI -> sh.others.toSet(); PodShot.PIP -> sh.others.toSet() + sh.main; else -> emptySet() }
        val top = ppl.indices.maxByOrNull { share[it] } ?: -1
        if (top >= 0 && share[top] > 0.5 && top !in shown && sh.type != PodShot.WIDE && dd.hasCam(top) && ppl[top].camDev != ppl.getOrNull(sh.main)?.camDev)
            out += tx("⚠ ${nm(top)} حكى أكثر بهالوقت بس مش ظاهر. إذا هيك الصوت غلط (مايكه لقط حدا ثاني) — «راجع الأصوات»؛ وإذا المونتاج غلط اختار ${nm(top)} من تحت.", "⚠ ${nm(top)} talked more here but isn't shown. If the audio is wrong (their mic picked up someone else) — “Review sounds”; if the edit is wrong pick ${nm(top)} below.") to UiKit.ORANGE
        return out
    }

    private fun shotSheet(i: Int, t: Double) {
        val pp = p ?: return
        val dd = d ?: return
        val sh = pp.shots.getOrNull(i) ?: return
        UiKit.sheet(this, tx("اللقطة ${i + 1} · ${fmt(sh.start)}–${fmt(sh.end)}", "Shot ${i + 1} · ${fmt(sh.start)}–${fmt(sh.end)}")) { col, dlg ->
            fun b(label: String, sel: Boolean = false, run: () -> Unit) = btn(label, sel) { dlg.dismiss(); run() }
            fun title(s: String) = col.addView(UiKit.text(this, s, 14f, UiKit.TEXT, true).apply { setPadding(0, dp(12), 0, dp(2)) })
            val n = dd.setup.people.size
            // ── ليش هاي اللقطة؟ (اللي سجّله المحرّك وقت قرّر + مين حكى فعلًا بوقتها)
            title(tx("ليش هاي اللقطة؟", "Why this shot?"))
            for ((line, col2) in whyShot(dd, pp, sh)) col.addView(txt(line, 13f, col2))
            title(tx("مين يطلع", "Who shows"))
            val show = ArrayList<View>()
            for (k in 0 until n) show += b("👤 " + dd.setup.people[k].name, sh.type == PodShot.FULL && sh.main == k) { change(i) { it.type = PodShot.FULL; it.main = k; it.others = IntArray(0) } }
            if (n >= 2) show += b(tx("👥 الكل", "👥 All"), sh.type == PodShot.MULTI) { change(i) { it.type = PodShot.MULTI; it.main = 0; it.others = IntArray(n) { k -> k } } }
            if (dd.setup.wides.isNotEmpty()) show += b(tx("🎥 عامة", "🎥 Wide"), sh.type == PodShot.WIDE) { change(i) { it.type = PodShot.WIDE; it.main = 0; it.others = IntArray(0) } }
            for (row in show.chunked(2)) col.addView(rowOf(*row.toTypedArray()))
            title(tx("اللقطة", "The shot"))
            col.addView(rowOf(
                b(if (sh.locked) tx("🔒 مثبّتة", "🔒 Locked") else tx("🔓 ثبّت", "🔓 Lock"), sh.locked) { edit { it.shots[i].locked = !it.shots[i].locked } },
                b(tx("✂ قسّم هون", "✂ Split here")) { splitAt(i, t) },
            ))
            col.addView(rowOf(
                b(tx("⤺ ادمج مع اللي قبلها", "⤺ Merge with previous")) { if (i > 0) edit { it.shots[i - 1].end = it.shots[i].end; it.shots.removeAt(i) } },
                b(tx("↩ رد فعل هون", "↩ Reaction here")) { reactionHere(t) },
            ))
            col.addView(rowOf(b(tx("↻ أعد توليد هالجزء", "↻ Regenerate this part")) { regenPart(t) }))
            val trimmed = pp.a > 0.05 || pp.b >= 0 && pp.b < dd.duration - 0.05
            title(tx("الفيديو المصدّر: ${fmt(pp.a)} → ${fmt(pp.end(dd.duration))}", "Exported video: ${fmt(pp.a)} → ${fmt(pp.end(dd.duration))}"))
            col.addView(rowOf(b(tx("⟵ بيبلّش من هون", "⟵ Starts here")) { setEdge(true, t) }, b(tx("بيخلص هون ⟶", "Ends here ⟶")) { setEdge(false, t) }))
            if (trimmed) col.addView(rowOf(b(tx("↺ التسجيل كامل", "↺ The whole recording")) { editHeavy { q -> q.a = 0.0; q.b = -1.0; PodStudio.regenerate(dd, q) } }))
        }
    }

    private fun cutName(k: String) = when (k) { PCut.SILENCE -> tx("قص صمت", "Silence cut"); PCut.GAP -> tx("فراغ طويل", "Long gap"); PCut.TEXT -> tx("جملة محذوفة", "Deleted sentence"); else -> tx("حذف يدوي", "Manual cut") }

    private fun typeName(s: PodShot): String {
        val st = d?.setup ?: return ""
        fun nm(i: Int) = st.people.getOrNull(i)?.name ?: tx("؟", "?")
        return when (s.type) {
            PodShot.FULL -> tx("${nm(s.main)} بملء الشاشة", "${nm(s.main)} full screen") + when (s.tag) {
                "reaction" -> tx(" (رد فعل)", " (reaction)"); "zoom" -> tx(" (تقريب لكسر الملل)", " (punch-in)"); "break" -> tx(" (لقطة لكسر الملل)", " (cutaway)"); else -> "" }
            PodShot.PIP -> tx("${nm(s.main)} + مربع عائم ${s.others.joinToString { nm(it) }}", "${nm(s.main)} + floating box ${s.others.joinToString { nm(it) }}")
            PodShot.MULTI -> tx("مشتركة: ", "Shared: ") + s.others.joinToString { nm(it) }
            else -> tx("الكاميرا العامة", "Wide camera")
        }
    }

    /** أي تعديل يدوي على لقطة يثبّتها تلقائيًا حتى لا تستبدلها إعادة التوليد */
    private fun change(i: Int, f: (PodShot) -> Unit) = edit { pp ->
        val sh = pp.shots[i]
        f(sh)
        sh.locked = true
        sh.tag = if (sh.tag == "reaction") "reaction" else "manual"
        pp.shots = PodEngine.tidy(pp.shots)
    }

    private fun pickPip(i: Int) {
        val st = d?.setup ?: return
        val n = st.people.size
        val names = st.people.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle(tx("بملء الشاشة", "full screen")).setItems(names) { _, main ->
            val rest = (0 until n).filter { it != main }
            AlertDialog.Builder(this).setTitle(tx("في المربع العائم", "in the floating box")).setItems(rest.map { names[it] }.toTypedArray()) { _, k ->
                change(i) { it.type = PodShot.PIP; it.main = main; it.others = intArrayOf(rest[k]) }
            }.show()
        }.show()
    }

    private fun splitAt(i: Int, t: Double) {
        val pp = p ?: return
        val sh = pp.shots[i]
        if (t - sh.start < 0.3 || sh.end - t < 0.3) { toast(tx("قريب جدًا من حد اللقطة", "Too close to the shot edge")); return }
        edit { q ->
            val s0 = q.shots[i]
            val b2 = s0.copy()
            s0.end = t
            b2.start = t
            q.shots.add(i + 1, b2)
        }
        toast(tx("قُسمت — غيّر أحد الجزأين الآن", "Split — change one of the parts now"))
    }

    /** إدراج لقطة رد فعل يدوية (1.5 ثانية) لشخص تختاره */
    private fun reactionHere(t: Double) {
        val st = d?.setup ?: return
        val names = st.people.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle(tx("رد فعل من؟", "Reaction from whom?")).setItems(names) { _, k ->
            edit { pp ->
                val r = PodShot(t, min(pp.end(d!!.duration), t + 1.5), PodShot.FULL, k, IntArray(0)).also { it.locked = true; it.tag = "reaction" }
                pp.shots = PodEngine.tidy(PodEngine.overlay(pp.shots, listOf(r)))
            }
        }.show()
    }

    private fun regenPart(t: Double) {
        val dd = d ?: return
        val pp = p ?: return
        val i = shotIndexAt(t)
        val r0 = max(pp.a, pp.shots[max(0, i - 1)].start)
        val r1 = min(pp.end(dd.duration), pp.shots[min(pp.shots.size - 1, i + 1)].end)
        ProjectStore.snapshot(this, sid, pp, tx("قبل إعادة توليد جزء", "Before regenerating a part"))
        editHeavy { PodStudio.regenerate(dd, it, r0, r1) }
        toast(tx("أُعيد توليد ${fmt(r0)}–${fmt(r1)} (المثبّت بقي كما هو)", "Regenerated ${fmt(r0)}–${fmt(r1)} (locked items kept)"))
    }

    private var frameReq = 0
    private var pickClip = "intro"
    /** رجعنا من مستعرض الإنترو: البداية والنهاية (والكاميرا اللي كنت عليها) */
    /**
     * رجعنا من تعديل القالب وانحفظ (على نفس القالب أو قالب جديد): بيتطبق فورًا على هالمشروع —
     * حتى الإعدادات اللي كنت معدّلها هون بإيدك، إذا عدّلتها بالقالب هلأ (إنت رحت تعدّل عشان هالمشروع).
     * المشاريع الثانية اللي على نفس القالب بتاخده لما تنفتح (أو لما تتصدّر).
     */
    private val tplEditor = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val id = r.data?.getStringExtra("tpl") ?: return@registerForActivityResult
        val before = try { PodTemplate.fromJson(JSONObject(r.data?.getStringExtra("before") ?: "{}")) } catch (_: Throwable) { null }
        val dd = d ?: return@registerForActivityResult
        editHeavy { q ->
            val now = TemplateStore.resolve(this, id, q.kind)
            if (before != null) q.st.ov.removeAll(TemplateStore.changedKeys(before, now).toSet())
            q.st.tpl = id
            PodStudio.syncTpl(this, q)
            PodStudio.regenerate(dd, q)
        }
        toast(tx("✓ اتطبق القالب على هالمشروع", "✓ Template applied to this project"))
    }
    private val introPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data ?: return@registerForActivityResult
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val a = data.getDoubleExtra("a", 0.0); val b = data.getDoubleExtra("b", a + 10.0)
        val mt = data.getDoubleArrayExtra("mt") ?: DoubleArray(0); val mw = data.getIntArrayExtra("mw") ?: IntArray(0)
        edit { q -> q.introSrc = "self"; q.ia = a; q.ib = b; q.imarks.clear(); for (i in mt.indices) q.imarks.add(mt[i] to mw.getOrElse(i) { -1 }) }
        toast(tx("الإنترو: من ${fmt(a)} لـ ${fmt(b)}", "Intro: from ${fmt(a)} to ${fmt(b)}") + if (mt.isNotEmpty()) tx(" · ${mt.size} علامة", " · ${mt.size} marks") else "")
    }
    /** بنسخ الفيديو المختار لجوّا التطبيق (عشان ما يضيع لو انحذف من المعرض) */
    private val clipPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val which = pickClip
        toast(tx("بنسخ الفيديو…", "Copying the video…"))
        Thread {
            val out = try {
                val dir = File(filesDir, "clips").also { it.mkdirs() }
                val name = (try { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } } catch (_: Throwable) { null }) ?: "clip.mp4"
                val f = File(dir, "${sid}_${which}_" + name.replace(Regex("[^\\w.\\-]"), "_"))
                contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                f.takeIf { it.length() > 1000 }
            } catch (_: Throwable) { null }
            ui.post {
                if (out == null) { toast(tx("ما قدرنا ننسخ الفيديو", "Couldn't copy the video")); return@post }
                edit { when (which) { "intro" -> { it.intro = out.absolutePath; it.introSrc = "file"; it.introFx = ClipFx() }; "mid" -> { it.mid = out.absolutePath; it.midFx = ClipFx() }; else -> { it.outro = out.absolutePath; it.outroFx = ClipFx() } } }
                toast(tx("انضاف ✓", "Added ✓"))
            }
        }.start()
    }

    /** بيانات القالب والمونتاج كنص (عشان تلصقها مع الفيديو المصدّر ونفهم شو صار) */
    private fun copyDebug() {
        val dd = d ?: return
        val pp = p ?: return
        val cur = PodStudio.tplOf(this, pp)
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
        val sb = StringBuilder()
        sb.append("ReactionRecorder $ver\n")
        sb.append("مشروع: ${if (isReel) "ريل" else "حلقة"} ${fmt(pp.a)}→${fmt(pp.end(dd.duration))} · ${if (pp.st.orient == "v") "9:16" else "16:9"} · قالب: ${cur.name}\n")
        sb.append("أشخاص: " + dd.setup.people.withIndex().joinToString(" · ") { (i, x) -> "${i + 1}=${x.name}" + if (dd.hasCam(i)) "" else "(بلا كاميرا)" } + "\n")
        sb.append("تفريغ: ${if (dd.tr != null) "موجود" else "لا"} · كتابة بالفيديو: ${pp.st.captions && dd.tr != null} · عنوان: ${if (pp.st.titleOn && pp.title.isNotBlank()) pp.title else "لا"}\n")
        // إحصائيات اللقطات
        val sh = pp.shots
        fun sum(tp: Int) = sh.filter { it.type == tp }.sumOf { it.dur }
        val tot = sh.sumOf { it.dur }.coerceAtLeast(0.01)
        fun pc(v: Double) = "${(v / tot * 100).toInt()}%"
        sb.append("لقطات: ${sh.size} · كاملة ${pc(sum(PodShot.FULL))} · مربع ${pc(sum(PodShot.PIP))} · مقسومة ${pc(sum(PodShot.MULTI))} · عامة ${pc(sum(PodShot.WIDE))} · أقصر من ٢ث: ${sh.count { it.dur < 2.0 }}\n")
        sb.append("أصوات: ${dd.an.events.size} (كلام ${dd.an.events.count { it.verdict == VEvent.SPEECH }}، مش متأكد ${dd.an.events.count { it.verdict == VEvent.UNSURE }}، ضجة ${dd.an.events.count { it.verdict == VEvent.NOISE }})\n")
        PodExplain.talkShare(dd, pp)?.let { ts -> sb.append("حكي (حسب الصوت): " + dd.setup.people.indices.joinToString(" · ") { "${it + 1}=${(ts[it] * 100).toInt()}%" } + "\n") }
        sb.append("تسريب بين المايكات: ${dd.an.bleed.ifBlank { "?" }}\n")
        sb.append("أجهزة: " + dd.setup.people.withIndex().joinToString(" · ") { (i, x) -> "${i + 1} كاميرا=${x.camDev.takeLast(4)} مايك=${PodcastAnalyzer.micOf(this, dd.s, x).takeLast(4)}" } + "\n")
        // مين حكى قديش بكل ١٠ ثواني (٠–٩) لأول ١٠ دقايق — عشان نفهم إذا الصوت انقرا صح
        run {
            val an = dd.an; val w = (10.0 / an.frameSec).toInt().coerceAtLeast(1); val i0 = (pp.a / an.frameSec).toInt()
            for ((pi, r) in an.active.withIndex()) {
                val line = StringBuilder()
                for (k in 0 until 60) { val s0 = i0 + k * w; if (s0 >= r.size) break; var c = 0; for (i in s0 until min(r.size, s0 + w)) if (r[i]) c++; line.append(min(9, c * 10 / w)) }
                sb.append("حكي ${pi + 1} كل ١٠ث: $line\n")
            }
        }
        sb.append("\n— اللقطات (أول ١٥٠) —\n")
        for (x in sh.take(150)) {
            val who = when (x.type) { PodShot.FULL -> "كاملة ${x.main + 1}"; PodShot.PIP -> "مربع ${x.others.joinToString(",") { "${it + 1}" }} فوق ${x.main + 1}"; PodShot.WIDE -> "عامة"; else -> "مقسومة ${x.others.joinToString("|") { "${it + 1}" }}" }
            sb.append("${fmt(x.start)}–${fmt(x.end)} (${String.format(Locale.US, "%.1f", x.dur)}ث) $who${if (x.tag.isNotBlank()) " [${x.tag}]" else ""}${if (x.locked) " 🔒" else ""}\n")
        }
        val js = JSONObject().put("tpl", cur.toJson()).put("st", pp.st.toJson()).put("setup", JSONObject().put("rot", JSONObject(dd.setup.srcRot as Map<*, *>)).put("fill", JSONObject(dd.setup.srcFill as Map<*, *>)).put("pip", dd.setup.usePip))
        sb.append("\n— JSON —\n").append(js.toString())
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ReactionRecorder", sb.toString()))
        toast(tx("انسخت — الصقها مع الفيديو", "Copied — paste it with the video"))
    }

    /** كم شخص بكاميرا الشخص i — بينحفظ بالجلسة وبيعيد المونتاج */
    private fun cameraCount(pi: Int) {
        val dd = d ?: return
        val opts = listOf("" to tx("تلقائي (من الوجوه)", "Auto (from faces)"), "1" to tx("شخص واحد", "One person"), "2" to tx("شخصين", "Two people"), "3" to tx("٣ أو أكثر", "Three or more"))
        AlertDialog.Builder(this).setTitle(tx("كم شخص بكاميرا ${dd.setup.people[pi].name}؟", "How many people in ${dd.setup.people[pi].name}'s camera?"))
            .setItems(opts.map { it.second }.toTypedArray()) { _, k ->
                val v = opts[k].first
                try {
                    val fresh = PodcastAnalyzer.setupOf(SessionStore.load(this, sid) ?: return@setItems)
                    if (v.isEmpty()) fresh.srcCrowd.remove("p$pi") else fresh.srcCrowd["p$pi"] = v
                    val ex = fresh.toJson().toString()
                    SessionStore.update(this, sid) { x -> x.extra = ex }
                    if (v.isEmpty()) dd.setup.srcCrowd.remove("p$pi") else dd.setup.srcCrowd["p$pi"] = v
                } catch (_: Throwable) {}
                Thread { val cr = PodStudio.crowdOf(this, dd.s, dd.setup); ui.post { dd.crowd = cr; editHeavy { q -> PodStudio.regenerate(dd, q) } } }.start()
            }.show()
    }

    /** شو هاد الجهاز: شخص جديد، كاميرا/مايك شخص موجود، كاميرا عامة، أو لا تستعمله — وبعدين بينعاد التحليل والمونتاج */
    private fun deviceRole(dv: DevInfo) {
        val dd = d ?: return
        val cam = dv.role == "both"
        val opts = ArrayList<Pair<String, () -> Unit>>()
        fun setRole(change: (PodSetup) -> Unit) {
            try {
                val fresh = PodcastAnalyzer.setupOf(SessionStore.load(this, sid) ?: return)
                // نشيله من أي مكان كان فيه
                for (p in fresh.people) { if (p.camDev == dv.id) p.camDev = ""; if (p.micDev == dv.id) p.micDev = "" }
                fresh.wides.remove(dv.id)
                change(fresh)
                val ex = fresh.toJson().toString()
                SessionStore.update(this, sid) { x -> x.extra = ex }
                p?.let { ProjectStore.save(this, sid, it) }
                toast(tx("انحفظ — بنرجع نحلل التسجيل", "Saved — re-analysing the recording"))
                recreate()
            } catch (e: Throwable) { toast(e.message ?: "?") }
        }
        opts += tx("شخص جديد", "A new person") to {
            val et = android.widget.EditText(this).apply { hint = tx("اسمه", "Their name"); setTextColor(Color.WHITE); textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
            AlertDialog.Builder(this).setTitle(tx("اسم الشخص", "Person's name")).setView(et).setPositiveButton(tx("ضيف", "Add")) { _, _ ->
                val n = et.text.toString().trim().ifBlank { tx("شخص ${dd.setup.people.size + 1}", "Person ${dd.setup.people.size + 1}") }
                setRole { st -> st.people.add(PodPerson(n, if (cam) dv.id else "", dv.id)) }
            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
        }
        for ((i, person) in dd.setup.people.withIndex()) {
            opts += (if (cam) tx("كاميرا ${person.name}", "${person.name}'s camera") else tx("مايك ${person.name}", "${person.name}'s mic")) to {
                setRole { st ->
                    val q = st.people.getOrNull(i) ?: return@setRole
                    if (cam) { q.camDev = dv.id; if (q.micDev.isEmpty()) q.micDev = dv.id } else q.micDev = dv.id
                }
            }
        }
        if (cam) opts += tx("كاميرا عامة (بتجيب الكل)", "Wide camera (everyone)") to { setRole { st -> st.wides.add(dv.id) } }
        opts += tx("🗑 شيله من المونتاج (الملفات بتضل)", "🗑 Remove from the edit (files kept)") to { setRole { } }
        opts += tx("🗑 احذفه نهائيًا مع ملفاته", "🗑 Delete it and its files") to {
            AlertDialog.Builder(this).setTitle(tx("حذف «${dv.name}» نهائيًا؟", "Delete “${dv.name}” for good?"))
                .setMessage(tx("بينحذف الفيديو والصوت تبعه من هالجوال (${PodStudio.devSizes(this, sid, dv.id)}). ما بترجع.", "Its video and audio are deleted from this phone (${PodStudio.devSizes(this, sid, dv.id)}). This can't be undone."))
                .setPositiveButton(tx("احذف", "Delete")) { _, _ ->
                    try { SessionStore.devDir(this, sid, dv.id).deleteRecursively() } catch (_: Throwable) {}
                    SessionStore.update(this, sid) { x -> x.devices.remove(dv.id) }
                    setRole { }
                }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
        }
        AlertDialog.Builder(this).setTitle(tx("شو هاد الجهاز؟ (${dv.name})", "What is this device? (${dv.name})"))
            .setItems(opts.map { it.first }.toTypedArray()) { _, k -> opts[k].second() }.show()
    }

    /** تغيير اسم شخص: بينحفظ بالجلسة، وبيطلع بالفيديو وبكل القوائم */
    private fun renamePerson(i: Int) {
        val dd = d ?: return
        val person = dd.setup.people.getOrNull(i) ?: return
        val et = android.widget.EditText(this).apply { setText(person.name); setSelectAllOnFocus(true); textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setTextColor(android.graphics.Color.WHITE) }
        AlertDialog.Builder(this).setTitle(tx("اسم الشخص", "Person's name")).setView(et)
            .setPositiveButton(tx("احفظ", "Save")) { _, _ ->
                val n = et.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                person.name = n
                try {
                    val fresh = PodcastAnalyzer.setupOf(SessionStore.load(this, sid) ?: return@setPositiveButton)
                    fresh.people.getOrNull(i)?.name = n
                    val ex = fresh.toJson().toString()
                    SessionStore.update(this, sid) { x -> x.extra = ex }
                } catch (_: Throwable) {}
                buildBody()
            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    private fun loadPreview(sh: PodShot, t: Double) {
        val dd = d ?: return
        val req = ++frameReq
        val dev = if (sh.type == PodShot.WIDE) dd.setup.wides.getOrNull(sh.main) else dd.setup.people.getOrNull(if (sh.type == PodShot.MULTI && sh.others.isNotEmpty()) sh.others[0] else sh.main)?.camDev
        if (dev.isNullOrEmpty()) { preview.setImageDrawable(null); return }
        Thread {
            val bmp = try {
                val dir = SessionStore.devDir(this, sid, dev)
                val vm = JSONObject(File(dir, "meta.json").readText()).optJSONObject("video")
                val dv = dd.s.devices[dev]
                if (vm == null || dv == null) null else {
                    val ms = Finalizer.SatVideoTime(dd.s, dv, vm.optLong("startBoot")).fileMs((t * 1e9).toLong())
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(File(dir, "video.mp4").absolutePath)
                        r.getScaledFrameAtTime((ms * 1000).toLong().coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                    } finally { r.release() }
                }
            } catch (_: Throwable) { null }
            ui.post { if (req == frameReq) { preview.setImageBitmap(bmp) } }
        }.start()
    }

    // ───────── الأقسام ─────────

    private fun buildBody() {
        val dd = d ?: return
        val pp = p ?: return
        body.removeAllViews()
        cardViews.clear()
        stepBar(dd)
        if (tool == "shot") { (shotBox.parent as? ViewGroup)?.removeView(shotBox); body.addView(shotBox) }
        if (tool == "more") {
            // الحلقة الكاملة | الريلز
            val seg = LinearLayout(this).apply { background = UiKit.bg(UiKit.BG, 10, this@PodcastEditActivity); setPadding(dp(3), dp(3), dp(3), dp(3)) }
            fun segBtn(label: String, on: Boolean, run: () -> Unit) = UiKit.text(this, label, 13f, if (on) UiKit.ON_ACCENT else UiKit.TEXT, on).apply {
                gravity = Gravity.CENTER; minHeight = dp(40)
                background = if (on) UiKit.bg(UiKit.ACCENT, 8, this@PodcastEditActivity) else null
                isClickable = true; setOnClickListener { run() }
            }
            val epOn = projId == "episode"
            seg.addView(segBtn(tx("الحلقة الكاملة", "Full episode"), epOn) { if (!epOn) { startActivity(Intent(this, PodcastEditActivity::class.java).putExtra("session", sid).putExtra("project", "episode")); finish() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            seg.addView(segBtn(tx("الريلز", "Reels"), !epOn) { startActivity(Intent(this, PodcastShortsActivity::class.java).putExtra("session", sid)); if (!epOn) finish() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            body.addView(seg, UiKit.full(this, seg, 10))
            body.addView(rowOf(btn(tx("↩ نسخ احتياطية للمونتاج", "↩ Edit backups")) { versionsDialog() }))
            body.addView(hint(tx("التطبيق بيحفظ نسخة من المونتاج لحاله قبل أي تغيير كبير، عشان لو خرب إشي ترجع زي ما كان. مش فيديوهات — الفيديوهات المصدّرة بأداة «تصدير».", "The app keeps a copy of the edit before any big change so you can go back. These aren't videos — exported videos are in the Export tool.")))
            val gp = getSharedPreferences("pod_ui", MODE_PRIVATE)
            val gOn = gp.getBoolean("guides", false)
            body.addView(rowOf(btn(if (gOn) tx("🟥 خطوط المناطق الآمنة بالمعاينة: ظاهرة", "🟥 Safe-area lines in preview: shown") else tx("⬜ خطوط المناطق الآمنة بالمعاينة: مخفية", "⬜ Safe-area lines in preview: hidden"), gOn) { gp.edit().putBoolean("guides", !gOn).apply(); buildBody() }))
        }

        if (isReel) {
            val c = card("" + tx("حدود الريل", "Reel range"), "range", "${fmt(pp.a)} → ${fmt(pp.end(dd.duration))}", true)
            if (pp.reason.isNotBlank()) c.addView(txt(tx("سبب الاختيار: ${pp.reason}", "Why chosen: ${pp.reason}"), 11f, "#9A9CA3"))
            c.addView(rowOf(btn(tx("البداية −2ث", "Start −2s")) { moveEdge(true, -2.0) }, btn(tx("البداية +2ث", "Start +2s")) { moveEdge(true, 2.0) }))
            c.addView(rowOf(btn(tx("النهاية −2ث", "End −2s")) { moveEdge(false, -2.0) }, btn(tx("النهاية +2ث", "End +2s")) { moveEdge(false, 2.0) }))
            c.addView(rowOf(btn(tx("العنوان", "Title")) { editTitle() }))
        }

        // القالب
        run {
            val cur = PodStudio.tplOf(this, pp)
            val c = card(tx("القالب", "Template"), "tpl", cur.name + if (pp.st.ov.isNotEmpty()) tx(" · معدّل", " · modified") else "", true)
            val demo = LayoutDemoView(this).apply { tpl = cur; names = dd.setup.people.map { it.name }.ifEmpty { listOf("1", "2") }; hasCam = dd.setup.people.map { dd.hasCam(dd.setup.people.indexOf(it)) }.ifEmpty { listOf(true, true) }; landscape = pp.st.orient != "v"; brand = Brand.values(this@PodcastEditActivity); showGuides = pp.st.orient == "v" }
            Thread { val ph = try { DemoPhotos.load(this, sid) } catch (_: Throwable) { null }; runOnUiThread { if (!isFinishing) DemoPhotos.apply(demo, ph) } }.start()
            // النموذج جوّا المشروع = نفس اللقطات والعنوان والكتابة اللي رح تطلع بالتصدير
            demo.crowdSet = dd.crowd
            demo.realShots = pp.shots.toList(); demo.realA = pp.a; demo.realTotal = pp.end(dd.duration) - pp.a
            demo.realTitle = if (pp.st.titleOn) pp.title else ""
            val capsOut = pp.st.captions && dd.tr != null
            if (capsOut) demo.realCaps = PodEngine.captionChunks(dd.tr!!.segs.filter { it.b > pp.a && it.a < pp.end(dd.duration) }, HashSet(pp.emph), if (cur.capStyle != "line") 20 else 42, if (cur.capStyle != "line") 3 else 8)
            val capCap = txt("", 12.5f, "#FFFFFF")
            demo.onCaption = { s -> capCap.post { capCap.text = s } }
            c.addView(demo)
            c.addView(capCap)
            // إشي القالب بدّه بس ما رح يطلع: تنبيه واضح + الحل
            if ((cur.captions || pp.st.captions) && dd.tr == null) {
                c.addView(txt(tx("⚠ القالب فيه كتابة للكلام، بس ما رح تطلع بالفيديو لأنه الكلام مش مفرّغ.", "⚠ The template has captions, but none will show because there's no transcript."), 12.5f, "#FF9F0A"))
                c.addView(rowOf(btn(tx("📝 فرّغ الكلام هلأ", "📝 Transcribe now"), true) { confirmTranscribe() }))
            }
            if (cur.titleOn && pp.title.isBlank()) {
                c.addView(txt(tx("⚠ القالب فيه عنوان افتتاحي بس نص العنوان فاضي — ما رح يطلع.", "⚠ The template has an opening title but its text is empty — it won't show."), 12.5f, "#FF9F0A"))
                c.addView(rowOf(btn(tx("اكتب العنوان", "Write the title"), true) { editTitle() }))
            } else if (cur.titleOn && !pp.st.titleOn) {
                c.addView(txt(tx("⚠ العنوان الافتتاحي مطفي بهالمشروع.", "⚠ The opening title is off in this project."), 12.5f, "#FF9F0A"))
            }
            for (dv in dd.s.devices.values.filter { dv -> dv.started && dv.uploaded && dd.setup.people.none { it.camDev == dv.id || it.micDev == dv.id } && dv.id !in dd.setup.wides }) {
                c.addView(txt(tx("⚠ «${dv.name}» وصل بس مش مستعمل بالمونتاج.", "⚠ “${dv.name}” arrived but isn't used in the edit."), 12.5f, "#FF9F0A"))
                c.addView(rowOf(btn(tx("حدد شو هاد الجهاز", "Say what this device is"), true) { deviceRole(dv) }))
            }
            val missingLogo = cur.items.count { it.kind == "image" && (it.image.isBlank() || !File(it.image).exists()) }
            if (missingLogo > 0) c.addView(txt(tx("⚠ شعار بالقالب صورته مش موجودة — ما رح يطلع.", "⚠ A logo in the template has no image — it won't show."), 12.5f, "#FF9F0A"))
            // اسم القالب + زرين: غيّره (قائمة) أو عدّله
            c.addView(rowOf(
                btn("⇄ " + tx("غيّر القالب", "Change template")) { pickTemplate() },
                btn("✎ " + tx("عدّل «${cur.name}»", "Edit “${cur.name}”"), true) { tplEditor.launch(Intent(this, TemplateEditActivity::class.java).putExtra("id", cur.id)) },
            ))
            if (cur.titleOn) c.addView(rowOf(btn(tx("نص العنوان: ", "Title text: ") + pp.title) { editTitle() }))
            // غيّرت إشي بهالمشروع عن القالب؟ ٣ خيارات واضحة
            if (pp.st.ov.isNotEmpty()) {
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UiKit.bg("#2A2312", 12, this@PodcastEditActivity, "#5C4A1E"); setPadding(dp(12), dp(10), dp(12), dp(10)) }
                box.addView(txt(tx("✎ غيّرت ${pp.st.ov.size} عن القالب بهالمشروع: ", "✎ ${pp.st.ov.size} changed from the template here: ") + pp.st.ov.sorted().joinToString("، ") { TemplateStore.keyName(it) }, 13f, "#FFD21F"))
                if (!cur.builtIn) box.addView(rowOf(btn(tx("احفظهم على «${cur.name}»", "Save them to “${cur.name}”")) {
                    val t2 = cur.copy().also { it.id = cur.id }
                    for (k in pp.st.ov) TemplateStore.pull(pp.st, t2, k)
                    TemplateStore.save(this, t2)
                    edit { q -> q.st.ov.clear() }
                    toast(tx("انحفظ على القالب — المشاريع اللي لسا ما صدّرت بتمشي عليه", "Saved to the template — projects not yet exported follow it"))
                }))
                box.addView(rowOf(
                    btn(tx("＋ قالب جديد فيهم", "＋ New template with them")) { newTemplateFromProject(cur) },
                    btn(tx("↺ رجّع للقالب", "↺ Back to the template")) { editHeavy { q -> TemplateStore.applyTo(cur, q.st); PodStudio.regenerate(dd, q) } },
                ))
                c.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            c.addView(rowOf(btn(tx("📋 انسخ بيانات القالب والمونتاج", "📋 Copy template & edit data")) { copyDebug() }))
        }

        // شو بصير هون — بالكلام العادي
        run {
            val c = card(tx("شو المطبّق بهالمونتاج", "What applies in this edit"), "explain", "", true)
            // شو بصير + شو المطبّق فعليًا ومن وين = بطاقة وحدة
            for ((l, col) in PodExplain.inEdit(this, dd, pp)) if (col == UiKit.ORANGE || l.startsWith("👁")) c.addView(txt(l, 12.5f, col))
            val focus = listOf("tpl_pace", "tpl_pace", "tpl_pace", "tpl_pace", "tpl_pace", "tpl_pace", "tpl_trans")
            val projCard = emptyList<String?>()
            for ((ri, row) in ruleRows().withIndex()) {
                val (name, v, from) = row
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
                // دبل تاب: بيوديك على مكان تعديل هالخيار (بالمشروع إذا معدّل هون، وإلا بالقالب)
                var lastTap = 0L
                r.setOnClickListener {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastTap < 500) {
                        val pc = projCard.getOrNull(ri)
                        if (from.startsWith("✎") && pc != null) focusCard(pc)
                        else tplEditor.launch(Intent(this, TemplateEditActivity::class.java).putExtra("id", PodStudio.tplOf(this, pp).id).putExtra("focus", focus.getOrElse(ri) { "tpl_pace" }))
                    } else Toast.makeText(this, tx("دبل تاب عشان تعدّله", "Double-tap to edit it"), Toast.LENGTH_SHORT).show()
                    lastTap = now
                }
                r.addView(UiKit.text(this, name, 13.5f, UiKit.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(UiKit.text(this, v + (if (from.startsWith("✎")) "  ✎" else ""), 13f, if (from.startsWith("✎")) "#FF9F0A" else UiKit.SUB), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
                c.addView(r)
            }
            c.addView(hint(tx("كل هاد من القالب «${PodStudio.tplOf(this, pp).name}» — دبل تاب على أي سطر بيفتحلك مكان تعديله.", "All of this comes from “${PodStudio.tplOf(this, pp).name}” — double-tap a row to edit it.")))
            val man = manualShots()
            if (man.isNotEmpty()) {
                c.addView(txt(tx("🔒 في ${man.size} لقطة يدوية/مثبّتة — هاي بتغلب القالب: ", "🔒 ${man.size} manual/locked shot(s) — these override the template: ") + man.take(4).joinToString("، ") { "${fmt(it.start)}–${fmt(it.end)} " + it.label(dd.setup) }, 13f, UiKit.ORANGE))
                c.addView(rowOf(btn(tx("🗑 امسح اللقطات اليدوية ورجّع للقالب", "🗑 Remove manual shots, back to template")) { clearManual() }))
            }
        }

        // المصادر: تدوير وقص لكل كاميرا
        run {
            val keys = dd.setup.people.withIndex().filter { it.value.camDev.isNotEmpty() }.map { "p${it.index}" to it.value.name } + dd.setup.wides.indices.map { "w$it" to tx("العامة ${it + 1}", "Wide ${it + 1}") }
            val edited = keys.filter { (k, _) -> (dd.setup.srcRot[k] ?: 0) != 0 || dd.setup.srcCrop[k] != null || (dd.setup.srcFill[k] ?: "auto") != "auto" }
            val unused = dd.s.devices.values.count { dv -> dv.started && dv.uploaded && dd.setup.people.none { it.camDev == dv.id || it.micDev == dv.id } && dv.id !in dd.setup.wides }
            val c = card(tx("المصادر والأسماء", "Sources & names"), "sources", if (unused > 0) tx("⚠ $unused جهاز وصل ومش مستعمل", "⚠ $unused received device(s) unused") else if (edited.isEmpty()) tx("${keys.size} كاميرا · بدون تعديل", "${keys.size} cameras · unedited") else tx("معدّل: ", "Edited: ") + edited.joinToString("، ") { it.second }, true)
            for ((k, n) in keys) {
                val rot = dd.setup.srcRot[k] ?: 0
                val parts = listOfNotNull(if (rot != 0) tx("لف $rot°", "rotated $rot°") else null, if (dd.setup.srcCrop[k] != null) tx("مقصوص", "cropped") else null,
                    (dd.setup.srcFill[k] ?: "auto").takeIf { it != "auto" }?.let { tx("الفراغ: ", "gap: ") + it })
                c.addView(txt("• $n — " + (if (parts.isEmpty()) tx("متل ما هو", "as recorded") else parts.joinToString(" · ")), 13f))
            }
            // الأجهزة اللي وصلت: شو كل واحد (كاميرا مين، عامة، أو مش مستعمل)
            c.addView(sub(tx("الأجهزة بهالتسجيل", "Devices in this recording")))
            for (dv in dd.s.devices.values.filter { it.started }) {
                val pi = dd.setup.people.indexOfFirst { it.camDev == dv.id || it.micDev == dv.id }
                val role = when {
                    pi >= 0 -> (if (dd.setup.people[pi].camDev == dv.id) tx("كاميرا ", "camera of ") else tx("مايك ", "mic of ")) + dd.setup.people[pi].name
                    dv.id in dd.setup.wides -> tx("كاميرا عامة", "wide camera")
                    else -> null
                }
                val icon = if (dv.role == "both") "🎥" else "🎤"
                val stat = if (!dv.uploaded) tx(" (ما وصل)", " (not received)") else ""
                if (role == null) c.addView(txt("⚠ $icon ${dv.name}$stat — " + tx("مش مستعمل بالمونتاج", "not used in the edit"), 13f, "#FF9F0A"))
                c.addView(rowOf(btn("$icon ${dv.name}$stat: " + (role ?: tx("حدد شو هو", "say what it is")), role == null) { deviceRole(dv) }))
                if (dv.uploaded) c.addView(txt("📦 " + PodStudio.devSizes(this, sid, dv.id), 12f, "#9A9CA3"))
                // كم شخص بهالكاميرا (بيغيّر الكادر: شخص → زوم عليه، شخصين → الاثنين، وما بيطلع مربع عائم فوق حدا)
                if (pi >= 0 && dd.setup.people[pi].camDev == dv.id) {
                    val mode = dd.setup.srcCrowd["p$pi"] ?: ""
                    val auto = pi in dd.crowd
                    val lbl = when (mode) { "1", "one" -> tx("👤 شخص واحد", "👤 One person"); "2" -> tx("👥 شخصين", "👥 Two people"); "3", "many" -> tx("👥 ٣ أو أكثر", "👥 Three or more"); else -> tx("تلقائي", "Auto") + if (auto) tx(" (شايف أكثر من شخص)", " (sees several)") else tx(" (شايف شخص)", " (sees one)") }
                    c.addView(rowOf(btn(tx("بالكاميرا: ", "In camera: ") + lbl) { cameraCount(pi) }))
                }
            }
            c.addView(sub(tx("الأسماء (بتطلع على الفيديو وبكل مكان)", "Names (shown on the video and everywhere)")))
            for ((i, person) in dd.setup.people.withIndex()) c.addView(rowOf(btn("✎  " + person.name) { renamePerson(i) }))
            c.addView(rowOf(btn(tx("عدّل المصادر (تدوير، قص، الفراغ)", "Edit sources (rotate, crop, gap)"), true) { startActivity(Intent(this, SourceEditActivity::class.java).putExtra("session", sid).putExtra("vertical", pp.st.orient == "v")) }))
            c.addView(rowOf(btn(tx("✂ قص «الشكل الخاص» (للفترات الدورية)", "✂ “Special look” crop (for the periodic look)")) { startActivity(Intent(this, SourceEditActivity::class.java).putExtra("session", sid).putExtra("vertical", pp.st.orient == "v").putExtra("look", true)) }))
            c.addView(hint(tx("مثلًا إذا جوال صوّر مايل أو بالطول وبدك إياه بالعرض. بينطبق على كل لقطات هالكاميرا.", "E.g. if a phone recorded sideways or upright and you want it landscape. Applies to every shot from that camera.")))
        }

        // الردود القصيرة والأصوات (آه، امم / سعال، دق…)
        run {
            val evs = dd.an.events
            val ov = PodVoice.overrides(this, sid)
            val noise = evs.count { it.verdict != VEvent.SPEECH && it.key !in ov.accept }
            val left = evs.count { it.verdict != VEvent.SPEECH && it.key !in ov.accept && it.key !in ov.reject }
            val c2 = card(tx("الأصوات اللي مش كلام", "Sounds that aren't speech"), "sounds", if (left > 0) tx("$left صوت لسا ما راجعته", "$left sounds not reviewed yet") else tx("كله مراجع ✓", "All reviewed ✓"), left > 0)
            c2.addView(hint(tx("السعال والعطس والتنحنح والدق والخشخشة ما بتقلب الكاميرا. التمييز: نغمة صوت الحنجرة + مُصنّف أصوات على الجوال + التفريغ إذا موجود. اللي مش متأكدين منه ما بيقلب، وبيطلع هون تقرر فيه.", "Coughs, sneezes, throat clearing, knocks and rustling don't switch the camera. Detection: voice pitch + an on-phone sound classifier + the transcript if any. Uncertain sounds don't switch and are listed here for you to decide.")))
            c2.addView(rowOf(btn(if (ov.laugh) tx("الضحك بيقلب الكاميرا: نعم", "Laughter switches the camera: yes") else tx("الضحك بيقلب الكاميرا: لا", "Laughter switches the camera: no"), ov.laugh) {
                ov.laugh = !ov.laugh; PodVoice.saveOverrides(this, sid, ov); reloadVoice()
            }))
            c2.addView(rowOf(
                btn(tx("راجع الأصوات (${evs.count { it.verdict != VEvent.SPEECH }})", "Review sounds (${evs.count { it.verdict != VEvent.SPEECH }})")) { reviewSounds() },
                btn(tx("بعّد الكاميرا عن شخص بفترة", "Keep the camera off someone for a while")) { ignoreRangeDialog() },
            ))
            if (ov.ignores.isNotEmpty()) c2.addView(txt(tx("فترات «بعيد عنه»: ", "“Keep off” ranges: ") + ov.ignores.joinToString("، ") { "${dd.names.getOrNull(it[0].toInt()) ?: "?"} ${fmt(it[1])}→${fmt(it[2])}" }, 12f))
        }

        advancedToggle()

        // الكاميرات
        run {
            val c = card(tx("اللقطات", "Shots"), "strategy", if (pp.st.pinned.isNotEmpty()) "📌 ${pp.st.pinned.size}" else "")
            c.addView(hint(tx("طريقة التقليب كلها من القالب (المونتاج ← ٦ خيارات). هون بس أشياء خاصة بهالتصوير.", "How cutting works comes entirely from the template (Editing → 6 options). Only things specific to this shoot are here.")))
            if (pp.st.layout == "grid") c.addView(rowOf(btn(tx("الكل ظاهر طول الوقت (من القالب)", "Everyone all the time (template)"), true) {}))
            if (pp.st.orient == "v") c.addView(rowOf(btn(if (pp.st.stackReel) tx("كاميرات بالعرض: فوق وتحت طول الوقت ✓", "Landscape cameras: top & bottom all the time ✓") else tx("كاميرات بالعرض: فوق وتحت — مطفي", "Landscape cameras: top & bottom — off"), pp.st.stackReel) {
                editHeavy { q -> q.st.stackReel = !q.st.stackReel; PodStudio.regenerate(dd, q) } }))
            c.addView(rowOf(btn(tx("أعد توليد اللقطات التلقائية", "Regenerate automatic shots"), true) {
                AlertDialog.Builder(this).setMessage(tx("اللقطات التلقائية رح تنعمل من جديد حسب القالب (اللقطات اليدوية بتضل). بتنحفظ نسخة من الحالية أول.", "Automatic shots will be rebuilt from the template (manual shots stay). The current version is saved first."))
                    .setPositiveButton(tx("أعد التوليد", "Regenerate")) { _, _ ->
                        ProjectStore.snapshot(this, sid, pp, tx("قبل إعادة التوليد", "Before regenerating"))
                        editHeavy { PodStudio.regenerate(dd, it) }
                    }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
            }))
            if (manualShots().isNotEmpty()) c.addView(rowOf(btn(tx("🗑 امسح ${manualShots().size} لقطة يدوية", "🗑 Remove ${manualShots().size} manual shot(s)")) { clearManual() }))
            if (dd.setup.vlog && dd.setup.vlogLayout == "switch") {
                c.addView(rowOf(btn(if (pp.st.liveSwitch) tx("التبديل: حسب اختياري وقت التصوير", "Switching: my picks while filming") else tx("التبديل: تلقائي على اللي بيحكي", "Switching: automatic on the speaker"), pp.st.liveSwitch) {
                    editHeavy { q -> q.st.liveSwitch = !q.st.liveSwitch; PodStudio.regenerate(dd, q) }
                }))
                if (dd.live.isEmpty()) c.addView(txt(tx("ما اخترت كاميرات وقت التصوير — التبديل تلقائي.", "You didn't pick cameras while filming — switching is automatic."), 11f, "#9A9CA3"))
            }
        }

        // الصوت (من إعداد الجلسة — للعرض)
        run {
            val c = card(tx("الصوت", "Audio"), "audio", (if (dd.setup.level) tx("توحيد المستوى", "Levelling") else "") + (if (dd.setup.duck < 0.999f) " · " + tx("تخفيض الساكتين", "Ducking") else ""))
            c.addView(txt(tx("توحيد مستوى الأصوات: ", "Level matching: ") + if (dd.setup.level) tx("شغّال", "on") else tx("مطفي", "off"), 13f))
            c.addView(txt(tx("تخفيض المايكات الساكتة: ", "Ducking silent mics: ") + if (dd.setup.duck < 0.999f) "${((1f - dd.setup.duck) * 100).toInt()}%" else tx("مطفي", "off"), 13f))
            c.addView(hint(tx("كل شخص صوته من مايكه، وإذا ما وصل مايكه بيتاخد صوت كاميرته. هاي من إعداد الجلسة وقت التسجيل.", "Each person's sound comes from their mic, or their camera's sound if the mic file is missing. These come from the session setup at recording time.")))
        }

        // التأطير والزوم
        run {
            val c = card(tx("التأطير والزوم", "Framing and zoom"), "frame",
                tx("الصورة: ", "Picture: ") + when (pp.st.frame) { "fill" -> tx("ملء", "fill"); "fit" -> tx("كاملة", "whole"); else -> tx("ذكي", "smart") } +
                    (if (pp.st.pinned.isNotEmpty()) " • 📌 ${pp.st.pinned.size}" else ""))
            c.addView(txt(tx("الكاميرا داخل الإطار", "Camera inside the frame"), 12f, "#9A9CA3"))
            c.addView(rowOf(*listOf("smart" to tx("ذكي (بدون قص مؤذٍ)", "Smart (no harmful crop)"), "fit" to tx("الصورة كاملة", "Whole picture"), "fill" to tx("ملء مع قص", "Fill with crop")).map { (k, n) ->
                btn(n, pp.st.frame == k) { edit { it.st.frame = k } } }.toTypedArray()))
            c.addView(hint(tx("ذكي: إذا اختلف اتجاه الكاميرا عن الفيديو (عمودي داخل أفقي مثلًا) تظهر الصورة كاملة وخلفها نسخة مموّهة، وإلا تملأ الإطار.", "Smart: if the camera orientation differs from the video (portrait inside landscape, say) the whole picture shows over a blurred copy; otherwise it fills the frame.")))
            c.addView(txt(tx("عندما لا يوجد فيديو لشخص (جهازه توقف مبكرًا أو لم يُصوَّر)", "When a person has no video (their device stopped early or didn't film)"), 12f, "#9A9CA3"))
            c.addView(rowOf(*listOf("other" to tx("كاميرا أخرى", "Another camera"), "freeze" to tx("آخر صورة", "Last frame"), "card" to tx("بطاقة باسمه", "Name card")).map { (k, n) ->
                btn(n, pp.st.fill == k) { edit { it.st.fill = k } } }.toTypedArray()))
            c.addView(txt(tx("لحظة قلب الكاميرا (أمامية ↔ خلفية)", "The moment a camera flips (front ↔ back)"), 12f, "#9A9CA3"))
            c.addView(rowOf(*listOf("freeze" to tx("آخر صورة", "Last frame"), "other" to tx("كاميرا أخرى", "Another camera"), "blur" to tx("تمويه", "Blur")).map { (k, n) ->
                btn(n, pp.st.flipFill == k) { edit { it.st.flipFill = k } } }.toTypedArray()))
            if (PodStudio.tplOf(this, pp).follow) { c.addView(sub(tx("الزوم", "Zoom"))); zoomInto(c, dd, pp) }
            else {
                c.addView(hint(tx("الكادر ثابت (القالب ← المونتاج ← ٤ الكادر): ولا زوم ولا لحاق راس — متل ما انصوّر.", "Framing is still (template → Editing → 4 Framing): no zoom, no head-following — as recorded.")))
                c.addView(rowOf(btn(tx("المصادر: تدوير وقص", "Sources: rotate & crop")) { startActivity(Intent(this, SourceEditActivity::class.java).putExtra("session", sid).putExtra("vertical", (p?.st?.orient ?: "h") == "v")) }))
            }
        }

        // الإيقاع والوقفات
        run {
            val c = card(tx("الإيقاع والوقفات", "Pacing and pauses"), "silence", if (isReel) pp.st.silence else "")
            if (isReel) {
                c.addView(rowOf(*listOf("off" to tx("إيقاف", "Off"), "light" to tx("خفيف", "Light"), "balanced" to tx("متوازن", "Balanced"), "intense" to tx("مكثّف", "Intense")).map { (k, n) ->
                    btn(n, pp.st.silence == k) {
                        editHeavy { q ->
                            q.st.silence = k
                            q.cuts.removeAll { it.kind == PCut.SILENCE && !it.locked }
                            q.cuts.addAll(PodEngine.silenceCuts(dd.an, dd.turns, k, q.a, q.end(dd.duration)))
                        }
                    }
                }.toTypedArray()))
                c.addView(hint(tx("القص عند فواصل الكلام فقط مع هامش قبل الكلام وبعده؛ الضحك وأي صوت مسموع لا يُقص، والوقفة التعبيرية تُقصَّر ولا تُحذف (إلا في المكثّف). لا يُسرَّع الصوت.", "Cuts happen only at speech gaps with a margin before and after; laughter and any audible sound are not cut, and expressive pauses are shortened, not removed (except in intense). Audio is never sped up.")))
            } else {
                val gaps = pp.cuts.filter { it.kind == PCut.GAP }
                c.addView(txt(if (gaps.isEmpty()) tx("معطّل (الافتراضي للحلقة الكاملة)", "Off (default for the full episode)") else tx("محذوف: ${gaps.size} فراغ طويل (${fmt(gaps.sumOf { it.b - it.a })})", "Removed: ${gaps.size} long gaps (${fmt(gaps.sumOf { it.b - it.a })})"), 12f))
                c.addView(rowOf(
                    btn(tx("راجع الفراغات الطويلة", "Review long gaps")) { gapsDialog() },
                    btn(tx("أعدها كلها", "Restore all")) { edit { it.cuts.removeAll { x -> x.kind == PCut.GAP } } },
                ))
            }
        }

        // النص والكتابة
        run {
            val c = card(tx("النصوص", "Text"), "text", if (dd.tr == null) tx("لا يوجد نص", "No transcript") else if (pp.st.captions) tx("الكتابة مفعّلة", "Captions on") else "")
            val tr = dd.tr
            if (tr == null) {
                c.addView(txt(tx("لا يوجد تفريغ بعد. التفريغ يحتاج خدمة خارجية اختيارية بمفتاحك (🤖)، ويرفع صوت الحلقة عند طلبك فقط.", "No transcript yet. Transcription needs an optional external service with your key (🤖), and uploads the episode audio only when you ask."), 12f, "#9A9CA3"))
                c.addView(rowOf(btn(tx("🤖 الخدمة", "🤖 Service")) { PodUi.aiDialog(this) }, btn(tx("📝 فرّغ الكلام", "📝 Transcribe"), true) { confirmTranscribe() }))
            } else {
                c.addView(txt(tx("${tr.segs.size} جملة مفرّغة (${tr.source})", "${tr.segs.size} transcribed sentences (${tr.source})"), 12f, "#9A9CA3"))
                c.addView(rowOf(
                    btn(tx("📝 النص", "📝 Text")) { transcriptDialog() },
                    btn(tx("🧠 افهم الحوار", "🧠 Understand")) { confirmSemantic() },
                ))
                c.addView(rowOf(
                    btn(if (pp.st.captions) tx("الكتابة: مفعّلة", "Captions: on") else tx("الكتابة: معطّلة", "Captions: off"), pp.st.captions) { edit { it.st.captions = !it.st.captions } },
                    btn(if (pp.st.highlight) tx("تمييز كلمة: نعم", "Word highlight: yes") else tx("تمييز كلمة: لا", "Word highlight: no"), pp.st.highlight) { edit { it.st.highlight = !it.st.highlight } },
                ))
                if (pp.st.captions) c.addView(rowOf(*listOf(0.85f to tx("صغير", "Small"), 1f to tx("عادي", "Normal"), 1.2f to tx("كبير", "Large")).map { (v, n) -> btn(n, abs(pp.st.capSize - v) < 0.01f) { edit { it.st.capSize = v } } }.toTypedArray()))
            }
            c.addView(rowOf(
                btn(if (pp.st.titleOn) tx("عنوان افتتاحي: مفعّل", "Opening title: on") else tx("عنوان افتتاحي: معطّل", "Opening title: off"), pp.st.titleOn) { edit { it.st.titleOn = !it.st.titleOn } },
                btn(tx("نص العنوان", "Title text")) { editTitle() },
            ))
            c.addView(hint(tx("الكتابة سطر أو سطران بخط عربي واتجاه صحيح، في منطقة آمنة بعيدًا عن أزرار المنصات (تظهر الأدلة في المعاينة).", "Captions are one or two lines with correct Arabic direction, in a safe area away from platform buttons (guides show in the preview).")))
        }

        // التصدير
        run {
            val c = card(tx("التصدير", "Export"), "export", (if (pp.st.orient == "v") "9:16" else if (pp.st.orient == "h") "16:9" else "16:9 + 9:16") + " • ${pp.st.height}p", true)
            manualShots().takeIf { it.isNotEmpty() }?.let { man ->
                c.addView(txt(tx("🔒 انتبه: ${man.size} لقطة يدوية/مثبّتة رح تطلع زي ما هي بالتصدير (مش حسب القالب): ", "🔒 Note: ${man.size} manual/locked shot(s) will export as they are (not by the template): ") + man.take(3).joinToString("، ") { "${fmt(it.start)}–${fmt(it.end)} " + it.label(dd.setup) }, 13f, UiKit.ORANGE))
                c.addView(rowOf(btn(tx("🗑 امسحها ورجّع للقالب", "🗑 Remove them, back to template")) { clearManual() }))
            }
            val opts = if (isReel) listOf("v" to "9:16", "h" to "16:9") else listOf("h" to "16:9", "v" to "9:16", "both" to tx("الاثنين", "Both"))
            c.addView(rowOf(*opts.map { (k, n) -> btn(n, pp.st.orient == k) { edit { it.st.orient = k } } }.toTypedArray()))
            c.addView(rowOf(btn(tx("360p للفحص", "360p check"), pp.st.height <= 360) { edit { it.st.height = 360 } }, btn("720p", pp.st.height in 361..1079) { edit { it.st.height = 720 } }, btn("1080p", pp.st.height >= 1080) { edit { it.st.height = 1080 } }))
            if (pp.st.height <= 360) c.addView(txt(tx("نسخة صغيرة (حوالي 10 ميغا للدقيقة) عشان تبعتها لـClaude يفحص المونتاج. نفس اللقطات والنصوص بالضبط، بس جودة أقل.", "A small copy (about 10 MB a minute) to send to Claude for checking. Exactly the same shots and texts, lower quality."), 11.5f, "#FF9F0A"))
            // إنترو وأوترو: فيديو قبل الحلقة وبعدها (بصوته، مع تلاشي خفيف) — بأداة «الإنترو»
            run {
            val c = card(tx("قبل الحلقة وبعدها", "Before and after"), "intro", listOfNotNull(if (pp.introSrc == "self") tx("إنترو من الحلقة", "intro from episode") else if (pp.intro.isNotBlank()) tx("إنترو", "intro") else null, if (pp.mid.isNotBlank()) tx("فاصل", "bridge") else null, if (pp.outro.isNotBlank()) tx("أوترو", "outro") else null).joinToString(" · ").ifEmpty { tx("ما في", "none") }, true)
            fun clipName(path: String) = if (path.isBlank() || !File(path).exists()) "" else File(path).name.substringAfter("_")
            // ── الإنترو: فيديو من الجوال، أو مقطع من الحلقة نفسها بأسلوب مونتاج لحاله
            c.addView(sub(tx("🎬 الإنترو", "🎬 Intro")))
            c.addView(strip("", listOf(
                btn(tx("بدون", "None"), pp.introSrc == "file" && pp.intro.isBlank() || pp.introSrc == "none") { edit { it.introSrc = "file"; it.intro = "" } },
                btn(if (pp.introSrc == "file" && clipName(pp.intro).isNotEmpty()) "📁 " + clipName(pp.intro) else tx("📁 فيديو من الجوال", "📁 A video file"), pp.introSrc == "file" && pp.intro.isNotBlank()) { pickClip = "intro"; clipPicker.launch(arrayOf("video/*")) },
                btn(tx("✂ مقطع من الحلقة", "✂ A clip from the episode"), pp.introSrc == "self") { edit { it.introSrc = "self"; if (it.ib <= it.ia) { val t0 = tl.selected.takeIf { x -> x >= 0 } ?: it.a; it.ia = t0; it.ib = min(dd.duration, t0 + 10.0) } } },
            )))
            if (pp.introSrc == "file" && pp.intro.isNotBlank()) fxRow(c, "intro", pp)
            if (pp.introSrc == "self") {
                c.addView(txt(tx("المقطع: من ${fmt(pp.ia)} لـ ${fmt(pp.ib)} · ${(pp.ib - pp.ia).toInt()} ث", "Clip: from ${fmt(pp.ia)} to ${fmt(pp.ib)} · ${(pp.ib - pp.ia).toInt()} s"), 13f, "#FFD21F"))
                val ms = pp.imarks.filter { it.first < pp.ib }.sortedBy { it.first }
                c.addView(txt(if (ms.isEmpty()) tx("مين بيطلع: 🤖 تلقائي حسب القالب", "Who's on screen: 🤖 automatic (template)")
                    else tx("مين بيطلع: ✋ أنت حددت — ", "Who's on screen: ✋ you chose — ") + ms.mapIndexed { i, m -> fmt(if (i == 0) pp.ia else m.first) + " " + (if (m.second < 0) tx("الكل", "all") else dd.setup.people.getOrNull(m.second)?.name ?: "?") }.joinToString(" · "), 13f, UiKit.TEXT))
                // مستعرض: بتختار الكاميرا، بتشغّل بصوت، بتحدد البداية والنهاية، ومين يطلع من كل لحظة
                val pick = UiKit.primary(this, "🎞  " + tx("اختار المقطع ومين يطلع (شغّل بصوت)", "Pick the clip and who's on screen (with sound)")) {
                    introPicker.launch(Intent(this, IntroPickActivity::class.java).putExtra("session", sid).putExtra("a", pp.ia).putExtra("b", pp.ib).putExtra("cam", pp.icam)
                        .putExtra("mt", pp.imarks.map { it.first }.toDoubleArray()).putExtra("mw", pp.imarks.map { it.second }.toIntArray()))
                }
                c.addView(pick, UiKit.full(this, pick, 6, 54))
                c.addView(hint(tx("الإنترو بدون أسماء ولا عنوان، ومونتاجه لحاله — ما بيغيّر الحلقة. بيطلع بالتصدير بس.", "The intro has no names or title and its own edit — it doesn't change the episode. Export only.")))
            }
            // ── فاصل بين الإنترو والحلقة (لوجو…)
            c.addView(sub(tx("⏸ فاصل بعد الإنترو", "⏸ Bridge after the intro")))
            c.addView(strip("", listOfNotNull(
                btn(if (clipName(pp.mid).isEmpty()) tx("📁 ضيف فيديو (لوجو…)", "📁 Add a video (logo…)") else "📁 " + clipName(pp.mid), pp.mid.isNotBlank()) { pickClip = "mid"; clipPicker.launch(arrayOf("video/*")) },
                if (pp.mid.isNotBlank()) btn(tx("✕ شيله", "✕ Remove")) { edit { it.mid = "" } } else null,
            )))
            if (pp.mid.isNotBlank()) fxRow(c, "mid", pp)
            // ── الانتقالات بين الإنترو والحلقة، وبين الحلقة والأوترو
            fun trRow(label: String, cur: String, sec: Double, set: (PodProject, String) -> Unit, setSec: (PodProject, Double) -> Unit) {
                val names = listOf("black" to tx("تلاشي لأسود", "Fade to black"), "white" to tx("تلاشي لأبيض", "Fade to white"), "flash" to tx("ومضة", "Flash"), "cut" to tx("قطع مباشر", "Hard cut"))
                c.addView(strip(label, names.map { (k, n) -> btn(n, cur == k) { edit { q -> set(q, k) } } }))
                if (cur == "black" || cur == "white") c.addView(strip(tx("مدته", "Length"), listOf(0.3, 0.6, 1.0, 1.5).map { v -> btn(String.format(Locale.US, "%.1f ث", v), abs(sec - v) < 0.01) { edit { q -> setSec(q, v) } } }))
            }
            if (pp.introSrc == "self" || pp.intro.isNotBlank() || pp.mid.isNotBlank()) trRow(tx("قبل الحلقة ← الحلقة", "Intro → episode"), pp.introTr, pp.introTrSec, { q, k -> q.introTr = k }, { q, v -> q.introTrSec = v })
            c.addView(sub(tx("🎬 الأوترو", "🎬 Outro")))
            c.addView(strip("", listOfNotNull(
                btn(if (clipName(pp.outro).isEmpty()) tx("📁 ضيف أوترو", "📁 Add an outro") else "📁 " + clipName(pp.outro), pp.outro.isNotBlank()) { pickClip = "outro"; clipPicker.launch(arrayOf("video/*")) },
                if (pp.outro.isNotBlank()) btn(tx("✕ شيله", "✕ Remove")) { edit { it.outro = "" } } else null,
            )))
            if (pp.outro.isNotBlank()) fxRow(c, "outro", pp)
            if (pp.outro.isNotBlank()) trRow(tx("الحلقة ← أوترو", "Episode → outro"), pp.outroTr, pp.outroTrSec, { q, k -> q.outroTr = k }, { q, v -> q.outroTrSec = v })
            c.addView(hint(tx("لحد ٣ دقايق لكل واحد. ما بيطلعوا بالمعاينة ٢٠ ثانية، بس بالتصدير. الانتقال بين اللقطات جوّا الحلقة من القالب (الإيقاع ← الانتقالات).", "Up to 3 minutes each. Not in the 20 s preview, only in the export. Transitions between shots inside the episode are in the template (Pacing → Transitions).")))
            }
            // مراجعة سريعة قبل التصدير
            if (tool == "export") for ((l, col) in PodExplain.inEdit(this, dd, pp).take(6)) c.addView(UiKit.text(this, l, 12.5f, col).apply { setPadding(0, dp(2), 0, dp(2)) })
            c.addView(rowOf(btn("👁 " + tx("شوف الحلقة كاملة (خفيف 360p)", "Watch the whole episode (light 360p)")) { startJob(PodcastExportService.ACTION_EXPORT, forceH = 360) }))
            c.addView(rowOf(btn(tx("⬆ صدّر الفيديو", "⬆ Export the video"), true) { startJob(PodcastExportService.ACTION_EXPORT) }))
            c.addView(hint(tx("الاثنين بيعملوا نفس المونتاج بالزبط من أوله لآخره (مع الإنترو والفاصل والأوترو). التصدير بينحفظ بالمعرض لحاله أول ما يخلص، حتى لو طلعت من التطبيق. الخفيف بس للمشاهدة.", "Both make the exact same edit start to end (with intro, bridge and outro). The export saves itself to the gallery when it finishes, even if you leave the app. The light one is just for watching.")))
            c.addView(sub(tx("📂 تصديرات هالمشروع", "📂 This project's exports")))
            PodUi.exportList(this, c, sid, pp.id) { buildBody() }
            if (dd.tr != null) c.addView(rowOf(btn(tx("📄 ملف SRT", "📄 SRT file")) { startJob(PodcastExportService.ACTION_SRT) }, btn(tx("🌐 SRT مترجم…", "🌐 Translated SRT…")) { translateSrt() }))
            c.addView(hint(tx("التصدير ينتج ملفًا جديدًا؛ التسجيل الأصلي والمشروع لا يتغيران، ولا يُحذف شيء عند الفشل.", "Export creates a new file; the original recording and the project don't change, and nothing is deleted on failure.")))
        }
    }


    private fun fxOf(q: PodProject, which: String) = when (which) { "intro" -> q.introFx; "mid" -> q.midFx; else -> q.outroFx }
    private fun clipPath(q: PodProject, which: String) = when (which) { "intro" -> q.intro; "mid" -> q.mid; else -> q.outro }

    /** سطر تحت المقطع: شو معدّل فيه، وزر يفتح التعديل */
    private fun fxRow(c: LinearLayout, which: String, pp: PodProject) {
        val fx = fxOf(pp, which)
        val parts = ArrayList<String>()
        if (fx.rot % 360 != 0) parts += "↻ ${fx.rot % 360}°"
        if (fx.mirror) parts += tx("مقلوب", "flipped")
        parts += if (fx.fill) tx("ملء الشاشة", "fill") else tx("كامل", "whole")
        if (fx.zoom > 1.01f) parts += "×" + String.format(Locale.US, "%.1f", fx.zoom)
        c.addView(rowOf(btn("✎ " + tx("الصورة: ", "Picture: ") + parts.joinToString(" · ")) { fxSheet(which) }))
    }

    /** تعديل صورة المقطع: تدوير، قلب، كامل/ملء الشاشة، تكبير وموضع — مع معاينة بنفس شكل الفيديو */
    private fun fxSheet(which: String) {
        val pp = p ?: return
        val path = clipPath(pp, which)
        val f = File(path); if (!f.exists()) return
        val fx = fxOf(pp, which).copy()
        val vert = pp.st.orient == "v"
        val da = if (vert) 9f / 16f else 16f / 9f
        val src: android.graphics.Bitmap? = try {
            val r = android.media.MediaMetadataRetriever(); r.setDataSource(f.absolutePath)
            val d = (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
            val bm = r.getFrameAtTime(min(1_000_000L, d * 500L), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            r.release(); bm?.let { if (it.width > 720) android.graphics.Bitmap.createScaledBitmap(it, 720, 720 * it.height / it.width, true) else it }
        } catch (_: Throwable) { null }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val img = android.widget.ImageView(this).apply { setBackgroundColor(android.graphics.Color.BLACK); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
        val pw = if (vert) dp(180) else dp(320)
        col.addView(img, LinearLayout.LayoutParams(pw, (pw / da).toInt()).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ctl)
        fun draw() { src?.let { img.setImageBitmap(fxPreview(it, fx, da)) } }
        fun build() {
            ctl.removeAllViews()
            ctl.addView(rowOf(
                btn("↻ " + tx("دوّر ٩٠°", "Rotate 90°")) { fx.rot = (fx.rot + 90) % 360; build() },
                btn("⇋ " + tx("اقلب", "Flip"), fx.mirror) { fx.mirror = !fx.mirror; build() },
            ))
            ctl.addView(rowOf(
                btn(tx("الصورة كاملة", "Whole picture"), !fx.fill) { fx.fill = false; build() },
                btn(tx("ملء الشاشة (قص)", "Fill screen (crop)"), fx.fill) { fx.fill = true; build() },
            ))
            ctl.addView(rowOf(*listOf(1f, 1.5f, 2f, 3f).map { z -> btn("×" + (if (z == 1f) "1" else String.format(Locale.US, "%.1f", z)), abs(fx.zoom - z) < 0.01f) { fx.zoom = z; build() } }.toTypedArray()))
            val r = fx.region(src?.let { it.width.toFloat() / it.height } ?: 16f / 9f, da)
            if (r[2] - r[0] < 0.999f) ctl.addView(seekRow(tx("⟷ يمين/شمال", "⟷ Left/right"), fx.cx) { fx.cx = it; draw() })
            if (r[3] - r[1] < 0.999f) ctl.addView(seekRow(tx("↕ فوق/تحت", "↕ Up/down"), fx.cy) { fx.cy = it; draw() })
            ctl.addView(rowOf(btn(tx("رجّعه زي ما كان", "Reset")) { fx.rot = 0; fx.mirror = false; fx.fill = false; fx.zoom = 1f; fx.cx = 0.5f; fx.cy = 0.5f; build() }))
            draw()
        }
        build()
        val name = when (which) { "intro" -> tx("الإنترو", "Intro"); "mid" -> tx("الفاصل", "Bridge"); else -> tx("الأوترو", "Outro") }
        AlertDialog.Builder(this).setTitle(tx("صورة $name", "$name picture")).setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton(tx("تم", "Done")) { _, _ -> edit { q -> when (which) { "intro" -> q.introFx = fx; "mid" -> q.midFx = fx; else -> q.outroFx = fx } } }
            .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    private fun seekRow(label: String, v: Float, set: (Float) -> Unit): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        r.addView(txt(label, 13f, UiKit.TEXT))
        r.addView(android.widget.SeekBar(this).apply {
            max = 100; progress = (v * 100).toInt(); minimumHeight = dp(40)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, pr: Int, u: Boolean) { if (u) set(pr / 100f) }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })
        return r
    }

    /** نفس حساب التصدير (ClipFx.region) بس على صورة: تدوير مع عقارب الساعة، بعدين قلب، بعدين المنطقة */
    private fun fxPreview(src: android.graphics.Bitmap, fx: ClipFx, da: Float): android.graphics.Bitmap {
        val m = android.graphics.Matrix(); m.postRotate(fx.rot.toFloat()); if (fx.mirror) m.postScale(-1f, 1f)
        val o = android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val W = 480; val H = (W / da).toInt()
        val out = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out); cv.drawColor(android.graphics.Color.BLACK)
        val pnt = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val r = fx.region(src.width.toFloat() / src.height, da)
        val ra = r[4]
        if (!fx.fill || abs(ra - da) > 0.01f) {
            val full = ClipFx(fx.rot, fx.mirror, true, 1f).region(src.width.toFloat() / src.height, da)
            pnt.alpha = 90
            cv.drawBitmap(o, android.graphics.Rect((full[0] * o.width).toInt(), (full[1] * o.height).toInt(), (full[2] * o.width).toInt(), (full[3] * o.height).toInt()), android.graphics.RectF(0f, 0f, W.toFloat(), H.toFloat()), pnt)
            pnt.alpha = 255
        }
        val fw: Float; val fh: Float
        if (ra > da) { fw = W.toFloat(); fh = fw / ra } else { fh = H.toFloat(); fw = fh * ra }
        cv.drawBitmap(o, android.graphics.Rect((r[0] * o.width).toInt(), (r[1] * o.height).toInt(), (r[2] * o.width).toInt(), (r[3] * o.height).toInt()),
            android.graphics.RectF((W - fw) / 2f, (H - fh) / 2f, (W + fw) / 2f, (H + fh) / 2f), pnt)
        return out
    }

    private fun zoomInto(c: LinearLayout, dd: PodStudio.Data, pp: PodProject) {
            c.addView(rowOf(*listOf("off" to tx("معطّل", "Off"), "auto" to tx("تلقائي", "Auto"), "manual" to tx("يدوي", "Manual")).map { (k, n) ->
            btn(n, pp.st.zoomMode == k) {
                editHeavy { q ->
                    q.st.zoomMode = k
                    if (k == "auto") q.zooms.let { z -> val keep = z.filter { it.locked || !it.auto }; z.clear(); z.addAll(PodEngine.mergeZooms(keep, PodEngine.autoZooms(q.shots, dd.turns, dd.regions, q.st, dd.sem?.keys, q.a, q.end(dd.duration)))) }
                }
            }
        }.toTypedArray()))
        if (pp.st.zoomMode != "off") {
            c.addView(txt(tx("أقصى تقريب: ", "Max zoom: ") + String.format(Locale.US, "%.2f×", pp.st.zoomMax), 12f, "#9A9CA3"))
            c.addView(rowOf(*listOf(1.05f, 1.10f, 1.15f, 1.20f).map { v -> btn(String.format(Locale.US, "%.2f×", v), abs(pp.st.zoomMax - v) < 0.001f) { edit { it.st.zoomMax = v; for (z in it.zooms) if (z.auto && z.scale > v) z.scale = v } } }.toTypedArray()))
            c.addView(rowOf(
                btn(if (pp.st.zoomSmooth) tx("حركة تدريجية", "Gradual move") else tx("انتقال مباشر", "Direct cut"), true) { edit { it.st.zoomSmooth = !it.st.zoomSmooth; for (z in it.zooms) if (z.auto) z.smooth = it.st.zoomSmooth } },
                btn(tx("اقترح الزوم", "Suggest zooms")) { editHeavy { q -> val keep = q.zooms.filter { it.locked || !it.auto }; q.zooms.clear(); q.zooms.addAll(PodEngine.mergeZooms(keep, PodEngine.autoZooms(q.shots, dd.turns, dd.regions, q.st.copy().also { s -> s.zoomMode = "auto" }, dd.sem?.keys, q.a, q.end(dd.duration)))) } },
            ))
            c.addView(rowOf(
                btn(tx("زوم هنا", "Zoom here")) { zoomHere() },
                btn(tx("ثبّت الكادر هنا", "Hold the frame here")) { holdFrameHere() },
                btn(tx("الحركات", "Moves")) { zoomsDialog() },
            ))
            val keysTxt = if (dd.sem?.keys.isNullOrEmpty()) tx("بدون نص: التقريب التلقائي يعتمد على طول السرد فقط (وبعد الوقفات في الحيوي).", "No transcript: automatic zoom follows narration length only (and after pauses in lively).") else tx("الجمل المحورية من فهم النص تُستعمل للتقريب.", "Key sentences from understanding the text are used for zoom.")
            c.addView(hint(tx("$keysTxt الزوم محدود بدقة الكاميرا وبالقص العمودي حتى لا تتدهور الصورة.", "$keysTxt Zoom is limited by the camera resolution and vertical crop so the picture doesn't degrade.")))
        }
        c.addView(rowOf(
            btn(tx("حلّل الوجوه", "Analyze faces")) { startJob(PodcastExportService.ACTION_FACES) },
            btn(tx("المصادر: تدوير وقص", "Sources: rotate & crop")) { startActivity(Intent(this, SourceEditActivity::class.java).putExtra("session", sid).putExtra("vertical", (p?.st?.orient ?: "h") == "v")) },
        ))
    }

    /** قائمة القوالب (بدل ٧ أزرار بالصفحة) */
    private fun pickTemplate() {
        val dd = d ?: return
        val pp = p ?: return
        val cur = PodStudio.tplOf(this, pp)
        val all = TemplateStore.ofKind(this, TemplateStore.kindOf(pp))
        UiKit.sheet(this, tx("اختار القالب", "Pick a template")) { col, dlg ->
            for (t in all) {
                val b = UiKit.chip(this, (if (t.id == cur.id) "✓ " else "") + t.name + "\n" + TemplateActivity.summary(t), t.id == cur.id) {
                    dlg.dismiss()
                    editHeavy { q -> TemplateStore.applyTo(t, q.st); PodStudio.regenerate(dd, q) }
                }.apply { textSize = 13.5f; maxLines = 3; minHeight = dp(64) }
                col.addView(b, UiKit.full(this, b, 6))
            }
            val ed = UiKit.chip(this, "⚙ " + tx("كل القوالب", "All templates")) { dlg.dismiss(); startActivity(Intent(this, TemplateActivity::class.java).putExtra("kind", TemplateStore.kindOf(pp)).putExtra("mode", if (dd.setup.vlog) "vlog" else "podcast")) }
            col.addView(ed, UiKit.full(this, ed, 12))
        }
    }

    /** قالب جديد = القالب الحالي + تعديلاتك بهالمشروع (والقالب القديم زي ما هو) */
    private fun newTemplateFromProject(cur: PodTemplate) {
        val pp = p ?: return
        val et = android.widget.EditText(this).apply { setText(cur.name + tx(" (معدّل)", " (edited)")); setSingleLine(); setTextColor(Color.WHITE) }
        android.app.AlertDialog.Builder(this).setTitle(tx("اسم القالب الجديد", "New template name")).setView(et)
            .setPositiveButton(tx("احفظ", "Save")) { _, _ ->
                val t2 = cur.copy().also { it.id = TemplateStore.newId(); it.name = et.text.toString().trim().ifBlank { it.name } }
                for (k in pp.st.ov) TemplateStore.pull(pp.st, t2, k)
                TemplateStore.save(this, t2)
                edit { q -> q.st.tpl = t2.id; q.st.ov.clear() }
                toast(tx("انعمل «${t2.name}» وهالمشروع صار عليه", "Created “${t2.name}” and this project now uses it"))
            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    /** كل قاعدة شغالة فعليًا بالمونتاج — نفس اللي بيعمله المحرّك (PodPlan) والتصدير بالزبط، وكلها من القالب */
    private fun ruleRows(): List<Triple<String, String, String>> {
        val pp = p ?: return emptyList()
        val tp = PodStudio.tplOf(this, pp)
        val st = pp.st
        val from = tx("📋 من القالب «${tp.name}»", "📋 from template “${tp.name}”")
        val off = tx("مطفي", "off")
        val rows = ArrayList<Triple<String, String, String>>()
        rows += Triple(tx("١ سرعة التقليب", "1 Cutting pace"), when (st.intensity) { "calm" -> tx("هادئ — أقل لقطة ٢٫٥ ث", "calm — min shot 2.5 s"); "lively" -> tx("سريع — أقل لقطة ١٫٢ ث", "lively — min shot 1.2 s"); else -> tx("متوسط — أقل لقطة ١٫٨ ث", "balanced — min shot 1.8 s") }, from)
        val after = if (st.overlap !in setOf("both", "split", "wide", "switch") || st.ovAfter < 0.5f) "" else tx(" · بعد ${st.ovAfter.toInt()} ث", " · after ${st.ovAfter.toInt()} s")
        rows += Triple(tx("٢ لما اثنين يحكوا مع بعض", "2 When two talk at once"), when (st.overlap) { "both", "split" -> tx("الاثنين مع بعض (تقسيم)", "both together (split)"); "wide" -> tx("الكاميرا العامة", "the wide camera"); "switch" -> tx("بتنتقل على اللي دخل", "goes to the one who came in"); else -> tx("بتضل على اللي كان (بلا تقسيم)", "stays on who it was (no split)") } + after, from)
        rows += Triple(tx("٣ التعليقات القصيرة", "3 Short comments"), if (st.pip) tx("مربع صغير فوق اللي بيحكي", "small box over the talker") else tx("ما بتقلب الكاميرا", "never move the camera"), from)
        rows += Triple(tx("٤ الكادر", "4 Framing"), if (tp.follow) tx("بيلحق الوجوه (زوم وتقريب)", "follows faces (zoom & push-in)") else tx("ثابت — متل ما انصوّر", "still — as recorded"), from)
        rows += Triple(tx("٥ كسر الملل", "5 Break monotony"), if (st.brk > 0) tx("لقطة ${String.format(Locale.US, "%.1f", st.brkLen)} ث على المستمع كل ${st.brk} ث", "${String.format(Locale.US, "%.1f", st.brkLen)} s on the listener every ${st.brk} s") else off, from)
        rows += Triple(tx("٦ أول ظهور", "6 First appearance"), if (st.firstHold > 0f) "${st.firstHold} ث" else off, from)
        rows += Triple(tx("الانتقال", "Transition"), when (tp.trans) { "fade" -> tx("تلاشي", "fade"); "flash" -> tx("ومضة", "flash"); "zoom" -> tx("تكبير", "zoom"); else -> tx("قطع", "cut") }, from)
        return rows
    }

    /** لقطات حطيتها بإيدك أو ثبّتها — هاي الوحيدة اللي بتغلب القالب */
    private fun manualShots(): List<PodShot> = p?.shots?.filter { it.locked || it.tag == "manual" } ?: emptyList()

    private fun clearManual() {
        val dd = d ?: return
        val pp = p ?: return
        ProjectStore.snapshot(this, sid, pp, tx("قبل مسح اللقطات اليدوية", "Before removing manual shots"))
        editHeavy { q ->
            for (sh in q.shots) { sh.locked = false; if (sh.tag == "manual") sh.tag = "" }
            q.shots.clear(); PodStudio.regenerate(dd, q)
        }
        toast(tx("انمسحت اللقطات اليدوية والمونتاج رجع حسب القالب", "Manual shots removed — the edit follows the template again"))
    }

    private fun rulesDialog() {
        val pp = p ?: return
        val dd = d ?: return
        val st = pp.st
        val rows = ruleRows()
        UiKit.sheet(this, tx("شو المطبّق فعليًا", "What actually applies")) { col, dlg ->
            for ((name, v, from) in rows) {
                col.addView(UiKit.text(this, name, 14.5f, UiKit.TEXT, true).apply { setPadding(0, dp(10), 0, 0) })
                col.addView(UiKit.text(this, v, 13.5f, UiKit.TEXT))
                col.addView(UiKit.text(this, from, 11.5f, if (from.startsWith("✎")) "#FF9F0A" else UiKit.SUB))
            }
            if (st.ov.isNotEmpty()) {
                col.addView(UiKit.text(this, tx("معدّل بهالمشروع: ", "Changed in this project: ") + st.ov.joinToString("، ") { TemplateStore.keyName(it) }, 12.5f, "#FF9F0A").apply { setPadding(0, dp(14), 0, 0) })
                val b = UiKit.primary(this, tx("↺ رجّع كل إشي للقالب", "↺ Reset everything to the template")) {
                    dlg.dismiss()
                    editHeavy { q -> TemplateStore.applyTo(PodStudio.tplOf(this, q), q.st); PodStudio.regenerate(dd, q) }
                }
                col.addView(b, UiKit.full(this, b, 12, 54))
            }
        }
    }

    /** بداية/نهاية الفيديو المصدّر بلحظة t (بنقربها من أقرب وقفة) */
    private fun setEdge(start: Boolean, t: Double) {
        val dd = d ?: return
        val pp = p ?: return
        val e = pp.end(dd.duration)
        if (start && t >= e - 3.0) { toast(tx("البداية لازم تكون قبل النهاية بـ3 ثواني على الأقل", "The start must be at least 3 s before the end")); return }
        if (!start && t <= pp.a + 3.0) { toast(tx("النهاية لازم تكون بعد البداية بـ3 ثواني على الأقل", "The end must be at least 3 s after the start")); return }
        ProjectStore.snapshot(this, sid, pp, tx("قبل تغيير بداية/نهاية الفيديو", "Before changing the video start/end"))
        editHeavy { q ->
            if (start) q.a = t.coerceIn(0.0, dd.duration) else q.b = t.coerceIn(0.0, dd.duration)
            PodStudio.regenerate(dd, q)
        }
        toast(if (start) tx("الفيديو بيبلّش من ${fmt(t)} — اعمل معاينة من هون تتأكد", "The video starts at ${fmt(t)} — preview from here to check") else tx("الفيديو بيخلص عند ${fmt(t)}", "The video ends at ${fmt(t)}"))
    }

    private fun moveEdge(start: Boolean, delta: Double) {
        val dd = d ?: return
        editHeavy { q ->
            val e = q.end(dd.duration)
            if (start) q.a = (q.a + delta).coerceIn(0.0, max(0.0, e - 3.0))
            else q.b = (e + delta).coerceIn(min(q.a + 3.0, dd.duration), dd.duration)
            // نقرب الحد من أقرب وقفة حتى لا يبدأ أو ينتهي في منتصف كلمة
            val real = dd.turns.filter { !it.backchannel }
            if (start) real.filter { abs(it.a - q.a) < 1.0 }.minByOrNull { abs(it.a - q.a) }?.let { q.a = max(0.0, it.a - 0.15) }
            else real.filter { abs(it.b - q.b) < 1.0 }.minByOrNull { abs(it.b - q.b) }?.let { q.b = min(dd.duration, it.b + 0.3) }
            PodStudio.regenerate(dd, q)
        }
    }

    private fun editTitle() {
        val pp = p ?: return
        val et = EditText(this).apply { setText(pp.title); setSingleLine() }
        AlertDialog.Builder(this).setTitle(tx("العنوان", "Title")).setView(et)
            .setPositiveButton(tx("حفظ", "Save")) { _, _ -> edit { it.title = et.text.toString().trim() } }
            .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    // ───────── الفراغات الطويلة (الحلقة) ─────────

    private fun gapsDialog() {
        val dd = d ?: return
        val pp = p ?: return
        val found = PodEngine.longGaps(dd.an, pp.st.gapMin.toDouble(), pp.a, pp.end(dd.duration))
        if (found.isEmpty()) { toast(tx("لا توجد فراغات أطول من ${pp.st.gapMin} ثانية", "No gaps longer than ${pp.st.gapMin} seconds")); return }
        val labels = found.map { "${fmt(it.a)} — " + tx("حذف", "remove") + " ${String.format(Locale.US, "%.1f", it.b - it.a)} " + tx("ث (يبقى 0.6 ث)", "s (0.6 s kept)") }.toTypedArray()
        val checked = BooleanArray(found.size) { k -> pp.cuts.any { it.kind == PCut.GAP && abs(it.a - found[k].a) < 0.05 } || pp.cuts.none { it.kind == PCut.GAP } }
        AlertDialog.Builder(this).setTitle(tx("الفراغات الطويلة — اختر ما يُحذف", "Long gaps — choose what to remove"))
            .setMultiChoiceItems(labels, checked) { _, k, on -> checked[k] = on }
            .setPositiveButton(tx("اعتمد", "Apply")) { _, _ ->
                edit { q ->
                    q.cuts.removeAll { it.kind == PCut.GAP }
                    for ((k, g) in found.withIndex()) if (checked[k]) q.cuts.add(g.copy().also { it.locked = true })
                    q.cuts.sortBy { it.a }
                }
            }
            .setNeutralButton(tx("أقل فراغ…", "Minimum gap…")) { _, _ ->
                val opts = arrayOf(tx("1.0 ث", "1.0 s"), tx("1.5 ث", "1.5 s"), tx("2.0 ث", "2.0 s"), tx("3.0 ث", "3.0 s"))
                AlertDialog.Builder(this).setItems(opts) { _, k -> edit { it.st.gapMin = listOf(1f, 1.5f, 2f, 3f)[k] }; gapsDialog() }.show()
            }
            .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    // ───────── الأصوات القصيرة ─────────

    /** بعد تعديل الأصوات: نعيد تحميل التحليل (بالتعديلات) ونولّد اللقطات غير المثبّتة */
    private fun reloadVoice() {
        val dd = d ?: return
        try {
            val fresh = PodcastAnalyzer.loadAnalysis(this, sid) ?: return
            PodVoice.apply(this, dd.s, fresh, dd.tr)
            for (i in fresh.active.indices) if (i < dd.an.active.size) System.arraycopy(fresh.active[i], 0, dd.an.active[i], 0, min(fresh.active[i].size, dd.an.active[i].size))
        } catch (_: Throwable) {}
        dd.resetTurns()
        editHeavy { q -> PodStudio.regenerate(dd, q) }
    }

    /** صورة من كاميرا شخص بلحظة t من الجلسة (بخيط خلفي) */
    /** إطار فاضي (أخضر/لون واحد) من خلل فك الترميز */
    private fun isBlankGreen(b: android.graphics.Bitmap): Boolean {
        var green = 0; var n = 0
        for (fy in 1..4) for (fx in 1..4) {
            val c = b.getPixel(b.width * fx / 5, b.height * fy / 5); n++
            if (Color.green(c) > 100 && Color.red(c) < 30 && Color.blue(c) < 30) green++
        }
        return green >= n - 1
    }

    private fun frameOf(dev: String, t: Double): android.graphics.Bitmap? {
        val dd = d ?: return null
        if (dev.isEmpty()) return null
        return try {
            val dir = SessionStore.devDir(this, sid, dev)
            val vm = JSONObject(File(dir, "meta.json").readText()).optJSONObject("video") ?: return null
            val dv = dd.s.devices[dev] ?: return null
            val ms = Finalizer.SatVideoTime(dd.s, dv, vm.optLong("startBoot")).fileMs((t * 1e9).toLong())
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(File(dir, "video.mp4").absolutePath)
                val us = (ms * 1000).toLong().coerceAtLeast(0L)
                var bm = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, 640, 640)
                // بعض الجوالات بترجع صورة خضرا بدل الإطار → بناخد أقرب إطار أساسي بداله
                if (bm == null || isBlankGreen(bm)) bm = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640) ?: bm
                PodFaces.upright(r, bm)
            } finally { r.release() }
        } catch (_: Throwable) { null }
    }

    /**
     * مراجعة الأصوات سؤال سؤال: بيشتغل الصوت من مايك الشخص نفسه (ثانية ونص قبل وبعد) مع صورته بهديك اللحظة،
     * وانت بتجاوب «كلام» أو «مش كلام» وبينقلك عاللي بعده. بالآخر بينعاد المونتاج مرة وحدة.
     */
    private fun reviewSounds() {
        val dd = d ?: return
        val ov = PodVoice.overrides(this, sid)
        val list = dd.an.events.filter { it.verdict != VEvent.SPEECH || it.key in ov.reject || it.key in ov.accept }.sortedBy { it.a }.toMutableList()
        if (list.isEmpty()) { toast(tx("ما في أصوات تجاهلناها", "No ignored sounds")); return }
        // بنكمّل من أول صوت لسا ما قررت فيه (قراراتك محفوظة، وبتقدر ترجع لورا تشيّك عليهم)
        fun decided(e: VEvent) = e.key in ov.accept || e.key in ov.reject
        var idx = list.indexOfFirst { !decided(it) }.let { if (it < 0) 0 else it }
        var changed = false
        var track: android.media.AudioTrack? = null
        fun stop() { try { track?.stop() } catch (_: Throwable) {}; try { track?.release() } catch (_: Throwable) {}; track = null }
        val dlg = UiKit.sheet(this, tx("راجع الأصوات", "Review sounds")) { col, dlg ->
            val head = UiKit.text(this, "", 13f, UiKit.SUB)
            val q = UiKit.text(this, "", 17f, UiKit.TEXT, true).apply { setPadding(0, dp(6), 0, dp(6)) }
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
            val state = UiKit.text(this, "", 12.5f, UiKit.SUB)
            col.addView(head); col.addView(q)
            col.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
            col.addView(state)
            val again = UiKit.chip(this, tx("▶ اسمعه كمان مرة", "▶ Play again")) {}
            col.addView(again, UiKit.full(this, again, 8))
            val yes = UiKit.primary(this, tx("كلام — اعرضه", "Speech — show it")) {}
            val no = UiKit.chip(this, tx("مش كلام — تجاهله", "Not speech — ignore it")) {}
            col.addView(yes, UiKit.full(this, yes, 10, 56)); col.addView(no, UiKit.full(this, no, 8, 56))
            val skipRow = LinearLayout(this)
            val back = UiKit.chip(this, tx("→ السابق", "← Previous")) {}
            skipRow.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            val skip = UiKit.chip(this, tx("تخطّي", "Skip")) {}
            val allSame = UiKit.chip(this, "") {}
            skipRow.addView(skip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            skipRow.addView(allSame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            col.addView(skipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            var req = 0
            fun play() {
                val e = list.getOrNull(idx) ?: return
                val my = ++req
                stop()
                state.text = tx("بيحمّل الصوت…", "Loading audio…")
                Thread {
                    val pcm = PodVoice.clip(this, dd.s, dd.setup, e.p, e.a - 1.5, e.b + 1.5)
                    ui.post {
                        if (my != req || !dlg.isShowing) return@post
                        if (pcm == null || pcm.first.isEmpty()) { state.text = tx("ما لقينا صوت المايك لهالشخص", "No mic audio found for this person"); return@post }
                        try {
                            val bytes = pcm.first.size * 2
                            val tr = android.media.AudioTrack.Builder()
                                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(pcm.second).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                                .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(bytes).build()
                            tr.write(pcm.first, 0, pcm.first.size)
                            tr.play()
                            track = tr
                            state.text = tx("🔊 بيشتغل: ثانية ونص قبل الصوت، الصوت نفسه، وثانية ونص بعده (من مايكه الأصلي)", "🔊 Playing: 1.5 s before, the sound, 1.5 s after (from their own mic)")
                        } catch (_: Throwable) { state.text = tx("ما قدرنا نشغّل الصوت", "Couldn't play the audio") }
                    }
                }.start()
            }
            fun show() {
                val e = list.getOrNull(idx)
                if (e == null) { dlg.dismiss(); return }
                val who = dd.names.getOrNull(e.p) ?: "?"
                val done = list.count { decided(it) }
                head.text = tx("${idx + 1} من ${list.size} · قررت بـ $done", "${idx + 1} of ${list.size} · $done decided")
                back.isEnabled = idx > 0; back.alpha = if (idx > 0) 1f else 0.4f
                val guess = when { e.key in ov.accept -> tx("اخترت: كلام", "You chose: speech"); e.key in ov.reject -> tx("اخترت: مش كلام", "You chose: not speech"); e.verdict == VEvent.UNSURE -> tx("تخميننا: ${VEvent.labelName(e.label)} (مش متأكدين)", "Our guess: ${VEvent.labelName(e.label)} (unsure)"); else -> tx("تخميننا: ${VEvent.labelName(e.label)}", "Our guess: ${VEvent.labelName(e.label)}") }
                q.text = tx("$who · ${fmt(e.a)} — هل هذا كلام؟\n", "$who · ${fmt(e.a)} — is this speech?\n") + guess
                allSame.text = tx("تجاهل كل «${VEvent.labelName(e.label)}» الباقية", "Ignore all remaining “${VEvent.labelName(e.label)}”")
                img.setImageDrawable(null)
                val dev = dd.setup.people.getOrNull(e.p)?.camDev ?: ""
                val my = req
                Thread { val b = frameOf(dev, (e.a + e.b) / 2); ui.post { if (dlg.isShowing && list.getOrNull(idx) === e) img.setImageBitmap(b) } }.start()
                play()
            }
            fun answer(speech: Boolean?) {
                val e = list.getOrNull(idx) ?: return
                if (speech == true) { ov.accept.add(e.key); ov.reject.remove(e.key); changed = true }
                if (speech == false) { ov.accept.remove(e.key); ov.reject.add(e.key); changed = true }
                if (speech != null) PodVoice.saveOverrides(this, sid, ov)
                idx++
                if (idx >= list.size) { toast(tx("خلصت ✓", "Done ✓")); dlg.dismiss() } else show()
            }
            again.setOnClickListener { play() }
            back.setOnClickListener { if (idx > 0) { idx--; show() } }
            yes.setOnClickListener { answer(true) }
            no.setOnClickListener { answer(false) }
            skip.setOnClickListener { answer(null) }
            allSame.setOnClickListener {
                val e = list.getOrNull(idx) ?: return@setOnClickListener
                val rest = list.drop(idx).filter { it.label == e.label }
                for (x in rest) { ov.accept.remove(x.key); ov.reject.add(x.key) }
                PodVoice.saveOverrides(this, sid, ov); changed = true
                list.removeAll(rest.drop(1).toSet())
                answer(false)
            }
            col.post { show() }
        }
        dlg.setOnDismissListener { stop(); if (changed) reloadVoice() }
    }

    /** "خلّي الكاميرا بعيد عن فلان" بفترة (مثلًا قام يعمل إشي ورا الكواليس) */
    private fun ignoreRangeDialog() {
        val dd = d ?: return
        val ov = PodVoice.overrides(this, sid)
        val t0 = if (tl.selected >= 0) tl.selected else 0.0
        UiKit.sheet(this, tx("بعّد الكاميرا عن شخص", "Keep the camera off someone")) { col, dlg ->
            col.addView(UiKit.text(this, tx("كل صوته بهالفترة بينتجاهل (ما بتقلب الكاميرا عليه). البداية من اللحظة المختارة على الخط الزمني.", "All their sound in this range is ignored. Starts at the moment selected on the timeline."), 13f, UiKit.SUB))
            var who = 0
            val wr = LinearLayout(this)
            fun drawWho() { wr.removeAllViews(); for ((i, n) in dd.names.withIndex()) wr.addView(UiKit.chip(this, n, i == who) { who = i; drawWho() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }) }
            drawWho(); col.addView(wr)
            val from = android.widget.EditText(this).apply { setText(fmt(t0)); setTextColor(Color.parseColor(UiKit.TEXT)); hint = "0:00" }
            val to = android.widget.EditText(this).apply { setText(fmt(t0 + 30)); setTextColor(Color.parseColor(UiKit.TEXT)); hint = "0:30" }
            col.addView(UiKit.text(this, tx("من", "From"), 13f, UiKit.SUB)); col.addView(from)
            col.addView(UiKit.text(this, tx("لـ", "To"), 13f, UiKit.SUB)); col.addView(to)
            fun parse(s: String): Double? { val p = s.trim().split(':'); return try { if (p.size == 2) p[0].toInt() * 60.0 + p[1].toDouble() else s.toDouble() } catch (_: Throwable) { null } }
            val ok = UiKit.primary(this, tx("ضيف", "Add")) {
                val a = parse(from.text.toString()); val b = parse(to.text.toString())
                if (a == null || b == null || b <= a) { toast(tx("الوقت مش مفهوم (مثال 3:20)", "Time not understood (e.g. 3:20)")); return@primary }
                ov.ignores.add(doubleArrayOf(who.toDouble(), a, b)); PodVoice.saveOverrides(this, sid, ov); dlg.dismiss(); reloadVoice()
            }
            col.addView(ok, UiKit.full(this, ok, 12))
            if (ov.ignores.isNotEmpty()) {
                val clr = UiKit.chip(this, tx("امسح كل الفترات", "Clear all ranges")) { ov.ignores.clear(); PodVoice.saveOverrides(this, sid, ov); dlg.dismiss(); reloadVoice() }
                col.addView(clr, UiKit.full(this, clr, 8))
            }
        }
    }

    // ───────── الزوم اليدوي ─────────

    private fun zoomHere() {
        val pp = p ?: return
        val t = tl.selected
        if (t < 0) { toast(tx("اختر لحظة على الخط الزمني أولًا", "Pick a moment on the timeline first")); return }
        val sh = pp.shots.getOrNull(shotIndexAt(t)) ?: return
        if (sh.type == PodShot.MULTI || sh.type == PodShot.WIDE) { toast(tx("الزوم يعمل على لقطة شخص واحد", "Zoom works on a single-person shot")); return }
        edit { q ->
            if (q.st.zoomMode == "off") q.st.zoomMode = "manual"
            val z = PZoom(t, min(sh.end, t + 3.0), q.st.zoomMax, q.st.zoomSmooth, locked = true, auto = false, reason = tx("يدوي", "Manual"))
            q.zooms.removeAll { it.a < z.b && it.b > z.a }
            q.zooms.add(z); q.zooms.sortBy { it.a }
        }
    }

    private fun holdFrameHere() {
        val pp = p ?: return
        val t = tl.selected
        if (t < 0) { toast(tx("اختر لحظة على الخط الزمني أولًا", "Pick a moment on the timeline first")); return }
        val sh = pp.shots.getOrNull(shotIndexAt(t)) ?: return
        edit { q ->
            val z = PZoom(sh.start, sh.end, 1f, false, locked = true, auto = false, reason = tx("تثبيت الكادر", "Hold the frame"))
            q.zooms.removeAll { it.a < z.b && it.b > z.a }
            q.zooms.add(z); q.zooms.sortBy { it.a }
        }
        toast(tx("الكادر مثبّت في هذه اللقطة (لا زوم)", "Frame held in this shot (no zoom)"))
    }

    private fun zoomsDialog() {
        val pp = p ?: return
        if (pp.zooms.isEmpty()) { toast(tx("لا توجد حركات زوم", "No zoom moves")); return }
        val items = pp.zooms.map { z ->
            (if (z.locked) "🔒 " else "") + "${fmt(z.a)}–${fmt(z.b)} • " + (if (z.scale <= 1.001f) tx("تثبيت", "Hold") else String.format(Locale.US, "%.2f×", z.scale)) +
                " • ${if (z.smooth) "تدريجي" else "مباشر"} • ${z.reason}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(tx("حركات الزوم", "Zoom moves")).setItems(items) { _, k ->
            val z = pp.zooms[k]
            AlertDialog.Builder(this).setTitle(items[k])
                .setItems(arrayOf(if (z.locked) tx("إلغاء التثبيت", "Unlock") else tx("تثبيت", "Hold"), tx("تقريب أقوى", "Stronger zoom"), tx("تقريب أخف", "Lighter zoom"), if (z.smooth) tx("اجعله مباشرًا", "Make it direct") else tx("اجعله تدريجيًا", "Make it gradual"), tx("اذهب إليها", "Go to it"), tx("حذف", "Delete"))) { _, a ->
                    when (a) {
                        0 -> edit { it.zooms[k].locked = !z.locked }
                        1 -> edit { it.zooms[k].scale = min(it.st.zoomMax + 0.1f, z.scale + 0.03f); it.zooms[k].locked = true; it.zooms[k].auto = false }
                        2 -> edit { it.zooms[k].scale = max(1f, z.scale - 0.03f); it.zooms[k].locked = true; it.zooms[k].auto = false }
                        3 -> edit { it.zooms[k].smooth = !z.smooth; it.zooms[k].locked = true }
                        4 -> onPick(z.a + 0.01)
                        5 -> edit { it.zooms.removeAt(k) }
                    }
                }.show()
        }.show()
    }

    // ───────── النص ─────────

    private fun transcriptDialog() {
        val dd = d ?: return
        val pp = p ?: return
        val tr = dd.tr ?: return
        val a = pp.a; val b = pp.end(dd.duration)
        val segs = tr.segs.filter { it.b > a && it.a < b }
        var dlg: AlertDialog? = null
        val lv = ListView(this)
        val adapter = object : BaseAdapter() {
            override fun getCount() = segs.size
            override fun getItem(i: Int) = segs[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val g = segs[i]
                val q = p ?: pp
                val deleted = q.cuts.any { it.kind == PCut.TEXT && it.ref == g.i }
                val tv = (cv as? TextView) ?: TextView(this@PodcastEditActivity).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); textSize = 15f; textDirection = View.TEXT_DIRECTION_RTL }
                val who = dd.setup.people.getOrNull(g.speaker)?.name ?: tx("؟", "?")
                tv.text = "${fmt(g.a)} • $who\n${g.text}"
                tv.setTextColor(if (deleted) Color.GRAY else Color.WHITE)
                tv.paintFlags = if (deleted) tv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else tv.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                tv.setBackgroundColor(if (g.speaker >= 0) (PodColors.people[g.speaker % 4] and 0x00FFFFFF) or 0x22000000 else 0)
                return tv
            }
        }
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, i, _ -> dlg?.dismiss(); onPick(segs[i].a + 0.01) }
        lv.setOnItemLongClickListener { _, _, i, _ ->
            val g = segs[i]
            val q = p ?: pp
            val deleted = q.cuts.any { it.kind == PCut.TEXT && it.ref == g.i }
            AlertDialog.Builder(this).setTitle(g.text.take(60))
                .setItems(arrayOf(tx("تصحيح النص", "Correct the text"), if (deleted) tx("أرجع الجملة", "Restore the sentence") else tx("✂ احذفها من النسخة المعدّلة", "✂ Remove it from the edit"), tx("★ ميّز كلمة", "★ Highlight a word"))) { _, k ->
                    when (k) {
                        0 -> {
                            val et = EditText(this).apply { setText(g.text); textDirection = View.TEXT_DIRECTION_RTL }
                            AlertDialog.Builder(this).setView(et).setPositiveButton(tx("حفظ", "Save")) { _, _ ->
                                val nt = et.text.toString().trim()
                                if (nt != g.text) {
                                    g.text = nt
                                    // توقيت الكلمات القديم لا يطابق النص المصحَّح: الكتابة تتوزع نسبيًا على مدة الجملة
                                    g.words = emptyList()
                                    PodAI.saveTranscript(this, sid, tr)
                                }
                                adapter.notifyDataSetChanged()
                            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
                        }
                        1 -> {
                            edit { pr ->
                                if (deleted) pr.cuts.removeAll { it.kind == PCut.TEXT && it.ref == g.i }
                                else { pr.cuts.add(PCut(max(pr.a, g.a - 0.05), min(pr.end(dd.duration), g.b + 0.05), PCut.TEXT, true, g.i)); pr.cuts.sortBy { it.a } }
                            }
                            adapter.notifyDataSetChanged()
                        }
                        2 -> {
                            val words = g.text.split(Regex("\\s+")).filter { it.isNotEmpty() }.toTypedArray()
                            AlertDialog.Builder(this).setItems(words) { _, w ->
                                edit { pr -> val key = "${g.i}:$w"; if (!pr.emph.remove(key)) pr.emph.add(key) }
                            }.show()
                        }
                    }
                }.show()
            true
        }
        dlg = AlertDialog.Builder(this).setTitle(tx("النص — اضغط للانتقال، ضغطة مطوّلة للتعديل أو الحذف", "Text — tap to jump, long-press to edit or delete")).setView(lv)
            .setPositiveButton(tx("إغلاق", "Close"), null).show()
    }

    private fun confirmTranscribe() {
        if (!PodAI.cfg(this).ok) { PodUi.aiDialog(this); return }
        val c = PodAI.cfg(this)
        AlertDialog.Builder(this).setTitle(tx("رفع الصوت للتفريغ؟", "Upload audio for transcription?"))
            .setMessage(tx("سيُرفع صوت الحلقة (مخلوط، 16kHz) إلى:\n${c.where}\nلن يُرفع الفيديو. قد تُحتسب تكلفة على حسابك لدى الخدمة.", "The episode audio (mixed, 16kHz) will be uploaded to:\n${c.where}\nVideo is not uploaded. The service may charge your account."))
            .setPositiveButton(tx("ارفع وفرّغ", "Upload and transcribe")) { _, _ -> startJob(PodcastExportService.ACTION_TRANSCRIBE) }
            .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    private fun confirmSemantic() {
        if (!PodAI.cfg(this).ok) { PodUi.aiDialog(this); return }
        AlertDialog.Builder(this).setTitle(tx("فهم الحوار؟", "Understand the conversation?"))
            .setMessage(tx("سيُرسل النص المفرّغ فقط (بدون صوت) إلى الخدمة لتصنيف أجزاء الحوار وتحديد الجمل المحورية واقتراح أفكار ريلز.", "Only the transcript (no audio) is sent to the service to classify parts, find key sentences and suggest reel ideas."))
            .setPositiveButton(tx("أرسل", "Send")) { _, _ -> startJob(PodcastExportService.ACTION_SEMANTIC) }
            .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    private fun translateSrt() {
        if (!PodAI.cfg(this).ok) { PodUi.aiDialog(this); return }
        val langs = arrayOf("English", "Français", "Deutsch", "Español", "Türkçe")
        AlertDialog.Builder(this).setTitle(tx("ترجمة إلى", "Translate to")).setItems(langs) { _, k -> startJob(PodcastExportService.ACTION_SRT, langs[k]) }.show()
    }

    // ───────── الأعمال الطويلة ─────────

    private fun startJob(action: String, lang: String = "", forceH: Int = 0) {
        val pp = p ?: return
        if (PodState.running) { toast(tx("هناك عمل آخر قيد التنفيذ", "Another job is running")); return }
        ProjectStore.save(this, sid, pp)
        val it = Intent(this, PodcastExportService::class.java).setAction(action).putExtra("session", sid).putExtra("project", pp.id)
            .putExtra("orient", pp.st.orient).putExtra("target", pp.st.targetLen.toDouble())
        if (lang.isNotBlank()) it.putExtra("lang", lang)
        if (forceH > 0) it.putExtra("forceH", forceH)
        ContextCompat.startForegroundService(this, it)
    }

    private fun previewHere() {
        val pp = p ?: return
        if (PodState.running) { toast(tx("هناك عمل آخر قيد التنفيذ", "Another job is running")); return }
        val t = if (tl.selected >= 0) tl.selected else pp.a
        ProjectStore.save(this, sid, pp)
        ContextCompat.startForegroundService(this, Intent(this, PodcastExportService::class.java).setAction(PodcastExportService.ACTION_PREVIEW)
            .putExtra("session", sid).putExtra("project", pp.id).putExtra("from", t).putExtra("orient", if (pp.st.orient == "v") "v" else "h"))
    }

    // ───────── النسخ ─────────

    private fun versionsDialog() {
        val pp = p ?: return
        val dd = d ?: return
        val vs = ProjectStore.versions(this, sid, pp.id)
        val df = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val items = (listOf(tx("💾 احفظ المونتاج الحالي كنسخة", "💾 Back up the current edit")) + vs.map { "↩ ${df.format(Date(it.time))} — ${it.label}" }).toTypedArray()
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(4)) }
        head.addView(UiKit.text(this, tx("نسخ احتياطية للمونتاج", "Edit backups"), 18f, UiKit.TEXT, true))
        head.addView(UiKit.text(this, tx("كل سطر = شكل المونتاج (اللقطات والقص والقالب) بهديك اللحظة. التطبيق بيحفظها لحاله قبل التغييرات الكبيرة. اكبس على وحدة لتقارنها وترجعلها. ما فيها فيديوهات.", "Each line = how the edit looked (shots, cuts, template) at that moment, saved automatically before big changes. Tap one to compare and go back. No videos here."), 12.5f, UiKit.SUB).apply { setPadding(0, dp(6), 0, 0) })
        AlertDialog.Builder(this).setCustomTitle(head).setItems(items) { _, k ->
            if (k == 0) { ProjectStore.snapshot(this, sid, pp, tx("حفظ يدوي", "Manual save")); toast(tx("حُفظت نسخة", "Version saved")); return@setItems }
            val v = vs[k - 1]
            val old = ProjectStore.loadVersion(v) ?: return@setItems
            AlertDialog.Builder(this).setTitle(tx("مقارنة مع الحالية", "Compare with current")).setMessage(compare(dd, old, pp))
                .setPositiveButton(tx("استرجع هذه", "Restore this")) { _, _ ->
                    ProjectStore.snapshot(this, sid, pp, tx("قبل الاسترجاع", "Before restoring"))
                    edit { cur -> val o = old.copy(); cur.shots = o.shots; cur.cuts.clear(); cur.cuts.addAll(o.cuts); cur.zooms.clear(); cur.zooms.addAll(o.zooms); cur.st = o.st; cur.a = o.a; cur.b = o.b; cur.title = o.title; cur.emph.clear(); cur.emph.addAll(o.emph) }
                }
                .setNegativeButton(tx("أبقِ الحالية", "Keep current"), null).show()
        }.show()
    }

    private fun compare(dd: PodStudio.Data, x: PodProject, y: PodProject): String {
        fun dur(q: PodProject) = PodStudio.keepSegs(dd, q).sumOf { it[1] - it[0] }
        val a = min(x.a, y.a); val b = max(x.end(dd.duration), y.end(dd.duration))
        var diff = 0.0
        var t = a
        while (t < b) {
            val sx = x.shots.firstOrNull { t >= it.start && t < it.end }
            val sy = y.shots.firstOrNull { t >= it.start && t < it.end }
            if (sx == null || sy == null || !sx.same(sy)) diff += 0.5
            t += 0.5
        }
        return tx("النسخة السابقة: ${x.shots.size} لقطة، مدة ${fmt(dur(x))}، ${x.zooms.size} زوم، ${x.cuts.size} حذف، ${x.shots.count { it.locked }} مثبّتة\n", "Previous: ${x.shots.size} shots, length ${fmt(dur(x))}, ${x.zooms.size} zooms, ${x.cuts.size} cuts, ${x.shots.count { it.locked }} locked\n") +
            tx("الحالية: ${y.shots.size} لقطة، مدة ${fmt(dur(y))}، ${y.zooms.size} زوم، ${y.cuts.size} حذف، ${y.shots.count { it.locked }} مثبّتة\n", "Current: ${y.shots.size} shots, length ${fmt(dur(y))}, ${y.zooms.size} zooms, ${y.cuts.size} cuts, ${y.shots.count { it.locked }} locked\n") +
            tx("اللقطات مختلفة في ${((diff / max(1.0, b - a)) * 100).toInt()}% من الوقت.", "Shots differ in ${((diff / max(1.0, b - a)) * 100).toInt()}% of the time.")
    }

    // ───────── الكادر لكل كاميرا ─────────

    private fun framingDialog() {
        val dd = d ?: return
        val keys = ArrayList<Pair<String, String>>()
        for ((i, pr) in dd.setup.people.withIndex()) if (pr.camDev.isNotEmpty()) keys.add("p$i" to pr.camDev)
        for ((i, w) in dd.setup.wides.withIndex()) keys.add("w$i" to w)
        if (keys.isEmpty()) { toast(tx("لا كاميرات في هذا البودكاست", "No cameras in this podcast")); return }
        val names = keys.map { (k, _) -> if (k.startsWith("p")) dd.setup.people[k.substring(1).toInt()].name else tx("العامة ${k.substring(1).toInt() + 1}", "Wide ${k.substring(1).toInt() + 1}") }
        AlertDialog.Builder(this).setTitle(tx("أي كاميرا؟", "Which camera?")).setItems(names.toTypedArray()) { _, which ->
            val (key, dev) = keys[which]
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
            val iv = ImageView(this).apply { setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER }
            col.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
            var px = dd.setup.panX[key] ?: 0f
            var src: android.graphics.Bitmap? = null
            fun redraw() {
                val bm = src ?: return
                val out = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                val c = Canvas(out)
                val k = (9f / 16f) / (bm.width.toFloat() / bm.height)
                if (k < 1f) {
                    val w = bm.width * k
                    val cx = (0.5f + px * 0.5f).coerceIn(k / 2f, 1f - k / 2f) * bm.width
                    val x0 = cx - w / 2f
                    val shade = Paint().apply { color = 0xAA000000.toInt() }
                    c.drawRect(0f, 0f, x0, bm.height.toFloat(), shade)
                    c.drawRect(x0 + w, 0f, bm.width.toFloat(), bm.height.toFloat(), shade)
                    c.drawRect(x0, 0f, x0 + w, bm.height.toFloat(), Paint().apply { style = Paint.Style.STROKE; strokeWidth = bm.width / 120f; color = Color.WHITE })
                }
                iv.setImageBitmap(out)
            }
            col.addView(TextView(this).apply { text = tx("حرّك إطار 9:16 يمينًا/يسارًا (الضبط اليدوي له أولوية على تتبع الوجه؛ المنتصف = تلقائي)", "Move the 9:16 frame left/right (manual beats face tracking; centre = auto)"); textSize = 12f; setPadding(0, dp(8), 0, 0) })
            col.addView(SeekBar(this).apply {
                max = 200; progress = ((px + 1f) * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) { px = v / 100f - 1f; redraw() }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
            Thread {
                val bmp = try {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(File(SessionStore.devDir(this, sid, dev), "video.mp4").absolutePath)
                        val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        r.getFrameAtTime(durMs * 500L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally { r.release() }
                } catch (_: Throwable) { null }
                ui.post { src = bmp; if (bmp == null) toast(tx("لا يوجد فيديو من هذه الكاميرا", "No video from this camera")) else redraw() }
            }.start()
            AlertDialog.Builder(this).setTitle(names[which]).setView(ScrollView(this).apply { addView(col) })
                .setPositiveButton(tx("حفظ", "Save")) { _, _ ->
                    dd.setup.panX[key] = px
                    val ex = dd.setup.toJson().toString()
                    SessionStore.update(this, sid) { x -> x.extra = ex }
                }
                .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
        }.show()
    }

    // ───────── متابعة الأعمال ─────────

    private val tick = object : Runnable {
        override fun run() {
            val mine = PodState.sid == sid
            if (PodState.running && mine) {
                status.text = tx("${PodState.stage} ${(PodState.progress * 100).toInt()}%  (اضغط للإلغاء)", "${PodState.stage} ${(PodState.progress * 100).toInt()}%  (tap to cancel)")
                status.setOnClickListener { startService(Intent(this@PodcastEditActivity, PodcastExportService::class.java).setAction(PodcastExportService.ACTION_CANCEL)) }
                bar.progress = (PodState.progress * 100).toInt()
            } else if (mine && PodState.doneSeq != lastDone && !PodState.running) {
                lastDone = PodState.doneSeq
                status.setOnClickListener(null)
                onJobDone()
            }
            ui.postDelayed(this, 400)
        }
    }

    private fun onJobDone() {
        resultBox.removeAllViews()
        val e = PodState.error
        when (PodState.job) {
            PodcastExportService.ACTION_PREVIEW -> {
                if (e == null && PodState.previewFile != null) startActivity(Intent(this, PodPreviewActivity::class.java).putExtra("file", PodState.previewFile))
                else if (e != null) resultBox.addView(txt("⚠ $e", 13f, "#FF6B6B"))
            }
            PodcastExportService.ACTION_EXPORT -> PodUi.results(this, resultBox)
            else -> {
                if (e != null) resultBox.addView(txt("⚠ $e", 13f, "#FF6B6B"))
                PodState.info?.let { resultBox.addView(txt("✓ $it", 13f, "#7CFC9A")) }
                if (PodState.job == PodcastExportService.ACTION_TRANSCRIBE || PodState.job == PodcastExportService.ACTION_SEMANTIC || PodState.job == PodcastExportService.ACTION_FACES) reloadData()
            }
        }
        for (w in PodState.warnings) if (PodState.job != PodcastExportService.ACTION_EXPORT) resultBox.addView(txt("• $w", 11f, "#FFB86B"))
        status.text = summary()
        syncResult()
        panelSv.smoothScrollTo(0, 0)
    }

    /** بعد التفريغ/الفهم: نعيد تحميل البيانات (المشروع كما هو) */
    private fun reloadData() {
        Thread {
            val dd = PodStudio.load(this, sid, {}, { false }) ?: return@Thread
            ui.post { d = dd; refreshAll() }
        }.start()
    }
}
