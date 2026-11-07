package com.twilightecho.android.feature.player

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Full-height content in immersive mode; temporarily revealed system bars overlay the page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.appStatusBarPadding(immersiveMode: Boolean): Modifier {
    // Normal layout restores immediately, without waiting for the system bar's visibility animation.
    val statusBars = WindowInsets.statusBarsIgnoringVisibility
    return windowInsetsPadding(if (immersiveMode) WindowInsets(0, 0, 0, 0) else statusBars)
}
