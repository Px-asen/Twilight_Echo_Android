package com.twilightecho.android.plugins

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock
import kotlin.math.min

/** One plugin's settings object (Keystore-encrypted on the device). */
internal interface PluginSettingsStore { fun read(): String; fun write(value: String) }

/** Refuses private, loopback, link-local and multicast answers, so a granted name cannot be pointed at the LAN. */
internal class PublicAddressDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = delegate.lookup(hostname).also { addresses ->
        // An IOException: OkHttp reports it as a failed call instead of crashing its dispatcher.
        if (addresses.any(::isPrivateAddress)) throw UnknownHostException("插件不能访问内网地址")
    }
}
internal fun isPrivateAddress(address: InetAddress) = address.isAnyLocalAddress || address.isLoopbackAddress ||
    address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress ||
    (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)

/** Only the server origin the user typed at sign-in may resolve to the LAN. */
internal class PluginNetwork(publicDns: Dns = PublicAddressDns(), serverDns: Dns = Dns.SYSTEM) {
    private val base = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    val public: OkHttpClient = base.newBuilder().dns(publicDns).build()
    val server: OkHttpClient = base.newBuilder().dns(serverDns).build()
}

internal fun HttpUrl.origin(): String {
    val name = if (host.contains(':')) "[$host]" else host
    return if (port == HttpUrl.defaultPort(scheme)) "$scheme://$name" else "$scheme://$name:$port"
}

/** A user-typed server address with its scheme; a bare `host:port` means plain HTTP, as the plugins assume. */
internal fun serverAddress(address: String): String =
    address.trim().let { if (Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(it)) it else "http://$it" }

/** The origin a user-typed server address grants. */
internal fun serverOriginOf(address: String): String {
    val url = serverAddress(address).toHttpUrlOrNull() ?: throw IllegalArgumentException("服务器地址必须使用 HTTP 或 HTTPS")
    require(url.username.isEmpty() && url.password.isEmpty()) { "服务器地址不能包含账号信息" }
    return url.origin()
}

internal fun hostMatches(patterns: List<String>, host: String) = patterns.any { pattern ->
    if (pattern.startsWith("*.")) host.endsWith(pattern.substring(1)) && host.length > pattern.length - 1 else pattern == host
}

/**
 * The app-process side of `__PLUGIN_HOST__.call` for ABI v2 (plugins repository `sdk/android`). It keeps
 * exact parity with that SDK's reference host (`harness.mjs`), grant checks included, so the offline plugin
 * tests describe what runs here.
 */
