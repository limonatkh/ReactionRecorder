package com.reactionrecorder

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * متحكّم "المشهد" الحيّ أثناء التسجيل. تملكه الخدمة، وتستعمله الواجهة عبر [RecorderState.scene].
 * كل الاستدعاءات من الخيط الرئيسي.
 *
 *  - قائمة التشغيل: فيديو أو صورة، تشغيل/إيقاف/تقديم/تأخير/التالي/السابق/قفزة، تكرار، تدوير، تحريك وتكبير.
 *  - شاشة الانتظار: صورة أو فيديو (يتكرر) أو نص، تحجب كل شيء وتُسجَّل في الفيديو، ثم نعود حيث كنّا.
 *  - الكاميرا: قلب أمامية/خلفية، مرآة، إخفاء، كاميرتان معًا (إن دعمها الجهاز).
 *  - الصوت: كتم الميكروفون / صوت الفيديو / الكل، وتشغيل صامت لمنع الصدى.
 */
class Scene(
    private val ctx: Context,
    private val cfg: RecordConfig,
    private val rec: Recorder,
    private val player: SourcePlayer?,
    private val host: Host,
) {
    interface Host {
        fun camFront(): Boolean
        fun camMirror(): Boolean
        fun flipCamera()
        /** سطوع الكاميرا (تعويض التعريض) */
        fun setExposure(i: Int) {}
        fun toggleMirror()
        fun dualAvailable(): Boolean
        fun dualActive(): Boolean
        fun setDual(on: Boolean, done: (Boolean) -> Unit)
        /** يجهّز كاميرا أمامية/خلفية كمصدر رئيسي، ويعيد فهرسها في المركّب أو -1 */
        fun ensureCamera(front: Boolean, done: (Int) -> Unit)
        /** انتهى آخر عنصر والمستخدم اختار الإيقاف التلقائي */
        fun allFinished()
        fun refreshNotification()
    }

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val comp: Compositor get() = rec.compositor
    @Volatile private var released = false

    /** هل هناك مشغّل فيديو (وضع Video file)؟ */
    val videoMode: Boolean get() = player != null

    val index: Int get() = Playlist.index
    val items: List<PlayItem> get() = Playlist.items
    fun current(): PlayItem? = Playlist.current()

    var ended: Boolean = false
        private set
    var standby: Boolean = false
        private set
    var loop: Boolean = false
        private set
    var silent: Boolean = cfg.silentPlayback
        private set
    var rotation: Int = 0
        private set
    var zoom: Float = 1f
        private set
    var panX: Float = 0f
        private set
    var panY: Float = 0f
        private set

    /** 0 = يُعرض الفيديو/الشاشة، 1 = الكاميرا الأمامية مكان الفيديو، 2 = الخلفية مكان الفيديو */
    var camMode: Int = 0
        private set
    private var camSavedPlaying = false

    // حالة ما قبل الانتظار
    private var sbSavedPos = 0L
    private var sbSavedPlaying = false
    private var sbSavedMic = false
    private var sbVideo = false
    private var pausedWasPlaying = false

    init {
        if (player == null) { zoom = cfg.scrZoom; panX = cfg.scrPanX; panY = cfg.scrPanY }
        rotation = ((cfg.srcRot % 360) + 360) % 360
    }

    /** تدوير الكاميرا الشخصية (السيلفي) مباشرة */
    fun setCamAdjust(deg: Int) { comp.cameraAdjust = ((deg % 360) + 360) % 360 }
    fun setSrcRotation(deg: Int) { rotation = ((deg % 360) + 360) % 360; applyTransform() }

    /** يُستدعى بعد تجهيز الـ Compositor */
    fun applyInitial() {
        applyTransform()
        silentApply()
    }

    private fun silentApply() { player?.setSilent(silent) }

    // ───────── تحويل المحتوى ─────────

    private fun applyTransform() {
        val c = comp
        c.srcZoom = zoom; c.srcPanX = panX; c.srcPanY = panY; c.srcRot = rotation
    }

    fun zoomBy(f: Float) { zoom = (zoom * f).coerceIn(0.3f, 5f); applyTransform() }
    fun panBy(dx: Float, dy: Float) {
        panX = (panX + dx).coerceIn(-1f, 1f); panY = (panY + dy).coerceIn(-1f, 1f); applyTransform()
    }
    fun rotate90() { rotation = (rotation + 90) % 360; applyTransform() }
    /** 100%: إلغاء التكبير والإزاحة والتدوير */
    fun resetView() { zoom = 1f; panX = 0f; panY = 0f; rotation = 0; applyTransform() }

    val fit: SourceFit get() = comp.sourceFit
    fun cycleFit(): SourceFit {
        val n = SourceFit.values()[(comp.sourceFit.ordinal + 1) % 3]
        comp.sourceFit = n
        return n
    }

    /** مدى الإزاحة (بكسل) داخل مستطيل المصدر rw×rh الذي تعرضه الواجهة */
    fun slack(rw: Float, rh: Float): FloatArray =
        Compositor.panSlack(comp.sourceFit, comp.contentW.toFloat(), comp.contentH.toFloat(), rotation, zoom, rw, rh)

    /** تمرير تخطيط الواجهة (مواضع الفيديو والكاميرا) فورًا إلى الفيديو المسجَّل */
    fun applyLayout(l: LayoutConfig) { comp.layout = l.deepCopy() }

    // ───────── قائمة التشغيل ─────────

    private fun showItem(i: Int, play: Boolean) {
        val list = Playlist.items
        if (i !in list.indices) return
        Playlist.index = i
        Playlist.save(ctx)
        ended = false
        resetView()
        val it = list[i]
        if (it.image) {
            player?.pause()
            loadImage(it.uri)
        } else {
            comp.setImage(null)
            if (player != null) {
                player.setLoop(loop)
                player.load(Uri.parse(it.uri), play)
            }
        }
    }

    private fun loadImage(uri: String) {
        bg.execute {
            val bmp = ImageLoader.load(ctx, uri, 2048)
            main.post {
                if (released || current()?.uri != uri) {
                    try { bmp?.recycle() } catch (_: Throwable) {}
                    return@post
                }
                if (bmp != null) comp.setImage(bmp)
            }
        }
    }

    /** تحميل أول عنصر بدون تشغيل (قبل العدّ التنازلي) */
    fun loadInitial() { showItem(Playlist.index, false) }

    /** يُستدعى مرة واحدة بعد بدء التسجيل الفعلي */
    fun startPlayback() {
        val it = current()
        if (it != null && !it.image) player?.start()
    }

    val playing: Boolean get() = player?.wantsPlay() == true && !ended
    val positionMs: Long get() = player?.positionMs() ?: 0L
    val durationMs: Long get() = player?.durationMs() ?: 0L
    val currentIsImage: Boolean get() = current()?.image == true
    val hasNext: Boolean get() = index < Playlist.items.lastIndex

    fun playPause() {
        val pl = player ?: return
        val it = current() ?: return
        if (it.image || standby || camMode != 0) return
        if (ended) { ended = false; pl.seekTo(0); pl.resume(); return }
        if (pl.wantsPlay()) pl.pause() else pl.resume()
    }

    fun seekBy(deltaMs: Long) {
        val pl = player ?: return
        if (currentIsImage || standby || camMode != 0) return
        val dur = pl.durationMs()
        val t = (pl.positionMs() + deltaMs).coerceAtLeast(0L)
        seekAbs(if (dur > 0L) min(t, max(0L, dur - 300L)) else t)
    }

    fun seekAbs(ms: Long) {
        if (currentIsImage || standby || camMode != 0) return
        ended = false
        player?.seekTo(ms)
    }

    fun toggleLoop() { loop = !loop; player?.setLoop(loop) }

    fun next() { if (hasNext) jump(index + 1) }
    fun prev() { jump(if (index > 0) index - 1 else 0) }

    /** عرض عنصر معيّن الآن (يُنهي شاشة الانتظار إن كانت فعّالة) */
    fun jump(i: Int) {
        if (camMode != 0) exitCameraMode(false)
        if (standby) exitStandby(false)
        showItem(i, true)
    }

    /** المستخدم قال: شغّل التالي (بعد انتهاء العنصر الحالي) */
    fun playNextAfterEnd() { if (hasNext) jump(index + 1) }

    fun addItems(newItems: List<PlayItem>, showNow: Boolean) {
        if (newItems.isEmpty()) return
        val first = Playlist.items.size
        Playlist.items.addAll(newItems)
        Playlist.save(ctx)
        if (showNow) jump(first)
    }

    fun removeItem(i: Int) {
        if (i !in Playlist.items.indices) return
        val wasCurrent = i == Playlist.index
        Playlist.remove(i)
        Playlist.save(ctx)
        if (wasCurrent && Playlist.items.isNotEmpty()) showItem(Playlist.index, false)
    }

    fun moveItem(i: Int, delta: Int) {
        Playlist.move(i, delta)
        Playlist.save(ctx)
    }

    /** المشغّل أنهى العنصر الحالي */
    fun onItemEnded() {
        if (released || standby) return
        ended = true
        if (!hasNext && cfg.autoStop) host.allFinished()
    }

    // ───────── شاشة الانتظار ─────────

    fun toggleStandby() { if (standby) exitStandby(true) else enterStandby() }

    fun enterStandby() {
        if (standby || released) return
        if (camMode != 0) exitCameraMode(false)
        standby = true
        sbSavedPlaying = player?.wantsPlay() == true && !ended
        sbSavedPos = player?.positionMs() ?: 0L
        sbSavedMic = RecorderState.micMuted
        player?.pause()
        if (cfg.standbyMuteMic) setMicMuted(true)

        val uri = cfg.standbyUri
        if (uri.isNotEmpty() && cfg.standbyIsVideo && player != null) {
            sbVideo = true
            comp.setStandby(2, null)
            player.setLoop(true)
            player.load(Uri.parse(uri), true)
        } else {
            sbVideo = false
            val wantImage = uri.isNotEmpty() && !cfg.standbyIsVideo
            val w = rec.resolved.w; val h = rec.resolved.h
            val text = cfg.standbyText
            bg.execute {
                val bmp = (if (wantImage) ImageLoader.load(ctx, uri, 2048) else null) ?: StandbyArt.render(text, w, h)
                main.post {
                    if (standby && !released) comp.setStandby(1, bmp) else try { bmp.recycle() } catch (_: Throwable) {}
                }
            }
        }
    }

    /** خروج من الانتظار. restoreMain=false عند الانتقال لعنصر جديد (لن نعيد القديم) */
    fun exitStandby(restoreMain: Boolean) {
        if (!standby) return
        standby = false
        comp.setStandby(0, null)
        setMicMuted(sbSavedMic)
        val it = current()
        if (player != null) {
            player.setLoop(loop)
            if (restoreMain) {
                if (sbVideo) {
                    if (it != null && !it.image) player.load(Uri.parse(it.uri), sbSavedPlaying, sbSavedPos) else player.pause()
                } else if (sbSavedPlaying) {
                    player.resume()
                }
            } else if (sbVideo) {
                player.pause()
            }
        }
        sbVideo = false
    }

    // ───────── الكاميرا ─────────

    fun flipCamera() = host.flipCamera()
    fun toggleMirror() = host.toggleMirror()
    fun toggleCameraVisible() { comp.cameraVisible = !comp.cameraVisible }
    fun setCameraVisible(v: Boolean) { comp.cameraVisible = v }
    /** الرسم فوق الفيديو (بينسجّل) */
    fun setOverlay(bmp: android.graphics.Bitmap?) = comp.setOverlay(bmp)
    fun setExposure(i: Int) = host.setExposure(i)

    /** تطبيق إعدادات إزالة الخلفية فورًا (من الإعدادات أثناء التسجيل) */
    fun applyKey(c: RecordConfig) { comp.setKey(c.keyMode, c.keyColor, c.keyTol, c.keySoft) }

    /** عرض الكاميرا (أمامية/خلفية) مكان الفيديو. الفيديو يتوقف مؤقتًا ويكمل عند العودة */
    fun showCamera(front: Boolean, done: (Boolean) -> Unit = {}) {
        if (released) { done(false); return }
        if (standby) exitStandby(false)
        host.ensureCamera(front) { idx ->
            if (released) { done(false); return@ensureCamera }
            if (idx < 0) { done(false); return@ensureCamera }
            if (camMode == 0) {
                camSavedPlaying = player?.wantsPlay() == true && !ended
                player?.pause()
            }
            camMode = if (front) 1 else 2
            comp.camMain = idx
            done(true)
        }
    }

    /** الرجوع للفيديو. بدون ثنائية: مربع السيلفي يبقى موجودًا لكن مخفيًا حتى يُظهره المستخدم */
    fun exitCameraMode(resume: Boolean = true) {
        if (camMode == 0) return
        camMode = 0
        comp.camMain = -1
        if (!host.dualActive()) {
            comp.cameraVisible = false
            if (!host.camFront()) host.flipCamera()
        }
        if (resume && camSavedPlaying) player?.resume()
        camSavedPlaying = false
    }
    val cameraVisible: Boolean get() = comp.cameraVisible
    val camFront: Boolean get() = host.camFront()
    val mirror: Boolean get() = host.camMirror()
    val dualAvailable: Boolean get() = host.dualAvailable()
    val dualActive: Boolean get() = host.dualActive()
    fun setDual(on: Boolean, done: (Boolean) -> Unit) {
        if (!on && camMode != 0) exitCameraMode(true) // الكاميرا المعروضة قد تختفي عند إيقاف الثنائية
        host.setDual(on, done)
    }

    // ───────── الصوت ─────────

    fun setMicMuted(m: Boolean) {
        RecorderState.micMuted = m
        rec.setMicMuted(m)
        RecorderState.changed()
        host.refreshNotification()
    }

    fun setSrcMuted(m: Boolean) {
        RecorderState.srcMuted = m
        rec.setSrcMuted(m)
        RecorderState.changed()
    }

    fun muteAll(m: Boolean) { setMicMuted(m); setSrcMuted(m) }
    val allMuted: Boolean get() = RecorderState.micMuted && RecorderState.srcMuted

    fun toggleSilent() { silent = !silent; silentApply() }

    // ───────── دورة حياة التسجيل ─────────

    fun onRecPause() { pausedWasPlaying = player?.wantsPlay() == true; player?.pause() }
    fun onRecResume() { if (pausedWasPlaying) player?.resume() }

    fun release() {
        released = true
        try { bg.shutdownNow() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
    }
}
