package com.meteor.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meteor.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A2 operations: the real AtomicFile/org.json journal reads 1.9 files and keeps unknown kinds locked. */
@RunWith(AndroidJUnit4::class)
class JournalCompatibilityDeviceTest {
    private val origin = "a".repeat(64)
    private val legacy = """{"version":1,"operations":[{"id":"r","profile":"p","origin":"$origin","authority":"pool","vm":"vm","name":"Guest",""" +
        """"action":"REBOOT","beforeBoot":"20260101T10:00:00Z","phase":"TRACKING","createdAt":1,"updatedAt":2,"taskRef":"ref","taskUuid":"uuid",""" +
        """"message":"Waiting for server task…"},{"id":"c","profile":"p","origin":"$origin","authority":"pool","vm":"vm","name":"Guest",""" +
        """"action":"START","beforeBoot":"","phase":"UNKNOWN","createdAt":1,"updatedAt":3,"taskRef":"","taskUuid":"","message":"Closed",""" +
        """"manualResolution":{"reviewedAt":3,"origin":"$origin","power":"Running","bootTime":""}},{"id":"f","profile":"p","origin":"$origin",""" +
        """"authority":"pool","vm":"vm","name":"Guest","action":"SNAPSHOT_REVERT","phase":"TRACKING","createdAt":1,"updatedAt":4,"snapshot":{"uuid":"s"}}]}"""

    @Test fun legacyAndForeignRecordsSurviveRealFileRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "instrumentation-journal-compat.json"
        val file = File(context.filesDir, name)
        try {
            file.writeText(legacy)
            val loaded = OperationJournal(context, name).load().associateBy { it.id }
            assertEquals(PowerAction.REBOOT, loaded.getValue("r").action); assertTrue(loaded.getValue("r").unresolved)
            assertFalse(loaded.getValue("c").unresolved); assertNotNull(loaded.getValue("c").manualResolution)
            assertEquals(PowerAction.UNRECOGNIZED, loaded.getValue("f").action); assertTrue(loaded.getValue("f").unresolved)
            val migrate = PowerOperation("m", "p", origin, "pool", "vm", "Guest", PowerAction.POOL_MIGRATE, null, PowerPhase.SUCCEEDED, 5, 5,
                "ref2", "uuid2", "Migrate to host verified: Running on Host B.", targetHostUuid = "host-b", targetHostName = "Host B")
            OperationJournal(context, name).put(migrate)
            val again = OperationJournal(context, name).load().associateBy { it.id }
            assertEquals(migrate, again.getValue("m"))
            assertEquals(loaded.getValue("r"), again.getValue("r"))
            val raw = JSONObject(file.readText()).getJSONArray("operations")
            val foreign = (0 until raw.length()).map { raw.getJSONObject(it) }.single { it.getString("id") == "f" }
            assertEquals("SNAPSHOT_REVERT", foreign.getString("action")); assertEquals("s", foreign.getJSONObject("snapshot").getString("uuid"))
        } finally { file.delete() }
    }
}
