package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.credit.CreditDecision;
import dev.otectus.mcareputation.credit.CreditPolicy;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.IncidentProfileDefinition;
import dev.otectus.mcareputation.profile.IncidentProfileEvidence;
import dev.otectus.mcareputation.profile.ProfileMath;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Format 3 on disk: the frozen profile payload, the bounded credit trackers, and the v2 to v3
 * migration with its resumable enrichment pass (§9.4, §10.5, §19).
 *
 * <p>Everything here runs against the real NBT path with no server, level or registry beyond the two
 * content registries the code under test reads, because the failures being guarded against are all
 * failures of the <em>file</em>: a payload that allocates from a hostile length, a counter that comes
 * back reset, a migration that awards the same history twice.
 */
class ProfileEvidencePersistenceTest {

    private static final int MIN = -1000;
    private static final int MAX = 1000;
    private static final long DAY = 24_000L;
    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;

    // The built-in types and shipped profile ids the frozen migration manifest actually names.
    private static final ResourceLocation RESCUE = ResourceLocation.fromNamespaceAndPath("mcareputation", "villager_rescued");
    private static final ResourceLocation ASSAULT = ResourceLocation.fromNamespaceAndPath("mcareputation", "villager_assaulted");
    private static final ResourceLocation RESCUE_PROFILE =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "rescued_villager");
    private static final ResourceLocation ASSAULT_PROFILE =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "assaulted_villager");
    private static final ResourceLocation BRAVERY = ResourceLocation.fromNamespaceAndPath("mcareputation", "bravery");
    private static final ResourceLocation COMPASSION = ResourceLocation.fromNamespaceAndPath("mcareputation", "compassion");

    // A type no manifest speaks for: §19.2's "unrecognized/custom/ambiguous stays unenriched".
    private static final ResourceLocation CUSTOM = ResourceLocation.fromNamespaceAndPath("somemod", "custom_deed");
    private static final ResourceLocation CUSTOM_PROFILE = ResourceLocation.fromNamespaceAndPath("somemod", "custom_profile");

    private static final ResourceLocation PRIVATE_TYPE = ResourceLocation.fromNamespaceAndPath("mcareputation", "promise_made");
    /** A built-in type with no {@code social_profile} at all: recognition is authored or zero (§7.1). */
    private static final ResourceLocation UNPROFILED =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "quest_completed");
    private static final ResourceLocation GROUP = ResourceLocation.fromNamespaceAndPath("mcareputation", "rescue_service");
    private static final ResourceLocation SOURCE = TestFixtures.SOURCE;

    @BeforeEach
    void setUp() {
        SaveQuarantine.clear();
        defineIncidents();
        publishProfiles();
    }

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
        ProfileRegistryBundle.clear();
        SaveQuarantine.clear();
    }

    // ------------------------------------------------------------------
    // Content the migration reads
    // ------------------------------------------------------------------

    private static void defineIncidents() {
        Map<ResourceLocation, IncidentDefinition> definitions = new LinkedHashMap<>();
        definitions.put(RESCUE, TestFixtures.definition(8, IncidentVisibility.VILLAGE,
                DecayPolicy.NONE, RESCUE_PROFILE));
        definitions.put(ASSAULT, TestFixtures.definition(-8, IncidentVisibility.WITNESSED,
                DecayPolicy.NONE, ASSAULT_PROFILE));
        definitions.put(CUSTOM, TestFixtures.definition(4, IncidentVisibility.VILLAGE,
                DecayPolicy.NONE, CUSTOM_PROFILE));
        definitions.put(PRIVATE_TYPE, TestFixtures.definition(0, IncidentVisibility.PRIVATE,
                DecayPolicy.NONE, RESCUE_PROFILE));
        // A type with social meaning nobody authored: the ordinary case, and it must stay payload-free.
        definitions.put(UNPROFILED, TestFixtures.definition(-4,
                IncidentVisibility.VILLAGE, DecayPolicy.NONE));
        IncidentRegistry.replaceAll(definitions);
    }

    /**
     * The shipped shapes that matter to migration: a wholly factual profile, and one whose second
     * facet is {@code evaluative} and therefore may never be reconstructed for old history.
     */
    private static void publishProfiles() {
        IncidentProfileDefinition rescue = new IncidentProfileDefinition(
                List.of(),
                Optional.of(contribution(6, 56 * DAY,
                        IncidentProfileDefinition.ResolutionMode.RECOGNITION)),
                Map.of(BRAVERY, contribution(8, 28 * DAY,
                        IncidentProfileDefinition.ResolutionMode.HISTORICAL)),
                IncidentProfileDefinition.CreditClass.COMMENDABLE,
                Optional.of(TestFixtures.CREDIT_POLICY), false, Optional.empty());
        Map<ResourceLocation, IncidentProfileDefinition.Contribution> adverseFacets =
                new LinkedHashMap<>();
        adverseFacets.put(BRAVERY, contribution(8, 28 * DAY,
                IncidentProfileDefinition.ResolutionMode.HISTORICAL));
        adverseFacets.put(COMPASSION, contribution(-4, 28 * DAY,
                IncidentProfileDefinition.ResolutionMode.EVALUATIVE));
        IncidentProfileDefinition assault = new IncidentProfileDefinition(
                List.of(),
                Optional.of(contribution(4, 56 * DAY,
                        IncidentProfileDefinition.ResolutionMode.RECOGNITION)),
                adverseFacets,
                IncidentProfileDefinition.CreditClass.ADVERSE,
                Optional.empty(), false, Optional.empty());
        ProfileRegistryBundle.publish(Map.of(), Map.of(),
                Map.of(RESCUE_PROFILE, rescue, ASSAULT_PROFILE, assault,
                        CUSTOM_PROFILE, rescue),
                Map.of(TestFixtures.CREDIT_POLICY,
                        new CreditPolicy(GROUP, 14 * DAY, List.of(10000, 10000, 5000, 2500, 0), 0,
                                CreditPolicy.Scope.PLAYER_COMMUNITY, Optional.of(
                                        new CreditPolicy.SubjectLimit("beneficiary",
                                                List.of(10000, 5000, 0), 0)))));
    }

    private static IncidentProfileDefinition.Contribution contribution(
            int points, long lifetime, IncidentProfileDefinition.ResolutionMode mode) {
        return new IncidentProfileDefinition.Contribution(points, lifetime, mode,
                IncidentProfileDefinition.ResolutionMultipliers.DEFAULT);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static IncidentRecord incident(UUID id, ResourceLocation type, UUID player, int delta,
                                           IncidentVisibility visibility, long created) {
        return IncidentRecord.create(id, type, player, HOME, created, SOURCE,
                Optional.of("op:" + id), delta, visibility, IncidentSeverity.MODERATE,
                List.of(IncidentSubject.villager(TestFixtures.VILLAGER_1, "Anna", "beneficiary")));
    }

    /** A store as 0.5.0 would have written it: real scalar history, no profile field anywhere. */
    private static ReputationSavedData legacyStore(UUID... playerIds) {
        ReputationSavedData data = ReputationSavedData.createForTest();
        for (UUID playerId : playerIds) {
            PlayerReputationRecord player = data.getOrCreatePlayer(playerId);
            CommunityReputationRecord community = player.getOrCreate(HOME);
            community.addBaseline(25, MIN, MAX);
            IncidentRecord rescue = incident(UUID.nameUUIDFromBytes(("r" + playerId).getBytes()),
                    RESCUE, playerId, 8, IncidentVisibility.VILLAGE, 0L);
            // Two weeks of observed age, which is exactly half of the bravery lifetime below.
            rescue.reconcile(DecayPolicy.NONE, 14 * DAY);
            community.addIncident(rescue);
            community.addIncident(incident(UUID.nameUUIDFromBytes(("a" + playerId).getBytes()),
                    ASSAULT, playerId, -8, IncidentVisibility.WITNESSED, 0L));
            community.addIncident(incident(UUID.nameUUIDFromBytes(("c" + playerId).getBytes()),
                    CUSTOM, playerId, 4, IncidentVisibility.VILLAGE, 0L));
            community.addIncident(incident(UUID.nameUUIDFromBytes(("p" + playerId).getBytes()),
                    PRIVATE_TYPE, playerId, 0, IncidentVisibility.PRIVATE, 0L));
            community.addIncident(incident(UUID.nameUUIDFromBytes(("u" + playerId).getBytes()),
                    UNPROFILED, playerId, -4, IncidentVisibility.VILLAGE, 0L));
            community.recomputeScore(MIN, MAX);
            player.setLastKnownName("P" + playerId.toString().substring(0, 4));
        }
        return data;
    }

    /** The same bytes, labelled as the format they came from. */
    private static CompoundTag asFormatTwo(ReputationSavedData data) {
        CompoundTag tag = data.savePayload(new CompoundTag());
        tag.putInt("version", 2);
        return tag;
    }

    private static List<IncidentRecord> allIncidents(ReputationSavedData data) {
        List<IncidentRecord> out = new ArrayList<>();
        data.players().forEach(player ->
                player.communities().forEach(community -> out.addAll(community.incidents())));
        return out;
    }

    private static Map<String, String> evidenceByIncident(ReputationSavedData data) {
        Map<String, String> out = new LinkedHashMap<>();
        for (IncidentRecord incident : allIncidents(data)) {
            out.put(incident.id().toString(), incident.profileEvidence()
                    .map(evidence -> evidence.save().toString())
                    .orElse("-"));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // §19.1 — the structural half
    // ------------------------------------------------------------------

    /** Every scalar value, identity, receipt and clock is what it was, read at the same time. */
    @Test
    void structuralMigrationPreservesEveryScalarValueAndIdentity() {
        ReputationSavedData before = legacyStore(TestFixtures.PLAYER_A);
        CompoundTag tag = asFormatTwo(before);
        CommunityReputationRecord old = before.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow();

        ReputationSavedData after = ReputationSavedData.loadPayload(tag);
        CommunityReputationRecord migrated = after.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow();

        assertEquals(ReputationSavedData.FORMAT_VERSION, after.loadedVersion());
        assertEquals(old.score(), migrated.score(), "the upgrade may not move a score");
        assertEquals(old.baseline(), migrated.baseline());
        assertEquals(old.incidentCount(), migrated.incidentCount());
        assertEquals(old.lastReconciledGameTime(), migrated.lastReconciledGameTime());
        for (IncidentRecord original : old.incidents()) {
            IncidentRecord copy = migrated.incident(original.id()).orElseThrow();
            assertEquals(original.baseDelta(), copy.baseDelta());
            assertEquals(original.settledDelta(), copy.settledDelta());
            assertEquals(original.currentContribution(), copy.currentContribution());
            assertEquals(original.decayElapsedTicks(), copy.decayElapsedTicks());
            assertEquals(original.status(), copy.status());
            assertEquals(original.dedupeKey(), copy.dedupeKey());
            assertEquals(original.witnesses(), copy.witnesses());
            assertEquals(original.subjects(), copy.subjects());
            assertEquals(original.storyRevision(), copy.storyRevision());
        }
        assertEquals("P" + TestFixtures.PLAYER_A.toString().substring(0, 4),
                after.player(TestFixtures.PLAYER_A).orElseThrow().lastKnownName());
    }

    /**
     * A stub goes on exactly the public incidents whose definition names a profile, and nowhere else.
     *
     * <p>The two negative cases are the interesting ones. A type with no {@code social_profile} gets
     * nothing, because §7.1 makes recognition authored or zero. A <b>private</b> record gets nothing
     * either, because it can never contribute public evidence (I03) and a stub would claim there is
     * something waiting to be established about it.
     */
    @Test
    void structuralMigrationStubsProfiledPublicIncidentsAndNothingElse() {
        ReputationSavedData data = ReputationSavedData.loadPayload(
                asFormatTwo(legacyStore(TestFixtures.PLAYER_A)));
        CommunityReputationRecord community = data.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow();

        for (IncidentRecord incident : community.incidents()) {
            boolean profiled = IncidentRegistry.get(incident.type())
                    .flatMap(IncidentDefinition::socialProfile).isPresent();
            boolean isPublic = incident.visibility().effective() != IncidentVisibility.PRIVATE;
            if (profiled && isPublic) {
                IncidentProfileEvidence evidence = incident.profileEvidence().orElseThrow(
                        () -> new AssertionError("no stub on " + incident.type()));
                assertEquals(IncidentProfileEvidence.Origin.LEGACY_UNENRICHED, evidence.origin());
                assertTrue(evidence.channels().isEmpty(), "a stub carries no quantities at all");
                assertFalse(evidence.hasCurrentSubunits());
            } else {
                assertTrue(incident.profileEvidence().isEmpty(),
                        incident.type() + " must carry no payload at all");
            }
        }
        assertEquals(3L, data.profileMigrationState().stubbed(),
                "rescue, assault and the custom deed; not the private one, not the unprofiled one");
    }

    /** The profile clock starts at the only interval the old save ever actually observed. */
    @Test
    void theProfileClockAdoptsTheObservedScalarAge() {
        ReputationSavedData data = ReputationSavedData.loadPayload(
                asFormatTwo(legacyStore(TestFixtures.PLAYER_A)));
        IncidentRecord rescue = data.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow()
                .incident(UUID.nameUUIDFromBytes(("r" + TestFixtures.PLAYER_A).getBytes()))
                .orElseThrow();

        assertEquals(14 * DAY, rescue.decayElapsedTicks());
        assertEquals(14 * DAY, rescue.profileElapsedTicks(),
                "starting at zero would hand an old deed a brand new lifetime");
    }

    // ------------------------------------------------------------------
    // §19.2 — conservative historical enrichment
    // ------------------------------------------------------------------

    private static ReputationSavedData migratedAndEnriched(UUID... players) {
        ReputationSavedData data = ReputationSavedData.loadPayload(asFormatTwo(legacyStore(players)));
        while (data.isProfileMigrationPending()) {
            data.advanceProfileMigration();
        }
        return data;
    }

    /** What the manifest can speak for, aged to the record's own clock, at full historical credit. */
    @Test
    void enrichmentReconstructsOnlyWhatTheManifestAndModesAllow() {
        ReputationSavedData data = migratedAndEnriched(TestFixtures.PLAYER_A);
        CommunityReputationRecord community = data.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow();

        IncidentProfileEvidence rescue = community
                .incident(UUID.nameUUIDFromBytes(("r" + TestFixtures.PLAYER_A).getBytes()))
                .orElseThrow().profileEvidence().orElseThrow();
        assertEquals(IncidentProfileEvidence.Origin.LEGACY_ENRICHED, rescue.origin());
        assertEquals(RESCUE_PROFILE, rescue.profileId());
        assertEquals(CreditDecision.Reason.LEGACY_FULL_CREDIT, rescue.credit().reason());
        assertEquals(ProfileMath.FULL_BP, rescue.credit().effectiveBp(),
                "the old system stored no decision, so history takes 100% and nothing is invented");
        IncidentProfileEvidence.Channel bravery = rescue.facet(BRAVERY).orElseThrow();
        assertEquals(ProfileMath.subunits(8), bravery.authored());
        assertEquals(ProfileMath.subunits(8), bravery.credited());
        // Authored 8, lifetime 28 days, observed age 14 days: exactly 4 points (§23.1).
        assertEquals(ProfileMath.subunits(4), bravery.current());

        // The assault's factual channels are reconstructed; its evaluative one never is, because the
        // old save recorded no authoritative context for a moral judgement (§19.2).
        IncidentProfileEvidence assault = community
                .incident(UUID.nameUUIDFromBytes(("a" + TestFixtures.PLAYER_A).getBytes()))
                .orElseThrow().profileEvidence().orElseThrow();
        assertEquals(IncidentProfileEvidence.Origin.LEGACY_ENRICHED, assault.origin());
        assertTrue(assault.recognition().isPresent(), "it was still a publicly known event");
        assertTrue(assault.facet(BRAVERY).isPresent());
        assertTrue(assault.facet(COMPASSION).isEmpty(),
                "an evaluative channel may not be inferred for old history");
    }

    /** A type the frozen manifest does not name stays unenriched for good, and says so. */
    @Test
    void aCustomTypeStaysExplicitlyUnenriched() {
        ReputationSavedData data = migratedAndEnriched(TestFixtures.PLAYER_A);
        IncidentProfileEvidence custom = data.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow()
                .incident(UUID.nameUUIDFromBytes(("c" + TestFixtures.PLAYER_A).getBytes()))
                .orElseThrow().profileEvidence().orElseThrow();

        assertEquals(IncidentProfileEvidence.Origin.LEGACY_UNENRICHED, custom.origin());
        assertTrue(custom.isEnrichmentCandidate());
        assertEquals(1, data.unenrichedIncidentCount());
        assertEquals(ProfileMigrationState.Coverage.PARTIAL_LEGACY, data.profileCoverage(),
                "an unenrichable record makes coverage partial, never complete");
    }

    /** T37 for profiles: running the pass twice enriches nothing twice and changes no payload. */
    @Test
    void enrichmentIsIdempotentAcrossTwoRuns() {
        ReputationSavedData once = migratedAndEnriched(TestFixtures.PLAYER_A, TestFixtures.PLAYER_B);
        Map<String, String> afterFirst = evidenceByIncident(once);
        long enrichedFirst = once.profileMigrationState().enriched();

        // The second run is the upgraded file being loaded again, which is what a restart does.
        ReputationSavedData twice = ReputationSavedData.loadPayload(once.savePayload(new CompoundTag()));
        assertFalse(twice.isProfileMigrationPending(), "a finished cursor stays finished");
        assertEquals(0, twice.advanceProfileMigration(), "and enriches nothing a second time");

        assertEquals(afterFirst, evidenceByIncident(twice), "no payload may differ by a byte");
        assertEquals(enrichedFirst, twice.profileMigrationState().enriched());
        assertEquals(once.score(TestFixtures.PLAYER_A, HOME), twice.score(TestFixtures.PLAYER_A, HOME));
    }

    /**
     * Interrupted half way through the cursor, restarted from the file, and finished: the result is
     * byte-identical to a pass that was never interrupted.
     *
     * <p>{@code setDirty} is not a durable commit, so this is the case that actually happens. The
     * budget is one player per pass, so there is a real half-finished state to save and reload.
     */
    @Test
    void enrichmentSurvivesAnInterruptionMidCursor() {
        ReputationSavedData straightThrough =
                migratedAndEnriched(TestFixtures.PLAYER_A, TestFixtures.PLAYER_B);

        ReputationSavedData interrupted = ReputationSavedData.loadPayload(
                asFormatTwo(legacyStore(TestFixtures.PLAYER_A, TestFixtures.PLAYER_B)));
        assertTrue(interrupted.advanceProfileMigration(1) > 0, "one player's worth of work");
        assertTrue(interrupted.isProfileMigrationPending(), "and the rest still owed");
        assertEquals(ProfileMigrationState.Coverage.MIGRATING, interrupted.profileCoverage());
        assertTrue(interrupted.profileMigrationState().cursor().isPresent());

        // The crash: everything written so far is on disk, the pass is not finished.
        ReputationSavedData resumed = ReputationSavedData.loadPayload(interrupted.savePayload(new CompoundTag()));
        assertTrue(resumed.isProfileMigrationPending(), "the cursor has to survive the restart");
        while (resumed.isProfileMigrationPending()) {
            resumed.advanceProfileMigration(1);
        }

        assertEquals(evidenceByIncident(straightThrough), evidenceByIncident(resumed));
        assertEquals(straightThrough.score(TestFixtures.PLAYER_B, HOME),
                resumed.score(TestFixtures.PLAYER_B, HOME));
    }

    /**
     * Repeating an already-performed pass whose progress never reached disk is harmless.
     *
     * <p>This is the same guarantee from the other side: the enrichment decision is a property of the
     * payload, not of the cursor, so a save that lost the cursor re-runs the pass and arrives at the
     * same state rather than enriching twice.
     */
    @Test
    void aPassWhoseProgressWasNeverWrittenIsSimplyPerformedAgain() {
        ReputationSavedData data = ReputationSavedData.loadPayload(
                asFormatTwo(legacyStore(TestFixtures.PLAYER_A)));
        data.advanceProfileMigration();
        Map<String, String> afterPass = evidenceByIncident(data);

        // The cursor is thrown away, as an unwritten setDirty would: the pass runs over the same
        // records again.
        data.openProfileMigrationForTest(0L, null);
        data.advanceProfileMigration();

        assertEquals(afterPass, evidenceByIncident(data));
    }

    /** Coverage may never read complete while a cursor remains, however much has been done. */
    @Test
    void coverageNeverReportsCompleteWhileTheCursorRemains() {
        ReputationSavedData data = ReputationSavedData.loadPayload(
                asFormatTwo(legacyStore(TestFixtures.PLAYER_A, TestFixtures.PLAYER_B)));

        assertEquals(ProfileMigrationState.Coverage.MIGRATING, data.profileCoverage());
        data.advanceProfileMigration(1);
        assertEquals(ProfileMigrationState.Coverage.MIGRATING, data.profileCoverage());
        while (data.isProfileMigrationPending()) {
            data.advanceProfileMigration(1);
        }
        assertEquals(ProfileMigrationState.Coverage.PARTIAL_LEGACY, data.profileCoverage(),
                "a finished conservative pass is partial history, not complete history");
    }

    /** Enrichment is quiet: no standing, no title, no receipt, no revision (§19.3). */
    @Test
    void enrichmentAwardsNothing() {
        ReputationSavedData data = ReputationSavedData.loadPayload(
                asFormatTwo(legacyStore(TestFixtures.PLAYER_A)));
        PlayerReputationRecord player = data.player(TestFixtures.PLAYER_A).orElseThrow();
        CommunityReputationRecord community = player.community(HOME).orElseThrow();
        int score = community.score();
        long revision = community.revision();
        int titles = community.titles().size();
        int receipts = player.receiptCount();

        while (data.isProfileMigrationPending()) {
            data.advanceProfileMigration();
        }

        assertEquals(score, community.score());
        assertEquals(revision, community.revision());
        assertEquals(titles, community.titles().size());
        assertEquals(receipts, player.receiptCount());
        assertEquals(0, community.creditTrackers().groupCount(),
                "trackers start empty: anti-grind accounting applies prospectively (§19.2)");
    }

    /** With no profile content published, the pass does nothing and keeps the work owed. */
    @Test
    void enrichmentDefersWhileTheDatapackIsUnpublished() {
        ProfileRegistryBundle.clear();
        ReputationSavedData data = ReputationSavedData.loadPayload(
                asFormatTwo(legacyStore(TestFixtures.PLAYER_A)));

        assertEquals(0, data.advanceProfileMigration(), "nothing to enrich against yet");
        assertTrue(data.isProfileMigrationPending(), "so the pass stays owed");
        assertEquals(ProfileMigrationState.Coverage.MIGRATING, data.profileCoverage());

        publishProfiles();
        assertTrue(data.advanceProfileMigration() > 0, "and runs once the content arrives");
    }

    // ------------------------------------------------------------------
    // §19.4 — bounds and quarantine
    // ------------------------------------------------------------------

    private static CompoundTag validPayload() {
        return IncidentProfileEvidence.of(IncidentProfileEvidence.Origin.LIVE, RESCUE_PROFILE, 42L, 1L,
                        Optional.of(new IncidentProfileEvidence.Channel(Optional.empty(),
                                ProfileMath.subunits(6), ProfileMath.subunits(6),
                                ProfileMath.subunits(6), 56 * DAY, DAY,
                                IncidentProfileDefinition.ResolutionMode.RECOGNITION,
                                IncidentProfileDefinition.ResolutionMultipliers.DEFAULT)),
                        List.of(), CreditDecision.unlimited(CreditDecision.Reason.NO_POLICY))
                .save();
    }

    @Test
    void aValidPayloadRoundTripsExactly() {
        CompoundTag tag = validPayload();
        IncidentProfileEvidence loaded = IncidentProfileEvidence.load(tag).orElseThrow();
        assertEquals(tag, loaded.save(), "the payload must be its own fixed point");
    }

    /** A declared length outside the bound is refused before anything is allocated from it. */
    @Test
    void anOversizeOrNegativeChannelCountIsRefused() {
        CompoundTag oversize = validPayload();
        oversize.putInt("n", Integer.MAX_VALUE);
        assertTrue(IncidentProfileEvidence.load(oversize).isEmpty(),
                "a declared count above the bound must never reach an allocation");

        CompoundTag negative = validPayload();
        negative.putInt("n", -1);
        assertTrue(IncidentProfileEvidence.load(negative).isEmpty());

        // A count that disagrees with the list beside it is equally unusable: one of the two is a lie,
        // and the reader cannot tell which.
        CompoundTag mismatched = validPayload();
        mismatched.putInt("n", 4);
        assertTrue(IncidentProfileEvidence.load(mismatched).isEmpty());
    }

    /** More facet channels than §10.5 allows, honestly declared, is still refused. */
    @Test
    void moreChannelsThanTheBoundAllowsIsRefused() {
        CompoundTag tag = validPayload();
        ListTag channels = new ListTag();
        for (int i = 0; i < IncidentProfileEvidence.MAX_CHANNELS + 2; i++) {
            CompoundTag channel = new CompoundTag();
            channel.putString("f", "mcareputation:facet_" + i);
            channel.putLong("a", ProfileMath.subunits(1));
            channel.putLong("c", ProfileMath.subunits(1));
            channel.putLong("u", ProfileMath.subunits(1));
            channel.putLong("lt", 28 * DAY);
            channel.putLong("st", DAY);
            channel.putString("mode", "historical");
            CompoundTag bp = new CompoundTag();
            bp.putInt("ap", 7500);
            bp.putInt("at", 2500);
            bp.putInt("fo", 0);
            bp.putInt("di", 0);
            channel.put("rbp", bp);
            channels.add(channel);
        }
        tag.put("ch", channels);
        tag.putInt("n", channels.size());
        assertTrue(IncidentProfileEvidence.load(tag).isEmpty());
    }

    /** A malformed save may not smuggle a larger authored value into the active model (§9.6). */
    @Test
    void anAuthoredValueBeyondTheHardBoundIsRefused() {
        CompoundTag tag = validPayload();
        tag.getList("ch", 10).getCompound(0)
                .putLong("a", IncidentProfileEvidence.MAX_CHANNEL_SUBUNITS + 1);
        assertTrue(IncidentProfileEvidence.load(tag).isEmpty());

        CompoundTag negativeRecognition = validPayload();
        negativeRecognition.getList("ch", 10).getCompound(0).putLong("a", -ProfileMath.subunits(6));
        assertTrue(IncidentProfileEvidence.load(negativeRecognition).isEmpty(),
                "negative recognition is not a concept (§7.1)");

        CompoundTag grown = validPayload();
        grown.getList("ch", 10).getCompound(0).putLong("u", ProfileMath.subunits(60));
        assertTrue(IncidentProfileEvidence.load(grown).isEmpty(),
                "a current value above the credited one claims the deed grew after acceptance");
    }

    /** A stub that carries quantities, an unknown origin, or an unknown credit reason is refused. */
    @Test
    void aPayloadThatContradictsItsOwnMarkerIsRefused() {
        CompoundTag smuggled = validPayload();
        smuggled.putString("origin", "legacy_unenriched");
        assertTrue(IncidentProfileEvidence.load(smuggled).isEmpty(),
                "an unenriched marker with quantities would be read two different ways");

        CompoundTag unknownOrigin = validPayload();
        unknownOrigin.putString("origin", "something_new");
        assertTrue(IncidentProfileEvidence.load(unknownOrigin).isEmpty());

        CompoundTag unknownReason = validPayload();
        unknownReason.getCompound("credit").putString("why", "because");
        assertTrue(IncidentProfileEvidence.load(unknownReason).isEmpty(),
                "an unrecognised reason must not be read as full credit");

        CompoundTag impossibleCredit = validPayload();
        impossibleCredit.getCompound("credit").putInt("gbp", 2500);
        impossibleCredit.getCompound("credit").putInt("ebp", 10000);
        assertTrue(IncidentProfileEvidence.load(impossibleCredit).isEmpty(),
                "an effective percentage above both ceilings is credit no policy could grant");

        CompoundTag risingResolution = validPayload();
        risingResolution.getList("ch", 10).getCompound(0).getCompound("rbp").putInt("at", 10000);
        assertTrue(IncidentProfileEvidence.load(risingResolution).isEmpty(),
                "a resolution progression may never increase a magnitude (§12.1)");
    }

    /** §19.4: the payload is quarantined, the deed is not. */
    @Test
    void aMalformedPayloadIsQuarantinedAndItsScalarIncidentSurvives() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        CommunityReputationRecord community = data.getOrCreatePlayer(TestFixtures.PLAYER_A)
                .getOrCreate(HOME);
        UUID id = UUID.nameUUIDFromBytes("damaged".getBytes());
        IncidentRecord rescue = incident(id, RESCUE, TestFixtures.PLAYER_A, 8,
                IncidentVisibility.VILLAGE, 0L);
        rescue.attachProfileEvidence(IncidentProfileEvidence.load(validPayload()).orElseThrow());
        community.addIncident(rescue);
        community.recomputeScore(MIN, MAX);
        int score = community.score();

        CompoundTag tag = data.savePayload(new CompoundTag());
        CompoundTag stored = tag.getCompound("players")
                .getCompound(TestFixtures.PLAYER_A.toString())
                .getList("communities", 10).getCompound(0)
                .getList("incidents", 10).getCompound(0);
        assertTrue(stored.contains("profile"), "the fixture has to carry a payload to damage");
        stored.getCompound("profile").putInt("n", 99);

        SaveQuarantine.clear();
        ReputationSavedData loaded = ReputationSavedData.loadPayload(tag);

        IncidentRecord survivor = loaded.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow().incident(id).orElseThrow();
        assertEquals(8, survivor.baseDelta(), "the deed happened either way");
        assertEquals(score, loaded.score(TestFixtures.PLAYER_A, HOME));
        assertTrue(survivor.profileEvidence().isEmpty(), "and its payload was not guessed at");
        assertEquals(1, SaveQuarantine.profilePayloadCount());
        assertTrue(SaveQuarantine.report().contains("profile"));
        assertEquals(ProfileMigrationState.Coverage.PARTIAL_LEGACY, loaded.profileCoverage(),
                "a quarantined payload makes coverage incomplete, not zero adverse evidence");
    }

    // ------------------------------------------------------------------
    // §10.5 — bounded trackers
    // ------------------------------------------------------------------

    private static CreditWindowTrackers trackers() {
        return CreditWindowTrackers.empty();
    }

    @Test
    void anOrdinalCountsPriorAcceptedOperationsInTheWindow() {
        CreditWindowTrackers trackers = trackers();

        assertEquals(0, trackers.consume(GROUP, 14 * DAY, Optional.empty(), false, 100L).groupOrdinal());
        assertEquals(1, trackers.peek(GROUP, Optional.empty(), false, 100L).groupOrdinal());
        assertEquals(1, trackers.consume(GROUP, 14 * DAY, Optional.empty(), false, 200L).groupOrdinal());
        assertEquals(2, trackers.consume(GROUP, 14 * DAY, Optional.empty(), false, 300L).groupOrdinal());

        // Past the window, the allowance starts again — which is what a window is for.
        assertEquals(0, trackers.consume(GROUP, 14 * DAY, Optional.empty(), false, 100L + 14 * DAY)
                .groupOrdinal());
    }

    /** §10.3: a rewound clock cannot reset an allowance, because the watermark only moves forward. */
    @Test
    void aRewoundClockCannotResetAnAllowance() {
        CreditWindowTrackers trackers = trackers();
        trackers.consume(GROUP, 14 * DAY, Optional.empty(), false, 100L);
        trackers.consume(GROUP, 14 * DAY, Optional.empty(), false, 100L + 13 * DAY);

        // /time set into the past, or an old event delivered late.
        assertEquals(2, trackers.peek(GROUP, Optional.empty(), false, 0L).groupOrdinal());
        assertEquals(2, trackers.consume(GROUP, 14 * DAY, Optional.empty(), false, 0L).groupOrdinal());
    }

    /** The 65th group finds capacity exhausted, takes zero credit, and evicts nothing. */
    @Test
    void theSixtyFifthGroupOverflowsConservativelyWithoutEvictingALiveTracker() {
        CreditWindowTrackers trackers = trackers();
        for (int i = 0; i < 64; i++) {
            trackers.consume(ResourceLocation.fromNamespaceAndPath("mcareputation", "group_" + i), 14 * DAY,
                    Optional.empty(), false, 100L);
        }
        assertEquals(64, trackers.groupCount());
        assertFalse(trackers.isOverflowing());

        ResourceLocation overflowing = ResourceLocation.fromNamespaceAndPath("mcareputation", "group_64");
        assertTrue(trackers.peek(overflowing, Optional.empty(), false, 100L).capacityOverflow(),
                "the peek and the commit have to agree about capacity");
        CreditWindowTrackers.CreditWindow window =
                trackers.consume(overflowing, 14 * DAY, Optional.empty(), false, 100L);

        assertTrue(window.capacityOverflow());
        assertEquals(64, trackers.groupCount(), "no live counter may be dropped to make room");
        assertTrue(trackers.isOverflowing());
        assertEquals(100L, trackers.overflowSinceGameTime());
        // The conservative decision: no new positive credit, and adverse effects untouched.
        CreditDecision decision = dev.otectus.mcareputation.credit.CreditResolver.resolve(
                Optional.of(new CreditPolicy(overflowing, 14 * DAY, List.of(10000), 10000,
                        CreditPolicy.Scope.PLAYER_COMMUNITY, Optional.empty())),
                true, window.groupOrdinal(), window.subjectOrdinal(), window.capacityOverflow());
        assertEquals(CreditDecision.Reason.CAPACITY_OVERFLOW, decision.reason());
        assertEquals(0, decision.applyTo(ProfileMath.subunits(8)));
        assertEquals(-ProfileMath.subunits(8), decision.applyTo(-ProfileMath.subunits(8)),
                "overflow never makes wrongdoing cheaper (I07)");
    }

    /** The 129th subject overflows the same way, and the group counters stay intact. */
    @Test
    void theHundredAndTwentyNinthSubjectOverflowsConservatively() {
        CreditWindowTrackers trackers = trackers();
        for (int i = 0; i < 128; i++) {
            trackers.consume(GROUP, 14 * DAY, Optional.of("subject-" + i), true, 100L);
        }
        assertEquals(128, trackers.subjectCount());
        assertFalse(trackers.isOverflowing());

        CreditWindowTrackers.CreditWindow window =
                trackers.consume(GROUP, 14 * DAY, Optional.of("subject-128"), true, 100L);

        assertTrue(window.capacityOverflow());
        assertEquals(128, trackers.subjectCount(), "never LRU, never a restored allowance");
        assertEquals(1, trackers.groupCount());
        assertEquals(129, trackers.peek(GROUP, Optional.of("subject-0"), true, 100L).groupOrdinal(),
                "and every one of those rotations still spent the group allowance it was evading");
    }

    /** Overflow lifts only when a window actually ends, never because a slot was wanted. */
    @Test
    void overflowLiftsWhenAWindowEndsAndNotBefore() {
        CreditWindowTrackers trackers = trackers();
        for (int i = 0; i < 64; i++) {
            trackers.consume(ResourceLocation.fromNamespaceAndPath("mcareputation", "group_" + i), 14 * DAY,
                    Optional.empty(), false, 100L);
        }
        trackers.consume(ResourceLocation.fromNamespaceAndPath("mcareputation", "group_64"), 14 * DAY,
                Optional.empty(), false, 100L);
        assertTrue(trackers.isOverflowing());

        assertEquals(0, trackers.dropExpired(200L), "nothing has expired yet");
        assertTrue(trackers.isOverflowing());

        assertTrue(trackers.dropExpired(100L + 14 * DAY) > 0);
        assertFalse(trackers.isOverflowing(), "room again means the conservative decision is over");
    }

    /** Counters, windows and watermarks survive a restart; an unwritten counter is free credit. */
    @Test
    void trackersSurviveARoundTripExactly() {
        ReputationSavedData data = ReputationSavedData.createForTest();
        CommunityReputationRecord community = data.getOrCreatePlayer(TestFixtures.PLAYER_A)
                .getOrCreate(HOME);
        community.creditTrackers().consume(GROUP, 14 * DAY,
                Optional.of(TestFixtures.VILLAGER_1.toString()), true, 100L);
        community.creditTrackers().consume(GROUP, 14 * DAY,
                Optional.of(TestFixtures.VILLAGER_1.toString()), true, 200L);
        assertFalse(community.isEmpty(), "a record holding only live anti-farm state must be saved");

        ReputationSavedData reloaded = ReputationSavedData.loadPayload(data.savePayload(new CompoundTag()));
        CreditWindowTrackers restored = reloaded.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow().creditTrackers();

        assertEquals(2, restored.peek(GROUP, Optional.of(TestFixtures.VILLAGER_1.toString()), true,
                200L).groupOrdinal(), "a restart may not hand back a spent allowance");
        assertEquals(2, restored.peek(GROUP, Optional.of(TestFixtures.VILLAGER_1.toString()), true,
                200L).subjectOrdinal().orElseThrow());
        assertEquals(Optional.of(100L + 14 * DAY), restored.windowEnd(GROUP),
                "and the window it froze keeps its original duration");
    }

    /** A file carrying more trackers than the bound is truncated <em>with</em> the overflow decision. */
    @Test
    void anOverfullTrackerFileKeepsTheConservativeDecision() {
        CreditWindowTrackers trackers = trackers();
        for (int i = 0; i < 64; i++) {
            trackers.consume(ResourceLocation.fromNamespaceAndPath("mcareputation", "group_" + i), 14 * DAY,
                    Optional.empty(), false, 100L);
        }
        CompoundTag tag = trackers.save();
        ListTag groups = tag.getList("groups", 10);
        CompoundTag extra = groups.getCompound(0).copy();
        extra.putString("g", "mcareputation:group_smuggled");
        groups.add(extra);
        tag.putInt("gn", groups.size());

        CreditWindowTrackers loaded = CreditWindowTrackers.load(tag);

        assertEquals(64, loaded.groupCount());
        assertTrue(loaded.isOverflowing(),
                "dropping anti-farm state and then granting full credit is the exploit itself");
        assertNotEquals(0L, loaded.overflowSinceGameTime());
    }
}
