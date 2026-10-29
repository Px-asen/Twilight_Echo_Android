package com.twilightecho.android.mediasession

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CueSource
import com.twilightecho.android.coreclient.DesktopMediaReference
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import com.twilightecho.android.plugins.providerArtworkData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Resolves only the active cover off the main thread; Media3 publishes it to notifications and SystemUI. */
internal class MediaSessionArtwork(private val context: Context, private val host: AndroidPlaybackHost) {
    suspend fun load(track: CoreTrackView): ByteArray? = withContext(Dispatchers.IO) {
        val source = track.source.orEmpty()
        val desktop = DesktopMediaReference.parse(source)
        val bytes = when {
            // NCM and v2 provider covers: CDN URLs fetched under the provider's own grants.
            source.startsWith("provider://") -> track.cover?.let { providerArtworkData(it, size = 512) }
            desktop != null -> {
                val preview = host.desktopArtwork(desktop.serverId, listOf(track.id)).getOrNull()
                    ?.items?.firstOrNull { it.id == track.id }?.cover
                decodePreview(preview)
            }
            track.id.startsWith("remote-track:") || host.systemMediaTransport.isRemote -> {
                val serverId = host.playbackDesktopServerId() ?: return@withContext null
                val preview = host.desktopArtwork(serverId, listOf(track.id)).getOrNull()
                    ?.items?.firstOrNull { it.id == track.id }?.cover
                decodePreview(preview)
            }
            else -> localArtwork(CueSource.audio(source), track.albumId, track.cover)
        } ?: return@withContext null
        // Bound both bitmap memory in SystemUI and the metadata sent over Binder.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
        try {
            ByteArrayOutputStream().use { output ->
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) output.toByteArray() else null
            }
        } finally { bitmap.recycle() }
    }

    /** [cover] is an image beside the audio file, for files without a picture of their own. */
    private fun localArtwork(source: String, albumId: String?, cover: String?): ByteArray? {
        if (source.isNotBlank() && (Uri.parse(source).scheme in setOf(null, "content", "file"))) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    if (source.startsWith("content://") || source.startsWith("file://")) retriever.setDataSource(context, Uri.parse(source))
                    else retriever.setDataSource(source)
                    retriever.embeddedPicture
                } finally { retriever.release() }
            }.getOrNull()?.let { return it }
        }
        if (cover != null && cover.startsWith("content://")) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(cover))?.use { it.readBytes() }
            }.getOrNull()?.takeIf { it.size <= 8 * 1024 * 1024 }?.let { return it }
        }
        return albumId?.toLongOrNull()?.takeIf { it > 0 }?.let { id ->
            runCatching {
                val uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), id)
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
        }
    }

    private fun decodePreview(value: String?): ByteArray? {
        if (value == null || value.length > 250_000 ||
            !Regex("^data:image/(?:png|jpe?g|webp);base64,", RegexOption.IGNORE_CASE).containsMatchIn(value)) return null
        return runCatching { Base64.decode(value.substringAfter(','), Base64.DEFAULT) }.getOrNull()
    }
}
