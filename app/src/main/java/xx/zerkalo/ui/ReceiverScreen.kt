package xx.zerkalo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import xx.zerkalo.R
import xx.zerkalo.receiver.ReceiverModel
import xx.zerkalo.settings.AppSettings

/** What the TV shows: who it is and the PIN to pair with it, or who is connected. */
@Composable
fun ReceiverScreen(model: ReceiverModel) {
    LifecycleStartEffect(model) {
        model.start()
        onStopOrDispose { model.stop() }
    }
    val state by model.state.collectAsState()
    val pin by model.pin.collectAsState()
    val name by AppSettings.deviceName.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        val connectedTo = state.connectedTo
        when {
            state.portTaken -> Text(stringResource(R.string.error_port), style = MaterialTheme.typography.headlineSmall)
            connectedTo != null -> Text(
                "${stringResource(R.string.connected)}: $connectedTo",
                style = MaterialTheme.typography.headlineMedium,
            )
            else -> {
                Text(name, style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.pin_label), style = MaterialTheme.typography.titleLarge)
                Text(pin, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                state.addresses.forEach { Text(it, style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}
