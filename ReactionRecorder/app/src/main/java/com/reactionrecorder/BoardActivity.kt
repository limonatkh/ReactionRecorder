package com.reactionrecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * وضع الشرح: لوح رسم، قارئ PDF، صفحة كتابة، وفيديو بترسم فوقه — داخل التطبيق نفسه.
 * التسجيل بيرسم "المسرح" مباشرة كمصدر فيديو (مش تصوير شاشة): ما بيطلع شريط الإشعارات ولا أي إشي من الجوال.
 * الصوت من المايك، وكاميرتك بمربع (شكله وحجمه ومكانه بتتغير حتى وقت التسجيل).
 */
class BoardActivity : BaseActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var stageBox: FrameLayout
    private lateinit var stageWrap: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var board: BoardCanvas
    private lateinit var textEt: EditText
    private lateinit var videoTv: TextureView
    private lateinit var camOv: CamOverlay
    private lateinit var panel: LinearLayout
    private lateinit var toolsBox: LinearLayout
    private lateinit var timeTv: TextView
    private lateinit var recBtn: Button
    private lateinit var holdStop: HoldButton
    private lateinit var statusRow: LinearLayout
    private lateinit var countTv: TextView
    private lateinit var monitor: SurfaceView
    private var pauseBtn: Button? = null
    private var camBtn: Button? = null
    private var micBtn: Button? = null
    private var portrait = false
    private var mode = "draw"
    private var pushThread: HandlerThread? = null
    private var pushH: Handler? = null
    private var lastPhase = RecorderState.Phase.IDLE
    private var player: MediaPlayer? = null
    private var videoW = 0
    private var videoH = 0
    private var videoReady = false

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.all { it } || hasCore()) startRec() }
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) openPdf(u) }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) openVideo(u) }

    private fun dp(v: Int) = UiKit.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasCore() = granted(Manifest.permission.RECORD_AUDIO) && granted(Manifest.permission.CAMERA)
    private val live get() = RecorderState.isBusy && RecorderState.phase != RecorderState.Phase.STARTING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // الكيبورد ما بيصغّر المسرح (كان بيصير شريط رفيع والكلام بينكسر حرف حرف)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        portrait = getSharedPreferences("reaction_ui", MODE_PRIVATE).getBoolean("boardPortrait", false)
        requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        board = BoardCanvas(this)
        board.onTextTap = { nx, ny -> askText(nx, ny) }
        textEt = EditText(this).apply {
            background = null; gravity = Gravity.TOP or Gravity.START; textSize = 30f; setTextColor(Color.parseColor("#1A1A1A"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            isSingleLine = false; hint = tx("اكتب هون…", "Type here…"); setHintTextColor(Color.parseColor("#888888"))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(dp(18), dp(14), dp(18), dp(14)); visibility = View.GONE
        }
        videoTv = TextureView(this).apply { visibility = View.GONE }
        videoTv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { player?.setSurface(Surface(st)) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { player?.setSurface(null); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        camOv = CamOverlay(this)
        buildLayout()
        RecorderState.addListener(stateL)
    }

    override fun onDestroy() {
        super.onDestroy()
        RecorderState.removeListener(stateL)
        stopPush()
        board.closePdf()
        try { player?.release() } catch (_: Throwable) {}
        player = null
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (RecorderState.isBusy) { toast(tx("أنهِ التسجيل أولًا (اضغط مطوّلًا على «إنهاء»)", "End the recording first (press and hold “End”)")); return }
        super.onBackPressed()
    }

    // ───────── الترتيب ─────────

    private fun buildLayout() {
        for (v in listOf(board, textEt, videoTv, camOv)) (v.parent as? ViewGroup)?.removeView(v)
        val root = LinearLayout(this).apply { orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#0D0E10")) }
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        // المسرح (بنسبة الفيديو)
        stageBox = object : FrameLayout(this) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                val W = r - l; val H = b - t
                val a = if (portrait) 9f / 16f else 16f / 9f
                var w = W; var h = (W / a).toInt()
                if (h > H) { h = H; w = (H * a).toInt() }
                val lp = stageWrap.layoutParams as LayoutParams
                if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER; post { stageWrap.layoutParams = lp; layoutVideo() } }
            }
        }
        stageWrap = FrameLayout(this)
        stage = FrameLayout(this).apply { clipChildren = true; setBackgroundColor(Color.BLACK) }
        stage.addView(videoTv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        stage.addView(board, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(textEt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stageWrap.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // مكان كاميرتك (برا المسرح: ما بينسجّل — الكاميرا الحقيقية بتنضاف بالتسجيل)
        stageWrap.addView(camOv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stageBox.addView(stageWrap, FrameLayout.LayoutParams(dp(320), dp(180), Gravity.CENTER))
        countTv = UiKit.text(this, "", 90f, "#FFFFFF").apply { typeface = Fonts.bold; gravity = Gravity.CENTER; visibility = View.GONE; setShadowLayer(12f, 0f, 0f, Color.BLACK) }
        stageBox.addView(countTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stageBox, if (portrait) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // اللوحة: التسجيل فوق، والأدوات تحت (بس أدوات الوضع الحالي)
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(8)); setBackgroundColor(Color.parseColor("#17181C")) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(UiKit.iconButton(this, if (L.ar) Ic.BACK else Ic.CHEV, tx("رجوع", "Back"), bgColor = null) { onBackPressedDispatcher.onBackPressed() })
        timeTv = UiKit.text(this, tx("شرح", "Lesson"), 18f, UiKit.TEXT).apply { typeface = Fonts.bold }
        head.addView(timeTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        monitor = SurfaceView(this).apply { visibility = View.GONE; contentDescription = tx("معاينة الفيديو المسجّل", "Recorded video preview") }
        monitor.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { PreviewBridge.attach(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { PreviewBridge.attach(h.surface) }
            override fun surfaceDestroyed(h: SurfaceHolder) { PreviewBridge.detach() }
        })
        head.addView(monitor, LinearLayout.LayoutParams(if (portrait) dp(36) else dp(80), if (portrait) dp(64) else dp(45)).apply { marginEnd = dp(6) })
        head.addView(UiKit.iconButton(this, Ic.GEAR, tx("الإعدادات والكاميرا", "Settings and camera"), bgColor = UiKit.CARD2) { settingsSheet() })
        panel.addView(head)
        recBtn = UiKit.primary(this, "●  " + tx("سجّل", "Record"), UiKit.RED) { onRecord() }
        panel.addView(recBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
        holdStop = HoldButton(this, tx("إنهاء (اضغط مطوّلًا)", "End (press and hold)")) { stopRec() }
        holdStop.onShortTap = { toast(tx("اضغط مطوّلًا لحد ما يتعبّى الزر", "Press and hold until the button fills")) }
        holdStop.visibility = View.GONE
        panel.addView(holdStop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
        // حالة الكاميرا والمايك (واضحة: شغّال/مطفي)
        statusRow = LinearLayout(this)
        pauseBtn = UiKit.chipIcon(this, Ic.PAUSE, tx("إيقاف مؤقت", "Pause")) { togglePause() }
        camBtn = UiKit.chip(this, "") { toggleCam() }
        micBtn = UiKit.chip(this, "") { val m = !RecorderState.micMuted; RecorderState.scene?.setMicMuted(m); renderStatus() }
        statusRow.addView(pauseBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(4) })
        statusRow.addView(camBtn, LinearLayout.LayoutParams(0, dp(46), 1.15f).apply { marginEnd = dp(4) })
        statusRow.addView(micBtn, LinearLayout.LayoutParams(0, dp(46), 1.15f))
        panel.addView(statusRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        toolsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(toolsBox); isVerticalScrollBarEnabled = false }
        panel.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        root.addView(panel, if (portrait) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.40f).toInt()) else LinearLayout.LayoutParams(min(dp(290), (resources.displayMetrics.widthPixels * 0.36f).toInt()), ViewGroup.LayoutParams.MATCH_PARENT))
        buildTools()
        renderStatus()
    }

    // ───────── الأدوات ─────────

    private fun row(): LinearLayout = LinearLayout(this).also { toolsBox.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

    /** زر أداة: أيقونة، والاسم بيطلع بضغطة مطوّلة */
    private fun tool(path: String, desc: String, sel: Boolean = false, tint: String = UiKit.TEXT, run: (View) -> Unit): ImageButton = ImageButton(this).apply {
        setImageDrawable(UiKit.icon(this@BoardActivity, path, if (sel) "#FFFFFF" else tint, 22))
        contentDescription = desc
        TooltipCompat.setTooltipText(this, desc)
        background = UiKit.ripple(UiKit.bg(if (sel) UiKit.BLUE_BG else UiKit.CARD2, 12, this@BoardActivity, if (sel) UiKit.BLUE else UiKit.LINE))
        setOnClickListener { run(it) }
    }

    private fun addRow(vararg vs: View?) {
        val r = row()
        for (v in vs) {
            if (v == null) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)); continue }
            r.addView(v, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
    }

    private fun label(s: String): TextView = UiKit.text(this, s, 14f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER; minHeight = dp(46) }

    private fun buildTools() {
        toolsBox.removeAllViews()
        // الوضع
        addRow(
            tool(Ic.BOARD, tx("لوح", "Board"), mode == "draw") { setMode("draw") },
            tool(Ic.FILE, "PDF", mode == "pdf") { setMode("pdf") },
            tool(Ic.TEXT, tx("صفحة كتابة", "Text page"), mode == "text") { setMode("text") },
            tool(Ic.PLAY, tx("فيديو", "Video"), mode == "video") { setMode("video") },
        )
        toolsBox.addView(UiKit.text(this, when (mode) {
            "pdf" -> tx("PDF — اكتب وعلّم عليه، إصبعين للتكبير", "PDF — mark it up, two fingers to zoom")
            "text" -> tx("صفحة كتابة", "Text page")
            "video" -> tx("فيديو — وقّفه وارسم عليه", "Video — pause it and draw on it")
            else -> tx("لوح", "Board")
        }, 12.5f, UiKit.SUB).apply { setPadding(dp(4), dp(6), dp(4), dp(2)) })
        if (mode == "text") buildTextTools() else buildDrawTools()
    }

    private fun shapeIcon(t: Int) = when (t) { BoardCanvas.LINE -> Ic.LINE; BoardCanvas.ARROW -> Ic.ARROW; BoardCanvas.RECT -> Ic.RECT; BoardCanvas.OVAL -> Ic.OVAL; else -> Ic.SHAPES }

    private fun buildDrawTools() {
        val t = board.tool
        val isShape = t in listOf(BoardCanvas.LINE, BoardCanvas.ARROW, BoardCanvas.RECT, BoardCanvas.OVAL)
        addRow(
            tool(Ic.PEN, tx("قلم", "Pen"), t == BoardCanvas.PEN) { board.tool = BoardCanvas.PEN; buildTools() },
            tool(Ic.HL, tx("تمييز", "Highlighter"), t == BoardCanvas.HL) { board.tool = BoardCanvas.HL; buildTools() },
            tool(Ic.ERASER, tx("ممحاة", "Eraser"), t == BoardCanvas.ERASE) { board.tool = BoardCanvas.ERASE; buildTools() },
            tool(Ic.LASER, tx("ليزر", "Laser"), t == BoardCanvas.LASER) { board.tool = BoardCanvas.LASER; buildTools() },
            tool(shapeIcon(t), tx("أشكال", "Shapes"), isShape) { v -> shapesPopup(v) },
        )
        addRow(
            tool(Ic.TEXT, tx("كتابة على اللوح (اضغط مكان)", "Text on the board (tap a spot)"), t == BoardCanvas.TEXT) { board.tool = BoardCanvas.TEXT; buildTools(); toast(tx("اضغط على المكان اللي بدك تكتب فيه", "Tap where you want to type")) },
            colorButton(board.color) { v -> colorPopup(v, listOf(0xFF111111.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF453A.toInt(), 0xFF3D8BFF.toInt(), 0xFF32D74B.toInt(), 0xFFFFD60A.toInt(), 0xFFFF9F0A.toInt(), 0xFFB45CE0.toInt())) { c -> board.color = c; buildTools() } },
            widthButton(),
            tool(Ic.UNDO, tx("تراجع", "Undo")) { board.undo() },
            tool(Ic.REDO, tx("إعادة", "Redo")) { board.redo() },
        )
        when (mode) {
            "pdf" -> addRow(
                tool(Ic.FILE, tx("افتح PDF", "Open PDF")) { pickPdf.launch(arrayOf("application/pdf")) },
                tool(if (L.ar) Ic.BACK else Ic.CHEV, tx("الصفحة السابقة", "Previous page")) { board.prevPage(); buildTools() },
                label(board.pageLabel()),
                tool(if (L.ar) Ic.CHEV else Ic.BACK, tx("الصفحة التالية", "Next page")) { board.nextPage(); buildTools() },
                tool(Ic.FRAME, tx("ملائمة", "Fit")) { board.resetView() },
            )
            "video" -> addRow(
                tool(Ic.FOLDER, tx("اختار فيديو", "Pick a video")) { pickVideo.launch(arrayOf("video/*")) },
                tool(Ic.RWD, tx("رجوع ٥ ثواني", "Back 5 s")) { seekBy(-5000) },
                tool(if (player?.isPlaying == true) Ic.PAUSE else Ic.PLAY, if (player?.isPlaying == true) tx("وقّف", "Pause") else tx("شغّل", "Play"), player?.isPlaying == true) { playPause() },
                tool(Ic.FWD, tx("قدّام ٥ ثواني", "Forward 5 s")) { seekBy(5000) },
                tool(Ic.TRASH, tx("امسح الرسم", "Clear drawing")) { board.clearPage() },
            )
            else -> addRow(
                tool(if (L.ar) Ic.BACK else Ic.CHEV, tx("الصفحة السابقة", "Previous page")) { board.prevPage(); buildTools() },
                label(board.pageLabel()),
                tool(if (L.ar) Ic.CHEV else Ic.BACK, tx("الصفحة التالية", "Next page")) { board.nextPage(); buildTools() },
                tool(Ic.PLUS, tx("صفحة جديدة", "New page")) { board.addPage(); buildTools() },
                tool(Ic.TRASH, tx("امسح الصفحة", "Clear page")) { board.clearPage() },
            )
        }
        if (mode == "pdf" && board.pdfPages == 0) toolsBox.addView(UiKit.text(this, tx("اضغط أيقونة الملف وافتح PDF.", "Tap the file icon and open a PDF."), 13f, UiKit.SUB).apply { setPadding(dp(4), dp(6), dp(4), 0) })
        if (mode == "video" && player == null) toolsBox.addView(UiKit.text(this, tx("اضغط أيقونة المجلد واختار فيديو. صوت الفيديو بيطلع من السماعة وبيلقطه المايك.", "Tap the folder icon and pick a video. Its sound plays from the speaker and the mic picks it up."), 13f, UiKit.SUB).apply { setPadding(dp(4), dp(6), dp(4), 0) })
    }

    private fun colorButton(c: Int, run: (View) -> Unit): View = ImageButton(this).apply {
        setImageDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(2), Color.parseColor("#9A9CA3")); setSize(dp(22), dp(22)) })
        contentDescription = tx("اللون", "Colour"); TooltipCompat.setTooltipText(this, contentDescription)
        background = UiKit.ripple(UiKit.bg(UiKit.CARD2, 12, this@BoardActivity, UiKit.LINE))
        setOnClickListener { run(it) }
    }

    private fun widthButton(): View = ImageButton(this).apply {
        val r = (board.strokeW * 1100).coerceIn(4f, 16f)
        setImageDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(UiKit.TEXT)); setSize(dp(r.toInt()), dp(r.toInt())) })
        contentDescription = tx("السماكة", "Thickness"); TooltipCompat.setTooltipText(this, contentDescription)
        background = UiKit.ripple(UiKit.bg(UiKit.CARD2, 12, this@BoardActivity, UiKit.LINE))
        setOnClickListener { v ->
            popup(v) { p, w ->
                for ((x, n) in listOf(0.003f to tx("رفيع", "Thin"), 0.006f to tx("وسط", "Medium"), 0.012f to tx("عريض", "Thick"), 0.02f to tx("عريض كثير", "Extra"))) {
                    p.addView(UiKit.chip(this@BoardActivity, n, abs(board.strokeW - x) < 0.0005f) { board.strokeW = x; w.dismiss(); buildTools() }, LinearLayout.LayoutParams(dp(92), dp(44)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
            }
        }
    }

    /** نافذة صغيرة فوق الزر (بدل صفوف أزرار طويلة) */
    private fun popup(anchor: View, build: (LinearLayout, PopupWindow) -> Unit) {
        val p = LinearLayout(this).apply { setPadding(dp(8), dp(8), dp(8), dp(8)); background = UiKit.bg(UiKit.CARD, 16, this@BoardActivity, UiKit.LINE); elevation = dp(8).toFloat() }
        val w = PopupWindow(p, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        w.elevation = dp(10).toFloat()
        build(p, w)
        p.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        w.showAsDropDown(anchor, 0, -anchor.height - p.measuredHeight - dp(6))
    }

    private fun colorPopup(anchor: View, colors: List<Int>, set: (Int) -> Unit) = popup(anchor) { p, w ->
        p.orientation = LinearLayout.VERTICAL
        for (chunk in colors.chunked(4)) {
            val r = LinearLayout(this)
            for (c in chunk) r.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(2), Color.parseColor("#9A9CA3")) }
                contentDescription = String.format("#%06X", c and 0xFFFFFF)
                setOnClickListener { set(c); w.dismiss() }
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            p.addView(r)
        }
    }

    private fun shapesPopup(anchor: View) = popup(anchor) { p, w ->
        for ((k, path, n) in listOf(Triple(BoardCanvas.LINE, Ic.LINE, tx("خط", "Line")), Triple(BoardCanvas.ARROW, Ic.ARROW, tx("سهم", "Arrow")), Triple(BoardCanvas.RECT, Ic.RECT, tx("مربع", "Box")), Triple(BoardCanvas.OVAL, Ic.OVAL, tx("دائرة", "Circle"))))
            p.addView(tool(path, n, board.tool == k) { board.tool = k; w.dismiss(); buildTools() }, LinearLayout.LayoutParams(dp(50), dp(46)).apply { setMargins(dp(3), 0, dp(3), 0) })
    }

    private fun askText(nx: Float, ny: Float) {
        val et = EditText(this).apply { hint = tx("اكتب…", "Type…"); setTextColor(Color.parseColor(UiKit.TEXT)); textDirection = View.TEXT_DIRECTION_FIRST_STRONG; imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI }
        val d = AlertDialog.Builder(this).setTitle(tx("كتابة على اللوح", "Text on the board")).setView(et)
            .setPositiveButton(tx("ضيف", "Add")) { _, _ -> val s = et.text.toString().trim(); if (s.isNotEmpty()) board.addText(nx, ny, s) }
            .setNegativeButton(tx("إلغاء", "Cancel"), null).create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        d.show()
        et.requestFocus()
    }

    // صفحات الكتابة
    private val textPages = arrayListOf("")
    private var textPage = 0
    private var textSize = 30f
    private var textColor = 0xFF1A1A1A.toInt()
    private var textCentre = false

    private fun buildTextTools() {
        addRow(
            tool(Ic.TEXT, tx("حجم الخط", "Text size")) { v ->
                popup(v) { p, w ->
                    for ((x, n) in listOf(22f to tx("صغير", "Small"), 30f to tx("وسط", "Medium"), 44f to tx("كبير", "Large"), 60f to tx("ضخم", "Huge")))
                        p.addView(UiKit.chip(this, n, textSize == x) { textSize = x; textEt.textSize = x; w.dismiss() }, LinearLayout.LayoutParams(dp(80), dp(44)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
            },
            colorButton(textColor) { v -> colorPopup(v, listOf(0xFF1A1A1A.toInt(), 0xFFFF453A.toInt(), 0xFF3D8BFF.toInt(), 0xFF1E8E3E.toInt())) { c -> textColor = c; textEt.setTextColor(c); buildTools() } },
            tool(Ic.LIST, if (textCentre) tx("بالنص", "Centred") else tx("من البداية", "From start"), textCentre) { textCentre = !textCentre; textEt.gravity = if (textCentre) Gravity.CENTER else Gravity.TOP or Gravity.START; buildTools() },
            tool(Ic.UNDO, tx("خبّي الكيبورد", "Hide keyboard")) { hideKeyboard() },
        )
        addRow(
            tool(if (L.ar) Ic.BACK else Ic.CHEV, tx("الصفحة السابقة", "Previous page")) { goText(textPage - 1) },
            label("${textPage + 1}/${textPages.size}"),
            tool(if (L.ar) Ic.CHEV else Ic.BACK, tx("الصفحة التالية", "Next page")) { goText(textPage + 1) },
            tool(Ic.PLUS, tx("صفحة جديدة", "New page")) { textPages[textPage] = textEt.text.toString(); textPages.add(textPage + 1, ""); goText(textPage + 1) },
        )
    }

    private fun hideKeyboard() {
        textEt.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(textEt.windowToken, 0)
    }

    private fun goText(i: Int) {
        if (i !in textPages.indices) return
        textPages[textPage] = textEt.text.toString()
        textPage = i
        textEt.setText(textPages[i])
        buildTools()
    }

    private fun setMode(k: String) {
        if (mode == "text") textPages[textPage] = textEt.text.toString()
        if (mode == "video" && k != "video") player?.let { if (it.isPlaying) it.pause() }
        mode = k
        board.setMode(k)
        textEt.visibility = if (k == "text") View.VISIBLE else View.GONE
        videoTv.visibility = if (k == "video" && player != null) View.VISIBLE else View.GONE
        if (k == "text") { textEt.setText(textPages[textPage]); textEt.requestFocus() } else hideKeyboard()
        if (k == "pdf" && board.pdfPages == 0) pickPdf.launch(arrayOf("application/pdf"))
        if (k == "video" && player == null) pickVideo.launch(arrayOf("video/*"))
        buildTools()
    }

    private fun setPortrait(p: Boolean) {
        if (portrait == p || RecorderState.isBusy) return
        portrait = p
        getSharedPreferences("reaction_ui", MODE_PRIVATE).edit().putBoolean("boardPortrait", p).apply()
        requestedOrientation = if (p) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        buildLayout()
        setMode(mode)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        stageBox.requestLayout()
    }

    private fun openPdf(u: Uri) {
        try {
            board.openPdf(contentResolver.openFileDescriptor(u, "r") ?: throw IllegalStateException("cannot open"))
            mode = "pdf"; board.setMode("pdf"); textEt.visibility = View.GONE; videoTv.visibility = View.GONE
            buildTools()
        } catch (t: Throwable) { toast(tx("تعذر فتح الملف: ", "Could not open the file: ") + (t.message ?: "")) }
    }

    // ───────── الفيديو ─────────

    private fun openVideo(u: Uri) {
        try { player?.release() } catch (_: Throwable) {}
        player = null; videoReady = false
        try {
            val mp = MediaPlayer()
            mp.setDataSource(this, u)
            mp.setOnPreparedListener { videoReady = true; videoW = it.videoWidth; videoH = it.videoHeight; layoutVideo(); it.seekTo(1); buildTools() }
            mp.setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; layoutVideo() }
            mp.setOnCompletionListener { buildTools() }
            mp.setOnErrorListener { _, _, _ -> toast(tx("تعذر تشغيل الفيديو", "Could not play the video")); true }
            if (videoTv.isAvailable) videoTv.surfaceTexture?.let { mp.setSurface(Surface(it)) }
            mp.prepareAsync()
            player = mp
            mode = "video"; board.setMode("video"); textEt.visibility = View.GONE; videoTv.visibility = View.VISIBLE
            board.clearMode("video")
            buildTools()
        } catch (t: Throwable) { toast(tx("تعذر فتح الفيديو: ", "Could not open the video: ") + (t.message ?: "")) }
    }

    /** الفيديو كامل داخل المسرح (بدون قص) */
    private fun layoutVideo() {
        val W = stageWrap.layoutParams?.width ?: return
        val H = stageWrap.layoutParams?.height ?: return
        if (videoW <= 0 || videoH <= 0 || W <= 0 || H <= 0) return
        val a = videoW.toFloat() / videoH
        var w = W; var h = (W / a).toInt()
        if (h > H) { h = H; w = (H * a).toInt() }
        videoTv.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    private fun playPause() {
        val p = player ?: run { pickVideo.launch(arrayOf("video/*")); return }
        if (!videoReady) return
        if (p.isPlaying) p.pause() else p.start()
        buildTools()
    }

    private fun seekBy(ms: Int) {
        val p = player ?: return
        if (!videoReady) return
        p.seekTo((p.currentPosition + ms).coerceIn(0, max(0, p.duration - 100)))
    }

    // ───────── الكاميرا والمايك ─────────

    private fun toggleCam() {
        val v = !BoardCam.visible(this)
        BoardCam.set(this) { putBoolean("vis", v) }
        applyCam()
    }

    /** يطبّق إعدادات كاميرتك (وقت التسجيل كمان) */
    private fun applyCam() {
        val sc = RecorderState.scene
        if (sc != null && RecorderState.isBusy) {
            sc.applyLayout(BoardCam.layout(this, portrait))
            sc.setCameraVisible(BoardCam.visible(this))
            if (sc.camFront != BoardCam.front(this)) sc.flipCamera()
        }
        camOv.invalidate()
        renderStatus()
    }

    private fun renderStatus() {
        val vis = BoardCam.visible(this)
        camBtn?.let { b ->
            b.text = if (vis) tx("الكاميرا ظاهرة", "Camera on") else tx("الكاميرا مخفية", "Camera hidden")
            b.setTextColor(Color.parseColor(if (vis) UiKit.TEXT else UiKit.ORANGE))
            UiKit.withIcon(b, if (vis) Ic.CAM else Ic.CAM_OFF, if (vis) UiKit.GREEN else UiKit.ORANGE, 18)
        }
        val muted = RecorderState.micMuted
        micBtn?.let { b ->
            b.text = if (!live) tx("المايك", "Mic") else if (muted) tx("المايك مكتوم", "Mic muted") else tx("المايك شغّال", "Mic on")
            b.setTextColor(Color.parseColor(if (muted && live) UiKit.RED else UiKit.TEXT))
            UiKit.withIcon(b, if (muted && live) Ic.MIC_OFF else Ic.MIC, if (muted && live) UiKit.RED else if (live) UiKit.GREEN else UiKit.SUB, 18)
            b.isEnabled = live; b.alpha = if (live) 1f else 0.5f
        }
        val paused = RecorderState.phase == RecorderState.Phase.PAUSED
        pauseBtn?.let { b ->
            b.text = if (paused) tx("كمّل", "Resume") else tx("إيقاف مؤقت", "Pause")
            UiKit.withIcon(b, if (paused) Ic.PLAY else Ic.PAUSE, if (paused) UiKit.ORANGE else UiKit.TEXT, 18)
            b.visibility = if (live) View.VISIBLE else View.GONE
        }
    }

    private fun settingsSheet() {
        UiKit.sheet(this, tx("الإعدادات", "Settings")) { col, _ ->
            fun sub(s: String) = col.addView(UiKit.text(this, s, 14f, UiKit.SUB).apply { setPadding(0, dp(12), 0, dp(4)) })
            fun chips(items: List<Pair<String, String>>, cur: String, enabled: Boolean = true, set: (String) -> Unit) {
                val r = LinearLayout(this)
                var sel = cur
                fun draw() {
                    r.removeAllViews()
                    for ((k, n) in items) r.addView(UiKit.chip(this, n, k == sel) {
                        if (enabled) { sel = k; set(k); draw() }
                    }.apply { alpha = if (enabled) 1f else 0.5f }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                draw()
                col.addView(r)
            }
            sub(tx("شكل الفيديو", "Video shape") + if (RecorderState.isBusy) tx(" (بعد التسجيل)", " (after recording)") else "")
            chips(listOf("h" to tx("بالعرض 16:9", "Landscape 16:9"), "v" to tx("بالطول 9:16", "Portrait 9:16")), if (portrait) "v" else "h", !RecorderState.isBusy) { setPortrait(it == "v") }
            if (mode == "draw") {
                sub(tx("خلفية اللوح", "Board background"))
                chips(listOf("${BoardCanvas.BG_WHITE}" to tx("أبيض", "White"), "${BoardCanvas.BG_GRID}" to tx("مربعات", "Grid"), "${BoardCanvas.BG_BLACK}" to tx("أخضر غامق", "Dark green")), "${board.bg}") { board.bg = it.toInt(); board.invalidate() }
            }
            col.addView(UiKit.text(this, tx("كاميرتك", "Your camera"), 17f, UiKit.TEXT, true).apply { setPadding(0, dp(16), 0, 0) })
            col.addView(UiKit.text(this, tx("كل هدول بيتغيروا كمان وقت التسجيل. وبتقدر تسحب المربع على اللوح.", "All of these also change while recording. You can drag the box on the board too."), 13f, UiKit.SUB))
            sub(tx("ظاهرة؟", "Shown?"))
            chips(listOf("1" to tx("ظاهرة", "Shown"), "0" to tx("مخفية", "Hidden")), if (BoardCam.visible(this)) "1" else "0") { BoardCam.set(this) { putBoolean("vis", it == "1") }; applyCam() }
            sub(tx("أي كاميرا", "Which camera"))
            chips(listOf("f" to tx("الأمامية", "Front"), "b" to tx("الخلفية", "Back")), if (BoardCam.front(this)) "f" else "b") { BoardCam.set(this) { putBoolean("front", it == "f") }; applyCam() }
            sub(tx("الشكل", "Shape"))
            chips(listOf("circle" to tx("دائرة", "Circle"), "rounded" to tx("زوايا دائرية", "Rounded"), "rect" to tx("مستطيل", "Rectangle")), BoardCam.shape(this)) { BoardCam.set(this) { putString("shape", it) }; applyCam() }
            sub(tx("الحجم", "Size"))
            chips(listOf("0" to tx("صغير", "Small"), "1" to tx("وسط", "Medium"), "2" to tx("كبير", "Large")), BoardCam.size(this).toString()) { BoardCam.set(this) { putInt("size", it.toInt()) }; applyCam() }
            sub(tx("المكان", "Position"))
            chips(listOf("tl" to tx("فوق يسار", "Top left"), "tr" to tx("فوق يمين", "Top right"), "bl" to tx("تحت يسار", "Bottom left"), "br" to tx("تحت يمين", "Bottom right")), "") { k ->
                val sz = BoardCam.boxSize(this, portrait)
                val cx = if (k.endsWith("l")) sz[0] / 2f + 0.035f else 1f - sz[0] / 2f - 0.035f
                val cy = if (k.startsWith("t")) sz[1] / 2f + 0.05f else 1f - sz[1] / 2f - 0.05f
                BoardCam.set(this) { putFloat("cx", cx); putFloat("cy", cy) }; applyCam()
            }
            sub(tx("السطوع (إذا طلعت غامقة)", "Brightness (if it looks dark)"))
            chips(listOf("-1" to "−1", "0" to tx("عادي", "Normal"), "1" to "+1", "2" to "+2", "3" to "+3"), BoardCam.exposure(this).toString()) { BoardCam.set(this) { putInt("exp", it.toInt()) }; RecorderState.scene?.setExposure(it.toInt()) }
        }
    }

    // ───────── التسجيل ─────────

    private fun onRecord() {
        if (RecorderState.isBusy) return
        if (PodRec.active != null) { toast(tx("في تسجيل بودكاست شغّال", "A podcast recording is running")); return }
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
        if (!granted(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) perms.launch(need.toTypedArray()) else startRec()
    }

    private fun startRec() {
        if (!hasCore()) { toast(tx("بدها إذن المايك والكاميرا", "Needs mic and camera permission")); return }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        ContextCompat.startForegroundService(this, Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_BOARD, true).putExtra(RecorderService.EXTRA_BOARD_PORTRAIT, portrait))
        startPush()
    }

    private fun stopRec() { startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)) }

    private fun togglePause() {
        val paused = RecorderState.phase == RecorderState.Phase.PAUSED
        startService(Intent(this, RecorderService::class.java).setAction(if (paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE))
    }

    /** يرسم المسرح على سطح خدمة التسجيل ~25 مرة بالثانية (بخيط منفصل) */
    private fun startPush() {
        if (pushThread != null) return
        val th = HandlerThread("board-push").also { it.start() }
        pushThread = th
        pushH = Handler(th.looper)
        pushH?.post(push)
    }

    private fun stopPush() {
        pushH?.removeCallbacksAndMessages(null)
        pushThread?.quitSafely()
        pushThread = null; pushH = null
    }

    // مخزنين بالتناوب: الواجهة بترسم بواحد والخيط الثاني بيبعث الثاني
    private var bufs: Array<Bitmap>? = null
    @Volatile private var front = -1
    private var vbmp: Bitmap? = null

    private val push = object : Runnable {
        override fun run() {
            val h = pushH ?: return
            val s = BoardBridge.surface
            val bs = bufs
            val f = front
            if (s != null && bs != null && f >= 0 && s.isValid) {
                val b = bs[f]
                try {
                    synchronized(b) {
                        val c = if (Build.VERSION.SDK_INT >= 23) s.lockHardwareCanvas() else s.lockCanvas(null)
                        c.drawColor(Color.BLACK)
                        c.drawBitmap(b, null, android.graphics.Rect(0, 0, BoardBridge.w, BoardBridge.h), null)
                        s.unlockCanvasAndPost(c)
                    }
                } catch (_: Throwable) {}
            }
            h.postDelayed(this, 40)
        }
    }

    /** لقطة من المسرح (على خيط الواجهة) — الخيط الثاني بيبعتها للتسجيل */
    private fun capture() {
        val w = BoardBridge.w; val h = BoardBridge.h
        if (stage.width <= 0 || stage.height <= 0) return
        var bs = bufs
        if (bs == null || bs[0].width != w || bs[0].height != h) { bs = arrayOf(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)); bufs = bs; front = -1 }
        val back = if (front == 0) 1 else 0
        val b = bs[back]
        synchronized(b) {
            val c = Canvas(b)
            c.drawColor(if (mode == "video") Color.BLACK else Color.WHITE)
            c.save()
            c.scale(w.toFloat() / stage.width, h.toFloat() / stage.height)
            // الفيديو (TextureView ما بينرسم على Canvas عادي → بناخد صورته)
            if (mode == "video" && videoTv.visibility == View.VISIBLE && videoTv.isAvailable && videoTv.width > 0) {
                val vw = min(videoTv.width, 1280); val vh = (vw * videoTv.height / videoTv.width.toFloat()).toInt().coerceAtLeast(1)
                var vb = vbmp
                if (vb == null || vb.width != vw || vb.height != vh) { vb = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888); vbmp = vb }
                try { videoTv.getBitmap(vb) } catch (_: Throwable) {}
                c.drawBitmap(vb, null, RectF(videoTv.left.toFloat(), videoTv.top.toFloat(), videoTv.right.toFloat(), videoTv.bottom.toFloat()), null)
            }
            board.draw(c)
            if (textEt.visibility == View.VISIBLE) {
                c.save(); c.translate(textEt.left.toFloat(), textEt.top.toFloat())
                val cur = textEt.isCursorVisible
                textEt.isCursorVisible = false
                textEt.draw(c)
                textEt.isCursorVisible = cur
                c.restore()
            }
            c.restore()
        }
        front = back
    }

    private val stateL: () -> Unit = { ui.post { renderState() } }

    private fun renderState() {
        val ph = RecorderState.phase
        val busy = RecorderState.isBusy
        recBtn.visibility = if (busy) View.GONE else View.VISIBLE
        holdStop.visibility = if (busy && ph != RecorderState.Phase.STARTING) View.VISIBLE else View.GONE
        monitor.visibility = if (busy) View.VISIBLE else View.GONE
        countTv.visibility = if (ph == RecorderState.Phase.COUNTDOWN) View.VISIBLE else View.GONE
        countTv.text = RecorderState.countdownLeft.toString()
        if (lastPhase != ph) {
            if (ph == RecorderState.Phase.IDLE && lastPhase != RecorderState.Phase.IDLE) onFinished()
            lastPhase = ph
            renderStatus()
        }
    }

    private fun onFinished() {
        stopPush()
        requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val uri = RecorderState.lastUri
        val err = RecorderState.lastError
        if (uri != null) {
            UiKit.sheet(this, tx("انحفظ الشرح", "Lesson saved")) { col, d ->
                col.addView(UiKit.text(this, tx("بالمعرض: Movies/ReactionRecorder", "In the Gallery: Movies/ReactionRecorder"), 14.5f, UiKit.SUB))
                val r = LinearLayout(this)
                r.addView(UiKit.chipIcon(this, Ic.PLAY, tx("شغّل", "Play")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Throwable) {}
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
                r.addView(UiKit.chipIcon(this, Ic.SHARE, tx("شارك", "Share")) {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), tx("شارك", "Share")))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                col.addView(r, UiKit.full(this, r, 12))
                val ok = UiKit.primary(this, tx("تمام", "Done")) { d.dismiss() }
                col.addView(ok, UiKit.full(this, ok, 12))
            }
        } else if (err != null) AlertDialog.Builder(this).setMessage(err).setPositiveButton(tx("تمام", "OK"), null).show()
    }

    private var lastMuted = false
    private val tick = object : Runnable {
        override fun run() {
            if (RecorderState.isBusy) {
                capture()
                val ms = RecorderState.elapsedMs()
                val sec = ms / 1000
                timeTv.text = (if (RecorderState.phase == RecorderState.Phase.PAUSED) "❚❚ " else "● ") + String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
                timeTv.setTextColor(Color.parseColor(if (RecorderState.phase == RecorderState.Phase.PAUSED) UiKit.ORANGE else UiKit.RED))
            } else { timeTv.text = tx("شرح", "Lesson"); timeTv.setTextColor(Color.parseColor(UiKit.TEXT)) }
            if (lastMuted != RecorderState.micMuted) { lastMuted = RecorderState.micMuted; renderStatus() }
            renderState()
            ui.postDelayed(this, 40)
        }
    }

    /** مكان كاميرتك فوق المسرح: إطار متقطع بتسحبه (ما بينسجّل — الكاميرا الحقيقية بتنضاف بالتسجيل) */
    inner class CamOverlay(ctx: Context) : View(ctx) {
        private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = 0xCCFFFFFF.toInt(); pathEffect = DashPathEffect(floatArrayOf(dp(6).toFloat(), dp(5).toFloat()), 0f) }
        private val pShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Fonts.semi; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        private var drag = false
        private var dx = 0f
        private var dy = 0f
        private fun box(): RectF {
            val r = BoardCam.rect(context, portrait)
            return RectF(r[0] * width, r[1] * height, (r[0] + r[2]) * width, (r[1] + r[3]) * height)
        }

        override fun onDraw(c: Canvas) {
            if (!BoardCam.visible(context) || width <= 0) return
            val b = box()
            val path = Path()
            when (BoardCam.shape(context)) {
                "circle" -> path.addOval(b, Path.Direction.CW)
                "rounded" -> path.addRoundRect(b, min(b.width(), b.height()) * 0.18f, min(b.width(), b.height()) * 0.18f, Path.Direction.CW)
                else -> path.addRect(b, Path.Direction.CW)
            }
            if (!RecorderState.isBusy || drag) c.drawPath(path, pShade)
            c.drawPath(path, pLine)
            pTxt.textSize = max(dp(10).toFloat(), min(b.width(), b.height()) * 0.13f)
            if (!RecorderState.isBusy || drag) {
                c.drawText(tx("كاميرتك", "Your camera"), b.centerX(), b.centerY(), pTxt)
                c.drawText(tx("اسحبها", "Drag it"), b.centerX(), b.centerY() + pTxt.textSize * 1.3f, pTxt)
            }
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!BoardCam.visible(context)) return false
            val b = box()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!b.contains(e.x, e.y)) return false
                    drag = true; dx = e.x - b.centerX(); dy = e.y - b.centerY()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (drag) {
                    BoardCam.set(context) { putFloat("cx", ((e.x - dx) / width).coerceIn(0f, 1f)); putFloat("cy", ((e.y - dy) / height).coerceIn(0f, 1f)) }
                    if (RecorderState.isBusy) RecorderState.scene?.applyLayout(BoardCam.layout(context, portrait))
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (drag) {
                        // نثبّت المركز ضمن الحدود
                        val r = BoardCam.rect(context, portrait)
                        BoardCam.set(context) { putFloat("cx", r[0] + r[2] / 2f); putFloat("cy", r[1] + r[3] / 2f) }
                        applyCam()
                    }
                    drag = false; invalidate()
                }
            }
            return drag
        }
    }
}

