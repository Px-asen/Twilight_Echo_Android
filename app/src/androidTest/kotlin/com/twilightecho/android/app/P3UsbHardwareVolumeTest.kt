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
import com.twilightecho.android.nativeintegration.usb.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Mocked presentation cases; compiling these does not exercise a USB DAC. */
@RunWith(AndroidJUnit4::class)
class P3UsbHardwareVolumeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>
    private val device = "usb-direct:/dev/bus/usb/001/002"
    private val id = UsbHardwareControlId(7, 2, 0)
    private var reads = 0
    private val writes = mutableListOf<Int>()
    private var failRead = false

    private fun start(reject: Boolean = false) {
        val volume = UsbAudioVolumeSnapshot(-3072, listOf(UsbAudioVolumeRange(-24576, 0, 256)))
        ui = mutableStateOf(PlayerUiState(homeAvailable = false, activeMainTab = MainDestinationTab.SETTINGS,
            volume = 0.35, eqConfig = CoreEqConfigView(preampDb = -2.0, dsp = CoreDspConfig(outputDeviceId = device)),
            audioDevices = listOf(AudioOutputDeviceUi(device, "Test DAC · USB 独占"))))
        val actions = PlayerActions(usbHardwareVolume = UsbHardwareVolumeActions(read = { selected ->
            assertEquals(device, selected); reads++
            if (failRead) error("设备已断开")
            UsbHardwareVolumeSnapshot(listOf(UsbHardwareChannel(id, volume, true, false, true)))
        }, volume = { target, requested ->
            assertEquals(id, target); writes += requested
            volume.copy(currentDb256 = if (reject) volume.currentDb256 else requested)
        }, mute = { target, requested -> assertEquals(id, target); requested }))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                TwilightEchoTheme { PlayerAppContent(ui.value, actions) }
            }
        }
        rule.onNodeWithText("音频与 DSP").performScrollTo().performClick()
    }

    private fun activate() = rule.runOnUiThread {
        ui.value = ui.value.copy(diagnostics = AudioDiagnosticsUiModel(outputBackend = "usb-direct", exclusive = true))
    }
    private fun open() = rule.onNodeWithTag("usb-hardware-volume-open").performScrollTo().performClick()

    @Test fun onlyExplicitPanelOpeningReadsAndHardwareAdjustmentLeavesAppSoundSettingsUnchanged() {
        start()
        val config = ui.value.eqConfig
        rule.onNodeWithTag("usb-hardware-volume-open").performScrollTo().assertIsNotEnabled()
        assertEquals(0, reads)
        activate(); open()
        rule.onNodeWithTag("usb-hardware-2-0-current").assertTextEquals("回读 -12 dB")
        assertEquals(1, reads)
        rule.captureAudioSettingsScreenshot("p3-hardware-volume-large-font")
        rule.onNodeWithTag("usb-hardware-2-0-input").performTextReplacement("-6")
        rule.onNodeWithTag("usb-hardware-2-0-apply").performScrollTo().performClick()
        rule.onNodeWithTag("usb-hardware-2-0-current").assertTextEquals("回读 -6 dB")
        assertEquals(listOf(-1536), writes)
        assertEquals(1, reads)
        assertEquals(config, ui.value.eqConfig); assertEquals(0.35, ui.value.volume, 0.0)
        rule.runOnUiThread { ui.value = ui.value.copy(diagnostics = AudioDiagnosticsUiModel()) }
        rule.onNodeWithTag("usb-hardware-released").assertIsDisplayed()
        rule.onNodeWithTag("usb-hardware-2-0-input").assertDoesNotExist()
        rule.onNodeWithTag("usb-hardware-refresh").assertIsNotEnabled()
        rule.captureAudioSettingsScreenshot("p3-hardware-released-large-font")
    }

    @Test fun rejectedWritesDisplayActualReadbackAndFailedRefreshRemovesOldValues() {
        start(reject = true); activate(); open()
        rule.onNodeWithTag("usb-hardware-2-0-input").performTextReplacement("-6")
        rule.onNodeWithTag("usb-hardware-2-0-apply").performScrollTo().performClick()
        rule.onNodeWithTag("usb-hardware-2-0-current").assertTextEquals("回读 -12 dB")
        rule.onNodeWithTag("usb-hardware-error").assertTextContains("实际回读 -12 dB", substring = true)
        rule.runOnUiThread { failRead = true }
        rule.onNodeWithTag("usb-hardware-refresh").performClick()
        rule.onNodeWithTag("usb-hardware-error").assertTextEquals("设备已断开")
        rule.onNodeWithTag("usb-hardware-2-0-input").assertDoesNotExist()
        rule.captureAudioSettingsScreenshot("p3-hardware-error-large-font")
    }
}
