package com.meteor.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.random.Random

private val messages = listOf(
    "Keep every spark in sight.",
    "Your universe. Under control.",
    "From a spark to a system.",
    "Small sparks. Stellar possibilities.",
    "A calmer view of your cluster.",
    "See the whole constellation.",
    "Every host has a place in orbit.",
    "Small details. Clearer skies.",
    "Keep your constellation close.",
    "A little clarity for your cluster.",
    "Find your bearings among the stars.",
    "Room for every constellation.",
    "One orbit. A wider view.",
    "Give your servers some space.",
)

/** Decorative copy, independent of inventory polling and operational notices. */
@Composable
internal fun AmbientMessage(canRotate: Boolean, modifier: Modifier = Modifier) {
    var index by rememberSaveable { mutableIntStateOf(Random.nextInt(messages.size)) }
    var changes by rememberSaveable { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, canRotate, changes) {
        if (canRotate && changes < 2) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Only an uninterrupted quiet interval on Overview counts. Never catch up
                // after backgrounding, and stop changing after two updates this session.
                delay(90_000)
                index = (index + 1) % messages.size
                changes++
            }
        }
    }
    Text(messages[index], modifier = modifier, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
