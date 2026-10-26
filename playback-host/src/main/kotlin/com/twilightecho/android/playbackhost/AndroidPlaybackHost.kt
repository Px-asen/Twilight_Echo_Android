package com.twilightecho.android.playbackhost

import android.content.Context
import com.twilightecho.android.adapters.AndroidClockAdapter
import com.twilightecho.android.adapters.AndroidMediaStoreScannerAdapter
import com.twilightecho.android.adapters.AndroidNetworkAdapter
import com.twilightecho.android.adapters.AndroidResourceAccessAdapter
import com.twilightecho.android.adapters.AndroidSessionStoreAdapter
import com.twilightecho.android.coreclient.CoreClient
import com.twilightecho.android.coreclient.CoreClientIdentity
import com.twilightecho.android.coreclient.CoreTypedCommand
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.CoreHostPorts
import com.twilightecho.android.runtimehost.CoreRuntimeThreadDispatcher
import com.twilightecho.android.runtimehost.QuickJsRuntimeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton Android Playback Host (Architecture Plan Sections 5.4, 11.4, 11.6 + Phase 2).
 *
 * Owns the process-wide instances of:
 * - `CoreRuntimeThreadDispatcher` + `QuickJsRuntimeHost` (Music Core JS runtime)
 * - `NativeAudioEngineAdapter` (C++ Audio Core via JNI)
 * - `AndroidSessionStoreAdapter` (Atomic CAS session store)
 * - `AndroidResourceAccessAdapter` (MediaStore / SAF URI permission-aware resource access)
 * - `AndroidMediaStoreScannerAdapter` (Phase 2 local MediaStore audio scanner)
 * - `AudioFocusCoordinator` (System AudioFocus & `ACTION_AUDIO_BECOMING_NOISY`)
 *
 * Implements Section 5.4 **Platform Safety Brake**:
 * When `AUDIOFOCUS_LOSS`, `AUDIOFOCUS_LOSS_TRANSIENT`, or `ACTION_AUDIO_BECOMING_NOISY` occurs,
 * `AndroidPlaybackHost` immediately invokes `nativeAudioEngine.emergencyBrakePause()` so audio
 * stops without waiting for a round-trip through the JS queue, and then dispatches
 * `CoreTypedCommand.PlatformInterruption` to keep Music Core authoritative state synchronized.
 */
class AndroidPlaybackHost private constructor(
    context: Context
) : Closeable {

    private val appContext: Context = context.applicationContext
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val runtimeDispatcher = CoreRuntimeThreadDispatcher()
    val clockAdapter = AndroidClockAdapter(CoroutineScope(SupervisorJob() + runtimeDispatcher.dispatcher))
    val sessionStoreAdapter = AndroidSessionStoreAdapter(
        storageDirectory = File(appContext.filesDir, "twilight_core_state")
    )
    val resourceAccessAdapter = AndroidResourceAccessAdapter(appContext)
    val mediaStoreScanner = AndroidMediaStoreScannerAdapter(appContext)
    val networkAdapter = AndroidNetworkAdapter()
    val nativeAudioEngine = NativeAudioEngineAdapter()

    private val hostPorts = CoreHostPorts(
        clock = clockAdapter,
        scheduler = clockAdapter,
        sessionStore = sessionStoreAdapter,
        resourceAccess = resourceAccessAdapter,
        audioEngine = nativeAudioEngine
    )

    val runtimeHost = QuickJsRuntimeHost(
        appContext = appContext,
        ports = hostPorts,
        runtimeDispatcher = runtimeDispatcher
    )

    val primaryCoreClient: CoreClient = DefaultCoreClient(
        identity = CoreClientIdentity.androidPrimaryClient(),
        transport = runtimeHost,
        scope = hostScope
    )

    val mediaSessionCoreClient: CoreClient = DefaultCoreClient(
        identity = CoreClientIdentity.androidSystemMediaClient(),
        transport = runtimeHost,
        scope = hostScope
    )

    val audioFocusCoordinator = AudioFocusCoordinator(
        context = appContext,
        onInterruption = ::handlePlatformInterruption,
        onFocusRegained = ::handleAudioFocusRegained,
        onDuckVolumeChange = ::handleDuckVolumeChange
    )

    fun ensureStarted() {
        if (started.compareAndSet(false, true)) {
            hostScope.launch {
                runtimeHost.start()
                primaryCoreClient.reconcileState()
                mediaSessionCoreClient.reconcileState()
            }
        }
    }

    fun createClient(identity: CoreClientIdentity): CoreClient {
        ensureStarted()
        return DefaultCoreClient(
            identity = identity,
            transport = runtimeHost,
            scope = hostScope
        )
    }

    /**
     * Scans device local audio via `AndroidMediaStoreScannerAdapter` and synchronizes the
     * discovered tracks into Music Core via `primaryCoreClient.syncLocalTracks(scanned)`.
     */
    suspend fun scanAndSyncDeviceMusic(): Result<Int> {
        ensureStarted()
        return runCatching {
            val scanned = mediaStoreScanner.scanDeviceMusic()
            primaryCoreClient.syncLocalTracks(scanned).getOrThrow()
            scanned.size
        }
    }

    /**
     * Requests Android AudioFocus prior to user-initiated playback and forwards `Play` to Music Core.
     */
    suspend fun requestFocusAndPlay(positionSeconds: Double? = null) {
        ensureStarted()
        if (audioFocusCoordinator.requestPlaybackFocus()) {
            primaryCoreClient.executeCommand(CoreTypedCommand.Play(positionSeconds))
        }
    }

    /**
     * Platform Safety Brake (Section 5.4):
     * 1. Immediately brakes native audio output (`emergencyBrakePause()`) when required.
     * 2. Reports the interruption reason and transient flag back to Music Core.
     */
    private fun handlePlatformInterruption(reason: PlatformInterruptionReason) {
        if (reason.shouldImmediateBrakePause) {
            nativeAudioEngine.emergencyBrakePause()
        }
        hostScope.launch {
            primaryCoreClient.executeCommand(
                CoreTypedCommand.PlatformInterruption(
                    reason = reason.wireReason,
                    shouldPause = reason.shouldImmediateBrakePause,
                    transientCanResume = reason.isTransient
                )
            )
        }
    }

    private fun handleAudioFocusRegained(shouldResumeTransient: Boolean) {
        if (!shouldResumeTransient) return
        hostScope.launch {
            // Check whether Music Core is still in a resumable paused state before resuming
            val currentState = primaryCoreClient.snapshot.value?.playback?.state
            if (currentState == "paused") {
                primaryCoreClient.executeCommand(CoreTypedCommand.Play(null))
            }
        }
    }

    private fun handleDuckVolumeChange(ducked: Boolean) {
        hostScope.launch {
            val targetVolume = if (ducked) 0.25 else 1.0
            nativeAudioEngine.setVolume(targetVolume)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            audioFocusCoordinator.close()
            runtimeHost.close()
            nativeAudioEngine.close()
            hostScope.cancel()
        }
    }

    companion object {
        @Volatile
        private var instance: AndroidPlaybackHost? = null

        fun getInstance(context: Context): AndroidPlaybackHost {
            return instance ?: synchronized(this) {
                instance ?: AndroidPlaybackHost(context.applicationContext).also { created ->
                    created.ensureStarted()
                    instance = created
                }
            }
        }
    }
}
