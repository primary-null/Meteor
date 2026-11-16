package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** D2 of the first phone run: one plain, human format for every byte, rate and number in the performance and history views. */
class MetricFormatTest {
    private val us = Locale.US

    @Test fun theValueFromThePhoneRunIsOneGibNotAnExponent() {
        assertEquals("1.00 GiB", MetricFormat.text(1024.0, "MiB", us))
        assertEquals("6.00 GiB", MetricFormat.text(6.0, "GiB", us))
        assertEquals("6.00 GiB", MetricFormat.text(6.0 * 1024 * 1024 * 1024, "B", us))
        assertEquals("512 MiB", MetricFormat.text(512.0, "MiB", us))
        assertEquals("1.50 GiB/s", MetricFormat.text(1536.0, "MiB/s", us))
        assertEquals("2.00 KiB/s", MetricFormat.text(2048.0, "bytes per second", us))
    }

    @Test fun bytesAreScaledToSensibleUnitsAcrossTheRange() {
        val cases = listOf(0.0 to "0 B", 0.4 to "0 B", 1.0 to "1 B", 512.0 to "512 B", 1023.0 to "1023 B", 1024.0 to "1.00 KiB", 1536.0 to "1.50 KiB",
            10.0 * 1024 to "10.0 KiB", 99.95 * 1024 to "100 KiB", 1024.0 * 1024 to "1.00 MiB", 730.0 * 1024 * 1024 to "730 MiB",
            3_211_944.0 * 1024 to "3.06 GiB", 1024.0.let { it * it * it * it } to "1.00 TiB", 1024.0.let { it * it * it * it * it } to "1.00 PiB",
            1024.0.let { it * it * it * it * it * it } to "1.00 EiB", 5000.0 * 1024.0.let { it * it * it * it * it * it } to "5,000 EiB")
        cases.forEach { (bytes, text) -> assertEquals("$bytes", text, MetricFormat.bytes(bytes, us)) }
        assertEquals("-1.50 KiB", MetricFormat.bytes(-1536.0, us))
    }

    @Test fun aValueThatRoundsUpToTheNextUnitMovesToIt() {
        assertEquals("1.00 MiB", MetricFormat.bytes(1024.0 * 1024 - 1, us))
        assertEquals("1.00 GiB", MetricFormat.bytes(1024.0 * 1024 * 1024 - 100, us))
        assertEquals("1023 KiB", MetricFormat.bytes(1023.4 * 1024, us))
    }

    @Test fun plainNumbersKeepAboutThreeSignificantDigitsAndNeverUseAnExponent() {
        val cases = listOf(0.0 to "0", 0.5 to "0.500", 0.12345 to "0.123", 0.0123 to "0.0123", 0.00123 to "0.0012", 0.00004 to "<0.0001", 1.0 to "1.00", 1.234 to "1.23",
            12.34 to "12.3", 123.4 to "123", 1234.4 to "1,234", 1024.0 to "1,024", 12345678.9 to "12,345,679", 1.0e15 to "1,000,000,000,000,000",
            -0.00004 to "0", -2.5 to "-2.50")
        cases.forEach { (value, text) -> assertEquals("$value", text, MetricFormat.number(value, us)) }
        assertEquals("No data", MetricFormat.number(Double.NaN, us)); assertEquals("No data", MetricFormat.number(Double.POSITIVE_INFINITY, us))
    }

    @Test fun percentAndLoadFollowTheSameRule() {
        assertEquals("0.538%", MetricFormat.text(0.005382667295634747, "fraction", us))
        assertEquals("25.6%", MetricFormat.percent(25.6, us))
        assertEquals("100%", MetricFormat.text(1.0, "fraction", us))
        assertEquals("12.5%", MetricFormat.text(12.5, "%", us))
        assertEquals("0%", MetricFormat.percent(0.0, us))
        assertEquals("0.15", MetricFormat.text(0.1500000059604645, "load", us))
        assertEquals("2.50", MetricFormat.load(2.5, us))
    }

    @Test fun unknownUnitsAndMissingValuesAreWordedPlainly() {
        assertEquals("3.00 packets", MetricFormat.text(3.0, "packets", us))
        assertEquals("1,500 ms", MetricFormat.text(1500.0, "ms", us))
        assertEquals("42.0", MetricFormat.text(42.0, "", us))
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) assertEquals("Unavailable", MetricFormat.text(value, "MiB", us))
        assertEquals("Unavailable", MetricFormat.text(Double.MAX_VALUE, "KiB", us))
    }

    @Test fun noMagnitudeOrUnitEverProducesAnExponent() {
        val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "B/s", "KiB/s", "MiB/s", "GiB/s", "bytes", "bytes per second", "fraction", "ratio", "%", "percent", "load", "", "widgets", "packets/s")
        val values = listOf(0.0, -0.0, 1e-300, 1e-12, 1e-6, 0.0004, 0.004, 0.04, 0.4, 0.99999, 1.0, 9.995, 99.95, 999.5, 1023.9, 1024.0, 1e4, 1e6, 1e9, 1e12, 1e15, 1e18, 1e21, 1e30, 1e100, 1e300,
            -1e-6, -1.0, -1e9, -1e21)
        for (unit in units) for (value in values) {
            val text = MetricFormat.text(value, unit, us)
            assertTrue("$value $unit -> $text", MetricFormat.isPlain(text))
            assertFalse("$value $unit -> $text", text.contains("NaN") || text.contains("Infinity"))
        }
        for (value in values) {
            assertTrue(MetricFormat.isPlain(MetricFormat.bytes(value, us))); assertTrue(MetricFormat.isPlain(MetricFormat.rate(value, us)))
            assertTrue(MetricFormat.isPlain(MetricFormat.number(value, us))); assertTrue(MetricFormat.isPlain(MetricFormat.percent(value, us)))
            assertTrue(MetricFormat.isPlain(MetricFormat.load(value, us)))
            for (unit in HistoryUnit.entries) assertTrue(HistoryFormat.value(value, unit, us), MetricFormat.isPlain(HistoryFormat.value(value, unit, us)))
        }
        // The detector itself recognises what it is meant to catch.
        assertFalse(MetricFormat.isPlain("1.02e+03 MiB")); assertFalse(MetricFormat.isPlain("5E10")); assertTrue(MetricFormat.isPlain("1,024 MiB"))
    }

    @Test fun theHistoryViewUsesTheSameFormatAsTheLiveView() {
        val pairs = listOf(1536.0 to "B", 3.0 * 1024 * 1024 * 1024 to "B", 0.256 to "fraction", 2048.0 to "B/s", 0.42 to "load")
        val units = mapOf("B" to HistoryUnit.BYTES, "fraction" to HistoryUnit.FRACTION, "B/s" to HistoryUnit.BYTES_PER_SECOND, "load" to HistoryUnit.LOAD)
        pairs.forEach { (value, unit) -> assertEquals(MetricFormat.text(value, unit, us), HistoryFormat.value(value, units.getValue(unit), us)) }
        assertEquals("1.00 GiB", HistoryFormat.size(1024L * 1024 * 1024, us))
    }
}