internal class PluginHostApi(
    private val runtime: AndroidPluginRuntime,
    private val settings: PluginSettingsStore,
    private val network: PluginNetwork,
    private val log: (level: String, message: String) -> Unit = { _, _ -> },
) : Closeable {
    private val networkHosts = runtime.networkHosts.map { it.lowercase() }
    private val mediaHosts = runtime.mediaHosts.map { it.lowercase() }
    private val cleartextHosts = runtime.cleartextHosts.map { it.lowercase() }
    /** Set by the sign-in form, never by the plugin. */
    @Volatile var userServerOrigin: String? = null
    private val random = SecureRandom()
    private var store: MutableMap<String, JsonElement>? = null
    private val bodies = object : LinkedHashMap<Int, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > MAX_STORED_BODIES
    }
    private var nextBody = 1
    private val sockets = Sockets()

    /** [deadline] is the System.nanoTime() by which the worker's invocation must finish. */
    @Synchronized fun call(method: String, argsJson: String, deadline: Long): String = try {
        val args = pluginJson.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        buildJsonObject { put("ok", true); put("value", dispatch(method, args, deadline)) }.toString()
    } catch (error: Exception) {
        buildJsonObject { put("ok", false); put("error", error.message ?: error.javaClass.simpleName) }.toString()
    } catch (error: StackOverflowError) {
        buildJsonObject { put("ok", false); put("error", "插件宿主调用失败") }.toString()
    }

    private fun dispatch(method: String, args: JsonObject, deadline: Long): JsonElement = when (method) {
        "settings.get", "settings.set", "settings.delete" -> settingsCall(method, args)
        "log" -> {
            val level = jsText(args["level"]).takeIf { it in LOG_LEVELS } ?: "info"
            log(level, jsText(args["message"], nullText = "undefined").take(2000)); JsonNull
        }
        "sleep" -> { Thread.sleep(min((jsNumber(args["ms"]).takeIf { !it.isNaN() } ?: 0.0).coerceIn(0.0, 2000.0).toLong(), remainingMs(deadline).coerceAtLeast(0))); JsonNull }
        "qrImage" -> {
            val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(!text.isNullOrEmpty() && text.length <= 2048) { "二维码内容无效" }
            JsonPrimitive(qrPngDataUrl(text))
        }
        "crypto.random" -> {
            val size = jsNumber(args["size"])
            require(size == Math.floor(size) && size in 0.0..65536.0) { "随机数长度无效" }
            JsonPrimitive(b64(ByteArray(size.toInt()).also(random::nextBytes)))
        }
        "crypto.hash" -> JsonPrimitive(b64(digest(HASHES[hashName(args["algorithm"])]!!, bytes(args["data"]))))
        "crypto.hmac" -> JsonPrimitive(b64(hmacOf(hashName(args["algorithm"]), bytes(args["key"])).doFinal(bytes(args["data"]))))
        "crypto.cipher" -> cipher(args)
        "crypto.publicEncrypt" -> publicEncrypt(args)
        "crypto.pbkdf2" -> pbkdf2(args, deadline)
        "zlib.process" -> JsonPrimitive(b64(zlib(args)))
        "http.request" -> httpRequest(args, deadline)
        "http.body" -> {
            val id = jsNumber(args["id"]).toInt()
            val entry = bodies[id] ?: error("响应内容已失效")
            val offset = jsNumber(args["offset"]).takeIf { !it.isNaN() }?.toInt()?.coerceIn(0, entry.length) ?: 0
            var end = min(entry.length, offset + BODY_CHUNK_CHARS)
            if (end < entry.length && end > offset && Character.isHighSurrogate(entry[end - 1])) end--
            val done = end >= entry.length
            if (done) bodies.remove(id)
            buildJsonObject { put("data", entry.substring(offset, end)); put("done", done) }
        }
        "ws.open", "ws.send", "ws.close", "ws.poll" -> sockets.call(method, args, deadline)
        else -> error("插件未获准调用宿主能力：$method")
    }

    // ── Settings ──────────────────────────────────────────────────────────
    private fun settingsCall(method: String, args: JsonObject): JsonElement {
        require("settings" in runtime.permissions) { "插件未声明 settings 权限" }
        val current = store ?: pluginJson.parseToJsonElement(settings.read()).jsonObject.toMutableMap().also { store = it }
        val key = jsText(args["key"], falsyEmpty = true)
        if (method == "settings.get") return if (key.isEmpty()) JsonObject(current) else current[key] ?: JsonNull
        require(key.isNotEmpty() && key.length <= 128) { "设置键无效" }
        val next = current.toMutableMap()
        if (method == "settings.set") next[key] = args["value"] ?: JsonNull else next.remove(key)
        val text = JsonObject(next).toString()
        require(text.length <= 256_000) { "插件设置过大" }
        settings.write(text); store = next
        return JsonNull
    }

    // ── Crypto ────────────────────────────────────────────────────────────
    private fun hashName(value: JsonElement?): String {
        val name = jsText(value).lowercase().removePrefix("rsa-").replace("-", "")
        require(name in HASHES) { "Digest method not supported" }
        return name
    }
    /** HMAC zero-pads short keys, so one zero byte stands in for the empty key `SecretKeySpec` rejects. */
    private fun hmacOf(hash: String, key: ByteArray): Mac = Mac.getInstance(HMACS.getValue(hash)).apply {
        init(SecretKeySpec(key.takeIf { it.isNotEmpty() } ?: ByteArray(1), algorithm))
    }

    private fun cipher(args: JsonObject): JsonElement {
        val algorithm = jsText(args["algorithm"], nullText = "undefined")
        val match = Regex("^aes-(128|192|256)-(ecb|cbc|ctr|gcm)$").matchEntire(algorithm) ?: error("不支持的加密算法 $algorithm")
        val (bits, mode) = match.destructured
        val key = bytes(args["key"])
        require(key.size * 8 == bits.toInt()) { "Invalid key length" }
        val decrypt = jsTruthy(args["decrypt"])
        val padding = !(args["padding"] is JsonPrimitive && (args["padding"] as JsonPrimitive).let { !it.isString && it.booleanOrNull == false })
        val cipher = Cipher.getInstance(when (mode) {
            "ecb", "cbc" -> "AES/${mode.uppercase()}/${if (padding) "PKCS5Padding" else "NoPadding"}"
            "ctr" -> "AES/CTR/NoPadding"
            else -> "AES/GCM/NoPadding"
        })
        val operation = if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE
        val spec = SecretKeySpec(key, "AES")
        val tagLength = jsNumber(args["authTagLength"]).takeIf { it > 0 }?.toInt() ?: 16
        when (mode) {
            "ecb" -> cipher.init(operation, spec)
            "cbc", "ctr" -> cipher.init(operation, spec, IvParameterSpec(bytes(args["iv"]).also { require(it.size == 16) { "Invalid initialization vector" } }))
            else -> {
                require(tagLength in 4..16) { "Invalid authentication tag length: $tagLength" }
                cipher.init(operation, spec, GCMParameterSpec(tagLength * 8, bytes(args["iv"]).also { require(it.isNotEmpty()) { "Invalid initialization vector" } }))
                if (jsTruthy(args["aad"])) cipher.updateAAD(bytes(args["aad"]))
            }
        }
        val data = bytes(args["data"])
        return buildJsonObject {
            when {
                mode != "gcm" -> put("data", b64(cipher.doFinal(data)))
                decrypt -> put("data", b64(try { cipher.doFinal(data + bytes(args["authTag"])) }
                    catch (_: AEADBadTagException) { error("Unsupported state or unable to authenticate data") }))
                else -> {
                    val sealed = cipher.doFinal(data)
                    put("data", b64(sealed.copyOfRange(0, sealed.size - tagLength)))
                    put("authTag", b64(sealed.copyOfRange(sealed.size - tagLength, sealed.size)))
                }
            }
        }
    }

    private fun publicEncrypt(args: JsonObject): JsonElement {
        val key = rsaPublicKey(jsText(args["key"]))
        val data = bytes(args["data"])
        val cipher = when (jsNumber(args["padding"])) {
            1.0 -> Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, key) }
            3.0 -> {
                require(data.size == (key.modulus.bitLength() + 7) / 8) { "data too small/large for key size" }
                Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            }
            4.0 -> {
                val hash = HASHES.getValue(jsText(args["oaepHash"], falsyEmpty = true).ifEmpty { "sha1" }.let { hashName(JsonPrimitive(it)) })
                Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                    init(Cipher.ENCRYPT_MODE, key, OAEPParameterSpec(hash, "MGF1", MGF1ParameterSpec(hash), PSource.PSpecified.DEFAULT))
                }
            }
            else -> error("不支持的 RSA 填充方式")
        }
        return JsonPrimitive(b64(cipher.doFinal(data)))
    }

    private fun pbkdf2(args: JsonObject, deadline: Long): JsonElement {
        val iterations = jsNumber(args["iterations"]); val keyLength = jsNumber(args["keyLength"])
        require(!(iterations > 1_000_000) && !(keyLength > 1024)) { "PBKDF2 参数过大" }
        require(iterations >= 1 && iterations == Math.floor(iterations)) { "PBKDF2 迭代次数无效" }
        require(keyLength >= 0 && keyLength == Math.floor(keyLength)) { "PBKDF2 密钥长度无效" }
        val mac = hmacOf(hashName(args["digest"]), bytes(args["password"]))
        val salt = bytes(args["salt"])
        val out = ByteArray(keyLength.toInt()); val u = ByteArray(mac.macLength); val t = ByteArray(mac.macLength)
        var block = 1; var offset = 0
        while (offset < out.size) {
            mac.update(salt); mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0); u.copyInto(t)
            for (i in 1 until iterations.toInt()) {
                if (i and 4095 == 0 && remainingMs(deadline) <= 0) error("插件调用超时")
                mac.update(u); mac.doFinal(u, 0)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val count = min(t.size, out.size - offset)
            t.copyInto(out, offset, 0, count); offset += count; block++
        }
        return JsonPrimitive(b64(out))
    }

    // ── zlib (no Brotli: Android has no codec, and the harness refuses it too) ─
    private fun zlib(args: JsonObject): ByteArray {
        val data = bytes(args["data"])
        val level = (args["level"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            ?.takeIf { it == Math.floor(it) && it in -1.0..9.0 }?.toInt() ?: -1
        return when (val mode = jsText(args["mode"], nullText = "undefined")) {
            "gzip" -> capped { output -> object : GZIPOutputStream(output) { init { def.setLevel(level) } }.use { it.write(data) } }
            "deflate", "deflateRaw" -> Deflater(level, mode == "deflateRaw").run {
                try { setInput(data); finish(); capped { output -> val buffer = ByteArray(64 * 1024); while (!finished()) output.write(buffer, 0, deflate(buffer)) } }
                finally { end() }
            }
            "inflate" -> inflate(data, raw = false)
            "inflateRaw" -> inflate(data, raw = true)
            "gunzip" -> gunzip(data)
            "unzip" -> if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) gunzip(data) else inflate(data, raw = false)
            else -> error("不支持的压缩方式 $mode")
        }
    }
    private fun capped(write: (java.io.OutputStream) -> Unit): ByteArray {
        val output = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) { check(count + len <= MAX_ZLIB_OUTPUT) { "压缩数据超过 8 MiB 上限" }; super.write(b, off, len) }
            override fun write(b: Int) { check(count + 1 <= MAX_ZLIB_OUTPUT) { "压缩数据超过 8 MiB 上限" }; super.write(b) }
        }
        write(output); return output.toByteArray()
    }
    private fun inflate(data: ByteArray, raw: Boolean): ByteArray {
        val inflater = Inflater(raw)
        try {
            inflater.setInput(data)
            return capped { output ->
                val buffer = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val count = try { inflater.inflate(buffer) } catch (failure: DataFormatException) { error(failure.message ?: "incorrect header check") }
                    if (count == 0 && inflater.needsDictionary()) error("Missing dictionary")
                    if (count == 0 && inflater.needsInput()) error("unexpected end of file")
                    output.write(buffer, 0, count)
                }
            }
        } finally { inflater.end() }
    }
    private fun gunzip(data: ByteArray): ByteArray = try {
        GZIPInputStream(ByteArrayInputStream(data)).use { input -> capped { output -> input.copyTo(output, 64 * 1024) } }
    } catch (_: java.io.EOFException) { error("unexpected end of file") }
    catch (failure: java.util.zip.ZipException) { error(failure.message ?: "incorrect header check") }

    // ── HTTP ──────────────────────────────────────────────────────────────
    /** The SDK reference host's grant rule; the sign-in server origin bypasses it. */
    internal fun checkGrant(url: HttpUrl) {
        if (userServerOrigin != null && url.origin() == userServerOrigin) return
        val host = url.host
        if (!url.isHttps && host !in cleartextHosts) error("插件只能通过 HTTPS 访问 $host")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) error("插件请求不能包含账号信息")
        if (host in networkHosts || hostMatches(mediaHosts, host)) return
        error("插件未获准访问 $host")
    }
    private fun client(url: HttpUrl) = if (userServerOrigin != null && url.origin() == userServerOrigin) network.server else network.public

    private fun httpRequest(args: JsonObject, deadline: Long): JsonElement {
        require("network" in runtime.permissions) { "插件未声明 network 权限" }
        var url = parseHttpUrl(jsText(args["url"]))
        var method = jsText(args["method"], falsyEmpty = true).ifEmpty { "GET" }.uppercase()
        var headers = headerPairs(args["headers"])
        var body: ByteArray? = when {
            args.containsKey("text") -> jsText(args["text"], nullText = "null").utf8()
            jsTruthy(args["body"]) -> bytes(args["body"])
            else -> null
        }
        val follow = jsText(args["redirect"]) != "manual"
        var redirected = false
        var hop = 0
        while (true) {
            checkGrant(url)
            val response = execute(url, method, headers, body, deadline)
            val location = response.header("location")
            if (follow && response.code in REDIRECT_STATUSES && location != null) {
                response.close()
                if (hop >= 5) error("重定向次数过多")
                val next = url.resolve(location) ?: error("不支持的协议 ${location.substringBefore(':')}:")
                if (response.code == 303 || ((response.code == 301 || response.code == 302) && method == "POST")) {
                    if (method != "HEAD") method = "GET"
                    body = null
                    headers = headers.filterNot { it.first.startsWith("content-") }
                }
                if (next.origin() != url.origin()) headers = headers.filterNot { it.first == "authorization" }
                url = next; redirected = true; hop++
                continue
            }
            return response.use { reply ->
                val bytes = reply.body?.byteStream()?.let { readCapped(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
                val type = reply.header("content-type")?.lowercase().orEmpty()
                val textual = type.isEmpty() || TEXTUAL.containsMatchIn(type)
                val text = if (textual) utf8OrNull(bytes) else null
                val payload = text ?: b64(bytes)
                buildJsonObject {
                    put("status", reply.code); put("statusText", reply.message); put("url", url.toString()); put("redirected", redirected)
                    put("headers", responseHeaders(reply.headers)); put("encoding", if (text != null) "utf8" else "base64")
                    if (payload.length > BODY_CHUNK_CHARS) put("bodyId", storeBody(payload)) else put("body", payload)
                }
            }
        }
    }

    private fun execute(url: HttpUrl, method: String, headers: List<Pair<String, String>>, body: ByteArray?, deadline: Long): Response {
        val timeout = min(HTTP_TIMEOUT_MS, remainingMs(deadline) - 2_000)
        if (timeout < 500) error("插件调用超时")
        val request = Request.Builder().url(url).headers(pluginHeaders(headers)).apply {
            method(method, body?.toRequestBody(null) ?: if (method in BODY_METHODS) ByteArray(0).toRequestBody(null) else null)
        }.build()
        return client(url).newCall(request).apply { timeout().timeout(timeout, TimeUnit.MILLISECONDS) }.execute()
    }
    private fun storeBody(text: String): Int { val id = nextBody++; bodies[id] = text; return id }

    /**
     * Opens a resolved media URL for the player. It applies the http.request grants on every hop, and the SDK's
     * HTTPS upgrade of declared hosts.
     */
    internal fun openMedia(target: String, headers: List<Pair<String, String>>, range: String?, timeoutMs: Long,
        onCall: (Call) -> Unit = {}): Response {
        var url = upgrade(parseHttpUrl(target))
        var sent = headers.map { it.first.lowercase() to it.second }.filter { allowedHeader(it.first) && it.first != "range" }
        for (hop in 0..5) {
            checkGrant(url)
            val request = Request.Builder().url(url).headers(pluginHeaders(sent)).apply {
                header("Accept-Encoding", "identity"); range?.let { header("Range", it) }
            }.build()
            val call = client(url).newCall(request).apply { timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS) }
            onCall(call)
            val response = call.execute()
            val location = response.header("location")
            if (response.code !in REDIRECT_STATUSES || location == null) return response
            response.close()
            val next = url.resolve(location) ?: throw java.io.IOException("音频重定向地址无效")
            if (next.origin() != url.origin()) sent = sent.filterNot { it.first == "authorization" }
            url = upgrade(next)
        }
        throw java.io.IOException("音频重定向次数过多")
    }
    internal fun upgrade(url: HttpUrl): HttpUrl {
        if (url.isHttps || (userServerOrigin != null && url.origin() == userServerOrigin) || url.host in cleartextHosts) return url
        if (url.host !in networkHosts && !hostMatches(mediaHosts, url.host)) return url
        return url.newBuilder().scheme("https").port(if (url.port == 80) 443 else url.port).build()
    }

    // ── WebSocket ─────────────────────────────────────────────────────────
    private inner class Sockets {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        /** Sockets that have not reported `close` yet; [open] gets each one once OkHttp returns it. */
        private val live = HashSet<Int>()
        private val open = HashMap<Int, WebSocket>()
        private val queues = HashMap<Int, ArrayDeque<JsonObject>>()
        private val queuedChars = HashMap<Int, Long>()
        private var next = 1

        fun call(method: String, args: JsonObject, deadline: Long): JsonElement {
            require("network" in runtime.permissions) { "插件未声明 network 权限" }
            return when (method) {
                "ws.open" -> openSocket(args)
                "ws.poll" -> poll(args, deadline)
                else -> {
                    val socket = lock.withLock { open[jsNumber(args["id"]).toInt()] }
                    if (method == "ws.send") {
                        socket ?: error("WebSocket 已关闭")
                        val sent = if (args.containsKey("text")) socket.send(jsText(args["text"], nullText = "null"))
                            else socket.send(bytes(args["binary"]).toByteString())
                        check(sent) { "WebSocket 已关闭" }
                    } else socket?.close(jsNumber(args["code"]).takeIf { !it.isNaN() && it != 0.0 }?.toInt() ?: 1000,
                        jsText(args["reason"], falsyEmpty = true).ifEmpty { null })
                    JsonNull
                }
            }
        }

        private fun openSocket(args: JsonObject): JsonElement {
            val raw = jsText(args["url"])
            val scheme = raw.substringBefore(':', "").lowercase()
            require(scheme == "wss" || (userServerOrigin != null && scheme == "ws")) { "WebSocket 只能使用 WSS" }
            val url = parseHttpUrl((if (scheme == "wss") "https" else "http") + raw.substring(scheme.length))
            checkGrant(url)
            val request = Request.Builder().url(url).headers(pluginHeaders(headerPairs(args["headers"]))).apply {
                val protocols = (args["protocols"] as? JsonArray)?.map { jsText(it) }.orEmpty()
                if (protocols.isNotEmpty()) header("Sec-WebSocket-Protocol", protocols.joinToString(", "))
            }.build()
            val id = lock.withLock {
                require(live.size < MAX_SOCKETS) { "WebSocket 连接过多" }
                next++.also { live += it; queues[it] = ArrayDeque(); queuedChars[it] = 0 }
            }
            val socket = client(url).newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) =
                    push(id, webSocket) { put("type", "open"); put("protocol", response.header("Sec-WebSocket-Protocol").orEmpty()) }
                override fun onMessage(webSocket: WebSocket, text: String) = push(id, webSocket) { put("type", "message"); put("text", text) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                    push(id, webSocket) { put("type", "message"); put("binary", bytes.base64()) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(id, code, reason, null)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    finish(id, 1006, "", t.message ?: "WebSocket error")
            })
            lock.withLock { if (id in live) open[id] = socket else socket.cancel() }
            return buildJsonObject { put("id", id) }
        }

        private fun push(id: Int, socket: WebSocket, event: JsonObjectBuilder.() -> Unit) {
            val item = buildJsonObject { put("id", id); event() }
            val overflow = lock.withLock {
                val queue = queues[id]?.takeIf { id in live } ?: return
                val chars = (queuedChars[id] ?: 0) + item.toString().length
                if (chars > MAX_SOCKET_BACKLOG_CHARS) true else { queue += item; queuedChars[id] = chars; changed.signalAll(); false }
            }
            if (overflow) { socket.cancel(); finish(id, 1009, "", "WebSocket 消息积压过多") }
        }
        private fun finish(id: Int, code: Int, reason: String, error: String?) = lock.withLock {
            if (!live.remove(id)) return@withLock
            open.remove(id)
            val queue = queues.getValue(id)
            if (error != null) queue += buildJsonObject { put("id", id); put("type", "error"); put("message", error) }
            queue += buildJsonObject { put("id", id); put("type", "close"); put("code", code); put("reason", reason) }
            changed.signalAll()
        }

        private fun poll(args: JsonObject, deadline: Long): JsonElement {
            val ids = (args["ids"] as? JsonArray)?.map { jsNumber(it).toInt() }.orEmpty()
            var wait = TimeUnit.MILLISECONDS.toNanos(min((jsNumber(args["timeoutMs"]).takeIf { !it.isNaN() } ?: 0.0).coerceIn(0.0, 2000.0).toLong(),
                remainingMs(deadline).coerceAtLeast(0)))
            return lock.withLock {
                while (ids.none { queues[it]?.isNotEmpty() == true } && wait > 0) wait = changed.awaitNanos(wait)
                JsonArray(ids.flatMap { id ->
                    val queue = queues[id] ?: return@flatMap emptyList()
                    queue.toList().also {
                        queue.clear(); queuedChars[id] = 0
                        if (id !in live) { queues.remove(id); queuedChars.remove(id) }
                    }
                })
            }
        }

        fun close() = lock.withLock {
            open.values.forEach { it.cancel() }
            live.clear(); open.clear(); queues.clear(); queuedChars.clear(); changed.signalAll()
        }
    }

    /** Drops connections and stored bodies when the worker unloads the plugin. */
    @Synchronized fun reset() { sockets.close(); bodies.clear() }
    override fun close() = reset()

    private companion object {
        const val BODY_CHUNK_CHARS = 128 * 1024
        const val MAX_STORED_BODIES = 8
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val MAX_ZLIB_OUTPUT = 8 * 1024 * 1024
        const val MAX_SOCKETS = 4
        const val MAX_SOCKET_BACKLOG_CHARS = 8L * 1024 * 1024
        const val HTTP_TIMEOUT_MS = 20_000L
        val LOG_LEVELS = setOf("debug", "info", "warn", "error", "silent")
        val HASHES = mapOf("md5" to "MD5", "sha1" to "SHA-1", "sha224" to "SHA-224", "sha256" to "SHA-256", "sha384" to "SHA-384", "sha512" to "SHA-512")
        val HMACS = mapOf("md5" to "HmacMD5", "sha1" to "HmacSHA1", "sha224" to "HmacSHA224", "sha256" to "HmacSHA256", "sha384" to "HmacSHA384", "sha512" to "HmacSHA512")
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val BODY_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
        val FORBIDDEN_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding", "upgrade", "keep-alive", "te", "trailer", "accept-encoding", "expect")
        val TEXTUAL = Regex("^text/|json|xml|javascript|ecmascript|x-www-form-urlencoded|graphql")
    }

    private fun allowedHeader(name: String) = name !in FORBIDDEN_HEADERS && !name.startsWith("proxy-")
    /** OkHttp rejects non-ASCII header values; send them as UTF-8 rather than failing the request. */
    private fun pluginHeaders(pairs: List<Pair<String, String>>) =
        Headers.Builder().apply { pairs.forEach { (name, value) -> addUnsafeNonAscii(name, value) } }.build()
    private fun headerPairs(value: JsonElement?): List<Pair<String, String>> = (value as? JsonArray).orEmpty().mapNotNull { pair ->
        val items = pair as? JsonArray ?: return@mapNotNull null
        jsText(items.getOrNull(0), nullText = "undefined").lowercase() to jsText(items.getOrNull(1), nullText = "undefined")
    }.filter { allowedHeader(it.first) }
}

