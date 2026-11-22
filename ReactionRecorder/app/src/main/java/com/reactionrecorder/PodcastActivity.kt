package com.reactionrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * حوار / بودكاست: الأجهزة ← الأشخاص ← تسجيل. كل قسم يطوى، والمراقبة الخفيفة ظاهرة دائمًا.
 * التسجيلات ومونتاجها في "تسجيلاتي".
 */
class PodcastActivity : BaseActivity() {
    // ملاحظة: التسجيل نفسه بشاشة MonitorActivity (معاينة كبيرة، علامات، إنهاء مطوّل)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var setup: PodSetup
    private lateinit var devBody: LinearLayout
    private lateinit var hubTv: TextView
    private lateinit var statsRow: LinearLayout
    private var statsSig = ""
    private lateinit var thisPhoneRow: LinearLayout
    private lateinit var qrView: ImageView
    private lateinit var devList: LinearLayout
    private lateinit var peopleBody: LinearLayout
    private lateinit var countRow: LinearLayout
    private lateinit var peopleBox: LinearLayout
    private lateinit var widesBox: LinearLayout
    private lateinit var styleBody: LinearLayout
    private lateinit var monitorRow: LinearLayout
    private lateinit var recBtn: Button
    private lateinit var recTv: TextView
    private lateinit var markerRow: LinearLayout
    private lateinit var lastTv: TextView
    private var lastSid: String? = null
    private var qrFor = ""
    private var devSig = ""
    private val monViews = HashMap<String, ImageView>()
    private val monSeen = HashMap<String, Long>()
    private val levelBars = HashMap<Int, ProgressBar>()
    private val devLines = HashMap<String, TextView>()

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { pendingAfterPerm?.invoke(); pendingAfterPerm = null }
    private var pendingAfterPerm: (() -> Unit)? = null

