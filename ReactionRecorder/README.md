# Reaction Recorder — تسجيل Reaction / Commentary بدون مونتاج

تطبيق Android (Kotlin) لـ Samsung Galaxy M13: يسجّل **شاشة الهاتف (فيديو المصدر) + الكاميرا + الميكروفون + صوت الفيديو** ويدمجها **أثناء التسجيل** في ملف `Reaction_YYYY-MM-DD_HH-mm-ss.mp4` جاهز للنشر، يُحفظ في `Movies/ReactionRecorder/` ويظهر مباشرة في Gallery.

> **حالة الكود:** كُتب بالكامل لكنه **لم يُترجَم ولم يُختبر على جهاز** (بيئة العمل التي كتبته فيها لا تصل إلى Android SDK/Maven). عند أول بناء قد تظهر أخطاء ترجمة بسيطة — أرسلها لي وأصلحها فورًا. أما الاختبار الفعلي على M13 فلا بدّ منه (انظر آخر الملف).

---

## كيف تحصل على الـ APK (الأسهل أولًا)

### الطريقة 1 — GitHub Actions (بدون تثبيت أي شيء)
1. أنشئ مستودعًا خاصًا على GitHub وارفع محتوى هذا المجلد كما هو (يتضمن `.github/workflows/build-apk.yml`).
2. افتح تبويب **Actions → Build APK → Run workflow**.
3. بعد دقائق نزّل الـ artifact باسم `ReactionRecorder-apk` وفيه `app-release.apk` (موقَّع بمفتاح debug، قابل للتثبيت مباشرة).

### الطريقة 2 — Android Studio
1. افتح المجلد بـ Android Studio (Koala أو أحدث) وانتظر Gradle Sync.
2. **Build → Build Bundle(s)/APK(s) → Build APK(s)**، أو من الطرفية: `gradle :app:assembleDebug`.
3. الملف: `app/build/outputs/apk/debug/app-debug.apk`.

### التثبيت على M13
انسخ الـ APK للهاتف → افتحه → اسمح بالتثبيت من "مصادر غير معروفة" لتطبيق الملفات.

---

## تحليل القيود التقنية (ما يسمح به Android وما لا يسمح)

| السؤال | الجواب |
|---|---|
| هل يمكن التقاط **إطارات YouTube** مباشرة بدل تسجيل الشاشة؟ | **لا.** YouTube يعمل في عملية أخرى، ومخرجات فك الترميز لا تُكشف لتطبيق آخر (وبعضها محمي بـ DRM/`FLAG_SECURE`). لا يوجد API رسمي لذلك، ولم أستعمل أي التفاف. |
| إذن ما الحل؟ | **MediaProjection الرسمي**، لكن بمسار مُحسَّن: الشاشة تُرسَم مباشرة داخل `SurfaceTexture` (GPU) → Shader → المُرمِّز. **لا Bitmap، لا ByteBuffer على الـ CPU، ولا ملف شاشة وسيط**. التقاط الشاشة نفسه يُقلَّص إلى ≤1920px للضلع الطويل لتخفيف الحمل. |
| صوت الفيديو؟ | `AudioPlaybackCapture` (رسمي من Android 10). التطبيقات التي تمنع الالتقاط (أو المحتوى المحمي) تعطي صمتًا — هذا قيد من النظام ولا نتجاوزه. الحالة تظهر في التطبيق. |
| الصدى؟ | الميكروفون يلتقط السماعة. **السماعات الخارجية هي الحل الأفضل.** خيار Echo suppression يستخدم `VOICE_COMMUNICATION` + AEC/NS إن وُجدا. |
| أزرار عائمة فوق YouTube؟ | تحتاج صلاحية `SYSTEM_ALERT_WINDOW` فلم أطلبها. التحكم أثناء التسجيل من **إشعار التسجيل**: Stop / Pause / Mute. |
| Android 14+ | يطلب موافقة الالتقاط في كل جلسة؛ اختر **Entire screen** (وليس تطبيقًا واحدًا) كي يظهر YouTube. |

## Architecture

