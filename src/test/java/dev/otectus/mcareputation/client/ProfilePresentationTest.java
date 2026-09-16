package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.profile.ProfileAvailability;
import dev.otectus.mcareputation.api.profile.ProfileCoverage;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot;
import dev.otectus.mcareputation.network.ReputationNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the profile pane says, and — mostly — what it says when it cannot say much (§18.2).
 *
 * <p>Five states have to read differently: a genuine stranger, an unreadable answer, an imported
 * history with holes, a migration in progress, and a read-only store. Nobody exercises those by hand,
 * which is why they are asserted here rather than hoped for.
 */
class ProfilePresentationTest {

    private static final ResourceLocation BRAVERY = new ResourceLocation("mcareputation", "bravery");

    private static ReputationNetwork.ProfileSummary summary(ProfileAvailability availability,
                                                            ProfileCoverage coverage,
                                                            boolean readOnly, int recognition,
                                                            List<Component> traits,
                                                            List<ReputationNetwork.FacetSummary> details) {
        return new ReputationNetwork.ProfileSummary(availability, coverage, readOnly,
                Optional.empty(), recognition, "well_known",
                Component.translatable("mcareputation.recognition.well_known"), 4, traits, details,
                7L, 2L);
    }

    private static ReputationNetwork.ProfileSummary known(int recognition, List<Component> traits) {
        return summary(ProfileAvailability.AVAILABLE, ProfileCoverage.COMPLETE_SINCE_RECORD_START,
                false, recognition, traits, List.of(facet(40, true)));
    }

    private static ReputationNetwork.FacetSummary facet(int value, boolean labelEligible) {
        return new ReputationNetwork.FacetSummary(BRAVERY, Component.literal("Bravery"),
                value == 0 ? Optional.empty()
                        : Optional.of(Component.literal(value > 0 ? "Brave" : "Cowardly")),
                value, -100, 100, value > 0 ? 3 : 0, value < 0 ? 2 : 0, labelEligible);
    }

    private static ReputationNetwork.VillagerProfileSummary observer(
            List<Component> traits, int involved, int witnessed, int hearsay, int adjustment,
            VillagerProfileSnapshot.TraitBasis basis) {
        return new ReputationNetwork.VillagerProfileSummary(TestFixtures.VILLAGER_1,
                Component.literal("Anna"), known(120, traits), 18, adjustment, 18 + adjustment,
                Component.literal("Friend"), basis, involved, witnessed, hearsay);
    }

    private static String text(Optional<Component> line) {
        return line.map(Component::getString).orElse("");
    }

    // ------------------------------------------------------------------
    // Drawable at all
    // ------------------------------------------------------------------

    /** A switched-off feature draws nothing: the player cannot act on it and did not ask. */
    @Test
    void aDisabledOrUnsupportedProfileIsNotDrawnAtAll() {
        for (ProfileAvailability availability : List.of(ProfileAvailability.DISABLED,
                ProfileAvailability.UNSUPPORTED)) {
            assertFalse(ProfilePresentation.drawable(Optional.of(summary(availability,
                    ProfileCoverage.MIGRATING, false, 0, List.of(), List.of()))));
        }
        assertFalse(ProfilePresentation.drawable(Optional.empty()));
        assertTrue(ProfilePresentation.drawable(Optional.of(known(120, List.of()))));
        assertTrue(ProfilePresentation.drawable(Optional.of(summary(ProfileAvailability.ERROR,
                        ProfileCoverage.MIGRATING, false, 0, List.of(), List.of()))),
                "a failure has something honest to say and is drawn");
    }

    // ------------------------------------------------------------------
    // The five honest states (§18.2)
    // ------------------------------------------------------------------

    @Test
    void theFiveStatesEachHaveTheirOwnLine() {
        assertEquals("mcareputation.screen.profile.unavailable",
                text(ProfilePresentation.stateLine(summary(ProfileAvailability.ERROR,
                        ProfileCoverage.COMPLETE_SINCE_RECORD_START, false, 0, List.of(), List.of()))));
        assertEquals("mcareputation.screen.profile.read_only",
                text(ProfilePresentation.stateLine(summary(ProfileAvailability.AVAILABLE,
                        ProfileCoverage.COMPLETE_SINCE_RECORD_START, true, 120, List.of(), List.of()))));
        assertEquals("mcareputation.screen.profile.migrating",
                text(ProfilePresentation.stateLine(summary(ProfileAvailability.AVAILABLE,
                        ProfileCoverage.MIGRATING, false, 120, List.of(), List.of()))));
        assertEquals("mcareputation.screen.profile.partial_legacy",
                text(ProfilePresentation.stateLine(summary(ProfileAvailability.AVAILABLE,
                        ProfileCoverage.PARTIAL_LEGACY, false, 0, List.of(), List.of()))),
                "an incomplete import reads as incomplete history, never as 'nobody knows you'");
        assertEquals("mcareputation.screen.profile.unknown",
                text(ProfilePresentation.stateLine(summary(ProfileAvailability.AVAILABLE,
                        ProfileCoverage.COMPLETE_SINCE_RECORD_START, false, 0, List.of(), List.of()))));
    }

