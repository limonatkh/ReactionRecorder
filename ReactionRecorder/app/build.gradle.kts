import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// بناء خاص لفحص الواجهة على محاكي (ما بيتنشر أبدًا): بدون تفعيل/بصمة، ومع مكتبات x86
val uxTest = (project.findProperty("uxTest") as String?) == "true"

// الأسرار (مفتاح التوقيع وبيانات مفتاح المالك) ما بتنحفظ بالمستودع: بتوصل للبناء من سرّ GitHub (RR_KEY)
// اللي بيفك secrets.enc لـ app/secret.properties و app/rr-debug.keystore. بدونها: توقيع تجريبي وبدون مفتاح مالك.
val secretProps = Properties()
file("secret.properties").takeIf { it.exists() }?.inputStream()?.use { secretProps.load(it) }
fun sec(k: String) = (secretProps.getProperty(k) ?: "").replace("\"", "")
val hasKeystore = file("rr-debug.keystore").exists() && sec("STORE_PASS").isNotEmpty()

android {
    namespace = "com.reactionrecorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reactionrecorder"
        // 29 = أول إصدار يدعم AudioPlaybackCapture (التقاط صوت التطبيقات الأخرى)
        minSdk = 29
        targetSdk = 34
        versionCode = 64
        versionName = "5.8.0"
        // الجوالات الحقيقية فقط (ARM): يحذف مكتبات المحاكيات x86 ويصغّر الحجم كثيرًا
        if (!uxTest) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("boolean", "UX_TEST", uxTest.toString())
        buildConfigField("String", "OWNER_SALT", "\"${sec("OWNER_SALT")}\"")
        buildConfigField("String", "OWNER_IV", "\"${sec("OWNER_IV")}\"")
        buildConfigField("String", "OWNER_ENC", "\"${sec("OWNER_ENC")}\"")
    }

    // ضغط المكتبات الأصلية داخل الـ APK (تنزيل أصغر بكثير)
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // مفتاح توقيع ثابت داخل المشروع: كل بناء جديد يثبَّت فوق القديم دون حذف
    signingConfigs {
        if (hasKeystore) create("fixed") {
            storeFile = file("rr-debug.keystore")
            storePassword = sec("STORE_PASS")
            keyAlias = sec("KEY_ALIAS").ifEmpty { "rrkey" }
            keyPassword = sec("KEY_PASS")
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasKeystore) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    androidResources {
        noCompress += "tflite"
    }
    buildFeatures {
        viewBinding = false
        buildConfig = true
    }
}

val cameraxVersion = "1.3.4"

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // قفل صاحب التطبيق بالبصمة (أو قفل الشاشة)
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    // الكاميرا تعمل داخل Foreground Service كي تستمر أثناء وجود المستخدم في تطبيق آخر
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    // مشغّل الفيديو الداخلي (وضع Video file)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    // عزل الشخص تلقائيًا (نموذج مدمج، يعمل بدون إنترنت)
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    // باركود الاقتران: توليد (ZXing) وقراءة (ML Kit مدمج، بدون إنترنت)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // تتبع الوجه (اختياري) لتأطير الريلز والزوم
    implementation("com.google.mlkit:face-detection:16.1.7")
    // تمييز الأصوات (كلام / سعال / عطس / ضحك / دق) — YAMNet على الجهاز بدون إنترنت
    implementation("com.google.mediapipe:tasks-audio:0.10.14")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
