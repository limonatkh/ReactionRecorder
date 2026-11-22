package com.reactionrecorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * يُخرج حلقة البودكاست من اللقطات: يفك فيديوهات الكاميرات المطلوبة فقط في كل لحظة،
 * يركّبها بـ OpenGL حسب نوع اللقطة، يرمّزها H.264، ثم يخلط صوت المايكات (مع تخفيض الساكت وتوحيد المستوى) ويدمج الكل.
 * لا يعمل بالزمن الحقيقي: كل إطار يُحسب بدقة، فلا يتأثر بسرعة الجهاز (فقط يأخذ وقتًا أطول).
 */
/** كل قرارات التصدير (هي نفسها للمعاينة والتصدير النهائي؛ المعاينة فقط بدقة أخف) */
class RenderJob(
    val shots: List<PodShot>,
    /** الأجزاء المطلوبة من الجلسة [بداية، نهاية] بعد قص الصمت/الجمل */
    val segs: List<DoubleArray>,
    val vertical: Boolean,
    val label: String,
    val zooms: List<PZoom> = emptyList(),
    val captions: List<PodEngine.CapChunk> = emptyList(),
    val title: String = "",
    val titleSecs: Double = 3.0,
    val fade: Boolean = false,
    val height: Int = 720,
    val capSize: Float = 1f,
    val faces: Map<String, FaceTrack> = emptyMap(),
    /** معاينة: دقة 360، أدلة المناطق الآمنة، والناتج ملف مؤقت بدل المعرض */
    val preview: Boolean = false,
    val outFile: File? = null,
    /** بداية المعاينة على خط النسخة المعدّلة (لإظهار العنوان الافتتاحي فقط في بدايتها الحقيقية) */
    val outOffset: Double = 0.0,
    /** تأطير الكاميرات: smart = كامل الصورة مع خلفية مموّهة إذا اختلف الاتجاه كثيرًا، وإلا ملء؛ fill = ملء مع قص؛ fit = الصورة كاملة دائمًا */
    val frame: String = "smart",
    /** عندما لا يوجد فيديو لشخص في لحظة ما: other = كاميرا أخرى متاحة، freeze = آخر صورة، card = بطاقة باسمه */
    val fill: String = "other",
    /** أشخاص يظهرون طول الوقت (مربعات صغيرة) */
    val pinned: List<Int> = emptyList(),
    /** إطار مضيء حول مربع من يتكلم */
    val glow: Boolean = false,
    /** لحظة قلب الكاميرا: freeze / other / blur */
    val flipFill: String = "freeze",
    /** قالب الشكل: المربع العائم، المقسوم، الأسماء، الثابتين، الكتابة */
    val tpl: PodTemplate = PodTemplate(),
    /** الوجوه للمربع العائم (القص على الوجه، أو إبعاده عن وجه اللي بيحكي) */
    val pipFaces: Map<String, FaceTrack> = emptyMap(),
    /** كاميرات جايبة أكثر من شخص (p0…): بالشاشة المقسومة بتطلع كاملة بدل القص على وجه واحد */
    val crowd: Set<String> = emptySet(),
    /** كم شخص بكل كاميرا حسب اختيارك (p0 → 1/2/3؛ ما في = تلقائي) */
    val count: Map<String, Int> = emptyMap(),
    /** فيديو قبل الحلقة وبعدها (بصوته) */
    val intro: File? = null,
    val outro: File? = null,
    /** فيديو بين الإنترو والحلقة (لوجو…) */
    val mid: File? = null,
    /** تدوير/قلب/قص كل مقطع */
    val introFx: ClipFx = ClipFx(),
    val midFx: ClipFx = ClipFx(),
    val outroFx: ClipFx = ClipFx(),
    /** الانتقال بين الإنترو والحلقة، وبين الحلقة والأوترو: black (تلاشي لأسود) / white (تلاشي لأبيض) / cut (قطع) / flash (ومضة) */
    val introTrans: String = "black",
    val introTransSec: Double = 0.3,
    val outroTrans: String = "black",
    val outroTransSec: Double = 0.3,
    /** لحظات التقريب السريع (بزمن الجلسة) */
    val punches: List<Double> = emptyList(),
    /** نصوصك الثابتة ({link}…) للعناصر الثابتة */
    val brand: Map<String, String> = emptyMap(),
    /** ما يصير بالمعرض لحد ما توافق (بعد ما تشوفه) */
    val hold: Boolean = false,
    /** طول الفيديو الناتج كامل (للمعاينة: عشان العناصر والشريط يحسبوا صح) */
    val totalOut: Double = 0.0,
)

