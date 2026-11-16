package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

class ObservabilityTest {
    private val target = MetricTarget("pool", "vm", MetricKind.VM, "Example VM")
    private val plan = LiveMetrics.plan(target, listOf(mapOf("name_label" to "cpu_usage", "enabled" to true, "units" to "(fraction)", "max" to 1.0)), null)
    private fun sample(time: Long, target: MetricTarget = this.target, value: Double? = .25) =
        LiveMetrics.snapshot(target, plan, mapOf("cpu_usage" to value), time)

    @Test fun sampleWindowIsBoundedAndMissingValuesAreRetained() {
        var window = emptyList<MetricSnapshot>()
        repeat(70) { window = MetricWindow.append(window, sample(it * 5000L)) }
        assertEquals(60, window.size)
        val missing = sample(350_000, value = null)
        window = MetricWindow.append(window, missing)
        assertNull(window.last().readings.single().value)
        assertTrue(window.all { 350_000 - it.observedAt <= 300_000 })
        assertEquals(listOf(sample(1)), MetricWindow.append(window, sample(1)))
        val other = sample(400_000, target.copy(uuid = "other"))
        assertEquals(listOf(other), MetricWindow.append(window, other))
    }
    @Test fun equalTimestampReplacesRatherThanDuplicates() {
        assertEquals(1, MetricWindow.append(listOf(sample(1)), sample(1)).size)
    }
    @Test fun compactTimestampsSupportEveryFractionWidthAndRejectInvalidDates() {
        val millis = ActivityMapper.time("2026-01-01T10:00:00.123Z")
        assertEquals(millis, ActivityMapper.time("20260101T10:00:00.123456Z"))
        assertNotNull(ActivityMapper.time("20260101T10:00:00.1"))
        assertNull(ActivityMapper.time("20260230T10:00:00Z"))
        assertNull(ActivityMapper.time("19700101T00:00:00Z"))
        assertNull(ActivityMapper.time("unavailable"))
        assertNull(ActivityMapper.time("+999999999-01-01T00:00:00Z"))
    }
    @Test fun fullProgressDoesNotMakeAPendingTaskCompleteOrExposeErrorArguments() {
        val task = ActivityMapper.tasks(mapOf("ref" to mapOf("uuid" to "id", "status" to "pending", "progress" to "1.0",
            "finished" to "20260101T10:00:00Z", "error_info" to listOf("CODE", "private argument"), "result" to "private result"))).items.single()
        assertEquals("pending", task.status); assertEquals(1.0, task.progress!!, 0.0)
        assertNull(task.finishedAt); assertNull(task.errorCode)
        assertFalse(task.toString().contains("private"))
    }
    @Test fun tasksAreBoundedWithActiveFirstAndOnlyFailureCodesSurvive() {
        val records = (0..110).associate { "ref$it" to mapOf("uuid" to "task$it", "status" to if (it == 110) "pending" else "failure",
            "progress" to "NaN", "error_info" to listOf("TASK_FAILED", "private parameter")) }
        val tasks = ActivityMapper.tasks(records)
        assertEquals(111, tasks.total); assertEquals(100, tasks.items.size)
        assertEquals("task110", tasks.items.first().uuid)
        assertTrue(tasks.items.all { it.progress == null })
        assertTrue(tasks.items.drop(1).all { it.errorCode == "TASK_FAILED" })
    }
    @Test fun alertBodiesAreBoundedAndDuplicateIdentityFailsClosed() {
        val record = mapOf("uuid" to "alert", "body" to "x".repeat(5000), "priority" to "bad")
        val alert = ActivityMapper.alerts(mapOf("ref" to record)).items.single()
        assertEquals(4096, alert.body.length); assertNull(alert.priority)
        assertFalse(alert.toString().contains("xxx"))
        assertTrue(runCatching { ActivityMapper.alerts(mapOf("a" to record, "b" to record)) }.isFailure)
    }
}
