package dev.otectus.mcareputation.incident;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.credit.CreditDecision;
import dev.otectus.mcareputation.profile.IncidentProfileDefinition;
import dev.otectus.mcareputation.profile.IncidentProfileEvidence;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §12.1's resolution modes, per channel, on an incident's own lifecycle.
 *
 * <p>The mistake this is written against is running every profile channel through the scalar penalty
 * multiplier. An apology does not undo the violence a killing demonstrated, and it certainly does not
 * make the player less famous for it — {@code recognition} and {@code historical} evidence keeps its
 * magnitude and fades on its own authored lifetime, while only {@code evaluative} evidence settles at
 * the frozen, explicitly authored multipliers.
 *
 * <p>Pure: one record, no server, no level, no registries.
 */
class ProfileSettlementTest {

    private static final long DAY = 24_000L;
    private static final ResourceLocation BRAVERY = ResourceLocation.fromNamespaceAndPath("mcareputation", "bravery");
    private static final ResourceLocation COMPASSION = ResourceLocation.fromNamespaceAndPath("mcareputation", "compassion");

    /** 6 authored recognition points, in subunits. */
    private static final long RECOGNITION = 60_000L;
    /** 8 authored bravery points: something that was actually demonstrated. */
    private static final long HISTORICAL = 80_000L;
    /** -12 authored compassion points: how the wrong reflects on the player now. */
    private static final long EVALUATIVE = -120_000L;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static IncidentProfileEvidence.Channel channel(
            Optional<ResourceLocation> facet, long units, long lifetimeDays,
            IncidentProfileDefinition.ResolutionMode mode) {
        return new IncidentProfileEvidence.Channel(facet, units, units, units, lifetimeDays * DAY, DAY,
                mode, IncidentProfileDefinition.ResolutionMultipliers.DEFAULT);
    }

    /** One deed with all three modes on it, so a change in one is visible against the other two. */
    private static IncidentRecord profiled() {
        IncidentProfileEvidence evidence = IncidentProfileEvidence.of(
                IncidentProfileEvidence.Origin.LIVE, TestFixtures.PROFILE, 7L, 1L,
                Optional.of(channel(Optional.empty(), RECOGNITION, 56,
                        IncidentProfileDefinition.ResolutionMode.RECOGNITION)),
                List.of(channel(Optional.of(BRAVERY), HISTORICAL, 28,
                                IncidentProfileDefinition.ResolutionMode.HISTORICAL),
                        channel(Optional.of(COMPASSION), EVALUATIVE, 28,
                                IncidentProfileDefinition.ResolutionMode.EVALUATIVE)),
                CreditDecision.unlimited(CreditDecision.Reason.NO_POLICY));
        IncidentRecord record = TestFixtures.record(-12);
        record.attachProfileEvidence(evidence);
        record.initializeProfileClock(0L, 0L);
        return record;
    }

    private static long recognition(IncidentRecord record) {
        return record.profileEvidence().orElseThrow().recognition().orElseThrow().current();
    }

    private static long facet(IncidentRecord record, ResourceLocation id) {
        return record.profileEvidence().orElseThrow().facet(id).orElseThrow().current();
    }

    private static void resolve(IncidentRecord record, IncidentStatus status, long gameTime) {
        assertTrue(record.resolve(ResolutionPolicy.DEFAULT, DecayPolicy.NONE, status, gameTime)
                .isPresent(), "the transition to " + status + " should have been accepted");
    }

    // ------------------------------------------------------------------
    // The three modes
    // ------------------------------------------------------------------

    @Test
    void anApologyReducesOnlyTheEvaluativeChannel() {
        IncidentRecord record = profiled();

        resolve(record, IncidentStatus.APOLOGIZED, 100L);

        assertEquals(RECOGNITION, recognition(record),
                "apologising does not make a deed less widely known (§7.3)");
        assertEquals(HISTORICAL, facet(record, BRAVERY),
                "nor does it undo what was actually demonstrated");
        assertEquals(-90_000L, facet(record, COMPASSION),
                "only the evaluative channel settles, at the frozen 0.75");
        assertEquals(EVALUATIVE, record.profileEvidence().orElseThrow().facet(COMPASSION).orElseThrow()
                .credited(), "and the credited quantity it was computed from is never rewritten");
    }

    @Test
    void theAuthoredProgressionSettlesAtEachStepAndOnlyDownwards() {
        IncidentRecord record = profiled();

        resolve(record, IncidentStatus.APOLOGIZED, 100L);
        assertEquals(-90_000L, facet(record, COMPASSION));

        resolve(record, IncidentStatus.ATONED, 200L);
        assertEquals(-30_000L, facet(record, COMPASSION), "atoned is the frozen 0.25");

        resolve(record, IncidentStatus.FORGIVEN, 300L);
        assertEquals(0L, facet(record, COMPASSION), "forgiven is the frozen zero");
        assertEquals(RECOGNITION, recognition(record), "recognition survives forgiveness too");
        assertEquals(HISTORICAL, facet(record, BRAVERY));
        assertTrue(record.profileContributes(),
                "the deed still carries evidence: forgiveness settled one channel, not the record");
    }

