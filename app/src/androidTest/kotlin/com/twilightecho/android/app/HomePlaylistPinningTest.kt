package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.UserPreferences
import com.twilightecho.android.coreclient.homePlaylistPinsFor
import com.twilightecho.android.coreclient.toggleHomePlaylistPin
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.playbackhost.UserPreferencesStore
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomePlaylistPinningTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ncm = "com.twilightecho.provider.ncm"
    private val plugin = "com.twilightecho.provider.fixture"
    private val account = "home-pin-fixture"
    private lateinit var store: UserPreferencesStore
    private lateinit var savedPreferences: UserPreferences
    private lateinit var ui: MutableState<PlayerUiState>
    private val opened = mutableListOf<String>()

    @Before fun preparePreferences() {
        store = UserPreferencesStore(InstrumentationRegistry.getInstrumentation().targetContext)
        savedPreferences = store.state.value
        store.update { UserPreferences() }
    }

    @After fun restorePreferences() { store.update { savedPreferences } }

    private fun playlists(prefix: String, owned: Boolean = false) = (1..6).map {
        StreamingCollection("$prefix-$it", "playlist", "晚风与回声 $it", "精选收藏", count = 20 + it,
            cover = "fixture://home-pinning/${if (it % 2 == 0) "03" else "01"}.jpg", owned = owned)
    }

    private fun start(provider: String = ncm) {
        val own = playlists("own", owned = true)
        val recommended = playlists("rec")
        ui = mutableStateOf(PlayerUiState(isLoadingLibrary = false, activeMainTab = MainDestinationTab.HOME,
            preferences = store.state.value, activeHomePluginId = provider,
            homeProviders = listOf(HomeProviderOption(ncm, "ncm", "网易云音乐"), HomeProviderOption(plugin, "fixture", "测试音源")),
            streaming = StreamingUiState(access = StreamingAccess.READY, accountId = account, nickname = "夜航",
                playlists = own + playlists("collected"), recommendedPlaylists = recommended, libraryLoaded = true),
            providerHome = ProviderHomeUiState(pluginId = plugin, providerId = "fixture", providerName = "测试音源",
                access = StreamingAccess.READY, profile = NcmProfile(account, "夜航"), supportsLibrary = true,
                playlists = own, recommendedPlaylists = recommended, libraryLoaded = true)))
        val toggle: (StreamingCollection) -> Unit = { collection ->
            store.update { it.toggleHomePlaylistPin(provider, account, collection.id) }
            ui.value = ui.value.copy(preferences = store.state.value)
        }
        val actions = PlayerActions(
            streaming = StreamingActions(collection = { opened += it.id }, togglePlaylistPin = toggle),
            providerHome = ProviderHomeActions(openCollection = { opened += it.id }, togglePlaylistPin = toggle))
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val covers = listOf("01.jpg", "03.jpg").associateWith { name -> assets.open("music/$name").use { it.readBytes() } }
        rule.setContent {
            CompositionLocalProvider(LocalProviderArtworkData provides { url, _ -> covers[url.substringAfterLast('/')] }) {
                TwilightEchoTheme(darkTheme = true) { PlayerAppContent(ui.value, actions) }
            }
        }
    }

    private fun showShelf(tag: String) {
        rule.onNodeWithTag("home").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun pin(shelf: String, id: String, prefix: String = "home-playlist") {
        showShelf(shelf)
        rule.onNodeWithTag(shelf).performScrollToNode(hasTestTag("$prefix-$id"))
        rule.onNodeWithTag("$prefix-$id").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag("$prefix-$id").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已置顶"))
        assertEquals("取消置顶", rule.onNodeWithTag("$prefix-$id").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        assertTrue("Long press must not open the playlist", opened.isEmpty())
    }

    @Test fun longPressRevealsPinKeepsNormalTapAndRestoresAfterReload() {
        start()
        pin("home-own", "own-6")
        assertTrue(rule.onNodeWithTag("home-playlist-own-6").fetchSemanticsNode().boundsInRoot.left <
            rule.onNodeWithTag("home-playlist-own-1").fetchSemanticsNode().boundsInRoot.left)
        screenshot("home-playlist-pinned.png")
        rule.runOnIdle {
            val reloaded = UserPreferencesStore(InstrumentationRegistry.getInstrumentation().targetContext)
            assertEquals(listOf("own-6"), reloaded.state.value.homePlaylistPinsFor(ncm, account))
            ui.value = ui.value.copy(preferences = reloaded.state.value,
                streaming = ui.value.streaming.copy(playlists = ui.value.streaming.playlists.map {
                    if (it.id == "own-6") it.copy(title = "刷新后的歌单") else it
                }))
        }
        rule.onNodeWithTag("home-playlist-own-6").assertTextContains("刷新后的歌单").performClick()
        assertEquals(listOf("own-6"), opened)
        rule.onNodeWithTag("home-playlist-own-6").performTouchInput { longClick() }
        rule.runOnIdle { assertTrue(store.state.value.homePlaylistPinsFor(ncm, account).isEmpty()) }
        rule.onNodeWithTag("home-own").performScrollToIndex(0)
        rule.onNodeWithTag("home-playlist-own-1").assertIsDisplayed()
        rule.onNodeWithTag("home-own").performScrollToNode(hasTestTag("home-playlist-own-6"))
        rule.onNodeWithTag("home-playlist-own-6")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "未置顶"))
    }

    @Test fun collectedAndRecommendedShelvesSupportPinning() {
        start()
        pin("home-collected", "collected-6")
        pin("home-recommended", "rec-6")
        rule.runOnIdle {
            assertEquals(listOf("rec-6", "collected-6"), store.state.value.homePlaylistPinsFor(ncm, account))
        }
    }

    @Test fun pluginShelvesUseTheirOwnPinsAndKeepTheCreatorSubtitle() {
        store.update { it.toggleHomePlaylistPin(ncm, account, "own-6") }
        start(plugin)
        showShelf("provider-home-playlists")
        rule.onNodeWithTag("provider-playlist-own-1").assertIsDisplayed()
        pin("provider-home-playlists", "own-6", "provider-playlist")
        rule.onNodeWithTag("provider-playlist-own-6").assertTextContains("精选收藏")
        screenshot("provider-playlist-pinned.png")
        pin("provider-home-recommended", "rec-6", "provider-playlist")
        rule.runOnIdle {
            assertEquals(listOf("own-6"), store.state.value.homePlaylistPinsFor(ncm, account))
            assertEquals(listOf("rec-6", "own-6"), store.state.value.homePlaylistPinsFor(plugin, account))
        }
    }

    private fun screenshot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
