package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineDesktopPlaybackUiTest {

    private val unusedClient = DesktopLibraryClient(
        object : CoreTransport {
            override val stateEvents = MutableSharedFlow<CoreEventMessage>()
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
            override suspend fun sendRequest(envelope: CoreRequestEnvelope) = error("Unused")
        }
    ) { "desktop" }

    @Test fun desktopStreamReferenceIdsCanLoadCachedArtworkAfterPresenterReset() = runTest {
        val streamId = DesktopMediaReference("desk-1", "lib-1", "src-42").resourceId()
        val cachedCover = "data:image/jpeg;base64,cachedStreamCover"
        val presenter = DesktopLibraryPresenter(backgroundScope, unusedClient) { ids ->
            Result.success(TrackArtworkPage(0, ids.map { TrackArtworkPreview(it, cachedCover) }))
        }
        presenter.reset()
        presenter.requestArtwork(streamId)
        advanceTimeBy(81)
        runCurrent()

        assertEquals(cachedCover, presenter.state.value.artwork[streamId])
    }

    @Test fun offlineCacheOmittingUncachedIdsKeepsLoadedCoversAndContinuesRemainingBatches() = runTest {
        val cachedIds = (0 until 12).filter { it % 2 == 0 }.map { "remote-track:$it" }.toSet()
        var online = true
        val presenter = DesktopLibraryPresenter(backgroundScope, unusedClient) { ids ->
            if (online) {
                Result.success(TrackArtworkPage(1, ids.map { TrackArtworkPreview(it, "data:image/jpeg;base64,$it") }))
            } else {
                // Offline cache returns only cached items without failing or overwriting uncached items with null.
                Result.success(
                    TrackArtworkPage(
                        0,
                        ids.filter { it in cachedIds }.map { TrackArtworkPreview(it, "data:image/jpeg;base64,cached-$it") }
                    )
                )
            }
        }

        // Load odd track cover while online.
        presenter.requestArtwork("remote-track:1")
        advanceTimeBy(81)
        runCurrent()
        assertEquals("data:image/jpeg;base64,remote-track:1", presenter.state.value.artwork["remote-track:1"])

        // Go offline and request 12 tracks (spans 2 batches of 8 + 4).
        online = false
        presenter.refreshArtwork()
        repeat(12) { presenter.requestArtwork("remote-track:$it") }
        advanceTimeBy(81)
        runCurrent()

        // Previously loaded odd track cover is preserved, and cached covers in BOTH batch 1 and batch 2 are loaded!
        assertEquals("data:image/jpeg;base64,remote-track:1", presenter.state.value.artwork["remote-track:1"])
        assertEquals("data:image/jpeg;base64,cached-remote-track:0", presenter.state.value.artwork["remote-track:0"])
        assertEquals("data:image/jpeg;base64,cached-remote-track:10", presenter.state.value.artwork["remote-track:10"])
    }

    @Test fun describeTrackOriginMarksOfflineDesktopTracksPlayingViaNetEase() {
        val offlineFallbackTrack = CoreTrackView(
            id = "remote-track:99",
            source = "provider://ncm/match?title=%E6%99%B4%E5%A4%A9",
            title = "晴天",
            artist = "周杰伦"
        )
        assertEquals("电脑曲库 · 书房电脑（网易云播放）", describeTrackOrigin(offlineFallbackTrack, "书房电脑"))
        assertEquals("电脑曲库（网易云播放）", describeTrackOrigin(offlineFallbackTrack, null))

        val directDesktopTrack = CoreTrackView(
            id = "remote-track:99",
            title = "晴天",
            artist = "周杰伦"
        )
        assertEquals("电脑曲库 · 书房电脑", describeTrackOrigin(directDesktopTrack, "书房电脑"))
    }
}
