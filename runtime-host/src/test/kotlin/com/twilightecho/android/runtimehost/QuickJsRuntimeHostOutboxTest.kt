package com.twilightecho.android.runtimehost

import com.twilightecho.android.coreclient.CoreCancelEnvelope
import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.coreclient.CoreRequestEnvelope
import com.twilightecho.android.coreclient.CoreResponseEnvelope
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.PlayMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Host plumbing of [QuickJsRuntimeHost] against a scripted engine that speaks the
 * `__TWILIGHT_PORTABLE_CORE__` outbox protocol. Core behaviour itself is covered by the
 * desktop repo's Node suites and by the instrumented tests running the real bundle.
 */
class QuickJsRuntimeHostOutboxTest {

    private val json = DefaultCoreClient.DefaultJson

    /** Outbox Core double: requests named `probe.*` drive the host paths under test. */
    private class ScriptedCore(private val bootError: String? = null) : JsRuntimeEngine {
        val scripts = CopyOnWriteArrayList<String>()
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        private val outbox = ArrayList<JsonElement>()
        private val waiting = HashMap<String, String>()
        private var nextCall = 0
        private var timerRequest: String? = null
        private var timerDueAt = 0L

        override fun evaluate(script: String, fileName: String) {
            scripts += script
        }

        override fun callCore(method: String, argJson: String): String {
            val arg = DefaultCoreClient.DefaultJson.parseToJsonElement(argJson).jsonObject
            calls += method to arg
            return when (method) {
                "bootHost" -> {
                    port("boot", "sessionStore", "load", JsonObject(emptyMap()))
                    """{"ok":true,"epoch":"scripted","protocolVersion":1,"hostApiVersion":2}"""
                }
                "handleRequest" -> {
                    val requestId = arg.str("requestId")
                    val payload = arg["payload"]?.jsonObject ?: JsonObject(emptyMap())
                    when (arg.str("name")) {
                        "probe.port" -> port(requestId, payload.str("port"), payload.str("method"), payload["args"]?.jsonObject ?: JsonObject(emptyMap()))
                        "probe.timer" -> {
                            timerRequest = requestId
                            timerDueAt = System.currentTimeMillis() + 20
                        }
                        "probe.hang" -> Unit
                        "probe.state" -> {
                            outbox += DefaultCoreClient.DefaultJson.parseToJsonElement(
                                """{"type":"event","event":{"kind":"state","epoch":"scripted","sequence":5,"snapshot":{"epoch":"scripted","sequence":5,"libraryTrackCount":2}}}"""
                            )
                            outbox += DefaultCoreClient.DefaultJson.parseToJsonElement(
                                """{"type":"event","event":{"kind":"state","epoch":"scripted","sequence":6,"snapshot":{"epoch":"scripted","sequence":"six"}}}"""
                            )
                            respond(requestId, JsonPrimitive("done"), null)
                        }
                        "probe.throw" -> throw IllegalStateException("ReferenceError: boom")
                        else -> respond(requestId, JsonPrimitive("done"), null)
                    }
                    """{"accepted":true}"""
                }
                "completePortCall" -> {
                    val target = waiting.remove(arg.str("callId")) ?: return """{"accepted":false}"""
                    if (target == "shutdown") {
                        outbox += buildJsonObject {
                            put("type", JsonPrimitive("shutdown"))
                            put("ok", JsonPrimitive(true))
                        }
                    } else if (target == "boot") {
                        outbox += buildJsonObject {
                            put("type", JsonPrimitive("boot"))
                            put("ok", JsonPrimitive(bootError == null))
                            put("epoch", JsonPrimitive("scripted"))
                            bootError?.let { put("error", JsonPrimitive(it)) }
                        }
                    } else if (arg["ok"]?.jsonPrimitive?.contentOrNull == "true") {
                        respond(target, arg["value"]!!, null)
                    } else {
                        respond(target, null, arg["error"]!!.jsonObject)
                    }
                    """{"accepted":true}"""
                }
                "cancelRequest" -> {
                    respond(arg.str("requestId"), null, errorOf("CANCELLED"))
                    """{"accepted":true}"""
                }
                "onAudioEngineEvent" -> """{"accepted":true}"""
                "shutdownHost" -> {
                    port("shutdown", "sessionStore", "save", buildJsonObject { put("payloadJson", JsonPrimitive("final")) })
                    """{"accepted":true}"""
                }
                "drain" -> {
                    val now = System.currentTimeMillis()
                    timerRequest?.takeIf { now >= timerDueAt }?.let {
                        timerRequest = null
                        respond(it, JsonPrimitive("timer"), null)
                    }
                    val messages = buildJsonArray { outbox.forEach { add(it) } }
                    outbox.clear()
                    buildJsonObject {
                        put("messages", messages)
                        timerRequest?.let { put("nextTimerDelayMs", JsonPrimitive((timerDueAt - now).coerceAtLeast(0))) }
                    }.toString()
                }
                else -> error("unexpected Core call $method")
            }
        }

