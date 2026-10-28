package com.twilightecho.android.runtimehost

import android.content.Context
import com.twilightecho.android.coreclient.CORE_PROTOCOL_VERSION
import com.twilightecho.android.coreclient.CoreCancelEnvelope
import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.coreclient.CoreErrorView
import com.twilightecho.android.coreclient.CoreEventMessage
import com.twilightecho.android.coreclient.CoreRequestEnvelope
import com.twilightecho.android.coreclient.CoreResponseEnvelope
import com.twilightecho.android.coreclient.CoreTransport
import com.twilightecho.android.coreclient.DefaultCoreClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.util.UUID

/**
 * Runs the shared Music Core bundle (`assets/core/music-core.js`) in QuickJS and exposes it as a
 * [CoreTransport]. The bundle owns every playback, queue, library, lyrics, bookmark, playlist and
 * session rule; this class only moves JSON across the boundary:
 *
 * 1. `bootHost` starts the Core; [start] returns once its `boot` message reports success.
 * 2. `drain()` hands back port calls, wire responses, coalesced state events and logs. Port calls
 *    run on one FIFO lane per port and are answered with `completePortCall`.
 * 3. `nextTimerDelayMs` schedules the next drain, so Core timers fire on the runtime thread.
 *
 * There is no fallback: a missing QuickJS, a bundle that fails to evaluate or a failed boot makes
 * [start] throw, and every request afterwards fails with that reason.
 *
 * [shutdown] stops the Core cleanly (its last session save goes through the ports first);
 * [close] tears the runtime down at once.
 */
