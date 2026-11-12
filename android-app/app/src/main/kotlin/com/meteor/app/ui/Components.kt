package com.meteor.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.data.Guest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun PageTitle(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/**
 * Titled, scrollable detail surface used both as a side pane and inside a full-screen dialog.
 * It has no height cap: the content scrolls within whatever height the window gives it.
 */
@Composable internal fun DetailPane(title: String, close: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable internal fun GuestIcon(guest: Guest, state: MeteorState, model: MeteorViewModel, modifier: Modifier) {
    val token = state.appearance.icons[guest.identity(state.inventory?.authority.orEmpty())] ?: "meteor"
    val bitmap by produceState<ImageBitmap?>(null, token) {
        value = if (token.startsWith("file:")) withContext(Dispatchers.IO) {
            runCatching { model.customIcons.file(token)?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() } }.getOrNull()
        } else null
    }
    val image = bitmap
    if (image != null) Image(image, "Custom VM icon", modifier.clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
    else SpaceIcon(token, modifier)
}

@Composable internal fun Fingerprint(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value.chunked(2).joinToString(":"), style = MaterialTheme.typography.bodySmall) }
    }
}

/** Decorative line drawing; it has no content description because a text label always accompanies it. */
@Composable internal fun SpaceIcon(kind: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = Stroke(1.6.dp.toPx())
        when (kind) {
            "planet", "orbit" -> { drawCircle(color, w * .28f, Offset(w * .5f, h * .5f), style = stroke); drawOval(color.copy(alpha = .7f), Offset(0f, h * .3f), Size(w, h * .4f), style = stroke) }
            "moon" -> { drawCircle(color, w * .34f, center, style = stroke); drawCircle(color.copy(alpha = .4f), w * .07f, Offset(w * .4f, h * .43f), style = stroke); drawCircle(color.copy(alpha = .4f), w * .04f, Offset(w * .61f, h * .61f), style = stroke) }
            "spark" -> { drawLine(color, Offset(w*.5f,h*.08f),Offset(w*.5f,h*.92f),stroke.width); drawLine(color,Offset(w*.08f,h*.5f),Offset(w*.92f,h*.5f),stroke.width); drawLine(color,Offset(w*.23f,h*.23f),Offset(w*.77f,h*.77f),stroke.width); drawLine(color,Offset(w*.23f,h*.77f),Offset(w*.77f,h*.23f),stroke.width) }
            else -> { drawCircle(color, w*.2f,Offset(w*.31f,h*.69f),style=stroke); drawLine(color,Offset(w*.35f,h*.46f),Offset(w*.86f,h*.12f),stroke.width); drawLine(color,Offset(w*.54f,h*.7f),Offset(w*.92f,h*.18f),stroke.width); drawLine(color.copy(alpha=.5f),Offset(w*.48f,h*.5f),Offset(w*.7f,h*.25f),stroke.width) }
        }
    }
}

internal fun bytes(value: Long): String = when {
    value >= 1L shl 40 -> "%.1f TiB".format(value / (1L shl 40).toDouble())
    value >= 1L shl 30 -> "%.1f GiB".format(value / (1L shl 30).toDouble())
    else -> "%.0f MiB".format(value / (1L shl 20).toDouble())
}
