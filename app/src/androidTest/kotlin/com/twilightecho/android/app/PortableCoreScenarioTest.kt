package com.twilightecho.android.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.coreclient.CoreRequestEnvelope
import com.twilightecho.android.coreclient.CoreResponseEnvelope
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.PlayMode
import com.twilightecho.android.runtimehost.AudioDeviceRouteInfo
import com.twilightecho.android.runtimehost.AudioDevicePort
import com.twilightecho.android.runtimehost.AudioEnginePort
import com.twilightecho.android.runtimehost.AudioEvent
import com.twilightecho.android.runtimehost.AudioSnapshot
import com.twilightecho.android.runtimehost.AudioSourceDescriptor
import com.twilightecho.android.runtimehost.CancelHandle
import com.twilightecho.android.runtimehost.ClockPort
import com.twilightecho.android.runtimehost.CoreHostPorts
import com.twilightecho.android.runtimehost.NetworkClientPort
import com.twilightecho.android.runtimehost.NetworkRequestSpec
import com.twilightecho.android.runtimehost.NetworkResponsePayload
import com.twilightecho.android.runtimehost.QuickJsRuntimeHost
import com.twilightecho.android.runtimehost.ResourceAccessPort
import com.twilightecho.android.runtimehost.ResourceAccessResult
import com.twilightecho.android.runtimehost.ResourceId
import com.twilightecho.android.runtimehost.ResourceStat
import com.twilightecho.android.runtimehost.SaveSessionResult
import com.twilightecho.android.runtimehost.SchedulerPort
import com.twilightecho.android.runtimehost.SessionStorePort
import com.twilightecho.android.runtimehost.StoredSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Replays the desktop repo's `scripts/fixtures/portable-core-scenarios.json` against the shipped
 * `music-core.js` in real QuickJS, through [QuickJsRuntimeHost] and its Kotlin port bridge.
 * `scripts/portable-core-scenarios.test.cjs` replays the same file in Node; the fakes below
 * follow that runner's platform exactly. Node settles the Core between steps; here port calls
 * and audio events run on other threads, so steps that change the platform first wait for the
 * ports to go quiet, and the `expect*` state checks retry until they match.
 */
