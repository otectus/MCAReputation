package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ImportResult;
import dev.otectus.mcareputation.api.LegacyImportRequest;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.nbt.CompoundTag;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two promises the transaction used to make only partially.
 *
 * <h2>One snapshot of the rules (I09)</h2>
 *
 * <p>{@code commit()} read the score bounds and both incident caps from the <em>live</em> config,
 * mid-transaction, from a spec a config reload can replace between two statements. A deed could then
 * be admitted against one cap and clamped against another minimum, and nothing could show that the
 * refusal, the eviction pass and the whole-player sweep had been decided by the same rules. All four
 * now come from the operation's own policy snapshot, which is what these tests pin: an injected policy
 * that differs from the config has to be the one that decides.
 *
 * <h2>A read-only store refuses (I14)</h2>
 *
 * <p>A store latched read-only by a future save format used to accept an administrative standing
 * change, a legacy import and an immunity toggle, apply them in memory, report success, and lose all
 * three at restart — with an AUDIT line claiming otherwise.
 */
class PolicySnapshotAdmissionTest {

    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;
    private static final CommunityKey AWAY = TestFixtures.NETHER_3;

    private final TestDeliverySeam seam = new TestDeliverySeam();

    @BeforeEach
    void setUp() {
        define(-8, DecayPolicy.NONE);
    }

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
        McaReputationConfig.TestOverrides.reset();
    }

    private static void define(int delta, DecayPolicy decay) {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(delta, IncidentVisibility.VILLAGE, decay)));
    }

    private static ReputationRequest request(CommunityKey community, long gameTime) {
        return new ReputationRequest(null, TestFixtures.PLAYER_A, community, TestFixtures.ASSAULT,
                TestFixtures.SOURCE, Optional.empty(), OptionalInt.empty(), Optional.empty(),
                List.of(), Set.of(), Map.of(), gameTime);
    }

    private CommunityReputationRecord community(CommunityKey key) {
        return seam.store().player(TestFixtures.PLAYER_A).orElseThrow().community(key).orElseThrow();
    }

    /** A store latched read-only the only way production can latch one: a file from a later format. */
    private static ReputationSavedData futureFormatStore() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", ReputationSavedData.FORMAT_VERSION + 1);
        ReputationSavedData store = ReputationSavedData.load(tag);
        assertTrue(store.isReadOnly(), "the fixture has to actually be read-only");
        return store;
    }

    // ------------------------------------------------------------------
    // The four reads that moved to the snapshot
    // ------------------------------------------------------------------

    @Test
    void theScoreBoundsComeFromTheSnapshot() {
        define(-50, DecayPolicy.NONE);
        seam.policy(ReputationPolicy.defaults().toBuilder()
                .minimumScore(-20).maximumScore(20).build());
        seam.gameTime(0L);

        ReputationResult result = seam.record(request(HOME, 0L));

        assertTrue(result.applied());
        assertEquals(-20, result.newScore(),
                "the clamp the transaction reports is the one it was decided against");
        assertEquals(-20, community(HOME).score());
        assertTrue(McaReputationConfig.minimumScore() < -20,
                "and the live config would have clamped somewhere else entirely");
    }

    @Test
    void thePerCommunityCapComesFromTheSnapshot() {
        seam.policy(ReputationPolicy.defaults().toBuilder().maxIncidentsPerCommunity(2).build());
        seam.gameTime(0L);
        assertTrue(seam.record(request(HOME, 0L)).applied());
        assertTrue(seam.record(request(HOME, 1L)).applied());

        ReputationResult refused = seam.record(request(HOME, 2L));

        assertFalse(refused.applied());
        assertEquals(ReputationResult.Reason.CAPACITY, refused.reason(),
                "two recent open negatives are not evictable, so the third is refused rather than "
                        + "quietly costing one of them");
        assertEquals(2, community(HOME).incidentCount());
        assertTrue(McaReputationConfig.maxIncidentsPerCommunity() > 2,
                "the live config would have admitted it");
    }

    @Test
    void theWholePlayerCapComesFromTheSnapshot() {
        seam.policy(ReputationPolicy.defaults().toBuilder().maxIncidentsPerPlayer(2).build());
        seam.gameTime(0L);
        assertTrue(seam.record(request(HOME, 0L)).applied());
        assertTrue(seam.record(request(AWAY, 1L)).applied());

        ReputationResult refused = seam.record(request(HOME, 2L));

        assertFalse(refused.applied());
        assertEquals(ReputationResult.Reason.CAPACITY, refused.reason());
        assertEquals(2, seam.store().player(TestFixtures.PLAYER_A).orElseThrow().totalIncidentCount());
        assertTrue(McaReputationConfig.maxIncidentsPerPlayer() > 2);
    }

    // ------------------------------------------------------------------
    // I14 — the write paths P1 left behind
    // ------------------------------------------------------------------

    @Test
    void anAdministrativeStandingChangeRefusesOnAReadOnlyStore() {
        seam.store(futureFormatStore());

        ReputationResult set = ReputationService.adjustBaseline(seam, TestFixtures.PLAYER_A, HOME, 500,
                true, TestFixtures.SOURCE, 100L);
        ReputationResult add = ReputationService.adjustBaseline(seam, TestFixtures.PLAYER_A, HOME, 25,
                false, TestFixtures.SOURCE, 200L);

        assertFalse(set.applied());
        assertEquals(ReputationResult.Reason.DISABLED, set.reason());
        assertFalse(add.applied());
        assertEquals(ReputationResult.Reason.DISABLED, add.reason());
        assertEquals(0, seam.store().playerCount(), "nothing was created on the way to refusing");
        assertFalse(seam.store().isDirty());
        assertEquals(0, seam.postedCount(),
                "and no standing change was announced for a write that did not happen");
    }

    @Test
    void aLegacyImportRefusesOnAReadOnlyStoreButStillPreviews() {
        seam.store(futureFormatStore());
        LegacyImportRequest real = new LegacyImportRequest(null, TestFixtures.PLAYER_A,
                "mcaquests:legacy_reputation_v1", "1", Map.of(HOME, 40), Map.of(), Map.of(), Set.of(),
                Map.of(), false);
        LegacyImportRequest preview = new LegacyImportRequest(null, TestFixtures.PLAYER_A,
                "mcaquests:legacy_reputation_v1", "1", Map.of(HOME, 40), Map.of(), Map.of(), Set.of(),
                Map.of(), true);

        ImportResult refused = ReputationService.importLegacyWith(seam, real);

        assertFalse(refused.applied());
        assertEquals(ImportResult.Reason.DISABLED, refused.reason());
        assertEquals(0, seam.store().playerCount(),
                "the migration marker is the thing that must not be written: it is one-way");
        assertFalse(seam.store().isDirty());

        ImportResult dryRun = ReputationService.importLegacyWith(seam, preview);
        assertEquals(ImportResult.Reason.DRY_RUN, dryRun.reason(),
                "a preview writes nothing by definition, so it is still worth answering");
        assertEquals(40, dryRun.baselineTotal());
    }

    @Test
    void anImmunityToggleRefusesOnAReadOnlyStore() {
        ReputationSavedData store = futureFormatStore();

        assertFalse(store.setDecayImmune(HOME, true),
                "reporting success for a flag that will not survive a restart is the defect");
        assertFalse(store.isDecayImmune(HOME));
        assertTrue(store.decayImmuneCommunities().isEmpty());
        assertFalse(store.isDirty());
    }

    @Test
    void anImmunityToggleStillWorksOnAWritableStore() {
        ReputationSavedData store = ReputationSavedData.createForTest();

        assertTrue(store.setDecayImmune(HOME, true));
        assertTrue(store.isDecayImmune(HOME));
        assertFalse(store.setDecayImmune(HOME, true), "and an unchanged flag still forces no write");
        assertTrue(store.setDecayImmune(HOME, false));
        assertFalse(store.isDecayImmune(HOME));
    }
}