    private val pickBg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            setup.bgImage = u.toString()
            save(); buildStyle()
        }
    }

    private fun dp(v: Int) = UiKit.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setup = PodSetup.loadDefault(this)
        lastSid = getSharedPreferences("reaction_podcast", MODE_PRIVATE).getString("lastSid", null)
        build()
        if (!SatHub.running) {
            val c = applicationContext
            Thread { try { SatHub.start(c); SatHub.setHubWanted(c, SatHub.running) } catch (_: Throwable) {} }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        devSig = ""
        demoTpl = null
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        for (l in SatHub.links.values) SatHub.setPreview(l.id, l.stripOn, false, l.stripOn)
    }

    private fun save() = PodSetup.saveDefault(this, setup)

    private lateinit var demo: LayoutDemoView
    private lateinit var demoWarn: TextView
    private var demoPortraitBtn: Button? = null
    private var demoTpl: PodTemplate? = null
    private lateinit var demoCap: TextView
    private lateinit var readyBox: LinearLayout
    private lateinit var devTitle: TextView
    private lateinit var liveBanner: TextView
    private class DevCard(val img: ImageView?, val pill: TextView, val lvl: ProgressBar, val info: TextView) { var seen = 0L }
    private val devCards = HashMap<String, DevCard>()

    private var twoPane: TwoPane? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        twoPane?.apply(newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun build() {
        // صفحة بزر ثابت تحت: "ابدأ التسجيل"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(UiKit.BG)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(20)) }
        val sv = ScrollView(this).apply { isFillViewport = true; addView(col) }
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(12)); setBackgroundColor(Color.parseColor(UiKit.BG)) }
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#2A2C32")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        root.addView(bottom)
        setContentView(root)
        twoPane = TwoPane(this, root, sv, col) {
            // الأجهزة (مع صورها) على جهة، والأشخاص والشكل والجاهزية على الجهة الثانية
            var x: View = peopleBody
            while (x.parent != null && x.parent !== col) x = x.parent as View
            col.indexOfChild(x).let { if (it < 0) col.childCount else it }
        }
        ui.post { twoPane?.apply(resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bb.top, 0, bb.bottom); insets
        }
        col.addView(UiKit.header(this, tx("جلسة بودكاست", "Podcast session"), tx("وصّل الجوالات، تأكد إنها جاهزة، وسجّل. المونتاج بعدين من «مشاريعي».", "Connect the phones, check they're ready, and record. Edit later from Projects.")))
        // ملخص سريع فوق: كم شخص، كم جهاز متصل، وكم كاميرا
        statsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        col.addView(statsRow, UiKit.full(this, statsRow, 4))

        liveBanner = UiKit.text(this, tx("● التسجيل شغّال — افتح شاشة التسجيل", "● Recording — open the recording screen"), 15f, "#FFFFFF", true).apply {
            background = UiKit.bg(UiKit.RED, 14, this@PodcastActivity); setPadding(dp(14), dp(12), dp(14), dp(12)); minHeight = dp(48); visibility = View.GONE
            setOnClickListener { startActivity(Intent(this@PodcastActivity, MonitorActivity::class.java)) }
        }
        col.addView(liveBanner, UiKit.full(this, liveBanner, 8))

        // ── الأجهزة
        val dh = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        devTitle = UiKit.text(this, tx("الأجهزة", "Devices"), 19f, UiKit.TEXT).apply { typeface = Fonts.bold }
        dh.addView(devTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dh.addView(UiKit.chipIcon(this, Ic.QR, tx("إضافة جوال", "Add a phone"), color = "#F2F2F3") { addPhoneSheet() })
        col.addView(dh, UiKit.full(this, dh, 14))
        hubTv = UiKit.text(this, "", 13f, UiKit.SUB)
        col.addView(hubTv)
        val tp = UiKit.card(this, tx("هذا الجوال", "This phone"), col)
        thisPhoneRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tp.addView(thisPhoneRow)
        buildThisPhone()
        devList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(devList)
        val legend = UiKit.text(this, "● " + tx("متصل وجاهز", "connected & ready") + "    ● " + tx("يسجّل", "recording") + "    ● " + tx("تنبيه", "warning") + "    ✓ " + tx("وصلت ملفاته", "files received"), 12f, UiKit.SUB)
        col.addView(legend, UiKit.full(this, legend, 8))
        qrView = ImageView(this)

        // ── الأشخاص
        peopleBody = UiKit.section(this, col, "pod_people", tx("الأشخاص", "People"), "", openByDefault = true)
        countRow = LinearLayout(this)
        peopleBody.addView(UiKit.text(this, tx("كم شخص؟", "How many people?"), 13.5f, UiKit.SUB))
        peopleBody.addView(countRow)
        peopleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peopleBody.addView(peopleBox)
        peopleBody.addView(UiKit.text(this, tx("الكاميرا العامة (بتجيب الكل) — اختياري", "Wide camera (shows everyone) — optional"), 14f, UiKit.TEXT, true).apply { setPadding(0, dp(12), 0, 0) })
        peopleBody.addView(UiKit.text(this, tx("جوال زيادة بعيد شوي بيصوّر الطاولة كلها. المونتاج بيروح عليه وقت الصمت، وبكسر الملل لو اخترته بالقالب. علّم الجهاز هون أو من زر «الكاميرا العامة» على بطاقته.", "An extra phone a bit further back filming the whole table. The edit cuts to it during silence, and for monotony breaks if your template uses it. Tick the device here or use the “Wide camera” button on its card."), 12.5f, UiKit.SUB))
        widesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peopleBody.addView(widesBox)

        // ── شكل المشهد
        // شكل المشهد لما يحكوا مع بعض: صار بالقالب بس (مكان واحد لكل إعداد)
        styleBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buildStyle()

        // ── كيف رح يطلع
        val dc = UiKit.card(this, tx("كيف رح يطلع الفيديو (القالب الافتراضي)", "How the video will look (default template)"), col)
        demo = LayoutDemoView(this)
        dc.addView(demo)
        demoCap = UiKit.text(this, "", 14f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        dc.addView(demoCap)
        demo.onCaption = { t -> demoCap.post { demoCap.text = t } }
        demoWarn = UiKit.text(this, "", 13f, UiKit.ORANGE).apply { visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        dc.addView(demoWarn)
        demoPortraitBtn = UiKit.chip(this, tx("شوف كيف بيطلع لو ضل الجوال بالطول", "See how it looks if the phone stays upright")) {
            demo.drawnPortrait = !demo.drawnPortrait
            demoPortraitBtn?.text = if (demo.drawnPortrait) tx("رجّع: الجوال بالعرض", "Back: phone sideways") else tx("شوف كيف بيطلع لو ضل الجوال بالطول", "See how it looks if the phone stays upright")
            demo.invalidate()
        }.apply { visibility = View.GONE }
        dc.addView(demoPortraitBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        dc.addView(UiKit.chipIcon(this, Ic.REELS, tx("قوالب المونتاج", "Edit templates")) { startActivity(Intent(this, TemplateActivity::class.java)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // ── الجاهزية
        readyBox = UiKit.card(this, tx("جاهزين للتسجيل؟", "Ready to record?"), col)

        lastTv = UiKit.text(this, "", 14f, UiKit.SUB).apply {
            background = UiKit.bg(UiKit.CARD, 14, this@PodcastActivity); setPadding(dp(14), dp(12), dp(14), dp(12)); minHeight = dp(48)
            setOnClickListener { lastSid?.let { startActivity(Intent(this@PodcastActivity, SessionActivity::class.java).putExtra("session", it)) } }
        }
        col.addView(lastTv, UiKit.full(this, lastTv, 12))

        // ── الزر الثابت
        recTv = UiKit.text(this, "", 15f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        bottom.addView(recTv)
        recBtn = UiKit.primary(this, "", UiKit.RED) { onRecord() }
        bottom.addView(recBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        markerRow = LinearLayout(this)
        monitorRow = LinearLayout(this)
        setPeople(setup.people.size.coerceIn(1, 4))
    }

    /** إضافة جوال: باركود الربط (وتنزيل التطبيق) + تعليمات قصيرة */
    private fun addPhoneSheet() {
        UiKit.sheet(this, tx("إضافة جوال", "Add a phone")) { col, _ ->
            col.addView(UiKit.text(this, tx("١. الجوال الثاني يتصل بنفس الواي فاي أو بالهوتسبوت تبعك.\n٢. يفتح التطبيق ← الانضمام لجلسة ← يختار الدور ← يمسح الباركود.\n   ما عنده التطبيق؟ يمسحه بكاميرا جواله وبينزل من جوالك.\n٣. بيتصل لحاله، وبيرجع يتصل إذا انقطع.", "1. The other phone joins the same Wi-Fi or your hotspot.\n2. It opens the app ← Join a session ← picks a role ← scans the code.\n   No app? Scan with the camera and it downloads from your phone.\n3. It connects by itself and reconnects if dropped."), 14.5f, UiKit.TEXT))
            val iv = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)) }
            col.addView(iv, LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
            val pin = UiKit.text(this, "", 13f, UiKit.SUB).apply { gravity = Gravity.CENTER }
            col.addView(pin, UiKit.full(this, pin, 8))
            if (SatHub.running) { iv.setImageBitmap(qrBitmap(AppShare.pairQr())); pin.text = tx("الرمز: ", "PIN: ") + SatHub.pin }
            else pin.text = tx("جارٍ تشغيل الاستقبال… سكّر وافتح من جديد بعد ثانية.", "Starting… close and reopen in a second.")
        }
    }

    // ───────── هذا الجوال ─────────

    private fun buildThisPhone() {
        thisPhoneRow.removeAllViews()
        val mode = if (!SatState.serviceOn) "off" else SatCfg.load(this).role
        val sc = SatCfg.load(this)
        thisPhoneRow.addView(UiKit.row(this,
            UiKit.chip(this, tx("لا يسجّل", "Not recording"), mode == "off") { stopThisPhone() },
            UiKit.chip(this, tx("كاميرا + مايك", "Camera + mic"), mode == "both") { startThisPhone("both", sc.front) },
            UiKit.chip(this, tx("مايك بس", "Mic only"), mode == "mic") { startThisPhone("mic", sc.front) },
        ))
        if (mode == "both") {
            thisPhoneRow.addView(UiKit.row(this,
                UiKit.chip(this, if (sc.front) tx("الكاميرا الأمامية", "Front camera") else tx("الكاميرا الخلفية", "Back camera")) { startThisPhone("both", !sc.front) },
                UiKit.chip(this, when (sc.orient) { "portrait" -> tx("عمودي", "Portrait"); "landscape" -> tx("أفقي", "Landscape"); else -> tx("اتجاه تلقائي", "Auto orientation") }) {
                    val c = SatCfg.load(this)
                    c.orient = when (c.orient) { "auto" -> "portrait"; "portrait" -> "landscape"; else -> "auto" }
                    SatCfg.save(this, c); buildThisPhone()
                },
            ))
        }
    }

    /** هذا الجوال يسجّل ككاميرا/مايك عبر نفس نظام الأجهزة المساعدة (اتصال داخلي) */
    private fun startThisPhone(role: String, front: Boolean, asked: Boolean = false) {
        if (asked) {
            if (!granted(Manifest.permission.RECORD_AUDIO)) { toast(tx("يلزم إذن المايك", "Microphone permission is required")); return }
            if (role == "both" && !granted(Manifest.permission.CAMERA)) { toast(tx("يلزم إذن الكاميرا", "Camera permission is required")); return }
        } else {
            val need = ArrayList<String>()
            if (!granted(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
            if (role == "both" && !granted(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
            if (need.isNotEmpty()) { pendingAfterPerm = { startThisPhone(role, front, true) }; perms.launch(need.toTypedArray()); return }
        }
        if (!SatHub.running) { toast(tx("جارٍ تشغيل الاستقبال… حاول بعد ثانية", "Starting… try again in a second")); return }
        if (RecorderState.isBusy) { toast(tx("أوقف تسجيل الرياكشن أولًا", "Stop the reaction recording first")); return }
        if (PodRec.active != null) { toast(tx("لا يمكن التغيير أثناء التسجيل", "Can't change while recording")); return }
        val c = SatCfg.load(this)
        val restart = SatState.serviceOn && (c.role != role || c.front != front)
        c.host = "127.0.0.1:${SatHub.port}"
        c.pin = SatHub.pin
        c.hubId = SatHub.hubId
        c.name = tx("هذا الجوال", "This phone")
        c.role = role
        c.front = front
        SatCfg.save(this, c)
        if (restart) {
            startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_DISCONNECT))
            ui.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    try { ContextCompat.startForegroundService(this, Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_CONNECT)) } catch (_: Throwable) { toast(tx("تعذرت إعادة التشغيل — اضغط مرة ثانية", "Could not restart — tap again")) }
                    buildThisPhone()
                }
            }, 2000)
        } else if (!SatState.serviceOn) {
            ContextCompat.startForegroundService(this, Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_CONNECT))
        }
        ui.postDelayed({ buildThisPhone() }, 400)
    }

    private fun stopThisPhone() {
        if (PodRec.active != null) { toast(tx("لا يمكن التغيير أثناء التسجيل", "Can't change while recording")); return }
        if (SatState.serviceOn) startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_DISCONNECT))
        ui.postDelayed({ buildThisPhone() }, 400)
    }

    // ───────── الأشخاص ─────────

    private fun setPeople(n: Int) {
        while (setup.people.size < n) setup.people.add(PodPerson(tx("شخص", "Person") + " ${setup.people.size + 1}", "", ""))
        while (setup.people.size > n) setup.people.removeAt(setup.people.size - 1)
        save()
        countRow.removeAllViews()
        for (k in 1..4) countRow.addView(UiKit.chip(this, "$k", k == n) { if (PodRec.active == null) setPeople(k) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        devSig = ""
    }

    private class Dev(val id: String, val name: String)

    private fun devices(): List<Dev> = SatHub.links.values.sortedBy { it.name }.map {
        Dev(it.id, (if (it.connected) "🟢 " else "⚪ ") + it.name + if (it.role == "both") " (" + tx("كاميرا+مايك", "cam+mic") + ")" else " (" + tx("مايك", "mic") + ")")
    }

    private fun spinner(items: List<Dev>, selId: String, none: String, onSel: (String) -> Unit): Spinner {
        val labels = ArrayList(listOf(none) + items.map { it.name })
        val ids = ArrayList(listOf("") + items.map { it.id })
        if (selId.isNotEmpty() && selId !in ids) { ids.add(selId); labels.add("⚪ " + tx("جهاز محفوظ (غير متصل)", "Saved device (offline)")) }
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        sp.setSelection(ids.indexOf(selId).coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onSel(ids[pos]) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        sp.isEnabled = PodRec.active == null
        return sp
    }

    private fun buildPeople() {
        peopleBox.removeAllViews()
        levelBars.clear()
        val devs = devices()
        val cams = devs.filter { d -> SatHub.links[d.id]?.role == "both" }
        if (devs.isEmpty()) peopleBox.addView(UiKit.text(this, tx("لا أجهزة بعد — وصّل الجوالات أولًا، أو اجعل هذا الجوال يسجّل.", "No devices yet — connect phones first, or let this phone record."), 12.5f, UiKit.ORANGE).apply { setPadding(0, dp(8), 0, 0) })
        for ((i, p) in setup.people.withIndex()) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(10)); background = UiKit.bg(UiKit.CARD2, 14, this@PodcastActivity) }
            val name = EditText(this).apply {
                setText(p.name); setTextColor(Color.WHITE); textSize = 15f; setSingleLine()
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: android.text.Editable?) { p.name = e?.toString()?.ifBlank { tx("شخص", "Person") + " ${i + 1}" } ?: p.name; save() }
                })
            }
            box.addView(name)
            val r = LinearLayout(this)
            val c1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c1.addView(UiKit.text(this, tx("كاميرته", "Camera"), 13f, UiKit.SUB))
            c1.addView(spinner(cams, p.camDev, tx("— بدون —", "— none —")) { id -> if (p.camDev != id) { p.camDev = id; save() } })
            val c2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c2.addView(UiKit.text(this, tx("مايكه", "Mic"), 13f, UiKit.SUB))
            c2.addView(spinner(devs, p.micDev, tx("— بدون —", "— none —")) { id -> if (p.micDev != id) { p.micDev = id; save() } })
            r.addView(c1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            r.addView(c2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(r)
            // كم شخص قاعد قدام كاميرته (بيساعد المونتاج: شخص → زوم عليه، شخصين → الاثنين)
            val cr = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            cr.addView(UiKit.text(this, tx("قدام الكاميرا:", "In front of the camera:"), 13f, UiKit.SUB), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            val curC = setup.srcCrowd["p$i"] ?: ""
            for ((k, n) in listOf("" to tx("تلقائي", "Auto"), "1" to "1", "2" to "2", "3" to "3+")) cr.addView(UiKit.chip(this, n, curC == k) {
                if (k.isEmpty()) setup.srcCrowd.remove("p$i") else setup.srcCrowd["p$i"] = k
                save(); buildPeople()
            }.apply { textSize = 13f; setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(4), dp(2), 0) })
            box.addView(cr)
            val pb = UiKit.level(this)
            levelBars[i] = pb
            box.addView(pb, UiKit.full(this, pb, 6, 6))
            peopleBox.addView(box, UiKit.full(this, box, 8))
        }
        widesBox.removeAllViews()
        if (cams.isEmpty()) widesBox.addView(UiKit.text(this, tx("لا توجد كاميرات متصلة", "No cameras connected"), 12f, UiKit.SUB))
        for (d in cams) {
            widesBox.addView(CheckBox(this).apply {
                text = d.name; setTextColor(Color.WHITE)
                isChecked = d.id in setup.wides
                isEnabled = PodRec.active == null
                setOnCheckedChangeListener { _, on -> if (on) { if (d.id !in setup.wides) setup.wides.add(d.id) } else setup.wides.remove(d.id); save() }
            })
        }
        val nc = setup.people.count { it.camDev.isNotEmpty() }; val nm = setup.people.count { it.micDev.isNotEmpty() }
        UiKit.setSummary(peopleBody, tx("${setup.people.size} أشخاص • $nc كاميرا • $nm مايك", "${setup.people.size} people • $nc cameras • $nm mics"))
    }

    private fun buildStyle() {
        styleBody.removeAllViews()
        styleBody.addView(UiKit.row(this,
            UiKit.chip(this, "▥ " + tx("تقسيم الشاشة", "Split screen"), setup.multiStyle == "split") { setup.multiStyle = "split"; save(); buildStyle() },
            UiKit.chip(this, "▣ " + tx("مربعات على خلفية", "Boxes on a background"), setup.multiStyle == "boxes") { setup.multiStyle = "boxes"; save(); buildStyle() },
        ))
        if (setup.multiStyle == "boxes") {
            val colors = listOf(0xFF1B1D24.toInt(), 0xFF0B3D91.toInt(), 0xFF5B2C83.toInt(), 0xFF1E5631.toInt(), 0xFF7A1F1F.toInt(), 0xFFEDEDED.toInt())
            val r = LinearLayout(this)
            for (c in colors) {
                r.addView(View(this).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(c); if (setup.bgColor == c && setup.bgImage.isEmpty()) setStroke(dp(3), Color.parseColor(UiKit.BLUE)) }
                    setOnClickListener { setup.bgColor = c; setup.bgImage = ""; save(); buildStyle() }
                }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(0, dp(6), dp(8), 0) })
            }
            styleBody.addView(r)
            styleBody.addView(UiKit.chip(this, if (setup.bgImage.isEmpty()) "🖼 " + tx("صورة كخلفية", "Image as background") else "🖼 " + tx("صورة مختارة ✓", "Image chosen ✓"), setup.bgImage.isNotEmpty()) { pickBg.launch(arrayOf("image/*")) })
        }
        styleBody.addView(UiKit.text(this, tx("التبديل بين المتكلمين والزوم والكتابة تُضبط لكل حلقة/ريل في المونتاج.", "Speaker switching, zoom and captions are set per episode/reel in the editor."), 11.5f, UiKit.SUB).apply { setPadding(0, dp(8), 0, 0) })
        UiKit.setSummary(styleBody, if (setup.multiStyle == "split") tx("تقسيم الشاشة", "Split screen") else tx("مربعات على خلفية", "Boxes on a background"))
    }

    // ───────── التسجيل ─────────

    private fun onRecord() {
        if (PodRec.active != null) { startActivity(Intent(this, MonitorActivity::class.java)); return }
        if (RecorderState.isBusy) { toast(tx("أوقف تسجيل الرياكشن أولًا", "Stop the reaction recording first")); return }
        if (!SatHub.running) { toast(tx("الاستقبال لا يعمل", "Receiving is off")); return }
        // كاميرا متصلة ومش مربوطة بحدا: نسأل قبل ما نبلّش (عشان ما توصل وما تنستعمل)
        val loose = SatHub.links.values.filter { l -> l.connected && l.use && l.role == "both" && setup.people.none { it.camDev == l.id } && l.id !in setup.wides }
        if (loose.isNotEmpty()) { askLoose(loose.first()) { onRecord() }; return }
        PodExplain.confirmStart(this, setup, PodRec.check(setup)) { startRec() }
    }

    private val skipLoose = HashSet<String>()

    private fun askLoose(l: SatHub.Link, then: () -> Unit) {
        if (l.id in skipLoose) { val rest = SatHub.links.values.filter { x -> x.connected && x.use && x.role == "both" && setup.people.none { it.camDev == x.id } && x.id !in setup.wides && x.id !in skipLoose }
            if (rest.isEmpty()) { PodExplain.confirmStart(this, setup, PodRec.check(setup)) { startRec() } } else askLoose(rest.first(), then); return }
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += tx("شخص جديد", "A new person") to {
            val et = android.widget.EditText(this).apply { hint = tx("اسمه", "Their name"); setTextColor(Color.WHITE) }
            AlertDialog.Builder(this).setTitle(tx("اسم الشخص", "Person's name")).setView(et).setPositiveButton(tx("ضيف", "Add")) { _, _ ->
                setup.people.add(PodPerson(et.text.toString().trim().ifBlank { tx("شخص ${setup.people.size + 1}", "Person ${setup.people.size + 1}") }, l.id, l.id))
                save(); buildPeople(); devSig = ""; then()
            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
        }
        for (p in setup.people.filter { it.camDev.isEmpty() }) opts += tx("كاميرا ${p.name}", "${p.name}'s camera") to { p.camDev = l.id; if (p.micDev.isEmpty()) p.micDev = l.id; save(); buildPeople(); devSig = ""; then() }
        opts += tx("كاميرا عامة (بتجيب الكل)", "Wide camera (everyone)") to { setup.wides.add(l.id); save(); buildPeople(); devSig = ""; then() }
        opts += tx("ابدأ بدونه", "Start without it") to { skipLoose.add(l.id); then() }
        AlertDialog.Builder(this).setTitle(tx("«${l.name}» متصل بس مش مربوط بحدا — شو هو؟", "“${l.name}” is connected but not assigned — what is it?"))
            .setItems(opts.map { it.first }.toTypedArray()) { _, k -> opts[k].second() }.show()
    }

    private fun startRec() {
        val sid = PodRec.start(this, setup)
        lastSid = sid
        getSharedPreferences("reaction_podcast", MODE_PRIVATE).edit().putString("lastSid", sid).apply()
        devSig = ""
        render()
        startActivity(Intent(this, MonitorActivity::class.java))
    }

    private fun stopRec() {
        PodRec.stop(this)
        toast(tx("حُفظ على كل جوال — جارٍ جمع الملفات", "Saved on every phone — collecting the files"))
        devSig = ""
        render()
    }

    private fun mark(kind: String) {
        PodRec.mark(this, kind)
        toast(if (kind == "star") "⭐ " + tx("سُجّلت لحظة مهمة", "Key moment marked") else "✖ " + tx("سُجّلت علامة غلطة (لن يُحذف شيء تلقائيًا)", "Mistake marked (nothing is deleted automatically)"))
    }

    private fun render() {
        val r = PodRec.active != null
        // الشاشة بتلف مع الجوال، وبتثبت وقت التسجيل
        val want = if (r) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        if (requestedOrientation != want) requestedOrientation = want
        liveBanner.visibility = if (r) View.VISIBLE else View.GONE
        recBtn.text = if (r) tx("افتح شاشة التسجيل", "Open the recording screen") else "●  " + tx("ابدأ التسجيل للكل", "Start recording on all")
        recBtn.background = UiKit.bg(if (r) UiKit.RED else UiKit.ACCENT, 16, this)
        recBtn.setTextColor(Color.parseColor(if (r) "#FFFFFF" else UiKit.ON_ACCENT))
        if (r) {
            val sec = PodRec.elapsedSec(this)
            recTv.text = "● " + String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
            recTv.setTextColor(Color.parseColor(UiKit.RED))
            recTv.visibility = View.VISIBLE
        } else recTv.visibility = View.GONE
    }

    // ───────── تحديث دوري ─────────

    private val tick = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) refresh()
            ui.postDelayed(this, 300)
        }
    }

    private var readySig = ""

    private fun refresh() {
        val conn = SatHub.links.values.count { it.connected }
        hubTv.text = if (SatHub.running) tx("الاستقبال شغّال · متصل: $conn", "Receiving · connected: $conn") else tx("تشغيل الاستقبال…", "Starting…")
        devTitle.text = tx("الأجهزة", "Devices") + " · $conn"
        val ncam = SatHub.links.values.count { it.connected && it.role == "both" }
        val stats = listOf(tx("${setup.people.size} أشخاص", "${setup.people.size} people") to UiKit.TEXT, tx("$conn أجهزة", "$conn devices") to (if (conn > 0) UiKit.GREEN else UiKit.ORANGE), tx("$ncam كاميرا", "$ncam cameras") to UiKit.SUB)
        val ss = stats.joinToString("|") { it.first }
        if (ss != statsSig) {
            statsSig = ss
            statsRow.removeAllViews()
            for ((t, c) in stats) statsRow.addView(UiKit.badge(this, t, c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        val rec = PodRec.active != null
        val sig = SatHub.links.values.joinToString("|") { "${it.id}${it.connected}${it.role}${it.name}${it.licLeft > 0}" } + setup.people.size + rec
        if (sig != devSig) {
            devSig = sig
            buildPeople()
            buildThisPhone()
            buildDevList()
            for (l in SatHub.links.values) {
                if (l.role == "both") SatHub.setLightPreview(l.id, true, if (rec) 1000 else 500, 240)
                else SatHub.setPreview(l.id, l.stripOn, false, true)
            }
        }
        // بطاقات الأجهزة: صورة، مستوى، حالة
        val lastS = lastSid?.let { sid -> if (PodRec.active == sid) null else SessionStore.load(this, sid) }
        for ((id, dc) in devCards) {
            val l = SatHub.links[id] ?: continue
            val f = l.frame
            if (dc.img != null && f != null && dc.seen != l.frameAt) { dc.img.setImageBitmap(f); dc.seen = l.frameAt }
            dc.lvl.progress = if (l.connected) (l.level * 100 / 32767).coerceIn(0, 100) else 0
            val got = lastS?.devices?.get(id)?.uploaded == true
            val (txt, color) = when {
                !l.connected && l.recording -> tx("انقطع — آخر حالة: كان يسجّل", "Dropped — last known: recording") to UiKit.ORANGE
                !l.connected -> tx("غير متصل", "Offline") to UiKit.ORANGE
                l.recording -> "● " + tx("يسجّل", "Recording") to UiKit.RED
                l.transfer.isNotEmpty() -> tx("بيبعت ملفاته ", "Sending files ") + l.transfer to UiKit.BLUE
                got -> "✓ " + tx("متصل · وصلت ملفاته", "Connected · files received") to UiKit.BLUE
                else -> "● " + tx("متصل · جاهز", "Connected · ready") to UiKit.GREEN
            }
            if (dc.pill.text != txt) { dc.pill.text = txt; val nb = UiKit.badge(this, txt, color); dc.pill.setTextColor(nb.currentTextColor); dc.pill.background = nb.background }
            val warn = ArrayList<String>()
            if (l.battery in 0..19) warn += tx("البطارية ${l.battery}% — وصّله بالشاحن", "Battery ${l.battery}% — plug it in")
            if (l.freeMb in 0..1999) warn += tx("المساحة قليلة", "Low storage")
            val base = listOfNotNull(if (l.battery >= 0) tx("البطارية ${l.battery}%", "Battery ${l.battery}%") else null,
                if (l.freeMb >= 0) tx("المساحة ", "Storage ") + String.format(Locale.US, "%.1fGB", l.freeMb / 1024f) else null).joinToString(" · ")
            dc.info.text = if (warn.isNotEmpty()) warn.joinToString(" · ") else base
            dc.info.setTextColor(Color.parseColor(if (warn.isNotEmpty()) UiKit.ORANGE else UiKit.SUB))
        }
        for ((i, pb) in levelBars) {
            val l = SatHub.links[setup.people.getOrNull(i)?.micDev ?: ""]
            pb.progress = if (l != null && l.connected) (l.level * 100 / 32767).coerceIn(0, 100) else 0
        }
        updateDemo()
        renderReady()
        render()
        renderLast()
    }

    private fun updateDemo() {
        val ppl = setup.people
        var por = 0; var land = 0
        for (p in ppl) { val f = SatHub.links[p.camDev]?.frame ?: continue; if (f.width > f.height) land++ else por++ }
        if (demoTpl == null) demoTpl = TemplateStore.resolve(this, null)
        // شكل الفيديو من القالب (مش من مسكة الجوال هلأ) — الافتراضي بالعرض
        val landscape = demoTpl?.orient != "v"
        val phonePortrait = por > 0 || (land + por == 0 && resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        val warn = landscape && phonePortrait
        demoWarn.visibility = if (warn) View.VISIBLE else View.GONE
        demoPortraitBtn?.visibility = if (warn) View.VISIBLE else View.GONE
        if (warn) demoWarn.text = tx("الفيديو بالعرض (حسب القالب)، وهاد على أساس إنك رح تقلب الجوال بالعرض قبل ما تبلّش. إذا ضل جوال بالطول: صورته طولية بفيديو عرضي — يا بتطلع صغيرة بالنص وحواليها خلفية (الإطار «ذكي» أو «كاملة»)، يا بتنقص كثير من فوق وتحت (لو «ملء»). بتقدر تصلّحها بعدين من «المصادر: تدوير وقص».",
            "The video is landscape (from the template), assuming you'll turn the phone sideways before you start. If a phone stays upright, its picture is portrait inside a landscape video — either small in the middle with a background (framing “smart”/“whole”), or heavily cropped top and bottom (“fill”). You can fix it later in “Sources: rotate & crop”.")
        demo.names = ppl.map { it.name }
        demo.hasCam = ppl.map { it.camDev.isNotEmpty() }
        demo.pip = setup.usePip; demo.boxes = setup.multiStyle == "boxes"; demo.vlogLayout = null; demo.glow = false
        demo.tpl = demoTpl
        if (demo.landscape != landscape) { demo.landscape = landscape; demo.requestLayout() }
    }

    /** ملخص الجاهزية قبل الزر: من الحالة الفعلية للأجهزة */
    private fun renderReady() {
        val lines = ArrayList<Pair<Boolean, String>>()
        val miss = PodRec.check(setup)
        val used = (setup.people.flatMap { listOf(it.camDev, it.micDev) } + setup.wides).filter { it.isNotEmpty() }.toSet()
        val okN = used.count { SatHub.links[it]?.connected == true }
        if (used.isEmpty()) lines += false to tx("ما في أجهزة مختارة للأشخاص — اختار كاميرا ومايك لكل شخص", "No devices picked for people — choose a camera and mic for each")
        else if (miss.isEmpty()) lines += true to tx("$okN أجهزة متصلة وجاهزة", "$okN devices connected and ready")
        for (m in miss) lines += false to m
        val cams = setup.people.mapNotNull { SatHub.links[it.camDev]?.frame }
        if (cams.isNotEmpty()) {
            val l = cams.count { it.width > it.height }
            lines += (l == 0 || l == cams.size) to (if (l == cams.size) tx("الكاميرات بالعرض 16:9 بدون قص", "Cameras landscape 16:9, no cropping") else if (l == 0) tx("الكاميرات بالطول 9:16 بدون قص", "Cameras portrait 9:16, no cropping") else tx("الكاميرات مش كلها بنفس الاتجاه", "Cameras aren't all the same orientation"))
        }
        for (id in used) { val l = SatHub.links[id] ?: continue; if (l.connected && l.battery in 0..19) lines += false to tx("بطارية ${l.name} ضعيفة (${l.battery}%)", "${l.name}'s battery is low (${l.battery}%)") }
        for (id in used) { val l = SatHub.links[id] ?: continue; if (l.connected && l.freeMb in 0..1999) lines += false to tx("مساحة ${l.name} قليلة", "${l.name} is low on storage") }
        // المساحة بالساعات: أقل جوال كاميرا هو اللي بيحدد (1080p ≈ 5.4GB/ساعة، 720p ≈ 2.3GB/ساعة)
        val hrs = used.mapNotNull { id -> SatHub.links[id]?.takeIf { it.connected && it.role == "both" && it.freeMb > 0 } }
            .map { l -> val q = l.cfgs?.optInt("q", 720) ?: 720; (l.freeMb / 1024.0) / (if (q >= 1080) 5.4 else 2.3) }.minOrNull()
        if (hrs != null) lines += (hrs >= 1.0) to tx("المساحة بتكفي حوالي ", "Storage lasts about ") + (if (hrs >= 10) String.format(Locale.US, "%.0f", hrs) else String.format(Locale.US, "%.1f", hrs)) + tx(" ساعة تصوير", " hours of filming")
        val s = lines.joinToString("|") { "${it.first}${it.second}" }
        if (s == readySig) return
        readySig = s
        while (readyBox.childCount > 1) readyBox.removeViewAt(1)
        for ((ok, t) in lines) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            r.addView(ImageView(this).apply { setImageDrawable(UiKit.icon(this@PodcastActivity, if (ok) Ic.CHECK else Ic.ALERT, if (ok) UiKit.GREEN else UiKit.ORANGE, 20)) })
            r.addView(UiKit.text(this, t, 15f).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            readyBox.addView(r)
        }
        val allOk = lines.all { it.first }
        readyBox.background = UiKit.bg(if (allOk) UiKit.TINT_GREEN else UiKit.TINT_ORANGE, 16, this, if (allOk) "#2B4A33" else "#5A4520")
    }

    private fun buildDevList() {
        devList.removeAllViews(); devLines.clear(); devCards.clear()
        val links = SatHub.links.values.sortedBy { it.name }
        if (links.isEmpty()) {
            val e = UiKit.card(this, null, devList)
            e.addView(UiKit.text(this, tx("ما في جوالات متصلة بعد", "No phones connected yet"), 16f, UiKit.TEXT, true))
            e.addView(UiKit.text(this, tx("اضغط «إضافة جوال» واتبع الخطوات.", "Tap “Add a phone” and follow the steps."), 14f, UiKit.SUB))
            return
        }
        val ordered = links.filter { it.role == "both" } + links.filter { it.role != "both" }
        var lastKind = ""
        for (l in ordered) {
            val kind = if (l.role == "both") "cam" else "mic"
            if (kind != lastKind) {
                lastKind = kind
                devList.addView(UiKit.text(this, if (kind == "cam") tx("الكاميرات", "Cameras") else tx("المايكات", "Mics"), 13.5f, UiKit.SUB, true).apply { setPadding(dp(2), dp(12), 0, dp(2)) })
            }
            val c = UiKit.card(this, null, devList)
            val top = LinearLayout(this).apply { gravity = Gravity.TOP }
            var iv: ImageView? = null
            if (l.role == "both") {
                val box = android.widget.FrameLayout(this).apply { background = UiKit.bg("#22242A", 10, this@PodcastActivity); clipToOutline = true }
                iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
                box.addView(iv, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                box.setOnClickListener { startActivity(Intent(this, FramingActivity::class.java).putExtra("dev", l.id)) }
                top.addView(box, LinearLayout.LayoutParams(dp(144), dp(81)))
            } else top.addView(ImageView(this).apply {
                setImageDrawable(UiKit.icon(this@PodcastActivity, Ic.MIC, UiKit.SUB, 24)); scaleType = ImageView.ScaleType.CENTER; background = UiKit.bg(UiKit.CARD2, 24, this@PodcastActivity)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(12), 0, 0, 0) }
            val who = setup.people.withIndex().filter { it.value.camDev == l.id || it.value.micDev == l.id }.joinToString("، ") { it.value.name }
                .ifBlank { if (l.id in setup.wides) tx("الكاميرا العامة", "Wide camera") else "" }
            t.addView(UiKit.text(this, if (who.isNotBlank()) "$who · ${l.name}" else l.name, 16f, UiKit.TEXT, true))
            val q = l.cfgs?.optInt("q", 0) ?: 0
            t.addView(UiKit.text(this, (if (l.role == "both") tx("كاميرا + مايك", "Camera + mic") else tx("مايك بس", "Mic only")) + (if (l.role == "both" && q > 0) " · ${q}p" else ""), 13.5f, UiKit.SUB))
            val pill = UiKit.badge(this, "", UiKit.GREEN)
            t.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            top.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(top)
            val lvl = UiKit.level(this)
            c.addView(lvl, UiKit.full(this, lvl, 10, 6))
            val info = UiKit.text(this, "", 13.5f, UiKit.SUB)
            c.addView(info, UiKit.full(this, info, 6))
            val act = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            act.addView(UiKit.chipIcon(this, Ic.SLIDERS, tx("إعدادات الجهاز", "Device settings")) { startActivity(Intent(this, StudioActivity::class.java).putExtra("dev", l.id)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            if (l.role == "both") act.addView(UiKit.chipIcon(this, Ic.FRAME, tx("الإطار", "Frame")) { startActivity(Intent(this, FramingActivity::class.java).putExtra("dev", l.id)) })
            if (l.role == "both" && PodRec.active == null && setup.people.none { it.camDev == l.id }) {
                val isWide = l.id in setup.wides
                act.addView(UiKit.chip(this, if (isWide) tx("✓ العامة", "✓ Wide") else tx("خليه العامة", "Make wide"), isWide) {
                    if (isWide) setup.wides.remove(l.id) else setup.wides.add(l.id)
                    save(); buildPeople(); devSig = ""
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            }
            PodUi.licenseButton(this, l) { ui.postDelayed({ devSig = "" }, 700) }?.let { act.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }) }
            c.addView(act, UiKit.full(this, act, 8))
            devCards[l.id] = DevCard(iv, pill, lvl, info)
        }
    }

    private fun renderLast() {
        val sid = lastSid
        if (sid == null || PodRec.active == sid) { lastTv.visibility = View.GONE; return }
        val s = SessionStore.load(this, sid)
        if (s == null) { lastTv.visibility = View.GONE; return }
        lastTv.visibility = View.VISIBLE
        val waiting = s.devices.values.filter { it.started && !it.uploaded }
        lastTv.text = tx("آخر تسجيل: ", "Last recording: ") + if (waiting.isEmpty()) "✓ " + tx("وصلت كل الملفات — اضغط للمونتاج", "all files received — tap to edit")
        else tx("بانتظار ملفات: ", "waiting for files: ") + waiting.joinToString("، ") { it.name } + tx(" (بتوصل لحالها لما يتصلوا)", " (they arrive automatically when connected)")
        lastTv.setTextColor(Color.parseColor(if (waiting.isEmpty()) UiKit.GREEN else UiKit.ORANGE))
    }

    companion object {
        fun qrBitmap(payload: String): android.graphics.Bitmap? = try {
            val bm = com.google.zxing.qrcode.QRCodeWriter().encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
            val px = IntArray(512 * 512)
            for (y in 0 until 512) for (x in 0 until 512) px[y * 512 + x] = if (bm.get(x, y)) Color.BLACK else Color.WHITE
            android.graphics.Bitmap.createBitmap(px, 512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) { null }
    }
}

/** أدوات واجهة مشتركة للبودكاست */
object PodUi {
    /** لصاحب التطبيق فقط: زر 🔓 يفعّل نسخة الجهاز المتصل يومًا، أو 🔒 يقفلها */
    fun licenseButton(a: AppCompatActivity, l: SatHub.Link, after: () -> Unit): View? {
        if (!License.isOwner(a) || !l.connected || l.licOwner || l.id == SatCfg.load(a).devId) return null
        val on = l.licLeft > 0
        return UiKit.chipIcon(a, if (on) Ic.LOCK else Ic.UNLOCK, if (on) tx("اقفل", "Lock") else tx("فعّل", "Activate")) {
            val ok = if (on) SatHub.revokeLicense(l.id) else SatHub.sendLicense(l.id, License.DAY)
            Toast.makeText(a, if (!ok) tx("تعذر الإرسال", "Could not send") else if (on) tx("أُقفلت نسخته", "Their copy is locked") else tx("فُعّلت نسخته ليوم", "Activated for 1 day"), Toast.LENGTH_SHORT).show()
            after()
        }
    }

    /** إعداد الخدمة الخارجية الاختيارية (تفريغ الكلام وفهم الحوار والقوالب بالكلام) */
    fun aiDialog(a: AppCompatActivity, after: (() -> Unit)? = null) {
        val c = PodAI.cfg(a)
        UiKit.sheet(a, tx("خدمة الذكاء الاصطناعي", "AI service")) { col, dlg ->
            fun dp(v: Int) = UiKit.dp(a, v)
            col.addView(UiKit.text(a, tx("اختياري. بتفيد بـ: تفريغ الكلام (للكتابة على الفيديو)، فهم الحوار واختيار ريلز كاملة المعنى، تحديد الكلمات المهمة للتقريب والتلوين، وعمل قالب من وصفك بالكلام. المونتاج الأساسي بيشتغل بدونها.\n" +
                "• ما في إشي بينرفع إلا لما تضغط وتوافق. التفريغ بيرفع صوت الحلقة بس (مش الفيديو).\n• المفتاح محفوظ على هالجوال بس.",
                "Optional. Used for: transcription (captions), understanding the conversation and picking complete reels, choosing key words for punch-ins and highlights, and building a template from your description. Basic editing works without it.\n" +
                    "• Nothing is uploaded unless you tap and agree. Transcription uploads the episode audio only (not video).\n• The key stays on this phone."), 13f, UiKit.SUB))
            var prov = c.prov
            val provRow = LinearLayout(a)
            col.addView(provRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            val gBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            val oBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            col.addView(gBox); col.addView(oBox)
            fun field(box: LinearLayout, label: String, value: String, secret: Boolean = false): EditText {
                box.addView(UiKit.text(a, label, 13f, UiKit.SUB).apply { setPadding(0, dp(10), 0, 0) })
                val e = EditText(a).apply {
                    setText(value); setSingleLine(); textDirection = View.TEXT_DIRECTION_LTR; setTextColor(Color.parseColor(UiKit.TEXT))
                    if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                box.addView(e)
                return e
            }
            gBox.addView(UiKit.text(a, tx("Gemini من Google: بدك مفتاح بس (من aistudio.google.com ← Get API key). منيح باللهجات العربية، وفي استعمال مجاني محدود.", "Google Gemini: only a key is needed (aistudio.google.com → Get API key). Good with Arabic dialects; limited free use."), 13f, UiKit.TEXT).apply { setPadding(0, dp(10), 0, 0) })
            val gKey = field(gBox, tx("مفتاح Gemini", "Gemini API key"), if (c.gemini) c.key else "", true)
            val gModel = field(gBox, tx("النموذج (إذا طلع خطأ إنه مش موجود، غيّره)", "Model (change it if you get a not-found error)"), c.gmodel)
            val base = field(oBox, tx("عنوان الخدمة (Base URL)", "Service address (Base URL)"), c.base)
            val key = field(oBox, tx("المفتاح (API key)", "API key"), if (!c.gemini) c.key else "", true)
            val stt = field(oBox, tx("نموذج التفريغ (OpenAI: whisper-1 — Groq: whisper-large-v3)", "Transcription model (OpenAI: whisper-1 — Groq: whisper-large-v3)"), c.stt)
            val chat = field(oBox, tx("نموذج فهم الحوار (gpt-4o-mini أو llama-3.3-70b-versatile)", "Understanding model (gpt-4o-mini or llama-3.3-70b-versatile)"), c.chat)
            val presets = LinearLayout(a)
            presets.addView(UiKit.chip(a, "OpenAI") { base.setText("https://api.openai.com/v1"); stt.setText("whisper-1"); chat.setText("gpt-4o-mini") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            presets.addView(UiKit.chip(a, "Groq") { base.setText("https://api.groq.com/openai/v1"); stt.setText("whisper-large-v3"); chat.setText("llama-3.3-70b-versatile") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            oBox.addView(presets, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            val lang = field(col, tx("لغة الكلام (ar)", "Spoken language (ar)"), c.lang)
            fun renderProv() {
                provRow.removeAllViews()
                for ((k, n) in listOf("gemini" to "Gemini", "openai" to tx("متوافق مع OpenAI", "OpenAI-compatible")))
                    provRow.addView(UiKit.chip(a, n, prov == k) { prov = k; renderProv() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
                gBox.visibility = if (prov == "gemini") View.VISIBLE else View.GONE
                oBox.visibility = if (prov == "gemini") View.GONE else View.VISIBLE
            }
            renderProv()
            val save = UiKit.primary(a, tx("حفظ", "Save")) {
                val g = prov == "gemini"
                PodAI.save(a, AiCfg(base.text.toString(), (if (g) gKey else key).text.toString(), stt.text.toString(), chat.text.toString(), lang.text.toString(), prov, gModel.text.toString()))
                UiKit.done(a, tx("انحفظ", "Saved")); dlg.dismiss(); after?.invoke()
            }
            col.addView(save, UiKit.full(a, save, 14))
            val rm = UiKit.chip(a, tx("احذف المفتاح", "Remove key")) { PodAI.save(a, AiCfg(c.base, "", c.stt, c.chat, c.lang, prov, c.gmodel)); dlg.dismiss(); after?.invoke() }
            col.addView(rm, UiKit.full(a, rm, 8))
        }
    }

    /** يعرض نتيجة آخر تصدير: تشغيل/مشاركة لكل ملف + الخطأ والتنبيهات */
    /** زر بنفس شكل التطبيق الجديد (بدل زر أندرويد الرمادي) — main = أصفر */
    private fun rbtn(a: AppCompatActivity, main: Boolean = false) = TextView(a).apply {
        isAllCaps = false; gravity = Gravity.CENTER; textSize = 14f; typeface = Fonts.semi
        setTextColor(Color.parseColor(if (main) UiKit.ON_ACCENT else UiKit.TEXT))
        val d = a.resources.displayMetrics.density
        minHeight = (46 * d).toInt(); setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        background = UiKit.ripple(UiKit.bg(if (main) UiKit.ACCENT else UiKit.CARD2, 12, a, if (main) null else UiKit.LINE), main)
    }
    private fun rlp(a: AppCompatActivity) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        val m = (3 * a.resources.displayMetrics.density).toInt(); setMargins(m, m * 2, m, m)
    }

    fun results(a: AppCompatActivity, box: LinearLayout) {
        box.removeAllViews()
        fun tv(s: String, c: String, size: Float = 13f) = TextView(a).apply { text = s; textSize = size; setTextColor(Color.parseColor(c)); setPadding(0, 8, 0, 4) }
        PodState.error?.let { box.addView(tv("⚠ $it", "#FF6B6B")) }
        // لسا مش بالمعرض: شوفه، وبعدين احفظه (نفس الملف) أو ارمِه
        if (PodState.held && PodState.results.isNotEmpty()) {
            box.addView(tv(if (PodState.light) tx("👁 جاهزة النسخة الخفيفة (360p) للحلقة كاملة — مع الإنترو والفاصل والأوترو. شوفها:", "👁 The light (360p) full-episode copy is ready — with intro, bridge and outro. Watch it:")
                else tx("✓ الفيديو جاهز — بس لسا مش بالمعرض. شوفه، وإذا عجبك احفظه (نفس الملف بالزبط، ما بينعمل من جديد):", "✓ The video is ready — not in the gallery yet. Watch it, and if you like it save it (the very same file, not re-made):"), "#7CFC9A", 14f))
            for ((i, u) in PodState.results.withIndex()) {
                val tag = if (PodState.results.size > 1) (if (i == 0) " 16:9" else " 9:16") else ""
                val row = LinearLayout(a)
                fun b(label: String, run: () -> Unit) = row.addView(rbtn(a, label.startsWith("✓")).apply { text = label; setOnClickListener { run() } }, rlp(a))
                b("▶ " + tx("شوفه", "Watch") + tag) { a.startActivity(Intent(a, PodPreviewActivity::class.java).putExtra("uri", u)) }
                b("✓ " + tx("احفظه بالمعرض", "Save to gallery")) {
                    try { StorageUtil.publish(a, Uri.parse(u)) } catch (_: Throwable) {}
                    if (PodState.project == "episode" && !PodState.light) try { SessionStore.update(a, PodState.sid) { x -> x.mergedUri = u; x.merged = true } } catch (_: Throwable) {}
                    PodState.results = PodState.results - u
                    if (PodState.results.isEmpty()) PodState.held = false
                    PodState.saved = PodState.saved + u
                    results(a, box)
                }
                b("🗑 " + tx("ارمِه", "Discard")) {
                    StorageUtil.delete(a, Uri.parse(u))
                    PodState.results = PodState.results - u
                    if (PodState.results.isEmpty()) PodState.held = false
                    results(a, box)
                }
                box.addView(row)
            }
            for (w in PodState.warnings) box.addView(tv("• $w", "#FFB86B", 11f))
            return
        }
        for ((i, u) in (PodState.saved + PodState.results).distinct().withIndex()) {
            box.addView(tv("✓ " + tx("حُفظ في Movies/ReactionRecorder", "Saved to Movies/ReactionRecorder") + if (PodState.results.size > 1) (if (i == 0) " (16:9)" else " (9:16)") else "", "#7CFC9A"))
            val row = LinearLayout(a)
            row.addView(rbtn(a).apply {
                text = "▶ " + tx("تشغيل", "Play")
                setOnClickListener { try { a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(u), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Throwable) {} }
            }, rlp(a))
            row.addView(rbtn(a).apply {
                text = "↗ " + tx("مشاركة", "Share")
                setOnClickListener {
                    val sh = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(u)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    a.startActivity(Intent.createChooser(sh, tx("مشاركة", "Share")))
                }
            }, rlp(a))
            box.addView(row)
        }
        for (w in PodState.warnings) box.addView(tv("• $w", "#FFB86B", 11f))
    }

    fun pacingDialog(a: AppCompatActivity, init: PodPacing, onSave: (PodPacing) -> Unit) {
        val p = init.copy()
        val d = a.resources.displayMetrics.density
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((18 * d).toInt(), (8 * d).toInt(), (18 * d).toInt(), (8 * d).toInt()) }
        fun slider(title: String, min: Float, max: Float, get: () -> Float, set: (Float) -> Unit) {
            val tv = TextView(a).apply { textSize = 13f; setPadding(0, (10 * d).toInt(), 0, 0) }
            fun upd() { tv.text = "$title: ${String.format(Locale.US, "%.1f", get())} s" }
            upd()
            col.addView(tv)
            col.addView(SeekBar(a).apply {
                this.max = 100
                progress = (((get() - min) / (max - min)) * 100).toInt().coerceIn(0, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { set(min + (max - min) * v / 100f); upd() }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        slider(tx("كلام لازم قبل التبديل", "Speech needed before switching"), 0.2f, 3f, { p.switchDelay }, { p.switchDelay = it })
        slider(tx("أقصر لقطة", "Shortest shot"), 0.5f, 6f, { p.minShot }, { p.minShot = it })
        slider(tx("تجاهل الأصوات الأقصر من (آه، امم)", "Ignore sounds shorter than (uh, hmm)"), 0.1f, 1.5f, { p.minSpeech }, { p.minSpeech = it })
        slider(tx("أطول لقطة قبل لقطة عامة (0 = أبدًا)", "Longest shot before a wide shot (0 = never)"), 0f, 60f, { p.maxShot }, { p.maxShot = it })
        slider(tx("صمت قبل إظهار الكاميرا العامة", "Silence before showing the wide camera"), 1f, 10f, { p.silenceToWide }, { p.silenceToWide = it })
        AlertDialog.Builder(a).setTitle(tx("إيقاع المونتاج", "Editing pace")).setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton(tx("حفظ", "Save")) { _, _ -> onSave(p) }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }
}
