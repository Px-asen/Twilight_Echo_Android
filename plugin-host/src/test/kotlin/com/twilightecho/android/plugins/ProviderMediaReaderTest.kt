package com.twilightecho.android.plugins

import okhttp3.Dns
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Ranged windows, the progressive spool and cancellation, against a loopback CDN opened under the plugin's grants. */
class ProviderMediaReaderTest {
    private val loopback = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }
    private val audio = ByteArray(600_000) { (it * 31 + 7).toByte() }
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var etag = "\"v1\""
    @Volatile private var reportedLength = audio.size
    private val gate = CountDownLatch(1)
    private val server = TestHttpServer { route(it) }
    private val port = server.port
    private val api = PluginHostApi(testRuntime, MemorySettings(), PluginNetwork(publicDns = loopback))
    private val spool: File = Files.createTempDirectory("provider-media").toFile()

    @After fun stop() { gate.countDown(); api.close(); server.close(); spool.deleteRecursively() }

    private fun route(request: TestRequest): TestReply {
        requests += "${request.path} ${request.header("Range")}"
        val range = Regex("""bytes=(\d+)-(\d+)""").matchEntire(request.header("Range").orEmpty())
        fun window(): TestReply {
            val first = range!!.groupValues[1].toInt(); val last = minOf(range.groupValues[2].toInt(), audio.size - 1)
            return TestReply(206, audio.copyOfRange(first, last + 1), listOf("Content-Range" to "bytes $first-$last/$reportedLength", "ETag" to etag))
        }
        return when (request.path) {
            "/audio" -> window()
            "/expired" -> TestReply(403)
            "/whole" -> TestReply(200, audio)
            "/live" -> TestReply(200, audio, length = false, pause = gate)
            "/slow" -> TestReply(206, audio.copyOfRange(0, 262_144), listOf("Content-Range" to "bytes 0-262143/600000"), pause = gate)
            "/forgetful" -> if (range!!.groupValues[1] == "0") window() else TestReply(200, audio)
            else -> TestReply(404)
        }
    }

    private fun url(path: String) = ProviderPlayback("http://img.cdn.test:$port$path", emptyList())
    private fun reader(path: String = "/audio", resolve: (Boolean) -> ProviderPlayback = { url(path) }) =
        ProviderMediaReader(resolve, { playback, range, onCall -> api.openMedia(playback.url, playback.headers, range, 5_000, onCall) }, spool)
    private fun ProviderMediaReader.readFully(position: Long, size: Int): ByteArray {
        val out = ByteArray(size); var done = 0
        while (done < size) { val n = readAt(position + done, out, done, size - done); check(n > 0) { "EOF at ${position + done}" }; done += n }
        return out
    }
    private fun eventually(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) { check(System.nanoTime() < until) { "timed out" }; Thread.sleep(10) }
    }

    @Test fun rangedServersAreReadInWindows() {
        reader().use { reader ->
            assertEquals(600_000L, reader.size())
            assertArrayEquals(audio, reader.readFully(0, audio.size))
            assertArrayEquals(audio.copyOfRange(1_000, 1_100), reader.readFully(1_000, 100)) // seeking back fetches again
            assertEquals(-1, reader.readAt(600_000, ByteArray(1), 0, 1))
            assertEquals(0, reader.readAt(5, ByteArray(1), 0, 0))
        }
        assertEquals(listOf("bytes=0-262143", "bytes=262144-524287", "bytes=524288-599999", "bytes=1000-263143"),
            requests.map { it.substringAfter(' ') })
    }

    @Test fun anExpiredUrlIsResolvedAgainOnce() {
        val forced = CopyOnWriteArrayList<Boolean>()
        reader(resolve = { force -> forced += force; url(if (force) "/audio" else "/expired") }).use { reader ->
            assertEquals(600_000L, reader.size())
            assertArrayEquals(audio.copyOfRange(300_000, 300_010), reader.readFully(300_000, 10))
        }
        assertEquals(listOf(false, true), forced)
        reader("/expired").use { reader ->
            assertEquals("音源返回 HTTP 403", assertThrows(IOException::class.java) { reader.size() }.message)
        }
    }

    @Test fun aChangedFileStopsPlayback() {
        reader().use { reader ->
            reader.size(); reportedLength = 700_000
            assertEquals("音频文件已变化，请重新播放", assertThrows(IOException::class.java) { reader.readFully(300_000, 10) }.message)
        }
        reportedLength = audio.size
        reader().use { reader ->
            reader.size(); etag = "W/\"weak\""
            reader.readFully(300_000, 10) // weak validators differ between CDN edges
            etag = "\"v2\""
            assertEquals("音频文件版本已变化，请重新播放", assertThrows(IOException::class.java) { reader.readFully(0, 10) }.message)
        }
    }

    @Test fun serversIgnoringRangeAreSpooledOnce() {
        reader("/whole").use { reader ->
            assertEquals(600_000L, reader.size())
            assertArrayEquals(audio, reader.readFully(0, audio.size))
            assertArrayEquals(audio.copyOfRange(10, 20), reader.readFully(10, 10))
            assertEquals(-1, reader.readAt(600_000, ByteArray(1), 0, 1))
            assertEquals(1, spool.listFiles()!!.size)
        }
        assertEquals(1, requests.size)
        eventually { spool.listFiles().isNullOrEmpty() }
    }

    @Test fun aStreamWithoutLengthIsReadWhileItDownloads() {
        reader("/live").use { reader ->
            assertEquals(-1L, reader.size())
            assertArrayEquals(audio.copyOfRange(0, 1_000), reader.readFully(0, 1_000))
            gate.countDown()
            assertArrayEquals(audio.copyOfRange(599_000, 600_000), reader.readFully(599_000, 1_000))
            eventually { reader.size() == 600_000L }
            assertEquals(-1, reader.readAt(600_000, ByteArray(1), 0, 1))
        }
    }

    @Test fun aServerThatStopsHonouringRangeIsAnError() {
        reader("/forgetful").use { reader ->
            assertEquals(600_000L, reader.size())
            assertEquals("音源服务器不再支持断点续传，请重新播放", assertThrows(IOException::class.java) { reader.readFully(300_000, 10) }.message)
        }
    }

    @Test fun closeAbandonsAPendingResolve() {
        val entered = CountDownLatch(1)
        val reader = ProviderMediaReader({ entered.countDown(); Thread.sleep(30_000); url("/audio") }, { _, _, _ -> error("never opened") }, spool)
        var failure: Throwable? = null
        val reading = thread { failure = runCatching { reader.size() }.exceptionOrNull() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        reader.close()
        reading.join(2_000)
        assertFalse(reading.isAlive)
        assertEquals("音频读取已取消", failure?.message)
    }

    @Test fun closeCancelsABodyInFlight() {
        val reader = reader("/slow")
        var failure: Throwable? = null
        val reading = thread { failure = runCatching { reader.size() }.exceptionOrNull() }
        eventually { requests.isNotEmpty() }
        Thread.sleep(100)
        reader.close()
        reading.join(2_000) // well inside the 5 s call timeout, so only the cancel can have ended it
        assertFalse(reading.isAlive)
        assertTrue(failure.toString(), failure is IOException)
    }
}
