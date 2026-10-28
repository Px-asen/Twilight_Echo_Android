package com.twilightecho.android.app

import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.plugins.PluginWorkerService
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Executes the packaged provider with account endpoint fixtures in the real isolated worker. */
@RunWith(AndroidJUnit4::class)
class NcmProfileGatewayTest {
    @Test fun packagedProviderCombinesAccountAndUserDetailAndClearsProfileOnLogout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = CountDownLatch(1)
        lateinit var worker: IBinder
        var cookie = "MUSIC_U=profile-fixture-not-a-real-account"
        val routes = mutableListOf<String>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { worker = service; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        instrumentation.runOnMainSync {
            check(context.bindService(Intent(context, PluginWorkerService::class.java), connection, Context.BIND_AUTO_CREATE))
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                val method = data.readString()
                val args = Json.parseToJsonElement(data.readString()!!).jsonObject
                val value: JsonElement = when (method) {
                    "settings.get" -> if (args["key"]?.jsonPrimitive?.content == "cookie") JsonPrimitive(cookie) else JsonNull
                    "settings.set" -> { if (args["key"]?.jsonPrimitive?.content == "cookie") cookie = args.getValue("value").jsonPrimitive.content; JsonNull }
                    "settings.delete" -> { if (args["key"]?.jsonPrimitive?.content == "cookie") cookie = ""; JsonNull }
                    "ncm" -> {
                        val uri = args.getValue("uri").jsonPrimitive.content
                        routes += uri
                        val profile = buildJsonObject {
                            put("userId", 42); put("nickname", "测试听众"); put("avatarUrl", "http://p1.music.126.net/profile-fixture.png")
                            if (uri == "/api/v1/user/detail/42") {
                                put("signature", "把生活听成一首歌。"); put("follows", 18); put("followeds", 26)
                            }
                        }
                        check(uri in listOf("/api/w/nuser/account/get", "/api/v1/user/detail/42")) { "Unexpected profile route $uri" }
                        buildJsonObject {
                            put("status", 200); put("cookie", JsonArray(emptyList()))
                            put("body", buildJsonObject { put("code", 200); put("profile", profile) })
                        }
                    }
                    else -> error("Unexpected profile host method $method")
                }
                reply?.writeString(buildJsonObject { put("ok", true); put("value", value) }.toString())
                return true
            }
        }
        fun execute(script: String?, input: String): JsonElement {
            val request = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                request.writeString("ncm-profile-fixture"); request.writeString(script); request.writeString(input); request.writeStrongBinder(callback)
                check(worker.transact(1, request, reply, 0))
                val result = Json.parseToJsonElement(reply.readString()!!).jsonObject
                check(result["ok"]?.jsonPrimitive?.boolean == true) { result.toString() }
                return result["value"] ?: JsonNull
            } finally { request.recycle(); reply.recycle() }
        }
        try {
            val script = ZipInputStream(context.assets.open("plugins/ncm.tep")).use { zip ->
                var found: String? = null
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "android.js") { found = zip.readBytes().toString(Charsets.UTF_8); break }
                }
                checkNotNull(found)
            }
            execute(script, """{"operation":"activate"}""")
            val login = execute(null, """{"method":"checkLogin","args":[]}""").jsonObject
            assertTrue(login.getValue("loggedIn").jsonPrimitive.boolean)
            val profile = login.getValue("profile").jsonObject
            // The provider retains the source URL; Android's artwork adapter upgrades CDN requests to HTTPS.
            assertEquals("http://p1.music.126.net/profile-fixture.png", profile.getValue("avatarUrl").jsonPrimitive.content)
            assertEquals("把生活听成一首歌。", profile.getValue("signature").jsonPrimitive.content)
            assertEquals(18, profile.getValue("follows").jsonPrimitive.int)
            assertEquals(26, profile.getValue("followeds").jsonPrimitive.int)
            assertEquals(profile, execute(null, """{"method":"getProfile","args":[]}"""))
            assertEquals(listOf("/api/w/nuser/account/get", "/api/v1/user/detail/42"), routes)
            execute(null, """{"method":"logout","args":[]}""")
            assertEquals(JsonNull, execute(null, """{"method":"getProfile","args":[]}"""))
        } finally { context.unbindService(connection) }
    }
}
