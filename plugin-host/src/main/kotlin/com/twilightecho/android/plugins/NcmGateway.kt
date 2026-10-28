package com.twilightecho.android.plugins

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigInteger
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal val pluginJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
internal fun JsonObject.string(key: String, fallback: String = ""): String = (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback
internal fun String.utf8() = toByteArray(Charsets.UTF_8)
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
internal fun b64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
internal fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)
internal fun digest(name: String, value: ByteArray) = MessageDigest.getInstance(name).digest(value)
internal fun form(values: Map<String, String>) = values.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }

/** Every network hop is mediated outside the untrusted process; there is no ambient cookie jar. */
internal class PluginHttp {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            require(addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress ||
                ((it.address[0].toInt() and 254) == 252 && it.address.size == 16) }) { "插件不能访问内网地址" }
        } }).build()
    data class Reply(val status: Int, val bytes: ByteArray, val cookies: List<String>)
    fun request(url: String, hosts: List<String>, body: String? = null, headers: Map<String, String> = emptyMap(), maxBytes: Int = 8 * 1024 * 1024, downloadRedirects: Int = 0): Reply {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host in hosts && uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "插件未获准访问此网络地址" }
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> require(!k.equals("Host", true) && !k.equals("Connection", true)); builder.header(k, v) }
        if (body != null) builder.post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        return client.newCall(builder.build()).execute().use { response ->
            if (downloadRedirects > 0 && response.code in listOf(301, 302, 303, 307, 308)) {
                // Repository/package fetches may follow public HTTPS CDNs. Plugin network calls never broaden their grant.
                require(body == null && headers.isEmpty())
                val target = uri.resolve(response.header("Location") ?: error("下载重定向缺少地址"))
                response.close()
                return@use request(target.toString(), listOf(target.host ?: ""), maxBytes = maxBytes, downloadRedirects = downloadRedirects - 1)
            }
            val stream = response.body ?: error("服务器没有返回内容")
            require(stream.contentLength() <= maxBytes) { "服务器返回内容过大" }
            val output = java.io.ByteArrayOutputStream()
            stream.byteStream().use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) { val n = input.read(chunk); if (n < 0) break; require(output.size() + n <= maxBytes) { "服务器返回内容过大" }; output.write(chunk, 0, n) }
            }
            Reply(response.code, output.toByteArray(), response.headers.values("Set-Cookie").map { it.substringBefore(';') })
        }
    }
}

