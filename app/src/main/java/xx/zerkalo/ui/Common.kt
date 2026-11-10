package xx.zerkalo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import xx.zerkalo.settings.ThemeMode

// ---------- Theme ----------

// The same window tones as in the other apps built on this skeleton.
val WindowLight = Color(0xFFF1F2F4)
val WindowDark = Color(0xFF121212)

private val Accent = Color(0xFF1E88E5)

private fun scheme(dark: Boolean): ColorScheme {
    val base = if (dark) {
        darkColorScheme(background = WindowDark, surface = WindowDark)
    } else {
        lightColorScheme(background = WindowLight, surface = WindowLight)
    }
    return base.copy(
        primary = Accent,
        onPrimary = Color.White,
        secondaryContainer = Accent,
        onSecondaryContainer = Color.White,
        surfaceTint = Accent,
    )
}

/** Free of Compose, so the activity can ask it before the first frame. */
fun isDarkTheme(mode: ThemeMode, systemInDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemInDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun ZerkaloTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme(isDarkTheme(themeMode, isSystemInDarkTheme())), content = content)
}

// ---------- Top bar ----------

/** An icon in the top bar. */
class BarAction(val icon: ImageVector, val description: String, val onClick: () -> Unit)

/**
 * The title with one icon, before it when [iconFirst] and after it otherwise, and
 * optionally an [extra] icon at the far end. The first icon
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
        if (iconFirst) icon()
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        if (!iconFirst) icon()
        extra?.let {
            IconButton(onClick = it.onClick) { Icon(it.icon, contentDescription = it.description) }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
