package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.incident.ResolutionPolicy;
import dev.otectus.mcareputation.reputation.OpinionResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T21: compaction is supposed to cost the <em>explanation</em> for standing and nothing else.
 *
 * <p>Each case builds the same ledger twice, prunes one copy, and then asks the two the same questions
 * about the future: what the score will be after several more days of decay, what one villager thinks,
 * and whether the open cases can still be settled. Equivalence there is what makes a prune safe, and it
 * is precisely what folding live weight into a non-decaying baseline used to break.
 */
class CompactionTest {

    private static final int MIN = -1000;
    private static final int MAX = 1000;
    private static final long DAY = DecayPolicy.TICKS_PER_DAY;

    @BeforeEach
    void setUp() {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(-8, IncidentVisibility.VILLAGE,
                        DecayPolicy.linearToZero(0, 2))));
    }

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
    }

    /** Three open cases the village still holds against the player, plus five spent records. */
    private static CommunityReputationRecord ledger() {
        CommunityReputationRecord record = new CommunityReputationRecord(TestFixtures.OVERWORLD_3);
        for (int i = 0; i < 5; i++) {
            // Spent history: expired, weightless, nobody's business. The only kind that is evictable.
            IncidentRecord spent = IncidentRecord.create(
                    UUID.nameUUIDFromBytes(("spent" + i).getBytes()), TestFixtures.ASSAULT,
                    TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, i, TestFixtures.SOURCE,
                    Optional.empty(), 0, IncidentVisibility.VILLAGE, IncidentSeverity.TRIVIAL,
                    List.of());
            spent.markExpired(i);
            record.addIncident(spent);
        }
        for (int i = 0; i < 3; i++) {
            IncidentRecord open = IncidentRecord.create(
                    UUID.nameUUIDFromBytes(("open" + i).getBytes()), TestFixtures.ASSAULT,
                    TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 100 + i, TestFixtures.SOURCE,
                    Optional.empty(), -30, IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE,
                    List.of(IncidentSubject.villager(TestFixtures.VILLAGER_1, "Anna", "victim")));
            open.addWitnesses(List.of(TestFixtures.VILLAGER_1));
            record.addIncident(open);
        }
        record.recomputeScore(MIN, MAX);
        return record;
    }

    private static List<Integer> trajectory(CommunityReputationRecord record) {
        List<Integer> scores = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            record.reconcile(1000L + day * DAY, MIN, MAX);
            scores.add(record.score());
        }
        return scores;
    }

    private static int opinion(CommunityReputationRecord record) {
        return OpinionResolver.resolve(record, TestFixtures.VILLAGER_1, true, 1000L, 0, 0, 50, 150,
                MIN, MAX).score();
    }

    @Test
    void theFutureDecayTrajectoryIsUnchanged() {
        CommunityReputationRecord control = ledger();
        CommunityReputationRecord compacted = ledger();

        List<IncidentRecord> removed = compacted.prune(3, 1000L, MIN, MAX);
        assertEquals(5, removed.size(), "the spent history is what compaction is for");
        assertEquals(control.score(), compacted.score(), "and today's number does not move");

        assertEquals(trajectory(control), trajectory(compacted),
                "the score has to decay the same way for the next six days");
    }

    @Test
    void perVillagerOpinionIsUnchanged() {
        CommunityReputationRecord control = ledger();
        CommunityReputationRecord compacted = ledger();
        compacted.prune(3, 1000L, MIN, MAX);

        assertEquals(opinion(control), opinion(compacted));
    }

    @Test
    void everyRetainedOpenCaseIsStillResolvable() {
        CommunityReputationRecord control = ledger();
        CommunityReputationRecord compacted = ledger();
        compacted.prune(3, 1000L, MIN, MAX);
        assertEquals(3, compacted.incidentCount());

        for (IncidentRecord retained : compacted.incidents()) {
            assertEquals(IncidentStatus.ACTIVE, retained.status(), "only open cases survived");
            IncidentRecord twin = control.incident(retained.id()).orElseThrow();
            Optional<Integer> before = twin.resolve(ResolutionPolicy.DEFAULT,
                    DecayPolicy.linearToZero(0, 2), IncidentStatus.ATONED, 2000L);
            Optional<Integer> after = retained.resolve(ResolutionPolicy.DEFAULT,
                    DecayPolicy.linearToZero(0, 2), IncidentStatus.ATONED, 2000L);
            assertTrue(after.isPresent(), "a pending case must remain resolvable after compaction");
            assertEquals(before, after);
        }
        assertEquals(control.score(), compacted.score(),
                "and settling them lands on the same number either way");
    }

    /** The other half of the guarantee: compaction never reaches anything that still counts. */
    @Test
    void compactionStopsAtTheFirstThingThatStillCounts() {
        CommunityReputationRecord record = ledger();

        List<IncidentRecord> removed = record.prune(1, 1000L, MIN, MAX);

        assertEquals(5, removed.size());
        assertEquals(3, record.incidentCount(), "the cap is exceeded rather than live history lost");
        assertFalse(removed.stream().anyMatch(IncidentRecord::contributes));
    }
}
