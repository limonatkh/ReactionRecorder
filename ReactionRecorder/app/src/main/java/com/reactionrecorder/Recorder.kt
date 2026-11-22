package com.reactionrecorder

import android.content.Context
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * ينسّق كل أجزاء الخط:
 *   Compositor (GL) → VideoEncoder (Surface) ┐
 *   AudioEngine → AudioEncoder (AAC)          ├→ MuxerHolder → MediaStore (Movies/ReactionRecorder)
 */
class Recorder(
    private val ctx: Context,
    private val cfg: RecordConfig,
    private val projection: MediaProjection?,
    private val sourceW: Int,
    private val sourceH: Int,
    /** صوت المشغّل الداخلي في وضع Video file (null في وضع تصوير الشاشة) */
    private val feed: PlayerAudioFeed? = null,
    /** إن وُجد: نكتب WAV للميكروفون والمصدر هنا لاستخدامها في دمج الأجهزة المساعدة */
    private val sessionDir: java.io.File? = null,
) {
    /** زمن بداية التسجيل على ساعة elapsedRealtime (نفس صفر الـ PTS) */
    @Volatile var startElapsedNs = 0L
        private set
    @Volatile var endElapsedNs = 0L
        private set
    /** فترات الإيقاف المؤقت [بداية، مدة] بالنانو ثانية (elapsed) */
    val pauses = ArrayList<LongArray>()
    private var pauseAt = 0L
    var wavOk = false
        private set

    lateinit var compositor: Compositor
        private set
    lateinit var resolved: ResolvedVideo
        private set
    var audio: AudioEngine? = null
        private set
    val notes = ArrayList<String>()

    private lateinit var video: VideoEncoder
    private lateinit var holder: MuxerHolder
    private var pfd: ParcelFileDescriptor? = null
    var uri: Uri? = null
        private set
    private var wantAudio = false
    private var startNs = 0L

    /** ينشئ الملف والمُرمِّز والـ Compositor. بعدها نوصل الكاميرا والمصدر ثم نستدعي [begin]. */
    fun prepare() {
        resolved = CodecSupport.resolve(cfg)
        notes.addAll(resolved.notes)

        uri = StorageUtil.createPending(ctx, StorageUtil.newName()) ?: error("MediaStore insert failed")
        pfd = ctx.contentResolver.openFileDescriptor(uri!!, "rw") ?: error("cannot open output file")
        val muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        wantAudio = cfg.micOn || (cfg.srcAudioOn && (projection != null || feed != null))
        holder = MuxerHolder(muxer, if (wantAudio) 2 else 1)

        video = VideoEncoder(resolved, holder)
        video.prepare()

        compositor = Compositor(resolved.w, resolved.h, resolved.fps, video.inputSurface).also {
            it.layout = cfg.layout
            it.sourceFit = cfg.sourceFit
            it.setCameraMirror(0, cfg.frontCamera && cfg.mirrorFront)
            it.cameraAdjust = cfg.camRotAdjust
            it.setKey(cfg.keyMode, cfg.keyColor, cfg.keyTol, cfg.keySoft)
            it.setup(sourceW, sourceH)
        }
        video.start()
    }

    /** يبدأ الرسم والصوت معًا بنفس الساعة المرجعية */
    fun begin() {
        startNs = System.nanoTime()
        startElapsedNs = android.os.SystemClock.elapsedRealtimeNanos()
        if (wantAudio) {
            try {
                val enc = AudioEncoder(holder)
                var mw: WavWriter? = null
                var sw: WavWriter? = null
                if (sessionDir != null) {
                    try {
                        mw = WavWriter(java.io.File(sessionDir, "main_mic.wav"), AudioEngine.RATE, 1)
                        sw = WavWriter(java.io.File(sessionDir, "main_src.wav"), AudioEngine.RATE, 2)
                    } catch (_: Throwable) { mw = null; sw = null }
                }
                val engine = AudioEngine(
                    micEnabled = cfg.micOn, sourceEnabled = cfg.srcAudioOn, projection = projection,
                    echoSuppress = cfg.echoSuppress, encoder = enc, startNs = startNs,
                    pausedNanos = { compositor.pausedNanos() },
                    feed = feed, feedDelayMs = cfg.videoAudioDelayMs,
                    ctx = ctx, micPref = cfg.micSource,
                    micWav = mw, srcWav = sw,
                )
                wavOk = mw != null && sw != null
                engine.open()
                if (engine.hasMic || engine.hasSource || engine.hasFeed) {
                    engine.micVol = cfg.micVol
                    engine.srcVol = cfg.srcVol
                    enc.start()
                    engine.go()
                    audio = engine
                } else {
                    holder.lowerExpectedTracks(1)
                    notes += "No audio source available — recording video only"
                }
                if (cfg.srcAudioOn && (projection != null || feed != null) && engine.sourceStatus.isNotEmpty()) notes += engine.sourceStatus
                if (cfg.micOn && engine.micStatus.isNotEmpty() && (!engine.hasMic || cfg.micSource != "auto" || engine.micStatus != "Microphone: OK")) notes += engine.micStatus
            } catch (t: Throwable) {
                holder.lowerExpectedTracks(1)
                notes += "Audio failed: ${t.javaClass.simpleName}"
            }
        }
        compositor.begin(startNs)
    }

    fun pause() {
        pauseAt = android.os.SystemClock.elapsedRealtimeNanos()
        compositor.pause(); audio?.paused = true
    }
    fun resume() {
        compositor.resume(); audio?.paused = false
        if (pauseAt != 0L) {
            pauses.add(longArrayOf(pauseAt, android.os.SystemClock.elapsedRealtimeNanos() - pauseAt))
            pauseAt = 0L
        }
    }
    fun setMicMuted(m: Boolean) { audio?.micMuted = m }
    fun setSrcMuted(m: Boolean) { audio?.srcMuted = m }

    /** يُنهي كل شيء. يُستدعى من خيط خلفي. يعيد Uri الفيديو الجاهز أو null عند الفشل. */
    fun stop(): Uri? {
        endElapsedNs = android.os.SystemClock.elapsedRealtimeNanos()
        if (pauseAt != 0L) {
            pauses.add(longArrayOf(pauseAt, endElapsedNs - pauseAt))
            pauseAt = 0L
        }
        try { compositor.stopDrawing() } catch (_: Throwable) {}
        try { audio?.stop() } catch (_: Throwable) {}
        try { video.stop() } catch (_: Throwable) {}
        val ok = try { holder.finish() } catch (_: Throwable) { false }
        try { pfd?.close() } catch (_: Throwable) {}
        try { compositor.release() } catch (_: Throwable) {}
        val u = uri
        return if (ok && u != null) {
            StorageUtil.publish(ctx, u); u
        } else {
            if (u != null) StorageUtil.delete(ctx, u)
            null
        }
    }

    /** تنظيف عند فشل prepare/begin */
    fun abort() {
        try { compositor.stopDrawing() } catch (_: Throwable) {}
        try { video.stop() } catch (_: Throwable) {}
        try { holder.finish() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Throwable) {}
        try { compositor.release() } catch (_: Throwable) {}
        uri?.let { StorageUtil.delete(ctx, it) }
    }
}
