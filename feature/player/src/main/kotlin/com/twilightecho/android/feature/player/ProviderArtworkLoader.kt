package com.twilightecho.android.feature.player

import androidx.compose.runtime.*
import com.twilightecho.android.plugins.providerArtworkData
import com.twilightecho.android.ui.components.TrackArtworkVisual
import com.twilightecho.android.ui.components.rememberByteArtwork

/** Artwork transport is separate from presentation; the default retains the provider's network grants. */
val LocalProviderArtworkData = staticCompositionLocalOf<suspend (String, Int) -> ByteArray?> { ::providerArtworkData }

@Composable
internal fun rememberProviderArtwork(url: String, size: Int, extractColors: Boolean): TrackArtworkVisual {
    val load = LocalProviderArtworkData.current
    return rememberByteArtwork("provider:$url", size, extractColors) { load(url, size) }
}
