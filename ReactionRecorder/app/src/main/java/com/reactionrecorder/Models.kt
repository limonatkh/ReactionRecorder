package com.reactionrecorder

import android.content.Context
import kotlin.math.min

/** مستطيل بإحداثيات مُطبَّعة (0..1) نسبةً إلى إطار الفيديو النهائي، الأصل أعلى اليسار. */
data class NRect(var x: Float, var y: Float, var w: Float, var h: Float) {
    fun copyRect() = NRect(x, y, w, h)
}

enum class CamShape { RECT, ROUNDED, CIRCLE }

/** كيف يُوضع فيديو المصدر داخل مستطيله: FIT = بدون اقتطاع (أشرطة سوداء)، FILL = ملء مع اقتطاع، STRETCH = ملء بالتمديد. */
enum class SourceFit { FIT, FILL, STRETCH }

/** مصدر الفيديو: تصوير شاشة الجوال، أو تشغيل ملف/رابط فيديو داخل التطبيق نفسه (بدون تصوير الشاشة). */
enum class SourceMode { SCREEN, VIDEO }

data class LayoutConfig(
    var source: NRect,
    var camera: NRect,
    var cameraOnTop: Boolean = true,
    var shape: CamShape = CamShape.ROUNDED,
    /** سمك الإطار كنسبة من عرض الفيديو (0 = بدون إطار) */
    var borderFrac: Float = 0.006f,
    /** نصف قطر الزوايا كنسبة من أصغر ضلع للكاميرا (0..0.5) */
    var radiusFrac: Float = 0.12f,
    var borderColor: Int = 0xFFFFFFFF.toInt(),
    var presetId: Int = 1,
    /** حصة الفيديو في الـ Presets المقسومة (0.3..0.8) */
    var ratio: Float = 0.7f,
    /** الكاميرا الثانية (وضع الكاميرتين معًا أمامية + خلفية) */
    var camera2: NRect = NRect(0.04f, 0.70f, 0.30f, 0.17f),
) {
    fun deepCopy(): LayoutConfig = copy(source = source.copyRect(), camera = camera.copyRect(), camera2 = camera2.copyRect())
}

object Presets {
    /** مكان افتراضي للكاميرا الثانية: زاوية سفلية قريبة من المربع بالبكسل */
    fun defaultCam2(portrait: Boolean): NRect =
        if (portrait) NRect(0.04f, 0.74f, 0.30f, 0.17f) else NRect(0.04f, 0.56f, 0.18f, 0.32f)

    val names: List<String> get() = listOf(tx("فيديو + كاميرا", "Video + Cam"), "70 / 30", tx("جنبًا لجنب", "Side by Side"), tx("أعلى / أسفل", "Top / Bottom"), tx("الكاميرا كاملة", "Cam Full"), tx("مخصص", "Custom"))

