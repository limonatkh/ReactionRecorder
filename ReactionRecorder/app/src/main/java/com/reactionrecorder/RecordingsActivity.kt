package com.reactionrecorder

import android.content.Intent
import android.os.Bundle

/** "تسجيلاتي" صارت تبويب «مشاريعي» بالشاشة الرئيسية — هذه الشاشة بتحوّل عليه (للروابط القديمة) */
class RecordingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, HomeActivity::class.java).putExtra("tab", "projects").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
