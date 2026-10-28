package com.twilightecho.android.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PluginUiFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun homeAndStreamingLibraryOpenTheSharedAccountDirectly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = AndroidPlaybackHost.getInstance(instrumentation.targetContext).plugins
        runBlocking {
            repository.initialize()
            if (repository.state.value.entries.single { it.id == "com.twilightecho.provider.ncm" }.let { it.installedVersion != it.version })
                repository.install("com.twilightecho.provider.ncm")
            repository.setEnabled("com.twilightecho.provider.ncm", true)
        }
        rule.waitUntil(20_000) {
            rule.onAllNodes(hasTestTag("streaming-account") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("streaming-account").performClick()
        rule.onNodeWithTag("providerLogin").assertIsDisplayed()
        rule.onNodeWithText("完成").performClick()
        rule.onNodeWithTag("streamingHome").assertIsDisplayed()
        rule.onNodeWithTag("streaming-open-library").performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("streamingAccess").fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithTag("streamingLibrary").fetchSemanticsNodes().isNotEmpty() }
        screenshot("streaming-library-live-account.png")
        rule.onNodeWithText("歌曲", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithTag("library").assertIsDisplayed()
    }
    @Test fun repositorySearchAndQrScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = AndroidPlaybackHost.getInstance(instrumentation.targetContext).plugins
        runBlocking {
            repository.initialize()
            if (repository.state.value.entries.single { it.id == "com.twilightecho.provider.ncm" }.let { it.installedVersion != it.version }) repository.install("com.twilightecho.provider.ncm")
            repository.setEnabled("com.twilightecho.provider.ncm", true)
            repository.refresh()
        }
        assertTrue(repository.state.value.entries.any { it.unsupportedReason != null })
        rule.onNodeWithTag("nav-SETTINGS").performClick()
        rule.onNodeWithText("插件与音源").performClick()
        rule.onNodeWithTag("pluginRepository").assertIsDisplayed()
        screenshot("plugin-repository.png")
        rule.onNodeWithTag("plugin-open-com.twilightecho.provider.ncm").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("providerSearch").fetchSemanticsNodes().size == 1 }
        rule.onNodeWithTag("providerSearch").performTextInput("Twilight")
        rule.onNodeWithTag("providerSearch").performImeAction()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("搜索结果").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("loadedTrackArtwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("plugin-ncm-search.png")
        rule.onNodeWithText("登录", substring = false).performClick()
        rule.onNodeWithText("获取二维码").performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithContentDescription("网易云登录二维码").fetchSemanticsNodes().isNotEmpty() }
        screenshot("plugin-ncm-login.png")
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
