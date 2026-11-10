package xx.zerkalo.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.zerkalo.FRAME_DEADLINE_MS
import xx.zerkalo.MAX_DATAGRAM
import xx.zerkalo.MAX_NACKS_PER_FRAME
import xx.zerkalo.MAX_NACK_FRAGMENTS
import xx.zerkalo.MAX_PARTIAL_FRAMES
import xx.zerkalo.MAX_VIDEO_PAYLOAD
import xx.zerkalo.NACK_DELAY_MS
import xx.zerkalo.NACK_INTERVAL_MS
import xx.zerkalo.PACKETS_PER_BURST
import xx.zerkalo.PLAYOUT_DELAY_MS
import xx.zerkalo.PLAYOUT_WINDOW_MS
import xx.zerkalo.RETRANSMIT_FRAMES
import xx.zerkalo.VIDEO_HEADER_SIZE
import xx.zerkalo.net.VideoHeader

class VideoPipelineTest {
    private class Datagram(val bytes: ByteArray) {
        val header: VideoHeader = VideoHeader.parse(bytes, bytes.size)!!
    }

    private var pauses = 0

    private fun frame(size: Int, frameId: Long = 1, keyframe: Boolean = true) =
        SentFrame(ByteArray(size) { (it % 251).toByte() }, keyframe, 3, frameId, 77 + frameId)

    private fun fragmenter(out: MutableList<Datagram>) =
        Fragmenter(send = { bytes, length -> out += Datagram(bytes.copyOf(length)) }, pause = { pauses++ })

    private fun packets(size: Int, frameId: Long = 1, keyframe: Boolean = true): List<Datagram> {
        val out = ArrayList<Datagram>()
        fragmenter(out).sendFrame(frame(size, frameId, keyframe))
        return out
    }

    private fun FrameAssembler.feed(packets: List<Datagram>, now: Long = 0) =
        packets.forEach { add(it.header, it.bytes, it.bytes.size, now) }

