package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.TestPaths;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.credit.CreditDecision;
import dev.otectus.mcareputation.community.CommunityMetadata;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.incident.ResolutionPolicy;
import dev.otectus.mcareputation.profile.IncidentProfileDefinition;
import dev.otectus.mcareputation.profile.IncidentProfileEvidence;
import dev.otectus.mcareputation.profile.ProfileMath;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golden saved-data files: proof that two loaders write the same bytes for the same ledger.
 *
 * <p>Both ledgers below are built entirely from loader-neutral state — no server, no level, no
 * registry — because the NeoForge port copies these exact fixtures and has to reproduce them byte for
 * byte. They are stored <b>uncompressed</b> so the comparison is over NBT and nothing else; a gzip
 * container would put the encoder's own choices between the two trees.
 *
 * <h2>Two files, two different jobs</h2>
 *
 * <p>{@code format-3} is the current serializer's own output, asserted byte for byte. {@code format-2}
 * predates this build and is therefore <b>evidence</b> rather than output: it can no longer be
 * compared whole, because the current serializer writes {@code version = 3}, but its player subtrees
 * still have to match exactly. That is the useful half — it proves the format bump added fields
 * without moving a single scalar byte, which is what lets an existing world load unchanged.
 *
 * <p>Regenerating is deliberate and gated, and only ever regenerates the newest file; see
 * {@code src/test/resources/fixtures/README.md}.
 */
class GoldenSavedDataTest {

    private static final String FORMAT_2_NAME = "mcareputation-format-2-1.20.1.nbt";
    private static final String FORMAT_3_NAME = "mcareputation-format-3-1.20.1.nbt";
    private static final String REGENERATE = "mcareputation.regenerateFixtures";

