package xx.zerkalo.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import xx.zerkalo.PIN_LENGTH
import xx.zerkalo.R
import xx.zerkalo.net.isValidPin
import xx.zerkalo.sender.SenderModel
import xx.zerkalo.sender.SenderService
import xx.zerkalo.sender.SenderStatus
import xx.zerkalo.sender.receiverRows
import xx.zerkalo.settings.PairedDevices

/** The phone's screen: the receivers around, and the connection to one of them. */
@Composable
fun SenderScreen(model: SenderModel) {
    LifecycleStartEffect(model) {
        model.startDiscovery()
        onStopOrDispose { model.stopDiscovery() }
    }
    val found by model.found.collectAsState()
    val remembered by PairedDevices.receivers.devices.collectAsState()
    val rows = remember(found, remembered) { receiverRows(found, remembered) }
    val status by model.status.collectAsState()
    val fileToSend by model.fileToSend.collectAsState()
    val servingFile by model.servingFile.collectAsState()
    var enteringAddress by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            SenderService.start(context, result.resultCode, data)
        } else {
            model.disconnect()
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::pickFile)
    }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Mirroring works either way; without them there is no sound or no Stop button in the shade.
    }
    // On its own and early, so these dialogs never compete with the capture one.
    LaunchedEffect(Unit) {
        val missing = missingPermissions(context)
        if (missing.isNotEmpty()) askPermissions.launch(missing)
    }
    // Keyed on the status itself: each new connection asks for capture once.
    LaunchedEffect(status) {
        if (status is SenderStatus.Connected) {
            if (model.fileToSend.value != null) model.startFile() else capture.launch(screenCaptureIntent(context))
        }
    }

    val panel = servingFile?.let { ActivePanelText(stringResource(R.string.serving_file), it, model::stopServing) }
        ?: (status as? SenderStatus.Streaming)?.let { ActivePanelText(stringResource(R.string.mirroring), it.name, model::disconnect) }
    panel?.let { ActivePanel(it) } ?: Column(
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
                if (rows.isEmpty()) {
                    Text(stringResource(R.string.searching), style = MaterialTheme.typography.titleLarge)
                }
                rows.forEach { receiver ->
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
                val chosen = fileToSend
                if (chosen == null) {
                    FilledTonalButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                        Text(stringResource(R.string.send_file))
                    }
                } else {
                    Text(stringResource(R.string.file_pick_receiver), style = MaterialTheme.typography.titleMedium)
                    Text(chosen.name, style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = model::cancelFile) { Text(stringResource(R.string.cancel)) }
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

/** What mirroring would like and does not have yet. */
private fun missingPermissions(context: Context): Array<String> {
    val wanted = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.RECORD_AUDIO)
    }
    return wanted.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
}

private class ActivePanelText(val title: String, val name: String, val onStop: () -> Unit)

/** Fills the screen while the phone is working for a receiver, so there is no doubt that it is. */
@Composable
private fun ActivePanel(text: ActivePanelText) {
    val activity = LocalActivity.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Text(text.title, style = MaterialTheme.typography.headlineMedium)
        Text(text.name, style = MaterialTheme.typography.titleLarge)
        Button(onClick = { activity?.moveTaskToBack(true) }) { Text(stringResource(R.string.minimize)) }
        FilledTonalButton(onClick = text.onStop) { Text(stringResource(R.string.stop)) }
    }
}

/** The system's screen capture dialog; offers the whole screen only where that can be asked for. */
private fun screenCaptureIntent(context: Context): Intent {
    val manager = context.getSystemService(MediaProjectionManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        manager.createScreenCaptureIntent()
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