/** Protocol/crypto adapter for the very same upstream endpoint modules used by Desktop. */
internal class NcmGateway(private val http: PluginHttp, private val deviceId: String) {
    private val random = SecureRandom()
    private var publicKey: JsonObject? = null
    private val eapiKey = "e82ckenh8dichen8".utf8()
    private val xeapiKey = hex("ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84")
    private val hosts = listOf("music.163.com", "interface.music.163.com", "interface3.music.163.com")
    private val userAgent = "NeteaseMusic/9.1.65.240927161425(9001065);Dalvik/2.1.0 (Linux; U; Android 14)"
    fun request(args: JsonObject): JsonObject {
        val uri = args.string("uri")
        require(Regex("/api/[A-Za-z0-9_./-]+").matches(uri) && !uri.contains("..")) { "无效的网易云接口" }
        val data = (args["data"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
        val options = args["options"] as? JsonObject ?: JsonObject(emptyMap())
        val cookie = options.string("cookie").split(';').mapNotNull {
            val i = it.indexOf('='); if (i < 1) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap().toMutableMap()
        cookie.putIfAbsent("os", "pc"); cookie.putIfAbsent("appver", "3.1.17.204416")
        cookie.putIfAbsent("osver", "Microsoft-Windows-10-Professional-build-19045-64bit")
        cookie.putIfAbsent("deviceId", deviceId); cookie.putIfAbsent("__remember_me", "true")
        val crypto = options.string("crypto").ifBlank { "eapi" }
        data["e_r"] = JsonPrimitive(false)
        val headers = mutableMapOf("User-Agent" to options.string("ua").ifBlank { userAgent }, "Referer" to "https://music.163.com/")
        var url: String
        val encrypted: Map<String, String>
        when (crypto) {
            "weapi" -> {
                data["csrf_token"] = JsonPrimitive(cookie["__csrf"] ?: "")
                val secret = ByteArray(16) { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[random.nextInt(62)].code.toByte() }
                val first = b64(aes(JsonObject(data).toString().utf8(), "0CoJUm6Qyw8W8jud".utf8(), iv = "0102030405060708".utf8()))
                val params = b64(aes(first.utf8(), secret, iv = "0102030405060708".utf8()))
                val modulus = BigInteger("00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7", 16)
                val encSecKey = BigInteger(1, secret.reversedArray()).modPow(BigInteger("10001", 16), modulus).toString(16).padStart(256, '0')
                encrypted = mapOf("params" to params, "encSecKey" to encSecKey)
                url = "https://music.163.com/weapi/" + uri.removePrefix("/api/")
                headers["Cookie"] = cookie.entries.joinToString("; ") { it.key + "=" + it.value }
            }
            "eapi", "api" -> {
                val header = cookie.filterKeys { it in listOf("osver", "deviceId", "os", "appver", "MUSIC_U", "MUSIC_A", "__csrf") }.toMutableMap()
                header["requestId"] = "${System.currentTimeMillis()}_${random.nextInt(1000)}"
                header["buildver"] = (System.currentTimeMillis() / 1000).toString(); header["versioncode"] = "140"
                headers["Cookie"] = header.entries.joinToString("; ") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
                if (crypto == "eapi") {
                    data["header"] = JsonObject(header.mapValues { JsonPrimitive(it.value) })
                    val text = JsonObject(data).toString()
                    val hash = digest("MD5", "nobody${uri}use${text}md5forencrypt".utf8()).hex()
                    encrypted = mapOf("params" to aes("$uri-36cd479b6b5-$text-36cd479b6b5-$hash".utf8(), eapiKey).hex().uppercase())
                    url = "https://interface.music.163.com/eapi/" + uri.removePrefix("/api/")
                } else { encrypted = data.mapValues { (_, v) -> if (v is JsonPrimitive) v.content else v.toString() }; url = "https://interface.music.163.com$uri" }
            }
            "xeapi" -> {
                val key = publicKey ?: registerKey().also { publicKey = it }
                cookie["os"] = "android"; cookie["appver"] = "9.1.65"; cookie["osver"] = "14"; cookie["buildver"] = (System.currentTimeMillis() / 1000).toString()
                headers["Cookie"] = cookie.entries.joinToString("; ") { it.key + "=" + it.value }
                headers.putAll(mapOf("X-Client-Enc-State" to "ENCRYPTED", "x-aeapi" to "true", "x-deviceid" to deviceId,
                    "x-sdeviceid" to deviceId, "x-os" to "android", "x-osver" to "14", "x-appver" to "9.1.65", "x-buildver" to cookie.getValue("buildver")))
                cookie["MUSIC_U"]?.let { headers["x-music-u"] = it }
                data.remove("e_r")
                val body = form(data.mapValues { (_, v) -> if (v is JsonPrimitive) v.content else v.toString() })
                val plaintext = buildJsonObject { put("body", b64(body.utf8())); put("queryString", "e_r=true") }.toString().utf8()
                val dynamicKey = ByteArray(16).also(random::nextBytes)
                val inner = aes(plaintext, xeapiKey); val xor = ByteArray(16).also(random::nextBytes)
                val encoded = b64(ByteArray(inner.size) { (inner[it].toInt() xor xor[it and 15].toInt()).toByte() }).utf8()
                val rotation = (xor[0].toInt() and 15) % encoded.size
                val mid = xor + encoded.copyOfRange(rotation, encoded.size) + encoded.copyOfRange(0, rotation)
                val private = ByteArray(32).also(random::nextBytes)
                val ephemeral = x25519(private, byteArrayOf(9) + ByteArray(31))
                val shared = x25519(private, unb64(key.string("publicKey")))
                val derived = hmac(hmac(ByteArray(32), shared), ephemeral + byteArrayOf(1)).copyOf(16)
                val iv = ByteArray(12).also(random::nextBytes)
                val payload = Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, iv))
                    doFinal("${b64(dynamicKey)}|android|${key.string("sk")}".utf8())
                }
                encrypted = mapOf("B" to b64(aes(mid, dynamicKey)), "S" to b64(ephemeral + iv + payload), "R" to b64(aes((key.string("version") + "|").utf8(), xeapiKey)))
                url = "https://interface3.music.163.com/xeapi/" + uri.removePrefix("/api/")
            }
            else -> error("Android 不支持此网易云加密模式：$crypto")
        }
        val response = http.request(url, hosts, form(encrypted), headers)
        val bytes = if (crypto == "xeapi") {
            val decrypted = aes(response.bytes, eapiKey, decrypt = true)
            if (decrypted.size > 2 && decrypted[0] == 0x1f.toByte() && decrypted[1] == 0x8b.toByte())
                GZIPInputStream(decrypted.inputStream()).use { it.readBytes() } else decrypted
        } else response.bytes
        val result = pluginJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        return buildJsonObject {
            put("status", response.status); put("body", result)
            put("cookie", JsonArray(response.cookies.map(::JsonPrimitive)))
        }
    }
    private fun registerKey(): JsonObject {
        val nonce = (1..16).joinToString("") { random.nextInt(10).toString() }; val timestamp = System.currentTimeMillis().toString()
        val values = mapOf("appVersion" to "9.1.65", "currentKeyVersion" to "", "deviceId" to deviceId, "nonce" to nonce,
            "os" to "android", "requestType" to "active", "signature" to sign(timestamp, nonce), "t1" to "", "t2" to "", "timestamp" to timestamp, "uid" to "")
        val reply = http.request("https://interface.music.163.com/api/gorilla/anti/crawler/security/key/get", hosts, form(values), mapOf("User-Agent" to userAgent))
        val data = pluginJson.parseToJsonElement(reply.bytes.toString(Charsets.UTF_8)).jsonObject["data"]?.jsonObject ?: error("网易云密钥获取失败")
        check(MessageDigest.isEqual(sign(data.string("timestamp"), nonce).utf8(), data.string("signature").utf8())) { "网易云密钥签名不匹配" }
        return pluginJson.parseToJsonElement(aes(unb64(data.string("encryptedData")), xeapiKey, decrypt = true).toString(Charsets.UTF_8)).jsonObject.also {
            require(it.string("sk").isNotBlank() && unb64(it.string("publicKey")).size == 32) { "网易云密钥无效" }
        }
    }
    private fun sign(timestamp: String, nonce: String) = b64(hmac("mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g==".utf8(), (timestamp + nonce).utf8()))
    companion object {
        internal fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
        internal fun aes(data: ByteArray, key: ByteArray, decrypt: Boolean = false, iv: ByteArray? = null): ByteArray = Cipher.getInstance(if (iv == null) "AES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding").run {
            val mode = if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE
            if (iv == null) init(mode, SecretKeySpec(key, "AES")) else init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
        /** RFC 7748 Montgomery ladder; works on API 26 without a platform XDH provider. */
        internal fun x25519(scalar: ByteArray, coordinate: ByteArray): ByteArray {
            require(scalar.size == 32 && coordinate.size == 32)
            val k = scalar.copyOf(); k[0] = (k[0].toInt() and 248).toByte(); k[31] = ((k[31].toInt() and 127) or 64).toByte()
            val u = coordinate.copyOf(); u[31] = (u[31].toInt() and 127).toByte()
            val p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
            val x1 = BigInteger(1, u.reversedArray()); var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
            var x3 = x1; var z3 = BigInteger.ONE; var swap = 0
            for (t in 254 downTo 0) {
                val bit = (k[t / 8].toInt() ushr (t and 7)) and 1; swap = swap xor bit
                if (swap != 0) { val a = x2; x2 = x3; x3 = a; val b = z2; z2 = z3; z3 = b }; swap = bit
                val a = (x2 + z2).mod(p); val aa = a.multiply(a).mod(p); val b = (x2 - z2).mod(p); val bb = b.multiply(b).mod(p)
                val e = (aa - bb).mod(p); val c = (x3 + z3).mod(p); val d = (x3 - z3).mod(p)
                val da = d.multiply(a).mod(p); val cb = c.multiply(b).mod(p)
                x3 = (da + cb).modPow(BigInteger.TWO, p); z3 = x1.multiply((da - cb).modPow(BigInteger.TWO, p)).mod(p)
                x2 = aa.multiply(bb).mod(p); z2 = e.multiply(aa + BigInteger.valueOf(121665).multiply(e)).mod(p)
            }
            if (swap != 0) { x2 = x3; z2 = z3 }
            val bytes = x2.multiply(z2.modInverse(p)).mod(p).toByteArray().reversedArray().copyOf(32)
            require(bytes.any { it != 0.toByte() }) { "无效的 X25519 公钥" }; return bytes
        }
    }
}
