package com.meteor.app.data

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** RFB encoding numbers (RFC 6143). Negative values are pseudo-encodings. */
object RfbEncoding {
    const val RAW = 0
    const val COPY_RECT = 1
    const val HEXTILE = 5
    const val ZRLE = 16
    const val DESKTOP_SIZE = -223

    /** Preference order sent in SetEncodings: compressed first, Raw as the universal fallback. */
    val DEFAULT = listOf(ZRLE, HEXTILE, COPY_RECT, RAW, DESKTOP_SIZE)
    /** Compatibility mode used for one reconnect after a decoding failure. */
    val SAFE = listOf(COPY_RECT, RAW, DESKTOP_SIZE)
}

data class RfbServerInfo(val width: Int, val height: Int, val name: String)

/** What a server message changed. The caller decides when to request the next update. */
enum class RfbEvent { UPDATE, RESIZED, BELL, CLIPBOARD }

/**
 * RFB 3.3 / 3.7 / 3.8 client with security type None. One decoder thread calls [readEvent]; any thread may
 * send key and pointer events. Pixels are always requested as 32 bpp, depth 24, little endian, 8 bits per
 * channel (red shift 16, green 8, blue 0), so Raw and Hextile pixels are `B G R pad` and ZRLE
 * compressed pixels (CPIXEL) are `B G R`.
 *
 * Memory and work are bounded: the framebuffer is limited by [Framebuffer.validSize], every rectangle
 * must be non-empty and inside it, ZRLE data is inflated on demand with a size cap, and server clipboard
 * text is skipped without being stored.
 */
class RfbProtocol(input: InputStream, output: OutputStream, private val encodings: List<Int> = RfbEncoding.DEFAULT) : Closeable {
    companion object {
        private const val MAX_NAME = 65_536
        private const val MAX_CLIPBOARD_SKIP = 1_048_576
        private const val MAX_ZRLE_COMPRESSED = 64L * 1024 * 1024
        const val LEFT = 1
        const val MIDDLE = 2
        const val RIGHT = 4
        const val WHEEL_UP = 8
        const val WHEEL_DOWN = 16
        const val WHEEL_LEFT = 32
        const val WHEEL_RIGHT = 64

        /** SetPixelFormat: 32 bpp, depth 24, little endian, true colour, 255/255/255, shifts 16/8/0. */
        fun pixelFormatMessage(): ByteArray = byteArrayOf(
            0, 0, 0, 0, 32, 24, 0, 1, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 16, 8, 0, 0, 0, 0)

        fun encodingsMessage(list: List<Int>): ByteArray {
            val out = ByteArray(4 + 4 * list.size)
            out[0] = 2; out[2] = (list.size ushr 8).toByte(); out[3] = list.size.toByte()
            list.forEachIndexed { index, value -> for (byteIndex in 0..3) out[4 + 4 * index + byteIndex] = (value ushr (24 - 8 * byteIndex)).toByte() }
            return out
        }

        fun updateRequestMessage(incremental: Boolean, x: Int, y: Int, w: Int, h: Int): ByteArray = byteArrayOf(
            3, if (incremental) 1 else 0, (x ushr 8).toByte(), x.toByte(), (y ushr 8).toByte(), y.toByte(),
            (w ushr 8).toByte(), w.toByte(), (h ushr 8).toByte(), h.toByte())

        fun keyMessage(keysym: Int, down: Boolean): ByteArray = byteArrayOf(
            4, if (down) 1 else 0, 0, 0, (keysym ushr 24).toByte(), (keysym ushr 16).toByte(), (keysym ushr 8).toByte(), keysym.toByte())

        fun pointerMessage(mask: Int, x: Int, y: Int): ByteArray = byteArrayOf(
            5, mask.toByte(), (x ushr 8).toByte(), x.toByte(), (y ushr 8).toByte(), y.toByte())
    }

    private val source = DataInputStream(BufferedInputStream(input, 64 * 1024))
    private val sink = output
    private val inflater = Inflater()
    private var fb: Framebuffer? = null
    private var rowBuffer = ByteArray(0)
    private val tile = IntArray(64 * 64)
    private val scratch = ByteArray(4096)
    @Volatile private var closed = false

    val framebuffer: Framebuffer get() = checkNotNull(fb) { "The console handshake has not completed." }
    val ready: Boolean get() = fb != null

