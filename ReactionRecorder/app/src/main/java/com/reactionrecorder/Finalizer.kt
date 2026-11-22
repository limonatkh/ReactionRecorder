package com.reactionrecorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * دمج الجلسة: يخلط صوت الجهاز الرئيسي (ميكروفون + مصدر) مع صوت الأجهزة المساعدة بعد مواءمة الساعات،
 * ثم يعيد كتابة الفيديو الأصلي (بدون إعادة ترميز) مع المسار الصوتي الجديد.
 */
object Finalizer {
    const val RATE = 44100
    private const val BLOCK = 4096
    private const val SUB = 256

    class Result(val uri: Uri?, val file: File?, val error: String?, val warnings: List<String>)

    /** خط انحدار خطّي لفرق الساعة θ(m) = a + b·(m − mRef) */
    class ClockFit(samples: List<DevSample>) {
        var mRef = 0L
        var a = 0.0
        var b = 0.0
        val ok: Boolean

        init {
            if (samples.isEmpty()) {
                ok = false
            } else {
                val sorted = samples.sortedBy { it.rtt }
                val keep = max(min(3, sorted.size), sorted.size / 2)
                val use = sorted.take(keep)
                mRef = use.minOf { it.m }
                val n = use.size
                var sx = 0.0; var sy = 0.0
                for (u in use) { sx += (u.m - mRef).toDouble(); sy += u.theta.toDouble() }
                val mx = sx / n
                val my = sy / n
                var sxx = 0.0; var sxy = 0.0
                for (u in use) {
                    val dx = (u.m - mRef).toDouble() - mx
                    sxx += dx * dx
                    sxy += dx * (u.theta.toDouble() - my)
                }
                if (n >= 3 && sxx > 1e18) { // أكثر من ~ثانية تباعد زمني
                    b = (sxy / sxx).coerceIn(-1e-3, 1e-3)
                    a = my - b * mx
                } else {
                    b = 0.0
                    a = my
                }
                ok = true
            }
        }

        fun theta(m: Long): Double = a + b * (m - mRef).toDouble()
    }

    /** ربط رقم إطار في ملف WAV للجهاز المساعد بزمن ساعته */
    class AudioMap(o: JSONObject, wavRate: Int) {
        val tsOk = o.optBoolean("tsOk")
        val f0 = o.optLong("f0")
        val t0 = o.optLong("t0")
        val fb = o.optLong("fallbackStart")
        var eff = wavRate.toDouble()

        init {
            val f1 = o.optLong("f1")
            val t1 = o.optLong("t1")
            if (tsOk && f1 - f0 > wavRate * 3L && t1 > t0) {
                val r = (f1 - f0).toDouble() * 1e9 / (t1 - t0).toDouble()
                if (abs(r - wavRate) < wavRate * 0.005) eff = r
            }
        }

        fun nOf(satNs: Double): Double =
            if (tsOk) f0 + (satNs - t0.toDouble()) * eff / 1e9 else (satNs - fb.toDouble()) * eff / 1e9
    }

    private class PauseMap(val s: SessionInfo) {
        val ptsAt = LongArray(s.pauses.size)
        val dur = LongArray(s.pauses.size)

        init {
            var c = 0L
            for (i in s.pauses.indices) {
                ptsAt[i] = s.pauses[i][0] - s.startElapsedNs - c
                dur[i] = s.pauses[i][1]
                c += dur[i]
            }
        }

        /** من زمن الفيديو (ns بدون الإيقاف) إلى ساعة elapsed الحقيقية على الجهاز الرئيسي */
        fun toElapsed(ptsNs: Long): Long {
            var add = 0L
            for (i in ptsAt.indices) { if (ptsNs >= ptsAt[i]) add += dur[i] else break }
            return s.startElapsedNs + ptsNs + add
        }
    }

    private class Src(val dev: DevInfo, val rd: WavReader, val map: AudioMap, val fit: ClockFit)