/**
 * لوح الرسم: صفحات، أدوات (قلم، تمييز، ممحاة، ليزر، أشكال)، وخلفيات؛ وفي وضع PDF بيرسم الصفحة
 * وتحتها التعليقات (بإحداثيات الصفحة، فبتضل مكانها مع التكبير والتحريك).
 */
class BoardCanvas(ctx: Context) : View(ctx) {
    companion object {
        const val PEN = 0; const val HL = 1; const val ERASE = 2; const val LASER = 3; const val LINE = 4; const val ARROW = 5; const val RECT = 6; const val OVAL = 7; const val TEXT = 8
        /** أوضاع شفافة (الرسم فوق فيديو) */
        private val CLEAR_MODES = setOf("video", "overlay")
        const val BG_WHITE = 0; const val BG_GRID = 1; const val BG_BLACK = 2
    }

    /** خط مرسوم بإحداثيات نسبية (x/عرض المرجع، y/عرض المرجع) */
    class Stroke(val tool: Int, val color: Int, val width: Float) { val pts = ArrayList<Float>(); var text = "" }
    private sealed class Act { class Add(val s: Stroke) : Act(); class Remove(val list: List<Stroke>) : Act(); class Clear(val list: List<Stroke>) : Act() }
    class Page { val strokes = ArrayList<Stroke>(); val undo = ArrayList<Any>(); val redo = ArrayList<Any>() }

