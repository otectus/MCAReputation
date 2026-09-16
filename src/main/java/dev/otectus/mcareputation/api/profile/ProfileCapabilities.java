package dev.otectus.mcareputation.api.profile;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What this build's profile feature can do <em>right now</em> (§14.4).
 *
 * <p>Separate from {@code ReputationCapabilities} on purpose. That record advertises stable support
 * — which features exist in this binary — and §14.4 forbids withdrawing a support string merely
 * because an operator switched something off. This record carries the dynamic half: whether profiles
 * are enabled, whether content is published, whether the store is read-only, and how complete the
 * history is. A companion negotiates once against the capability strings and consults this for
 * readiness.
 *
 * <p>Both lists are explicitly sorted, so a log line or a handshake comparison is reproducible.
 *
 * @param schemaVersion           the frozen-evidence schema this build writes
 * @param supported               whether this build implements profiles at all
 * @param enabled                 whether the operator has them switched on
 * @param contentPublished        whether a datapack generation with profile content is published
 * @param readOnly                whether the store refuses writes (a newer save format)
 * @param repeatCreditEnabled     whether §10's repeat-credit reduction is applied
 * @param facetOpinionEnabled     whether facets contribute to per-villager opinion
 * @param recognitionCap          the configured ceiling on public recognition
 * @param facetPointCap           the configured ceiling on a facet's magnitude
 * @param maxDominantFacets       how many facets may describe a player at once
 * @param maxFacetOpinionAdjustment the configured cap on the facet opinion term
 * @param coverage                how complete the profile history in this save is
 * @param publishedFacets         every facet id currently published, sorted
 * @param features                the profile feature strings this build implements, sorted
 * @param readinessReason         present only when something is not usable now; for operators
 * @since MCA: Reputation 0.6.0
 */
public record ProfileCapabilities(int schemaVersion, boolean supported, boolean enabled,
                                  boolean contentPublished, boolean readOnly,
                                  boolean repeatCreditEnabled, boolean facetOpinionEnabled,
                                  int recognitionCap, int facetPointCap, int maxDominantFacets,
                                  int maxFacetOpinionAdjustment, ProfileCoverage coverage,
                                  List<ResourceLocation> publishedFacets, List<String> features,
                                  Optional<String> readinessReason) {

    public ProfileCapabilities {
        coverage = coverage == null ? ProfileCoverage.MIGRATING : coverage;
        publishedFacets = sortedIds(publishedFacets);
        features = sortedStrings(features);
        readinessReason = readinessReason == null ? Optional.empty() : readinessReason;
    }

    private static List<ResourceLocation> sortedIds(List<ResourceLocation> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ResourceLocation> copy = new ArrayList<>(raw);
        copy.sort(java.util.Comparator.comparing(ResourceLocation::toString));
        return List.copyOf(copy);
    }

    private static List<String> sortedStrings(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(raw);
        copy.sort(java.util.Comparator.naturalOrder());
        return List.copyOf(copy);
    }

    /**
     * Whether a profile query asked right now can return a real answer.
     *
     * <p>Read-only is deliberately not part of this: a store that may not be written can still be
     * read honestly, and refusing to answer would lose the diagnostics an operator needs to fix it.
     */
    public boolean ready() {
        return supported && enabled && contentPublished;
    }

    public boolean has(String feature) {
        return feature != null && features.contains(feature);
    }
}
