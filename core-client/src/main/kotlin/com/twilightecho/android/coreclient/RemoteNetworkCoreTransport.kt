package com.twilightecho.android.coreclient

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class RemoteCoreServerStatus(
    val app: String = "Twilight Echo",
    val requiresPairing: Boolean = true,
    val paired: Boolean = false,
    val coreBridge: Boolean = false,
    val protocolVersion: Int = CORE_PROTOCOL_VERSION
)

@Serializable
data class RemoteCoreCredentials(
    val baseUrl: String,
    val bearerToken: String
)

/**
 * Roon-style Remote Controller `CoreTransport` connecting Android/iOS `CoreClient` directly to a
 * Desktop `CoreApplication` / `CoreRemoteService` over LAN (`/api/pair`, `/api/core/rpc`, `/api/core/events`).
 */
class RemoteNetworkCoreTransport(
    rawBaseUrl: String,
    @Volatile var bearerToken: String? = null,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = DefaultCoreClient.DefaultJson,
    private val httpExecutor: RemoteHttpExecutor = UrlConnectionRemoteHttpExecutor()
) : CoreTransport, Closeable {

    val baseUrl: String = normalizeBaseUrl(rawBaseUrl)

    private val closed = AtomicBoolean(false)
    private var sseJob: Job? = null

    private val _stateEvents = MutableSharedFlow<CoreEventMessage>(extraBufferCapacity = 64)
    override val stateEvents: Flow<CoreEventMessage> = _stateEvents.asSharedFlow()

    /**
     * Checks whether a Desktop Core is reachable at [baseUrl]/api/status.
     */
    suspend fun checkStatus(): Result<RemoteCoreServerStatus> = withContext(ioDispatcher) {
        runCatching {
            val response = httpExecutor.executeGet(
                url = "$baseUrl/api/status",
                headers = authHeaders()
            )
            if (response.statusCode !in 200..299) {
                throw IllegalStateException("Desktop Core returned HTTP ${response.statusCode}")
            }
            val obj = json.parseToJsonElement(response.body).jsonObject
            RemoteCoreServerStatus(
                app = obj["app"]?.jsonPrimitive?.contentOrNull ?: "Twilight Echo",
                requiresPairing = obj["requiresPairing"]?.jsonPrimitive?.booleanOrNull ?: true,
                paired = obj["paired"]?.jsonPrimitive?.booleanOrNull ?: false,
                coreBridge = obj["coreBridge"]?.jsonPrimitive?.booleanOrNull ?: false,
                protocolVersion = obj["protocolVersion"]?.jsonPrimitive?.intOrNull ?: CORE_PROTOCOL_VERSION
            )
        }
    }

    /**
     * Pairs with the Desktop Core using the 6-digit PIN shown in Desktop Settings -> Remote Control.
     */
    suspend fun pairWithPin(pin: String): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val payload = JsonObject(mapOf("pin" to JsonPrimitive(pin.trim())))
            val response = httpExecutor.executePostJson(
                url = "$baseUrl/api/pair",
                headers = emptyMap(),
                bodyJson = json.encodeToString(payload)
            )
            if (response.statusCode !in 200..299) {
                val errObj = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
                val reason = errObj?.get("error")?.jsonPrimitive?.contentOrNull ?: "HTTP ${response.statusCode}"
                throw IllegalStateException("PIN pairing failed: $reason")
            }
            val obj = json.parseToJsonElement(response.body).jsonObject
            val token = obj["token"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("Desktop Core pair response missing token")
            bearerToken = token
            token
        }
    }

    /**
     * Starts the background SSE stream (`GET /api/core/events`) that receives live `core_state`
     * events from the Desktop Core and emits them into [stateEvents].
     */
    fun startEventStream() {
        if (closed.get()) return
        sseJob?.cancel()
        sseJob = scope.launch(ioDispatcher) {
            while (isActive && !closed.get()) {
                runCatching {
                    httpExecutor.streamSseEvents(
                        url = "$baseUrl/api/core/events",
                        headers = authHeaders(),
                        isCancelled = { !isActive || closed.get() }
                    ) { eventName, dataLine ->
                        if (eventName == "core_state" && dataLine.isNotBlank()) {
                            runCatching {
                                json.decodeFromString<CoreEventMessage>(dataLine)
                            }.getOrNull()?.let { eventMsg ->
                                _stateEvents.tryEmit(eventMsg)
                            }
                        }
                    }
                }
                if (isActive && !closed.get()) {
                    delay(1_500L)
                }
            }
        }
    }

    override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope =
        withContext(ioDispatcher) {
            if (closed.get()) {
                return@withContext CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = envelope.epoch,
                    ok = false,
                    error = CoreErrorView(
                        code = CoreErrorCode.NOT_READY,
                        message = "RemoteNetworkCoreTransport is closed"
                    )
                )
            }
            runCatching {
                val bodyJson = json.encodeToString(envelope)
                val httpRes = httpExecutor.executePostJson(
                    url = "$baseUrl/api/core/rpc",
                    headers = authHeaders(),
                    bodyJson = bodyJson
                )
                if (httpRes.statusCode == 401) {
                    return@withContext CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = envelope.epoch,
                        ok = false,
                        error = CoreErrorView(
                            code = CoreErrorCode.FORBIDDEN,
                            message = "Desktop Core authorization rejected (401); re-pair with PIN"
                        )
                    )
                }
                if (httpRes.statusCode !in 200..299) {
                    return@withContext CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = envelope.epoch,
                        ok = false,
                        error = CoreErrorView(
                            code = CoreErrorCode.FAILED,
                            message = "Desktop Core HTTP ${httpRes.statusCode}: ${httpRes.body}",
                            retryable = httpRes.statusCode >= 500
                        )
                    )
                }
                json.decodeFromString<CoreResponseEnvelope>(httpRes.body)
            }.getOrElse { err ->
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = envelope.epoch,
                    ok = false,
                    error = CoreErrorView(
                        code = CoreErrorCode.OUTCOME_UNKNOWN,
                        message = "Network error communicating with Desktop Core: ${err.message}",
                        retryable = true
                    )
                )
            }
        }

    override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {
        // HTTP RPC calls are bounded; no-op for cancel envelope over stateless HTTP RPC.
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            sseJob?.cancel()
            sseJob = null
        }
    }

    private fun authHeaders(): Map<String, String> {
        val token = bearerToken?.trim()
        return if (!token.isNullOrEmpty()) {
            mapOf("Authorization" to "Bearer $token")
        } else {
            emptyMap()
        }
    }

    companion object {
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Desktop Core host/URL must not be blank" }
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }
    }
}

