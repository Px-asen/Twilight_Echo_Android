package com.twilightecho.android.coreclient

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CorePhase3ProtocolTest {

    @Test
    fun `Phase 3 CoreClient dispatches playlists CRUD, library collections, and EQ DSP configuration`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val events = MutableSharedFlow<CoreEventMessage>(extraBufferCapacity = 16)

        val playlists = mutableListOf<CorePlaylistView>()
        val albums = listOf(
            CoreAlbumCollectionView(
                id = "alb-echoes-of-dusk",
                title = "Echoes of Dusk",
                artist = "Aether Ensemble",
                trackCount = 2,
                totalDurationSec = 440.0,
                trackIds = listOf("track-1", "track-2")
            )
        )
        val artists = listOf(
            CoreArtistCollectionView(
                id = "art-aether-ensemble",
                name = "Aether Ensemble",
                albumCount = 1,
                trackCount = 2,
                trackIds = listOf("track-1", "track-2")
            )
        )
        var eqConfig = CoreEqConfigView()
        var seq = 1L

        fun buildSnapshot(): CoreSnapshot = CoreSnapshot(
            epoch = "epoch-phase3",
            sequence = seq,
            playback = CorePlaybackView(
                state = "playing",
                outputPerfect = eqConfig.bitPerfect && !eqConfig.enabled,
                gaplessActive = eqConfig.gapless
            ),
            playlists = playlists.toList(),
            albums = albums,
            artists = artists,
            eqConfig = eqConfig,
            libraryTrackCount = 2
        )

        val fakeTransport = object : CoreTransport {
            override val stateEvents = events

            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                seq += 1L
                return when (envelope.name) {
                    "library.collections" -> CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = "epoch-phase3",
                        ok = true,
                        value = DefaultCoreClient.DefaultJson.encodeToJsonElement(
                            CoreCollectionsView(albums = albums, artists = artists)
                        )
                    )
                    "playlists.list" -> CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = "epoch-phase3",
                        ok = true,
                        value = DefaultCoreClient.DefaultJson.encodeToJsonElement(playlists.toList())
                    )
                    "playlists.create" -> {
                        val name = envelope.payload["name"]?.jsonPrimitive?.content.orEmpty()
                        val trackIds = envelope.payload["trackIds"]?.jsonArray?.map {
                            it.jsonPrimitive.content
                        }.orEmpty()
                        playlists.add(
                            CorePlaylistView(
                                id = "pl-${playlists.size + 1}",
                                name = name,
                                trackIds = trackIds,
                                trackCount = trackIds.size,
                                updatedAtMs = 1710000000000L
                            )
                        )
                        CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = "epoch-phase3",
                            ok = true,
                            value = buildJsonObject {
                                put("snapshot", DefaultCoreClient.DefaultJson.encodeToJsonElement(buildSnapshot()))
                            }
                        )
                    }
                    "playlists.addTracks" -> {
                        val plId = envelope.payload["playlistId"]?.jsonPrimitive?.content.orEmpty()
                        val incomingIds = envelope.payload["trackIds"]?.jsonArray?.map {
                            it.jsonPrimitive.content
                        }.orEmpty()
                        val idx = playlists.indexOfFirst { it.id == plId }
                        if (idx >= 0) {
                            val existing = playlists[idx]
                            val merged = (existing.trackIds + incomingIds).distinct()
                            playlists[idx] = existing.copy(trackIds = merged, trackCount = merged.size)
                        }
                        CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = "epoch-phase3",
                            ok = true,
                            value = buildJsonObject {
                                put("snapshot", DefaultCoreClient.DefaultJson.encodeToJsonElement(buildSnapshot()))
                            }
                        )
                    }
                    "playlists.rename" -> {
                        val plId = envelope.payload["playlistId"]?.jsonPrimitive?.content.orEmpty()
                        val newName = envelope.payload["name"]?.jsonPrimitive?.content.orEmpty()
                        val idx = playlists.indexOfFirst { it.id == plId }
                        if (idx >= 0) {
                            playlists[idx] = playlists[idx].copy(name = newName)
                        }
                        CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = "epoch-phase3",
                            ok = true,
                            value = buildJsonObject {
                                put("snapshot", DefaultCoreClient.DefaultJson.encodeToJsonElement(buildSnapshot()))
                            }
                        )
                    }
                    "playlists.removeTrack" -> {
                        val plId = envelope.payload["playlistId"]?.jsonPrimitive?.content.orEmpty()
                        val trackId = envelope.payload["trackId"]?.jsonPrimitive?.content.orEmpty()
                        val idx = playlists.indexOfFirst { it.id == plId }
                        if (idx >= 0) {
                            val filtered = playlists[idx].trackIds.filterNot { it == trackId }
                            playlists[idx] = playlists[idx].copy(trackIds = filtered, trackCount = filtered.size)
                        }
                        CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = "epoch-phase3",
                            ok = true,
                            value = buildJsonObject {
                                put("snapshot", DefaultCoreClient.DefaultJson.encodeToJsonElement(buildSnapshot()))
                            }
                        )
                    }
                    "playlists.delete" -> {
                        val plId = envelope.payload["playlistId"]?.jsonPrimitive?.content.orEmpty()
                        playlists.removeAll { it.id == plId }
                        CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = "epoch-phase3",
                            ok = true,
                            value = buildJsonObject {
                                put("snapshot", DefaultCoreClient.DefaultJson.encodeToJsonElement(buildSnapshot()))
                            }
                        )
                    }
                    "audio.setEq" -> {
                        val enabled = envelope.payload["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                        val preset = envelope.payload["preset"]?.jsonPrimitive?.content ?: "Flat"
                        val preampDb = envelope.payload["preampDb"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val bands = envelope.payload["bandsDb"]?.jsonArray?.mapNotNull {
                            it.jsonPrimitive.doubleOrNull
                        } ?: CoreEqConfigView.PRESETS[preset].orEmpty()
                        val bitPerfect = envelope.payload["bitPerfect"]?.jsonPrimitive?.booleanOrNull ?: true
                        val gapless = envelope.payload["gapless"]?.jsonPrimitive?.booleanOrNull ?: true
                        eqConfig = CoreEqConfigView(
                            enabled = enabled,
                            preset = preset,
                            preampDb = preampDb,
                            bandsDb = bands,
                            bitPerfect = bitPerfect,
                            gapless = gapless
                        )
                        CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = "epoch-phase3",
                            ok = true,
                            value = buildJsonObject {
                                put("snapshot", DefaultCoreClient.DefaultJson.encodeToJsonElement(buildSnapshot()))
                            }
                        )
                    }
                    "audio.getEq" -> CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = "epoch-phase3",
                        ok = true,
                        value = DefaultCoreClient.DefaultJson.encodeToJsonElement(eqConfig)
                    )
                    else -> CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = "epoch-phase3",
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

        // 1. Query Collections
        val collectionsRes = client.queryCollections()
        assertTrue(collectionsRes.isSuccess)
        assertEquals(1, collectionsRes.getOrThrow().albums.size)
        assertEquals("Echoes of Dusk", collectionsRes.getOrThrow().albums.first().title)
        assertEquals(1, collectionsRes.getOrThrow().artists.size)
        assertEquals("Aether Ensemble", collectionsRes.getOrThrow().artists.first().name)

        // 2. Custom Playlists CRUD
        val createRes = client.createPlaylist("Late Night Reference", listOf("track-1"))
        advanceUntilIdle()
        assertTrue(createRes.isSuccess)
        assertEquals(1, client.snapshot.value?.playlists?.size)
        assertEquals("Late Night Reference", client.snapshot.value?.playlists?.first()?.name)

        val addTracksRes = client.addTracksToPlaylist("pl-1", listOf("track-2"))
        assertTrue(addTracksRes.isSuccess)
        assertEquals(2, client.snapshot.value?.playlists?.first()?.trackCount)

        val renameRes = client.renamePlaylist("pl-1", "Hi-Res Favorites")
        assertTrue(renameRes.isSuccess)
        assertEquals("Hi-Res Favorites", client.snapshot.value?.playlists?.first()?.name)

        val removeTrackRes = client.removeTrackFromPlaylist("pl-1", "track-1")
        assertTrue(removeTrackRes.isSuccess)
        assertEquals(listOf("track-2"), client.snapshot.value?.playlists?.first()?.trackIds)

        val listRes = client.listPlaylists()
        assertTrue(listRes.isSuccess)
        assertEquals(1, listRes.getOrThrow().size)

        val deleteRes = client.deletePlaylist("pl-1")
        assertTrue(deleteRes.isSuccess)
        assertEquals(0, client.snapshot.value?.playlists?.size)

        // 3. Equalizer / DSP Configuration
        val vocalPresetBands = CoreEqConfigView.PRESETS.getValue("Vocal")
        val setEqRes = client.setEqConfig(
            CoreEqConfigView(
                enabled = true,
                preset = "Vocal",
                preampDb = -1.5,
                bandsDb = vocalPresetBands,
                bitPerfect = true,
                gapless = true
            )
        )
        assertTrue(setEqRes.isSuccess)
        assertEquals(true, client.snapshot.value?.eqConfig?.enabled)
        assertEquals("Vocal", client.snapshot.value?.eqConfig?.preset)
        // When EQ is enabled, outputPerfect becomes false because DSP modifies PCM samples
        assertFalse(client.snapshot.value?.playback?.outputPerfect ?: true)

        val getEqRes = client.getEqConfig()
        assertTrue(getEqRes.isSuccess)
        assertEquals("Vocal", getEqRes.getOrThrow().preset)
        assertEquals(4.0, getEqRes.getOrThrow().bandsDb[4], 0.001)
    }
}
