package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.profile.ProfileMath;

// An immutable snapshot of the policy-relevant COMMON config, taken once and read many times.
public record ReputationPolicy(
        boolean enabled,
        boolean scoreDecayEnabled,
        boolean tierTitlesEnabled,
        int minimumScore,
        int maximumScore,
        int villageSearchRadius,
        int witnessRadius,
        int maxWitnesses,
        boolean requireWitnessLineOfSight,
        int minRumorDelayTicks,
        int maxRumorDelayTicks,
        boolean villagerOpinionEnabled,
        int opinionHearsayPercent,
        int opinionInvolvedPercent,
        int maxIncidentsPerCommunity,
        int maxIncidentsPerPlayer,
        int assaultCoalesceTicks,
        int selfDefenseWindowTicks,
        int reconcileOnlineIntervalTicks,
        long receiptRetentionTicks,
        UndeclaredAuthorityMode undeclaredAuthorityMode,
        boolean conversationsIntegrationEnabled,
        // The two profile switches of §20, as policy rather than as a live config read: one snapshot
        // per operation is what stops a mid-transaction reload from creating evidence under one rule
        // and accounting for it under another (I09). Both now follow the COMMON config through the
        // guarded accessors, so a reload moves them exactly where it moves every other field here.
        boolean profilesEnabled,
        boolean repeatCreditEnabled,
        // The rest of §20's profile table that is policy rather than presentation. The four show*
        // settings are CLIENT-side display and deliberately absent: this snapshot is the COMMON
        // policy the server decides with, and a client preference must never be able to change what
        // is recorded. Profile lifetimes and decay steps are absent for a different reason — §9.3 and
        // §17 author them per contribution in the datapack and §9.4 freezes them onto each deed, so a
        // config override would be a live reinterpretation of quantities a player already earned.
        boolean facetOpinionEnabled,
        int maxFacetOpinionAdjustment,
        int recognitionCap,
        int facetPointCap,
        boolean protectProfileEvidence) {

    // How an incident claimed by an authority that declared no kinds is treated.
    public enum UndeclaredAuthorityMode {
        // Honour whatever kind the undeclared authority claims.
        TRUST_LEGACY,
        // Honour only the two kinds that existed when undeclared authorities were written.
        ASSAULT_KILL_ONLY,
        // Honour nothing; an authority that declares nothing claims nothing.
        IGNORE
    }

    // Fourteen in-game days of ticks (24000 per day): how long a receipt stays answerable.
    public static final long DEFAULT_RECEIPT_RETENTION_TICKS = 336000L;

    public static final UndeclaredAuthorityMode DEFAULT_UNDECLARED_AUTHORITY_MODE =
            UndeclaredAuthorityMode.ASSAULT_KILL_ONLY;

    // §20's documented defaults. Profiles on: a fresh world gets recognition and facets from its
    // first deed. Repeat credit on: the shipped schedules are the anti-farm story, and an installation
    // that silently ran without them would accumulate history no later switch could correct.
    public static final boolean DEFAULT_PROFILES_ENABLED = true;
    public static final boolean DEFAULT_REPEAT_CREDIT_ENABLED = true;

    /** §20: the bounded facet interpretation is on, and it is an opinion adjustment, not a write. */
    public static final boolean DEFAULT_FACET_OPINION_ENABLED = true;

    /** §20's documented default for the combined facet adjustment. */
    public static final int DEFAULT_MAX_FACET_OPINION_ADJUSTMENT = 25;

    /** §20's hard upper end of that setting's 0..100 range; the config spec cannot exceed it. */
    public static final int MAX_FACET_OPINION_ADJUSTMENT_LIMIT = 100;

    /** §7.1: public recognition is 0..1000, with no conversion from standing. */
    public static final int DEFAULT_RECOGNITION_CAP = ProfileMath.MAX_RECOGNITION;

    /** §8.1/§9.6: a facet's displayed magnitude never exceeds 100 points, whatever a pack authors. */
    public static final int DEFAULT_FACET_POINT_CAP = ProfileMath.MAX_FACET_POINTS;

    /**
     * §12.3: a record holding live profile subunits is not prunable. Not offered as a config setting
     * in §20 and not one here either — it is the retention rule the cap paths are decided against,
     * carried in the snapshot so the refusal and the eviction pass cannot read it at two moments and
     * disagree. A future operator override would be a decision to discard evidence, which §12.3 says
     * requires measured save-size evidence rather than a switch.
     */
    public static final boolean DEFAULT_PROTECT_PROFILE_EVIDENCE = true;

    // Reads through the guarded accessors only, so this is safe before the spec is loaded.
    public static ReputationPolicy fromConfig() {
        return new ReputationPolicy(
                McaReputationConfig.enabled(),
                McaReputationConfig.scoreDecayEnabled(),
                McaReputationConfig.tierTitlesEnabled(),
                McaReputationConfig.minimumScore(),
                McaReputationConfig.maximumScore(),
                McaReputationConfig.villageSearchRadius(),
                McaReputationConfig.witnessRadius(),
                McaReputationConfig.maxWitnesses(),
                McaReputationConfig.requireWitnessLineOfSight(),
                McaReputationConfig.minRumorDelayTicks(),
                McaReputationConfig.maxRumorDelayTicks(),
                McaReputationConfig.villagerOpinionEnabled(),
                McaReputationConfig.opinionHearsayPercent(),
                McaReputationConfig.opinionInvolvedPercent(),
                McaReputationConfig.maxIncidentsPerCommunity(),
                McaReputationConfig.maxIncidentsPerPlayer(),
                McaReputationConfig.assaultCoalesceTicks(),
                McaReputationConfig.selfDefenseWindowTicks(),
                McaReputationConfig.reconcileOnlineIntervalTicks(),
                McaReputationConfig.receiptRetentionTicks(),
                McaReputationConfig.coreAuthorityUndeclaredKinds(),
                McaReputationConfig.conversationsIntegrationEnabled(),
                McaReputationConfig.profilesEnabled(),
                McaReputationConfig.repeatCreditEnabled(),
                McaReputationConfig.facetOpinionEnabled(),
                McaReputationConfig.maxFacetOpinionAdjustment(),
                // Not config settings: §20 offers neither, and both are the units the stored subunits
                // are interpreted in, so an operator lowering one would reinterpret earned evidence.
                DEFAULT_RECOGNITION_CAP,
                DEFAULT_FACET_POINT_CAP,
                DEFAULT_PROTECT_PROFILE_EVIDENCE);
    }

    // The documented config defaults, mirrored without touching the spec at all.
    public static ReputationPolicy defaults() {
        return new ReputationPolicy(
                true,
                true,
                true,
                ReputationBounds.DEFAULT_MIN_SCORE,
                ReputationBounds.DEFAULT_MAX_SCORE,
                128,
                24,
                ReputationBounds.MAX_WITNESSES,
                true,
                6000,
                48000,
                true,
                50,
                150,
                ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY,
                ReputationBounds.MAX_INCIDENTS_PER_PLAYER,
                200,
                100,
                1200,
                DEFAULT_RECEIPT_RETENTION_TICKS,
                DEFAULT_UNDECLARED_AUTHORITY_MODE,
                true,
                DEFAULT_PROFILES_ENABLED,
                DEFAULT_REPEAT_CREDIT_ENABLED,
                DEFAULT_FACET_OPINION_ENABLED,
                DEFAULT_MAX_FACET_OPINION_ADJUSTMENT,
                DEFAULT_RECOGNITION_CAP,
                DEFAULT_FACET_POINT_CAP,
                DEFAULT_PROTECT_PROFILE_EVIDENCE);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public ReputationPolicy withEnabled(boolean value) {
        return toBuilder().enabled(value).build();
    }

    public ReputationPolicy withScoreDecayEnabled(boolean value) {
        return toBuilder().scoreDecayEnabled(value).build();
    }

    public ReputationPolicy withTierTitlesEnabled(boolean value) {
        return toBuilder().tierTitlesEnabled(value).build();
    }

    public ReputationPolicy withReceiptRetentionTicks(long value) {
        return toBuilder().receiptRetentionTicks(value).build();
    }

    public ReputationPolicy withUndeclaredAuthorityMode(UndeclaredAuthorityMode value) {
        return toBuilder().undeclaredAuthorityMode(value).build();
    }

    public ReputationPolicy withConversationsIntegrationEnabled(boolean value) {
        return toBuilder().conversationsIntegrationEnabled(value).build();
    }

    public ReputationPolicy withProfilesEnabled(boolean value) {
        return toBuilder().profilesEnabled(value).build();
    }

    public ReputationPolicy withRepeatCreditEnabled(boolean value) {
        return toBuilder().repeatCreditEnabled(value).build();
    }

    public ReputationPolicy withFacetOpinionEnabled(boolean value) {
        return toBuilder().facetOpinionEnabled(value).build();
    }

    public ReputationPolicy withMaxFacetOpinionAdjustment(int value) {
        return toBuilder().maxFacetOpinionAdjustment(value).build();
    }

    public ReputationPolicy withProtectProfileEvidence(boolean value) {
        return toBuilder().protectProfileEvidence(value).build();
    }

    // A mutable copy of one snapshot, so a test can vary a single field without naming twenty-one.
    public static final class Builder {

        private boolean enabled;
        private boolean scoreDecayEnabled;
        private boolean tierTitlesEnabled;
        private int minimumScore;
        private int maximumScore;
        private int villageSearchRadius;
        private int witnessRadius;
        private int maxWitnesses;
        private boolean requireWitnessLineOfSight;
        private int minRumorDelayTicks;
        private int maxRumorDelayTicks;
        private boolean villagerOpinionEnabled;
        private int opinionHearsayPercent;
        private int opinionInvolvedPercent;
        private int maxIncidentsPerCommunity;
        private int maxIncidentsPerPlayer;
        private int assaultCoalesceTicks;
        private int selfDefenseWindowTicks;
        private int reconcileOnlineIntervalTicks;
        private long receiptRetentionTicks;
        private UndeclaredAuthorityMode undeclaredAuthorityMode;
        private boolean conversationsIntegrationEnabled;
        private boolean profilesEnabled;
        private boolean repeatCreditEnabled;
        private boolean facetOpinionEnabled;
        private int maxFacetOpinionAdjustment;
        private int recognitionCap;
        private int facetPointCap;
        private boolean protectProfileEvidence;

        private Builder(ReputationPolicy source) {
            this.enabled = source.enabled;
            this.scoreDecayEnabled = source.scoreDecayEnabled;
            this.tierTitlesEnabled = source.tierTitlesEnabled;
            this.minimumScore = source.minimumScore;
            this.maximumScore = source.maximumScore;
            this.villageSearchRadius = source.villageSearchRadius;
            this.witnessRadius = source.witnessRadius;
            this.maxWitnesses = source.maxWitnesses;
            this.requireWitnessLineOfSight = source.requireWitnessLineOfSight;
            this.minRumorDelayTicks = source.minRumorDelayTicks;
            this.maxRumorDelayTicks = source.maxRumorDelayTicks;
            this.villagerOpinionEnabled = source.villagerOpinionEnabled;
            this.opinionHearsayPercent = source.opinionHearsayPercent;
            this.opinionInvolvedPercent = source.opinionInvolvedPercent;
            this.maxIncidentsPerCommunity = source.maxIncidentsPerCommunity;
            this.maxIncidentsPerPlayer = source.maxIncidentsPerPlayer;
            this.assaultCoalesceTicks = source.assaultCoalesceTicks;
            this.selfDefenseWindowTicks = source.selfDefenseWindowTicks;
            this.reconcileOnlineIntervalTicks = source.reconcileOnlineIntervalTicks;
            this.receiptRetentionTicks = source.receiptRetentionTicks;
            this.undeclaredAuthorityMode = source.undeclaredAuthorityMode;
            this.conversationsIntegrationEnabled = source.conversationsIntegrationEnabled;
            this.profilesEnabled = source.profilesEnabled;
            this.repeatCreditEnabled = source.repeatCreditEnabled;
            this.facetOpinionEnabled = source.facetOpinionEnabled;
            this.maxFacetOpinionAdjustment = source.maxFacetOpinionAdjustment;
            this.recognitionCap = source.recognitionCap;
            this.facetPointCap = source.facetPointCap;
            this.protectProfileEvidence = source.protectProfileEvidence;
        }

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder scoreDecayEnabled(boolean value) {
            this.scoreDecayEnabled = value;
            return this;
        }

        public Builder tierTitlesEnabled(boolean value) {
            this.tierTitlesEnabled = value;
            return this;
        }

        public Builder minimumScore(int value) {
            this.minimumScore = value;
            return this;
        }

        public Builder maximumScore(int value) {
            this.maximumScore = value;
            return this;
        }

        public Builder villageSearchRadius(int value) {
            this.villageSearchRadius = value;
            return this;
        }

        public Builder witnessRadius(int value) {
            this.witnessRadius = value;
            return this;
        }

        public Builder maxWitnesses(int value) {
            this.maxWitnesses = value;
            return this;
        }

        public Builder requireWitnessLineOfSight(boolean value) {
            this.requireWitnessLineOfSight = value;
            return this;
        }

        public Builder minRumorDelayTicks(int value) {
            this.minRumorDelayTicks = value;
            return this;
        }

        public Builder maxRumorDelayTicks(int value) {
            this.maxRumorDelayTicks = value;
            return this;
        }

        public Builder villagerOpinionEnabled(boolean value) {
            this.villagerOpinionEnabled = value;
            return this;
        }

        public Builder opinionHearsayPercent(int value) {
            this.opinionHearsayPercent = value;
            return this;
        }

        public Builder opinionInvolvedPercent(int value) {
            this.opinionInvolvedPercent = value;
            return this;
        }

        public Builder maxIncidentsPerCommunity(int value) {
            this.maxIncidentsPerCommunity = value;
            return this;
        }

        public Builder maxIncidentsPerPlayer(int value) {
            this.maxIncidentsPerPlayer = value;
            return this;
        }

        public Builder assaultCoalesceTicks(int value) {
            this.assaultCoalesceTicks = value;
            return this;
        }

        public Builder selfDefenseWindowTicks(int value) {
            this.selfDefenseWindowTicks = value;
            return this;
        }

        public Builder reconcileOnlineIntervalTicks(int value) {
            this.reconcileOnlineIntervalTicks = value;
            return this;
        }

        public Builder receiptRetentionTicks(long value) {
            this.receiptRetentionTicks = value;
            return this;
        }

        public Builder undeclaredAuthorityMode(UndeclaredAuthorityMode value) {
            this.undeclaredAuthorityMode = value;
            return this;
        }

        public Builder conversationsIntegrationEnabled(boolean value) {
            this.conversationsIntegrationEnabled = value;
            return this;
        }

        public Builder profilesEnabled(boolean value) {
            this.profilesEnabled = value;
            return this;
        }

        public Builder repeatCreditEnabled(boolean value) {
            this.repeatCreditEnabled = value;
            return this;
        }

        public Builder facetOpinionEnabled(boolean value) {
            this.facetOpinionEnabled = value;
            return this;
        }

        public Builder maxFacetOpinionAdjustment(int value) {
            this.maxFacetOpinionAdjustment = value;
            return this;
        }

        public Builder recognitionCap(int value) {
            this.recognitionCap = value;
            return this;
        }

        public Builder facetPointCap(int value) {
            this.facetPointCap = value;
            return this;
        }

        public Builder protectProfileEvidence(boolean value) {
            this.protectProfileEvidence = value;
            return this;
        }

        public ReputationPolicy build() {
            return new ReputationPolicy(enabled, scoreDecayEnabled, tierTitlesEnabled, minimumScore,
                    maximumScore, villageSearchRadius, witnessRadius, maxWitnesses,
                    requireWitnessLineOfSight, minRumorDelayTicks, maxRumorDelayTicks,
                    villagerOpinionEnabled, opinionHearsayPercent, opinionInvolvedPercent,
                    maxIncidentsPerCommunity, maxIncidentsPerPlayer, assaultCoalesceTicks,
                    selfDefenseWindowTicks, reconcileOnlineIntervalTicks, receiptRetentionTicks,
                    undeclaredAuthorityMode, conversationsIntegrationEnabled, profilesEnabled,
                    repeatCreditEnabled, facetOpinionEnabled, maxFacetOpinionAdjustment,
                    recognitionCap, facetPointCap, protectProfileEvidence);
        }
    }
}
