package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.api.ReputationSnapshot;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.network.SnapshotSelection;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.ReputationSavedData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole standing pipeline, end to end, for the report "no matter what I do I have 25 more to
 * acquaintance and my rank is stranger".
 *
 * <p>Two opposite bugs produce that one sentence, and the point of this class is that each is now
 * pinned separately:
 *
 * <ol>
 *   <li><b>The stored value never moves.</b> {@link #anAwardMovesTheStoredValueByExactlyItsDelta} and
 *       friends walk the number from 0 to Acquaintance and back out of a save file.</li>
 *   <li><b>The stored value moves and the screen reads somewhere else.</b>
 *       {@link #theScreenNeverPrefersAVillageWithNoRecordOverOneWithAHistory} pins the read key
 *       against the write key, which is the drift that made a real record invisible.</li>
 * </ol>
 *
 * <p>Loader-independent: the transaction runs against {@link TestServiceContext}'s in-memory store,
 * and the selection decision is a pure function.
 */
class StandingPipelineTest {

    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;
    private static final CommunityKey ELSEWHERE = TestFixtures.OVERWORLD_7;

    /** The documented default ladder: Stranger at 0, Acquaintance at 25 (see reputation_tiers/default.json). */
    private static final int ACQUAINTANCE_THRESHOLD = 25;

    private final TestServiceContext ctx = new TestServiceContext();

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
        McaReputationConfig.TestOverrides.reset();
    }

    /** A plain, always-scoring deed: village-visible, no decay, so only the delta under test moves. */
    private void defineDeed(int delta) {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(delta, IncidentVisibility.VILLAGE, DecayPolicy.NONE)));
    }

    private ReputationResult award(CommunityKey community, int delta, String dedupeKey) {
        defineDeed(delta);
        return ReputationService.recordWith(ctx, new ReputationRequest(null, TestFixtures.PLAYER_A,
                community, TestFixtures.ASSAULT, TestFixtures.SOURCE, Optional.ofNullable(dedupeKey),
                OptionalInt.empty(), Optional.empty(), List.of(), Set.of(), Map.of(), 0L));
    }

    private CommunityReputationRecord record(CommunityKey community) {
        return ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().community(community).orElseThrow();
    }

    private ReputationSnapshot snapshot(CommunityKey community) {
        return ReputationService.snapshotWith(ctx, TestFixtures.PLAYER_A, community, 0L).orElseThrow();
    }

    // ------------------------------------------------------------------
    // 1. The stored value moves
    // ------------------------------------------------------------------

    @Test
    void anAwardMovesTheStoredValueByExactlyItsDelta() {
        assertTrue(ctx.data.player(TestFixtures.PLAYER_A).isEmpty(), "no record before the first deed");

        ReputationResult first = award(HOME, 10, "deed-1");
        assertTrue(first.applied());
        assertEquals(10, first.appliedDelta());
        assertEquals(10, record(HOME).score());

        ReputationResult second = award(HOME, 7, "deed-2");
        assertEquals(7, second.appliedDelta());
        assertEquals(17, record(HOME).score(), "deltas accumulate in the one stored value");
    }

    /** The exact figure in the report: at 0 the ladder says Stranger and 25 to go. */
    @Test
    void aFreshPlayerIsStrangerWithTwentyFiveToAcquaintance() {
        award(HOME, 0, "seed");
        ReputationSnapshot snapshot = snapshot(HOME);

        assertEquals(0, snapshot.score());
        assertEquals("stranger", snapshot.tierId());
        assertEquals(Optional.of("acquaintance"), snapshot.nextTier().map(ReputationTier::id));
        assertEquals(Optional.of(ACQUAINTANCE_THRESHOLD), snapshot.pointsToNextTier());
    }

    @Test
    void crossingTheDocumentedThresholdChangesTheTier() {
        award(HOME, ACQUAINTANCE_THRESHOLD - 1, "just-short");
        assertEquals("stranger", snapshot(HOME).tierId(), "one short of the threshold is still Stranger");
        assertEquals(Optional.of(1), snapshot(HOME).pointsToNextTier());

        award(HOME, 1, "the-last-point");
        ReputationSnapshot crossed = snapshot(HOME);
        assertEquals(ACQUAINTANCE_THRESHOLD, crossed.score());
        assertEquals("acquaintance", crossed.tierId(), "the threshold is inclusive");
        assertEquals(Optional.of("friend"), crossed.nextTier().map(ReputationTier::id));
    }

    /**
     * The tier and the "N more to X" figure must come from one number. Reading the tier from the
     * stored score while computing the remainder from anything else is how a display freezes at its
     * initial value while the underlying number moves.
     */
    @Test
    void theDistanceToTheNextTierDerivesFromTheSameScoreTheTierDoes() {
        for (int total = 0; total <= 80; total += 8) {
            TestServiceContext fresh = new TestServiceContext();
            defineDeed(total);
            ReputationService.recordWith(fresh, new ReputationRequest(null, TestFixtures.PLAYER_A, HOME,
                    TestFixtures.ASSAULT, TestFixtures.SOURCE, Optional.of("k" + total),
                    OptionalInt.empty(), Optional.empty(), List.of(), Set.of(), Map.of(), 0L));
            ReputationSnapshot snapshot =
                    ReputationService.snapshotWith(fresh, TestFixtures.PLAYER_A, HOME, 0L).orElseThrow();

            ReputationTierSet ladder = ReputationTiers.getDefault();
            assertEquals(ladder.tierFor(snapshot.score()).id(), snapshot.tierId());
            assertEquals(ladder.nextTier(snapshot.score()).map(next -> next.threshold() - snapshot.score()),
                    snapshot.pointsToNextTier(),
                    "remaining must be nextThreshold - the very score the tier was read from");
        }
    }

    @Test
    void standingSurvivesASaveAndLoadRoundTrip() {
        award(HOME, 30, "deed-1");
        award(HOME, 12, "deed-2");
        assertEquals(42, record(HOME).score());

        ReputationSavedData reloaded = ctx.data.roundTripForTest();

        assertEquals(42, reloaded.score(TestFixtures.PLAYER_A, HOME));
        CommunityReputationRecord loaded = reloaded.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow();
        assertEquals(2, loaded.incidentCount(), "the deeds that explain the score survive too");
        assertEquals("acquaintance", ReputationTiers.getDefault().tierFor(loaded.score()).id());
    }

    // ------------------------------------------------------------------
    // 2. The screen reads the same store the deeds are written to
    // ------------------------------------------------------------------

    /**
     * The store-drift guard. A deed is filed against the home village of the villager it was about;
     * the standing screen, asked nothing in particular, used to detail whichever village was nearest
     * the player's feet — and {@code buildSnapshot} synthesises a floor-tier detail for a community
     * with no record. A player who had earned 40 points in one village and walked to another was
     * therefore shown "Stranger, 25 more to Acquaintance" with a straight face.
     */
    @Test
    void theScreenNeverPrefersAVillageWithNoRecordOverOneWithAHistory() {
        assertEquals(Optional.of(HOME), SnapshotSelection.unprompted(
                Optional.of(ELSEWHERE), false, Optional.of(HOME)),
                "standing in a village with no record must not hide the one with a history");
    }

    @Test
    void theScreenPrefersWhereYouAreStandingWhenYouHaveAHistoryThere() {
        assertEquals(Optional.of(HOME), SnapshotSelection.unprompted(
                Optional.of(HOME), true, Optional.of(ELSEWHERE)));
    }

    /** With no record anywhere, "you are a stranger here" is the honest answer, not a mask. */
    @Test
    void aPlayerWithNoStandingAnywhereStillSeesTheVillageTheyAreStandingIn() {
        assertEquals(Optional.of(ELSEWHERE), SnapshotSelection.unprompted(
                Optional.of(ELSEWHERE), false, Optional.empty()));
    }

    @Test
    void nowhereToStandAndNothingKnownSelectsNothing() {
        assertEquals(Optional.empty(),
                SnapshotSelection.unprompted(Optional.empty(), false, Optional.empty()));
        assertEquals(Optional.of(HOME),
                SnapshotSelection.unprompted(Optional.empty(), false, Optional.of(HOME)));
    }

    /**
     * Ties the two halves together: after a real award the store knows the community, so the selection
     * that the screen makes is the one the deed was written to.
     */
    @Test
    void afterAnAwardTheReadKeyAndTheWriteKeyAgree() {
        award(HOME, 40, "deed-1");
        assertTrue(ctx.data.knows(TestFixtures.PLAYER_A, HOME));
        assertFalse(ctx.data.knows(TestFixtures.PLAYER_A, ELSEWHERE));

        Optional<CommunityKey> best = ReputationService
                .knownCommunitiesWith(ctx, TestFixtures.PLAYER_A, 0L).stream()
                .findFirst().map(ReputationSnapshot::community);
        Optional<CommunityKey> selected = SnapshotSelection.unprompted(
                Optional.of(ELSEWHERE), ctx.data.knows(TestFixtures.PLAYER_A, ELSEWHERE), best);

        assertEquals(Optional.of(HOME), selected);
        assertEquals(40, snapshot(selected.orElseThrow()).score());
    }
}
