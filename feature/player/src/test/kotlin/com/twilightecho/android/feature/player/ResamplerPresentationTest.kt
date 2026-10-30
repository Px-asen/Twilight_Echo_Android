package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreDspOutputStage
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ResamplerPresentationTest {
    private fun status(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun savedRequestAndAvailabilityCannotClaimAnActiveFilter() {
        assertEquals("播放后显示实际重采样状态", resamplerStatusText(status("""{"resamplerPhase":"minimum","resamplerEngine":"soxr"}""")))
        assertEquals("播放后显示实际重采样状态", resamplerStatusText(status("""{"effectiveResampler":"swr","resampled":false}""")))
    }
    @Test fun observedFilterAndEqualRateHaveDifferentDescriptions() {
        assertEquals("实际：SWR 极高 · 线性相位 · 平滑滚降", resamplerStatusText(status("""{"effectiveResampler":"swr","effectiveResamplerQuality":"ultra","effectiveResamplerPhase":"linear-smooth","resampled":true}""")))
        assertEquals("本引擎未重采样", resamplerStatusText(status("""{"effectiveResampler":"none","resampled":false}""")))
        assertTrue(resamplerStatusText(status("""{"effectiveResampler":"swr","resampled":true}""")).endsWith("相位待确认"))
    }
    @Test fun phaseAndRolloffChangeIndependentlyWithoutLosingSavedMinimum() {
        for (phase in listOf("minimum", "minimum-smooth")) {
            val stage = CoreDspOutputStage(resamplerPhase = phase)
            assertEquals("minimum", resamplerPhaseChoice(stage.resamplerPhase))
            assertEquals(phase, stage.resamplerPhase)
        }
        assertEquals("linear-smooth", resamplerRolloff("minimum-smooth"))
        assertEquals("linear", resamplerRolloff("minimum"))
        assertEquals("minimum-smooth", resamplerWithRolloff("minimum", "linear-smooth"))
        assertEquals("minimum", resamplerWithRolloff("minimum-smooth", "linear"))
        assertEquals("linear-smooth", resamplerWithPhase("minimum-smooth", "linear"))
        assertEquals("minimum", resamplerWithPhase("linear", "minimum"))
        assertEquals("实际：SoX 极高 · 最小相位 · 平滑滚降", resamplerStatusText(status("""{"effectiveResampler":"soxr","effectiveResamplerQuality":"ultra","effectiveResamplerPhase":"minimum-smooth","resampled":true}""")))
    }
}
