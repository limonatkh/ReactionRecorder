package com.reactionrecorder

import android.content.Context
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * ربط منطق المونتاج (PodEngine) بملفات الجلسة: تحميل التحليل والنص، توليد/إعادة توليد مشروع مع حماية المثبّت،
 * وبناء مهمة التصدير أو المعاينة بنفس القرارات.
 */
object PodStudio {
    class Data(val s: SessionInfo, val setup: PodSetup, val an: PodAnalysis, val tr: Transcript?, val sem: Semantic?, val markers: List<PMarker>) {
        var camsPortrait = false
        /** الأشخاص اللي كاميرتهم جايبة أكثر من شخص */
        var crowd: Set<Int> = emptySet()
        /** الأشخاص اللي كاميرتهم بالعرض (بعد التدوير) */
        var camLand: Set<Int> = emptySet()
        /** فلوق: اختيارات الكاميرا وقت التصوير */
        var live: List<Pair<Double, Int>> = emptyList()
        /** فترات الإيقاف المؤقت وقت تصوير الفلوق (ثوانٍ بزمن الجلسة) */
        var pauses: List<Pair<Double, Double>> = emptyList()
        /** الفلوق: أول ثانية فيها صورة من كاميرتك (قبلها الكاميرا كانت لسا بتفتح = أسود) */
        var vFirst = 0.0
        /** وصلت ملفات جديدة منذ آخر فتح → أُعيد التحليل والمونتاج (غير المثبّت) */
        var inputsChanged = false
        /** ملفات لم تصل بعد (للعرض) */
        var missing: List<String> = emptyList()
        /** أشخاص محفوظين من تصوير قديم بس ولا جهاز إلهم شارك بهالتصوير → كأنهم مش موجودين */
        var absent: Set<Int> = emptySet()
        fun hasCam(i: Int) = i !in absent && setup.people.getOrNull(i)?.camDev?.isNotEmpty() == true
        val duration get() = an.duration
        val names get() = setup.people.map { it.name }
        private var turnsCache: List<PodEngine.Turn>? = null
        val turns: List<PodEngine.Turn> get() = turnsCache ?: PodEngine.markBackchannels(PodEngine.turns(an, setup.people.size)).also { turnsCache = it }
        /** بعد تعديل نشاط الكلام (الأصوات القصيرة) */
        fun resetTurns() { turnsCache = null; regionsCache = null }
        /** طبيعة الحوار: من التحليل الدلالي إن وُجد (أدق)، وإلا من التوقيت محليًا */
        /** أجزاء الحوار: بتنحسب من جديد لما الأدوار تتغيّر (تصحيح صوت) */
        private var regionsCache: List<PodEngine.Region>? = null
        val regions: List<PodEngine.Region> get() = regionsCache ?: run {
            val ai = sem?.regions
            (if (!ai.isNullOrEmpty()) ai else PodEngine.regions(turns, an.duration)).also { regionsCache = it }
        }
    }

    /** القالب الافتراضي (للمشاريع الجديدة) */
    @Volatile var defaultTpl: PodTemplate? = null
    @Volatile var defaultReelTpl: PodTemplate? = null

    /** قالب المشروع (حسب نوعه) */
    fun tplOf(ctx: Context, p: PodProject): PodTemplate = TemplateStore.resolve(ctx, p.st.tpl, TemplateStore.kindOf(p))

    /**
     * المونتاج التلقائي: منهج واحد (PodPlan). «الكل ظاهر طول الوقت» (ريل بكاميرات بالعرض فوق وتحت) = لقطة مشتركة وحدة.
     */
    fun planShots(d: Data, st: ProjSettings, a: Double, b: Double, hasWide: Boolean): MutableList<PodShot> {
        val camOf = PodPlan.camOf(d.setup) { d.hasCam(it) }
        if (st.layout == "grid") {
            val who = camOf.indices.filter { camOf[it] >= 0 }.distinctBy { camOf[it] }
            if (who.size >= 2) return mutableListOf(PodShot(a, b, PodShot.MULTI, who[0], who.toIntArray()))
        }
        return PodPlan.plan(d.an, camOf, hasWide, PodPlan.Cfg.of(st), a, b)
    }

    const val PLAN_VER = 2

    /** إعدادات المونتاج اللي بتيجي من القالب بس (ما في إلها تعديل جوّا المشروع) */
    private val PLAN_KEYS = setOf("intensity", "overlap", "pip", "brk", "short", "cut", "layout", "zoomMode", "zoomMax", "faces", "wideAfter", "style", "fade")

