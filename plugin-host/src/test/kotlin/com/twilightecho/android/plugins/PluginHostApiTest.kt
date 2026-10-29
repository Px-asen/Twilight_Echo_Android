package com.twilightecho.android.plugins

import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

internal class MemorySettings(var text: String = "{}") : PluginSettingsStore {
    override fun read() = text
    override fun write(value: String) { text = value }
}

internal val testRuntime = AndroidPluginRuntime(apiVersion = 2, entry = "android/provider.js", permissions = listOf("network", "settings"),
    networkHosts = listOf("api.test", "other.test"), mediaHosts = listOf("*.cdn.test"), cleartextHosts = listOf("api.test", "other.test", "img.cdn.test"))

internal fun PluginHostApi.reply(method: String, args: JsonObject): JsonObject =
    pluginJson.parseToJsonElement(call(method, args.toString(), System.nanoTime() + 30_000_000_000L)).jsonObject
internal fun PluginHostApi.ok(method: String, args: JsonObject = JsonObject(emptyMap())): JsonElement =
    reply(method, args).also { assertEquals(it.toString(), JsonPrimitive(true), it["ok"]) }.getValue("value")
internal fun PluginHostApi.failure(method: String, args: JsonObject = JsonObject(emptyMap())): String =
    reply(method, args).also { assertEquals(it.toString(), JsonPrimitive(false), it["ok"]) }.getValue("error").jsonPrimitive.content
internal fun args(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (key, value) -> key to jsonOf(value) })
internal fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is ByteArray -> JsonPrimitive(b64(value))
    is List<*> -> JsonArray(value.map(::jsonOf))
    else -> throw IllegalArgumentException("$value")
}

/** Expected values were produced by Node's crypto and zlib, which back the SDK reference host. */
class PluginHostApiTest {
    private fun host(runtime: AndroidPluginRuntime = testRuntime, settings: PluginSettingsStore = MemorySettings(), log: (String, String) -> Unit = { _, _ -> }) =
        PluginHostApi(runtime, settings, PluginNetwork(), log)
    private val data = "Twilight Echo 音源 plugin payload, longer than one block.".utf8()

    @Test fun settingsFollowTheReferenceHost() {
        assertEquals("插件未声明 settings 权限", host(testRuntime.copy(permissions = listOf("network"))).failure("settings.get"))
        val store = MemorySettings("""{"theme":"dark"}""")
        val api = host(settings = store)
        assertEquals(JsonPrimitive("dark"), api.ok("settings.get", args("key" to "theme")))
        assertEquals(JsonNull, api.ok("settings.get", args("key" to "missing")))
        api.ok("settings.set", args("key" to "cookie", "value" to buildJsonObject { put("a", 1) }))
        api.ok("settings.delete", args("key" to "theme"))
        assertEquals("""{"cookie":{"a":1}}""", store.text)
        assertEquals(pluginJson.parseToJsonElement(store.text), api.ok("settings.get"))
        assertEquals("设置键无效", api.failure("settings.set", args("key" to "", "value" to 1)))
        assertEquals("设置键无效", api.failure("settings.delete", args("key" to "k".repeat(129))))
        assertEquals("插件设置过大", api.failure("settings.set", args("key" to "big", "value" to "x".repeat(256_000))))
        assertEquals("""{"cookie":{"a":1}}""", store.text)
    }

    @Test fun unknownMethodsLogLevelsSleepAndRandom() {
        val logs = mutableListOf<Pair<String, String>>()
        val api = host(log = { level, message -> logs += level to message })
        assertEquals("插件未获准调用宿主能力：fs.readFile", api.failure("fs.readFile"))
        api.ok("log", args("level" to "warn", "message" to "careful")); api.ok("log", args("level" to "trace", "message" to 42))
        assertEquals(listOf("warn" to "careful", "info" to "42"), logs)
        val started = System.nanoTime(); api.ok("sleep", args("ms" to 999_999))
        assertTrue(System.nanoTime() - started < 3_000_000_000L)
        assertEquals(16, unb64(api.ok("crypto.random", args("size" to 16)).jsonPrimitive.content).size)
        for (size in listOf(1.5, -1, 65537, "x")) assertEquals("随机数长度无效", api.failure("crypto.random", args("size" to size)))
    }

