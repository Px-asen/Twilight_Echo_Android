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
}
