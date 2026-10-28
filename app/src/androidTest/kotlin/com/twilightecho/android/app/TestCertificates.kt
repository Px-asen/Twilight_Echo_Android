package com.twilightecho.android.app

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** A desktop-like identity for instrumentation fixtures; mirrors core-client test TestCertificates.kt. */
internal class TestTlsIdentity(val keyPair: KeyPair, val certificate: X509Certificate) {
    val fingerprint: String get() = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    fun serverContext(): SSLContext {
        val password = "test".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("desktop", keyPair.private, password, arrayOf(certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, SecureRandom()) }
    }
}

internal fun selfSignedTestCertificate(name: String = "Twilight Echo Core"): TestTlsIdentity {
    val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val ecdsaSha256 = der(0x30, der(0x06, byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02)))
    val cn = der(0x30, der(0x31, der(0x30, der(0x06, byteArrayOf(0x55, 0x04, 0x03)), der(0x0c, name.toByteArray()))))
    val serial = ByteArray(16).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x7f).toByte() }
    val tbs = der(0x30,
        der(0xa0, der(0x02, byteArrayOf(2))),
        der(0x02, BigInteger(1, serial).toByteArray()),
        ecdsaSha256, cn,
        der(0x30, der(0x17, "200101000000Z".toByteArray()), der(0x18, "20991231235959Z".toByteArray())),
        cn, keys.public.encoded)
    val signature = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(tbs); sign() }
    val der = der(0x30, tbs, ecdsaSha256, der(0x03, byteArrayOf(0) + signature))
    val certificate = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    return TestTlsIdentity(keys, certificate)
}

private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
    val body = parts.fold(ByteArray(0)) { all, part -> all + part }
    val out = ByteArrayOutputStream()
    out.write(tag)
    when {
        body.size < 0x80 -> out.write(body.size)
        body.size < 0x100 -> { out.write(0x81); out.write(body.size) }
        else -> { out.write(0x82); out.write(body.size shr 8); out.write(body.size and 0xff) }
    }
    out.write(body)
    return out.toByteArray()
}

/** Keeps a fixture identity across instrumentation runs, so a restarted app meets the computer it paired with. */
internal fun TestTlsIdentity.saveTo(file: java.io.File) {
    val password = "test".toCharArray()
    KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        setKeyEntry("desktop", keyPair.private, password, arrayOf(certificate))
        file.outputStream().use { store(it, password) }
    }
}

internal fun loadTestTlsIdentity(file: java.io.File): TestTlsIdentity {
    val password = "test".toCharArray()
    val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
    val certificate = store.getCertificate("desktop") as X509Certificate
    return TestTlsIdentity(KeyPair(certificate.publicKey, store.getKey("desktop", password) as java.security.PrivateKey), certificate)
}