    private fun fail(message: String, kind: ConsoleFailureKind = ConsoleFailureKind.PROTOCOL): Nothing = throw ConsoleError(message, kind)

    private fun reason(): String {
        val size = source.readInt()
        if (size !in 0..1024) return ""
        return String(ByteArray(size).also(source::readFully), Charsets.UTF_8).filter { it in ' '..'~' }.take(120)
    }

    fun handshake(): RfbServerInfo {
        val banner = ByteArray(12).also(source::readFully).toString(Charsets.US_ASCII)
        if (!banner.matches(Regex("RFB 003\\.00[3-8]\\n"))) fail("Unsupported RFB version.", ConsoleFailureKind.UNSUPPORTED)
        val modern = banner.substring(8, 11).toInt() >= 8
        send(if (modern) "RFB 003.008\n".toByteArray(Charsets.US_ASCII) else "RFB 003.003\n".toByteArray(Charsets.US_ASCII))
        val unsupported = "Console requires unsupported RFB authentication; only None is supported."
        if (modern) {
            val count = source.readUnsignedByte()
            if (count == 0) fail("The console refused the connection${reason().let { if (it.isEmpty()) "." else ": $it" }}", ConsoleFailureKind.FAILED)
            val types = ByteArray(count).also(source::readFully)
            if (1.toByte() !in types) fail(unsupported, ConsoleFailureKind.UNSUPPORTED)
            send(byteArrayOf(1))
            if (source.readInt() != 0) fail("Console authentication failed.", ConsoleFailureKind.AUTH)
        } else when (source.readInt()) {
            1 -> Unit
            0 -> fail("The console refused the connection${reason().let { if (it.isEmpty()) "." else ": $it" }}", ConsoleFailureKind.FAILED)
            else -> fail(unsupported, ConsoleFailureKind.UNSUPPORTED)
        }
        send(byteArrayOf(1)) // ClientInit: shared, so other viewers such as XenCenter are not disconnected.
        val width = source.readUnsignedShort(); val height = source.readUnsignedShort()
        if (!Framebuffer.validSize(width, height)) fail("Console framebuffer is too large or invalid.")
        source.readFully(ByteArray(16)) // The server's native pixel format is replaced below.
        val nameSize = source.readInt()
        if (nameSize !in 0..MAX_NAME) fail("Invalid console name length.")
        val name = String(ByteArray(nameSize).also(source::readFully), Charsets.UTF_8).filter { it >= ' ' }.take(128)
        fb = Framebuffer(width, height)
        send(pixelFormatMessage())
        send(encodingsMessage(encodings))
        requestUpdate(false)
        return RfbServerInfo(width, height, name)
    }

    /** Reads and applies one server message. Blocks until data arrives; closing the stream unblocks it. */
    fun readEvent(): RfbEvent {
        val type = source.read()
        if (type < 0) throw EOFException("The console closed the connection.")
        return when (type) {
            0 -> framebufferUpdate()
            1 -> fail("Colour-map consoles are not supported.", ConsoleFailureKind.UNSUPPORTED)
            2 -> RfbEvent.BELL
            3 -> {
                skipFully(3)
                val size = source.readInt()
                if (size !in 0..MAX_CLIPBOARD_SKIP) fail("Console clipboard exceeds limit.")
                skipFully(size.toLong())
                RfbEvent.CLIPBOARD
            }
            else -> fail("Unsupported console message.")
        }
    }

    private fun framebufferUpdate(): RfbEvent {
        skipFully(1)
        var resized = false
        repeat(source.readUnsignedShort()) {
            val x = source.readUnsignedShort(); val y = source.readUnsignedShort()
            val w = source.readUnsignedShort(); val h = source.readUnsignedShort()
            val encoding = source.readInt()
            if (encoding == RfbEncoding.DESKTOP_SIZE) {
                if (!Framebuffer.validSize(w, h)) fail("Console framebuffer is too large or invalid.")
                framebuffer.resize(w, h); resized = true
            } else {
                checkRect(x, y, w, h)
                when (encoding) {
                    RfbEncoding.RAW -> decodeRaw(x, y, w, h)
                    RfbEncoding.COPY_RECT -> decodeCopyRect(x, y, w, h)
                    RfbEncoding.HEXTILE -> decodeHextile(x, y, w, h)
                    RfbEncoding.ZRLE -> decodeZrle(x, y, w, h)
                    else -> fail("Unsupported console encoding.")
                }
            }
        }
        return if (resized) RfbEvent.RESIZED else RfbEvent.UPDATE
    }

