package com.twilightecho.android.coreclient

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UserPreferencesTest {
    @Test fun homePlaylistPinsPersistInPinOrderAndStaySeparateForEachProviderAndAccount() {
        val original = UserPreferences(ncmQuality = NcmPlaybackQuality.LOSSLESS)
        val pinned = original.toggleHomePlaylistPin("ncm", "alice", "11")
            .toggleHomePlaylistPin("ncm", "alice", "22")
            .toggleHomePlaylistPin("qq", "alice", "11")
            .toggleHomePlaylistPin("ncm", "bob", "33")
        val restored = Json.decodeFromString<UserPreferences>(Json.encodeToString(pinned))
        assertEquals(listOf("22", "11"), restored.homePlaylistPinsFor("ncm", "alice"))
        assertEquals(listOf("11"), restored.homePlaylistPinsFor("qq", "alice"))
        assertEquals(listOf("33"), restored.homePlaylistPinsFor("ncm", "bob"))
        assertTrue(restored.homePlaylistPinsFor("ncm", null).isEmpty())
        assertTrue(Json.decodeFromString<UserPreferences>("{}").homePlaylistPins.isEmpty())
        assertEquals(original.ncmQuality, restored.ncmQuality)
    }

    @Test fun unpinningRemovesOnlyTheSelectedPlaylistAndRepinningMovesItFirst() {
        val pinned = UserPreferences().toggleHomePlaylistPin("provider", null, "11")
            .toggleHomePlaylistPin("provider", null, "22")
        val unpinned = pinned.toggleHomePlaylistPin("provider", null, "11")
        assertEquals(listOf("22"), unpinned.homePlaylistPinsFor("provider", null))
        assertEquals(listOf("11", "22"), unpinned.toggleHomePlaylistPin("provider", null, "11")
            .homePlaylistPinsFor("provider", null))
        assertTrue(unpinned.toggleHomePlaylistPin("provider", null, "22").homePlaylistPins.isEmpty())
    }

    @Test fun mobilePolicyAndExternalAddressPersistWithoutChangingOldPlaybackDefault() {
        val defaults = Json.decodeFromString<UserPreferences>("{}")
        assertTrue(defaults.mobileNetwork.allowMeteredStreaming)
        assertFalse(defaults.mobileNetwork.allowMeteredDownloads)
        val preferences = defaults.copy(mobileNetwork = MobileNetworkPreferences(false, true, 10L * 1024 * 1024 * 1024),
            desktopExternalUrls = mapOf("server" to "https://music.example.com:18492"))
        assertEquals(preferences, Json.decodeFromString<UserPreferences>(Json.encodeToString(preferences)))
        assertEquals(1024L * 1024 * 1024, preferences.copy(mobileNetwork = MobileNetworkPreferences(offlineQuotaBytes = -1)).normalized().mobileNetwork.offlineQuotaBytes)
    }
    @Test fun collectionSortsRoundTripWithoutChangingExistingCustomization() {
        val preferences = UserPreferences(collectionSorts = mapOf(
            "ncm:42:group:LIKED" to CollectionSort(descending = true),
            "library:playlist:1" to CollectionSort(CollectionSortField.TITLE)))
        assertEquals(preferences, Json.decodeFromString<UserPreferences>(Json.encodeToString(preferences)))
        assertTrue(Json.decodeFromString<UserPreferences>("{}").collectionSorts.isEmpty())
        assertEquals(AppearancePreferences(), preferences.appearance)
    }
    @Test fun oldAndPartialPreferencesRetainExistingDefaults() {
        assertEquals(UserPreferences(), Json.decodeFromString<UserPreferences>("{}"))
        val partial = Json.decodeFromString<UserPreferences>("""{"appearance":{"accentSource":"CUSTOM","accentArgb":-14647299}}""")
        assertEquals(1f, partial.appearance.densityScale)
        assertEquals(0.86f, partial.appearance.contentOpacity)
        assertFalse(partial.appearance.immersiveMode)
        assertEquals(LyricsPreferences(), partial.lyrics)
        assertEquals(NcmPlaybackQuality.AUTO, partial.ncmQuality)
    }

    @Test fun immersiveModeRoundTripsAndKeepsOtherAppearanceChoices() {
        val appearance = AppearancePreferences(accentSource = AccentSource.CUSTOM,
            densityScale = 1.2f, contentOpacity = 0.5f, immersiveMode = true)
        val preferences = UserPreferences(appearance = appearance)
        val decoded = Json.decodeFromString<UserPreferences>(Json.encodeToString(preferences)).normalized()
        assertEquals(preferences, decoded)
        assertTrue(decoded.appearance.immersiveMode)
        assertEquals(appearance.copy(immersiveMode = false), decoded.appearance.copy(immersiveMode = false))
        assertFalse(AppearancePreferences().immersiveMode)
    }

    @Test fun allChoicesRoundTripIndependently() {
        val preferences = UserPreferences(AppearancePreferences(AccentSource.CUSTOM, 0xFF31C48D.toInt(),
            BackgroundStyle.IMAGE, 0xFF123344.toInt(), "/backgrounds/cover.png", 1.25f, 0.35f),
            LyricsPreferences(42f, 500, 0f, 38f, false, 22f), NcmPlaybackQuality.LOSSLESS)
        assertEquals(preferences, Json.decodeFromString<UserPreferences>(Json.encodeToString(preferences)))
        val onlyLyricsChanged = preferences.copy(lyrics = LyricsPreferences())
        assertEquals(preferences.appearance, onlyLyricsChanged.appearance)
        assertEquals(preferences.ncmQuality, onlyLyricsChanged.ncmQuality)
    }

    @Test fun boundsKeepSavedSizesUsableAndColorsOpaque() {
        val settings = UserPreferences(AppearancePreferences(accentArgb = 0x0031C48D, densityScale = 20f, contentOpacity = -1f),
            LyricsPreferences(Float.NaN, 2000, -2f, 90f, true, Float.POSITIVE_INFINITY)).normalized()
        assertEquals(0xFF31C48D.toInt(), settings.appearance.accentArgb)
        assertEquals(1.25f, settings.appearance.densityScale)
        assertEquals(0.2f, settings.appearance.contentOpacity)
        assertEquals(30f, settings.lyrics.fontSizeSp)
        assertEquals(900, settings.lyrics.fontWeight)
        assertEquals(0f, settings.lyrics.blurDp)
        assertEquals(44f, settings.lyrics.lineSpacingDp)
        assertEquals(17f, settings.lyrics.translationSizeSp)
    }

    @Test fun excludedLocalTrackIdsDefaultEmptyAndRoundTrip() {
        assertTrue(Json.decodeFromString<UserPreferences>("{}").excludedLocalTrackIds.isEmpty())
        val prefs = UserPreferences(excludedLocalTrackIds = setOf("media:101", "folder:202:cue:1"))
        val decoded = Json.decodeFromString<UserPreferences>(Json.encodeToString(prefs))
        assertEquals(setOf("media:101", "folder:202:cue:1"), decoded.excludedLocalTrackIds)
        val cleared = decoded.copy(excludedLocalTrackIds = emptySet())
        assertTrue(cleared.excludedLocalTrackIds.isEmpty())
    }
}
