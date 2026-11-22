package com.reactionrecorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * غلاف آمن للـ MediaMuxer يُستعمل من خيطين (الفيديو والصوت).
 * يؤجّل بدء الـ muxer حتى تُضاف كل المسارات المتوقعة، ويخزّن العينات المبكرة مؤقتًا
 * بحيث لا يتوقف مُرمِّز الفيديو أبدًا بانتظار مسار الصوت.
 */
class MuxerHolder(private val muxer: MediaMuxer, @Volatile private var expectedTracks: Int) {
    private class Pending(val track: Int, val data: ByteArray, val ptsUs: Long, val flags: Int)

    private var tracksAdded = 0
    private var started = false
    private var released = false
    private val pending = ArrayList<Pending>()
    @Volatile var bytesWritten = 0L
        private set

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val id = muxer.addTrack(format)
        tracksAdded++
        maybeStart()
        return id
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (released || info.size <= 0) return
        if (started) {
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            muxer.writeSampleData(track, buf, info)
            bytesWritten += info.size
        } else {
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            val arr = ByteArray(info.size)
            buf.get(arr)
            pending.add(Pending(track, arr, info.presentationTimeUs, info.flags))
        }
    }

    private fun maybeStart() {
        if (!started && tracksAdded >= expectedTracks) {
            muxer.start()
            started = true
            val bi = MediaCodec.BufferInfo()
            for (p in pending) {
                bi.set(0, p.data.size, p.ptsUs, p.flags)
                muxer.writeSampleData(p.track, ByteBuffer.wrap(p.data), bi)
                bytesWritten += p.data.size
            }
            pending.clear()
        }
    }

    /** إن فشل مسار الصوت نكمل بمسار الفيديو وحده بدل ترك الملف فارغًا */
    @Synchronized
    fun lowerExpectedTracks(n: Int) {
        expectedTracks = n
        maybeStart()
    }

    @Synchronized
    fun isStarted() = started

    /** يعيد true إذا خرج ملف صالح (بدأ الـ muxer فعلًا) */
    @Synchronized
    fun finish(): Boolean {
        if (released) return false
        released = true
        var ok = false
        try {
            if (started) { muxer.stop(); ok = true }
        } catch (_: Throwable) {
        }
        try { muxer.release() } catch (_: Throwable) {}
        return ok
    }
}
