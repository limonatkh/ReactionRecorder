package com.reactionrecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import kotlin.math.max

/** الإعدادات الفعلية بعد مطابقتها لقدرات ترميز الجهاز */
data class ResolvedVideo(val w: Int, val h: Int, val fps: Int, val mime: String, val bitrate: Int, val notes: List<String>)

object CodecSupport {
    private fun findEncoder(mime: String): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                !ci.name.startsWith("c2.android.") && !ci.name.startsWith("OMX.google.") // نفضّل المُرمِّز العتادي
        } ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    fun hevcSupported(): Boolean = findEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC) != null

    fun encoderName(mime: String): String? = findEncoder(mime)?.name

    /** يختار أقرب إعداد مدعوم: يخفّض الـ FPS ثم الدقة إذا لزم، ويحاذي الأبعاد */
    fun resolve(cfg: RecordConfig): ResolvedVideo {
        val notes = ArrayList<String>()
        var mime = if (cfg.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        if (cfg.hevc && !hevcSupported()) { mime = MediaFormat.MIMETYPE_VIDEO_AVC; notes += "H.265 غير مدعوم → H.264" }
        val info = findEncoder(mime)
        var w = cfg.outW
        var h = cfg.outH
        var fps = cfg.fps
        val vc = try { info?.getCapabilitiesForType(mime)?.videoCapabilities } catch (_: Throwable) { null }
        if (vc != null) {
            val wa = max(vc.widthAlignment, 2)
            val ha = max(vc.heightAlignment, 2)
            fun align(v: Int, a: Int) = (v + a - 1) / a * a
            w = align(w, wa); h = align(h, ha)
            if (!vc.isSizeSupported(w, h)) {
                // نهبط إلى 720p
                val s = 720
                val nw = if (cfg.portrait) s else s * 16 / 9 / 2 * 2
                val nh = if (cfg.portrait) s * 16 / 9 / 2 * 2 else s
                w = align(nw, wa); h = align(nh, ha)
                notes += "الدقة المطلوبة غير مدعومة → 720p"
            }
            if (!vc.areSizeAndRateSupported(w, h, fps.toDouble())) {
                val fallback = listOf(30, 24).firstOrNull { it < fps && vc.areSizeAndRateSupported(w, h, it.toDouble()) } ?: 24
                notes += "${fps}fps غير مستقر على هذا الجهاز → ${fallback}fps"
                fps = fallback
            }
        }
        var br = cfg.effectiveBitrate()
        val br2 = try { info?.getCapabilitiesForType(mime)?.videoCapabilities?.bitrateRange } catch (_: Throwable) { null }
        if (br2 != null) br = br.coerceIn(br2.lower, br2.upper)
        return ResolvedVideo(w, h, fps, mime, br, notes)
    }
}

/** مُرمِّز الفيديو: يستقبل الإطارات من الـ Compositor عبر Surface ويكتبها إلى الـ muxer */
class VideoEncoder(private val rv: ResolvedVideo, private val muxer: MuxerHolder) {
    private lateinit var codec: MediaCodec
    lateinit var inputSurface: Surface
        private set
    private var track = -1
    private var drainThread: Thread? = null

    fun prepare() {
        val fmt = MediaFormat.createVideoFormat(rv.mime, rv.w, rv.h)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, rv.bitrate)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, rv.fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
        val name = CodecSupport.encoderName(rv.mime)
        codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(rv.mime)
        try {
            val caps = codec.codecInfo.getCapabilitiesForType(rv.mime).encoderCapabilities
            val mode = if (caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
        } catch (_: Throwable) {}
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        codec.start()
        drainThread = Thread({ drain() }, "video-drain").also { it.start() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = muxer.addTrack(codec.outputFormat)
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buf != null && info.size > 0 && track >= 0) muxer.write(track, buf, info)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** يستدعى بعد إيقاف الرسم: يرسل EOS وينتظر تفريغ كل الإطارات */
    fun stop() {
        try { codec.signalEndOfInputStream() } catch (_: Throwable) {}
        drainThread?.join(8000)
        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
        try { inputSurface.release() } catch (_: Throwable) {}
    }
}

/** مُرمِّز AAC-LC ستيريو 44.1kHz */
class AudioEncoder(private val muxer: MuxerHolder, private val bitrate: Int = 128_000) {
    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
    }

    private lateinit var codec: MediaCodec
    private var track = -1
    private var drainThread: Thread? = null

    fun start() {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        drainThread = Thread({ drain() }, "audio-drain").also { it.start() }
    }

    /** يُغذّي المُرمِّز بـ PCM 16-bit مُتداخل (interleaved) */
    fun encode(pcm: ShortArray, ptsUs: Long) {
        val idx = codec.dequeueInputBuffer(20_000)
        if (idx < 0) return // نُسقط الكتلة بدل تعطيل الخيط
        val buf = codec.getInputBuffer(idx) ?: return
        buf.clear()
        val bytes = pcm.size * 2
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        codec.queueInputBuffer(idx, 0, bytes, ptsUs, 0)
    }

    fun finish(lastPtsUs: Long) {
        try {
            val idx = codec.dequeueInputBuffer(50_000)
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, lastPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } catch (_: Throwable) {}
        drainThread?.join(5000)
        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = muxer.addTrack(codec.outputFormat)
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buf != null && info.size > 0 && track >= 0) muxer.write(track, buf, info)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }
}
