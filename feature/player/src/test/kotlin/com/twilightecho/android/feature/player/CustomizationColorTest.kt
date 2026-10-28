package com.twilightecho.android.feature.player

import org.junit.Assert.*
import org.junit.Test

class CustomizationColorTest {
    @Test fun acceptsExactlySixRgbDigitsWithOptionalHash() {
        assertEquals(0xFF31C48D.toInt(), parseColorHex("#31c48d"))
        assertEquals(0xFF000000.toInt(), parseColorHex("000000"))
        assertEquals("#31C48D", colorHex(0xFF31C48D.toInt()))
        assertNull(parseColorHex("#12345"))
        assertNull(parseColorHex("#ZZZZZZ"))
        assertNull(parseColorHex("#FF123456"))
    }
}
