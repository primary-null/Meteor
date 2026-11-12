package com.meteor.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider

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
        // --- B3 background/summary --- A notification tap opens Alerts; it is not re-applied after recreation.
        if (savedInstanceState == null) NotificationIntents.profileId(intent)?.let(model::openAlertsFor)
        // --- end B3 ---
        setContent { MeteorApp(model) }
    }

    // --- B3 background/summary ---
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationIntents.profileId(intent)?.let(ViewModelProvider(this)[MeteorViewModel::class.java]::openAlertsFor)
    }
    // --- end B3 ---
}
