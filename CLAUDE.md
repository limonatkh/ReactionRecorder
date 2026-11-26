# ReactionRecorder — notes for Claude

## Who and how
- The owner writes in Palestinian Arabic. **Always reply in Palestinian Arabic**, short and plain, no jargon.
- He often wants an explanation / proposal **before** any change ("ما تعدل، قلي شو منيح"). When he asks for an opinion, answer first and don't edit.
- Be honest about what was tested and what wasn't. He wants everything simulated/tested before delivery when possible.
- Target phone: Samsung Galaxy M13. Laptop: Windows.

- History, decisions and current state from the earlier cloud chat: `docs/HANDOFF.md`.

## Repo layout
- `ReactionRecorder/` — the Android app (Kotlin, views built in code, no XML layouts). Main sources in `ReactionRecorder/app/src/main/java/com/reactionrecorder/`.
- `desktop/` — (planned) the Windows control-room app. See `docs/DESKTOP_PLAN.md`.
- `ux/` — emulator tour (`tour.py`), report checks, browser-join test.
- `.github/workflows/build.yml` — builds the APK and publishes it as a **draft** release (drafts are visible only to the owner).
- `.github/workflows/ux.yml` — full emulator tour (~1.5 h). Results go to a draft release `ux-N` (screenshots, logs, exported videos).
- `secrets.enc` — encrypted bundle with `rr-debug.keystore` (app signing key) and `secret.properties` (owner key data). The workflow decrypts it with the repository secret `RR_KEY`. **Never commit the keystore, `secret.properties`, or the owner code.** Without them a local build still works (debug signing, no owner key), but it won't install as an update over the owner's app.
- The repo is **public**. Anything committed is visible to everyone.

## Build
- CI: `gh workflow run build.yml --ref claude/apk-build`, then download the APK from the newest draft release.
- Local (Windows, Android Studio or `gradlew` inside `ReactionRecorder/`): `gradlew :app:assembleDebug`. To get the real signature locally, decrypt `secrets.enc` into `ReactionRecorder/app/` with the `RR_KEY` password: `openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in secrets.enc | tar -xf - -C ReactionRecorder/app`.
- Bump `versionCode` / `versionName` in `ReactionRecorder/app/build.gradle.kts` for every delivered build.
- Unit tests: `gradlew :app:testDebugUnitTest` (layout, templates, planner, sync…).

## Product rules the owner set
- **No floating boxes** (picture-in-picture) in podcast or vlog, in templates or anywhere. Reaction and explainer modes are different and keep theirs.
- Split screen follows the "flip method" (doc: https://claude.ai/artifact/YKLWx9F1ERtNbEvz3Txhrm + `SplitLayout` in `PodLayout.kt`): each person gets the biggest box in the safe range 3:4–4:3 so the crop never ruins them; leftover space is background; side-by-side in landscape becomes stacked in portrait; person 1 is first in reading order (right, top) and positions never change when the speaker changes; optional "main person bigger" (`PodTemplate.multiMain`).
- Podcast templates and vlog templates are separate (`PodTemplate.mode`). Vlog has two built-ins: "بدون مونتاج" and "ثابت — كل الكاميرات", both portrait by default.
- Final stop = hold 1 second. Vlog has pause (paused parts are auto-cut, recoverable in the edit).
- Phones join as **wireless mic** by default; the admin can switch any phone to camera / camera+mic.
- Preview from devices is **not** a live stream: a small snapshot every 2–5 s, so recording quality is never affected.
- No limit on the number of devices or mics.

## Architecture in one paragraph
One device is the **hub** (`SatHub`, TCP port 47600, PIN + QR pairing). Every recording device — including the hub phone itself via 127.0.0.1 — runs `SatelliteService`, records locally at full quality (camera through `Compositor` + `SatVideoRec`, audio through `SatAudioRec` WAV), and uploads files to the hub after stop. The hub keeps a session (`SessionStore`), clocks are aligned (`Finalizer.SatVideoTime`, audio cross-correlation in `PodSync`), then `PodStudio` builds projects (shots, cuts) and `PodcastRenderer` exports with GLES. Messages are JSON lines with a `"t"` type — see `docs/PROTOCOL.md`.
