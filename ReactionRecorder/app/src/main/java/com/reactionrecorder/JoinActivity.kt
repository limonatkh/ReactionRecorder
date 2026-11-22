package com.reactionrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * انضمام لتسجيل: اختر دور هذا الجوال (كاميرا ومايك، أو مايك لاسلكي) ثم امسح باركود الجوال الرئيسي.
 * بعد المسح يتصل تلقائيًا — لا عنوان ولا منفذ ولا رمز يُكتب. الإدخال اليدوي مخفي تحت "متقدم".
 */
class JoinActivity : BaseActivity() {
    override val allowedWithoutLicense = true
    private lateinit var cfg: SatCfg
    private var role = "both"
    private lateinit var roleBox: LinearLayout

    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            cfg.host = d.getStringExtra("host") ?: ""
            cfg.pin = d.getStringExtra("pin") ?: ""
            cfg.hubId = d.getStringExtra("hubId") ?: ""
            cfg.hubName = d.getStringExtra("hubName") ?: ""
            connect()
        }
    }

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openScanner() else toast(tx("يلزم إذن الكاميرا لمسح الباركود", "Camera permission is needed to scan"))
    }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { connect(true) }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SatState.serviceOn) { startActivity(Intent(this, SatelliteActivity::class.java)); super.finish(); return }
        cfg = SatCfg.load(this)
        // الافتراضي: مايك لاسلكي (الجوال الرئيسي بيقدر يخليه كاميرا من عنده)
        role = if (cfg.role == "both") "both" else "mic"
        val col = UiKit.page(this, tx("انضمام لتسجيل", "Join a recording"), tx("هذا الجوال يسجّل محليًا بجودة كاملة ويرسل الملفات للجوال الرئيسي عند الانتهاء — حتى لو انقطع الاتصال في النص.", "This phone records locally in full quality and sends the files to the main phone when done — even if the connection drops midway."))

        val c1 = UiKit.card(this, tx("١. ماذا سيكون هذا الجوال؟", "1. What will this phone be?"), col)
        roleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c1.addView(roleBox)
        renderRoles()

        val c2 = UiKit.card(this, tx("٢. امسح باركود الجوال الرئيسي", "2. Scan the main phone's QR code"), col)
        c2.addView(UiKit.text(this, tx("على الجوال الرئيسي: حوار/بودكاست ← الأجهزة ← الباركود.", "On the main phone: Podcast ← Devices ← QR code."), 12.5f, UiKit.SUB))
        val scanBtn = UiKit.primary(this, "📷  " + tx("امسح الباركود واتصل", "Scan the QR and connect")) {
            if (granted(Manifest.permission.CAMERA)) openScanner() else camPerm.launch(Manifest.permission.CAMERA)
        }
        c2.addView(scanBtn, UiKit.full(this, scanBtn, 10, 54))
        if (cfg.host.isNotBlank() && cfg.pin.length == 4) {
            val again = UiKit.chip(this, "↻ " + tx("اتصل بآخر جوال رئيسي", "Reconnect to the last main phone") + if (cfg.hubName.isNotBlank()) " (${cfg.hubName})" else "") { connect() }
            c2.addView(again, UiKit.full(this, again, 8))
        }

        val adv = UiKit.section(this, col, "join_adv", tx("متقدم: إدخال العنوان يدويًا", "Advanced: enter the address manually"))
        val hostEt = EditText(this).apply { hint = "192.168.43.1"; setText(cfg.host); setSingleLine(); textDirection = View.TEXT_DIRECTION_LTR }
        val pinEt = EditText(this).apply { hint = "PIN"; setText(cfg.pin); setSingleLine(); inputType = InputType.TYPE_CLASS_NUMBER }
        adv.addView(UiKit.text(this, tx("العنوان", "Address"), 12f, UiKit.SUB)); adv.addView(hostEt)
        adv.addView(UiKit.text(this, tx("الرمز (4 أرقام)", "PIN (4 digits)"), 12f, UiKit.SUB)); adv.addView(pinEt)
        adv.addView(UiKit.chip(this, tx("اتصل", "Connect")) {
            cfg.host = hostEt.text.toString().trim(); cfg.pin = SatNet.normDigits(pinEt.text.toString().trim())
            if (cfg.host.isEmpty() || cfg.pin.length != 4) toast(tx("أدخل العنوان والرمز", "Enter the address and PIN")) else connect()
        })

        val loc = UiKit.section(this, col, "join_local", tx("تسجيلات محفوظة على هذا الجوال", "Recordings saved on this phone"))
        loc.addView(UiKit.chip(this, tx("افتح القائمة", "Open the list")) { startActivity(Intent(this, SatelliteActivity::class.java).putExtra("localOnly", true)) })
    }

    private fun renderRoles() {
        roleBox.removeAllViews()
        fun opt(r: String, icon: String, title: String, desc: String) {
            val v = UiKit.bigCard(this, icon, title, desc, if (role == r) UiKit.BLUE_BG else UiKit.CARD2) { role = r; renderRoles() }
            if (role == r) v.background = UiKit.bg(UiKit.BLUE_BG, 18, this, UiKit.BLUE)
            roleBox.addView(v, UiKit.full(this, v, 6))
        }
        opt("mic", Ic.MIC, tx("مايك لاسلكي", "Wireless mic"), tx("صوت فقط — شاشة بسيطة وبطارية أقل. الجوال الرئيسي بيقدر يخليه كاميرا بعدين.", "Audio only — a simple screen and less battery. The main phone can turn it into a camera later."))
        opt("both", Ic.CAM, tx("كاميرا ومايك", "Camera and mic"), tx("يصوّر شخصًا أو المكان ويسجّل الصوت", "Films a person or the room and records sound"))
    }

    private fun openScanner() {
        try { androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Throwable) {}
        scan.launch(Intent(this, ScanActivity::class.java))
    }

    private fun connect(asked: Boolean = false) {
        cfg.role = role
        if (cfg.name.isBlank()) cfg.name = Build.MODEL ?: "Phone"
        SatCfg.save(this, cfg)
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
        // إذن الكاميرا حتى للمايك: عشان الجوال الرئيسي يقدر يخليه كاميرا بدون ما تلمس هالجوال
        if (!granted(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty() && !asked) { perms.launch(need.toTypedArray()); return }
        if (role == "both" && !granted(Manifest.permission.CAMERA)) { role = "mic"; cfg.role = "mic"; SatCfg.save(this, cfg) }
        if (!granted(Manifest.permission.RECORD_AUDIO)) { toast(tx("يلزم إذن المايك", "Microphone permission is required")); return }
        try { androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Throwable) {}
        SatState.status = tx("جارٍ الاتصال…", "Connecting…")
        ContextCompat.startForegroundService(this, Intent(this, SatelliteService::class.java).setAction(SatelliteService.ACTION_CONNECT))
        startActivity(Intent(this, SatelliteActivity::class.java))
        super.finish()
    }
}
