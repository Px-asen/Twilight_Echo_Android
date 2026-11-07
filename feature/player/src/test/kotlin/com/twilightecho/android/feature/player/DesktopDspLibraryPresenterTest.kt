package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDspLibraryPresenterTest {
    private class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        override val isRemote = true
        var route = 1L; override val generation get() = route
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<CoreRequestEnvelope>()
        var custom: ((CoreRequestEnvelope) -> JsonElement?)? = null
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            requests += envelope
            if (envelope.kind == "command") { gate?.await(); if (fail) error("reply lost") }
            val offset = envelope.payload["assetOffset"]?.jsonPrimitive?.int ?: 0
            val value = custom?.invoke(envelope) ?: if (envelope.kind == "query") DefaultCoreClient.DefaultJson.encodeToJsonElement(DesktopDspLibrary(5,
                assets = DesktopDspAssets(105, offset, (offset until minOf(offset + 100, 105)).map {
                    DesktopDspAsset("asset-$it", "impulseResponse", "Room $it", "room.wav", 100)
                }))) else buildJsonObject { put("result", JsonNull) }
            return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "e1", ok = true, value = value)
        }
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
    }
    @Test fun writeCannotRetargetAndUnconfirmedMutationNeedsRefreshBeforeAnotherEdit() = runTest {
        val t = Transport(); val p = DesktopDspLibraryPresenter(backgroundScope, DesktopDspLibraryClient(t) { "e1" })
        p.open("zone-room", "房间"); runCurrent()
        t.gate = CompletableDeferred(); t.fail = true
        val edit = buildJsonObject { put("kind", "createScene"); put("name", "Room") }
        p.actions.edit(edit); runCurrent()
        assertTrue(p.state.value.writing)
        p.close(); p.open("local", "书房")
        assertEquals("zone-room", p.state.value.zoneId)
        t.gate!!.complete(Unit); runCurrent()
        assertTrue(p.state.value.needsRefresh)
        p.actions.edit(edit); runCurrent()
        assertEquals(1, t.requests.count { it.kind == "command" })
        t.fail = false; p.actions.refresh(); runCurrent()
        assertFalse(p.state.value.needsRefresh)
        p.actions.edit(edit); runCurrent()
        assertEquals(2, t.requests.count { it.kind == "command" })
    }
    @Test fun paginationKeepsRevisionAndStalePickerFileIsDisposedWithoutUploadingElsewhere() = runTest {
        val t = Transport(); val p = DesktopDspLibraryPresenter(backgroundScope, DesktopDspLibraryClient(t) { "e1" })
        p.open("zone-room", "房间"); runCurrent()
        val old = p.state.value.sessionId
        p.actions.moreAssets(); runCurrent()
        assertEquals(105, p.state.value.library!!.assets.items.size)
        assertEquals(5L, t.requests.last().payload["revision"]!!.jsonPrimitive.long)
        p.close(); p.open("local", "书房"); runCurrent()
        val file = File.createTempFile("old-picker", ".wav"); file.writeBytes(byteArrayOf(1))
        p.actions.upload(old, "impulseResponse", "room.wav", file); runCurrent()
        assertFalse(file.exists()); assertTrue(t.requests.none { it.kind == "command" })
        t.route++
        p.actions.edit(buildJsonObject { put("kind", "scan") }); runCurrent()
        assertTrue(t.requests.none { it.kind == "command" }); assertTrue(p.state.value.needsRefresh)
    }
    @Test fun profileUploadWaitsForConfirmationAndUnconfirmedImportCannotBeReplayed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val t = Transport(); val json = DefaultCoreClient.DefaultJson
            t.custom = { request -> when (request.name) {
                "zones.dsp.upload.begin" -> buildJsonObject { put("result", json.encodeToJsonElement(DesktopDspUpload("upload", 100))) }
                "zones.dsp.upload.chunk" -> buildJsonObject { put("result", buildJsonObject { put("offset", 3) }) }
                "zones.dsp.profile.preview" -> buildJsonObject { put("result", json.encodeToJsonElement(DesktopProfilePreview("preview", 5, "Room", listOf(DesktopProfileScene("Room", 1)), 1, 3))) }
                else -> null
            } }
            val p = DesktopDspLibraryPresenter(backgroundScope, DesktopDspLibraryClient(t) { "e1" })
            p.open("zone-room", "房间"); runCurrent()
            val file = File.createTempFile("profile", ".tedsp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            p.actions.upload(p.state.value.sessionId, "profile", "Room.tedsp", file)
            p.state.first { !it.writing }
            assertNotNull(p.state.value.preview); assertFalse(file.exists())
            assertTrue(t.requests.none { it.name == "zones.dsp.profile.import" })
            t.fail = true
            p.actions.importProfile(); runCurrent()
            assertNull(p.state.value.preview); assertTrue(p.state.value.needsRefresh)
            p.actions.importProfile(); runCurrent()
            assertEquals(1, t.requests.count { it.name == "zones.dsp.profile.import" })
        } finally { Dispatchers.resetMain() }
    }
    @Test fun parameterPagesRetainUnknownPresetValuesAndWritesUseExactNodeIdentity() = runTest {
        val t = Transport(); val json = DefaultCoreClient.DefaultJson
        t.custom = { request -> if (request.name == "zones.dsp.parameters") {
            val offset = request.payload["offset"]!!.jsonPrimitive.int
            json.encodeToJsonElement(DesktopParameterPage(5, "scene", "node", "Room EQ", 105, offset,
                (offset until minOf(offset + 100, 105)).map { DesktopVst3Parameter(it.toLong(), "P$it", defaultNormalizedValue = 0.5, flags = if (it == 1) 2 else 0) }))
        } else null }
        val p = DesktopDspLibraryPresenter(backgroundScope, DesktopDspLibraryClient(t) { "e1" })
        p.open("zone-room", "房间"); runCurrent()
        p.actions.parameters("scene", "node"); runCurrent()
        assertNull(p.state.value.parameters!!.items[0].value)
        p.actions.moreParameters(); runCurrent()
        assertEquals(105, p.state.value.parameters!!.items.size)
        p.actions.parameter(1, 0.25); runCurrent()
        assertTrue(t.requests.none { it.kind == "command" })
        p.actions.parameter(0, 0.25); runCurrent()
        val edit = t.requests.single { it.name == "zones.dsp.library.edit" }.payload["edit"]!!.jsonObject
        assertEquals("node", edit["nodeId"]!!.jsonPrimitive.content)
        assertEquals("scene", edit["sceneId"]!!.jsonPrimitive.content)
        assertEquals(0.25, edit["value"]!!.jsonPrimitive.double, 0.0)
    }
    @Test fun exportedBytesAreCompleteAndReleasedWhenTheSheetCloses() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val directory = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        try {
            val t = Transport(); val json = DefaultCoreClient.DefaultJson
            t.custom = { request -> when (request.name) {
                "zones.dsp.profile.export" -> buildJsonObject { put("result", json.encodeToJsonElement(DesktopProfileDownload("download", "Room.tedsp", 3, 100))) }
                "zones.dsp.profile.chunk" -> json.encodeToJsonElement(DesktopProfileChunk(0, 3, "AQID"))
                else -> null
            } }
            val p = DesktopDspLibraryPresenter(backgroundScope, DesktopDspLibraryClient(t) { "e1" })
            p.open("zone-room", "房间"); runCurrent()
            p.actions.exportProfile(directory); p.state.first { !it.writing }
            val file = requireNotNull(p.state.value.exportedFile)
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
            assertEquals(1, t.requests.count { it.name == "zones.dsp.profile.release" })
            p.close(); assertFalse(file.exists())
        } finally { Dispatchers.resetMain() }
    }
}