    private fun checkRect(x: Int, y: Int, w: Int, h: Int) {
        val target = framebuffer
        if (w <= 0 || h <= 0 || x + w > target.width || y + h > target.height) fail("Unsupported console rectangle.")
    }

    private fun cpixel(): Int = 0xff000000.toInt() or (Integer.reverseBytes(source.readInt()) and 0xffffff)

    private fun decodeRaw(x: Int, y: Int, w: Int, h: Int) {
        val target = framebuffer; val pixels = target.pixels; val stride = target.width
        val bytes = w * 4
        if (rowBuffer.size < bytes) rowBuffer = ByteArray(bytes)
        for (row in 0 until h) {
            source.readFully(rowBuffer, 0, bytes)
            var offset = (y + row) * stride + x; var index = 0
            repeat(w) {
                pixels[offset++] = 0xff000000.toInt() or (rowBuffer[index].toInt() and 0xff) or
                    ((rowBuffer[index + 1].toInt() and 0xff) shl 8) or ((rowBuffer[index + 2].toInt() and 0xff) shl 16)
                index += 4
            }
        }
        target.markDirty(x, y, w, h)
    }

    private fun decodeCopyRect(x: Int, y: Int, w: Int, h: Int) {
        val srcX = source.readUnsignedShort(); val srcY = source.readUnsignedShort()
        val target = framebuffer
        if (srcX + w > target.width || srcY + h > target.height) fail("Unsupported console rectangle.")
        target.copy(srcX, srcY, w, h, x, y)
        target.markDirty(x, y, w, h)
    }

    private fun decodeHextile(x: Int, y: Int, w: Int, h: Int) {
        val target = framebuffer
        var background = 0xff000000.toInt(); var foreground = 0xff000000.toInt()
        var ty = y
        while (ty < y + h) {
            val th = minOf(16, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tw = minOf(16, x + w - tx)
                val mask = source.readUnsignedByte()
                if (mask and 1 != 0) {
                    val pixels = target.pixels; val stride = target.width
                    for (row in 0 until th) { var offset = (ty + row) * stride + tx; repeat(tw) { pixels[offset++] = 0xff000000.toInt() or (Integer.reverseBytes(source.readInt()) and 0xffffff) } }
                } else {
                    if (mask and 2 != 0) background = cpixel()
                    if (mask and 4 != 0) foreground = cpixel()
                    target.fill(tx, ty, tw, th, background)
                    if (mask and 8 != 0) {
                        repeat(source.readUnsignedByte()) {
                            val color = if (mask and 16 != 0) cpixel() else foreground
                            val position = source.readUnsignedByte(); val size = source.readUnsignedByte()
                            val sx = position ushr 4; val sy = position and 15
                            val sw = (size ushr 4) + 1; val sh = (size and 15) + 1
                            if (sx + sw > tw || sy + sh > th) fail("Corrupt Hextile subrectangle.")
                            target.fill(tx + sx, ty + sy, sw, sh, color)
                        }
                    }
                }
                tx += 16
            }
            ty += 16
        }
        target.markDirty(x, y, w, h)
    }

    /** Inflates ZRLE data on demand from the compressed bytes of one rectangle, keeping the connection's zlib stream aligned. */
    private inner class ZrleSource(private var remaining: Int, private val limit: Long) {
        private val input = ByteArray(16 * 1024)
        private val output = ByteArray(16 * 1024)
        private var position = 0
        private var length = 0
        private var total = 0L

        private fun fill(): Boolean {
            while (true) {
                val count = try { inflater.inflate(output) } catch (_: DataFormatException) { fail("Corrupt ZRLE data.") }
                if (count > 0) {
                    total += count
                    if (total > limit) fail("ZRLE data exceeds its rectangle.")
                    position = 0; length = count
                    return true
                }
                if (inflater.finished() || inflater.needsDictionary()) fail("Corrupt ZRLE data.")
                if (inflater.needsInput()) {
                    if (remaining == 0) return false
                    val chunk = minOf(remaining, input.size)
                    source.readFully(input, 0, chunk)
                    remaining -= chunk
                    inflater.setInput(input, 0, chunk)
                }
            }
        }

        fun byte(): Int {
            if (position >= length && !fill()) fail("ZRLE data ended early.")
            return output[position++].toInt() and 0xff
        }

        fun pixel(): Int {
            val blue = byte(); val green = byte(); val red = byte()
            return 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        }

        /** Consumes whatever the tiles did not use so the next rectangle continues the same zlib stream. */
        fun finish() {
            while (fill()) position = length
        }
    }

