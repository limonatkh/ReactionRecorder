package com.reactionrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * شاشة الجهاز المساعد (كاميرا ومايك، أو مايك لاسلكي): حالة واضحة، والتسجيل محلي بالكامل.
 * الكاميرا: معاينة حية، اتجاه تلقائي يثبت عند بدء التسجيل، قفل/تدوير يدوي.
 * المايك: مستوى الصوت فقط، وزر لتعتيم الشاشة توفيرًا للبطارية.
 */
class SatelliteActivity : BaseActivity() {
    override val allowedWithoutLicense = true
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var cfg: SatCfg
    private lateinit var statusTv: TextView
    private lateinit var dotTv: TextView
    private lateinit var transferTv: TextView
    private lateinit var level: ProgressBar
    private lateinit var connectBtn: Button
    private lateinit var sessionsBox: LinearLayout
    private var previewBox: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var recSurface: SurfaceView? = null
    private var hintTv: TextView? = null
    private var localImg: android.widget.ImageView? = null
    private var localSeen = 0L
    private var previewBound = false
    private var lastBindTry = 0L
    private var lastSessionsSig = ""
    private var dim: View? = null
    private var localOnly = false
    private var lockRot = false

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun dp(v: Int) = UiKit.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cfg = SatCfg.load(this)
        localOnly = intent.getBooleanExtra("localOnly", false) && !SatState.serviceOn
        lockRot = getSharedPreferences("reaction_ui", MODE_PRIVATE).getBoolean("satLock", false)
        build()
        ui.post(tick)
    }

    private var shown = false
    override fun onResume() { super.onResume(); shown = true }

    override fun onPause() {
        super.onPause()
        shown = false
        if (SatState.wantLocal && SatState.serviceOn) { SatState.wantLocal = false; startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_LOCALPV)) }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(tick)
        if (recSurface != null) PreviewBridge.detach()
        if (!SatState.serviceOn && !SatState.recording) try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Throwable) {}
    }

    private val isCam get() = cfg.role == "both"
    private var offNote: TextView? = null
    private var builtRole = ""

    private fun build() {
        val title = when {
            localOnly -> tx("تسجيلات على هذا الجوال", "Recordings on this phone")
            isCam -> "🎥 " + tx("كاميرا ومايك", "Camera and mic")
            else -> "🎤 " + tx("مايك لاسلكي", "Wireless mic")
        }
        val col = UiKit.page(this, title)
        pageCol = col
        builtRole = cfg.role

        val st = UiKit.card(this, null, col)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        dotTv = UiKit.text(this, "⚪", 22f)
        head.addView(dotTv)
        statusTv = UiKit.text(this, "", 15f, UiKit.TEXT, true).apply { setPadding(dp(10), 0, 0, 0) }
        head.addView(statusTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        st.addView(head)
        level = UiKit.level(this)
        st.addView(level, UiKit.full(this, level, 10, if (isCam) 8 else 22))
        transferTv = UiKit.text(this, "", 12f, UiKit.SUB)
        st.addView(transferTv)
        val on0 = UiKit.text(this, tx("✓ عادي تطفي الشاشة من زر الباور — التسجيل بيضل شغال. الجوال بيهتز هزة لما يبلّش، وهزتين لما يوقف. وبتشوف من الجوال الرئيسي إنه بيسجّل.", "✓ It's fine to turn the screen off with the power button — recording keeps going. The phone buzzes once when it starts and twice when it stops, and the main phone shows it's recording."), 13f, UiKit.GREEN).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = UiKit.bg("#14301C", 12, this@SatelliteActivity); visibility = View.GONE
        }
        offNote = on0
        st.addView(on0, UiKit.full(this, on0, 10))
        connectBtn = UiKit.primary(this, "") { onConnectClicked() }
        if (!localOnly) st.addView(connectBtn, UiKit.full(this, connectBtn, 10, 50))

        if (!localOnly && isCam) buildCamera(col)
        if (!localOnly && !isCam) {
            val c = UiKit.card(this, null, col)
            c.addView(UiKit.text(this, tx("ضع الجوال قريبًا من فم الشخص (20–30 سم) ووجّه أسفله نحوه. التسجيل يستمر حتى لو انقطعت الشبكة.", "Place the phone 20–30 cm from the speaker's mouth, bottom facing them. Recording continues even if the network drops."), 13f, UiKit.SUB))
            val b = UiKit.chipIcon(this, Ic.MOON, tx("عتّم الشاشة (لمسة واحدة للرجوع)", "Dim the screen (tap to wake)")) { setDim(true) }
            c.addView(b, UiKit.full(this, b, 10))
        }
        if (!localOnly) buildMic(col)

        val ses = UiKit.section(this, col, "sat_local", tx("التسجيلات على هذا الجوال", "Recordings on this phone"), tx("تُحذف فقط عندما تختار ذلك", "Removed only when you choose"), openByDefault = localOnly)
        sessionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ses.addView(sessionsBox)

        if (!localOnly) {
            val other = UiKit.chip(this, "⇄ " + tx("تغيير الدور / الجوال الرئيسي", "Change role / main phone")) {
                if (SatState.recording) { toast(tx("أوقف التسجيل أولًا من الجوال الرئيسي", "Stop recording from the main phone first")); return@chip }
                if (SatState.serviceOn) startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_DISCONNECT))
                ui.postDelayed({ startActivity(Intent(this, JoinActivity::class.java)); finish() }, 600)
            }
            col.addView(other, UiKit.full(this, other, 12))
        }
        landRow = null; boxHome = null
        applyLand(resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    // ───────── بالعرض: المعاينة كبيرة على جهة، والباقي على الجهة الثانية ─────────
    private var pageCol: LinearLayout? = null
    private var landRow: LinearLayout? = null
    private var boxHome: Pair<ViewGroup, Int>? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLand(newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun applyLand(land: Boolean) {
        val box = previewBox ?: return
        val col = pageCol ?: return
        val sv = col.parent as? android.widget.ScrollView ?: return
        if (land == (landRow != null)) return
        if (land) {
            val home = box.parent as? ViewGroup ?: return
            boxHome = home to home.indexOfChild(box)
            home.removeView(box)
            (sv.parent as? ViewGroup)?.removeView(sv)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor(UiKit.BG)) }
            row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f).apply { setMargins(dp(8), dp(8), dp(4), dp(8)) })
            row.addView(sv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            setContentView(row)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(row) { v, insets ->
                val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom); insets
            }
            sv.setPadding(0, 0, 0, 0)
            landRow = row
        } else {
            val row = landRow ?: return
            row.removeAllViews()
            landRow = null
            val (home, idx) = boxHome ?: return
            home.addView(box, idx.coerceIn(0, home.childCount), UiKit.full(this, box, 4, 300))
            setContentView(sv)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sv) { v, insets ->
                val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                v.setPadding(0, b.top, 0, b.bottom); insets
            }
        }
    }

    private fun buildCamera(col: LinearLayout) {
        val c = UiKit.card(this, tx("المعاينة", "Preview"), col)
        val box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); background = UiKit.bg("#000000", 14, this@SatelliteActivity) }
        previewBox = box
        val pv = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER }
        previewView = pv
        box.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val sv = SurfaceView(this)
        recSurface = sv
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { PreviewBridge.attach(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { PreviewBridge.attach(h.surface) }
            override fun surfaceDestroyed(h: SurfaceHolder) { PreviewBridge.detach() }
        })
        sv.visibility = View.GONE
        box.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // وهو متصل وقبل التسجيل: نفس الإطار اللي رح يتسجّل (من الخدمة)
        val li = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        localImg = li
        box.addView(li, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hintTv = UiKit.text(this, "", 13f, "#FFFFFF").apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.addView(hintTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        c.addView(box, UiKit.full(this, box, 4, 300))
        val fr = UiKit.chipIcon(this, Ic.FRAME, tx("شوف الإطار كامل (وين أحط الجوال)", "See the full frame (where to place the phone)")) {
            startActivity(Intent(this, FramingActivity::class.java).putExtra("local", true))
        }
        c.addView(fr, UiKit.full(this, fr, 8))
        // قلب الكاميرا بإيدك — حتى أثناء التسجيل (التسجيل لا يتوقف)
        val flip = UiKit.primary(this, tx("اقلب الكاميرا", "Flip camera"), UiKit.BLUE) {
            if (SatState.serviceOn) startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_FLIP))
            else { cfg.front = !cfg.front; SatCfg.save(this, cfg); previewBound = false }
        }
        c.addView(flip, UiKit.full(this, flip, 8, 52))

        c.addView(UiKit.text(this, tx("اتجاه الفيديو", "Video orientation"), 13f, UiKit.SUB).apply { setPadding(0, dp(10), 0, 0) })
        val oRow = LinearLayout(this)
        fun oSel() {
            oRow.removeAllViews()
            for ((k, n) in listOf("auto" to tx("تلقائي", "Auto"), "portrait" to tx("عمودي", "Portrait"), "landscape" to tx("أفقي", "Landscape"))) {
                oRow.addView(UiKit.chip(this, n, cfg.orient == k) {
                    if (SatState.recording) { toast(tx("الاتجاه ثابت أثناء التسجيل", "Orientation is fixed while recording")); return@chip }
                    cfg.orient = k; SatCfg.save(this, cfg); oSel()
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            }
        }
        oSel()
        c.addView(oRow)
        c.addView(UiKit.text(this, tx("تلقائي: حسب وضع الجوال لحظة البدء، ثم يثبت حتى النهاية. الصورة كاملة بدون قص.", "Auto: follows how you hold the phone when recording starts, then stays fixed. Full picture, no cropping."), 11.5f, UiKit.SUB))

        val r2 = LinearLayout(this)
        val lockB = UiKit.chip(this, "", lockRot) {}
        fun lockL() { lockB.text = if (lockRot) "🔒 " + tx("الشاشة مقفلة", "Rotation locked") else "🔓 " + tx("الشاشة تدور", "Auto-rotate"); UiKit.style(lockB, lockRot) }
        lockL()
        lockB.setOnClickListener {
            lockRot = !lockRot
            getSharedPreferences("reaction_ui", MODE_PRIVATE).edit().putBoolean("satLock", lockRot).apply()
            lockL(); applyOrientation()
        }
        val rotB = UiKit.chip(this, "↻ " + tx("تدوير الصورة ${cfg.rot}°", "Rotate picture ${cfg.rot}°")) {}
        rotB.setOnClickListener {
            cfg.rot = (cfg.rot + 90) % 360; SatCfg.save(this, cfg)
            rotB.text = "↻ " + tx("تدوير الصورة ${cfg.rot}°", "Rotate picture ${cfg.rot}°")
        }
        r2.addView(lockB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        r2.addView(rotB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(r2, UiKit.full(this, r2, 6))

        val more = UiKit.section(this, col, "sat_cam", tx("الكاميرا والجودة", "Camera and quality"), tx("الأمامية/الخلفية، الدقة", "Front/back, resolution"))
        val camRow = LinearLayout(this)
        fun camSel() {
            camRow.removeAllViews()
            camRow.addView(UiKit.chip(this, tx("الأمامية", "Front"), cfg.front) { if (!SatState.recording) { cfg.front = true; SatCfg.save(this, cfg); camSel(); previewBound = false } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            camRow.addView(UiKit.chip(this, tx("الخلفية", "Back"), !cfg.front) { if (!SatState.recording) { cfg.front = false; SatCfg.save(this, cfg); camSel(); previewBound = false } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        camSel()
        more.addView(camRow)
        val qRow = LinearLayout(this)
        fun qSel() {
            qRow.removeAllViews()
            for (q in listOf(480, 720, 1080)) qRow.addView(UiKit.chip(this, "${q}p", cfg.shortSide == q) {
                if (SatState.recording) return@chip
                cfg.shortSide = q; cfg.camLong = when (q) { 480 -> 854; 1080 -> 1920; else -> 1280 }; SatCfg.save(this, cfg); qSel()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        qSel()
        more.addView(UiKit.text(this, tx("الدقة (الأقل أثبت على الجوالات الضعيفة)", "Resolution (lower is steadier on weak phones)"), 12f, UiKit.SUB).apply { setPadding(0, dp(8), 0, 0) })
        more.addView(qRow)
    }

    private fun buildMic(col: LinearLayout) {
        val m = UiKit.section(this, col, "sat_mic", tx("إعدادات المايك", "Microphone settings"), tx("صوتي بس / طبيعي / المحيط، التكبير", "Voice / natural / surroundings, gain"))
        m.addView(UiKit.text(this, tx("«صوتي بس» بيخفف صوت الناس والضجة حواليك (أحسن إشي والجوال قريب من الثم ٢٠ سم).", "“Just my voice” reduces people and noise around (best with the phone ~20 cm from the mouth)."), 12f, UiKit.SUB))
        val aRow = LinearLayout(this)
        fun aSel() {
            aRow.removeAllViews()
            for ((k, n) in listOf(2 to tx("صوتي بس", "Just my voice"), 0 to tx("طبيعي", "Natural"), 1 to tx("المحيط", "Surroundings")))
                aRow.addView(UiKit.chip(this, n, cfg.aSrc == k) { if (SatState.recording) return@chip; cfg.aSrc = k; SatCfg.save(this, cfg); aSel() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        aSel()
        m.addView(aRow)
        val gainTv = UiKit.text(this, "", 13f).apply { setPadding(0, dp(8), 0, 0) }
        fun gl() { gainTv.text = tx("التكبير: ", "Gain: ") + String.format(Locale.US, "%.1fx", cfg.gain) }
        gl()
        m.addView(gainTv)
        m.addView(SeekBar(this).apply {
            max = 300; progress = (cfg.gain * 100).toInt().coerceIn(0, 300)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { cfg.gain = (p / 100f).coerceAtLeast(0.1f); gl() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { SatCfg.save(this@SatelliteActivity, cfg) }
            })
        })
    }

    private fun setDim(on: Boolean) {
        val root = window.decorView as ViewGroup
        if (on && dim == null) {
            val v = View(this).apply { setBackgroundColor(Color.BLACK); setOnClickListener { setDim(false) } }
            root.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dim = v
            window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        } else if (!on && dim != null) {
            root.removeView(dim); dim = null
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }

    private fun applyOrientation() {
        val want = when {
            SatState.recording || lockRot -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        if (requestedOrientation != want) requestedOrientation = want
    }

    private fun onConnectClicked() {
        if (SatState.serviceOn) {
            if (SatState.recording) {
                AlertDialog.Builder(this).setTitle(tx("قطع الاتصال أثناء التسجيل؟", "Disconnect while recording?"))
                    .setMessage(tx("سيتوقف التسجيل على هذا الجوال ويُحفظ. الأفضل الإيقاف من الجوال الرئيسي.", "Recording on this phone will stop and be saved. Better to stop from the main phone."))
                    .setPositiveButton(tx("أوقف واقطع", "Stop and disconnect")) { _, _ -> startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_DISCONNECT)) }
                    .setNegativeButton(tx("رجوع", "Back"), null).show()
            } else startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_DISCONNECT))
            return
        }
        if (cfg.host.isBlank()) { startActivity(Intent(this, JoinActivity::class.java)); finish(); return }
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Throwable) {}
        previewBound = false
        ContextCompat.startForegroundService(this, Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_CONNECT))
    }

    private fun rebindPreview() {
        val pv = previewView ?: return
        if (!isCam || SatState.recording || SatState.serviceOn || !granted(Manifest.permission.CAMERA)) return
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                if (SatState.recording || SatState.serviceOn) return@addListener
                val p = f.get()
                p.unbindAll()
                val prev = Preview.Builder().build()
                prev.setSurfaceProvider(pv.surfaceProvider)
                p.bindToLifecycle(this, if (cfg.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, prev)
                previewBound = true
            } catch (_: Throwable) {
                previewBound = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 400)
        }
    }

    private fun refresh() {
        cfg = SatCfg.load(this)
        // الجوال الرئيسي غيّر دور هالجوال (مايك ↔ كاميرا): نبني الشاشة من جديد
        if (builtRole.isNotEmpty() && cfg.role != builtRole && !localOnly) { builtRole = cfg.role; recreate(); return }
        val on = SatState.serviceOn
        val rec = SatState.recording
        applyOrientation()
        if (!localOnly) {
            connectBtn.text = if (on) tx("قطع الاتصال", "Disconnect") else tx("اتصل", "Connect")
            connectBtn.background = UiKit.bg(if (on) UiKit.CARD2 else UiKit.RED, 24, this)
            dotTv.text = if (rec) "🔴" else if (SatState.connected) "🟢" else if (on) "🟠" else "⚪"
            val since = SatState.recSince
            val secs = if (rec && since > 0) (SystemClock.elapsedRealtime() - since) / 1000 else 0
            offNote?.visibility = if (rec) View.VISIBLE else View.GONE
            statusTv.text = if (rec) tx("يسجّل الآن", "Recording now") + String.format(Locale.US, " · %d:%02d", secs / 60, secs % 60) + (if (!SatState.connected) " — " + tx("الاتصال مقطوع والتسجيل مستمر", "offline, still recording") else "")
            else SatState.status
            level.progress = (SatState.level * 100 / 32767).coerceIn(0, 100)
            transferTv.text = SatState.transfer
        } else {
            dotTv.text = "📁"; statusTv.text = tx("هذا الجوال", "This phone"); level.visibility = View.GONE
        }

        // المعاينة: محلية قبل الاتصال، والصورة المسجّلة نفسها أثناء التسجيل
        recSurface?.visibility = if (rec) View.VISIBLE else View.GONE
        previewView?.visibility = if (!on && !rec) View.VISIBLE else View.INVISIBLE
        hintTv?.text = when {
            rec -> ""
            on -> tx("تجهيز الصورة…", "Preparing the picture…")
            else -> ""
        }
        // متصل وقبل التسجيل: الصورة من الخدمة نفسها (نفس إطار التسجيل)
        val wantL = shown && isCam && on && !rec && dim == null && !isFinishing
        if (SatState.wantLocal != wantL) { SatState.wantLocal = wantL; startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_LOCALPV)) }
        val lf = SatState.localFrame
        localImg?.visibility = if (wantL && lf != null) View.VISIBLE else View.GONE
        if (wantL && lf != null && SatState.localFrameAt != localSeen) { localImg?.setImageBitmap(lf); localSeen = SatState.localFrameAt }
        hintTv?.visibility = if (on && !rec && lf == null) View.VISIBLE else View.GONE
        if (rec) fitRecSurface()
        if (on || rec) previewBound = false
        else if (isCam && !previewBound && SystemClock.elapsedRealtime() - lastBindTry > 3000L) { lastBindTry = SystemClock.elapsedRealtime(); rebindPreview() }

        val list = SatState.localSessions(this)
        val sig = list.joinToString("|") { "${it.id}:${it.uploaded}:${it.sizeBytes / 100000}" } + SatState.connected + rec
        if (sig != lastSessionsSig) {
            lastSessionsSig = sig
            sessionsBox.removeAllViews()
            if (list.isEmpty()) sessionsBox.addView(UiKit.text(this, tx("لا يوجد", "None"), 12f, UiKit.SUB))
            for (s in list) sessionsBox.addView(sessionRow(s, rec))
        }
    }

    /** الصورة المسجّلة بنسبتها الحقيقية داخل صندوق المعاينة */
    private fun fitRecSurface() {
        val box = previewBox ?: return
        val sv = recSurface ?: return
        val bw = box.width; val bh = box.height
        if (bw <= 0 || bh <= 0) return
        val portrait = SatState.lockedOrient != 2
        val a = if (portrait) 9f / 16f else 16f / 9f
        var w = bw; var h = (bw / a).toInt()
        if (h > bh) { h = bh; w = (bh * a).toInt() }
        val lp = sv.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER; sv.layoutParams = lp }
    }

    private fun sessionRow(s: SatState.Local, rec: Boolean): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val date = try { java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(java.util.Date(s.id.drop(1).toLong())) } catch (_: Throwable) { s.id }
        box.addView(UiKit.text(this, date + "  •  " + StorageUtil.formatBytes(s.sizeBytes) + "  •  " + (if (s.hasVideo) tx("فيديو", "video") else tx("صوت", "audio")), 13f))
        box.addView(UiKit.text(this, if (s.uploaded) "✓ " + tx("وصل للجوال الرئيسي", "Received by the main phone") else "⏳ " + tx("لم يُرسل بعد — يُرسل تلقائيًا عند الاتصال", "Not sent yet — sent automatically when connected"), 12f, if (s.uploaded) UiKit.GREEN else UiKit.ORANGE))
        val r = LinearLayout(this)
        r.addView(UiKit.chip(this, "⬆ " + tx("أرسل", "Send")) {
            if (!SatState.connected) toast(tx("غير متصل — يُرسل تلقائيًا عند رجوع الاتصال", "Not connected — it is sent automatically when reconnected"))
            else startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_UPLOAD).putExtra("session", s.id))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        r.addView(UiKit.chip(this, "📤 " + tx("احفظ في التنزيلات", "Save to Downloads")) {
            val c = applicationContext
            toast(tx("جارٍ النسخ…", "Copying…"))
            Thread {
                val res = SatState.exportLocal(c, s.id)
                ui.post {
                    if (res == null) toast(tx("تعذر النسخ", "Copy failed"))
                    else AlertDialog.Builder(this).setTitle(tx("نُسخت الملفات", "Files copied"))
                        .setMessage(tx("المجلد: $res\n\nانقلها للجوال الرئيسي بأي طريقة (Quick Share أو كابل)، ثم من هناك: تسجيلاتي ← التسجيل ← الملفات ← استيراد.", "Folder: $res\n\nMove them to the main phone any way (Quick Share or cable), then there: My recordings ← the recording ← Files ← Import."))
                        .setPositiveButton(tx("تم", "OK"), null).show()
                }
            }.start()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        r.addView(UiKit.chip(this, "🗑") {
            if (rec && s.id == SatState.sessionId) return@chip
            AlertDialog.Builder(this).setTitle(tx("حذف هذا التسجيل من هذا الجوال؟", "Delete this recording from this phone?"))
                .setMessage(if (s.uploaded) tx("وصل للجوال الرئيسي من قبل.", "It was already received by the main phone.") else tx("لم يُرسل بعد — سيضيع نهائيًا!", "It was NOT sent yet — it will be lost!"))
                .setPositiveButton(tx("حذف", "Delete")) { _, _ -> SatState.deleteLocal(this, s.id); lastSessionsSig = "" }
                .setNegativeButton(tx("إبقاء", "Keep"), null).show()
        })
        box.addView(r)
        return box
    }
}