/** Fetch-style response headers: sorted lower-case names, repeated values joined, `set-cookie` kept separate. */
internal fun responseHeaders(headers: Headers): JsonArray {
    val grouped = sortedMapOf<String, MutableList<String>>()
    for ((name, value) in headers) grouped.getOrPut(name.lowercase()) { mutableListOf() } += value
    return buildJsonArray {
        for ((name, values) in grouped) {
            if (name == "set-cookie" || name == "content-encoding" || name == "content-length") continue
            add(buildJsonArray { add(name); add(values.joinToString(", ")) })
        }
        grouped["set-cookie"]?.forEach { add(buildJsonArray { add("set-cookie"); add(it) }) }
    }
}

internal fun parseHttpUrl(value: String): HttpUrl {
    val scheme = value.substringBefore(':', "").lowercase()
    require(scheme == "http" || scheme == "https") { "不支持的协议 $scheme:" }
    return value.toHttpUrlOrNull() ?: throw IllegalArgumentException("无效的网址")
}

internal fun readCapped(input: InputStream, max: Int): ByteArray {
    val output = ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer); if (count < 0) break
        require(output.size() + count <= max) { "响应内容过大" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun utf8OrNull(bytes: ByteArray): String? {
    if (bytes.contains(0)) return null
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { null }
}

private fun remainingMs(deadline: Long) = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())