```
CameraX Preview ──► SurfaceTexture(OES) ─┐
                                          ├─► GL ES 2.0 Compositor ─► EGL window = MediaCodec input Surface ─► H.264/H.265
MediaProjection ──► SurfaceTexture(OES) ─┘     (crop/fit، دائرة/مدوّر/إطار داخل الـ Shader)                         │
                                                                                                                      ▼
Mic ─► AudioRecord ──┐                                                                                          MuxerHolder ─► MediaStore
Source audio (AudioPlaybackCapture) ┴► Mixer (gain/mute/clip) ─► AAC-LC ────────────────────────────────────────────┘
```

- **ساعة واحدة:** كل الـ PTS من `System.nanoTime()`؛ الصوت يُؤرَّخ بعدّ العيّنات مع تصحيح للأمام عند أي فجوة، والإيقاف المؤقت يُطرح من المسارين.
- **معدل إطارات ثابت:** الرسم بإيقاع fps ثابت حتى لو كانت الشاشة ثابتة.
- **الكاميرا والتسجيل داخل Foreground Service** (`mediaProjection|camera|microphone`) كي يستمر التسجيل وأنت داخل YouTube.
- **أمان الملف:** الـ Muxer لا يبدأ إلا بعد وصول كل المسارات (العيّنات المبكرة تُخزَّن)، والحفظ عبر `MediaStore (IS_PENDING)`، ومراقبة المساحة كل 5 ثوانٍ مع إيقاف آمن إذا امتلأ التخزين.

## الإعدادات الافتراضية لـ M13
9:16 • 1080×1920 • 30fps • H.264 • CBR ≈ 8 Mbps • كاميرا 720p. يتحقق التطبيق من قدرات المُرمِّز: إن لم يدعم الدقة/الـ FPS ينزل تلقائيًا ويُعلمك. 60fps خيار موجود لكنه موسوم ⚠.

## الميزات في الـ MVP
- Layouts جاهزة: Video+Cam (زاوية)، 70/30، Side by Side، Top/Bottom، Cam Full — مع **Custom** (اسحب/غيّر حجم كل من مربع الكاميرا ومربع الفيديو، وقرصة بإصبعين لتكبير الكاميرا) ومزلاج نسبة الفيديو للـ Presets المقسومة.
- شكل الكاميرا: Rectangle / Rounded / Circle + سمك الإطار + نصف القطر — **مدموجة في الناتج النهائي** (داخل الـ Shader) وليست Overlay.
- Portrait 9:16 و Landscape 16:9، تدوير الشاشة أثناء التسجيل مدعوم (تُعاد تهيئة حجم الالتقاط).
- كاميرا أمامية/خلفية، Mirror، دقة الكاميرا، Zoom، Exposure، Torch (تبديل الكاميرا أثناء التسجيل مُتاح).
- Mic/Source ON/OFF وVolume لكل منهما، Mute أثناء التسجيل، Pause/Resume.
- Countdown (Off/3/5/10)، عرض المساحة المتاحة والوقت المقدَّر وتحذير قبل التسجيل.
- شاشة النتيجة: Play / Share / Save / Delete.

## ما لم أنفّذه عمدًا (للاستقرار)
تركيز الكاميرا اليدوي (التركيز التلقائي المستمر يعمل)، اختيار FPS للكاميرا (يُترك للجهاز = 30)، أزرار عائمة، تسجيل بدون MediaProjection. أي ميزة قد تخاطر بتلف الملف أُجّلت.

## اختبارات مهمة على الجهاز (بالترتيب)
1. تسجيل 30 ثانية بلا صوت المصدر → هل الفيديو يُفتح وكل الأجزاء ظاهرة؟
2. تسجيل مع YouTube وسماعات → هل يظهر صوت الفيديو وصوتك؟ هل المزامنة جيدة؟
3. تسجيل 10 دقائق → راقب الحرارة وإسقاط الإطارات.
4. Pause/Resume، تدوير الهاتف، تبديل الكاميرا.
5. أرسل لي أي سلوك غريب ومعه وصفه وسأعدّل الكود.

## What's new (v1.1)

