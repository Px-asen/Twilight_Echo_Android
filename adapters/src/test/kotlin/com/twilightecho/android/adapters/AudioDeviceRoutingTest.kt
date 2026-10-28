package com.twilightecho.android.adapters

import android.media.AudioDeviceInfo
import com.twilightecho.android.runtimehost.AudioDeviceRouteInfo
import com.twilightecho.android.runtimehost.AudioSnapshot
import org.junit.Assert.*
import org.junit.Test

class AudioDeviceRoutingTest {
    private val speaker = AudioDeviceRouteInfo(1, "手机扬声器", "speaker", supportedSampleRates = listOf(48000))
    private val usb = AudioDeviceRouteInfo(9, "USB DAC", "usb_dac", supportedSampleRates = listOf(44100, 192000))
    private val bluetooth = AudioDeviceRouteInfo(12, "耳机", "bluetooth")
    private val playing = AudioSnapshot(state = "playing", actualDeviceId = "1", actualSampleRate = 48000)

    @Test fun connectedUsbDoesNotReplaceTheRoutedSpeaker() {
        assertEquals(1, resolvePlaybackRoute(listOf(usb, bluetooth, speaker), playing).deviceId)
    }

    @Test fun inventoryRatesNeverBecomeTheActiveRate() {
        assertEquals(0, usb.activeSampleRate)
        val route = resolvePlaybackRoute(listOf(usb), playing.copy(actualDeviceId = "9", actualSampleRate = 44100))
        assertEquals(44100, route.activeSampleRate)
        assertEquals(listOf(44100, 192000), route.supportedSampleRates)
    }

    @Test fun missingOrStaleStreamDoesNotChooseAnAvailableDevice() {
        assertEquals("unknown", resolvePlaybackRoute(listOf(usb), null).routeType)
        assertEquals(0, resolvePlaybackRoute(listOf(usb), playing.copy(state = "idle")).activeSampleRate)
        assertEquals(0, resolvePlaybackRoute(listOf(usb), playing.copy(state = "error")).deviceId)
        assertEquals("unknown", resolvePlaybackRoute(listOf(usb), playing.copy(actualDeviceId = null)).routeType)
    }

    @Test fun bluetoothOutputsNameTheirLinkAndKeepItWhenRouted() {
        assertEquals("a2dp", bluetoothProfile(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals("le_audio", bluetoothProfile(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals("le_audio", bluetoothProfile(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertEquals("le_audio", bluetoothProfile(AudioDeviceInfo.TYPE_BLE_BROADCAST))
        assertEquals("call", bluetoothProfile(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals("", bluetoothProfile(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals("", bluetoothProfile(AudioDeviceInfo.TYPE_USB_DEVICE))
        val headset = bluetooth.copy(bluetoothProfile = "a2dp")
        assertEquals("a2dp", resolvePlaybackRoute(listOf(speaker, headset), playing.copy(actualDeviceId = "12")).bluetoothProfile)
        assertEquals("", resolvePlaybackRoute(listOf(headset), playing.copy(actualDeviceId = "40")).bluetoothProfile)
    }

    @Test fun onlyRemovalOfTheActiveHeadphonePausesPlayback() {
        assertFalse(shouldPauseForDeviceRemoval(speaker, setOf(9)))
        assertFalse(shouldPauseForDeviceRemoval(usb, setOf(12)))
        assertTrue(shouldPauseForDeviceRemoval(usb, setOf(9)))
        assertTrue(shouldPauseForDeviceRemoval(bluetooth, setOf(12)))
        assertFalse(shouldPauseForDeviceRemoval(AudioDeviceRouteInfo(), setOf(9)))
    }
}
