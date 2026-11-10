package xx.zerkalo.video

import org.junit.Assert.assertEquals
import org.junit.Test
import xx.zerkalo.AUDIO_CODEC_ADVANCE_MS

class AudioSyncTest {
    @Test
    fun queueIsWhatWasWrittenAndNotYetPlayed() {
        assertEquals(100L, outputQueueMs(writtenFrames = 9600, playedFrames = 4800, playedAtMs = 1000, nowMs = 1000, sampleRate = 48_000))
    }

    @Test
    fun playheadKeepsMovingAfterTheReading() {
        assertEquals(70L, outputQueueMs(9600, 4800, playedAtMs = 1000, nowMs = 1030, sampleRate = 48_000))
    }

    @Test
    fun queueIsNeverNegative() {
        assertEquals(0L, outputQueueMs(4800, 4800, playedAtMs = 1000, nowMs = 1500, sampleRate = 48_000))
    }

    @Test
    fun aLongerQueueMeansWritingSooner() {
        val short = audioWaitMs(targetMs = 2000, nowMs = 1000, queueMs = 40, offsetMs = 0)
        val long = audioWaitMs(targetMs = 2000, nowMs = 1000, queueMs = 140, offsetMs = 0)
        assertEquals(100L, short - long)
    }

    @Test
    fun waitAllowsForTheCodecAndTheQueue() {
        assertEquals(1000 - AUDIO_CODEC_ADVANCE_MS - 40, audioWaitMs(2000, 1000, 40, 0))
    }

    @Test
    fun positiveOffsetMakesTheSoundLater() {
        assertEquals(20L, audioWaitMs(2000, 1000, 40, 20) - audioWaitMs(2000, 1000, 40, 0))
    }

    @Test
    fun negativeOffsetMakesTheSoundEarlier() {
        assertEquals(-80L, audioWaitMs(2000, 1000, 40, -80) - audioWaitMs(2000, 1000, 40, 0))
    }

    @Test
    fun nextBlockQueuedBehindThisOneWaitsNoLonger() {
        // 21 ms of sound added to the queue and 21 ms later on the clock: the stream stays continuous.
        assertEquals(audioWaitMs(2000, 1000, 40, 0), audioWaitMs(2021, 1000, 61, 0))
    }
}
