package com.twilightecho.android.nativeintegration

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AutoMixAnalysisWorkerTest {
    private class Tokens : AutoMixCancellationTokens {
        val events = CopyOnWriteArrayList<String>()
        val live = ConcurrentHashMap.newKeySet<Long>()
        private val next = AtomicLong()
        override fun create(): Long = next.incrementAndGet().also { live.add(it); events.add("create:$it") }
        override fun cancel(token: Long) { check(token in live); events.add("cancel:$token") }
        override fun destroy(token: Long) { check(live.remove(token)); events.add("destroy:$token") }
    }
    private suspend fun CountDownLatch.waitFor() = withContext(Dispatchers.IO) { assertTrue(await(5, TimeUnit.SECONDS)) }
    private suspend fun AutoMixAnalysisWorker.drain() {
        val done = CountDownLatch(1)
        close { done.countDown() }
        done.waitFor()
    }

    @Test fun cancelBeforeEnqueueNeedsNoNativeToken() = runBlocking {
        val tokens = Tokens(); val worker = AutoMixAnalysisWorker(tokens)
        try {
            worker.cancel("early")
            val result = runCatching { worker.execute("early") { error("must not run") } }
            assertEquals("analysis_cancelled", result.exceptionOrNull()?.message)
            assertTrue(tokens.events.isEmpty())
            assertEquals(7, worker.execute("fresh") { 7 })
            assertEquals(listOf("create:1", "destroy:1"), tokens.events.toList())
        } finally { worker.drain() }
    }

    @Test fun liveCancellationRetainsTokenUntilNativeReturns() = runBlocking {
        val tokens = Tokens(); val worker = AutoMixAnalysisWorker(tokens)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val running = async(Dispatchers.Default) { worker.execute("live") { token ->
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); token
            } }
            entered.waitFor(); worker.cancel("live")
            assertEquals(listOf("create:1", "cancel:1"), tokens.events.toList())
            assertEquals(setOf(1L), tokens.live.toSet())
            release.countDown()
            assertEquals(1L, running.await())
            assertEquals(listOf("create:1", "cancel:1", "destroy:1"), tokens.events.toList())
            assertTrue(tokens.live.isEmpty())
        } finally { release.countDown(); worker.drain() }
    }

    @Test fun queuedCoroutineCancellationNeverStartsNativeWork() = runBlocking {
        val tokens = Tokens(); val worker = AutoMixAnalysisWorker(tokens)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val active = async(Dispatchers.Default) { worker.execute("active") { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
            entered.waitFor()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { worker.execute("queued") { error("cancelled queue ran") } }
            queued.cancelAndJoin()
            release.countDown(); active.await()
            assertEquals(3, worker.execute("barrier") { 3 })
            assertEquals(listOf("create:1", "destroy:1", "create:2", "destroy:2"), tokens.events.toList())
        } finally { release.countDown(); worker.drain() }
    }

    @Test fun queueIsBoundedAndDuplicateIdsCannotReplaceActiveWork() = runBlocking {
        val tokens = Tokens(); val worker = AutoMixAnalysisWorker(tokens)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val active = async(Dispatchers.Default) { worker.execute("active") { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
            entered.waitFor()
            val queued = (1..3).map { i -> async(start = CoroutineStart.UNDISPATCHED) { worker.execute("q$i") { i } } }
            assertEquals("analysis_queue_full", runCatching { worker.execute("overflow") { 0 } }.exceptionOrNull()?.message)
            assertEquals("analysis_queue_full", runCatching { worker.execute("active") { 0 } }.exceptionOrNull()?.message)
            release.countDown(); active.await()
            assertEquals(listOf(1, 2, 3), queued.awaitAll())
            assertTrue(tokens.live.isEmpty())
        } finally { release.countDown(); worker.drain() }
    }

    @Test fun closeCancelsActiveAndQueuedThenDisposesAfterTokenCleanup() = runBlocking {
        val tokens = Tokens(); val worker = AutoMixAnalysisWorker(tokens)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val disposed = CountDownLatch(1)
        try {
            val active = async(Dispatchers.Default) { worker.execute("active") { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
            entered.waitFor()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { worker.execute("queued") { error("closed queue ran") } } }
            worker.close { tokens.events.add("dispose"); disposed.countDown() }
            worker.close { error("double disposal") }
            assertEquals(1L, disposed.count)
            assertEquals(listOf("create:1", "cancel:1"), tokens.events.toList())
            assertEquals("analysis_service_unavailable", runCatching { worker.execute("late") { 0 } }.exceptionOrNull()?.message)
            release.countDown(); active.await()
            assertEquals("analysis_cancelled", queued.await().exceptionOrNull()?.message)
            disposed.waitFor()
            assertEquals(listOf("create:1", "cancel:1", "destroy:1", "dispose"), tokens.events.toList())
            assertTrue(tokens.live.isEmpty())
        } finally { release.countDown() }
    }
}