- **Two sources**: 📱 Screen (MediaProjection, e.g. to react to YouTube) or 🎞 Video file (plays a video from your phone, or a direct https link, *inside* the app — no screen capture; shown live while recording).
- YouTube links are refused in Video-file mode (no access to protected streams). Use Screen mode for those.
- **Source: Fit / Fill / Stretch** chip. Screen mode defaults to Fill (no black bars), Video mode to Fit.
- Camera orientation fix in the recorded output (auto-detect). If it is still sideways: Settings → "Camera rotation fix".
- Video mode settings: stop when the video ends, video audio delay (sync). Use headphones.
- The APK is now signed with a fixed key (`app/rr-debug.keystore`). **Uninstall the previous build once**; later updates install over it.

## v1.2 — live control, playlist, standby, dual camera

**Video file mode (no screen capture)**
- 📂 *Add*: pick several videos and/or images from the phone. 🔗 *Link*: direct https video links (YouTube is refused — use Screen mode for it).
- 📋 *List*: reorder, remove, jump to any item. Items **never** auto-play one after another: when one ends you get a *▶ Play next* button.
- During recording: ⏮ ⏪10 ⏯ 10⏩ ⏭, seek bar, ⟳ rotate 90°, 🔁 loop, *Move video* (drag = pan, pinch = zoom), ⛶ 100%, Fit/Fill/Stretch.
- ＋ *Import* while recording: add videos/images, show a file now, add a link. While the system file picker is open the **standby screen** is shown (can be turned off in Settings).

**Standby screen** (Settings): an image, a looping video, or a text screen. Tap ⏸ *Standby* during recording to show it (it is recorded); tap again to leave and return to where you were. Optional: mute the mic while it is on.

**Camera** (📷 button during recording): flip front/back, mirror, hide camera (cover something), and front+back together when the phone supports concurrent cameras (hidden/greyed otherwise).

