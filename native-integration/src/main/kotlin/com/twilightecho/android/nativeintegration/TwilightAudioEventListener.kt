package com.twilightecho.android.nativeintegration

/**
 * Callback interface invoked by `twilight_audio_jni.cpp` when `TAE_SetEventCallback` fires.
 *
 * Real-time safety rule (Section 13.5):
 * Implementations MUST enqueue the event onto a non-RT control queue immediately and return
 * without blocking or synchronously invoking JS/UI work.
 */
fun interface TwilightAudioEventListener {
    fun onEvent(eventType: String, payloadJson: String)
}
