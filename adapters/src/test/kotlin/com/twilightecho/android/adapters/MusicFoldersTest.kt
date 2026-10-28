package com.twilightecho.android.adapters

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CueRange
import com.twilightecho.android.coreclient.CueSkip
import com.twilightecho.android.coreclient.CueSource
import com.twilightecho.android.coreclient.cueTrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicFoldersTest {
    private class FakeReader(
        val texts: Map<String, String>,
        val headers: Map<String, AudioHeader>,
        val tags: Map<String, FileTags> = emptyMap()
    ) : FolderReader {
        val described = mutableListOf<String>()
        override fun uri(file: ListedFile) = "content://tree/document/${file.documentId}"
        override fun bytes(file: ListedFile) = texts.getValue(file.name).toByteArray()
        override fun header(file: ListedFile) = headers[file.name]
        override fun tags(file: ListedFile) = tags[file.name].also { described += file.name }
    }

    private fun file(name: String) = ListedFile("primary:Music/CueTest/$name", name, size = 1000, lastModified = 1_700_000_000_000)

    @Test
    fun `MediaStore rows and folder documents name a file the same way`() {
        assertEquals("primary:music/cuetest/album.flac", StorageLocation.ofMediaStore("external_primary", "Music/CueTest/", "Album.flac"))
        assertEquals(StorageLocation.ofDocument("primary:Music/CueTest/Album.flac"),
            StorageLocation.ofMediaStore("external_primary", "Music/CueTest/", "Album.flac"))
        assertEquals(StorageLocation.ofDocument("1A2B-3C4D:Music/a.flac"), StorageLocation.ofMediaStore("1a2b-3c4d", "Music/", "a.flac"))
        assertEquals("primary:a.flac", StorageLocation.ofMediaStore("external_primary", "", "a.flac"))
        assertNull(StorageLocation.ofMediaStore(null, "Music/", "a.flac"))
        assertEquals("primary:music/cuetest/album.flac", StorageLocation.ofPath("/storage/emulated/0/Music/CueTest/Album.flac"))
        assertEquals("1a2b-3c4d:music/a.flac", StorageLocation.ofPath("/storage/1A2B-3C4D/Music/a.flac"))
        assertNull(StorageLocation.ofPath("/data/media/0/a.flac"))
    }

    @Test
    fun `a folder splits its CUE images, adds files MediaStore missed and lends covers to the rest`() {
        val album = CoreTrackView("mediastore-1", title = "Album", artist = "File Artist", album = "File Album", albumId = "9",
            source = "content://media/external/audio/media/1", duration = 599.999, format = "FLAC")
        val single = CoreTrackView("mediastore-2", title = "Single", source = "content://media/external/audio/media/2", duration = 200.0)
        val elsewhere = CoreTrackView("mediastore-3", title = "Elsewhere", source = "content://media/external/audio/media/3", duration = 90.0)
        val mediaStore = mapOf("primary:music/cuetest/album.flac" to album, "primary:music/cuetest/single.mp3" to single)
        val reader = FakeReader(
            texts = mapOf(
                "Album.cue" to "PERFORMER \"乐团\"\nTITLE \"夜曲集\"\nFILE \"Album.flac\" WAVE\nTRACK 01 AUDIO\nTITLE \"第一首\"\n" +
                    "INDEX 01 00:00:00\nTRACK 02 AUDIO\nTITLE \"第二首\"\nINDEX 01 04:00:00",
                "Live.cue" to "FILE \"live.APE\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 10:00:00",
                "Broken.cue" to "FILE \"Gone.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00"
            ),
            headers = mapOf("Album.flac" to AudioHeader(600.0, 44100, 16), "Live.ape" to AudioHeader(1200.5, 44100, 16),
                "Extra.wv" to AudioHeader(30.0, 44100, null)),
            tags = mapOf("Extra.wv" to FileTags(title = "Extra Song", artist = "Someone", trackNumber = 3))
        )
        val names = listOf("Album.flac", "Album.cue", "Album.jpg", "cover.jpg", "Live.ape", "Live.cue", "Single.mp3", "Extra.wv",
            "Clip.mp4", "._Album.flac", "NoLength.ape", "Broken.cue")
        val songs = MusicFolders.songs(ListedFolder("CueTest", names.map(::file)), mediaStore, reader)

        // MediaStore's own files are not read again; the others are, except a lone MP4.
        assertEquals(listOf("Live.ape", "Extra.wv", "NoLength.ape"), reader.described)
        assertEquals(setOf("mediastore-1"), songs.replaced)
        assertEquals(mapOf("mediastore-2" to "content://tree/document/primary:Music/CueTest/cover.jpg"), songs.covers)
        assertEquals(listOf(CueSkip("CueTest/Broken.cue", "找不到引用的音频“Gone.flac”")), songs.skipped)

        val (first, second) = songs.tracks.filter { it.album == "夜曲集" }
        assertEquals(cueTrackId("primary:Music/CueTest/Album.flac", "primary:Music/CueTest/Album.cue", 2), second.id)
        assertEquals(listOf("第一首", "第二首"), listOf(first.title, second.title))
        // The file's header length, not MediaStore's rounded one, ends the last song.
        assertEquals("content://tree/document/primary:Music/CueTest/Album.flac" to CueRange(240.0, 600.0),
            CueSource.decode(second.source!!))
        assertEquals("content://tree/document/primary:Music/CueTest/Album.jpg", second.cover)
        assertNull("The sheet's title names the album, not MediaStore's", second.albumId)

        val live = songs.tracks.filter { it.title.endsWith(". Live") }
        assertEquals(listOf("01. Live", "02. Live"), live.map { it.title })
        assertEquals(listOf(600.0, 600.5), live.map { it.duration })
        assertTrue(live.all { it.album == "CueTest" && it.artist == "Unknown Artist" && it.format == "APE" })
        assertEquals("content://tree/document/primary:Music/CueTest/cover.jpg", live[0].cover)

        val extra = songs.tracks.single { it.title == "Extra Song" }
        assertEquals(MusicFolders.folderTrackId("primary:Music/CueTest/Extra.wv"), extra.id)
        assertEquals("content://tree/document/primary:Music/CueTest/Extra.wv", extra.source)
        assertEquals(30.0, extra.duration, 0.0)
        assertEquals(44100, extra.sampleRate)
        assertEquals("CueTest", extra.album)
        assertEquals(3, extra.trackNumber)
        assertEquals(1_700_000_000_000, extra.addedAt)
        assertEquals(5, songs.tracks.size)

        // The library keeps MediaStore's other songs, drops the split file and adds the folder's songs.
        val library = songs.over(listOf(album, single, elsewhere))
        assertEquals(listOf("mediastore-2", "mediastore-3") + songs.tracks.map { it.id }, library.map { it.id })
        assertEquals("content://tree/document/primary:Music/CueTest/cover.jpg", library[0].cover)
        assertNull(library[1].cover)
    }

    @Test
    fun `a MediaStore file whose sheet does not fit stays one song`() {
        val album = CoreTrackView("mediastore-1", title = "Album", source = "content://media/external/audio/media/1", duration = 100.0)
        val reader = FakeReader(mapOf("Album.cue" to "FILE Album.flac WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 05:00:00"),
            emptyMap())
        val songs = MusicFolders.songs(ListedFolder("CueTest", listOf(file("Album.flac"), file("Album.cue"), file("folder.png"))),
            mapOf("primary:music/cuetest/album.flac" to album), reader)
        assertEquals(emptyList<CoreTrackView>(), songs.tracks)
        assertEquals(emptySet<String>(), songs.replaced)
        assertEquals(mapOf("mediastore-1" to "content://tree/document/primary:Music/CueTest/folder.png"), songs.covers)
        assertEquals(listOf(CueSkip("CueTest/Album.cue", "第 1 轨超出音频文件长度")), songs.skipped)
        assertEquals(emptyList<String>(), reader.described)
    }
}
