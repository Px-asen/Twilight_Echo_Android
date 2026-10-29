package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import org.junit.Assert.*
import org.junit.Test

class LyricsMotionTest {
    private val lyrics = CoreLyricsView("song", lines = listOf(
        CoreLyricLine(0, "暮色落在窗边", "Evening at the window"), CoreLyricLine(4000, "风吹过唱片")))

    @Test fun focusUsesFixedGeometryAndDistanceLayers() {
        val active = lyricFocus(3, 3, true, true, true, 36)
        val neighbor = lyricFocus(4, 3, true, true, true, 36)
        val distant = lyricFocus(0, 3, true, true, true, 36)
        assertEquals(1f, active.alpha)
        assertEquals(1f, active.scale)
        assertEquals(0f, active.offsetDp)
        assertEquals(0f, active.blurDp)
        assertEquals(0.42f, neighbor.alpha)
        assertEquals(0.9f, neighbor.blurDp, 0.001f)
        assertEquals(0.30f, distant.alpha)
        assertEquals(0.95f, distant.scale)
        assertEquals(0f, distant.offsetDp)
        assertEquals(2.7f, distant.blurDp, 0.001f)
        assertEquals(4f, lyricFocus(0, 9, true, true, true, 36).blurDp)
        assertEquals(0.22f, lyricFocus(0, 9, true, true, true, 36).alpha)
    }

    @Test fun browsingAndOlderAndroidNeverBlurText() {
        assertEquals(0f, lyricFocus(0, 4, true, false, true, 36).blurDp)
        assertEquals(0f, lyricFocus(0, 4, true, true, true, 26).blurDp)
    }

    @Test fun customBlurCanBeDisabledOrScaledWithoutChangingFocus() {
        val normal = lyricFocus(4, 3, true, true, true, 36)
        assertEquals(normal.copy(blurDp = 0f), lyricFocus(4, 3, true, true, true, 36, 0f))
        assertEquals(normal.copy(blurDp = 1.8f), lyricFocus(4, 3, true, true, true, 36, 8f))
        assertEquals(8f, lyricFocus(0, 12, true, true, true, 36, 8f).blurDp)
        assertEquals(0f, lyricFocus(0, 12, true, false, true, 36, 8f).blurDp)
    }

    @Test fun reducedMotionRetainsFocusWithoutTransforms() {
        assertEquals(LyricFocus(0.22f, 1f, 0f, 0f), lyricFocus(0, 4, true, true, false, 36))
        assertEquals(LyricFocus(1f, 1f, 0f, 0f), lyricFocus(4, 4, true, true, false, 36))
    }

    @Test fun untimedLyricsHaveNoArtificialTimeFocus() {
        assertEquals(LyricFocus(0.86f, 1f, 0f, 0f), lyricFocus(0, 4, false, true, true, 36))
        assertEquals(0f, lyricFocus(0, -1, true, true, true, 36).blurDp)
    }

    @Test fun manualOwnershipHasNoTimedResumeAndExplicitSeekCanRealignSameLine() {
        val follow = LyricsFollowController()
        assertTrue(follow.following)
        follow.aligned(2)
        follow.browse()
        follow.aligned(3)
        assertFalse(follow.following)
        follow.resume()
        assertTrue(follow.following)
        assertEquals(1, follow.alignmentRequest)
        follow.resume()
        assertEquals(2, follow.alignmentRequest)
    }

    @Test fun initialAndLargeJumpsSnapButNeighborsUseSpring() {
        val follow = LyricsFollowController()
        assertTrue(follow.shouldSnap(2, true))
        follow.aligned(2)
        assertFalse(follow.shouldSnap(3, true))
        assertTrue(follow.shouldSnap(6, true))
        assertTrue(follow.shouldSnap(3, false))
        assertTrue("Resuming from a distant manual viewport must not fly across the song", follow.shouldSnap(2, true, 9))
        assertFalse(follow.shouldSnap(2, true, 3))
    }

    @Test fun waveSpringStartsAtRestSettlesAndBarelyOvershoots() {
        assertEquals(0f, lyricSpringStep(0f))
        assertTrue(lyricSpringStep(0.03f) < 0.1f)
        assertEquals(1f, lyricSpringStep(LyricsMotionSpec.SettleSeconds), 0.002f)
        val peak = (1..200).maxOf { lyricSpringStep(it / 200f) }
        assertTrue("A soft landing, not a bounce: peak=$peak", peak in 1.005f..1.04f)
    }

    @Test fun waveRowsBelowTheNewLineTrailInOrder() {
        val wave = LyricsWave()
        assertEquals(0L, wave.delayNanos(3, 5))
        assertEquals(0L, wave.delayNanos(4, 5))
        assertTrue(wave.delayNanos(5, 5) < wave.delayNanos(6, 5))
        assertEquals(wave.delayNanos(40, 5), wave.delayNanos(12, 5))
        wave.push(120f, 5)
        assertEquals("Before the first frame every row still sits where it was", 120f, wave.offset(9))
        assertEquals(0f, LyricsWave().offset(0))
    }

    @Test fun identityChangesWithTrackSourceAndLyricsButNotPlaybackTicks() {
        val state = PlayerUiState(currentTrack = CoreTrackView("song", title = "歌曲"), currentLyrics = lyrics)
        val identity = lyricsSceneIdentity(state)
        assertEquals(identity, lyricsSceneIdentity(state.copy(displayedPositionSeconds = 12.0, activeLyricIndex = 1)))
        assertNotEquals(identity, lyricsSceneIdentity(state.copy(operatingMode = CoreOperatingMode.RemoteDesktopCore("http://core"))))
        assertNotEquals(identity, lyricsSceneIdentity(state.copy(currentLyrics = lyrics.copy(lines = lyrics.lines.take(1)))))
        assertNull(lyricsSceneIdentity(state.copy(currentTrack = CoreTrackView("other", title = "另一首"))).lyrics)
    }
}
