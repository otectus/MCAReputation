package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.DeliveryOutcome;
import dev.otectus.mcareputation.api.IncidentDelivery;
import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.event.ReputationChangedEvent;
import dev.otectus.mcareputation.api.event.ReputationProfileChangedEvent;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import net.neoforged.bus.api.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §15's publication contract for the profile channel.
 *
 * <p>Two cases have to exist and a third must not. An accepted deed that moved public evidence
 * publishes one profile envelope <em>after</em> its standing envelope, so a synchronous listener sees
 * a finished transaction. A reconciliation pass that moved profile evidence and nothing else
 * publishes the profile envelope <b>alone</b> — the case that has no standing event to ride on, and
 * the reason this event exists. And nothing anywhere publishes a {@code StandingChange} whose old and
 * new scores are identical, which is what repurposing {@code ReputationChangedEvent} for a
 * profile-only change would have required.
 */
class ProfileEventTest {

    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;

    private TestDeliverySeam seam;

    @BeforeEach
    void setUp() {
        seam = new TestDeliverySeam().policy(ReputationPolicy.defaults()).gameTime(TestFixtures.DAY);
        profiled(0);
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

    /** A deed carrying the test profile. A zero delta isolates the profile channel from the score. */
    private static void profiled(int delta) {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT, TestFixtures.definition(delta,
                IncidentVisibility.VILLAGE, DecayPolicy.NONE, TestFixtures.PROFILE)));
    }

    /** The same deed with no {@code social_profile} at all. */
    private static void unprofiled(int delta) {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(delta, IncidentVisibility.VILLAGE, DecayPolicy.NONE)));
    }

    private ReputationRequest request() {
        return new ReputationRequest(null, TestFixtures.PLAYER_A, HOME, TestFixtures.ASSAULT,
                TestFixtures.SOURCE, Optional.empty(), OptionalInt.empty(), Optional.empty(),
                List.of(), Set.of(), Map.of(), seam.gameTime());
    }

    private List<ReputationProfileChangedEvent> profileEvents() {
        List<ReputationProfileChangedEvent> events = new ArrayList<>();
        for (Event event : seam.posted()) {
            if (event instanceof ReputationProfileChangedEvent profile) {
                events.add(profile);
            }
        }
        return events;
    }

    private List<ReputationChangedEvent> standingEvents() {
        List<ReputationChangedEvent> events = new ArrayList<>();
        for (Event event : seam.posted()) {
            if (event instanceof ReputationChangedEvent standing) {
                events.add(standing);
            }
        }
        return events;
    }

    // ------------------------------------------------------------------
    // An accepted deed
    // ------------------------------------------------------------------

    @Test
    void aLiveProfileDeedPublishesExactlyOneProfileEvent() {
        seam.record(request());

        List<ReputationProfileChangedEvent> events = profileEvents();
        assertEquals(1, events.size(), "one operation, one profile envelope");
        ReputationProfileChangedEvent event = events.get(0);
        assertEquals(0, event.oldRecognition());
        assertEquals(6, event.newRecognition(), "the authored six recognition points");
        assertEquals(6, event.recognitionDelta());
        assertEquals(List.of(TestFixtures.FACET), event.changedFacets());
        assertEquals(ChangeCause.DEED, event.cause());
        assertFalse(event.quiet(), "an earned deed is not background fading");
        assertTrue(event.incidentId().isPresent());
        assertEquals(Optional.of(TestFixtures.ASSAULT), event.incidentType());
        assertTrue(event.operationKey().isEmpty(), "an unkeyed record has no operation identity");
        assertTrue(event.profileRevision() > 0L);
    }

    @Test
    void theProfileEventIsPublishedAfterTheStandingEnvelope() {
        profiled(5);
        seam.record(request());

        List<Class<?>> order = new ArrayList<>();
        for (Event event : seam.posted()) {
            order.add(event.getClass());
        }
        int standing = order.indexOf(ReputationChangedEvent.class);
        int profile = order.indexOf(ReputationProfileChangedEvent.class);
        assertTrue(standing >= 0 && profile >= 0, "a scoring profiled deed publishes both");
        assertTrue(profile > standing,
                "the profile envelope goes out last, when every accepted fact is already visible");
    }

    @Test
    void aKeyedDeliveryNamesItsOperationInTheProfileEvent() {
        DeliveryOutcome outcome = seam.deliver(IncidentDelivery.of(request(), "mcacrime", "op-1"));
        assertEquals(ReceiptOutcome.APPLIED, outcome.outcome());
        assertEquals(Optional.of("op-1"), profileEvents().get(0).operationKey());
    }

    @Test
    void aReplayedDeliveryPublishesNoSecondProfileEvent() {
        seam.deliver(IncidentDelivery.of(request(), "mcacrime", "op-1"));
        seam.clearPosted();
        DeliveryOutcome replay = seam.deliver(IncidentDelivery.of(request(), "mcacrime", "op-1"));
        assertEquals(ReceiptOutcome.DUPLICATE, replay.outcome());
        assertTrue(profileEvents().isEmpty(), "a replay changes no evidence and announces nothing");
    }

    @Test
    void aDeedWithNoProfileEvidencePublishesNoProfileEvent() {
        unprofiled(5);
        seam.record(request());
        assertTrue(profileEvents().isEmpty());
        assertFalse(standingEvents().isEmpty(), "the standing change still goes out");
    }

    @Test
    void aDeedAcceptedWhileProfilesAreOffPublishesNoProfileEvent() {
        seam.policy(ReputationPolicy.defaults().withProfilesEnabled(false));
        seam.record(request());
        assertTrue(profileEvents().isEmpty(),
                "nothing was observed, so there is nothing to announce");
    }

    @Test
    void aPrivateDeedPublishesNoProfileEvent() {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT, TestFixtures.definition(0,
                IncidentVisibility.PRIVATE, DecayPolicy.NONE, TestFixtures.PROFILE)));
        seam.record(request());
        assertTrue(profileEvents().isEmpty(),
                "a private deed carries no public evidence, even for its subjects");
    }

    // ------------------------------------------------------------------
    // A profile-only change
    // ------------------------------------------------------------------

    @Test
    void aProfileOnlyFadePublishesTheProfileEventAloneAndQuietly() {
        seam.record(request());
        seam.clearPosted();

        // Twenty-eight days is the authored bravery lifetime and half the recognition one, so the
        // profile channel has certainly moved while the zero-delta score cannot have.
        assertTrue(seam.reconcile(TestFixtures.PLAYER_A, TestFixtures.DAY + 28 * TestFixtures.DAY),
                "the sweep found work to do");

        List<ReputationProfileChangedEvent> events = profileEvents();
        assertEquals(1, events.size(), "one pass, one profile envelope");
        ReputationProfileChangedEvent event = events.get(0);
        assertEquals(ChangeCause.DECAY, event.cause());
        assertTrue(event.quiet(), "ordinary fading must never replay deed feedback");
        assertTrue(event.oldRecognition() > event.newRecognition(),
                "recognition faded on its own authored lifetime");
        assertTrue(event.changedFacets().isEmpty(),
                "background fading is deliberately not itemised; the revision is the signal");
        assertTrue(event.incidentId().isEmpty(), "no single deed caused it");

        assertTrue(standingEvents().isEmpty(),
                "a profile-only change must never be announced as a standing change");
    }

    @Test
    void aPassThatMovesNothingPublishesNothing() {
        seam.record(request());
        seam.clearPosted();
        seam.reconcile(TestFixtures.PLAYER_A, seam.gameTime());
        assertTrue(seam.posted().isEmpty(), "an idempotent pass at the same time announces nothing");
    }

    @Test
    void aProfileQueryNeverPublishesAProfileEventOfItsOwn() {
        seam.record(request());
        seam.clearPosted();
        ProfileService.profile(seam.policySnapshot(), seam.store(), TestFixtures.PLAYER_A, HOME,
                TestFixtures.DAY + 28 * TestFixtures.DAY, false);
        assertTrue(profileEvents().isEmpty(),
                "a read ages the ledger through the gate but publishes nothing; the sweep does that");
    }
}
