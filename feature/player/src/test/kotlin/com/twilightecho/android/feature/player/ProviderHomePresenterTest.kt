package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CollectionSort
import com.twilightecho.android.coreclient.CollectionSortField
import com.twilightecho.android.plugins.MobileProvider
import com.twilightecho.android.plugins.PluginEntry
import com.twilightecho.android.plugins.ProviderPanel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderHomePresenterTest {
    private val json = Json
    private fun parse(value: String): JsonElement = json.parseToJsonElement(value)

    private val qqPluginId = "com.twilightecho.provider.qqmusic"
    private val biliPluginId = "com.twilightecho.provider.bilibili"

    private fun entry(id: String, name: String) = PluginEntry(
        id = id,
        name = name,
        version = "0.1.0",
        description = "",
        enabled = true
    )

    private fun qqProvider(): MobileProvider = MobileProvider(
        id = "qqmusic",
        name = "QQ 音乐",
        capabilities = listOf("search", "stream"),
        methods = listOf(
            "checkLogin",
            "searchSongs",
            "fetchRecommendSongs",
            "fetchRecommendPlaylists",
            "fetchUserLibrary",
            "fetchPlaylistTracksPage"
        ),
        panels = listOf(ProviderPanel(id = "qqmusic-settings", title = "QQ 音乐设置", command = "qqmusic.settings")),
        ui = parse(
            """{
                "color": "#31c27c",
                "streamingHome": {"requiresLogin": false, "subtitle": "榜单与个性化推荐"},
                "streamingSections": [
                    {"id": "daily", "title": "每日 30 首", "eyebrow": "为你挑选", "description": "按听歌偏好刷新", "method": "fetchRecommendSongs", "args": ["daily"], "requiresLogin": true},
                    {"id": "up", "title": "巅峰 · 飙升榜", "eyebrow": "QQ 音乐榜", "description": "热度上升最快", "method": "fetchRecommendSongs", "args": ["toplist:62"], "requiresLogin": false}
                ]
            }"""
        ).jsonObject
    )

    private fun biliProvider(): MobileProvider = MobileProvider(
        id = "bilibili",
        name = "哔哩哔哩",
        capabilities = listOf("search", "stream"),
        methods = listOf("checkLogin", "searchSongs", "fetchUserLibrary", "fetchPlaylistTracks"),
        ui = parse("""{"color": "#fb7299", "streamingHome": {"requiresLogin": true}}""").jsonObject
    )

    @Test
    fun homeProviderOptionsOrderNcmThenSectionProvidersThenLibraryProviders() {
        val entries = listOf(
            entry(biliPluginId, "哔哩哔哩音源"),
            entry(qqPluginId, "QQ 音乐音源")
        )
        val described = mapOf(
            qqPluginId to qqProvider(),
            biliPluginId to biliProvider()
        )
        val withNcm = buildHomeProviderOptions(ncmEnabled = true, enabledV2Entries = entries, describedProviders = described)
        assertEquals(
            listOf(NCM_PLUGIN_ID, qqPluginId, biliPluginId),
            withNcm.map { it.pluginId }
        )
        assertEquals(listOf("网易云音乐", "QQ 音乐", "哔哩哔哩"), withNcm.map { it.name })

        val withoutNcm = buildHomeProviderOptions(ncmEnabled = false, enabledV2Entries = entries, describedProviders = described)
        assertEquals(listOf(qqPluginId, biliPluginId), withoutNcm.map { it.pluginId })
    }

    @Test
    fun unacceptedDisclaimerGatesProviderCallsWithoutShowingDesktopError() = runTest {
        var accepted = false
        var loggedIn = false
        val providerCalls = mutableListOf<String>()

        val presenter = ProviderHomePresenter(
            scope = backgroundScope,
            describeProvider = { qqProvider() },
            callProvider = { _, method, args ->
                providerCalls += method
                when (method) {
                    "checkLogin" -> if (loggedIn) {
                        parse("""{"loggedIn":true,"profile":{"userId":"10086","nickname":"晴天"}}""")
                    } else {
                        parse("""{"loggedIn":false}""")
                    }
                    "fetchRecommendSongs" -> {
                        val kind = args.firstOrNull()?.jsonPrimitive?.content.orEmpty()
                        parse("""[{"id":"song-$kind-1","title":"Song $kind","artist":"周杰伦","album":"叶惠美","duration":260}]""")
                    }
                    "fetchRecommendPlaylists" -> parse(
                        """[{"id":"rec-1","name":"华语流行精选","trackCount":25,"cover":"https://y.gtimg.cn/rec.jpg","playCount":88000}]"""
                    )
                    "fetchUserLibrary" -> parse(
                        """{"likedPlaylist":{"id":"like-1","name":"我喜欢","trackCount":12},
                           "playlists":[{"id":"my-1","name":"通勤歌单","trackCount":8}]}"""
                    )
                    else -> JsonArray(emptyList())
                }
            },
            runCommand = { _, command, _ ->
                assertEquals("qqmusic.settings", command)
                val valStr = if (accepted) "2026-04-v1" else ""
                parse(
                    """{"kind":"settings-form","submitCommand":"qqmusic.saveSettings",
                        "notice":"首次使用请阅读并同意免责声明。",
                        "fields":[{"key":"disclaimer","label":"免责声明确认","type":"select","required":true,"value":"$valStr",
                                   "options":[{"label":"未确认","value":""},{"label":"我已阅读并同意免责声明","value":"2026-04-v1"}]}]}"""
                )
            }
        )

        // 1. Before disclaimer is accepted: zero provider API calls, clean disclaimer gate state.
        presenter.selectProvider(qqPluginId, "QQ 音乐")
        runCurrent()
        val gated = presenter.state.value
        assertTrue(gated.requiresDisclaimer)
        assertEquals("首次使用请阅读并同意免责声明。", gated.disclaimerNotice)
        assertNull(gated.accountError)
        assertTrue(providerCalls.isEmpty())
        assertEquals(2, gated.sections.size)

        // 2. Disclaimer accepted while signed out: public section + recommended playlists load; login-only section stays locked.
        accepted = true
        presenter.refreshActive(force = true)
        runCurrent()
        val signedOut = presenter.state.value
        assertFalse(signedOut.requiresDisclaimer)
        assertEquals(StreamingAccess.LOGGED_OUT, signedOut.access)
        val dailyOut = signedOut.sections.first { it.id == "daily" }
        val upOut = signedOut.sections.first { it.id == "up" }
        assertFalse(dailyOut.loaded)
        assertTrue(dailyOut.tracks.isEmpty())
        assertTrue(upOut.loaded)
        assertEquals(1, upOut.tracks.size)
        assertEquals(1, signedOut.recommendedPlaylists.size)
        assertNull(signedOut.likedPlaylist)

        // 3. Signed in: daily section + user library (liked + playlists) also load.
        loggedIn = true
        presenter.refreshActive(force = true)
        runCurrent()
        val signedIn = presenter.state.value
        assertEquals(StreamingAccess.READY, signedIn.access)
        assertEquals("晴天", signedIn.nickname)
        assertTrue(signedIn.sections.all { it.loaded && it.tracks.isNotEmpty() })
        assertEquals("我喜欢", signedIn.likedPlaylist?.title)
        assertEquals(1, signedIn.playlists.size)
    }

    @Test
    fun sectionAndPaginatedPlaylistDetailsSupportNavigationAndFiltering() = runTest {
        val presenter = ProviderHomePresenter(
            scope = backgroundScope,
            describeProvider = { qqProvider() },
            callProvider = { _, method, args ->
                when (method) {
                    "checkLogin" -> parse("""{"loggedIn":true,"profile":{"userId":"7","nickname":"测试"}}""")
                    "fetchRecommendSongs" -> parse("""[{"id":"s1","title":"晴天","artist":"周杰伦"}]""")
                    "fetchRecommendPlaylists" -> JsonArray(emptyList())
                    "fetchUserLibrary" -> parse(
                        """{"likedPlaylist":{"id":"liked","name":"我喜欢","trackCount":3},
                           "playlists":[{"id":"pl-9","name":"夜跑歌单","trackCount":3}]}"""
                    )
                    "fetchPlaylistTracksPage" -> {
                        val offset = args[1].jsonPrimitive.int
                        if (offset == 0) {
                            parse(
                                """{"tracks":[
                                    {"id":"t1","title":"Beta","artist":"A"},
                                    {"id":"t2","title":"Alpha","artist":"B"}
                                ],"total":3,"hasMore":true,"nextOffset":2}"""
                            )
                        } else {
                            parse(
                                """{"tracks":[{"id":"t3","title":"Gamma","artist":"C"}],
                                   "total":3,"hasMore":false,"nextOffset":3}"""
                            )
                        }
                    }
                    else -> JsonArray(emptyList())
                }
            },
            runCommand = { _, _, _ -> parse("""{"ok":true}""") }
        )

        presenter.selectProvider(qqPluginId, "QQ 音乐")
        runCurrent()

        presenter.openSection("up")
        assertNotNull(presenter.state.value.detail)
        assertEquals("巅峰 · 飙升榜", presenter.state.value.detail?.title)
        assertEquals(1, presenter.state.value.detail?.tracks?.size)

        presenter.openAllPlaylists()
        assertEquals("我的歌单", presenter.state.value.detail?.title)
        val nightRun = presenter.state.value.playlists.first()

        // Drill down from "我的歌单" -> "夜跑歌单"
        presenter.openCollection(nightRun)
        runCurrent()
        val firstPage = presenter.state.value.detail!!
        assertEquals("夜跑歌单", firstPage.title)
        assertEquals(listOf("Beta", "Alpha"), firstPage.tracks.map { it.title })
        assertEquals(2, firstPage.nextOffset)

        // Advance to page 2
        presenter.page(2)
        runCurrent()
        assertEquals(listOf("Gamma"), presenter.state.value.detail?.tracks?.map { it.title })

        // Sorting loads whole playlist across pages and sorts client-side
        presenter.playlistSort(CollectionSort(CollectionSortField.TITLE, descending = false))
        runCurrent()
        assertEquals(listOf("Alpha", "Beta", "Gamma"), presenter.state.value.detail?.tracks?.map { it.title })

        // Back pops to "我的歌单", and again closes detail
        presenter.closeDetail()
        assertEquals("我的歌单", presenter.state.value.detail?.title)
        presenter.closeDetail()
        assertNull(presenter.state.value.detail)
    }
}
