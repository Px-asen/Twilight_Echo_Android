package com.twilightecho.android.adapters

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.twilightecho.android.coreclient.CueSheets
import com.twilightecho.android.coreclient.CueSkip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** A picked music folder. [label] names it in the library settings. */
data class MusicFolder(val uri: String, val label: String)

/** [unreadable] lists the folders whose access was lost or whose storage is gone. */
data class MusicFolderScan(val songs: FolderSongs, val unreadable: List<MusicFolder>)

/**
 * What a library scan found: all songs, how many CUE sheets split off, the sheets that split nothing and why.
 * [withDeviceMusic] is false when Android's music permission was refused and only the music folders were read.
 */
data class LibraryScanSummary(
    val songs: Int,
    val cueSongs: Int,
    val skipped: List<CueSkip>,
    val unreadableFolders: List<MusicFolder>,
    val withDeviceMusic: Boolean = true
)

/**
 * Music folders the user picked through the Storage Access Framework. Android keeps `.cue` files out of
 * MediaStore and lists APE or WavPack files at most as plain files, so CUE sheets and such audio are found by
 * walking the folders themselves. The folders are the app's persisted folder grants.
 */
class AndroidMusicFolderScanner(private val context: Context) {
    private val resolver get() = context.contentResolver

    companion object {
        private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

        /** Where the folder picker opens: the phone's own Music folder, where music usually is. */
        fun pickerStart(): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:${Environment.DIRECTORY_MUSIC}")
    }

    fun folders(): List<MusicFolder> = runCatching {
        resolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .sortedBy { it.persistedTime }
            .map { MusicFolder(it.uri.toString(), label(it.uri)) }
    }.getOrDefault(emptyList())

    /** Keeps read access to a folder from `ACTION_OPEN_DOCUMENT_TREE` across restarts. */
    fun add(uri: String) {
        resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun remove(uri: String) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Every folder's songs over MediaStore's. A folder inside another picked folder is walked once. */
    suspend fun scan(mediaStore: List<MediaStoreTrack>): MusicFolderScan = withContext(Dispatchers.IO) {
        val byLocation = mediaStore.mapNotNull { entry -> entry.location?.let { it to entry.track } }.toMap()
        val visited = HashSet<String>()
        val parts = mutableListOf<FolderSongs>()
        val unreadable = mutableListOf<MusicFolder>()
        for (folder in folders()) {
            val tree = Uri.parse(folder.uri)
            val reader = TreeReader(tree)
            val readable = walk(tree, visited) { listed ->
                ensureActive()
                parts += MusicFolders.songs(listed, byLocation, reader)
            }
            if (!readable) unreadable += folder
        }
        MusicFolderScan(FolderSongs.of(parts), unreadable)
    }

    /** Lists each folder under [tree], eight levels deep; false when the folder itself cannot be read. */
    private fun walk(tree: Uri, visited: MutableSet<String>, onFolder: (ListedFolder) -> Unit): Boolean {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return false
        val rootName = displayName(tree, rootId) ?: return false
        val pending = ArrayDeque(listOf(Triple(rootId, rootName, 0)))
        while (pending.isNotEmpty()) {
            val (documentId, path, depth) = pending.removeFirst()
            if (!visited.add(documentId)) continue
            val children = children(tree, documentId) ?: if (documentId == rootId) return false else continue
            val files = mutableListOf<ListedFile>()
            for (child in children) {
                if (child.file.name.startsWith(".")) continue
                if (!child.directory) files += child.file
                else if (depth < 8) pending += Triple(child.file.documentId, "$path/${child.file.name}", depth + 1)
            }
            onFolder(ListedFolder(path, files))
        }
        return true
    }

    private class Child(val file: ListedFile, val directory: Boolean)

    private fun children(tree: Uri, documentId: String): List<Child>? = try {
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId), arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        ), null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    add(Child(
                        ListedFile(id, cursor.getString(1).orEmpty(), if (cursor.isNull(3)) -1 else cursor.getLong(3),
                            if (cursor.isNull(4)) null else cursor.getLong(4).takeIf { it > 0 }),
                        cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    ))
                }
            }
        }
    } catch (_: Exception) {
        // Lost access and removed storage both leave the folder unreadable.
        null
    }

    private fun displayName(tree: Uri, documentId: String): String? = try {
        resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty().ifEmpty { "文件夹" } else null
        }
    } catch (_: Exception) {
        null
    }

    /** `Music/CueTest` on the phone's own storage; other volumes and providers are named as Android names them. */
    private fun label(tree: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
        if (tree.authority != EXTERNAL_STORAGE || ':' !in documentId) {
            return displayName(tree, documentId) ?: documentId
        }
        val volume = documentId.substringBefore(':')
        val path = documentId.substringAfter(':').trim('/')
        val volumeName = if (volume == "primary") "内部存储" else runCatching {
            context.getSystemService(StorageManager::class.java).storageVolumes
                .firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.getDescription(context)
        }.getOrNull() ?: volume
        return when {
            path.isEmpty() -> volumeName
            volume == "primary" -> path
            else -> "$volumeName/$path"
        }
    }

    private inner class TreeReader(private val tree: Uri) : FolderReader {
        override fun uri(file: ListedFile): String = DocumentsContract.buildDocumentUriUsingTree(tree, file.documentId).toString()

        override fun bytes(file: ListedFile): ByteArray {
            val input = resolver.openInputStream(Uri.parse(uri(file))) ?: throw java.io.IOException("无法读取 ${file.name}")
            return input.use {
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (output.size() <= CueSheets.MAX_BYTES) {
                    val count = it.read(chunk, 0, minOf(chunk.size, CueSheets.MAX_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
        }

        override fun header(file: ListedFile): AudioHeader? = runCatching {
            val descriptor = resolver.openFileDescriptor(Uri.parse(uri(file)), "r") ?: return@runCatching null
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel.use { channel ->
                AudioHeaderProbe.probe { offset, length ->
                    val buffer = ByteBuffer.allocate(length)
                    while (buffer.hasRemaining() && channel.read(buffer, offset + buffer.position()) > 0) Unit
                    buffer.array().copyOf(buffer.position())
                }
            }
        }.getOrNull()

        override fun tags(file: ListedFile): FileTags? = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri(file)))
                fun text(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
                fun number(key: Int) = text(key)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                FileTags(
                    title = text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    albumArtist = text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    durationSeconds = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
                        ?.let { it / 1000.0 },
                    trackNumber = number(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                    discNumber = number(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                    year = text(MediaMetadataRetriever.METADATA_KEY_YEAR),
                    genre = text(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    bitrateKbps = text(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 }
                        ?.let { it / 1000.0 }
                )
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
    }
}
