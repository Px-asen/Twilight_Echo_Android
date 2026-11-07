package com.twilightecho.android.playbackhost

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable enum class OfflineDownloadStatus { QUEUED, DOWNLOADING, PAUSED, READY, FAILED }
@Serializable data class OfflineDownload(
    val id: String, val reference: DesktopMediaReference, val track: CoreTrackView, val host: String,
    val status: OfflineDownloadStatus = OfflineDownloadStatus.QUEUED,
    val bytes: Long = 0, val size: Long = 0, val error: String? = null
)
data class OfflineDownloadState(val items: List<OfflineDownload> = emptyList(), val error: String? = null)

interface OfflineDownloadReader : Closeable {
    fun size(): Long
    fun version(): String
    fun readAt(position: Long, data: ByteArray): Int
}

/** Each completed file belongs to a durable source version. Partial files never enter playback. */
class OfflineDownloads(private val root: File, private val open: (DesktopMediaReference) -> OfflineDownloadReader,
    private val quota: () -> Long, private val authorize: () -> Unit) {
    private val json = DefaultCoreClient.DefaultJson
    private val manifest = File(root, "downloads.json")
    private val mutable = MutableStateFlow(OfflineDownloadState())
    val state = mutable.asStateFlow()
    @Volatile private var active: Pair<String, OfflineDownloadReader>? = null
    private val processing = java.util.concurrent.atomic.AtomicBoolean()
    init {
        root.mkdirs()
        try {
            val rows = if (manifest.exists()) json.decodeFromString<List<OfflineDownload>>(manifest.readText()) else emptyList()
            require(rows.map { it.id }.distinct().size == rows.size && rows.all { UUID.fromString(it.id).toString() == it.id })
            mutable.value = OfflineDownloadState(rows.map { row -> when {
                row.size > 0 && row.reference.sourceVersion != null && file(row.id).isFile && file(row.id).length() == row.size -> row.copy(status = OfflineDownloadStatus.READY, bytes = row.size)
                row.status == OfflineDownloadStatus.DOWNLOADING || row.status == OfflineDownloadStatus.QUEUED -> row.copy(status = OfflineDownloadStatus.PAUSED)
                row.status == OfflineDownloadStatus.READY && (!file(row.id).isFile || file(row.id).length() != row.size) -> row.copy(status = OfflineDownloadStatus.FAILED, error = "下载文件缺失，请重新下载")
                else -> row
            } })
        } catch (error: Exception) { mutable.value = OfflineDownloadState(error = "下载记录无法读取：${error.message}") }
    }
    private fun file(id: String) = File(root, "$id.media")
    private fun partial(id: String) = File(root, "$id.part")
    @Synchronized private fun save(items: List<OfflineDownload>) {
        check(mutable.value.error == null) { mutable.value.error!! }
        val temporary = File(root, "downloads.json.new")
        FileOutputStream(temporary).use { output -> output.write(json.encodeToString(items).toByteArray()); output.fd.sync() }
        Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        mutable.value = OfflineDownloadState(items)
    }
    @Synchronized fun enqueue(reference: DesktopMediaReference, track: CoreTrackView, host: String) {
        val existing = mutable.value.items.firstOrNull { it.reference.copy(sourceVersion = null) == reference.copy(sourceVersion = null) }
        if (existing?.status == OfflineDownloadStatus.READY) return
        if (existing != null) { resume(existing.id); return }
        require(mutable.value.items.size < 500) { "下载列表已满，请移除不需要的下载" }
        save(mutable.value.items + OfflineDownload(UUID.randomUUID().toString(), reference, track.copy(source = null, queueEntryId = null), host))
    }
    @Synchronized fun resume(id: String) {
        save(mutable.value.items.map { if (it.id == id && it.status != OfflineDownloadStatus.READY && it.status != OfflineDownloadStatus.DOWNLOADING)
            it.copy(status = OfflineDownloadStatus.QUEUED, error = null) else it })
    }
    @Synchronized fun pause(id: String) {
        save(mutable.value.items.map { if (it.id == id && it.status in setOf(OfflineDownloadStatus.QUEUED, OfflineDownloadStatus.DOWNLOADING))
            it.copy(status = OfflineDownloadStatus.PAUSED) else it })
        active?.takeIf { it.first == id }?.second?.close()
    }
    @Synchronized fun pauseAll() {
        if (mutable.value.error != null) return
        save(mutable.value.items.map { if (it.status in setOf(OfflineDownloadStatus.QUEUED, OfflineDownloadStatus.DOWNLOADING)) it.copy(status = OfflineDownloadStatus.PAUSED) else it })
        active?.second?.close()
    }
    @Synchronized fun remove(id: String) {
        if (mutable.value.items.none { it.id == id }) return
        pause(id)
        save(mutable.value.items.filterNot { it.id == id })
        file(id).delete(); partial(id).delete()
    }
    @Synchronized fun track(row: OfflineDownload): CoreTrackView? = row.takeIf {
        it.status == OfflineDownloadStatus.READY && file(it.id).isFile && file(it.id).length() == it.size
    }?.track?.copy(id = "offline-download:${row.id}", source = file(row.id).absolutePath, queueEntryId = null)
    @Synchronized fun find(serverId: String, libraryId: String?, sourceId: String): CoreTrackView? = mutable.value.items.firstOrNull {
        it.reference.serverId == serverId && it.reference.libraryId == libraryId && it.reference.sourceId == sourceId
    }?.let(::track)
    @Synchronized fun tracks(): List<CoreTrackView> = mutable.value.items.mapNotNull(::track)
    @Synchronized private fun update(id: String, transform: (OfflineDownload) -> OfflineDownload) {
        save(mutable.value.items.map { if (it.id == id) transform(it) else it })
    }
    @Synchronized private fun progress(id: String, bytes: Long, checkpoint: Boolean) {
        val items = mutable.value.items.map { if (it.id == id) it.copy(bytes = bytes) else it }
        if (checkpoint) save(items) else mutable.value = mutable.value.copy(items = items)
    }
    suspend fun runPending() = withContext(Dispatchers.IO) {
        if (!processing.compareAndSet(false, true)) return@withContext
        try {
            while (currentCoroutineContext().isActive) {
                val entry = mutable.value.items.firstOrNull { it.status == OfflineDownloadStatus.QUEUED } ?: break
                try {
                    authorize()
                    update(entry.id) { it.copy(status = OfflineDownloadStatus.DOWNLOADING, error = null) }
                    val reader = open(entry.reference)
                    active = entry.id to reader
                    reader.use {
                        val size = reader.size(); val version = reader.version()
                        require(size > 0 && size <= quota()) { "歌曲超过下载空间额度" }
                        val otherBytes = mutable.value.items.filter { it.id != entry.id }.sumOf { if (it.status == OfflineDownloadStatus.READY) it.size else partial(it.id).length() }
                        require(size <= quota() - otherBytes) { "下载空间额度不足，请删除下载或提高额度" }
                        require(entry.reference.sourceVersion == null || entry.reference.sourceVersion == version) { "电脑文件已改变，请移除此任务后重新下载" }
                        val part = partial(entry.id)
                        require(part.length() <= size) { "下载片段无效，请移除此任务后重新下载" }
                        require(root.usableSpace >= size - part.length() + 64L * 1024 * 1024) { "手机存储空间不足" }
                        update(entry.id) { it.copy(reference = it.reference.copy(sourceVersion = version), size = size, bytes = part.length()) }
                        RandomAccessFile(part, "rw").use { output ->
                            var offset = output.length(); var savedAt = offset; output.seek(offset)
                            val buffer = ByteArray(256 * 1024)
                            while (offset < size) {
                                currentCoroutineContext().ensureActive(); authorize()
                                check(mutable.value.items.any { it.id == entry.id && it.status == OfflineDownloadStatus.DOWNLOADING }) { "下载已暂停" }
                                val count = reader.readAt(offset, buffer)
                                check(count in 1..minOf(buffer.size.toLong(), size - offset).toInt()) { "电脑未返回完整音频" }
                                output.write(buffer, 0, count); offset += count
                                val checkpoint = offset - savedAt >= 4L * 1024 * 1024 || offset == size
                                progress(entry.id, offset, checkpoint)
                                if (checkpoint) savedAt = offset
                            }
                            output.fd.sync()
                        }
                        synchronized(this@OfflineDownloads) {
                            if (mutable.value.items.any { it.id == entry.id && it.status == OfflineDownloadStatus.DOWNLOADING }) {
                                Files.move(part.toPath(), file(entry.id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                                update(entry.id) { it.copy(status = OfflineDownloadStatus.READY, bytes = size) }
                            }
                        }
                    }
                } catch (cancel: CancellationException) {
                    update(entry.id) { if (it.status == OfflineDownloadStatus.DOWNLOADING) it.copy(status = OfflineDownloadStatus.PAUSED) else it }
                    throw cancel
                } catch (error: Exception) {
                    update(entry.id) { if (it.status in setOf(OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.QUEUED))
                        it.copy(status = OfflineDownloadStatus.FAILED, error = error.message ?: "下载失败，请重试") else it }
                } finally {
                    active = null
                    if (mutable.value.items.none { it.id == entry.id }) { partial(entry.id).delete(); file(entry.id).delete() }
                }
            }
        } finally { processing.set(false) }
    }
}
