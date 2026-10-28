package com.twilightecho.android.coreclient

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UserPreferencesTest {
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
        assertEquals(LyricsPreferences(), partial.lyrics)
        assertEquals(NcmPlaybackQuality.AUTO, partial.ncmQuality)
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
}
