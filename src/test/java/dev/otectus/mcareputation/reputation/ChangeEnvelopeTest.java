package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.ReputationMirror;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.StandingChange;
import dev.otectus.mcareputation.api.event.ReputationChangedEvent;
import dev.otectus.mcareputation.api.event.ReputationTierChangedEvent;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T18: one semantic change, one envelope (§6 "Standing change").
 *
 * <p>Decay that crosses a tier boundary is the case that used to publish nothing at all, so a mirror
 * could hold a score the ledger had already left behind. It now publishes exactly once, quietly: the
 * mirror and the displayed tier follow, and nothing pretends the player just earned a milestone.
 */
class ChangeEnvelopeTest {

    private static final long DAY = DecayPolicy.TICKS_PER_DAY;
    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;

    private final TestServiceContext ctx = new TestServiceContext();
    private final List<StandingChange> mirrored = new ArrayList<>();
    private final RecordingMirror mirror = new RecordingMirror();

    @BeforeEach
    void setUp() {
        ReputationService.registerMirror(mirror);
    }

    @AfterEach
    void tearDown() {
        ReputationService.unregisterMirror(mirror);
        IncidentRegistry.replaceAll(Map.of());
        McaReputationConfig.TestOverrides.reset();
    }

    private final class RecordingMirror implements ReputationMirror {

        @Override
        public void mirrorStanding(StandingChange change) {
            mirrored.add(change);
        }

        @Override
        public void mirrorScore(UUID player, CommunityKey community, int score,
                                ResourceLocation ladder, String highWaterTierId) {
            throw new AssertionError("the service must call mirrorStanding, not mirrorScore");
        }

        @Override
        public void mirrorVillageTitle(UUID player, CommunityKey community, ResourceLocation title) {
        }

        @Override
        public void mirrorGlobalTitle(UUID player, ResourceLocation title) {
        }

        @Override
        public String mirrorName() {
            return "envelope-test";
        }
    }

    @Test
    void decayAcrossATierBoundaryPublishesOneQuietChange() {
        // -30 is 'distrusted'; a day later the whole contribution is gone and the player is a stranger again.
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(-30, IncidentVisibility.VILLAGE, DecayPolicy.linearToZero(0, 30))));
        CommunityReputationRecord record = ctx.data.getOrCreatePlayer(TestFixtures.PLAYER_A)
                .getOrCreate(HOME);
        record.addIncident(IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, HOME, 0L, TestFixtures.SOURCE, Optional.empty(), -30,
                IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE, List.of()));
        record.recomputeScore(McaReputationConfig.minimumScore(), McaReputationConfig.maximumScore());

        assertTrue(ReputationService.reconcileWith(ctx, TestFixtures.PLAYER_A, DAY));

        assertEquals(1, mirrored.size(), "the mirror sees the decay exactly once");
        StandingChange change = mirrored.get(0);
        assertEquals(ChangeCause.DECAY, change.cause());
        assertTrue(change.quiet(), "decay is background movement, not news");
        assertEquals(-30, change.oldScore());
        assertEquals(0, change.newScore());
        assertEquals("distrusted", change.oldTierId());
        assertEquals("stranger", change.newTierId());
        assertTrue(change.revision() > 0, "a real score change bumps the revision");

        List<ReputationChangedEvent> changed = ctx.posted(ReputationChangedEvent.class);
        assertEquals(1, changed.size(), "one semantic event, one ReputationChangedEvent");
        assertEquals(ChangeCause.DECAY, changed.get(0).cause());
        assertTrue(changed.get(0).quiet());
        for (ReputationTierChangedEvent tier : ctx.posted(ReputationTierChangedEvent.class)) {
            assertFalse(tier.firstTime(), "ageing into a tier is never a first time");
        }
        assertEquals(Optional.of("distrusted"), record.tierHighWater(ReputationTiers.DEFAULT_ID),
                "a quiet crossing seeds the high-water mark but never advances it");
    }

    @Test
    void aRecordedDeedPublishesOneLoudChange() {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(30, IncidentVisibility.VILLAGE, DecayPolicy.NONE)));

        assertTrue(ReputationService.recordWith(ctx, new ReputationRequest(null, TestFixtures.PLAYER_A,
                HOME, TestFixtures.ASSAULT, TestFixtures.SOURCE, Optional.empty(), OptionalInt.empty(),
                Optional.empty(), List.of(), Set.of(), Map.of(), 0L)).applied());

        assertEquals(1, mirrored.size(), "one deed, one publication");
        assertEquals(ChangeCause.DEED, mirrored.get(0).cause());
        assertFalse(mirrored.get(0).quiet());
        assertEquals(30, mirrored.get(0).newScore());

        List<ReputationChangedEvent> changed = ctx.posted(ReputationChangedEvent.class);
        assertEquals(1, changed.size());
        assertEquals(ChangeCause.DEED, changed.get(0).cause());
        assertFalse(changed.get(0).quiet());
        assertEquals(1, ctx.posted(ReputationTierChangedEvent.class).size());
        assertTrue(ctx.posted(ReputationTierChangedEvent.class).get(0).firstTime(),
                "an earned crossing is still a milestone");
    }
}
