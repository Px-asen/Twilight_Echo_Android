package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopArtistAlbumsTest {
    private val json = DefaultCoreClient.DefaultJson
    private val calls = mutableListOf<CoreRequestEnvelope>()
    private val artist = CatalogEntity("artist-1", "artist", "北岸")
    private var revision = 9L
    private var fail = false
    private var uniqueAlbums = false
    private var busyOnce = false
    private var gate: CompletableDeferred<Unit>? = null
    private fun client() = DesktopLibraryClient(object : CoreTransport {
        override val stateEvents = MutableSharedFlow<CoreEventMessage>()
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            calls += envelope
            val payload = envelope.payload
            val value = when (envelope.name) {
                "metadata.entity.detail" -> json.encodeToJsonElement(EntityMetadataDetail(payload.getValue("entityId").jsonPrimitive.content,
                    revision, "北岸 $revision", MetadataIntroduction("电脑提供的完整简介")))
                "catalog.sources" -> {
                    val offset = payload.getValue("offset").jsonPrimitive.int
                    val id = payload.getValue("entityId").jsonPrimitive.content
                    val count = if (id == artist.id) 105 else 1
                    json.encodeToJsonElement(CatalogSourcePage(revision, count, offset,
                        (offset until minOf(offset + 100, count)).map { i -> CatalogSource("source-$i", "local", "recording-$i",
                            if (uniqueAlbums) "release-$i" else if (i == 104) "release-2" else "release-1", track = CatalogSourceTrack("歌曲 $i")) }))
                }
                "catalog.detail" -> {
                    gate?.let { withContext(NonCancellable) { it.await() } }
                    if (busyOnce) {
                        busyOnce = false
                        return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "desktop", ok = false,
                            error = CoreErrorView(CoreErrorCode.BUSY, "电脑请求较多，请稍后重试"))
                    }
                    if (fail) return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "desktop", ok = false,
                        error = CoreErrorView(CoreErrorCode.FAILED, "专辑读取失败"))
                    val id = payload.getValue("id").jsonPrimitive.content
                    json.encodeToJsonElement(CatalogEntityDetail(revision, CatalogEntity(id, "release", "专辑 $id", "北岸")))
                }
                else -> json.encodeToJsonElement(CatalogPage(revision, 1, items = listOf(artist)))
            }
            return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "desktop", ok = true, value = value)
        }
    }) { "desktop" }

    @Test fun artistAlbumsReadAllSourcePagesAndResolveExactUniqueReleasesAtTheSameRevision() = runTest {
        val presenter = DesktopLibraryPresenter(backgroundScope, client())
        presenter.open(artist); runCurrent()
        assertTrue(presenter.state.value.albums.isEmpty())
        presenter.artistTab(true); runCurrent()
        assertEquals(listOf("release-1", "release-2"), presenter.state.value.albums.map { it.id })
        assertTrue(presenter.state.value.albumsLoaded)
        val lookups = calls.filter { it.name == "catalog.detail" }
        assertEquals(2, lookups.size)
        assertTrue(lookups.all { it.payload["revision"] == JsonPrimitive(9) })
        assertTrue(calls.any { it.name == "catalog.sources" && it.payload["offset"] == JsonPrimitive(100) && it.payload["revision"] == JsonPrimitive(9) })
        val reads = calls.size
        presenter.artistTab(false); presenter.artistTab(true); runCurrent()
        assertEquals(reads, calls.size)
        val album = presenter.state.value.albums.first()
        presenter.open(album); runCurrent(); presenter.closeDetail()
        assertEquals(artist, presenter.state.value.selected)
        assertTrue(presenter.state.value.artistAlbums)
        assertEquals(2, presenter.state.value.albums.size)
        assertEquals("电脑提供的完整简介", presenter.state.value.metadata!!.introduction!!.value)
    }

    @Test fun failureIsRetryableWithoutHidingArtistSongsAndLateAlbumsCannotReopenAClosedPage() = runTest {
        val presenter = DesktopLibraryPresenter(backgroundScope, client())
        presenter.open(artist); runCurrent()
        fail = true; presenter.artistTab(true); runCurrent()
        assertEquals("专辑读取失败", presenter.state.value.albumsError)
        assertFalse(presenter.state.value.albumsLoaded)
        assertEquals(100, presenter.state.value.sources!!.items.size)
        fail = false; presenter.loadArtistAlbums(); runCurrent()
        assertEquals(2, presenter.state.value.albums.size)
        gate = CompletableDeferred()
        presenter.loadArtistAlbums(); runCurrent()
        presenter.closeDetail(); gate!!.complete(Unit); runCurrent()
        assertNull(presenter.state.value.selected)
        assertFalse(presenter.state.value.albumsLoading)
    }

    @Test fun revisionsRefreshOpenAlbumAndItsArtistWhenReturning() = runTest {
        val presenter = DesktopLibraryPresenter(backgroundScope, client())
        presenter.observe(9, 9); presenter.open(artist); runCurrent()
        presenter.artistTab(true); runCurrent()
        presenter.open(presenter.state.value.albums.first()); runCurrent()
        revision = 10; presenter.observe(10, 10); advanceTimeBy(181); runCurrent()
        presenter.closeDetail(); runCurrent()
        assertEquals("北岸 10", presenter.state.value.metadata!!.name)
        assertEquals(10L, presenter.state.value.sources!!.revision)
        assertTrue(presenter.state.value.artistAlbums)
        assertTrue(presenter.state.value.albumsLoaded)
    }

    @Test fun largeDiscographiesArePacedAndPublishAlbumsBeforeTheWholeListFinishes() = runTest {
        uniqueAlbums = true
        val presenter = DesktopLibraryPresenter(backgroundScope, client())
        presenter.open(artist); runCurrent()
        presenter.artistTab(true); runCurrent()
        assertEquals(2, presenter.state.value.albums.size)
        assertTrue(presenter.state.value.albumsLoading)
        advanceTimeBy(1_250); runCurrent()
        assertEquals(3, presenter.state.value.albums.size)
        advanceTimeBy(130_000); runCurrent()
        assertEquals(105, presenter.state.value.albums.size)
        assertTrue(presenter.state.value.albumsLoaded)
    }

    @Test fun aBusyDesktopWaitsForTheRateWindowBeforeRetryingTheSameRead() = runTest {
        val presenter = DesktopLibraryPresenter(backgroundScope, client())
        presenter.open(artist); runCurrent()
        busyOnce = true; presenter.artistTab(true); runCurrent()
        assertTrue(presenter.state.value.albumsLoading)
        assertNull(presenter.state.value.albumsError)
        advanceTimeBy(59_999); runCurrent()
        assertEquals(1, calls.count { it.name == "catalog.detail" })
        advanceTimeBy(1); runCurrent()
        assertTrue(presenter.state.value.albumsLoaded)
        assertEquals(listOf("release-1", "release-2"), presenter.state.value.albums.map { it.id })
    }
}
