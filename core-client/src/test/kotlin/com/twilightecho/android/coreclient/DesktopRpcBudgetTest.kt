package com.twilightecho.android.coreclient

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopRpcBudgetTest {
    private class Http(val now: () -> Long) : RemoteHttpExecutor {
        val calls = mutableListOf<Pair<Long, CoreRequestEnvelope>>()
        var status = 200
        override fun executeGet(url: String, headers: Map<String, String>) = RemoteHttpSimpleResponse(200, "{}")
        override fun streamSseEvents(url: String, headers: Map<String, String>, isCancelled: () -> Boolean,
            onEvent: (String, String) -> Unit) {}
        override fun executePostJson(url: String, headers: Map<String, String>, bodyJson: String): RemoteHttpSimpleResponse {
            val request = DefaultCoreClient.DefaultJson.decodeFromString<CoreRequestEnvelope>(bodyJson)
            calls += now() to request
            return RemoteHttpSimpleResponse(status,
                """{"requestId":"${request.requestId}","epoch":"desktop","ok":true,"value":{}}""")
        }
    }
    private fun TestScope.transport(http: Http) = RemoteNetworkCoreTransport("desktop", scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler), httpExecutor = http, monotonicMs = { testScheduler.currentTime })
    private fun request(name: String, kind: String = "query") = CoreRequestEnvelope(
        requestId = name, epoch = "desktop", name = name, kind = kind)

    @Test fun indexArtworkAndArtistReadsShareOneBudgetAndLeaveRoomForPlayback() = runTest {
        val http = Http { testScheduler.currentTime }
        val transport = transport(http)
        val bulk = List(90) { i -> async {
            transport.sendRequest(request(listOf("search.songs", "library.artwork", "metadata.entity.detail", "catalog.detail")[i % 4]))
        } }
        runCurrent()
        assertEquals(4, http.calls.size)
        assertTrue(transport.sendRequest(request("playback.pause", "command")).ok)
        assertTrue(transport.sendRequest(request("media.resolve")).ok)
        assertEquals(0L, http.calls.last().first)
        bulk.awaitAll().forEach { assertTrue(it.ok) }
        assertEquals(92, http.calls.size)
        for ((at, _) in http.calls) assertTrue(http.calls.count { it.first in (at - 60_000)..at } <= 58)
        assertTrue(http.calls.last().first > 60_000)
    }

    @Test fun aRateLimitedReadRetriesOnceAndAllOtherReadersRespectTheCooldown() = runTest {
        val http = Http { testScheduler.currentTime }.apply { status = 429 }
        val transport = transport(http)
        val artist = async { transport.sendRequest(request("metadata.entity.detail")) }
        runCurrent()
        val cover = async { transport.sendRequest(request("library.artwork")) }
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, http.calls.size)
        http.status = 200
        advanceTimeBy(1); runCurrent()
        assertTrue(artist.await().ok); assertTrue(cover.await().ok)
        assertEquals(listOf("metadata.entity.detail", "metadata.entity.detail", "library.artwork"), http.calls.map { it.second.name })
        assertEquals(RemoteConnectionPhase.CONNECTING, transport.connection.value.phase)
    }

    @Test fun rejectedCommandsAreNotReplayedOrQueuedBehindAFullWindow() = runTest {
        val http = Http { testScheduler.currentTime }.apply { status = 429 }
        val transport = transport(http)
        assertEquals(CoreErrorCode.BUSY, transport.sendRequest(request("playback.next", "command")).error?.code)
        assertEquals(CoreErrorCode.BUSY, transport.sendRequest(request("playback.play", "command")).error?.code)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(listOf("playback.next"), http.calls.map { it.second.name })
    }

    @Test fun persistent429IsBoundedAndCancellationDoesNotSendThePendingRead() = runTest {
        val http = Http { testScheduler.currentTime }.apply { status = 429 }
        val transport = transport(http)
        val pending = async { transport.sendRequest(request("catalog.sources")) }
        runCurrent(); advanceTimeBy(60_001); runCurrent()
        assertEquals(CoreErrorCode.BUSY, pending.await().error?.code)
        assertEquals(2, http.calls.size)
        val cancelled = launch { transport.sendRequest(request("library.artwork")) }
        runCurrent(); cancelled.cancelAndJoin()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(2, http.calls.size)
    }

    @Test fun closingAConnectionDoesNotSendQueuedOrNewRequests() = runTest {
        val http = Http { testScheduler.currentTime }
        val transport = transport(http)
        repeat(4) { transport.sendRequest(request("catalog.query")) }
        val pending = async { transport.sendRequest(request("catalog.sources")) }
        runCurrent(); transport.close()
        assertEquals(CoreErrorCode.NOT_READY, transport.sendRequest(request("catalog.query")).error?.code)
        advanceTimeBy(1_250); runCurrent()
        assertEquals(CoreErrorCode.NOT_READY, pending.await().error?.code)
        assertEquals(4, http.calls.size)
    }
}
