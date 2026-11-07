package com.twilightecho.android.app

import com.twilightecho.android.coreclient.CoreClient
import com.twilightecho.android.coreclient.CoreQueueEdits
import com.twilightecho.android.coreclient.CoreTypedCommand

/** Uses the latest queue revision; ReplaceQueue deliberately rejects an empty track list. */
internal suspend fun clearTestPlaybackQueue(client: CoreClient) {
    val stop = client.executeCommand(CoreTypedCommand.Stop)
    check(stop.isSuccess) { "Stop failed during cleanup: ${stop.errorOrNull()}" }
    val current = client.reconcileState()
    check(current.isSuccess) { "Cannot read cleanup queue: ${current.errorOrNull()}" }
    current.getOrNull()!!.queue?.let { queue ->
        val clear = client.executeCommand(CoreTypedCommand.QueueEdit(queue.id, queue.revision, CoreQueueEdits.clear()))
        check(clear.isSuccess) { "Queue clear failed during cleanup: ${clear.errorOrNull()}" }
    }
    val restored = client.reconcileState()
    check(restored.isSuccess) { "Cannot verify cleanup queue: ${restored.errorOrNull()}" }
    check(restored.getOrNull()!!.let { it.track == null && (it.queue?.total ?: 0) == 0 }) {
        "Playback cleanup left a selected track or nonempty queue"
    }
}
