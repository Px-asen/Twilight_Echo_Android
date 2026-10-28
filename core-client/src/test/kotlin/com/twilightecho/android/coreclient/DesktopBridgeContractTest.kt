package com.twilightecho.android.coreclient

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBridgeContractTest {
    private val json = DefaultCoreClient.DefaultJson
    private var state = CoreSnapshot(epoch = "desktop", sequence = 1,
        queue = CoreQueueReference("queue", 7, 2, 0, "occ-a"))
    private val requests = mutableListOf<CoreRequestEnvelope>()
    private val events = MutableSharedFlow<CoreEventMessage>(extraBufferCapacity = 8)
    private fun transport(remote: Boolean = true) = object : CoreTransport {
        override val isRemote = remote
        override val stateEvents = events
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            requests += envelope
            val value = when (envelope.name) {
                "core.getSnapshot" -> json.encodeToJsonElement(state)
                "playback.getQueueWindow" -> json.encodeToJsonElement(CoreQueueWindowView(
                    queueId = state.queue!!.id, revision = state.queue!!.revision, total = 2))
                "search.songs" -> json.encodeToJsonElement(CoreLibrarySearchPage("", 230,
                    listOf(CoreTrackView("remote-track:a", title = "Song")), offset = 100, revision = 3))
                "providers.session" -> json.parseToJsonElement("""{"providerId":"ncm","loggedIn":true,"cookie":"MUSIC_U=test-session"}""")
                else -> {
                    state = state.copy(sequence = state.sequence + 1, queue = state.queue?.copy(revision = 8))
                    buildJsonObject { put("snapshot", json.encodeToJsonElement(state)) }
                }
            }
            return CoreResponseEnvelope(requestId = envelope.requestId, epoch = state.epoch, ok = true, value = value)
        }
    }

    @Test fun remoteSelectionSendsOnlyIssuedIdsAndRefreshesInlineQueue() = runTest {
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), transport(), backgroundScope)
        client.reconcileState()
        val tracks = listOf(CoreTrackView("remote-track:a", title = "Untrusted", source = "file:///phone"))
        client.executeCommand(CoreTypedCommand.ReplaceQueue(revision = 7, tracks = tracks + tracks, startIndex = 1))
        val selected = requests.single { it.name == "playback.replaceQueue" }
        assertEquals(7L, selected.expectedRevision)
        assertEquals("desktop", selected.epoch)
        assertEquals("""[{"id":"remote-track:a"},{"id":"remote-track:a"}]""", selected.payload["tracks"].toString())
        assertEquals(8L, client.queueWindow.value.revision)
    }

    @Test fun localSelectionKeepsExistingTrackPayload() = runTest {
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), transport(false), backgroundScope)
        client.reconcileState()
        client.executeCommand(CoreTypedCommand.ReplaceQueue(revision = 7,
            tracks = listOf(CoreTrackView("local:a", title = "Phone", source = "content://media/1"))))
        val selected = requests.single { it.name == "playback.replaceQueue" }
        assertTrue(selected.payload["tracks"].toString().contains("content://media/1"))
    }

    @Test fun pageQueriesCarryOffsetAndKeepRevision() = runTest {
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), transport(), backgroundScope)
        client.reconcileState()
        val page = client.searchTracksPage("test", 100, 100).getOrThrow()
        assertEquals(100, page.offset); assertEquals(230, page.total); assertEquals(3L, page.revision)
        assertEquals(JsonPrimitive(100), requests.last().payload["offset"])
    }

    @Test fun realDesktopBitratesRemainNumericAcrossLibraryQueueAndPlayback() {
        // music-metadata reports a fractional average bitrate for FLAC/VBR files.
        val track = """{"id":"remote-track:flac","title":"FLAC","artist":"Artist","album":"Album",
            "source":"local","duration":198.37,"size":25354880,"bitrate":1022519.7329376856,
            "sampleRate":44100,"bitDepth":16}"""
        val page = json.decodeFromString<CoreLibrarySearchPage>("""{"query":"","total":1,"items":[$track]}""")
        assertEquals(1022519.7329376856, page.items.single().bitrate!!.toDouble(), 0.0001)
        val snapshot = json.decodeFromString<CoreSnapshot>("""{"epoch":"desktop","sequence":1,
            "track":$track,"playback":{"state":"playing","bitrate":1022519.7329376856}}""")
        assertEquals(1022519.7329376856, snapshot.playback!!.bitrate!!.toDouble(), 0.0001)
        val queue = json.decodeFromString<CoreQueueWindowView>("""{"items":[{"occurrenceId":"a","index":0,"track":$track}]}""")
        assertEquals(page.items.single().bitrate, queue.items.single().track!!.bitrate)
        assertEquals(page.items.single(), json.decodeFromString<CoreTrackView>(json.encodeToString(page.items.single())))
        assertEquals(320000.0, json.decodeFromString<CoreTrackView>("""{"id":"mp3","title":"MP3","bitrate":320000}""").bitrate!!.toDouble(), 0.0)
    }

    @Test fun desktopProviderSessionUsesTheSharedContractAndRedactsItsCookie() = runTest {
        val client = DesktopLibraryClient(transport()) { state.epoch }
        val session = client.providerSession("ncm").getOrThrow()
        assertTrue(session.loggedIn)
        assertEquals("MUSIC_U=test-session", session.cookie)
        assertEquals("providers.session", requests.single().name)
        assertEquals(buildJsonObject { put("providerId", "ncm") }, requests.single().payload)
        assertFalse(session.toString().contains("MUSIC_U"))
        val signedOut = json.decodeFromString<DesktopProviderSession>("""{"providerId":"ncm","loggedIn":false}""")
        assertFalse(signedOut.loggedIn)
        assertNull(signedOut.cookie)
    }

    @Test fun switchingSourcesRejectsAnOldInFlightResponse() = runTest {
        val gate = CompletableDeferred<Unit>()
        val old = object : CoreTransport {
            override val stateEvents = events
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                gate.await()
                return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "old", ok = true,
                    value = json.encodeToJsonElement(state.copy(epoch = "old")))
            }
        }
        val switchable = SwitchableCoreTransport(old, backgroundScope)
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), switchable, backgroundScope)
        val delayed = async { client.querySnapshot() }
        runCurrent()
        switchable.switchTo(transport(), CoreOperatingMode.RemoteDesktopCore("http://desktop"))
        client.reconcileState()
        gate.complete(Unit)
        assertEquals(CoreErrorCode.CANCELLED, delayed.await().errorOrNull()?.code)
        assertEquals("desktop", client.snapshot.value?.epoch)
    }

    @Test fun snapshotReconciliationDoesNotRegressANewerSseSequence() = runTest {
        val gate = CompletableDeferred<Unit>()
        val t = object : CoreTransport {
            override val stateEvents = events
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                gate.await()
                return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "desktop", ok = true,
                    value = json.encodeToJsonElement(state.copy(queue = null)))
            }
        }
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), t, backgroundScope)
        val delayed = async { client.reconcileState() }
        runCurrent()
        events.emit(CoreEventMessage(epoch = "desktop", sequence = 10, snapshot = state.copy(sequence = 10, queue = null)))
        runCurrent()
        gate.complete(Unit); delayed.await()
        assertEquals(10L, client.snapshot.value?.sequence)
    }

    @Test fun bufferedEventsFromPreviousSourceCannotRestoreItsSnapshot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val oldSnapshot = state.copy(epoch = "old", queue = CoreQueueReference("old-queue", 2, 1))
        val old = object : CoreTransport {
            override val stateEvents = events
            override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
            override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                gate.await()
                return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "old", ok = true,
                    value = json.encodeToJsonElement(CoreQueueWindowView(queueId = "old-queue", revision = 2)))
            }
        }
        val route = SwitchableCoreTransport(old, backgroundScope)
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), route, backgroundScope)
        runCurrent()
        events.emit(CoreEventMessage(epoch = "old", sequence = 1, snapshot = oldSnapshot)); runCurrent()
        // The client is waiting on the first event's queue read; the next event is already buffered.
        events.emit(CoreEventMessage(epoch = "old", sequence = 2, snapshot = oldSnapshot.copy(sequence = 2))); runCurrent()
        route.switchTo(transport(), CoreOperatingMode.RemoteDesktopCore("http://desktop"))
        client.reconcileState()
        gate.complete(Unit); runCurrent()
        assertEquals("desktop", client.snapshot.value?.epoch)
        assertEquals("queue", client.queueWindow.value.queueId)
    }

    @Test fun unknownCommandOutcomeReconcilesWithoutReplaying() = runTest {
        val names = mutableListOf<String>()
        val executor = object : RemoteHttpExecutor {
            override fun executeGet(url: String, headers: Map<String, String>) = RemoteHttpSimpleResponse(200, "{}")
            override fun streamSseEvents(url: String, headers: Map<String, String>, isCancelled: () -> Boolean,
                onEvent: (String, String) -> Unit) {}
            override fun executePostJson(url: String, headers: Map<String, String>, bodyJson: String): RemoteHttpSimpleResponse {
                val request = json.decodeFromString<CoreRequestEnvelope>(bodyJson)
                names += request.name
                if (request.kind == "command") throw java.io.IOException("lost response")
                return RemoteHttpSimpleResponse(200, json.encodeToString(CoreResponseEnvelope(
                    requestId = request.requestId, epoch = "desktop", ok = true,
                    value = json.encodeToJsonElement(state.copy(queue = null)))))
            }
        }
        val remote = RemoteNetworkCoreTransport("127.0.0.1:18492", "token", backgroundScope,
            StandardTestDispatcher(testScheduler), httpExecutor = executor)
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), remote, backgroundScope)
        val result = client.executeCommand(CoreTypedCommand.Next)
        assertEquals(CoreErrorCode.OUTCOME_UNKNOWN, result.errorOrNull()?.code)
        assertEquals(listOf("playback.next", "core.getSnapshot"), names)
        assertEquals("desktop", client.snapshot.value?.epoch)
        remote.close()
    }

    @Test fun sseUnauthorizedRequiresPairingAndDoesNotRetryForever() = runTest {
        var streams = 0
        val executor = object : RemoteHttpExecutor {
            override fun executeGet(url: String, headers: Map<String, String>) = RemoteHttpSimpleResponse(200, "{}")
            override fun executePostJson(url: String, headers: Map<String, String>, bodyJson: String) = RemoteHttpSimpleResponse(401, "{}")
            override fun streamSseEvents(url: String, headers: Map<String, String>, isCancelled: () -> Boolean,
                onEvent: (String, String) -> Unit) { streams++; throw RemoteHttpException(401) }
        }
        val remote = RemoteNetworkCoreTransport("127.0.0.1:18492", "token", backgroundScope,
            StandardTestDispatcher(testScheduler), httpExecutor = executor)
        remote.startEventStream(); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(RemoteConnectionPhase.PAIRING_REQUIRED, remote.connection.value.phase)
        assertEquals(1, streams)
        remote.close()
    }

    @Test fun desktopAuthEventRevokesTheOpenStreamWithoutAcceptingLaterSnapshots() = runTest {
        var streams = 0
        var acceptedAfterRevocation = false
        val executor = object : RemoteHttpExecutor {
            override fun executeGet(url: String, headers: Map<String, String>) = RemoteHttpSimpleResponse(200, "{}")
            override fun executePostJson(url: String, headers: Map<String, String>, bodyJson: String) = RemoteHttpSimpleResponse(401, "{}")
            override fun streamSseEvents(url: String, headers: Map<String, String>, isCancelled: () -> Boolean,
                onEvent: (String, String) -> Unit) {
                streams++
                onEvent("core_state", json.encodeToString(CoreEventMessage(epoch = "desktop", sequence = 1, snapshot = state)))
                onEvent("auth", """{"paired":false}""")
                acceptedAfterRevocation = true
                onEvent("core_state", json.encodeToString(CoreEventMessage(epoch = "desktop", sequence = 2, snapshot = state)))
            }
        }
        val remote = RemoteNetworkCoreTransport("127.0.0.1:18492", "token", backgroundScope,
            StandardTestDispatcher(testScheduler), httpExecutor = executor)
        remote.startEventStream(); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(RemoteConnectionPhase.PAIRING_REQUIRED, remote.connection.value.phase)
        assertEquals(1, streams)
        assertFalse(acceptedAfterRevocation)
        remote.close()
    }

    @Test fun desktopMetadataPreservesManualAndUsageSemantics() {
        val detail = json.decodeFromString<EntityMetadataDetail>("""{"entityId":"artist","revision":2,"name":"人工名称",
            "introduction":{"value":"人工简介","origin":"manual","language":"zh-Hans"},
            "artworkOverride":{"mode":"clear"},"facts":[],"artwork":[]}""")
        assertEquals("人工名称", detail.name)
        assertEquals("manual", detail.introduction?.origin)
        assertEquals("clear", detail.artworkOverride?.mode)
        assertFalse(MetadataUsage(display = false).canDisplay())
        assertFalse(MetadataUsage(expiresAt = 100).canDisplay(101))
    }

    @Test fun baseUrlRejectsCredentialsAndNonServerPaths() {
        assertEquals("https://192.168.1.10:18492", RemoteNetworkCoreTransport.normalizeBaseUrl("192.168.1.10:18492/"))
        assertEquals("https://192.168.1.10:18492", RemoteNetworkCoreTransport.normalizeBaseUrl("http://192.168.1.10:18492"))
        for (bad in listOf("http://user:secret@host", "http://host/path", "http://host?token=secret", "file:///private")) {
            assertTrue(runCatching { RemoteNetworkCoreTransport.normalizeBaseUrl(bad) }.isFailure)
        }
    }
}
