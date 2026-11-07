package com.twilightecho.android.coreclient

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DesktopDspLibraryTest {
    @Test fun actualProfilePreviewAndDownloadReceiptsDecodeAndImportRetainsPreviewRevision() = runTest {
        val json = DefaultCoreClient.DefaultJson
        val samples = json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/desktop-dsp-profile-wire.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        val preview = json.decodeFromJsonElement<DesktopProfilePreview>(samples.getValue("zones.dsp.profile.preview"))
        val download = json.decodeFromJsonElement<DesktopProfileDownload>(samples.getValue("zones.dsp.profile.export"))
        assertTrue(preview.scenes.size >= 2); assertTrue(preview.issues.isEmpty()); assertTrue(download.byteSize > 800000)
        val transport = Transport()
        transport.respond = { r -> CoreResponseEnvelope(requestId = r.requestId, epoch = "e1", ok = true,
            value = buildJsonObject { put("result", buildJsonObject { put("sceneIds", JsonArray(emptyList())) }) }) }
        val client = DesktopDspLibraryClient(transport) { "e1" }
        client.importProfile(client.scope(), "room", DesktopDspUpload("upload", 100), preview).getOrThrow()
        assertEquals(preview.revision, transport.requests.single().payload["revision"]!!.jsonPrimitive.long)
        assertEquals(preview.previewId, transport.requests.single().payload["previewId"]!!.jsonPrimitive.content)
    }
    @Test fun actualServerWireSamplesDecodeManagedAssetsAndSceneReferences() {
        val json = DefaultCoreClient.DefaultJson
        val sample = json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/desktop-dsp-library-wire.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        val library = json.decodeFromJsonElement<DesktopDspLibrary>(sample.getValue("zones.dsp.library"))
        assertEquals("Room impulse.wav", library.assets.items.single().fileName)
        assertEquals(1, library.assets.items.single().referenceCount)
        assertTrue(library.scenes.any { it.active && it.nodes.any { node -> node.assetId == library.assets.items.single().id } })
    }
    private class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        override val isRemote = true
        var route = 1L; override val generation get() = route
        val requests = mutableListOf<CoreRequestEnvelope>()
        var respond: (CoreRequestEnvelope) -> CoreResponseEnvelope = { request -> CoreResponseEnvelope(
            requestId = request.requestId, epoch = "e1", ok = true, value = buildJsonObject {
                put("result", buildJsonObject { put("uploadId", "u1"); put("chunkBytes", 786432) }) }) }
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope { requests += envelope; return respond(envelope) }
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
    }
    @Test fun uploadRetainsZoneAndReceiptAndNeverSendsHostPaths() = runTest {
        val t = Transport(); val client = DesktopDspLibraryClient(t) { "e1" }; val captured = client.scope()
        val upload = client.begin(captured, "zone-bedroom", "impulseResponse", "room.wav", 1024).getOrThrow()
        assertEquals("u1", upload.uploadId)
        assertEquals("zone-bedroom", t.requests.single().payload["zoneId"]!!.jsonPrimitive.content)
        assertNotNull(t.requests.single().commandId)
        assertFalse(t.requests.single().payload.containsKey("path"))
        t.respond = { r -> CoreResponseEnvelope(requestId = r.requestId, epoch = "e1", ok = true, value = buildJsonObject { put("result", JsonNull) }) }
        client.edit(captured, "zone-bedroom", 5, buildJsonObject { put("kind", "scan") }).getOrThrow()
        assertEquals(5L, t.requests.last().payload["revision"]!!.jsonPrimitive.long)
    }
    @Test fun changingComputerOrEpochRejectsOldOperationsAndLateReplies() = runTest {
        val t = Transport(); var epoch = "e1"; val client = DesktopDspLibraryClient(t) { epoch }; val captured = client.scope()
        t.route++
        assertTrue(client.begin(captured, "local", "vst3State", "state.bin", 1).isFailure)
        assertTrue(t.requests.isEmpty())
        t.route--; epoch = "e2"
        assertTrue(client.library(captured, "local").isFailure)
        epoch = "e1"
        t.respond = { r -> t.route++; CoreResponseEnvelope(requestId = r.requestId, epoch = "e1", ok = true, value = buildJsonObject { put("revision", 1) }) }
        assertTrue(client.library(captured, "local").isFailure)
        assertEquals(1, t.requests.size)
    }
}
