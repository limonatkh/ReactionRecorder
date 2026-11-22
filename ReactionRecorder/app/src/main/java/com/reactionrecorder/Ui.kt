package com.reactionrecorder

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** لغة الواجهة (عربي / English) — تُحفظ على الجوال وتُطبَّق على كل الشاشات */
object L {
    @Volatile var lang = "ar"
        private set
    private var loaded = false

    val ar: Boolean get() = lang == "ar"

    fun init(ctx: Context) {
        if (loaded) return
        lang = ctx.getSharedPreferences("reaction_ui", Context.MODE_PRIVATE).getString("lang", "ar") ?: "ar"
        loaded = true
    }

    fun set(ctx: Context, l: String) {
        lang = if (l == "en") "en" else "ar"
        loaded = true
        ctx.getSharedPreferences("reaction_ui", Context.MODE_PRIVATE).edit().putString("lang", lang).apply()
    }

    /** للاختبارات */
    fun setForTest(l: String) { lang = l; loaded = true }
}

/** نص بحسب لغة الواجهة */
fun tx(ar: String, en: String): String = if (L.ar) ar else en

/**
 * أساس كل الشاشات: يطبّق اللغة واتجاه الكتابة (يمين لليسار للعربي)، وانتقالات ناعمة بين الشاشات.
 */
open class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(base: Context) {
        L.init(base)
        val loc = Locale(L.lang)
        val c = Configuration(base.resources.configuration)
        c.setLocale(loc)
        c.setLayoutDirection(loc)
        super.attachBaseContext(base.createConfigurationContext(c))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        window.decorView.layoutDirection = if (L.ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    /** شاشات مسموحة بدون تفعيل (الانضمام كمايك/كاميرا، والتفعيل نفسه) */
    open val allowedWithoutLicense = false

    override fun onResume() {
        super.onResume()
        if (this is LockActivity || BuildConfig.UX_TEST) return
        // صاحب التطبيق: بصمة كل فترة وبعد إعادة تشغيل الجوال
        if (OwnerLock.locked(this)) {
            super.startActivity(android.content.Intent(this, LockActivity::class.java))
            return
        }
        // نسخة غير مفعّلة: الانضمام فقط
        if (!allowedWithoutLicense && !License.active(this)) {
            super.startActivity(android.content.Intent(this, HomeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }

    override fun startActivity(intent: android.content.Intent?) {
        super.startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.enter_fwd, R.anim.exit_fwd)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.enter_back, R.anim.exit_back)
    }
}

/** خط التطبيق (IBM Plex Sans Arabic) — للأماكن اللي بتحدد الخط بالكود */
object Fonts {
    var regular: Typeface = Typeface.DEFAULT
        private set
    var semi: Typeface = Typeface.DEFAULT_BOLD
        private set
    var bold: Typeface = Typeface.DEFAULT_BOLD
        private set
    private var done = false
    fun init(ctx: Context) {
        if (done) return
        try {
            regular = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.plex_regular) ?: regular
            semi = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.plex_semibold) ?: semi
            bold = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.plex_bold) ?: bold
        } catch (_: Throwable) {}
        done = true
    }
}

