package xx.zerkalo.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.updater.Updater
import xx.zerkalo.AUDIO_OFFSET_STEP_MS
import xx.zerkalo.R
import xx.zerkalo.settings.AppSettings
import xx.zerkalo.settings.PairedDevices
import xx.zerkalo.settings.PeerStore
import xx.zerkalo.settings.Role
import xx.zerkalo.settings.ThemeMode
import xx.zerkalo.settings.currentLanguageTag
import xx.zerkalo.settings.setLanguageTag
import xx.zerkalo.settings.supportedLanguages
import xx.zerkalo.video.FrameRate
import xx.zerkalo.video.Quality
import xx.zerkalo.video.VideoCodec

/** Which editor is open; only one can be at a time. */
private enum class Editing { NONE, ROLE, NAME, PAIRED, CODEC, FRAME_RATE, QUALITY, THEME, LANGUAGE }

@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val role by AppSettings.role.collectAsState()
    val themeMode by AppSettings.themeMode.collectAsState()
    val deviceName by AppSettings.deviceName.collectAsState()
    val videoCodec by AppSettings.videoCodec.collectAsState()
    val frameRate by AppSettings.frameRate.collectAsState()
    val quality by AppSettings.quality.collectAsState()
    val audioAutoSync by AppSettings.audioAutoSync.collectAsState()
    val audioOffsetMs by AppSettings.audioOffsetMs.collectAsState()

    val systemLabel = stringResource(R.string.language_system)
    val languages = remember(context, systemLabel) { supportedLanguages(context, systemLabel) }
    val languageTag = remember(context) { currentLanguageTag(context) }
    val languageLabel = languages.firstOrNull { it.tag == languageTag }?.label ?: systemLabel

    // Saveable: a new language recreates the activity under an open dialog.
    var editing by rememberSaveable { mutableStateOf(Editing.NONE) }
    // The updater keeps this flag in its own preferences file.
    var updateCheck by remember { mutableStateOf(Updater.isEnabled(context)) }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = stringResource(R.string.settings),
            action = BarAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack),
            iconFirst = true,
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SettingRow(stringResource(R.string.setting_role), stringResource(role.labelRes())) {
                editing = Editing.ROLE
            }
            HorizontalDivider()
            SettingRow(stringResource(R.string.setting_device_name), deviceName) {
                editing = Editing.NAME
            }
            HorizontalDivider()
            SettingRow(stringResource(R.string.setting_paired), "") {
                editing = Editing.PAIRED
            }
            HorizontalDivider()
            if (role == Role.SENDER) {
                SettingRow(stringResource(R.string.setting_codec), videoCodec.label) {
                    editing = Editing.CODEC
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.setting_frame_rate), frameRate.fps.toString()) {
                    editing = Editing.FRAME_RATE
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.setting_quality), stringResource(quality.labelRes())) {
                    editing = Editing.QUALITY
                }
                HorizontalDivider()
            }
            if (role == Role.RECEIVER) {
                SwitchRow(stringResource(R.string.setting_audio_auto_sync), audioAutoSync) {
                    AppSettings.setAudioAutoSync(context, it)
                }
                HorizontalDivider()
                AudioOffsetRow(audioOffsetMs) { AppSettings.setAudioOffsetMs(context, it) }
                HorizontalDivider()
            }
            SettingRow(stringResource(R.string.setting_theme), stringResource(themeMode.labelRes())) {
                editing = Editing.THEME
            }
            HorizontalDivider()
            SettingRow(stringResource(R.string.setting_language), languageLabel) {
                editing = Editing.LANGUAGE
            }
            HorizontalDivider()
            SwitchRow(stringResource(R.string.setting_update_check), updateCheck) {
                updateCheck = it
                Updater.setEnabled(context, it)
            }
            HorizontalDivider()
            SettingRow(stringResource(R.string.about), "", onAbout)
        }
    }

    when (editing) {
        Editing.ROLE -> ChoiceDialog(
            title = stringResource(R.string.setting_role),
            options = Role.entries,
            selected = role,
            label = { stringResource(it.labelRes()) },
            onDismiss = { editing = Editing.NONE },
            onPick = {
                AppSettings.setRole(context, it)
                editing = Editing.NONE
            },
        )

        Editing.NAME -> NameDialog(
            initial = deviceName,
            onSave = {
                AppSettings.setDeviceName(context, it)
                editing = Editing.NONE
            },
            onDismiss = { editing = Editing.NONE },
        )

        Editing.PAIRED -> PairedDialog(
            store = if (role == Role.RECEIVER) PairedDevices.senders else PairedDevices.receivers,
            onDismiss = { editing = Editing.NONE },
        )

        Editing.CODEC -> ChoiceDialog(
            title = stringResource(R.string.setting_codec),
            options = VideoCodec.entries,
            selected = videoCodec,
            label = { it.label },
            onDismiss = { editing = Editing.NONE },
            onPick = {
                AppSettings.setVideoCodec(context, it)
                editing = Editing.NONE
            },
        )

        Editing.FRAME_RATE -> ChoiceDialog(
            title = stringResource(R.string.setting_frame_rate),
            options = FrameRate.entries,
            selected = frameRate,
            label = { it.fps.toString() },
            onDismiss = { editing = Editing.NONE },
            onPick = {
                AppSettings.setFrameRate(context, it)
                editing = Editing.NONE
            },
        )

        Editing.QUALITY -> ChoiceDialog(
            title = stringResource(R.string.setting_quality),
            options = Quality.entries,
            selected = quality,
            label = { stringResource(it.labelRes()) },
            onDismiss = { editing = Editing.NONE },
            onPick = {
                AppSettings.setQuality(context, it)
                editing = Editing.NONE
            },
        )

        Editing.THEME -> ChoiceDialog(
            title = stringResource(R.string.setting_theme),
            options = ThemeMode.entries,
            selected = themeMode,
            label = { stringResource(it.labelRes()) },
            onDismiss = { editing = Editing.NONE },
            onPick = {
                AppSettings.setThemeMode(context, it)
                editing = Editing.NONE
            },
        )

        Editing.LANGUAGE -> ChoiceDialog(
            title = stringResource(R.string.setting_language),
            options = languages,
            selected = languages.firstOrNull { it.tag == languageTag },
            label = { it.label },
            onDismiss = { editing = Editing.NONE },
            onPick = {
                editing = Editing.NONE
                // Persisted, and the activity recreated in the new language.
                activity?.let { a -> setLanguageTag(a, it.tag) }
            },
        )

        Editing.NONE -> Unit
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Buttons and not a slider: a TV remote gets one exact step per press. */
@Composable
private fun AudioOffsetRow(offset: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.setting_audio_offset),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = { onChange(offset - AUDIO_OFFSET_STEP_MS) }) { Text("\u2212", maxLines = 1) }
        Text(
            text = "${if (offset > 0) "+" else ""}$offset ${stringResource(R.string.unit_ms)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        FilledTonalButton(onClick = { onChange(offset + AUDIO_OFFSET_STEP_MS) }) { Text("+", maxLines = 1) }
    }
}

/** Edits the name other devices see; left blank it goes back to the device's model. */
@Composable
private fun NameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_device_name)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { Button(onClick = { onSave(name) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), maxLines = 1) }
        },
    )
}

/** The devices this one is paired with in its current role; forgetting one means entering the PIN again. */
@Composable
private fun PairedDialog(store: PeerStore, onDismiss: () -> Unit) {
    val devices by store.devices.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_paired)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (devices.isEmpty()) Text(stringResource(R.string.no_paired))
                devices.forEach { device ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(device.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        FilledTonalButton(onClick = { store.forget(device.id) }) {
                            Text(stringResource(R.string.forget), maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.close), maxLines = 1) }
        },
    )
}

/** Picks one of [options]. The radio has no click of its own, so a TV remote stops once per row. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, onClick = { onPick(option) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), maxLines = 1) }
        },
    )
}

private fun Quality.labelRes(): Int = when (this) {
    Quality.LOW -> R.string.quality_low
    Quality.MEDIUM -> R.string.quality_medium
    Quality.HIGH -> R.string.quality_high
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
