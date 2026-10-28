package com.twilightecho.android.coreclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference

/**
 * Roon-style Operating Mode for Twilight Echo (Core / Control / Output separation):
 * - [StandaloneLocal]: Phone runs its own embedded Music Core (`QuickJsRuntimeHost`) + local audio engine.
 * - [RemoteDesktopCore]: Phone acts as a Roon-style Remote Controller connected to a Desktop Core over LAN.
 */
@Serializable
sealed interface CoreOperatingMode {
    @Serializable
    data object StandaloneLocal : CoreOperatingMode

    @Serializable
    data class RemoteDesktopCore(
        val baseUrl: String,
        val hostLabel: String = baseUrl,
        val connected: Boolean = true
    ) : CoreOperatingMode
}

/**
 * Dynamic transport multiplexer that allows `DefaultCoreClient` instances (`primaryCoreClient`,
 * `mediaSessionCoreClient`) to seamlessly hot-swap between the embedded in-process
 * `QuickJsRuntimeHost` (Standalone mode) and a `RemoteNetworkCoreTransport` connected to a PC
 * Desktop Core (Roon Remote Controller mode) without recreating UI clients.
 */
class SwitchableCoreTransport(
    private val localTransport: CoreTransport,
    private val scope: CoroutineScope
) : CoreTransport {

    private data class Route(val transport: CoreTransport, val generation: Long)
    private val activeDelegate = AtomicReference(Route(localTransport, 0))
    override val generation: Long get() = activeDelegate.get().generation
    override val isRemote: Boolean get() = activeDelegate.get().transport.isRemote
    private var forwardJob: Job? = null

    private val _activeMode = MutableStateFlow<CoreOperatingMode>(CoreOperatingMode.StandaloneLocal)
    val activeMode: StateFlow<CoreOperatingMode> = _activeMode.asStateFlow()

    private val _mergedStateEvents = MutableSharedFlow<CoreEventMessage>(extraBufferCapacity = 64)
    override val stateEvents: Flow<CoreEventMessage> = _mergedStateEvents.asSharedFlow()

    init {
        bindEventForwarder(localTransport)
    }

    val currentTransport: CoreTransport
        get() = activeDelegate.get().transport

    fun switchTo(targetTransport: CoreTransport, mode: CoreOperatingMode) {
        activeDelegate.updateAndGet { Route(targetTransport, it.generation + 1) }
        _activeMode.value = mode
        bindEventForwarder(targetTransport)
    }

    fun switchToLocal() {
        switchTo(localTransport, CoreOperatingMode.StandaloneLocal)
    }

    private fun bindEventForwarder(transport: CoreTransport) {
        val origin = generation
        forwardJob?.cancel()
        forwardJob = scope.launch {
            transport.stateEvents.collect { event ->
                if (origin == generation) _mergedStateEvents.emit(event.copy(transportGeneration = origin))
            }
        }
    }

    override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
        val route = activeDelegate.get()
        val response = route.transport.sendRequest(envelope)
        return if (route === activeDelegate.get()) response else CoreResponseEnvelope(
            requestId = envelope.requestId, epoch = envelope.epoch, ok = false,
            error = CoreErrorView(CoreErrorCode.CANCELLED, "播放来源已切换，请重新选择"))
    }

    fun updateRemoteConnection(connected: Boolean) {
        val mode = _activeMode.value as? CoreOperatingMode.RemoteDesktopCore ?: return
        _activeMode.value = mode.copy(connected = connected)
    }

    override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {
        activeDelegate.get().transport.cancelRequest(envelope)
    }
}
