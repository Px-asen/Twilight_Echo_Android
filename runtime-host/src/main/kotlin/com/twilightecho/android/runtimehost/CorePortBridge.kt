package com.twilightecho.android.runtimehost

import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient. DefaultCoreClient
import com.twilightecho.android.coreclient.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Connects JavaScript Port invocations (`__TWILIGHT_HOST_BRIDGE__.invokePort(...)`) to the
 * injected Kotlin `CoreHostPorts` using structured JSON envelopes (Sections 8.1 & 9.6).
 */
class CorePortBridge(
    private val ports: CoreHostPorts,
    private val runtimeScope: CoroutineScope,
    private val onStateEventJson: (String) -> Unit,
    private val json: Json = DefaultCoreClient.DefaultJson
) : JsHostCallbackBridge {

    override fun invokePort(portName: String, methodName: String, argsJson: String): String {
        val args = runCatching {
            json.parseToJsonElement(argsJson).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }

        return try {
            when (portName) {
                "clock" -> handleClockPort(methodName)
                "sessionStore" -> handleSessionStorePort(methodName, args)
                "resourceAccess" -> handleResourceAccessPort(methodName, args)
                "audioEngine" -> handleAudioEnginePort(methodName, args)
                else -> encodeError(
                    CoreErrorCode.UNSUPPORTED_CAPABILITY,
                    "Unknown host port: $portName.$methodName"
                )
            }
        } catch (t: Throwable) {
            encodeError(
                CoreErrorCode.FAILED,
                "Port exception in $portName.$methodName: ${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    override fun emitCoreEvent(eventJson: String) {
        onStateEventJson(eventJson)
    }

    private fun handleClockPort(methodName: String): String = when (methodName) {
        "nowUnixMs" -> encodeOk(JsonPrimitive(ports.clock.nowUnixMs()))
        "nowMonotonicMs" -> encodeOk(JsonPrimitive(ports.clock.nowMonotonicMs()))
        else -> encodeError(CoreErrorCode.INVALID_REQUEST, "Unknown clock method: $methodName")
    }

    private fun handleSessionStorePort(methodName: String, args: JsonObject): String = runBlocking {
        when (methodName) {
            "load" -> {
                val stored = ports.sessionStore.load()
                if (stored == null) {
                    encodeOk( kotlinx.serialization.json.JsonNull)
                } else {
                    encodeOk(
                        buildJsonObject {
                            put("revision", JsonPrimitive(stored.revision))
                            put("payloadJson", JsonPrimitive(stored.payloadJson))
                        }
                    )
                }
            }
            "save" -> {
                val payloadJson = args["payloadJson"]?.jsonPrimitive?.contentOrNull ?: "{}"
                val expectedRevision = args["expectedRevision"]?.jsonPrimitive?.contentOrNull
                when (val result = ports.sessionStore.save(payloadJson, expectedRevision)) {
                    is SaveSessionResult.Ok -> encodeOk(
                        buildJsonObject {
                            put("ok", JsonPrimitive(true))
                            put("revision", JsonPrimitive(result.revision))
                        }
                    )
                    is SaveSessionResult.Conflict -> encodeOk(
                        buildJsonObject {
                            put("ok", JsonPrimitive(false))
                            put("reason", JsonPrimitive("conflict"))
                            result.actualRevision?.let { put("actualRevision", JsonPrimitive(it)) }
                        }
                    )
                }
            }
            else -> encodeError(CoreErrorCode.INVALID_REQUEST, "Unknown sessionStore method: $methodName")
        }
    }

    private fun handleResourceAccessPort(methodName: String, args: JsonObject): String = runBlocking {
        val resourceId = args["resourceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        when (methodName) {
            "stat" -> {
                when (val res = ports.resourceAccess.stat(resourceId)) {
                    is ResourceAccessResult.Ok -> encodeOk(
                        json.parseToJsonElement(json.encodeToString(res.value))
                    )
                    is ResourceAccessResult.Error -> encodeError(res.code, res.message, res.retryable)
                }
            }
            "resolveNativeUri" -> {
                when (val res = ports.resourceAccess.resolveNativeUri(resourceId)) {
                    is ResourceAccessResult.Ok -> encodeOk(JsonPrimitive(res.value))
                    is ResourceAccessResult.Error -> encodeError(res.code, res.message, res.retryable)
                }
            }
            else -> encodeError(CoreErrorCode.INVALID_REQUEST, "Unknown resourceAccess method: $methodName")
        }
    }

    private fun handleAudioEnginePort(methodName: String, args: JsonObject): String = runBlocking {
        when (methodName) {
            "load" -> {
                val resourceId = args["resourceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val nativeUri = args["nativeUri"]?.jsonPrimitive?.contentOrNull ?: resourceId
                val mimeType = args["mimeType"]?.jsonPrimitive?.contentOrNull
                val startTime = args["startTimeSeconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                ports.audioEngine.load(
                    AudioSourceDescriptor(
                        resourceId = resourceId,
                        nativeUri = nativeUri,
                        mimeType = mimeType,
                        startTimeSeconds = startTime
                    )
                )
                encodeOk(JsonPrimitive(true))
            }
            "play" -> {
                val position = args["position"]?.jsonPrimitive?.doubleOrNull
                ports.audioEngine.play(position)
                encodeOk(JsonPrimitive(true))
            }
            "pause" -> {
                ports.audioEngine.pause()
                encodeOk(JsonPrimitive(true))
            }
            "stop" -> {
                ports.audioEngine.stop()
                encodeOk(JsonPrimitive(true))
            }
            "seek" -> {
                val positionMs = args["positionMs"]?.jsonPrimitive?.longOrNull
                    ?: ((args["position"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000.0).toLong()
                ports.audioEngine.seek(positionMs)
                encodeOk(JsonPrimitive(true))
            }
            "setVolume" -> {
                val volume = args["volume"]?.jsonPrimitive?.doubleOrNull ?: 1.0
                ports.audioEngine.setVolume(volume)
                encodeOk(JsonPrimitive(true))
            }
            "setPlayMode" -> {
                val modeStr = args["mode"]?.jsonPrimitive?.contentOrNull
                ports.audioEngine.setPlayMode(PlayMode.fromWireValue(modeStr))
                encodeOk(JsonPrimitive(true))
            }
            "loadQueue" -> {
                val startIndex = args["startIndex"]?.jsonPrimitive?.intOrNull ?: 0
                val tracks = args["tracks"]?.jsonArray?.mapNotNull { elem ->
                    runCatching { json.decodeFromJsonElement<CoreTrackView>(elem) }.getOrNull()
                }.orEmpty()
                ports.audioEngine.loadQueue(tracks, startIndex)
                encodeOk(JsonPrimitive(true))
            }
            "selectQueueIndex" -> {
                val index = args["index"]?.jsonPrimitive?.intOrNull ?: 0
                ports.audioEngine.selectQueueIndex(index)
                encodeOk(JsonPrimitive(true))
            }
            "next" -> {
                ports.audioEngine.next()
                encodeOk(JsonPrimitive(true))
            }
            "previous" -> {
                ports.audioEngine.previous()
                encodeOk(JsonPrimitive(true))
            }
            "getSnapshot" -> {
                val snap = ports.audioEngine.getSnapshot()
                encodeOk(json.parseToJsonElement(json.encodeToString(snap)))
            }
            else -> encodeError(CoreErrorCode.INVALID_REQUEST, "Unknown audioEngine method: $methodName")
        }
    }

    fun dispatchAudioEventToCore(event: AudioEvent, notifyJs: (String) -> Unit) {
        runtimeScope.launch {
            val payloadJson = json.encodeToString(event)
            notifyJs(payloadJson)
        }
    }

    private fun encodeOk(value: kotlinx.serialization.json.JsonElement): String =
        buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("value", value)
        }.toString()

    private fun encodeError(
        code: CoreErrorCode,
        message: String,
        retryable: Boolean = false
    ): String = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put(
            "error",
            buildJsonObject {
                put("code", JsonPrimitive(code.name))
                put("message", JsonPrimitive(message))
                put("retryable", JsonPrimitive(retryable))
            }
        )
    }.toString()
}
