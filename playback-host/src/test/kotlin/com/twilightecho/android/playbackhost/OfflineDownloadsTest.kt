package com.twilightecho.android.playbackhost

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class OfflineDownloadsTest {
    private val reference = DesktopMediaReference("server", "library", "source")
    private val track = CoreTrackView("remote", title = "Offline fixture", source = "remote-track:opaque", duration = 20.0)
    private val bytes = ByteArray(700_000) { (it % 251).toByte() }
    private fun reader(version: String = "v1", onRead: (Long) -> Unit = {}): OfflineDownloadReader = object : OfflineDownloadReader {
        override fun size() = bytes.size.toLong()
        override fun version() = version
        override fun readAt(position: Long, data: ByteArray): Int {
            onRead(position)
            val count = minOf(data.size, bytes.size - position.toInt())
            bytes.copyInto(data, 0, position.toInt(), position.toInt() + count)
            return count
        }
        override fun close() = Unit
    }
    @Test fun interruptedTransferPersistsVersionAndResumesWithoutExposingPartialAudio() = runBlocking {
        val directory = Files.createTempDirectory("offline-resume-").toFile()
        try {
            var allowed = true
            val first = OfflineDownloads(directory, { reader(onRead = { allowed = false }) }, { 2_000_000 }, { check(allowed) { "metered" } })
            first.enqueue(reference, track, "Desk"); first.runPending()
            val paused = first.state.value.items.single()
            assertEquals(OfflineDownloadStatus.FAILED, paused.status)
            assertEquals("v1", paused.reference.sourceVersion)
            assertTrue(paused.bytes in 1 until bytes.size.toLong())
            assertNull(first.track(paused))
            val offsets = mutableListOf<Long>()
            var received: DesktopMediaReference? = null
            val restored = OfflineDownloads(directory, { received = it; reader(onRead = { offsets += it }) }, { 2_000_000 }, {})
            restored.resume(paused.id); restored.runPending()
            assertEquals("v1", received!!.sourceVersion)
            assertEquals(paused.bytes, offsets.first())
            val ready = restored.state.value.items.single()
            assertEquals(OfflineDownloadStatus.READY, ready.status)
            assertArrayEquals(bytes, File(restored.track(ready)!!.source!!).readBytes())
            assertEquals("Offline fixture", restored.find("server", "library", "source")!!.title)
            assertNull(restored.find("server", "replacement-library", "source"))
            restored.remove(ready.id)
            assertTrue(restored.state.value.items.isEmpty())
            assertFalse(File(directory, "${ready.id}.media").exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun changedSourceAndStorageQuotaDoNotPublishMixedOrPartialFiles() = runBlocking {
        val directory = Files.createTempDirectory("offline-version-").toFile()
        try {
            var allowed = true
            val first = OfflineDownloads(directory, { reader(onRead = { allowed = false }) }, { 2_000_000 }, { check(allowed) })
            first.enqueue(reference, track, "Desk"); first.runPending()
            val row = first.state.value.items.single()
            var read = false
            val changed = OfflineDownloads(directory, { reader("v2", onRead = { read = true }) }, { 2_000_000 }, {})
            changed.resume(row.id); changed.runPending()
            assertFalse(read); assertNull(changed.find("server", "library", "source"))
            assertTrue(changed.state.value.items.single().error!!.contains("改变"))
            changed.remove(row.id)
            val limited = OfflineDownloads(directory, { reader() }, { 10 }, {})
            limited.enqueue(reference, track, "Desk"); limited.runPending()
            assertEquals(OfflineDownloadStatus.FAILED, limited.state.value.items.single().status)
            assertTrue(limited.tracks().isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun pauseAndDeleteDuringReadNeverResurrectACompletedDownload() = runBlocking {
        val directory = Files.createTempDirectory("offline-cancel-").toFile()
        try {
            lateinit var store: OfflineDownloads
            store = OfflineDownloads(directory, { reader(onRead = { store.remove(store.state.value.items.single().id) }) }, { 2_000_000 }, {})
            store.enqueue(reference, track, "Desk"); store.runPending()
            assertTrue(store.state.value.items.isEmpty())
            assertTrue(directory.listFiles()!!.none { it.extension in setOf("part", "media") })
        } finally { directory.deleteRecursively() }
    }
    @Test fun completedRenameIsRecoveredAndCorruptMetadataIsNotOverwritten() {
        val directory = Files.createTempDirectory("offline-recovery-").toFile()
        try {
            val row = OfflineDownload(UUID.randomUUID().toString(), reference.copy(sourceVersion = "v1"), track, "Desk", OfflineDownloadStatus.DOWNLOADING, bytes.size.toLong(), bytes.size.toLong())
            val file = File(directory, "downloads.json")
            file.writeText(DefaultCoreClient.DefaultJson.encodeToString(listOf(row)))
            File(directory, "${row.id}.media").writeBytes(bytes)
            val restored = OfflineDownloads(directory, { error("offline") }, { 2_000_000 }, {})
            assertEquals(OfflineDownloadStatus.READY, restored.state.value.items.single().status)
            file.writeText("broken metadata")
            val broken = OfflineDownloads(directory, { error("offline") }, { 2_000_000 }, {})
            assertNotNull(broken.state.value.error)
            assertTrue(runCatching { broken.enqueue(reference, track, "Desk") }.isFailure)
            assertEquals("broken metadata", file.readText())
        } finally { directory.deleteRecursively() }
    }
}
