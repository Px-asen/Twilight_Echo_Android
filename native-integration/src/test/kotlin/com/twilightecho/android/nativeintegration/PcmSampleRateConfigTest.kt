package com.twilightecho.android.nativeintegration

import com.twilightecho.android.coreclient.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PcmSampleRateConfigTest {
    private val json = DefaultCoreClient.DefaultJson
    private fun output(config: CoreEqConfigView, rates: List<Int> = emptyList()) =
        json.parseToJsonElement(nativeOutputConfig(config, rates)).jsonObject

    @Test fun oldAutoAndFixedRequestsDoNotRequireDeviceAdmission() {
        val old = json.decodeFromString<CoreEqConfigView>("""{"bitPerfect":true}""")
        assertFalse(old.dsp.sampleRateFamilySync)
        assertTrue(old.dsp.allowedSampleRates.isEmpty())
        output(old, listOf(48000))
        output(old.copy(dsp = old.dsp.copy(outputStage = CoreDspOutputStage(targetSampleRate = 96000))), listOf(48000))
    }

    @Test fun familyAndAllowedSetTravelWithTheGraphButDeviceRatesStayLive() {
        val config = CoreEqConfigView(bitPerfect = true, dsp = CoreDspConfig(sampleRateFamilySync = true,
            allowedSampleRates = listOf(96000, 44100)))
        val output = output(config, listOf(96000, 44100, 44100, 768000))
        assertEquals(listOf(44100, 96000), output.getValue("deviceSampleRates").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf(44100, 96000), output.getValue("allowedSampleRates").jsonArray.map { it.jsonPrimitive.int })
        assertTrue(output.getValue("sampleRateFamilySync").jsonPrimitive.boolean)
        val state = json.parseToJsonElement(nativeDspState(config, json, 1, output.toString())).jsonObject
        assertEquals(output, state.getValue("outputConfig"))
        assertFalse("deviceSampleRates" in json.encodeToJsonElement(config).jsonObject.getValue("dsp").jsonObject)
        output(config) // No declaration still permits playback.
    }

    @Test fun incompatibleRequestsAreRejectedBeforeApplyingAnything() {
        val family = CoreEqConfigView(bitPerfect = true, dsp = CoreDspConfig(sampleRateFamilySync = true))
        listOf(family.copy(bitPerfect = false),
            family.copy(dsp = family.dsp.copy(outputStage = CoreDspOutputStage(targetSampleRate = 96000))),
            family.copy(dsp = family.dsp.copy(allowedSampleRates = listOf(48000, 48000))),
            family.copy(dsp = family.dsp.copy(allowedSampleRates = listOf(768000))),
            family.copy(dsp = family.dsp.copy(allowedSampleRates = List(33) { 8000 + it }))).forEach {
            assertThrows(IllegalArgumentException::class.java) { output(it) }
        }
        val fixed = CoreEqConfigView(bitPerfect = true, dsp = CoreDspConfig(allowedSampleRates = listOf(48000, 96000),
            outputStage = CoreDspOutputStage(targetSampleRate = 96000)))
        assertThrows(IllegalArgumentException::class.java) { output(fixed, listOf(48000)) }
        assertThrows(IllegalArgumentException::class.java) { output(fixed.copy(dsp = fixed.dsp.copy(allowedSampleRates = listOf(48000)))) }
        assertThrows(IllegalArgumentException::class.java) { output(family.copy(dsp = family.dsp.copy(allowedSampleRates = listOf(44100))), listOf(48000)) }
    }
}
