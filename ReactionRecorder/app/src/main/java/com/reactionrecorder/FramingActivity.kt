package com.reactionrecorder

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * "الإطار كامل": شاشة كاملة بتفرجيك كل اللي الكاميرا بتسجّله بالزبط (بدون قص) — عشان تعرف وين تحط الجوال.
 * dev = جهاز متصل (من الجوال الرئيسي)، أو local = كاميرا هذا الجوال.
 */
class FramingActivity : BaseActivity() {
    override val allowedWithoutLicense = true
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var frameView: FrameView
    private lateinit var info: TextView
    private var dev = ""
    private var local = false
    private var pv: PreviewView? = null
    private var seen = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dev = intent.getStringExtra("dev") ?: ""
        local = intent.getBooleanExtra("local", false)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frameView = FrameView(this)
        if (local && !SatState.serviceOn) {
            val p = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER }
            pv = p
            root.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frameView.overlayOnly = true
        }
        root.addView(frameView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setBackgroundColor(0x99000000.toInt())
            val d = UiKit.dp(this@FramingActivity, 10); setPadding(d, d, d, d)
        }
        root.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { val m = UiKit.dp(this@FramingActivity, 12); setMargins(m, m * 3, m, m) })
        val close = UiKit.chip(this, "✕ " + tx("سكّر", "Close")) { finish() }
        root.addView(close, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = UiKit.dp(this@FramingActivity, 24) })
        setContentView(root)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    override fun onResume() {
        super.onResume()
        if (local) {
            if (SatState.serviceOn) { SatState.wantLocal = true; startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_LOCALPV)) }
            else bindLocal()
        } else SatHub.setPreview(dev, true, false, true, 150, 640)
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        if (local) {
            if (SatState.serviceOn) { SatState.wantLocal = false; startService(Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_LOCALPV)) }
            else try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Throwable) {}
        } else SatHub.links[dev]?.let { SatHub.setPreview(dev, it.stripOn, false, it.stripOn) }
    }

    private fun bindLocal() {
        val p = pv ?: return
        val cfg = SatCfg.load(this)
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val prov = f.get(); prov.unbindAll()
                val rs = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                val prev = Preview.Builder().setResolutionSelector(rs).build()
                prev.setSurfaceProvider(p.surfaceProvider)
                prov.bindToLifecycle(this, if (cfg.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, prev)
            } catch (_: Throwable) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 120)
        }
    }

    private fun refresh() {
        val bmp: Bitmap?
        val at: Long
        val name: String
        if (local) { bmp = SatState.localFrame; at = SatState.localFrameAt; name = tx("هذا الجوال", "This phone") }
        else { val l = SatHub.links[dev]; bmp = l?.frame; at = l?.frameAt ?: 0L; name = l?.name ?: "" }
        if (pv == null && bmp != null && at != seen) { seen = at; frameView.bmp = bmp; frameView.invalidate() }
        val land = if (pv != null) resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            else bmp != null && bmp.width > bmp.height
        frameView.aspect = if (pv != null) (if (land) 16f / 9f else 9f / 16f) else 0f
        val q = if (local) SatCfg.load(this).shortSide else (SatHub.links[dev]?.cfgs?.optInt("q", 720) ?: 720)
        val long = when (q) { 480 -> 848; 1080 -> 1920; else -> 1280 }
        info.text = "$name\n" + (if (bmp == null && pv == null) tx("بانتظار الصورة…", "Waiting for the picture…") else
            (if (land) tx("بالعرض", "Landscape") + " 16:9  ($long×$q)" else tx("بالطول", "Portrait") + " 9:16  ($q×$long)")) + "\n" +
            tx("الإطار الأبيض = كل اللي بيتسجّل بالزبط، بدون قص.\nالاتجاه بيثبت لحظة ما يبدأ التسجيل.", "The white frame = exactly everything recorded, no cropping.\nThe orientation locks when recording starts.")
    }

    /** يرسم الصورة كاملة (FIT) + إطار أبيض حولها + خطوط الأثلاث لتساعدك بالتكوين */
    class FrameView(ctx: Context) : View(ctx) {
        var bmp: Bitmap? = null
        var overlayOnly = false
        /** نسبة الإطار عندما لا توجد صورة (معاينة PreviewView) */
        var aspect = 0f
        private val pB = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val pE = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 4f }
        private val pG = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = 2f }

        override fun onDraw(c: Canvas) {
            val b = bmp
            val a = if (!overlayOnly && b != null) b.width.toFloat() / b.height else aspect
            if (a <= 0f) return
            val s = min(width / a, height.toFloat())
            val w = s * a; val h = s
            val r = RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            if (!overlayOnly && b != null) c.drawBitmap(b, null, r, pB)
            for (k in 1..2) {
                c.drawLine(r.left + r.width() * k / 3f, r.top, r.left + r.width() * k / 3f, r.bottom, pG)
                c.drawLine(r.left, r.top + r.height() * k / 3f, r.right, r.top + r.height() * k / 3f, pG)
            }
            c.drawRect(r, pE)
        }
    }
}