        @Volatile var closed = false

        override fun close() {
            closed = true
        }

        fun audioEvents() = calls.filter { it.first == "onAudioEngineEvent" }.map { it.second }

        private fun port(target: String, port: String, method: String, args: JsonObject) {
            val callId = "1-${++nextCall}"
            waiting[callId] = target
            outbox += buildJsonObject {
                put("type", JsonPrimitive("port"))
                put("callId", JsonPrimitive(callId))
                put("port", JsonPrimitive(port))
                put("method", JsonPrimitive(method))
                put("args", args)
            }
        }

        private fun respond(requestId: String, value: JsonElement?, error: JsonObject?) {
            outbox += buildJsonObject {
                put("type", JsonPrimitive("response"))
                put("response", buildJsonObject {
                    put("kind", JsonPrimitive("response"))
                    put("requestId", JsonPrimitive(requestId))
                    put("epoch", JsonPrimitive("scripted"))
                    put("ok", JsonPrimitive(error == null))
                    value?.let { put("value", it) }
                    error?.let { put("error", it) }
                })
            }
        }

        private fun errorOf(code: String) = buildJsonObject {
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(code))
            put("retryable", JsonPrimitive(false))
        }

        private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private class FakeSessionStore : SessionStorePort {
        val saved = CopyOnWriteArrayList<String>()
        override suspend fun load(): StoredSession? = null
        override suspend fun save(payloadJson: String, expectedRevision: String?): SaveSessionResult {
            saved += payloadJson
            return SaveSessionResult.Ok("rev-${saved.size}")
        }
    }

    private class FakeResourceAccess(private val revoked: Set<String> = emptySet()) : ResourceAccessPort {
        override suspend fun stat(resourceId: ResourceId): ResourceAccessResult<ResourceStat> =
            ResourceAccessResult.Ok(ResourceStat(resourceId = resourceId, uri = resourceId))

        override suspend fun openReadStream(resourceId: ResourceId, offsetBytes: Long): ResourceAccessResult<InputStream> =
            ResourceAccessResult.Ok(ByteArrayInputStream(ByteArray(0)))

        override suspend fun resolveNativeUri(resourceId: ResourceId): ResourceAccessResult<String> =
            if (resourceId in revoked) ResourceAccessResult.Error(CoreErrorCode.PERMISSION_EXPIRED, "Folder access was revoked")
            else ResourceAccessResult.Ok(resourceId)
    }

    private class FakeAudioEngine : AudioEnginePort {
        private val listeners = CopyOnWriteArraySet<(AudioEvent) -> Unit>()
        val loads = CopyOnWriteArrayList<Pair<List<CoreTrackView>, Long>>()
        var snapshot = AudioSnapshot(state = "paused")
        var plays = 0

