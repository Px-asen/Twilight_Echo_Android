package com.twilightecho.android.runtimehost

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated single-threaded execution context owning the JavaScript runtime and Music Core instance
 * (Architecture Plan Section 10.5: "Core 专属串行执行上下文").
 *
 * Invariants:
 * 1. All JS evaluations, microtask drains, timer callbacks, and native event deliveries MUST
 *    run sequentially on this single thread.
 * 2. Never blocks the Android UI Main thread or the C++/Oboe real-time audio callback thread.
 */
class CoreRuntimeThreadDispatcher(
    threadName: String = "TwilightCore-JsRuntime"
) : Closeable {

    @Volatile
    private var ownerThreadId: Long = -1L

    private val closed = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            ownerThreadId = Thread.currentThread().id
            runnable.run()
        }, threadName).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    fun isOnRuntimeThread(): Boolean = Thread.currentThread().id == ownerThreadId

    fun checkOnRuntimeThread() {
        check(isOnRuntimeThread()) {
            "Expected execution on TwilightCore-JsRuntime thread ($ownerThreadId), " +
                "but was on thread ${Thread.currentThread().name} (${Thread.currentThread().id})"
        }
    }

    suspend fun <T> runOnRuntimeThread(block: suspend () -> T): T {
        check(!closed.get()) { "CoreRuntimeThreadDispatcher has already been closed" }
        return withContext(dispatcher) {
            block()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow()
        }
    }
}
