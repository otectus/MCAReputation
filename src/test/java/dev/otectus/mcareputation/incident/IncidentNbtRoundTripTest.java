package dev.otectus.mcareputation.incident;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §36.1 group 4. */
class IncidentNbtRoundTripTest {

    private static IncidentRecord fullyPopulated() {
        IncidentRecord record = IncidentRecord.create(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                TestFixtures.ASSAULT, TestFixtures.PLAYER_A, TestFixtures.NETHER_3, 1234L,
                TestFixtures.SOURCE, Optional.of("assault:a:b:7"), -8,
                IncidentVisibility.WITNESSED, IncidentSeverity.MAJOR,
                List.of(IncidentSubject.villager(TestFixtures.VILLAGER_1, "Anna", "victim"),
                        IncidentSubject.player(TestFixtures.PLAYER_B, "Bo", "witness")));
        record.addWitnesses(List.of(TestFixtures.VILLAGER_1, TestFixtures.VILLAGER_2));
        record.putContext("damage", "12.5");
        record.putContext("hits", "3");
        record.setPinned(true);
        record.reconcile(DecayPolicy.linearToZero(0, 2), 24000L);
        return record;
    }

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        IncidentRecord original = fullyPopulated();
        IncidentRecord loaded = IncidentRecord.load(original.save()).orElseThrow();