/** أيقونات خطية موحّدة (مسارات SVG على شبكة 24) — بدل الإيموجي */
object Ic {
    const val PLUS = "M12 5v14M5 12h14"
    const val FLASH = "M13 2L4 14h7l-1 8 9-12h-7z"
    const val PHONE = "M9 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM11 18h2"
    const val SUN = "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
    const val QR = "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h3v3h-3zM21 14v7h-7"
    const val FOLDER = "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
    const val REC = "M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16z"
    const val SLIDERS = "M4 7h10M18 7h2M4 17h4M12 17h8M14 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0M8 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0"
    const val MIC = "M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0zM5 11a7 7 0 0 0 14 0M12 18v3"
    const val CAM = "M3 8a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM16 10l5-3v10l-5-3"
    const val SCREEN = "M2 6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2zM13 10h6v5h-6z"
    const val STAR = "M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"
    const val X = "M5 5l14 14M19 5L5 19"
    const val LOCK = "M5 13a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2zM8 11V8a4 4 0 0 1 8 0v3"
    const val UNLOCK = "M5 13a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2zM8 11V8a4 4 0 0 1 7.5-2"
    const val FLIP = "M20 12a8 8 0 0 1-14 5M4 12a8 8 0 0 1 14-5M18 3v4h-4M6 21v-4h4"
    const val FRAME = "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"
    const val SHARE = "M12 3v12M7 8l5-5 5 5M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5"
    const val KEY = "M14 10a4 4 0 1 0-1.2 2.8L21 21M17 17l2-2"
    const val GEAR = "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM19 12l2-1-1-3-2 .3-1.4-1.4.3-2-3-1-1 2h-1.8l-1-2-3 1 .3 2L5 7.3 3 7 2 10l2 1v2l-2 1 1 3 2-.3 1.4 1.4-.3 2 3 1 1-2h1.8l1 2 3-1-.3-2 1.4-1.4 2 .3 1-3-2-1z"
    const val CHEV = "M15 6l-6 6 6 6"
    const val BACK = "M9 6l6 6-6 6"
    const val UNDO = "M15 7l4 4-4 4M19 11H9a5 5 0 0 0 0 10h2"
    const val REDO = "M9 7l-4 4 4 4M5 11h10a5 5 0 0 1 0 10h-2"
    const val PLAY = "M8 5l11 7-11 7z"
    const val WIFI = "M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19h.01"
    const val BATT = "M3 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM21 10v4"
    const val EXPORT = "M12 15V3M7 8l5-5 5 5M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4"
    const val REELS = "M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM10 9l5 3-5 3z"
    const val CHECK = "M5 12l5 5 9-10"
    const val ALERT = "M12 9v4M12 17h.01M10.3 3.9L2 18a2 2 0 0 0 1.7 3h16.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"
    const val SEARCH = "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4"
    const val EYE = "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"
    const val TRASH = "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"
    const val DOWNLOAD = "M12 3v12M7 10l5 5 5-5M5 19h14"
    const val MOON = "M20 14A8 8 0 1 1 10 4a6 6 0 0 0 10 10z"
    const val LINK = "M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1"
    const val LIST = "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"
    const val PEN = "M4 20l4-1L19 8l-3-3L5 16zM14 7l3 3"
    const val HL = "M9 11l-6 6v3h9l3-3M22 12l-4.6 4.6a2 2 0 0 1-2.8 0l-5.2-5.2a2 2 0 0 1 0-2.8L14 4"
    const val ERASER = "M7 21l-4.3-4.3a2.4 2.4 0 0 1 0-3.4l9.6-9.6a2.4 2.4 0 0 1 3.4 0l5.6 5.6a2.4 2.4 0 0 1 0 3.4L13 21zM22 21H7M5 11l9 9"
    const val LASER = "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM12 2v3M12 19v3M2 12h3M19 12h3"
    const val SHAPES = "M3 13h8v8H3zM17 3a4 4 0 1 0 0 8a4 4 0 1 0 0-8z"
    const val TEXT = "M4 7V4h16v3M9 20h6M12 4v16"
    const val PAUSE = "M8 5v14M16 5v14"
    const val MIC_OFF = "M9 6a3 3 0 0 1 6 0v5M5 11a7 7 0 0 0 11 5.7M12 18v3M3 3l18 18"
    const val CAM_OFF = "M3 8a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM16 10l5-3v10l-5-3M2 2l20 20"
    const val FILE = "M6 3h8l4 4v14H6zM14 3v4h4"
    const val BOARD = "M3 4h18v12H3zM8 20l4-4 4 4"
    const val LINE = "M5 19L19 5"
    const val ARROW = "M5 19L19 5M10 5h9v9"
    const val RECT = "M4 6h16v12H4z"
    const val OVAL = "M3 12a9 7 0 1 0 18 0a9 7 0 1 0-18 0z"
    const val FWD = "M13 6l6 6-6 6M5 6l6 6-6 6"
    const val RWD = "M11 6l-6 6 6 6M19 6l-6 6 6 6"
}

