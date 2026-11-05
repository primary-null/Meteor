package com.meteor.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meteor.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class JournalDeviceTest {
    @Test fun durableOriginAndCorruptionPreservation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "instrumentation-journal-test.json"
        val file = File(context.filesDir, name)
        try {
            val op = PowerOperation("id", "profile", "origin", "pool", "vm", "Guest", PowerAction.REBOOT,
                "20260101T00:00:00Z", PowerPhase.TRACKING, 1, 2, "ref", "uuid", "Tracking")
            OperationJournal(context, name).put(op)
            assertEquals(op, OperationJournal(context, name).load().single())
            val reviewed = op.copy(phase = PowerPhase.UNKNOWN,
                manualResolution = ManualResolution(3, "a".repeat(64), "Running", "20260101T01:00:00Z"))
            OperationJournal(context, name).put(reviewed)
            val restored = OperationJournal(context, name).load().single()
            assertEquals(reviewed, restored); assertFalse(restored.unresolved)
            assertEquals(op.origin, restored.origin); assertEquals(op.taskUuid, restored.taskUuid)
            val corrupt = "corrupted-history".toByteArray()
            file.writeBytes(corrupt)
            assertTrue(runCatching { OperationJournal(context, name).load() }.isFailure)
            assertTrue(runCatching { OperationJournal(context, name).put(op) }.isFailure)
            assertArrayEquals(corrupt, file.readBytes())
        } finally { file.delete() }
    }
}
