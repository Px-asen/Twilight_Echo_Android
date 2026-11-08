package com.twilightecho.android.app

import android.app.AlarmManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SleepTimerUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(1200)
        rule.waitForIdle()
        Thread.sleep(450)
        val directory = File(context.getExternalFilesDir(null), "sleep-timer-screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }
    @Test fun settingsEntryPresetsValidationAndCancel() {
        assumeTrue(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        var state by mutableStateOf(PlayerUiState(activeMainTab = MainDestinationTab.SETTINGS))
        var minutes = 0
        var finish = false
        rule.setContent {
            PlayerAppContent(state, PlayerActions(sleepTimerOpen = { state = state.copy(sleepTimerOpen = it) },
                startSleepCountdown = { m, f -> minutes = m; finish = f; state = state.copy(sleepTimer = CoreSleepTimerState(1,
                    SleepTimerState(endsAt = System.currentTimeMillis() + m * 60000L))) },
                cancelSleepTimer = { state = state.copy(sleepTimer = CoreSleepTimerState()) }))
        }
        rule.onNodeWithTag("openSleepTimerSettings").performScrollTo().performClick()
        rule.onNodeWithTag("sleepPreset45").performClick()
        rule.onNodeWithTag("sleepFinishTrack").performScrollTo().performClick()
        rule.onNodeWithTag("startSleepTimer").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(45, minutes); assertTrue(finish) }
        rule.onNodeWithTag("sleepTimerStatus").assertExists()
        rule.onNodeWithTag("cancelSleepTimer").performScrollTo().performClick()
        rule.onNodeWithTag("sleepMinutes").performTextReplacement("0")
        rule.onNodeWithTag("startSleepTimer").assertIsNotEnabled()
        rule.onNodeWithTag("sleepMinutes").performTextReplacement("721")
        rule.onNodeWithTag("startSleepTimer").assertIsNotEnabled()
        rule.onNodeWithTag("sleepMinutes").performTextReplacement("720")
        rule.onNodeWithTag("startSleepTimer").assertIsEnabled()
        device.pressBack()
    }
    @Test fun permissionDeniedKeepsADraftAndNeverStartsATimer() {
        assumeTrue(!context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        var started = false
        rule.setContent { PlayerAppContent(PlayerUiState(sleepTimerOpen = true), PlayerActions(startSleepCountdown = { _, _ -> started = true })) }
        rule.onNodeWithTag("sleepPreset60").performClick()
        rule.onNodeWithText("开启闹钟与提醒权限").assertExists()
        shot("permission-required")
        rule.runOnIdle { assertFalse(started) }
    }
    @Test fun sheetRendersDarkLightAndLargeText() {
        var light by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                PlayerAppContent(PlayerUiState(sleepTimerOpen = true, preferences = UserPreferences(appearance = AppearancePreferences(
                    backgroundStyle = BackgroundStyle.SOLID, backgroundArgb = if (light) 0xFFF7F7FA.toInt() else 0xFF1C1C1E.toInt()))), PlayerActions())
            }
        }
        shot("countdown-dark")
        rule.runOnIdle { light = true }
        rule.mainClock.advanceTimeBy(1200)
        shot("countdown-light")
        rule.onNodeWithTag("sleepAtTimeMode").performClick()
        rule.onNodeWithTag("sleepTimePicker").assertExists()
        shot("at-time-light")
        rule.runOnIdle { scale = 1.5f }
        rule.mainClock.advanceTimeBy(1200)
        rule.onNodeWithTag("startSleepTimer").performScrollTo().assertIsDisplayed()
        shot("large-text")
    }
    @Test fun landscapeKeepsControlsScrollableAndVisible() {
        try {
            device.setOrientationLeft()
            rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            rule.setContent { PlayerAppContent(PlayerUiState(sleepTimerOpen = true), PlayerActions()) }
            rule.onNodeWithTag("startSleepTimer").performScrollTo().assertIsDisplayed()
            shot("landscape")
        } finally { device.setOrientationNatural(); device.unfreezeRotation() }
    }
    @Test fun lightThemeRendersTheCountdownSheet() {
        rule.setContent { PlayerAppContent(PlayerUiState(sleepTimerOpen = true, preferences = UserPreferences(appearance = AppearancePreferences(
            backgroundStyle = BackgroundStyle.SOLID, backgroundArgb = 0xFFF7F7FA.toInt()))), PlayerActions()) }
        rule.onNodeWithTag("startSleepTimer").assertExists()
        shot("countdown-light-initial")
    }
    @Test fun playerEntryAndRunningStatusOpenTheSameSheet() {
        var state by mutableStateOf(PlayerUiState(currentTrack = CoreTrackView("one", title = "Night Walk", artist = "Twilight Echo"),
            isImmersivePlayerOpen = true, sleepTimer = CoreSleepTimerState(1,
                SleepTimerState(endsAt = System.currentTimeMillis() + 30 * 60000L))))
        rule.setContent { PlayerAppContent(state, PlayerActions(sleepTimerOpen = { state = state.copy(sleepTimerOpen = it) })) }
        rule.onNodeWithTag("playerSleepTimer").performClick()
        rule.onNodeWithTag("sleepTimerSheet").assertExists()
        rule.runOnIdle { state = state.copy(sleepTimerOpen = false) }
        rule.onNodeWithContentDescription("更多播放操作").performClick()
        rule.onNodeWithText("睡眠与定时").performClick()
        rule.onNodeWithTag("sleepTimerSheet").assertExists()
    }
}
