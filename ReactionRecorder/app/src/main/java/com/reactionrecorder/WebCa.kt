package com.reactionrecorder

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * شهادة محلية للانضمام من المتصفح بدون إنترنت.
 *
 * • «سلطة الشهادات» (CA) بتنعمل مرة وحدة على جوال المدير، ومفتاحها ما بيطلع منه أبدًا.
 *   كل جهاز بيثق فيها مرة وحدة بحياته (ملف .crt أو بروفايل iPhone).
 * • محصورة بالعناوين المحلية بس (Name Constraints): 10.x و172.16–31 و192.168.x و169.254.x و.local —
 *   حتى لو حدا أخذ مفتاحها ما بتنفع لأي موقع على الإنترنت.
 * • شهادة السيرفر بتنعمل من جديد لما يتغيّر عنوان الجوال (موقّعة من نفس السلطة → ما في ثقة جديدة).
 *
 * كاتبين ترميز DER بإيدنا (بدون مكتبة BouncyCastle الثقيلة)؛ القواعد حسب RFC 5280 وشروط Apple
 * (serverAuth، SAN، مدة ≤ 825 يوم، RSA 2048، SHA-256).
 */
object WebCa {
    const val CA_NAME = "ReactionRecorder Local"

    class Ca(val key: PrivateKey, val pub: PublicKey, val cert: X509Certificate, val der: ByteArray)
    class Server(val key: PrivateKey, val chain: Array<X509Certificate>, val names: List<String>)