    /** يحوّل زمن الفيديو الرئيسي إلى زمن داخل ملف فيديو الجهاز المساعد (بالميلي ثانية، قد يكون سالبًا) */
    class SatVideoTime(private val s: SessionInfo, private val dev: DevInfo, private val startBoot: Long) {
        private val pm = PauseMap(s)
        private val fit = ClockFit(dev.samples)

        fun fileMs(ptsNs: Long): Double {
            val m = pm.toElapsed(ptsNs)
            val sat = m.toDouble() + fit.theta(m)
            return (sat - startBoot.toDouble()) / 1e6 + dev.videoLatencyMs - dev.nudgeMs
        }
    }

    fun totalFrames(s: SessionInfo): Long {
        val paused = s.pauses.sumOf { it[1] }
        val dur = (s.endElapsedNs - s.startElapsedNs - paused).coerceAtLeast(0L)
        return dur * RATE / 1_000_000_000L
    }

    /** هل الجلسة جاهزة للدمج؟ (بعض الأجهزة قد تبقى بدون رفع) */
    fun readiness(ctx: Context, s: SessionInfo): String? {
        if (!s.ended || s.mainUri.isEmpty()) return "Recording is not finished"
        if (!s.hasWav) return "This recording has no separate audio tracks"
        if (!File(SessionStore.dir(ctx, s.id), "main_src.wav").exists()) return "Main audio files are missing"
        return null
    }

