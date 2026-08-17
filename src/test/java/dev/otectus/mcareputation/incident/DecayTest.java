package dev.otectus.mcareputation.incident;

import dev.otectus.mcareputation.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §36.1 group 6. */
class DecayTest {

    private static final long DAY = DecayPolicy.TICKS_PER_DAY;

    /** The shipped assault policy: nothing for two days, then two points a day toward zero. */
    private static final DecayPolicy ASSAULT_DECAY = DecayPolicy.linearToZero(2 * DAY, 2);

    @Test
    void noneNeverMoves() {
        assertEquals(-8, DecayPolicy.NONE.contributionAt(-8, 0L));
        assertEquals(-8, DecayPolicy.NONE.contributionAt(-8, 100 * DAY));
    }

    @Test
    void nothingHappensDuringTheDelay() {
        assertEquals(-8, ASSAULT_DECAY.contributionAt(-8, 0L));
        assertEquals(-8, ASSAULT_DECAY.contributionAt(-8, 2 * DAY));
        assertEquals(-8, ASSAULT_DECAY.contributionAt(-8, 2 * DAY + DAY - 1));
    }

    @Test
    void stepsOncePerCompleteDayAfterTheDelay() {
        assertEquals(-6, ASSAULT_DECAY.contributionAt(-8, 2 * DAY + DAY));
        assertEquals(-4, ASSAULT_DECAY.contributionAt(-8, 2 * DAY + 2 * DAY));
        assertEquals(-2, ASSAULT_DECAY.contributionAt(-8, 2 * DAY + 3 * DAY));
        assertEquals(0, ASSAULT_DECAY.contributionAt(-8, 2 * DAY + 4 * DAY));
    }

    @Test
    void stopsAtZeroAndNeverCrossesIt() {
        assertEquals(0, ASSAULT_DECAY.contributionAt(-8, 2 * DAY + 40 * DAY));
        assertEquals(0, ASSAULT_DECAY.contributionAt(-8, Long.MAX_VALUE / 2));
    }

    @Test
    void positiveContributionsDecayToo() {
        assertEquals(4, ASSAULT_DECAY.contributionAt(8, 2 * DAY + 2 * DAY));
        assertEquals(0, ASSAULT_DECAY.contributionAt(8, 2 * DAY + 10 * DAY));
    }

    @Test
    void ticksUntilZeroIsCorrectAndTerminates() {
        assertEquals(2 * DAY + 4 * DAY, ASSAULT_DECAY.ticksUntilZero(-8));
        assertEquals(2 * DAY + 4 * DAY, ASSAULT_DECAY.ticksUntilZero(8));
        assertEquals(2 * DAY + DAY, ASSAULT_DECAY.ticksUntilZero(-1), "a partial day still rounds up");
        assertEquals(Long.MAX_VALUE, DecayPolicy.NONE.ticksUntilZero(-8));
        assertEquals(0L, ASSAULT_DECAY.ticksUntilZero(0));
    }

    // ------------------------------------------------------------------
    // Record-level reconciliation
    // ------------------------------------------------------------------

    @Test
    void reconciliationIsIdempotent() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.VILLAGE, 0L);
        assertEquals(0, record.reconcile(ASSAULT_DECAY, 0L));
        assertEquals(2, record.reconcile(ASSAULT_DECAY, 3 * DAY), "-8 -> -6 is a +2 change to the score");
        assertEquals(0, record.reconcile(ASSAULT_DECAY, 3 * DAY), "running it again must move nothing");
        assertEquals(-6, record.currentContribution());
    }

    /**
     * §15.1 and §33 rule 13: winding the clock back must never hand contribution back. The counter is
     * monotonic, so a rewind adds nothing and a later re-advance does not double-count either.
     */
    @Test
    void clockRollbackNeverRestoresContribution() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.VILLAGE, 0L);
        record.reconcile(ASSAULT_DECAY, 4 * DAY);
        assertEquals(-4, record.currentContribution());

        assertEquals(0, record.reconcile(ASSAULT_DECAY, 0L), "/time set 0 changes nothing");
        assertEquals(-4, record.currentContribution());

        assertEquals(0, record.reconcile(ASSAULT_DECAY, 4 * DAY),
                "re-reaching the same time must not decay a second time");
        assertEquals(-4, record.currentContribution());

        assertEquals(2, record.reconcile(ASSAULT_DECAY, 5 * DAY), "genuine progress still decays");
        assertEquals(-2, record.currentContribution());
    }

    @Test
    void elapsedCounterOnlyMovesForward() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.VILLAGE, 100L);
        record.reconcile(ASSAULT_DECAY, 1000L);
        long afterForward = record.decayElapsedTicks();
        record.reconcile(ASSAULT_DECAY, 50L);
        assertEquals(afterForward, record.decayElapsedTicks());
        assertTrue(afterForward > 0L);
    }

    @Test
    void decayAppliesFromCreationNotFromAbsoluteWorldTime() {
        // An incident created on day 400 of a world should not arrive already fully decayed.
        IncidentRecord late = TestFixtures.record(-8, IncidentVisibility.VILLAGE, 400 * DAY);
        assertEquals(0, late.reconcile(ASSAULT_DECAY, 400 * DAY));
        assertEquals(-8, late.currentContribution());
    }

    /**
     * Expiry is assigned during community reconciliation — the only place that computes it — so a
     * fully decayed record past its retention window actually reads "expired" in the ledger instead
     * of claiming ACTIVE forever.
     */
    @Test
    void reconciliationAssignsExpiredOnceDecayAndRetentionHaveRun() {
        DecayPolicy decay = DecayPolicy.linearToZero(0, 8);
        IncidentDefinition definition = new IncidentDefinition(
                net.minecraft.network.chat.Component.translatable("test.incident"),
                -8, IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE, java.util.List.of(),
                java.util.Optional.of(3 * DAY), decay, ResolutionPolicy.DEFAULT, GossipSpec.NONE,
                false, java.util.Optional.of(100), false, false);
        IncidentRegistry.replaceAll(java.util.Map.of(TestFixtures.ASSAULT, definition));
        try {
            var community = new dev.otectus.mcareputation.state.CommunityReputationRecord(
                    TestFixtures.OVERWORLD_3);
            IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.VILLAGE, 0L);
            community.addIncident(record);
            community.recomputeScore(-1000, 1000);

            community.reconcile(2 * DAY, -1000, 1000);
            assertEquals(0, record.currentContribution(), "fully decayed after one day");
            assertEquals(IncidentStatus.ACTIVE, record.status(),
                    "decayed but inside retention: not yet expired");

            assertTrue(community.reconcile(4 * DAY, -1000, 1000),
                    "assigning expiry is a change worth persisting");
            assertEquals(IncidentStatus.EXPIRED, record.status());

            assertTrue(record.resolve(ResolutionPolicy.DEFAULT, decay, IncidentStatus.APOLOGIZED,
                    5 * DAY).isPresent(), "a genuine apology still lands after expiry");
        } finally {
            IncidentRegistry.replaceAll(java.util.Map.of());
        }
    }

    @Test
    void validationRejectsANonTerminatingPolicy() {
        assertTrue(DecayPolicy.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE,
                        com.google.gson.JsonParser.parseString(
                                "{\"type\":\"linear_to_zero\",\"amount_per_day\":0}"))
                .error().isPresent(), "amount_per_day 0 would never reach zero");
        assertTrue(DecayPolicy.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE,
                        com.google.gson.JsonParser.parseString(
                                "{\"type\":\"linear_to_zero\",\"delay_ticks\":-1,\"amount_per_day\":2}"))
                .error().isPresent());
    }
}
