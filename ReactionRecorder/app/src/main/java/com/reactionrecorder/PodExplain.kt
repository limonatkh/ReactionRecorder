package com.reactionrecorder

import android.content.Context
import kotlin.math.max
import kotlin.math.min
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * شرح بالكلام العادي: شو الإعدادات الشغالة وشو رح يصير بالمونتاج.
 * قبل التسجيل (مربع تأكيد) وداخل المونتاج (بطاقة "شو بصير هون").
 */
object PodExplain {

    private fun role(p: PodPerson): String = when {
        p.camDev.isNotEmpty() && p.micDev.isNotEmpty() && p.camDev != p.micDev -> tx("كاميرا + مايك منفصل", "camera + separate mic")
        p.camDev.isNotEmpty() -> tx("كاميرا + مايك", "camera + mic")
        p.micDev.isNotEmpty() -> tx("مايك بس (بدون صورة)", "mic only (no picture)")
        else -> tx("ولا إشي!", "nothing!")
    }

    private fun flipName(k: String) = when (k) {
        "blur" -> tx("تمويه", "blur"); "other" -> tx("كاميرا ثانية", "another camera"); else -> tx("آخر صورة ثابتة", "last frame frozen")
    }

    /** ملخص ما قبل التسجيل */
    fun beforeRecording(ctx: Context, s: PodSetup): List<String> {
        val out = ArrayList<String>()
        out += "📹 " + tx("مين بسجّل:", "Who records:")
        for (p in s.people) out += "   • ${p.name}: " + role(p)
        for (w in s.wides) out += "   • " + (SatHub.links[w]?.name ?: tx("جهاز", "device")) + ": " + tx("كاميرا عامة للكل", "wide camera")
        val cams = s.people.count { it.camDev.isNotEmpty() }
        out += ""
        out += "🎬 " + tx("المونتاج التلقائي رح يكون:", "The automatic edit will be:")
        if (cams <= 1 && s.wides.isEmpty()) {
            out += "   • " + tx("كاميرا وحدة بس — الفيديو كله منها، والصوت من كل المايكات.", "Only one camera — the whole video comes from it, with sound from every mic.")
        } else if (s.vlog) {
            out += "   • " + when (s.vlogLayout) {
                "grid" -> tx("كل الكاميرات ظاهرة طول الوقت (الشاشة مقسومة).", "Every camera shown all the time (split screen).")
                "mainboxes" -> tx("كاميرتك بملء الشاشة، وصحابك بمربعات صغيرة فوقها.", "Your camera full screen, friends in small boxes on top.")
                else -> tx("الصورة بتتنقل تلقائيًا لكاميرا اللي بيحكي.", "The picture switches automatically to whoever is speaking.")
            }
            if (s.vlogLayout == "switch") out += "   • " + tx("إذا ضغطت على صورة صاحبك وقت التصوير، المونتاج بيمشي على اختياراتك بدل التلقائي.", "If you tap a friend's picture while filming, the edit follows your picks instead.")
            if (s.glow) out += "   • " + tx("إطار مضيء حول اللي بيحكي.", "A glowing frame around whoever speaks.")
            out += "   • " + tx("لحظة قلب الكاميرا بتنعبّى بـ: ", "The moment a camera flips is filled with: ") + flipName(ctx.getSharedPreferences("reaction_ui", Context.MODE_PRIVATE).getString("flipFill", "freeze") ?: "freeze")
        } else {
            out += "   • " + tx("الصورة بتتنقل تلقائيًا لكاميرا اللي بيحكي.", "The picture switches automatically to whoever is speaking.")
            out += "   • " + tx("لما يحكوا مع بعض: ", "When they talk together: ") + if (s.multiStyle == "boxes") tx("مربعات فوق خلفية.", "boxes over a background.") else tx("الشاشة مقسومة.", "split screen.")
            if (s.usePip) out += "   • " + tx("اللي بيقاطع أو بيرد رد قصير بيطلع بمربع عائم فوق اللي بيحكي.", "Whoever interrupts or replies briefly appears in a floating box over the speaker.")
            if (s.cutSilence) out += "   • " + tx("السكتات بتنقص.", "Silences are cut.")
        }
        out += ""
        out += "📦 " + tx("بعد ما توقف: كل جوال حافظ تسجيله عنده وبيبعته لجوالك تلقائيًا. استنى لحد ما يطلع \"✓ وصلت كل الملفات\" وبعدين افتح المونتاج.", "After you stop: every phone keeps its recording and sends it to yours automatically. Wait for \"✓ all files received\" before opening the edit.")
        out += "✏️ " + tx("كل هذا بتقدر تغيّره بعدين بالمونتاج.", "You can change all of this later in the edit.")
        return out
    }