    /** يطابق إعدادات المشروع مع قالبه؛ true = لازم إعادة توليد اللقطات */
    fun syncTpl(ctx: Context, p: PodProject): Boolean {
        // المونتاج بمكان واحد (القالب): أي تعديل قديم جوّا المشروع على هالإعدادات بينلغى
        p.st.ov.removeAll(PLAN_KEYS)
        val t = tplOf(ctx, p)
        val vOrient = p.st.orient
        val r = TemplateStore.sync(t, p.st, p.kind != PodProject.REEL)
        if (p.kind == PodProject.REEL && t.orient == "auto") p.st.orient = vOrient
        // مشروع لقطاته من المحرّك القديم → بنعيد اللقطات التلقائية بالمحرّك الجديد (اليدوية بتضل)
        if (p.planVer < PLAN_VER) { p.planVer = PLAN_VER; return true }
        return r
    }

    /** نتيجة آخر مزامنة بالصوت (للعرض في الواجهة) */
    @Volatile var lastSync: List<PodSync.SyncResult> = emptyList()

    /** يحمّل كل ما يلزم؛ يحلل الصوت إن لم يكن محللًا (ويحفظ النتيجة فقط إذا اكتمل) */
    fun load(ctx: Context, sid: String, progress: (Float) -> Unit, cancelled: () -> Boolean): Data? {
        val s = SessionStore.load(ctx, sid) ?: return null
        val setup = PodcastAnalyzer.setupOf(s)
        // كاميرات عامة من تسجيلات قديمة (مش بهالجلسة) ما بتنحسب
        setup.wides.retainAll { s.devices[it]?.started == true }
        var ss = s
        defaultFlipFill = ctx.getSharedPreferences("reaction_ui", Context.MODE_PRIVATE).getString("flipFill", "freeze") ?: "freeze"
        TemplateStore.mode = if (setup.vlog) "vlog" else "podcast"
        val tm = TemplateStore.mode
        // الفلوق: القالب اللي اخترته بشاشة التصوير (وإلا حسب عدد الكاميرات: وحدة = بدون مونتاج، أكثر = ثابت)
        val vId = if (!setup.vlog) null else when (setup.vlogLayout) {
            "plain" -> "vlog_plain"; "grid" -> "vlog_split"
            "tpl" -> if (setup.people.count { it.camDev.isNotEmpty() } >= 2) "vlog_split" else null
            else -> null
        }
        defaultTpl = try { TemplateStore.resolve(ctx, vId, "episode", tm) } catch (_: Throwable) { null }
        defaultReelTpl = try { TemplateStore.resolve(ctx, vId?.let { it + "_reel" }, "reel", tm) } catch (_: Throwable) { null }
        // أجهزة بلا قياس ساعة (انضمت متأخرة / استيراد يدوي): نضبطها بالصوت قبل التحليل
        val unsynced = s.devices.values.any { it.started && it.samples.isEmpty() && File(SessionStore.devDir(ctx, sid, it.id), "audio.wav").exists() }
        if (unsynced) {
            val r = PodSync.syncBySound(ctx, sid, emptySet(), { f -> progress(f * 0.2f) }, cancelled)
            if (cancelled()) return null
            if (r.any { it.ok }) {
                try { File(SessionStore.dir(ctx, sid), "pod_analysis.json").delete() } catch (_: Throwable) {}
                ss = SessionStore.load(ctx, sid) ?: s
            }
            lastSync = r
        }
        // بصمة الملفات الواصلة: إذا وصل ملف جديد (صاحبك رفع بعد ما فتحت المونتاج) نعيد التحليل والمونتاج
        val fpFile = File(SessionStore.dir(ctx, sid), "pod_inputs.txt")
        val fp = inputsOf(ctx, ss)
        val oldFp = try { if (fpFile.exists()) fpFile.readText() else null } catch (_: Throwable) { null }
        val anFile = File(SessionStore.dir(ctx, sid), "pod_analysis.json")
        var changed = false
        if (oldFp != fp && anFile.exists()) { try { anFile.delete() } catch (_: Throwable) {}; changed = true }
        // مين أي جهاز (الأشخاص والكاميرات العامة): لو غيّرته بعد التسجيل بينعاد التحليل والمونتاج
        val rolesFile = File(SessionStore.dir(ctx, sid), "pod_roles.txt")
        val roles = setup.people.joinToString(",") { "${it.camDev}/${it.micDev}" } + "#" + setup.wides.joinToString(",")
        val oldRoles = try { if (rolesFile.exists()) rolesFile.readText() else null } catch (_: Throwable) { null }
        if (oldRoles != null && oldRoles != roles && anFile.exists()) { try { anFile.delete() } catch (_: Throwable) {}; changed = true }
        try { rolesFile.writeText(roles) } catch (_: Throwable) {}
        // تحليل قديم (قبل تمييز الأصوات): بينعاد، واللقطات غير المثبّتة بتتولد من جديد
        if (anFile.exists() && PodcastAnalyzer.loadAnalysis(ctx, sid) == null) changed = true
        val an = PodcastAnalyzer.loadAnalysis(ctx, sid) ?: run {
            val a = PodcastAnalyzer.analyze(ctx, ss, setup, progress, cancelled)
            if (cancelled()) return null
            PodcastAnalyzer.saveAnalysis(ctx, sid, a)
            a
        }
        try { fpFile.writeText(fp) } catch (_: Throwable) {}
        val trn = PodAI.loadTranscript(ctx, sid)
        // تعديلاتك على الأصوات القصيرة (اعرضه/تجاهله، فترات "بعيد عنه"، الضحك) والتفريغ
        try { PodVoice.apply(ctx, ss, an, trn) } catch (_: Throwable) {}
        val d = Data(ss, setup, an, trn, PodAI.loadSemantic(ctx, sid), ProjectStore.markers(ctx, ss)).also {
            it.camsPortrait = portraitCams(ctx, ss); it.live = PodRec.liveList(ctx, ss); it.pauses = PodRec.pauseList(ctx, ss); if (setup.vlog) it.vFirst = videoFirstSec(ctx, ss, setup.people.firstOrNull()?.camDev ?: ""); it.absent = absentOf(ctx, ss, setup); it.missing = missingOf(ctx, ss, setup)
            it.camLand = setup.people.indices.filter { i -> camLandscape(ctx, ss, setup, i) }.toSet()
            it.crowd = crowdOf(ctx, ss, setup)
        }
        if (changed) d.inputsChanged = true
        // كل مشروع: يتبع قالبه (لو عدّلت القالب بعدين، بيتطبق هون — إلا اللي عدّلته بإيدك بالمشروع)
        val dir = ProjectStore.dir(ctx, sid)
        for (f in dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: emptyList()) {
            val p = ProjectStore.load(ctx, sid, f.name.removeSuffix(".json")) ?: continue
            val before = p.st.toJson().toString()
            val pacing = try { syncTpl(ctx, p) } catch (_: Throwable) { false }
            if (changed && setup.vlog) p.st.liveSwitch = p.st.liveSwitch && d.live.isNotEmpty()
            if (changed || pacing) try { regenerate(d, p) } catch (_: Throwable) {}
            if (changed || pacing || p.st.toJson().toString() != before) try { ProjectStore.save(ctx, sid, p) } catch (_: Throwable) {}
        }
        return d
    }

