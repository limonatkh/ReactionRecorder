package com.reactionrecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * خط الصوت: ميكروفون + صوت التطبيقات الأخرى (AudioPlaybackCapture) → خلط → AAC.
 *
 *  mic thread ──► micQ ─┐
 *                        ├─► mixer thread ──► AudioEncoder ──► MuxerHolder
 *  src thread ──► srcQ ─┘
 *
 * ملاحظات مهمة:
 *  - AudioPlaybackCapture رسمي من Android 10. بعض التطبيقات تمنع التقاط صوتها (أو المحتوى المحمي بـ DRM)،
 *    وفي هذه الحالة يصل الصوت صامتًا — لا نحاول تجاوز ذلك. الحالة تظهر في [sourceStatus].
 *  - الميكروفون يلتقط صوت السماعة أيضًا (صدى). الحل الأفضل: سماعات رأس. خيار "Echo suppression"
 *    يستعمل مصدر VOICE_COMMUNICATION مع AEC/NS إذا وفّرها الجهاز.
 *  - كل الأوقات (PTS) مرتبطة بنفس ساعة النظام التي يستعملها الفيديو، مع استبعاد مدة الإيقاف المؤقت.
 */
class AudioEngine(
    private val micEnabled: Boolean,
    private val sourceEnabled: Boolean,
    private val projection: MediaProjection?,
    private val echoSuppress: Boolean,
    private val encoder: AudioEncoder,
    private val startNs: Long,
    private val pausedNanos: () -> Long,
    /** صوت المشغّل الداخلي (وضع Video file). إن وُجد لا نحتاج MediaProjection لصوت المصدر */
    private val feed: PlayerAudioFeed? = null,
    private val feedDelayMs: Int = 0,
    /** لاختيار مصدر الميكروفون: auto (USB أولًا) / builtin / usb */
    private val ctx: Context? = null,
    private val micPref: String = "auto",
    /** نسخ WAV منفصلة للدمج لاحقًا مع أجهزة مساعدة: ميكروفون (أحادي) ومصدر (ستيريو)، محاذاة لزمن الفيديو، قبل التحكم بالمستوى */
    private val micWav: WavWriter? = null,
    private val srcWav: WavWriter? = null,
) {
    private class Chunk(val data: ShortArray, val endNs: Long)

    @Volatile var micMuted = false
    /** كتم صوت المصدر (الفيديو أو الشاشة) أثناء التسجيل */
    @Volatile var srcMuted = false
    @Volatile var micVol = 1f
    @Volatile var srcVol = 1f
    @Volatile var paused = false

    /** نص يُعرض للمستخدم عن حالة التقاط صوت المصدر */
    @Volatile var sourceStatus: String = ""
        private set
    @Volatile var micStatus: String = ""
        private set
    val hasMic get() = mic != null
    val hasSource get() = src != null
    val hasFeed get() = feed != null && sourceEnabled

    private var mic: AudioRecord? = null
    private var src: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    private val micQ = LinkedBlockingQueue<Chunk>(80)
    private val srcQ = LinkedBlockingQueue<Chunk>(80)
    private val tickQ = LinkedBlockingQueue<Chunk>(80)
    @Volatile private var running = false
    private val threads = ArrayList<Thread>()
    @Volatile private var lastPtsUs = 0L

    companion object {
        const val RATE = AudioEncoder.SAMPLE_RATE
        const val FRAMES = 1024 // كتلة واحدة = 1024 إطارًا ≈ 23ms

        /** ميكروفونات خارجية (USB أو سماعة سلكية بميكروفون) */
        fun isExternal(d: AudioDeviceInfo): Boolean =
            d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET

        fun isUsb(d: AudioDeviceInfo): Boolean =
            d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET

        /** كل أجهزة الإدخال المتصلة الآن (للعرض في الإعدادات) */
        fun inputDevices(c: Context): List<AudioDeviceInfo> = try {
            (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        } catch (_: Throwable) {
            emptyList()
        }

        fun label(d: AudioDeviceInfo): String {
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                else -> "Input"
            }
            val name = d.productName?.toString().orEmpty()
            return if (name.isNotBlank()) "$kind – $name" else kind
        }

        /** يختار جهاز الإدخال بحسب التفضيل. null = ندع النظام يقرّر */
        fun choose(c: Context, pref: String): AudioDeviceInfo? {
            val all = inputDevices(c)
            val usb = all.firstOrNull { isUsb(it) } ?: all.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            val builtin = all.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            return when (pref) {
                "builtin" -> builtin
                "usb" -> usb
                else -> usb // تلقائي: نفرض الخارجي فقط إن وُجد، وإلا يقرّر النظام (أضمن)
            }
        }
    }

    /** ينشئ كائنات AudioRecord فقط (بدون بدء الالتقاط). الجودة/التوفّر يظهران في micStatus/sourceStatus */
    @SuppressLint("MissingPermission")
    fun open() {
        if (micEnabled) {
            try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val order = if (echoSuppress) intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
                else intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.CAMCORDER)
                var r: AudioRecord? = null
                for (st in order) {
                    val c = try { AudioRecord(st, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, FRAMES * 2) * 4) } catch (_: Throwable) { null }
                    if (c != null && c.state == AudioRecord.STATE_INITIALIZED) { r = c; break }
                    try { c?.release() } catch (_: Throwable) {}
                }
                if (r != null) {
                    mic = r
                    var route = ""
                    if (ctx != null) {
                        val dev = choose(ctx, micPref)
                        if (dev != null) {
                            val ok = try { r.setPreferredDevice(dev) } catch (_: Throwable) { false }
                            route = if (ok) label(dev) else ""
                        } else if (micPref == "usb") {
                            route = "USB mic not found — using default"
                        }
                    }
                    if (echoSuppress) {
                        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
                        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true }
                    }
                    micStatus = if (route.isNotEmpty()) "Microphone: $route" else "Microphone: OK"
                } else {
                    micStatus = "Microphone: unavailable (permission or in use by another app)"
                }
            } catch (t: Throwable) {
                micStatus = "Microphone: failed (${t.javaClass.simpleName})"
            }
        }
        if (sourceEnabled) {
            if (feed != null) {
                sourceStatus = "Video audio: OK"
            } else if (projection == null) {
                sourceStatus = "Source audio: needs screen capture"
            } else {
                try {
                    val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    val fmt = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                    val r = AudioRecord.Builder()
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(FRAMES * 4 * 8)
                        .setAudioPlaybackCaptureConfig(cfg)
                        .build()
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        src = r
                        sourceStatus = "Source audio: capturing (apps that block capture stay silent)"
                    } else {
                        r.release(); sourceStatus = "Source audio: Android refused capture"
                    }
                } catch (t: Throwable) {
                    sourceStatus = "Source audio: not available (${t.javaClass.simpleName})"
                }
            }
        }
    }

    /** يبدأ الالتقاط والخلط. يُستدعى بعد تشغيل مُرمِّز AAC */
    fun go() {
        running = true
        if (feed != null) { feed.reset(feedDelayMs); feed.active = true }
        mic?.let { r ->
            try { r.startRecording() } catch (_: Throwable) {}
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) micStatus = "Microphone: Android refused to start it"
            threads.add(Thread({ readLoop(r, micQ, mono = true) }, "mic-read"))
        }
        src?.let { r -> r.startRecording(); threads.add(Thread({ readLoop(r, srcQ, mono = false) }, "src-read")) }
        // الساعة الرئيسية دائمًا: لو تعطّل الميكروفون يبقى صوت الفيديو يُسجَّل
        threads.add(Thread({ tickLoop() }, "audio-tick"))
        threads.add(Thread({ mixLoop() }, "audio-mix"))
        threads.forEach { it.start() }
    }

    private fun readLoop(r: AudioRecord, q: LinkedBlockingQueue<Chunk>, mono: Boolean) {
        val n = if (mono) FRAMES else FRAMES * 2
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            val buf = ShortArray(n)
            var got = 0
            while (got < n && running) {
                val rd = r.read(buf, got, n - got) // blocking
                if (rd < 0) return
                got += rd
            }
            if (got < n) return
            val data = if (mono) {
                val st = ShortArray(FRAMES * 2)
                for (i in 0 until FRAMES) { st[i * 2] = buf[i]; st[i * 2 + 1] = buf[i] }
                st
            } else buf
            if (mono) RecorderState.micAlive = true
            if (!q.offer(Chunk(data, System.nanoTime()))) { q.poll(); q.offer(Chunk(data, System.nanoTime())) }
        }
    }

    /** مصدر توقيت بإيقاع الزمن الحقيقي (كتل صامتة) لحالة: لا ميكروفون ولا التقاط → فقط صوت المشغّل */
    private fun tickLoop() {
        val chunkNs = FRAMES * 1_000_000_000L / RATE
        var next = System.nanoTime() + chunkNs
        val silence = ShortArray(FRAMES * 2)
        while (running) {
            val now = System.nanoTime()
            if (next > now) {
                try { Thread.sleep((next - now) / 1_000_000L, ((next - now) % 1_000_000L).toInt()) } catch (_: InterruptedException) { return }
            }
            next += chunkNs
            if (!tickQ.offer(Chunk(silence, System.nanoTime()))) { tickQ.poll(); tickQ.offer(Chunk(silence, System.nanoTime())) }
        }
    }

    private fun mixLoop() {
        // المرجع الزمني: ساعة ثابتة (كتلة كل ~23ms). الميكروفون والتقاط الصوت يُخلطان إن توفّرا
        val master = tickQ
        val chunkUs = FRAMES * 1_000_000L / RATE
        var framesOut = 0L
        var anchorUs = Long.MIN_VALUE
        val out = ShortArray(FRAMES * 2)
        val feedBuf = ShortArray(FRAMES * 2)
        val silence = ShortArray(FRAMES * 2)
        val monoTmp = ShortArray(FRAMES)
        val stTmp = ShortArray(FRAMES * 2)
        val useFeed = feed != null && sourceEnabled
        var micLvl = 0f
        var srcLvl = 0f
        while (running || master.isNotEmpty()) {
            val m = master.poll(100, TimeUnit.MILLISECONDS) ?: continue
            var mc: Chunk? = null
            if (mic != null) {
                while (micQ.size > 4) micQ.poll() // نحافظ على زمن تأخير صغير
                mc = micQ.poll()
            }
            var sc: Chunk? = null
            if (src != null) {
                while (srcQ.size > 4) srcQ.poll()
                sc = srcQ.poll()
            }
            if (paused) continue
            if (useFeed) feed!!.read(feedBuf, FRAMES * 2)

            val sv = if (srcMuted) 0f else srcVol
            val gm = if (micMuted) 0f else micVol
            val md = mc?.data ?: silence
            val sd = sc?.data ?: silence
            var pm = 0
            var ps = 0
            for (i in 0 until FRAMES * 2) {
                val mv = md[i].toInt()
                val fv = if (useFeed) feedBuf[i].toInt() else 0
                val sx = sd[i].toInt()
                val am = if (mv < 0) -mv else mv
                if (am > pm) pm = am
                val af = (if (fv < 0) -fv else fv) + (if (sx < 0) -sx else sx)
                if (af > ps) ps = af
                val v = mv * gm + sx * sv + fv * sv
                out[i] = if (v > 32767f) Short.MAX_VALUE else if (v < -32768f) Short.MIN_VALUE else v.toInt().toShort()
            }
            micLvl = max(pm.toFloat(), micLvl * 0.9f)
            srcLvl = max(ps.toFloat(), srcLvl * 0.9f)
            RecorderState.micLevel = micLvl.toInt()
            RecorderState.srcLevel = srcLvl.toInt()

            val wallUs = (m.endNs - startNs - pausedNanos()) / 1000L - chunkUs
            if (anchorUs == Long.MIN_VALUE) anchorUs = wallUs
            var pts = anchorUs + framesOut * 1_000_000L / RATE
            if (wallUs - pts > 40_000) { // تأخّر (فجوة) → نقفز للأمام ونُبقي التزامن
                anchorUs += wallUs - pts
                pts = wallUs
            }
            if (micWav != null || srcWav != null) {
                val fr = max(pts, 0L) * RATE / 1_000_000L
                if (micWav != null) {
                    for (i in 0 until FRAMES) monoTmp[i] = if (micMuted) 0 else md[i * 2]
                    micWav.padTo(fr)
                    micWav.write(monoTmp, FRAMES)
                }
                if (srcWav != null) {
                    for (i in 0 until FRAMES * 2) {
                        val v = if (srcMuted) 0 else ((if (useFeed) feedBuf[i].toInt() else 0) + sd[i].toInt())
                        stTmp[i] = if (v > 32767) Short.MAX_VALUE else if (v < -32768) Short.MIN_VALUE else v.toShort()
                    }
                    srcWav.padTo(fr)
                    srcWav.write(stTmp, FRAMES * 2)
                }
            }
            encoder.encode(out, max(pts, 0L))
            lastPtsUs = pts + chunkUs
            framesOut += FRAMES
        }
    }

    fun stop() {
        running = false
        feed?.active = false
        try { mic?.stop() } catch (_: Throwable) {}
        try { src?.stop() } catch (_: Throwable) {}
        threads.forEach { try { it.join(2000) } catch (_: Throwable) {} }
        try { aec?.release() } catch (_: Throwable) {}
        try { ns?.release() } catch (_: Throwable) {}
        try { mic?.release() } catch (_: Throwable) {}
        try { src?.release() } catch (_: Throwable) {}
        encoder.finish(lastPtsUs)
        val endFr = lastPtsUs * RATE / 1_000_000L
        micWav?.let { it.padTo(endFr); it.finish() }
        srcWav?.let { it.padTo(endFr); it.finish() }
    }
}