    /** A complete, ordinary, non-empty answer gets no caveat at all. */
    @Test
    void aCompleteProfileWithEvidenceHasNoStateLine() {
        assertTrue(ProfilePresentation.stateLine(known(120, List.of(Component.literal("Brave"))))
                .isEmpty());
    }

    /** Exactly one caveat is ever shown, and the most consequential one wins. */
    @Test
    void aReadOnlyStoreWithPartialHistoryReportsTheReadOnlyStoreOnly() {
        assertEquals("mcareputation.screen.profile.read_only",
                text(ProfilePresentation.stateLine(summary(ProfileAvailability.AVAILABLE,
                        ProfileCoverage.PARTIAL_LEGACY, true, 0, List.of(), List.of()))));
    }

    // ------------------------------------------------------------------
    // The two compact lines
    // ------------------------------------------------------------------

    @Test
    void recognitionFollowsItsClientSwitchAndTheExactValueSwitchSeparately() {
        ReputationNetwork.ProfileSummary profile = known(240, List.of());

        assertTrue(ProfilePresentation.recognitionLine(profile, false, false).isEmpty());
        assertEquals("mcareputation.screen.profile.recognition",
                text(ProfilePresentation.recognitionLine(profile, true, false)));
        assertEquals("mcareputation.screen.profile.recognition_exact",
                text(ProfilePresentation.recognitionLine(profile, true, true)));
    }

    /** An unreadable answer has no recognition to report, whatever the switches say. */
    @Test
    void recognitionIsNotClaimedForAnUnavailableAnswer() {
        assertTrue(ProfilePresentation.recognitionLine(summary(ProfileAvailability.MIGRATING,
                ProfileCoverage.MIGRATING, false, 0, List.of(), List.of()), true, true).isEmpty());
    }

    @Test
    void knownForNamesTheTraitsAndDisappearsWhenThereAreNone() {
        assertEquals("mcareputation.screen.profile.known_for",
                text(ProfilePresentation.knownForLine(
                        known(240, List.of(Component.literal("Brave"), Component.literal("Kind"))),
                        true)));
        assertTrue(ProfilePresentation.knownForLine(known(240, List.of()), true).isEmpty(),
                "no earned label is not a gap to fill with a reassuring phrase");
        assertTrue(ProfilePresentation.knownForLine(known(240, List.of(Component.literal("Brave"))),
                false).isEmpty());
    }

    // ------------------------------------------------------------------
    // Details, and the sign that is never a colour (§28.4)
    // ------------------------------------------------------------------

    @Test
    void theDetailsToggleOnlyExistsWhenThereAreDetails() {
        assertTrue(ProfilePresentation.hasDetails(Optional.of(known(120, List.of()))));
        assertFalse(ProfilePresentation.hasDetails(Optional.of(summary(
                ProfileAvailability.AVAILABLE, ProfileCoverage.COMPLETE_SINCE_RECORD_START, false,
                120, List.of(), List.of()))));
        assertFalse(ProfilePresentation.hasDetails(Optional.empty()));
        assertEquals("mcareputation.screen.profile.show_details",
                ProfilePresentation.detailsToggle(false).getString());
        assertEquals("mcareputation.screen.profile.hide_details",
                ProfilePresentation.detailsToggle(true).getString());
    }

    @Test
    void aFacetsDirectionIsCarriedByWordsRatherThanByColour() {
        assertTrue(ProfilePresentation.facetMeta(facet(40, true), false).getString()
                .contains("mcareputation.screen.profile.direction.favour"));
        assertTrue(ProfilePresentation.facetMeta(facet(-40, true), false).getString()
                .contains("mcareputation.screen.profile.direction.against"));
        assertTrue(ProfilePresentation.facetMeta(facet(0, true), false).getString()
                .contains("mcareputation.screen.profile.direction.balanced"));
    }

    /** Zero is never described by either label: for a unipolar facet it is not the opposite (§8.1). */
    @Test
    void aZeroFacetIsKnownBothWaysRatherThanEitherLabel() {
        assertEquals("mcareputation.screen.profile.facet_balanced",
                ProfilePresentation.facetLine(facet(0, true)).getString());
        assertEquals("mcareputation.screen.profile.facet",
                ProfilePresentation.facetLine(facet(40, true)).getString());
    }

