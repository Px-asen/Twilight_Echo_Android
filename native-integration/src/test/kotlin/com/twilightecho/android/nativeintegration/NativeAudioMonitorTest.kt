package com.twilightecho.android.nativeintegration

import com.twilightecho.android.coreclient.DefaultCoreClient
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeAudioMonitorTest {
    @Test fun monitorRequestsTheExactBarPayloadConsumedByThePlayer() {
        val request = DefaultCoreClient.DefaultJson.parseToJsonElement(AUDIO_MONITOR_VISUALIZATION_OPTIONS).jsonObject
        assertEquals(48, request["visualizerBarCount"]!!.jsonPrimitive.int)
        assertEquals("peak", request["visualizerBarMode"]!!.jsonPrimitive.content)
        assertEquals(4096, request["spectrumPoints"]!!.jsonPrimitive.int)
        assertEquals(16, request["waveformPoints"]!!.jsonPrimitive.int)
        assertEquals(0, request["spectrogramFrames"]!!.jsonPrimitive.int)
        assertEquals(0, request["oscilloscopePoints"]!!.jsonPrimitive.int)
    }
}
