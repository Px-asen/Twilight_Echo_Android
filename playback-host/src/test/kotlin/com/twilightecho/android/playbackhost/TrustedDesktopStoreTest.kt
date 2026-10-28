package com.twilightecho.android.playbackhost

import com.twilightecho.android.coreclient.RemoteCoreCredentials
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrustedDesktopStoreTest {
    @get:Rule val folder = TemporaryFolder()

    /** Reversible stand-in for the Keystore cipher; the marker proves nothing is written in plain text. */
    private class FakeCipher : CredentialCipher {
        override fun seal(plain: ByteArray) = MARKER + plain.reversedArray()
        override fun open(sealed: ByteArray): ByteArray {
            require(sealed.take(MARKER.size) == MARKER.toList()) { "not sealed" }
            return sealed.copyOfRange(MARKER.size, sealed.size).reversedArray()
        }
        companion object { val MARKER = "sealed:".toByteArray() }
    }

    private fun desktop(id: String, url: String = "https://$id:18492", pin: String? = "ab".repeat(32)) =
        RemoteCoreCredentials(url, "token-$id", serverId = id, serverName = id, certificateSha256 = pin)

    @Test fun savedComputersAreSealedAndReplacedByServerId() {
        val store = TrustedDesktopStore(folder.root, FakeCipher())
        store.save(desktop("one"))
        store.save(desktop("two"))
        store.save(desktop("one", url = "https://10.0.0.9:18492"))
        assertEquals(listOf("two", "one"), store.load().map { it.serverId })
        assertEquals("https://10.0.0.9:18492", store.load().last().baseUrl)
        val onDisk = File(folder.root, TrustedDesktopStore.FILE_NAME).readText()
        assertFalse("tokens must not be stored in plain text", onDisk.contains("token-one"))
    }

    @Test fun forgettingRemovesTokenAndPin() {
        val store = TrustedDesktopStore(folder.root, FakeCipher())
        store.save(desktop("one")); store.save(desktop("two"))
        assertTrue(store.forget("one"))
        assertFalse(store.forget("one"))
        assertEquals(listOf("two"), store.load().map { it.serverId })
    }

    @Test fun aComputerWithoutAPinnedCertificateIsNeverTrusted() {
        val store = TrustedDesktopStore(folder.root, FakeCipher())
        assertThrows(IllegalArgumentException::class.java) { store.save(desktop("plain", pin = null)) }
        assertTrue(store.load().isEmpty())
    }

    @Test fun plainTextListsFromBeforeTlsAreDeletedAndADamagedListCostsOnlyARepairing() {
        File(folder.root, "remote_desktops.json").writeText("""[{"baseUrl":"http://old","token":"leaked"}]""")
        File(folder.root, "remote_desktop_core.json").writeText("{}")
        File(folder.root, TrustedDesktopStore.FILE_NAME).writeText("garbage")
        val store = TrustedDesktopStore(folder.root, FakeCipher())
        assertTrue(store.load().isEmpty())
        assertFalse(File(folder.root, "remote_desktops.json").exists())
        assertFalse(File(folder.root, "remote_desktop_core.json").exists())
        store.save(desktop("one"))
        assertEquals(listOf("one"), store.load().map { it.serverId })
    }

    @Test fun onlyTheMostRecentSixteenComputersAreKept() {
        val store = TrustedDesktopStore(folder.root, FakeCipher())
        repeat(20) { store.save(desktop("d$it")) }
        assertEquals((4 until 20).map { "d$it" }, store.load().map { it.serverId })
    }
}
