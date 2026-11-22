package com.reactionrecorder

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
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
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * المركِّب (Compositor): يرسم فيديو المصدر (شاشة الهاتف عبر MediaProjection أو مشغّل الفيديو الداخلي)،
 * وإطارات الكاميرا (وحتى كاميرتين معًا)، وصورة عرض اختيارية، وشاشة الانتظار، على سطح الـ MediaCodec
 * مباشرةً عبر OpenGL ES 2.0.
 *
 *   Camera 1/2 → SurfaceTexture(OES) ┐
 *   Source     → SurfaceTexture(OES) ├→ GL shader (crop/zoom/rotate/shape/border) → EGL window = encoder Surface
 *   Image/Standby → GL_TEXTURE_2D    ┘
 *
 * لا يوجد أي نسخ للـ Bitmap أو ByteBuffer على الـ CPU للفيديو: الإطارات تبقى على الـ GPU من الالتقاط إلى الترميز.
 * الرسم يتم على إيقاع ثابت (fps) حتى لو كانت الشاشة ثابتة، لضمان فيديو بمعدل إطارات ثابت.
 */
private const val NCAM = 4

class Compositor(
    private val outW: Int,
    private val outH: Int,
    private val fps: Int,
    private val encoderSurface: Surface,
) {
    @Volatile var layout: LayoutConfig = Presets.build(1, true, 0.7f)
    @Volatile var sourceFit: SourceFit = SourceFit.FIT
    /** عند "ملاءمة" (بدون قص): الفراغ حول الصورة يُملأ بنفس الصورة مموّهة بدل الأسود */
    @Volatile var blurFill: Boolean = true
    /** دوران الشاشة الحالي بوحدات الربع (0..3) كما في Surface.ROTATION_* */
    @Volatile var displayQuarter: Int = 0
    /** تصحيح يدوي إضافي يختاره المستخدم (0/90/180/270) إذا ظهرت الكاميرا مائلة على جهاز معيّن */
    @Volatile var cameraAdjust: Int = 0

    /** إظهار/إخفاء الكاميرا (لتغطية شيء مثلًا). الإخفاء يؤثر على الفيديو المسجَّل أيضًا */
    @Volatile var cameraVisible: Boolean = true
    /** هل الكاميرا الثانية فعّالة (وضع الكاميرتين) */
    @Volatile var camera2Active: Boolean = false
    /** الكاميرا المعروضة مكان الفيديو (فهرس 0/1) أو -1 = يُعرض الفيديو/الشاشة */
    @Volatile var camMain: Int = -1
    /** رسم الكاميرا 1 كصورة صغيرة ثانية في مستطيل camera2 (لإعادة العرض مع أجهزة مساعدة) */
    @Volatile var camera2Pip: Boolean = false
    /** هل الكاميرا 0 هي الأمامية (السيلفي)؟ إزالة الخلفية تُطبَّق على السيلفي فقط */
    @Volatile var cam0IsFront: Boolean = true

    // إزالة خلفية السيلفي: 0 إيقاف، 1 كروما، 2 تلقائي (تقطيع الشخص)
    @Volatile private var keyMode = 0
    @Volatile private var keyR = 0f
    @Volatile private var keyG = 0.69f
    @Volatile private var keyB = 0.25f
    @Volatile private var keyTol = 0.14f
    @Volatile private var keySoft = 0.08f
    /** نص حالة التقطيع التلقائي (للعرض عند الفشل) */
    @Volatile var segStatus: String = ""
        private set
    private var curKeyMode = 0
    private var seg: PersonSegmenter? = null
    private var segFailed = false

    // تحويل محتوى المصدر (فيديو أو شاشة أو صورة): تكبير، إزاحة -1..1، تدوير بزوايا قائمة
    @Volatile var srcZoom: Float = 1f
    @Volatile var srcPanX: Float = 0f
    @Volatile var srcPanY: Float = 0f
    @Volatile var srcRot: Int = 0

    @Volatile private var srcW = 1080
    @Volatile private var srcH = 1920
    val sourceW: Int get() = srcW
    val sourceH: Int get() = srcH
    /** أبعاد المحتوى المعروض حاليًا في منطقة المصدر (صورة العرض إن وُجدت، وإلا الفيديو/الشاشة) */
    val contentW: Int get() = if (imgOn) imgW else srcW
    val contentH: Int get() = if (imgOn) imgH else srcH

    // حالة كل كاميرا (0 = الأساسية، 1 = الثانية)
    private val camW = IntArray(NCAM) { 1280 }
    private val camH = IntArray(NCAM) { 720 }
    private val camInfoRot = IntArray(NCAM)
    private val camAdj = IntArray(NCAM)
    private val camMirror = BooleanArray(NCAM)
    private val camSwaps = BooleanArray(NCAM)
    private val camHas = BooleanArray(NCAM)
    private val camDirty = Array(NCAM) { AtomicBoolean(false) }
    private val camMat = Array(NCAM) { FloatArray(16) }
    private val camSt = arrayOfNulls<SurfaceTexture>(NCAM)
    private val camSurfaces = arrayOfNulls<Surface>(NCAM)
    /** إعادة العرض مع أجهزة مساعدة: مستطيل كل كاميرا (الفهرس = رقم الكاميرا)؛ null = السلوك العادي */
    @Volatile var pipRects: List<NRect>? = null

    // صورة العرض (في منطقة المصدر) وشاشة الانتظار
    @Volatile private var imgOn = false
    @Volatile private var imgW = 1
    @Volatile private var imgH = 1
    @Volatile private var sbMode = 0 // 0 لا شيء، 1 صورة، 2 فيديو (يُرسَم من نسيج المصدر)
    @Volatile private var sbW = 1
    @Volatile private var sbH = 1

    private val thread = HandlerThread("compositor").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglCfg: EGLConfig? = null
    // سطح "المراقبة" الاختياري (معاينة حيّة على شاشة التطبيق)
    private var pvSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var pvCurrent: Surface? = null

    private class Prog {
        var id = 0
        var aPos = 0; var aTex = 0; var aLocal = 0
        var uTexMatrix = 0; var uTex = 0; var uHalf = 0
        var uRadius = 0; var uBorder = 0; var uBorderColor = 0
        var uKeyMode = 0; var uKeyColor = 0; var uKeyTol = 0; var uKeySoft = 0; var uMask = 0
        var uUseAlpha = 0
    }

    private val pOes = Prog()
    private val p2d = Prog()

    private val texOes = IntArray(NCAM + 1) // 0..NCAM-1 = كاميرات، NCAM = المصدر
    private val tex2d = IntArray(3)  // 0 = صورة العرض، 1 = صورة الانتظار، 2 = الرسم فوق الفيديو
    /** رسم فوق الفيديو (بشفافية) */
    @Volatile private var ovOn = false
    private var ovW = 0
    private var ovH = 0
    private var texAlpha = false
    private var maskTex = 0
    private var maskBuf: ByteBuffer? = null
    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0
    private var capBuf: ByteBuffer? = null
    private var capBmp: Bitmap? = null
    private lateinit var srcSt: SurfaceTexture
    lateinit var sourceSurface: Surface
        private set
    val cameraSurface: Surface get() = camSurfaces[0]!!
    val camera2Surface: Surface get() = camSurfaces[1]!!

    private val srcDirty = AtomicBoolean(false)
    private var hasSrc = false
    private val srcMat = FloatArray(16)
    private val flipMat = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

    private val posBuf: FloatBuffer = floatBuf(8)
    private val texBuf: FloatBuffer = floatBuf(8)
    private val locBuf: FloatBuffer = floatBuf(8).apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    @Volatile private var running = false
    @Volatile private var paused = false
    private var startNs = 0L
    private var pausedAtNs = 0L
    private var pausedTotalNs = 0L
    private var nextNs = 0L
    @Volatile var framesDrawn = 0L
    /** متى وصل أول إطار من الكاميرا (elapsedRealtimeNanos) — قبله الفيديو أسود، والمونتاج بيقصّه */
    @Volatile var firstCamNs = 0L
        private set

    /** تهيئة EGL وإنشاء أسطح الكاميرات والمصدر. تُستدعى مرة واحدة وتنتظر حتى الانتهاء. */
    fun setup(sourceW: Int, sourceH: Int) {
        srcW = sourceW; srcH = sourceH
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                initEgl()
                initGl()
                for (i in 0 until NCAM) {
                    val st = SurfaceTexture(texOes[i])
                    st.setDefaultBufferSize(camW[i], camH[i])
                    val d = camDirty[i]
                    st.setOnFrameAvailableListener({ d.set(true) }, handler)
                    camSt[i] = st
                    camSurfaces[i] = Surface(st)
                }
                srcSt = SurfaceTexture(texOes[NCAM]).also {
                    it.setDefaultBufferSize(srcW, srcH)
                    it.setOnFrameAvailableListener({ srcDirty.set(true) }, handler)
                }
                sourceSurface = Surface(srcSt)
                PreviewBridge.compositor = this
            } catch (t: Throwable) {
                error = t
            }
            latch.countDown()
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("Compositor init timeout")
        error?.let { throw it }
    }

    fun cameraSurfaceFor(i: Int): Surface = camSurfaces[i.coerceIn(0, NCAM - 1)]!!

    /** تُستدعى عندما تطلب CameraX سطحًا بحجم معيّن */
    fun setCameraBufferSize(i: Int, w: Int, h: Int) {
        val k = i.coerceIn(0, NCAM - 1)
        camW[k] = w; camH[k] = h
        val latch = CountDownLatch(1)
        handler.post { camSt[k]?.setDefaultBufferSize(w, h); latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
    }

    /** الدوران الذي تطلبه CameraX (باتجاه عقارب الساعة) من إطار الحساس إلى اتجاه الشاشة */
    fun setCameraInfoRotation(i: Int, deg: Int) { camInfoRot[i.coerceIn(0, NCAM - 1)] = deg }

    /** تدوير إضافي (0/90/180/270) للكاميرا i، يُضاف إلى التصحيح العام */
    fun setCameraRotation(i: Int, deg: Int) { camAdj[i.coerceIn(0, NCAM - 1)] = ((deg % 360) + 360) % 360 }

    fun setCameraMirror(i: Int, m: Boolean) { camMirror[i.coerceIn(0, NCAM - 1)] = m }

    /** هل وصل إطار واحد على الأقل من هذه الكاميرا منذ آخر إعادة ضبط؟ */
    fun hasFrame(i: Int): Boolean = camHas[i.coerceIn(0, NCAM - 1)]

    /** عند إعادة ربط الكاميرات: ننسى الإطارات القديمة كي لا تظهر صورة كاميرا سابقة */
    fun resetCameras() { handler.post { for (i in 0 until NCAM) camHas[i] = false } }

    /** إعدادات إزالة الخلفية: mode 0 إيقاف، 1 كروما (color = ARGB)، 2 تلقائي */
    fun setKey(mode: Int, color: Int, tol: Float, soft: Float) {
        keyR = ((color shr 16) and 0xFF) / 255f
        keyG = ((color shr 8) and 0xFF) / 255f
        keyB = (color and 0xFF) / 255f
        keyTol = tol.coerceIn(0f, 0.6f)
        keySoft = soft.coerceIn(0.005f, 0.5f)
        keyMode = mode
    }

    /** يُستدعى عند تدوير الشاشة/تغيير حجم الالتقاط أو عند تغيّر أبعاد الفيديو */
    fun setSourceSize(w: Int, h: Int) {
        srcW = w; srcH = h
        handler.post { srcSt.setDefaultBufferSize(w, h) }
    }

    /** صورة تُعرض مكان المصدر (null = العودة لعرض الفيديو/الشاشة). يتولّى الـ Compositor تحرير الـ Bitmap */
    fun setImage(bmp: Bitmap?) {
        handler.post {
            if (bmp == null) { imgOn = false; return@post }
            try {
                uploadTexture(0, bmp)
                imgW = bmp.width; imgH = bmp.height
                imgOn = true
            } catch (_: Throwable) {
                imgOn = false
            }
            try { bmp.recycle() } catch (_: Throwable) {}
        }
    }

    /** شاشة الانتظار: 0 إيقاف، 1 صورة (bmp)، 2 فيديو (يُرسَم نسيج المصدر بملء الإطار) */
    fun setStandby(mode: Int, bmp: Bitmap?) {
        handler.post {
            if (mode == 1 && bmp != null) {
                try {
                    uploadTexture(1, bmp)
                    sbW = bmp.width; sbH = bmp.height
                    sbMode = 1
                } catch (_: Throwable) {
                    sbMode = 0
                }
            } else {
                sbMode = if (mode == 2) 2 else 0
            }
            try { bmp?.recycle() } catch (_: Throwable) {}
        }
    }

    private fun uploadTexture(i: Int, bmp: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2d[i])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    fun begin(startNanos: Long) {
        startNs = startNanos
        running = true
        nextNs = System.nanoTime()
        handler.post(tick)
    }

    /** يوقف الرسم فقط (قبل إرسال EOS للمُرمِّز). تحرير EGL يتم لاحقًا في release() */
    fun stopDrawing() {
        running = false
        val latch = CountDownLatch(1)
        handler.post { handler.removeCallbacks(tick); latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun pause() { if (!paused) { pausedAtNs = System.nanoTime(); paused = true } }
    fun resume() { if (paused) { pausedTotalNs += System.nanoTime() - pausedAtNs; paused = false } }

    /** إجمالي مدة الإيقاف المؤقت بالنانو ثانية (يستعملها الصوت لتبقى المسارات متزامنة) */
    fun pausedNanos(): Long = pausedTotalNs + if (paused) System.nanoTime() - pausedAtNs else 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                renderFrame()
            } catch (t: Throwable) {
                // لا نوقف التسجيل بسبب إطار واحد
            }
            nextNs += 1_000_000_000L / fps
            val now = System.nanoTime()
            var delayMs = (nextNs - now) / 1_000_000L
            if (delayMs < 0) { nextNs = now; delayMs = 0 }
            handler.postAtTime(this, SystemClock.uptimeMillis() + delayMs)
        }
    }

    private fun renderFrame() {
        for (i in 0 until NCAM) {
            if (camDirty[i].getAndSet(false)) {
                val st = camSt[i] ?: continue
                st.updateTexImage(); st.getTransformMatrix(camMat[i]); camHas[i] = true
                if (i == 0 && firstCamNs == 0L) firstCamNs = SystemClock.elapsedRealtimeNanos()
                val m = camMat[i]
                camSwaps[i] = abs(m[1]) + abs(m[4]) > abs(m[0]) + abs(m[5])
            }
        }
        if (srcDirty.getAndSet(false)) { srcSt.updateTexImage(); srcSt.getTransformMatrix(srcMat); hasSrc = true }
        if (paused) return

        if (keyMode == 2) { try { updateMask() } catch (_: Throwable) { segFailed = true; segStatus = "Auto cutout failed on this device" } }
        GLES20.glViewport(0, 0, outW, outH)
        drawScene()

        val ptsNs = System.nanoTime() - startNs - pausedTotalNs
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        framesDrawn++
        drawMonitor()
    }

    private fun drawScene() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (sbMode != 0) { drawStandby(); return }
        val l = layout
        val mc = camMain
        if (mc >= 0) { drawMainCamera(mc, l); drawCameras(l); return }
        // مع إزالة الخلفية يجب أن يكون السيلفي فوق الفيديو دائمًا
        if (l.cameraOnTop || keyMode != 0) { drawSourceLayer(l); drawCameras(l) } else { drawCameras(l); drawSourceLayer(l) }
        if (ovOn) {
            val r = l.source
            texAlpha = true
            drawContent(p2d, GLES20.GL_TEXTURE_2D, tex2d[2], flipMat, r.x * outW, r.y * outH, r.w * outW, r.h * outH,
                ovW.toFloat(), ovH.toFloat(), SourceFit.STRETCH, 1f, 0f, 0f, 0)
            texAlpha = false
        }
    }

    /** رسم المستخدم فوق الفيديو (null = إخفاء). الـ Compositor بيحرر الـ Bitmap */
    fun setOverlay(bmp: Bitmap?) {
        handler.post {
            if (bmp == null) { ovOn = false; return@post }
            try {
                uploadTexture(2, bmp)
                ovW = bmp.width; ovH = bmp.height
                ovOn = true
            } catch (_: Throwable) { ovOn = false }
            try { bmp.recycle() } catch (_: Throwable) {}
        }
    }

    // ───────── شاشة المراقبة (معاينة حيّة) ─────────

    /** يطابق سطح المعاينة المطلوب مع سطح EGL الحالي (يُستدعى على خيط الـ Compositor) */
    private fun syncPreview() {
        val want = PreviewBridge.surface?.takeIf { it.isValid }
        if (want === pvCurrent) return
        destroyPreview()
        if (want != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                val s = EGL14.eglCreateWindowSurface(eglDisplay, eglCfg, want, intArrayOf(EGL14.EGL_NONE), 0)
                if (s != EGL14.EGL_NO_SURFACE) { pvSurface = s; pvCurrent = want }
            } catch (_: Throwable) {
            }
        }
    }

    private fun destroyPreview() {
        if (pvSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            EGL14.eglDestroySurface(eglDisplay, pvSurface)
        }
        pvSurface = EGL14.EGL_NO_SURFACE
        pvCurrent = null
    }

    /** من الخيط الرئيسي عند تدمير السطح: ننتظر حتى يتخلّى الـ Compositor عنه */
    fun syncPreviewBlocking() {
        val latch = CountDownLatch(1)
        if (!handler.post { try { syncPreview() } finally { latch.countDown() } }) return
        latch.await(500, TimeUnit.MILLISECONDS)
    }

    private fun drawMonitor() {
        syncPreview()
        if (pvSurface == EGL14.EGL_NO_SURFACE) return
        if (fps >= 30 && (framesDrawn and 1L) == 1L) return // نصف المعدّل يكفي للمراقبة ويخفّف الحِمل
        val wh = IntArray(2)
        EGL14.eglQuerySurface(eglDisplay, pvSurface, EGL14.EGL_WIDTH, wh, 0)
        EGL14.eglQuerySurface(eglDisplay, pvSurface, EGL14.EGL_HEIGHT, wh, 1)
        if (wh[0] <= 0 || wh[1] <= 0) return
        if (!EGL14.eglMakeCurrent(eglDisplay, pvSurface, pvSurface, eglContext)) { destroyPreview(); return }
        EGL14.eglSwapInterval(eglDisplay, 0)
        GLES20.glViewport(0, 0, wh[0], wh[1])
        drawScene()
        EGL14.eglSwapBuffers(eglDisplay, pvSurface)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    // ───────── طبقات الرسم ─────────

    private fun drawStandby() {
        if (sbMode == 1) {
            drawContent(p2d, GLES20.GL_TEXTURE_2D, tex2d[1], flipMat, 0f, 0f, outW.toFloat(), outH.toFloat(),
                sbW.toFloat(), sbH.toFloat(), SourceFit.FIT, 1f, 0f, 0f, 0)
        } else if (sbMode == 2 && hasSrc) {
            drawContent(pOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes[NCAM], srcMat, 0f, 0f, outW.toFloat(), outH.toFloat(),
                srcW.toFloat(), srcH.toFloat(), SourceFit.FIT, 1f, 0f, 0f, 0)
        }
    }

    private fun drawSourceLayer(l: LayoutConfig) {
        val r = l.source
        val x = r.x * outW; val y = r.y * outH; val w = r.w * outW; val h = r.h * outH
        if (imgOn) {
            drawContent(p2d, GLES20.GL_TEXTURE_2D, tex2d[0], flipMat, x, y, w, h,
                imgW.toFloat(), imgH.toFloat(), sourceFit, srcZoom, srcPanX, srcPanY, srcRot)
        } else if (hasSrc) {
            drawContent(pOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes[NCAM], srcMat, x, y, w, h,
                srcW.toFloat(), srcH.toFloat(), sourceFit, srcZoom, srcPanX, srcPanY, srcRot)
        }
    }

    /**
     * يرسم محتوى (cw0×ch0) داخل المستطيل (rx,ry,rw,rh) بحسب الملاءمة (FIT/FILL/STRETCH)،
     * ثم التكبير والإزاحة والتدوير. الجزء الخارج عن المستطيل يُقصّ.
     */
    private fun drawContent(
        p: Prog, target: Int, tex: Int, texMat: FloatArray,
        rx: Float, ry: Float, rw: Float, rh: Float,
        cw0: Float, ch0: Float, fit: SourceFit, zoom: Float, panX: Float, panY: Float, rot: Int,
        mirror: Boolean = false,
    ) {
        if (rw < 1f || rh < 1f || cw0 < 1f || ch0 < 1f) return
        val rotated = rot % 180 != 0
        val cw = if (rotated) ch0 else cw0
        val ch = if (rotated) cw0 else ch0
        val sc = contentScale(fit, cw, ch, rw, rh, zoom)
        val cwPx = cw * sc[0]; val chPx = ch * sc[1]
        val slackX = abs(cwPx - rw) / 2f; val slackY = abs(chPx - rh) / 2f
        val cx = rx + rw / 2f + panX.coerceIn(-1f, 1f) * slackX
        val cy = ry + rh / 2f + panY.coerceIn(-1f, 1f) * slackY
        val cl = cx - cwPx / 2f; val ct = cy - chPx / 2f
        val il = max(cl, rx); val ir = min(cl + cwPx, rx + rw)
        val it = max(ct, ry); val ib = min(ct + chPx, ry + rh)
        if (ir - il < 1f || ib - it < 1f) return
        if (blurFill && fit == SourceFit.FIT && (ir - il < rw - 2f || ib - it < rh - 2f)) {
            try { drawBlurBack(p, target, tex, texMat, rx, ry, rw, rh, cw, ch, ((rot % 360) + 360) % 360, mirror) } catch (_: Throwable) {}
        }
        val u0 = (il - cl) / cwPx; val u1 = (ir - cl) / cwPx
        val vTop = 1f - (it - ct) / chPx; val vBot = 1f - (ib - ct) / chPx
        val tc = windowCoords(u0, vBot, u1, vTop, ((rot % 360) + 360) % 360, mirror)
        draw(p, target, tex, texMat, il, it, ir - il, ib - it, tc, 0f, 0f)
    }

    private var blurFbo = 0
    private var blurTex = 0
    private var blurW = 0
    private var blurH = 0
    private val identMat = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private val vp = IntArray(4)

    /**
     * خلفية مموّهة: نرسم المحتوى (ملء مع قص) في صورة صغيرة جدًا ثم نكبّرها على المستطيل ونعتّمها.
     * التكبير من دقة صغيرة يعطي تمويهًا ناعمًا بكلفة شبه معدومة على الـ GPU.
     */
    private fun drawBlurBack(p: Prog, target: Int, tex: Int, texMat: FloatArray, rx: Float, ry: Float, rw: Float, rh: Float, cw: Float, ch: Float, rot: Int, mirror: Boolean) {
        val bw = 24
        val bh = (bw * rh / rw).toInt().coerceIn(6, 64)
        if (blurFbo == 0 || blurW != bw || blurH != bh) {
            val ids = IntArray(1)
            if (blurFbo != 0) { ids[0] = blurFbo; GLES20.glDeleteFramebuffers(1, ids, 0); ids[0] = blurTex; GLES20.glDeleteTextures(1, ids, 0) }
            GLES20.glGenTextures(1, ids, 0); blurTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bw, bh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glGenFramebuffers(1, ids, 0); blurFbo = ids[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, blurTex, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            blurW = bw; blurH = bh
        }
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo)
        GLES20.glViewport(0, 0, bw, bh)
        GLES20.glDisable(GLES20.GL_BLEND)
        val km = curKeyMode
        curKeyMode = 0
        draw(p, target, tex, texMat, 0f, 0f, outW.toFloat(), outH.toFloat(), coverCoords(cw, ch, rw, rh, rot, mirror), 0f, 0f)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendColor(0f, 0f, 0f, 0.5f)
        GLES20.glBlendFunc(GLES20.GL_CONSTANT_ALPHA, GLES20.GL_ONE_MINUS_CONSTANT_ALPHA)
        draw(p2d, GLES20.GL_TEXTURE_2D, blurTex, identMat, rx, ry, rw, rh, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), 0f, 0f)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        curKeyMode = km
    }

    /** مربع السيلفي (الكاميرا 0). الكاميرا الثانية لا تُرسَم كمربع صغير؛ تُعرض فقط مكان الفيديو عبر camMain */
    private fun drawCameras(l: LayoutConfig) {
        if (!cameraVisible) return
        val pr = pipRects
        if (pr != null) {
            for (i in pr.indices) if (i < NCAM && camHas[i]) drawCamera(i, l, pr[i])
            return
        }
        if (camMain != 0 && camHas[0]) drawCamera(0, l, l.camera)
        if (camera2Pip && camMain != 1 && camHas[1]) drawCamera(1, l, l.camera2)
    }

    /** دوران الكاميرا i الكلي (بما فيه التصحيح اليدوي). مصفوفة SurfaceTexture على أغلب الأجهزة تُدوّر صورة الحساس
     *  لتصبح مستقيمة بالنسبة لاتجاه الجهاز الطبيعي، فنضيف فقط دوران الشاشة؛ وإلا نستعمل دوران CameraX الكامل. */
    private fun camRot(i: Int): Int {
        val base = if (camSwaps[i]) (360 - displayQuarter * 90) % 360 else camInfoRot[i]
        return ((base + cameraAdjust + camAdj[i]) % 360 + 360) % 360
    }

    private fun camNatW(i: Int): Float = if (camSwaps[i]) camH[i].toFloat() else camW[i].toFloat()
    private fun camNatH(i: Int): Float = if (camSwaps[i]) camW[i].toFloat() else camH[i].toFloat()

    private fun drawCamera(i: Int, l: LayoutConfig, rect: NRect) {
        val r = effectiveRect(rect, l.shape, outW, outH)
        val x = r.x * outW; val y = r.y * outH; val w = r.w * outW; val h = r.h * outH

        val rot = camRot(i)
        val natW = camNatW(i)
        val natH = camNatH(i)
        val dispW = if (rot % 180 == 0) natW else natH
        val dispH = if (rot % 180 == 0) natH else natW

        val tc = coverCoords(dispW, dispH, w, h, rot, camMirror[i])
        val radius = when (l.shape) {
            CamShape.RECT -> 0f
            CamShape.ROUNDED -> l.radiusFrac.coerceIn(0f, 0.5f) * min(w, h)
            CamShape.CIRCLE -> min(w, h) / 2f
        }
        val km = if (i == 0 && cam0IsFront && keyMode != 0 && !(keyMode == 2 && (segFailed || seg == null))) keyMode else 0
        val border = if (km != 0) 0f else l.borderFrac * outW
        curKeyMode = km
        draw(pOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes[i], camMat[i], x, y, w, h, tc, if (km != 0) 0f else radius, border, l.borderColor)
        curKeyMode = 0
    }

    /** الكاميرا كمصدر رئيسي: تملأ منطقة المصدر (بحسب Fit والتكبير) بدل الفيديو */
    private fun drawMainCamera(i: Int, l: LayoutConfig) {
        if (!camHas[i]) return
        val r = l.source
        val rot = (camRot(i) + srcRot) % 360
        drawContent(
            pOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes[i], camMat[i],
            r.x * outW, r.y * outH, r.w * outW, r.h * outH,
            camNatW(i), camNatH(i), sourceFit, srcZoom, srcPanX, srcPanY, rot, camMirror[i],
        )
    }

    // ───────── التقطيع التلقائي (عزل الشخص) ─────────

    /** يرسم السيلفي بحجم صغير في FBO ويرسله لـ ML Kit، ويرفع آخر قناع جاهز كنسيج */
    private fun updateMask() {
        if (segFailed) return
        val sg = seg ?: try { PersonSegmenter().also { seg = it } } catch (t: Throwable) {
            segFailed = true; segStatus = "Auto cutout unavailable (${t.javaClass.simpleName})"; return
        }
        sg.takeMask()?.let { m ->
            val n = m.w * m.h
            var b = maskBuf
            if (b == null || b.capacity() < n) { b = ByteBuffer.allocateDirect(n); maskBuf = b }
            b!!.clear(); b.put(m.data, 0, n); b.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, m.w, m.h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, b)
            sg.maskReady = true
        }
        if (sg.error != null) { segFailed = true; segStatus = "Auto cutout failed: ${sg.error}"; return }
        // لا نلتقط إلا عندما يكون السيلفي ظاهرًا، والمعالجة السابقة انتهت، وكل إطارين
        if (sg.busy || !cameraVisible || !cam0IsFront || camMain == 0 || !camHas[0]) return
        if ((framesDrawn and 1L) != 0L) return
        val l = layout
        val r = effectiveRect(l.camera, l.shape, outW, outH)
        val rw = r.w * outW; val rh = r.h * outH
        if (rw < 2f || rh < 2f) return
        val fw = 192
        val fh = (fw * rh / rw).toInt().coerceIn(96, 384)
        ensureFbo(fw, fh)
        val rot = camRot(0)
        val natW = camNatW(0); val natH = camNatH(0)
        val dispW = if (rot % 180 == 0) natW else natH
        val dispH = if (rot % 180 == 0) natH else natW
        val tc = coverCoords(dispW, dispH, rw, rh, rot, camMirror[0])

        val bytes = fw * fh * 4
        var cb = capBuf
        if (cb == null || cb.capacity() < bytes) { cb = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()); capBuf = cb }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, fw, fh)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_BLEND)
        curKeyMode = 0
        draw(pOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes[0], camMat[0], -2f, -2f, outW.toFloat() + 4f, outH.toFloat() + 4f, tc, 0f, 0f)
        cb!!.clear()
        GLES20.glReadPixels(0, 0, fw, fh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, cb)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glEnable(GLES20.GL_BLEND)

        // glReadPixels يعطي الصفوف من الأسفل للأعلى: نقلبها لتصبح الصورة مستقيمة للنموذج
        var bmp = capBmp
        if (bmp == null || bmp.width != fw || bmp.height != fh) { bmp?.recycle(); bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888); capBmp = bmp }
        val src = ByteArray(bytes)
        cb.position(0); cb.get(src)
        val flipped = ByteArray(bytes)
        val row = fw * 4
        for (y in 0 until fh) System.arraycopy(src, (fh - 1 - y) * row, flipped, y * row, row)
        bmp!!.copyPixelsFromBuffer(ByteBuffer.wrap(flipped))
        sg.submit(bmp)
    }

    private fun ensureFbo(w: Int, h: Int) {
        if (fbo != 0 && fboW == w && fboH == h) return
        val ids = IntArray(1)
        if (fbo != 0) { ids[0] = fbo; GLES20.glDeleteFramebuffers(1, ids, 0); ids[0] = fboTex; GLES20.glDeleteTextures(1, ids, 0) }
        GLES20.glGenTextures(1, ids, 0); fboTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fboW = w; fboH = h
    }

    /**
     * إحداثيات الـ texture بترتيب [BL, BR, TL, TR] بحيث يملأ المحتوى (upW×upH) المستطيل (rw×rh)
     * بقصّ مركزي (cover)، ثم يُطبَّق الانعكاس الأفقي ثم الدوران ليطابق اتجاه الـ buffer.
     */
    private fun coverCoords(upW: Float, upH: Float, rw: Float, rh: Float, rotation: Int, mirror: Boolean): FloatArray {
        var u0 = 0f; var u1 = 1f; var v0 = 0f; var v1 = 1f
        val texAspect = upW / upH
        val rectAspect = rw / rh
        if (texAspect > rectAspect) {
            val c = rectAspect / texAspect
            u0 = 0.5f - c / 2f; u1 = 0.5f + c / 2f
        } else if (texAspect < rectAspect) {
            val c = texAspect / rectAspect
            v0 = 0.5f - c / 2f; v1 = 0.5f + c / 2f
        }
        return windowCoords(u0, v0, u1, v1, rotation, mirror)
    }

    /**
     * نافذة (u0..u1, v0..v1) في فضاء العرض (v للأعلى) → إحداثيات texture بترتيب [BL, BR, TL, TR]،
     * بعد تدوير النافذة حول المركز بزاوية rotation (باتجاه عقارب الساعة بالنسبة للصورة المعروضة) ثم الانعكاس الأفقي.
     */
    private fun windowCoords(u0: Float, v0: Float, u1: Float, v1: Float, rotation: Int, mirror: Boolean): FloatArray {
        var pts = floatArrayOf(u0, v0, u1, v0, u0, v1, u1, v1)
        if (rotation != 0) {
            val a = Math.toRadians(rotation.toDouble())
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val out = FloatArray(8)
            for (i in 0 until 4) {
                val cx = pts[i * 2] - 0.5f; val cy = pts[i * 2 + 1] - 0.5f
                out[i * 2] = cx * ca - cy * sa + 0.5f
                out[i * 2 + 1] = cx * sa + cy * ca + 0.5f
            }
            pts = out
        }
        if (mirror) { // انعكاس أفقي على الشاشة (بعد الدوران) كي يبقى صحيحًا في كل الاتجاهات
            pts = floatArrayOf(pts[2], pts[3], pts[0], pts[1], pts[6], pts[7], pts[4], pts[5])
        }
        return pts
    }

    private fun draw(
        p: Prog, target: Int, tex: Int, texMat: FloatArray, x: Float, y: Float, w: Float, h: Float, tc: FloatArray,
        radius: Float, border: Float, borderColor: Int = 0xFFFFFFFF.toInt(),
    ) {
        val x0 = x / outW * 2f - 1f; val x1 = (x + w) / outW * 2f - 1f
        val yTop = 1f - y / outH * 2f; val yBot = 1f - (y + h) / outH * 2f
        posBuf.position(0); posBuf.put(floatArrayOf(x0, yBot, x1, yBot, x0, yTop, x1, yTop)); posBuf.position(0)
        texBuf.position(0); texBuf.put(tc); texBuf.position(0)
        locBuf.position(0)

        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniform1i(p.uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(p.uMask, 1)
        GLES20.glUniform1f(p.uKeyMode, curKeyMode.toFloat())
        GLES20.glUniform3f(p.uKeyColor, keyR, keyG, keyB)
        GLES20.glUniform1f(p.uKeyTol, keyTol)
        GLES20.glUniform1f(p.uKeySoft, keySoft)
        GLES20.glUniform1f(p.uUseAlpha, if (texAlpha) 1f else 0f)
        GLES20.glUniformMatrix4fv(p.uTexMatrix, 1, false, texMat, 0)
        GLES20.glUniform2f(p.uHalf, w / 2f, h / 2f)
        GLES20.glUniform1f(p.uRadius, radius)
        GLES20.glUniform1f(p.uBorder, border)
        GLES20.glUniform4f(
            p.uBorderColor,
            ((borderColor shr 16) and 0xFF) / 255f, ((borderColor shr 8) and 0xFF) / 255f,
            (borderColor and 0xFF) / 255f, ((borderColor ushr 24) and 0xFF) / 255f,
        )
        GLES20.glEnableVertexAttribArray(p.aPos); GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 8, posBuf)
        GLES20.glEnableVertexAttribArray(p.aTex); GLES20.glVertexAttribPointer(p.aTex, 2, GLES20.GL_FLOAT, false, 8, texBuf)
        GLES20.glEnableVertexAttribArray(p.aLocal); GLES20.glVertexAttribPointer(p.aLocal, 2, GLES20.GL_FLOAT, false, 8, locBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p.aPos)
        GLES20.glDisableVertexAttribArray(p.aTex)
        GLES20.glDisableVertexAttribArray(p.aLocal)
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no EGL config" }
        eglCfg = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun initGl() {
        buildProg(pOes, true)
        buildProg(p2d, false)
        GLES20.glGenTextures(NCAM + 1, texOes, 0)
        for (id in texOes) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        val mt = IntArray(1)
        GLES20.glGenTextures(1, mt, 0)
        maskTex = mt[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(1).put(0, 255.toByte()))
        GLES20.glGenTextures(3, tex2d, 0)
        for (id in tex2d) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
    }

    private fun buildProg(p: Prog, oes: Boolean) {
        p.id = buildProgram(VERTEX, fragmentSrc(oes))
        p.aPos = GLES20.glGetAttribLocation(p.id, "aPos")
        p.aTex = GLES20.glGetAttribLocation(p.id, "aTex")
        p.aLocal = GLES20.glGetAttribLocation(p.id, "aLocal")
        p.uTexMatrix = GLES20.glGetUniformLocation(p.id, "uTexMatrix")
        p.uTex = GLES20.glGetUniformLocation(p.id, "uTex")
        p.uHalf = GLES20.glGetUniformLocation(p.id, "uHalf")
        p.uRadius = GLES20.glGetUniformLocation(p.id, "uRadius")
        p.uBorder = GLES20.glGetUniformLocation(p.id, "uBorder")
        p.uBorderColor = GLES20.glGetUniformLocation(p.id, "uBorderColor")
        p.uKeyMode = GLES20.glGetUniformLocation(p.id, "uKeyMode")
        p.uKeyColor = GLES20.glGetUniformLocation(p.id, "uKeyColor")
        p.uKeyTol = GLES20.glGetUniformLocation(p.id, "uKeyTol")
        p.uKeySoft = GLES20.glGetUniformLocation(p.id, "uKeySoft")
        p.uUseAlpha = GLES20.glGetUniformLocation(p.id, "uUseAlpha")
        p.uMask = GLES20.glGetUniformLocation(p.id, "uMask")
    }

    fun release() {
        running = false
        val latch = CountDownLatch(1)
        handler.post {
            handler.removeCallbacks(tick)
            try { destroyPreview() } catch (_: Throwable) {}
            try { seg?.close() } catch (_: Throwable) {}
            seg = null
            if (PreviewBridge.compositor === this) PreviewBridge.compositor = null
            for (i in 0 until NCAM) {
                try { camSurfaces[i]?.release() } catch (_: Throwable) {}
                try { camSt[i]?.release() } catch (_: Throwable) {}
            }
            try { sourceSurface.release() } catch (_: Throwable) {}
            try { srcSt.release() } catch (_: Throwable) {}
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private fun floatBuf(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        /** مقياس المحتوى (بكسل لكل وحدة محتوى) في المحورين بحسب الملاءمة والتكبير. cw/ch = أبعاد المحتوى بعد التدوير */
        fun contentScale(fit: SourceFit, cw: Float, ch: Float, rw: Float, rh: Float, zoom: Float): FloatArray {
            val z = zoom.coerceIn(0.2f, 6f)
            return when (fit) {
                SourceFit.FIT -> { val s = min(rw / cw, rh / ch) * z; floatArrayOf(s, s) }
                SourceFit.FILL -> { val s = max(rw / cw, rh / ch) * z; floatArrayOf(s, s) }
                SourceFit.STRETCH -> floatArrayOf(rw / cw * z, rh / ch * z)
            }
        }

        /**
         * مدى الإزاحة الممكن (بوحدات بكسل المستطيل) في المحورين: pan=±1 يعني تحريك المحتوى بهذا المقدار.
         * يستعملها محرر الواجهة لتحويل سحب الإصبع إلى إزاحة.
         */
        fun panSlack(fit: SourceFit, srcW: Float, srcH: Float, rot: Int, zoom: Float, rw: Float, rh: Float): FloatArray {
            val rotated = rot % 180 != 0
            val cw = if (rotated) srcH else srcW
            val ch = if (rotated) srcW else srcH
            if (cw < 1f || ch < 1f || rw < 1f || rh < 1f) return floatArrayOf(0f, 0f)
            val sc = contentScale(fit, cw, ch, rw, rh, zoom)
            return floatArrayOf(abs(cw * sc[0] - rw) / 2f, abs(ch * sc[1] - rh) / 2f)
        }

        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            attribute vec2 aLocal;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            varying vec2 vLocal;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
                vLocal = aLocal;
            }
        """

        // SDF لمستطيل بزوايا مدوّرة: يعطي الشكل (مستطيل/مدوّر/دائرة) والإطار داخل الـ Shader نفسه
        private const val FRAGMENT_BODY = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTex;
            varying vec2 vLocal;
            uniform SAMPLER uTex;
            uniform vec2 uHalf;
            uniform float uRadius;
            uniform float uBorder;
            uniform vec4 uBorderColor;
            uniform float uKeyMode;
            uniform vec3 uKeyColor;
            uniform float uKeyTol;
            uniform float uKeySoft;
            uniform sampler2D uMask;
            uniform float uUseAlpha;
            vec2 cbcr(vec3 c) {
                return vec2(-0.169 * c.r - 0.331 * c.g + 0.5 * c.b, 0.5 * c.r - 0.419 * c.g - 0.081 * c.b);
            }
            void main() {
                vec2 p = vLocal * uHalf;
                vec2 q = abs(p) - (uHalf - vec2(uRadius));
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
                float a = 1.0 - smoothstep(-1.0, 0.0, d);
                vec4 c = texture2D(uTex, vTex);
                float ta = 1.0;
                if (uUseAlpha > 0.5) { ta = c.a; if (c.a > 0.001) c.rgb = c.rgb / c.a; }
                float b = 0.0;
                if (uBorder > 0.0) { b = smoothstep(-uBorder - 1.0, -uBorder, d); }
                c.rgb = mix(c.rgb, uBorderColor.rgb, b * uBorderColor.a);
                float k = 1.0;
                if (uKeyMode > 0.5 && uKeyMode < 1.5) {
                    float dd = distance(cbcr(c.rgb), cbcr(uKeyColor));
                    k = smoothstep(uKeyTol, uKeyTol + uKeySoft, dd);
                } else if (uKeyMode > 1.5) {
                    float m = texture2D(uMask, vec2(vLocal.x * 0.5 + 0.5, 0.5 - vLocal.y * 0.5)).r;
                    k = smoothstep(0.3, 0.7, m);
                }
                gl_FragColor = vec4(c.rgb, a * k * ta);
            }
        """

        private fun fragmentSrc(oes: Boolean): String {
            val head = if (oes) "#extension GL_OES_EGL_image_external : require\n" else ""
            return head + FRAGMENT_BODY.trimIndent().replace("SAMPLER", if (oes) "samplerExternalOES" else "sampler2D")
        }

        private fun buildProgram(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val st = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
            check(st[0] == GLES20.GL_TRUE) { "link failed: " + GLES20.glGetProgramInfoLog(p) }
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src.trimIndent())
            GLES20.glCompileShader(s)
            val st = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
            check(st[0] == GLES20.GL_TRUE) { "shader failed: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
    }
}