    /** مربع "قبل ما نبدأ": الإعدادات باختصار + التحذيرات؛ سجّل أو ارجع */
    fun confirmStart(a: AppCompatActivity, s: PodSetup, warnings: List<String>, go: () -> Unit) {
        val d = a.resources.displayMetrics.density
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (4 * d).toInt()) }
        fun line(t: String, c: String = UiKit.TEXT, size: Float = 13.5f) = col.addView(TextView(a).apply { text = t; textSize = size; setTextColor(Color.parseColor(c)); setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt()) })
        if (warnings.isNotEmpty()) {
            line("⚠️ " + tx("انتبه:", "Heads up:"), UiKit.ORANGE)
            for (w in warnings) line("   • $w", UiKit.ORANGE)
            line("")
        }
        for (l in beforeRecording(a, s)) line(l)
        AlertDialog.Builder(a).setTitle(tx("قبل ما نبدأ", "Before we start")).setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("●  " + tx("سجّل", "Record")) { _, _ -> go() }
            .setNegativeButton(tx("ارجع", "Back"), null).show()
    }

    /** داخل المونتاج: شو بصير هون (سطر + لون) */
    fun inEdit(ctx: Context, dd: PodStudio.Data, pp: PodProject): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val n = dd.setup.people.size
        for (m in dd.missing) out += "⏳ $m" to UiKit.ORANGE
        if (dd.missing.isNotEmpty()) out += tx("لما يوصلوا، المونتاج بيتعمل من جديد لحاله (التعديلات المثبّتة 🔒 ما بتتغير).", "When they arrive, the edit rebuilds itself (locked 🔒 changes stay).") to UiKit.SUB
        val cams = (0 until n).filter { dd.hasCam(it) && PodcastAnalyzer.hasVideo(ctx, dd.s, dd.setup.people[it].camDev) }
        out += ("🎥 " + tx("كاميرات وصلت: ", "Cameras received: ") + if (cams.isEmpty()) tx("ولا وحدة", "none") else cams.joinToString("، ") { dd.setup.people[it].name }) to UiKit.TEXT
        val how = when {
            cams.size <= 1 -> tx("في كاميرا وحدة بس، فالفيديو كله منها.", "There's only one camera, so the whole video uses it.")
            dd.setup.vlog && dd.setup.vlogLayout == "grid" -> tx("كل الكاميرات ظاهرة طول الوقت (مقسومة).", "Every camera shown all the time (split).")
            dd.setup.vlog && dd.setup.vlogLayout == "mainboxes" -> tx("الكاميرا الرئيسية بملء الشاشة والباقي مربعات.", "Main camera full screen, the rest in boxes.")
            dd.setup.vlog && pp.st.liveSwitch && dd.live.isNotEmpty() -> tx("الصورة ماشية على اختياراتك وقت التصوير (${dd.live.size} مرة).", "The picture follows your picks while filming (${dd.live.size} times).")
            else -> tx("الصورة بتتنقل تلقائيًا لكاميرا اللي بيحكي.", "The picture switches automatically to whoever speaks.")
        }
        out += "🎬 $how" to UiKit.TEXT
        // المايكات: ناقص (مستعمل صوت الكاميرا)، ولا مشترك بين أشخاص على كاميرات مختلفة
        val micUsed = dd.setup.people.map { PodcastAnalyzer.micOf(ctx, dd.s, it) }
        for ((i, pr) in dd.setup.people.withIndex()) if (pr.micDev.isNotEmpty() && micUsed[i] != pr.micDev)
            out += tx("⚠ مايك ${pr.name} ما وصل — مستعملين صوت ${if (micUsed[i].isEmpty()) "ولا إشي" else "كاميرته"} بداله (صوته ممكن يكون أبعد).", "⚠ ${pr.name}'s mic didn't arrive — using ${if (micUsed[i].isEmpty()) "nothing" else "their camera's audio"} instead (may sound farther).") to UiKit.ORANGE
        for (i in dd.setup.people.indices) for (j in 0 until i) if (micUsed[i].isNotEmpty() && micUsed[i] == micUsed[j] && dd.setup.people[i].camDev != dd.setup.people[j].camDev)
            out += tx("⚠ ${dd.setup.people[j].name} و${dd.setup.people[i].name} على نفس المايك وكاميرات مختلفة — من الصوت ما بنقدر نعرف مين فيهم بيحكي، فكاميرا ${dd.setup.people[i].name} ما رح تطلع لحالها. الحل: مايك لكل كاميرا، أو اختار اللقطات بإيدك.", "⚠ ${dd.setup.people[j].name} and ${dd.setup.people[i].name} share one mic on different cameras — the audio can't tell who's talking, so ${dd.setup.people[i].name}'s camera won't be picked on its own. Fix: one mic per camera, or pick shots by hand.") to UiKit.ORANGE
        if (dd.an.bleed.isNotBlank() && dd.an.bleed != "-") out += (tx("🎚 تسريب الصوت بين المايكات (اتعلّمناه وطرحناه): ", "🎚 Mic bleed (learned and removed): ") + dd.an.bleed) to UiKit.SUB
        // كم ظهر كل واحد فعليًا
        val share = DoubleArray(n)
        // على الفيديو النهائي: بس الأجزاء اللي بتضل بعد القص
        val kept = PodStudio.keepSegs(dd, pp)
        fun inKept(a: Double, b: Double) = kept.sumOf { k -> max(0.0, min(b, k[1]) - max(a, k[0])) }
        for (s in pp.shots) {
            val d = inKept(s.start, s.end)
            when (s.type) {
                PodShot.FULL -> if (s.main in 0 until n) share[s.main] += d
                PodShot.PIP -> { if (s.main in 0 until n) share[s.main] += d; for (o in s.others) if (o in 0 until n) share[o] += d }
                PodShot.MULTI -> for (o in s.others) if (o in 0 until n) share[o] += d
            }
        }
        val tot = kept.sumOf { it[1] - it[0] }.coerceAtLeast(0.001)
        if (n > 1) out += ("👁 " + tx("كم بيظهر كل واحد (بالفيديو النهائي): ", "Screen time (in the final video): ") + (0 until n).filter { dd.hasCam(it) }.joinToString("، ") { "${dd.setup.people[it].name} ${(share[it] * 100 / tot).toInt().coerceAtMost(100)}%" }) to UiKit.TEXT
        // كم بيحكي كل واحد حسب الصوت (عشان تعرف: المشكلة بالصوت ولا بالمونتاج)
        talkShare(dd, pp)?.let { ts ->
            if (n > 1) out += ("🎙 " + tx("كم بيحكي كل واحد (حسب الصوت): ", "Talk time (from the audio): ") + (0 until n).filter { dd.hasCam(it) }.joinToString("، ") { "${dd.setup.people[it].name} ${(ts[it] * 100).toInt()}%" }) to UiKit.TEXT
            val off = (0 until n).filter { dd.hasCam(it) && ts[it] - share[it] / tot > 0.2 }
            for (i in off) out += tx("⚠ ${dd.setup.people[i].name} بيحكي ${(ts[i] * 100).toInt()}% بس ظاهر ${(share[i] * 100 / tot).toInt()}% — هاد غلط بالمونتاج: اكبس «انسخ بيانات القالب والمونتاج» وابعتهن.", "⚠ ${dd.setup.people[i].name} talks ${(ts[i] * 100).toInt()}% but is on screen ${(share[i] * 100 / tot).toInt()}% — that's an editing bug: tap “Copy template & edit data” and send it.") to UiKit.ORANGE
        }
        if (cams.size > 1 && (0 until n).count { share[it] > 0.5 } <= 1 && !(dd.setup.vlog && dd.setup.vlogLayout != "switch"))
            out += tx("ما في تنقّل بين الكاميرات لأنه ما انسمع إلا صوت واحد واضح. إذا كنتوا قريبين من بعض، كل مايك بيلقط الثاني — جرّب \"حساسية الكلام\" أو اختار اللقطات بإيدك من الشريط.", "No switching because only one voice was clearly detected. If you were close together, each mic picks up the other — try the speech sensitivity or pick shots by hand on the timeline.") to UiKit.ORANGE
        out += ("✨ " + tx("إطار حول اللي بيحكي: ", "Glow around the speaker: ") + (if (pp.st.glow) tx("شغّال", "on") else tx("مطفي", "off")) +
            "   •   🔄 " + tx("لحظة قلب الكاميرا: ", "Camera flip: ") + flipName(pp.st.flipFill)) to UiKit.SUB
        return out
    }

    /** نسبة الوقت اللي كل شخص حكى فيه (حسب تحليل الصوت) بين بداية المشروع ونهايته */
    fun talkShare(dd: PodStudio.Data, pp: PodProject): DoubleArray? {
        val an = dd.an
        val n = dd.setup.people.size
        val a = pp.a; val b = pp.end(dd.duration)
        if (b - a < 1.0) return null
        val i0 = (a / an.frameSec).toInt(); val i1 = (b / an.frameSec).toInt()
        return DoubleArray(n) { p -> val r = an.active.getOrNull(p) ?: return@DoubleArray 0.0; var k = 0; for (i in kotlin.math.max(0, i0) until kotlin.math.min(r.size, i1)) if (r[i]) k++; k.toDouble() / kotlin.math.max(1, i1 - i0) }
    }
}