/** رسم أيقونة خطية بلون وحجم */
class IconDrawable(ctx: Context, path: String, private val color: Int, sizeDp: Int, private val fill: Boolean = false) : android.graphics.drawable.Drawable() {
    private val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
    private val p = try { androidx.core.graphics.PathParser.createPathFromPathData(path) } catch (_: Throwable) { android.graphics.Path() }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@IconDrawable.color
        style = if (fill) android.graphics.Paint.Style.FILL_AND_STROKE else android.graphics.Paint.Style.STROKE
        strokeWidth = 2f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        c.drawPath(p, paint)
        c.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** ألوان وعناصر موحدة لكل الشاشات — طابع استوديو فحمي هادئ */
object UiKit {
    // «مساحة عمل احترافية داكنة»: خلفية فحمية، سطوح مسطحة، أصفر للإجراء الأساسي فقط
    const val BG = "#0E0F11"
    const val CARD = "#17181C"
    const val CARD2 = "#22242A"
    const val LINE = "#2A2C32"
    const val TEXT = "#F2F2F3"
    const val SUB = "#9A9CA3"
    /** التسجيل والإيقاف فقط */
    const val RED = "#FF453A"
    const val GREEN = "#32D74B"
    const val ORANGE = "#FF9F0A"
    /** الإجراء الأساسي والاختيار (أصفر) — الكلام عليه غامق */
    const val ACCENT = "#FFD21F"
    const val ON_ACCENT = "#111111"
    const val ACCENT_BG = "#3A2E12"
    /** أسماء قديمة = اللون الأساسي الجديد */
    const val BLUE = ACCENT
    const val BLUE_BG = ACCENT_BG
    const val TINT_GREEN = "#1F3324"
    const val TINT_ORANGE = "#3A2412"
    const val TINT_RED = "#3A1A18"
    /** ألوان مسارات الأشخاص بالتايملاين */
    val LANES = arrayOf("#3D7BE0", "#C9773A", "#8873E8", "#2E9E8F")

    /** لون الكلام المناسب فوق خلفية (غامق فوق الفاتح) */
    fun onColor(bgHex: String): Int {
        val c = Color.parseColor(bgHex)
        val l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
        return if (l > 0.6) Color.parseColor(ON_ACCENT) else Color.WHITE
    }

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun bg(color: String, radiusDp: Int, ctx: Context, stroke: String? = null) = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(Color.parseColor(color))
        if (stroke != null) setStroke(dp(ctx, 1), Color.parseColor(stroke))
    }

    fun icon(ctx: Context, path: String, color: String = TEXT, sizeDp: Int = 22, fill: Boolean = false) =
        IconDrawable(ctx, path, Color.parseColor(color), sizeDp, fill)

    /** يشيل الإيموجي الزخرفي من بداية العناوين (الأيقونات صارت خطية موحّدة) */
    fun clean(s: String): String = s.replace(Regex("^[\\p{So}\\p{Sk}\\p{Cs}\\uFE0F\\u200D\\u2190-\\u21FF\\u2600-\\u27BF\\s]+"), "")

