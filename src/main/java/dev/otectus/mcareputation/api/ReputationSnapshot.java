package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.community.CommunityMetadata;
import dev.otectus.mcareputation.reputation.ReputationMath;
import dev.otectus.mcareputation.reputation.ReputationTier;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everything about one player's standing with one community, in one immutable object (spec §25).
 *
 * <p>This is what the API hands to MCA: Quests' Journal, what the snapshot packet carries to the
 * client, and what MCA: Conversations reads for dialogue context. Producing it in one place means the
 * Journal, the standing screen, and a villager's opinion can never disagree about what the player's
 * standing is.
 */
public record ReputationSnapshot(
        CommunityKey community,
        CommunityMetadata metadata,
        int score,
        int baseline,
        ResourceLocation ladder,
        ReputationTier tier,
        Optional<ReputationTier> nextTier,
        Optional<String> highWaterTierId,
        Set<ResourceLocation> villageTitles,
        Set<ResourceLocation> globalTitles,
        List<ReputationIncidentView> incidents,
        int totalIncidentCount) {

    public ReputationSnapshot {
        villageTitles = villageTitles == null ? Set.of() : Set.copyOf(villageTitles);
        globalTitles = globalTitles == null ? Set.of() : Set.copyOf(globalTitles);
        incidents = incidents == null ? List.of() : List.copyOf(incidents);
    }

    public String tierId() {
        return tier.id();
    }

    /** The Conversations check bias for an axis, straight from the current tier (§30.3). */
    public int biasFor(String axis) {
        return tier.biasFor(axis);
    }

    /**
     * Progress from this tier's threshold toward the next, as {@code 0.0..1.0}. Returns {@code 1.0} at
     * the top of the ladder, so the bar reads "as good as it gets" rather than sitting empty.
     */
    public float progressToNextTier() {
        return nextTier
                .map(next -> ReputationMath.progress(score, tier.threshold(), next.threshold()))
                .orElse(1.0f);
    }

    /** Points still needed for the next tier, or empty at the top of the ladder. */
    public Optional<Integer> pointsToNextTier() {
        return nextTier.map(next -> Math.max(0, next.threshold() - score));
    }

    /** True when the player has any deed at all recorded here, as opposed to a bare imported baseline. */
    public boolean hasHistory() {
        return totalIncidentCount > 0;
    }

    /** Incidents that still count toward the score, newest first. */
    public List<ReputationIncidentView> contributingIncidents() {
        return incidents.stream().filter(ReputationIncidentView::contributes).toList();
    }

    /** Unresolved negative deeds — what "how can I make this right?" needs to find (§30.5). */
    public List<ReputationIncidentView> unresolvedNegativeIncidents() {
        return incidents.stream()
                .filter(view -> view.currentContribution() < 0)
                .filter(view -> !view.status().isResolved())
                .toList();
    }
}
