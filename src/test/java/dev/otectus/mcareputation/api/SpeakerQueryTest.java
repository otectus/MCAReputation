package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.reputation.TestDeliverySeam;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T27: a knowledge filter that cannot be evaluated must narrow to nothing, not to everything, and a
 * committed reward must bind to one incident (§6 "Speaker-aware query", "Bound resolution").
 */
class SpeakerQueryTest {

    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;
    private static final UUID WITNESS = TestFixtures.VILLAGER_1;
    private static final UUID BYSTANDER = TestFixtures.VILLAGER_2;

    private final TestDeliverySeam seam = new TestDeliverySeam();

    @AfterEach
    void tearDown() {
        McaReputationConfig.TestOverrides.reset();
    }

    private static final IncidentQuery KNOWN = IncidentQuery.builder()
            .knownToSpeaker(true)
            .newestOnly(false)
            .build();

    private IncidentRecord seed(IncidentVisibility visibility, int delta) {
        CommunityReputationRecord community = seam.store()
                .getOrCreatePlayer(TestFixtures.PLAYER_A).getOrCreate(HOME);
        IncidentRecord incident = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, HOME, 0L, TestFixtures.SOURCE, Optional.empty(), delta,
                visibility, IncidentSeverity.MODERATE,
                List.of(IncidentSubject.villager(WITNESS, "Anna", "victim")));
        incident.addWitnesses(List.of(WITNESS));
        community.addIncident(incident);
        community.recomputeScore(-1000, 1000);
        return incident;
    }

    // --- who sees what ------------------------------------------------------

    @Test
    void anInformedSpeakerSeesTheIncidentAndAnUninformedOneDoesNot() {
        IncidentRecord incident = seed(IncidentVisibility.WITNESSED, -30);

        List<ReputationIncidentView> informed = seam.selectIncidents(TestFixtures.PLAYER_A, HOME, KNOWN,
                SpeakerContext.of(WITNESS, true));
        assertEquals(List.of(incident.id()), informed.stream().map(ReputationIncidentView::id).toList());

        // A resident who did not see it waits out their own deterministic share of the rumour window.
        assertTrue(seam.selectIncidents(TestFixtures.PLAYER_A, HOME, KNOWN,
                SpeakerContext.of(BYSTANDER, true)).isEmpty());
    }

    @Test
    void aSpeakerFromAnotherCommunityFollowsTheHearsayRules() {
        seed(IncidentVisibility.WITNESSED, -30);
        seam.gameTime(1_000_000L); // long past any rumour delay

        // Hearsay does not follow a villager between villages...
        assertTrue(seam.selectIncidents(TestFixtures.PLAYER_A, HOME, KNOWN,
                SpeakerContext.of(BYSTANDER, false)).isEmpty());
        // ...but what they saw themselves goes with them.
        assertEquals(1, seam.selectIncidents(TestFixtures.PLAYER_A, HOME, KNOWN,
                SpeakerContext.of(WITNESS, false)).size());
    }

    @Test
    void knownToSpeakerWithNoSpeakerAnswersWithNothing() {
        seed(IncidentVisibility.VILLAGE, -30);

        assertTrue(seam.selectIncidents(TestFixtures.PLAYER_A, HOME, KNOWN, null).isEmpty(),
                "an unevaluable knowledge filter must narrow to nothing");
        // The same failure closed at the public entry point, which has no speaker to offer at all.
        assertTrue(McaReputationApi.selectIncidents(null, TestFixtures.PLAYER_A, HOME, KNOWN).isEmpty());
    }

    @Test
    void aSelectorWithoutTheFlagIgnoresTheSpeakerEntirely() {
        IncidentRecord incident = seed(IncidentVisibility.WITNESSED, -30);
        IncidentQuery any = IncidentQuery.builder().type(TestFixtures.ASSAULT).newestOnly(false).build();

        assertEquals(List.of(incident.id()),
                seam.selectIncidents(TestFixtures.PLAYER_A, HOME, any, SpeakerContext.of(BYSTANDER, true))
                        .stream().map(ReputationIncidentView::id).toList());
    }

    // --- bound resolution ---------------------------------------------------

    @Test
    void aReplayedOperationKeyMovesNothingASecondTime() {
        IncidentRecord incident = seed(IncidentVisibility.VILLAGE, -30);

        ResolutionResult first = seam.resolveBound(TestFixtures.PLAYER_A, HOME, incident.id(),
                IncidentStatus.ATONED, TestFixtures.SOURCE, "reward-1");
        assertTrue(first.applied());
        int settled = incident.currentContribution();
        long revision = incident.storyRevision();

        ResolutionResult replay = seam.resolveBound(TestFixtures.PLAYER_A, HOME, incident.id(),
                IncidentStatus.ATONED, TestFixtures.SOURCE, "reward-1");
        assertFalse(replay.applied());
        assertEquals(ResolutionResult.Reason.NOT_STRONGER, replay.reason());
        assertEquals(settled, incident.currentContribution(), "a replay may not move the ledger");
        assertEquals(revision, incident.storyRevision(), "nor the story");
        assertEquals(ReceiptOutcome.APPLIED, seam.findReceipt(TestFixtures.SOURCE.getNamespace(),
                TestFixtures.PLAYER_A, HOME, "reward-1").orElseThrow().outcome());
    }

    @Test
    void aSupersededRecordIsRefusedAndTheRefusalIsSettled() {
        IncidentRecord incident = seed(IncidentVisibility.VILLAGE, -30);
        incident.foldInto(UUID.randomUUID(), 0L);
        seam.store().getOrCreatePlayer(TestFixtures.PLAYER_A).getOrCreate(HOME)
                .recomputeScore(-1000, 1000);

        ResolutionResult result = seam.resolveBound(TestFixtures.PLAYER_A, HOME, incident.id(),
                IncidentStatus.ATONED, TestFixtures.SOURCE, "reward-2");
        assertFalse(result.applied());
        assertEquals(ResolutionResult.Reason.INVALID, result.reason());
        assertEquals(ReceiptOutcome.REFUSED_INVALID, seam.findReceipt(TestFixtures.SOURCE.getNamespace(),
                TestFixtures.PLAYER_A, HOME, "reward-2").orElseThrow().outcome());

        // And the settled refusal answers the same way forever.
        assertEquals(ResolutionResult.Reason.INVALID, seam.resolveBound(TestFixtures.PLAYER_A, HOME,
                incident.id(), IncidentStatus.ATONED, TestFixtures.SOURCE, "reward-2").reason());
    }

    @Test
    void aWeakerStatusAfterAStrongerOneChangesNothingAndSettlesTheOperation() {
        IncidentRecord incident = seed(IncidentVisibility.VILLAGE, -30);
        assertTrue(seam.resolveBound(TestFixtures.PLAYER_A, HOME, incident.id(), IncidentStatus.ATONED,
                TestFixtures.SOURCE, "reward-3").applied());
        int settled = incident.currentContribution();

        ResolutionResult weaker = seam.resolveBound(TestFixtures.PLAYER_A, HOME, incident.id(),
                IncidentStatus.APOLOGIZED, TestFixtures.SOURCE, "reward-4");
        assertFalse(weaker.applied());
        assertEquals(ResolutionResult.Reason.NOT_STRONGER, weaker.reason());
        assertEquals(IncidentStatus.ATONED, incident.status());
        assertEquals(settled, incident.currentContribution());
        assertEquals(ReceiptOutcome.ACCEPTED_NO_PUBLIC_INCIDENT,
                seam.findReceipt(TestFixtures.SOURCE.getNamespace(), TestFixtures.PLAYER_A, HOME,
                        "reward-4").orElseThrow().outcome(),
                "no change, but the operation is complete and must not be retried");
    }

    @Test
    void anUnknownIncidentIsRetryableAndStoresNoReceipt() {
        seed(IncidentVisibility.VILLAGE, -30);

        ResolutionResult result = seam.resolveBound(TestFixtures.PLAYER_A, HOME, UUID.randomUUID(),
                IncidentStatus.ATONED, TestFixtures.SOURCE, "reward-5");
        assertEquals(ResolutionResult.Reason.NOT_FOUND, result.reason());
        assertTrue(seam.findReceipt(TestFixtures.SOURCE.getNamespace(), TestFixtures.PLAYER_A, HOME,
                "reward-5").isEmpty());
    }
}
