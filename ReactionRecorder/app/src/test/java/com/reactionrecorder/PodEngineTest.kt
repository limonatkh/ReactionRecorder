package com.reactionrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class PodEngineTest {
    /** يبني تحليلًا من فترات كلام لكل شخص */
    private fun analysis(duration: Double, vararg people: List<Pair<Double, Double>>): PodAnalysis {
        val fs = 0.05
        val n = (duration / fs).toInt()
        val act = Array(people.size) { p ->
            BooleanArray(n) { f -> val t = f * fs; people[p].any { t >= it.first && t < it.second } }
        }
        return PodAnalysis(fs, act, FloatArray(people.size) { -25f }, duration)
    }

    /** الاختبارات القديمة على السلوك القديم (الردود القصيرة ما بتقلب) */
    private fun st(intensity: String = "balanced") = ProjSettings.episodeDefaults().also { it.intensity = intensity; it.shortMode = "off" }

    @Test fun anyUtteranceShowsAndHolds() {
        // أ يتكلم 20 ثانية، ب يقول "آه" نصف ثانية عند 8 → الكاميرا عليه وبتضل ~2.5ث بعد ما يسكت
        val an = analysis(20.0, listOf(0.0 to 20.0), listOf(8.0 to 8.5))
        val s1 = ProjSettings.episodeDefaults().also { it.shortStyle = "switch"; it.firstHold = 0f }
        val shots = PodEngine.autoShots(an, 2, false, true, s1, 0.0, 20.0)
        val b = shots.firstOrNull { it.main == 1 && it.type == PodShot.FULL }
        assertNotNull("expected a shot on person 1: " + shots.map { Triple(it.start, it.type, it.main) }, b)
        assertTrue(b!!.start in 7.7..8.0); assertTrue("end ${b.end}", b.end in 10.8..11.2)
        assertEquals(0, shots.last().main)
        // مربع عائم بدل التبديل
        val pip = PodEngine.autoShots(an, 2, false, true, ProjSettings.episodeDefaults().also { it.shortStyle = "pip"; it.firstHold = 0f }, 0.0, 20.0)
        assertTrue(pip.any { it.type == PodShot.PIP && it.others.contentEquals(intArrayOf(1)) })
        // بوضع "بس إذا حكى أكثر من ثانية" ما بتقلب
        val min = PodEngine.autoShots(an, 2, false, true, ProjSettings.episodeDefaults().also { it.shortMode = "min"; it.minTalk = 1f }, 0.0, 20.0)
        assertTrue(min.all { it.main == 0 && it.type == PodShot.FULL })
    }

    @Test fun boxStaysWhenTheyTalkAgainSoon() {
        // ب بيرد مرتين قريبين (8 و 13): المربع ما بيختفي ويرجع
        val an = analysis(30.0, listOf(0.0 to 30.0), listOf(8.0 to 8.5, 12.0 to 12.5))
        val st = ProjSettings.episodeDefaults().also { it.shortStyle = "pip"; it.bridge = 3f }
        val shots = PodEngine.autoShots(an, 2, false, true, st, 0.0, 30.0)
        val pips = shots.filter { it.type == PodShot.PIP }
        assertEquals("one continuous box: " + shots.map { Triple(it.start, it.type, it.main) }, 1, pips.size)
        assertTrue(pips[0].start < 8.0 && pips[0].end > 14.5)
        // بدون جسر: بيختفي ويرجع (مربعين)
        val st0 = ProjSettings.episodeDefaults().also { it.shortStyle = "pip"; it.bridge = 0f }
        assertEquals(2, PodEngine.autoShots(an, 2, false, true, st0, 0.0, 30.0).count { it.type == PodShot.PIP })
    }

    @Test fun noFlickerAfterASwitch() {
        // تبديل سريع: كل لقطة بعد أي انتقال ≥ الحد الأدنى، أو الاثنين مع بعض
        val shots = PodEngine.settle(listOf(
            PodShot(0.0, 10.0, PodShot.FULL, 0, IntArray(0)),
            PodShot(10.0, 11.0, PodShot.FULL, 1, IntArray(0)),
            PodShot(11.0, 11.8, PodShot.FULL, 0, IntArray(0)),
            PodShot(11.8, 20.0, PodShot.FULL, 1, IntArray(0)),
        ), 2.0, true)
        assertTrue(shots.map { Triple(it.start, it.type, it.main) }.toString(), shots.all { it.dur >= 1.5 })
        assertTrue(shots.any { it.type == PodShot.MULTI && it.others.contentEquals(intArrayOf(0, 1)) })
    }

    @Test fun boxShrinksOrSplitsInsteadOfCoveringAFace() {
        val tp = PodTemplate().also { it.pipSize = 0.47f; it.pipPos = "tl"; it.avoidFace = false }
        val W = 1920f; val H = 1080f
        val sz = PodLayout.pipSize(tp, false, W, false)
        // وجه بعيد عن المربع → نفس الحجم
        val far = PodLayout.pipPlan(tp, W, H, false, 1, sz[0], sz[1], false, false, PodLayout.Box(1500f, 300f, 1800f, 700f))
        assertEquals(1f, far.scale); assertTrue(!far.split)
        // وجه قريب → بيصغر
        // وجه بيلمس المربع بحجمه الكامل بس مش لو صغر لـ 85%
        val r1 = PodLayout.pipRects(tp, W, H, false, 1, sz[0], sz[1], false, false, false)[0]
        val r85 = PodLayout.pipRects(tp, W, H, false, 1, sz[0] * 0.85f, sz[1] * 0.85f, false, false, false)[0]
        val fx = (r1.right + r85.right) / 2f
        val near = PodLayout.pipPlan(tp, W, H, false, 1, sz[0], sz[1], false, false, PodLayout.Box(fx, r1.top, fx + 300f, r1.top + 300f))
        assertTrue("scale ${near.scale}", near.scale < 1f && !near.split)
        // وجه بالزاوية نفسها → شاشة مقسومة
        assertTrue(PodLayout.pipPlan(tp, W, H, false, 1, sz[0], sz[1], false, false, PodLayout.Box(60f, 60f, 500f, 500f)).split)
    }

    @Test fun boxNeverStaysTooLong() {
        // بيرد كل شوي لمدة طويلة: ما في مربع أطول من ٨ ثواني
        val an = analysis(60.0, listOf(0.0 to 60.0), (0 until 10).map { 5.0 + it * 4.0 to 5.6 + it * 4.0 })
        val st = ProjSettings.episodeDefaults().also { it.shortStyle = "pip"; it.bridge = 3f }
        val shots = PodEngine.autoShots(an, 2, false, true, st, 0.0, 60.0)
        assertTrue(shots.map { Triple(it.start, it.type, it.dur) }.toString(), shots.none { it.type == PodShot.PIP && it.dur > PodEngine.MAX_PIP + 0.5 })
    }

    @Test fun sharePicksExactly() {
        assertEquals(1, (0 until 10).count { PodEngine.sharePick(it, 0.1) })
        assertEquals(3, (0 until 10).count { PodEngine.sharePick(it, 0.3) })
    }

    @Test fun typingNameAnimation() {
        // بينكتب حرف حرف ووراه مؤشر، بيثبت، وبينمسح
        assertEquals("ع|", TypeAnim.at("عماد", 0.05, 2f, "type")!!.first)
        assertEquals("عم|", TypeAnim.at("عماد", 0.2, 2f, "type")!!.first)
        assertEquals("عماد", TypeAnim.at("عماد", 1.5, 2f, "type")!!.first)
        assertNull(TypeAnim.at("عماد", 10.0, 2f, "type"))
    }

    @Test fun geminiMinuteTimesAreFixed() {
        val o = org.json.JSONObject(PodAI.fixTimes("{\"sounds\":[{\"start\": 1:00.1, \"end\": 1:03.5, \"type\": \"speech\"}]}"))
        assertEquals(60.1, o.getJSONArray("sounds").getJSONObject(0).getDouble("start"), 0.01)
    }

    @Test fun voicingSeparatesHumFromKnock() {
        // "امم": نغمة 150 هرتز نص ثانية؛ دقة: ضربة قصيرة ضجيج
        val sr = 16000
        val hum = FloatArray(sr / 2) { i -> (0.3 * kotlin.math.sin(2 * Math.PI * 150 * i / sr) + 0.1 * kotlin.math.sin(2 * Math.PI * 300 * i / sr)).toFloat() }
        val rnd = java.util.Random(1)
        val knock = FloatArray(sr / 2) { i -> if (i < 1200) ((rnd.nextFloat() - 0.5f) * (1f - i / 1200f)) else 0f }
        val vh = PodVoice.voicing(hum); val vk = PodVoice.voicing(knock)
        assertEquals(VEvent.SPEECH, PodVoice.decide(vh, null).second)
        assertEquals(VEvent.NOISE, PodVoice.decide(vk, null).second)
        // المصنّف بيغلب: سعال
        assertEquals("cough", PodVoice.decide(vh, mapOf("Cough" to 0.7f, "Speech" to 0.1f)).first)
    }

    @Test fun shortBackchannelDoesNotSwitchCamera() {
        // أ يتكلم 20 ثانية، ب يقول "آه" نصف ثانية في المنتصف
        val an = analysis(20.0, listOf(0.0 to 20.0), listOf(8.0 to 8.5))
        val shots = PodEngine.autoShots(an, 2, false, true, st("balanced"), 0.0, 20.0)
        assertTrue("all shots should show person 0: " + shots.map { it.type to it.main }, shots.all { it.type == PodShot.FULL && it.main == 0 })
    }

    @Test fun realTurnSwitchesNearItsStart() {
        val an = analysis(20.0, listOf(0.0 to 10.0), listOf(10.5 to 20.0))
        val shots = PodEngine.autoShots(an, 2, false, false, st("balanced"), 0.0, 20.0)
        assertEquals(2, shots.size)
        assertEquals(0, shots[0].main)
        assertEquals(1, shots[1].main)
        assertTrue("cut at ~10.3: ${shots[1].start}", shots[1].start in 10.0..10.6)
        assertEquals(0.0, shots.first().start, 1e-6)
        assertEquals(20.0, shots.last().end, 1e-6)
    }

    @Test fun noTimerBasedSwitchDuringLongMonologue() {
        val an = analysis(120.0, listOf(0.0 to 120.0), listOf())
        val shots = PodEngine.autoShots(an, 2, true, true, st("balanced"), 0.0, 120.0)
        assertEquals(1, shots.size)
    }

    @Test fun overlapGivesSharedShot() {
        val an = analysis(20.0, listOf(0.0 to 12.0), listOf(8.0 to 20.0))
        val shots = PodEngine.autoShots(an, 2, false, false, st("balanced"), 0.0, 20.0)
        assertTrue(shots.any { it.type == PodShot.MULTI && it.start in 7.5..8.5 })
    }

    @Test fun interruptionShowsInFloatingBox() {
        // أ يحكي، ب بيقاطعه من 8 لـ 10 وأ مكمّل
        val an = analysis(20.0, listOf(0.0 to 20.0), listOf(8.0 to 10.0))
        val shots = PodEngine.autoShots(an, 2, false, true, st("balanced"), 0.0, 20.0)
        val pip = shots.firstOrNull { it.type == PodShot.PIP }
        assertNotNull("expected a floating box: " + shots.map { it.type to it.main }, pip)
        assertEquals(0, pip!!.main); assertTrue(pip.others.contentEquals(intArrayOf(1)))
        assertTrue(pip.start in 7.5..8.5)
        assertEquals(0, shots.last().main); assertEquals(PodShot.FULL, shots.last().type)
    }

    @Test fun longMonologueGetsBreaks() {
        // أ بيحكي 90 ث متواصل؛ كسر الملل كل 25 ث على المستمع
        val an = analysis(90.0, listOf(0.0 to 90.0), listOf())
        val shots = PodEngine.autoShots(an, 2, false, false, st().also { it.brk = 25; it.brkTo = "listener"; it.brkLen = 2.5f; it.firstHold = 0f }, 0.0, 90.0)
        val breaks = shots.filter { it.tag == "break" }
        assertTrue("expected breaks: " + shots.map { Triple(it.start, it.main, it.tag) }, breaks.size >= 2)
        assertTrue(breaks.all { it.main == 1 && it.dur in 2.0..3.0 })
        // كل دقيقتين ونص تقريبًا (مع عشوائية خفيفة): بـ ١٠ دقايق كلام ٣-٥ قلبات
        val an2 = analysis(600.0, listOf(0.0 to 600.0), listOf())
        val long = PodEngine.autoShots(an2, 2, false, false, st().also { it.brk = 150; it.brkTo = "listener"; it.brkVar = true; it.firstHold = 0f }, 0.0, 600.0).filter { it.tag == "break" }
        assertTrue("got ${long.size}", long.size in 3..5)
        assertEquals(0, shots.last().main)
        // بدون كسر الملل: لقطة وحدة
        val plain = PodEngine.autoShots(an, 2, false, false, st(), 0.0, 90.0)
        assertTrue(plain.none { it.tag == "break" })
    }

    @Test fun gridLayoutShowsEveryone() {
        val an = analysis(30.0, listOf(0.0 to 10.0), listOf(12.0 to 20.0))
        val shots = PodEngine.autoShots(an, 2, false, true, st().also { it.layout = "grid" }, 0.0, 30.0)
        assertEquals(1, shots.size); assertEquals(PodShot.MULTI, shots[0].type)
    }

    @Test fun lockedShotSurvivesRegeneration() {
        val an = analysis(30.0, listOf(0.0 to 30.0), listOf())
        val fresh = PodEngine.autoShots(an, 2, false, false, st(), 0.0, 30.0)
        val old = ArrayList(fresh.map { it.copy() })
        val lock = PodShot(10.0, 14.0, PodShot.FULL, 1, IntArray(0)).also { it.locked = true; it.tag = "manual" }
        val withLock = PodEngine.overlay(old, listOf(lock))
        val regen = PodEngine.regenerate(withLock, PodEngine.autoShots(an, 2, false, false, st("lively"), 0.0, 30.0), 0.0, 30.0)
        val l = regen.firstOrNull { it.locked }
        assertNotNull(l)
        assertEquals(10.0, l!!.start, 1e-6)
        assertEquals(14.0, l.end, 1e-6)
        assertEquals(1, l.main)
        assertEquals(0.0, regen.first().start, 1e-6)
        assertEquals(30.0, regen.last().end, 1e-6)
        for (i in 1 until regen.size) assertEquals(regen[i - 1].end, regen[i].start, 1e-6)
    }

    @Test fun partialRegenerationKeepsOutside() {
        val old = mutableListOf(
            PodShot(0.0, 10.0, PodShot.FULL, 1, IntArray(0)),
            PodShot(10.0, 20.0, PodShot.FULL, 1, IntArray(0)),
            PodShot(20.0, 30.0, PodShot.FULL, 1, IntArray(0)),
        )
        val fresh = listOf(PodShot(0.0, 30.0, PodShot.FULL, 0, IntArray(0)))
        val r = PodEngine.regenerate(old, fresh, 0.0, 30.0, 10.0, 20.0)
        assertEquals(1, r.first { it.start < 5 }.main)
        assertEquals(0, r.first { it.start <= 15 && it.end > 15 }.main)
        assertEquals(1, r.first { it.start <= 25 && it.end > 25 }.main)
    }

    @Test fun episodeAndReelSettingsAreIndependent() {
        val ep = PodProject("episode", PodProject.EPISODE)
        val reel = PodProject("reel_1", PodProject.REEL)
        assertEquals("off", ep.st.silence)
        assertEquals("balanced", reel.st.silence)
        reel.st.silence = "intense"
        reel.cuts.add(PCut(1.0, 2.0, PCut.SILENCE))
        assertEquals("off", ep.st.silence)
        assertTrue(ep.cuts.isEmpty())
        val back = PodProject.fromJson(JSONObject(reel.toJson().toString()))
        assertEquals("intense", back.st.silence)
        assertEquals(1, back.cuts.size)
        val copy = reel.copy()
        copy.st.silence = "light"
        assertEquals("intense", reel.st.silence)
    }

    @Test fun timeMapAfterCuts() {
        val segs = PodEngine.keepSegments(0.0, 10.0, listOf(PCut(2.0, 3.0, PCut.SILENCE), PCut(5.0, 6.0, PCut.TEXT)))
        val m = PodEngine.TimeMap(segs)
        assertEquals(8.0, m.outDur, 1e-6)
        assertEquals(3.0, m.toOut(4.0)!!, 1e-6)
        assertNull(m.toOut(2.5))
        assertEquals(4.5, m.toSession(3.5), 1e-6)
        assertEquals(7.5, m.toSession(5.5), 1e-6)
        val srt = PodEngine.srt(listOf(Triple(6.5, 7.0, "نص")), m)
        assertTrue(srt, srt.contains("00:00:04,500 --> 00:00:05,000"))
    }

    @Test fun silenceCutsKeepMargins() {
        val an = analysis(6.0, listOf(0.0 to 2.0, 4.0 to 6.0))
        val ts = PodEngine.markBackchannels(PodEngine.turns(an, 1))
        val cuts = PodEngine.silenceCuts(an, ts, "intense", 0.0, 6.0)
        assertEquals(1, cuts.size)
        assertTrue(cuts[0].a >= 2.0 + 0.09)
        assertTrue(cuts[0].b <= 4.0 - 0.07)
        assertTrue(PodEngine.silenceCuts(an, ts, "off", 0.0, 6.0).isEmpty())
    }

    @Test fun expressivePauseIsShortenedNotRemoved() {
        val an = analysis(8.0, listOf(0.0 to 3.0, 4.5 to 8.0))
        val ts = PodEngine.markBackchannels(PodEngine.turns(an, 1, joinGap = 0.5))
        val cuts = PodEngine.silenceCuts(an, ts, "balanced", 0.0, 8.0)
        assertEquals(1, cuts.size)
        val kept = 1.5 - (cuts[0].b - cuts[0].a)
        assertTrue("kept $kept", kept >= 0.55)
    }

    @Test fun zoomStaysOutOfSharedShotsAndIsSpaced() {
        val st = ProjSettings.reelDefaults().also { it.zoomMode = "auto"; it.intensity = "lively"; it.zoomMax = 1.12f }
        val shots = listOf(PodShot(0.0, 10.0, PodShot.MULTI, 0, intArrayOf(0, 1)), PodShot(10.0, 40.0, PodShot.FULL, 0, IntArray(0)))
        val keys = listOf(doubleArrayOf(2.0, 4.0), doubleArrayOf(12.0, 14.0), doubleArrayOf(15.0, 16.0))
        val z = PodEngine.autoZooms(shots, emptyList(), emptyList(), st, keys, 0.0, 40.0)
        assertTrue(z.none { it.a < 10.0 })
        for (i in 1 until z.size) assertTrue(z[i].a >= z[i - 1].b + 6.0)
        assertTrue(z.all { it.scale <= 1.12f })
        assertEquals(1f, PodEngine.zoomAt(z, 30.0), 1e-6f)
        assertTrue(PodEngine.zoomLimit(607.0, 1080.0, 1.15f) <= 1.24f)
        assertEquals(1f, PodEngine.zoomLimit(405.0, 1080.0, 1.15f), 1e-6f)
        assertTrue(PodEngine.autoZooms(shots, emptyList(), emptyList(), st.copy().also { it.zoomMode = "off" }, keys, 0.0, 40.0).isEmpty())
    }

    @Test fun lockedZoomIsKept() {
        val old = listOf(PZoom(5.0, 8.0, 1f, false, locked = true, auto = false, reason = "تثبيت"))
        val fresh = listOf(PZoom(6.0, 9.0, 1.1f, true), PZoom(20.0, 22.0, 1.1f, true))
        val r = PodEngine.mergeZooms(old, fresh)
        assertEquals(2, r.size)
        assertTrue(r.any { it.locked && it.a == 5.0 })
        assertTrue(r.none { it.a == 6.0 })
    }

    @Test fun captionChunksAreShortAndTimed() {
        val seg = TSeg(0, 10.0, 20.0, "مرحبا بكم في هذه الحلقة الجديدة، اليوم بدنا نحكي عن موضوع مهم جدا للناس كلها", 0, emptyList())
        val ch = PodEngine.captionChunks(listOf(seg), setOf("0:1"))
        assertTrue(ch.size >= 2)
        assertTrue(ch.all { it.text.length <= 50 })
        assertEquals(10.0, ch.first().a, 1e-6)
        assertEquals(20.0, ch.last().b, 1e-6)
        for (i in 1 until ch.size) assertEquals(ch[i - 1].b, ch[i].a, 1e-6)
        assertTrue(ch[0].emph.contains(1))
        assertTrue(ch[0].words.last().endsWith("،"))
    }

    @Test fun reelSuggestionFindsQuestionAndAnswer() {
        val an = analysis(120.0, listOf(10.0 to 14.0, 60.0 to 64.0), listOf(15.0 to 50.0, 65.0 to 110.0))
        val s = PodEngine.suggestReels(an, listOf("أحمد", "سارة"), emptyList(), 40.0, 5)
        assertTrue(s.isNotEmpty())
        assertTrue(s.any { it.type == "qa" && it.a <= 10.0 && it.b >= 49.0 })
    }

    @Test fun oopsMarkerLowersScore() {
        val an = analysis(120.0, listOf(10.0 to 14.0, 60.0 to 64.0), listOf(15.0 to 50.0, 65.0 to 100.0))
        val s = PodEngine.suggestReels(an, listOf("أ", "ب"), listOf(PMarker(30.0, "oops"), PMarker(80.0, "star")), 40.0, 5)
        val first = s.first { it.a <= 15 && it.b >= 30 }
        val second = s.first { it.a <= 65 && it.b >= 80 }
        assertTrue(second.score > first.score)
        assertTrue(first.reason.contains("غلطة"))
    }

    @Test fun undoRedo() {
        val u = UndoStack()
        u.push("a")
        u.push("b")
        assertEquals("b", u.undo("c"))
        assertEquals("a", u.undo("b"))
        assertFalse(u.canUndo())
        assertEquals("b", u.redo("a"))
    }

    @Test fun pingPongBecomesSharedShot() {
        // تبادل سريع جدًا: كل شخص يتكلم 1.5 ثانية بالتناوب
        val a = (0 until 10).filter { it % 2 == 0 }.map { it * 1.6 to it * 1.6 + 1.5 }
        val b = (0 until 10).filter { it % 2 == 1 }.map { it * 1.6 to it * 1.6 + 1.5 }
        val an = analysis(16.0, a, b)
        val shots = PodEngine.autoShots(an, 2, false, false, st("balanced"), 0.0, 16.0)
        assertTrue("expected a shared shot: " + shots.map { it.type }, shots.any { it.type == PodShot.MULTI })
        assertTrue("not ping-pong: ${shots.size}", shots.size <= 4)
    }

    @Test fun longSilenceShowsWideCamera() {
        val an = analysis(20.0, listOf(0.0 to 5.0, 12.0 to 20.0), listOf())
        val shots = PodEngine.autoShots(an, 2, true, false, st("balanced"), 0.0, 20.0)
        assertTrue(shots.any { it.type == PodShot.WIDE && it.start in 5.0..7.0 })
    }

    @Test fun monologueRegionDetected() {
        val an = analysis(60.0, listOf(0.0 to 60.0), listOf(20.0 to 20.4))
        val ts = PodEngine.markBackchannels(PodEngine.turns(an, 2))
        val r = PodEngine.regions(ts, 60.0)
        assertTrue(r.any { it.type == "monologue" })
        assertTrue(ts.any { it.p == 1 && it.backchannel })
    }

    @Test fun overlappingCutsMergeAndNearestMapping() {
        val segs = PodEngine.keepSegments(0.0, 10.0, listOf(PCut(2.0, 4.0, PCut.SILENCE), PCut(3.0, 5.0, PCut.TEXT)))
        assertEquals(2, segs.size)
        val m = PodEngine.TimeMap(segs)
        assertEquals(7.0, m.outDur, 1e-6)
        assertEquals(2.0, m.toOutNearest(3.5), 1e-6)
    }

    @Test fun captionsStayInSyncAfterSilenceCut() {
        // كلام 0–2 ثم صمت ثم كلام 4–6؛ القص المكثّف يحذف معظم الصمت
        val an = analysis(6.0, listOf(0.0 to 2.0, 4.0 to 6.0))
        val ts = PodEngine.markBackchannels(PodEngine.turns(an, 1))
        val cuts = PodEngine.silenceCuts(an, ts, "intense", 0.0, 6.0)
        val m = PodEngine.TimeMap(PodEngine.keepSegments(0.0, 6.0, cuts))
        val seg = TSeg(1, 4.0, 6.0, "الجملة الثانية هنا", 0, emptyList())
        val ch = PodEngine.captionChunks(listOf(seg), emptySet())
        // بداية الكتابة على النسخة المعدّلة = بداية الكلام على النسخة المعدّلة
        val speechOut = m.toOut(4.0)!!
        assertEquals(speechOut, m.toOut(ch.first().a)!!, 1e-6)
        assertTrue(speechOut < 4.0)
    }

    @Test fun lockedShotsSurviveMinShotAndTidy() {
        val l = PodShot(5.0, 5.5, PodShot.FULL, 1, IntArray(0)).also { it.locked = true }
        val list = listOf(PodShot(0.0, 5.0, PodShot.FULL, 0, IntArray(0)), l, PodShot(5.5, 20.0, PodShot.FULL, 0, IntArray(0)))
        val r = PodEngine.tidy(PodEngine.enforceMinShot(list, 2.0))
        assertTrue(r.any { it.locked && it.main == 1 && it.start == 5.0 })
    }

    @Test
    fun correctedTextReachesCaptions() {
        val seg = TSeg(0, 0.0, 3.0, "هاد غلط", 0, listOf(TWord(0.0, 1.0, "هاد"), TWord(1.0, 2.0, "غلط")))
        // مثل محرّر النص: تصحيح النص يمسح توقيت الكلمات القديم
        seg.text = "هذا صحيح تماما"; seg.words = emptyList()
        val ch = PodEngine.captionChunks(listOf(seg), emptySet())
        val all = ch.flatMap { it.words }.joinToString(" ")
        assertEquals("هذا صحيح تماما", all)
        assertTrue(ch.first().a >= 0.0 && ch.last().b <= 3.0 + 1e-6)
    }

    @Test fun firstHoldKeepsFirstAppearances() {
        val l = listOf(PodShot(0.0, 2.0, PodShot.FULL, 0, IntArray(0)), PodShot(2.0, 3.0, PodShot.FULL, 1, IntArray(0)), PodShot(3.0, 4.0, PodShot.FULL, 0, IntArray(0)),
            PodShot(4.0, 12.0, PodShot.FULL, 1, IntArray(0)), PodShot(12.0, 13.0, PodShot.FULL, 0, IntArray(0)), PodShot(13.0, 14.0, PodShot.FULL, 1, IntArray(0)))
        val r = PodEngine.firstHold(l, 5.0)
        assertEquals(0, r[0].main); assertTrue("first ${r[0].end}", r[0].end >= 5.0)
        assertEquals(1, r[1].main); assertTrue("second ${r[1].dur}", r[1].dur >= 5.0)
        // بعد البداية: عادي
        assertEquals(13.0, r.last().start, 1e-6)
        for (k in 1 until r.size) assertEquals(r[k - 1].end, r[k].start, 1e-6)
    }

    @Test fun unsplitNeverSplitsAndFollowsTalk() {
        // ٠-١٠ مشتركة: أ بيحكي لحد ٥، ب من ٤ لـ ١٠
        val ts = listOf(PodEngine.Turn(0, 0.0, 5.0), PodEngine.Turn(1, 4.0, 10.0))
        val l = listOf(PodShot(0.0, 10.0, PodShot.MULTI, 0, intArrayOf(0, 1)))
        for (m in listOf("stay", "talker", "alternate")) {
            val r = PodEngine.unsplit(l, ts, m, 2.0, 0.0, 1.5)
            assertTrue(m, r.none { it.type == PodShot.MULTI })
            assertEquals(m, 0, r.first().main); assertEquals(m, 1, r.last().main)
            for (k in 1 until r.size) assertEquals(r[k - 1].end, r[k].start, 1e-6)
        }
    }

    @Test fun speakerModeFollowsTheFloorOnly() {
        // أ ٠-١٠، ب بيعلّق "آه" ٤-٤٫٥ (ما بتنقل)، ب بيقاطع ٩-١٦ (أ لسا بيحكي لـ١٠ → بنستنى لـ١٠)، أ بيعلّق ١٢-١٢٫٦، أ بيرجع ١٦-٢٠
        val ts = listOf(PodEngine.Turn(0, 0.0, 10.0), PodEngine.Turn(1, 4.0, 4.5), PodEngine.Turn(1, 9.0, 16.0), PodEngine.Turn(0, 12.0, 12.6), PodEngine.Turn(0, 16.0, 20.0))
        val r = PodEngine.speakerShots(ts, 0.0, 20.0, 1.5, 1.5)
        assertTrue(r.all { it.type == PodShot.FULL })
        assertEquals(listOf(0, 1, 0), r.map { it.main })
        assertEquals(10.0, r[1].start, 0.01)
        assertEquals(15.85, r[2].start, 0.01)
        for (k in 1 until r.size) assertEquals(r[k - 1].end, r[k].start, 1e-6)
    }
}
