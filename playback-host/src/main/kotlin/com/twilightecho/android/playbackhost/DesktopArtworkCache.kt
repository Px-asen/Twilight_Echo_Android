package com.twilightecho.android.playbackhost

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.DesktopMediaReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Durable artwork cache key for a desktop library song.
 *
 * Desktop `remote-track:<uuid>` IDs are session-scoped and change when the computer restarts, while
 * `catalog.sourceId` (also carried inside `desktop-stream:...` media references) is stable across
 * desktop restarts and offline/online transitions.
 */
fun desktopTrackArtworkKey(track: CoreTrackView): String {
    track.catalog?.sourceId?.takeIf { it.isNotBlank() }?.let { return "source:$it" }
    val reference = DesktopMediaReference.parse(track.source.orEmpty())
        ?: DesktopMediaReference.parse(track.id)
    if (reference != null && reference.sourceId.isNotBlank()) return "source:${reference.sourceId}"
    if (track.title.isNotBlank() || track.artist.isNotBlank() || track.album.isNotBlank()) {
        val meta = listOf(track.title, track.artist, track.album)
            .joinToString("\u0000") { it.trim().lowercase() }
        return "meta:$meta"
    }
    return "id:${track.id}"
}

@Serializable
internal data class DesktopServerArtworkIndex(
    val serverId: String,
    val entries: Map<String, String> = emptyMap(),
    val checkedRevision: String? = null,
    val missingKeys: Set<String> = emptySet()
)

/**
 * Persistent disk + bounded in-memory LRU cache for desktop song cover previews (`library.artwork`).
 *
 * Covers are content-addressed by SHA-256 so tracks from the same album share a single blob on disk
 * and in memory. External entity metadata images (`EntityMetadataDetail.artwork` with usage rules)
 * are never stored here.
 */
