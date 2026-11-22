package com.reactionrecorder

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

/** الإعدادات مقسّمة لأقسام تنطوي: عام، التصوير، الأجهزة والشبكة، التخزين، خدمة التفريغ */
class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = UiKit.page(this, "⚙️ " + tx("الإعدادات", "Settings"))

        // ── عام
        val g = UiKit.section(this, col, "set_general", "🌐 " + tx("عام", "General"), if (L.ar) "العربية" else "English", openByDefault = true)
        g.addView(UiKit.text(this, tx("لغة التطبيق", "App language"), 13f, UiKit.SUB))
        g.addView(UiKit.row(this,
            UiKit.chip(this, "العربية", L.ar) { setLang("ar") },
            UiKit.chip(this, "English", !L.ar) { setLang("en") },
        ))

        // ── التصوير (الرياكشن)
        val cfg = ConfigStore.load(this)
        val r = UiKit.section(this, col, "set_rec", "" + tx("التصوير", "Recording"), tx("اتجاه الفيديو، الملاءمة، الجودة", "Orientation, fit, quality"))
        r.addView(UiKit.text(this, tx("اتجاه فيديو الرياكشن", "Reaction video orientation"), 13f, UiKit.SUB))
        val oRow = LinearLayout(this)
        fun oSel() {
            oRow.removeAllViews()
            for ((k, n) in listOf("auto" to tx("تلقائي", "Auto"), "portrait" to tx("عمودي 9:16", "Portrait 9:16"), "landscape" to tx("أفقي 16:9", "Landscape 16:9"))) {
                oRow.addView(UiKit.chip(this, n, cfg.orientMode == k) {
                    cfg.orientMode = k
                    if (k != "auto") { cfg.portrait = k == "portrait"; cfg.layout = Presets.build(if (cfg.layout.presetId in 1..5) cfg.layout.presetId else 1, cfg.portrait, cfg.layout.ratio, cfg.layout) }
                    ConfigStore.save(this, cfg); oSel(); UiKit.done(this, tx("انحفظ", "Saved"))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = UiKit.dp(this@SettingsActivity, 6) })
            }
        }
        oSel()
        r.addView(oRow)
        r.addView(UiKit.text(this, tx("تلقائي: عند تشغيل فيديو من الجوال يأخذ اتجاهه (أفقي/عمودي)، ثم يثبت طوال التسجيل.", "Auto: when playing a video from the phone it takes its orientation, then stays fixed for the whole recording."), 11.5f, UiKit.SUB))
        r.addView(UiKit.text(this, tx("الصورة داخل الإطار", "Picture inside the frame"), 13f, UiKit.SUB).apply { setPadding(0, UiKit.dp(this@SettingsActivity, 10), 0, 0) })
        val fRow = LinearLayout(this)
        fun fSel() {
            fRow.removeAllViews()
            for ((k, n) in listOf(SourceFit.FIT to tx("كاملة بدون قص", "Whole, no crop"), SourceFit.FILL to tx("ملء مع قص", "Fill with crop"))) {
                val sel = cfg.fitScreen == k && cfg.fitVideo == k
                fRow.addView(UiKit.chip(this, n, sel) { cfg.fitScreen = k; cfg.fitVideo = k; ConfigStore.save(this, cfg); fSel(); UiKit.done(this, tx("انحفظ", "Saved")) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = UiKit.dp(this@SettingsActivity, 6) })
            }
        }
        fSel()
        r.addView(fRow)
        r.addView(UiKit.text(this, tx("كاملة: الفراغ حول الصورة يُملأ بنفسها مموّهة بدل الأسود.", "Whole: the space around the picture is filled with a blurred copy instead of black."), 11.5f, UiKit.SUB))
        val adv = UiKit.chipIcon(this, Ic.SLIDERS, tx("إعدادات الرياكشن المتقدمة", "Advanced reaction settings")) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("openSettings", true))
        }
        r.addView(adv, UiKit.full(this, adv, 10))

        // المونتاج: كل إعداداته بالقالب (مش هون)

        // ── الأجهزة والشبكة
        val n = UiKit.section(this, col, "set_net", "📡 " + tx("الأجهزة والشبكة", "Devices and network"), tx("الرمز، اسم هذا الجوال", "PIN, this phone's name"))
        val sc = SatCfg.load(this)
        n.addView(UiKit.text(this, tx("اسم هذا الجوال (يظهر للآخرين)", "This phone's name (shown to others)"), 13f, UiKit.SUB))
        val nameEt = EditText(this).apply { setText(sc.name); setSingleLine() }
        n.addView(nameEt)
        n.addView(UiKit.chip(this, tx("حفظ الاسم", "Save name")) { val c = SatCfg.load(this); c.name = nameEt.text.toString().trim().ifEmpty { android.os.Build.MODEL ?: "Phone" }; SatCfg.save(this, c); UiKit.done(this, tx("انحفظ الاسم: ", "Name saved: ") + c.name) })
        n.addView(UiKit.text(this, tx("رمز الاقتران الحالي: ", "Current pairing PIN: ") + (if (SatHub.running) SatHub.pin else "—"), 13f).apply { setPadding(0, UiKit.dp(this@SettingsActivity, 10), 0, 0) })
        n.addView(UiKit.chip(this, "" + tx("رمز جديد (يفصل الأجهزة المقترنة)", "New PIN (unpairs devices)")) {
            AlertDialog.Builder(this).setMessage(tx("الأجهزة المقترنة ستحتاج مسح الباركود من جديد. متابعة؟", "Paired devices will need to scan the QR again. Continue?"))
                .setPositiveButton(tx("نعم", "Yes")) { _, _ -> SatHub.newPin(this); recreate() }.setNegativeButton(tx("لا", "No"), null).show()
        })
        n.addView(UiKit.chip(this, "" + tx("الأجهزة وجلسات الرياكشن (عرض قديم)", "Devices & reaction sessions (classic view)")) { startActivity(Intent(this, DevicesActivity::class.java)) })

        // ── التخزين
        val st = UiKit.section(this, col, "set_store", "💾 " + tx("التخزين", "Storage"), tx("متاح: ", "Free: ") + StorageUtil.formatBytes(StorageUtil.freeBytes()))
        st.addView(UiKit.text(this, tx("الفيديوهات النهائية في المعرض: Movies/ReactionRecorder.\nملفات العمل (للدمج والمونتاج) داخل التطبيق — احذفها من تسجيلاتي ← التسجيل ← حذف ملفات العمل.", "Final videos are in the Gallery: Movies/ReactionRecorder.\nWorking files (for merging and editing) are inside the app — delete them from My recordings ← the recording ← Delete working files."), 12.5f, UiKit.SUB))
        st.addView(UiKit.chip(this, "" + tx("تسجيلاتي", "My recordings")) { startActivity(Intent(this, RecordingsActivity::class.java)) })

        // ── خدمة التفريغ
        col.addView(UiKit.text(this, tx("متقدم", "Advanced"), 14f, UiKit.SUB).apply { setPadding(0, UiKit.dp(this@SettingsActivity, 22), 0, 0) })
        val ai = UiKit.section(this, col, "set_ai", tx("خدمة التفريغ وفهم الحوار (اختياري)", "Transcription & understanding service (optional)"), if (PodAI.cfg(this).ok) tx("مضبوطة", "Configured") else tx("غير مضبوطة", "Not configured"))
        ai.addView(UiKit.text(this, tx("للكتابة على الفيديو واقتراح الريلز حسب المعنى. لا يُرفع شيء إلا بموافقتك.", "For captions and meaning-based reel ideas. Nothing is uploaded without your consent."), 12.5f, UiKit.SUB))
        ai.addView(UiKit.chip(this, tx("اضبط الخدمة", "Set up the service")) { PodUi.aiDialog(this) })
    }

    private fun setLang(l: String) {
        if (l == L.lang) return
        L.set(this, l)
        // نعيد فتح الواجهة من الشاشة الرئيسية باللغة الجديدة
        val i = Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