    /** يبني Preset رقم id (1..5) بحسب اتجاه الإطار. aspect = العرض/الارتفاع للفيديو النهائي. */
    fun build(id: Int, portrait: Boolean, ratio: Float, old: LayoutConfig? = null): LayoutConfig {
        val aspect = if (portrait) 9f / 16f else 16f / 9f
        val base = old ?: LayoutConfig(NRect(0f, 0f, 1f, 1f), NRect(0.6f, 0.7f, 0.35f, 0.2f))
        val r = ratio.coerceIn(0.3f, 0.8f)
        base.presetId = id
        base.ratio = r
        base.camera2 = defaultCam2(portrait)
        when (id) {
            1 -> { // فيديو كامل + كاميرا صغيرة في الزاوية
                base.source = NRect(0f, 0f, 1f, 1f)
                val w = if (portrait) 0.36f else 0.22f
                // الدائرة 1:1 بالبكسل، والمستطيل 4:3
                base.camera = squareish(NRect(1f - w - 0.04f, 0f, w, 0f), aspect, base.shape)
                base.camera.y = (1f - base.camera.h - 0.05f).coerceAtLeast(0f)
                base.cameraOnTop = true
            }
            2 -> { // فيديو r  + كاميرا (1-r)
                if (portrait) {
                    base.source = NRect(0f, 0f, 1f, r); base.camera = NRect(0f, r, 1f, 1f - r)
                } else {
                    base.source = NRect(0f, 0f, r, 1f); base.camera = NRect(r, 0f, 1f - r, 1f)
                }
                base.cameraOnTop = true
            }
            3 -> { // فيديو يسار + كاميرا يمين
                base.source = NRect(0f, 0f, r, 1f); base.camera = NRect(r, 0f, 1f - r, 1f)
                base.cameraOnTop = true
            }
            4 -> { // فيديو أعلى + كاميرا أسفل
                base.source = NRect(0f, 0f, 1f, r); base.camera = NRect(0f, r, 1f, 1f - r)
                base.cameraOnTop = true
            }
            5 -> { // كاميرا كاملة + فيديو صغير
                base.camera = NRect(0f, 0f, 1f, 1f)
                val w = if (portrait) 0.5f else 0.32f
                base.source = NRect(1f - w - 0.04f, if (portrait) 0.70f else 0.60f, w, 0.25f)
                base.cameraOnTop = false
            }
        }
        // في الـ Presets المقسومة نلغي الدائرة والإطار الجانبي كي لا تظهر فجوات غريبة
        if (id in 2..4 && base.shape == CamShape.CIRCLE) base.shape = CamShape.RECT
        return base
    }

    private fun squareish(r: NRect, aspect: Float, shape: CamShape): NRect {
        // نجعل الارتفاع بحيث تكون الدائرة 1:1 بالبكسل، والمستطيل 4:3
        val wPx = r.w * aspect // العرض بوحدات "الارتفاع"
        val hFrac = if (shape == CamShape.CIRCLE) wPx else wPx * 0.75f
        return NRect(r.x, r.y, r.w, hFrac.coerceIn(0.1f, 0.5f))
    }
}

