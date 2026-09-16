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

        // §20's profile table, as policy rather than as a live config read. P7 wires these to the
        // spec; until then the snapshot carries the documented defaults, which is what the profile
        // arithmetic and the cap paths are decided against.
        assertTrue(policy.profilesEnabled());
        assertTrue(policy.repeatCreditEnabled());
        assertTrue(policy.facetOpinionEnabled());
        assertEquals(25, policy.maxFacetOpinionAdjustment());
        assertEquals(1000, policy.recognitionCap());
        assertEquals(100, policy.facetPointCap());
        assertTrue(policy.protectProfileEvidence(),
                "§12.3: live profile evidence is not prunable, and the cap paths read that from here");
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
