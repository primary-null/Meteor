package com.meteor.app.data

import com.meteor.app.data.RfbScript.Companion.rect
import com.meteor.app.data.RfbScript.Companion.short
import com.meteor.app.data.RfbScript.Companion.int
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.util.zip.Deflater

/**
 * Pixel formats other than the app's 32 bpp one, as the XCP-ng host console uses: its recorded ServerInit announces 8 bpp
 * true colour 3-3-2. The handshake bytes are the recorded ones (`rfb-dom0`); the framebuffer data is synthetic, because a
 * real host console screen shows the host's address and is never committed.
 */
class RfbPixelFormatTest {
    private val recordedDom0 get() = Live83.gunzip("rfb-dom0.handshake.s2c.bin.gz")

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** An update message (type 0) with [rectangles]. */
    private fun update(vararg rectangles: ByteArray) = byteArrayOf(0, 0) + short(rectangles.size) + rectangles.fold(ByteArray(0)) { a, b -> a + b }

    private fun decode(handshake: ByteArray, format: RfbPixelFormat?, vararg messages: ByteArray): Pair<RfbProtocol, ByteArray> {
        val out = ByteArrayOutputStream()
        val rfb = RfbProtocol(ByteArrayInputStream(messages.fold(handshake) { a, b -> a + b }), out, RfbEncoding.DEFAULT, format)
        rfb.handshake()
        try { repeat(messages.size) { rfb.readEvent() } } catch (_: EOFException) { fail("the stream ended early") }
        return rfb to out.toByteArray()
    }

    /** A server handshake (RFB 3.3, security None) that announces [format]. */
    private fun handshake(width: Int, height: Int, format: RfbPixelFormat): ByteArray {
        val out = ByteArrayOutputStream(); val data = DataOutputStream(out)
        data.writeBytes("RFB 003.003\n"); data.writeInt(1); data.writeShort(width); data.writeShort(height)
        data.write(format.bytes()); data.writeInt(4); data.writeBytes("test")
        return out.toByteArray()
    }

    // ---- formats ----

    @Test fun theRecordedHostConsoleFormatIs8BitTrueColour332() {
        val announced = RfbPixelFormat.parse(recordedDom0, 12 + 4 + 4)
        assertEquals(RfbPixelFormat.TRUE_COLOUR_8, announced)
        assertEquals("08080001000700070003050200000000", hex(announced.bytes()))
        assertTrue(announced.decodable)
        assertEquals(1, announced.bytesPerPixel); assertEquals(1, announced.cpixelBytes)
    }

    @Test fun theAppsUsualFormatIsUnchanged() {
        assertEquals("000000002018000100ff00ff00ff100800000000", hex(RfbProtocol.pixelFormatMessage()))
        assertEquals(3, RfbPixelFormat.TRUE_COLOUR_32.cpixelBytes)
    }

    @Test fun threeThreeTwoColoursExpandToFullIntensity() {
        val f = RfbPixelFormat.TRUE_COLOUR_8
        assertEquals(0xffff0000.toInt(), f.argb(0xe0)); assertEquals(0xff00ff00.toInt(), f.argb(0x1c)); assertEquals(0xff0000ff.toInt(), f.argb(0x03))
        assertEquals(0xffffffff.toInt(), f.argb(0xff)); assertEquals(0xff000000.toInt(), f.argb(0))
        assertEquals("red 3 of 7", 0xff6d0000.toInt(), f.argb(3 shl 5))
        assertEquals("blue 1 of 3", 0xff000055.toInt(), f.argb(1))
    }

    @Test fun colourMapAndOddFormatsAreNotDecodable() {
        assertFalse("colour map", RfbPixelFormat.TRUE_COLOUR_8.copy(trueColour = false).decodable)
        assertFalse("24 bpp", RfbPixelFormat.TRUE_COLOUR_32.copy(bitsPerPixel = 24).decodable)
        assertFalse("a maximum that is not 2^n - 1", RfbPixelFormat.TRUE_COLOUR_8.copy(redMax = 6).decodable)
        assertFalse("a channel outside the pixel", RfbPixelFormat.TRUE_COLOUR_8.copy(redShift = 6).decodable)
        assertFalse("all zero, as a test script announces", RfbPixelFormat.parse(ByteArray(16)).decodable)
    }