**Audio**: 🎤 / 🔊 / 🔇 mute buttons during recording, and 🔈 *Speaker* = silent playback (the video's sound is recorded cleanly but not played from the speaker → no echo). A warning appears when no headphones are connected.

**Screen mode**: Settings → *Screen zoom / move* crops the captured screen so a video (e.g. YouTube) fills the frame.

## v1.3

- **Camera quality per camera** (Settings): front (default 720p) and back (default 480p), requested from the camera itself.
- **Dual camera**: the app now tries front + back together at the start, without asking. If the phone refuses (e.g. Galaxy M13), it falls back to one camera and shows a notice.
- **Back camera as a source**: `📷 Camera` menu / `🎥→📷` button shows the back (or front) camera in place of the video; the video pauses and resumes when you go back. After returning, the selfie box stays hidden until you tap `👁 Show selfie`.
- **Background removal for the selfie** (Settings): *Chroma key* (pick the colour, range, softness) or *Auto* (ML Kit selfie segmentation, runs on the phone, needs no internet).
- **Auto-save**: the video is saved to Movies/ReactionRecorder as soon as you stop; the dialog after recording is informational (Play / Share / Delete-with-confirmation).
- **Audio input** (Settings): Auto (USB mic first), Built-in only, or USB/external only; connected microphones are listed.
- **Full screen** while recording (`⛶ Full screen`) with side buttons: exit, play/pause, camera, mic.

- v1.3.1: audio mixer now uses its own steady clock (video audio no longer depends on the mic), tries several mic sources, and shows live 🎤/🔊 level bars (or "NO SIGNAL") while recording.

## v1.4 — Satellite devices (remote mics / cameras)

Other phones can act as extra microphones (audio only) or cameras (audio + video) for the main phone. Everything is in the same APK.

**Main phone**: `📡 Devices` → turn on *Accept remote devices*. It shows the address (IP) and a 4-digit PIN.
**Other phone**: `📲 Satellite` → enter the address + PIN, choose *Audio only* or *Audio + video* → Connect.
Both phones must be on the same Wi-Fi, or one phone's hotspot.

**Recording**: press record on the main phone as usual. Each connected phone starts recording **locally** (WAV 44.1 kHz 16-bit for audio, H.264 for video). Walking out of range or losing Wi-Fi does not stop it.
**After**: when a phone reconnects, its files are sent (resumable) and the main phone can mix. `📡 Devices` → session → *Mix audio* (fast; new file `…_mix.mp4`) or *Add device video* (plays your video together with the other phone's video, up to 2, and records the result in real time).
**Sync**: clocks are measured continuously with ping/pong and fitted with a linear regression (handles drift); pauses are cut out; a manual nudge per device is available in *Options*.

Limits: start must happen while connected; if the main phone stops while a device is disconnected, stop it manually on that phone; video latency compensation is approximate (80 ms default, adjust with nudge); keep the satellite phone's orientation equal to the chosen orientation.

## v1.5 — QR pairing, live preview, remote studio, rotation, up to many devices

- **QR pairing**: `📡 Devices` shows a QR code; on the other phone `📲 Satellite → 📷 Scan the QR code` fills the address and PIN (several addresses are tried in order).
- **Live preview + listening** (light: ~320 px JPEG at ~8 fps, 11 kHz audio): in `📡 Devices` → `🎛 Studio` per device, or `👁 Show on main screen` to get small thumbnails at the top of the main screen (tap a thumbnail to open its Studio). Preview is only a monitor — the real recording stays local on the other phone in full quality, so a dropped connection never affects it.
- **Remote studio** for each device (before and during recording): front/back camera, 480/720/1080p, portrait/landscape (locked while recording), rotate 90°/180°, mirror, flash, zoom, brightness, mic gain and source.
- **Rotation**: `↻ Rotate cam` (selfie camera, before and during recording) and `⟳ Rotate video` (video/screen) chips on the main screen; per-device video rotation, position (6 slots + auto) and size in session `⚙ Options`.
- **Many devices**: any number can connect and record (audio is mixed from all). Up to 3 device videos can be placed in the final video at once (decoder limit on mid-range phones); choose which in Options (“Use its video”).

- v1.5.2: network messages sent from the screen (Studio, preview, fetch files, settings) now go out on a background thread (Android blocked them silently); PIN is always Latin digits; the hub switch shows a clear error if it cannot start; satellite no longer drops a recording when "send" arrives while it is still saving; the satellite screen no longer steals the camera from the service while connected; more accurate clock sync while previewing.

## v1.6.0 — 🎙 Podcast mode (batch 1)

- **Setup**: 1–4 people, each with a camera and a microphone (any connected phone, or this phone via "This phone"), plus optional wide camera(s). Light live monitor with thumbnails and mic levels.
- **Recording**: every phone records locally in full quality (same system as remote devices); this phone records through an internal connection, so everything stays in sync.
- **Automatic edit**: who is talking is detected per mic by comparing all mics (so one person's voice leaking into the other mic is ignored). Rules: the speaker is shown full screen; a reply first appears in a floating box and becomes full screen if it continues; overlapping talk → split screen or boxes on your background; silence / long shots → wide camera. Short sounds ("uh", "hmm") are ignored. Pace presets Fast / Medium / Calm or fully custom.
- **Timeline**: lanes showing when each person talks + the shots; tap any moment to change who is shown, split, or merge. Voice sensitivity and audio options (even out levels, lower silent mics).
- **Export 16:9** (720p/1080p) with soft transitions, mixed audio from all mics. Only the cameras visible at each moment are decoded, which keeps it workable on mid-range phones.
- Next batches: vertical 9:16 export, Shorts/Reels tool with silence cutting and highlight suggestions.

## v1.7.0 — Podcast: vertical 9:16, Shorts/Reels, silence cutting

- **Export 16:9, 9:16 or both** for the full episode. In 9:16 every camera is cropped to fill the tall frame, several people are stacked top/bottom (4 → 2×2), and the floating box sits above the bottom area used by Shorts/Reels buttons.
- **🎯 Framing**: per camera, move the 9:16 crop left/right with a live preview of the frame.
- **Cut silences between words** (option for the full episode and for clips) with short fades so cuts are not heard.
- **📱 Shorts / Reels**: suggests the liveliest moments (many turns, overlaps, little silence) for 15–90 s clips; move/resize each clip, add your own by time, then export vertical with a fast edit (or your own edit) and silence cutting.

## v1.8 — مونتاج ما بعد التسجيل (حلقة كاملة / ريلز)
- مساران منفصلان: «الحلقة الكاملة» و«الريلز»؛ كل ريل مشروع مستقل في `proj/<id>.json`، وكل التعديل غير إتلافي (الملفات الأصلية لا تُلمس).
- محرك قرارات (`PodEngine.kt`) مختبر بـ JUnit: تبديل على تبادل الكلام فقط، تجاهل التعقيبات القصيرة، لقطة مشتركة عند التداخل، قص صمت بمستويات مع هوامش، زوم محافظ بحدود الدقة، اقتراح ريلز، كتابة RTL و SRT بعد القص.
- تثبيت اللقطات/القص/الزوم، تراجع/إعادة، حفظ تلقائي، نسخ سابقة ومقارنة، معاينة 20 ثانية بنفس قرارات التصدير، علامات «لحظة مهمة» و«غلطة».
- التفريغ وفهم المعنى عبر خدمة خارجية **اختيارية** بمفتاح المستخدم وبعد موافقة صريحة؛ لا مفاتيح داخل الـ APK ولا رفع تلقائي.
- v1.8.1: إصلاحات مراجعة (حفظ الزوم خارج مدى إعادة التوليد، تعطّل حدود الريل، وصول تصحيح النص إلى الكتابة، معاينة قرب النهاية، فيديو مُدوَّر، سباق إيقاف الخدمة، الحساب الثقيل خارج واجهة المستخدم).

## v2.0 — واجهة جديدة، لغتان، واتصال لا يضيع
- شاشة رئيسية بأربع بطاقات: تسجيل جديد، انضمام لتسجيل، تسجيلاتي، الإعدادات. أقسام تنطوي بدل الصفوف المزدحمة، وانتقالات ناعمة.
- عربي / English من الإعدادات (اتجاه الواجهة يتبع اللغة).
- الاقتران: باركود v2 يحمل هوية الجوال الرئيسي؛ بعد المسح يتصل تلقائيًا. الجوال الرئيسي ينادي على الشبكة كل ثانيتين، فيرجع الجهاز المساعد لحاله حتى لو تغيّر العنوان أو المنفذ.
- الملفات: الجهاز المساعد يعلن تسجيلاته غير المرسلة عند كل اتصال فتُطلب تلقائيًا، الإرسال يكمل من حيث انقطع، ولا يُحذف شيء إلا يدويًا. بديل يدوي: حفظ في التنزيلات ← استيراد في الجوال الرئيسي.
- مزامنة بالصوت (FFT) لأي جهاز بلا قياس ساعة، وتوحيد مدى الجلسة (الكل / المشترك) عندما تتوقف الأجهزة بأوقات مختلفة، مع خيار ما يظهر في الفراغ (كاميرا أخرى / آخر صورة / بطاقة باسم).
- بلا قص مؤذٍ: الصورة كاملة مع خلفية مموّهة بدل الأسود (الرياكشن والمونتاج)، واتجاه تلقائي يثبت عند بدء التسجيل.
- أوضاع جديدة: مايك لاسلكي بشاشة بسيطة، شاشة ستوديو بمراقبة خفيفة جدًا، وأشخاص مثبّتون ظاهرون طول الوقت.

## v2.1 — فلوق، مشاركة التطبيق، والتفعيل
- **🎥 فلوق** (بطاقة مستقلة في "تسجيل جديد"): جوالك الكاميرا الرئيسية، وصحابك مايك أو كاميرا+مايك. كل جوال يقلب كاميرته يدويًا أثناء التسجيل دون توقف. اختيار الكاميرا الظاهرة باللمس أثناء التصوير يُحفظ كقرار مونتاج. أشكال: تبديل (اختياري/تلقائي على المتكلم)، الكل ثابت، أنا بملء الشاشة + مربعات. إطار مضيء حول من يتكلم. ملء لحظة القلب: تجميد/ضبابية/كاميرا أخرى.
- **باركود واحد لوظيفتين**: يفتح صفحة تنزيل التطبيق من جوالك عبر الهوتسبوت (بدون إنترنت)، ونفس الباركود يقرنه بك.
- **التفعيل**: النسخ غير المفعّلة تعرض "انضمام" فقط. صاحب التطبيق (برمز المالك مرة واحدة) يفعّل الأجهزة (يوم افتراضيًا) ويقفلها 🔓/🔒، وتطبيقه يُقفل بالبصمة كل 24 ساعة (ساعة داخلية) وبعد إعادة التشغيل. حماية ضد المستخدم العادي، وتعمل بدون إنترنت.
