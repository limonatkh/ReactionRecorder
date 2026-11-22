package com.reactionrecorder

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * التفعيل:
 *  • نسخة صديق: رمز جهازه (باركود) + مسح باركود الترخيص من جوال صاحب التطبيق. بدون تفعيل: الانضمام فقط.
 *  • صاحب التطبيق: تفعيل الأجهزة (يوم افتراضيًا)، إيقافها، ومدة قفل البصمة.
 */
class LicenseActivity : BaseActivity() {
    override val allowedWithoutLicense = true
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var col: LinearLayout
    private var pendingDur = License.DAY

    private val scanLic = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val raw = r.data?.getStringExtra("raw") ?: return@registerForActivityResult
        val err = License.apply(this, raw)
        AlertDialog.Builder(this).setMessage(err ?: (tx("✓ فُعّل التطبيق لمدة ", "✓ Activated for ") + License.fmtLeft(License.remaining(this))))
            .setPositiveButton(tx("تم", "OK")) { _, _ -> if (err == null) { startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() } }.show()
    }

    private val scanDev = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val raw = r.data?.getStringExtra("raw") ?: return@registerForActivityResult
        val p = raw.split('|')
        val dev = p.getOrNull(1) ?: return@registerForActivityResult
        val name = p.getOrNull(2) ?: dev
        val lic = License.issue(this, dev, name, pendingDur)
        if (lic == null) { AlertDialog.Builder(this).setMessage(tx("تعذر إصدار الترخيص", "Could not issue the licence")).setPositiveButton("OK", null).show(); return@registerForActivityResult }
        showQr(tx("خلي صاحبك يمسح هذا الباركود", "Let your friend scan this code"), lic,
            tx("على جواله: التفعيل ← امسح الترخيص. المدة: ", "On their phone: Activation ← Scan licence. Duration: ") + durName(pendingDur))
        build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = UiKit.page(this, "🔐 " + tx("التفعيل", "Activation"))
        build()
    }

    private fun dp(v: Int) = UiKit.dp(this, v)

    private fun durName(ms: Long): String = when (ms) {
        License.DAY -> tx("يوم", "1 day"); 3 * License.DAY -> tx("3 أيام", "3 days"); 7 * License.DAY -> tx("أسبوع", "1 week"); 30 * License.DAY -> tx("30 يوم", "30 days")
        else -> License.fmtLeft(ms)
    }

    private fun build() {
        while (col.childCount > 1) col.removeViewAt(1)
        if (License.isOwner(this)) buildOwner() else buildClient()
    }

    private fun qr(payload: String, sizeDp: Int = 230): ImageView = ImageView(this).apply {
        setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8))
        setImageBitmap(PodcastActivity.qrBitmap(payload))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) }
    }

    private fun showQr(title: String, payload: String, note: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        box.addView(qr(payload, 260))
        box.addView(TextView(this).apply { text = note; textSize = 13f; setPadding(0, dp(10), 0, 0) })
        AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton(tx("تم", "Done"), null).show()
    }

    // ───── نسخة صديق ─────

    private fun buildClient() {
        val left = License.remaining(this)
        val st = UiKit.card(this, null, col)
        st.addView(UiKit.text(this, if (left > 0) "✓ " + tx("مفعّل — باقي ", "Active — ") + License.fmtLeft(left) + tx("", " left") else "🔒 " + tx("غير مفعّل", "Not activated"), 17f, if (left > 0) UiKit.GREEN else UiKit.ORANGE, true))
        st.addView(UiKit.text(this, tx("بدون تفعيل تقدر تنضم لتسجيل كمايك أو كاميرا فقط. التفعيل من صاحب التطبيق: وانت متصل بجواله يفعّلك بضغطة، أو يمسح رمزك ويعطيك باركود.", "Without activation you can only join a recording as a mic or camera. The owner activates you: with one tap while you're connected to their phone, or by scanning your code and showing you a QR."), 12.5f, UiKit.SUB).apply { setPadding(0, dp(6), 0, 0) })

        val c = UiKit.card(this, tx("رمز هذا الجهاز", "This device's code"), col)
        val dev = License.deviceId(this)
        c.addView(qr("RRDEV|$dev|" + (android.os.Build.MODEL ?: "Phone")))
        c.addView(UiKit.text(this, dev, 15f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        val sc = UiKit.primary(this, "📷  " + tx("امسح باركود الترخيص", "Scan the licence QR")) { scanLic.launch(Intent(this, ScanActivity::class.java).putExtra("mode", "lic")) }
        col.addView(sc, UiKit.full(this, sc, 14, 54))

        val o = UiKit.section(this, col, "lic_owner", tx("أنا صاحب التطبيق", "I am the app owner"))
        o.addView(UiKit.chip(this, tx("أدخل رمز المالك", "Enter the owner code")) { ownerDialog() })
    }

    private fun ownerDialog() {
        val et = EditText(this).apply { hint = "XXXX-XXXX-XXXX"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; setSingleLine() }
        AlertDialog.Builder(this).setTitle(tx("رمز المالك", "Owner code")).setView(et)
            .setPositiveButton(tx("تفعيل", "Activate")) { _, _ ->
                val code = et.text.toString()
                val wait = AlertDialog.Builder(this).setMessage(tx("جارٍ التحقق…", "Checking…")).setCancelable(false).show()
                Thread {
                    val ok = try { License.becomeOwner(applicationContext, code) } catch (_: Throwable) { false }
                    ui.post {
                        wait.dismiss()
                        if (ok) {
                            AlertDialog.Builder(this).setMessage(tx("✓ هذا الجوال صار جوال صاحب التطبيق. سيُطلب منك فتحه بالبصمة كل ${OwnerLock.hours(this)} ساعة وبعد إعادة تشغيل الجوال.", "✓ This phone is now the owner's phone. You'll unlock it with your fingerprint every ${OwnerLock.hours(this)} hours and after a restart."))
                                .setPositiveButton(tx("تم", "OK")) { _, _ -> startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() }.show()
                        } else AlertDialog.Builder(this).setMessage(tx("الرمز غير صحيح", "Wrong code")).setPositiveButton("OK", null).show()
                    }
                }.start()
            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    // ───── صاحب التطبيق ─────

    private fun buildOwner() {
        val st = UiKit.card(this, null, col)
        st.addView(UiKit.text(this, "👑 " + tx("أنت صاحب التطبيق", "You are the app owner"), 17f, UiKit.TEXT, true))
        st.addView(UiKit.text(this, tx("القفل بالبصمة كل:", "Fingerprint lock every:"), 12.5f, UiKit.SUB).apply { setPadding(0, dp(8), 0, 0) })
        val h = OwnerLock.hours(this)
        st.addView(UiKit.row(this, *listOf(12, 24, 48, 72).map { v -> UiKit.chip(this, tx("$v ساعة", "$v h"), h == v) { OwnerLock.setHours(this, v); build() } }.toTypedArray()))
        st.addView(UiKit.text(this, tx("بساعة داخلية لا تتأثر بتغيير التاريخ، وبعد كل إعادة تشغيل للجوال.", "Measured by an internal clock that ignores date changes, and after every phone restart."), 11.5f, UiKit.SUB))

        val a = UiKit.card(this, "🔓 " + tx("فعّل جهازًا", "Activate a device"), col)
        a.addView(UiKit.text(this, tx("الأسهل: وصاحبك متصل بجوالك (حوار أو فلوق) اضغط 🔓 بجانب جهازه. أو هنا: اختر المدة وامسح رمز جهازه.", "Easiest: while your friend is connected to you (podcast or vlog) tap 🔓 next to their device. Or here: pick a duration and scan their device code."), 12.5f, UiKit.SUB))
        val durs = listOf(License.DAY, 3 * License.DAY, 7 * License.DAY, 30 * License.DAY)
        a.addView(UiKit.row(this, *durs.map { d -> UiKit.chip(this, durName(d), pendingDur == d) { pendingDur = d; build() } }.toTypedArray()))
        val b = UiKit.primary(this, "📷  " + tx("امسح رمز جهاز صاحبك", "Scan your friend's device code")) { scanDev.launch(Intent(this, ScanActivity::class.java).putExtra("mode", "dev")) }
        a.addView(b, UiKit.full(this, b, 10, 52))

        val l = UiKit.card(this, "📋 " + tx("الأجهزة المفعّلة", "Activated devices"), col)
        val list = License.issued(this)
        if (list.isEmpty()) l.addView(UiKit.text(this, tx("لا يوجد بعد", "None yet"), 12.5f, UiKit.SUB))
        val now = System.currentTimeMillis()
        for (x in list) {
            val alive = !x.revoked && x.until > now
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(UiKit.text(this, x.name.ifBlank { x.dev }, 14f))
            t.addView(UiKit.text(this, if (alive) tx("مفعّل حتى تقريبًا ", "Active until about ") + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(x.until)) else if (x.revoked) tx("أُوقف", "Stopped") else tx("انتهى", "Expired"), 11.5f, if (alive) UiKit.GREEN else UiKit.SUB))
            row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (alive) row.addView(UiKit.chip(this, "🔒 " + tx("أوقف", "Stop")) { stopDevice(x) })
            l.addView(row)
        }
    }

    private fun stopDevice(x: License.Issued) {
        val link = SatHub.links.values.firstOrNull { it.licDev == x.dev && it.connected }
        if (link != null) {
            SatHub.revokeLicense(link.id)
            AlertDialog.Builder(this).setMessage(tx("✓ أُقفلت نسخته الآن", "✓ Their copy is locked now")).setPositiveButton("OK", null).show()
        } else {
            License.markRevoked(this, x.dev)
            AlertDialog.Builder(this).setMessage(tx("جهازه غير متصل الآن، فتُقفل نسخته أول ما يتصل بجوالك، وإلا عند انتهاء مدته.", "Their phone isn't connected now, so their copy locks as soon as it connects to you, otherwise when its time runs out."))
                .setPositiveButton("OK", null).show()
        }
        build()
    }
}
