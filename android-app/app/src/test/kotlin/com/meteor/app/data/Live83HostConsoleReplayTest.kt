package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File

/**
 * Replays PRIVATE recordings of the real XCP-ng 8.3 host console through the app's decoders. Skipped unless the newest
 * capture directory under the folder named by `METEOR_LIVE83_CAPTURES` (or `METEOR_LIVE83_CAPTURE`) holds the host-console passes
 * recorded with `record_fixtures.py capture --part region --target dom0 --pixel <plan> --prefix private-dom0-<plan>`. The host's
 * screen shows its management address, so these bytes are never committed and nothing here prints a pixel.
 *
 * What the recordings show (2026-11-15): sent its own 8 bpp format back explicitly (`true8`) or asked for 32 bpp (`app32`),
 * the host console answers with Raw and Hextile rectangles (Raw strips even when only Hextile is listed); asked for a
 * colour map (`colourmap8`) or sent no SetPixelFormat at all (`native`), it answers no update request.
 */
class Live83HostConsoleReplayTest {
    private fun capture(name: String): ByteArray {
        val roots = listOfNotNull(System.getenv("METEOR_LIVE83_CAPTURE")?.let(::File), System.getenv("METEOR_LIVE83_CAPTURES")?.let(::File))
        val file = roots.flatMap { root -> (root.listFiles { f -> f.isDirectory } ?: emptyArray<File>()).toList() + root }
            .sortedByDescending { it.name }
            .firstNotNullOfOrNull { dir -> dir.listFiles { f -> f.name.endsWith("-$name.s2c.bin") }?.singleOrNull() }
        assumeTrue("no private capture of $name on this machine", file != null)
        val raw = file!!.readBytes()
        val head = String(raw, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        return if (String(raw.copyOf(5), Charsets.ISO_8859_1) == "HTTP/") raw.copyOfRange(head + 4, raw.size) else raw
    }

    private class Replay(val info: RfbServerInfo, val updates: Int, val pixels: IntArray, val format: RfbPixelFormat)

    private fun replay(name: String, format: RfbPixelFormat?, encodings: List<Int>): Replay {
        val rfb = RfbProtocol(ByteArrayInputStream(capture(name)), ByteArrayOutputStream(), encodings, format)
        val info = rfb.handshake()
        var updates = 0
        try { while (true) if (rfb.readEvent() == RfbEvent.UPDATE) updates++ } catch (_: EOFException) { }
        return Replay(info, updates, rfb.framebuffer.pixels.copyOf(), rfb.agreedFormat)
    }

    private fun lit(pixel: Int) = (pixel and 0xffffff) != 0

    @Test fun theHostConsoleIn8BitsDecodesCompletelyWithHextileAndRaw() {
        for (plan in listOf("hextile-only", "raw-only", "app-default")) {
            val result = replay("private-dom0-true8-$plan", null, listOf(RfbEncoding.HEXTILE))
            assertEquals(RfbPixelFormat.TRUE_COLOUR_8, result.format)
            assertEquals(640, result.info.width); assertEquals(384, result.info.height)
            assertTrue("$plan: updates arrived", result.updates >= 1)
            assertTrue("$plan: something is on the screen", result.pixels.count(::lit) > 100)
        }
    }

    @Test fun theSameScreenAt8And32BitsAgrees() {
        val eight = replay("private-dom0-true8-hextile-only", null, listOf(RfbEncoding.HEXTILE)).pixels
        val thirtyTwo = replay("private-dom0-app32-hextile-only", RfbPixelFormat.TRUE_COLOUR_32, listOf(RfbEncoding.HEXTILE)).pixels
        val shape = eight.indices.count { lit(eight[it]) != lit(thirtyTwo[it]) }
        println("LIVE83 host console 8 vs 32 bpp: $shape pixels differ in shape (of ${eight.size})")
        assertTrue("shape differs in $shape pixels (a blinking cursor or a clock at most)", shape <= 2_000)
        // The server draws in 24-bit colour (its text is light grey c0c0c0) and rounds to 3-3-2 for an 8 bpp client, so the
        // colours are not equal: each 8-bit colour must map to one 32-bit colour, within one 3-3-2 step per channel.
        val pairs = eight.indices.filter { lit(eight[it]) && lit(thirtyTwo[it]) }.groupBy({ eight[it] }, { thirtyTwo[it] }).mapValues { it.value.toSet() }
        val shared = pairs.values.filter { it.size > 1 }.size
        println("LIVE83 host console colours: ${pairs.size} at 8 bpp, $shared of them with more than one 32 bpp colour")
        for ((small, large) in pairs) for (full in large) for ((shift, step) in listOf(16 to 37, 8 to 37, 0 to 85))
            assertTrue("channel within one 3-3-2 step", kotlin.math.abs(((small ushr shift) and 255) - ((full ushr shift) and 255)) <= step)
    }

    @Test fun theHostConsoleAt32BitsStillDecodesAsBefore() {
        val result = replay("private-dom0-app32-app-default", RfbPixelFormat.TRUE_COLOUR_32, RfbEncoding.DEFAULT)
        assertTrue(result.updates >= 1)
        assertTrue(result.pixels.count(::lit) > 100)
    }

    @Test fun withoutASetPixelFormatOrWithAColourMapTheHostConsoleSendsNothing() {
        for (name in listOf("private-dom0-native-hextile-only", "private-dom0-colourmap8-hextile-only")) {
            val rfb = RfbProtocol(ByteArrayInputStream(capture(name)), ByteArrayOutputStream())
            rfb.handshake()
            assertThrows("$name: no message after ServerInit", EOFException::class.java) { rfb.readEvent() }
        }
    }
}
