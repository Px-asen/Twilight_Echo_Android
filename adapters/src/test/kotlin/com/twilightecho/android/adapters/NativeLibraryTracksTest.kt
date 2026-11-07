package com.twilightecho.android.adapters

import com.twilightecho.android.coreclient.AudioFileMetadata
import com.twilightecho.android.coreclient.SacdTrackSource
import org.junit.Assert.*
import org.junit.Test

class NativeLibraryTracksTest {
    private class Reader(val metadata: Map<String, AudioFileMetadata>, val failures: Map<String, String> = emptyMap(),
        val headers: Map<String, AudioHeader> = emptyMap(), val cues: Map<String, String> = emptyMap()) : FolderReader {
        val readFiles = mutableListOf<String>()
        override fun uri(file: ListedFile) = "content://documents/opaque-${file.documentId}?grant=keep"
        override fun nativeMetadata(file: ListedFile): AudioFileMetadata {
            readFiles += file.name
            failures[file.name]?.let { throw java.io.IOException(it) }
            return metadata.getValue(file.name)
        }
        override fun bytes(file: ListedFile): ByteArray = cues.getValue(file.name).toByteArray()
        override fun header(file: ListedFile): AudioHeader? = headers.getValue(file.name)
        override fun tags(file: ListedFile): FileTags? = error("Use the native DSD tags")
    }
    private fun file(name: String) = ListedFile(name, name, 400_000_000L, 123L)
    private val base = AudioFileMetadata(title = "Moonrise", artist = "Twilight", album = "Night", year = "2026",
        duration = 123.25, sampleRate = 2_822_400, bitDepth = 1, channelCount = 2, codec = "dsd", isDsd = true, dsdRate = 64)

    @Test fun `native file and SACD identities include provider while retaining logical selectors`() {
        val file = file("Disc.iso")
        val source = "content://example.documents/tree/root/document/disc"
        val folder = ListedFolder("Music", listOf(file))
        val sources = listOf(source, SacdTrackSource(source, "stereo", 1).encode(),
            SacdTrackSource(source, "stereo", 2).encode(), SacdTrackSource(source, "multichannel", 1).encode())
        for (uri in sources) {
            val old = nativeLibraryTrack(file, folder, uri, base, null)
            val qualified = nativeLibraryTrack(file, folder.copy(authority = "example.documents"), uri, base, null)
            assertEquals(old.id + "@example.documents", qualified.id)
            assertEquals(old.source, qualified.source)
            assertEquals(old.id, nativeLibraryTrack(file, folder.copy(authority = "com.android.externalstorage.documents"), uri, base, null).id)
        }
        assertEquals(4, sources.map { nativeLibraryTrack(file, folder.copy(authority = "example.documents"), it, base, null).id }.distinct().size)
    }

    @Test
    fun `SAF DSF and DFF keep native tags exact duration and one-bit source rate`() {
        val reader = Reader(mapOf("song.dsf" to base, "song.dff" to base.copy(codec = "dst", title = "Compressed")))
        val songs = MusicFolders.songs(ListedFolder("Music/HiFi", listOf(file("song.dsf"), file("song.dff"))), reader = reader)
        assertEquals(listOf("Moonrise", "Compressed"), songs.tracks.map { it.title })
        assertEquals(listOf("song.dsf", "song.dff"), reader.readFiles)
        assertTrue(songs.tracks.all { it.sampleRate == 2_822_400 && it.bitDepth == 1 && it.duration == 123.25 })
        assertEquals(5644.8, songs.tracks.first().bitrate!!, 0.0)
        assertNull(songs.tracks.last().bitrate) // An ISO/file size is not a per-track DST bitrate.
        assertTrue(songs.tracks.all { it.album == "Night" && it.releaseDate == "2026" })
    }

    @Test
    fun `SACD stereo and multichannel virtual tracks have separate stable identities and no CUE ranges`() {
        val iso = file("Disc.iso")
        val reader = Reader(emptyMap())
        val uri = reader.uri(iso)
        val tracks = listOf(base.copy(source = SacdTrackSource(uri, "stereo", 1).encode()),
            base.copy(source = SacdTrackSource(uri, "multichannel", 1).encode(), channelCount = 6),
            base.copy(source = SacdTrackSource(uri, "stereo", 2).encode(), title = "Unavailable", playable = false,
                reasonCode = "dst_dsd_provider_unavailable", comment = "DST 解码器不可用"))
        val inputs = Reader(mapOf("Disc.iso" to AudioFileMetadata(isoTracks = tracks)))
        val folder = ListedFolder("Music/HiFi", listOf(iso, file("cover.jpg")))
        val songs = MusicFolders.songs(folder, reader = inputs)
        val again = MusicFolders.songs(folder, reader = inputs)
        assertEquals(3, songs.tracks.map { it.id }.distinct().size)
        assertEquals(songs.tracks.map { it.id }, again.tracks.map { it.id })
        assertTrue(songs.tracks.all { it.id.startsWith("local:sacd:") && it.cueRange == null && it.format == "SACD ISO" })
        assertTrue(songs.tracks.all { it.cover == inputs.uri(file("cover.jpg")) })
        assertEquals(tracks.map { it.source }, songs.tracks.map { it.source })
        assertEquals("DST 解码器不可用", songs.tracks.last().unavailableReason)
        assertTrue(songs.skipped.isEmpty() && songs.skippedFiles.isEmpty())
    }

