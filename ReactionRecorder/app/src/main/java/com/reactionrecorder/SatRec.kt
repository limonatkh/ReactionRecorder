package com.reactionrecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * تسجيل الصوت محليًا على الجهاز المساعد: WAV بجودة كاملة (44.1kHz، 16-bit، أحادي) بدون ضغط.
 * يحفظ أزواج (رقم الإطار ← زمن النظام) من AudioRecord.getTimestamp كي نعرف بدقة متى التُقطت كل عيّنة.
 */
class SatAudioRec(private val ctx: Context, private val file: File, private val srcType: Int, @Volatile var gain: Float) {
    /** مستمع لمعاينة الصوت: يستلم الكتلة بعد تطبيق الكسب (يُستدعى من خيط التسجيل، لا يجوز أن يطول) */
    @Volatile var tap: ((ShortArray, Int) -> Unit)? = null
    val rate = 44100
    @Volatile var level = 0
    @Volatile var running = false
    @Volatile var error = ""
    var f0 = -1L
    var t0 = 0L
    var f1 = -1L
    var t1 = 0L
    var tsOk = false
    var fallbackStart = 0L
    val frames: Long get() = writer?.frames ?: 0L

    private var rec: AudioRecord? = null
    private var th: Thread? = null
    private var writer: WavWriter? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        try {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val order = when (srcType) {
                MediaRecorder.AudioSource.UNPROCESSED -> intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)
                MediaRecorder.AudioSource.VOICE_COMMUNICATION -> intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
                else -> intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.CAMCORDER)
            }
            var found: AudioRecord? = null
            for (st in order) {
                val c = try {
                    AudioRecord(st, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 8192) * 4)
                } catch (_: Throwable) { null }
                if (c != null && c.state == AudioRecord.STATE_INITIALIZED) { found = c; break }
                try { c?.release() } catch (_: Throwable) {}
            }
            val r: AudioRecord = found ?: run { error = "Microphone unavailable (permission or in use)"; return false }
            try {
                val dev = AudioEngine.choose(ctx, "auto")
                if (dev != null) r.setPreferredDevice(dev)
            } catch (_: Throwable) {}
            // «صوتي بس»: تنظيف الضجة + سماع من جهة الشخص (إذا الجوال بيدعم)
            if (srcType == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(r.audioSessionId)?.enabled = true } catch (_: Throwable) {}
                if (android.os.Build.VERSION.SDK_INT >= 29) try { r.setPreferredMicrophoneDirection(android.media.MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER) } catch (_: Throwable) {}
            } else if (srcType == MediaRecorder.AudioSource.UNPROCESSED && android.os.Build.VERSION.SDK_INT >= 29) {
                try { r.setPreferredMicrophoneFieldDimension(-1f) } catch (_: Throwable) {}
            }
            writer = WavWriter(file, rate, 1)
            rec = r
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error = "Android refused to start the microphone"
                try { r.release() } catch (_: Throwable) {}
                rec = null
                return false
            }
            running = true
            th = Thread({ loop(r) }, "sat-audio").also { it.start() }
            return true
        } catch (t: Throwable) {
            error = t.javaClass.simpleName
            return false
        }
    }

    private fun loop(r: AudioRecord) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(2048)
        val ts = AudioTimestamp()
        var total = 0L
        var lastTs = 0L
        var lvl = 0f
        while (running) {
            val n = r.read(buf, 0, buf.size)
            if (n < 0) break
            if (n == 0) continue
            val nowE = SystemClock.elapsedRealtimeNanos()
            if (total == 0L) fallbackStart = nowE - n * 1_000_000_000L / rate
            var peak = 0
            val g = gain
            if (g != 1f) {
                for (i in 0 until n) {
                    val v = buf[i] * g
                    buf[i] = if (v > 32767f) Short.MAX_VALUE else if (v < -32768f) Short.MIN_VALUE else v.toInt().toShort()
                }
            }
            for (i in 0 until n) {
                val a = abs(buf[i].toInt())
                if (a > peak) peak = a
            }
            writer?.write(buf, n)
            tap?.let { try { it(buf, n) } catch (_: Throwable) {} }
            total += n
            lvl = max(peak.toFloat(), lvl * 0.9f)
            level = lvl.toInt()
            if (nowE - lastTs > 700_000_000L) {
                lastTs = nowE
                try {
                    if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        val fp = ts.framePosition
                        if (abs(fp - total) <= rate) {
                            if (f0 < 0L) { f0 = fp; t0 = ts.nanoTime }
                            f1 = fp; t1 = ts.nanoTime
                            tsOk = true
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun stop() {
        running = false
        try { th?.join(2000) } catch (_: Throwable) {}
        try { rec?.stop() } catch (_: Throwable) {}
        try { rec?.release() } catch (_: Throwable) {}
        rec = null
        writer?.finish()
        level = 0
    }
}

/**
 * تسجيل الفيديو محليًا على الجهاز المساعد: نفس مسار التطبيق (Compositor ← ترميز H.264 ← MP4)،
 * لكن الكاميرا فقط بملء الإطار. زمن الصفر في الملف = [startElapsedNs] على ساعة هذا الجهاز.
 */
class SatVideoRec(private val file: File, private val w: Int, private val h: Int, private val fps: Int, private val bitrate: Int) {
    lateinit var compositor: Compositor
        private set
    private lateinit var video: VideoEncoder
    private lateinit var holder: MuxerHolder
    var startElapsedNs = 0L
        private set
    var startedAtNs = 0L
        private set

    fun prepare() {
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        holder = MuxerHolder(muxer, 1)
        val rv = ResolvedVideo(w, h, fps, MediaFormat.MIMETYPE_VIDEO_AVC, bitrate, emptyList())
        video = VideoEncoder(rv, holder)
        video.prepare()
        compositor = Compositor(w, h, fps, video.inputSurface).also {
            it.layout = LayoutConfig(NRect(0f, 0f, 1f, 1f), NRect(0f, 0f, 1f, 1f), true, CamShape.RECT, 0f, 0f)
            it.setup(w, h)
        }
        video.start()
    }

    fun begin() {
        startedAtNs = System.nanoTime()
        startElapsedNs = SystemClock.elapsedRealtimeNanos()
        compositor.begin(startedAtNs)
    }

    val width: Int get() = w
    val height: Int get() = h

    /** يعيد true إذا خرج ملف صالح */
    fun stop(): Boolean {
        try { compositor.stopDrawing() } catch (_: Throwable) {}
        try { video.stop() } catch (_: Throwable) {}
        val ok = try { holder.finish() } catch (_: Throwable) { false }
        try { compositor.release() } catch (_: Throwable) {}
        return ok
    }
}


/**
 * ميكروفون مراقبة خفيف (11025Hz أحادي) للاستماع من الجهاز الرئيسي قبل بدء التسجيل.
 * يتوقف عند بدء التسجيل (يتولّى [SatAudioRec] إرسال الصوت وقتها).
 */
class SatMonitorMic(private val onChunk: (ShortArray, Int) -> Unit, private val onLevel: (Int) -> Unit) {
    @Volatile private var running = false
    private var rec: AudioRecord? = null
    private var th: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        return try {
            val rate = 11025
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 4096) * 2)
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return false }
            rec = r
            running = true
            th = Thread({
                val buf = ShortArray(441)
                var lvl = 0f
                while (running) {
                    val n = r.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    var peak = 0
                    for (i in 0 until n) { val a = abs(buf[i].toInt()); if (a > peak) peak = a }
                    lvl = max(peak.toFloat(), lvl * 0.9f)
                    onLevel(lvl.toInt())
                    onChunk(buf, n)
                }
            }, "sat-monitor").also { it.start() }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun stop() {
        running = false
        try { th?.join(1000) } catch (_: Throwable) {}
        try { rec?.stop() } catch (_: Throwable) {}
        try { rec?.release() } catch (_: Throwable) {}
        rec = null
        onLevel(0)
    }
}
