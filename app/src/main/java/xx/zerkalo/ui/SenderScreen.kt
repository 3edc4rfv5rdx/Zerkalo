package xx.zerkalo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import xx.zerkalo.PIN_LENGTH
import xx.zerkalo.R
import xx.zerkalo.net.isValidPin
import xx.zerkalo.sender.SenderModel
import xx.zerkalo.sender.SenderStatus

/** The phone's screen: the receivers around, and the connection to one of them. */
@Composable
fun SenderScreen(model: SenderModel) {
    LifecycleStartEffect(model) {
        model.startDiscovery()
        onStopOrDispose { model.stopDiscovery() }
    }
    val found by model.found.collectAsState()
    val status by model.status.collectAsState()
    var enteringAddress by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val current = status) {
            is SenderStatus.Connected -> {
                Text("${stringResource(R.string.connected)}: ${current.name}", style = MaterialTheme.typography.titleLarge)
                Button(onClick = model::disconnect) { Text(stringResource(R.string.disconnect)) }
            }
            is SenderStatus.Connecting -> {
                Text("${stringResource(R.string.connecting)}: ${current.name}", style = MaterialTheme.typography.titleLarge)
            }
            else -> {
                if (found.isEmpty()) {
                    Text(stringResource(R.string.searching), style = MaterialTheme.typography.titleLarge)
                }
                found.forEach { receiver ->
                    Column(
                        Modifier.fillMaxWidth().clickable { model.connect(receiver) }.padding(vertical = 10.dp),
                    ) {
                        Text(receiver.name, style = MaterialTheme.typography.titleLarge)
                        Text(receiver.host, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
                FilledTonalButton(onClick = { enteringAddress = true }) {
                    Text(stringResource(R.string.enter_address))
                }
            }
        }
    }

    when (val current = status) {
        is SenderStatus.NeedPin -> PinDialog(current.name, current.wrong, model::submitPin, model::cancelPin)
        is SenderStatus.Failed -> AlertDialog(
            onDismissRequest = model::dismissFailure,
            text = { Text(stringResource(current.message), style = MaterialTheme.typography.titleMedium) },
            confirmButton = {
                FilledTonalButton(onClick = model::dismissFailure) { Text(stringResource(R.string.ok)) }
            },
        )
        else -> Unit
    }
    if (enteringAddress) {
        AddressDialog(
            onSubmit = {
                enteringAddress = false
                model.connectByAddress(it)
            },
            onDismiss = { enteringAddress = false },
        )
    }
}

@Composable
private fun PinDialog(name: String, wrong: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value -> pin = value.filter { it in '0'..'9' }.take(PIN_LENGTH) },
                    label = { Text(stringResource(R.string.pin_from_tv)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (wrong) {
                    Text(stringResource(R.string.wrong_pin), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(pin) }, enabled = isValidPin(pin)) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = {
            FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AddressDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text(stringResource(R.string.address_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        },
        confirmButton = {
            Button(onClick = { onSubmit(address) }, enabled = address.isNotEmpty()) {
                Text(stringResource(R.string.connect))
            }
        },
        dismissButton = {
            FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