        assertEquals(original.id(), loaded.id());
        assertEquals(original.type(), loaded.type());
        assertEquals(original.player(), loaded.player());
        assertEquals(original.community(), loaded.community());
        assertEquals(original.createdGameTime(), loaded.createdGameTime());
        assertEquals(original.updatedGameTime(), loaded.updatedGameTime());
        assertEquals(original.source(), loaded.source());
        assertEquals(original.dedupeKey(), loaded.dedupeKey());
        assertEquals(original.baseDelta(), loaded.baseDelta());
        assertEquals(original.settledDelta(), loaded.settledDelta());
        assertEquals(original.currentContribution(), loaded.currentContribution());
        assertEquals(original.visibility(), loaded.visibility());
        assertEquals(original.severity(), loaded.severity());
        assertEquals(original.status(), loaded.status());
        assertEquals(original.pinned(), loaded.pinned());
        assertEquals(original.decayElapsedTicks(), loaded.decayElapsedTicks());
        assertEquals(original.witnesses(), loaded.witnesses());
        assertEquals(original.context(), loaded.context());
        assertEquals(original.subjects().size(), loaded.subjects().size());
        assertEquals("Anna", loaded.subjects().get(0).displayName());
        assertEquals(Optional.of("victim"), loaded.subjects().get(0).role());
        assertEquals(SubjectKind.PLAYER, loaded.subjects().get(1).kind());
    }

    /**
     * The reason decay must not restart on load: a reloaded record continues from the elapsed time it
     * had, not from zero.
     */
    @Test
    void decayContinuesAcrossASaveLoadCycle() {
        IncidentRecord original = TestFixtures.record(-8, IncidentVisibility.VILLAGE, 0L);
        DecayPolicy policy = DecayPolicy.linearToZero(0, 2);
        original.reconcile(policy, 2 * DecayPolicy.TICKS_PER_DAY);
        assertEquals(-4, original.currentContribution());

        IncidentRecord loaded = IncidentRecord.load(original.save()).orElseThrow();
        assertEquals(-4, loaded.currentContribution());
        loaded.reconcile(policy, 3 * DecayPolicy.TICKS_PER_DAY);
        assertEquals(-2, loaded.currentContribution(), "one more day, one more step — not a restart");
    }

    @Test
    void missingIdentityMakesTheEntryUnusable() {
        assertTrue(IncidentRecord.load(null).isEmpty());
        assertTrue(IncidentRecord.load(new CompoundTag()).isEmpty());

        CompoundTag noType = fullyPopulated().save();
        noType.putString("type", "NOT A RESOURCE LOCATION");
        assertTrue(IncidentRecord.load(noType).isEmpty());

        CompoundTag noCommunity = fullyPopulated().save();
        noCommunity.put("community", new CompoundTag());
        assertTrue(IncidentRecord.load(noCommunity).isEmpty());
    }

    /** §13.6: a soft field that is malformed degrades; it does not cost us the whole record. */
    @Test
    void unknownEnumStringsFallBackSafely() {
        CompoundTag tag = fullyPopulated().save();
        tag.putString("status", "vaporised");
        tag.putString("severity", "apocalyptic");
        tag.putString("visibility", "telepathic");

        IncidentRecord loaded = IncidentRecord.load(tag).orElseThrow();
        assertEquals(IncidentStatus.ACTIVE, loaded.status());
        assertEquals(IncidentSeverity.MINOR, loaded.severity());
        assertEquals(IncidentVisibility.PRIVATE, loaded.visibility(),
                "visibility fails closed: a corrupt entry must not accidentally publish a deed");
    }

    @Test
    void oneMalformedWitnessDoesNotCostTheIncident() {
        CompoundTag tag = fullyPopulated().save();
        ListTag witnesses = new ListTag();
        witnesses.add(StringTag.valueOf("not a uuid"));
        tag.put("witnesses", witnesses);
        // A list of the wrong element type reads as empty rather than throwing.
        IncidentRecord loaded = IncidentRecord.load(tag).orElseThrow();
        assertTrue(loaded.witnesses().isEmpty());
        assertEquals(-8, loaded.baseDelta(), "the rest of the record is intact");
    }

    @Test
    void legacyTagsWithoutTheNewerFieldsStillLoad() {
        // Simulates a record written by a build that predates settled/current/reconciled.
        CompoundTag tag = fullyPopulated().save();
        tag.remove("settled");
        tag.remove("current");
        tag.remove("reconciled");
        IncidentRecord loaded = IncidentRecord.load(tag).orElseThrow();
        assertEquals(loaded.baseDelta(), loaded.settledDelta());
        assertEquals(loaded.baseDelta(), loaded.currentContribution());
    }

    // ------------------------------------------------------------------
    // Bounds
    // ------------------------------------------------------------------

    @Test
    void witnessesAreCapped() {
        IncidentRecord record = TestFixtures.record(-8);
        List<UUID> many = new java.util.ArrayList<>();
        for (int i = 0; i < ReputationBounds.MAX_WITNESSES * 3; i++) {
            many.add(new UUID(i, i));
        }
        record.addWitnesses(many);
        assertEquals(ReputationBounds.MAX_WITNESSES, record.witnesses().size());
    }

    @Test
    void subjectsAreCapped() {
        List<IncidentSubject> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(IncidentSubject.villager(new UUID(i, i), "V" + i, "witness"));
        }
        IncidentRecord record = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 0L, TestFixtures.SOURCE,
                Optional.empty(), 0, IncidentVisibility.VILLAGE, IncidentSeverity.MINOR, many);
        assertEquals(ReputationBounds.MAX_SUBJECTS, record.subjects().size());
    }

    @Test
    void contextIsCappedInCountKeyLengthAndValueLength() {
        IncidentRecord record = TestFixtures.record(-8);
        for (int i = 0; i < ReputationBounds.MAX_CONTEXT_KEYS * 2; i++) {
            record.putContext("key" + i, "value");
        }
        assertEquals(ReputationBounds.MAX_CONTEXT_KEYS, record.context().size());

        IncidentRecord other = TestFixtures.record(-8);
        String longKey = "k".repeat(ReputationBounds.MAX_CONTEXT_KEY_LENGTH * 2);
        String longValue = "v".repeat(ReputationBounds.MAX_CONTEXT_VALUE_LENGTH * 2);
        other.putContext(longKey, longValue);
        String storedKey = other.context().keySet().iterator().next();
        assertEquals(ReputationBounds.MAX_CONTEXT_KEY_LENGTH, storedKey.length());
        assertEquals(ReputationBounds.MAX_CONTEXT_VALUE_LENGTH, other.context().get(storedKey).length());
    }

    @Test
    void existingContextKeysMayAlwaysBeOverwrittenEvenAtTheCap() {
        IncidentRecord record = TestFixtures.record(-8);
        for (int i = 0; i < ReputationBounds.MAX_CONTEXT_KEYS; i++) {
            record.putContext("key" + i, "1");
        }
        record.putContext("key0", "99");
        assertEquals(Optional.of("99"), record.context("key0"),
                "an assault's accumulated damage must stay updatable in a full context map");
    }

    @Test
    void dedupeKeysAreBounded() {
        String huge = "k".repeat(ReputationBounds.MAX_DEDUPE_KEY_LENGTH * 3);
        IncidentRecord record = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 0L, TestFixtures.SOURCE,
                Optional.of(huge), 0, IncidentVisibility.VILLAGE, IncidentSeverity.MINOR, List.of());
        assertEquals(ReputationBounds.MAX_DEDUPE_KEY_LENGTH, record.dedupeKey().orElseThrow().length());
    }

    @Test
    void blankDedupeKeysAreTreatedAsAbsent() {
        IncidentRecord record = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 0L, TestFixtures.SOURCE,
                Optional.of("   "), 0, IncidentVisibility.VILLAGE, IncidentSeverity.MINOR, List.of());
        assertFalse(record.dedupeKey().isPresent());
    }

    /**
     * The load path enforces the same dedupe bounds as {@code create}: a hand-edited save cannot
     * smuggle in an oversized key, and a blank one reads as "no key" — a blank key could never match
     * a lookup and would only pretend to protect against replays.
     */
    @Test
    void loadBoundsDedupeKeysLikeCreateDoes() {
        IncidentRecord record = TestFixtures.record(-8, IncidentVisibility.VILLAGE, 0L);
        var tag = record.save();
        tag.putString("dedupe", "k".repeat(ReputationBounds.MAX_DEDUPE_KEY_LENGTH * 3));
        IncidentRecord oversized = IncidentRecord.load(tag).orElseThrow();
        assertEquals(ReputationBounds.MAX_DEDUPE_KEY_LENGTH,
                oversized.dedupeKey().orElseThrow().length());

        tag.putString("dedupe", "   ");
        assertFalse(IncidentRecord.load(tag).orElseThrow().dedupeKey().isPresent());
    }
}
