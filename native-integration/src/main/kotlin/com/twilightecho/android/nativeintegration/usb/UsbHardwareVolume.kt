package com.twilightecho.android.nativeintegration.usb

/** A control belongs to one open USB lease, not a saved device model or DoP confirmation. */
data class UsbHardwareControlId(val leaseId: Long, val unitId: Int, val channel: Int)

data class UsbHardwareChannel(
    val id: UsbHardwareControlId,
    val volume: UsbAudioVolumeSnapshot?,
    val volumeWritable: Boolean,
    val muted: Boolean?,
    val muteWritable: Boolean,
)

data class UsbHardwareVolumeSnapshot(val channels: List<UsbHardwareChannel>)
