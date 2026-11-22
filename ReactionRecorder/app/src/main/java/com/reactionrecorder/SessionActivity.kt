package com.reactionrecorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تفاصيل تسجيل واحد: ملفات كل جهاز (وصلت/ناقصة)، جلبها أو استيرادها يدويًا، المزامنة بالصوت،
 * وتوحيد الوقت إذا توقفت الأجهزة بأوقات مختلفة. ثم الانتقال للمونتاج (الحلقة/الريلز) أو الدمج (رياكشن).
 */
class SessionActivity : BaseActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sid: String
    private lateinit var body: LinearLayout
    private lateinit var busyTv: TextView
    private lateinit var busyBar: ProgressBar
    @Volatile private var busy = false
    private var sig = ""
    @Volatile private var cov: List<PodSync.Cov>? = null
    @Volatile private var covFor = ""

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        work(tx("استيراد الملفات…", "Importing files…")) {
            val r = DeviceImport.import(applicationContext, uris, sid)
            ui.post { msg(r.message) }
        }
    }

    private fun dp(v: Int) = UiKit.dp(this, v)
    private fun msg(s: String) = AlertDialog.Builder(this).setMessage(s).setPositiveButton(tx("تم", "OK"), null).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sid = intent.getStringExtra("session") ?: run { finish(); return }
        val s = SessionStore.load(this, sid) ?: run { finish(); return }
        val col = UiKit.page(this, (if (s.kind == "podcast") (if (s.extra.contains("\"vlog\":true")) "🎥 " else "🎙 ") else "🎞 ") + SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US).format(Date(s.wallMs)))
        busyTv = UiKit.text(this, "", 13f, UiKit.ORANGE)
        busyBar = UiKit.level(this)
        col.addView(busyTv); col.addView(busyBar, UiKit.full(this, busyBar, 2, 6))
        busyTv.visibility = View.GONE; busyBar.visibility = View.GONE
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
    }

    override fun onResume() { super.onResume(); sig = ""; ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    /** عمل في الخلفية مع شريط تقدم (واحد في كل مرة) */
    private fun work(label: String, job: ((Float) -> Unit) -> Unit) {
        if (busy) return
        busy = true
        busyTv.text = label; busyTv.visibility = View.VISIBLE; busyBar.visibility = View.VISIBLE; busyBar.progress = 0
        Thread {
            try { job { f -> ui.post { busyBar.progress = (f * 100).toInt() } } } catch (e: Throwable) { ui.post { msg("⚠ " + (e.message ?: e.javaClass.simpleName)) } }
            ui.post { busy = false; busyTv.visibility = View.GONE; busyBar.visibility = View.GONE; sig = ""; covFor = "" }
        }.start()
    }

    private fun refresh() {
        val s = SessionStore.load(this, sid) ?: return
        val key = s.devices.values.joinToString("|") { "${it.id}${it.uploaded}${it.samples.size}${it.nudgeMs}" + SatHub.links[it.id]?.let { l -> "${l.connected}${l.transfer}" } } + s.ended + s.startElapsedNs + s.endElapsedNs + s.mergedUri + s.overlayUri + ReplayState.running + ReplayState.done + busy
        // التغطية تُحسب في الخلفية عند تغيّر الملفات
        if (key != covFor && !busy && s.ended) {
            covFor = key
            val c = applicationContext
            Thread { val r = try { PodSync.coverage(c, s) } catch (_: Throwable) { emptyList() }; cov = r; ui.post { sig = "" } }.start()
        }
        val k2 = key + (cov?.size ?: -1)
        if (k2 == sig) { if (ReplayState.running && ReplayState.sid == sid) { busyTv.text = ReplayState.stage; busyTv.visibility = View.VISIBLE; busyBar.visibility = View.VISIBLE; busyBar.progress = (ReplayState.progress * 100).toInt() }; return }
        sig = k2
        build(s)
    }

    private fun fmt(x: Double): String { val v = kotlin.math.abs(x).toLong(); return (if (x < 0) "-" else "") + String.format(Locale.US, "%d:%02d", v / 60, v % 60) }

    private fun build(s: SessionInfo) {
        body.removeAllViews()
        val pod = s.kind == "podcast"
        val dur = PodcastAnalyzer.duration(s)
        val cv = cov

        // ── الملفات
        val files = UiKit.card(this, "📦 " + tx("ملفات الأجهزة", "Device files"), body)
        if (s.devices.isEmpty()) files.addView(UiKit.text(this, tx("لا أجهزة في هذا التسجيل", "No devices in this recording"), 12.5f, UiKit.SUB))
        for (d in s.devices.values) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val icon = if (d.role == "both") "🎥" else "🎤"
            line.addView(UiKit.text(this, "$icon ${d.name}", 14f, UiKit.TEXT, true))
            val lk = SatHub.links[d.id]
            val tr = lk?.transfer ?: ""
            val st = when {
                !d.started -> "— " + tx("لم يشارك", "Did not take part")
                d.uploaded -> "✓ " + tx("وصلت الملفات", "Files received")
                tr.isNotEmpty() -> "⬇ " + tx("عم يستقبل: ", "Receiving: ") + tr
                lk?.connected == true -> "⏳ " + tx("الجهاز متصل — اضغط «اجلب الآن» إذا ما بلّش الإرسال", "Device connected — tap “Fetch now” if sending hasn't started")
                else -> "⏳ " + tx("الجهاز مش متصل هلأ — الملفات بتوصل لحالها أول ما يتصل (افتح التطبيق عليه)", "Device not connected — files arrive as soon as it connects (open the app on it)")
            }
            line.addView(UiKit.text(this, st, 12.5f, when { d.uploaded -> UiKit.GREEN; tr.isNotEmpty() -> UiKit.BLUE; else -> UiKit.ORANGE }))
            if (!d.uploaded && tr.isNotEmpty()) {
                val pct = Regex("(\\d+)%").find(tr)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                line.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = pct }, UiKit.full(this, line, 4))
            }
            // وصل بس مش مربوط بشخص ولا كاميرا عامة؟
            if (pod && d.uploaded && d.started) {
                val su = PodcastAnalyzer.setupOf(s)
                val used = su.people.any { it.camDev == d.id || it.micDev == d.id } || d.id in su.wides
                if (!used) line.addView(UiKit.text(this, "⚠ " + tx("وصل بس مش مستعمل بالمونتاج — بالمونتاج: «المصادر والأسماء» ← حدد شو هاد الجهاز", "Received but not used in the edit — in the editor: “Sources & names” → say what this device is"), 12f, UiKit.ORANGE))
            }
            val c = cv?.firstOrNull { it.dev.id == d.id }?.range
            if (d.uploaded) line.addView(UiKit.text(this, "📦 " + PodStudio.devSizes(this, sid, d.id), 12f, UiKit.SUB))
            if (c != null && d.uploaded) line.addView(UiKit.text(this, tx("سجّل من ${fmt(c[0])} إلى ${fmt(c[1])} (طول الجلسة ${fmt(dur)})", "Recorded from ${fmt(c[0])} to ${fmt(c[1])} (session length ${fmt(dur)})"), 12f, UiKit.SUB))
            if (d.uploaded) line.addView(UiKit.text(this, if (d.samples.isEmpty()) "⚠ " + tx("بلا مزامنة بعد — تُضبط بالصوت عند فتح المونتاج", "Not synced yet — synced by sound when the editor opens")
                else if (d.samples.size == 1 && d.samples[0].rtt == 0L) "🔊 " + tx("مضبوط بالصوت", "Synced by sound") else "⏱ " + tx("مضبوط بقياس الساعة", "Synced by clock"), 11.5f, UiKit.SUB))
            files.addView(line)
        }
        val waiting = s.devices.values.any { it.started && !it.uploaded }
        val r1 = LinearLayout(this)
        if (waiting) r1.addView(UiKit.chip(this, "⬇ " + tx("اجلب الآن", "Fetch now")) {
            if (!SatHub.running) Thread { try { SatHub.start(applicationContext) } catch (_: Throwable) {} ; SatHub.requestFiles(sid) }.start() else SatHub.requestFiles(sid)
            android.widget.Toast.makeText(this, tx("طُلبت من الأجهزة المتصلة", "Requested from connected devices"), android.widget.Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        r1.addView(UiKit.chip(this, "📥 " + tx("استيراد من ملفات", "Import from files")) {
            AlertDialog.Builder(this).setMessage(tx("اختر كل ملفات الجهاز من مجلده (meta.json والصوت/الفيديو). على الجوال الآخر: التسجيلات على هذا الجوال ← احفظ في التنزيلات.", "Pick all of the device's files from its folder (meta.json and audio/video). On the other phone: Recordings on this phone ← Save to Downloads."))
                .setPositiveButton(tx("اختر", "Pick")) { _, _ -> pick.launch(arrayOf("*/*")) }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        files.addView(r1, UiKit.full(this, r1, 8))
        if (s.devices.values.count { it.uploaded } >= 2) files.addView(UiKit.chip(this, "🔊 " + tx("أعد المزامنة بالصوت", "Re-sync by sound")) { soundSync(s) }, UiKit.full(this, files, 4))

        // ── توحيد الوقت
        val covs = cv?.mapNotNull { it.range } ?: emptyList()
        if (pod && s.ended && covs.isNotEmpty() && s.devices.size >= 2 && PodSync.mismatch(covs, dur)) {
            val c = UiKit.card(this, "⏱ " + tx("الأجهزة توقفت بأوقات مختلفة", "Devices stopped at different times"), body)
            val u = PodSync.span(covs, "union", dur)
            val m = PodSync.span(covs, "common", dur)
            c.addView(UiKit.text(this, tx("اختر ما يدخل في المونتاج. الأجزاء التي فيها صوت بلا صورة (أو العكس) تُعالج حسب خيار \"الفراغ\" في المونتاج: كاميرا أخرى، آخر صورة، أو بطاقة بالاسم.",
                "Choose what goes into the edit. Parts with sound but no picture (or the reverse) are handled by the editor's \"gaps\" option: another camera, last frame, or a name card."), 12.5f, UiKit.SUB))
            c.addView(UiKit.chip(this, "⇔ " + tx("كل التسجيل (${fmt(u[1] - u[0])})", "Everything (${fmt(u[1] - u[0])})")) { applySpan(u, s) }, UiKit.full(this, c, 8))
            c.addView(UiKit.chip(this, "▭ " + tx("الجزء المشترك فقط (${fmt(m[1] - m[0])})", "Common part only (${fmt(m[1] - m[0])})")) { applySpan(m, s) }, UiKit.full(this, c, 6))
            c.addView(UiKit.text(this, tx("الحالي: حسب الجوال الرئيسي (${fmt(dur)}).", "Current: by the main phone (${fmt(dur)})."), 11.5f, UiKit.SUB))
        }

        // ── المونتاج / الدمج
        if (pod) {
            val st = PodcastAnalyzer.setupOf(s)
            val warn = ArrayList<String>()
            for (p in st.people) {
                val mic = PodcastAnalyzer.micOf(this, s, p)
                val hasA = mic.isNotEmpty() && java.io.File(SessionStore.devDir(this, sid, mic), "audio.wav").exists()
                val hasV = PodcastAnalyzer.hasVideo(this, s, p.camDev)
                if (!hasA) warn += "🎤 " + tx("لا يوجد صوت لـ ${p.name} — التبديل التلقائي على كلامه غير ممكن", "No audio for ${p.name} — auto-switching on their speech isn't possible")
                else if (mic != p.micDev) warn += "🎤 " + tx("${p.name}: يُستعمل صوت كاميرته لأن مايكه لم يصل", "${p.name}: using the camera's audio because the mic didn't arrive")
                if (!hasV) warn += "🎥 " + tx("لا توجد كاميرا لـ ${p.name} — عندما يتكلم تُعرض كاميرا متاحة", "No camera for ${p.name} — another available camera is shown when they speak")
            }
            val e = UiKit.card(this, "✂️ " + tx("المونتاج", "Editing"), body)
            for (w in warn) e.addView(UiKit.text(this, w, 12f, UiKit.ORANGE).apply { setPadding(0, 0, 0, dp(4)) })
            if (!s.ended) e.addView(UiKit.text(this, tx("التسجيل لم ينتهِ بعد.", "The recording hasn't finished yet."), 12.5f, UiKit.SUB))
            else {
                val ep = UiKit.primary(this, "🎬  " + tx("الحلقة الكاملة", "Full episode")) { guarded(waiting) { startActivity(Intent(this, PodcastEditActivity::class.java).putExtra("session", sid).putExtra("project", "episode")) } }
                e.addView(ep, UiKit.full(this, ep, 8, 52))
                val rl = UiKit.primary(this, "📱  " + tx("الريلز", "Reels"), UiKit.BLUE) { guarded(waiting) { startActivity(Intent(this, PodcastShortsActivity::class.java).putExtra("session", sid)) } }
                e.addView(rl, UiKit.full(this, rl, 8, 52))
            }
        } else {
            val e = UiKit.card(this, "🔊 " + tx("الدمج", "Merging"), body)
            val ready = s.ended && Finalizer.readiness(this, s) == null
            if (!ready) e.addView(UiKit.text(this, Finalizer.readiness(this, s) ?: "", 12.5f, UiKit.SUB))
            else {
                e.addView(UiKit.chip(this, if (s.merged) "🔊 " + tx("أعد دمج الصوت", "Re-mix audio") else "🔊 " + tx("ادمج الصوت", "Mix audio"), true) { startReplay(false) }, UiKit.full(this, e, 6))
                if (s.hasVideoDevice()) e.addView(UiKit.chip(this, "🎬 " + tx("أضف فيديو الجهاز", "Add the device video"), true) { confirmOverlay() }, UiKit.full(this, e, 6))
                e.addView(UiKit.chip(this, "⚙ " + tx("خيارات الدمج", "Merge options")) { mergeOptions(s) }, UiKit.full(this, e, 6))
            }
            if (s.mergedUri.isNotEmpty()) e.addView(UiKit.chip(this, "▶ " + tx("الفيديو بالصوت المدموج", "Video with mixed audio")) { play(s.mergedUri) }, UiKit.full(this, e, 6))
            if (s.overlayUri.isNotEmpty()) e.addView(UiKit.chip(this, "▶ " + tx("الفيديو النهائي", "Final video")) { play(s.overlayUri) }, UiKit.full(this, e, 6))
            if (ReplayState.sid == sid && !ReplayState.running) {
                ReplayState.error?.let { e.addView(UiKit.text(this, "⚠ $it", 12.5f, UiKit.RED)) }
                if (ReplayState.done && ReplayState.error == null) e.addView(UiKit.text(this, "✓ " + tx("تم", "Done") + ReplayState.warnings.joinToString("") { "\n• $it" }, 12.5f, UiKit.GREEN))
            }
        }

        // ── حذف
        val del = UiKit.chip(this, "🗑 " + tx("حذف ملفات العمل", "Delete working files")) {
            AlertDialog.Builder(this).setTitle(tx("حذف ملفات العمل لهذا التسجيل؟", "Delete this recording's working files?"))
                .setMessage(tx("الفيديوهات المصدّرة في المعرض تبقى. لن تستطيع المونتاج أو الدمج مجددًا.", "Videos already exported to the Gallery stay. You won't be able to edit or merge again."))
                .setPositiveButton(tx("حذف", "Delete")) { _, _ -> SessionStore.delete(this, sid); finish() }
                .setNegativeButton(tx("إبقاء", "Keep"), null).show()
        }
        body.addView(del, UiKit.full(this, del, 16))
    }

    private fun guarded(waiting: Boolean, go: () -> Unit) {
        if (waiting) AlertDialog.Builder(this).setMessage(tx("بعض الملفات لم تصل بعد، فستغيب كاميراتها/مايكاتها عن المونتاج. متابعة؟", "Some files haven't arrived; their cameras/mics will be missing from the edit. Continue?"))
            .setPositiveButton(tx("متابعة", "Continue")) { _, _ -> go() }.setNegativeButton(tx("انتظار", "Wait"), null).show()
        else go()
    }

    private fun applySpan(r: DoubleArray, s: SessionInfo) {
        AlertDialog.Builder(this).setTitle(tx("تغيير مدى التسجيل", "Change the recording range"))
            .setMessage(tx("سيُعاد حساب التحليل، وتُزاح التعديلات الموجودة لتبقى في مكانها الصحيح. النسخ السابقة للمشاريع تُحذف.", "Analysis is recomputed and existing edits are shifted to stay in place. Older project versions are removed."))
            .setPositiveButton(tx("طبّق", "Apply")) { _, _ ->
                work(tx("تطبيق…", "Applying…")) { PodSync.applySpan(applicationContext, sid, r[0], r[1]) }
            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    private fun soundSync(s: SessionInfo) {
        val devs = s.devices.values.filter { it.uploaded }.toList()
        val names = devs.map { it.name }.toTypedArray()
        val sel = BooleanArray(devs.size) { devs[it].samples.isEmpty() }
        AlertDialog.Builder(this).setTitle(tx("أي الأجهزة تُضبط بالصوت؟", "Which devices to sync by sound?"))
            .setMultiChoiceItems(names, sel) { _, i, on -> sel[i] = on }
            .setPositiveButton(tx("ابدأ", "Start")) { _, _ ->
                val force = devs.filterIndexed { i, _ -> sel[i] }.map { it.id }.toSet()
                if (force.isEmpty()) return@setPositiveButton
                work(tx("مزامنة بالصوت…", "Syncing by sound…")) { p ->
                    val r = PodSync.syncBySound(applicationContext, sid, force, p)
                    try { java.io.File(SessionStore.dir(applicationContext, sid), "pod_analysis.json").delete() } catch (_: Throwable) {}
                    ui.post { msg(r.joinToString("\n") { (if (it.ok) "✓ " else "⚠ ") + it.name + ": " + it.note + if (it.ok) String.format(Locale.US, " (%+.2f s)", it.offsetSec) else "" }.ifEmpty { tx("لا شيء", "Nothing") }) }
                }
            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    // ───── دمج الرياكشن ─────

    private fun play(uri: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Throwable) {}
    }

    private fun startReplay(overlay: Boolean) {
        if (ReplayState.running) { msg(tx("هناك دمج آخر قيد التنفيذ", "Another merge is running")); return }
        ContextCompat.startForegroundService(this, Intent(this, ReplayService::class.java).setAction(if (overlay) ReplayService.ACTION_OVERLAY else ReplayService.ACTION_MERGE).putExtra("session", sid))
    }

    private fun confirmOverlay() {
        AlertDialog.Builder(this).setTitle(tx("إضافة فيديو الجهاز", "Add the device video"))
            .setMessage(tx("سيُشغَّل فيديوك مع فيديو الجوال الآخر (حتى جوالين) ويُسجَّل الناتج. يأخذ نفس مدة الفيديو، والشاشة يجب أن تبقى مضاءة.", "Your video plays together with the other phone's video (up to 2 phones) and the result is recorded. It takes as long as the video, and the screen must stay on."))
            .setPositiveButton(tx("ابدأ", "Start")) { _, _ -> window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); startReplay(true) }
            .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    private fun mergeOptions(s0: SessionInfo) {
        val s = SessionStore.load(this, sid) ?: s0
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        var micG = s.micGain
        var srcG = s.srcGain
        fun slider(title: String, init: Float, onChange: (Float) -> Unit) {
            val tv = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, 0) }
            fun upd(v: Float) { tv.text = "$title: ${String.format(Locale.US, "%.1f", v)}x" }
            upd(init)
            col.addView(tv)
            col.addView(SeekBar(this).apply {
                max = 300; progress = (init * 100).toInt().coerceIn(0, 300)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { val v = p / 100f; upd(v); onChange(v) }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }
        slider(tx("مايك هذا الجوال", "This phone's mic"), micG) { micG = it }
        slider(tx("صوت الفيديو / الشاشة", "Video / screen sound"), srcG) { srcG = it }
        class DevEdit(val id: String, var gain: Float, val nudge: EditText, val useA: CheckBox, val useV: CheckBox, var rot: Int, var pos: Int, var size: Float)
        val edits = ArrayList<DevEdit>()
        for (d in s.devices.values) {
            if (!d.started) continue
            col.addView(TextView(this).apply { text = d.name; textSize = 15f; setPadding(0, dp(16), 0, 0); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            val de = DevEdit(d.id, d.gain, EditText(this), CheckBox(this), CheckBox(this), d.rot, d.pos, d.size)
            slider(tx("مستوى الصوت", "Volume"), d.gain) { de.gain = it }
            col.addView(TextView(this).apply { text = tx("تعديل التزامن (ms): + يؤخّر هذا الجهاز، − يقدّمه", "Sync nudge (ms): + makes this device later, − earlier"); textSize = 12f })
            de.nudge.apply { setText(d.nudgeMs.toString()); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setSingleLine() }
            col.addView(de.nudge)
            de.useA.apply { text = tx("استعمل صوته", "Use its audio"); isChecked = d.useAudio }
            col.addView(de.useA)
            if (d.role == "both") {
                de.useV.apply { text = tx("استعمل فيديوه", "Use its video"); isChecked = d.useVideo }
                col.addView(de.useV)
                val rb = android.widget.Button(this)
                fun rl() { rb.text = "↻ " + tx("تدوير فيديوه: ${de.rot}°", "Rotate its video: ${de.rot}°") }
                rl(); rb.isAllCaps = false
                rb.setOnClickListener { de.rot = (de.rot + 90) % 360; rl() }
                col.addView(rb)
                val names = listOf(tx("تلقائي", "Auto"), tx("أعلى يسار", "Top-left"), tx("أعلى يمين", "Top-right"), tx("وسط يسار", "Middle-left"), tx("وسط يمين", "Middle-right"), tx("أسفل يسار", "Bottom-left"), tx("أسفل يمين", "Bottom-right"))
                val pb = android.widget.Button(this)
                fun pl() { pb.text = "📍 " + tx("مكانه: ", "Position: ") + names[de.pos + 1] }
                pl(); pb.isAllCaps = false
                pb.setOnClickListener { de.pos = if (de.pos >= 5) -1 else de.pos + 1; pl() }
                col.addView(pb)
                val sb = android.widget.Button(this)
                fun sl() { sb.text = "↔ " + tx("الحجم: ", "Size: ") + String.format(Locale.US, "%.1fx", de.size) }
                sl(); sb.isAllCaps = false
                sb.setOnClickListener { de.size = when { de.size < 0.8f -> 1f; de.size < 1.2f -> 1.5f; de.size < 1.8f -> 2f; else -> 0.7f }; sl() }
                col.addView(sb)
            }
            edits += de
        }
        AlertDialog.Builder(this).setTitle(tx("خيارات الدمج", "Merge options")).setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton(tx("حفظ", "Save")) { _, _ ->
                SessionStore.update(this, sid) { x ->
                    x.micGain = micG; x.srcGain = srcG
                    for (e in edits) {
                        val dv = x.devices[e.id] ?: continue
                        dv.gain = e.gain
                        dv.nudgeMs = e.nudge.text.toString().toIntOrNull()?.coerceIn(-5000, 5000) ?: 0
                        dv.useAudio = e.useA.isChecked
                        if (dv.role == "both") { dv.useVideo = e.useV.isChecked; dv.rot = e.rot; dv.pos = e.pos; dv.size = e.size }
                    }
                }
                sig = ""
            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }
}
