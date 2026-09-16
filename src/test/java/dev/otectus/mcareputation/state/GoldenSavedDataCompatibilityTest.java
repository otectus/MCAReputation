package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.TestPaths;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.IncidentProfileEvidence;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    /** The format-3 fixture's own ids, from the Forge fixture's {@code profiledLedger()}. */
    private static final UUID RESCUE_V3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final ResourceLocation BRAVERY =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "bravery");
    private static final ResourceLocation CREDIT_GROUP =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "rescue_service");

    private static final CommunityKey OVERWORLD_3 = new CommunityKey(
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 3);
    private static final CommunityKey NETHER_3 = new CommunityKey(
            ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"), 3);

    /**
     * The format-2 golden file, written by the Forge 1.20.1 serializer and copied here byte for byte.
     * Stored uncompressed, so the comparison below is over NBT and nothing else.
     */
    private static final Path FIXTURE_V2 = TestPaths.testResources()
            .resolve("fixtures/mcareputation-format-2-1.20.1.nbt");

    /**
     * The third Forge fixture, written by the Forge 0.6.0 serializer at format 3 and copied here
     * byte for byte. This is the strongest of the three statements: format 3 is where the profile
     * layer first reaches other people's disks, and it has to reach them as the same bytes on both
     * loaders or a world stops being able to cross.
     */
    private static final Path FIXTURE_V3 = TestPaths.testResources()
            .resolve("fixtures/mcareputation-format-3-1.20.1.nbt");

    private static final UUID V2_ASSAULT =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID V2_KILLING = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID V2_RESCUE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final CommunityKey STONEBROOK = new CommunityKey(
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 7);

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

    /** The format-3 fixture, likewise uncompressed so the two loaders compare over NBT alone. */
    private static CompoundTag readFixtureV3() throws IOException {
        return readUncompressed(FIXTURE_V3, "format-3");
    }

    private static CompoundTag readUncompressed(Path fixture, String what) throws IOException {
        assertTrue(Files.isRegularFile(fixture),
                () -> "the golden " + what + " fixture is missing from " + fixture.toAbsolutePath());
        try (DataInputStream in = new DataInputStream(Files.newInputStream(fixture))) {
            return NbtIo.read(in, NbtAccounter.unlimitedHeap());
        }
    }

    // ------------------------------------------------------------------
    // The format itself
    // ------------------------------------------------------------------

    @Test
    void theFixtureStillDeclaresFormatVersionOne() throws IOException {
        assertEquals(1, readFixture().getInt("version"),
                "the fixture is evidence of what 1.20.1 wrote; it must never be regenerated");
        assertEquals(3, ReputationSavedData.FORMAT_VERSION,
                "the schema number tracks the Forge build exactly; the loader port does not move it");
        assertEquals("mcareputation", ReputationSavedData.DATA_NAME,
                "the data file name is part of the save's identity and must not move");
    }

    /**
     * The stored version is read from the file, then carried forward. A v1 file is recognised as v1
     * and migrated in place, which is why {@code loadedVersion()} reports the current format
     * afterwards — 3 since 0.6.0, and the upgrade runs in one step.
     */
    @Test
    void loadingReportsTheStoredVersionRatherThanAssumingIt() throws IOException {
        assertEquals(ReputationSavedData.FORMAT_VERSION, loadGolden().loadedVersion(),
                "a v1 file is migrated on load, so the live store is at the current format");
    }

    // ------------------------------------------------------------------
    // The v1 migration
    // ------------------------------------------------------------------

    /**
     * Migration is additive: every retained incident that carries a dedupe key gains an
     * {@code APPLIED} receipt naming that incident, and nothing else in the save moves.
     */
    @Test
    void migrationGivesEveryKeyedIncidentAReceiptAndMovesNothingElse() throws IOException {
        ReputationSavedData data = loadGolden();
        PlayerReputationRecord ada = data.player(ADA).orElseThrow();
        CommunityReputationRecord home = ada.community(OVERWORLD_3).orElseThrow();

        for (IncidentRecord incident : home.incidents()) {
            Optional<String> key = incident.dedupeKey();
            if (key.isEmpty()) {
                continue;
            }
            OperationReceipt receipt = ada.findReceipt(incident.source().getNamespace(),
                    OVERWORLD_3, key.get()).orElseThrow(() ->
                    new AssertionError("no receipt recovered for dedupe key " + key.get()));
            assertEquals(ReceiptOutcome.APPLIED, receipt.outcome());
            assertEquals(Optional.of(incident.id()), receipt.incidentId(),
                    "a replayed operation key must name the incident it already produced");
        }

        // Totals, ids and baselines are exactly what the pre-migration assertions above expect.
        assertEquals(2, data.playerCount());
        assertEquals(6, home.incidentCount());
        assertEquals(-70, data.score(ADA, OVERWORLD_3));
        assertEquals(60, data.score(ADA, NETHER_3));
        assertEquals(-80, data.score(BO, OVERWORLD_3));
        assertEquals(25, home.baseline());
        assertEquals(60, ada.community(NETHER_3).orElseThrow().baseline());
        assertEquals(INC_ACTIVE, ada.findByDedupeKey(OVERWORLD_3, "dedupe-active").orElseThrow().id());
    }

    /** Idempotent: migrating an already-migrated store adds no second receipt and no second event. */
    @Test
    void migratingTwiceIsANoOp() throws IOException {
        ReputationSavedData once = loadGolden();
        int receipts = once.player(ADA).orElseThrow().receipts().size();
        assertTrue(receipts > 0, "the fixture has keyed incidents, so migration must produce receipts");

        ReputationSavedData twice =
                ReputationSavedData.loadPayload(once.savePayload(new CompoundTag()));
        assertEquals(ReputationSavedData.FORMAT_VERSION, twice.loadedVersion());
        assertEquals(receipts, twice.player(ADA).orElseThrow().receipts().size(),
                "an already-migrated file must not be migrated again");
    }

    // ------------------------------------------------------------------
    // Format 3: the profile layer crossing loaders
    // ------------------------------------------------------------------

    /**
     * The whole of invariant 2 at the current format: the Forge 0.6.0 serializer's own output is
     * byte-for-byte what this build writes for the same ledger.
     *
     * <p>{@code GoldenSavedDataTest} builds that ledger from loader-neutral state and asserts the
     * bytes; this test asserts the other half, that the file it is comparing against is the Forge
     * file and not a local regeneration. The digest is checked rather than the name, because a
     * regenerated file keeps its name.
     */
    @Test
    void theForgeFormatThreeFixtureIsTheFileForgeWrote() throws IOException {
        assertTrue(Files.isRegularFile(FIXTURE_V3),
                () -> "the Forge format-3 fixture is missing from " + FIXTURE_V3.toAbsolutePath());
        assertEquals("1a906772662000bc0331c6ba358d21cb1880b1036356a900acd3473a233ec758",
                sha256(FIXTURE_V3),
                "this file is evidence copied from the Forge branch; never regenerate it here");
        assertEquals(3, readFixtureV3().getInt("version"));
        assertEquals(ReputationSavedData.FORMAT_VERSION, readFixtureV3().getInt("version"));
    }

    /**
     * Every format-3 addition survives the crossing with its exact stored quantities.
     *
     * <p>The frozen subunits are the point (§9.4): an authored quantity is copied onto an accepted
     * deed forever, so a loader that read them back even slightly differently would be handing the
     * player a different history rather than a rounding error.
     */
    @Test
    void theProfileLayerCrossesLoadersIntact() throws IOException {
        ReputationSavedData loaded = ReputationSavedData.loadPayload(readFixtureV3());
        assertEquals(3, loaded.loadedVersion(), "nothing is migrated: this is already format 3");
        assertFalse(loaded.isReadOnly());

        CommunityReputationRecord riverbend = loaded.player(ADA).orElseThrow()
                .community(OVERWORLD_3).orElseThrow();
        IncidentRecord rescue = riverbend.incident(RESCUE_V3).orElseThrow();
        IncidentProfileEvidence live = rescue.profileEvidence().orElseThrow();
        assertEquals(IncidentProfileEvidence.Origin.LIVE, live.origin());
        assertEquals(48_000L, rescue.profileElapsedTicks(), "the second clock is persisted");
        assertEquals(500L, rescue.lastProfileObservedGameTime());

        IncidentProfileEvidence.Channel bravery = live.facet(BRAVERY).orElseThrow();
        assertEquals(80_000L, bravery.authored(), "authored 8 points, in subunits");
        assertEquals(40_000L, bravery.credited(), "credited at 50%");
        assertEquals(37_142L, bravery.current(), "aged two of its 28 days");
        assertEquals(5000, live.credit().effectiveBp());

        assertTrue(riverbend.creditTrackers().trackedGroups().contains(CREDIT_GROUP),
                "the bounded anti-farm counters cross too, or an allowance is handed back");
        assertEquals(1, riverbend.creditTrackers().groupCount());
        assertEquals(1, riverbend.creditTrackers().subjectCount());
        assertFalse(riverbend.creditTrackers().isOverflowing());

        assertTrue(loaded.isProfileMigrationPending(), "an unfinished cursor stays unfinished");
        assertEquals(ProfileMigrationState.Coverage.MIGRATING, loaded.profileCoverage());
    }

    /** Re-saving the Forge format-3 file must not move a byte, in either direction. */
    @Test
    void reSavingTheForgeFormatThreeFileIsAFixedPoint() throws IOException {
        CompoundTag fixture = readFixtureV3();
        CompoundTag rewritten = ReputationSavedData.loadPayload(fixture)
                .savePayload(new CompoundTag());
        assertEquals(fixture, rewritten,
                "a save that crossed loaders must be writable back as the same tree");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandated by the JLS", e);
        }
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
     * Loading a 1.20.1 save and writing it back out must not lose or renumber anything. It now
     * declares the current format, because the load migrated it; the fixture on disk is untouched.
     */
    @Test
    void reSavingLosesNothingAndWritesTheUpgradedFormat() throws IOException {
        CompoundTag rewritten = loadGolden().savePayload(new CompoundTag());
        assertEquals(ReputationSavedData.FORMAT_VERSION, rewritten.getInt("version"));

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

    // ------------------------------------------------------------------
    // The format-2 golden file
    // ------------------------------------------------------------------

    private static byte[] fixtureV2Bytes() throws IOException {
        assertTrue(Files.isRegularFile(FIXTURE_V2),
                () -> "the golden format-2 fixture is missing from " + FIXTURE_V2.toAbsolutePath());
        return Files.readAllBytes(FIXTURE_V2);
    }

    private static CompoundTag readFixtureV2() throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(fixtureV2Bytes()))) {
            return NbtIo.read(in);
        }
    }

    /**
     * A v2 ledger written by Forge 1.20.1 reads on NeoForge 1.21.1 with exactly the totals the Forge
     * {@code GoldenSavedDataTest} asserts against it. Same numbers, other loader.
     *
     * <p>Since 0.6.0 it also takes the v2-to-v3 upgrade here, and takes it exactly as the Forge
     * build would: the schema number moves, no total does. This was a "nothing left to migrate"
     * assertion until format 3 existed.
     */
    @Test
    void theFormatTwoFixtureLoadsWithTheExpectedTotals() throws IOException {
        assertEquals(2, readFixtureV2().getInt("version"));
        ReputationSavedData loaded = ReputationSavedData.loadPayload(readFixtureV2());

        assertEquals(2, loaded.playerCount());
        assertEquals(3, loaded.loadedVersion(),
                "a format-2 file is upgraded to 3 in place, exactly as the Forge build upgrades it");
        assertEquals(ReputationSavedData.FORMAT_VERSION, loaded.loadedVersion());
        assertFalse(loaded.isReadOnly(), "format 2 is a past format of this build, not a future one");
        assertEquals(-25, loaded.score(ADA, OVERWORLD_3));
        assertEquals(60, loaded.score(ADA, NETHER_3));
        assertEquals(15, loaded.score(ADA, STONEBROOK));
        assertEquals(-80, loaded.score(BO, OVERWORLD_3));
        assertTrue(loaded.isDecayImmune(STONEBROOK));

        PlayerReputationRecord ada = loaded.player(ADA).orElseThrow();
        assertEquals(2, ada.receiptCount());
        assertEquals(2L, ada.titleRevision());
        assertTrue(ada.hasMigrated("mcaquests:legacy_reputation_v1"));

        CommunityReputationRecord riverbend = ada.community(OVERWORLD_3).orElseThrow();
        assertEquals(Optional.of("acquaintance"), riverbend.tierHighWater(ReputationTiers.DEFAULT_ID));
        IncidentRecord folded = riverbend.incident(V2_ASSAULT).orElseThrow();
        assertTrue(folded.isSuperseded());
        assertEquals(Optional.of(V2_KILLING), folded.supersededBy());
        assertEquals(1L, folded.storyRevision());
        assertEquals(1L, riverbend.incident(V2_RESCUE).orElseThrow().storyRevision());
    }

    /**
     * The cross-loader claim itself, at the current format: re-saving that file on NeoForge moves
     * the schema number and nothing else. A difference anywhere else is a real incompatibility in
     * the schema, never a reason to rewrite the file.
     *
     * <p>Raw byte identity was the 0.5.0 statement and format 3 cannot honour it — the version
     * header legitimately differs — so the claim is made where it still means something, over the
     * player subtrees. Every format-3 field is written only when it carries information, so a ledger
     * with no profile content re-serializes as exactly the bytes Forge 1.20.1 wrote at format 2.
     */
    @Test
    void reSavingTheFormatTwoFixtureMovesNothingButTheSchemaNumber() throws IOException {
        CompoundTag fixture = readFixtureV2();
        CompoundTag rewritten = ReputationSavedData.loadPayload(readFixtureV2())
                .savePayload(new CompoundTag());

        assertEquals(3, rewritten.getInt("version"), "the v2 to v3 upgrade is the only difference");
        assertEquals(fixture.getCompound("players"), rewritten.getCompound("players"),
                "format 3 must add fields without moving a byte of the format-2 player subtrees");
        assertEquals(fixture.getList("decayImmune", Tag.TAG_COMPOUND),
                rewritten.getList("decayImmune", Tag.TAG_COMPOUND));
    }
}
