package dev.otectus.mcareputation.incident;

import dev.otectus.mcareputation.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §36.1 group 9. */
class AwarenessTest {

    private static final int MIN_DELAY = 6000;
    private static final int MAX_DELAY = 48000;

    private static IncidentRecord witnessed(long created, UUID... witnesses) {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.WITNESSED, created);
        record.addWitnesses(List.of(witnesses));
        return record;
    }

    @Test
    void witnessesKnowImmediately() {
        IncidentRecord record = witnessed(0L, TestFixtures.VILLAGER_1);
        assertTrue(AwarenessResolver.knows(record, TestFixtures.VILLAGER_1, true, 0L, MIN_DELAY, MAX_DELAY));
    }

    /** A witness carries what they saw with them, even after moving away (§19.3). */
    @Test
    void aWitnessKnowsEvenAfterLeavingTheVillage() {
        IncidentRecord record = witnessed(0L, TestFixtures.VILLAGER_1);
        assertTrue(AwarenessResolver.knows(record, TestFixtures.VILLAGER_1, false, 0L, MIN_DELAY, MAX_DELAY));
    }

    @Test
    void villageVisibilityIsKnownToEveryResidentAtOnce() {
        IncidentRecord record = TestFixtures.record(5, IncidentVisibility.VILLAGE, 0L);
        assertTrue(AwarenessResolver.knows(record, TestFixtures.VILLAGER_2, true, 0L, MIN_DELAY, MAX_DELAY));
        assertFalse(AwarenessResolver.knows(record, TestFixtures.VILLAGER_2, false, 0L, MIN_DELAY, MAX_DELAY),
                "someone who does not live here has no claim on village business");
    }

    /** §19.2: private incidents never spread; only a subject of the deed itself may recall it. */
    @Test
    void privateNeverSpreads() {
        IncidentRecord record = TestFixtures.record(0, IncidentVisibility.PRIVATE, 0L);
        assertTrue(AwarenessResolver.knows(record, TestFixtures.VILLAGER_1, true, Long.MAX_VALUE / 4,
                MIN_DELAY, MAX_DELAY), "the villager the promise was made to remembers it");
        assertFalse(AwarenessResolver.knows(record, TestFixtures.VILLAGER_2, true, Long.MAX_VALUE / 4,
                MIN_DELAY, MAX_DELAY), "nobody else ever hears");
    }

    @Test
    void globalReservedBehavesExactlyAsVillage() {
        IncidentRecord record = TestFixtures.record(5, IncidentVisibility.GLOBAL_RESERVED, 0L);
        assertTrue(AwarenessResolver.knows(record, TestFixtures.VILLAGER_2, true, 0L, MIN_DELAY, MAX_DELAY));
        assertEquals(IncidentVisibility.VILLAGE, IncidentVisibility.GLOBAL_RESERVED.effective());
    }

    @Test
    void nonWitnessesLearnOnlyAfterTheirOwnDelay() {
        IncidentRecord record = witnessed(0L, TestFixtures.VILLAGER_1);
        long delay = AwarenessResolver.rumorDelayTicks(record.id(), TestFixtures.VILLAGER_2,
                record.community(), MIN_DELAY, MAX_DELAY);
        assertFalse(AwarenessResolver.knows(record, TestFixtures.VILLAGER_2, true, delay - 1,
                MIN_DELAY, MAX_DELAY));
        assertTrue(AwarenessResolver.knows(record, TestFixtures.VILLAGER_2, true, delay,
                MIN_DELAY, MAX_DELAY));
    }

    @Test
    void theDelayIsStableAcrossCalls() {
        IncidentRecord record = witnessed(0L);
        long first = AwarenessResolver.rumorDelayTicks(record.id(), TestFixtures.VILLAGER_2,
                record.community(), MIN_DELAY, MAX_DELAY);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, AwarenessResolver.rumorDelayTicks(record.id(), TestFixtures.VILLAGER_2,
                    record.community(), MIN_DELAY, MAX_DELAY));
        }
    }

    @Test
    void theDelayStaysInsideTheConfiguredWindow() {
        IncidentRecord record = witnessed(0L);
        for (int i = 0; i < 2000; i++) {
            UUID villager = new UUID(i, i * 31L);
            long delay = AwarenessResolver.rumorDelayTicks(record.id(), villager, record.community(),
                    MIN_DELAY, MAX_DELAY);
            assertTrue(delay >= MIN_DELAY && delay <= MAX_DELAY, "delay out of window: " + delay);
        }
    }

    /**
     * Adjacent village ids must not cluster onto the same delay — the reason the hash uses a proper
     * finalizer rather than {@code Objects.hash}.
     */
    @Test
    void adjacentCommunitiesGetUnrelatedDelays() {
        IncidentRecord record = witnessed(0L);
        Set<Long> delays = new HashSet<>();
        for (int villageId = 0; villageId < 64; villageId++) {
            delays.add(AwarenessResolver.rumorDelayTicks(record.id(), TestFixtures.VILLAGER_2,
                    new dev.otectus.mcareputation.community.CommunityKey(
                            net.minecraft.resources.ResourceLocation.parse("minecraft:overworld"), villageId),
                    MIN_DELAY, MAX_DELAY));
        }
        assertTrue(delays.size() > 50, "expected well-spread delays, got " + delays.size() + " distinct");
    }

    @Test
    void differentVillagersGetDifferentDelays() {
        IncidentRecord record = witnessed(0L);
        Set<Long> delays = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            delays.add(AwarenessResolver.rumorDelayTicks(record.id(), new UUID(i, ~i),
                    record.community(), MIN_DELAY, MAX_DELAY));
        }
        assertTrue(delays.size() > 400, "expected mostly distinct delays, got " + delays.size());
    }

    /** Once true, always true — knowledge cannot be un-learned by a clock rewind (§19.3). */
    @Test
    void learningIsMonotonic() {
        IncidentRecord record = witnessed(0L, TestFixtures.VILLAGER_1);
        long delay = AwarenessResolver.rumorDelayTicks(record.id(), TestFixtures.VILLAGER_2,
                record.community(), MIN_DELAY, MAX_DELAY);
        record.reconcile(DecayPolicy.NONE, delay + 100);
        assertTrue(AwarenessResolver.knows(record, TestFixtures.VILLAGER_2, true, delay + 100,
                MIN_DELAY, MAX_DELAY));
        // Wind the clock back below the delay: the incident's own age counter has already advanced.
        assertTrue(AwarenessResolver.knows(record, TestFixtures.VILLAGER_2, true, 0L,
                MIN_DELAY, MAX_DELAY), "a rewound clock must not un-tell a rumour");
    }

    @Test
    void degenerateWindowsDoNotThrow() {
        IncidentRecord record = witnessed(0L);
        assertEquals(0L, AwarenessResolver.rumorDelayTicks(record.id(), TestFixtures.VILLAGER_2,
                record.community(), 0, 0));
        // Inverted bounds: normalised rather than producing a negative range.
        long delay = AwarenessResolver.rumorDelayTicks(record.id(), TestFixtures.VILLAGER_2,
                record.community(), 5000, 100);
        assertTrue(delay >= 100 && delay <= 5000);
    }

    @Test
    void nullsAreSafe() {
        assertFalse(AwarenessResolver.knows(null, TestFixtures.VILLAGER_1, true, 0L, MIN_DELAY, MAX_DELAY));
        assertFalse(AwarenessResolver.knows(witnessed(0L), null, true, 0L, MIN_DELAY, MAX_DELAY));
    }

    @Test
    void canTellRequiresAGossipPhraseAndRespectsMaxAge() {
        IncidentRecord record = witnessed(0L, TestFixtures.VILLAGER_1);
        IncidentDefinition silent = TestFixtures.definition(-8, IncidentVisibility.WITNESSED,
                DecayPolicy.NONE);
        assertFalse(AwarenessResolver.canTell(record, silent, TestFixtures.VILLAGER_1, true, 0L, 0L,
                MIN_DELAY, MAX_DELAY), "no gossip phrase means nothing to say");

        IncidentDefinition tellable = new IncidentDefinition(silent.display(), silent.defaultDelta(),
                silent.visibility(), silent.severity(), silent.tags(), silent.retentionTicks(),
                silent.decay(), silent.resolution(),
                new GossipSpec(java.util.Optional.of("condemnation"),
                        java.util.Optional.of("mcareputation.gossip.villager_assaulted"), List.of()),
                silent.pinned(), silent.maxOverrideAbs(), silent.retainUnwitnessed(),
                silent.allowPrivateScore());
        assertTrue(AwarenessResolver.canTell(record, tellable, TestFixtures.VILLAGER_1, true, 0L, 0L,
                MIN_DELAY, MAX_DELAY));

        record.reconcile(DecayPolicy.NONE, 100_000L);
        assertFalse(AwarenessResolver.canTell(record, tellable, TestFixtures.VILLAGER_1, true, 100_000L,
                1000L, MIN_DELAY, MAX_DELAY), "older than the caller's window");
    }
}
