package dev.otectus.mcareputation.reputation;

/**
 * Every arithmetic decision about a standing score, in one pure place (spec §17.1).
 *
 * <p>Two rules govern all of it:
 *
 * <ol>
 *   <li><b>All intermediates are {@code long}.</b> A player's score is an {@code int}, but summing
 *       hundreds of contributions plus a baseline can overflow {@code int} before the clamp ever
 *       runs, and an overflowed score would flip sign — turning a hero into an outcast on load.
 *       §13.6 and §17.1 both require {@code long} intermediates for exactly this reason.</li>
 *   <li><b>Clamp last, once.</b> Clamping partway through would make the total depend on the order
 *       incidents happen to be stored in, and §13.4's recomputation invariant would stop holding.</li>
 * </ol>
 *
 * <p>No Minecraft or Forge types here on purpose: {@code ReputationMathTest} exercises this class
 * with no game running.
 */
public final class ReputationMath {

    private ReputationMath() {
    }

    /** Clamps a {@code long} into the configured score window and narrows it safely to {@code int}. */
    public static int clampScore(long raw, int minScore, int maxScore) {
        long low = Math.min(minScore, maxScore);
        long high = Math.max(minScore, maxScore);
        return (int) Math.max(low, Math.min(high, raw));
    }

    /**
     * The authoritative score for a community: baseline plus every retained incident's current
     * contribution, clamped. This is the definition §13.4 states as an invariant, and
     * {@code ReputationSavedData} must be able to reproduce its cached score from here at any time —
     * a corrupted cached value never becomes authoritative.
     *
     * @param baseline      admin/imported standing that does not decay
     * @param contributions current contributions of the retained incidents, in any order
     */
    public static int totalScore(int baseline, Iterable<Integer> contributions, int minScore, int maxScore) {
        long sum = baseline;
        for (Integer contribution : contributions) {
            if (contribution != null) {
                sum += contribution;
            }
        }
        return clampScore(sum, minScore, maxScore);
    }

    /**
     * The delta actually applied when moving from {@code oldScore} by {@code requestedDelta} under a
     * clamp. At the ceiling this is {@code 0}, which is what the player feedback and the change event
     * must report — telling somebody "+12" when the number did not move is a lie the UI should not
     * tell (§28.3).
     */
    public static int appliedDelta(int oldScore, int requestedDelta, int minScore, int maxScore) {
        return clampScore((long) oldScore + requestedDelta, minScore, maxScore) - oldScore;
    }

    /**
     * Clamps a <b>baseline</b>, which deliberately has a wider window than the visible score.
     *
     * <p>The baseline must be allowed outside the configured score window or the §13.5 pruning
     * invariant is unsatisfiable: folding a pruned {@code +50} into a baseline already at the ceiling
     * while a {@code -100} incident survives requires the baseline to hold the overflow so that
     * {@code clamp(baseline + contributions)} still reproduces the player's exact score. Only the
     * visible score is clamped to the configured window — last, once, in {@link #totalScore} — while
     * the baseline is bounded solely by {@link ReputationBounds#ABSOLUTE_SCORE_LIMIT} to stay
     * overflow-safe and keep the save format an {@code int}.
     */
    public static int clampBaseline(long raw) {
        return (int) Math.max(-ReputationBounds.ABSOLUTE_SCORE_LIMIT,
                Math.min(ReputationBounds.ABSOLUTE_SCORE_LIMIT, raw));
    }

    /**
     * Rounds a scaled value toward zero (§15.2). Uses {@code double} then narrows, because narrowing
     * a {@code double} to {@code int} truncates toward zero by definition, which is exactly the
     * behaviour resolution multipliers are specified with — and unlike {@code Math.round} it never
     * turns a {@code -0.4} penalty into {@code -1}.
     */
    public static int scaleTowardZero(int value, float multiplier) {
        if (value == 0 || multiplier == 1.0f) {
            return value;
        }
        if (Float.isNaN(multiplier)) {
            return 0;
        }
        double scaled = (double) value * multiplier;
        if (scaled > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (scaled < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) scaled;
    }

    /**
     * How far {@code score} has progressed from {@code from} toward {@code to}, as {@code 0.0..1.0}.
     * Used by the standing screen's progress bar. Returns {@code 1.0} for a degenerate span so the
     * bar shows "complete" rather than dividing by zero.
     */
    public static float progress(int score, int from, int to) {
        if (to == from) {
            return 1.0f;
        }
        float ratio = (float) (score - from) / (float) (to - from);
        return Math.max(0.0f, Math.min(1.0f, ratio));
    }
}
