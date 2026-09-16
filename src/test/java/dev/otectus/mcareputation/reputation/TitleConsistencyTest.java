package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ReputationMirror;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.StandingChange;
import dev.otectus.mcareputation.api.TitleSnapshot;
import dev.otectus.mcareputation.api.event.ReputationChangedEvent;
import dev.otectus.mcareputation.api.event.ReputationTierChangedEvent;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRegistry;
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
 * T28 and T29: a title a mirror never hears about is a title the fallback copy gets wrong, and a
 * milestone the player stood in is owed to them however fast they got there (§6 "Title
 * synchronization", §5 F13).
 */
class TitleConsistencyTest {

    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;
    private static final ResourceLocation HONORED = new ResourceLocation("mcaquests", "honored_of_village");
    private static final ResourceLocation REVERED = new ResourceLocation("mcaquests", "revered_of_village");
    private static final ResourceLocation WANDERER = new ResourceLocation("mcareputation", "wanderer");
    private static final ResourceLocation CUSTOM_LADDER = new ResourceLocation("examplepack", "guild");

    private final TestServiceContext ctx = new TestServiceContext();
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

    /** A mirror that records everything it is told, in the order it is told. */
    private static final class RecordingMirror implements ReputationMirror {

        final List<StandingChange> standing = new ArrayList<>();
        final List<ResourceLocation> granted = new ArrayList<>();
        final List<ResourceLocation> revoked = new ArrayList<>();
        final List<Long> revisions = new ArrayList<>();
        final List<TitleSnapshot> states = new ArrayList<>();

        @Override
        public void mirrorScore(UUID player, CommunityKey community, int score, ResourceLocation ladder,
                                String highWaterTierId) {
        }

        @Override
        public void mirrorStanding(StandingChange change) {
            standing.add(change);
        }

        @Override
        public void mirrorVillageTitle(UUID player, CommunityKey community, ResourceLocation title) {
            granted.add(title);
        }

        @Override
        public void mirrorGlobalTitle(UUID player, ResourceLocation title) {
            granted.add(title);
        }

        @Override
        public void mirrorTitleRevoked(UUID player, Optional<CommunityKey> community, ResourceLocation title) {
            revoked.add(title);
        }

        @Override
        public void mirrorTitleState(UUID player, TitleSnapshot state, long revision) {
            states.add(state);
            revisions.add(revision);
        }
    }

    // --- grant and revoke reach the mirror ----------------------------------

    @Test
    void aVillageGrantAndItsRevocationBothReachTheMirror() {
        assertTrue(TitleService.grantVillage(ctx, TestFixtures.PLAYER_A, null, HOME, HONORED));
        assertTrue(mirror.granted.contains(HONORED), "a village grant used to reach no mirror at all");
        assertEquals(List.of(1L), mirror.revisions);
        assertTrue(mirror.states.get(0).has(HONORED));

        assertTrue(TitleService.revoke(ctx, TestFixtures.PLAYER_A, HOME, HONORED));
        assertEquals(List.of(HONORED), mirror.revoked);
        assertEquals(List.of(1L, 2L), mirror.revisions, "the revision only ever rises");
        assertFalse(mirror.states.get(1).has(HONORED), "and the state that follows is the whole truth");
    }

    @Test
    void theTitleRevisionSurvivesAReload() {
        TitleService.grantGlobal(ctx, TestFixtures.PLAYER_A, null, WANDERER);
        assertEquals(1L, ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().titleRevision());

        assertEquals(1L, ctx.data.roundTripForTest().player(TestFixtures.PLAYER_A).orElseThrow()
                .titleRevision(), "a mirror caching by revision must not see it restart at zero");
    }

    // --- a global-only holder -----------------------------------------------

    @Test
    void aGlobalOnlyTitleHolderIsEnumeratedWithNoCommunityRecord() {
        TitleService.grantGlobal(ctx, TestFixtures.PLAYER_A, null, WANDERER);

        assertEquals(Set.of(WANDERER), TitleService.globalTitles(ctx.data, TestFixtures.PLAYER_A));
        assertTrue(TitleService.hasTitle(ctx.data, TestFixtures.PLAYER_A, WANDERER, null),
                "a dialogue condition with no village in hand still finds a global title");
        assertTrue(TitleService.hasTitle(ctx.data, TestFixtures.PLAYER_A, WANDERER, HOME),
                "and naming a village the player has never visited does not hide it");
        assertTrue(ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().communities().isEmpty(),
                "no fake community may be created to answer any of that");
    }

    @Test
    void anUnknownPlayerAnswersEmptyAndIsNotCreated() {
        UUID stranger = UUID.randomUUID();
        assertEquals(Set.of(), TitleService.globalTitles(ctx.data, stranger));
        assertFalse(TitleService.hasTitle(ctx.data, stranger, WANDERER, null));
        assertEquals(0, ctx.data.playerCount());
    }

    // --- high-water per ladder ----------------------------------------------

    @Test
    void aCustomLaddersHighWaterQueryReturnsThatLaddersData() {
        CommunityReputationRecord community = ctx.data.getOrCreatePlayer(TestFixtures.PLAYER_A)
                .getOrCreate(HOME);
        community.setTierHighWater(ReputationTiers.DEFAULT_ID, "friend");
        community.setTierHighWater(CUSTOM_LADDER, "journeyman");

        assertEquals(Optional.of("journeyman"), ReputationService.tierHighWaterWith(ctx,
                TestFixtures.PLAYER_A, HOME, CUSTOM_LADDER));
        assertEquals(Optional.of("friend"), ReputationService.tierHighWaterWith(ctx,
                TestFixtures.PLAYER_A, HOME, ReputationTiers.DEFAULT_ID));
        assertEquals(Optional.empty(), ReputationService.tierHighWaterWith(ctx, TestFixtures.PLAYER_A,
                HOME, new ResourceLocation("examplepack", "never_ranked")));
    }

    // --- milestones ---------------------------------------------------------

    @Test
    void aJumpAcrossSeveralTiersGrantsEachMilestoneOnceAndAnnouncesOnce() {
        ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, HOME, 300, true,
                TestFixtures.SOURCE, 0L);

        CommunityReputationRecord community = ctx.data.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow();
        assertEquals(300, community.score());
        assertEquals(Set.of(HONORED, REVERED), community.titles(),
                "Honored was crossed on the way to Revered and is owed too");
        assertEquals(1, mirror.standing.size(), "one semantic change, one envelope");
        assertEquals(1, ctx.posted(ReputationChangedEvent.class).size());
        List<ReputationTierChangedEvent> tiers = ctx.posted(ReputationTierChangedEvent.class);
        assertEquals(1, tiers.size(), "and one tier notification, for where the player ended up");
        assertEquals("revered", tiers.get(0).newTierId());
        assertEquals(Optional.of("revered"), community.tierHighWater(ReputationTiers.DEFAULT_ID));
    }

    @Test
    void aSecondJumpGrantsNoMilestoneASecondTime() {
        ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, HOME, 300, true,
                TestFixtures.SOURCE, 0L);
        long revisionAfterFirst = ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().titleRevision();
        assertEquals(2L, revisionAfterFirst, "two badges, two changes to the title set");

        ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, HOME, 0, true,
                TestFixtures.SOURCE, 0L);
        ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, HOME, 300, true,
                TestFixtures.SOURCE, 0L);

        assertEquals(revisionAfterFirst,
                ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().titleRevision(),
                "a badge is earned once; the second climb changes no title at all");
    }

    @Test
    void decayAcrossATierBoundaryKeepsTheMilestoneTitle() {
        IncidentDefinition praise = new IncidentDefinition(
                net.minecraft.network.chat.Component.translatable("test.incident"), 300,
                IncidentVisibility.VILLAGE, dev.otectus.mcareputation.incident.IncidentSeverity.MAJOR,
                List.of("test"), Optional.empty(),
                new DecayPolicy(DecayPolicy.Type.LINEAR_TO_ZERO, 0L, 100),
                dev.otectus.mcareputation.incident.ResolutionPolicy.DEFAULT,
                dev.otectus.mcareputation.incident.GossipSpec.NONE, false, Optional.empty(), false, false);
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT, praise));

        ReputationService.recordWith(ctx, new ReputationRequest(null, TestFixtures.PLAYER_A, HOME,
                TestFixtures.ASSAULT, TestFixtures.SOURCE, Optional.empty(), OptionalInt.empty(),
                Optional.empty(), List.of(), Set.of(), Map.of(), 0L));

        CommunityReputationRecord community = ctx.data.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow();
        assertEquals(300, community.score());
        assertTrue(community.hasTitle(REVERED));

        // Three days of fading takes the player from Revered back to Stranger.
        ctx.gameTime = 3 * DecayPolicy.TICKS_PER_DAY;
        ReputationService.reconcileWith(ctx, TestFixtures.PLAYER_A, ctx.gameTime);

        assertEquals(0, community.score());
        assertTrue(community.hasTitle(REVERED), "a badge records where you once stood, and is not a readout");
        assertTrue(community.hasTitle(HONORED));
        assertTrue(mirror.revoked.isEmpty(), "and nothing was revoked behind the player's back");
        assertEquals(Optional.of("revered"), community.tierHighWater(ReputationTiers.DEFAULT_ID));
    }
}
