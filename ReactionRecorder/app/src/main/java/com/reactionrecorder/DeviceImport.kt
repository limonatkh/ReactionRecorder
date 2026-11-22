package com.reactionrecorder

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File

/**
 * استيراد يدوي لملفات جهاز مساعد (عندما تفشل الشبكة): اختر من مجلد الجهاز
 * meta.json وملف الصوت و/أو الفيديو. نقرأ الوصف لنعرف الجلسة والجهاز، وننسخ الملفات للجلسة.
 * إن لم يكن للجهاز قياس ساعة تُضبط مزامنته بالصوت تلقائيًا عند فتح المونتاج.
 */
object DeviceImport {
    class Result(val ok: Boolean, val message: String, val sid: String = "")

    private fun nameOf(ctx: Context, u: Uri): String = try {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Throwable) { null } ?: (u.lastPathSegment ?: "file")

    /** expectSid: إن فُتح الاستيراد من بطاقة جلسة معيّنة نتحقق أن الملفات تخصها */
    fun import(ctx: Context, uris: List<Uri>, expectSid: String? = null): Result {
        if (uris.isEmpty()) return Result(false, tx("لم تختر ملفات", "No files chosen"))
        val named = uris.map { it to nameOf(ctx, it).lowercase() }
        val metas = named.filter { it.second.endsWith(".json") }
        if (metas.isEmpty()) return Result(false, tx("اختر ملف meta.json من مجلد الجهاز مع ملفاته", "Pick meta.json from the device folder together with its files"))
        if (metas.size > 1) return Result(false, tx("اختر ملفات جهاز واحد في كل مرة", "Pick the files of one device at a time"))
        val meta = try {
            JSONObject(ctx.contentResolver.openInputStream(metas[0].first)!!.use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (_: Throwable) { return Result(false, tx("ملف الوصف غير صالح", "Invalid meta file")) }
        val sid = SessionStore.safe(meta.optString("session"))
        val dev = meta.optString("dev")
        if (sid.isBlank() || dev.isBlank()) return Result(false, tx("ملف الوصف لا يحدد الجلسة", "The meta file has no session"))
        if (expectSid != null && sid != expectSid) return Result(false, tx("هذه الملفات تخص تسجيلًا آخر", "These files belong to another recording"))
        val s = SessionStore.load(ctx, sid) ?: return Result(false, tx("هذا التسجيل غير موجود على هذا الجوال", "This recording is not on this phone"))
        val dir = SessionStore.devDir(ctx, sid, dev)
        var copied = 0
        for ((u, n) in named) {
            val target = when {
                n.endsWith(".json") -> "meta.json"
                n.endsWith(".wav") -> "audio.wav"
                n.endsWith(".mp4") -> "video.mp4"
                else -> null
            } ?: continue
            val tmp = File(dir, "$target.part")
            try {
                ctx.contentResolver.openInputStream(u)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it, 256 * 1024) } }
                val f = File(dir, target)
                if (f.exists()) f.delete()
                tmp.renameTo(f)
                copied++
            } catch (_: Throwable) {
                tmp.delete()
            }
        }
        if (copied == 0) return Result(false, tx("تعذر نسخ الملفات", "Could not copy the files"))
        SessionStore.update(ctx, sid) { x ->
            val dv = x.devices[dev] ?: DevInfo(dev, meta.optString("name", "device"), meta.optString("role", "mic")).also { x.devices[dev] = it }
            dv.started = true
            dv.uploaded = true
            dv.stopped = true
        }
        val synced = s.devices[dev]?.samples?.isNotEmpty() == true
        return Result(true, tx("استُورد $copied ملف", "Imported $copied file(s)") + if (!synced) tx(" — ستُضبط المزامنة بالصوت عند فتح المونتاج", " — it will be synced by sound when you open the editor") else "", sid)
    }
}
