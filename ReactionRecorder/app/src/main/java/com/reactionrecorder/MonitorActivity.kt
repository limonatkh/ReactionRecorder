package com.reactionrecorder

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.util.Locale

/**
 * شاشة التسجيل (بودكاست): المعاينة الكبيرة للكاميرا اللي بتختارها، صور صغيرة لباقي الكاميرات،
 * المؤقت وحالة كل جهاز دائمًا (الاتصال والتسجيل كلٌّ لحاله)، وأزرار كبيرة: لحظة مهمة، غلطة/إعادة، إنهاء (ضغط مطوّل + تأكيد).
 * لمسة على صورة = معاينة بس؛ المونتاج تلقائي على اللي بيحكي. قفل اللمس بيحمي من الضغطات بالغلط.
 */
class MonitorActivity : BaseActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var timeTv: TextView
    private lateinit var big: ImageView
    private lateinit var bigHint: TextView
    private lateinit var viewTag: TextView
    private lateinit var editTag: TextView
    private lateinit var thumbs: LinearLayout
    private lateinit var devRows: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var endBtn: HoldButton
    private lateinit var startBtn: Button
    private lateinit var lockOverlay: FrameLayout
    private var sel = ""
    private var bigSeen = 0L
    private var sig = ""
    private val speakers = LiveSpeakers()
    private var editMain = -1
    private var setup: PodSetup = PodSetup()
    private var ended = false

    private class Thumb(val img: ImageView, val frame: FrameLayout) { var seen = 0L }
    private val thumbViews = HashMap<String, Thumb>()
    private class Row(val conn: TextView, val rec: TextView)
    private val rows = HashMap<String, Row>()

    private fun dp(v: Int) = UiKit.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private var twoPane: TwoPane? = null
    private var bigFrame: FrameLayout? = null

    private var mainCol: LinearLayout? = null
    private var bottomBarV: LinearLayout? = null

    /**
     * بالعرض: يسار = المؤقت والمعاينة الكبيرة (بطول الشاشة)، يمين = الصور الصغيرة والأجهزة والأزرار (بتتقلّب).
     * الأزرار ما بتضل تحت على عرض الشاشة كلها وتاكل نص الطول.
     */
    private fun layoutFor(land: Boolean) {
        val tp = twoPane ?: return
        val col = mainCol ?: return
        val bar = bottomBarV ?: return
        if (land != tp.land && ::actions.isInitialized) {
            val moving = listOf<View>(actions, startBtn)
            if (land) {
                for (v in moving) { (v.parent as? ViewGroup)?.removeView(v); col.addView(v) }
                tp.apply(true)
            } else {
                tp.apply(false)
                for (v in moving) { (v.parent as? ViewGroup)?.removeView(v); bar.addView(v) }
            }
        }
        val dm = resources.displayMetrics
        bigFrame?.layoutParams = (bigFrame?.layoutParams as? LinearLayout.LayoutParams)?.apply {
            height = if (land) (dm.heightPixels - dp(130)).coerceAtLeast(dp(160)) else (dm.widthPixels * 9 / 16).coerceAtMost(dp(320))
        }
        bigFrame?.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutFor(newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0D0E10")) }
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { isFillViewport = true; addView(col) }
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottomBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(bottomBar)
        mainCol = col; bottomBarV = bottomBar
        frame.addView(outer)
        setContentView(frame)
        // بالعرض: المعاينة على جهة والأجهزة والأزرار على الجهة الثانية
        twoPane = TwoPane(this, outer, sv, col) { 2 }
        ui.post { layoutFor(resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }

        // المؤقت + قفل اللمس
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(12), dp(8)) }
        top.addView(UiKit.iconButton(this, if (L.ar) Ic.BACK else Ic.CHEV, tx("رجوع", "Back"), bgColor = null) { finish() })
        timeTv = UiKit.text(this, "", 26f, UiKit.TEXT).apply { typeface = Fonts.bold; setPadding(dp(6), 0, 0, 0) }
        top.addView(timeTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(UiKit.chipIcon(this, Ic.LOCK, tx("قفل اللمس", "Lock touch")) { setLock(true) })
        col.addView(top)

        // المعاينة الكبيرة
        val pv = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#1A1C20")) }
        bigFrame = pv
        big = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        pv.addView(big, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bigHint = UiKit.text(this, tx("اختار كاميرا من تحت", "Pick a camera below"), 14f, UiKit.SUB).apply { gravity = Gravity.CENTER }
        pv.addView(bigHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        viewTag = UiKit.text(this, "", 13f, "#FFFFFF").apply {
            background = UiKit.bg("#B3000000", 8, this@MonitorActivity); setPadding(dp(10), dp(4), dp(10), dp(4))
            setCompoundDrawablesRelativeWithIntrinsicBounds(UiKit.icon(this@MonitorActivity, Ic.EYE, "#FFFFFF", 16), null, null, null); compoundDrawablePadding = dp(6)
        }
        pv.addView(viewTag, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        editTag = UiKit.text(this, "", 12.5f, "#FFFFFF").apply { background = UiKit.bg("#E63D8BFF", 8, this@MonitorActivity); setPadding(dp(10), dp(4), dp(10), dp(4)) }
        pv.addView(editTag, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        pv.setOnClickListener { if (sel.isNotEmpty()) startActivity(Intent(this, FramingActivity::class.java).putExtra("dev", sel)) }
        val w = resources.displayMetrics.widthPixels
        col.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (w * 9 / 16).coerceAtMost(dp(320))))

        // الصور الصغيرة
        thumbs = LinearLayout(this)
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(thumbs); setPadding(dp(12), dp(10), dp(12), 0) })
        col.addView(UiKit.text(this, tx("لمسة على صورة = معاينة بس (ما بتغيّر المونتاج). المونتاج بيلحق اللي بيحكي تلقائيًا.", "Tapping a picture = preview only (doesn't change the edit). The edit follows whoever speaks automatically."), 13f, UiKit.SUB).apply { setPadding(dp(16), dp(6), dp(16), 0) })

        // حالة الأجهزة
        devRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        col.addView(devRows)

        // الأزرار
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16)) }
        val star = bigBtn(Ic.STAR, "#FFD21F", tx("لحظة مهمة", "Key moment")) { PodRec.mark(this, "star"); toast(tx("سُجّلت لحظة مهمة", "Key moment marked")) }
        val oops = bigBtn(Ic.X, UiKit.ORANGE, tx("غلطة / إعادة", "Mistake / retake")) { PodRec.mark(this, "oops"); toast(tx("سُجّلت علامة غلطة (ما بينحذف إشي تلقائيًا)", "Mistake marked (nothing is deleted automatically)")) }
        actions.addView(UiKit.row(this, star, oops))
        // حدا قام يعمل إشي ورا الكواليس؟ صوته ما بيقلب الكاميرا لحد ما ترجّعه
        val away = UiKit.chip(this, tx("بعّد الكاميرا عن شخص…", "Keep the camera off someone…")) { awaySheet() }
        actions.addView(away, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        // الإنهاء: ضغطة مطوّلة بس (الزر بيتعبّى خلال ثانية ونص)، بدون نافذة تأكيد
        endBtn = HoldButton(this, tx("إنهاء التسجيل (اضغط مطوّلًا)", "End recording (press and hold)")) { endNow() }.also {
            it.onShortTap = { toast(tx("اضغط مطوّلًا لحد ما يتعبّى الزر", "Press and hold until the button fills")) }
        }
        actions.addView(endBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(10) })
        bottomBar.addView(actions)

        // قبل التسجيل
        startBtn = UiKit.primary(this, "●  " + tx("ابدأ التسجيل", "Start recording"), UiKit.RED) { beginRecording() }
        bottomBar.addView(startBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { setMargins(dp(16), dp(12), dp(16), dp(16)) })
        // قفل اللمس: طبقة بتمسك كل اللمسات، وبتنفتح بضغطة مطوّلة
        lockOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#33000000")); visibility = View.GONE
            isClickable = true
        }
        val unlock = UiKit.chipIcon(this, Ic.UNLOCK, tx("اللمس مقفول — اضغط مطوّلًا للفتح", "Touch locked — press and hold to unlock")) { toast(tx("اضغط مطوّلًا للفتح", "Press and hold to unlock")) }
        unlock.setOnLongClickListener { setLock(false); true }
        lockOverlay.addView(unlock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(70) })
        frame.addView(lockOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun bigBtn(path: String, color: String, label: String, run: () -> Unit): Button =
        UiKit.chip(this, label) { run() }.apply {
            minHeight = dp(64); minimumHeight = dp(64); textSize = 15.5f; typeface = Fonts.semi
            setAutoSizeTextTypeUniformWithConfiguration(11, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            UiKit.withIcon(this, path, color, 24)
            background = UiKit.bg(UiKit.CARD2, 16, this@MonitorActivity)
        }

    /** إنهاء فوري بعد الضغطة المطوّلة، وبعدها على «مشاريعي» مباشرة */
    /** الأشخاص اللي الكاميرا بعيد عنهم هلأ */
    private val awayNow = HashSet<Int>()

    private fun awaySheet() {
        val sid = PodRec.active ?: run { toast(tx("بلّش التسجيل أول", "Start recording first")); return }
        val people = try { PodcastAnalyzer.setupOf(SessionStore.load(this, sid)!!).people.map { it.name } } catch (_: Throwable) { emptyList() }
        if (people.isEmpty()) return
        UiKit.sheet(this, tx("بعّد الكاميرا عن…", "Keep the camera off…")) { col, dlg ->
            col.addView(UiKit.text(this, tx("مثلًا قام يعمل إشي وصوته واصل للمايك. صوته بهالفترة ما بيقلب الكاميرا عليه. اضغط كمان مرة لما يرجع.", "E.g. they stepped away and their mic still picks up sounds. Their sound won't switch the camera until you tap again."), 13f, UiKit.SUB))
            for ((i, n) in people.withIndex()) {
                val on = i in awayNow
                val b = UiKit.chip(this, if (on) tx("$n: الكاميرا بعيد عنه — اضغط لترجّعه", "$n: kept off — tap to bring back") else tx("$n: عادي — اضغط لتبعّد الكاميرا عنه", "$n: normal — tap to keep the camera off"), on) {
                    if (on) awayNow.remove(i) else awayNow.add(i)
                    PodVoice.liveIgnore(this, sid, i, !on)
                    toast(if (on) tx("رجعت الكاميرا عادي لـ $n", "Camera back to normal for $n") else tx("الكاميرا بعيد عن $n", "Camera kept off $n"))
                    dlg.dismiss()
                }
                col.addView(b, UiKit.full(this, b, 8))
            }
        }
    }

    private fun endNow() {
        PodRec.stop(this)
        Toast.makeText(this, tx("انحفظ — الملفات بتنجمع لحالها", "Saved — files are being collected"), Toast.LENGTH_LONG).show()
        startActivity(Intent(this, HomeActivity::class.java).putExtra("tab", "projects").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun setLock(on: Boolean) {
        lockOverlay.visibility = if (on) View.VISIBLE else View.GONE
        if (on) toast(tx("انقفل اللمس — التسجيل مكمّل", "Touch locked — recording continues"))
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (lockOverlay.visibility == View.VISIBLE) toast(tx("اللمس مقفول", "Touch is locked")) else super.onBackPressed() }

    override fun onResume() { super.onResume(); sig = ""; ui.post(tick) }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        for (l in SatHub.links.values) SatHub.setPreview(l.id, l.stripOn, false, l.stripOn)
    }

    private fun beginRecording() {
        if (RecorderState.isBusy) { toast(tx("أوقف تسجيل الرياكشن أولًا", "Stop the reaction recording first")); return }
        val s = PodSetup.loadDefault(this)
        PodExplain.confirmStart(this, s, PodRec.check(s)) {
            val sid = PodRec.start(this, s)
            getSharedPreferences("reaction_podcast", MODE_PRIVATE).edit().putString("lastSid", sid).apply()
            ended = false; sig = ""
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) refresh()
            ui.postDelayed(this, 250)
        }
    }

    /** الكاميرات اللي إلها دور بالجلسة (أو كل الكاميرات المتصلة إذا ما في إعداد) */
    private fun cams(): List<SatHub.Link> {
        val ids = (setup.people.map { it.camDev } + setup.wides).filter { it.isNotEmpty() }.toSet()
        val all = SatHub.links.values.filter { it.role == "both" }
        return (if (ids.isEmpty()) all else all.filter { it.id in ids }).sortedBy { it.name }
    }

    private fun personOf(id: String): String? = setup.people.firstOrNull { it.camDev == id || it.micDev == id }?.name

    private var setupAt = 0L

    private fun refresh() {
        val rec = PodRec.active != null
        val now = SystemClock.elapsedRealtime()
        if (now - setupAt > 2000) { setupAt = now; setup = if (rec && PodRec.isVlog(this)) PodSetup.loadDefault(this, "vlog") else PodSetup.loadDefault(this) }
        val want = if (rec) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        if (requestedOrientation != want) requestedOrientation = want
        val cs = cams()
        if (sel.isEmpty() || cs.none { it.id == sel }) sel = cs.firstOrNull()?.id ?: ""
        val links = SatHub.links.values.sortedBy { it.name }
        val s = links.joinToString("|") { it.id + it.role + it.name } + rec + sel + ended
        if (s != sig) {
            sig = s
            buildThumbs(cs)
            buildRows(links)
            for (l in links) when {
                l.id == sel -> SatHub.setPreview(l.id, true, false, true, 150, 480)
                l.role == "both" -> SatHub.setLightPreview(l.id, true, 1000, 240)
                else -> SatHub.setPreview(l.id, false, false, true)
            }
            actions.visibility = if (rec) View.VISIBLE else View.GONE
            startBtn.visibility = if (!rec) View.VISIBLE else View.GONE
        }
        // المؤقت
        if (rec) { val sec = PodRec.elapsedSec(this); timeTv.text = "● " + String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60); timeTv.setTextColor(Color.parseColor(UiKit.RED)) }
        else { timeTv.text = if (ended) tx("انتهى التسجيل", "Recording ended") else tx("جاهز للتسجيل", "Ready to record"); timeTv.setTextColor(Color.parseColor(UiKit.TEXT)) }
        // المعاينة الكبيرة
        val l = SatHub.links[sel]
        val f = l?.frame
        if (f != null && l.frameAt != bigSeen) { big.setImageBitmap(f); bigSeen = l.frameAt }
        bigHint.visibility = if (f == null) View.VISIBLE else View.GONE
        bigHint.text = if (cs.isEmpty()) tx("ما في كاميرات متصلة", "No cameras connected") else tx("بانتظار الصورة…", "Waiting for the picture…")
        viewTag.text = tx("معاينة: ", "Preview: ") + (personOf(sel) ?: l?.name ?: "—")
        viewTag.visibility = if (sel.isEmpty()) View.GONE else View.VISIBLE
        // قرار المونتاج الحالي (تقريبي من مستوى المايكات)
        val ppl = setup.people
        val lv = IntArray(ppl.size) { i -> val p = ppl[i]; val k = SatHub.links[p.micDev.ifEmpty { p.camDev }]; if (k != null && k.connected) k.level else 0 }
        val sp = speakers.update(lv)
        if (sp.size == 1) editMain = sp[0]
        editTag.text = tx("بالمونتاج هلأ: تلقائي", "In the edit now: auto") + when {
            sp.size >= 2 -> tx(" · بيحكوا مع بعض", " · talking together")
            editMain in ppl.indices -> " · " + ppl[editMain].name + tx(" بيحكي", " speaking")
            else -> ""
        }
        editTag.visibility = if (rec && ppl.size > 1) View.VISIBLE else View.GONE
        // الصور الصغيرة
        for ((id, t) in thumbViews) {
            val k = SatHub.links[id] ?: continue
            val fr = k.frame
            if (fr != null && t.seen != k.frameAt) { t.img.setImageBitmap(fr); t.seen = k.frameAt }
            t.frame.background = UiKit.bg(if (id == sel) UiKit.TEXT else "#22242A", 10, this)
        }
        // الحالة: الاتصال والتسجيل كلٌّ لحاله، من آخر حالة وصلت فعليًا
        for ((id, row) in rows) {
            val k = SatHub.links[id] ?: continue
            row.conn.text = if (k.connected) tx("متصل", "Connected") else tx("الاتصال مقطوع", "Disconnected") + (if (k.lastSeen > 0) " · " + fmtAgo((now - k.lastSeen) / 1000) else "")
            row.conn.setTextColor(Color.parseColor(if (k.connected) UiKit.SUB else UiKit.ORANGE))
            val (t, c) = when {
                k.connected && k.recording -> ("● " + tx("يسجّل", "Recording")) to UiKit.RED
                !k.connected && k.recording -> tx("آخر حالة: كان يسجّل محليًا", "Last known: recording locally") to UiKit.ORANGE
                k.transfer.isNotEmpty() -> tx("بيبعت ", "Sending ") + k.transfer to UiKit.BLUE
                k.connected -> tx("مش عم يسجّل", "Not recording") to (if (rec) UiKit.ORANGE else UiKit.SUB)
                else -> tx("حالته مش معروفة", "Unknown") to UiKit.SUB
            }
            if (row.rec.text != t) { row.rec.text = t; val nb = UiKit.badge(this, t, c); row.rec.setTextColor(nb.currentTextColor); row.rec.background = nb.background }
        }
    }

    private fun fmtAgo(sec: Long): String = if (sec < 60) tx("من ${sec}ث", "${sec}s ago") else tx("من ${sec / 60}د", "${sec / 60}m ago")

    private fun buildThumbs(cs: List<SatHub.Link>) {
        thumbs.removeAllViews(); thumbViews.clear()
        for (l in cs) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val fr = FrameLayout(this).apply { setPadding(dp(2), dp(2), dp(2), dp(2)); background = UiKit.bg("#22242A", 10, this@MonitorActivity) }
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
            fr.addView(iv, FrameLayout.LayoutParams(dp(112), dp(63)))
            cell.addView(fr)
            cell.addView(UiKit.text(this, personOf(l.id) ?: l.name, 13f, UiKit.SUB).apply { gravity = Gravity.CENTER; maxLines = 1 })
            cell.setOnClickListener { sel = l.id; bigSeen = 0; sig = "" }
            thumbViews[l.id] = Thumb(iv, fr)
            thumbs.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
    }

    private fun buildRows(links: List<SatHub.Link>) {
        devRows.removeAllViews(); rows.clear()
        for (l in links) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setPadding(0, dp(4), 0, dp(4)) }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(UiKit.text(this, (personOf(l.id)?.let { "$it · " } ?: "") + l.name, 15f, UiKit.TEXT))
            val conn = UiKit.text(this, "", 12.5f, UiKit.SUB)
            t.addView(conn)
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val st = UiKit.badge(this, "", UiKit.SUB)
            r.addView(st)
            devRows.addView(r)
            devRows.addView(View(this).apply { setBackgroundColor(Color.parseColor("#2A2C32")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            rows[l.id] = Row(conn, st)
        }
    }
}
