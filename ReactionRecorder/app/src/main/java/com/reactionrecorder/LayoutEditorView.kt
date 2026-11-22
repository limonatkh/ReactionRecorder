package com.reactionrecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import kotlin.math.max
import kotlin.math.min

/**
 * محرر الـ Layout: يعرض الإطار النهائي (9:16 أو 16:9) وفيه مربع الفيديو المصدر ومربع الكاميرا الحيّة.
 * السحب = تحريك، الزاوية السفلية اليمنى = تغيير الحجم، قرصة بإصبعين = تكبير/تصغير الكاميرا.
 * الشكل (دائرة/مدوّر) والإطار يُرسمان هنا بنفس معادلات الـ Shader النهائي ليتطابق الـ Preview مع الناتج.
 */
class LayoutEditorView(ctx: Context) : FrameLayout(ctx) {
    val previewView = PreviewView(ctx).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE // مطلوب لقصّ الشكل بالـ Outline
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    var cfg: RecordConfig = RecordConfig()
    var onChanged: (() -> Unit)? = null
    /** نسبة عرض/ارتفاع شاشة الهاتف (لرسم منطقة الفيديو المصدر الفعلية في وضع FIT) */
    var screenAspect: Float = 9f / 20f
    var overlayText: String = ""
        set(v) { field = v; invalidate() }
    /** نص داخل مربع المصدر (اسم الفيديو في وضع Video file) */
    var sourceLabel: String = "VIDEO / SCREEN"
        set(v) { field = v; invalidate() }
    /** صورة مصغّرة للفيديو المختار لتظهر في المعاينة */
    var sourceThumb: android.graphics.Bitmap? = null
        set(v) { field = v; invalidate() }
    /** منع اللمس كليًا (أثناء التجهيز/العدّ التنازلي/الإنهاء) */
    var locked = false
    /** أثناء التسجيل الحيّ (وضع Video file): التعديلات تُرسَل فورًا إلى الفيديو المسجَّل */
    var live = false
    /** 0 = تحريك/تغيير حجم المربعات، 1 = تحريك وتكبير محتوى الفيديو نفسه */
    var gestureMode = 0
    /** الكاميرا الثانية فعّالة (أمامية + خلفية) */
    var dualOn = false
        set(v) { field = v; invalidate() }
    var onLiveEdit: (() -> Unit)? = null
    /** «بدون كاميرا»: ما في مربع كاميرا (ولا معاينة ولا إطار ولا مقابض) */
    var noCam = false
        set(v) { field = v; previewView.visibility = if (v || monitor) View.INVISIBLE else View.VISIBLE; invalidate() }

    /** طبقة رسم فوق مربع الفيديو (الرسم على الفيديو وقت التسجيل) */
    var drawLayer: View? = null
        set(v) {
            field?.let { removeView(it) }
            field = v
            if (v != null) addView(v)
            requestLayout()
        }

