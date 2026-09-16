package dev.otectus.mcareputation.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What this build of Reputation can currently do, for a companion to negotiate against (§6
 * "Effective capabilities").
 *
 * <p>Answers the three questions a bridge otherwise has to guess: which optional operations exist
 * ({@link #features}), which core kinds this mod is still detecting itself ({@link #nativeKinds}),
 * and who took the rest ({@link #claimants}). {@link #readinessReason} is present only when something
 * is not usable right now, and is meant for an operator's eyes.
 *
 * @since MCA: Reputation 0.4.1
 */
public record ReputationCapabilities(int apiVersion, boolean enabled, boolean decayEnabled,
                                     boolean opinionEnabled, Set<String> features,
                                     Set<CoreIncidentKind> nativeKinds,
                                     Map<CoreIncidentKind, Optional<ResourceLocation>> claimants,
                                     Optional<String> readinessReason) {

    /** The canonical standing-change envelope and its cause/quiet contract are implemented. */
    public static final String FEATURE_STANDING_CHANGE = "standing_change";

    /** Effective, per-kind authority claims are resolved and inspectable. */
    public static final String FEATURE_EFFECTIVE_AUTHORITY = "effective_authority";

    /** A refused duplicate reports the identity of the incident that already exists. */
    public static final String FEATURE_DUPLICATE_IDENTITY = "duplicate_identity";

    /** One deed can absorb an earlier one through {@link McaReputationApi#recordSuperseding}. */
    public static final String FEATURE_SUPERSEDE = "supersede";

    /** A request's game time is an occurrence time, and a backdated deed arrives already aged. */
    public static final String FEATURE_OCCURRENCE_TIME = "occurrence_time";

    /** Keyed deliveries leave a replayable receipt, so exactly-once delivery is recoverable. */
    public static final String FEATURE_RECEIPTS = "receipts";

    /** {@link McaReputationApi#deliver} exists: a request plus an operation identity. */
    public static final String FEATURE_DELIVERY = "delivery";

    /** Receipt and incident lookups are strictly read-only and never grow the save. */
    public static final String FEATURE_READ_ONLY_LOOKUP = "read_only_lookup";

    /** Selectors can be evaluated against a named speaker, and fail closed without one. */
    public static final String FEATURE_SPEAKER_QUERY = "speaker_query";

    /** {@link McaReputationApi#resolveBound} exists: an exact incident, resolved exactly once. */
    public static final String FEATURE_BOUND_RESOLUTION = "bound_resolution";

    /** Title grants, revocations and whole-state snapshots reach registered mirrors. */
    public static final String FEATURE_TITLE_SYNC = "title_sync";

    /** High-water tiers can be read per ladder rather than only for the default one. */
    public static final String FEATURE_LADDER_HIGH_WATER = "ladder_high_water";

    /** {@link McaReputationApi#gossipStory} exists: enriched gossip with a semantic revision. */
    public static final String FEATURE_GOSSIP_STORY = "gossip_story";

    // --- public profiles (0.6.0, §14.4) -------------------------------------
    //
    // Advertised only when the implementation and its payload handling exist, and — unlike the rows
    // above — only while the feature is actually live: profiles switched off or content unpublished
    // means a query cannot answer, so claiming support would make a companion skip the authored
    // fallback it needs. The stable-support half of the question lives in ProfileCapabilities, which
    // reports supported/enabled/published separately and keeps saying "supported" through a
    // temporary disablement (§14.4).

    /**
     * {@link McaReputationApi#getProfileDetailed} exists: recognition, facets and coverage for one
     * community, through the canonical reconciliation gate.
     */
    public static final String FEATURE_PROFILE_SNAPSHOT = "profile_snapshot_v1";

    /**
     * {@link McaReputationApi#getVillagerProfileDetailed} exists: a profile assembled from one
     * observer's own knowledge, which never falls back to the community answer.
     */
    public static final String FEATURE_SPEAKER_PROFILE = "speaker_profile_v1";

    /** §10's repeat-credit accounting is implemented and explained on an accepted delivery. */
    public static final String FEATURE_REPEAT_CREDIT = "repeat_credit_v1";

    /**
     * {@link McaReputationApi#deliverProfiled} exists: an authored profile selection and a
     * profile-aware supersession through the same canonical commit as {@code deliver}.
     */
    public static final String FEATURE_PROFILED_DELIVERY = "profiled_delivery_v1";

    /**
     * {@code ReputationProfileChangedEvent} is posted, including for a profile-only change no
     * standing event can describe.
     */
    public static final String FEATURE_PROFILE_CHANGE = "profile_change_v1";

    public ReputationCapabilities {
        features = features == null ? Set.of() : Set.copyOf(features);
        nativeKinds = nativeKinds == null ? Set.of() : Set.copyOf(nativeKinds);
        claimants = claimants == null ? Map.of() : Map.copyOf(claimants);
        readinessReason = readinessReason == null ? Optional.empty() : readinessReason;
    }

    public boolean has(String feature) {
        return features.contains(feature);
    }
}
