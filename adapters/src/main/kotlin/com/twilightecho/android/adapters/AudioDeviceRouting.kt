package com.twilightecho.android.adapters

import android.media.AudioDeviceInfo
import com.twilightecho.android.runtimehost.AudioDeviceRouteInfo
import com.twilightecho.android.runtimehost.AudioSnapshot

/** Device presence never selects an output. Only the playback stream supplies the active ID. */
internal fun resolvePlaybackRoute(devices: List<AudioDeviceRouteInfo>, output: AudioSnapshot?): AudioDeviceRouteInfo {
    if (output == null || output.state !in setOf("playing", "paused")) return AudioDeviceRouteInfo()
    val id = output.actualDeviceId?.toIntOrNull()?.takeIf { it > 0 }
    val device = devices.firstOrNull { it.deviceId == id }
        ?: AudioDeviceRouteInfo(deviceId = id ?: 0, deviceName = output.actualDeviceName ?: "系统输出")
    return device.copy(
        outputBackend = output.outputBackend.orEmpty(),
        activeSampleRate = output.actualSampleRate?.takeIf { it > 0 } ?: 0
    )
}

/**
 * The Bluetooth link of an output, as Android names it. The codec on the link is not given to ordinary apps:
 * the phone's codec list and the negotiated codec both need system permissions, and a headset's name says neither.
 */
internal fun bluetoothProfile(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "a2dp"
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "le_audio"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "call"
    else -> ""
}

internal fun shouldPauseForDeviceRemoval(active: AudioDeviceRouteInfo, removedIds: Set<Int>): Boolean =
    active.deviceId > 0 && active.deviceId in removedIds &&
        active.routeType in setOf("usb_dac", "wired_headset", "bluetooth")
