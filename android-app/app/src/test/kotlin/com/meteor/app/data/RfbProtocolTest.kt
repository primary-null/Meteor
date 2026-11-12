package com.meteor.app.data

import com.meteor.app.data.RfbScript.Companion.argb
import com.meteor.app.data.RfbScript.Companion.cpixel
import com.meteor.app.data.RfbScript.Companion.int
import com.meteor.app.data.RfbScript.Companion.pixel
import com.meteor.app.data.RfbScript.Companion.rect
import com.meteor.app.data.RfbScript.Companion.short
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException

/** Handshake, client messages and every decoder, against scripted server bytes (no sockets). */
class RfbProtocolTest {
    private fun hex(text: String) = text.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun client(script: RfbScript, choppy: Boolean = false, encodings: List<Int> = RfbEncoding.DEFAULT): Pair<RfbProtocol, ByteArrayOutputStream> {
        val out = ByteArrayOutputStream()
        val input = if (choppy) ChoppyInputStream(script.bytes()) else script.bytes().inputStream()
        return RfbProtocol(input, out, encodings) to out
    }
    private fun connected(script: RfbScript, choppy: Boolean = false): RfbProtocol = client(script, choppy).first.also { it.handshake() }
    private fun RfbProtocol.at(x: Int, y: Int) = framebuffer.pixels[y * framebuffer.width + x]
    private fun failure(block: () -> Unit): ConsoleError = assertThrows(ConsoleError::class.java) { block() }

    // ---- handshake and client messages ----

