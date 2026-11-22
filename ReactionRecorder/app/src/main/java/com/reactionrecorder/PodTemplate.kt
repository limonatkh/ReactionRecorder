package com.reactionrecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * قالب مونتاج: "كيف" بيظهر الفيديو (المونتاج التلقائي بيقرر "مين" و"إمتى").
 * المربع العائم، الشاشة المقسومة، الإطار المضيء، أسماء الأشخاص، الثابتين، الكتابة، والإيقاع.
 * الأماكن بإحداثيات الفيديو (يسار/يمين حقيقي، مش حسب لغة الواجهة).
 */
class PodTemplate {
    var id = "default"
    var name = tx("افتراضي", "Default")
    var builtIn = false
    /** episode = الحلقة الكاملة (بالعرض غالبًا)، reel = ريلز (بالطول) */
    var kind = "episode"
    /** podcast = قوالب البودكاست، vlog = قوالب الفلوق — كل وحدة بمكانها وما بيختلطوا */
    var mode = "podcast"

    // ── الإيقاع والقرارات (بتنسخ للمشروع لما تختار القالب)
    var intensity = "balanced"
    /** اللي بيقاطع أو بيرد رد قصير بمربع عائم (وإلا شاشة مقسومة) */
    /** ممنوع بالبودكاست والفلوق: ما في مربعات عائمة (التعليقات القصيرة = شاشة مقسومة أو بتضل على اللي بيحكي) */
    var pip = false
    var fade = false
    var fadeMs = 300
    /** اتجاه الإخراج: auto = حسب الكاميرات، h، v، both */
    var orient = "auto"
    /** بالشاشة المقسومة: الشخص الأساسي (الأول) بخانة أكبر والباقي ثانويين — لـ ٣ أشخاص وأكثر */
    var multiMain = false
    /** auto = الكاميرا بتلحق اللي بيحكي، grid = الكل ظاهر طول الوقت */
    var layout = "auto"
    /** كسر الملل: كل كم ثانية كلام متصل لشخص واحد بنعمل لقطة قصيرة (0 = مطفي) */
    var breakEvery = 0
    /** auto / listener (المستمع) / wide (الكاميرا العامة) / zoom (تقريب على نفس الشخص) */
    var breakTo = "auto"
    var breakLen = 2.5f
    /** المدة مش ثابتة بالضبط (±٢٥٪) عشان ما يبين آلي */
    var breakVar = true
    /** الردود القصيرة: show (أي كلام بيطلع) / min (بس إذا أطول من minTalk) */
    var shortMode = "show"
    /** mix (منوّع، أغلبه انتقال كامل) / pip / switch */
    var shortStyle = "mix"
    /** إذا المربع/اللقطة رح تختفي وترجع خلال هالمدة (ثواني) بتضل ظاهرة */
    var bridge = 3f
    var firstHold = 5f
    var cutMode = "smart"
    /** شكل خاص دوري: كل [lookEvery] دقيقة، كل كاميرا بتطلع [lookShots] لقطة بشكل خاص (raw = الكاميرا زي ما هي / crop = قصّك)، وبعدين بيرجع العادي. 0 = مطفي */
    var lookEvery = 0f
    var lookShots = 1
    var lookMode = "raw"
    var takeSec = 1.5f
    var overlap = "split"
    var ovSwitch = 2f
    /** لما اثنين يحكوا: بعد قديش ثانية بيصير الخيار (0 = فورًا) */
    var ovAfter = 0f
    var ovMax = 0f
    /** نسبة ظهور اللي بيرد بمربع عائم (والباقي انتقال كامل): 0..1 */
    var pipShare = 0.3f
    var holdAfter = 2.5f
    var minTalk = 1.0f
    /** شكل المربع العائم: auto (بالعرض 16:9 بالحلقة، مربع بالريل) / square / wide / tall */
    var pipAspect = "auto"
    /** الكاميرا العامة بعد صمت: 0 = حسب الإيقاع، -1 = أبدًا، غير هيك ثواني */
    var wideAfter = 0f

    // ── باقي إعدادات المونتاج (كلها بتتطبق على المشروع إلا اللي عدّلته بإيدك فيه)
    var style = "auto"
    var frame = "smart"
    var fill = "other"
    var flipFill = "freeze"
    var zoomMode = "off"
    /** تقريب بطيء على اللي بيحكي (بالكاميرا اللي فيها أكثر من شخص) */
    var pushIn = true
    /** الكادر: false = الكاميرا زي ما صورت (ثابتة) / true = بيلحق الوجوه وبيقرّب على اللي بيحكي */
    var follow = false
    var zoomMax = 1.12f
    var faces = false
    var silence = "off"
    var captions = false
    var highlight = true
    var titleOn = false

    // ── المربع العائم
    /** br, bl, tr, tl, custom */
    var pipPos = "br"
    var pipX = 0.80f
    var pipY = 0.78f
    /** fixed = نفس المكان دايمًا، person = حسب الشخص (الثاني بالجهة المقابلة)، alternate = بالتناوب يمين/يسار */
    var pipPlace = "fixed"
    /** عرض المربع كنسبة من عرض الفيديو (بالعرض؛ بالطول بيكبر تلقائيًا) */
    var pipSize = 0.28f
    /** rect, rounded, circle, alternate (مرة دائري ومرة زوايا دائرية) */
    var pipShape = "rounded"
    var pipRadius = 0.10f
    /** سماكة الإطار كنسبة من ارتفاع الفيديو (0 = بدون) */
    var pipBorder = 0.006f
    var pipBorderColor = 0xFFFFFFFF.toInt()
    var pipShadow = true
    /** cut, fade, slide, zoom */
    var pipAnim = "cut"
    var pipAnimMs = 250
    var pipMargin = 0.04f
    /** المربع العائم مقصوص على وجه الشخص (بتحليل الوجوه) */
    var pipFace = false
    var faceZoom = 1.6f
    /** square أو portrait */
    var faceAspect = "square"
    /** المربع ما بيغطي وجه اللي بيحكي: بينزاح عالجهة الثانية */
    var avoidFace = false

    // ── لما يحكوا مع بعض
    /** split أو boxes */
    var multi = "split"
    /** auto, side, stack */
    var splitDir = "auto"
    var gap = 0.004f
    var gapColor = 0xFF000000.toInt()
    var bgColor = 0xFF1B1D24.toInt()
    var boxRadius = 0.035f

    // ── اللي بيحكي
    var glow = false
    var glowColor = 0xFFFFD60A.toInt()
    var glowWidth = 0.018f

    // ── أسماء الأشخاص: never, first, switch
    var names = "never"
    /** bl, bc, br, tl, tr */
    var namePos = "bl"
    var nameColor = 0xFF3D8BFF.toInt()
    /** حركة الاسم: type = بينكتب حرف حرف وبينمسح، fade = بيظهر ويختفي */
    var nameAnim = "type"
    /** الاسم بخلفية (لون nameColor) أو بدون */
    var nameBg = true
    /** كم ثانية بيضل الاسم ثابت */
    var nameSecs = 2f
    /** خط الاسم ("" = خط القالب) ولون كتابته */
    var nameFont = ""
    var nameFg = 0xFFFFFFFF.toInt()

    // ── الأشخاص الثابتين: tl, tr, bl, br
    var pinPos = "tl"
    var pinSize = 0.20f
    var pinShape = "rounded"

    // ── الكتابة على الفيديو: bottom, middle, top
    var capPos = "bottom"
    var capSize = 1f
    var capColor = 0xFFFFFFFF.toInt()
    /** line = سطر عادي، words = ٢-٣ كلمات كبار والكلمة الحالية ملوّنة، boxed = كلمات على خلفية */
    var capStyle = "line"
    var capHi = 0xFFFFD60A.toInt()

