package com.twilightecho.android.nativeintegration

/** User volume is persistent; focus attenuation is temporary. Owned by the control dispatcher. */
internal class PlaybackGain {
    var volume: Double = 1.0
        set(value) { field = value.coerceIn(0.0, 1.0) }
    var ducked: Boolean = false
    val effective: Double get() = volume * if (ducked) 0.25 else 1.0
}
