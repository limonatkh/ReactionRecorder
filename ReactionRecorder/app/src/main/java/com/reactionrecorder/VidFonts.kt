package com.reactionrecorder

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File

/** خطوط الكتابة على الفيديو (عنوان، كتابة، نصوص ثابتة). كلها مجانية (OFL)، وبتقدر تضيف خطك */
object VidFonts {
    class F(val id: String, val label: String, val asset: String?, val wght: Int = 0)

    val list = listOf(
        F("plex", tx("بلكس (خط التطبيق)", "Plex (app font)"), null),
        F("tajawal", tx("تجوّل عريض", "Tajawal heavy"), "fonts/Tajawal-ExtraBold.ttf"),
        F("changa", tx("تشانجا", "Changa"), "fonts/Changa.ttf", 700),
        F("messiri", tx("المسيري", "El Messiri"), "fonts/ElMessiri.ttf", 700),
        F("lalezar", tx("لاله‌زار (عريض جدًا)", "Lalezar (extra bold)"), "fonts/Lalezar-Regular.ttf"),
        F("amiri", tx("أميري (نسخ)", "Amiri (naskh)"), "fonts/Amiri-Bold.ttf"),
        F("ruqaa", tx("رقعة", "Ruqaa"), "fonts/ArefRuqaa-Bold.ttf"),
    )

    private val cache = HashMap<String, Typeface>()

    fun label(id: String): String = list.firstOrNull { it.id == id }?.label ?: if (id.startsWith("file:")) tx("خطك: ", "Your font: ") + File(id.removePrefix("file:")).nameWithoutExtension else id

    @Synchronized
    fun typeface(ctx: Context, id: String): Typeface {
        cache[id]?.let { return it }
        val t: Typeface = try {
            if (id.startsWith("file:")) Typeface.createFromFile(id.removePrefix("file:"))
            else {
                val f = list.firstOrNull { it.id == id }
                when {
                    f?.asset == null -> androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.plex_bold) ?: Typeface.DEFAULT_BOLD
                    f.wght > 0 -> Typeface.Builder(ctx.assets, f.asset).setFontVariationSettings("'wght' ${f.wght}").build()
                    else -> Typeface.createFromAsset(ctx.assets, f.asset)
                }
            }
        } catch (_: Throwable) { Typeface.DEFAULT_BOLD }
        cache[id] = t
        return t
    }

    /** خطك: بينسخ ملف TTF/OTF للتطبيق؛ بيرجع المعرّف أو null إذا الملف مش خط */
    fun import(ctx: Context, uri: Uri): String? = try {
        val dir = File(ctx.filesDir, "fonts").also { it.mkdirs() }
        val name = (uri.lastPathSegment ?: "font").substringAfterLast('/').replace(Regex("[^A-Za-z0-9_.-]"), "_").let { if (it.endsWith(".ttf", true) || it.endsWith(".otf", true)) it else "$it.ttf" }
        val f = File(dir, System.currentTimeMillis().toString(36) + "_" + name)
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
        Typeface.createFromFile(f)
        "file:" + f.absolutePath
    } catch (_: Throwable) { null }

    /** صورة (شعار أو خلفية): بينسخها للتطبيق عشان تضل موجودة؛ بيرجع المسار */
    fun importImage(ctx: Context, uri: Uri, kind: String): String? = try {
        val dir = File(ctx.filesDir, "images").also { it.mkdirs() }
        val f = File(dir, kind + "_" + System.currentTimeMillis().toString(36) + ".png")
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: throw IllegalStateException()
        // نصغّرها إذا كبيرة كثير
        val k = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        val b2 = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        f.outputStream().use { b2.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        f.absolutePath
    } catch (_: Throwable) { null }
}
