package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.SpeakerContext;
import dev.otectus.mcareputation.api.VillagerOpinion.OpinionBasis;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.FacetDefinition;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.profile.VillagerProfileResolver;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import net.minecraft.network.chat.Component;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §13's observer interpretation end to end: what one villager knows, then what they make of it.
 *
 * <p>The ordering is the whole subject. A knowledge-filtered profile computed by scaling the
 * community's vector would pass any test that only checks magnitudes, and would still leak the
 * <em>shape</em> of evidence the observer never learned (§13.1). So the assertions here are about
 * presence and absence per facet, per observer, against the same ledger — and about the two API entry
 * points agreeing, because a second trait resolver is how they stop.
 *
 * <p>The last group is the one that protects everybody else's code: whatever a pack authors and an
 * operator configures, the term that actually leaves this mod for a Trust/Respect check stays inside
 * the existing ±8 envelope, and stays <em>one</em> term.
 */
class ObserverInterpretationTest {

    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;

    private TestDeliverySeam seam;

    @BeforeEach
    void setUp() {
        seam = new TestDeliverySeam().policy(ReputationPolicy.defaults()).gameTime(TestFixtures.DAY);
        // WITNESSED, so a resident who was not there has to wait out their own rumour delay: the only
        // visibility where "the village knows" and "this villager knows" can differ.
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT, TestFixtures.definition(0,
                IncidentVisibility.WITNESSED, DecayPolicy.NONE, TestFixtures.PROFILE)));
        publish(TestFixtures.facet());
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

    private void publish(FacetDefinition facet) {
        TestFixtures.publishProfile(TestFixtures.profile(null), null,
                Map.of(TestFixtures.FACET, facet));
    }

    /** A facet definition with an authored weight and personality overrides. */
    private static FacetDefinition facet(int weightBp, Map<String, Integer> overrides) {
        return new FacetDefinition(Component.literal("Bravery"), Optional.empty(),
                new FacetDefinition.Range(-100, 100), Component.literal("Brave"),
                Optional.of(Component.literal("Cowardly")), 10, 5, 2, weightBp, overrides);
    }

    /** One accepted deed carrying the test profile: 6 recognition, 8 bravery. */
    private UUID deed(Set<UUID> witnesses) {
        return seam.record(new ReputationRequest(null, TestFixtures.PLAYER_A, HOME,
                TestFixtures.ASSAULT, TestFixtures.SOURCE, Optional.empty(), OptionalInt.empty(),
                Optional.empty(), List.of(), witnesses, Map.of(), seam.gameTime()))
                .incidentId().orElseThrow();
    }

    private VillagerProfileSnapshot view(UUID villager, boolean resident,
                                         VillagerProfileResolver.ObserverTraits traits) {
        return ProfileService.speakerProfile(seam.policySnapshot(), seam.store(), TestFixtures.PLAYER_A,
                        HOME, SpeakerContext.of(villager, resident), traits, seam.gameTime())
                .value().orElseThrow();
    }

    private VillagerProfileSnapshot view(UUID villager, boolean resident) {
        return view(villager, resident, VillagerProfileResolver.ObserverTraits.NEUTRAL);
    }

    private CommunityReputationRecord ledger() {
        return seam.store().player(TestFixtures.PLAYER_A).orElseThrow().community(HOME).orElseThrow();
    }

    // ------------------------------------------------------------------
    // Filter first, then aggregate (§13.1)
    // ------------------------------------------------------------------

    @Test
    void aVillagerWhoDidNotWitnessADeedDoesNotSeeItsFacetWhileTheVillageDoes() {
        deed(Set.of(TestFixtures.VILLAGER_1));

        // The community holds the evidence.
        assertTrue(ProfileService.profile(seam.policySnapshot(), seam.store(), TestFixtures.PLAYER_A,
                        HOME, seam.gameTime(), false)
                .value().orElseThrow().facet(TestFixtures.FACET).isPresent(),
                "the village's own profile carries the facet");

        VillagerProfileSnapshot witness = view(TestFixtures.VILLAGER_1, true);
        assertTrue(witness.knownProfile().facet(TestFixtures.FACET).isPresent());
        assertEquals(8, witness.knownProfile().facet(TestFixtures.FACET).orElseThrow().value());
        assertEquals(1, witness.witnessedCount());

        // A resident who was not there is inside their rumour delay and knows nothing yet. Not a
        // faded copy of the village's view: no facet at all.
        VillagerProfileSnapshot uninformed = view(TestFixtures.VILLAGER_2, true);
        assertTrue(uninformed.knownProfile().facet(TestFixtures.FACET).isEmpty(),
                "a knowledge-filtered profile must not reveal a facet its observer has not learned");
        assertTrue(uninformed.knownProfile().facets().isEmpty());
        assertEquals(0, uninformed.knownProfile().recognition().value());
        assertEquals(0, uninformed.knownIncidents());
        assertEquals(0, uninformed.facetAdjustment());
        assertFalse(uninformed.knowsAnything(), "and that valid zero stays zero (§13.3)");
    }

    @Test
    void theRumourReachesTheFacetAndTheOpinionAtTheSameMoment() {
        seam.gameTime(0L);
        deed(Set.of(TestFixtures.VILLAGER_1));
        // Past the whole configured rumour window, so every resident has heard.
        seam.gameTime(ReputationPolicy.defaults().maxRumorDelayTicks() + 1L);

        VillagerProfileSnapshot heard = view(TestFixtures.VILLAGER_2, true);
        assertEquals(1, heard.hearsayCount());
        assertEquals(0, heard.witnessedCount());
        assertTrue(heard.knownProfile().facet(TestFixtures.FACET).isPresent(),
                "one awareness rule for both channels: the facet and the opinion learn together");
        // The same evidence the village holds at this moment, not a discounted copy of it: §13.1
        // forbids a hearsay coefficient over the facet vector. The deed has aged two in-game days by
        // now, so the number to compare against is the community's own current one.
        int community = ProfileService.profile(seam.policySnapshot(), seam.store(),
                        TestFixtures.PLAYER_A, HOME, seam.gameTime(), false)
                .value().orElseThrow().facet(TestFixtures.FACET).orElseThrow().value();
        assertEquals(community, heard.knownProfile().facet(TestFixtures.FACET).orElseThrow().value(),
                "hearsay is a filter on which deeds are known, not a discount on their evidence");
        assertTrue(community > 0 && community < 8, "and the deed has faded a little by now, as authored");
    }

    @Test
    void dominantTraitsAreComputedFromTheObserversOwnEvidence() {
        // Two witnessed deeds: 16 bravery on two evidence items, which is what §8.2's default
        // thresholds require before a label may describe anybody.
        deed(Set.of(TestFixtures.VILLAGER_1));
        deed(Set.of(TestFixtures.VILLAGER_1));

        VillagerProfileSnapshot witness = view(TestFixtures.VILLAGER_1, true);
        assertEquals(List.of(TestFixtures.FACET), witness.knownProfile().dominantFacets());
        assertEquals(16, witness.knownProfile().facet(TestFixtures.FACET).orElseThrow().value());

        assertTrue(view(TestFixtures.VILLAGER_2, true).knownProfile().dominantFacets().isEmpty(),
                "a villager with no evidence describes the player as nothing at all");
    }

    @Test
    void aSingleDeedIsNotEnoughEvidenceToDescribeSomeone() {
        deed(Set.of(TestFixtures.VILLAGER_1));
        VillagerProfileSnapshot witness = view(TestFixtures.VILLAGER_1, true);
        assertTrue(witness.knownProfile().facet(TestFixtures.FACET).isPresent(),
                "the value is known");
        assertTrue(witness.knownProfile().dominantFacets().isEmpty(),
                "but one demonstration does not earn a label (§8.2)");
    }

    // ------------------------------------------------------------------
    // One resolver behind both entry points (§13.2)
    // ------------------------------------------------------------------

    @Test
    void theUuidAndEntityEntryPointsAnswerIdenticallyForTheSameVillager() {
        deed(Set.of(TestFixtures.VILLAGER_1));
        // Both API overloads resolve traits through the one resolver and then call this one read
        // model. With MCA unavailable both resolve NEUTRAL, which is the case a server without MCA
        // actually runs; the assertion that matters is that neither path has an answer of its own.
        VillagerProfileResolver.ObserverTraits fromEntity = VillagerProfileResolver.traits(null);
        VillagerProfileResolver.ObserverTraits fromUuid =
                VillagerProfileResolver.traits(null, HOME, TestFixtures.VILLAGER_1);
        assertEquals(fromEntity, fromUuid);
        assertEquals(view(TestFixtures.VILLAGER_1, true, fromEntity),
                view(TestFixtures.VILLAGER_1, true, fromUuid));
        // And the overload that takes no traits at all is the same neutral answer, so an older caller
        // is never quietly given a different interpretation.
        assertEquals(view(TestFixtures.VILLAGER_1, true, fromEntity),
                ProfileService.speakerProfile(seam.policySnapshot(), seam.store(),
                                TestFixtures.PLAYER_A, HOME,
                                SpeakerContext.of(TestFixtures.VILLAGER_1, true), seam.gameTime())
                        .value().orElseThrow());
    }

    @Test
    void twoObserversWithTheSameKnowledgeDifferOnlyInTheirInterpretation() {
        publish(facet(5_000, Map.of("upbeat", 10_000)));
        deed(Set.of(TestFixtures.VILLAGER_1));

        VillagerProfileSnapshot neutral = view(TestFixtures.VILLAGER_1, true);
        VillagerProfileSnapshot upbeat = view(TestFixtures.VILLAGER_1, true,
                VillagerProfileResolver.ObserverTraits.of("upbeat", "minecraft:farmer"));

        assertEquals(neutral.knownProfile(), upbeat.knownProfile(),
                "interpretation may not change what the observer knows");
        assertEquals(4, neutral.facetAdjustment());
        assertEquals(8, upbeat.facetAdjustment());
        assertEquals(VillagerProfileSnapshot.TraitBasis.NEUTRAL_DEFAULT, neutral.traitBasis());
        assertEquals(VillagerProfileSnapshot.TraitBasis.RESOLVED, upbeat.traitBasis());
        assertNotEquals(neutral.finalOpinion(), upbeat.finalOpinion());
    }

    @Test
    void anObserverWithNoStoredRecordStillReportsAnHonestTraitBasis() {
        // No deed at all: the synthesised neutral read model must still say how it would interpret.
        VillagerProfileSnapshot resolved = view(TestFixtures.VILLAGER_1, true,
                VillagerProfileResolver.ObserverTraits.of("upbeat", null));
        assertEquals(VillagerProfileSnapshot.TraitBasis.RESOLVED, resolved.traitBasis());
        assertEquals(0, resolved.facetAdjustment());
        assertFalse(resolved.knowsAnything());

        seam.policy(ReputationPolicy.defaults().withFacetOpinionEnabled(false));
        assertEquals(VillagerProfileSnapshot.TraitBasis.DISABLED,
                view(TestFixtures.VILLAGER_1, true,
                        VillagerProfileResolver.ObserverTraits.of("upbeat", null)).traitBasis());
    }

    // ------------------------------------------------------------------
    // The existing opinion is untouched where there is nothing to add
    // ------------------------------------------------------------------

    @Test
    void anOpinionWithNoProfileEvidenceIsTheExistingAnswerExactly() {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(-8, IncidentVisibility.WITNESSED, DecayPolicy.NONE)));
        deed(Set.of(TestFixtures.VILLAGER_1));
        CommunityReputationRecord record = ledger();
        ReputationPolicy policy = seam.policySnapshot();

        OpinionResolver.Opinion base = OpinionResolver.resolve(record, TestFixtures.VILLAGER_1, true,
                seam.gameTime(), policy.minRumorDelayTicks(), policy.maxRumorDelayTicks(),
                policy.opinionHearsayPercent(), policy.opinionInvolvedPercent(),
                policy.minimumScore(), policy.maximumScore());
        OpinionResolver.ProfiledOpinion profiled = OpinionResolver.resolveProfiled(policy, record,
                ProfileRegistryBundle.current(), TestFixtures.VILLAGER_1, true, seam.gameTime(),
                VillagerProfileResolver.ObserverTraits.of("upbeat", null));

        assertEquals(-8, base.score(), "the 0.4.x witness weighting, unchanged");
        assertEquals(base.score(), profiled.score());
        assertEquals(0, profiled.facetAdjustment());
        assertEquals(base.basis(), profiled.basis());
        assertEquals(OpinionBasis.WITNESSED, profiled.basis());
        assertEquals(base.knownIncidents(), profiled.knownIncidents());
    }

    @Test
    void switchingFacetInterpretationOffRestoresTheExistingAnswerByteForByte() {
        deed(Set.of(TestFixtures.VILLAGER_1));
        CommunityReputationRecord record = ledger();
        ReputationPolicy off = ReputationPolicy.defaults().withFacetOpinionEnabled(false);

        OpinionResolver.Opinion base = OpinionResolver.resolve(record, TestFixtures.VILLAGER_1, true,
                seam.gameTime(), off.minRumorDelayTicks(), off.maxRumorDelayTicks(),
                off.opinionHearsayPercent(), off.opinionInvolvedPercent(), off.minimumScore(),
                off.maximumScore());
        OpinionResolver.ProfiledOpinion profiled = OpinionResolver.resolveProfiled(off, record,
                ProfileRegistryBundle.current(), TestFixtures.VILLAGER_1, true, seam.gameTime(),
                VillagerProfileResolver.ObserverTraits.of("upbeat", null));

        assertEquals(base.score(), profiled.score());
        assertEquals(0, profiled.facetAdjustment());
        assertEquals(VillagerProfileSnapshot.TraitBasis.DISABLED, profiled.traitBasis());
    }

    // ------------------------------------------------------------------
    // The two bounds, and the ±8 the outside world sees (§13.2)
    // ------------------------------------------------------------------

    @Test
    void theFacetTermIsBoundedByTheAuthoredWeightAndByTheOperatorCap() {
        publish(facet(10_000, Map.of()));
        deed(Set.of(TestFixtures.VILLAGER_1));
        assertEquals(8, view(TestFixtures.VILLAGER_1, true).facetAdjustment(),
                "a fully weighted facet contributes its whole value");

        publish(facet(2_500, Map.of()));
        assertEquals(2, view(TestFixtures.VILLAGER_1, true).facetAdjustment(),
                "and a quarter-weighted one a quarter of it, truncated toward zero");

        publish(facet(10_000, Map.of()));
        seam.policy(ReputationPolicy.defaults().withMaxFacetOpinionAdjustment(3));
        assertEquals(3, view(TestFixtures.VILLAGER_1, true).facetAdjustment(),
                "the operator's cap binds whatever the pack authored");
    }

    @Test
    void theCombinedExternalContributionStaysWithinTheExistingEightPointLimit() {
        publish(facet(10_000, Map.of()));
        // §13.2's hard ceiling for the facet term, and enough evidence to saturate it.
        seam.policy(ReputationPolicy.defaults().withMaxFacetOpinionAdjustment(100));
        for (int i = 0; i < 20; i++) {
            deed(Set.of(TestFixtures.VILLAGER_1));
        }
        VillagerProfileSnapshot witness = view(TestFixtures.VILLAGER_1, true);
        assertEquals(100, witness.facetAdjustment(), "the facet term is at its hard bound");
        assertEquals(witness.baseOpinion() + 100, witness.finalOpinion());

        for (String axis : List.of("trust", "respect")) {
            int bias = OpinionResolver.externalCheckBias(ReputationTiers.BUILTIN_DEFAULT,
                    witness.finalOpinion(), axis);
            assertTrue(Math.abs(bias) <= ReputationTier.BIAS_SHIPPED_LIMIT,
                    axis + " bias " + bias + " must stay within the existing ±"
                            + ReputationTier.BIAS_SHIPPED_LIMIT + " limit");
            // One term from one rung, never the rung's bias plus a facet bonus beside it (R04).
            assertEquals(ReputationTiers.BUILTIN_DEFAULT.tierFor(witness.finalOpinion()).biasFor(axis),
                    bias);
        }
        assertEquals(0, OpinionResolver.externalCheckBias(ReputationTiers.BUILTIN_DEFAULT,
                witness.finalOpinion(), "warmth"),
                "public standing has no business touching a private disposition axis");
    }

    @Test
    void theExternalContributionIsWithinTheLimitAtEveryPointOnTheLadder() {
        // The invariant, not one sample of it: whatever a facet term does to an opinion, the term that
        // leaves this mod is one authored tier bias, and every one of those is within ±8.
        for (int score : List.of(Integer.MIN_VALUE, -100_000, -1000, -300, -151, -75, -25, -1, 0, 24,
                25, 75, 149, 150, 299, 300, 1000, 100_000, Integer.MAX_VALUE)) {
            for (String axis : List.of("trust", "respect")) {
                int bias = OpinionResolver.externalCheckBias(ReputationTiers.BUILTIN_DEFAULT, score,
                        axis);
                assertTrue(Math.abs(bias) <= ReputationTier.BIAS_SHIPPED_LIMIT,
                        "score " + score + " produced " + axis + " bias " + bias);
            }
        }
        assertEquals(0, OpinionResolver.externalCheckBias(null, 300, "trust"),
                "no ladder is no contribution, not a guess");
    }

    @Test
    void adverseFacetEvidenceMovesTheOpinionDownAndStaysBounded() {
        publish(facet(-10_000, Map.of()));
        seam.policy(ReputationPolicy.defaults().withMaxFacetOpinionAdjustment(100));
        for (int i = 0; i < 20; i++) {
            deed(Set.of(TestFixtures.VILLAGER_1));
        }
        VillagerProfileSnapshot witness = view(TestFixtures.VILLAGER_1, true);
        assertEquals(-100, witness.facetAdjustment());
        assertTrue(witness.finalOpinion() < witness.baseOpinion());
        assertTrue(witness.finalOpinion() >= seam.policySnapshot().minimumScore(),
                "and never below the existing score window");
        int bias = OpinionResolver.externalCheckBias(ReputationTiers.BUILTIN_DEFAULT,
                witness.finalOpinion(), "respect");
        assertTrue(bias >= -ReputationTier.BIAS_SHIPPED_LIMIT);
    }
}
