package com.twilightecho.android.feature.player

import com.twilightecho.android.nativeintegration.usb.UsbAudioVolumeRange
import org.junit.Assert.*
import org.junit.Test

class UsbHardwareVolumePresentationTest {
    @Test fun exactDb256ValuesRoundTripWithoutTurningInfinityIntoMinus128Db() {
        for (value in listOf(-32767, -1536, -1, 0, 1, 32767)) assertEquals(value, usbVolumeDb256(usbVolumeDbText(value)))
        assertEquals("-∞", usbVolumeDbText(-32768))
        assertEquals(-32768, usbVolumeDb256("−∞"))
        assertNull(usbVolumeDb256("-128"))
        assertEquals("-0.00390625", usbVolumeDbText(-1))
    }

    @Test fun inputNeverRoundsOutOfRangeOrNonRepresentableValuesToADeviceVolume() {
        for (text in listOf("", "NaN", "Infinity", "127.99609376", "128", "-128.1", "0.001", "999999999999999999999"))
            assertNull(text, usbVolumeDb256(text))
        assertEquals(-1536, usbVolumeDb256(" -6.0 "))
        assertEquals(32767, usbVolumeDb256("127.99609375"))
    }

    @Test fun hardwareRangesKeepTheirGapsAndStepsInDbRatherThanInventingAPercentage() {
        val ranges = listOf(UsbAudioVolumeRange(-32768, -32768, 0), UsbAudioVolumeRange(-24576, 0, 128))
        assertEquals("-∞ dB\n-96 … 0 dB · 步进 0.5 dB", usbVolumeRangesText(ranges))
        assertTrue(ranges.any { it.contains(-32768) })
        assertFalse(ranges.any { it.contains(-30000) })
        assertFalse(ranges.any { it.contains(-255) })
        assertTrue(ranges.any { it.contains(-256) })
        assertEquals("-1 … 1 dB · 连续", usbVolumeRangesText(listOf(UsbAudioVolumeRange(-256, 256, 0))))
    }
}
