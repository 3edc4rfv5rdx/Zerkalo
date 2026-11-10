package xx.zerkalo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import xx.zerkalo.R
import xx.zerkalo.receiver.ReceiverModel
import xx.zerkalo.sender.SenderModel
import xx.zerkalo.settings.AppSettings
import xx.zerkalo.settings.Role

private enum class Screen { HOME, SETTINGS }

/** The app's two screens. */
@Composable
fun App(sender: SenderModel, receiver: ReceiverModel, onAbout: () -> Unit) {
    // Saveable: a new language recreates the activity under the open settings.
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.HOME }

    val role by AppSettings.role.collectAsState()
    val receiverState by receiver.state.collectAsState()
    // A receiver's session ends with its screen; a sender's has to be ended here.
    LaunchedEffect(role) { if (role != Role.SENDER) sender.disconnect() }

    // The video takes the whole display. ReceiverScreen stays at the same place in the
    // composition either way: moving it would end the session it runs.
    val fullScreen = role == Role.RECEIVER && receiverState.connectedTo != null
    val insets = if (fullScreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().then(insets)) {
            when (screen) {
                Screen.HOME -> {
                    if (!fullScreen) {
                        TopBar(
                            title = stringResource(role.labelRes()),
                            action = BarAction(Icons.Filled.Settings, stringResource(R.string.settings)) {
                                screen = Screen.SETTINGS
                            },
                            iconFirst = false,
                        )
                    }
                    when (role) {
                        Role.RECEIVER -> ReceiverScreen(receiver)
                        Role.SENDER -> SenderScreen(sender)
                    }
                }
                Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME }, onAbout = onAbout)
            }
        }
    }
}

fun Role.labelRes(): Int = when (this) {
    Role.RECEIVER -> R.string.role_receiver
    Role.SENDER -> R.string.role_sender
}