    fun merge(
        ctx: Context,
        sid: String,
        toFile: File?,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): Result {
        val warnings = ArrayList<String>()
        val s = SessionStore.load(ctx, sid) ?: return Result(null, null, "Session not found", warnings)
        readiness(ctx, s)?.let { return Result(null, null, it, warnings) }
        val dir = SessionStore.dir(ctx, sid)
        val total = totalFrames(s)
        if (total <= 0L) return Result(null, null, "Empty recording", warnings)

        var micRd: WavReader? = null
        var srcRd: WavReader? = null
        val sats = ArrayList<Src>()
        var ex: MediaExtractor? = null
        var enc: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var outUri: Uri? = null
        var started = false
        var ok = false
        try {
            val mrd = WavReader(File(dir, "main_mic.wav"))
            micRd = mrd
            val srd = WavReader(File(dir, "main_src.wav"))
            srcRd = srd

            for (dv in s.devices.values) {
                if (!dv.started || !dv.useAudio) continue
                val dd = SessionStore.devDir(ctx, sid, dv.id)
                val wf = File(dd, "audio.wav")
                val mf = File(dd, "meta.json")
                if (!wf.exists() || !mf.exists()) {
                    warnings += "${dv.name}: audio not received yet — skipped"
                    continue
                }
                try {
                    val meta = JSONObject(mf.readText())
                    val a = meta.optJSONObject("audio")
                    if (a == null) { warnings += "${dv.name}: no audio in its recording"; continue }
                    val rd = WavReader(wf)
                    if (!ClockFit(dv.samples).ok) warnings += "${dv.name}: no clock samples — sync may be off"
                    sats.add(Src(dv, rd, AudioMap(a, rd.rate), ClockFit(dv.samples)))
                } catch (t: Throwable) {
                    warnings += "${dv.name}: ${t.javaClass.simpleName}"
                }
            }

            // الفيديو الأصلي
            val extractor = MediaExtractor()
            ex = extractor
            extractor.setDataSource(ctx, Uri.parse(s.mainUri), null)
            var vIdx = -1
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { vIdx = i; break }
            }
            if (vIdx < 0) return Result(null, null, "No video track in the recording", warnings)
            val vFmt = extractor.getTrackFormat(vIdx)
            extractor.selectTrack(vIdx)

            // المخرج
            if (toFile != null) {
                toFile.parentFile?.mkdirs()
                if (toFile.exists()) toFile.delete()
                muxer = MediaMuxer(toFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                val name = StorageUtil.newName().replace(".mp4", "_mix.mp4")
                val ou = StorageUtil.createPending(ctx, name) ?: return Result(null, null, "Cannot create the output file", warnings)
                outUri = ou
                val pf = ctx.contentResolver.openFileDescriptor(ou, "rw")
                    ?: return Result(null, null, "Cannot open the output file", warnings)
                pfd = pf
                muxer = MediaMuxer(pf.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
            val mux: MediaMuxer = muxer!!
            try {
                if (vFmt.containsKey(MediaFormat.KEY_ROTATION)) mux.setOrientationHint(vFmt.getInteger(MediaFormat.KEY_ROTATION))
            } catch (_: Throwable) {}
            val vTrack = mux.addTrack(vFmt)
            var aTrack = -1

            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2)
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            af.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc = codec
            codec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val vBuf = ByteBuffer.allocateDirect(max(vFmt.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0, 2 * 1024 * 1024))
            val vInfo = MediaCodec.BufferInfo()
            var vDone = false

            fun pumpVideo(upToUs: Long) {
                while (!vDone) {
                    val t = extractor.sampleTime
                    if (t < 0L) { vDone = true; break }
                    if (t > upToUs) break
                    vBuf.clear()
                    val n = extractor.readSampleData(vBuf, 0)
                    if (n < 0) { vDone = true; break }
                    vInfo.set(0, n, t, if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(vTrack, vBuf, vInfo)
                    extractor.advance()
                }
            }

            val oInfo = MediaCodec.BufferInfo()
            var gotEos = false
            fun drain() {
                while (true) {
                    val i = codec.dequeueOutputBuffer(oInfo, 0)
                    when {
                        i == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            aTrack = mux.addTrack(codec.outputFormat)
                            mux.start()
                            started = true
                        }
                        i >= 0 -> {
                            val ob = codec.getOutputBuffer(i)
                            if (ob != null && oInfo.size > 0 && (oInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && started) {
                                pumpVideo(oInfo.presentationTimeUs)
                                ob.position(oInfo.offset)
                                ob.limit(oInfo.offset + oInfo.size)
                                mux.writeSampleData(aTrack, ob, oInfo)
                            }
                            codec.releaseOutputBuffer(i, false)
                            if ((oInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { gotEos = true; return }
                        }
                    }
                }
            }

            var fedFrames = 0L
            fun feed(pcm: ShortArray, frames: Int) {
                var off = 0
                while (off < frames) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx < 0) { drain(); continue }
                    val ib = codec.getInputBuffer(idx)!!
                    ib.clear()
                    ib.order(ByteOrder.LITTLE_ENDIAN)
                    val c = min(frames - off, ib.capacity() / 4)
                    ib.asShortBuffer().put(pcm, off * 2, c * 2)
                    codec.queueInputBuffer(idx, 0, c * 4, fedFrames * 1_000_000L / RATE, 0)
                    fedFrames += c
                    off += c
                    drain()
                }
            }

            // ───── الخلط ─────
            val pm = PauseMap(s)
            val mixL = FloatArray(BLOCK)
            val mixR = FloatArray(BLOCK)
            val tmpA = FloatArray(BLOCK)
            val tmpB = FloatArray(BLOCK)
            val satBuf = FloatArray(BLOCK + SUB * 2 + 16)
            val pcm = ShortArray(BLOCK * 2)
            val bnd = DoubleArray(BLOCK / SUB + 3)

            var k0 = 0L
            while (k0 < total) {
                if (cancelled()) { return Result(null, null, "Cancelled", warnings) }
                val n = min(BLOCK.toLong(), total - k0).toInt()
                java.util.Arrays.fill(mixL, 0, n, 0f)
                java.util.Arrays.fill(mixR, 0, n, 0f)

                mrd.readChannel(k0, n, 0, tmpA)
                val mg = s.micGain
                for (i in 0 until n) { val v = tmpA[i] * mg; mixL[i] += v; mixR[i] += v }
                srd.readChannel(k0, n, 0, tmpA)
                srd.readChannel(k0, n, 1, tmpB)
                val sg = s.srcGain
                for (i in 0 until n) { mixL[i] += tmpA[i] * sg; mixR[i] += tmpB[i] * sg }

                for (sat in sats) {
                    val nb = (n + SUB - 1) / SUB
                    var lo = Double.MAX_VALUE
                    var hi = -Double.MAX_VALUE
                    val nudgeNs = sat.dev.nudgeMs * 1_000_000.0
                    for (j in 0..nb) {
                        val k = k0 + min(j.toLong() * SUB, n.toLong())
                        val ptsNs = k * 1_000_000_000L / RATE
                        val m = pm.toElapsed(ptsNs)
                        val satNs = m.toDouble() + sat.fit.theta(m)
                        val np = sat.map.nOf(satNs - nudgeNs)
                        bnd[j] = np
                        if (np < lo) lo = np
                        if (np > hi) hi = np
                    }
                    if (hi < -2.0 || lo > sat.rd.frames + 2.0) continue
                    val start = floor(lo).toLong() - 1L
                    val cnt = (ceil(hi).toLong() + 3L - start).toInt()
                    if (cnt <= 0 || cnt > satBuf.size) continue
                    sat.rd.readChannel(start, cnt, 0, satBuf)
                    val g = sat.dev.gain
                    for (i in 0 until n) {
                        val seg = i / SUB
                        val fr = (i - seg * SUB).toDouble() / SUB
                        val np = bnd[seg] + (bnd[seg + 1] - bnd[seg]) * fr
                        val fl = floor(np)
                        val w = (np - fl).toFloat()
                        val ix = (fl.toLong() - start).toInt()
                        if (ix < 0 || ix + 1 >= cnt) continue
                        val v = (satBuf[ix] * (1f - w) + satBuf[ix + 1] * w) * g
                        mixL[i] += v; mixR[i] += v
                    }
                }

                for (i in 0 until n) {
                    pcm[i * 2] = toS16(mixL[i])
                    pcm[i * 2 + 1] = toS16(mixR[i])
                }
                feed(pcm, n)
                k0 += n
                progress(k0.toFloat() / total)
            }

            // نهاية الصوت
            var eosSent = false
            while (!eosSent) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx < 0) { drain(); continue }
                codec.queueInputBuffer(idx, 0, 0, fedFrames * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                eosSent = true
            }
            var guard = 0
            while (!gotEos && guard < 2000) { drain(); guard++; if (!gotEos) try { Thread.sleep(5) } catch (_: Throwable) {} }
            if (!started) return Result(null, null, "Audio encoder produced nothing", warnings)
            pumpVideo(Long.MAX_VALUE)
            try { mux.stop() } catch (_: Throwable) {}
            started = false
            ok = true
            progress(1f)
        } catch (t: Throwable) {
            return Result(null, null, "Merge failed: ${t.message ?: t.javaClass.simpleName}", warnings)
        } finally {
            try { enc?.stop() } catch (_: Throwable) {}
            try { enc?.release() } catch (_: Throwable) {}
            try { ex?.release() } catch (_: Throwable) {}
            if (started) try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
            micRd?.close(); srcRd?.close()
            for (x in sats) x.rd.close()
            if (!ok) {
                if (outUri != null) StorageUtil.delete(ctx, outUri)
                if (toFile != null) try { toFile.delete() } catch (_: Throwable) {}
            }
        }
        if (outUri != null) StorageUtil.publish(ctx, outUri)
        return Result(outUri, toFile, null, warnings)
    }

    /** قصّ ناعم قرب الحدّ ثم تحويل إلى 16-bit */
    private fun toS16(x: Float): Short {
        var v = x
        val a = abs(v)
        if (a > 0.9f) {
            val e = 0.9f + 0.1f * kotlin.math.tanh((a - 0.9f) / 0.1f)
            v = if (v < 0f) -e else e
        }
        val i = (v * 32767f).toInt()
        return i.coerceIn(-32768, 32767).toShort()
    }
}
