package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.profile.FacetValue;
import dev.otectus.mcareputation.api.profile.ProfileAvailability;
import dev.otectus.mcareputation.api.profile.ProfileCoverage;
import dev.otectus.mcareputation.api.profile.ProfileQueryResult;
import dev.otectus.mcareputation.api.profile.ProfileSnapshot;
import dev.otectus.mcareputation.api.profile.RecognitionValue;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot;
import dev.otectus.mcareputation.profile.FacetDefinition;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.profile.RecognitionTierSet;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reaches the wire from a profile answer (§18.3): resolved labels, a bounded pane, and a
 * deterministic order.
 *
 * <p>No server and no channel: the projection takes a query result and a content bundle, so the two
 * things that actually go wrong here — a label resolved from the wrong pack, and a pane whose order
 * depends on map iteration — are checkable in a plain unit test.
 */
class ProfileProjectionTest {

    private static final ResourceLocation BRAVERY = ResourceLocation.fromNamespaceAndPath("mcareputation", "bravery");
    private static final ResourceLocation MERCY = ResourceLocation.fromNamespaceAndPath("mcareputation", "mercy");

    private static FacetDefinition definition(String name, String positive, String negative,
                                              int displayOrder) {
        return new FacetDefinition(Component.literal(name), Optional.empty(),
                new FacetDefinition.Range(-100, 100), Component.literal(positive),
                Optional.of(Component.literal(negative)), displayOrder, 5, 2, 0, Map.of());
    }

    private static FacetValue value(ResourceLocation facet, int points, boolean labelEligible) {
        return new FacetValue(facet, points, -100, 100, points > 0 ? 2 : 0, points < 0 ? 2 : 0,
                Math.max(0, points), Math.max(0, -points), true, false, labelEligible);
    }

    private static ProfileSnapshot snapshot(List<FacetValue> facets, List<ResourceLocation> dominant,
                                            ProfileCoverage coverage) {
        return new ProfileSnapshot(TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 90, "friend",
                new RecognitionValue(240, "well_known", 7), facets, dominant, 4L, 9L, 3L, 1000L,
                coverage);
    }

    private static ProfileRegistryBundle bundle(Map<ResourceLocation, FacetDefinition> facets) {
        return new ProfileRegistryBundle(3L, facets,
                Map.of(RecognitionTierSet.DEFAULT_ID, RecognitionTierSet.BUILTIN_DEFAULT), Map.of(),
                Map.of());
    }

    @Test
    void aDominantTraitTravelsAsThePacksOwnWordForIt() {
        ReputationNetwork.ProfileSummary summary = ProfileProjection.summary(
                ProfileQueryResult.available(snapshot(List.of(value(BRAVERY, 40, true)),
                        List.of(BRAVERY), ProfileCoverage.COMPLETE_SINCE_RECORD_START)),
                bundle(Map.of(BRAVERY, definition("Bravery", "Brave", "Cowardly", 10))), false);

        assertEquals(1, summary.dominantTraits().size());
        assertEquals("Brave", summary.dominantTraits().get(0).getString());
        assertEquals(Component.translatable("mcareputation.recognition.well_known"),
                summary.recognitionTierName(),
                "the recognition tier name is resolved from the server's own ladder, as a component "
                        + "the client renders rather than an id it would have to look up");
        assertEquals(240, summary.recognition());
        assertEquals(ProfileAvailability.AVAILABLE, summary.availability());
    }

    /** A negative value earns the negative word, not the positive one with a minus in front. */
    @Test
    void anAdverseFacetTravelsAsItsNegativeLabel() {
        ReputationNetwork.ProfileSummary summary = ProfileProjection.summary(
                ProfileQueryResult.available(snapshot(List.of(value(BRAVERY, -40, true)),
                        List.of(BRAVERY), ProfileCoverage.COMPLETE_SINCE_RECORD_START)),
                bundle(Map.of(BRAVERY, definition("Bravery", "Brave", "Cowardly", 10))), false);

        assertEquals("Cowardly", summary.dominantTraits().get(0).getString());
        assertEquals("Cowardly", summary.details().get(0).label().orElseThrow().getString());
        assertEquals(-40, summary.details().get(0).value());
    }

