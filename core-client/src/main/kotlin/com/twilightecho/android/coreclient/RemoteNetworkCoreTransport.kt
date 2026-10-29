package com.twilightecho.android.coreclient

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
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
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject

/** Wire protocol versions a side accepts; both ends pick the highest version they share. */
@Serializable
data class RemoteProtocolRange(val min: Int, val max: Int)

/** Versions this phone speaks to a desktop; must overlap the desktop's REMOTE_CORE_PROTOCOL_RANGE. */
val REMOTE_PROTOCOL_RANGE = RemoteProtocolRange(1, 1)

/** The desktop's own audio output, the only zone a desktop serves today (desktop REMOTE_CORE_LOCAL_ZONE). */
const val REMOTE_LOCAL_ZONE = "local"

fun negotiateRemoteProtocol(client: RemoteProtocolRange, server: RemoteProtocolRange): Int? {
    val version = minOf(client.max, server.max)
    return version.takeIf { it >= maxOf(client.min, server.min) }
}

@Serializable
data class RemoteZone(val zoneId: String, val name: String? = null)

@Serializable
data class RemoteCoreServerStatus(
    val serverId: String? = null,
    val serverName: String? = null,
    val pairingMode: String? = null,
    val tls: Boolean = false,
    val app: String = "Twilight Echo",
    val requiresPairing: Boolean = true,
    val paired: Boolean = false,
    val coreBridge: Boolean = false,
    val protocolVersion: Int = CORE_PROTOCOL_VERSION,
    /** Absent on desktops from before negotiation, which speak only [protocolVersion]. */
    val protocolVersions: RemoteProtocolRange? = null,
    val coreBridgeVersion: Int? = null,
    val zones: List<RemoteZone> = emptyList(),
    val capabilities: DesktopCapabilities? = null
)

enum class RemoteConnectionPhase { CONNECTING, CONNECTED, RECONNECTING, PAIRING_REQUIRED, CLOSED }
data class RemoteConnectionState(val phase: RemoteConnectionPhase, val message: String? = null)
class RemoteHttpException(val statusCode: Int) : IllegalStateException("电脑连接返回 HTTP $statusCode")

@Serializable
data class RemoteCoreCredentials(
    val baseUrl: String,
    val bearerToken: String,
    val serverId: String? = null,
    val serverName: String? = null,
    /** SHA-256 of the desktop certificate verified at pairing; a token without one predates TLS and is not used. */
    val certificateSha256: String? = null
)

@Serializable
private data class DesktopPairingTicket(
    val requestId: String,
    val claimSecret: String,
    val expiresAt: Long,
    val serverNonce: String? = null
)
@Serializable
private data class DesktopPairingKey(val requestId: String, val claimSecret: String)
@Serializable
private data class DesktopPairingClaim(val state: String, val token: String? = null)

/**
 * Roon-style Remote Controller `CoreTransport` connecting Android/iOS `CoreClient` directly to a
 * Desktop `CoreApplication` / `CoreRemoteService` over LAN (`/api/pair`, `/api/core/rpc`, `/api/core/events`).
 * Every request goes over TLS to the certificate held by [certificatePin].
 */
