package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File

/**
 * The console against a real XCP-ng 8.3.0 server. Committed fixtures hold the RFB handshake bytes and, for Raw and
 * Hextile, the console's tiny all-black probe answers. The rendered screen is NOT committed: it shows the guest's host
 * name (and the host console shows the host's address). Full framebuffer replays run only on the machine that holds
 * the private captures (`Live83RfbPrivateReplayTest`), and are skipped everywhere else.
 */
class Live83ConsoleTest {
    private fun handshake(name: String, encodings: List<Int> = RfbEncoding.DEFAULT): Triple<RfbProtocol, RfbServerInfo, ByteArray> {
        val out = ByteArrayOutputStream()
        val rfb = RfbProtocol(ByteArrayInputStream(Live83.gunzip(name)), out, encodings)
        val info = rfb.handshake()
        return Triple(rfb, info, out.toByteArray())
    }

    @Test fun consoleLocationsAreHttpsPathConsoleWithARefQueryAndNoPort() {
        val consoles = Live83.records("console").values
        assertEquals(3, consoles.size)
        assertEquals(setOf("rfb", "vt100"), consoles.map { it["protocol"] }.toSet())
        for (console in consoles) {
            val uri = ConsoleSession.location(console["location"] as String)
            assertEquals("https", uri.scheme); assertEquals("/console", uri.rawPath); assertEquals(-1, uri.port)
            assertTrue(uri.rawQuery.startsWith("ref=OpaqueRef:"))
        }
        assertEquals("only the control domain has a vt100 console too", 2, consoles.count { it["protocol"] == "rfb" })
    }

    @Test fun aRunningGuestHasOneRfbConsoleAndATemplateHasNone() {
        assertEquals(1, (Live83.value("VM.get_consoles") as List<*>).size)
        assertEquals(0, (Live83.value("template.VM.get_consoles") as List<*>).size)
        assertEquals("rfb", (Live83.value("console.get_record") as Map<*, *>)["protocol"])
    }

    @Test fun theConnectRequestTheAppSendsMatchesTheRecordedOne() {
        val recorded = (Live83.entry("rfb-app-default")["connectRequest"] as List<*>).map { it.toString() }
        assertTrue(recorded[0].matches(Regex("CONNECT /console\\?ref=OpaqueRef:[0-9a-f-]+ HTTP/1\\.1")))
        assertTrue(recorded[1].startsWith("Host: "))
        assertTrue(recorded[2].startsWith("Cookie: session_id=OpaqueRef:"))
        assertTrue(recorded[3].startsWith("User-Agent: Meteor-Spark/"))
        assertEquals(listOf("HTTP/1.1 200 OK"), (Live83.entry("rfb-app-default")["connectResponse"] as List<*>).map { it.toString() }.take(1))
    }

    @Test fun theGuestConsoleSpeaksRfb38WithSecurityTypeNoneAndTheAppsHandshakeAnswersIt() {
        val entry = Live83.entry("rfb-app-default")
        assertEquals("RFB 003.008\n", entry["banner"])
        assertEquals(listOf(1L), entry["securityTypes"]); assertEquals(0L, entry["securityResult"])
        val (_, info, sent) = handshake("rfb-app-default.handshake.s2c.bin.gz")
        assertEquals(RfbServerInfo(720, 400, "QEMU"), info)
        // The app's own bytes are the prefix of what was recorded: version, type, ClientInit, pixel format, encodings, update request.
        val recorded = Live83.gunzip("rfb-app-default.c2s.bin.gz")
        assertArrayEquals(sent, recorded.copyOf(sent.size))
        assertEquals("RFB 003.008\n", String(sent.copyOf(12)))
        // Only handshake and update requests were ever sent: no key or pointer events.
        var offset = sent.size
        while (offset < recorded.size) { assertEquals(3, recorded[offset].toInt()); offset += 10 }
        assertEquals(recorded.size, offset)
    }

