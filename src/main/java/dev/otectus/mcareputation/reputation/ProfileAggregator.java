package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.api.profile.FacetValue;
import dev.otectus.mcareputation.api.profile.RecognitionValue;
import dev.otectus.mcareputation.incident.AwarenessResolver;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.profile.FacetDefinition;
import dev.otectus.mcareputation.profile.IncidentProfileDefinition;
import dev.otectus.mcareputation.profile.IncidentProfileEvidence;
import dev.otectus.mcareputation.profile.ProfileMath;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.profile.RecognitionTierSet;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Folds the frozen profile evidence in one ledger into the public read model (§8.3, §13.1).
 *
 * <h2>Aggregate the evidence, never scale the answer</h2>
 *
 * <p>The speaker-scoped pass differs from the community one in <em>which incidents it reads</em> and
 * in nothing else. §13.1 is explicit that a knowledge-filtered profile must not be the community
 * vector multiplied by a hearsay coefficient: that would reveal events the speaker has never learned,
 * because the shape of the vector is itself evidence. So the filter runs first, per incident, through
 * the existing {@link AwarenessResolver}, and the same summation then runs over whatever survives.
 *
 * <h2>Subunits in, public integers out</h2>
 *
 * <p>Every sum is taken in §8.3's subunits and quantized exactly once, at the end. A 25%-credited
 * half point is 1250 subunits rather than a public zero, so two of them still aggregate to one point;
 * summing rounded integers instead would silently discard the fractions repeat credit exists to keep.
 *
 * <p>Presentation is read live and quantities are not. A facet's range, labels, display order and
 * label thresholds come from the current content generation — §9.4 permits presentation to change on
 * reload — while every authored quantity comes from the deed's own frozen payload. A facet whose
 * definition has been removed from the pack keeps its stored units and degrades to
 * {@link FacetDefinition#unknown} presentation.
 *
 * <p>Pure: no server, no clock of its own, no Minecraft type beyond {@link ResourceLocation}. The
 * ledger must already have been reconciled through {@link ReconciliationService} before it arrives
 * here, which is the only reason this class can be free of an evaluation time.
 */
public final class ProfileAggregator {

    private ProfileAggregator() {
    }

    /**
     * One folded view of a ledger.
     *
     * @param recognition        the public recognition value and its ladder tier
     * @param facets             every facet with retained evidence, ordered by id
     * @param dominantFacets     §8.2's at-most-three label-eligible facets, strongest first
     * @param involvedIncidents  deeds the observer was a subject of; zero for a community-wide fold
     * @param witnessedIncidents deeds the observer saw
     * @param hearsayIncidents   deeds the observer was only told about
     */
    public record Aggregate(RecognitionValue recognition, List<FacetValue> facets,
                            List<ResourceLocation> dominantFacets, int involvedIncidents,
                            int witnessedIncidents, int hearsayIncidents) {

        public int knownIncidents() {
            return involvedIncidents + witnessedIncidents + hearsayIncidents;
        }
    }

    /** The community-wide fold: every retained deed's public evidence, whoever knows about it. */
    public static Aggregate community(ReputationPolicy policy, CommunityReputationRecord record,
                                      ProfileRegistryBundle bundle) {
        return fold(policy, record, bundle, null, false, 0L, 0, 0, null);
    }

    /**
     * The same fold with one deed left out, which is how the publication path learns what a deed
     * changed (§15).
     *
     * <p>Folding twice rather than subtracting the payload from the total: the public integers are
     * clamped and quantized, so "the value without this deed" is not the value minus the deed's
     * contribution, and a cap or a truncation would make the published transition disagree with the
     * very next query.
     */
    public static Aggregate communityExcluding(ReputationPolicy policy,
                                               CommunityReputationRecord record,
                                               ProfileRegistryBundle bundle,
                                               @Nullable UUID excludedIncident) {
        return fold(policy, record, bundle, null, false, 0L, 0, 0, excludedIncident);
    }

    /**
     * The knowledge-filtered fold for one observer (§13.1).
     *
     * <p>A villager who is not a resident hears no rumours and keeps only what they witnessed or were
     * part of, exactly as {@link AwarenessResolver} decides it for the scalar opinion — one rule for
     * both channels, so the two can never describe different knowledge.
     */
    public static Aggregate speaker(ReputationPolicy policy, CommunityReputationRecord record,
                                    ProfileRegistryBundle bundle, UUID villager, boolean resident,
                                    long gameTime) {
        return fold(policy, record, bundle, villager, resident, gameTime, policy.minRumorDelayTicks(),
                policy.maxRumorDelayTicks(), null);
    }

    private static Aggregate fold(ReputationPolicy policy, CommunityReputationRecord record,
                                  ProfileRegistryBundle bundle, @Nullable UUID villager,
                                  boolean resident, long gameTime, int minRumorDelayTicks,
                                  int maxRumorDelayTicks, @Nullable UUID excludedIncident) {
        ProfileRegistryBundle content = bundle == null ? ProfileRegistryBundle.EMPTY : bundle;
        long recognitionSubunits = 0L;
        int recognitionEvidence = 0;
        int involved = 0;
        int witnessed = 0;
        int hearsay = 0;
        // Insertion-ordered while accumulating; the final list is sorted explicitly below, because a
        // read model that reaches a packet may not depend on insertion order either.
        Map<ResourceLocation, Accumulator> accumulators = new LinkedHashMap<>();

        if (record != null) {
            for (IncidentRecord incident : record.incidents()) {
                if (excludedIncident != null && excludedIncident.equals(incident.id())) {
                    continue;
                }
                if (!incident.hasProfileEvidence() || incident.isSuperseded()
                        || incident.status() == IncidentStatus.DISPROVEN) {
                    // A folded record's weight was absorbed by its successor and a disproven one
                    // evidences nothing; both keep their frozen units on disk (§11.3, §12.1).
                    continue;
                }
                if (villager != null && !AwarenessResolver.knows(incident, villager, resident, gameTime,
                        minRumorDelayTicks, maxRumorDelayTicks)) {
                    continue;
                }
                IncidentProfileEvidence evidence = incident.profileEvidence().orElseThrow();
                if (villager != null) {
                    if (AwarenessResolver.isKnowingSubject(incident, villager)) {
                        involved++;
                    } else if (incident.isWitness(villager)) {
                        witnessed++;
                    } else {
                        hearsay++;
                    }
                }
                boolean major = majorEvidence(content, evidence.profileId());
                for (IncidentProfileEvidence.Channel channel : evidence.channels()) {
                    if (channel.isRecognition()) {
                        recognitionSubunits = ProfileMath.add(recognitionSubunits, channel.current());
                        if (channel.current() != 0L) {
                            recognitionEvidence++;
                        }
                        continue;
                    }
                    ResourceLocation facet = channel.facet().orElseThrow();
                    accumulators.computeIfAbsent(facet, Accumulator::new).add(channel, major);
                }
            }
        }

        RecognitionTierSet ladder = content.recognitionLadderOrDefault(RecognitionTierSet.DEFAULT_ID);
        int recognitionCap = policy == null
                ? ProfileMath.MAX_RECOGNITION
                : Math.max(0, policy.recognitionCap());
        int recognition = Math.min(ProfileMath.publicRecognition(recognitionSubunits), recognitionCap);
        RecognitionValue recognitionValue =
                new RecognitionValue(recognition, ladder.tierFor(recognition).id(), recognitionEvidence);

        int facetCap = policy == null ? ProfileMath.MAX_FACET_POINTS : Math.max(0, policy.facetPointCap());
        List<FacetValue> facets = new ArrayList<>(accumulators.size());
        List<ProfileMath.FacetEvidence> eligible = new ArrayList<>(accumulators.size());
        for (Accumulator accumulator : accumulators.values()) {
            FacetDefinition definition = content.facetOrUnknown(accumulator.facet);
            FacetValue value = accumulator.toValue(definition, facetCap);
            facets.add(value);
            if (value.labelEligible()) {
                eligible.add(new ProfileMath.FacetEvidence(accumulator.facet, accumulator.subunits,
                        definition.range().magnitude(), definition.displayOrder(),
                        value.evidenceCount(), accumulator.major));
            }
        }
        // One comparator for dominance, shared with the client and the diagnostics, so the three can
        // never disagree about which traits describe a player (§8.2).
        List<ResourceLocation> dominant = new ArrayList<>();
        for (ProfileMath.FacetEvidence entry : ProfileMath.dominant(eligible, 1, 1,
                ProfileMath.MAX_DOMINANT_FACETS)) {
            dominant.add(entry.facet());
        }
        facets.sort(FacetValue.BY_ID);
        return new Aggregate(recognitionValue, List.copyOf(facets), List.copyOf(dominant), involved,
                witnessed, hearsay);
    }

    /**
     * Whether the profile behind a deed authored it as major evidence (§8.2).
     *
     * <p>Read from the live content rather than the frozen payload, deliberately: label eligibility is
     * presentation, which §9.4 allows a reload to change, and the payload carries no such flag. A
     * profile removed from the pack degrades to "not major", which can only ever make a label harder
     * to earn.
     */
    private static boolean majorEvidence(ProfileRegistryBundle content, ResourceLocation profileId) {
        return content.profile(profileId).map(IncidentProfileDefinition::majorEvidence).orElse(false);
    }

    /** One facet's running totals. Mutable and package-local; nothing outside this fold sees it. */
    private static final class Accumulator {

        private final ResourceLocation facet;
        private long subunits;
        private long supportingSubunits;
        private long opposingSubunits;
        private int supporting;
        private int opposing;
        private boolean major;

        private Accumulator(ResourceLocation facet) {
            this.facet = facet;
        }

        private void add(IncidentProfileEvidence.Channel channel, boolean majorEvidence) {
            long current = channel.current();
            subunits = ProfileMath.add(subunits, current);
            if (current > 0L) {
                supporting++;
                supportingSubunits = ProfileMath.add(supportingSubunits, current);
                major |= majorEvidence;
            } else if (current < 0L) {
                opposing++;
                opposingSubunits = ProfileMath.add(opposingSubunits, -current);
                major |= majorEvidence;
            }
        }

        private FacetValue toValue(FacetDefinition definition, int facetCap) {
            FacetDefinition.Range range = definition.range();
            int min = Math.max(range.min(), -facetCap);
            int max = Math.min(range.max(), facetCap);
            int value = ProfileMath.publicValue(subunits, min, max);
            int evidence = supporting + opposing;
            boolean observed = evidence > 0;
            // Eligibility needs magnitude and evidence, or one authored major deed (§8.2). Zero is
            // never described as either trait, whatever the thresholds say.
            boolean labelEligible = observed && value != 0
                    && Math.abs(value) >= Math.max(1, definition.labelMinMagnitude())
                    && (major || evidence >= Math.max(1, definition.labelMinEvidence()));
            return new FacetValue(facet, value, min, max, supporting, opposing,
                    ProfileMath.toPoints(supportingSubunits), ProfileMath.toPoints(opposingSubunits),
                    observed, major, labelEligible);
        }
    }
}
