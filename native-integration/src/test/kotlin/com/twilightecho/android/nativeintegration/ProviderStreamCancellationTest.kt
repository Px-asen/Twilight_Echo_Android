package com.twilightecho.android.nativeintegration

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class ProviderStreamCancellationTest {
    @Test fun cancellingAReaderDoesNotWaitForPluginResolution() {
        val started = CountDownLatch(1)
        val reader = ProviderStreamDataSource(resolveUrl = {
            started.countDown(); Thread.sleep(60_000); "https://m1.music.126.net/fixture.flac"
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val size = executor.submit<Long> { reader.size }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            reader.close()
            assertThrows(ExecutionException::class.java) { size.get(500, TimeUnit.MILLISECONDS) }
        } finally { reader.close(); executor.shutdownNow() }
    }
}