    @Test fun theNativePixelFormatIsTheOneTheAppRequestsAnyway() {
        assertEquals("2018000100ff00ff00ff100800000000", Live83.entry("rfb-app-default")["serverPixelFormat"])  // 32 bpp, little endian, R16 G8 B0
        assertEquals("08080001000700070003050200000000", Live83.entry("rfb-dom0")["serverPixelFormat"])        // 8 bpp, 3-3-2
        assertEquals(RfbProtocol.pixelFormatMessage().copyOfRange(4, 20).joinToString("") { "%02x".format(it) }, "2018000100ff00ff00ff100800000000")
    }

    @Test fun theHostConsoleIsRfb33At8BitsNamedXenServerVirtualTerminal() {
        val entry = Live83.entry("rfb-dom0")
        assertEquals("RFB 003.003\n", entry["banner"])
        assertEquals(listOf(1L), entry["securityTypes"])
        val (_, info, sent) = handshake("rfb-dom0.handshake.s2c.bin.gz")
        assertEquals(RfbServerInfo(640, 384, "XenServer Virtual Terminal"), info)
        assertEquals("RFB 003.003\n", String(sent.copyOf(12)))
        val recorded = Live83.gunzip("rfb-dom0.c2s.bin.gz")
        assertArrayEquals(sent, recorded.copyOf(sent.size))
    }

    @Test fun theHostAlsoOffersAVt100ConsoleThatSendsNothingUntilItIsWrittenTo() {
        val entry = Live83.entry("stream-dom0-vt100")
        assertEquals("HTTP/1.1 200 OK", (entry["connectResponse"] as List<*>).first())
        assertEquals(0L, entry["bytes"])
        assertEquals(0, Live83.gunzip("stream-dom0-vt100.s2c.bin.gz").size)
    }

    private fun replayBlackProbes(name: String) {
        val (rfb, info, _) = handshake(name)
        var events = 0
        try { while (true) { assertEquals(RfbEvent.UPDATE, rfb.readEvent()); events++ } } catch (_: EOFException) { }
        assertTrue("$name: only the console's probe answers are in the stream", events in 1..3)
        assertEquals(info.width, rfb.framebuffer.width)
        // Cursor-off pixels are black; nothing else was drawn.
        assertEquals(0xff000000.toInt(), rfb.framebuffer.pixels[0])
    }

    @Test fun theRawProbeAnswersDecode() = replayBlackProbes("rfb-raw-only.handshake-and-black-probe-updates.s2c.bin.gz")

    @Test fun theHextileProbeAnswersDecode() = replayBlackProbes("rfb-hextile-only.handshake-and-black-probe-updates.s2c.bin.gz")

    @Test fun whichEncodingTheServerUsedForEachAdvertisedList() {
        // Recorded sizes of the first update (full screen 720x400): Raw 1152016 B, ZRLE 646 B, Hextile 2478 B.
        assertEquals(1_152_016L, (Live83.entry("rfb-raw-only")["bytesAfterRequests"] as List<*>)[0])
        assertTrue((Live83.entry("rfb-app-default")["bytesAfterRequests"] as List<*>)[0] as Long in 500..800)
        assertTrue((Live83.entry("rfb-hextile-only")["bytesAfterRequests"] as List<*>)[0] as Long in 2000..3000)
        assertTrue("ZRLE alone and the default list give the same kind of answer", (Live83.entry("rfb-zrle-only")["bytesAfterRequests"] as List<*>)[0] as Long in 500..800)
    }
}

/**
 * Replays the PRIVATE recorded server bytes (full first screen) through the app's decoders. Skipped unless
 * `METEOR_LIVE83_CAPTURE` names a capture directory, or `METEOR_LIVE83_CAPTURES` names a folder of plain-timestamp capture
 * directories (the newest is used). The screens in these captures show private names and addresses, so they are never committed.
 */