    @Test
    void disprovenZeroesEveryChannelInEveryMode() {
        IncidentRecord record = profiled();

        resolve(record, IncidentStatus.DISPROVEN, 100L);

        assertEquals(0L, recognition(record),
                "a deed shown never to have happened supplies no notoriety either (§7.3)");
        assertEquals(0L, facet(record, BRAVERY));
        assertEquals(0L, facet(record, COMPASSION));
        assertEquals(0L, record.profileCurrentMagnitude());
        assertFalse(record.profileContributes());
        assertFalse(record.hasLiveProfileEvidence(), "and it stops protecting itself from pruning");
        assertEquals(RECOGNITION, record.profileEvidence().orElseThrow().recognition().orElseThrow()
                .authored(), "the authored quantities stay on disk; only the derived value is zero");
    }

    @Test
    void expiryIsBookkeepingAndSettlesNothing() {
        IncidentRecord record = profiled();

        record.markExpired(100L);
        assertEquals(0L, record.settleProfileEvidence(),
                "EXPIRED is not a resolution, so no channel settles");
        assertEquals(EVALUATIVE, facet(record, COMPASSION));

        // §15.2: a genuine resolution arriving after expiry still lands, and still settles.
        resolve(record, IncidentStatus.APOLOGIZED, 200L);
        assertEquals(-90_000L, facet(record, COMPASSION));
    }

    // ------------------------------------------------------------------
    // Monotonicity
    // ------------------------------------------------------------------

    /**
     * Every legal transition order, and in none of them does a later transition restore magnitude
     * (§12.1). Enumerated rather than sampled, because "no resurrection" has to hold for the order
     * nobody thought to try.
     */
    @Test
    void settlementIsMonotonicAcrossEveryLegalTransitionOrder() {
        List<IncidentStatus> progression = List.of(IncidentStatus.APOLOGIZED, IncidentStatus.ATONED,
                IncidentStatus.FORGIVEN, IncidentStatus.DISPROVEN);
        int orders = 0;
        for (int mask = 1; mask < (1 << progression.size()); mask++) {
            List<IncidentStatus> sequence = new ArrayList<>();
            for (int bit = 0; bit < progression.size(); bit++) {
                if ((mask & (1 << bit)) != 0) {
                    sequence.add(progression.get(bit));
                }
            }
            orders++;
            IncidentRecord record = profiled();
            long previous = record.profileCurrentMagnitude();
            long time = 100L;
            for (IncidentStatus status : sequence) {
                resolve(record, status, time);
                time += 100L;
                long now = record.profileCurrentMagnitude();
                assertTrue(now <= previous, sequence + " restored magnitude at " + status
                        + " (" + previous + " -> " + now + ")");
                previous = now;
            }
            if (sequence.contains(IncidentStatus.DISPROVEN)) {
                assertEquals(0L, record.profileCurrentMagnitude(), sequence + " must end at zero");
            }
        }
        assertEquals(15, orders, "all fifteen non-empty ascending sequences were exercised");
    }

    @Test
    void settlingTwiceAtTheSameStatusAndAgeChangesNothing() {
        IncidentRecord record = profiled();
        record.reconcileProfile(14 * DAY, 0L);
        resolve(record, IncidentStatus.APOLOGIZED, 14 * DAY);
        IncidentProfileEvidence settled = record.profileEvidence().orElseThrow();

        assertEquals(0L, record.settleProfileEvidence(), "a second settlement compounds nothing");
        assertSame(settled, record.profileEvidence().orElseThrow(),
                "and it does not even replace the payload, so a rollback reference stays valid");
    }

    // ------------------------------------------------------------------
    // Settlement against the credited original, then age
    // ------------------------------------------------------------------

    @Test
    void aSettlementComputesFromTheCreditedOriginalAndTheEffectiveAge() {
        IncidentRecord record = profiled();

        // Half the 28-day lifetime gone: the evaluative channel is at -60000 on its own.
        record.reconcileProfile(14 * DAY, 0L);
        assertEquals(-60_000L, facet(record, COMPASSION));

        resolve(record, IncidentStatus.APOLOGIZED, 14 * DAY);
        assertEquals(-45_000L, facet(record, COMPASSION),
                "0.75 of the credited original, aged 14 of 28 days");

        // And ageing continues from the settled quantity rather than restarting from the original.
        record.reconcileProfile(21 * DAY, 0L);
        assertEquals(-22_500L, facet(record, COMPASSION));
        assertEquals(RECOGNITION * 35 / 56, recognition(record),
                "recognition keeps fading on its own longer lifetime, unaffected by the apology");
    }

    @Test
    void aSpentChannelStaysSpentAndStopsProtectingTheRecord() {
        IncidentRecord record = profiled();

        record.reconcileProfile(56 * DAY, 0L);

        assertEquals(0L, record.profileCurrentMagnitude(), "every authored lifetime is finite (§12.3)");
        assertFalse(record.hasLiveProfileEvidence());
        assertTrue(record.hasProfileEvidence(), "the evidence itself is still on the record");
    }
}
