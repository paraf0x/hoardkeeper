package dev.hoardkeeper.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChimePitchTest {

    private static final long RESET_MILLIS = 1500;

    @Test
    void firstChimeOfARunStartsAtTheBottom() {
        ChimePitch pitch = new ChimePitch(RESET_MILLIS);

        assertEquals(ChimePitch.BASE_PITCH, pitch.next(10_000), 0.0001f);
    }

    @Test
    void consecutiveContainersClimb() {
        ChimePitch pitch = new ChimePitch(RESET_MILLIS);

        float first = pitch.next(0);
        float second = pitch.next(200);
        float third = pitch.next(400);

        assertTrue(second > first, "a second container 200ms later must be higher");
        assertTrue(third > second);
    }

    @Test
    void aPauseRestartsTheRunAtTheBottom() {
        // The whole point of the design: each cluster of chests gets its own rising run, so walking
        // to the next room re-arms it instead of leaving the pitch parked wherever it stopped.
        ChimePitch pitch = new ChimePitch(RESET_MILLIS);
        pitch.next(0);
        pitch.next(200);
        pitch.next(400);

        float afterPause = pitch.next(400 + RESET_MILLIS + 1);

        assertEquals(ChimePitch.BASE_PITCH, afterPause, 0.0001f);
    }

    @Test
    void aGapExactlyAtTheThresholdStillCountsAsTheSameRun() {
        ChimePitch pitch = new ChimePitch(RESET_MILLIS);
        float first = pitch.next(0);

        float second = pitch.next(RESET_MILLIS);

        assertTrue(second > first, "the reset is a strict '>' — the boundary belongs to the run");
    }

    @Test
    void everyStepStaysInsideMinecraftsPitchClamp() {
        // Minecraft silently clamps to [0.5, 2.0]. A value outside it is not an exception, it is a
        // note that quietly turns into a different note — so the table must be legal by
        // construction, not by luck.
        for (int step = 0; step < ChimePitch.runLength(); step++) {
            float p = ChimePitch.pitchForStep(step);
            assertTrue(p >= ChimePitch.BASE_PITCH && p <= ChimePitch.MAX_PITCH,
                    "step " + step + " produced " + p + ", outside [0.5, 2.0]");
        }
    }

    @Test
    void theRunEndsExactlyOnTheCeilingAndThenWraps() {
        ChimePitch pitch = new ChimePitch(RESET_MILLIS);
        int length = ChimePitch.runLength();

        float last = 0;
        for (int i = 0; i < length; i++) {
            last = pitch.next(i * 200L);
        }
        float afterWrap = pitch.next(length * 200L);

        assertEquals(ChimePitch.MAX_PITCH, last, 0.0001f, "the top of the run is the top of the range");
        assertEquals(ChimePitch.BASE_PITCH, afterWrap, 0.0001f, "and then it starts over");
    }

    @Test
    void everyStepOfTheRunIsAudiblyDistinct() {
        // A run whose steps collapse onto each other is a fixed tone with extra arithmetic. The
        // smallest interval in the table is a whole tone (~12% frequency), comfortably audible.
        for (int step = 1; step < ChimePitch.runLength(); step++) {
            float previous = ChimePitch.pitchForStep(step - 1);
            float current = ChimePitch.pitchForStep(step);
            assertTrue(current / previous > 1.05f,
                    "step " + step + " is less than a semitone above its predecessor");
        }
    }

    @Test
    void stepsRiseInEvenMusicalIntervalsNotEvenAdditiveOnes() {
        // Pitch is a frequency multiplier, so the run must step by ratios. The two-semitone steps
        // must all share one ratio even though their additive differences do not.
        float zeroToTwo = ChimePitch.pitchForStep(1) / ChimePitch.pitchForStep(0);
        float sevenToNine = ChimePitch.pitchForStep(4) / ChimePitch.pitchForStep(3);

        assertEquals(zeroToTwo, sevenToNine, 0.0005f);
        assertNotEquals(ChimePitch.pitchForStep(1) - ChimePitch.pitchForStep(0),
                ChimePitch.pitchForStep(4) - ChimePitch.pitchForStep(3), 0.0005f);
    }

    @Test
    void resetSendsTheNextChimeBackToTheBottom() {
        ChimePitch pitch = new ChimePitch(RESET_MILLIS);
        pitch.next(0);
        pitch.next(200);

        pitch.reset();

        assertEquals(ChimePitch.BASE_PITCH, pitch.next(400), 0.0001f,
                "reset must not leave the run mid-climb for the next scan");
    }

    @Test
    void aBackwardsClockRestartsRatherThanClimbingForever() {
        ChimePitch pitch = new ChimePitch(RESET_MILLIS);
        pitch.next(10_000);
        pitch.next(10_200);

        assertEquals(ChimePitch.BASE_PITCH, pitch.next(9_000), 0.0001f);
    }

    @Test
    void aZeroResetThresholdMakesEveryChimeTheBaseNote() {
        // The config allows 0, and it must mean "no run at all" rather than something undefined:
        // any real gap is > 0, so every chime is the first chime.
        ChimePitch pitch = new ChimePitch(0);

        assertEquals(ChimePitch.BASE_PITCH, pitch.next(0), 0.0001f);
        assertEquals(ChimePitch.BASE_PITCH, pitch.next(1), 0.0001f);
        assertEquals(ChimePitch.BASE_PITCH, pitch.next(500), 0.0001f);
    }
}
