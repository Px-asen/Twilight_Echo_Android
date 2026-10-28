package com.twilightecho.android.coreclient

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusts exactly one desktop certificate, identified by the SHA-256 of its DER encoding.
 *
 * A pin loaded from a pairing is enforced from the first handshake. An empty pin records the first
 * certificate the desktop presents and then enforces it; pairing turns that recording into a verified
 * pin by showing [remotePairingCode] on both screens. Host names are not checked because the desktop
 * is reached by LAN address and its identity is the pinned certificate.
 */
class DesktopCertificatePin(pinned: String? = null) {
    @Volatile var fingerprint: String? = pinned?.lowercase()
        private set

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("Client certificates are not accepted")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("电脑未提供安全证书")
            val presented = sha256Hex(leaf.encoded)
            synchronized(this@DesktopCertificatePin) {
                val expected = fingerprint
                if (expected == null) fingerprint = presented
                else if (expected != presented) throw DesktopCertificateMismatchException()
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val sslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }.socketFactory
    }

    val hostnameVerifier = HostnameVerifier { _, _ -> true }
}

class DesktopCertificateMismatchException : CertificateException(
    "电脑的安全证书与配对时不一致。若电脑端重装过，请在手机上忘记此电脑后重新连接"
)

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/**
 * The six digits shown while the desktop owner decides; it must equal the desktop's
 * `remotePairingCode` (src/core/cast/tlsIdentity.ts) for the same certificate and nonces.
 */
fun remotePairingCode(fingerprint: String, phoneNonce: String, serverNonce: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("twilight-pair-v2|${fingerprint.lowercase()}|$phoneNonce|$serverNonce".toByteArray(Charsets.UTF_8))
    val value = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
        ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
    return (value % 1_000_000).toString().padStart(6, '0')
}