/** PKCS#8 `PUBLIC KEY` (SPKI) or PKCS#1 `RSA PUBLIC KEY` PEM, as Node's crypto.publicEncrypt accepts. */
internal fun rsaPublicKey(pem: String): RSAPublicKey {
    val match = Regex("-----BEGIN (RSA )?PUBLIC KEY-----([\\s\\S]*?)-----END (RSA )?PUBLIC KEY-----").find(pem)
        ?: throw IllegalArgumentException("仅支持 PEM 格式的 RSA 公钥")
    val der = java.util.Base64.getMimeDecoder().decode(match.groupValues[2])
    val spec = if (match.groupValues[1].isEmpty()) X509EncodedKeySpec(der) else {
        var position = 0
        fun length(): Int {
            val first = der[position++].toInt() and 255
            if (first < 0x80) return first
            return (0 until (first and 0x7f)).fold(0) { acc, _ -> (acc shl 8) or (der[position++].toInt() and 255) }
        }
        fun integer(): BigInteger {
            require(der[position++] == 0x02.toByte()) { "RSA 公钥格式无效" }
            val size = length(); return BigInteger(der.copyOfRange(position, position + size)).also { position += size }
        }
        try {
            require(der[position++] == 0x30.toByte()) { "RSA 公钥格式无效" }; length()
            RSAPublicKeySpec(integer(), integer())
        } catch (_: IndexOutOfBoundsException) { throw IllegalArgumentException("RSA 公钥格式无效") }
    }
    return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
}

