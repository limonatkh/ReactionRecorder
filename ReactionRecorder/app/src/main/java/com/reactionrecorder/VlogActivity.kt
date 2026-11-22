package com.reactionrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * فلوق: جوالك كاميرا، وصحابك مايكات لاسلكية (أو كاميرات) — كل جوال بيسجّل عنده بجودة كاملة.
 * أول ما تفوت: «صوّر» و«اربط مايك لاسلكي» قدامك. التصوير بملء الشاشة: زوم بإصبعين، تركيز بلمسة،
 * إضاءة وفلاش وقلب ومايكك، إيقاف مؤقت، وإيقاف نهائي بضغطة ثانية. حالة الأجهزة فوق يمين كنص بدون خلفية.
 * ولا مربع عائم: القالبين «بدون مونتاج» و«ثابت — كل الكاميرات» (مقسوم).
 */
class VlogActivity : BaseActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var setup: PodSetup
    private val vp by lazy { getSharedPreferences("reaction_vlog", MODE_PRIVATE) }

    // الكاميرا (نفس العناصر بتنتقل بين الصفحة وملء الشاشة)
    private lateinit var camBox: FrameLayout
    private lateinit var camImg: ImageView
    private lateinit var camSurface: SurfaceView
    private lateinit var camHint: TextView

    // الصفحة العادية
    private lateinit var root: FrameLayout
    private lateinit var pageSv: ScrollView
    private lateinit var finder: FrameLayout
    private lateinit var shootBtn: TextView
    private lateinit var devBox: LinearLayout
    private lateinit var tplBox: LinearLayout
    private lateinit var lastTv: TextView
    private lateinit var pageState: TextView

    // ملء الشاشة
    private lateinit var full: FrameLayout
    private lateinit var fullCam: FrameLayout
    private lateinit var topLeft: LinearLayout
    private lateinit var recTv: TextView
    private lateinit var devStatus: TextView
    private lateinit var pausedTv: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var ctlRow: LinearLayout
    private lateinit var evRow: LinearLayout
    private lateinit var evBar: SeekBar
    private lateinit var mainRow: LinearLayout
    private lateinit var thumbs: LinearLayout
    private lateinit var focusRing: View
    private lateinit var bottomBox: LinearLayout
    private var isFull = false

    private var sig = ""
    private var seenLocal = 0L
    private val thumbViews = HashMap<String, ImageView>()
    private val thumbSeen = HashMap<String, Long>()
    private val levelBars = HashMap<String, ProgressBar>()
    private val devLines = HashMap<String, TextView>()
    private var liveIdx = 0
    private var lastSid: String? = null
    private val personOf = HashMap<String, Int>()
    private var qrImg: ImageView? = null
    private var qrList: TextView? = null
    private var qrDlg: android.app.Dialog? = null
    private var qrFor = ""

    // تحكم الكاميرا (جوالي)
    private var zoom = 1f
    private var lastZoomSent = 0L
    private var ctlSig = ""

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ensureThisPhone() }

    private fun dp(v: Int) = UiKit.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private val myId: String get() = SatCfg.load(this).devId
    private val rec get() = PodRec.active != null
    private var myMic: Boolean
        get() = vp.getBoolean("myMic", true)
        set(v) { vp.edit().putBoolean("myMic", v).apply() }

    /** دور كل جهاز حسب اختيارك (الآدمن): mic / cam / both — الافتراضي حسب ما انضم */
    private fun roleOf(l: SatHub.Link): String = vp.getString("role_${l.id}", null) ?: if (l.role == "both") "both" else "mic"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setup = PodSetup.loadDefault(this, "vlog").also { it.vlog = true }
        if (setup.vlogLayout !in setOf("tpl", "plain", "grid")) { setup.vlogLayout = "tpl"; save() }
        build()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (isFull) setFull(false) else finish() }
        })
        val c = applicationContext
        Thread {
            try { if (!SatHub.running) { SatHub.start(c); SatHub.setHubWanted(c, SatHub.running) } } catch (_: Throwable) {}
            ui.post { askThenStart() }
        }.start()
        if (rec) ui.post { setFull(true) }
    }

    override fun onResume() { super.onResume(); sig = ""; ctlSig = ""; ui.post(tick) }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        for (l in SatHub.links.values) SatHub.setPreview(l.id, l.stripOn, false, l.stripOn)
    }

    override fun onDestroy() {
        super.onDestroy()
        PreviewBridge.detach()
        try { qrDlg?.dismiss() } catch (_: Throwable) {}
    }

    private fun save() = PodSetup.saveDefault(this, setup, "vlog")

    // ───────── هذا الجوال = الكاميرا الرئيسية ─────────

    private fun askThenStart() {
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
        if (!granted(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) perms.launch(need.toTypedArray()) else ensureThisPhone()
    }

    private fun ensureThisPhone() {
        if (!SatHub.running) { ui.postDelayed({ ensureThisPhone() }, 800); return }
        if (RecorderState.isBusy) { toast(tx("أوقف تسجيل الرياكشن أولًا", "Stop the reaction recording first")); return }
        val c = SatCfg.load(this)
        val ok = SatState.serviceOn && c.role == "both" && c.host.startsWith("127.0.0.1")
        if (ok) return
        if (PodRec.active != null) return
        val restart = SatState.serviceOn
        c.host = "127.0.0.1:${SatHub.port}"; c.pin = SatHub.pin; c.hubId = SatHub.hubId
        c.name = tx("أنا", "Me"); c.role = "both"
        // الفلوق: كاميرتك الأساسية بجودة 1080p (أوضح بكثير من 720p لما تتكبّر للفيديو)
        c.shortSide = vp.getInt("q", 1080); c.camLong = if (c.shortSide == 1080) 1920 else 1280
        SatCfg.save(this, c)
        if (restart) {
            startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_DISCONNECT))
            ui.postDelayed({ if (!isFinishing) ContextCompat.startForegroundService(this, Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_CONNECT)) }, 2000)
        } else ContextCompat.startForegroundService(this, Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_CONNECT))
    }

    // ───────── البناء ─────────

    /** نص ما بيعيد الرسم إذا ما تغيّر (الشاشة بتتحدث كل ربع ثانية) */
    private fun qtext(s: String, size: Float, color: String = UiKit.TEXT, bold: Boolean = false): TextView = QuietText(this).apply {
        text = s; textSize = size; setTextColor(Color.parseColor(color)); typeface = if (bold) Fonts.semi else Fonts.regular
    }

    private fun shadowText(s: String, size: Float, color: String = "#FFFFFF", bold: Boolean = false) = qtext(s, size, color, bold).apply {
        setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xE6000000.toInt())
    }

    /** زر دائري شفاف فوق الكاميرا */
    private fun glassBtn(path: String, desc: String, size: Int = 48, run: () -> Unit) = ImageView(this).apply {
        setImageDrawable(UiKit.icon(this@VlogActivity, path, "#FFFFFF", 22))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = desc
        background = UiKit.ripple(UiKit.bg("#59000000", size / 2, this@VlogActivity, "#33FFFFFF"))
        isClickable = true; setOnClickListener { run() }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(5), 0, dp(5), 0) }
    }

    private fun build() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor(UiKit.BG)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(28)) }
        pageSv = ScrollView(this).apply { isFillViewport = true; addView(col) }
        root.addView(pageSv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(pageSv) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }
        col.addView(UiKit.header(this, "🎥 " + tx("فلوق", "Vlog")))

        // الكاميرا
        camImg = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
        camSurface = SurfaceView(this).apply { visibility = View.GONE }
        camSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { PreviewBridge.attach(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { PreviewBridge.attach(h.surface) }
            override fun surfaceDestroyed(h: SurfaceHolder) { PreviewBridge.detach() }
        })
        camHint = UiKit.text(this, tx("تجهيز الكاميرا…", "Preparing the camera…"), 13f, "#FFFFFF").apply { gravity = Gravity.CENTER }
        camBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        camBox.addView(camImg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        camBox.addView(camSurface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        camBox.addView(camHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        finder = FrameLayout(this).apply {
            background = UiKit.bg("#000000", 18, this@VlogActivity); clipToOutline = true
            contentDescription = tx("الكاميرا — اضغط لملء الشاشة", "Camera — tap for full screen")
            setOnClickListener { setFull(true) }
        }
        finder.addView(camBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        finder.addView(View(this).apply { isClickable = true; setOnClickListener { setFull(true) } }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val expand = shadowText("⤢  " + tx("ملء الشاشة", "Full screen"), 12.5f, "#FFFFFF", true).apply { setPadding(dp(10), dp(5), dp(10), dp(5)); background = UiKit.bg("#66000000", 12, this@VlogActivity) }
        finder.addView(expand, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
        col.addView(finder, UiKit.full(this, finder, 4, 300))

        pageState = qtext("", 13f, UiKit.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        col.addView(pageState)

        // الخيار المبدئي: صوّر + اربط مايك لاسلكي
        shootBtn = QuietText(this).apply {
            text = tx("صوّر", "Shoot"); textSize = 18f; typeface = Fonts.bold; gravity = Gravity.CENTER
            setTextColor(Color.parseColor(UiKit.ON_ACCENT)); contentDescription = tx("صوّر", "Shoot")
            setCompoundDrawablesRelativeWithIntrinsicBounds(UiKit.icon(this@VlogActivity, Ic.CAM, UiKit.ON_ACCENT, 22), null, null, null)
            compoundDrawablePadding = dp(8); setPadding(dp(16), 0, dp(16), 0)
            background = UiKit.ripple(UiKit.bg(UiKit.ACCENT, 16, this@VlogActivity), true)
            isClickable = true; setOnClickListener { setFull(true) }
        }
        col.addView(shootBtn, UiKit.full(this, shootBtn, 12, 60))
        val micBtn = UiKit.chipIcon(this, Ic.MIC, tx("اربط مايك لاسلكي", "Connect a wireless mic")) { qrSheet() }.apply { minHeight = dp(54); textSize = 15.5f }
        col.addView(micBtn, UiKit.full(this, micBtn, 8))

        // الأجهزة
        val dc = UiKit.card(this, tx("الأجهزة", "Devices"), col)
        devBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dc.addView(devBox)

        // القالب
        val tc = UiKit.card(this, tx("شكل الفيديو (القالب)", "Video look (template)"), col)
        tplBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tc.addView(tplBox)
        buildTpl()

        lastSid = getSharedPreferences("reaction_podcast", MODE_PRIVATE).getString("lastSid", null)
        lastTv = qtext("", 13f, UiKit.SUB).apply {
            background = UiKit.bg(UiKit.CARD, 14, this@VlogActivity, UiKit.LINE); setPadding(dp(14), dp(12), dp(14), dp(12)); visibility = View.GONE
            setOnClickListener { lastSid?.let { startActivity(Intent(this@VlogActivity, SessionActivity::class.java).putExtra("session", it)) } }
        }
        col.addView(lastTv, UiKit.full(this, lastTv, 10))
        val recs = UiKit.listRow(this, Ic.FOLDER, tx("تسجيلاتي", "My recordings")) { startActivity(Intent(this, RecordingsActivity::class.java)) }
        col.addView(recs, UiKit.full(this, recs, 8))

        buildFull()
    }

    private fun buildTpl() {
        tplBox.removeAllViews()
        val cams = 1 + SatHub.links.values.count { it.connected && it.id != myId && it.role == "both" && roleOf(it) != "mic" }
        val auto = setup.vlogLayout == "tpl"
        val eff = if (auto) (if (cams >= 2) "grid" else "plain") else setup.vlogLayout
        val row = LinearLayout(this)
        for ((k, n) in listOf("plain" to tx("بدون مونتاج", "No editing"), "grid" to tx("ثابت — كل الكاميرات", "Fixed — all cameras"))) {
            row.addView(UiKit.chip(this, n, eff == k) {
                if (rec) { toast(tx("بتقدر تغيّره من المونتاج بعد التصوير", "You can change it in the edit after filming")); return@chip }
                setup.vlogLayout = if (setup.vlogLayout == k) "tpl" else k; save(); buildTpl()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        tplBox.addView(row)
        tplBox.addView(UiKit.text(this, when (eff) {
            "grid" -> tx("كل الكاميرات بالشاشة طول الوقت: كاميرتين = وحدة فوق ووحدة تحت، ٣ = مقسومة. ولا مربع عائم.", "Every camera on screen all the time: 2 = one on top, one below; 3 = split. No floating box.")
            else -> tx("كاميرتك بس مع صوت المايك اللاسلكي — بدون قص ولا تبديل.", "Just your camera with the wireless mic's sound — no cuts, no switching.")
        } + if (auto) "\n" + tx("(تلقائي حسب عدد الكاميرات — اضغط واحد لتثبّته)", "(automatic by number of cameras — tap one to fix it)") else "", 12f, UiKit.SUB).apply { setPadding(dp(2), dp(8), 0, 0) })
        val ed = UiKit.listRow(this, Ic.REELS, tx("عدّل قوالب الفلوق", "Edit vlog templates"), tx("يدوي: الأسماء، الإطار، الإيقاع…", "By hand: names, frame, pacing…")) {
            startActivity(Intent(this, TemplateActivity::class.java).putExtra("mode", "vlog"))
        }
        tplBox.addView(ed, UiKit.full(this, ed, 6))
    }

    private fun buildFull() {
        full = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isClickable = true }
        root.addView(full, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullCam = FrameLayout(this)
        full.addView(fullCam, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // لمس الكاميرا: إصبعين = زوم، لمسة = تركيز
        val touch = View(this).apply { contentDescription = tx("الكاميرا: إصبعين للزوم، لمسة للتركيز", "Camera: pinch to zoom, tap to focus") }
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { setZoom(zoom * d.scaleFactor, false); return true }
            override fun onScaleEnd(d: ScaleGestureDetector) { setZoom(zoom, true) }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusTap(e.x, e.y); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { flip(); return true }
        })
        touch.setOnTouchListener { _, e -> scale.onTouchEvent(e); if (!scale.isInProgress) tap.onTouchEvent(e); true }
        full.addView(touch, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        focusRing = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), Color.parseColor(UiKit.ACCENT)) }
            visibility = View.GONE
        }
        full.addView(focusRing, FrameLayout.LayoutParams(dp(64), dp(64)))

        // ظل خفيف فوق وتحت (عشان الكتابة تنقرأ على أي صورة)
        full.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x8C000000.toInt(), 0x00000000)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130), Gravity.TOP))
        full.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0x00000000)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300), Gravity.BOTTOM))

        // فوق يسار: خروج + الوقت
        topLeft = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        recTv = shadowText("", 16f, "#FFFFFF", true).apply { setPadding(dp(6), dp(6), 0, 0) }
        topLeft.addView(recTv)
        full.addView(topLeft, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT).apply { setMargins(dp(10), dp(10), dp(10), 0) })
        // فوق يمين: حالة الأجهزة — نص بدون خلفية
        devStatus = shadowText("", 13f, "#FFFFFF", true).apply { gravity = Gravity.RIGHT; setLineSpacing(dp(2).toFloat(), 1f); textDirection = View.TEXT_DIRECTION_ANY_RTL }
        full.addView(devStatus, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.RIGHT).apply { setMargins(dp(70), dp(14), dp(14), 0) })
        pausedTv = shadowText("⏸  " + tx("متوقف مؤقتًا", "Paused") + "\n" + tx("هالجزء ما رح يطلع بالفيديو", "This part won't be in the video"), 17f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), dp(12), dp(18), dp(12)); background = UiKit.bg("#8C000000", 16, this@VlogActivity); visibility = View.GONE
        }
        full.addView(pausedTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // تحت: صور صحابك، الزوم، التحكم، والأزرار الرئيسية
        bottomBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8), 0, dp(8), dp(14)) }
        thumbs = LinearLayout(this)
        bottomBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(thumbs) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        zoomRow = LinearLayout(this).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(dp(4), dp(3), dp(4), dp(3)); background = UiKit.bg("#59000000", 22, this@VlogActivity) }
        // سطر الزوم: الزوم بالنص، وزر الرجوع عاليمين (قريب من إيدك)
        val zLine = FrameLayout(this)
        zLine.addView(zoomRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        zLine.addView(glassBtn(Ic.BACK, tx("رجوع للصفحة", "Back to the page"), 48) { setFull(false) }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(8) })
        bottomBox.addView(zLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(8) })
        evRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(dp(12), dp(4), dp(12), dp(4)); background = UiKit.bg("#59000000", 18, this@VlogActivity) }
        evRow.addView(ImageView(this).apply { setImageDrawable(UiKit.icon(this@VlogActivity, Ic.SUN, "#FFFFFF", 18)) })
        evBar = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) sendMine(JSONObject().put("exp", p + evMin())) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        evRow.addView(evBar, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomBox.addView(evRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        ctlRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        bottomBox.addView(ctlRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        mainRow = LinearLayout(this).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        bottomBox.addView(mainRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)))
        full.addView(bottomBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(full) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (topLeft.layoutParams as FrameLayout.LayoutParams).topMargin = dp(10) + b.top
            (devStatus.layoutParams as FrameLayout.LayoutParams).topMargin = dp(14) + b.top
            bottomBox.setPadding(dp(8) + b.left, 0, dp(8) + b.right, dp(14) + b.bottom)
            topLeft.requestLayout(); devStatus.requestLayout(); insets
        }
    }

    private fun setFull(on: Boolean) {
        if (on == isFull) return
        isFull = on
        (camBox.parent as? ViewGroup)?.removeView(camBox)
        val target = if (on) fullCam else finder
        target.addView(camBox, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        full.visibility = if (on) View.VISIBLE else View.GONE
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            full.alpha = 0f; full.animate().alpha(1f).setDuration(180).start()
            ViewCompat.requestApplyInsets(full)
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            evRow.visibility = View.GONE
        }
        sig = ""; ctlSig = ""
        ui.post { fitCam() }
    }

    // ───────── التحكم بكاميرا جوالي ─────────

    private fun myLink() = SatHub.links[myId]
    private fun caps() = myLink()?.caps
    private fun cfgs() = myLink()?.cfgs
    private fun zMin() = (caps()?.optDouble("zmin", 1.0) ?: 1.0).toFloat()
    private fun zMax() = (caps()?.optDouble("zmax", 1.0) ?: 1.0).toFloat().coerceAtLeast(1f)
    private fun evMin() = caps()?.optInt("emin", 0) ?: 0
    private fun evMax() = caps()?.optInt("emax", 0) ?: 0

    private fun sendMine(o: JSONObject) { if (myLink()?.connected == true) SatHub.sendCfg(myId, o) }

    private fun setZoom(z: Float, force: Boolean) {
        zoom = z.coerceIn(zMin().coerceAtMost(1f), zMax())
        val now = SystemClock.uptimeMillis()
        if (force || now - lastZoomSent > 60) { lastZoomSent = now; sendMine(JSONObject().put("zoom", zoom.toDouble())) }
        renderZoom()
    }

    private fun renderZoom() {
        zoomRow.removeAllViews()
        val stops = ArrayList<Float>()
        if (zMin() < 0.95f) stops += zMin().coerceAtLeast(0.5f)
        stops += 1f
        if (zMax() >= 2f) stops += 2f
        if (zMax() >= 5f) stops += 5f else if (zMax() >= 3f) stops += zMax().coerceAtMost(4f)
        val near = stops.minByOrNull { abs(it - zoom) } ?: 1f
        for (s in stops) {
            val on = s == near
            val label = if (on && abs(zoom - s) > 0.05f) fmtZ(zoom) else fmtZ(s)
            val v = TextView(this).apply {
                text = label; textSize = if (on) 13.5f else 12f; typeface = Fonts.bold; gravity = Gravity.CENTER
                setTextColor(Color.parseColor(if (on) UiKit.ACCENT else "#FFFFFF"))
                background = if (on) UiKit.bg("#99000000", 20, this@VlogActivity) else null
                contentDescription = tx("زوم ", "Zoom ") + fmtZ(s)
                isClickable = true; setOnClickListener { animateZoom(s) }
            }
            zoomRow.addView(v, LinearLayout.LayoutParams(dp(if (on) 52 else 42), dp(38)).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        if (zMax() <= 1.01f && zMin() >= 0.99f) zoomRow.addView(UiKit.text(this, tx("الزوم مش متاح بهالكاميرا", "No zoom on this camera"), 11.5f, "#CCFFFFFF").apply { setPadding(dp(10), 0, dp(10), 0) })
    }

    private fun fmtZ(z: Float) = (if (abs(z - Math.round(z)) < 0.05f) String.format(Locale.US, "%d", Math.round(z)) else String.format(Locale.US, "%.1f", z)) + "×"

    /** الانتقال بين درجات الزوم بنعومة (مش قفزة) */
    private fun animateZoom(target: Float) {
        val from = zoom
        val va = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(220)
        va.addUpdateListener { a -> setZoom(from + (target - from) * (a.animatedValue as Float), a.animatedFraction >= 1f) }
        va.start()
    }

    private fun focusTap(x: Float, y: Float) {
        val r = camRect() ?: return
        if (!r.contains(x, y)) return
        sendMine(JSONObject().put("afx", ((x - r.left) / r.width()).toDouble()).put("afy", ((y - r.top) / r.height()).toDouble()))
        focusRing.visibility = View.VISIBLE
        focusRing.x = x - dp(32); focusRing.y = y - dp(32)
        focusRing.scaleX = 1.4f; focusRing.scaleY = 1.4f; focusRing.alpha = 1f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction { focusRing.animate().alpha(0f).setStartDelay(700).setDuration(250).start() }.start()
    }

    /** مكان الصورة الحقيقي على الشاشة (للتركيز) */
    private fun camRect(): RectF? {
        val v: View = if (camSurface.visibility == View.VISIBLE) camSurface else camImg
        if (v.width <= 0) return null
        if (v === camSurface) return RectF(v.x + camBox.x, v.y + camBox.y, v.x + camBox.x + v.width, v.y + camBox.y + v.height)
        val d = camImg.drawable ?: return RectF(0f, 0f, camImg.width.toFloat(), camImg.height.toFloat())
        val sc = minOf(camImg.width / d.intrinsicWidth.toFloat(), camImg.height / d.intrinsicHeight.toFloat())
        val w = d.intrinsicWidth * sc; val h = d.intrinsicHeight * sc
        return RectF((camImg.width - w) / 2, (camImg.height - h) / 2, (camImg.width + w) / 2, (camImg.height + h) / 2)
    }

    private fun flip() = startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_FLIP))

    private fun renderCtl() {
        val c = cfgs()
        val torch = c?.optBoolean("torch") == true
        val hasFlash = caps()?.optBoolean("flash") == true
        val s = "$torch$hasFlash${myMic}$rec${PodRec.isPaused(this)}${evMax() - evMin()}${evRow.visibility}"
        if (s == ctlSig) return
        ctlSig = s
        ctlRow.removeAllViews()
        if (hasFlash) ctlRow.addView(glassBtn(Ic.FLASH, if (torch) tx("الضو: شغّال", "Light: on") else tx("الضو: مطفي", "Light: off")) { sendMine(JSONObject().put("torch", !torch)) }.apply {
            if (torch) setImageDrawable(UiKit.icon(this@VlogActivity, Ic.FLASH, UiKit.ACCENT, 22))
        })
        if (evMax() > evMin()) ctlRow.addView(glassBtn(Ic.SUN, tx("الإضاءة", "Exposure")) {
            evRow.visibility = if (evRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            evBar.max = evMax() - evMin(); evBar.progress = (cfgs()?.optInt("exp", 0) ?: 0) - evMin(); ctlSig = ""
        })
        ctlRow.addView(glassBtn(Ic.FLIP, tx("اقلب الكاميرا", "Flip camera")) { flip() })
        ctlRow.addView(glassBtn(if (myMic) Ic.MIC else Ic.MIC_OFF, if (myMic) tx("مايك جوالي: شغّال", "My phone's mic: on") else tx("مايك جوالي: مطفي", "My phone's mic: off")) { toggleMyMic() }.apply {
            if (!myMic) background = UiKit.ripple(UiKit.bg("#B3FF453A", 24, this@VlogActivity))
        })
        if (rec) ctlRow.addView(glassBtn(Ic.STAR, tx("علامة (لحظة حلوة)", "Mark (a good moment)")) { PodRec.mark(this, "star"); toast("⭐") })

        // الأزرار الرئيسية
        mainRow.removeAllViews()
        val side = LinearLayout.LayoutParams(dp(64), dp(64)).apply { setMargins(dp(22), 0, dp(22), 0) }
        if (!rec) {
            mainRow.addView(View(this), LinearLayout.LayoutParams(dp(64), dp(64)).apply { setMargins(dp(22), 0, dp(22), 0) })
            mainRow.addView(RecButton(), LinearLayout.LayoutParams(dp(80), dp(80)))
            mainRow.addView(glassBtn(Ic.PLUS, tx("اربط مايك لاسلكي", "Connect a wireless mic"), 64) { qrSheet() }, side)
        } else {
            val paused = PodRec.isPaused(this)
            mainRow.addView(glassBtn(if (paused) Ic.PLAY else Ic.PAUSE, if (paused) tx("كمّل التسجيل", "Resume") else tx("إيقاف مؤقت", "Pause"), 64) {
                if (PodRec.isPaused(this)) PodRec.resume(this) else PodRec.pause(this); ctlSig = ""
            }, side)
            mainRow.addView(HoldStop(), LinearLayout.LayoutParams(dp(80), dp(80)))
            mainRow.addView(View(this), LinearLayout.LayoutParams(dp(64), dp(64)).apply { setMargins(dp(22), 0, dp(22), 0) })
        }
    }

    private fun toggleMyMic() {
        if (rec) { toast(tx("مايك جوالك بيتغيّر قبل التسجيل — بعد التسجيل بتقدر تطفيه من المونتاج", "Change your phone's mic before recording — afterwards you can mute it in the edit")); return }
        myMic = !myMic; ctlSig = ""; sig = ""
        toast(if (myMic) tx("مايك جوالك شغّال", "Your phone's mic is on") else tx("مايك جوالك مطفي — الصوت من المايك اللاسلكي", "Your phone's mic is off — sound from the wireless mic"))
    }

    // ───────── التسجيل ─────────

    /** الناس = أنا أولًا (كاميرتي، ومايكي إذا شغّال)، ثم كل جهاز متصل حسب الدور اللي اخترته */
    private fun buildPeople() {
        val me = myId
        setup.people.clear(); setup.wides.clear(); personOf.clear()
        setup.people.add(PodPerson(tx("أنا", "Me"), me, if (myMic) me else "")); personOf[me] = 0
        for (l in SatHub.links.values.filter { it.connected && it.use && it.id != me }.sortedBy { it.name }) {
            val r = roleOf(l)
            val cam = if (r != "mic" && l.role == "both") l.id else ""
            val mic = if (r != "cam") l.id else ""
            if (cam.isEmpty() && mic.isEmpty()) continue
            personOf[l.id] = setup.people.size
            setup.people.add(PodPerson(l.name, cam, mic))
        }
        setup.vlog = true
        save()
    }

    private fun startRec() {
        if (rec) return
        if (RecorderState.isBusy) { toast(tx("أوقف تسجيل الرياكشن أولًا", "Stop the reaction recording first")); return }
        if (myLink()?.connected != true) { toast(tx("كاميرا هذا الجوال لم تجهز بعد", "This phone's camera isn't ready yet")); ensureThisPhone(); return }
        buildPeople()
        if (setup.people.none { it.micDev.isNotEmpty() }) { toast(tx("ما في ولا مايك شغّال — شغّل مايك جوالك أو اربط مايك لاسلكي", "No mic is on — turn on your phone's mic or connect a wireless mic")); return }
        val warn = PodRec.check(setup)
        if (warn.isNotEmpty()) toast(warn.first())
        liveIdx = 0
        val sid = PodRec.start(this, setup)
        lastSid = sid
        getSharedPreferences("reaction_podcast", MODE_PRIVATE).edit().putString("lastSid", sid).apply()
        sig = ""; ctlSig = ""
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun stopRec() {
        if (!rec) return
        PodRec.stop(this)
        toast(tx("حُفظ على كل جوال — جارٍ جمع الملفات", "Saved on every phone — collecting files"))
        sig = ""; ctlSig = ""
    }

    private fun goLive(idx: Int) {
        if (!rec || idx == liveIdx) return
        if (setup.people.getOrNull(idx)?.camDev.isNullOrEmpty()) { toast(tx("هذا الجهاز مايك فقط", "This device is mic only")); return }
        liveIdx = idx
        PodRec.live(this, idx)
        sig = ""
    }

    /** زر التسجيل: دائرة حمرا كبيرة */
    private inner class RecButton : View(this@VlogActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { contentDescription = tx("سجّل", "Record"); isClickable = true; setOnClickListener { startRec() } }
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(4).toFloat(); p.color = Color.WHITE
            c.drawCircle(r, r, r - dp(3), p)
            p.style = Paint.Style.FILL; p.color = Color.parseColor(UiKit.RED)
            c.drawCircle(r, r, r - dp(9), p)
        }
    }

    /** الإيقاف النهائي: اضغط مطوّلًا ثانية وحدة — حلقة بتتعبّى وأنت ضاغط */
    private inner class HoldStop : View(this@VlogActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var start = 0L
        private var prog = 0f
        private val hold = 1000L
        private val tickR = object : Runnable {
            override fun run() {
                if (start == 0L) return
                prog = ((SystemClock.uptimeMillis() - start).toFloat() / hold).coerceIn(0f, 1f)
                invalidate()
                if (prog >= 1f) { start = 0L; prog = 0f; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); stopRec() } else postDelayed(this, 16)
            }
        }
        init { contentDescription = tx("إيقاف نهائي (اضغط مطوّلًا)", "Stop (press and hold)") }
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { start = SystemClock.uptimeMillis(); post(tickR) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val short = start != 0L && prog < 0.2f
                    start = 0L; prog = 0f; removeCallbacks(tickR); invalidate()
                    if (short && e.actionMasked == MotionEvent.ACTION_UP) toast(tx("اضغط مطوّلًا ثانية لتوقف", "Press and hold for a second to stop"))
                }
            }
            return true
        }
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(4).toFloat(); p.color = 0x66FFFFFF
            c.drawCircle(r, r, r - dp(3), p)
            if (prog > 0f) { p.color = Color.WHITE; c.drawArc(RectF(dp(3).toFloat(), dp(3).toFloat(), width - dp(3).toFloat(), height - dp(3).toFloat()), -90f, 360f * prog, false, p) }
            p.style = Paint.Style.FILL; p.color = Color.parseColor(UiKit.RED)
            val s = dp(13).toFloat()
            c.drawRoundRect(RectF(r - s, r - s, r + s, r + s), dp(5).toFloat(), dp(5).toFloat(), p)
        }
    }

    // ───────── ربط المايك اللاسلكي ─────────

    private fun qrSheet() {
        qrFor = ""
        qrDlg = UiKit.sheet(this, tx("اربط مايك لاسلكي", "Connect a wireless mic")) { col, _ ->
            col.addView(UiKit.text(this, tx("١. صاحبك يتصل بشبكة جوالك (الهوتسبوت) أو نفس الواي فاي.\n٢. يمسح الباركود: إذا ما عنده التطبيق بينزله من جوالك، وإذا عنده بيرتبط.\n٣. بيرتبط كمايك لاسلكي تلقائيًا — وبتقدر تخليه كاميرا من «الأجهزة».", "1. Your friend joins your hotspot or the same Wi-Fi.\n2. They scan the code: without the app it downloads from your phone; with it, it connects.\n3. It joins as a wireless mic — you can make it a camera from “Devices”."), 13f, UiKit.SUB))
            val w = LinearLayout(this).apply { gravity = Gravity.CENTER }
            val iv = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = tx("باركود الربط", "Pairing code") }
            w.addView(iv, LinearLayout.LayoutParams(dp(230), dp(230)))
            col.addView(w, UiKit.full(this, w, 12))
            qrImg = iv
            val lt = UiKit.text(this, "", 14f, UiKit.GREEN, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
            col.addView(lt); qrList = lt
        }.also { it.setOnDismissListener { qrImg = null; qrList = null; sig = "" } }
    }

    private fun micName(i: Int) = tx("مايك ", "Mic ") + (i + 1)

    // ───────── الأجهزة ─────────

    private fun buildDevices(links: List<SatHub.Link>) {
        devBox.removeAllViews(); devLines.clear(); levelBars.clear()
        // جوالي
        devBox.addView(devRow(Ic.PHONE, tx("جوالي", "My phone"), tx("كاميرا", "Camera") + " + " + if (myMic) tx("مايك", "mic") else tx("مايك مطفي", "mic off"), null) { mySheet() })
        for (l in links) {
            val r = roleOf(l)
            val icon = when { r == "mic" || l.role != "both" -> Ic.MIC; else -> Ic.CAM }
            val what = when { r == "mic" || l.role != "both" -> tx("مايك لاسلكي", "Wireless mic"); r == "cam" -> tx("كاميرا بس", "Camera only"); else -> tx("كاميرا ومايك", "Camera and mic") }
            devBox.addView(devRow(icon, l.name, what, l) { devSheet(l) })
        }
        val mics = links.count { roleOf(it) == "mic" || it.role != "both" }
        val add = UiKit.chipIcon(this, Ic.PLUS, if (mics == 0) tx("اربط مايك لاسلكي", "Connect a wireless mic") else "+ " + micName(mics)) { qrSheet() }
        devBox.addView(add, UiKit.full(this, add, 8))
    }

    private fun devRow(icon: String, name: String, what: String, l: SatHub.Link?, run: () -> Unit): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)); isClickable = true; setOnClickListener { run() }; contentDescription = name }
        row.addView(ImageView(this).apply { setImageDrawable(UiKit.icon(this@VlogActivity, icon, UiKit.TEXT, 20)); scaleType = ImageView.ScaleType.CENTER; background = UiKit.bg(UiKit.CARD2, 12, this@VlogActivity) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(10), 0, dp(6), 0) }
        t.addView(UiKit.text(this, name, 15f, UiKit.TEXT, true))
        val st = qtext(what + (l?.let { "  ·  " + status(it) } ?: ""), 12f, UiKit.SUB)
        t.addView(st)
        if (l != null) {
            devLines[l.id] = st
            val pb = UiKit.level(this); levelBars[l.id] = pb
            t.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply { topMargin = dp(5) })
        } else {
            val pb = UiKit.level(this); levelBars[myId] = pb
            t.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply { topMargin = dp(5) })
        }
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageView(this).apply { setImageDrawable(UiKit.icon(this@VlogActivity, Ic.GEAR, UiKit.SUB, 18)) })
        if (l != null) PodUi.licenseButton(this, l) { sig = "" }?.let { row.addView(it) }
        return row
    }

    private fun mySheet() {
        UiKit.sheet(this, tx("جوالي", "My phone")) { col, dlg ->
            col.addView(UiKit.text(this, tx("جودة التصوير", "Video quality"), 14f, UiKit.TEXT, true))
            val q = vp.getInt("q", 1080)
            val qr = LinearLayout(this)
            for ((v, n) in listOf(1080 to tx("1080p (أوضح)", "1080p (sharper)"), 720 to tx("720p (أخف)", "720p (lighter)")))
                qr.addView(UiKit.chip(this, n, q == v) {
                    if (rec) { toast(tx("غيّرها قبل التسجيل", "Change it before recording")); return@chip }
                    vp.edit().putInt("q", v).apply(); sendMine(JSONObject().put("q", v)); dlg.dismiss()
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(6), dp(3), dp(10)) })
            col.addView(qr)
            val stab = vp.getBoolean("stab", true)
            val sb = UiKit.chip(this, (if (stab) "✓ " else "") + tx("تثبيت الصورة وقت المشي", "Steady picture while walking"), stab) {
                if (rec) { toast(tx("غيّرها قبل التسجيل", "Change it before recording")); return@chip }
                vp.edit().putBoolean("stab", !stab).apply(); sendMine(JSONObject().put("stab", !stab)); dlg.dismiss()
            }
            col.addView(sb, UiKit.full(this, sb, 0))
            col.addView(UiKit.text(this, tx("بيقص أطراف الصورة شوي عشان يخفف الهزّ — إذا جوالك بيدعمه.", "Crops the edges a little to reduce shake — if your phone supports it."), 12f, UiKit.SUB).apply { setPadding(0, dp(4), 0, dp(12)) })
            col.addView(UiKit.text(this, tx("كاميرا جوالك هي الأساسية. مايكه:", "Your phone's camera is the main one. Its mic:"), 13f, UiKit.SUB))
            val r = LinearLayout(this)
            for ((v, n) in listOf(true to tx("شغّال", "On"), false to tx("مطفي (الصوت من المايك اللاسلكي)", "Off (sound from the wireless mic)")))
                r.addView(UiKit.chip(this, n, myMic == v) { if (rec) { toggleMyMic(); return@chip }; myMic = v; sig = ""; ctlSig = ""; dlg.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(8), dp(3), 0) })
            col.addView(r)
        }
    }

    private fun devSheet(l: SatHub.Link) {
        UiKit.sheet(this, l.name) { col, dlg ->
            col.addView(UiKit.text(this, tx("شو بدك يكون هالجوال؟", "What should this phone be?"), 14f, UiKit.TEXT, true))
            val cur = roleOf(l)
            for ((k, n) in listOf("mic" to tx("🎙  مايك بس", "🎙  Mic only"), "cam" to tx("📷  كاميرا بس", "📷  Camera only"), "both" to tx("🎥  كاميرا ومايك", "🎥  Camera and mic"))) {
                val b = UiKit.chip(this, n, cur == k) {
                    if (rec) { toast(tx("غيّر الدور قبل التسجيل", "Change the role before recording")); return@chip }
                    vp.edit().putString("role_${l.id}", k).apply()
                    SatHub.sendCfg(l.id, JSONObject().put("role", if (k == "mic") "mic" else "both"))
                    sig = ""; dlg.dismiss(); buildTpl()
                }
                col.addView(b, UiKit.full(this, b, 6))
            }
            col.addView(UiKit.text(this, tx("الصوت على هالجوال", "Sound on this phone"), 14f, UiKit.TEXT, true).apply { setPadding(0, dp(14), 0, 0) })
            val src = l.cfgs?.optInt("aSrc", 0) ?: 0
            val sr = LinearLayout(this)
            for ((k, n) in listOf(2 to tx("صوته بس (عزل)", "Just their voice"), 0 to tx("طبيعي", "Natural"), 1 to tx("المحيط", "Surroundings")))
                sr.addView(UiKit.chip(this, n, src == k) {
                    if (rec) { toast(tx("غيّره قبل التسجيل", "Change it before recording")); return@chip }
                    SatHub.sendCfg(l.id, JSONObject().put("aSrc", k)); dlg.dismiss(); toast("✓ $n")
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(6), dp(3), 0) })
            col.addView(sr)
            col.addView(UiKit.text(this, tx("«صوته بس» بيشغّل وضع المكالمات وتنظيف الضجة، فبيخفف صوت الناس حواليه كثير — أحسن نتيجة والجوال قريب من ثمه (٢٠ سم، بالجيبة أو بإيده). «المحيط» بيسجّل كل إشي حواليه.", "“Just their voice” uses call mode with noise cleaning, cutting the people around a lot — best with the phone ~20 cm from the mouth (pocket or hand). “Surroundings” records everything."), 12f, UiKit.SUB).apply { setPadding(0, dp(6), 0, 0) })
            val g = (l.cfgs?.optDouble("gain", 1.0) ?: 1.0).toFloat()
            val gt = UiKit.text(this, tx("التكبير: ", "Gain: ") + String.format(Locale.US, "%.1f×", g), 13f).apply { setPadding(0, dp(12), 0, 0) }
            col.addView(gt)
            col.addView(SeekBar(this).apply {
                max = 300; progress = (g * 100).toInt().coerceIn(10, 300)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { gt.text = tx("التكبير: ", "Gain: ") + String.format(Locale.US, "%.1f×", p.coerceAtLeast(10) / 100f) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { SatHub.sendCfg(l.id, JSONObject().put("gain", (s!!.progress.coerceAtLeast(10) / 100.0))) }
                })
            })
            if (l.role == "both") {
                val sv = UiKit.chipIcon(this, Ic.EYE, tx("شوف كاميرته وتحكم فيها", "See and control its camera")) { dlg.dismiss(); startActivity(Intent(this, StudioActivity::class.java).putExtra("dev", l.id)) }
                col.addView(sv, UiKit.full(this, sv, 12))
            }
        }
    }

    // ───────── تحديث دوري ─────────

    private val tick = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) refresh()
            ui.postDelayed(this, 250)
        }
    }

    private fun refresh() {
        val rec = this.rec
        val want = if (rec) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        if (requestedOrientation != want) requestedOrientation = want
        val me = myId
        val links = SatHub.links.values.filter { it.id != me && it.connected }.sortedBy { it.name }
        val s = links.joinToString("|") { "${it.id}${it.role}${roleOf(it)}${it.licLeft > 0}${it.licOwner}" } + rec + liveIdx + isFull + myMic
        if (s != sig) {
            sig = s
            // معاينتي: قبل التسجيل صور (أوضح بملء الشاشة)؛ أثناءه الصورة المسجّلة نفسها
            SatHub.setPreview(me, !rec, false, true, if (isFull) 100 else 125, if (isFull) 720 else 360)
            for (l in links) if (l.role == "both") SatHub.setLightPreview(l.id, true, if (rec) 800 else 500, 240) else SatHub.setPreview(l.id, false, false, true)
            // الدور اللي اخترته بيوصل للجهاز (لو انضم بدور ثاني)
            for (l in links) vp.getString("role_${l.id}", null)?.let { r -> val want2 = if (r == "mic") "mic" else "both"; if (l.role != want2) SatHub.sendCfg(l.id, JSONObject().put("role", want2)) }
            buildThumbs(links, rec)
            buildDevices(links)
            buildTpl()
            cfgs()?.let { zoom = it.optDouble("zoom", 1.0).toFloat() }
            // الجودة اللي اخترتها توصل لكاميرتك (لو كانت متصلة من قبل بجودة ثانية)
            if (!rec) cfgs()?.let { c -> val q = vp.getInt("q", 1080); if (c.has("q") && c.optInt("q") != q) sendMine(JSONObject().put("q", q)) }
            renderZoom()
            ctlSig = ""
        }
        // الكاميرا
        camSurface.visibility = if (rec) View.VISIBLE else View.GONE
        val local = myLink()
        val f = local?.frame
        if (!rec && f != null && local.frameAt != seenLocal) { camImg.setImageBitmap(f); seenLocal = local.frameAt }
        camHint.visibility = if (!rec && f == null) View.VISIBLE else View.GONE
        if (rec) fitCam()
        renderCtl()

        val paused = PodRec.isPaused(this)
        val sec = PodRec.elapsedSec(this)
        val t = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
        recTv.text = when { !rec -> ""; paused -> "⏸ $t"; else -> "●  $t" }
        recTv.setTextColor(if (rec && !paused) Color.parseColor("#FF5A50") else Color.WHITE)
        pausedTv.visibility = if (rec && paused) View.VISIBLE else View.GONE
        devStatus.text = statusLines(links)
        shootBtn.text = if (rec) tx("ارجع للتصوير", "Back to filming") + "  ·  $t" else tx("صوّر", "Shoot")
        pageState.text = pageStateText(links, rec)
        renderLast(rec)
        for ((id, iv) in thumbViews) {
            val l = SatHub.links[id] ?: continue
            val fr = l.frame
            if (fr != null && thumbSeen[id] != l.frameAt) { iv.setImageBitmap(fr); thumbSeen[id] = l.frameAt }
        }
        if (!isFull) for ((id, pb) in levelBars) { val l = SatHub.links[id]; pb.progress = if (l != null && l.connected) (l.level * 100 / 32767).coerceIn(0, 100) else 0 }
        if (!isFull) for ((id, tv) in devLines) SatHub.links[id]?.let { l ->
            val r = roleOf(l)
            val what = when { r == "mic" || l.role != "both" -> tx("مايك لاسلكي", "Wireless mic"); r == "cam" -> tx("كاميرا بس", "Camera only"); else -> tx("كاميرا ومايك", "Camera and mic") }
            tv.text = what + "  ·  " + status(l)
        }
        // نافذة الربط: الباركود وقائمة اللي ارتبطوا
        qrImg?.let { iv -> if (SatHub.running) { val p = AppShare.pairQr(); if (p != qrFor) { qrFor = p; iv.setImageBitmap(PodcastActivity.qrBitmap(p)) } } }
        qrList?.let { lt ->
            val mics = links.filter { roleOf(it) == "mic" || it.role != "both" }
            lt.text = if (links.isEmpty()) tx("بانتظار أول جهاز…", "Waiting for the first device…")
            else links.joinToString("\n") { "✓ " + it.name + " — " + if (roleOf(it) == "mic" || it.role != "both") tx("مايك", "mic") else tx("كاميرا", "camera") } +
                "\n" + tx("بدك كمان؟ خلّي الجوال الثاني يمسح نفس الباركود", "Another one? Have the next phone scan the same code") + " (" + micName(mics.size) + ")"
        }
    }

    /** فوق يمين بملء الشاشة: سطر لكل جهاز */
    private fun statusLines(links: List<SatHub.Link>): String {
        val out = ArrayList<String>()
        if (!myMic) out += "🔇 " + tx("مايك جوالي مطفي", "My phone's mic off")
        for (l in links) {
            val r = roleOf(l)
            val icon = if (r == "mic" || l.role != "both") "🎙" else "📷"
            val st = if (l.recording) "● " + tx("بيسجّل", "rec") else "○ " + tx("جاهز", "ready")
            out += "$icon ${l.name}  $st" + if (l.battery >= 0) "  ${l.battery}%" + (if (l.battery in 0..15) " ⚠" else "") else ""
        }
        for (l in SatHub.links.values.filter { it.id != myId && !it.connected && it.recording }) out += "⚪ ${l.name} — " + tx("برّا التغطية، بيكمل تسجيل عنده", "out of range, still recording")
        if (out.isEmpty()) out += tx("ما في مايك لاسلكي — صوت جوالك", "No wireless mic — your phone's sound")
        return out.joinToString("\n")
    }

    private fun pageStateText(links: List<SatHub.Link>, rec: Boolean): String {
        if (rec) return "🔴 " + tx("بيسجّل على كل الجوالات", "Recording on every phone") + if (PodRec.isPaused(this)) " — ⏸ " + tx("متوقف مؤقتًا", "paused") else ""
        if (myLink()?.connected != true) return tx("تجهيز كاميرا جوالك…", "Preparing your phone's camera…")
        val mics = links.count { roleOf(it) == "mic" || it.role != "both" }
        val cams = links.count { it.role == "both" && roleOf(it) != "mic" }
        return "✓ " + tx("جاهز", "Ready") + " — " + listOfNotNull(
            tx("كاميرتك", "your camera"),
            if (mics > 0) tx("$mics مايك لاسلكي", "$mics wireless mic(s)") else null,
            if (cams > 0) tx("$cams كاميرا ثانية", "$cams more camera(s)") else null,
        ).joinToString(" + ")
    }

    private fun status(l: SatHub.Link): String = if (!l.connected) "⚪ " + tx("برّا التغطية", "Out of range") else
        (if (l.recording) "🔴 " + tx("بيسجّل", "Recording") else "🟢 " + tx("جاهز", "Ready")) + (if (l.battery >= 0) "  🔋${l.battery}%" else "") +
            (if (l.battery in 0..15) " ⚠" else "") + (if (l.transfer.isNotEmpty()) "  ⬆ ${l.transfer}" else "")

    private var lastShown = 0L
    private fun renderLast(rec: Boolean) {
        val sid = lastSid
        if (rec || sid == null) { lastTv.visibility = View.GONE; return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastShown < 1500) return
        lastShown = now
        val s = SessionStore.load(this, sid) ?: run { lastTv.visibility = View.GONE; return }
        lastTv.visibility = View.VISIBLE
        val waiting = s.devices.values.filter { it.started && !it.uploaded }
        lastTv.text = tx("آخر تسجيل: ", "Last recording: ") + if (waiting.isEmpty()) "✓ " + tx("وصلت كل الملفات — اضغط للمونتاج", "all files received — tap to edit")
        else "⏳ " + tx("بانتظار ملفات: ", "waiting for files from: ") + waiting.joinToString("، ") { it.name } + tx(" — استنى قبل ما تفتح المونتاج", " — wait before opening the edit")
        lastTv.setTextColor(Color.parseColor(if (waiting.isEmpty()) UiKit.GREEN else UiKit.ORANGE))
    }

    /** الصورة المسجّلة بنسبتها الحقيقية: ملء الشاشة = بتعبّي العرض */
    private fun fitCam() {
        val bw = camBox.width; val bh = camBox.height
        if (bw <= 0 || bh <= 0) return
        val a = if (SatState.lockedOrient == 2) 16f / 9f else 9f / 16f
        var w = bw; var h = (bw / a).toInt()
        if (h > bh) { h = bh; w = (bh * a).toInt() }
        val lp = camSurface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER; camSurface.layoutParams = lp }
    }

    private fun buildThumbs(links: List<SatHub.Link>, rec: Boolean) {
        thumbs.removeAllViews(); thumbViews.clear(); thumbSeen.clear()
        val cams = links.filter { it.role == "both" && it.connected && roleOf(it) != "mic" }
        if (cams.isEmpty()) return
        val all = listOf<SatHub.Link?>(null) + cams
        for (l in all) {
            val idx = if (l == null) 0 else personOf[l.id] ?: -1
            val cell = FrameLayout(this).apply {
                background = UiKit.bg(if (rec && idx == liveIdx) UiKit.ACCENT else "#59000000", 10, this@VlogActivity)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                contentDescription = l?.name ?: tx("كاميرتي", "My camera")
                setOnClickListener { if (rec) goLive(idx) else if (l != null) startActivity(Intent(this@VlogActivity, StudioActivity::class.java).putExtra("dev", l.id)) }
            }
            if (l != null) {
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
                cell.addView(iv, FrameLayout.LayoutParams(dp(54), dp(72)))
                thumbViews[l.id] = iv
            } else cell.addView(UiKit.text(this, tx("أنا", "Me"), 12f, "#FFFFFF", true).apply { gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK) }, FrameLayout.LayoutParams(dp(54), dp(72)))
            cell.addView(UiKit.text(this, l?.name ?: tx("أنا", "Me"), 9.5f, "#FFFFFF").apply { setBackgroundColor(0x99000000.toInt()); setPadding(dp(3), 0, dp(3), 0); maxLines = 1 },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            thumbs.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
    }
}
