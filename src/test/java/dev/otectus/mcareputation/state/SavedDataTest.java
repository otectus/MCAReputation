package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §36.1 groups 4 and 11: whole-store persistence, per-entry corruption containment, and the
 * migration marker semantics that make legacy import exactly-once.
 */
class SavedDataTest {

    private static final int MIN = -1000;
    private static final int MAX = 1000;

    private static ReputationSavedData populated() {
        ReputationSavedData data = ReputationSavedData.createForTest();

        PlayerReputationRecord a = data.getOrCreatePlayer(TestFixtures.PLAYER_A);
        a.setLastKnownName("Ada");
        CommunityReputationRecord home = a.getOrCreate(TestFixtures.OVERWORLD_3);
        home.setMetadata(new dev.otectus.mcareputation.community.CommunityMetadata("Riverbend",
                Optional.of(new net.minecraft.core.BlockPos(10, 64, -20)), 500L));
        home.addIncident(IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 0L, TestFixtures.SOURCE,
                Optional.of("k1"), 30, IncidentVisibility.VILLAGE, IncidentSeverity.MINOR, List.of()));
        home.recomputeScore(MIN, MAX);
        home.setTierHighWater(dev.otectus.mcareputation.reputation.ReputationTiers.DEFAULT_ID,
                "acquaintance");
        home.grantTitle(new ResourceLocation("mcaquests:honored_of_village"));
        a.grantGlobalTitle(new ResourceLocation("mcareputation:wanderer"));
        a.markMigrated("mcaquests:legacy_reputation_v1", "1");

