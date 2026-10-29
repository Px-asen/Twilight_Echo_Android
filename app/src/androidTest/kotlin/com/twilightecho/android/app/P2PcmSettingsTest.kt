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
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class P2PcmSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>

    private fun start(fontScale: Float = 1f) {
        ui = mutableStateOf(PlayerUiState(homeAvailable = false, activeMainTab = MainDestinationTab.SETTINGS,
            eqConfig = CoreEqConfigView(bitPerfect = true), activeOutputSampleRates = listOf(44100, 48000, 96000),
            diagnostics = AudioDiagnosticsUiModel(sourceSampleRateHz = 88200, requestedSampleRateHz = 96000, actualSampleRateHz = 48000)))
        fun audio(edit: (CoreEqConfigView) -> CoreEqConfigView) { ui.value = ui.value.copy(eqConfig = edit(ui.value.eqConfig)) }
        val actions = PlayerActions(editAudio = ::audio, editDsp = { edit -> audio { it.copy(dsp = edit(it.dsp)) } })
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TwilightEchoTheme { PlayerAppContent(ui.value, actions) }
            }
        }
        rule.onNodeWithText("音频与 DSP").performScrollTo().performClick()
    }

    private fun tap(tag: String) {
        // A chip's nearest scrollable ancestor is horizontal. First reveal its
        // whole row in the settings' vertical list, then scroll within that row.
        when {
            tag.startsWith("pcm-allowed-") -> rule.onNodeWithTag("pcm-allowed-row").performScrollTo()
            tag.startsWith("pcm-fixed-") -> rule.onNodeWithTag("pcm-fixed-row").performScrollTo()
        }
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }
    private fun readable(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            // Compose can retain a paragraph's wrapping width after font-scale
            // changes. Verify the complete visible glyphs and their actual bounds.
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "$name.png")))
    }

    @Test fun policyAndAllowedSetControlsUseDeclaredRatesAndSeparateRequestFromActual() {
        start()
        tap("pcm-policy-family")
        assertTrue(ui.value.eqConfig.bitPerfect && ui.value.eqConfig.dsp.sampleRateFamilySync)
        tap("pcm-allowed-custom")
        assertEquals(listOf(48000), ui.value.eqConfig.dsp.allowedSampleRates)
        tap("pcm-allow-96000")
        assertEquals(listOf(48000, 96000), ui.value.eqConfig.dsp.allowedSampleRates)
        tap("pcm-policy-fixed")
        assertFalse(ui.value.eqConfig.dsp.sampleRateFamilySync)
        tap("pcm-fixed-96000")
        assertEquals(96000, ui.value.eqConfig.dsp.outputStage.targetSampleRate)
        rule.onNodeWithTag("pcm-fixed-44100").assertDoesNotExist()
        tap("pcm-allow-96000")
        rule.onNodeWithTag("pcm-selection-error").performScrollTo().assertTextEquals("先更改固定目标，再移除此采样率。")
        assertEquals(listOf(48000, 96000), ui.value.eqConfig.dsp.allowedSampleRates)
        tap("pcm-fixed-48000")
        rule.onNodeWithTag("pcm-selection-error").assertDoesNotExist()
        tap("pcm-policy-family")
        rule.onNodeWithTag("pcm-rate-result").performScrollTo()
        rule.onNodeWithTag("pcm-rate-request").assertTextEquals("引擎请求 96 kHz")
        rule.onNodeWithTag("pcm-rate-actual").assertTextEquals("播放流 48 kHz")
        rule.onNodeWithTag("pcm-device-rates").assertTextEquals("设备声明：44.1 kHz、48 kHz、96 kHz")
        shot("p2-pcm-family")
    }

    @Test fun largeFontAndUndeclaredOrConflictingCapabilitiesStayActionable() {
        start(1.5f)
        tap("pcm-policy-family")
        rule.runOnUiThread { ui.value = ui.value.copy(activeOutputSampleRates = emptyList()) }
        rule.onNodeWithTag("pcm-device-rates").performScrollTo().assertTextEquals("设备未声明采样率，仍可请求输出。")
        tap("pcm-allowed-custom")
        tap("pcm-allow-48000")
        rule.onNodeWithTag("pcm-selection-error").performScrollTo().assertTextEquals("至少保留一个采样率，或选择“不限制”。")
        rule.runOnUiThread { ui.value = ui.value.copy(activeOutputSampleRates = listOf(44100)) }
        rule.onNodeWithTag("pcm-rate-conflict").performScrollTo().assertIsDisplayed()
        tap("pcm-policy-fixed")
        assertTrue(ui.value.eqConfig.dsp.sampleRateFamilySync)
        rule.onNodeWithTag("pcm-selection-error").performScrollTo().assertTextEquals("先调整允许集合，再选择固定采样率。")
        tap("pcm-allow-44100")
        rule.onNodeWithTag("pcm-rate-conflict").assertDoesNotExist()
        tap("pcm-allow-48000")
        tap("pcm-policy-fixed")
        assertEquals(44100, ui.value.eqConfig.dsp.outputStage.targetSampleRate)
        rule.runOnUiThread { ui.value = ui.value.copy(diagnostics = ui.value.diagnostics.copy(requestedSampleRateHz = 44100)) }
        rule.onNodeWithTag("pcm-rate-result").performScrollTo()
        rule.onNodeWithTag("pcm-rate-request").assertTextEquals("引擎请求 44.1 kHz")
        rule.onNodeWithTag("pcm-rate-actual").assertTextEquals("播放流 48 kHz")
        readable("pcm-device-rates")
        readable("pcm-rate-request")
        readable("pcm-rate-actual")
        shot("p2-pcm-large-font")
    }
}
