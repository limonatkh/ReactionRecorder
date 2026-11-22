package com.reactionrecorder

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * قفل صاحب التطبيق: يُطلب كل فترة (24 ساعة افتراضيًا بساعة داخلية) وبعد إعادة تشغيل الجوال.
 * بالبصمة أو قفل الشاشة؛ وإن لم يكن مضبوطًا على الجوال فبرمز المالك.
 * القفل للواجهة فقط: التسجيل الجاري والأجهزة المتصلة تستمر.
 */
class LockActivity : BaseActivity() {
    private lateinit var msg: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = UiKit.page(this, null)
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(UiKit.text(this, "🔒", 64f).apply { gravity = Gravity.CENTER; setPadding(0, UiKit.dp(this@LockActivity, 80), 0, 0) })
        col.addView(UiKit.text(this, tx("التطبيق مقفل", "The app is locked"), 22f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER })
        msg = UiKit.text(this, tx("افتحه ببصمتك. التسجيل الجاري لم يتوقف.", "Unlock with your fingerprint. Any running recording continues."), 14f, UiKit.SUB).apply { gravity = Gravity.CENTER; setPadding(0, UiKit.dp(this@LockActivity, 8), 0, 0) }
        col.addView(msg)
        val b = UiKit.primary(this, "👆  " + tx("افتح", "Unlock")) { prompt() }
        col.addView(b, UiKit.full(this, b, 28, 56))
        val alt = UiKit.chip(this, tx("افتح برمز المالك", "Unlock with the owner code")) { codeDialog() }
        col.addView(alt, UiKit.full(this, alt, 10))
        prompt()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { moveTaskToBack(true) }

    private fun prompt() {
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            msg.text = tx("لا توجد بصمة أو قفل شاشة على هذا الجوال — استعمل رمز المالك.", "No fingerprint or screen lock on this phone — use the owner code.")
            return
        }
        val p = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { done() }
            override fun onAuthenticationError(code: Int, err: CharSequence) { msg.text = err }
        })
        p.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(tx("فتح Reaction Recorder", "Unlock Reaction Recorder"))
            .setAllowedAuthenticators(auth).build())
    }

    private fun codeDialog() {
        val et = EditText(this).apply { hint = "XXXX-XXXX-XXXX"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; setSingleLine() }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tx("رمز المالك", "Owner code")).setView(et)
            .setPositiveButton(tx("افتح", "Unlock")) { _, _ ->
                val c = et.text.toString()
                msg.text = tx("جارٍ التحقق…", "Checking…")
                Thread {
                    val ok = License.decryptOwnerKey(c) != null
                    runOnUiThread { if (ok) done() else msg.text = tx("الرمز غير صحيح", "Wrong code") }
                }.start()
            }.setNegativeButton(tx("إلغاء", "Cancel"), null).show()
    }

    private fun done() {
        OwnerLock.unlocked(this)
        finish()
    }
}
