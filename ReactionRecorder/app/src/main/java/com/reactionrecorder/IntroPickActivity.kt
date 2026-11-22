package com.reactionrecorder

import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * اختيار مقطع من الحلقة (للإنترو): بتختار الكاميرا، بتشغّل الفيديو بصوته، بتقدّم وبترجّع،
 * وبتحدد «بداية هون» و«نهاية هون». بيرجع ia/ib (ثواني على خط الجلسة) وicam.
 */
class IntroPickActivity : BaseActivity() {
    private lateinit var sid: String
    private lateinit var s: SessionInfo
    private lateinit var setup: PodSetup
    private val cams = ArrayList<Int>()
    private var cam = 0
    private var a = 0.0
    private var b = 10.0
    private var dur = 60.0
    /** فرق الوقت بين خط الجلسة وملف الفيديو/الصوت (ms) */
    private var vOff = 0.0
    private var aOff = 0.0
    private lateinit var video: VideoView
    private var audio: MediaPlayer? = null
    private lateinit var seek: SeekBar
    private lateinit var timeTv: TextView
    private lateinit var rangeTv: TextView
    private lateinit var camRow: LinearLayout
    private lateinit var playBtn: android.widget.Button
    private val ui = Handler(Looper.getMainLooper())
    private var playing = false
    /** لما بنشغّل المقطع: بنوقف عند النهاية */
    private var stopAt = -1.0
    private var t = 0.0
    /** الإنترو اليدوي: من لحظة t مين بيطلع (رقم الشخص، -1 = الكل). فاضي = تلقائي */
    private val marks = ArrayList<Pair<Double, Int>>()
    private lateinit var marksBox: LinearLayout
    private lateinit var bar: MarkBar

