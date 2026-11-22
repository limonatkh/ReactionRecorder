package com.reactionrecorder

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.Executors

/** قناع الشخص: قيمة 0..255 لكل بكسل (الصف الأول = أعلى الصورة) */
class MaskData(val w: Int, val h: Int, val data: ByteArray)

/**
 * عزل الشخص تلقائيًا بنموذج Selfie Segmentation من Google ML Kit (يعمل على الجهاز بدون إنترنت).
 * الـ Compositor يرسل صورة السيلفي المصغّرة ([submit]) ويستلم آخر قناع جاهز ([takeMask]).
 */
class PersonSegmenter {
    private val exec = Executors.newSingleThreadExecutor()
    private val client: Segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask()
            .build(),
    )

    @Volatile var busy = false
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var maskReady = false
    @Volatile private var pending: MaskData? = null

    fun submit(bmp: Bitmap) {
        busy = true
        try {
            client.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(exec) { m ->
                    try {
                        val w = m.width
                        val h = m.height
                        val buf = m.buffer
                        buf.rewind()
                        val out = ByteArray(w * h)
                        for (i in 0 until w * h) {
                            val v = buf.getFloat()
                            out[i] = (v.coerceIn(0f, 1f) * 255f).toInt().toByte()
                        }
                        pending = MaskData(w, h, out)
                    } catch (t: Throwable) {
                        error = t.javaClass.simpleName
                    }
                    busy = false
                }
                .addOnFailureListener(exec) { e ->
                    error = e.javaClass.simpleName
                    busy = false
                }
        } catch (t: Throwable) {
            error = t.javaClass.simpleName
            busy = false
        }
    }

    /** آخر قناع جاهز (مرة واحدة) أو null */
    fun takeMask(): MaskData? {
        val m = pending
        pending = null
        return m
    }

    fun close() {
        try { client.close() } catch (_: Throwable) {}
        try { exec.shutdownNow() } catch (_: Throwable) {}
    }
}
