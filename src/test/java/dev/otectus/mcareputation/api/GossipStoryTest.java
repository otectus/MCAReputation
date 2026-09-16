package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.GossipSpec;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.incident.ResolutionPolicy;
import dev.otectus.mcareputation.reputation.TestDeliverySeam;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T33 (seam): a story revision has to move when the village's belief about a deed moves, and stay put
 * while nothing but the number is fading (§6 "Gossip story", DD12).
 */
class GossipStoryTest {

    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;
    private static final UUID SPEAKER = TestFixtures.VILLAGER_1;

    private final TestDeliverySeam seam = new TestDeliverySeam();

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
        McaReputationConfig.TestOverrides.reset();
    }

    private static IncidentDefinition tellable() {
        return new IncidentDefinition(Component.translatable("test.incident"), -30,
                IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE, List.of("test"), Optional.empty(),
                new DecayPolicy(DecayPolicy.Type.LINEAR_TO_ZERO, 0L, 5), ResolutionPolicy.DEFAULT,
                new GossipSpec(Optional.of("condemnation"), Optional.of("test.gossip.assault"), List.of()),
                false, Optional.empty(), false, false);
    }

    private IncidentRecord seed(int delta) {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT, tellable()));
        CommunityReputationRecord community = seam.store()
                .getOrCreatePlayer(TestFixtures.PLAYER_A).getOrCreate(HOME);
        IncidentRecord incident = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, HOME, 0L, TestFixtures.SOURCE, Optional.empty(), delta,
                IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE,
                List.of(IncidentSubject.villager(SPEAKER, "Anna", "victim")));
        community.addIncident(incident);
        community.recomputeScore(-1000, 1000);
        return incident;
    }

    @Test
    void aTellableDeedComesBackWithACandidateAndTheOriginalRevision() {
        IncidentRecord incident = seed(-30);

        GossipStory story = seam.gossipStory(TestFixtures.PLAYER_A, HOME, SPEAKER, true).orElseThrow();
        assertEquals(incident.id(), story.incidentId());
        assertEquals(0L, story.storyRevision());
        assertEquals(-30, story.originalDelta());
        assertFalse(story.correction());
        assertEquals("test.gossip.assault", story.candidate().orElseThrow().phraseKey());
    }

    @Test
    void resolvingTheDeedMovesTheStoryOn() {
        IncidentRecord incident = seed(-30);
        long before = seam.gossipStory(TestFixtures.PLAYER_A, HOME, SPEAKER, true)
                .orElseThrow().storyRevision();

        assertTrue(seam.resolve(TestFixtures.PLAYER_A, HOME, incident.id(), IncidentStatus.ATONED,
                TestFixtures.SOURCE).applied());

        GossipStory after = seam.gossipStory(TestFixtures.PLAYER_A, HOME, SPEAKER, true).orElseThrow();
        assertNotEquals(before, after.storyRevision(), "an atonement is news; the story has moved on");
        assertEquals(IncidentStatus.ATONED, after.status());
    }

    @Test
    void aDecayTickAloneDoesNotMoveTheStory() {
        seed(-30);
        long before = seam.gossipStory(TestFixtures.PLAYER_A, HOME, SPEAKER, true)
                .orElseThrow().storyRevision();

        seam.gameTime(4 * DecayPolicy.TICKS_PER_DAY);
        GossipStory after = seam.gossipStory(TestFixtures.PLAYER_A, HOME, SPEAKER, true).orElseThrow();

        assertEquals(before, after.storyRevision(), "fading is not news");
    }

    @Test
    void aDisprovenDeedIsACorrectionWithNoLineToSay() {
        IncidentRecord incident = seed(-30);
        assertTrue(seam.resolve(TestFixtures.PLAYER_A, HOME, incident.id(), IncidentStatus.DISPROVEN,
                TestFixtures.SOURCE).applied());

        GossipStory story = seam.gossipStory(TestFixtures.PLAYER_A, HOME, SPEAKER, true).orElseThrow();
        assertTrue(story.disproven());
        assertTrue(story.correction());
        assertEquals(1L, story.storyRevision());
    }

    @Test
    void aSupersededRecordAppearsOnlyAsACorrection() {
        IncidentRecord incident = seed(-30);
        long before = incident.storyRevision();
        incident.foldInto(UUID.randomUUID(), 0L);
        seam.store().getOrCreatePlayer(TestFixtures.PLAYER_A).getOrCreate(HOME)
                .recomputeScore(-1000, 1000);

        GossipStory story = seam.gossipStory(TestFixtures.PLAYER_A, HOME, SPEAKER, true).orElseThrow();
        assertTrue(story.superseded());
        assertTrue(story.supersededBy().isPresent());
        assertTrue(story.candidate().isEmpty(), "there is a change to acknowledge and no deed to report");
        assertNotEquals(before, story.storyRevision());
    }

    @Test
    void aSpeakerWhoCannotKnowGetsNothing() {
        seed(-30);
        // A villager who lives somewhere else hears no village business from here.
        assertTrue(seam.gossipStory(TestFixtures.PLAYER_A, HOME, TestFixtures.VILLAGER_2, false).isEmpty());
    }
}
