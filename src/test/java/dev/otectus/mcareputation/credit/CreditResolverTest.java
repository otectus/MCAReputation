package dev.otectus.mcareputation.credit;

import com.mojang.serialization.DataResult;
import dev.otectus.mcareputation.data.ReputationReloadListener;
import dev.otectus.mcareputation.profile.ProfileMath;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §23.1's repeat-credit fixtures, plus the schema rules §9.6 puts on a schedule.
 *
 * <p>The three fixtures that matter most are the ones an exploit would exercise: group credit,
 * subject ceiling, and fresh-subject rotation. If rotation could restore a spent group allowance,
 * every other guard in §10 would be decoration.
 */
class CreditResolverTest {

    private static final ResourceLocation GROUP = new ResourceLocation("mcareputation", "rescue_service");

    private static CreditPolicy policy(List<Integer> schedule, int tailBp,
                                       CreditPolicy.SubjectLimit limit) {
        return new CreditPolicy(GROUP, 336_000L, schedule, tailBp,
                CreditPolicy.Scope.PLAYER_COMMUNITY, Optional.ofNullable(limit));
    }

    private static CreditDecision resolve(CreditPolicy policy, int groupOrdinal,
                                          OptionalInt subjectOrdinal) {
        return CreditResolver.resolve(Optional.of(policy), true, groupOrdinal, subjectOrdinal, false);
    }

    /** §23.1 "group credit": 100/100/50/25/0 percent, base 8 points, no subject rule. */
    @Test
    void theGroupScheduleWalksDownAndStaysAtTheTail() {
        CreditPolicy rescue = policy(List.of(10_000, 10_000, 5_000, 2_500, 0), 0, null);
        int[] expectedPoints = {8, 8, 4, 2, 0, 0, 0};
        for (int ordinal = 0; ordinal < expectedPoints.length; ordinal++) {
            CreditDecision decision = resolve(rescue, ordinal, OptionalInt.empty());
            assertEquals(expectedPoints[ordinal],
                    ProfileMath.toPoints(decision.applyTo(ProfileMath.subunits(8))),
                    "occurrence " + (ordinal + 1));
        }
        assertTrue(resolve(rescue, 4, OptionalInt.empty()).isSuppressed(),
                "the fifth outcome is still a deed, but it supplies no positive evidence");
    }

    /** §23.1 "subject ceiling": group 100/100/50 against subject 100/50/0 gives 100/50/0. */
    @Test
    void theSubjectRuleIsASecondCeilingNotASecondAllowance() {
        CreditPolicy rescue = policy(List.of(10_000, 10_000, 5_000), 0,
                new CreditPolicy.SubjectLimit("beneficiary", List.of(10_000, 5_000, 0), 0));
        assertEquals(10_000, resolve(rescue, 0, OptionalInt.of(0)).effectiveBp());
        assertEquals(5_000, resolve(rescue, 1, OptionalInt.of(1)).effectiveBp());
        assertEquals(0, resolve(rescue, 2, OptionalInt.of(2)).effectiveBp());
        assertEquals(CreditDecision.Reason.SUBJECT_CEILING,
                resolve(rescue, 1, OptionalInt.of(1)).reason());
    }

    /** §23.1 "fresh-subject rotation": a new beneficiary cannot restore a spent group allowance. */
    @Test
    void rotatingSubjectsCannotRestoreTheGroupAllowance() {
        CreditPolicy rescue = policy(List.of(10_000, 10_000, 5_000, 2_500, 0), 0,
                new CreditPolicy.SubjectLimit("beneficiary", List.of(10_000, 5_000, 0), 0));
        // Group allowance exhausted, but this is the first time helping this particular villager.
        CreditDecision decision = resolve(rescue, 9, OptionalInt.of(0));
        assertEquals(10_000, decision.subjectBp(), "the subject ceiling is genuinely fresh");
        assertEquals(0, decision.groupBp());
        assertEquals(0, decision.effectiveBp(), "the minimum rule keeps the group limit binding");
        assertEquals(CreditDecision.Reason.GROUP_SCHEDULE, decision.reason());
    }