    private fun decodeZrle(x: Int, y: Int, w: Int, h: Int) {
        val compressed = source.readInt()
        val limit = minOf(MAX_ZRLE_COMPRESSED, w.toLong() * h * 4 + 65_536)
        if (compressed < 0 || compressed > limit) fail("ZRLE rectangle is too large.")
        val data = ZrleSource(compressed, limit)
        val target = framebuffer; val pixels = target.pixels; val stride = target.width
        var ty = y
        while (ty < y + h) {
            val th = minOf(64, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tw = minOf(64, x + w - tx)
                decodeZrleTile(data, tw, th)
                for (row in 0 until th) System.arraycopy(tile, row * tw, pixels, (ty + row) * stride + tx, tw)
                tx += 64
            }
            ty += 64
        }
        data.finish()
        target.markDirty(x, y, w, h)
    }

    private fun decodeZrleTile(data: ZrleSource, tw: Int, th: Int) {
        val sub = data.byte()
        val rle = sub and 0x80 != 0
        val paletteSize = sub and 0x7f
        val total = tw * th
        if (!rle) when {
            paletteSize == 0 -> for (i in 0 until total) tile[i] = data.pixel()
            paletteSize == 1 -> java.util.Arrays.fill(tile, 0, total, data.pixel())
            paletteSize <= 16 -> {
                val palette = IntArray(paletteSize) { data.pixel() }
                val bits = if (paletteSize == 2) 1 else if (paletteSize <= 4) 2 else 4
                val mask = (1 shl bits) - 1
                for (row in 0 until th) {
                    var buffer = 0; var left = 0
                    for (column in 0 until tw) {
                        if (left == 0) { buffer = data.byte(); left = 8 }
                        left -= bits
                        val index = (buffer ushr left) and mask
                        if (index >= paletteSize) fail("Corrupt ZRLE palette index.")
                        tile[row * tw + column] = palette[index]
                    }
                }
            }
            else -> fail("Corrupt ZRLE tile type.")
        } else when {
            paletteSize == 0 -> {
                var done = 0
                while (done < total) {
                    val color = data.pixel()
                    val run = runLength(data)
                    if (run > total - done) fail("Corrupt ZRLE run.")
                    java.util.Arrays.fill(tile, done, done + run, color); done += run
                }
            }
            paletteSize == 1 -> fail("Corrupt ZRLE tile type.")
            else -> {
                val palette = IntArray(paletteSize) { data.pixel() }
                var done = 0
                while (done < total) {
                    val code = data.byte()
                    val index = code and 0x7f
                    if (index >= paletteSize) fail("Corrupt ZRLE palette index.")
                    val run = if (code >= 0x80) runLength(data) else 1
                    if (run > total - done) fail("Corrupt ZRLE run.")
                    java.util.Arrays.fill(tile, done, done + run, palette[index]); done += run
                }
            }
        }
    }

    private fun runLength(data: ZrleSource): Int {
        var run = 1
        do { val part = data.byte(); run += part } while (part == 255)
        return run
    }

    private fun skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val read = source.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (read < 0) throw EOFException("The console closed the connection.")
            left -= read
        }
    }
    private fun skipFully(count: Int) = skipFully(count.toLong())

    private fun send(bytes: ByteArray) = synchronized(sink) { sink.write(bytes); sink.flush() }

    /** Requests the whole screen. Incremental requests return only what changed since the last update. */
    fun requestUpdate(incremental: Boolean) {
        val target = framebuffer
        send(updateRequestMessage(incremental, 0, 0, target.width, target.height))
    }

    /** A tiny non-incremental request that forces a reply, used as a liveness probe on a static screen. */
    fun probe() = send(updateRequestMessage(false, 0, 0, 1, 1))

    fun key(keysym: Int, down: Boolean) = send(keyMessage(keysym, down))

    fun pointer(x: Int, y: Int, buttons: Int) {
        val target = framebuffer
        send(pointerMessage(buttons and 0xff, x.coerceIn(0, target.width - 1), y.coerceIn(0, target.height - 1)))
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { inflater.end() }
    }
}
