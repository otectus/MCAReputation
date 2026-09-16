package dev.otectus.mcareputation.api.profile;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.McaReputationApi;
import dev.otectus.mcareputation.api.ReputationCapabilities;
import dev.otectus.mcareputation.profile.IncidentProfileEvidence;
import dev.otectus.mcareputation.profile.ProfileMath;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.reputation.ProfileService;
import dev.otectus.mcareputation.reputation.ReputationPolicy;
import dev.otectus.mcareputation.state.ReputationSavedData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §14.4's negotiation, and the one distinction it turns on.
 *
 * <p>{@code ReputationCapabilities.features()} answers "should I call this at all", so a profile
 * feature appears there only while it can actually answer: advertising it with profiles switched off
 * or no content published would make a companion treat an unavailable result as a negative fact about
 * the player and skip its authored fallback. {@link ProfileCapabilities} answers "what does this build
 * support, and why can it not answer right now", so it keeps reporting {@code supported} through a
 * temporary disablement — which is the half §14.4 forbids withdrawing.
 */
class ProfileCapabilityTest {

    @AfterEach
    void tearDown() {
        ProfileRegistryBundle.clear();
        McaReputationConfig.TestOverrides.reset();
    }

    private static Set<String> features() {
        return McaReputationApi.capabilities(null).features();
    }

    // ------------------------------------------------------------------
    // The advertised feature set
    // ------------------------------------------------------------------

    @Test
    void theProfileFeaturesAreAdvertisedOnceContentIsPublished() {
        TestFixtures.publishProfile(TestFixtures.profile(null), null,
                Map.of(TestFixtures.FACET, TestFixtures.facet()));
        Set<String> features = features();
        assertTrue(features.contains(ReputationCapabilities.FEATURE_PROFILE_SNAPSHOT));
        assertTrue(features.contains(ReputationCapabilities.FEATURE_SPEAKER_PROFILE));
        assertTrue(features.contains(ReputationCapabilities.FEATURE_REPEAT_CREDIT));
        assertTrue(features.contains(ReputationCapabilities.FEATURE_PROFILED_DELIVERY));
        assertTrue(features.contains(ReputationCapabilities.FEATURE_PROFILE_CHANGE));
    }

    @Test
    void noPublishedContentMeansNoProfileFeatureIsAdvertised() {
        ProfileRegistryBundle.clear();
        Set<String> features = features();
        for (String feature : ProfileService.FEATURES) {
            assertFalse(features.contains(feature),
                    feature + " cannot answer with no content published, so it is not advertised");
        }
    }

    @Test
    void theThirteenOlderFeaturesAreUnconditionalAndUnchanged() {
        ProfileRegistryBundle.clear();
        Set<String> features = features();
        assertTrue(features.containsAll(List.of(
                ReputationCapabilities.FEATURE_STANDING_CHANGE,
                ReputationCapabilities.FEATURE_EFFECTIVE_AUTHORITY,
                ReputationCapabilities.FEATURE_DUPLICATE_IDENTITY,
                ReputationCapabilities.FEATURE_SUPERSEDE,
                ReputationCapabilities.FEATURE_OCCURRENCE_TIME,
                ReputationCapabilities.FEATURE_RECEIPTS,
                ReputationCapabilities.FEATURE_DELIVERY,
                ReputationCapabilities.FEATURE_READ_ONLY_LOOKUP,
                ReputationCapabilities.FEATURE_SPEAKER_QUERY,
                ReputationCapabilities.FEATURE_BOUND_RESOLUTION,
                ReputationCapabilities.FEATURE_TITLE_SYNC,
                ReputationCapabilities.FEATURE_LADDER_HIGH_WATER,
                ReputationCapabilities.FEATURE_GOSSIP_STORY)),
                "the 0.5.0 surface is additive, so nothing here may be withdrawn");
        assertEquals(13, features.size(), "and nothing else is advertised while profiles are not live");
    }

    /**
     * Two on this branch, not one: the {@code api.event} types extend NeoForge's {@code Event}, so a
     * bridge compiled against the Forge artifact never linked here. The number is this branch's own
     * lineage and 0.6.0 does not move it, for exactly the Forge reason - Quests and Conversations
     * hard-refuse any other value, and the profile surface is purely additive.
     */
    @Test
    void theApiVersionStaysTwoOnThisBranch() {
        assertEquals(2, McaReputationApi.getApiVersion(),
                "Quests and Conversations hard-refuse any other value; profiles are additive");
        assertEquals(2, McaReputationApi.capabilities(null).apiVersion());
    }

    /**
     * The config switch reaching the advertised feature set, which is the end of P7's wiring: a
     * companion negotiating through {@code capabilities()} must see the operator's decision, not the
     * shipped default.
     */
    @Test
    void switchingProfilesOffInTheConfigWithdrawsTheAdvertisedFeatures() {
        TestFixtures.publishProfile(TestFixtures.profile(null), null,
                Map.of(TestFixtures.FACET, TestFixtures.facet()));
        assertTrue(features().contains(ReputationCapabilities.FEATURE_PROFILE_SNAPSHOT));

        McaReputationConfig.TestOverrides.profiles = false;
        Set<String> disabled = features();
        for (String feature : ProfileService.FEATURES) {
            assertFalse(disabled.contains(feature),
                    feature + " cannot answer while profiles are switched off in the config");
        }
        assertEquals(13, disabled.size(), "and the 0.5.0 surface is untouched by that switch");

        // The static half still reports support, and now reports the operator's switches too.
        ProfileCapabilities report = McaReputationApi.profileCapabilities(null);
        assertTrue(report.supported());
        assertFalse(report.enabled());
        assertTrue(report.readinessReason().isPresent());
    }

