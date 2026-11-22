package com.reactionrecorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import kotlin.math.max

/** تحميل الصور من الجوال بحجم معقول للـ GPU (مع تصحيح اتجاه EXIF) */
object ImageLoader {
    fun load(ctx: Context, uriStr: String, maxSide: Int = 2048): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val rot = try {
                ctx.contentResolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
            } catch (_: Throwable) { 0 }
            if (rot != 0) {
                val m = Matrix(); m.postRotate(rot.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val s = maxSide.toFloat() / max(bmp.width, bmp.height)
            if (s < 1f) {
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun exifRotation(s: java.io.InputStream): Int {
        val e = android.media.ExifInterface(s)
        return when (e.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }
}

/** يرسم شاشة انتظار نصية (خلفية متدرجة + نص) عندما لا يختار المستخدم صورة أو فيديو */
object StandbyArt {
    fun render(text: String, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#1B1F2B"), Color.parseColor("#0B0C10"), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        val lines = text.ifBlank { "Be right back" }.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        var size = w * 0.11f
        p.textSize = size
        val maxLine = lines.maxOf { p.measureText(it) }
        if (maxLine > w * 0.86f) { size *= w * 0.86f / maxLine; p.textSize = size }
        val lineH = size * 1.25f
        var y = h / 2f - (lines.size - 1) * lineH / 2f - (p.descent() + p.ascent()) / 2f
        for (l in lines) { c.drawText(l, w / 2f, y, p); y += lineH }
        return bmp
    }
}
