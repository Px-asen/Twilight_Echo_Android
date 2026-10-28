package com.twilightecho.android.plugins

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun writeAtomic(file: File, bytes: ByteArray) {
    file.parentFile!!.mkdirs()
    val atomic = AtomicFile(file); val stream = atomic.startWrite()
    try { stream.write(bytes); atomic.finishWrite(stream) }
    catch (error: Throwable) { atomic.failWrite(stream); throw error }
}
internal fun readAtomic(file: File): ByteArray? = if (file.exists() || File(file.path + ".bak").exists())
    AtomicFile(file).openRead().use { it.readBytes() } else null

/** Plugin settings (including account cookies) never live in plain SharedPreferences. */
internal class PluginSecrets(private val root: File) {
    private val key: SecretKey by lazy {
        val alias = "twilight-plugin-settings-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized fun read(id: String): String {
        val file = File(root, "$id.bin")
        val bytes = readAtomic(file) ?: return "{}"
        require(bytes.size >= 28) { "插件凭据文件损坏，请移除插件后重新登录" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(id.toByteArray()); doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }
    @Synchronized fun write(id: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(id.toByteArray())
        writeAtomic(File(root, "$id.bin"), cipher.iv + cipher.doFinal(value.toByteArray()))
    }
    fun delete(id: String) {
        val file = File(root, "$id.bin")
        check(!file.exists() || file.delete()) { "插件凭据删除失败，请重试卸载" }
        File(file.path + ".bak").delete(); File(file.path + ".new").delete()
    }
}
