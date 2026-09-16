package dev.otectus.mcareputation.api.profile;

import dev.otectus.mcareputation.community.CommunityKey;

import java.util.UUID;

/**
 * What one villager, from what they personally know, makes of a player's public profile (§13,
 * §14.2).
 *
 * <p>{@link #knownProfile} is aggregated from the evidence this observer is actually aware of. It is
 * not the community vector scaled by a hearsay coefficient — that would leak events the speaker has
 * never learned (§13.1) — so a villager who knows nothing carries a real, empty profile rather than a
 * faded copy of the village's.
 *
 * <p>The opinion arithmetic is reported in its three parts so a disagreement is explainable (§13.4):
 * {@link #baseOpinion} is the existing knowledge-filtered standing opinion, {@link #facetAdjustment}
 * is the capped contribution of the facets this observer knows, and {@link #finalOpinion} is the
 * clamped sum. Nothing here writes warmth, familiarity, hearts or legal state (§13.3).
 *
 * @param villagerId      the observer
 * @param community       the community the question was asked in
 * @param knownProfile    the profile assembled from this observer's own knowledge
 * @param baseOpinion     the knowledge-filtered standing opinion, before facets
 * @param facetAdjustment the capped facet contribution actually applied
 * @param finalOpinion    the clamped result
 * @param involvedCount   known deeds this observer was a subject of
 * @param witnessedCount  known deeds this observer saw
 * @param hearsayCount    known deeds this observer was only told about
 * @param traitBasis      how well this observer's interpretation traits could be resolved
 * @since MCA: Reputation 0.6.0
 */
public record VillagerProfileSnapshot(UUID villagerId, CommunityKey community,
                                      ProfileSnapshot knownProfile, int baseOpinion,
                                      int facetAdjustment, int finalOpinion, int involvedCount,
                                      int witnessedCount, int hearsayCount, TraitBasis traitBasis) {

    public VillagerProfileSnapshot {
        involvedCount = Math.max(0, involvedCount);
        witnessedCount = Math.max(0, witnessedCount);
        hearsayCount = Math.max(0, hearsayCount);
        traitBasis = traitBasis == null ? TraitBasis.NEUTRAL_DEFAULT : traitBasis;
    }

    /**
     * How the observer's own interpretation weights were arrived at (§13.2's "reduced interpretation
     * basis").
     *
     * <p>Exposed rather than hidden because a neutral fallback is mandatory and a caller debugging
     * "why do these two villagers agree" deserves to know that neither of their personalities could
     * be read.
     */
    public enum TraitBasis {

        /** The observer's own traits were read and applied. */
        RESOLVED,

        /** Traits were unavailable, so neutral authored defaults applied. Never fabricated. */
        NEUTRAL_DEFAULT,

        /** Facet interpretation is switched off, so no facet weighting was applied at all. */
        DISABLED
    }

    /** Known deeds behind this view, however the observer came to know them. */
    public int knownIncidents() {
        return involvedCount + witnessedCount + hearsayCount;
    }

    /**
     * Whether this observer knows anything at all about the player.
     *
     * <p>A valid zero stays zero: §13.3 forbids falling back from it to the community profile,
     * because doing so is exactly the stranger-treated-as-friend defect.
     */
    public boolean knowsAnything() {
        return knownIncidents() > 0 || knownProfile.observed();
    }
}