data class RecordConfig(
    var portrait: Boolean = true,
    /** الضلع القصير: 720 أو 1080 */
    var shortSide: Int = 1080,
    var fps: Int = 30,
    /** Mbps — 0 = تلقائي حسب الدقة وFPS */
    var bitrateMbps: Float = 0f,
    var hevc: Boolean = false,
    var micOn: Boolean = true,
    var srcAudioOn: Boolean = true,
    var micVol: Float = 1.0f,
    var srcVol: Float = 0.8f,
    var echoSuppress: Boolean = false,
    var countdown: Int = 3,
    var frontCamera: Boolean = true,
    /** بدون كاميرا خالص: الشاشة/الفيديو + الصوت بس (الكاميرا ما بتنفتح) */
    var noCamera: Boolean = false,
    var mirrorFront: Boolean = true,
    /** دقة الكاميرا الأمامية والخلفية (الضلع الطويل): 640=360p / 854=480p / 1280=720p / 1920=1080p */
    var camLongFront: Int = 1280,
    var camLongBack: Int = 854,
    /** إزالة خلفية السيلفي: 0 إيقاف، 1 كروما (لون يحدده المستخدم)، 2 تلقائي (عزل الشخص) */
    var keyMode: Int = 0,
    var keyColor: Int = 0xFF00B140.toInt(),
    var keyTol: Float = 0.14f,
    var keySoft: Float = 0.08f,
    /** مصدر الصوت: auto = USB أولًا إن وُجد ثم الداخلي، builtin = الداخلي فقط، usb = USB/خارجي فقط */
    var micSource: String = "auto",
    var cameraFps: Int = 30,
    var zoom: Float = 1f,
    var exposureIndex: Int = 0,
    var torch: Boolean = false,
    var mode: SourceMode = SourceMode.SCREEN,
    var fitScreen: SourceFit = SourceFit.FILL,
    var fitVideo: SourceFit = SourceFit.FIT,
    /** إيقاف التسجيل تلقائيًا عند انتهاء آخر فيديو في القائمة (وضع Video file) */
    var autoStop: Boolean = false,
    /** تأخير صوت الفيديو بالميلي ثانية لمعادلة تأخر السماعة (وضع Video file) */
    var videoAudioDelayMs: Int = 100,
    /** تصحيح يدوي لدوران الكاميرا: 0/90/180/270 */
    var camRotAdjust: Int = 0,
    /** تدوير فيديو/شاشة المصدر المبدئي (0/90/180/270) */
    var srcRot: Int = 0,
    /** تشغيل صامت: الصوت يُسجَّل نظيفًا لكنه لا يخرج من السماعة (يمنع الصدى) */
    var silentPlayback: Boolean = false,
    /** شاشة الانتظار: صورة أو فيديو من الجوال، وإلا نص على خلفية داكنة */
    var standbyUri: String = "",
    var standbyName: String = "",
    var standbyIsVideo: Boolean = false,
    var standbyText: String = "Be right back",
    var standbyMuteMic: Boolean = true,
    /** عند الذهاب لاستيراد ملف أثناء التسجيل: تفعيل شاشة الانتظار تلقائيًا */
    var autoStandbyOnImport: Boolean = true,
    /** تنبيه عند غياب سماعات الرأس */
    var warnEcho: Boolean = true,
    /** قصّ/تكبير منطقة الشاشة الملتقطة (وضع Screen): تكبير 1..4 وإزاحة -1..1 */
    var scrZoom: Float = 1f,
    var scrPanX: Float = 0f,
    var scrPanY: Float = 0f,
    var layout: LayoutConfig = Presets.build(1, true, 0.7f),
) {
    /** auto = يُحدَّد عند بدء التسجيل (اتجاه الفيديو المختار أو الشاشة) ثم يثبت؛ portrait؛ landscape */
    var orientMode: String = "auto"
    /** صوت الفيديو يُلتقط بالمايك فقط (يشتغل من السماعة، بدون نسخة نظيفة ولا كتم صدى) */
    var srcViaMic: Boolean = false
    /** الملاءمة الحالية بحسب الوضع (للشاشة غير الفيديو لأن نسبتيهما مختلفتان) */
    var sourceFit: SourceFit
        get() = if (mode == SourceMode.VIDEO) fitVideo else fitScreen
        set(v) { if (mode == SourceMode.VIDEO) fitVideo = v else fitScreen = v }

    val outW: Int get() = if (portrait) shortSide else shortSide * 16 / 9 / 2 * 2
    val outH: Int get() = if (portrait) shortSide * 16 / 9 / 2 * 2 else shortSide

    /** البيتريت الفعلي بالبت/ثانية */
    fun effectiveBitrate(): Int {
        if (bitrateMbps > 0f) return (bitrateMbps * 1_000_000).toInt()
        val base = if (shortSide >= 1080) 8_000_000 else 5_000_000
        val scaled = base * fps / 30
        return if (hevc) (scaled * 0.65f).toInt() else scaled
    }
}

/** الحجم بالبكسل لمستطيل الكاميرا الفعلي (مع جعل الدائرة مربعة) */
fun effectiveCameraRect(l: LayoutConfig, outW: Int, outH: Int): NRect = effectiveRect(l.camera, l.shape, outW, outH)

fun effectiveRect(c: NRect, shape: CamShape, outW: Int, outH: Int): NRect {
    if (shape != CamShape.CIRCLE) return c
    val wPx = c.w * outW
    val hPx = c.h * outH
    val side = min(wPx, hPx)
    val w = side / outW
    val h = side / outH
    return NRect(c.x + (c.w - w) / 2f, c.y + (c.h - h) / 2f, w, h)
}

object ConfigStore {
    private const val P = "reaction_recorder"