    var tool = PEN
    var color = 0xFF111111.toInt()
    var strokeW = 0.006f
    var bg = BG_WHITE
    private var mode = "draw"
    private val pages = arrayListOf(Page())
    private var page = 0
    private val pdfAnn = HashMap<Int, Page>()
    private var pdfPage = 0
    var pdfPages = 0
        private set
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pdfBmp: Bitmap? = null
    private var pdfBmpPage = -1
    // عرض الـ PDF: تكبير وتحريك
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var cur: Stroke? = null
    private class LP(val x: Float, val y: Float, val t: Long)
    private val laser = ArrayList<LP>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** صفحات الأوضاع الشفافة (الفيديو، الرسم فوق الرياكشن) */
    private val clearPages = HashMap<String, Page>()
    /** أداة الكتابة: ضغطة بمكان (إحداثيات نسبية) */
    var onTextTap: ((Float, Float) -> Unit)? = null
    /** بعد أي رسم/مسح (للي بده يعرف) */
    var onChanged: (() -> Unit)? = null

    private fun curPage(): Page = when {
        mode == "pdf" -> pdfAnn.getOrPut(pdfPage) { Page() }
        mode in CLEAR_MODES -> clearPages.getOrPut(mode) { Page() }
        else -> pages[page]
    }

    fun clearMode(m: String) { clearPages.remove(m); invalidate() }
    fun isEmptyNow(): Boolean = curPage().strokes.isEmpty()