    @Test
    fun `unreadable DSD and non SACD ISO are reported separately from CUE without a fake container song`() {
        val reader = Reader(mapOf("data.iso" to AudioFileMetadata()), mapOf("broken.dsf" to "授权已失效"))
        val songs = MusicFolders.songs(ListedFolder("Music/HiFi", listOf(file("data.iso"), file("broken.dsf"))), reader = reader)
        assertTrue(songs.tracks.isEmpty())
        assertTrue(songs.skipped.isEmpty())
        assertEquals(listOf(MusicFileSkip("Music/HiFi/data.iso", "未找到 SACD 音轨"),
            MusicFileSkip("Music/HiFi/broken.dsf", "授权已失效")), songs.skippedFiles)
        assertEquals(songs.skippedFiles, FolderSongs.of(listOf(songs)).skippedFiles)
    }

    @Test
    fun `scan cancellation is not converted to an unreadable file`() {
        for (name in listOf("Disc.iso", "song.dsf", "song.wv")) {
            val cancelled = java.util.concurrent.CancellationException("scan stopped")
            val reader = object : FolderReader by Reader(emptyMap()) {
                override fun nativeMetadata(file: ListedFile): AudioFileMetadata = throw cancelled
            }
            val result = runCatching { MusicFolders.songs(ListedFolder("Music", listOf(file(name))), reader = reader) }
            assertSame(cancelled, result.exceptionOrNull())
        }
    }

    @Test
    fun `WavPack uses native tags but header-valid bits and exact CUE boundaries`() {
        val image = file("Disc.wv")
        val metadata = base.copy(isDsd = false, bitDepth = 24, sampleRate = 96000, codec = "wavpack", duration = 30.0,
            albumArtist = "Album Artist", trackNumber = "3/9", discNumber = "2/2", genre = "Jazz", bitrate = 0)
        val reader = Reader(mapOf("Disc.wv" to metadata), headers = mapOf("Disc.wv" to AudioHeader(30.125, 96000, 20)))
        val folder = ListedFolder("Music/HiFi", listOf(image, file("cover.jpg")))
        val song = MusicFolders.songs(folder, reader = reader).tracks.single()
        assertEquals(MusicFolders.folderTrackId(image.documentId), song.id)
        assertEquals("Moonrise", song.title)
        assertEquals("Album Artist", song.albumArtist)
        assertEquals("Jazz", song.genre)
        assertEquals(3, song.trackNumber)
        assertEquals(2, song.discNumber)
        assertEquals("WV", song.format)
        assertEquals(20, song.bitDepth)
        assertEquals(96000, song.sampleRate)
        assertEquals(30.125, song.duration, 0.0)
        assertEquals(image.size * 8 / 30.125 / 1000, song.bitrate!!, 0.0)
        val cueReader = Reader(reader.metadata, headers = reader.headers, cues = mapOf("Disc.cue" to
            "FILE Disc.wv WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 00:10:00"))
        val split = MusicFolders.songs(folder.copy(files = folder.files + file("Disc.cue")), reader = cueReader)
        assertEquals(listOf(10.0, 20.125), split.tracks.map { it.duration })
        assertTrue(split.tracks.all { it.bitDepth == 20 && it.album == "Night" && it.cover == reader.uri(file("cover.jpg")) })
        assertEquals(com.twilightecho.android.coreclient.CueRange(10.0, 30.125), split.tracks.last().cueRange)
        assertTrue(split.skipped.isEmpty() && split.skippedFiles.isEmpty())
    }

    @Test
    fun `WavPack DSD keeps its one-bit clock without inventing an uncompressed bitrate`() {
        val name = "DSD.wv"
        val reader = Reader(mapOf(name to base.copy(codec = "wavpack", bitrate = 0)),
            headers = mapOf(name to AudioHeader(123.25, 2_822_400, 1, isDsd = true)))
        val knownSize = MusicFolders.songs(ListedFolder("Music", listOf(file(name))), reader = reader).tracks.single()
        assertEquals("WavPack DSD", knownSize.format)
        assertEquals(1, knownSize.bitDepth)
        assertEquals(2_822_400, knownSize.sampleRate)
        assertEquals(400_000_000.0 * 8 / 123.25 / 1000, knownSize.bitrate!!, 0.0)
        val unknownSize = MusicFolders.songs(ListedFolder("Music", listOf(file(name).copy(size = -1))), reader = reader).tracks.single()
        assertNull(unknownSize.bitrate)
    }
}
