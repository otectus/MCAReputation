package dev.otectus.mcareputation.incident;

import dev.otectus.mcareputation.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §36.1 group 7. */
class ResolutionTest {

    private static final ResolutionPolicy POLICY = ResolutionPolicy.DEFAULT;

    @Test
    void multipliersRoundTowardZero() {
        assertEquals(-6, POLICY.settledDelta(-8, IncidentStatus.APOLOGIZED));
        assertEquals(-2, POLICY.settledDelta(-8, IncidentStatus.ATONED));
        assertEquals(0, POLICY.settledDelta(-8, IncidentStatus.FORGIVEN));
        assertEquals(0, POLICY.settledDelta(-8, IncidentStatus.DISPROVEN));
    }

    @Test
    void anUnlistedResolutionLeavesTheScoreAlone() {
        ResolutionPolicy sparse = new ResolutionPolicy(java.util.Map.of(IncidentStatus.FORGIVEN, 0.0f));
        assertEquals(-8, sparse.settledDelta(-8, IncidentStatus.ATONED),
                "an unauthored resolution changes the story, not the number");
        assertEquals(0, sparse.settledDelta(-8, IncidentStatus.FORGIVEN));
    }

    @Test
    void resolvingMovesTheContributionAndReportsTheDelta() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        Optional<Integer> moved = record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.ATONED, 100L);
        assertEquals(Optional.of(6), moved, "-8 -> -2 is a +6 change to the score");
        assertEquals(-2, record.currentContribution());
        assertEquals(IncidentStatus.ATONED, record.status());
    }

    /** §33 rule 14: resolving twice cannot pay out twice. */
    @Test
    void repeatingTheSameResolutionIsANoOp() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        assertTrue(record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.ATONED, 100L).isPresent());
        assertTrue(record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.ATONED, 200L).isEmpty());
        assertEquals(-2, record.currentContribution());
    }

    @Test
    void aWeakerResolutionIsRefused() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.ATONED, 100L);
        assertTrue(record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.APOLOGIZED, 200L).isEmpty());
        assertEquals(IncidentStatus.ATONED, record.status());
        assertEquals(-2, record.currentContribution());
    }

    @Test
    void aStrongerResolutionMayReduceFurther() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.APOLOGIZED, 100L);
        assertEquals(-6, record.currentContribution());
        assertEquals(Optional.of(4), record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.ATONED, 200L));
        assertEquals(-2, record.currentContribution());
        assertEquals(Optional.of(2), record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.FORGIVEN, 300L));
        assertEquals(0, record.currentContribution());
    }

    /** §14/§15.2: a deed shown never to have happened cannot be turned back into a real one. */
    @Test
    void disprovenIsTerminal() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.DISPROVEN, 100L);
        assertEquals(0, record.currentContribution());
        assertTrue(record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.FORGIVEN, 200L).isEmpty());
        assertTrue(record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.ATONED, 300L).isEmpty());
        assertEquals(IncidentStatus.DISPROVEN, record.status());
    }

    /**
     * Expiry is bookkeeping, not a resolution: any genuine resolution arriving after it must still
     * land — including {@code APOLOGIZED}, whose shared strength a plain comparison would refuse.
     */
    @Test
    void expiryDoesNotBlockALaterGenuineResolution() {
        assertTrue(IncidentStatus.EXPIRED.canTransitionTo(IncidentStatus.APOLOGIZED),
                "a real apology after expiry is still recordable");
        assertTrue(IncidentStatus.EXPIRED.canTransitionTo(IncidentStatus.ATONED));
        assertTrue(IncidentStatus.EXPIRED.canTransitionTo(IncidentStatus.FORGIVEN));
        assertTrue(IncidentStatus.EXPIRED.canTransitionTo(IncidentStatus.DISPROVEN));
        assertFalse(IncidentStatus.EXPIRED.canTransitionTo(IncidentStatus.ACTIVE),
                "expiry cannot be re-armed into a standing penalty");
        assertFalse(IncidentStatus.EXPIRED.canTransitionTo(IncidentStatus.EXPIRED));
    }

    @Test
    void anExpiredRecordResolvesLikeAnActiveOne() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        record.markExpired(100L);
        assertEquals(IncidentStatus.EXPIRED, record.status());
        assertTrue(record.resolve(POLICY, DecayPolicy.NONE, IncidentStatus.APOLOGIZED, 200L).isPresent());
        assertEquals(IncidentStatus.APOLOGIZED, record.status());
    }

    /**
     * Resolution scales the <em>original</em> delta, so the outcome of atoning does not depend on how
     * long the player took to get around to it.
     */
    @Test
    void resolutionIsIndependentOfHowMuchDecayHasAlreadyRun() {
        DecayPolicy decay = DecayPolicy.linearToZero(0, 1);
        IncidentRecord prompt = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        IncidentRecord tardy = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);

        prompt.resolve(POLICY, decay, IncidentStatus.ATONED, 0L);
        tardy.reconcile(decay, 3 * DecayPolicy.TICKS_PER_DAY);
        tardy.resolve(POLICY, decay, IncidentStatus.ATONED, 3 * DecayPolicy.TICKS_PER_DAY);

        assertEquals(-2, prompt.settledDelta());
        assertEquals(-2, tardy.settledDelta());
    }

    /** A resolution may only ever move the contribution toward zero, never away from it. */
    @Test
    void resolutionNeverIncreasesAPenaltyThatDecayAlreadySoftened() {
        DecayPolicy decay = DecayPolicy.linearToZero(0, 3);
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        record.reconcile(decay, 2 * DecayPolicy.TICKS_PER_DAY);
        assertEquals(-2, record.currentContribution());

        record.resolve(POLICY, decay, IncidentStatus.APOLOGIZED, 2 * DecayPolicy.TICKS_PER_DAY);
        assertTrue(Math.abs(record.currentContribution()) <= 2,
                "apologising must not make things worse than they already were");
    }

    @Test
    void foldingZeroesTheContributionAndStopsFurtherDecay() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        java.util.UUID successor = java.util.UUID.randomUUID();
        assertEquals(8, record.foldInto(successor, 100L), "the score must gain back the whole -8");
        assertEquals(0, record.currentContribution());
        assertEquals(0, record.settledDelta());
        assertEquals(Optional.of(successor.toString()), record.context("superseded_by"));

        record.reconcile(DecayPolicy.linearToZero(0, 2), 10 * DecayPolicy.TICKS_PER_DAY);
        assertEquals(0, record.currentContribution(), "a folded record stays at zero");
        assertEquals(-8, record.baseDelta(), "but the history of what it was worth is intact");
    }

    /**
     * The §20.2 rollback seam: when a killing turns out to carry no public weight, the assault fold
     * that preceded it is undone bit-for-bit — an unseen murder must not refund a witnessed beating.
     */
    @Test
    void aFoldCanBeRolledBackExactly() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        int settledBefore = record.settledDelta();
        int currentBefore = record.currentContribution();

        record.foldInto(null, 100L);
        assertEquals(0, record.currentContribution());

        record.restoreContribution(settledBefore, currentBefore, 200L);
        assertEquals(-8, record.currentContribution());
        assertEquals(-8, record.settledDelta());
        assertTrue(record.contributes(), "the witnessed penalty is back in force");
    }

    @Test
    void resolvingWithNoPolicyIsSafe() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        assertTrue(record.resolve(null, null, IncidentStatus.FORGIVEN, 10L).isPresent());
        assertEquals(IncidentStatus.FORGIVEN, record.status());
    }

    @Test
    void nullStatusIsRefused() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        assertTrue(record.resolve(POLICY, DecayPolicy.NONE, null, 10L).isEmpty());
    }
}
