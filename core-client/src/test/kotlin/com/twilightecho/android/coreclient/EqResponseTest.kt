package com.twilightecho.android.coreclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EqResponseTest {
    private fun parametric(vararg bands: CoreParametricEqBand, preampDb: Double = 0.0, stage: CoreDspOutputStage = CoreDspOutputStage()) =
        CoreEqConfigView(
            enabled = true,
            preampDb = preampDb,
            dsp = CoreDspConfig(eqMode = "parametric", parametricBands = bands.toList(), outputStage = stage)
        )

    @Test
    fun peakReachesItsGainAtTheCenterAndSetsTheRecommendedPreamp() {
        val config = parametric(CoreParametricEqBand(1000.0, 6.0, 1.0))
        val headroom = EqResponse.headroom(config)

        assertEquals(6.0, headroom.peakBoostDb, 1.0e-9)
        assertEquals(1000.0, headroom.peakFrequencyHz, 1.0e-9)
        assertTrue(headroom.clippingRisk)
        assertEquals(-6.0, headroom.recommendedPreampDb, 0.0)
        assertFalse(EqResponse.headroom(config.copy(preampDb = -6.0)).clippingRisk)
        // AutoEQ rounds its preamp to 0.1 dB; that rounding is not a clipping warning.
        assertFalse(EqResponse.headroom(config.copy(preampDb = -5.95)).clippingRisk)
    }

    @Test
    fun overlappingPeaksAreFoundBetweenGridPoints() {
        val config = parametric(CoreParametricEqBand(1000.0, 6.0, 4.0), CoreParametricEqBand(1060.0, 6.0, 4.0))
        val headroom = EqResponse.headroom(config)
        val dense = (0..20000).maxOf { EqResponse.pointAt(config, 900.0 + it * 0.01).leftDb }

        assertEquals(dense, headroom.peakBoostDb, 1.0e-4)
        assertTrue(headroom.peakFrequencyHz in 1000.0..1060.0)
        assertEquals(-(Math.ceil(dense * 10.0) / 10.0), headroom.recommendedPreampDb, 0.0)
    }

    @Test
    fun curveFollowsChannelMasksAndCutsDoNotNeedPreamp() {
        val config = parametric(
            CoreParametricEqBand(100.0, 4.0, 0.7, "lowShelf", channelMask = 1L),
            CoreParametricEqBand(3000.0, -8.0, 2.0, channelMask = 2L)
        )
        val curve = EqResponse.curve(config, points = 64)

        assertEquals(64, curve.size)
        assertEquals(20.0, curve.first().frequencyHz, 1.0e-9)
        assertEquals(20000.0, curve.last().frequencyHz, 1.0e-6)
        assertTrue(curve.first().leftDb > 3.5)
        assertEquals(0.0, curve.first().rightDb, 0.01)
        assertTrue(curve.minOf { it.rightDb } < -7.5)
        assertTrue(curve.all { it.leftDb >= -0.01 })

        val cutOnly = EqResponse.headroom(parametric(CoreParametricEqBand(3000.0, -8.0, 2.0)))
        assertEquals(0.0, cutOnly.peakBoostDb, 0.0)
        assertEquals(0.0, cutOnly.recommendedPreampDb, 0.0)
        assertFalse(cutOnly.clippingRisk)
    }

    @Test
    fun graphicSlidersUseTheEnginePeaks() {
        val config = CoreEqConfigView(enabled = true, bandsDb = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0))
        val headroom = EqResponse.headroom(config)

        assertEquals(3.0, headroom.peakBoostDb, 1.0e-9)
        assertEquals(1000.0, headroom.peakFrequencyHz, 1.0e-9)
    }

    @Test
    fun bypassedStagesAndTheHeadroomStageChangeTheBudget() {
        val boost = parametric(CoreParametricEqBand(1000.0, 2.0, 1.0))
        assertFalse(EqResponse.headroom(boost.copy(enabled = false)).clippingRisk)
        assertFalse(EqResponse.headroom(boost.copy(dsp = boost.dsp.copy(enabled = false))).clippingRisk)

        val withStage = boost.copy(dsp = boost.dsp.copy(outputStage = CoreDspOutputStage(headroomEnabled = true, headroomGainDb = -3.0)))
        val headroom = EqResponse.headroom(withStage)
        assertEquals(-1.0, headroom.outputPeakDb, 1.0e-9)
        assertFalse(headroom.clippingRisk)
        assertEquals(0.0, headroom.recommendedPreampDb, 0.0)
    }

    @Test
    fun boostsBeyondThePreampRangeAskForTheHeadroomStage() {
        val headroom = EqResponse.headroom(
            parametric(CoreParametricEqBand(60.0, 12.0, 0.7, "lowShelf"), CoreParametricEqBand(60.0, 12.0, 1.0))
        )

        val stage = headroom.recommendedStageDb!!
        assertTrue(headroom.peakBoostDb > 12.0)
        assertEquals(-12.0, headroom.recommendedPreampDb, 0.0)
        assertTrue(stage + -12.0 + headroom.peakBoostDb in -0.1..1.0e-9)
        assertEquals(null, EqResponse.headroom(parametric(CoreParametricEqBand(1000.0, 6.0, 1.0))).recommendedStageDb)
    }
}
