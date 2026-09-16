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
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.api.ResolutionResult;
import dev.otectus.mcareputation.api.SupersedeSpec;
import dev.otectus.mcareputation.api.event.ReputationIncidentCreatedEvent;
import dev.otectus.mcareputation.reputation.ReputationPolicy;
import net.minecraft.nbt.CompoundTag;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import dev.otectus.mcareputation.reputation.TestDeliverySeam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * all, and the two backpressure budgets (§5 F03, §5 F09) — plus the transaction contract of §11.1:
 * what an observer can see at each stage boundary, and what a failure at each boundary leaves behind.
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
        define(delta, visibility, DecayPolicy.NONE);
    }

    private static void define(int delta, IncidentVisibility visibility, DecayPolicy decay) {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(delta, visibility, decay)));
    }

    /** A store latched read-only the only way production can latch one: a file from a later format. */
    private static ReputationSavedData futureFormatStore() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", ReputationSavedData.FORMAT_VERSION + 1);
        ReputationSavedData store = ReputationSavedData.load(tag);
        assertTrue(store.isReadOnly(), "the fixture has to actually be read-only");
        return store;
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

    // ------------------------------------------------------------------
    // §11.1 — what an observer sees at each stage boundary
    // ------------------------------------------------------------------

    /**
     * The headline of §3.2: the receipt is filed <em>before</em> anything is published.
     *
     * <p>The keyed path used to publish from inside the record transaction and append the receipt
     * afterwards. Between those two moments the operation was accepted and unrecorded, so a
     * synchronous consumer reacting to our own event saw a deed with no receipt behind it — and if it
     * responded by re-delivering the same operation, as a producer retrying an apparently unfinished
     * hand-over does, it was answered by the ordinary acceptance path and got a second deed for one
     * event. There is no such window now, and the re-entrant delivery is a duplicate.
     */
    @Test
    void aSynchronousListenerSeesTheReceiptAndCannotReplayTheOperationIntoASecondDeed() {
        List<Boolean> receiptVisible = new ArrayList<>();
        List<ReceiptOutcome> reentrant = new ArrayList<>();
        seam.listener(event -> {
            if (!(event instanceof ReputationIncidentCreatedEvent) || !reentrant.isEmpty()) {
                return;
            }
            receiptVisible.add(seam.findReceipt(NAMESPACE, TestFixtures.PLAYER_A, HOME, KEY).isPresent());
            reentrant.add(seam.deliver(delivery(KEY, 1000L)).outcome());
        });

        DeliveryOutcome outcome = seam.deliver(delivery(KEY, 1000L));

        assertEquals(ReceiptOutcome.APPLIED, outcome.outcome());
        assertEquals(List.of(Boolean.TRUE), receiptVisible,
                "the receipt has to be in the store before the event that announces the deed");
        assertEquals(List.of(ReceiptOutcome.DUPLICATE), reentrant,
                "a re-entrant delivery of the same operation is a replay, not a second award");
        assertEquals(1, home().incidentCount(), "one logical outcome, one canonical deed");
        assertEquals(-8, home().score());
        assertEquals(1, seam.store().player(TestFixtures.PLAYER_A).orElseThrow().receiptCount());
    }

    /** The publication boundary: a broken listener cannot un-consume a committed operation. */
    @Test
    void aListenerThatThrowsAfterTheCommitCannotUnconsumeTheOperation() {
        seam.listener(event -> {
            throw new TestDeliverySeam.InjectedFailure("listener");
        });

        DeliveryOutcome outcome = seam.deliver(delivery(KEY, 1000L));

        assertEquals(ReceiptOutcome.APPLIED, outcome.outcome());
        assertTrue(outcome.receipt().isPresent(), "the receipt was already filed when the listener ran");
        assertEquals(1, home().incidentCount());

        seam.listener(null);
        assertEquals(ReceiptOutcome.DUPLICATE, seam.deliver(delivery(KEY, 1000L)).outcome(),
                "consumed once, and it stays consumed");
        assertEquals(1, home().incidentCount());
    }

    /**
     * The preflight boundary: a failure while the operation is still being staged leaves nothing at
     * all — which is the only condition under which the contained-failure log line may honestly claim
     * nothing was written.
     *
     * <p>The clock is the earliest thing the transaction touches; read two is the one inside the
     * commit, after validation and the definition lookup.
     */
    @Test
    void aFailureWhileStagingLeavesNoLedgerNoReceiptAndNoEvent() {
        seam.failClockRead(2);

        DeliveryOutcome outcome = seam.deliver(delivery(KEY, 1000L));

        assertEquals(ReceiptOutcome.REFUSED_INVALID, outcome.outcome());
        assertEquals(ReputationResult.Reason.ERROR, outcome.result().reason());
        assertTrue(outcome.receipt().isEmpty(),
                "a contained internal failure is the one refusal a producer may retry");
        assertEquals(0, seam.store().playerCount());
        assertEquals(0, seam.postedCount());
        assertFalse(seam.store().isDirty());

        seam.failClockRead(-1);
        assertEquals(ReceiptOutcome.APPLIED, seam.deliver(delivery(KEY, 1000L)).outcome(),
                "and the retry succeeds exactly once");
        assertEquals(1, home().incidentCount());
    }

    /**
     * The mutation boundary. Everything that can fail now happens before the first write, so the last
     * fallible call in the commit — the online-player lookup for the stored name — is the tightest
     * available injection point: it throws with the admission decision already made and not one field
     * of the ledger touched.
     */
    @Test
    void aFailureAtTheLastReadBeforeTheWriteLeavesTheLedgerUntouched() {
        assertEquals(ReceiptOutcome.APPLIED, seam.deliver(delivery("first", 1000L)).outcome());
        int score = home().score();
        long revision = home().revision();
        seam.clearPosted();
        seam.failOnlinePlayerLookup(true);

        DeliveryOutcome outcome = seam.deliver(delivery(KEY, 1000L));

        assertEquals(ReceiptOutcome.REFUSED_INVALID, outcome.outcome());
        assertEquals(ReputationResult.Reason.ERROR, outcome.result().reason());
        assertTrue(outcome.receipt().isEmpty());
        assertEquals(1, home().incidentCount(), "no partial deed");
        assertEquals(score, home().score());
        assertEquals(revision, home().revision(), "and no standing revision for a change that failed");
        assertEquals(1, seam.store().player(TestFixtures.PLAYER_A).orElseThrow().receiptCount());
        assertEquals(0, seam.postedCount());

        seam.failOnlinePlayerLookup(false);
        assertEquals(ReceiptOutcome.APPLIED, seam.deliver(delivery(KEY, 1000L)).outcome());
        assertEquals(2, home().incidentCount());
    }

    // ------------------------------------------------------------------
    // I14 — a read-only store refuses, it does not pretend
    // ------------------------------------------------------------------

    /**
     * Every write path this transaction owns refuses on a store latched read-only by a future save
     * format, rather than applying the change in memory and losing it at restart.
     *
     * <p>The old behaviour was documented as a feature: "every mutation is still accepted by the API
     * and simply never persisted". It is the one outcome no consumer can recover from — the toast
     * fires, a producer files the returned incident id as proof of settlement, and the whole
     * transaction is gone on the next restart with nobody having seen an error.
     */
    @Test
    void aReadOnlyStoreRefusesEveryWritePathInsteadOfLosingItAtRestart() {
        seam.store(futureFormatStore());

        DeliveryOutcome keyed = seam.deliver(delivery(KEY, 1000L));
        assertEquals(ReceiptOutcome.REFUSED_DISABLED, keyed.outcome(), "retryable, not terminal");
        assertEquals(ReputationResult.Reason.DISABLED, keyed.result().reason());
        assertTrue(keyed.receipt().isEmpty());

        ReputationResult unkeyed = seam.record(request(null, Set.of(TestFixtures.VILLAGER_1), 1000L));
        assertFalse(unkeyed.applied());
        assertEquals(ReputationResult.Reason.DISABLED, unkeyed.reason());

        ReputationResult superseding = seam.recordSuperseding(
                request(null, Set.of(TestFixtures.VILLAGER_1), 1000L),
                SupersedeSpec.of(UUID.randomUUID(), 200L, false));
        assertFalse(superseding.applied());
        assertEquals(ReputationResult.Reason.DISABLED, superseding.reason());

        UUID unknownIncident = UUID.randomUUID();
        assertEquals(ResolutionResult.Reason.DISABLED,
                seam.resolve(TestFixtures.PLAYER_A, HOME, unknownIncident, IncidentStatus.APOLOGIZED,
                        TestFixtures.SOURCE).reason(),
                "and read-only is reported before not-found, because the store cannot answer either");
        assertEquals(ResolutionResult.Reason.DISABLED,
                seam.resolveBound(TestFixtures.PLAYER_A, HOME, unknownIncident,
                        IncidentStatus.APOLOGIZED, TestFixtures.SOURCE, "bound:1").reason());

        assertEquals(0, seam.store().playerCount(), "nothing was created on the way to refusing");
        assertFalse(seam.store().isDirty());
        assertEquals(0, seam.postedCount());
    }

    // ------------------------------------------------------------------
    // §3.2 — admission is decided against the reconciled ledger
    // ------------------------------------------------------------------

    /**
     * A ledger whose entries become evictable at {@code now} must not stay full forever.
     *
     * <p>Eligibility to evict is a function of age: a record is evictable once it has stopped
     * contributing and has aged past the receipt horizon. Admission used to be checked before the
     * transaction's reconciliation pass, so a ledger of contributions that had in fact decayed to
     * nothing still looked live, the deed was refused — and because the refusal returned without
     * reconciling, the next deed was refused too. Permanently, with no way out but an admin command.
     *
     * <p>The same delivery, the same ledger: refused at a clock where nothing has aged out, admitted
     * at a clock where it has.
     */
    @Test
    void admissionIsDecidedAfterAgingSoAFullLedgerRecoversOnItsOwn() {
        // Two points a day to zero: eight points of assault is spent after four days.
        define(-8, IncidentVisibility.VILLAGE, DecayPolicy.linearToZero(0L, 2));
        int cap = McaReputationConfig.maxIncidentsPerCommunity();
        PlayerReputationRecord player = seam.store().getOrCreatePlayer(TestFixtures.PLAYER_A);
        CommunityReputationRecord community = player.getOrCreate(HOME);
        for (int i = 0; i < cap; i++) {
            community.addIncident(IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                    TestFixtures.PLAYER_A, HOME, 0L, TestFixtures.SOURCE, Optional.empty(), -8,
                    IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE, List.of()));
        }
        community.recomputeScore(MIN, MAX);
        assertEquals(cap, community.incidentCount());

        // Two days in: every entry still carries weight, so nothing may be evicted and the refusal is
        // the honest answer.
        seam.gameTime(2 * DecayPolicy.TICKS_PER_DAY);
        assertEquals(ReceiptOutcome.REFUSED_CAPACITY, seam.deliver(delivery("early", 0L)).outcome());
        assertEquals(cap, community.incidentCount(), "no partial commit");

        // Past the decay and past the receipt horizon, the same ledger has room.
        seam.gameTime(ReputationPolicy.DEFAULT_RECEIPT_RETENTION_TICKS + DecayPolicy.TICKS_PER_DAY);
        DeliveryOutcome late = seam.deliver(delivery("late", seam.gameTime()));

        assertEquals(ReceiptOutcome.APPLIED, late.outcome(),
                "aged-out history is evictable, and the deed that arrives now gets its place");
        assertEquals(cap, community.incidentCount(), "the cap is still the cap");
        assertTrue(community.incident(late.result().incidentId().orElseThrow()).isPresent());
        assertEquals(-8, community.score(), "the spent history contributes nothing to the new total");
    }

    /**
     * The other direction: aging is what creates room, so a community where aging is switched off
     * stays full. A freeze must not be a back door that evicts protected history to make space.
     */
    @Test
    void aFrozenCommunityStaysFullBecauseNothingAges() {
        define(-8, IncidentVisibility.VILLAGE, DecayPolicy.linearToZero(0L, 2));
        int cap = McaReputationConfig.maxIncidentsPerCommunity();
        PlayerReputationRecord player = seam.store().getOrCreatePlayer(TestFixtures.PLAYER_A);
        CommunityReputationRecord community = player.getOrCreate(HOME);
        for (int i = 0; i < cap; i++) {
            community.addIncident(IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                    TestFixtures.PLAYER_A, HOME, 0L, TestFixtures.SOURCE, Optional.empty(), -8,
                    IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE, List.of()));
        }
        community.recomputeScore(MIN, MAX);
        int score = community.score();
        seam.store().setDecayImmune(HOME, true);

        seam.gameTime(ReputationPolicy.DEFAULT_RECEIPT_RETENTION_TICKS + DecayPolicy.TICKS_PER_DAY);
        DeliveryOutcome outcome = seam.deliver(delivery("late", seam.gameTime()));

        assertEquals(ReceiptOutcome.REFUSED_CAPACITY, outcome.outcome());
        assertEquals(cap, community.incidentCount());
        assertEquals(score, community.score(), "a protected ledger is not aged to make room");
        assertTrue(outcome.receipt().isEmpty(), "and the refusal stays retryable");
    }

    // ------------------------------------------------------------------
    // §10.6 — the replay horizon is bounded, and says so
    // ------------------------------------------------------------------

    /**
     * Beyond the retained receipt, an absent receipt is not proof that the operation never happened —
     * it is proof that this store can no longer say. The published floor is what tells a producer to
     * quarantine an old unacknowledged operation instead of retrying it under the same key.
     */
    @Test
    void anOperationBeyondTheReceiptBudgetIsNoLongerAnswerableAsAReplay() {
        assertEquals(ReceiptOutcome.APPLIED, seam.deliver(delivery(KEY, 1000L)).outcome());
        PlayerReputationRecord player = seam.store().player(TestFixtures.PLAYER_A).orElseThrow();
        for (int i = 0; i < ReputationBounds.MAX_RECEIPTS_PER_PLAYER; i++) {
            player.recordReceipt(new OperationReceipt(NAMESPACE, TestFixtures.PLAYER_A, HOME,
                    "later:" + i, ReceiptOutcome.APPLIED, Optional.of(UUID.randomUUID()),
                    2000L + i, 2000L + i));
        }

        assertTrue(seam.findReceipt(NAMESPACE, TestFixtures.PLAYER_A, HOME, KEY).isEmpty(),
                "the oldest receipt is what the newest cost");
        assertTrue(player.receiptFloor().orElseThrow() > 1000L,
                "the floor is above our occurrence, which is how a producer knows to quarantine");

        seam.gameTime(3000L);
        DeliveryOutcome replay = seam.deliver(delivery(KEY, 1000L));

        assertEquals(ReceiptOutcome.APPLIED, replay.outcome(),
                "honest rather than exactly-once: past the floor this store cannot recognise the retry");
        assertEquals(2, home().incidentCount(),
                "which is precisely why the documented guarantee is a bounded recovery window");
    }
}
