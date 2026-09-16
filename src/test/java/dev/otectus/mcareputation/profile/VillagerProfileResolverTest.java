package dev.otectus.mcareputation.profile;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.profile.FacetValue;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot.TraitBasis;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §13.2's individual interpretation, with no game running.
 *
 * <p>The cases here are the ones a "small personality adjustment" gets wrong in production: an
 * override that fires for a villager whose personality was never actually read, a facet weight a
 * pack set to zero being overridden by an operator cap, an operator cap being overridden by a pack,
 * and a villager whose traits are unavailable being interpreted optimistically instead of neutrally.
 *
 * <p>MCA is absent from this JVM, which is the point of the trait-resolution cases: the compatibility
 * seam is exercised in exactly the state a server without MCA — or with an MCA this build cannot link
 * against — presents, and the required answer is the mandatory neutral fallback rather than an
 * exception.
 */
class VillagerProfileResolverTest {

    private static final ResourceLocation FACET = TestFixtures.FACET;
    private static final ResourceLocation SECOND = ResourceLocation.fromNamespaceAndPath("mcareputation", "reliability");

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A facet definition with an authored weight and optional personality overrides. */
    private static FacetDefinition facet(int weightBp, Map<String, Integer> overrides) {
        return new FacetDefinition(Component.literal("Bravery"), Optional.empty(),
                new FacetDefinition.Range(-100, 100), Component.literal("Brave"),
                Optional.of(Component.literal("Cowardly")), 10, 5, 2, weightBp, overrides);
    }

    private static ProfileRegistryBundle bundle(Map<ResourceLocation, FacetDefinition> facets) {
        return new ProfileRegistryBundle(1L, facets, Map.of(), Map.of(), Map.of());
    }

    /** An observed facet value, as the knowledge-filtered aggregation would have produced it. */
    private static FacetValue value(ResourceLocation id, int points) {
        return new FacetValue(id, points, -100, 100, points >= 0 ? 2 : 0, points < 0 ? 2 : 0,
                Math.max(points, 0), Math.max(-points, 0), true, false, true);
    }

    private static VillagerProfileResolver.OpinionAdjustment adjust(ProfileRegistryBundle content,
                                                                    List<FacetValue> facets,
                                                                    VillagerProfileResolver.ObserverTraits traits,
                                                                    int cap) {
        return VillagerProfileResolver.facetAdjustment(content, facets, traits, true, cap);
    }

    // ------------------------------------------------------------------
    // Trait normalization (§9.2)
    // ------------------------------------------------------------------

    @Test
    void aPersonalityIsNormalizedToTheBareLowercaseIdAuthoringUses() {
        // "ODD" is MCA 7.6's enum name; "mca:odd" is 7.7's registry id. Both must meet the same
        // authored personality_overrides key.
        assertEquals(Optional.of("odd"),
                VillagerProfileResolver.ObserverTraits.of("ODD", null).personality());
        assertEquals(Optional.of("odd"),
                VillagerProfileResolver.ObserverTraits.of("mca:odd", null).personality());
        assertEquals(Optional.of("upbeat"),
                VillagerProfileResolver.ObserverTraits.of("  UPBEAT  ", null).personality());
    }

    @Test
    void aProfessionKeepsItsNamespaceBecauseTwoPacksMayShareAPath() {
        assertEquals(Optional.of("minecraft:farmer"),
                VillagerProfileResolver.ObserverTraits.of(null, "minecraft:Farmer").profession());
        assertEquals(Optional.of("mca:guard"),
                VillagerProfileResolver.ObserverTraits.of(null, " MCA:guard ").profession());
    }

    @Test
    void blankOrMissingTraitsAreAbsentRatherThanEmptyStrings() {
        VillagerProfileResolver.ObserverTraits blank =
                VillagerProfileResolver.ObserverTraits.of("   ", "");
        assertTrue(blank.personality().isEmpty());
        assertTrue(blank.profession().isEmpty());
        assertFalse(blank.anyResolved());
        assertFalse(blank.interpretationResolved());
    }

    @Test
    void anUnassignedPersonalityIsReportedAsTheIdMcaGaveNotAsUnresolved() {
        // MCA has its own UNASSIGNED personality. Folding it into "unresolved" would invent a fact
        // about MCA's answer; a pack may legitimately author a weight for it.
        VillagerProfileResolver.ObserverTraits traits =
                VillagerProfileResolver.ObserverTraits.of("UNASSIGNED", null);
        assertEquals(Optional.of("unassigned"), traits.personality());
        assertTrue(traits.interpretationResolved());
    }

    // ------------------------------------------------------------------
    // Resolution with no MCA present
    // ------------------------------------------------------------------

    @Test
    void anUnresolvableObserverInterpretsNeutrallyThroughBothEntryPoints() {
        assertSame(VillagerProfileResolver.ObserverTraits.NEUTRAL,
                VillagerProfileResolver.traits((Entity) null));
        assertSame(VillagerProfileResolver.ObserverTraits.NEUTRAL,
                VillagerProfileResolver.traits(null, TestFixtures.OVERWORLD_3, TestFixtures.VILLAGER_1));
        assertSame(VillagerProfileResolver.ObserverTraits.NEUTRAL,
                VillagerProfileResolver.traits(null, null, null));
    }