    @Test fun hashesHmacsAndPbkdf2() {
        val api = host()
        fun text(method: String, vararg pairs: Pair<String, Any?>) = api.ok(method, args(*pairs)).jsonPrimitive.content
        assertEquals("O7JWH4os42P1hx5Etj7NIXaYHS/YF6I57TH9PPQ29hs=", text("crypto.hash", "algorithm" to "sha256", "data" to data))
        assertEquals("mQafWDrPNmA3X3rKXE1A8Q==", text("crypto.hash", "algorithm" to "md5", "data" to data))
        assertEquals("u8sklA6MWqB5UuBl48jciXeD1Zg=", text("crypto.hmac", "algorithm" to "sha1", "key" to "", "data" to data))
        assertEquals("kWOZaGV8Qk+A91KpW7Sg2kYRRkVr/8R4l8IW6FDoNNU=", text("crypto.hmac", "algorithm" to "sha256", "key" to ByteArray(32) { 7 }, "data" to data))
        assertEquals("7q28t6IgXKLGpaJc3GHtG/TrKxUEqXQmCahPhxCar9io/npjrXWlbmmC3rOFjQSU3Yg=",
            text("crypto.pbkdf2", "password" to "password".utf8(), "salt" to "salt".utf8(), "iterations" to 4097, "keyLength" to 50, "digest" to "sha1"))
        assertEquals("YjhEZiZNqtxBRAGMa9hkZIJys02omA0xUh/8zpKuADs=",
            text("crypto.pbkdf2", "password" to "", "salt" to "salt".utf8(), "iterations" to 2, "keyLength" to 32, "digest" to "sha256"))
        assertEquals("PBKDF2 参数过大", api.failure("crypto.pbkdf2", args("password" to "", "salt" to "", "iterations" to 1_000_001, "keyLength" to 32, "digest" to "sha256")))
        assertEquals("Digest method not supported", api.failure("crypto.hash", args("algorithm" to "whirlpool", "data" to data)))
    }

    @Test fun aesModesMatchNode() {
        val api = host()
        val key16 = hex("000102030405060708090a0b0c0d0e0f"); val key32 = ByteArray(32) { 7 }; val iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        fun cipher(vararg pairs: Pair<String, Any?>) = api.ok("crypto.cipher", args(*pairs)).jsonObject
        fun roundTrip(expected: String, vararg pairs: Pair<String, Any?>) {
            assertEquals(expected, cipher(*pairs).getValue("data").jsonPrimitive.content)
            val decrypt = pairs.toMap() + mapOf("data" to expected, "decrypt" to true)
            assertEquals(b64(pairs.toMap()["data"] as ByteArray), cipher(*decrypt.toList().toTypedArray()).getValue("data").jsonPrimitive.content)
        }
        roundTrip("+XsSi/BGj9DPDZVBXy89mmY933lWkM1YSoB9SThKXbTXv+34NPjJ0qrt0JAqlmnuqvzLfMjg+oSE8yE37cATfQ==",
            "algorithm" to "aes-128-ecb", "key" to key16, "data" to data)
        roundTrip("w1KAV1QjfzEawP/04+A+eMNSgFdUI38xGsD/9OPgPng=", "algorithm" to "aes-128-ecb", "key" to key16, "data" to ByteArray(32) { 1 }, "padding" to false)
        roundTrip("ve4IhC4E6nFA2y14yMWGx4u11u30oHpyLDAYS/5mFnic8i7E7QkIAcQBJnLRSy3JLrTGfnyixHI+Ex5MWy9mTQ==",
            "algorithm" to "aes-256-cbc", "key" to key32, "iv" to iv, "data" to data)
        roundTrip("MtCuhF01WTy3FL1vXDZEMgFnbZCX7lDYw8Qdmx79Zoa9EPZ6XDeE85zk+Xk7+jQfULcIPxzdNCg0Bi0=",
            "algorithm" to "aes-128-ctr", "key" to key16, "iv" to iv, "data" to data, "padding" to false)

        val sealed = "1hTHgk4ZEY8Z+gtC+pE5iebfYzzXLNVCzTjEX3L7Pp+WE9ng3qh3gHePhPBpIVEAmvTibXUhLrYCaYs="
        val gcm = arrayOf("algorithm" to "aes-256-gcm", "key" to key32, "iv" to hex("cafebabefacedbaddecaf888"), "aad" to "header".utf8(), "padding" to false)
        for ((length, tag) in listOf(16 to "fl6cAuJyG7YUr7VtXDKRYQ==", 12 to "fl6cAuJyG7YUr7Vt")) {
            val encrypted = cipher(*gcm, "data" to data, "authTagLength" to length)
            assertEquals(sealed, encrypted.getValue("data").jsonPrimitive.content)
            assertEquals(tag, encrypted.getValue("authTag").jsonPrimitive.content)
            assertEquals(b64(data), cipher(*gcm, "data" to sealed, "authTag" to tag, "authTagLength" to length, "decrypt" to true).getValue("data").jsonPrimitive.content)
        }
        assertEquals("Unsupported state or unable to authenticate data",
            api.failure("crypto.cipher", args(*gcm, "data" to sealed, "authTag" to "AAAAAAAAAAAAAAAAAAAAAA==", "authTagLength" to 16, "decrypt" to true)))
        assertEquals("不支持的加密算法 aes-128-ofb", api.failure("crypto.cipher", args("algorithm" to "aes-128-ofb", "key" to key16, "data" to data)))
        assertEquals("Invalid key length", api.failure("crypto.cipher", args("algorithm" to "aes-256-cbc", "key" to key16, "iv" to iv, "data" to data)))
        assertEquals("Invalid initialization vector", api.failure("crypto.cipher", args("algorithm" to "aes-128-cbc", "key" to key16, "iv" to key16.copyOf(8), "data" to data)))
    }