    // ── حركات المونتاج (للريلز خصوصًا)
    /** off / emph = تقريب سريع على الكلمات والجمل المهمة */
    var punch = "off"
    var punchAmt = 1.18f
    /** cut / fade / flash / zoom — الانتقال بين اللقطات */
    var trans = "cut"
    /** شريط تقدّم رفيع فوق */
    var progress = false
    /** box = عنوان بمربع، big = كبير بالنص مع تعتيم، lines = كل سطر بخلفية ملونة (متل البودكاستات العربية) */
    var hookStyle = "box"
    /** مكان العنوان: top / middle / bottom (عموديًا) */
    var hookPos = "middle"
    /** محاذاة العنوان: start (يمين بالعربي) / center */
    var hookAlign = "center"
    /** كم ثانية بيضل العنوان */
    var hookSecs = 3f
    /** مكان حر للعنوان (مركزه 0..1، -1 = حسب hookPos) وحجمه */
    var hookX = -1f
    var hookY = -1f
    var hookSize = 1f
    /** مكان حر للكتابة (مركزها، -1 = تلقائي بعيد عن الوجوه) */
    var capX = -1f
    var capY = -1f
    /** مكان حر لاسم الشخص (مركزه، -1 = حسب namePos) وحجمه */
    var nameX = -1f
    var nameY = -1f
    var nameSize = 1f
    /** دخول وطلوع العنوان: none / fade / slide / wipe */
    var hookIn = "fade"
    var hookOut = "fade"
    var hookColor = 0xFFFFFFFF.toInt()
    var hookBg = 0xFFFFD54F.toInt()
    /** خط الكتابة على الفيديو (VidFonts) */
    var font = "plex"
    /** لما الصورة ما بتعبّي إطارها: blur = نفسها مموّهة، color = لون الخلفية، image = صورة الخلفية */
    var fitBg = "blur"
    /** صورة خلفية (للمربعات وللفراغ) — مسار ملف بالتطبيق */
    var bgImage = ""

    /** عناصر ثابتة: رابط، اسم الحساب، اسم البودكاست… */
    var items: MutableList<TplItem> = ArrayList()

    fun copy(): PodTemplate = fromJson(toJson()).also { it.builtIn = false }

