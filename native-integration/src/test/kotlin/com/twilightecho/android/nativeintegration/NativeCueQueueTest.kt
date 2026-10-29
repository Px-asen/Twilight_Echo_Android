package com.twilightecho.android.nativeintegration

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CueRange
import com.twilightecho.android.coreclient.CueSource
import com.twilightecho.android.runtimehost.NativeFdResource
import com.twilightecho.android.runtimehost.ResourceAccessPort
import com.twilightecho.android.runtimehost.ResourceAccessResult
import com.twilightecho.android.runtimehost.ResourceId
import com.twilightecho.android.runtimehost.ResourceStat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class NativeCueQueueTest {
    private val second = CueRange(240.0, 450 + 37 / 75.0, 0.0, 240.0 - (3 * 60 + 58 + 50 / 75.0))
    private val tracks = listOf(
        CoreTrackView("local:cue:a", title = "One", source = CueSource.encode("content://doc/a.flac", CueRange(0.0, 240.0))),
        CoreTrackView("local:cue:b", queueEntryId = "entry-b", title = "Two", source = CueSource.encode("content://doc/a.flac", second)),
        CoreTrackView("mediastore-1", title = "Plain", source = "content://media/external/audio/media/1")
    )

    @Test
    fun `CUE songs reach the engine as their file plus the range`() {
        val queue = Json.parseToJsonElement(nativeQueueJson(tracks) { source, _ -> source }).jsonArray.map { it.jsonObject }
        assertEquals(listOf("content://doc/a.flac", "content://doc/a.flac", "content://media/external/audio/media/1"),
            queue.map { it["source"]!!.jsonPrimitive.content })
        assertEquals(listOf("occ-0", "entry-b", "occ-2"), queue.map { it["queueEntryId"]!!.jsonPrimitive.content })
        val range = queue[1]["cueRange"]!!.jsonObject
        assertEquals(second.startSeconds, range["startSeconds"]!!.jsonPrimitive.double, 0.0)
        assertEquals(second.endSeconds, range["endSeconds"]!!.jsonPrimitive.double, 0.0)
        assertEquals(second.sourcePregapSeconds, range["sourcePregapSeconds"]!!.jsonPrimitive.double, 0.0)
        assertEquals(second.sourcePregapSeconds, range["pregapSeconds"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.0, range["virtualPregapSeconds"]!!.jsonPrimitive.double, 0.0)
        // The engine plays on without a gap only when one song's end is exactly the next one's start.
        assertEquals(queue[0]["cueRange"]!!.jsonObject["endSeconds"]!!.jsonPrimitive.content, range["startSeconds"]!!.jsonPrimitive.content)
        assertNull(queue[2]["cueRange"])
    }

    @Test
    fun `shared cueRange reaches the native queue without changing its source`() {
        val track = tracks[1].copy(source = "content://doc/a.flac", cueRange = second)
        val row = Json.parseToJsonElement(nativeQueueJson(listOf(track)) { source, _ -> source }).jsonArray.single().jsonObject
        assertEquals(track.source, row["source"]!!.jsonPrimitive.content)
        assertEquals(second.startSeconds, row["cueRange"]!!.jsonObject["startSeconds"]!!.jsonPrimitive.double, 0.0)
        assertEquals(second.sourcePregapSeconds, row["cueRange"]!!.jsonObject["sourcePregapSeconds"]!!.jsonPrimitive.double, 0.0)
    }

    @Test fun `invalid or conflicting segment metadata never plays the whole file`() {
        val source = tracks[1]
        assertThrows(IllegalArgumentException::class.java) {
            nativeQueueJson(listOf(source.copy(cueRange = CueRange(20.0, 10.0)))) { audio, _ -> audio }
        }
        assertThrows(IllegalArgumentException::class.java) {
            nativeQueueJson(listOf(source.copy(cueRange = CueRange(20.0, 40.0)))) { audio, _ -> audio }
        }
    }

    @Test
    fun `a CUE song opens its file without the range`() = runBlocking {
        val acquired = mutableListOf<String>()
        val access = object : ResourceAccessPort {
            override suspend fun stat(resourceId: ResourceId) = ResourceAccessResult.Ok(ResourceStat(resourceId = resourceId, uri = resourceId))
            override suspend fun openReadStream(resourceId: ResourceId, offsetBytes: Long): ResourceAccessResult<InputStream> =
                ResourceAccessResult.Ok(ByteArrayInputStream(ByteArray(0)))
            override suspend fun resolveNativeUri(resourceId: ResourceId) = ResourceAccessResult.Ok(resourceId)
            override suspend fun acquirePlayableResource(resourceId: ResourceId): ResourceAccessResult<NativeFdResource> {
                acquired += resourceId
                return ResourceAccessResult.Ok(NativeFdResource(resourceId = resourceId, nativePath = "/proc/self/fd/7", detachedFd = 7,
                    mimeType = "audio/flac"))
            }
            override suspend fun releasePlayableResource(resourceId: ResourceId) = Unit
        }
        val adapter = NativeAudioEngineAdapter(resourceAccess = access)
        adapter.loadQueue(tracks, startIndex = 1)
        adapter.play(0.0)
        adapter.selectQueueIndex(2)
        assertEquals(listOf("content://doc/a.flac", "content://media/external/audio/media/1"), acquired)
        adapter.close()
    }
}
