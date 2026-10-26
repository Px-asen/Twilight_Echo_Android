package com.twilightecho.android.playbackhost

import android.content.Context
import com.twilightecho.android.adapters.AndroidAudioDeviceAdapter
import com.twilightecho.android.adapters.AndroidClockAdapter
import com.twilightecho.android.adapters.AndroidMediaStoreScannerAdapter
import com.twilightecho.android.adapters.AndroidNetworkAdapter
import com.twilightecho.android.adapters.AndroidResourceAccessAdapter
import com.twilightecho.android.adapters.AndroidSessionStoreAdapter
import com.twilightecho.android.coreclient.CoreClient
import com.twilightecho.android.coreclient.CoreClientIdentity
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.CoreResult
import com.twilightecho.android.coreclient.CoreSnapshot
import com.twilightecho.android.coreclient.CoreTypedCommand
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.RemoteCoreCredentials
import com.twilightecho.android.coreclient.RemoteNetworkCoreTransport
import com.twilightecho.android.coreclient.SwitchableCoreTransport
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.CoreHostPorts
import com.twilightecho.android.runtimehost.CoreRuntimeThreadDispatcher
import com.twilightecho.android.runtimehost.QuickJsRuntimeHost
import com.twilightecho.android.runtimehost.ResourceAccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    val audioDeviceAdapter = AndroidAudioDeviceAdapter(
        context = appContext,
        onPhysicalDisconnectBrake = {
            handlePlatformInterruption(PlatformInterruptionReason.AUDIO_BECOMING_NOISY)
        }
    )
    val nativeAudioEngine = NativeAudioEngineAdapter(
        resourceAccess = resourceAccessAdapter,
        appContext = appContext
    )

    private val hostPorts = CoreHostPorts(
        clock = clockAdapter,
        scheduler = clockAdapter,
        sessionStore = sessionStoreAdapter,
        resourceAccess = resourceAccessAdapter,
        audioEngine = nativeAudioEngine,
        networkClient = networkAdapter,
        audioDevice = audioDeviceAdapter
    )

    val runtimeHost = QuickJsRuntimeHost(
        appContext = appContext,
        ports = hostPorts,
        runtimeDispatcher = runtimeDispatcher
    )

    val switchableTransport = SwitchableCoreTransport(
        localTransport = runtimeHost,
        scope = hostScope
    )

    private val activeRemoteTransport = AtomicReference<RemoteNetworkCoreTransport?>(null)

    /**
     * Current Roon-style operating mode ([CoreOperatingMode.StandaloneLocal] vs [CoreOperatingMode.RemoteDesktopCore]).
     */
    val operatingMode: StateFlow<CoreOperatingMode> = switchableTransport.activeMode

    val primaryCoreClient: CoreClient = DefaultCoreClient(
        identity = CoreClientIdentity.androidPrimaryClient(),
        transport = switchableTransport,
        scope = hostScope
    )

    val mediaSessionCoreClient: CoreClient = DefaultCoreClient(
        identity = CoreClientIdentity.androidSystemMediaClient(),
        transport = switchableTransport,
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
            transport = switchableTransport,
            scope = hostScope
        )
    }

    /**
     * Connects to a PC Desktop Core over LAN (Roon-style Remote Controller mode).
     *
     * 1. Pairs via 6-digit [pin] (or reuses saved token for the same [hostOrUrl]).
     * 2. Pauses local phone playback and releases local AudioFocus so phone acts as a pure Remote Controller.
     * 3. Hot-swaps [switchableTransport] to [RemoteNetworkCoreTransport] and reconciles [primaryCoreClient] + [mediaSessionCoreClient].
     */
    suspend fun connectToDesktopCore(
        hostOrUrl: String,
        pin: String? = null
    ): Result<CoreSnapshot> {
        ensureStarted()
        return runCatching {
            val normalizedUrl = RemoteNetworkCoreTransport.normalizeBaseUrl(hostOrUrl)
            val savedCreds = loadSavedRemoteCredentials()
            val initialToken = if (pin.isNullOrBlank() && savedCreds?.baseUrl == normalizedUrl) {
                savedCreds.bearerToken
            } else {
                null
            }

            val remoteTransport = RemoteNetworkCoreTransport(
                rawBaseUrl = normalizedUrl,
                bearerToken = initialToken,
                scope = hostScope
            )

            try {
                if (!pin.isNullOrBlank()) {
                    val token = remoteTransport.pairWithPin(pin.trim()).getOrThrow()
                    saveRemoteCredentials(RemoteCoreCredentials(baseUrl = normalizedUrl, bearerToken = token))
                } else if (remoteTransport.bearerToken.isNullOrBlank()) {
                    val status = remoteTransport.checkStatus().getOrThrow()
                    if (status.requiresPairing) {
                        throw IllegalStateException("Desktop Core requires a 6-digit PIN to pair")
                    }
                }

                // Pause local phone audio engine before switching into Remote Controller mode
                nativeAudioEngine.emergencyBrakePause()
                audioFocusCoordinator.abandonPlaybackFocus()

                activeRemoteTransport.getAndSet(remoteTransport)?.close()
                remoteTransport.startEventStream()

                switchableTransport.switchTo(
                    targetTransport = remoteTransport,
                    mode = CoreOperatingMode.RemoteDesktopCore(
                        baseUrl = normalizedUrl,
                        hostLabel = normalizedUrl.removePrefix("http://").removePrefix("https://"),
                        connected = true
                    )
                )

                val reconciled = primaryCoreClient.reconcileState()
                mediaSessionCoreClient.reconcileState()

                when (reconciled) {
                    is CoreResult.Success -> reconciled.value
                    is CoreResult.Failure -> {
                        // Roll back to local standalone Core if initial snapshot handshake failed
                        switchToStandaloneLocalCore()
                        throw IllegalStateException(
                            "Failed to synchronize with Desktop Core (${reconciled.error.code}): ${reconciled.error.message}"
                        )
                    }
                }
            } catch (err: Throwable) {
                remoteTransport.close()
                throw err
            }
        }
    }

    /**
     * Switches back from Desktop Remote Controller mode to the phone's embedded Standalone Music Core.
     */
    suspend fun switchToStandaloneLocalCore(): Result<CoreSnapshot> {
        ensureStarted()
        return runCatching {
            activeRemoteTransport.getAndSet(null)?.close()
            switchableTransport.switchToLocal()
            val reconciled = primaryCoreClient.reconcileState()
            mediaSessionCoreClient.reconcileState()
            reconciled.getOrNull()
                ?: throw IllegalStateException("Local Standalone Core failed to reconcile snapshot")
        }
    }

    fun getSavedRemoteDesktopUrl(): String? = loadSavedRemoteCredentials()?.baseUrl

    private val remoteCredentialsFile: File
        get() = File(File(appContext.filesDir, "twilight_core_state").apply { mkdirs() }, "remote_desktop_core.json")

    private fun loadSavedRemoteCredentials(): RemoteCoreCredentials? {
        return runCatching {
            val file = remoteCredentialsFile
            if (!file.exists()) return null
            DefaultCoreClient.DefaultJson.decodeFromString<RemoteCoreCredentials>(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun saveRemoteCredentials(credentials: RemoteCoreCredentials) {
        runCatching {
            val payloadJson = DefaultCoreClient.DefaultJson.encodeToString(credentials)
            remoteCredentialsFile.writeText(payloadJson, Charsets.UTF_8)
        }
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
     * Scans an authorized SAF directory tree (`content://...`) and synchronizes discovered audio tracks
     * into Music Core, propagating `PERMISSION_EXPIRED` if the folder grant was revoked (Section 8.3).
     */
    suspend fun scanSafDirectoryAndSync(treeUriString: String): Result<Int> {
        ensureStarted()
        return when (val res = resourceAccessAdapter.scanSafDirectoryTree(treeUriString)) {
            is ResourceAccessResult.Ok -> runCatching {
                primaryCoreClient.syncLocalTracks(res.value).getOrThrow()
                res.value.size
            }
            is ResourceAccessResult.Error -> Result.failure(
                IllegalStateException("${res.code.name}: ${res.message}")
            )
        }
    }

    /**
     * Requests Android AudioFocus prior to user-initiated playback when in [CoreOperatingMode.StandaloneLocal]
     * mode, or directly forwards `Play` to the PC Desktop Core when in [CoreOperatingMode.RemoteDesktopCore] mode.
     */
    suspend fun requestFocusAndPlay(positionSeconds: Double? = null) {
        ensureStarted()
        if (operatingMode.value is CoreOperatingMode.RemoteDesktopCore) {
            primaryCoreClient.executeCommand(CoreTypedCommand.Play(positionSeconds))
            return
        }
        if (audioFocusCoordinator.requestPlaybackFocus()) {
            primaryCoreClient.executeCommand(CoreTypedCommand.Play(positionSeconds))
        }
    }

    /**
     * Platform Safety Brake (Section 5.4):
     * 1. Immediately brakes native audio output (`emergencyBrakePause()`) when required in local standalone mode.
     * 2. Reports the interruption reason and transient flag back to the local Music Core.
     */
    private fun handlePlatformInterruption(reason: PlatformInterruptionReason) {
        if (operatingMode.value is CoreOperatingMode.RemoteDesktopCore) return
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
        if (!shouldResumeTransient || operatingMode.value is CoreOperatingMode.RemoteDesktopCore) return
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
            activeRemoteTransport.getAndSet(null)?.close()
            audioFocusCoordinator.close()
            audioDeviceAdapter.close()
            runtimeHost.close()
            nativeAudioEngine.close()
            resourceAccessAdapter.close()
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