    fun load(ctx: Context): RecordConfig {
        val s = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val portrait = s.getBoolean("portrait", true)
        val orientMode = s.getString("orientMode", "auto") ?: "auto"
        val cfg = RecordConfig(
            portrait = portrait,
            shortSide = s.getInt("shortSide", 1080),
            fps = s.getInt("fps", 30),
            bitrateMbps = s.getFloat("bitrateMbps", 0f),
            hevc = s.getBoolean("hevc", false),
            micOn = s.getBoolean("micOn", true),
            srcAudioOn = s.getBoolean("srcAudioOn", true),
            micVol = s.getFloat("micVol", 1f),
            srcVol = s.getFloat("srcVol", 0.8f),
            echoSuppress = s.getBoolean("echo", false),
            countdown = s.getInt("countdown", 3),
            frontCamera = s.getBoolean("front", true),
            noCamera = s.getBoolean("nocam", false),
            mirrorFront = s.getBoolean("mirror", true),
            camLongFront = s.getInt("camLongF", 1280),
            camLongBack = s.getInt("camLongB", 854),
            keyMode = s.getInt("keyMode", 0),
            keyColor = s.getInt("keyColor", 0xFF00B140.toInt()),
            keyTol = s.getFloat("keyTol", 0.14f),
            keySoft = s.getFloat("keySoft", 0.08f),
            micSource = s.getString("micSrc", "auto") ?: "auto",
            cameraFps = s.getInt("camFps", 30),
            zoom = s.getFloat("zoom", 1f),
            exposureIndex = s.getInt("exp", 0),
            torch = false,
            mode = if (s.getInt("mode", 0) == 1) SourceMode.VIDEO else SourceMode.SCREEN,
            // افتراضيًا: الصورة كاملة بدون قص (والفراغ مموّه)؛ ترحيل مرة واحدة من الافتراضي القديم (ملء مع قص)
            fitScreen = if (!s.getBoolean("fitMig2", false)) SourceFit.FIT else SourceFit.values()[s.getInt("fitS", SourceFit.FIT.ordinal).coerceIn(0, 2)],
            fitVideo = SourceFit.values()[s.getInt("fitV", SourceFit.FIT.ordinal).coerceIn(0, 2)],
            autoStop = s.getBoolean("autoStop2", false),
            videoAudioDelayMs = s.getInt("vDelay", 100),
            camRotAdjust = s.getInt("camAdj", 0),
            srcRot = s.getInt("srcRot", 0),
            silentPlayback = s.getBoolean("silent", false),
            standbyUri = s.getString("sbUri", "") ?: "",
            standbyName = s.getString("sbName", "") ?: "",
            standbyIsVideo = s.getBoolean("sbVideo", false),
            standbyText = s.getString("sbText", "Be right back") ?: "Be right back",
            standbyMuteMic = s.getBoolean("sbMute", true),
            autoStandbyOnImport = s.getBoolean("sbAuto", true),
            warnEcho = s.getBoolean("warnEcho", true),
            scrZoom = s.getFloat("scrZ", 1f),
            scrPanX = s.getFloat("scrPX", 0f),
            scrPanY = s.getFloat("scrPY", 0f),
        )
        val shape = CamShape.values()[s.getInt("shape", CamShape.ROUNDED.ordinal).coerceIn(0, 2)]
        val preset = s.getInt("preset", 1)
        val ratio = s.getFloat("ratio", 0.7f)
        val l = Presets.build(1, portrait, ratio).also { it.shape = shape }
        if (preset == 6 && s.contains("sx")) {
            l.source = NRect(s.getFloat("sx", 0f), s.getFloat("sy", 0f), s.getFloat("sw", 1f), s.getFloat("sh", 1f))
            l.camera = NRect(s.getFloat("cx", .6f), s.getFloat("cy", .7f), s.getFloat("cw", .35f), s.getFloat("ch", .2f))
            l.cameraOnTop = s.getBoolean("camTop", true)
            l.presetId = 6
        } else {
            Presets.build(preset.coerceIn(1, 5), portrait, ratio, l)
        }
        l.camera2 = if (s.contains("c2x")) NRect(s.getFloat("c2x", .04f), s.getFloat("c2y", .7f), s.getFloat("c2w", .3f), s.getFloat("c2h", .17f))
        else Presets.defaultCam2(portrait)
        l.borderFrac = s.getFloat("border", 0.006f)
        l.radiusFrac = s.getFloat("radius", 0.12f)
        l.ratio = ratio
        cfg.layout = l
        cfg.orientMode = orientMode
        cfg.srcViaMic = s.getBoolean("srcViaMic", false)
        if (!s.getBoolean("fitMig2", false)) s.edit().putBoolean("fitMig2", true).putInt("fitS", SourceFit.FIT.ordinal).apply()
        return cfg
    }

