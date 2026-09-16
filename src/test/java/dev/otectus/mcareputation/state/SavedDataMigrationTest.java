package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v1 to v2 migration (§9, D2), run against a real format-1 file written by the 0.4.x serializer.
 *
 * <p>The fixture is evidence, not a convenience: it is the only thing in the test tree that was not
 * produced by the code under test, so it is the only thing that can prove an upgrade does not quietly
 * rewrite what a player already had.
 */
class SavedDataMigrationTest {

    private static final String FIXTURE = "/fixtures/mcareputation-format-1-1.20.1.nbt";
    private static final int MIN = -1000;
    private static final int MAX = 1000;
    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final CommunityKey RIVERBEND =
            new CommunityKey(new ResourceLocation("minecraft", "overworld"), 3);
    private static final CommunityKey ASHFALL =
            new CommunityKey(new ResourceLocation("minecraft", "the_nether"), 3);

    private static CompoundTag fixtureTag() {
        try (InputStream in = SavedDataMigrationTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "missing test fixture " + FIXTURE);
            return NbtIo.readCompressed(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<IncidentRecord> allIncidents(ReputationSavedData data) {
        List<IncidentRecord> out = new ArrayList<>();
        data.players().forEach(player ->
                player.communities().forEach(community -> out.addAll(community.incidents())));
        return out;
    }

    @Test
    void theFixtureIsAFormatOneFile() {
        assertEquals(1, fixtureTag().getInt("version"));
    }

    /** Nothing a player could see may move: totals, identities and baselines are carried across. */
    @Test
    void migrationChangesNoTotalsIdentitiesOrBaselines() {
        ReputationSavedData data = ReputationSavedData.load(fixtureTag());

        assertEquals(2, data.playerCount());
        assertEquals(-70, data.score(ADA, RIVERBEND));
        assertEquals(60, data.score(ADA, ASHFALL));
        assertEquals(-80, data.score(BO, RIVERBEND));

        PlayerReputationRecord ada = data.player(ADA).orElseThrow();
        CommunityReputationRecord home = ada.community(RIVERBEND).orElseThrow();
        assertEquals(25, home.baseline(), "an imported balance is a baseline and stays one");
        assertEquals(6, home.incidentCount(), "every incident on disk is still here");
        assertEquals("Riverbend", home.metadata().name());
        assertTrue(ada.hasMigrated("mcaquests:legacy_reputation_v1"),
                "a legacy-import marker survives the format change");
    }

    /** §9: every retained incident with a dedupe key gets a receipt that returns its own id. */
    @Test
    void everyKeyedIncidentGainsAnAppliedReceipt() {
        ReputationSavedData data = ReputationSavedData.load(fixtureTag());
        PlayerReputationRecord ada = data.player(ADA).orElseThrow();

        int keyed = 0;
        for (CommunityReputationRecord community : ada.communities()) {
            for (IncidentRecord incident : community.incidents()) {
                Optional<String> key = incident.dedupeKey();
                if (key.isEmpty()) {
                    continue;
                }
                keyed++;
                OperationReceipt receipt = ada.findReceipt(incident.source().getNamespace(),
                        community.key(), key.get()).orElseThrow(() -> new AssertionError(
                        "no receipt recovered for dedupe key " + key.get()));
                assertEquals(ReceiptOutcome.APPLIED, receipt.outcome());
                assertEquals(Optional.of(incident.id()), receipt.incidentId(),
                        "the receipt has to return the incident it was recovered from");
                assertEquals(incident.createdGameTime(), receipt.occurredGameTime());
                assertEquals(incident.appliedGameTime(), receipt.recordedGameTime());
            }
        }
        assertTrue(keyed > 0, "the fixture is supposed to carry dedupe keys");
        assertEquals(keyed, ada.receiptCount(), "one receipt per keyed incident, and no others");
    }

    /** §5 F08: a record that carried only the legacy context entry becomes terminal. */
    @Test
    void legacySupersededByRecordsBecomeTerminal() {
        ReputationSavedData data = ReputationSavedData.load(fixtureTag());

        int terminal = 0;
        for (IncidentRecord incident : allIncidents(data)) {
            if (incident.context(BuiltinIncidents.CONTEXT_SUPERSEDED_BY).isPresent()) {
                terminal++;
                assertTrue(incident.isSuperseded(), "a folded record must read as terminal after upgrade");
                assertFalse(incident.contributes());
                assertTrue(incident.supersededBy().isPresent(), "and the link must be typed");
            }
        }
        assertTrue(terminal > 0, "the fixture is supposed to carry a folded record");
    }

    /** A hand-built v1 record whose successor was itself pruned away: tolerated, still terminal. */
    @Test
    void aMissingSuccessorIsToleratedAndTheRecordIsStillTerminal() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        PlayerReputationRecord player = data.getOrCreatePlayer(ADA);
        IncidentRecord folded = IncidentRecord.create(UUID.randomUUID(),
                new ResourceLocation("mcareputation", "villager_assaulted"), ADA, RIVERBEND, 0L,
                new ResourceLocation("mcareputation", "core"), Optional.empty(), 0,
                IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE, List.of());
        UUID successor = UUID.randomUUID();
        folded.putContext(BuiltinIncidents.CONTEXT_SUPERSEDED_BY, successor.toString());
        player.getOrCreate(RIVERBEND).addIncident(folded);

        CompoundTag tag = data.save(new CompoundTag());
        tag.putInt("version", 1); // pretend the file predates the flag

        ReputationSavedData loaded = ReputationSavedData.load(tag);
        IncidentRecord reloaded = loaded.player(ADA).orElseThrow().community(RIVERBEND).orElseThrow()
                .incidents().iterator().next();
        assertTrue(reloaded.isSuperseded());
        assertEquals(Optional.of(successor), reloaded.supersededBy());
    }

    /** An unparseable link must not fail the record; only the identity is lost. */
    @Test
    void anUnparseableSuccessorLinkStillMarksTheRecordTerminal() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        PlayerReputationRecord player = data.getOrCreatePlayer(ADA);
        IncidentRecord folded = IncidentRecord.create(UUID.randomUUID(),
                new ResourceLocation("mcareputation", "villager_assaulted"), ADA, RIVERBEND, 0L,
                new ResourceLocation("mcareputation", "core"), Optional.empty(), 0,
                IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE, List.of());
        folded.putContext(BuiltinIncidents.CONTEXT_SUPERSEDED_BY, "not-a-uuid");
        player.getOrCreate(RIVERBEND).addIncident(folded);

        CompoundTag tag = data.save(new CompoundTag());
        tag.putInt("version", 1);

        IncidentRecord reloaded = ReputationSavedData.load(tag).player(ADA).orElseThrow()
                .community(RIVERBEND).orElseThrow().incidents().iterator().next();
        assertTrue(reloaded.isSuperseded());
        assertTrue(reloaded.supersededBy().isEmpty());
    }

