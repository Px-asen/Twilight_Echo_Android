package com.twilightecho.android.adapters

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CueSheets
import com.twilightecho.android.coreclient.CueSkip
import com.twilightecho.android.coreclient.FolderFile
import java.security.MessageDigest
import java.util.Locale

/** A file a folder lists. [documentId] names it to the storage provider; [size] is -1 when unknown. */
data class ListedFile(val documentId: String, val name: String, val size: Long = -1, val lastModified: Long? = null)

/** One folder inside a picked music folder; [path] starts with the picked folder's name. */
data class ListedFolder(val path: String, val files: List<ListedFile>)

/** Tags MediaMetadataRetriever reads from a file MediaStore does not list. */
data class FileTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationSeconds: Double? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: String? = null,
    val genre: String? = null,
    val bitrateKbps: Double? = null
)

/** How a folder scan reads the files it lists. */
interface FolderReader {
    /** What playback and artwork open. */
    fun uri(file: ListedFile): String
    /** At most [CueSheets.MAX_BYTES] + 1 bytes, so an oversized sheet is refused without reading all of it. */
    fun bytes(file: ListedFile): ByteArray
    fun header(file: ListedFile): AudioHeader?
    fun tags(file: ListedFile): FileTags?
}

/**
 * What music folders change in the MediaStore library: songs only the folders hold, including the songs CUE
 * sheets split from one file; the MediaStore songs those splits replace; folder cover images for MediaStore
 * songs; and the sheets that split nothing, named by path.
 */
data class FolderSongs(
    val tracks: List<CoreTrackView> = emptyList(),
    val replaced: Set<String> = emptySet(),
    val covers: Map<String, String> = emptyMap(),
    val skipped: List<CueSkip> = emptyList()
) {
    companion object {
        fun of(parts: List<FolderSongs>) = FolderSongs(parts.flatMap { it.tracks }, parts.flatMapTo(HashSet()) { it.replaced },
            parts.fold(HashMap()) { covers, part -> covers.apply { putAll(part.covers) } }, parts.flatMap { it.skipped })
    }

    /** The library: MediaStore's songs, less the files sheets split, then the folders' own songs. */
    fun over(mediaStore: List<CoreTrackView>): List<CoreTrackView> =
        (mediaStore.filter { it.id !in replaced }.map { track -> covers[track.id]?.let { track.copy(cover = track.cover ?: it) } ?: track } +
            tracks).distinctBy { it.id }
}

/**
 * A file's place on shared storage as `volume:path`, lower-cased, the way the external storage provider names
 * documents (`primary:Music/a.flac`), so a folder's files find their MediaStore rows.
 */
object StorageLocation {
    fun ofDocument(documentId: String): String = documentId.lowercase(Locale.ROOT)

    /** From MediaStore's volume, relative path and file name (Android 10 and later). */
    fun ofMediaStore(volume: String?, relativePath: String?, name: String?): String? {
        if (volume.isNullOrEmpty() || relativePath == null || name.isNullOrEmpty()) return null
        val root = when (volume.lowercase(Locale.ROOT)) {
            "external_primary", "external" -> "primary"
            else -> volume
        }
        val folder = relativePath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return "$root:$folder$name".lowercase(Locale.ROOT)
    }

    /** From the file's path (before Android 10). */
    fun ofPath(path: String?): String? {
        if (path == null) return null
        PRIMARY.find(path)?.let { return "primary:${it.groupValues[1]}".lowercase(Locale.ROOT) }
        val volume = VOLUME.find(path) ?: return null
        if (volume.groupValues[1] in setOf("emulated", "self")) return null
        return "${volume.groupValues[1]}:${volume.groupValues[2]}".lowercase(Locale.ROOT)
    }

    private val PRIMARY = Regex("^/(?:storage/emulated/\\d+|sdcard)/(.+)$")
    private val VOLUME = Regex("^/storage/([^/]+)/(.+)$")
}

object MusicFolders {
    private val COVER_NAMES = listOf("cover", "folder", "front", "albumart")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    /** A sheet may split an MP4 file, but on its own one is more likely a video. */
    private val SONG_EXTENSIONS = CueSheets.AUDIO_EXTENSIONS - "mp4"

