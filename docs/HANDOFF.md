# Handoff — cloud chat → Claude Code on the laptop

Written 2026-11-26 at the end of the long cloud chat (which ran from the phone). Read `CLAUDE.md` first; this file adds the history and the current state. Owner = the app's owner, who writes in Palestinian Arabic. His quotes are given in Arabic with a short English gloss.

## 1. Goal
We were building ReactionRecorder (Android): a multi-phone podcast / vlog recorder with automatic editing (templates, split screen, export). The latest work was 5.8.1 (exports save themselves and each project has a list of its exports, plus preview fixes). We also planned a Windows control-room ("desktop") app, which is not started yet.

## 2. Final decisions (and why)
- **No floating boxes (PiP) in podcast or vlog, anywhere.** He asked for this word for word: «اوعى تعمل … مربع عائم، ما بدي في كل البرنامج مربع عائم» (“don't make a floating box, I don't want one anywhere in the app”). Reaction and explainer modes keep theirs: «لا الريئاكشن والشرح غير» (“no, reaction and explainer are different”).
- **Split screen = "flip method", safe range 3:4–4:3, not strict squares.** He rejected strict squares: «هيك انت بتنفذ حرفياً … القانون انه القص ما يفسد الشخص … وما يكون في مساحة كبيرة متاحة … عشان اقدر اكبر المربعات» (“you're applying it too literally… the rule is that the crop never ruins the person, and no big unused space… so the boxes can be bigger”). Priority = a correct crop; gaps between boxes are fine.
- **"Main person bigger" option**, with secondary people around them, up to 9 people (`PodTemplate.multiMain`, used when n ≥ 3).
- **Apply the method in every template (podcast and vlog). Vlog default templates are vertical**: «طبّق النظرية هذه في قوالب المنتاج في كل التطبيق، في الفلوق والبودكاست. وخلي قوالب الفلوق الديفولت معمولة طولية» (“apply this in all editing templates, vlog and podcast, and make the default vlog templates vertical”).
- **Uncropped side-by-side (two cameras shown whole, letterboxed) must not exist anywhere**: «ما بدي أصلاً تكون هذه الطريقة متاحة في البرنامج» (“I don't want this method available in the app at all”). Done in 5.8.1: split cells always crop.
- **Podcast and vlog templates are separate.** Vlog has two built-ins: "بدون مونتاج" (no editing) and "ثابت — كل الكاميرات" (fixed, all cameras).
- **Phones join as wireless mic by default.** The admin can switch any phone to camera or camera+mic. A 2nd/3rd mic can be added.
- **Vlog: pause + final stop by holding 1 s.** Paused parts are cut automatically and can be recovered in the edit.
- **Screen can be turned off while the mic keeps recording, with clear feedback**: «خليلي اقدر اطفي الشاشة ويضله التسجيل في الميك شغال، لاني مش عارف هو شغال ولا لأ» (“let me turn the screen off and keep the mic recording, because I can't tell whether it's working”). Done on Android with a vibration on start/stop and a timer notification. A true screen-off setting is planned for desktop phase 4.
- **The back button replaces the × exit button, at the position he marked on his video** (5.7.1).
- **Exports save to the gallery automatically, and each project has a list of its exports**: «خليه يحفظ اوتوماتيك في الجهاز، ويعطيني قائمة التصديرات لكل مشروع مفتوح» (“make it save to the device automatically and give me a list of exports for each project”). Reason: before this, a video was published only if he was in the app to tap save.
- **The preview must match the final video.** A person's name shows only where the full video shows it. The red safe-area lines are hidden by default, with a toggle: «شيللي المساحات الحمرة … يا حط في الاعدادات اشيلها ولا أخليها» (“remove the red areas, or put a setting to hide or keep them”).
- **The repo is public** (GitHub Actions minutes ran out while it was private). The history was cleaned first, and the secrets moved to `secrets.enc` + the repo secret `RR_KEY`.
- **Desktop app: Windows laptop, all the suggestions, no limit on devices or mics, true screen-off (not dimming) as an optional setting**: «اهم اشي ما يكون في لمت لعدد المايكات والأجهزة … ايقاف للشاشة مش تعتيم … يكون في الاعدادات لو بدي او لأ» (“most important: no limit on mics and devices… screen off, not dimming… as a setting, on or off”).
- **Device previews are snapshots every 2–5 s, not a live stream**, so recording quality is never affected (desktop plan).
- **Work moves to Claude Code on the laptop, in two separate conversations**: one for desktop, one for Android, to avoid mix-ups. Reason: local builds, installing on the real phone over USB (adb), and testing with the laptop's real mics.
- **Use the real phone over USB instead of a local emulator.** The cloud `ux.yml` emulator tour stays available.
- **Folder layout on the laptop:** `C:\Projects\ReactionRecorder` (one subfolder per project under `C:\Projects`).

## 3. Product rules (must / must not)
- Must not: floating boxes in podcast/vlog/templates; uncropped "fit" images inside split cells; a limit on devices or mics; committing the keystore, `secret.properties`, the owner code or any password (the repo is public).
- Must: each person gets the biggest box in 3:4–4:3. Leftover space is background. Side-by-side in landscape becomes stacked in portrait. Person 1 is first in reading order (right, top). Positions never change when the speaker changes.
- Must: final stop = hold 1 s (`HoldButton`, `holdMs = 1000`).
- Must: the vlog camera is fullscreen with controls (zoom, tap focus, torch, exposure, flip, own mic on/off). The status text at the top right has no background (he gave the Speed stream overlay as the example).
- Must: vlog role per phone, set by the admin.
- Must: in the export, the vlog start doesn't begin with black seconds (auto cut from the first camera frame).
- Must: the preview shows only what the full video shows (names, layout).
- Must: every UI text is in Arabic. Reply to the owner in Palestinian Arabic, short and plain. When he asks for an opinion, answer before changing anything.
- Must: be honest about what was tested. He prefers simulation/tests before delivery.
- Must: bump `versionCode` / `versionName` for every delivered build.

## 4. Technical details
**Repo / build**
- `github.com/limonatkh/ReactionRecorder`, branches `main` and `claude/apk-build` (both carry the same commits). Android source is in `ReactionRecorder/app/src/main/java/com/reactionrecorder/`.
- Current version: **5.8.1, versionCode 65** (`ReactionRecorder/app/build.gradle.kts`, which needs `import java.util.Properties` at the top).
- CI: `build.yml` → draft release `vX.Y.Z-buildN` with the APK. `ux.yml` → draft release `ux-N` with `ux-shots.zip` (screenshots, `log.txt`, `export1.mp4`, `export2.mp4`, `exports.txt`). The ux job limit is about 2 h. The emulator uses `-camera-back emulated -camera-front emulated`.
- Signing: `secrets.enc` (openssl aes-256-cbc, pbkdf2, iter 200000) holds `rr-debug.keystore` + `secret.properties` (OWNER_SALT/IV/ENC, STORE_PASS, KEY_PASS, KEY_ALIAS). Expected cert SHA-256 of the release APK: `0DEB752F6F9AF985B0C652BA12028B217C730EC29D09416B8E0993043204CAF5` (a public value, not a secret). `License.kt` reads `BuildConfig.OWNER_*`.
- The UX_TEST build (`-PuxTest=true`) is for tests only. Never deliver it.

**Layout (flip method)** — `PodLayout.kt` → `object SplitLayout`
- `AMIN = 0.75f`, `AMAX = 4f/3f`. `equal(n, x, y, w, h, g, dir)` and `mainPlus(k, x, y, w, h, g)`. RTL order. `who` is sorted so positions stay stable.
- The renderer grid gap is `g = max(gap, short*0.015)`. With a background texture and more than 2 people, the split background uses `coverBg`.
- The "people" multi mode (one tile per person, or half a camera when 2 people share one) now also goes through `grid(...)` = SplitLayout (5.8.1).
- Used in `PodcastRenderer.kt`, `PodDemo.kt` and `TemplateActivity.kt` (mode chips, sizes option, demo names, `landscape = kind != "reel" && orient != "v"`). Unit test: `SplitLayoutTest.kt`.

**Crop rule in cells (5.8.1)**
- In `PodcastRenderer` the `fit` decision is `cell -> false` (in both places, also `faceRectIn`). In `PodDemo`: `if (faceZoom > 0f || pipBoxNow || cell) return false`.
- Cause of the bug he saw: a landscape camera (16:9) in a cell near 4:3 gave ratio ≈ 1.33 > 1.3, so the "smart" frame chose fit. Crowd cameras used fit when the ratio was > 1.15. They now crop on the faces or the group (`groupCrop`), or a centre crop when no face is found.
- `frame = "fit"` ("الصورة كاملة", the whole picture) still exists for single full-screen shots only.

**Templates / projects**
- `PodTemplate.mode` = "podcast" / "vlog". `TemplateStore.mode` is global. Built-ins `vlog_plain`, `vlog_split` (+ `_reel`), `orient "v"`. PiP forced false. `PodShot.unfloat()` turns PIP into MULTI/FULL. `Podcast.usePip` is false.
- `PCut.PAUSE` cuts. `PodStudio`: `Data.pauses`, `vFirst`/`videoFirstSec` (from `Compositor.firstCamNs` → meta `"first"`). Vlog template choice by `setup.vlogLayout` ("tpl" / "plain" / "grid").

**Vlog / devices**
- `VlogActivity`: zoom, focus via `afx`/`afy` (0..1), torch, exposure, flip, my-mic, pause via `PodRec.pause/resume/pauseList` (`elapsedSec` excludes the pause), `HoldButton` 1 s, device roles via the `cfg` `role` message.
- `aSrc`: 0 natural, 1 surroundings, 2 voice-only (`VOICE_COMMUNICATION` + `NoiseSuppressor`). `tuneCamera`: 30 fps AE range, stabilization via `Camera2Interop`.
- `SatelliteService`: vibrates on start/stop, `recSince`, notification with a chronometer, remote role switch. `JoinActivity` defaults to mic and asks for the camera permission. `SatelliteActivity`: `offNote`, aSrc chips, recreates itself on a role change. `SatHub`: `cfgs` updates `link.role`. `SessionActivity`: warns about a mismatch only with ≥ 2 devices.
- Hub/protocol: TCP 47600, PIN + QR, JSON lines with `"t"`. The full table is in `docs/PROTOCOL.md`.
- Preview: `SatHub.setPreview(id, video, audio, level, iv = 125, px = 320)`; the device clamps `iv` to 80–5000 ms. A selected device uses 150 ms at 480/640 px. A role snapshot is 240 px. (So the phone hub's preview is fast, about every 125 ms, while the 2–5 s rule was set for the desktop; see section 8.)

**Export (5.8.1)**
- `PodcastExportService.kt`: `PodState.held = act == ACTION_EXPORT && forceH > 0`, so only the light 360p "watch" copy is held (pending MediaStore item). A real export is published by the renderer at the end (`hold = false`).
- On success: `ExportLog.add(ctx, sid, projId, uri, vert, p.st.height)`, `PodState.saved = done`, and `SessionStore.mergedUri` for "episode". When the export finishes, a notification says it was saved to the gallery, or failed, and tapping it plays the video.
- `ExportLog` (end of `PodProject.kt`): file `<ProjectStore.dir(sid)>/<proj>.exports.json`, entries `{u, v, h, t}`. `list()` is newest first and drops entries whose `openFileDescriptor` throws `FileNotFoundException`. There is also `remove()`.
- UI: `PodUi.exportList(a, box, sid, proj, onChange)` (in `PodcastActivity.kt`) shows play / share / delete with a confirm. It is shown in the editor's export tool under «📂 تصديرات هالمشروع» (“this project's exports”). `onJobDone` calls `buildBody()` when `tool == "export"`.
- `PodUi.results()` shows «✓ خلص وانحفظ بالمعرض لحاله» (“done, saved to the gallery automatically”). Gallery path: `Movies/ReactionRecorder` (`StorageUtil.createPending/publish/delete`).

**Preview (5.8.1)**
- `RenderJob.nameShots: Set<Int>?`: computed in `PodStudio.buildJob` when `tpl.names == "first"`, from the full kept segments (`keepSegs`): the first FULL/PIP shot of each person. `drawName` uses it, so a preview from mid-video shows only the names the full render shows.
- `RenderJob.guides`: `drawGuides()` runs only if `job.preview && job.guides`. The pref is `getSharedPreferences("pod_ui").getBoolean("guides", false)`, with a toggle in the editor's «المزيد» (“More”) tool.
- The editor snapshots (rollback versions of the edit, not videos) were renamed «↩ نسخ احتياطية للمونتاج» (“↩ edit backups”), with an explanation in `versionsDialog()`.

**Other Android files touched in this chat:** `Ui.kt` (`Ic.FLASH/SUN/PHONE`, `HoldButton`, `QuietText`), `PodcastActivity.kt` (session screen redesign: stat pills, cameras/mics groups, quality, storage in hours, yellow start-for-all button), `SatRec.kt`, `Compositor.kt`.

**Docs:** `CLAUDE.md`, `docs/PROTOCOL.md` (protocol v1), `docs/DESKTOP_PLAN.md` (Arabic, 5 phases, protocol v2 additions), the flip-method write-up at https://claude.ai/artifact/YKLWx9F1ERtNbEvz3Txhrm.

## 5. Status
**Done and tested**
- 5.8.1: CI build OK, signature matches. Emulator tour `ux-32`: two exports saved themselves in about 40 s and 60 s, and the export list showed them both times. Frames of `export1.mp4` show the split cells cropped, not shown whole. (The emulator cameras show a colour test pattern, not people.)
- 5.7.1: emulator tour passed (delivered inside 5.8.0).
- Delivered to the owner: 5.6.1, 5.7.0, 5.8.0, 5.8.1.

**Written but not tested**
- 5.8.1: face crop with real people, the "saved" notification, deleting from the export list, preview names from mid-video, the red-lines toggle, the new edit-backups wording. None was checked on a real phone. (Not confirmed whether the ux tour opens these screens.)
- 5.8.0 changes (session screen redesign, SplitLayout everywhere): unit tests + build only before 5.8.1; then covered partly by the ux-32 tour (not confirmed exactly which parts).
- Vlog screen-off behaviour on the Samsung M13: not tested on the device.

**Not started**
- The desktop app (`desktop/` doesn't exist yet). A start prompt was given to Claude Code on the laptop (phase 1, branch `desktop`, don't touch `ReactionRecorder/`). Its progress is unknown here.
- Protocol v2 additions on Android (`afLock`, `aeLock`, `awb`, `focusDist`, `fps`, `lens`, `screenOff`, `saver`, `restartCam`, `name`; `caps`/`lvl` extras; `mark`, `check`).
- The Android Claude Code setup on the laptop (local build, adb install over USB).

## 6. Known problems and what we tried
- **Exports not appearing**: they were held as pending MediaStore items until a manual «احفظه بالمعرض» (“save to gallery”) tap. Fixed by auto-publishing (5.8.1).
- **Export list stayed empty after an export**: the list was built before the job ended. Fixed by rebuilding the body on `onJobDone`, and by using `openFileDescriptor` instead of a query.
- **ux tour hit the 2 h limit**: it waited 25 min per export for the removed save button. `export_and_wait` now waits for the file count to rise and then looks for "🎬" in the list.
- **ux log has many `!! not found` lines** (permission dialogs, optional buttons). These are believed to be normal fallbacks (not confirmed).
- **Maven Central 429** can fail a build: just rerun.
- **uiautomator "idle" failures in fullscreen vlog**: fixed by stopping hidden level/text updates when fullscreen, plus position fallbacks in `tour.py` (`tap_desc` with xy).
- **The template chip "هادئ" (“calm”) has a second text line**: `chip_of()` matches by prefix.
- **Kotlin smart cast on the `offNote` var**: fixed with a local `val`.
- **Strict squares were rejected** by the owner: replaced by the 3:4–4:3 safe range.
- **Actions minutes ran out while the repo was private**: the repo was made public after cleaning. The cloud session could not delete old releases/tags or set secrets, so the owner added `RR_KEY`. About 85 old releases still exist; the owner chose not to delete them (not confirmed as final).
- The light 360p copy stays a pending MediaStore item, and Android may auto-delete pending items after some days (not confirmed).
- Google download hosts were blocked from the cloud shell. This doesn't apply on the laptop.

## 7. Next steps (in order)
1. Desktop conversation: phase 1 from `docs/DESKTOP_PLAN.md` (hub on Windows, QR/PIN, the current Android app joins unchanged, preview grid with status). Test with a fake device script first, then the real M13. Firewall rule for port 47600 on private networks. Stop and report before phase 2.
2. Android conversation on the laptop: decrypt `secrets.enc` locally (password from the owner, never committed), `gradlew :app:assembleDebug`, install on the M13 with adb over USB, read logcat.
3. Real-phone check of 5.8.1: an export with the app closed, the notification, the list (play/share/delete), split crop with real faces, preview names, the red-lines toggle.
4. Desktop phases 2–5 (recording + laptop mics with naming and pairing to cameras, full camera/mic control + protocol v2 on Android, battery/true screen-off/alerts, hand-off to the phone for editing).
5. Later: move the shared protocol/session code into `shared/`, coordinated between the two conversations.

## 8. Open questions (not decided)
- Screen off during recording: "immediate" (Device Admin `lockNow`) or "after 10 s" (system timeout change), and which is the default? Does the camera keep recording with the screen off on Samsung?
- Preview rate: the desktop plan says 2–5 s chosen by the user, but the phone hub currently previews about every 125 ms when a strip is on. Should the phone hub follow the 2–5 s rule too? (Not discussed.)
- Should `frame = "fit"` for single full-screen shots also be removed? (He banned it for side-by-side only.)
- Should the light 360p copy also be saved or listed, or stay a temporary watch copy?
- Delete the old releases or not.
- Editing on the desktop: a later phase, scope not decided.
