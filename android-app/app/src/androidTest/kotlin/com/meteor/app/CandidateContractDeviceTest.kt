package com.meteor.app

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meteor.app.data.ProfileVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CandidateContractDeviceTest {
    @Test fun installedCandidateIsNonDebuggableAndIgnoresBootstrap() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.meteor.app.candidate", context.packageName)
        assertEquals("candidate", BuildConfig.BUILD_TYPE)
        assertFalse(BuildConfig.DEBUG)
        assertTrue(BuildConfig.VERSION_NAME.endsWith("-rc2"))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val bootstrap = File(context.filesDir, "bootstrap.json")
        assumeFalse("An existing bootstrap fixture must be preserved", bootstrap.exists())
        try {
            bootstrap.writeText("not-a-profile")
            assertFalse(ProfileVault(context, "instrumentation-contract.secured", "meteor.instrumentation.contract").importDebugBootstrap(context))
            assertEquals("not-a-profile", bootstrap.readText())
        } finally { bootstrap.delete() }
    }
}