    @Test
    void aFacetTooSmallToBeSpokenOfSaysSoAndTheNumbersFollowTheirOwnSwitch() {
        String ineligible = ProfilePresentation.facetMeta(facet(4, false), false).getString();
        assertTrue(ineligible.contains("mcareputation.screen.profile.facet_not_spoken"));
        assertFalse(ineligible.contains("mcareputation.screen.profile.facet_value"));

        assertTrue(ProfilePresentation.facetMeta(facet(40, true), true).getString()
                .contains("mcareputation.screen.profile.facet_value"));
        assertFalse(ProfilePresentation.facetMeta(facet(40, true), false).getString()
                .contains("mcareputation.screen.profile.facet_value"));
    }

    // ------------------------------------------------------------------
    // The observer pane (§13.3, §13.4)
    // ------------------------------------------------------------------

    @Test
    void anObserverWhoKnowsNothingGetsTheirOwnLine() {
        assertEquals("mcareputation.screen.profile.observer_nothing",
                text(ProfilePresentation.observerLine(
                        Optional.of(observer(List.of(), 0, 0, 0, 0,
                                VillagerProfileSnapshot.TraitBasis.NEUTRAL_DEFAULT)), true)),
                "a valid zero must be visible as a zero (§13.3), never as the village's own view");
    }

    /** Knowing deeds but nothing label-worthy is a third state, and it is not "has heard nothing". */
    @Test
    void anObserverWithKnowledgeButNoLabelSaysThatInstead() {
        assertEquals("mcareputation.screen.profile.observer_unspoken",
                text(ProfilePresentation.observerLine(
                        Optional.of(observer(List.of(), 1, 0, 0, 0,
                                VillagerProfileSnapshot.TraitBasis.RESOLVED)), true)));
    }

    @Test
    void anObserverWithTraitsNamesThem() {
        assertEquals("mcareputation.screen.profile.observer",
                text(ProfilePresentation.observerLine(
                        Optional.of(observer(List.of(Component.literal("Brave")), 1, 1, 1, -3,
                                VillagerProfileSnapshot.TraitBasis.RESOLVED)), true)));
    }

    @Test
    void theObserverPaneFollowsItsOwnClientSwitch() {
        Optional<ReputationNetwork.VillagerProfileSummary> view = Optional.of(observer(
                List.of(Component.literal("Brave")), 1, 1, 1, -3,
                VillagerProfileSnapshot.TraitBasis.RESOLVED));
        assertTrue(ProfilePresentation.observerLine(view, false).isEmpty());
        assertTrue(ProfilePresentation.observerKnowledgeLine(view, false).isEmpty());
        assertTrue(ProfilePresentation.observerAdjustmentLine(view, false, true).isEmpty());
        assertTrue(ProfilePresentation.observerLine(Optional.empty(), true).isEmpty());
    }

    /** §13.4's bounded explanation: how much is known and how, with no informant ever named. */
    @Test
    void theKnowledgeLineCountsDeedsAndNamesNoInformant() {
        Optional<Component> line = ProfilePresentation.observerKnowledgeLine(
                Optional.of(observer(List.of(Component.literal("Brave")), 1, 2, 3, -3,
                        VillagerProfileSnapshot.TraitBasis.RESOLVED)), true);
        assertEquals("mcareputation.screen.profile.observer_knowledge", text(line));
        assertTrue(ProfilePresentation.observerKnowledgeLine(
                        Optional.of(observer(List.of(), 0, 0, 0, 0,
                                VillagerProfileSnapshot.TraitBasis.NEUTRAL_DEFAULT)), true).isEmpty(),
                "with nothing known there is nothing to explain");
    }

    /** The adjustment line is a number, so it follows the numbers switch and reports its basis. */
    @Test
    void theAdjustmentLineNeedsTheExactValuesSwitchAndReportsItsBasis() {
        Optional<ReputationNetwork.VillagerProfileSummary> view = Optional.of(observer(
                List.of(Component.literal("Brave")), 1, 1, 1, -3,
                VillagerProfileSnapshot.TraitBasis.DISABLED));
        assertTrue(ProfilePresentation.observerAdjustmentLine(view, true, false).isEmpty());
        assertEquals("mcareputation.screen.profile.observer_adjustment",
                text(ProfilePresentation.observerAdjustmentLine(view, true, true)));
        assertEquals("mcareputation.screen.profile.basis.disabled",
                ProfilePresentation.basisKey(VillagerProfileSnapshot.TraitBasis.DISABLED));
        assertEquals("mcareputation.screen.profile.basis.resolved",
                ProfilePresentation.basisKey(VillagerProfileSnapshot.TraitBasis.RESOLVED));
        assertEquals("mcareputation.screen.profile.basis.neutral",
                ProfilePresentation.basisKey(VillagerProfileSnapshot.TraitBasis.NEUTRAL_DEFAULT));
    }
}
