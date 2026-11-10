package xx.zerkalo.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import xx.zerkalo.R
import xx.zerkalo.receiver.ReceiverModel
import xx.zerkalo.settings.AppSettings

/** What the TV shows: who it is and the PIN to pair with it, or the sender's screen. */
@Composable
fun ReceiverScreen(model: ReceiverModel) {
    LifecycleStartEffect(model) {
        model.start()
        onStopOrDispose { model.stop() }
    }
    val state by model.state.collectAsState()
    val pin by model.pin.collectAsState()
    val name by AppSettings.deviceName.collectAsState()

    if (state.connectedTo != null) {
        VideoView(model, state.videoWidth, state.videoHeight)
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (state.portTaken) {
            Text(stringResource(R.string.error_port), style = MaterialTheme.typography.headlineSmall)
        } else {
            Text(name, style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.pin_label), style = MaterialTheme.typography.titleLarge)
            Text(pin, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
            state.addresses.forEach { Text(it, style = MaterialTheme.typography.titleLarge) }
        }
    }
}

/** The stream on a black screen, proportions kept, with the system bars out of the way. */
@Composable
private fun VideoView(model: ReceiverModel, width: Int, height: Int) {
    BackHandler { model.endSession() }

    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val shape = if (width > 0 && height > 0) Modifier.aspectRatio(width.toFloat() / height) else Modifier.fillMaxSize()
        AndroidView(
            modifier = shape,
            factory = { context ->
                SurfaceView(context).apply {
                    keepScreenOn = true
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = model.setSurface(holder.surface)

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) = model.setSurface(null)
                    })
                }
            },
        )
    }
}
