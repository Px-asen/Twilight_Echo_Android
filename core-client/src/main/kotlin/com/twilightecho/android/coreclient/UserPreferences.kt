package com.twilightecho.android.coreclient

import kotlinx.serialization.Serializable

@Serializable
enum class AccentSource { COVER, CUSTOM }

@Serializable
enum class BackgroundStyle { COVER, IMAGE, SOLID }

@Serializable
enum class CollectionSortField { ORIGINAL, TITLE, ARTIST, ALBUM }

@Serializable
data class CollectionSort(val field: CollectionSortField = CollectionSortField.ORIGINAL,
    val descending: Boolean = false)

/** Values supported by the shared NCM provider, rather than Android-only quality names. */
@Serializable
enum class NcmPlaybackQuality(val wireValue: String, val label: String) {
    AUTO("auto", "自动选择"), HI_RES("hires", "高解析无损"), LOSSLESS("lossless", "无损"),
    HIGH("exhigh", "极高"), STANDARD("standard", "标准")
}

@Serializable
data class AppearancePreferences(
    val accentSource: AccentSource = AccentSource.COVER,
    val accentArgb: Int = 0xFFFA2D48.toInt(),
    val backgroundStyle: BackgroundStyle = BackgroundStyle.COVER,
    val backgroundArgb: Int = 0xFF1C1C1E.toInt(),
    /** App-owned, sampled image. It remains available after the document picker grant expires. */
    val backgroundImagePath: String? = null,
    val densityScale: Float = 1f,
    /** Container fills only. Text, icons and album artwork retain their original opacity. */
    val contentOpacity: Float = 0.86f,
    /** Hide the phone's status bar while keeping system navigation available. */
    val immersiveMode: Boolean = false
) {
    fun normalized() = copy(
        accentArgb = accentArgb or (0xFF shl 24), backgroundArgb = backgroundArgb or (0xFF shl 24),
        densityScale = densityScale.finiteOr(1f).coerceIn(0.8f, 1.25f),
        contentOpacity = contentOpacity.finiteOr(0.86f).coerceIn(0.2f, 1f))
}

@Serializable
data class LyricsPreferences(
    val fontSizeSp: Float = 30f,
    val fontWeight: Int = 800,
    val blurDp: Float = 4f,
    val lineSpacingDp: Float = 26f,
    val showTranslation: Boolean = true,
    val translationSizeSp: Float = 17f
) {
    fun normalized() = copy(fontSizeSp = fontSizeSp.finiteOr(30f).coerceIn(22f, 46f),
        fontWeight = fontWeight.coerceIn(400, 900), blurDp = blurDp.finiteOr(4f).coerceIn(0f, 8f),
        lineSpacingDp = lineSpacingDp.finiteOr(26f).coerceIn(12f, 44f),
        translationSizeSp = translationSizeSp.finiteOr(17f).coerceIn(14f, 26f))
}

@Serializable
data class MobileNetworkPreferences(
    val allowMeteredStreaming: Boolean = true,
    val allowMeteredDownloads: Boolean = false,
    val offlineQuotaBytes: Long = 5L * 1024 * 1024 * 1024
) {
    fun normalized() = copy(offlineQuotaBytes = offlineQuotaBytes.coerceIn(1024L * 1024 * 1024, 50L * 1024 * 1024 * 1024))
}

@Serializable
data class UserPreferences(
    val appearance: AppearancePreferences = AppearancePreferences(),
    val lyrics: LyricsPreferences = LyricsPreferences(),
    val ncmQuality: NcmPlaybackQuality = NcmPlaybackQuality.AUTO,
    /** Selected streaming provider plugin ID on the mobile Home tab. */
    val homeProviderId: String = "com.twilightecho.provider.ncm",
    /** View order only; never rewrites playlist membership or the provider's stored order. */
    val collectionSorts: Map<String, CollectionSort> = emptyMap(),
    /** Phone outputs only; a connected computer keeps its own output settings. */
    val outputPresets: OutputPresetPreferences = OutputPresetPreferences(),
    /** Phone library track IDs explicitly deleted by the user so rescans keep them hidden until restored or reset. */
    val excludedLocalTrackIds: Set<String> = emptySet(),
    /** Source evidence for exclusions; old ID-only records retain their original provider-wide scope. */
    val excludedLocalTrackSources: Map<String, String> = emptyMap(),
    val mobileNetwork: MobileNetworkPreferences = MobileNetworkPreferences(),
    val desktopExternalUrls: Map<String, String> = emptyMap(),
    /** Home playlist IDs, most recently pinned first, scoped to provider plugin and account. */
    val homePlaylistPins: Map<String, List<String>> = emptyMap()
) {
    fun normalized() = copy(appearance = appearance.normalized(), lyrics = lyrics.normalized(),
        outputPresets = outputPresets.normalized(), mobileNetwork = mobileNetwork.normalized())
}

fun UserPreferences.homePlaylistPinsFor(pluginId: String, accountId: String?): List<String> =
    homePlaylistPins["provider:$pluginId:${accountId.orEmpty()}"].orEmpty()

fun UserPreferences.toggleHomePlaylistPin(pluginId: String, accountId: String?, playlistId: String): UserPreferences {
    val key = "provider:$pluginId:${accountId.orEmpty()}"
    val pins = homePlaylistPinsFor(pluginId, accountId)
    val updated = if (playlistId in pins) pins - playlistId else listOf(playlistId) + pins
    return copy(homePlaylistPins = if (updated.isEmpty()) homePlaylistPins - key else homePlaylistPins + (key to updated))
}

private fun Float.finiteOr(default: Float) = if (isFinite()) this else default
