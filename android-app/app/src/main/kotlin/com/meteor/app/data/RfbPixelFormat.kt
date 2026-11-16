package com.meteor.app.data

/**
 * An RFB pixel format (RFC 6143 §7.4): the 16 bytes of ServerInit and SetPixelFormat. Only true-colour formats are
 * decoded; a colour-map format is never requested. Pure Kotlin, so every conversion is tested on the JVM.
 *
 * Recorded on a real XCP-ng 8.3 host: a guest's QEMU announces [TRUE_COLOUR_32]; the host console ("XenServer Virtual
 * Terminal") announces 8 bpp true colour 3-3-2 ([TRUE_COLOUR_8]). The host console answers no update request at all
 * until the client has sent a SetPixelFormat, so the client always sends one, even when it keeps the announced format.
 */
data class RfbPixelFormat(
    val bitsPerPixel: Int, val depth: Int, val bigEndian: Boolean, val trueColour: Boolean,
    val redMax: Int, val greenMax: Int, val blueMax: Int, val redShift: Int, val greenShift: Int, val blueShift: Int,
) {
    companion object {
        /** What the app has always requested: 32 bpp, depth 24, little endian, 255/255/255, shifts 16/8/0. */
        val TRUE_COLOUR_32 = RfbPixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0)
        /** The host console's own format, as recorded: 8 bpp true colour 3-3-2, red in bits 5-7, green 2-4, blue 0-1. */
        val TRUE_COLOUR_8 = RfbPixelFormat(8, 8, false, true, 7, 7, 3, 5, 2, 0)

        fun parse(bytes: ByteArray, offset: Int = 0): RfbPixelFormat {
            fun u8(index: Int) = bytes[offset + index].toInt() and 0xff
            fun u16(index: Int) = (u8(index) shl 8) or u8(index + 1)
            return RfbPixelFormat(u8(0), u8(1), u8(2) != 0, u8(3) != 0, u16(4), u16(6), u16(8), u8(10), u8(11), u8(12))
        }
    }

    val bytesPerPixel: Int get() = bitsPerPixel / 8

    /**
     * A format this client can decode: 8, 16 or 32 bpp true colour, each channel maximum one less than a power of two
     * (at most 8 bits, as RFC 6143 servers use) and inside the pixel.
     */
    val decodable: Boolean get() = trueColour && bitsPerPixel in setOf(8, 16, 32) && depth in 1..bitsPerPixel &&
        listOf(redMax to redShift, greenMax to greenShift, blueMax to blueShift).all { (max, shift) ->
            max in 1..255 && (max and (max + 1)) == 0 && shift in 0 until bitsPerPixel && shift + Integer.bitCount(max) <= bitsPerPixel
        }

    /** The 16 format bytes as sent in SetPixelFormat. */
    fun bytes(): ByteArray = byteArrayOf(bitsPerPixel.toByte(), depth.toByte(), if (bigEndian) 1 else 0, if (trueColour) 1 else 0,
        (redMax ushr 8).toByte(), redMax.toByte(), (greenMax ushr 8).toByte(), greenMax.toByte(), (blueMax ushr 8).toByte(), blueMax.toByte(),
        redShift.toByte(), greenShift.toByte(), blueShift.toByte(), 0, 0, 0)

    /** SetPixelFormat (message type 0, three bytes of padding, then the format). */
    fun message(): ByteArray = byteArrayOf(0, 0, 0, 0) + bytes()

    private fun channel(value: Int, max: Int, shift: Int): Int = ((value ushr shift) and max).let { (it * 255 + max / 2) / max }

    /** An opaque ARGB colour for a pixel value already assembled in the format's byte order. */
    fun argb(value: Int): Int = 0xff000000.toInt() or (channel(value, redMax, redShift) shl 16) or
        (channel(value, greenMax, greenShift) shl 8) or channel(value, blueMax, blueShift)

    /**
     * ZRLE's compressed pixel: 3 bytes when the format is 32 bpp true colour of depth 24 or less whose channels all fit in the
     * least (or the most) significant three bytes, otherwise a whole pixel (RFC 6143 §7.7.6).
     */
    val cpixelBytes: Int get() = if (bitsPerPixel == 32 && trueColour && depth <= 24 && (fitsLow || fitsHigh)) 3 else bytesPerPixel

    private val channelBits get() = listOf(redMax to redShift, greenMax to greenShift, blueMax to blueShift)
        .fold(0L) { bits, (max, shift) -> bits or (max.toLong() shl shift) }
    internal val fitsLow: Boolean get() = channelBits and 0xff000000L == 0L
    internal val fitsHigh: Boolean get() = channelBits and 0xffL == 0L
}
