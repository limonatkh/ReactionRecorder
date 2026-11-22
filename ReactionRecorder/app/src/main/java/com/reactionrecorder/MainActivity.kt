package com.reactionrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : BaseActivity() {
    private lateinit var cfg: RecordConfig
    private lateinit var editor: LayoutEditorView
    private lateinit var infoText: TextView
    private lateinit var statusText: TextView
    private lateinit var noteText: TextView
    private lateinit var recBtn: TextView
    private lateinit var flipChip: TextView
    private lateinit var pauseChip: TextView
    private lateinit var presetRow: LinearLayout
    private lateinit var quickRow: LinearLayout
    private lateinit var ratioRow: LinearLayout
    private lateinit var ratioLabel: TextView
    private lateinit var ratioSeek: SeekBar
    private val presetChips = ArrayList<TextView>()
    private lateinit var noCamChip: TextView
    private lateinit var orientChip: TextView
    private lateinit var resChip: TextView
    private lateinit var fpsChip: TextView
    private lateinit var micChip: TextView
    private lateinit var srcChip: TextView
    private lateinit var micSrcChip: TextView
    private lateinit var shapeChip: TextView
    private lateinit var fitChip: TextView
    private lateinit var modeScreenChip: TextView
    private lateinit var modeVideoChip: TextView
    private lateinit var pickChip: TextView
    private lateinit var listChip: TextView
    private lateinit var linkChip: TextView
    private lateinit var modeRow: LinearLayout

    // اللوحة الحيّة أثناء التسجيل
    private lateinit var liveBox: LinearLayout
    private lateinit var transportScroll: View
    private lateinit var seekRow: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var endedRow: LinearLayout
    private lateinit var endedText: TextView
    private lateinit var nextBtn: TextView
    private lateinit var playChip: TextView
    private lateinit var loopChip: TextView
    private lateinit var standbyChip: TextView
    private lateinit var camChip: TextView
    private lateinit var gestureChip: TextView
    private lateinit var fullChip: TextView
    private lateinit var fitLiveChip: TextView
    private lateinit var micLiveChip: TextView
    private lateinit var srcLiveChip: TextView
    private lateinit var allMuteChip: TextView
    private lateinit var silentChip: TextView
    private var seeking = false
    private var fullScreen = false
    private lateinit var fsPanel: LinearLayout
    private lateinit var fsPlayChip: TextView
    private lateinit var fsMicChip: TextView
    private val chrome = ArrayList<View>()
    // ترتيب الشاشة: عمودي = كل إشي تحت بعض؛ عرضي = الفيديو كبير وكل الأزرار بعمود جانبي
    private lateinit var rootLL: LinearLayout
    private lateinit var sideScroll: ScrollView
    private lateinit var sideCol: LinearLayout
    private lateinit var topRow: View
    private lateinit var ctlRow: View
    private val portraitKids = ArrayList<Pair<View, ViewGroup.LayoutParams>>()
    private var arrangedLand: Boolean? = null
    private val chromeAlways = ArrayList<View>()
    private lateinit var selfieChip: TextView
    private var drawChip: TextView? = null
    private lateinit var camSwapChip: TextView
    private var importAutoStandby = false
    private var standbyLabelView: TextView? = null
    private var videoThumb: android.graphics.Bitmap? = null
    private var videoAspect = 16f / 9f

    private val ui = Handler(Looper.getMainLooper())
    // شريط الخيارات تحت المعاينة (كل مجموعة بورقة لحالها بدل صفوف كثيرة)
    private lateinit var optBar: LinearLayout
    private lateinit var srcBar: TextView
    private lateinit var layBar: TextView
    private lateinit var avBar: TextView
    private val srcViews = ArrayList<View>()
    private val layViews = ArrayList<View>()
    private val avViews = ArrayList<View>()
    // الرسم على الفيديو وقت التسجيل
    private lateinit var drawCanvas: BoardCanvas
    private lateinit var drawBar: HorizontalScrollView
    private lateinit var drawRow: LinearLayout
    private var drawing = false
    private var pausedByDraw = false
    private var drawPushed = -1L
    private var camera: Camera? = null
    private var wasBusy = false
    private val stateListener: () -> Unit = { onStateChanged() }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hasCamera()) bindPreview()
    }
    private val pickMultiLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onFilesPicked(uris ?: emptyList(), false)
    }
    private val pickOneLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onFilesPicked(if (uri != null) listOf(uri) else emptyList(), true)
    }
    private val standbyPickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onStandbyPicked(uri)
    }
    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START)
                .putExtra(RecorderService.EXTRA_CODE, r.resultCode).putExtra(RecorderService.EXTRA_DATA, data)
            ContextCompat.startForegroundService(this, i)
        } else {
            toast(tx("يلزم إذن تصوير الشاشة لتسجيل المصدر", "Screen capture permission is required to record the video source"))
            bindPreview()
        }
    }

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = ConfigStore.load(this)
        Playlist.load(this)
        buildUi()
        if (SatHub.hubWanted(this) && !SatHub.running) SatHub.start(this)
        if (cfg.mode == SourceMode.VIDEO) loadVideoInfoAsync()
        requestPermissionsIfNeeded()
        RecorderState.addListener(stateListener)
        render()
        if (intent.getBooleanExtra("openSettings", false)) ui.post { showSettings() }
    }

    override fun onResume() {
        super.onResume()
        stripH.post(stripTick)
        render()
        if (!RecorderState.isBusy) bindPreview()
        handleResult()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        stripH.removeCallbacks(stripTick)
        ui.removeCallbacks(ticker)
        ConfigStore.save(this, cfg)
    }

    override fun onDestroy() {
        super.onDestroy()
        RecorderState.removeListener(stateListener)
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            updateLive()
            ui.postDelayed(this, if (RecorderState.isBusy) 250L else 500L)
        }
    }

    // ───────────────────────── permissions ─────────────────────────

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val need = ArrayList<String>()
        if (!hasCamera()) need += Manifest.permission.CAMERA
        if (!hasMic()) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    // ───────────────────────── camera preview (قبل التسجيل فقط) ─────────────────────────

    private fun bindPreview() {
        if (!hasCamera() || RecorderState.isBusy || cfg.noCamera) return
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                if (RecorderState.isBusy) return@addListener
                val p = f.get()
                p.unbindAll()
                val pv = Preview.Builder().build()
                pv.setSurfaceProvider(editor.previewView.surfaceProvider)
                val sel = if (cfg.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val cam = p.bindToLifecycle(this, sel, pv)
                camera = cam
                applyPreviewRotation()
                applyCameraControls()
            } catch (e: Throwable) {
                statusText.text = tx("خطأ في الكاميرا: ", "Camera error: ") + e.message
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindPreview() {
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Throwable) {}
        camera = null
    }

    private fun applyCameraControls() {
        val c = camera ?: return
        try {
            val zs = c.cameraInfo.zoomState.value
            if (zs != null) c.cameraControl.setZoomRatio(cfg.zoom.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
            c.cameraControl.setExposureCompensationIndex(cfg.exposureIndex)
            c.cameraControl.enableTorch(cfg.torch && c.cameraInfo.hasFlashUnit())
        } catch (_: Throwable) {}
    }

    // ───────────────────────── UI building ─────────────────────────

    private lateinit var stripScroll: HorizontalScrollView
    private lateinit var stripRow: LinearLayout
    private val stripViews = HashMap<String, android.widget.ImageView>()
    private val stripSeen = HashMap<String, Long>()
    private val stripH = Handler(Looper.getMainLooper())
    private val stripTick = object : Runnable {
        override fun run() {
            updateStrip()
            stripH.postDelayed(this, 200)
        }
    }

    private fun updateStrip() {
        val on = SatHub.links.values.filter { it.stripOn }
        stripScroll.visibility = if (on.isEmpty()) View.GONE else View.VISIBLE
        val ids = on.map { it.id }.toSet()
        for (id in stripViews.keys.toList()) if (id !in ids) { stripRow.removeView(stripViews.remove(id)); stripSeen.remove(id) }
        for (l in on) {
            val v = stripViews.getOrPut(l.id) {
                android.widget.ImageView(this).apply {
                    setBackgroundColor(Color.BLACK)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setOnClickListener { startActivity(Intent(this@MainActivity, StudioActivity::class.java).putExtra("dev", l.id)) }
                    stripRow.addView(this, LinearLayout.LayoutParams(dp(120), dp(90)).apply { setMargins(0, 0, dp(6), 0) })
                }
            }
            val f = l.frame
            if (f != null && stripSeen[l.id] != l.frameAt) { v.setImageBitmap(f); stripSeen[l.id] = l.frameAt }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun chip(text: String, onClick: () -> Unit): TextView = CleanChip(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 13.5f; gravity = Gravity.CENTER; typeface = Fonts.semi
        setPadding(dp(14), dp(8), dp(14), dp(8))
        minWidth = dp(48); minHeight = dp(44); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        setOnClickListener { onClick() }
        setChipSelected(this, false)
    }

    /** chip بالشريط: سطرين — العنوان صغير فوق والقيمة تحت، عشان ما تنقص الكلمة («المصدر: الش…») */
    private fun barChip(v: TextView): TextView = v.apply {
        isSingleLine = false; maxLines = 2; textSize = 12.5f; setLineSpacing(0f, 0.95f)
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }
    private fun barText(label: String, value: String): CharSequence {
        val sb = android.text.SpannableStringBuilder(label).append("\n")
        sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor(UiKit.SUB)), 0, label.length, 0)
        sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), 0, label.length, 0)
        sb.append(value); return sb
    }
    private fun setBar(v: TextView, label: String, value: String) {
        if (v.text?.toString() == label + "\n" + value) return
        v.text = barText(label, value); v.contentDescription = "$label: $value"
    }

    private fun setChipSelected(v: TextView, sel: Boolean) {
        v.background = UiKit.ripple(UiKit.bg(if (sel) UiKit.BLUE_BG else UiKit.CARD2, 22, this, if (sel) UiKit.BLUE else UiKit.LINE))
    }

    private fun lp(w: Int, h: Int, l: Int = 0, r: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), 0, dp(r), 0) }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0F11"))
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(10), b.top + dp(8), dp(10), b.bottom + dp(10)); insets
        }

        // الصف العلوي: التخزين + الإعدادات
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        infoText = QuietText(this).apply { setTextColor(Color.parseColor("#9A9CA3")); textSize = 12f }
        top.addView(TextView(this).apply {
            text = if (L.ar) "→" else "←"; textSize = 22f; setTextColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(12), dp(2))
            setOnClickListener { finish() }
        })
        top.addView(infoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(chip("⚙") { showSettings() })
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        topRow = top
        chrome += top; chromeAlways += top

        // اختيار المصدر: تصوير الشاشة أو ملف/رابط فيديو
        modeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        modeScreenChip = chip("📱 " + tx("الشاشة", "Screen")) { setMode(SourceMode.SCREEN) }
        modeVideoChip = chip("🎞 " + tx("ملف فيديو", "Video file")) { setMode(SourceMode.VIDEO) }
        pickChip = chip("📂 " + tx("إضافة", "Add")) { pickMultiLauncher.launch(arrayOf("video/*", "image/*")) }
        listChip = chip("📋 " + tx("القائمة", "List")) { showPlaylistDialog() }
        linkChip = chip("🔗 " + tx("رابط", "Link")) { showLinkDialog() }
        val rotCamChip = chip("↻ " + tx("تدوير الكاميرا", "Rotate cam")) { rotateSelfie() }
        val rotSrcChip = chip("⟳ " + tx("تدوير الفيديو", "Rotate video")) { rotateSource() }
        val devChip = chip("📡 " + tx("أجهزة مساعدة", "Remote devices")) { startActivity(Intent(this, DevicesActivity::class.java)) }
        for (c in listOf(modeScreenChip, modeVideoChip, pickChip, listChip, linkChip)) modeRow.addView(c, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6))
        HorizontalScrollView(this).apply { addView(modeRow) } // مش ظاهر: الأزرار بتنعرض بورقة "المصدر"
        srcViews += listOf(modeScreenChip, modeVideoChip, pickChip, listChip, linkChip)
        layViews += listOf(rotCamChip, rotSrcChip)

        // معاينة الأجهزة المساعدة (تظهر فقط للأجهزة التي فعّلت لها "Show on main screen")
        stripRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        stripScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; addView(stripRow) }
        root.addView(stripScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        chrome += stripScroll

        // المحرر/المعاينة
        editor = LayoutEditorView(this).apply {
            noCam = this@MainActivity.cfg.noCamera
            this.cfg = this@MainActivity.cfg
            onChanged = { ConfigStore.save(this@MainActivity, this@MainActivity.cfg); renderPresets() }
            onLiveEdit = { RecorderState.scene?.applyLayout(this@MainActivity.cfg.layout) }
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        drawCanvas = BoardCanvas(this).apply {
            setMode("overlay"); color = 0xFFFF453A.toInt(); strokeW = 0.008f; visibility = View.GONE
            onTextTap = { nx, ny -> askDrawText(nx, ny) }
        }
        editor.drawLayer = drawCanvas

        // شريط الخيارات: كل مجموعة بورقة
        optBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        srcBar = barChip(chip(tx("المصدر", "Source")) { optSheet(tx("المصدر", "Source"), srcViews) })
        layBar = barChip(chip(tx("الترتيب", "Layout")) { optSheet(tx("الترتيب", "Layout"), layViews) })
        avBar = barChip(chip(tx("الصوت والصورة", "Sound & picture")) { optSheet(tx("الصوت والصورة", "Sound & picture"), avViews) })
        optBar.addView(srcBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { marginEnd = dp(4) })
        optBar.addView(layBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        optBar.addView(avBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { marginEnd = dp(4) })
        optBar.addView(chip(tx("أجهزة", "Devices")) { startActivity(Intent(this, DevicesActivity::class.java)) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        root.addView(optBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        chrome += optBar

        // Presets
        presetRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (i in 0 until 5) {
            val c = chip(Presets.names[i]) { applyPreset(i + 1) }
            presetChips += c
            presetRow.addView(c, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6))
        }
        // بدون كاميرا: الشاشة (أو الفيديو) بملء الإطار + الصوت بس، والكاميرا ما بتنفتح
        noCamChip = chip("🚫📷 " + tx("بدون كاميرا", "No camera")) { setNoCamera(!cfg.noCamera) }
        presetRow.addView(noCamChip, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6))
        HorizontalScrollView(this).apply { addView(presetRow) }
        layViews.addAll(0, presetChips + noCamChip)

        // نسبة الفيديو/الكاميرا (للـ Presets المقسومة)
        ratioRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        ratioLabel = TextView(this).apply { setTextColor(Color.parseColor("#9A9CA3")); textSize = 12f }
        ratioSeek = SeekBar(this).apply {
            max = 50 // 30%..80%
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val id = cfg.layout.presetId
                    if (id !in 2..4) return
                    Presets.build(id, cfg.portrait, (30 + p) / 100f, cfg.layout)
                    ratioLabel.text = tx("الفيديو", "Video") + " ${30 + p}%"
                    editor.refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { ConfigStore.save(this@MainActivity, cfg) }
            })
        }
        ratioRow.addView(ratioLabel, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
        ratioRow.addView(ratioSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layViews += ratioRow

        // أزرار سريعة: الاتجاه، الدقة، FPS، الصوت، الشكل
        quickRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        orientChip = chip("9:16") {
            // تلقائي ← عمودي ← أفقي
            cfg.orientMode = when (cfg.orientMode) { "auto" -> "portrait"; "portrait" -> "landscape"; else -> "auto" }
            val want = when (cfg.orientMode) { "portrait" -> true; "landscape" -> false; else -> autoPortrait() }
            if (want != cfg.portrait) {
                cfg.portrait = want
                val id = if (cfg.layout.presetId in 1..5) cfg.layout.presetId else 1
                cfg.layout = Presets.build(id, cfg.portrait, cfg.layout.ratio, cfg.layout)
            }
            changed(true)
        }
        resChip = chip("1080p") { cfg.shortSide = if (cfg.shortSide == 1080) 720 else 1080; changed(false) }
        fpsChip = chip("30 fps") { cfg.fps = when (cfg.fps) { 30 -> 24; 24 -> 60; else -> 30 }; changed(false) }
        micChip = chip("Mic") { cfg.micOn = !cfg.micOn; changed(false) }
        srcChip = chip("Audio") {
            if (RecorderState.isBusy) return@chip
            setSrcAudioMode(when (srcAudioMode()) { "clean" -> "mic"; "mic" -> "off"; else -> "clean" }); changed(false)
            toast(when (srcAudioMode()) {
                "clean" -> tx("صوت الفيديو يُسجَّل نظيفًا من الجوال", "Video sound recorded cleanly from the phone")
                "mic" -> tx("صوت الفيديو يُلتقط بالمايك فقط (من السماعة)", "Video sound captured by the mic only (from the speaker)")
                else -> tx("صوت الفيديو لا يُسجَّل", "Video sound is not recorded")
            })
        }
        micSrcChip = chip("Mic") {
            if (RecorderState.isBusy) return@chip
            cfg.micSource = when (cfg.micSource) { "auto" -> "builtin"; "builtin" -> "usb"; else -> "auto" }; changed(false)
        }
        shapeChip = chip("Shape") {
            cfg.layout.shape = CamShape.values()[(cfg.layout.shape.ordinal + 1) % 3]
            if (cfg.layout.presetId in 1..5) Presets.build(cfg.layout.presetId, cfg.portrait, cfg.layout.ratio, cfg.layout)
            changed(true)
        }
        fitChip = chip("Fit") {
            cfg.sourceFit = SourceFit.values()[(cfg.sourceFit.ordinal + 1) % 3]
            changed(true)
        }
        // الأساسي ظاهر، والباقي في "المزيد"
        for (c in listOf(orientChip, micChip, micSrcChip, srcChip, fitChip)) quickRow.addView(c, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6))
        HorizontalScrollView(this).apply { addView(quickRow) }
        avViews += listOf(orientChip, fitChip, resChip, fpsChip, micChip, micSrcChip, srcChip)
        layViews += shapeChip

        buildLiveBox(root)
        chrome += liveBox

        statusText = QuietText(this).apply { setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE }
        root.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        chrome += statusText; chromeAlways += statusText
        noteText = QuietText(this).apply { setTextColor(Color.parseColor("#8A90A0")); textSize = 11f; gravity = Gravity.CENTER }
        root.addView(noteText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        chrome += noteText; chromeAlways += noteText

        // شريط التحكم: تبديل الكاميرا | Record | Pause/Mute
        val ctl = LinearLayout(this).apply { gravity = Gravity.CENTER }
        flipChip = chip("⟲ " + tx("قلب", "Flip")) { flipCamera() }
        recBtn = TextView(this).apply {
            text = tx("سجّل", "REC"); setTextColor(Color.WHITE); textSize = 16f; typeface = Fonts.bold; gravity = Gravity.CENTER
            setOnClickListener { onRecordClicked() }
        }
        pauseChip = chip("⏸ " + tx("إيقاف مؤقت", "Pause")) { onPauseClicked() }
        ctl.addView(flipChip, lp(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14))
        ctl.addView(recBtn, LinearLayout.LayoutParams(dp(84), dp(84)))
        ctl.addView(pauseChip, lp(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT, 14, 0))
        root.addView(ctl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        ctlRow = ctl
        rootLL = root
        for (i in 0 until root.childCount) root.getChildAt(i).let { portraitKids += it to it.layoutParams }
        sideCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sideScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(sideCol) }
        arrange()
        chrome += ctl; chromeAlways += ctl

        // أزرار جانبية تظهر فقط في وضع ملء الشاشة أثناء التسجيل
        fsPlayChip = chip("⏯") { RecorderState.scene?.playPause() }
        fsMicChip = chip("🎤") { RecorderState.scene?.let { it.setMicMuted(!RecorderState.micMuted) }; updateLive() }
        fsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0.8f; visibility = View.GONE
            addView(chip("⤡ " + tx("خروج", "Exit")) { setFullScreen(false) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            addView(fsPlayChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            addView(chip("📷") { showCameraMenu() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            addView(fsMicChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        frame.addView(fsPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(6) })

        // أدوات الرسم على الفيديو (نفس أدوات وضع الشرح)
        drawRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)); background = UiKit.bg(UiKit.CARD, 18, this@MainActivity, UiKit.LINE) }
        drawBar = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(drawRow); visibility = View.GONE }
        frame.addView(drawBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(40) })
    }

    // ───────── خيارات بورقة ─────────

    private fun optSheet(title: String, views: List<View>) {
        if (RecorderState.isBusy) return
        render()
        UiKit.sheet(this, title) { col, _ ->
            var row: LinearLayout? = null
            var n = 0
            for (v in views) {
                if (v.visibility == View.GONE) continue
                (v.parent as? ViewGroup)?.removeView(v)
                if (v is LinearLayout) { col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }); row = null; n = 0; continue }
                if (n % 2 == 0) { row = LinearLayout(this); col.addView(row) }
                row!!.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) })
                n++
            }
            if (n % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    // ───────── الرسم على الفيديو ─────────

    private fun drawTool(path: String, desc: String, sel: Boolean = false, run: (View) -> Unit): View = android.widget.ImageButton(this).apply {
        setImageDrawable(UiKit.icon(this@MainActivity, path, if (sel) "#FFFFFF" else UiKit.TEXT, 22))
        contentDescription = desc
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, desc)
        background = UiKit.ripple(UiKit.bg(if (sel) UiKit.BLUE_BG else UiKit.CARD2, 12, this@MainActivity, if (sel) UiKit.BLUE else UiKit.LINE))
        setOnClickListener { run(it) }
    }

    private fun buildDrawBar() {
        drawRow.removeAllViews()
        val t = drawCanvas.tool
        fun add(v: View) = drawRow.addView(v, LinearLayout.LayoutParams(dp(46), dp(44)).apply { marginEnd = dp(4) })
        add(drawTool(Ic.PEN, tx("قلم", "Pen"), t == BoardCanvas.PEN) { drawCanvas.tool = BoardCanvas.PEN; buildDrawBar() })
        add(drawTool(Ic.HL, tx("تمييز", "Highlighter"), t == BoardCanvas.HL) { drawCanvas.tool = BoardCanvas.HL; buildDrawBar() })
        add(drawTool(Ic.LASER, tx("ليزر", "Laser"), t == BoardCanvas.LASER) { drawCanvas.tool = BoardCanvas.LASER; buildDrawBar() })
        add(drawTool(Ic.ARROW, tx("سهم", "Arrow"), t == BoardCanvas.ARROW) { drawCanvas.tool = BoardCanvas.ARROW; buildDrawBar() })
        add(drawTool(Ic.OVAL, tx("دائرة", "Circle"), t == BoardCanvas.OVAL) { drawCanvas.tool = BoardCanvas.OVAL; buildDrawBar() })
        add(drawTool(Ic.TEXT, tx("كتابة (اضغط مكان)", "Text (tap a spot)"), t == BoardCanvas.TEXT) { drawCanvas.tool = BoardCanvas.TEXT; buildDrawBar() })
        add(drawTool(Ic.ERASER, tx("ممحاة", "Eraser"), t == BoardCanvas.ERASE) { drawCanvas.tool = BoardCanvas.ERASE; buildDrawBar() })
        for (c in listOf(0xFFFF453A.toInt(), 0xFFFFD60A.toInt(), 0xFF32D74B.toInt(), 0xFF3D8BFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt())) {
            drawRow.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(if (drawCanvas.color == c) 3 else 1), Color.parseColor(if (drawCanvas.color == c) UiKit.BLUE else "#9A9CA3")) }
                contentDescription = tx("لون", "Colour")
                setOnClickListener { drawCanvas.color = c; buildDrawBar() }
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(6) })
        }
        add(drawTool(Ic.UNDO, tx("تراجع", "Undo")) { drawCanvas.undo() })
        add(drawTool(Ic.TRASH, tx("امسح", "Clear")) { drawCanvas.clearPage() })
        val done = UiKit.chip(this, tx("خلصت، كمّل الفيديو", "Done, resume video"), true) { stopDraw() }
        drawRow.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
    }

    private fun startDraw() {
        val sc = RecorderState.scene ?: return
        if (drawing) return
        drawing = true
        pausedByDraw = sc.playing
        if (sc.playing) sc.playPause()
        if (editor.gestureMode != 0) editor.gestureMode = 0
        drawCanvas.clearMode("overlay")
        drawCanvas.visibility = View.VISIBLE
        drawBar.visibility = View.VISIBLE
        buildDrawBar()
        drawPushed = -1L
        ui.post(drawTick)
        toast(tx("الفيديو وقف — ارسم واكتب عليه، وبينسجّل", "Video paused — draw and write on it; it's recorded"))
    }

    private fun stopDraw() {
        if (!drawing) return
        drawing = false
        ui.removeCallbacks(drawTick)
        drawCanvas.visibility = View.GONE
        drawBar.visibility = View.GONE
        drawCanvas.clearMode("overlay")
        val sc = RecorderState.scene
        sc?.setOverlay(null)
        if (pausedByDraw && sc != null && !sc.playing) sc.playPause()
        pausedByDraw = false
        updateLive()
    }

    /** ينسخ الرسم لصورة شفافة ويبعتها للتسجيل (~15 مرة بالثانية لما يتغير) */
    private val drawTick = object : Runnable {
        override fun run() {
            if (!drawing) return
            val sc = RecorderState.scene
            if (sc == null || !RecorderState.isBusy) { stopDraw(); return }
            if (drawCanvas.drawCount != drawPushed && drawCanvas.width > 0) {
                drawPushed = drawCanvas.drawCount
                val src = cfg.layout.source
                val bw = (src.w * cfg.outW).toInt().coerceIn(16, 1280)
                val bh = (bw * drawCanvas.height / drawCanvas.width.toFloat()).toInt().coerceIn(16, 1280)
                try {
                    val b = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(b)
                    c.scale(bw / drawCanvas.width.toFloat(), bh / drawCanvas.height.toFloat())
                    drawCanvas.draw(c)
                    sc.setOverlay(b)
                } catch (_: Throwable) {}
            }
            ui.postDelayed(this, 66)
        }
    }

    private fun askDrawText(nx: Float, ny: Float) {
        val et = android.widget.EditText(this).apply { hint = tx("اكتب…", "Type…"); textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        AlertDialog.Builder(this).setTitle(tx("كتابة على الفيديو", "Text on the video")).setView(et)
            .setPositiveButton(tx("ضيف", "Add")) { _, _ -> val s = et.text.toString().trim(); if (s.isNotEmpty()) drawCanvas.addText(nx, ny, s) }
            .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    /** يرتّب الشاشة حسب اتجاهها: بالعرض الفيديو بياخد معظم الشاشة والأزرار بعمود على الجنب */
    private fun arrange() {
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (arrangedLand == land) return
        arrangedLand = land
        rootLL.removeAllViews(); sideCol.removeAllViews()
        (sideScroll.parent as? ViewGroup)?.removeView(sideScroll)
        if (!land) {
            rootLL.orientation = LinearLayout.VERTICAL
            for ((v, lp) in portraitKids) rootLL.addView(v, lp)
        } else {
            rootLL.orientation = LinearLayout.HORIZONTAL
            rootLL.addView(editor, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            val rest = portraitKids.map { it.first }.filter { it !== editor && it !== topRow && it !== ctlRow && it !== statusText }
            for (v in listOf(topRow, ctlRow, statusText) + rest)
                sideCol.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            val w = min(dp(340), (resources.displayMetrics.widthPixels * 0.42f).toInt())
            rootLL.addView(sideScroll, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(10) })
        }
    }

    /** الاتجاه التلقائي: حسب ميلان الجوال (الشاشة تدور مع الجوال قبل التسجيل، ثم تثبت عند البدء) */
    private fun autoPortrait(): Boolean = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** في الوضع التلقائي الشاشة تدور مع الجوال قبل التسجيل وتثبت أثناءه؛ وفي الاختيار اليدوي تبقى عمودية */
    private fun applyScreenOrientation() {
        val want = when {
            cfg.orientMode != "auto" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            RecorderState.isBusy -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        if (requestedOrientation != want) requestedOrientation = want
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (cfg.orientMode == "auto" && !RecorderState.isBusy && autoPortrait() != cfg.portrait) {
            cfg.portrait = autoPortrait()
            val id = if (cfg.layout.presetId in 1..5) cfg.layout.presetId else 1
            cfg.layout = Presets.build(id, cfg.portrait, cfg.layout.ratio, cfg.layout)
            ConfigStore.save(this, cfg)
        }
        arrange()
        // الوضع الأفقي: نخفي الصفوف الأقل أهمية كي تبقى المعاينة كبيرة
        val land = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        (presetRow.parent as View).visibility = if (land || RecorderState.isBusy) View.GONE else View.VISIBLE
        noteText.visibility = if (land) View.GONE else View.VISIBLE
        render(); editor.refresh()
        if (!RecorderState.isBusy) bindPreview()
        editor.post { applyPreviewRotation() }
    }

    /** صوت الفيديو في التسجيل: clean = نظيف من الجوال، mic = من المايك فقط، off = بدون */
    private fun srcAudioMode(): String = if (cfg.srcAudioOn) "clean" else if (cfg.srcViaMic) "mic" else "off"

    private fun setSrcAudioMode(k: String) {
        when (k) {
            "clean" -> { cfg.srcAudioOn = true; cfg.srcViaMic = false }
            "mic" -> { cfg.srcAudioOn = false; cfg.srcViaMic = true; cfg.silentPlayback = false; cfg.echoSuppress = false; cfg.micOn = true }
            else -> { cfg.srcAudioOn = false; cfg.srcViaMic = false }
        }
        ConfigStore.save(this, cfg)
    }

    // صورة الفيديو المصغّرة بعد "تدوير الفيديو" — حتى يظهر التدوير قبل التسجيل كما سيُسجَّل
    private var rotThumbSrc: android.graphics.Bitmap? = null
    private var rotThumbDeg = 0
    private var rotThumb: android.graphics.Bitmap? = null

    private fun rotatedThumb(): android.graphics.Bitmap? {
        val b = videoThumb ?: return null
        val deg = ((cfg.srcRot % 360) + 360) % 360
        if (deg == 0) return b
        if (rotThumb != null && rotThumbSrc === b && rotThumbDeg == deg) return rotThumb
        rotThumb = try { android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true) } catch (_: Throwable) { b }
        rotThumbSrc = b; rotThumbDeg = deg
        return rotThumb
    }

    private fun micSrcLabel(): String = "🎤 " + when (cfg.micSource) { "builtin" -> tx("مايك الجوال", "Phone mic"); "usb" -> tx("مايك USB", "USB mic"); else -> tx("مايك تلقائي", "Mic: auto") }

    /** خيارات ثانوية (دقة، إطارات، صوت المصدر، شكل الكاميرا) في قائمة واحدة بدل صف مزدحم */
    private fun showMoreQuick() {
        if (RecorderState.isBusy) return
        val items = arrayOf(
            tx("الدقة: ", "Resolution: ") + (if (cfg.shortSide == 1080) "1080p" else "720p"),
            tx("الإطارات: ", "Frame rate: ") + "${cfg.fps} fps" + if (cfg.fps == 60) " ⚠" else "",
            tx("شكل الكاميرا: ", "Camera shape: ") + shapeChip.text,
        )
        AlertDialog.Builder(this).setTitle(tx("المزيد", "More")).setItems(items) { _, i ->
            when (i) {
                0 -> resChip.performClick()
                1 -> fpsChip.performClick()
                2 -> shapeChip.performClick()
            }
            ui.postDelayed({ showMoreQuick() }, 150)
        }.setNegativeButton(tx("إغلاق", "Close"), null).show()
    }

    /** ملء الشاشة أثناء التسجيل: تختفي كل الأزرار ويبقى المحرر/المعاينة مع أزرار جانبية صغيرة */
    private fun setFullScreen(on: Boolean) {
        if (fullScreen == on) return
        fullScreen = on
        fsPanel.visibility = if (on) View.VISIBLE else View.GONE
        val ic = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ic.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            ic.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        render()
    }

    private fun hideChromeIfFull() {
        if (!fullScreen) { for (v in chromeAlways) v.visibility = View.VISIBLE; return }
        for (v in chrome) v.visibility = View.GONE
    }

    // ───────────────────────── render ─────────────────────────

    private fun changed(layoutChanged: Boolean) {
        ConfigStore.save(this, cfg)
        render()
        if (layoutChanged) editor.refresh()
    }

    private fun applyPreset(id: Int) {
        if (cfg.noCamera) { cfg.noCamera = false; editor.noCam = false; bindPreview() }
        Presets.build(id, cfg.portrait, cfg.layout.ratio, cfg.layout)
        changed(true)
    }

    /** بدون كاميرا ⇄ رجّع الكاميرا */
    private fun setNoCamera(on: Boolean) {
        if (RecorderState.isBusy) { toast(tx("غيّرها قبل ما تبلّش التسجيل", "Change it before you start recording")); return }
        cfg.noCamera = on
        if (on) {
            // المصدر بياخد الإطار كله
            Presets.build(1, cfg.portrait, cfg.layout.ratio, cfg.layout)
            cfg.layout.source = NRect(0f, 0f, 1f, 1f)
            try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Throwable) {}
        }
        editor.noCam = on
        if (!on) bindPreview()
        changed(true)
        toast(if (on) tx("بدون كاميرا: بيتسجّل المصدر والصوت بس", "No camera: only the source and the sound are recorded") else tx("رجعت الكاميرا", "Camera is back"))
    }

    /** جنبًا لجنب بس بالعرض، وفوق/تحت بس بالطول، وما في 70/30 */
    private fun fixPreset() {
        val id = cfg.layout.presetId
        val want = when {
            id == 2 -> if (cfg.portrait) 4 else 3
            id == 3 && cfg.portrait -> 4
            id == 4 && !cfg.portrait -> 3
            else -> id
        }
        if (want != id) { Presets.build(want, cfg.portrait, cfg.layout.ratio, cfg.layout); ConfigStore.save(this, cfg); if (::editor.isInitialized) editor.refresh() }
    }

    private fun renderPresets() {
        fixPreset()
        val id = cfg.layout.presetId
        if (::noCamChip.isInitialized) setChipSelected(noCamChip, cfg.noCamera)
        for ((i, c) in presetChips.withIndex()) {
            setChipSelected(c, id == i + 1 && !cfg.noCamera)
            c.visibility = when (i + 1) { 2 -> View.GONE; 3 -> if (cfg.portrait) View.GONE else View.VISIBLE; 4 -> if (cfg.portrait) View.VISIBLE else View.GONE; else -> View.VISIBLE }
        }
        if (::layBar.isInitialized) setBar(layBar, tx("الترتيب", "Layout"), if (cfg.noCamera) tx("بدون كاميرا", "No camera") else (Presets.names.getOrNull(id - 1) ?: tx("مخصص", "Custom")))
        ratioRow.visibility = if (id in 3..4 && !RecorderState.isBusy) View.VISIBLE else View.GONE
        if (id in 2..4) {
            val pct = (cfg.layout.ratio * 100).toInt().coerceIn(30, 80)
            ratioSeek.progress = pct - 30
            ratioLabel.text = tx("الفيديو", "Video") + " $pct%"
        }
    }

    private fun render() {
        if (!::editor.isInitialized) return
        if (fullScreen && !RecorderState.isBusy) {
            fullScreen = false
            fsPanel.visibility = View.GONE
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        editor.cfg = cfg
        val dm = resources.displayMetrics
        val sh = min(dm.widthPixels, dm.heightPixels).toFloat(); val lo = max(dm.widthPixels, dm.heightPixels).toFloat()
        val videoMode = cfg.mode == SourceMode.VIDEO
        val rotQ = cfg.srcRot % 180 != 0
        editor.screenAspect = if (videoMode) (if (rotQ) 1f / videoAspect else videoAspect) else if (cfg.portrait) sh / lo else lo / sh
        val busy = RecorderState.isBusy
        val ph = RecorderState.phase
        val liveNow = videoMode && (ph == RecorderState.Phase.RECORDING || ph == RecorderState.Phase.PAUSED)
        editor.live = liveNow
        editor.locked = busy && !liveNow
        if (!liveNow) editor.gestureMode = 0
        editor.monitor = busy && videoMode
        editor.sourceThumb = if (videoMode) rotatedThumb() else null
        val curItem = Playlist.current()
        editor.sourceLabel = if (!videoMode) tx("الفيديو / الشاشة", "VIDEO / SCREEN") else if (curItem == null) tx("اختر فيديو", "Choose a video") else (if (curItem.image) "🖼 " else "🎞 ") + curItem.name
        editor.invalidate()

        setChipSelected(modeScreenChip, !videoMode); setChipSelected(modeVideoChip, videoMode)
        pickChip.visibility = if (videoMode) View.VISIBLE else View.GONE
        listChip.visibility = if (videoMode) View.VISIBLE else View.GONE
        listChip.text = "📋 " + tx("القائمة", "List") + " (${Playlist.items.size})"
        linkChip.visibility = if (videoMode) View.VISIBLE else View.GONE
        (modeRow.parent as View).visibility = if (busy) View.GONE else View.VISIBLE
        fitChip.text = when (cfg.sourceFit) { SourceFit.FIT -> tx("الصورة: كاملة", "Picture: whole"); SourceFit.FILL -> tx("الصورة: ملء", "Picture: fill"); SourceFit.STRETCH -> tx("الصورة: تمديد", "Picture: stretch") }
        srcChip.text = "🔊 " + when (srcAudioMode()) { "clean" -> tx("صوت الفيديو: نظيف", "Video sound: clean"); "mic" -> tx("صوت الفيديو: من المايك", "Video sound: via mic"); else -> tx("صوت الفيديو: بدون", "Video sound: none") }
        micSrcChip.text = micSrcLabel()
        applyScreenOrientation()

        orientChip.text = when (cfg.orientMode) { "auto" -> tx("اتجاه: تلقائي", "Orientation: auto"); else -> if (cfg.portrait) tx("عمودي 9:16", "Portrait 9:16") else tx("أفقي 16:9", "Landscape 16:9") }
        resChip.text = if (cfg.shortSide == 1080) "1080p" else "720p"
        fpsChip.text = if (cfg.fps == 60) "60 fps ⚠" else "${cfg.fps} fps"
        micChip.text = if (cfg.micOn) tx("المايك: يعمل", "Mic ON") else tx("المايك: مطفأ", "Mic OFF")
        shapeChip.text = when (cfg.layout.shape) { CamShape.RECT -> tx("مستطيل", "Rectangle"); CamShape.ROUNDED -> tx("زوايا دائرية", "Rounded"); CamShape.CIRCLE -> tx("دائرة", "Circle") }
        setChipSelected(micChip, cfg.micOn); setChipSelected(srcChip, cfg.srcAudioOn)
        renderPresets()

        val landNow = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        optBar.visibility = if (busy) View.GONE else View.VISIBLE
        setBar(srcBar, tx("المصدر", "Source"), if (!videoMode) tx("الشاشة", "Screen") else (curItem?.name ?: tx("اختر فيديو", "choose a video")) + if (Playlist.items.size > 1) " (${Playlist.items.size})" else "")
        setBar(avBar, tx("الصوت والصورة", "Sound & picture"), (if (cfg.portrait) "9:16" else "16:9") + " · " + (if (cfg.shortSide == 1080) "1080p" else "720p") + " · " + if (cfg.micOn) tx("مايك", "mic") else tx("بدون مايك", "no mic"))

        val phase = RecorderState.phase
        when (phase) {
            RecorderState.Phase.IDLE -> {
                recBtn.text = tx("سجّل", "REC"); styleRec(false); pauseChip.visibility = View.INVISIBLE
                flipChip.text = "⟲ " + tx("قلب", "Flip"); flipChip.visibility = View.VISIBLE
            }
            RecorderState.Phase.STARTING, RecorderState.Phase.COUNTDOWN -> {
                recBtn.text = tx("إلغاء", "CANCEL"); styleRec(true); pauseChip.visibility = View.INVISIBLE; flipChip.visibility = View.INVISIBLE
            }
            RecorderState.Phase.RECORDING, RecorderState.Phase.PAUSED -> {
                recBtn.text = "■ " + tx("إيقاف", "STOP"); styleRec(true)
                pauseChip.visibility = View.VISIBLE; pauseChip.text = if (phase == RecorderState.Phase.PAUSED) "▶ " + tx("استئناف", "Resume") else "⏸ " + tx("إيقاف مؤقت", "Pause")
                flipChip.visibility = View.VISIBLE; flipChip.text = "⟲ " + tx("قلب", "Flip")
            }
            RecorderState.Phase.FINISHING -> {
                recBtn.text = "…"; styleRec(true); pauseChip.visibility = View.INVISIBLE; flipChip.visibility = View.INVISIBLE
            }
        }
        editor.overlayText = if (phase == RecorderState.Phase.COUNTDOWN && RecorderState.countdownLeft > 0) RecorderState.countdownLeft.toString() else ""
        updateStatus()
        updateLive()
        hideChromeIfFull()
    }

    private fun styleRec(active: Boolean) {
        recBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.parseColor("#7A1F1A") else Color.parseColor("#FF3B30"))
            setStroke(dp(4), Color.WHITE)
        }
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val phase = RecorderState.phase
        when (phase) {
            RecorderState.Phase.IDLE -> {
                val rv = try { CodecSupport.resolve(cfg) } catch (_: Throwable) { null }
                val br = rv?.bitrate ?: cfg.effectiveBitrate()
                val est = StorageUtil.estimateSeconds(br, cfg.micOn || cfg.srcAudioOn)
                infoText.text = tx("متاح ", "Free ") + "${StorageUtil.formatBytes(StorageUtil.freeBytes())} • ≈ ${StorageUtil.formatDuration(est)} • " +
                    "${rv?.w ?: cfg.outW}x${rv?.h ?: cfg.outH} ${rv?.fps ?: cfg.fps}fps"
                statusText.text = tx("جاهز", "Ready")
                noteText.text = if (cfg.mode == SourceMode.VIDEO)
                    tx("الفيديو يُشغَّل داخل التطبيق ويُسجَّل مع كاميرتك — بدون تصوير الشاشة. استعمل سماعات.", "The video plays inside this app and is recorded with your camera — no screen capture. Use headphones.")
                else tx("عندما يسأل أندرويد اختر \"الشاشة كاملة\" ثم افتح يوتيوب. استعمل سماعات لتجنب الصدى.", "When Android asks, choose “Entire screen”, then open YouTube. Use headphones to avoid echo.")
            }
            RecorderState.Phase.STARTING -> { statusText.text = tx("تجهيز…", "Preparing…"); noteText.text = "" }
            RecorderState.Phase.COUNTDOWN -> { statusText.text = tx("يبدأ بعد ", "Starting in ") + "${RecorderState.countdownLeft}…"; noteText.text = tx("تقدر تنتقل لتطبيق الفيديو الآن.", "You can switch to your video app now.") }
            RecorderState.Phase.RECORDING, RecorderState.Phase.PAUSED -> {
                val s = RecorderState.elapsedMs() / 1000
                val tm = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                fun bars(l: Int): String {
                    val n = (Math.sqrt(l / 32768.0) * 6).toInt().coerceIn(0, 6)
                    return "▮".repeat(n) + "▯".repeat(6 - n)
                }
                val micTxt = when {
                    !cfg.micOn -> ""
                    s >= 2 && !RecorderState.micAlive -> "\n🎤 " + tx("لا إشارة", "NO SIGNAL") + " ⚠"
                    else -> "\n🎤 " + bars(RecorderState.micLevel)
                }
                val srcTxt = if (cfg.srcAudioOn) (if (cfg.micOn) "   " else "\n") + "🔊 " + bars(RecorderState.srcLevel) else ""
                statusText.text = (if (phase == RecorderState.Phase.PAUSED) "⏸ " else "● ") + tm + micTxt + srcTxt +
                    (if (RecorderState.micMuted) "  🎤✕" else "") + (if (RecorderState.srcMuted) "  🔊✕" else "") +
                    (if (RecorderState.scene?.standby == true) "  ⏸ " + tx("انتظار", "standby") else "")
                infoText.text = RecorderState.resolvedInfo
                noteText.text = RecorderState.notes.joinToString("\n")
            }
            RecorderState.Phase.FINISHING -> { statusText.text = tx("إنهاء الفيديو…", "Finishing video…"); noteText.text = tx("انتظر — لا تغلق التطبيق", "Please wait — do not close the app") }
        }
    }

    // ───────────────────────── actions ─────────────────────────

    private fun serviceCmd(action: String) {
        startService(Intent(this, RecorderService::class.java).setAction(action))
    }

    private fun flipCamera() {
        if (RecorderState.isBusy) { serviceCmd(RecorderService.ACTION_SWITCH_CAMERA); return }
        cfg.frontCamera = !cfg.frontCamera
        ConfigStore.save(this, cfg)
        bindPreview()
    }

    private fun onPauseClicked() {
        serviceCmd(if (RecorderState.phase == RecorderState.Phase.PAUSED) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE)
    }

    private fun onRecordClicked() {
        if (RecorderState.isBusy) {
            if (RecorderState.phase != RecorderState.Phase.FINISHING) serviceCmd(RecorderService.ACTION_STOP)
            return
        }
        if (!hasCamera() || (cfg.micOn && !hasMic())) {
            toast(tx("يلزم إذن الكاميرا والمايك", "Camera and microphone permissions are required"))
            requestPermissionsIfNeeded(); return
        }
        if (cfg.mode == SourceMode.VIDEO && Playlist.items.isEmpty()) {
            toast(tx("أضف فيديو أو صورة أو رابطًا أولًا", "Add a video, an image or a link first"))
            return
        }
        // الاتجاه التلقائي يُحسم الآن ويثبت طوال التسجيل
        if (cfg.orientMode == "auto") {
            val want = autoPortrait()
            if (want != cfg.portrait) {
                cfg.portrait = want
                val id = if (cfg.layout.presetId in 1..5) cfg.layout.presetId else 1
                cfg.layout = Presets.build(id, cfg.portrait, cfg.layout.ratio, cfg.layout)
                render(); editor.refresh()
            }
        }
        ConfigStore.save(this, cfg)
        val rv = CodecSupport.resolve(cfg)
        val est = StorageUtil.estimateSeconds(rv.bitrate, cfg.micOn || cfg.srcAudioOn)
        val free = StorageUtil.freeBytes()
        if (free < 300L * 1024 * 1024) {
            AlertDialog.Builder(this).setTitle(tx("المساحة غير كافية", "Not enough storage"))
                .setMessage(tx("المتاح ${StorageUtil.formatBytes(free)} فقط. وفّر مساحة قبل التسجيل.", "Only ${StorageUtil.formatBytes(free)} free. Free some space before recording."))
                .setPositiveButton(tx("حسنًا", "OK"), null).show()
            return
        }
        if (est < 10 * 60) {
            AlertDialog.Builder(this).setTitle(tx("مساحة قليلة", "Low storage"))
                .setMessage(tx("المساحة تكفي لحوالي ${StorageUtil.formatDuration(est)} فقط بهذه الجودة. متابعة؟", "Free space allows only about ${StorageUtil.formatDuration(est)} of recording at this quality. Continue?"))
                .setPositiveButton(tx("سجّل", "Record")) { _, _ -> echoCheckThen { launchCapture() } }
                .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
            return
        }
        echoCheckThen { launchCapture() }
    }

    private fun launchCapture() {
        unbindPreview() // الخدمة ستفتح الكاميرا
        if (cfg.mode == SourceMode.VIDEO) { // لا حاجة لإذن تصوير الشاشة
            ContextCompat.startForegroundService(this, Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START))
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // ───────────────────────── مصدر الفيديو + قائمة التشغيل ─────────────────────────

    private fun setMode(m: SourceMode) {
        if (RecorderState.isBusy) return
        cfg.mode = m
        ConfigStore.save(this, cfg)
        if (m == SourceMode.VIDEO) {
            if (Playlist.items.isEmpty()) pickMultiLauncher.launch(arrayOf("video/*", "image/*")) else loadVideoInfoAsync()
        }
        render(); editor.refresh()
    }

    /** نتيجة اختيار ملفات (قبل التسجيل أو أثناءه). showNow = اعرض أول ملف جديد مباشرة */
    private fun onFilesPicked(uris: List<Uri>, showNow: Boolean) {
        val newItems = uris.map { u ->
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            val type = contentResolver.getType(u) ?: ""
            PlayItem(u.toString(), queryName(u) ?: "file", type.startsWith("image/"))
        }
        val sc = RecorderState.scene
        if (RecorderState.isBusy && sc != null) {
            if (newItems.isNotEmpty()) sc.addItems(newItems, showNow)
            if (importAutoStandby) {
                importAutoStandby = false
                if (sc.standby) sc.exitStandby(true)
            }
            render()
            return
        }
        if (newItems.isEmpty()) return
        val first = Playlist.items.size
        Playlist.items.addAll(newItems)
        if (showNow || first == 0) Playlist.index = first
        Playlist.save(this)
        cfg.mode = SourceMode.VIDEO
        ConfigStore.save(this, cfg)
        loadVideoInfoAsync()
        render(); editor.refresh()
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Throwable) { null }

    /** يقرأ أبعاد العنصر الحالي ولقطة مصغّرة في خيط خلفي ليعرضها المحرر كمعاينة */
    private fun loadVideoInfoAsync() {
        val cur = Playlist.current()
        if (cur == null) { videoThumb = null; videoAspect = 16f / 9f; return }
        val u = cur.uri
        Thread {
            var thumb: android.graphics.Bitmap? = null
            var aspect = 16f / 9f
            if (cur.image) {
                thumb = ImageLoader.load(applicationContext, u, 640)
                if (thumb != null) aspect = thumb.width.toFloat() / thumb.height
            } else {
                val info = VideoProbe.probe(applicationContext, u, true)
                if (info != null) { thumb = info.thumb; aspect = info.width.toFloat() / info.height }
            }
            runOnUiThread {
                if (Playlist.current()?.uri == u) {
                    videoThumb = thumb
                    videoAspect = aspect
                    if (cfg.orientMode == "auto" && !RecorderState.isBusy && autoPortrait() != cfg.portrait) {
                        cfg.portrait = autoPortrait()
                        val id = if (cfg.layout.presetId in 1..5) cfg.layout.presetId else 1
                        cfg.layout = Presets.build(id, cfg.portrait, cfg.layout.ratio, cfg.layout)
                        ConfigStore.save(this, cfg)
                    }
                    render(); editor.refresh()
                }
            }
        }.start()
    }

    private fun showLinkDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "https://example.com/video.mp4"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        fun add(showNow: Boolean) {
            val link = input.text.toString().trim()
            val host = try { Uri.parse(link).host?.lowercase() ?: "" } catch (_: Throwable) { "" }
            when {
                !link.startsWith("https://") -> toast(tx("الرابط يجب أن يبدأ بـ https://", "The link must start with https://"))
                host.contains("youtube.com") || host.contains("youtu.be") || host.contains("youtube-nocookie.com") -> showYoutubeDialog()
                else -> {
                    val item = PlayItem(link, Uri.parse(link).lastPathSegment ?: "link", false)
                    val sc = RecorderState.scene
                    if (RecorderState.isBusy && sc != null) {
                        sc.addItems(listOf(item), showNow)
                    } else {
                        val first = Playlist.items.size
                        Playlist.items.add(item)
                        if (showNow || first == 0) Playlist.index = first
                        Playlist.save(this)
                        cfg.mode = SourceMode.VIDEO
                        ConfigStore.save(this, cfg)
                        loadVideoInfoAsync()
                        render(); editor.refresh()
                    }
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(tx("رابط فيديو مباشر", "Direct video link"))
            .setMessage(tx("الصق رابط https مباشر لملف فيديو (مثل …/clip.mp4). استعمل فقط فيديوهات يحق لك استعمالها.", "Paste a direct https link to a video file (for example …/clip.mp4). Only use videos you have the right to use."))
            .setView(box)
            .setPositiveButton(tx("أضف للقائمة", "Add to list")) { _, _ -> add(false) }
            .setNeutralButton(tx("اعرضه الآن", "Show now")) { _, _ -> add(true) }
            .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    /** رسالة من التطبيق (ليست من يوتيوب): لماذا لا نشغّل يوتيوب داخل التطبيق، والبديل الرسمي */
    private fun showYoutubeDialog() {
        val b = AlertDialog.Builder(this).setTitle(tx("لا يمكن تشغيل يوتيوب داخل التطبيق", "YouTube can't be played inside this app"))
            .setMessage(tx("هذه الرسالة من التطبيق، ليست من يوتيوب.\n\nيوتيوب لا يعطي التطبيقات الأخرى بث الفيديو، فلا يمكن تشغيل رابطه ودمجه هنا.\n\nللتعليق على فيديو يوتيوب استعمل وضع الشاشة: افتح يوتيوب، والتطبيق يسجّل الشاشة مع كاميرتك.",
                "This message comes from Reaction Recorder, not from YouTube.\n\nYouTube does not give other apps access to its video stream, so a YouTube link can't be played and mixed here.\n\nTo react to a YouTube video use Screen mode: open YouTube, and this app records your screen with your camera."))
            .setPositiveButton(tx("حسنًا", "OK"), null)
        if (!RecorderState.isBusy) b.setNeutralButton(tx("انتقل لوضع الشاشة", "Switch to Screen mode")) { _, _ -> setMode(SourceMode.SCREEN) }
        b.show()
    }

    private fun showPlaylistDialog() {
        val sc = RecorderState.scene
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        var dlg: AlertDialog? = null
        fun reopen() { dlg?.dismiss(); showPlaylistDialog() }
        val list = Playlist.items
        if (list.isEmpty()) {
            col.addView(TextView(this).apply { text = tx("القائمة فارغة. اضغط \"أضف ملفات\".", "The list is empty. Tap “Add files”."); setTextColor(Color.parseColor("#9A9CA3")); setPadding(dp(8), dp(12), dp(8), dp(12)) })
        }
        for ((i, item) in list.withIndex()) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val isCur = i == Playlist.index
            val label = TextView(this).apply {
                text = (if (isCur) "▶ " else "    ") + "${i + 1}. " + (if (item.image) "🖼 " else "🎞 ") + item.name
                setTextColor(if (isCur) Color.parseColor("#FF6B63") else Color.WHITE)
                textSize = 14f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener {
                    if (sc != null) sc.jump(i)
                    else { Playlist.index = i; Playlist.save(this@MainActivity); loadVideoInfoAsync(); render() }
                    dlg?.dismiss()
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(chip("▲") {
                if (sc != null) sc.moveItem(i, -1) else { Playlist.move(i, -1); Playlist.save(this) }
                reopen()
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2, 2))
            row.addView(chip("▼") {
                if (sc != null) sc.moveItem(i, 1) else { Playlist.move(i, 1); Playlist.save(this) }
                reopen()
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2, 2))
            row.addView(chip("✕") {
                if (sc != null) sc.removeItem(i) else { Playlist.remove(i); Playlist.save(this); loadVideoInfoAsync() }
                reopen()
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2, 0))
            col.addView(row)
        }
        val sv = ScrollView(this).apply { addView(col) }
        val d = AlertDialog.Builder(this).setTitle(tx("القائمة (يشتغل فقط عندما تختار)", "Playlist (plays only when you choose)")).setView(sv)
            .setPositiveButton("＋ " + tx("أضف ملفات", "Add files")) { _, _ -> pickMultiLauncher.launch(arrayOf("video/*", "image/*")) }
            .setNeutralButton("🔗 " + tx("أضف رابطًا", "Add link")) { _, _ -> showLinkDialog() }
            .setNegativeButton(tx("إغلاق", "Close")) { _, _ -> render() }
            .create()
        dlg = d
        d.show()
    }

    // ───────────────────────── اللوحة الحيّة أثناء التسجيل ─────────────────────────

    private fun scrollOf(vararg chips: TextView): HorizontalScrollView {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (c in chips) row.addView(c, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6))
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun buildLiveBox(root: LinearLayout) {
        liveBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        // شريط التشغيل (وضع الفيديو فقط)
        playChip = chip("▶") { RecorderState.scene?.playPause() }
        loopChip = chip("🔁") { RecorderState.scene?.toggleLoop(); updateLive() }
        val transport = scrollOf(
            chip("⏮") { RecorderState.scene?.prev() },
            chip(tx("رجوع 10ث", "Back 10s")) { RecorderState.scene?.seekBy(-10_000L) },
            playChip,
            chip(tx("قدّام 10ث", "Fwd 10s")) { RecorderState.scene?.seekBy(10_000L) },
            chip("⏭") { RecorderState.scene?.next() },
            chip(tx("لف الفيديو 90°", "Rotate 90°")) { rotateSource() },
            loopChip,
            chip(tx("ارسم على الفيديو", "Draw on video")) { startDraw() }.also { drawChip = it },
        )
        transportScroll = transport
        liveBox.addView(transport)

        seekRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        timeText = QuietText(this).apply { setTextColor(Color.parseColor("#9A9CA3")); textSize = 11f; typeface = Typeface.MONOSPACE }
        seekBar = SeekBar(this).apply {
            max = 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) timeText.text = fmtTime(p.toLong()) + " / " + fmtTime(s.max.toLong()) }
                override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(s: SeekBar) { RecorderState.scene?.seekAbs(s.progress.toLong()); seeking = false }
            })
        }
        seekRow.addView(timeText, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        liveBox.addView(seekRow)

        // انتهى الفيديو → زر التالي (لا يشتغل تلقائيًا)
        endedRow = LinearLayout(this).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        endedText = QuietText(this).apply { setTextColor(Color.parseColor("#FFB86B")); textSize = 13f; text = tx("انتهى الفيديو", "Video ended") }
        nextBtn = chip("▶ " + tx("شغّل التالي", "Play next")) { RecorderState.scene?.playNextAfterEnd() }
        endedRow.addView(endedText, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10))
        endedRow.addView(nextBtn)
        liveBox.addView(endedRow)

        // صف المشهد: استيراد، قائمة، انتظار، كاميرا، تحريك، صوت
        standbyChip = chip("⏸ " + tx("انتظار", "Standby")) { RecorderState.scene?.toggleStandby(); updateLive() }
        camChip = chip("📷 " + tx("الكاميرا", "Camera")) { showCameraMenu() }
        gestureChip = chip("🎞 " + tx("حرّك الفيديو", "Move video")) {
            editor.gestureMode = if (editor.gestureMode == 0) 1 else 0
            editor.invalidate(); updateLive()
        }
        fullChip = chip("⛶ 100%") {
            val sc = RecorderState.scene
            if (sc != null) {
                sc.resetView()
                cfg.layout.source = NRect(0f, 0f, 1f, 1f); cfg.layout.presetId = 6
                sc.applyLayout(cfg.layout)
                editor.refresh()
            }
        }
        fitLiveChip = chip("Fit") { RecorderState.scene?.cycleFit(); updateLive() }
        micLiveChip = chip("🎤 ON") { RecorderState.scene?.let { it.setMicMuted(!RecorderState.micMuted) }; updateLive() }
        srcLiveChip = chip("🔊 ON") { RecorderState.scene?.let { it.setSrcMuted(!RecorderState.srcMuted) }; updateLive() }
        allMuteChip = chip("🔇 " + tx("كتم الكل", "Mute all")) { RecorderState.scene?.let { it.muteAll(!it.allMuted) }; updateLive() }
        silentChip = chip("🔈 " + tx("السماعة", "Speaker")) { RecorderState.scene?.toggleSilent(); updateLive() }
        camSwapChip = chip("🎥→📷") { quickCameraSwap() }
        selfieChip = chip("🙈 " + tx("أخفِ السيلفي", "Hide selfie")) { RecorderState.scene?.toggleCameraVisible(); updateLive() }
        liveBox.addView(
            scrollOf(chip("＋ " + tx("استيراد", "Import")) { showImportMenu() }, standbyChip, camSwapChip, selfieChip, camChip, gestureChip, fullChip, fitLiveChip),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
        )
        liveBox.addView(
            scrollOf(chip("⛶ " + tx("ملء الشاشة", "Full screen")) { setFullScreen(true) }, micLiveChip, srcLiveChip, allMuteChip, silentChip, chip("📋 " + tx("القائمة", "List")) { showPlaylistDialog() }),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
        )
        root.addView(liveBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** يحدّث أزرار اللوحة الحيّة من حالة المشهد (يُستدعى 4 مرات في الثانية أثناء التسجيل) */
    private fun updateLive() {
        if (!::liveBox.isInitialized) return
        val sc = RecorderState.scene
        val phase = RecorderState.phase
        val liveNow = sc != null && (phase == RecorderState.Phase.RECORDING || phase == RecorderState.Phase.PAUSED)
        liveBox.visibility = if (liveNow) View.VISIBLE else View.GONE
        hideChromeIfFull()
        if (!liveNow || sc == null) return
        val vm = sc.videoMode
        transportScroll.visibility = if (vm) View.VISIBLE else View.GONE
        seekRow.visibility = if (vm) View.VISIBLE else View.GONE
        gestureChip.visibility = if (vm) View.VISIBLE else View.GONE
        fullChip.visibility = if (vm) View.VISIBLE else View.GONE
        fitLiveChip.visibility = if (vm) View.VISIBLE else View.GONE
        silentChip.visibility = if (vm) View.VISIBLE else View.GONE

        playChip.text = if (sc.playing) "⏸" else "▶"
        setChipSelected(loopChip, sc.loop)
        if (vm && !seeking) {
            val dur = sc.durationMs
            val pos = sc.positionMs
            seekBar.max = if (dur > 0L) dur.toInt() else 1
            seekBar.progress = if (dur > 0L) pos.coerceIn(0L, dur).toInt() else 0
            seekBar.isEnabled = dur > 0L && !sc.standby
            timeText.text = if (sc.currentIsImage) tx("صورة", "image") else fmtTime(pos) + " / " + fmtTime(dur)
        }
        val showEnded = vm && sc.ended && !sc.standby
        endedRow.visibility = if (showEnded) View.VISIBLE else View.GONE
        nextBtn.visibility = if (sc.hasNext) View.VISIBLE else View.GONE
        endedText.text = if (sc.hasNext) tx("انتهى الفيديو", "Video ended") else tx("انتهى الفيديو — آخر عنصر", "Video ended — last item")

        standbyChip.text = if (sc.standby) "▶ " + tx("اخرج من الانتظار", "Exit standby") else "⏸ " + tx("انتظار", "Standby")
        setChipSelected(standbyChip, sc.standby)
        camChip.text = when {
            sc.camMode == 1 -> "📷 " + tx("الأمامية على الشاشة", "Front on screen")
            sc.camMode == 2 -> "📷 " + tx("الخلفية على الشاشة", "Back on screen")
            !sc.cameraVisible -> "📷 " + tx("الكاميرا (مخفية)", "Camera (hidden)")
            sc.dualActive -> "📷📷 " + tx("الكاميرا", "Camera")
            else -> "📷 " + tx("الكاميرا", "Camera")
        }
        setChipSelected(camChip, sc.camMode != 0 || !sc.cameraVisible || sc.dualActive)
        camSwapChip.text = if (sc.camMode == 0) tx("الكاميرا مكان الفيديو", "Camera instead of video") else tx("رجّع الفيديو", "Back to video")
        setChipSelected(camSwapChip, sc.camMode != 0)
        for (v in listOf(camSwapChip, selfieChip, camChip)) v.visibility = if (cfg.noCamera) View.GONE else View.VISIBLE
        selfieChip.text = if (sc.cameraVisible) "🙈 " + tx("أخفِ السيلفي", "Hide selfie") else "👁 " + tx("أظهر السيلفي", "Show selfie")
        setChipSelected(selfieChip, !sc.cameraVisible)
        fsPlayChip.text = if (sc.playing) "⏸" else "▶"
        fsPlayChip.visibility = if (vm) View.VISIBLE else View.GONE
        fsMicChip.text = if (RecorderState.micMuted) "🎤✕" else "🎤"
        gestureChip.text = if (editor.gestureMode == 1) "✥ " + tx("حرّك المربعات", "Move boxes") else "🎞 " + tx("حرّك الفيديو", "Move video")
        setChipSelected(gestureChip, editor.gestureMode == 1)
        fitLiveChip.text = when (sc.fit) { SourceFit.FIT -> tx("كاملة", "Fit"); SourceFit.FILL -> tx("ملء", "Fill"); SourceFit.STRETCH -> tx("تمديد", "Stretch") }
        micLiveChip.text = if (RecorderState.micMuted) tx("المايك: مكتوم", "Mic: muted") else tx("المايك: شغّال", "Mic: on")
        setChipSelected(micLiveChip, RecorderState.micMuted)
        srcLiveChip.text = if (RecorderState.srcMuted) tx("صوت الفيديو: مكتوم", "Video sound: muted") else tx("صوت الفيديو: شغّال", "Video sound: on")
        setChipSelected(srcLiveChip, RecorderState.srcMuted)
        setChipSelected(allMuteChip, sc.allMuted)
        silentChip.text = if (sc.silent) "🔈 " + tx("السماعة مطفأة", "Speaker OFF") else "🔈 " + tx("السماعة تعمل", "Speaker ON")
        setChipSelected(silentChip, sc.silent)
        editor.dualOn = false // الكاميرا الخلفية لا تظهر كمربع صغير بل مكان الفيديو
    }

    /** تبديل سريع: الفيديو ⇄ الكاميرا. مع الكاميرتين نعرض الخلفية، وبكاميرا واحدة نعرض الأمامية (السيلفي) */
    private fun quickCameraSwap() {
        val sc = RecorderState.scene ?: return
        if (sc.camMode != 0) { sc.exitCameraMode(true); updateLive(); return }
        sc.showCamera(!sc.dualActive) { ok -> runOnUiThread { if (!ok) toast(tx("تعذر فتح الكاميرا", "Could not open the camera")); updateLive() } }
    }

    /** يدوّر كاميرا السيلفي 90° (قبل التسجيل وأثناءه) */
    private fun rotateSelfie() {
        cfg.camRotAdjust = (cfg.camRotAdjust + 90) % 360
        ConfigStore.save(this, cfg)
        RecorderState.scene?.setCamAdjust(cfg.camRotAdjust)
        applyPreviewRotation()
        toast(tx("تدوير السيلفي: ", "Selfie camera rotation: ") + "${cfg.camRotAdjust}°")
    }

    /** يدوّر الفيديو/الشاشة المعروضة 90° (قبل التسجيل وأثناءه) */
    private fun rotateSource() {
        cfg.srcRot = (cfg.srcRot + 90) % 360
        ConfigStore.save(this, cfg)
        RecorderState.scene?.setSrcRotation(cfg.srcRot)
        render(); editor.refresh()
        toast(tx("تدوير الفيديو: ", "Video rotation: ") + "${cfg.srcRot}°")
    }

    private fun applyPreviewRotation() {
        val pv = editor.previewView
        val r = cfg.camRotAdjust
        val sign = if (cfg.frontCamera && !cfg.mirrorFront) -1f else 1f
        pv.rotation = r.toFloat()
        val w = pv.width.toFloat().coerceAtLeast(1f)
        val h = pv.height.toFloat().coerceAtLeast(1f)
        val s = if (r % 180 == 0) 1f else minOf(w / h, h / w)
        pv.scaleX = sign * s
        pv.scaleY = s
    }

    private fun showCameraMenu() {
        val sc = RecorderState.scene ?: return
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun showCam(front: Boolean) = sc.showCamera(front) { ok -> runOnUiThread { if (!ok) toast(tx("تعذر فتح هذه الكاميرا", "Could not open that camera")); updateLive() } }
        if (sc.camMode != 0) {
            labels += "🎞 " + tx("ارجع للفيديو", "Back to the video"); acts.add { sc.exitCameraMode(true) }
        }
        if (sc.camMode != 2) { labels += "📷 " + tx("اعرض الكاميرا الخلفية بدل الفيديو", "Show the BACK camera instead of the video"); acts.add { showCam(false) } }
        if (sc.camMode != 1) { labels += "🤳 " + tx("اعرض الكاميرا الأمامية بدل الفيديو", "Show the FRONT camera instead of the video"); acts.add { showCam(true) } }
        labels += if (sc.cameraVisible) "🙈 " + tx("أخفِ مربع السيلفي", "Hide the selfie box") else "👁 " + tx("أظهر مربع السيلفي", "Show the selfie box"); acts.add { sc.toggleCameraVisible() }
        if (!sc.dualActive) { labels += "⟲ " + tx("اقلب كاميرا السيلفي", "Flip the selfie camera") + " (" + (if (sc.camFront) tx("أمامية", "front") else tx("خلفية", "back")) + ")"; acts.add { sc.flipCamera() } }
        labels += "↔ " + tx("المرآة: ", "Mirror: ") + (if (sc.mirror) tx("تعمل", "ON") else tx("مطفأة", "OFF")); acts.add { sc.toggleMirror() }
        labels += "↻ " + tx("دوّر السيلفي 90°", "Rotate the selfie camera 90°") + " (${cfg.camRotAdjust}°)"; acts.add { rotateSelfie() }
        if (sc.dualAvailable) {
            labels += if (sc.dualActive) "📷📷 " + tx("الأمامية والخلفية معًا: يعمل", "Front + back together: ON") else "📷📷 " + tx("الأمامية والخلفية معًا: جرّب", "Front + back together: try")
            acts.add {
                sc.setDual(!sc.dualActive) { ok ->
                    runOnUiThread { if (!ok) toast(tx("تعذر تغيير وضع الكاميرتين", "Could not change dual camera mode")); updateLive() }
                }
            }
        } else {
            labels += "📷📷 " + tx("الكاميرتان معًا (يحتاج أندرويد 11+)", "Front + back together (needs Android 11+)")
            acts.add { toast(tx("هذا الجوال لا يسمح بكاميرتين معًا", "This phone does not allow two cameras at the same time")) }
        }
        AlertDialog.Builder(this).setTitle(tx("الكاميرا", "Camera"))
            .setItems(labels.toTypedArray()) { _, i -> acts[i](); updateLive() }
            .setNegativeButton(tx("إغلاق", "Close"), null).show()
    }

    private fun showImportMenu() {
        val sc = RecorderState.scene ?: return
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (sc.videoMode) {
            labels += "📂 " + tx("أضف فيديوهات/صورًا للقائمة…", "Add videos / images to the list…"); acts.add { startImport(false) }
            labels += "▶ " + tx("اختر ملفًا واعرضه الآن…", "Choose a file and show it now…"); acts.add { startImport(true) }
            labels += "🔗 " + tx("أضف رابط فيديو…", "Add a video link…"); acts.add { showLinkDialog() }
            labels += "📋 " + tx("افتح القائمة…", "Open the playlist…"); acts.add { showPlaylistDialog() }
        }
        labels += if (sc.standby) "▶ " + tx("اخرج من شاشة الانتظار", "Leave the standby screen") else "⏸ " + tx("اعرض شاشة الانتظار", "Show the standby screen"); acts.add { sc.toggleStandby(); updateLive() }
        AlertDialog.Builder(this).setTitle(tx("استيراد / المشهد", "Import / scene"))
            .setItems(labels.toTypedArray()) { _, i -> acts[i]() }
            .setNegativeButton(tx("إغلاق", "Close"), null).show()
    }

    /** يفتح منتقي الملفات أثناء التسجيل. إن فُعّل الخيار نعرض شاشة الانتظار أثناء التصفّح */
    private fun startImport(showNow: Boolean) {
        val sc = RecorderState.scene
        if (sc != null && cfg.autoStandbyOnImport && !sc.standby) {
            sc.enterStandby()
            importAutoStandby = true
        }
        if (showNow) pickOneLauncher.launch(arrayOf("video/*", "image/*"))
        else pickMultiLauncher.launch(arrayOf("video/*", "image/*"))
    }

    private fun onStandbyPicked(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        val type = contentResolver.getType(uri) ?: ""
        cfg.standbyUri = uri.toString()
        cfg.standbyName = queryName(uri) ?: "file"
        cfg.standbyIsVideo = type.startsWith("video/")
        ConfigStore.save(this, cfg)
        standbyLabelView?.text = standbyDesc()
    }

    private fun standbyDesc(): String =
        if (cfg.standbyUri.isEmpty()) tx("شاشة نص: ", "Text screen: ") + "“${cfg.standbyText}”"
        else (if (cfg.standbyIsVideo) "🎞 " else "🖼 ") + cfg.standbyName

    // ───────────────────────── صدى: سماعات الرأس ─────────────────────────

    private fun hasHeadphones(): Boolean {
        return try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                when (d.type) {
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> true
                    else -> d.type == 26 // TYPE_BLE_HEADSET
                }
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun echoCheckThen(run: () -> Unit) {
        val risky = cfg.warnEcho && cfg.micOn && cfg.srcAudioOn && !hasHeadphones()
        if (!risky) { run(); return }
        val video = cfg.mode == SourceMode.VIDEO
        val b = AlertDialog.Builder(this).setTitle(tx("لا توجد سماعات", "No headphones detected"))
            .setMessage(
                if (video) tx("بدون سماعات يلتقط المايك صوت الفيديو من السماعة (صدى).\n\nالأفضل: سماعات.\nأو \"تشغيل صامت\": صوت الفيديو يُسجَّل نظيفًا لكن لن تسمعه.", "Without headphones the microphone picks up the video's sound from the speaker (echo).\n\nBest: plug in headphones.\nOr use “Silent playback”: the video's sound is recorded cleanly but you won't hear it from the speaker.")
                else tx("بدون سماعات يلتقط المايك صوت الفيديو الذي تعلّق عليه (صدى). استعمل سماعات لتسجيل نظيف.", "Without headphones the microphone picks up the sound of the video you are reacting to (echo). Plug in headphones for a clean recording.")
            )
            .setPositiveButton(tx("سجّل على أي حال", "Record anyway")) { _, _ -> run() }
            .setNegativeButton(tx("إلغاء", "Cancel"), null)
        if (video) b.setNeutralButton(tx("تشغيل صامت", "Silent playback")) { _, _ ->
            cfg.silentPlayback = true
            ConfigStore.save(this, cfg)
            run()
        }
        b.show()
    }

    // ───────────────────────── state / result ─────────────────────────

    private fun onStateChanged() {
        val busy = RecorderState.isBusy
        RecorderState.notice?.let { RecorderState.notice = null; toast(it) }
        render()
        if (wasBusy && !busy) { bindPreview(); handleResult() }
        wasBusy = busy
    }

    private fun handleResult() {
        val uri = RecorderState.lastUri
        val err = RecorderState.lastError
        if (RecorderState.isBusy) return
        if (uri != null) {
            RecorderState.lastUri = null
            RecorderState.lastError = null
            showReady(uri, err)
        } else if (err != null) {
            RecorderState.lastError = null
            AlertDialog.Builder(this).setTitle(tx("مشكلة في التسجيل", "Recording problem")).setMessage(err).setPositiveButton(tx("حسنًا", "OK"), null).show()
        }
    }

    /** الفيديو يُحفظ تلقائيًا عند الإيقاف (لا موافقة مطلوبة). هذه النافذة للمعلومات فقط: تشغيل، مشاركة، أو حذف (بتأكيد) */
    private fun showReady(uri: Uri, warning: String?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        box.addView(TextView(this).apply {
            text = tx("حُفظ تلقائيًا في Movies/ReactionRecorder — موجود في المعرض.", "Saved automatically in Movies/ReactionRecorder — it is already in your Gallery.") + if (warning != null) "\n\n$warning" else ""
            setTextColor(Color.parseColor("#F2F2F3")); textSize = 14f
        })
        val d = AlertDialog.Builder(this).setTitle(tx("حُفظ الفيديو ✓", "Video saved ✓")).setView(box).setCancelable(true)
            .setPositiveButton(tx("إغلاق", "Close"), null).create()
        fun btn(label: String, run: () -> Unit) = TextView(this).apply {
            text = label; setTextColor(Color.parseColor(UiKit.TEXT)); textSize = 15f; typeface = Fonts.semi
            gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(12), dp(10), dp(12), dp(10))
            background = UiKit.ripple(UiKit.bg(UiKit.CARD2, 12, this@MainActivity, UiKit.LINE))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener { run(); }
        }
        box.addView(btn("▶  " + tx("تشغيل", "Play")) {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        })
        val sid = RecorderState.lastSessionId
        RecorderState.lastSessionId = null
        if (sid != null) {
            box.addView(btn("📡  " + tx("ادمج مع الأجهزة المساعدة…", "Merge with remote devices…")) {
                startActivity(Intent(this, SessionActivity::class.java).putExtra("session", sid))
                d.dismiss()
            })
        }
        box.addView(btn("↗  " + tx("مشاركة", "Share")) {
            val s = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(s, tx("مشاركة الفيديو", "Share video")))
        })
        box.addView(btn("🗑  " + tx("احذف هذا الفيديو…", "Delete this video…")) {
            AlertDialog.Builder(this).setTitle(tx("حذف هذا الفيديو؟", "Delete this video?"))
                .setMessage(tx("الفيديو محفوظ. الحذف يزيله من جوالك.", "It is already saved. Deleting removes it from your phone."))
                .setPositiveButton(tx("حذف", "Delete")) { _, _ -> StorageUtil.delete(this, uri); toast(tx("حُذف", "Deleted")); d.dismiss() }
                .setNegativeButton(tx("أبقِه", "Keep it"), null).show()
        })
        d.show()
    }

    // ───────────────────────── settings dialog ─────────────────────────

    private fun showSettings() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(10)) }
        var col = root
        fun sec(key: String, title: String, sum: String = "") { col = UiKit.section(this, root, "rs_$key", title, sum) }
        fun label(t: String) = TextView(this).apply { text = t; setTextColor(Color.parseColor("#F2F2F3")); textSize = 13f; setPadding(0, dp(12), 0, dp(2)) }
        fun spinnerRow(title: String, items: List<String>, sel: Int, onSel: (Int) -> Unit) {
            col.addView(label(title))
            val sp = android.widget.Spinner(this)
            sp.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            sp.setSelection(sel)
            sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                var first = true
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { if (first) { first = false; return }; onSel(pos) }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
            col.addView(sp)
        }
        fun switchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
            col.addView(Switch(this).apply { text = title; isChecked = on; setOnCheckedChangeListener { _, v -> onChange(v) } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        fun seekRow(title: String, maxV: Int, cur: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
            val tv = label("$title: ${fmt(cur)}")
            col.addView(tv)
            col.addView(SeekBar(this).apply {
                max = maxV; progress = cur
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { tv.text = "$title: ${fmt(p)}"; if (u) onChange(p) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
        }

        sec("audio", "🎤 " + tx("الصوت", "Audio"), tx("المايك، صوت الفيديو، الصدى", "Mic, video sound, echo"))
        col.addView(label(tx("صوت الفيديو في التسجيل", "Video sound in the recording")))
        col.addView(UiKit.row(this, *listOf("clean" to tx("نظيف من الجوال", "Clean from the phone"), "mic" to tx("من المايك فقط", "Through the mic only"), "off" to tx("بدون", "None")).map { (k, n) ->
            UiKit.chip(this, n, srcAudioMode() == k) { setSrcAudioMode(k); render() }
        }.toTypedArray()))
        col.addView(TextView(this).apply {
            text = tx("«من المايك فقط»: الفيديو يشتغل من سماعة الجوال ويُلتقط صوته بالمايك مثل ما تسمعه في الغرفة (بدون نسخة نظيفة وبدون كتم الصدى).", "“Through the mic only”: the video plays from the phone speaker and its sound is captured by the mic like you hear it in the room (no clean copy, no echo cancelling).")
            setTextColor(Color.parseColor("#9A9CA3")); textSize = 11f
        })
        seekRow(tx("مستوى المايك", "Microphone volume"), 200, (cfg.micVol * 100).toInt(), { "$it%" }) { cfg.micVol = it / 100f }
        seekRow(tx("مستوى صوت المصدر", "Source volume"), 200, (cfg.srcVol * 100).toInt(), { "$it%" }) { cfg.srcVol = it / 100f }
        switchRow(tx("تخفيف الصدى (السماعات أفضل دائمًا)", "Echo suppression (VOICE mode; headphones are still best)"), cfg.echoSuppress) { cfg.echoSuppress = it }
        // مصدر الصوت (المايك)
        col.addView(label(tx("مدخل الصوت (المايك)", "Audio input (microphone)")))
        val micSrcs = listOf("auto", "builtin", "usb")
        spinnerRow(tx("مصدر المايك", "Microphone source"), listOf(tx("تلقائي — مايك USB أولًا إن وُجد (موصى به)", "Auto — USB mic first if one is plugged in (recommended)"), tx("المايك الداخلي فقط", "Built-in mic only"), tx("مايك USB/خارجي فقط", "USB / external mic only")), micSrcs.indexOf(cfg.micSource).coerceAtLeast(0)) { cfg.micSource = micSrcs[it]; ConfigStore.save(this, cfg) }
        val devText = TextView(this).apply { setTextColor(Color.parseColor("#9A9CA3")); textSize = 12f; setPadding(0, dp(6), 0, 0) }
        fun refreshDevs() {
            val list = AudioEngine.inputDevices(this).filter { it.type != android.media.AudioDeviceInfo.TYPE_TELEPHONY }
            devText.text = tx("المايكات الموجودة الآن:\n", "Microphones detected now:\n") + if (list.isEmpty()) "• " + tx("لا يوجد", "none") else list.joinToString("\n") { "• " + AudioEngine.label(it) + if (AudioEngine.isUsb(it)) "  ✔ USB" else "" }
        }
        refreshDevs()
        col.addView(devText)
        col.addView(chip("🔄 " + tx("حدّث المايكات", "Refresh microphones")) { refreshDevs() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        seekRow(tx("تأخير صوت الفيديو (تصحيح التزامن)", "Video audio delay (fix sync)"), 500, cfg.videoAudioDelayMs, { "$it ms" }) { cfg.videoAudioDelayMs = it }
        switchRow(tx("تشغيل صامت (صوت الفيديو نظيف بلا صدى)", "Silent playback (record the video's sound cleanly, no speaker sound → no echo)"), cfg.silentPlayback) { cfg.silentPlayback = it }
        switchRow(tx("نبّهني عند عدم وجود سماعات", "Warn me when no headphones are connected"), cfg.warnEcho) { cfg.warnEcho = it }
        sec("quality", "🎞 " + tx("الجودة والتسجيل", "Quality and recording"), tx("معدل البت، الترميز، العد التنازلي", "Bitrate, codec, countdown"))
        val brs = listOf(0f, 4f, 6f, 8f, 10f, 12f)
        spinnerRow(tx("معدل البت", "Bitrate"), brs.map { if (it == 0f) tx("تلقائي (موصى به)", "Auto (recommended)") else "${it.toInt()} Mbps" }, brs.indexOf(cfg.bitrateMbps).coerceAtLeast(0)) { cfg.bitrateMbps = brs[it] }
        val hevcOk = CodecSupport.hevcSupported()
        spinnerRow(tx("الترميز", "Codec"), listOf(tx("H.264 (موصى به)", "H.264 (recommended)"), if (hevcOk) "H.265 / HEVC" else tx("H.265 (غير مدعوم)", "H.265 (not supported)")), if (cfg.hevc && hevcOk) 1 else 0) { cfg.hevc = (it == 1) && hevcOk }
        val cds = listOf(0, 3, 5, 10)
        spinnerRow(tx("العد التنازلي", "Countdown"), cds.map { if (it == 0) tx("بدون", "Off") else "$it " + tx("ث", "sec") }, cds.indexOf(cfg.countdown).coerceAtLeast(0)) { cfg.countdown = cds[it] }
        switchRow(tx("وضع الملف: أوقف التسجيل عند انتهاء آخر عنصر", "Video file mode: stop recording when the last item ends"), cfg.autoStop) { cfg.autoStop = it }


        sec("camera", "🎥 " + tx("الكاميرا", "Camera"), tx("الجودة، المرآة، التقريب، الإطار", "Quality, mirror, zoom, border"))
        val camRes = listOf(640, 854, 1280, 1920)
        val camNames = listOf(tx("360p (الأخف)", "360p (lightest)"), "480p", "720p", tx("1080p (ثقيل)", "1080p (heavy)"))
        spinnerRow(tx("جودة الكاميرا الأمامية (السيلفي)", "Front camera (selfie) quality"), camNames, camRes.indexOf(cfg.camLongFront).coerceAtLeast(2)) { cfg.camLongFront = camRes[it]; ConfigStore.save(this, cfg) }
        spinnerRow(tx("جودة الكاميرا الخلفية", "Back camera quality"), camNames, camRes.indexOf(cfg.camLongBack).coerceAtLeast(1)) { cfg.camLongBack = camRes[it]; ConfigStore.save(this, cfg) }
        col.addView(TextView(this).apply {
            text = tx("الجودة تُطلب من الكاميرا نفسها، فالأقل يخفف الحمل فعلًا. مع الكاميرتين معًا يحدّ الجوال كل واحدة بحوالي 720p.", "Quality is requested from the camera itself, so a lower value really lowers the load. When both cameras run together the phone usually limits each to about 720p.")
            setTextColor(Color.parseColor("#9A9CA3")); textSize = 11f
        })
        switchRow(tx("مرآة الكاميرا الأمامية", "Mirror front camera"), cfg.mirrorFront) { cfg.mirrorFront = it; ConfigStore.save(this, cfg); editor.previewView.scaleX = if (cfg.frontCamera && !cfg.mirrorFront) -1f else 1f }
        seekRow(tx("التقريب", "Zoom"), 30, ((cfg.zoom - 1f) * 10).toInt(), { String.format(Locale.US, "%.1fx", 1f + it / 10f) }) { cfg.zoom = 1f + it / 10f; applyCameraControls() }
        seekRow(tx("الإضاءة", "Exposure"), 8, cfg.exposureIndex + 4, { "${it - 4}" }) { cfg.exposureIndex = it - 4; applyCameraControls() }
        switchRow(tx("الفلاش (الكاميرا الخلفية)", "Flash / torch (back camera)"), cfg.torch) { cfg.torch = it; applyCameraControls() }


        sec("key", "🧍 " + tx("إزالة الخلفية", "Background removal"), tx("كروما أو تلقائي", "Chroma or auto"))
        col.addView(label(tx("إزالة خلفيتي (مربع السيلفي) — يظهر الفيديو خلفي.", "Remove my background (selfie box) — the video shows behind me.")))
        val keyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (cfg.keyMode == 1) View.VISIBLE else View.GONE }
        spinnerRow(tx("إزالة الخلفية", "Background removal"), listOf(tx("إيقاف", "Off"), tx("كروما (لون تختاره)", "Chroma key (pick the color to remove)"), tx("تلقائي (عزل الشخص)", "Auto (detect the person)")), cfg.keyMode.coerceIn(0, 2)) {
            cfg.keyMode = it
            keyBox.visibility = if (it == 1) View.VISIBLE else View.GONE
            ConfigStore.save(this, cfg)
        }
        val swatch = View(this).apply { setBackgroundColor(cfg.keyColor) }
        keyBox.addView(label(tx("اللون المراد إزالته", "Color to remove")))
        keyBox.addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        val bars = arrayOfNulls<SeekBar>(3)
        fun applyKeyColor() {
            cfg.keyColor = Color.rgb(bars[0]!!.progress, bars[1]!!.progress, bars[2]!!.progress)
            swatch.setBackgroundColor(cfg.keyColor)
        }
        fun setKeyColor(c: Int) {
            bars[0]!!.progress = Color.red(c); bars[1]!!.progress = Color.green(c); bars[2]!!.progress = Color.blue(c)
            applyKeyColor()
        }
        val presetsRow = LinearLayout(this)
        for ((nm, c) in listOf(tx("أخضر", "Green") to 0xFF00B140.toInt(), tx("أزرق", "Blue") to 0xFF0047BB.toInt(), tx("أبيض", "White") to 0xFFFFFFFF.toInt(), tx("أسود", "Black") to 0xFF000000.toInt())) {
            presetsRow.addView(chip(nm) { setKeyColor(c) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6))
        }
        val presetsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presetsRow) }
        keyBox.addView(presetsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        for ((i, nm) in listOf(tx("أحمر", "Red"), tx("أخضر", "Green"), tx("أزرق", "Blue")).withIndex()) {
            keyBox.addView(label(nm))
            val sb = SeekBar(this).apply {
                max = 255; progress = (cfg.keyColor shr (16 - 8 * i)) and 0xFF
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { if (u) applyKeyColor() }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            bars[i] = sb
            keyBox.addView(sb)
        }
        val keySeek = { title: String, maxV: Int, cur: Int, onChange: (Int) -> Unit ->
            val tv = label("$title: $cur")
            keyBox.addView(tv)
            keyBox.addView(SeekBar(this).apply {
                max = maxV; progress = cur
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { tv.text = "$title: $p"; if (u) onChange(p) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
        }
        keySeek(tx("مدى مطابقة اللون (ارفعه إن لم تُزل الخلفية كلها)", "Color match range (raise if the background is not fully removed)"), 60, (cfg.keyTol * 100).toInt()) { cfg.keyTol = it / 100f }
        keySeek(tx("نعومة الحواف", "Edge softness"), 40, (cfg.keySoft * 100).toInt()) { cfg.keySoft = (it.coerceAtLeast(1)) / 100f }
        col.addView(keyBox)


        sec("standby", "⏸ " + tx("شاشة الانتظار", "Standby screen"), standbyDesc())
        col.addView(label(tx("شاشة الانتظار — تظهر (وتُسجَّل) عند الضغط على ⏸ انتظار", "Standby screen — shown (and recorded) when you tap ⏸ Standby during recording")))
        val sbLabel = TextView(this).apply { text = standbyDesc(); setTextColor(Color.parseColor("#9A9CA3")); textSize = 12f }
        standbyLabelView = sbLabel
        col.addView(sbLabel)
        val sbRow = LinearLayout(this)
        sbRow.addView(chip(tx("اختر صورة / فيديو", "Choose image / video")) { standbyPickLauncher.launch(arrayOf("image/*", "video/*")) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8))
        sbRow.addView(chip(tx("استعمل نصًا", "Use text")) {
            cfg.standbyUri = ""; cfg.standbyName = ""; cfg.standbyIsVideo = false
            ConfigStore.save(this, cfg); sbLabel.text = standbyDesc()
        })
        col.addView(sbRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val sbText = android.widget.EditText(this).apply {
            setText(cfg.standbyText); hint = tx("نص الانتظار", "Standby text"); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            setSingleLine(false); maxLines = 4
            doAfterTextChanged { cfg.standbyText = it?.toString() ?: ""; if (cfg.standbyUri.isEmpty()) sbLabel.text = standbyDesc() }
        }
        col.addView(sbText)
        switchRow(tx("اكتم المايك أثناء الانتظار", "Mute the microphone while standby is on"), cfg.standbyMuteMic) { cfg.standbyMuteMic = it }
        switchRow(tx("اعرض الانتظار تلقائيًا أثناء اختيار ملف", "Show standby automatically while I pick a file during recording"), cfg.autoStandbyOnImport) { cfg.autoStandbyOnImport = it }

        sec("screen", "📱 " + tx("وضع الشاشة", "Screen mode"), tx("قص وتكبير الشاشة الملتقطة", "Crop and zoom the captured screen"))
        col.addView(label(tx("وضع الشاشة — قص/تكبير الشاشة الملتقطة", "Screen mode — crop / zoom the captured screen")))
        seekRow(tx("تكبير الشاشة", "Screen zoom"), 300, ((cfg.scrZoom - 1f) * 100).toInt(), { "${100 + it}%" }) { cfg.scrZoom = 1f + it / 100f }
        seekRow(tx("تحريك يمين / يسار", "Screen move left / right"), 200, (cfg.scrPanX * 100).toInt() + 100, { "${it - 100}" }) { cfg.scrPanX = (it - 100) / 100f }
        seekRow(tx("تحريك أعلى / أسفل", "Screen move up / down"), 200, (cfg.scrPanY * 100).toInt() + 100, { "${it - 100}" }) { cfg.scrPanY = (it - 100) / 100f }
        sec("shape", "🔲 " + tx("شكل مربع الكاميرا", "Camera box look"), tx("تصحيح الدوران، الإطار، الزوايا", "Rotation fix, border, corners"))
        val rots = listOf(0, 90, 180, 270)
        spinnerRow(tx("تصحيح دوران الكاميرا", "Camera rotation fix"), listOf(tx("0° (تلقائي)", "0° (auto)"), "90°", "180°", "270°"), rots.indexOf(cfg.camRotAdjust).coerceAtLeast(0)) { cfg.camRotAdjust = rots[it] }
        seekRow(tx("إطار الكاميرا", "Camera border"), 30, (cfg.layout.borderFrac * 1000).toInt(), { "${it / 10f}" }) { cfg.layout.borderFrac = it / 1000f; editor.refresh() }
        seekRow(tx("استدارة الزوايا", "Corner radius"), 50, (cfg.layout.radiusFrac * 100).toInt(), { "$it%" }) { cfg.layout.radiusFrac = it / 100f; editor.refresh() }

        col = root
        col.addView(TextView(this).apply {
            text = tx("ملاحظات: 60 إطارًا و1080p قد تسخّن الجوال — 1080p/30 هو الأثبت. صوت التطبيقات الأخرى يُلتقط فقط إن سمحت بذلك (قاعدة أندرويد).", "Notes: 60 fps and 1080p may overheat — 1080p/30 is the stable default. Other apps' audio can only be captured if they allow it (Android rule).")
            setTextColor(Color.parseColor("#9A9CA3")); textSize = 11f; setPadding(0, dp(14), 0, 0)
        })

        val sv = ScrollView(this).apply { addView(root) }
        AlertDialog.Builder(this).setTitle(tx("إعدادات الرياكشن", "Reaction settings")).setView(sv)
            .setPositiveButton(tx("تم", "Done")) { _, _ -> ConfigStore.save(this, cfg); render() }
            .setOnDismissListener {
                standbyLabelView = null
                ConfigStore.save(this, cfg); render()
                RecorderState.scene?.applyLayout(cfg.layout)
                RecorderState.scene?.applyKey(cfg)
            }
            .show()
    }
}
