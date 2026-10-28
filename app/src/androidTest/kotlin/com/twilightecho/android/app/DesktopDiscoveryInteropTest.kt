package com.twilightecho.android.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

/** NSD uses a real Android DNS-SD advertisement; HTTP goes to the production desktop fixture.
 * Emulator NAT does not carry the physical LAN's multicast, so the test advertises its forwarded endpoint. */
@RunWith(AndroidJUnit4::class)
class DesktopDiscoveryInteropTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val host get() = AndroidPlaybackHost.getInstance(instrumentation.targetContext)

    @Test fun discoverSelectApproveAndReconnectWithoutAddressOrPin() {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("desktopServerId")
        val port = args.getString("desktopPort")?.toIntOrNull()
        assumeTrue("Requires an approval-enabled fixture and its advertised ID/port", id != null && port != null)
        val nsd = instrumentation.targetContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val relay = DesktopFixtureRelay(port!!)
        val registered = CountDownLatch(1)
        var registrationError: Int? = null
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { registered.countDown() }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { registrationError = error; registered.countDown() }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        nsd.registerService(NsdServiceInfo().apply {
            serviceName = "Twilight QA ${id!!.take(8)}"; serviceType = "_twilight-core._tcp."; setPort(relay.port)
            setAttribute("id", id); setAttribute("name", "书房电脑 · 联调"); setAttribute("v", "1"); setAttribute("bridge", "1")
        }, NsdManager.PROTOCOL_DNS_SD, registration)
        assertTrue(registered.await(10, TimeUnit.SECONDS)); assertNull(registrationError)
        try {
            rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value != null }
            rule.onNodeWithTag("nav-SETTINGS").performClick()
            rule.onNodeWithText("连接电脑", substring = false).performClick()
            rule.waitUntil(20000) { host.desktopDiscovery.state.value.devices.any { it.id == id } }
            // The real NSD address is the emulator's LAN interface. Its forwarded desktop port is reachable there.
            rule.onNodeWithTag("desktop-$id").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("remoteHost").assertDoesNotExist()
            screenshot("desktop-discovered")
            rule.onNode(hasText("连接") and hasAnyAncestor(hasTestTag("desktop-$id"))).performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText("请在 书房电脑 · 联调 上允许连接").fetchSemanticsNodes().isNotEmpty() }
            screenshot("desktop-awaiting-approval")
            rule.waitUntil(20000) { (host.librarySource.value as? LibrarySource.Desktop)?.connected == true &&
                host.libraryCoreClient.snapshot.value?.epoch == "desktop-interop" }
            assertTrue(host.operatingMode.value is CoreOperatingMode.StandaloneLocal)
            assertEquals("desktop-interop", host.libraryCoreClient.snapshot.value?.epoch)
            rule.onNodeWithText("切回本机音乐").performScrollTo().performClick()
            rule.waitUntil(15000) { host.librarySource.value is LibrarySource.Phone }
            rule.onNodeWithTag("desktop-$id").performScrollTo()
            rule.onNode(hasText("重连") and hasAnyAncestor(hasTestTag("desktop-$id"))).performClick()
            rule.waitUntil(15000) { (host.librarySource.value as? LibrarySource.Desktop)?.connected == true }
            assertTrue(host.knownDesktops.value.any { it.id == id && it.trusted })
            assertNotEquals("playing", runBlocking { host.nativeAudioEngine.getSnapshot().state })
            screenshot("desktop-connected-without-pin")
        } finally {
            runBlocking { host.switchToStandaloneLocalCore() }
            host.desktopDiscovery.stop()
            runCatching { nsd.unregisterService(registration) }
            relay.close()
        }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle(); Thread.sleep(400)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "desktop-bridge-qa").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, "$name.png")))
    }
}

/** Exposes adb reverse's loopback listener on the emulator's own NSD-resolved interface. */
private class DesktopFixtureRelay(private val targetPort: Int) : java.io.Closeable {
    private val server = ServerSocket(0)
    val port get() = server.localPort
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "desktop-test-relay").apply { isDaemon = true } }
    init { workers.execute {
        while (!server.isClosed) {
            val downstream = runCatching { server.accept() }.getOrNull() ?: break
            sockets += downstream
            workers.execute {
                try {
                    val upstream = Socket("127.0.0.1", targetPort)
                    sockets += upstream
                    workers.execute { runCatching { downstream.getInputStream().copyTo(upstream.getOutputStream()) }; runCatching { upstream.close() } }
                    runCatching { upstream.getInputStream().copyTo(downstream.getOutputStream()) }
                    upstream.close(); sockets -= upstream
                } finally { downstream.close(); sockets -= downstream }
            }
        }
    } }
    override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow() }
}
