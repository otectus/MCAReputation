package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;

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
        boolean conversationsIntegrationEnabled) {

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
                McaReputationConfig.conversationsIntegrationEnabled());
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
                true);
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

        public ReputationPolicy build() {
            return new ReputationPolicy(enabled, scoreDecayEnabled, tierTitlesEnabled, minimumScore,
                    maximumScore, villageSearchRadius, witnessRadius, maxWitnesses,
                    requireWitnessLineOfSight, minRumorDelayTicks, maxRumorDelayTicks,
                    villagerOpinionEnabled, opinionHearsayPercent, opinionInvolvedPercent,
                    maxIncidentsPerCommunity, maxIncidentsPerPlayer, assaultCoalesceTicks,
                    selfDefenseWindowTicks, reconcileOnlineIntervalTicks, receiptRetentionTicks,
                    undeclaredAuthorityMode, conversationsIntegrationEnabled);
        }
    }
}