    /** حجم ملفات الجهاز الواصلة (للعرض): "فيديو 120MB · صوت 30MB" */
    fun devSizes(ctx: Context, sid: String, dev: String): String {
        val dd = SessionStore.devDir(ctx, sid, dev)
        fun mb(f: File) = if (f.exists()) f.length() / 1_048_576.0 else -1.0
        fun fmt(v: Double) = if (v >= 1024) String.format(java.util.Locale.US, "%.1fGB", v / 1024) else String.format(java.util.Locale.US, "%.0fMB", kotlin.math.max(v, 0.1))
        val v = mb(File(dd, "video.mp4")); val a = mb(File(dd, "audio.wav"))
        val parts = ArrayList<String>()
        if (v >= 0) parts += tx("فيديو ", "video ") + fmt(v)
        if (a >= 0) parts += tx("صوت ", "audio ") + fmt(a)
        return if (parts.isEmpty()) tx("ما في ملفات", "no files") else parts.joinToString(" · ")
    }

    private fun devFiles(ctx: Context, s: SessionInfo, dev: String): Pair<Boolean, Boolean> {
        if (dev.isEmpty()) return false to false
        val dd = SessionStore.devDir(ctx, s.id, dev)
        val meta = File(dd, "meta.json").exists()
        return (meta && File(dd, "audio.wav").exists()) to (meta && File(dd, "video.mp4").exists())
    }

    private fun inputsOf(ctx: Context, s: SessionInfo): String =
        s.devices.keys.sorted().joinToString("|") { id ->
            val (a, v) = devFiles(ctx, s, id)
            // حجم الملف ووقته كمان: ملف انبدل بنفس الاسم = تحليل جديد
            val dir = SessionStore.devDir(ctx, s.id, id)
            val sig = listOf("audio.wav", "video.mp4").joinToString(":") { n -> File(dir, n).let { f -> if (f.exists()) "${f.length()}@${f.lastModified() / 1000}" else "-" } }
            "$id:$a:$v:$sig"
        }

