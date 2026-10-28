package com.twilightecho.android.playbackhost

import android.content.Context
import com.twilightecho.android.adapters.AndroidAudioDeviceAdapter
import com.twilightecho.android.adapters.AndroidClockAdapter
import com.twilightecho.android.adapters.AndroidMediaStoreScannerAdapter
import com.twilightecho.android.adapters.AndroidMusicFolderScanner
import com.twilightecho.android.adapters.LibraryScanSummary
import com.twilightecho.android.adapters.MusicFolder
import com.twilightecho.android.adapters.AndroidNetworkAdapter
import com.twilightecho.android.adapters.AndroidResourceAccessAdapter
import com.twilightecho.android.adapters.AndroidSessionStoreAdapter
import com.twilightecho.android.coreclient.CoreClient
import com.twilightecho.android.coreclient.CoreClientIdentity
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.CoreResult
import com.twilightecho.android.coreclient.CoreSnapshot
import com.twilightecho.android.coreclient.DesktopCertificateMismatchException
import com.twilightecho.android.coreclient.CoreTypedCommand
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.RemoteCoreCredentials
import com.twilightecho.android.coreclient.RemoteNetworkCoreTransport
import com.twilightecho.android.coreclient.DesktopCertificatePin
import com.twilightecho.android.coreclient.SwitchableCoreTransport
import com.twilightecho.android.coreclient.DesktopLibraryClient
import com.twilightecho.android.coreclient.RemoteConnectionPhase
import com.twilightecho.android.coreclient.RemoteConnectionState
import com.twilightecho.android.coreclient.CoreRequestEnvelope
import com.twilightecho.android.coreclient.CorePhase
import com.twilightecho.android.coreclient.DesktopCoreDevice
import com.twilightecho.android.coreclient.LibrarySource
import com.twilightecho.android.coreclient.DesktopMediaReference
import com.twilightecho.android.coreclient.DesktopMediaRangeReader
import com.twilightecho.android.coreclient.AuthorizedDesktopMedia
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DesktopCapabilities
import com.twilightecho.android.coreclient.TrackArtworkPage
import com.twilightecho.android.coreclient.EntityMetadataDetail
import com.twilightecho.android.coreclient.OutputPresetCoordinator
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.nativeintegration.DesktopStreamDataSource
import com.twilightecho.android.runtimehost.CoreHostPorts
import com.twilightecho.android.runtimehost.CoreRuntimeThreadDispatcher
import com.twilightecho.android.runtimehost.QuickJsRuntimeHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel

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
    private val userPlaybackRevision = AtomicLong(0)
    // While Music Core applies a platform interruption, its engine pause is not the user's.
    private val platformPauses = AtomicInteger(0)
    private val focusActions = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init { hostScope.launch { for (action in focusActions) action() } }

    val runtimeDispatcher = CoreRuntimeThreadDispatcher()
    val clockAdapter = AndroidClockAdapter(CoroutineScope(SupervisorJob() + runtimeDispatcher.dispatcher))
    val sessionStoreAdapter = AndroidSessionStoreAdapter(
        storageDirectory = File(appContext.filesDir, "twilight_core_state")
    )
    val resourceAccessAdapter = AndroidResourceAccessAdapter(appContext)
    val mediaStoreScanner = AndroidMediaStoreScannerAdapter(appContext)
    private val musicFolderScanner = AndroidMusicFolderScanner(appContext)
    private val mutableMusicFolders = MutableStateFlow(musicFolderScanner.folders())
    /** The music folders the user picked, oldest first. */
    val musicFolders: StateFlow<List<MusicFolder>> = mutableMusicFolders.asStateFlow()
    val networkAdapter = AndroidNetworkAdapter()
    val userPreferences = UserPreferencesStore(appContext)
    val plugins = com.twilightecho.android.plugins.AndroidPluginRepository(appContext)
    val audioDeviceAdapter = AndroidAudioDeviceAdapter(
        context = appContext,
        onPhysicalDisconnectBrake = {
            handlePlatformInterruption(PlatformInterruptionReason.AUDIO_BECOMING_NOISY)
        }
    )
    private val mutableOutputRoute = MutableStateFlow(com.twilightecho.android.runtimehost.AudioDeviceRouteInfo())
    /** The output the phone's own playback goes to; the default route while it plays nothing. */
    val outputRoute: StateFlow<com.twilightecho.android.runtimehost.AudioDeviceRouteInfo> = mutableOutputRoute.asStateFlow()
    private val outputRouteUpdates = audioDeviceAdapter.subscribeRouteChanges { mutableOutputRoute.value = it }
    val nativeAudioEngine: NativeAudioEngineAdapter = NativeAudioEngineAdapter(
        resourceAccess = resourceAccessAdapter,
        appContext = appContext,
        onUserPause = { if (platformPauses.get() == 0) noteUserPlaybackChange() },
        desktopStream = { resourceId ->
            val reference = DesktopMediaReference.parse(resourceId) ?: error("电脑音源引用无效")
            DesktopStreamDataSource(DesktopMediaRangeReader(reference, ::resolveDesktopMedia))
        },
        providerStream = { source ->
            val uri = java.net.URI(source)
            require(uri.host == "ncm" && uri.path.matches(Regex("/[0-9]+"))) { "音源引用无效" }
            val quality = userPreferences.state.value.ncmQuality
            com.twilightecho.android.nativeintegration.ProviderStreamDataSource(resolveUrl = { force ->
                kotlinx.coroutines.runBlocking {
                    val result = plugins.call("com.twilightecho.provider.ncm", "getPlaybackUrl",
                        ncmPlaybackArguments(uri.path.removePrefix("/"), quality, force))
                    result.jsonPrimitive.content.let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it }
                }
            })
        }
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

    private val outputRouteSubscription = nativeAudioEngine.subscribe { event ->
        if (event is com.twilightecho.android.runtimehost.AudioEvent.SnapshotUpdated) {
            audioDeviceAdapter.reportPlaybackOutput(event.snapshot)
        }
    }

    val runtimeHost = QuickJsRuntimeHost(
        appContext = appContext,
        ports = hostPorts,
        runtimeDispatcher = runtimeDispatcher,
        // Music Core may already be paused by an interruption, so a user pause can reach no engine
        // port; the request itself is what cancels a pending focus resume.
        onRequest = { request ->
            if (request.kind == "command" && request.name in USER_STOP_COMMANDS) noteUserPlaybackChange()
        }
    )

    val switchableTransport = SwitchableCoreTransport(
        localTransport = runtimeHost,
        scope = hostScope
    )

    private val activeRemoteTransport = AtomicReference<RemoteNetworkCoreTransport?>(null)
    private class DesktopConnection(val transport: RemoteNetworkCoreTransport, val client: CoreClient,
        val library: DesktopLibraryClient, val source: LibrarySource.Desktop, val capabilities: DesktopCapabilities?) {
        /** The desktop is asked for its streaming sign-in at most once per connection. */
        val sessionQueried = AtomicBoolean(false)
    }
    private val connections = java.util.concurrent.ConcurrentHashMap<String, DesktopConnection>()
    private val pinnedMediaVersions = java.util.concurrent.ConcurrentHashMap<String, String>()
    val libraryTransport = SwitchableCoreTransport(localTransport = runtimeHost, scope = hostScope)
    val libraryCoreClient: CoreClient = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient("android-library"), libraryTransport, hostScope)
    private val mutableLibrarySource = MutableStateFlow<LibrarySource>(LibrarySource.Phone)
    val librarySource = mutableLibrarySource.asStateFlow()
    private val connectionMutex = Mutex()
    private var connectionJob: Job? = null
    private val mutableRemoteConnection = MutableStateFlow(RemoteConnectionState(RemoteConnectionPhase.CLOSED))
    val remoteConnection = mutableRemoteConnection.asStateFlow()

    /**
     * Current Roon-style operating mode ([CoreOperatingMode.StandaloneLocal] vs [CoreOperatingMode.RemoteDesktopCore]).
     */
    val operatingMode: StateFlow<CoreOperatingMode> = switchableTransport.activeMode
    val desktopDiscovery = DesktopCoreDiscovery(appContext)
    private val mutableKnownDesktops = MutableStateFlow<List<DesktopCoreDevice>>(emptyList())
    val knownDesktops = mutableKnownDesktops.asStateFlow()

    // Remembered computer libraries: their songs stay listed (and playable through a fallback) while offline.
    private val trackIndexStore = DesktopTrackIndexStore(File(appContext.filesDir, "twilight_core_state"))
    private val indexMutex = Mutex()
    private val indexLoaded = CompletableDeferred<Unit>()
    private val indexSyncJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val desktopWatchers = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val mutableDesktopLibraries = MutableStateFlow<List<DesktopLibraryIndex>>(emptyList())
    val desktopLibraries: StateFlow<List<DesktopLibraryIndex>> = mutableDesktopLibraries.asStateFlow()
    private val mutableConnectedDesktops = MutableStateFlow<Set<String>>(emptySet())
    /** Server IDs whose connection is currently live; only these can stream to the phone. */
    val connectedDesktops: StateFlow<Set<String>> = mutableConnectedDesktops.asStateFlow()
    private val mutableAdoptedSessions = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emits a computer's name after the phone signed into the streaming account that computer shared. */
    val providerSessionAdopted: SharedFlow<String> = mutableAdoptedSessions.asSharedFlow()

    val primaryCoreClient: CoreClient = DefaultCoreClient(
        identity = CoreClientIdentity.androidPrimaryClient(),
        transport = switchableTransport,
        scope = hostScope
    )

    // Keep Android's foreground/lock-screen session attached to audible phone playback even
    // while the UI temporarily controls the computer. An idle phone can still be a remote.
    val systemMediaTransport = SwitchableCoreTransport(localTransport = runtimeHost, scope = hostScope)
    private val mediaRouteMutex = Mutex()
    private var phoneOwnsSystemSession = false

    val mediaSessionCoreClient: CoreClient = DefaultCoreClient(
        identity = CoreClientIdentity.androidSystemMediaClient(),
        transport = systemMediaTransport,
        scope = hostScope
    )

    // Device scanning and platform interruptions always belong to the phone, never the selected desktop.
    private val localCoreClient: CoreClient = DefaultCoreClient(
        identity = CoreClientIdentity.androidPrimaryClient("android-local-host"),
        transport = runtimeHost, scope = hostScope
    )
    val desktopLibrary = DesktopLibraryClient(libraryTransport) {
        libraryCoreClient.snapshot.value?.epoch ?: "epoch-boot"
    }
    /** The phone's own library, whichever computer the catalog browser currently reads. */
    val phoneLibraryClient: CoreClient get() = localCoreClient

    /** Serializes EQ/DSP read-modify-write between the UI and output presets. */
    val audioConfigMutex = Mutex()
    /** Presets follow the phone's outputs, so they always edit the phone's own Core. */
    val outputPresets = OutputPresetCoordinator(hostScope, userPreferences.state, userPreferences::update, audioConfigMutex,
        readConfig = { localCoreClient.getEqConfig().getOrThrow() },
        writeConfig = { localCoreClient.setEqConfig(it).getOrThrow() })

    val audioFocusCoordinator = AudioFocusCoordinator(
        context = appContext,
        onInterruption = ::handlePlatformInterruption,
        onFocusRegained = ::handleAudioFocusRegained,
        onDuckVolumeChange = ::handleDuckVolumeChange,
        isPlaying = { nativeAudioEngine.isPlaying }
    )

    fun ensureStarted() {
        if (started.compareAndSet(false, true)) {
            publishKnownDesktops()
            hostScope.launch(Dispatchers.IO) {
                val stored = runCatching { trackIndexStore.loadAll() }.getOrDefault(emptyList())
                indexMutex.withLock {
                    mutableDesktopLibraries.update { current ->
                        (stored.filter { saved -> current.none { it.serverId == saved.serverId } } + current).sortedBy { it.hostLabel }
                    }
                }
                indexLoaded.complete(Unit)
            }
            hostScope.launch {
                try {
                    runtimeHost.start()
                } catch (error: Exception) {
                    // The runtime host now fails every request with this reason; crashing the
                    // process would only hide it behind a restart loop.
                    android.util.Log.e("TwilightPlaybackHost", "Music Core failed to start", error)
                    return@launch
                }
                localCoreClient.reconcileState()
                primaryCoreClient.reconcileState()
                mediaSessionCoreClient.reconcileState()
                libraryCoreClient.reconcileState()
                audioDeviceAdapter.subscribeRouteChanges { route -> outputPresets.outputChanged { audioDeviceAdapter.outputIdentity(route) } }
                audioDeviceAdapter.subscribeRemovedOutputs(outputPresets::outputsRemoved)
                val route = audioDeviceAdapter.getActiveRoute()
                outputPresets.outputChanged { audioDeviceAdapter.outputIdentity(route) }
                outputPresets.start()
                if (!userChoseConnection.get()) autoReconnectJob = hostScope.launch { reconnectLastDesktop() }
            }
            hostScope.launch { localCoreClient.snapshot.collect { refreshSystemMediaRoute() } }
            hostScope.launch { operatingMode.collect { refreshSystemMediaRoute() } }
        }
    }

    private suspend fun refreshSystemMediaRoute() = mediaRouteMutex.withLock {
        val state = localCoreClient.snapshot.value?.playback?.state
        if (state in setOf("playing", "loading", "buffering")) phoneOwnsSystemSession = true
        else if (state != "paused") phoneOwnsSystemSession = false
        // A lock-screen pause must retain the same session so its Play button resumes the phone.
        val target = if (phoneOwnsSystemSession) runtimeHost else switchableTransport.currentTransport
        if (systemMediaTransport.currentTransport !== target) {
            systemMediaTransport.switchTo(target, if (target === runtimeHost) CoreOperatingMode.StandaloneLocal else operatingMode.value)
            mediaSessionCoreClient.reconcileState()
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

    /** IRs become app-owned files so native loading and saved scenes survive SAF grants/restarts. */
    suspend fun importImpulseResponse(uri: String): File = withContext(Dispatchers.IO) {
        val directory = File(appContext.filesDir, "dsp-impulses").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.wav")
        try {
            appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取脉冲响应文件")
            val header = ByteArray(12)
            file.inputStream().use { require(it.read(header) == 12) { "WAV 文件不完整" } }
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
                "请选择 PCM 或 Float WAV 脉冲响应"
            }
            file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    /**
     * Connects to a PC Desktop Core over LAN (Roon-style Remote Controller mode).
     *
     * 1. Pairs via 6-digit [pin] (or reuses saved token for the same [hostOrUrl]).
     * Selects a library only. Playback target and both queues remain unchanged.
     */
    suspend fun connectDesktopLibrary(
        hostOrUrl: String,
        pin: String? = null,
        expectedServerId: String? = null,
        requestApproval: Boolean = false,
        waitingForApproval: (verificationCode: String) -> Unit = {}
    ): Result<CoreSnapshot> {
        stopAutoReconnect()
        return openDesktop(hostOrUrl, pin, expectedServerId, requestApproval, waitingForApproval, selectLibrary = true).onSuccess {
            (librarySource.value as? LibrarySource.Desktop)?.let { source ->
                rememberSession { it.copy(desktopId = source.serverId, libraryOnDesktop = true) }
            }
        }
    }

    /** Connects a computer; [selectLibrary] also shows its library. Never changes the playback target. */
    private suspend fun openDesktop(
        hostOrUrl: String,
        pin: String?,
        expectedServerId: String?,
        requestApproval: Boolean,
        waitingForApproval: (verificationCode: String) -> Unit,
        selectLibrary: Boolean
    ): Result<CoreSnapshot> = connectionMutex.withLock {
        ensureStarted()
        try {
            val normalizedUrl = RemoteNetworkCoreTransport.normalizeBaseUrl(hostOrUrl)
            val remoteTransport = RemoteNetworkCoreTransport(
                rawBaseUrl = normalizedUrl,
                scope = hostScope
            )

            try {
                val status = remoteTransport.checkStatus().getOrElse { throw desktopTransportFailure(it) }
                check(expectedServerId == null || status.serverId == expectedServerId) { "电脑身份已变化，请重新搜索后连接" }
                check(status.coreBridge && status.coreBridgeVersion == 1) { "电脑端尚未提供兼容的曲库接口，请更新电脑端后重试" }
                checkNotNull(remoteTransport.negotiate(status)) { "手机与电脑的远程协议版本不兼容，请将两端更新到最新版本" }
                check(status.tls) { "电脑端尚未支持加密连接，请更新电脑端后重试" }
                // The status request recorded the certificate this address presents. A paired computer must present
                // the certificate pinned at pairing, whatever address it has now.
                val saved = status.serverId?.let { id -> loadRemoteCredentials().lastOrNull { it.serverId == id } }
                if (saved != null && saved.certificateSha256 != remoteTransport.certificatePin.fingerprint)
                    throw DesktopCertificateMismatchException()
                if (!pin.isNullOrBlank()) {
                    remoteTransport.pairWithPin(pin.trim()).getOrThrow()
                } else {
                    remoteTransport.bearerToken = saved?.bearerToken
                    if (saved == null && status.requiresPairing) {
                        check(requestApproval && status.pairingMode == "approval-v2") { "请更新电脑端以支持一键连接，或使用手动配对" }
                        remoteTransport.pairWithDesktopApproval(android.os.Build.MODEL, waitingForApproval).getOrThrow()
                    }
                }

                // Validate the authenticated snapshot before disturbing the current playback source.
                fun handshakeRequest() = CoreRequestEnvelope(
                    requestId = "handshake-${UUID.randomUUID()}", epoch = "epoch-boot",
                    kind = "query", name = "core.getSnapshot"
                )
                var handshake = remoteTransport.sendRequest(handshakeRequest())
                if (!handshake.ok && handshake.error?.code == com.twilightecho.android.coreclient.CoreErrorCode.FORBIDDEN &&
                    requestApproval && status.pairingMode == "approval-v2") {
                    remoteTransport.bearerToken = null
                    remoteTransport.pairWithDesktopApproval(android.os.Build.MODEL, waitingForApproval).getOrThrow()
                    handshake = remoteTransport.sendRequest(handshakeRequest())
                }
                check(handshake.ok && handshake.value != null) { handshake.error?.message ?: "无法读取电脑状态" }
                val initial = DefaultCoreClient.DefaultJson.decodeFromJsonElement<CoreSnapshot>(handshake.value!!)
                check(initial.phase == CorePhase.READY) { "电脑 Core 尚未就绪，请稍后重试" }

                val desktopClient = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient("android-desktop-reader"), remoteTransport, hostScope)
                desktopClient.reconcileState().getOrNull() ?: error("无法同步电脑状态")
                val library = DesktopLibraryClient(remoteTransport) { desktopClient.snapshot.value?.epoch ?: initial.epoch }
                val info = if (status.capabilities?.queries?.contains("library.info") == true) library.info().getOrThrow() else null
                val serverId = status.serverId ?: error("电脑未提供稳定身份，请更新电脑端")
                val source = LibrarySource.Desktop(serverId, info?.libraryId, normalizedUrl,
                    status.serverName ?: normalizedUrl.removePrefix("http://").removePrefix("https://"))
                val previous = connections[serverId]
                val previousLibraryTransport = libraryTransport.currentTransport
                val previousLibraryMode = libraryTransport.activeMode.value
                val previousSource = librarySource.value
                val previousPlaybackTransport = switchableTransport.currentTransport
                val previousPlaybackMode = operatingMode.value
                val reconciled: CoreSnapshot
                try {
                    if (selectLibrary) {
                        libraryTransport.switchTo(
                            targetTransport = remoteTransport,
                            mode = CoreOperatingMode.RemoteDesktopCore(
                                baseUrl = normalizedUrl,
                                hostLabel = source.hostLabel,
                                connected = true
                            )
                        )
                        mutableLibrarySource.value = source
                    } else if (previous != null && libraryTransport.currentTransport === previous.transport) {
                        // Reconnecting the computer whose library is showing replaces its stale connection there too.
                        libraryTransport.switchTo(remoteTransport, CoreOperatingMode.RemoteDesktopCore(normalizedUrl, source.hostLabel))
                    }
                    if (previous != null && switchableTransport.currentTransport === previous.transport) {
                        switchableTransport.switchTo(remoteTransport, CoreOperatingMode.RemoteDesktopCore(source.baseUrl, source.hostLabel))
                        primaryCoreClient.reconcileState().getOrNull() ?: error("无法同步电脑播放状态")
                    }
                    reconciled = if (selectLibrary) libraryCoreClient.reconcileState().getOrNull() ?: error("无法同步电脑曲库状态")
                        else desktopClient.snapshot.value ?: initial
                    refreshSystemMediaRoute()
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        libraryTransport.switchTo(previousLibraryTransport, previousLibraryMode)
                        mutableLibrarySource.value = previousSource
                        switchableTransport.switchTo(previousPlaybackTransport, previousPlaybackMode)
                        runCatching { libraryCoreClient.reconcileState() }
                        runCatching { primaryCoreClient.reconcileState() }
                        runCatching { refreshSystemMediaRoute() }
                    }
                    throw error
                }
                val connection = DesktopConnection(remoteTransport, desktopClient, library, source, status.capabilities)
                connections[serverId] = connection
                activeRemoteTransport.set(remoteTransport)
                connectionJob?.cancel()
                remoteTransport.bearerToken?.let {
                    saveRemoteCredentials(RemoteCoreCredentials(normalizedUrl, it, status.serverId, status.serverName,
                        remoteTransport.certificatePin.fingerprint))
                }
                connectionJob = hostScope.launch {
                    remoteTransport.connection.collect { state ->
                        if (activeRemoteTransport.get() === remoteTransport) {
                            mutableRemoteConnection.value = state
                            if ((mutableLibrarySource.value as? LibrarySource.Desktop)?.serverId == serverId)
                                mutableLibrarySource.value = source.copy(connected = state.phase == RemoteConnectionPhase.CONNECTED)
                            libraryTransport.updateRemoteConnection(state.phase == RemoteConnectionPhase.CONNECTED)
                            if (switchableTransport.currentTransport === remoteTransport)
                                switchableTransport.updateRemoteConnection(state.phase == RemoteConnectionPhase.CONNECTED)
                        }
                    }
                }
                remoteTransport.startEventStream()
                previous?.transport?.close()
                watchDesktop(serverId, connection)
                startIndexSync(serverId)
                adoptProviderSession(serverId)
                Result.success(reconciled)
            } catch (err: Throwable) {
                remoteTransport.close()
                throw err
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Compatibility entry point for callers explicitly requesting desktop controller mode. */
    suspend fun connectToDesktopCore(hostOrUrl: String, pin: String? = null, expectedServerId: String? = null,
        requestApproval: Boolean = false, waitingForApproval: (verificationCode: String) -> Unit = {}): Result<CoreSnapshot> {
        val result = connectDesktopLibrary(hostOrUrl, pin, expectedServerId, requestApproval, waitingForApproval)
        return if (result.isSuccess) selectPlaybackTarget(phone = false) else result
    }

    /**
     * Switches back from Desktop Remote Controller mode to the phone's embedded Standalone Music Core.
     */
    suspend fun switchToStandaloneLocalCore(): Result<CoreSnapshot> {
        stopAutoReconnect()
        return connectionMutex.withLock {
            ensureStarted()
            withContext(NonCancellable) { runCatching {
                restoreLocalLibrary()
                switchableTransport.switchToLocal()
                refreshSystemMediaRoute()
                primaryCoreClient.reconcileState().getOrNull() ?: error("本机播放状态未就绪")
            } }
        }.onSuccess { rememberSession { it.copy(libraryOnDesktop = false, outputDesktopId = null) } }
    }

    suspend fun selectPhoneLibrary(): Result<CoreSnapshot> {
        stopAutoReconnect()
        return connectionMutex.withLock {
            ensureStarted()
            runCatching { restoreLocalLibrary() }
        }.onSuccess { rememberSession { it.copy(libraryOnDesktop = false) } }
    }

    private suspend fun restoreLocalLibrary(): CoreSnapshot {
        mutableLibrarySource.value = LibrarySource.Phone
        libraryTransport.switchToLocal()
        val reconciled = libraryCoreClient.reconcileState()
        return reconciled.getOrNull() ?: throw IllegalStateException("本机音乐状态未就绪，请重试")
    }

    /**
     * Only changes which independent session the UI/system controls observe. Does not pause or copy.
     * This is the user's explicit choice, so it is the output restored after a restart.
     */
    suspend fun selectPlaybackTarget(phone: Boolean): Result<CoreSnapshot> {
        stopAutoReconnect()
        return connectionMutex.withLock {
            val desktop = if (phone) null else (librarySource.value as? LibrarySource.Desktop)?.serverId
                ?: return@withLock Result.failure(IllegalStateException("请先选择电脑曲库"))
            routePlayback(desktop).onSuccess { rememberSession { it.copy(outputDesktopId = desktop) } }
        }
    }

    /** Points playback controls at the phone ([serverId] null) or a connected computer. Caller holds the connection lock. */
    private suspend fun routePlayback(serverId: String?): Result<CoreSnapshot> {
        ensureStarted()
        val previousTransport = switchableTransport.currentTransport
        val previousMode = operatingMode.value
        return try {
            if (serverId == null) switchableTransport.switchToLocal()
            else {
                val connection = connections[serverId] ?: error("请重新连接电脑")
                val source = connection.source
                switchableTransport.switchTo(connection.transport, CoreOperatingMode.RemoteDesktopCore(source.baseUrl,
                    source.hostLabel, connection.transport.connection.value.phase == RemoteConnectionPhase.CONNECTED))
            }
            val snapshot = primaryCoreClient.reconcileState().getOrNull()
                ?: error("播放状态未就绪，请重试")
            mediaRouteMutex.withLock {
                if (localCoreClient.snapshot.value?.playback?.state !in setOf("playing", "loading", "buffering"))
                    phoneOwnsSystemSession = false
            }
            refreshSystemMediaRoute()
            Result.success(snapshot)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                switchableTransport.switchTo(previousTransport, previousMode)
                runCatching { primaryCoreClient.reconcileState() }
                runCatching { refreshSystemMediaRoute() }
            }
            if (error is CancellationException) throw error
            Result.failure(error)
        }
    }

    fun desktopQueueTracks(tracks: List<CoreTrackView>): List<CoreTrackView> {
        val source = librarySource.value as? LibrarySource.Desktop ?: error("请先选择电脑曲库")
        return desktopStreamTracks(source.serverId, tracks)
    }

    /** Turns songs of one computer into phone-playable stream references; needs a live connection to it. */
    fun desktopStreamTracks(serverId: String, tracks: List<CoreTrackView>): List<CoreTrackView> {
        val connection = connections[serverId] ?: error("电脑未连接，请先重新连接")
        val libraryId = connection.source.libraryId ?: error("请更新电脑端以支持手机串流")
        return tracks.map { track ->
            val catalog = track.catalog ?: error("歌曲缺少稳定音源引用，请刷新电脑曲库")
            val reference = DesktopMediaReference(serverId, libraryId, catalog.sourceId)
            pinnedMediaVersions.remove("$serverId/$libraryId/${catalog.sourceId}")
            val id = reference.resourceId()
            track.copy(id = id, source = id, queueEntryId = null)
        }
    }

    /** Cover previews for songs of a specific computer, independent of which library is being browsed. */
    suspend fun desktopArtwork(serverId: String, ids: List<String>): Result<TrackArtworkPage> =
        connections[serverId]?.library?.artwork(ids) ?: Result.failure(IllegalStateException("电脑未连接"))

    /** Exact source membership joins a merged phone collection to the computer's curated artist. */
    suspend fun desktopArtistMetadata(serverId: String, sourceIds: Set<String>, name: String): Result<EntityMetadataDetail?> {
        val connection = connections[serverId] ?: return Result.failure(IllegalStateException("电脑未连接"))
        return try {
            // Stored indexes intentionally omit entity references. A bounded fresh read supplies them.
            val page = connection.client.searchTracksPage(name, 0, 100).getOrThrow()
            val candidates = page.items.filter { it.catalog?.sourceId in sourceIds }
                .flatMap { it.catalog?.items.orEmpty() }.filter { it.kind == "artist" && it.title.trim().equals(name.trim(), true) }
                .distinctBy { it.id }
            if (candidates.size != 1) Result.success(null)
            else connection.library.metadata(candidates.single().id).map { it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    /** The computer the playback controls currently drive, or null while the phone plays. */
    fun playbackDesktopServerId(): String? =
        connections.entries.firstOrNull { it.value.transport === switchableTransport.currentTransport }?.key

    /** Last address this phone used for a paired computer, for reconnecting without searching. */
    fun savedDesktopAddress(serverId: String): String? =
        loadRemoteCredentials().lastOrNull { it.serverId == serverId }?.baseUrl

    /**
     * Re-reads a computer's song list now. [force] copies it again even when it looks unchanged, e.g.
     * after its session references went stale; otherwise an unchanged library costs one request.
     */
    fun refreshDesktopIndex(serverId: String, force: Boolean = true) = startIndexSync(serverId, restart = force, force = force)

    private fun watchDesktop(serverId: String, connection: DesktopConnection) {
        desktopWatchers.remove(serverId)?.cancel()
        desktopWatchers[serverId] = hostScope.launch {
            launch {
                connection.transport.connection.collect { state ->
                    if (connections[serverId] !== connection) return@collect
                    val online = state.phase == RemoteConnectionPhase.CONNECTED
                    mutableConnectedDesktops.update { if (online) it + serverId else it - serverId }
                    if (online) startIndexSync(serverId)
                }
            }
            // A restarted desktop issues new session references; a changed library needs a new list.
            launch {
                connection.client.snapshot.map { it?.epoch to it?.libraryRevision }.distinctUntilChanged().drop(1)
                    .collectLatest { delay(INDEX_RESYNC_DELAY_MS); startIndexSync(serverId, restart = true) }
            }
        }
    }

    private fun startIndexSync(serverId: String, restart: Boolean = false, force: Boolean = false) {
        synchronized(indexSyncJobs) {
            val running = indexSyncJobs[serverId]
            if (running?.isActive == true && !restart) return
            running?.cancel()
            indexSyncJobs[serverId] = hostScope.launch { running?.join(); syncDesktopIndex(serverId, force) }
        }
    }

    /**
     * Copies a computer's song list page by page. The desktop allows about 60 requests a minute across
     * all clients, so pages are spaced out and failures back off; an unchanged library costs one request.
     */
    private suspend fun syncDesktopIndex(serverId: String, force: Boolean) {
        indexLoaded.await()
        val connection = connections[serverId] ?: return
        val epoch = connection.client.snapshot.value?.epoch
        val existing = desktopLibraries.value.find { it.serverId == serverId }
        val tracks = ArrayList<CoreTrackView>()
        var offset = 0
        var revision: Long? = null
        var failures = 0
        var pagesSinceSave = 0
        while (currentCoroutineContext().isActive && connections[serverId] === connection) {
            val page = connection.client.searchTracksPage("", offset, INDEX_PAGE_SIZE).getOrNull()
            if (page == null) {
                if (++failures > INDEX_MAX_FAILURES) return
                delay(INDEX_REQUEST_SPACING_MS shl failures); continue
            }
            failures = 0
            if (revision != null && page.revision != revision) {
                // The library changed mid-copy; start over so the list is one consistent version.
                tracks.clear(); offset = 0; revision = null; delay(INDEX_REQUEST_SPACING_MS); continue
            }
            revision = page.revision
            if (offset == 0 && !force && existing != null && existing.complete && existing.epoch == epoch &&
                existing.revision == page.revision && existing.tracks.size == page.total) {
                val labelled = existing.labelled(connection.source)
                publishIndex(labelled, persist = labelled != existing)
                return
            }
            page.items.mapTo(tracks, ::indexedDesktopTrack)
            offset += page.items.size
            val done = page.items.isEmpty() || offset >= page.total
            // A first copy lists songs as they arrive; a refresh swaps the old list only once the new one is whole.
            if (done || existing == null || !existing.complete) {
                val persist = done || ++pagesSinceSave >= INDEX_PAGES_PER_SAVE
                if (persist) pagesSinceSave = 0
                publishIndex(DesktopLibraryIndex(serverId, connection.source.libraryId, connection.source.hostLabel,
                    connection.source.baseUrl, epoch, revision, System.currentTimeMillis(), done, tracks.toList()), persist)
            }
            if (done) return
            delay(INDEX_REQUEST_SPACING_MS)
        }
    }

    private fun DesktopLibraryIndex.labelled(source: LibrarySource.Desktop) =
        copy(hostLabel = source.hostLabel, baseUrl = source.baseUrl, libraryId = source.libraryId ?: libraryId)

    private suspend fun publishIndex(index: DesktopLibraryIndex, persist: Boolean) = indexMutex.withLock {
        mutableDesktopLibraries.update { list -> (list.filterNot { it.serverId == index.serverId } + index).sortedBy { it.hostLabel } }
        if (persist) withContext(Dispatchers.IO) { runCatching { trackIndexStore.save(index) } }
    }

    /** Asks every connected computer for its streaming sign-in, e.g. right after the provider was enabled. */
    fun adoptDesktopProviderSessions() = connections.keys.forEach(::adoptProviderSession)

    /**
     * Signs a signed-out phone into the account the user's own desktop already uses. Only a successful
     * `providers.session` reply is imported; any error means sharing is unavailable and is not retried.
     * The cookie goes straight into the plugin's encrypted settings and is never logged.
     */
    private fun adoptProviderSession(serverId: String) {
        val connection = connections[serverId] ?: return
        if (connection.capabilities?.queries?.contains("providers.session") != true) return
        if (plugins.state.value.installed.none { it.id == NCM_PROVIDER_PLUGIN && it.enabled }) return
        if (!connection.sessionQueried.compareAndSet(false, true)) return
        hostScope.launch {
            try {
                // A signed-in phone keeps its own account, so the desktop is not even asked.
                val login = plugins.call(NCM_PROVIDER_PLUGIN, "checkLogin") as? JsonObject
                if (login?.get("loggedIn")?.jsonPrimitive?.content == "true") return@launch
                val session = connection.library.providerSession(NCM_PROVIDER).getOrNull() ?: return@launch
                val cookie = session.cookie?.takeIf { session.providerId == NCM_PROVIDER && session.loggedIn && it.isNotBlank() }
                    ?: return@launch
                if (plugins.adoptAccountCookie(NCM_PROVIDER_PLUGIN, cookie)) mutableAdoptedSessions.tryEmit(connection.source.hostLabel)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Best effort: the user can still sign in on the phone.
            }
        }
    }

    private suspend fun resolveDesktopMedia(reference: DesktopMediaReference): AuthorizedDesktopMedia {
        val connection = connections[reference.serverId] ?: error("电脑尚未连接，请先重新连接该曲库")
        val key = "${reference.serverId}/${reference.libraryId}/${reference.sourceId}"
        val plan = connection.library.resolveMedia(reference.copy(sourceVersion = reference.sourceVersion ?: pinnedMediaVersions[key]))
            .getOrThrow()
        pinnedMediaVersions[key] = plan.sourceVersion
        val bearer = connection.transport.bearerToken ?: error("电脑授权已失效，请重新连接")
        val certificate = connection.transport.certificatePin.fingerprint ?: error("电脑未建立加密连接，请重新连接")
        return AuthorizedDesktopMedia(connection.transport.baseUrl, bearer, plan, certificate)
    }

    fun getSavedRemoteDesktopUrl(): String? = loadRemoteCredentials().lastOrNull()?.baseUrl

    private val trustedDesktops by lazy { TrustedDesktopStore(File(appContext.filesDir, "twilight_core_state")) }
    private val desktopSession by lazy { DesktopSessionStore(File(appContext.filesDir, "twilight_core_state")) }
    @Volatile private var autoReconnectJob: Job? = null
    private val userChoseConnection = AtomicBoolean(false)
    private val mutableReconnectNotice = MutableStateFlow<String?>(null)
    /** Why the computer from last time could not be reconnected automatically, or null. */
    val desktopReconnectNotice: StateFlow<String?> = mutableReconnectNotice.asStateFlow()

    private fun rememberSession(transform: (DesktopSession) -> DesktopSession) {
        runCatching { desktopSession.update(transform) }
    }

    /** Any explicit connection choice by the user supersedes restoring the previous session. */
    private fun stopAutoReconnect() {
        userChoseConnection.set(true)
        autoReconnectJob?.cancel()
    }

    /**
     * Reconnects the computer the user last connected to, without asking it for a new approval. Its library is
     * shown and playback is pointed at it only if those were the user's last explicit choices; otherwise the
     * phone stays as it is. Only an unreachable computer is retried: a refused token or a changed certificate
     * needs the user, and is reported through [desktopReconnectNotice].
     */
    private suspend fun reconnectLastDesktop() {
        val session = desktopSession.load()
        val serverId = session.desktopId ?: return
        val saved = loadRemoteCredentials().lastOrNull { it.serverId == serverId } ?: return
        for (wait in RECONNECT_DELAYS_MS) {
            delay(wait)
            if (userChoseConnection.get() || connections.containsKey(serverId)) return
            val address = desktopDiscovery.state.value.devices.firstOrNull { it.id == serverId }?.baseUrl ?: saved.baseUrl
            val result = openDesktop(address, null, serverId, requestApproval = false, waitingForApproval = {},
                selectLibrary = session.libraryOnDesktop)
            if (result.isSuccess) {
                restoreOutput(serverId)
                return
            }
            val error = result.exceptionOrNull()
            val causes = generateSequence(error) { it.cause }.take(8).toList()
            val unreachable = causes.any { it is java.io.IOException } && causes.none { it is javax.net.ssl.SSLException }
            if (!unreachable) {
                mutableReconnectNotice.value = "无法自动连接 ${saved.serverName ?: saved.baseUrl}：${error?.message ?: "请重新连接"}"
                return
            }
        }
    }

    private suspend fun restoreOutput(serverId: String) {
        if (desktopSession.load().outputDesktopId != serverId || userChoseConnection.get()) return
        // A phone already playing was chosen after the saved output, so it keeps the controls.
        if (localCoreClient.snapshot.value?.playback?.state in setOf("playing", "loading", "buffering")) return
        connectionMutex.withLock { routePlayback(serverId) }
    }

    /**
     * Forgets a paired computer: its token, pinned certificate and remembered songs are deleted from the
     * phone, and the computer is asked to revoke the token. Whatever was shown or playing from it returns to
     * the phone. Connecting again needs a new approval on the computer.
     */
    suspend fun forgetDesktop(serverId: String): Result<Unit> {
        stopAutoReconnect()
        return connectionMutex.withLock { withContext(NonCancellable) { runCatching {
            val connection = connections.remove(serverId)
            desktopWatchers.remove(serverId)?.cancel()
            indexSyncJobs.remove(serverId)?.cancel()
            mutableConnectedDesktops.update { it - serverId }
            if ((librarySource.value as? LibrarySource.Desktop)?.serverId == serverId) runCatching { restoreLocalLibrary() }
            if (connection != null && switchableTransport.currentTransport === connection.transport) {
                switchableTransport.switchToLocal()
                runCatching { primaryCoreClient.reconcileState() }
            }
            runCatching { refreshSystemMediaRoute() }
            if (connection != null && activeRemoteTransport.compareAndSet(connection.transport, null)) {
                connectionJob?.cancel()
                mutableRemoteConnection.value = RemoteConnectionState(RemoteConnectionPhase.CLOSED)
            }
            val saved = loadRemoteCredentials().lastOrNull { it.serverId == serverId }
            trustedDesktops.forget(serverId)
            rememberSession { it.forgetting(serverId) }
            indexMutex.withLock {
                mutableDesktopLibraries.update { list -> list.filterNot { it.serverId == serverId } }
                withContext(Dispatchers.IO) { runCatching { trackIndexStore.delete(serverId) } }
            }
            publishKnownDesktops()
            // Revoking on the computer is best effort and must not hold the phone up while it is offline.
            val revoking = connection?.transport ?: saved?.certificateSha256?.let { pin ->
                RemoteNetworkCoreTransport(saved.baseUrl, saved.bearerToken, hostScope,
                    certificatePin = DesktopCertificatePin(pin))
            }
            if (revoking != null) hostScope.launch { try { revoking.forgetOnDesktop() } finally { revoking.close() } }
            Unit
        } } }
    }

    private fun loadRemoteCredentials(): List<RemoteCoreCredentials> = runCatching { trustedDesktops.load() }.getOrDefault(emptyList())

    private fun saveRemoteCredentials(credentials: RemoteCoreCredentials) {
        runCatching { trustedDesktops.save(credentials) }
        publishKnownDesktops()
    }

    /** TLS failures name what the user can do; a changed certificate is never retried silently. */
    private fun desktopTransportFailure(error: Throwable): Throwable {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        causes.firstOrNull { it is DesktopCertificateMismatchException }?.let { return it }
        return if (causes.any { it is javax.net.ssl.SSLException }) IllegalStateException("无法与电脑建立加密连接，请确认电脑端已更新", error)
        else error
    }

    private fun publishKnownDesktops() {
        mutableKnownDesktops.value = loadRemoteCredentials().map {
            DesktopCoreDevice(it.serverId ?: it.baseUrl, it.serverName ?: it.baseUrl, it.baseUrl, discovered = false, trusted = true)
        }
    }

    /**
     * Scans MediaStore and the picked music folders, then replaces the phone's Music Core library with the
     * result: CUE sheets in the folders split their audio files into songs, and audio MediaStore does not list
     * joins in. A folder that cannot be read keeps no songs, and the summary names it.
     */
    /** Without Android's music permission only the music folders are read, and the library holds only their songs. */
    suspend fun scanAndSyncDeviceMusic(): Result<LibraryScanSummary> {
        ensureStarted()
        return runCatching {
            val withDeviceMusic = mediaStoreScanner.canReadDeviceMusic()
            val scanned = if (withDeviceMusic) mediaStoreScanner.scanDeviceMusic() else emptyList()
            val folders = musicFolderScanner.scan(scanned)
            mutableMusicFolders.value = musicFolderScanner.folders()
            val library = folders.songs.over(scanned.map { it.track })
            localCoreClient.reconcileState()
            localCoreClient.syncLocalTracks(library).getOrThrow()
            LibraryScanSummary(library.size, library.count { it.id.startsWith("local:cue:") }, folders.songs.skipped,
                folders.unreadable, withDeviceMusic)
        }
    }

    /** Keeps read access to a folder from the system folder picker; the next scan reads it. */
    fun addMusicFolder(uri: String): Result<Unit> =
        runCatching { musicFolderScanner.add(uri) }.also { mutableMusicFolders.value = musicFolderScanner.folders() }

    /** Gives up a folder's access; its songs leave the library at the next scan. */
    fun removeMusicFolder(uri: String) {
        musicFolderScanner.remove(uri)
        mutableMusicFolders.value = musicFolderScanner.folders()
    }

    /**
     * Requests Android AudioFocus prior to user-initiated playback when in [CoreOperatingMode.StandaloneLocal]
     * mode, or directly forwards `Play` to the PC Desktop Core when in [CoreOperatingMode.RemoteDesktopCore] mode.
     */
    suspend fun requestFocusAndPlay(positionSeconds: Double? = null): CoreResult<JsonObject> {
        return playOnClient(primaryCoreClient, !switchableTransport.isRemote, positionSeconds)
    }

    suspend fun requestSystemFocusAndPlay(): CoreResult<JsonObject> =
        playOnClient(mediaSessionCoreClient, !systemMediaTransport.isRemote, null)

    private suspend fun playOnClient(client: CoreClient, phone: Boolean, positionSeconds: Double?): CoreResult<JsonObject> {
        ensureStarted()
        if (!phone) {
            return client.executeCommand(CoreTypedCommand.Play(positionSeconds))
        }
        if (audioFocusCoordinator.requestPlaybackFocus()) {
            return client.executeCommand(CoreTypedCommand.Play(positionSeconds))
        }
        return CoreResult.Failure(com.twilightecho.android.coreclient.CoreErrorView(
            com.twilightecho.android.coreclient.CoreErrorCode.FAILED, "未获得手机音频焦点，请重试"), "epoch-boot")
    }

    /**
     * Platform Safety Brake (Section 5.4):
     * 1. Immediately brakes native audio output (`emergencyBrakePause()`) when required in local standalone mode.
     * 2. Reports the interruption reason and transient flag back to the local Music Core.
     */
    private fun handlePlatformInterruption(reason: PlatformInterruptionReason) {
        if (!reason.isTransient) noteUserPlaybackChange()
        if (reason.shouldImmediateBrakePause) {
            nativeAudioEngine.emergencyBrakePause()
        }
        focusActions.trySend {
            platformPauses.incrementAndGet()
            try {
                localCoreClient.executeCommand(
                    CoreTypedCommand.PlatformInterruption(
                        reason = reason.wireReason,
                        shouldPause = reason.shouldImmediateBrakePause,
                        transientCanResume = reason.isTransient
                    )
                )
            } finally {
                platformPauses.decrementAndGet()
            }
        }
    }

    private fun noteUserPlaybackChange() {
        userPlaybackRevision.incrementAndGet()
        audioFocusCoordinator.cancelPendingResume()
    }

    private fun handleAudioFocusRegained(shouldResumeTransient: Boolean) {
        if (!shouldResumeTransient) return
        val revision = userPlaybackRevision.get()
        focusActions.trySend {
            // Check whether Music Core is still in a resumable paused state before resuming
            val currentState = nativeAudioEngine.getSnapshot().state
            if (revision == userPlaybackRevision.get() && currentState == "paused") {
                localCoreClient.executeCommand(CoreTypedCommand.Play(null))
            }
        }
    }

    private fun handleDuckVolumeChange(ducked: Boolean) {
        focusActions.trySend {
            nativeAudioEngine.setDucked(ducked)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            desktopDiscovery.stop()
            connections.values.forEach { it.transport.close() }
            connections.clear()
            audioFocusCoordinator.close()
            focusActions.close()
            outputRouteSubscription.cancel()
            outputRouteUpdates.cancel()
            audioDeviceAdapter.close()
            runtimeHost.close()
            nativeAudioEngine.close()
            plugins.close()
            resourceAccessAdapter.close()
            hostScope.cancel()
        }
    }

    companion object {
        private val USER_STOP_COMMANDS = setOf(CoreTypedCommand.Pause.name, CoreTypedCommand.Stop.name)
        private const val NCM_PROVIDER = "ncm"
        private const val NCM_PROVIDER_PLUGIN = "com.twilightecho.provider.ncm"
        private const val INDEX_PAGE_SIZE = 200
        private const val INDEX_REQUEST_SPACING_MS = 3_000L
        private const val INDEX_MAX_FAILURES = 4
        private const val INDEX_PAGES_PER_SAVE = 10
        private const val INDEX_RESYNC_DELAY_MS = 15_000L
        /** Waits before each attempt to reconnect last session's computer, e.g. while Wi-Fi comes up. */
        private val RECONNECT_DELAYS_MS = longArrayOf(0L, 5_000L, 30_000L)

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
