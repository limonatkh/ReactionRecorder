package com.reactionrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** الترخيص والقفل وقرارات الفلوق — بمفاتيح تُولَّد داخل الاختبار (ليست مفاتيح التطبيق) */
class LicenseTest {
    private fun keys() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test fun signAndCheck() {
        val kp = keys(); val other = keys()
        val lic = License.sign(kp.private, "DEV123", License.DAY, 1000L, "n1")
        val ok = License.check(lic, "DEV123", kp.public)
        assertNotNull(ok); assertEquals(License.DAY, ok!!.dur); assertEquals("n1", ok.nonce)
        assertNull(License.check(lic, "OTHER", kp.public))
        assertNull(License.check(lic, "DEV123", other.public))
        val p = lic.split('.')
        val forged = p[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"d":"DEV123","dur":999999999,"t":1,"n":"x"}""".toByteArray()) + "." + p[2]
        assertNull(License.check(forged, "DEV123", kp.public))
        assertNull(License.check("garbage", "DEV123", kp.public))
    }

    @Test fun ownerKeyRoundTrip() {
        val kp = keys()
        val r = SecureRandom(); val salt = ByteArray(16).also { r.nextBytes(it) }; val iv = ByteArray(12).also { r.nextBytes(it) }
        val code = "TEST-CODE-1234"
        val k = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(License.normCode(code).toCharArray(), salt, 150000, 256)).encoded, "AES")
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        val e = Base64.getEncoder()
        val enc = e.encodeToString(c.doFinal(kp.private.encoded))
        val got = License.decryptOwnerKey("test code 1234", e.encodeToString(salt), e.encodeToString(iv), enc)
        assertNotNull(got)
        assertNotNull(License.check(License.sign(got!!, "D", 5, 6, "n"), "D", kp.public))
        assertNull(License.decryptOwnerKey("WRONG-CODE-0000", e.encodeToString(salt), e.encodeToString(iv), enc))
    }

    @Test fun embeddedPublicKeyParses() { assertNotNull(License.publicKey(License.ROOT_PUB)) }

    @Test fun consumedTime() {
        assertEquals(500L, License.consumed(1000, 1500, 0, 99999))
        assertEquals(3000L, License.consumed(9000, 100, 10000, 13000)) // بعد إعادة تشغيل
        assertEquals(0L, License.consumed(9000, 100, 10000, 5000))     // تأخير التاريخ لا يعيد وقتًا
    }

    @Test fun ownerLock() {
        val h = 3600_000L
        assertTrue(OwnerLock.isLocked(-1, 100, 24))
        assertFalse(OwnerLock.isLocked(1000, 1000 + 23 * h, 24))
        assertTrue(OwnerLock.isLocked(1000, 1000 + 25 * h, 24))
        assertTrue(OwnerLock.isLocked(5000, 100, 24)) // إعادة تشغيل
    }

    @Test fun vlogLivePicks() {
        val s = PodEngine.shotsFromLive(listOf(5.0 to 1, 8.0 to 2, 12.0 to 0), 0.0, 20.0, { it != 2 })
        assertEquals(listOf(0, 1, 0), s.map { it.main })
        assertEquals(5.0, s[0].end, 1e-9); assertEquals(12.0, s[1].end, 1e-9); assertEquals(20.0, s[2].end, 1e-9)
        assertTrue(s.all { it.tag == "live" && it.type == PodShot.FULL })
        assertEquals(1, PodEngine.shotsFromLive(emptyList(), 0.0, 10.0, { true }).size)
    }

    @Test fun shareQrCarriesPairing() {
        val pair = "RRSAT2|192.168.43.1:47610|47610|1234|hubX|Phone"
        val url = SatNet.shareUrl("192.168.43.1", 8080, pair)
        assertTrue(url.startsWith("http://192.168.43.1:8080/"))
        val q = SatNet.parseQr(url)
        assertNotNull(q); assertEquals("1234", q!!.pin); assertEquals("hubX", q.hubId)
    }

    @Test fun rotationFollowsTilt() {
        assertEquals(0, SatNet.rotFromAngle(10, -1))
        assertEquals(3, SatNet.rotFromAngle(90, -1))   // مايل يمين → ROTATION_270
        assertEquals(1, SatNet.rotFromAngle(270, -1))  // مايل يسار → ROTATION_90
        assertEquals(2, SatNet.rotFromAngle(180, -1))
        assertEquals(0, SatNet.rotFromAngle(50, 0))    // هامش ثبات: ما بيرجف عند 45°
        assertEquals(3, SatNet.rotFromAngle(70, 0))
    }
}
