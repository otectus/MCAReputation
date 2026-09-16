package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.IncidentDelivery;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.SupersedeSpec;
import dev.otectus.mcareputation.api.event.ReputationProfileChangedEvent;
import dev.otectus.mcareputation.api.profile.ProfileAvailability;
import dev.otectus.mcareputation.api.profile.ProfileCreditExplanation;
import dev.otectus.mcareputation.api.profile.ProfiledDelivery;
import dev.otectus.mcareputation.api.profile.ProfiledDeliveryResult;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.IncidentProfileDefinition;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §9.5's wrapper, normalised into the same canonical commit as {@code deliver} and
 * {@code recordSuperseding}.
 *
 * <p>The two additions it makes are the two ways it can go wrong. A profile <em>selection</em> lets a
 * producer pick which authored rules a generic completion incident is judged by, and must therefore
 * be validated against the same allowlist an authored default is — a selection is not permission to
 * invent quantities. A <em>supersession</em> arriving under an operation key has to keep the receipt
 * ordering the plain keyed path already keeps, or a producer that crashes mid-delivery folds the same
 * precursor twice.
 */
class ProfiledDeliveryTest {

    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;
    private static final ResourceLocation KILL = ResourceLocation.fromNamespaceAndPath("mcareputation", "villager_killed");
    private static final ResourceLocation RESCUE_PROFILE =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "rescue_profile");
    private static final ResourceLocation UNKNOWN_PROFILE =
            ResourceLocation.fromNamespaceAndPath("mcareputation", "no_such_profile");

    private TestDeliverySeam seam;

    @BeforeEach
    void setUp() {
        seam = new TestDeliverySeam().policy(ReputationPolicy.defaults()).gameTime(TestFixtures.DAY);
        IncidentRegistry.replaceAll(Map.of(
                TestFixtures.ASSAULT, TestFixtures.definition(-8, IncidentVisibility.VILLAGE,
                        DecayPolicy.NONE, TestFixtures.PROFILE),
                KILL, TestFixtures.definition(-40, IncidentVisibility.VILLAGE, DecayPolicy.NONE,
                        TestFixtures.PROFILE)));
        publish();
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

    /**
     * Two published profiles: the default one, and a selectable "rescue" profile worth more
     * recognition whose allowlist admits only the assault incident.
     */
    private static void publish() {
        IncidentProfileDefinition rescue = new IncidentProfileDefinition(
                List.of(TestFixtures.ASSAULT),
                Optional.of(new IncidentProfileDefinition.Contribution(20, 56 * TestFixtures.DAY,
                        IncidentProfileDefinition.ResolutionMode.RECOGNITION,
                        IncidentProfileDefinition.ResolutionMultipliers.DEFAULT)),
                Map.of(), IncidentProfileDefinition.CreditClass.COMMENDABLE, Optional.empty(), false,
                Optional.empty());
        ProfileRegistryBundle.publish(Map.of(TestFixtures.FACET, TestFixtures.facet()), Map.of(),
                Map.of(TestFixtures.PROFILE, TestFixtures.profile(null), RESCUE_PROFILE, rescue),
                Map.of());
    }

    private ReputationRequest request(ResourceLocation type) {
        return new ReputationRequest(null, TestFixtures.PLAYER_A, HOME, type, TestFixtures.SOURCE,
                Optional.empty(), OptionalInt.empty(), Optional.empty(),
                List.of(IncidentSubject.villager(TestFixtures.VILLAGER_1, "Anna", "victim")),
                Set.of(), Map.of(), seam.gameTime());
    }

    private int profileEventCount() {
        int count = 0;
        for (Event event : seam.posted()) {
            if (event instanceof ReputationProfileChangedEvent) {
                count++;
            }
        }
        return count;
    }

    private int recognition() {
        return ProfileService.profile(seam.policySnapshot(), seam.store(), TestFixtures.PLAYER_A, HOME,
                        seam.gameTime(), true)
                .value().orElseThrow().recognition().value();
    }

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    @Test
    void anOrdinaryProfiledDeliveryUsesTheIncidentsOwnProfile() {
        ProfiledDeliveryResult result = seam.deliverProfiled(
                ProfiledDelivery.of(IncidentDelivery.of(request(TestFixtures.ASSAULT), "mcacrime", "op-1")));
        assertEquals(ReceiptOutcome.APPLIED, result.outcome().outcome());
        assertEquals(ProfileAvailability.AVAILABLE, result.profileAvailability());
        assertEquals(Optional.of(TestFixtures.PROFILE), result.appliedProfile());
        assertTrue(result.profileEvidenceRecorded());
        assertEquals(6, recognition(), "the incident's own profile authors six recognition points");
        assertTrue(result.outcome().receipt().isPresent(), "a keyed delivery earns a receipt");
    }

    @Test
    void anAuthorisedSelectionChangesWhichRulesTheDeedIsJudgedBy() {
        ProfiledDeliveryResult result = seam.deliverProfiled(new ProfiledDelivery(
                IncidentDelivery.of(request(TestFixtures.ASSAULT), "mcaquests", "op-1"),
                Optional.of(RESCUE_PROFILE), Optional.empty()));
        assertEquals(Optional.of(RESCUE_PROFILE), result.appliedProfile());
        assertEquals(20, recognition(), "the selected profile's own authored quantities apply");
    }

    @Test
    void aSelectionTheAllowlistDoesNotAdmitFallsBackToTheDefault() {
        // The rescue profile's allowed_incidents names the assault type only.
        ProfiledDeliveryResult result = seam.deliverProfiled(new ProfiledDelivery(
                IncidentDelivery.of(request(KILL), "mcaquests", "op-1"),
                Optional.of(RESCUE_PROFILE), Optional.empty()));
        assertEquals(Optional.of(TestFixtures.PROFILE), result.appliedProfile(),
                "a producer asking for the wrong rules does not lose the deed's own profile");
        assertEquals(6, recognition());
    }

    @Test
    void anUnknownSelectionFallsBackToTheDefault() {
        ProfiledDeliveryResult result = seam.deliverProfiled(new ProfiledDelivery(
                IncidentDelivery.of(request(TestFixtures.ASSAULT), "mcaquests", "op-1"),
                Optional.of(UNKNOWN_PROFILE), Optional.empty()));
        assertEquals(Optional.of(TestFixtures.PROFILE), result.appliedProfile());
    }

    @Test
    void theCreditExplanationReportsWhyTheDeedWasWorthWhatItWas() {
        ProfiledDeliveryResult result = seam.deliverProfiled(
                ProfiledDelivery.of(IncidentDelivery.of(request(TestFixtures.ASSAULT), "mcacrime", "op-1")));
        ProfileCreditExplanation credit = result.credit().orElseThrow();
        assertEquals(ProfileCreditExplanation.Reason.NO_POLICY, credit.reason(),
                "the test profile names no credit policy, and that is reported rather than assumed");
        assertTrue(credit.fullCredit());
        assertFalse(credit.suppressed());
    }

    // ------------------------------------------------------------------
    // Supersession
    // ------------------------------------------------------------------

    @Test
    void aKeyedSupersedingDeliveryFoldsThePrecursorAndEarnsOneReceipt() {
        UUID precursor = seam.record(request(TestFixtures.ASSAULT)).incidentId().orElseThrow();
        seam.clearPosted();
        seam.gameTime(TestFixtures.DAY + 100L);

        ProfiledDeliveryResult result = seam.deliverProfiled(ProfiledDelivery.superseding(
                IncidentDelivery.of(request(KILL), "mcacrime", "kill-1"),
                SupersedeSpec.of(precursor, 24_000L, true)));

        assertEquals(ReceiptOutcome.APPLIED, result.outcome().outcome());
        assertTrue(result.outcome().receipt().isPresent(),
                "the receipt is filed inside the canonical mutation, not skipped");
        assertEquals(-40, seam.store().player(TestFixtures.PLAYER_A).orElseThrow()
                        .community(HOME).orElseThrow().score(),
                "one encounter totals the successor's figure rather than stacking");
        assertTrue(seam.store().player(TestFixtures.PLAYER_A).orElseThrow().community(HOME)
                .orElseThrow().incident(precursor).orElseThrow().isSuperseded());
        assertEquals(1, profileEventCount(), "one encounter, one profile envelope");
    }

    @Test
    void replayingAKeyedSupersedingDeliveryFoldsNothingASecondTime() {
        UUID precursor = seam.record(request(TestFixtures.ASSAULT)).incidentId().orElseThrow();
        seam.gameTime(TestFixtures.DAY + 100L);
        SupersedeSpec spec = SupersedeSpec.of(precursor, 24_000L, true);
        seam.deliverProfiled(ProfiledDelivery.superseding(
                IncidentDelivery.of(request(KILL), "mcacrime", "kill-1"), spec));
        int afterFirst = seam.store().player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow().incidentCount();
        seam.clearPosted();

        ProfiledDeliveryResult replay = seam.deliverProfiled(ProfiledDelivery.superseding(
                IncidentDelivery.of(request(KILL), "mcacrime", "kill-1"), spec));
        assertEquals(ReceiptOutcome.DUPLICATE, replay.outcome().outcome());
        assertEquals(afterFirst, seam.store().player(TestFixtures.PLAYER_A).orElseThrow()
                        .community(HOME).orElseThrow().incidentCount(),
                "the receipt index is consulted before the ledger, so nothing is recorded twice");
        assertEquals(0, profileEventCount(), "a replay announces nothing");
    }

    @Test
    void supersedeTermsThatDoNotHoldStillRecordTheDeedAsAnOrdinaryDelivery() {
        UUID precursor = seam.record(request(TestFixtures.ASSAULT)).incidentId().orElseThrow();
        // Disproven precursors may not be superseded; the successor is still a real deed.
        seam.resolve(TestFixtures.PLAYER_A, HOME, precursor, IncidentStatus.DISPROVEN,
                TestFixtures.SOURCE);
        seam.gameTime(TestFixtures.DAY + 100L);

        ProfiledDeliveryResult result = seam.deliverProfiled(ProfiledDelivery.superseding(
                IncidentDelivery.of(request(KILL), "mcacrime", "kill-1"),
                SupersedeSpec.of(precursor, 24_000L, true)));
        assertEquals(ReceiptOutcome.APPLIED, result.outcome().outcome());
        assertTrue(result.outcome().receipt().isPresent());
        assertFalse(seam.store().player(TestFixtures.PLAYER_A).orElseThrow().community(HOME)
                .orElseThrow().incident(precursor).orElseThrow().isSuperseded());
    }

    // ------------------------------------------------------------------
    // The two halves fail independently (§16.3)
    // ------------------------------------------------------------------

    @Test
    void aDeedIsStillRecordedWhileProfilesAreSwitchedOff() {
        seam.policy(ReputationPolicy.defaults().withProfilesEnabled(false));
        ProfiledDeliveryResult result = seam.deliverProfiled(
                ProfiledDelivery.of(IncidentDelivery.of(request(TestFixtures.ASSAULT), "mcacrime", "op-1")));
        assertEquals(ReceiptOutcome.APPLIED, result.outcome().outcome(),
                "a social delivery failure must never undo a legal settlement");
        assertEquals(ProfileAvailability.DISABLED, result.profileAvailability());
        assertFalse(result.profileEvidenceRecorded());
        assertTrue(result.credit().isPresent(),
                "the window accounting still advanced, and the payload says so");
    }

    @Test
    void anUnpublishedContentGenerationIsReportedAsUnsupported() {
        ProfileRegistryBundle.clear();
        ProfiledDeliveryResult result = seam.deliverProfiled(
                ProfiledDelivery.of(IncidentDelivery.of(request(TestFixtures.ASSAULT), "mcacrime", "op-1")));
        assertEquals(ReceiptOutcome.APPLIED, result.outcome().outcome());
        assertEquals(ProfileAvailability.UNSUPPORTED, result.profileAvailability());
        assertFalse(result.profileEvidenceRecorded());
    }

    @Test
    void anUnkeyedProfiledDeliveryIsAcceptedWithoutAReceipt() {
        ProfiledDeliveryResult result = seam.deliverProfiled(
                ProfiledDelivery.of(IncidentDelivery.of(request(TestFixtures.ASSAULT))));
        assertEquals(ReceiptOutcome.APPLIED, result.outcome().outcome());
        assertTrue(result.outcome().receipt().isEmpty(), "no operation identity, no receipt");
        assertTrue(result.profileEvidenceRecorded());
    }
}
