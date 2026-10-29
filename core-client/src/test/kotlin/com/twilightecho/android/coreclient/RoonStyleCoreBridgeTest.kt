package com.twilightecho.android.coreclient

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoonStyleCoreBridgeTest {

    private val json = DefaultCoreClient.DefaultJson

    @Test
    fun `SwitchableCoreTransport hot-swaps DefaultCoreClient between Standalone Local Core and Desktop Remote Core`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)

        // 1. Setup Local Embedded Core Transport (Standalone Mode)
        val localEvents = MutableSharedFlow<CoreEventMessage>(extraBufferCapacity = 16)
        val localSnapshot = CoreSnapshot(
            epoch = "epoch-phone-local",
            sequence = 1L,
            playback = CorePlaybackView(state = "paused", position = 12.0, duration = 200.0),
            track = CoreTrackView(id = "phone-track-1", title = "Phone Local Song", artist = "Local Artist"),
            queue = CoreQueueReference(id = "local-q", revision = 1L, total = 1, index = 0)
        )
        val localTransport = object : CoreTransport {
            override val stateEvents = localEvents
            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                return when (envelope.name) {
                    "core.getSnapshot" -> CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = localSnapshot.epoch,
                        ok = true,
                        value = json.encodeToJsonElement(localSnapshot)
                    )
                    "playback.getQueueWindow" -> CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = localSnapshot.epoch,
                        ok = true,
                        value = json.encodeToJsonElement(
                            CoreQueueWindowView(
                                queueId = "local-q",
                                revision = 1L,
                                total = 1,
                                items = listOf(CoreQueueEntryView("occ-0", 0, localSnapshot.track))
                            )
                        )
                    )
                    else -> CoreResponseEnvelope(
                        requestId = envelope.requestId,
                        epoch = localSnapshot.epoch,
                        ok = true,
                        value = JsonObject(mapOf("snapshot" to json.encodeToJsonElement(localSnapshot)))
                    )
                }
            }
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
        }

        // 2. Setup Simulated Desktop Core over LAN HTTP Executor
        var desktopSnapshot = CoreSnapshot(
            epoch = "epoch-desktop-pc",
            sequence = 42L,
            playback = CorePlaybackView(state = "playing", position = 88.5, duration = 320.0, playMode = "shuffle"),
            track = CoreTrackView(id = "pc-flac-99", title = "Desktop Hi-Res Symphony", artist = "PC Orchestra"),
            queue = CoreQueueReference(id = "pc-q", revision = 9L, total = 1, index = 0)
        )
        val recordedDesktopRpcCommands = mutableListOf<String>()

        val fakeDesktopHttp = object : RemoteHttpExecutor {
            override fun executeGet(url: String, headers: Map<String, String>): RemoteHttpSimpleResponse {
                if (url.endsWith("/api/status")) {
                    return RemoteHttpSimpleResponse(
                        statusCode = 200,
                        body = """{"requiresPairing":true,"paired":true,"app":"Twilight Echo","coreBridge":true,"protocolVersion":1}"""
                    )
                }
                return RemoteHttpSimpleResponse(404, """{"error":"not_found"}""")
            }

            override fun executePostJson(
                url: String,
                headers: Map<String, String>,
                bodyJson: String
            ): RemoteHttpSimpleResponse {
                if (url.endsWith("/api/pair")) {
                    return RemoteHttpSimpleResponse(200, """{"token":"pc-lan-token-777"}""")
                }
                if (url.endsWith("/api/core/rpc")) {
                    assertEquals("Bearer pc-lan-token-777", headers["Authorization"])
                    val req = json.decodeFromString<CoreRequestEnvelope>(bodyJson)
                    if (req.kind == "command") {
                        recordedDesktopRpcCommands.add(req.name)
                        if (req.name == "playback.pause") {
                            desktopSnapshot = desktopSnapshot.copy(
                                sequence = desktopSnapshot.sequence + 1,
                                playback = desktopSnapshot.playback?.copy(state = "paused")
                            )
                        }
                    }
                    val responseEnv = when (req.name) {
                        "core.getSnapshot" -> CoreResponseEnvelope(
                            requestId = req.requestId,
                            epoch = desktopSnapshot.epoch,
                            ok = true,
                            value = json.encodeToJsonElement(desktopSnapshot)
                        )
                        "playback.getQueueWindow" -> CoreResponseEnvelope(
                            requestId = req.requestId,
                            epoch = desktopSnapshot.epoch,
                            ok = true,
                            value = json.encodeToJsonElement(
                                CoreQueueWindowView(
                                    queueId = "pc-q",
                                    revision = 9L,
                                    total = 1,
                                    items = listOf(CoreQueueEntryView("pc-occ-0", 0, desktopSnapshot.track))
                                )
                            )
                        )
                        else -> CoreResponseEnvelope(
                            requestId = req.requestId,
                            epoch = desktopSnapshot.epoch,
                            ok = true,
                            value = JsonObject(mapOf("snapshot" to json.encodeToJsonElement(desktopSnapshot)))
                        )
                    }
                    return RemoteHttpSimpleResponse(200, json.encodeToString(responseEnv))
                }
                return RemoteHttpSimpleResponse(404, """{"error":"not_found"}""")
            }

            override fun streamSseEvents(
                url: String,
                headers: Map<String, String>,
                isCancelled: () -> Boolean,
                onEvent: (eventName: String, dataLine: String) -> Unit
            ) {
                val initialEvent = CoreEventMessage(
                    epoch = desktopSnapshot.epoch,
                    sequence = desktopSnapshot.sequence,
                    snapshot = desktopSnapshot
                )
                onEvent("core_state", json.encodeToString(initialEvent))
            }
        }

        // 3. Create SwitchableCoreTransport + single DefaultCoreClient
        val switchable = SwitchableCoreTransport(localTransport = localTransport, scope = scope)
        val client = DefaultCoreClient(
            identity = CoreClientIdentity.androidPrimaryClient(),
            transport = switchable,
            scope = scope
        )

        // Start in Standalone Local Mode
        assertTrue(switchable.activeMode.value is CoreOperatingMode.StandaloneLocal)
        client.reconcileState()
        advanceUntilIdle()
        assertEquals("Phone Local Song", client.snapshot.value?.track?.title)
        assertEquals("epoch-phone-local", client.snapshot.value?.epoch)

        // 4. Pair and hot-swap to Remote Desktop Core Mode (Roon Remote Controller)
        val remoteTransport = RemoteNetworkCoreTransport(
            rawBaseUrl = "192.168.1.50:18492",
            scope = scope,
            ioDispatcher = dispatcher,
            httpExecutor = fakeDesktopHttp,
            monotonicMs = { testScheduler.currentTime }
        )
        val tokenRes = remoteTransport.pairWithPin("654321")
        assertEquals("pc-lan-token-777", tokenRes.getOrThrow())

        switchable.switchTo(
            targetTransport = remoteTransport,
            mode = CoreOperatingMode.RemoteDesktopCore(
                baseUrl = remoteTransport.baseUrl,
                hostLabel = "192.168.1.50:18492"
            )
        )
        client.reconcileState()
        advanceUntilIdle()

        assertTrue(switchable.activeMode.value is CoreOperatingMode.RemoteDesktopCore)
        assertEquals("Desktop Hi-Res Symphony", client.snapshot.value?.track?.title)
        assertEquals("epoch-desktop-pc", client.snapshot.value?.epoch)
        assertEquals("playing", client.snapshot.value?.playback?.state)

        // Send a Pause command from the phone client -> verify it controls the Desktop Core!
        client.executeCommand(CoreTypedCommand.Pause)
        advanceUntilIdle()
        assertTrue(recordedDesktopRpcCommands.contains("playback.pause"))
        assertEquals("paused", client.snapshot.value?.playback?.state)

        // 5. Hot-swap back to Standalone Local Core Mode
        remoteTransport.close()
        switchable.switchToLocal()
        client.reconcileState()
        advanceUntilIdle()

        assertTrue(switchable.activeMode.value is CoreOperatingMode.StandaloneLocal)
        assertEquals("Phone Local Song", client.snapshot.value?.track?.title)
        assertEquals("epoch-phone-local", client.snapshot.value?.epoch)
    }
}
