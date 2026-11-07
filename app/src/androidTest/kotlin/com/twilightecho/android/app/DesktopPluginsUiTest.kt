package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DesktopPluginsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val plugin = DesktopManagedPlugin("radio", "电台音源", "1.0.0", description = "浏览收藏的网络电台", author = "Twilight", token = "v1")
    private fun shot(name: String) {
        rule.waitForIdle(); Thread.sleep(300)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "plugin-shots").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory, "$name.png")))
    }
    @Test fun installRequiresTrustAndWritesWaitForReadback() {
        val preview = DesktopPluginPreview("preview", DesktopPluginManifest("radio", "电台音源", "2.0.0", author = "Twilight", permissions = listOf("network", "settings")),
            previousVersion = "1.0.0", previouslyEnabled = true, expiresAt = Long.MAX_VALUE)
        val state = mutableStateOf(DesktopPluginsState(open = true, host = "书房 Server", page = DesktopPluginsPage(true, 1, items = listOf(plugin)), preview = preview))
        var installed = false
        val actions = DesktopPluginsActions(install = { installed = true; state.value = state.value.copy(preview = null, writing = true) })
        rule.setContent { TwilightEchoTheme { PlayerAppContent(PlayerUiState(desktopPlugins = state.value), PlayerActions(desktopPlugins = actions)) } }
        rule.onNodeWithTag("serverPluginConfirm").assertIsNotEnabled()
        shot("install-preview")
        rule.onNodeWithTag("serverPluginTrust").performScrollTo().performClick()
        rule.onNodeWithTag("serverPluginConfirm").performClick()
        rule.runOnIdle { assertTrue(installed) }
        rule.onNodeWithTag("serverPluginUpload").assertIsNotEnabled()
        rule.onNodeWithText("关闭").assertIsNotEnabled()
        rule.runOnIdle { state.value = state.value.copy(writing = false, needsRefresh = true, error = "安装未获确认，请刷新核对。") }
        rule.onNodeWithTag("serverPluginUpload").assertIsNotEnabled()
        rule.onNodeWithText("刷新").assertIsEnabled()
        shot("refresh-required")
    }
    @Test fun uninstallKeepsDataUnlessExplicitlySelected() {
        var removed: Boolean? = null
        val state = DesktopPluginsState(open = true, host = "书房 Server", page = DesktopPluginsPage(true, 1, items = listOf(plugin)))
        rule.setContent { TwilightEchoTheme { PlayerAppContent(PlayerUiState(desktopPlugins = state),
            PlayerActions(desktopPlugins = DesktopPluginsActions(edit = { _, action, data -> if (action == "uninstall") removed = data }))) } }
        shot("extension-list")
        rule.onNodeWithText("卸载").performClick()
        rule.onNodeWithTag("serverPluginRemoveData").assertIsOff()
        rule.onNodeWithText("确认卸载").performClick()
        rule.runOnIdle { assertEquals(false, removed) }
    }
}
