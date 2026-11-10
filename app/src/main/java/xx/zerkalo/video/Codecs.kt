package xx.zerkalo.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import xx.zerkalo.CODEC_H264
import xx.zerkalo.CODEC_H265
import xx.zerkalo.net.codecMaskOf

private val SOFTWARE_PREFIXES = listOf("omx.google.", "c2.android.", "omx.ffmpeg.", "c2.ffmpeg.")

private fun MediaCodecInfo.isHardware(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isHardwareAccelerated
    } else {
        SOFTWARE_PREFIXES.none { name.lowercase().startsWith(it) }
    }

/** The mask of video codecs this device has hardware for, as encoders or as decoders. */
fun hardwareCodecMask(encoder: Boolean): Int {
    val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder == encoder && it.isHardware() }
    fun has(mime: String) = infos.any { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
    val codecs = buildList {
        if (has(MediaFormat.MIMETYPE_VIDEO_AVC)) add(CODEC_H264)
        if (has(MediaFormat.MIMETYPE_VIDEO_HEVC)) add(CODEC_H265)
    }
    return codecMaskOf(*codecs.toIntArray())
}
