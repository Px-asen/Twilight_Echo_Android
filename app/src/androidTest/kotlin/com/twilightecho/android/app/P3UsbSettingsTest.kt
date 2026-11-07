package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Presentation fixtures only; USB device acceptance is a separate hardware run. */
@RunWith(AndroidJUnit4::class)
class P3UsbSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>
    private val device = "usb-direct:/dev/bus/usb/001/002"
    private val selections = mutableListOf<String>()

    private fun start(disconnected: Boolean = false, allowDsp: Boolean = false, config: CoreEqConfigView = CoreEqConfigView()) {
        ui = mutableStateOf(PlayerUiState(homeAvailable = false, activeMainTab = MainDestinationTab.SETTINGS,
            eqConfig = if (disconnected) config.copy(dsp = config.dsp.copy(outputDeviceId = device)) else config,
            audioDevices = if (disconnected) emptyList() else listOf(AudioOutputDeviceUi(device, "Test DAC · USB 独占",
                dopConfirmationKey = "8369:1:1.00"))))
        val actions = PlayerActions(selectOutputDevice = { selected ->
            selections += selected
            ui.value = ui.value.copy(eqConfig = ui.value.eqConfig.copy(dsp = ui.value.eqConfig.dsp.copy(outputDeviceId = selected)))
        }, editDsp = { edit ->
            if (!allowDsp) fail("Output selection must use its permission-aware handler")
            ui.value = ui.value.copy(eqConfig = ui.value.eqConfig.copy(dsp = edit(ui.value.eqConfig.dsp)))
        })
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                TwilightEchoTheme { PlayerAppContent(ui.value, actions) }
            }
        }
        rule.onNodeWithText("音频与 DSP").performScrollTo().performClick()
    }

    private fun select(id: String) {
        rule.onNodeWithTag("output-device-row").performScrollTo()
        rule.onNodeWithTag("output-device-$id").performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test fun directSelectionAndActiveThenReleasedStatusStaySeparateFromSystemControls() {
        start()
        select(device)
        assertEquals(listOf(device), selections)
        rule.onNodeWithTag("usb-direct-setting").performScrollTo().assertTextEquals("USB 直连独占")
        rule.onNodeWithTag("usb-pcm-setting").assertDoesNotExist()
        rule.onNodeWithText("请求 AAudio 独占").assertDoesNotExist()
        rule.onNodeWithTag("usb-direct-status").performScrollTo().assertTextContains("点播放建立 USB 直连", substring = true)
        rule.runOnUiThread {
            ui.value = ui.value.copy(diagnostics = AudioDiagnosticsUiModel(outputBackend = "usb-direct",
                exclusive = true, actualSampleRateHz = 96000, actualBitDepth = 24, actualSampleFormat = "int24"))
        }
        rule.onNodeWithTag("usb-direct-status").performScrollTo().assertIsDisplayed()
            .assertTextContains("USB 接口已独占", substring = true)
        rule.captureAudioSettingsScreenshot("p3-usb-active-large-font")
        rule.runOnUiThread { ui.value = ui.value.copy(diagnostics = AudioDiagnosticsUiModel()) }
        rule.onNodeWithTag("usb-direct-status").performScrollTo().assertIsDisplayed()
            .assertTextContains("点播放建立 USB 直连", substring = true)
        rule.captureAudioSettingsScreenshot("p3-usb-released-large-font")
        select("auto")
        rule.onNodeWithTag("usb-direct-setting").assertDoesNotExist()
        rule.onNodeWithTag("usb-pcm-setting").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("请求 AAudio 独占").performScrollTo().assertIsDisplayed()
    }

    @Test fun missingHostDeviceKeepsTheSavedSelectionAndOffersAnExplicitRouteChange() {
        start(disconnected = true)
        rule.onNodeWithText("所选 USB 设备当前未连接，播放保持暂停。可重新选择设备。").performScrollTo().assertIsDisplayed()
        assertEquals(device, ui.value.eqConfig.dsp.outputDeviceId)
        assertTrue(selections.isEmpty())
        rule.captureAudioSettingsScreenshot("p3-usb-missing-large-font")
        select("auto")
        assertEquals(listOf("auto"), selections)
    }

    @Test fun dopRequiresExplicitDacConfirmationWithoutAdjustingTheExistingSound() {
        val initial = CoreEqConfigView(enabled = true, preampDb = -2.0,
            dsp = CoreDspConfig(playbackRate = 1.25, crossfadeSeconds = 2.0))
        start(allowDsp = true, config = initial)
        select(device)
        rule.onNodeWithTag("dsd-mode-row").performScrollTo()
        rule.onNodeWithTag("dsd-mode-dop").performScrollTo().performClick()
        rule.onNodeWithText("确认 DAC 支持 DoP").assertIsDisplayed()
        rule.onNodeWithText("Test DAC").assertIsDisplayed()
        assertEquals("pcm", ui.value.eqConfig.dsp.dsdOutputMode)
        rule.captureAudioSettingsScreenshot("p3-dop-confirm-large-font")
        rule.onNodeWithText("取消").performClick()
        assertEquals("", ui.value.eqConfig.dsp.dopConfirmedDeviceKey)
        rule.onNodeWithTag("dsd-mode-dop").performScrollTo().performClick()
        rule.onNodeWithTag("dsd-confirm-dop").performClick()
        rule.waitForIdle()
        assertEquals("dop", ui.value.eqConfig.dsp.dsdOutputMode)
        assertEquals("8369:1:1.00", ui.value.eqConfig.dsp.dopConfirmedDeviceKey)
        assertEquals(initial.dsp.copy(outputDeviceId = device, dsdOutputMode = "dop", dopConfirmedDeviceKey = "8369:1:1.00"),
            ui.value.eqConfig.dsp)
        assertEquals(initial.preampDb, ui.value.eqConfig.preampDb, 0.0)
        assertTrue(ui.value.eqConfig.enabled)
    }
}
