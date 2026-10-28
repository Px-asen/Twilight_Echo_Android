package com.twilightecho.android.playbackhost

import com.twilightecho.android.coreclient.NcmPlaybackQuality
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NcmPlaybackQualityTest {
    @Test fun everyVisibleChoiceReachesTheProviderOnInitialLoadAndRenewal() {
        assertEquals(listOf("auto", "hires", "lossless", "exhigh", "standard"), NcmPlaybackQuality.entries.map { it.wireValue })
        NcmPlaybackQuality.entries.forEach { quality ->
            listOf(false, true).forEach { force ->
                val args = ncmPlaybackArguments("42", quality, force)
                assertEquals("42", args[0].jsonObject.getValue("ncmSongId").jsonPrimitive.content)
                assertEquals(quality.wireValue, args[1].jsonObject.getValue("quality").jsonPrimitive.content)
                assertEquals(force, args[1].jsonObject.getValue("force").jsonPrimitive.boolean)
            }
        }
    }
}
