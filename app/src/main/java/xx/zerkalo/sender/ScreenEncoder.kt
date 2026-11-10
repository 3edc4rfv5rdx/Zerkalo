package xx.zerkalo.sender

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import xx.zerkalo.CODEC_H264
import xx.zerkalo.DEFAULT_BIT_RATE
import xx.zerkalo.DEFAULT_FRAME_RATE
import xx.zerkalo.KEY_FRAME_INTERVAL_S
import xx.zerkalo.MAX_DATAGRAM
import xx.zerkalo.REPEAT_FRAME_AFTER_US
import xx.zerkalo.VIDEO_PORT
import xx.zerkalo.net.ControlChannel
import xx.zerkalo.net.ControlMessage
import xx.zerkalo.video.Fragmenter
import xx.zerkalo.video.fitStreamSize
import xx.zerkalo.video.mimeOf
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

private const val TAG = "ScreenEncoder"
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val STOP_WAIT_MS = 1_000L
private const val PTS_MASK = 0xFFFF_FFFFL

/** Captures the screen, encodes it and sends it to the far end of [channel]. */
class ScreenEncoder(
    private val context: Context,
    private val projection: MediaProjection,
    private val channel: ControlChannel,
    private val onFailed: () -> Unit,
) {
    @Volatile
    private var stopped = false

    /** The configuration the receiver has said it can decode. No frame is sent before that. */
    @Volatile
    private var readyConfig = -1

    @Volatile
    private var codec: MediaCodec? = null

    private var worker: Thread? = null

    fun start() {
        worker = thread(name = "screen-encoder") { run() }
    }

    fun stop() {
        stopped = true
        worker?.join(STOP_WAIT_MS)
    }

    /** The receiver is set up for [configId], and needs a key frame to begin with. */
    fun onReady(configId: Int) {
        readyConfig = configId
        requestKeyFrame()
    }

    private fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Key frame request while the codec is stopping", e)
        }
    }

    private fun run() {
        var encoder: MediaCodec? = null
        var input: Surface? = null
        var display: VirtualDisplay? = null
        try {
            val mime = mimeOf(CODEC_H264)
            val created = MediaCodec.createEncoderByType(mime)
            encoder = created
            val capabilities = created.codecInfo.getCapabilitiesForType(mime)
            val metrics = screenMetrics()
            val video = checkNotNull(capabilities.videoCapabilities) { "Not a video codec" }
            val size = fitStreamSize(metrics.widthPixels, metrics.heightPixels, video.widthAlignment, video.heightAlignment)
            val format = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_S)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_AFTER_US)
                val vbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                if (capabilities.encoderCapabilities?.isBitrateModeSupported(vbr) == true) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, vbr)
                }
            }
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = created.createInputSurface()
            input = surface
            created.start()
            codec = created
            display = projection.createVirtualDisplay(
                "zerkalo",
                size.width,
                size.height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )
            sendLoop(created, size.width, size.height)
        } catch (e: Exception) {
            // Codecs and sockets fail in many ways on real hardware; each one ends the session the same.
            Log.e(TAG, "Encoding failed", e)
            if (!stopped) onFailed()
        } finally {
            codec = null
            display?.release()
            runCatching { encoder?.stop() }
            encoder?.release()
            input?.release()
        }
    }

    private fun sendLoop(encoder: MediaCodec, width: Int, height: Int) {
        val address = InetAddress.getByName(channel.remoteHost)
        DatagramSocket().use { socket ->
            val datagram = DatagramPacket(ByteArray(MAX_DATAGRAM), MAX_DATAGRAM, address, VIDEO_PORT)
            val fragmenter = Fragmenter(
                send = { bytes, length ->
                    datagram.setData(bytes, 0, length)
                    socket.send(datagram)
                },
                pause = { Thread.sleep(1) },
            )
            val info = MediaCodec.BufferInfo()
            var configId = 0
            var frameId = 0L
            var firstPtsUs = -1L
            while (!stopped) {
                val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index < 0) continue
                val buffer = encoder.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        configId++
                        channel.send(ControlMessage.Config(configId, CODEC_H264, width, height, bytes))
                    } else if (readyConfig == configId) {
                        if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                        val ptsMs = (info.presentationTimeUs - firstPtsUs) / 1000 and PTS_MASK
                        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        fragmenter.sendFrame(bytes, keyframe, configId, frameId++, ptsMs)
                    }
                }
                encoder.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun screenMetrics(): DisplayMetrics {
        val manager = context.getSystemService(DisplayManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics
    }
}