internal class DesktopArtworkCache(
    stateDirectory: File,
    private val maxMemoryChars: Int = DEFAULT_MAX_MEMORY_CHARS,
    private val maxDiskBytes: Long = DEFAULT_MAX_DISK_BYTES
) {
    private val root = File(stateDirectory, "desktop-artwork")
    private val blobsDir = File(root, "blobs")
    private val json = DefaultCoreClient.DefaultJson
    private val lock = Any()

    private var indexesLoaded = false
    private val indexes = HashMap<String, DesktopServerArtworkIndex>()
    private val memoryBlobs = LinkedHashMap<String, String>(64, 0.75f, true)
    private var memoryChars = 0

    fun loadIndexes() {
        val loaded = HashMap<String, DesktopServerArtworkIndex>()
        val files = root.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(INDEX_PREFIX) && it.name.endsWith(".json") }
        for (file in files) {
            val parsed = runCatching {
                json.decodeFromString<DesktopServerArtworkIndex>(file.readText(Charsets.UTF_8))
            }.getOrNull() ?: continue
            if (parsed.serverId.isNotBlank()) {
                loaded[parsed.serverId] = parsed
            }
        }
        synchronized(lock) {
            for ((serverId, saved) in loaded) {
                if (serverId !in indexes) {
                    indexes[serverId] = saved
                }
            }
            indexesLoaded = true
        }
    }

    private fun ensureIndexesLoadedLocked() {
        if (!indexesLoaded) {
            indexesLoaded = true
            val files = root.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith(INDEX_PREFIX) && it.name.endsWith(".json") }
            for (file in files) {
                val parsed = runCatching {
                    json.decodeFromString<DesktopServerArtworkIndex>(file.readText(Charsets.UTF_8))
                }.getOrNull() ?: continue
                if (parsed.serverId.isNotBlank() && parsed.serverId !in indexes) {
                    indexes[parsed.serverId] = parsed
                }
            }
        }
    }

    /** Non-blocking memory-only lookup safe for synchronous Compose reads without disk I/O. */
    fun getMemoryOnly(serverId: String, durableKey: String): String? = synchronized(lock) {
        val hash = indexes[serverId]?.entries?.get(durableKey) ?: return null
        memoryBlobs[hash]
    }

    /** Returns a cached cover data URL from memory or disk, populating the memory LRU on a disk hit. */
    fun get(serverId: String, durableKey: String): String? {
        val hash = synchronized(lock) {
            ensureIndexesLoadedLocked()
            val h = indexes[serverId]?.entries?.get(durableKey) ?: return null
            memoryBlobs[h]?.let { return it }
            h
        }
        val file = blobFile(hash)
        val dataUrl = runCatching {
            if (file.isFile) sanitizeCoverDataUrl(file.readText(Charsets.UTF_8)) else null
        }.getOrNull()
        synchronized(lock) {
            if (dataUrl != null) {
                putMemoryLocked(hash, dataUrl)
                return dataUrl
            }
            val current = indexes[serverId]
            if (current != null && current.entries[durableKey] == hash) {
                val updated = current.copy(entries = current.entries - durableKey)
                indexes[serverId] = updated
                saveIndexFile(updated)
            }
            return null
        }
    }

    /**
     * Whether [durableKey] already has a stored cover or was already checked as missing under [revisionKey].
     */
    fun isKnown(serverId: String, durableKey: String, revisionKey: String? = null): Boolean {
        val hash: String?
        val knownMissing: Boolean
        synchronized(lock) {
            ensureIndexesLoadedLocked()
            val index = indexes[serverId] ?: return false
            hash = index.entries[durableKey]
            if (hash != null && hash in memoryBlobs) return true
            knownMissing = revisionKey != null && index.checkedRevision == revisionKey && durableKey in index.missingKeys
        }
        if (hash != null && blobFile(hash).isFile) return true
        return knownMissing
    }

    /**
     * Persists a batch of `library.artwork` results for [serverId].
     * A non-null valid data URL is stored; an explicit `null` clears any previous cover and records
     * the key as missing for [revisionKey].
     */
    fun putBatch(serverId: String, updates: Map<String, String?>, revisionKey: String? = null) {
        if (serverId.isBlank() || updates.isEmpty()) return
        val preparedBlobs = HashMap<String, Pair<String, String>>() // durableKey -> (hash, sanitizedDataUrl)
        val clearedKeys = HashSet<String>()
        for ((key, rawCover) in updates) {
            if (key.isBlank()) continue
            if (rawCover == null) {
                clearedKeys += key
                continue
            }
            val clean = sanitizeCoverDataUrl(rawCover)
            if (clean != null) {
                val hash = sha256Hex(clean, 32)
                writeBlobIfAbsent(hash, clean)
                preparedBlobs[key] = hash to clean
            } else {
                clearedKeys += key
            }
        }
        if (preparedBlobs.isEmpty() && clearedKeys.isEmpty()) return

        synchronized(lock) {
            ensureIndexesLoadedLocked()
            val current = indexes[serverId] ?: DesktopServerArtworkIndex(serverId = serverId)
            val entries = HashMap(current.entries)
            val missing = if (revisionKey != null && current.checkedRevision != null && current.checkedRevision != revisionKey) {
                HashSet()
            } else {
                HashSet(current.missingKeys)
            }
            for ((key, pair) in preparedBlobs) {
                val (hash, dataUrl) = pair
                entries[key] = hash
                missing.remove(key)
                putMemoryLocked(hash, dataUrl)
            }
            for (key in clearedKeys) {
                entries.remove(key)
                missing.add(key)
            }
            val nextRevision = revisionKey ?: current.checkedRevision
            val updated = DesktopServerArtworkIndex(
                serverId = serverId,
                entries = entries,
                checkedRevision = nextRevision,
                missingKeys = missing
            )
            if (updated != current) {
                indexes[serverId] = updated
                saveIndexFile(updated)
                pruneBlobsLocked()
            }
        }
    }

    /** Drops cached index entries for songs removed from [serverId]'s complete library index. */
    fun retainServerKeys(serverId: String, activeKeys: Set<String>) {
        synchronized(lock) {
            ensureIndexesLoadedLocked()
            val current = indexes[serverId] ?: return
            val filteredEntries = current.entries.filterKeys { it in activeKeys }
            val filteredMissing = current.missingKeys.filterTo(HashSet()) { it in activeKeys }
            if (filteredEntries.size == current.entries.size && filteredMissing.size == current.missingKeys.size) return
            val updated = current.copy(entries = filteredEntries, missingKeys = filteredMissing)
            indexes[serverId] = updated
            saveIndexFile(updated)
            pruneBlobsLocked()
        }
    }

    /** Deletes all cached cover entries and unreferenced blobs for a forgotten computer. */
    fun deleteServer(serverId: String) {
        synchronized(lock) {
            ensureIndexesLoadedLocked()
            indexes.remove(serverId)
            runCatching { Files.deleteIfExists(indexFile(serverId).toPath()) }
            pruneBlobsLocked()
        }
    }

    private fun putMemoryLocked(hash: String, dataUrl: String) {
        val previous = memoryBlobs.put(hash, dataUrl)
        if (previous != null) memoryChars -= previous.length
        memoryChars += dataUrl.length
        val iterator = memoryBlobs.entries.iterator()
        while (memoryChars > maxMemoryChars && memoryBlobs.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            memoryChars -= eldest.value.length
            iterator.remove()
        }
    }

    private fun writeBlobIfAbsent(hash: String, dataUrl: String) {
        val target = blobFile(hash)
        if (target.isFile && target.length() > 0L) return
        blobsDir.mkdirs()
        val temp = File(blobsDir, "$hash.${ Thread.currentThread().id }.tmp")
        try {
            temp.writeText(dataUrl, Charsets.UTF_8)
            atomicMove(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun saveIndexFile(index: DesktopServerArtworkIndex) {
        root.mkdirs()
        val target = indexFile(index.serverId)
        val temp = File(root, "${target.name}.tmp")
        try {
            temp.writeText(json.encodeToString(index), Charsets.UTF_8)
            atomicMove(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun pruneBlobsLocked() {
        val referenced = HashSet<String>()
        for (index in indexes.values) {
            referenced.addAll(index.entries.values)
        }
        val memoryIter = memoryBlobs.entries.iterator()
        while (memoryIter.hasNext()) {
            val entry = memoryIter.next()
            if (entry.key !in referenced) {
                memoryChars -= entry.value.length
                memoryIter.remove()
            }
        }
        val blobFiles = blobsDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".dat") }
        val kept = ArrayList<File>(blobFiles.size)
        for (file in blobFiles) {
            val hash = file.name.removeSuffix(".dat")
            if (hash !in referenced) {
                runCatching { file.delete() }
            } else {
                kept += file
            }
        }
        var totalBytes = kept.sumOf { it.length() }
        if (totalBytes <= maxDiskBytes) return

        val evictedHashes = HashSet<String>()
        for (file in kept.sortedBy { it.lastModified() }) {
            if (totalBytes <= maxDiskBytes) break
            val len = file.length()
            val hash = file.name.removeSuffix(".dat")
            if (runCatching { file.delete() }.getOrDefault(false)) {
                totalBytes -= len
                evictedHashes += hash
                memoryBlobs.remove(hash)?.let { memoryChars -= it.length }
            }
        }
        if (evictedHashes.isNotEmpty()) {
            for ((serverId, index) in HashMap(indexes)) {
                val filtered = index.entries.filterValues { it !in evictedHashes }
                if (filtered.size != index.entries.size) {
                    val updated = index.copy(entries = filtered)
                    indexes[serverId] = updated
                    saveIndexFile(updated)
                }
            }
        }
    }

    private fun indexFile(serverId: String): File =
        File(root, "$INDEX_PREFIX${sha256Hex(serverId, 24)}.json")

    private fun blobFile(hash: String): File =
        File(blobsDir, "$hash.dat")

    companion object {
        private const val INDEX_PREFIX = "index-"
        private const val DEFAULT_MAX_MEMORY_CHARS = 16 * 1024 * 1024
        private const val DEFAULT_MAX_DISK_BYTES = 64L * 1024L * 1024L
        private const val MAX_COVER_CHARS = 2 * 1024 * 1024
        private val DATA_URL_PREFIX = Regex("^data:image/(?:png|jpe?g|webp);base64,", RegexOption.IGNORE_CASE)

        internal fun sanitizeCoverDataUrl(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.length < 32 || trimmed.length > MAX_COVER_CHARS) return null
            val match = DATA_URL_PREFIX.find(trimmed) ?: return null
            val payload = trimmed.substring(match.range.last + 1)
            if (payload.isBlank()) return null
            for (ch in payload) {
                val valid = (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') ||
                    ch == '+' || ch == '/' || ch == '=' || ch == '\n' || ch == '\r'
                if (!valid) return null
            }
            return trimmed
        }

        private fun sha256Hex(input: String, hexChars: Int): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append("%02x".format(b.toInt() and 0xff))
                if (sb.length >= hexChars) break
            }
            return sb.substring(0, minOf(hexChars, sb.length))
        }

        private fun atomicMove(source: File, target: File) {
            try {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
