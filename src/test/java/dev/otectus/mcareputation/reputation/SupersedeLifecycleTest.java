package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.ReputationMirror;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.api.StandingChange;
import dev.otectus.mcareputation.api.SupersedeSpec;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.incident.ResolutionPolicy;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T19 and T12/T13/T14: the terminal superseded state, and the one seam that produces it (§5 F05/F08).
 *
 * <p>Lives beside the service rather than in {@code incident} because {@code recordSupersedingWith}
 * and {@link TestServiceContext} are the package-private seam this has to drive.
 */
class SupersedeLifecycleTest {

    private static final long DAY = DecayPolicy.TICKS_PER_DAY;
    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;
    private static final CommunityKey AWAY = TestFixtures.OVERWORLD_7;
    private static final ResourceLocation KILL =
            new ResourceLocation("mcareputation", "villager_killed");

    private final TestServiceContext ctx = new TestServiceContext();
    private final List<StandingChange> mirrored = new ArrayList<>();
    private final RecordingMirror mirror = new RecordingMirror();

    @BeforeEach
    void setUp() {
        ReputationService.registerMirror(mirror);
        IncidentRegistry.replaceAll(Map.of(
                TestFixtures.ASSAULT,
                TestFixtures.definition(-8, IncidentVisibility.WITNESSED, DecayPolicy.NONE),
                KILL,
                TestFixtures.definition(-40, IncidentVisibility.WITNESSED, DecayPolicy.NONE)));
    }

    @AfterEach
    void tearDown() {
        ReputationService.unregisterMirror(mirror);
        IncidentRegistry.replaceAll(Map.of());
        McaReputationConfig.TestOverrides.reset();
    }

    private final class RecordingMirror implements ReputationMirror {

        @Override
        public void mirrorStanding(StandingChange change) {
            mirrored.add(change);
        }

        @Override
        public void mirrorScore(UUID player, CommunityKey community, int score,
                                ResourceLocation ladder, String highWaterTierId) {
            throw new AssertionError("the service must call mirrorStanding, not mirrorScore");
        }

        @Override
        public void mirrorVillageTitle(UUID player, CommunityKey community, ResourceLocation title) {
        }

        @Override
        public void mirrorGlobalTitle(UUID player, ResourceLocation title) {
        }

        @Override
        public String mirrorName() {
            return "supersede-test";
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ReputationRequest request(ResourceLocation type, CommunityKey community, UUID victim,
                                      Set<UUID> witnesses, long gameTime) {
        return new ReputationRequest(null, TestFixtures.PLAYER_A, community, type, TestFixtures.SOURCE,
                Optional.empty(), OptionalInt.empty(), Optional.empty(),
                List.of(IncidentSubject.villager(victim, "Anna", "victim")), witnesses, Map.of(),
                gameTime);
    }

    /** The assault everything else supersedes: witnessed, worth -8, at time zero. */
    private IncidentRecord seedAssault(CommunityKey community, UUID victim) {
        ctx.gameTime = 0L;
        ReputationResult result = ReputationService.recordWith(ctx,
                request(TestFixtures.ASSAULT, community, victim, Set.of(TestFixtures.VILLAGER_2), 0L));
        assertTrue(result.applied());
        mirrored.clear();
        ctx.posted.clear();
        return community(community).incident(result.incidentId().orElseThrow()).orElseThrow();
    }

    private CommunityReputationRecord community(CommunityKey community) {
        return ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().community(community).orElseThrow();
    }

    // ------------------------------------------------------------------
    // T19 — the terminal state itself
    // ------------------------------------------------------------------

    @Test
    void aFoldedRecordStaysAtZeroThroughResolveReconcileAndReload() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.VILLAGE, 0L);
        UUID successor = UUID.randomUUID();
        assertEquals(8, record.foldInto(successor, 10L), "the fold hands back the whole contribution");
        assertEquals(0, record.currentContribution());
        assertTrue(record.isSuperseded());
        assertFalse(record.contributes());

        assertTrue(record.resolve(ResolutionPolicy.DEFAULT, DecayPolicy.NONE, IncidentStatus.APOLOGIZED,
                20L).isEmpty(), "a superseded record is not an amends candidate");
        assertEquals(0, record.currentContribution());