    /** The two other COMMON switches are reported from the config rather than from the defaults. */
    @Test
    void theRepeatCreditAndFacetOpinionSwitchesAreReportedFromTheConfig() {
        TestFixtures.publishProfile(TestFixtures.profile(null), null,
                Map.of(TestFixtures.FACET, TestFixtures.facet()));
        McaReputationConfig.TestOverrides.repeatCredit = false;
        McaReputationConfig.TestOverrides.facetOpinion = false;
        McaReputationConfig.TestOverrides.maxFacetOpinionAdjustment = 11;

        ProfileCapabilities report = McaReputationApi.profileCapabilities(null);
        assertFalse(report.repeatCreditEnabled());
        assertFalse(report.facetOpinionEnabled());
        assertEquals(11, report.maxFacetOpinionAdjustment());
        assertTrue(report.enabled(), "and none of those three is the profile master switch");
    }

    @Test
    void switchedOffProfilesAreNotLiveEvenWithContentPublished() {
        TestFixtures.publishProfile(TestFixtures.profile(null), null);
        assertTrue(ProfileService.live(ReputationPolicy.defaults()));
        assertFalse(ProfileService.live(ReputationPolicy.defaults().withProfilesEnabled(false)));
        assertFalse(ProfileService.live(ReputationPolicy.defaults().withEnabled(false)));
        assertFalse(ProfileService.live(null));
    }

    // ------------------------------------------------------------------
    // The dynamic report
    // ------------------------------------------------------------------

    @Test
    void supportIsReportedThroughATemporaryDisablement() {
        TestFixtures.publishProfile(TestFixtures.profile(null), null,
                Map.of(TestFixtures.FACET, TestFixtures.facet()));
        ProfileCapabilities disabled = ProfileService.capabilities(
                ReputationPolicy.defaults().withProfilesEnabled(false),
                ReputationSavedData.createForTest());
        assertTrue(disabled.supported(), "the binary still implements profiles");
        assertFalse(disabled.enabled());
        assertTrue(disabled.contentPublished());
        assertFalse(disabled.ready());
        assertTrue(disabled.readinessReason().isPresent());
        assertEquals(ProfileService.FEATURES.size(), disabled.features().size(),
                "stable support information is not withdrawn by a config switch");
        assertTrue(disabled.has(ReputationCapabilities.FEATURE_PROFILE_SNAPSHOT));
    }

    @Test
    void aReadyBuildReportsItsBoundsAndItsPublishedFacets() {
        TestFixtures.publishProfile(TestFixtures.profile(null), null,
                Map.of(TestFixtures.FACET, TestFixtures.facet()));
        ProfileCapabilities ready = ProfileService.capabilities(ReputationPolicy.defaults(),
                ReputationSavedData.createForTest());
        assertTrue(ready.ready());
        assertTrue(ready.readinessReason().isEmpty());
        assertEquals(IncidentProfileEvidence.SCHEMA_VERSION, ready.schemaVersion());
        assertEquals(ProfileMath.MAX_RECOGNITION, ready.recognitionCap());
        assertEquals(ProfileMath.MAX_FACET_POINTS, ready.facetPointCap());
        assertEquals(ProfileMath.MAX_DOMINANT_FACETS, ready.maxDominantFacets());
        assertEquals(ReputationPolicy.DEFAULT_MAX_FACET_OPINION_ADJUSTMENT,
                ready.maxFacetOpinionAdjustment());
        assertEquals(List.of(TestFixtures.FACET), ready.publishedFacets());
        assertEquals(ProfileCoverage.COMPLETE_SINCE_RECORD_START, ready.coverage());
    }

    @Test
    void withNoStoreCoverageIsReportedConservatively() {
        TestFixtures.publishProfile(TestFixtures.profile(null), null);
        ProfileCapabilities noWorld = ProfileService.capabilities(ReputationPolicy.defaults(), null);
        assertEquals(ProfileCoverage.PARTIAL_LEGACY, noWorld.coverage(),
                "with no store to consult, a complete history cannot be claimed");
        assertTrue(noWorld.readinessReason().isPresent());
    }

    @Test
    void aMigratingStoreIsReportedAsMigrating() {
        TestFixtures.publishProfile(TestFixtures.profile(null), null);
        ReputationSavedData data = ReputationSavedData.createForTest();
        data.openProfileMigrationForTest(4L, TestFixtures.PLAYER_B);
        ProfileCapabilities migrating = ProfileService.capabilities(ReputationPolicy.defaults(), data);
        assertEquals(ProfileCoverage.MIGRATING, migrating.coverage());
        assertTrue(migrating.readinessReason().isPresent());
        assertTrue(migrating.ready(), "a migrating store still answers; the coverage says how well");
    }

    @Test
    void theProfileCapabilityEntryPointSurvivesWithoutAServer() {
        ProfileCapabilities capabilities = McaReputationApi.profileCapabilities(null);
        assertTrue(capabilities.supported());
        assertEquals(ProfileService.FEATURES.size(), capabilities.features().size());
    }
}
