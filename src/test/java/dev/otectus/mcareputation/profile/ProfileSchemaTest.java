package dev.otectus.mcareputation.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.otectus.mcareputation.credit.CreditPolicy;
import dev.otectus.mcareputation.data.ReputationReloadListener;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strict-codec half of §9.6: a malformed profile definition must be <b>reported</b>, not
 * defaulted, and the published generation must not depend on the order files happened to load in.
 *
 * <p>Everything goes through {@link ReputationReloadListener#parseStrictForTest} rather than calling
 * the codec directly, so the duplicate-key rule is asserted through the same path the reload uses.
 */
class ProfileSchemaTest {

    private static <T> DataResult<T> parse(Codec<T> codec, String json) {
        return ReputationReloadListener.parseStrictForTest(codec, json);
    }

    private static <T> T ok(Codec<T> codec, String json) {
        DataResult<T> parsed = parse(codec, json);
        assertTrue(parsed.error().isEmpty(),
                () -> "expected a successful parse: " + parsed.error().map(Object::toString).orElse(""));
        return parsed.result().orElseThrow();
    }

    private static String errorOf(DataResult<?> parsed) {
        assertTrue(parsed.error().isPresent(), "expected a parse error");
        return parsed.error().orElseThrow().message();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mcareputation", path);
    }

    // ------------------------------------------------------------------
    // Facets (§9.2)
    // ------------------------------------------------------------------

    private static final String RELIABILITY = """
            {
              "name": { "translate": "mcareputation.facet.reliability" },
              "description": { "translate": "mcareputation.facet.reliability.description" },
              "range": { "min": -100, "max": 100 },
              "positive_label": { "translate": "mcareputation.facet.reliability.dependable" },
              "negative_label": { "translate": "mcareputation.facet.reliability.unreliable" },
              "display_order": 10,
              "label_min_magnitude": 10,
              "label_min_evidence": 2,
              "opinion_weight_bp": 5000
            }
            """;

    @Test
    void theSpecsExampleFacetParses() {
        FacetDefinition facet = ok(FacetDefinition.CODEC, RELIABILITY);
        assertEquals(-100, facet.range().min());
        assertEquals(100, facet.range().max());
        assertEquals(10, facet.displayOrder());
        assertEquals(5000, facet.opinionWeightBp());
        assertTrue(facet.range().bipolar());
    }

    @Test
    void anInvertedRangeIsRejected() {
        assertTrue(errorOf(parse(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": 50, "max": -50}, "positive_label": "p"}
                """)).contains("inverted"));
    }

    @Test
    void aRangeThatDoesNotContainZeroIsRejected() {
        assertTrue(errorOf(parse(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": 10, "max": 100}, "positive_label": "p"}
                """)).contains("contain zero"));
    }

    @Test
    void aRangeOutsideTheHardBoundsIsRejected() {
        assertTrue(errorOf(parse(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": -101, "max": 100}, "positive_label": "p"}
                """)).contains("-100..100"));
    }

    /** §8.1: a unipolar facet's zero is not the opposite trait, so it has no negative half to label. */
    @Test
    void aUnipolarFacetMayNotAuthorANegativeLabel() {
        assertTrue(errorOf(parse(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": 0, "max": 100}, "positive_label": "p",
                 "negative_label": "q"}
                """)).contains("unipolar"));
    }

    @Test
    void aBipolarFacetMustLabelItsNegativeHalf() {
        assertTrue(errorOf(parse(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": -100, "max": 100}, "positive_label": "p"}
                """)).contains("negative_label"));
    }

    @Test
    void anOpinionWeightOutsideTheHardBoundsIsRejected() {
        assertTrue(errorOf(parse(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": 0, "max": 100}, "positive_label": "p",
                 "opinion_weight_bp": 20001}
                """)).contains("opinion_weight_bp"));
        assertTrue(errorOf(parse(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": 0, "max": 100}, "positive_label": "p",
                 "personality_overrides": {"upbeat": -20001}}
                """)).contains("personality_overrides"));
    }

    @Test
    void aLabelThresholdOutsideTheFacetsOwnRangeIsRejected() {
        assertTrue(errorOf(parse(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": 0, "max": 20}, "positive_label": "p",
                 "label_min_magnitude": 30}
                """)).contains("label_min_magnitude"));
    }

    /** §9.6: a duplicate key is reported rather than resolved to the last value. */
    @Test
    void aDuplicateFacetKeyIsRejected() {
        assertTrue(errorOf(parse(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": 0, "max": 100}, "positive_label": "p",
                 "display_order": 10, "display_order": 20}
                """)).contains("duplicate key"));
    }

    @Test
    void aDuplicateKeyIsRejectedInsideANestedObjectToo() {
        assertTrue(errorOf(parse(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": 0, "min": 5, "max": 100}, "positive_label": "p"}
                """)).contains("duplicate key"));
    }

    /** A present-but-malformed optional block must not be replaced by its default (StrictCodecs). */
    @Test
    void aMalformedOptionalBlockIsNotSilentlyDefaulted() {
        assertTrue(errorOf(parse(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": 0, "max": 100}, "positive_label": "p",
                 "personality_overrides": "upbeat"}
                """)).contains("personality_overrides"));
    }

    @Test
    void personalityOverridesAreNormalizedAndOrderStable() {
        FacetDefinition first = ok(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": 0, "max": 100}, "positive_label": "p",
                 "personality_overrides": {"UPBEAT": 6000, "odd": 3500}}
                """);
        FacetDefinition second = ok(FacetDefinition.CODEC, """
                {"name": "n", "range": {"min": 0, "max": 100}, "positive_label": "p",
                 "personality_overrides": {"odd": 3500, "upbeat": 6000}}
                """);
        assertEquals(List.of("odd", "upbeat"), List.copyOf(first.personalityOverrides().keySet()));
        assertEquals(first, second, "authoring order must not change the definition");
        assertEquals(6000, first.opinionWeightFor("Upbeat"));
        assertEquals(0, first.opinionWeightFor("a_personality_nobody_defined"),
                "an unresolved personality uses the default weight");
    }

    @Test
    void aMissingFacetDefinitionDegradesToANeutralStandIn() {
        FacetDefinition unknown = FacetDefinition.unknown(id("gone"));
        assertEquals(0, unknown.opinionWeightBp(), "a removed definition must not move any opinion");
        assertTrue(unknown.labelFor(0).isEmpty());
    }

    @Test
    void zeroIsNeverLabelledAsEitherTrait() {
        FacetDefinition facet = ok(FacetDefinition.CODEC, RELIABILITY);
        assertTrue(facet.labelFor(0).isEmpty());
        assertTrue(facet.labelFor(5).isPresent());
        assertTrue(facet.labelFor(-5).isPresent());
    }

    // ------------------------------------------------------------------
    // Recognition ladders (§7.2, §9.6)
    // ------------------------------------------------------------------

    @Test
    void theShippedRecognitionLadderShapeParses() {
        RecognitionTierSet ladder = ok(RecognitionTierSet.CODEC, """
                {"tiers": [
                  {"id": "unknown", "threshold": 0, "name": "Unknown"},
                  {"id": "noticed", "threshold": 5, "name": "Noticed"},
                  {"id": "famous", "threshold": 180, "name": "Famous"}
                ]}
                """);
        assertEquals("unknown", ladder.tierFor(0).id());
        assertEquals("unknown", ladder.tierFor(4).id());
        assertEquals("noticed", ladder.tierFor(5).id());
        assertEquals("famous", ladder.tierFor(1000).id());
        assertEquals("noticed", ladder.nextTier(0).orElseThrow().id());
        assertTrue(ladder.nextTier(1000).isEmpty());
    }

    @Test
    void nonMonotonicRecognitionThresholdsAreRejected() {
        assertTrue(errorOf(parse(RecognitionTierSet.CODEC, """
                {"tiers": [
                  {"id": "unknown", "threshold": 0, "name": "a"},
                  {"id": "noticed", "threshold": 40, "name": "b"},
                  {"id": "recognized", "threshold": 15, "name": "c"}
                ]}
                """)).contains("ascend strictly"));
    }

    @Test
    void repeatedRecognitionThresholdsAreRejected() {
        assertTrue(errorOf(parse(RecognitionTierSet.CODEC, """
                {"tiers": [
                  {"id": "unknown", "threshold": 0, "name": "a"},
                  {"id": "noticed", "threshold": 0, "name": "b"}
                ]}
                """)).contains("ascend strictly"));
    }

    @Test
    void aLadderWithoutAZeroFloorIsRejected() {
        assertTrue(errorOf(parse(RecognitionTierSet.CODEC, """
                {"tiers": [{"id": "noticed", "threshold": 5, "name": "a"}]}
                """)).contains("threshold 0"));
    }

    @Test
    void aThresholdAboveTheRecognitionMaximumIsRejected() {
        assertTrue(errorOf(parse(RecognitionTierSet.CODEC, """
                {"tiers": [
                  {"id": "unknown", "threshold": 0, "name": "a"},
                  {"id": "mythic", "threshold": 1001, "name": "b"}
                ]}
                """)).contains("0..1000"));
    }

    @Test
    void duplicateRecognitionTierIdsAreRejected() {
        assertTrue(errorOf(parse(RecognitionTierSet.CODEC, """
                {"tiers": [
                  {"id": "unknown", "threshold": 0, "name": "a"},
                  {"id": "unknown", "threshold": 5, "name": "b"}
                ]}
                """)).contains("duplicate"));
    }

    @Test
    void anEmptyLadderIsRejected() {
        assertTrue(parse(RecognitionTierSet.CODEC, "{\"tiers\": []}").error().isPresent());
    }

    // ------------------------------------------------------------------
    // Incident profiles (§9.3, §9.6)
    // ------------------------------------------------------------------

    private static final String RESCUE_PROFILE = """
            {
              "allowed_incidents": ["mcareputation:villager_rescued"],
              "recognition": {
                "points": 6,
                "lifetime_ticks": 1344000,
                "resolution_mode": "recognition"
              },
              "facets": {
                "mcareputation:bravery": {
                  "points": 8,
                  "lifetime_ticks": 672000,
                  "resolution_mode": "historical"
                },
                "mcareputation:compassion": {
                  "points": 5,
                  "lifetime_ticks": 672000,
                  "resolution_mode": "evaluative"
                }
              },
              "credit_class": "commendable",
              "credit_policy": "mcareputation:rescue_service",
              "major_evidence": false
            }
            """;

    @Test
    void theSpecsExampleProfileParses() {
        IncidentProfileDefinition profile = ok(IncidentProfileDefinition.CODEC, RESCUE_PROFILE);
        assertEquals(6, profile.recognition().orElseThrow().points());
        assertEquals(1_344_000L, profile.recognition().orElseThrow().lifetimeTicks());
        assertEquals(IncidentProfileDefinition.ResolutionMode.RECOGNITION,
                profile.recognition().orElseThrow().resolutionMode());
        assertEquals(2, profile.facets().size());
        assertEquals(8, profile.facets().get(id("bravery")).points());
        assertEquals(IncidentProfileDefinition.CreditClass.COMMENDABLE, profile.creditClass());
        assertTrue(profile.discountable());
        assertEquals(ProfileMath.DEFAULT_DECAY_STEP_TICKS, profile.effectiveDecayStepTicks());
        assertTrue(profile.permits(id("villager_rescued")));
        assertFalse(profile.permits(id("quest_completed")), "§9.5: the allowlist is binding");
    }

    /** §9.6: the authored facet map is stored in id order so two authoring orders agree exactly. */
    @Test
    void facetOrderIsStableAcrossAuthoringOrders() {
        IncidentProfileDefinition first = ok(IncidentProfileDefinition.CODEC, """
                {"facets": {
                  "mcareputation:violence": {"points": 8, "lifetime_ticks": 672000},
                  "mcareputation:compassion": {"points": -4, "lifetime_ticks": 672000}
                }}
                """);
        IncidentProfileDefinition second = ok(IncidentProfileDefinition.CODEC, """
                {"facets": {
                  "mcareputation:compassion": {"points": -4, "lifetime_ticks": 672000},
                  "mcareputation:violence": {"points": 8, "lifetime_ticks": 672000}
                }}
                """);
        assertEquals(List.of(id("compassion"), id("violence")),
                List.copyOf(first.facets().keySet()));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void anEmptyProfileIsRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, "{}"))
                .contains("must author recognition"));
    }

    @Test
    void negativeRecognitionIsRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"recognition": {"points": -1, "lifetime_ticks": 672000}}
                """)).contains("recognition.points"));
    }

    /** The shared point bound bites before the recognition-specific one; either way it is rejected. */
    @Test
    void recognitionAboveOneHundredPointsIsRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"recognition": {"points": 101, "lifetime_ticks": 672000}}
                """)).contains("points must be -100..100"));
    }

    @Test
    void facetPointsOutsideTheHardBoundsAreRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"facets": {"mcareputation:violence": {"points": 101, "lifetime_ticks": 672000}}}
                """)).contains("points"));
    }

    @Test
    void aLifetimeOutsideTheHardBoundsIsRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"recognition": {"points": 6, "lifetime_ticks": 23999}}
                """)).contains("lifetime_ticks"));
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"recognition": {"points": 6, "lifetime_ticks": 100000001}}
                """)).contains("lifetime_ticks"));
    }

    /** §9.3: a lifetime that is not a whole multiple of its decay step is not the authored lifetime. */
    @Test
    void aLifetimeIncompatibleWithItsDecayStepIsRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"recognition": {"points": 6, "lifetime_ticks": 30000}}
                """)).contains("whole multiple"));
        assertTrue(parse(IncidentProfileDefinition.CODEC, """
                {"recognition": {"points": 6, "lifetime_ticks": 30000}, "decay_step_ticks": 100}
                """).error().isEmpty(), "30000 is a whole multiple of a 100-tick step");
    }

    @Test
    void aDecayStepOutsideTheHardBoundsIsRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"recognition": {"points": 6, "lifetime_ticks": 672000}, "decay_step_ticks": 19}
                """)).contains("decay_step_ticks"));
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"recognition": {"points": 6, "lifetime_ticks": 672000}, "decay_step_ticks": 24001}
                """)).contains("decay_step_ticks"));
    }

    /** §10.4: a credit policy on a class that may not be discounted is a contradiction. */
    @Test
    void aCreditPolicyOnAnAdverseProfileIsRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"facets": {"mcareputation:violence": {"points": 8, "lifetime_ticks": 672000}},
                 "credit_class": "adverse", "credit_policy": "mcareputation:rescue_service"}
                """)).contains("commendable"));
    }

    /** I07: repetition may not make harm cheaper, so a commendable profile carries no adverse facet. */
    @Test
    void aCommendableProfileWithAdverseEffectsIsRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"facets": {"mcareputation:compassion": {"points": -4, "lifetime_ticks": 672000}},
                 "credit_class": "commendable"}
                """)).contains("adverse effects"));
    }

    /** §12.1: a resolution progression may never increase a contribution's magnitude. */
    @Test
    void aNonMonotonicResolutionProgressionIsRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"facets": {"mcareputation:violence": {"points": 8, "lifetime_ticks": 672000,
                  "resolution_mode": "evaluative",
                  "resolution_bp": {"apologized": 2500, "atoned": 7500}}}}
                """)).contains("never increase"));
    }

    @Test
    void aDisprovenMultiplierAboveZeroIsRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"facets": {"mcareputation:violence": {"points": 8, "lifetime_ticks": 672000,
                  "resolution_mode": "evaluative", "resolution_bp": {"disproven": 2500}}}}
                """)).contains("disproven"));
    }

    @Test
    void theDefaultResolutionMultipliersMatchTheExistingScalarPolicy() {
        assertEquals(7500, IncidentProfileDefinition.ResolutionMultipliers.DEFAULT.apologizedBp());
        assertEquals(2500, IncidentProfileDefinition.ResolutionMultipliers.DEFAULT.atonedBp());
        assertEquals(0, IncidentProfileDefinition.ResolutionMultipliers.DEFAULT.forgivenBp());
        assertEquals(0, IncidentProfileDefinition.ResolutionMultipliers.DEFAULT.disprovenBp());
    }

    @Test
    void anUnknownResolutionModeIsRejected() {
        assertTrue(parse(IncidentProfileDefinition.CODEC, """
                {"recognition": {"points": 6, "lifetime_ticks": 672000,
                  "resolution_mode": "vibes"}}
                """).error().isPresent());
    }

    @Test
    void tooManyFacetEntriesAreRejected() {
        StringBuilder facets = new StringBuilder();
        for (int i = 0; i <= IncidentProfileDefinition.MAX_FACET_ENTRIES; i++) {
            facets.append(i == 0 ? "" : ",")
                    .append("\"mcareputation:f").append(i)
                    .append("\": {\"points\": 1, \"lifetime_ticks\": 672000}");
        }
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC,
                "{\"facets\": {" + facets + "}}")).contains("at most"));
    }

    @Test
    void aDuplicateProfileKeyIsRejected() {
        assertTrue(errorOf(parse(IncidentProfileDefinition.CODEC, """
                {"recognition": {"points": 6, "lifetime_ticks": 672000},
                 "credit_class": "adverse", "credit_class": "commendable"}
                """)).contains("duplicate key"));
    }

    @Test
    void aMalformedResourceIdIsRejected() {
        assertTrue(parse(IncidentProfileDefinition.CODEC, """
                {"facets": {"Not An Id": {"points": 1, "lifetime_ticks": 672000}}}
                """).error().isPresent());
    }

    // ------------------------------------------------------------------
    // The published bundle (§9.6)
    // ------------------------------------------------------------------

    @Test
    void theBundleIsIdSortedRegardlessOfInsertionOrder() {
        FacetDefinition facet = ok(FacetDefinition.CODEC, RELIABILITY);
        Map<ResourceLocation, FacetDefinition> forwards = new LinkedHashMap<>();
        forwards.put(id("violence"), facet);
        forwards.put(id("bravery"), facet);
        forwards.put(ResourceLocation.fromNamespaceAndPath("other", "bravery"), facet);
        Map<ResourceLocation, FacetDefinition> backwards = new LinkedHashMap<>();
        backwards.put(ResourceLocation.fromNamespaceAndPath("other", "bravery"), facet);
        backwards.put(id("bravery"), facet);
        backwards.put(id("violence"), facet);

        ProfileRegistryBundle first =
                new ProfileRegistryBundle(1L, forwards, Map.of(), Map.of(), Map.of());
        ProfileRegistryBundle second =
                new ProfileRegistryBundle(1L, backwards, Map.of(), Map.of(), Map.of());
        assertEquals(List.of(id("bravery"), id("violence"), ResourceLocation.fromNamespaceAndPath("other", "bravery")),
                List.copyOf(first.facets().keySet()));
        assertEquals(first, second);
    }

    @Test
    void publishingIncrementsTheGenerationAndSwapsInOneStep() {
        ProfileRegistryBundle before = ProfileRegistryBundle.current();
        ProfileRegistryBundle published = ProfileRegistryBundle.publish(
                Map.of(id("reliability"), ok(FacetDefinition.CODEC, RELIABILITY)),
                Map.of(RecognitionTierSet.DEFAULT_ID, RecognitionTierSet.BUILTIN_DEFAULT),
                Map.of(id("rescued_villager"), ok(IncidentProfileDefinition.CODEC, RESCUE_PROFILE)),
                Map.of());
        try {
            assertNotEquals(before.generation(), published.generation());
            assertTrue(published.generation() > before.generation());
            assertEquals(published, ProfileRegistryBundle.current());
            assertTrue(published.facet(id("reliability")).isPresent());
            assertTrue(published.profile(id("rescued_villager")).isPresent());
            assertEquals(RecognitionTierSet.BUILTIN_DEFAULT,
                    published.recognitionLadderOrDefault(id("nonexistent")));
        } finally {
            ProfileRegistryBundle.clear();
        }
    }

    @Test
    void anAbsentLadderFallsBackToTheBuiltInOne() {
        assertEquals(RecognitionTierSet.BUILTIN_DEFAULT,
                ProfileRegistryBundle.EMPTY.recognitionLadderOrDefault(RecognitionTierSet.DEFAULT_ID));
        assertTrue(ProfileRegistryBundle.EMPTY.isEmpty());
    }

    @Test
    void anUnknownFacetLookupDegradesRatherThanFailing() {
        assertTrue(ProfileRegistryBundle.EMPTY.facet(id("gone")).isEmpty());
        assertEquals(0, ProfileRegistryBundle.EMPTY.facetOrUnknown(id("gone")).opinionWeightBp());
    }

    @Test
    void creditPoliciesAreFoundByTheirGroupRatherThanTheirFileId() {
        CreditPolicy policy = new CreditPolicy(id("rescue_service"), 336_000L,
                List.of(10_000, 0), 0, CreditPolicy.Scope.PLAYER_COMMUNITY, Optional.empty());
        ProfileRegistryBundle bundle = new ProfileRegistryBundle(1L, Map.of(), Map.of(), Map.of(),
                Map.of(id("some_file_name"), policy));
        assertTrue(bundle.creditPolicy(id("rescue_service")).isEmpty());
        assertEquals(policy, bundle.creditPolicyForGroup(id("rescue_service")).orElseThrow());
        assertEquals(List.of(id("rescue_service")), List.copyOf(bundle.creditGroups()));
    }
}