        override suspend fun load(source: AudioSourceDescriptor) {}
        override suspend fun play(startTimeSeconds: Double?) { plays++ }
        override suspend fun pause() {}
        override suspend fun stop() {}
        override suspend fun seek(positionMs: Long) {}
        override suspend fun setVolume(volume: Double) {}
        override suspend fun setPlayMode(mode: PlayMode) {}
        override suspend fun loadQueue(tracks: List<CoreTrackView>, startIndex: Int) = error("generation-less loadQueue")
        override suspend fun loadQueue(tracks: List<CoreTrackView>, startIndex: Int, queueGeneration: Long) {
            loads += tracks to queueGeneration
            snapshot = snapshot.copy(queueIndex = startIndex, queueGeneration = queueGeneration)
        }
        override suspend fun selectQueueIndex(index: Int) {}
        override suspend fun next() {}
        override suspend fun previous() {}
        override suspend fun getSnapshot(): AudioSnapshot = snapshot
        override fun subscribe(listener: (AudioEvent) -> Unit): CancelHandle {
            listeners += listener
            return CancelHandle { listeners -= listener }
        }
        override fun close() {}
        fun emit(event: AudioEvent) = listeners.forEach { it(event) }
    }

    private class Fixture(
        engine: ScriptedCore = ScriptedCore(),
        factory: ((CoreRuntimeThreadDispatcher) -> JsRuntimeEngine)? = null,
        revoked: Set<String> = emptySet()
    ) : AutoCloseable {
        val core = engine
        val store = FakeSessionStore()
        val audio = FakeAudioEngine()
        val logs = CopyOnWriteArrayList<String>()
        val seen = CopyOnWriteArrayList<String>()
        val host = QuickJsRuntimeHost(
            appContext = null,
            ports = CoreHostPorts(
                clock = object : ClockPort {
                    override fun nowUnixMs() = 0L
                    override fun nowMonotonicMs() = 0L
                },
                scheduler = object : SchedulerPort {
                    override fun schedule(delayMs: Long, task: () -> Unit) = CancelHandle {}
                },
                sessionStore = store,
                resourceAccess = FakeResourceAccess(revoked),
                audioEngine = audio
            ),
            engineFactory = factory ?: { engine },
            scriptLoader = { BUNDLE },
            portDispatcher = Dispatchers.Default,
            logger = { level, message -> logs += "$level: $message" },
            onRequest = { seen += it.name }
        )
        private var counter = 0

        suspend fun request(name: String, payload: JsonObject = JsonObject(emptyMap())): CoreResponseEnvelope =
            withTimeout(5_000) { host.sendRequest(envelope(name, payload)) }

        fun envelope(name: String, payload: JsonObject = JsonObject(emptyMap())) = CoreRequestEnvelope(
            requestId = "req-${++counter}",
            epoch = "scripted",
            kind = "query",
            name = name,
            payload = payload
        )

        override fun close() = host.close()
    }

    private fun portProbe(port: String, method: String, args: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("port", JsonPrimitive(port))
        put("method", JsonPrimitive(method))
        put("args", args)
    }

    private fun track(id: String, title: String? = "Title $id") = buildJsonObject {
        put("id", JsonPrimitive(id))
        title?.let { put("title", JsonPrimitive(it)) }
        put("source", JsonPrimitive("/music/$id.flac"))
        put("duration", JsonPrimitive(42))
    }

    private fun loadQueueArgs(vararg tracks: JsonObject, startIndex: Int = 0, generation: Long = 7) = buildJsonObject {
        put("tracks", buildJsonArray { tracks.forEach { add(it) } })
        put("startIndex", JsonPrimitive(startIndex))
        put("queueGeneration", JsonPrimitive(generation))
    }

    @Test
    fun `missing QuickJS fails start and every later request instead of faking a Core`() = runBlocking {
        Fixture(factory = { throw PortableCoreException("QuickJS native runtime is unavailable on this device") }).use { f ->
            try {
                f.host.start()
                fail("start must throw when QuickJS is missing")
            } catch (expected: PortableCoreException) {
                assertTrue(expected.message!!.contains("unavailable"))
            }
            val response = f.request("core.getSnapshot")
            assertFalse(response.ok)
            assertEquals(CoreErrorCode.FAILED, response.error!!.code)
            assertTrue(response.error!!.message.contains("unavailable"))
            assertTrue(f.logs.any { it.startsWith("error:") })
        }
    }