    /** صفحة قابلة للتمرير بعمود رئيسي؛ تعيد العمود */
    fun page(a: Activity, title: String? = null, sub: String? = null, back: Boolean = true): LinearLayout {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 28)) }
        val sv = ScrollView(a).apply { setBackgroundColor(Color.parseColor(BG)); isFillViewport = true; addView(col) }
        a.setContentView(sv)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sv) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }
        if (title != null) col.addView(header(a, title, sub, back))
        return col
    }

    fun header(a: Activity, title: String, sub: String? = null, back: Boolean = true): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(a, 4), 0, dp(a, 8)) }
        val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        if (back) row.addView(iconButton(a, Ic.BACK.takeIf { L.ar } ?: Ic.CHEV, tx("رجوع", "Back"), bgColor = null) { a.finish() })
        row.addView(TextView(a).apply { text = clean(title); textSize = 20f; setTextColor(Color.parseColor(TEXT)); typeface = Fonts.bold; setPaddingRelative(if (back) dp(a, 4) else 0, 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
        if (!sub.isNullOrBlank()) box.addView(text(a, sub, 13.5f, SUB).apply { setPadding(0, dp(a, 4), 0, 0) })
        return box
    }

    /** زر أيقونة دائري 48dp */
    fun iconButton(ctx: Context, path: String, desc: String, color: String = TEXT, bgColor: String? = CARD, run: () -> Unit): View =
        android.widget.ImageButton(ctx).apply {
            setImageDrawable(icon(ctx, path, color, 22))
            contentDescription = desc
            background = if (bgColor != null) ripple(bg(bgColor, 24, ctx)) else android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), null, null)
            minimumWidth = dp(ctx, 48); minimumHeight = dp(ctx, 48)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48))
            setOnClickListener { run() }
        }

    fun text(ctx: Context, s: String, size: Float = 15f, color: String = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(Color.parseColor(color))
        if (bold) typeface = Fonts.semi
        setLineSpacing(0f, 1.2f)
    }

    /** بطاقة بعنوان (اختياري) */
    fun card(ctx: Context, title: String? = null, parent: LinearLayout? = null): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            background = bg(CARD, 14, ctx, LINE)
        }
        if (title != null) c.addView(text(ctx, clean(title), 16.5f, TEXT, true).apply { setPadding(0, 0, 0, dp(ctx, 8)) })
        parent?.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 12) })
        return c
    }

    /**
     * قسم قابل للطي: العنوان دائمًا ظاهر (مع ملخص صغير)، والمحتوى يظهر عند الضغط.
     * يعيد صندوق المحتوى. حالة الفتح تُحفظ باسم key.
     */
    fun section(ctx: Context, parent: LinearLayout, key: String, title: String, summary: String = "", openByDefault: Boolean = false): LinearLayout {
        val prefs = ctx.getSharedPreferences("reaction_ui", Context.MODE_PRIVATE)
        var open = prefs.getBoolean("sec_$key", openByDefault)
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = bg(CARD, 14, ctx, LINE) }
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(ctx, 56); setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 10), dp(ctx, 12)) }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(ctx, clean(title), 16f, TEXT, true))
        val sumTv = text(ctx, summary, 13f, SUB).apply { visibility = if (summary.isBlank()) View.GONE else View.VISIBLE }
        titles.addView(sumTv)
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val arrow = ImageViewCompatHelper.rot(ctx, open)
        head.addView(arrow)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), 0, dp(ctx, 14), dp(ctx, 14))
            visibility = if (open) View.VISIBLE else View.GONE
            tag = sumTv
        }
        head.setOnClickListener {
            open = !open
            prefs.edit().putBoolean("sec_$key", open).apply()
            arrow.animate().rotation(if (open) -90f else 90f).setDuration(160).start()
            body.visibility = if (open) View.VISIBLE else View.GONE
            if (open) body.startAnimation(AlphaAnimation(0f, 1f).apply { duration = 180; interpolator = DecelerateInterpolator() })
        }
        c.addView(head)
        c.addView(body)
        parent.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 12) })
        return body
    }

    /** يغيّر ملخص قسم أنشأه section() */
    fun setSummary(body: LinearLayout, s: String) {
        (body.tag as? TextView)?.let { it.text = s; it.visibility = if (s.isBlank()) View.GONE else View.VISIBLE }
    }

    /** زر كبسولة (48dp)؛ sel = مختار (أزرق) */
    fun chip(ctx: Context, label: String, sel: Boolean = false, run: () -> Unit): Button = ChipButton(ctx).apply {
        // إيموجي زخرفي ببداية الكلام بينشال (الأيقونات صارت خطية)، إلا إذا الزر كله إيموجي
        text = clean(label).ifBlank { label }; isAllCaps = false; textSize = 14f; typeface = Fonts.regular
        minWidth = 0; minimumWidth = 0; minHeight = dp(ctx, 48); minimumHeight = dp(ctx, 48)
        setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        // سطر واحد دايمًا: إذا الكلام طويل بيصغر الخط شوي بدل ما ينزل لسطرين ويخرّب الصف
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        stateListAnimator = null
        style(this, sel)
        setOnClickListener { run() }
    }

    /** يحط الأيقونة جنب الكلام بالنص (مش بطرف الزر) */
    fun withIcon(b: TextView, path: String, color: String = TEXT, sizeDp: Int = 20): TextView {
        val ctx = b.context
        val d = icon(ctx, path, color, sizeDp).apply { setBounds(0, 0, dp(ctx, sizeDp), dp(ctx, sizeDp)) }
        val label = b.text.toString()
        val sp = android.text.SpannableString("\u00A0\u00A0$label")
        sp.setSpan(android.text.style.ImageSpan(d, if (android.os.Build.VERSION.SDK_INT >= 29) android.text.style.DynamicDrawableSpan.ALIGN_CENTER else android.text.style.DynamicDrawableSpan.ALIGN_BASELINE), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.text = sp
        return b
    }

    /** زر كبسولة بأيقونة جنب الكلام */
    fun chipIcon(ctx: Context, path: String, label: String, sel: Boolean = false, color: String = TEXT, run: () -> Unit): Button =
        chip(ctx, label, sel, run).also { withIcon(it, path, color, 20) }

    /** عدد الأشخاص بالعربي الصحيح */
    fun people(n: Int): String = if (!L.ar) (if (n == 1) "1 person" else "$n people") else when (n) { 1 -> "شخص واحد"; 2 -> "شخصين"; in 3..10 -> "$n أشخاص"; else -> "$n شخص" }
    fun projects(n: Int): String = if (!L.ar) (if (n == 1) "1 project" else "$n projects") else when (n) { 1 -> "مشروع واحد"; 2 -> "مشروعين"; in 3..10 -> "$n مشاريع"; else -> "$n مشروع" }

    fun style(b: Button, sel: Boolean) {
        b.setTextColor(Color.parseColor(if (sel) ON_ACCENT else TEXT))
        b.typeface = if (sel) Fonts.semi else Fonts.regular
        b.background = ripple(if (sel) bg(ACCENT, 12, b.context) else bg(CARD2, 12, b.context), dark = sel)
    }

    /** موجة ضغط فوق أي خلفية — عشان تبيّن إنه الزر انضغط */
    fun ripple(content: android.graphics.drawable.Drawable, dark: Boolean = false): android.graphics.drawable.Drawable =
        android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (dark) 0x33000000 else 0x30FFFFFF), content, null)

    /** زر الفعل الرئيسي (عريض) — أزرق افتراضيًا، والأحمر للتسجيل */
    fun primary(ctx: Context, label: String, color: String = BLUE, run: () -> Unit): Button = Button(ctx).apply {
        text = label; isAllCaps = false; textSize = 17f; typeface = Fonts.bold
        minHeight = dp(ctx, 54); minimumHeight = dp(ctx, 54)
        setTextColor(onColor(color)); stateListAnimator = null
        background = ripple(bg(color, 14, ctx), dark = onColor(color) != Color.WHITE)
        setOnClickListener { run() }
    }

    /** تأكيد قصير إنه الأمر تنفّذ */
    fun done(ctx: Context, msg: String) = android.widget.Toast.makeText(ctx, "✓ $msg", android.widget.Toast.LENGTH_SHORT).show()

    fun row(ctx: Context, vararg v: View): LinearLayout = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        for ((i, x) in v.withIndex()) addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (i < v.size - 1) marginEnd = dp(ctx, 8)
            topMargin = dp(ctx, 4); bottomMargin = dp(ctx, 4)
        })
    }

    fun full(ctx: Context, v: View, topDp: Int = 8, hDp: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (hDp > 0) dp(ctx, hDp) else hDp).apply { topMargin = dp(ctx, topDp) }

    /** شريط مستوى رفيع (أخضر) */
    fun level(ctx: Context): ProgressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(GREEN))
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(CARD2))
    }

    /** بطاقة كبيرة: أيقونة + عنوان + وصف */
    fun bigCard(ctx: Context, icon: String, title: String, desc: String, accent: String, run: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            background = ripple(bg(CARD, 14, ctx, LINE))
            isClickable = true
            setOnClickListener { run() }
            val iv = android.widget.ImageView(ctx).apply {
                val path = if (icon.startsWith("M")) icon else Ic.REC
                setImageDrawable(icon(ctx, path, TEXT, 26)); scaleType = android.widget.ImageView.ScaleType.CENTER
                background = bg(accent, 14, ctx)
            }
            addView(iv, LinearLayout.LayoutParams(dp(ctx, 52), dp(ctx, 52)))
            val tcol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(ctx, 14), 0, dp(ctx, 4), 0) }
            tcol.addView(text(ctx, clean(title), 17f, TEXT, true))
            tcol.addView(text(ctx, desc, 13.5f, SUB).apply { setPadding(0, dp(ctx, 3), 0, 0) })
            addView(tcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(android.widget.ImageView(ctx).apply { setImageDrawable(icon(ctx, if (L.ar) Ic.CHEV else Ic.BACK, SUB, 20)) })
        }

    /** شارة حالة ملونة */
    fun badge(ctx: Context, s: String, color: String): TextView = TextView(ctx).apply {
        text = s; textSize = 13f; setTextColor(Color.parseColor(color)); typeface = Fonts.semi
        setPadding(dp(ctx, 10), dp(ctx, 3), dp(ctx, 10), dp(ctx, 3))
        val tint = when (color) { GREEN -> TINT_GREEN; ORANGE -> TINT_ORANGE; RED -> TINT_RED; BLUE -> BLUE_BG; else -> CARD2 }
        background = bg(tint, 12, ctx)
    }

    /** لوحة سفلية منظمة للخيارات الثانوية (بدل ما تغطي الشاشة) */
    fun sheet(a: Activity, title: String, build: (LinearLayout, android.app.Dialog) -> Unit): android.app.Dialog {
        val d = android.app.Dialog(a)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 18), dp(a, 10), dp(a, 18), dp(a, 22)) }
        col.addView(View(a).apply { background = bg("#3A3C42", 2, a) }, LinearLayout.LayoutParams(dp(a, 40), dp(a, 4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(a, 10) })
        col.addView(text(a, clean(title), 19f, TEXT, true).apply { setPadding(0, 0, 0, dp(a, 6)) })
        build(col, d)
        val sv = ScrollView(a).apply { background = GradientDrawable().apply { setColor(Color.parseColor(CARD)); cornerRadii = floatArrayOf(dp(a, 20).toFloat(), dp(a, 20).toFloat(), dp(a, 20).toFloat(), dp(a, 20).toFloat(), 0f, 0f, 0f, 0f) }; addView(col) }
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(sv)
        d.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.decorView.layoutDirection = if (L.ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        d.show()
        // تطلع من تحت بنعومة (220ms)
        sv.translationY = dp(a, 420).toFloat(); sv.alpha = 0.6f
        sv.animate().translationY(0f).alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        return d
    }

    /** صف قائمة: أيقونة + عنوان + وصف + سهم (للإعدادات والمجموعات) */
    fun listRow(ctx: Context, path: String?, title: String, sub: String = "", run: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ctx, 60)
        setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8))
        isClickable = true; setOnClickListener { run() }
        if (path != null) addView(android.widget.ImageView(ctx).apply { setImageDrawable(icon(ctx, path, SUB, 22)) }, LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)))
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(ctx, 10), 0, dp(ctx, 6), 0) }
        t.addView(text(ctx, clean(title), 16f, TEXT, true))
        if (sub.isNotBlank()) t.addView(text(ctx, sub, 13f, SUB))
        addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(android.widget.ImageView(ctx).apply { setImageDrawable(icon(ctx, if (L.ar) Ic.CHEV else Ic.BACK, SUB, 18)) })
    }
}