data class RemoteHttpSimpleResponse(
    val statusCode: Int,
    val body: String
)

interface RemoteHttpExecutor {
    fun executeGet(url: String, headers: Map<String, String>): RemoteHttpSimpleResponse
    fun executePostJson(url: String, headers: Map<String, String>, bodyJson: String): RemoteHttpSimpleResponse
    fun streamSseEvents(
        url: String,
        headers: Map<String, String>,
        isCancelled: () -> Boolean,
        onEvent: (eventName: String, dataLine: String) -> Unit
    )
}

class UrlConnectionRemoteHttpExecutor(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000
) : RemoteHttpExecutor {

    override fun executeGet(url: String, headers: Map<String, String>): RemoteHttpSimpleResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return readHttpResponse(conn)
    }

    override fun executePostJson(
        url: String,
        headers: Map<String, String>,
        bodyJson: String
    ): RemoteHttpSimpleResponse {
        val bytes = bodyJson.toByteArray(StandardCharsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Content-Length", bytes.size.toString())
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { out ->
            out.write(bytes)
            out.flush()
        }
        return readHttpResponse(conn)
    }

    override fun streamSseEvents(
        url: String,
        headers: Map<String, String>,
        isCancelled: () -> Boolean,
        onEvent: (eventName: String, dataLine: String) -> Unit
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = 45_000
            setRequestProperty("Accept", "text/event-stream")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val status = conn.responseCode
            if (status !in 200..299) return
            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                var currentEvent = "message"
                val dataBuffer = StringBuilder()
                while (!isCancelled()) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (dataBuffer.isNotEmpty()) {
                                onEvent(currentEvent, dataBuffer.toString())
                                dataBuffer.setLength(0)
                            }
                            currentEvent = "message"
                        }
                        line.startsWith("event:") -> {
                            currentEvent = line.removePrefix("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                            dataBuffer.append(line.removePrefix("data:").trim())
                        }
                    }
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun readHttpResponse(conn: HttpURLConnection): RemoteHttpSimpleResponse {
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            RemoteHttpSimpleResponse(statusCode = code, body = body)
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
