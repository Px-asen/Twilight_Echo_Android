package com.twilightecho.android.nativeintegration

import com.twilightecho.android.runtimehost.AudioSnapshot

internal fun AudioSnapshot.withoutOutputFacts() = copy(
    actualSampleRate = null, actualBitDepth = null, actualChannels = null, actualSampleFormat = null,
    usbPcmPreferenceAccepted = false,
    usbPcmFormatMatched = false,
    requestedSampleRate = null, requestedSampleFormat = null,
    hardwareSampleRate = null, hardwareBitDepth = null, hardwareChannels = null, hardwareSampleFormat = null,
    actualDeviceId = null, actualDeviceName = null, exclusive = false,
    outputPerfect = false, perfectReason = null, perfectReasonCode = null,
    latencyMs = 0.0, bufferSizeFrames = 0, dspActive = false, eqActive = false,
    gaplessActive = false, preloadReady = false, gaplessBlockedReason = null,
    crossfadeActive = false, crossfadeSeconds = 0.0, crossfadeMixActive = false,
    crossfadeEffectiveSeconds = 0.0, crossfadeCurve = null, crossfadeBlockedReason = null
)