/** سهم القسم القابل للطي */
object ImageViewCompatHelper {
    fun rot(ctx: Context, open: Boolean) = android.widget.ImageView(ctx).apply {
        setImageDrawable(UiKit.icon(ctx, Ic.BACK, UiKit.SUB, 20))
        rotation = if (open) -90f else 90f
    }
}

/**
 * زر بضغطة مطوّلة: بيتعبّى تدريجيًا وأنت ضاغط، ولما يكمل بينفّذ (بدون نافذة تأكيد).
 * إذا رفعت إصبعك قبل ما يكمل، ما بصير إشي.
 */
class HoldButton(ctx: Context, label: String, private val holdMs: Long = 1000, private val fillColor: String = UiKit.RED, private val onDone: () -> Unit) : Button(ctx) {
    private var start = 0L
    private var progress = 0f
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    var onShortTap: (() -> Unit)? = null
    private val tickR = object : Runnable {
        override fun run() {
            if (start == 0L) return
            progress = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / holdMs).coerceIn(0f, 1f)
            invalidate()
            if (progress >= 1f) {
                start = 0L; progress = 0f
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onDone()
                invalidate()
            } else postDelayed(this, 16)
        }
    }

    init {
        text = label; isAllCaps = false; textSize = 17f; typeface = Fonts.bold
        setTextColor(Color.WHITE); stateListAnimator = null
        background = UiKit.bg("#4A1C1A", 16, ctx)
        minHeight = UiKit.dp(ctx, 60)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { start = android.os.SystemClock.uptimeMillis(); post(tickR); return true }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                val was = start != 0L && progress < 0.15f
                start = 0L; progress = 0f; removeCallbacks(tickR); invalidate()
                if (was && e.actionMasked == android.view.MotionEvent.ACTION_UP) onShortTap?.invoke()
                return true
            }
        }
        return true
    }

    override fun onDraw(c: android.graphics.Canvas) {
        if (progress > 0f) {
            paint.color = Color.parseColor(fillColor)
            val r = UiKit.dp(context, 16).toFloat()
            c.save()
            val w = width * progress
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) c.clipRect(width - w, 0f, width.toFloat(), height.toFloat()) else c.clipRect(0f, 0f, w, height.toFloat())
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            c.restore()
        }
        super.onDraw(c)
    }
}

