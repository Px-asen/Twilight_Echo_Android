package com.twilightecho.android.adapters

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import com.twilightecho.android.coreclient.CoreTrackView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A MediaStore song and where its file lives ([StorageLocation]); null when MediaStore does not say. */
data class MediaStoreTrack(val track: CoreTrackView, val location: String?)

/**
 * Queries Android `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` on `Dispatchers.IO` and maps
 * device audio files (`IS_MUSIC != 0` and `DURATION >= 5000`) into `CoreTrackView` DTOs.
 */
class AndroidMediaStoreScannerAdapter(
    private val context: Context
) {

    /** Whether Android lets the app read the phone's music; picked music folders carry their own access. */
    fun canReadDeviceMusic(): Boolean = context.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    suspend fun scanDeviceMusic(): List<MediaStoreTrack> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MediaStoreTrack>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.BITRATE)
            }
            // Where the file lives, so a music folder's files find their songs here.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.VOLUME_NAME)
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf("5000")
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val displayNameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val bitrateCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                } else {
                    -1
                }
                val volumeCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME) else -1
                val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                @Suppress("DEPRECATION")
                val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.Audio.Media.DATA) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

                    val rawTitle = cursor.getString(titleCol)?.trim().orEmpty()
                    val displayName = if (displayNameCol >= 0) cursor.getString(displayNameCol) else null
                    val title = rawTitle.ifEmpty {
                        displayName?.substringBeforeLast('.') ?: "Track $id"
                    }

                    val rawArtist = cursor.getString(artistCol)?.trim().orEmpty()
                    val artist = if (rawArtist.isEmpty() || rawArtist == "<unknown>") {
                        "Unknown Artist"
                    } else {
                        rawArtist
                    }

                    val rawAlbum = cursor.getString(albumCol)?.trim().orEmpty()
                    val album = if (rawAlbum.isEmpty() || rawAlbum == "<unknown>") {
                        "Unknown Album"
                    } else {
                        rawAlbum
                    }

                    val albumId = if (albumIdCol >= 0 && !cursor.isNull(albumIdCol)) {
                        cursor.getLong(albumIdCol).toString()
                    } else {
                        null
                    }

                    val durationMs = cursor.getLong(durationCol).coerceAtLeast(0L)
                    val sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) {
                        cursor.getLong(sizeCol)
                    } else {
                        null
                    }
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    val addedAt = if (dateAddedCol >= 0 && !cursor.isNull(dateAddedCol)) {
                        cursor.getLong(dateAddedCol) * 1000L
                    } else {
                        null
                    }

                    val bitrateKbps = if (bitrateCol >= 0 && !cursor.isNull(bitrateCol)) {
                        (cursor.getInt(bitrateCol) / 1000).takeIf { it > 0 }
                    } else if (sizeBytes != null && durationMs > 0L) {
                        ((sizeBytes * 8L) / durationMs).toInt().takeIf { it > 0 }
                    } else {
                        null
                    }

                    val format = audioFormat(mimeType, displayName)
                    val location = if (dataCol >= 0) {
                        StorageLocation.ofPath(cursor.getString(dataCol))
                    } else {
                        StorageLocation.ofMediaStore(
                            if (volumeCol >= 0) cursor.getString(volumeCol) else null,
                            if (relativePathCol >= 0) cursor.getString(relativePathCol) else null,
                            displayName
                        )
                    }

                    results += MediaStoreTrack(
                        CoreTrackView(
                            id = "mediastore-$id",
                            queueEntryId = "occ-ms-$id",
                            title = title,
                            artist = artist,
                            artists = listOf(artist),
                            album = album,
                            albumId = albumId,
                            source = contentUri,
                            duration = durationMs / 1000.0,
                            size = sizeBytes,
                            format = format,
                            // MediaStore does not report these fields here. The decoder supplies
                            // measured values during playback; file extensions are not evidence.
                            sampleRate = null,
                            bitDepth = null,
                            bitrate = bitrateKbps?.toDouble(),
                            addedAt = addedAt
                        ),
                        location
                    )
                }
            }
        }.getOrThrow()

        results
    }
}
