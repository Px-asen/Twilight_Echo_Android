package com.twilightecho.android.coreclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DesktopSessionQueueTest {
    private val json = DefaultCoreClient.DefaultJson
    private val samples by lazy { json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/desktop-session-wire.json"))
        .bufferedReader().use { it.readText() }).jsonObject.getValue("samples").jsonObject }
    private inner class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        var remote = true; override val isRemote get() = remote
        var route = 1L; override val generation get() = route
        val requests = mutableListOf<CoreRequestEnvelope>()
        var respond: suspend (CoreRequestEnvelope) -> CoreResponseEnvelope = { request ->
            CoreResponseEnvelope(requestId = request.requestId, epoch = "e1", ok = true, value = when {
                request.kind == "command" -> buildJsonObject { put("snapshot", buildJsonObject {}) }
                request.name == "groups.list" -> buildJsonObject { put("revision", 0); put("groups", JsonArray(emptyList())) }
                else -> samples.getValue(request.name)
            })
        }
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope { requests += envelope; return respond(envelope) }
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
    }
    @Test fun actualPairedTlsSamplesDecodeAndCommandsRetainExactSessionQueueAndOccurrence() = runTest {
        val transport = Transport(); val client = DesktopSessionQueueClient(transport) { "e1" }; val scope = client.scope()
        val destination = client.destinations(scope).getOrThrow().first()
        assertEquals("zone", destination.target.kind)
        val page = client.page(scope, destination).getOrThrow()
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.first().track!!.id.startsWith("remote-track:"))
        client.select(scope, destination, page.items.first()).getOrThrow()
        val command = transport.requests.last()
        assertEquals("zones.queue.select", command.name)
        assertEquals(page.items.first().queueEntryId, command.payload["occurrenceId"]!!.jsonPrimitive.content)
        assertEquals(destination.queue!!.id, command.payload["queue"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertNotNull(command.commandId)
        val sources = json.decodeFromJsonElement<CatalogSourcePage>(samples.getValue("catalog.sources"))
        client.load(scope, destination, catalogQueueSelection(sources.revision, listOf(sources.items[0].id, sources.items[0].id)), false).getOrThrow()
        val load = transport.requests.last().payload
        assertFalse(load["play"]!!.jsonPrimitive.boolean)
        assertEquals(2, load["selection"]!!.jsonObject["sourceIds"]!!.jsonArray.size)
        assertEquals(destination.queue.occurrenceId, load["queue"]!!.jsonObject["occurrenceId"]!!.jsonPrimitive.contentOrNull)
    }
    @Test fun emptyQueueHasExplicitNullOccurrenceAndGroupTargetNeverBecomesLocal() = runTest {
        val transport = Transport(); val client = DesktopSessionQueueClient(transport) { "e1" }; val scope = client.scope()
        val group = DesktopQueueDestination(DesktopQueueTarget("group", "group-a", 17), "全屋", DesktopZoneQueue("group-q", 9))
        client.load(scope, group, catalogQueueSelection(2, listOf("source-a")), false).getOrThrow()
        val p = transport.requests.single().payload
        assertEquals("group", p["target"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("group-a", p["target"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, p["queue"]!!.jsonObject["occurrenceId"])
        assertFalse(transport.requests.any { it.name.startsWith("playback.") })
    }
    @Test fun routeAndEpochChangesRejectOldWritesAndLateRepliesWithoutFallbackOrReplay() = runTest {
        val transport = Transport(); var epoch = "e1"
        val client = DesktopSessionQueueClient(transport) { epoch }; val scope = client.scope()
        val target = DesktopQueueDestination(DesktopQueueTarget("zone", "local", 0), "主会话", null)
        transport.route++
        assertTrue(client.load(scope, target, catalogQueueSelection(1, listOf("s")), true).isFailure)
        assertTrue(transport.requests.isEmpty())
        transport.route--; epoch = "e2"
        assertTrue(client.destinations(scope).isFailure)
        assertTrue(transport.requests.isEmpty())
        epoch = "e1"
        transport.respond = { request -> transport.route++; CoreResponseEnvelope(requestId = request.requestId, epoch = "e1", ok = true, value = samples.getValue("zones.list")) }
        assertTrue(client.destinations(scope).isFailure)
        assertEquals(1, transport.requests.size)
    }
    @Test fun cancellationPropagatesAndUnknownMutationIsSentOnce() = runTest {
        val transport = Transport(); val client = DesktopSessionQueueClient(transport) { "e1" }; val scope = client.scope()
        val target = DesktopQueueDestination(DesktopQueueTarget("zone", "local", 0), "主会话", null)
        transport.respond = { throw IllegalStateException("response lost") }
        assertTrue(client.load(scope, target, catalogQueueSelection(1, listOf("s")), true).isFailure)
        assertEquals(1, transport.requests.size)
        transport.respond = { throw CancellationException("leave") }
        try { client.catalog(scope, "夜航"); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(2, transport.requests.size)
    }
}
