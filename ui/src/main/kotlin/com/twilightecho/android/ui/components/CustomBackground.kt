package com.twilightecho.android.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BackgroundVisuals(val useCover: Boolean = true, val imagePath: String? = null)
val LocalBackgroundVisuals = staticCompositionLocalOf { BackgroundVisuals() }
private object BackgroundImageCache {
    private var entry: Pair<String, ImageBitmap>? = null
    @Synchronized fun get(path: String?) = entry?.takeIf { it.first == path }?.second
    @Synchronized fun put(path: String, image: ImageBitmap) { entry = path to image }
}

@Composable
fun rememberBackgroundImage(path: String?): ImageBitmap? {
    val image by produceState(BackgroundImageCache.get(path), path) {
        value = if (path == null) null else BackgroundImageCache.get(path) ?: withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(path)?.asImageBitmap()?.also { BackgroundImageCache.put(path, it) }
        }
    }
    return image
}

/** Shared by browsing, plugin pages and the full-screen player. User images keep their detail. */
@Composable
fun CustomBackgroundBackdrop(modifier: Modifier = Modifier) {
    val visuals = LocalBackgroundVisuals.current
    val image = rememberBackgroundImage(visuals.imagePath)
    Box(modifier.background(MaterialTheme.colorScheme.background)
        .testTag(if (visuals.imagePath != null) "customImageBackground" else "solidBackground")) {
        image?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.64f)))
        }
    }
}