    @Test
    fun `a failed Core boot is reported by start`() = runBlocking {
        Fixture(ScriptedCore(bootError = "session store unreadable")).use { f ->
            try {
                f.host.start()
                fail("start must throw when the Core reports a failed boot")
            } catch (expected: PortableCoreException) {
                assertEquals("session store unreadable", expected.message)
            }
        }
    }

    @Test
    fun `every submitted request is observed before the Core answers it`() = runBlocking {
        Fixture().use { f ->
            f.host.start()
            f.request("probe.port", portProbe("sessionStore", "save", buildJsonObject { put("payloadJson", JsonPrimitive("{}")) }))
            assertEquals(listOf("probe.port"), f.seen.toList())
            f.host.close()
            f.request("probe.port")
            assertEquals("a closed host submits nothing", listOf("probe.port"), f.seen.toList())
        }
    }

    @Test
    fun `the host event loop is enabled before the bundle is evaluated`() = runBlocking {
        Fixture().use { f ->
            f.host.start()
            assertEquals(listOf("globalThis.__TWILIGHT_HOST_EVENT_LOOP__ = true;", BUNDLE), f.core.scripts.toList())
            assertEquals("bootHost", f.core.calls.first().first)
        }
    }

    @Test
    fun `port calls execute against host ports and answer the waiting request`() = runBlocking {
        Fixture(revoked = setOf("content://revoked")).use { f ->
            f.host.start()
            val saved = f.request("probe.port", portProbe("sessionStore", "save", buildJsonObject {
                put("payloadJson", JsonPrimitive("""{"schema":"portable-core/1"}"""))
            }))
            assertTrue(saved.ok)
            assertEquals("rev-1", saved.value!!.jsonObject["revision"]!!.jsonPrimitive.content)
            assertEquals(listOf("""{"schema":"portable-core/1"}"""), f.store.saved.toList())

            val revoked = f.request("probe.port", portProbe("resourceAccess", "resolveNativeUri", buildJsonObject {
                put("resourceId", JsonPrimitive("content://revoked"))
            }))
            assertEquals(CoreErrorCode.PERMISSION_EXPIRED, revoked.error!!.code)

            val unknown = f.request("probe.port", portProbe("clipboard", "read"))
            assertEquals(CoreErrorCode.UNSUPPORTED_CAPABILITY, unknown.error!!.code)
        }
    }

    @Test
    fun `malformed queues are rejected whole instead of silently dropping tracks`() = runBlocking {
        Fixture().use { f ->
            f.host.start()
            val response = f.request("probe.port", portProbe("audioEngine", "loadQueue", loadQueueArgs(track("a"), track("b", title = null))))
            assertEquals(CoreErrorCode.INVALID_REQUEST, response.error!!.code)
            assertTrue(f.audio.loads.isEmpty())

            val badMode = f.request("probe.port", portProbe("audioEngine", "setPlayMode", buildJsonObject { put("mode", JsonPrimitive("warp")) }))
            assertEquals(CoreErrorCode.INVALID_REQUEST, badMode.error!!.code)
        }
    }

    @Test
    fun `play only starts the source the engine holds at its queue position`() = runBlocking {
        Fixture().use { f ->
            f.host.start()
            assertTrue(f.request("probe.port", portProbe("audioEngine", "loadQueue", loadQueueArgs(track("a"), track("b")))).ok)
            assertEquals(7L, f.audio.loads.single().second)

            val stale = f.request("probe.port", portProbe("audioEngine", "play", buildJsonObject {
                put("position", JsonPrimitive(0))
                put("source", JsonPrimitive("/music/b.flac"))
            }))
            assertEquals(CoreErrorCode.SOURCE_CHANGED, stale.error!!.code)
            assertEquals(0, f.audio.plays)

            val current = f.request("probe.port", portProbe("audioEngine", "play", buildJsonObject {
                put("position", JsonPrimitive(1.5))
                put("source", JsonPrimitive("/music/a.flac"))
            }))
            assertTrue(current.ok)
            assertEquals(1, f.audio.plays)

            val snapshot = f.request("probe.port", portProbe("audioEngine", "getSnapshot")).value!!.jsonObject
            assertEquals(7L, snapshot["queueGeneration"]!!.jsonPrimitive.longOrNull)
        }
    }