    /** أساس كل ريل: بالطول، القص على الوجه، المربع فوق بالنص، الكتابة شغّالة */
    fun reelBase() {
        orient = "v"; faces = true; captions = true; highlight = true
        pipPos = "tc"; pipShape = "rounded"; pipRadius = 0.2f; pipSize = 0.30f; pipBorder = 0.005f; pipAnim = "zoom"
        multi = "split"; splitDir = "stack"; capPos = "bottom"; capSize = 1.1f
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("bi", builtIn).put("kind", kind).put("mode", mode).put("mm", multiMain)
        .put("lay", layout).put("brk", breakEvery).put("brkTo", breakTo).put("brkLen", breakLen.toDouble()).put("wa", wideAfter.toDouble())
        .put("style", style).put("frame", frame).put("fill", fill).put("flip", flipFill).put("zm", zoomMode).put("push", pushIn).put("fol", follow).put("zx", zoomMax.toDouble())
        .put("faces", faces).put("sil", silence).put("cap", captions).put("hl", highlight).put("title", titleOn)
        .put("brkVar", breakVar).put("shM", shortMode).put("shS", shortStyle).put("hold", holdAfter.toDouble()).put("brg", bridge.toDouble()).put("fH", firstHold.toDouble()).put("cut", cutMode).put("lkE", lookEvery.toDouble()).put("lkN", lookShots).put("lkM", lookMode).put("take", takeSec.toDouble()).put("ovl", overlap).put("ovS", ovSwitch.toDouble()).put("ovA", ovAfter.toDouble()).put("ovM", ovMax.toDouble()).put("pSh", pipShare.toDouble()).put("minT", minTalk.toDouble()).put("pAsp", pipAspect).put("capStyle", capStyle).put("capHi", capHi).put("punch", punch).put("punchAmt", punchAmt.toDouble())
        .put("trans", trans).put("progress", progress).put("hook", hookStyle).put("hPos", hookPos).put("hAlign", hookAlign).put("hSecs", hookSecs.toDouble()).put("hX", hookX.toDouble()).put("hY", hookY.toDouble()).put("hZ", hookSize.toDouble())
        .put("cX", capX.toDouble()).put("cY", capY.toDouble()).put("nX", nameX.toDouble()).put("nY", nameY.toDouble()).put("nZ", nameSize.toDouble()).put("hIn", hookIn).put("hOut", hookOut)
        .put("hC", hookColor).put("hBg", hookBg).put("font", font).put("fitBg", fitBg).put("bgImg", bgImage).put("items", JSONArray().also { a -> for (i in items) a.put(i.toJson()) })
        .put("pFace", pipFace).put("fZoom", faceZoom.toDouble()).put("fAsp", faceAspect).put("avoid", avoidFace)
        .put("int", intensity).put("pip", pip).put("fade", fade).put("fadeMs", fadeMs).put("orient", orient)
        .put("pPos", pipPos).put("pX", pipX.toDouble()).put("pY", pipY.toDouble()).put("pPlace", pipPlace).put("pSize", pipSize.toDouble())
        .put("pShape", pipShape).put("pRad", pipRadius.toDouble()).put("pBord", pipBorder.toDouble()).put("pBordC", pipBorderColor)
        .put("pShadow", pipShadow).put("pAnim", pipAnim).put("pAnimMs", pipAnimMs).put("pMargin", pipMargin.toDouble())
        .put("multi", multi).put("sDir", splitDir).put("gap", gap.toDouble()).put("gapC", gapColor).put("bg", bgColor).put("boxR", boxRadius.toDouble())
        .put("glow", glow).put("glowC", glowColor).put("glowW", glowWidth.toDouble())
        .put("names", names).put("nPos", namePos).put("nC", nameColor).put("nAn", nameAnim).put("nBg", nameBg).put("nSec", nameSecs.toDouble()).put("nF", nameFont).put("nFg", nameFg)
        .put("pinPos", pinPos).put("pinSize", pinSize.toDouble()).put("pinShape", pinShape)
        .put("capPos", capPos).put("capSize", capSize.toDouble()).put("capC", capColor)

    companion object {
        fun fromJson(o: JSONObject): PodTemplate = PodTemplate().apply {
            id = o.optString("id", id); name = o.optString("name", name); builtIn = o.optBoolean("bi", false); kind = o.optString("kind", kind); mode = o.optString("mode", "podcast"); multiMain = o.optBoolean("mm", false)
            layout = o.optString("lay", layout); breakEvery = o.optInt("brk", breakEvery); breakTo = o.optString("brkTo", breakTo)
            breakLen = o.optDouble("brkLen", breakLen.toDouble()).toFloat(); wideAfter = o.optDouble("wa", wideAfter.toDouble()).toFloat()
            style = o.optString("style", style); frame = o.optString("frame", frame); fill = o.optString("fill", fill); flipFill = o.optString("flip", flipFill)
            zoomMode = o.optString("zm", zoomMode); pushIn = o.optBoolean("push", pushIn); follow = o.optBoolean("fol", follow); zoomMax = o.optDouble("zx", zoomMax.toDouble()).toFloat(); faces = o.optBoolean("faces", faces)
            silence = o.optString("sil", silence); captions = o.optBoolean("cap", captions); highlight = o.optBoolean("hl", highlight); titleOn = o.optBoolean("title", titleOn)
            pipFace = o.optBoolean("pFace", pipFace); faceZoom = o.optDouble("fZoom", faceZoom.toDouble()).toFloat()
            faceAspect = o.optString("fAsp", faceAspect); avoidFace = o.optBoolean("avoid", avoidFace)
            shortMode = o.optString("shM", shortMode); shortStyle = o.optString("shS", shortStyle).let { if (it == "auto") "mix" else it }; bridge = o.optDouble("brg", bridge.toDouble()).toFloat(); firstHold = o.optDouble("fH", firstHold.toDouble()).toFloat(); cutMode = o.optString("cut", cutMode); lookEvery = o.optDouble("lkE", lookEvery.toDouble()).toFloat(); lookShots = o.optInt("lkN", lookShots); lookMode = o.optString("lkM", lookMode); takeSec = o.optDouble("take", takeSec.toDouble()).toFloat(); overlap = o.optString("ovl", overlap); ovSwitch = o.optDouble("ovS", ovSwitch.toDouble()).toFloat(); ovAfter = o.optDouble("ovA", ovAfter.toDouble()).toFloat(); ovMax = o.optDouble("ovM", ovMax.toDouble()).toFloat(); pipShare = o.optDouble("pSh", pipShare.toDouble()).toFloat(); holdAfter = o.optDouble("hold", holdAfter.toDouble()).toFloat()
            minTalk = o.optDouble("minT", minTalk.toDouble()).toFloat(); pipAspect = o.optString("pAsp", pipAspect)
            breakVar = o.optBoolean("brkVar", breakVar); capStyle = o.optString("capStyle", capStyle); capHi = o.optInt("capHi", capHi)
            punch = o.optString("punch", punch); punchAmt = o.optDouble("punchAmt", punchAmt.toDouble()).toFloat()
            trans = o.optString("trans", trans); progress = o.optBoolean("progress", progress); hookStyle = o.optString("hook", hookStyle)
            hookPos = o.optString("hPos", hookPos); hookAlign = o.optString("hAlign", hookAlign); hookSecs = o.optDouble("hSecs", hookSecs.toDouble()).toFloat()
            hookX = o.optDouble("hX", -1.0).toFloat(); hookY = o.optDouble("hY", -1.0).toFloat(); hookSize = o.optDouble("hZ", 1.0).toFloat()
            capX = o.optDouble("cX", -1.0).toFloat(); capY = o.optDouble("cY", -1.0).toFloat()
            nameX = o.optDouble("nX", -1.0).toFloat(); nameY = o.optDouble("nY", -1.0).toFloat(); nameSize = o.optDouble("nZ", 1.0).toFloat()
            hookIn = o.optString("hIn", hookIn); hookOut = o.optString("hOut", hookOut); hookColor = o.optInt("hC", hookColor); hookBg = o.optInt("hBg", hookBg)
            font = o.optString("font", font); fitBg = o.optString("fitBg", fitBg); bgImage = o.optString("bgImg", bgImage)
            items = ArrayList()
            o.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { items.add(TplItem.fromJson(it)) } }
            intensity = o.optString("int", intensity); pip = false; fade = o.optBoolean("fade", fade); fadeMs = o.optInt("fadeMs", fadeMs); orient = o.optString("orient", orient)
            pipPos = o.optString("pPos", pipPos); pipX = o.optDouble("pX", pipX.toDouble()).toFloat(); pipY = o.optDouble("pY", pipY.toDouble()).toFloat()
            pipPlace = o.optString("pPlace", pipPlace); pipSize = o.optDouble("pSize", pipSize.toDouble()).toFloat()
            pipShape = o.optString("pShape", pipShape); pipRadius = o.optDouble("pRad", pipRadius.toDouble()).toFloat()
            pipBorder = o.optDouble("pBord", pipBorder.toDouble()).toFloat(); pipBorderColor = o.optInt("pBordC", pipBorderColor)
            pipShadow = o.optBoolean("pShadow", pipShadow); pipAnim = o.optString("pAnim", pipAnim); pipAnimMs = o.optInt("pAnimMs", pipAnimMs)
            pipMargin = o.optDouble("pMargin", pipMargin.toDouble()).toFloat()
            multi = o.optString("multi", multi); splitDir = o.optString("sDir", splitDir); gap = o.optDouble("gap", gap.toDouble()).toFloat()
            gapColor = o.optInt("gapC", gapColor); bgColor = o.optInt("bg", bgColor); boxRadius = o.optDouble("boxR", boxRadius.toDouble()).toFloat()
            glow = o.optBoolean("glow", glow); glowColor = o.optInt("glowC", glowColor); glowWidth = o.optDouble("glowW", glowWidth.toDouble()).toFloat()
            names = o.optString("names", names); namePos = o.optString("nPos", namePos); nameColor = o.optInt("nC", nameColor)
            nameAnim = o.optString("nAn", nameAnim); nameBg = o.optBoolean("nBg", nameBg); nameSecs = o.optDouble("nSec", nameSecs.toDouble()).toFloat()
            nameFont = o.optString("nF", nameFont); nameFg = o.optInt("nFg", nameFg)
            pinPos = o.optString("pinPos", pinPos); pinSize = o.optDouble("pinSize", pinSize.toDouble()).toFloat(); pinShape = o.optString("pinShape", pinShape)
            capPos = o.optString("capPos", capPos); capSize = o.optDouble("capSize", capSize.toDouble()).toFloat(); capColor = o.optInt("capC", capColor)
        }

        /** قوالب جاهزة (ما بتنحذف؛ بتقدر تعمل منها نسخة وتعدّلها) */
        fun builtIns(): List<PodTemplate> = listOf(
            PodTemplate().apply { id = "default"; name = tx("افتراضي", "Default"); builtIn = true },
            PodTemplate().apply {
                id = "bi_circle"; name = tx("دائري بالتناوب", "Alternating circles"); builtIn = true
                pipShape = "alternate"; pipPlace = "alternate"; pipAnim = "zoom"; glow = true; pipBorder = 0.008f; names = "first"
            },
            PodTemplate().apply {
                id = "bi_split"; name = tx("كلاسيكي مقسوم", "Classic split"); builtIn = true
                pip = false; intensity = "calm"; fade = true; gap = 0.008f; gapColor = 0xFF121316.toInt(); names = "switch"; namePos = "bc"
            },
            PodTemplate().apply {
                id = "bi_lively"; name = tx("حيوي مربعات", "Lively boxes"); builtIn = true
                intensity = "lively"; multi = "boxes"; pipPlace = "person"; pipAnim = "slide"; pipShape = "rounded"; pipRadius = 0.2f; glow = true; glowColor = 0xFF3D8BFF.toInt()
            },
            PodTemplate().apply {
                id = "bi_face"; name = tx("مربع على الوجه", "Face box"); builtIn = true
                pipFace = true; avoidFace = true; pipShape = "rounded"; pipRadius = 0.16f; faces = true; breakEvery = 40; breakTo = "auto"; names = "first"
            },
            PodTemplate().apply {
                id = "bi_elegant"; name = tx("بودكاست أنيق (اسم وعنوان)", "Elegant podcast (name & title)"); builtIn = true
                intensity = "calm"; faces = true; pipShape = "rounded"; pipRadius = 0.16f; avoidFace = true; names = "first"
                titleOn = true; hookStyle = "lines"; hookPos = "middle"; hookAlign = "start"; hookSecs = 4f; hookIn = "wipe"; hookOut = "fade"
                hookColor = 0xFF111111.toInt(); hookBg = 0xFFFFD54F.toInt(); font = "tajawal"
                items = TplDefaults.allItems().filter { it.text == "{channel}" || it.text == "{series}" }.toMutableList()
            },
            // ── ريلز (بالطول): المتكلم مقصوص على وجهه، المربع فوق بالنص، الكتابة بالثلث التحتاني بعيد عن أزرار المنصة
            PodTemplate().apply {
                id = "reel_default"; kind = "reel"; name = tx("ريل افتراضي", "Default reel"); builtIn = true
                reelBase(); intensity = "lively"; silence = "balanced"; zoomMode = "auto"; zoomMax = 1.12f
                capStyle = "words"; punch = "emph"; trans = "zoom"; titleOn = true; hookStyle = "big"
            },
            PodTemplate().apply {
                id = "reel_headline"; kind = "reel"; name = tx("عنوان مُبرز (بدون كتابة)", "Headline (no captions)"); builtIn = true
                reelBase(); intensity = "calm"; silence = "light"; zoomMode = "auto"; zoomMax = 1.06f; captions = false
                titleOn = true; hookStyle = "lines"; hookPos = "middle"; hookAlign = "start"; hookSecs = 5f; hookIn = "wipe"; hookOut = "fade"
                hookColor = 0xFF111111.toInt(); hookBg = 0xFFFFD54F.toInt(); font = "tajawal"; trans = "fade"; fade = true
                items = TplDefaults.allItems().filter { it.text == "{channel}" || it.text == "{series}" }.toMutableList()
            },
            PodTemplate().apply {
                id = "reel_calm"; kind = "reel"; name = tx("ريل هادئ", "Calm reel"); builtIn = true
                reelBase(); intensity = "calm"; silence = "light"; zoomMode = "auto"; zoomMax = 1.06f; fade = true; fadeMs = 300
                capStyle = "line"; trans = "fade"; titleOn = true; hookStyle = "box"; pipAnim = "fade"
            },
            PodTemplate().apply {
                id = "reel_fast"; kind = "reel"; name = tx("ريل حماسي (قصّات)", "Hype reel (cuts)"); builtIn = true
                reelBase(); intensity = "lively"; silence = "intense"; zoomMode = "auto"; zoomMax = 1.15f
                breakEvery = 12; breakTo = "zoom"; breakLen = 1.8f; capStyle = "words"; punch = "emph"; punchAmt = 1.25f
                trans = "flash"; progress = true; titleOn = true; hookStyle = "big"; pipAnim = "zoom"
            },
            PodTemplate().apply {
                id = "reel_stack"; kind = "reel"; name = tx("حوار فوق وتحت", "Top & bottom talk"); builtIn = true
                reelBase(); layout = "grid"; splitDir = "stack"; glow = true; gap = 0.006f; silence = "balanced"
                capStyle = "boxed"; capPos = "middle"; names = "first"; namePos = "tl"; titleOn = true; hookStyle = "box"
            },
            PodTemplate().apply {
                id = "reel_clip"; kind = "reel"; name = tx("كليب بودكاست", "Podcast clip"); builtIn = true
                reelBase(); intensity = "balanced"; silence = "balanced"; zoomMode = "auto"; titleOn = true; hookStyle = "big"
                pipFace = true; avoidFace = true; pipShape = "circle"; pipSize = 0.26f; pipAnim = "zoom"; names = "first"; namePos = "tl"
                capStyle = "words"; punch = "emph"; trans = "zoom"
                items = mutableListOf(TplItem().apply { text = "{channel}"; pos = "tc"; whenShow = "always"; size = 0.8f; bg = false })
            },
            PodTemplate().apply {
                id = "reel_debate"; kind = "reel"; name = tx("نقاش حامي", "Heated debate"); builtIn = true
                reelBase(); intensity = "lively"; style = "debate"; silence = "intense"; zoomMode = "auto"; zoomMax = 1.15f
                glow = true; glowColor = 0xFFFF453A.toInt(); multi = "split"; splitDir = "stack"
                capStyle = "words"; capHi = 0xFFFF453A.toInt(); punch = "emph"; trans = "flash"; progress = true
            },
            // ── الفلوق: قالبين بس — بدون مونتاج (كاميرا وحدة)، وثابت (كل الكاميرات مقسومة). ولا مربع عائم.
            PodTemplate().apply {
                id = "vlog_plain"; mode = "vlog"; name = tx("بدون مونتاج", "No editing"); builtIn = true; orient = "v"
                layout = "auto"; intensity = "calm"; names = "never"; zoomMode = "off"; silence = "off"; glow = false
            },
            PodTemplate().apply {
                id = "vlog_split"; mode = "vlog"; name = tx("ثابت — كل الكاميرات", "Fixed — all cameras"); builtIn = true; orient = "v"
                layout = "grid"; multi = "split"; splitDir = "auto"; gap = 0.006f; gapColor = 0xFF0E0F11.toInt(); intensity = "calm"; names = "never"; zoomMode = "off"; silence = "off"; glow = false
            },
            PodTemplate().apply {
                id = "vlog_plain_reel"; mode = "vlog"; kind = "reel"; name = tx("بدون مونتاج", "No editing"); builtIn = true
                reelBase(); layout = "auto"; intensity = "calm"; names = "never"; zoomMode = "off"; silence = "off"; titleOn = false
            },
            PodTemplate().apply {
                id = "vlog_split_reel"; mode = "vlog"; kind = "reel"; name = tx("ثابت — كل الكاميرات", "Fixed — all cameras"); builtIn = true
                reelBase(); layout = "grid"; multi = "split"; splitDir = "auto"; gap = 0.006f; names = "never"; zoomMode = "off"; silence = "off"; titleOn = false
            },
        )

        /** شرح بالأرقام لشو بيصير تلقائيًا بهالإيقاع (من نفس قواعد المونتاج) */
        fun pacingInfo(t: PodTemplate, hasWide: Boolean = true): List<String> {
            if (t.layout == "grid") return listOf(tx("الكل ظاهر طول الوقت بشاشة مقسومة — ما في تبديل كاميرات.", "Everyone on screen all the time in a split — no camera switching."))
            val out = pacingInfo(t.intensity, t.pip, hasWide && t.wideAfter >= 0f, if (t.wideAfter > 0f) t.wideAfter.toDouble() else null).toMutableList()
            fun s(v: Float) = String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")
            val i = out.indexOfFirst { it.startsWith(tx("كلام طويل", "Long cont")) }
            if (t.breakEvery > 0 && i >= 0) {
                val to = when (t.breakTo) {
                    "listener" -> tx("على المستمع", "to the listener")
                    "wide" -> tx("على الكاميرا العامة", "to the wide camera")
                    "zoom" -> tx("تقريب على نفس الشخص", "a punch-in on the same person")
                    else -> tx("بالتناوب: المستمع، العامة، تقريب", "alternating: listener, wide, punch-in")
                }
                out[i] = tx("كسر الملل: إذا حدا حكى أكثر من ${t.breakEvery} ث متواصل، بتصير لقطة قصيرة ${s(t.breakLen)} ث ($to) وبترجع عليه. بتتكرر كل ${t.breakEvery} ث.",
                    "Break monotony: when someone talks over ${t.breakEvery} s straight, a short ${s(t.breakLen)} s shot ($to), then back. Repeats every ${t.breakEvery} s.")
            }
            if (!hasWide) out += tx("ما في كاميرا عامة بهالتصوير — لإضافتها: بشاشة التصوير، قسم الأشخاص، علّم جهاز كـ\"الكاميرا العامة\".", "No wide camera in this shoot — add one in the recording screen, People section, mark a device as \"Wide camera\".")
            if (t.pipFace) out += tx("المربع العائم بيكون مقرّب على وجه الشخص (بيحلل الوجوه تلقائيًا وقت التصدير).", "The floating box is zoomed on the person's face (faces analyzed automatically at export).")
            if (t.avoidFace) out += tx("المربع ما بيغطي وجه اللي بيحكي: إذا كان مكانه فوق الوجه بينتقل للجهة الثانية.", "The box never covers the speaker's face: it moves to the other side if needed.")
            return out
        }

        fun pacingInfo(intensity: String, pip: Boolean, hasWide: Boolean = true, wideAfter: Double? = null): List<String> {
            val r = PodEngine.rules(intensity)
            fun s(v: Double) = String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")
            val out = ArrayList<String>()
            out += tx("الكاميرا بتنتقل على اللي بيحكي لما ياخد الدور فعلًا، وبتنتقل قبل ما يبلّش بـ ${s(r.lead)} ث.", "The camera moves to whoever really takes the turn, ${s(r.lead)} s before they start.")
            out += tx("أقصر لقطة: ${s(r.minShot)} ث — أي لقطة أقصر بتندمج باللي قبلها (إلا المربع العائم).", "Shortest shot: ${s(r.minShot)} s — anything shorter merges into the previous one (except the floating box).")
            out += tx("الردود القصيرة جدًا (آه، صح — أقل من ١٫٢ ث) ما بتغيّر الكاميرا أبدًا.", "Very short replies (uh-huh, right — under 1.2 s) never switch the camera.")
            out += if (pip && r.pipMax > 0) tx("رد قصير لحد ${s(r.pipMax)} ث بيطلع بمربع عائم فوق اللي بيحكي، وإذا كمّل بيصير هو بملء الشاشة.", "A short reply up to ${s(r.pipMax)} s shows in a floating box; if they keep talking they go full screen.")
                else tx("الردود القصيرة ما بتطلع بمربع بهالإيقاع — الكاميرا بتضل على اللي بيحكي.", "Short replies don't get a box at this pace — the camera stays on the speaker.")
            out += if (pip) tx("لما اتنين يحكوا مع بعض أكثر من نص ثانية: اللي كان بيحكي بيضل بملء الشاشة واللي قاطعه بمربع عائم.", "When two talk together for over half a second: the speaker stays full screen and the interrupter gets a floating box.")
                else tx("لما اتنين يحكوا مع بعض ثانية أو أكثر: الشاشة بتنقسم.", "When two talk together for a second or more: the screen splits.")
            out += tx("نقاش سريع: إذا صار ${r.pingCount} تنقّلات أو أكثر خلال ${s(r.pingWin)} ث، بتصير لقطة مشتركة للكل بدل التنطيط.", "Fast exchange: ${r.pingCount}+ switches within ${s(r.pingWin)} s become one shared shot instead of bouncing.")
            if (hasWide) out += tx("الكاميرا العامة (اللي بتجيب الكل): بتطلع بعد صمت ${s(wideAfter ?: r.silenceToWide)} ث، وبتضل لحد ما حدا يحكي.", "The wide camera (everyone): appears after ${s(wideAfter ?: r.silenceToWide)} s of silence and stays until someone speaks.")
            out += tx("كلام طويل متصل: ما في تبديل بمؤقت — الكاميرا بتضل على اللي بيحكي طول ما هو بيحكي.", "Long continuous talk: no timer switching — the camera stays on the speaker as long as they talk.")
            if (r.reactions || intensity == "lively") out += tx("لقطات رد فعل: ١٫٣ ث على اللي علّق، بس بلقطات أطول من ١٠ ث، وبينها ٢٠ ث على الأقل.", "Reaction shots: 1.3 s on whoever reacted, only in shots over 10 s, at least 20 s apart.")
            return out
        }
    }
}