    // ---- negotiation ----

    @Test fun keepingTheNativeFormatStillSendsItBecauseTheHostConsoleWaitsForASetPixelFormat() {
        val (rfb, sent) = decode(recordedDom0, null)
        assertEquals(RfbPixelFormat.TRUE_COLOUR_8, rfb.agreedFormat)
        val afterInit = sent.copyOfRange(13, 33) // version (12), ClientInit (1), then SetPixelFormat
        assertArrayEquals(RfbPixelFormat.TRUE_COLOUR_8.message(), afterInit)
        assertEquals("the encoding list follows", 2, sent[33].toInt())
    }

    @Test fun byDefaultTheHostConsoleIsAsked32BitsExactlyAsAGuest() {
        val (rfb, sent) = decode(recordedDom0, RfbPixelFormat.TRUE_COLOUR_32)
        assertEquals(RfbPixelFormat.TRUE_COLOUR_32, rfb.agreedFormat)
        assertArrayEquals(RfbProtocol.pixelFormatMessage(), sent.copyOfRange(13, 33))
    }

    @Test fun aColourMapServerIsAskedForTrueColourInstead() {
        val (rfb, sent) = decode(handshake(4, 4, RfbPixelFormat.TRUE_COLOUR_8.copy(trueColour = false)), null)
        assertEquals(RfbPixelFormat.TRUE_COLOUR_32, rfb.agreedFormat)
        assertArrayEquals(RfbProtocol.pixelFormatMessage(), sent.copyOfRange(13, 33))
    }

    @Test fun aColourMapMessageIsStillRefused() {
        val rfb = RfbProtocol(ByteArrayInputStream(recordedDom0 + byteArrayOf(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)), ByteArrayOutputStream(), RfbEncoding.DEFAULT, null)
        rfb.handshake()
        assertEquals(ConsoleFailureKind.UNSUPPORTED, assertThrows(ConsoleError::class.java) { rfb.readEvent() }.kind)
    }

    // ---- decoding at 8 bpp ----