    // ───── ترميز DER ─────
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }
    private fun tlv(tag: Int, body: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + len(body.size) + body
    private fun seq(vararg parts: ByteArray) = tlv(0x30, cat(*parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, cat(*parts))
    private fun cat(vararg parts: ByteArray): ByteArray { val o = ByteArrayOutputStream(); for (p in parts) o.write(p); return o.toByteArray() }
    private fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun int(v: Int) = int(BigInteger.valueOf(v.toLong()))
    private fun bool(b: Boolean) = tlv(0x01, byteArrayOf(if (b) 0xFF.toByte() else 0))
    private fun octets(b: ByteArray) = tlv(0x04, b)
    private fun bits(b: ByteArray, unused: Int = 0) = tlv(0x03, byteArrayOf(unused.toByte()) + b)
    private fun nul() = byteArrayOf(0x05, 0x00)
    private fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun oid(s: String): ByteArray {
        val p = s.split('.').map { it.toLong() }
        val o = ByteArrayOutputStream()
        o.write((p[0] * 40 + p[1]).toInt())
        for (v in p.drop(2)) {
            val stack = ArrayList<Int>(); var x = v
            stack.add((x and 0x7F).toInt()); x = x shr 7
            while (x > 0) { stack.add(((x and 0x7F) or 0x80).toInt()); x = x shr 7 }
            for (b in stack.reversed()) o.write(b)
        }
        return tlv(0x06, o.toByteArray())
    }
    private fun time(d: Date): ByteArray {
        // UTCTime لحد 2049، وبعدها GeneralizedTime (RFC 5280)
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).also { it.time = d }
        return if (cal.get(java.util.Calendar.YEAR) < 2050)
            tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d).toByteArray())
        else tlv(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d).toByteArray())
    }
    private fun name(cn: String) = seq(set(seq(oid("2.5.4.3"), utf8(cn))), set(seq(oid("2.5.4.10"), utf8("ReactionRecorder"))))
    private fun ext(id: String, critical: Boolean, value: ByteArray) = if (critical) seq(oid(id), bool(true), octets(value)) else seq(oid(id), octets(value))
    private val sha256Rsa get() = seq(oid("1.2.840.113549.1.1.11"), nul())

    private fun keyId(pub: PublicKey): ByteArray {
        // معرّف المفتاح = SHA-1 لبتات المفتاح العام (الطريقة ١ بـ RFC 5280)
        val spki = pub.encoded
        val bitString = parseSpkiBits(spki)
        return MessageDigest.getInstance("SHA-1").digest(bitString)
    }
    /** بتات المفتاح من SubjectPublicKeyInfo (SEQUENCE { alg, BIT STRING }) */
    private fun parseSpkiBits(spki: ByteArray): ByteArray {
        var i = 0
        fun readLen(): Int { val b = spki[i++].toInt() and 0xFF; if (b < 0x80) return b; var n = 0; repeat(b and 0x7F) { n = (n shl 8) or (spki[i++].toInt() and 0xFF) }; return n }
        i++; readLen()              // SEQUENCE
        i++; val al = readLen(); i += al // AlgorithmIdentifier
        i++; val bl = readLen()     // BIT STRING
        return spki.copyOfRange(i + 1, i + bl)
    }

    private fun ipBytes(s: String): ByteArray? = try {
        if (!s.matches(Regex("[0-9.]+")) && !s.contains(':')) null else InetAddress.getByName(s).address
    } catch (_: Throwable) { null }

    private fun sign(tbs: ByteArray, key: PrivateKey): X509Certificate {
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(tbs) }.sign()
        val der = seq(tbs, sha256Rsa, bits(sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun serial(): BigInteger = BigInteger(1, ByteArray(16).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x7F).toByte() })

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()

    /** سلطة شهادات جديدة (١٠ سنين)، محصورة بالعناوين المحلية */
    fun newCa(label: String): Ca {
        val kp = rsa()
        val now = System.currentTimeMillis()
        val subj = name("$CA_NAME · $label")
        // nameConstraints: permittedSubtrees = عناوين IP خاصة + .local + rr.local
        fun ipNet(a: String, m: String) = seq(tlv(0x87, InetAddress.getByName(a).address + InetAddress.getByName(m).address))
        fun dns(d: String) = seq(tlv(0x82, d.toByteArray()))
        val permitted = cat(
            ipNet("10.0.0.0", "255.0.0.0"), ipNet("172.16.0.0", "255.240.0.0"), ipNet("192.168.0.0", "255.255.0.0"),
            ipNet("169.254.0.0", "255.255.0.0"), dns(".local"), dns("local"),
        )
        val nc = seq(tlv(0xA0, permitted))
        val ski = keyId(kp.public)
        val exts = seq(
            ext("2.5.29.19", true, seq(bool(true), int(0))),              // CA, pathLen 0
            ext("2.5.29.15", true, bits(byteArrayOf(0x06), 1)),           // keyCertSign + cRLSign
            ext("2.5.29.14", false, octets(ski)),
            ext("2.5.29.30", true, nc),
        )
        val tbs = seq(
            tlv(0xA0, int(2)), int(serial()), sha256Rsa, subj,
            seq(time(Date(now - 86_400_000L)), time(Date(now + 3650L * 86_400_000L))),
            subj, kp.public.encoded, tlv(0xA3, exts),
        )
        val cert = sign(tbs, kp.private)
        return Ca(kp.private, kp.public, cert, cert.encoded)
    }

    /** شهادة السيرفر للعناوين الحالية (+ rr.local)، موقّعة من السلطة. مدتها ٣٩٧ يوم (أقل من حد Apple) */
    fun newServer(ca: Ca, names: List<String>): Server {
        val kp = rsa()
        val now = System.currentTimeMillis()
        val cn = names.firstOrNull() ?: "rr.local"
        val sans = cat(*names.distinct().map { n -> ipBytes(n)?.let { tlv(0x87, it) } ?: tlv(0x82, n.toByteArray()) }.toTypedArray())
        val exts = seq(
            ext("2.5.29.19", true, seq()),                                 // مش CA
            ext("2.5.29.15", true, bits(byteArrayOf(0xA0.toByte()), 5)),   // digitalSignature + keyEncipherment
            ext("2.5.29.37", false, seq(oid("1.3.6.1.5.5.7.3.1"))),        // serverAuth
            ext("2.5.29.17", false, seq(sans)),
            ext("2.5.29.14", false, octets(keyId(kp.public))),
            ext("2.5.29.35", false, seq(tlv(0x80, keyId(ca.pub)))),
        )
        val tbs = seq(
            tlv(0xA0, int(2)), int(serial()), sha256Rsa, issuerOf(ca.cert),
            seq(time(Date(now - 86_400_000L)), time(Date(now + 397L * 86_400_000L))),
            name(cn), kp.public.encoded, tlv(0xA3, exts),
        )
        return Server(kp.private, arrayOf(sign(tbs, ca.key), ca.cert), names)
    }

    /** اسم صاحب الشهادة بالضبط زي ما هو بشهادة السلطة (لازم يتطابق حرف بحرف) */
    private fun issuerOf(c: X509Certificate): ByteArray = c.subjectX500Principal.encoded

    // ───── حفظ وقراءة ─────
    fun pem(der: ByteArray, type: String = "CERTIFICATE"): String =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

    fun save(ca: Ca, dir: File) {
        dir.mkdirs()
        File(dir, "ca.der").writeBytes(ca.der)
        File(dir, "ca.key").writeBytes(ca.key.encoded)
        File(dir, "ca.pub").writeBytes(ca.pub.encoded)
    }

    fun load(dir: File): Ca? = try {
        val der = File(dir, "ca.der").readBytes()
        val kf = KeyFactory.getInstance("RSA")
        val key = kf.generatePrivate(PKCS8EncodedKeySpec(File(dir, "ca.key").readBytes()))
        val pub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(File(dir, "ca.pub").readBytes()))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        Ca(key, pub, cert, der)
    } catch (_: Throwable) { null }

    /** بصمة السلطة (عشان المستخدم يتأكد إنه وثق بالشهادة الصح) */
    fun fingerprint(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).take(8).joinToString(":") { "%02X".format(it) }

    /** بروفايل iPhone/iPad (.mobileconfig) فيه شهادة السلطة */
    fun mobileconfig(ca: Ca, label: String): String {
        val b64 = Base64.getEncoder().encodeToString(ca.der)
        val u1 = java.util.UUID.nameUUIDFromBytes(ca.der).toString().uppercase()
        val u2 = java.util.UUID.nameUUIDFromBytes(ca.der + 1).toString().uppercase()
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>PayloadContent</key><array><dict>
<key>PayloadCertificateFileName</key><string>reactionrecorder-local.cer</string>
<key>PayloadContent</key><data>$b64</data>
<key>PayloadDisplayName</key><string>$CA_NAME</string>
<key>PayloadIdentifier</key><string>com.reactionrecorder.ca.$u2</string>
<key>PayloadType</key><string>com.apple.security.root</string>
<key>PayloadUUID</key><string>$u2</string>
<key>PayloadVersion</key><integer>1</integer>
</dict></array>
<key>PayloadDisplayName</key><string>ReactionRecorder · $label</string>
<key>PayloadDescription</key><string>Local certificate so this device can join ReactionRecorder sessions without internet. Limited to local network addresses.</string>
<key>PayloadIdentifier</key><string>com.reactionrecorder.profile.$u1</string>
<key>PayloadType</key><string>Configuration</string>
<key>PayloadUUID</key><string>$u1</string>
<key>PayloadVersion</key><integer>1</integer>
</dict></plist>
"""
    }
}
