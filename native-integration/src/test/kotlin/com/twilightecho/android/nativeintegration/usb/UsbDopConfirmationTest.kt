package com.twilightecho.android.nativeintegration.usb

import org.junit.Assert.*
import org.junit.Test

class UsbDopConfirmationTest {
    @Test fun explicitConsentAppliesToTheConfirmedDeviceModelAndVersionNotItsConnectionPath() {
        val key = AndroidUsbDirectHost.dopConfirmationKey(0x20b1, 1, "1.00")
        requireUsbDopConfirmation(key, AndroidUsbDirectHost.dopConfirmationKey(0x20b1, 1, "1.00"))
        for (other in listOf("", AndroidUsbDirectHost.dopConfirmationKey(0x20b1, 2, "1.00"),
            AndroidUsbDirectHost.dopConfirmationKey(0x20b1, 1, "2.00"))) {
            assertThrows(IllegalArgumentException::class.java) { requireUsbDopConfirmation(key, other) }
        }
        assertThrows(IllegalArgumentException::class.java) { requireUsbDopConfirmation("", key) }
    }
}
