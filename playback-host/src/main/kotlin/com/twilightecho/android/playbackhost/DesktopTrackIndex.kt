package com.twilightecho.android.playbackhost

import android.util.AtomicFile
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DefaultCoreClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest

/**
 * What the phone remembers of one computer's library, so its songs stay listed while that computer
 * is offline. Track `id`s are the desktop's session references from the last sync and are only
 * valid for that desktop epoch; `catalog.sourceId` is the durable reference used for streaming.
 */
@Serializable
data class DesktopLibraryIndex(
    val serverId: String,
    val libraryId: String? = null,
    val hostLabel: String,
    val baseUrl: String,
    val epoch: String? = null,
    val revision: Long? = null,
    val syncedAt: Long = 0L,
    val complete: Boolean = false,
    val tracks: List<CoreTrackView> = emptyList()
)

/** One AtomicFile per computer under `twilight_core_state/desktop-libraries`. Never holds tickets or tokens. */
internal class DesktopTrackIndexStore(stateDirectory: File) {
    private val root = File(stateDirectory, "desktop-libraries")
    private val json = DefaultCoreClient.DefaultJson

    fun loadAll(): List<DesktopLibraryIndex> {
        val names = root.listFiles().orEmpty().map { it.name.removeSuffix(".new").removeSuffix(".bak") }
            .filter { it.startsWith(PREFIX) && it.endsWith(".json") }.distinct()
        return names.mapNotNull { name ->
            runCatching { json.decodeFromString<DesktopLibraryIndex>(AtomicFile(File(root, name)).readFully().toString(Charsets.UTF_8)) }
                .getOrNull()
        }.sortedBy { it.hostLabel }
    }

    fun save(index: DesktopLibraryIndex) {
        root.mkdirs()
        val file = AtomicFile(fileFor(index.serverId))
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(index).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output); throw error
        }
    }

    fun delete(serverId: String) = AtomicFile(fileFor(serverId)).delete()

    private fun fileFor(serverId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(serverId.toByteArray(Charsets.UTF_8))
        return File(root, PREFIX + digest.take(12).joinToString("") { "%02x".format(it) } + ".json")
    }

    private companion object { const val PREFIX = "desktop-library-" }
}

/** Keeps what the list and streaming need; drops per-queue and per-page data. */
internal fun indexedDesktopTrack(track: CoreTrackView): CoreTrackView =
    track.copy(queueEntryId = null, cover = null, catalog = track.catalog?.copy(items = emptyList()))
