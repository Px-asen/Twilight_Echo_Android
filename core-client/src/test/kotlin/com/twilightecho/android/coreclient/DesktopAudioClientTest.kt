package com.twilightecho.android.coreclient

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DesktopAudioClientTest {
    private class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        var remote = true
        override val isRemote get() = remote
        var route = 5L
        override val generation get() = route
        var failCommand = false
        var received: ((CoreRequestEnvelope) -> Unit)? = null
        val requests = mutableListOf<CoreRequestEnvelope>()
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            requests += envelope
            received?.invoke(envelope)
            val body = when (envelope.name) {
                "zones.state" -> """{"revision":7,"selectedOutputId":"bridge","selected":true,"connected":true,"outputs":[{"id":"bridge","name":"Study DAC","available":true}],"futureField":true}"""
                "audio.dsp" -> """{"revision":12,"processing":{"eqEnabled":true,"eqPreamp":-6},"scenes":[{"id":"room","name":"Room correction","enabled":true}]}"""
                "audio.signalPath" -> """{"transport":{"kind":"tls-pcm","format":"float32-le"},"receiver":{"consumedFrames":96000},"hardware":null}"""
                "zones.list" -> """{"revision":4,"limit":4,"zones":[{"id":"local","name":"Main","revision":3,"queue":{"id":"main-q","revision":9,"occurrenceId":"duplicate:second"}},{"id":"zone-11111111-1111-4111-8111-111111111111","name":"Study","revision":8,"track":{"title":"Study song"},"playback":{"state":"paused","position":12},"output":{"connected":true},"queue":{"id":"study-q","revision":2,"occurrenceId":"study:first"}}]}"""
                "zones.dsp" -> """{"revision":21,"processing":{"eqPreamp":-3},"scenes":[]}"""
                "zones.signalPath" -> """{"zoneId":"zone-11111111-1111-4111-8111-111111111111","transport":{"kind":"tls-pcm"}}"""
                else -> "{}"
            }
            return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "core-a", ok = !failCommand || envelope.kind != "command",
                value = Json.parseToJsonElement(body), error = if (failCommand && envelope.kind == "command")
                    CoreErrorView(CoreErrorCode.OUTCOME_UNKNOWN, "check state") else null)
        }
    }
    @Test fun outputAndDspBelongToTheSelectedCoreWithoutEditingPhoneSettings() = runTest {
        val transport = Transport()
        val client = DesktopAudioClient(transport) { "core-a" }
        val snapshot = client.snapshot().getOrThrow()
        assertEquals("Study DAC", snapshot.zone.outputs.single().name)
        assertEquals(-6, snapshot.dsp.processing["eqPreamp"]?.jsonPrimitive?.int)
        client.selectOutput(snapshot.zone.revision, "bridge", snapshot.targetGeneration).getOrThrow()
        client.processing(snapshot.dsp.revision, buildJsonObject { put("eqPreamp", -9) }, snapshot.targetGeneration).getOrThrow()
        assertEquals(listOf("zones.state", "audio.dsp", "audio.signalPath", "zones.select", "audio.dsp.edit"), transport.requests.map { it.name })
        assertTrue(transport.requests.all { it.epoch == "core-a" })
        assertEquals(12, transport.requests.last().payload["revision"]?.jsonPrimitive?.int)
        assertEquals(transport.requests.size, transport.requests.map { it.requestId }.toSet().size)
        transport.route++
        assertTrue(client.processing(12, buildJsonObject { put("eqPreamp", -3) }, snapshot.targetGeneration).isFailure)
        assertEquals(5, transport.requests.size) // never apply the old screen's edit to a different computer
        transport.remote = false
        assertTrue(client.snapshot().isFailure)
        assertEquals(5, transport.requests.size) // never fall back to embedded/phone Core
    }
    @Test fun anUncertainCommandIsNotReplayed() = runTest {
        val transport = Transport().apply { failCommand = true }
        val client = DesktopAudioClient(transport) { "core-a" }
        assertTrue(client.selectOutput(7, "bridge", 5).isFailure)
        assertEquals(1, transport.requests.size)
    }
    @Test fun namedZoneReadsAndEditsNeverRetargetLegacyLocalApis() = runTest {
        val transport = Transport()
        val client = DesktopAudioClient(transport) { "core-a" }
        val id = "zone-11111111-1111-4111-8111-111111111111"
        val snapshot = client.snapshot(id).getOrThrow()
        assertEquals("Study song", snapshot.session?.track?.title)
        assertEquals(0, snapshot.zone.outputs.size)
        client.processing(21, buildJsonObject { put("eqPreamp", -9) }, snapshot.targetGeneration, id).getOrThrow()
        client.control(snapshot, "play").getOrThrow()
        assertEquals(listOf("zones.list", "zones.dsp", "zones.signalPath", "zones.dsp.edit", "zones.transport"), transport.requests.map { it.name })
        assertTrue(transport.requests.drop(1).all { it.payload["zoneId"]?.jsonPrimitive?.content == id })
        client.interrupt(snapshot, "stop").getOrThrow()
        assertEquals(6, transport.requests.size)
        assertEquals("zones.transport", transport.requests.last().name)
        assertEquals(JsonNull, transport.requests.last().payload["revision"])
        client.control(snapshot, "volume", 0.3).getOrThrow()
        assertEquals(0.3, transport.requests.last().payload["action"]?.jsonObject?.get("volume")?.jsonPrimitive?.double)
        client.control(snapshot, "seek", 19.0).getOrThrow()
        assertEquals(19.0, transport.requests.last().payload["action"]?.jsonObject?.get("position")?.jsonPrimitive?.double)
        assertTrue(client.control(snapshot, "volume", 1.1).isFailure)
        assertTrue(client.control(snapshot, "seek", Double.NaN).isFailure)
        assertEquals(8, transport.requests.size)
    }
    @Test fun handoffCarriesBothFrozenQueueFencesAndRequiresReplacementConsentWithoutReplay() = runTest {
        val transport = Transport()
        val client = DesktopAudioClient(transport) { "core-a" }
        val zones = client.sessions().getOrThrow().zones
        val command = DesktopSessionHandoff(zones[0], zones[1], transport.generation)
        assertTrue(client.handoff(command).isFailure)
        assertEquals(1, transport.requests.size)
        client.handoff(command.copy(replace = true)).getOrThrow()
        val request = transport.requests.last()
        assertEquals("zones.handoff", request.name)
        assertEquals("duplicate:second", request.payload["source"]?.jsonObject?.get("queue")?.jsonObject?.get("occurrenceId")?.jsonPrimitive?.content)
        assertEquals("study-q", request.payload["target"]?.jsonObject?.get("queue")?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals(false, request.payload["resume"]?.jsonPrimitive?.boolean)
        transport.failCommand = true
        assertTrue(client.handoff(command.copy(replace = true, resume = true)).isFailure)
        assertEquals(3, transport.requests.size)
        transport.route++
        assertTrue(client.handoff(command.copy(replace = true)).isFailure)
        assertEquals(3, transport.requests.size)
    }
    @Test fun changingComputerDuringZoneSnapshotDiscardsResultAndStopsFurtherRequests() = runTest {
        val transport = Transport()
        transport.received = { if (it.name == "zones.dsp") transport.route++ }
        val client = DesktopAudioClient(transport) { "core-a" }
        assertTrue(client.snapshot("zone-11111111-1111-4111-8111-111111111111").isFailure)
        assertEquals(listOf("zones.list", "zones.dsp"), transport.requests.map { it.name })
    }
}
