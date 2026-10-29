package com.twilightecho.android.plugins

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

/** Expected symbols come from `QRCode.create([{ data, mode: 'byte' }], { errorCorrectionLevel: 'M' })`, qrcode 1.5.4. */
class QrCodeTest {
    private fun rows(matrix: QrMatrix) = (0 until matrix.size).joinToString("\n") { row ->
        (0 until matrix.size).joinToString("") { col -> if (matrix[row, col]) "#" else "." }
    }
    private fun fingerprint(matrix: QrMatrix) =
        digest("SHA-256", (0 until matrix.size * matrix.size).joinToString("") { if (matrix[it / matrix.size, it % matrix.size]) "1" else "0" }.utf8()).hex()

    @Test fun smallSymbolMatchesModuleForModule() {
        assertEquals("""
            #######..##...#######
            #.....#.##....#.....#
            #.###.#..#.##.#.###.#
            #.###.#...##..#.###.#
            #.###.#.##..#.#.###.#
            #.....#.....#.#.....#
            #######.#.#.#.#######
            ..........###........
            #.#.#.#..#.#....#..#.
            ..#.##....#...#....##
            .#.#..#.###.#...#####
            ##..#.........#....#.
            .##.#.##..#.#.#.#....
            ........####.#.#..###
            #######...##.###..###
            #.....#...####.##....
            #.###.#.#.##.###...##
            #.###.#..#....##..##.
            #.###.#.###.#...#.#.#
            #.....#..#....#.#..#.
            #######.###.#.##...##
        """.trimIndent(), rows(qrEncode("hello")))
    }

    @Test fun versionMaskAndInterleavingMatchAcrossSizes() {
        val bilibili = "https://passport.bilibili.com/h5-app/passport/login/scan?navhide=1&qrcode_key=0123456789abcdef0123456789abcdef&from="
        val qq = "https://y.qq.com/ptqrlogin?u1=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump&ptqrtoken=1234567890&ptredirect=0&h=1&t=1&g=1" +
            "&from_ui=1&ptlang=2052&action=0-0-1700000000000&js_ver=23111510&js_type=1&login_sig=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNO" +
            "PQRSTUVWXYZ&pt_uistyle=40&aid=716027609&daid=383&pt_3rd_aid=100497308"
        // text, size, fingerprint with the chosen mask, a forced mask and its fingerprint
        val cases = listOf(
            listOf("hello", 21, "99ccedcf0d82e92a63894c8b42e405a3ac50eafa33ee2ce68fa895813fdcb386", 3, "dfe75c4cc7ff735bed162390e7b29f91e0fd54be1949e70b531716db80d3f52b"),
            listOf(bilibili, 45, "491908f2afdc9fdc4a992f2e8dfdf3e3ec3feb0b23c1c39e9e664cf128760e1b", 1, "e1ede637c492cad4f9eec9d183cc88a7c77d442f0e9b2486f446eb51ca7d749e"),
            listOf(qq, 69, "6ebca01ae1183978e2d51aedc1126dc7d1e99e9e0077b9f95ea8b8cdacb4c435", 5, "fca30d9675e8b9fb7b6ad9c9d96cbbc291be6b8632a0d849b070dfef5362d670"),
            listOf("汽水音乐扫码登录 " + "x".repeat(40), 37, "971e8702f50fbe643653c01d396b116e78ab15b97bda9046f4070e0388e7cb47", 3, "3c41c3b038c70479bdb1ecce67a8a4988e112f7896e56e0250a873132e0aeed6"),
            listOf("q".repeat(600), 93, "08af9464660b176041e592a888659a20075eeb7895eeaee36da3238324b34b7d", 7, "a109b34513015476df9e9866fdc09f317b293e1b5a6cea989d68617cc0901ea7"))
        assertEquals(116, bilibili.length); assertEquals(330, qq.length)
        for ((text, size, chosen, mask, forced) in cases) {
            val matrix = qrEncode(text as String)
            assertEquals(text, size, matrix.size)
            assertEquals(text, chosen, fingerprint(matrix))
            assertEquals(text, forced, fingerprint(qrEncode(text, mask as Int)))
        }
    }

    @Test fun largestByteModeSymbolIsVersion40() {
        assertEquals(177, qrEncode("a".repeat(2331)).size)
        assertThrows(IllegalArgumentException::class.java) { qrEncode("a".repeat(2332)) }
    }

    @Test fun pngHasAQuietZoneAndDarkFinderModules() {
        val url = qrPngDataUrl("hello")
        assertTrue(url.startsWith("data:image/png;base64,"))
        val png = ByteBuffer.wrap(unb64(url.substringAfter(',')))
        assertEquals("89504e470d0a1a0a", ByteArray(8).also { png.get(it) }.hex())
        var width = 0; var height = 0; val idat = java.io.ByteArrayOutputStream()
        while (png.hasRemaining()) {
            val body = ByteArray(png.int); val type = ByteArray(4).also { png.get(it) }
            png.get(body)
            assertEquals(CRC32().apply { update(type); update(body) }.value.toInt(), png.int)
            when (String(type, Charsets.US_ASCII)) {
                "IHDR" -> ByteBuffer.wrap(body).run { width = int; height = int; assertEquals(1, get().toInt()); assertEquals(0, get().toInt()) }
                "IDAT" -> idat.write(body)
            }
        }
        val raw = InflaterInputStream(idat.toByteArray().inputStream()).readBytes()
        val stride = (width + 7) / 8 + 1
        assertEquals(stride * height, raw.size)
        fun white(x: Int, y: Int) = (raw[y * stride + 1 + x / 8].toInt() shr (7 - x % 8)) and 1 == 1
        val scale = 512 / 29
        assertEquals(29 * scale, width); assertEquals(width, height)
        assertTrue(white(scale, scale)) // quiet zone
        assertFalse(white(4 * scale, 4 * scale)) // finder corner
        assertTrue(white(5 * scale, 5 * scale)) // finder ring gap
    }
}