    @Test fun handshake38SendsVersionSecurityInitFormatEncodingsAndFirstRequest() {
        val (rfb, out) = client(RfbScript(640, 480, name = "Guest"))
        assertEquals(RfbServerInfo(640, 480, "Guest"), rfb.handshake())
        val expected = "RFB 003.008\n".toByteArray() + hex("01") + hex("01") +
            hex("00 00 00 00 20 18 00 01 00 ff 00 ff 00 ff 10 08 00 00 00 00") +
            hex("02 00 00 05 00 00 00 10 00 00 00 05 00 00 00 01 00 00 00 00 ff ff ff 21") +
            hex("03 00 00 00 00 00 02 80 01 e0")
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test fun handshake33And37AnswerWith33AndReadAFourByteSecurityType() {
        for (version in listOf("RFB 003.003\n", "RFB 003.007\n")) {
            val (rfb, out) = client(RfbScript(8, 8, version = version))
            rfb.handshake()
            val bytes = out.toByteArray()
            assertEquals("RFB 003.003\n", String(bytes, 0, 12, Charsets.US_ASCII))
            assertEquals("The next byte is ClientInit, with no security selection", 1, bytes[12].toInt())
            assertEquals(12 + 1 + 20 + 24 + 10, bytes.size)
        }
    }

    @Test fun pixelFormatAndEncodingMessagesAreExact() {
        assertArrayEquals(hex("00 00 00 00 20 18 00 01 00 ff 00 ff 00 ff 10 08 00 00 00 00"), RfbProtocol.pixelFormatMessage())
        assertArrayEquals(hex("02 00 00 02 00 00 00 01 00 00 00 00"), RfbProtocol.encodingsMessage(listOf(RfbEncoding.COPY_RECT, RfbEncoding.RAW)))
        assertArrayEquals(hex("02 00 00 03 00 00 00 01 00 00 00 00 ff ff ff 21"), RfbProtocol.encodingsMessage(RfbEncoding.SAFE))
        assertEquals(listOf(16, 5, 1, 0, -223), RfbEncoding.DEFAULT)
        assertArrayEquals(hex("03 01 00 00 00 00 03 20 02 58"), RfbProtocol.updateRequestMessage(true, 0, 0, 800, 600))
    }

    @Test fun keyAndPointerMessagesAndClamping() {
        val (rfb, out) = client(RfbScript(16, 16))
        rfb.handshake(); out.reset()
        rfb.key(KeySyms.RETURN, true); rfb.key(0x01000020, false)
        rfb.pointer(100, -5, RfbProtocol.LEFT or RfbProtocol.WHEEL_UP)
        assertArrayEquals(hex("04 01 00 00 00 00 ff 0d") + hex("04 00 00 00 01 00 00 20") + hex("05 09 00 0f 00 00"), out.toByteArray())
    }

    @Test fun rejectsAuthenticationTypesAndFailedSecurityResults() {
        assertEquals(ConsoleFailureKind.UNSUPPORTED, failure { RfbProtocol(RfbScript(security = 2).bytes().inputStream(), ByteArrayOutputStream()).handshake() }.kind)
        assertEquals(ConsoleFailureKind.UNSUPPORTED, failure { RfbProtocol(RfbScript(version = "RFB 003.003\n", security = 2).bytes().inputStream(), ByteArrayOutputStream()).handshake() }.kind)
        assertEquals(ConsoleFailureKind.AUTH, failure { RfbProtocol(RfbScript(securityResult = 1).bytes().inputStream(), ByteArrayOutputStream()).handshake() }.kind)
        assertEquals(ConsoleFailureKind.UNSUPPORTED, failure { RfbProtocol("RFB 004.001\n".toByteArray().inputStream(), ByteArrayOutputStream()).handshake() }.kind)
    }

    @Test fun rejectsAbsurdFramebufferSizes() {
        failure { RfbProtocol(RfbScript(5000, 2000).bytes().inputStream(), ByteArrayOutputStream()).handshake() }
        failure { RfbProtocol(RfbScript(0, 10).bytes().inputStream(), ByteArrayOutputStream()).handshake() }
        failure { RfbProtocol(RfbScript(65535, 100).bytes().inputStream(), ByteArrayOutputStream()).handshake() }
        assertEquals(4096, connected(RfbScript(4096, 2160)).framebuffer.width)
    }

    // ---- Raw and CopyRect ----

    @Test fun rawRectanglesDecodeToArgb() {
        val body = pixel(0x112233) + pixel(0x445566) + pixel(0x778899) + pixel(0xaabbcc)
        val rfb = connected(RfbScript(4, 4).update(rect(1, 1, 2, 2, RfbEncoding.RAW, body)))
        assertEquals(RfbEvent.UPDATE, rfb.readEvent())
        assertEquals(argb(0x112233), rfb.at(1, 1)); assertEquals(argb(0x445566), rfb.at(2, 1))
        assertEquals(argb(0x778899), rfb.at(1, 2)); assertEquals(argb(0xaabbcc), rfb.at(2, 2))
        assertEquals(0, rfb.at(0, 0))
        val delta = rfb.framebuffer.takeDelta()!!
        assertTrue("The first delta is the whole new surface", delta.resized)
        assertNull(rfb.framebuffer.takeDelta())
    }

    @Test fun dirtyRegionsAccumulateAsABoundingBox() {
        val rfb = connected(RfbScript(16, 16).update(rect(1, 2, 2, 1, RfbEncoding.RAW, pixel(1) + pixel(2)), rect(8, 9, 1, 2, RfbEncoding.RAW, pixel(3) + pixel(4))))
        rfb.framebuffer.takeDelta()
        rfb.readEvent()
        val delta = rfb.framebuffer.takeDelta()!!
        assertFalse(delta.resized)
        assertEquals(listOf(1, 2, 8, 9), listOf(delta.x, delta.y, delta.w, delta.h))
    }

    @Test fun copyRectMovesPixelsEvenWhenTheRegionsOverlap() {
        val row = (0 until 6).map { pixel(it + 1) }.reduce { a, b -> a + b }
        val rfb = connected(RfbScript(6, 2).update(rect(0, 0, 6, 1, RfbEncoding.RAW, row), rect(2, 0, 4, 1, RfbEncoding.COPY_RECT, short(0) + short(0))))
        rfb.readEvent()
        assertEquals((1..6).map { argb(it) }.let { listOf(it[0], it[1], it[0], it[1], it[2], it[3]) }, (0 until 6).map { rfb.at(it, 0) })
    }

    @Test fun copyRectCopiesDownwardOverlapsWithoutSmearing() {
        val script = RfbScript(1, 4)
        for (y in 0 until 4) script.update(rect(0, y, 1, 1, RfbEncoding.RAW, pixel(y + 1)))
        script.update(rect(0, 1, 1, 3, RfbEncoding.COPY_RECT, short(0) + short(0)))
        val rfb = connected(script)
        repeat(5) { rfb.readEvent() }
        assertEquals(listOf(1, 1, 2, 3).map { argb(it) }, (0 until 4).map { rfb.at(0, it) })
    }

    @Test fun copyRectSourceOutsideTheFramebufferIsRejected() {
        val rfb = connected(RfbScript(8, 8).update(rect(0, 0, 4, 4, RfbEncoding.COPY_RECT, short(6) + short(6))))
        failure { rfb.readEvent() }
    }

    // ---- Hextile ----

    private fun hextileScript(): RfbScript {
        val tile1 = byteArrayOf((2 or 4 or 8).toByte()) + pixel(0xff0000) + pixel(0x0000ff) + byteArrayOf(1, 0x23, 0x31)
        val gray = (0 until 64).map { val g = it and 0xff; pixel(g or (g shl 8) or (g shl 16)) }.reduce { a, b -> a + b }
        val tile2 = byteArrayOf(1) + gray
        val tile3 = byteArrayOf((8 or 16).toByte(), 2) + pixel(0x00ff00) + byteArrayOf(0x00, 0xf0.toByte()) + pixel(0xffff00) + byteArrayOf(0x51, 0x00)
        val tile4 = byteArrayOf(2) + pixel(0xffffff)
        return RfbScript(32, 20).update(rect(0, 0, 20, 18, RfbEncoding.HEXTILE, tile1 + tile2 + tile3 + tile4))
    }

    private fun checkHextile(rfb: RfbProtocol) {
        assertEquals(RfbEvent.UPDATE, rfb.readEvent())
        assertEquals(argb(0xff0000), rfb.at(0, 0)); assertEquals(argb(0xff0000), rfb.at(15, 15))
        for (y in 3..4) for (x in 2..5) assertEquals(argb(0x0000ff), rfb.at(x, y))
        assertEquals(argb(0xff0000), rfb.at(6, 3)); assertEquals(argb(0xff0000), rfb.at(2, 5))
        // Raw tile: 4x16 grey ramp, row-major.
        assertEquals(argb(0x000000), rfb.at(16, 0)); assertEquals(argb(0x010101), rfb.at(17, 0)); assertEquals(argb(0x040404), rfb.at(16, 1))
        // Background persists across tiles (a raw tile does not change it); coloured sub-rectangles.
        for (x in 0 until 16) assertEquals(argb(0x00ff00), rfb.at(x, 16))
        for (x in 0 until 16) assertEquals(if (x == 5) argb(0xffff00) else argb(0xff0000), rfb.at(x, 17))
        for (y in 16..17) for (x in 16..19) assertEquals(argb(0xffffff), rfb.at(x, y))
        assertEquals("Outside the rectangle is untouched", 0, rfb.at(20, 0))
    }

    @Test fun hextileDecodesBackgroundForegroundRawAndColouredSubrectangles() = checkHextile(connected(hextileScript()))
    @Test fun hextileSurvivesOneByteReads() = checkHextile(connected(hextileScript(), choppy = true))

    @Test fun hextileSubrectangleOutsideItsTileIsRejected() {
        // One tile with a specified background (2) and sub-rectangles (8): x=15, w=1 fits, x=15, w=2 does not.
        val fits = byteArrayOf(10) + pixel(0) + byteArrayOf(1, 0xf0.toByte(), 0x00)
        connected(RfbScript(16, 16).update(rect(0, 0, 16, 16, RfbEncoding.HEXTILE, fits))).readEvent()
        val bad = byteArrayOf(10) + pixel(0) + byteArrayOf(1, 0xf0.toByte(), 0x10)
        failure { connected(RfbScript(16, 16).update(rect(0, 0, 16, 16, RfbEncoding.HEXTILE, bad))).readEvent() }
        val below = byteArrayOf(10) + pixel(0) + byteArrayOf(1, 0x0f, 0x01)
        failure { connected(RfbScript(16, 16).update(rect(0, 0, 16, 16, RfbEncoding.HEXTILE, below))).readEvent() }
    }

    // ---- ZRLE ----

    private fun packed(bits: Int, width: Int, height: Int, index: (Int, Int) -> Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (y in 0 until height) {
            var buffer = 0; var used = 0
            for (x in 0 until width) {
                buffer = (buffer shl bits) or index(x, y); used += bits
                if (used == 8) { out.write(buffer); buffer = 0; used = 0 }
            }
            if (used > 0) out.write(buffer shl (8 - used))
        }
        return out.toByteArray()
    }

    private fun zrleScript(): RfbScript {
        val script = RfbScript(70, 66)
        val palette4 = intArrayOf(0xff0000, 0x00ff00, 0x0000ff, 0xffff00)
        val tileA = byteArrayOf(4) + palette4.map { cpixel(it) }.reduce { a, b -> a + b } + packed(2, 64, 64) { x, y -> (x + y) and 3 }
        val tileB = byteArrayOf(0x80.toByte()) + cpixel(0xff0000) + byteArrayOf(99) + cpixel(0x0000ff) + byteArrayOf(0xff.toByte(), 28)
        val tileC = byteArrayOf(0x83.toByte()) + cpixel(0x00ff00) + cpixel(0xffffff) + cpixel(0x000000) +
            byteArrayOf(2) + byteArrayOf(0x81.toByte(), 99) + byteArrayOf(0x80.toByte(), 26)
        val tileD = byteArrayOf(1) + cpixel(0xff00ff)
        script.update(rect(0, 0, 70, 66, RfbEncoding.ZRLE, script.zlib(tileA + tileB + tileC + tileD).let { int(it.size) + it }))
        // The same zlib stream continues: Raw tile (3x2), 2-colour 1-bit palette (5x2) and a 16-colour 4-bit palette (4x4).
        val raw = byteArrayOf(0) + (1..6).map { cpixel(it * 0x101010) }.reduce { a, b -> a + b }
        script.update(rect(0, 0, 3, 2, RfbEncoding.ZRLE, script.zlib(raw).let { int(it.size) + it }))
        val two = byteArrayOf(2) + cpixel(0x111111) + cpixel(0x222222) + packed(1, 5, 2) { x, y -> (x + y) and 1 }
        script.update(rect(10, 10, 5, 2, RfbEncoding.ZRLE, script.zlib(two).let { int(it.size) + it }))
        val sixteen = byteArrayOf(16) + (0 until 16).map { cpixel(0x010000 * (it + 1)) }.reduce { a, b -> a + b } + packed(4, 4, 4) { x, y -> y * 4 + x }
        script.update(rect(20, 20, 4, 4, RfbEncoding.ZRLE, script.zlib(sixteen).let { int(it.size) + it }))
        return script
    }

    private fun checkZrle(rfb: RfbProtocol) {
        val palette4 = intArrayOf(0xff0000, 0x00ff00, 0x0000ff, 0xffff00)
        rfb.readEvent()
        for (y in 0 until 64) for (x in 0 until 64) assertEquals(argb(palette4[(x + y) and 3]), rfb.at(x, y))
        for (n in 0 until 384) assertEquals(argb(if (n < 100) 0xff0000 else 0x0000ff), rfb.at(64 + n % 6, n / 6))
        val third = (0 until 128).map { n -> if (n == 0) 0x000000 else if (n <= 100) 0xffffff else 0x00ff00 }
        for (n in 0 until 128) assertEquals("tile C pixel $n", argb(third[n]), rfb.at(n % 64, 64 + n / 64))
        for (y in 64..65) for (x in 64..69) assertEquals(argb(0xff00ff), rfb.at(x, y))
        rfb.readEvent()
        for (n in 0 until 6) assertEquals(argb((n + 1) * 0x101010), rfb.at(n % 3, n / 3))
        rfb.readEvent()
        for (y in 0 until 2) for (x in 0 until 5) assertEquals(argb(if ((x + y) and 1 == 0) 0x111111 else 0x222222), rfb.at(10 + x, 10 + y))
        rfb.readEvent()
        for (y in 0 until 4) for (x in 0 until 4) assertEquals(argb(0x010000 * (y * 4 + x + 1)), rfb.at(20 + x, 20 + y))
    }

    @Test fun zrleDecodesEveryTileKindAcrossRectanglesOnOnePersistentStream() = checkZrle(connected(zrleScript()))
    @Test fun zrleSurvivesOneByteReads() = checkZrle(connected(zrleScript(), choppy = true))

    private fun zrleRect(script: RfbScript, w: Int, h: Int, tiles: ByteArray) =
        script.update(rect(0, 0, w, h, RfbEncoding.ZRLE, script.zlib(tiles).let { int(it.size) + it }))

    @Test fun malformedZrleTilesAreRejected() {
        val one = cpixel(1)
        listOf(
            "plain RLE run beyond the tile" to byteArrayOf(0x80.toByte()) + one + byteArrayOf(19),
            "palette index beyond the palette" to byteArrayOf(3) + one + one + one + byteArrayOf(0xc0.toByte(), 0, 0, 0),
            "unused palette size" to byteArrayOf(17) + ByteArray(80),
            "reserved RLE tile type" to byteArrayOf(0x81.toByte()) + one,
            "palette RLE index beyond the palette" to byteArrayOf(0x82.toByte()) + one + one + byteArrayOf(5),
            "tile data ends early" to byteArrayOf(0) + one,
        ).forEach { (name, tile) ->
            val script = RfbScript(8, 8)
            zrleRect(script, 4, 4, tile)
            failure("$name") { connected(script).readEvent() }
        }
    }
    private fun failure(name: String, block: () -> Unit) = assertThrows(name, ConsoleError::class.java) { block() }

    @Test fun corruptAndOversizedZrleDataIsRejected() {
        failure { connected(RfbScript(8, 8).update(rect(0, 0, 4, 4, RfbEncoding.ZRLE, int(6) + hex("de ad be ef 00 01")))).readEvent() }
        failure { connected(RfbScript(8, 8).update(rect(0, 0, 4, 4, RfbEncoding.ZRLE, int(10_000_000)))).readEvent() }
        failure { connected(RfbScript(8, 8).update(rect(0, 0, 4, 4, RfbEncoding.ZRLE, int(-1)))).readEvent() }
    }

    // ---- DesktopSize and malformed rectangles ----

    @Test fun desktopSizeResizesTheFramebufferAndLaterRectanglesUseTheNewSize() {
        val script = RfbScript(8, 8).update(rect(0, 0, 16, 12, RfbEncoding.DESKTOP_SIZE))
            .update(rect(10, 8, 2, 1, RfbEncoding.RAW, pixel(0x123456) + pixel(0x654321)))
        val rfb = connected(script)
        rfb.framebuffer.takeDelta()
        assertEquals(RfbEvent.RESIZED, rfb.readEvent())
        assertEquals(16, rfb.framebuffer.width); assertEquals(12, rfb.framebuffer.height)
        val delta = rfb.framebuffer.takeDelta()!!
        assertTrue(delta.resized); assertEquals(16 * 12, delta.pixels.size)
        assertEquals(RfbEvent.UPDATE, rfb.readEvent())
        assertEquals(argb(0x654321), rfb.at(11, 8))
    }

    @Test fun desktopSizeToAnAbsurdSizeIsRejected() {
        failure { connected(RfbScript(8, 8).update(rect(0, 0, 5000, 5000, RfbEncoding.DESKTOP_SIZE))).readEvent() }
        failure { connected(RfbScript(8, 8).update(rect(0, 0, 0, 5, RfbEncoding.DESKTOP_SIZE))).readEvent() }
    }

    @Test fun malformedRectanglesAreRejected() {
        failure { connected(RfbScript(8, 8).update(rect(0, 0, 0, 4, RfbEncoding.RAW))).readEvent() }
        failure { connected(RfbScript(8, 8).update(rect(6, 6, 4, 4, RfbEncoding.RAW, ByteArray(64)))).readEvent() }
        failure { connected(RfbScript(8, 8).update(rect(0, 0, 4, 4, 7, ByteArray(64)))).readEvent() }
        failure { connected(RfbScript(8, 8).raw(byteArrayOf(9))).readEvent() }
        failure { connected(RfbScript(8, 8).raw(byteArrayOf(1))).readEvent() }
    }

    @Test fun truncatedRectangleDataIsAnIoErrorNotACrash() {
        assertThrows(EOFException::class.java) { connected(RfbScript(8, 8).update(rect(0, 0, 4, 4, RfbEncoding.RAW, ByteArray(10)))).readEvent() }
        assertThrows(EOFException::class.java) { connected(RfbScript(8, 8)).readEvent() }
    }

    @Test fun bellAndServerClipboardAreSkippedWithoutStoringText() {
        val rfb = connected(RfbScript(8, 8).bell().serverCutText("secret clipboard".toByteArray()).update(rect(0, 0, 1, 1, RfbEncoding.RAW, pixel(5))))
        assertEquals(RfbEvent.BELL, rfb.readEvent())
        assertEquals(RfbEvent.CLIPBOARD, rfb.readEvent())
        assertEquals(RfbEvent.UPDATE, rfb.readEvent())
        assertEquals(argb(5), rfb.at(0, 0))
        failure { connected(RfbScript(8, 8).raw(byteArrayOf(3, 0, 0, 0) + int(2_000_000))).readEvent() }
    }

    @Test fun safeModeAdvertisesOnlyUncompressedEncodings() {
        val (rfb, out) = client(RfbScript(8, 8), encodings = RfbEncoding.SAFE)
        rfb.handshake()
        assertTrue(out.toByteArray().toList().containsAll(RfbProtocol.encodingsMessage(RfbEncoding.SAFE).toList()))
        assertFalse(out.toByteArray().toList().windowed(4).contains(int(RfbEncoding.ZRLE).toList()))
    }

    @Test fun mixedUpdateDecodesIdenticallyWithPartialReads() {
        fun script() = RfbScript(32, 20).also { s ->
            s.update(rect(0, 0, 4, 1, RfbEncoding.RAW, pixel(1) + pixel(2) + pixel(3) + pixel(4)), rect(4, 1, 4, 1, RfbEncoding.COPY_RECT, short(0) + short(0)))
            val tile = byteArrayOf(1) + cpixel(0x00ff00)
            s.update(rect(8, 8, 4, 4, RfbEncoding.ZRLE, s.zlib(tile).let { int(it.size) + it }))
        }
        val whole = connected(script()); repeat(2) { whole.readEvent() }
        val choppy = connected(script(), choppy = true); repeat(2) { choppy.readEvent() }
        assertArrayEquals(whole.framebuffer.pixels, choppy.framebuffer.pixels)
    }
}
