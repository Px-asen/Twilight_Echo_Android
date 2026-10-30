package com.twilightecho.android.adapters

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.TrackInspectionDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Probes local audio files on demand for detailed tag metadata, exact audio header specs
 * (sample rate, bit depth, channels, bitrate), file size, and human-readable storage location.
 */
class AndroidTrackMetadataProbe(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun inspect(track: CoreTrackView): TrackInspectionDetails = withContext(Dispatchers.IO) {
        val rawSource = track.source?.takeIf { it.isNotBlank() }
        val base = TrackInspectionDetails(
            title = track.title.takeIf { it.isNotBlank() },
            artist = track.artists.filter { it.isNotBlank() }.joinToString(" / ")
                .ifBlank { track.artist }.takeIf { it.isNotBlank() },
            album = track.album.takeIf { it.isNotBlank() },
            albumArtist = track.albumArtist?.takeIf { it.isNotBlank() },
            trackNumber = track.trackNumber?.takeIf { it > 0 },
            discNumber = track.discNumber?.takeIf { it > 0 },
            releaseDate = track.releaseDate?.takeIf { it.isNotBlank() },
            genre = track.genre?.takeIf { it.isNotBlank() },
            format = track.format?.takeIf { it.isNotBlank() },
            durationSeconds = track.duration.takeIf { it > 0.0 },
            sampleRate = track.sampleRate?.takeIf { it > 0 },
            bitDepth = track.bitDepth?.takeIf { it > 0 },
            bitrateKbps = track.bitrate?.takeIf { it > 0.0 },
            fileSize = track.size?.takeIf { it > 0L }
        )
        if (rawSource == null) return@withContext base

        val uri = runCatching { Uri.parse(rawSource) }.getOrNull() ?: return@withContext base
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "content" && scheme != "file" && !rawSource.startsWith("/")) {
            return@withContext base.copy(displayPath = rawSource)
        }

        val resolvedInfo = if (scheme == "content") queryContentMeta(uri) else queryFileMeta(uri, rawSource)
        val headerAndChannels = probeHeaderAndChannels(uri, scheme, rawSource)
        val retrieverTags = probeRetrieverTags(uri, scheme, rawSource)

        val header = headerAndChannels?.first
        val channels = headerAndChannels?.second ?: retrieverTags?.channels
        val duration = track.cueRange?.let { (it.endSeconds - it.startSeconds).takeIf { d -> d > 0.0 } }
            ?: base.durationSeconds
            ?: header?.durationSeconds?.takeIf { it > 0.0 }
            ?: retrieverTags?.durationSeconds
        val fullFileDuration = header?.durationSeconds?.takeIf { it > 0.0 }
            ?: retrieverTags?.durationSeconds
            ?: base.durationSeconds
        val size = base.fileSize ?: resolvedInfo.size
        val computedBitrate = if (size != null && fullFileDuration != null && fullFileDuration > 0.0) {
            (size * 8.0) / fullFileDuration / 1000.0
        } else null
        val mime = resolvedInfo.mimeType ?: retrieverTags?.mimeType
        val fileName = resolvedInfo.fileName

        TrackInspectionDetails(
            title = base.title ?: retrieverTags?.title,
            artist = base.artist ?: retrieverTags?.artist,
            album = base.album ?: retrieverTags?.album,
            albumArtist = base.albumArtist ?: retrieverTags?.albumArtist,
            composer = retrieverTags?.composer,
            trackNumber = base.trackNumber ?: retrieverTags?.trackNumber,
            discNumber = base.discNumber ?: retrieverTags?.discNumber,
            releaseDate = base.releaseDate ?: retrieverTags?.year,
            genre = base.genre ?: retrieverTags?.genre,
            format = base.format ?: audioFormat(mime, fileName).takeIf { it != "AUDIO" },
            mimeType = mime,
            durationSeconds = duration,
            sampleRate = header?.sampleRate?.takeIf { it > 0 } ?: base.sampleRate ?: retrieverTags?.sampleRate,
            bitDepth = header?.bitDepth?.takeIf { it > 0 } ?: base.bitDepth ?: retrieverTags?.bitDepth,
            channels = channels,
            bitrateKbps = base.bitrateKbps ?: retrieverTags?.bitrateKbps ?: computedBitrate,
            fileSize = size,
            fileName = fileName,
            displayPath = resolvedInfo.displayPath ?: rawSource
        )
    }

    private data class ResolvedMeta(
        val fileName: String? = null,
        val size: Long? = null,
        val mimeType: String? = null,
        val displayPath: String? = null
    )

    private data class RetrieverTags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val composer: String? = null,
        val durationSeconds: Double? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val year: String? = null,
        val genre: String? = null,
        val bitrateKbps: Double? = null,
        val mimeType: String? = null,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        val channels: Int? = null
    )

    private fun queryContentMeta(uri: Uri): ResolvedMeta {
        var name: String? = null
        var size: Long? = null
        var relativePath: String? = null
        val mime = runCatching { resolver.getType(uri) }.getOrNull()?.takeIf { it.isNotBlank() }

        val projection = buildList {
            add(OpenableColumns.DISPLAY_NAME)
            add(OpenableColumns.SIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val relIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else -1
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        name = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx).takeIf { it > 0L }
                    }
                    if (relIdx >= 0 && !cursor.isNull(relIdx)) {
                        relativePath = cursor.getString(relIdx)?.takeIf { it.isNotBlank() }
                    }
                }
            }
        }

        val safPath = runCatching {
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                formatDocumentIdPath(DocumentsContract.getDocumentId(uri))
            } else null
        }.getOrNull()

        val displayPath = when {
            safPath != null -> safPath
            relativePath != null && name != null -> "${relativePath!!.trimEnd('/')}/$name"
            relativePath != null -> relativePath
            else -> uri.toString()
        }
        val fallbackName = name ?: safPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return ResolvedMeta(
            fileName = fallbackName,
            size = size,
            mimeType = mime,
            displayPath = displayPath
        )
    }

    private fun queryFileMeta(uri: Uri, rawSource: String): ResolvedMeta {
        val path = uri.path?.takeIf { it.isNotBlank() } ?: rawSource
        val file = File(path)
        return ResolvedMeta(
            fileName = file.name.takeIf { it.isNotBlank() },
            size = file.length().takeIf { file.exists() && it > 0L },
            displayPath = file.path
        )
    }

    private fun probeHeaderAndChannels(uri: Uri, scheme: String, rawSource: String): Pair<AudioHeader?, Int?>? = runCatching {
        if (scheme == "content") {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel.use { channel ->
                val readAt: (Long, Int) -> ByteArray = { offset, length ->
                    val buffer = ByteBuffer.allocate(length)
                    while (buffer.hasRemaining() && channel.read(buffer, offset + buffer.position()) > 0) Unit
                    buffer.array().copyOf(buffer.position())
                }
                AudioHeaderProbe.probe(readAt) to probeAudioChannels(readAt)
            }
        } else {
            val file = File(uri.path?.takeIf { it.isNotBlank() } ?: rawSource)
            if (!file.isFile) return@runCatching null
            FileInputStream(file).channel.use { channel ->
                val readAt: (Long, Int) -> ByteArray = { offset, length ->
                    val buffer = ByteBuffer.allocate(length)
                    while (buffer.hasRemaining() && channel.read(buffer, offset + buffer.position()) > 0) Unit
                    buffer.array().copyOf(buffer.position())
                }
                AudioHeaderProbe.probe(readAt) to probeAudioChannels(readAt)
            }
        }
    }.getOrNull()

    private fun probeRetrieverTags(uri: Uri, scheme: String, rawSource: String): RetrieverTags? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (scheme == "content") {
                retriever.setDataSource(appContext, uri)
            } else {
                retriever.setDataSource(uri.path?.takeIf { it.isNotBlank() } ?: rawSource)
            }
            fun text(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            fun number(key: Int) = text(key)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val year = text(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: text(MediaMetadataRetriever.METADATA_KEY_DATE)
            val composer = text(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                ?: text(MediaMetadataRetriever.METADATA_KEY_WRITER)
                ?: text(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                number(METADATA_KEY_SAMPLERATE_Compat)
            } else null
            val bitDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                number(METADATA_KEY_BITS_PER_SAMPLE_Compat)
            } else null
            RetrieverTags(
                title = text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                composer = composer,
                durationSeconds = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.takeIf { it > 0L }?.let { it / 1000.0 },
                trackNumber = number(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                discNumber = number(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                year = year,
                genre = text(MediaMetadataRetriever.METADATA_KEY_GENRE),
                bitrateKbps = text(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    ?.takeIf { it > 0L }?.let { it / 1000.0 },
                mimeType = text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                sampleRate = sampleRate,
                bitDepth = bitDepth
            )
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    companion object {
        // MediaMetadataRetriever.METADATA_KEY_SAMPLERATE (38) and METADATA_KEY_BITS_PER_SAMPLE (39) added in API 31.
        private const val METADATA_KEY_SAMPLERATE_Compat = 38
        private const val METADATA_KEY_BITS_PER_SAMPLE_Compat = 39

        internal fun formatDocumentIdPath(documentId: String?): String? {
            val raw = documentId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val colon = raw.indexOf(':')
            if (colon <= 0) return raw
            val volume = raw.substring(0, colon)
            val afterColon = raw.substring(colon + 1)
            if (volume.equals("raw", ignoreCase = true)) {
                return afterColon.takeIf { it.isNotBlank() }
            }
            val rel = afterColon.trimStart('/')
            return if (volume.equals("primary", ignoreCase = true)) {
                if (rel.isEmpty()) "内部存储" else "内部存储/$rel"
            } else {
                if (rel.isEmpty()) volume else "$volume/$rel"
            }
        }

        /**
         * Reads exact PCM channel counts from FLAC, WAV, and Monkey's Audio headers without
         * altering [AudioHeader] equality semantics.
         */
        internal fun probeAudioChannels(read: (offset: Long, length: Int) -> ByteArray): Int? = runCatching {
            var start = 0L
            for (attempt in 0 until 4) {
                val tag = read(start, 10)
                if (tag.size < 10 || tag.ascii(0, 3) != "ID3") break
                val size = ((tag.u8(6) and 0x7F) shl 21) or ((tag.u8(7) and 0x7F) shl 14) or
                    ((tag.u8(8) and 0x7F) shl 7) or (tag.u8(9) and 0x7F)
                start += 10L + size + if (tag.u8(5) and 0x10 != 0) 10 else 0
            }
            val head = read(start, 64)
            when {
                head.ascii(0, 4) == "fLaC" -> {
                    if (head.size < 42 || (head.u8(4) and 0x7F) != 0) null
                    else (((head.u8(20) ushr 1) and 0x07) + 1).takeIf { it in 1..8 }
                }
                head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WAVE" -> {
                    var offset = start + 12
                    var found: Int? = null
                    for (chunkIdx in 0 until 64) {
                        val chunk = read(offset, 24)
                        if (chunk.size < 8) break
                        val size = chunk.u32(4)
                        if (chunk.ascii(0, 4) == "fmt " && chunk.size >= 12) {
                            found = chunk.u16(10).takeIf { it in 1..32 }
                            break
                        }
                        offset += 8 + size + (size and 1)
                    }
                    found
                }
                head.ascii(0, 4) == "MAC " -> {
                    if (head.size < 32) null
                    else {
                        val version = head.u16(4)
                        if (version >= 3980) {
                            val descriptorBytes = head.u32(8)
                            if (descriptorBytes in 52..4096) {
                                val fields = read(start + descriptorBytes, 24)
                                if (fields.size >= 20) fields.u16(18).takeIf { it in 1..32 } else null
                            } else null
                        } else {
                            head.u16(10).takeIf { it in 1..32 }
                        }
                    }
                }
                head.ascii(0, 4) == "DSD " -> {
                    if (head.size >= 56 && head.ascii(28, 4) == "fmt ") {
                        head.u32(52).toInt().takeIf { it in 1..32 }
                    } else null
                }
                else -> null
            }
        }.getOrNull()

        private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF
        private fun ByteArray.u16(index: Int) = u8(index) or (u8(index + 1) shl 8)
        private fun ByteArray.u32(index: Int) = u16(index).toLong() or (u16(index + 2).toLong() shl 16)
        private fun ByteArray.ascii(index: Int, length: Int) =
            if (size < index + length) "" else String(this, index, length, Charsets.ISO_8859_1)
    }
}
