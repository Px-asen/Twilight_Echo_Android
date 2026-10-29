package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.coreclient.CoreErrorView
import com.twilightecho.android.coreclient.CoreLyricLine
import com.twilightecho.android.coreclient.CoreLyricsView
import com.twilightecho.android.coreclient.CoreResult
import com.twilightecho.android.coreclient.CoreTrackView
import org.junit.Assert.*
import org.junit.Test

class PlayerProgressTest {
    private val track = CoreTrackView("song", title = "暮色里的旋律", duration = 180.0)
    private val state = PlayerUiState(currentTrack = track, libraryTracks = listOf(track),
        currentLyrics = CoreLyricsView(track.id, lines = listOf(CoreLyricLine(0L, "第一句"), CoreLyricLine(1000L, "第二句"))))

    @Test fun clockAndLineTicksDoNotInvalidatePageContent() {
        val tick = state.copy(sequence = 20L, anchorMonotonicMs = 1000L, anchorPositionSeconds = 1.0,
            displayedPositionSeconds = 1.25, activeLyricIndex = 1)
        assertEquals(state.withoutProgressTicks(), tick.withoutProgressTicks())
        assertSame(state.libraryTracks, tick.withoutProgressTicks().libraryTracks)
        assertSame(state.currentLyrics, tick.withoutProgressTicks().currentLyrics)
        assertEquals("The presenter still retains its real clock", 1.25, tick.displayedPositionSeconds, 0.0)
        assertEquals(1, tick.activeLyricIndex)
    }

    @Test fun playbackContentChangesStillInvalidateThePage() {
        val content = state.withoutProgressTicks()
        listOf(state.copy(currentTrack = track.copy(title = "新曲目")), state.copy(isPlaying = true),
            state.copy(queueRevision = 2L), state.copy(currentLyrics = state.currentLyrics!!.copy(synced = false)),
            state.copy(isLyricsMode = true), state.copy(isImmersivePlayerOpen = true),
            state.copy(preferences = state.preferences.copy(lyrics = state.preferences.lyrics.copy(blurDp = 0f))))
            .forEach { assertNotEquals(content, it.withoutProgressTicks()) }
    }

    @Test fun pendingSeekHoldBlocksPreSeekSnapBackAndReleasesOnceEngineCatchesUp() {
        val hold = PendingSeekHold(
            request = 1L,
            source = "0:epoch-1",
            trackId = "song",
            targetSeconds = 120.0,
            anchorMonotonicMs = 1_000L
        )
        // Stale pre-seek snapshot arrives 100ms later at 15.2s -> must NOT snap back to 15.2s!
        val duringFlight = evaluatePendingSeekHold(
            hold = hold,
            source = "0:epoch-1",
            trackId = "song",
            incomingPositionSeconds = 15.2,
            durationSeconds = 180.0,
            isPlaying = true,
            playbackRate = 1.0,
            monoNowMs = 1_100L
        )
        assertNotNull(duringFlight.hold)
        assertEquals(120.1, duringFlight.displayedPositionSeconds, 0.001)

        // Even right after Seek RPC returns (settled = true), one late engine tick at 15.3s is still ignored.
        val settledHold = hold.copy(settled = true, settledAtMonotonicMs = 1_200L)
        val lateTick = evaluatePendingSeekHold(
            hold = settledHold,
            source = "0:epoch-1",
            trackId = "song",
            incomingPositionSeconds = 15.3,
            durationSeconds = 180.0,
            isPlaying = true,
            playbackRate = 1.0,
            monoNowMs = 1_250L
        )
        assertNotNull(lateTick.hold)
        assertEquals(120.25, lateTick.displayedPositionSeconds, 0.001)

        // Engine catches up at 120.4s -> hold releases cleanly to authoritative engine position.
        val caughtUp = evaluatePendingSeekHold(
            hold = settledHold,
            source = "0:epoch-1",
            trackId = "song",
            incomingPositionSeconds = 120.4,
            durationSeconds = 180.0,
            isPlaying = true,
            playbackRate = 1.0,
            monoNowMs = 1_450L
        )
        assertNull(caughtUp.hold)
        assertEquals(120.4, caughtUp.displayedPositionSeconds, 0.001)
    }

    @Test fun supersededCancelledPlaybackResultDoesNotSurfaceAsErrorBanner() {
        val superseded = CoreResult.Failure(
            CoreErrorView(CoreErrorCode.CANCELLED, "Playback request superseded"),
            epoch = "epoch-1"
        )
        val realFailure = CoreResult.Failure(
            CoreErrorView(CoreErrorCode.UNSUPPORTED_CAPABILITY, "此远程连接暂不支持该音源或操作"),
            epoch = "epoch-1"
        )
        assertFalse(shouldSurfaceCoreError(superseded))
        assertTrue(shouldSurfaceCoreError(realFailure))
    }

    @Test fun serializeLyricsViewToLrcPreservesBilingualTimestamps() {
        val view = CoreLyricsView(
            trackId = "desktop-stream:1",
            source = "sidecar",
            synced = true,
            lines = listOf(
                CoreLyricLine(1250L, "First line", "第一句"),
                CoreLyricLine(65000L, "Second line", "第二句")
            )
        )
        val (original, translation) = serializeLyricsViewToLrc(view)!!
        assertEquals("[00:01.250]First line\n[01:05.000]Second line", original)
        assertEquals("[00:01.250]第一句\n[01:05.000]第二句", translation)
    }
}
