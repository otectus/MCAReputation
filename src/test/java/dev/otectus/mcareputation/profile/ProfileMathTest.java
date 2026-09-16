package dev.otectus.mcareputation.profile;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §23.1's arithmetic fixtures, asserted in exact subunits. UI rounding is asserted separately,
 * because the whole point of §8.3 is that the two are different numbers.
 */
class ProfileMathTest {

    private static final long DAY = 24_000L;

    @Test
    void oneAuthoredPointIsTenThousandSubunits() {
        assertEquals(10_000L, ProfileMath.SUBUNITS_PER_POINT);
        assertEquals(80_000L, ProfileMath.subunits(8));
        assertEquals(-120_000L, ProfileMath.subunits(-12));
    }

    /**
     * §23.1 "fraction preservation": two credited contributions of half a point aggregate to exactly
     * one point. Truncating each to a public integer first would produce zero — the defect §8.3 exists
     * to prevent.
     */
    @Test
    void halfPointContributionsAggregateToAWholePoint() {
        long first = ProfileMath.scaleByBasisPoints(ProfileMath.subunits(1), 5000);
        long second = ProfileMath.scaleByBasisPoints(ProfileMath.subunits(1), 5000);
        assertEquals(5_000L, first);
        assertEquals(0, ProfileMath.toPoints(first), "half a point is not yet a public point");
        assertEquals(1, ProfileMath.toPoints(ProfileMath.add(first, second)));
    }

    /** §23.1 "group credit": base 8 points at 100/100/50/25/0 percent. */
    @Test
    void theCreditScheduleProducesExactPoints() {
        long authored = ProfileMath.subunits(8);
        int[] schedule = {10_000, 10_000, 5_000, 2_500, 0};
        int[] expected = {8, 8, 4, 2, 0};
        for (int i = 0; i < schedule.length; i++) {
            assertEquals(expected[i], ProfileMath.toPoints(
                    ProfileMath.scaleByBasisPoints(authored, schedule[i])), "occurrence " + (i + 1));
        }
    }

    @Test
    void scalingTruncatesTowardZeroRatherThanAwayFromIt() {
        // 1 subunit at 50% is half a subunit: a discount always reduces magnitude.
        assertEquals(0L, ProfileMath.scaleByBasisPoints(1L, 5000));
        assertEquals(0L, ProfileMath.scaleByBasisPoints(-1L, 5000));
        assertEquals(-5_000L, ProfileMath.scaleByBasisPoints(-10_000L, 5000));
    }

