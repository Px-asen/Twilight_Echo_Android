package com.twilightecho.android.feature.player

import com.twilightecho.android.plugins.MobileProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderPageTest {
    private fun provider(ui: String = "{}", methods: List<String> = emptyList(), capabilities: List<String> = emptyList()) =
        MobileProvider("kugou", "酷狗音乐", capabilities, methods, ui = Json.parseToJsonElement(ui).jsonObject)
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test fun qrCodesFallBackToDesktopDefaultsAndDeniedCountsAsExpired() {
        val defaults = provider().qrStatusCodes()
        assertEquals(801, defaults.waiting); assertEquals(802, defaults.scanned); assertEquals(803, defaults.success)
        assertTrue(defaults.expired(800)); assertFalse(defaults.expired(801))
        val oauth = provider("""{"qrStatusCodes":{"waiting":-2,"scanned":null,"expired":-3,"denied":-4,"success":0}}""").qrStatusCodes()
        assertNull(oauth.scanned); assertTrue(oauth.expired(-3)); assertTrue(oauth.expired(-4)); assertEquals(0, oauth.success)
    }

    @Test fun tabsFollowDesktopsOnlineHome() {
        val ui = """{"streamingHome":{"requiresLogin":false},"streamingSections":[
            {"id":"daily","title":"每日推荐","method":"fetchRecommendSongs","args":["daily"],"requiresLogin":true},
            {"id":"hot","title":"热歌","method":"fetchToplists"},
            {"id":"fm","title":"私人 FM","method":"fetchPersonalFm"},
            {"id":"hot","title":"重复","method":"fetchRecommendSongs"}]}"""
        val kugou = provider(ui, listOf("fetchRecommendSongs", "fetchToplists", "fetchUserLibrary"))
        // A section whose method the plugin lacks, or whose id repeats, is left out.
        assertEquals(listOf("section:hot" to "热歌"), kugou.tabs(loggedIn = false))
        assertEquals(listOf("section:daily" to "每日推荐", "section:hot" to "热歌", "library" to "我的歌单"), kugou.tabs(loggedIn = true))
        assertEquals(listOf("daily"), kugou.sections().first().args.map { it.jsonPrimitive.content })
        val navidrome = provider("""{"streamingHome":{"requiresLogin":true}}""", listOf("fetchUserLibrary"))
        assertTrue(navidrome.tabs(false).isEmpty())
        assertEquals(listOf("library" to "我的歌单"), navidrome.tabs(true))
        // Without a home declaration the sections need an account, as on Desktop.
        assertTrue(provider(ui.replace("\"streamingHome\":{\"requiresLogin\":false},", ""), listOf("fetchToplists")).tabs(false).isEmpty())
    }

    @Test fun searchNeedsTheCapabilityAndTheMethod() {
        assertTrue(provider(methods = listOf("searchSongs"), capabilities = listOf("search")).searchable())
        assertFalse(provider(methods = listOf("searchSongs")).searchable())
        assertFalse(provider(capabilities = listOf("search")).searchable())
    }

    @Test fun trackListsComeFromArraysOrPages() {
        val tracks = """[{"id":"1","title":"A"},{"id":2,"title":"B"}]"""
        assertEquals(2, providerTrackArray(json(tracks)).size)
        listOf("tracks", "items", "songs").forEach { key -> assertEquals(2, providerTrackArray(json("""{"$key":$tracks,"total":9}""")).size) }
        assertTrue(providerTrackArray(JsonNull).isEmpty())
        assertTrue(providerTrackArray(null).isEmpty())
    }

    @Test fun queueEntriesSkipTracksWithoutIds() {
        val raw = json("""[{"id":"a b","title":"A","cover":"https://img.example/x.jpg"},{"title":"无 ID"},"junk",{"id":7,"name":"B"}]""").jsonArray
        val tracks = raw.providerTracks("kugou", "provider-search-0")
        assertEquals(listOf("A", "B"), tracks.map { it.title })
        assertEquals(listOf("provider-search-0-0", "provider-search-0-3"), tracks.map { it.queueEntryId })
        assertEquals("provider://kugou/a%20b", tracks.first().id)
    }

    @Test fun libraryListsTheLikedPlaylistFirstOnce() {
        val value = json("""{"likedPlaylist":{"id":"liked","name":"我喜欢","trackCount":12},
            "playlists":[{"id":"p1","name":"","trackCount":3},{"id":"liked","name":"重复"},{"name":"无 ID"}]}""")
        assertEquals(listOf(ProviderPlaylist("liked", "我喜欢", 12), ProviderPlaylist("p1", "未命名歌单", 3)), providerLibrary(value))
        assertTrue(providerLibrary(JsonArray(emptyList())).isEmpty())
    }

    @Test fun accountsAreNamedAsDesktopNamesThem() {
        assertNull(providerAccount(json("""{"loggedIn":false,"profile":{"nickname":"x"}}""")))
        assertEquals("小明", providerAccount(json("""{"loggedIn":true,"profile":{"userId":"42","nickname":"小明"}}"""))?.nickname)
        assertEquals("42", providerAccount(json("""{"loggedIn":true,"profile":{"userId":42}}"""))?.nickname)
        assertEquals("已登录", providerAccount(json("""{"loggedIn":true,"profile":null}"""))?.nickname)
    }

    @Test fun settingsFormsFollowDesktopsRules() {
        val form = providerForm(json("""{"kind":"settings-form","submitCommand":"qqmusic.save","notice":"n","fields":[
            {"key":"consent","label":"免责声明","type":"select","required":true,"value":"","options":[{"label":"同意","value":"accepted"},{"label":"","value":"x"}]},
            {"key":"cookie","label":"Cookie","type":"password","value":"secret"},
            {"key":"hq","label":"高音质","type":"toggle","value":true},
            {"key":"empty","label":"空选项","type":"select","options":[]},
            {"key":"bad key","label":"坏键","type":"text"},
            {"key":"range","label":"范围","type":"range"},
            {"key":"state","label":"状态","type":"text","readonly":true,"value":"已登录","group":" 账号 "}]}"""))!!
        assertEquals("qqmusic.save", form.submitCommand)
        assertEquals(listOf("consent", "cookie", "hq", "state"), form.fields.map { it.key })
        assertEquals(listOf("同意" to "accepted"), form.fields[0].options)
        assertEquals("", form.fields[1].value)
        assertEquals("true", form.fields[2].value)
        assertTrue(form.fields[3].readonly); assertEquals("账号", form.fields[3].group)
        assertEquals("免责声明", form.missing(mapOf("consent" to " "))?.label)
        assertNull(form.missing(mapOf("consent" to "accepted")))
        assertNull(providerForm(json("""{"kind":"other","submitCommand":"x","fields":[{"key":"a","label":"A","type":"text"}]}""")))
        assertNull(providerForm(json("""{"kind":"settings-form","submitCommand":" ","fields":[{"key":"a","label":"A","type":"text"}]}""")))
        assertNull(providerForm(json("""{"kind":"settings-form","submitCommand":"x","fields":[]}""")))
    }

    @Test fun commandMessagesPreferTextThenMessage() {
        assertEquals("已保存", commandMessage(JsonPrimitive("已保存"), "x"))
        assertEquals("ok", commandMessage(json("""{"message":"ok"}"""), "x"))
        assertEquals("设置已保存", commandMessage(JsonObject(emptyMap()), "设置已保存"))
        assertEquals("设置已保存", commandMessage(JsonPrimitive(1), "设置已保存"))
        assertEquals("设置已保存", commandMessage(null, "设置已保存"))
    }
}
