package com.twilightecho.android.nativeintegration

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProviderStreamDataSourceTest {
    private class Fixture {
        val data = ByteArray(2 * 1024 * 1024 + 37) { (it % 251).toByte() }
        val ranges = CopyOnWriteArrayList<String>()
        val prefetched = CountDownLatch(1)
        var tag = "v1"
        var status = 206
        var resolutions = 0
        fun reader() = ProviderStreamDataSource(resolveUrl = { resolutions++; "https://m1.music.126.net/fixture.flac" },
            openConnection = { uri -> object : HttpURLConnection(uri.toURL()) {
                private val first get() = getRequestProperty("Range").removePrefix("bytes=").substringBefore('-').toInt()
                private val last get() = getRequestProperty("Range").substringAfter('-').toInt()
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode(): Int { ranges += getRequestProperty("Range"); return status }
                override fun getHeaderField(name: String) = when (name) {
                    "Content-Range" -> "bytes $first-$last/${data.size}"
                    "ETag" -> tag
                    else -> null
                }
                override fun getInputStream(): ByteArrayInputStream {
                    if (first == 256 * 1024) prefetched.countDown()
                    return ByteArrayInputStream(data, first, last - first + 1)
                }
            } })
    }

    @Test fun cdnRangesArePrefetchedAndSeeksStillReadTheirOwnExactBytes() {
        val f = Fixture()
        f.reader().use { reader ->
            assertEquals(f.data.size.toLong(), reader.size)
            assertEquals(listOf("bytes=0-0"), f.ranges.toList())
            val bytes = ByteArray(32768)
            assertEquals(bytes.size, reader.readAt(0, bytes, 0, bytes.size))
            assertArrayEquals(f.data.copyOfRange(0, bytes.size), bytes)
            assertTrue(f.prefetched.await(2, TimeUnit.SECONDS))
            assertEquals(bytes.size, reader.readAt(256 * 1024L, bytes, 0, bytes.size))
            assertArrayEquals(f.data.copyOfRange(256 * 1024, 256 * 1024 + bytes.size), bytes)
            assertEquals(37, reader.readAt(2 * 1024 * 1024L, bytes, 0, bytes.size))
            assertArrayEquals(f.data.takeLast(37).toByteArray(), bytes.copyOfRange(0, 37))
            assertEquals(-1, reader.readAt(f.data.size.toLong(), bytes, 0, bytes.size))
            assertEquals(1, f.resolutions)
        }
    }

    @Test fun prefetchNeverBypassesVersionOrCdnAuthorization() {
        val f = Fixture()
        f.reader().use { reader ->
            reader.size
            f.tag = "replaced"
            assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(8), 0, 8) }
        }
        ProviderStreamDataSource(resolveUrl = { "https://example.com/stolen.flac" },
            openConnection = { _: URI -> error("untrusted hosts must never be contacted") }).use { reader ->
            assertThrows(IllegalArgumentException::class.java) { reader.size }
        }
    }
}
