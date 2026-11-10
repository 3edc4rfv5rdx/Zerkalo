package xx.zerkalo

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toDrawable
import dev.about.About
import dev.about.AboutConfig
import dev.updater.Updater
import dev.updater.UpdaterConfig
import xx.zerkalo.receiver.ReceiverModel
import xx.zerkalo.sender.SenderModel
import xx.zerkalo.settings.AppSettings
import xx.zerkalo.settings.PairedDevices
import xx.zerkalo.settings.localizedContext
import xx.zerkalo.ui.App
import xx.zerkalo.ui.WindowDark
import xx.zerkalo.ui.WindowLight
import xx.zerkalo.ui.ZerkaloTheme
import xx.zerkalo.ui.isDarkTheme

// One description of this app's release, for the check at start-up and the About dialog alike.
private val UPDATER_CONFIG = UpdaterConfig(appKey = "zerkalo", repo = "Zerkalo")

class MainActivity : ComponentActivity() {

    private val senderModel by viewModels<SenderModel>()
    private val receiverModel by viewModels<ReceiverModel>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        PairedDevices.load(this)
        Updater.checkOnStart(this, UPDATER_CONFIG)
        applyWindowTheme()

        setContent {
            val themeMode by AppSettings.themeMode.collectAsState()
            val accentIndex by AppSettings.accentIndex.collectAsState()
            // The window is outside the composition: a theme changed in
            // Settings is carried out to the bars by hand.
            LaunchedEffect(themeMode) { applyWindowTheme() }

            ZerkaloTheme(themeMode, accentIndex) {
                App(sender = senderModel, receiver = receiverModel, onAbout = ::showAbout)
            }
        }
    }

    /** The window background before the first frame and the colour the system bar icons are drawn for. */
    private fun applyWindowTheme() {
        val systemInDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = isDarkTheme(AppSettings.themeMode.value, systemInDark)
        window.setBackgroundDrawable((if (dark) WindowDark else WindowLight).toArgb().toDrawable())
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun showAbout() {
        About.show(this, AboutConfig(updater = UPDATER_CONFIG, buildDate = BuildConfig.BUILD_DATE))
    }
}
