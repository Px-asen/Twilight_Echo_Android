package com.twilightecho.android.runtimehost

import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.PlayMode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A port call the Core must see as a typed wire error rather than a generic failure. */
class PortCallFailure(
    val code: CoreErrorCode,
    message: String,
    val retryable: Boolean = false
) : Exception(message)

/**
 * Executes the port calls the embedded Core puts in its outbox (`{type:'port', port, method, args}`)
 * against the injected [CoreHostPorts]. Arguments are validated strictly: a malformed call is an
 * `INVALID_REQUEST`, never a silently defaulted or partially applied operation.
 *
 * Calls for one port arrive in order (the host runs one FIFO lane per port), so the queue the
 * audio engine was last given is consistent with the `play` that follows it.
 */
class CorePortBridge(
    private val ports: CoreHostPorts,
    private val json: Json = DefaultCoreClient.DefaultJson
) {
    @Volatile
    private var loadedQueue: List<CoreTrackView> = emptyList()

    suspend fun call(port: String, method: String, args: JsonObject): JsonElement = when (port) {
        "sessionStore" -> sessionStore(method, args)
        "resourceAccess" -> resourceAccess(method, args)
        "audioEngine" -> audioEngine(method, args)
        "network" -> network(method, args)
        "audioDevice" -> audioDevice(method)
        else -> unsupported(port, method)
    }

    private suspend fun sessionStore(method: String, args: JsonObject): JsonElement = when (method) {
        "load" -> ports.sessionStore.load()?.let { stored ->
            buildJsonObject {
                put("revision", JsonPrimitive(stored.revision))
                put("payloadJson", JsonPrimitive(stored.payloadJson))
            }
        } ?: JsonNull
        "save" -> when (val result = ports.sessionStore.save(args.requireString("payloadJson"), args.optString("expectedRevision"))) {
            is SaveSessionResult.Ok -> buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("revision", JsonPrimitive(result.revision))
            }
            is SaveSessionResult.Conflict -> buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("reason", JsonPrimitive(result.reason))
                result.actualRevision?.let { put("actualRevision", JsonPrimitive(it)) }
            }
        }
        else -> unsupported("sessionStore", method)
    }

    private suspend fun resourceAccess(method: String, args: JsonObject): JsonElement {
        val resourceId = args.requireString("resourceId")
        return when (method) {
            "resolveNativeUri" -> JsonPrimitive(ports.resourceAccess.resolveNativeUri(resourceId).orThrow())
            "stat" -> json.encodeToJsonElement(ResourceStat.serializer(), ports.resourceAccess.stat(resourceId).orThrow())
            else -> unsupported("resourceAccess", method)
        }
    }

    private suspend fun audioEngine(method: String, args: JsonObject): JsonElement {
        val engine = ports.audioEngine
        when (method) {
            "loadQueue" -> {
                val rows = args["tracks"] as? JsonArray ?: invalid("audioEngine.loadQueue requires tracks")
                val tracks = rows.mapIndexed { index, row ->
                    runCatching { json.decodeFromJsonElement(CoreTrackView.serializer(), row) }
                        .getOrElse { invalid("Queue item $index is not a playable track: ${it.message}") }
                }
                val startIndex = args.requireLong("startIndex").toInt()
                if (tracks.isNotEmpty() && startIndex !in tracks.indices) invalid("startIndex $startIndex is outside the queue")
                engine.loadQueue(tracks, startIndex, args.optLong("queueGeneration") ?: 0L)
                loadedQueue = tracks
            }
            "play" -> {
                val position = args.requireDouble("position")
                args.optString("source")?.let { source ->
                    val index = engine.getSnapshot().queueIndex
                    if (loadedQueue.getOrNull(index)?.source != source) {
                        throw PortCallFailure(CoreErrorCode.SOURCE_CHANGED, "The audio engine no longer holds the requested track at queue index $index")
                    }
                }
                engine.play(position)
            }
            "pause" -> engine.pause()
            "stop" -> engine.stop()
            "seek" -> engine.seek(args.requireLong("positionMs").coerceAtLeast(0L))
            "setVolume" -> {
                val volume = args.requireDouble("volume")
                if (volume !in 0.0..1.0) invalid("Volume $volume is outside 0..1")
                engine.setVolume(volume)
            }
            "setPlayMode" -> {
                val mode = args.requireString("mode")
                engine.setPlayMode(PlayMode.entries.firstOrNull { it.toWireValue() == mode } ?: invalid("Unknown play mode: $mode"))
            }
            "setDspConfig" -> engine.setDspConfig(args.requireString("configJson"))
            "getSnapshot" -> return json.encodeToJsonElement(AudioSnapshot.serializer(), engine.getSnapshot())
            else -> unsupported("audioEngine", method)
        }
        return JsonPrimitive(true)
    }

    private suspend fun network(method: String, args: JsonObject): JsonElement {
        if (method != "request") unsupported("network", method)
        val headers = (args["headers"] as? JsonObject)?.mapValues { (name, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("Header $name must be a string")
        }.orEmpty()
        val spec = NetworkRequestSpec(
            url = args.requireString("url"),
            method = args.optString("method") ?: "GET",
            headers = headers,
            bodyUtf8 = args.optString("bodyUtf8"),
            contentType = args.optString("contentType") ?: "application/json; charset=utf-8",
            timeoutMs = args.optLong("timeoutMs")
        )
        return json.encodeToJsonElement(NetworkResponsePayload.serializer(), ports.networkClient.execute(spec).orThrow())
    }

    private fun audioDevice(method: String): JsonElement = when (method) {
        "getActiveRoute" -> json.encodeToJsonElement(AudioDeviceRouteInfo.serializer(), ports.audioDevice.getActiveRoute())
        "listOutputDevices" -> json.encodeToJsonElement(
            ListSerializer(AudioDeviceRouteInfo.serializer()),
            ports.audioDevice.listOutputDevices()
        )
        else -> unsupported("audioDevice", method)
    }

    private fun <T> ResourceAccessResult<T>.orThrow(): T = when (this) {
        is ResourceAccessResult.Ok -> value
        is ResourceAccessResult.Error -> throw PortCallFailure(code, message, retryable)
    }

    private fun unsupported(port: String, method: String): Nothing =
        throw PortCallFailure(CoreErrorCode.UNSUPPORTED_CAPABILITY, "Unknown host port: $port.$method")

    private fun invalid(message: String): Nothing = throw PortCallFailure(CoreErrorCode.INVALID_REQUEST, message)

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.optString(key: String): String? =
        primitive(key)?.let { if (it.isString) it.content else invalid("$key must be a string") }

    private fun JsonObject.requireString(key: String): String = optString(key) ?: invalid("$key is required")

    private fun JsonObject.optLong(key: String): Long? = primitive(key)?.let {
        if (it.isString || it.booleanOrNull != null) invalid("$key must be a number")
        it.longOrNull ?: it.doubleOrNull?.takeIf { d -> d % 1.0 == 0.0 }?.toLong() ?: invalid("$key must be an integer")
    }

    private fun JsonObject.requireLong(key: String): Long = optLong(key) ?: invalid("$key is required")

    private fun JsonObject.requireDouble(key: String): Double = primitive(key)?.let {
        if (it.isString) invalid("$key must be a number")
        it.doubleOrNull?.takeIf { d -> d.isFinite() } ?: invalid("$key must be a finite number")
    } ?: invalid("$key is required")
}
