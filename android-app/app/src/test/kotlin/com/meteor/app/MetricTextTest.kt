package com.meteor.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** How the live view words the readings a real XCP-ng 8.3 reports (units as LiveMetrics.unit spells them). */
class MetricTextTest {
    private lateinit var saved: Locale
    @Before fun fixLocale() { saved = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun restoreLocale() = Locale.setDefault(saved)

    @Test fun fractionsAreShownAsPercentages() {
        assertEquals("0.538%", metricText(0.005382667295634747, "fraction"))
        assertEquals("100%", metricText(1.0, "fraction"))
    }
    @Test fun memoryIsShownInTheLargestFittingBinaryUnit() {
        assertEquals("1.00 GiB", metricText(1_073_741_824.0, "B"))
        assertEquals("730 MiB", metricText(747_108.0, "KiB"))
        assertEquals("3.06 GiB", metricText(3_211_944.0, "KiB"))
    }
    @Test fun ratesCarryAPerSecondSuffix() {
        assertEquals("679 B/s", metricText(678.9083251953125, "B/s"))
        assertEquals("2.00 KiB/s", metricText(2.0, "KiB/s"))
        assertEquals("1.50 MiB/s", metricText(1.5, "MiB/s"))
    }
    @Test fun aLoadAverageIsAPlainNumberNotAPercentage() {
        assertEquals("0.15", metricText(0.1500000059604645, "load"))
        assertEquals("2.50", metricText(2.5, "load"))
    }
    @Test fun missingAndNonFiniteValuesAreUnavailableNeverZero() {
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY)) assertEquals("Unavailable", metricText(value, "B"))
    }
}