    /** شخص ولا جهاز من أجهزته (كاميرا/مايك) كان بهالتصوير، ولا وصل منه إشي — محفوظ من تصوير قبل */
    fun absentOf(ctx: Context, s: SessionInfo, setup: PodSetup): Set<Int> {
        fun took(dev: String) = dev.isNotEmpty() && (s.devices.containsKey(dev) || SessionStore.devDir(ctx, s.id, dev).listFiles()?.isNotEmpty() == true)
        val out = setup.people.indices.filter { i -> val p = setup.people[i]; !took(p.camDev) && !took(p.micDev) }.toSet()
        return if (out.size >= setup.people.size) emptySet() else out
    }

    private fun missingOf(ctx: Context, s: SessionInfo, setup: PodSetup): List<String> {
        val out = ArrayList<String>()
        val gone = absentOf(ctx, s, setup)
        for ((i, p) in setup.people.withIndex()) {
            if (i in gone) continue
            if (p.camDev.isNotEmpty() && !devFiles(ctx, s, p.camDev).second) out += tx("فيديو ${p.name} لسا ما وصل", "${p.name}'s video hasn't arrived yet")
            val mic = PodcastAnalyzer.micOf(ctx, s, p)
            if (mic.isNotEmpty() && !devFiles(ctx, s, mic).first) out += tx("صوت ${p.name} لسا ما وصل", "${p.name}'s audio hasn't arrived yet")
        }
        return out
    }

    /**
     * إعادة توليد العناصر غير المثبّتة (كل المشروع أو المدى [ra,rb] فقط):
     * اللقطات، قص الصمت التلقائي (للريلز)، وحركات الزوم التلقائية.
     */
    fun regenerate(d: Data, p: PodProject, ra: Double? = null, rb: Double? = null) {
        val a = p.a
        val b = p.end(d.duration)
        val r0 = ra ?: a
        val r1 = rb ?: b
        val eff = effective(d, p)
        val fresh = vlogShots(d, p, a, b) ?: planShots(d, eff, a, b, d.setup.wides.isNotEmpty())
        p.shots = if (p.shots.isEmpty()) fresh else PodEngine.regenerate(p.shots, fresh, a, b, ra, rb)
        for (x in p.shots) x.unfloat()
        if (p.kind == PodProject.REEL) {
            p.cuts.removeAll { it.kind == PCut.SILENCE && !it.locked && it.b > r0 && it.a < r1 }
            p.cuts.addAll(PodEngine.silenceCuts(d.an, d.turns, p.st.silence, r0, r1))
            p.cuts.sortBy { it.a }
        }
        val keepZ = p.zooms.filter { it.locked || !it.auto || it.b <= r0 || it.a >= r1 }
        // كاميرا اللي بيحكي: ولا زوم تلقائي (بس اللي حطيته بإيدك)
        val fz = (if (p.st.zoomMode == "off") emptyList() else PodEngine.autoZooms(p.shots, d.turns, d.regions, p.st, d.sem?.keys, r0, r1))
            .filter { z -> z.a < r1 && z.b > r0 && keepZ.none { k -> z.a < k.b && z.b > k.a } }
        val merged = (keepZ.map { it.copy() } + fz).sortedBy { it.a }
        p.zooms.clear()
        p.zooms.addAll(merged)
    }

    /**
     * الإعدادات الفعلية للمونتاج حسب الكاميرات:
     *  • كاميرا جايبة أكثر من شخص → بلا مربع عائم (المربع رح يغطي حدا) — انتقال كامل أو جنب بعض
     *  • ريل وكاميرات بالعرض → كاميرا فوق وكاميرا تحت طول الوقت (إلا إذا طفيتها)
     */
    fun effective(d: Data, p: PodProject): ProjSettings {
        val st = p.st.copy()
        st.ov = p.st.ov
        val cams = d.setup.people.indices.filter { d.hasCam(it) }
        val vertical = p.st.orient == "v"
        if (vertical && p.st.stackReel && cams.size >= 2 && cams.count { it in d.camLand } * 2 >= cams.size) st.layout = "grid"
        return st
    }

