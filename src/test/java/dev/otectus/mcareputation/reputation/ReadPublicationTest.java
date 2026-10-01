package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.ReputationMirror;
import dev.otectus.mcareputation.api.StandingChange;
import dev.otectus.mcareputation.api.event.ReputationChangedEvent;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A read that observes decay publishes it, exactly once (§5 F07, §15.1).
 *
 * <p>The gate persists whatever it ages. The API's score reads skipped it, so MCA: Quests' Journal
 * and tier gates read a number the standing screen had already aged past; its condition and profile
 * reads went through it bare, so the decay step they happened to see first reached no mirror and no
 * scoreboard, and the next pass — finding nothing left to age — never reported it either. Every
 * assertion here is about that one step being told to everyone once. (This branch has no standing
 * outbox; the Forge copy of this test also asserts the outbox entry.)
 */
class ReadPublicationTest {

    private static final long DAY = DecayPolicy.TICKS_PER_DAY;
    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;

    private final TestServiceContext ctx = new TestServiceContext();
    private final List<StandingChange> mirrored = new ArrayList<>();
    private final ReputationMirror mirror = new ReputationMirror() {
        @Override
        public void mirrorStanding(StandingChange change) {
            mirrored.add(change);
        }

        @Override
        public void mirrorScore(UUID player, CommunityKey community, int score, ResourceLocation ladder,
                                String highWaterTierId) {
        }

        @Override
        public void mirrorVillageTitle(UUID player, CommunityKey community, ResourceLocation title) {
        }

        @Override
        public void mirrorGlobalTitle(UUID player, ResourceLocation title) {
        }

        @Override
        public String mirrorName() {
            return "read-publication-test";
        }
    };

    @BeforeEach
    void setUp() {
        ReputationService.registerMirror(mirror);
        // -30 is 'distrusted'; one day later the whole contribution has decayed and the player is a
        // stranger again, so the step being published is also a tier change.
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(-30, IncidentVisibility.VILLAGE, DecayPolicy.linearToZero(0, 30))));
        CommunityReputationRecord record = ctx.data.getOrCreatePlayer(TestFixtures.PLAYER_A)
                .getOrCreate(HOME);
        record.addIncident(IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, HOME, 0L, TestFixtures.SOURCE, Optional.empty(), -30,
                IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE, List.of()));
        record.recomputeScore(McaReputationConfig.minimumScore(), McaReputationConfig.maximumScore());
        ctx.gameTime = DAY;
    }

    @AfterEach
    void tearDown() {
        ReputationService.unregisterMirror(mirror);
        IncidentRegistry.replaceAll(Map.of());
        McaReputationConfig.TestOverrides.reset();
    }

    @Test
    void theScoreReadReportsTheDecayedValueNotTheStoredOne() {
        assertEquals(OptionalInt.of(0), ReputationService.currentScoreWith(ctx, TestFixtures.PLAYER_A, HOME),
                "the API's score read must agree with the snapshot and the screen");
    }

    @Test
    void theScoreReadPublishesTheDecayItObservesOnce() {
        ReputationService.currentScoreWith(ctx, TestFixtures.PLAYER_A, HOME);
        ReputationService.currentScoreWith(ctx, TestFixtures.PLAYER_A, HOME);

        assertEquals(1, mirrored.size(), "the mirror hears the decay exactly once");
        assertEquals(ChangeCause.DECAY, mirrored.get(0).cause());
        assertTrue(mirrored.get(0).quiet(), "decay is background movement, never news");
        assertEquals(-30, mirrored.get(0).oldScore());
        assertEquals(0, mirrored.get(0).newScore());
        assertEquals(1, ctx.posted(ReputationChangedEvent.class).size(),
                "one ReputationChangedEvent, so the scoreboard and tab list follow");
    }

    /** The condition path: Quests' gates, Conversations' conditions and the loot condition. */
    @Test
    void evaluatingAConditionPublishesTheDecayItObserves() {
        StandingAvailability.EffectiveStanding standing =
                ReputationService.effectiveStandingWith(ctx, TestFixtures.PLAYER_A, HOME);

        assertEquals(StandingAvailability.State.AVAILABLE, standing.state());
        assertEquals(0, standing.score());
        assertEquals("stranger", standing.tierId());
        assertEquals(1, mirrored.size(), "the step the condition observed reached the mirror");
        assertEquals(1, ctx.posted(ReputationChangedEvent.class).size(), "and the event bus");
    }

    /** Exactly once across paths: the periodic sweep after a read has nothing left to report. */
    @Test
    void theSweepAfterAReadDoesNotReportTheSameStepAgain() {
        ReputationService.effectiveStandingWith(ctx, TestFixtures.PLAYER_A, HOME);
        ReputationService.reconcileWith(ctx, TestFixtures.PLAYER_A, DAY);

        assertEquals(1, mirrored.size());
        assertEquals(1, ctx.posted(ReputationChangedEvent.class).size());
    }

    /** Off the server thread nothing is aged, so nothing can be absorbed there either. */
    @Test
    void offTheServerThreadTheReadAgesNothing() {
        ctx.serverThread = false;

        assertEquals(OptionalInt.of(-30), ReputationService.currentScoreWith(ctx, TestFixtures.PLAYER_A, HOME),
                "the stored value, untouched");
        assertEquals(0, mirrored.size());
        assertEquals(0, ctx.posted.size());
    }

    @Test
    void aPlayerWithNoRecordIsNeitherCreatedNorPublished() {
        assertEquals(OptionalInt.empty(),
                ReputationService.currentScoreWith(ctx, TestFixtures.PLAYER_B, HOME));
        assertTrue(ctx.data.player(TestFixtures.PLAYER_B).isEmpty(), "a read never grows the save");
        assertEquals(0, mirrored.size());
    }
}
