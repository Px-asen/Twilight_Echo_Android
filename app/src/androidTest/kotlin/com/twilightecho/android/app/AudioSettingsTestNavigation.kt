package com.twilightecho.android.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import java.io.File

/** Reveal both axes: the tab's nearest scrollable parent is horizontal. */
internal fun ComposeContentTestRule.openAudioSettingsPage(page: String) {
    val tag = "audio-page-$page"
    onNodeWithTag("settings-AUDIO").performScrollToNode(hasTestTag(tag))
    onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
}

internal fun ComposeContentTestRule.captureAudioSettingsScreenshot(name: String) {
    waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Dialog windows animate on the system compositor, outside Compose's clock.
    UiDevice.getInstance(instrumentation).waitForIdle()
    Thread.sleep(500)
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
    assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "$name.png")))
}
