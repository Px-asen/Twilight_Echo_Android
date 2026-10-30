package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** UI fixtures test controls and readable layout; model/transport tests are separate. */
@RunWith(AndroidJUnit4::class)
class P2AutoMixSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>
    private val requested = mutableListOf<String>()
    private var accepts = true
    private fun start(scale: Float) {
        ui = mutableStateOf(PlayerUiState(homeAvailable = false, activeMainTab = MainDestinationTab.SETTINGS, isPlaying = true))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                TwilightEchoTheme { PlayerAppContent(ui.value, PlayerActions(autoMixMode = { mode ->
                    requested += mode
                    if (accepts) ui.value = ui.value.copy(diagnostics = ui.value.diagnostics.copy(autoMixMode = mode))
                })) }
            }
        }
        rule.onNodeWithText("音频与 DSP").performScrollTo().performClick()
    }
    private fun tap(mode: String) {
        rule.onNodeWithTag("automix-mode-row").performScrollTo()
        rule.onNodeWithTag("automix-mode-$mode").performScrollTo().performClick()
        rule.waitForIdle()
    }
    private fun readable(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue(results.isNotEmpty())
        results.forEach { layout ->
            assertFalse(layout.didOverflowHeight)
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue(layout.getLineLeft(line) >= -1f)
                assertTrue(layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue(layout.getLineBottom(line) <= layout.size.height + 1f)
            }
        }
    }
    private fun shot(name: String) {
        rule.waitForIdle()
        val i = InstrumentationRegistry.getInstrumentation()
        val directory = File(i.targetContext.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(i).takeScreenshot(File(directory, "$name.png")))
    }
    @Test fun modeChoicesUseAcceptedStateAndKeepNativePreparationDistinct() {
        start(1f)
        rule.onNodeWithTag("automix-status").performScrollTo().assertTextEquals("已关闭 · 使用无缝播放或普通交叉淡化")
        accepts = false; tap("natural")
        assertEquals(listOf("natural"), requested)
        assertEquals("off", ui.value.diagnostics.autoMixMode)
        accepts = true; tap("natural")
        rule.runOnUiThread { ui.value = ui.value.copy(diagnostics = ui.value.diagnostics.copy(
            autoMixState = "ready", autoMixStrategy = "content-boundary")) }
        readable("automix-status"); readable("automix-help")
        rule.onNodeWithTag("automix-status").assertTextEquals("下一次过渡已就绪 · 内容边界")
        shot("p2-automix-ready")
        tap("off")
        rule.onNodeWithTag("automix-help").assertDoesNotExist()
    }
    @Test fun largeFontKeepsDjExplanationAndSourceFailureReadable() {
        start(1.5f); tap("dj")
        rule.runOnUiThread { ui.value = ui.value.copy(diagnostics = ui.value.diagnostics.copy(
            autoMixState = "degraded", autoMixReason = "actual_source_identity_unavailable")) }
        readable("automix-help"); readable("automix-status")
        rule.onNodeWithTag("automix-status").assertTextEquals("本音源无法取得稳定的本地文件身份 · 使用普通播放")
        shot("p2-automix-large-font")
        rule.runOnUiThread { ui.value = ui.value.copy(diagnostics = ui.value.diagnostics.copy(
            autoMixState = "mixing", autoMixStrategy = "beat-aligned")) }
        rule.onNodeWithTag("automix-status").assertTextEquals("正在过渡 · 节拍对齐")
    }
}
