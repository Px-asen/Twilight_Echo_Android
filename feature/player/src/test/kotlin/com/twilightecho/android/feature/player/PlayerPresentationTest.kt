package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerPresentationTest {
    private val tracks = listOf(
        CoreTrackView("a", title = "A", artist = "甲", format = "FLAC"),
        CoreTrackView("b", title = "B", artist = "乙", format = "MP3", bitDepth = 24),
        CoreTrackView("c", title = "C", artist = "丙", format = "WAV")
    )
    private fun page(query: String, items: List<CoreTrackView>) =
        Result.success(CoreLibrarySearchPage(query, items.size, items))

    @Test fun pendingButtonFeedbackDoesNotAdvanceThePlaybackClock() {
        val paused = PlayerUiState(isPlaying = false, requestedPlaying = true)
        assertTrue(paused.playbackButtonPlaying)
        assertFalse(paused.isPlaying)
        val playing = paused.copy(isPlaying = true, requestedPlaying = false)
        assertFalse(playing.playbackButtonPlaying)
        assertTrue(playing.isPlaying)
        assertTrue(playing.copy(requestedPlaying = null).playbackButtonPlaying)
    }

    @Test fun unchangedQueueWindowKeepsItsRowInputs() {
        val previous = listOf(CoreQueueEntryView("occ-0", 0, tracks.first()))
        assertSame(previous, mergeQueueEntries(previous, previous.toList()))
        assertSame(previous, mergeQueueEntries(previous, emptyList()))
    }

    @Test fun searchDoesNotReplaceLibraryOrItsCount() = runTest {
        val loader = LibraryContentLoader(backgroundScope) { query ->
            page(query, if (query.isBlank()) tracks else tracks.take(1))
        }
        loader.refresh(); runCurrent()
        loader.query("A", debounce = false); runCurrent()
        assertEquals(tracks, loader.state.value.tracks)
        assertEquals(3, loader.state.value.total)
        assertEquals(tracks.take(1), loader.state.value.results)
        loader.query("")
        assertEquals(tracks, loader.state.value.results)
    }

    @Test fun lateNonCancellableSearchCannotOverwriteNewQuery() = runTest {
        val slow = CompletableDeferred<Result<CoreLibrarySearchPage>>()
        val fast = CompletableDeferred<Result<CoreLibrarySearchPage>>()
        val loader = LibraryContentLoader(backgroundScope) { query ->
            withContext(NonCancellable) { if (query == "old") slow.await() else fast.await() }
        }
        loader.query("old", false); runCurrent()
        loader.query("new", false); runCurrent()
        fast.complete(page("new", tracks.takeLast(1))); runCurrent()
        slow.complete(page("old", tracks.take(1))); runCurrent()
        assertEquals("new", loader.state.value.query)
        assertEquals(tracks.takeLast(1), loader.state.value.results)
    }

    @Test fun switchingCoreDiscardsOldLibraryResponse() = runTest {
        val old = CompletableDeferred<Result<CoreLibrarySearchPage>>()
        var count = 0
        val loader = LibraryContentLoader(backgroundScope) {
            if (++count == 1) withContext(NonCancellable) { old.await() } else page("", tracks.takeLast(1))
        }
        loader.refresh(); runCurrent()
        loader.refresh(resetSource = true); runCurrent()
        old.complete(page("", tracks)); runCurrent()
        assertEquals(tracks.takeLast(1), loader.state.value.tracks)
        assertEquals(1, loader.state.value.total)
    }

    @Test fun searchFailureCanBeRetriedWithoutLosingLibrary() = runTest {
        var fail = true
        val loader = LibraryContentLoader(backgroundScope) {
            if (it.isEmpty()) page("", tracks)
            else if (fail) Result.failure(IllegalStateException("offline")) else page(it, tracks.take(1))
        }
        loader.refresh(); runCurrent()
        loader.query("A", false); runCurrent()
        assertEquals("offline", loader.state.value.searchError)
        assertEquals(tracks, loader.state.value.tracks)
        fail = false
        loader.query("A", false); runCurrent()
        assertNull(loader.state.value.searchError)
        assertEquals(tracks.take(1), loader.state.value.results)
    }

    @Test fun remotePagesReplaceItemsWhileKeepingTotalAndSearchOffset() = runTest {
        val loader = LibraryContentLoader(backgroundScope, pageSearch = { query, offset ->
            Result.success(CoreLibrarySearchPage(query, 125, tracks.take(1), offset = offset, revision = 4))
        }, search = { error("Paged remote reads must not fall back to the local search contract") })
        loader.refresh(); runCurrent()
        loader.refresh(offset = 100); runCurrent()
        assertEquals(125, loader.state.value.total)
        assertEquals(100, loader.state.value.offset)
        assertEquals(tracks.take(1), loader.state.value.tracks)
        loader.query("song", false, offset = 100); runCurrent()
        assertEquals(125, loader.state.value.searchTotal)
        assertEquals(100, loader.state.value.searchOffset)
        assertEquals(100, loader.state.value.offset)
        loader.query("")
        assertEquals(100, loader.state.value.searchOffset)
        assertEquals(125, loader.state.value.searchTotal)
    }

    @Test fun collectionPreservesCoreTrackOrder() {
        assertEquals(listOf("c", "a", "b"), collectionTracks(listOf("c", "a", "b"), tracks).map { it.id })
        assertTrue(collectionTracks(listOf("missing"), tracks).isEmpty())
    }

    @Test fun queueWindowsKeepRepeatedTracksByOccurrenceAndCanPassFifty() {
        fun window(start: Int, end: Int) = (start until end).map {
            CoreQueueEntryView("occ-$it", it, tracks.first())
        }
        val merged = mergeQueueEntries(window(0, 50), window(40, 80))
        assertEquals(80, merged.size)
        assertEquals(80, merged.map { it.occurrenceId }.toSet().size)
        assertEquals(80, nextQueueOffset(merged, 125))
        assertNull(nextQueueOffset(mergeQueueEntries(merged, window(80, 125)), 125))
        assertEquals(0, nextQueueOffset(window(50, 100), 125))
    }

    @Test fun filtersUseFormatNotGuessedBitDepth() {
        val state = PlayerUiState(searchResults = tracks, activeSearchFilter = SearchFilterCategory.LOSSLESS)
        assertEquals(listOf("a", "c"), filteredSearchTracks(state).map { it.id })
        assertEquals(listOf("b"), filteredSearchTracks(state.copy(activeSearchFilter = SearchFilterCategory.FAVORITES,
            favoriteTrackIds = listOf("b"))).map { it.id })
    }

    @Test fun diagnosticsNeverInventOutputOrFlac() {
        val empty = diagnosticsFor(CoreSnapshot(epoch = "test", sequence = 1))
        assertEquals("", empty.codec)
        assertEquals(0, empty.actualSampleRateHz)
        assertFalse(empty.outputPerfect)
        val sourceOnly = diagnosticsFor(CoreSnapshot(epoch = "test", sequence = 2,
            track = tracks.first().copy(sampleRate = 44100, bitDepth = 16)))
        assertEquals(44100, sourceOnly.sourceSampleRateHz)
        assertEquals(0, sourceOnly.actualSampleRateHz)
        assertFalse(sourceOnly.outputPerfect)
    }

    @Test fun diagnosticsSeparateStreamFromHardwareAndUseReadableFormats() {
        val result = diagnosticsFor(CoreSnapshot(epoch = "test", sequence = 1,
            playback = CorePlaybackView(actualSampleRate = 44100, actualBitDepth = 32,
                actualSampleFormat = "float32", hardwareSampleRate = 48000, hardwareBitDepth = 16,
                hardwareSampleFormat = "int16", requestedSampleRate = 96000, exclusive = true)))
        assertEquals(44100, result.actualSampleRateHz)
        assertEquals(48000, result.hardwareSampleRateHz)
        assertEquals(96000, result.requestedSampleRateHz)
        assertFalse(result.outputPerfect)
        assertEquals("44.1 kHz / 32 位浮点", audioResolution(44100, 32, "float32"))
        assertEquals("48 kHz / 16 位", audioResolution(48000, 16, "int16"))
        assertEquals("", audioResolution(0, 0))
    }

    @Test fun bluetoothLinksAreNamedWithoutACodec() {
        assertEquals(listOf("A2DP", "LE Audio", "通话线路"), listOf("a2dp", "le_audio", "call").map(::bluetoothProfileLabel))
        assertNull(bluetoothProfileLabel(""))
        assertNull(bluetoothProfileLabel("usb"))
    }

    @Test fun sourceQualityRestoresCodecDepthAndFractionalKhz() {
        val track = tracks.first().copy(sampleRate = 96000, bitDepth = 24)
        val state = PlayerUiState(currentTrack = track)
        assertEquals("FLAC 24/96", sourceQualityLabel(state))
        assertEquals("FLAC 16/44.1", sourceQualityLabel(state.copy(currentTrack = track.copy(sampleRate = 44100, bitDepth = 16))))
        assertEquals("FLAC 24/88.2", sourceQualityLabel(state.copy(currentTrack = track.copy(sampleRate = 88200))))
    }

    @Test fun sourceQualityUsesDecoderFactsNotOutputFormatOrStaleCatalog() {
        val state = PlayerUiState(currentTrack = tracks.first().copy(sampleRate = 96000, bitDepth = 24),
            diagnostics = AudioDiagnosticsUiModel(codec = "aac", sourceSampleRateHz = 44100, sourceBitDepth = 16,
                actualSampleRateHz = 48000, actualBitDepth = 32))
        assertEquals("AAC 16/44.1", sourceQualityLabel(state))
    }

    @Test fun sourceQualityOmitsUnknownFactsAndKeepsKnownOnes() {
        assertNull(sourceQualityLabel(PlayerUiState()))
        val state = PlayerUiState(currentTrack = tracks.first().copy(format = null))
        assertNull(sourceQualityLabel(state))
        assertEquals("FLAC", sourceQualityLabel(state.copy(currentTrack = tracks.first())))
        assertEquals("44.1 kHz", sourceQualityLabel(state.copy(currentTrack = state.currentTrack!!.copy(sampleRate = 44100))))
        assertEquals("24 bit", sourceQualityLabel(state.copy(currentTrack = state.currentTrack!!.copy(bitDepth = 24))))
        assertEquals("FLAC", sourceQualityLabel(state.copy(currentTrack = tracks.first(),
            diagnostics = AudioDiagnosticsUiModel(codec = "unknown"))))
    }
}
