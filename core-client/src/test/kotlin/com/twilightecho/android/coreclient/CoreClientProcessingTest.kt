package com.twilightecho.android.coreclient

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

class CoreClientProcessingTest {
    private class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        override var generation = 0L
        val requests = mutableListOf<CoreRequestEnvelope>()
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            requests += envelope
            return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "test", ok = true)
        }
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
    }

    @Test fun bulkQueueEncodingRunsOffTheCallerThread() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "core-json-test") }.asCoroutineDispatcher().use { worker ->
            val transport = Transport()
            val scope = CoroutineScope(SupervisorJob())
            try {
                val caller = Thread.currentThread()
                var reads = 0
                val tracks = object : AbstractList<CoreTrackView>() {
                    override val size = 1000
                    override fun get(index: Int): CoreTrackView {
                        assertNotSame("Track serialization must not block its UI caller", caller, Thread.currentThread())
                        assertTrue(Thread.currentThread().name.startsWith("core-json-test"))
                        reads++
                        return CoreTrackView("$index", title = "Song $index", source = "/music/$index.flac")
                    }
                }
                val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), transport, scope,
                    processingDispatcher = worker)
                assertTrue(client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = tracks)).isSuccess)
                assertEquals(1000, reads)
                assertEquals(1000, transport.requests.single().payload["tracks"]!!.jsonArray.size)
            } finally { scope.cancel() }
        }
    }

    @Test fun sourceChangeDuringQueueEncodingDoesNotControlTheNewOutput() = runBlocking {
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val tracks = object : AbstractList<CoreTrackView>() {
                override val size = 1
                override fun get(index: Int): CoreTrackView {
                    transport.generation++
                    return CoreTrackView("old-phone-song", title = "Phone song")
                }
            }
            val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), transport, scope)
            val result = client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = tracks))
            assertTrue(result is CoreResult.Failure && result.error.code == CoreErrorCode.CANCELLED)
            assertTrue(transport.requests.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun sourceChangeDuringSnapshotDecodingCancelsQueriesAndCommands() = runBlocking {
        for (command in listOf(false, true)) {
            val snapshotJson = DefaultCoreClient.DefaultJson.encodeToJsonElement(CoreSnapshot(epoch = "old", sequence = 1))
            val transport = object : CoreTransport {
                override val stateEvents = emptyFlow<CoreEventMessage>()
                override var generation = 0L
                override suspend fun sendRequest(envelope: CoreRequestEnvelope) = CoreResponseEnvelope(
                    requestId = envelope.requestId, epoch = "old", ok = true,
                    value = if (command) buildJsonObject { put("snapshot", snapshotJson) } else snapshotJson)
                override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
            }
            var processingCalls = 0
            val worker = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    if (++processingCalls == if (command) 2 else 1) transport.generation++
                    block.run()
                }
            }
            val scope = CoroutineScope(SupervisorJob())
            try {
                val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), transport, scope,
                    processingDispatcher = worker)
                val result = if (command) client.executeCommand(CoreTypedCommand.Next) else client.querySnapshot()
                assertTrue(result is CoreResult.Failure && result.error.code == CoreErrorCode.CANCELLED)
                assertNull("A decoded old snapshot must not repopulate the new source", client.snapshot.value)
            } finally { scope.cancel() }
        }
    }

    @Test fun cancellingBeforeDecodeDoesNotTurnCancellationIntoAnInvalidJsonResult() = runBlocking {
        for (command in listOf(false, true)) {
            val snapshotJson = DefaultCoreClient.DefaultJson.encodeToJsonElement(CoreSnapshot(epoch = "test", sequence = 1))
            val transport = object : CoreTransport {
                override val stateEvents = emptyFlow<CoreEventMessage>()
                override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                    currentCoroutineContext().cancel(CancellationException("cancel decode"))
                    return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "test", ok = true,
                        value = if (command) buildJsonObject { put("snapshot", snapshotJson) } else snapshotJson)
                }
                override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
            }
            val scope = CoroutineScope(SupervisorJob())
            var returned = false
            var cancelled = false
            try {
                val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), transport, scope)
                val task = launch {
                    try {
                        if (command) client.executeCommand(CoreTypedCommand.Next) else client.querySnapshot()
                        returned = true
                    } catch (_: CancellationException) { cancelled = true }
                }
                task.join()
                assertFalse("A cancelled caller must not continue with a parsed result", returned)
                assertTrue(cancelled)
                assertNull(client.snapshot.value)
            } finally { scope.cancel() }
        }
    }
}
