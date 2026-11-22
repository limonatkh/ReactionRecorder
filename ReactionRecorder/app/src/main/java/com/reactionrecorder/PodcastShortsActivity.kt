package com.reactionrecorder

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max

/**
 * مسار "ريلز من الحوار": اقتراحات (حسب المعنى إن وُجد فهم للنص، وإلا من بنية الحوار)، إنشاء ريل يدوي،
 * وقائمة الريلز المحفوظة — لكل ريل مشروعه وإعداداته المستقلة عن الحلقة الكاملة.
 */
class PodcastShortsActivity : BaseActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sid: String
    private var d: PodStudio.Data? = null
    private var target = 45.0
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var reelsBox: LinearLayout
    private lateinit var sugBox: LinearLayout
    private lateinit var optBox: LinearLayout
    private var lastDone = PodState.doneSeq

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun fmt(t: Double): String { val s = max(0.0, t).toLong(); return "${s / 60}:${String.format(Locale.US, "%02d", s % 60)}" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sid = intent.getStringExtra("session") ?: run { finish(); return }
        build()
        load()
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        if (d != null) renderReels()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(tick)
    }

    private fun txt(t: String, size: Float = 13f, color: String = "#F2F2F3") = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, dp(6), 0, dp(2))
    }

    private fun btn(label: String, sel: Boolean = false, run: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); textSize = 12f; minWidth = 0; minimumWidth = 0
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor(if (sel) "#FF3B30" else "#2A2D36")) }
        setOnClickListener { run() }
    }

    private fun rowOf(vararg v: View) = LinearLayout(this).apply {
        for (x in v) addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }

    private fun cardView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#17181C")) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(24)) }
        val sv = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0E0F11")); addView(col) }
        setContentView(sv)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sv) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }
        col.addView(txt(tx("📱 ريلز من الحوار", "📱 Reels from the conversation"), 20f))
        status = txt(tx("جارٍ التحميل…", "Loading…"), 13f, "#FFD21F")
        col.addView(status)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        col.addView(txt(tx("ريلزك", "Your reels"), 16f))
        reelsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(reelsBox)
        col.addView(rowOf(btn(tx("✚ ريل يدوي", "✚ Manual reel")) { manualReel() }))
        col.addView(txt(tx("اقتراحات", "Suggestions"), 16f))
        optBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(optBox)
        sugBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sugBox)
    }

    private fun load() {
        Thread {
            val dd = PodStudio.load(this, sid, { pr -> ui.post { status.text = tx("تحليل من يتكلم… ${(pr * 100).toInt()}%", "Analyzing who speaks… ${(pr * 100).toInt()}%"); bar.progress = (pr * 100).toInt() } }, { isFinishing })
            ui.post {
                if (dd == null) { status.text = if (isFinishing) "" else tx("تعذر فتح الجلسة", "Could not open the session"); return@post }
                d = dd
                bar.progress = 100
                status.text = tx("مدة الحلقة ${fmt(dd.duration)}", "Episode length ${fmt(dd.duration)}") + (if (dd.tr == null) tx(" • بدون نص", " • no transcript") else tx(" • ${dd.tr.segs.size} جملة مفرّغة", " • ${dd.tr.segs.size} transcribed sentences")) +
                    (if (dd.sem != null) tx(" • مفهومة المعنى", " • meaning understood") else "")
                renderReels()
                renderOpts()
                renderSuggestions()
            }
        }.start()
    }

    private fun renderReels() {
        val dd = d ?: return
        reelsBox.removeAllViews()
        val reels = ProjectStore.reels(this, sid)
        if (reels.isEmpty()) reelsBox.addView(txt(tx("لا يوجد ريلز بعد — أنشئ من الاقتراحات أو يدويًا", "No reels yet — create one from the suggestions or manually"), 12f, "#9A9CA3"))
        for (r in reels) {
            val c = cardView()
            val out = PodStudio.keepSegs(dd, r).sumOf { it[1] - it[0] }
            c.addView(txt(r.title.ifBlank { tx("ريل", "Reel") } + "  •  ${fmt(r.a)}→${fmt(r.end(dd.duration))}  •  " + tx("بعد القص ", "after cuts ") + fmt(out), 14f))
            c.addView(rowOf(
                btn(tx("✎ افتح", "✎ Open"), true) { open(r.id) },
                btn(tx("⧉ نسخة", "⧉ Duplicate")) {
                    val cp = PodProject.fromJson(r.toJson().put("id", ProjectStore.newReelId()))
                    cp.title = r.title + tx(" (نسخة)", " (copy)")
                    ProjectStore.save(this, sid, cp); renderReels()
                },
                btn("🗑") {
                    AlertDialog.Builder(this).setMessage(tx("حذف مشروع هذا الريل؟ (الحلقة والتسجيل لا يتأثران)", "Delete this reel project? (the episode and recording are not affected)"))
                        .setPositiveButton(tx("حذف", "Delete")) { _, _ -> ProjectStore.delete(this, sid, r.id); renderReels() }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
                },
            ))
            reelsBox.addView(c)
        }
    }

    private fun renderOpts() {
        val dd = d ?: return
        optBox.removeAllViews()
        optBox.addView(txt(tx("المدة المستهدفة (مرنة — لا يُقطع المعنى لأجلها)", "Target length (flexible — meaning is never cut for it)"), 12f, "#9A9CA3"))
        optBox.addView(rowOf(*listOf(15.0, 30.0, 45.0, 60.0, 90.0).map { v -> btn(tx("${v.toInt()}ث", "${v.toInt()}s"), target == v) { target = v; renderOpts(); renderSuggestions() } }.toTypedArray()))
        if (dd.tr == null) {
            optBox.addView(txt(tx("الاقتراحات الآن من بنية الحوار فقط (سؤال وجوابه، كلام متصل يبدأ وينتهي عند وقفة، تبادل سريع). لاقتراحات حسب المعنى مع عناوين: فرّغ الكلام ثم افهم الحوار (خدمة خارجية اختيارية).", "Suggestions now come from the conversation structure only (a question and its answer, continuous speech from pause to pause, fast exchanges). For meaning-based ideas with titles: transcribe, then understand (optional external service)."), 11f, "#9A9CA3"))
            optBox.addView(rowOf(btn(tx("🤖 الخدمة", "🤖 Service")) { PodUi.aiDialog(this) }, btn(tx("📝 فرّغ الكلام", "📝 Transcribe")) { confirm(PodcastExportService.ACTION_TRANSCRIBE) }))
        } else if (dd.sem == null) {
            optBox.addView(txt(tx("يوجد نص. افهم الحوار لاقتراحات بفكرة مكتملة وعنوان وسبب.", "A transcript exists. Understand the conversation for complete ideas with a title and reason."), 11f, "#9A9CA3"))
            optBox.addView(rowOf(btn(tx("🧠 افهم الحوار", "🧠 Understand"), true) { confirm(PodcastExportService.ACTION_SEMANTIC) }))
        } else {
            optBox.addView(rowOf(btn(tx("🧠 أعد فهم الحوار", "🧠 Understand again")) { confirm(PodcastExportService.ACTION_SEMANTIC) }))
        }
    }

    private fun renderSuggestions() {
        val dd = d ?: return
        sugBox.removeAllViews()
        val ai = dd.sem?.ideas.orEmpty()
        if (ai.isEmpty()) {
            val tg = target
            val req = ++sugReq
            sugBox.addView(txt(tx("جارٍ البحث عن مقاطع…", "Finding clips…"), 12f, "#9A9CA3"))
            Thread {
                val local = try { PodEngine.suggestReels(dd.an, dd.names, dd.markers, tg, 6) } catch (_: Throwable) { emptyList() }
                ui.post { if (req == sugReq && !isFinishing) showSuggestions(dd, ai, local) }
            }.start()
            return
        }
        showSuggestions(dd, ai, emptyList())
    }

    private var sugReq = 0

    private fun showSuggestions(dd: PodStudio.Data, ai: List<PodEngine.Suggest>, local: List<PodEngine.Suggest>) {
        sugBox.removeAllViews()
        val list = if (ai.isNotEmpty()) ai.sortedBy { kotlin.math.abs((it.b - it.a) - target) }.take(8) else local
        if (ai.isNotEmpty()) sugBox.addView(txt(tx("من فهم النص (${ai.size} فكرة)", "From understanding the text (${ai.size} ideas)"), 12f, "#7CFC9A"))
        else sugBox.addView(txt(tx("من بنية الحوار — تقدير بدون فهم المعنى", "From the conversation structure — an estimate without meaning"), 12f, "#FFB86B"))
        if (list.isEmpty()) sugBox.addView(txt(tx("لا اقتراحات مناسبة لهذه المدة — جرّب مدة أخرى أو أنشئ ريلًا يدويًا", "No suitable suggestions for this length — try another length or create a reel manually"), 12f, "#9A9CA3"))
        for (s in list) {
            val c = cardView()
            c.addView(txt("${s.title.ifBlank { "مقطع" }}", 15f))
            c.addView(txt(tx("${fmt(s.a)} → ${fmt(s.b)}  •  ${(s.b - s.a).toInt()} ث", "${fmt(s.a)} → ${fmt(s.b)}  •  ${(s.b - s.a).toInt()} s"), 12f, "#9A9CA3"))
            if (s.reason.isNotBlank()) c.addView(txt(tx("لماذا: ${s.reason}", "Why: ${s.reason}"), 12f))
            val stars = dd.markers.filter { it.t in s.a..s.b }
            if (stars.isNotEmpty()) c.addView(txt(stars.joinToString(" ") { if (it.kind == "star") "⭐ ${fmt(it.t)}" else "✖ ${fmt(it.t)}" }, 11f, "#FFD21F"))
            val text = dd.tr?.textIn(s.a, s.b)
            if (!text.isNullOrBlank()) c.addView(txt("«${text.take(220)}${if (text.length > 220) "…" else ""}»", 12f, "#CED4DA"))
            c.addView(rowOf(
                btn(tx("✚ أنشئ ريل وافتحه", "✚ Create reel and open"), true) { create(s.a, s.b, s.title.ifBlank { tx("ريل ${fmt(s.a)}", "Reel ${fmt(s.a)}") }, s.reason) },
                btn(tx("📖 النص كامل", "📖 Full text")) { AlertDialog.Builder(this).setMessage(text ?: tx("لا يوجد نص", "No text")).setPositiveButton(tx("إغلاق", "Close"), null).show() },
            ))
            sugBox.addView(c)
        }
    }

    private fun create(a: Double, b: Double, title: String, reason: String) {
        val dd = d ?: return
        Thread {
            val p = PodStudio.newReel(dd, a, b, title, reason, target.toFloat())
            ProjectStore.save(this, sid, p)
            ui.post { renderReels(); open(p.id) }
        }.start()
    }

    private fun manualReel() {
        val dd = d ?: return
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val s0 = EditText(this).apply { hint = tx("البداية مثل 12:30", "Start e.g. 12:30"); setSingleLine() }
        val s1 = EditText(this).apply { hint = tx("النهاية مثل 13:20", "End e.g. 13:20"); setSingleLine() }
        col.addView(s0); col.addView(s1)
        fun parse(x: String): Double? {
            val p = x.trim().split(':')
            return try { if (p.size >= 2) p[0].toDouble() * 60 + p[1].toDouble() else p[0].toDouble() } catch (_: Throwable) { null }
        }
        AlertDialog.Builder(this).setTitle(tx("ريل يدوي", "Manual reel")).setView(col)
            .setPositiveButton(tx("أنشئ", "Create")) { _, _ ->
                val a = parse(s0.text.toString()); val b = parse(s1.text.toString())
                if (a == null || b == null || b - a < 3 || a < 0 || a >= dd.duration - 1.0) { toast(tx("وقت غير صحيح", "Invalid time")); return@setPositiveButton }
                create(a, b, tx("ريل ${fmt(a)}", "Reel ${fmt(a)}"), tx("اختيار يدوي", "Manual choice"))
            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    private fun open(id: String) = startActivity(Intent(this, PodcastEditActivity::class.java).putExtra("session", sid).putExtra("project", id))

    private fun confirm(action: String) {
        if (!PodAI.cfg(this).ok) { PodUi.aiDialog(this); return }
        val c = PodAI.cfg(this)
        val msg = if (action == PodcastExportService.ACTION_TRANSCRIBE) tx("سيُرفع صوت الحلقة (مخلوط، بدون فيديو) إلى ${c.where} للتفريغ. قد تُحتسب تكلفة على حسابك.", "The episode audio (mixed, no video) will be uploaded to ${c.where} for transcription. The service may charge your account.")
        else tx("سيُرسل النص المفرّغ فقط (بدون صوت) إلى ${c.where} لفهم الحوار واقتراح أفكار.", "Only the transcript (no audio) is sent to ${c.where} to understand the conversation and suggest ideas.")
        AlertDialog.Builder(this).setTitle(tx("موافقة على الإرسال", "Consent to send")).setMessage(msg)
            .setPositiveButton(tx("موافق", "OK")) { _, _ ->
                if (PodState.running) { toast(tx("هناك عمل آخر قيد التنفيذ", "Another job is running")); return@setPositiveButton }
                ContextCompat.startForegroundService(this, Intent(this, PodcastExportService::class.java).setAction(action).putExtra("session", sid).putExtra("target", target))
            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    private val tick = object : Runnable {
        override fun run() {
            val mine = PodState.sid == sid
            if (PodState.running && mine) {
                status.text = "${PodState.stage} ${(PodState.progress * 100).toInt()}%"
                bar.progress = (PodState.progress * 100).toInt()
            } else if (mine && PodState.doneSeq != lastDone && !PodState.running) {
                lastDone = PodState.doneSeq
                status.text = PodState.error?.let { "⚠ $it" } ?: ("✓ " + (PodState.info ?: tx("تم", "Done")))
                if (PodState.error == null) load()
            }
            ui.postDelayed(this, 500)
        }
    }
}
