package com.twilightecho.android.coreclient

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoreClientReconciliationTest {

    @Test
    fun `ignores stale sequence within same epoch and accepts newer sequence`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val events = MutableSharedFlow<CoreEventMessage>(extraBufferCapacity = 8)

        val initialSnapshot = CoreSnapshot(
            epoch = "epoch-1",
            sequence = 5L,
            phase = CorePhase.READY,
            playback = CorePlaybackView(state = "paused", position = 12.0),
            queue = CoreQueueReference(id = "default", revision = 2L, total = 3, index = 0)
        )

        val fakeTransport = object : CoreTransport {
            override val stateEvents = events
            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                return when (envelope.name) {
                    "core.getSnapshot" -> CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = "epoch-1",
                        ok = true,
                        value = DefaultCoreClient.DefaultJson.encodeToJsonElement(initialSnapshot)
                    )
                    "playback.getQueueWindow" -> CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = "epoch-1",
                        ok = true,
                        value = DefaultCoreClient.DefaultJson.encodeToJsonElement(
                            CoreQueueWindowView(queueId = "default", revision = 2L, total = 3)
                        )
                    )
                    else -> CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = "epoch-1",
                        ok = true
                    )
                }
            }

            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
        }

        val client = DefaultCoreClient(
            identity = CoreClientIdentity.androidPrimaryClient(),
            transport = fakeTransport,
            scope = scope
        )

        client.reconcileState()
        advanceUntilIdle()
        assertEquals(5L, client.snapshot.value?.sequence)

        // Emit stale sequence 4 -> should be ignored
        events.emit(
            CoreEventMessage(
                epoch = "epoch-1",
                sequence = 4L,
                snapshot = initialSnapshot.copy(
                    sequence = 4L,
                    playback = CorePlaybackView(state = "stopped", position = 0.0)
                )
            )
        )
        advanceUntilIdle()
        assertEquals(5L, client.snapshot.value?.sequence)
        assertEquals("paused", client.snapshot.value?.playback?.state)

        // Emit newer sequence 6 -> should be applied
        events.emit(
            CoreEventMessage(
                epoch = "epoch-1",
                sequence = 6L,
                snapshot = initialSnapshot.copy(
                    sequence = 6L,
                    playback = CorePlaybackView(state = "playing", position = 18.5)
                )
            )
        )
        advanceUntilIdle()
        assertEquals(6L, client.snapshot.value?.sequence)
        assertEquals("playing", client.snapshot.value?.playback?.state)
    }
}
