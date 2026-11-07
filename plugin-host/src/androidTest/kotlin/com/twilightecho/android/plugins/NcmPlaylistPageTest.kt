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

/** The shipped NCM package and isolated worker; fixture HTTP models a partially restricted playlist. */
@RunWith(AndroidJUnit4::class)
class NcmPlaylistPageTest {
    @Test fun restrictedEntriesDoNotHideThePlaylistAndImportStillRequiresEveryId() = runBlocking {
        var reportedCount = 7
        var callbackUid = -1
        val routes = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            callbackUid = Binder.getCallingUid()
            val path = chain.request().url.encodedPath
            routes += path
            val body = when {
                path.endsWith("/playlist/detail") -> """{"code":200,"playlist":{"name":"国漫 · 版权夹具",
                    "trackCount":$reportedCount,"trackIds":[{"id":1},{"id":2},{"id":3},{"id":4},{"id":5},{"id":1}],
                    "tracks":[{"id":3,"name":"仍有歌单元数据","ar":[],"al":{"name":"原专辑"}}]}}"""
                path.endsWith("/song/detail") -> """{"code":200,"songs":[null,
                    {"id":4,"name":"下一首正常歌曲","ar":[{"name":"歌手"}],"dt":180000},
                    {"id":2,"name":"版权受限歌曲","ar":[null,{"name":"原歌手"}],"noCopyrightRcmd":{}},
                    {"id":1,"name":"正常歌曲","ar":[{"name":"歌手"}],"dt":180000}]}
                    """
                else -> error("Unexpected fixture route: $path")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidPluginRepository(context, PluginHttp(http)).use { repository ->
            val id = "com.twilightecho.provider.ncm"
            repository.initialize()
            val entry = repository.state.value.entries.single { it.id == id }
            if (entry.installedVersion != entry.version) repository.install(id)
            repository.setEnabled(id, true)
            repository.call(id, "logout")
            fun args(offset: Int, limit: Int) = buildJsonArray { add("77"); add(offset); add(limit) }
            val first = repository.call(id, "fetchPlaylistTracksPage", args(0, 4)).jsonObject
            assertEquals(6, first.getValue("total").jsonPrimitive.int)
            assertEquals(7, first.getValue("reportedTotal").jsonPrimitive.int)
            val tracks = first.getValue("tracks").jsonArray.map { it.jsonObject }
            assertEquals(listOf(1, 2, 3, 4), tracks.map { it.getValue("ncmSongId").jsonPrimitive.int })
            assertNull(tracks.first()["unavailableReason"])
            assertEquals("原平台版权受限", tracks[1].getValue("unavailableReason").jsonPrimitive.content)
            assertEquals("原歌手", tracks[1].getValue("artist").jsonPrimitive.content)
            assertEquals("仍有歌单元数据", tracks[2].getValue("title").jsonPrimitive.content)
            assertTrue(tracks[2].getValue("unavailableReason").jsonPrimitive.content.contains("无权访问"))
            assertEquals(4, first.getValue("nextOffset").jsonPrimitive.int)
            assertTrue(first.getValue("hasMore").jsonPrimitive.boolean)
            val last = repository.call(id, "fetchPlaylistTracksPage", args(4, 4)).jsonObject
            val tail = last.getValue("tracks").jsonArray.map { it.jsonObject }
            assertEquals(listOf(5, 1), tail.map { it.getValue("ncmSongId").jsonPrimitive.int })
            assertTrue(tail.first().getValue("unavailableReason").jsonPrimitive.content.contains("缺少元数据"))
            assertFalse(last.getValue("hasMore").jsonPrimitive.boolean)
            val failure = runCatching { repository.call(id, "readPlaylistImportPage", args(0, 4)) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("不完整的条目"))
            reportedCount = 6
            assertEquals(6, repository.call(id, "readPlaylistImportPage", args(0, 6))
                .jsonObject.getValue("songs").jsonArray.size)
            assertTrue(routes.all { it.endsWith("/playlist/detail") || it.endsWith("/song/detail") })
            assertTrue("Provider calls must cross the isolated UID boundary", callbackUid > 0 && callbackUid != context.applicationInfo.uid)
        }
    }
}