    /** T37: migrating twice duplicates nothing and loses nothing. */
    @Test
    void migratingTwiceIsANoOp() {
        ReputationSavedData once = ReputationSavedData.load(fixtureTag());
        Map<String, Integer> scoresOnce = once.player(ADA).orElseThrow().scoreByCommunity();
        int receiptsOnce = once.player(ADA).orElseThrow().receiptCount();

        // The second run is the upgraded file being loaded again, which is what a restart does.
        ReputationSavedData twice = ReputationSavedData.load(once.save(new CompoundTag()));

        assertEquals(ReputationSavedData.FORMAT_VERSION, twice.loadedVersion());
        assertEquals(scoresOnce, twice.player(ADA).orElseThrow().scoreByCommunity());
        assertEquals(receiptsOnce, twice.player(ADA).orElseThrow().receiptCount(),
                "no receipt was invented a second time");
        assertEquals(allIncidents(once).size(), allIncidents(twice).size());
        assertTrue(twice.player(ADA).orElseThrow().hasMigrated("mcaquests:legacy_reputation_v1"));
    }

    /** §9: already-pruned history invents nothing. */
    @Test
    void anEmptyLedgerGainsNoReceipts() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        data.getOrCreatePlayer(ADA).getOrCreate(RIVERBEND).addBaseline(120, MIN, MAX);
        CompoundTag tag = data.save(new CompoundTag());
        tag.putInt("version", 1);

        ReputationSavedData loaded = ReputationSavedData.load(tag);
        assertEquals(0, loaded.player(ADA).orElseThrow().receiptCount());
        assertEquals(120, loaded.score(ADA, RIVERBEND));
    }

    /**
     * The upgrade marks the store dirty so the next autosave writes v2, and it emits nothing at all:
     * {@code migrateFormat} runs inside {@code load}, which has no service context, no event bus, no
     * mirrors and no player to notify.
     */
    @Test
    void migrationMarksTheStoreDirty() {
        assertTrue(ReputationSavedData.load(fixtureTag()).isDirty());
    }

    /** A save/load round trip after the upgrade is a v2 file with the same totals. */
    @Test
    void theUpgradedStoreRoundTripsAsVersionTwo() {
        ReputationSavedData migrated = ReputationSavedData.load(fixtureTag());
        CompoundTag written = migrated.save(new CompoundTag());
        assertEquals(2, written.getInt("version"));

        ReputationSavedData reloaded = ReputationSavedData.load(written);
        assertEquals(-70, reloaded.score(ADA, RIVERBEND));
        assertEquals(60, reloaded.score(ADA, ASHFALL));
        assertEquals(-80, reloaded.score(BO, RIVERBEND));
        assertEquals(migrated.player(ADA).orElseThrow().receiptCount(),
                reloaded.player(ADA).orElseThrow().receiptCount());
    }
}
