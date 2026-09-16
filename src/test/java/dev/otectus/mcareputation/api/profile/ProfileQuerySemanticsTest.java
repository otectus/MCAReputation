package dev.otectus.mcareputation.api.profile;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.SpeakerContext;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.reputation.ProfileService;
import dev.otectus.mcareputation.reputation.ReputationPolicy;
import dev.otectus.mcareputation.reputation.TestDeliverySeam;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §14.5's predicate table, and the three ways it is required to fail.
 *
 * <p>Every case here is one an authored gate gets wrong if the implementation is merely permissive:
 * a typo that opens a door, an absence of evidence read as proof of innocence, a speaker-scoped
 * question quietly answered from the whole village, and an upper bound answered from a history that
 * is missing deeds. The distinction each assertion defends is between {@code false} and
 * <em>unavailable</em> — one closes the gate, the other lets the authored fallback run — so the tests
 * assert the availability as well as the boolean.
 */
class ProfileQuerySemanticsTest {

    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;
    private static final ResourceLocation UNKNOWN_FACET =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "no_such_facet");

    private TestDeliverySeam seam;

    @BeforeEach
    void setUp() {
        seam = new TestDeliverySeam().policy(ReputationPolicy.defaults()).gameTime(TestFixtures.DAY);
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT, TestFixtures.definition(0,
                IncidentVisibility.VILLAGE, DecayPolicy.NONE, TestFixtures.PROFILE)));
        TestFixtures.publishProfile(TestFixtures.profile(null), null,
                Map.of(TestFixtures.FACET, TestFixtures.facet()));
    }

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
        ProfileRegistryBundle.clear();
        McaReputationConfig.TestOverrides.reset();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** One accepted public deed carrying the test profile: 6 recognition, 8 bravery. */
    private UUID deed(CommunityKey community, Set<UUID> witnesses) {
        return seam.record(new ReputationRequest(null, TestFixtures.PLAYER_A, community,
                TestFixtures.ASSAULT, TestFixtures.SOURCE, Optional.empty(), OptionalInt.empty(),
                Optional.empty(), List.of(), witnesses, Map.of(), seam.gameTime()))
                .incidentId().orElseThrow();
    }

    private ProfileQueryResult<Boolean> matches(ProfileQuery query) {
        return ProfileService.matches(seam.policySnapshot(), seam.store(), TestFixtures.PLAYER_A, HOME,
                query, seam.gameTime());
    }

    private ProfileQueryResult<ProfileSnapshot> profile() {
        return ProfileService.profile(seam.policySnapshot(), seam.store(), TestFixtures.PLAYER_A, HOME,
                seam.gameTime(), false);
    }

    /** Leaves the store with pre-profile history it can never fill in: §19.3's permanent partial. */
    private void markPartialLegacy() {
        seam.store().openProfileMigrationForTest(3L, null);
        seam.store().profileMigrationState().advanced(null, 0L, 0L, 0L);
    }

    // ------------------------------------------------------------------
    // Unknown ids fail closed
    // ------------------------------------------------------------------

    @Test
    void anUnknownFacetIdFailsClosedRatherThanBeingIgnored() {
        deed(HOME, Set.of());
        ProfileQueryResult<Boolean> result = matches(ProfileQuery.builder()
                .facet(ProfileQuery.FacetPredicate.atLeast(UNKNOWN_FACET, 0))
                .build());
        assertEquals(ProfileAvailability.AVAILABLE, result.availability(),
                "a datapack typo is the author's problem to see, not a reason to run the fallback");
        assertFalse(result.orElse(true), "an unknown facet must never satisfy a gate");
        assertEquals(Optional.of("unknown_facet"), result.reason());
    }

    @Test
    void anUnknownRecognitionTierFailsClosed() {
        deed(HOME, Set.of());
        ProfileQueryResult<Boolean> result =
                matches(ProfileQuery.builder().minRecognitionTier("legendary").build());
        assertFalse(result.orElse(true));
        assertEquals(Optional.of("unknown_recognition_tier"), result.reason());
    }

    @Test
    void aKnownRecognitionTierIsCompared() {
        deed(HOME, Set.of());
        assertTrue(matches(ProfileQuery.builder().minRecognitionTier("noticed").build()).orElse(false),
                "six authored recognition points reach the 'noticed' rung");
        assertFalse(matches(ProfileQuery.builder().minRecognitionTier("renowned").build()).orElse(true));
    }

    @Test
    void anInvalidQueryIsUnavailableSoTheFallbackRuns() {
        deed(HOME, Set.of());
        ProfileQuery inverted = new ProfileQuery(OptionalInt.of(40), OptionalInt.of(10),
                Optional.empty(), List.of(), false);
        assertFalse(inverted.valid());
        ProfileQueryResult<Boolean> result = matches(inverted);
        assertEquals(ProfileAvailability.UNRESOLVED, result.availability());
        assertEquals(Optional.of("invalid_query"), result.reason());
        assertTrue(result.value().isEmpty(), "a malformed query has no answer, not a false one");
    }

    @Test
    void aDuplicatedFacetClauseIsRejectedRatherThanSilentlyAnded() {
        ProfileQuery duplicated = ProfileQuery.builder()
                .facet(ProfileQuery.FacetPredicate.atLeast(TestFixtures.FACET, 5))
                .facet(ProfileQuery.FacetPredicate.atMost(TestFixtures.FACET, 1))
                .build();
        assertFalse(duplicated.valid(), "two clauses on one facet is an authoring mistake");
    }

    // ------------------------------------------------------------------
    // Unobserved is not negative evidence
    // ------------------------------------------------------------------

    @Test
    void anUnobservedFacetSatisfiesNoGateByAccident() {
        // A stranger: no record at all, so nothing is known either way.
        ProfileQueryResult<Boolean> upperBound = matches(ProfileQuery.builder()
                .facet(ProfileQuery.FacetPredicate.atMost(TestFixtures.FACET, 0))
                .build());
        assertEquals(ProfileAvailability.AVAILABLE, upperBound.availability());
        assertFalse(upperBound.orElse(true),
                "'nonviolent' must not be satisfied by a village that has never seen this player");
        assertEquals(Optional.of("unobserved_facet"), upperBound.reason());
    }

    @Test
    void theNamedEscapeHatchExpressesNoContraryEvidenceKnown() {
        ProfileQueryResult<Boolean> result = matches(ProfileQuery.builder()
                .facet(new ProfileQuery.FacetPredicate(TestFixtures.FACET, OptionalInt.empty(),
                        OptionalInt.of(0), 1, true))
                .build());
        assertTrue(result.orElse(false),
                "allow_unobserved is the documented way to ask for an absence of adverse evidence");
    }

    @Test
    void anObservedFacetStillNeedsTheEvidenceTheClauseAsksFor() {
        deed(HOME, Set.of());
        ProfileQuery twoDeeds = ProfileQuery.builder()
                .facet(TestFixtures.FACET, 5, 2)
                .build();
        ProfileQueryResult<Boolean> one = matches(twoDeeds);
        assertFalse(one.orElse(true), "one deed cannot satisfy a two-evidence gate");
        assertEquals(Optional.of("insufficient_evidence"), one.reason());

        seam.gameTime(2 * TestFixtures.DAY);
        deed(HOME, Set.of());
        assertTrue(matches(twoDeeds).orElse(false), "two independent deeds do");
    }

    @Test
    void aStrangerWithCompleteHistoryIsAvailableZeroAndUnobserved() {
        ProfileQueryResult<ProfileSnapshot> result = profile();
        assertTrue(result.isAvailable(), "a valid player and community always have an answer");
        ProfileSnapshot snapshot = result.value().orElseThrow();
        assertEquals(0, snapshot.recognition().value());
        assertFalse(snapshot.recognition().observed());
        assertFalse(snapshot.observed());
        assertEquals(ProfileCoverage.COMPLETE_SINCE_RECORD_START, snapshot.coverage(),
                "'available, zero, complete' has to stay distinguishable from 'unavailable'");
        assertTrue(seam.store().player(TestFixtures.PLAYER_A).isEmpty(),
                "asking the question must not write a record down");
    }

    // ------------------------------------------------------------------
    // Coverage
    // ------------------------------------------------------------------

    @Test
    void anUpperBoundRefusesOnPartialLegacyHistory() {
        deed(HOME, Set.of());
        markPartialLegacy();
        ProfileQueryResult<Boolean> result = matches(ProfileQuery.builder().maxRecognition(3).build());
        assertEquals(ProfileAvailability.INCOMPLETE_HISTORY, result.availability());
        assertEquals(Optional.of("partial_legacy_history"), result.reason());
        assertTrue(result.value().isEmpty());
    }

    @Test
    void anUpperBoundMayOptIntoPartialHistoryExplicitly() {
        deed(HOME, Set.of());
        markPartialLegacy();
        ProfileQueryResult<Boolean> result = matches(ProfileQuery.builder()
                .maxRecognition(3)
                .allowPartialHistory(true)
                .build());
        assertEquals(ProfileAvailability.AVAILABLE, result.availability());
        assertFalse(result.orElse(true), "six recognition points exceed a maximum of three");
    }

    @Test
    void anObservedLowerBoundStillAnswersOnPartialLegacyHistory() {
        deed(HOME, Set.of());
        markPartialLegacy();
        ProfileQueryResult<Boolean> result = matches(ProfileQuery.builder().minRecognition(5).build());
        assertEquals(ProfileAvailability.AVAILABLE, result.availability(),
                "missing history can only hide evidence, so observed evidence is still evidence");
        assertTrue(result.orElse(false));
    }

    @Test
    void aRunningMigrationIsReportedAsMigratingRatherThanPartial() {
        deed(HOME, Set.of());
        seam.store().openProfileMigrationForTest(3L, TestFixtures.PLAYER_B);
        assertEquals(ProfileCoverage.MIGRATING, profile().value().orElseThrow().coverage());
        ProfileQueryResult<Boolean> result = matches(ProfileQuery.builder().maxRecognition(3).build());
        assertEquals(ProfileAvailability.MIGRATING, result.availability());
        assertEquals(Optional.of("migrating_history"), result.reason());
    }

    // ------------------------------------------------------------------
    // Switched off and unpublished are not "false"
    // ------------------------------------------------------------------

    @Test
    void disabledProfilesAnswerDisabledNotFalse() {
        deed(HOME, Set.of());
        seam.policy(ReputationPolicy.defaults().withProfilesEnabled(false));
        ProfileQueryResult<Boolean> result = matches(ProfileQuery.builder().minRecognition(1).build());
        assertEquals(ProfileAvailability.DISABLED, result.availability());
        assertEquals(Optional.of("profiles_disabled"), result.reason());
        assertNotEquals(Optional.of(false), result.value(),
                "a switched-off feature is not a negative answer about the player");
    }

    @Test
    void anUnpublishedContentGenerationAnswersUnsupported() {
        deed(HOME, Set.of());
        ProfileRegistryBundle.clear();
        ProfileQueryResult<Boolean> result = matches(ProfileQuery.builder().minRecognition(1).build());
        assertEquals(ProfileAvailability.UNSUPPORTED, result.availability());
        assertEquals(Optional.of("no_profile_content"), result.reason());
    }

    @Test
    void anUnresolvableCommunityIsUnresolved() {
        ProfileQueryResult<ProfileSnapshot> result = ProfileService.profile(seam.policySnapshot(),
                seam.store(), TestFixtures.PLAYER_A, null, seam.gameTime(), false);
        assertEquals(ProfileAvailability.UNRESOLVED, result.availability());
    }

    // ------------------------------------------------------------------
    // The speaker never widens (§13.3)
    // ------------------------------------------------------------------

    @Test
    void aSpeakerQueryWithNoSpeakerContextIsUnavailable() {
        deed(HOME, Set.of());
        ProfileQueryResult<Boolean> result = ProfileService.matchesSpeaker(seam.policySnapshot(),
                seam.store(), TestFixtures.PLAYER_A, HOME, null,
                ProfileQuery.builder().minRecognition(1).build(), seam.gameTime());
        assertEquals(ProfileAvailability.UNRESOLVED, result.availability());
        assertEquals(Optional.of("no_speaker_context"), result.reason());
        assertTrue(result.value().isEmpty(), "a missing speaker has no answer at all");
    }

    @Test
    void aSpeakerQueryNeverFallsBackToTheCommunityAnswer() {
        // Witnessed by villager 1 only, and delivered far enough in the past that no rumour has
        // reached anyone else yet: the community knows, villager 2 does not.
        seam.gameTime(TestFixtures.DAY);
        deed(HOME, Set.of(TestFixtures.VILLAGER_1));
        ProfileQuery gate = ProfileQuery.builder().minRecognition(1).build();

        assertTrue(matches(gate).orElse(false), "the community holds the evidence");

        ProfileQueryResult<Boolean> stranger = ProfileService.matchesSpeaker(seam.policySnapshot(),
                seam.store(), TestFixtures.PLAYER_A, HOME,
                SpeakerContext.of(TestFixtures.VILLAGER_2, false), gate, seam.gameTime());
        assertEquals(ProfileAvailability.AVAILABLE, stranger.availability(),
                "a villager who knows nothing has a real answer, not an unavailable one");
        assertFalse(stranger.orElse(true),
                "a valid zero speaker view must not be answered from the village's profile");
    }

    @Test
    void aWitnessKnowsWhatTheySawAndTheUuidAndEntityPathsAgreeOnIt() {
        seam.gameTime(TestFixtures.DAY);
        deed(HOME, Set.of(TestFixtures.VILLAGER_1));
        ProfileQueryResult<VillagerProfileSnapshot> witness = ProfileService.speakerProfile(
                seam.policySnapshot(), seam.store(), TestFixtures.PLAYER_A, HOME,
                SpeakerContext.of(TestFixtures.VILLAGER_1, true), seam.gameTime());
        assertTrue(witness.isAvailable());
        VillagerProfileSnapshot view = witness.value().orElseThrow();
        assertEquals(1, view.witnessedCount());
        assertEquals(0, view.hearsayCount());
        assertTrue(view.knowsAnything());
        assertTrue(view.knownProfile().recognition().value() > 0);
        assertEquals(VillagerProfileSnapshot.TraitBasis.NEUTRAL_DEFAULT, view.traitBasis(),
                "P5 interprets with neutral authored weights and says so");
    }

    @Test
    void theFacetOpinionTermIsCappedAndSwitchable() {
        seam.gameTime(TestFixtures.DAY);
        deed(HOME, Set.of(TestFixtures.VILLAGER_1));
        VillagerProfileSnapshot weighted = ProfileService.speakerProfile(seam.policySnapshot(),
                seam.store(), TestFixtures.PLAYER_A, HOME,
                SpeakerContext.of(TestFixtures.VILLAGER_1, true), seam.gameTime())
                .value().orElseThrow();
        // 8 bravery points at the facet's 50% authored weight.
        assertEquals(4, weighted.facetAdjustment());
        assertEquals(weighted.baseOpinion() + weighted.facetAdjustment(), weighted.finalOpinion());

        seam.policy(ReputationPolicy.defaults().withMaxFacetOpinionAdjustment(2));
        assertEquals(2, ProfileService.speakerProfile(seam.policySnapshot(), seam.store(),
                        TestFixtures.PLAYER_A, HOME, SpeakerContext.of(TestFixtures.VILLAGER_1, true),
                        seam.gameTime())
                .value().orElseThrow().facetAdjustment(), "the cap is the operator's, not the facet's");

        seam.policy(ReputationPolicy.defaults().withFacetOpinionEnabled(false));
        VillagerProfileSnapshot unweighted = ProfileService.speakerProfile(seam.policySnapshot(),
                seam.store(), TestFixtures.PLAYER_A, HOME,
                SpeakerContext.of(TestFixtures.VILLAGER_1, true), seam.gameTime())
                .value().orElseThrow();
        assertEquals(0, unweighted.facetAdjustment());
        assertEquals(VillagerProfileSnapshot.TraitBasis.DISABLED, unweighted.traitBasis());
    }
}
