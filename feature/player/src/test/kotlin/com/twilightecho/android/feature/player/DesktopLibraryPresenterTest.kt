package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLibraryPresenterTest {
    @Test fun metadataAndCatalogRevisionsRefreshOpenDetailAndKeepItsPage() = runTest {
        var revision = 9L
        val calls = mutableListOf<CoreRequestEnvelope>()
        val transport = object : CoreTransport {
            override val stateEvents = MutableSharedFlow<CoreEventMessage>()
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                calls += envelope
                val value = when (envelope.name) {
                    "catalog.query" -> DefaultCoreClient.DefaultJson.encodeToJsonElement(CatalogPage(revision, 1))
                    "metadata.entity.detail" -> DefaultCoreClient.DefaultJson.encodeToJsonElement(
                        EntityMetadataDetail("artist-1", revision, if (revision == 9L) "原名" else "更正的名字"))
                    else -> DefaultCoreClient.DefaultJson.encodeToJsonElement(CatalogSourcePage(revision, 200,
                        envelope.payload["offset"]!!.jsonPrimitive.int))
                }
                return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "desktop", ok = true, value = value)
            }
        }
        val presenter = DesktopLibraryPresenter(backgroundScope, DesktopLibraryClient(transport) { "desktop" })
        presenter.observe(5, 9)
        presenter.open(CatalogEntity("artist-1", "artist", "原名")); runCurrent()
        presenter.sourcePage(100); runCurrent()
        revision = 10
        presenter.observe(6, 10); advanceTimeBy(181); runCurrent()
        assertEquals("artist-1", presenter.state.value.selected!!.id)
        assertEquals("更正的名字", presenter.state.value.metadata!!.name)
        assertEquals(100, presenter.state.value.sources!!.offset)
        assertFalse(presenter.state.value.detailLoading)
        val reads = calls.size
        presenter.observe(6, 10); advanceTimeBy(200); runCurrent()
        assertEquals(reads, calls.size)
    }
    @Test fun lateCatalogPageCannotOverwriteNewKindOrSource() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val json = DefaultCoreClient.DefaultJson
        val transport = object : CoreTransport {
            override val stateEvents = MutableSharedFlow<CoreEventMessage>()
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                val request = ++reads
                if (request == 1) withContext(NonCancellable) { gate.await() }
                val kind = envelope.payload["kind"]!!.jsonPrimitive.content
                return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "desktop", ok = true,
                    value = json.encodeToJsonElement(CatalogPage(request.toLong(), 1, items = listOf(
                        CatalogEntity("entity-$request", kind, "Page $request")))))
            }
        }
        val presenter = DesktopLibraryPresenter(backgroundScope, DesktopLibraryClient(transport) { "desktop" })
        presenter.browse("artist"); runCurrent()
        presenter.browse("release"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("release", presenter.state.value.kind)
        assertEquals("entity-2", presenter.state.value.page!!.items.single().id)
        presenter.reset()
        assertNull(presenter.state.value.page)
        assertTrue(presenter.state.value.artwork.isEmpty())
    }

    @Test fun visibleArtworkRequestsAreDeduplicatedAndBatchedToEight() = runTest {
        val batches = mutableListOf<List<String>>()
        val json = DefaultCoreClient.DefaultJson
        val transport = object : CoreTransport {
            override val stateEvents = MutableSharedFlow<CoreEventMessage>()
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                val ids = envelope.payload["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
                batches += ids
                return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "desktop", ok = true,
                    value = json.encodeToJsonElement(TrackArtworkPage(1, ids.map { TrackArtworkPreview(it) })))
            }
        }
        val presenter = DesktopLibraryPresenter(backgroundScope, DesktopLibraryClient(transport) { "desktop" })
        repeat(10) { presenter.requestArtwork("remote-track:$it"); presenter.requestArtwork("remote-track:$it") }
        presenter.requestArtwork("content://phone/file")
        advanceTimeBy(81); runCurrent()
        assertEquals(listOf(8, 2), batches.map { it.size })
        assertEquals(10, presenter.state.value.artwork.size)
    }

    @Test fun aFailedBackgroundRefreshKeepsTheLoadedArtistAndSongs() = runTest {
        var fail = false
        val json = DefaultCoreClient.DefaultJson
        val transport = object : CoreTransport {
            override val stateEvents = MutableSharedFlow<CoreEventMessage>()
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                if (fail) return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "desktop", ok = false,
                    error = CoreErrorView(CoreErrorCode.BUSY, "电脑请求较多，请稍后重试"))
                val value = if (envelope.name == "metadata.entity.detail") json.encodeToJsonElement(
                    EntityMetadataDetail("artist", 1, "北岸", MetadataIntroduction("已载入的简介")))
                else json.encodeToJsonElement(CatalogSourcePage(1, 1, items = listOf(CatalogSource(
                    "source", "local", "recording", track = CatalogSourceTrack("已载入的歌曲")))))
                return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "desktop", ok = true, value = value)
            }
        }
        val presenter = DesktopLibraryPresenter(backgroundScope, DesktopLibraryClient(transport) { "desktop" })
        val artist = CatalogEntity("artist", "artist", "北岸")
        presenter.open(artist); runCurrent()
        val before = presenter.state.value
        fail = true; presenter.open(artist, refresh = true)
        assertEquals(before.metadata, presenter.state.value.metadata)
        assertEquals(before.sources, presenter.state.value.sources)
        runCurrent()
        assertEquals(before.metadata, presenter.state.value.metadata)
        assertEquals(before.sources, presenter.state.value.sources)
        assertNotNull(presenter.state.value.detailError)
        assertFalse(presenter.state.value.detailLoading)
    }

    @Test fun artworkRefreshKeepsTheCoverUntilAReplacementOrExplicitClearArrives() = runTest {
        var cover: String? = "data:image/jpeg;base64,existing"
        val transport = object : CoreTransport {
            override val stateEvents = MutableSharedFlow<CoreEventMessage>()
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
            override suspend fun sendRequest(envelope: CoreRequestEnvelope) = error("Unused")
        }
        val presenter = DesktopLibraryPresenter(backgroundScope, DesktopLibraryClient(transport) { "desktop" }) {
            Result.success(TrackArtworkPage(1, it.map { id -> TrackArtworkPreview(id, cover) }))
        }
        presenter.requestArtwork("remote-track:1"); advanceTimeBy(81); runCurrent()
        presenter.aliasArtwork("stream:1", "remote-track:1")
        presenter.refreshArtwork()
        assertEquals(cover, presenter.state.value.artwork["remote-track:1"])
        assertEquals(cover, presenter.state.value.artwork["stream:1"])
        cover = null
        presenter.requestArtwork("remote-track:1"); advanceTimeBy(81); runCurrent()
        assertTrue(presenter.state.value.artwork.containsKey("remote-track:1"))
        assertNull(presenter.state.value.artwork["remote-track:1"])
        assertNull(presenter.state.value.artwork["stream:1"])
    }
}
