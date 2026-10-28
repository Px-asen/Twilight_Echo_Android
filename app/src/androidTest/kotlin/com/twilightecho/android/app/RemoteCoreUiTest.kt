package com.twilightecho.android.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import javax.net.ssl.SSLServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** A real HTTPS transport fixture with a desktop-like self-signed certificate, not an external Desktop Core or a substituted UI action. */
@RunWith(AndroidJUnit4::class)
class RemoteCoreUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val host get() = AndroidPlaybackHost.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun pairOverTlsRefreshLibraryAndReturnToLocal() {
        rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value != null }
        val local = host.primaryCoreClient.snapshot.value!!
        DesktopHttpFixture().use { desktop ->
            try {
                rule.onNodeWithTag("nav-SETTINGS").performClick()
                rule.onNodeWithText("连接电脑", substring = false).performClick()
                rule.onNodeWithTag("manualDesktopConnection").performScrollTo().performClick()
                rule.onNodeWithTag("remoteHost").performScrollTo().performClick().performTextClearance()
                rule.onNodeWithTag("remoteHost").performTextInput(desktop.address)
                rule.onNodeWithTag("remotePin").performScrollTo().performClick().performTextInput("123456")
                rule.runOnUiThread {
                    WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
                        .hide(WindowInsetsCompat.Type.ime())
                }
                rule.onNodeWithText("连接电脑 Core").performScrollTo().performClick()
                rule.waitUntil(15000) { host.librarySource.value is LibrarySource.Desktop }
                rule.waitUntil(15000) { host.libraryCoreClient.snapshot.value?.epoch == "desktop-ui-test" }
                val computer = (host.librarySource.value as LibrarySource.Desktop).serverId
                rule.onNodeWithTag("nav-LIBRARY").performClick()
                rule.waitUntil(15000) { rule.onAllNodesWithTag("libraryDevice").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("libraryDevice").performClick()
                rule.onNodeWithTag("libraryDevice-$computer").performClick()
                rule.waitUntil(15000) { rule.onAllNodesWithTag("track-desktop-only").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("track-desktop-only").assertIsDisplayed()
                assertTrue(desktop.requests.any { it == "/api/pair" })
                val state = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "twilight_core_state")
                val sealed = java.io.File(state, "remote_desktops.bin").readBytes().toString(Charsets.ISO_8859_1)
                assertFalse("the device token is stored only Keystore-encrypted", sealed.contains("instrumentation-fixture-token"))
                assertFalse(sealed.contains(desktop.identity.fingerprint))
                assertFalse(java.io.File(state, "remote_desktops.json").exists())
                assertTrue(desktop.requests.any { it == "search.songs" })
                rule.onNodeWithTag("libraryDevice").performClick()
                rule.onNodeWithText("连接电脑…").performClick()
                rule.onNodeWithTag("settings-REMOTE").assertIsDisplayed()
                rule.onNodeWithText("切回本机音乐").performScrollTo().performClick()
                rule.waitUntil(15000) { host.librarySource.value is LibrarySource.Phone &&
                    host.libraryCoreClient.snapshot.value?.epoch == local.epoch }
                // The computer's songs stay listed while it is away; the device menu narrows the library to this phone.
                rule.onNodeWithTag("nav-LIBRARY").performClick()
                rule.waitUntil(15000) { rule.onAllNodesWithTag("track-desktop-only").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("libraryDevice").performClick()
                rule.onNodeWithTag("libraryDevice-phone").performClick()
                rule.waitUntil(15000) { rule.onAllNodesWithText("${local.libraryTrackCount} 首歌曲 · ", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("track-desktop-only").assertDoesNotExist()
                assertFalse("Phone scanning must never replace the Desktop library",
                    desktop.requests.any { it == "library.syncTracks" })
            } finally {
                runBlocking { host.switchToStandaloneLocalCore().getOrThrow() }
            }
        }
    }
}

internal class DesktopHttpFixture(val identity: TestTlsIdentity = selfSignedTestCertificate(), port: Int = 0) : Closeable {
    private val json = DefaultCoreClient.DefaultJson
    private val server = identity.serverContext().serverSocketFactory
        .createServerSocket(port, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    val port get() = server.localPort
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "desktop-http-fixture").apply { isDaemon = true } }
    val address = "127.0.0.1:${server.localPort}"
    val requests = CopyOnWriteArrayList<String>()
    private val track = CoreTrackView("desktop-only", title = "来自电脑的音乐", artist = "Core 测试曲库")
    private val snapshot = CoreSnapshot(epoch = "desktop-ui-test", sequence = 1, libraryTrackCount = 1)

    init {
        workers.submit {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                workers.submit { client.use { respond(it) } }
            }
        }
    }

    private fun respond(socket: Socket) {
        socket.soTimeout = 5000
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
        var length = 0
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
        }
        val chars = CharArray(length)
        var read = 0
        while (read < length) {
            val count = reader.read(chars, read, length - read)
            if (count < 0) break
            read += count
        }
        requests += path
        val contentType: String
        val body = when (path) {
            "/api/status" -> { contentType = "application/json"
                """{"serverId":"desktop-ui-fixture","coreBridge":true,"protocolVersion":1,"coreBridgeVersion":1,"requiresPairing":true,"tls":true}""" }
            "/api/pair" -> {
                require(json.parseToJsonElement(String(chars)).jsonObject["pin"]?.jsonPrimitive?.content == "123456")
                contentType = "application/json"
                "{\"token\":\"instrumentation-fixture-token\"}"
            }
            "/api/core/events" -> {
                contentType = "text/event-stream"
                "event: core_state\ndata: ${json.encodeToString(CoreEventMessage(epoch = snapshot.epoch, sequence = 1, snapshot = snapshot))}\n\n"
            }
            "/api/core/rpc" -> {
                contentType = "application/json"
                val request = json.decodeFromString<CoreRequestEnvelope>(String(chars))
                requests += request.name
                val value = when (request.name) {
                    "core.getSnapshot" -> json.encodeToJsonElement(snapshot)
                    "playback.getQueueWindow" -> json.encodeToJsonElement(CoreQueueWindowView())
                    "search.songs" -> {
                        val query = request.payload["query"]?.jsonPrimitive?.content.orEmpty()
                        val tracks = listOf(track).filter { it.title.contains(query, true) }
                        json.encodeToJsonElement(CoreLibrarySearchPage(query, tracks.size, tracks))
                    }
                    else -> JsonObject(emptyMap())
                }
                json.encodeToString(CoreResponseEnvelope(requestId = request.requestId, epoch = snapshot.epoch,
                    ok = true, value = value))
            }
            else -> { contentType = "application/json"; "{}" }
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    override fun close() { server.close(); workers.shutdownNow() }
}
