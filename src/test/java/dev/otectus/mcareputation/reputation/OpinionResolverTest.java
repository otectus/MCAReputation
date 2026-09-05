package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.VillagerOpinion.OpinionBasis;
import dev.otectus.mcareputation.incident.AwarenessResolver;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-villager fold: same ledger, different villager, different answer. */
class OpinionResolverTest {

    private static final int MIN_DELAY = 6000;
    private static final int MAX_DELAY = 48000;
    private static final int HEARSAY = 50;
    private static final int INVOLVED = 150;
    private static final int MIN_SCORE = -1000;
    private static final int MAX_SCORE = 1000;

    private static CommunityReputationRecord ledger(IncidentRecord... incidents) {
        CommunityReputationRecord record = new CommunityReputationRecord(TestFixtures.OVERWORLD_3);
        for (IncidentRecord incident : incidents) {
            record.addIncident(incident);
        }
        return record;
    }

    private static OpinionResolver.Opinion resolve(CommunityReputationRecord record, UUID villager,
                                                   boolean resident, long gameTime) {
        return OpinionResolver.resolve(record, villager, resident, gameTime, MIN_DELAY, MAX_DELAY,
                HEARSAY, INVOLVED, MIN_SCORE, MAX_SCORE);
    }

    /** A witness carries the whole weight of what they saw. */
    @Test
    void aWitnessCountsTheDeedInFull() {
        IncidentRecord incident = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        incident.addWitnesses(List.of(TestFixtures.VILLAGER_2));
        OpinionResolver.Opinion opinion = resolve(ledger(incident), TestFixtures.VILLAGER_2, true, 0L);
        assertEquals(-8, opinion.score());
        assertEquals(OpinionBasis.WITNESSED, opinion.basis());
        assertEquals(1, opinion.knownIncidents());
    }

    /** Village business heard second-hand weighs less than having been there. */
    @Test
    void hearsayWeighsLessThanWitnessing() {
        IncidentRecord incident = TestFixtures.record(-8, IncidentVisibility.VILLAGE, 0L);
        OpinionResolver.Opinion opinion = resolve(ledger(incident), TestFixtures.VILLAGER_2, true, 0L);
        assertEquals(-4, opinion.score());
        assertEquals(OpinionBasis.HEARSAY, opinion.basis());
    }

    /** Being the villager it happened to outranks — and outweighs — having watched it. */
    @Test
    void beingASubjectBeatsBeingAWitness() {
        IncidentRecord incident = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        incident.addWitnesses(List.of(TestFixtures.VILLAGER_1));
        OpinionResolver.Opinion opinion = resolve(ledger(incident), TestFixtures.VILLAGER_1, true, 0L);
        assertEquals(-12, opinion.score());
        assertEquals(OpinionBasis.INVOLVED, opinion.basis());
    }

    /** §19.3: someone who does not live here hears nothing, and carries no baseline either. */
    @Test
    void aNonResidentWhoSawNothingKnowsNothing() {
        CommunityReputationRecord record = ledger(TestFixtures.record(-8, IncidentVisibility.VILLAGE, 0L));
        record.addBaseline(40, MIN_SCORE, MAX_SCORE);
        OpinionResolver.Opinion opinion = resolve(record, TestFixtures.VILLAGER_2, false, 0L);
        assertEquals(0, opinion.score());
        assertEquals(OpinionBasis.NONE, opinion.basis());
        assertEquals(0, opinion.knownIncidents());
    }

    /** A rumour is worth nothing until it arrives, and full hearsay weight the moment it does. */
    @Test
    void aRumourCountsOnlyOnceItHasArrived() {
        IncidentRecord incident = TestFixtures.record(-8, IncidentVisibility.WITNESSED, 0L);
        long delay = AwarenessResolver.rumorDelayTicks(incident.id(), TestFixtures.VILLAGER_2,
                TestFixtures.OVERWORLD_3, MIN_DELAY, MAX_DELAY);
        CommunityReputationRecord record = ledger(incident);
        assertEquals(OpinionBasis.NONE,
                resolve(record, TestFixtures.VILLAGER_2, true, delay - 1).basis());
        OpinionResolver.Opinion after = resolve(record, TestFixtures.VILLAGER_2, true, delay);
        assertEquals(OpinionBasis.HEARSAY, after.basis());
        assertEquals(-4, after.score());
    }

    /** An opinion sits on the same ladder as a score, so it obeys the same window. */
    @Test
    void theOpinionIsClampedToTheScoreWindow() {
        IncidentRecord first = TestFixtures.record(-500, IncidentVisibility.WITNESSED, 0L);
        first.addWitnesses(List.of(TestFixtures.VILLAGER_2));
        IncidentRecord second = TestFixtures.record(-500, IncidentVisibility.WITNESSED, 0L);
        second.addWitnesses(List.of(TestFixtures.VILLAGER_2));
        OpinionResolver.Opinion opinion = OpinionResolver.resolve(ledger(first, second),
                TestFixtures.VILLAGER_2, true, 0L, MIN_DELAY, MAX_DELAY, HEARSAY, INVOLVED, -100, 100);
        assertEquals(-100, opinion.score());
        assertEquals(2, opinion.knownIncidents());
    }

    /** Baseline is the village's general sense of someone: it reaches a resident as hearsay. */
    @Test
    void aBaselineAloneReadsAsHearsay() {
        CommunityReputationRecord record = ledger();
        record.addBaseline(40, MIN_SCORE, MAX_SCORE);
        OpinionResolver.Opinion opinion = resolve(record, TestFixtures.VILLAGER_2, true, 0L);
        assertEquals(20, opinion.score());
        assertEquals(OpinionBasis.HEARSAY, opinion.basis());
        assertEquals(0, opinion.knownIncidents(),
                "a baseline is not a deed; nothing in the ledger explains it");
    }

    @Test
    void anEmptyLedgerIsNotAnOpinion() {
        OpinionResolver.Opinion opinion = resolve(ledger(), TestFixtures.VILLAGER_2, true, 0L);
        assertEquals(OpinionResolver.Opinion.NOTHING, opinion);
        assertTrue(opinion.score() == 0);
    }
}
