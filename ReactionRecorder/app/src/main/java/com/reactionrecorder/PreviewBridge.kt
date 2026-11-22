package com.reactionrecorder

import android.view.Surface

/**
 * جسر بين واجهة التطبيق وخدمة التسجيل (نفس العملية): سطح "شاشة المراقبة" الذي يعرض الفيديو المركَّب مباشرة
 * أثناء التسجيل في وضع Video file، كي ترى الفيديو وأنت تعلّق عليه.
 */
object PreviewBridge {
    @Volatile var surface: Surface? = null
    @Volatile var compositor: Compositor? = null

    fun attach(s: Surface) {
        surface = s
    }

    /** يُستدعى قبل تدمير السطح: يسحبه وينتظر حتى يتوقف الـ Compositor عن الرسم عليه */
    fun detach() {
        surface = null
        compositor?.syncPreviewBlocking()
    }
}
