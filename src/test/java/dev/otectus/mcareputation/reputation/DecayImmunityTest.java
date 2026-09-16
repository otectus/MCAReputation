package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T16/T17: decay immunity holds on every path, and lifting it costs no catch-up (§5 F07, DD2).
 *
 * <p>Each test drives one of the paths that used to call {@code CommunityReputationRecord.reconcile}
 * directly and therefore walked round the immunity check. The protected village is asserted against a
 * control village that is not protected and does age on the very same call, so a test that stopped
 * exercising decay at all would fail rather than pass vacuously.
 */
class DecayImmunityTest {

    private static final long DAY = DecayPolicy.TICKS_PER_DAY;

    /** Protected. */
    private static final CommunityKey SAFE = TestFixtures.OVERWORLD_3;

    /** The control: same ledger, same clock, no protection. */
    private static final CommunityKey OPEN = TestFixtures.NETHER_3;

    private final TestServiceContext ctx = new TestServiceContext();

    @BeforeEach
    void setUp() {
        // One point a day, no delay: five days is five points off a contribution's magnitude.
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(-5, IncidentVisibility.VILLAGE, DecayPolicy.linearToZero(0, 1))));
        ctx.data.setDecayImmune(SAFE, true);
    }

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
        McaReputationConfig.TestOverrides.reset();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A -20 deed in both villages at time zero, plus a -10 one to resolve without touching it. */
    private void seed() {
        for (CommunityKey community : List.of(SAFE, OPEN)) {
            seedIncident(community, UUID.randomUUID(), -20);
            seedIncident(community, UUID.randomUUID(), -10);
        }
    }

    private void seedIncident(CommunityKey community, UUID id, int delta) {
        CommunityReputationRecord record = ctx.data.getOrCreatePlayer(TestFixtures.PLAYER_A)
                .getOrCreate(community);
        record.addIncident(IncidentRecord.create(id, TestFixtures.ASSAULT, TestFixtures.PLAYER_A,
                community, 0L, TestFixtures.SOURCE, Optional.empty(), delta,
                IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE, List.of()));
        record.recomputeScore(McaReputationConfig.minimumScore(), McaReputationConfig.maximumScore());
    }

    private CommunityReputationRecord record(CommunityKey community) {
        return ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().community(community).orElseThrow();
    }

    /** The oldest incident's current contribution: the number decay moves and a freeze must not. */
    private int oldestContribution(CommunityKey community) {
        return record(community).incidents().iterator().next().currentContribution();
    }

    private ReputationRequest request(CommunityKey community, String dedupeKey, long gameTime) {
        return new ReputationRequest(null, TestFixtures.PLAYER_A, community, TestFixtures.ASSAULT,
                TestFixtures.SOURCE, Optional.ofNullable(dedupeKey), OptionalInt.empty(),
                Optional.empty(), List.of(), Set.of(), Map.of(), gameTime);
    }

    /** Both villages five days on: the protected one is exactly where it was, the control is not. */
    private void assertFrozenAndControlAged() {
        assertEquals(-20, oldestContribution(SAFE), "a protected village forgets nothing on its own");
        assertEquals(-15, oldestContribution(OPEN), "the control village ages five days' worth");
        assertEquals(-30, record(SAFE).score(), "and its score is untouched");
        assertEquals(-20, record(OPEN).score());
    }

    // ------------------------------------------------------------------
    // T16 — the former bypass paths
    // ------------------------------------------------------------------

    @Test
    void recordingAnotherDeedDoesNotAgeAProtectedVillage() {
        seed();

        ctx.gameTime = 5 * DAY;
        assertTrue(ReputationService.recordWith(ctx, request(SAFE, null, 5 * DAY)).applied());
        assertTrue(ReputationService.recordWith(ctx, request(OPEN, null, 5 * DAY)).applied());

        assertEquals(-20, oldestContribution(SAFE));
        assertEquals(-15, oldestContribution(OPEN));
        assertEquals(-35, record(SAFE).score(), "-20, -10 and the new -5, none of them aged");
        assertEquals(-25, record(OPEN).score());
    }

    @Test
    void aRefusedDuplicateDoesNotAgeAProtectedVillage() {
        for (CommunityKey community : List.of(SAFE, OPEN)) {
            assertTrue(ReputationService.recordWith(ctx, request(community, "once", 0L)).applied());
        }

        ctx.gameTime = 5 * DAY;
        ReputationResult safeReplay = ReputationService.recordWith(ctx, request(SAFE, "once", 5 * DAY));
        ReputationResult openReplay = ReputationService.recordWith(ctx, request(OPEN, "once", 5 * DAY));

        assertEquals(ReputationResult.Reason.DUPLICATE, safeReplay.reason());
        assertEquals(ReputationResult.Reason.DUPLICATE, openReplay.reason());
        assertEquals(-5, oldestContribution(SAFE), "replaying a deed is not a reason to age a village");
        assertEquals(0, oldestContribution(OPEN), "the control's -5 is five days gone");
        assertEquals(-5, safeReplay.newScore(), "and the refusal reports the unaged score");
        assertEquals(0, openReplay.newScore());
    }

    @Test
    void resolvingDoesNotAgeAProtectedVillageFirst() {
        seed();
        UUID safeTarget = lastIncidentId(SAFE);
        UUID openTarget = lastIncidentId(OPEN);

        assertTrue(ReputationService.resolveWith(ctx, TestFixtures.PLAYER_A, SAFE, safeTarget,
                IncidentStatus.FORGIVEN, TestFixtures.SOURCE, 5 * DAY).applied());
        assertTrue(ReputationService.resolveWith(ctx, TestFixtures.PLAYER_A, OPEN, openTarget,
                IncidentStatus.FORGIVEN, TestFixtures.SOURCE, 5 * DAY).applied());

        assertEquals(-20, oldestContribution(SAFE), "the incident nobody resolved is untouched");
        assertEquals(-15, oldestContribution(OPEN));
        assertEquals(-20, record(SAFE).score(), "forgiving the -10 leaves the -20 exactly as it was");
        assertEquals(-15, record(OPEN).score());
    }

    @Test
    void readingASnapshotDoesNotAgeAProtectedVillage() {
        seed();

        assertTrue(ReputationService.snapshotWith(ctx, TestFixtures.PLAYER_A, SAFE, 5 * DAY).isPresent());
        assertTrue(ReputationService.snapshotWith(ctx, TestFixtures.PLAYER_A, OPEN, 5 * DAY).isPresent());

        assertFrozenAndControlAged();
    }

    @Test
    void listingKnownCommunitiesDoesNotAgeAProtectedVillage() {
        seed();

        assertEquals(2, ReputationService.knownCommunitiesWith(ctx, TestFixtures.PLAYER_A, 5 * DAY).size());

        assertFrozenAndControlAged();
    }

    @Test
    void readingRecentHistoryDoesNotAgeAProtectedVillage() {
        seed();

        assertEquals(2, ReputationService.recentIncidentsWith(ctx, TestFixtures.PLAYER_A, SAFE, 10,
                5 * DAY).size());
        assertEquals(2, ReputationService.recentIncidentsWith(ctx, TestFixtures.PLAYER_A, OPEN, 10,
                5 * DAY).size());

        assertFrozenAndControlAged();
    }

    /** The opinion fold is pure: it reads the ledger and writes nothing, protected or not. */
    @Test
    void derivingAnOpinionDoesNotAgeEitherVillage() {
        seed();

        for (CommunityKey community : List.of(SAFE, OPEN)) {
            OpinionResolver.resolve(record(community), TestFixtures.VILLAGER_1, true, 5 * DAY,
                    6000, 48000, 50, 150, McaReputationConfig.minimumScore(),
                    McaReputationConfig.maximumScore());
        }

        assertEquals(-20, oldestContribution(SAFE));
        assertEquals(-20, oldestContribution(OPEN), "a pure read ages nothing, anywhere");
    }

    @Test
    void anAdministrativeAdjustmentDoesNotAgeAProtectedVillage() {
        seed();

        ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, SAFE, 5, false,
                TestFixtures.SOURCE, 5 * DAY);
        ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, OPEN, 5, false,
                TestFixtures.SOURCE, 5 * DAY);

        assertEquals(-20, oldestContribution(SAFE));
        assertEquals(-15, oldestContribution(OPEN));
        assertEquals(-25, record(SAFE).score());
        assertEquals(-15, record(OPEN).score());
    }

    @Test
    void reconcilePlayerFreezesAProtectedVillageRatherThanSkippingIt() {
        seed();

        assertTrue(ctx.data.reconcilePlayer(TestFixtures.PLAYER_A, 5 * DAY));

        assertFrozenAndControlAged();
        assertEquals(5 * DAY, record(SAFE).lastReconciledGameTime(),
                "the clock moved even though the ledger did not — that is what stops a catch-up burst");
    }

    // ------------------------------------------------------------------
    // T17 — no catch-up when protection is lifted
    // ------------------------------------------------------------------

    @Test
    void liftingImmunityDoesNotPayBackThePausedInterval() {
        seed();
        ctx.data.reconcilePlayer(TestFixtures.PLAYER_A, 5 * DAY);
        assertEquals(-20, oldestContribution(SAFE));

        ctx.data.setDecayImmune(SAFE, false);
        ctx.data.reconcilePlayer(TestFixtures.PLAYER_A, 5 * DAY);

        assertEquals(-20, oldestContribution(SAFE), "five protected days are not repaid at once");

        ctx.data.reconcilePlayer(TestFixtures.PLAYER_A, 6 * DAY);
        assertEquals(-19, oldestContribution(SAFE), "and ageing resumes from the moment it was lifted");
    }

    @Test
    void reEnablingGlobalDecayDoesNotPayBackThePausedInterval() {
        ctx.data.setDecayImmune(SAFE, false);
        seed();
        ctx.policy(ReputationPolicy.defaults().withScoreDecayEnabled(false));

        ReputationService.reconcileWith(ctx, TestFixtures.PLAYER_A, 5 * DAY);
        assertEquals(-20, oldestContribution(SAFE), "decay off means the ledger stands still");

        ctx.policy(ReputationPolicy.defaults());
        ReputationService.reconcileWith(ctx, TestFixtures.PLAYER_A, 5 * DAY);
        assertEquals(-20, oldestContribution(SAFE), "switching it back on is not a bill");

        ReputationService.reconcileWith(ctx, TestFixtures.PLAYER_A, 7 * DAY);
        assertEquals(-18, oldestContribution(SAFE), "two days have passed since it came back on");
    }

    /** The most recently seeded incident: insertion order, since the pair share an occurrence time. */
    private UUID lastIncidentId(CommunityKey community) {
        UUID last = null;
        for (IncidentRecord incident : record(community).incidents()) {
            last = incident.id();
        }
        return last;
    }
}
