package com.reactionrecorder

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * تراخيص النسخ بدون إنترنت:
 *  • صاحب التطبيق يفتح "وضع المالك" مرة واحدة برمز المالك؛ الرمز يفك مفتاح التوقيع المشفّر داخل التطبيق،
 *    والمفتاح يُحفظ بعدها على جوال المالك فقط (محميًا بـ Android Keystore).
 *  • كل ترخيص = (رقم الجهاز، المدة، رقم فريد) موقّع بمفتاح المالك. أي نسخة تتحقق منه بالمفتاح العام المضمّن.
 *  • المدة تُحسب بساعة داخلية (منذ التشغيل) لا بالتاريخ، فتغيير التاريخ لا يمددها.
 * حماية ضد الاستعمال العادي؛ مبرمج يفك التطبيق ويعدّله يستطيع تجاوزها.
 */
object License {
    const val ROOT_PUB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESsIWBrwSH1jWCtcIhrMhOQLPiFvlplAq7X2zVtL7aLxy9BEJBo9wcp7PAe00Y1SR6CiUnOqTddhW5DR4pPSwRg=="
    // بيانات مفتاح المالك المشفّر بتوصل وقت البناء من الأسرار (مش محفوظة بالكود)
    val OWNER_SALT: String get() = BuildConfig.OWNER_SALT
    val OWNER_IV: String get() = BuildConfig.OWNER_IV
    val OWNER_ENC: String get() = BuildConfig.OWNER_ENC
    private const val ITER = 150000
    private const val P = "reaction_lic"
    private const val WRAP = "rr_owner_wrap"
    const val DAY = 24L * 3600 * 1000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
    private val b64 = Base64.getEncoder()
    private val d64 = Base64.getDecoder()
    private val u64 = Base64.getUrlEncoder().withoutPadding()
    private val ud64 = Base64.getUrlDecoder()

