package com.reactionrecorder

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * مخزن دائري لصوت المشغّل (PCM ستيريو 16-bit بتردد 44.1kHz). يستقبل الصوت من ExoPlayer ويقرؤه الـ Mixer
 * بإيقاع الزمن الحقيقي. بدء الاستهلاك مع بدء التشغيل يجعل الصوت المسجَّل متزامنًا مع الصورة؛
 * والتأخير الصغير (delay) يعادل تأخر خرج السماعة.
 */
class PlayerAudioFeed {
    private val cap = AudioEngine.RATE * 2 * 4 // 4 ثوانٍ ستيريو
    private val ring = ShortArray(cap)
    private var r = 0
    private var w = 0
    private var count = 0

    private var inRate = AudioEngine.RATE
    private var inCh = 2
    private var inEnc = C.ENCODING_PCM_16BIT
    private var supported = true
    private var step = 1.0
    private var frac = 0.0
    private var prevL = 0f
    private var prevR = 0f
    private var havePrev = false

    /** لا نخزّن شيئًا قبل بدء التسجيل فعليًا */
    @Volatile var active = false
    @Volatile var unsupportedFormat = false
        private set

    private var delayMs = 0

    @Synchronized
    fun reset(delayMs: Int) {
        this.delayMs = delayMs
        clearAndPrefill()
    }

    private fun clearAndPrefill() {
        r = 0; w = 0; count = 0
        havePrev = false; frac = 0.0
        val frames = (delayMs.coerceIn(0, 800) * AudioEngine.RATE / 1000)
        // صمت أولي = تأخير ثابت لصوت الفيديو
        val shorts = (frames * 2).coerceAtMost(cap - 2)
        java.util.Arrays.fill(ring, 0, shorts, 0.toShort())
        w = shorts % cap; count = shorts
    }

    @Synchronized
    fun onFormat(rate: Int, channels: Int, encoding: Int) {
        inRate = if (rate > 0) rate else AudioEngine.RATE
        inCh = max1(channels)
        inEnc = encoding
        supported = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT
        unsupportedFormat = !supported
        step = inRate.toDouble() / AudioEngine.RATE
        frac = 0.0
        havePrev = false
        // كل flush = انقطاع في التشغيل (قفزة أو عنصر جديد): نمسح الصوت القديم كي لا يتسرّب ذيله
        if (active) clearAndPrefill()
    }

    private fun max1(v: Int) = if (v < 1) 1 else v

    @Synchronized
    fun write(src: ByteBuffer) {
        if (!active || !supported) return
        val b = src.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPer = if (inEnc == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = inCh * bytesPer
        var frames = b.remaining() / frameBytes
        var pos = b.position()
        while (frames-- > 0) {
            val l: Float
            val rr: Float
            if (inEnc == C.ENCODING_PCM_FLOAT) {
                l = b.getFloat(pos)
                rr = if (inCh >= 2) b.getFloat(pos + 4) else l
            } else {
                l = b.getShort(pos) / 32768f
                rr = if (inCh >= 2) b.getShort(pos + 2) / 32768f else l
            }
            pos += frameBytes
            if (!havePrev) { prevL = l; prevR = rr; havePrev = true }
            while (frac < 1.0) {
                val f = frac.toFloat()
                push(prevL + (l - prevL) * f, prevR + (rr - prevR) * f)
                frac += step
            }
            frac -= 1.0
            prevL = l; prevR = rr
        }
    }

    private fun push(l: Float, rr: Float) {
        put(toShort(l)); put(toShort(rr))
    }

    private fun toShort(v: Float): Short {
        val x = v * 32767f
        return if (x > 32767f) Short.MAX_VALUE else if (x < -32768f) Short.MIN_VALUE else x.toInt().toShort()
    }

    private fun put(v: Short) {
        if (count >= cap) { r = (r + 1) % cap; count-- } // نُسقط الأقدم عند الامتلاء
        ring[w] = v
        w = (w + 1) % cap
        count++
    }

    /** يقرأ n عيّنة (short) ويملأ الباقي صمتًا إذا نفد المخزن */
    @Synchronized
    fun read(dst: ShortArray, n: Int) {
        val take = if (count < n) count else n
        for (i in 0 until take) {
            dst[i] = ring[r]
            r = (r + 1) % cap
        }
        count -= take
        for (i in take until n) dst[i] = 0
    }
}

/** معلومات الفيديو (الأبعاد بعد التدوير + مصغّرة للمعاينة) */
object VideoProbe {
    class Info(val width: Int, val height: Int, val durationMs: Long, val thumb: Bitmap?)

