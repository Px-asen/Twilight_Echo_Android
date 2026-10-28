package com.twilightecho.android.coreclient

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopApprovalTest {
    private class Http : RemoteHttpExecutor {
        val paths = mutableListOf<String>()
        var decision = "pending"
        var commitment: String? = null
        var revealedNonce: String? = null
        override fun executeGet(url: String, headers: Map<String, String>) = RemoteHttpSimpleResponse(200, "{}")
        override fun streamSseEvents(url: String, headers: Map<String, String>, isCancelled: () -> Boolean, onEvent: (String, String) -> Unit) {}
        override fun executePostJson(url: String, headers: Map<String, String>, bodyJson: String): RemoteHttpSimpleResponse {
            val path = url.substringAfter("https://desktop")
            paths += path
            assertTrue(headers.isEmpty())
            val body = DefaultCoreClient.DefaultJson.parseToJsonElement(bodyJson).jsonObject
            return when (path) {
                "/api/pair/requests" -> {
                    commitment = body["commitment"]!!.jsonPrimitive.content
                    RemoteHttpSimpleResponse(202,
                        """{"requestId":"ticket","claimSecret":"private","expiresAt":120000,"serverNonce":"server-nonce"}""")
                }
                "/api/pair/reveal" -> {
                    val nonce = body["nonce"]!!.jsonPrimitive.content
                    assertEquals("the phone reveals the nonce it committed to", commitment,
                        base64Url(java.security.MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray())))
                    revealedNonce = nonce
                    RemoteHttpSimpleResponse(200, """{"state":"pending"}""")
                }
                "/api/pair/claim" -> {
                    assertEquals(setOf("requestId", "claimSecret"), body.keys)
                    assertTrue(bodyJson.contains("private"))
                    RemoteHttpSimpleResponse(200, """{"state":"$decision","token":"approved-token"}""")
                }
                else -> RemoteHttpSimpleResponse(200, "{}")
            }
        }
    }
    private val certificate = "ab".repeat(32)
    private fun TestScope.transport(http: Http) = RemoteNetworkCoreTransport("desktop", scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler), certificatePin = DesktopCertificatePin(certificate), httpExecutor = http)

    @Test fun explicitApprovalShowsTheCertificateBoundCodeBeforeAnyToken() = runTest {
        val http = Http()
        val remote = transport(http)
        var code: String? = null
        val result = async { remote.pairWithDesktopApproval("Phone") { code = it } }
        runCurrent()
        assertEquals(remotePairingCode(certificate, http.revealedNonce!!, "server-nonce"), code)
        assertFalse(result.isCompleted); assertNull(remote.bearerToken)
        http.decision = "approved"
        advanceTimeBy(1501); runCurrent()
        assertEquals("approved-token", result.await().getOrThrow())
        assertEquals(listOf("/api/pair/requests", "/api/pair/reveal", "/api/pair/claim"), http.paths)
    }
    @Test fun rejectionDoesNotCreateACredential() = runTest {
        val http = Http().apply { decision = "rejected" }
        val remote = transport(http)
        assertTrue(remote.pairWithDesktopApproval("Phone").exceptionOrNull()?.message?.contains("拒绝") == true)
        assertNull(remote.bearerToken)
    }
    @Test fun cancellationClosesThePendingDesktopPrompt() = runTest {
        val http = Http()
        val remote = transport(http)
        val job = launch { remote.pairWithDesktopApproval("Phone") }
        runCurrent(); job.cancelAndJoin()
        assertEquals(listOf("/api/pair/requests", "/api/pair/reveal", "/api/pair/cancel"), http.paths)
        assertNull(remote.bearerToken)
    }
    @Test fun aDesktopWithoutTheCodeExchangeIsNotPaired() = runTest {
        val http = object : RemoteHttpExecutor {
            val paths = mutableListOf<String>()
            override fun executeGet(url: String, headers: Map<String, String>) = RemoteHttpSimpleResponse(200, "{}")
            override fun streamSseEvents(url: String, headers: Map<String, String>, isCancelled: () -> Boolean, onEvent: (String, String) -> Unit) {}
            override fun executePostJson(url: String, headers: Map<String, String>, bodyJson: String): RemoteHttpSimpleResponse {
                paths += url.substringAfter("https://desktop")
                return if (url.endsWith("/requests")) RemoteHttpSimpleResponse(202, """{"requestId":"t","claimSecret":"s","expiresAt":1}""")
                else RemoteHttpSimpleResponse(200, "{}")
            }
        }
        val remote = RemoteNetworkCoreTransport("desktop", scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler), certificatePin = DesktopCertificatePin(certificate), httpExecutor = http)
        assertTrue(remote.pairWithDesktopApproval("Phone").exceptionOrNull()?.message?.contains("更新电脑端") == true)
        assertEquals(listOf("/api/pair/requests", "/api/pair/cancel"), http.paths)
    }
    @Test fun pairingCodesMatchTheDesktopImplementation() {
        // Vectors from remotePairingCode in the desktop's src/core/cast/tlsIdentity.ts.
        assertEquals("133668", remotePairingCode("AB".repeat(32), "phone-nonce", "server-nonce"))
        assertEquals("495891", remotePairingCode("0".repeat(64), "x", "y"))
        assertEquals("889474", remotePairingCode("f00d".repeat(16), "nonceA_-", "nonceB"))
    }
    @Test fun anEmptyPinRecordsTheFirstCertificateAndThenRefusesAnyOther() {
        val pin = DesktopCertificatePin()
        val first = selfSignedTestCertificate().certificate
        pin.trustManager.checkServerTrusted(arrayOf(first), "ECDHE_ECDSA")
        assertEquals(sha256Hex(first.encoded), pin.fingerprint)
        pin.trustManager.checkServerTrusted(arrayOf(first), "ECDHE_ECDSA")
        assertThrows(DesktopCertificateMismatchException::class.java) {
            pin.trustManager.checkServerTrusted(arrayOf(selfSignedTestCertificate().certificate), "ECDHE_ECDSA")
        }
        val pinned = DesktopCertificatePin(sha256Hex(first.encoded).uppercase())
        pinned.trustManager.checkServerTrusted(arrayOf(first), "ECDHE_ECDSA")
        assertThrows(DesktopCertificateMismatchException::class.java) {
            DesktopCertificatePin(certificate).trustManager.checkServerTrusted(arrayOf(first), "ECDHE_ECDSA")
        }
    }
    @Test fun discoveryUpdatesSavedAddressesWithoutMergingDifferentComputers() {
        val known = DesktopCoreDevice("one", "Old", "http://192.168.1.2:1", false, true)
        val current = DesktopCoreDevice("one", "书房电脑", "http://192.168.1.20:2")
        val other = DesktopCoreDevice("two", "书房电脑", "http://192.168.1.30:3")
        val merged = mergeDesktopDevices(listOf(current, other), listOf(known))
        assertEquals(2, merged.size)
        assertEquals(current.baseUrl, merged.first { it.id == "one" }.baseUrl)
        assertTrue(merged.first { it.id == "one" }.trusted)
        assertFalse(merged.first { it.id == "two" }.trusted)
    }
    @Test fun discoveryPrefersThePhonesLanOverDesktopVpnAndVirtualAdapters() {
        fun ip(value: String) = InetAddress.getByName(value)
        val advertised = listOf(ip("169.254.1.153"), ip("198.18.0.1"), ip("172.19.1.157"))
        assertEquals("172.19.1.157", selectDesktopIpv4Address(advertised,
            listOf(ip("172.19.20.3") to 17))?.hostAddress)
        assertEquals("192.168.8.4", selectDesktopIpv4Address(listOf(ip("::1"), ip("169.254.1.1"), ip("192.168.8.4")),
            emptyList())?.hostAddress)
        assertNull(selectDesktopIpv4Address(listOf(ip("::1")), emptyList()))
    }
}
