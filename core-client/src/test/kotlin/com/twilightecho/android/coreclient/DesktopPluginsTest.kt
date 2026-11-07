package com.twilightecho.android.coreclient

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DesktopPluginsTest {
    private class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        override val isRemote = true
        var route = 1L
        override val generation get() = route
        val requests = mutableListOf<CoreRequestEnvelope>()
        var respond: (CoreRequestEnvelope) -> CoreResponseEnvelope = { request -> CoreResponseEnvelope(
            requestId = request.requestId, epoch = "e1", ok = true,
            value = buildJsonObject { put("result", buildJsonObject { put("uploadId", "upload"); put("chunkBytes", 786432) }) }) }
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope { requests += envelope; return respond(envelope) }
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
    }
    @Test fun uploadUsesPackageBytesAndEditKeepsObservedVersion() = runTest {
        val transport = Transport(); val client = DesktopPluginsClient(transport) { "e1" }; val scope = client.scope()
        val upload = client.begin(scope, "radio.tep", 300).getOrThrow()
        assertEquals("upload", upload.uploadId)
        assertEquals(setOf("fileName", "byteSize"), transport.requests.single().payload.keys)
        assertNotNull(transport.requests.single().commandId)
        transport.respond = { request -> CoreResponseEnvelope(requestId = request.requestId, epoch = "e1", ok = true,
            value = buildJsonObject { put("result", JsonNull) }) }
        client.edit(scope, DesktopManagedPlugin("radio", "Radio", "1.0.0", token = "observed-version"), "disable").getOrThrow()
        assertEquals("observed-version", transport.requests.last().payload["token"]!!.jsonPrimitive.content)
    }
    @Test fun computerSwitchAndLateRepliesCannotCrossLibraryScope() = runTest {
        val transport = Transport(); var epoch = "e1"; val client = DesktopPluginsClient(transport) { epoch }; val scope = client.scope()
        transport.route++
        assertTrue(client.begin(scope, "radio.tep", 1).isFailure)
        assertTrue(transport.requests.isEmpty())
        transport.route--; epoch = "e2"
        assertTrue(client.list(scope).isFailure)
        epoch = "e1"
        val reply = transport.respond
        transport.respond = { request -> transport.route++; reply(request) }
        assertTrue(client.begin(scope, "radio.tep", 1).isFailure)
    }
}
