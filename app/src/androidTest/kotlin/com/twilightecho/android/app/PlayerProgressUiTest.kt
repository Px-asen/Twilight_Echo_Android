package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.twilightecho.android.coreclient.BackgroundStyle
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.feature.player.PlayerActions
import com.twilightecho.android.feature.player.PlayerAppContent
import com.twilightecho.android.feature.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlayerProgressUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun clockUpdatesTheTimeWithoutRecomposingTheAppCanvas() {
        val initial = PlayerUiState(currentTrack = CoreTrackView("progress-qa", title = "歌曲", duration = 120.0),
            durationSeconds = 120.0, displayedPositionSeconds = 20.0, isImmersivePlayerOpen = true, isLoadingLibrary = false)
        val ui = mutableStateOf(initial.copy(preferences = initial.preferences.copy(
            appearance = initial.preferences.appearance.copy(backgroundStyle = BackgroundStyle.SOLID))))
        val actions = PlayerActions()
        val compositions = AtomicInteger()
        val canvas = Modifier.composed { SideEffect { compositions.incrementAndGet() }; Modifier }
        rule.setContent { PlayerAppContent(ui.value, actions, canvas) }
        rule.onNodeWithText("0:20").assertIsDisplayed()
        val before = compositions.get()
        repeat(10) { tick ->
            rule.runOnIdle { ui.value = ui.value.copy(sequence = tick.toLong() + 1,
                anchorMonotonicMs = tick * 100L, anchorPositionSeconds = 20.0 + tick / 10.0,
                displayedPositionSeconds = 21.0 + tick) }
        }
        rule.onNodeWithText("0:30").assertIsDisplayed()
        assertEquals("Clock-only updates must skip the app canvas", before, compositions.get())
    }
}
