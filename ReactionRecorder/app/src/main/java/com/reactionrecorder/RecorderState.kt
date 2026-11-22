package com.reactionrecorder

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/** حالة مشتركة بين الخدمة والواجهة (نفس العملية). التغييرات تُرسَل إلى الـ listeners على الخيط الرئيسي. */
object RecorderState {
    enum class Phase { IDLE, STARTING, COUNTDOWN, RECORDING, PAUSED, FINISHING }

    @Volatile var phase = Phase.IDLE
    @Volatile var countdownLeft = 0
    @Volatile var micMuted = false
    /** مستوى الصوت الحالي (0..32767) للميكروفون ولصوت المصدر، وهل وصلت بيانات من الميكروفون أصلًا */
    @Volatile var micLevel = 0
    @Volatile var srcLevel = 0
    @Volatile var micAlive = false
    @Volatile var srcMuted = false
    /** متحكّم المشهد الحيّ أثناء التسجيل (قائمة التشغيل، الكاميرا، شاشة الانتظار…). null خارج التسجيل */
    @Volatile var scene: Scene? = null
    @Volatile var lastUri: Uri? = null
    @Volatile var lastError: String? = null
    /** جلسة الأجهزة المساعدة الخاصة بآخر تسجيل (إن وُجدت) */
    @Volatile var lastSessionId: String? = null
    /** رسالة معلومات عابرة للمستخدم (مثل: الكاميرتان معًا غير مدعومتين). الواجهة تعرضها مرة وتمسحها */
    @Volatile var notice: String? = null
    @Volatile var notes: List<String> = emptyList()
    @Volatile var resolvedInfo: String = ""

    private var segmentStart = 0L
    private var accumulatedMs = 0L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun changed() {
        main.post { listeners.forEach { it() } }
    }

    val isBusy: Boolean get() = phase != Phase.IDLE

    fun markStarted() { accumulatedMs = 0L; segmentStart = SystemClock.elapsedRealtime() }
    fun markPaused() { accumulatedMs += SystemClock.elapsedRealtime() - segmentStart }
    fun markResumed() { segmentStart = SystemClock.elapsedRealtime() }

    fun elapsedMs(): Long = when (phase) {
        Phase.RECORDING -> accumulatedMs + SystemClock.elapsedRealtime() - segmentStart
        Phase.PAUSED, Phase.FINISHING -> accumulatedMs
        else -> 0L
    }

    fun reset() {
        phase = Phase.IDLE; countdownLeft = 0; micMuted = false; srcMuted = false; scene = null
        micLevel = 0; srcLevel = 0; micAlive = false
    }
}
