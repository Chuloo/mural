package chat.mural.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone's pure turn decisions: when the learner starts speaking, when they have finished,
 * and when a clip reaches its limit. Frames are 0.1 s of 16 kHz mono PCM.
 */
class SpeechTurnDetectorTest {
    private val detector = SpeechTurnDetector()
    private val quiet = SpeechTurnDetector.SPEECH_LEVEL / 2
    private val loud = 0.3

    private fun offer(level: Double, frames: Int) = List(frames) { detector.offer(level) }

    @Test fun silenceNeverStartsATurnAndAWholeQuietWindowIsDropped() {
        assertEquals(List(49) { SpeechTurnDetector.Step.Listening(false) }, offer(0.0, 49))
        assertEquals(SpeechTurnDetector.Step.Restart, detector.offer(0.0))
        // The next window listens again instead of accumulating silence forever.
        assertEquals(SpeechTurnDetector.Step.Listening(false), detector.offer(0.0))
        assertEquals(List(10) { SpeechTurnDetector.Step.Listening(false) }, offer(quiet, 10))
    }

    @Test fun speechThenEightTenthsOfASecondOfQuietEndsOneTurn() {
        assertEquals(List(3) { SpeechTurnDetector.Step.Listening(true) }, offer(loud, 3))
        assertEquals(List(7) { SpeechTurnDetector.Step.Listening(true) }, offer(quiet, 7))
        assertEquals(SpeechTurnDetector.Step.TurnEnded, detector.offer(quiet))
    }

    @Test fun aShortQuietGapInsideATurnDoesNotEndIt() {
        offer(loud, 3)
        assertEquals(List(3) { SpeechTurnDetector.Step.Listening(true) }, offer(quiet, 3))
        assertEquals(List(3) { SpeechTurnDetector.Step.Listening(true) }, offer(loud, 3))
        assertEquals(List(7) { SpeechTurnDetector.Step.Listening(true) }, offer(quiet, 7))
        assertEquals(SpeechTurnDetector.Step.TurnEnded, detector.offer(quiet))
    }

    @Test fun aLongTurnEndsAtTheThirtySecondClipLimit() {
        assertEquals(List(299) { SpeechTurnDetector.Step.Listening(true) }, offer(loud, 299))
        assertEquals(SpeechTurnDetector.Step.TurnEnded, detector.offer(loud))
    }

    @Test fun afterATurnTheDetectorListensForTheNextOne() {
        offer(loud, 2)
        offer(quiet, 7)
        assertEquals(SpeechTurnDetector.Step.TurnEnded, detector.offer(quiet))
        assertEquals(SpeechTurnDetector.Step.Listening(true), detector.offer(loud))
        detector.reset()
        assertEquals(SpeechTurnDetector.Step.Listening(false), detector.offer(quiet))
    }

    @Test fun levelIsTheFrameEnergyScaledToUnity() {
        val loudFrame = ShortArray(4) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertEquals(1.0, SpeechTurnDetector.level(loudFrame, 4), 0.001)
        assertEquals(0.0, SpeechTurnDetector.level(ShortArray(4), 4), 0.0)
        assertEquals(0.0, SpeechTurnDetector.level(ShortArray(4), 0), 0.0)
        // A short read counts only the samples the recorder delivered.
        assertEquals(0.0, SpeechTurnDetector.level(shortArrayOf(0, Short.MAX_VALUE, Short.MAX_VALUE), 1), 0.0)
        assertTrue(SpeechTurnDetector.level(shortArrayOf(16_384, 16_384, 16_384, 16_384), 4) in 0.4..0.6)
    }
}