/** حفظ القوالب على الجوال + القالب الافتراضي */
object TemplateStore {
    private const val P = "reaction_tpl"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun user(ctx: Context): List<PodTemplate> = try {
        val a = JSONArray(prefs(ctx).getString("list", "[]"))
        List(a.length()) { PodTemplate.fromJson(a.getJSONObject(it)).also { t -> t.builtIn = false } }
    } catch (_: Throwable) { emptyList() }

    fun all(ctx: Context): List<PodTemplate> = PodTemplate.builtIns() + user(ctx)

    /** قوالب نوع معيّن، والافتراضي أولها */
    /** القسم الحالي (بودكاست/فلوق) — بيحدد القوالب الظاهرة والافتراضي */
    @Volatile var mode = "podcast"

    fun ofKind(ctx: Context, kind: String, m: String = mode): List<PodTemplate> {
        val def = defaultId(ctx, kind, m)
        val l = all(ctx).filter { it.kind == kind && it.mode == m }
        return l.filter { it.id == def } + l.filter { it.id != def }
    }

    fun get(ctx: Context, id: String?): PodTemplate? = all(ctx).firstOrNull { it.id == id }

    private fun defKey(kind: String, m: String) = (if (m == "vlog") "v" else "") + (if (kind == "reel") "def_reel" else "def")
    private fun defFallback(kind: String, m: String) = if (m == "vlog") (if (kind == "reel") "vlog_plain_reel" else "vlog_plain") else (if (kind == "reel") "reel_default" else "default")

