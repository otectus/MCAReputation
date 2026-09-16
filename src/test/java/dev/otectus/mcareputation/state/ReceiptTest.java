package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.DeliveryOutcome;
import dev.otectus.mcareputation.api.IncidentDelivery;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.api.ReceiptView;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import dev.otectus.mcareputation.reputation.TestDeliverySeam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T04, T10 and T11: exactly-once delivery across a restart, read-only lookups that change nothing at
 * all, and the two backpressure budgets (§5 F03, §5 F09).
 */
class ReceiptTest {

    private static final int MIN = -1000;
    private static final int MAX = 1000;
    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;
    private static final String NAMESPACE = "mcacrime";
    private static final String KEY = "crime:assault:1234";

    private final TestDeliverySeam seam = new TestDeliverySeam();

    @BeforeEach
    void setUp() {
        define(-8, IncidentVisibility.VILLAGE);
        seam.gameTime(1000L);
    }

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
        McaReputationConfig.TestOverrides.reset();
    }

    private static void define(int delta, IncidentVisibility visibility) {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(delta, visibility, DecayPolicy.NONE)));
    }

    private static ReputationRequest request(String dedupeKey, Set<UUID> witnesses, long gameTime) {
        return new ReputationRequest(null, TestFixtures.PLAYER_A, HOME, TestFixtures.ASSAULT,
                TestFixtures.SOURCE, Optional.ofNullable(dedupeKey), OptionalInt.empty(),
                Optional.empty(), List.of(), witnesses, Map.of(), gameTime);
    }

    private static IncidentDelivery delivery(String key, long gameTime) {
        return IncidentDelivery.of(request(null, Set.of(TestFixtures.VILLAGER_1), gameTime), NAMESPACE, key);
    }

    private CommunityReputationRecord home() {
        return seam.store().player(TestFixtures.PLAYER_A).orElseThrow().community(HOME).orElseThrow();
    }

    // ------------------------------------------------------------------
    // T04 — the acknowledgment that never arrived
    // ------------------------------------------------------------------

    @Test
    void aReplayedOperationReturnsTheFirstIncidentAndRecordsNothingNew() {
        DeliveryOutcome first = seam.deliver(delivery(KEY, 1000L));
        assertEquals(ReceiptOutcome.APPLIED, first.outcome());
        UUID incidentId = first.result().incidentId().orElseThrow();
        assertEquals(1, home().incidentCount());
        int score = home().score();

        // The producer crashed here: it never stored the id we just returned.
        seam.reload();
        seam.gameTime(2000L);
        DeliveryOutcome replay = seam.deliver(delivery(KEY, 1000L));

        assertEquals(ReceiptOutcome.DUPLICATE, replay.outcome());
        assertEquals(Optional.of(incidentId), replay.result().incidentId(),
                "the replay has to name the incident the first attempt created");
        assertEquals(ReputationResult.Reason.DUPLICATE, replay.result().reason());
        assertFalse(replay.result().applied());
        assertEquals(1, home().incidentCount(), "one delivery, one ledger record");
        assertEquals(score, home().score());
    }

    /** The stored receipt survives the round trip in its own right, not just its incident. */
    @Test
    void receiptsSurviveSaveAndLoad() {
        seam.deliver(delivery(KEY, 1000L));
        seam.reload();

        Optional<ReceiptView> receipt =
                seam.findReceipt(NAMESPACE, TestFixtures.PLAYER_A, HOME, KEY);
        assertTrue(receipt.isPresent());
        assertEquals(ReceiptOutcome.APPLIED, receipt.orElseThrow().outcome());
        assertEquals(1000L, receipt.orElseThrow().occurredGameTime(),
                "the receipt records when the deed happened, not when it arrived");
    }

    /** A key written before receipts existed carries no namespace, and must still resolve. */
    @Test
    void aLegacyUnnamespacedKeyResolves() {
        // The pre-receipt world: an incident in the ledger under the key, and no receipt at all.
        PlayerReputationRecord player = seam.store().getOrCreatePlayer(TestFixtures.PLAYER_A);
        IncidentRecord legacy = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, HOME, 500L, TestFixtures.SOURCE, Optional.of(KEY), -8,
                IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE, List.of());
        player.getOrCreate(HOME).addIncident(legacy);
        player.getOrCreate(HOME).recomputeScore(MIN, MAX);
        player.indexDedupe(legacy);
        assertEquals(0, player.receiptCount());

        DeliveryOutcome outcome = seam.deliver(delivery(KEY, 1000L));

        assertEquals(ReceiptOutcome.DUPLICATE, outcome.outcome());
        assertEquals(Optional.of(legacy.id()), outcome.result().incidentId());
        assertEquals(1, home().incidentCount());
        assertEquals(1, player.receiptCount(), "the legacy hit is given a receipt on the spot");
    }

    /** Whoever files it, the same key inside one community is the same operation. */
    @Test
    void aReceiptFiledUnderAnotherNamespaceStillMatches() {
        seam.deliver(IncidentDelivery.of(request(null, Set.of(TestFixtures.VILLAGER_1), 1000L),
                "mcaquests", KEY));
        DeliveryOutcome second = seam.deliver(delivery(KEY, 1000L));
        assertEquals(ReceiptOutcome.DUPLICATE, second.outcome());
        assertEquals(1, home().incidentCount());
    }

    /** A delivery with no operation key is exactly today's record(): no identity, no receipt. */
    @Test
    void anUnkeyedDeliveryLeavesNoReceipt() {
        DeliveryOutcome outcome = seam.deliver(IncidentDelivery.of(
                request(null, Set.of(TestFixtures.VILLAGER_1), 1000L)));
        assertEquals(ReceiptOutcome.APPLIED, outcome.outcome());
        assertTrue(outcome.receipt().isEmpty());
        assertEquals(0, seam.store().player(TestFixtures.PLAYER_A).orElseThrow().receiptCount());
    }

    // ------------------------------------------------------------------
    // T10 — the read-only lookups
    // ------------------------------------------------------------------

    @Test
    void lookupsChangeNothingAtAll() {
        // A definition whose visibility makes an unwitnessed assault public: no witness is needed for
        // the deed to count, which is the shape the write-capable probe used to be reached for.
        define(-8, IncidentVisibility.VILLAGE);
        DeliveryOutcome delivered = seam.deliver(IncidentDelivery.of(
                request(null, Set.of(), 1000L), NAMESPACE, KEY));
        assertEquals(ReceiptOutcome.APPLIED, delivered.outcome());
        UUID incidentId = delivered.result().incidentId().orElseThrow();

        int players = seam.store().playerCount();
        int ledger = home().incidentCount();
        int score = home().score();
        int receipts = seam.store().player(TestFixtures.PLAYER_A).orElseThrow().receiptCount();
        seam.clearPosted();
        seam.gameTime(500_000L); // far enough that a stray reconcile would show

        assertTrue(seam.findReceipt(NAMESPACE, TestFixtures.PLAYER_A, HOME, KEY).isPresent());
        assertTrue(seam.findIncident(TestFixtures.PLAYER_A, HOME, incidentId).isPresent());
        assertEquals(OptionalLong.empty(), seam.receiptFloor(TestFixtures.PLAYER_A));

        // The same three questions about somebody nobody has ever recorded anything for.
        assertTrue(seam.findReceipt(NAMESPACE, TestFixtures.PLAYER_B, HOME, KEY).isEmpty());
        assertTrue(seam.findIncident(TestFixtures.PLAYER_B, HOME, incidentId).isEmpty());
        assertEquals(OptionalLong.empty(), seam.receiptFloor(TestFixtures.PLAYER_B));

        assertEquals(players, seam.store().playerCount(), "an unknown player was not created");
        assertEquals(ledger, home().incidentCount());
        assertEquals(score, home().score(), "nothing was reconciled");
        assertEquals(receipts,
                seam.store().player(TestFixtures.PLAYER_A).orElseThrow().receiptCount());
        assertEquals(0, seam.postedCount(), "a read posts no events");
    }

    // ------------------------------------------------------------------
    // T11 — the two budgets
    // ------------------------------------------------------------------

    @Test
    void theReceiptBudgetEvictsOldestFirstAndPublishesAFloor() {
        PlayerReputationRecord player = seam.store().getOrCreatePlayer(TestFixtures.PLAYER_A);
        assertEquals(OptionalLong.empty(), player.receiptFloor(),
                "nothing forgotten yet, so there is no floor");

        int overBudget = ReputationBounds.MAX_RECEIPTS_PER_PLAYER + 40;
        for (int i = 0; i < overBudget; i++) {
            player.recordReceipt(new OperationReceipt(NAMESPACE, TestFixtures.PLAYER_A, HOME,
                    "op:" + i, ReceiptOutcome.APPLIED, Optional.of(UUID.randomUUID()), i, i));
        }

        assertEquals(ReputationBounds.MAX_RECEIPTS_PER_PLAYER, player.receiptCount());
        assertTrue(player.findReceipt(NAMESPACE, HOME, "op:0").isEmpty(), "the oldest went first");
        assertTrue(player.findReceipt(NAMESPACE, HOME, "op:" + (overBudget - 1)).isPresent(),
                "the newest is still answerable");
        assertEquals(OptionalLong.of(40L), player.receiptFloor(),
                "the floor names the oldest occurrence still answerable");
    }

    @Test
    void aLedgerWithNothingEvictableRefusesTheDeedRatherThanExceedingTheCap() {
        int cap = McaReputationConfig.maxIncidentsPerCommunity();
        PlayerReputationRecord player = seam.store().getOrCreatePlayer(TestFixtures.PLAYER_A);
        CommunityReputationRecord community = player.getOrCreate(HOME);
        for (int i = 0; i < cap; i++) {
            IncidentRecord pinned = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                    TestFixtures.PLAYER_A, HOME, i, TestFixtures.SOURCE, Optional.empty(), 0,
                    IncidentVisibility.VILLAGE, IncidentSeverity.TRIVIAL, List.of());
            pinned.setPinned(true);
            community.addIncident(pinned);
        }
        community.recomputeScore(MIN, MAX);
        int score = community.score();
        seam.clearPosted();

        DeliveryOutcome outcome = seam.deliver(delivery(KEY, 1000L));

        assertEquals(ReceiptOutcome.REFUSED_CAPACITY, outcome.outcome());
        assertEquals(ReputationResult.Reason.CAPACITY, outcome.result().reason());
        assertFalse(outcome.result().applied());
        assertEquals(cap, community.incidentCount(), "no partial commit");
        assertEquals(score, community.score());
        assertTrue(outcome.receipt().isEmpty(), "a retryable refusal stores nothing");
        assertEquals(0, player.receiptCount());
        assertEquals(0, seam.postedCount(), "a refusal before the commit posts nothing");
    }

    /** The same refusal must not become permanent: clearing the pin lets the deed through. */
    @Test
    void aCapacityRefusalIsRetryable() {
        int cap = McaReputationConfig.maxIncidentsPerCommunity();
        PlayerReputationRecord player = seam.store().getOrCreatePlayer(TestFixtures.PLAYER_A);
        CommunityReputationRecord community = player.getOrCreate(HOME);
        List<IncidentRecord> pinned = new java.util.ArrayList<>();
        for (int i = 0; i < cap; i++) {
            IncidentRecord record = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                    TestFixtures.PLAYER_A, HOME, i, TestFixtures.SOURCE, Optional.empty(), 0,
                    IncidentVisibility.VILLAGE, IncidentSeverity.TRIVIAL, List.of());
            record.setPinned(true);
            community.addIncident(record);
            pinned.add(record);
        }
        community.recomputeScore(MIN, MAX);
        assertEquals(ReceiptOutcome.REFUSED_CAPACITY, seam.deliver(delivery(KEY, 1000L)).outcome());

        pinned.get(0).setPinned(false);
        DeliveryOutcome retry = seam.deliver(delivery(KEY, 1000L));

        assertEquals(ReceiptOutcome.APPLIED, retry.outcome());
        assertEquals(cap, community.incidentCount());
        assertTrue(community.incident(pinned.get(0).id()).isEmpty(), "the unpinned entry made the room");
    }

    /** Retryable refusals are the ones a receipt must never make permanent. */
    @Test
    void aDisabledRefusalLeavesNoReceipt() {
        McaReputationConfig.TestOverrides.enabled = false;

        DeliveryOutcome outcome = seam.deliver(delivery(KEY, 1000L));

        assertEquals(ReceiptOutcome.REFUSED_DISABLED, outcome.outcome());
        assertTrue(outcome.receipt().isEmpty());
        assertEquals(0, seam.store().playerCount(), "a refusal before the commit creates no record");

        McaReputationConfig.TestOverrides.enabled = true;
        assertEquals(ReceiptOutcome.APPLIED, seam.deliver(delivery(KEY, 1000L)).outcome(),
                "the retry succeeds, which is what 'retryable' has to mean");
    }

    /** An unknown incident type is a producer bug, not a transient one: it is remembered. */
    @Test
    void anInvalidDeliveryIsRefusedTerminally() {
        IncidentRegistry.replaceAll(Map.of());

        DeliveryOutcome outcome = seam.deliver(delivery(KEY, 1000L));

        assertEquals(ReceiptOutcome.REFUSED_INVALID, outcome.outcome());
        assertTrue(outcome.receipt().isPresent());

        // And it stays refused, even once the type exists, because the operation is finished.
        define(-8, IncidentVisibility.VILLAGE);
        DeliveryOutcome replay = seam.deliver(delivery(KEY, 1000L));
        assertEquals(ReceiptOutcome.REFUSED_INVALID, replay.outcome());
        assertTrue(seam.store().player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).map(CommunityReputationRecord::incidentCount).orElse(0) == 0);
    }

    /** An accepted delivery that produced no public consequence is still remembered. */
    @Test
    void anUnwitnessedDeedIsAcceptedWithNoPublicIncident() {
        IncidentDefinition witnessed = TestFixtures.definition(-8, IncidentVisibility.WITNESSED,
                DecayPolicy.NONE);
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT, witnessed));

        DeliveryOutcome outcome = seam.deliver(IncidentDelivery.of(
                request(null, Set.of(), 1000L), NAMESPACE, KEY));

        assertEquals(ReceiptOutcome.ACCEPTED_NO_PUBLIC_INCIDENT, outcome.outcome());
        assertTrue(outcome.receipt().isPresent());
        assertNotEquals(ReceiptOutcome.APPLIED, outcome.outcome());

        DeliveryOutcome replay = seam.deliver(IncidentDelivery.of(
                request(null, Set.of(), 1000L), NAMESPACE, KEY));
        assertEquals(ReceiptOutcome.DUPLICATE, replay.outcome());
    }
}
