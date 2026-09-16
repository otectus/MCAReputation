package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityMetadata;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.incident.ResolutionPolicy;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golden format-2 file: proof that two loaders write the same bytes for the same ledger.
 *
 * <p>The ledger below is built entirely from loader-neutral state — no server, no level, no registry —
 * because the NeoForge port copies this exact fixture and has to reproduce it byte for byte. The file
 * is stored <b>uncompressed</b> so the comparison is over NBT and nothing else; a gzip container would
 * put the encoder's own choices between the two trees.
 *
 * <p>Regenerating it is deliberate and gated; see {@code src/test/resources/fixtures/README.md}.
 */
class GoldenSavedDataTest {

    private static final String FIXTURE_NAME = "mcareputation-format-2-1.20.1.nbt";
    private static final String FIXTURE = "/fixtures/" + FIXTURE_NAME;
    private static final String REGENERATE = "mcareputation.regenerateFixtures";

    private static final int MIN = -1000;
    private static final int MAX = 1000;

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID ANNA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BRAM = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final CommunityKey RIVERBEND =
            new CommunityKey(new ResourceLocation("minecraft", "overworld"), 3);
    private static final CommunityKey ASHFALL =
            new CommunityKey(new ResourceLocation("minecraft", "the_nether"), 3);
    private static final CommunityKey STONEBROOK =
            new CommunityKey(new ResourceLocation("minecraft", "overworld"), 7);

    private static final ResourceLocation ASSAULT =
            new ResourceLocation("mcareputation", "villager_assaulted");
    private static final ResourceLocation KILLING =
            new ResourceLocation("mcareputation", "villager_killed");
    private static final ResourceLocation RESCUE =
            new ResourceLocation("mcareputation", "villager_rescued");
    private static final ResourceLocation CORE = new ResourceLocation("mcareputation", "core");
    private static final ResourceLocation QUESTS = new ResourceLocation("mcaquests", "reward");

    private static final UUID ASSAULT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KILLING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RESCUE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID THEFT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    /**
     * The ledger the fixture is made of: two players, three communities across two dimensions, keyed
     * incidents with their receipts, one superseded pair, one immune community, titles and a
     * high-water mark.
     */
    private static ReputationSavedData ledger() {
        ReputationSavedData data = ReputationSavedData.createForTest();

        // --- Ada, in Riverbend -------------------------------------------------
        PlayerReputationRecord ada = data.getOrCreatePlayer(ADA);
        ada.setLastKnownName("Ada");
        CommunityReputationRecord riverbend = ada.getOrCreate(RIVERBEND);
        riverbend.setMetadata(new CommunityMetadata("Riverbend",
                Optional.of(new BlockPos(10, 64, -20)), 500L));
        riverbend.addBaseline(25, MIN, MAX);

        IncidentRecord assault = IncidentRecord.create(ASSAULT_ID, ASSAULT, ADA, RIVERBEND, 100L,
                CORE, Optional.of("core:assault-1"), -20, IncidentVisibility.WITNESSED,
                IncidentSeverity.MODERATE, List.of(IncidentSubject.villager(ANNA, "Anna", "victim")));
        assault.addWitnesses(List.of(ANNA, BRAM));
        assault.putContext("damage", "6");
        riverbend.addIncident(assault);

        // The pair: the assault above was absorbed by the killing that followed it.
        IncidentRecord killing = IncidentRecord.create(KILLING_ID, KILLING, ADA, RIVERBEND, 300L,
                CORE, Optional.of("core:kill-1"), -60, IncidentVisibility.VILLAGE,
                IncidentSeverity.SEVERE, List.of(IncidentSubject.villager(ANNA, "Anna", "victim")));
        killing.addWitnesses(List.of(BRAM));
        riverbend.addIncident(killing);
        assault.foldInto(killing.id(), 300L);

        // A resolved deed, so a story revision and a settled delta are both on disk.
        IncidentRecord rescue = IncidentRecord.create(RESCUE_ID, RESCUE, ADA, RIVERBEND, 400L,
                QUESTS, Optional.of("quests:rescue-1"), 40, IncidentVisibility.VILLAGE,
                IncidentSeverity.MINOR, List.of(IncidentSubject.villager(BRAM, "Bram", "beneficiary")));
        riverbend.addIncident(rescue);
        rescue.resolve(ResolutionPolicy.DEFAULT, null, IncidentStatus.ATONED, 500L);

        riverbend.recomputeScore(MIN, MAX);
        riverbend.setTierHighWater(ReputationTiers.DEFAULT_ID, "acquaintance");
        riverbend.grantTitle(new ResourceLocation("mcaquests", "honored_of_village"));
        ada.grantGlobalTitle(new ResourceLocation("mcareputation", "wanderer"));
        ada.bumpTitleRevision();
        ada.bumpTitleRevision();
        ada.markMigrated("mcaquests:legacy_reputation_v1", "1");

        ada.recordReceipt(new OperationReceipt("mcaquests", ADA, RIVERBEND, "quests:rescue-1",
                ReceiptOutcome.APPLIED, Optional.of(rescue.id()), 400L, 400L));
        ada.recordReceipt(new OperationReceipt("mcacrime", ADA, RIVERBEND, "crime:fine-7",
                ReceiptOutcome.ACCEPTED_NO_PUBLIC_INCIDENT, Optional.empty(), 450L, 460L));

        // --- Ada, in the Nether, and in the immune village ---------------------
        CommunityReputationRecord ashfall = ada.getOrCreate(ASHFALL);
        ashfall.setMetadata(new CommunityMetadata("Ashfall", Optional.empty(), 900L));
        ashfall.addBaseline(60, MIN, MAX);
        ashfall.recomputeScore(MIN, MAX);

        CommunityReputationRecord stonebrook = ada.getOrCreate(STONEBROOK);
        stonebrook.setMetadata(new CommunityMetadata("Stonebrook",
                Optional.of(new BlockPos(-300, 70, 512)), 950L));
        stonebrook.addBaseline(15, MIN, MAX);
        stonebrook.recomputeScore(MIN, MAX);
        data.setDecayImmune(STONEBROOK, true);

        // --- Bo, in the same village as Ada ------------------------------------
        PlayerReputationRecord bo = data.getOrCreatePlayer(BO);
        bo.setLastKnownName("Bo");
        CommunityReputationRecord bosRiverbend = bo.getOrCreate(RIVERBEND);
        IncidentRecord theft = IncidentRecord.create(THEFT_ID, ASSAULT, BO, RIVERBEND, 200L,
                QUESTS, Optional.of("quests:theft-1"), -80, IncidentVisibility.VILLAGE,
                IncidentSeverity.MAJOR, List.of());
        bosRiverbend.addIncident(theft);
        bosRiverbend.recomputeScore(MIN, MAX);
        bo.recordReceipt(new OperationReceipt("mcaquests", BO, RIVERBEND, "quests:theft-1",
                ReceiptOutcome.APPLIED, Optional.of(theft.id()), 200L, 200L));

        return data;
    }

