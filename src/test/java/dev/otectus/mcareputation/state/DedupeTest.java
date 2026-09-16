package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.DeliveryOutcome;
import dev.otectus.mcareputation.api.IncidentDelivery;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.incident.ResolutionPolicy;
import dev.otectus.mcareputation.reputation.TestDeliverySeam;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
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
        PlayerReputationRecord player = new PlayerReputationRecord(TestFixtures.PLAYER_A);
        // Spent history: zero contribution, so §5 F09 lets it be evicted at all.
        IncidentRecord spent = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 0L, TestFixtures.SOURCE,
                Optional.of(KEY), 0, IncidentVisibility.VILLAGE, IncidentSeverity.TRIVIAL, List.of());
        player.getOrCreate(TestFixtures.OVERWORLD_3).addIncident(spent);
        player.indexDedupe(spent);
        // Prune the incident away without touching the index.
        CommunityReputationRecord community = player.community(TestFixtures.OVERWORLD_3).orElseThrow();
        community.prune(0, 1000L, MIN, MAX);
        assertTrue(player.findByDedupeKey(TestFixtures.OVERWORLD_3, KEY).isEmpty());
    }

    /**
     * T20, inverted (§10). A pruned dedupe key used to vanish from the ledger, and replaying it would
     * award the deed a second time — the ledger cannot remember what it no longer holds. The receipt
     * outlives its incident, so the replay is still refused and still names what it originally created.
     */
    @Test
    void aPrunedKeyIsStillRefusedBecauseTheReceiptSurvives() {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(-8, IncidentVisibility.VILLAGE, DecayPolicy.NONE)));
        try {
            TestDeliverySeam seam = new TestDeliverySeam();
            seam.gameTime(1000L);
            IncidentDelivery delivery = IncidentDelivery.of(new ReputationRequest(null,
                    TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, TestFixtures.ASSAULT,
                    TestFixtures.SOURCE, Optional.empty(), OptionalInt.empty(), Optional.empty(),
                    List.of(), Set.of(TestFixtures.VILLAGER_1), Map.of(), 1000L), "mcaquests", KEY);

            DeliveryOutcome first = seam.deliver(delivery);
            UUID incidentId = first.result().incidentId().orElseThrow();

            PlayerReputationRecord player = seam.store().player(TestFixtures.PLAYER_A).orElseThrow();
            CommunityReputationRecord community =
                    player.community(TestFixtures.OVERWORLD_3).orElseThrow();
            community.incident(incidentId).orElseThrow()
                    .resolve(ResolutionPolicy.DEFAULT, null, IncidentStatus.FORGIVEN, 1000L);
            community.prune(0, 1000L, MIN, MAX);
            assertTrue(player.findByDedupeKey(TestFixtures.OVERWORLD_3, KEY).isEmpty(),
                    "the ledger itself has forgotten the key");

            DeliveryOutcome replay = seam.deliver(delivery);
            assertEquals(ReceiptOutcome.DUPLICATE, replay.outcome());
            assertEquals(Optional.of(incidentId), replay.result().incidentId());
            assertEquals(0, community.incidentCount(), "and the deed was not recorded a second time");
        } finally {
            IncidentRegistry.replaceAll(Map.of());
        }
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
