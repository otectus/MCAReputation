package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.incident.ResolutionPolicy;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §36.1 group 10. */
class PruningTest {

    private static final int MIN = -1000;
    private static final int MAX = 1000;
    private static final ResourceLocation TYPE = TestFixtures.ASSAULT;

    private static IncidentRecord incident(int delta, IncidentSeverity severity, long created) {
        return IncidentRecord.create(UUID.randomUUID(), TYPE, TestFixtures.PLAYER_A,
                TestFixtures.OVERWORLD_3, created, TestFixtures.SOURCE, Optional.empty(), delta,
                IncidentVisibility.VILLAGE, severity,
                List.of(IncidentSubject.villager(TestFixtures.VILLAGER_1, "Anna", "victim")));
    }

    private static CommunityReputationRecord communityWith(IncidentRecord... incidents) {
        CommunityReputationRecord record = new CommunityReputationRecord(TestFixtures.OVERWORLD_3);
        for (IncidentRecord incident : incidents) {
            record.addIncident(incident);
        }
        record.recomputeScore(MIN, MAX);
        return record;
    }

    /**
     * The invariant that makes pruning acceptable at all: a player's score is bit-for-bit unchanged by
     * losing the explanation for it (§13.5).
     */
    @Test
    void scoreIsPreservedExactlyAcrossPruning() {
        CommunityReputationRecord record = communityWith(
                incident(10, IncidentSeverity.MINOR, 0),
                incident(-4, IncidentSeverity.MINOR, 1),
                incident(7, IncidentSeverity.MINOR, 2),
                incident(-3, IncidentSeverity.MINOR, 3));
        int before = record.score();
        assertEquals(10, before);

        List<IncidentRecord> removed = record.prune(2, 1000L, MIN, MAX);
        assertEquals(2, removed.size());
        assertEquals(2, record.incidentCount());
        assertEquals(before, record.score(), "the number the player sees must not move");
    }

    @Test
    void contributingIncidentsAreFoldedIntoBaselineBeforeRemoval() {
        CommunityReputationRecord record = communityWith(
                incident(10, IncidentSeverity.MINOR, 0),
                incident(5, IncidentSeverity.MINOR, 1));
        assertEquals(0, record.baseline());
        record.prune(1, 1000L, MIN, MAX);
        assertEquals(10, record.baseline(), "the pruned incident's weight moved to the baseline");
        assertEquals(15, record.score());
    }

    /** §13.5: zero-contribution history goes before anything that still carries weight. */
    @Test
    void zeroContributionEntriesArePrunedFirst() {
        IncidentRecord spent = incident(0, IncidentSeverity.TRIVIAL, 0);
        IncidentRecord valuable = incident(20, IncidentSeverity.MINOR, 1);
        CommunityReputationRecord record = communityWith(spent, valuable);

        List<IncidentRecord> removed = record.prune(1, 1000L, MIN, MAX);
        assertEquals(1, removed.size());
        assertEquals(spent.id(), removed.get(0).id());
        assertTrue(record.incident(valuable.id()).isPresent());
        assertEquals(0, record.baseline(), "nothing needed folding");
    }

    @Test
    void resolvedZeroEntriesGoBeforeUnresolvedOnes() {
        IncidentRecord resolved = incident(-8, IncidentSeverity.MINOR, 0);
        resolved.resolve(ResolutionPolicy.DEFAULT, null, IncidentStatus.FORGIVEN, 10L);
        IncidentRecord plainZero = incident(0, IncidentSeverity.MAJOR, 1);
        CommunityReputationRecord record = communityWith(resolved, plainZero);

        List<IncidentRecord> removed = record.prune(1, 1000L, MIN, MAX);
        assertEquals(1, removed.size());
        assertEquals(resolved.id(), removed.get(0).id(),
                "a settled matter is discarded before an open, notable one");
    }

    @Test
    void nonNotableZeroEntriesGoBeforeNotableOnes() {
        IncidentRecord trivial = incident(0, IncidentSeverity.TRIVIAL, 0);
        IncidentRecord severe = incident(0, IncidentSeverity.SEVERE, 1);
        CommunityReputationRecord record = communityWith(trivial, severe);

        List<IncidentRecord> removed = record.prune(1, 1000L, MIN, MAX);
        assertEquals(trivial.id(), removed.get(0).id());
        assertTrue(record.incident(severe.id()).isPresent());
    }

