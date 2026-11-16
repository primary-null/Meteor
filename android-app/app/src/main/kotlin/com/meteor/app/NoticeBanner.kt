package com.meteor.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
/** [dismiss] is null for a persistent status that goes away only when its cause does. */
internal fun NoticeBanner(notice: UiNotice, dismiss: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (notice.kind) {
        NoticeKind.INFO -> colors.surfaceContainerHigh to colors.onSurface
        NoticeKind.WARNING -> colors.tertiaryContainer to colors.onTertiaryContainer
        NoticeKind.ERROR -> colors.errorContainer to colors.onErrorContainer
    }
    Surface(color = background, contentColor = foreground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(notice.kind.title, style = MaterialTheme.typography.labelMedium)
            Text(notice.text, style = MaterialTheme.typography.bodySmall)
            if (dismiss != null) TextButton(onClick = dismiss) { Text("Dismiss", color = foreground) }
        }
    }
}
