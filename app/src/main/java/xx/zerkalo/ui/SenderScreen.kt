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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import xx.zerkalo.PIN_LENGTH
import xx.zerkalo.R
import xx.zerkalo.net.isValidPin
import xx.zerkalo.sender.ConnectTarget
import xx.zerkalo.sender.SenderMode
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
    val mode by model.mode.collectAsState()
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
        if (uri != null) model.pickFile(uri) else model.releaseTarget()
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
    val choose = { target: ConnectTarget ->
        if (mode == SenderMode.FILE) {
            model.holdTarget(target)
            pickFile.launch(arrayOf("*/*"))
        } else {
            model.connect(target)
        }
    }
    val listShown = status !is SenderStatus.Connected && status !is SenderStatus.Connecting
    panel?.let { ActivePanel(it) } ?: Column(Modifier.fillMaxSize()) {
        if (listShown) ModeSwitch(mode, model::setMode)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
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
                    if (mode == SenderMode.FILE) {
                        Text(stringResource(R.string.file_pick_receiver), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (rows.isEmpty()) SearchingHint()
                    rows.forEach { receiver -> ReceiverCard(receiver, onClick = { choose(receiver) }) }
                }
            }
        }
        if (listShown) {
            TonalButton(
                onClick = { enteringAddress = true },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(stringResource(R.string.enter_address), maxLines = 1)
            }
        }
    }

    when (val current = status) {
        is SenderStatus.NeedPin -> PinDialog(current.name, current.wrong, model::submitPin, model::cancelPin)
        is SenderStatus.Failed -> AlertDialog(
            onDismissRequest = model::dismissFailure,
            text = { Text(stringResource(current.message), style = MaterialTheme.typography.titleMedium) },
            confirmButton = {
                TonalButton(onClick = model::dismissFailure) { Text(stringResource(R.string.ok)) }
            },
        )
        else -> Unit
    }
    if (enteringAddress) {
        AddressDialog(
            onSubmit = {
                enteringAddress = false
                choose(model.targetAt(it))
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

private val PANEL_BUTTON_WIDTH = 200.dp

/** In place of the list while nothing has been found. */
@Composable
private fun SearchingHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.searching), style = MaterialTheme.typography.titleMedium)
    }
}

/** One receiver; the dot is lit if it is on the network right now. */
@Composable
private fun ReceiverCard(receiver: ConnectTarget, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_tv),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(receiver.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(receiver.host, style = MaterialTheme.typography.bodySmall)
            }
            val dot = if (receiver.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(10.dp).background(dot, CircleShape))
        }
    }
}

/** Decides what choosing a receiver does. */
@Composable
private fun ModeSwitch(mode: SenderMode, onChange: (SenderMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeButton(stringResource(R.string.mode_mirror), mode == SenderMode.MIRROR) { onChange(SenderMode.MIRROR) }
        ModeButton(stringResource(R.string.mode_file), mode == SenderMode.FILE) { onChange(SenderMode.FILE) }
    }
}

@Composable
private fun RowScope.ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
    } else {
        TonalButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
    }
}

private class ActivePanelText(val title: String, val name: String, val onStop: () -> Unit)

/** Fills the screen while the phone is working for a receiver, so there is no doubt that it is. */
@Composable
private fun ActivePanel(text: ActivePanelText) {
    val activity = LocalActivity.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_cast),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
            Text(text.title, style = MaterialTheme.typography.headlineMedium)
            Text(text.name, style = MaterialTheme.typography.titleLarge)
            Button(onClick = { activity?.moveTaskToBack(true) }, modifier = Modifier.widthIn(min = PANEL_BUTTON_WIDTH)) {
                Text(stringResource(R.string.minimize))
            }
        }
        // Apart from Minimize, so ending the session takes a deliberate reach.
        TonalButton(onClick = text.onStop, modifier = Modifier.widthIn(min = PANEL_BUTTON_WIDTH)) {
            Text(stringResource(R.string.stop))
        }
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
            TonalButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
            TonalButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