    /** §13.5: a pinned incident survives everything short of an administrator clearing the pin. */
    @Test
    void pinnedIncidentsAreNeverPruned() {
        IncidentRecord pinned = incident(0, IncidentSeverity.TRIVIAL, 0);
        pinned.setPinned(true);
        IncidentRecord ordinary = incident(0, IncidentSeverity.MINOR, 1);
        CommunityReputationRecord record = communityWith(pinned, ordinary);

        record.prune(1, 1000L, MIN, MAX);
        assertTrue(record.incident(pinned.id()).isPresent());
        assertTrue(record.incident(ordinary.id()).isEmpty());
    }

    @Test
    void anAllPinnedLedgerExceedsTheCapRatherThanLosingHistory() {
        IncidentRecord a = incident(0, IncidentSeverity.TRIVIAL, 0);
        IncidentRecord b = incident(0, IncidentSeverity.TRIVIAL, 1);
        a.setPinned(true);
        b.setPinned(true);
        CommunityReputationRecord record = communityWith(a, b);

        assertTrue(record.prune(1, 1000L, MIN, MAX).isEmpty());
        assertEquals(2, record.incidentCount(), "exceeding the cap beats destroying protected history");
    }

    @Test
    void oldestGoesFirstWithinAPass() {
        IncidentRecord oldest = incident(0, IncidentSeverity.MINOR, 0);
        IncidentRecord middle = incident(0, IncidentSeverity.MINOR, 1);
        IncidentRecord newest = incident(0, IncidentSeverity.MINOR, 2);
        CommunityReputationRecord record = communityWith(oldest, middle, newest);

        List<IncidentRecord> removed = record.prune(1, 1000L, MIN, MAX);
        assertEquals(2, removed.size());
        assertEquals(oldest.id(), removed.get(0).id());
        assertEquals(middle.id(), removed.get(1).id());
        assertTrue(record.incident(newest.id()).isPresent());
    }

    @Test
    void pruningIsANoOpBelowTheCap() {
        CommunityReputationRecord record = communityWith(
                incident(5, IncidentSeverity.MINOR, 0),
                incident(5, IncidentSeverity.MINOR, 1));
        assertTrue(record.prune(10, 1000L, MIN, MAX).isEmpty());
        assertEquals(2, record.incidentCount());
    }

    /**
     * The invariant must hold at the clamp too. Folding one pruned incident at a time with a
     * window-clamped baseline saturates the baseline and silently discards weight, so the surviving
     * negative incident would then move the visible score — the exact bug this guards against.
     */
    @Test
    void pruningNearTheClampStillPreservesTheScoreExactly() {
        IncidentRecord[] positives = new IncidentRecord[14];
        for (int i = 0; i < positives.length; i++) {
            positives[i] = incident(100, IncidentSeverity.MINOR, i);
        }
        IncidentRecord surviving = incident(-300, IncidentSeverity.MAJOR, 99);
        CommunityReputationRecord record = new CommunityReputationRecord(TestFixtures.OVERWORLD_3);
        for (IncidentRecord positive : positives) {
            record.addIncident(positive);
        }
        record.addIncident(surviving);
        record.recomputeScore(MIN, MAX);
        assertEquals(MAX, record.score(), "1400 - 300 saturates the +1000 ceiling");

        record.prune(2, 1000L, MIN, MAX);
        assertEquals(2, record.incidentCount());
        assertTrue(record.incident(surviving.id()).isPresent());
        assertEquals(MAX, record.score(),
                "the visible score is bit-for-bit unchanged even though the ledger folded past the clamp");
        assertTrue(record.baseline() > MAX,
                "the baseline holds the fold overflow; only the visible score is window-clamped");
    }

