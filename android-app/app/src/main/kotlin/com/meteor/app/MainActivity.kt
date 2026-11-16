package com.meteor.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import com.meteor.app.data.NotificationTaps

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[MeteorViewModel::class.java]
        // --- A4 alerts/events --- Live events run only while this activity is at least STARTED.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.setForeground(true)
                Lifecycle.Event.ON_STOP -> model.setForeground(false)
                else -> Unit
            }
        })
        // --- end A4 ---
        // --- B3 background/summary --- A notification tap opens Alerts, once per tap: a recreated activity applies its launch
        // intent only when it is a different tap from the one already applied (see NotificationTaps).
        appliedTap = savedInstanceState?.getLong(STATE_APPLIED_TAP)?.takeIf { it > 0 }
        val stamp = NotificationIntents.stamp(intent)
        if (NotificationTaps.applyOnCreate(stamp, savedInstanceState != null, appliedTap)) {
            NotificationIntents.profileId(intent)?.let(model::openAlertsFor)
            appliedTap = stamp ?: appliedTap
        }
        // --- end B3 ---
        setContent { MeteorApp(model) }
    }

    // --- B3 background/summary ---
    private var appliedTap: Long? = null

    /** A delivered new intent is always a new tap. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationIntents.profileId(intent)?.let(ViewModelProvider(this)[MeteorViewModel::class.java]::openAlertsFor)
        appliedTap = NotificationIntents.stamp(intent) ?: appliedTap
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        appliedTap?.let { outState.putLong(STATE_APPLIED_TAP, it) }
    }

    private companion object { const val STATE_APPLIED_TAP = "com.meteor.app.state.APPLIED_TAP" }
    // --- end B3 ---
}
