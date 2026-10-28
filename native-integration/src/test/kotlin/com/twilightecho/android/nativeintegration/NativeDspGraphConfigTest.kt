package com.twilightecho.android.nativeintegration

import com.twilightecho.android.coreclient.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeDspGraphConfigTest {
    private val json = DefaultCoreClient.DefaultJson
    private fun state(config: CoreEqConfigView) = json.parseToJsonElement(nativeDspState(config, json, 7L)).jsonObject

    @Test fun legacyGraphicEqAndAdvancedNodesShareOneAtomicGraph() {
        val config = CoreEqConfigView(enabled = true, bandsDb = listOf(6.0) + List(9) { 0.0 }, dsp = CoreDspConfig(
            nodes = listOf(CoreDspNode("crossfeed", "crossfeed", params = buildJsonObject { put("strength", 0.4) }),
                CoreDspNode("limit", "truePeakLimiter")), crossfadeSeconds = 4.5))
        val state = state(config)
        assertEquals(7L, state.getValue("revision").jsonPrimitive.long)
        val processing = state.getValue("processing").jsonObject
        assertEquals(4.5, processing.getValue("crossfadeSeconds").jsonPrimitive.double, 0.0)
        assertEquals("pcm", processing.getValue("dsdOutputMode").jsonPrimitive.content)
        val nodes = state.getValue("graph").jsonObject.getValue("nodes").jsonArray
        assertEquals(listOf("equalizer", "crossfeed", "truePeakLimiter"), nodes.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(6.0, nodes[0].jsonObject.getValue("params").jsonObject.getValue("bands").jsonArray[0].jsonObject.getValue("gain").jsonPrimitive.double, 0.0)
    }

    @Test fun parametricEqPreservesFilterShapeQAndChannelMask() {
        val config = CoreEqConfigView(enabled = true, dsp = CoreDspConfig(eqMode = "parametric", parametricBands = listOf(
            CoreParametricEqBand(80.0, -5.0, 0.7, "lowShelf", channelMask = 1))))
        val band = state(config).getValue("graph").jsonObject.getValue("nodes").jsonArray[0].jsonObject
            .getValue("params").jsonObject.getValue("bands").jsonArray.single().jsonObject
        assertEquals("lowShelf", band.getValue("filterType").jsonPrimitive.content)
        assertEquals(0.7, band.getValue("q").jsonPrimitive.double, 0.0)
        assertEquals(1, band.getValue("channelMask").jsonPrimitive.int)
    }

    @Test fun masterBypassPreservesSettingsAndDoesNotInventOutputPerfect() {
        val config = CoreEqConfigView(enabled = true, dsp = CoreDspConfig(enabled = false,
            nodes = listOf(CoreDspNode("cf", "crossfeed"))))
        val state = state(config)
        assertFalse(state.getValue("processing").jsonObject.getValue("dspEnabled").jsonPrimitive.boolean)
        assertTrue(state.getValue("graph").jsonObject.getValue("nodes").jsonArray[1].jsonObject.getValue("enabled").jsonPrimitive.boolean)
        assertFalse("outputPerfect" in state.toString())
    }

    @Test fun outputPolicyReallyReachesTheSharedOutputContract() {
        val config = CoreEqConfigView(bitPerfect = false, dsp = CoreDspConfig(bufferFrames = 1024, continuitySampleRate = 96000,
            outputStage = CoreDspOutputStage(targetSampleRate = 88200, resamplerQuality = "ultra", headroomEnabled = true)))
        val output = json.parseToJsonElement(nativeOutputConfig(config)).jsonObject
        assertEquals("continuity-first", output.getValue("playbackPolicy").jsonPrimitive.content)
        assertEquals(96000, output.getValue("continuitySampleRate").jsonPrimitive.int)
        assertEquals(1024, output.getValue("preferredBufferSize").jsonPrimitive.int)
        val stage = state(config).getValue("graph").jsonObject.getValue("outputStage").jsonObject
        assertEquals("fixed", stage.getValue("targetSampleRateMode").jsonPrimitive.content)
        assertEquals(88200, stage.getValue("targetSampleRate").jsonPrimitive.int)
    }

    @Test fun rejectsDuplicateProcessorsAndUnsupportedDesktopPlugins() {
        listOf(listOf(CoreDspNode("a", "crossfeed"), CoreDspNode("b", "crossfeed")),
            listOf(CoreDspNode("vst", "vst3Plugin"))).forEach { nodes ->
            assertThrows(IllegalArgumentException::class.java) { state(CoreEqConfigView(dsp = CoreDspConfig(nodes = nodes))) }
        }
    }
}
