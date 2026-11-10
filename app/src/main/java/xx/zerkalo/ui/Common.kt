package xx.zerkalo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import xx.zerkalo.ACCENT_COUNT
import xx.zerkalo.settings.ThemeMode

// ---------- Theme ----------

// The same window tones as in the other apps built on this skeleton.
val WindowLight = Color(0xFFF1F2F4)
val WindowDark = Color(0xFF121212)

/**
 * Accent choices, the same six as in the other apps built on this skeleton: mid-tones
 * that hold contrast on both windows and carry white text as a button fill.
 */
val AccentPalette = listOf(
    Color(0xFF00897B), // teal
    Color(0xFF1E88E5), // blue
    Color(0xFF5C6BC0), // indigo
    Color(0xFF8E24AA), // purple
    Color(0xFFEF6C00), // orange
    Color(0xFFE53935), // red
).also { check(it.size == ACCENT_COUNT) }

fun accentAt(index: Int): Color = AccentPalette[index.coerceIn(AccentPalette.indices)]

/** How much of the accent a container takes over the window it lies on. */
private const val ACCENT_WASH_LIGHT = 0.18f
private const val ACCENT_WASH_DARK = 0.45f

/** The theme's colours, with every one Material would paint its own lilac in taken from [accent] instead. */
internal fun accentScheme(dark: Boolean, accent: Color): ColorScheme {
    val base = if (dark) {
        darkColorScheme(background = WindowDark, surface = WindowDark)
    } else {
        lightColorScheme(background = WindowLight, surface = WindowLight)
    }
    val container = if (dark) lerp(base.surfaceContainerHigh, accent, ACCENT_WASH_DARK) else lerp(Color.White, accent, ACCENT_WASH_LIGHT)
    val onContainer = if (dark) Color.White else base.onSurface
    return base.copy(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = accent,
        secondary = accent,
        onSecondary = Color.White,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = accent,
        onTertiary = Color.White,
        tertiaryContainer = container,
        onTertiaryContainer = onContainer,
        surfaceTint = accent,
    )
}

/** Free of Compose, so the activity can ask it before the first frame. */
fun isDarkTheme(mode: ThemeMode, systemInDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemInDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun ZerkaloTheme(themeMode: ThemeMode, accentIndex: Int, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = accentScheme(isDarkTheme(themeMode, isSystemInDarkTheme()), accentAt(accentIndex)), content = content)
}

/** One accent colour, ringed when it is the one in force. */
@Composable
fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color = color, shape = CircleShape)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

// ---------- Buttons ----------

/** A secondary button. Its fill is only a pale wash of the accent, so the outline carries the colour. */
@Composable
fun TonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        content = content,
    )
}

// ---------- Top bar ----------

/** An icon in the top bar. */
class BarAction(val icon: ImageVector, val description: String, val onClick: () -> Unit)

/**
 * The title with one icon, before it when [iconFirst] and after it otherwise, and
 * optionally an [extra] icon at the start of the bar. The first icon
 * takes the focus when the bar appears, so a TV remote has somewhere to start from.
 */
@Composable
fun TopBar(title: String, action: BarAction, iconFirst: Boolean, extra: BarAction? = null) {
    val focus = remember { FocusRequester() }
    val icon = @Composable {
        IconButton(onClick = action.onClick, modifier = Modifier.focusRequester(focus)) {
            Icon(action.icon, contentDescription = action.description)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        extra?.let {
            IconButton(onClick = it.onClick) { Icon(it.icon, contentDescription = it.description) }
        }
        if (iconFirst) icon()
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        if (!iconFirst) icon()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
