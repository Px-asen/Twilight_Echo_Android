package com.twilightecho.android.nativeintegration

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resumeWithException

internal interface AutoMixCancellationTokens {
    fun create(): Long
    fun cancel(token: Long)
    fun destroy(token: Long)
}

/** One bounded analysis lane, independent of transport. Tokens remain alive
 * until the native call returns; cancellation never destroys an in-use token. */
internal class AutoMixAnalysisWorker(private val tokens: AutoMixCancellationTokens) {
    private class Work { var cancelled = false; var token = 0L }
    private val lock = Any()
    private val work = mutableMapOf<String, Work>()
    // Cancellation can arrive on its separate bridge lane before execute().
    private val cancelled = linkedSetOf<String>()
    private var closed = false
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TwilightAutoMix-Analysis").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    suspend fun <T> execute(id: String, action: (Long) -> T): T = suspendCancellableCoroutine { continuation ->
        val job = Work()
        synchronized(lock) {
            if (closed || work.size >= 4 || work.containsKey(id)) {
                continuation.resumeWithException(IOException(if (closed) "analysis_service_unavailable" else "analysis_queue_full"))
                return@suspendCancellableCoroutine
            }
            job.cancelled = cancelled.remove(id)
            work[id] = job
            executor.execute {
                val result = runCatching {
                    val token = synchronized(lock) {
                        if (job.cancelled) throw IOException("analysis_cancelled")
                        tokens.create().also { check(it != 0L) { "analysis_service_unavailable" }; job.token = it }
                    }
                    action(token)
                }
                synchronized(lock) {
                    if (job.token != 0L) tokens.destroy(job.token)
                    job.token = 0L
                    work.remove(id)
                }
                continuation.resumeWith(result)
            }
        }
        continuation.invokeOnCancellation { cancel(id) }
    }

    fun cancel(id: String) = synchronized(lock) {
        val job = work[id]
        if (job == null) {
            cancelled.add(id)
            while (cancelled.size > 64) cancelled.remove(cancelled.first())
        } else {
            job.cancelled = true
            if (job.token != 0L) tokens.cancel(job.token)
        }
    }

    /** Run engine disposal only after all analysis and token cleanup finished. */
    fun close(onIdle: () -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        work.values.forEach { it.cancelled = true; if (it.token != 0L) tokens.cancel(it.token) }
        executor.execute(onIdle)
        executor.shutdown()
    }
}