/** جسر وضع الشرح: سطح المصدر في خدمة التسجيل، اللي بيرسم عليه BoardActivity */
object BoardBridge {
    @Volatile var surface: android.view.Surface? = null
        private set
    @Volatile var w = 1280
        private set
    @Volatile var h = 720
        private set

    fun attach(s: android.view.Surface, w: Int, h: Int) { this.w = w; this.h = h; surface = s }
    fun detach() { surface = null }
}

/** إعدادات كاميرتك بوضع الشرح (محفوظة، وبتتغير كمان وقت التسجيل) */
object BoardCam {
    private fun p(ctx: Context) = ctx.getSharedPreferences("board_cam", Context.MODE_PRIVATE)
    fun visible(ctx: Context) = p(ctx).getBoolean("vis", true)
    fun front(ctx: Context) = p(ctx).getBoolean("front", true)
    /** circle / rounded / rect */
    fun shape(ctx: Context) = p(ctx).getString("shape", "circle") ?: "circle"
    /** 0 صغير، 1 وسط، 2 كبير */
    fun size(ctx: Context) = p(ctx).getInt("size", 1)
    /** مركز المربع (نسبة من الفيديو) — سالب = الزاوية الافتراضية تحت يمين */
    fun cx(ctx: Context) = p(ctx).getFloat("cx", -1f)
    fun cy(ctx: Context) = p(ctx).getFloat("cy", -1f)
    /** سطوع (تعويض التعريض) */
    fun exposure(ctx: Context) = p(ctx).getInt("exp", 0)
    fun set(ctx: Context, f: android.content.SharedPreferences.Editor.() -> Unit) { p(ctx).edit().apply(f).apply() }