@RunWith(AndroidJUnit4::class)
class PortableCoreScenarioTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val json = DefaultCoreClient.DefaultJson
    private val corpus: JsonObject by lazy {
        instrumentation.context.assets.open("core/portable-core-scenarios.json").bufferedReader().use {
            json.parseToJsonElement(it.readText()).jsonObject
        }
    }

    @Test
    fun replaysTheSharedScenarioCorpus() = runBlocking {
        val scenarios = corpus["scenarios"]!!.jsonArray.map { it.jsonObject }
        val failures = scenarios.mapNotNull { scenario ->
            val name = scenario["name"]!!.jsonPrimitive.content
            runCatching { replay(scenario) }.exceptionOrNull()?.let { "$name: ${it.message}" }
        }
        assertTrue("${failures.size}/${scenarios.size} scenarios failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private class Context(var snapshot: JsonElement? = null, var value: JsonElement? = null)

    @Volatile private var lastPortActivity = 0L

    private fun touch() {
        lastPortActivity = android.os.SystemClock.elapsedRealtime()
    }

    /** Waits until no port has been called for [QUIET_MS], like Node's `settle()` between steps. */
    private suspend fun settle() {
        withTimeout(EXPECT_TIMEOUT_MS) {
            while (android.os.SystemClock.elapsedRealtime() - lastPortActivity < QUIET_MS) delay(QUIET_MS / 3)
        }
    }

    private suspend fun replay(scenario: JsonObject) {
        val platform = FakePlatform(scenario)
        val context = Context()
        var host = platform.boot()
        var epoch = host.epoch()
        var counter = 0
        try {
            for ((index, step) in expandSteps(scenario["steps"]!!.jsonArray).withIndex()) {
                val label = "step $index ${step.toString().take(120)}"
                when {
                    "request" in step -> {
                        val name = step.string("request")!!
                        val response = host.sendRequest(
                            CoreRequestEnvelope(
                                protocolVersion = step["protocolVersion"]?.jsonPrimitive?.intOrNull ?: 1,
                                requestId = "scenario-${++counter}",
                                commandId = step.string("commandId"),
                                epoch = step.string("epoch") ?: epoch,
                                expectedRevision = step["expectedRevision"]?.let { resolve(it, context).jsonPrimitive.longOrNull },
                                kind = step.string("kind") ?: "command",
                                name = name,
                                payload = resolve(expandTracks(step["payload"] ?: JsonObject(emptyMap())), context).jsonObject
                            )
                        )
                        val actual = json.encodeToJsonElement(CoreResponseEnvelope.serializer(), response)
                        step["expect"]?.let { expected -> mismatch(expected, actual, "$")?.let { error("$label: $it\nactual: ${actual.toString().take(2000)}") } }
                        if (response.ok) {
                            context.value = response.value
                            val value = response.value as? JsonObject
                            if (name == "core.getSnapshot") context.snapshot = value
                            else (value?.get("snapshot") as? JsonObject)?.let { context.snapshot = it }
                        }
                    }
                    "expectSnapshot" in step -> eventually(label, step["expectSnapshot"]!!) {
                        host.snapshotValue(epoch).also { context.snapshot = it }
                    }
                    "expectAudio" in step -> eventually(label, step["expectAudio"]!!) {
                        json.encodeToJsonElement(AudioSnapshot.serializer(), platform.audio.snapshot)
                    }
                    "expectStored" in step -> eventually(label, step["expectStored"]!!) {
                        platform.store.stored?.payloadJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull
                    }
                    "expectNetworkUrl" in step -> {
                        val url = platform.network.lastUrl.orEmpty()
                        check(Regex(step.string("expectNetworkUrl")!!).containsMatchIn(url)) { "$label: network url was $url" }
                    }
                    "platformAudio" in step -> settle().also { platform.audio.patch(step["platformAudio"]!!.jsonObject) }
                    "audioEvent" in step -> settle().also { platform.audio.emitScenarioEvent(step["audioEvent"]!!.jsonObject) }
                    "revoke" in step -> settle().also {
                        step["revoke"]!!.jsonArray.forEach { platform.resources.revoked += it.jsonPrimitive.content }
                    }
                    "network" in step -> settle().also { platform.network.canned = step["network"]!!.jsonObject }
                    "wait" in step -> delay(step["wait"]!!.jsonPrimitive.longOrNull ?: 0L)
                    "restart" in step -> {
                        settle()
                        host.shutdown()
                        host = platform.boot()
                        epoch = host.epoch()
                    }
                    else -> error("$label: unknown step")
                }
            }
        } finally {
            host.close()
        }
    }

    private suspend fun QuickJsRuntimeHost.snapshotValue(epoch: String): JsonElement {
        val response = sendRequest(CoreRequestEnvelope(requestId = "snapshot-${System.nanoTime()}", epoch = epoch, kind = "query", name = "core.getSnapshot"))
        check(response.ok) { "core.getSnapshot failed: ${response.error}" }
        return response.value!!
    }

    private suspend fun QuickJsRuntimeHost.epoch(): String =
        snapshotValue("epoch-boot").jsonObject["epoch"]!!.jsonPrimitive.content

    private suspend fun eventually(label: String, expected: JsonElement, read: suspend () -> JsonElement) {
        var last: String? = null
        try {
            withTimeout(EXPECT_TIMEOUT_MS) {
                while (true) {
                    val actual = read()
                    last = mismatch(expected, actual, "$")?.let { "$it\nactual: ${actual.toString().take(2000)}" } ?: return@withTimeout
                    delay(25)
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            error("$label: $last")
        }
    }

    // ---- Corpus expansion and matching: same semantics as portable-core-scenarios.test.cjs ----

    private fun expandSteps(steps: JsonArray): List<JsonObject> = steps.flatMap { raw ->
        val step = raw.jsonObject
        step.string("\$fragment")?.let { expandSteps(corpus["fragments"]!!.jsonObject[it]!!.jsonArray) } ?: listOf(step)
    }

    private fun expandTracks(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::expandTracks))
        is JsonObject -> value.string("\$track")?.let { corpus["tracks"]!!.jsonObject[it] ?: error("unknown \$track $it") }
            ?: JsonObject(value.mapValues { expandTracks(it.value) })
        else -> value
    }

    private fun resolve(value: JsonElement, context: Context): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map { resolve(it, context) })
        is JsonObject -> value.string("\$from")?.let { path ->
            val root = mapOf("snapshot" to context.snapshot, "value" to context.value)
            val segments = path.split('.')
            var found: JsonElement? = root[segments.first()]
            for (segment in segments.drop(1)) {
                found = when (val current = found) {
                    is JsonObject -> current[segment]
                    is JsonArray -> segment.toIntOrNull()?.let(current::getOrNull)
                    else -> null
                }
            }
            val resolved = found ?: error("\$from $path is not available")
            value["\$add"]?.jsonPrimitive?.longOrNull?.let { JsonPrimitive(resolved.jsonPrimitive.longOrNull!! + it) } ?: resolved
        } ?: JsonObject(value.mapValues { resolve(it.value, context) })
        else -> value
    }

    private fun mismatch(expected: JsonElement, actual: JsonElement?, at: String): String? {
        if (expected is JsonObject && "\$approx" in expected) {
            val target = expected["\$approx"]!!.jsonPrimitive.doubleOrNull!!
            val tolerance = expected["\$tolerance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val number = (actual as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            return if (number != null && abs(number - target) <= tolerance) null else "$at: expected $target±$tolerance, got $actual"
        }
        return when (expected) {
            is JsonArray -> {
                if (actual !is JsonArray) return "$at: expected an array, got $actual"
                if (actual.size != expected.size) return "$at: expected ${expected.size} items, got ${actual.size}"
                expected.indices.firstNotNullOfOrNull { mismatch(expected[it], actual[it], "$at[$it]") }
            }
            is JsonObject -> {
                if (actual !is JsonObject) return "$at: expected an object, got $actual"
                expected.entries.firstNotNullOfOrNull { (key, item) -> mismatch(item, actual[key], "$at.$key") }
            }
            is JsonPrimitive -> if (primitiveEquals(expected, actual)) null else "$at: expected $expected, got $actual"
        }
    }

    private fun primitiveEquals(expected: JsonPrimitive, actual: JsonElement?): Boolean {
        if (expected is JsonNull) return actual == null || actual is JsonNull
        val value = actual as? JsonPrimitive ?: return false
        if (value is JsonNull) return false
        return when {
            expected.isString -> value.isString && value.content == expected.content
            expected.booleanOrNull != null -> !value.isString && value.booleanOrNull == expected.booleanOrNull
            else -> !value.isString && value.doubleOrNull != null && value.doubleOrNull == expected.doubleOrNull
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    // ---- The scenario platform (mirrors createFakePlatform in portable-embedded-harness.cjs) ----

    private inner class FakePlatform(scenario: JsonObject) {
        val store = FakeSessionStore(scenario["stored"]?.jsonObject?.let { stored ->
            StoredSession(
                revision = stored.string("revision")!!,
                payloadJson = stored.string("raw") ?: expandTracks(stored["payload"]!!).toString()
            )
        })
        val resources = FakeResourceAccess()
        val audio = FakeAudioEngine(json.decodeFromJsonElement(AudioSnapshot.serializer(), corpus["audio"]!!))
        val network = FakeNetwork()
        private val route = json.decodeFromJsonElement(AudioDeviceRouteInfo.serializer(), corpus["route"]!!)

        suspend fun boot(): QuickJsRuntimeHost {
            val host = QuickJsRuntimeHost(
                appContext = instrumentation.targetContext,
                ports = CoreHostPorts(
                    clock = object : ClockPort {
                        override fun nowUnixMs() = System.currentTimeMillis()
                        override fun nowMonotonicMs() = android.os.SystemClock.elapsedRealtime()
                    },
                    scheduler = object : SchedulerPort {
                        override fun schedule(delayMs: Long, task: () -> Unit) = CancelHandle {}
                    },
                    sessionStore = store,
                    resourceAccess = resources,
                    audioEngine = audio,
                    networkClient = network,
                    audioDevice = object : AudioDevicePort {
                        override fun getActiveRoute() = route
                    }
                )
            )
            host.start()
            return host
        }
    }

    private inner class FakeSessionStore(@Volatile var stored: StoredSession?) : SessionStorePort {
        override suspend fun load(): StoredSession? = stored.also { touch() }
        override suspend fun save(payloadJson: String, expectedRevision: String?): SaveSessionResult = synchronized(this) {
            touch()
            val current = stored?.revision
            if (expectedRevision != current) return SaveSessionResult.Conflict(actualRevision = current)
            val next = ((current?.toIntOrNull() ?: 0) + 1).toString()
            stored = StoredSession(next, payloadJson)
            SaveSessionResult.Ok(next)
        }
    }

    private inner class FakeResourceAccess : ResourceAccessPort {
        val revoked = CopyOnWriteArraySet<String>()
        override suspend fun stat(resourceId: ResourceId): ResourceAccessResult<ResourceStat> =
            ResourceAccessResult.Ok(ResourceStat(resourceId = resourceId, uri = resourceId))
        override suspend fun openReadStream(resourceId: ResourceId, offsetBytes: Long): ResourceAccessResult<InputStream> =
            ResourceAccessResult.Ok(ByteArrayInputStream(ByteArray(0)))
        override suspend fun resolveNativeUri(resourceId: ResourceId): ResourceAccessResult<String> =
            if (touch().let { resourceId in revoked }) ResourceAccessResult.Error(CoreErrorCode.PERMISSION_EXPIRED, "Access to $resourceId expired")
            else ResourceAccessResult.Ok(resourceId)
    }

    private inner class FakeNetwork : NetworkClientPort {
        @Volatile var lastUrl: String? = null
        @Volatile var canned: JsonObject = JsonObject(mapOf("statusCode" to JsonPrimitive(404)))
        override suspend fun execute(spec: NetworkRequestSpec): ResourceAccessResult<NetworkResponsePayload> {
            touch()
            lastUrl = spec.url
            val response = canned
            (response["fail"] as? JsonObject)?.let { fail ->
                return ResourceAccessResult.Error(
                    CoreErrorCode.valueOf(fail.string("code")!!),
                    fail.string("message").orEmpty(),
                    fail["retryable"]?.jsonPrimitive?.booleanOrNull == true
                )
            }
            return ResourceAccessResult.Ok(json.decodeFromJsonElement(NetworkResponsePayload.serializer(), response))
        }
    }

    private inner class FakeAudioEngine(initial: AudioSnapshot) : AudioEnginePort {
        private val listeners = CopyOnWriteArraySet<(AudioEvent) -> Unit>()
        @Volatile var snapshot = initial
        private var queue: List<CoreTrackView> = emptyList()

        override suspend fun loadQueue(tracks: List<CoreTrackView>, startIndex: Int, queueGeneration: Long) = synchronized(this) {
            touch()
            val previous = queue.getOrNull(snapshot.queueIndex)
            val selected = tracks.getOrNull(startIndex)
            val changed = previous?.id != selected?.id || previous?.source != selected?.source ||
                previous?.queueEntryId != selected?.queueEntryId
            queue = tracks
            snapshot = snapshot.copy(queueGeneration = queueGeneration, queueIndex = startIndex).let {
                if (!changed) it
                else it.copy(state = "paused", positionMs = 0L, durationMs = ((selected?.duration ?: 0.0) * 1000).roundToLong())
            }
        }
        override suspend fun loadQueue(tracks: List<CoreTrackView>, startIndex: Int) = loadQueue(tracks, startIndex, 0L)
        override suspend fun play(startTimeSeconds: Double?) {
            touch()
            snapshot = snapshot.copy(state = "playing", positionMs = ((startTimeSeconds ?: 0.0) * 1000).roundToLong())
        }
        override suspend fun pause() {
            touch()
            if (snapshot.state == "playing") snapshot = snapshot.copy(state = "paused")
        }
        override suspend fun stop() {
            touch()
            snapshot = snapshot.copy(state = "idle", positionMs = 0L)
        }
        override suspend fun seek(positionMs: Long) {
            touch()
            snapshot = snapshot.copy(positionMs = positionMs)
        }
        override suspend fun setVolume(volume: Double) {
            snapshot = snapshot.copy(volume = volume)
        }
        override suspend fun setPlayMode(mode: PlayMode) {
            snapshot = snapshot.copy(playMode = mode.toWireValue())
        }
        override suspend fun setDspConfig(configJson: String) {}
        override suspend fun getSnapshot(): AudioSnapshot = snapshot.also { touch() }
        override suspend fun load(source: AudioSourceDescriptor) = unexpected("load")
        override suspend fun selectQueueIndex(index: Int) = unexpected("selectQueueIndex")
        override suspend fun next() = unexpected("next")
        override suspend fun previous() = unexpected("previous")
        override fun subscribe(listener: (AudioEvent) -> Unit): CancelHandle {
            listeners += listener
            return CancelHandle { listeners -= listener }
        }
        override fun close() {}

        fun patch(fields: JsonObject) {
            val merged = json.encodeToJsonElement(AudioSnapshot.serializer(), snapshot).jsonObject + fields
            snapshot = json.decodeFromJsonElement(AudioSnapshot.serializer(), JsonObject(merged))
        }

        fun emitScenarioEvent(event: JsonObject) {
            val filled = if (event.string("type") == "snapshot" && "snapshot" !in event) {
                JsonObject(event + ("snapshot" to json.encodeToJsonElement(AudioSnapshot.serializer(), snapshot)))
            } else event
            val decoded = json.decodeFromJsonElement(AudioEvent.serializer(), filled)
            listeners.forEach { it(decoded) }
        }

        private fun unexpected(method: String): Nothing =
            throw UnsupportedOperationException("The embedded Core does not call audioEngine.$method")
    }

    private companion object {
        const val EXPECT_TIMEOUT_MS = 5_000L
        const val QUIET_MS = 150L
    }
}
