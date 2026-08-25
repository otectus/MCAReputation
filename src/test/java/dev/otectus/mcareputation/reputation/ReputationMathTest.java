package dev.otectus.mcareputation.reputation;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §36.1 group 2. */
class ReputationMathTest {

    private static final int MIN = -1000;
    private static final int MAX = 1000;

    @Test
    void clampsBothWays() {
        assertEquals(MAX, ReputationMath.clampScore(5000L, MIN, MAX));
        assertEquals(MIN, ReputationMath.clampScore(-5000L, MIN, MAX));
        assertEquals(42, ReputationMath.clampScore(42L, MIN, MAX));
    }

    @Test
    void clampToleratesInvertedBounds() {
        assertEquals(10, ReputationMath.clampScore(50L, 10, -10),
                "hand-edited config must not produce an impossible window");
    }

    /**
     * The reason every intermediate is a {@code long}: summing many large contributions would overflow
     * {@code int} and flip sign, turning a hero into an outcast on load.
     */
    @Test
    void sumsWithoutIntOverflow() {
        List<Integer> huge = Collections.nCopies(64, Integer.MAX_VALUE / 4);
        assertEquals(MAX, ReputationMath.totalScore(0, huge, MIN, MAX));

        List<Integer> hugeNegative = Collections.nCopies(64, Integer.MIN_VALUE / 4);
        assertEquals(MIN, ReputationMath.totalScore(0, hugeNegative, MIN, MAX));
    }

    @Test
    void totalIsBaselinePlusContributions() {
        assertEquals(17, ReputationMath.totalScore(5, List.of(10, 4, -2), MIN, MAX));
        assertEquals(5, ReputationMath.totalScore(5, List.of(), MIN, MAX));
        assertEquals(0, ReputationMath.totalScore(0, List.of(8, -8), MIN, MAX));
    }

    @Test
    void nullContributionsAreIgnoredNotFatal() {
        assertEquals(10, ReputationMath.totalScore(0, java.util.Arrays.asList(10, null), MIN, MAX));
    }

    /** At the clamp the applied delta is zero, and the UI must be told the truth (§28.3). */
    @Test
    void appliedDeltaReportsWhatActuallyMoved() {
        assertEquals(12, ReputationMath.appliedDelta(0, 12, MIN, MAX));
        assertEquals(0, ReputationMath.appliedDelta(MAX, 50, MIN, MAX));
        assertEquals(0, ReputationMath.appliedDelta(MIN, -50, MIN, MAX));
        assertEquals(1, ReputationMath.appliedDelta(MAX - 1, 50, MIN, MAX));
    }

    /**
     * The baseline clamps to the absolute limit, not the score window: it must be able to hold fold
     * overflow so {@code clamp(baseline + contributions)} keeps reproducing the exact score after
     * pruning (§13.5). Only the visible score is window-clamped.
     */
    @Test
    void baselineClampIsWiderThanTheScoreWindow() {
        assertEquals(MAX + 500, ReputationMath.clampBaseline(MAX + 500L),
                "a baseline just past the score ceiling survives intact");
        assertEquals(ReputationBounds.ABSOLUTE_SCORE_LIMIT,
                ReputationMath.clampBaseline(Long.MAX_VALUE));
        assertEquals(-ReputationBounds.ABSOLUTE_SCORE_LIMIT,
                ReputationMath.clampBaseline(Long.MIN_VALUE));
        assertEquals(0, ReputationMath.clampBaseline(0L));
    }

    /** §15.2: resolution multipliers round toward zero, in both directions. */
    @Test
    void scaleRoundsTowardZero() {
        assertEquals(-6, ReputationMath.scaleTowardZero(-8, 0.75f));
        assertEquals(-2, ReputationMath.scaleTowardZero(-8, 0.25f));
        assertEquals(0, ReputationMath.scaleTowardZero(-8, 0.0f));
        assertEquals(6, ReputationMath.scaleTowardZero(8, 0.75f));
        assertEquals(0, ReputationMath.scaleTowardZero(-1, 0.5f),
                "a half point of penalty rounds away, never up to a whole one");
        assertEquals(0, ReputationMath.scaleTowardZero(0, 0.5f));
        assertEquals(8, ReputationMath.scaleTowardZero(8, 1.0f));
    }

    @Test
    void scaleHandlesNaN() {
        assertEquals(0, ReputationMath.scaleTowardZero(8, Float.NaN));
    }

    @Test
    void progressIsBoundedToUnitInterval() {
        assertEquals(0.0f, ReputationMath.progress(0, 0, 100));
        assertEquals(0.5f, ReputationMath.progress(50, 0, 100));
        assertEquals(1.0f, ReputationMath.progress(150, 0, 100));
        assertEquals(0.0f, ReputationMath.progress(-50, 0, 100));
        assertEquals(1.0f, ReputationMath.progress(5, 5, 5), "a degenerate span reads as complete");
        assertTrue(ReputationMath.progress(-50, -75, -25) > 0.0f,
                "progress works across negative tiers too");
    }
}