    /** §10.2: missing subject data takes the conservative shared bucket, never a fresh allowance. */
    @Test
    void aMissingSubjectTakesTheConservativeBucket() {
        CreditPolicy rescue = policy(List.of(10_000, 10_000), 10_000,
                new CreditPolicy.SubjectLimit("beneficiary", List.of(10_000, 5_000), 0));
        CreditDecision decision = resolve(rescue, 0, OptionalInt.empty());
        assertEquals(0, decision.effectiveBp());
        assertEquals(CreditDecision.Reason.SUBJECT_MISSING, decision.reason());
    }

    /** §10.5: at capacity the answer is zero new positive credit, not a restored allowance. */
    @Test
    void capacityOverflowGrantsNoNewPositiveCredit() {
        CreditPolicy rescue = policy(List.of(10_000), 10_000, null);
        CreditDecision decision =
                CreditResolver.resolve(Optional.of(rescue), true, 0, OptionalInt.empty(), true);
        assertEquals(0, decision.effectiveBp());
        assertEquals(CreditDecision.Reason.CAPACITY_OVERFLOW, decision.reason());
    }

    @Test
    void noPolicyMeansFullCreditAndNothingTracked() {
        CreditDecision decision =
                CreditResolver.resolve(Optional.empty(), true, 7, OptionalInt.of(7), false);
        assertTrue(decision.isFullCredit());
        assertEquals(CreditDecision.Reason.NO_POLICY, decision.reason());
        assertTrue(decision.group().isEmpty());
    }

    /**
     * §23.1 "adverse repeat" and I07: two distinct negative events are worth -8 each. No reward
     * policy reduces the second, and the guard is structural rather than a caller's responsibility.
     */
    @Test
    void adverseContributionsAreNeverDiscounted() {
        CreditPolicy rescue = policy(List.of(10_000, 0), 0, null);
        CreditDecision exhausted = resolve(rescue, 1, OptionalInt.empty());
        assertEquals(0, exhausted.effectiveBp());
        assertEquals(ProfileMath.subunits(-8), exhausted.applyTo(ProfileMath.subunits(-8)),
                "a suppressed allowance must not make harm cheaper");
    }

    /** §10.4: a non-commendable profile is reported as such rather than silently discounted. */
    @Test
    void aNonCommendableProfileIsNeverDiscounted() {
        CreditPolicy rescue = policy(List.of(10_000, 0), 0, null);
        CreditDecision decision =
                CreditResolver.resolve(Optional.of(rescue), false, 5, OptionalInt.empty(), false);
        assertTrue(decision.isFullCredit());
        assertEquals(CreditDecision.Reason.NOT_COMMENDABLE, decision.reason());
    }

    @Test
    void creditIsAppliedInSubunitsSoSmallContributionsSurvive() {
        CreditPolicy rescue = policy(List.of(2_500), 0, null);
        CreditDecision decision = resolve(rescue, 0, OptionalInt.empty());
        assertEquals(2_500L, decision.applyTo(ProfileMath.subunits(1)),
                "a quarter point stays evidence instead of truncating to nothing");
    }

    // ------------------------------------------------------------------
    // Schema (§9.6)
    // ------------------------------------------------------------------

    private static DataResult<CreditPolicy> parse(String json) {
        return ReputationReloadListener.parseStrictForTest(CreditPolicy.CODEC, json);
    }

