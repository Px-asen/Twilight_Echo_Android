package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CorePlaybackView
import com.twilightecho.android.coreclient.CoreSnapshot
import org.junit.Assert.*
import org.junit.Test

class DsdOutputPresentationTest {
    @Test fun sourceBitClockIsNotAHighRatePcmOrUsbWordClock() {
        val source = CorePlaybackView(isDsd = true, dsdMode = "native", dsdRate = 64, actualDsdRate = 64,
            sourceSampleRate = 2822400, sourceBitDepth = 1, actualSampleRate = 2822400, actualBitDepth = 1,
            transportSampleRate = 88200, semanticSampleRate = 2822400)
        val d = diagnosticsFor(CoreSnapshot(epoch = "dsd", sequence = 1, playback = source))
        assertEquals(2822400, d.sourceSampleRateHz)
        assertEquals(88200, d.transportSampleRateHz)
        assertEquals("Native DSD64 · 2.8224 MHz / 1 位", outputResolution(d))
        assertEquals("DSD64 · 2.8224 MHz / 1 位", dsdResolution(d.sourceSampleRateHz, d.dsdRate))
        assertEquals("2.8224 MHz / 1 位", requestedOutputResolution(d.copy(requestedSampleRateHz = 2822400,
            requestedSampleFormat = "dsd-int8-msb1")))
        val dop = d.copy(dsdMode = "dop", actualSampleRateHz = 176400, actualBitDepth = 24,
            actualSampleFormat = "int24", transportSampleRateHz = 176400)
        assertEquals("DoP · 载波 176.4 kHz / 24 位", outputResolution(dop))
        assertEquals("DoP · DSD64", dsdOutputStatus(dop))
        assertEquals("176.4 kHz / 24 位", requestedOutputResolution(dop.copy(requestedSampleRateHz = 176400,
            requestedSampleFormat = "int24")))
    }

    @Test fun outputStatusShowsActualPcmConversionDownrateAndReleasedState() {
        val d = AudioDiagnosticsUiModel(isDsd = true, dsdRate = 128, actualDsdRate = 64,
            dsdMode = "native", actualSampleRateHz = 2822400, dsdConversion = "downrate")
        assertEquals("Native DSD64 · 已降低倍率并重新调制", dsdOutputStatus(d))
        assertEquals("DSD → PCM · 按所选模式转换", dsdOutputStatus(d.copy(dsdMode = "pcm",
            dsdConversionReason = "dsd_output_mode_pcm", dsdConversion = "pcm-fallback")))
        assertTrue(dsdOutputStatus(d.copy(actualSampleRateHz = 0)).contains("未建立"))
    }
}
