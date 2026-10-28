package com.twilightecho.android.coreclient

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteProtocolNegotiationTest {
    private class Desktop(private val status: String) : RemoteHttpExecutor {
        val bodies = mutableListOf<String>()
        override fun executeGet(url: String, headers: Map<String, String>) = RemoteHttpSimpleResponse(200, status)
        override fun streamSseEvents(url: String, headers: Map<String, String>, isCancelled: () -> Boolean, onEvent: (String, String) -> Unit) {}
        override fun executePostJson(url: String, headers: Map<String, String>, bodyJson: String): RemoteHttpSimpleResponse {
            bodies += bodyJson
            return RemoteHttpSimpleResponse(200, """{"kind":"response","requestId":"r","epoch":"e","ok":true,"value":{}}""")
        }
    }

    private fun TestScope.transport(desktop: Desktop) = RemoteNetworkCoreTransport("desktop", bearerToken = "t",
        scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler), httpExecutor = desktop)

    private suspend fun RemoteNetworkCoreTransport.sent(desktop: Desktop) =
        sendRequest(CoreRequestEnvelope(requestId = "r", epoch = "e", kind = "query", name = "search.songs"))
            .let { DefaultCoreClient.DefaultJson.parseToJsonElement(desktop.bodies.last()).jsonObject }

    @Test fun bothSidesPickTheHighestSharedVersion() {
        assertEquals(2, negotiateRemoteProtocol(RemoteProtocolRange(1, 3), RemoteProtocolRange(1, 2)))
        assertEquals(1, negotiateRemoteProtocol(RemoteProtocolRange(1, 1), RemoteProtocolRange(1, 2)))
        assertNull(negotiateRemoteProtocol(RemoteProtocolRange(3, 4), RemoteProtocolRange(1, 2)))
    }

    @Test fun requestsAreAddressedToTheNegotiatedDesktopAndZone() = runTest {
        val desktop = Desktop("""{"serverId":"desktop-a","coreBridge":true,"coreBridgeVersion":1,"tls":true,
            "protocolVersion":1,"protocolVersions":{"min":1,"max":5},"zones":[{"zoneId":"local","name":"书房"}]}""")
        val remote = transport(desktop)
        val status = remote.checkStatus().getOrThrow()
        assertEquals(RemoteProtocolRange(1, 5), status.protocolVersions)
        assertEquals(listOf(RemoteZone("local", "书房")), status.zones)
        assertEquals(REMOTE_PROTOCOL_RANGE.max, remote.negotiate(status))
        val body = remote.sent(desktop)
        assertEquals(REMOTE_PROTOCOL_RANGE.max, body["protocolVersion"]!!.jsonPrimitive.int)
        assertEquals("desktop-a", body["targetId"]!!.jsonPrimitive.content)
        assertEquals(REMOTE_LOCAL_ZONE, body["zoneId"]!!.jsonPrimitive.content)
    }

    @Test fun aDesktopFromBeforeNegotiationGetsOnlyTheFieldsItAccepts() = runTest {
        val desktop = Desktop("""{"serverId":"desktop-a","coreBridge":true,"coreBridgeVersion":1,"protocolVersion":1}""")
        val remote = transport(desktop)
        assertEquals(1, remote.negotiate(remote.checkStatus().getOrThrow()))
        val body = remote.sent(desktop)
        assertEquals(1, body["protocolVersion"]!!.jsonPrimitive.int)
        assertFalse(body.containsKey("targetId")); assertFalse(body.containsKey("zoneId"))
    }

    @Test fun noSharedVersionMeansNoConnection() = runTest {
        val desktop = Desktop("""{"serverId":"d","protocolVersion":9,"protocolVersions":{"min":9,"max":9},"zones":[{"zoneId":"local"}]}""")
        val remote = transport(desktop)
        assertNull(remote.negotiate(remote.checkStatus().getOrThrow()))
    }

    @Test fun bridgeRefusalsDecodeAsTypedErrors() = runTest {
        for (code in listOf("UNSUPPORTED_PROTOCOL", "WRONG_TARGET", "UNKNOWN_ZONE")) {
            val response = DefaultCoreClient.DefaultJson.decodeFromString(CoreResponseEnvelope.serializer(),
                """{"kind":"response","requestId":"r","epoch":"e","ok":false,"error":{"code":"$code","message":"m"}}""")
            assertEquals(code, response.error!!.code.name)
        }
    }
}