        assertEquals(0, record.reconcile(DecayPolicy.linearToZero(0, 2), 30 * DAY),
                "there is nothing left to age");
        assertEquals(0, record.currentContribution());
        assertTrue(record.isSuperseded());

        IncidentRecord loaded = IncidentRecord.load(record.save()).orElseThrow();
        assertTrue(loaded.isSuperseded(), "the flag survives the round trip");
        assertEquals(Optional.of(successor), loaded.supersededBy());
        assertEquals(0, loaded.currentContribution());
        assertEquals(0, loaded.reconcile(DecayPolicy.linearToZero(0, 2), 60 * DAY));
        assertEquals(0, loaded.currentContribution());
        assertFalse(loaded.contributes());
    }

    /**
     * The record layer is deliberately literal: {@code IncidentRecord.load} reads what is on disk and
     * a context-only entry is not the flag, so it loads as <em>not</em> superseded and keeps its stored
     * weight. WP5 moved the decision one level up, where it belongs — the v1 to v2 saved-data migration
     * is what marks those records terminal, once, at upgrade. See {@code SavedDataMigrationTest}.
     */
    @Test
    void aLegacyContextOnlyEntryDoesNotLoadAsSuperseded() {
        IncidentRecord legacy = TestFixtures.record(-8, IncidentVisibility.VILLAGE, 0L);
        legacy.putContext(BuiltinIncidents.CONTEXT_SUPERSEDED_BY, UUID.randomUUID().toString());
        CompoundTag tag = legacy.save();
        assertFalse(tag.contains("superseded"));

        IncidentRecord loaded = IncidentRecord.load(tag).orElseThrow();
        assertFalse(loaded.isSuperseded());
        assertTrue(loaded.contributes(), "its stored weight is untouched until a migration says otherwise");
    }

    // ------------------------------------------------------------------
    // T12/T13/T14 — the seam
    // ------------------------------------------------------------------

    @Test
    void aKillInsideTheWindowFoldsTheAssaultAndPublishesOneChange() {
        IncidentRecord assault = seedAssault(HOME, TestFixtures.VILLAGER_1);
        ctx.gameTime = 100L;

        ReputationResult result = ReputationService.recordSupersedingWith(ctx,
                request(KILL, HOME, TestFixtures.VILLAGER_1, Set.of(TestFixtures.VILLAGER_2), 100L),
                SupersedeSpec.of(assault.id(), 200L, true));

        assertTrue(result.applied());
        assertEquals(-40, community(HOME).score(), "the beating and the death are one encounter");
        assertEquals(-8, result.oldScore());
        assertEquals(-40, result.newScore());
        assertTrue(assault.isSuperseded());
        assertFalse(assault.contributes());
        assertEquals(result.incidentId(), assault.supersededBy(),
                "the precursor names the incident that absorbed it");

        assertEquals(1, mirrored.size(), "one encounter, one publication");
        assertEquals(ChangeCause.SUPERSEDE, mirrored.get(0).cause());
        assertEquals(-8, mirrored.get(0).oldScore());
        assertEquals(-40, mirrored.get(0).newScore());
    }

    @Test
    void anUnseenKillRestoresThePrecursorRatherThanRefundingIt() {
        IncidentRecord assault = seedAssault(HOME, TestFixtures.VILLAGER_1);
        ctx.gameTime = 100L;

        ReputationResult result = ReputationService.recordSupersedingWith(ctx,
                request(KILL, HOME, TestFixtures.VILLAGER_1, Set.of(), 100L),
                SupersedeSpec.of(assault.id(), 200L, true));

        assertFalse(result.applied());
        assertEquals(ReputationResult.Reason.UNWITNESSED, result.reason());
        assertEquals(-8, community(HOME).score(), "the village saw the beating; nobody refunds it");
        assertFalse(assault.isSuperseded());
        assertTrue(assault.contributes());
        assertTrue(assault.supersededBy().isEmpty());
        assertTrue(assault.context(BuiltinIncidents.CONTEXT_SUPERSEDED_BY).isEmpty());
    }

