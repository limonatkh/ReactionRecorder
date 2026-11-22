package com.reactionrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateTest {
    @Test fun jsonRoundTrip() {
        val t = PodTemplate().apply { id = "x"; name = "n"; pipPos = "custom"; pipX = 0.3f; pipShape = "alternate"; pipPlace = "person"; names = "first"; glowColor = 0xFF3D8BFF.toInt(); intensity = "lively" }
        val b = PodTemplate.fromJson(t.toJson())
        assertEquals("custom", b.pipPos); assertEquals(0.3f, b.pipX, 1e-4f); assertEquals("alternate", b.pipShape)
        assertEquals("person", b.pipPlace); assertEquals("first", b.names); assertEquals(0xFF3D8BFF.toInt(), b.glowColor); assertEquals("lively", b.intensity)
    }

    @Test fun applyCopiesDecisions() {
        val st = ProjSettings()
        TemplateStore.applyTo(PodTemplate().apply { id = "q"; intensity = "calm"; pip = false; fade = true; orient = "v" }, st)
        assertEquals("q", st.tpl); assertEquals("calm", st.intensity); assertEquals(false, st.pip); assertTrue(st.fade); assertEquals("v", st.orient)
        assertEquals("q", ProjSettings.fromJson(st.toJson()).tpl)
    }

    @Test fun pacingInfoHasNumbers() {
        val l = PodTemplate.pacingInfo("balanced", true)
        assertTrue(l.size >= 6)
        assertTrue(l.any { it.contains("2") })
    }

    @Test fun templateChangeReachesProjectButNotManualEdits() {
        val t = PodTemplate().apply { id = "q"; glow = true; zoomMode = "auto" }
        val st = ProjSettings()
        TemplateStore.applyTo(t, st)
        assertTrue(st.glow)
        // تعديل يدوي بالمشروع
        val before = st.copy(); st.applied = false
        st.zoomMode = "off"
        TemplateStore.trackOverrides(before, st)
        assertTrue("zoomMode" in st.ov)
        // المستخدم شال الإطار المضيء من القالب → لازم يختفي من المشروع (المعاينة = التصدير)
        t.glow = false; t.zoomMode = "auto"
        TemplateStore.sync(t, st)
        assertEquals(false, st.glow)
        assertEquals("off", st.zoomMode)
    }

    @Test fun oldProjectsKeepTheirOwnSettings() {
        val st = ProjSettings().apply { glow = true; zoomMode = "auto" } // sv = 0 (قديم)
        TemplateStore.sync(PodTemplate().apply { glow = false; zoomMode = "off" }, st)
        assertEquals(false, st.glow)        // كان بينسخ من القالب → بيتبع القالب
        assertEquals("auto", st.zoomMode)   // ما كان من القالب → تعديلك بيضل
    }

    @Test fun reelTemplatesHaveTheirOwnKind() {
        val reels = PodTemplate.builtIns().filter { it.kind == "reel" }
        assertTrue(reels.size >= 5)
        assertTrue(reels.all { it.orient == "v" })
        val b = PodTemplate.fromJson(reels.first { it.pipFace }.toJson())
        assertTrue(b.pipFace && b.avoidFace); assertEquals("reel", b.kind)
    }

    @Test fun pipIsFreeAndCaptionsMoveAwayFromIt() {
        val t = PodTemplate().apply { kind = "reel"; reelBase(); pipPos = "custom"; pipX = 0.5f; pipY = 0.97f; pipSize = 0.6f }
        val W = 720f; val H = 1280f
        val sz = PodLayout.pipSize(t, true, W, false)
        val r = PodLayout.pipRects(t, W, H, true, 1, sz[0], sz[1], false, false, true)[0]
        // حر: لازق تحت إذا بدك (التنبيه الأحمر بس بالمعاينة)
        assertEquals(H, r.bottom, 1f)
        assertTrue(PodLayout.inBlocked(r, true, W, H))
        // الكتابة بتبعد عنه
        val capH = 80f
        val y = PodLayout.capPlace(t, true, W, H, capH, listOf(r), null)
        assertEquals(0f, PodLayout.overlap(PodLayout.Box(W * 0.06f, y - capH / 2, W * 0.94f, y + capH / 2), r), 1f)
        // شخص فوق وشخص تحت: الكتابة على الفاصل
        assertEquals(640f, PodLayout.capPlace(t, true, W, H, capH, emptyList(), 640f), 0.5f)
    }

    @Test fun sourceRotationMapsFacesAndTexture() {
        // وجه فوق يسار بصورة بالعرض، بعد لفة ٩٠ (مع عقارب الساعة) بيصير فوق يمين
        val f = SrcGeo.mapFace(floatArrayOf(0.2f, 0.1f, 0.2f), 90, null, 1920, 1080)!!
        assertEquals(0.9f, f[0], 1e-4f); assertEquals(0.2f, f[1], 1e-4f)
        // المصفوفة: نقطة الشاشة (فوق يمين، v لفوق = (1,1)) بتاخد من الأصل (فوق يسار = (0,1))
        val m = SrcGeo.texMatrix(90, null)
        val p = SrcGeo.apply(m, 1f, 1f)
        assertEquals(0f, p[0], 1e-4f); assertEquals(1f, p[1], 1e-4f)
        // قص: نص الصورة اليسار
        val m2 = SrcGeo.texMatrix(0, floatArrayOf(0f, 0f, 0.5f, 1f))
        assertEquals(0.5f, SrcGeo.apply(m2, 1f, 0f)[0], 1e-4f)
        val d = SrcGeo.dims(1920, 1080, 90, floatArrayOf(0f, 0f, 1f, 0.5f))
        assertEquals(1080, d[0]); assertEquals(960, d[1])
    }

    @Test fun itemsRoundTripAndBrandFill() {
        val t = PodTemplate().apply { items.add(TplItem().apply { text = "{link}"; pos = "bc"; whenShow = "end"; secs = 5f }) }
        val b = PodTemplate.fromJson(t.toJson())
        assertEquals("bc", b.items[0].pos); assertEquals("end", b.items[0].whenShow)
        assertEquals("x.com/me", Brand.fill("{link}", mapOf("link" to "x.com/me")))
        assertEquals("", Brand.fill("{handle}", emptyMap()))
        assertTrue(b.items[0].alphaAt(58.0, 60.0) > 0.5f)
        assertEquals(0f, b.items[0].alphaAt(10.0, 60.0), 0.001f)
    }

    @Test fun aiTemplateValuesAreClamped() {
        val o = org.json.JSONObject().put("name", "x").put("pipSize", 5.0).put("trans", "explode").put("capStyle", "words").put("breakEvery", 9999)
        val t = PodAI.templateFromJson(o, "reel")
        assertEquals(0.45f, t.pipSize, 1e-4f); assertEquals("cut", t.trans); assertEquals("words", t.capStyle); assertEquals(300, t.breakEvery)
        assertEquals("reel", t.kind); assertEquals("tc", t.pipPos)
    }

    @Test fun localIdeasUnderstandBasics() {
        val t = TemplateIdeas.local("ريل حماسي دائري ورابطي بالآخر", "reel")
        assertEquals("circle", t.pipShape); assertEquals("lively", t.intensity); assertEquals("{link}", t.items[0].text)
    }
}
