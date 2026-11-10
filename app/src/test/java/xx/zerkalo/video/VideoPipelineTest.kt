package xx.zerkalo.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.zerkalo.MAX_DATAGRAM
import xx.zerkalo.MAX_PARTIAL_FRAMES
import xx.zerkalo.MAX_VIDEO_PAYLOAD
import xx.zerkalo.PACKETS_PER_BURST
import xx.zerkalo.VIDEO_HEADER_SIZE
import xx.zerkalo.net.VideoHeader

class VideoPipelineTest {
    private class Datagram(val bytes: ByteArray) {
        val header: VideoHeader = VideoHeader.parse(bytes, bytes.size)!!
    }

    private var pauses = 0

    private fun packets(size: Int, frameId: Long = 1, keyframe: Boolean = true): List<Datagram> {
        val out = ArrayList<Datagram>()
        val fragmenter = Fragmenter(send = { bytes, length -> out += Datagram(bytes.copyOf(length)) }, pause = { pauses++ })
        fragmenter.sendFrame(ByteArray(size) { (it % 251).toByte() }, keyframe, 3, frameId, 77)
        return out
    }

    private fun FrameAssembler.feed(packets: List<Datagram>): List<AssembledFrame> =
        packets.mapNotNull { add(it.header, it.bytes, it.bytes.size) }

    // ---------- Stream size ----------

    @Test
    fun portraitPhoneFitsByHeight() {
        assertEquals(StreamSize(498, 1080), fitStreamSize(1080, 2340, 2, 2))
    }

    @Test
    fun landscapePhoneFitsByWidth() {
        assertEquals(StreamSize(1920, 886), fitStreamSize(2340, 1080, 2, 2))
    }

    @Test
    fun smallScreenIsNotEnlarged() {
        assertEquals(StreamSize(1280, 720), fitStreamSize(1280, 720, 2, 2))
    }

    @Test
    fun sidesFollowCodecAlignment() {
        assertEquals(StreamSize(496, 1072), fitStreamSize(1080, 2340, 16, 16))
    }

    @Test
    fun oddSideBecomesEvenEvenWithoutAlignment() {
        assertEquals(StreamSize(1278, 718), fitStreamSize(1279, 719, 1, 1))
    }

    @Test
    fun extremelyThinScreenKeepsAUsableSide() {
        assertEquals(2, fitStreamSize(1, 5000, 2, 2).width)
    }

    // ---------- Fragmenter ----------

    @Test
    fun exactlyOnePayloadIsOnePacket() {
        assertEquals(1, packets(MAX_VIDEO_PAYLOAD).size)
    }

    @Test
    fun oneByteMoreIsTwoPackets() {
        val result = packets(MAX_VIDEO_PAYLOAD + 1)
        assertEquals(2, result.size)
        assertEquals(VIDEO_HEADER_SIZE + 1, result[1].bytes.size)
    }

    @Test
    fun packetsNeverExceedTheDatagramLimit() {
        assertTrue(packets(100_000).all { it.bytes.size <= MAX_DATAGRAM })
    }

    @Test
    fun packetsCarryTheirPlaceAndFrameFields() {
        val result = packets(MAX_VIDEO_PAYLOAD * 3)
        assertEquals(listOf(0, 1, 2), result.map { it.header.fragIndex })
        assertTrue(result.all { it.header.fragCount == 3 && it.header.configId == 3 && it.header.ptsMs == 77L })
    }

    @Test
    fun pausesComeBetweenBurstsOnly() {
        packets(MAX_VIDEO_PAYLOAD * PACKETS_PER_BURST)
        assertEquals(0, pauses)
        packets(MAX_VIDEO_PAYLOAD * PACKETS_PER_BURST + 1)
        assertEquals(1, pauses)
    }

    // ---------- Assembler ----------

    private val expected = ByteArray(5000) { (it % 251).toByte() }

    @Test
    fun framePutsBackTogetherInOrder() {
        val frames = FrameAssembler().feed(packets(5000))
        assertEquals(1, frames.size)
        assertArrayEquals(expected, frames[0].data)
        assertTrue(frames[0].keyframe)
        assertEquals(3, frames[0].configId)
        assertEquals(77L, frames[0].ptsMs)
    }

    @Test
    fun framePutsBackTogetherOutOfOrder() {
        val frames = FrameAssembler().feed(packets(5000).reversed())
        assertArrayEquals(expected, frames.single().data)
    }

    @Test
    fun duplicatesAreHarmless() {
        val source = packets(5000)
        val frames = FrameAssembler().feed(source.flatMap { listOf(it, it) })
        assertArrayEquals(expected, frames.single().data)
    }

    @Test
    fun incompleteFrameIsNotDelivered() {
        assertTrue(FrameAssembler().feed(packets(5000).drop(1)).isEmpty())
    }

    @Test
    fun finishedFrameMakesOlderOnesObsolete() {
        val assembler = FrameAssembler()
        val first = packets(5000, frameId = 1)
        assembler.feed(first.dropLast(1))
        assertNotNull(assembler.feed(packets(10, frameId = 2)).singleOrNull())
        assertNull(assembler.add(first.last().header, first.last().bytes, first.last().bytes.size))
    }

    @Test
    fun deliveredFrameIsNotDeliveredTwice() {
        val assembler = FrameAssembler()
        assertEquals(1, assembler.feed(packets(10)).size)
        assertTrue(assembler.feed(packets(10)).isEmpty())
    }

    @Test
    fun abandonedFramesDoNotPileUpForever() {
        val assembler = FrameAssembler()
        val oldest = packets(5000, frameId = 1)
        assembler.feed(oldest.take(1))
        for (id in 2L..(MAX_PARTIAL_FRAMES + 2L)) assembler.feed(packets(5000, frameId = id).take(1))
        // The oldest was evicted, so its remaining pieces can no longer finish it.
        assertTrue(assembler.feed(oldest.drop(1)).isEmpty())
    }

    @Test
    fun deltaFrameIsMarkedAsSuch() {
        assertFalse(FrameAssembler().feed(packets(10, keyframe = false)).single().keyframe)
    }
}
