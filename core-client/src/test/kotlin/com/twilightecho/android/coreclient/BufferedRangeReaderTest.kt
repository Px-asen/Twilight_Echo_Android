package com.twilightecho.android.coreclient

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BufferedRangeReaderTest {
    private val data = ByteArray(4096) { (it % 251).toByte() }

    @Test fun probesStayDemandDrivenAndSequentialReadsEnableBoundedLookahead() {
        val reads = CopyOnWriteArrayList<Long>()
        val primed = CountDownLatch(1)
        BufferedRangeReader(data.size.toLong(), rangeBytes = 64, windowCount = 4) { start, count, _ ->
            reads += start
            if (reads.size == 4) primed.countDown()
            data.copyOfRange(start.toInt(), start.toInt() + count)
        }.use { reader ->
            val out = ByteArray(80) { -1 }
            assertEquals(8, reader.readAt(0, out, 4, 8))
            assertEquals(listOf(0L), reads.toList())
            assertArrayEquals(data.copyOfRange(0, 8), out.copyOfRange(4, 12))
            assertEquals((-1).toByte(), out[3])
            assertEquals(56, reader.readAt(8, out, 0, 64))
            assertTrue(primed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(0L, 64L, 128L, 192L), reads.toList())
            // All next bytes were downloaded before this demand, not at its Range boundary.
            assertEquals(64, reader.readAt(64, out, 0, 64))
            assertArrayEquals(data.copyOfRange(64, 128), out.copyOfRange(0, 64))
            assertEquals(-1, reader.readAt(data.size.toLong(), out, 0, 1))
            assertEquals(0, reader.readAt(0, out, 0, 0))
        }
    }

    @Test fun seekCancelsSpeculativeIoAndDiscardsItsLateBytesAndError() {
        val downloading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        BufferedRangeReader(data.size.toLong(), rangeBytes = 64, windowCount = 4) { start, count, request ->
            if (start == 64L) {
                request.onCancel { cancelled.countDown() }
                downloading.countDown()
                check(cancelled.await(2, TimeUnit.SECONDS))
                throw IOException("obsolete request")
            }
            data.copyOfRange(start.toInt(), start.toInt() + count)
        }.use { reader ->
            try {
                reader.readAt(0, ByteArray(64), 0, 64)
                assertTrue(downloading.await(2, TimeUnit.SECONDS))
                val output = ByteArray(32)
                val seek = worker.submit<Int> { reader.readAt(2048, output, 0, output.size) }
                assertEquals(32, seek.get(500, TimeUnit.MILLISECONDS).toInt())
                assertTrue(cancelled.await(500, TimeUnit.MILLISECONDS))
                assertArrayEquals(data.copyOfRange(2048, 2080), output)
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun forwardSeekPastAFullCacheDoesNotWaitForItsOwnConsumer() {
        val full = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val reads = AtomicInteger()
        BufferedRangeReader(data.size.toLong(), 64, 4) { start, count, _ ->
            if (reads.incrementAndGet() == 4) full.countDown()
            data.copyOfRange(start.toInt(), start.toInt() + count)
        }.use { reader ->
            try {
                reader.readAt(0, ByteArray(64), 0, 64)
                assertTrue(full.await(2, TimeUnit.SECONDS))
                val output = ByteArray(64)
                val read = worker.submit<Int> { reader.readAt(256, output, 0, 64) }
                assertEquals(64, read.get(500, TimeUnit.MILLISECONDS).toInt())
                assertArrayEquals(data.copyOfRange(256, 320), output)
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun closeCancelsNetworkAndWakesTheBlockedConsumer() {
        val downloading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val reader = BufferedRangeReader(4096, 64) { _, _, request ->
            request.onCancel { cancelled.countDown() }
            downloading.countDown()
            cancelled.await(5, TimeUnit.SECONDS)
            request.checkActive()
            error("cancelled reads never publish")
        }
        try {
            val read = worker.submit<Boolean> { runCatching { reader.readAt(0, ByteArray(8), 0, 8) }.exceptionOrNull() is IOException }
            assertTrue(downloading.await(2, TimeUnit.SECONDS))
            reader.close()
            assertTrue(read.get(500, TimeUnit.MILLISECONDS))
            assertTrue(cancelled.await(500, TimeUnit.MILLISECONDS))
            assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(8), 0, 8) }
        } finally { reader.close(); worker.shutdownNow() }
    }

    @Test fun speculativeFailureDoesNotDiscardPlayableCacheOrRetryForever() {
        val failed = CountDownLatch(1)
        val failures = AtomicInteger()
        BufferedRangeReader(data.size.toLong(), 64) { start, count, _ ->
            if (start == 64L) { failures.incrementAndGet(); failed.countDown(); throw IOException("revoked") }
            data.copyOfRange(start.toInt(), start.toInt() + count)
        }.use { reader ->
            val out = ByteArray(64)
            assertEquals(64, reader.readAt(0, out, 0, 64))
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertEquals(8, reader.readAt(8, out, 0, 8))
            assertEquals("revoked", assertThrows(IOException::class.java) { reader.readAt(64, out, 0, 8) }.message)
            assertEquals(1, failures.get())
        }
    }

    @Test fun shortFinalWindowAndBackwardSeekReturnExactBytes() {
        BufferedRangeReader(150, 64) { start, count, _ -> data.copyOfRange(start.toInt(), start.toInt() + count) }.use { reader ->
            val out = ByteArray(64)
            assertEquals(22, reader.readAt(128, out, 0, 64))
            assertArrayEquals(data.copyOfRange(128, 150), out.copyOfRange(0, 22))
            assertEquals(64, reader.readAt(0, out, 0, 64))
            assertArrayEquals(data.copyOfRange(0, 64), out)
        }
    }

    @Test fun cancellationBeforeConnectionRegistrationStillClosesThatConnection() {
        val request = RangeReadRequest()
        val closed = AtomicInteger()
        request.cancel()
        request.onCancel { closed.incrementAndGet() }
        assertEquals(1, closed.get())
        assertThrows(IOException::class.java) { request.checkActive() }
    }
}
