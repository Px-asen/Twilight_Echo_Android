package com.twilightecho.android.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.LibrarySource
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Restoring the last computer across a real process restart. Each phase runs as its own instrumentation
 * (`-e sessionPhase ...`), so the second phase starts a fresh app process that has only what was saved:
 *
 * 1. `prepare -e output desktop|phone` pairs with a local TLS fixture and makes the explicit output choice.
 * 2. `restore -e output desktop|phone` brings the same computer back on the same port and checks that the app
 *    reconnected by itself, restored the library, and moved playback only if the user had chosen the computer.
 *    It ends by forgetting the computer.
 */
@RunWith(AndroidJUnit4::class)
class DesktopSessionRestoreTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val host get() = AndroidPlaybackHost.getInstance(context)
    private val identityFile get() = File(context.filesDir, "session-restore-fixture.p12")
    private val portFile get() = File(context.filesDir, "session-restore-fixture.port")
    private val sessionFile get() = File(context.filesDir, "twilight_core_state/desktop_session.json")
    private val desktopOutput get() = args.getString("output") == "desktop"

    @Test fun prepare() {
        assumeTrue(args.getString("sessionPhase") == "prepare")
        waitFor(15_000) { host.primaryCoreClient.snapshot.value != null }
        val desktop = DesktopHttpFixture()
        desktop.identity.saveTo(identityFile)
        portFile.writeText(desktop.port.toString())
        runBlocking {
            host.connectDesktopLibrary(desktop.address, "123456").getOrThrow()
            host.selectPlaybackTarget(phone = !desktopOutput).getOrThrow()
        }
        assertEquals(desktopOutput, host.operatingMode.value is CoreOperatingMode.RemoteDesktopCore)
        val session = sessionFile.readText()
        assertTrue(session, session.contains("desktop-ui-fixture"))
        assertEquals(session, desktopOutput, session.contains("\"outputDesktopId\":\"desktop-ui-fixture\""))
        // The fixture dies with this process; the next phase is a fresh start.
    }

    @Test fun restore() {
        assumeTrue(args.getString("sessionPhase") == "restore")
        DesktopHttpFixture(loadTestTlsIdentity(identityFile), portFile.readText().trim().toInt()).use { desktop ->
            waitFor(45_000) { (host.librarySource.value as? LibrarySource.Desktop)?.serverId == "desktop-ui-fixture" }
            assertTrue("reconnected without a new pairing", desktop.requests.none { it.startsWith("/api/pair") })
            if (desktopOutput) {
                waitFor(15_000) { host.operatingMode.value is CoreOperatingMode.RemoteDesktopCore }
            } else {
                Thread.sleep(3_000)
                assertTrue("reconnecting must never move playback to the computer",
                    host.operatingMode.value is CoreOperatingMode.StandaloneLocal)
            }

            runBlocking { host.forgetDesktop("desktop-ui-fixture").getOrThrow() }
            assertTrue(host.librarySource.value is LibrarySource.Phone)
            assertTrue(host.operatingMode.value is CoreOperatingMode.StandaloneLocal)
            assertTrue(host.knownDesktops.value.none { it.id == "desktop-ui-fixture" })
            assertNull(host.savedDesktopAddress("desktop-ui-fixture"))
            assertTrue(host.desktopLibraries.value.none { it.serverId == "desktop-ui-fixture" })
            assertFalse(sessionFile.readText().contains("desktop-ui-fixture"))
            waitFor(10_000) { "/api/pair/forget" in desktop.requests }
        }
        identityFile.delete(); portFile.delete()
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out after $timeoutMs ms")
            Thread.sleep(100)
        }
    }
}
