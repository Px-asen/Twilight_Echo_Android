package com.twilightecho.android.coreclient

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoreClientRequestIdTest {
    @Test fun systemPlayDoesNotCollideWithAnotherClientsPendingQuery() = runTest {
        val releaseQuery = CompletableDeferred<Unit>()
        val requests = mutableListOf<CoreRequestEnvelope>()
        val pending = mutableSetOf<String>()
        val transport = object : CoreTransport {
            override val stateEvents = emptyFlow<CoreEventMessage>()

            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                requests += envelope
                if (!pending.add(envelope.requestId)) return CoreResponseEnvelope(
                    requestId = envelope.requestId, epoch = "test", ok = false,
                    error = CoreErrorView(CoreErrorCode.INVALID_REQUEST, "Request is already in flight"))
                return try {
                    val value = if (envelope.name == "core.getSnapshot") {
                        releaseQuery.await()
                        DefaultCoreClient.DefaultJson.encodeToJsonElement(CoreSnapshot(epoch = "test", sequence = 1))
                    } else JsonObject(emptyMap())
                    CoreResponseEnvelope(requestId = envelope.requestId, epoch = "test", ok = true, value = value)
                } finally {
                    pending.remove(envelope.requestId)
                }
            }

            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val identity = CoreClientIdentity.androidPrimaryClient()
        val uiClient = DefaultCoreClient(identity, transport, backgroundScope, processingDispatcher = dispatcher)
        val mediaClient = DefaultCoreClient(identity, transport, backgroundScope, processingDispatcher = dispatcher)
        val query = async { uiClient.querySnapshot() }
        runCurrent()
        assertEquals(1, pending.size)
        try {
            val play = async { mediaClient.executeCommand(CoreTypedCommand.Play()) }
            runCurrent()
            assertTrue("System Play must succeed while the UI query is still pending", play.await().isSuccess)
            assertEquals(1, pending.size)
            assertEquals(2, requests.map { it.requestId }.toSet().size)
        } finally {
            releaseQuery.complete(Unit)
        }
        assertTrue(query.await().isSuccess)
        assertTrue(pending.isEmpty())
    }

    @Test fun recreatedClientsWithTheSameIdentityDoNotReuseRequestIds() = runTest {
        val requests = mutableListOf<CoreRequestEnvelope>()
        val transport = object : CoreTransport {
            override val stateEvents = emptyFlow<CoreEventMessage>()
            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                requests += envelope
                return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "test", ok = true)
            }
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val identity = CoreClientIdentity.androidPrimaryClient()
        repeat(3) {
            val client = DefaultCoreClient(identity, transport, backgroundScope, processingDispatcher = dispatcher)
            assertTrue(client.executeCommand(CoreTypedCommand.Play()).isSuccess)
            assertTrue(client.toggleFavorite("track").isSuccess)
        }
        assertEquals(6, requests.size)
        assertEquals("All request paths need an instance-unique namespace", 6, requests.map { it.requestId }.toSet().size)
    }
}