class PodcastRenderer(
    private val ctx: Context,
    private val s: SessionInfo,
    private val setup: PodSetup,
    private val an: PodAnalysis?,
    private val job: RenderJob,
    private val progress: (String, Float) -> Unit,
    private val cancelled: () -> Boolean,
) {
    private val shots = job.shots
    private val vertical = job.vertical
    private val label = job.label
    private val fps = 30
    private val base = if (job.preview || job.height <= 360) 360 else if (job.height >= 1080) 1080 else 720
    private val outW = if (vertical) base else base * 16 / 9
    private val outH = if (vertical) base * 16 / 9 else base
    private val dur = PodcastAnalyzer.duration(s)
    val warnings = ArrayList<String>()

    /** أجزاء الإخراج وبداية كل جزء على خط الإخراج */
    private val segList: List<DoubleArray> = job.segs
        .map { doubleArrayOf(it[0].coerceIn(0.0, dur), it[1].coerceIn(0.0, dur)) }.filter { it[1] - it[0] > 0.04 }
    private val segOut: DoubleArray = DoubleArray(segList.size).also { a -> var c = 0.0; for (i in segList.indices) { a[i] = c; c += segList[i][1] - segList[i][0] } }
    private val outDur: Double = segList.sumOf { it[1] - it[0] }
    /** مدة الإنترو والأوترو (حد أقصى دقيقة لكل واحد) */
    private fun clipDur(f: File?): Double = if (f == null) 0.0 else try {
        val r = android.media.MediaMetadataRetriever(); r.setDataSource(f.absolutePath)
        val d = (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
        r.release(); d.coerceIn(0.0, 180.0)
    } catch (_: Throwable) { 0.0 }
    /** المقاطع قبل الحلقة (إنترو ثم فاصل) وبعدها (أوترو)، كل واحد بمدته وتعديله */
    private class Clip(val key: String, val file: File, val fx: ClipFx, val dur: Double)
    private val pre: List<Clip> = listOfNotNull(job.intro?.let { Clip("x_intro", it, job.introFx, clipDur(it)) }, job.mid?.let { Clip("x_mid", it, job.midFx, clipDur(it)) }).filter { it.dur > 0.05 }
    private val post: List<Clip> = listOfNotNull(job.outro?.let { Clip("x_outro", it, job.outroFx, clipDur(it)) }).filter { it.dur > 0.05 }
    private val introDur = pre.sumOf { it.dur }
    /** مدة التلاشي عند حد الإنترو/الأوترو (قطع/ومضة = بدون تلاشي) */
    private fun trSec(kind: String, sec: Double) = if (kind == "black" || kind == "white") sec.coerceIn(0.1, 2.0) else 0.0
    private val inTr get() = trSec(job.introTrans, job.introTransSec)
    private val outTr get() = trSec(job.outroTrans, job.outroTransSec)
    private fun trColor(kind: String) = if (kind == "white") 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    private val outroDur = post.sumOf { it.dur }

    /** رقم الجزء الذي يحوي اللحظة tOut على خط الإخراج */
    private fun segAt(tOut: Double): Int {
        var lo = 0
        var hi = segList.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (segOut[mid] <= tOut) lo = mid else hi = mid - 1 }
        return lo
    }

    private fun sessionT(tOut: Double): Double { val i = segAt(tOut); return segList[i][0] + (tOut - segOut[i]) }

    // ───────── EGL / GL ─────────
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglCtx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurf: EGLSurface = EGL14.EGL_NO_SURFACE

    private class Prog { var id = 0; var aPos = 0; var aTex = 0; var aLocal = 0; var uTexM = 0; var uTex = 0; var uSize = 0; var uRadius = 0; var uAlpha = 0; var uColor = 0 }
    private val pOes = Prog()
    private val p2d = Prog()
    private val pSolid = Prog()
    private val pBlur = Prog()
    private val posBuf = fb(8)
    private val texBuf = fb(8)
    private val locBuf = fb(8).apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val ident = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private val flip = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    private var bgTex = 0
    private var bgW = 1
    private var bgH = 1

    private val frameThread by lazy { HandlerThread("pod-frames").apply { start() } }
    private var frameStarted = false
    private val frameHandler by lazy { frameStarted = true; Handler(frameThread.looper) }

    private fun fb(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** مصدر فيديو: ملف + تحويل زمن الجلسة إلى زمن الملف */
    private inner class VDec(val key: String, val dev: String, val file: File, val time: Finalizer.SatVideoTime) {
        var ex: MediaExtractor? = null
        var dec: MediaCodec? = null
        var st: SurfaceTexture? = null
        var surf: Surface? = null
        var tex = 0
        val mat = FloatArray(16)
        var w = 1
        var h = 1
        var durUs = 0L
        var curPts = Long.MIN_VALUE
        var hasFrame = false
        var inputDone = false
        var ended = false
        var failed = false
        private val lock = java.lang.Object()
        private var avail = false
        private val info = MediaCodec.BufferInfo()

        fun open(): Boolean {
            try {
                val e = MediaExtractor()
                e.setDataSource(file.absolutePath)
                var idx = -1
                for (i in 0 until e.trackCount) if (e.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { idx = i; break }
                if (idx < 0) { e.release(); failed = true; return false }
                e.selectTrack(idx)
                val f = e.getTrackFormat(idx)
                w = f.getInteger(MediaFormat.KEY_WIDTH)
                h = f.getInteger(MediaFormat.KEY_HEIGHT)
                val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rot == 90 || rot == 270) { val t0 = w; w = h; h = t0 }
                durUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE
                if (fpsChecked.add(key)) checkFps(idx)
                val t = IntArray(1)
                GLES20.glGenTextures(1, t, 0)
                tex = t[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val sTex = SurfaceTexture(tex)
                sTex.setOnFrameAvailableListener({ synchronized(lock) { avail = true; lock.notifyAll() } }, frameHandler)
                st = sTex
                val sf = Surface(sTex)
                surf = sf
                val d = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
                d.configure(f, sf, null, 0)
                d.start()
                ex = e
                dec = d
                return true
            } catch (t: Throwable) {
                failed = true
                warnings += "Cannot open a video ($key): ${t.javaClass.simpleName}"
                close()
                return false
            }
        }

        /** سرعة التصوير الحقيقية (إطارات بالثانية) بعيّنة من نص الفيديو — الضو القليل بيخلي الجوال يصوّر 15 */
        private fun checkFps(track: Int) {
            try {
                val e = MediaExtractor()
                e.setDataSource(file.absolutePath)
                e.selectTrack(track)
                val mid = if (durUs in 1 until Long.MAX_VALUE) durUs / 2 else 0L
                e.seekTo(mid, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val t0 = e.sampleTime
                var n = 0
                while (e.sampleTime >= 0 && e.sampleTime - t0 < 6_000_000L) { n++; if (!e.advance()) break }
                val span = (e.sampleTime - t0).coerceAtLeast(1L)
                e.release()
                val fps = n * 1_000_000.0 / span
                if (n > 10 && fps < 22.0) warnings += tx("كاميرا ${nameOf(key)} صوّرت حوالي ${fps.toInt()} إطار بالثانية (الطبيعي 30) — الحركة رح تبين متقطعة. السبب غالبًا ضو قليل: زيد الإضاءة بالتصوير الجاي.",
                    "${nameOf(key)}'s camera recorded about ${fps.toInt()} fps (normal is 30) — motion will look choppy. Usually low light: add more light next time.")
            } catch (_: Throwable) {}
        }

        private fun seek(us: Long) {
            val e = ex ?: return
            val d = dec ?: return
            synchronized(lock) { avail = false }
            e.seekTo(max(0L, us), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            d.flush()
            inputDone = false
            ended = false
            curPts = Long.MIN_VALUE
        }

        /** يجعل النسيج يحمل الإطار المقابل للزمن المطلوب. يعيد false إن لم يتوفر فيديو في هذه اللحظة */
        var retryAt = 0L

        fun frameAt(targetUs: Long): Boolean {
            if (failed) {
                // فشل الفتح (غالبًا نقص موارد فك الترميز): نعيد المحاولة بعد قليل بدل إطفاء الكاميرا للأبد
                if (System.currentTimeMillis() < retryAt) return false
                failed = false
            }
            if (dec == null && !open()) { retryAt = System.currentTimeMillis() + 1500; return false }
            val d = dec!!
            val e = ex!!
            if (targetUs < -100_000L || targetUs > durUs + 300_000L) return false
            val half = 1_000_000L / fps / 2
            if (hasFrame && targetUs <= curPts + half && targetUs >= curPts - 1_500_000L) return true
            if (curPts != Long.MIN_VALUE && (targetUs < curPts - half || targetUs > curPts + 3_000_000L)) seek(targetUs)
            else if (curPts == Long.MIN_VALUE && !hasFrame) seek(targetUs)
            if (ended && targetUs >= curPts) return hasFrame
            var guard = 0
            while (guard++ < 2000) {
                if (!inputDone) {
                    val ii = d.dequeueInputBuffer(2000)
                    if (ii >= 0) {
                        val buf = d.getInputBuffer(ii)!!
                        val n = e.readSampleData(buf, 0)
                        if (n < 0) {
                            d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            d.queueInputBuffer(ii, 0, n, e.sampleTime, 0)
                            e.advance()
                        }
                    }
                }
                val oi = d.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val render = info.size > 0 && (info.presentationTimeUs >= targetUs - half || eos)
                    d.releaseOutputBuffer(oi, render)
                    if (render) {
                        if (waitFrame()) {
                            st?.updateTexImage()
                            // إطار متأخر من مهلة سابقة؟ نأخذ التالي ليبقى التزامن صحيحًا
                            val stUs = (st?.timestamp ?: 0L) / 1000L
                            if (stUs in 1 until info.presentationTimeUs - 1000L && waitFrame()) st?.updateTexImage()
                            st?.getTransformMatrix(mat)
                            hasFrame = true
                        }
                        curPts = info.presentationTimeUs
                        return hasFrame
                    }
                    curPts = info.presentationTimeUs
                    if (eos) { ended = true; return hasFrame }
                } else if (inputDone && oi == MediaCodec.INFO_TRY_AGAIN_LATER && guard > 200) {
                    ended = true
                    return hasFrame
                }
            }
            return hasFrame
        }

        private fun waitFrame(): Boolean {
            synchronized(lock) {
                val until = System.currentTimeMillis() + 800
                while (!avail) {
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) return false
                    lock.wait(left)
                }
                avail = false
                return true
            }
        }

        fun close() {
            try { dec?.stop() } catch (_: Throwable) {}
            try { dec?.release() } catch (_: Throwable) {}
            try { ex?.release() } catch (_: Throwable) {}
            try { surf?.release() } catch (_: Throwable) {}
            try { st?.release() } catch (_: Throwable) {}
            if (tex != 0) { GLES20.glDeleteTextures(1, intArrayOf(tex), 0); tex = 0 }
            dec = null; ex = null; surf = null; st = null
            synchronized(lock) { avail = false }
            ended = false
            hasFrame = false
            curPts = Long.MIN_VALUE
        }
    }

    private val sources = HashMap<String, VDec?>()
    /** فترات قلب الكاميرا لكل مصدر (بزمن الملف ms) */
    private val flipsMs = HashMap<String, List<DoubleArray>>()

    /** هل الكاميرا تُقلب في هذه اللحظة؟ */
    private fun inFlip(key: String, t: Double): Boolean {
        val v = srcFor(key) ?: return false
        val fl = flipsMs[key] ?: return false
        val ms = v.time.fileMs((t * 1e9).toLong())
        return fl.any { ms >= it[0] - 30 && ms <= it[1] }
    }

    /** هل الشخص p يتكلم الآن؟ (للإطار المضيء) */
    private fun speaking(p: Int, t: Double): Boolean {
        val a = an ?: return false
        val f = (t / a.frameSec).toInt()
        return a.active.getOrNull(p)?.getOrNull(f) == true
    }

    /** إطار مضيء داخل حواف المربع */
    private fun glowFrame(x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        val b = max(3f, min(w, h) * job.tpl.glowWidth)
        val c = job.tpl.glowColor
        solid(x, y, w, b, c, 0f, alpha); solid(x, y + h - b, w, b, c, 0f, alpha)
        solid(x, y, b, h, c, 0f, alpha); solid(x + w - b, y, b, h, c, 0f, alpha)
    }

    private fun srcFor(key: String): VDec? {
        if (sources.containsKey(key)) return sources[key]
        val dev = when {
            key.startsWith("p") -> setup.people.getOrNull(key.substring(1).toInt())?.camDev
            else -> setup.wides.getOrNull(key.substring(1).toInt())
        }
        var v: VDec? = null
        if (!dev.isNullOrEmpty()) {
            val dv = s.devices[dev]
            val dd = SessionStore.devDir(ctx, s.id, dev)
            val vf = File(dd, "video.mp4")
            val mf = File(dd, "meta.json")
            if (dv != null && vf.exists() && mf.exists()) {
                try {
                    val vm = JSONObject(mf.readText()).optJSONObject("video")
                    if (vm != null) {
                        v = VDec(key, dev, vf, Finalizer.SatVideoTime(s, dv, vm.optLong("startBoot")))
                        val fl = vm.optJSONArray("flips")
                        if (fl != null && fl.length() > 0) {
                            val sb = vm.optLong("startBoot")
                            flipsMs[key] = List(fl.length()) { i -> val x = fl.getJSONArray(i); doubleArrayOf((x.getLong(0) - sb) / 1e6, (x.getLong(1) - sb) / 1e6) }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        sources[key] = v
        return v
    }

    // ───────── رسم ─────────

    private fun buildProg(p: Prog, kind: Int) {
        val vs = """
            attribute vec2 aPos; attribute vec2 aTex; attribute vec2 aLocal;
            uniform mat4 uTexM; varying vec2 vTex; varying vec2 vLocal;
            void main(){ gl_Position = vec4(aPos,0.0,1.0); vTex = (uTexM*vec4(aTex,0.0,1.0)).xy; vLocal = aLocal; }
        """.trimIndent()
        val head = if (kind == 0 || kind == 3) "#extension GL_OES_EGL_image_external : require\nprecision highp float;\nuniform samplerExternalOES uTex;\n"
        else "precision highp float;\nuniform sampler2D uTex;\n"
        val body = """
            uniform vec2 uSize; uniform float uRadius; uniform float uAlpha; uniform vec4 uColor;
            varying vec2 vTex; varying vec2 vLocal;
            float rr(vec2 p, vec2 b, float r){ vec2 q = abs(p) - b + vec2(r); return length(max(q,0.0)) + min(max(q.x,q.y),0.0) - r; }
            void main(){
              float a = 1.0;
              if (uRadius > 0.0) { a = clamp(0.5 - rr(vLocal*uSize*0.5, uSize*0.5, uRadius), 0.0, 1.0); }
              ${if (kind == 3) BLUR else "vec4 c = " + (if (kind == 2) "uColor" else "texture2D(uTex, vTex)") + ";"}
              ${if (kind == 1) "gl_FragColor = c * (a*uAlpha);" else "gl_FragColor = vec4(c.rgb, c.a*a*uAlpha);"}
            }
        """.trimIndent()
        val fs = if (kind == 2) "precision highp float;\n$body" else head + body
        p.id = link(vs, fs)
        p.aPos = GLES20.glGetAttribLocation(p.id, "aPos")
        p.aTex = GLES20.glGetAttribLocation(p.id, "aTex")
        p.aLocal = GLES20.glGetAttribLocation(p.id, "aLocal")
        p.uTexM = GLES20.glGetUniformLocation(p.id, "uTexM")
        p.uTex = GLES20.glGetUniformLocation(p.id, "uTex")
        p.uSize = GLES20.glGetUniformLocation(p.id, "uSize")
        p.uRadius = GLES20.glGetUniformLocation(p.id, "uRadius")
        p.uAlpha = GLES20.glGetUniformLocation(p.id, "uAlpha")
        p.uColor = GLES20.glGetUniformLocation(p.id, "uColor")
    }

    /** تمويه خفيف بـ 25 عيّنة (خلفية الصورة الكاملة بدل الأشرطة السوداء)؛ uColor.x = نصف القطر */
    private val BLUR = """
        vec4 c = vec4(0.0);
        for (int i = -2; i <= 2; i++) { for (int j = -2; j <= 2; j++) {
          c += texture2D(uTex, vTex + vec2(float(i), float(j)) * uColor.x * 0.05);
        } }
        c = c / 25.0; c.rgb = c.rgb * 0.55; c.a = 1.0;
    """.trimIndent()

    private fun link(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(id))
            return id
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    /** يرسم مستطيلًا (بكسل، الأصل أعلى اليسار) */
    private fun quad(p: Prog, target: Int, tex: Int, texM: FloatArray, x: Float, y: Float, w: Float, h: Float,
                     u0: Float, v0: Float, u1: Float, v1: Float, radius: Float, alpha: Float, color: Int = 0) {
        if (w < 1f || h < 1f) return
        val x0 = x / outW * 2f - 1f
        val x1 = (x + w) / outW * 2f - 1f
        val yt = 1f - y / outH * 2f
        val yb = 1f - (y + h) / outH * 2f
        posBuf.position(0); posBuf.put(floatArrayOf(x0, yb, x1, yb, x0, yt, x1, yt)); posBuf.position(0)
        texBuf.position(0); texBuf.put(floatArrayOf(u0, v0, u1, v0, u0, v1, u1, v1)); texBuf.position(0)
        GLES20.glUseProgram(p.id)
        // الصور ثنائية الأبعاد (الكتابة، الخلفية) تُرفع بألفا مضروبة مسبقًا
        if (p === p2d) GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) else GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(p.uTexM, 1, false, texM, 0)
        GLES20.glUniform2f(p.uSize, w, h)
        GLES20.glUniform1f(p.uRadius, radius)
        GLES20.glUniform1f(p.uAlpha, alpha)
        if (p.uColor >= 0) GLES20.glUniform4f(p.uColor, ((color shr 16) and 255) / 255f, ((color shr 8) and 255) / 255f, (color and 255) / 255f, ((color ushr 24) and 255) / 255f)
        if (tex != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target, tex)
            GLES20.glUniform1i(p.uTex, 0)
        }
        GLES20.glEnableVertexAttribArray(p.aPos)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        if (p.aTex >= 0) { GLES20.glEnableVertexAttribArray(p.aTex); GLES20.glVertexAttribPointer(p.aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf) }
        GLES20.glEnableVertexAttribArray(p.aLocal)
        GLES20.glVertexAttribPointer(p.aLocal, 2, GLES20.GL_FLOAT, false, 0, locBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun solid(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float, alpha: Float) =
        quad(pSolid, 0, 0, ident, x, y, w, h, 0f, 0f, 1f, 1f, radius, alpha, color)

    /** يملأ المستطيل بالمصدر مع قصّ الزائد (cover) */
    private val zoomLimited = HashSet<String>()

    /**
     * يملأ المستطيل بالمصدر مع قصّ الزائد (cover)، وزوم اختياري.
     * مركز الكادر: الضبط اليدوي له الأولوية، ثم الوجه إن كان التتبع مفعّلًا، وإلا المنتصف.
     * الزوم محدود بدقة المصدر حتى لا تتدهور الصورة، والكادر لا يخرج عن حدود الصورة.
     */
    /** تعديل المصدر (تدوير/قص) كمصفوفة نسيج + أبعاد */
    private fun geoOf(key: String, v: VDec): Pair<FloatArray, IntArray> {
        val deg = setup.srcRot[key] ?: 0
        val crop = setup.srcCrop[key]
        if (deg == 0 && crop == null) return v.mat to intArrayOf(v.w, v.h)
        return SrcGeo.mul(v.mat, SrcGeo.texMatrix(deg, crop)) to SrcGeo.dims(v.w, v.h, deg, crop)
    }

    /** وجه المصدر بلحظة ms (بإحداثيات المصدر بعد التدوير/القص) */
    private fun faceOf(key: String, v: VDec, track: FaceTrack?, ms: Double): FloatArray? {
        val f = track?.at(ms)?.takeIf { !it[0].isNaN() } ?: return null
        return SrcGeo.mapFace(f, setup.srcRot[key] ?: 0, setup.srcCrop[key], v.w, v.h)
    }

    /** نقطة من المصدر الأصلي لإحداثيات المصدر بعد تدويرك وقصك */
    private fun mapPt(key: String, x: Float, y: Float): FloatArray {
        val p = SrcGeo.rotPoint(x, y, setup.srcRot[key] ?: 0)
        val c = setup.srcCrop[key] ?: return p
        return floatArrayOf((p[0] - c[0]) / c[2], (p[1] - c[1]) / c[3])
    }

    /**
     * الكادر على الناس الموجودين هلأ بالكاميرا: [cx, cy, kw, kh] (0..1 من المصدر).
     *  side = 0/1 → مربع على الشخص اليسار/اليمين لحاله (شكل "كل شخص بمربع").
     *  push = 0..1 تقريب بطيء على اللي بيحكي (pushSide) من كادر الاثنين.
     * null = ما بنقدر (ما في وجوه، أو الناس أعرض من إنهم يدخلوا بلا قص) → المنطق العادي.
     */
    private val lastPerson = HashMap<String, FloatArray>()
    private var strictSplit = false

    /** الكاميرا فيها شخصين بعاد كفاية عن بعض إنهم ينقسموا لمربعين؟ (بنفحص ٣ لحظات باللقطة، لازم ٢ منهم) */
    private fun canSplit(key: String, sh: PodShot, da: Float): Boolean {
        val v = srcFor(key) ?: return false
        val dm = geoOf(key, v).second; val sa = dm[0].toFloat() / dm[1]
        var ok = 0
        for (f in doubleArrayOf(0.25, 0.5, 0.75)) {
            val ms = v.time.fileMs(((sh.start + (sh.end - sh.start) * f) * 1e9).toLong())
            val gg = job.pipFaces[v.dev]?.groupAt(ms) ?: continue
            if (gg[FaceTrack.G_CNT] < 2f) continue
            strictSplit = true
            val a = groupCrop(key, v, ms, sa, da, 0); val b = groupCrop(key, v, ms, sa, da, 1)
            strictSplit = false
            if (a != null && b != null) { lastPerson["$key#0"] = a; lastPerson["$key#1"] = b; ok++ }
        }
        return ok >= 2
    }

    private fun groupCrop(key: String, v: VDec, fileMs: Double, sa: Float, da: Float, side: Int = -1, push: Float = 0f, pushSide: Int = -1): FloatArray? {
        val tr = job.pipFaces[v.dev] ?: return null
        val gg = tr.groupAt(fileMs) ?: return if (side >= 0 && !strictSplit) lastPerson["$key#$side"] else null
        if (gg[FaceTrack.G_X0].isNaN()) return if (side >= 0 && !strictSplit) lastPerson["$key#$side"] else null
        val want = job.count[key] ?: 0
        if (want == 1) return null // انت قلت شخص واحد: القص العادي على الوجه
        val deg = setup.srcRot[key] ?: 0
        val quarter = (((deg % 360) + 360) % 360) % 180 != 0
        fun person(sd: Int): FloatArray? {
            val bx = if (sd == 0) FaceTrack.G_LX else FaceTrack.G_RX
            val ox = if (sd == 0) FaceTrack.G_RX else FaceTrack.G_LX
            if (gg[bx].isNaN() || gg[ox].isNaN() || gg[FaceTrack.G_CNT] < 2f) return null
            val p = mapPt(key, gg[bx], gg[bx + 1]); val q = mapPt(key, gg[ox], gg[ox + 1])
            val qk = if (quarter) v.h / v.w.toFloat() else 1f
            var fs = gg[bx + 2] * qk; var fo = gg[ox + 2] * qk
            setup.srcCrop[key]?.let { fs /= it[3]; fo /= it[3] }
            // المربع لازم يضل بجهة هالشخص: ما يوصل لوجه الثاني (مع هامش للشعر والكتاف)
            val hfo = fo / sa * 0.75f
            val lo: Float; val hi: Float
            if (p[0] <= q[0]) { lo = 0f; hi = q[0] - hfo } else { lo = q[0] + hfo; hi = 1f }
            val room = hi - lo
            if (room < 0.18f || abs(p[0] - q[0]) < 0.18f) return null // قراب كتير من بعض: ما بينقسموا
            var kh = (fs / 0.36f).coerceIn(0.2f, 1f)
            var kw = kh * da / sa
            if (kw > room) { kw = room; kh = kw * sa / da }
            // لازم الراس كامل مع فراغ فوقه وشوية كتاف: إذا المكان ضيق، بنبعّد الكادر حتى لو بيبيّن طرف الثاني
            val need = fs * 2.3f
            if (kh < need) { kh = need; kw = kh * da / sa }
            if (kh > 1f) { kh = 1f; kw = kh * da / sa }
            if (kw > 1f) { kw = 1f; kh = kw * sa / da }
            // الكادر بجهة هالشخص قد ما بنقدر (وجوّا الصورة دايمًا)
            val cx = if (p[0] <= q[0]) min(p[0], hi - kw / 2f).mid(kw) else max(p[0], lo + kw / 2f).mid(kw)
            // الراس فوق: أعلى الكادر = أعلى الوجه ناقص فراغ
            val faceTop = p[1] - fs * 0.6f
            val cy = (faceTop - fs * 0.45f + kh / 2f).mid(kh)
            return floatArrayOf(cx, cy, kw, kh)
        }
        if (side >= 0) {
            // لحظة ما انشافوا الاثنين: بنضل على آخر كادر منيح بدل ما نرجع للكاميرا كلها (وتصير المربعات مكررة)
            val k2 = "$key#$side"
            val r = person(side)
            if (r != null) lastPerson[k2] = r
            return r ?: if (strictSplit) null else lastPerson[k2]
        }
        // حدود كل الناس (بعد التدوير/القص)
        val c1 = mapPt(key, gg[FaceTrack.G_X0], gg[FaceTrack.G_Y0]); val c2 = mapPt(key, gg[FaceTrack.G_X1], gg[FaceTrack.G_Y1])
        val x0 = min(c1[0], c2[0]); val x1 = max(c1[0], c2[0]); val y0 = min(c1[1], c2[1]); val y1 = max(c1[1], c2[1])
        val n = gg[FaceTrack.G_CNT].coerceAtLeast(1f)
        val headH = ((y1 - y0) / (if (n >= 2f) 1.15f else 1f)).coerceIn(0.05f, 0.6f)
        val headW = headH / sa
        val bx0 = x0 - headW * 0.9f; val bx1 = x1 + headW * 0.9f
        val by0 = y0 - headH * 0.5f; val by1 = y1 + headH * 1.9f
        val bw = bx1 - bx0; val bh = by1 - by0
        val kwMax = min(1f, da / sa)
        if (bw > kwMax * 1.03f) return null
        var kh = max(bh, bw * sa / da).coerceIn(0.45f, 1f)
        var kw = kh * da / sa
        if (kw > 1f) { kw = 1f; kh = kw * sa / da }
        var cx = (bx0 + bx1) / 2f; var cy = (by0 + by1) / 2f
        // تقريب بطيء على اللي بيحكي (أو على النص إذا مش واضح مين)
        if (push > 0f) {
            val tgt = if (pushSide >= 0) person(pushSide) else null
            if (tgt != null) {
                cx += (tgt[0] - cx) * push; cy += (tgt[1] - cy) * push; kw += (tgt[2] - kw) * push; kh += (tgt[3] - kh) * push
            } else { kw *= 1f - 0.25f * push; kh *= 1f - 0.25f * push }
        }
        return floatArrayOf(cx, cy, kw, kh)
    }

    private fun drawSrc(key: String, t: Double, x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float, zoom: Float = 1f, quiet: Boolean = false, faceZoom: Float = 0f, cell: Boolean = false, pipBox: Boolean = false, side: Int = -1, push: Float = 0f, pushSide: Int = -1): Boolean {
        val v = srcFor(key)
        var fileMs = v?.time?.fileMs((t * 1e9).toLong()) ?: 0.0
        // "آخر صورة": خارج مدة الملف نثبّت أول/آخر إطار بدل الفراغ
        if (v != null && job.fill == "freeze" && v.durUs in 1 until Long.MAX_VALUE) fileMs = fileMs.coerceIn(0.0, v.durUs / 1000.0 - 40.0)
        // أول وآخر ثانيتين: الكاميرا بلّشت متأخرة شوي أو وقفت بدري → أول/آخر إطار بدل شاشة سودا
        else if (v != null && v.durUs in 1 until Long.MAX_VALUE) {
            val endMs = v.durUs / 1000.0 - 40.0
            if (fileMs < 0.0 && fileMs > -2500.0) fileMs = 0.0
            else if (fileMs > endMs && fileMs < endMs + 2500.0) fileMs = endMs
        }
        if (v == null || !v.frameAt((fileMs * 1000.0).toLong())) {
            if (!quiet) drawCard(key, x, y, w, h, radius, alpha)
            return false
        }
        val (m, dm) = geoOf(key, v)
        if (job.flipFill == "blur" && flipsMs[key]?.any { fileMs >= it[0] - 30 && fileMs <= it[1] } == true) {
            quad(pBlur, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.tex, m, x, y, w, h, 0f, 0f, 1f, 1f, radius, alpha, packBlur(0.04f))
            return true
        }
        val sa = dm[0].toFloat() / dm[1]
        val da = w / h
        val ratio = if (sa > da) sa / da else da / sa
        // الكادر الثابت (الأساس): الكاميرا زي ما صورت — ولا لحاق وجه ولا تقريب (إلا جوّا المربع العائم)
        val still = !job.tpl.follow && !pipBox && faceZoom <= 0f
        val faceT = if (still) null else if (faceZoom > 0f || cell || pipBox) job.pipFaces[v.dev] else job.faces[v.dev]
        val faceNow = faceOf(key, v, faceT, fileMs)
        // شو يعبّي الفراغ: إعداد المصدر أولًا، ثم القالب
        val fm = setup.srcFill[key] ?: "auto"
        val fit = when {
            faceZoom > 0f -> false
            pipBox -> false // المربع العائم دايمًا معبّى (بدون صورة صغيرة وتمويه)
            fm == "zoom" -> false
            fm == "blur" || fm == "color" || fm == "image" -> ratio > 1.02f
            cell && key in job.crowd -> ratio > 1.15f // كاميرا فيها أكثر من شخص: الصورة كلها (ما بنقص حدا)
            cell && faceNow != null -> false // الشاشة المقسومة: قص على الوجه بدل صورة صغيرة بنص المربع
            else -> job.frame == "fit" || (job.frame == "smart" && ratio > 1.3f)
        }
        // الكادر بيلحق الناس الموجودين: شخص → عليه، شخصين → الاثنين، ولا حدا بينقص
        if (!still && !pipBox && faceZoom <= 0f && (cell || job.faces.containsKey(v.dev) || key in job.crowd) && fm != "blur" && fm != "color" && fm != "image") {
            val gc = groupCrop(key, v, fileMs, sa, da, side, push, pushSide)
            if (gc != null) {
                var kw = gc[2] / max(1f, zoom); var kh = gc[3] / max(1f, zoom)
                kw = kw.coerceAtMost(1f); kh = kh.coerceAtMost(1f)
                val cx = gc[0].mid(kw); val cy = gc[1].mid(kh)
                val vc = 1f - cy
                quad(pOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.tex, m, x, y, w, h, cx - kw / 2f, vc - kh / 2f, cx + kw / 2f, vc + kh / 2f, radius, alpha)
                drawMap[key] = floatArrayOf(x, y, w, h, cx - kw / 2f, cy - kh / 2f, kw, kh)
                return true
            }
        }
        if (fit) {
            drawFit(v, m, sa, x, y, w, h, radius, alpha, zoom, if (fm == "auto") job.tpl.fitBg else fm)
            val fw0: Float; val fh0: Float
            if (sa > da) { fw0 = w; fh0 = w / sa } else { fh0 = h; fw0 = h * sa }
            val c0 = 1f / max(1f, zoom)
            drawMap[key] = floatArrayOf(x + (w - fw0) / 2f, y + (h - fh0) / 2f, fw0, fh0, 0.5f - c0 / 2f, 0.5f - c0 / 2f, c0, c0)
            return true
        }
        var kw = 1f
        var kh = 1f
        if (sa > da) kw = da / sa else kh = sa / da
        // على الوجه: الزوم حسب حجم الوجه (الشخص قريب = زوم أقل)
        var z = if (faceZoom > 0f && faceNow != null) max(zoom, PodLayout.adaptiveZoom(PodLayout.faceTarget(faceZoom), faceNow[2], kh)) else zoom
        // الوجه على طرف الصورة الأصلية (جزء منه برّا): بلا زوم زيادة، عشان ما نقص على الصدر
        if (faceZoom > 0f && faceNow != null && PodLayout.faceAtEdge(faceNow)) z = min(z, max(1f, zoom))
        if (z > 1.001f) {
            val lim = PodEngine.zoomLimit((kw * dm[0]).toDouble(), w.toDouble(), z)
            if (lim < z - 0.001f && zoomLimited.add(key)) warnings += tx("الزوم محدود لكاميرا ${nameOf(key)} بسبب دقتها", "Zoom limited for ${nameOf(key)}'s camera by its resolution")
            z = lim
        }
        kw /= z; kh /= z
        var cx = 0.5f
        var cy = 0.5f
        val face = faceNow
        if (face != null) { cx = face[0]; cy = face[1] + face[2] * (if (faceZoom > 0f) 0.05f else 0.15f) }
        if ((faceZoom <= 0f && !cell && !pipBox) || face == null) {
            setup.panX[key]?.let { if (abs(it) > 0.001f) cx = 0.5f + it.coerceIn(-1f, 1f) * 0.5f }
            setup.panY[key]?.let { if (abs(it) > 0.001f) cy = 0.5f + it.coerceIn(-1f, 1f) * 0.5f }
        }
        cx = cx.mid(kw)
        cy = cy.mid(kh)
        val u0 = cx - kw / 2f
        val u1 = cx + kw / 2f
        val vc = 1f - cy
        val v0 = vc - kh / 2f
        val v1 = vc + kh / 2f
        quad(pOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.tex, m, x, y, w, h, u0, v0, u1, v1, radius, alpha)
        drawMap[key] = floatArrayOf(x, y, w, h, u0, cy - kh / 2f, kw, kh)
        return true
    }

    /**
     * الشكل الخاص الدوري: كل [PodTemplate.lookEvery] دقيقة، أول لقطات كاملة بعدها بتطلع بالشكل الخاص
     * لحد ما كل كاميرا تطلع [PodTemplate.lookShots] مرات (أو دقيقتين كحد أقصى)، وبعدين العادي.
     * 0 = عادي، 1 = الكاميرا زي ما هي، 2 = قصّك.
     */
    private val looks: IntArray by lazy {
        val tp = job.tpl
        val r = IntArray(shots.size)
        if (tp.lookEvery <= 0f || shots.isEmpty()) return@lazy r
        val every = tp.lookEvery * 60.0
        val mode = if (tp.lookMode == "crop") 2 else 1
        val t0 = shots.first().start
        var next = t0 + every
        var inLook = false; var lookStart = 0.0
        val cnt = HashMap<Int, Int>()
        val cams = shots.filter { it.type == PodShot.FULL }.map { it.main }.distinct()
        for ((i, sh) in shots.withIndex()) {
            if (!inLook && sh.start >= next) { inLook = true; lookStart = sh.start; cnt.clear() }
            if (inLook) {
                if (sh.type == PodShot.FULL && (cnt[sh.main] ?: 0) < tp.lookShots) { r[i] = mode; cnt[sh.main] = (cnt[sh.main] ?: 0) + 1 }
                val done = cams.all { (cnt[it] ?: 0) >= tp.lookShots } || sh.end - lookStart > 120.0
                if (done) { inLook = false; next = sh.end + every }
            }
        }
        r
    }
    private fun lookOf(idx: Int) = looks.getOrElse(idx) { 0 }

    /** رسم الكاميرا بالشكل الخاص: كاملة زي ما هي (بدون قص ولا زوم)، أو بالقص اللي حددته */
    private fun drawLook(key: String, t: Double, W: Float, H: Float, alpha: Float, look: Int): Boolean {
        val v = srcFor(key) ?: return false
        val fileMs = v.time.fileMs((t * 1e9).toLong())
        if (!v.frameAt((fileMs * 1000.0).toLong())) return false
        val (m, dm) = geoOf(key, v)
        val sa = dm[0].toFloat() / dm[1]; val da = W / H
        val lc = setup.lookCrop[key]
        if (look == 2 && lc != null) {
            // القص محفوظ بإحداثيات الصورة بعد التدوير؛ إذا في قص للمصدر كمان بنحوّله لجوّاته
            var x = lc[0]; var y = lc[1]; var w = lc[2]; var h = lc[3]
            setup.srcCrop[key]?.let { c -> x = (x - c[0]) / c[2]; y = (y - c[1]) / c[3]; w /= c[2]; h /= c[3] }
            // بنفس مقاس الشاشة بالضبط (بدون مط): بنحافظ على الارتفاع وبنعدّل العرض حوالين النص
            var kh = h.coerceIn(0.05f, 1f); var kw = kh * da / sa
            if (kw > 1f) { kw = 1f; kh = kw * sa / da }
            val cx = (x + w / 2f).mid(kw); val cy = (y + h / 2f).mid(kh)
            val vc = 1f - cy
            quad(pOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.tex, m, 0f, 0f, W, H, cx - kw / 2f, vc - kh / 2f, cx + kw / 2f, vc + kh / 2f, 0f, alpha)
            drawMap[key] = floatArrayOf(0f, 0f, W, H, cx - kw / 2f, cy - kh / 2f, kw, kh)
            return true
        }
        // زي ما هي: الصورة كاملة، وإذا المقاس مختلف الفراغ بنسخة مموّهة
        drawFit(v, m, sa, 0f, 0f, W, H, 0f, alpha, 1f, "blur")
        val fw0: Float; val fh0: Float
        if (sa > da) { fw0 = W; fh0 = W / sa } else { fh0 = H; fw0 = H * sa }
        drawMap[key] = floatArrayOf((W - fw0) / 2f, (H - fh0) / 2f, fw0, fh0, 0f, 0f, 1f, 1f)
        return true
    }

    /** آخر رسم لكل مصدر: [x,y,w,h على الشاشة، u0,v0,kw,kh من المصدر] — عشان نعرف وين الوجوه طلعت بالضبط */
    private val drawMap = HashMap<String, FloatArray>()

    /** وين وجوه الناس بالمصدر طالعين على الشاشة هلأ (كلهم، مش بس الأكبر) — حسب آخر رسم لهالمصدر */
    private fun screenFaces(key: String, t: Double): PodLayout.Box? {
        val dm = drawMap[key] ?: return null
        val v = srcFor(key) ?: return null
        val tr = job.pipFaces[v.dev] ?: job.faces[v.dev] ?: return null
        val ms = v.time.fileMs((t * 1e9).toLong())
        fun toScreen(u: Float, vv: Float) = floatArrayOf(dm[0] + (u - dm[4]) / dm[6] * dm[2], dm[1] + (vv - dm[5]) / dm[7] * dm[3])
        val g = tr.groupAt(ms)
        val pts = ArrayList<FloatArray>()
        if (g != null && !g[FaceTrack.G_X0].isNaN()) {
            val a = mapPt(key, g[FaceTrack.G_X0], g[FaceTrack.G_Y0]); val b = mapPt(key, g[FaceTrack.G_X1], g[FaceTrack.G_Y1])
            pts += toScreen(a[0], a[1]); pts += toScreen(b[0], b[1])
        }
        faceOf(key, v, tr, ms)?.let { f ->
            val sa = srcAspectOf(v, key)
            val hw = f[2] / sa * 0.75f; val hh = f[2] * 0.85f
            pts += toScreen(f[0] - hw, f[1] - hh); pts += toScreen(f[0] + hw, f[1] + hh)
        }
        if (pts.isEmpty()) return null
        val x0 = pts.minOf { it[0] }; val x1 = pts.maxOf { it[0] }; val y0 = pts.minOf { it[1] }; val y1 = pts.maxOf { it[1] }
        // هامش: الراس والشعر وحركة خفيفة
        val mx = (x1 - x0) * 0.2f + 8f; val my = (y1 - y0) * 0.25f + 8f
        return PodLayout.Box(x0 - mx, y0 - my, x1 + mx, y1 + my)
    }

    private fun srcAspectOf(v: VDec, key: String): Float { val dm = geoOf(key, v).second; return dm[0].toFloat() / dm[1] }

    /** الصورة كاملة بدون قص، والفراغ حولها: نفسها مموّهة، أو لون، أو صورة خلفية */
    private fun drawFit(v: VDec, m: FloatArray, sa: Float, x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float, zoom: Float, bg: String) {
        val da = w / h
        when {
            bg == "color" -> solid(x, y, w, h, job.tpl.bgColor, radius, alpha)
            bg == "image" && bgTex != 0 -> coverBg(x, y, w, h, alpha)
            else -> {
                var kw = 1f; var kh = 1f
                if (sa > da) kw = da / sa else kh = sa / da
                val bu0 = 0.5f - kw / 2f; val bu1 = 0.5f + kw / 2f
                val bv0 = 0.5f - kh / 2f; val bv1 = 0.5f + kh / 2f
                quad(pBlur, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.tex, m, x, y, w, h, bu0, bv0, bu1, bv1, radius, alpha, packBlur(0.018f))
            }
        }
        // الصورة كاملة في الوسط
        val fw: Float; val fh: Float
        if (sa > da) { fw = w; fh = w / sa } else { fh = h; fw = h * sa }
        val z = max(1f, zoom)
        val c = 1f / z
        quad(pOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.tex, m, x + (w - fw) / 2f, y + (h - fh) / 2f, fw, fh,
            0.5f - c / 2f, 0.5f - c / 2f, 0.5f + c / 2f, 0.5f + c / 2f, 0f, alpha)
    }

    /** صورة الخلفية بملء المستطيل (قص الزائد) */
    private fun coverBg(x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        val sa = bgW.toFloat() / bgH
        val da = w / h
        var u0 = 0f; var u1 = 1f; var v0 = 0f; var v1 = 1f
        if (sa > da) { val k = da / sa; u0 = (1f - k) / 2f; u1 = 1f - u0 } else { val k = sa / da; v0 = (1f - k) / 2f; v1 = 1f - v0 }
        quad(p2d, GLES20.GL_TEXTURE_2D, bgTex, flip, x, y, w, h, u0, v0, u1, v1, 0f, alpha)
    }

    /** نصف قطر التمويه يُمرَّر في uColor.x (القناة الحمراء) */
    private fun packBlur(r: Float): Int = ((r * 255f / 0.05f).toInt().coerceIn(1, 255) shl 16) or (0xFF shl 24)

    // بطاقات الأسماء (عندما لا يوجد فيديو لشخص في هذه اللحظة)
    private val cardTex = HashMap<String, IntArray>()

    private fun drawCard(key: String, x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float) {
        solid(x, y, w, h, 0xFF1B1D24.toInt(), radius, alpha)
        if (w < 40f || h < 30f) return
        val name = nameOf(key)
        val e = cardTex.getOrPut(name) {
            try {
                val size = max(14f, min(w, h) * 0.12f)
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = 0xFFECEEF2.toInt(); typeface = android.graphics.Typeface.DEFAULT_BOLD }
                val tw = p.measureText(name).toInt().coerceAtLeast(8) + 8
                val th = (size * 1.4f).toInt()
                val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bmp).drawText(name, 4f, size, p)
                val t = IntArray(1)
                GLES20.glGenTextures(1, t, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                val r = intArrayOf(t[0], bmp.width, bmp.height)
                bmp.recycle()
                r
            } catch (_: Throwable) { intArrayOf(0, 1, 1) }
        }
        if (e[0] == 0) return
        val tw = min(e[1].toFloat(), w * 0.9f)
        val th = e[2] * tw / e[1]
        quad(p2d, GLES20.GL_TEXTURE_2D, e[0], flip, x + (w - tw) / 2f, y + (h - th) / 2f, tw, th, 0f, 0f, 1f, 1f, 0f, alpha)
    }

    /** آخر كاميرا نجح التعويض بها (نبدأ بها كي لا تقفز الصورة بين الكاميرات) */
    private var lastAlt: String? = null

    /** يعرض أول كاميرا عندها صورة الآن بدل كاميرا غائبة */
    private fun drawAny(t: Double, except: String, W: Float, H: Float, alpha: Float): Boolean {
        val keys = (listOfNotNull(lastAlt) + setup.wides.indices.map { "w$it" } + setup.people.indices.map { "p$it" }).distinct()
        for (k in keys) {
            if (k == except || srcFor(k) == null || inFlip(k, t)) continue
            // كاميرا فشلت قبل قليل لا نعيد فتحها كل إطار (نعيد المحاولة كل ثانية)
            val miss = altMiss[k]
            if (miss != null && abs(t - miss) < 1.0) continue
            if (drawSrc(k, t, 0f, 0f, W, H, 0f, alpha, quiet = true)) { lastAlt = k; altMiss.remove(k); return true }
            altMiss[k] = t
        }
        return false
    }

    private val altMiss = HashMap<String, Double>()

    private fun nameOf(key: String): String =
        if (key.startsWith("p")) setup.people.getOrNull(key.substring(1).toIntOrNull() ?: -1)?.name ?: key else tx("العامة", "Wide")

    /** منهج القلب: كل شخص بخانة آمنة (3:4–4:3) بأكبر حجم، والفراغ خلفية — SplitLayout */
    private fun grid(n: Int, x: Float, y: Float, w: Float, h: Float, gap: Float): List<FloatArray> {
        val g = max(gap, min(w, h) * 0.015f)
        return if (job.tpl.multiMain && n >= 3) SplitLayout.mainPlus(n - 1, x, y, w, h, g)
        else SplitLayout.equal(n, x, y, w, h, g, job.tpl.splitDir)
    }

    /**
     * مكان وجه الشخص على الشاشة لما يكون بملء الشاشة (نفس حساب drawSrc) — [x0,y0,x1,y1] بالبكسل، أو null.
     */
    private fun faceRectOut(key: String, t: Double, W: Float, H: Float, zoom: Float): FloatArray? = faceRectIn(key, t, 0f, 0f, W, H, zoom, false)

    /** مكان الوجه داخل مربع (x,y,w,h) على الشاشة — نفس قرارات drawSrc */
    private fun faceRectIn(key: String, t: Double, x: Float, y: Float, W: Float, H: Float, zoom: Float, cell: Boolean): FloatArray? {
        val v = srcFor(key) ?: return null
        val ft = job.pipFaces[v.dev] ?: job.faces[v.dev] ?: return null
        val fileMs = v.time.fileMs((t * 1e9).toLong())
        val f = faceOf(key, v, ft, fileMs)?.takeIf { it[2] > 0f } ?: return null
        val dm = geoOf(key, v).second
        val sa = dm[0].toFloat() / dm[1]
        val da = W / H
        val ratio = if (sa > da) sa / da else da / sa
        val fwN = f[2] / sa * 1.35f   // عرض الوجه (مع الشعر) كنسبة من عرض الصورة
        val fhN = f[2] * 1.5f
        val fm = setup.srcFill[key] ?: "auto"
        val fit = when {
            fm == "zoom" -> false
            fm == "blur" || fm == "color" || fm == "image" -> ratio > 1.02f
            cell && key in job.crowd -> ratio > 1.15f
            cell -> false
            else -> job.frame == "fit" || (job.frame == "smart" && ratio > 1.3f)
        }
        if (fit) {
            val fw: Float; val fh: Float
            if (sa > da) { fw = W; fh = W / sa } else { fh = H; fw = H * sa }
            val x0 = x + (W - fw) / 2f; val y0 = y + (H - fh) / 2f
            val cx = x0 + f[0] * fw; val cy = y0 + f[1] * fh
            return floatArrayOf(cx - fwN * fw / 2f, cy - fhN * fh / 2f, cx + fwN * fw / 2f, cy + fhN * fh / 2f)
        }
        var kw = 1f; var kh = 1f
        if (sa > da) kw = da / sa else kh = sa / da
        val z = max(1f, zoom)
        kw /= z; kh /= z
        var cx = 0.5f; var cy = 0.5f
        val follow = if (cell) f else faceOf(key, v, job.faces[v.dev], fileMs)
        follow?.let { cx = it[0]; cy = it[1] + it[2] * 0.15f }
        if (!cell || follow == null) {
            setup.panX[key]?.let { if (abs(it) > 0.001f) cx = 0.5f + it.coerceIn(-1f, 1f) * 0.5f }
            setup.panY[key]?.let { if (abs(it) > 0.001f) cy = 0.5f + it.coerceIn(-1f, 1f) * 0.5f }
        }
        cx = cx.mid(kw); cy = cy.mid(kh)
        val u0 = cx - kw / 2f; val v0 = cy - kh / 2f
        val ox = x + (f[0] - u0) / kw * W; val oy = y + (f[1] - v0) / kh * H
        val hw = fwN / kw * W / 2f; val hh = fhN / kh * H / 2f
        return floatArrayOf(ox - hw, oy - hh, ox + hw, oy + hh)
    }

    /** اللي ما لازم الكتابة تغطيه بهالإطار (وجوه، مربعات عائمة، نصوص ثابتة) + خط الفاصل لما يكونوا فوق وتحت */
    private val frameAvoid = ArrayList<PodLayout.Box>()
    private var frameDivider: Float? = null

    private fun avoidFace(fr: FloatArray?) { if (fr != null) frameAvoid.add(PodLayout.Box(fr[0], fr[1], fr[2], fr[3])) }

    /** لكل لقطة مربع عائم: هل نعكس الجهة (يمين/يسار) أو (فوق/تحت) عشان ما يغطي الوجه؟ */
    private val pipPlans = HashMap<Int, PodLayout.PipPlan>()
    private val fpsChecked = HashSet<String>()
    /** مربعات "كل شخص بمربع" لكل لقطة (بتتقرر مرة عشان ما تنط): [شخص، جهة] */
    private val peopleTiles = java.util.IdentityHashMap<PodShot, List<IntArray>>()
    private val pushSides = java.util.IdentityHashMap<PodShot, Int>()

    /** تقريب بطيء بلقطة على كاميرا فيها شخصين: بعد ٣ ثواني بيبلّش يقرّب على اللي بيحكي (لحد النص) — [مقدار، جهة] */
    private fun pushFor(sh: PodShot, t: Double): Pair<Float, Int> {
        if (sh.type != PodShot.FULL || sh.tag == "zoom" || !job.tpl.pushIn || !job.tpl.follow) return 0f to -1
        val key = "p${sh.main}"
        if ((job.count[key] ?: 0) == 1) return 0f to -1
        val v = srcFor(key) ?: return 0f to -1
        val tr = job.pipFaces[v.dev] ?: return 0f to -1
        val g = tr.groupAt(v.time.fileMs((t * 1e9).toLong())) ?: return 0f to -1
        if (g[FaceTrack.G_CNT] < 2f) return 0f to -1
        val k = ((t - sh.start - 3.0) / 8.0).coerceIn(0.0, 1.0).toFloat()
        if (k <= 0f) return 0f to -1
        val e = k * k * (3f - 2f * k)
        val side = pushSides.getOrPut(sh) { tr.talkerSide(v.time.fileMs((((sh.start + sh.end) / 2) * 1e9).toLong()), max(4000.0, (sh.end - sh.start) * 500.0)) }
        return 0.5f * e to side
    }

    /** مكان العنوان (بيتقرر مرة) */
    private var titleCy: Float? = null

    /** الوجوه على الشاشة بلحظة t (للعنوان): وجه اللي بالشاشة، أو وجوه الشاشة المقسومة */
    private fun facesAt(t: Double, W: Float, H: Float): List<PodLayout.Box> {
        val sh = shots.firstOrNull { t >= it.start && t < it.end } ?: shots.firstOrNull() ?: return emptyList()
        val out = ArrayList<PodLayout.Box>()
        when (sh.type) {
            PodShot.FULL, PodShot.PIP -> {
                val fr = faceRectOut("p${sh.main}", t, W, H, 1f)
                out += if (fr != null) PodLayout.Box(fr[0], fr[1], fr[2], fr[3]) else PodLayout.assumedFace(W, H)
                screenFaces("p${sh.main}", t)?.let { out += it }
                if ("p${sh.main}" in job.crowd) out += PodLayout.Box(W * 0.1f, H * 0.2f, W * 0.9f, H * 0.6f)
            }
            PodShot.MULTI -> {
                val who = (if (sh.others.isNotEmpty()) sh.others else intArrayOf(sh.main)).sortedArray()
                for (c in grid(who.size, 0f, 0f, W, H, if (job.tpl.gap <= 0f) 0f else max(1f, H * job.tpl.gap))) out += PodLayout.Box(c[0] + c[2] * 0.25f, c[1] + c[3] * 0.1f, c[0] + c[2] * 0.75f, c[1] + c[3] * 0.7f)
            }
        }
        return out
    }
    /** مربعات عائمة بهالإطار (العناصر الثابتة بتبعد عنها) */
    private val framePips = ArrayList<PodLayout.Box>()

    /** ترتيب كل لقطة مربع عائم (للتناوب بالشكل والجهة) */
    private val pipOrd: IntArray by lazy { var k = 0; IntArray(shots.size) { i -> if (shots[i].type == PodShot.PIP) k++ else k } }

    /** حركات القالب على الصورة الرئيسية: تقريب سريع على الكلمات المهمة + تكبير خفيف مع بداية كل لقطة */
    private fun motion(sh: PodShot, t: Double): Float {
        var k = 1f
        val tp = job.tpl
        if (tp.trans == "zoom") {
            val d = t - sh.start
            if (d in 0.0..0.28) k *= 1f + 0.12f * (1f - (d / 0.28).toFloat()).let { it * it }
        }
        if (job.punches.isNotEmpty() && sh.type == PodShot.FULL) {
            // ادخل بسرعة (٠٫٠٨ث)، اثبت، واطلع بنعومة
            val p = job.punches.lastOrNull { it <= t + 1e-6 }
            if (p != null) {
                val d = t - p
                val amt = tp.punchAmt - 1f
                val f = when { d < 0.08 -> (d / 0.08).toFloat(); d < 1.3 -> 1f; d < 1.6 -> (1f - ((d - 1.3) / 0.3).toFloat()); else -> 0f }
                k *= 1f + amt * f.coerceIn(0f, 1f)
            }
        }
        return k
    }

    /** الشاشة المقسومة: وجوه كل مربع + خط الفاصل (شخص فوق وشخص تحت) */
    private fun noteCells(who: IntArray, cells: List<FloatArray>, t: Double) {
        if (job.captions.isEmpty()) return
        for ((i, c) in cells.withIndex()) avoidFace(faceRectIn("p${who[i]}", t, c[0], c[1], c[2], c[3], 1f, true))
        if (cells.size == 2 && cells[1][1] > cells[0][1] + 1f && abs(cells[1][0] - cells[0][0]) < 1f) frameDivider = (cells[0][1] + cells[0][3] + cells[1][1]) / 2f
    }

    private fun drawShot(sh: PodShot, t: Double, alpha: Float, idx: Int = 0) {
        val W = outW.toFloat()
        val H = outH.toFloat()
        val z = if (job.zooms.isEmpty()) 1f else PodEngine.zoomAt(job.zooms, t)
        when (sh.type) {
            PodShot.FULL -> {
                val k = "p${sh.main}"
                // كسر الملل بالتقريب: نفس الشخص بزوم أقرب
                val zf = (if (sh.tag == "zoom") max(z * 1.3f, 1.3f) else z) * motion(sh, t)
                if (job.captions.isNotEmpty()) avoidFace(faceRectOut(k, t, W, H, zf))
                if (job.flipFill == "other" && inFlip(k, t) && drawAny(t, k, W, H, alpha)) { /* كاميرا أخرى أثناء القلب */ }
                else if (lookOf(idx) > 0 && drawLook(k, t, W, H, alpha, lookOf(idx))) { /* الشكل الخاص الدوري */ }
                else if (!pushFor(sh, t).let { (pu, sd) -> drawSrc(k, t, 0f, 0f, W, H, 0f, alpha, zf, quiet = job.fill == "other", push = pu, pushSide = sd) }) {
                    if (job.fill == "other" && !drawAny(t, k, W, H, alpha)) drawCard(k, 0f, 0f, W, H, 0f, alpha)
                }
                if (job.captions.isNotEmpty()) screenFaces(k, t)?.let { frameAvoid.add(it) }
            }
            PodShot.WIDE -> {
                val k = "w${sh.main}"
                if (!drawSrc(k, t, 0f, 0f, W, H, 0f, alpha, quiet = job.fill == "other")) {
                    if (job.fill == "other" && !drawAny(t, k, W, H, alpha)) drawCard(k, 0f, 0f, W, H, 0f, alpha)
                }
            }
            PodShot.PIP -> {
                drawSrc("p${sh.main}", t, 0f, 0f, W, H, 0f, alpha, z * motion(sh, t))
                val tp = job.tpl
                val ord = pipOrd.getOrElse(idx) { 0 }
                val circle = tp.pipShape == "circle" || (tp.pipShape == "alternate" && ord % 2 == 0)
                val sz = PodLayout.pipSize(tp, vertical, W, circle)
                val b = if (tp.pipBorder > 0f) max(2f, H * tp.pipBorder) else 0f
                val capsOn = job.captions.isNotEmpty()
                // المكان: معكوس حسب الشخص أو بالتناوب، ثم (إذا مفعّل) بعيد عن وجه اللي بيحكي
                val mx0 = (tp.pipPlace == "person" && (sh.others.firstOrNull() ?: 0) % 2 == 1) || (tp.pipPlace == "alternate" && ord % 2 == 1)
                // بعيد عن وجه اللي بالشاشة: الجهة الثانية، أو بيصغر، أو بتنقسم الشاشة (القرار مرة لكل لقطة)
                val plan = pipPlans.getOrPut(idx) {
                    // الوجوه زي ما طلعت فعلًا على الشاشة (بعد الزوم والقص)، ولو ما في معلومات: بنفترضها بالنص
                    var face: PodLayout.Box? = null
                    for (tt in doubleArrayOf(t, (sh.start + sh.end) / 2, sh.end - 0.1)) {
                        val f = screenFaces("p${sh.main}", tt) ?: continue
                        face = face?.let { PodLayout.Box(min(it.left, f.left), min(it.top, f.top), max(it.right, f.right), max(it.bottom, f.bottom)) } ?: f
                    }
                    // ما في وجوه معروفة → بنفترض وجه بالنص (أحسن من نحط المربع عشوائي)
                    if (face == null) face = PodLayout.assumedFace(W, H)
                    PodLayout.pipPlan(tp, W, H, vertical, sh.others.size, sz[0], sz[1], mx0, capsOn, face)
                }
                if (plan.split) {
                    // المربع رح يغطي الوجه → الشاشة كلها على الشخص اللي كان رح يطلع بالمربع
                    drawShot(PodShot(sh.start, sh.end, PodShot.FULL, sh.others.firstOrNull() ?: sh.main, IntArray(0)), t, alpha, -1)
                    return
                }
                val pw = sz[0] * plan.scale; val ph = sz[1] * plan.scale
                val r = when { circle -> pw / 2f; tp.pipShape == "rect" -> 0f; else -> min(pw, ph) * tp.pipRadius }
                val rects = PodLayout.pipRects(tp, W, H, vertical, sh.others.size, pw, ph, plan.mirrorX, plan.flipY, capsOn)
                framePips.addAll(rects)
                if (capsOn) { avoidFace(faceRectOut("p${sh.main}", t, W, H, z)); screenFaces("p${sh.main}", t)?.let { frameAvoid.add(it) }; for (rc in rects) frameAvoid.add(rc) }
                // ظهور متحرك عند بداية اللقطة
                val ap = if (tp.pipAnim == "cut" || tp.pipAnimMs <= 0) 1f else ((t - sh.start) / (tp.pipAnimMs / 1000.0)).toFloat().coerceIn(0f, 1f)
                val e = 1f - (1f - ap) * (1f - ap)
                for ((i, o) in sh.others.withIndex()) {
                    val rc = rects.getOrNull(i) ?: continue
                    val px = rc.left; val py = rc.top
                    var a2 = alpha; var sw = pw; var sh2 = ph; var ox = px; var oy = py
                    when (tp.pipAnim) {
                        "fade" -> a2 = alpha * e
                        "slide" -> ox = if (px + pw / 2f < W / 2f) px - (1f - e) * (px + pw) else px + (1f - e) * (W - px)
                        "zoom" -> { val k = 0.6f + 0.4f * e; sw = pw * k; sh2 = ph * k; ox = px + (pw - sw) / 2f; oy = py + (ph - sh2) / 2f; a2 = alpha * e }
                    }
                    val rr = if (circle) sw / 2f else r * (sw / pw)
                    if (tp.pipShadow) solid(ox + b + H * 0.006f, oy + b + H * 0.008f, sw, sh2, 0x66000000, rr + b, a2 * 0.8f)
                    if (b > 0f) solid(ox - b, oy - b, sw + 2 * b, sh2 + 2 * b, tp.pipBorderColor, rr + b, a2)
                    drawSrc("p$o", t, ox, oy, sw, sh2, rr, a2, faceZoom = if (tp.pipFace) tp.faceZoom else 0f, pipBox = true)
                    if (job.glow && speaking(o, t)) glowFrame(ox, oy, sw, sh2, a2)
                }
            }
            else -> {
                val who0 = if (sh.others.isNotEmpty()) sh.others else intArrayOf(sh.main)
                // اللي بدون كاميرا (مايك بس) ما بياخد مربع
                val who = who0.filter { setup.people.getOrNull(it)?.camDev?.isNotEmpty() == true }.distinctBy { setup.people[it].camDev }.toIntArray().let { if (it.isEmpty()) who0 else it }.sortedArray()
                // كل كاميرا بتنعرض مرة وحدة بس (لو شخصين مسجلين على نفس الكاميرا ما بتتكرر)
                fun camOf(p: Int) = setup.people.getOrNull(p)?.camDev ?: ""
                val allCams = setup.people.indices.filter { camOf(it).isNotEmpty() }.distinctBy { camOf(it) }
                val crowdSession = allCams.any { "p$it" in job.crowd || (job.count["p$it"] ?: 0) >= 2 }
                // جلسة فيها كاميرا عليها أكثر من شخص (زي ٤ أشخاص على كاميرتين): الشكل الوحيد = كل شخص بزاوية
                if (job.tpl.follow && (crowdSession || job.tpl.multi == "people")) {
                    val rows = peopleTiles.getOrPut(sh) {
                        val cams = if (crowdSession) allCams else who.toList().distinctBy { camOf(it) }
                        val l = ArrayList<IntArray>()
                        for (p in cams) l.add(intArrayOf(p, if ((job.count["p$p"] ?: 0) != 1 && canSplit("p$p", sh, W / H)) 1 else 0))
                        l
                    }
                    if (bgTex != 0) coverBg(0f, 0f, W, H, alpha) else solid(0f, 0f, W, H, job.tpl.bgColor, 0f, alpha)
                    val pad = H * 0.03f; val gap = H * 0.02f
                    val cells = ArrayList<Pair<FloatArray, IntArray>>() // مربع → (شخص، جهة)
                    if (rows.size <= 2) {
                        // صف لكل كاميرا: اللي فيها شخصين بتنقسم لمربعين جنب بعض، واللي فيها واحد بتاخد الصف كله
                        val rh = (H - 2 * pad - gap * (rows.size - 1)) / rows.size
                        for ((ri, r) in rows.withIndex()) {
                            val y = pad + ri * (rh + gap)
                            if (r[1] == 1) {
                                val cw = (W - 2 * pad - gap) / 2f
                                cells += floatArrayOf(pad, y, cw, rh) to intArrayOf(r[0], 0)
                                cells += floatArrayOf(pad + cw + gap, y, cw, rh) to intArrayOf(r[0], 1)
                            } else cells += floatArrayOf(pad, y, W - 2 * pad, rh) to intArrayOf(r[0], -1)
                        }
                    } else {
                        for ((i, c) in grid(rows.size, pad, pad, W - 2 * pad, H - 2 * pad, gap).withIndex()) cells += c to intArrayOf(rows[i][0], -1)
                    }
                    for ((c, tl) in cells) {
                        drawSrc("p${tl[0]}", t, c[0], c[1], c[2], c[3], H * job.tpl.boxRadius, alpha, cell = true, side = tl[1])
                        if (job.glow && speaking(tl[0], t)) glowFrame(c[0], c[1], c[2], c[3], alpha)
                    }
                    return
                }
                if (job.tpl.multi == "boxes") {
                    if (bgTex != 0) coverBg(0f, 0f, W, H, alpha)
                    else solid(0f, 0f, W, H, if (job.tpl.multi == "boxes") job.tpl.bgColor else setup.bgColor, 0f, alpha)
                    // مربعات أكبر: فراغ قليل حواليها
                    val pad = H * 0.03f
                    val cells = grid(who.size, pad, pad, W - 2 * pad, H - 2 * pad, H * 0.02f)
                    noteCells(who, cells, t)
                    for ((i, c) in cells.withIndex()) {
                        drawSrc("p${who[i]}", t, c[0], c[1], c[2], c[3], H * job.tpl.boxRadius, alpha, cell = true)
                        if (job.glow && who.size > 1 && speaking(who[i], t)) glowFrame(c[0], c[1], c[2], c[3], alpha)
                    }
                } else {
                    if (bgTex != 0 && who.size > 2) coverBg(0f, 0f, W, H, alpha) else solid(0f, 0f, W, H, job.tpl.gapColor, 0f, alpha)
                    val cells = grid(who.size, 0f, 0f, W, H, if (job.tpl.gap <= 0f) 0f else max(1f, H * job.tpl.gap))
                    noteCells(who, cells, t)
                    for ((i, c) in cells.withIndex()) {
                        drawSrc("p${who[i]}", t, c[0], c[1], c[2], c[3], 0f, alpha, cell = true)
                        if (job.glow && who.size > 1 && speaking(who[i], t)) glowFrame(c[0], c[1], c[2], c[3], alpha)
                    }
                }
            }
        }
    }

    // ───────── اسم الشخص (حسب القالب) ─────────
    private val nameTex = HashMap<String, IntArray>()
    private val namedOnce = HashSet<Int>()
    private var nameShot = -1

    private fun drawName(sh: PodShot, t: Double, idx: Int) {
        val tp = job.tpl
        if (tp.names == "never" || (sh.type != PodShot.FULL && sh.type != PodShot.PIP)) return
        val p = sh.main
        val name = setup.people.getOrNull(p)?.name ?: return
        if (tp.names == "first") {
            if (nameShot != idx && p in namedOnce) return
            if (nameShot != idx) { namedOnce.add(p); nameShot = idx }
        }
        val since = t - sh.start
        val st = TypeAnim.at(name, since, tp.nameSecs, tp.nameAnim) ?: return
        val shown = st.first
        if (shown.isEmpty()) return
        val a = st.second
        val W = outW.toFloat(); val H = outH.toFloat()
        val size = (if (vertical) H * 0.030f else H * 0.045f) * tp.nameSize
        val pad = size * 0.55f
        val e = nameTex.getOrPut("$shown|${tp.nameBg}") {
            try {
                val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = tp.nameFg; typeface = VidFonts.typeface(ctx, tp.nameFont.ifBlank { tp.font }) }
                val tw = pt.measureText(shown)
                val bmp = Bitmap.createBitmap((tw + pad * 2).toInt().coerceAtLeast(8), (size * 1.7f).toInt(), Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                if (tp.nameBg) c.drawRoundRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), bmp.height / 2f, bmp.height / 2f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = tp.nameColor })
                else c.drawText(shown, pad, size * 1.2f, android.graphics.Paint(pt).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = size * 0.14f; color = 0xE6000000.toInt(); strokeJoin = android.graphics.Paint.Join.ROUND })
                c.drawText(shown, pad, size * 1.2f, pt)
                val tx2 = IntArray(1)
                GLES20.glGenTextures(1, tx2, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tx2[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                val r = intArrayOf(tx2[0], bmp.width, bmp.height)
                bmp.recycle()
                r
            } catch (_: Throwable) { intArrayOf(0, 1, 1) }
        }
        if (e[0] == 0) return
        val w = e[1].toFloat(); val h = e[2].toFloat()
        val m = min(W, H) * 0.045f
        val rtl = name.any { it in '\u0600'..'\u06FF' }
        // العرض بيكبر مع الكتابة: بنثبّت الطرف اللي بتبلش منه الكتابة
        val fullW = nameFullW.getOrPut("$name|${tp.nameSize}") { android.graphics.Paint().apply { textSize = size; typeface = VidFonts.typeface(ctx, tp.nameFont.ifBlank { tp.font }) }.measureText(name) + pad * 2 }
        val x0 = if (tp.nameX >= 0f) tp.nameX * W - fullW / 2f else when (tp.namePos) { "bc" -> (W - fullW) / 2f; "br", "tr" -> W - fullW - m; else -> m }
        val x = if (rtl) x0 + fullW - w else x0
        val y = if (tp.nameY >= 0f) tp.nameY * H - h / 2f else if (tp.namePos.startsWith("t")) m + (if (vertical) H * 0.06f else 0f) else (if (vertical) H * 0.62f else H - h - m * 2.2f)
        quad(p2d, GLES20.GL_TEXTURE_2D, e[0], flip, x, y, w, h, 0f, 0f, 1f, 1f, 0f, a)
    }
    private val nameFullW = HashMap<String, Float>()

    /** الأشخاص المثبّتون يظهرون طول الوقت: مربعات صغيرة لمن لا يظهر في اللقطة الحالية */
    private fun drawPinned(sh: PodShot, t: Double) {
        if (job.pinned.isEmpty() || sh.type == PodShot.WIDE) return
        val shown = keysOf(sh).toSet()
        val todo = job.pinned.filter { "p$it" !in shown && it < setup.people.size }
        if (todo.isEmpty()) return
        val W = outW.toFloat(); val H = outH.toFloat()
        if (job.glow && sh.type == PodShot.FULL && speaking(sh.main, t)) glowFrame(0f, 0f, W, H, 1f)
        val tp = job.tpl
        val pw = W * (if (vertical) min(0.45f, tp.pinSize * 1.5f) else tp.pinSize)
        val circle = tp.pinShape == "circle"
        val ph = if (circle) pw else if (vertical) pw * 4f / 3f else pw * 9f / 16f
        val b = max(3f, H / 240f)
        val r = if (circle) pw / 2f else if (tp.pinShape == "rect") 0f else ph * 0.1f
        val right = tp.pinPos.endsWith("r")
        val bottom = tp.pinPos.startsWith("b")
        for ((i, pi) in todo.withIndex()) {
            val px = if (right) W - W * 0.03f - pw - i * (pw + W * 0.02f) else W * 0.03f + i * (pw + W * 0.02f)
            val py = if (bottom) (if (vertical) H * 0.80f - ph else H * 0.95f - ph) else (if (vertical) H * 0.10f else H * 0.05f)
            solid(px - b, py - b, pw + 2 * b, ph + 2 * b, 0xFFFFFFFF.toInt(), r + b, 1f)
            drawSrc("p$pi", t, px, py, pw, ph, r, 1f)
            if (job.glow && speaking(pi, t)) glowFrame(px, py, pw, ph, 1f)
        }
    }

    private fun keysOf(sh: PodShot): List<String> = when (sh.type) {
        PodShot.FULL -> listOf("p${sh.main}")
        PodShot.WIDE -> listOf("w${sh.main}")
        PodShot.PIP -> listOf("p${sh.main}") + sh.others.map { "p$it" }
        else -> (if (sh.others.isNotEmpty()) sh.others.toList() else listOf(sh.main)).map { "p$it" }
    }

    // ───────── الكتابة على الفيديو والعنوان والمناطق الآمنة ─────────

    /** يرسم عبارة الكتابة الحالية والعنوان الافتتاحي كنسيج (Android يتولى العربية واتجاه RTL) */
    private inner class CapPainter {
        private var capTex = 0
        private var capKey = -1
        private var capW = 1
        private var capH = 1
        private var titleTex = 0
        private var titleW = 1
        private var titleH = 1
        private var ci = 0

        private fun upload(bmp: android.graphics.Bitmap, old: Int): Int {
            val t = if (old != 0) old else IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            return t
        }

        /** نص بحد أقصى سطرين، بإطار داكن؛ الكلمات المميزة (أو الكلمة الحالية) بلون التمييز */
        private fun textBitmap(words: List<String>, emph: Set<Int>, sizePx: Float, maxW: Int, bg: Boolean, color: Int = job.tpl.capColor, hiColor: Int = job.tpl.capHi, bgColor: Int = 0x99000000.toInt(), font: String = job.tpl.font): android.graphics.Bitmap {
            val sb = android.text.SpannableStringBuilder()
            for ((i, w) in words.withIndex()) {
                if (i > 0) sb.append(' ')
                val st = sb.length
                sb.append(w)
                if (i in emph) sb.setSpan(android.text.style.ForegroundColorSpan(hiColor), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val plain = sb.toString()
            val fill = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sizePx; this.color = color
                typeface = VidFonts.typeface(ctx, font)
            }
            val stroke = android.text.TextPaint(fill).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = sizePx * 0.14f; this.color = 0xE6000000.toInt(); strokeJoin = android.graphics.Paint.Join.ROUND }
            fun layout(text: CharSequence, p: android.text.TextPaint) = android.text.StaticLayout.Builder.obtain(text, 0, text.length, p, maxW)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL)
                .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setIncludePad(true).build()
            val lf = layout(sb, fill)
            val ls = layout(plain, stroke)
            val pad = (sizePx * 0.35f).toInt()
            var lw = 1f
            for (i in 0 until lf.lineCount) lw = max(lw, lf.getLineWidth(i))
            val bw = (lw.toInt() + pad * 2).coerceAtMost(maxW + pad * 2)
            val bh = lf.height + pad * 2
            val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            if (bg) c.drawRoundRect(0f, 0f, bw.toFloat(), bh.toFloat(), pad.toFloat(), pad.toFloat(), android.graphics.Paint().apply { this.color = bgColor; isAntiAlias = true })
            c.translate(pad + (bw - pad * 2 - maxW) / 2f, pad.toFloat())
            if (!bg) ls.draw(c)
            lf.draw(c)
            return bmp
        }

        private var capWord = -1
        private val itemTex = HashMap<String, IntArray>()
        private val itemFullW = HashMap<String, Float>()

        /** عنوان "أسطر": كل سطر بخلفية على قد عرضه (متل البودكاستات العربية) */
        private fun linesBitmap(title: String, sizePx: Float, maxW: Int, fg: Int, bgc: Int, alignStart: Boolean): android.graphics.Bitmap {
            val p = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx; color = fg; typeface = VidFonts.typeface(ctx, job.tpl.font) }
            val lay = android.text.StaticLayout.Builder.obtain(title, 0, title.length, p, maxW)
                .setTextDirection(android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL).setMaxLines(4).setIncludePad(false).build()
            val lines = (0 until lay.lineCount).map { title.substring(lay.getLineStart(it), lay.getLineEnd(it)).trim() }.filter { it.isNotEmpty() }
            val padX = sizePx * 0.35f; val padY = sizePx * 0.12f
            val lh = sizePx * 1.32f
            val widths = lines.map { p.measureText(it) + padX * 2 }
            val bw = ((widths.maxOrNull() ?: 10f)).toInt().coerceAtLeast(10)
            val bh = (lines.size * (lh + padY * 2)).toInt().coerceAtLeast(10)
            val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val bgP = android.graphics.Paint().apply { color = bgc; isAntiAlias = true }
            val rtl = lines.firstOrNull()?.let { l -> l.any { ch -> ch in '\u0600'..'\u06FF' } } ?: true
            var y = 0f
            for ((i, l) in lines.withIndex()) {
                val w = widths[i]
                val x = if (!alignStart) (bw - w) / 2f else if (rtl) bw - w else 0f
                c.drawRect(x, y, x + w, y + lh + padY * 2, bgP)
                c.drawText(l, x + padX, y + padY + sizePx * 1.0f, p)
                y += lh + padY * 2
            }
            return bmp
        }

        private var capYKey = -1
        private var capY = 0f
        private val itemImg = HashMap<Int, IntArray>()

        fun draw(t: Double, tOut: Double) {
            val W = outW.toFloat()
            val H = outH.toFloat()
            val tp = job.tpl
            val tt = tOut + job.outOffset
            val total = if (job.totalOut > 0) job.totalOut else outDur
            val secs = job.titleSecs
            // ── عنوان كبير بالنص: تعتيم خفيف وراه
            val hookOn = job.title.isNotBlank() && tt < secs
            if (hookOn && tp.hookStyle == "big") {
                val a = (min(tt / 0.25, (secs - tt) / 0.3)).toFloat().coerceIn(0f, 1f)
                solid(0f, 0f, W, H, 0x73000000, 0f, a)
            }
            // ── العناصر الثابتة (رابط، اسم الحساب، شعار…) — قبل الكتابة عشان الكتابة تبعد عنها
            if (tp.items.isNotEmpty()) {
                val perPos = HashMap<String, Int>()
                for ((i, it) in tp.items.withIndex()) {
                    val isImg = it.kind == "image"
                    val txt = if (isImg) "" else Brand.fill(it.text, job.brand)
                    if (!isImg && txt.isBlank()) continue
                    if (isImg && (it.image.isBlank() || !File(it.image).exists())) continue
                    val idx = perPos.getOrDefault(it.pos, 0); perPos[it.pos] = idx + 1
                    val win = it.window(tt, total) ?: continue
                    // طريقة الظهور: كيبورد (للنص) / تلاشي / انزلاق / بدون
                    var shown = txt
                    var a = 1f
                    var dx = 0f
                    val since = tt - win[0]; val left = win[1] - tt
                    val ramp = (min(since / 0.3, left / 0.3)).toFloat().coerceIn(0f, 1f)
                    when {
                        it.anim == "type" && !isImg -> { shown = TypeAnim.inWindow(txt, since, win[1] - win[0])?.first ?: continue }
                        it.anim == "none" -> a = 1f
                        it.anim == "slide" -> { a = ramp; dx = (1f - ramp) * W * 0.06f * (if (L.ar) 1f else -1f) }
                        else -> a = ramp
                    }
                    if (a <= 0.01f) continue
                    val size = (if (vertical) H * 0.026f else H * 0.04f) * it.size
                    val e = if (isImg) itemImg.getOrPut(i) {
                        try {
                            val bmp = BitmapFactory.decodeFile(it.image)!!
                            val r = intArrayOf(upload(bmp, 0), bmp.width, bmp.height); bmp.recycle(); r
                        } catch (_: Throwable) { intArrayOf(0, 1, 1) }
                    } else itemTex.getOrPut("$i|$shown") {
                        val bmp = textBitmap(listOf(shown), emptySet(), size, (W * 0.9f).toInt(), it.bg, color = it.color, bgColor = 0x8C000000.toInt(), font = it.font.ifBlank { tp.font })
                        val r = intArrayOf(upload(bmp, 0), bmp.width, bmp.height); bmp.recycle(); r
                    }
                    if (e[0] == 0) continue
                    var iw = e[1].toFloat(); var ih = e[2].toFloat()
                    if (isImg) { ih = (if (vertical) H * 0.05f else H * 0.08f) * it.size; iw = ih * e[1] / e[2].toFloat() }
                    // الحجم الكامل (للنص اللي عم ينكتب): المكان ثابت على النص الكامل
                    val fullW = if (!isImg && shown != txt) itemFullW.getOrPut("$i") { textBitmap(listOf(txt), emptySet(), size, (W * 0.9f).toInt(), it.bg, color = it.color, font = it.font.ifBlank { tp.font }).let { b -> val w0 = b.width.toFloat(); b.recycle(); w0 } } else iw
                    // ثابت بمكانه (ما بيتحرك لو إجا مربع أو وجه)
                    val rr = PodLayout.itemRect(it.pos, fullW, ih, W, H, vertical, idx, cx = it.x, cy = it.y)
                    frameAvoid.add(rr)
                    val rtl = txt.any { ch -> ch in '\u0600'..'\u06FF' }
                    val x0 = if (rtl) rr.right - iw else rr.left
                    quad(p2d, GLES20.GL_TEXTURE_2D, e[0], flip, x0 + dx, rr.top, iw, rr.height(), 0f, 0f, 1f, 1f, 0f, a)
                }
            }
            // ── الكتابة: بعيد عن الوجوه والمربع العائم والنصوص، وعلى الفاصل لما يكونوا فوق وتحت
            val caps = job.captions
            if (caps.isNotEmpty() && !(hookOn && tp.hookStyle == "big")) {
                while (ci < caps.size - 1 && t >= caps[ci].b) ci++
                while (ci > 0 && t < caps[ci].a) ci--
                val c = caps[ci]
                if (t >= c.a && t < c.b + (if (tp.capStyle == "line") 0.0 else 0.15)) {
                    val wi = if (tp.capStyle == "line") -1 else c.wordAt(t)
                    if (capKey != ci || capWord != wi) {
                        val size = PodLayout.capTextSize(tp, vertical, H, job.capSize)
                        val hi = if (wi >= 0) setOf(wi) else c.emph
                        val bmp = textBitmap(c.words, hi, size, (W * (if (tp.capStyle == "line") 0.84f else 0.80f)).toInt(), tp.capStyle == "boxed")
                        capTex = upload(bmp, capTex); capW = bmp.width; capH = bmp.height; bmp.recycle()
                        capKey = ci; capWord = wi
                    }
                    // المكان بيتقرر مرة لكل مجموعة كلمات (ما بينط)
                    if (capYKey != ci) { capY = if (tp.capY >= 0f) tp.capY * H else PodLayout.capPlace(tp, vertical, W, H, capH.toFloat(), frameAvoid, frameDivider); capYKey = ci }
                    val k = if (tp.capStyle == "line") 1f else (0.86f + 0.14f * ((t - c.a) / 0.12).toFloat().coerceIn(0f, 1f))
                    val w = capW * k; val h = capH * k
                    val capCx = if (tp.capX >= 0f) tp.capX * W else W / 2f
                    quad(p2d, GLES20.GL_TEXTURE_2D, capTex, flip, capCx - w / 2f, capY - h / 2f, w, h, 0f, 0f, 1f, 1f, 0f, 1f)
                }
            }
            // ── العنوان الافتتاحي
            if (hookOn) {
                val lines = tp.hookStyle == "lines"
                val big = tp.hookStyle == "big"
                if (titleTex == 0) {
                    val size = tp.hookSize * when {
                        lines -> if (vertical) H * 0.048f else H * 0.075f
                        big -> if (vertical) H * 0.052f else H * 0.08f
                        else -> if (vertical) H * 0.034f else H * 0.06f
                    }
                    val bmp = if (lines) linesBitmap(job.title.trim(), size, (W * 0.72f).toInt(), tp.hookColor, tp.hookBg, tp.hookAlign == "start")
                        else textBitmap(job.title.trim().split(Regex("\\s+")), emptySet(), size, (W * (if (big) 0.86f else 0.80f)).toInt(), !big, color = tp.hookColor)
                    titleTex = upload(bmp, 0); titleW = bmp.width; titleH = bmp.height; bmp.recycle()
                }
                // دخول وطلوع
                val pin = (tt / 0.35).toFloat().coerceIn(0f, 1f); val pout = ((secs - tt) / 0.35).toFloat().coerceIn(0f, 1f)
                val ein = 1f - (1f - pin) * (1f - pin); val eout = 1f - (1f - pout) * (1f - pout)
                val phase = if (pin < 1f) tp.hookIn to ein else tp.hookOut to eout
                val e = if (phase.first == "none") 1f else phase.second
                var a = if (phase.first == "fade" || phase.first == "slide") e else 1f
                val w = titleW.toFloat(); val h = titleH.toFloat()
                val cy0 = when (tp.hookPos) { "top" -> if (vertical) H * 0.16f else H * 0.16f; "bottom" -> if (vertical) H * 0.68f else H * 0.80f; else -> if (vertical) H * 0.45f else H * 0.5f }
                // العنوان ما بينحط على وجه: بنختار مكانه (مرة وحدة) من مكانك أو فوق أو تحت، حسب مكان الوجوه
                val cy = if (tp.hookY >= 0f) tp.hookY * H else titleCy ?: run {
                    val fs = facesAt(t, W, H)
                    val cands = listOf(cy0, H * 0.16f, if (vertical) H * 0.74f else H * 0.82f)
                    val best = cands.minByOrNull { c -> fs.sumOf { f -> PodLayout.overlap(PodLayout.Box(W * 0.1f, c - h / 2f, W * 0.9f, c + h / 2f), f).toDouble() } + abs(c - cy0) * 0.001 } ?: cy0
                    titleCy = best; best
                }
                var x = when {
                    tp.hookAlign == "start" && L.ar -> W - w - W * 0.08f
                    tp.hookAlign == "start" -> W * 0.08f
                    else -> (W - w) / 2f
                }
                if (big) x = (W - w) / 2f
                if (tp.hookX >= 0f) x = tp.hookX * W - w / 2f
                if (phase.first == "slide") x += (1f - e) * W * 0.18f * (if (L.ar) 1f else -1f)
                if (phase.first == "wipe") {
                    // مسح: بيبين من اليمين لليسار (بالعربي)
                    val vis = w * e
                    if (vis >= 1f) {
                        if (L.ar) quad(p2d, GLES20.GL_TEXTURE_2D, titleTex, flip, x + w - vis, cy - h / 2f, vis, h, 1f - e, 0f, 1f, 1f, 0f, 1f)
                        else quad(p2d, GLES20.GL_TEXTURE_2D, titleTex, flip, x, cy - h / 2f, vis, h, 0f, 0f, e, 1f, 0f, 1f)
                    }
                } else {
                    if (big) { val k = 0.9f + 0.1f * ein; val ww = w * k; val hh = h * k; quad(p2d, GLES20.GL_TEXTURE_2D, titleTex, flip, x + (w - ww) / 2f, cy - hh / 2f, ww, hh, 0f, 0f, 1f, 1f, 0f, a) }
                    else quad(p2d, GLES20.GL_TEXTURE_2D, titleTex, flip, x, cy - h / 2f, w, h, 0f, 0f, 1f, 1f, 0f, a)
                }
            }
            // ── شريط التقدّم
            if (tp.progress && total > 0) {
                val bh = max(4f, H * 0.006f)
                val p = ((tt) / total).toFloat().coerceIn(0f, 1f)
                solid(0f, 0f, W, bh, 0x40FFFFFF, 0f, 1f)
                solid(0f, 0f, W * p, bh, tp.capHi, 0f, 1f)
            }
        }

        fun release() {
            if (capTex != 0) GLES20.glDeleteTextures(1, intArrayOf(capTex), 0)
            if (titleTex != 0) GLES20.glDeleteTextures(1, intArrayOf(titleTex), 0)
            for (e in itemTex.values + itemImg.values) if (e[0] != 0) GLES20.glDeleteTextures(1, intArrayOf(e[0]), 0)
            itemTex.clear(); itemImg.clear()
            capTex = 0; titleTex = 0
        }
    }

    /** أدلة المناطق الآمنة (في المعاينة فقط): أزرار المنصات أسفل/يمين، والعنوان أعلى، والكتابة */
    private fun drawGuides() {
        val W = outW.toFloat()
        val H = outH.toFloat()
        val c = 0x55FF3B30
        if (vertical) {
            solid(0f, H * 0.82f, W, H * 0.18f, c, 0f, 1f)
            solid(W * 0.86f, H * 0.45f, W * 0.14f, H * 0.37f, c, 0f, 1f)
            solid(0f, 0f, W, H * 0.08f, c, 0f, 1f)
        } else {
            solid(0f, H * 0.93f, W, H * 0.07f, c, 0f, 1f)
        }
    }

    // ───────── التنفيذ ─────────

    /** يعيد رسالة خطأ أو null عند النجاح، ويضع الناتج في [resultUri] (أو [RenderJob.outFile] للمعاينة) */
    var resultUri: Uri? = null

    fun run(): String? {
        if (shots.isEmpty() || dur <= 0.5 || outDur <= 0.5) return "Nothing to export"
        val tmp = File(ctx.cacheDir, "pod_${s.id}_video.mp4")
        tmp.delete()
        val err = renderVideo(tmp)
        if (err != null) { tmp.delete(); return err }
        val e2 = muxAudio(tmp)
        tmp.delete()
        return e2
    }

    private fun renderVideo(tmp: File): String? {
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        var muxStarted = false
        var inSurf: Surface? = null
        try {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            f.setInteger(MediaFormat.KEY_BIT_RATE, if (base <= 360) 1_200_000 else if (base >= 1080) 10_000_000 else 6_000_000)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc = e
            e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val isf = e.createInputSurface()
            inSurf = isf
            e.start()
            val m = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            initEgl(isf)
            buildProg(pOes, 0)
            buildProg(p2d, 1)
            buildProg(pSolid, 2)
            buildProg(pBlur, 3)
            loadBg()
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            var track = -1
            val info = MediaCodec.BufferInfo()
            fun drain(end: Boolean) {
                var waits = 0
                while (true) {
                    val i = e.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                    if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!end) return
                        if (++waits > 300 || cancelled()) return
                        continue
                    }
                    waits = 0
                    if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = m.addTrack(e.outputFormat); m.start(); muxStarted = true; continue }
                    if (i < 0) continue
                    val ob = e.getOutputBuffer(i)
                    if (ob != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxStarted) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        m.writeSampleData(track, ob, info)
                    }
                    e.releaseOutputBuffer(i, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }

            val total = ((introDur + outDur + outroDur) * fps).toInt()
            val fade = if (job.fade) (job.tpl.fadeMs / 1000.0).coerceIn(0.1, 1.5) else 0.0
            val cap = CapPainter()
            var si = 0
            val clipDec = HashMap<String, VDec>()
            for (fi in 0 until total) {
                if (cancelled()) return "Cancelled"
                val tAll = fi.toDouble() / fps
                // المقاطع قبل الحلقة (إنترو، فاصل) وبعدها (أوترو)
                if (tAll < introDur || tAll >= introDur + outDur) {
                    val isIntro = tAll < introDur
                    val list = if (isIntro) pre else post
                    var cs = if (isIntro) tAll else tAll - introDur - outDur
                    var ci = 0
                    while (ci < list.size - 1 && cs >= list[ci].dur) { cs -= list[ci].dur; ci++ }
                    val clip = list[ci]
                    for ((k2, v2) in clipDec.entries.toList()) if (k2 != clip.key) { try { v2.close() } catch (_: Throwable) {}; clipDec.remove(k2) }
                    val cv = clipDec.getOrPut(clip.key) { VDec(clip.key, "", clip.file, Finalizer.SatVideoTime(s, DevInfo("clip", "clip", "both"), 0L)).also { it.open() } }
                    val cd = clip.dur
                    GLES20.glViewport(0, 0, outW, outH)
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    if (cv.frameAt((cs * 1e6).toLong())) {
                        // بداية الفيديو ونهايته: تلاشي خفيف من/لأسود. الحد مع الحلقة: حسب الانتقال اللي اخترته. بين الإنترو والفاصل: قطع
                        val kind = if (isIntro) job.introTrans else job.outroTrans
                        val tr = if (isIntro) inTr else outTr
                        val big = 99.0
                        val edge = if (isIntro) (if (ci == list.size - 1) cd - cs else big) else (if (ci == 0) cs else big) // البعد عن الحلقة
                        val outer = if (isIntro) (if (ci == 0) cs else big) else (if (ci == list.size - 1) cd - cs else big) // البعد عن طرف الفيديو
                        drawClip(cv, clip.fx, (outer / 0.3).toFloat().coerceIn(0f, 1f))
                        if (tr > 0 && edge < tr) solid(0f, 0f, outW.toFloat(), outH.toFloat(), trColor(kind), 0f, (1f - (edge / tr).toFloat()).coerceIn(0f, 1f))
                        if (kind == "flash" && edge < 0.08) solid(0f, 0f, outW.toFloat(), outH.toFloat(), 0xFFFFFFFF.toInt(), 0f, 0.7f)
                    }
                    EGLExt.eglPresentationTimeANDROID(dpy, eglSurf, (tAll * 1e9).toLong())
                    EGL14.eglSwapBuffers(dpy, eglSurf)
                    drain(false)
                    if (fi % 15 == 0) progress(tx("إخراج الفيديو", "Rendering the video"), fi.toFloat() / total)
                    continue
                }
                val tOut = tAll - introDur
                val t = sessionT(tOut)
                while (si < shots.size - 1 && t >= shots[si].end) si++
                val cur = shots[si]
                // انتقال ناعم فقط إذا كان الحدّ بين اللقطتين داخل نفس الجزء (لا عند القفز فوق سكتة محذوفة)
                val segStart = segList[segAt(tOut)][0]
                val prev = if (si > 0 && fade > 0 && t - cur.start < fade && segStart <= cur.start) shots[si - 1] else null

                // فتح/إغلاق المفكّكات: نُبقي فقط ما يظهر الآن أو خلال ثانيتين
                val need = HashSet<String>()
                need.addAll(keysOf(cur))
                if (prev != null) need.addAll(keysOf(prev))
                var k = si + 1
                while (k < shots.size && shots[k].start < t + 2.0) { need.addAll(keysOf(shots[k])); k++ }
                if (cur.type == PodShot.FULL && setup.wides.isNotEmpty()) need.add("w0")
                for (pi in job.pinned) need.add("p$pi")
                lastAlt?.let { need.add(it) }
                for ((key, v) in sources) if (v != null && key !in need && v.dec != null) v.close()

                GLES20.glViewport(0, 0, outW, outH)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                frameAvoid.clear(); framePips.clear(); frameDivider = null
                if (prev != null) {
                    drawShot(prev, t, 1f, si - 1)
                    drawShot(cur, t, ((t - cur.start) / fade).toFloat().coerceIn(0f, 1f), si)
                } else drawShot(cur, t, 1f, si)
                // ومضة بيضا مع بداية كل لقطة (حسب القالب)
                if (job.tpl.trans == "flash" && si > 0 && t - cur.start in 0.0..0.16 && segStart <= cur.start)
                    solid(0f, 0f, outW.toFloat(), outH.toFloat(), 0xFFFFFFFF.toInt(), 0f, (0.65f * (1f - ((t - cur.start) / 0.16).toFloat())).coerceIn(0f, 1f))
                drawPinned(cur, t)
                drawName(cur, t, si)
                cap.draw(t, tOut)
                if (job.preview) drawGuides()

                // أول وآخر ثلث ثانية من الحلقة: تلاشي من/لأسود إذا في إنترو/أوترو
                if (introDur > 0) {
                    val tr = inTr
                    if (tr > 0 && tOut < tr) solid(0f, 0f, outW.toFloat(), outH.toFloat(), trColor(job.introTrans), 0f, (1f - (tOut / tr).toFloat()).coerceIn(0f, 1f))
                    if (job.introTrans == "flash" && tOut < 0.16) solid(0f, 0f, outW.toFloat(), outH.toFloat(), 0xFFFFFFFF.toInt(), 0f, (0.7f * (1f - (tOut / 0.16).toFloat())).coerceIn(0f, 1f))
                }
                if (outroDur > 0) {
                    val tr = outTr; val left = outDur - tOut
                    if (tr > 0 && left < tr) solid(0f, 0f, outW.toFloat(), outH.toFloat(), trColor(job.outroTrans), 0f, (1f - (left / tr).toFloat()).coerceIn(0f, 1f))
                    if (job.outroTrans == "flash" && left < 0.08) solid(0f, 0f, outW.toFloat(), outH.toFloat(), 0xFFFFFFFF.toInt(), 0f, 0.7f)
                }
                EGLExt.eglPresentationTimeANDROID(dpy, eglSurf, (tAll * 1e9).toLong())
                EGL14.eglSwapBuffers(dpy, eglSurf)
                drain(false)
                if (fi % 15 == 0) progress(if (job.preview) tx("تجهيز المعاينة", "Preparing the preview") else tx("إخراج الفيديو", "Rendering the video"), fi.toFloat() / total)
            }
            cap.release()
            for (v2 in clipDec.values) try { v2.close() } catch (_: Throwable) {}
            e.signalEndOfInputStream()
            drain(true)
            if (muxStarted) m.stop()
            return if (muxStarted) null else "Video encoder produced nothing"
        } catch (t: Throwable) {
            return "Render failed: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            for (v in sources.values) try { v?.close() } catch (_: Throwable) {}
            sources.clear()
            if (bgTex != 0) try { GLES20.glDeleteTextures(1, intArrayOf(bgTex), 0) } catch (_: Throwable) {}
            for (e in cardTex.values) if (e[0] != 0) try { GLES20.glDeleteTextures(1, intArrayOf(e[0]), 0) } catch (_: Throwable) {}
            cardTex.clear()
            for (e in nameTex.values) if (e[0] != 0) try { GLES20.glDeleteTextures(1, intArrayOf(e[0]), 0) } catch (_: Throwable) {}
            nameTex.clear()
            releaseEgl()
            try { enc?.stop() } catch (_: Throwable) {}
            try { enc?.release() } catch (_: Throwable) {}
            try { inSurf?.release() } catch (_: Throwable) {}
            try { mux?.release() } catch (_: Throwable) {}
            if (frameStarted) frameThread.quitSafely()
        }
    }

    private fun loadBg() {
        val tplImg = job.tpl.bgImage.takeIf { it.isNotBlank() && File(it).exists() }
        if (tplImg == null && (setup.multiStyle != "boxes" || setup.bgImage.isEmpty())) return
        try {
            val bmp: Bitmap = (if (tplImg != null) BitmapFactory.decodeFile(tplImg) else ctx.contentResolver.openInputStream(Uri.parse(setup.bgImage))?.use { BitmapFactory.decodeStream(it) }) ?: return
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            bgTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bgW = bmp.width; bgH = bmp.height
            bmp.recycle()
        } catch (_: Throwable) {
            warnings += "Background image could not be loaded"
        }
    }

    private fun initEgl(surface: Surface) {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { "eglInitialize failed" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, num, 0) && num[0] > 0) { "no EGL config" }
        eglCtx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(dpy, eglSurf, eglSurf, eglCtx)) { "eglMakeCurrent failed" }
    }

    private fun releaseEgl() {
        if (dpy == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, eglSurf)
        if (eglCtx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, eglCtx)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(dpy)
        dpy = EGL14.EGL_NO_DISPLAY
        eglCtx = EGL14.EGL_NO_CONTEXT
        eglSurf = EGL14.EGL_NO_SURFACE
    }

    // ───────── الصوت ─────────

    private class Mic(val src: PodAudioSrc, val people: List<Int>, var gain: Float) {
        var env = 1f
        // مرشّح تمرير عالٍ 80Hz (يزيل الطنين والهدير المنخفض) — حالته مستمرة بين الكتل
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
    }

    // معاملات مرشّح Butterworth عالي التمرير 80Hz عند 44.1kHz
    private val hb0: Float
    private val hb1: Float
    private val hb2: Float
    private val ha1: Float
    private val ha2: Float

    init {
        val w0 = 2.0 * Math.PI * 80.0 / 44100.0
        val alpha = Math.sin(w0) / (2.0 * 0.7071)
        val cs = Math.cos(w0)
        val a0 = 1.0 + alpha
        hb0 = ((1.0 + cs) / 2.0 / a0).toFloat()
        hb1 = (-(1.0 + cs) / a0).toFloat()
        hb2 = ((1.0 + cs) / 2.0 / a0).toFloat()
        ha1 = (-2.0 * cs / a0).toFloat()
        ha2 = ((1.0 - alpha) / a0).toFloat()
    }

    private fun muxAudio(videoFile: File): String? {
        val rate = 44100
        val introS = Math.round(introDur * rate)
        val mainS = Math.round(outDur * rate)
        val outroS = Math.round(outroDur * rate)
        val total = introS + mainS + outroS
        val introPcm = joinClipAudio(pre, rate, introS)
        val outroPcm = joinClipAudio(post, rate, outroS)
        val mics = ArrayList<Mic>()
        val byDev = LinkedHashMap<String, MutableList<Int>>()
        for ((i, p) in setup.people.withIndex()) {
            val m = PodcastAnalyzer.micOf(ctx, s, p)
            if (m.isNotEmpty()) byDev.getOrPut(m) { ArrayList() }.add(i)
            if (m != p.micDev && m.isNotEmpty()) warnings += tx("صوت ${p.name}: استُعمل مايك كاميرته لأن ملف مايكه لم يصل", "${p.name}: used the camera's audio because the mic file did not arrive")
        }
        for ((dev, ppl) in byDev) {
            val src = PodAudioSrc.open(ctx, s, dev)
            if (src == null) { warnings += tx("مايك ${setup.people[ppl[0]].name} لم يصل — تُرك", "Microphone of ${setup.people[ppl[0]].name} not received — skipped"); continue }
            var g = 1f
            if (setup.level && an != null) {
                val sp = ppl.map { an.speechDb.getOrElse(it) { -30f } }.maxOrNull() ?: -30f
                g = Math.pow(10.0, ((-20f - sp) / 20f).toDouble()).toFloat().coerceIn(0.5f, 8f)
            }
            mics.add(Mic(src, ppl, g))
        }
        if (mics.isEmpty()) warnings += tx("لا يوجد صوت مايكات — سيكون الفيديو صامتًا", "No microphone audio — the video will be silent")

        val block = 4096
        val sub = 256
        val tmpBuf = FloatArray(block + sub * 2 + 64)
        val bnd = DoubleArray(block / sub + 3)
        val envAt = FloatArray(block / sub + 2)
        val attack = 1f - Math.exp(-(sub.toDouble() / rate) / 0.05).toFloat()
        val release = 1f - Math.exp(-(sub.toDouble() / rate) / 0.30).toFloat()

        // بداية كل جزء على خط الإخراج بالعينات
        val segOutS = LongArray(segList.size) { Math.round(segOut[it] * rate) }

        /** يخلط cnt عيّنة تبدأ في المصفوفة عند off، وأول عيّنة تقابل اللحظة t0 من الجلسة */
        fun mixPiece(off: Int, cnt: Int, t0: Double, L: FloatArray, R: FloatArray) {
            val nb = (cnt + sub - 1) / sub
            for (mic in mics) {
                // غلاف التخفيض: 1 إذا صاحب المايك يتكلم أو لا أحد يتكلم، وإلا قيمة التخفيض
                for (j in 0..nb) {
                    val tSec = t0 + min(j * sub, cnt).toDouble() / rate
                    var target = 1f
                    if (an != null && setup.duck < 0.999f) {
                        val f = (tSec / an.frameSec).toInt()
                        if (f in 0 until an.frames) {
                            val mine = mic.people.any { an.active.getOrNull(it)?.get(f) == true }
                            val anyone = an.active.any { it[f] }
                            if (!mine && anyone) target = setup.duck
                        }
                    }
                    if (j > 0) mic.env += (target - mic.env) * (if (target > mic.env) attack else release)
                    envAt[j] = mic.env
                    bnd[j] = mic.src.nAt(tSec)
                }
                var lo = Double.MAX_VALUE
                var hi = -Double.MAX_VALUE
                for (j in 0..nb) { lo = min(lo, bnd[j]); hi = max(hi, bnd[j]) }
                if (hi < -2.0 || lo > mic.src.rd.frames + 2.0) continue
                val start = floor(lo).toLong() - 1L
                val c2 = (ceil(hi).toLong() + 3L - start).toInt()
                if (c2 <= 0 || c2 > tmpBuf.size) continue
                mic.src.rd.readChannel(start, c2, 0, tmpBuf)
                for (i in 0 until cnt) {
                    val seg = i / sub
                    val fr = (i - seg * sub).toDouble() / sub
                    val np = bnd[seg] + (bnd[seg + 1] - bnd[seg]) * fr
                    val fl = floor(np)
                    val w = (np - fl).toFloat()
                    val ix = (fl.toLong() - start).toInt()
                    if (ix < 0 || ix + 1 >= c2) continue
                    val g = mic.gain * (envAt[seg] + (envAt[seg + 1] - envAt[seg]) * fr.toFloat())
                    val raw = tmpBuf[ix] * (1f - w) + tmpBuf[ix + 1] * w
                    val hp = hb0 * raw + hb1 * mic.x1 + hb2 * mic.x2 - ha1 * mic.y1 - ha2 * mic.y2
                    mic.x2 = mic.x1; mic.x1 = raw; mic.y2 = mic.y1; mic.y1 = hp
                    val v = hp * g
                    L[off + i] += v; R[off + i] += v
                }
            }
        }

        /** صوت الحلقة: cnt عيّنة من km0 (على خط الحلقة بدون الإنترو)، بالمصفوفة من base */
        fun mainMix(km0: Long, cnt: Int, base: Int, L: FloatArray, R: FloatArray) {
            val k0 = km0; val n = cnt
            var k = k0
            while (k < k0 + n) {
                val j = segAt(k.toDouble() / rate).let { if (it + 1 < segOutS.size && k >= segOutS[it + 1]) it + 1 else it }
                val segEndS = if (j + 1 < segOutS.size) segOutS[j + 1] else total
                val cnt = min(k0 + n, max(segEndS, k + 1)) - k
                val t0 = segList[j][0] + (k - segOutS[j]).toDouble() / rate
                mixPiece(base + (k - k0).toInt(), cnt.toInt(), t0, L, R)
                // تلاشٍ قصير (6ms) عند حدود الأجزاء كي لا تُسمع طقطقة عند قص السكتات
                if (segList.size > 1) {
                    val ramp = 256L
                    for (q in 0 until cnt.toInt()) {
                        val pos = k + q
                        val fromStart = pos - segOutS[j]
                        val toEnd = segEndS - 1 - pos
                        val g = (min(fromStart, toEnd).toFloat() / ramp).coerceIn(0f, 1f)
                        if (g < 1f) { val idx = base + (pos - k0).toInt(); L[idx] *= g; R[idx] *= g }
                    }
                }
                k += cnt
            }
        }

        /** صوت الإنترو/الأوترو مع تلاشي خفيف بالأطراف */
        fun clipMix(pcm: FloatArray, len: Long, c0: Long, cnt: Int, base: Int, L: FloatArray, R: FloatArray, isIntro: Boolean) {
            // طرف الفيديو: ٠٫٣ث. الحد مع الحلقة: مدة الانتقال (قطع = سريع جدًا بدون طقة)
            val edgeRamp = rate * max(0.02, if (isIntro) inTr else outTr)
            val outerRamp = rate * 0.3
            for (i in 0 until cnt) {
                val pos = c0 + i
                val v = pcm.getOrElse(pos.toInt()) { 0f }
                val fromStart = pos.toDouble(); val toEnd = (len - pos).toDouble()
                val g = if (isIntro) min(fromStart / outerRamp, toEnd / edgeRamp).toFloat().coerceIn(0f, 1f) else min(fromStart / edgeRamp, toEnd / outerRamp).toFloat().coerceIn(0f, 1f)
                L[base + i] += v * g; R[base + i] += v * g
            }
        }

        val res = AudioMux.writeWithAudio(ctx, videoFile, total, rate, outName(), job.outFile, { k0, n, L, R ->
            java.util.Arrays.fill(L, 0, n, 0f)
            java.util.Arrays.fill(R, 0, n, 0f)
            var k = k0
            val end = k0 + n
            while (k < end) {
                when {
                    k < introS -> { val c = (min(end, introS) - k).toInt(); clipMix(introPcm, introS, k, c, (k - k0).toInt(), L, R, true); k += c }
                    k < introS + mainS -> { val c = (min(end, introS + mainS) - k).toInt(); mainMix(k - introS, c, (k - k0).toInt(), L, R); k += c }
                    else -> { val c = (end - k).toInt(); clipMix(outroPcm, outroS, k - introS - mainS, c, (k - k0).toInt(), L, R, false); k += c }
                }
            }
        }, { p -> progress(if (job.preview) tx("تجهيز صوت المعاينة", "Preparing preview audio") else tx("خلط الصوت", "Mixing audio"), p) }, cancelled, hold = job.hold)
        for (m in mics) m.src.close()
        resultUri = res.first
        return res.second
    }

    /** صوت المقاطع ورا بعض (كل واحد بطول صورته بالزبط، عشان الصوت ما يسبق الصورة) */
    private fun joinClipAudio(list: List<Clip>, rate: Int, total: Long): FloatArray {
        if (total <= 0) return FloatArray(0)
        val out = FloatArray(total.toInt())
        var at = 0
        for (c in list) {
            val n = Math.round(c.dur * rate).toInt()
            val pcm = try { decodeClipAudio(c.file, rate) } catch (_: Throwable) { FloatArray(0) }
            System.arraycopy(pcm, 0, out, at, min(min(n, pcm.size), out.size - at).coerceAtLeast(0))
            at += n
            if (at >= out.size) break
        }
        return out
    }

    /** المقطع بتعديله: تدوير/قلب (بمصفوفة الإحداثيات)، والمنطقة الظاهرة، وخلفية مموّهة إذا ما عبّى الشاشة */
    private fun drawClip(v: VDec, fx: ClipFx, alpha: Float) {
        if (fx.isPlain()) { drawFit(v, v.mat, v.w.toFloat() / v.h, 0f, 0f, outW.toFloat(), outH.toFloat(), 0f, alpha, 1f, "blur"); return }
        val m = clipMat(v.mat, fx)
        val da = outW.toFloat() / outH
        val r = fx.region(v.w.toFloat() / v.h, da)
        val ra = r[4]
        // خلفية: نفس المقطع مموّه ومعبّي الشاشة
        if (!fx.fill || kotlin.math.abs(ra - da) > 0.01f) {
            val full = ClipFx(fx.rot, fx.mirror, true, 1f).region(v.w.toFloat() / v.h, da)
            quad(pBlur, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.tex, m, 0f, 0f, outW.toFloat(), outH.toFloat(), full[0], 1f - full[3], full[2], 1f - full[1], 0f, alpha, packBlur(0.018f))
        }
        val fw: Float; val fh: Float
        if (ra > da) { fw = outW.toFloat(); fh = fw / ra } else { fh = outH.toFloat(); fw = fh * ra }
        // v بالتكستشر من تحت لفوق → بنقلب y المنطقة
        quad(pOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.tex, m, (outW - fw) / 2f, (outH - fh) / 2f, fw, fh, r[0], 1f - r[3], r[2], 1f - r[1], 0f, alpha)
    }

    /** مصفوفة: إحداثيات الصورة بعد التدوير/القلب → إحداثيات الفيديو الأصلي (حوالين النص) */
    private fun clipMat(base: FloatArray, fx: ClipFx): FloatArray {
        val r = FloatArray(16); android.opengl.Matrix.setIdentityM(r, 0)
        android.opengl.Matrix.translateM(r, 0, 0.5f, 0.5f, 0f)
        android.opengl.Matrix.rotateM(r, 0, ((fx.rot % 360) + 360) % 360 * 1f, 0f, 0f, 1f)
        if (fx.mirror) android.opengl.Matrix.scaleM(r, 0, -1f, 1f, 1f)
        android.opengl.Matrix.translateM(r, 0, -0.5f, -0.5f, 0f)
        val out = FloatArray(16); android.opengl.Matrix.multiplyMM(out, 0, base, 0, r, 0)
        return out
    }

    /** صوت ملف فيديو (إنترو/أوترو) كعيّنات أحادية بمعدل rate (حد أقصى دقيقة) */
    private fun decodeClipAudio(f: File, rate: Int): FloatArray {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(f.absolutePath)
            var idx = -1
            for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; break }
            if (idx < 0) return FloatArray(0)
            ex.selectTrack(idx)
            val fmt = ex.getTrackFormat(idx)
            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val d = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(fmt, null, null, 0); d.start()
            var mono = FloatArray(1 shl 16)
            var mn = 0
            var idle = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            val maxN = srcRate * 61
            while (true) {
                if (!inDone) {
                    val ii = d.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(d.getInputBuffer(ii)!!, 0)
                        if (n < 0) { d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { d.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = d.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val of = d.outputFormat; srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT); continue }
                if (oi >= 0) {
                    idle = 0
                    val ob = d.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = sb.remaining() / max(1, ch)
                        // كل قطعة صوت بمكانها حسب وقتها بالملف (نفس ساعة الفيديو) — مش ورا بعض من الصفر،
                        // هيك ما بيصير تأخير/تقديم لو الصوت بالملف بلّش بعد الصورة أو قبلها، أو في فجوات
                        val at = Math.round(info.presentationTimeUs * srcRate / 1e6).toInt()
                        var skip = 0
                        if (at >= 0 && kotlin.math.abs(at - mn) > srcRate / 50) { if (at > mn) { if (at > mono.size) mono = mono.copyOf(max(mono.size * 2, at + frames)); java.util.Arrays.fill(mono, mn, at, 0f); mn = at } else mn = at }
                        else if (at < 0) skip = min(frames, -at) // صوت قبل الصفر (تحضير الترميز): بنرميه
                        if (mn + frames > mono.size) mono = mono.copyOf(max(mono.size * 2, mn + frames))
                        for (i in skip until frames) { var acc = 0f; for (c in 0 until ch) acc += sb.get(i * ch + c) / 32768f; mono[mn++] = acc / ch }
                    }
                    d.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || mn > maxN) break
                } else if (inDone && ++idle > 100) break
            }
            // تحويل المعدل
            val outN = (mn.toLong() * rate / max(1, srcRate)).toInt()
            val k = srcRate.toDouble() / rate
            return FloatArray(outN) { i -> val p = i * k; val i0 = p.toInt(); val fr = (p - i0).toFloat(); val a = if (i0 < mn) mono[i0] else 0f; val b = if (i0 + 1 < mn) mono[i0 + 1] else a; a + (b - a) * fr }
        } catch (_: Throwable) {
            warnings += tx("ما قدرنا نقرا صوت ${f.name}", "Couldn't read the audio of ${f.name}")
            return FloatArray(0)
        } finally {
            try { dec?.stop() } catch (_: Throwable) {}
            try { dec?.release() } catch (_: Throwable) {}
            ex.release()
        }
    }

    private fun outName(): String = StorageUtil.newName().replace("Reaction_", "Podcast_")
        .replace(".mp4", label + (if (vertical) "_9x16.mp4" else "_16x9.mp4"))
}