        PlayerReputationRecord b = data.getOrCreatePlayer(TestFixtures.PLAYER_B);
        b.getOrCreate(TestFixtures.NETHER_3).addBaseline(-40, MIN, MAX);
        return data;
    }

    @Test
    void wholeStoreRoundTrips() {
        ReputationSavedData loaded = populated().roundTripForTest();

        assertEquals(2, loaded.playerCount());
        PlayerReputationRecord a = loaded.player(TestFixtures.PLAYER_A).orElseThrow();
        assertEquals("Ada", a.lastKnownName());
        CommunityReputationRecord home = a.community(TestFixtures.OVERWORLD_3).orElseThrow();
        assertEquals(30, home.score());
        assertEquals("Riverbend", home.metadata().name());
        assertEquals(Optional.of("acquaintance"), home.tierHighWater(
                dev.otectus.mcareputation.reputation.ReputationTiers.DEFAULT_ID));
        assertTrue(home.hasTitle(new ResourceLocation("mcaquests:honored_of_village")));
        assertTrue(a.hasGlobalTitle(new ResourceLocation("mcareputation:wanderer")));
        assertTrue(a.hasMigrated("mcaquests:legacy_reputation_v1"));

        assertEquals(-40, loaded.score(TestFixtures.PLAYER_B, TestFixtures.NETHER_3));
    }

    /** §41: two players in the same village must diverge; one's deed never touches the other. */
    @Test
    void playersAreIndependentInTheSameCommunity() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        data.getOrCreatePlayer(TestFixtures.PLAYER_A).getOrCreate(TestFixtures.OVERWORLD_3)
                .addBaseline(100, MIN, MAX);
        data.getOrCreatePlayer(TestFixtures.PLAYER_B).getOrCreate(TestFixtures.OVERWORLD_3)
                .addBaseline(-100, MIN, MAX);

        assertEquals(100, data.score(TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3));
        assertEquals(-100, data.score(TestFixtures.PLAYER_B, TestFixtures.OVERWORLD_3));
    }

    /** §41: identical numeric village ids in two dimensions must not collide. */
    @Test
    void communitiesAreIndependentAcrossDimensions() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        PlayerReputationRecord player = data.getOrCreatePlayer(TestFixtures.PLAYER_A);
        player.getOrCreate(TestFixtures.OVERWORLD_3).addBaseline(75, MIN, MAX);
        player.getOrCreate(TestFixtures.NETHER_3).addBaseline(-75, MIN, MAX);

        ReputationSavedData loaded = data.roundTripForTest();
        assertEquals(75, loaded.score(TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3));
        assertEquals(-75, loaded.score(TestFixtures.PLAYER_A, TestFixtures.NETHER_3));
    }

    @Test
    void unknownPlayerAndCommunityReadAsZero() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        assertEquals(0, data.score(TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3));
        assertFalse(data.knows(TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3));
    }

    /** A plain query must not populate the save with a record for every village walked past. */
    @Test
    void queryingDoesNotCreateRecords() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        data.score(TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3);
        data.player(TestFixtures.PLAYER_A);
        assertEquals(0, data.playerCount());
    }

    // ------------------------------------------------------------------
    // §13.6 corruption containment
    // ------------------------------------------------------------------

    @Test
    void oneUnparseablePlayerKeyDoesNotCostTheOthers() {
        CompoundTag tag = populated().save(new CompoundTag());
        tag.getCompound("players").put("definitely-not-a-uuid", new CompoundTag());

        ReputationSavedData loaded = ReputationSavedData.load(tag);
        assertEquals(2, loaded.playerCount());
        assertEquals(30, loaded.score(TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3));
    }

    @Test
    void oneMalformedCommunityDoesNotCostThePlayersOthers() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        PlayerReputationRecord player = data.getOrCreatePlayer(TestFixtures.PLAYER_A);
        player.getOrCreate(TestFixtures.OVERWORLD_3).addBaseline(50, MIN, MAX);
        player.getOrCreate(TestFixtures.NETHER_3).addBaseline(20, MIN, MAX);

        CompoundTag tag = data.save(new CompoundTag());
        ListTag communities = tag.getCompound("players")
                .getCompound(TestFixtures.PLAYER_A.toString())
                .getList("communities", net.minecraft.nbt.Tag.TAG_COMPOUND);
        communities.getCompound(0).put("key", new CompoundTag()); // wreck the first one's identity

        ReputationSavedData loaded = ReputationSavedData.load(tag);
        PlayerReputationRecord reloaded = loaded.player(TestFixtures.PLAYER_A).orElseThrow();
        assertEquals(1, reloaded.communities().size(), "the surviving community must still load");
        assertEquals(20, loaded.score(TestFixtures.PLAYER_A, TestFixtures.NETHER_3));
    }

    @Test
    void oneMalformedIncidentDoesNotCostItsSiblings() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        CommunityReputationRecord community = data.getOrCreatePlayer(TestFixtures.PLAYER_A)
                .getOrCreate(TestFixtures.OVERWORLD_3);
        community.addIncident(IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 0L, TestFixtures.SOURCE,
                Optional.empty(), 10, IncidentVisibility.VILLAGE, IncidentSeverity.MINOR, List.of()));
        community.addIncident(IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 1L, TestFixtures.SOURCE,
                Optional.empty(), 5, IncidentVisibility.VILLAGE, IncidentSeverity.MINOR, List.of()));
        community.recomputeScore(MIN, MAX);

        CompoundTag tag = data.save(new CompoundTag());
        ListTag incidents = tag.getCompound("players")
                .getCompound(TestFixtures.PLAYER_A.toString())
                .getList("communities", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0)
                .getList("incidents", net.minecraft.nbt.Tag.TAG_COMPOUND);
        incidents.getCompound(0).putString("type", "!!! not a resource location !!!");

        ReputationSavedData loaded = ReputationSavedData.load(tag);
        CommunityReputationRecord reloaded = loaded.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(TestFixtures.OVERWORLD_3).orElseThrow();
        assertEquals(1, reloaded.incidentCount());
        assertEquals(5, reloaded.score(), "and the score is recomputed from what survived");
    }

    @Test
    void emptyRecordsAreNotPersisted() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        data.getOrCreatePlayer(TestFixtures.PLAYER_A);
        assertEquals(0, ReputationSavedData.load(data.save(new CompoundTag())).playerCount());
    }

    // ------------------------------------------------------------------
    // Migration markers (§32.2)
    // ------------------------------------------------------------------

    @Test
    void migrationMarkersSurviveARestart() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        PlayerReputationRecord player = data.getOrCreatePlayer(TestFixtures.PLAYER_A);
        player.getOrCreate(TestFixtures.OVERWORLD_3).addBaseline(120, MIN, MAX);
        player.markMigrated("mcaquests:legacy_reputation_v1", "1");

        ReputationSavedData loaded = data.roundTripForTest();
        PlayerReputationRecord reloaded = loaded.player(TestFixtures.PLAYER_A).orElseThrow();
        assertTrue(reloaded.hasMigrated("mcaquests:legacy_reputation_v1"));
        assertEquals(Optional.of("1"), reloaded.migrationVersion("mcaquests:legacy_reputation_v1"));
        assertEquals(120, reloaded.community(TestFixtures.OVERWORLD_3).orElseThrow().baseline(),
                "the imported balance is a baseline, not a fabricated deed");
        assertEquals(0, reloaded.community(TestFixtures.OVERWORLD_3).orElseThrow().incidentCount(),
                "migration invents no history it cannot substantiate");
    }

    @Test
    void anUnmigratedPlayerIsReportedAsSuch() {
        PlayerReputationRecord player = new PlayerReputationRecord(TestFixtures.PLAYER_A);
        assertFalse(player.hasMigrated("mcaquests:legacy_reputation_v1"));
        assertTrue(player.migrationVersion("mcaquests:legacy_reputation_v1").isEmpty());
    }

    @Test
    void wholePlayerIncidentCapIsEnforcedAcrossCommunities() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        PlayerReputationRecord player = data.getOrCreatePlayer(TestFixtures.PLAYER_A);
        for (var community : List.of(TestFixtures.OVERWORLD_3, TestFixtures.NETHER_3,
                TestFixtures.OVERWORLD_7)) {
            CommunityReputationRecord record = player.getOrCreate(community);
            for (int i = 0; i < 20; i++) {
                record.addIncident(IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                        TestFixtures.PLAYER_A, community, i, TestFixtures.SOURCE, Optional.empty(), 0,
                        IncidentVisibility.VILLAGE, IncidentSeverity.MINOR, List.of()));
            }
            record.recomputeScore(MIN, MAX);
        }
        assertEquals(60, player.totalIncidentCount());

        player.enforcePlayerIncidentCap(30, 1000L, MIN, MAX);
        assertTrue(player.totalIncidentCount() <= 30,
                "expected at most 30, got " + player.totalIncidentCount());
    }

    /** §13.5/§34: the load path enforces the same ceilings as the write path. */
    @Test
    void anOversizedOnDiskLedgerIsCappedOnLoad() {
        CommunityReputationRecord record = new CommunityReputationRecord(TestFixtures.OVERWORLD_3);
        CompoundTag tag = record.save();
        net.minecraft.nbt.ListTag incidents = new net.minecraft.nbt.ListTag();
        int oversize = dev.otectus.mcareputation.reputation.ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY + 40;
        for (int i = 0; i < oversize; i++) {
            incidents.add(IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                    TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, i, TestFixtures.SOURCE,
                    Optional.empty(), 0, IncidentVisibility.VILLAGE, IncidentSeverity.TRIVIAL,
                    List.of()).save());
        }
        tag.put("incidents", incidents);

        CommunityReputationRecord loaded =
                CommunityReputationRecord.load(tag, MIN, MAX).orElseThrow();
        assertEquals(dev.otectus.mcareputation.reputation.ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY,
                loaded.incidentCount(),
                "a hand-edited or corrupt save cannot smuggle an unbounded ledger into memory");
    }

    /**
     * Cap enforcement prunes incidents and folds weight into baselines — a store mutation like any
     * other. {@code reconcilePlayer} must report it and mark the save dirty even when decay itself
     * moved nothing, or the prune is silently lost on a crash before the next unrelated write.
     */
    @Test
    void capPruningAloneMarksTheStoreDirty() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        PlayerReputationRecord player = data.getOrCreatePlayer(TestFixtures.PLAYER_A);
        for (var community : List.of(TestFixtures.OVERWORLD_3, TestFixtures.NETHER_3)) {
            CommunityReputationRecord record = player.getOrCreate(community);
            for (int i = 0; i < 300; i++) {
                record.addIncident(IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                        TestFixtures.PLAYER_A, community, i, TestFixtures.SOURCE, Optional.empty(), 0,
                        IncidentVisibility.VILLAGE, IncidentSeverity.TRIVIAL, List.of()));
            }
            record.recomputeScore(MIN, MAX);
        }
        assertEquals(600, player.totalIncidentCount());
        assertFalse(data.isDirty(), "direct record construction has not touched the dirty flag");

        assertTrue(data.reconcilePlayer(TestFixtures.PLAYER_A, 1000L),
                "cap pruning is a change even though no decay ran");
        assertTrue(data.isDirty());
        assertTrue(player.totalIncidentCount() <= 512);
    }
}