    /** شاشة المراقبة: عرض حيّ للفيديو المركَّب أثناء التسجيل (وضع Video file) */
    private val monitorView = SurfaceView(ctx).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { PreviewBridge.attach(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) { PreviewBridge.attach(h.surface) }
            override fun surfaceDestroyed(h: SurfaceHolder) { PreviewBridge.detach() }
        })
        visibility = View.GONE
    }
    var monitor: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            monitorView.visibility = if (v) View.VISIBLE else View.GONE
            previewView.visibility = if (v) View.INVISIBLE else View.VISIBLE
            requestLayout()
        }

    private val frame = RectF()
    private val pBg = Paint().apply { color = Color.parseColor("#0E0F11") }
    private val pFrame = Paint().apply { color = Color.BLACK }
    private val pSrc = Paint().apply { color = Color.parseColor("#2B3040") }
    private val pSrcEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#55FFFFFF"); strokeWidth = 2f }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#99FFFFFF"); textAlign = Paint.Align.CENTER }
    private val pBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF3B30") }
    private val pHandleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val pBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val density = resources.displayMetrics.density

    private enum class Mode { NONE, MOVE_CAM, MOVE_SRC, RESIZE_CAM, RESIZE_SRC, MOVE_CAM2, RESIZE_CAM2, PAN_CONTENT }
    private var mode = Mode.NONE
    private var lx = 0f
    private var ly = 0f
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (mode == Mode.PAN_CONTENT) {
                RecorderState.scene?.zoomBy(d.scaleFactor)
                return true
            }
            if (mode != Mode.MOVE_CAM && mode != Mode.MOVE_CAM2) return false
            val c = if (mode == Mode.MOVE_CAM2) cfg.layout.camera2 else cfg.layout.camera
            val f = d.scaleFactor
            val cx = c.x + c.w / 2f; val cy = c.y + c.h / 2f
            val nw = (c.w * f).coerceIn(0.1f, 1f); val nh = (c.h * f).coerceIn(0.1f, 1f)
            c.w = nw; c.h = nh
            c.x = (cx - nw / 2f).coerceIn(0f, 1f - nw); c.y = (cy - nh / 2f).coerceIn(0f, 1f - nh)
            touched(); return true
        }
    })

    init {
        setWillNotDraw(false)
        addView(monitorView, LayoutParams(1, 1))
        addView(previewView, LayoutParams(1, 1))
        previewView.clipToOutline = true
        previewView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                val w = v.width; val h = v.height
                val l = cfg.layout
                val r = when (l.shape) {
                    CamShape.RECT -> 0f
                    CamShape.ROUNDED -> l.radiusFrac.coerceIn(0f, 0.5f) * min(w, h)
                    CamShape.CIRCLE -> min(w, h) / 2f
                }
                if (r <= 0f) o.setRect(0, 0, w, h) else o.setRoundRect(0, 0, w, h, r)
            }
        }
    }

    /** يُستدعى بعد أي تغيير خارجي في cfg (Preset، شكل، اتجاه…) */
    fun refresh() {
        previewView.invalidateOutline()
        requestLayout(); invalidate()
    }

    private fun aspect() = if (cfg.portrait) 9f / 16f else 16f / 9f

    private fun computeFrame() {
        val pad = 6f * density
        val maxW = width - pad * 2; val maxH = height - pad * 2
        val a = aspect()
        val w = min(maxW, maxH * a); val h = w / a
        frame.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private fun px(r: NRect) = RectF(
        frame.left + r.x * frame.width(), frame.top + r.y * frame.height(),
        frame.left + (r.x + r.w) * frame.width(), frame.top + (r.y + r.h) * frame.height(),
    )

    private fun camPx() = px(effectiveCameraRect(cfg.layout, cfg.outW, cfg.outH))
    private fun cam2Px() = px(effectiveRect(cfg.layout.camera2, cfg.layout.shape, cfg.outW, cfg.outH))
    private fun srcPx() = px(cfg.layout.source)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        computeFrame()
        val c = camPx()
        val w = max(1, c.width().toInt()); val h = max(1, c.height().toInt())
        previewView.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        previewView.layout(c.left.toInt(), c.top.toInt(), c.left.toInt() + w, c.top.toInt() + h)
        previewView.invalidateOutline()
        val fw = max(1, frame.width().toInt()); val fh = max(1, frame.height().toInt())
        monitorView.measure(MeasureSpec.makeMeasureSpec(fw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(fh, MeasureSpec.EXACTLY))
        monitorView.layout(frame.left.toInt(), frame.top.toInt(), frame.left.toInt() + fw, frame.top.toInt() + fh)
        drawLayer?.let { d ->
            val s = srcPx()
            val sw = max(1, s.width().toInt()); val sh = max(1, s.height().toInt())
            d.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY))
            d.layout(s.left.toInt(), s.top.toInt(), s.left.toInt() + sw, s.top.toInt() + sh)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(pBg)
        canvas.drawRect(frame, pFrame)
        if (cfg.layout.cameraOnTop) drawSource(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas) // الكاميرا الحيّة
        val l = cfg.layout
        if (!l.cameraOnTop && !monitor) drawSource(canvas)
        // إطار الكاميرا
        val c = camPx()
        val bw = l.borderFrac * frame.width()
        if (bw > 0.5f && !monitor && !noCam) {
            pBorder.strokeWidth = bw; pBorder.color = l.borderColor
            val inset = bw / 2f
            val rr = RectF(c.left + inset, c.top + inset, c.right - inset, c.bottom - inset)
            val rad = when (l.shape) {
                CamShape.RECT -> 0f
                CamShape.ROUNDED -> l.radiusFrac.coerceIn(0f, 0.5f) * min(c.width(), c.height()) - inset
                CamShape.CIRCLE -> min(rr.width(), rr.height()) / 2f
            }.coerceAtLeast(0f)
            canvas.drawRoundRect(rr, rad, rad, pBorder)
        }
        if (!locked) {
            if (gestureMode == 0) {
                if (!noCam) { canvas.drawCircle(c.right, c.bottom, 9f * density, pHandle); canvas.drawCircle(c.right, c.bottom, 9f * density, pHandleEdge) }
                val s = srcPx()
                canvas.drawCircle(s.right, s.bottom, 9f * density, pHandle); canvas.drawCircle(s.right, s.bottom, 9f * density, pHandleEdge)
                if (dualOn) {
                    val c2 = cam2Px()
                    canvas.drawCircle(c2.right, c2.bottom, 9f * density, pHandle); canvas.drawCircle(c2.right, c2.bottom, 9f * density, pHandleEdge)
                }
            } else {
                pText.textSize = 11f * density
                canvas.drawText("✋ drag = move video • pinch = zoom", frame.centerX(), frame.top + 16f * density, pText)
            }
        }
        if (overlayText.isNotEmpty()) {
            pBig.textSize = frame.width() * 0.35f
            val y = frame.centerY() - (pBig.descent() + pBig.ascent()) / 2f
            if (overlayText.length > 2) pBig.textSize = frame.width() * 0.12f
            canvas.drawText(overlayText, frame.centerX(), y, pBig)
        }
    }

    private fun drawSource(canvas: Canvas) {
        val s = srcPx()
        var inner = RectF(s)
        if (cfg.sourceFit == SourceFit.FIT) {
            val sc = min(s.width() / screenAspectOrient(), s.height()) // نحسب بوحدات الارتفاع
            val h = sc; val w = sc * screenAspectOrient()
            inner = RectF(s.centerX() - w / 2f, s.centerY() - h / 2f, s.centerX() + w / 2f, s.centerY() + h / 2f)
        }
        canvas.drawRect(inner, pSrc)
        val bmp = sourceThumb
        if (bmp != null) {
            if (cfg.sourceFit == SourceFit.FILL) {
                // اقتطاع مركزي للصورة المصغّرة كي تطابق ما سيُسجَّل
                val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
                val ra = inner.width() / inner.height(); val ba = bw / bh
                val src = if (ba > ra) {
                    val cw = bh * ra; android.graphics.Rect(((bw - cw) / 2f).toInt(), 0, ((bw + cw) / 2f).toInt(), bmp.height)
                } else {
                    val ch = bw / ra; android.graphics.Rect(0, ((bh - ch) / 2f).toInt(), bmp.width, ((bh + ch) / 2f).toInt())
                }
                canvas.drawBitmap(bmp, src, inner, null)
            } else {
                canvas.drawBitmap(bmp, null, inner, null)
            }
        }
        canvas.drawRect(s, pSrcEdge)
        if (bmp == null) {
            pText.textSize = 12f * density
            canvas.drawText(sourceLabel, s.centerX(), s.centerY(), pText)
        }
    }

    private fun screenAspectOrient() = screenAspect

    // ───────────── اللمس ─────────────

    private fun hit(x: Float, y: Float, px: Float, py: Float) =
        kotlin.math.abs(x - px) < 26f * density && kotlin.math.abs(y - py) < 26f * density

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (locked) return false
        scaler.onTouchEvent(e)
        if (scaler.isInProgress) { return true }
        val l = cfg.layout
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val c = camPx(); val s = srcPx()
                val c2 = cam2Px()
                mode = when {
                    gestureMode == 1 && live -> if (frame.contains(e.x, e.y)) Mode.PAN_CONTENT else Mode.NONE
                    dualOn && hit(e.x, e.y, c2.right, c2.bottom) -> Mode.RESIZE_CAM2
                    dualOn && c2.contains(e.x, e.y) && !hit(e.x, e.y, c.right, c.bottom) && !c.contains(e.x, e.y) -> Mode.MOVE_CAM2
                    noCam && hit(e.x, e.y, s.right, s.bottom) -> Mode.RESIZE_SRC
                    noCam -> if (s.contains(e.x, e.y)) Mode.MOVE_SRC else Mode.NONE
                    hit(e.x, e.y, c.right, c.bottom) -> Mode.RESIZE_CAM
                    hit(e.x, e.y, s.right, s.bottom) -> Mode.RESIZE_SRC
                    c.contains(e.x, e.y) && (l.cameraOnTop || !s.contains(e.x, e.y)) -> Mode.MOVE_CAM
                    s.contains(e.x, e.y) -> Mode.MOVE_SRC
                    c.contains(e.x, e.y) -> Mode.MOVE_CAM
                    else -> Mode.NONE
                }
                if (mode == Mode.MOVE_CAM || mode == Mode.RESIZE_CAM) l.camera = effectiveCameraRect(l, cfg.outW, cfg.outH).copyRect()
                if (mode == Mode.MOVE_CAM2 || mode == Mode.RESIZE_CAM2) l.camera2 = effectiveRect(l.camera2, l.shape, cfg.outW, cfg.outH).copyRect()
                lx = e.x; ly = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return mode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1) return true
                val dx = (e.x - lx) / frame.width(); val dy = (e.y - ly) / frame.height()
                lx = e.x; ly = e.y
                if (mode == Mode.PAN_CONTENT) {
                    val sc = RecorderState.scene
                    if (sc != null) {
                        val sr = srcPx()
                        val sl = sc.slack(sr.width(), sr.height())
                        val pdx = if (sl[0] > 2f) dx * frame.width() / sl[0] else 0f
                        val pdy = if (sl[1] > 2f) dy * frame.height() / sl[1] else 0f
                        sc.panBy(pdx, pdy)
                    }
                    return true
                }
                when (mode) {
                    Mode.MOVE_CAM2 -> move(l.camera2, dx, dy)
                    Mode.RESIZE_CAM2 -> resize(l.camera2, e, square = l.shape == CamShape.CIRCLE)
                    Mode.MOVE_CAM -> move(l.camera, dx, dy)
                    Mode.MOVE_SRC -> move(l.source, dx, dy)
                    Mode.RESIZE_CAM -> resize(l.camera, e, square = l.shape == CamShape.CIRCLE)
                    Mode.RESIZE_SRC -> resize(l.source, e, square = false)
                    else -> return false
                }
                touched()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = Mode.NONE }
        }
        return true
    }

    private fun move(r: NRect, dx: Float, dy: Float) {
        r.x = (r.x + dx).coerceIn(0f, 1f - r.w)
        r.y = (r.y + dy).coerceIn(0f, 1f - r.h)
    }

    private fun resize(r: NRect, e: MotionEvent, square: Boolean) {
        var nw = ((e.x - frame.left) / frame.width() - r.x)
        var nh = ((e.y - frame.top) / frame.height() - r.y)
        if (square) { // 1:1 بالبكسل
            val side = max(nw * frame.width(), nh * frame.height())
            nw = side / frame.width(); nh = side / frame.height()
        }
        r.w = nw.coerceIn(0.1f, 1f - r.x)
        r.h = nh.coerceIn(0.1f, 1f - r.y)
        if (square) { val side = min(r.w * frame.width(), r.h * frame.height()); r.w = side / frame.width(); r.h = side / frame.height() }
    }

    private fun touched() {
        cfg.layout.presetId = 6 // Custom
        requestLayout(); invalidate()
        onChanged?.invoke()
        if (live) onLiveEdit?.invoke()
    }
}
