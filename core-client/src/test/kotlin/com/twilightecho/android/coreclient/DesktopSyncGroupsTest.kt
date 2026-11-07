package com.twilightecho.android.coreclient

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DesktopSyncGroupsTest {
    private val zoneId = "zone-22222222-2222-4222-8222-222222222222"
    private val groupId = "group-11111111-1111-4111-8111-111111111111"
    private inner class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        override val isRemote = true
        var route = 3L
        override val generation get() = route
        val requests = mutableListOf<CoreRequestEnvelope>()
        var after: ((CoreRequestEnvelope) -> Unit)? = null
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            requests += envelope
            val body = when (envelope.name) {
                "zones.state" -> """{"revision":7,"selected":true,"connected":true,"groupId":"$groupId"}"""
                "zones.list" -> """{"revision":2,"zones":[{"id":"$zoneId","name":"Study","revision":9,"groupId":"$groupId","output":{"connected":true},"queue":{"id":"original","revision":0,"occurrenceId":"old"}}]}"""
                "audio.dsp", "zones.dsp" -> """{"revision":4,"processing":{"eqPreamp":-6}}"""
                "groups.list" -> """{"revision":2,"groups":[{"id":"$groupId","name":"Music","revision":12,"members":[{"zoneId":"local","downstreamDelayMs":0},{"zoneId":"$zoneId","downstreamDelayMs":3}],"track":{"title":"Common track"},"playback":{"state":"paused","position":23,"volume":0.6},"queue":{"id":"shared","revision":8,"occurrenceId":"duplicate:2"},"coordination":{"phase":"paused","estimatedSkewMs":null}}]}"""
                "groups.signalPath" -> """{"transport":{"kind":"tls-pcm"},"zoneId":"${envelope.payload["zoneId"]!!.jsonPrimitive.content}"}"""
                else -> "{}"
            }
            after?.invoke(envelope)
            return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "epoch", ok = true, value = DefaultCoreClient.DefaultJson.parseToJsonElement(body))
        }
    }

    @Test fun groupedMainAndMemberUseSharedRevisionButKeepPhysicalDspAndSignalPath() = runTest {
        val transport = Transport(); val client = DesktopAudioClient(transport) { "epoch" }
        for (zone in listOf("local", zoneId)) {
            val snapshot = client.snapshot(zone).getOrThrow()
            assertEquals(groupId, snapshot.group!!.id)
            assertEquals("duplicate:2", snapshot.group.queue!!.occurrenceId)
            assertEquals("Common track", snapshot.group.track!!.title)
            assertEquals(zone, snapshot.path["zoneId"]!!.jsonPrimitive.content)
            client.control(snapshot, "seek", 24.0).getOrThrow()
            val control = transport.requests.last()
            assertEquals("groups.transport", control.name)
            assertEquals(12, control.payload["revision"]!!.jsonPrimitive.int)
            assertEquals(groupId, control.payload["groupId"]!!.jsonPrimitive.content)
            assertFalse(control.payload.containsKey("zoneId"))
            val count = transport.requests.size
            client.interrupt(snapshot, "pause").getOrThrow()
            assertEquals(count + 1, transport.requests.size)
            assertEquals(JsonNull, transport.requests.last().payload["revision"])
            client.processing(4, buildJsonObject { put("eqPreamp", -9) }, snapshot.targetGeneration, zone).getOrThrow()
            assertEquals(if (zone == "local") "audio.dsp.edit" else "zones.dsp.edit", transport.requests.last().name)
        }
    }

    @Test fun creationCarriesTopologyMemberAndOccurrenceFencesWithoutOutputAuthorization() = runTest {
        val transport = Transport(); val client = DesktopAudioClient(transport) { "epoch" }
        val source = DesktopZoneSession("local", "Main", 3, queue = DesktopZoneQueue("queue", 8, "duplicate:2"))
        val other = DesktopZoneSession(zoneId, "Study", 4)
        val command = DesktopSyncGroupCreate(2, "Music", source,
            listOf(DesktopSyncSelection(source), DesktopSyncSelection(other, 3.5)), 3)
        client.createGroup(command).getOrThrow()
        val sent = transport.requests.single()
        assertEquals("groups.create", sent.name)
        assertEquals(2, sent.payload["revision"]!!.jsonPrimitive.int)
        assertEquals("duplicate:2", sent.payload["source"]!!.jsonObject["queue"]!!.jsonObject["occurrenceId"]!!.jsonPrimitive.content)
        val members = sent.payload["members"]!!.jsonArray
        assertEquals(4, members[1].jsonObject["revision"]!!.jsonPrimitive.int)
        assertEquals(3.5, members[1].jsonObject["downstreamDelayMs"]!!.jsonPrimitive.double, 0.0)
        assertFalse(sent.payload.toString().contains("token")); assertFalse(sent.payload.toString().contains("filePath"))
        assertTrue(client.createGroup(command.copy(members = listOf(DesktopSyncSelection(source), DesktopSyncSelection(other, 251.0)))).isFailure)
        assertEquals(1, transport.requests.size)
        transport.route++
        assertTrue(client.createGroup(command).isFailure); assertEquals(1, transport.requests.size)
    }

    @Test fun lateGroupReadsCannotCrossTargetsAndRemovalDoesNotResumeOldQueues() = runTest {
        val transport = Transport(); val client = DesktopAudioClient(transport) { "epoch" }
        val group = client.groups().getOrThrow().groups.single()
        client.removeGroup(group, 3).getOrThrow()
        assertEquals("groups.remove", transport.requests.last().name)
        assertEquals(12, transport.requests.last().payload["revision"]!!.jsonPrimitive.int)
        assertTrue(transport.requests.none { it.name == "zones.transport" || it.name == "groups.transport" })
        transport.after = { if (it.name == "groups.list") transport.route++ }
        assertTrue(client.snapshot().isFailure)
    }

    @Test fun groupModeDefaultsOffAndEditsFenceTheObservedRevisionAndTargetWithoutPlayback() = runTest {
        val transport = Transport(); val client = DesktopAudioClient(transport) { "epoch" }
        val legacy = client.groups().getOrThrow().groups.single()
        assertEquals("off", legacy.autoMixMode); assertFalse(legacy.autoMixAvailable)
        val group = legacy.copy(autoMixAvailable = true)
        transport.requests.clear()
        for (mode in listOf("off", "natural", "dj")) {
            client.groupAutoMix(group, mode, 3).getOrThrow()
            val sent = transport.requests.last()
            assertEquals("groups.settings.edit", sent.name)
            assertEquals(buildJsonObject { put("groupId", groupId); put("revision", 12); put("autoMixMode", mode) }, sent.payload)
        }
        assertEquals(3, transport.requests.size)
        assertTrue(client.groupAutoMix(legacy, "natural", 3).isFailure)
        assertTrue(client.groupAutoMix(group, "auto", 3).isFailure)
        transport.route++
        assertTrue(client.groupAutoMix(group, "natural", 3).isFailure)
        assertEquals(3, transport.requests.size)
        transport.after = { transport.route++ }
        assertTrue(client.groupAutoMix(group, "natural", 4).isFailure)
        assertEquals(4, transport.requests.size) // Unknown outcomes are never replayed.
    }
}
