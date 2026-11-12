package com.meteor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmTextTest {
    private fun body(value: String, metric: String?, level: String? = null) = buildString {
        append("value: ").append(value).append("\nconfig:\n<variable>\n")
        metric?.let { append("\t<name value=\"").append(it).append("\"/>\n") }
        level?.let { append("\t<alarm_trigger_level value=\"").append(it).append("\"/>\n") }
        append("\t<alarm_trigger_period value=\"60\"/>\n</variable>")
    }

    @Test fun cpuUsageIsShownAsPercentWithTheAlarmLevel() =
        assertEquals("CPU usage 97% (alarm level 90%)", AlarmText.summary("ALARM", body("0.97", "cpu_usage", "0.9")))

    @Test fun freeMemoryIsShownInBinaryUnitsWithABelowThreshold() =
        assertEquals("Free memory 512.0 MiB (alarm below 1.0 GiB)",
            AlarmText.summary("alarm", body("524288", "memory_free_kib", "1048576")))

    @Test fun throughputMetricsGetPerSecondUnits() {
        assertEquals("Network throughput 2.0 MiB/s", AlarmText.summary("ALARM", body("2097152", "network_usage")))
        assertEquals("Storage throughput 120.0 MiB/s", AlarmText.summary("ALARM", body("120", "sr_io_throughput_total_abcd1234")))
    }

    @Test fun missingMetricStillShowsTheValue() =
        assertEquals("Monitored value 118", AlarmText.summary("ALARM", "value: 118\nconfig:\n<variable>"))

    @Test fun unknownMetricKeepsItsServerName() =
        assertEquals("custom_metric 0.5", AlarmText.summary("ALARM", body("0.5", "custom_metric")))

    @Test fun otherMessagesAndMalformedBodiesAreLeftAlone() {
        assertNull(AlarmText.summary("HA_HOST_FAILED", body("0.97", "cpu_usage")))
        assertNull(AlarmText.summary("ALARM", "no value here"))
        assertNull(AlarmText.summary("ALARM", "value: NaN"))
    }
}