/** دمج فيديو جاهز (بدون إعادة ترميز) مع صوت يُولَّد كتلة كتلة، وحفظ الناتج في المعرض */
object AudioMux {
    fun writeWithAudio(
        ctx: Context,
        videoFile: File,
        totalFrames: Long,
        rate: Int,
        name: String,
        toFile: File?,
        fill: (Long, Int, FloatArray, FloatArray) -> Unit,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
        hold: Boolean = false,
    ): Pair<Uri?, String?> {
        var ex: MediaExtractor? = null
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var outUri: Uri? = null
        var started = false
        var ok = false
        try {
            val extractor = MediaExtractor()
            ex = extractor
            extractor.setDataSource(videoFile.absolutePath)
            var vIdx = -1
            for (i in 0 until extractor.trackCount) if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { vIdx = i; break }
            if (vIdx < 0) return Pair(null, "No video track")
            val vFmt = extractor.getTrackFormat(vIdx)
            extractor.selectTrack(vIdx)
            val m: MediaMuxer
            if (toFile != null) {
                toFile.parentFile?.mkdirs()
                toFile.delete()
                m = MediaMuxer(toFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                val ou = StorageUtil.createPending(ctx, name) ?: return Pair(null, "Cannot create the output file")
                outUri = ou
                val pf = ctx.contentResolver.openFileDescriptor(ou, "rw") ?: return Pair(null, "Cannot open the output file")
                pfd = pf
                m = MediaMuxer(pf.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
            mux = m
            val vTrack = m.addTrack(vFmt)
            var aTrack = -1
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2)
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            af.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc = codec
            codec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val vBuf = ByteBuffer.allocateDirect(4 * 1024 * 1024)
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
                    m.writeSampleData(vTrack, vBuf, vInfo)
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
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { aTrack = m.addTrack(codec.outputFormat); m.start(); started = true }
                        i >= 0 -> {
                            val ob = codec.getOutputBuffer(i)
                            if (ob != null && oInfo.size > 0 && (oInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && started) {
                                pumpVideo(oInfo.presentationTimeUs)
                                ob.position(oInfo.offset); ob.limit(oInfo.offset + oInfo.size)
                                m.writeSampleData(aTrack, ob, oInfo)
                            }
                            codec.releaseOutputBuffer(i, false)
                            if ((oInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { gotEos = true; return }
                        }
                    }
                }
            }
            var fed = 0L
            fun feed(pcm: ShortArray, frames: Int) {
                var off = 0
                while (off < frames) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx < 0) { drain(); continue }
                    val ib = codec.getInputBuffer(idx)!!
                    ib.clear(); ib.order(ByteOrder.LITTLE_ENDIAN)
                    val c = min(frames - off, ib.capacity() / 4)
                    ib.asShortBuffer().put(pcm, off * 2, c * 2)
                    codec.queueInputBuffer(idx, 0, c * 4, fed * 1_000_000L / rate, 0)
                    fed += c; off += c
                    drain()
                }
            }
            val block = 4096
            val L = FloatArray(block)
            val R = FloatArray(block)
            val pcm = ShortArray(block * 2)
            var k0 = 0L
            while (k0 < totalFrames) {
                if (cancelled()) return Pair(null, "Cancelled")
                val n = min(block.toLong(), totalFrames - k0).toInt()
                fill(k0, n, L, R)
                for (i in 0 until n) { pcm[i * 2] = s16(L[i]); pcm[i * 2 + 1] = s16(R[i]) }
                feed(pcm, n)
                k0 += n
                if ((k0 / block) % 50 == 0L) progress(k0.toFloat() / totalFrames)
            }
            var sent = false
            while (!sent) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx < 0) { drain(); continue }
                codec.queueInputBuffer(idx, 0, 0, fed * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                sent = true
            }
            var guard = 0
            while (!gotEos && guard < 2000) { drain(); guard++; if (!gotEos) try { Thread.sleep(5) } catch (_: Throwable) {} }
            if (!started) return Pair(null, "Audio encoder produced nothing")
            pumpVideo(Long.MAX_VALUE)
            m.stop()
            started = false
            ok = true
            progress(1f)
        } catch (t: Throwable) {
            return Pair(null, "Saving failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            try { enc?.stop() } catch (_: Throwable) {}
            try { enc?.release() } catch (_: Throwable) {}
            try { ex?.release() } catch (_: Throwable) {}
            if (started) try { mux?.stop() } catch (_: Throwable) {}
            try { mux?.release() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
            if (!ok) { outUri?.let { StorageUtil.delete(ctx, it) }; toFile?.delete() }
        }
        // «شوفه قبل ما تحفظه»: الملف بيضل مخفي (مش بالمعرض) لحد ما توافق — وبعدها نفس الملف بالزبط بيصير ظاهر
        if (!hold) outUri?.let { StorageUtil.publish(ctx, it) }
        return Pair(outUri, null)
    }

    private fun s16(x: Float): Short {
        var v = x
        val a = abs(v)
        if (a > 0.9f) {
            val e = 0.9f + 0.1f * kotlin.math.tanh((a - 0.9f) / 0.1f)
            v = if (v < 0f) -e else e
        }
        return (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }
}

/** مركز الكادر جوّا الصورة (حتى لو الكادر أكبر من الصورة بفرق تقريب، ما بيوقّع) */
private fun Float.mid(k: Float): Float = if (k >= 1f) 0.5f else coerceIn(k / 2f, 1f - k / 2f)
