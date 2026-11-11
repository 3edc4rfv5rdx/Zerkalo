package xx.zerkalo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import xx.zerkalo.R
import xx.zerkalo.receiver.PlayRequest
import xx.zerkalo.receiver.ReceiverModel
import xx.zerkalo.settings.AppSettings
import xx.zerkalo.settings.PictureFit
import kotlin.math.max
import kotlin.math.min

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
    val request by model.playRequest.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(request) {
        request?.let { model.onPlayRequestDone(openInPlayer(context, it)) }
    }

    if (state.connectedTo != null) {
        VideoView(model, state.videoWidth, state.videoHeight)
        return
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (state.portTaken) {
            Text(stringResource(R.string.error_port), style = MaterialTheme.typography.headlineSmall)
        } else {
            Card(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.waiting_connection), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.pin_label),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        text = pin,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    state.addresses.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (state.noPlayer) {
                        Text(stringResource(R.string.error_no_player), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Hands the file to whatever plays it here; the system asks which app if there are several. */
private fun openInPlayer(context: Context, request: PlayRequest): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(request.url.toUri(), request.mime))
    true
} catch (ignored: ActivityNotFoundException) {
    false
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

    val fit by AppSettings.pictureFit.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val shape = if (width > 0 && height > 0) {
            val widthScale = maxWidth.value / width
            val heightScale = maxHeight.value / height
            // A portrait phone filling a TV would leave a strip of it, so only a landscape stream is cropped.
            val scale = if (fit == PictureFit.FILL && width > height) max(widthScale, heightScale) else min(widthScale, heightScale)
            // Larger than the screen when filling; the screen's edges crop it.
            Modifier.requiredSize((width * scale).dp, (height * scale).dp)
        } else {
            Modifier.fillMaxSize()
        }
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