    private fun dp(v: Int) = UiKit.dp(this, v)
    private fun fmt(x: Double): String { val q = max(0.0, x); return "${q.toInt() / 60}:${String.format(Locale.US, "%04.1f", q % 60)}" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sid = intent.getStringExtra("session") ?: run { finish(); return }
        s = SessionStore.load(this, sid) ?: run { finish(); return }
        setup = PodcastAnalyzer.setupOf(s)
        for ((i, p) in setup.people.withIndex()) if (p.camDev.isNotEmpty() && File(SessionStore.devDir(this, sid, p.camDev), "video.mp4").exists()) cams.add(i)
        dur = PodcastAnalyzer.duration(s)
        a = intent.getDoubleExtra("a", 0.0).coerceIn(0.0, dur); b = intent.getDoubleExtra("b", a + 10.0).coerceIn(a, dur)
        if (b - a < 1.0) b = min(dur, a + 10.0)
        cam = intent.getIntExtra("cam", -1).takeIf { it in cams } ?: cams.firstOrNull() ?: 0
        t = a
        intent.getDoubleArrayExtra("mt")?.let { mt -> val mw = intent.getIntArrayExtra("mw") ?: IntArray(mt.size) { -1 }; for (i in mt.indices) marks.add(mt[i] to mw.getOrElse(i) { -1 }) }

        val col = UiKit.page(this, tx("اختار مقطع الإنترو", "Pick the intro clip"))
        camRow = LinearLayout(this)
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(camRow) })
        val box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        box.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(8) })
        timeTv = UiKit.text(this, "", 16f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        col.addView(timeTv)
        seek = SeekBar(this).apply {
            max = (dur * 10).toInt(); minimumHeight = dp(44)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { if (u) { t = p / 10.0; playFrom = t; playAt = android.os.SystemClock.uptimeMillis(); timeTv.text = tx("⏱ ${fmt(t)} من ${fmt(dur)}", "⏱ ${fmt(t)} of ${fmt(dur)}"); if (!playing) seekAll(t) } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { seekAll(t) }
            })
        }
        col.addView(seek)
        // التحكم: رجوع/تقديم + تشغيل
        val ctl = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun c(label: String, run: () -> Unit) = ctl.addView(UiKit.chip(this, label) { run() }.apply { textSize = 15f }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        c("−5") { jump(-5.0) }; c("−1") { jump(-1.0) }
        playBtn = UiKit.primary(this, "▶") { toggle() }
        ctl.addView(playBtn, LinearLayout.LayoutParams(0, dp(52), 1.3f).apply { setMargins(dp(3), 0, dp(3), 0) })
        c("+1") { jump(1.0) }; c("+5") { jump(5.0) }
        col.addView(ctl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        // البداية والنهاية
        rangeTv = UiKit.text(this, "", 15f, "#FFD21F", true).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(4)) }
        col.addView(rangeTv)
        val se = LinearLayout(this)
        se.addView(UiKit.chip(this, "⟵ " + tx("البداية هون", "Start here")) { a = t; if (b <= a + 0.5) b = min(dur, a + 10.0); showRange() }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) })
        se.addView(UiKit.chip(this, tx("النهاية هون", "End here") + " ⟶") { b = t; if (a >= b - 0.5) a = max(0.0, b - 10.0); showRange() }, LinearLayout.LayoutParams(0, dp(54), 1f))
        col.addView(se)
        val pr = UiKit.chip(this, "▶ " + tx("شغّل المقطع من أوله", "Play the clip from its start")) { t = a; seekAll(a); stopAt = b; if (!playing) toggle() }
        col.addView(pr, UiKit.full(this, pr, 8, 52))
        // ── مين بيطلع بالإنترو: تلقائي، أو أنت بتحدد من كل لحظة
        col.addView(UiKit.text(this, tx("مين بيطلع بالإنترو", "Who is on screen in the intro"), 16f, UiKit.TEXT, true).apply { setPadding(0, dp(18), 0, dp(4)) })
        col.addView(UiKit.text(this, tx("شغّل ووقّف عند أي لحظة، واكبس مين بدك يطلع من هون. بدون ولا علامة = تلقائي حسب القالب. الإنترو ما فيه أسماء.", "Play, stop at any moment and tap who should be on screen from there. No marks = automatic (template). The intro shows no names."), 12.5f, "#9A9CA3"))
        bar = MarkBar()
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)).apply { topMargin = dp(8) })
        val who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val people = cams.distinctBy { setup.people[it].camDev }
        val btns = people.map { i -> "🎥 " + setup.people[i].name to i } + (if (people.size >= 2) listOf("👥 " + tx("الكل مع بعض", "Everyone together") to -1) else emptyList())
        for (row in btns.chunked(2)) {
            val r = LinearLayout(this)
            for ((label, w) in row) r.addView(UiKit.chip(this, label + " " + tx("من هون", "from here")) { addMark(w) }.apply { maxLines = 2 }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            if (row.size == 1) r.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
            who.addView(r)
        }
        col.addView(who)
        marksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(marksBox)
        val ok = UiKit.primary(this, "✓ " + tx("خلّي هاد الإنترو", "Use this as the intro")) {
            val ms = marks.filter { it.first < b }.sortedBy { it.first }
            setResult(RESULT_OK, Intent().putExtra("a", a).putExtra("b", b).putExtra("cam", cam)
                .putExtra("mt", ms.map { it.first }.toDoubleArray()).putExtra("mw", ms.map { it.second }.toIntArray())); finish()
        }
        col.addView(ok, UiKit.full(this, ok, 16, 56))
        renderCams(); showRange(); renderMarks(); load()
    }

    private fun whoName(w: Int) = if (w < 0) tx("الكل مع بعض", "everyone") else setup.people.getOrNull(w)?.name ?: "?"

    private fun addMark(w: Int) {
        val x = t.coerceIn(a, b)
        // علامة قريبة (أقل من ٠٫٣ث) بتنستبدل
        marks.removeAll { abs(it.first - x) < 0.3 }
        marks.add(x to w); marks.sortBy { it.first }
        renderMarks()
        android.widget.Toast.makeText(this, tx("من ${fmt(x)}: ${whoName(w)}", "From ${fmt(x)}: ${whoName(w)}"), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun renderMarks() {
        if (!::marksBox.isInitialized) return
        marksBox.removeAllViews()
        val ms = marks.sortedBy { it.first }
        if (ms.isEmpty()) marksBox.addView(UiKit.text(this, tx("🤖 تلقائي: المونتاج حسب القالب", "🤖 Automatic: edited by the template"), 13.5f, "#9A9CA3").apply { setPadding(0, dp(8), 0, 0) })
        for ((i, m) in ms.withIndex()) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
            val from = if (i == 0) a else m.first
            val to = ms.getOrNull(i + 1)?.first ?: b
            r.addView(UiKit.text(this, "${fmt(from)} → ${fmt(to)} · ${whoName(m.second)}" + if (m.first < a || m.first >= b) tx(" (برّا المقطع)", " (outside the clip)") else "", 14f, if (m.first >= b) "#666C78" else UiKit.TEXT, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(UiKit.chip(this, "▶") { t = from; seekAll(from); stopAt = to; if (!playing) toggle() }, LinearLayout.LayoutParams(dp(54), dp(44)).apply { marginEnd = dp(4) })
            r.addView(UiKit.chip(this, "✕") { marks.remove(m); renderMarks() }, LinearLayout.LayoutParams(dp(54), dp(44)))
            marksBox.addView(r)
        }
        if (ms.isNotEmpty()) marksBox.addView(UiKit.chip(this, tx("ارجع للتلقائي (امسح الكل)", "Back to automatic (clear all)")) { marks.clear(); renderMarks() }.let { v -> v }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
        bar.invalidate()
    }

    /** شريط المقطع: لون لكل شخص، والمكان الحالي */
    private inner class MarkBar : android.view.View(this@IntroPickActivity) {
        private val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val cols = intArrayOf(0xFF0A84FF.toInt(), 0xFFFF9F0A.toInt(), 0xFF30D158.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt())
        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val len = max(0.1, b - a)
            fun x(tt: Double) = ((tt - a) / len * w).toFloat().coerceIn(0f, w)
            pnt.color = 0xFF2C2F36.toInt(); c.drawRoundRect(0f, 0f, w, h, h / 4, h / 4, pnt)
            val ms = marks.sortedBy { it.first }.filter { it.first < b }
            for ((i, m) in ms.withIndex()) {
                val x0 = if (i == 0) 0f else x(m.first); val x1 = ms.getOrNull(i + 1)?.let { x(it.first) } ?: w
                pnt.color = if (m.second < 0) 0xFFE5E5EA.toInt() else cols[m.second % cols.size]
                c.drawRect(x0, h * 0.2f, x1, h * 0.8f, pnt)
            }
            pnt.color = 0xFFFF3B30.toInt(); val px = x(t); c.drawRect(px - 2f, 0f, px + 2f, h, pnt)
        }
    }

    private fun renderCams() {
        camRow.removeAllViews()
        for (i in cams) camRow.addView(UiKit.chip(this, "🎥 " + setup.people[i].name, i == cam) { if (cam != i) { cam = i; renderCams(); load() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
    }

    private fun showTime() { timeTv.text = tx("⏱ ${fmt(t)} من ${fmt(dur)}", "⏱ ${fmt(t)} of ${fmt(dur)}"); seek.progress = (t * 10).toInt(); if (::bar.isInitialized) bar.invalidate() }
    private fun showRange() { if (::bar.isInitialized) renderMarks(); rangeTv.text = tx("الإنترو: من ${fmt(a)} لـ ${fmt(b)} · ${(b - a).toInt()} ث", "Intro: from ${fmt(a)} to ${fmt(b)} · ${(b - a).toInt()} s") }

    /** فيديو الكاميرا + صوت مايك صاحبها، والاثنين على نفس الوقت */
    private fun load() {
        pause()
        val p = setup.people.getOrNull(cam) ?: return
        val dv = s.devices[p.camDev] ?: return
        val dir = SessionStore.devDir(this, sid, p.camDev)
        try {
            val vm = JSONObject(File(dir, "meta.json").readText()).optJSONObject("video")
            if (vm != null) {
                val tm = Finalizer.SatVideoTime(s, dv, vm.optLong("startBoot"))
                vOff = tm.fileMs(0L)
            }
        } catch (_: Throwable) { vOff = 0.0 }
        video.setVideoURI(Uri.fromFile(File(dir, "video.mp4")))
        video.setOnPreparedListener { mp -> mp.setVolume(0f, 0f); seekAll(t) }
        video.setOnCompletionListener { pause() }
        audio?.release(); audio = null
        try {
            val mic = PodcastAnalyzer.micOf(this, s, p)
            val src = PodAudioSrc.open(this, s, mic)
            if (src != null) {
                aOff = src.nAt(0.0) / src.rd.rate * 1000.0
                src.close()
                audio = MediaPlayer().apply { setDataSource(File(SessionStore.devDir(this@IntroPickActivity, sid, mic), "audio.wav").absolutePath); prepare() }
            }
        } catch (_: Throwable) { audio = null }
        showTime()
    }

    private fun seekAll(x: Double) {
        try { video.seekTo((x * 1000 + vOff).toInt().coerceAtLeast(0)) } catch (_: Throwable) {}
        try { audio?.seekTo((x * 1000 + aOff).toInt().coerceAtLeast(0)) } catch (_: Throwable) {}
    }

    private fun jump(d: Double) { t = (t + d).coerceIn(0.0, dur); playFrom = t; playAt = android.os.SystemClock.uptimeMillis(); showTime(); seekAll(t) }

    /** الوقت بنحسبه من ساعتنا (من وين بلّشنا التشغيل)، والصورة والصوت بنلحقهم عليه */
    private var playFrom = 0.0
    private var playAt = 0L

    private fun toggle() { if (playing) pause() else play() }

    private fun play() {
        if (t >= dur - 0.2) t = 0.0
        playFrom = t; playAt = android.os.SystemClock.uptimeMillis()
        seekAll(t)
        video.start(); audio?.start(); playing = true; playBtn.text = "⏸"
        ui.post(tick)
    }

    private fun pause() {
        playing = false; stopAt = -1.0
        if (::playBtn.isInitialized) playBtn.text = "▶"
        try { video.pause() } catch (_: Throwable) {}
        try { audio?.pause() } catch (_: Throwable) {}
        ui.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            t = (playFrom + (android.os.SystemClock.uptimeMillis() - playAt) / 1000.0).coerceAtMost(dur)
            // الصورة والصوت بيمشوا مع الوقت: إذا بعدوا أكثر من ثلث ثانية بنرجّعهم
            try { val vt = (video.currentPosition - vOff) / 1000.0; if (abs(vt - t) > 0.35) video.seekTo((t * 1000 + vOff).toInt().coerceAtLeast(0)) } catch (_: Throwable) {}
            audio?.let { m -> val at = (m.currentPosition - aOff) / 1000.0; if (abs(at - t) > 0.35) m.seekTo((t * 1000 + aOff).toInt().coerceAtLeast(0)) }
            if (t >= dur - 0.05) { pause(); return }
            showTime()
            if (stopAt > 0 && t >= stopAt) { pause(); return }
            ui.postDelayed(this, 200)
        }
    }

    override fun onPause() { super.onPause(); pause() }
    override fun onDestroy() { super.onDestroy(); audio?.release(); audio = null }
}
