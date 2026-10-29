package com.twilightecho.android.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twilightecho.android.ui.theme.LocalTwilightAccents

/** The cover of what is playing; pages blur it into their background. Null keeps the plain grey. */
val LocalAmbientArtwork = compositionLocalOf<TrackArtworkVisual?> { null }

/**
 * Default canvas: Apple's dark grey. Once a song with real artwork plays, its cover is blurred across
 * the page under a grey veil, so the colour is felt rather than seen and text stays neutral.
 * Pass [artwork] at the app root; nested pages inherit it through [LocalAmbientArtwork].
 * Custom images and solid colors replace that canvas throughout the app.
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier, artwork: TrackArtworkVisual? = LocalAmbientArtwork.current,
    showBackdrop: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val base = MaterialTheme.colorScheme.background
    val cover = artwork?.takeIf { it.isRealEmbeddedArt }
    Box(modifier.background(base)) {
        if (showBackdrop && !LocalBackgroundVisuals.current.useCover) CustomBackgroundBackdrop(Modifier.matchParentSize())
        else if (showBackdrop) Crossfade(cover?.let { rememberBlurredArtwork(it) }, Modifier.matchParentSize(), tween(700), label = "ambientCover") { image ->
            if (image != null) Box(Modifier.fillMaxSize()) {
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(base.copy(alpha = 0.80f)))
            }
        }
        CompositionLocalProvider(LocalAmbientArtwork provides artwork) { content() }
    }
}

/** A grouped container whose fill follows the user's content-opacity setting. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp),
    onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        content = content)
}

/** Quiet grey group label, in sentence case like an iOS grouped-list header. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, style = MaterialTheme.typography.labelMedium,
        color = color, maxLines = 1)
}

@Composable
fun QuietIconButton(icon: ImageVector, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled) {
        Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = 0.38f), modifier = Modifier.size(24.dp))
    }
}

/** Circular translucent icon button for use over artwork and in page headers. */
@Composable
fun GlassIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    size: Dp = 40.dp) {
    Box(modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Section title, with an optional quiet text action on the right (e.g. "重新搜索"). */
@Composable
fun SectionHeading(title: String, actionLabel: String? = null, onAction: () -> Unit = {},
    modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = SectionTitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (actionLabel != null) Text(actionLabel, Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 8.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A section title that opens its full list, written the Apple way: "专辑 ›". The whole row is the target. */
@Composable
fun SectionLink(title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = SectionTitleStyle, maxLines = 1)
        Icon(TwilightIcons.ArrowRight, null, Modifier.padding(start = 2.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

private val SectionTitleStyle: TextStyle @Composable get() =
    MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)

/** Page title: bold but modest, so the page starts with one calm line instead of a banner. */
@Composable
fun PageHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold))
            if (subtitle != null) SupportingText(subtitle, Modifier.padding(top = 2.dp), maxLines = 1)
        }
        trailing()
    }
}

@Composable
fun EmptyState(title: String, message: String, icon: ImageVector = TwilightIcons.MusicNote,
    modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null) PillButton(actionLabel, onAction, Modifier.padding(top = 8.dp))
    }
}

/** Primary call to action: solid accent pill. */
@Composable
fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    enabled: Boolean = true, tonal: Boolean = false) {
    val container = if (tonal) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f) else MaterialTheme.colorScheme.primary
    val content = if (tonal) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
    Row(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50))
        .background(if (enabled) container else container.copy(alpha = 0.3f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) Icon(icon, null, Modifier.padding(end = 8.dp).size(18.dp),
            tint = if (enabled) content else content.copy(alpha = 0.5f))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) content else content.copy(alpha = 0.5f),
            maxLines = 1)
    }
}

/** Scrollable pill tabs; the selected pill is a light grey fill, the rest plain grey text. */
@Composable
fun <T> PillTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit,
    modifier: Modifier = Modifier, tag: (T) -> String = { "" }) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val active = option == selected
            val bg by animateColorAsState(if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
                else Color.Transparent, label = "pillBg")
            val fg by animateColorAsState(if (active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant, label = "pillFg")
            Text(label(option), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(bg)
                    .clickable(role = Role.Tab) { onSelect(option) }
                    .then(if (tag(option).isNotEmpty()) Modifier.testTag(tag(option)) else Modifier)
                    .heightIn(min = 40.dp).padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

/** Rounded square holding a tinted icon, used at the start of settings rows and cards. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer, content: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = content)
    }
}

/** A grouped list of rows inside one grey card. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) Text(title, Modifier.padding(start = 4.dp, bottom = 10.dp),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), content = content)
    }
}

@Composable
fun SettingsItem(icon: ImageVector?, title: String, subtitle: String? = null, modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) IconTile(icon, Modifier.padding(end = 14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) SupportingText(subtitle, Modifier.padding(top = 2.dp), maxLines = 2)
        }
        if (trailing != null) trailing()
        else if (onClick != null) Icon(TwilightIcons.ArrowRight, null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small rounded label, e.g. "FLAC 24/96" or a status. */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    Text(text, modifier.clip(RoundedCornerShape(6.dp)).background(if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/** Square or round artwork with title and subtitle beneath, for carousels and grids. */
@Composable
fun ArtworkTile(artwork: TrackArtworkVisual, title: String, subtitle: String?, onClick: () -> Unit,
    modifier: Modifier = Modifier, round: Boolean = false, centered: Boolean = false) {
    val shape = if (round) CircleShape else RoundedCornerShape(16.dp)
    // Bottom padding keeps the subtitle clear of the rounded corner of the click/ripple clip.
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick).padding(bottom = 6.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        ArtworkImage(artwork, Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape), ContentScale.Crop)
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp))
        if (subtitle != null) SupportingText(subtitle, Modifier.padding(top = 1.dp), maxLines = 1)
    }
}

@Composable
fun ArtworkImage(artwork: TrackArtworkVisual, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit, description: String? = null) {
    androidx.compose.foundation.Image(bitmap = artwork.imageBitmap, contentDescription = description,
        contentScale = contentScale, modifier = modifier)
}

@Composable
fun SupportingText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Translucent outline used around inputs and secondary cards. */
val HairlineBorder: BorderStroke @Composable get() = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))

fun formatDurationSeconds(seconds: Double): String {
    val total = if (seconds.isFinite()) seconds.coerceAtLeast(0.0).toLong() else 0L
    return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60)
}