    @Test
    fun `Core timers schedule the next drain`() = runBlocking {
        Fixture().use { f ->
            f.host.start()
            val response = f.request("probe.timer")
            assertEquals("timer", response.value!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `cancelling a suspended request cancels it inside the Core`() = runBlocking {
        Fixture().use { f ->
            f.host.start()
            val envelope = f.envelope("probe.hang")
            val call = async(start = CoroutineStart.UNDISPATCHED) { f.host.sendRequest(envelope) }
            withTimeout(5_000) { while (f.core.calls.none { it.first == "handleRequest" }) delay(5) }
            call.cancel()
            withTimeout(5_000) {
                while (f.core.calls.none { it.first == "cancelRequest" && it.second["requestId"]!!.jsonPrimitive.content == envelope.requestId }) delay(5)
            }
            f.host.cancelRequest(CoreCancelEnvelope(requestId = "never-sent", epoch = "scripted"))
            assertEquals(1, f.core.calls.count { it.first == "cancelRequest" })
        }
    }

    @Test
    fun `shutdown lets the Core save through the ports before the runtime closes`() = runBlocking {
        Fixture().use { f ->
            f.host.start()
            withTimeout(5_000) { f.host.shutdown() }
            assertEquals(listOf("final"), f.store.saved)
            withTimeout(5_000) { while (!f.core.closed) delay(5) }
            val late = f.request("core.getSnapshot")
            assertEquals(CoreErrorCode.CANCELLED, late.error!!.code)
        }
    }

    @Test
    fun `native audio events reach the Core in order with their queue generation`() = runBlocking {
        Fixture().use { f ->
            f.host.start()
            f.audio.emit(AudioEvent.SnapshotUpdated(AudioSnapshot(state = "playing", queueIndex = 1, queueGeneration = 3)))
            f.audio.emit(AudioEvent.TrackEnded(queueIndex = 1))
            withTimeout(5_000) { while (f.core.audioEvents().size < 2) delay(5) }
            val (snapshot, ended) = f.core.audioEvents()
            assertEquals("snapshot", snapshot["type"]!!.jsonPrimitive.content)
            assertEquals(3L, snapshot["snapshot"]!!.jsonObject["queueGeneration"]!!.jsonPrimitive.longOrNull)
            assertEquals("track-ended", ended["type"]!!.jsonPrimitive.content)
            assertEquals("eof", ended["reason"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `an exception escaping the Core is fatal and loud`() = runBlocking {
        Fixture().use { f ->
            f.host.start()
            val thrown = f.request("probe.throw")
            assertEquals(CoreErrorCode.FAILED, thrown.error!!.code)
            assertTrue(thrown.error!!.message.contains("boom"))
            assertEquals(CoreErrorCode.FAILED, f.request("core.getSnapshot").error!!.code)
            assertTrue(f.logs.any { it.contains("boom") })
        }
    }

    @Test
    fun `state events from the outbox are decoded and published, bad ones are logged`() = runBlocking {
        Fixture().use { f ->
            f.host.start()
            assertTrue(f.request("probe.state").ok)
            val event = withTimeout(5_000) { f.host.stateEvents.first() }
            assertEquals(5L, event.sequence)
            assertEquals(2, event.snapshot.libraryTrackCount)
            assertTrue(f.logs.any { it.startsWith("error: Undecodable Core state event") })
        }
    }

    private companion object {
        const val BUNDLE = "/* music-core.js */"
    }
}
