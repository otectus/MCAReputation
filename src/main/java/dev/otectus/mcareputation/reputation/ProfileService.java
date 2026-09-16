package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.SpeakerContext;
import dev.otectus.mcareputation.api.profile.FacetValue;
import dev.otectus.mcareputation.api.profile.ProfileAvailability;
import dev.otectus.mcareputation.api.profile.ProfileCapabilities;
import dev.otectus.mcareputation.api.profile.ProfileCoverage;
import dev.otectus.mcareputation.api.profile.ProfileQuery;
import dev.otectus.mcareputation.api.profile.ProfileQueryResult;
import dev.otectus.mcareputation.api.profile.ProfileSnapshot;
import dev.otectus.mcareputation.api.profile.RecognitionValue;
import dev.otectus.mcareputation.api.profile.VillagerProfileSnapshot;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.profile.FacetDefinition;
import dev.otectus.mcareputation.profile.IncidentProfileEvidence;
import dev.otectus.mcareputation.profile.ProfileMath;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.profile.RecognitionTierSet;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.ProfileMigrationState;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side of public profiles: the one place a profile answer is produced (§14.3).
 *
 * <p>Shaped like {@link StandingAvailability} and for the same reason — every consumer that invents
 * its own answer to "what does this community know about this player" eventually invents a different
 * one. The public entry points take an explicit {@link ReputationPolicy}, store and evaluation time,
 * so the whole read model is exercisable with no server running.
 *
 * <h2>Three rules this class exists to enforce</h2>
 *
 * <ul>
 *   <li><b>Every read goes through the gate.</b> {@link ReconciliationService} is entered with
 *       {@link ReconciliationService.Intent#QUERY} for a live read and
 *       {@link ReconciliationService.Intent#INSPECT} for a diagnostic, so no profile value can be
 *       produced by a path that skipped the second clock — and a diagnostic can never be the thing
 *       that ages the record it is reporting.</li>
 *   <li><b>Nothing is created.</b> A valid player and community with no stored record answer with a
 *       synthesised neutral read model that is never saved (§14.3). An unresolvable target, a
 *       switched-off feature and an unpublished content generation are each a distinct unavailable
 *       answer rather than the same convenient zero.</li>
 *   <li><b>A speaker-scoped answer never widens.</b> Without a resolvable {@link SpeakerContext} the
 *       answer is unavailable, never the community-wide profile (§13.3). Falling back from a valid
 *       zero speaker view to a positive community view is the stranger-treated-as-friend defect.</li>
 * </ul>
 */
public final class ProfileService {

    private ProfileService() {
    }

    // ------------------------------------------------------------------
    // Capabilities
    // ------------------------------------------------------------------

    /**
     * What the profile feature can do right now (§14.4).
     *
     * <p>{@code data} may be null — a companion asking before a world is loaded gets the static half
     * of the answer. Coverage is then reported conservatively as
     * {@link ProfileCoverage#PARTIAL_LEGACY}: with no store to consult, this build cannot prove a
     * history is complete, and §14.5's absence gates must not be answered from an assumption.
     */
    public static ProfileCapabilities capabilities(@Nullable ReputationPolicy policy,
                                                   @Nullable ReputationSavedData data) {
        ProfileRegistryBundle bundle = ProfileRegistryBundle.current();
        boolean masterEnabled = policy != null && policy.enabled();
        boolean enabled = masterEnabled && policy.profilesEnabled();
        boolean published = !bundle.isEmpty();
        boolean readOnly = data != null && data.isReadOnly();
        ProfileCoverage coverage = data == null
                ? ProfileCoverage.PARTIAL_LEGACY
                : coverageOf(data);
        Optional<String> reason = Optional.empty();
        if (!masterEnabled) {
            reason = Optional.of("mcareputation is disabled in the common config");
        } else if (!enabled) {
            reason = Optional.of("profiles are disabled in the common config");
        } else if (!published) {
            reason = Optional.of("no datapack profile content is published yet");
        } else if (readOnly) {
            reason = Optional.of("the saved data was written by a newer format; this store is read-only");
        } else if (data == null) {
            reason = Optional.of("no server is running; coverage is reported conservatively");
        } else if (coverage == ProfileCoverage.MIGRATING) {
            reason = Optional.of("the profile migration pass is still running");
        }
        List<ResourceLocation> facets = new ArrayList<>(bundle.facets().keySet());
        return new ProfileCapabilities(IncidentProfileEvidence.SCHEMA_VERSION, true, enabled, published,
                readOnly, policy != null && policy.repeatCreditEnabled(),
                policy != null && policy.facetOpinionEnabled(),
                policy == null ? ProfileMath.MAX_RECOGNITION : policy.recognitionCap(),
                policy == null ? ProfileMath.MAX_FACET_POINTS : policy.facetPointCap(),
                ProfileMath.MAX_DOMINANT_FACETS,
                policy == null
                        ? ReputationPolicy.DEFAULT_MAX_FACET_OPINION_ADJUSTMENT
                        : policy.maxFacetOpinionAdjustment(),
                coverage, facets, FEATURES, reason);
    }

    /**
     * The profile feature strings this build implements.
     *
     * <p>Reported here unconditionally, which is §14.4's rule: stable support information must not be
     * withdrawn because an operator temporarily switched something off, and the enabled/published
     * flags on the same record already carry the dynamic state. The copy that
     * {@code ReputationCapabilities.features()} advertises is the live one, because a companion
     * negotiating there is deciding whether to call at all.
     */
    public static final List<String> FEATURES = List.of(
            dev.otectus.mcareputation.api.ReputationCapabilities.FEATURE_PROFILE_SNAPSHOT,
            dev.otectus.mcareputation.api.ReputationCapabilities.FEATURE_SPEAKER_PROFILE,
            dev.otectus.mcareputation.api.ReputationCapabilities.FEATURE_REPEAT_CREDIT,
            dev.otectus.mcareputation.api.ReputationCapabilities.FEATURE_PROFILED_DELIVERY,
            dev.otectus.mcareputation.api.ReputationCapabilities.FEATURE_PROFILE_CHANGE);

    /** Whether the profile feature is live enough for {@code capabilities(server)} to advertise it. */
    public static boolean live(@Nullable ReputationPolicy policy) {
        return policy != null && policy.enabled() && policy.profilesEnabled()
                && !ProfileRegistryBundle.current().isEmpty();
    }

    /** §19.3's coverage, translated to the public vocabulary. */
    public static ProfileCoverage coverageOf(@Nullable ReputationSavedData data) {
        if (data == null) {
            return ProfileCoverage.PARTIAL_LEGACY;
        }
        ProfileMigrationState.Coverage coverage = data.profileCoverage();
        return switch (coverage) {
            case COMPLETE -> ProfileCoverage.COMPLETE_SINCE_RECORD_START;
            case MIGRATING -> ProfileCoverage.MIGRATING;
            case PARTIAL_LEGACY -> ProfileCoverage.PARTIAL_LEGACY;
        };
    }

    // ------------------------------------------------------------------
    // Community profile
    // ------------------------------------------------------------------

    /**
     * One community's public profile for one player.
     *
     * @param inspect when true the gate is entered with {@code INSPECT}: stored state exactly as it
     *                is, nothing aged, nothing dirtied
     */
    public static ProfileQueryResult<ProfileSnapshot> profile(@Nullable ReputationPolicy policy,
                                                              @Nullable ReputationSavedData data,
                                                              @Nullable UUID player,
                                                              @Nullable CommunityKey community,
                                                              long gameTime, boolean inspect) {
        ProfileAvailability blocked = unavailableReason(policy, data, player, community);
        if (blocked != null) {
            return ProfileQueryResult.unavailable(blocked, reasonToken(blocked));
        }
        ProfileRegistryBundle bundle = ProfileRegistryBundle.current();
        Optional<CommunityReputationRecord> maybe = data.player(player)
                .flatMap(record -> record.community(community));
        if (maybe.isEmpty()) {
            // A valid combination with no record: a stranger, synthesised and not saved (§14.3).
            return ProfileQueryResult.available(neutral(player, community, bundle,
                    coverageOf(data), gameTime));
        }
        ReconciliationService.ReconcileOutcome outcome = ReconciliationService.reconcile(policy, data,
                player, community, gameTime, ChangeCause.DECAY,
                inspect ? ReconciliationService.Intent.INSPECT : ReconciliationService.Intent.QUERY);
        CommunityReputationRecord record = maybe.get();
        ProfileAggregator.Aggregate aggregate = ProfileAggregator.community(policy, record, bundle);
        return ProfileQueryResult.available(new ProfileSnapshot(player, community, outcome.newScore(),
                outcome.newTierId(), aggregate.recognition(), aggregate.facets(),
                aggregate.dominantFacets(), record.revision(), record.profileRevision(),
                bundle.generation(), gameTime, coverageOf(data)));
    }

    // ------------------------------------------------------------------
    // Speaker profile
    // ------------------------------------------------------------------

    /**
     * What one villager makes of a player, from the evidence that villager actually knows (§13).
     *
     * <p>A missing or unresolvable speaker is {@link ProfileAvailability#UNRESOLVED} and never the
     * community answer. A resolvable speaker who knows nothing is {@code AVAILABLE} with an empty
     * profile and a zero opinion, which is a real answer a caller must not override.
     */
    public static ProfileQueryResult<VillagerProfileSnapshot> speakerProfile(
            @Nullable ReputationPolicy policy, @Nullable ReputationSavedData data,
            @Nullable UUID player, @Nullable CommunityKey community,
            @Nullable SpeakerContext speaker, long gameTime) {
        if (speaker == null || speaker.speakerId() == null) {
            return ProfileQueryResult.unavailable(ProfileAvailability.UNRESOLVED, "no_speaker_context");
        }
        ProfileAvailability blocked = unavailableReason(policy, data, player, community);
        if (blocked != null) {
            return ProfileQueryResult.unavailable(blocked, reasonToken(blocked));
        }
        ProfileRegistryBundle bundle = ProfileRegistryBundle.current();
        ProfileCoverage coverage = coverageOf(data);
        Optional<CommunityReputationRecord> maybe = data.player(player)
                .flatMap(record -> record.community(community));
        if (maybe.isEmpty()) {
            ProfileSnapshot empty = neutral(player, community, bundle, coverage, gameTime);
            return ProfileQueryResult.available(new VillagerProfileSnapshot(speaker.speakerId(),
                    community, empty, 0, 0, 0, 0, 0, 0, traitBasis(policy)));
        }
        ReconciliationService.ReconcileOutcome outcome = ReconciliationService.reconcile(policy, data,
                player, community, gameTime, ChangeCause.DECAY, ReconciliationService.Intent.QUERY);
        CommunityReputationRecord record = maybe.get();
        ProfileAggregator.Aggregate aggregate = ProfileAggregator.speaker(policy, record, bundle,
                speaker.speakerId(), speaker.resident(), gameTime);
        ProfileSnapshot known = new ProfileSnapshot(player, community, outcome.newScore(),
                outcome.newTierId(), aggregate.recognition(), aggregate.facets(),
                aggregate.dominantFacets(), record.revision(), record.profileRevision(),
                bundle.generation(), gameTime, coverage);

        int baseOpinion = OpinionResolver.resolve(record, speaker.speakerId(), speaker.resident(),
                gameTime, policy.minRumorDelayTicks(), policy.maxRumorDelayTicks(),
                policy.opinionHearsayPercent(), policy.opinionInvolvedPercent(),
                policy.minimumScore(), policy.maximumScore()).score();
        int adjustment = facetAdjustment(policy, bundle, aggregate.facets());
        int finalOpinion = ProfileMath.clamp(baseOpinion + adjustment, policy.minimumScore(),
                policy.maximumScore());
        return ProfileQueryResult.available(new VillagerProfileSnapshot(speaker.speakerId(), community,
                known, baseOpinion, adjustment, finalOpinion, aggregate.involvedIncidents(),
                aggregate.witnessedIncidents(), aggregate.hearsayIncidents(), traitBasis(policy)));
    }

    /**
     * §13.2's facet term: the known facet values, weighted by their authored interpretation weights
     * and clamped to the configured cap.
     *
     * <p>One capped term, never three independent bonuses (R04). The weights are read at the neutral
     * default here; P6 resolves the observer's own personality through the compatibility seam and
     * reports the difference in {@link VillagerProfileSnapshot#traitBasis()}, which is why the basis
     * is part of the answer rather than an implementation detail.
     */
    private static int facetAdjustment(ReputationPolicy policy, ProfileRegistryBundle bundle,
                                       List<FacetValue> facets) {
        if (!policy.facetOpinionEnabled()) {
            return 0;
        }
        long weightedBp = 0L;
        for (FacetValue facet : facets) {
            if (!facet.observed() || facet.value() == 0) {
                continue;
            }
            FacetDefinition definition = bundle.facetOrUnknown(facet.facet());
            weightedBp = ProfileMath.add(weightedBp,
                    (long) facet.value() * definition.opinionWeightFor(null));
        }
        int cap = Math.max(0, policy.maxFacetOpinionAdjustment());
        return ProfileMath.clamp((int) (weightedBp / ProfileMath.FULL_BP), -cap, cap);
    }

    private static VillagerProfileSnapshot.TraitBasis traitBasis(ReputationPolicy policy) {
        return policy.facetOpinionEnabled()
                ? VillagerProfileSnapshot.TraitBasis.NEUTRAL_DEFAULT
                : VillagerProfileSnapshot.TraitBasis.DISABLED;
    }

    // ------------------------------------------------------------------
    // Predicates (§14.5)
    // ------------------------------------------------------------------

    /** An authored profile predicate, evaluated community-wide. */
    public static ProfileQueryResult<Boolean> matches(@Nullable ReputationPolicy policy,
                                                      @Nullable ReputationSavedData data,
                                                      @Nullable UUID player,
                                                      @Nullable CommunityKey community,
                                                      @Nullable ProfileQuery query, long gameTime) {
        if (query == null || !query.valid()) {
            return ProfileQueryResult.unavailable(ProfileAvailability.UNRESOLVED, "invalid_query");
        }
        ProfileQueryResult<ProfileSnapshot> snapshot = profile(policy, data, player, community,
                gameTime, false);
        return evaluate(snapshot, query);
    }

    /** The same predicate, evaluated against what one villager knows. Never widens (§13.3). */
    public static ProfileQueryResult<Boolean> matchesSpeaker(@Nullable ReputationPolicy policy,
                                                             @Nullable ReputationSavedData data,
                                                             @Nullable UUID player,
                                                             @Nullable CommunityKey community,
                                                             @Nullable SpeakerContext speaker,
                                                             @Nullable ProfileQuery query,
                                                             long gameTime) {
        if (query == null || !query.valid()) {
            return ProfileQueryResult.unavailable(ProfileAvailability.UNRESOLVED, "invalid_query");
        }
        ProfileQueryResult<VillagerProfileSnapshot> speakerView = speakerProfile(policy, data, player,
                community, speaker, gameTime);
        if (!speakerView.isAvailable()) {
            return new ProfileQueryResult<>(speakerView.availability(), Optional.empty(),
                    speakerView.reason());
        }
        return evaluate(ProfileQueryResult.available(speakerView.value().orElseThrow().knownProfile()),
                query);
    }

    /**
     * §14.5's truth table, applied to an already-resolved snapshot.
     *
     * <p>Failing closed here means answering {@code false} rather than unavailable: a datapack that
     * names a facet this build has never heard of has authored a gate nobody can satisfy, and an
     * authored fallback firing instead would silently open it. Refusing to answer is reserved for the
     * cases the author could not have known about — missing history, a disabled feature, an
     * unresolvable speaker.
     */
    private static ProfileQueryResult<Boolean> evaluate(ProfileQueryResult<ProfileSnapshot> resolved,
                                                        ProfileQuery query) {
        if (!resolved.isAvailable()) {
            return new ProfileQueryResult<>(resolved.availability(), Optional.empty(),
                    resolved.reason());
        }
        ProfileSnapshot snapshot = resolved.value().orElseThrow();
        if (query.dependsOnCompleteHistory() && !query.allowPartialHistory()
                && !snapshot.coverage().complete()) {
            // An upper bound or an absence claim cannot be honoured from a history that is missing
            // deeds: legacy history only ever hides evidence, so the answer would be wrong in exactly
            // the direction that opens a gate. Refuse distinguishably and let the authored fallback run.
            boolean migrating = snapshot.coverage() == ProfileCoverage.MIGRATING;
            return ProfileQueryResult.unavailable(
                    migrating ? ProfileAvailability.MIGRATING : ProfileAvailability.INCOMPLETE_HISTORY,
                    migrating ? "migrating_history" : "partial_legacy_history");
        }
        ProfileRegistryBundle bundle = ProfileRegistryBundle.current();
        RecognitionValue recognition = snapshot.recognition();
        if (query.minRecognition().isPresent()
                && recognition.value() < query.minRecognition().getAsInt()) {
            return answer(false, null);
        }
        if (query.maxRecognition().isPresent()
                && recognition.value() > query.maxRecognition().getAsInt()) {
            return answer(false, null);
        }
        if (query.minRecognitionTier().isPresent()) {
            RecognitionTierSet ladder =
                    bundle.recognitionLadderOrDefault(RecognitionTierSet.DEFAULT_ID);
            String required = query.minRecognitionTier().get();
            int requiredIndex = ladder.indexOf(required);
            if (requiredIndex < 0) {
                // Unknown tier id: fail closed (§14.5).
                return answer(false, "unknown_recognition_tier");
            }
            if (ladder.indexOf(recognition.tierId()) < requiredIndex) {
                return answer(false, null);
            }
        }
        for (ProfileQuery.FacetPredicate predicate : query.facets()) {
            if (bundle.facet(predicate.facet()).isEmpty()) {
                // Unknown facet id: fail closed, whatever the bounds say.
                return answer(false, "unknown_facet");
            }
            Optional<FacetValue> maybe = snapshot.facet(predicate.facet());
            int value;
            int evidence;
            boolean observed;
            if (maybe.isPresent()) {
                FacetValue facet = maybe.get();
                value = facet.value();
                evidence = facet.evidenceCount();
                observed = facet.observed();
            } else {
                value = 0;
                evidence = 0;
                observed = false;
            }
            if (!observed) {
                // Unobserved is not negative evidence. Only the named escape hatch reads it as
                // "no contrary evidence is known", and then the evidence requirement is moot.
                if (!predicate.allowUnobserved()) {
                    return answer(false, "unobserved_facet");
                }
            } else if (evidence < predicate.minEvidence()) {
                return answer(false, "insufficient_evidence");
            }
            if (predicate.min().isPresent() && value < predicate.min().getAsInt()) {
                return answer(false, null);
            }
            if (predicate.max().isPresent() && value > predicate.max().getAsInt()) {
                return answer(false, null);
            }
        }
        return answer(true, null);
    }

    private static ProfileQueryResult<Boolean> answer(boolean value, @Nullable String reason) {
        return new ProfileQueryResult<>(ProfileAvailability.AVAILABLE, Optional.of(value),
                Optional.ofNullable(reason));
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /** The neutral read model for a valid target with no stored record. Never saved (§14.3). */
    private static ProfileSnapshot neutral(UUID player, CommunityKey community,
                                           ProfileRegistryBundle bundle, ProfileCoverage coverage,
                                           long gameTime) {
        RecognitionTierSet ladder = bundle.recognitionLadderOrDefault(RecognitionTierSet.DEFAULT_ID);
        return new ProfileSnapshot(player, community, 0, ReputationService.currentTierId(0),
                RecognitionValue.none(ladder.tierFor(0).id()), List.of(), List.of(), 0L, 0L,
                bundle.generation(), gameTime, coverage);
    }

    /**
     * Why a profile question cannot be answered at all, or null when it can.
     *
     * <p>Ordered from the broadest cause to the narrowest so the reason an operator sees is the one
     * they can act on: the mod being off outranks profiles being off, which outranks a pack that has
     * published no content.
     */
    @Nullable
    private static ProfileAvailability unavailableReason(@Nullable ReputationPolicy policy,
                                                         @Nullable ReputationSavedData data,
                                                         @Nullable UUID player,
                                                         @Nullable CommunityKey community) {
        if (policy == null || !policy.enabled() || !policy.profilesEnabled()) {
            return ProfileAvailability.DISABLED;
        }
        if (data == null || player == null || community == null) {
            return ProfileAvailability.UNRESOLVED;
        }
        if (ProfileRegistryBundle.current().isEmpty()) {
            return ProfileAvailability.UNSUPPORTED;
        }
        return null;
    }

    private static String reasonToken(ProfileAvailability availability) {
        return switch (availability) {
            case DISABLED -> "profiles_disabled";
            case UNRESOLVED -> "unresolved_target";
            case UNSUPPORTED -> "no_profile_content";
            case READ_ONLY -> "read_only_store";
            case MIGRATING -> "migrating_history";
            case INCOMPLETE_HISTORY -> "partial_legacy_history";
            case ERROR -> "internal_error";
            case AVAILABLE -> "";
        };
    }
}
