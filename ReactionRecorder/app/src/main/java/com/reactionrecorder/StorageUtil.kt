package com.reactionrecorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StorageUtil {
    const val FOLDER = "Movies/ReactionRecorder"

    fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (_: Throwable) { 0L }

    /** تقدير مدة التسجيل المتاحة بالثواني بحسب البيتريت (فيديو + صوت 128kbps) مع هامش أمان 10% */
    fun estimateSeconds(videoBitrate: Int, hasAudio: Boolean): Long {
        val bps = videoBitrate + if (hasAudio) 128_000 else 0
        if (bps <= 0) return 0
        return ((freeBytes() * 0.9) * 8 / bps).toLong()
    }

    fun formatDuration(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60
        return if (h > 99) "99h+" else if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun formatBytes(b: Long): String {
        val gb = b / 1_073_741_824.0
        return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb) else String.format(Locale.US, "%d MB", b / 1_048_576)
    }

    fun newName(): String =
        "Reaction_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".mp4"

    /** ينشئ عنصرًا "معلّقًا" في MediaStore داخل Movies/ReactionRecorder (لا يحتاج صلاحية تخزين) */
    fun createPending(ctx: Context, name: String): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
    }

    /** يجعل الفيديو ظاهرًا في المعرض */
    fun publish(ctx: Context, uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, v, null, null)
    }

    fun delete(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.delete(uri, null, null) > 0
    } catch (_: Throwable) { false }
}