    fun defaultId(ctx: Context, kind: String = "episode", m: String = mode): String =
        prefs(ctx).getString(defKey(kind, m), defFallback(kind, m))?.takeIf { id -> get(ctx, id)?.let { it.kind == kind && it.mode == m } == true } ?: defFallback(kind, m)

    fun setDefault(ctx: Context, id: String) {
        val t = get(ctx, id) ?: return
        prefs(ctx).edit().putString(defKey(t.kind, t.mode), id).apply()
    }

    /** القالب المطلوب، وإلا الافتراضي */
    fun resolve(ctx: Context, id: String?, kind: String = "episode", m: String = mode): PodTemplate {
        val t = if (!id.isNullOrBlank()) get(ctx, id) else null
        if (t != null && t.kind == kind) return t
        return get(ctx, defaultId(ctx, kind, m)) ?: PodTemplate.builtIns().first { it.kind == kind && it.mode == m }
    }

    fun kindOf(p: PodProject) = if (p.kind == PodProject.REEL) "reel" else "episode"

    fun save(ctx: Context, t: PodTemplate) {
        if (t.builtIn) return
        val l = user(ctx).toMutableList()
        val i = l.indexOfFirst { it.id == t.id }
        if (i >= 0) l[i] = t else l.add(t)
        write(ctx, l)
    }

    fun delete(ctx: Context, id: String) {
        write(ctx, user(ctx).filter { it.id != id })
        for (m in listOf("podcast", "vlog")) for (k in listOf("episode", "reel"))
            if (prefs(ctx).getString(defKey(k, m), "") == id) prefs(ctx).edit().remove(defKey(k, m)).apply()
    }

    fun newId() = "t" + System.currentTimeMillis().toString(36)

    private fun write(ctx: Context, l: List<PodTemplate>) {
        val a = JSONArray()
        for (t in l) a.put(t.toJson())
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }

    /** مفاتيح الإعدادات اللي بتيجي من القالب (لو عدّلتها بالمشروع بتنحفظ بـ st.ov وما بيغيرها القالب) */
    val KEYS = listOf("intensity", "pip", "fade", "glow", "capSize", "orient", "layout", "brk", "short", "wideAfter", "style", "frame", "fill",
        "flipFill", "zoomMode", "zoomMax", "faces", "silence", "captions", "highlight", "titleOn", "overlap", "cut")
    /** هدول بيغيروا اللقطات (لازم إعادة توليد) */
    private val PACING = setOf("intensity", "pip", "layout", "brk", "short", "wideAfter", "style", "silence", "zoomMode", "zoomMax", "overlap", "cut")