    private static final int MIN = -1000;
    private static final int MAX = 1000;
    private static final long DAY = 24_000L;

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID ANNA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BRAM = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final CommunityKey RIVERBEND =
            new CommunityKey(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 3);
    private static final CommunityKey ASHFALL =
            new CommunityKey(ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"), 3);
    private static final CommunityKey STONEBROOK =
            new CommunityKey(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 7);

    private static final ResourceLocation ASSAULT =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "villager_assaulted");
    private static final ResourceLocation KILLING =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "villager_killed");
    private static final ResourceLocation RESCUE =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "villager_rescued");
    private static final ResourceLocation CORE = ResourceLocation.fromNamespaceAndPath("mcareputation", "core");
    private static final ResourceLocation QUESTS = ResourceLocation.fromNamespaceAndPath("mcaquests", "reward");

    private static final UUID ASSAULT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KILLING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RESCUE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID THEFT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final ResourceLocation PROFILE =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "rescued_villager");
    private static final ResourceLocation ADVERSE_PROFILE =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "assaulted_villager");
    private static final ResourceLocation STUB_PROFILE =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "killed_villager");
    private static final ResourceLocation BRAVERY = ResourceLocation.fromNamespaceAndPath("mcareputation", "bravery");
    private static final ResourceLocation CREDIT_GROUP =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "rescue_service");

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
        riverbend.grantTitle(ResourceLocation.fromNamespaceAndPath("mcaquests", "honored_of_village"));
        ada.grantGlobalTitle(ResourceLocation.fromNamespaceAndPath("mcareputation", "wanderer"));
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

    /**
     * The format-3 ledger: everything above, plus one of each thing format 3 added.
     *
     * <p>Deliberately one of each <em>kind</em> rather than many of one: a live payload with a
     * reduced credit decision, an enriched legacy payload, an unenriched stub, a live credit window
     * with both a group and a subject counter, and a migration cursor that has not finished. A
     * fixture that only carried the easy case would not notice a serializer that dropped the hard
     * one.
     */
    private static ReputationSavedData profiledLedger() {
        ReputationSavedData data = ledger();
        PlayerReputationRecord ada = data.getOrCreatePlayer(ADA);
        CommunityReputationRecord riverbend = ada.community(RIVERBEND).orElseThrow();

        // A live payload, credited at 50% by the third rescue in the window. Half an authored point
        // survives as 5000 subunits, which is the fixed-point promise of 8.3 on disk.
        CreditDecision credited = new CreditDecision(Optional.of(CREDIT_GROUP),
                Optional.of("beneficiary"), 2, 1, 5000, 5000, 5000,
                CreditDecision.Reason.GROUP_SCHEDULE);
        IncidentRecord rescue = riverbend.incident(RESCUE_ID).orElseThrow();
        rescue.initializeProfileClock(2 * DAY, 500L);
        rescue.attachProfileEvidence(IncidentProfileEvidence.of(
                IncidentProfileEvidence.Origin.LIVE, PROFILE, 0x0123456789abcdefL, 7L,
                Optional.of(channel(Optional.empty(), 6, credited, 56 * DAY, 2 * DAY,
                        IncidentProfileDefinition.ResolutionMode.RECOGNITION)),
                List.of(channel(Optional.of(BRAVERY), 8, credited, 28 * DAY, 2 * DAY,
                        IncidentProfileDefinition.ResolutionMode.HISTORICAL)),
                credited));

        // The folded assault carries enriched legacy evidence at full historical credit: the deed
        // predates the feature, and a superseded record keeps its units while contributing none.
        IncidentRecord assault = riverbend.incident(ASSAULT_ID).orElseThrow();
        assault.adoptLegacyProfileClock();
        assault.attachProfileEvidence(IncidentProfileEvidence.of(
                IncidentProfileEvidence.Origin.LEGACY_ENRICHED, ADVERSE_PROFILE, -1L, 7L,
                Optional.of(channel(Optional.empty(), 4,
                        CreditDecision.unlimited(CreditDecision.Reason.LEGACY_FULL_CREDIT),
                        56 * DAY, 0L, IncidentProfileDefinition.ResolutionMode.RECOGNITION)),
                List.of(),
                CreditDecision.unlimited(CreditDecision.Reason.LEGACY_FULL_CREDIT)));

        // Two accepted qualifying operations in one live window, one of them for a named subject.
        riverbend.creditTrackers().consume(CREDIT_GROUP, 14 * DAY, Optional.of(BRAM.toString()),
                true, 400L);
        riverbend.creditTrackers().consume(CREDIT_GROUP, 14 * DAY, Optional.of(BRAM.toString()),
                true, 500L);

        // Bo's deed is a stub: the kind of thing the migration marks and the enrichment pass has not
        // reached yet.
        PlayerReputationRecord bo = data.getOrCreatePlayer(BO);
        IncidentRecord theft = bo.community(RIVERBEND).orElseThrow().incident(THEFT_ID).orElseThrow();
        theft.adoptLegacyProfileClock();
        theft.attachProfileEvidence(IncidentProfileEvidence.legacyStub(STUB_PROFILE));

        // ... and the cursor stopped after Ada, which is what makes coverage MIGRATING rather than
        // partial: there is work still owed, and the file has to say so.
        data.openProfileMigrationForTest(1L, ADA);
        return data;
    }

    private static IncidentProfileEvidence.Channel channel(Optional<ResourceLocation> facet,
                                                           int points, CreditDecision decision,
                                                           long lifetime, long age,
                                                           IncidentProfileDefinition.ResolutionMode mode) {
        long authored = ProfileMath.subunits(points);
        long credited = decision.applyTo(authored);
        return new IncidentProfileEvidence.Channel(facet, authored, credited,
                ProfileMath.remainingAt(credited, age, lifetime, ProfileMath.DEFAULT_DECAY_STEP_TICKS),
                lifetime, ProfileMath.DEFAULT_DECAY_STEP_TICKS, mode,
                IncidentProfileDefinition.ResolutionMultipliers.DEFAULT);
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

    private static byte[] fixtureBytes(String name) {
        String path = "/fixtures/" + name;
        try (InputStream in = GoldenSavedDataTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing test fixture " + path
                    + "; regenerate it with -D" + REGENERATE + "=true");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CompoundTag fixtureTag(String name) {
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(fixtureBytes(name)))) {
            return NbtIo.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Rewrites the <b>current</b> fixture. Gated, and skipped on every ordinary run: a golden file
     * that regenerates itself when it disagrees proves nothing at all.
     *
     * <p>Only the newest file is ever written. The format-1 and format-2 fixtures were produced by
     * builds that no longer exist, which is the whole of their evidential value.
     */
    @Test
    void regenerateTheFixture() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(REGENERATE), "fixture regeneration is not requested");
        Path target = TestPaths.testResources().resolve("fixtures").resolve(FORMAT_3_NAME);
        Files.createDirectories(target.getParent());
        Files.write(target, encode(profiledLedger().savePayload(new CompoundTag())));
    }

    // --- the assertions -----------------------------------------------------

    @Test
    void theFormatTwoFixtureIsStillAFormatTwoFile() {
        assertEquals(2, fixtureTag(FORMAT_2_NAME).getInt("version"));
    }

    @Test
    void theFormatThreeFixtureIsAFormatThreeFile() {
        assertEquals(3, fixtureTag(FORMAT_3_NAME).getInt("version"));
        assertEquals(ReputationSavedData.FORMAT_VERSION, fixtureTag(FORMAT_3_NAME).getInt("version"));
    }

    @Test
    void theCurrentSerializerReproducesTheFormatThreeFixtureByteForByte() {
        assertArrayEquals(fixtureBytes(FORMAT_3_NAME),
                encode(profiledLedger().savePayload(new CompoundTag())),
                "the v3 serializer no longer writes the ledger it wrote when this fixture was taken");
    }

    /**
     * The format bump added fields and moved nothing: a ledger with no profile content still
     * serializes to the same player subtrees the format-2 file carries.
     *
     * <p>This is the strongest statement that can honestly be made about the older file now — the
     * version header legitimately differs — and it is the one that matters. Every new field is
     * written only when it carries information, so an existing world's incidents, receipts, titles
     * and high-water marks are byte-for-byte what they were.
     */
    @Test
    void theFormatTwoPlayerSubtreesStillSerializeIdentically() {
        CompoundTag fixture = fixtureTag(FORMAT_2_NAME);
        CompoundTag current = ledger().savePayload(new CompoundTag());

        assertEquals(fixture.getCompound("players"), current.getCompound("players"),
                "format 3 must add fields without moving a scalar byte of format 2");
        assertEquals(fixture.getList("decayImmune", 10), current.getList("decayImmune", 10));
        assertTrue(current.contains("version"));
    }

    @Test
    void theFormatTwoFixtureLoadsWithTheExpectedTotals() {
        ReputationSavedData loaded = ReputationSavedData.loadPayload(fixtureTag(FORMAT_2_NAME));

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
    void theFormatThreeFixtureRoundTripsUnchanged() {
        assertArrayEquals(fixtureBytes(FORMAT_3_NAME),
                encode(ReputationSavedData.loadPayload(fixtureTag(FORMAT_3_NAME))
                        .savePayload(new CompoundTag())),
                "loading and re-saving the golden file must not move a byte");
    }

    /** Every format-3 addition survives the round trip with its exact stored quantities. */
    @Test
    void theFormatThreeFixtureLoadsItsProfileStateExactly() {
        ReputationSavedData loaded = ReputationSavedData.loadPayload(fixtureTag(FORMAT_3_NAME));
        PlayerReputationRecord ada = loaded.player(ADA).orElseThrow();
        CommunityReputationRecord riverbend = ada.community(RIVERBEND).orElseThrow();

        IncidentRecord rescue = riverbend.incident(RESCUE_ID).orElseThrow();
        IncidentProfileEvidence live = rescue.profileEvidence().orElseThrow();
        assertEquals(IncidentProfileEvidence.Origin.LIVE, live.origin());
        assertEquals(PROFILE, live.profileId());
        assertEquals(2 * DAY, rescue.profileElapsedTicks());
        assertEquals(500L, rescue.lastProfileObservedGameTime());
        // Authored 8 bravery, credited at 50% is 4 points, aged two of its 28 days.
        IncidentProfileEvidence.Channel bravery = live.facet(BRAVERY).orElseThrow();
        assertEquals(80_000L, bravery.authored());
        assertEquals(40_000L, bravery.credited());
        assertEquals(37_142L, bravery.current());
        assertEquals(5000, live.credit().effectiveBp());
        assertEquals(CreditDecision.Reason.GROUP_SCHEDULE, live.credit().reason());

        IncidentRecord assault = riverbend.incident(ASSAULT_ID).orElseThrow();
        assertEquals(IncidentProfileEvidence.Origin.LEGACY_ENRICHED,
                assault.profileEvidence().orElseThrow().origin());
        assertFalse(assault.profileContributes(), "a folded deed describes nobody");

        assertTrue(riverbend.creditTrackers().trackedGroups().contains(CREDIT_GROUP));
        assertEquals(1, riverbend.creditTrackers().groupCount());
        assertEquals(1, riverbend.creditTrackers().subjectCount());
        assertFalse(riverbend.creditTrackers().isOverflowing());

        IncidentRecord theft = loaded.player(BO).orElseThrow().community(RIVERBEND).orElseThrow()
                .incident(THEFT_ID).orElseThrow();
        assertTrue(theft.profileEvidence().orElseThrow().isEnrichmentCandidate());

        assertTrue(loaded.isProfileMigrationPending(), "the cursor was not finished");
        assertEquals(Optional.of(ADA), loaded.profileMigrationState().cursor());
        assertEquals(ProfileMigrationState.Coverage.MIGRATING, loaded.profileCoverage());
    }

    /**
     * A file from a format this build does not understand loads read-only and is handed back
     * verbatim (I14, §19.4).
     *
     * <p>Synthesised from the format-3 fixture rather than shipped as a fourth file: the point is the
     * version number, and a real future file cannot exist until a future build writes one. What is
     * asserted is the whole of the promise — nothing loaded, nothing writable, and every key returned
     * exactly as it arrived, including the ones this build has no idea about.
     */
    @Test
    void aFutureFormatFileLoadsReadOnlyAndReSavesVerbatim() {
        CompoundTag future = fixtureTag(FORMAT_3_NAME);
        future.putInt("version", ReputationSavedData.FORMAT_VERSION + 1);
        CompoundTag unknown = new CompoundTag();
        unknown.putString("somethingFromTheFuture", "keep me");
        future.put("profileArchive", unknown);

        ReputationSavedData loaded = ReputationSavedData.loadPayload(future);

        assertTrue(loaded.isReadOnly());
        assertFalse(loaded.writable());
        assertEquals(0, loaded.playerCount(), "nothing may be loaded from a format we cannot read");
        assertFalse(loaded.isProfileMigrationPending(), "and no migration may be attempted on it");
        assertEquals(0, loaded.advanceProfileMigration(), "a read-only store enriches nothing");
        assertEquals(future, loaded.savePayload(new CompoundTag()),
                "a file from the future is handed back exactly as it arrived");
    }
}
