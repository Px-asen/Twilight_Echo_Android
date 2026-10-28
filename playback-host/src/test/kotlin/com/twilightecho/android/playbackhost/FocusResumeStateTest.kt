package com.twilightecho.android.playbackhost

import org.junit.Assert.*
import org.junit.Test

class FocusResumeStateTest {
    @Test fun pausedPlayerStaysPausedWhenFocusReturns() {
        val state = FocusResumeState()
        state.transientLoss(false)
        assertFalse(state.gain())
    }

    @Test fun interruptedPlaybackResumesOnlyOnce() {
        val state = FocusResumeState()
        state.transientLoss(true)
        state.transientLoss(false)
        assertTrue(state.gain())
        assertFalse(state.gain())
    }

    @Test fun userPauseStopOrDisconnectCancelsResume() {
        val state = FocusResumeState()
        state.transientLoss(true)
        state.cancel()
        assertFalse(state.gain())
    }
}