    fun addText(nx: Float, ny: Float, t: String) {
        val s = Stroke(TEXT, color, strokeW).also { it.pts.add(nx); it.pts.add(ny); it.text = t }
        val p = curPage(); val a = Act.Add(s); apply(p, a); p.undo.add(a); p.redo.clear(); invalidate(); onChanged?.invoke()
    }

    fun setMode(m: String) { mode = m; invalidate() }

    fun pageLabel(): String = if (mode == "pdf") (if (pdfPages > 0) "${pdfPage + 1}/$pdfPages" else "—") else "${page + 1}/${pages.size}"

    fun prevPage() { if (mode == "pdf") { if (pdfPage > 0) { pdfPage--; resetView() } } else if (page > 0) page--; invalidate() }
    fun nextPage() { if (mode == "pdf") { if (pdfPage < pdfPages - 1) { pdfPage++; resetView() } } else if (page < pages.size - 1) page++; invalidate() }
    fun addPage() { pages.add(page + 1, Page()); page++; invalidate() }

    fun resetView() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }

    fun undo() { val p = curPage(); val a = p.undo.removeLastOrNull() as? Act ?: return; revert(p, a); p.redo.add(a); invalidate() }
    fun redo() { val p = curPage(); val a = p.redo.removeLastOrNull() as? Act ?: return; apply(p, a); p.undo.add(a); invalidate() }
    fun clearPage() { val p = curPage(); if (p.strokes.isEmpty()) return; val a = Act.Clear(p.strokes.toList()); apply(p, a); p.undo.add(a); p.redo.clear(); invalidate() }

    private fun apply(p: Page, a: Act) { when (a) { is Act.Add -> p.strokes.add(a.s); is Act.Remove -> p.strokes.removeAll(a.list.toSet()); is Act.Clear -> p.strokes.clear() } }
    private fun revert(p: Page, a: Act) { when (a) { is Act.Add -> p.strokes.remove(a.s); is Act.Remove -> p.strokes.addAll(a.list); is Act.Clear -> p.strokes.addAll(a.list) } }

    fun openPdf(f: ParcelFileDescriptor) {
        closePdf()
        pfd = f
        val r = PdfRenderer(f)
        renderer = r
        pdfPages = r.pageCount
        pdfPage = 0; pdfAnn.clear(); resetView()
    }

    fun closePdf() {
        try { renderer?.close() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Throwable) {}
        renderer = null; pfd = null; pdfPages = 0; pdfBmp?.recycle(); pdfBmp = null; pdfBmpPage = -1
    }

    /** مستطيل الصفحة على الشاشة (ملاءمة + تكبير + تحريك) */
    private val pageRect = RectF()
    private fun layoutPdf() {
        val r = renderer ?: return
        if (pdfBmpPage != pdfPage) {
            try {
                val pg: PdfRenderer.Page = r.openPage(pdfPage)
                try {
                    val sc = (max(width, height) * 1.6f / max(pg.width, pg.height).toFloat()).coerceAtMost(4f)
                    val bw = (pg.width * sc).toInt().coerceIn(1, 2600); val bh = (pg.height * sc).toInt().coerceIn(1, 2600)
                    val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pdfBmp?.recycle(); pdfBmp = b; pdfBmpPage = pdfPage
                } finally { pg.close() }
            } catch (_: Throwable) {}
        }
        val b = pdfBmp ?: return
        val s = min(width / b.width.toFloat(), height / b.height.toFloat()) * zoom
        val w = b.width * s; val h = b.height * s
        val x = (width - w) / 2f + panX; val y = (height - h) / 2f + panY
        pageRect.set(x, y, x + w, y + h)
    }

    /** المرجع للإحداثيات النسبية: المسرح كله (لوح) أو الصفحة (PDF) */
    private fun ref(): RectF = if (mode == "pdf" && pdfPages > 0) pageRect else RectF(0f, 0f, width.toFloat(), height.toFloat())

    /** بيزيد مع كل رسمة على الشاشة (عشان اللي بينسخ الرسم يعرف إنه تغيّر) */
    var drawCount = 0L
        private set

    override fun onDraw(c: Canvas) {
        drawCount++
        when {
            mode in CLEAR_MODES -> {}
            mode == "pdf" -> {
                c.drawColor(Color.parseColor("#3A3D45"))
                if (pdfPages > 0) { layoutPdf(); pdfBmp?.let { c.drawBitmap(it, null, pageRect, bmpP) } }
            }
            mode == "text" || bg == BG_WHITE -> c.drawColor(Color.WHITE)
            bg == BG_BLACK -> c.drawColor(Color.parseColor("#1E2421"))
            else -> {
                c.drawColor(Color.WHITE)
                fill.color = 0xFFE3E8EF.toInt(); fill.strokeWidth = 1.5f
                val step = width / 24f
                var x = step; while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), fill); x += step }
                var y = step; while (y < height) { c.drawLine(0f, y, width.toFloat(), y, fill); y += step }
            }
        }
        if (mode == "text") return
        val R = ref()
        for (s in curPage().strokes) drawStroke(c, s, R)
        cur?.let { drawStroke(c, it, R) }
        // الليزر: أثر أحمر بيختفي خلال ثانية
        val now = SystemClock.uptimeMillis()
        laser.removeAll { now - it.t > 900 }
        if (laser.isNotEmpty()) {
            for (i in 1 until laser.size) {
                val a = 1f - (now - laser[i].t) / 900f
                paint.color = Color.argb((230 * a).toInt().coerceIn(0, 255), 255, 59, 48); paint.strokeWidth = R.width() * 0.008f; paint.pathEffect = null
                c.drawLine(R.left + laser[i - 1].x * R.width(), R.top + laser[i - 1].y * R.width(), R.left + laser[i].x * R.width(), R.top + laser[i].y * R.width(), paint)
            }
            val l = laser.last(); fill.color = 0xFFFF3B30.toInt()
            c.drawCircle(R.left + l.x * R.width(), R.top + l.y * R.width(), R.width() * 0.008f, fill)
            postInvalidateDelayed(30)
        }
    }

    private val textP = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.semi }

    private fun drawStroke(c: Canvas, s: Stroke, R: RectF) {
        if (s.tool == TEXT) {
            if (s.pts.size < 2) return
            textP.color = s.color
            textP.textSize = R.width() * (0.022f + s.width * 2.2f)
            val x = R.left + s.pts[0] * R.width(); val y = R.top + s.pts[1] * R.width()
            // ظل خفيف عشان تنقرا فوق أي خلفية/فيديو
            textP.setShadowLayer(textP.textSize * 0.08f, 0f, 0f, if (Color.luminance(s.color) > 0.5f) Color.BLACK else Color.WHITE)
            var yy = y
            for (line in s.text.split('\n')) { c.drawText(line, x, yy, textP); yy += textP.textSize * 1.25f }
            return
        }
        if (s.pts.size < 2) return
        paint.color = s.color
        paint.strokeWidth = s.width * R.width() * (if (s.tool == HL) 3.2f else 1f)
        paint.alpha = if (s.tool == HL) 90 else 255
        paint.pathEffect = null
        fun X(i: Int) = R.left + s.pts[i] * R.width()
        fun Y(i: Int) = R.top + s.pts[i + 1] * R.width()
        val n = s.pts.size
        when (s.tool) {
            LINE, ARROW, RECT, OVAL -> {
                val x0 = X(0); val y0 = Y(0); val x1 = X(n - 2); val y1 = Y(n - 2)
                when (s.tool) {
                    LINE -> c.drawLine(x0, y0, x1, y1, paint)
                    ARROW -> {
                        c.drawLine(x0, y0, x1, y1, paint)
                        val ang = Math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                        val len = paint.strokeWidth * 4f
                        for (d in listOf(2.6, -2.6)) c.drawLine(x1, y1, x1 + (len * Math.cos(ang + d)).toFloat(), y1 + (len * Math.sin(ang + d)).toFloat(), paint)
                    }
                    RECT -> c.drawRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), paint)
                    else -> c.drawOval(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), paint)
                }
            }
            else -> {
                val p = Path()
                p.moveTo(X(0), Y(0))
                var i = 2
                while (i < n) { val mx = (X(i - 2) + X(i)) / 2f; val my = (Y(i - 2) + Y(i)) / 2f; p.quadTo(X(i - 2), Y(i - 2), mx, my); i += 2 }
                p.lineTo(X(n - 2), Y(n - 2))
                c.drawPath(p, paint)
            }
        }
        paint.alpha = 255
    }

    // ───────── اللمس ─────────

    private var panning = false
    private var lastMx = 0f
    private var lastMy = 0f
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (mode != "pdf" || pdfPages == 0) return false
            val nz = (zoom * d.scaleFactor).coerceIn(1f, 6f)
            val k = nz / zoom
            panX = (panX - (d.focusX - width / 2f)) * k + (d.focusX - width / 2f)
            panY = (panY - (d.focusY - height / 2f)) * k + (d.focusY - height / 2f)
            zoom = nz; invalidate(); return true
        }
    })

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode == "text") return false
        scaler.onTouchEvent(e)
        if (e.pointerCount >= 2) {
            // إصبعين: تحريك/تكبير (PDF)، ونلغي الخط اللي بلش
            if (cur != null) { cur = null; invalidate() }
            val mx = (e.getX(0) + e.getX(1)) / 2f; val my = (e.getY(0) + e.getY(1)) / 2f
            if (!panning || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) { panning = true; lastMx = mx; lastMy = my }
            else if (mode == "pdf") { panX += mx - lastMx; panY += my - lastMy; lastMx = mx; lastMy = my; invalidate() }
            return true
        }
        if (panning) { if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) panning = false; return true }
        val R = ref()
        if (R.width() <= 0f) return true
        val nx = (e.x - R.left) / R.width(); val ny = (e.y - R.top) / R.width()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                when (tool) {
                    TEXT -> {}
                    LASER -> { laser.add(LP(nx, ny, SystemClock.uptimeMillis())); invalidate() }
                    ERASE -> eraseAt(nx, ny)
                    else -> { cur = Stroke(tool, color, strokeW).also { it.pts.add(nx); it.pts.add(ny) }; invalidate() }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (tool) {
                    LASER -> { laser.add(LP(nx, ny, SystemClock.uptimeMillis())); invalidate() }
                    ERASE -> eraseAt(nx, ny)
                    else -> cur?.let { s ->
                        if (s.tool in listOf(LINE, ARROW, RECT, OVAL) && s.pts.size >= 4) { s.pts[s.pts.size - 2] = nx; s.pts[s.pts.size - 1] = ny }
                        else { val lx = s.pts[s.pts.size - 2]; val ly = s.pts[s.pts.size - 1]; if (hypot(nx - lx, ny - ly) > 0.0015f || s.pts.size < 4) { s.pts.add(nx); s.pts.add(ny) } }
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tool == TEXT && e.actionMasked == MotionEvent.ACTION_UP) onTextTap?.invoke(nx, ny)
                if (cur != null) onChanged?.invoke()
                cur?.let { s -> if (s.pts.size >= 4 || s.tool == PEN || s.tool == HL) { if (s.pts.size < 4) { s.pts.add(nx + 0.0005f); s.pts.add(ny) }; val p = curPage(); val a = Act.Add(s); apply(p, a); p.undo.add(a); p.redo.clear() } }
                cur = null; invalidate()
            }
        }
        return true
    }

    private fun eraseAt(nx: Float, ny: Float) {
        val p = curPage()
        val r = 0.02f
        val hit = p.strokes.filter { s ->
            if (s.tool == TEXT && s.pts.size >= 2) {
                val sz = 0.022f + s.width * 2.2f
                val w = s.text.lines().maxOf { it.length } * sz * 0.55f
                val lines = s.text.lines().size
                // النص العربي بيمتد لليسار من نقطة البداية؛ الإنجليزي لليمين → نقبل الجهتين
                nx > s.pts[0] - w - r && nx < s.pts[0] + w + r && ny > s.pts[1] - sz - r && ny < s.pts[1] + sz * 1.25f * (lines - 1) + r
            } else { var k = 0; var h = false; while (k < s.pts.size - 1) { if (abs(s.pts[k] - nx) < r && abs(s.pts[k + 1] - ny) < r) { h = true; break }; k += 2 }; h }
        }
        if (hit.isEmpty()) return
        val a = Act.Remove(hit); apply(p, a); p.undo.add(a); p.redo.clear(); invalidate()
    }
}
