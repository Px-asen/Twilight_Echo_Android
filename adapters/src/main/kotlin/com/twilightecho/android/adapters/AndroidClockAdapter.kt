package com.twilightecho.android.adapters

import android.os.SystemClock
import com.twilightecho.android.runtimehost.CancelHandle
import com.twilightecho.android.runtimehost.ClockPort
import com.twilightecho.android.runtimehost.SchedulerPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Android implementation of `ClockPort` and `SchedulerPort` (Section 8.1).
 * Uses `System.currentTimeMillis()` for wall-clock and `SystemClock.elapsedRealtime()`
 * (with `System.nanoTime()` fallback in local JVM tests) for monotonic time.
 */
class AndroidClockAdapter(
    private val schedulerScope: CoroutineScope
) : ClockPort, SchedulerPort {

    override fun nowUnixMs(): Long = System.currentTimeMillis()

    override fun nowMonotonicMs(): Long = runCatching {
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed > 0L) elapsed else System.nanoTime() / 1_000_000L
    }.getOrElse {
        System.nanoTime() / 1_000_000L
    }

    override fun schedule(delayMs: Long, task: () -> Unit): CancelHandle {
        val job = schedulerScope.launch {
            delay(max(0L, delayMs))
            task()
        }
        return CancelHandle {
            job.cancel()
        }
    }
}