    /** The wide baseline must survive a save/load cycle, or the reload re-clamps and moves the score. */
    @Test
    void aBaselineHoldingFoldOverflowSurvivesTheRoundTrip() {
        CommunityReputationRecord record = new CommunityReputationRecord(TestFixtures.OVERWORLD_3);
        for (int i = 0; i < 14; i++) {
            record.addIncident(incident(100, IncidentSeverity.MINOR, i));
        }
        IncidentRecord surviving = incident(-300, IncidentSeverity.MAJOR, 99);
        record.addIncident(surviving);
        record.recomputeScore(MIN, MAX);
        record.prune(2, 1000L, MIN, MAX);
        int scoreBefore = record.score();
        int baselineBefore = record.baseline();

        CommunityReputationRecord loaded =
                CommunityReputationRecord.load(record.save(), MIN, MAX).orElseThrow();
        assertEquals(baselineBefore, loaded.baseline());
        assertEquals(scoreBefore, loaded.score());
    }

    /**
     * One community whose ledger is entirely pinned must not end the whole-player sweep: the
     * next-fullest community still has prunable history, and stopping early would leave the player
     * permanently over the cap because of one stubborn village.
     */
    @Test
    void anUnprunableCommunityDoesNotAbortTheWholePlayerCapSweep() {
        PlayerReputationRecord player = new PlayerReputationRecord(TestFixtures.PLAYER_A);
        CommunityReputationRecord stubborn = player.getOrCreate(TestFixtures.OVERWORLD_3);
        for (int i = 0; i < 4; i++) {
            IncidentRecord pinned = incident(0, IncidentSeverity.TRIVIAL, i);
            pinned.setPinned(true);
            stubborn.addIncident(pinned);
        }
        CommunityReputationRecord prunable = player.getOrCreate(TestFixtures.NETHER_3);
        for (int i = 0; i < 3; i++) {
            prunable.addIncident(IncidentRecord.create(UUID.randomUUID(), TYPE, TestFixtures.PLAYER_A,
                    TestFixtures.NETHER_3, i, TestFixtures.SOURCE, Optional.empty(), 0,
                    IncidentVisibility.VILLAGE, IncidentSeverity.TRIVIAL, List.of()));
        }
        stubborn.recomputeScore(MIN, MAX);
        prunable.recomputeScore(MIN, MAX);

        int pruned = player.enforcePlayerIncidentCap(5, 1000L, MIN, MAX);
        assertEquals(2, pruned, "the sweep skipped the all-pinned community and kept going");
        assertEquals(4, stubborn.incidentCount(), "pinned history is never destroyed");
        assertEquals(1, prunable.incidentCount());
        assertEquals(5, player.totalIncidentCount());
    }

    @Test
    void theCapSweepReportsZeroWhenNothingChanged() {
        PlayerReputationRecord player = new PlayerReputationRecord(TestFixtures.PLAYER_A);
        player.getOrCreate(TestFixtures.OVERWORLD_3)
                .addIncident(incident(5, IncidentSeverity.MINOR, 0));
        assertEquals(0, player.enforcePlayerIncidentCap(10, 1000L, MIN, MAX));
    }

    // ------------------------------------------------------------------
    // Recomputation invariant (§13.4)
    // ------------------------------------------------------------------

    @Test
    void aCorruptedCachedScoreIsRepairedOnLoadNotTrusted() {
        CommunityReputationRecord record = communityWith(
                incident(10, IncidentSeverity.MINOR, 0),
                incident(-4, IncidentSeverity.MINOR, 1));
        assertEquals(6, record.score());

        var tag = record.save();
        tag.putInt("score", 999); // simulate a damaged or hand-edited save

        CommunityReputationRecord loaded = CommunityReputationRecord.load(tag, MIN, MAX).orElseThrow();
        assertEquals(6, loaded.score(), "the cached number must never become authoritative");
    }

    @Test
    void scoreAlwaysEqualsBaselinePlusContributions() {
        CommunityReputationRecord record = communityWith(
                incident(10, IncidentSeverity.MINOR, 0),
                incident(-4, IncidentSeverity.MINOR, 1));
        record.addBaseline(25, MIN, MAX);

        int expected = record.baseline()
                + record.incidents().stream().mapToInt(IncidentRecord::currentContribution).sum();
        assertEquals(expected, record.score());
    }
}