    // --- fixture plumbing ---------------------------------------------------

    private static byte[] encode(CompoundTag tag) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            NbtIo.write(tag, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] fixtureBytes() {
        try (InputStream in = GoldenSavedDataTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "missing test fixture " + FIXTURE
                    + "; regenerate it with -D" + REGENERATE + "=true");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CompoundTag fixtureTag() {
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(fixtureBytes()))) {
            return NbtIo.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Rewrites the fixture. Gated, and skipped on every ordinary run: a golden file that regenerates
     * itself when it disagrees proves nothing at all.
     */
    @Test
    void regenerateTheFixture() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(REGENERATE), "fixture regeneration is not requested");
        Path target = Path.of("src", "test", "resources", "fixtures", FIXTURE_NAME);
        Files.createDirectories(target.getParent());
        Files.write(target, encode(ledger().save(new CompoundTag())));
    }

    // --- the assertions -----------------------------------------------------

    @Test
    void theFixtureIsAFormatTwoFile() {
        assertEquals(2, fixtureTag().getInt("version"));
    }

    @Test
    void theCurrentSerializerReproducesTheFixtureByteForByte() {
        assertArrayEquals(fixtureBytes(), encode(ledger().save(new CompoundTag())),
                "the v2 serializer no longer writes the ledger it wrote when this fixture was taken");
    }

    @Test
    void theFixtureLoadsWithTheExpectedTotals() {
        ReputationSavedData loaded = ReputationSavedData.load(fixtureTag());

        assertEquals(2, loaded.playerCount());
        assertEquals(ReputationSavedData.FORMAT_VERSION, loaded.loadedVersion());
        // Riverbend: baseline 25, the folded assault contributes nothing, the killing -60, the
        // atoned rescue what the default resolution policy left of +40.
        assertEquals(-25, loaded.score(ADA, RIVERBEND));
        assertEquals(60, loaded.score(ADA, ASHFALL));
        assertEquals(15, loaded.score(ADA, STONEBROOK));
        assertEquals(-80, loaded.score(BO, RIVERBEND));
        assertTrue(loaded.isDecayImmune(STONEBROOK));

        PlayerReputationRecord ada = loaded.player(ADA).orElseThrow();
        assertEquals(2, ada.receiptCount());
        assertEquals(2L, ada.titleRevision());
        assertTrue(ada.hasMigrated("mcaquests:legacy_reputation_v1"));

        CommunityReputationRecord riverbend = ada.community(RIVERBEND).orElseThrow();
        assertEquals(Optional.of("acquaintance"), riverbend.tierHighWater(ReputationTiers.DEFAULT_ID));
        IncidentRecord folded = riverbend.incident(ASSAULT_ID).orElseThrow();
        assertTrue(folded.isSuperseded());
        assertEquals(Optional.of(KILLING_ID), folded.supersededBy());
        assertEquals(1L, folded.storyRevision());
        assertEquals(1L, riverbend.incident(RESCUE_ID).orElseThrow().storyRevision());
    }

    @Test
    void theFixtureRoundTripsUnchanged() {
        assertArrayEquals(fixtureBytes(),
                encode(ReputationSavedData.load(fixtureTag()).save(new CompoundTag())),
                "loading and re-saving the golden file must not move a byte");
    }
}
