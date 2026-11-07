package com.twilightecho.android.nativeintegration.usb

/** Numeric USB identity and firmware revision, never product-name guesses. */
internal data class UsbDsdDeviceIdentity(val vendor: Int, val product: Int, val revision: Int) {
    /**
     * Wire facts from Linux v6.12 sound/usb/quirks.c, snd_usb_interface_dsd_format_quirks.
     * Start with the no-vendor-command variants: XMOS RAW_DATA and known Combo384 firmware.
     * RAW_DATA by itself does not identify DSD; other devices remain unavailable.
     */
    fun nativeFormat(function: UsbAudioFunction, stream: UsbAudioStreamingAlternate): String? {
        if (function.version != UsbAudioVersion.UAC2 || stream.subslotBytes != 4) return null
        if (vendor == 0x20b1 && stream.rawData) return "dsd-u32-be"
        if (vendor == 0x16d0 && product == 0x071a && stream.alternateSetting == 2) return when (revision) {
            0x0199 -> "dsd-u32-le"
            0x019b, 0x0203 -> "dsd-u32-be"
            else -> null
        }
        return null
    }
}