    @Test
    void theSpecsExamplePolicyParses() {
        DataResult<CreditPolicy> parsed = parse("""
                {
                  "group": "mcareputation:rescue_service",
                  "window_ticks": 336000,
                  "credit_schedule_bp": [10000, 10000, 5000, 2500, 0],
                  "tail_bp": 0,
                  "scope": "player_community",
                  "subject_limit": {
                    "role": "beneficiary",
                    "credit_schedule_bp": [10000, 5000, 0],
                    "tail_bp": 0
                  }
                }
                """);
        assertTrue(parsed.error().isEmpty(), () -> parsed.error().orElseThrow().message());
        CreditPolicy policy = parsed.result().orElseThrow();
        assertEquals(336_000L, policy.windowTicks());
        assertEquals("beneficiary", policy.subjectLimit().orElseThrow().role());
        assertFalse(policy.permitsIndefiniteCredit());
    }

    @Test
    void anIncreasingScheduleIsRejected() {
        DataResult<CreditPolicy> parsed = parse("""
                {"group": "mcareputation:g", "window_ticks": 20,
                 "credit_schedule_bp": [5000, 10000]}
                """);
        assertTrue(parsed.error().isPresent());
        assertTrue(parsed.error().orElseThrow().message().contains("non-increasing"));
    }

    @Test
    void aTailAboveTheLastEntryIsRejected() {
        DataResult<CreditPolicy> parsed = parse("""
                {"group": "mcareputation:g", "window_ticks": 20,
                 "credit_schedule_bp": [10000, 0], "tail_bp": 5000}
                """);
        assertTrue(parsed.error().isPresent());
        assertTrue(parsed.error().orElseThrow().message().contains("tail_bp"));
    }

    @Test
    void aPercentageAboveOneHundredIsRejected() {
        assertTrue(parse("""
                {"group": "mcareputation:g", "window_ticks": 20,
                 "credit_schedule_bp": [10001]}
                """).error().isPresent());
    }

    @Test
    void anEmptyScheduleIsRejected() {
        assertTrue(parse("""
                {"group": "mcareputation:g", "window_ticks": 20, "credit_schedule_bp": []}
                """).error().isPresent());
    }

    @Test
    void aWindowOutsideTheHardBoundsIsRejected() {
        assertTrue(parse("""
                {"group": "mcareputation:g", "window_ticks": 19, "credit_schedule_bp": [10000]}
                """).error().isPresent());
        assertTrue(parse("""
                {"group": "mcareputation:g", "window_ticks": 100000001,
                 "credit_schedule_bp": [10000]}
                """).error().isPresent());
    }

    /** §9.6: a duplicate key must be reported, not resolved to the last value. */
    @Test
    void aDuplicateKeyIsRejected() {
        DataResult<CreditPolicy> parsed = parse("""
                {"group": "mcareputation:g", "window_ticks": 20, "window_ticks": 40,
                 "credit_schedule_bp": [10000]}
                """);
        assertTrue(parsed.error().isPresent());
        assertTrue(parsed.error().orElseThrow().message().contains("duplicate key"));
    }

    /** A present-but-malformed optional block is an error, never a silent default (StrictCodecs). */
    @Test
    void aMalformedSubjectLimitIsNotSilentlyDropped() {
        DataResult<CreditPolicy> parsed = parse("""
                {"group": "mcareputation:g", "window_ticks": 20, "credit_schedule_bp": [10000],
                 "subject_limit": {"role": "beneficiary", "credit_schedule_bp": [0, 10000]}}
                """);
        assertTrue(parsed.error().isPresent());
        assertTrue(parsed.error().orElseThrow().message().contains("subject_limit"));
    }

    @Test
    void anUnknownScopeIsRejected() {
        assertTrue(parse("""
                {"group": "mcareputation:g", "window_ticks": 20, "credit_schedule_bp": [10000],
                 "scope": "per_villager_per_session"}
                """).error().isPresent());
    }

    @Test
    void aScheduleLongerThanTheBoundIsRejected() {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < CreditPolicy.MAX_SCHEDULE_ENTRIES + 1; i++) {
            entries.append(i == 0 ? "" : ", ").append(10_000);
        }
        assertTrue(parse("{\"group\": \"mcareputation:g\", \"window_ticks\": 20, "
                + "\"credit_schedule_bp\": [" + entries + "]}").error().isPresent());
    }
}
