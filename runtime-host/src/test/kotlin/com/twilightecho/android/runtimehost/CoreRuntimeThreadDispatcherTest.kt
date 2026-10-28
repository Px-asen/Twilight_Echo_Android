package com.twilightecho.android.runtimehost

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CoreRuntimeThreadDispatcherTest {
    @Test fun cleanupRunsOnTheVmOwnerThreadBeforeShutdown() = runBlocking {
        val dispatcher = CoreRuntimeThreadDispatcher()
        val done = CountDownLatch(1)
        val order = mutableListOf<String>()
        dispatcher.runOnRuntimeThread { order += "request" }
        dispatcher.closeAfter {
            assertTrue(dispatcher.isOnRuntimeThread())
            order += "close"
            done.countDown()
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("request", "close"), order)
        dispatcher.close() // idempotent
    }
}
