package com.twilightecho.android.coreclient

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One budget per connection, shared by catalog, index, artwork and playback clients. */
internal class DesktopRpcBudget(private val now: () -> Long) {
    private val mutex = Mutex()
    private val starts = ArrayDeque<Long>()
    private var updatedAt = now()
    private var readCredit = READ_BURST * READ_SPACING_MS
    private var blockedUntil = 0L

    suspend fun acquire(urgent: Boolean, waitForSlot: Boolean = true): Boolean {
        while (true) {
            val wait = mutex.withLock {
                val at = now()
                readCredit = (readCredit + (at - updatedAt).coerceAtLeast(0))
                    .coerceAtMost(READ_BURST * READ_SPACING_MS)
                updatedAt = at
                while (starts.isNotEmpty() && at - starts.first() >= WINDOW_MS) starts.removeFirst()
                // Reserve ten slots for controls, state reconciliation and media tickets.
                val limit = if (urgent) 58 else 48
                val windowWait = if (starts.size >= limit) starts.first() + WINDOW_MS - at else 0
                val paceWait = if (urgent) 0 else (READ_SPACING_MS - readCredit).coerceAtLeast(0)
                maxOf(blockedUntil - at, windowWait, paceWait, 0).also { wait ->
                    if (wait == 0L) {
                        starts.addLast(at)
                        if (!urgent) readCredit -= READ_SPACING_MS
                    }
                }
            }
            if (wait == 0L) return true
            if (!waitForSlot) return false
            // Never hold the lock while waiting: Play/Pause can pass queued bulk reads.
            delay(wait)
        }
    }

    /** Another controller can exhaust the server-wide budget; all our readers then wait together. */
    suspend fun rateLimited() = mutex.withLock { blockedUntil = maxOf(blockedUntil, now() + WINDOW_MS) }

    private companion object {
        const val WINDOW_MS = 60_001L
        const val READ_SPACING_MS = 1_250L
        const val READ_BURST = 4
    }
}
