package com.reactionrecorder

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.util.Locale

/** قوالب المونتاج: القائمة، الافتراضي، نسخ وحذف */
class TemplateActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private lateinit var tabs: LinearLayout
    private var kind = "episode"
    private lateinit var modeRow: LinearLayout
    private var prevMode = "podcast"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = UiKit.page(this, tx("قوالب المونتاج", "Edit templates"), tx("القالب بيقرر كيف بيطلع الفيديو: الشاشة المقسومة، الأسماء، الإطار المضيء، والإيقاع. المونتاج التلقائي بيقرر مين يظهر وإمتى. ما في مربعات عائمة.", "A template decides how the video looks: split screen, names, glow and pacing. The automatic edit decides who appears and when. No floating boxes."))
        val add = UiKit.primary(this, tx("قالب جديد", "New template")) {
            val t = TemplateStore.resolve(this, null, kind).copy().also { it.id = TemplateStore.newId(); it.name = if (kind == "reel") tx("ريلي", "My reel") else tx("قالبي", "My template") }
            TemplateStore.save(this, t)
            startActivity(Intent(this, TemplateEditActivity::class.java).putExtra("id", t.id))
        }.also { UiKit.withIcon(it, Ic.PLUS, "#FFFFFF", 20) }
        kind = intent.getStringExtra("kind") ?: "episode"
        prevMode = TemplateStore.mode
        TemplateStore.mode = intent.getStringExtra("mode") ?: "podcast"
        // البودكاست والفلوق كل واحد إله قوالبه — ما بيختلطوا
        modeRow = LinearLayout(this)
        col.addView(modeRow, UiKit.full(this, modeRow, 6))
        tabs = LinearLayout(this)
        col.addView(tabs, UiKit.full(this, tabs, 10))
        col.addView(add, UiKit.full(this, add, 10))
        val ai = LinearLayout(this)
        ai.addView(UiKit.chipIcon(this, Ic.STAR, tx("اقترح قالب بالكلام", "Describe a template")) { suggest() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginEnd = dp(6) })
        ai.addView(UiKit.chip(this, tx("نصوصك (رابط، حساب…)", "Your texts (link, handle…)")) { brandSheet(this) { render() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(ai, UiKit.full(this, ai, 8))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
    }

    override fun onResume() {
        super.onResume(); render()
    }


    /** قالب من وصفك: بالذكاء الاصطناعي إذا مضبوط، وإلا بفهم الكلمات الأساسية */
    private fun suggest() {
        val et = EditText(this).apply {
            hint = if (kind == "reel") tx("مثلًا: ريل حماسي، المتكلم على وجهه، اللي بيرد بدائرة فوق، كتابة كبيرة صفرا، ومضة بين اللقطات، ورابطي تحت آخر ٥ ثواني", "e.g. hype reel, speaker on their face, the replier in a circle on top, big yellow captions, flash between shots, my link at the end")
            else tx("مثلًا: حلقة هادية، مربع عائم دائري تحت يمين ما يغطي الوجه، أسماء أول مرة، اسم البودكاست فوق دايمًا", "e.g. calm episode, round floating box bottom right that avoids faces, names first time, podcast name always on top")
            setTextColor(Color.parseColor(UiKit.TEXT)); minLines = 3; gravity = Gravity.TOP or Gravity.START; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        UiKit.sheet(this, tx("اقترح قالب بالكلام", "Describe a template")) { col, d ->
            col.addView(UiKit.text(this, tx("احكي شو بدك، والقالب بيطلع جاهز. الأماكن (المربع، الكتابة، النصوص) بيرتبها المحرك تلقائيًا بعيد عن الوجوه وأزرار المنصة.", "Say what you want and the template comes out ready. Positions (box, captions, texts) are arranged automatically, away from faces and platform buttons."), 13f, UiKit.SUB))
            col.addView(et, UiKit.full(this, et, 8))
            val st = UiKit.text(this, "", 13f, UiKit.SUB); col.addView(st)
            val go = UiKit.primary(this, tx("اعمل القالب", "Make the template")) {
                val desc = et.text.toString().trim()
                if (desc.isEmpty()) return@primary
                if (!PodAI.cfg(this).ok) {
                    val t = TemplateIdeas.local(desc, kind)
                    t.id = TemplateStore.newId(); TemplateStore.save(this, t); d.dismiss()
                    Toast.makeText(this, tx("بدون خدمة ذكاء: فهمت الكلمات الأساسية بس. لنتيجة أدق ضيف مفتاح Gemini.", "No AI service: only key words understood. Add a Gemini key for better results."), Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, TemplateEditActivity::class.java).putExtra("id", t.id)); return@primary
                }
                st.text = tx("بفكّر…", "Thinking…")
                Thread {
                    val r = try { Result.success(PodAI.templateFromText(this, desc, kind)) } catch (e: Throwable) { Result.failure(e) }
                    runOnUiThread {
                        r.onSuccess { t -> t.id = TemplateStore.newId(); TemplateStore.save(this, t); d.dismiss(); startActivity(Intent(this, TemplateEditActivity::class.java).putExtra("id", t.id)) }
                        r.onFailure { e -> st.text = "⚠ " + (e.message ?: e.javaClass.simpleName) }
                    }
                }.start()
            }
            col.addView(go, UiKit.full(this, go, 12))
            if (!PodAI.cfg(this).ok) col.addView(UiKit.chip(this, tx("ضبط Gemini (أدق بكثير)", "Set up Gemini (much better)")) { PodUi.aiDialog(this) }.let { b -> b.also { col.addView(View(this), LinearLayout.LayoutParams(1, dp(6))) } })
        }
    }

    private fun dp(v: Int) = UiKit.dp(this, v)

    override fun onDestroy() { super.onDestroy(); TemplateStore.mode = prevMode }

    private fun render() {
        modeRow.removeAllViews()
        for ((m, n) in listOf("podcast" to tx("🎙 البودكاست", "🎙 Podcast"), "vlog" to tx("🎥 الفلوق", "🎥 Vlog")))
            modeRow.addView(UiKit.chip(this, n, TemplateStore.mode == m) { TemplateStore.mode = m; render() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        tabs.removeAllViews()
        for ((k, n) in listOf("episode" to (if (TemplateStore.mode == "vlog") tx("الفيديو الكامل (بالطول)", "Full video (portrait)") else tx("الحلقة الكاملة (بالعرض)", "Full episode (landscape)")), "reel" to tx("ريلز (بالطول)", "Reels (portrait)")))
            tabs.addView(UiKit.chip(this, n, kind == k) { kind = k; render() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        list.removeAllViews()
        val def = TemplateStore.defaultId(this, kind)
        for (t in TemplateStore.ofKind(this, kind)) {
            val c = UiKit.card(this, null, list)
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(UiKit.text(this, t.name, 17f, UiKit.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (t.id == def) head.addView(UiKit.badge(this, tx("الافتراضي", "Default"), UiKit.BLUE))
            else if (t.builtIn) head.addView(UiKit.badge(this, tx("جاهز", "Built-in"), UiKit.SUB))
            c.addView(head)
            c.addView(UiKit.text(this, summary(t), 13.5f, UiKit.SUB))
            val demo = LayoutDemoView(this).apply { tpl = t; names = TemplateActivity.demoNames(t); landscape = t.kind != "reel" && t.orient != "v"; brand = Brand.values(this@TemplateActivity); showGuides = t.kind == "reel" || t.orient == "v" }
            if (t.kind == "reel") {
                val w = LinearLayout(this).apply { gravity = Gravity.CENTER }
                w.addView(demo, LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT))
                c.addView(w, UiKit.full(this, w, 8))
            } else c.addView(demo, UiKit.full(this, demo, 8))
            val r = LinearLayout(this)
            fun btn(label: String, run: () -> Unit) = r.addView(UiKit.chip(this, label) { run() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(8), dp(3), 0) })
            if (t.id != def) btn(tx("اجعله الافتراضي", "Make default")) { TemplateStore.setDefault(this, t.id); render() }
            btn(if (t.builtIn) tx("انسخ وعدّل", "Copy & edit") else tx("عدّل", "Edit")) {
                val id = if (t.builtIn) t.copy().also { it.id = TemplateStore.newId(); it.name = t.name + tx(" (نسختي)", " (mine)"); TemplateStore.save(this, it) }.id else t.id
                startActivity(Intent(this, TemplateEditActivity::class.java).putExtra("id", id))
            }
            if (!t.builtIn) btn(tx("احذف", "Delete")) {
                AlertDialog.Builder(this).setMessage(tx("تحذف القالب «${t.name}»؟ المشاريع اللي بتستعمله بترجع للافتراضي.", "Delete “${t.name}”? Projects using it go back to the default."))
                    .setPositiveButton(tx("احذف", "Delete")) { _, _ -> TemplateStore.delete(this, t.id); render() }
                    .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
            }
            c.addView(r)
        }
    }

    companion object {
        /** نصوصك الثابتة: الرابط، الحساب، اسم البودكاست، الضيف */
        fun brandSheet(a: android.app.Activity, after: () -> Unit) {
            UiKit.sheet(a, tx("نصوصك الثابتة", "Your fixed texts")) { col, d ->
                col.addView(UiKit.text(a, tx("بتكتبها مرة، وكل قالب فيه {link} أو {handle}… بيحطها بالفيديو تلقائيًا.", "Write them once; any template with {link}, {handle}… puts them in the video automatically."), 13f, UiKit.SUB))
                val fields = HashMap<String, EditText>()
                for (k in Brand.KEYS) {
                    col.addView(UiKit.text(a, Brand.label(k) + "  {$k}", 13.5f, UiKit.TEXT, true).apply { setPadding(0, UiKit.dp(a, 10), 0, 0) })
                    val e = EditText(a).apply { setText(Brand.get(a, k)); setSingleLine(); setTextColor(Color.parseColor(UiKit.TEXT)); textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
                    fields[k] = e; col.addView(e)
                }
                val ok = UiKit.primary(a, tx("حفظ", "Save")) { for ((k, e) in fields) Brand.set(a, k, e.text.toString()); UiKit.done(a, tx("انحفظ", "Saved")); d.dismiss(); after() }
                col.addView(ok, UiKit.full(a, ok, 14))
            }
        }

        /** صورة القالب: فلوق «بدون مونتاج» = كاميرا وحدة؛ غير هيك شخصين */
        fun demoNames(t: PodTemplate): List<String> =
            if (t.mode == "vlog" && t.layout != "grid") listOf(tx("أنا", "Me"))
            else if (t.mode == "vlog") listOf(tx("أنا", "Me"), tx("كاميرا ٢", "Camera 2"))
            else listOf(tx("شخص ١", "Person 1"), tx("شخص ٢", "Person 2"))

        fun summary(t: PodTemplate): String {
            if (t.mode == "vlog" && t.layout != "grid") return tx("كاميرا وحدة طول الوقت — بدون قص ولا تبديل", "One camera all the time — no cuts or switching") + (if (t.names != "never") tx(" · أسماء", " · names") else "")
            val pace = when (t.intensity) { "calm" -> tx("هادئ", "Calm"); "lively" -> tx("حيوي", "Lively"); else -> tx("متوازن", "Balanced") }
            val box = if (!t.pip) tx("المقاطِع: شاشة مقسومة", "Interrupter: split screen") else
                tx("مربع عائم ", "Floating box ") + when (t.pipShape) { "circle" -> tx("دائري", "circle"); "rect" -> tx("مستطيل", "rectangle"); "alternate" -> tx("دائري/مربع بالتناوب", "circle/square alternating"); else -> tx("زوايا دائرية", "rounded") } +
                    when (t.pipPlace) { "person" -> tx("، حسب الشخص", ", by person"); "alternate" -> tx("، يمين/يسار بالتناوب", ", alternating sides"); else -> "" }
            if (t.layout == "grid") return tx("الكل ظاهر طول الوقت", "Everyone on screen") + (if (t.glow) tx(" · إطار مضيء", " · glow") else "") + (if (t.captions) tx(" · كتابة", " · captions") else "")
            return "$pace · $box" + (if (t.pipFace) tx(" · على الوجه", " · on the face") else "") + (if (t.avoidFace) tx(" · ما بيغطي الوجه", " · avoids faces") else "") +
                (if (t.breakEvery > 0) tx(" · كسر الملل كل ${t.breakEvery}ث", " · break every ${t.breakEvery}s") else "") +
                (if (t.names != "never") tx(" · أسماء", " · names") else "") + (if (t.glow) tx(" · إطار مضيء", " · glow") else "") +
                (if (t.kind == "reel") (if (t.captions) tx(" · كتابة", " · captions") else "") + (if (t.zoomMode == "auto") tx(" · زوم", " · zoom") else "") else "")
        }
    }
}

/** محرر قالب: معاينة متحركة فوق (اسحب المربع وكبّره بإصبعين) وخيارات واسعة تحت */
class TemplateEditActivity : BaseActivity() {
    private lateinit var t: PodTemplate
    private lateinit var demo: LayoutDemoView
    private lateinit var cap: TextView
    private lateinit var opts: LinearLayout
    private lateinit var modeRow: LinearLayout
    private var editing = false
    private lateinit var layerBar: LinearLayout
    /** لشو الصورة/الخط اللي عم نختاره: "bg" أو رقم عنصر (شعار) */
    private var pickFor = ""
    private val pickImage = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@registerForActivityResult
        Thread {
            val path = VidFonts.importImage(this, u, if (pickFor == "bg") "bg" else "logo")
            runOnUiThread {
                if (path == null) { Toast.makeText(this, tx("تعذر فتح الصورة", "Could not open the image"), Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                if (pickFor == "bg") { t.bgImage = path; if (t.multi != "boxes" && t.fitBg != "image") t.fitBg = "image" }
                else t.items.getOrNull(pickFor.toIntOrNull() ?: -1)?.image = path
                build()
            }
        }.start()
    }
    private val pickFont = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@registerForActivityResult
        val id = VidFonts.import(this, u)
        if (id == null) Toast.makeText(this, tx("الملف مش خط (TTF/OTF)", "Not a font file (TTF/OTF)"), Toast.LENGTH_SHORT).show() else { t.font = id; build() }
    }

    private fun dp(v: Int) = UiKit.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")
        val found = TemplateStore.get(this, id)
        if (found == null) { finish(); return }
        t = if (found.builtIn) found.copy().also { it.id = TemplateStore.newId() } else found.copy().also { it.id = found.id }
        orig = found.toJson()
        origBuiltIn = found.builtIn
        // جاي من «شو المطبّق» بدبل تاب: نفتح التبويب والقسم المطلوب
        intent.getStringExtra("focus")?.let { f -> TAB_OF[f]?.let { tab = it } }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(UiKit.BG)) }
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }
        // المعاينة ثابتة فوق
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(8)) }
        top.addView(UiKit.header(this, tx("تعديل القالب", "Edit template")))
        demo = LayoutDemoView(this).apply { tpl = t; names = TemplateActivity.demoNames(t); onTplChanged = { syncOpts() }; landscape = t.kind != "reel" && t.orient != "v"; brand = Brand.values(this@TemplateEditActivity); showGuides = t.kind == "reel" || t.orient == "v" }
        if (t.kind == "reel") {
            val w = LinearLayout(this).apply { gravity = Gravity.CENTER }
            w.addView(demo, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT))
            top.addView(w)
        } else top.addView(demo)
        cap = UiKit.text(this, "", 13.5f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        top.addView(cap)
        demo.onCaption = { s -> cap.post { cap.text = s } }
        modeRow = LinearLayout(this)
        top.addView(modeRow)
        layerBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        top.addView(layerBar)
        demo.onLayerSelected = { k -> layerBar.post { renderLayers() } }
        renderMode()
        root.addView(top)
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#2A2C32")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        opts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(20)) }
        root.addView(ScrollView(this).apply { addView(opts) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(this).apply { setPadding(dp(16), dp(10), dp(16), dp(12)) }
        bottom.addView(UiKit.chip(this, tx("احفظ", "Save")) { save(false) }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(8) })
        bottom.addView(UiKit.primary(this, tx("احفظ واجعله الافتراضي", "Save as default")) { save(true) }, LinearLayout.LayoutParams(0, dp(54), 1.4f))
        root.addView(bottom)
        build()
        intent.getStringExtra("focus")?.let { f -> opts.post { val v = secViews[f] ?: return@post; (opts.parent as? ScrollView)?.smoothScrollTo(0, v.top) } }
    }

    /** القالب زي ما كان لما فتحته (للمقارنة قبل ← بعد) */
    private var orig: org.json.JSONObject = org.json.JSONObject()
    private var origBuiltIn = false

    /** شو تغيّر: [اسم الإعداد، قبل، بعد] */
    private fun changes(): List<Triple<String, String, String>> {
        val now = t.toJson()
        val out = ArrayList<Triple<String, String, String>>()
        var other = 0
        for (k in (orig.keys().asSequence() + now.keys().asSequence()).toSet()) {
            if (k == "id" || k == "bi") continue
            val a = orig.opt(k)?.toString() ?: ""; val b = now.opt(k)?.toString() ?: ""
            if (a == b) continue
            val name = TplDiff.name(k)
            if (name == null) { other++; continue }
            out += Triple(name, TplDiff.value(k, a), TplDiff.value(k, b))
        }
        if (other > 0) out += Triple(tx("إعدادات ثانية", "Other settings"), "", tx("$other تغيير", "$other change(s)"))
        return out
    }

    /** زر الرجوع (فوق أو بالجوال) مع تعديلات مش محفوظة → نفس نافذة «شو تغيّر» */
    private var closing = false
    override fun finish() {
        if (!closing && ::t.isInitialized && changes().isNotEmpty()) { saveSheet(false); return }
        super.finish()
    }
    private fun close() { closing = true; finish() }

    private fun save(asDefault: Boolean) {
        if (changes().isEmpty() && !origBuiltIn) {
            if (asDefault) { TemplateStore.setDefault(this, t.id); Toast.makeText(this, tx("صار الافتراضي", "Now the default"), Toast.LENGTH_SHORT).show() }
            close(); return
        }
        saveSheet(asDefault)
    }

    /**
     * قبل ما ينحفظ: هيك كان القالب ← وهيك صار، وانت بتختار:
     * احفظ على نفس القالب (بيتغيّر بكل مشروع لسا ما صدّر وبيستعمله) / قالب جديد بإسم / الغي التعديلات.
     */
    private fun saveSheet(asDefault: Boolean) {
        val ch = changes()
        UiKit.sheet(this, tx("شو تغيّر بالقالب", "What changed in the template")) { col, dlg ->
            if (ch.isEmpty()) col.addView(UiKit.text(this, tx("ما في تغيير.", "No changes."), 14f, UiKit.SUB))
            for ((n, a, b) in ch) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                r.addView(UiKit.text(this, n, 14.5f, UiKit.TEXT, true))
                r.addView(UiKit.text(this, if (a.isEmpty()) b else "$a  ←  $b", 13.5f, UiKit.SUB))
                col.addView(r)
            }
            fun finishSaved(msg: String) {
                if (asDefault) TemplateStore.setDefault(this, t.id)
                // المشروع اللي فتحت منه بياخد التعديل فورًا (ولو قالب جديد: بيصير عليه)
                setResult(RESULT_OK, android.content.Intent().putExtra("tpl", t.id).putExtra("before", orig.toString()))
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); dlg.dismiss(); close()
            }
            if (!origBuiltIn) {
                val same = UiKit.primary(this, tx("احفظ على القالب «${orig.optString("name")}»", "Save to “${orig.optString("name")}”")) {
                    TemplateStore.save(this, t); finishSaved(tx("انحفظ — المشاريع اللي لسا ما صدّرت بتمشي عليه", "Saved — projects not yet exported follow it"))
                }
                col.addView(same, UiKit.full(this, same, 14, 54))
                col.addView(UiKit.text(this, tx("بيتغيّر بكل مشروع بيستعمله ولسا ما بلّش تصديره. اللي عم يتصدّر هلأ ما بيتأثر.", "Changes every project using it that hasn't started exporting. An export already running isn't affected."), 12f, UiKit.SUB))
            }
            val asNew = UiKit.chip(this, tx("＋ احفظ كقالب جديد…", "＋ Save as a new template…"), origBuiltIn) {
                val et = android.widget.EditText(this).apply { setText(if (origBuiltIn) t.name else t.name + tx(" (جديد)", " (new)")); setSingleLine(); setTextColor(Color.WHITE) }
                android.app.AlertDialog.Builder(this).setTitle(tx("اسم القالب الجديد", "New template name")).setView(et)
                    .setPositiveButton(tx("احفظ", "Save")) { _, _ ->
                        t.id = TemplateStore.newId(); t.name = et.text.toString().trim().ifBlank { t.name }
                        TemplateStore.save(this, t)
                        finishSaved(tx("انعمل قالب جديد «${t.name}» — القديم زي ما هو", "New template “${t.name}” — the old one is unchanged"))
                    }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
            }
            col.addView(asNew, UiKit.full(this, asNew, 10, 54))
            val discard = UiKit.chip(this, tx("✕ الغي التعديلات", "✕ Discard the changes")) { dlg.dismiss(); close() }
            col.addView(discard, UiKit.full(this, discard, 10, 54))
        }
    }

    private fun renderMode() {
        modeRow.removeAllViews()
        fun add(label: String, sel: Boolean, run: () -> Unit) = modeRow.addView(UiKit.chip(this, label, sel) { run(); renderMode() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(6), dp(2), 0) })
        add(tx("شغّل النموذج", "Play sample"), !editing) { editing = false; demo.hold = -1; demo.editable = false; demo.layersMode = false }
        add(tx("الطبقات", "Layers"), editing) {
            editing = true; demo.hold = LayoutDemoView.HOLD_PIP; demo.editable = true; demo.layersMode = true
            if (demo.selLayer == null) demo.selLayer = layers().firstOrNull()?.first
        }
        if (t.kind != "reel") {
            add("16:9", demo.landscape) { demo.landscape = true; demo.requestLayout() }
            add("9:16", !demo.landscape) { demo.landscape = false; demo.requestLayout() }
        }
        renderLayers()
    }

    /** الطبقات الموجودة بالقالب: [مفتاح، اسم] */
    private fun layers(): List<Pair<String, String>> {
        val l = ArrayList<Pair<String, String>>()
        for ((i, x) in t.items.withIndex()) l += "item:$i" to (if (x.kind == "image") tx("الشعار", "Logo") + (if (t.items.count { it.kind == "image" } > 1) " ${i + 1}" else "") else Brand.KEYS.firstOrNull { x.text == "{$it}" }?.let { Brand.label(it).substringBefore(" (") } ?: x.text.take(14))
        if (t.names != "never") l += "name" to tx("اسم الشخص", "Person's name")
        if (t.titleOn) l += "title" to tx("العنوان", "Title")
        if (t.captions) l += "cap" to tx("الكتابة", "Captions")
        if (t.pip) l += "pip" to tx("المربع العائم", "Floating box")
        return l
    }

    /** شريط الطبقات تحت النموذج: بتختار عنصر → بيضوي لحاله وبتسحبه وبتكبّره بإصبعين */
    private fun renderLayers() {
        layerBar.removeAllViews()
        if (!editing) return
        val ls = layers()
        if (ls.isEmpty()) { layerBar.addView(UiKit.text(this, tx("ما في عناصر — ضيف نص أو شعار أو فعّل الأسماء/العنوان/الكتابة.", "No layers — add a text or logo, or turn on names/title/captions."), 12.5f, UiKit.SUB)); return }
        val row = LinearLayout(this)
        for ((k, n) in ls) row.addView(UiKit.chip(this, n, k == demo.selLayer) { demo.selLayer = k; renderLayers() }.apply { textSize = 13f; setPadding(dp(10), 0, dp(10), 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(2), dp(4), dp(2), 0) })
        layerBar.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        val sel = demo.selLayer ?: return
        val act = LinearLayout(this)
        act.addView(UiKit.chip(this, tx("⚙ خياراته", "⚙ Options")) { layerSheet(sel) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(4), dp(2), 0) })
        act.addView(UiKit.chip(this, tx("↺ مكانه الأصلي", "↺ Default spot")) { resetLayer(sel); demo.invalidate() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(4), dp(2), 0) })
        layerBar.addView(act)
        layerBar.addView(UiKit.text(this, tx("اسحب العنصر المضوّي لأي مكان، وكبّره وصغّره بإصبعين. الباقي ما بيتحرك.", "Drag the highlighted layer anywhere; pinch to resize. Others stay put."), 11.5f, UiKit.SUB).apply { gravity = Gravity.CENTER })
    }

    private fun nameSheet() = NameStyle.show(this, t, tx("عماد الخطيب", "Sam Lee"), demo.landscape, { demo.invalidate() }) { build(); renderLayers() }

    private fun resetLayer(k: String) {
        when (k) {
            "pip" -> { t.pipPos = "tr"; t.pipSize = 0.3f }
            "name" -> { t.nameX = -1f; t.nameY = -1f; t.nameSize = 1f }
            "title" -> { t.hookX = -1f; t.hookY = -1f; t.hookSize = 1f }
            "cap" -> { t.capX = -1f; t.capY = -1f; t.capSize = 1f }
            else -> t.items.getOrNull(k.removePrefix("item:").toIntOrNull() ?: -1)?.let { it.x = -1f; it.y = -1f; it.size = 1f }
        }
    }

    /** خيارات طبقة: المكان (بالضغط على شاشة صغيرة)، الحجم، وخيارات النص */
    private fun layerSheet(k: String) {
        if (k.startsWith("item:")) { itemSheet(t, k.removePrefix("item:").toIntOrNull() ?: return); return }
        if (k == "name") { nameSheet(); return }
        UiKit.sheet(this, layers().firstOrNull { it.first == k }?.second ?: "") { col, d ->
            fun cur(): Pair<Float, Float> = when (k) { "name" -> t.nameX to t.nameY; "title" -> t.hookX to t.hookY; "cap" -> t.capX to t.capY; else -> (if (t.pipPos == "custom") t.pipX else -1f) to (if (t.pipPos == "custom") t.pipY else -1f) }
            col.addView(UiKit.text(this, tx("المكان: اضغط وين بدك إياه", "Position: tap where you want it"), 14f, UiKit.TEXT, true))
            val pad = PosPad(this, demo.landscape, cur().first, cur().second) { fx, fy ->
                when (k) { "name" -> { t.nameX = fx; t.nameY = fy }; "title" -> { t.hookX = fx; t.hookY = fy }; "cap" -> { t.capX = fx; t.capY = fy }; else -> { t.pipPos = "custom"; t.pipX = fx; t.pipY = fy } }
                demo.invalidate()
            }
            col.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            when (k) {
                "name" -> {
                    slider(col, tx("الحجم", "Size"), 0.5f, 4f, t.nameSize, ::pct) { t.nameSize = it }
                    col.addView(UiKit.text(this, tx("طريقة الظهور", "How it appears"), 14f, UiKit.TEXT, true).apply { setPadding(0, dp(10), 0, 0) })
                    chips(col, listOf("type" to tx("كيبورد", "Typing"), "fade" to tx("تلاشي", "Fade"), "none" to tx("بدون حركة", "No motion")), t.nameAnim) { t.nameAnim = it }
                    chips(col, listOf("1" to tx("بخلفية", "Background"), "0" to tx("بدون خلفية", "No background")), if (t.nameBg) "1" else "0") { t.nameBg = it == "1" }
                    col.addView(UiKit.text(this, tx("الخط", "Font"), 14f, UiKit.TEXT, true).apply { setPadding(0, dp(10), 0, 0) })
                    chips(col, VidFonts.list.map { it.id to it.label }, t.font) { t.font = it }
                    col.addView(UiKit.text(this, tx("(خط الاسم هو خط القالب)", "(the name uses the template font)"), 12f, UiKit.SUB))
                }
                "title" -> {
                    slider(col, tx("الحجم", "Size"), 0.5f, 3f, t.hookSize, ::pct) { t.hookSize = it }
                    col.addView(UiKit.text(this, tx("طريقة الظهور", "How it appears"), 14f, UiKit.TEXT, true).apply { setPadding(0, dp(10), 0, 0) })
                    chips(col, listOf("fade" to tx("تلاشي", "Fade"), "slide" to tx("انزلاق", "Slide"), "wipe" to tx("مسح", "Wipe"), "none" to tx("بدون", "None")), t.hookIn) { t.hookIn = it; t.hookOut = it; d.dismiss(); col.post { layerSheet(k) } }
                    chips(col, listOf("box" to tx("بخلفية", "Boxed"), "big" to tx("كبير بالنص", "Big"), "lines" to tx("أسطر", "Lines")), t.hookStyle) { t.hookStyle = it; d.dismiss(); col.post { layerSheet(k) } }
                }
                "cap" -> slider(col, tx("الحجم", "Size"), 0.5f, 2.5f, t.capSize, ::pct) { t.capSize = it }
                else -> slider(col, tx("الحجم", "Size"), 0.1f, 0.9f, t.pipSize, ::pct) { t.pipSize = it }
            }
            val ok = UiKit.primary(this, tx("تم", "Done")) { d.dismiss(); build(); renderLayers() }
            col.addView(ok, UiKit.full(this, ok, 14))
        }
    }

    private var lastSig = ""
    /** بعد السحب: نحدّث أرقام الخيارات بدون ما نعيد البناء كل حركة */
    private fun syncOpts() {
        val s = "${t.pipPos}${(t.pipSize * 100).toInt()}${(t.nameX * 50).toInt()}${(t.nameY * 50).toInt()}${(t.hookX * 50).toInt()}${(t.capY * 50).toInt()}" + t.items.joinToString { "${(it.x * 50).toInt()},${(it.y * 50).toInt()},${(it.size * 20).toInt()}" }
        if (s != lastSig) { lastSig = s; opts.removeCallbacks(rebuild); opts.postDelayed(rebuild, 250) }
    }
    private val rebuild = Runnable { build() }

    // ───────── عناصر ─────────

    private fun chips(parent: LinearLayout, items: List<Pair<String, String>>, cur: String, set: (String) -> Unit) {
        // عدد الأزرار بالصف حسب طول الكلام — عشان ولا كلمة تنقص
        val longest = items.maxOf { it.second.split("\n").maxOf { l -> l.length } }
        val per = when {
            items.size == 2 -> if (longest > 20) 1 else 2
            items.size == 3 -> if (longest > 11) 1 else 3
            longest > 17 -> 1
            longest > 9 || items.size == 4 -> 2
            else -> 3
        }
        var row: LinearLayout? = null
        for ((i, p) in items.withIndex()) {
            if (i % per == 0) { row = LinearLayout(this); parent.addView(row) }
            row!!.addView(UiKit.chip(this, p.second, p.first == cur) { set(p.first); build() }.apply {
                textSize = 13.5f; setPadding(dp(8), dp(6), dp(8), dp(6))
                maxLines = 2; setAutoSizeTextTypeWithDefaults(android.widget.TextView.AUTO_SIZE_TEXT_TYPE_NONE); textSize = 13.5f
                if (p.second.contains("\n")) minHeight = dp(58)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
        }
        val left = items.size % per
        if (left != 0) repeat(per - left) { row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
    }

    private fun label(parent: LinearLayout, s: String) = parent.addView(UiKit.text(this, s, 14.5f, UiKit.TEXT, true).apply { setPadding(0, dp(12), 0, dp(2)) })
    /** الشرح مطوي: «ⓘ شرح» وبيفتح لما تضغطه (أقل كلام بالشاشة) */
    private fun note(parent: LinearLayout, s: String) = parent.addView(UiKit.text(this, "ⓘ " + tx("شرح", "Info"), 12.5f, UiKit.SUB).apply {
        setPadding(0, dp(4), 0, dp(4)); var open = false
        setOnClickListener { open = !open; text = if (open) "ⓘ $s" else "ⓘ " + tx("شرح", "Info") }
    })

    private fun toggle(parent: LinearLayout, on: Boolean, yes: String, no: String, set: (Boolean) -> Unit) {
        val b = UiKit.chip(this, if (on) yes else no, on) { set(!on); build() }
        parent.addView(b, UiKit.full(this, b, 6))
    }

    private fun swatches(parent: LinearLayout, colors: List<Int>, cur: Int, set: (Int) -> Unit) {
        val r = LinearLayout(this)
        for (c in colors) {
            r.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(c); setStroke(dp(if (c == cur) 3 else 1), Color.parseColor(if (c == cur) UiKit.BLUE else UiKit.LINE)) }
                contentDescription = String.format("#%06X", c and 0xFFFFFF)
                setOnClickListener { set(c); build() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0, dp(6), dp(10), 0) })
        }
        parent.addView(r)
    }

    private fun slider(parent: LinearLayout, title: String, min: Float, max: Float, cur: Float, fmt: (Float) -> String, set: (Float) -> Unit) {
        val tv = UiKit.text(this, "$title: ${fmt(cur)}", 14f, UiKit.TEXT)
        parent.addView(tv.apply { setPadding(0, dp(10), 0, 0) })
        parent.addView(SeekBar(this).apply {
            this.max = 100; progress = (((cur - min) / (max - min)) * 100).toInt().coerceIn(0, 100)
            minimumHeight = dp(40)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (!u) return; val v = min + (max - min) * p / 100f; set(v); tv.text = "$title: ${fmt(v)}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    private fun pct(v: Float) = "${(v * 100).toInt()}%"

    private val palette = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF3D8BFF.toInt(), 0xFFFFD60A.toInt(), 0xFFFF453A.toInt(), 0xFF32D74B.toInt(), 0xFFB45CE0.toInt(), 0xFF1B1D24.toInt())

    // ───────── الخيارات ─────────

    /** المحرر ٤ تبويبات بدل قائمة طويلة: الشكل · الكتابة · الإيقاع · متقدم */
    private val TAB_OF = mapOf("tpl_pip" to 0, "tpl_multi" to 0, "tpl_glow" to 0, "tpl_items" to 0,
        "tpl_names" to 1, "tpl_title" to 1, "tpl_font" to 1, "tpl_caps" to 1,
        "tpl_pace" to 2, "tpl_overlap" to 2, "tpl_trans" to 2, "tpl_look" to 2, "tpl_motion" to 2, "tpl_pin" to 3, "tpl_edit" to 3)
    private var tab: Int
        get() = getSharedPreferences("reaction_ui", MODE_PRIVATE).getInt("tpl_tab", 0)
        set(v) { getSharedPreferences("reaction_ui", MODE_PRIVATE).edit().putInt("tpl_tab", v).apply() }

    private fun sec(key: String, title: String, summary: String = "", openByDefault: Boolean = false): LinearLayout {
        if ((TAB_OF[key] ?: 3) != tab) return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // بوضع «كاميرا اللي بيحكي» هدول مطفيين: بنقول هيك بدل ما نعرض خيارات ما إلها مفعول
        // شكل المربع العائم بيطلع بس لما «التعليقات بمربع»، وشكل التقسيم بس لما «الاثنين مع بعض» (تبويب الإيقاع)
        val off = when (key) {
            "tpl_pip" -> if (!t.pip) tx("مطفي — شغّله من المونتاج ← ٣ التعليقات القصيرة", "Off — turn it on in Editing → 3 Short comments") else null
            "tpl_multi" -> if (t.overlap !in setOf("both", "split", "wide")) tx("ما في تقسيم — من المونتاج ← ٢ لما اثنين يحكوا مع بعض", "No split — set in Editing → 2 When two talk at once") else null
            else -> null
        }
        if (off != null) {
            UiKit.section(this, opts, key, title, off, false)
            return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        }
        return UiKit.section(this, opts, key, title, summary, true).also { b -> (b.parent as? View)?.let { secViews[key] = it } }
    }
    private val secViews = HashMap<String, View>()

    private fun tabBar() {
        val seg = LinearLayout(this).apply { background = UiKit.bg(UiKit.CARD, 12, this@TemplateEditActivity); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val names = listOf(tx("الشكل", "Look"), tx("الكتابة", "Text"), tx("المونتاج", "Editing"), tx("متقدم", "Advanced"))
        for ((i, n) in names.withIndex()) {
            val on = i == tab
            seg.addView(android.widget.Button(this).apply {
                text = n; isAllCaps = false; textSize = 14f; stateListAnimator = null
                setTextColor(Color.parseColor(if (on) UiKit.TEXT else UiKit.SUB)); minHeight = dp(44); minimumHeight = dp(44)
                background = if (on) UiKit.bg(UiKit.BLUE_BG, 10, this@TemplateEditActivity, UiKit.BLUE) else null
                setOnClickListener { if (tab != i) { tab = i; build() } }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        opts.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); bottomMargin = dp(6) })
    }

    /** كسر الملل: شريط من ١٠ ثواني لـ ٥ دقايق + طول اللقطة + عشوائية خفيفة */
    private fun breakControls(p: LinearLayout, t: PodTemplate) {
        fun every(v: Int) = if (v <= 0) tx("مطفي", "Off") else if (v < 60) tx("كل $v ث", "every $v s") else tx("كل ${v / 60}:${String.format(Locale.US, "%02d", v % 60)} د", "every ${v / 60}:${String.format(Locale.US, "%02d", v % 60)} min")
        val est = UiKit.text(this, "", 13f, UiKit.SUB)
        fun estimate() { est.text = if (t.breakEvery <= 0) tx("مطفي — الكاميرا بتضل على اللي بيحكي طول ما هو بيحكي.", "Off — the camera stays on the speaker as long as they talk.")
            else tx("لو حدا حكى ٥ دقايق متواصل: تقريبًا ${(300 / (t.breakEvery + t.breakLen)).toInt()} قلبة. (بس بالكلام الطويل — التبديل الطبيعي بين المتكلمين ما بيتأثر)", "If someone talks 5 minutes straight: about ${(300 / (t.breakEvery + t.breakLen)).toInt()} cutaways. (Only during long talk — natural switching isn't affected)") }
        // الشريط: ٠ = مطفي، وبعدين ١٠..٣٠٠ ث بخطوة ٥
        slider(p, tx("كسر الملل", "Break monotony"), 0f, 1f, if (t.breakEvery <= 0) 0f else ((t.breakEvery - 10) / 290f).coerceIn(0.01f, 1f), { v -> every(if (v < 0.01f) 0 else (10 + (v * 290).toInt() / 5 * 5)) }) { v ->
            t.breakEvery = if (v < 0.01f) 0 else 10 + (v * 290).toInt() / 5 * 5; estimate()
        }
        p.addView(est); estimate()
        slider(p, tx("طول اللقطة", "Cutaway length"), 1.5f, 5f, t.breakLen, { String.format(Locale.US, "%.1f ث", it) }) { t.breakLen = (it * 2).toInt() / 2f }
        chips(p, listOf("auto" to tx("بالتناوب", "Alternate"), "listener" to tx("على المستمع", "Listener"), "wide" to tx("الكاميرا العامة", "Wide camera"), "zoom" to tx("تقريب عليه", "Punch-in")), t.breakTo) { t.breakTo = it }
        toggle(p, t.breakVar, tx("مش منتظم بالثانية (طبيعي أكثر): نعم", "Not exactly regular (more natural): yes"), tx("مش منتظم بالثانية: لا (بالضبط)", "Not exactly regular: no (exact)")) { t.breakVar = it }
        note(p, tx("ما بيقلب إذا صار رد فعل قريب. إذا اخترت «الكاميرا العامة» وما في وحدة، بيصير تقريب بدالها.", "No cutaway right after a reaction shot. If you pick “Wide camera” and there is none, a punch-in is used."))
    }

    /** حركات المونتاج والكتابة والعنوان */
    private fun motionSection(t: PodTemplate) {
        val mv = sec("tpl_motion", tx("حركات بتشد المشاهد", "Engaging motion"), listOfNotNull(
            if (t.punch != "off") tx("تقريب سريع", "punch-ins") else null, if (t.trans != "cut") tx("انتقالات", "transitions") else null,
            if (t.progress) tx("شريط", "bar") else null, if (t.titleOn) tx("عنوان", "title") else null).joinToString(" · ").ifBlank { tx("بدون", "None") }, openByDefault = t.kind == "reel")
        if (t.punch != "off" && t.follow) slider(mv, tx("قوة التقريب", "Punch strength"), 1.05f, 1.35f, t.punchAmt, { String.format(Locale.US, "%.2f×", it) }) { t.punchAmt = it }
        note(mv, tx("مع فهم الحوار (Gemini): على الكلمات والجمل المهمة. بدونه: على بداية كل كلام طويل.", "With understanding (Gemini): on key words and sentences. Without it: at the start of each long turn."))
        toggle(mv, t.progress, tx("شريط تقدّم فوق: نعم", "Progress bar on top: yes"), tx("شريط تقدّم فوق: لا", "Progress bar on top: no")) { t.progress = it }
        // ── العنوان الافتتاحي
        val ti = sec("tpl_title", tx("العنوان بأول الفيديو", "Opening title"), if (!t.titleOn) tx("بدون", "None") else when (t.hookStyle) { "lines" -> tx("أسطر بخلفية", "Highlighted lines"); "big" -> tx("كبير بالنص", "Big"); else -> tx("بمربع", "Boxed") } + " · ${t.hookSecs.toInt()} " + tx("ث", "s"), openByDefault = t.titleOn)
        toggle(ti, t.titleOn, tx("عنوان بالبداية: نعم", "Opening title: yes"), tx("عنوان بالبداية: لا", "Opening title: no")) { t.titleOn = it }
        if (t.titleOn) {
            label(ti, tx("الشكل", "Style"))
            chips(ti, listOf("lines" to tx("أسطر بخلفية ملوّنة", "Highlighted lines"), "big" to tx("كبير بالنص", "Big, centred"), "box" to tx("بمربع", "Boxed")), t.hookStyle) { t.hookStyle = it }
            label(ti, tx("المكان", "Position"))
            chips(ti, listOf("top" to tx("فوق", "Top"), "middle" to tx("بالنص", "Middle"), "bottom" to tx("تحت", "Bottom")), t.hookPos) { t.hookPos = it }
            if (t.hookStyle != "big") chips(ti, listOf("start" to tx("على اليمين", "Aligned to start"), "center" to tx("بالنص", "Centred")), t.hookAlign) { t.hookAlign = it }
            slider(ti, tx("كم ثانية بيضل", "How long it stays"), 1.5f, 10f, t.hookSecs, { String.format(Locale.US, "%.1f ث", it) }) { t.hookSecs = (it * 2).toInt() / 2f }
            label(ti, tx("كيف بيدخل", "How it enters"))
            chips(ti, listOf("none" to tx("مباشر", "Instant"), "fade" to tx("تلاشي", "Fade"), "slide" to tx("سحب", "Slide"), "wipe" to tx("مسح", "Wipe")), t.hookIn) { t.hookIn = it }
            label(ti, tx("كيف بيروح", "How it leaves"))
            chips(ti, listOf("none" to tx("مباشر", "Instant"), "fade" to tx("تلاشي", "Fade"), "slide" to tx("سحب", "Slide"), "wipe" to tx("مسح", "Wipe")), t.hookOut) { t.hookOut = it }
            label(ti, tx("لون الكتابة", "Text colour"))
            swatches(ti, listOf(0xFF111111.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt(), 0xFF3D8BFF.toInt(), 0xFFFF453A.toInt()), t.hookColor) { t.hookColor = it }
            if (t.hookStyle == "lines") {
                label(ti, tx("لون خلفية الأسطر", "Line background colour"))
                swatches(ti, listOf(0xFFFFD54F.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFF3D8BFF.toInt(), 0xFFFF453A.toInt(), 0xFF32D74B.toInt()), t.hookBg) { t.hookBg = it }
            }
            note(ti, tx("النص هو عنوان الحلقة أو الريل (Gemini بيقترحه من الكلام، وبتعدّله من المونتاج). العنوان والكتابة مستقلين: بتقدر تخلي عنوان بس بدون كتابة.", "The text is the episode/reel title (Gemini suggests it; edit it in the editor). Title and captions are independent: you can have a title without captions."))
        }
        // ── الخط
        val fo = sec("tpl_font", tx("الخط على الفيديو", "Font on the video"), VidFonts.label(t.font))
        chips(fo, VidFonts.list.map { it.id to it.label } + (if (t.font.startsWith("file:")) listOf(t.font to VidFonts.label(t.font)) else emptyList()), t.font) { t.font = it }
        note(fo, tx("للعنوان والكتابة والنصوص الثابتة. كلها خطوط مجانية، أو ضيف خطك (ملف TTF أو OTF).", "For the title, captions and fixed texts. All are free fonts, or add your own (TTF or OTF file)."))
        toggle(fo, false, "", tx("ضيف خطك من ملف", "Add your font from a file")) { pickFont.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*")) }
    }

    /** العناصر الثابتة: رابط، اسم الحساب… */
    private fun itemsSection(t: PodTemplate) {
        val it0 = sec("tpl_items", tx("نصوص ثابتة على الفيديو", "Fixed texts on the video"), if (t.items.isEmpty()) tx("بدون", "None") else "${t.items.size}")
        note(it0, tx("مثل رابطك أو اسم حسابك. بتطلع تلقائي بكل فيديو بهالقالب، وبالمعاينة. القيم نفسها ({link} وغيره) بتعبّيها مرة وحدة بـ «نصوصك».", "Like your link or handle. They appear automatically in every video with this template, and in the preview. Fill the values ({link}…) once in “Your texts”."))
        for ((i, x) in t.items.withIndex()) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val whenS = when (x.whenShow) { "start" -> tx("أول ${x.secs.toInt()} ث", "first ${x.secs.toInt()} s"); "end" -> tx("آخر ${x.secs.toInt()} ث", "last ${x.secs.toInt()} s"); "every" -> tx("كل ${x.everyMin.toInt()} د لـ ${x.secs.toInt()} ث", "every ${x.everyMin.toInt()} min for ${x.secs.toInt()} s"); else -> tx("طول الوقت", "always") }
            val what = if (x.kind == "image") tx("شعار", "Logo") + if (x.image.isBlank()) tx(" (اختار صورة)", " (pick an image)") else "" else Brand.KEYS.firstOrNull { x.text == "{$it}" }?.let { Brand.label(it) } ?: x.text
            r.addView(UiKit.text(this, "$what  ·  ${if (x.x >= 0f) tx("مكان حر", "free spot") else posName(x.pos)}  ·  ${(x.size * 100).toInt()}%  ·  $whenS", 14f, UiKit.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(UiKit.iconButton(this, Ic.PEN, tx("عدّل", "Edit")) { itemSheet(t, i) })
            r.addView(UiKit.iconButton(this, Ic.TRASH, tx("احذف", "Delete")) { t.items.removeAt(i); build() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(6) })
            it0.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        val row = LinearLayout(this)
        val all = UiKit.chipIcon(this, Ic.PLUS, tx("ضيف كل النصوص مرة وحدة", "Add all texts at once")) {
            val have = t.items.map { it.text }.toSet()
            for (x in TplDefaults.allItems()) if (x.text !in have) t.items.add(x)
            build()
            Toast.makeText(this, tx("انضافوا بأماكن افتراضية — عدّل أو احذف أي واحد", "Added at default places — edit or delete any"), Toast.LENGTH_SHORT).show()
        }
        it0.addView(all, UiKit.full(this, all, 8))
        row.addView(UiKit.chipIcon(this, Ic.PLUS, tx("ضيف نص", "Add text")) { t.items.add(TplItem()); itemSheet(t, t.items.size - 1) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(UiKit.chip(this, tx("ضيف شعار (صورة)", "Add a logo (image)")) { t.items.add(TplItem().apply { kind = "image"; pos = "tr"; bg = false }); pickFor = "${t.items.size - 1}"; pickImage.launch(arrayOf("image/*")) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(UiKit.chip(this, tx("نصوصك", "Your texts")) { TemplateActivity.brandSheet(this) { demo.brand = Brand.values(this) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        it0.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun posName(p: String) = when (p) { "tl" -> tx("فوق يسار", "top left"); "tc" -> tx("فوق بالنص", "top centre"); "tr" -> tx("فوق يمين", "top right"); "bl" -> tx("تحت يسار", "bottom left"); "bc" -> tx("تحت بالنص", "bottom centre"); else -> tx("تحت يمين", "bottom right") }

    private fun itemSheet(t: PodTemplate, i: Int) {
        val x = t.items.getOrNull(i) ?: return
        UiKit.sheet(this, tx("نص ثابت", "Fixed text")) { col, d ->
            val et = EditText(this).apply { setText(x.text); setTextColor(Color.parseColor(UiKit.TEXT)); textDirection = View.TEXT_DIRECTION_FIRST_STRONG; hint = "{link}" }
            if (x.kind == "image") {
                val pick = UiKit.chip(this, if (x.image.isBlank()) tx("اختار صورة الشعار", "Pick the logo image") else tx("غيّر صورة الشعار", "Change the logo image")) { pickFor = "$i"; d.dismiss(); pickImage.launch(arrayOf("image/*")) }
                col.addView(pick, UiKit.full(this, pick, 6))
                col.addView(UiKit.text(this, tx("الأفضل PNG بخلفية شفافة.", "A PNG with a transparent background works best."), 12.5f, UiKit.SUB))
            } else {
                col.addView(et)
                val chosen = Brand.KEYS.firstOrNull { x.text == "{$it}" } ?: ""
                var sel = chosen
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                fun drawKeys() {
                    box.removeAllViews()
                    for (chunk in Brand.KEYS.chunked(3)) {
                        val rr = LinearLayout(this)
                        for (k in chunk) rr.addView(UiKit.chip(this, Brand.label(k).substringBefore(" ("), k == sel) { sel = k; et.setText("{$k}"); drawKeys() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
                        repeat(3 - chunk.size) { rr.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                        box.addView(rr)
                    }
                }
                drawKeys(); col.addView(box)
                col.addView(UiKit.text(this, tx("اختار وحدة من «نصوصك» (بتبدّل النص)، أو اكتب نص عادي.", "Pick one of “Your texts” (replaces the text), or type plain text."), 12.5f, UiKit.SUB))
            }
            fun group(title: String, items: List<Pair<String, String>>, cur: String, set: (String) -> Unit) {
                col.addView(UiKit.text(this, title, 14f, UiKit.TEXT, true).apply { setPadding(0, dp(10), 0, dp(2)) })
                var sel = cur
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                fun draw() {
                    box.removeAllViews()
                    for (chunk in items.chunked(3)) {
                        val r = LinearLayout(this)
                        for ((k, n) in chunk) r.addView(UiKit.chip(this, n, k == sel) { sel = k; set(k); draw() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
                        repeat(3 - chunk.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                        box.addView(r)
                    }
                }
                draw(); col.addView(box)
            }
            col.addView(UiKit.text(this, tx("المكان: اضغط وين بدك إياه", "Position: tap where you want it"), 14f, UiKit.TEXT, true).apply { setPadding(0, dp(10), 0, dp(2)) })
            col.addView(PosPad(this, demo.landscape, x.x, x.y) { fx, fy -> x.x = fx; x.y = fy; demo.invalidate() })
            col.addView(UiKit.text(this, tx("أو بـ«الطبقات» فوق: اسحبه بإصبعك وكبّره بإصبعين.", "Or in “Layers” above: drag it and pinch to resize."), 12.5f, UiKit.SUB))
            if (x.kind != "image") {
                group(tx("الخط", "Font"), listOf("" to tx("خط القالب", "Template font")) + VidFonts.list.map { it.id to it.label }, x.font) { x.font = it }
                group(tx("طريقة الظهور", "How it appears"), listOf("fade" to tx("تلاشي", "Fade"), "type" to tx("كيبورد", "Typing"), "slide" to tx("انزلاق", "Slide"), "none" to tx("بدون حركة", "No motion")), x.anim) { x.anim = it }
            } else group(tx("طريقة الظهور", "How it appears"), listOf("fade" to tx("تلاشي", "Fade"), "slide" to tx("انزلاق", "Slide"), "none" to tx("بدون حركة", "No motion")), x.anim) { x.anim = it }
            group(tx("إمتى بيظهر", "When"), listOf("always" to tx("طول الوقت", "Always"), "start" to tx("بالبداية", "At the start"), "end" to tx("بالآخر", "At the end"), "every" to tx("كل فترة", "Every while")), x.whenShow) { x.whenShow = it }
            group(tx("كم ثانية (للبداية/الآخر/كل فترة)", "Seconds (start/end/every)"), listOf("3" to "3", "5" to "5", "8" to "8", "12" to "12"), x.secs.toInt().toString()) { x.secs = it.toFloat() }
            group(tx("كل كم دقيقة (لـ «كل فترة»)", "Every how many minutes"), listOf("1" to "1", "2" to "2", "3" to "3", "5" to "5", "10" to "10"), x.everyMin.toInt().toString()) { x.everyMin = it.toFloat() }
            slider(col, tx("الحجم", "Size"), 0.4f, if (x.kind == "image") 6f else 3f, x.size, { "${(it * 100).toInt()}%" }) { x.size = (it * 20).toInt() / 20f; demo.invalidate() }
            if (x.kind != "image") group(tx("خلفية", "Background"), listOf("1" to tx("مع خلفية", "With background"), "0" to tx("بدون", "None")), if (x.bg) "1" else "0") { x.bg = it == "1" }
            col.addView(UiKit.text(this, tx("اللون", "Colour"), 14f, UiKit.TEXT, true).apply { setPadding(0, dp(10), 0, dp(2)) })
            val sw = LinearLayout(this); col.addView(sw)
            fun drawSw() {
                sw.removeAllViews()
                for (c in listOf(0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt(), 0xFF3D8BFF.toInt(), 0xFF32D74B.toInt(), 0xFFFF453A.toInt())) sw.addView(View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(c); setStroke(dp(if (c == x.color) 3 else 1), Color.parseColor(if (c == x.color) UiKit.BLUE else UiKit.LINE)) }
                    setOnClickListener { x.color = c; drawSw() }
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0, dp(6), dp(10), 0) })
            }
            drawSw()
            val ok = UiKit.primary(this, tx("تمام", "Done")) { x.text = et.text.toString().ifBlank { "{link}" }; d.dismiss(); build() }
            col.addView(ok, UiKit.full(this, ok, 14))
            d.setOnDismissListener { x.text = et.text.toString().ifBlank { "{link}" }; build() }
        }
    }

    private fun build() {
        lastSig = "${t.pipPos}${(t.pipSize * 100).toInt()}"
        opts.removeAllViews()
        val nameEt = EditText(this).apply {
            setText(t.name); setSingleLine(); textSize = 17f; setTextColor(Color.parseColor(UiKit.TEXT)); hint = tx("اسم القالب", "Template name")
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { t.name = e?.toString()?.ifBlank { tx("قالبي", "My template") } ?: t.name }
            })
        }
        opts.addView(UiKit.text(this, tx("اسم القالب", "Template name"), 13f, UiKit.SUB).apply { setPadding(0, dp(10), 0, 0) })
        opts.addView(nameEt)

        opts.addView(UiKit.text(this, tx("عدّل براحتك — لما تحفظ بتشوف شو تغيّر وبتختار: على هالقالب، قالب جديد، أو تلغي.", "Edit freely — when you save you'll see what changed and choose: this template, a new one, or discard."), 12.5f, UiKit.SUB).apply { setPadding(0, dp(8), 0, 0) })
        tabBar()

        // ── الإيقاع
        // ── المونتاج: منهج واحد، ٦ خيارات بس. كل خيار بقانون واحد واضح، وما في خيار بيلغي ثاني.
        val p = sec("tpl_pace", tx("المونتاج", "The edit"), when (t.intensity) { "calm" -> tx("هادئ", "Calm"); "lively" -> tx("سريع", "Fast"); else -> tx("متوازن", "Balanced") })
        label(p, tx("١ · سرعة تقليب الكاميرا", "1 · How fast the camera switches"))
        chips(p, listOf("calm" to tx("هادئ\nبعد ١٫٨ ث حكي", "Calm\nafter 1.8 s"), "balanced" to tx("متوازن\nبعد ١٫٢ ث", "Balanced\nafter 1.2 s"), "lively" to tx("سريع\nبعد ٠٫٨ ث", "Fast\nafter 0.8 s")), t.intensity) { t.intensity = it }
        note(p, tx("الكاميرا بتروح على اللي صار الحكي معه: حكى أكثر من نص آخر لحظات والباقي قليل. القطع على بداية كلامه. أقل لقطة: هادئ ٢٫٥ث، متوازن ١٫٨ث، سريع ١٫٢ث. شخصين على نفس الكاميرا: ما في تقليب بينهم.",
            "The camera goes to whoever has the floor: talked more than half of the last moments while the rest were quiet. The cut lands at the start of their speech. Shortest shot: calm 2.5 s, balanced 1.8 s, fast 1.2 s. Two people on one camera: no switching between them."))
        label(p, tx("٢ · لما اثنين يحكوا مع بعض", "2 · When two talk at once"))
        val ovNow = when (t.overlap) { "both", "split" -> "both"; "wide" -> "wide"; "switch" -> "switch"; else -> "stay" }
        chips(p, listOf("stay" to tx("بتضل على اللي كان", "Stay on who was on"), "switch" to tx("بتنتقل على اللي دخل", "Go to the one who came in"),
            "both" to tx("الاثنين مع بعض (تقسيم)", "Both together (split)"), "wide" to tx("الكاميرا العامة", "The wide camera")), ovNow) { t.overlap = it }
        if (ovNow != "stay") {
            note(p, tx("بعد قديش من حكيهم مع بعض:", "After how long of talking together:"))
            chips(p, listOf("0" to tx("فورًا", "Right away"), "1" to tx("١ ث", "1 s"), "2" to tx("٢ ث", "2 s"), "3" to tx("٣ ث", "3 s"), "5" to tx("٥ ث", "5 s")),
                when { t.ovAfter < 0.5f -> "0"; t.ovAfter < 1.5f -> "1"; t.ovAfter < 2.5f -> "2"; t.ovAfter < 4f -> "3"; else -> "5" }) { t.ovAfter = it.toFloat() }
        }
        val after = if (ovNow == "stay" || t.ovAfter < 0.5f) "" else tx(" (بعد ${t.ovAfter.toInt()} ث من حكيهم مع بعض؛ قبلها بتضل على اللي كان)", " (after ${t.ovAfter.toInt()} s of talking together; until then it stays)")
        note(p, when (ovNow) {
            "both" -> tx("الشاشة بتنقسم للكاميرتين اللي بيحكوا بس (شكل التقسيم بتبويب الشكل)", "The screen splits for the two cameras that are talking only (split look in the Look tab)") + after + tx(". لما واحد يسكت بترجع عليه لحاله.", ". When one stops, it goes back to a single camera.")
            "switch" -> tx("الكاميرا بتروح على اللي قاطع/دخل على الحكي وبتضل عليه — مرة وحدة لكل نقاش، ما بتقلب رايح جاي", "The camera goes to the one who cut in and stays — once per exchange, no back-and-forth") + after + "."
            "wide" -> tx("الكاميرا العامة إذا موجودة بالتصوير، وإذا مش موجودة: الاثنين مع بعض", "The wide camera if there is one in the shoot; otherwise both together") + after + "."
            else -> tx("ما في تقسيم أبدًا. الكاميرا بتضل على اللي كان بالشاشة لحد ما واحد منهم ياخد الدور لحاله.", "Never splits. The camera stays on whoever was on screen until one of them clearly takes the floor.")
        })
        label(p, tx("٣ · التعليقات القصيرة (آه، صح، ضحكة)", "3 · Short comments (uh-huh, right, a laugh)"))
        note(p, tx("ما بتقلب الكاميرا عليها (ما في مربعات عائمة بالبرنامج).", "They don't move the camera (no floating boxes in the app)."))
        label(p, tx("٤ · الكادر", "4 · Framing"))
        chips(p, listOf("0" to tx("الكاميرا زي ما صورت (ثابت)", "As filmed (steady)"), "1" to tx("بيلحق الوجوه ويقرّب", "Follows faces and pushes in")), if (t.follow) "1" else "0") { t.follow = it == "1"; if (it == "1") t.faces = true }
        note(p, tx("«زي ما صورت»: ولا زوم ولا لحاق راس ولا تقريب، الصورة بتعبّي الشاشة متل ما هي. «بيلحق الوجوه»: الكادر بيتحرك مع الوجوه، وبيقرّب شوي شوي على اللي بيحكي بالكاميرا اللي فيها شخصين.", "“As filmed”: no zoom, no head-following, no push-in; the picture fills the screen as it is. “Follows faces”: the frame moves with faces and slowly pushes in on the talker when a camera has two people."))
        label(p, tx("٥ · كسر الملل بالكلام الطويل", "5 · Break monotony in long talk"))
        chips(p, listOf("0" to tx("مطفي", "Off"), "30" to tx("كل ٣٠ ث", "Every 30 s"), "60" to tx("كل دقيقة", "Every minute"), "90" to tx("كل دقيقة ونص", "Every 1.5 min")), when { t.breakEvery <= 0 -> "0"; t.breakEvery < 45 -> "30"; t.breakEvery < 75 -> "60"; else -> "90" }) { t.breakEvery = it.toInt() }
        if (t.breakEvery > 0) {
            note(p, tx("مدة لقطة كسر الملل:", "Length of the cutaway:"))
            chips(p, listOf("1.5" to tx("١٫٥ ث", "1.5 s"), "2.5" to tx("٢٫٥ ث", "2.5 s"), "4" to tx("٤ ث", "4 s"), "6" to tx("٦ ث", "6 s")),
                when { t.breakLen < 2f -> "1.5"; t.breakLen < 3.2f -> "2.5"; t.breakLen < 5f -> "4"; else -> "6" }) { t.breakLen = it.toFloat() }
        }
        note(p, tx("لقطة ${String.format(Locale.US, "%.1f", t.breakLen)} ث على المستمع وهو بيسمع، بس لما حدا بيحكي متواصل أطول من المدة اللي اخترتها.", "A ${String.format(Locale.US, "%.1f", t.breakLen)} s shot of the listener listening, only when someone talks non-stop longer than the interval you chose."))
        label(p, tx("٦ · أول ظهور ببداية الفيديو", "6 · First appearance at the start"))
        slider(p, tx("الكاميرا بتضل عليه (عشان اسمه ينقرا)", "Camera stays on them (so the name reads)"), 0f, 10f, t.firstHold, { if (it < 0.25f) tx("بدون", "Off") else String.format(Locale.US, "%.1f ث", it) }) { t.firstHold = (it * 2).toInt() / 2f }
        label(p, tx("اتجاه الفيديو", "Video orientation"))
        chips(p, listOf("auto" to tx("حسب الكاميرات", "By cameras"), "h" to "16:9", "v" to "9:16", "both" to tx("الاثنين", "Both")), t.orient) { t.orient = it }

        // ── كل الانتقالات بمكان واحد
        val tr = sec("tpl_trans", tx("الانتقالات", "Transitions"), when (t.trans) { "fade" -> tx("تلاشي", "Fade"); "flash" -> tx("ومضة", "Flash"); "zoom" -> tx("تكبير", "Zoom"); else -> tx("قطع", "Cut") })
        label(tr, tx("بين لقطة ولقطة", "Between shots"))
        chips(tr, listOf("cut" to tx("قطع مباشر", "Hard cut"), "fade" to tx("تلاشي ناعم", "Soft fade"), "flash" to tx("ومضة بيضا", "White flash"), "zoom" to tx("تكبير خفيف", "Light zoom-in")), if (t.trans == "cut" && t.fade) "fade" else t.trans) { t.trans = it; t.fade = it == "fade" }
        if (t.trans == "fade" || t.fade) chips(tr, listOf("200" to "0.2 ث", "300" to "0.3 ث", "500" to "0.5 ث", "800" to "0.8 ث"), t.fadeMs.toString()) { t.fadeMs = it.toInt() }
        if (t.pip) {
            label(tr, tx("المربع العائم لما يطلع", "Floating box appearing"))
            chips(tr, listOf("cut" to tx("مباشر", "Instant"), "fade" to tx("تلاشي", "Fade"), "slide" to tx("انزلاق من الجنب", "Slide in"), "zoom" to tx("تكبير", "Zoom in")), t.pipAnim) { t.pipAnim = it }
            if (t.pipAnim != "cut") chips(tr, listOf("150" to tx("سريع", "Fast"), "250" to tx("عادي", "Normal"), "450" to tx("بطيء", "Slow")), t.pipAnimMs.toString()) { t.pipAnimMs = it.toInt() }
        }
        note(tr, tx("الانتقال بين الإنترو والحلقة وبين الحلقة والأوترو: بصفحة المونتاج ← ٣ التصدير.", "Intro → episode and episode → outro transitions: in the editor → 3 Export."))

        // ── شكل خاص كل فترة (مش كسر ملل: كل كم دقيقة، كم لقطة بشكل مختلف، وبعدين بيرجع العادي)
        val lk = sec("tpl_look", tx("شكل خاص كل فترة", "A special look every so often"), if (t.lookEvery <= 0f) tx("مطفي", "Off") else tx("كل ${t.lookEvery.toInt()} د", "every ${t.lookEvery.toInt()} min"))
        chips(lk, listOf("0" to tx("مطفي", "Off"), "10" to tx("كل ١٠ د", "Every 10 min"), "15" to tx("كل ١٥ د", "Every 15 min"), "20" to tx("كل ٢٠ د", "Every 20 min"), "30" to tx("كل ٣٠ د", "Every 30 min"), "5" to tx("كل ٥ د", "Every 5 min")), t.lookEvery.toInt().toString()) { t.lookEvery = it.toFloat() }
        if (t.lookEvery > 0f) {
            label(lk, tx("الشكل", "The look"))
            chips(lk, listOf("raw" to tx("الكاميرا زي ما هي بالضبط", "The camera exactly as it is"), "crop" to tx("القص اللي حددته", "The crop you set")), t.lookMode) { t.lookMode = it }
            if (t.lookMode == "crop") {
                // القص لكل كاميرا: بنفتح محرر القص على آخر حلقة فتحتها بالمونتاج
                val last = getSharedPreferences("reaction_ui", MODE_PRIVATE).getString("lastEditSid", "") ?: ""
                if (last.isNotEmpty() && SessionStore.load(this, last) != null) {
                    val b = UiKit.primary(this, "✂  " + tx("حدد القص (لكاميرات آخر حلقة)", "Set the crop (cameras of your last episode)")) {
                        startActivity(Intent(this, SourceEditActivity::class.java).putExtra("session", last).putExtra("vertical", t.kind == "reel").putExtra("look", true))
                    }
                    lk.addView(b, UiKit.full(this, b, 8, 52))
                } else note(lk, tx("القص بينحدد لكل كاميرا بحلقة: افتح الحلقة بالمونتاج، وبعدين ارجع هون (أو من المصادر ← قص الشكل الخاص).", "The crop is set per camera of an episode: open the episode in the editor, then come back here (or Sources → special crop)."))
            }
            label(lk, tx("كم مرة كل كاميرا", "How many times each camera"))
            chips(lk, listOf("1" to tx("مرة مرة", "Once each"), "2" to tx("مرتين مرتين", "Twice each"), "3" to tx("٣ مرات", "3 times each")), t.lookShots.toString()) { t.lookShots = it.toInt() }
            note(lk, (if (t.lookMode == "crop") tx("القص بتحدده لكل كاميرا من صفحة المونتاج ← المصادر ← «قص الشكل الخاص». كاميرا ما إلها قص بتطلع زي ما هي. ", "Set the crop per camera in the editor → Sources → “Special look crop”. A camera without one shows as it is. ") else "") +
                tx("الكاميرا بتضل تلحق اللي بيحكي عادي — بس الشكل بيتغيّر لحد ما كل كاميرا تطلع العدد اللي اخترته (أو دقيقتين كحد أقصى)، وبعدين بيرجع العادي لحد الفترة الجاية.", "The camera keeps following the talker as usual — only the look changes until each camera has shown the number you chose (2 minutes at most), then it's back to normal until the next period."))
        }

        // ── المربع العائم
        val b = if (!t.pip) LinearLayout(this) else sec("tpl_pip", tx("المربع العائم", "Floating box"), if (t.pip) TemplateActivity.summary(t).substringAfter(" · ") else tx("معطّل", "Off"), openByDefault = true)
        if (t.pip) {
            label(b, tx("المكان", "Position"))
            chips(b, listOf("tl" to tx("فوق يسار", "Top left"), "tc" to tx("فوق بالنص", "Top centre"), "tr" to tx("فوق يمين", "Top right"), "bl" to tx("تحت يسار", "Bottom left"), "bc" to tx("تحت بالنص", "Bottom centre"), "br" to tx("تحت يمين", "Bottom right"), "custom" to tx("حر (اسحبه)", "Free (drag)")), t.pipPos) { t.pipPos = it }
            note(b, tx("حر بالكامل: اضغط «حرّك المربع» واسحبه لأي مكان، وكبّره بإصبعين. الكتابة هي اللي بتبعد عنه وعن الوجوه.", "Fully free: tap “Move the box”, drag it anywhere and pinch to resize. Captions move away from it and from faces.") +
                if (t.kind == "reel") tx(" الأحمر الخفيف = أماكن بتغطيها أزرار المنصة (تنبيه بس).", " Light red = areas covered by platform buttons (a warning only).") else "")
            note(b, tx("«حر»: اضغط «حرّك المربع» فوق واسحبه، وكبّره أو صغّره بإصبعين.", "“Free”: tap “Move the box” above and drag it; pinch to resize."))
            label(b, tx("مكانه بين اللقطات", "Placement between shots"))
            chips(b, listOf("fixed" to tx("نفس المكان", "Same place"), "person" to tx("حسب الشخص", "By person"), "alternate" to tx("يمين/يسار بالتناوب", "Alternate sides")), t.pipPlace) { t.pipPlace = it }
            note(b, when (t.pipPlace) { "person" -> tx("شخص ١ و٣ بمكانك المختار، وشخص ٢ و٤ بالجهة المقابلة.", "Persons 1 and 3 at your spot, 2 and 4 on the opposite side."); "alternate" -> tx("كل مربع جديد بيطلع بالجهة المقابلة للي قبله.", "Each new box appears on the side opposite the previous one."); else -> tx("دايمًا بنفس المكان.", "Always in the same place.") })
            slider(b, tx("الحجم", "Size"), 0.12f, 0.8f, t.pipSize, ::pct) { t.pipSize = it }
            if (!t.pipFace && t.pipShape != "circle") {
                label(b, tx("شكل المربع", "Box shape"))
                chips(b, listOf("auto" to tx("تلقائي", "Auto"), "square" to tx("مربع", "Square"), "wide" to tx("عرضي خفيف", "Slightly wide"), "169" to "16:9", "tall" to tx("طولي", "Tall")), t.pipAspect) { t.pipAspect = it }
                note(b, tx("تلقائي: مربع بالريل، و16:9 بالحلقة. جوّا المربع الصورة دايمًا معبّية ومقصوصة على الوجه.", "Auto: square in reels, 16:9 in episodes. The picture inside always fills the box, cropped on the face."))
            }
            label(b, tx("الشكل", "Shape"))
            chips(b, listOf("rect" to tx("مستطيل", "Rectangle"), "rounded" to tx("زوايا دائرية", "Rounded"), "circle" to tx("دائرة", "Circle"), "alternate" to tx("دائرة/مربع بالتناوب", "Circle/square alternating")), t.pipShape) { t.pipShape = it }
            if (t.pipShape == "rounded" || t.pipShape == "alternate") slider(b, tx("استدارة الزوايا", "Corner roundness"), 0f, 0.5f, t.pipRadius, ::pct) { t.pipRadius = it }
            label(b, tx("الإطار", "Border"))
            chips(b, listOf("0" to tx("بدون", "None"), "0.004" to tx("رفيع", "Thin"), "0.01" to tx("عريض", "Thick")), when { t.pipBorder <= 0f -> "0"; t.pipBorder < 0.007f -> "0.004"; else -> "0.01" }) { t.pipBorder = it.toFloat() }
            if (t.pipBorder > 0f) swatches(b, palette, t.pipBorderColor) { t.pipBorderColor = it }
            toggle(b, t.pipShadow, tx("ظل خفيف: نعم", "Soft shadow: yes"), tx("ظل خفيف: لا", "Soft shadow: no")) { t.pipShadow = it }
            note(b, tx("طريقة ظهوره: بتبويب «المونتاج» ← الانتقالات.", "How it appears: in “Pacing” → Transitions."))
            if (t.pipPos != "custom") slider(b, tx("البعد عن الحواف", "Distance from edges"), 0f, 0.1f, t.pipMargin, ::pct) { t.pipMargin = it }
            label(b, tx("على الوجه", "On the face"))
            toggle(b, t.pipFace, tx("المربع مقرّب على وجه الشخص: نعم", "Box zoomed on the face: yes"), tx("المربع مقرّب على وجه الشخص: لا", "Box zoomed on the face: no")) { t.pipFace = it }
            if (t.pipFace) {
                chips(b, listOf("1.3" to tx("خفيف (الوجه ٣٥٪)", "Light (face 35%)"), "1.6" to tx("متوسط (٤٥٪)", "Medium (45%)"), "2.2" to tx("قريب (٦٠٪)", "Close (60%)")), String.format(Locale.US, "%.1f", t.faceZoom)) { t.faceZoom = it.toFloat() }
                note(b, tx("الزوم بيتغير لحاله حسب قرب الشخص من الكاميرا: قريب = زوم أقل، بعيد = أكثر.", "Zoom adapts to how close the person is: closer = less zoom, farther = more."))
                if (t.pipShape != "circle") chips(b, listOf("square" to tx("مربع", "Square"), "portrait" to tx("طولي", "Portrait")), t.faceAspect) { t.faceAspect = it }
            }
            toggle(b, t.avoidFace, tx("ما يغطي وجه اللي بيحكي: نعم", "Never cover the speaker's face: yes"), tx("ما يغطي وجه اللي بيحكي: لا", "Never cover the speaker's face: no")) { t.avoidFace = it }
            if (t.pipFace || t.avoidFace) note(b, tx("بيحتاج تحليل الوجوه — بيصير تلقائيًا أول مرة بتعمل معاينة أو تصدير (بياخد شوي وقت). إذا كان مكان المربع فوق الوجه، بينتقل للجهة الثانية وبيبعد عن الحافة.", "Needs face analysis — done automatically on the first preview or export (takes a little time). If the box would sit on the face, it moves to the other side, away from the edge."))
        }

        // ── لما يحكوا مع بعض
        val m = sec("tpl_multi", tx("شكل التقسيم", "Split look"), if (t.overlap !in setOf("both", "split", "wide")) tx("مطفي", "Off") else if (t.multi == "boxes") tx("مربعات فوق خلفية", "Boxes over a background") else tx("شاشة مقسومة", "Split screen"))
        run {
        chips(m, listOf("split" to tx("شاشة مقسومة", "Split screen"), "boxes" to tx("مربعات فوق خلفية", "Boxes on a background"), "people" to tx("كل شخص بمربع", "Each person in a box")), t.multi) { t.multi = it }
        if (t.multi == "people") note(m, tx("كاميرا فيها شخصين بتنقسم لمربعين (واحد لكل شخص)، فـ٤ أشخاص بكاميرتين = ٤ مربعات. إذا الشخصين لازقين ببعض بيضلّوا بمربع واحد.", "A camera with two people splits into two boxes (one each), so 4 people on 2 cameras = 4 boxes. If they sit too close they stay in one box."))
        label(m, tx("الترتيب", "Arrangement"))
        chips(m, listOf("auto" to tx("تلقائي", "Auto"), "side" to tx("جنب بعض", "Side by side"), "stack" to tx("فوق بعض", "Stacked")), t.splitDir) { t.splitDir = it }
        label(m, tx("الأحجام (٣ أشخاص وأكثر)", "Sizes (3+ people)"))
        chips(m, listOf("0" to tx("الكل نفس الحجم", "All the same size"), "1" to tx("الأول أكبر (أساسي)", "First is bigger (main)")), if (t.multiMain) "1" else "0") { t.multiMain = it == "1" }
        note(m, tx("كل شخص بخانة ما بتنمغط لدرجة تقصه (بين 3:4 و 4:3) وبأكبر حجم ممكن — والفراغ خلفية. بالطول بيصيروا فوق بعض لحالهم.", "Each person gets a box that never stretches enough to cut them (3:4 to 4:3), as big as possible — the rest is background. In portrait they stack by themselves."))
        if (t.multi == "split") {
            label(m, tx("الفاصل", "Divider"))
            chips(m, listOf("0" to tx("بدون", "None"), "0.004" to tx("رفيع", "Thin"), "0.012" to tx("عريض", "Wide")), when { t.gap <= 0f -> "0"; t.gap < 0.008f -> "0.004"; else -> "0.012" }) { t.gap = it.toFloat() }
            if (t.gap > 0f) swatches(m, palette, t.gapColor) { t.gapColor = it }
        } else {
            label(m, tx("لون الخلفية", "Background colour"))
            swatches(m, listOf(0xFF1B1D24.toInt(), 0xFF0B3D91.toInt(), 0xFF5B2C83.toInt(), 0xFF1E5631.toInt(), 0xFF7A1F1F.toInt(), 0xFFEDEDED.toInt()), t.bgColor) { t.bgColor = it }
            slider(m, tx("استدارة المربعات", "Box roundness"), 0f, 0.1f, t.boxRadius, ::pct) { t.boxRadius = it }
        }
        label(m, tx("صورة خلفية (بدل اللون)", "Background image (instead of colour)"))
        val bgr = LinearLayout(this)
        bgr.addView(UiKit.chip(this, if (t.bgImage.isBlank()) tx("اختار صورة", "Pick an image") else tx("غيّر الصورة", "Change image")) { pickFor = "bg"; pickImage.launch(arrayOf("image/*")) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        if (t.bgImage.isNotBlank()) bgr.addView(UiKit.chip(this, tx("شيل الصورة", "Remove image")) { t.bgImage = ""; if (t.fitBg == "image") t.fitBg = "blur"; build() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        m.addView(bgr)
        label(m, tx("لما الصورة ما بتعبّي إطارها (مثلًا كاميرا بالطول بفيديو بالعرض)", "When a picture doesn't fill its frame (e.g. a portrait camera in a landscape video)"))
        chips(m, listOf("blur" to tx("نفسها مموّهة", "Blurred copy"), "color" to tx("لون الخلفية", "Background colour"), "image" to tx("صورة الخلفية", "Background image")), t.fitBg) { t.fitBg = it; if (it == "image" && t.bgImage.isBlank()) { pickFor = "bg"; pickImage.launch(arrayOf("image/*")) } }

        }

        // ── اللي بيحكي
        val g = sec("tpl_glow", tx("إطار حول اللي بيحكي", "Frame around the speaker"), if (t.glow) tx("شغّال", "On") else tx("مطفي", "Off"))
        toggle(g, t.glow, tx("إطار مضيء: نعم", "Glow: yes"), tx("إطار مضيء: لا", "Glow: no")) { t.glow = it }
        if (t.glow) {
            swatches(g, palette, t.glowColor) { t.glowColor = it }
            chips(g, listOf("0.01" to tx("رفيع", "Thin"), "0.018" to tx("متوسط", "Medium"), "0.03" to tx("عريض", "Wide")), when { t.glowWidth < 0.014f -> "0.01"; t.glowWidth < 0.024f -> "0.018"; else -> "0.03" }) { t.glowWidth = it.toFloat() }
        }

        // ── الأسماء
        val n = sec("tpl_names", tx("أسماء الأشخاص على الفيديو", "Names on the video"), when (t.names) { "first" -> tx("أول مرة", "First time"); "switch" -> tx("كل ما يظهر", "Every time"); else -> tx("بدون", "None") })
        n.addView(UiKit.text(this, NameStyle.summary(t), 13f, UiKit.SUB))
        val nb = UiKit.primary(this, tx("✎ شكل الاسم (الخط، الحجم، الخلفية، الحركة، المكان)", "✎ Name style (font, size, background, motion, spot)")) { nameSheet() }
        n.addView(nb, UiKit.full(this, nb, 8))

        // ── الثابتين
        val pn = sec("tpl_pin", tx("الأشخاص الثابتين", "Always-visible people"), tx("بتختارهم من المونتاج", "Chosen in the editor"))
        note(pn, tx("لما تثبّت شخص بالمونتاج، بيظهر بمربع صغير لما ما يكون باللقطة.", "When you pin a person in the editor, they show in a small box when they're not in the shot."))
        chips(pn, listOf("tl" to tx("فوق يسار", "Top left"), "tr" to tx("فوق يمين", "Top right"), "bl" to tx("تحت يسار", "Bottom left"), "br" to tx("تحت يمين", "Bottom right")), t.pinPos) { t.pinPos = it }
        slider(pn, tx("الحجم", "Size"), 0.12f, 0.35f, t.pinSize, ::pct) { t.pinSize = it }
        chips(pn, listOf("rect" to tx("مستطيل", "Rectangle"), "rounded" to tx("زوايا دائرية", "Rounded"), "circle" to tx("دائرة", "Circle")), t.pinShape) { t.pinShape = it }

        // ── باقي المونتاج
        val e = sec("tpl_edit", tx("باقي إعدادات المونتاج", "Other edit settings"), tx("الكادر، الزوم، الوجه", "Framing, zoom, face") + if (t.kind == "reel") tx("، الصمت", ", silence") else "")
        label(e, tx("الكاميرا داخل الإطار", "Camera inside the frame"))
        chips(e, listOf("smart" to tx("ذكي", "Smart"), "fit" to tx("الصورة كاملة", "Whole"), "fill" to tx("ملء مع قص", "Fill")), t.frame) { t.frame = it }
        label(e, tx("لما ما في فيديو لشخص", "When a person has no video"))
        chips(e, listOf("other" to tx("كاميرا ثانية", "Other camera"), "freeze" to tx("آخر صورة", "Last frame"), "card" to tx("بطاقة باسمه", "Name card")), t.fill) { t.fill = it }
        label(e, tx("لحظة قلب الكاميرا", "When a camera flips"))
        chips(e, listOf("freeze" to tx("آخر صورة", "Last frame"), "other" to tx("كاميرا ثانية", "Other camera"), "blur" to tx("تمويه", "Blur")), t.flipFill) { t.flipFill = it }
        if (t.kind == "reel") {
            label(e, tx("قص الصمت", "Silence cuts"))
            chips(e, listOf("off" to tx("مطفي", "Off"), "light" to tx("خفيف", "Light"), "balanced" to tx("متوازن", "Balanced"), "intense" to tx("مكثّف", "Intense")), t.silence) { t.silence = it }
        }
        motionSection(t)

        // ── الكتابة
        val c = sec("tpl_caps", tx("الكتابة على الفيديو", "Captions"), tx("بتشتغل إذا في تفريغ للكلام", "Works when there's a transcript"))
        toggle(c, t.captions, tx("الكتابة: مفعّلة", "Captions: on"), tx("الكتابة: مطفية", "Captions: off")) { t.captions = it }
        if (t.captions) toggle(c, t.highlight, tx("تمييز الكلمات المهمة: نعم", "Highlight key words: yes"), tx("تمييز الكلمات المهمة: لا", "Highlight key words: no")) { t.highlight = it }
        chips(c, listOf("top" to tx("فوق", "Top"), "middle" to tx("بالنص", "Middle"), "bottom" to tx("تحت", "Bottom")), t.capPos) { t.capPos = it }
        chips(c, listOf("0.85" to tx("صغير", "Small"), "1.0" to tx("عادي", "Normal"), "1.2" to tx("كبير", "Large")), String.format(Locale.US, "%.2f", t.capSize).let { if (it == "1.00") "1.0" else it.trimEnd('0') }) { t.capSize = it.toFloat() }
        swatches(c, listOf(0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt(), 0xFF3D8BFF.toInt(), 0xFF32D74B.toInt()), t.capColor) { t.capColor = it }
        label(c, tx("شكل الكتابة", "Caption style"))
        chips(c, listOf("line" to tx("سطر عادي", "Line"), "words" to tx("كلمات كبار (الحالية ملوّنة)", "Big words (current coloured)"), "boxed" to tx("كلمات على خلفية", "Words on a box")), t.capStyle) { t.capStyle = it }
        label(c, tx("لون التمييز", "Highlight colour"))
        swatches(c, listOf(0xFFFFD60A.toInt(), 0xFF32D74B.toInt(), 0xFF3D8BFF.toInt(), 0xFFFF453A.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFFFFF.toInt()), t.capHi) { t.capHi = it }
        itemsSection(t)
    }
}

/** شاشة صغيرة: اضغط وين بدك العنصر يكون (مركزه) — بأي مكان بالصورة */
class PosPad(ctx: android.content.Context, private val landscape: Boolean, x0: Float, y0: Float, private val onPick: (Float, Float) -> Unit) : View(ctx) {
    private var fx = x0; private var fy = y0
    private val pBg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2D35.toInt() }
    private val pLine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; strokeWidth = 2f }
    private val pDot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D8BFF.toInt() }
    private val pTxt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); textAlign = android.graphics.Paint.Align.CENTER; textSize = 30f }
    private val r = android.graphics.RectF()

    override fun onMeasure(w: Int, h: Int) {
        val ww = MeasureSpec.getSize(w)
        val hh = if (landscape) ww * 9 / 16 else (ww * 0.6f).toInt()
        setMeasuredDimension(ww, hh)
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val a = if (landscape) 16f / 9f else 9f / 16f
        val s = kotlin.math.min(width / a, height.toFloat())
        r.set((width - s * a) / 2f, (height - s) / 2f, (width + s * a) / 2f, (height + s) / 2f)
        c.drawRoundRect(r, 14f, 14f, pBg)
        for (k in 1..2) { c.drawLine(r.left + r.width() * k / 3f, r.top, r.left + r.width() * k / 3f, r.bottom, pLine); c.drawLine(r.left, r.top + r.height() * k / 3f, r.right, r.top + r.height() * k / 3f, pLine) }
        if (fx >= 0f && fy >= 0f) c.drawCircle(r.left + fx * r.width(), r.top + fy * r.height(), 16f, pDot)
        else c.drawText(tx("المكان الافتراضي — اضغط لتغيّره", "Default spot — tap to change"), r.centerX(), r.centerY(), pTxt)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        fx = ((e.x - r.left) / r.width()).coerceIn(0f, 1f); fy = ((e.y - r.top) / r.height()).coerceIn(0f, 1f)
        invalidate()
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_MOVE) onPick(fx, fy)
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }
}


/** أسماء إعدادات القالب وقيمها بالكلام العادي (لشاشة «شو تغيّر») */
object TplDiff {
    fun name(k: String): String? = when (k) {
        "name" -> tx("الاسم", "Name"); "cut" -> tx("طريقة المونتاج", "Edit mode"); "take" -> tx("الكاميرا بتنتقل بعد", "Camera moves after")
        "int" -> tx("الأسلوب", "Style"); "pip" -> tx("المربع العائم", "Floating box"); "ovl" -> tx("لما يحكوا مع بعض", "Talking together")
        "ovS" -> tx("بتقلب إذا الثاني حكى لحاله", "Switch when the other talks alone"); "ovM" -> tx("أطول لقطة وقت النقاش", "Longest shot in a debate")
        "multi" -> tx("شكل التقسيم", "Split look"); "zm" -> tx("الزوم التلقائي", "Auto zoom"); "push" -> tx("التقريب على اللي بيحكي", "Push-in on the talker")
        "punch" -> tx("التقريب على الكلمات", "Punch-in on words"); "trans" -> tx("الانتقال بين اللقطات", "Transition between shots"); "fade" -> tx("التلاشي", "Fade")
        "fadeMs" -> tx("مدة التلاشي", "Fade length"); "brk" -> tx("كسر الملل", "Break monotony"); "shM" -> tx("الردود القصيرة", "Short replies")
        "pSh" -> tx("نسبة المربع العائم", "Floating-box share"); "hold" -> tx("بتضل عليه بعد ما يسكت", "Hold after they stop"); "brg" -> tx("إذا رجع حكى خلال", "If they talk again within")
        "fH" -> tx("أول ظهور ببداية الفيديو", "First appearance"); "lkE" -> tx("شكل خاص كل", "Special look every"); "lkN" -> tx("مرات كل كاميرا (شكل خاص)", "Times per camera (special look)")
        "lkM" -> tx("الشكل الخاص", "Special look"); "names" -> tx("أسماء الأشخاص", "Names"); "nAn" -> tx("حركة الاسم", "Name motion"); "nSec" -> tx("مدة الاسم", "Name duration")
        "font" -> tx("الخط", "Font"); "title" -> tx("العنوان بأول الفيديو", "Opening title"); "cap" -> tx("الكتابة على الفيديو", "Captions"); "glow" -> tx("الإطار حول اللي بيحكي", "Speaker frame")
        "pSize" -> tx("حجم المربع العائم", "Floating-box size"); "pPos" -> tx("مكان المربع العائم", "Floating-box position"); "pShape" -> tx("شكل المربع العائم", "Floating-box shape")
        "pAnim" -> tx("ظهور المربع العائم", "Floating box appearing"); "orient" -> tx("الاتجاه", "Orientation"); "lay" -> tx("التخطيط", "Layout"); "items" -> tx("النصوص والشعار", "Texts and logo")
        "bg" -> tx("لون الخلفية", "Background colour"); "bgImg" -> tx("صورة الخلفية", "Background image"); "wa" -> tx("الكاميرا العامة", "Wide camera"); "frame" -> tx("الكادر", "Framing")
        else -> null
    }

    fun value(k: String, v: String): String = when {
        v == "true" -> tx("شغّال", "on"); v == "false" -> tx("مطفي", "off")
        k == "cut" -> if (v == "speaker") tx("كاميرا اللي بيحكي", "talker's camera") else tx("ذكي", "smart")
        k == "int" -> when (v) { "calm" -> tx("هادئ", "calm"); "lively" -> tx("حيوي", "lively"); else -> tx("متوازن", "balanced") }
        k == "ovl" -> when (v) { "split" -> tx("الشاشة بتنقسم", "split"); "stay" -> tx("بتضل على اللي كان", "stay"); "talker" -> tx("بتلحق اللي حكيه أكثر", "follow"); else -> tx("بتبدّل", "alternate") }
        k == "trans" -> when (v) { "fade" -> tx("تلاشي", "fade"); "flash" -> tx("ومضة", "flash"); "zoom" -> tx("تكبير", "zoom"); else -> tx("قطع", "cut") }
        k == "zm" -> if (v == "auto") tx("شغّال", "on") else tx("مطفي", "off")
        k == "punch" -> if (v == "off") tx("مطفي", "off") else tx("شغّال", "on")
        k == "lkM" -> if (v == "crop") tx("قصّك", "your crop") else tx("الكاميرا زي ما هي", "as is")
        k == "items" -> tx("تغيّرت", "changed")
        k == "pSh" -> "${((v.toDoubleOrNull() ?: 0.0) * 100).toInt()}%"
        k in setOf("take", "ovS", "ovM", "hold", "brg", "fH") -> (v.toDoubleOrNull()?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: v) + tx(" ث", " s")
        k == "lkE" -> if ((v.toDoubleOrNull() ?: 0.0) <= 0.0) tx("مطفي", "off") else "${v.toDouble().toInt()} " + tx("د", "min")
        k == "fadeMs" -> "${(v.toIntOrNull() ?: 0) / 1000.0} " + tx("ث", "s")
        k == "brk" -> if ((v.toIntOrNull() ?: 0) <= 0) tx("مطفي", "off") else tx("كل $v ث", "every $v s")
        v.length > 40 -> v.take(40) + "…"
        else -> v
    }
}