    /** حجم المربع كنسبة من الفيديو [w,h] */
    fun boxSize(ctx: Context, portrait: Boolean): FloatArray {
        val aspect = if (portrait) 9f / 16f else 16f / 9f
        val w = (if (portrait) floatArrayOf(0.26f, 0.36f, 0.48f) else floatArrayOf(0.15f, 0.21f, 0.29f))[size(ctx).coerceIn(0, 2)]
        val h = if (shape(ctx) == "circle") w * aspect else w * aspect * 4f / 3f
        return floatArrayOf(w, h.coerceAtMost(0.9f))
    }

    /** مكان المربع [x,y,w,h] */
    fun rect(ctx: Context, portrait: Boolean): FloatArray {
        val (w, h) = boxSize(ctx, portrait).let { it[0] to it[1] }
        var cx = cx(ctx); var cy = cy(ctx)
        if (cx < 0f || cy < 0f) { cx = 1f - w / 2f - 0.035f; cy = 1f - h / 2f - (if (portrait) 0.04f else 0.06f) }
        val x = (cx - w / 2f).coerceIn(0f, 1f - w); val y = (cy - h / 2f).coerceIn(0f, 1f - h)
        return floatArrayOf(x, y, w, h)
    }

    fun layout(ctx: Context, portrait: Boolean): LayoutConfig {
        val r = rect(ctx, portrait)
        val sh = when (shape(ctx)) { "rect" -> CamShape.RECT; "rounded" -> CamShape.ROUNDED; else -> CamShape.CIRCLE }
        return LayoutConfig(NRect(0f, 0f, 1f, 1f), NRect(r[0], r[1], r[2], r[3]), cameraOnTop = true, shape = sh, borderFrac = 0.004f, radiusFrac = 0.18f, presetId = 1)
    }
}

