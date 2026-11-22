package com.reactionrecorder

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/** تشغيل ملف المعاينة (20 ثانية بنفس قرارات التصدير، بدقة خفيفة، مع أدلة المناطق الآمنة) */
class PodPreviewActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("file")
        val uri = intent.getStringExtra("uri")
        if (path == null && uri == null) { finish(); return }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val vv = VideoView(this)
        root.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(TextView(this).apply {
            text = if (uri != null) tx("هاد الملف النهائي بالزبط — إذا عجبك ارجع واكبس «احفظه بالمعرض» (بينحفظ نفسه بدون ما ينعمل من جديد)", "This is the exact final file — if you like it, go back and tap “Save to gallery” (the same file is saved, not re-made)")
                else tx("معاينة بدقة خفيفة — المربعات الحمراء الشفافة = مناطق أزرار المنصات (لا تظهر في التصدير)", "Light preview — the transparent red boxes = platform button areas (not in the export)")
            setTextColor(Color.WHITE); textSize = 12f; setBackgroundColor(0x88000000.toInt()); setPadding(16, 12, 16, 12)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        vv.setMediaController(MediaController(this).also { it.setAnchorView(vv) })
        if (uri != null) vv.setVideoURI(android.net.Uri.parse(uri)) else vv.setVideoPath(path)
        vv.setOnPreparedListener {
            it.isLooping = true
            // الشاشة بتلف حسب الفيديو: بالعرض للفيديو العرضي، وطولي للطولي
            requestedOrientation = if (it.videoWidth > it.videoHeight) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            vv.start()
        }
    }
}
