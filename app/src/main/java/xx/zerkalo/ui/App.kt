package xx.zerkalo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import xx.zerkalo.R
import xx.zerkalo.settings.AppSettings
import xx.zerkalo.settings.Role

private enum class Screen { HOME, SETTINGS }

/** The app's two screens. */
@Composable
fun App(onAbout: () -> Unit) {
    // Saveable: a new language recreates the activity under the open settings.
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.HOME }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (screen) {
                Screen.HOME -> HomeScreen(onSettings = { screen = Screen.SETTINGS })
                Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME }, onAbout = onAbout)
            }
        }
    }
}

/** The role's own screen. Both are placeholders until the network and video stages land. */
@Composable
private fun HomeScreen(onSettings: () -> Unit) {
    val role by AppSettings.role.collectAsState()
    val settings = stringResource(R.string.settings)
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = stringResource(role.labelRes()),
            action = BarAction(Icons.Filled.Settings, settings, onSettings),
            iconFirst = false,
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.not_ready), style = MaterialTheme.typography.titleLarge)
        }
    }
}

fun Role.labelRes(): Int = when (this) {
    Role.RECEIVER -> R.string.role_receiver
    Role.SENDER -> R.string.role_sender
}