    @Test fun rsaPublicEncryptAcceptsBothPemKinds() {
        val pkcs1 = "-----BEGIN RSA PUBLIC KEY-----\nMIGJAoGBANfa0jbFr7IEgdzgRiUIp2UpZa60vOhrJQ9iLzyQgSCKEtkHn9uCb+Bu\n98smG+KkCjy08xkIv2UwyPfOKlXoUDxARCURlrPEBpw9zlumnPfTf8SIhxpm+lyh\n" +
            "GT87HHAhgB67r4wP+b11+/dDMqDINGXtekJxolrmQ0+b9gFD/xh/AgMBAAE=\n-----END RSA PUBLIC KEY-----\n"
        val spki = "-----BEGIN PUBLIC KEY-----\nMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDX2tI2xa+yBIHc4EYlCKdlKWWu\ntLzoayUPYi88kIEgihLZB5/bgm/gbvfLJhvipAo8tPMZCL9lMMj3zipV6FA8QEQl\n" +
            "EZazxAacPc5bppz303/EiIcaZvpcoRk/OxxwIYAeu6+MD/m9dfv3QzKgyDRl7XpC\ncaJa5kNPm/YBQ/8YfwIDAQAB\n-----END PUBLIC KEY-----\n"
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(unb64(
            "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBANfa0jbFr7IEgdzgRiUIp2UpZa60vOhrJQ9iLzyQgSCKEtkHn9uCb+Bu98smG+KkCjy08xkIv2UwyPfOKlXoUDxARCURlrPEBpw9zlumnPfTf8SIhxpm+lyhGT87HHAhgB67r4wP+b11+/dDMqDINGXtekJxolrmQ0+b9gFD/xh/AgMBAAECgYEA0Crc3iup9DE6mCvGGgMNhJb21BTDthVWiU50JaFqllnyMVGUDhruD/CUklsvO1Rd9wu/Ad6YzlrNBYbS90Ry0tJs/vTjQI/QfOeXGAWycgCBxvDwvX8lSCkNszOzBeh9aZ/4ocNpBkHRItjJNZbLMfxno5BMKOlK9TBLAVv7c1kCQQD2qIM8o0oAI58+XG7ERO2IFBBi9hDgom3gH8ItfNPdwlYZSTxZPrCa5yLb6DCAj/OpHrIpgkKg3VqtygK5bBGzAkEA4Aem3OrN3AHApFOJVEfpnrHSt3C+zX6Sla0JDyMutdQskcBVYEqDnYd+t3suGYVYCsMNb8KSxltSiPd9ZrJABQJAWFU02oxnvfED4GNdz7AXmLoGvEn+9VN2IeN9uKiRf0NPbjLKB/PpbzADTdmzNPdYfWJez3q0vcNH7CECJMFM/QJAO3LsXa1VaW9xnmmohJv8q6cGgrVBfKh834kmYUT1zTx7g0XlogLEAFaHUss2RYF0Bo8SDrk0V7y+HyiFt23PvQJAYrk93iqsEHbPD4KbhZ6xNuVkQuC1oTKOk9QBaCJBKV9VazDpRwVNzqL9cST+J7wz/gstC6X1NixGoXpaF9P2oA==")))
        val api = host()
        fun encrypt(vararg pairs: Pair<String, Any?>) = unb64(api.ok("crypto.publicEncrypt", args(*pairs)).jsonPrimitive.content)
        val raw = ByteArray(128).also { it[1] = 9; it[127] = 5 }
        assertEquals("TbhCiwnAhlIELnHErwEVnAN9JunyVCLsz5UC+rA9vbvRLHSpp2CcfBpZQEMDDmaGCN9fAJmhQgPNdF8DvWkHhOS4bmRWe8ZvrRJzEMK50/eHSPGUBC3/ID8At0n0hORJkyUYxInUkAeeK/nt3BrqkRzMsxnYTLyP0GZinWPeObg=",
            b64(encrypt("key" to pkcs1, "padding" to 3, "data" to raw)))
        for (key in listOf(pkcs1, spki)) {
            val pkcs = encrypt("key" to key, "padding" to 1, "data" to data)
            assertArrayEquals(data, Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, privateKey) }.doFinal(pkcs))
            val oaep = encrypt("key" to key, "padding" to 4, "oaepHash" to "sha256", "data" to data)
            assertArrayEquals(data, Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                init(Cipher.DECRYPT_MODE, privateKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            }.doFinal(oaep))
            val sha1 = encrypt("key" to key, "padding" to 4, "data" to data)
            assertArrayEquals(data, Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").apply { init(Cipher.DECRYPT_MODE, privateKey) }.doFinal(sha1))
        }
        assertEquals("仅支持 PEM 格式的 RSA 公钥", api.failure("crypto.publicEncrypt", args("key" to "not a key", "padding" to 1, "data" to data)))
    }

    @Test fun zlibReadsNodeOutputAndRoundTrips() {
        val api = host()
        val text = "zlib round trip ".repeat(200).utf8()
        fun zlib(mode: String, data: Any, level: Any? = null) = api.ok("zlib.process", args("mode" to mode, "data" to data, "level" to level)).jsonPrimitive.content
        val deflated = "eJztx7ENABAQAMBVfjbRfCKI0JjeFLq77m7LEmucXmOvnHHd3d3d3d0//QGTtqn9"
        val gzipped = "H4sIAAAAAAAACu3HsQ0AEBAAwFV+NtF8IojQmN4UurvubssSa5xeY6+ccd3d3d3d3T/9ARCB5X6ADAAA"
        assertEquals(b64(text), zlib("inflate", deflated)); assertEquals(b64(text), zlib("unzip", deflated))
        assertEquals(b64(text), zlib("gunzip", gzipped)); assertEquals(b64(text), zlib("unzip", gzipped))
        assertEquals(b64(text), zlib("inflateRaw", "7cexDQAQEADAVX420XwiiNCY3hS6u+5uyxJrnF5jr5xx3d3d3d3dP/0B"))
        for ((pack, unpack) in listOf("gzip" to "gunzip", "deflate" to "inflate", "deflateRaw" to "inflateRaw"))
            for (level in listOf(null, 0, 9, 12, "9")) assertEquals("$pack $level", b64(text), zlib(unpack, zlib(pack, text, level)))
        assertEquals("unexpected end of file", api.failure("zlib.process", args("mode" to "inflate", "data" to unb64(deflated).copyOf(20))))
        assertEquals("unexpected end of file", api.failure("zlib.process", args("mode" to "gunzip", "data" to unb64(gzipped).copyOf(30))))
        assertEquals("不支持的压缩方式 brotliCompress", api.failure("zlib.process", args("mode" to "brotliCompress", "data" to text)))
        val bomb = Deflater().run {
            setInput(ByteArray(9 * 1024 * 1024)); finish()
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (!finished()) out.write(buffer, 0, deflate(buffer))
            end(); out.toByteArray()
        }
        assertEquals("压缩数据超过 8 MiB 上限", api.failure("zlib.process", args("mode" to "inflate", "data" to bomb)))
    }

    @Test fun qrImageValidatesText() {
        val api = host()
        for (text in listOf("", 42, "x".repeat(2049), null)) assertEquals("二维码内容无效", api.failure("qrImage", args("text" to text)))
        assertTrue(api.ok("qrImage", args("text" to "https://example.com/qr")).jsonPrimitive.content.startsWith("data:image/png;base64,"))
    }

    @Test fun grantsFollowTheSdkReferenceHost() {
        val api = host()
        fun denial(url: String) = runCatching { api.checkGrant(url.toHttpUrl()) }.exceptionOrNull()?.message
        for (url in listOf("https://api.test/a", "http://api.test:8080/a", "https://a.b.cdn.test/x.jpg", "http://img.cdn.test/x.jpg"))
            assertNull(url, denial(url))
        assertEquals("插件只能通过 HTTPS 访问 a.cdn.test", denial("http://a.cdn.test/x.jpg"))
        assertEquals("插件未获准访问 cdn.test", denial("https://cdn.test/x.jpg"))
        assertEquals("插件未获准访问 api.test.evil", denial("https://api.test.evil/"))
        assertEquals("插件请求不能包含账号信息", denial("https://user:pass@api.test/"))
        assertEquals("插件只能通过 HTTPS 访问 192.168.1.5", denial("http://192.168.1.5:4533/rest"))
        api.userServerOrigin = serverOriginOf("192.168.1.5:4533")
        assertEquals("http://192.168.1.5:4533", api.userServerOrigin)
        assertNull(denial("http://192.168.1.5:4533/rest/ping"))
        assertEquals("插件只能通过 HTTPS 访问 192.168.1.5", denial("http://192.168.1.5:8080/rest"))

        assertEquals("https://music.example.com", serverOriginOf(" HTTPS://Music.Example.com:443/jellyfin/ "))
        assertEquals("http://[fd00::1]:8096", serverOriginOf("http://[fd00::1]:8096/"))
        for (address in listOf("ftp://nas", "http://u:p@nas", "")) assertTrue(address, runCatching { serverOriginOf(address) }.isFailure)
    }

    @Test fun mediaUrlsUpgradeDeclaredHostsLikeTheSdk() {
        val api = host()
        fun upgraded(url: String) = api.upgrade(url.toHttpUrl()).toString()
        assertEquals("https://a.cdn.test/x.mp3", upgraded("http://a.cdn.test/x.mp3"))
        assertEquals("https://a.cdn.test:8443/x.mp3", upgraded("http://a.cdn.test:8443/x.mp3"))
        assertEquals("http://img.cdn.test/x.jpg", upgraded("http://img.cdn.test/x.jpg"))
        assertEquals("http://unknown.test/x", upgraded("http://unknown.test/x"))
        api.userServerOrigin = "http://a.cdn.test:4533"
        assertEquals("http://a.cdn.test:4533/rest/stream", upgraded("http://a.cdn.test:4533/rest/stream"))
    }

    @Test fun publicDnsRefusesPrivateAnswers() {
        fun guard(vararg addresses: String) = PublicAddressDns(object : Dns {
            override fun lookup(hostname: String) = addresses.map { InetAddress.getByName(it) }
        })
        assertEquals(1, guard("93.184.216.34").lookup("api.test").size)
        for (address in listOf("10.1.2.3", "127.0.0.1", "169.254.1.1", "192.168.0.10", "172.16.5.4", "::1", "fd12::1", "fe80::1", "224.0.0.1", "0.0.0.0"))
            assertThrows(address, UnknownHostException::class.java) { guard("93.184.216.34", address).lookup("api.test") }
    }
}
