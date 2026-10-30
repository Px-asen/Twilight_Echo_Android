package com.twilightecho.android.plugins

import android.os.Binder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** Real repository, packaged NCM and isolated Binder worker; only HTTP responses are fixtures. */
@RunWith(AndroidJUnit4::class)
class NcmLargeLibraryTest {
    @Test fun largeUpstreamLibraryKeepsEveryPlaylistInOrderAcrossTheSandbox() = runBlocking {
        val count = 1000
        val playlists = buildJsonArray {
            repeat(count) { index -> add(buildJsonObject {
                put("id", 10_000 + index)
                put("name", "暮色歌单 $index 🎵")
                put("specialType", if (index == 0) 5 else 0)
                put("trackCount", index + 1)
                put("coverImgUrl", "https://p1.music.126.net/cover-$index.jpg")
                put("description", "晚风与回声 🎵 ".repeat(100))
                putJsonObject("creator") {
                    put("userId", if (index % 2 == 0) 42 else 99)
                    put("nickname", "听众 ${index % 2}")
                }
            }) }
        }
        val body = buildJsonObject { put("code", 200); put("playlist", playlists); put("more", false) }.toString()
        assertTrue("Must exceed the obsolete host response ceiling", body.length > 500_000)
        assertTrue(body.length < MAX_TRANSFER_CHARS)
        var libraryResponse = body
        val routes = Collections.synchronizedList(mutableListOf<String>())
        var callbackUid = -1
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            callbackUid = Binder.getCallingUid()
            val path = chain.request().url.encodedPath
            routes += path
            val response = when {
                path.endsWith("/user/playlist") -> libraryResponse
                path.endsWith("/w/nuser/account/get") || path.endsWith("/v1/user/detail/42") ->
                    """{"code":200,"profile":{"userId":42,"nickname":"大曲库测试听众","signature":"夜航","follows":1,"followeds":2}}"""
                else -> error("Unexpected fixture route: $path")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidPluginRepository(context, PluginHttp(client)).use { repository ->
            val id = "com.twilightecho.provider.ncm"
            repository.initialize()
            val entry = repository.state.value.entries.single { it.id == id }
            if (entry.installedVersion != entry.version) repository.install(id)
            repository.setEnabled(id, true)
            // The library test APK has its own settings, never the user's app/account.
            repository.call(id, "logout")
            assertTrue(repository.adoptAccountCookie(id, "MUSIC_U=large-library-fixture-not-a-real-account"))
            try {
                val result = repository.call(id, "fetchUserLibrary").jsonObject
                assertEquals(10_000, result.getValue("likedPlaylist").jsonObject.getValue("id").jsonPrimitive.int)
                val actual = result.getValue("playlists").jsonArray
                assertEquals(count - 1, actual.size)
                actual.forEachIndexed { position, element ->
                    val index = position + 1
                    val playlist = element.jsonObject
                    assertEquals(10_000 + index, playlist.getValue("id").jsonPrimitive.int)
                    assertEquals("暮色歌单 $index 🎵", playlist.getValue("name").jsonPrimitive.content)
                    assertEquals(index + 1, playlist.getValue("trackCount").jsonPrimitive.int)
                    assertEquals(index % 2 == 0, playlist.getValue("owned").jsonPrimitive.boolean)
                    assertTrue(playlist.getValue("cover").jsonPrimitive.content.contains("cover-$index.jpg"))
                }
                // A fresh read still succeeds; sliced response state must not leak between calls.
                assertEquals(result, repository.call(id, "fetchUserLibrary", buildJsonArray { add(true) }))
                assertEquals(2, routes.count { it.endsWith("/user/playlist") })
                assertTrue("The packaged provider must call from an isolated UID", callbackUid > 0 && callbackUid != context.applicationInfo.uid)

                // The HTTP safety budget still applies, and rejecting it must leave the provider usable.
                libraryResponse = " ".repeat(8 * 1024 * 1024 + 1)
                val failure = runCatching { repository.call(id, "fetchUserLibrary", buildJsonArray { add(true) }) }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(failure!!.message.orEmpty().contains("服务器返回内容过大"))
                libraryResponse = body
                assertEquals(result, repository.call(id, "fetchUserLibrary", buildJsonArray { add(true) }))
            } finally { repository.call(id, "logout") }
        }
    }
}
