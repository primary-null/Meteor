package com.meteor.app.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * One way to write a measured value, for the live performance view and the history charts (D2 of the first phone run:
 * the live view printed 1024 MiB as "1.02e+03 MiB" because it used a general-scientific format). Three significant digits,
 * never exponent notation at any magnitude; bytes and rates are scaled to B, KiB, MiB, GiB, TiB, PiB or EiB.
 */
object MetricFormat {
    private val BYTE_UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    private const val TINY = "<0.0001"

    /** Decimals that give about three significant digits: 123, 12.3, 1.23, 0.123, 0.0123 (never more than four). */
    private fun decimals(magnitude: Double): Int = when {
        magnitude >= 100 -> 0
        magnitude >= 10 -> 1
        magnitude >= 1 -> 2
        magnitude >= 0.1 -> 3
        else -> 4
    }

    /** [decimals] again after rounding, so 99.95 becomes "100" and 0.9999 becomes "1.00" rather than gaining a digit. */
    private fun digits(magnitude: Double): Int = decimals(magnitude).let { first -> decimals(roundedTo(magnitude, first)) }

    private fun fixed(value: Double, decimals: Int, locale: Locale, grouping: Boolean): String {
        val text = String.format(locale, if (grouping) "%,.${decimals}f" else "%.${decimals}f", value)
        // Rounding a small negative value gives "-0" or "-0.00"; that is plain zero.
        return if (text.startsWith("-") && text.none { it in '1'..'9' }) text.substring(1) else text
    }

    /** A plain number: "0", "0.123", "1.23", "12.3", "123", "12,346", "1,000,000,000,000,000,000". Never an exponent. */
    fun number(value: Double, locale: Locale = Locale.getDefault()): String {
        if (!value.isFinite()) return "No data"
        if (value == 0.0) return "0"
        val magnitude = abs(value)
        val text = fixed(value, digits(magnitude), locale, grouping = true)
        return if (text.none { it in '1'..'9' }) (if (value > 0) TINY else "0") else text
    }

    /** A percentage given as a percent value (12.5 is "12.5%"). */
    fun percent(percent: Double, locale: Locale = Locale.getDefault()): String =
        if (!percent.isFinite()) "No data" else number(percent, locale) + "%"

    /** A load average. */
    fun load(value: Double, locale: Locale = Locale.getDefault()): String = if (!value.isFinite()) "No data" else fixed(value, 2, locale, grouping = true)

    /** A size in bytes with the largest unit that keeps the number under 1024: "512 B", "1.00 GiB", "730 MiB". */
    fun bytes(bytes: Double, locale: Locale = Locale.getDefault()): String {
        if (!bytes.isFinite()) return "No data"
        var scaled = abs(bytes)
        var index = 0
        while (scaled >= 1024 && index < BYTE_UNITS.lastIndex) { scaled /= 1024; index++ }
        var d = if (index == 0) 0 else digits(scaled)
        // A value that rounds up to 1024 belongs to the next unit: 1023.99 KiB is 1.00 MiB.
        if (index < BYTE_UNITS.lastIndex && roundedTo(scaled, d) >= 1024.0) { scaled /= 1024; index++; d = digits(scaled) }
        val signed = if (bytes < 0) -scaled else scaled
        return fixed(signed, d, locale, grouping = index == BYTE_UNITS.lastIndex) + " " + BYTE_UNITS[index]
    }
    private fun roundedTo(value: Double, decimals: Int): Double = 10.0.pow(decimals).let { (value * it).roundToLong() / it }

    /** A rate in bytes per second. */
    fun rate(bytesPerSecond: Double, locale: Locale = Locale.getDefault()): String =
        if (!bytesPerSecond.isFinite()) "No data" else bytes(bytesPerSecond, locale) + "/s"

    private val SIZE_FACTORS = mapOf("b" to 1.0, "byte" to 1.0, "bytes" to 1.0, "kib" to 1024.0, "kibibytes" to 1024.0,
        "mib" to 1024.0.pow(2), "mebibytes" to 1024.0.pow(2), "gib" to 1024.0.pow(3), "gibibytes" to 1024.0.pow(3), "tib" to 1024.0.pow(4))

    /**
     * A live reading in the unit the server declared. Sizes and rates (B, KiB, MiB, GiB, TiB, with "/s" or "per second")
     * are scaled; fractions and percentages become percent; anything else is a plain number followed by its unit.
     */
    fun text(value: Double?, unit: String, locale: Locale = Locale.getDefault()): String {
        if (value == null || !value.isFinite()) return "Unavailable"
        val declared = unit.trim().lowercase(Locale.ROOT)
        val perSecond = declared.endsWith("/s") || declared.endsWith(" per second")
        val base = declared.removeSuffix(" per second").removeSuffix("/s").trim()
        SIZE_FACTORS[base]?.let { factor ->
            val scaled = value * factor
            return if (!scaled.isFinite()) "Unavailable" else if (perSecond) rate(scaled, locale) else bytes(scaled, locale)
        }
        return when (declared) {
            "fraction", "ratio" -> if ((value * 100).isFinite()) percent(value * 100, locale) else "Unavailable"
            "%", "percent" -> percent(value, locale)
            "load" -> load(value, locale)
            else -> (number(value, locale) + " " + unit.trim()).trim()
        }
    }

    /** True when [text] contains no exponent notation such as "1.02e+03" (used by tests over many magnitudes). */
    fun isPlain(text: String) = !Regex("[0-9][eE][+-]?[0-9]").containsMatchIn(text)
}