    /** Everything ready at [now], in the order it came out. */
    private fun FrameAssembler.drain(now: Long = 0): List<AssembledFrame> {
        val out = ArrayList<AssembledFrame>()
        while (true) {
            when (val step = next(now)) {
                FrameAssembler.Step.Idle -> return out
                FrameAssembler.Step.Lost -> return out
                is FrameAssembler.Step.Ready -> out += step.frame
            }
        }
    }

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
        assertTrue(result.all { it.header.fragCount == 3 && it.header.configId == 3 && it.header.ptsMs == 78L })
    }

    @Test
    fun pausesComeBetweenBurstsOnly() {
        packets(MAX_VIDEO_PAYLOAD * PACKETS_PER_BURST)
        assertEquals(0, pauses)
        packets(MAX_VIDEO_PAYLOAD * PACKETS_PER_BURST + 1)
        assertEquals(1, pauses)
    }

    @Test
    fun resendRepeatsExactlyThatFragment() {
        val whole = packets(5000)
        val out = ArrayList<Datagram>()
        fragmenter(out).resend(frame(5000), 2)
        assertArrayEquals(whole[2].bytes, out.single().bytes)
    }

    @Test
    fun resendIgnoresFragmentTheFrameDoesNotHave() {
        val out = ArrayList<Datagram>()
        fragmenter(out).resend(frame(10), 5)
        assertTrue(out.isEmpty())
    }

    // ---------- Retransmit buffer ----------

    @Test
    fun oldestFrameLeavesTheBufferFirst() {
        val buffer = RetransmitBuffer()
        for (id in 0L..RETRANSMIT_FRAMES) buffer.put(frame(10, id))
        assertNull(buffer.get(0))
        assertEquals(1L, buffer.get(1)?.frameId)
        assertEquals(RETRANSMIT_FRAMES.toLong(), buffer.get(RETRANSMIT_FRAMES.toLong())?.frameId)
    }

    // ---------- Assembler: putting frames together ----------

    private val expected = ByteArray(5000) { (it % 251).toByte() }

    @Test
    fun framePutsBackTogether() {
        val assembler = FrameAssembler()
        assembler.feed(packets(5000))
        val result = assembler.drain().single()
        assertArrayEquals(expected, result.data)
        assertTrue(result.keyframe)
        assertEquals(3, result.configId)
        assertEquals(78L, result.ptsMs)
    }

    @Test
    fun orderOfArrivalDoesNotMatter() {
        val assembler = FrameAssembler()
        assembler.feed(packets(5000).reversed())
        assertArrayEquals(expected, assembler.drain().single().data)
    }

    @Test
    fun duplicatesAreHarmless() {
        val assembler = FrameAssembler()
        assembler.feed(packets(5000).flatMap { listOf(it, it) })
        assertArrayEquals(expected, assembler.drain().single().data)
    }

    @Test
    fun deliveredFrameIsNotDeliveredTwice() {
        val assembler = FrameAssembler()
        assembler.feed(packets(10))
        assertEquals(1, assembler.drain().size)
        assembler.feed(packets(10))
        assertTrue(assembler.drain().isEmpty())
    }

    @Test
    fun framesComeOutInOrderEvenIfTheLaterOneFinishesFirst() {
        val assembler = FrameAssembler()
        val first = packets(5000, frameId = 1)
        assembler.feed(first.dropLast(1))
        assembler.feed(packets(10, frameId = 2, keyframe = false))
        assertTrue(assembler.drain().isEmpty())
        assembler.feed(first.takeLast(1))
        assertEquals(listOf(78L, 79L), assembler.drain().map { it.ptsMs })
    }

    @Test
    fun deltaFrameIsMarkedAsSuch() {
        val assembler = FrameAssembler()
        assembler.feed(packets(10, frameId = 1))
        assembler.feed(packets(10, frameId = 2, keyframe = false))
        assertFalse(assembler.drain()[1].keyframe)
    }

    // ---------- Assembler: key frames ----------

    @Test
    fun streamStartsAtAKeyFrame() {
        val assembler = FrameAssembler()
        assertTrue(assembler.waitingForKey)
        assembler.feed(packets(10, frameId = 1, keyframe = false))
        assertTrue(assembler.drain().isEmpty())
        assertTrue(assembler.waitingForKey)
        assembler.feed(packets(10, frameId = 2))
        assertFalse(assembler.waitingForKey)
        assertEquals(1, assembler.drain().size)
    }

    @Test
    fun frameMissingPastItsDeadlineLosesTheStream() {
        val assembler = FrameAssembler()
        assembler.feed(packets(5000).drop(1), now = 0)
        assertSame(FrameAssembler.Step.Idle, assembler.next(FRAME_DEADLINE_MS - 1))
        assertSame(FrameAssembler.Step.Lost, assembler.next(FRAME_DEADLINE_MS))
        assertTrue(assembler.waitingForKey)
    }

    @Test
    fun afterLossDeltaFramesAreSkippedUntilANewKeyFrame() {
        val assembler = FrameAssembler()
        assembler.feed(packets(5000, frameId = 1).drop(1), now = 0)
        assembler.next(FRAME_DEADLINE_MS)
        assembler.feed(packets(10, frameId = 2, keyframe = false), now = FRAME_DEADLINE_MS)
        assertTrue(assembler.drain(FRAME_DEADLINE_MS).isEmpty())
        assembler.feed(packets(10, frameId = 3), now = FRAME_DEADLINE_MS)
        assertEquals(1, assembler.drain(FRAME_DEADLINE_MS).size)
    }

    @Test
    fun lateKeyFrameFromTheLostStretchDoesNotRestartIt() {
        val assembler = FrameAssembler()
        val old = packets(5000, frameId = 1)
        assembler.feed(old.drop(1), now = 0)
        assembler.next(FRAME_DEADLINE_MS)
        assembler.feed(old, now = FRAME_DEADLINE_MS)
        assertTrue(assembler.waitingForKey)
    }

    @Test
    fun wholeFrameMissingIsAlsoLostAtItsDeadline() {
        val assembler = FrameAssembler()
        assembler.feed(packets(10, frameId = 1), now = 0)
        assembler.drain(0)
        assembler.feed(packets(10, frameId = 3, keyframe = false), now = 5)
        assertSame(FrameAssembler.Step.Idle, assembler.next(5 + FRAME_DEADLINE_MS - 1))
        assertSame(FrameAssembler.Step.Lost, assembler.next(5 + FRAME_DEADLINE_MS))
    }

    @Test
    fun repeatedFragmentsSaveTheFrame() {
        val assembler = FrameAssembler()
        val all = packets(5000, frameId = 1)
        assembler.feed(all.drop(1), now = 0)
        assembler.feed(all.take(1), now = FRAME_DEADLINE_MS - 1)
        assertArrayEquals(expected, assembler.drain(FRAME_DEADLINE_MS - 1).single().data)
    }

    @Test
    fun endlessGapResetsInsteadOfPilingUp() {
        val assembler = FrameAssembler()
        assembler.feed(packets(10, frameId = 1))
        assembler.drain()
        assembler.feed(packets(10, frameId = 2L + MAX_PARTIAL_FRAMES + 5, keyframe = false))
        assertTrue(assembler.waitingForKey)
    }

    // ---------- Assembler: NACK ----------

    @Test
    fun noRequestBeforeTheFrameHasGoneQuiet() {
        val assembler = FrameAssembler()
        assembler.feed(packets(5000).drop(1), now = 0)
        assertTrue(assembler.nacks(NACK_DELAY_MS - 1).isEmpty())
    }

    @Test
    fun requestNamesExactlyTheHoles() {
        val assembler = FrameAssembler()
        val all = packets(MAX_VIDEO_PAYLOAD * 4)
        assembler.feed(listOf(all[0], all[3]), now = 0)
        val nack = assembler.nacks(NACK_DELAY_MS).single()
        assertEquals(1L, nack.frameId)
        assertArrayEquals(intArrayOf(1, 2), nack.fragments)
    }

    @Test
    fun wholeMissingFrameIsAskedForByItsFirstFragment() {
        val assembler = FrameAssembler()
        assembler.feed(packets(10, frameId = 1), now = 0)
        assembler.drain(0)
        assembler.feed(packets(10, frameId = 3, keyframe = false), now = 0)
        val nack = assembler.nacks(NACK_DELAY_MS).single()
        assertEquals(2L, nack.frameId)
        assertArrayEquals(intArrayOf(0), nack.fragments)
    }

    @Test
    fun requestsAreSpacedAndLimited() {
        val assembler = FrameAssembler()
        assembler.feed(packets(5000).drop(1), now = 0)
        var now = NACK_DELAY_MS
        assertEquals(1, assembler.nacks(now).size)
        assertTrue(assembler.nacks(now + NACK_INTERVAL_MS - 1).isEmpty())
        repeat(MAX_NACKS_PER_FRAME - 1) {
            now += NACK_INTERVAL_MS
            assertEquals(1, assembler.nacks(now).size)
        }
        assertTrue(assembler.nacks(now + NACK_INTERVAL_MS).isEmpty())
    }

    @Test
    fun largeHoleIsSplitAcrossRequests() {
        val assembler = FrameAssembler()
        val all = packets(MAX_VIDEO_PAYLOAD * (MAX_NACK_FRAGMENTS + 2))
        assembler.feed(all.takeLast(1), now = 0)
        val requests = assembler.nacks(NACK_DELAY_MS)
        assertEquals(2, requests.size)
        assertEquals(MAX_NACK_FRAGMENTS + 1, requests.sumOf { it.fragments.size })
    }

    @Test
    fun finishedFrameIsNotAskedFor() {
        val assembler = FrameAssembler()
        assembler.feed(packets(5000), now = 0)
        assertTrue(assembler.nacks(NACK_DELAY_MS).isEmpty())
    }

    // ---------- Playout clock ----------

    @Test
    fun nothingToTimeBeforeTheFirstFrame() {
        assertNull(PlayoutClock().targetMs(0))
    }

    @Test
    fun frameIsShownAFixedDelayBehindTheFastestArrival() {
        val clock = PlayoutClock()
        clock.onFrame(ptsMs = 0, arrivalMs = 1000)
        assertEquals(1000 + PLAYOUT_DELAY_MS, clock.targetMs(0))
        assertEquals(1033 + PLAYOUT_DELAY_MS, clock.targetMs(33))
    }

    @Test
    fun aDelayedFrameDoesNotMoveTheTimeline() {
        val clock = PlayoutClock()
        clock.onFrame(ptsMs = 0, arrivalMs = 1000)
        clock.onFrame(ptsMs = 33, arrivalMs = 1100)
        assertEquals(1033 + PLAYOUT_DELAY_MS, clock.targetMs(33))
    }

    @Test
    fun aFasterPathPullsTheTimelineIn() {
        val clock = PlayoutClock()
        clock.onFrame(ptsMs = 0, arrivalMs = 1000)
        clock.onFrame(ptsMs = 33, arrivalMs = 1020)
        assertEquals(1020 + PLAYOUT_DELAY_MS, clock.targetMs(33))
    }

    @Test
    fun anOldFastSampleExpiresWithItsWindow() {
        val clock = PlayoutClock()
        clock.onFrame(ptsMs = 0, arrivalMs = 1000)
        clock.onFrame(ptsMs = 33, arrivalMs = 1100)
        val later = 1100 + PLAYOUT_WINDOW_MS + 1
        clock.onFrame(ptsMs = later - 1067, arrivalMs = later)
        assertEquals(1067 + PLAYOUT_DELAY_MS, clock.targetMs(0))
    }
}