    @Test
    void aDifferentVictimLeavesThePrecursorAlone() {
        IncidentRecord assault = seedAssault(HOME, TestFixtures.VILLAGER_1);
        ctx.gameTime = 100L;

        ReputationResult result = ReputationService.recordSupersedingWith(ctx,
                request(KILL, HOME, TestFixtures.VILLAGER_2, Set.of(TestFixtures.VILLAGER_2), 100L),
                SupersedeSpec.of(assault.id(), 200L, true));

        assertTrue(result.applied(), "a real deed is never dropped because the terms did not hold");
        assertEquals(-48, community(HOME).score(), "two victims, two deeds");
        assertFalse(assault.isSuperseded());
        assertTrue(assault.contributes());
    }

    @Test
    void aPrecursorInAnotherCommunityIsNotFound() {
        IncidentRecord assault = seedAssault(HOME, TestFixtures.VILLAGER_1);
        ctx.gameTime = 100L;

        ReputationResult result = ReputationService.recordSupersedingWith(ctx,
                request(KILL, AWAY, TestFixtures.VILLAGER_1, Set.of(TestFixtures.VILLAGER_2), 100L),
                SupersedeSpec.of(assault.id(), 200L, true));

        assertTrue(result.applied());
        assertEquals(-8, community(HOME).score(), "the other village's ledger is untouched");
        assertEquals(-40, community(AWAY).score());
        assertFalse(assault.isSuperseded());
        assertTrue(assault.contributes());
    }

    @Test
    void aPrecursorOutsideTheWindowIsNotFolded() {
        IncidentRecord assault = seedAssault(HOME, TestFixtures.VILLAGER_1);
        ctx.gameTime = 5000L;

        ReputationResult result = ReputationService.recordSupersedingWith(ctx,
                request(KILL, HOME, TestFixtures.VILLAGER_1, Set.of(TestFixtures.VILLAGER_2), 5000L),
                SupersedeSpec.of(assault.id(), 200L, true));

        assertTrue(result.applied());
        assertEquals(-48, community(HOME).score(), "a beating long past is its own deed");
        assertFalse(assault.isSuperseded());
        assertTrue(assault.contributes());
    }

    @Test
    void anAlreadySupersededPrecursorIsNotFoldedTwice() {
        IncidentRecord assault = seedAssault(HOME, TestFixtures.VILLAGER_1);
        ctx.gameTime = 100L;
        assertTrue(ReputationService.recordSupersedingWith(ctx,
                request(KILL, HOME, TestFixtures.VILLAGER_1, Set.of(TestFixtures.VILLAGER_2), 100L),
                SupersedeSpec.of(assault.id(), 200L, true)).applied());
        UUID firstSuccessor = assault.supersededBy().orElseThrow();

        ctx.gameTime = 200L;
        ReputationResult second = ReputationService.recordSupersedingWith(ctx,
                request(KILL, HOME, TestFixtures.VILLAGER_1, Set.of(TestFixtures.VILLAGER_2), 200L),
                SupersedeSpec.of(assault.id(), 200L, true));

        assertTrue(second.applied());
        assertEquals(-80, community(HOME).score(), "the second killing is charged in full");
        assertEquals(Optional.of(firstSuccessor), assault.supersededBy(),
                "the precursor still names the incident that actually absorbed it");
    }

    /** A superseded record is never offered as history, a selector target, or an amends candidate. */
    @Test
    void aSupersededRecordDropsOutOfTheServerSideCandidateList() {
        IncidentRecord assault = seedAssault(HOME, TestFixtures.VILLAGER_1);
        ctx.gameTime = 100L;
        assertTrue(ReputationService.recordSupersedingWith(ctx,
                request(KILL, HOME, TestFixtures.VILLAGER_1, Set.of(TestFixtures.VILLAGER_2), 100L),
                SupersedeSpec.of(assault.id(), 200L, true)).applied());

        assertEquals(1, ReputationService.recentIncidentsWith(ctx, TestFixtures.PLAYER_A, HOME, 10,
                100L).size(), "only the killing is a candidate");
        assertEquals(1, ReputationService.snapshotWith(ctx, TestFixtures.PLAYER_A, HOME, 100L)
                .orElseThrow().incidents().size());
        assertEquals(2, community(HOME).incidentCount(), "but the ledger still explains itself");
    }
}
