package com.meteor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Material 3 window width classes: compact < 600 dp, medium 600–839 dp, expanded >= 840 dp.
 * A Galaxy Z Fold 3 cover display is compact; its inner display is medium or expanded.
 */
enum class WidthClass {
    COMPACT, MEDIUM, EXPANDED;

    /** A navigation rail replaces the bottom bar. */
    val usesRail: Boolean get() = this != COMPACT
    /** Details render as a side pane next to their list instead of a dialog. */
    val usesPanes: Boolean get() = this != COMPACT
    /** Share of the content width given to the list when a detail pane is shown. */
    val listFraction: Float get() = if (this == EXPANDED) .4f else .46f

    companion object {
        fun of(widthDp: Float): WidthClass = when {
            widthDp.isNaN() || widthDp < 600f -> COMPACT
            widthDp < 840f -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** Width class of the app window, provided by `MeteorApp`. */
val LocalWidthClass = staticCompositionLocalOf { WidthClass.COMPACT }

/**
 * A list with an optional detail pane beside it on medium/expanded width. On compact width only
 * [list] is drawn; callers show details as a dialog there. [list] is always called from the same
 * place so its saved state (scroll position, search text) survives fold/unfold.
 */
@Composable
internal fun ListDetailLayout(showDetail: Boolean, list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    val width = LocalWidthClass.current
    val pane = width.usesPanes && showDetail
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(if (pane) width.listFraction else 1f).fillMaxHeight()) { list() }
        if (pane) {
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
            Box(Modifier.weight(1f - width.listFraction).fillMaxHeight()) { detail() }
        }
    }
}

/** Keeps single-column screens readable on wide displays. A no-op on phones. */
@Composable
internal fun ReadableWidth(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { content() }
    }
}

/** Placeholder for an empty detail pane. */
@Composable
internal fun EmptyPane(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        SpaceIcon("orbit", Modifier.size(52.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
