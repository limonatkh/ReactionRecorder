package com.reactionrecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** عنصر في قائمة التشغيل: فيديو (ملف أو رابط https) أو صورة من الجوال */
data class PlayItem(val uri: String, val name: String, val image: Boolean)

/**
 * قائمة التشغيل المشتركة بين الواجهة والخدمة (نفس العملية). تُحفظ في SharedPreferences
 * فتبقى بين مرات تشغيل التطبيق.
 */
object Playlist {
    private const val P = "reaction_recorder_playlist"

    val items = ArrayList<PlayItem>()
    @Volatile var index = 0

    fun current(): PlayItem? = items.getOrNull(index)

    fun load(ctx: Context) {
        val s = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        items.clear()
        try {
            val arr = JSONArray(s.getString("pl", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(PlayItem(o.getString("u"), o.optString("n", "video"), o.optBoolean("i", false)))
            }
        } catch (_: Throwable) {
            items.clear()
        }
        index = s.getInt("idx", 0).coerceIn(0, maxOf(0, items.size - 1))
    }

    fun save(ctx: Context) {
        val arr = JSONArray()
        for (it in items) arr.put(JSONObject().put("u", it.uri).put("n", it.name).put("i", it.image))
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putString("pl", arr.toString()).putInt("idx", index).apply()
    }

    fun move(i: Int, delta: Int) {
        val j = i + delta
        if (i !in items.indices || j !in items.indices) return
        val it = items.removeAt(i)
        items.add(j, it)
        if (index == i) index = j else if (index == j) index = i
    }

    fun remove(i: Int) {
        if (i !in items.indices) return
        items.removeAt(i)
        if (index > i) index-- else if (index >= items.size) index = maxOf(0, items.size - 1)
    }
}