    /**
     * A facet whose definition has left the datapacks keeps its stored value and degrades only in
     * presentation (§9.4). The alternative — dropping it — would quietly change what the village
     * knows because somebody edited a pack.
     */
    @Test
    void aFacetWhoseDefinitionHasGoneStillTravelsWithAStandIn() {
        ReputationNetwork.ProfileSummary summary = ProfileProjection.summary(
                ProfileQueryResult.available(snapshot(List.of(value(BRAVERY, 30, true)),
                        List.of(BRAVERY), ProfileCoverage.COMPLETE_SINCE_RECORD_START)),
                bundle(Map.of()), false);

        assertEquals(1, summary.details().size());
        assertEquals(30, summary.details().get(0).value());
        assertTrue(summary.dominantTraits().get(0).getString().contains("bravery"),
                "the stand-in names the id rather than leaving a blank line");
    }

    /** §18.3's two per-pane caps, applied at the projection rather than hoped for at the encoder. */
    @Test
    void theProjectionIsBoundedToThreeTraitsAndEightDetails() {
        List<FacetValue> facets = new ArrayList<>();
        List<ResourceLocation> dominant = new ArrayList<>();
        Map<ResourceLocation, FacetDefinition> definitions = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("mcareputation", "facet_" + i);
            facets.add(value(id, 50 - i, true));
            dominant.add(id);
            definitions.put(id, definition("Facet " + i, "Good " + i, "Bad " + i, i));
        }
        ReputationNetwork.ProfileSummary summary = ProfileProjection.summary(
                ProfileQueryResult.available(snapshot(facets, dominant,
                        ProfileCoverage.COMPLETE_SINCE_RECORD_START)),
                bundle(definitions), false);

