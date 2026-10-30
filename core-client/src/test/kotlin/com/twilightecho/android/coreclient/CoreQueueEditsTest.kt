package com.twilightecho.android.coreclient

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreQueueEditsTest {

    @Test
    fun `clear and remove build valid queue edit payloads`() {
        val clear = CoreQueueEdits.clear()
        assertEquals("clear", clear["kind"]?.jsonPrimitive?.content)
        assertEquals(setOf("kind"), clear.keys)

        val remove = CoreQueueEdits.remove(listOf("occ-1", "occ-2"))
        assertEquals("remove", remove["kind"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("occ-1", "occ-2"),
            remove["entryIds"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `insertTrackIds preserves mutually exclusive anchor fields`() {
        val after = CoreQueueEdits.insertTrackIds(listOf("track-1"), afterEntryId = "occ-7")
        assertEquals("insert", after["kind"]?.jsonPrimitive?.content)
        assertEquals("occ-7", after["afterEntryId"]?.jsonPrimitive?.content)
        assertFalse("beforeEntryId" in after)
        assertEquals(listOf("track-1"), after["trackIds"]?.jsonArray?.map { it.jsonPrimitive.content })

        val before = CoreQueueEdits.insertTrackIds(
            listOf("track-2"),
            afterEntryId = null,
            beforeEntryId = "occ-0"
        )
        assertEquals("occ-0", before["beforeEntryId"]?.jsonPrimitive?.content)
        assertFalse("afterEntryId" in before)
    }

    @Test
    fun `insertTracks matches music-core wire validator schema`() {
        val track = CoreTrackView(
            id = "media:42",
            title = "Twilight Song",
            artist = "Echo Artist",
            album = "Night Sky",
            albumArtist = "Echo Ensemble",
            duration = 245.5,
            source = "content://media/external/audio/media/42",
            format = "FLAC",
            sampleRate = 96000,
            bitDepth = 24,
            size = 48_000_000L,
            trackNumber = 3,
            discNumber = 1,
            releaseDate = "2026",
            genre = "Ambient",
            cueRange = CueRange(startSeconds = 12.0, endSeconds = 257.5)
        )
        val edit = CoreQueueEdits.insertTracks(listOf(track), afterEntryId = "occ-active")
        assertEquals("insert", edit["kind"]?.jsonPrimitive?.content)
        assertEquals("occ-active", edit["afterEntryId"]?.jsonPrimitive?.content)
        assertFalse("trackIds" in edit)

        val items = edit["items"] as JsonArray
        assertEquals(1, items.size)
        val item = items.single().jsonObject
        // music-core.js strictly requires keys(item, ["id", "snapshot"]) and snapshot.id == item.id.
        assertEquals(setOf("id", "snapshot"), item.keys)
        assertEquals("media:42", item["id"]?.jsonPrimitive?.content)

        val snapshot = item["snapshot"]!!.jsonObject
        assertEquals("media:42", snapshot["id"]?.jsonPrimitive?.content)
        assertEquals("content://media/external/audio/media/42", snapshot["filePath"]?.jsonPrimitive?.content)
        assertEquals("local", snapshot["source"]?.jsonPrimitive?.content)
        assertEquals("Twilight Song", snapshot["title"]?.jsonPrimitive?.content)
        assertEquals("Echo Artist", snapshot["artist"]?.jsonPrimitive?.content)
        assertEquals("Night Sky", snapshot["album"]?.jsonPrimitive?.content)
        assertEquals(245.5, snapshot["duration"]!!.jsonPrimitive.double, 0.001)
        assertEquals("FLAC", snapshot["format"]?.jsonPrimitive?.content)
        assertEquals(96000, snapshot["sampleRate"]?.jsonPrimitive?.int)
        assertEquals(24, snapshot["bitDepth"]?.jsonPrimitive?.int)
        assertEquals(48_000_000L, snapshot["size"]?.jsonPrimitive?.long)
        assertNotNull(snapshot["cueRange"])
    }

    @Test
    fun `QueueEdit command serializes id revision and edit on DefaultCoreClient`() = runTest {
        var captured: CoreRequestEnvelope? = null
        val fakeTransport = object : CoreTransport {
            override val stateEvents: Flow<CoreEventMessage> = emptyFlow()
            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                captured = envelope
                return CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = envelope.epoch,
                    ok = true
                )
            }
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
        }
        val client = DefaultCoreClient(
            identity = CoreClientIdentity.androidPrimaryClient(),
            transport = fakeTransport,
            scope = backgroundScope
        )
        val edit = CoreQueueEdits.remove(listOf("occ-9"))
        val result = client.executeCommand(CoreTypedCommand.QueueEdit(id = "default", revision = 12L, edit = edit))
        assertTrue(result.isSuccess)
        val req = captured!!
        assertEquals("command", req.kind)
        assertEquals("queue.edit", req.name)
        val payload: JsonObject = req.payload
        assertEquals("default", payload["id"]?.jsonPrimitive?.content)
        assertEquals(12L, payload["revision"]?.jsonPrimitive?.long)
        assertEquals("remove", payload["edit"]?.jsonObject?.get("kind")?.jsonPrimitive?.content)
    }
}
