package xx.zerkalo.video

import xx.zerkalo.BIT_RATE_HIGH
import xx.zerkalo.BIT_RATE_LOW
import xx.zerkalo.BIT_RATE_MEDIUM
import xx.zerkalo.CODEC_H264
import xx.zerkalo.CODEC_H265
import xx.zerkalo.FRAME_RATE_HIGH
import xx.zerkalo.FRAME_RATE_STANDARD
import xx.zerkalo.H265_BIT_RATE_PERCENT
import xx.zerkalo.net.codecMaskOf

/** [label] is the format's proper name, the same in every language. */
enum class VideoCodec(val id: Int, val label: String) {
    H264(CODEC_H264, "H.264"),
    H265(CODEC_H265, "H.265"),
}

enum class FrameRate(val fps: Int) {
    STANDARD(FRAME_RATE_STANDARD),
    HIGH(FRAME_RATE_HIGH),
}

enum class Quality(val h264BitRate: Int) {
    LOW(BIT_RATE_LOW),
    MEDIUM(BIT_RATE_MEDIUM),
    HIGH(BIT_RATE_HIGH),
}

/** What the sender's settings ask of a mirroring session. */
class StreamOptions(val codec: VideoCodec, val frameRate: FrameRate, val quality: Quality)

/** [wanted] if both ends have hardware for it, else H.264, which every device can do. */
fun negotiateCodec(wanted: VideoCodec, senderMask: Int, receiverMask: Int): VideoCodec =
    if (senderMask and receiverMask and codecMaskOf(wanted.id) != 0) wanted else VideoCodec.H264

fun bitRateOf(quality: Quality, codec: VideoCodec): Int =
    if (codec == VideoCodec.H265) quality.h264BitRate / 100 * H265_BIT_RATE_PERCENT else quality.h264BitRate