    /** المفاتيح اللي تغيّرت بين نسختين من القالب */
    fun changedKeys(a: PodTemplate, b: PodTemplate): List<String> = KEYS.filter { tplVal(a, it) != tplVal(b, it) }

    /** قيمة كل مفتاح بالقالب وبالمشروع (للمقارنة والنسخ) */
    private fun tplVal(t: PodTemplate, k: String): Any = when (k) {
        "intensity" -> t.intensity; "pip" -> t.pip; "fade" -> (t.fade || t.trans == "fade"); "glow" -> t.glow; "capSize" -> t.capSize; "orient" -> t.orient
        "layout" -> t.layout; "brk" -> "${t.breakEvery}|${t.breakTo}|${t.breakLen}|${t.breakVar}"; "short" -> "${t.shortMode}|${t.shortStyle}|${t.holdAfter}|${t.minTalk}|${t.bridge}|${t.pipShare}|${t.firstHold}"; "wideAfter" -> t.wideAfter; "style" -> t.style
        "frame" -> t.frame; "fill" -> t.fill; "flipFill" -> t.flipFill; "zoomMode" -> t.zoomMode; "zoomMax" -> t.zoomMax; "faces" -> t.faces || t.pipFace || t.avoidFace
        "silence" -> t.silence; "captions" -> t.captions; "highlight" -> t.highlight; "overlap" -> "${t.overlap}|${t.ovSwitch}|${t.ovMax}|${t.ovAfter}"; "cut" -> "${t.cutMode}|${t.takeSec}"; else -> t.titleOn
    }

    private fun stVal(st: ProjSettings, k: String): Any = when (k) {
        "intensity" -> st.intensity; "pip" -> st.pip; "fade" -> st.fade; "glow" -> st.glow; "capSize" -> st.capSize; "orient" -> st.orient
        "layout" -> st.layout; "brk" -> "${st.brk}|${st.brkTo}|${st.brkLen}|${st.brkVar}"; "short" -> "${st.shortMode}|${st.shortStyle}|${st.holdAfter}|${st.minTalk}|${st.bridge}|${st.pipShare}|${st.firstHold}"; "wideAfter" -> st.wideAfter; "style" -> st.style
        "frame" -> st.frame; "fill" -> st.fill; "flipFill" -> st.flipFill; "zoomMode" -> st.zoomMode; "zoomMax" -> st.zoomMax; "faces" -> st.faces
        "silence" -> st.silence; "captions" -> st.captions; "highlight" -> st.highlight; "overlap" -> "${st.overlap}|${st.ovSwitch}|${st.ovMax}|${st.ovAfter}"; "cut" -> "${st.cutMode}|${st.takeSec}"; else -> st.titleOn
    }

    private fun put(t: PodTemplate, st: ProjSettings, k: String) {
        when (k) {
            "intensity" -> st.intensity = t.intensity; "pip" -> st.pip = false; "fade" -> st.fade = t.fade || t.trans == "fade"; "glow" -> st.glow = t.glow
            "capSize" -> st.capSize = t.capSize; "orient" -> if (t.orient != "auto") st.orient = t.orient
            "layout" -> st.layout = t.layout; "brk" -> { st.brk = t.breakEvery; st.brkTo = t.breakTo; st.brkLen = t.breakLen; st.brkVar = t.breakVar }
            "short" -> { st.shortMode = t.shortMode; st.shortStyle = t.shortStyle; st.holdAfter = t.holdAfter; st.minTalk = t.minTalk; st.bridge = t.bridge; st.pipShare = t.pipShare; st.firstHold = t.firstHold }
            "wideAfter" -> st.wideAfter = t.wideAfter; "style" -> st.style = t.style; "frame" -> st.frame = t.frame; "fill" -> st.fill = t.fill
            "flipFill" -> st.flipFill = t.flipFill; "zoomMode" -> st.zoomMode = t.zoomMode; "zoomMax" -> st.zoomMax = t.zoomMax
            "faces" -> st.faces = t.faces || t.pipFace || t.avoidFace; "silence" -> st.silence = t.silence; "captions" -> st.captions = t.captions
            "highlight" -> st.highlight = t.highlight; "titleOn" -> st.titleOn = t.titleOn
            "overlap" -> { st.overlap = t.overlap; st.ovSwitch = t.ovSwitch; st.ovMax = t.ovMax; st.ovAfter = t.ovAfter }
            "cut" -> { st.cutMode = t.cutMode; st.takeSec = t.takeSec }
        }
    }

    /**
     * يخلي المشروع مطابق للقالب (كل شي ما عدّلته بإيدك بهالمشروع).
     * بيرجع true إذا تغيّر إشي بيأثر على اللقطات (لازم إعادة توليد).
     */
    fun sync(t: PodTemplate, st: ProjSettings, episode: Boolean = t.kind == "episode"): Boolean {
        // مشاريع قديمة (قبل هالنظام): القالب كان بينسخ ٦ إعدادات بس — الباقي اللي بيختلف بنعتبره تعديلك
        if (st.sv < 1) {
            val old = setOf("intensity", "pip", "fade", "glow", "capSize", "orient")
            for (k in KEYS) if (k !in old && k !in st.ov && stVal(st, k) != tplVal(t, k)) st.ov.add(k)
            st.sv = 1
        }
        st.tpl = t.id
        var pacing = false
        for (k in KEYS) {
            if (k in st.ov) continue
            if (k == "silence" && episode && t.silence != "off" && t.silence != "gaps") continue
            if (k == "orient" && t.orient == "auto") continue
            if (stVal(st, k) == tplVal(t, k)) continue
            put(t, st, k)
            if (k in PACING) pacing = true
        }
        return pacing
    }

    /** بعد تعديل يدوي بالمشروع: أي إعداد من إعدادات القالب تغيّر بيصير "تعديلك" وما بيرجع يتغيّر مع القالب */
    fun trackOverrides(before: ProjSettings, after: ProjSettings) {
        if (after.applied) { after.applied = false; return }
        if (before.tpl != after.tpl) return
        for (k in KEYS) if (stVal(before, k) != stVal(after, k)) after.ov.add(k)
    }

    /** أسماء التعديلات (للعرض) */
    fun keyName(k: String): String = when (k) {
        "intensity" -> tx("الأسلوب", "Style"); "pip" -> tx("المربع العائم", "Floating box"); "fade" -> tx("الانتقالات", "Transitions")
        "glow" -> tx("الإطار المضيء", "Glow"); "capSize" -> tx("حجم الكتابة", "Caption size"); "orient" -> tx("الاتجاه", "Orientation")
        "layout" -> tx("التخطيط", "Layout"); "brk" -> tx("كسر الملل", "Break monotony"); "short" -> tx("الردود القصيرة", "Short replies"); "wideAfter" -> tx("الكاميرا العامة", "Wide camera")
        "style" -> tx("طبيعة الحوار", "Conversation type"); "frame" -> tx("الكادر", "Framing"); "fill" -> tx("غياب الفيديو", "Missing video")
        "flipFill" -> tx("قلب الكاميرا", "Camera flip"); "zoomMode" -> tx("الزوم", "Zoom"); "zoomMax" -> tx("أقصى زوم", "Max zoom")
        "faces" -> tx("تتبع الوجه", "Face tracking"); "silence" -> tx("قص الصمت", "Silence cuts"); "captions" -> tx("الكتابة", "Captions")
        "highlight" -> tx("تمييز الكلمات", "Highlight"); "overlap" -> tx("لما يحكوا مع بعض", "Talking together"); "cut" -> tx("طريقة المونتاج", "Edit mode"); else -> tx("العنوان", "Title")
    }

