package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BufferingPlayerUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun bufferingShowsItsStateAndStillLetsTheListenerPause() {
        val track = CoreTrackView("buffering-ui", title = "暮色里的旋律", artist = "北岸", duration = 180.0)
        val state = mutableStateOf(PlayerUiState(currentTrack = track, libraryTracks = listOf(track),
            isLoadingLibrary = false, isImmersivePlayerOpen = true, durationSeconds = 180.0,
            playbackState = "buffering", isPlaying = false, displayedPositionSeconds = 12.5))
        var paused = false
        rule.setContent { TwilightEchoTheme { PlayerAppContent(state.value, PlayerActions(togglePlayback = {
            paused = true
            state.value = state.value.copy(playbackState = "paused")
        })) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("playerPlayPause").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playerPlayPause").assertIsDisplayed()
        rule.onNodeWithContentDescription("暂停播放").assertIsDisplayed().assertIsEnabled()
        rule.waitForIdle()
        Thread.sleep(450) // Let the finite full-screen transition reach the Android surface.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "buffering-qa").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "buffering-player.png")))
        rule.onNodeWithContentDescription("暂停播放").performClick()
        rule.onNodeWithContentDescription("开始播放").assertIsDisplayed()
        assertTrue(paused)
        assertEquals(12.5, state.value.displayedPositionSeconds, 0.0)
    }
}