    @Test
    void neutralTraitsResolveNothingAndSaySo() {
        assertFalse(VillagerProfileResolver.ObserverTraits.NEUTRAL.anyResolved());
        assertEquals(TraitBasis.NEUTRAL_DEFAULT,
                VillagerProfileResolver.traitBasis(VillagerProfileResolver.ObserverTraits.NEUTRAL, true));
        assertEquals(TraitBasis.NEUTRAL_DEFAULT, VillagerProfileResolver.traitBasis(null, true));
    }

    // ------------------------------------------------------------------
    // The trait basis (§13.2)
    // ------------------------------------------------------------------

    @Test
    void theTraitBasisDistinguishesResolvedNeutralAndDisabled() {
        VillagerProfileResolver.ObserverTraits resolved =
                VillagerProfileResolver.ObserverTraits.of("upbeat", "minecraft:farmer");
        assertEquals(TraitBasis.RESOLVED, VillagerProfileResolver.traitBasis(resolved, true));
        assertEquals(TraitBasis.DISABLED, VillagerProfileResolver.traitBasis(resolved, false));
        assertEquals(TraitBasis.DISABLED,
                VillagerProfileResolver.traitBasis(VillagerProfileResolver.ObserverTraits.NEUTRAL, false));
    }

    @Test
    void aProfessionAloneIsStillANeutralInterpretation() {
        // Nothing authored weights a profession in this schema, so a resolved role that changed no
        // weight must not claim a resolved interpretation.
        VillagerProfileResolver.ObserverTraits roleOnly =
                VillagerProfileResolver.ObserverTraits.of(null, "minecraft:farmer");
        assertTrue(roleOnly.anyResolved());
        assertFalse(roleOnly.interpretationResolved());
        assertEquals(TraitBasis.NEUTRAL_DEFAULT, VillagerProfileResolver.traitBasis(roleOnly, true));
    }

    // ------------------------------------------------------------------
    // The authored weight bound
    // ------------------------------------------------------------------

    @Test
    void aFacetContributesItsValueScaledByTheAuthoredWeight() {
        ProfileRegistryBundle content = bundle(Map.of(FACET, facet(5_000, Map.of())));
        VillagerProfileResolver.OpinionAdjustment half = adjust(content, List.of(value(FACET, 8)),
                VillagerProfileResolver.ObserverTraits.NEUTRAL, 25);
        assertEquals(4, half.adjustment(), "8 points at 50% is 4");
        assertFalse(half.capped());
        assertEquals(1, half.contributions().size());
        assertEquals(5_000, half.contributions().get(0).weightBp());
        assertFalse(half.contributions().get(0).personalityApplied());
    }

    @Test
    void aFacetWeightedAtZeroContributesNothingWhateverTheCapAllows() {
        ProfileRegistryBundle content = bundle(Map.of(FACET, facet(0, Map.of())));
        VillagerProfileResolver.OpinionAdjustment none = adjust(content, List.of(value(FACET, 100)),
                VillagerProfileResolver.ObserverTraits.NEUTRAL, 100);
        assertEquals(0, none.adjustment(), "an operator cap cannot make a facet a pack ignored matter");
        assertTrue(none.contributions().isEmpty());
    }

    @Test
    void aNegativeValueAndANegativeWeightKeepTheirSigns() {
        ProfileRegistryBundle content = bundle(Map.of(FACET, facet(5_000, Map.of())));
        assertEquals(-4, adjust(content, List.of(value(FACET, -8)),
                VillagerProfileResolver.ObserverTraits.NEUTRAL, 25).adjustment());
        ProfileRegistryBundle inverted = bundle(Map.of(FACET, facet(-5_000, Map.of())));
        assertEquals(-4, adjust(inverted, List.of(value(FACET, 8)),
                VillagerProfileResolver.ObserverTraits.NEUTRAL, 25).adjustment(),
                "a pack may author a facet as disliked; that is a sign, not a bug");
    }

    @Test
    void anUnobservedOrZeroFacetIsNotEvidenceInEitherDirection() {
        ProfileRegistryBundle content = bundle(Map.of(FACET, facet(10_000, Map.of())));
        FacetValue unobserved = new FacetValue(FACET, 0, -100, 100, 0, 0, 0, 0, false, false, false);
        FacetValue zeroButContested =
                new FacetValue(FACET, 0, -100, 100, 2, 2, 8, 8, true, false, false);
        assertEquals(0, adjust(content, List.of(unobserved),
                VillagerProfileResolver.ObserverTraits.NEUTRAL, 25).adjustment());
        assertEquals(0, adjust(content, List.of(zeroButContested),
                VillagerProfileResolver.ObserverTraits.NEUTRAL, 25).adjustment(),
                "a balanced facet describes nobody and must not push an opinion either way");
    }