    /** العكس: إعدادات المشروع (اللي غيّرتها بإيدك) بتنحط بالقالب */
    fun pull(st: ProjSettings, t: PodTemplate, k: String) {
        when (k) {
            "intensity" -> t.intensity = st.intensity; "pip" -> t.pip = false; "fade" -> { t.fade = st.fade; if (st.fade) t.trans = "fade" else if (t.trans == "fade") t.trans = "cut" }
            "glow" -> t.glow = st.glow; "capSize" -> t.capSize = st.capSize; "orient" -> t.orient = st.orient; "layout" -> t.layout = st.layout
            "brk" -> { t.breakEvery = st.brk; t.breakTo = st.brkTo; t.breakLen = st.brkLen; t.breakVar = st.brkVar }
            "short" -> { t.shortMode = st.shortMode; t.shortStyle = st.shortStyle; t.holdAfter = st.holdAfter; t.minTalk = st.minTalk; t.bridge = st.bridge; t.pipShare = st.pipShare; t.firstHold = st.firstHold }
            "wideAfter" -> t.wideAfter = st.wideAfter; "style" -> t.style = st.style; "frame" -> t.frame = st.frame; "fill" -> t.fill = st.fill
            "flipFill" -> t.flipFill = st.flipFill; "zoomMode" -> t.zoomMode = st.zoomMode; "zoomMax" -> t.zoomMax = st.zoomMax; "faces" -> t.faces = st.faces
            "silence" -> t.silence = st.silence; "captions" -> t.captions = st.captions; "highlight" -> t.highlight = st.highlight; "titleOn" -> t.titleOn = st.titleOn
            "overlap" -> { t.overlap = st.overlap; t.ovSwitch = st.ovSwitch; t.ovMax = st.ovMax; t.ovAfter = st.ovAfter }
            "cut" -> { t.cutMode = st.cutMode; t.takeSec = st.takeSec }
        }
    }

    /** يطبّق القالب على مشروع من جديد (بيلغي تعديلاتك عالإعدادات اللي بتيجي من القالب) */
    fun applyTo(t: PodTemplate, st: ProjSettings, vertical: Boolean? = null) {
        st.ov.clear()
        st.sv = 1
        sync(t, st)
        st.applied = true
    }
}

/** عنصر ثابت بالفيديو (نص أو رابط)؛ {link} {handle} {channel} {guest} بتتبدل من نصوصك المحفوظة */
/**
 * حركة الكتابة كأنها من كيبورد: الحروف بتنكتب وحدة وحدة (بسرعة كتابة حقيقية) ووراها مؤشر |،
 * فآخر حرف بيبين لحاله لحد ما ينكتب اللي بعده ويتصل فيه (بالعربي)، وبالآخر بتنمسح بنفس الطريقة.
 */
object TypeAnim {
    const val PER_CHAR = 0.13
    const val PER_DEL = 0.05
    fun tIn(len: Int) = kotlin.math.min(3.0, len * PER_CHAR)
    fun tOut(len: Int) = kotlin.math.min(1.2, len * PER_DEL)
    fun total(len: Int, hold: Float, anim: String): Double = if (anim == "type") tIn(len) + hold + tOut(len) else hold + 0.8

    /** [النص الظاهر (مع المؤشر)، الشفافية] بلحظة since من بداية الظهور، ومدة الثبات hold؛ null = خلص */
    fun at(text: String, since: Double, hold: Float, anim: String): Pair<String, Float>? {
        if (since < 0) return null
        if (anim != "type") {
            val tot = hold + 0.8
            if (since > tot) return null
            val a = if (anim == "none") 1f else (kotlin.math.min(since / 0.25, (tot - since) / 0.3)).toFloat().coerceIn(0f, 1f)
            return text to a
        }
        return inWindow(text, since, tIn(text.length) + hold + tOut(text.length))
    }

    /** الكتابة بفترة طولها dur: بتنكتب بالأول وبتنمسح بالآخر */
    fun inWindow(text: String, since: Double, dur: Double): Pair<String, Float>? {
        val len = text.length
        if (since < 0 || since > dur || len == 0) return null
        val ti = tIn(len); val to = tOut(len)
        val n = when {
            since < ti -> kotlin.math.ceil(since / PER_CHAR).toInt().coerceIn(1, len)
            since > dur - to -> kotlin.math.ceil((dur - since) / PER_DEL).toInt().coerceIn(0, len)
            else -> len
        }
        if (n <= 0) return null
        val cursor = since < ti || since > dur - to
        return (text.take(n) + if (cursor) "|" else "") to 1f
    }
}

class TplItem {
    /** text = نص، image = صورة (شعار) */
    var kind = "text"
    /** مسار صورة الشعار (لـ image) */
    var image = ""
    var text = "{link}"
    /** tl tc tr bl bc br */
    var pos = "br"
    /** always / start / end / every */
    var whenShow = "always"
    /** كم ثانية بيضل (للبداية/النهاية/الدوري) */
    var secs = 5f
    /** للدوري: كل كم دقيقة */
    var everyMin = 3f
    var size = 1f
    var color = 0xFFFFFFFF.toInt()
    var bg = true
    /** مكان حر (سحب بإيدك): 0..1 من عرض/ارتفاع الفيديو، -1 = حسب pos */
    var x = -1f
    var y = -1f
    /** الخط ("" = خط القالب) */
    var font = ""
    /** طريقة الظهور: fade / type (كيبورد) / slide / none */
    var anim = "fade"

    /** فترة الظهور الحالية [من، لـ] بلحظة t، أو null */
    fun window(t: Double, total: Double): DoubleArray? = when (whenShow) {
        "start" -> if (t < secs) doubleArrayOf(0.0, secs.toDouble()) else null
        "end" -> if (t > total - secs) doubleArrayOf(total - secs, total) else null
        "every" -> {
            val per = kotlin.math.max(20.0, everyMin * 60.0)
            val k = t % per
            val start = kotlin.math.min(8.0, per / 4)
            if (k >= start && k < start + secs) doubleArrayOf(t - k + start, t - k + start + secs) else null
        }
        else -> doubleArrayOf(0.0, kotlin.math.max(total, 1.0))
    }

    fun toJson(): JSONObject = JSONObject().put("x", x.toDouble()).put("y", y.toDouble()).put("f", font).put("an", anim).put("t", text).put("p", pos).put("w", whenShow).put("s", secs.toDouble()).put("e", everyMin.toDouble())
        .put("z", size.toDouble()).put("c", color).put("b", bg).put("k", kind).put("img", image)

    /** هل بيظهر بلحظة t من الفيديو الناتج (طوله total)؛ بيرجع شفافية 0..1 */
    fun alphaAt(t: Double, total: Double): Float {
        fun ramp(x: Double, a: Double, b: Double): Float = (kotlin.math.min((x - a) / 0.3, (b - x) / 0.3)).toFloat().coerceIn(0f, 1f)
        return when (whenShow) {
            "start" -> if (t < secs) ramp(t, -1.0, secs.toDouble()) else 0f
            "end" -> if (t > total - secs) ramp(t, total - secs, total + 1.0) else 0f
            "every" -> {
                val per = kotlin.math.max(20.0, everyMin * 60.0)
                val k = t % per
                val start = kotlin.math.min(8.0, per / 4)
                if (k >= start && k < start + secs) ramp(k, start, start + secs) else 0f
            }
            else -> 1f
        }
    }

