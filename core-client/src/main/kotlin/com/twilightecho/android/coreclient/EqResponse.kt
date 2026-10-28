package com.twilightecho.android.coreclient

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class EqResponsePoint(val frequencyHz: Double, val leftDb: Double, val rightDb: Double)

data class EqHeadroom(
    /** Largest boost of the running filters on either channel over 20 Hz–20 kHz, preamp excluded. */
    val peakBoostDb: Double,
    val peakFrequencyHz: Double,
    /** Headroom stage, preamp and peak boost together; above zero, full-scale material can clip. */
    val outputPeakDb: Double,
    val clippingRisk: Boolean,
    /** The preamp that cancels the boost left after the headroom stage, within ±12 dB. */
    val recommendedPreampDb: Double,
    /** Headroom stage gain to use with a −12 dB preamp when the preamp alone cannot cancel the boost. */
    val recommendedStageDb: Double?
)

/**
 * Magnitude response of the RBJ biquads in the shared `ParametricEqProcessor.cpp`, including its
 * frequency and Q clamps, evaluated at a 48 kHz reference rate.
 */
object EqResponse {
    const val REFERENCE_SAMPLE_RATE = 48000
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20000.0
    /** AutoEQ rounds its preamp to 0.1 dB; smaller overshoots are not reported. */
    private const val CLIP_TOLERANCE_DB = 0.1
    private const val GRID_POINTS = 512
    /** `DspChain.cpp` clamps the headroom stage to −30..0 dB. */
    private const val MIN_STAGE_DB = -30.0

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double, val mask: Long) {
        fun db(hz: Double, sampleRate: Int): Double {
            val w = 2.0 * PI * hz / sampleRate
            val c1 = cos(w); val s1 = sin(w); val c2 = cos(2.0 * w); val s2 = sin(2.0 * w)
            val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
            val dr = 1.0 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
            return 10.0 * log10(max(nr * nr + ni * ni, 1.0e-12) / max(dr * dr + di * di, 1.0e-12))
        }
    }

    /** Filter response per channel without the preamp; empty filters give a flat line. */
    fun curve(config: CoreEqConfigView, points: Int = 96, sampleRate: Int = REFERENCE_SAMPLE_RATE): List<EqResponsePoint> {
        val filters = biquads(config, sampleRate)
        return (0 until points).map { point(filters, logFrequency(it, points), sampleRate) }
    }

    fun pointAt(config: CoreEqConfigView, hz: Double, sampleRate: Int = REFERENCE_SAMPLE_RATE): EqResponsePoint =
        point(biquads(config, sampleRate), hz, sampleRate)

    fun headroom(config: CoreEqConfigView, sampleRate: Int = REFERENCE_SAMPLE_RATE): EqHeadroom {
        val dsp = config.dsp
        val eqRunning = dsp.enabled && config.enabled
        val stageDb = if (dsp.enabled && dsp.outputStage.headroomEnabled) dsp.outputStage.headroomGainDb else 0.0
        val filters = if (eqRunning) biquads(config, sampleRate) else emptyList()
        var peakDb = 0.0
        var peakHz = 1000.0
        if (filters.isNotEmpty()) {
            fun peakAt(hz: Double) = max(channelDb(filters, 0, hz, sampleRate), channelDb(filters, 1, hz, sampleRate))
            val grid = (0 until GRID_POINTS).map { logFrequency(it, GRID_POINTS) }
            val centers = config.effectiveEqBands().map { it.frequency }.filter { it in MIN_HZ..MAX_HZ }
            var best = -1
            peakDb = Double.NEGATIVE_INFINITY
            grid.forEachIndexed { index, hz -> peakAt(hz).let { if (it > peakDb) { peakDb = it; peakHz = hz; best = index } } }
            centers.forEach { hz -> peakAt(hz).let { if (it > peakDb) { peakDb = it; peakHz = hz; best = -1 } } }
            if (best >= 0) {
                // Overlapping bands can peak between grid points; refine on log frequency.
                var lo = ln(grid[max(0, best - 1)])
                var hi = ln(grid[minOf(grid.lastIndex, best + 1)])
                repeat(48) {
                    val m1 = lo + (hi - lo) / 3.0
                    val m2 = hi - (hi - lo) / 3.0
                    if (peakAt(exp(m1)) < peakAt(exp(m2))) lo = m1 else hi = m2
                }
                val hz = exp((lo + hi) / 2.0)
                peakAt(hz).let { if (it > peakDb) { peakDb = it; peakHz = hz } }
            }
            peakDb = max(0.0, peakDb)
        }
        val preampDb = if (eqRunning) config.preampDb else 0.0
        val outputPeakDb = stageDb + preampDb + peakDb
        val excessDb = max(0.0, peakDb + stageDb)
        return EqHeadroom(
            peakBoostDb = peakDb,
            peakFrequencyHz = peakHz,
            outputPeakDb = outputPeakDb,
            clippingRisk = outputPeakDb > CLIP_TOLERANCE_DB,
            recommendedPreampDb = (-roundUp(excessDb)).coerceIn(-EqLimits.PREAMP_DB, 0.0),
            recommendedStageDb = if (excessDb > EqLimits.PREAMP_DB + CLIP_TOLERANCE_DB) {
                (-roundUp(peakDb - EqLimits.PREAMP_DB)).coerceIn(MIN_STAGE_DB, 0.0)
            } else null
        )
    }

    /** Rounds a needed attenuation up to the 0.1 dB the controls show. */
    private fun roundUp(db: Double): Double = if (db <= 0.0) 0.0 else ceil(db * 10.0 - 1.0e-9) / 10.0

    private fun logFrequency(index: Int, points: Int): Double =
        MIN_HZ * (MAX_HZ / MIN_HZ).pow(index.toDouble() / max(1, points - 1))

    private fun point(filters: List<Biquad>, hz: Double, sampleRate: Int) =
        EqResponsePoint(hz, channelDb(filters, 0, hz, sampleRate), channelDb(filters, 1, hz, sampleRate))

    private fun channelDb(filters: List<Biquad>, channel: Int, hz: Double, sampleRate: Int): Double =
        filters.sumOf { if (it.mask and (1L shl channel) != 0L) it.db(hz, sampleRate) else 0.0 }

    private fun biquads(config: CoreEqConfigView, sampleRate: Int): List<Biquad> {
        val graphic = config.dsp.eqMode != "parametric"
        return config.effectiveEqBands().mapNotNull { band ->
            val type = if (graphic) "peak" else band.filterType
            if (!band.enabled || (type in EqLimits.GAIN_FILTER_TYPES && abs(band.gain) <= 0.0001)) null
            else biquad(type, band, sampleRate)
        }
    }

    private fun biquad(type: String, band: CoreParametricEqBand, sampleRate: Int): Biquad? {
        val frequency = band.frequency.coerceIn(10.0, max(1.0, sampleRate * 0.5) * 0.98)
        val w0 = 2.0 * PI * frequency / sampleRate
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = sinW0 / (2.0 * band.q.coerceIn(0.1, 20.0))
        val a = 10.0.pow(band.gain / 40.0)
        val s = 2.0 * sqrt(a) * alpha
        val c = when (type) {
            "peak" -> doubleArrayOf(1 + alpha * a, -2 * cosW0, 1 - alpha * a, 1 + alpha / a, -2 * cosW0, 1 - alpha / a)
            "lowShelf" -> doubleArrayOf(
                a * ((a + 1) - (a - 1) * cosW0 + s), 2 * a * ((a - 1) - (a + 1) * cosW0), a * ((a + 1) - (a - 1) * cosW0 - s),
                (a + 1) + (a - 1) * cosW0 + s, -2 * ((a - 1) + (a + 1) * cosW0), (a + 1) + (a - 1) * cosW0 - s
            )
            "highShelf" -> doubleArrayOf(
                a * ((a + 1) + (a - 1) * cosW0 + s), -2 * a * ((a - 1) + (a + 1) * cosW0), a * ((a + 1) + (a - 1) * cosW0 - s),
                (a + 1) - (a - 1) * cosW0 + s, 2 * ((a - 1) - (a + 1) * cosW0), (a + 1) - (a - 1) * cosW0 - s
            )
            "lowPass" -> doubleArrayOf((1 - cosW0) / 2, 1 - cosW0, (1 - cosW0) / 2, 1 + alpha, -2 * cosW0, 1 - alpha)
            "highPass" -> doubleArrayOf((1 + cosW0) / 2, -(1 + cosW0), (1 + cosW0) / 2, 1 + alpha, -2 * cosW0, 1 - alpha)
            "bandPass" -> doubleArrayOf(alpha, 0.0, -alpha, 1 + alpha, -2 * cosW0, 1 - alpha)
            "allPass" -> doubleArrayOf(1 - alpha, -2 * cosW0, 1 + alpha, 1 + alpha, -2 * cosW0, 1 - alpha)
            "notch" -> doubleArrayOf(1.0, -2 * cosW0, 1.0, 1 + alpha, -2 * cosW0, 1 - alpha)
            else -> return null
        }
        if (abs(c[3]) < 1.0e-12) return null
        return Biquad(c[0] / c[3], c[1] / c[3], c[2] / c[3], c[4] / c[3], c[5] / c[3], band.channelMask)
    }
}
