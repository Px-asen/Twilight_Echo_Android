package com.twilightecho.android.coreclient

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** PCM request bounds, not a claim about the active device or negotiated stream. */
object PcmSampleRates {
    const val MIN = 8000
    const val MAX = 768000
    val range = MIN..MAX
}

/** The node types and parameter names are the shared C++/desktop DSP graph contract. */
@Serializable
data class CoreDspNode(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    val params: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class CoreParametricEqBand(
    val frequency: Double = 1000.0,
    val gain: Double = 0.0,
    val q: Double = 1.0,
    val filterType: String = "peak",
    val enabled: Boolean = true,
    val channelMask: Long = 4294967295L
)

@Serializable
data class CoreDspOutputStage(
    val targetSampleRate: Int = 0,
    val resamplerQuality: String = "native",
    val resamplerPhase: String = "linear",
    val dither: String = "off",
    val headroomEnabled: Boolean = false,
    val headroomGainDb: Double = -3.0,
    val safetyClamp: Boolean = true
)

/** Extends the original audio.setEq contract; old EQ callers retain their fields and defaults. */
@Serializable
data class CoreDspConfig(
    val enabled: Boolean = true,
    val eqMode: String = "graphic",
    val parametricBands: List<CoreParametricEqBand> = listOf(CoreParametricEqBand()),
    val nodes: List<CoreDspNode> = emptyList(),
    val outputStage: CoreDspOutputStage = CoreDspOutputStage(),
    val crossfadeSeconds: Double = 0.0,
    val crossfadeCurve: String = "equal-power",
    val crossfadeContent: String = "conservative",
    val outputDeviceId: String = "auto",
    val exclusive: Boolean = false,
    val bufferFrames: Int = 0,
    val continuitySampleRate: Int = 48000,
    val playbackRate: Double = 1.0,
    val usbPcmPassthrough: Boolean = false,
    val sampleRateFamilySync: Boolean = false,
    /** Empty follows the existing request policy; nonempty constrains PCM requests, not system hardware. */
    val allowedSampleRates: List<Int> = emptyList()
)

@Serializable
data class CoreDspScene(val id: String, val name: String, val config: JsonObject)

@Serializable
data class CoreDspNodeStatus(
    val id: String,
    val type: String = "",
    val enabled: Boolean = false,
    val active: Boolean = false,
    val bypassed: Boolean = false,
    val bypassReason: String = "",
    val latencyFrames: Long = 0L
)

@Serializable
data class CoreDspMeter(
    val momentaryLufs: Double? = null,
    val integratedLufs: Double? = null,
    val truePeakDb: Double? = null,
    val correlation: Double = 0.0,
    val clipCount: Long = 0L
)

@Serializable
data class CoreDspStatus(
    val revision: Long = 0L,
    val nodes: List<CoreDspNodeStatus> = emptyList(),
    val meter: CoreDspMeter = CoreDspMeter(),
    val outputStage: JsonObject = JsonObject(emptyMap())
)