    companion object {
        fun fromJson(o: JSONObject) = TplItem().apply {
            text = o.optString("t", text); pos = o.optString("p", pos); whenShow = o.optString("w", whenShow)
            secs = o.optDouble("s", secs.toDouble()).toFloat(); everyMin = o.optDouble("e", everyMin.toDouble()).toFloat()
            size = o.optDouble("z", 1.0).toFloat(); color = o.optInt("c", color); bg = o.optBoolean("b", true)
            kind = o.optString("k", "text"); image = o.optString("img", "")
            x = o.optDouble("x", -1.0).toFloat(); y = o.optDouble("y", -1.0).toFloat()
            font = o.optString("f", ""); anim = o.optString("an", "fade")
        }
    }
}

/** النصوص الثابتة كلها مرة وحدة، بأماكن افتراضية متل البودكاستات المعروفة (القناة فوق يمين، السلسلة فوق يسار…) */
object TplDefaults {
    /** أماكن متل البودكاستات المعروفة: القناة صغيرة فوق بالزاوية، اسم الضيف كبير بخط حلو جنبه ووصفه تحته */
    fun allItems(): List<TplItem> = listOf(
        TplItem().apply { text = "{channel}"; pos = "tl"; whenShow = "always"; bg = false; size = 1.0f; anim = "fade" },
        TplItem().apply { text = "{series}"; pos = "tr"; whenShow = "always"; bg = false; size = 1.2f; anim = "fade" },
        TplItem().apply { text = "{guest}"; x = 0.70f; y = 0.36f; whenShow = "start"; secs = 8f; bg = false; size = 2.4f; font = "ruqaa"; anim = "type" },
        TplItem().apply { text = "{role}"; x = 0.70f; y = 0.47f; whenShow = "start"; secs = 8f; bg = false; size = 0.9f; anim = "fade" },
        TplItem().apply { text = "{handle}"; pos = "bc"; whenShow = "every"; everyMin = 3f; secs = 5f; bg = true; size = 0.85f },
        TplItem().apply { text = "{link}"; pos = "bc"; whenShow = "end"; secs = 6f; bg = true; size = 0.9f },
    )
}

/** نصوصك الثابتة (بتنحفظ مرة وبتستعملها كل القوالب) */
object Brand {
    private fun p(ctx: Context) = ctx.getSharedPreferences("brand", Context.MODE_PRIVATE)
    val KEYS = listOf("channel", "series", "handle", "link", "guest", "role")
    fun get(ctx: Context, k: String): String = p(ctx).getString(k, "") ?: ""
    fun set(ctx: Context, k: String, v: String) { p(ctx).edit().putString(k, v.trim()).apply() }
    fun label(k: String) = when (k) {
        "link" -> tx("الرابط", "Link"); "handle" -> tx("اسم الحساب (@)", "Handle (@)"); "channel" -> tx("اسم البودكاست/القناة", "Podcast/channel name"); "series" -> tx("اسم السلسلة/البرنامج", "Series/show name"); "role" -> tx("وصف الضيف (تحت اسمه)", "Guest's title (under the name)"); else -> tx("اسم الضيف", "Guest name")
    }
    fun values(ctx: Context): Map<String, String> = KEYS.associateWith { get(ctx, it) }
    /** بيبدّل {link} وغيره؛ لو القيمة فاضية بيطلع نص فاضي */
    fun fill(text: String, v: Map<String, String>): String {
        var s = text
        for ((k, x) in v) s = s.replace("{$k}", x)
        return s.replace(Regex("\\{[a-z]+\\}"), "").trim()
    }
}

/** قالب من وصف بدون خدمة ذكاء: بيلقط الكلمات الأساسية بس */
object TemplateIdeas {
    fun local(desc: String, kind: String): PodTemplate {
        val d = desc.lowercase()
        fun has(vararg w: String) = w.any { d.contains(it) }
        val t = PodTemplate()
        t.kind = kind
        if (kind == "reel") t.reelBase()
        t.name = desc.trim().take(28).ifBlank { tx("قالبي", "My template") }
        if (has("هادي", "هادئ", "هدوء", "calm")) { t.intensity = "calm"; t.trans = "fade"; t.fade = true; t.punch = "off" }
        if (has("حماس", "سريع", "حيوي", "hype", "fast", "energetic")) { t.intensity = "lively"; t.punch = "emph"; t.trans = "flash"; t.progress = true; if (kind == "reel") t.silence = "intense" }
        if (has("نقاش", "debate")) { t.style = "debate"; t.intensity = "lively" }
        if (has("دائر", "دائري", "circle")) t.pipShape = "circle"
        if (has("مقسوم", "split")) t.pip = false
        if (has("فوق وتحت", "فوق بعض", "stack")) { t.splitDir = "stack"; if (has("طول الوقت", "دايم", "always")) t.layout = "grid" }
        if (has("جنب بعض", "side by side")) t.splitDir = "side"
        if (has("الكل ظاهر", "everyone")) t.layout = "grid"
        if (has("فوق بالنص", "top centre", "top center")) t.pipPos = "tc"
        else if (has("فوق يمين")) t.pipPos = "tr" else if (has("فوق يسار")) t.pipPos = "tl"
        else if (has("تحت يسار")) t.pipPos = "bl" else if (has("تحت يمين")) t.pipPos = "br"
        if (has("وجه", "face")) { t.faces = true; if (has("مربع على الوجه", "مقرب", "قريب")) t.pipFace = true }
        if (has("ما يغطي", "ما يغطّي", "avoid")) t.avoidFace = true
        if (has("أسماء", "اسماء", "names")) t.names = "first"
        if (has("إطار مضيء", "اطار مضيء", "glow")) t.glow = true
        if (has("كتابة", "ترجمة", "captions")) t.captions = true
        if (has("كلمة كلمة", "كلمات كبار", "كبيرة")) t.capStyle = "words"
        if (has("صفرا", "أصفر", "اصفر", "yellow")) t.capHi = 0xFFFFD60A.toInt()
        if (has("أحمر", "احمر", "red")) { t.capHi = 0xFFFF453A.toInt(); t.glowColor = 0xFFFF453A.toInt() }
        if (has("أزرق", "ازرق", "blue")) { t.capHi = 0xFF3D8BFF.toInt(); t.glowColor = 0xFF3D8BFF.toInt() }
        if (has("ومضة", "فلاش", "flash")) t.trans = "flash"
        if (has("تقريب", "زوم", "zoom")) { t.punch = "emph"; t.zoomMode = "auto" }
        if (has("شريط", "progress")) t.progress = true
        if (has("عنوان", "title", "hook")) { t.titleOn = true; t.hookStyle = "big" }
        if (has("كسر الملل", "تقليب", "يقلب")) { t.breakEvery = Regex("(\\d+)").find(d)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(10, 300) ?: 45 }
        if (has("رابط", "link")) t.items.add(TplItem().apply { text = "{link}"; pos = "bc"; whenShow = if (has("آخر", "اخر", "النهاية")) "end" else "always"; secs = 5f })
        if (has("حساب", "handle", "@")) t.items.add(TplItem().apply { text = "{handle}"; pos = "tr"; whenShow = "always"; bg = false })
        if (has("اسم البودكاست", "اسم القناة", "channel")) t.items.add(TplItem().apply { text = "{channel}"; pos = "tc"; whenShow = "always"; bg = false; size = 0.8f })
        return t
    }
}