    /**
     * One folder's songs over [mediaStore], MediaStore's songs by [StorageLocation]. A file a sheet splits
     * becomes the sheet's songs; any other file MediaStore lists stays MediaStore's song.
     */
    fun songs(folder: ListedFolder, mediaStore: Map<String, CoreTrackView>, reader: FolderReader): FolderSongs {
        // Names starting with a dot are hidden, including the `._` copies macOS leaves beside each file.
        val files = folder.files.filter { !it.name.startsWith(".") }
        val audio = files.filter { extension(it.name) in CueSheets.AUDIO_EXTENSIONS }
        val sheets = files.filter { extension(it.name) == "cue" }
        val images = files.filter { extension(it.name) in IMAGE_EXTENSIONS }
        val folderCover = COVER_NAMES.firstNotNullOfOrNull { name -> images.firstOrNull { baseName(it.name) == name } }
        val sheetFiles = sheets.associateBy { it.documentId }
        val claims = CueSheets.claims(audio.map { FolderFile(it.documentId, it.name) }, sheets.map { FolderFile(it.documentId, it.name) }) {
            reader.bytes(sheetFiles.getValue(it.key))
        }
        val tracks = mutableListOf<CoreTrackView>()
        val replaced = mutableSetOf<String>()
        val covers = mutableMapOf<String, String>()
        val skipped = claims.skipped.toMutableList()
        for (file in audio) {
            val cover = (images.firstOrNull { baseName(it.name) == baseName(file.name) } ?: folderCover)?.let(reader::uri)
            val stored = mediaStore[StorageLocation.ofDocument(file.documentId)]
            val candidates = claims.byAudio[file.documentId]
            if (stored != null && candidates == null) {
                if (cover != null) covers[stored.id] = cover
                continue
            }
            if (stored == null && candidates == null && extension(file.name) !in SONG_EXTENSIONS) continue
            val header = reader.header(file)
            val own = (stored ?: describe(file, folder, reader, header)).let { it.copy(cover = it.cover ?: cover) }
            if (candidates != null) {
                // Header lengths are exact, so the last song ends exactly where the file does.
                val base = own.copy(source = reader.uri(file), duration = header?.durationSeconds ?: own.duration)
                val split = CueSheets.split(base, file.documentId, candidates)
                skipped += split.skipped
                if (split.tracks.isNotEmpty()) {
                    tracks += split.tracks
                    stored?.let { replaced += it.id }
                    continue
                }
                if (stored != null) {
                    if (cover != null) covers[stored.id] = cover
                    continue
                }
                if (extension(file.name) !in SONG_EXTENSIONS) continue
            }
            if (own.duration > 0) tracks += own
        }
        return FolderSongs(tracks, replaced, covers, skipped.map { it.copy(sheetName = "${folder.path}/${it.sheetName}") })
    }

    /** A song for a file MediaStore does not list, from its tags and header. */
    private fun describe(file: ListedFile, folder: ListedFolder, reader: FolderReader, header: AudioHeader?): CoreTrackView {
        val tags = reader.tags(file)
        val artist = tags?.artist ?: "Unknown Artist"
        val duration = header?.durationSeconds ?: tags?.durationSeconds ?: 0.0
        val size = file.size.takeIf { it >= 0 }
        return CoreTrackView(
            id = folderTrackId(file.documentId),
            title = tags?.title ?: file.name.substringBeforeLast('.').ifEmpty { file.name },
            artist = artist,
            artists = listOf(artist),
            album = tags?.album ?: folder.path.substringAfterLast('/'),
            albumArtist = tags?.albumArtist,
            source = reader.uri(file),
            duration = duration,
            size = size,
            format = audioFormat(null, file.name),
            sampleRate = header?.sampleRate,
            bitDepth = header?.bitDepth,
            bitrate = tags?.bitrateKbps ?: if (size != null && duration > 0) size * 8 / duration / 1000 else null,
            trackNumber = tags?.trackNumber,
            discNumber = tags?.discNumber,
            releaseDate = tags?.year,
            genre = tags?.genre,
            addedAt = file.lastModified
        )
    }

    /** Stable while the file does not move, like a split song's ID. */
    fun folderTrackId(documentId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(documentId.toByteArray(Charsets.UTF_8))
        return "local:saf:" + digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private fun baseName(name: String) = name.substringBeforeLast('.').lowercase(Locale.ROOT)
}

/** The format the library shows, from the MIME type or the file name. */
internal fun audioFormat(mimeType: String?, displayName: String?): String {
    val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    val mime = mimeType?.lowercase().orEmpty()
    return when {
        ext == "flac" || "flac" in mime -> "FLAC"
        ext == "wav" || "wav" in mime -> "WAV"
        ext == "dsf" || ext == "dff" || "dsd" in mime -> "DSD"
        ext == "m4a" || ext == "alac" || "mp4" in mime || "aac" in mime -> "AAC/M4A"
        ext == "ogg" || ext == "opus" || "ogg" in mime || "opus" in mime -> "OPUS/OGG"
        ext == "mp3" || "mpeg" in mime -> "MP3"
        else -> ext.uppercase().ifEmpty { "AUDIO" }
    }
}