class RemoteNetworkCoreTransport(
    rawBaseUrl: String,
    @Volatile var bearerToken: String? = null,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = DefaultCoreClient.DefaultJson,
    val certificatePin: DesktopCertificatePin = DesktopCertificatePin(),
    private val httpExecutor: RemoteHttpExecutor = UrlConnectionRemoteHttpExecutor(certificatePin = certificatePin),
    monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 }
) : CoreTransport, Closeable {
    override val isRemote = true

    /** Version and addressing agreed by [negotiate], added to every RPC on the wire. */
    private class WireAddress(val protocolVersion: Int, val targetId: String?, val zoneId: String?)
    @Volatile private var wire = WireAddress(CORE_PROTOCOL_VERSION, null, null)

    /**
     * Agrees on the highest wire version both sides speak and addresses later requests to this desktop's
     * local zone, so a request can never act on another computer that took over the address.
     * Returns null when the two sides share no version.
     */
    fun negotiate(status: RemoteCoreServerStatus): Int? {
        val server = status.protocolVersions ?: RemoteProtocolRange(status.protocolVersion, status.protocolVersion)
        val version = negotiateRemoteProtocol(REMOTE_PROTOCOL_RANGE, server) ?: return null
        // Desktops that list zones also accept targetId/zoneId; earlier ones reject unknown envelope fields.
        val zone = status.zones.firstOrNull { it.zoneId == REMOTE_LOCAL_ZONE }?.zoneId
        wire = WireAddress(version, status.serverId?.takeIf { zone != null }, zone)
        return version
    }

    private fun wireBody(envelope: CoreRequestEnvelope): String {
        val address = wire
        val fields = json.encodeToJsonElement(CoreRequestEnvelope.serializer(), envelope).jsonObject.toMutableMap()
        fields["protocolVersion"] = JsonPrimitive(address.protocolVersion)
        address.targetId?.let { fields["targetId"] = JsonPrimitive(it) }
        address.zoneId?.let { fields["zoneId"] = JsonPrimitive(it) }
        return json.encodeToString(JsonObject.serializer(), JsonObject(fields))
    }

    val baseUrl: String = normalizeBaseUrl(rawBaseUrl)

    private val closed = AtomicBoolean(false)
    private val rpcBudget = DesktopRpcBudget(monotonicMs)
    private var sseJob: Job? = null
    private val mutableConnection = MutableStateFlow(RemoteConnectionState(RemoteConnectionPhase.CONNECTING))
    val connection = mutableConnection.asStateFlow()

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
                serverId = obj["serverId"]?.jsonPrimitive?.contentOrNull,
                serverName = obj["serverName"]?.jsonPrimitive?.contentOrNull,
                pairingMode = obj["pairingMode"]?.jsonPrimitive?.contentOrNull,
                tls = obj["tls"]?.jsonPrimitive?.booleanOrNull ?: false,
                app = obj["app"]?.jsonPrimitive?.contentOrNull ?: "Twilight Echo",
                requiresPairing = obj["requiresPairing"]?.jsonPrimitive?.booleanOrNull ?: true,
                paired = obj["paired"]?.jsonPrimitive?.booleanOrNull ?: false,
                coreBridge = obj["coreBridge"]?.jsonPrimitive?.booleanOrNull ?: false,
                protocolVersion = obj["protocolVersion"]?.jsonPrimitive?.intOrNull ?: CORE_PROTOCOL_VERSION,
                protocolVersions = obj["protocolVersions"]?.takeIf { it is JsonObject }?.let {
                    runCatching { json.decodeFromJsonElement<RemoteProtocolRange>(it) }.getOrNull()
                },
                coreBridgeVersion = obj["coreBridgeVersion"]?.jsonPrimitive?.intOrNull,
                zones = obj["zones"]?.let { runCatching { json.decodeFromJsonElement<List<RemoteZone>>(it) }.getOrNull() }.orEmpty(),
                capabilities = obj["capabilities"]?.takeIf { it is JsonObject }?.let {
                    json.decodeFromJsonElement<DesktopCapabilities>(it)
                }
            )
        }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * A fresh bearer is issued only after the desktop owner approves this request. The phone commits to a
     * nonce, learns the desktop's nonce, then reveals its own; [waiting] receives the six digits the desktop
     * shows too, derived from the certificate this transport pinned. Matching digits prove no one in between.
     */
    suspend fun pairWithDesktopApproval(deviceName: String, waiting: (verificationCode: String) -> Unit = {}): Result<String> =
        withContext(ioDispatcher) {
        var ticket: DesktopPairingKey? = null
        var completed = false
        try {
            val nonce = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val response = httpExecutor.executePostJson("$baseUrl/api/pair/requests", emptyMap(),
                json.encodeToString(buildJsonObject {
                    put("deviceName", JsonPrimitive(deviceName.take(80)))
                    put("commitment", JsonPrimitive(base64Url(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(nonce.toByteArray(Charsets.UTF_8)))))
                }))
            check(response.statusCode == 202) {
                if (response.statusCode == 429) "电脑收到的连接请求较多，请稍后重试" else "无法申请连接，请更新电脑端后重试"
            }
            val issued = json.decodeFromString<DesktopPairingTicket>(response.body)
            val pending = DesktopPairingKey(issued.requestId, issued.claimSecret)
            ticket = pending
            val serverNonce = issued.serverNonce ?: error("电脑端不支持加密配对，请更新电脑端后重试")
            val fingerprint = certificatePin.fingerprint ?: error("电脑未建立加密连接")
            val revealed = httpExecutor.executePostJson("$baseUrl/api/pair/reveal", emptyMap(),
                json.encodeToString(buildJsonObject {
                    put("requestId", JsonPrimitive(pending.requestId))
                    put("claimSecret", JsonPrimitive(pending.claimSecret))
                    put("nonce", JsonPrimitive(nonce))
                }))
            check(revealed.statusCode == 200) { "连接请求已过期，请重新选择电脑" }
            waiting(remotePairingCode(fingerprint, nonce, serverNonce))
            val token = withTimeout(120_000) {
                var approvedToken: String? = null
                while (approvedToken == null) {
                    delay(1500)
                    val claimResponse = httpExecutor.executePostJson("$baseUrl/api/pair/claim", emptyMap(), json.encodeToString(pending))
                    check(claimResponse.statusCode == 200) { "连接请求已过期，请重新选择电脑" }
                    val claim = json.decodeFromString<DesktopPairingClaim>(claimResponse.body)
                    check(claim.state != "rejected") { "电脑已拒绝连接" }
                    if (claim.state == "approved") approvedToken = claim.token ?: error("电脑未返回连接凭证")
                }
                checkNotNull(approvedToken)
            }
            bearerToken = token
            mutableConnection.value = RemoteConnectionState(RemoteConnectionPhase.CONNECTING)
            completed = true
            Result.success(token)
        } catch (_: TimeoutCancellationException) { Result.failure(IllegalStateException("等待电脑确认超时，请重新选择电脑"))
        } catch (cancel: CancellationException) { throw cancel
        } catch (error: Exception) { Result.failure(error)
        } finally {
            if (!completed && ticket != null) withContext(NonCancellable) {
                runCatching { httpExecutor.executePostJson("$baseUrl/api/pair/cancel", emptyMap(), json.encodeToString(ticket)) }
            }
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
            mutableConnection.value = RemoteConnectionState(RemoteConnectionPhase.CONNECTING)
            token
        }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * Starts the background SSE stream (`GET /api/core/events`) that receives live `core_state`
     * events from the Desktop Core and emits them into [stateEvents].
     */
    fun startEventStream() {
        if (closed.get()) return
        sseJob?.cancel()
        httpExecutor.closeEventStream()
        sseJob = scope.launch(ioDispatcher) {
            var backoff = 1_500L
            while (isActive && !closed.get() && mutableConnection.value.phase != RemoteConnectionPhase.PAIRING_REQUIRED) {
                try {
                    httpExecutor.streamSseEvents(
                        url = "$baseUrl/api/core/events",
                        headers = authHeaders(),
                        isCancelled = { !isActive || closed.get() }
                    ) { eventName, dataLine ->
                        // Existing desktop servers revoke live sessions with an auth event,
                        // without necessarily closing the HTTP stream themselves.
                        if (eventName == "auth" && runCatching {
                            json.parseToJsonElement(dataLine).jsonObject["paired"]?.jsonPrimitive?.booleanOrNull == false
                        }.getOrDefault(false)) throw RemoteHttpException(401)
                        if (mutableConnection.value.phase == RemoteConnectionPhase.PAIRING_REQUIRED) throw RemoteHttpException(401)
                        if (eventName == "core_state" && dataLine.isNotBlank()) {
                            runCatching {
                                json.decodeFromString<CoreEventMessage>(dataLine)
                            }.getOrNull()?.let { eventMsg ->
                                _stateEvents.tryEmit(eventMsg)
                                updateConnection(RemoteConnectionPhase.CONNECTED)
                                backoff = 1_500L
                            }
                        }
                    }
                } catch (cancel: CancellationException) { throw cancel
                } catch (error: Exception) {
                    if (error is RemoteHttpException && error.statusCode == 401) {
                        requirePairing()
                        break
                    }
                }
                if (mutableConnection.value.phase == RemoteConnectionPhase.PAIRING_REQUIRED) break
                if (isActive && !closed.get()) {
                    updateConnection(RemoteConnectionPhase.RECONNECTING, "电脑连接已断开，正在重连")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(15_000L)
                }
            }
        }
    }

    override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope =
        withContext(ioDispatcher) {
            val urgent = envelope.kind == "command" || envelope.name in setOf(
                "core.handshake", "core.getSnapshot", "state.snapshot", "playback.getQueueWindow", "media.resolve")
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
            if (!rpcBudget.acquire(urgent, waitForSlot = envelope.kind == "query"))
                return@withContext CoreResponseEnvelope(requestId = envelope.requestId, epoch = envelope.epoch, ok = false,
                    error = CoreErrorView(CoreErrorCode.BUSY, "电脑请求较多，请稍后重试"))
            if (closed.get()) return@withContext CoreResponseEnvelope(
                requestId = envelope.requestId, epoch = envelope.epoch, ok = false,
                error = CoreErrorView(CoreErrorCode.NOT_READY, "电脑连接已关闭"))
            runCatching {
                val bodyJson = wireBody(envelope)
                var httpRes = httpExecutor.executePostJson(
                    url = "$baseUrl/api/core/rpc",
                    headers = authHeaders(),
                    bodyJson = bodyJson
                )
                if (httpRes.statusCode == 429) {
                    rpcBudget.rateLimited()
                    // Only an explicitly rejected read is replayed; commands are never replayed.
                    if (envelope.kind == "query") {
                        rpcBudget.acquire(urgent)
                        if (closed.get()) return@withContext CoreResponseEnvelope(
                            requestId = envelope.requestId, epoch = envelope.epoch, ok = false,
                            error = CoreErrorView(CoreErrorCode.NOT_READY, "电脑连接已关闭"))
                        httpRes = httpExecutor.executePostJson("$baseUrl/api/core/rpc", authHeaders(), bodyJson)
                        if (httpRes.statusCode == 429) rpcBudget.rateLimited()
                    }
                }
                if (httpRes.statusCode == 401) {
                    requirePairing()
                    return@withContext CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = envelope.epoch,
                        ok = false,
                        error = CoreErrorView(
                            code = CoreErrorCode.FORBIDDEN,
                            message = "配对已失效，请输入电脑上的新配对码"
                        )
                    )
                }
                if (httpRes.statusCode !in 200..299) {
                    return@withContext CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = envelope.epoch,
                        ok = false,
                        error = CoreErrorView(
                            code = if (httpRes.statusCode == 429) CoreErrorCode.BUSY else if (envelope.kind == "command" && httpRes.statusCode >= 500) CoreErrorCode.OUTCOME_UNKNOWN else CoreErrorCode.FAILED,
                            message = if (httpRes.statusCode == 429) "电脑请求较多，请稍后重试" else "电脑连接返回 HTTP ${httpRes.statusCode}",
                            retryable = envelope.kind == "query" && httpRes.statusCode >= 500
                        )
                    )
                }
                json.decodeFromString<CoreResponseEnvelope>(httpRes.body)
            }.getOrElse { err ->
                if (err is CancellationException) throw err
                updateConnection(RemoteConnectionPhase.RECONNECTING, "无法连接电脑，正在重连")
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = envelope.epoch,
                    ok = false,
                    error = CoreErrorView(
                        code = if (envelope.kind == "command") CoreErrorCode.OUTCOME_UNKNOWN else CoreErrorCode.FAILED,
                        message = if (envelope.kind == "command") "操作结果尚未确认，请查看电脑状态后再操作" else "无法连接电脑，请检查网络和远程控制设置",
                        retryable = envelope.kind == "query"
                    )
                )
            }
        }

    /** Asks the desktop to revoke this phone's own token; false when it was not reachable or did not accept. */
    suspend fun forgetOnDesktop(): Boolean = withContext(ioDispatcher) {
        runCatching { httpExecutor.executePostJson("$baseUrl/api/pair/forget", authHeaders(), "{}").statusCode == 200 }
            .getOrDefault(false)
    }

    override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {
        // HTTP RPC calls are bounded; no-op for cancel envelope over stateless HTTP RPC.
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            sseJob?.cancel()
            sseJob = null
            httpExecutor.closeEventStream()
            mutableConnection.value = RemoteConnectionState(RemoteConnectionPhase.CLOSED)
        }
    }

    private fun requirePairing() {
        mutableConnection.update { current ->
            if (current.phase == RemoteConnectionPhase.CLOSED) current
            else RemoteConnectionState(RemoteConnectionPhase.PAIRING_REQUIRED, "配对已失效，请输入电脑上的新配对码")
        }
        httpExecutor.closeEventStream()
    }

    private fun updateConnection(phase: RemoteConnectionPhase, message: String? = null) {
        mutableConnection.update { current ->
            if (current.phase == RemoteConnectionPhase.PAIRING_REQUIRED || current.phase == RemoteConnectionPhase.CLOSED) current
            else RemoteConnectionState(phase, message)
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
        /**
         * Desktop addresses always use TLS: the desktop serves both protocols on one port, so an address
         * saved or typed with `http://` names the same endpoint and is upgraded rather than trusted in plain text.
         */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Desktop Core host/URL must not be blank" }
            val value = when {
                trimmed.startsWith("https://") -> trimmed
                trimmed.startsWith("http://") -> "https://" + trimmed.removePrefix("http://")
                else -> "https://$trimmed"
            }
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.isNullOrEmpty() && (uri.port == -1 || uri.port in 1..65535)) {
                "请输入电脑的 IP 或主机地址和端口，例如 192.168.1.20:18492"
            }
            return value
        }
    }
}

data class RemoteHttpSimpleResponse(
    val statusCode: Int,
    val body: String
)

interface RemoteHttpExecutor {
    fun closeEventStream() {}
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
    private val readTimeoutMs: Int = 10_000,
    private val certificatePin: DesktopCertificatePin? = null
) : RemoteHttpExecutor {
    private val eventConnection = AtomicReference<HttpURLConnection?>(null)
    override fun closeEventStream() { eventConnection.getAndSet(null)?.disconnect() }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).also { conn ->
        if (certificatePin != null && conn is HttpsURLConnection) {
            conn.sslSocketFactory = certificatePin.sslSocketFactory
            conn.hostnameVerifier = certificatePin.hostnameVerifier
        }
    }

    override fun executeGet(url: String, headers: Map<String, String>): RemoteHttpSimpleResponse {
        val conn = open(url).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
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
        val conn = open(url).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
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
        val conn = open(url).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = 45_000
            setRequestProperty("Accept", "text/event-stream")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        eventConnection.set(conn)
        try {
            val status = conn.responseCode
            if (status !in 200..299) throw RemoteHttpException(status)
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
            eventConnection.compareAndSet(conn, null)
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