    /** فلوق: لقطات حسب شكل الفيديو المختار (أو null = المونتاج التلقائي على المتكلم) */
    fun vlogShots(d: Data, p: PodProject, a: Double, b: Double): MutableList<PodShot>? {
        if (!d.setup.vlog) return null
        val cams = d.setup.people.indices.filter { d.hasCam(it) }
        if (cams.isEmpty()) return null
        return when (d.setup.vlogLayout) {
            // فلوق جديد: القالب بيقرر — «ثابت» = الكل مقسوم، «بدون مونتاج» = كاميرتك (أو اللي اخترتها وقت التصوير)
            "tpl", "plain" -> if (p.st.layout == "grid") { if (cams.size >= 2) mutableListOf(PodShot(a, b, PodShot.MULTI, cams[0], cams.toIntArray())) else mutableListOf(PodShot(a, b, PodShot.FULL, cams[0], IntArray(0))) }
                else if (d.live.isNotEmpty()) PodEngine.shotsFromLive(d.live, a, b, { d.hasCam(it) }, cams[0])
                else mutableListOf(PodShot(a, b, PodShot.FULL, cams[0], IntArray(0)))
            "grid" -> if (p.st.layout != "auto" || cams.size >= 2) mutableListOf(PodShot(a, b, PodShot.MULTI, cams[0], cams.toIntArray())) else mutableListOf(PodShot(a, b, PodShot.FULL, cams[0], IntArray(0)))
            "mainboxes" -> mutableListOf(PodShot(a, b, PodShot.FULL, cams[0], IntArray(0)))
            else -> if (p.st.liveSwitch && d.live.isNotEmpty()) PodEngine.shotsFromLive(d.live, a, b, { d.hasCam(it) }, cams[0]) else null
        }
    }

    /** إعدادات الفلوق الافتراضية لمشروع جديد */
    private fun vlogDefaults(d: Data, p: PodProject) {
        if (!d.setup.vlog) return
        p.st.glow = d.setup.glow
        // المونتاج الطبيعي: تلقائي على اللي بيحكي؛ إلا إذا اخترت كاميرات بإيدك وقت التصوير
        p.st.liveSwitch = d.live.isNotEmpty()
        // أول ثواني قبل ما تفتح الكاميرا (أسود): بتنقص من البداية تلقائيًا
        if (d.vFirst > 0.25 && p.cuts.none { it.kind == PCut.PAUSE && it.a <= 0.01 })
            p.cuts.add(PCut(0.0, d.vFirst + 0.1, PCut.PAUSE, true))
        // ⏸ الإيقاف المؤقت وقت التصوير = مقطع محذوف من الفيديو (بتقدر ترجعه من المونتاج)
        for ((pa, pb) in d.pauses) if (pb > pa + 0.05 && p.cuts.none { it.kind == PCut.PAUSE && kotlin.math.abs(it.a - pa) < 0.05 })
            p.cuts.add(PCut(pa, pb, PCut.PAUSE, true))
        p.cuts.sortBy { it.a }
    }

    fun newEpisode(d: Data): PodProject {
        val p = PodProject("episode", PodProject.EPISODE)
        p.title = tx("الحلقة الكاملة", "Full episode")
        p.st.height = d.setup.height
        p.st.fade = d.setup.fade
        // اتجاه الإخراج الافتراضي = اتجاه أغلب الكاميرات (لا نقص صورًا عمودية داخل إطار أفقي)
        if (camerasMostlyPortrait(d)) p.st.orient = "v"
        p.st.flipFill = defaultFlipFill
        p.st.pip = d.setup.usePip
        defaultTpl?.let { TemplateStore.applyTo(it, p.st); if (camerasMostlyPortrait(d) && it.orient == "auto") p.st.orient = "v" }
        if (p.st.captions && d.tr == null) p.st.captions = d.tr != null
        vlogDefaults(d, p)
        regenerate(d, p)
        return p
    }

    /** كم شخص بكل كاميرا حسب اختيارك (p0 → 1/2/3) */
    fun countsOf(setup: PodSetup): Map<String, Int> = setup.srcCrowd.mapNotNull { (k, v) ->
        when (v) { "1", "one" -> k to 1; "2" -> k to 2; "3", "many" -> k to 3; else -> null }
    }.toMap()

