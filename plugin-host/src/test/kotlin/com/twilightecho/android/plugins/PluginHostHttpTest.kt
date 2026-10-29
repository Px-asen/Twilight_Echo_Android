package com.twilightecho.android.plugins

import kotlinx.serialization.json.*
import okhttp3.Dns
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Runs `http.request`, media and WebSocket calls against loopback servers; every test host name resolves to them. */
class PluginHostHttpTest {
    private val loopback = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }
    private val server = TestHttpServer { route(it) }
    private val port = server.port
    private val api = PluginHostApi(testRuntime, MemorySettings(), PluginNetwork(publicDns = loopback))
    private val large = "a".repeat(131_071) + "😀" + "b".repeat(200_000)

    @After fun stop() { api.close(); server.close() }

    private fun route(request: TestRequest): TestReply {
        fun seen() = buildJsonObject {
            put("method", request.method); put("body", request.body.size)
            for (name in listOf("Authorization", "Content-Type", "X-Keep", "Host", "Accept-Encoding", "Range", "Referer", "Proxy-Authorization", "X-Name"))
                put(name.lowercase(), request.header(name) ?: "")
        }.toString()
        fun typed(type: String, body: ByteArray, vararg headers: Pair<String, String>) = TestReply(200, body, listOf("Content-Type" to type) + headers)
        fun redirect(status: Int, location: String) = TestReply(status, headers = listOf("Location" to location))
        return when (request.path) {
            "/text" -> typed("application/json; charset=utf-8", """{"hello":"世界"}""".utf8(),
                "X-Multi" to "b", "X-Multi" to "a", "Set-Cookie" to "a=1; Path=/", "Set-Cookie" to "b=2")
            "/binary" -> typed("image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 1, 2))
            "/latin" -> typed("text/plain", byteArrayOf(0x63, 0x61, 0x66, 0xe9.toByte()))
            "/large" -> typed("text/plain; charset=utf-8", large.utf8())
            "/huge" -> typed("application/octet-stream", ByteArray(8 * 1024 * 1024 + 1))
            "/echo" -> typed("application/json", seen().utf8())
            "/redirect" -> redirect(302, "http://other.test:$port/echo")
            "/see-other" -> redirect(303, "/echo")
            "/loop" -> redirect(302, "/loop")
            "/evil" -> redirect(302, "http://evil.test:$port/echo")
            "/media-redirect" -> redirect(302, "http://img.cdn.test:$port/media")
            "/media" -> TestReply(206, "0123".utf8(), listOf("Content-Type" to "audio/mpeg", "Content-Range" to "bytes 0-3/10", "X-Seen" to seen()))
            else -> TestReply(404)
        }
    }

    private fun request(vararg pairs: Pair<String, Any?>) = api.ok("http.request", args(*pairs)).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun echoed(reply: JsonObject) = pluginJson.parseToJsonElement(reply.text("body")).jsonObject

    @Test fun textResponsesKeepTheFetchHeaderShape() {
        val reply = request("url" to "http://api.test:$port/text")
        assertEquals(200, reply.getValue("status").jsonPrimitive.int)
        assertEquals("utf8", reply.text("encoding")); assertEquals("""{"hello":"世界"}""", reply.text("body"))
        assertEquals(false, reply.getValue("redirected").jsonPrimitive.boolean)
        assertEquals("http://api.test:$port/text", reply.text("url"))
        val headers = reply.getValue("headers").jsonArray.map { pair -> pair.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content } }
        assertEquals(listOf("set-cookie" to "a=1; Path=/", "set-cookie" to "b=2"), headers.takeLast(2))
        val names = headers.dropLast(2).map { it.first }
        assertEquals(names.sorted(), names); assertFalse("content-length" in names)
        assertEquals("b, a", headers.toMap()["x-multi"])
    }

    @Test fun binaryAndLargeBodies() {
        val binary = request("url" to "http://api.test:$port/binary")
        assertEquals("base64", binary.text("encoding")); assertEquals(b64(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 1, 2)), binary.text("body"))
        assertEquals("base64", request("url" to "http://api.test:$port/latin").text("encoding"))

        val stored = request("url" to "http://api.test:$port/large")
        assertNull(stored["body"])
        val id = stored.getValue("bodyId").jsonPrimitive.int
        val parts = mutableListOf<String>()
        while (true) {
            val chunk = api.ok("http.body", args("id" to id, "offset" to parts.sumOf { it.length })).jsonObject
            parts += chunk.text("data")
            if (chunk.getValue("done").jsonPrimitive.boolean) break
        }
        assertEquals(131_071, parts[0].length) // never splits the surrogate pair
        assertEquals(large, parts.joinToString(""))
        assertEquals("响应内容已失效", api.failure("http.body", args("id" to id, "offset" to 0)))
        assertEquals("响应内容过大", api.failure("http.request", args("url" to "http://api.test:$port/huge")))
    }

    @Test fun redirectsFollowFetchRules() {
        val post = request("url" to "http://api.test:$port/redirect", "method" to "post", "text" to "payload",
            "headers" to listOf(listOf("Authorization", "Bearer secret"), listOf("Content-Type", "text/plain"), listOf("X-Keep", "1")))
        assertTrue(post.getValue("redirected").jsonPrimitive.boolean)
        assertEquals("http://other.test:$port/echo", post.text("url"))
        val seen = echoed(post)
        assertEquals("GET", seen.text("method")); assertEquals("0", seen.text("body"))
        assertEquals("", seen.text("authorization")); assertEquals("", seen.text("content-type")); assertEquals("1", seen.text("x-keep"))

        val put = echoed(request("url" to "http://api.test:$port/see-other", "method" to "PUT", "body" to "AQID",
            "headers" to listOf(listOf("authorization", "Bearer secret"))))
        assertEquals("GET", put.text("method")); assertEquals("Bearer secret", put.text("authorization"))

        val manual = request("url" to "http://api.test:$port/redirect", "redirect" to "manual")
        assertEquals(302, manual.getValue("status").jsonPrimitive.int)
        assertEquals("http://other.test:$port/echo", manual.getValue("headers").jsonArray.first { it.jsonArray[0].jsonPrimitive.content == "location" }.jsonArray[1].jsonPrimitive.content)
        assertEquals("重定向次数过多", api.failure("http.request", args("url" to "http://api.test:$port/loop")))
        assertEquals("插件只能通过 HTTPS 访问 evil.test", api.failure("http.request", args("url" to "http://api.test:$port/evil")))
    }

    @Test fun hostControlledHeadersAreDroppedAndNonAsciiValuesSurvive() {
        val seen = echoed(request("url" to "http://api.test:$port/echo", "headers" to listOf(listOf("Host", "evil.test"),
            listOf("Proxy-Authorization", "x"), listOf("Accept-Encoding", "br"), listOf("X-Name", "汽水"))))
        assertEquals("api.test:$port", seen.text("host")); assertEquals("", seen.text("proxy-authorization"))
        assertEquals("gzip", seen.text("accept-encoding"))
        assertEquals("汽水", String(seen.text("x-name").toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
    }

    @Test fun permissionsGrantsAndTheUserServer() {
        val offline = PluginHostApi(testRuntime.copy(permissions = listOf("settings")), MemorySettings(), PluginNetwork(publicDns = loopback))
        assertEquals("插件未声明 network 权限", offline.failure("http.request", args("url" to "http://api.test:$port/text")))
        assertEquals("插件只能通过 HTTPS 访问 127.0.0.1", api.failure("http.request", args("url" to "http://127.0.0.1:$port/text")))
        api.userServerOrigin = "http://127.0.0.1:$port"
        assertEquals(200, request("url" to "http://127.0.0.1:$port/text").getValue("status").jsonPrimitive.int)
        assertEquals("不支持的协议 file:", api.failure("http.request", args("url" to "file:///etc/hosts")))

        val guarded = PluginHostApi(testRuntime, MemorySettings(), PluginNetwork(publicDns = PublicAddressDns(loopback)))
        assertEquals("插件不能访问内网地址", guarded.failure("http.request", args("url" to "http://api.test:$port/text")))
    }

    @Test fun mediaKeepsGrantsAcrossRedirects() {
        api.openMedia("http://api.test:$port/media-redirect",
            listOf("Authorization" to "secret", "Referer" to "https://api.test/", "Range" to "bytes=5-", "Host" to "evil.test"), "bytes=0-3", 5_000).use { response ->
            assertEquals(206, response.code); assertEquals("0123", response.body!!.string())
            val seen = pluginJson.parseToJsonElement(response.header("X-Seen")!!).jsonObject
            assertEquals("bytes=0-3", seen.text("range")); assertEquals("identity", seen.text("accept-encoding"))
            assertEquals("", seen.text("authorization")); assertEquals("https://api.test/", seen.text("referer"))
            assertEquals("img.cdn.test:$port", seen.text("host"))
        }
        assertThrows(IllegalStateException::class.java) { api.openMedia("http://api.test:$port/evil", emptyList(), null, 5_000) }
    }

    @Test fun webSocketsQueueEventsForPolling() {
        val socketServer = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val serverThread = thread { socketServer.accept().use { echoWebSocket(it) } }
        val origin = "127.0.0.1:${socketServer.localPort}"
        assertEquals("WebSocket 只能使用 WSS", api.failure("ws.open", args("url" to "ws://$origin/mqtt")))
        assertEquals("插件未获准访问 evil.test", api.failure("ws.open", args("url" to "wss://evil.test/mqtt")))
        api.userServerOrigin = "http://$origin"
        val id = api.ok("ws.open", args("url" to "ws://$origin/mqtt", "protocols" to listOf("mqtt"))).jsonObject.getValue("id").jsonPrimitive.int
        fun poll() = api.ok("ws.poll", args("ids" to listOf(id), "timeoutMs" to 2000)).jsonArray.map { it.jsonObject }
        val opened = poll().single()
        assertEquals("open", opened.text("type")); assertEquals("mqtt", opened.text("protocol"))
        api.ok("ws.send", args("id" to id, "text" to "ping"))
        assertEquals("ping", poll().single().text("text"))
        api.ok("ws.send", args("id" to id, "binary" to byteArrayOf(1, 2, 3)))
        assertEquals(b64(byteArrayOf(1, 2, 3)), poll().single().text("binary"))
        api.ok("ws.close", args("id" to id, "code" to 1000, "reason" to "done"))
        val events = mutableListOf<JsonObject>()
        repeat(5) { if (events.none { it.text("type") == "close" }) events += poll() }
        assertEquals(listOf("close"), events.map { it.text("type") })
        assertEquals(1000, events.single().getValue("code").jsonPrimitive.int)
        assertEquals("WebSocket 已关闭", api.failure("ws.send", args("id" to id, "text" to "late")))
        assertEquals(JsonNull, api.ok("ws.close", args("id" to id)))
        assertEquals(JsonArray(emptyList()), api.ok("ws.poll", args("ids" to listOf(id), "timeoutMs" to 0)))
        serverThread.join(5_000); socketServer.close()
    }

    /** A minimal RFC 6455 echo server: it answers the handshake, then reflects every frame, close included. */
    private fun echoWebSocket(client: Socket) {
        val input = client.getInputStream(); val output = client.getOutputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
        val lines = head.lines()
        fun header(name: String) = lines.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val accept = b64(digest("SHA-1", (header("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").utf8()))
        val protocol = header("Sec-WebSocket-Protocol")?.substringBefore(',')
        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n" +
            (protocol?.let { "Sec-WebSocket-Protocol: $it\r\n" } ?: "") + "\r\n").utf8())
        while (true) {
            val first = input.read(); if (first < 0) return
            val length = input.read() and 0x7f // the test frames are short and always masked
            val mask = input.readNBytes(4)
            val payload = input.readNBytes(length).also { for (i in it.indices) it[i] = (it[i].toInt() xor mask[i % 4].toInt()).toByte() }
            output.write(byteArrayOf((0x80 or (first and 0x0f)).toByte(), payload.size.toByte())); output.write(payload); output.flush()
            if (first and 0x0f == 8) return
        }
    }
}