    /** رقم هذا الجهاز (ثابت لهذا التطبيق على هذا الجوال) */
    fun deviceId(ctx: Context): String {
        val a = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) } catch (_: Throwable) { null }
        val raw = if (!a.isNullOrBlank()) a else prefs(ctx).getString("rid", null) ?: java.util.UUID.randomUUID().toString().also { prefs(ctx).edit().putString("rid", it).apply() }
        return raw.replace("-", "").uppercase().take(12)
    }

    // ───────── منطق خالص (مختبر) ─────────

    fun normCode(s: String): String = SatNet.normDigits(s).uppercase().filter { it.isLetterOrDigit() }

    fun publicKey(b64der: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(d64.decode(b64der)))

    /** يفك مفتاح التوقيع برمز المالك؛ null إن كان الرمز خطأ */
    fun decryptOwnerKey(code: String, salt: String = OWNER_SALT, iv: String = OWNER_IV, enc: String = OWNER_ENC): PrivateKey? = try {
        val spec = PBEKeySpec(normCode(code).toCharArray(), d64.decode(salt), ITER, 256)
        val k = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, d64.decode(iv)))
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(c.doFinal(d64.decode(enc))))
    } catch (_: Throwable) { null }

    fun sign(key: PrivateKey, dev: String, durMs: Long, wallMs: Long, nonce: String): String {
        val payload = JSONObject().put("d", dev).put("dur", durMs).put("t", wallMs).put("n", nonce).toString().toByteArray(Charsets.UTF_8)
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(key); s.update(payload)
        return "RRLIC1." + u64.encodeToString(payload) + "." + u64.encodeToString(s.sign())
    }

    class Lic(val dev: String, val dur: Long, val t: Long, val nonce: String)

    /** يتحقق من التوقيع والجهاز؛ null إن كان مزورًا أو لجهاز آخر */
    fun check(lic: String, dev: String, pub: PublicKey): Lic? = try {
        val p = lic.trim().split('.')
        require(p.size == 3 && p[0] == "RRLIC1")
        val payload = ud64.decode(p[1])
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(pub); s.update(payload)
        require(s.verify(ud64.decode(p[2])))
        val o = JSONObject(String(payload, Charsets.UTF_8))
        if (o.getString("d") != dev) null else Lic(o.getString("d"), o.getLong("dur"), o.getLong("t"), o.getString("n"))
    } catch (_: Throwable) { null }

    /**
     * الوقت المستهلك منذ آخر قياس: بالساعة الداخلية؛ وإذا أُعيد تشغيل الجوال (الساعة رجعت للصفر)
     * نأخذ فرق ساعة الحائط (ولا يقل عن صفر).
     */
    fun consumed(lastElapsed: Long, nowElapsed: Long, lastWall: Long, nowWall: Long): Long =
        if (nowElapsed >= lastElapsed) nowElapsed - lastElapsed else maxOf(0L, nowWall - lastWall)

    // ───────── المالك ─────────

    fun isOwner(ctx: Context): Boolean = prefs(ctx).getBoolean("owner", false) && prefs(ctx).contains("okey")

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(WRAP, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(WRAP, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    /** يفعّل وضع المالك على هذا الجوال برمز المالك (يأخذ ثانية أو ثانيتين: لا يُستدعى من الخيط الرئيسي) */
    fun becomeOwner(ctx: Context, code: String): Boolean {
        val k = decryptOwnerKey(code) ?: return false
        // تأكيد أن المفتاح يطابق المفتاح العام المضمّن
        val test = sign(k, "X", 1, 0, "t")
        if (check(test, "X", publicKey(ROOT_PUB)) == null) return false
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        val enc = c.doFinal(k.encoded)
        prefs(ctx).edit().putBoolean("owner", true).putString("okey", b64.encodeToString(enc)).putString("oiv", b64.encodeToString(c.iv)).apply()
        OwnerLock.unlocked(ctx)
        return true
    }

    private fun ownerKey(ctx: Context): PrivateKey? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, d64.decode(prefs(ctx).getString("oiv", ""))))
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(c.doFinal(d64.decode(prefs(ctx).getString("okey", "")))))
    } catch (_: Throwable) { null }

    /** يصدر ترخيصًا لجهاز (المالك فقط) ويحفظه في سجل الأجهزة */
    fun issue(ctx: Context, dev: String, name: String, durMs: Long): String? {
        val k = ownerKey(ctx) ?: return null
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val lic = sign(k, dev, durMs, System.currentTimeMillis(), nonce)
        val log = try { JSONObject(prefs(ctx).getString("issued", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        log.put(dev, JSONObject().put("name", name).put("until", System.currentTimeMillis() + durMs).put("revoked", false))
        prefs(ctx).edit().putString("issued", log.toString()).apply()
        return lic
    }

    fun markRevoked(ctx: Context, dev: String) {
        val log = try { JSONObject(prefs(ctx).getString("issued", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        log.optJSONObject(dev)?.put("revoked", true)?.put("until", System.currentTimeMillis())
        prefs(ctx).edit().putString("issued", log.toString()).apply()
    }

    class Issued(val dev: String, val name: String, val until: Long, val revoked: Boolean)

    fun issued(ctx: Context): List<Issued> {
        val log = try { JSONObject(prefs(ctx).getString("issued", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        return log.keys().asSequence().map { k -> val o = log.getJSONObject(k); Issued(k, o.optString("name"), o.optLong("until"), o.optBoolean("revoked")) }.sortedByDescending { it.until }.toList()
    }

    // ───────── الجهاز المرخّص ─────────

    /** يطبّق ترخيصًا وصل (باركود أو من جوال المالك). يعيد رسالة خطأ أو null */
    @Synchronized
    fun apply(ctx: Context, lic: String): String? {
        val l = check(lic, deviceId(ctx), publicKey(ROOT_PUB)) ?: return tx("ترخيص غير صالح لهذا الجهاز", "This licence is not valid for this device")
        val used = (prefs(ctx).getString("nonces", "") ?: "").split(',')
        if (l.nonce in used) return tx("هذا الترخيص استُعمل من قبل", "This licence was already used")
        val now = System.currentTimeMillis()
        val left = l.dur - maxOf(0L, now - l.t)
        if (left <= 0) return tx("انتهت مدة هذا الترخيص", "This licence has expired")
        prefs(ctx).edit().putLong("left", left).putLong("le", SystemClock.elapsedRealtime()).putLong("lw", now)
            .putString("nonces", (used.filter { it.isNotEmpty() }.takeLast(50) + l.nonce).joinToString(",")).apply()
        return null
    }

    fun revoke(ctx: Context) { prefs(ctx).edit().putLong("left", 0L).apply() }

    /** الوقت المتبقي (ms) — يُحدَّث عند كل قراءة */
    @Synchronized
    fun remaining(ctx: Context): Long {
        val p = prefs(ctx)
        var left = p.getLong("left", 0L)
        if (left <= 0) return 0
        val ne = SystemClock.elapsedRealtime(); val nw = System.currentTimeMillis()
        left -= consumed(p.getLong("le", ne), ne, p.getLong("lw", nw), nw)
        if (left < 0) left = 0
        p.edit().putLong("left", left).putLong("le", ne).putLong("lw", nw).apply()
        return left
    }

    /** هل تعمل الواجهة الكاملة؟ (المالك، أو ترخيص ساري) */
    fun active(ctx: Context): Boolean = BuildConfig.UX_TEST || isOwner(ctx) || remaining(ctx) > 0

    fun fmtLeft(ms: Long): String {
        val m = ms / 60000
        return if (m >= 60) tx("${m / 60} ساعة و${m % 60} دقيقة", "${m / 60} h ${m % 60} min") else tx("$m دقيقة", "$m min")
    }
}

/** قفل صاحب التطبيق: بصمة كل فترة (ساعة داخلية، لا تتأثر بتغيير التاريخ) وبعد إعادة تشغيل الجوال */
object OwnerLock {
    private const val P = "reaction_lic"

    fun hours(ctx: Context): Int = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getInt("lockH", 24)
    fun setHours(ctx: Context, h: Int) { ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putInt("lockH", h).apply() }

    fun locked(ctx: Context): Boolean {
        if (!License.isOwner(ctx)) return false
        val last = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getLong("ue", -1L)
        return isLocked(last, SystemClock.elapsedRealtime(), hours(ctx))
    }

    /** خالص للاختبار: مقفل إذا لم يُفتح أبدًا، أو أعيد تشغيل الجوال، أو مرّت المدة */
    fun isLocked(lastUnlock: Long, now: Long, hours: Int): Boolean = lastUnlock < 0 || now < lastUnlock || now - lastUnlock > hours * 3600_000L

    fun unlocked(ctx: Context) { ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putLong("ue", SystemClock.elapsedRealtime()).apply() }
}
