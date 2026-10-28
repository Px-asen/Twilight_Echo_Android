package com.twilightecho.android.feature.player

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.plugins.providerArtworkPreview
import com.twilightecho.android.ui.components.*

@Composable
internal fun NcmAvatar(nickname: String, url: String?, modifier: Modifier = Modifier) {
    // A changed URL starts a fresh image state, including when the signed-in account changes.
    key(url) {
        val preview by produceState<String?>(null, url) { value = url?.let { providerArtworkPreview(it, size = 256) } }
        val visual = rememberTrackArtwork("ncm-avatar-$url", nickname, null, null,
            previewDataUrl = preview, previewOnly = true)
        Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = "${nickname.ifBlank { "网易云用户" }}的头像" }, contentAlignment = Alignment.Center) {
            if (visual.isRealEmbeddedArt) ArtworkImage(visual, Modifier.fillMaxSize().testTag("ncmAvatarLoaded"), ContentScale.Crop)
            else Text(nickname.trim().take(1).ifBlank { "我" }, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun NcmProfileCard(profile: NcmProfile, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("ncmProfile"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            NcmAvatar(profile.nickname, profile.avatarUrl, Modifier.size(80.dp))
            Text(profile.nickname, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (profile.signature.isNotBlank()) Text(profile.signature, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("ncmSignature"))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxWidth()) {
            Text("${profile.follows} 关注", style = MaterialTheme.typography.bodyLarge)
            Text("${profile.followeds} 粉丝", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