    @Test
    void aFacetWithNoPublishedDefinitionInterpretsNeutrally() {
        // §9.4: stored units survive a pack removal, but a definition that is gone weights nothing.
        assertEquals(0, adjust(bundle(Map.of()), List.of(value(FACET, 50)),
                VillagerProfileResolver.ObserverTraits.NEUTRAL, 25).adjustment());
        assertEquals(0, VillagerProfileResolver.facetAdjustment(null, List.of(value(FACET, 50)),
                VillagerProfileResolver.ObserverTraits.NEUTRAL, true, 25).adjustment());
    }

    // ------------------------------------------------------------------
    // The operator cap bound
    // ------------------------------------------------------------------

    @Test
    void theOperatorCapBindsTheSumAndTheUncappedTermStaysExplainable() {
        ProfileRegistryBundle content = bundle(Map.of(
                FACET, facet(10_000, Map.of()),
                SECOND, facet(10_000, Map.of())));
        VillagerProfileResolver.OpinionAdjustment capped = adjust(content,
                List.of(value(FACET, 100), value(SECOND, 100)),
                VillagerProfileResolver.ObserverTraits.NEUTRAL, 25);
        assertEquals(25, capped.adjustment(), "the default cap is 25 opinion points");
        assertEquals(200, capped.uncapped(), "and the term it bound is still reportable (§13.4)");
        assertTrue(capped.capped());
        assertEquals(2, capped.contributions().size());

        assertEquals(-25, adjust(content, List.of(value(FACET, -100), value(SECOND, -100)),
                VillagerProfileResolver.ObserverTraits.NEUTRAL, 25).adjustment());
        assertEquals(0, adjust(content, List.of(value(FACET, 100)),
                VillagerProfileResolver.ObserverTraits.NEUTRAL, 0).adjustment(),
                "a cap of zero switches the term off without switching the feature off");
    }

    // ------------------------------------------------------------------
    // Personality overrides (§9.2)
    // ------------------------------------------------------------------

    @Test
    void aPersonalityOverrideAppliesOnlyWhenThatPersonalityResolved() {
        ProfileRegistryBundle content =
                bundle(Map.of(FACET, facet(5_000, Map.of("upbeat", 10_000, "odd", 2_500))));
        List<FacetValue> known = List.of(value(FACET, 8));

        VillagerProfileResolver.OpinionAdjustment upbeat = adjust(content, known,
                VillagerProfileResolver.ObserverTraits.of("upbeat", null), 25);
        assertEquals(8, upbeat.adjustment(), "an upbeat villager weights bravery fully");
        assertEquals(TraitBasis.RESOLVED, upbeat.traitBasis());
        assertTrue(upbeat.contributions().get(0).personalityApplied());

        assertEquals(2, adjust(content, known,
                VillagerProfileResolver.ObserverTraits.of("mca:odd", null), 25).adjustment(),
                "and an odd one discounts it");

        VillagerProfileResolver.OpinionAdjustment unresolved = adjust(content, known,
                VillagerProfileResolver.ObserverTraits.NEUTRAL, 25);
        assertEquals(4, unresolved.adjustment(),
                "an unread personality uses the authored default, never the most generous override");
        assertEquals(TraitBasis.NEUTRAL_DEFAULT, unresolved.traitBasis());
        assertFalse(unresolved.contributions().get(0).personalityApplied());
    }

    @Test
    void aPersonalityWithNoAuthoredOverrideUsesTheDefaultWeightAndStillCountsAsResolved() {
        ProfileRegistryBundle content = bundle(Map.of(FACET, facet(5_000, Map.of("upbeat", 10_000))));
        VillagerProfileResolver.OpinionAdjustment gloomy = adjust(content, List.of(value(FACET, 8)),
                VillagerProfileResolver.ObserverTraits.of("gloomy", null), 25);
        assertEquals(4, gloomy.adjustment());
        assertEquals(TraitBasis.RESOLVED, gloomy.traitBasis(),
                "the traits were read and applied; the pack simply weights this one no differently");
        assertFalse(gloomy.contributions().get(0).personalityApplied());
    }

    // ------------------------------------------------------------------
    // The master switch
    // ------------------------------------------------------------------

    @Test
    void switchingFacetInterpretationOffIsNotTheSameAsKnowingNothing() {
        ProfileRegistryBundle content = bundle(Map.of(FACET, facet(10_000, Map.of())));
        VillagerProfileResolver.OpinionAdjustment off = VillagerProfileResolver.facetAdjustment(content,
                List.of(value(FACET, 100)), VillagerProfileResolver.ObserverTraits.of("upbeat", null),
                false, 25);
        assertEquals(0, off.adjustment());
        assertEquals(0, off.uncapped());
        assertEquals(TraitBasis.DISABLED, off.traitBasis(),
                "no weighting happened at all, which is a different answer from a neutral one");
        assertTrue(off.contributions().isEmpty());
    }
}
