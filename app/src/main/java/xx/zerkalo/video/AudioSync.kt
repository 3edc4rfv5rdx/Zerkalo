package xx.zerkalo.video

import xx.zerkalo.AUDIO_CODEC_ADVANCE_MS

/**
 * How long until a frame written now would be heard, given that [playedFrames] were
 * reaching the speaker at [playedAtMs]. Never below zero: the playhead can have caught
 * up with what was written.
 */
fun outputQueueMs(writtenFrames: Long, playedFrames: Long, playedAtMs: Long, nowMs: Long, sampleRate: Int): Long {
    val queuedMs = (writtenFrames - playedFrames) * 1000 / sampleRate
    return (queuedMs - (nowMs - playedAtMs)).coerceAtLeast(0)
}

/**
 * How long to hold back the sound due at [targetMs]; below zero means it is already late.
 * A block joins the end of what is queued, so written at this moment it is heard on time.
 */
fun audioWaitMs(targetMs: Long, nowMs: Long, queueMs: Long, offsetMs: Int): Long =
    targetMs + offsetMs - AUDIO_CODEC_ADVANCE_MS - queueMs - nowMs
