package com.reactionrecorder

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** يقرأ باركود الاقتران الظاهر على الجوال الرئيسي ويعيد العنوان والـ PIN */
class ScanActivity : BaseActivity() {
    override val allowedWithoutLicense = true
    private val exec = Executors.newSingleThreadExecutor()
    private val done = AtomicBoolean(false)
    private val scanner by lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pv = PreviewView(this)
        val hint = TextView(this).apply {
            text = when (intent.getStringExtra("mode")) {
                "lic" -> tx("وجّه الكاميرا نحو باركود الترخيص على جوال صاحب التطبيق", "Point the camera at the licence QR on the owner's phone")
                "dev" -> tx("وجّه الكاميرا نحو رمز الجهاز على جوال صاحبك", "Point the camera at your friend's device code")
                else -> tx("وجّه الكاميرا نحو الباركود على الجوال الرئيسي", "Point the camera at the QR code on the main phone")
            }
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(24, 24, 24, 24)
        }
        val root = FrameLayout(this)
        root.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val p = f.get()
                p.unbindAll()
                val prev = Preview.Builder().build()
                prev.setSurfaceProvider(pv.surfaceProvider)
                val an = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                an.setAnalyzer(exec) { img -> scan(img) }
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, an)
            } catch (e: Throwable) {
                hint.text = tx("خطأ في الكاميرا: ", "Camera error: ") + e.message
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun scan(img: ImageProxy) {
        val media = img.image
        if (media == null || done.get()) { img.close(); return }
        val input = InputImage.fromMediaImage(media, img.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { list ->
                for (b in list) {
                    val raw = b.rawValue ?: continue
                    val mode = intent.getStringExtra("mode") ?: "pair"
                    if (mode != "pair") {
                        val ok = (mode == "lic" && raw.startsWith("RRLIC1.")) || (mode == "dev" && raw.startsWith("RRDEV|"))
                        if (ok && done.compareAndSet(false, true)) { setResult(RESULT_OK, Intent().putExtra("raw", raw)); finish(); break }
                        continue
                    }
                    val q = SatNet.parseQr(raw) ?: continue
                    if (done.compareAndSet(false, true)) {
                        setResult(RESULT_OK, Intent().putExtra("host", q.hosts).putExtra("pin", q.pin).putExtra("hubId", q.hubId).putExtra("hubName", q.hubName))
                        finish()
                        break
                    }
                }
            }
            .addOnCompleteListener { img.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { scanner.close() } catch (_: Throwable) {}
        exec.shutdown()
    }
}
