package dev.otectus.mcareputation.api.profile;

import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;

/**
 * What a community currently holds on one facet of a player's public profile (§8, §14.2).
 *
 * <p>{@link #observed} is the field that carries the semantics of §14.5: a facet with no live
 * evidence is <b>unobserved</b>, and unobserved is not negative evidence. A "nonviolent" gate must
 * not be satisfied by the absence of a violence record, and a "trustworthy" gate must not be
 * satisfied by a facet nobody has ever evidenced. {@link ProfileQuery.FacetPredicate} defaults to
 * requiring evidence for exactly that reason.
 *
 * <p>Supporting and opposing evidence are reported separately because the value alone cannot
 * describe a mixed reputation: a player with three reliable deliveries and three broken promises has
 * the same value as one nobody has any opinion about, and only the counts tell them apart.
 *
 * @param facet              the facet id, as authored
 * @param value              the display value, clamped to the facet's authored range
 * @param rangeMin           the facet's authored minimum, so a consumer can normalize without
 *                           reading the datapack
 * @param rangeMax           the facet's authored maximum
 * @param supportingEvidence live deeds pushing this facet positive
 * @param opposingEvidence   live deeds pushing it negative
 * @param supportingStrength the summed positive magnitude, in whole display points
 * @param opposingStrength   the summed negative magnitude, as a positive number of display points
 * @param observed           whether any live evidence at all backs this facet
 * @param majorEvidence      whether at least one contributing deed was authored as major evidence
 * @param labelEligible      whether this value may currently describe the player in words (§8.2)
 * @since MCA: Reputation 0.6.0
 */
public record FacetValue(ResourceLocation facet, int value, int rangeMin, int rangeMax,
                         int supportingEvidence, int opposingEvidence,
                         int supportingStrength, int opposingStrength,
                         boolean observed, boolean majorEvidence, boolean labelEligible) {

    /** Stable order for anything that has to agree across a packet, a log and a screen. */
    public static final Comparator<FacetValue> BY_ID =
            Comparator.comparing(value -> value.facet().toString());

    public FacetValue {
        supportingEvidence = Math.max(0, supportingEvidence);
        opposingEvidence = Math.max(0, opposingEvidence);
        supportingStrength = Math.max(0, supportingStrength);
        opposingStrength = Math.max(0, opposingStrength);
    }

    /** Live deeds behind this facet, whichever way they push. */
    public int evidenceCount() {
        return supportingEvidence + opposingEvidence;
    }

    /** Whether the community holds evidence on both sides of this facet. */
    public boolean contested() {
        return supportingEvidence > 0 && opposingEvidence > 0;
    }
}
