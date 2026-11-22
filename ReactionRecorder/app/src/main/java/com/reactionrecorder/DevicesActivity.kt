package com.reactionrecorder

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * شاشة الجهاز الرئيسي للأجهزة المساعدة: تشغيل الاستقبال، الأجهزة المتصلة، والجلسات (جلب الملفات، دمج الصوت، دمج الفيديو).
 */
class DevicesActivity : BaseActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var hubSwitch: Switch
    private lateinit var infoTv: TextView
    private lateinit var devicesBox: LinearLayout
    private lateinit var sessionsBox: LinearLayout
    private lateinit var jobTv: TextView
    private lateinit var jobBar: ProgressBar
    private lateinit var cancelBtn: Button
    private var devSig = ""
    private var sesSig = ""
    private val levelBars = HashMap<String, ProgressBar>()
    private var suppressSwitch = false
    @Volatile private var starting = false
    @Volatile private var sessionsCache: List<SessionInfo> = emptyList()
    @Volatile private var loadingSessions = false
    private var lastSessionsLoad = 0L
    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var qrView: android.widget.ImageView
    private var qrFor = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
        if (SatHub.hubWanted(this) && !SatHub.running) SatHub.start(this)
        ui.post(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(tick)
        bg.shutdown()
    }

    private fun txt(t: String, size: Float = 13f, color: String = "#F2F2F3") = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, dp(6), 0, dp(2))
    }

    private fun pill(b: Button, sel: Boolean = false) {
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 12f
        b.minWidth = 0; b.minimumWidth = 0
        b.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor(if (sel) "#FF3B30" else "#2A2D36"))
        }
    }

    private fun btn(label: String, sel: Boolean = false, run: () -> Unit) = Button(this).apply {
        text = label; pill(this, sel); setOnClickListener { run() }
    }

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        val sv = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0E0F11")); addView(col) }
        setContentView(sv)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sv) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }

        col.addView(txt("📡 " + tx("الأجهزة (مايكات / كاميرات مساعدة)", "Devices (remote mics / cameras)"), 20f))
        col.addView(txt(
            tx("ضع الجوالات على نفس الواي فاي (أو شغّل هوتسبوت على أحدها). على كل جوال آخر: انضمام لتسجيل ← امسح الباركود. كل جوال متصل يسجّل محليًا ويكمل حتى لو ابتعدت، وعند رجوع الاتصال تُرسل ملفاته تلقائيًا.",
                "Put the phones on the same Wi-Fi (or a hotspot). On each other phone: Join a recording ← scan the QR. Every connected phone records locally and keeps going if you walk away; its files are sent automatically when it reconnects."),
            12f, "#9A9CA3",
        ))

        hubSwitch = Switch(this).apply {
            text = tx("استقبال الأجهزة المساعدة", "Accept remote devices"); setTextColor(Color.WHITE); textSize = 15f
            isChecked = SatHub.running
            setOnCheckedChangeListener { _, on ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                SatHub.setHubWanted(this@DevicesActivity, on)
                if (on) {
                    val ctx = applicationContext
                    starting = true
                    infoTv.text = tx("تشغيل…", "Starting…")
                    Thread {
                        try { SatHub.start(ctx) } catch (e: Throwable) { SatHub.lastError = "Start failed: ${e.javaClass.simpleName}: ${e.message}" }
                        val ok = SatHub.running
                        if (!ok) SatHub.setHubWanted(ctx, false)
                        ui.post {
                            starting = false
                            if (!ok) {
                                AlertDialog.Builder(this@DevicesActivity).setTitle(tx("تعذر التشغيل", "Could not start"))
                                    .setMessage(SatHub.lastError.ifEmpty { tx("خطأ غير معروف", "Unknown error") } + tx("\n\nتأكد أن الواي فاي أو الهوتسبوت يعمل ثم حاول مجددًا.", "\n\nMake sure Wi-Fi or hotspot is on, then try again."))
                                    .setPositiveButton("OK", null).show()
                            }
                            refresh()
                        }
                    }.start()
                } else SatHub.stop()
            }
        }
        col.addView(hubSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        infoTv = txt("", 14f)
        col.addView(infoTv)
        col.addView(btn("🔑 " + tx("رمز جديد", "New PIN")) { SatHub.newPin(this) })
        qrView = android.widget.ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        col.addView(txt(tx("امسح هذا من الجوال الآخر (انضمام لتسجيل)", "Scan this from the other phone (Join a recording)"), 12f, "#9A9CA3"))
        col.addView(qrView, LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(4) })

        jobTv = txt("", 13f, "#FFD21F")
        jobBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        cancelBtn = btn(tx("إلغاء", "Cancel")) { startService(Intent(this, ReplayService::class.java).setAction(ReplayService.ACTION_CANCEL)) }
        col.addView(jobTv)
        col.addView(jobBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        col.addView(cancelBtn)

        col.addView(txt(tx("الأجهزة المتصلة", "Connected devices"), 16f))
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(devicesBox)

        col.addView(txt(tx("جلسات الرياكشن مع أجهزة", "Reaction sessions with devices"), 16f))
        sessionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sessionsBox)
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    private fun refresh() {
        if (isFinishing || isDestroyed) return
        if (!starting && hubSwitch.isChecked != SatHub.running) { suppressSwitch = true; hubSwitch.isChecked = SatHub.running; suppressSwitch = false }
        infoTv.text = if (starting) tx("تشغيل…", "Starting…") else if (SatHub.running) {
            val ips = SatNet.localIps()
            tx("العنوان: ", "Address: ") + (if (ips.isEmpty()) tx("لا واي فاي / هوتسبوت", "no Wi-Fi / hotspot found") else ips.joinToString("   ")) + "\nPIN: ${SatHub.pin}   •   port ${SatHub.port}"
        } else {
            if (SatHub.lastError.isNotEmpty()) "⚠ " + SatHub.lastError else tx("مطفأ", "Off")
        }

        // باركود الاقتران
        if (SatHub.running) {
            val payload = AppShare.pairQr()
            if (payload != qrFor) {
                qrFor = payload
                try {
                    val bm = com.google.zxing.qrcode.QRCodeWriter().encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
                    val px = IntArray(512 * 512)
                    for (y in 0 until 512) for (x in 0 until 512) px[y * 512 + x] = if (bm.get(x, y)) Color.BLACK else Color.WHITE
                    qrView.setImageBitmap(android.graphics.Bitmap.createBitmap(px, 512, 512, android.graphics.Bitmap.Config.ARGB_8888))
                } catch (_: Throwable) { qrView.setImageDrawable(null) }
            }
            qrView.visibility = android.view.View.VISIBLE
        } else qrView.visibility = android.view.View.GONE

        // مهمة الدمج الجارية
        if (ReplayState.running) {
            jobTv.text = ReplayState.stage
            jobBar.progress = (ReplayState.progress * 100).toInt()
            jobTv.visibility = android.view.View.VISIBLE; jobBar.visibility = android.view.View.VISIBLE; cancelBtn.visibility = android.view.View.VISIBLE
        } else {
            val e = ReplayState.error
            jobTv.text = when {
                e != null -> "⚠ $e"
                ReplayState.done -> "✓ " + tx("تم", "Done") + if (ReplayState.warnings.isNotEmpty()) "\n" + ReplayState.warnings.joinToString("\n") else ""
                else -> ""
            }
            jobTv.visibility = if (jobTv.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            jobBar.visibility = android.view.View.GONE; cancelBtn.visibility = android.view.View.GONE
        }

        // الأجهزة
        val links = SatHub.links.values.sortedBy { it.name }
        val dsig = links.joinToString("|") { "${it.id}:${it.connected}:${it.use}:${it.role}:${it.stripOn}" }
        if (dsig != devSig) {
            devSig = dsig
            devicesBox.removeAllViews()
            levelBars.clear()
            if (links.isEmpty()) devicesBox.addView(txt(tx("لم يتصل أي جهاز بعد", "No device has connected yet"), 12f, "#9A9CA3"))
            for (l in links) {
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val cb = CheckBox(this).apply {
                    isChecked = l.use
                    text = (if (l.connected) "🟢 " else "⚪ ") + l.name + if (l.role == "both") tx("  (صوت + فيديو)", "  (audio + video)") else tx("  (صوت)", "  (audio)")
                    setTextColor(Color.WHITE); textSize = 14f
                    setOnCheckedChangeListener { _, on -> SatHub.setUse(l.id, on); devSig = "" }
                }
                head.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                box.addView(head)
                val acts = LinearLayout(this)
                acts.addView(btn("🎛 " + tx("ستوديو", "Studio")) {
                    startActivity(Intent(this, StudioActivity::class.java).putExtra("dev", l.id))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
                acts.addView(btn(if (l.stripOn) "👁 " + tx("على الشاشة الرئيسية ✓", "On main screen ✓") else "👁 " + tx("اعرض على الشاشة الرئيسية", "Show on main screen"), l.stripOn) {
                    l.stripOn = !l.stripOn
                    SatHub.setPreview(l.id, l.stripOn, false, l.stripOn)
                    devSig = ""
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                box.addView(acts)
                val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
                levelBars[l.id] = pb
                box.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
                val st = TextView(this).apply { tag = "st_" + l.id; setTextColor(Color.parseColor("#9A9CA3")); textSize = 11f }
                box.addView(st)
                devicesBox.addView(box)
            }
        }
        for (l in links) {
            levelBars[l.id]?.progress = if (l.connected) (l.level * 100 / 32767).coerceIn(0, 100) else 0
            val st = devicesBox.findViewWithTag<TextView>("st_" + l.id)
            st?.text = if (l.connected) {
                "ping ${l.rttMs} ms" + (if (l.battery >= 0) " • 🔋${l.battery}%" else "") +
                    (if (l.freeMb >= 0) " • 💾${l.freeMb} MB" else "") + (if (l.recording) " • " + tx("يسجّل", "recording") else "") +
                    (if (l.transfer.isNotEmpty()) " • ${l.transfer}" else "")
            } else tx("غير متصل", "Not connected")
        }

        // الجلسات
        // قراءة الجلسات من الملفات في الخلفية (كل ثانيتين) كي لا تعلق الواجهة
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (!loadingSessions && nowMs - lastSessionsLoad > 2000L) {
            loadingSessions = true
            lastSessionsLoad = nowMs
            val ctx = applicationContext
            bg.execute {
                val list = try { SessionStore.list(ctx) } catch (_: Throwable) { emptyList() }
                sessionsCache = list
                loadingSessions = false
            }
        }
        val sessions = sessionsCache.filter { it.kind != "podcast" }
        val ssig = sessions.joinToString("|") { s ->
            s.id + s.ended + s.merged + s.mergedUri + s.overlayUri + s.devices.values.joinToString(",") { "${it.id}${it.uploaded}${it.stopped}" }
        } + ReplayState.running + SatHub.running
        if (ssig != sesSig) {
            sesSig = ssig
            sessionsBox.removeAllViews()
            if (sessions.isEmpty()) sessionsBox.addView(txt(tx("لا يوجد بعد", "None yet"), 12f, "#9A9CA3"))
            for (s in sessions) sessionsBox.addView(sessionView(s))
        }
    }

    private fun sessionView(s: SessionInfo): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#17181C")) }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        box.layoutParams = lp
        val dur = if (s.ended) ((s.endElapsedNs - s.startElapsedNs - s.pauses.sumOf { it[1] }) / 1_000_000_000L).coerceAtLeast(0L) else 0L
        box.addView(txt(
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(s.wallMs)) +
                (if (s.ended) "  •  ${dur / 60}:${String.format(Locale.US, "%02d", dur % 60)}" else "  •  " + tx("لم ينتهِ", "not finished")),
            14f,
        ))
        for (d in s.devices.values) {
            val state = when {
                !d.started -> tx("لم يشارك", "did not take part")
                d.uploaded -> tx("وصلت الملفات ✓", "files received ✓")
                else -> tx("بانتظار الملفات…", "waiting for files…")
            }
            box.addView(txt("• ${d.name} (${if (d.role == "both") tx("صوت+فيديو", "audio+video") else tx("صوت", "audio")}): $state", 12f, "#9A9CA3"))
        }
        val waiting = s.devices.values.any { it.started && !it.uploaded }
        val row1 = LinearLayout(this)
        val row2 = LinearLayout(this)
        if (waiting) row1.addView(btn("⬇ " + tx("اجلب الملفات", "Fetch files")) {
            if (!SatHub.running) toast(tx("شغّل الاستقبال أولًا", "Turn on “Accept remote devices” first")) else { SatHub.requestFiles(s.id); toast(tx("طُلبت من الأجهزة المتصلة", "Requested from connected devices")) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
        val ready = s.ended && Finalizer.readiness(this, s) == null
        if (ready) {
            row1.addView(btn(if (s.merged) "🔊 " + tx("أعد دمج الصوت", "Re-mix audio") else "🔊 " + tx("ادمج الصوت", "Mix audio"), true) { startJob(s.id, false) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
            if (s.hasVideoDevice()) row1.addView(btn("🎬 " + tx("أضف فيديو الجهاز", "Add device video"), true) { confirmOverlay(s.id) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (row1.childCount > 0) box.addView(row1)
        row2.addView(btn("⚙ " + tx("خيارات", "Options")) { showOptions(s.id) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
        if (s.mergedUri.isNotEmpty()) row2.addView(btn("▶ " + tx("المدموج", "Mixed")) { play(s.mergedUri) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
        if (s.overlayUri.isNotEmpty()) row2.addView(btn("▶ " + tx("النهائي", "Final")) { play(s.overlayUri) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
        row2.addView(btn("🗑") {
            AlertDialog.Builder(this).setTitle(tx("حذف ملفات العمل لهذه الجلسة؟", "Delete this session's working files?"))
                .setMessage(tx("يحذف ملفات الدمج المؤقتة (لا الفيديوهات في المعرض). لن تستطيع الدمج مجددًا.", "Deletes the temporary audio/video files used for merging (not the videos saved in your Gallery). You will not be able to merge it again."))
                .setPositiveButton(tx("حذف", "Delete")) { _, _ -> SessionStore.delete(this, s.id); sesSig = ""; lastSessionsLoad = 0L }
                .setNegativeButton(tx("إبقاء", "Keep"), null).show()
        })
        box.addView(row2)
        return box
    }

    private fun play(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Throwable) { toast(tx("لا يوجد مشغّل", "No player found")) }
    }

    private fun startJob(sid: String, overlay: Boolean) {
        if (ReplayState.running) { toast(tx("هناك دمج آخر قيد التنفيذ", "Another merge is running")); return }
        ContextCompat.startForegroundService(
            this,
            Intent(this, ReplayService::class.java).setAction(if (overlay) ReplayService.ACTION_OVERLAY else ReplayService.ACTION_MERGE).putExtra("session", sid),
        )
    }

    private fun confirmOverlay(sid: String) {
        AlertDialog.Builder(this).setTitle(tx("إضافة فيديو الجهاز", "Add device video"))
            .setMessage(tx("سيُشغَّل فيديوك مع فيديو الجوال الآخر ويُسجَّل الناتج. يأخذ نفس مدة الفيديو، والشاشة يجب أن تبقى مضاءة. متابعة؟", "The app will play your video together with the other phone's video (up to 2 phones) and record the final result. This takes as long as the video itself, and the screen must stay on. Continue?"))
            .setPositiveButton(tx("ابدأ", "Start")) { _, _ ->
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startJob(sid, true)
            }
            .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    /** إعدادات الدمج: مستوى الصوت، إزاحة التزامن اليدوية، استخدام الصوت/الفيديو لكل جهاز */
    private fun showOptions(sid: String) {
        val s = SessionStore.load(this, sid) ?: return
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val sv = ScrollView(this).apply { addView(col) }
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
        slider(tx("مايك الجوال الرئيسي", "Main phone microphone"), micG) { micG = it }
        slider(tx("صوت الفيديو / الشاشة", "Video / screen sound"), srcG) { srcG = it }

        class DevEdit(val id: String, var gain: Float, val nudge: EditText, val useA: CheckBox, val useV: CheckBox, var rot: Int, var pos: Int, var size: Float)
        val edits = ArrayList<DevEdit>()
        for (d in s.devices.values) {
            if (!d.started) continue
            col.addView(TextView(this).apply { text = d.name; textSize = 15f; setPadding(0, dp(16), 0, 0); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            val de = DevEdit(d.id, d.gain, EditText(this), CheckBox(this), CheckBox(this), d.rot, d.pos, d.size)
            slider(tx("مستوى الصوت", "Volume"), d.gain) { de.gain = it }
            col.addView(TextView(this).apply { text = tx("تعديل التزامن (ms): + يؤخّره، − يقدّمه", "Sync nudge (ms): + makes this device later, − earlier"); textSize = 12f })
            de.nudge.apply { setText(d.nudgeMs.toString()); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setSingleLine() }
            col.addView(de.nudge)
            de.useA.apply { text = tx("استعمل صوته", "Use its audio"); isChecked = d.useAudio }
            col.addView(de.useA)
            if (d.role == "both") {
                de.useV.apply { text = tx("استعمل فيديوه", "Use its video"); isChecked = d.useVideo }
                col.addView(de.useV)
                val rb = Button(this)
                fun rl() { rb.text = "↻ " + tx("تدوير فيديوه: ", "Rotate its video: ") + "${de.rot}°" }
                rl()
                rb.isAllCaps = false
                rb.setOnClickListener { de.rot = (de.rot + 90) % 360; rl() }
                col.addView(rb)
                val names = listOf(tx("تلقائي", "Auto"), tx("أعلى يسار", "Top-left"), tx("أعلى يمين", "Top-right"), tx("وسط يسار", "Middle-left"), tx("وسط يمين", "Middle-right"), tx("أسفل يسار", "Bottom-left"), tx("أسفل يمين", "Bottom-right"))
                val pb = Button(this)
                fun pl() { pb.text = "📍 " + tx("مكانه: ", "Position: ") + names[de.pos + 1] }
                pl()
                pb.isAllCaps = false
                pb.setOnClickListener { de.pos = if (de.pos >= 5) -1 else de.pos + 1; pl() }
                col.addView(pb)
                val sbn = Button(this)
                fun sl() { sbn.text = "↔ " + tx("الحجم: ", "Size: ") + String.format(Locale.US, "%.1fx", de.size) }
                sl()
                sbn.isAllCaps = false
                sbn.setOnClickListener { de.size = when { de.size < 0.8f -> 1f; de.size < 1.2f -> 1.5f; de.size < 1.8f -> 2f; else -> 0.7f }; sl() }
                col.addView(sbn)
            }
            edits += de
        }
        AlertDialog.Builder(this).setTitle(tx("خيارات الدمج", "Merge options")).setView(sv)
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
                sesSig = ""; lastSessionsLoad = 0L
            }
            .setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }
}
