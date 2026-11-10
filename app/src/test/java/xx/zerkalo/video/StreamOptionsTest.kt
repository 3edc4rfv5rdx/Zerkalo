package xx.zerkalo.video

import org.junit.Assert.assertEquals
import org.junit.Test
import xx.zerkalo.CODEC_H264
import xx.zerkalo.CODEC_H265
import xx.zerkalo.net.codecMaskOf

class StreamOptionsTest {
    private val both = codecMaskOf(CODEC_H264, CODEC_H265)
    private val h264Only = codecMaskOf(CODEC_H264)

    @Test
    fun h265IsUsedWhenBothEndsHaveIt() {
        assertEquals(VideoCodec.H265, negotiateCodec(VideoCodec.H265, both, both))
    }

    @Test
    fun h265FallsBackWhenTheReceiverLacksIt() {
        assertEquals(VideoCodec.H264, negotiateCodec(VideoCodec.H265, both, h264Only))
    }

    @Test
    fun h265FallsBackWhenTheSenderLacksIt() {
        assertEquals(VideoCodec.H264, negotiateCodec(VideoCodec.H265, h264Only, both))
    }

    @Test
    fun h264IsKeptEvenWithNoHardwareListed() {
        // The emulator's encoder is software, so its mask is empty.
        assertEquals(VideoCodec.H264, negotiateCodec(VideoCodec.H264, 0, both))
    }

    @Test
    fun soundBitDoesNotCountAsAVideoCodec() {
        assertEquals(VideoCodec.H264, negotiateCodec(VideoCodec.H265, 1 shl 4, 1 shl 4))
    }

    @Test
    fun h265GetsALowerBitRateAtTheSameQuality() {
        assertEquals(8_000_000, bitRateOf(Quality.MEDIUM, VideoCodec.H264))
        assertEquals(5_200_000, bitRateOf(Quality.MEDIUM, VideoCodec.H265))
        assertEquals(2_600_000, bitRateOf(Quality.LOW, VideoCodec.H265))
        assertEquals(10_400_000, bitRateOf(Quality.HIGH, VideoCodec.H265))
    }
}