    @Test fun rawAt8BitsPerPixel() {
        val (rfb, _) = decode(recordedDom0, null, update(rect(0, 0, 3, 1, RfbEncoding.RAW, byteArrayOf(0xe0.toByte(), 0x1c, 0x03))))
        val p = rfb.framebuffer.pixels
        assertEquals(listOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt()), listOf(p[0], p[1], p[2]))
        assertEquals(640, rfb.framebuffer.width)
    }

    @Test fun hextileAt8BitsPerPixelWithEveryKindOfTile() {
        val white = 0xff.toByte(); val red = 0xe0.toByte(); val green = 0x1c.toByte()
        val tiles = byteArrayOf(
            // tile 1: background white + foreground red + two sub-rectangles in the foreground colour
            (2 or 4 or 8).toByte(), white, red, 2, 0x00, 0x00, 0x11, 0x11,
            // tile 2: raw 16x16 pixels, all green
            1) + ByteArray(256) { green } + byteArrayOf(
            // tile 3: inherited background, coloured sub-rectangle (red, 2x1 at 1,0)
            (8 or 16).toByte(), 1, red, 0x10, 0x10)
        val (rfb, _) = decode(recordedDom0, null, update(rect(0, 0, 48, 16, RfbEncoding.HEXTILE, tiles)))
        val fb = rfb.framebuffer; val p = fb.pixels; val w = fb.width
        assertEquals(0xffff0000.toInt(), p[0]); assertEquals(0xffff0000.toInt(), p[17 * 0 + 0])
        assertEquals("sub-rectangle at (1,1)", 0xffff0000.toInt(), p[w + 1])
        assertEquals("background", 0xffffffff.toInt(), p[5])
        assertEquals("raw tile", 0xff00ff00.toInt(), p[16]); assertEquals(0xff00ff00.toInt(), p[15 * w + 31])
        assertEquals("inherited white", 0xffffffff.toInt(), p[32]); assertEquals("coloured", 0xffff0000.toInt(), p[33])
        assertEquals(0xffff0000.toInt(), p[34]); assertEquals(0xffffffff.toInt(), p[35])
    }

    @Test fun rawAndHextileRectanglesInOneUpdateAsTheRealHostSends() {
        // Recorded: with only Hextile listed, the first answer after SetPixelFormat mixed Raw strips with Hextile ones.
        val raw = rect(0, 0, 2, 1, RfbEncoding.RAW, byteArrayOf(0x03, 0x03))
        val hextile = rect(0, 1, 16, 16, RfbEncoding.HEXTILE, byteArrayOf(2, 0xe0.toByte()))
        val (rfb, _) = decode(recordedDom0, null, update(raw, hextile))
        val p = rfb.framebuffer.pixels; val w = rfb.framebuffer.width
        assertEquals(0xff0000ff.toInt(), p[1]); assertEquals(0xffff0000.toInt(), p[w]); assertEquals(0xffff0000.toInt(), p[16 * w + 15])
    }

    @Test fun zrleAt8BitsPerPixelUsesOneBytePixels() {
        val deflater = Deflater()
        val plain = byteArrayOf(1, 0xe0.toByte()) // one solid 64x64 tile, one-byte CPIXEL
        deflater.setInput(plain); val buffer = ByteArray(256)
        val compressed = buffer.copyOf(deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH))
        val (rfb, _) = decode(recordedDom0, null, update(rect(0, 0, 64, 64, RfbEncoding.ZRLE, int(compressed.size) + compressed)))
        assertEquals(0xffff0000.toInt(), rfb.framebuffer.pixels[63 * 640 + 63])
    }

    // ---- other true-colour layouts ----

    @Test fun sixteenBitBigEndian565() {
        val f = RfbPixelFormat(16, 16, true, true, 31, 63, 31, 11, 5, 0)
        assertTrue(f.decodable)
        val (rfb, _) = decode(handshake(2, 1, f), null, update(rect(0, 0, 2, 1, RfbEncoding.RAW, byteArrayOf(0xf8.toByte(), 0, 0x07, 0xe0.toByte()))))
        assertEquals(0xffff0000.toInt(), rfb.framebuffer.pixels[0]); assertEquals(0xff00ff00.toInt(), rfb.framebuffer.pixels[1])
    }

    @Test fun thirtyTwoBitBigEndianZrleCarriesTheMostSignificantThreeBytes() {
        val f = RfbPixelFormat(32, 24, true, true, 255, 255, 255, 24, 16, 8)
        assertTrue(f.fitsHigh); assertFalse(f.fitsLow); assertEquals(3, f.cpixelBytes)
        val deflater = Deflater()
        deflater.setInput(byteArrayOf(1, 0x11, 0x22, 0x33)); val buffer = ByteArray(256)
        val compressed = buffer.copyOf(deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH))
        val (rfb, _) = decode(handshake(4, 4, f), null, update(rect(0, 0, 4, 4, RfbEncoding.ZRLE, int(compressed.size) + compressed)))
        assertEquals(0xff112233.toInt(), rfb.framebuffer.pixels[15])
    }

    @Test fun thirtyTwoBitLittleEndianOtherShiftsUseTheGeneralPath() {
        val f = RfbPixelFormat(32, 24, false, true, 255, 255, 255, 0, 8, 16) // red in the lowest byte
        val (rfb, _) = decode(handshake(1, 1, f), null, update(rect(0, 0, 1, 1, RfbEncoding.RAW, byteArrayOf(0x11, 0x22, 0x33, 0))))
        assertEquals(0xff112233.toInt(), rfb.framebuffer.pixels[0])
    }
}
