package com.meteor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.meteor.app.data.Appearance

/** Applies the saved appearance (mode and accent) and matching system-bar icon colors. */
@Composable
internal fun MeteorTheme(appearance: Appearance, content: @Composable (dark: Boolean) -> Unit) {
    val dark = when (appearance.mode) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    val accent = when (appearance.accent) {
        "slate" -> if (dark) Color(0xFFA6BBCB) else Color(0xFF496579)
        "green" -> if (dark) Color(0xFF9EC7AC) else Color(0xFF356E4A)
        "silver" -> if (dark) Color(0xFFD1D6DE) else Color(0xFF525C6B)
        else -> if (dark) Color(0xFFF1A18F) else Color(0xFF9D4535)
    }
    val colors = if (dark) darkColorScheme(primary = accent, secondary = accent, tertiary = accent,
        primaryContainer = lerp(Color(0xFF15191F), accent, .22f), onPrimaryContainer = accent,
        secondaryContainer = lerp(Color(0xFF15191F), accent, .18f), onSecondaryContainer = accent,
        background = Color(0xFF0B0D12), surface = Color(0xFF15191F), surfaceVariant = Color(0xFF303841),
        surfaceContainer = Color(0xFF1B2027), surfaceContainerHigh = Color(0xFF242A32), surfaceTint = accent,
        onPrimary = Color(0xFF171B20))
    else lightColorScheme(primary = accent, secondary = accent, tertiary = accent,
        primaryContainer = lerp(Color(0xFFFAF9F6), accent, .15f), onPrimaryContainer = accent,
        secondaryContainer = lerp(Color(0xFFFAF9F6), accent, .13f), onSecondaryContainer = accent,
        background = Color(0xFFF2F1ED), surface = Color(0xFFFAF9F6),
        surfaceVariant = Color(0xFFE1E3DF), surfaceContainer = Color(0xFFE9E9E5),
        surfaceContainerHigh = Color(0xFFE9E9E5), surfaceTint = accent, onPrimary = Color.White)
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors) { content(dark) }
}

/** Optional decorative stars and orbit lines behind the content. */
@Composable
internal fun OrbitalTexture(dark: Boolean) {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize().alpha(if (dark) .12f else .07f)) {
        repeat(64) { index ->
            val x = ((index * 137 + 23) % 997) / 997f * size.width
            val y = ((index * 211 + 79) % 991) / 991f * size.height
            drawCircle(ink, if (index % 7 == 0) 2f else 1f, Offset(x, y))
        }
        drawCircle(ink, size.width * .72f, Offset(size.width * .9f, size.height * .35f), style = Stroke(1f))
        drawCircle(ink, size.width * .9f, Offset(size.width * .9f, size.height * .35f), style = Stroke(1f))
    }
}
