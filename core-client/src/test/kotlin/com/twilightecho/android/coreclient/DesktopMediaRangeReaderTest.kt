package com.twilightecho.android.coreclient

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation

class DesktopMediaRangeReaderTest {
    private class Fixture : Closeable {
        val data = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val requests = CopyOnWriteArrayList<String>()
        var responseStatus = 206
        var resolutions = 0
        var version = "file-v1"
        var firstExpired = false
        var stall: CountDownLatch? = null
        val reading = CountDownLatch(1)
        val identity = selfSignedTestCertificate()
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(identity.serverContext())
        }
        val base get() = "https://127.0.0.1:${server.address.port}"
        val ref = DesktopMediaReference("desktop-1", "library-1", "source-1")
        init {
            server.createContext("/media/") { exchange ->
                try {
                    assertEquals("Bearer device-secret", exchange.requestHeaders.getFirst("Authorization"))
                    assertEquals("\"file-v1\"", exchange.requestHeaders.getFirst("If-Match"))
                    val range = exchange.requestHeaders.getFirst("Range")
                    requests += range
                    if (firstExpired && requests.size == 1) exchange.sendResponseHeaders(404, -1)
                    else if (responseStatus != 206) {
                        exchange.responseHeaders.set("Location", "$base/steal")
                        exchange.sendResponseHeaders(responseStatus, -1)
                    } else {
                        val parts = range.removePrefix("bytes=").split('-').map { it.toInt() }
                        exchange.responseHeaders.set("Content-Range", "bytes ${parts[0]}-${parts[1]}/${data.size}")
                        exchange.responseHeaders.set("ETag", "\"file-v1\"")
                        exchange.sendResponseHeaders(206, (parts[1] - parts[0] + 1).toLong())
                        reading.countDown()
                        stall?.await(12, TimeUnit.SECONDS)
                        exchange.responseBody.write(data, parts[0], parts[1] - parts[0] + 1)
                    }
                } finally { exchange.close() }
            }
            server.createContext("/steal") { error("Redirect must not receive device credentials") }
            server.start()
        }
        fun reader() = DesktopMediaRangeReader(ref) { requested ->
            resolutions++
            if (requested.sourceVersion != null && requested.sourceVersion != version) throw IOException("source changed")
            AuthorizedDesktopMedia(base, "device-secret", DesktopMediaPlan("library-1", "source-1", version,
                "/media/" + (if (resolutions == 1) "a" else "b").repeat(24), "audio/flac", data.size.toLong(),
                System.currentTimeMillis() + 900000, 262144), identity.fingerprint)
        }
        override fun close() { stall?.countDown(); server.stop(0) }
    }

    @Test fun stableReferenceContainsNoEndpointOrAccessTicket() {
        val ref = DesktopMediaReference("computer", "library", "source", "v1")
        assertEquals(ref, DesktopMediaReference.parse(ref.resourceId()))
        assertNull(DesktopMediaReference.parse("http://desktop/media/secret"))
    }

    @Test fun boundedRangesSupportDecoderProbesAndSeeksWithoutDownloadingWholeFile() {
        Fixture().use { f -> f.reader().use { reader ->
            assertEquals(f.data.size.toLong(), reader.size())
            val first = ByteArray(64)
            assertEquals(64, reader.readAt(0, first, 0, 64))
            assertArrayEquals(f.data.copyOfRange(0, 64), first)
            reader.readAt(100, first, 0, 64)
            assertEquals(1, f.requests.size)
            reader.readAt(900000, first, 0, 64)
            assertArrayEquals(f.data.copyOfRange(900000, 900064), first)
            assertEquals(listOf("bytes=0-262143", "bytes=900000-1048575"), f.requests.toList())
            assertEquals(-1, reader.readAt(f.data.size.toLong(), first, 0, 64))
        } }
    }

    @Test fun expiredTicketRenewsOnceWithPinnedVersion() {
        Fixture().use { f -> f.firstExpired = true; f.reader().use { reader ->
            assertEquals(8, reader.readAt(10, ByteArray(8), 0, 8))
            assertEquals(2, f.resolutions)
            assertEquals(2, f.requests.size)
        } }
    }

    @Test fun changedFileAndRevocationFailWithoutLoopsOrRedirectedCredentials() {
        for (status in listOf(401, 409, 412, 302)) Fixture().use { f ->
            f.responseStatus = status
            f.reader().use { reader ->
                assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(8), 0, 8) }
                assertEquals(1, f.resolutions); assertEquals(1, f.requests.size)
            }
        }
        Fixture().use { f -> f.reader().use { reader ->
            reader.readAt(0, ByteArray(8), 0, 8)
            f.firstExpired = true
            // The existing plan is pinned; a source-generation mismatch is rejected by the reader.
            f.responseStatus = 409
            assertThrows(IOException::class.java) { reader.readAt(800000, ByteArray(8), 0, 8) }
        } }
    }

    @Test fun aDifferentCertificateNeverReceivesTheDeviceToken() {
        Fixture().use { f ->
            val other = selfSignedTestCertificate().fingerprint
            DesktopMediaRangeReader(f.ref) {
                AuthorizedDesktopMedia(f.base, "device-secret", DesktopMediaPlan("library-1", "source-1", f.version,
                    "/media/" + "a".repeat(24), "audio/flac", f.data.size.toLong(), System.currentTimeMillis() + 900000, 262144), other)
            }.use { reader ->
                assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(8), 0, 8) }
                assertTrue(f.requests.isEmpty())
            }
        }
    }

    @Test fun cancellationPreventsFurtherNetworkReads() {
        Fixture().use { f -> val reader = f.reader(); reader.size(); reader.close()
            assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(8), 0, 8) }
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun closingWhileResolvingCancelsThePendingPlan() {
        val entered = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val reader = DesktopMediaRangeReader(DesktopMediaReference("desktop", "library", "source")) {
            entered.countDown(); awaitCancellation()
        }
        try {
            val task = worker.submit<Boolean> { runCatching { reader.size() }.exceptionOrNull() is IOException }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            reader.close()
            assertTrue(task.get(2, TimeUnit.SECONDS))
        } finally { reader.close(); worker.shutdownNow() }
    }

    @Test fun closingDisconnectsAnActiveRangeRead() {
        Fixture().use { f ->
            f.stall = CountDownLatch(1)
            val worker = Executors.newFixedThreadPool(2)
            val reader = f.reader()
            try {
                val task = worker.submit<Boolean> { runCatching { reader.readAt(0, ByteArray(8), 0, 8) }.exceptionOrNull() is IOException }
                assertTrue(f.reading.await(2, TimeUnit.SECONDS))
                val closing = worker.submit { reader.close() }
                assertTrue(task.get(2, TimeUnit.SECONDS)); closing.get(2, TimeUnit.SECONDS)
            } finally { f.stall!!.countDown(); reader.close(); worker.shutdownNow() }
        }
    }
}
