package com.twilightecho.android.plugins

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginSafetyTest {
    @Test fun x25519MatchesRfc7748() {
        val secret = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", NcmGateway.x25519(secret, byteArrayOf(9) + ByteArray(31)).hex())
        val bob = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", NcmGateway.x25519(secret, bob).hex())
    }
    @Test fun aesMatchesUpstreamEapiPrimitive() {
        val cipher = NcmGateway.aes("Twilight Echo".utf8(), "e82ckenh8dichen8".utf8())
        assertEquals("Twilight Echo", NcmGateway.aes(cipher, "e82ckenh8dichen8".utf8(), decrypt = true).toString(Charsets.UTF_8))
        assertEquals("1c000569513ce64e437249bd725d4a12", digest("MD5", "Twilight Echo".utf8()).hex())
    }
    @Test fun tepRejectsTraversalAndRequiresManifest() {
        for (name in listOf("../escape.js", "/absolute.js", "a/../../escape", "C:/escape", "a\\escape", "a/./index.js", "a//index.js", "only.js")) {
            val root = Files.createTempDirectory("plugin-safety").toFile()
            try { assertThrows(IllegalArgumentException::class.java) { extractPlugin(zip(name), root) } }
            finally { root.deleteRecursively() }
        }
        val root = Files.createTempDirectory("plugin-valid").toFile()
        try { extractPlugin(zip("plugin.json"), root); assertTrue(java.io.File(root, "plugin.json").isFile) }
        finally { root.deleteRecursively() }
    }
    private fun zip(name: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry(name)); it.write("{}".utf8()); it.closeEntry() }
    }.toByteArray()
}
