package com.twilightecho.android.app

import android.app.AlarmManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SleepTimerActivityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("nav-SETTINGS").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-SETTINGS").performClick()
        rule.onNodeWithTag("openSleepTimerSettings").performScrollTo().performClick()
        rule.onNodeWithTag("sleepPreset60").performClick()
    }
    @Test fun activityRecreationKeepsTheOpenSheetAndDraft() {
        open()
        rule.onNodeWithTag("sleepFinishTrack").performScrollTo().performClick()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("sleepTimerSheet").assertExists()
        rule.onNodeWithTag("sleepMinutes").assertTextContains("60")
        rule.onNodeWithTag("sleepFinishTrack").assertIsOn()
    }
    @Test fun permissionGrantReturnsToTheDraftWithoutStartingAutomatically() {
        assumeTrue(!context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        open()
        rule.onNodeWithTag("startSleepTimer").performScrollTo().performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle()
        device.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        device.pressBack()
        rule.onNodeWithTag("sleepTimerSheet").assertExists()
        rule.onNodeWithTag("sleepMinutes").assertTextContains("60")
        rule.onNodeWithText("开始定时").assertExists()
        assertNull(AndroidPlaybackHost.getInstance(context).sleepTimer.state.value.timer)
    }
    @Test fun systemLargeFontKeepsTheStartActionReachable() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previous = device.executeShellCommand("settings get system font_scale").trim().toFloatOrNull() ?: 1f
        try {
            device.executeShellCommand("settings put system font_scale 1.5")
            device.waitForIdle()
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10_000) { rule.activity.resources.configuration.fontScale >= 1.49f }
            open()
            rule.onNodeWithTag("startSleepTimer").performScrollTo().assertIsDisplayed()
            rule.waitForIdle(); device.waitForIdle()
            Thread.sleep(600)
            val directory = File(context.getExternalFilesDir(null), "sleep-timer-screenshots").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(directory, "system-large-text.png")))
        } finally { device.executeShellCommand("settings put system font_scale $previous") }
    }
}
