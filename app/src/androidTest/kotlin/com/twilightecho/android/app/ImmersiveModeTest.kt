package com.twilightecho.android.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.AppearancePreferences
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import com.twilightecho.android.playbackhost.UserPreferencesStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the actual Activity window, not just the switch's Compose state. */
@RunWith(AndroidJUnit4::class)
class ImmersiveModeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = AndroidPlaybackHost.getInstance(context).userPreferences
    private val device get() = UiDevice.getInstance(instrumentation)
    private lateinit var originalAppearance: AppearancePreferences

    @Before fun prepare() {
        originalAppearance = store.state.value.appearance
        store.update { it.copy(appearance = it.appearance.copy(immersiveMode = false)) }
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("nav-SETTINGS").fetchSemanticsNodes().isNotEmpty() }
        waitForStatusBar(true)
    }

    @After fun restore() {
        if (::originalAppearance.isInitialized) store.update { it.copy(appearance = originalAppearance) }
    }

    private fun openImmersiveMode() {
        rule.onNodeWithTag("nav-SETTINGS").performClick()
        if (rule.onAllNodesWithTag("openAppearance").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("openAppearance").performClick()
        }
        rule.onNodeWithTag("settings-APPEARANCE").performScrollToNode(hasText("沉浸模式"))
        rule.onNodeWithContentDescription("沉浸模式").assertIsDisplayed()
    }

    private fun barsVisible(type: Int): Boolean? {
        var visible: Boolean? = null
        rule.activityRule.scenario.onActivity {
            visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(type)
        }
        return visible
    }

    private fun waitForStatusBar(visible: Boolean) {
        rule.waitUntil(10_000) { barsVisible(WindowInsetsCompat.Type.statusBars()) == visible }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        device.waitForIdle()
        Thread.sleep(500) // Let the system compositor present the frames advanced by Compose's test clock.
        val directory = File(context.getExternalFilesDir(null), "immersive-mode-screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    @Test fun modeExpandsContentPersistsAndRestoresSystemBars() {
        val navigationVisible = barsVisible(WindowInsetsCompat.Type.navigationBars())
        openImmersiveMode()
        rule.onNodeWithContentDescription("沉浸模式").assertIsOff()
        val headerTop = rule.onNodeWithContentDescription("返回设置").fetchSemanticsNode().boundsInRoot.top
        var statusBarHeight = 0
        rule.activityRule.scenario.onActivity {
            statusBarHeight = ViewCompat.getRootWindowInsets(it.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        }
        assertTrue(statusBarHeight > 0)
        screenshot("01-status-bar-visible")

        rule.onNodeWithContentDescription("沉浸模式").performClick().assertIsOn()
        waitForStatusBar(false)
        assertEquals(navigationVisible, barsVisible(WindowInsetsCompat.Type.navigationBars()))
        assertTrue(UserPreferencesStore(context).state.value.appearance.immersiveMode)
        rule.activityRule.scenario.onActivity {
            assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                WindowCompat.getInsetsController(it.window, it.window.decorView).systemBarsBehavior)
        }
        assertEquals("页面应向上展开，不保留状态栏的空白占位", statusBarHeight.toFloat(),
            headerTop - rule.onNodeWithContentDescription("返回设置").fetchSemanticsNode().boundsInRoot.top, 1f)
        screenshot("02-immersive-mode")

        rule.onNodeWithTag("nav-LIBRARY").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasTestTag("nav-LIBRARY") and isSelected()).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithTag("settings-APPEARANCE").fetchSemanticsNodes().isEmpty()
        }
        waitForStatusBar(false)
        screenshot("03-immersive-library")
        rule.activityRule.scenario.recreate()
        waitForStatusBar(false)
        assertTrue(store.state.value.appearance.immersiveMode)
        device.pressHome()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForStatusBar(false)

        openImmersiveMode()
        rule.onNodeWithContentDescription("沉浸模式").assertIsOn().performClick().assertIsOff()
        waitForStatusBar(true)
        assertEquals("关闭沉浸模式后恢复原来的顶部间距", headerTop,
            rule.onNodeWithContentDescription("返回设置").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertFalse(UserPreferencesStore(context).state.value.appearance.immersiveMode)
        screenshot("04-status-bar-restored")

        rule.onNodeWithContentDescription("沉浸模式").performClick()
        waitForStatusBar(false)
        rule.onNodeWithTag("settings-APPEARANCE").performScrollToNode(hasTestTag("resetAppearance"))
        rule.onNodeWithTag("resetAppearance").performClick()
        waitForStatusBar(true)
        assertFalse(UserPreferencesStore(context).state.value.appearance.immersiveMode)
        assertEquals(navigationVisible, barsVisible(WindowInsetsCompat.Type.navigationBars()))
    }
}