    fun probe(ctx: Context, uriStr: String, wantThumb: Boolean): Info? {
        val mmr = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(uriStr)
            if (uri.scheme == "http" || uri.scheme == "https") mmr.setDataSource(uriStr, HashMap<String, String>())
            else mmr.setDataSource(ctx, uri)
            var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var thumb: Bitmap? = null
            if (wantThumb) {
                val full = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (full != null) {
                    val s = 480f / maxOf(full.width, full.height)
                    thumb = if (s < 1f) Bitmap.createScaledBitmap(full, (full.width * s).toInt().coerceAtLeast(1), (full.height * s).toInt().coerceAtLeast(1), true) else full
                }
            }
            if (w <= 0 || h <= 0) null else Info(w, h, dur, thumb)
        } catch (_: Throwable) {
            null
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
        }
    }
}

/**
 * مشغّل الفيديو المصدر (ExoPlayer): يرسم الفيديو مباشرة على سطح الـ Compositor (GPU) بدل تصوير الشاشة،
 * ويمرّر صوته (PCM) إلى [PlayerAudioFeed] لخلطه مع الميكروفون. يُستعمل من الخيط الرئيسي فقط.
 */
@OptIn(UnstableApi::class)
class SourcePlayer(private val ctx: Context, private val feed: PlayerAudioFeed) {
    var onReady: (() -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onVideoSize: ((Int, Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var player: ExoPlayer? = null
    private var readyFired = false

    /** ينشئ المشغّل ويربطه بسطح الـ Compositor. ثم تُحمَّل العناصر بـ [load]. */
    fun init(surface: Surface, silent: Boolean) {
        val tee = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
            override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                feed.onFormat(sampleRateHz, channelCount, encoding)
            }

            override fun handleBuffer(buffer: ByteBuffer) {
                feed.write(buffer)
            }
        })
        val factory = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(tee))
                    .build()
            }
        }
        val p = ExoPlayer.Builder(ctx, factory).build()
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !readyFired) { readyFired = true; onReady?.invoke() }
                if (state == Player.STATE_ENDED) onEnded?.invoke()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val w = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                    onVideoSize?.invoke(w, videoSize.height)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onError?.invoke(error.errorCodeName)
            }
        })
        p.setVideoSurface(surface)
        p.volume = if (silent) 0f else 1f
    }

    /** يحمّل فيديو جديدًا (يستبدل الحالي). onReady يُطلَق عند أول جاهزية بعد كل تحميل */
    fun load(uri: Uri, play: Boolean, startMs: Long = 0L) {
        val p = player ?: return
        readyFired = false
        p.setMediaItem(MediaItem.fromUri(uri), startMs.coerceAtLeast(0L))
        p.playWhenReady = play
        p.prepare()
    }

    fun start() { player?.playWhenReady = true }
    fun pause() { player?.playWhenReady = false }
    fun resume() { player?.playWhenReady = true }

    /** نية المستخدم: هل الفيديو مُشغَّل أم موقوف */
    fun wantsPlay(): Boolean = player?.playWhenReady ?: false
    fun positionMs(): Long = player?.currentPosition ?: 0L
    fun durationMs(): Long {
        val d = player?.duration ?: C.TIME_UNSET
        return if (d == C.TIME_UNSET || d < 0) 0L else d
    }
    fun seekTo(ms: Long) { player?.seekTo(ms.coerceAtLeast(0L)) }
    fun setLoop(on: Boolean) { player?.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    /** صامت = لا يخرج من السماعة، لكن الصوت يصل إلى المسجِّل نظيفًا (مفتاح منع الصدى) */
    fun setSilent(on: Boolean) { player?.volume = if (on) 0f else 1f }

    fun release() {
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
    }
}
