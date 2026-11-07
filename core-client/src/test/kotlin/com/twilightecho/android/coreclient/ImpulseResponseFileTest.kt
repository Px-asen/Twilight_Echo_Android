package com.twilightecho.android.coreclient

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** 32 stereo PCM16 frames: left +0.5, right -0.25, in four lossless containers. */
internal object ImpulseResponseFixtures {
    fun all(): Map<String, ByteArray> = linkedMapOf(
        "wav" to wave(), "flac" to flac(), "aiff" to aiff(false), "aifc" to aiff(true)
    )

    private fun wave() = ByteArrayOutputStream().also { out ->
        fun int(value: Int, count: Int) = repeat(count) { out.write(value shr (8 * it)) }
        out.write("RIFF".toByteArray()); int(164, 4); out.write("WAVEfmt ".toByteArray())
        int(16, 4); int(1, 2); int(2, 2); int(48000, 4); int(192000, 4); int(4, 2); int(16, 2)
        out.write("data".toByteArray()); int(128, 4)
        repeat(32) { int(16384, 2); int(-8192, 2) }
    }.toByteArray()

    private fun flac() = ("664c614380000022002000200000000000000bb802f00000002000000000000000000000000000000000" +
        "fff86018001f4400400000e000164f").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun aiff(littleEndian: Boolean): ByteArray {
        fun ByteArrayOutputStream.int(value: Int, count: Int) = repeat(count) { write(value shr (8 * (count - it - 1))) }
        val chunks = ByteArrayOutputStream().apply {
            if (littleEndian) { write("FVER".toByteArray()); int(4, 4); int(0xa2805140.toInt(), 4) }
            write("COMM".toByteArray()); int(if (littleEndian) 24 else 18, 4)
            int(2, 2); int(32, 4); int(16, 2)
            write(byteArrayOf(0x40, 0x0e, 0xbb.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0))
            if (littleEndian) { write("sowt".toByteArray()); int(0, 2) }
            write("SSND".toByteArray()); int(136, 4); int(0, 4); int(0, 4)
            repeat(32) {
                if (littleEndian) write(byteArrayOf(0, 0x40, 0, 0xe0.toByte()))
                else { int(16384, 2); int(-8192, 2) }
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("FORM".toByteArray()); int(chunks.size + 4, 4)
            write((if (littleEndian) "AIFC" else "AIFF").toByteArray()); write(chunks)
        }.toByteArray()
    }

    fun config(path: String) = CoreEqConfigView(dsp = CoreDspConfig(nodes = listOf(
        CoreDspNode("ir", "convolver", params = buildJsonObject { put("impulseResponsePath", path) })
    )))

    fun scene(id: String, config: CoreEqConfigView) = CoreDspScene(id, id,
        JsonObject(DefaultCoreClient.DefaultJson.encodeToJsonElement(CoreEqConfigView.serializer(), config).jsonObject - "scenes"))
}

class ImpulseResponseFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `document copies use content format and preserve bytes even with short reads`() {
        for ((name, bytes) in ImpulseResponseFixtures.all()) {
            val input = object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
            }
            val copied = copyImpulseResponse(input, folder.newFolder(name))
            assertEquals(if (name == "aifc") "aiff" else name, copied.extension)
            assertArrayEquals(bytes, copied.readBytes())
            val described = describeImpulseFile(copied)!!
            assertEquals(48000, described.sampleRate)
            assertEquals(2, described.channels)
            assertEquals(bytes.size.toLong(), described.byteSize)
            assertTrue(described.archivePath.endsWith(".${copied.extension}"))
        }
    }

    @Test fun `copy errors leave no partial impulse and unsupported input creates no file`() {
        val directory = folder.newFolder()
        assertThrows(IllegalStateException::class.java) { copyImpulseResponse(ByteArrayInputStream("ID3 audio".toByteArray()), directory) }
        val wave = ImpulseResponseFixtures.all().getValue("wav")
        val input = object : InputStream() {
            var at = 0
            override fun read(): Int = if (at < 12) wave[at++].toInt() and 0xff else throw IOException("read failed")
        }
        assertThrows(IOException::class.java) { copyImpulseResponse(input, directory) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `all impulse containers survive the real archive import and reexport path`() {
        val sources = ImpulseResponseFixtures.all().mapValues { (name, bytes) -> folder.newFile("$name.bin").apply { writeBytes(bytes) } }
        val scenes = sources.map { (name, file) -> ImpulseResponseFixtures.scene(name, ImpulseResponseFixtures.config(file.absolutePath)) }
        val exported = DspScenePackage.export(CoreEqConfigView(scenes = scenes), { describeImpulseFile(File(it)) })
        assertEquals(4, exported.files.size)
        assertEquals(setOf("audio/wav", "audio/flac", "audio/aiff"),
            exported.manifest["assets"]!!.jsonArray.map { it.jsonObject["asset"]!!.jsonObject["mediaType"]!!.jsonPrimitive.content }.toSet())
        val bytes = ByteArrayOutputStream().also { DspPackageArchive.write(it, exported.manifest, exported.files) }.toByteArray()
        val contents = DspPackageArchive.read(ByteArrayInputStream(bytes), folder.newFolder("extracted"))
        val imported = DspPackageArchive.importScenes(contents, CoreEqConfigView(), folder.newFolder("owned"))
        assertTrue(imported.rejected.isEmpty())
        for (scene in imported.scenes) {
            assertTrue(scene.notes.toString(), scene.notes.isEmpty())
            val node = CoreEqConfigView().withScene(scene.scene).dsp.nodes.single()
            assertTrue(node.enabled)
            val file = File(node.params["impulseResponsePath"]!!.jsonPrimitive.content)
            val source = sources.getValue(scene.scene.id)
            assertArrayEquals(source.readBytes(), file.readBytes())
            assertEquals(describeImpulseFile(source)!!.assetId, describeImpulseFile(file)!!.assetId)
            assertEquals(if (scene.scene.id == "aifc") "aiff" else scene.scene.id, file.extension)
        }
        val reexported = DspScenePackage.export(CoreEqConfigView(scenes = imported.scenes.map { it.scene }), { describeImpulseFile(File(it)) })
        assertEquals(exported.files.keys, reexported.files.keys)
    }

    @Test fun `an asset with changed bytes is not installed or activated`() {
        val file = folder.newFile("ir.flac").apply { writeBytes(ImpulseResponseFixtures.all().getValue("flac")) }
        val config = ImpulseResponseFixtures.config(file.absolutePath)
        val exported = DspScenePackage.export(config.copy(scenes = listOf(ImpulseResponseFixtures.scene("flac", config))), { describeImpulseFile(File(it)) })
        file.appendBytes(byteArrayOf(1))
        val directory = folder.newFolder("owned")
        val imported = DspPackageArchive.importScenes(DspPackageArchive.Contents(exported.manifest, exported.files), CoreEqConfigView(), directory)
        assertFalse(CoreEqConfigView().withScene(imported.scenes.single().scene).dsp.nodes.single().enabled)
        assertTrue(imported.scenes.single().notes.isNotEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
