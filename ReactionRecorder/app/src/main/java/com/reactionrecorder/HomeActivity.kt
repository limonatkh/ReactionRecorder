package com.reactionrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الشاشة الرئيسية بشريط تنقل سفلي: مشاريعي — تصوير — الإعدادات.
 * مشاريعي: كل تسجيل مشروع واحد فيه ملفاته ومونتاجه وريلزه وتصديره، وزر بيكمّل مرحلته الحالية.
 * نسخة غير مفعّلة: الانضمام والتفعيل بس.
 */
class HomeActivity : BaseActivity() {
    override val allowedWithoutLicense = true
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private val tabs = HashMap<String, View>()
    private val navItems = HashMap<String, TextView>()
    private var tab = "projects"
    private var builtFull: Boolean? = null

    // مشاريعي
    private lateinit var liveBar: TextView
    private lateinit var projList: LinearLayout
    private lateinit var projSub: TextView
    private lateinit var filterRow: LinearLayout
    private var filter = "all"
    private var sig = ""
    @Volatile private var cache: List<SessionInfo> = emptyList()
    @Volatile private var loading = false
    private var lastLoad = 0L
    private val thumbs = HashMap<String, Bitmap?>()

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val c = applicationContext
        Thread {
            val r = DeviceImport.import(c, uris)
            ui.post { AlertDialog.Builder(this).setMessage(r.message).setPositiveButton(tx("تم", "OK"), null).show(); lastLoad = 0L }
        }.start()
    }

    private fun dp(v: Int) = UiKit.dp(this, v)
    private val PHONE = "M9 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM11 18h2"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // بناء فحص الواجهة بس: جلسات تجريبية بتنحط بـ files/ux_import (من المحاكي)
        if (BuildConfig.UX_TEST) try {
            val inbox = java.io.File(filesDir, "ux_import")
            inbox.listFiles()?.forEach { d -> d.copyRecursively(java.io.File(SessionStore.root(this), d.name), overwrite = true); d.deleteRecursively() }
        } catch (_: Throwable) {}
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(UiKit.BG)) }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        nav = LinearLayout(this).apply { setBackgroundColor(Color.parseColor(UiKit.BG)) }
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#1E2025")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            content.setPadding(0, b.top, 0, 0); nav.setPadding(0, 0, 0, b.bottom); insets
        }
        tab = intent.getStringExtra("tab") ?: savedInstanceState?.getString("tab") ?: "projects"
        if (SatHub.hubWanted(this) && !SatHub.running) Thread { try { SatHub.start(applicationContext) } catch (_: Throwable) {} }.start()
        askPerms()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("tab")?.let { show(it) }
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("tab", tab) }

    override fun onResume() { super.onResume(); build(); lastLoad = 0L; ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (tab != "projects" && builtFull == true) show("projects") else super.onBackPressed() }

    // ───────── البناء حسب التفعيل ─────────

    private fun build() {
        val full = License.active(this)
        if (builtFull == full) { show(tab); return }
        builtFull = full
        content.removeAllViews(); tabs.clear(); nav.removeAllViews(); navItems.clear(); sig = ""
        if (!full) {
            nav.visibility = View.GONE
            content.addView(buildLocked())
            return
        }
        nav.visibility = View.VISIBLE
        tabs["projects"] = buildProjects()
        tabs["devices"] = buildDevices()
        tabs["settings"] = buildSettings()
        for (v in tabs.values) content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addNav("projects", Ic.FOLDER, tx("مشاريعي", "Projects"))
        addNav("devices", PHONE, tx("الأجهزة", "Devices"))
        addNav("templates", Ic.REELS, tx("القوالب", "Templates"))
        addNav("settings", Ic.GEAR, tx("الإعدادات", "Settings"))
        show(tab)
    }

    private fun addNav(key: String, path: String, label: String) {
        val tv = TextView(this).apply {
            text = label; textSize = 11.5f; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(6)); minHeight = dp(58)
            compoundDrawablePadding = dp(3)
            isClickable = true
            setOnClickListener { if (key == "templates") startActivity(Intent(this@HomeActivity, TemplateActivity::class.java)) else show(key) }
            tag = path
        }
        navItems[key] = tv
        nav.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun show(key: String) {
        if (builtFull != true) return
        // «تصوير» القديم صار جوا مشاريعي (مربعات البداية)
        tab = if (tabs.containsKey(key)) key else if (key == "capture") "projects" else "projects"
        for ((k, v) in tabs) v.visibility = if (k == tab) View.VISIBLE else View.GONE
        for ((k, tv) in navItems) {
            val on = k == tab
            val col = if (on) UiKit.ACCENT else UiKit.SUB
            tv.setTextColor(Color.parseColor(col))
            tv.typeface = if (on) Fonts.semi else Fonts.regular
            tv.setCompoundDrawablesWithIntrinsicBounds(null, UiKit.icon(this, tv.tag as String, col, 24), null, null)
        }
    }

    private fun scrollCol(): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(24)) }
        val sv = ScrollView(this).apply { isFillViewport = true; addView(col) }
        return sv to col
    }

    private fun title(col: LinearLayout, t: String, sub: String? = null): TextView? {
        col.addView(UiKit.text(this, t, 22f, UiKit.TEXT).apply { typeface = Fonts.bold; setPadding(0, dp(8), 0, 0) })
        if (sub == null) return null
        val s = UiKit.text(this, sub, 14f, UiKit.SUB)
        col.addView(s)
        return s
    }

    // ───────── غير مفعّل: الانضمام فقط ─────────

    private fun buildLocked(): View {
        val (sv, col) = scrollCol()
        title(col, "Reaction Recorder", tx("هذه النسخة غير مفعّلة — بتقدر تنضم لتسجيل كمايك أو كاميرا.", "This copy is not activated — you can join a recording as a mic or camera."))
        val j = UiKit.bigCard(this, Ic.QR, tx("الانضمام لجلسة", "Join a session"), tx("خلي هذا الجوال كاميرا أو مايك لجوال ثاني", "Use this phone as a camera or mic for another phone"), UiKit.BLUE_BG) { startActivity(Intent(this, JoinActivity::class.java)) }
        col.addView(j, UiKit.full(this, j, 18))
        val a = UiKit.bigCard(this, Ic.KEY, tx("التفعيل", "Activation"), tx("اطلب التفعيل من صاحب التطبيق", "Ask the app owner to activate it"), UiKit.TINT_ORANGE) { startActivity(Intent(this, LicenseActivity::class.java)) }
        col.addView(a, UiKit.full(this, a, 12))
        return sv
    }

    // ───────── مشاريعي ─────────

    private fun buildProjects(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (sv, col) = scrollCol()
        wrap.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // الرأس: الشعار + الانضمام
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(4)) }
        head.addView(ImageView(this).apply {
            setImageDrawable(UiKit.icon(this@HomeActivity, "M12 6a6 6 0 1 0 0 12a6 6 0 1 0 0-12z", UiKit.ON_ACCENT, 18, fill = true)); scaleType = ImageView.ScaleType.CENTER
            background = UiKit.bg(UiKit.ACCENT, 8, this@HomeActivity)
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        head.addView(UiKit.text(this, "ReactionRecorder", 19f, UiKit.TEXT).apply { typeface = Fonts.bold; setPaddingRelative(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(UiKit.iconButton(this, Ic.QR, tx("الانضمام لجلسة", "Join a session"), bgColor = null) { startActivity(Intent(this, JoinActivity::class.java)) })
        col.addView(head)
        liveBar = UiKit.text(this, "", 15f, "#FFFFFF", true).apply {
            background = UiKit.bg(UiKit.RED, 12, this@HomeActivity); setPadding(dp(14), dp(12), dp(14), dp(12)); minHeight = dp(48)
            visibility = View.GONE; setOnClickListener { openLive() }
        }
        col.addView(liveBar, UiKit.full(this, liveBar, 10))
        // ابدأ تصوير: ٤ مربعات
        col.addView(UiKit.text(this, tx("ابدأ تصوير", "Start capturing"), 13f, UiKit.SUB).apply { setPadding(0, dp(14), 0, 0) })
        val grid = LinearLayout(this)
        fun tile(path: String, name: String, sub: String, go: () -> Unit) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(4), dp(12), dp(4), dp(10))
                background = UiKit.ripple(UiKit.bg(UiKit.CARD, 12, this@HomeActivity, UiKit.LINE))
                isClickable = true; setOnClickListener { go() }
                contentDescription = name
            }
            v.addView(ImageView(this).apply { setImageDrawable(UiKit.icon(this@HomeActivity, path, UiKit.ACCENT, 24)) }, LinearLayout.LayoutParams(dp(26), dp(26)))
            v.addView(UiKit.text(this, name, 13f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0); maxLines = 1 })
            v.addView(UiKit.text(this, sub, 10.5f, UiKit.SUB).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            grid.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (grid.childCount < 3) marginEnd = dp(8) })
        }
        tile(Ic.MIC, tx("بودكاست", "Podcast"), tx("عدة جوالات", "Many phones")) { startActivity(Intent(this, PodcastActivity::class.java)) }
        tile(Ic.SCREEN, tx("رياكشن", "Reaction"), tx("فيديو + كاميرتك", "Video + you")) { startActivity(Intent(this, MainActivity::class.java)) }
        tile(Ic.PEN, tx("شرح", "Lesson"), tx("لوح وPDF", "Board & PDF")) { startActivity(Intent(this, BoardActivity::class.java)) }
        tile(Ic.CAM, tx("فلوق", "Vlog"), tx("وإنت ماشي", "On the move")) { startActivity(Intent(this, VlogActivity::class.java)) }
        col.addView(grid, UiKit.full(this, grid, 8))
        // آخر المشاريع + الفلاتر
        val ph = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(20), 0, dp(4)) }
        val pt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pt.addView(UiKit.text(this, tx("آخر المشاريع", "Recent projects"), 15f, UiKit.TEXT, true))
        projSub = UiKit.text(this, "", 12f, UiKit.SUB)
        pt.addView(projSub)
        ph.addView(pt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filterRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        ph.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(filterRow) })
        col.addView(ph)
        renderFilters()
        projList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(projList)
        val imp = UiKit.listRow(this, Ic.DOWNLOAD, tx("استيراد ملفات جهاز", "Import a device's files"), tx("لما ما في اتصال: انقل الملفات بإيدك", "When there's no connection: move the files by hand")) {
            AlertDialog.Builder(this).setTitle(tx("استيراد يدوي", "Manual import"))
                .setMessage(tx("على الجوال الآخر: التسجيلات على هذا الجوال ← احفظ في التنزيلات. انقل المجلد لهذا الجوال، ثم اختر كل ملفاته (meta.json والصوت/الفيديو).", "On the other phone: Recordings on this phone ← Save to Downloads. Move the folder here, then pick all its files (meta.json and the audio/video)."))
                .setPositiveButton(tx("اختر الملفات", "Pick files")) { _, _ -> pick.launch(arrayOf("*/*")) }
                .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
        }
        col.addView(imp, UiKit.full(this, imp, 4))
        // زر ثابت تحت: جلسة تصوير جديدة
        val newB = UiKit.primary(this, tx("جلسة تصوير جديدة", "New session"), UiKit.ACCENT) { newSession() }.apply { textSize = 16f; minHeight = dp(52); minimumHeight = dp(52) }
        UiKit.withIcon(newB, Ic.PLUS, UiKit.ON_ACCENT, 20)
        wrap.addView(newB, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(18), dp(6), dp(18), dp(10)) })
        return wrap
    }

    /** جلسة جديدة: اختيار النوع من لوحة تحت */
    private fun newSession() {
        UiKit.sheet(this, tx("جلسة تصوير جديدة", "New session")) { col, d ->
            fun row(path: String, n: String, s: String, c: Class<*>) = col.addView(UiKit.listRow(this, path, n, s) { d.dismiss(); startActivity(Intent(this, c)) })
            row(Ic.MIC, tx("بودكاست", "Podcast"), tx("عدة جوالات، كل شخص بكاميرته ومايكه، والمونتاج بيلحق اللي بيحكي تلقائيًا", "Several phones, auto-edited on whoever speaks"), PodcastActivity::class.java)
            row(Ic.SCREEN, tx("رياكشن", "Reaction"), tx("فيديو أو شاشة الجوال مع كاميرتك (أو بدونها)", "A video or your screen, with or without your camera"), MainActivity::class.java)
            row(Ic.PEN, tx("شرح", "Lesson"), tx("لوح رسم، PDF، وصفحة كتابة مع صوتك وكاميرتك", "Whiteboard, PDF and text page with your voice and camera"), BoardActivity::class.java)
            row(Ic.CAM, tx("فلوق", "Vlog"), tx("جوالك الكاميرا، وصحابك مايكات أو كاميرات وإنتو ماشيين", "Your phone films; friends are mics or cameras"), VlogActivity::class.java)
        }
    }

    private fun renderFilters() {
        filterRow.removeAllViews()
        val act = if (cache.isEmpty()) 0 else cache.count { stateOf(it).needsAction }
        for ((k, n) in listOf("all" to tx("الكل", "All"), "action" to tx("بدها إجراء", "Needs action") + (if (act > 0) " $act" else ""), "ready" to tx("جاهزة", "Ready"))) {
            val on = filter == k
            filterRow.addView(UiKit.text(this, n, 12f, if (on) UiKit.ON_ACCENT else "#C8CAD0", on).apply {
                setPadding(dp(11), dp(6), dp(11), dp(6)); minHeight = dp(32); gravity = Gravity.CENTER
                background = UiKit.ripple(UiKit.bg(if (on) UiKit.ACCENT else UiKit.CARD2, 16, this@HomeActivity), dark = on)
                isClickable = true; setOnClickListener { filter = k; renderFilters(); sig = ""; renderProjects() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        }
    }

    /** حالة المشروع الفعلية: نص، لون، زر المرحلة التالية */
    private class PState(val text: String, val color: String, val action: String, val go: () -> Unit, val needsAction: Boolean, val ready: Boolean)

    private fun stateOf(s: SessionInfo): PState {
        val pod = s.kind == "podcast"
        val waiting = s.devices.values.filter { it.started && !it.uploaded }
        val openSession = { startActivity(Intent(this, SessionActivity::class.java).putExtra("session", s.id)) }
        return when {
            PodRec.active == s.id -> PState(tx("يسجّل الآن", "Recording now"), UiKit.RED, tx("ارجع للتسجيل", "Back to recording"), { openLive() }, false, false)
            PodState.running && PodState.sid == s.id -> PState(tx("جارٍ التصدير ", "Exporting ") + "${(PodState.progress * 100).toInt()}%", UiKit.BLUE, tx("عرض التقدم", "View progress"), openSession, false, false)
            !s.ended -> PState(tx("التسجيل ما انتهى بشكل طبيعي", "Recording didn't finish normally"), UiKit.ORANGE, tx("افتح المشروع", "Open project"), openSession, true, false)
            waiting.isNotEmpty() -> PState(tx("بانتظار ملفات: ", "Waiting for files: ") + waiting.joinToString("، ") { it.name }, UiKit.ORANGE, tx("اجلب الملفات", "Fetch the files"), openSession, true, false)
            pod && ProjectStore.load(this, s.id, "episode") != null -> PState(tx("قيد المونتاج", "Editing"), UiKit.BLUE, tx("أكمل المونتاج", "Continue editing"), { openEdit(s.id) }, false, true)
            pod -> PState(tx("جاهز للمونتاج", "Ready to edit"), UiKit.GREEN, tx("ابدأ المونتاج", "Start editing"), { openEdit(s.id) }, true, true)
            s.mergedUri.isNotEmpty() -> PState(tx("مدموج ومحفوظ", "Merged and saved"), UiKit.SUB, tx("افتح المشروع", "Open project"), openSession, false, false)
            else -> PState(tx("جاهز للدمج", "Ready to merge"), UiKit.GREEN, tx("ادمج الصوت والفيديو", "Merge audio and video"), openSession, true, true)
        }
    }

    private fun openEdit(sid: String) = startActivity(Intent(this, PodcastEditActivity::class.java).putExtra("session", sid).putExtra("project", "episode"))

    private fun renderProjects() {
        if (builtFull != true || !::projList.isInitialized) return
        val states = cache.associate { it.id to stateOf(it) }
        val shown = cache.filter { x -> val st = states[x.id]!!; when (filter) { "action" -> st.needsAction; "ready" -> st.ready; else -> true } }
        val s = shown.joinToString("|") { x -> x.id + states[x.id]!!.text + (thumbs[x.id] != null) } + filter
        if (s == sig) return
        val firstWithData = sig.isEmpty() || sig == filter
        sig = s
        if (firstWithData) renderFilters()
        val act = cache.count { states[it.id]!!.needsAction }
        projSub.text = if (cache.isEmpty()) "" else UiKit.projects(cache.size) + if (act > 0) tx(" · $act بدها إجراء", " · $act need action") else ""
        projList.removeAllViews()
        if (shown.isEmpty()) {
            val e = UiKit.card(this, null, projList)
            e.addView(UiKit.text(this, if (cache.isEmpty()) tx("ما في مشاريع بعد", "No projects yet") else tx("ما في مشاريع بهالتصنيف", "No projects in this filter"), 15f, UiKit.TEXT, true))
            e.addView(UiKit.text(this, if (cache.isEmpty()) tx("ابدأ جلسة تصوير جديدة. فيديوهات الرياكشن العادية بتنحفظ مباشرة بالمعرض (Movies/ReactionRecorder)، وهون بتظهر التسجيلات اللي بدها دمج أو مونتاج.", "Start a new session. Plain reaction videos go straight to the Gallery (Movies/ReactionRecorder); recordings that need merging or editing show up here.") else "", 13f, UiKit.SUB))
            return
        }
        for (x in shown) projList.addView(projectCard(x, states[x.id]!!))
    }

    private fun projectCard(s: SessionInfo, st: PState): View {
        val pod = s.kind == "podcast"
        val vlog = pod && s.extra.contains("\"vlog\":true")
        val dur = if (pod) PodcastAnalyzer.duration(s).toLong() else if (s.ended) ((s.endElapsedNs - s.startElapsedNs - s.pauses.sumOf { it[1] }) / 1_000_000_000L).coerceAtLeast(0L) else 0L
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64)
            setPadding(0, dp(10), 0, dp(10))
            background = UiKit.ripple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isClickable = true; setOnClickListener { st.go() }
            isLongClickable = true; setOnLongClickListener { startActivity(Intent(this@HomeActivity, SessionActivity::class.java).putExtra("session", s.id)); true }
            contentDescription = st.action
        }
        val th = FrameLayout(this).apply { background = UiKit.bg(UiKit.CARD2, 8, this@HomeActivity); clipToOutline = true }
        val tb = thumbs[s.id]
        if (tb != null) th.addView(ImageView(this).apply { setImageBitmap(tb); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        else th.addView(ImageView(this).apply { setImageDrawable(UiKit.icon(this@HomeActivity, if (vlog) Ic.CAM else if (pod) Ic.MIC else Ic.SCREEN, UiKit.SUB, 20)); scaleType = ImageView.ScaleType.CENTER },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(th, LinearLayout.LayoutParams(dp(76), dp(44)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(12), 0, dp(8), 0) }
        val kind = if (vlog) tx("فلوق", "Vlog") else if (pod) tx("بودكاست", "Podcast") else tx("رياكشن", "Reaction")
        t.addView(UiKit.text(this, kind + " · " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(s.wallMs)), 14.5f, UiKit.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val n = if (pod) PodcastAnalyzer.setupOf(s).people.size else s.devices.size
        val reels = if (pod) try { ProjectStore.dir(this, s.id).listFiles()?.count { it.name.startsWith("reel_") && it.name.endsWith(".json") } ?: 0 } catch (_: Throwable) { 0 } else 0
        val meta = ArrayList<String>()
        meta += if (pod) UiKit.people(n) else tx("أجهزة: ${s.devices.size}", "Devices: ${s.devices.size}")
        if (s.ended && dur > 0) meta += String.format(Locale.US, "%d:%02d", dur / 60, dur % 60)
        if (reels > 0) meta += tx("ريلز $reels", "$reels reels")
        t.addView(UiKit.text(this, meta.joinToString(" · "), 12f, UiKit.SUB).apply { maxLines = 1 })
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(UiKit.text(this, st.text, 11f, st.color).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3)); maxLines = 1; maxWidth = dp(130); ellipsize = android.text.TextUtils.TruncateAt.END
            background = UiKit.bg(when (st.color) { UiKit.GREEN -> UiKit.TINT_GREEN; UiKit.ORANGE -> UiKit.TINT_ORANGE; UiKit.RED -> UiKit.TINT_RED; UiKit.ACCENT -> UiKit.ACCENT_BG; else -> UiKit.CARD2 }, 6, this@HomeActivity)
        })
        box.addView(row)
        if (PodState.running && PodState.sid == s.id) box.addView(UiKit.level(this).apply {
            progress = (PodState.progress * 100).toInt(); progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(UiKit.ACCENT))
        }, UiKit.full(this, box, 0, 4))
        box.addView(View(this).apply { setBackgroundColor(Color.parseColor("#1E2025")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        return box
    }

    /** صورة مصغّرة من فيديو أول جهاز (تُحفظ بجانب الجلسة) */
    private fun loadThumb(s: SessionInfo): Bitmap? {
        val f = File(SessionStore.dir(this, s.id), "thumb.jpg")
        if (f.exists()) return try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
        val v = s.devices.keys.map { File(SessionStore.devDir(this, s.id, it), "video.mp4") }.firstOrNull { it.exists() && it.length() > 0 } ?: return null
        return try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(v.absolutePath)
            val b = r.getFrameAtTime(2_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            r.release()
            if (b == null) null else {
                val sc = 240f / maxOf(b.width, b.height)
                val small = Bitmap.createScaledBitmap(b, (b.width * sc).toInt().coerceAtLeast(1), (b.height * sc).toInt().coerceAtLeast(1), true)
                f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                small
            }
        } catch (_: Throwable) { null }
    }

    // ───────── تصوير ─────────

    private fun buildDevices(): View {
        val (sv, col) = scrollCol()
        title(col, tx("الأجهزة", "Devices"), tx("خلي جوالات ولابتوبات ثانية كاميرات أو مايكات لجلستك", "Use other phones and laptops as cameras or mics"))
        val c = UiKit.card(this, null, col)
        c.addView(UiKit.listRow(this, Ic.QR, tx("الانضمام لجلسة", "Join a session"), tx("خلي هذا الجوال كاميرا أو مايك لجوال ثاني", "Use this phone as a camera or mic for another phone")) { startActivity(Intent(this, JoinActivity::class.java)) })
        c.addView(UiKit.listRow(this, Ic.LINK, tx("الانضمام من المتصفح", "Join from a browser"), tx("آيفون، آيباد، لابتوب ويندوز أو ماك، أو أندرويد بدون التطبيق — بدون إنترنت", "iPhone, iPad, Windows or Mac laptop, or Android without the app — no internet")) { startActivity(Intent(this, WebJoinActivity::class.java)) })
        c.addView(UiKit.listRow(this, Ic.SHARE, tx("شارك التطبيق", "Share the app"), tx("باركود بينزّل التطبيق من جوالك ويربطه فيك، بدون إنترنت", "A code that downloads the app from your phone and pairs it, no internet")) { shareDialog() })
        c.addView(UiKit.listRow(this, Ic.WIFI, tx("الأجهزة المتصلة وجلسات الرياكشن", "Connected devices & reaction sessions"), tx("مين متصل فيك هلأ، والملفات اللي وصلت", "Who's connected now, and received files")) { startActivity(Intent(this, DevicesActivity::class.java)) })
        return sv
    }

    // ───────── الإعدادات ─────────

    private fun buildSettings(): View {
        val (sv, col) = scrollCol()
        title(col, tx("الإعدادات", "Settings"))
        val g = UiKit.card(this, tx("عام", "General"), col)
        g.addView(UiKit.listRow(this, Ic.SLIDERS, tx("إعدادات التطبيق", "App settings"), tx("اللغة، التصوير، المونتاج الافتراضي، الأجهزة، التخزين", "Language, capture, default editing, devices, storage")) { startActivity(Intent(this, SettingsActivity::class.java)) })
        g.addView(UiKit.listRow(this, Ic.REELS, tx("قوالب المونتاج", "Edit templates"), tx("البودكاست والفلوق: المقسوم، الأسماء، والإيقاع — والقالب الافتراضي", "Podcast and vlog: split screen, names, pacing — and the default")) { startActivity(Intent(this, TemplateActivity::class.java)) })
        g.addView(UiKit.listRow(this, Ic.KEY, tx("التفعيل", "Activation"), if (License.isOwner(this)) tx("صاحب التطبيق — تفعيل وقفل الأجهزة", "Owner — activate and lock devices") else tx("حالة نسختك", "Your copy's status")) { startActivity(Intent(this, LicenseActivity::class.java)) })
        g.addView(UiKit.listRow(this, Ic.SHARE, tx("شارك التطبيق", "Share the app"), tx("باركود التنزيل والربط", "Download & pairing code")) { shareDialog() })
        val a = UiKit.card(this, tx("متقدم", "Advanced"), col)
        a.addView(UiKit.listRow(this, Ic.KEY, tx("خدمة التفريغ وفهم الحوار", "Transcription & understanding service"), if (PodAI.cfg(this).key.isNotBlank()) tx("مضبوطة", "Configured") else tx("غير مضبوطة (اختياري)", "Not set (optional)")) { PodUi.aiDialog(this) })
        col.addView(UiKit.text(this, "Reaction Recorder v" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "" }), 12f, UiKit.SUB).apply {
            setPadding(0, dp(20), 0, 0); textAlignment = View.TEXT_ALIGNMENT_CENTER
        })
        return sv
    }

    /** مشاركة التطبيق: باركود واحد ينزّل التطبيق من هذا الجوال (بدون إنترنت) ويقترن به */
    private fun shareDialog() {
        val c = applicationContext
        UiKit.sheet(this, tx("شارك التطبيق", "Share the app")) { col, _ ->
            val img = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)) }
            val note = UiKit.text(this, tx("جارٍ التشغيل…", "Starting…"), 14f, UiKit.SUB)
            col.addView(img, LinearLayout.LayoutParams(dp(250), dp(250)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
            col.addView(note, UiKit.full(this, note, 10))
            Thread {
                try { if (!SatHub.running) { SatHub.start(c); SatHub.setHubWanted(c, SatHub.running) } } catch (_: Throwable) {}
                try { AppShare.start(c) } catch (_: Throwable) {}
                val url = AppShare.pairQr()
                ui.post {
                    if (!AppShare.running || AppShare.ip() == null) {
                        note.text = tx("شغّل الهوتسبوت أو الواي فاي أولًا ثم افتح هذه النافذة من جديد.", "Turn on the hotspot or Wi-Fi first, then open this again.")
                    } else {
                        img.setImageBitmap(PodcastActivity.qrBitmap(url))
                        note.text = tx("خلي صاحبك يتصل بشبكة جوالك (الهوتسبوت) ويمسح الباركود بكاميرا جواله: ينزل التطبيق من جوالك بدون إنترنت. ومن عنده التطبيق، نفس الباركود يربطه بجوالك. النسخ الجديدة تحتاج تفعيلك.",
                            "Your friend joins your phone's hotspot and scans this with their camera: the app downloads from your phone, no internet. If they already have the app, the same code pairs them with you. New copies need your activation.")
                    }
                }
            }.start()
        }
    }

    private fun askPerms() {
        val need = ArrayList<String>()
        for (p in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need += p
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) perms.launch(need.toTypedArray())
    }

    private fun openLive() {
        when {
            RecorderState.isBusy -> startActivity(Intent(this, MainActivity::class.java))
            PodRec.active != null -> startActivity(Intent(this, if (PodRec.isVlog(this)) VlogActivity::class.java else MonitorActivity::class.java))
            SatState.serviceOn -> startActivity(Intent(this, SatelliteActivity::class.java))
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (builtFull == true && ::liveBar.isInitialized) {
                val txt = when {
                    RecorderState.isBusy -> tx("● تسجيل رياكشن جارٍ — اضغط للرجوع", "● Reaction recording in progress — tap to return")
                    PodRec.active != null -> tx("● تسجيل جارٍ — اضغط للرجوع", "● Recording in progress — tap to return")
                    SatState.recording -> tx("● هذا الجوال يسجّل كجهاز مساعد — اضغط للرجوع", "● This phone is recording as a helper — tap to return")
                    // جوالك نفسه ككاميرا لجلستك (اتصال داخلي) مش "جهاز مساعد"
                    SatState.serviceOn && !SatCfg.load(this@HomeActivity).host.startsWith("127.0.0.1") -> tx("هذا الجوال متصل كجهاز مساعد — اضغط للرجوع", "This phone is connected as a helper — tap to return")
                    else -> ""
                }
                liveBar.text = txt
                liveBar.visibility = if (txt.isEmpty()) View.GONE else View.VISIBLE
                val lbc = if (SatState.serviceOn && !SatState.recording && !RecorderState.isBusy && PodRec.active == null) UiKit.BLUE else UiKit.RED
                liveBar.background = UiKit.bg(lbc, 14, this@HomeActivity); liveBar.setTextColor(UiKit.onColor(lbc))
                val now = android.os.SystemClock.elapsedRealtime()
                if (!loading && now - lastLoad > 2500) {
                    loading = true; lastLoad = now
                    val c = applicationContext
                    Thread {
                        val l = try { SessionStore.list(c).filter { it.kind == "podcast" || it.devices.isNotEmpty() } } catch (_: Throwable) { emptyList() }
                        val need = l.filter { !thumbs.containsKey(it.id) && it.ended }.take(4)
                        val got = need.associate { it.id to loadThumb(it) }
                        ui.post { cache = l; thumbs.putAll(got); loading = false; renderProjects() }
                    }.start()
                } else renderProjects()
            }
            ui.postDelayed(this, 1000)
        }
    }
}
