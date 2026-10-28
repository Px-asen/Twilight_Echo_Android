package com.twilightecho.android.playbackhost

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.RemoteCoreCredentials
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals the trusted-computer list; the Keystore implementation never lets the key leave the device. */
internal interface CredentialCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

internal class KeystoreCredentialCipher(private val alias: String = "twilight-trusted-desktops-v1") : CredentialCipher {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(AAD)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size >= 28) { "Trusted computer list is damaged" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            updateAAD(AAD); doFinal(sealed.copyOfRange(12, sealed.size))
        }
    }

    private companion object { val AAD = "remote_desktops".toByteArray() }
}

/**
 * Paired computers with their bearer tokens and pinned certificates, encrypted at rest and excluded
 * from backup (see res/xml/backup_rules.xml). Lists from before TLS pairing were plain text and their
 * tokens carry no certificate, so they are deleted: those computers simply pair again.
 */
internal class TrustedDesktopStore(
    private val directory: File,
    private val cipher: CredentialCipher = KeystoreCredentialCipher()
) {
    private val file get() = File(directory, FILE_NAME)

    @Synchronized fun load(): List<RemoteCoreCredentials> {
        LEGACY_FILES.forEach { File(directory, it).delete() }
        val sealed = runCatching { file.readBytes() }.getOrNull() ?: return emptyList()
        // An unreadable list (for example after the Keystore key was cleared) costs a new pairing, never a crash.
        return runCatching {
            DefaultCoreClient.DefaultJson.decodeFromString<List<RemoteCoreCredentials>>(cipher.open(sealed).toString(Charsets.UTF_8))
        }.getOrDefault(emptyList()).filter { it.certificateSha256 != null }
    }

    @Synchronized fun save(credentials: RemoteCoreCredentials) {
        require(credentials.certificateSha256 != null) { "A trusted computer needs a pinned certificate" }
        write(load().filterNot {
            (credentials.serverId != null && it.serverId == credentials.serverId) || it.baseUrl == credentials.baseUrl
        }.takeLast(MAX_ENTRIES - 1) + credentials)
    }

    /** Removes the computer's token and pin; returns whether it was known. */
    @Synchronized fun forget(serverId: String): Boolean {
        val entries = load()
        val kept = entries.filterNot { it.serverId == serverId }
        if (kept.size == entries.size) return false
        write(kept)
        return true
    }

    private fun write(entries: List<RemoteCoreCredentials>) {
        directory.mkdirs()
        val temp = File(directory, "$FILE_NAME.tmp")
        temp.writeBytes(cipher.seal(DefaultCoreClient.DefaultJson.encodeToString(entries).toByteArray(Charsets.UTF_8)))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val FILE_NAME = "remote_desktops.bin"
        private val LEGACY_FILES = listOf("remote_desktops.json", "remote_desktop_core.json")
        private const val MAX_ENTRIES = 16
    }
}
