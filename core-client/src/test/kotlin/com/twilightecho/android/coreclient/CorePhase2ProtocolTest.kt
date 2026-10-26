package com.twilightecho.android.coreclient

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CorePhase2ProtocolTest {

    @Test
    fun `Phase 2 CoreClient methods dispatch syncTracks, searchTracks, toggleFavorite, and bookmarks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val events = MutableSharedFlow<CoreEventMessage>(extraBufferCapacity = 8)

        val syncedTracks = mutableListOf<CoreTrackView>()
        val favoriteIds = linkedSetOf<String>()
        val bookmarks = mutableListOf<CoreBookmarkView>()
        var seq = 1L

        fun buildSnapshot(): CoreSnapshot = CoreSnapshot(
            epoch = "epoch-phase2",
            sequence = seq,
            favoriteTrackIds = favoriteIds.toList(),
            bookmarks = bookmarks.toList(),
            currentLyrics = CoreLyricsView(
                trackId = "mediastore-101",
                lines = listOf(CoreLyricLine(timeMs = 1500L, text = "Into the twilight echo"))
            ),
            libraryTrackCount = syncedTracks.size
        )

        val fakeTransport = object : CoreTransport {
            override val stateEvents = events

            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                seq += 1L
                return when (envelope.name) {
                    "library.syncTracks" -> {
                        val incoming = envelope.payload["tracks"]?.jsonArray?.map {
                            DefaultCoreClient.DefaultJson.decodeFromJsonElement(CoreTrackView.serializer(), it)
                        }.orEmpty()
                        syncedTracks.clear()
                        syncedTracks.addAll(incoming)
                        CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = "epoch-phase2",
                            ok = true,
                            value = buildJsonObject {
                                put("snapshot", DefaultCoreClient.DefaultJson.encodeToJsonElement(buildSnapshot()))
                            }
                        )
                    }
                    "search.songs" -> {
                        val q = envelope.payload["query"]?.jsonPrimitive?.content.orEmpty()
                        val filtered = syncedTracks.filter {
                            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
                        }
                        val page = CoreLibrarySearchPage(
                            query = q,
                            total = filtered.size,
                            items = filtered
                        )
                        CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = "epoch-phase2",
                            ok = true,
                            value = DefaultCoreClient.DefaultJson.encodeToJsonElement(page)
                        )
                    }
                    "playlists.toggleFavorite" -> {
                        val id = envelope.payload["trackId"]?.jsonPrimitive?.content.orEmpty()
                        if (!favoriteIds.add(id)) {
                            favoriteIds.remove(id)
                        }
                        CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = "epoch-phase2",
                            ok = true,
                            value = buildJsonObject {
                                put("snapshot", DefaultCoreClient.DefaultJson.encodeToJsonElement(buildSnapshot()))
                            }
                        )
                    }
                    "bookmarks.add" -> {
                        val trackId = envelope.payload["trackId"]?.jsonPrimitive?.content.orEmpty()
                        val posSec = envelope.payload["positionSec"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                        val label = envelope.payload["label"]?.jsonPrimitive?.content.orEmpty()
                        bookmarks.add(
                            CoreBookmarkView(
                                id = "bm-${bookmarks.size + 1}",
                                trackId = trackId,
                                positionSec = posSec,
                                label = label,
                                createdAtMs = 1700000000000L
                            )
                        )
                        CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = "epoch-phase2",
                            ok = true,
                            value = buildJsonObject {
                                put("snapshot", DefaultCoreClient.DefaultJson.encodeToJsonElement(buildSnapshot()))
                            }
                        )
                    }
                    "bookmarks.remove" -> {
                        val bmId = envelope.payload["bookmarkId"]?.jsonPrimitive?.content.orEmpty()
                        bookmarks.removeAll { it.id == bmId }
                        CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = "epoch-phase2",
                            ok = true,
                            value = buildJsonObject {
                                put("snapshot", DefaultCoreClient.DefaultJson.encodeToJsonElement(buildSnapshot()))
                            }
                        )
                    }
                    else -> CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = "epoch-phase2",
                        ok = true,
                        value = buildJsonObject { put("ok", JsonPrimitive(true)) }
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

        val tracks = listOf(
            CoreTrackView(
                id = "mediastore-101",
                title = "Starlight Sonata",
                artist = "Celeste",
                album = "Nightfall",
                duration = 210.0,
                format = "FLAC"
            ),
            CoreTrackView(
                id = "mediastore-102",
                title = "Aurora Pulse",
                artist = "Vanguard",
                album = "Horizon",
                duration = 185.0,
                format = "MP3"
            )
        )

        val syncRes = client.syncLocalTracks(tracks)
        advanceUntilIdle()
        assertTrue(syncRes.isSuccess)
        assertEquals(2, client.snapshot.value?.libraryTrackCount)

        val searchRes = client.searchTracks("Starlight")
        assertTrue(searchRes.isSuccess)
        assertEquals(1, searchRes.getOrThrow().total)
        assertEquals("mediastore-101", searchRes.getOrThrow().items.first().id)

        val favRes = client.toggleFavorite("mediastore-101")
        assertTrue(favRes.isSuccess)
        assertEquals(listOf("mediastore-101"), client.snapshot.value?.favoriteTrackIds)

        val addBmRes = client.addBookmark("mediastore-101", 42.5, "Chorus Drop")
        assertTrue(addBmRes.isSuccess)
        assertEquals(1, client.snapshot.value?.bookmarks?.size)
        assertEquals("Chorus Drop", client.snapshot.value?.bookmarks?.first()?.label)

        val remBmRes = client.removeBookmark("bm-1")
        assertTrue(remBmRes.isSuccess)
        assertEquals(0, client.snapshot.value?.bookmarks?.size)
    }
}
