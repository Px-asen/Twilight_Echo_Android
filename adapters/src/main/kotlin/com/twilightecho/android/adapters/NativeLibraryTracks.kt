package com.twilightecho.android.adapters

import com.twilightecho.android.coreclient.AudioFileMetadata
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.SacdTrackSource
import com.twilightecho.android.coreclient.SafTrackIds

internal fun nativeLibraryTrack(
    file: ListedFile, folder: ListedFolder, source: String, metadata: AudioFileMetadata, cover: String?, header: AudioHeader? = null
): CoreTrackView {
    val sacd = SacdTrackSource.decode(source)
    val wavPack = file.name.substringAfterLast('.').equals("wv", ignoreCase = true)
    val dsd = metadata.isDsd || header?.isDsd == true || sacd != null
    val duration = header?.durationSeconds ?: metadata.duration
    val rate = header?.sampleRate ?: metadata.sampleRate
    val artist = metadata.artist.ifBlank { "Unknown Artist" }
    val id = if (sacd == null) MusicFolders.folderTrackId(file.documentId)
        else "local:sacd:${MusicFolders.folderTrackId(file.documentId).removePrefix("local:saf:")}:${sacd.area}:${sacd.track}"
    return CoreTrackView(
        id = SafTrackIds.qualify(id, folder.authority),
        title = metadata.title.ifBlank { file.name.substringBeforeLast('.') },
        artist = artist,
        artists = listOf(artist),
        album = metadata.album.ifBlank { folder.path.substringAfterLast('/') },
        albumArtist = metadata.albumArtist.ifBlank { null },
        source = source,
        cover = cover,
        unavailableReason = if (metadata.playable) null else metadata.comment.ifBlank { metadata.reasonCode.ifBlank { "音源无法解码" } },
        duration = duration,
        size = file.size.takeIf { it >= 0 },
        format = when { sacd != null -> "SACD ISO"; wavPack && dsd -> "WavPack DSD"; dsd -> "DSD"; else -> audioFormat(null, file.name) },
        sampleRate = rate.takeIf { it > 0 },
        bitDepth = if (dsd) 1 else header?.bitDepth ?: metadata.bitDepth.takeIf { it > 0 },
        bitrate = metadata.bitrate.takeIf { it > 0 }?.let { it / 1000.0 }
            ?: if (wavPack) file.size.takeIf { it >= 0 && duration > 0 }?.let { it * 8 / duration / 1000 }
            else if (metadata.codec != "dst" && rate > 0 && metadata.channelCount > 0)
                rate.toDouble() * metadata.channelCount / 1000.0 else null,
        trackNumber = metadata.trackNumber.substringBefore('/').toIntOrNull() ?: sacd?.track,
        discNumber = metadata.discNumber.substringBefore('/').toIntOrNull(),
        releaseDate = metadata.year.ifBlank { null },
        genre = metadata.genre.ifBlank { null },
        addedAt = file.lastModified
    )
}