class QuickJsRuntimeHost(
    private val appContext: Context?,
    private val ports: CoreHostPorts,
    private val runtimeDispatcher: CoreRuntimeThreadDispatcher = CoreRuntimeThreadDispatcher(),
    private val json: Json = DefaultCoreClient.DefaultJson,
    private val engineFactory: (CoreRuntimeThreadDispatcher) -> JsRuntimeEngine = ::CashQuickJsRuntimeEngine,
    private val scriptLoader: (() -> String)? = null,
    portDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (level: String, message: String) -> Unit = ::logToAndroid,
    /** Sees every request before it is submitted, on the caller's thread. */
    private val onRequest: (CoreRequestEnvelope) -> Unit = {}
) : CoreTransport, Closeable {

    private val runtimeScope = CoroutineScope(SupervisorJob() + runtimeDispatcher.dispatcher)
    private val portScope = CoroutineScope(SupervisorJob() + portDispatcher)
    private val _stateEvents = MutableSharedFlow<CoreEventMessage>(replay = 1, extraBufferCapacity = 64)
    override val stateEvents: Flow<CoreEventMessage> = _stateEvents.asSharedFlow()

    private val bridge = CorePortBridge(ports, json)
    private val epoch: String = "epoch-android-${UUID.randomUUID().toString().take(8)}"

    // Runtime-thread state.
    private var engine: JsRuntimeEngine? = null
    private var failure: String? = null
    private val pending = HashMap<String, CompletableDeferred<CoreResponseEnvelope>>()
    private val lanes = HashMap<String, Channel<PortCall>>()
    private val booted = CompletableDeferred<JsonObject>()
    private val stopped = CompletableDeferred<JsonObject>()
    @Volatile private var closed = false
    private var timerJob: Job? = null
    private var audioSubscription: CancelHandle? = null
    private var routeSubscription: CancelHandle? = null

    private class PortCall(val callId: String, val method: String, val args: JsonObject)

    suspend fun start() {
        runtimeDispatcher.runOnRuntimeThread {
            check(engine == null && failure == null) { "Music Core was already started" }
            try {
                val created = engineFactory(runtimeDispatcher)
                engine = created
                created.evaluate("globalThis.__TWILIGHT_HOST_EVENT_LOOP__ = true;", "host-event-loop.js")
                created.evaluate(loadPortableCoreScript(), PORTABLE_CORE_ASSET)
                val ack = parseObject(created.callCore("bootHost", buildJsonObject { put("epoch", JsonPrimitive(epoch)) }.toString()))
                if (ack.bool("ok") != true) throw PortableCoreException("bootHost failed: ${ack.string("error") ?: ack}")
                val protocol = (ack["protocolVersion"] as? JsonPrimitive)?.longOrNull
                if (protocol != CORE_PROTOCOL_VERSION.toLong()) {
                    throw PortableCoreException("music-core.js speaks protocol $protocol; this app needs $CORE_PROTOCOL_VERSION")
                }
            } catch (t: Throwable) {
                fail(t)
                throw t
            }
            audioSubscription = ports.audioEngine.subscribe { event ->
                val text = json.encodeToString(AudioEvent.serializer(), event)
                runtimeScope.launch { deliverAudioEvent(text) }
            }
            routeSubscription = ports.audioDevice.subscribeRouteChanges { route ->
                val text = buildJsonObject {
                    put("type", JsonPrimitive("route"))
                    put("route", json.encodeToJsonElement(AudioDeviceRouteInfo.serializer(), route))
                }.toString()
                runtimeScope.launch { deliverAudioEvent(text) }
            }
            pump()
        }
        val boot = try {
            withTimeout(BOOT_TIMEOUT_MS) { booted.await() }
        } catch (timeout: TimeoutCancellationException) {
            val error = PortableCoreException("Music Core did not finish booting within ${BOOT_TIMEOUT_MS / 1000}s")
            runtimeDispatcher.runOnRuntimeThread { fail(error) }
            throw error
        }
        if (boot.bool("ok") != true) {
            throw PortableCoreException(boot.string("error") ?: failure ?: "Music Core failed to boot")
        }
    }

    /**
     * Lets the Core finish its work and save the session through the ports, then [close]s. A Core
     * that does not report back within [timeoutMs] is closed anyway.
     */
    suspend fun shutdown(timeoutMs: Long = SHUTDOWN_TIMEOUT_MS) {
        try {
            val accepted = runCatching {
                runtimeDispatcher.runOnRuntimeThread {
                    val ack = callCore("shutdownHost", "{}")?.let(::parseObject)
                    pump()
                    ack?.bool("accepted") == true
                }
            }.getOrDefault(false)
            if (!accepted) return
            val report = withTimeoutOrNull(timeoutMs) { stopped.await() }
            when {
                report == null -> logger("warn", "Music Core did not finish shutting down within ${timeoutMs}ms")
                report.bool("ok") != true -> logger("warn", "Music Core shutdown failed: ${report.string("error")}")
            }
        } finally {
            close()
        }
    }

    override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
        if (closed) return errorResponse(envelope.requestId, CoreErrorCode.CANCELLED, "Music Core was closed")
        onRequest(envelope)
        return try {
            // Cancellation can land while the submission returns from the runtime thread, so the
            // Core-side cancel covers both phases; it is a no-op for a request never submitted.
            runtimeDispatcher.runOnRuntimeThread { submit(envelope) }.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { cancelRequest(CoreCancelEnvelope(requestId = envelope.requestId, epoch = envelope.epoch)) }
            }
            throw cancelled
        }
    }

    override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {
        if (closed) return
        runtimeDispatcher.runOnRuntimeThread {
            if (!pending.containsKey(envelope.requestId)) return@runOnRuntimeThread
            callCore("cancelRequest", buildJsonObject { put("requestId", JsonPrimitive(envelope.requestId)) }.toString())
            pump()
        }
    }

    private fun submit(envelope: CoreRequestEnvelope): CompletableDeferred<CoreResponseEnvelope> {
        val response = CompletableDeferred<CoreResponseEnvelope>()
        failure?.let { reason ->
            response.complete(errorResponse(envelope.requestId, CoreErrorCode.FAILED, reason))
            return response
        }
        if (engine == null) {
            response.complete(errorResponse(envelope.requestId, CoreErrorCode.NOT_READY, "Music Core is not started", retryable = true))
            return response
        }
        if (pending.containsKey(envelope.requestId)) {
            response.complete(errorResponse(envelope.requestId, CoreErrorCode.INVALID_REQUEST, "Request ${envelope.requestId} is already in flight"))
            return response
        }
        pending[envelope.requestId] = response
        val ack = callCore("handleRequest", json.encodeToString(CoreRequestEnvelope.serializer(), envelope))
            ?.let(::parseObject)
        if (ack != null && ack.bool("accepted") != true) {
            pending.remove(envelope.requestId)
            response.complete(errorResponse(envelope.requestId, CoreErrorCode.INVALID_REQUEST, ack.string("error") ?: "Request rejected"))
        }
        pump()
        return response
    }

    /** Calls a Core entry point; any engine failure is fatal and returns null. */
    private fun callCore(method: String, argJson: String): String? {
        val current = engine ?: return null
        if (failure != null) return null
        return try {
            current.callCore(method, argJson)
        } catch (t: Throwable) {
            fail(t)
            null
        }
    }

    private fun pump() {
        repeat(MAX_DRAIN_ROUNDS) {
            val drained = callCore("drain", "{}")?.let(::parseObject) ?: return
            val messages = drained["messages"] as? JsonArray ?: JsonArray(emptyList())
            for (message in messages) (message as? JsonObject)?.let(::handleMessage)
            scheduleTimer((drained["nextTimerDelayMs"] as? JsonPrimitive)?.longOrNull)
            if (messages.isEmpty()) return
        }
    }

    private fun handleMessage(message: JsonObject) {
        when (message.string("type")) {
            "port" -> {
                val callId = message.string("callId") ?: return
                val port = message.string("port").orEmpty()
                val call = PortCall(callId, message.string("method").orEmpty(), message["args"] as? JsonObject ?: JsonObject(emptyMap()))
                lane(port).trySend(call)
            }
            "response" -> {
                val response = runCatching {
                    json.decodeFromJsonElement(CoreResponseEnvelope.serializer(), message["response"]!!)
                }.getOrElse {
                    logger("error", "Undecodable Core response: ${it.message}")
                    return
                }
                pending.remove(response.requestId)?.complete(response)
            }
            "event" -> runCatching {
                json.decodeFromJsonElement(CoreEventMessage.serializer(), message["event"]!!)
            }.onSuccess { _stateEvents.tryEmit(it) }
                .onFailure { logger("error", "Undecodable Core state event: ${it.message}") }
            "boot" -> booted.complete(message)
            "shutdown" -> stopped.complete(message)
            "log" -> logger(message.string("level") ?: "info", message.string("message").orEmpty())
        }
    }

    private fun lane(port: String): Channel<PortCall> = lanes.getOrPut(port) {
        Channel<PortCall>(Channel.UNLIMITED).also { channel ->
            portScope.launch {
                for (call in channel) {
                    val result = runPortCall(port, call)
                    runtimeScope.launch {
                        callCore("completePortCall", result.toString())
                        pump()
                    }
                }
            }
        }
    }

    private suspend fun runPortCall(port: String, call: PortCall): JsonObject {
        val outcome: Result<JsonElement> = try {
            Result.success(bridge.call(port, call.method, call.args))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }
        return buildJsonObject {
            put("callId", JsonPrimitive(call.callId))
            outcome.onSuccess {
                put("ok", JsonPrimitive(true))
                put("value", it)
            }.onFailure { error ->
                val (code, retryable) = when (error) {
                    is PortCallFailure -> error.code to error.retryable
                    is SecurityException -> CoreErrorCode.PERMISSION_EXPIRED to false
                    else -> CoreErrorCode.FAILED to false
                }
                put("ok", JsonPrimitive(false))
                put("error", buildJsonObject {
                    put("code", JsonPrimitive(code.name))
                    put("message", JsonPrimitive(error.message ?: "$port.${call.method} failed (${error.javaClass.simpleName})"))
                    put("retryable", JsonPrimitive(retryable))
                })
            }
        }
    }

    private fun deliverAudioEvent(eventJson: String) {
        if (callCore("onAudioEngineEvent", eventJson) != null) pump()
    }

    private fun scheduleTimer(delayMs: Long?) {
        timerJob?.cancel()
        timerJob = delayMs?.let { wait ->
            runtimeScope.launch {
                delay(wait.coerceAtLeast(0L))
                timerJob = null
                pump()
            }
        }
    }

    private fun fail(error: Throwable) {
        if (failure != null) return
        val reason = "Music Core runtime failed: ${error.message ?: error.javaClass.simpleName}"
        failure = reason
        logger("error", reason)
        timerJob?.cancel()
        lanes.values.forEach { it.close() }
        pending.forEach { (requestId, response) -> response.complete(errorResponse(requestId, CoreErrorCode.FAILED, reason)) }
        pending.clear()
        val failed = buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("error", JsonPrimitive(reason))
        }
        booted.complete(failed)
        stopped.complete(failed)
    }

    private fun errorResponse(requestId: String, code: CoreErrorCode, message: String, retryable: Boolean = false) =
        CoreResponseEnvelope(
            requestId = requestId,
            epoch = epoch,
            ok = false,
            error = CoreErrorView(code = code, message = message, retryable = retryable)
        )

    private fun loadPortableCoreScript(): String {
        scriptLoader?.let { return it() }
        val context = appContext ?: throw PortableCoreException("No Android context to load $PORTABLE_CORE_ASSET")
        return try {
            context.assets.open(PORTABLE_CORE_PATH).bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            throw PortableCoreException("Missing $PORTABLE_CORE_ASSET; build it with the desktop repo's build:portable-core", t)
        }
    }

    private fun parseObject(text: String): JsonObject =
        json.parseToJsonElement(text) as? JsonObject ?: throw PortableCoreException("Core returned non-object JSON: ${text.take(200)}")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    override fun close() {
        closed = true
        audioSubscription?.cancel()
        audioSubscription = null
        routeSubscription?.cancel()
        routeSubscription = null
        portScope.cancel()
        runtimeScope.cancel()
        runtimeDispatcher.closeAfter {
            timerJob?.cancel()
            lanes.values.forEach { it.close() }
            pending.forEach { (requestId, response) ->
                response.complete(errorResponse(requestId, CoreErrorCode.CANCELLED, "Music Core was closed"))
            }
            pending.clear()
            engine?.close()
            engine = null
        }
    }

    companion object {
        const val PORTABLE_CORE_PATH = "core/music-core.js"
        private const val PORTABLE_CORE_ASSET = "assets/$PORTABLE_CORE_PATH"
        private const val BOOT_TIMEOUT_MS = 30_000L
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L
        private const val MAX_DRAIN_ROUNDS = 4
    }
}

private fun logToAndroid(level: String, message: String) {
    val priority = when (level) {
        "error" -> android.util.Log.ERROR
        "warn" -> android.util.Log.WARN
        "debug" -> android.util.Log.DEBUG
        else -> android.util.Log.INFO
    }
    runCatching { android.util.Log.println(priority, "TwilightCore", message) }
}
