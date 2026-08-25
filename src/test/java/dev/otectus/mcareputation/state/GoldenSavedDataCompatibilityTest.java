package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.TestPaths;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural invariant 2: a reputation save written by the Forge 1.20.1 build must load under
 * NeoForge 1.21.1 without resetting anything.
 *
 * <p>The fixture was produced by the <em>unmodified</em> 1.20.1 serializer before this port touched
 * {@code ReputationSavedData}, and is checked in with its provenance and SHA-256 (see
 * {@code src/test/resources/fixtures/README.md}). That is the whole point: it is evidence, not
 * output. <b>Never regenerate it with the current serializer</b> — doing so would make this test
 * assert only that the code agrees with itself.
 *
 * <p>Everything goes through {@code loadPayload}/{@code savePayload}, the provider-neutral pair the
 * 1.21.1 lookup-aware {@code SavedData} methods delegate to. The registry provider is an adapter
 * parameter; this schema stores primitives, strings, UUIDs and resource-location text, so a fixture
 * written without one reads back unchanged.
 */
class GoldenSavedDataCompatibilityTest {

    private static final Path FIXTURE = TestPaths.testResources()
            .resolve("fixtures/mcareputation-format-1-1.20.1.nbt");

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID VILLAGER_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID VILLAGER_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final UUID INC_ACTIVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INC_RESOLVED = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INC_EXPIRED = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID INC_HIDDEN = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID INC_FOLDED = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID INC_SUCCESSOR = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static final CommunityKey OVERWORLD_3 = new CommunityKey(
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 3);
    private static final CommunityKey NETHER_3 = new CommunityKey(
            ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"), 3);

    /** The fixture is gzip-compressed, as {@code NbtIo.writeCompressed} left it. */
    private static CompoundTag readFixture() throws IOException {
        assertTrue(Files.isRegularFile(FIXTURE),
                () -> "the golden 1.20.1 fixture is missing from " + FIXTURE.toAbsolutePath());
        try (InputStream in = Files.newInputStream(FIXTURE)) {
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
    }

    private static ReputationSavedData loadGolden() throws IOException {
        return ReputationSavedData.loadPayload(readFixture());
    }

    // ------------------------------------------------------------------
    // The format itself
    // ------------------------------------------------------------------

    @Test
    void theFixtureStillDeclaresFormatVersionOne() throws IOException {
        assertEquals(1, readFixture().getInt("version"));
        assertEquals(1, ReputationSavedData.FORMAT_VERSION,
                "the loader port changed the API, not the schema");
        assertEquals("mcareputation", ReputationSavedData.DATA_NAME,
                "the data file name is part of the save's identity and must not move");
    }

    @Test
    void loadingReportsTheStoredVersionRatherThanAssumingIt() throws IOException {
        assertEquals(1, loadGolden().loadedVersion());
    }

    // ------------------------------------------------------------------
    // Scores, baselines and identity
    // ------------------------------------------------------------------

    @Test
    void everyPlayerAndScoreSurvives() throws IOException {
        ReputationSavedData data = loadGolden();
        assertEquals(2, data.playerCount());
        assertEquals(-70, data.score(ADA, OVERWORLD_3));
        assertEquals(60, data.score(ADA, NETHER_3));
        assertEquals(-80, data.score(BO, OVERWORLD_3));
        assertEquals("Ada", data.player(ADA).orElseThrow().lastKnownName());
        assertEquals("Bo", data.player(BO).orElseThrow().lastKnownName());
    }

    /** Invariant 3: the same numeric village id in two dimensions stays two communities. */
    @Test
    void communityIdentityRemainsDimensionAware() throws IOException {
        PlayerReputationRecord ada = loadGolden().player(ADA).orElseThrow();
        assertEquals(2, ada.communityKeys().size());
        assertTrue(ada.communityKeys().contains(OVERWORLD_3));
        assertTrue(ada.communityKeys().contains(NETHER_3));
        assertEquals(3, OVERWORLD_3.villageId());
        assertEquals(3, NETHER_3.villageId());
        assertEquals(25, ada.community(OVERWORLD_3).orElseThrow().baseline());
        assertEquals(60, ada.community(NETHER_3).orElseThrow().baseline());
    }

    @Test
    void twoPlayersInOneVillageStayIndependent() throws IOException {
        ReputationSavedData data = loadGolden();
        assertEquals(-70, data.score(ADA, OVERWORLD_3));
        assertEquals(-80, data.score(BO, OVERWORLD_3));
    }

    // ------------------------------------------------------------------
    // Incidents: every status and shape
    // ------------------------------------------------------------------

    @Test
    void everyIncidentStatusAndShapeSurvives() throws IOException {
        CommunityReputationRecord home = loadGolden().player(ADA).orElseThrow()
                .community(OVERWORLD_3).orElseThrow();
        assertEquals(6, home.incidentCount());

        IncidentRecord active = home.incident(INC_ACTIVE).orElseThrow();
        assertEquals(IncidentStatus.ACTIVE, active.status());
        assertEquals(IncidentVisibility.VILLAGE, active.visibility());
        assertTrue(active.pinned(), "the pinned flag is part of the record, not a UI decoration");
        assertEquals(2, active.witnesses().size());
        assertTrue(active.isWitness(VILLAGER_1));
        assertTrue(active.isWitness(VILLAGER_2));
        assertEquals(Optional.of("iron_sword"), active.context("weapon"));
        assertEquals(Optional.of("struck_first"), active.context("decision"));
        assertEquals(Optional.of("dedupe-active"), active.dedupeKey());
        assertEquals(1, active.subjects().size());
        assertEquals(VILLAGER_1, active.subjects().get(0).uuid().orElseThrow());

        assertEquals(IncidentStatus.ATONED, home.incident(INC_RESOLVED).orElseThrow().status());
        assertEquals(IncidentStatus.EXPIRED, home.incident(INC_EXPIRED).orElseThrow().status());
        assertEquals(IncidentVisibility.PRIVATE, home.incident(INC_HIDDEN).orElseThrow().visibility());

        IncidentRecord folded = home.incident(INC_FOLDED).orElseThrow();
        assertEquals(0, folded.currentContribution(), "a folded incident carries no weight of its own");
        assertEquals(Optional.of(INC_SUCCESSOR.toString()), folded.context("superseded_by"));
        assertEquals(-40, home.incident(INC_SUCCESSOR).orElseThrow().baseDelta());
    }

    /** The dedupe index is rebuilt from the loaded incidents, so a repeat deed is still a repeat. */
    @Test
    void dedupeKeysStillResolve() throws IOException {
        PlayerReputationRecord ada = loadGolden().player(ADA).orElseThrow();
        assertEquals(INC_ACTIVE,
                ada.findByDedupeKey(OVERWORLD_3, "dedupe-active").orElseThrow().id());
        assertEquals(INC_SUCCESSOR,
                ada.findByDedupeKey(OVERWORLD_3, "dedupe-successor").orElseThrow().id());
        assertTrue(ada.findByDedupeKey(OVERWORLD_3, "never-recorded").isEmpty());
    }

    // ------------------------------------------------------------------
    // Titles, high-water, migration markers, cached metadata
    // ------------------------------------------------------------------

    @Test
    void titlesAndTierHighWaterSurviveOnBothLadders() throws IOException {
        ReputationSavedData data = loadGolden();
        PlayerReputationRecord ada = data.player(ADA).orElseThrow();
        CommunityReputationRecord home = ada.community(OVERWORLD_3).orElseThrow();

        assertTrue(home.hasTitle(ResourceLocation.parse("mcaquests:honored_of_village")));
        assertTrue(home.hasTitle(ResourceLocation.parse("mcaquests:revered_of_village")));
        assertTrue(ada.hasGlobalTitle(ResourceLocation.parse("mcareputation:wanderer")));
        assertTrue(data.player(BO).orElseThrow()
                .hasGlobalTitle(ResourceLocation.parse("mcareputation:outcast")));

        assertEquals(Optional.of("acquaintance"), home.tierHighWater(ReputationTiers.DEFAULT_ID));
        assertEquals(Optional.of("friend"), home.tierHighWater(ReputationTiers.LEGACY_DEFAULT_ID),
                "the legacy mcaquests ladder's high-water mark must not be dropped");
    }

    /** Invariant: legacy import is exactly-once, and the marker is what makes it so. */
    @Test
    void theLegacyImportMarkerSurvives() throws IOException {
        PlayerReputationRecord ada = loadGolden().player(ADA).orElseThrow();
        assertTrue(ada.hasMigrated("mcaquests:legacy_reputation_v1"));
        assertEquals(Optional.of("1"), ada.migrationVersion("mcaquests:legacy_reputation_v1"));
        assertFalse(loadGolden().player(BO).orElseThrow().hasMigrated("mcaquests:legacy_reputation_v1"));
    }

    @Test
    void cachedVillageMetadataSurvives() throws IOException {
        PlayerReputationRecord ada = loadGolden().player(ADA).orElseThrow();
        assertEquals("Riverbend", ada.community(OVERWORLD_3).orElseThrow().metadata().name());
        assertEquals(Optional.of(new BlockPos(10, 64, -20)),
                ada.community(OVERWORLD_3).orElseThrow().metadata().center());
        assertEquals("Ashfall", ada.community(NETHER_3).orElseThrow().metadata().name());
        assertEquals(Optional.of(new BlockPos(-300, 40, 88)),
                ada.community(NETHER_3).orElseThrow().metadata().center());
    }

    // ------------------------------------------------------------------
    // Re-save
    // ------------------------------------------------------------------

    /**
     * Loading a 1.20.1 save and writing it back out must not lose or renumber anything, and must
     * still declare format 1 — a world upgraded to 1.21.1 keeps the same schema.
     */
    @Test
    void reSavingLosesNothingAndStaysFormatOne() throws IOException {
        CompoundTag rewritten = loadGolden().savePayload(new CompoundTag());
        assertEquals(1, rewritten.getInt("version"));

        ReputationSavedData reloaded = ReputationSavedData.loadPayload(rewritten);
        assertEquals(2, reloaded.playerCount());
        assertEquals(-70, reloaded.score(ADA, OVERWORLD_3));
        assertEquals(60, reloaded.score(ADA, NETHER_3));
        assertEquals(-80, reloaded.score(BO, OVERWORLD_3));

        CommunityReputationRecord home = reloaded.player(ADA).orElseThrow()
                .community(OVERWORLD_3).orElseThrow();
        assertEquals(6, home.incidentCount());
        assertEquals(IncidentStatus.ATONED, home.incident(INC_RESOLVED).orElseThrow().status());
        assertEquals(2, home.incident(INC_ACTIVE).orElseThrow().witnesses().size());
        assertEquals(Optional.of("acquaintance"), home.tierHighWater(ReputationTiers.DEFAULT_ID));
        assertTrue(reloaded.player(ADA).orElseThrow().hasMigrated("mcaquests:legacy_reputation_v1"));
    }

    /** A second round trip must be a fixed point: nothing may drift on repeated saves. */
    @Test
    void aSecondRoundTripIsAFixedPoint() throws IOException {
        CompoundTag once = loadGolden().savePayload(new CompoundTag());
        CompoundTag twice = ReputationSavedData.loadPayload(once).savePayload(new CompoundTag());
        assertEquals(once, twice, "save(load(save(x))) must equal save(x)");
    }
}