    private fun clipFile(path: String): File? = path.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() && it.length() > 1000 }

    /** كاميرا الشخص i بالعرض؟ (أبعاد الفيديو بعد تدويرك) */
    fun camLandscape(ctx: Context, s: SessionInfo, setup: PodSetup, i: Int): Boolean {
        val dev = setup.people.getOrNull(i)?.camDev ?: return false
        if (dev.isEmpty()) return false
        val v = try { org.json.JSONObject(File(SessionStore.devDir(ctx, s.id, dev), "meta.json").readText()).optJSONObject("video") } catch (_: Throwable) { null } ?: return true
        var land = v.optInt("w") >= v.optInt("h")
        if ((setup.srcRot["p$i"] ?: 0) % 180 != 0) land = !land
        setup.srcCrop["p$i"]?.let { c -> if (c[2] > 0 && c[3] > 0) land = c[2] * (if (land) 16f else 9f) >= c[3] * (if (land) 9f else 16f) }
        return land
    }

    /** مين كاميرته جايبة أكثر من شخص: اختيارك أولًا، وإلا فحص الوجوه (أكثر من ثلث الصور فيها وجهين) */
    fun crowdOf(ctx: Context, s: SessionInfo, setup: PodSetup): Set<Int> = setup.people.indices.filter { i ->
        val dev = setup.people[i].camDev
        when (setup.srcCrowd["p$i"]) {
            "many", "2", "3" -> true
            "one", "1" -> false
            else -> dev.isNotEmpty() && (try { PodFaces.crowdProbe(ctx, s.id, dev, setup.srcRot["p$i"] ?: 0) } catch (_: Throwable) { -1f }) > 0.34f
        }
    }.toSet()

    /** هل أغلب فيديوهات الكاميرات عمودية؟ (يُحسب عند التحميل) */
    fun camerasMostlyPortrait(d: Data): Boolean = d.camsPortrait

    private fun portraitCams(ctx: Context, s: SessionInfo): Boolean {
        var por = 0; var land = 0
        for (dv in s.devices.values) {
            val v = try { org.json.JSONObject(File(SessionStore.devDir(ctx, s.id, dv.id), "meta.json").readText()).optJSONObject("video") } catch (_: Throwable) { null } ?: continue
            if (v.optInt("h") > v.optInt("w")) por++ else land++
        }
        return por > land
    }

    fun newReel(d: Data, a: Double, b: Double, title: String, reason: String, target: Float): PodProject {
        val p = PodProject(ProjectStore.newReelId(), PodProject.REEL)
        p.a = a.coerceIn(0.0, max(0.0, d.duration - 1.0))
        p.b = b.coerceIn(min(p.a + 1.0, d.duration), d.duration)
        p.title = title
        p.reason = reason
        p.st.targetLen = target
        p.st.captions = d.tr != null
        p.st.height = d.setup.height
        p.st.flipFill = defaultFlipFill
        p.st.pip = d.setup.usePip
        defaultReelTpl?.let { TemplateStore.applyTo(it, p.st) }
        if (p.st.orient == "h" && defaultReelTpl?.orient != "h") p.st.orient = "v"
        vlogDefaults(d, p)
        regenerate(d, p)
        return p
    }

    /** خيار الإعدادات: كيف تُملأ لحظة قلب الكاميرا في المشاريع الجديدة */
    @Volatile var defaultFlipFill = "freeze"

    /** الأجزاء الباقية من المشروع بعد كل الحذف (الصمت، الفراغات، الجمل المحذوفة) */
    fun keepSegs(d: Data, p: PodProject): List<DoubleArray> {
        val cuts = if (p.kind == PodProject.REEL && p.st.silence == "off") p.cuts.filter { it.kind != PCut.SILENCE } else p.cuts
        return PodEngine.keepSegments(p.a, p.end(d.duration), cuts)
    }

    /** نفس قرارات التصدير؛ المعاينة: 20 ثانية من زمن الجلسة from (بعد القص) وبدقة خفيفة */
    /**
     * الإنترو من الحلقة نفسها: مشروع صغير على المقطع اللي اخترته، بأسلوب مونتاج لحاله
     * (كاميرا شخص واحد، أو مونتاج مع الكل: كاميرا اللي بيحكي / ذكي، مع/بدون تقسيم ومربع).
     */
    fun selfIntroProject(d: Data, p: PodProject): PodProject? {
        if (!p.hasSelfIntro()) return null
        val q = p.copy()
        q.a = p.ia.coerceIn(0.0, d.duration); q.b = p.ib.coerceIn(q.a, d.duration)
        q.intro = ""; q.outro = ""; q.mid = ""; q.introSrc = "file"; q.cuts.clear(); q.zooms.clear(); q.emph.clear()
        q.st.titleOn = false; q.st.firstHold = 0f; q.st.brk = 0; q.st.layout = "auto"
        q.st.ov.addAll(listOf("brk", "layout", "titleOn", "short"))
        q.shots = introShots(d, p, q.a, q.b) ?: planShots(d, effective(d, q), q.a, q.b, false)
        return q
    }

    /** الإنترو اليدوي: من كل علامة لحد اللي بعدها، اللي اخترته (شخص أو الكل مع بعض). null = ما في علامات (تلقائي) */
    fun introShots(d: Data, p: PodProject, a: Double, b: Double): MutableList<PodShot>? {
        val ms = p.imarks.filter { it.first < b - 0.05 }.sortedBy { it.first }
        if (ms.isEmpty()) return null
        val cams = d.setup.people.indices.filter { d.hasCam(it) }
        if (cams.isEmpty()) return null
        // نفس الكاميرا (شخصين على جهاز واحد) = واحد بس بالتقسيم
        val perCam = cams.distinctBy { d.setup.people[it].camDev }
        val out = ArrayList<PodShot>()
        for ((i, m) in ms.withIndex()) {
            val s0 = if (i == 0) a else max(a, m.first)
            val s1 = if (i == ms.size - 1) b else min(b, ms[i + 1].first)
            if (s1 - s0 < 0.05) continue
            val sh = if (m.second >= 0 && d.hasCam(m.second)) PodShot(s0, s1, PodShot.FULL, m.second, IntArray(0))
                else if (perCam.size >= 2) PodShot(s0, s1, PodShot.MULTI, perCam[0], perCam.toIntArray())
                else PodShot(s0, s1, PodShot.FULL, perCam[0], IntArray(0))
            sh.locked = true; sh.tag = "manual"
            val l = out.lastOrNull()
            if (l != null && l.type == sh.type && l.main == sh.main && l.others.contentEquals(sh.others)) l.end = s1 else out.add(sh)
        }
        return out.ifEmpty { null }
    }

    fun buildJob(ctx: Context, d: Data, p: PodProject, vertical: Boolean, preview: Boolean, from: Double = 0.0, previewLen: Double = 20.0,
                 introFile: File? = null, outTo: File? = null, noSync: Boolean = false, hold: Boolean = false): RenderJob {
        // أول إشي: المشروع يكون محدّث (القالب واللقطات)، وبعدين كل إشي بينحسب من نفس النسخة
        if (!noSync && syncTpl(ctx, p)) regenerate(d, p)
        var segs = keepSegs(d, p)
        var previewOffset = 0.0
        if (preview) {
            val map = PodEngine.TimeMap(segs)
            val o0 = max(0.0, min(map.toOutNearest(from), map.outDur - previewLen))
            val o1 = min(map.outDur, o0 + previewLen)
            previewOffset = o0
            val win = ArrayList<DoubleArray>()
            for ((i, sg) in segs.withIndex()) {
                val sa = map.outStart[i]
                val sb = sa + (sg[1] - sg[0])
                val x0 = max(sa, o0); val x1 = min(sb, o1)
                if (x1 - x0 > 0.04) win.add(doubleArrayOf(sg[0] + (x0 - sa), sg[0] + (x1 - sa)))
            }
            segs = win
        }
        // الإنترو (من الحلقة): بدون أسماء ولا عنوان
        val tpl = if (noSync) tplOf(ctx, p).copy().also { it.id = p.st.tpl; it.names = "never"; it.titleOn = false } else tplOf(ctx, p)
        val shots = if (p.shots.isNotEmpty()) p.shots else planShots(d, effective(d, p), p.a, p.end(d.duration), d.setup.wides.isNotEmpty())
        // كاميرا اللي بيحكي: ولا زوم تلقائي (الزوم اللي حطيته بإيدك بيضل)
        // الكادر الثابت (الأساس): ولا زوم ولا تقريب — بس إذا اخترت «بيلحق الوجوه» بالقالب
        val zooms = if (!tpl.follow || p.st.zoomMode == "off") emptyList() else p.zooms.toList()
        val wordsStyle = tpl.capStyle != "line"
        val allCaps = if (d.tr != null) {
            val a = p.a; val b = p.end(d.duration)
            val emph = HashSet(p.emph)
            if (p.st.highlight || tpl.punch != "off") d.sem?.emphWords?.let { emph.addAll(it) }
            PodEngine.captionChunks(d.tr.segs.filter { it.b > a && it.a < b }, emph, if (wordsStyle) 20 else 42, if (wordsStyle) 3 else 8)
        } else emptyList()
        val caps = if (p.st.captions) (if (p.st.highlight || wordsStyle) allCaps else allCaps.map { PodEngine.CapChunk(it.a, it.b, it.words, emptySet(), it.wt) }) else emptyList()
        // تقريب سريع على الكلمات المهمة والجمل المحورية (مش أقرب من ٤ ثواني لبعض)
        val punches = ArrayList<Double>()
        if (tpl.punch == "emph" && tpl.follow) {
            val cand = ArrayList<Double>()
            for (c in allCaps) for (k in c.emph) c.wt.getOrNull(k)?.let { cand.add(it) }
            d.sem?.keys?.forEach { cand.add(it[0]) }
            // بدون فهم للنص: بداية كل كلام طويل (بعد ثانية من بدايته)
            val gapMin = if (cand.isEmpty()) 8.0 else 4.0
            if (cand.isEmpty()) for (tn in d.turns) if (!tn.backchannel && tn.b - tn.a > 4.0) cand.add(tn.a + 1.0)
            var last = -1e9
            for (x in cand.sorted()) if (x - last >= gapMin && x >= p.a && x <= p.end(d.duration)) { punches.add(x); last = x }
        }
        val faces = HashMap<String, FaceTrack>()
        val pipFaces = HashMap<String, FaceTrack>()
        val devs = (d.setup.people.map { it.camDev } + d.setup.wides).filter { it.isNotEmpty() }.distinct()
        // الوجوه: للمربع العائم، والقص على الوجه بالشاشة المقسومة، وإبعاد الكتابة عن الوجوه (إذا متحللة)
        for (dev in devs) PodFaces.load(ctx, d.s.id, dev)?.let { pipFaces[dev] = it; if (p.st.faces) faces[dev] = it }
        val label = when {
            preview -> "_preview"
            p.kind == PodProject.REEL -> "_reel_" + p.a.toInt()
            else -> ""
        }
        return RenderJob(
            shots = shots, segs = segs, vertical = vertical, label = label, zooms = zooms, captions = caps,
            title = if (p.st.titleOn) p.title else "", titleSecs = tpl.hookSecs.toDouble().coerceIn(1.0, 15.0), fade = p.st.fade,
            height = p.st.height, capSize = p.st.capSize, faces = faces, preview = preview,
            outFile = outTo ?: if (preview) File(ctx.cacheDir, "pod_preview.mp4") else null,
            outOffset = previewOffset,
            frame = p.st.frame, fill = p.st.fill, pinned = p.st.pinned, glow = p.st.glow, flipFill = p.st.flipFill,
            tpl = tpl, pipFaces = pipFaces, punches = punches, brand = Brand.values(ctx), crowd = d.crowd.map { "p$it" }.toSet(), count = countsOf(d.setup), intro = if (preview) null else introFile ?: (if (p.introSrc == "file") clipFile(p.intro) else null), outro = if (preview) null else clipFile(p.outro),
            mid = if (preview) null else clipFile(p.mid), introFx = if (introFile != null) ClipFx() else p.introFx, midFx = p.midFx, outroFx = p.outroFx,
            introTrans = p.introTr, introTransSec = p.introTrSec, outroTrans = p.outroTr, outroTransSec = p.outroTrSec,
            totalOut = PodEngine.TimeMap(keepSegs(d, p)).outDur, hold = hold,
        )
    }

    /** ملف SRT لمشروع (بأزمنة النسخة المعدّلة)، مع ترجمة اختيارية للنصوص */
    fun srtFor(d: Data, p: PodProject, texts: List<String>? = null): String {
        val tr = d.tr ?: return ""
        val a = p.a; val b = p.end(d.duration)
        val segs = tr.segs.filter { it.b > a && it.a < b }
        val map = PodEngine.TimeMap(keepSegs(d, p))
        val entries = segs.mapIndexed { i, g -> Triple(max(a, g.a), min(b, g.b), texts?.getOrNull(i) ?: g.text) }
            .filter { e -> map.toOut((e.first + e.second) / 2) != null }
        return PodEngine.srt(entries, map)
    }

/** وقت أول إطار فعلي من كاميرا جهاز (ثوانٍ بزمن الجلسة)، 0 = ما في معلومة */
fun videoFirstSec(ctx: Context, s: SessionInfo, dev: String): Double = try {
    val dv = s.devices[dev]
    val mf = File(SessionStore.devDir(ctx, s.id, dev), "meta.json")
    if (dv == null || dev.isEmpty() || !mf.exists()) 0.0 else {
        val vm = org.json.JSONObject(mf.readText()).optJSONObject("video")
        val first = vm?.optLong("first", 0L) ?: 0L
        if (vm == null || first <= 0L) 0.0 else {
            val sb = vm.optLong("startBoot")
            val target = (first - sb) / 1e6
            val vt = Finalizer.SatVideoTime(s, dv, sb)
            var lo = 0.0; var hi = 30.0
            repeat(40) { val m = (lo + hi) / 2; if (vt.fileMs(s.startElapsedNs + (m * 1e9).toLong()) < target) lo = m else hi = m }
            if (hi >= 29.9) 0.0 else hi
        }
    }
} catch (_: Throwable) { 0.0 }

}
