package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §36.1 group 5. */
class DedupeTest {

    private static final int MIN = -1000;
    private static final int MAX = 1000;
    private static final String KEY = "quest:example:make_amends:giver:100:complete";

    private static PlayerReputationRecord withIncident(String dedupeKey,
                                                       dev.otectus.mcareputation.community.CommunityKey community) {
        PlayerReputationRecord player = new PlayerReputationRecord(TestFixtures.PLAYER_A);
        IncidentRecord incident = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, community, 0L, TestFixtures.SOURCE, Optional.ofNullable(dedupeKey),
                12, IncidentVisibility.VILLAGE, IncidentSeverity.MINOR, List.of());
        player.getOrCreate(community).addIncident(incident);
        player.indexDedupe(incident);
        return player;
    }

    @Test
    void theSameKeyIsFoundAgain() {
        PlayerReputationRecord player = withIncident(KEY, TestFixtures.OVERWORLD_3);
        assertTrue(player.findByDedupeKey(TestFixtures.OVERWORLD_3, KEY).isPresent());
    }

    @Test
    void aDifferentKeyDoesNotCollide() {
        PlayerReputationRecord player = withIncident(KEY, TestFixtures.OVERWORLD_3);
        assertTrue(player.findByDedupeKey(TestFixtures.OVERWORLD_3, KEY + ":abandon").isEmpty());
    }

    /** Different communities are different transactions even under the same key (§14.2). */
    @Test
    void theSameKeyInAnotherCommunityIsADifferentTransaction() {
        PlayerReputationRecord player = withIncident(KEY, TestFixtures.OVERWORLD_3);
        assertTrue(player.findByDedupeKey(TestFixtures.NETHER_3, KEY).isEmpty());
        assertTrue(player.findByDedupeKey(TestFixtures.OVERWORLD_7, KEY).isEmpty());
    }

    @Test
    void aDifferentPlayerIsADifferentTransaction() {
        PlayerReputationRecord a = withIncident(KEY, TestFixtures.OVERWORLD_3);
        PlayerReputationRecord b = new PlayerReputationRecord(TestFixtures.PLAYER_B);
        assertTrue(a.findByDedupeKey(TestFixtures.OVERWORLD_3, KEY).isPresent());
        assertTrue(b.findByDedupeKey(TestFixtures.OVERWORLD_3, KEY).isEmpty());
    }

    /**
     * §14.2: the index is rebuilt from the records on load, so the guarantee has to survive a full
     * save/load cycle — this is the case that stops a relog from re-awarding a quest.
     */
    @Test
    void theGuaranteeSurvivesSaveAndLoad() {
        PlayerReputationRecord player = withIncident(KEY, TestFixtures.OVERWORLD_3);
        CompoundTag tag = player.save();
        PlayerReputationRecord loaded =
                PlayerReputationRecord.load(TestFixtures.PLAYER_A, tag, MIN, MAX);
        assertTrue(loaded.findByDedupeKey(TestFixtures.OVERWORLD_3, KEY).isPresent());
    }

    /** The index is advisory: losing it entirely must not cost the guarantee. */
    @Test
    void theLedgerRemainsTheAuthorityWhenTheIndexIsEmpty() {
        PlayerReputationRecord player = new PlayerReputationRecord(TestFixtures.PLAYER_A);
        IncidentRecord incident = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 0L, TestFixtures.SOURCE,
                Optional.of(KEY), 12, IncidentVisibility.VILLAGE, IncidentSeverity.MINOR, List.of());
        player.getOrCreate(TestFixtures.OVERWORLD_3).addIncident(incident);
        // deliberately NOT indexed
        assertTrue(player.findByDedupeKey(TestFixtures.OVERWORLD_3, KEY).isPresent(),
                "the fallback scan over the ledger keeps the guarantee");
    }

    @Test
    void aStaleIndexEntryDoesNotProduceAPhantomHit() {
        PlayerReputationRecord player = withIncident(KEY, TestFixtures.OVERWORLD_3);
        // Prune the incident away without touching the index.
        CommunityReputationRecord community = player.community(TestFixtures.OVERWORLD_3).orElseThrow();
        community.prune(0, 1000L, MIN, MAX);
        assertTrue(player.findByDedupeKey(TestFixtures.OVERWORLD_3, KEY).isEmpty());
    }

    @Test
    void blankAndNullKeysNeverMatch() {
        PlayerReputationRecord player = withIncident(null, TestFixtures.OVERWORLD_3);
        assertTrue(player.findByDedupeKey(TestFixtures.OVERWORLD_3, null).isEmpty());
        assertTrue(player.findByDedupeKey(TestFixtures.OVERWORLD_3, "").isEmpty());
        assertTrue(player.findByDedupeKey(TestFixtures.OVERWORLD_3, "   ").isEmpty());
    }

    @Test
    void rebuildingTheIndexRestoresEveryKey() {
        PlayerReputationRecord player = new PlayerReputationRecord(TestFixtures.PLAYER_A);
        for (int i = 0; i < 20; i++) {
            IncidentRecord incident = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                    TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, i, TestFixtures.SOURCE,
                    Optional.of("key:" + i), 1, IncidentVisibility.VILLAGE, IncidentSeverity.MINOR,
                    List.of());
            player.getOrCreate(TestFixtures.OVERWORLD_3).addIncident(incident);
        }
        player.rebuildDedupeIndex();
        for (int i = 0; i < 20; i++) {
            assertTrue(player.findByDedupeKey(TestFixtures.OVERWORLD_3, "key:" + i).isPresent(),
                    "missing key:" + i);
        }
    }

    @Test
    void communitiesStaySeparatePerPlayer() {
        PlayerReputationRecord player = new PlayerReputationRecord(TestFixtures.PLAYER_A);
        player.getOrCreate(TestFixtures.OVERWORLD_3).addBaseline(50, MIN, MAX);
        player.getOrCreate(TestFixtures.NETHER_3).addBaseline(-20, MIN, MAX);
        assertEquals(50, player.community(TestFixtures.OVERWORLD_3).orElseThrow().score());
        assertEquals(-20, player.community(TestFixtures.NETHER_3).orElseThrow().score());
    }
}