// JavaScript coercions, so arguments behave as in the reference host.
/** `String(value)`, or `String(value || '')` when [falsyEmpty]. */
private fun jsText(value: JsonElement?, nullText: String = "", falsyEmpty: Boolean = false): String = when {
    falsyEmpty && !jsTruthy(value) -> ""
    value == null -> nullText
    value is JsonNull -> if (nullText.isEmpty()) "" else "null"
    value is JsonPrimitive -> value.content
    else -> value.toString()
}
private fun jsNumber(value: JsonElement?): Double = when (value) {
    null, JsonNull -> Double.NaN
    is JsonPrimitive -> value.booleanOrNull?.takeIf { !value.isString }?.let { if (it) 1.0 else 0.0 }
        ?: value.content.trim().let { if (it.isEmpty() && value.isString) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
    else -> Double.NaN
}
private fun jsTruthy(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonPrimitive -> if (value.isString) value.content.isNotEmpty() else value.booleanOrNull ?: value.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: true
    else -> true
}
/** Base64 as Node's Buffer reads it: either alphabet, padding and whitespace optional. */
private fun bytes(value: JsonElement?): ByteArray {
    val text = jsText(value, falsyEmpty = true).filterNot { it.isWhitespace() || it == '=' }.replace('-', '+').replace('_', '/')
    return java.util.Base64.getDecoder().decode(text.substring(0, text.length - text.length % 4 + if (text.length % 4 == 1) 0 else text.length % 4))
}
