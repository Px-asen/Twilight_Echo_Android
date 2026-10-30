package com.twilightecho.android.nativeintegration

/**
 * JNI bridge matching `sdk/android/jni/twilight_audio_jni.cpp` and `twilight_audio_engine.h`.
 *
 * Package and method signatures match:
 * `Java_com_twilightecho_android_nativeintegration_TwilightAudioEngineJni_*`
 */
class TwilightAudioEngineJni {

    val isNativeLibraryLoaded: Boolean = Companion.nativeLoaded

    external fun nativeCreateEngine(): Long
    external fun nativeDestroyEngine(handle: Long)
    external fun nativeSetEventListener(handle: Long, listener: TwilightAudioEventListener?)
    external fun nativeSetStateEventsEnabled(handle: Long, enabled: Boolean): Int
    external fun nativeSetUsbPcmPolicy(handle: Long, policy: AndroidUsbPcmPolicy): Int
    external fun nativeSetInputProvider(handle: Long, provider: NativeAudioInputProvider): Int

    external fun nativePlay(handle: Long, source: String, startTimeSeconds: Double): Int
    external fun nativeLoad(handle: Long, source: String, startTimeSeconds: Double): Int
    external fun nativePause(handle: Long): Int
    external fun nativeSetPaused(handle: Long, paused: Boolean): Int
    external fun nativeStop(handle: Long): Int
    external fun nativeSeek(handle: Long, positionSeconds: Double): Int
    external fun nativeSetVolume(handle: Long, volume: Double): Int
    external fun nativeSetPlaybackRate(handle: Long, rate: Double): Int
    external fun nativeSetPlayMode(handle: Long, mode: String): Int

    external fun nativeLoadQueue(handle: Long, queueJson: String, startIndex: Int): Int
    external fun nativeSelectQueueIndex(handle: Long, index: Int): Int
    external fun nativeNext(handle: Long): Int
    external fun nativePrevious(handle: Long): Int

    external fun nativeSetOutputConfig(handle: Long, outputConfigJson: String): Int
    external fun nativeSetDspConfig(handle: Long, dspConfigJson: String): Int
    external fun nativeApplyDspState(handle: Long, revision: Long, stateJson: String): Int
    external fun nativeGetDspGraphStatusJson(handle: Long): String
    external fun nativeGetDspConfigJson(handle: Long): String
    external fun nativeSetLoopRange(handle: Long, startSeconds: Double, endSeconds: Double): Int
    external fun nativeSetVisualizationDemand(handle: Long, enabled: Boolean): Int
    external fun nativeGetVisualizationDataJson(handle: Long, optionsJson: String): String
    external fun nativeSetOutputDevice(handle: Long, deviceId: String): Int
    external fun nativeSetOutputBackend(handle: Long, backendId: String): Int

    external fun nativeGetPlaybackInfoJson(handle: Long): String
    external fun nativeGetQueueStateJson(handle: Long): String
    external fun nativeGetEngineCapabilitiesJson(handle: Long): String
    external fun nativeGetLastErrorJson(handle: Long): String
    external fun nativeGetVersion(): String
    external fun nativeSetAutoMixMode(handle: Long, mode: String): Int
    external fun nativeSelectAutoMix(handle: Long, requestJson: String): String
    external fun nativePrepareAutoMix(handle: Long, requestJson: String): Int
    external fun nativeSubmitAutoMix(handle: Long, planId: Long): Int
    external fun nativeCancelAutoMix(handle: Long): Int
    external fun nativeCreateAutoMixCancel(timeoutMs: Int): Long
    external fun nativeCancelAutoMixAnalysis(token: Long)
    external fun nativeDestroyAutoMixCancel(token: Long)
    external fun nativeAnalyzeAutoMix(handle: Long, source: String, optionsJson: String, token: Long): String
    external fun nativeIdentifyAutoMixSource(handle: Long, source: String, token: Long): String

    companion object {
        const val TAE_RESULT_OK = 0
        const val TAE_RESULT_INVALID_ARGUMENT = 1
        const val TAE_RESULT_NOT_INITIALIZED = 2
        const val TAE_RESULT_BACKEND_UNAVAILABLE = 3
        const val TAE_RESULT_INTERNAL_ERROR = 4

        private val nativeLoaded: Boolean = runCatching {
            System.loadLibrary("twilight_audio_jni")
            true
        }.getOrElse {
            runCatching {
                System.loadLibrary("twilight_audio_engine")
                true
            }.getOrDefault(false)
        }
    }
}