    fun save(ctx: Context, c: RecordConfig) {
        val l = c.layout
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putBoolean("portrait", c.portrait).putInt("shortSide", c.shortSide).putInt("fps", c.fps)
            .putFloat("bitrateMbps", c.bitrateMbps).putBoolean("hevc", c.hevc)
            .putBoolean("micOn", c.micOn).putBoolean("srcAudioOn", c.srcAudioOn)
            .putFloat("micVol", c.micVol).putFloat("srcVol", c.srcVol).putBoolean("echo", c.echoSuppress)
            .putInt("countdown", c.countdown).putBoolean("front", c.frontCamera).putBoolean("nocam", c.noCamera).putBoolean("mirror", c.mirrorFront)
            .putInt("camLongF", c.camLongFront).putInt("camLongB", c.camLongBack).putInt("camFps", c.cameraFps)
            .putInt("keyMode", c.keyMode).putInt("keyColor", c.keyColor).putFloat("keyTol", c.keyTol).putFloat("keySoft", c.keySoft)
            .putString("micSrc", c.micSource)
            .putFloat("zoom", c.zoom).putInt("exp", c.exposureIndex)
            .putInt("mode", if (c.mode == SourceMode.VIDEO) 1 else 0)
            .putInt("fitS", c.fitScreen.ordinal).putInt("fitV", c.fitVideo.ordinal)
            .putBoolean("autoStop2", c.autoStop).putInt("vDelay", c.videoAudioDelayMs).putInt("camAdj", c.camRotAdjust).putInt("srcRot", c.srcRot)
            .putBoolean("silent", c.silentPlayback)
            .putString("sbUri", c.standbyUri).putString("sbName", c.standbyName).putBoolean("sbVideo", c.standbyIsVideo)
            .putString("sbText", c.standbyText).putBoolean("sbMute", c.standbyMuteMic).putBoolean("sbAuto", c.autoStandbyOnImport)
            .putBoolean("warnEcho", c.warnEcho)
            .putFloat("scrZ", c.scrZoom).putFloat("scrPX", c.scrPanX).putFloat("scrPY", c.scrPanY)
            .putFloat("c2x", l.camera2.x).putFloat("c2y", l.camera2.y).putFloat("c2w", l.camera2.w).putFloat("c2h", l.camera2.h)
            .putInt("shape", l.shape.ordinal).putInt("preset", l.presetId).putFloat("ratio", l.ratio)
            .putFloat("border", l.borderFrac).putFloat("radius", l.radiusFrac)
            .putFloat("sx", l.source.x).putFloat("sy", l.source.y).putFloat("sw", l.source.w).putFloat("sh", l.source.h)
            .putFloat("cx", l.camera.x).putFloat("cy", l.camera.y).putFloat("cw", l.camera.w).putFloat("ch", l.camera.h)
            .putBoolean("camTop", l.cameraOnTop)
            .putString("orientMode", c.orientMode).putBoolean("srcViaMic", c.srcViaMic)
            .apply()
    }
}
