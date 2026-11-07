package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryArtistPresenterTest {
    private val artist = CoreArtistCollectionView("artist:北岸", "北岸", 1, 2, listOf("1", "2"))
    @Test fun metadataSupplementsTheCollectionAndIsReusedUntilRefreshOrSourceChange() = runTest {
        var reads = 0
        val metadata = EntityMetadataDetail("catalog-artist", 9, "北岸", MetadataIntroduction("电脑简介"))
        val presenter = LibraryArtistPresenter(backgroundScope) { reads++; Result.success(metadata) }
        presenter.load(artist); runCurrent()
        assertEquals(artist.id, presenter.state.value.collectionId)
        assertEquals(metadata, presenter.state.value.metadata)
        presenter.load(artist); runCurrent(); assertEquals(1, reads)
        presenter.load(artist, force = true); runCurrent(); assertEquals(2, reads)
        presenter.reset(); assertNull(presenter.state.value.metadata)
        presenter.load(artist); runCurrent(); assertEquals(3, reads)
    }
    @Test fun missingComputerDataAndErrorsRemainSeparateAndLateResponsesCannotCrossArtists() = runTest {
        val gate = CompletableDeferred<Unit>()
        val presenter = LibraryArtistPresenter(backgroundScope) {
            if (it.id == artist.id) {
                withContext(NonCancellable) { gate.await() }
                Result.success(EntityMetadataDetail("old", 1, "旧歌手"))
            } else Result.failure(IllegalStateException("电脑未连接"))
        }
        presenter.load(artist); runCurrent()
        presenter.load(artist.copy(id = "artist:另一个")); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("artist:另一个", presenter.state.value.collectionId)
        assertEquals("电脑未连接", presenter.state.value.error)
        assertNull(presenter.state.value.metadata)
        assertFalse(presenter.state.value.loading)
    }

    @Test fun visiblePortraitAndOpenedDetailShareOneReadAndDoNotSelectOtherTiles() = runTest {
        var reads = 0
        val presenter = LibraryArtistPresenter(backgroundScope) { reads++; Result.success(EntityMetadataDetail(it.id, 1, it.name)) }
        presenter.request(artist); runCurrent()
        assertEquals("", presenter.state.value.collectionId)
        assertNotNull(presenter.profiles.value[artist.id]?.metadata)
        presenter.load(artist); runCurrent()
        presenter.request(artist.copy(id = "artist:南山", name = "南山")); runCurrent()
        assertEquals(artist.id, presenter.state.value.collectionId)
        assertEquals(2, reads)
        presenter.load(artist); runCurrent()
        assertEquals(2, reads)
    }

    @Test fun newerRefreshWinsOverAnUncancellableResponseForTheSameArtist() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val presenter = LibraryArtistPresenter(backgroundScope) {
            val revision = ++reads
            if (revision == 1) withContext(NonCancellable) { gate.await() }
            Result.success(EntityMetadataDetail("a", revision.toLong(), "北岸 $revision"))
        }
        presenter.load(artist); runCurrent()
        presenter.load(artist, force = true); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("北岸 2", presenter.state.value.metadata?.name)
        assertEquals("北岸 2", presenter.profiles.value[artist.id]?.metadata?.name)
    }

    @Test fun failedRefreshKeepsThePortraitButAnExplicitClearReplacesIt() = runTest {
        val original = EntityMetadataDetail("a", 1, "北岸", manualArtwork = PersonalArtworkPreview("portrait"))
        var result: Result<EntityMetadataDetail?> = Result.success(original)
        val presenter = LibraryArtistPresenter(backgroundScope) { result }
        presenter.load(artist); runCurrent()
        result = Result.failure(IllegalStateException("暂时断线"))
        presenter.load(artist, force = true); runCurrent()
        assertEquals(original, presenter.state.value.metadata)
        result = Result.success(original.copy(manualArtwork = null, artworkOverride = ArtworkOverride("clear")))
        presenter.load(artist, force = true); runCurrent()
        assertNull(presenter.state.value.metadata?.artworkPreview())
        assertNull(presenter.state.value.error)
    }
}
