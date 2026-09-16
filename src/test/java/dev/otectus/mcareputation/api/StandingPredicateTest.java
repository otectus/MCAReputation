package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.reputation.ReputationPolicy;
import dev.otectus.mcareputation.reputation.StandingAvailability;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T30/T31: generic standing predicates do not depend on an integration switch, and an opinion answer
 * says why it is or is not available (§5 F14, DD9).
 *
 * <p>The predicate half runs against {@link StandingAvailability}, which is exactly what
 * {@link McaReputationApi#matches} evaluates — the server-taking method itself needs a running game,
 * the truth table does not.
 */
class StandingPredicateTest {

    private final ReputationSavedData data = ReputationSavedData.createForTest();

    @AfterEach
    void tearDown() {
        McaReputationConfig.TestOverrides.reset();
    }

    private static final List<ReputationQuery> QUERIES = List.of(
            ReputationQuery.ANY,
            ReputationQuery.builder().min(0).build(),
            ReputationQuery.builder().min(10).build(),
            ReputationQuery.builder().max(0).build(),
            ReputationQuery.builder().minTier("stranger").build(),
            ReputationQuery.builder().minTier("friend").build(),
            ReputationQuery.builder().maxTier("stranger").build(),
            ReputationQuery.builder().hasTitle(new ResourceLocation("mcaquests", "friend_of_village")).build());

    private boolean matches(ReputationPolicy policy, ReputationQuery query) {
        StandingAvailability.EffectiveStanding standing = StandingAvailability.of(policy, data,
                TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 0L);
        return StandingAvailability.matches(standing, query, Set.of(), Set.of());
    }

    // ------------------------------------------------------------------
    // T30
    // ------------------------------------------------------------------

    @Test
    void theConversationsToggleCannotChangeAGenericPredicate() {
        seedScore(40);

        for (ReputationQuery query : QUERIES) {
            McaReputationConfig.TestOverrides.conversationsIntegration = true;
            boolean on = matches(ReputationPolicy.defaults().withConversationsIntegrationEnabled(true), query);
            McaReputationConfig.TestOverrides.conversationsIntegration = false;
            boolean off = matches(ReputationPolicy.defaults().withConversationsIntegrationEnabled(false), query);

            assertEquals(on, off, "a dialogue mod's switch cannot disable a loot condition: " + query);
        }
    }

    @Test
    void aNeutralPredicateAnswersTheSameBeforeAndAfterARecordExists() {
        ReputationQuery minZero = ReputationQuery.builder().min(0).build();

        assertTrue(matches(ReputationPolicy.defaults(), minZero),
                "everyone starts a stranger, and a stranger satisfies min: 0");
        assertTrue(data.player(TestFixtures.PLAYER_A).isEmpty(),
                "asking the question must not write a record");

        seedScore(0);
        assertTrue(matches(ReputationPolicy.defaults(), minZero),
                "and the answer does not change once the record exists");
    }

    @Test
    void anUnresolvableCommunityFailsClosed() {
        StandingAvailability.EffectiveStanding standing = StandingAvailability.of(
                ReputationPolicy.defaults(), data, TestFixtures.PLAYER_A, null, 0L);

        assertEquals(StandingAvailability.State.UNAVAILABLE_COMMUNITY, standing.state());
        assertFalse(StandingAvailability.matches(standing, ReputationQuery.ANY, Set.of(), Set.of()),
                "no community, no match — nothing is invented for it");
    }

    @Test
    void aDisabledMasterSwitchEvaluatesAgainstTheNeutralValue() {
        seedScore(40);
        ReputationPolicy disabled = ReputationPolicy.defaults().withEnabled(false);

        StandingAvailability.EffectiveStanding standing = StandingAvailability.of(disabled, data,
                TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 0L);

        assertEquals(StandingAvailability.State.MASTER_DISABLED, standing.state());
        assertEquals(0, standing.score(), "gameplay influence is neutral while the mod is off");
        assertTrue(matches(disabled, ReputationQuery.builder().min(0).build()));
        assertFalse(matches(disabled, ReputationQuery.builder().min(10).build()));
    }

    @Test
    void aTitlePredicateIsAbsentRatherThanUnspecifiedForANeutralStanding() {
        ResourceLocation title = new ResourceLocation("mcaquests", "friend_of_village");

        assertFalse(StandingAvailability.matches(
                        new StandingAvailability.EffectiveStanding(StandingAvailability.State.NEUTRAL_NEW,
                                0, StandingAvailability.neutralTierId()),
                        ReputationQuery.builder().hasTitle(title).build(), Set.of(title), Set.of()),
                "a title the player has not earned is absent, not a wildcard");
    }

    // ------------------------------------------------------------------
    // T31
    // ------------------------------------------------------------------

    @Test
    void aVillagerWhoKnowsNothingIsAvailableWithAZeroOpinion() {
        OpinionResult result = McaReputationApi.uninformedOpinion(TestFixtures.VILLAGER_1,
                TestFixtures.OVERWORLD_3);

        assertEquals(OpinionResult.OpinionAvailability.AVAILABLE, result.availability(),
                "private ignorance must never be answered with public standing");
        assertEquals(0, result.opinion().orElseThrow().opinion());
        assertEquals(VillagerOpinion.OpinionBasis.NONE, result.opinion().orElseThrow().basis());
    }

    @Test
    void aSwitchedOffOpinionFeatureSaysDisabled() {
        McaReputationConfig.TestOverrides.villagerOpinion = false;

        OpinionResult result = McaReputationApi.getVillagerOpinionDetailed(null, TestFixtures.PLAYER_A,
                TestFixtures.VILLAGER_1, TestFixtures.OVERWORLD_3);

        assertEquals(OpinionResult.OpinionAvailability.DISABLED, result.availability());
        assertTrue(result.opinion().isEmpty());
        assertTrue(McaReputationApi.getVillagerOpinion(null, TestFixtures.PLAYER_A,
                TestFixtures.VILLAGER_1, TestFixtures.OVERWORLD_3).isEmpty(),
                "the original overload keeps its shape and delegates");
    }

    @Test
    void anUnresolvableContextSaysUnresolved() {
        OpinionResult result = McaReputationApi.getVillagerOpinionDetailed(null, TestFixtures.PLAYER_A,
                null, TestFixtures.OVERWORLD_3);

        assertEquals(OpinionResult.OpinionAvailability.UNRESOLVED, result.availability());
        assertTrue(result.opinion().isEmpty());
    }

    private void seedScore(int score) {
        CommunityReputationRecord record = data.getOrCreatePlayer(TestFixtures.PLAYER_A)
                .getOrCreate(TestFixtures.OVERWORLD_3);
        if (score != 0) {
            record.addIncident(IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                    TestFixtures.PLAYER_A, TestFixtures.OVERWORLD_3, 0L, TestFixtures.SOURCE,
                    Optional.empty(), score, IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE,
                    List.of()));
        }
        record.recomputeScore(McaReputationConfig.minimumScore(), McaReputationConfig.maximumScore());
    }
}