    @Test
    void arithmeticSaturatesInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, ProfileMath.add(Long.MAX_VALUE, 1L));
        assertEquals(Long.MIN_VALUE, ProfileMath.add(Long.MIN_VALUE, -1L));
        assertEquals(Long.MAX_VALUE, ProfileMath.multiply(Long.MAX_VALUE, 2L));
        assertEquals(Long.MIN_VALUE, ProfileMath.multiply(Long.MAX_VALUE, -2L));
        assertEquals(Long.MAX_VALUE, ProfileMath.sum(Collections.nCopies(4, Long.MAX_VALUE / 2)));
    }

    @Test
    void saturatedScalingStaysOnItsOwnSideOfZero() {
        assertTrue(ProfileMath.scaleByBasisPoints(Long.MAX_VALUE, 5000) > 0L);
        assertTrue(ProfileMath.scaleByBasisPoints(Long.MIN_VALUE, 5000) < 0L);
    }

    @Test
    void ageIsQuantizedDownToWholeSteps() {
        assertEquals(0L, ProfileMath.quantizeAge(23_999L, DAY));
        assertEquals(DAY, ProfileMath.quantizeAge(DAY, DAY));
        assertEquals(DAY, ProfileMath.quantizeAge(DAY + 1L, DAY));
        assertEquals(14 * DAY, ProfileMath.quantizeAge(14 * DAY + 17L, DAY));
    }

    /** A backdated delivery whose occurrence is ahead of "now" must not age negatively. */
    @Test
    void negativeAgeIsTreatedAsZero() {
        assertEquals(0L, ProfileMath.quantizeAge(-500L, DAY));
        assertEquals(ProfileMath.subunits(8),
                ProfileMath.remainingAt(ProfileMath.subunits(8), -500L, 28 * DAY, DAY));
    }

    /**
     * §23.1 "linear profile age": authored 8, lifetime 28 days, effective age 14 days, no resolution
     * is current 4 — and that is independent of any scalar standing decay policy.
     */
    @Test
    void halfOfALifetimeLeavesHalfTheContribution() {
        long remaining = ProfileMath.remainingAt(ProfileMath.subunits(8), 14 * DAY, 28 * DAY, DAY);
        assertEquals(40_000L, remaining);
        assertEquals(4, ProfileMath.toPoints(remaining));
    }

    @Test
    void quantizationHoldsTheValueStillWithinAStep() {
        long lifetime = 28 * DAY;
        long atFourteen = ProfileMath.remainingAt(ProfileMath.subunits(8), 14 * DAY, lifetime, DAY);
        long nearlyFifteen = ProfileMath.remainingAt(ProfileMath.subunits(8), 15 * DAY - 1L, lifetime, DAY);
        assertEquals(atFourteen, nearlyFifteen, "the value may only move on a whole step boundary");
        assertNotEquals(atFourteen,
                ProfileMath.remainingAt(ProfileMath.subunits(8), 15 * DAY, lifetime, DAY));
    }

    @Test
    void aSpentLifetimeContributesExactlyZeroAndNeverNegative() {
        long lifetime = 28 * DAY;
        assertEquals(0L, ProfileMath.remainingAt(ProfileMath.subunits(8), lifetime, lifetime, DAY));
        assertEquals(0L, ProfileMath.remainingAt(ProfileMath.subunits(8), 400 * DAY, lifetime, DAY));
        assertEquals(0L, ProfileMath.remainingAt(ProfileMath.subunits(-8), 400 * DAY, lifetime, DAY));
    }

    @Test
    void negativeContributionsFadeTowardZeroWithTheirSignIntact() {
        long remaining = ProfileMath.remainingAt(ProfileMath.subunits(-12), 14 * DAY, 28 * DAY, DAY);
        assertEquals(-60_000L, remaining);
        assertEquals(-6, ProfileMath.toPoints(remaining));
    }

    @Test
    void aMalformedLifetimeContributesNothingRatherThanForever() {
        assertEquals(0L, ProfileMath.remainingAt(ProfileMath.subunits(8), 0L, 0L, DAY));
        assertEquals(0L, ProfileMath.remainingAt(ProfileMath.subunits(8), 0L, -1L, DAY));
    }

    @Test
    void ticksUntilSpentRoundsUpToAWholeStep() {
        assertEquals(28 * DAY, ProfileMath.ticksUntilSpent(28 * DAY, DAY));
        assertEquals(DAY, ProfileMath.ticksUntilSpent(DAY - 1L, DAY));
    }

    /**
     * §8.3: the raw total keeps meaning even when the public integer clamps to the edge of the range,
     * because §12.3 needs that evidence when a later opposing deed arrives.
     */
    @Test
    void theRawTotalSurvivesADisplayClamp() {
        long raw = ProfileMath.subunits(140);
        assertEquals(100, ProfileMath.publicValue(raw, -100, 100));
        assertEquals(140, ProfileMath.toPoints(raw), "the evidence itself is not clamped");
    }

    @Test
    void recognitionIsFlooredAtZeroAndCappedAtOneThousand() {
        assertEquals(0, ProfileMath.publicRecognition(-50_000L));
        assertEquals(20, ProfileMath.publicRecognition(ProfileMath.subunits(20)));
        assertEquals(ProfileMath.MAX_RECOGNITION,
                ProfileMath.publicRecognition(ProfileMath.subunits(5000)));
    }

    // ------------------------------------------------------------------
    // Dominance ordering (§8.2)
    // ------------------------------------------------------------------

    private static ProfileMath.FacetEvidence evidence(String path, int points, int order) {
        return new ProfileMath.FacetEvidence(new ResourceLocation("mcareputation", path),
                ProfileMath.subunits(points), 100, order, 2, false);
    }

    @Test
    void dominantFacetsAreOrderedByNormalizedMagnitudeThenOrderThenId() {
        List<ProfileMath.FacetEvidence> input = List.of(
                evidence("compassion", 20, 30),
                evidence("violence", 40, 70),
                evidence("bravery", 20, 20));
        List<ProfileMath.FacetEvidence> sorted =
                ProfileMath.dominant(input, 10, 2, ProfileMath.MAX_DOMINANT_FACETS);
        assertEquals(List.of("mcareputation:violence", "mcareputation:bravery",
                        "mcareputation:compassion"),
                sorted.stream().map(e -> e.facet().toString()).toList());
    }

    /** Ties must be broken identically on every JVM and after a packet round trip (§8.2). */
    @Test
    void theOrderIsIndependentOfInsertionOrder() {
        List<ProfileMath.FacetEvidence> input = new ArrayList<>(List.of(
                evidence("mercy", 20, 60),
                evidence("bravery", 20, 60),
                evidence("violence", 20, 60),
                evidence("reliability", 20, 10)));
        List<String> expected = ProfileMath.dominant(input, 10, 2, 4).stream()
                .map(e -> e.facet().toString()).toList();
        assertEquals(List.of("mcareputation:reliability", "mcareputation:bravery",
                "mcareputation:mercy", "mcareputation:violence"), expected);

        for (int shift = 0; shift < input.size(); shift++) {
            Collections.rotate(input, 1);
            assertEquals(expected, ProfileMath.dominant(input, 10, 2, 4).stream()
                    .map(e -> e.facet().toString()).toList(), "rotation " + shift);
        }
        List<ProfileMath.FacetEvidence> reversed = new ArrayList<>(input);
        Collections.reverse(reversed);
        assertEquals(expected, ProfileMath.dominant(reversed, 10, 2, 4).stream()
                .map(e -> e.facet().toString()).toList());
    }

    /** A negative value is as dominant as the same positive magnitude — it just reads differently. */
    @Test
    void negativeEvidenceCompetesOnMagnitude() {
        List<ProfileMath.FacetEvidence> sorted = ProfileMath.dominant(List.of(
                evidence("compassion", -40, 30),
                evidence("bravery", 20, 20)), 10, 2, 3);
        assertEquals("mcareputation:compassion", sorted.get(0).facet().toString());
    }

    /** Facets with different ranges compare on their own scale, not on raw points. */
    @Test
    void normalizationComparesAcrossDifferentRanges() {
        ProfileMath.FacetEvidence wide = new ProfileMath.FacetEvidence(
                new ResourceLocation("mcareputation", "wide"), ProfileMath.subunits(30), 100, 10, 2, false);
        ProfileMath.FacetEvidence narrow = new ProfileMath.FacetEvidence(
                new ResourceLocation("mcareputation", "narrow"), ProfileMath.subunits(20), 20, 20, 2, false);
        List<ProfileMath.FacetEvidence> sorted = ProfileMath.dominant(List.of(wide, narrow), 10, 2, 3);
        assertEquals("mcareputation:narrow", sorted.get(0).facet().toString(),
                "20 of a possible 20 is more of the facet than 30 of a possible 100");
    }

    /** §8.2: a label needs magnitude and evidence; one ordinary deed is not a demonstration. */
    @Test
    void oneOrdinaryDeedIsNotEnoughEvidenceForALabel() {
        ProfileMath.FacetEvidence single = new ProfileMath.FacetEvidence(
                new ResourceLocation("mcareputation", "bravery"), ProfileMath.subunits(40), 100, 20, 1, false);
        assertTrue(ProfileMath.dominant(List.of(single), 10, 2, 3).isEmpty());

        ProfileMath.FacetEvidence major = new ProfileMath.FacetEvidence(
                single.facet(), single.subunits(), 100, 20, 1, true);
        assertEquals(1, ProfileMath.dominant(List.of(major), 10, 2, 3).size(),
                "an explicitly authored major deed may qualify alone");
    }

    @Test
    void weakEvidenceIsNotDominantEvenWithManyIncidents() {
        ProfileMath.FacetEvidence weak = new ProfileMath.FacetEvidence(
                new ResourceLocation("mcareputation", "mercy"), ProfileMath.subunits(3), 100, 60, 9, false);
        assertTrue(ProfileMath.dominant(List.of(weak), 10, 2, 3).isEmpty());
    }

    @Test
    void atMostThreeLabelsAreReturned() {
        List<ProfileMath.FacetEvidence> many = List.of(
                evidence("a", 90, 10), evidence("b", 80, 20), evidence("c", 70, 30),
                evidence("d", 60, 40), evidence("e", 50, 50));
        assertEquals(3, ProfileMath.dominant(many, 10, 2, ProfileMath.MAX_DOMINANT_FACETS).size());
    }
}