/** نص بيتحدّث كل شوي: إذا نفس الكلام ما بيعيد الرسم (الشاشة بتهدى، وأدوات الفحص بتقدر تقراها) */
open class QuietText(ctx: Context) : TextView(ctx) {
    override fun setText(text: CharSequence?, type: BufferType?) {
        val cur: CharSequence? = getText()
        if (cur != null && text != null && text !is android.text.Spanned && cur !is android.text.Spanned && cur.toString() == text.toString()) return
        super.setText(text, type)
    }
}

/** كبسولة نص: بتشيل الإيموجي الزخرفي من أول النص (الأيقونات صارت خطية) وما بتتحاذى على سطر الكتابة */
class CleanChip(ctx: Context) : QuietText(ctx) {
    override fun getBaseline(): Int = -1
    override fun setText(text: CharSequence?, type: BufferType?) {
        val t = if (text != null && text !is android.text.Spanned) { val s = text.toString(); UiKit.clean(s).ifBlank { s } } else text
        super.setText(t, type)
    }
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.96f else 1f).scaleY(if (pressed) 0.96f else 1f).setDuration(90).start()
    }
}

/** زر الكبسولات: بدون محاذاة على سطر الكتابة (عشان كل الأزرار بالصف يكونوا على نفس المستوى) + ضغطة بتصغّره شوي */
class ChipButton(ctx: Context) : Button(ctx) {
    override fun getBaseline(): Int = -1
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.96f else 1f).scaleY(if (pressed) 0.96f else 1f).setDuration(90).start()
    }
}

/**
 * صفحة بعمود واحد بالطول، وبالعرض بتنقسم عمودين: الجزء الأول (الفيديو/المعاينة) على جهة،
 * والباقي (القوائم) على الجهة الثانية بيتمرر لحاله — عشان الفيديو يضل مبين.
 */
class TwoPane(private val a: android.app.Activity, private val slot: LinearLayout, private val sv: ScrollView, private val col: LinearLayout, private val splitAt: () -> Int) {
    private val colB = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(UiKit.dp(a, 12), UiKit.dp(a, 8), UiKit.dp(a, 12), UiKit.dp(a, 20)) }
    private val svB = ScrollView(a).apply { isFillViewport = true; addView(colB) }
    private val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    var land = false
        private set

    fun apply(landscape: Boolean) {
        if (landscape == land) return
        land = landscape
        if (landscape) {
            val idx = slot.indexOfChild(sv).coerceAtLeast(0)
            val k = splitAt().coerceIn(0, col.childCount)
            val moved = (k until col.childCount).map { col.getChildAt(it) }
            for (v in moved) { col.removeView(v); colB.addView(v) }
            slot.removeView(sv)
            row.addView(sv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
            row.addView(View(a).apply { setBackgroundColor(Color.parseColor("#2A2C32")) }, LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(svB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            slot.addView(row, idx, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val idx = slot.indexOfChild(row).coerceAtLeast(0)
            val moved = (0 until colB.childCount).map { colB.getChildAt(it) }
            colB.removeAllViews()
            for (v in moved) col.addView(v)
            row.removeAllViews()
            slot.removeView(row)
            slot.addView(sv, idx, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }
}
