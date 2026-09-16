package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The policy snapshot under the one condition every pure test runs in: an unloaded config spec. If
// a field ever reaches past the guarded accessors, this is where the exception surfaces.
class ReputationPolicyTest {

    @AfterEach
    void tearDown() {
        McaReputationConfig.TestOverrides.reset();
    }

    @Test
    void fromConfigReturnsDocumentedDefaultsWhenTheSpecIsUnloaded() {
        ReputationPolicy policy = McaReputationConfig.snapshot();

        assertTrue(policy.enabled());
        assertTrue(policy.scoreDecayEnabled());
        assertTrue(policy.tierTitlesEnabled());
        assertEquals(ReputationBounds.DEFAULT_MIN_SCORE, policy.minimumScore());
        assertEquals(ReputationBounds.DEFAULT_MAX_SCORE, policy.maximumScore());
        assertEquals(128, policy.villageSearchRadius());
        assertEquals(24, policy.witnessRadius());
        assertEquals(ReputationBounds.MAX_WITNESSES, policy.maxWitnesses());
        assertTrue(policy.requireWitnessLineOfSight());
        assertEquals(6000, policy.minRumorDelayTicks());
        assertEquals(48000, policy.maxRumorDelayTicks());
        assertTrue(policy.villagerOpinionEnabled());
        assertEquals(50, policy.opinionHearsayPercent());
        assertEquals(150, policy.opinionInvolvedPercent());
        assertEquals(ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY, policy.maxIncidentsPerCommunity());
        assertEquals(ReputationBounds.MAX_INCIDENTS_PER_PLAYER, policy.maxIncidentsPerPlayer());
        assertEquals(200, policy.assaultCoalesceTicks());
        assertEquals(100, policy.selfDefenseWindowTicks());
        assertEquals(1200, policy.reconcileOnlineIntervalTicks());
        assertEquals(ReputationPolicy.DEFAULT_RECEIPT_RETENTION_TICKS, policy.receiptRetentionTicks());
        assertEquals(ReputationPolicy.UndeclaredAuthorityMode.ASSAULT_KILL_ONLY,
                policy.undeclaredAuthorityMode());

        // §20's profile table, read from the spec through the guarded accessors. With no spec loaded
        // — which is every pure test, datagen, and a client that never joined a server — those
        // accessors answer with the documented defaults, which is what the profile arithmetic and the
        // cap paths are decided against.
        assertTrue(policy.profilesEnabled());
        assertTrue(policy.repeatCreditEnabled());
        assertTrue(policy.facetOpinionEnabled());
        assertEquals(25, policy.maxFacetOpinionAdjustment());
        assertEquals(1000, policy.recognitionCap());
        assertEquals(100, policy.facetPointCap());
        assertTrue(policy.protectProfileEvidence(),
                "§12.3: live profile evidence is not prunable, and the cap paths read that from here");
    }

    /**
     * The wiring P7 added: §20's four COMMON profile settings, read through the guarded accessors.
     *
     * <p>Until this existed the policy carried the documented defaults and an operator switching
     * profiles off changed nothing at all — the switch was in {@code CONFIG.md} and in the spec, and
     * nowhere in the code path that decides anything.
     */
    @Test
    void theProfileSwitchesFollowTheConfig() {
        McaReputationConfig.TestOverrides.profiles = false;
        McaReputationConfig.TestOverrides.repeatCredit = false;
        McaReputationConfig.TestOverrides.facetOpinion = false;
        McaReputationConfig.TestOverrides.maxFacetOpinionAdjustment = 7;

        ReputationPolicy policy = McaReputationConfig.snapshot();
        assertFalse(policy.profilesEnabled());
        assertFalse(policy.repeatCreditEnabled());
        assertFalse(policy.facetOpinionEnabled());
        assertEquals(7, policy.maxFacetOpinionAdjustment());
        assertNotEquals(ReputationPolicy.defaults(), policy);

        // The two quantities §20 does not offer as settings stay where the stored subunits are
        // interpreted: an operator lowering either would reinterpret evidence a player already earned.
        assertEquals(ReputationPolicy.DEFAULT_RECOGNITION_CAP, policy.recognitionCap());
        assertEquals(ReputationPolicy.DEFAULT_FACET_POINT_CAP, policy.facetPointCap());
        assertTrue(policy.protectProfileEvidence());
    }

    /** §20's 0..100 range holds even against a hand-edited TOML: config tightens, never loosens. */
    @Test
    void theFacetAdjustmentCapIsClampedToItsDocumentedRange() {
        McaReputationConfig.TestOverrides.maxFacetOpinionAdjustment = 5000;
        assertEquals(ReputationPolicy.MAX_FACET_OPINION_ADJUSTMENT_LIMIT,
                McaReputationConfig.snapshot().maxFacetOpinionAdjustment());

        McaReputationConfig.TestOverrides.maxFacetOpinionAdjustment = -40;
        assertEquals(0, McaReputationConfig.snapshot().maxFacetOpinionAdjustment(),
                "zero is a legal answer: it switches the facet term off without switching the "
                        + "interpretation's reporting off with it");
    }

    @Test
    void theProfileFieldsSurviveTheBuilderOneAtATime() {
        ReputationPolicy base = ReputationPolicy.defaults();

        assertFalse(base.withProfilesEnabled(false).profilesEnabled());
        assertFalse(base.withRepeatCreditEnabled(false).repeatCreditEnabled());
        assertFalse(base.withFacetOpinionEnabled(false).facetOpinionEnabled());
        assertFalse(base.withProtectProfileEvidence(false).protectProfileEvidence());
        assertEquals(0, base.withMaxFacetOpinionAdjustment(0).maxFacetOpinionAdjustment());
        assertEquals(base, base.withMaxFacetOpinionAdjustment(7)
                .withMaxFacetOpinionAdjustment(25), "and one field at a time means one field");
        assertEquals(base.maxIncidentsPerCommunity(),
                base.withProfilesEnabled(false).maxIncidentsPerCommunity());
    }

    @Test
    void defaultsMirrorFromConfigInAnUnloadedJvm() {
        assertEquals(ReputationPolicy.defaults(), ReputationPolicy.fromConfig());
    }

    @Test
    void withChangesOneFieldAndLeavesTheRest() {
        ReputationPolicy base = ReputationPolicy.defaults();
        ReputationPolicy noDecay = base.withScoreDecayEnabled(false);

        assertFalse(noDecay.scoreDecayEnabled());
        assertNotEquals(base, noDecay);
        assertEquals(base, noDecay.withScoreDecayEnabled(true));

        ReputationPolicy ignored =
                base.withUndeclaredAuthorityMode(ReputationPolicy.UndeclaredAuthorityMode.IGNORE);
        assertEquals(ReputationPolicy.UndeclaredAuthorityMode.IGNORE, ignored.undeclaredAuthorityMode());
        assertEquals(base.maxIncidentsPerPlayer(), ignored.maxIncidentsPerPlayer());

        assertEquals(42L, base.withReceiptRetentionTicks(42L).receiptRetentionTicks());
    }
}