class Live83RfbPrivateReplayTest {
    private fun captureDir(): File? {
        System.getenv("METEOR_LIVE83_CAPTURE")?.let { return File(it).takeIf { dir -> dir.isDirectory } }
        val root = System.getenv("METEOR_LIVE83_CAPTURES")?.let(::File) ?: return null
        return root.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{8}T\\d{6}Z")) }?.maxByOrNull { it.name }
    }

    private fun stream(plan: String): ByteArray {
        val dir = captureDir(); assumeTrue("no private capture on this machine", dir != null)
        val file = dir!!.listFiles { f -> f.name.endsWith("-$plan.s2c.bin") }?.singleOrNull()
        assumeTrue("no private capture for $plan", file != null)
        val raw = file!!.readBytes()
        val head = String(raw, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        return if (String(raw.copyOf(5), Charsets.ISO_8859_1) == "HTTP/") raw.copyOfRange(head + 4, raw.size) else raw
    }

    private class Replay(val info: RfbServerInfo, val events: List<RfbEvent>, val pixels: IntArray, val rfb: RfbProtocol)

    private fun replay(plan: String, encodings: List<Int>): Replay {
        val rfb = RfbProtocol(ByteArrayInputStream(stream(plan)), ByteArrayOutputStream(), encodings)
        val info = rfb.handshake()
        val events = ArrayList<RfbEvent>()
        try { while (true) events += rfb.readEvent() } catch (_: EOFException) { }
        return Replay(info, events, rfb.framebuffer.pixels.copyOf(), rfb)
    }

    private fun lit(pixels: IntArray) = pixels.count { (it and 0xffffff) != 0 }

    @Test fun zrleStreamDecodesCompletely() {
        val result = replay("rfb-zrle-only", listOf(RfbEncoding.ZRLE))
        assertEquals(720, result.info.width)
        assertEquals(3, result.events.size)
        assertTrue("some text is on the screen", lit(result.pixels) in 200..60_000)
    }

    @Test fun defaultListStreamDecodesCompletely() {
        val result = replay("rfb-app-default", RfbEncoding.DEFAULT)
        assertEquals(3, result.events.size)
        assertTrue(lit(result.pixels) in 200..60_000)
    }

    @Test fun hextileStreamDecodesCompletely() {
        val result = replay("rfb-hextile-only", listOf(RfbEncoding.HEXTILE))
        assertEquals(3, result.events.size)
        assertTrue(lit(result.pixels) in 200..60_000)
    }

    @Test fun rawStreamDecodesCompletely() {
        val result = replay("rfb-raw-only", listOf(RfbEncoding.RAW))
        assertEquals(3, result.events.size)
        assertTrue(lit(result.pixels) in 200..60_000)
    }

    @Test fun theThreeDecodersProduceTheSameScreenExceptForTheBlinkingCursor() {
        val raw = replay("rfb-raw-only", listOf(RfbEncoding.RAW)).pixels
        val zrle = replay("rfb-zrle-only", listOf(RfbEncoding.ZRLE)).pixels
        val hextile = replay("rfb-hextile-only", listOf(RfbEncoding.HEXTILE)).pixels
        val rawVsZrle = raw.indices.count { raw[it] != zrle[it] }
        val rawVsHextile = raw.indices.count { raw[it] != hextile[it] }
        println("LIVE83 decoder agreement: raw/zrle differ in $rawVsZrle pixels, raw/hextile in $rawVsHextile (cursor cell only)")
        assertTrue("raw vs zrle differ in $rawVsZrle pixels", rawVsZrle <= 40)
        assertTrue("raw vs hextile differ in $rawVsHextile pixels", rawVsHextile <= 40)
    }

    @Test fun theHostConsoleHextileStreamDecodes() {
        val result = replay("rfb-dom0", RfbEncoding.DEFAULT)
        assertEquals(640, result.info.width); assertEquals(384, result.info.height)
        assertTrue(result.events.isNotEmpty())
        assertTrue(lit(result.pixels) > 100)
    }
}
