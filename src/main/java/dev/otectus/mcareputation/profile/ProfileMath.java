package dev.otectus.mcareputation.profile;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every arithmetic decision about recognition and facet evidence, in one pure place (spec §8.3).
 *
 * <h2>Three rules govern all of it</h2>
 *
 * <ol>
 *   <li><b>Fixed point, not integers.</b> One authored point is {@link #SUBUNITS_PER_POINT} subunits.
 *       A 25%-credited half point must survive as {@code 1250} subunits rather than truncating to a
 *       public {@code 0} before it is summed; §8.3 and §23.1's fraction-preservation fixture both
 *       require two 0.5-point contributions to aggregate to exactly one point.</li>
 *   <li><b>Saturating {@code long} intermediates.</b> Authored values are bounded, but a malformed
 *       save or a pathological pack must not be able to wrap a sum into the opposite sign. Every
 *       operation here saturates at {@link Long#MIN_VALUE}/{@link Long#MAX_VALUE} instead.</li>
 *   <li><b>Quantize and clamp last, once.</b> The public integer is derived for the read model only
 *       (§8.3). {@code rawFacet} stays meaningful for retention even when {@code publicFacet} is
 *       zero, which is what stops §12.3's pruning from discarding evidence a later opposing deed
 *       needs.</li>
 * </ol>
 *
 * <p>This class is also the single home of "scale subunits by a basis-point percentage", which is why
 * {@code dev.otectus.mcareputation.credit.CreditDecision} reaches into it rather than re-deriving the
 * same rounding. One implementation of the rounding rule is worth the sibling-package reference; two
 * would eventually disagree about a half subunit and silently change what a frozen deed is worth.
 *
 * <p>No Minecraft types beyond {@link ResourceLocation} (used only as an ordering tiebreak), no server
 * state, no clock: {@code ProfileMathTest} exercises all of it with no game running.
 */
public final class ProfileMath {

    /** §8.3: internal contribution units per authored point. */
    public static final long SUBUNITS_PER_POINT = 10_000L;

    /** One hundred percent, in basis points. */
    public static final int FULL_BP = 10_000;

    /** §9.3: effective age is quantized to whole in-game days by default. */
    public static final long DEFAULT_DECAY_STEP_TICKS = 24_000L;

    /** §9.6: a profile decay step is 20..24000 ticks. */
    public static final long MIN_DECAY_STEP_TICKS = 20L;
    public static final long MAX_DECAY_STEP_TICKS = 24_000L;

    /** §9.6: a profile lifetime is 24000..100000000 ticks, finite and positive. */
    public static final long MIN_LIFETIME_TICKS = 24_000L;
    public static final long MAX_LIFETIME_TICKS = 100_000_000L;

    /** §7.1: recognition is non-negative and ranges 0..1000. */
    public static final int MAX_RECOGNITION = 1000;

    /** §9.6: authored recognition points are 0..100 per incident. */
    public static final int MAX_RECOGNITION_POINTS = 100;

    /** §9.6: authored facet points are -100..100, and a facet's display range sits inside that. */
    public static final int MAX_FACET_POINTS = 100;

    /** §8.2: at most three dominant labels are displayed. */
    public static final int MAX_DOMINANT_FACETS = 3;

    private ProfileMath() {
    }

    // ------------------------------------------------------------------
    // Saturating primitives
    // ------------------------------------------------------------------

    /** {@code a + b}, saturating rather than wrapping. */
    public static long add(long a, long b) {
        long sum = a + b;
        // Overflow iff the operands share a sign that the result does not.
        if (((a ^ sum) & (b ^ sum)) < 0) {
            return a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return sum;
    }

    /** {@code a * b}, saturating rather than wrapping. */
    public static long multiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return ((a < 0) != (b < 0)) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    /** Sums {@code values} with saturating arithmetic, in the order given. */
    public static long sum(Iterable<Long> values) {
        long total = 0L;
        for (Long value : values) {
            if (value != null) {
                total = add(total, value);
            }
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Fixed point
    // ------------------------------------------------------------------

    /** Authored points as internal subunits. */
    public static long subunits(int points) {
        return multiply(points, SUBUNITS_PER_POINT);
    }

    /**
     * {@code subunits * bp / 10000}, truncated toward zero.
     *
     * <p>Truncation toward zero is deliberate and matches
     * {@code ReputationMath.scaleTowardZero}: a partial credit always reduces magnitude, never
     * rounds a discount away. Because the operand is subunits rather than points, a 50% cut of one
     * authored point is {@code 5000}, not {@code 0}.
     */
    public static long scaleByBasisPoints(long subunits, int bp) {
        if (bp == FULL_BP) {
            return subunits;
        }
        if (subunits == 0L || bp <= 0) {
            return 0L;
        }
        long scaled = multiply(subunits, bp);
        if (scaled == Long.MAX_VALUE || scaled == Long.MIN_VALUE) {
            // Saturated: divide first instead, which cannot overflow and stays on the same side.
            return multiply(subunits / FULL_BP, bp);
        }
        return scaled / FULL_BP;
    }

    /** The public integer for a subunit total: truncated toward zero, then clamped to the range. */
    public static int toPoints(long subunits) {
        long points = subunits / SUBUNITS_PER_POINT;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, points));
    }

    /** Clamps a public integer into an inclusive range, tolerating an inverted range defensively. */
    public static int clamp(int value, int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        return Math.max(low, Math.min(high, value));
    }

    /** §8.3's {@code publicFacet}: quantize to points, then clamp to the facet's display range. */
    public static int publicValue(long subunits, int rangeMin, int rangeMax) {
        return clamp(toPoints(subunits), rangeMin, rangeMax);
    }

    /** §7.1's public recognition: quantized, floored at zero and capped at {@link #MAX_RECOGNITION}. */
    public static int publicRecognition(long subunits) {
        return clamp(toPoints(subunits), 0, MAX_RECOGNITION);
    }

    // ------------------------------------------------------------------
    // Lifetime
    // ------------------------------------------------------------------

    /**
     * §9.3: {@code a = floor(effectiveAge / step) * step}. A negative age (a backdated delivery whose
     * occurrence is ahead of the evaluation time) is treated as zero, and a non-positive step returns
     * the age unquantized rather than dividing by zero.
     */
    public static long quantizeAge(long effectiveAgeTicks, long decayStepTicks) {
        if (effectiveAgeTicks <= 0L) {
            return 0L;
        }
        if (decayStepTicks <= 0L) {
            return effectiveAgeTicks;
        }
        return (effectiveAgeTicks / decayStepTicks) * decayStepTicks;
    }

    /**
     * The unreconciled remaining contribution of {@code creditedSubunits} at {@code effectiveAgeTicks}
     * under a finite linear lifetime: {@code credited * max(0, lifetime - a) / lifetime} where
     * {@code a} is the quantized age (§9.3).
     *
     * <p>Sign preserving and monotonic toward zero. A non-positive lifetime yields zero rather than
     * an immortal contribution, which is the conservative reading of a malformed stored value.
     */
    public static long remainingAt(long creditedSubunits, long effectiveAgeTicks, long lifetimeTicks,
                                   long decayStepTicks) {
        if (creditedSubunits == 0L) {
            return 0L;
        }
        if (lifetimeTicks <= 0L) {
            return 0L;
        }
        long quantized = quantizeAge(effectiveAgeTicks, decayStepTicks);
        if (quantized >= lifetimeTicks) {
            return 0L;
        }
        long remainingTicks = lifetimeTicks - quantized;
        long scaled = multiply(creditedSubunits, remainingTicks);
        if (scaled == Long.MAX_VALUE || scaled == Long.MIN_VALUE) {
            return multiply(creditedSubunits / lifetimeTicks, remainingTicks);
        }
        return scaled / lifetimeTicks;
    }

    /** Ticks from the start of a contribution's life until {@link #remainingAt} first returns zero. */
    public static long ticksUntilSpent(long lifetimeTicks, long decayStepTicks) {
        if (lifetimeTicks <= 0L) {
            return 0L;
        }
        if (decayStepTicks <= 0L) {
            return lifetimeTicks;
        }
        // The first quantized age at or above the lifetime.
        long steps = (lifetimeTicks + decayStepTicks - 1L) / decayStepTicks;
        return multiply(steps, decayStepTicks);
    }

    // ------------------------------------------------------------------
    // Dominance ordering (§8.2)
    // ------------------------------------------------------------------

    /**
     * One facet's aggregated evidence, as the dominant-label sort sees it.
     *
     * @param facet          the facet's id, the final ordering tiebreak
     * @param subunits       the aggregated raw contribution, signed
     * @param rangeMagnitude the facet's largest authored magnitude, used to normalize
     * @param displayOrder   the authored display order, the second ordering key
     * @param evidenceCount  distinct credited incidents behind this value (§8.2)
     * @param major          whether any single contribution was authored as major evidence
     */
    public record FacetEvidence(
            ResourceLocation facet,
            long subunits,
            int rangeMagnitude,
            int displayOrder,
            int evidenceCount,
            boolean major) {
    }

    /**
     * §8.2's total order: normalized absolute magnitude descending, then authored display order
     * ascending, then resource id ascending.
     *
     * <p>Every key is an integer or a string comparison, so the order is identical on every JVM and
     * across a packet round trip. That is a correctness requirement, not tidiness: a tie broken by
     * hash order would let the client and server disagree about which three traits a player is known
     * for.
     */
    public static final Comparator<FacetEvidence> DOMINANCE_ORDER = Comparator
            .comparingLong((FacetEvidence e) -> -normalizedMagnitude(e.subunits(), e.rangeMagnitude()))
            .thenComparingInt(FacetEvidence::displayOrder)
            .thenComparing(e -> e.facet().toString());

    /**
     * {@code |subunits| / rangeMagnitude}, re-scaled to basis points of the facet's own range so
     * facets with different ranges compare fairly. Saturating; a non-positive range magnitude yields
     * zero rather than dividing by zero.
     */
    public static long normalizedMagnitude(long subunits, int rangeMagnitude) {
        if (rangeMagnitude <= 0) {
            return 0L;
        }
        long magnitude = subunits == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(subunits);
        long scaled = multiply(magnitude, FULL_BP);
        long divisor = multiply(rangeMagnitude, SUBUNITS_PER_POINT);
        if (divisor <= 0L) {
            return 0L;
        }
        if (scaled == Long.MAX_VALUE) {
            return multiply(magnitude / divisor, FULL_BP);
        }
        return scaled / divisor;
    }

    /**
     * The facets eligible for a dominant label, best first, at most {@code limit} of them.
     *
     * <p>Eligibility needs magnitude <em>and</em> evidence (§8.2): a value can only describe a player
     * once at least {@code minEvidence} distinct credited incidents support it, unless one of those
     * incidents was authored as major evidence. Input order does not affect the result.
     */
    public static List<FacetEvidence> dominant(List<FacetEvidence> evidence, int minMagnitudePoints,
                                               int minEvidence, int limit) {
        List<FacetEvidence> eligible = new ArrayList<>();
        for (FacetEvidence entry : evidence) {
            if (entry == null) {
                continue;
            }
            int magnitude = Math.abs(toPoints(entry.subunits()));
            boolean enoughEvidence = entry.major() || entry.evidenceCount() >= Math.max(1, minEvidence);
            if (magnitude >= Math.max(1, minMagnitudePoints) && enoughEvidence) {
                eligible.add(entry);
            }
        }
        eligible.sort(DOMINANCE_ORDER);
        return List.copyOf(eligible.subList(0, Math.min(eligible.size(), Math.max(0, limit))));
    }
}
