package com.reactionrecorder

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** كاتب ملفات WAV (PCM 16-bit). يُكتب الرأس في النهاية؛ وإن انقطع التسجيل يمكن إصلاحه بـ [fixHeader] */
class WavWriter(val file: File, val rate: Int, val channels: Int) {
    private val raf = RandomAccessFile(file, "rw")
    var frames = 0L
        private set
    private var buf = ByteArray(0)
    private var closed = false

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    /** يكتب [count] عيّنة (shorts) متداخلة القنوات */
    @Synchronized
    fun write(pcm: ShortArray, count: Int) {
        if (closed || count <= 0) return
        val need = count * 2
        if (buf.size < need) buf = ByteArray(need)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bb.putShort(pcm[i])
        raf.write(buf, 0, need)
        frames += count / channels
    }

    /** يملأ بصمت حتى يصل عدد الإطارات إلى [target] (للمحافظة على المحاذاة مع زمن الفيديو) */
    @Synchronized
    fun padTo(target: Long) {
        if (closed) return
        var missing = target - frames
        if (missing <= 0L) return
        val z = ByteArray(8192 * channels * 2)
        while (missing > 0L) {
            val n = minOf(missing, 8192L).toInt()
            raf.write(z, 0, n * channels * 2)
            missing -= n
            frames += n
        }
    }

    @Synchronized
    fun finish() {
        if (closed) return
        closed = true
        try {
            writeHeader(raf, rate, channels, frames * channels * 2)
        } catch (_: Throwable) {
        }
        try { raf.close() } catch (_: Throwable) {}
    }

    companion object {
        private fun writeHeader(raf: RandomAccessFile, rate: Int, channels: Int, dataBytes: Long) {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII))
            h.putInt((36 + dataBytes).toInt())
            h.put("WAVE".toByteArray(Charsets.US_ASCII))
            h.put("fmt ".toByteArray(Charsets.US_ASCII))
            h.putInt(16)
            h.putShort(1) // PCM
            h.putShort(channels.toShort())
            h.putInt(rate)
            h.putInt(rate * channels * 2)
            h.putShort((channels * 2).toShort())
            h.putShort(16)
            h.put("data".toByteArray(Charsets.US_ASCII))
            h.putInt(dataBytes.toInt())
            raf.seek(0)
            raf.write(h.array())
        }

        /** إصلاح رأس ملف لم يُغلق بشكل سليم (انقطاع/تعطّل) اعتمادًا على حجم الملف */
        fun fixHeader(file: File, rate: Int, channels: Int) {
            try {
                RandomAccessFile(file, "rw").use { r ->
                    val data = (r.length() - 44).coerceAtLeast(0L)
                    val aligned = data - data % (channels * 2)
                    writeHeader(r, rate, channels, aligned)
                }
            } catch (_: Throwable) {
            }
        }
    }
}

/** قارئ WAV بسيط للملفات التي كتبناها (رأس 44 بايت) */
class WavReader(file: File) {
    private val raf = RandomAccessFile(file, "r")
    val rate: Int
    val channels: Int
    val frames: Long
    private var tmp = ByteArray(0)

    init {
        val h = ByteArray(44)
        raf.seek(0)
        raf.readFully(h)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        channels = bb.getShort(22).toInt().coerceAtLeast(1)
        rate = bb.getInt(24)
        val bytes = (raf.length() - 44).coerceAtLeast(0L)
        frames = bytes / (channels * 2)
    }

    /** يقرأ قناعًا واحدًا (ch) من [count] إطارًا ابتداءً من [start] إلى out كقيم -1..1؛ خارج الملف = صمت */
    @Synchronized
    fun readChannel(start: Long, count: Int, ch: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0, count, 0f)
        if (count <= 0) return
        val from = maxOf(start, 0L)
        val to = minOf(start + count, frames)
        if (to <= from) return
        val n = (to - from).toInt()
        val bytes = n * channels * 2
        if (tmp.size < bytes) tmp = ByteArray(bytes)
        raf.seek(44L + from * channels * 2)
        raf.readFully(tmp, 0, bytes)
        val bb = ByteBuffer.wrap(tmp, 0, bytes).order(ByteOrder.LITTLE_ENDIAN)
        val c = ch.coerceIn(0, channels - 1)
        val off = (from - start).toInt()
        for (i in 0 until n) {
            out[off + i] = bb.getShort((i * channels + c) * 2) / 32768f
        }
    }

    fun close() { try { raf.close() } catch (_: Throwable) {} }
}