        assertEquals(ReputationBounds.MAX_SYNCED_DOMINANT_TRAITS, summary.dominantTraits().size());
        assertEquals(ReputationBounds.MAX_SYNCED_FACET_DETAILS, summary.details().size());
    }

    /**
     * Dominant facets come first and the rest follow by authored display order, whatever order the
     * snapshot happened to list them in. A pane whose order depended on iteration would let the
     * screen, the packet and the diagnostics disagree about the same evidence.
     */
    @Test
    void detailsPutTheDominantFacetsFirstAndThenAuthoredDisplayOrder() {
        ResourceLocation third = ResourceLocation.fromNamespaceAndPath("mcareputation", "compassion");
        ReputationNetwork.ProfileSummary summary = ProfileProjection.summary(
                ProfileQueryResult.available(snapshot(
                        List.of(value(BRAVERY, 10, false), value(MERCY, 40, true),
                                value(third, 20, false)),
                        List.of(MERCY), ProfileCoverage.COMPLETE_SINCE_RECORD_START)),
                bundle(Map.of(BRAVERY, definition("Bravery", "Brave", "Cowardly", 30),
                        MERCY, definition("Mercy", "Merciful", "Merciless", 20),
                        third, definition("Compassion", "Kind", "Cruel", 10))), false);

        assertEquals(List.of(MERCY, third, BRAVERY),
                summary.details().stream().map(ReputationNetwork.FacetSummary::facet).toList());
    }

    /** An unavailable answer keeps its availability and reason and carries no numbers at all. */
    @Test
    void anUnavailableAnswerProjectsItsReasonRatherThanAZeroProfile() {
        ReputationNetwork.ProfileSummary summary = ProfileProjection.summary(
                ProfileQueryResult.unavailable(ProfileAvailability.DISABLED, "profiles_disabled"),
                bundle(Map.of()), true);

        assertEquals(ProfileAvailability.DISABLED, summary.availability());
        assertEquals(Optional.of("profiles_disabled"), summary.reason());
        assertEquals(0, summary.recognition());
        assertTrue(summary.details().isEmpty());
        assertTrue(summary.readOnlyStore(), "a read-only store is reported whatever else failed");
    }

    /** Coverage travels untouched: an incomplete import must not read as a complete zero (§18.2). */
    @Test
    void partialLegacyCoverageSurvivesTheProjection() {
        ReputationNetwork.ProfileSummary summary = ProfileProjection.summary(
                ProfileQueryResult.available(snapshot(List.of(), List.of(),
                        ProfileCoverage.PARTIAL_LEGACY)),
                bundle(Map.of()), false);

        assertEquals(ProfileCoverage.PARTIAL_LEGACY, summary.coverage());
        assertEquals(ProfileAvailability.AVAILABLE, summary.availability());
    }

    /** The observer pane reuses the same projection, so both panes describe a facet identically. */
    @Test
    void theObserverPaneProjectsItsOwnKnowledgeThroughTheSameProjection() {
        ProfileSnapshot known = snapshot(List.of(value(BRAVERY, 40, true)), List.of(BRAVERY),
                ProfileCoverage.COMPLETE_SINCE_RECORD_START);
        Optional<ReputationNetwork.VillagerProfileSummary> summary = ProfileProjection.villagerSummary(
                ProfileQueryResult.available(new VillagerProfileSnapshot(TestFixtures.VILLAGER_1,
                        TestFixtures.OVERWORLD_3, known, 18, -4, 14, 1, 2, 3,
                        VillagerProfileSnapshot.TraitBasis.RESOLVED)),
                bundle(Map.of(BRAVERY, definition("Bravery", "Brave", "Cowardly", 10))),
                TestFixtures.VILLAGER_1, Component.literal("Anna"),
                Component.literal("Friend"), false);

        ReputationNetwork.VillagerProfileSummary view = summary.orElseThrow();
        assertEquals("Brave", view.known().dominantTraits().get(0).getString());
        assertEquals(-4, view.facetAdjustment());
        assertEquals(6, view.knownIncidents());
        assertEquals(VillagerProfileSnapshot.TraitBasis.RESOLVED, view.traitBasis());
    }

    /**
     * §13.3: an unresolvable speaker sends no observer pane. Widening it into the community's own
     * traits under a villager's name is the stranger-treated-as-friend defect with a label on it.
     */
    @Test
    void anUnresolvedSpeakerProjectsNoObserverPaneAtAll() {
        assertTrue(ProfileProjection.villagerSummary(
                ProfileQueryResult.unavailable(ProfileAvailability.UNRESOLVED, "no_speaker_context"),
                bundle(Map.of()), TestFixtures.VILLAGER_1, Component.literal("Anna"),
                Component.empty(), false).isEmpty());
        assertTrue(ProfileProjection.villagerSummary(
                ProfileQueryResult.available(new VillagerProfileSnapshot(TestFixtures.VILLAGER_1,
                        TestFixtures.OVERWORLD_3,
                        snapshot(List.of(), List.of(), ProfileCoverage.COMPLETE_SINCE_RECORD_START),
                        0, 0, 0, 0, 0, 0, VillagerProfileSnapshot.TraitBasis.NEUTRAL_DEFAULT)),
                bundle(Map.of()), null, Component.literal("Anna"), Component.empty(), false)
                .isEmpty(), "and neither does a pane with no villager identity to attach it to");
    }

    /** A villager who knows nothing is a real answer and does get a pane, with nothing in it. */
    @Test
    void aVillagerWhoKnowsNothingStillGetsAPane() {
        Optional<ReputationNetwork.VillagerProfileSummary> summary = ProfileProjection.villagerSummary(
                ProfileQueryResult.available(new VillagerProfileSnapshot(TestFixtures.VILLAGER_2,
                        TestFixtures.OVERWORLD_3,
                        new ProfileSnapshot(TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 0,
                                "stranger", RecognitionValue.none("unknown"), List.of(), List.of(), 0L,
                                0L, 3L, 1000L, ProfileCoverage.COMPLETE_SINCE_RECORD_START),
                        0, 0, 0, 0, 0, 0, VillagerProfileSnapshot.TraitBasis.NEUTRAL_DEFAULT)),
                bundle(Map.of()), TestFixtures.VILLAGER_2, Component.literal("Bo"),
                Component.literal("Neutral"), false);

        ReputationNetwork.VillagerProfileSummary view = summary.orElseThrow();
        assertEquals(0, view.knownIncidents());
        assertTrue(view.known().dominantTraits().isEmpty());
        assertFalse(view.known().readOnlyStore());
        assertEquals(ProfileAvailability.AVAILABLE, view.known().availability(),
                "a valid zero is available, not unavailable (§13.3)");
    }
}
